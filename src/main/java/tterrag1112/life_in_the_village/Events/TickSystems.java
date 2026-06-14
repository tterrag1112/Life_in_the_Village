package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.HouseholdWealthManager;
import tterrag1112.life_in_the_village.Entities.ModEntities;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Lore.KingdomHistoryData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Profession.ProfessionPerkManager;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.HousePurchaseManager;
import tterrag1112.life_in_the_village.Village.Buildings.VillageExpansionManager;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.VillagePath;
import tterrag1112.life_in_the_village.Village.Decoration.VillageAgingManager;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;

import tterrag1112.life_in_the_village.Village.Decoration.VillageDecorator;
import tterrag1112.life_in_the_village.Village.Economy.CraftingOrderManager;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.TreasuryTickHandler;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketRentManager;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStallPlacer;
import tterrag1112.life_in_the_village.Village.Economy.Market.MerchantStartingStock;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.AtlasRouteRouter;
import tterrag1112.life_in_the_village.Village.Economy.Trade.BoatCaravanSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.CaravanSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Realization.EdgeRealizer;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;
import tterrag1112.life_in_the_village.Village.Event.VillageEventScheduler;
import tterrag1112.life_in_the_village.Village.Needs.VillageNeedsCalculator;
import tterrag1112.life_in_the_village.Village.Simulation.KingdomEconomyEngine;
import tterrag1112.life_in_the_village.Village.Simulation.VillageSimEngine;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageWarningSystem;
import tterrag1112.life_in_the_village.Village.Needs.NeedCategory;

import tterrag1112.life_in_the_village.Village.Roads.Planning.ParallelismDetector;
import tterrag1112.life_in_the_village.Village.Roads.Planning.ParallelismResolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

// =============================================================================
// EVERY-TICK SYSTEMS (interval = 1)
// =============================================================================

/**
 * Village events must be checked every tick to correctly transition
 * ANNOUNCED → ACTIVE → ENDED based on precise timing.
 */
class EventTickSystem implements TickSubsystem {
    @Override public String name()     { return "events"; }
    @Override public int    interval() { return 1; }
    @Override public int    priority() { return 10; }

    @Override
    public void tick(TickContext ctx) {
        VillageEventScheduler.tickEvents(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

// =============================================================================
// EVERY-SECOND SYSTEMS (interval = 20)
// =============================================================================

class AdventurerTickSystem implements TickSubsystem {
    @Override public String name()     { return "adventurer"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 100; }

    @Override
    public void tick(TickContext ctx) {
        ctx.adventurerData().tick(ctx.level(), ctx.villageData());
    }
}

class CaravanTickSystem implements TickSubsystem {
    @Override public String name()     { return "caravans"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 117; }

    @Override
    public void tick(TickContext ctx) {
        CaravanSavedData.get(ctx.level())
                .tick(ctx.level(), ctx.villageData());
    }
}
class BoatCaravanTickSystem implements TickSubsystem {
    @Override public String name()     { return "boat_caravans"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 118; }

    @Override
    public void tick(TickContext ctx) {
        BoatCaravanSavedData.get(ctx.level())
                .tick(ctx.level(), ctx.villageData());
    }
}

/** Religion R3e-3b — drives resident pilgrimages (single-member travellers). */
class PilgrimageTickSystem implements TickSubsystem {
    @Override public String name()     { return "pilgrimages"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 119; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Village.Travel.PilgrimageSavedData.get(ctx.level())
                .tick(ctx.level(), ctx.villageData());
    }
}

class CompanyTickSystem implements TickSubsystem {
    @Override public String name()     { return "business"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 100; }

    @Override
    public void tick(TickContext ctx) {
        ctx.businessData().tick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

class WarningTickSystem implements TickSubsystem {
    @Override public String name()     { return "warnings"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 120; }

    @Override
    public void tick(TickContext ctx) {
        VillageWarningSystem.tickWarningSpread(
                ctx.level(), ctx.villageData(), ctx.tick());
    }
}

class ExpansionTickSystem implements TickSubsystem {
    @Override public String name()     { return "expansion"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 130; }

    @Override
    public void tick(TickContext ctx) {
        VillageExpansionManager.tick(
                ctx.level(), ctx.villageData(), ctx.tick());
    }
}

class AgingTickSystem implements TickSubsystem {
    @Override public String name()     { return "aging"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 140; }

    @Override
    public void tick(TickContext ctx) {
        VillageAgingManager.tick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

class PropertyTaxTickSystem implements TickSubsystem {
    @Override public String name()     { return "property_tax"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 150; }

    @Override
    public void tick(TickContext ctx) {
        HousePurchaseManager.tickPropertyTax(
                ctx.level(), ctx.villageData(), ctx.tick());
    }
}

class WorkplacePayTickSystem implements TickSubsystem {
    @Override public String name()     { return "workplace_pay"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 160; }

    @Override
    public void tick(TickContext ctx) {
        WorkplaceAssignmentManager.tickWeeklyPay(ctx.level(), ctx.tick());
    }
}

class CraftingOrderTickSystem implements TickSubsystem {
    @Override public String name()     { return "crafting_orders"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 170; }

    @Override
    public void tick(TickContext ctx) {
        CraftingOrderManager.tick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

class PlayerPerkTickSystem implements TickSubsystem {
    @Override public String name()     { return "player_perks"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 200; }

    @Override
    public void tick(TickContext ctx) {
        for (ServerPlayer player :
                ctx.level().getServer().getPlayerList().getPlayers()) {
            ProfessionPerkManager.tickPassiveEffects(player);
        }
    }
}

// =============================================================================
// DAILY VILLAGE SYSTEM (runs once per in-game day, staggered per village)
// =============================================================================

/**
 * Replaces the inline per-village daily loop from the old ServerTickDispatcher.
 *
 * Each village is staggered across the 24000-tick day using a hash of its
 * name, so not all villages fire on the same tick. This was already done
 * in the old code — preserved here with the same offset logic.
 *
 * Kingdom economy evaluation is staggered separately with a +1000 tick
 * offset from the village tick to avoid clustering.
 */
class VillageDailyTickSystem implements TickSubsystem {
    @Override public String name()     { return "village_daily"; }
    @Override public int    interval() { return 1; }  // we do our own gating
    @Override public int    priority() { return 300; } // after all fast systems

    @Override
    public void tick(TickContext ctx) {
        long tick = ctx.tick();
        VillageSavedData vdata = ctx.villageData();
        ServerLevel level = ctx.level();

        for (Village village : vdata.getAllVillages()) {
            if (!village.isRealised()) continue;
            long offset = Math.abs(village.getName().hashCode() % 24000L);
            if ((tick + offset) % 24000L != 0) continue;

            // ── Needs calculation (other daily systems depend on this) ────────
            var needs = VillageNeedsCalculator.compute(level, village, vdata);
            village.setNeeds(needs);
            village.setLastNeedsUpdate(tick);

            // ── Food effects ─────────────────────────────────────────────────
            applyFoodEffects(level, village, vdata);

            // ── Event scheduling ─────────────────────────────────────────────
            VillageEventScheduler.tick(level, village, vdata, tick);

            // ── Economy maintenance ──────────────────────────────────────────
            VillageEconomy.purgeStaleListings(village.getId(), tick);
            // ── Treasury operations ──────────────────────────────────────────
            TreasuryTickHandler.tick(level, village, vdata, tick);

            // ── Path upgrades ────────────────────────────────────────────────
            upgradePathsIfAffordable(level, village, vdata);

            // ── Kingdom formation check ──────────────────────────────────────
            checkKingdomFormation(level, village, vdata, tick);

            // ── Simulation engine update ─────────────────────────────────────
            VillageSimEngine.tick(level, village, vdata, tick);

            MarketRentManager.tick(level, village, vdata, tick);

            HouseholdWealthManager.tickHouseholdSpending(level, village, vdata);


            // Re-assign StallKeeperGoal to NPC stall owners whose goals
// were lost on server restart
            reAssignStallGoals(level, village, vdata, tick);
            // Seed each NPC stall with starting stock exactly ONCE, ever
            // (merchant-stall-fixes A). The old gate keyed off absolute world
            // game-time (tick <= 6000L), so on any reloaded/older world the
            // seeding never fired and starting stock stayed empty. Now we gate
            // per-stall on a persistent `seeded` flag: a sold-down stall is
            // never re-filled with free goods, and a never-seeded stall on an
            // old world finally gets its stock. MerchantStartingStock's
            // empty-content guard remains the secondary safety.
            vdata.getStallsForVillage(village.getId()).forEach(stall -> {
                if (stall.isActive()
                        && stall.getOwnerType() == MarketStall.OwnerType.NPC
                        && !stall.isSeeded()) {
                    boolean seeded = MerchantStartingStock.initialStockIfNeeded(
                            level, stall, village, vdata);
                    if (seeded) {
                        stall.setSeeded(true);
                        vdata.setDirty();
                    }
                }
            });


            vdata.setDirty();
        }

        // ── Kingdom economy (hoisted out of the per-village loop) ─────────────
        // Perf/correctness fix (perf-noon-social-food): this loop used to live
        // INSIDE the per-village loop, gated only by the kingdom offset. That
        // meant KingdomEconomyEngine.evaluate ran once per kingdom PER VILLAGE
        // that happened to fire on the kingdom's offset tick — in a world where
        // two villages share an offset tick the same kingdom was evaluated
        // twice in one tick, and the cost scaled with village count. Hoisted to
        // run at most ONCE per kingdom per day, independent of how many villages
        // fired this tick. The +1000 stagger from the village daily window is
        // preserved.
        for (Kingdom kingdom : vdata.getAllKingdoms()) {
            long kOffset = Math.abs(kingdom.getName().hashCode() % 24000L);
            if ((tick + kOffset + 1000L) % 24000L == 0) {
                KingdomEconomyEngine.evaluate(level, kingdom, vdata);
            }
        }
    }

    // =========================================================================
    // Helpers (moved from old ServerTickDispatcher)
    // =========================================================================

    private static void applyFoodEffects(ServerLevel level,
                                         Village village,
                                         VillageSavedData data) {
        var foodNeed = village.getNeeds().get(NeedCategory.FOOD);
        if (foodNeed == null) return;

        AABB bounds = village.getBounds(data)
                .map(b -> b.inflate(16))
                .orElse(null);
        if (bounds == null) return;

        var npcs = level.getEntitiesOfClass(
                TownspersonMob.class, bounds,
                mob -> mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false));

        switch (foodNeed.getLevel()) {
            case SURPLUS -> npcs.forEach(npc ->
                    npc.addEffect(new MobEffectInstance(
                            MobEffects.SATURATION, 24000, 0, false, false)));
            case CRITICAL -> npcs.forEach(npc ->
                    npc.addEffect(new MobEffectInstance(
                            MobEffects.HUNGER, 24000, 0, false, true)));
            default -> {} // SATISFIED and LOW — no effect
        }
    }

    private static void reAssignStallGoals(ServerLevel level,
                                           Village village,
                                           VillageSavedData vdata,
                                           long tick) {
        // Only run once per village on the first day tick after load
        // Use a 5-minute window after server start to avoid doing this every day
        if (tick > 6000L) return; // only within first ~5 minutes of world time

        vdata.getStallsForVillage(village.getId()).forEach(stall -> {
            if (!stall.isActive()) return;
            if (stall.getOwnerType()
                    != tterrag1112.life_in_the_village.Village.Economy.Market
                    .MarketStall.OwnerType.NPC) return;

            MarketStallPlacer.assignGoalIfNpc(level, stall);
        });
    }
    static void upgradePathsIfAffordable(ServerLevel level,
                                         Village village,
                                         VillageSavedData data) {
        Map<VillagePath.PathTier, CurrencyValue> thresholds = Map.of(
                VillagePath.PathTier.DIRT, CurrencyValue.ofGold(5),
                VillagePath.PathTier.GRAVEL, CurrencyValue.ofGold(15),
                VillagePath.PathTier.COBBLESTONE, CurrencyValue.ofGold(40)
        );

        VillagePath.PathTier current = village.getPathTier();
        VillagePath.PathTier next = current.next();
        if (next == null || current.isMaxTier()) return;

        CurrencyValue cost = thresholds.get(next);
        if (cost == null) return;

        long treasury = village.getTreasuryBronze();
        if (treasury < cost.toBronze()) return;

        village.depositToTreasury(cost.toBronze());
        village.setPathTier(next);
        data.markDirty();

        VillageBiomeStyle style = village.getBounds(data)
                .map(b -> VillageBiomeStyle.detect(
                        level, BlockPos.containing(b.getCenter())))
                .orElse(VillageBiomeStyle.PLAINS);

        // Use new organic road upgrade system
        VillageDecorator.upgradeStreets(level, village, data, style, next);
        data.setDirty();
    }

    private static void checkKingdomFormation(ServerLevel level,
                                              Village village,
                                              VillageSavedData data,
                                              long tick) {
        if (data.getKingdomForVillage(village.getId()).isPresent()) return;

        AABB bounds = village.getBounds(data)
                .map(b -> b.inflate(32))
                .orElse(new AABB(0, 0, 0, 0, 0, 0));

        int population = (int) level.getEntitiesOfClass(
                TownspersonMob.class, bounds,
                mob -> mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        ).size();

        if (population < 10) return;

        boolean hasMatureTownHall = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .anyMatch(b -> b.getType() == BuildingType.TOWN_HALL
                        && b.getLevel() >= 2);

        if (!hasMatureTownHall) return;

        String kingdomName = village.getName() + " Kingdom";
        Kingdom kingdom = new Kingdom(kingdomName, "default");
        kingdom.addVillage(village.getId());
        data.addKingdom(kingdom);

        kingdom.getHistory().recordEvent(
                HistoryTextGenerator.kingdomFounded(
                        kingdom.getName(), village.getName(), tick),
                kingdom.getName());

        kingdom.getHistory().setOrigin(
                new KingdomHistoryData.KingdomOriginData(
                        KingdomHistoryData.KingdomOrigins.ANCIENT,
                        "the village elders",
                        new UUID(0, 0),
                        village.getName(),
                        tick,
                        kingdom.getVillageIds().size(),
                        "From many, one."));

        data.setDirty();
    }
    // =============================================================================

    }
// WANDERING TRADER SPAWN SYSTEM
// =============================================================================

/**
 * Periodically spawns wandering exotic traders near active trade roads.
 *
 * At most {@code MAX_ACTIVE_TRADERS} traders exist at once across the world.
 * A new spawn attempt happens every {@code SPAWN_INTERVAL_TICKS} (roughly
 * every 2 in-game days) with a random chance of success.
 */
class WanderingTraderTickSystem implements TickSubsystem {

    private static final int  MAX_ACTIVE_TRADERS   = 3;
    private static final long SPAWN_INTERVAL_TICKS = 24000L * 2;
    private static final int  SPAWN_CHANCE          = 3; // 1-in-N

    @Override public String name()     { return "wandering_trader_spawn"; }
    @Override public int    interval() { return 1; } // gated internally
    @Override public int    priority() { return 250; }

    @Override
    public void tick(TickContext ctx) {
        long tick = ctx.tick();
        if (tick % SPAWN_INTERVAL_TICKS != 0) return;

        ServerLevel level = ctx.level();

        // Count existing wandering traders
        AABB searchBounds = new AABB(-30000000, -256, -30000000,
                30000000,  256,  30000000);
        long active = level.getEntitiesOfClass(
                        TownspersonMob.class,
                        searchBounds,
                        mob -> mob.getProfession()
                                == tterrag1112.life_in_the_village.Profession.Profession
                                .WANDERING_TRADER
                                && mob.isAlive())
                .size();

        if (active >= MAX_ACTIVE_TRADERS) return;
        if (level.getRandom().nextInt(SPAWN_CHANCE) != 0) return;

        // Find a trade road block to spawn near
        VillageSavedData vdata = ctx.villageData();
        BlockPos spawnPos = findSpawnPosition(level, vdata);
        if (spawnPos == null) return;

        spawnWanderingTrader(level, spawnPos, vdata);
    }

    private BlockPos findSpawnPosition(ServerLevel level,
                                       VillageSavedData vdata) {
        // Gather all road blocks from realised graph edges
        List<BlockPos> candidates = new java.util.ArrayList<>();
        var graph = tterrag1112.life_in_the_village.Networking.WorldRoadSavedData.get(level).getGraph();
        for (var edge : graph.allEdges()) {
            if (edge.isRealized()) candidates.addAll(edge.getBlockPath());
        }

        if (candidates.isEmpty()) {
            // No roads yet — spawn near any village
            return vdata.getAllVillages().stream()
                    .findFirst()
                    .flatMap(v -> v.getBounds(vdata))
                    .map(bounds -> {
                        BlockPos centre = BlockPos.containing(bounds.getCenter());
                        return centre.offset(
                                level.getRandom().nextIntBetweenInclusive(-80, 80),
                                0,
                                level.getRandom().nextIntBetweenInclusive(-80, 80));
                    })
                    .orElse(null);
        }

        // Pick a random road block well away from either village endpoint
        // (aim for the middle third of the road)
        int start = candidates.size() / 3;
        int end   = candidates.size() * 2 / 3;
        if (start >= end) return candidates.get(0);

        int idx = start + level.getRandom().nextInt(end - start);
        BlockPos road = candidates.get(idx);

        // Find surface height
        return level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types
                        .MOTION_BLOCKING_NO_LEAVES,
                road.offset(
                        level.getRandom().nextIntBetweenInclusive(-8, 8),
                        0,
                        level.getRandom().nextIntBetweenInclusive(-8, 8)));
    }

    private void spawnWanderingTrader(ServerLevel level,
                                      BlockPos pos,
                                      VillageSavedData vdata) {
        TownspersonMob trader = ModEntities.TOWNSPERSON.get()
                .create(level, net.minecraft.world.entity.EntitySpawnReason.NATURAL);
        if (trader == null) return;

        // Phase 6.3.3.a — gated, OTHER/SYSTEM (wandering trader spawn).
        tterrag1112.life_in_the_village.Npc.Career.CareerTransitions.changeProfession(
                trader,
                tterrag1112.life_in_the_village.Profession.Profession.WANDERING_TRADER,
                tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Reason.OTHER,
                tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Source.SYSTEM);

        // Give the trader a random name (not tied to any village)
        var rng = level.getRandom();
        String first = tterrag1112.life_in_the_village.Entities.NpcNameRegistry
                .INSTANCE.generateFirstName(rng.nextBoolean(), rng);
        String sur   = tterrag1112.life_in_the_village.Entities.NpcNameRegistry
                .INSTANCE.generateSurname(rng);
        trader.setNpcName(first + " " + sur);

        // Wandering traders have no assigned village
        trader.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        level.addFreshEntity(trader);

        System.out.println("[WanderingTrader] Spawned " + trader.getNpcName()
                + " at " + pos);
    }



}

class GraphEdgeRealizationSystem implements TickSubsystem {

    // Realize-radius per tier (blocks). Higher-tier roads are loaded earlier.
    private static final int RADIUS_GREAT_ROAD = 768;
    private static final int RADIUS_TRUNK      = 384;
    private static final int RADIUS_CONNECTOR  = 256;
    private static final int RADIUS_LOCAL      = 192;

    @Override public String name()     { return "graph_edge_realization"; }
    @Override public int    interval() { return 40; }
    @Override public int    priority() { return 150; }

    @Override
    public void tick(TickContext ctx) {
        ServerLevel level = ctx.level();
        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = roadData.getGraph();

        List<? extends ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        // Collect all edges that need work (unrealized OR stale) and are within range.
        // Record: (edge, tierOrdinal, closestDistSq) for priority sorting.
        record Candidate(RoadEdge edge, int tierOrdinal, long closestDistSq) {}
        List<Candidate> candidates = new ArrayList<>();

        for (RoadEdge edge : graph.allEdges()) {
            // Track C3.3 — SEA edges paint no blocks. EdgeRealizer
            // marks them realised on first contact; if for any reason
            // the realised flag is unset, skip them here so the
            // candidate list stays land-only.
            if (edge.getTier() == RoadEdge.EdgeTier.SEA) continue;
            boolean needsWork = !edge.isRealized() || !edge.getStaleCells().isEmpty();
            if (!needsWork) continue;
            if (edge.getCellPath().isEmpty()) continue;

            int radius = tierRadius(edge.getTier());
            long distSq = closestCellDistSq(players, edge);
            if (distSq > (long) radius * radius) continue;

            candidates.add(new Candidate(edge, edge.getTier().ordinal(), distSq));
        }

        if (candidates.isEmpty()) return;

        // Priority: lowest tier ordinal first (GREAT_ROAD=0), then closest-first within tier.
        candidates.sort(Comparator.comparingInt(Candidate::tierOrdinal)
                .thenComparingLong(Candidate::closestDistSq));

        RoadEdge chosen = candidates.get(0).edge();
        EdgeRealizer.realizeEdge(level, chosen, graph, ctx.villageData());

        if (!chosen.getBlockPath().isEmpty()) {
            roadData.markDirty();
            System.out.println("[GraphEdgeRealizationSystem] Realized edge "
                    + chosen.getEdgeId().toString().substring(0, 8) + "… ("
                    + chosen.getBlockPath().size() + " blocks, tier=" + chosen.getTier() + ")");
        }
    }

    private static int tierRadius(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case GREAT_ROAD -> RADIUS_GREAT_ROAD;
            case TRUNK      -> RADIUS_TRUNK;
            case CONNECTOR  -> RADIUS_CONNECTOR;
            case LOCAL      -> RADIUS_LOCAL;
            // Track C3.3 — SEA edges are skipped at the realisation
            // candidate-selection step; this value is unreachable in
            // practice but keeps the switch exhaustive.
            case SEA        -> 0;
        };
    }

    /** Returns the squared distance from the closest player to the closest cell in this edge. */
    private static long closestCellDistSq(List<? extends ServerPlayer> players, RoadEdge edge) {
        long best = Long.MAX_VALUE;
        for (long cellKey : edge.getCellPath()) {
            BlockPos center = AtlasRouteRouter.cellKeyToBlockCenter(cellKey);
            for (ServerPlayer p : players) {
                long dx = (long)(p.getX() - center.getX());
                long dz = (long)(p.getZ() - center.getZ());
                long d = dx * dx + dz * dz;
                if (d < best) best = d;
            }
        }
        return best;
    }
}

// =============================================================================
// PARALLELISM CLEANUP (interval = 2400 ≈ 2 min, priority = 160)
// =============================================================================

class ParallelismCleanupSystem implements TickSubsystem {

    /** Scan radius around the chosen player. */
    private static final int SCAN_RADIUS = 1536;

    /** Maximum merges per game day (24 000 ticks) — safety cap. */
    private static final int SESSION_CAP = 20;

    /** Game-day length in ticks. */
    private static final long DAY_TICKS = 24_000L;

    private int  mergesThisSession = 0;
    private long sessionStartTick  = Long.MIN_VALUE;

    private final Random rng = new Random();

    @Override public String name()     { return "parallelism_cleanup"; }
    @Override public int    interval() { return 2400; }
    @Override public int    priority() { return 160; }

    @Override
    public void tick(TickContext ctx) {
        ServerLevel level = ctx.level();
        List<? extends ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        // Reset session counter once per game day
        long gameTick = level.getGameTime();
        if (sessionStartTick == Long.MIN_VALUE
                || gameTick - sessionStartTick >= DAY_TICKS) {
            mergesThisSession = 0;
            sessionStartTick  = gameTick;
        }

        if (mergesThisSession >= SESSION_CAP) return;

        // Pick a random online player as the scan center
        ServerPlayer center = players.get(rng.nextInt(players.size()));
        BlockPos scanCenter = center.blockPosition();

        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = roadData.getGraph();

        List<ParallelismDetector.ParallelPair> pairs =
                ParallelismDetector.findParallelPairs(graph, SCAN_RADIUS, scanCenter);

        if (pairs.isEmpty()) return;

        ParallelismDetector.ParallelPair pair = pairs.get(0); // longest overlap first
        ParallelismResolver.ResolveResult result =
                ParallelismResolver.resolvePair(level, graph, pair);

        if (result.success()) {
            mergesThisSession++;
            roadData.markDirty();
            System.out.println("[ParallelismCleanupSystem] Merged parallel pair"
                    + " survivor=" + result.survivorEdgeId().toString().substring(0, 8)
                    + " removed=" + result.removedEdgeId().toString().substring(0, 8)
                    + " stubs=" + result.leftoverEdgeIds().size()
                    + " (session merges=" + mergesThisSession + "/" + SESSION_CAP + ")");
        } else {
            System.out.println("[ParallelismCleanupSystem] Resolve failed: "
                    + result.failureReason());
        }
    }
}

// =============================================================================
// VILLAGE REALIZATION (Tracks C1-c + V4) — interval = 20, priority = 58
// =============================================================================

/**
 * Doc 16 V4 — the unified player-approach realization pass. Realizes
 * CHARTERED settlement charters (the deterministic village-first model) into
 * real {@link Village}s via the canonical V2 spawner, and — in the SAME pass —
 * realizes any legacy planned {@link Village} (the natural-spawn /
 * {@code VillagePlanHelper} path). V4 unified the two former approach-scanners
 * ({@code VillageRealisationSystem} for planned villages and this one for
 * charters) into this single pass so we never run two competing realizers;
 * {@code VillageRealisationSystem} is retired.
 *
 * <h3>Cost tier</h3>
 * Realization is Tier 2 (doc 16 §1): this is the ONE place the expensive V2 /
 * {@code GeneratorTerrainSource} terrain adaptation belongs. Placement and
 * claim (V1–V3) stay digest-only; nothing here leaks back into those tiers.
 *
 * <h3>Trigger</h3>
 * Each pass it collects every realization target — unrealized charter (at its
 * candidate point) and planned village (at its planned origin) — within
 * {@link #REALISE_RADIUS} blocks of any player, picks the single nearest, and
 * realizes exactly ONE per pass (spawning is heavy; the dense-region queue is
 * acceptable LOD per doc 16 §8). Priority 58, 1 Hz.
 *
 * <h3>Charter candidate point (CHARTERED)</h3>
 * A CHARTERED charter has no surveyed anchor (the per-charter SURVEY loop was
 * removed in V2). Its realization point is the deterministic candidate point
 * recomputed load-free from {@code targetCellKey} via
 * {@link tterrag1112.life_in_the_village.Kingdom.Settlement.VillagePlacement#evaluateRegion}
 * (Tier-0, no chunk load) — the same point V1 placement chose — falling back
 * to the cell centre if the recompute returns empty. V2 ADAPTS to the real
 * terrain at this point (the lenient-gate payoff: the village bends to the
 * ground, never rejected). The realized position becomes truth (doc 16 §5).
 *
 * <h3>Link + sharpen + stage advance</h3>
 * On a successful charter spawn the produced {@link Village} (realised=true) is
 * bound to the issuing kingdom — {@code village.setKingdomId(kingdomId)} +
 * {@code kingdom.addVillage(id)} — the charter advances via
 * {@code charter.realized(id)} + {@code kingdom.updateSettlementCharter(...)},
 * so the map reads the realized village's actual position afterward (the stale
 * candidate pin is dropped once {@code isUnrealized()} is false; no double-pin).
 * The kingdomId is DERIVED from the cell-claim (the charter's issuing kingdom);
 * it is set on the Village only because the spawner/membership needs it, not
 * stored as a desync-prone duplicate. For the capital the kingdom's
 * {@code capitalVillageId} is set.
 *
 * <h3>Failure handling (decoupled)</h3>
 * If a charter spawn fails (overlap, etc.) the kingdom is NOT failed and the
 * charter is NOT lost: a per-charter {@link FailureRecord} backoff leaves it
 * CHARTERED for retry, and after {@code MAX_RETRY_ATTEMPTS} it is parked (no
 * further retries) but kept intact. Since placement was lenient and V2 adapts,
 * genuine failures should be rare.
 *
 * <h3>Capital</h3>
 * The capital is just a promoted charter (V3) and realizes through this same
 * path; grander-capital morphology is C4/A9 — out of scope here (C4 hook:
 * branch on {@code charter.capital()} before the spawn to pick a grander
 * type/size). The capital realizes as a normal village for now.
 *
 * <h3>Frontier / LOD (V5 hook)</h3>
 * V4 realizes CHARTERED→REALIZED on approach only. Global (unclaimed) frontier
 * placement and near-eager / distant-lazy LOD are V5 — not built here.
 */
// =============================================================================
// FRONTIER ENUMERATION (Track V5) — interval = 100, priority = 57
// =============================================================================

/**
 * Track V5 — the LOD-driven frontier enumeration pass. Charts ownerless
 * frontier villages in the deterministic frontier subset
 * ({@link tterrag1112.life_in_the_village.Kingdom.Settlement.VillagePlacement#isFrontierRegion})
 * within a bounded radius of players, on a 5s cadence. Distant frontier is
 * never enumerated until a player nears — the near-eager / distant-lazy LOD
 * (doc 16 §9 V5) that keeps worldgen + memory off the hook for far regions.
 *
 * <p>Runs just BEFORE the realization pass (priority 57 < 58) so a freshly
 * charted frontier village can realize the moment the player is in range, in
 * the same 1 Hz cadence window. Tier-0/1 — load-free digests + persisted claim
 * sets only; no scan, no atlas fill, no V2 (see the enumerator class note).
 */
class FrontierEnumerationTickSystem implements TickSubsystem {
    @Override public String name()     { return "frontier_enumeration"; }
    @Override public int    interval() { return 100; } // every 5s
    @Override public int    priority() { return 57; }  // just before realization (58)

    @Override
    public void tick(TickContext ctx) {
        ServerLevel level = ctx.level();
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
        List<? extends ServerPlayer> players = level.players();
        if (players.isEmpty()) return;
        tterrag1112.life_in_the_village.Kingdom.Worldgen.FrontierVillageEnumerator
                .enumerateNearPlayers(level, ctx.villageData(), players,
                        level.getGameTime(), msg -> System.out.println("  " + msg));
    }
}

class CharterRealizationTickSystem implements TickSubsystem {

    /** Player must be within this many blocks of the realization point. */
    private static final int REALISE_RADIUS = 256;
    private static final double REALISE_RADIUS_SQ =
            (double) REALISE_RADIUS * REALISE_RADIUS;

    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long[] BACKOFF_TICKS = {100L, 400L, 1200L, 3600L, 12000L};

    /** Per-charter failure backoff, keyed by charter id (stable across the
     *  copy-with mutations). */
    private final java.util.Map<java.util.UUID, FailureRecord> failureHistory =
            new java.util.HashMap<>();
    /** Per-planned-village failure backoff, keyed by planned village id. */
    private final java.util.Map<java.util.UUID, FailureRecord> plannedFailureHistory =
            new java.util.HashMap<>();
    /** Track V5 -- per-frontier-charter failure backoff, keyed by charter id. */
    private final java.util.Map<java.util.UUID, FailureRecord> frontierFailureHistory =
            new java.util.HashMap<>();

    private record FailureRecord(int attempts, long nextAttemptTick) {}

    @Override public String name()     { return "village_realization"; }
    @Override public int    interval() { return 20; } // 1 Hz
    @Override public int    priority() { return 58; }

    @Override
    public void tick(TickContext ctx) {
        ServerLevel level = ctx.level();
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;

        List<? extends ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        VillageSavedData data = ctx.villageData();
        long currentTick = level.getGameTime();

        // A realization target is either a charter (at its candidate point) or
        // a legacy planned Village (at its planned origin). We pick the single
        // nearest across BOTH kinds and realize exactly one per pass.
        Kingdom bestKingdom = null;
        tterrag1112.life_in_the_village.Kingdom.Settlement.SettlementCharter bestCharter = null;
        BlockPos bestCharterPoint = null;
        Village bestPlanned = null;
        BlockPos bestPlannedOrigin = null;
        // Track V5 -- frontier (ownerless) charters realize through this same
        // pass; they are just charters with no owning kingdom yet.
        tterrag1112.life_in_the_village.Kingdom.Settlement.SettlementCharter bestFrontier = null;
        BlockPos bestFrontierPoint = null;
        double bestDistSq = REALISE_RADIUS_SQ;

        // ── Charters ────────────────────────────────────────────────────────
        for (Kingdom k : data.getAllKingdoms()) {
            for (var charter : k.getSettlementCharters()) {
                if (!charter.isUnrealized()) continue; // REALIZED -> Village marker

                FailureRecord fs = failureHistory.get(charter.id());
                if (fs != null) {
                    if (fs.attempts() >= MAX_RETRY_ATTEMPTS) continue;
                    if (currentTick < fs.nextAttemptTick()) continue;
                }

                BlockPos point = charterRealizationPoint(level, charter);
                if (point == null) continue;

                for (ServerPlayer p : players) {
                    double dx = p.getX() - point.getX();
                    double dz = p.getZ() - point.getZ();
                    double d = dx * dx + dz * dz;
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        bestKingdom = k; bestCharter = charter; bestCharterPoint = point;
                        bestPlanned = null; bestPlannedOrigin = null;
                        bestFrontier = null; bestFrontierPoint = null;
                    }
                }
            }
        }

        // ── Legacy planned villages (natural-spawn path) ──────────────────────
        for (Village v : data.getAllVillages()) {
            if (!tterrag1112.life_in_the_village.Village.Planning.VillagePlanHelper.isPlanned(v))
                continue;

            FailureRecord fs = plannedFailureHistory.get(v.getId());
            if (fs != null) {
                if (fs.attempts() >= MAX_RETRY_ATTEMPTS) continue;
                if (currentTick < fs.nextAttemptTick()) continue;
            }

            BlockPos origin = v.getPlannedOrigin();
            if (origin == null) continue;

            for (ServerPlayer p : players) {
                double dx = p.getX() - origin.getX();
                double dz = p.getZ() - origin.getZ();
                double d = dx * dx + dz * dz;
                if (d < bestDistSq) {
                    bestDistSq = d;
                    bestPlanned = v; bestPlannedOrigin = origin;
                    bestKingdom = null; bestCharter = null; bestCharterPoint = null;
                    bestFrontier = null; bestFrontierPoint = null;
                }
            }
        }

        // ── Frontier (ownerless) charters (Track V5) ──────────────────────────
        for (var charter : data.getFrontierCharters()) {
            if (!charter.isUnrealized()) continue;

            FailureRecord fs = frontierFailureHistory.get(charter.id());
            if (fs != null) {
                if (fs.attempts() >= MAX_RETRY_ATTEMPTS) continue;
                if (currentTick < fs.nextAttemptTick()) continue;
            }

            BlockPos point = charterRealizationPoint(level, charter);
            if (point == null) continue;

            for (ServerPlayer p : players) {
                double dx = p.getX() - point.getX();
                double dz = p.getZ() - point.getZ();
                double d = dx * dx + dz * dz;
                if (d < bestDistSq) {
                    bestDistSq = d;
                    bestFrontier = charter; bestFrontierPoint = point;
                    bestKingdom = null; bestCharter = null; bestCharterPoint = null;
                    bestPlanned = null; bestPlannedOrigin = null;
                }
            }
        }

        if (bestCharter != null) {
            realizeCharter(level, data, bestKingdom, bestCharter, bestCharterPoint, currentTick);
        } else if (bestPlanned != null) {
            realizePlanned(level, data, bestPlanned, bestPlannedOrigin, currentTick);
        } else if (bestFrontier != null) {
            realizeFrontier(level, data, bestFrontier, bestFrontierPoint, currentTick);
        }
    }

    /**
     * The deterministic realization point for an unrealized charter: the
     * exact surveyed anchor if one was ever set (legacy SURVEYED charters),
     * else the V1 candidate point recomputed load-free from the charter's
     * target cell (Tier-0; no chunk load), else the cell centre.
     */
    private static BlockPos charterRealizationPoint(
            ServerLevel level,
            tterrag1112.life_in_the_village.Kingdom.Settlement.SettlementCharter charter) {
        if (charter.surveyedAnchor().isPresent()) return charter.surveyedAnchor().get();

        long cellKey = charter.targetCellKey();
        int blockX = (tterrag1112.life_in_the_village.World.Atlas.AtlasCell.unpackX(cellKey)
                << tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_SHIFT)
                + tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_HALF;
        int blockZ = (tterrag1112.life_in_the_village.World.Atlas.AtlasCell.unpackZ(cellKey)
                << tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_SHIFT)
                + tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_HALF;
        int rx = tterrag1112.life_in_the_village.Kingdom.Settlement.VillagePlacement
                .regionForBlockX(blockX);
        int rz = tterrag1112.life_in_the_village.Kingdom.Settlement.VillagePlacement
                .regionForBlockZ(blockZ);
        var cand = tterrag1112.life_in_the_village.Kingdom.Settlement.VillagePlacement
                .evaluateRegion(level, rx, rz).orElse(null);
        if (cand != null) return cand.pos();
        return charter.pinPos(); // fallback: cell centre
    }

    /** Realize one unrealized charter at its candidate point via the V2 spawner. */
    private void realizeCharter(
            ServerLevel level, VillageSavedData data, Kingdom kingdom,
            tterrag1112.life_in_the_village.Kingdom.Settlement.SettlementCharter charter,
            BlockPos point, long currentTick) {

        String type = charter.villageType();
        String name = charter.name();

        System.out.println("VillageRealization: realizing charter '" + name
                + "' (" + type + ") at " + point.toShortString());

        // C4 HOOK: branch on charter.capital() here to select a grander
        // capital type/size once C4/A9 lands. For now the capital realizes as
        // a normal village (the spawner derives the roster from terrain).

        tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder
                .beginAttempt();
        java.util.Optional<Village> spawnedOpt;
        try {
            spawnedOpt = tterrag1112.life_in_the_village.Village.VillageSpawner
                    .spawnVillage(level, point, type, name);
        } catch (Exception e) {
            tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder.record(
                    tterrag1112.life_in_the_village.Kingdom.Placement
                            .PlacementFailureRecorder.Reason.PLANNER_EXCEPTION,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), point, type);
            spawnedOpt = java.util.Optional.empty();
        }
        boolean succeeded = spawnedOpt.isPresent();
        tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder
                .endAttempt(succeeded, type, point);

        if (!succeeded) {
            FailureRecord prev = failureHistory.get(charter.id());
            int attempts = prev == null ? 1 : prev.attempts() + 1;
            if (attempts >= MAX_RETRY_ATTEMPTS) {
                failureHistory.put(charter.id(), new FailureRecord(attempts, Long.MAX_VALUE));
                System.out.println("[VillageRealization] WARN charter '" + name
                        + "' failed to realize after " + attempts
                        + " attempts — parked CHARTERED (relocation deferred)");
            } else {
                long backoff = BACKOFF_TICKS[attempts - 1];
                failureHistory.put(charter.id(),
                        new FailureRecord(attempts, currentTick + backoff));
                System.out.println("[VillageRealization] spawn failed for charter '"
                        + name + "' — left CHARTERED, retry in " + backoff + " ticks (attempt "
                        + attempts + ")");
            }
            return;
        }

        // Link + sharpen + advance. Position sharpens to the realized Village's
        // actual position (the spawner set it); the map reads that afterward.
        Village spawned = spawnedOpt.get();
        spawned.setKingdomId(kingdom.getId()); // derived from the cell-claim issuer
        kingdom.addVillage(spawned.getId());
        if (charter.capital()) {
            kingdom.setCapitalVillageId(spawned.getId());
        }

        boolean updated = kingdom.updateSettlementCharter(charter.realized(spawned.getId()));
        if (!updated) {
            System.out.println("[VillageRealization] WARN charter '" + name
                    + "' not found for stage-advance after spawn — village kept, charter "
                    + "stage unchanged");
        }
        failureHistory.remove(charter.id());
        data.setDirty();

        System.out.println("[VillageRealization] Realized charter '" + name
                + "' -> village " + spawned.getId() + " (kingdom " + kingdom.getId()
                + (charter.capital() ? ", capital" : "") + ") -> REALIZED");
    }

    /**
     * Realize one legacy planned Village (natural-spawn path) via the V2
     * spawner. Folded from the retired {@code VillageRealisationSystem}; the
     * planned record is replaced by a spawned one preserving name + kingdom.
     */
    private void realizePlanned(
            ServerLevel level, VillageSavedData data, Village planned,
            BlockPos origin, long currentTick) {

        String name = planned.getName();
        String type = planned.getVillageType();
        java.util.UUID plannedId = planned.getId();

        System.out.println("VillageRealization: realizing planned '" + name
                + "' (" + type + ") at " + origin.toShortString());

        java.util.Optional<Kingdom> kingdomOpt = data.getKingdomForVillage(plannedId);

        // Remove the placeholder so the spawner doesn't see a duplicate name.
        data.removeVillage(plannedId);
        kingdomOpt.ifPresent(k -> k.removeVillage(plannedId));
        data.setDirty();

        tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder
                .beginAttempt();
        java.util.Optional<Village> spawnedOpt;
        try {
            spawnedOpt = tterrag1112.life_in_the_village.Village.VillageSpawner
                    .spawnVillage(level, origin, type, name);
        } catch (Exception e) {
            tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder.record(
                    tterrag1112.life_in_the_village.Kingdom.Placement
                            .PlacementFailureRecorder.Reason.PLANNER_EXCEPTION,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), origin, type);
            spawnedOpt = java.util.Optional.empty();
        }
        boolean succeeded = spawnedOpt.isPresent();
        tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder
                .endAttempt(succeeded, type, origin);

        if (!succeeded) {
            // Restore the placeholder and back off (decoupled failure).
            Village restored = new Village(name);
            restored.setVillageType(type);
            restored.setPlannedOrigin(origin);
            restored.setRealised(false);
            data.addVillage(restored);
            kingdomOpt.ifPresent(k -> k.addVillage(restored.getId()));

            FailureRecord prev = plannedFailureHistory.get(plannedId);
            int attempts = prev == null ? 1 : prev.attempts() + 1;
            long backoff = attempts >= MAX_RETRY_ATTEMPTS
                    ? Long.MAX_VALUE : BACKOFF_TICKS[attempts - 1];
            plannedFailureHistory.remove(plannedId);
            plannedFailureHistory.put(restored.getId(),
                    new FailureRecord(attempts, backoff == Long.MAX_VALUE
                            ? Long.MAX_VALUE : currentTick + backoff));
            data.setDirty();
            System.out.println("[VillageRealization] spawn failed for planned '" + name
                    + "' — restored placeholder, attempt " + attempts);
            return;
        }

        Village spawned = spawnedOpt.get();
        tterrag1112.life_in_the_village.Village.Planning.VillagePlanHelper
                .markRealised(spawned, data);
        kingdomOpt.ifPresent(k -> k.addVillage(spawned.getId()));
        plannedFailureHistory.remove(plannedId);
        data.setDirty();
    }

    /**
     * Track V5 -- realize one FRONTIER (ownerless) charter at its deterministic
     * candidate point via the V2 spawner. Identical to {@link #realizeCharter}
     * except for ownership: a frontier village has no kingdom, so it is spawned
     * with the {@code default} culture / no owner.
     *
     * <h3>Late-absorption safety net</h3>
     * Before spawning we re-resolve {@code getKingdomForCell} -- if a kingdom
     * claimed this cell since the charter was issued, we ABSORB it now (move it
     * into that kingdom and realize it as an owned village) rather than spawn a
     * stray ownerless village. This keeps ownership consistent even though the
     * claim is imperative/order-dependent: position is fixed by seed, ownership
     * is derived from the current claim at the moment of realization. No
     * duplicate (the absorbed charter is the same id/cell/candidate).
     */
    private void realizeFrontier(
            ServerLevel level, VillageSavedData data,
            tterrag1112.life_in_the_village.Kingdom.Settlement.SettlementCharter charter,
            BlockPos point, long currentTick) {

        // Late-absorption: if a kingdom now claims this cell, hand off to the
        // owned-charter path so the village is born owned (culture upgraded).
        var ownerOpt = data.getKingdomForCell(charter.targetCellKey());
        if (ownerOpt.isPresent()) {
            Kingdom owner = ownerOpt.get();
            var absorbed = charter.absorbedBy(owner.getId());
            data.removeFrontierCharter(charter.id());
            owner.adoptSettlementCharter(absorbed);
            data.setDirty();
            frontierFailureHistory.remove(charter.id());
            realizeCharter(level, data, owner, absorbed, point, currentTick);
            return;
        }

        String type = charter.villageType();
        String name = charter.name();

        System.out.println("VillageRealization: realizing FRONTIER charter '" + name
                + "' (" + type + ", default culture, no owner) at " + point.toShortString());

        tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder
                .beginAttempt();
        java.util.Optional<Village> spawnedOpt;
        try {
            spawnedOpt = tterrag1112.life_in_the_village.Village.VillageSpawner
                    .spawnVillage(level, point, type, name);
        } catch (Exception e) {
            tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder.record(
                    tterrag1112.life_in_the_village.Kingdom.Placement
                            .PlacementFailureRecorder.Reason.PLANNER_EXCEPTION,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), point, type);
            spawnedOpt = java.util.Optional.empty();
        }
        boolean succeeded = spawnedOpt.isPresent();
        tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder
                .endAttempt(succeeded, type, point);

        if (!succeeded) {
            FailureRecord prev = frontierFailureHistory.get(charter.id());
            int attempts = prev == null ? 1 : prev.attempts() + 1;
            if (attempts >= MAX_RETRY_ATTEMPTS) {
                frontierFailureHistory.put(charter.id(), new FailureRecord(attempts, Long.MAX_VALUE));
                System.out.println("[VillageRealization] WARN frontier charter '" + name
                        + "' failed to realize after " + attempts
                        + " attempts -- parked CHARTERED (relocation deferred)");
            } else {
                long backoff = BACKOFF_TICKS[attempts - 1];
                frontierFailureHistory.put(charter.id(),
                        new FailureRecord(attempts, currentTick + backoff));
            }
            return;
        }

        // Frontier village: no kingdom binding (ownership stays derived-from-claim,
        // which is currently none). Advance the frontier charter to REALIZED.
        Village spawned = spawnedOpt.get();
        boolean updated = data.updateFrontierCharter(charter.realized(spawned.getId()));
        if (!updated) {
            System.out.println("[VillageRealization] WARN frontier charter '" + name
                    + "' not found for stage-advance after spawn -- village kept, charter "
                    + "stage unchanged");
        }
        frontierFailureHistory.remove(charter.id());
        data.setDirty();

        System.out.println("[VillageRealization] Realized FRONTIER charter '" + name
                + "' -> village " + spawned.getId() + " (no owner, default culture) -> REALIZED");
    }
}

// =============================================================================
// NODE DECORATION (interval = 20, priority = 200)
// =============================================================================

class NodeDecorationTickSystem implements TickSubsystem {
    @Override public String name()     { return "node_decoration"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 200; }

    @Override
    public void tick(TickContext ctx) {
        NodeDecorationSystem.tick(ctx.level(), ctx.villageData());
    }
}
// =============================================================================
// ROAD UPKEEP (once per game-day, interval = 24000, priority = 180)
// =============================================================================

class RoadUpkeepTickSystem implements TickSubsystem {
    @Override public String name()     { return "road_upkeep"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 180; }

    @Override
    public void tick(TickContext ctx) {
        RoadUpkeepSystem.runCycle(ctx.level(), ctx.villageData(), false);
    }
}
// =============================================================================
// TIER RECONCILIATION (every 2 in-game days, interval = 48000, priority = 170)
// =============================================================================

class TierReconciliationTickSystem implements TickSubsystem {
    @Override public String name()     { return "tier_reconciliation"; }
    @Override public int    interval() { return 48000; }
    @Override public int    priority() { return 170; }

    @Override
    public void tick(TickContext ctx) {
        TierReconciliationSystem.runReconciliation(ctx.level(), ctx.villageData());
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Great Road Generation
// ─────────────────────────────────────────────────────────────────────────────

class GreatRoadGenerationTickSystem implements TickSubsystem {
    @Override public String name()     { return "great_road_generation"; }
    @Override public int    interval() { return 1; }
    @Override public int    priority() { return 155; } // after edge realization (150), before parallelism cleanup (160)

    @Override
    public void tick(TickContext ctx) {
        GreatRoadGenerationQueue.tick(ctx.level());
    }
}
// =============================================================================
// NPC DAILY DECAY (once per in-game day, interval = 24000, priority = 190)
// =============================================================================

/**
 * One-shot daily pass over every loaded {@link TownspersonMob}. Currently
 * advances {@link tterrag1112.life_in_the_village.Npc.Memory.NpcMemoryLog}
 * decay + eviction, and pulls
 * {@link tterrag1112.life_in_the_village.Npc.Mood.NpcMoodState} toward its
 * baseline. Future Phase 0+ subsystems (skill decay, etc.) hang off the
 * same iteration — keeping a single sweep over loaded NPCs avoids paying
 * the iteration cost multiple times per day.
 */
class NpcMemoryDecayTickSystem implements TickSubsystem {
    @Override public String name()     { return "npc_daily_decay"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 190; }

    @Override
    public void tick(TickContext ctx) {
        long currentTick = ctx.tick();
        for (var entity : ctx.level().getEntities().getAll()) {
            if (!(entity instanceof TownspersonMob npc)) continue;

            // Memory decay + eviction (Phase 0-02).
            var log = npc.getMemory();
            if (!log.isEmpty()) {
                log.decayAll(1f);
                log.removeExpired();
            }

            // Mood drift toward baseline (Phase 0-04).
            npc.getMood().decay(1f, currentTick);

            // Trait calm-period drift-back (Phase 1, 10-phase1-integration).
            // After 180 idle days, traits drift back toward origin at
            // 0.01/year. Cap-aware: only fires when there is drift to
            // unwind.
            var driftBack = npc.getTraitDrift().applyCalmDriftBack(currentTick);
            if (!driftBack.isEmpty()) {
                var vec = npc.getTraitVector();
                driftBack.forEach((axis, delta) -> vec.adjust(axis, delta));
            }

            // Life-goal evaluator (Phase 1 task 07): poll progress, fire
            // GoalCompleted / GoalFailed for finished goals, refill empty
            // slates.
            tterrag1112.life_in_the_village.Npc.LifeGoal.LifeGoalEvaluator
                    .runDaily(npc, currentTick);

            // NPC↔NPC relationship decay (Phase 2 task 11). Boundary
            // mode-changes from decay are fanned out as life events so
            // the memory + mood producers can react.
            var changes = npc.getNpcRelationships().decayAll(1f, currentTick);
            for (var change : changes) {
                tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus.fire(
                        new tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent
                                .RelationshipBoundaryCrossed(
                                        npc, change.otherId(),
                                        change.from(), change.to()));
            }

            // Rumor seed (Phase 2 task 12): chance to promote a recent
            // high-value memory into a knowledge entry the NPC will
            // gossip about. Sociability-scaled.
            tterrag1112.life_in_the_village.Npc.Gossip.RumorSeeder.rollDaily(npc, currentTick);
        }

        // SOCIAL-phase proximity sweep (Phase 2 task 11). Single sample
        // per day per NPC currently in SOCIAL phase — see 11 Revision
        // Notes for the simplified-vs-spec discussion.
        runSocialProximitySweep(ctx);

        // Gossip topic-heat daily decay (Phase 2 task 12). One pass
        // across every village's tracker so old topics fade.
        for (var heat : ctx.villageData().getAllGossipHeat().values()) {
            heat.decay(1f);
        }
        ctx.villageData().setDirty();
    }

    /**
     * Walks loaded NPCs once. For each NPC currently in SOCIAL phase,
     * scans an 8-block AABB for any other TownspersonMob also in
     * SOCIAL, and bumps each side's relationship by +1 (capped at the
     * spec's PROXIMITY_CEILING via
     * {@link tterrag1112.life_in_the_village.Npc.Relations.NpcRelationshipLedger#adjustFromProximity}).
     */
    private static void runSocialProximitySweep(TickContext ctx) {
        long now = ctx.tick();
        var level = ctx.level();
        java.util.List<TownspersonMob> social = new java.util.ArrayList<>();
        for (var entity : level.getEntities().getAll()) {
            if (!(entity instanceof TownspersonMob mob)) continue;
            if (tterrag1112.life_in_the_village.Npc.Schedule.ScheduleResolver
                    .isSocialTime(mob, now)) {
                social.add(mob);
            }
        }
        for (int i = 0; i < social.size(); i++) {
            TownspersonMob a = social.get(i);
            for (int j = i + 1; j < social.size(); j++) {
                TownspersonMob b = social.get(j);
                if (a.distanceToSqr(b) > 6.0 * 6.0) continue;
                a.getNpcRelationships().adjustFromProximity(b.getUUID(), 1, now);
                b.getNpcRelationships().adjustFromProximity(a.getUUID(), 1, now);
            }
        }
    }
}

// =============================================================================
// APPRENTICESHIP WEEKLY TICK (interval = 168000 ≈ 7 in-game days, priority = 192)
// =============================================================================

/**
 * Drives {@link tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipManager#weeklyTick}.
 * Spec line 148: "Milestone progression is checked weekly."
 */
class ApprenticeshipWeeklyTickSystem implements TickSubsystem {
    @Override public String name()     { return "apprenticeship_weekly"; }
    @Override public int    interval() { return 7 * 24000; }
    @Override public int    priority() { return 192; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipManager
                .weeklyTick(ctx.level());
    }
}

// =============================================================================
// GOSSIP SCHEDULER (interval = 20, priority = 195)
// =============================================================================

/**
 * Drives {@link tterrag1112.life_in_the_village.Npc.Gossip.GossipScheduler}
 * once per second: ages active channels, runs exchanges, and rolls new
 * channels for SOCIAL-phase NPC pairs in proximity. Spec
 * {@code 12-gossip-rumor.md} line 70.
 */
class GossipSchedulerTickSystem implements TickSubsystem {
    @Override public String name()     { return "gossip_scheduler"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 195; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Npc.Gossip.GossipScheduler
                .tick(ctx.level(), ctx.tick());
    }
}

// =============================================================================
// TOLL GATE (interval = 20, priority = 119)
// =============================================================================

class TollGateTickSystem implements TickSubsystem {
    @Override public String name()     { return "toll_gates"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 119; }

    @Override
    public void tick(TickContext ctx) {
        TollGateSystem.tick(ctx.level(), ctx.villageData());
    }
}

// =============================================================================
// OFFICE ELECTIONS (once per in-game day, interval = 24000, priority = 195)
// =============================================================================

/**
 * Drives the office-framework election cycle once per in-game day per
 * spec {@code 06-office-framework.md} → "Term end detection" /
 * "Selection methods". Walks every loaded org, vacates term-ended seats,
 * and re-runs the appropriate selection engine for each vacancy.
 *
 * <p>Priority 195 places this just before the gossip scheduler so a
 * promotion/demotion fired from the election can seed gossip on the
 * same day.</p>
 */
class OfficeElectionTickSystem implements TickSubsystem {
    @Override public String name()     { return "office_elections"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 195; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Npc.Office.OfficeElection.dailyTick(ctx.level());
    }
}

// =============================================================================
// LAW DECISION (once per in-game day, interval = 24000, priority = 197)
// =============================================================================

/**
 * NPC-leader autonomy. Per spec doc 22 line 99, every village whose
 * {@code village_leader} is held by an NPC gets a daily roll on the
 * {@link tterrag1112.life_in_the_village.Npc.Laws.LawDecisionEngine}.
 *
 * <p>Priority 197 places this just after office elections (195) — a
 * leader who was just installed gets a chance to enact something the
 * same day.</p>
 */
class LawDecisionTickSystem implements TickSubsystem {
    @Override public String name()     { return "law_decision"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 197; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Npc.Laws.LawDecisionEngine.dailyTick(ctx.level());
    }
}

// =============================================================================
// CRIME TRIAL (once per in-game day, interval = 24000, priority = 198)
// =============================================================================

/**
 * Phase 3 doc 19 trial-execution + cold-report sweep. Runs every
 * scheduled trial whose tick has passed and ages stale FILED reports
 * to COLD after 30 days. Priority 198 — just after law decisions
 * (197), so a same-day enacted DOUBLE_PUNISHMENT is read by the trial
 * that fires immediately afterward.
 */
class CrimeTrialTickSystem implements TickSubsystem {
    @Override public String name()     { return "crime_trial"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 198; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Npc.Crime.TrialExecutor.runDue(ctx.level());
    }
}

// =============================================================================
// RELIGION RITES (once per in-game day, interval = 24000, priority = 199)
// =============================================================================

/**
 * Phase 3 doc 20 rite execution + holy-day calendar pass. Runs every
 * scheduled rite whose tick has passed and queues HARVEST_THANKSGIVING /
 * FEAST_DAY when today matches the village's religion calendar.
 * Priority 199 — after crime trials so a SERIOUS guilty verdict's
 * kingdom history entry lands before a same-day religious rite.
 */
class ReligionRiteTickSystem implements TickSubsystem {
    @Override public String name()     { return "religion_rite"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 199; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Npc.Religion.RiteScheduler.dailyTick(ctx.level());
    }
}

/**
 * Phase 3 doc 21 — daily medical sweep. Runs after religion (199) so
 * healed-by-confession side effects from rites land before resolution.
 */
class HealthDailyTickSystem implements TickSubsystem {
    @Override public String name()     { return "health_daily"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 200; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Npc.Health.HealthTicker.dailyTick(ctx.level());
    }
}

// =============================================================================
// HOUSE FOUNDING (Track D3.2b — once per in-game day, priority = 196)
// =============================================================================

/**
 * Track D3.2b — runs the house-founding scan once per in-game day.
 * Priority 196 places it between office elections (195) and law
 * decisions (197), so a same-day promotion can lift an NPC over
 * the rank gate before the founding scan reads it.
 */
class HouseFoundingTickSystem implements TickSubsystem {
    @Override public String name()     { return "house_founding"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 196; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Npc.Nobility.HouseFoundingDriver
                .dailyTick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

// =============================================================================
// PROVINCE RECOMPUTE (Track D3.3 — once per in-game week, priority = 191)
// =============================================================================

/**
 * Track D3.3 — weekly baseline of the C-hybrid polygon update timing
 * (manor-event invalidation handled via {@code NobilityEventDispatcher}).
 * Walks every kingdom; recomputes provinces when the kingdom is past
 * the {@code MIN_VILLAGES_FOR_SUBDIVISION} gate AND its
 * {@code lastProvinceRecomputeTick} is older than the weekly
 * cadence. Priority 191 places it before office elections so a
 * same-day governor selection sees fresh province assignments.
 */
class ProvinceRecomputeTickSystem implements TickSubsystem {
    /** Weekly cadence: 7 in-game days. */
    public static final long RECOMPUTE_INTERVAL_TICKS = 7L * 24000L;

    @Override public String name()     { return "province_recompute"; }
    /** Tick every in-game day so the {@code isProvinceRecomputeDue}
     *  check fires once per kingdom on the right day. The actual
     *  cost is a single integer comparison per kingdom. */
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 191; }

    @Override
    public void tick(TickContext ctx) {
        for (var kingdom : ctx.villageData().getAllKingdoms()) {
            if (!kingdom.isProvinceRecomputeDue(ctx.tick(), RECOMPUTE_INTERVAL_TICKS)) continue;
            tterrag1112.life_in_the_village.Kingdom.Provinces.ProvinceComputer
                    .recompute(ctx.level(), ctx.villageData(), kingdom, ctx.tick());
        }
    }
}

// =============================================================================
// PROVINCE DAILY (Track D3.3 — once per in-game day, priority = 194)
// =============================================================================

/**
 * Track D3.3 — daily provincial tick. Per-province: ticks stability
 * decay, generates a {@link tterrag1112.life_in_the_village.Kingdom.Provinces.ProvincialReport}
 * appended to the rolling buffer, applies ungoverned-status pressure.
 * The actual tax flow happens via {@code FealtyChain} in the
 * existing {@code KingdomTaxEvent}; this tick is the report +
 * stability summary pass.
 *
 * <p>Priority 194 — after province recompute (191), before office
 * elections (195) so a freshly-deposed governor's province reflects
 * the change in the same daily cycle.
 */
class ProvinceDailyTickSystem implements TickSubsystem {
    @Override public String name()     { return "province_daily"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 194; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Kingdom.Provinces.ProvincialDailyDriver
                .dailyTick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

/**
 * Track D3.5A — daily audience-loop sweep. Runs petition expiry +
 * resolved-entry GC + standing decay across every kingdom in the
 * world. Priority 195 — immediately after province daily so
 * province-tied standing changes feed today's queue.
 */
class AudienceLoopTickSystem implements TickSubsystem {
    @Override public String name()     { return "audience_loop"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 195; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Kingdom.Audience.AudienceLoopDriver
                .dailyTick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

/**
 * Track D3.6.1 — daily province-rebellion driver. Priority 197 —
 * after audience-loop (195) and NPC-ruler audit (196), so today's
 * province stability changes feed in before rebellion evaluation.
 */
class RebellionTickSystem implements TickSubsystem {
    @Override public String name()     { return "rebellion"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 197; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Kingdom.Rebellion.RebellionEngine
                .dailyTick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

/**
 * Track D3.6.1 — daily vassal-rebellion driver. Priority 198 —
 * after the province rebellion driver so per-vassal loyalty
 * computation reads today's rebellion outcomes.
 */
class VassalRebellionTickSystem implements TickSubsystem {
    @Override public String name()     { return "vassal_rebellion"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 198; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Kingdom.Rebellion.VassalRebellionDriver
                .dailyTick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

/**
 * Track D3.6.2 — daily kingdom-collapse driver. Priority 199 —
 * runs after the secession path so today's secessions feed
 * collapse-tracking computation.
 */
class KingdomCollapseTickSystem implements TickSubsystem {
    @Override public String name()     { return "kingdom_collapse"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 199; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Kingdom.Rebellion.CollapseEngine
                .dailyTick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

/**
 * Track D3.6.5 — daily religion-authority engine. Priority 202 —
 * after war scoring so today's wins/losses feed sanctification
 * and tension recompute.
 */
class ReligionAuthorityTickSystem implements TickSubsystem {
    @Override public String name()     { return "religion_authority"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 202; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Kingdom.Religion.ReligionAuthorityEngine
                .dailyTick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

/**
 * Track D3.6.5 — daily conversion-campaign sweep. Priority 203 —
 * runs after religion authority so newly-stamped tension is
 * picked up by today's campaign completion checks.
 */
class ConvertProvinceTickSystem implements TickSubsystem {
    @Override public String name()     { return "convert_province"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 203; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Kingdom.Religion.ConvertProvinceDriver
                .dailyTick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

/**
 * Track D3.6.6 — daily age-cycle evaluator. Priority 204 — runs
 * last in the Phase 6 chain so transitions reflect everything
 * that happened today.
 */
class AgeCycleTickSystem implements TickSubsystem {
    @Override public String name()     { return "age_cycle"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 204; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Kingdom.AgeCycle.AgeCycleDriver
                .dailyTick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

/**
 * Track D3.6.4 — daily war engine tick (battle scheduling +
 * scoring + resolution). Priority 201 — runs after the
 * fragmentation drivers so today's collapses / secessions feed
 * war-state computation.
 */
class WarTickSystem implements TickSubsystem {
    @Override public String name()     { return "war_engine"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 201; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Kingdom.War.WarEngine
                .dailyTick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

/**
 * Track D3.6.3 — daily voluntary-union petition driver. Priority
 * 200 — runs after collapse so already-collapsed weak kingdoms
 * don't try to petition.
 */
class VoluntaryUnionTickSystem implements TickSubsystem {
    @Override public String name()     { return "voluntary_union"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 200; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Kingdom.Merger.MergerEngine
                .dailyTick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

/**
 * Track D3.5B — daily NPC-ruler petition audit. Scores every
 * pending petition in NPC-ruled kingdoms via trait-weighted
 * formula in {@link tterrag1112.life_in_the_village.Kingdom.Audience.NpcRulerAuditor};
 * auto-approves / auto-denies entries past the threshold; leaves
 * the rest pending. Priority 196 — immediately after the
 * audience-loop sweep (195) so today's expiries are off the queue
 * before the ruler scores it.
 */
class NpcRulerAuditTickSystem implements TickSubsystem {
    @Override public String name()     { return "npc_ruler_audit"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 196; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Kingdom.Audience.NpcRulerAuditor
                .dailyTick(ctx.level(), ctx.villageData(), ctx.tick());
    }
}

/**
 * Weekly plague-outbreak roll per village (spec line 169). Runs once
 * a week ({@code 168000} ticks); chance is gated on village size +
 * region (Phase 4 visitor flux is stubbed for v1).
 */
class PlagueRollTickSystem implements TickSubsystem {
    @Override public String name()     { return "plague_roll"; }
    @Override public int    interval() { return 7 * 24000; }
    @Override public int    priority() { return 201; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Npc.Health.PlagueScheduler.weeklyRoll(ctx.level());
    }
}

/**
 * Phase 4 doc 26 — daily decision loop for NPC-owned businesses.
 * Runs the bankruptcy clock, succession on owner death, and the
 * eligibility scan that promotes long-tenured merchants to trading
 * businesses. Priority 202 — after religion (199), health (200),
 * plague (201) so per-day economic outcomes feed the decision.
 */
class CompanyAiTickSystem implements TickSubsystem {
    @Override public String name()     { return "company_ai"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 202; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Guilds.Companies.Ai.AiBusinessManager
                .dailyTick(ctx.level());
    }
}

/**
 * Phase 4 doc 28 — daily request-board maintenance: completes ready
 * fulfilments, expires stale requests, escalates scope, runs the
 * acceptance pass, and prunes terminal records. Priority 203 —
 * after business AI so businesses get a chance to post / accept on
 * their own first.
 */
class RequestBoardTickSystem implements TickSubsystem {
    @Override public String name()     { return "request_board"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 203; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Guilds.Common.Requests.RequestBoardTicker
                .dailyTick(ctx.level());
    }
}

/**
 * Phase 4 doc 29 — daily visitor flux. Despawns expired visitors,
 * computes per-village capacity, and rolls new arrivals weighted by
 * the village's infrastructure mix. Priority 204 — after request
 * board so a village's same-day economic state has settled before
 * we decide who's interested in showing up.
 */
class VisitorFluxTickSystem implements TickSubsystem {
    @Override public String name()     { return "visitor_flux"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 204; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Npc.Visitor.VisitorFluxEngine
                .dailyTick(ctx.level());
    }
}

/**
 * Phase 4 doc 30 — daily prune of village history logs. Drops
 * entries past their importance retention window and enforces the
 * per-village cap. Priority 205 — runs last so producers fired by
 * the same daily wave land in the log first, then the prune sees
 * the latest state.
 */
class HistoryPruneTickSystem implements TickSubsystem {
    @Override public String name()     { return "history_prune"; }
    @Override public int    interval() { return 24000; }
    @Override public int    priority() { return 205; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Village.History.VillageHistoryLog
                .get(ctx.level())
                .prune(ctx.tick());
    }
}

/**
 * Track C3.1 — advances every {@code IN_PROGRESS}
 * {@link tterrag1112.life_in_the_village.Village.Roads.Proposal.RoadProposal}
 * once per second. Calling
 * {@link tterrag1112.life_in_the_village.Village.Roads.Proposal.RoadProposalManager#tick}
 * is enough; the manager handles per-proposal advancement, completion
 * commit, and EdgeRealizer dispatch.
 */
class RoadProposalTickSystem implements TickSubsystem {
    @Override public String name()     { return "road_proposals"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 210; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Village.Roads.Proposal
                .RoadProposalManager.tick(ctx.level());
    }
}

/**
 * Track C3.2 — POI discovery (player-proximity scan) followed by
 * subroad planning. Runs every 10 seconds. The discovery half scans
 * loaded chunks near each online player; the planning half drains any
 * PENDING POIs through {@link tterrag1112.life_in_the_village
 * .Village.Roads.Poi.PoiSubroadPlanner#planAllPending}.
 */
class PoiDiscoveryTickSystem implements TickSubsystem {
    @Override public String name()     { return "poi_discovery"; }
    @Override public int    interval() { return 200; }
    @Override public int    priority() { return 215; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Village.Roads.Poi
                .PoiDiscovery.scanForPlayers(ctx.level());
        tterrag1112.life_in_the_village.Village.Roads.Poi
                .PoiSubroadPlanner.planAllPending(ctx.level());
    }
}

/**
 * Track D3.1 — kingdom office bootstrap. Once per second, scan
 * kingdoms whose capital village has realised (buildingIds non-empty)
 * but whose kingdom_king is still vacant; fresh-spawn the founding
 * ruler and draft / fresh-spawn the culture's required offices.
 * Idempotent: skips kingdoms whose king is already seated.
 */
class KingdomOfficeBootstrapTickSystem implements TickSubsystem {
    @Override public String name()     { return "kingdom_office_bootstrap"; }
    @Override public int    interval() { return 20; }
    @Override public int    priority() { return 220; }

    @Override
    public void tick(TickContext ctx) {
        tterrag1112.life_in_the_village.Kingdom.Worldgen
                .KingdomOfficeBootstrap.runOnce(ctx.level());
    }
}