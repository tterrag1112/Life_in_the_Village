package tterrag1112.life_in_the_village.Events;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerSavedData;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.CaravanSavedData;

import static tterrag1112.life_in_the_village.Life_in_the_village.MODID;

/**
 * Entry point for all per-tick server logic.
 *
 * <h3>Architecture</h3>
 * This class is intentionally thin. It:
 * <ol>
 *   <li>Acquires the shared data references once per tick</li>
 *   <li>Builds an immutable {@link TickSubsystem.TickContext}</li>
 *   <li>Delegates to {@link TickSubsystemRegistry#tickAll}</li>
 * </ol>
 *
 * All actual logic lives in {@link TickSubsystem} implementations registered
 * in {@link TickSubsystemRegistry}. This makes it trivial to add, remove,
 * reorder, or disable systems without touching this file.
 *
 * <h3>Error isolation</h3>
 * The registry wraps each subsystem call in try-catch. A failure in one
 * system (e.g. caravan pathfinding) will not prevent other systems
 * (e.g. crafting orders, property tax) from running.
 *
 * <h3>Profiling</h3>
 * Each subsystem's execution time is recorded by the registry. Use
 * {@code /liv debug tick_timings} (if implemented) to see per-system
 * microsecond costs.
 */
@EventBusSubscriber(modid = MODID)
public class ServerTickDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean initialized = false;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // ── Build shared context ─────────────────────────────────────────────
        ServerLevel overworld = event.getServer().overworld();
        long tick = overworld.getGameTime();

        VillageSavedData vdata = VillageSavedData.get(overworld);

        // ── One-time initialization on first tick ────────────────────────────
        if (!initialized) {
            TickSubsystemRegistry.registerDefaults();
            // Track C3.3 — one-shot, idempotent migration of legacy
            // SeaRoute records to SEA-tier RoadEdges. Gated by
            // WorldRoadSavedData.isSeaRoutesMigrated; safe to call on
            // every load (cheap when already migrated).
            tterrag1112.life_in_the_village.Village.Economy.Trade
                    .SeaRouteMigration.migrateIfNeeded(overworld);
            // Track D1 — back-fill Village.kingdomId from each
            // Kingdom's villageIds list. Idempotent; gated by
            // VillageSavedData.kingdomMembershipMigrated.
            tterrag1112.life_in_the_village.Kingdom
                    .KingdomMembershipMigration.migrateIfNeeded(overworld);
            initialized = true;
            LOGGER.info("ServerTickDispatcher: subsystem registry initialized");
        }

        BusinessSavedData    cdata = BusinessSavedData.get(overworld);
        AdventurerSavedData ada   = AdventurerSavedData.get(overworld);
        CaravanSavedData    cara  = CaravanSavedData.get(overworld);

        TickSubsystem.TickContext ctx = new TickSubsystem.TickContext(
                overworld, tick, vdata, cdata, ada, cara);

        // ── Delegate ─────────────────────────────────────────────────────────
        TickSubsystemRegistry.tickAll(ctx);

        // ── Phase 6.3.3.g.1 — animal-husbandry roster driver ─────────────────
        // Each BuildingRoster's internal tick advances growth + breeding
        // + production. Realize/derealize on chunk-load events is wired
        // via RosterChunkLoadHandler; the production cycle itself is
        // gated per-species in BuildingRoster.tick (see Phase 6.7.1.5):
        // SHEEP and BEE skip simulation production for Realized slots
        // because their loaded production is driven by SHEPHERD /
        // BEEKEEPER behaviors; other species retain the slot-type-
        // agnostic legacy path until their commercial behaviors land.
        if (tick % 200L == 0L) {
            var rdata = tterrag1112.life_in_the_village.Village.Roster
                    .RosterSavedData.get(overworld);
            for (var perBuilding : rdata.getAllRosters().values()) {
                for (var roster : perBuilding.values()) {
                    try {
                        roster.tick(overworld,
                                tterrag1112.life_in_the_village.Village.Roster
                                        .BuildingStorageSink.forBuilding(overworld,
                                                roster.buildingId()));
                    } catch (RuntimeException e) {
                        LOGGER.warn("[Roster] tick failed for building {}: {}",
                                roster.buildingId(), e.toString());
                    }
                }
            }
        }

        // ── Phase 6.3.3.k.1 — per-village climate ticker ─────────────────────
        // Every 200 ticks (matches the roster cadence), walk villages
        // and record rain whenever the sky is raining AT the village
        // centre. Cold biomes get snow, which counts the same as rain
        // for drought-clock purposes (the ground is wet either way).
        // Desert / dry biomes never see rain so their drought clock
        // accumulates from world-start, which is what we want.
        if (tick % 200L == 0L && overworld.isRaining()) {
            for (var village : vdata.getAllVillages()) {
                var centre = village.getVillageCentre();
                if (centre == null) continue;
                if (overworld.isRainingAt(centre)) {
                    vdata.recordRain(village.getId(), tick);
                }
            }
        }

        // ── Phase 6.3.3.k.4 — daily blight roll + spread + auto-fallow ───────
        // Fires once per in-game day. For each CROP_FIELD plot, roll
        // the onset chance (modulated by soil quality, mono-cropping,
        // drought). Then for each blighted plot, roll spread to a
        // same-farmhouse healthy neighbour, and tick the auto-fallow
        // clock that retires plots blighted longer than the threshold.
        if (tick % 24000L == 0L) {
            var rng = new java.util.Random(tick);
            for (var plot : vdata.getAllFarmPlots()) {
                if (plot.getSubtype() != tterrag1112.life_in_the_village
                        .Village.Buildings.FarmPlot.PlotSubtype.CROP_FIELD) {
                    continue;
                }
                java.util.UUID vid = null;
                if (plot.getFarmhouseId() != null) {
                    for (var v : vdata.getAllVillages()) {
                        if (v.getBuildingIds().contains(plot.getFarmhouseId())) {
                            vid = v.getId();
                            break;
                        }
                    }
                }
                boolean drought = tterrag1112.life_in_the_village.World
                        .WeatherContext.isDrought(overworld, vid);
                if (plot.rollDailyBlight(tick, drought, rng)) {
                    vdata.markDirty();
                }
                if (plot.tickBlightAutoFallow(tick)) {
                    vdata.markDirty();
                }
            }
            // Spread pass: each currently-blighted plot rolls once
            // against a randomly picked same-farmhouse healthy neighbour.
            for (var src : vdata.getAllFarmPlots()) {
                if (!src.isBlighted()) continue;
                if (src.getFarmhouseId() == null) continue;
                if (rng.nextFloat() >= tterrag1112.life_in_the_village
                        .Village.Buildings.FarmPlot.BLIGHT_SPREAD_DAILY_CHANCE) continue;
                var siblings = vdata.getFarmPlotsForFarmhouse(src.getFarmhouseId());
                java.util.List<tterrag1112.life_in_the_village.Village
                        .Buildings.FarmPlot> healthy = new java.util.ArrayList<>();
                for (var sib : siblings) {
                    if (sib != src && !sib.isBlighted()
                            && sib.getSubtype() == tterrag1112.life_in_the_village
                                    .Village.Buildings.FarmPlot.PlotSubtype.CROP_FIELD) {
                        healthy.add(sib);
                    }
                }
                if (healthy.isEmpty()) continue;
                var pick = healthy.get(rng.nextInt(healthy.size()));
                pick.setBlight(tick);
                vdata.markDirty();
            }
        }
    }
}