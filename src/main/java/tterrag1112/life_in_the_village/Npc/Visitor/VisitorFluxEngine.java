package tterrag1112.life_in_the_village.Npc.Visitor;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.ModEntities;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4 doc 29 — daily visitor-flux pass.
 *
 * <h3>Per-village daily tick</h3>
 * <ol>
 *   <li>Compute capacity via
 *   {@link VillageVisitorCapacity#compute}.</li>
 *   <li>Despawn visitors whose itinerary is complete or whose 7-day
 *   ceiling has passed.</li>
 *   <li>Roll arrivals — guaranteed integer slice + fractional roll.</li>
 *   <li>For each new arrival: weighted-pick a {@link VisitorType},
 *   build a basic single-stop itinerary keyed off the type, spawn a
 *   {@link TownspersonMob} flagged as a visitor at the village edge.</li>
 * </ol>
 *
 * <p>Spawning uses the existing {@link ModEntities#TOWNSPERSON}
 * registry — no separate entity class. Each visitor's
 * {@link VisitorState} component carries the type / itinerary /
 * arrival metadata; {@link VisitorGoal} drives behaviour.</p>
 */
public final class VisitorFluxEngine {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long DAY = 24000L;

    private VisitorFluxEngine() {}

    public static void dailyTick(ServerLevel level) {
        VillageSavedData vdata = VillageSavedData.get(level);
        long now = level.getGameTime();
        for (Village v : vdata.getAllVillages()) {
            tickVillage(level, v, vdata, now);
        }
    }

    private static void tickVillage(ServerLevel level, Village village,
                                    VillageSavedData vdata, long now) {
        VillageVisitorCapacity cap = VillageVisitorCapacity.compute(village, vdata);
        AABB bounds = village.getBounds(vdata).orElse(null);
        if (bounds == null) return;

        List<TownspersonMob> active = level.getEntitiesOfClass(
                TownspersonMob.class, bounds.inflate(16),
                m -> m.isAlive() && m.isVisitor()
                        && m.getAssignedVillageName()
                                .map(n -> n.equals(village.getName()))
                                .orElse(false));

        // 1. Despawn finished / expired visitors. Count survivors in
        //    the same pass so the capacity check sees the right
        //    number — the original separate-count path used the
        //    post-discard list as the initial size and re-subtracted
        //    the same mobs (shouldDespawn still returned true for the
        //    discarded entities), double-counting their removal.
        int currentCount = 0;
        for (TownspersonMob mob : active) {
            if (mob.getVisitorState().shouldDespawn(now)) {
                mob.discard();
            } else {
                currentCount++;
            }
        }

        if (currentCount >= cap.maxConcurrent()) return;

        // 2. Roll arrivals. arrivalRatePerDay is split into a
        //    guaranteed integer slice + a fractional roll.
        RandomSource rng = level.getRandom();
        float remaining = cap.arrivalRatePerDay();
        int spawnSlots  = cap.maxConcurrent() - currentCount;
        int spawned     = 0;
        while (spawned < spawnSlots && remaining > 0f) {
            boolean shouldSpawn = remaining >= 1f
                    || rng.nextFloat() < remaining;
            if (!shouldSpawn) break;
            VisitorType type = pickType(cap.typeWeights(), rng);
            spawnVisitor(level, village, vdata, type, bounds, now, rng);
            spawned++;
            remaining -= 1f;
        }
    }

    /** Phase 4 doc 25 hook: replaces
     *  {@code VillageSimEngine.estimateVisitorFlux} 0-stub. Returns
     *  the expected daily COIN_INFLUX from the village's visitor
     *  capacity * average wallet spend per visitor. */
    public static float estimateFlux(Village village, VillageSavedData data) {
        if (village == null) return 0f;
        VillageVisitorCapacity cap = VillageVisitorCapacity.compute(village, data);
        // Average wallet spend per visitor: half the midpoint across
        // every type, weighted equally. Visitors typically spend most
        // of their wallet by the time they leave; we estimate 60%.
        float avgWallet = 0f;
        int count = 0;
        for (VisitorType t : VisitorType.values()) {
            avgWallet += (t.walletMin() + t.walletMax()) / 2f;
            count++;
        }
        float averageSpend = (avgWallet / Math.max(1, count)) * 0.6f;
        return cap.arrivalRatePerDay() * averageSpend;
    }

    // ── Spawning ──────────────────────────────────────────────────────────

    private static VisitorType pickType(Map<VisitorType, Float> weights,
                                        RandomSource rng) {
        float total = 0f;
        for (float w : weights.values()) total += Math.max(0f, w);
        if (total <= 0f) return VisitorType.TRAVELER;
        float roll = rng.nextFloat() * total;
        float cum  = 0f;
        for (var e : weights.entrySet()) {
            cum += Math.max(0f, e.getValue());
            if (roll <= cum) return e.getKey();
        }
        return VisitorType.TRAVELER;
    }

    /** Public entry point used by /visitor spawn. Returns the spawned
     *  mob or empty when no suitable spawn position exists. */
    public static Optional<TownspersonMob> spawnVisitor(ServerLevel level, Village village,
                                                        VillageSavedData vdata,
                                                        VisitorType type) {
        AABB bounds = village.getBounds(vdata).orElse(null);
        if (bounds == null) return Optional.empty();
        return Optional.ofNullable(spawnVisitor(level, village, vdata, type,
                bounds, level.getGameTime(), level.getRandom()));
    }

    private static TownspersonMob spawnVisitor(ServerLevel level, Village village,
                                               VillageSavedData vdata, VisitorType type,
                                               AABB bounds, long now, RandomSource rng) {
        BlockPos edge = pickEdgePosition(level, bounds, rng);
        if (edge == null) return null;
        TownspersonMob npc = ModEntities.TOWNSPERSON.get()
                .create(level, EntitySpawnReason.MOB_SUMMONED);
        if (npc == null) return null;
        npc.setPos(edge.getX() + 0.5, edge.getY(), edge.getZ() + 0.5);
        npc.finalizeSpawn(level,
                level.getCurrentDifficultyAt(edge),
                EntitySpawnReason.MOB_SUMMONED, null);
        npc.setProfession(type.underlyingProfession());
        // Assign the visitor to the village so the per-village scans
        // (gossip, sim, despawn) treat it as a transient resident.
        // The building id stays null — visitors don't own a workplace.
        npc.assignToBuilding(/* buildingId */ null, village.getName());

        // Give the visitor a starting wallet [walletMin, walletMax].
        long bronze = type.walletMin() + rng.nextInt(
                Math.max(1, type.walletMax() - type.walletMin() + 1));
        npc.getWallet().receive(bronze);

        // Plan a basic single-stop itinerary based on type.
        List<VisitorItinerary> itin = planItinerary(village, vdata, type);
        long expectedDeparture = now + Math.max(DAY, itin.stream()
                .mapToLong(s -> s.expectedDurationTicks()).sum() + DAY);
        npc.getVisitorState().beginVisit(type, /* origin */ null,
                now, expectedDeparture, itin);

        level.addFreshEntity(npc);
        LOGGER.info("[VisitorFluxEngine] Spawned {} at {} in {} ({} br, {} stops)",
                type, edge, village.getName(), bronze, itin.size());
        return npc;
    }

    /** Picks a block position somewhere along the village bounds'
     *  perimeter. Random side; random slot along that side. */
    private static BlockPos pickEdgePosition(ServerLevel level, AABB bounds,
                                             RandomSource rng) {
        if (bounds == null) return null;
        int minX = (int) Math.floor(bounds.minX);
        int maxX = (int) Math.ceil(bounds.maxX);
        int minZ = (int) Math.floor(bounds.minZ);
        int maxZ = (int) Math.ceil(bounds.maxZ);
        int side = rng.nextInt(4);
        int x, z;
        switch (side) {
            case 0 -> { x = minX; z = minZ + rng.nextInt(Math.max(1, maxZ - minZ)); }
            case 1 -> { x = maxX; z = minZ + rng.nextInt(Math.max(1, maxZ - minZ)); }
            case 2 -> { x = minX + rng.nextInt(Math.max(1, maxX - minX)); z = minZ; }
            default -> { x = minX + rng.nextInt(Math.max(1, maxX - minX)); z = maxZ; }
        }
        // Drop to surface — height map look-up.
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                x, z);
        return new BlockPos(x, y, z);
    }

    /** Builds a one-stop itinerary at the type's preferred building.
     *  Multi-stop planning is Phase 5 polish per spec line 268. */
    private static List<VisitorItinerary> planItinerary(Village village,
                                                        VillageSavedData vdata,
                                                        VisitorType type) {
        Activity primary = primaryActivityFor(type);
        Optional<UUID> target = pickTargetBuilding(village, vdata, primary);
        List<VisitorItinerary> stops = new ArrayList<>();
        target.ifPresent(uuid -> stops.add(new VisitorItinerary(
                uuid, primary, primary.defaultDurationTicks())));
        return stops;
    }

    private static Activity primaryActivityFor(VisitorType type) {
        return switch (type) {
            case PILGRIM            -> Activity.PRAY;
            case MERCHANT_ITINERANT -> Activity.SELL_GOODS;
            case TRAVELER           -> Activity.STAY;
            case STUDENT            -> Activity.ATTEND_LESSON;
            case ENVOY              -> Activity.DELIVER_MESSAGE;
            case REFUGEE            -> Activity.STAY;
            case SCHOLAR_VISITING   -> Activity.ATTEND_LESSON;
            case MINSTREL           -> Activity.PERFORM;
        };
    }

    private static Optional<UUID> pickTargetBuilding(Village village,
                                                     VillageSavedData vdata,
                                                     Activity activity) {
        var allowed = activity.targetBuildings();
        for (UUID bid : village.getBuildingIds()) {
            Optional<Building> b = vdata.getBuildingById(bid);
            if (b.isPresent() && allowed.contains(b.get().getType())) {
                return Optional.of(bid);
            }
        }
        // Fallback: any building at all (so the visitor at least
        // has somewhere to go even when no perfect match exists).
        for (UUID bid : village.getBuildingIds()) {
            Optional<Building> b = vdata.getBuildingById(bid);
            if (b.isPresent()) return Optional.of(bid);
        }
        return Optional.empty();
    }

    /** Suppresses unused-import warning on BuildingType. */
    @SuppressWarnings("unused")
    private static final BuildingType DOC_HOOK = null;
}
