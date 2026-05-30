package tterrag1112.life_in_the_village.Village.Markets.Complex;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.MarketComplexRegistry;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.MarketComplexSpec;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStallPlacer;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Temporary event stalls — the farmer's-market connection (merchant arc
 * Phase 2d). On a market/fair event start, recruits surplus-producing
 * NPCs into real {@link MarketStall} records placed on the market pad via
 * the 2b allocator, stocked from each producer's building, owned by the
 * producer, tagged with the event id. On event end (and via load-time
 * {@link #reconcile reconciliation}) the stalls are torn down: goods
 * returned, structure cleared to the pad, record removed.
 *
 * <p><b>Leak-proofing</b> is the central concern. An event stall is
 * persisted with its {@code eventId}, so it survives restart/unload like
 * any stall — and {@link #reconcile} (run once at server start) tears down
 * any event stall whose event no longer exists, covering: normal end
 * (handled by {@link #teardown}), crash/restart mid-event, and
 * chunk-unload (the record persists; reconciliation catches orphans on
 * next load).
 *
 * <p><b>Phase 3 contract.</b> Each event stall exposes the standard 2c
 * work-post ({@code MarketWorkPost.forStall}) and is owned by the
 * recruited producer; Phase 3 occupancy walks that producer to the stall
 * to man it. Until Phase 3 ships, stalls appear + stocked but unmanned.
 */
public final class EventStallManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventStallManager.class);

    /** Max producers recruited per event (also bounded by pad capacity). */
    private static final int MAX_RECRUITS = 6;
    /** Items moved from a producer's building into its event stall chest. */
    private static final int STOCK_PER_STALL = 16;

    private EventStallManager() {}

    /** Event types that get producer stalls (the markets + fairs). */
    public static boolean wantsProducerStalls(VillageEvent.EventType type) {
        return switch (type) {
            case MARKET_DAY, SUMMER_MARKET, VILLAGE_FAIR -> true;
            // GUILD_FAIR is guild-scoped, not a producer farmer's-market; the
            // wandering trader + cosmetics still apply. Everything else: no.
            default -> false;
        };
    }

    // =========================================================================
    // Recruit (event start)
    // =========================================================================

    public static void recruit(ServerLevel level, VillageEvent event,
                               Village village, VillageSavedData data) {
        if (!wantsProducerStalls(event.getType())) return;
        Building market = findMarket(village, data);
        if (market == null) return;

        MarketComplexSpec spec = MarketComplexRegistry
                .get(cultureId(level, village), BuildingType.MARKET).orElse(null);
        if (spec == null || spec.stallPool().isEmpty()) return;

        // Recompute the bounded pad region (2a is deterministic — not
        // persisted), excluding other buildings as obstacles.
        var box = market.getShape().toAABB();
        int padY = market.getShape().getOrigin().getY();
        BlockPos centre = new BlockPos((int) box.getCenter().x, padY, (int) box.getCenter().z);
        MarketComplexPlanner.PlanResult pad = MarketComplexPlanner.plan(
                new MarketComplexPlanner.Input(
                        centre, market.getShape().getWidth(), market.getShape().getLength(),
                        padY, cultureId(level, village), BuildingType.MARKET,
                        obstacles(market, village, data)));
        if (!pad.success()) {
            LOGGER.info("[EventStallManager] no pad for event stalls at {}: {}",
                    market.getName(), pad.detail());
            return;
        }

        StallVariant variant = spec.stallPool().get(0);
        List<BoundingBox> occupied = existingOccupancy(level, market, data);
        List<TownspersonMob> producers = eligibleProducers(level, village, data);

        int baseSlot = data.getStallsForMarket(market.getId()).size();
        int placed = 0;
        for (TownspersonMob producer : producers) {
            if (placed >= MAX_RECRUITS) break;
            Optional<MarketStall> s = StallAllocator.place(
                    level, market, pad.region(), pad.buildingBounds(), pad.padY(),
                    variant, producer.getUUID(), MarketStall.OwnerType.NPC,
                    producer.getNpcName(), Long.MAX_VALUE, baseSlot + placed, occupied);
            if (s.isEmpty()) break; // pad full
            MarketStall stall = s.get();
            stall.setEventId(event.getId());
            stockFromProducer(level, producer, stall, data);
            data.addMarketStall(stall);
            placed++;
        }
        if (placed > 0) {
            LOGGER.info("[EventStallManager] recruited {} producer stall(s) for {} at {}",
                    placed, event.getType(), market.getName());
        }
    }

    // =========================================================================
    // Teardown (event end)
    // =========================================================================

    /** Tears down every stall belonging to {@code eventId}. */
    public static void teardown(ServerLevel level, VillageSavedData data,
                                Village village, UUID eventId) {
        Building market = findMarket(village, data);
        List<MarketStall> stalls = market == null ? List.of()
                : new ArrayList<>(data.getStallsForMarket(market.getId()));
        int removed = 0;
        for (MarketStall stall : stalls) {
            if (!eventId.equals(stall.getEventId())) continue;
            returnGoodsToOwner(level, stall, data);
            MarketStallPlacer.reclaimStall(level, stall); // clears structure to pad
            data.removeMarketStall(stall.getStallId());
            removed++;
        }
        if (removed > 0) {
            LOGGER.info("[EventStallManager] tore down {} event stall(s) for event {}",
                    removed, eventId);
        }
    }

    /**
     * Load-time reconciliation: tear down any event-scoped stall whose
     * event no longer exists (crash/restart mid-event, or a teardown that
     * never ran). Idempotent; safe to call on every server start.
     */
    public static void reconcile(ServerLevel level, VillageSavedData data) {
        Set<UUID> liveEvents = new HashSet<>();
        for (Village v : data.getAllVillages()) {
            for (VillageEvent e : data.getActiveEventsForVillage(v.getId())) {
                liveEvents.add(e.getId());
            }
        }
        int removed = 0;
        for (Village v : data.getAllVillages()) {
            Building market = findMarket(v, data);
            if (market == null) continue;
            for (MarketStall stall : new ArrayList<>(data.getStallsForMarket(market.getId()))) {
                if (!stall.isEventScoped()) continue;
                if (liveEvents.contains(stall.getEventId())) continue; // event still running
                returnGoodsToOwner(level, stall, data);
                MarketStallPlacer.reclaimStall(level, stall);
                data.removeMarketStall(stall.getStallId());
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.info("[EventStallManager] reconciliation removed {} orphaned event stall(s)",
                    removed);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static List<TownspersonMob> eligibleProducers(ServerLevel level,
                                                          Village village, VillageSavedData data) {
        var bounds = village.getBounds(data).map(b -> b.inflate(32)).orElse(null);
        if (bounds == null) return List.of();
        return level.getEntitiesOfClass(TownspersonMob.class, bounds, mob ->
                isProducer(mob.getProfession())
                        && mob.getAssignedVillageName()
                                .map(n -> n.equals(village.getName())).orElse(false)
                        && hasSurplus(level, mob, data));
    }

    private static boolean isProducer(Profession p) {
        return switch (p) {
            case FARMER, BAKER, WEAVER, CARPENTER,
                 BLACKSMITH, MINER, STONEMASON -> true;
            default -> false;
        };
    }

    private static boolean hasSurplus(ServerLevel level, TownspersonMob producer,
                                      VillageSavedData data) {
        Building b = producer.getAssignedBuildingId().flatMap(data::getBuildingById).orElse(null);
        if (b == null) return false;
        for (var c : BuildingStorageAccess.findInventories(level, b)) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (!c.getItem(i).isEmpty()) return true;
            }
        }
        return false;
    }

    /** Moves up to {@link #STOCK_PER_STALL} items from the producer's
     *  building into the stall chest. */
    private static void stockFromProducer(ServerLevel level, TownspersonMob producer,
                                          MarketStall stall, VillageSavedData data) {
        Building b = producer.getAssignedBuildingId().flatMap(data::getBuildingById).orElse(null);
        if (b == null) return;
        BlockPos cp = stall.getChestPos();
        if (cp == null || cp.equals(BlockPos.ZERO)) return;
        BlockEntity be = level.getBlockEntity(cp);
        if (!(be instanceof net.minecraft.world.Container chest)) return;

        int moved = 0;
        outer:
        for (var c : BuildingStorageAccess.findInventories(level, b)) {
            for (int i = 0; i < c.getContainerSize() && moved < STOCK_PER_STALL; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                int take = Math.min(s.getCount(), STOCK_PER_STALL - moved);
                ItemStack portion = s.copy();
                portion.setCount(take);
                // Stall chest only (hub=null); whatever doesn't fit stays in
                // the producer's building, so nothing is duplicated or lost.
                StallGoods.store(level, null, stall, portion);
                int movedNow = take - portion.getCount();
                s.shrink(movedNow);
                moved += movedNow;
                if (movedNow == 0) continue; // stall full — try next slot/inv
                if (moved >= STOCK_PER_STALL) break outer;
            }
        }
    }

    /** Returns the stall chest's contents to the owner's building (or
     *  market hub) at teardown so nothing is destroyed. */
    private static void returnGoodsToOwner(ServerLevel level, MarketStall stall,
                                           VillageSavedData data) {
        BlockPos cp = stall.getChestPos();
        if (cp == null || cp.equals(BlockPos.ZERO)) return;
        BlockEntity be = level.getBlockEntity(cp);
        if (!(be instanceof net.minecraft.world.Container chest)) return;
        Building dest = TownspersonMob.findByUUID(level, stall.getOwnerUUID())
                .flatMap(m -> m.getAssignedBuildingId()).flatMap(data::getBuildingById)
                .orElse(null);
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack s = chest.getItem(i);
            if (s.isEmpty()) continue;
            if (dest != null) BuildingStorageAccess.storeItem(level, dest, s.copy());
            chest.setItem(i, ItemStack.EMPTY);
        }
    }

    private static List<BoundingBox> existingOccupancy(ServerLevel level, Building market,
                                                       VillageSavedData data) {
        // Conservative: event stalls pack against existing stalls by their
        // recorded origin as a 3×3 footprint proxy (exact box needs the
        // template; the allocator's own gap checks prevent overlap of newly
        // placed ones, and permanent stalls were seeded with spacing).
        List<BoundingBox> boxes = new ArrayList<>();
        for (MarketStall s : data.getStallsForMarket(market.getId())) {
            if (!s.isActive()) continue;
            BlockPos o = s.getStallOrigin();
            if (o == null) continue;
            boxes.add(new BoundingBox(o.getX() - 1, o.getY(), o.getZ() - 1,
                    o.getX() + 1, o.getY() + 3, o.getZ() + 1));
        }
        return boxes;
    }

    private static List<Polygon.AABB> obstacles(Building market, Village village,
                                                VillageSavedData data) {
        List<Polygon.AABB> obs = new ArrayList<>();
        for (UUID id : village.getBuildingIds()) {
            data.getBuildingById(id).ifPresent(b -> {
                if (b.getId().equals(market.getId())) return;
                var box = b.getShape().toAABB();
                obs.add(new Polygon.AABB((int) Math.floor(box.minX), (int) Math.floor(box.minZ),
                        (int) Math.ceil(box.maxX), (int) Math.ceil(box.maxZ)));
            });
        }
        return obs;
    }

    private static Building findMarket(Village village, VillageSavedData data) {
        if (village == null) return null;
        return village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent).map(Optional::get)
                .filter(b -> b.getType() == BuildingType.MARKET)
                .findFirst().orElse(null);
    }

    private static String cultureId(ServerLevel level, Village village) {
        return tterrag1112.life_in_the_village.Cultures.CultureResolver
                .of(level, village).id();
    }
}
