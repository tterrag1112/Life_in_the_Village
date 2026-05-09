package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Trade.AtlasRouteRouter;
import tterrag1112.life_in_the_village.Village.Economy.Trade.Caravan;
import tterrag1112.life_in_the_village.Village.Economy.Trade.CaravanSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.GraphTradeRouteEstablisher;
import tterrag1112.life_in_the_village.Village.Economy.Trade.RouteSegment;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoute;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.CulturePalette;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.CulturePaletteResolver;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.PaletteRegistry;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.PathMaterial;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Roads.Events.EventLifecycleSystem;
import tterrag1112.life_in_the_village.Village.Roads.Events.EventPlacementContext;
import tterrag1112.life_in_the_village.Village.Roads.Events.RoadEvent;
import tterrag1112.life_in_the_village.Village.Roads.Events.RoadEventRegistry;
import tterrag1112.life_in_the_village.Village.Roads.Events.RoadEventType;
import tterrag1112.life_in_the_village.Village.Roads.Lifecycle.DeadEdgeDetector;
import tterrag1112.life_in_the_village.Village.Roads.Lifecycle.DeadEdgeState;
import tterrag1112.life_in_the_village.Village.Roads.Lifecycle.NetworkAlignmentScorer;
import tterrag1112.life_in_the_village.Village.Roads.Lifecycle.ReclaimedEdgeCleanup;
import tterrag1112.life_in_the_village.Village.Roads.Lighting.RoadLightingPlacer;
import tterrag1112.life_in_the_village.Village.Roads.Lighting.RoadLightingProfile;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Roads.Debug.RoadDebugVisualizer;
import tterrag1112.life_in_the_village.Village.Roads.Debug.RoadDebugVisualizer.ParticleEmission;
import tterrag1112.life_in_the_village.Village.Roads.Docking.VillageDockingPoint;
import tterrag1112.life_in_the_village.Village.Roads.Graph.GraphInvariantValidator;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Roads.Planning.ConnectorPlanner;
import tterrag1112.life_in_the_village.Village.Roads.Planning.ParallelismDetector;
import tterrag1112.life_in_the_village.Village.Roads.Planning.ParallelismResolver;
import tterrag1112.life_in_the_village.Events.GreatRoadGenerationQueue;
import tterrag1112.life_in_the_village.Kingdom.WorldgenKingdomSeeder;
import tterrag1112.life_in_the_village.Events.RoadTerrainChangeListener;
import tterrag1112.life_in_the_village.Village.Roads.Graph.GreatRoadCharacter;
import tterrag1112.life_in_the_village.Village.Roads.Graph.GreatRoadCharacter.CharacterTag;
import tterrag1112.life_in_the_village.Village.Roads.Travel.RoadProximityCache;
import tterrag1112.life_in_the_village.Village.Roads.Travel.RoadProximityChecker;
import tterrag1112.life_in_the_village.Village.Roads.Travel.RoadSpeedModifier;
import tterrag1112.life_in_the_village.Village.Roads.Travel.RoadUnderfootDetector;
import tterrag1112.life_in_the_village.Events.RoadSafetySystem;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.ShelterBuilder;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.ShelterInstance;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.ShelterPlanner;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.TollGateBuilder;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.TollGatePlanner;
import tterrag1112.life_in_the_village.Village.Roads.Economy.TollFeeCalculator;
import net.minecraft.world.level.ChunkPos;
import tterrag1112.life_in_the_village.Village.Roads.Graph.Worldgen.GreatRoadAnchorSeeder;
import tterrag1112.life_in_the_village.Village.Roads.Graph.Worldgen.GreatRoadAnchorSeeder.AnchorCandidate;
import tterrag1112.life_in_the_village.Village.Roads.Graph.Worldgen.NamedRoadSelector;
import tterrag1112.life_in_the_village.Events.RoadUpkeepSystem;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.JunctionDecorator;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.MilestoneDecorator;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.RoadOvergrowthDecorator;
import tterrag1112.life_in_the_village.Village.Roads.Economy.EdgeTierManager;
import tterrag1112.life_in_the_village.Village.Roads.Economy.RoadUpkeepCalculator;
import tterrag1112.life_in_the_village.Village.Roads.Economy.TierPromotionRules;
import tterrag1112.life_in_the_village.Village.Roads.Economy.VillageUpkeepLedger;
import tterrag1112.life_in_the_village.Events.TierReconciliationSystem;
import tterrag1112.life_in_the_village.Village.Roads.Realization.EdgeRealizer;
import tterrag1112.life_in_the_village.Networking.VillageRoadsSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Terrain.GreatRoadProfile;
import tterrag1112.life_in_the_village.Village.Roads.Terrain.GreatRoadProfile.PositionClassification;
import tterrag1112.life_in_the_village.Village.Roads.Terrain.RetainingWallBuilder;
import tterrag1112.life_in_the_village.Village.Roads.Terrain.RoadSmoother;
import tterrag1112.life_in_the_village.Village.Roads.Terrain.RoadSupportBuilder;
import tterrag1112.life_in_the_village.Village.Roads.Planning.GatewayPopulator;
import tterrag1112.life_in_the_village.Village.Roads.Terrain.TerrainClearer;
import tterrag1112.life_in_the_village.Village.Roads.Terrain.VegetationFeatureDetector;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Debug visualization commands for the world road graph.
 *
 * <p>Registered under {@code /liv road debug} (singular "road", distinct from the
 * legacy {@code /liv roads} block-manipulation commands). Requires OP level 2
 * and must be run by a player — not from console.
 *
 * <p>All visualization creates timed {@link RoadDebugVisualizer.VisualizationSession}s
 * that emit player-local particles for {@link RoadDebugVisualizer#DURATION_TICKS} ticks
 * (30 seconds). Multiple subcommands can run simultaneously per player; each adds its
 * own session.
 *
 * <h3>Particle convention (matches ROADS_PLAN.md Phase 1.5)</h3>
 * <ul>
 *   <li>GREAT_ROAD edges: {@code END_ROD} (white)</li>
 *   <li>TRUNK edges: {@code FLAME} (orange)</li>
 *   <li>CONNECTOR edges: {@code COMPOSTER} (green)</li>
 *   <li>LOCAL edges: {@code SMOKE} (grey)</li>
 *   <li>Anchor/junction nodes: {@code END_ROD}</li>
 *   <li>Village dock nodes: {@code HAPPY_VILLAGER}</li>
 *   <li>Toll gate nodes: {@code SOUL_FIRE_FLAME}</li>
 *   <li>Terminus/POI/waystation nodes: {@code SMOKE}</li>
 * </ul>
 */
public class RoadGraphDebugCommand {

    private static final int RADIUS_STANDARD = 512;
    private static final int RADIUS_PARALLEL  = 1024;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("liv")
                .then(Commands.literal("road")
                        .then(Commands.literal("debug")
                                .then(Commands.literal("show_graph")
                                        .executes(RoadGraphDebugCommand::showGraph))
                                .then(Commands.literal("highlight_edge")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::highlightEdge)))
                                .then(Commands.literal("show_parallel_pairs")
                                        .executes(RoadGraphDebugCommand::showParallelPairs))
                                .then(Commands.literal("show_junctions")
                                        .executes(RoadGraphDebugCommand::showJunctions))
                                .then(Commands.literal("show_staleness")
                                        .executes(RoadGraphDebugCommand::showStaleness))
                                .then(Commands.literal("show_traffic")
                                        .executes(RoadGraphDebugCommand::showTraffic))
                                .then(Commands.literal("show_maintenance")
                                        .executes(RoadGraphDebugCommand::showMaintenance))
                                .then(Commands.literal("show_overgrowth")
                                        .executes(RoadGraphDebugCommand::showOvergrowth))
                                .then(Commands.literal("seed_great_road")
                                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                .executes(RoadGraphDebugCommand::seedGreatRoad))))))
                                .then(Commands.literal("connect_village")
                                        .then(Commands.argument("villageName", StringArgumentType.word())
                                        .then(Commands.argument("targetEdgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::connectVillage))))
                                .then(Commands.literal("dispatch_test_caravan_between")
                                        .then(Commands.argument("villageA", StringArgumentType.word())
                                        .then(Commands.argument("villageB", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::dispatchTestCaravanBetween))))
                                .then(Commands.literal("caravan_status")
                                        .executes(RoadGraphDebugCommand::caravanStatus))
                                .then(Commands.literal("replan_connector")
                                        .then(Commands.argument("villageName", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::replanConnector)))
                                .then(Commands.literal("inspect_junction")
                                        .then(Commands.argument("nodeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::inspectJunction)))
                                // Track C3.1 — force a road proposal to completion.
                                .then(Commands.literal("complete_proposal")
                                        .then(Commands.argument("proposalIdPrefix", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::completeProposal)))
                                .then(Commands.literal("list_proposals")
                                        .executes(RoadGraphDebugCommand::listProposals))
                                .then(Commands.literal("cleanup_scan")
                                        .executes(RoadGraphDebugCommand::cleanupScan)
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(64, 4096))
                                                .executes(RoadGraphDebugCommand::cleanupScanWithRadius)))
                                .then(Commands.literal("cleanup_merge")
                                        .then(Commands.argument("edgeAId", StringArgumentType.word())
                                        .then(Commands.argument("edgeBId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::cleanupMerge))))
                                .then(Commands.literal("invalidate_cell")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(RoadGraphDebugCommand::invalidateCell))))
                                .then(Commands.literal("material_preview")
                                        .then(Commands.argument("culture", StringArgumentType.word())
                                        .then(Commands.argument("tier", StringArgumentType.word())
                                        .then(Commands.argument("maintenance", IntegerArgumentType.integer(0, 100))
                                                .executes(RoadGraphDebugCommand::materialPreview)
                                                .then(Commands.argument("season", StringArgumentType.word())
                                                        .executes(RoadGraphDebugCommand::materialPreviewWithSeason))))))
                                .then(Commands.literal("force_maintenance")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                        .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                                                .executes(RoadGraphDebugCommand::forceMaintenance))))
                                .then(Commands.literal("redecorate_edge")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::redecoratEdge)))
                                .then(Commands.literal("redecorate_node")
                                        .then(Commands.argument("nodeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::redecoratNode)))
                                .then(Commands.literal("show_decorations")
                                        .executes(RoadGraphDebugCommand::showDecorations))
                                .then(Commands.literal("village_upkeep")
                                        .then(Commands.argument("villageName", StringArgumentType.greedyString())
                                                .executes(RoadGraphDebugCommand::villageUpkeepReport)))
                                .then(Commands.literal("trigger_upkeep")
                                        .executes(RoadGraphDebugCommand::triggerUpkeep))
                                .then(Commands.literal("force_decay_cycle")
                                        .then(Commands.argument("cycles", IntegerArgumentType.integer(1, 200))
                                                .executes(RoadGraphDebugCommand::forceDecayCycle)))
                                .then(Commands.literal("village_tier")
                                        .then(Commands.argument("villageName", StringArgumentType.greedyString())
                                                .executes(RoadGraphDebugCommand::villageTierReport)))
                                .then(Commands.literal("promote_village")
                                        .then(Commands.argument("villageName", StringArgumentType.word())
                                        .then(Commands.argument("newTier", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::promoteVillage))))
                                .then(Commands.literal("reconcile_all")
                                        .executes(RoadGraphDebugCommand::reconcileAll))
                                // ── Phase 7a: great-road generation ──────────────────────
                                .then(Commands.literal("show_great_roads")
                                        .executes(RoadGraphDebugCommand::showGreatRoads))
                                .then(Commands.literal("show_anchors")
                                        .executes(RoadGraphDebugCommand::showAnchors))
                                .then(Commands.literal("generation_status")
                                        .executes(RoadGraphDebugCommand::generationStatus))
                                .then(Commands.literal("force_complete_generation")
                                        .executes(RoadGraphDebugCommand::forceCompleteGeneration))
                                .then(Commands.literal("dominant_axis")
                                        .executes(RoadGraphDebugCommand::dominantAxis))
                                // ── Phase 7b: named roads ─────────────────────────────────
                                .then(Commands.literal("named_roads")
                                        .executes(RoadGraphDebugCommand::namedRoads))
                                .then(Commands.literal("highlight_named")
                                        .then(Commands.argument("namePrefix", StringArgumentType.greedyString())
                                                .executes(RoadGraphDebugCommand::highlightNamed)))
                                .then(Commands.literal("reselect_names")
                                        .executes(RoadGraphDebugCommand::reselectNames))
                                // ── Phase 7c: great road character ────────────────────────
                                .then(Commands.literal("character")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::roadCharacter)))
                                .then(Commands.literal("characters")
                                        .executes(RoadGraphDebugCommand::roadCharacters))
                                .then(Commands.literal("highlight_character")
                                        .then(Commands.argument("tag", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::highlightCharacter)))
                                // ── Phase 8a: travel incentives ───────────────────────────
                                .then(Commands.literal("underfoot")
                                        .executes(RoadGraphDebugCommand::underfoot))
                                .then(Commands.literal("simulate_walk")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::simulateWalk)))
                                .then(Commands.literal("speed_report")
                                        .executes(RoadGraphDebugCommand::speedReport))
                                // ── Phase 8b: safety gradient ─────────────────────────────
                                .then(Commands.literal("safety_check")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(RoadGraphDebugCommand::safetyCheck)))))
                                .then(Commands.literal("simulate_spawn")
                                        .then(Commands.argument("mob_type", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::simulateSpawn)))
                                .then(Commands.literal("safety_stats")
                                        .executes(RoadGraphDebugCommand::safetyStats))
                                // ── Phase 8c: shelter expectation ─────────────────────────
                                .then(Commands.literal("shelters_on_edge")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::sheltersOnEdge)))
                                .then(Commands.literal("nearest_shelter")
                                        .executes(RoadGraphDebugCommand::nearestShelter))
                                .then(Commands.literal("force_place_shelters")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::forcePlaceShelters)))
                                .then(Commands.literal("force_place_shelter_type")
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::forcePlaceShelterType)))
                                .then(Commands.literal("replace_shelter")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::replaceShelter)))
                                // ── Phase 8d: toll gates ──────────────────────────────────────
                                .then(Commands.literal("list_tolls")
                                        .executes(RoadGraphDebugCommand::listTolls))
                                .then(Commands.literal("simulate_toll")
                                        .then(Commands.argument("kingdomName", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::simulateToll)))
                                .then(Commands.literal("toll_revenue")
                                        .then(Commands.argument("kingdomName", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::tollRevenue)))
                                .then(Commands.literal("force_place_toll")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::forcePlaceToll)))
                                .then(Commands.literal("replace_toll")
                                        .then(Commands.argument("nodeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::replaceToll)))
                                // ── Phase 7d: worldgen timing ─────────────────────────────────
                                .then(Commands.literal("worldgen_status")
                                        .executes(RoadGraphDebugCommand::worldgenStatus))
                                .then(Commands.literal("worldgen_timing")
                                        .executes(RoadGraphDebugCommand::worldgenTiming))
                                // ── B4: vegetation feature debug ──────────────────────────────
                                .then(Commands.literal("detect_feature")
                                        .executes(RoadGraphDebugCommand::detectFeature))
                                .then(Commands.literal("clear_feature")
                                        .executes(RoadGraphDebugCommand::clearFeature))
                                .then(Commands.literal("clear_corridor")
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                                .executes(RoadGraphDebugCommand::clearCorridor)))
                                // ── 7e: terrain authority debug ───────────────────────────────
                                .then(Commands.literal("profile")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::showProfile)))
                                .then(Commands.literal("show_supports")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::showSupports)))
                                .then(Commands.literal("force_rebuild_profile")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::forceRebuildProfile)))
                                .then(Commands.literal("supports_report")
                                        .executes(RoadGraphDebugCommand::supportsReport))
                                // ── 7f Slice 1: village road graph debug ──────────────────────
                                .then(Commands.literal("village_graph")
                                        .then(Commands.argument("villageName", StringArgumentType.greedyString())
                                                .executes(RoadGraphDebugCommand::villageGraph)))
                                .then(Commands.literal("validate_village_graphs")
                                        .executes(RoadGraphDebugCommand::validateVillageGraphs))
                                .then(Commands.literal("show_village_graph")
                                        .then(Commands.argument("villageName", StringArgumentType.greedyString())
                                                .executes(RoadGraphDebugCommand::showVillageGraph)))
                                // ── 10: event infrastructure debug ────────────────────────────
                                .then(Commands.literal("events")
                                        .then(Commands.argument("targetId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::eventsForTarget)))
                                .then(Commands.literal("events_near")
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 8192))
                                                .executes(RoadGraphDebugCommand::eventsNear)))
                                .then(Commands.literal("spawn_event")
                                        .then(Commands.argument("typeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::spawnEvent)))
                                .then(Commands.literal("despawn_event")
                                        .then(Commands.argument("eventId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::despawnEvent)))
                                .then(Commands.literal("event_registry")
                                        .executes(RoadGraphDebugCommand::eventRegistry))
                                .then(Commands.literal("event_stats")
                                        .executes(RoadGraphDebugCommand::eventStats))
                                // ── 9: dead edges + network alignment debug ───────────────────
                                .then(Commands.literal("dead_edges")
                                        .executes(RoadGraphDebugCommand::deadEdges))
                                .then(Commands.literal("force_dead")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::forceDead)))
                                .then(Commands.literal("force_death_phase")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                        .then(Commands.argument("phase", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::forceDeathPhase))))
                                .then(Commands.literal("reclaim_edge")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::reclaimEdge)))
                                .then(Commands.literal("network_score")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(RoadGraphDebugCommand::networkScore)))))
                                .then(Commands.literal("village_death")
                                        .then(Commands.argument("villageName", StringArgumentType.greedyString())
                                                .executes(RoadGraphDebugCommand::villageDeath)))
                                // ── 7g: palette + lighting debug ──────────────────────────────
                                .then(Commands.literal("palette")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::paletteForEdge)))
                                .then(Commands.literal("force_lighting")
                                        .then(Commands.argument("edgeId", StringArgumentType.word())
                                        .then(Commands.argument("frequency", StringArgumentType.word())
                                        .then(Commands.argument("strategy", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::forceLighting)))))
                                .then(Commands.literal("lighting_summary")
                                        .executes(RoadGraphDebugCommand::lightingSummary))
                                .then(Commands.literal("list_palettes")
                                        .executes(RoadGraphDebugCommand::listPalettes))
                                // ── 7f Slice 2: gateway debug ─────────────────────────────────
                                .then(Commands.literal("village_gateways")
                                        .then(Commands.argument("villageName", StringArgumentType.greedyString())
                                                .executes(RoadGraphDebugCommand::villageGateways)))
                                .then(Commands.literal("test_gateway_selection")
                                        .then(Commands.argument("villageName", StringArgumentType.greedyString())
                                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                        .executes(RoadGraphDebugCommand::testGatewaySelection))))))
                                .then(Commands.literal("show_all_gateways")
                                        .executes(RoadGraphDebugCommand::showAllGateways))
                        )
                )
        );

        // ── Donate and repair (non-debug, player-facing) ──────────────────────
        dispatcher.register(Commands.literal("liv")
                .then(Commands.literal("road")
                        .then(Commands.literal("donate")
                                .then(Commands.argument("villageName", StringArgumentType.word())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(RoadGraphDebugCommand::donateToVillage))))
                        .then(Commands.literal("repair")
                                .then(Commands.argument("edgeId", StringArgumentType.word())
                                        .executes(RoadGraphDebugCommand::repairEdge)))
                )
        );

        // ── /litv toll pay (player-facing toll payment, Phase 8d) ────────────
        dispatcher.register(Commands.literal("litv")
                .then(Commands.literal("toll")
                        .then(Commands.literal("pay")
                                .executes(RoadGraphDebugCommand::playerTollPay))
                )
        );

        // ── /litv road propose <vA> <vB> [tier]  (Track C3.1) ────────────────
        dispatcher.register(Commands.literal("litv")
                .then(Commands.literal("road")
                        .then(Commands.literal("propose")
                                .then(Commands.argument("villageA", StringArgumentType.word())
                                .then(Commands.argument("villageB", StringArgumentType.word())
                                        .executes(RoadGraphDebugCommand::proposeRoadConnector)
                                        .then(Commands.argument("tier", StringArgumentType.word())
                                                .executes(RoadGraphDebugCommand::proposeRoadConnectorWithTier)))))
                        .then(Commands.literal("estimate")
                                .then(Commands.argument("villageA", StringArgumentType.word())
                                .then(Commands.argument("villageB", StringArgumentType.word())
                                        .executes(RoadGraphDebugCommand::estimateRoadConnector))))
                        .then(Commands.literal("cancel_proposal")
                                .then(Commands.argument("proposalIdPrefix", StringArgumentType.word())
                                        .executes(RoadGraphDebugCommand::cancelProposal))))
        );
    }

    // =========================================================================
    // show_graph
    // =========================================================================

    private static int showGraph(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<ParticleEmission> emissions = new ArrayList<>();

        // Edge trails
        List<RoadEdge> edges = graph.edgesNear(player.getBlockX(), player.getBlockZ(), RADIUS_STANDARD);
        for (RoadEdge edge : edges) {
            emissions.addAll(buildEdgeEmissions(edge, particleForTier(edge.getTier()), level,
                    RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
        }

        // Node beams — iterate all nodes and filter by distance
        int nodeCount = 0;
        for (RoadNode node : graph.allNodes()) {
            if (node.position().distSqr(player.blockPosition()) > (double) RADIUS_STANDARD * RADIUS_STANDARD) continue;
            emissions.addAll(buildNodeBeam(node, 10, particleForNodeType(node.type())));
            nodeCount++;
        }

        RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);

        int ec = edges.size(), nc = nodeCount;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Showing " + ec + " edges and " + nc + " nodes within "
                        + RADIUS_STANDARD + " blocks for 30 seconds."), false);
        return ec + nc;
    }

    // =========================================================================
    // highlight_edge
    // =========================================================================

    private static int highlightEdge(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        String input = StringArgumentType.getString(ctx, "edgeId").toLowerCase(Locale.ROOT);

        List<RoadEdge> matches = new ArrayList<>();
        for (RoadEdge edge : graph.allEdges()) {
            if (edge.getEdgeId().toString().startsWith(input)) matches.add(edge);
        }

        if (matches.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "No edge matches prefix '" + input + "'."));
            return 0;
        }
        if (matches.size() > 1) {
            String list = matches.stream()
                    .limit(5)
                    .map(e -> e.getEdgeId().toString().substring(0, Math.min(8, e.getEdgeId().toString().length())))
                    .reduce((a, b) -> a + ", " + b).orElse("");
            String suffix = matches.size() > 5 ? " (+" + (matches.size() - 5) + " more)" : "";
            ctx.getSource().sendFailure(Component.literal(
                    "Ambiguous edge prefix '" + input + "'. Matches: " + list + suffix + "."));
            return 0;
        }

        RoadEdge edge = matches.get(0);
        List<ParticleEmission> emissions = new ArrayList<>(
                buildEdgeEmissions(edge, ParticleTypes.ENCHANT, level,
                        RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));

        for (UUID nodeId : List.of(edge.getNodeAId(), edge.getNodeBId())) {
            RoadNode node = graph.getNode(nodeId);
            if (node != null) emissions.addAll(buildNodeBeam(node, 12, ParticleTypes.ENCHANT));
        }

        RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);

        String shortId = edge.getEdgeId().toString().substring(0, 8);
        List<Long> hCellPath  = edge.getCellPath();
        List<BlockPos> hBlockPath = edge.getBlockPath();

        String cellEnds = "";
        if (!hCellPath.isEmpty()) {
            BlockPos fc = AtlasRouteRouter.cellKeyToBlockCenter(hCellPath.get(0));
            BlockPos lc = AtlasRouteRouter.cellKeyToBlockCenter(hCellPath.get(hCellPath.size() - 1));
            cellEnds = " firstCell=(" + fc.getX() + ",0," + fc.getZ() + ")"
                     + " lastCell=(" + lc.getX() + ",0," + lc.getZ() + ")";
        }

        String blockInfo = "  blockPath=" + hBlockPath.size() + " blocks";
        if (!hBlockPath.isEmpty()) {
            blockInfo += " first=" + hBlockPath.get(0).toShortString()
                       + " last="  + hBlockPath.get(hBlockPath.size() - 1).toShortString();
        }

        RoadNode nA = graph.getNode(edge.getNodeAId());
        RoadNode nB = graph.getNode(edge.getNodeBId());
        String nodeAStr = "  nodeA: " + edge.getNodeAId().toString().substring(0, 8) + "/"
                + (nA != null ? nA.type() + " @" + nA.position().toShortString() : "MISSING");
        String nodeBStr = "  nodeB: " + edge.getNodeBId().toString().substring(0, 8) + "/"
                + (nB != null ? nB.type() + " @" + nB.position().toShortString() : "MISSING");

        String primitiveInfo = "  primitives=" + edge.getPrimitives().size();
        if (edge.hasPrimitives()) {
            primitiveInfo += " [" + edge.getPrimitives().stream()
                    .map(p -> p.typeKey())
                    .reduce((a, b) -> a + ", " + b).orElse("") + "]";
        } else {
            primitiveInfo += " (not yet derived)";
        }

        final String hLine0 = "Edge " + shortId + ": tier=" + edge.getTier()
                + " realized=" + edge.isRealized()
                + " maintenance=" + edge.getMaintenance()
                + " cells=" + hCellPath.size() + cellEnds;
        final String hLine1 = blockInfo;
        final String hLine2 = nodeAStr;
        final String hLine3 = nodeBStr;
        final String hLine4 = primitiveInfo;
        ctx.getSource().sendSuccess(() -> Component.literal(hLine0), false);
        ctx.getSource().sendSuccess(() -> Component.literal(hLine1), false);
        ctx.getSource().sendSuccess(() -> Component.literal(hLine2), false);
        ctx.getSource().sendSuccess(() -> Component.literal(hLine3), false);
        ctx.getSource().sendSuccess(() -> Component.literal(hLine4), false);
        return 1;
    }

    // =========================================================================
    // show_parallel_pairs
    // =========================================================================

    private static int showParallelPairs(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<RoadEdge> edges = graph.edgesNear(player.getBlockX(), player.getBlockZ(), RADIUS_PARALLEL);

        // Pre-compute sample point lists once per edge
        List<List<BlockPos>> samples = new ArrayList<>(edges.size());
        for (RoadEdge edge : edges) samples.add(sampleEdgeForParallel(edge, level));

        List<ParticleEmission> emissions = new ArrayList<>();
        int parallelCount = 0;

        for (int i = 0; i < edges.size(); i++) {
            for (int j = i + 1; j < edges.size(); j++) {
                List<BlockPos> sa = samples.get(i);
                List<BlockPos> sb = samples.get(j);
                if (sa.isEmpty() || sb.isEmpty()) continue;

                int closeCount = 0;
                outer:
                for (BlockPos a : sa) {
                    for (BlockPos b : sb) {
                        double dx = a.getX() - b.getX();
                        double dz = a.getZ() - b.getZ();
                        if (dx * dx + dz * dz < 120.0 * 120.0 && ++closeCount > 8) break outer;
                    }
                }

                if (closeCount > 8) {
                    BlockPos midA = sa.get(sa.size() / 2);
                    BlockPos midB = sb.get(sb.size() / 2);
                    emissions.addAll(buildLine(midA, midB, ParticleTypes.WITCH));
                    parallelCount++;
                }
            }
        }

        if (!emissions.isEmpty()) {
            RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);
        }

        int pc = parallelCount;
        if (pc == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "No parallel pairs detected."), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Found " + pc + " parallel pairs within " + RADIUS_PARALLEL + " blocks."), false);
        }
        return pc;
    }

    // =========================================================================
    // show_junctions
    // =========================================================================

    private static int showJunctions(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        // Build edge-count-per-node across all edges in the graph
        Map<UUID, Integer> edgeCountByNode = new HashMap<>();
        for (RoadEdge edge : graph.allEdges()) {
            edgeCountByNode.merge(edge.getNodeAId(), 1, Integer::sum);
            edgeCountByNode.merge(edge.getNodeBId(), 1, Integer::sum);
        }

        List<ParticleEmission> emissions = new ArrayList<>();
        int totalNodes = 0, junctions = 0, docks = 0, termini = 0;

        for (RoadNode node : graph.allNodes()) {
            if (node.position().distSqr(player.blockPosition()) > (double) RADIUS_STANDARD * RADIUS_STANDARD) continue;

            int ec     = edgeCountByNode.getOrDefault(node.nodeId(), 0);
            // isolated/1-edge = 5 blocks tall; +5 per additional edge (2→10, 3→15, 4+→20)
            int height = 5 + 5 * Math.min(3, Math.max(0, ec - 1));

            emissions.addAll(buildNodeBeam(node, height, particleForNodeType(node.type())));
            totalNodes++;

            if (ec >= 3) junctions++;
            if (node.type() == RoadNode.NodeType.VILLAGE_DOCK)  docks++;
            if (node.type() == RoadNode.NodeType.TERMINUS)       termini++;
        }

        if (!emissions.isEmpty()) {
            RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);
        }

        int tn = totalNodes, jn = junctions, dn = docks, tm = termini;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Showing " + tn + " nodes within " + RADIUS_STANDARD + " blocks. "
                        + jn + " junctions (3+ edges), " + dn + " docks, " + tm + " termini."), false);
        return totalNodes;
    }

    // =========================================================================
    // show_staleness
    // =========================================================================

    private static int showStaleness(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<ParticleEmission> emissions = new ArrayList<>();
        int staleCount = 0, edgesWithStale = 0;

        for (RoadEdge edge : graph.edgesNear(player.getBlockX(), player.getBlockZ(), RADIUS_STANDARD)) {
            if (edge.getStaleCells().isEmpty()) continue;
            edgesWithStale++;
            for (long cellKey : edge.getStaleCells()) {
                int cx = AtlasCell.unpackX(cellKey);
                int cz = AtlasCell.unpackZ(cellKey);
                int bx = (cx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
                int bz = (cz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
                int y  = safeGroundHeight(level, bx, bz);
                emissions.add(new ParticleEmission(
                        new BlockPos(bx, y + 1, bz), ParticleTypes.ANGRY_VILLAGER,
                        RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
                staleCount++;
            }
        }

        if (!emissions.isEmpty()) {
            RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);
        }

        int sc = staleCount, ec = edgesWithStale;
        if (sc == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal("No stale cells."), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    sc + " stale cells across " + ec + " edges."), false);
        }
        return sc;
    }

    // =========================================================================
    // show_traffic
    // =========================================================================

    private static int showTraffic(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<RoadEdge> edges = graph.edgesNear(player.getBlockX(), player.getBlockZ(), RADIUS_STANDARD);
        List<ParticleEmission> emissions = new ArrayList<>();
        for (RoadEdge edge : edges) {
            emissions.addAll(buildEdgeEmissions(edge, trafficParticle(edge.getTrafficCounter()),
                    level, RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
        }

        if (!emissions.isEmpty()) {
            RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);
        }

        int ec = edges.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Showing traffic heatmap for " + ec + " edges."), false);
        return ec;
    }

    // =========================================================================
    // show_maintenance
    // =========================================================================

    private static int showMaintenance(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<RoadEdge> edges = graph.edgesNear(player.getBlockX(), player.getBlockZ(), RADIUS_STANDARD);
        List<ParticleEmission> emissions = new ArrayList<>();
        int green = 0, orange = 0, red = 0;

        for (RoadEdge edge : edges) {
            int m = edge.getMaintenance();
            ParticleOptions p;
            if      (m >= 80) { p = ParticleTypes.COMPOSTER;       green++;  }
            else if (m >= 40) { p = ParticleTypes.FLAME;            orange++; }
            else              { p = ParticleTypes.ANGRY_VILLAGER;   red++;    }
            emissions.addAll(buildEdgeEmissions(edge, p, level,
                    RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
        }

        if (!emissions.isEmpty()) {
            RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);
        }

        int g = green, o = orange, r = red;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Maintenance: " + g + " green, " + o + " orange, " + r + " red edges."), false);
        return edges.size();
    }

    // =========================================================================
    // show_overgrowth
    // =========================================================================

    private static int showOvergrowth(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<RoadEdge> edges = graph.edgesNear(player.getBlockX(), player.getBlockZ(), RADIUS_STANDARD);
        List<ParticleEmission> emissions = new ArrayList<>();

        for (RoadEdge edge : edges) {
            int m = edge.getMaintenance();
            // High maintenance = very sparse particles (barely any overgrowth).
            // Low maintenance = dense particles clumped alongside the edge.
            int emitRate = m >= 80 ? 20 : m >= 40 ? 8 : 2;
            emissions.addAll(buildEdgeEmissions(edge, ParticleTypes.COMPOSTER, level, emitRate));
        }

        if (!emissions.isEmpty()) {
            RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);
        }

        int ec = edges.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Showing overgrowth preview for " + ec + " edges."), false);
        return ec;
    }

    // =========================================================================
    // seed_great_road
    // =========================================================================

    private static int seedGreatRoad(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        int x1 = IntegerArgumentType.getInteger(ctx, "x1");
        int z1 = IntegerArgumentType.getInteger(ctx, "z1");
        int x2 = IntegerArgumentType.getInteger(ctx, "x2");
        int z2 = IntegerArgumentType.getInteger(ctx, "z2");

        int y1 = level.isLoaded(new BlockPos(x1, 0, z1))
                ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x1, z1) : 64;
        int y2 = level.isLoaded(new BlockPos(x2, 0, z2))
                ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x2, z2) : 64;

        BlockPos from = new BlockPos(x1, y1, z1);
        BlockPos to   = new BlockPos(x2, y2, z2);

        WorldAtlas atlas = WorldAtlas.get(level);
        prefillAtlasCorridor(level, atlas, from, to);

        List<Long> cellPath = AtlasRouteRouter.findRoute(atlas, from, to);
        if (cellPath.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "No cell path found between (" + x1 + "," + z1 + ") and (" + x2 + "," + z2 + ")."));
            return 0;
        }

        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldRoadGraph     graph    = roadData.getGraph();

        RoadNode nodeA = new RoadNode(UUID.randomUUID(), from,
                RoadNode.NodeType.GREAT_ROAD_ANCHOR, Optional.empty());
        RoadNode nodeB = new RoadNode(UUID.randomUUID(), to,
                RoadNode.NodeType.GREAT_ROAD_ANCHOR, Optional.empty());
        graph.addNode(nodeA);
        graph.addNode(nodeB);

        RoadEdge edge = RoadEdge.create(nodeA.nodeId(), nodeB.nodeId(), cellPath,
                RoadEdge.EdgeTier.GREAT_ROAD,
                new RoadEdge.MeanderProfile(6.0f, 0.02f, from.asLong()));
        graph.addEdge(edge);

        List<String> warnings = GraphInvariantValidator.validate(graph);
        warnings.forEach(w -> System.out.println("[RoadGraph Validator] " + w));
        if (warnings.isEmpty()) {
            System.out.println("[RoadGraph Validator] Graph OK — "
                    + graph.allNodes().size() + " nodes, " + graph.allEdges().size() + " edges.");
        }
        roadData.markDirty();

        String na = nodeA.nodeId().toString().substring(0, 8);
        String nb = nodeB.nodeId().toString().substring(0, 8);
        String eid = edge.getEdgeId().toString().substring(0, 8);
        int cells = cellPath.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Seeded great road: nodes " + na + "… ↔ " + nb + "… edge=" + eid
                + "… (" + cells + " cells)"), false);
        return 1;
    }

    // =========================================================================
    // connect_village
    // =========================================================================

    private static int connectVillage(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level   = ctx.getSource().getLevel();
        VillageSavedData villageData = VillageSavedData.get(level);
        WorldRoadSavedData roadData  = WorldRoadSavedData.get(level);
        WorldRoadGraph     graph     = roadData.getGraph();
        WorldAtlas         atlas     = WorldAtlas.get(level);

        String villageName = StringArgumentType.getString(ctx, "villageName");
        String edgePrefix  = StringArgumentType.getString(ctx, "targetEdgeId").toLowerCase(Locale.ROOT);

        Village village = villageData.getAllVillages().stream()
                .filter(v -> v.getName().toLowerCase(Locale.ROOT).contains(villageName.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);
        if (village == null) {
            ctx.getSource().sendFailure(Component.literal("No village matching '" + villageName + "'."));
            return 0;
        }

        List<RoadEdge> edgeMatches = new ArrayList<>();
        for (RoadEdge e : graph.allEdges()) {
            if (e.getEdgeId().toString().startsWith(edgePrefix)) edgeMatches.add(e);
        }
        if (edgeMatches.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No edge matching prefix '" + edgePrefix + "'."));
            return 0;
        }
        if (edgeMatches.size() > 1) {
            ctx.getSource().sendFailure(Component.literal(
                    "Ambiguous edge prefix '" + edgePrefix + "' (" + edgeMatches.size() + " matches)."));
            return 0;
        }
        RoadEdge targetEdge = edgeMatches.get(0);

        BlockPos anchor = village.getAnchorPos();
        if (anchor == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Village '" + village.getName() + "' has no anchor position."));
            return 0;
        }

        List<Long> cellPath = targetEdge.getCellPath();
        if (cellPath.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Target edge has no cell path."));
            return 0;
        }

        // Find nearest cell on edge to village anchor
        int bestIdx = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < cellPath.size(); i++) {
            BlockPos center = AtlasRouteRouter.cellKeyToBlockCenter(cellPath.get(i));
            double dx = center.getX() - anchor.getX();
            double dz = center.getZ() - anchor.getZ();
            double d  = dx * dx + dz * dz;
            if (d < bestDist) { bestDist = d; bestIdx = i; }
        }

        BlockPos junctionRaw = AtlasRouteRouter.cellKeyToBlockCenter(cellPath.get(bestIdx));
        int jy = level.isLoaded(junctionRaw)
                ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, junctionRaw.getX(), junctionRaw.getZ())
                : 64;
        BlockPos junctionPos = new BlockPos(junctionRaw.getX(), jy, junctionRaw.getZ());

        // Split edge using the graph's factored split helper
        WorldRoadGraph.SplitResult splitResult =
                graph.splitEdgeAtCell(targetEdge.getEdgeId(), cellPath.get(bestIdx), junctionPos)
                        .orElse(null);
        if (splitResult == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Edge split failed (cell not found in path)."));
            return 0;
        }
        RoadNode junctionNode = graph.getNode(splitResult.junctionNodeId());

        // Compute docking geometry for village
        VillageDockingPoint dock = VillageDockingPoint.compute(village, junctionPos, level, villageData);

        // Pre-fill atlas and route the connector
        prefillAtlasCorridor(level, atlas, anchor, junctionPos);
        List<Long> connectorPath = AtlasRouteRouter.findRoute(atlas, anchor, junctionPos);
        if (connectorPath.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Could not find connector route from '" + village.getName() + "' to junction."));
            return 0;
        }

        RoadNode dockNode = new RoadNode(UUID.randomUUID(), dock.dockingAnchor(),
                RoadNode.NodeType.VILLAGE_DOCK, Optional.empty());
        graph.addNode(dockNode);

        RoadEdge connectorEdge = RoadEdge.create(dockNode.nodeId(), junctionNode.nodeId(),
                connectorPath, RoadEdge.EdgeTier.CONNECTOR,
                new RoadEdge.MeanderProfile(4.0f, 0.02f, dock.dockingAnchor().asLong()));
        graph.addEdge(connectorEdge);

        village.setDockNodeId(dockNode.nodeId());
        villageData.setDirty();

        List<String> warnings = GraphInvariantValidator.validate(graph);
        warnings.forEach(w -> System.out.println("[RoadGraph Validator] " + w));
        if (warnings.isEmpty()) {
            System.out.println("[RoadGraph Validator] Graph OK — "
                    + graph.allNodes().size() + " nodes, " + graph.allEdges().size() + " edges.");
        }
        roadData.markDirty();

        String vn  = village.getName();
        String eid = targetEdge.getEdgeId().toString().substring(0, 8);
        String jid = junctionNode.nodeId().toString().substring(0, 8);
        String did = dockNode.nodeId().toString().substring(0, 8);
        String cid = connectorEdge.getEdgeId().toString().substring(0, 8);
        int    cc  = connectorPath.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Connected '" + vn + "' to edge " + eid + "…. Junction=" + jid
                + "… dock=" + did + "… connector=" + cid + "… (" + cc + " cells)"), false);
        return 1;
    }

    // =========================================================================
    // dispatch_test_caravan_between
    // =========================================================================

    private static int dispatchTestCaravanBetween(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException();
        ServerLevel level        = ctx.getSource().getLevel();
        VillageSavedData vdata   = VillageSavedData.get(level);
        WorldRoadSavedData rdata = WorldRoadSavedData.get(level);
        WorldRoadGraph graph     = rdata.getGraph();

        String nameA = StringArgumentType.getString(ctx, "villageA");
        String nameB = StringArgumentType.getString(ctx, "villageB");

        Village villageA = vdata.getAllVillages().stream()
                .filter(v -> v.getName().toLowerCase(Locale.ROOT).contains(nameA.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);
        Village villageB = vdata.getAllVillages().stream()
                .filter(v -> v.getName().toLowerCase(Locale.ROOT).contains(nameB.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);

        if (villageA == null) {
            ctx.getSource().sendFailure(Component.literal("No village matching '" + nameA + "'."));
            return 0;
        }
        if (villageB == null) {
            ctx.getSource().sendFailure(Component.literal("No village matching '" + nameB + "'."));
            return 0;
        }
        if (villageA.getId().equals(villageB.getId())) {
            ctx.getSource().sendFailure(Component.literal("Villages must be different."));
            return 0;
        }

        UUID dockA = villageA.getDockNodeId().orElse(null);
        UUID dockB = villageB.getDockNodeId().orElse(null);
        if (dockA == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "'" + villageA.getName() + "' has no dock node — run connect_village first."));
            return 0;
        }
        if (dockB == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "'" + villageB.getName() + "' has no dock node — run connect_village first."));
            return 0;
        }

        System.out.println("[CaravanDispatch] Dijkstra from " + villageA.getName()
                + " (dock=" + dockA.toString().substring(0, 8) + ") to "
                + villageB.getName() + " (dock=" + dockB.toString().substring(0, 8) + ")");

        // Track C2: try the segmented pathfinder first so the route can
        // walk through any multi-gateway villages between A and B. Falls
        // back to the legacy single-graph path if the segmented search
        // can't find a route (e.g., neither village is multi-gateway).
        VillageRoadsSavedData villageRoadsData = VillageRoadsSavedData.get(level);
        Optional<List<RouteSegment>> segOpt = GraphTradeRouteEstablisher.findSegmentedPath(
                graph, villageRoadsData, dockA, dockB);
        Optional<List<UUID>> edgePathOpt;
        boolean usedSegments;
        if (segOpt.isPresent() && segOpt.get().stream()
                .anyMatch(s -> s instanceof RouteSegment.VillageTraversal)) {
            edgePathOpt = Optional.empty();
            usedSegments = true;
            System.out.println("[CaravanDispatch] Segmented path with "
                    + segOpt.get().size() + " segment(s) (includes village traversal)");
        } else {
            edgePathOpt = GraphTradeRouteEstablisher.findEdgePath(graph, dockA, dockB);
            usedSegments = false;
        }
        if (edgePathOpt.isEmpty() && !usedSegments) {
            ctx.getSource().sendFailure(Component.literal(
                    "No graph path found between '" + villageA.getName()
                    + "' and '" + villageB.getName() + "'. Check that their dock nodes are connected."));
            return 0;
        }
        List<UUID> edgeIds = usedSegments ? List.of() : edgePathOpt.get();

        if (!usedSegments) {
            System.out.println("[CaravanDispatch] Found " + edgeIds.size() + " edge path:");
            for (UUID eid : edgeIds) {
                RoadEdge e = graph.getEdge(eid);
                System.out.println("  edge=" + eid.toString().substring(0, 8)
                        + (e != null ? " tier=" + e.getTier() + " realized=" + e.isRealized()
                                             + " blocks=" + e.getBlockPath().size() : " (null)"));
            }
        }

        // Force-realize any unrealized edges on the path (only for the
        // graph-edge case; segments resolve via village or world edges
        // separately at tick time).
        int realizedNow = 0;
        if (!usedSegments) {
            for (UUID eid : edgeIds) {
                RoadEdge e = graph.getEdge(eid);
                if (e != null && !e.isRealized()) {
                    EdgeRealizer.realizeEdge(level, e, graph, vdata);
                    realizedNow++;
                    System.out.println("[CaravanDispatch] Realized edge " + eid.toString().substring(0, 8)
                            + " → " + e.getBlockPath().size() + " blocks");
                }
            }
        } else {
            // Realize each WorldEdge in the segment chain so the caravan
            // has block paths to walk through.
            for (RouteSegment seg : segOpt.get()) {
                if (seg instanceof RouteSegment.WorldEdge we) {
                    RoadEdge e = graph.getEdge(we.edgeId());
                    if (e != null && !e.isRealized()) {
                        EdgeRealizer.realizeEdge(level, e, graph, vdata);
                        realizedNow++;
                    }
                }
            }
        }
        if (realizedNow > 0) rdata.markDirty();

        List<BlockPos> resolvedPath = usedSegments
                ? Caravan.resolveSegmentBlocks(level, segOpt.get())
                : GraphTradeRouteEstablisher.resolveGraphBlocks(graph, edgeIds, dockA);
        if (resolvedPath.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Path resolved to 0 blocks — edges may still be unrealized after forced realization. Check logs."));
            return 0;
        }

        // Reserve a real merchant from villageA so the caravan has a working
        // principal entity. Using a synthetic UUID here would resolve to no
        // entity in the world, and CaravanMerchantGoal would never link
        // entity → expedition, so the caravan would not visibly progress.
        UUID principalId = vdata.reserveIdleMerchant(villageA.getId(), level);
        if (principalId == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "'" + villageA.getName() + "' has no idle merchant available — "
                    + "try again later or move a villager to MERCHANT profession."));
            return 0;
        }

        // Find an origin market building so the roster's origin is populated
        // (matches the daily-dispatch path).
        UUID originMarketId = findFirstBuildingOfType(villageA, BuildingType.MARKET, vdata);

        // Create a real TradeRoute (stored in villageData so TravellingGroupEngine can find it)
        TradeRoute route = usedSegments
                ? TradeRoute.createSegmented(villageA.getId(), villageB.getId(),
                        TradeRoute.RouteType.NEUTRAL, level.getGameTime(), segOpt.get())
                : TradeRoute.createGraph(villageA.getId(), villageB.getId(),
                        TradeRoute.RouteType.NEUTRAL, level.getGameTime(), edgeIds, dockA);
        vdata.addTradeRoute(route);
        vdata.setDirty();

        // Create caravan pointing to that real route, using the reserved merchant
        Caravan testCaravan = Caravan.create(
                route.getRouteId(),
                villageA.getId(),
                villageB.getId(),
                principalId,
                originMarketId,
                List.of(),
                0,
                level.getGameTime());

        // Tag the merchant entity with the new expedition so CaravanMerchantGoal
        // can find its caravan when it next ticks (matches CaravanSavedData.dispatchNewCaravans).
        var mob = level.getEntity(principalId);
        if (mob instanceof TownspersonMob m) {
            m.setCurrentExpeditionId(testCaravan.getCaravanId());
        }

        CaravanSavedData.get(level).addCaravan(testCaravan);

        int routeShape = usedSegments ? segOpt.get().size() : edgeIds.size();
        System.out.println("[CaravanDispatch] Created route " + route.getRouteId().toString().substring(0, 8)
                + " with " + routeShape + (usedSegments ? " segments" : " edges")
                + " → " + resolvedPath.size() + " blocks resolved"
                + " (principal=" + principalId.toString().substring(0, 8) + ")");

        final String va = villageA.getName();
        final String vb = villageB.getName();
        final int shapeCnt = routeShape;
        final boolean seg = usedSegments;
        final int blkCnt  = resolvedPath.size();
        int finalRealizedNow = realizedNow;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Dispatched test caravan from '" + va + "' to '" + vb + "': "
                + shapeCnt + (seg ? " segments" : " edges") + ", "
                + blkCnt + " blocks resolved."
                + (finalRealizedNow > 0 ? " (realized " + finalRealizedNow + " edges)" : "")), false);
        return 1;
    }

    /** Returns the first building of the given type in {@code village}, or null. */
    private static UUID findFirstBuildingOfType(Village village,
                                                BuildingType type,
                                                VillageSavedData vdata) {
        for (UUID id : village.getBuildingIds()) {
            Building b = vdata.getBuildingById(id).orElse(null);
            if (b != null && b.getType() == type) return id;
        }
        return null;
    }

    // =========================================================================
    // replan_connector
    // =========================================================================

    private static int replanConnector(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException(); // player-only
        ServerLevel level        = ctx.getSource().getLevel();
        VillageSavedData vdata   = VillageSavedData.get(level);
        WorldRoadSavedData rdata = WorldRoadSavedData.get(level);
        WorldRoadGraph graph     = rdata.getGraph();

        String name = StringArgumentType.getString(ctx, "villageName");
        Village village = vdata.getAllVillages().stream()
                .filter(v -> v.getName().toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);
        if (village == null) {
            ctx.getSource().sendFailure(Component.literal("No village matching '" + name + "'."));
            return 0;
        }

        // ── Clean up existing dock + connector ────────────────────────────────
        Optional<UUID> dockOpt = village.getDockNodeId();
        if (dockOpt.isPresent()) {
            UUID dockId = dockOpt.get();
            RoadNode dockNode = graph.getNode(dockId);
            if (dockNode != null) {
                RoadEdge connector = null;
                for (RoadEdge e : graph.allEdges()) {
                    if ((e.getNodeAId().equals(dockId) || e.getNodeBId().equals(dockId))
                            && e.getTier() == RoadEdge.EdgeTier.CONNECTOR) {
                        connector = e;
                        break;
                    }
                }
                UUID junctionSide = null;
                if (connector != null) {
                    junctionSide = connector.getNodeAId().equals(dockId)
                            ? connector.getNodeBId() : connector.getNodeAId();
                    graph.removeEdge(connector.getEdgeId());
                }
                graph.removeNode(dockId);
                if (junctionSide != null) mergeIfStranded(graph, junctionSide);
            }
            village.setDockNodeId(null);
            vdata.setDirty();
        }

        // ── Re-plan ───────────────────────────────────────────────────────────
        ConnectorPlanner.ConnectorPlanResult result = ConnectorPlanner.planConnector(
                level, graph, vdata, village, ConnectorPlanner.DEFAULT_SEARCH_RADIUS);
        System.out.println("[ConnectorPlanner] replan: " + result.planSummary());
        rdata.markDirty();

        final String summary = result.planSummary();
        ctx.getSource().sendSuccess(() -> Component.literal(summary), false);
        return 1;
    }

    /**
     * If the given junction node now has exactly two incident edges of the same tier,
     * merges them into one edge and removes the junction. This keeps the graph
     * clean after a connector is removed.
     */
    private static void mergeIfStranded(WorldRoadGraph graph, UUID nodeId) {
        RoadNode node = graph.getNode(nodeId);
        if (node == null || node.type() != RoadNode.NodeType.TRUNK_JUNCTION) return;

        List<RoadEdge> incident = new ArrayList<>();
        for (RoadEdge e : graph.allEdges()) {
            if (e.getNodeAId().equals(nodeId) || e.getNodeBId().equals(nodeId)) {
                incident.add(e);
            }
        }
        if (incident.size() != 2) return;
        RoadEdge eA = incident.get(0);
        RoadEdge eB = incident.get(1);
        if (eA.getTier() != eB.getTier()) return;

        // Orient: aOther → junction → bOther
        UUID aOther = eA.getNodeAId().equals(nodeId) ? eA.getNodeBId() : eA.getNodeAId();
        UUID bOther = eB.getNodeAId().equals(nodeId) ? eB.getNodeBId() : eB.getNodeAId();

        // Build merged cell path: aOther→junction direction, then junction→bOther direction
        List<Long> pathA = new ArrayList<>(eA.getCellPath());
        if (eA.getNodeAId().equals(nodeId)) Collections.reverse(pathA); // now goes aOther→junction
        List<Long> pathB = new ArrayList<>(eB.getCellPath());
        if (eB.getNodeBId().equals(nodeId)) Collections.reverse(pathB); // now goes junction→bOther

        // Concatenate, deduplicating the shared junction cell
        List<Long> merged = new ArrayList<>(pathA);
        if (!merged.isEmpty() && !pathB.isEmpty()
                && merged.get(merged.size() - 1).equals(pathB.get(0))) {
            merged.addAll(pathB.subList(1, pathB.size()));
        } else {
            merged.addAll(pathB);
        }

        int avgMaint = (eA.getMaintenance() + eB.getMaintenance()) / 2;
        List<UUID> maintainers = new ArrayList<>(eA.getMaintainerVillageIds());
        for (UUID uid : eB.getMaintainerVillageIds()) {
            if (!maintainers.contains(uid)) maintainers.add(uid);
        }

        RoadEdge mergedEdge = RoadEdge.create(aOther, bOther,
                merged, eA.getTier(), eA.getMeanderProfile());
        mergedEdge.setMaintenance(avgMaint);
        mergedEdge.getMaintainerVillageIds().addAll(maintainers);

        graph.removeEdge(eA.getEdgeId());
        graph.removeEdge(eB.getEdgeId());
        graph.removeNode(nodeId);
        graph.addEdge(mergedEdge);
        System.out.println("[replanConnector] Merged stranded junction "
                + nodeId.toString().substring(0, 8));
    }

    // =========================================================================
    // inspect_junction
    // =========================================================================

    private static int inspectJunction(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        String input = StringArgumentType.getString(ctx, "nodeId").toLowerCase(Locale.ROOT);

        List<RoadNode> matches = new ArrayList<>();
        for (RoadNode n : graph.allNodes()) {
            if (n.nodeId().toString().startsWith(input)) matches.add(n);
        }

        if (matches.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "No node matches prefix '" + input + "'."));
            return 0;
        }
        if (matches.size() > 1) {
            ctx.getSource().sendFailure(Component.literal(
                    "Ambiguous node prefix '" + input + "' (" + matches.size() + " matches)."));
            return 0;
        }

        RoadNode node = matches.get(0);
        String nodeShortId = node.nodeId().toString().substring(0, 8);
        BlockPos nodePos = node.position();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "--- inspect_junction " + nodeShortId + " [" + node.type() + "] ---"), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "  pos=" + nodePos.toShortString()), false);

        // Collect incident edges
        List<RoadEdge> incident = new ArrayList<>();
        for (RoadEdge e : graph.allEdges()) {
            if (e.getNodeAId().equals(node.nodeId()) || e.getNodeBId().equals(node.nodeId())) {
                incident.add(e);
            }
        }

        final String incHeader = "  Incident edges: " + incident.size();
        ctx.getSource().sendSuccess(() -> Component.literal(incHeader), false);

        for (RoadEdge e : incident) {
            String eid = e.getEdgeId().toString().substring(0, 8);
            boolean isNodeA = e.getNodeAId().equals(node.nodeId());
            String role = isNodeA ? "nodeA" : "nodeB";
            List<BlockPos> bp = e.getBlockPath();

            String bpSummary;
            if (bp.isEmpty()) {
                bpSummary = "blocks=0";
            } else {
                bpSummary = "blocks=" + bp.size()
                        + " first=" + bp.get(0).toShortString()
                        + " last="  + bp.get(bp.size() - 1).toShortString();
            }

            final String edgeLine = "  [" + eid + "] tier=" + e.getTier()
                    + " endpoint=" + role
                    + " realized=" + e.isRealized()
                    + " cells=" + e.getCellPath().size()
                    + " " + bpSummary;
            ctx.getSource().sendSuccess(() -> Component.literal(edgeLine), false);

            // Connectivity check: nearest block to this node
            if (!bp.isEmpty()) {
                double minDist = Double.MAX_VALUE;
                BlockPos nearestBlock = bp.get(0);

                // Sample densely enough to catch the true nearest
                int step = Math.max(1, bp.size() / 500);
                for (int i = 0; i < bp.size(); i += step) {
                    double d = Math.sqrt(bp.get(i).distSqr(nodePos));
                    if (d < minDist) { minDist = d; nearestBlock = bp.get(i); }
                }
                // Always check endpoints regardless of step size
                for (BlockPos check : List.of(bp.get(0), bp.get(bp.size() - 1))) {
                    double d = Math.sqrt(check.distSqr(nodePos));
                    if (d < minDist) { minDist = d; nearestBlock = check; }
                }

                final double finalDist = minDist;
                final BlockPos finalNearest = nearestBlock;
                final String distLine = "    nearestBlock=" + finalNearest.toShortString()
                        + " dist=" + String.format("%.1f", finalDist);
                ctx.getSource().sendSuccess(() -> Component.literal(distLine), false);

                if (minDist > 8.0) {
                    final String warn = "    DISCONNECTED: edge " + eid
                            + " does not reach node " + nodeShortId
                            + ". Nearest block at distance "
                            + String.format("%.1f", finalDist) + ".";
                    ctx.getSource().sendSuccess(() -> Component.literal(warn), false);
                }
            }
        }

        return incident.size();
    }

    // =========================================================================
    // caravan_status
    // =========================================================================

    private static int caravanStatus(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException(); // player-only
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData villageData = VillageSavedData.get(level);
        List<Caravan> caravans = CaravanSavedData.get(level).getAllCaravans();

        if (caravans.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No active caravans."), false);
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "--- " + caravans.size() + " active caravan(s) ---"), false);

        for (Caravan c : caravans) {
            String shortId = c.getCaravanId().toString().substring(0, 8);
            String origin  = villageData.getVillageById(c.getOriginVillageId())
                    .map(Village::getName)
                    .orElse(c.getOriginVillageId().toString().substring(0, 8) + "…");
            String dest    = villageData.getVillageById(c.getDestVillageId())
                    .map(Village::getName)
                    .orElse(c.getDestVillageId().toString().substring(0, 8) + "…");
            int    pct     = (int)(c.getProgress() * 100.0);
            TradeRoute tr = VillageSavedData.get(level).getRouteById(c.getRouteId()).orElse(null);
            String routeInfo = tr == null ? "route=missing"
                    : tr.hasGraphPath() ? "graph(" + tr.getEdgeIds().size() + " edges)"
                    : tr.getConnectionId() != null
                            ? "sea(" + tr.getConnectionId().toString().substring(0, 8) + ")"
                            : "no-path";
            final String line = "[" + shortId + "] " + origin + " → " + dest
                    + "  state=" + c.getState()
                    + " progress=" + pct + "%"
                    + " spawned=" + c.isSpawned()
                    + " " + routeInfo;
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return caravans.size();
    }

    // =========================================================================
    // Atlas pre-fill helper — delegates to the now-public TradeRouteManager method
    // =========================================================================

    private static void prefillAtlasCorridor(ServerLevel level, WorldAtlas atlas,
                                              BlockPos from, BlockPos to) {
        TradeRouteManager.prefillAtlasCorridor(level, atlas, from, to);
    }

    // =========================================================================
    // Emission builders
    // =========================================================================

    /**
     * Builds a list of particle positions sampling the edge's block path (if
     * realized, every 3rd block) or cell path (if unrealized, every cell center
     * snapped to the surface). Sampling is done once at command time, not per tick.
     */
    private static List<ParticleEmission> buildEdgeEmissions(
            RoadEdge edge, ParticleOptions particle, ServerLevel level, int emitRate) {
        List<ParticleEmission> out = new ArrayList<>();
        if (!edge.getBlockPath().isEmpty()) {
            List<BlockPos> path = edge.getBlockPath();
            for (int i = 0; i < path.size(); i += 3) {
                out.add(new ParticleEmission(path.get(i), particle, emitRate));
            }
        } else {
            for (long cellKey : edge.getCellPath()) {
                int cx = AtlasCell.unpackX(cellKey);
                int cz = AtlasCell.unpackZ(cellKey);
                int bx = (cx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
                int bz = (cz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
                int y  = safeGroundHeight(level, bx, bz);
                out.add(new ParticleEmission(new BlockPos(bx, y + 1, bz), particle, emitRate));
            }
        }
        return out;
    }

    /**
     * Builds a vertical column of particles above a node's stored position.
     * {@code height} determines how many blocks tall the beam is.
     */
    private static List<ParticleEmission> buildNodeBeam(
            RoadNode node, int height, ParticleOptions particle) {
        List<ParticleEmission> out = new ArrayList<>(height);
        BlockPos base = node.position();
        for (int dy = 0; dy < height; dy++) {
            out.add(new ParticleEmission(
                    base.above(dy), particle, RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
        }
        return out;
    }

    /**
     * Builds a straight line of particles between two points, spaced ~5 blocks apart.
     * Used to connect midpoints of detected parallel edge pairs.
     */
    private static List<ParticleEmission> buildLine(
            BlockPos a, BlockPos b, ParticleOptions particle) {
        List<ParticleEmission> out = new ArrayList<>();
        double dist  = Math.sqrt(a.distSqr(b));
        int    steps = Math.min(200, Math.max(1, (int)(dist / 5.0)));
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            int x = (int)(a.getX() + t * (b.getX() - a.getX()));
            int y = (int)(a.getY() + t * (b.getY() - a.getY()));
            int z = (int)(a.getZ() + t * (b.getZ() - a.getZ()));
            out.add(new ParticleEmission(
                    new BlockPos(x, y, z), particle, RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
        }
        return out;
    }

    // =========================================================================
    // Edge sampling for parallel-pair detection
    // =========================================================================

    /**
     * Samples representative points along an edge for parallel-pair detection.
     * Realized edges: every 50th block in the block path.
     * Unrealized edges: every cell-path center (~64-block spacing).
     */
    private static List<BlockPos> sampleEdgeForParallel(RoadEdge edge, ServerLevel level) {
        List<BlockPos> samples = new ArrayList<>();
        if (!edge.getBlockPath().isEmpty()) {
            List<BlockPos> path = edge.getBlockPath();
            for (int i = 0; i < path.size(); i += 50) samples.add(path.get(i));
        } else {
            for (long cellKey : edge.getCellPath()) {
                int cx = AtlasCell.unpackX(cellKey);
                int cz = AtlasCell.unpackZ(cellKey);
                int bx = (cx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
                int bz = (cz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
                samples.add(new BlockPos(bx, safeGroundHeight(level, bx, bz), bz));
            }
        }
        return samples;
    }

    // =========================================================================
    // Particle type selectors
    // =========================================================================

    private static ParticleOptions particleForTier(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case GREAT_ROAD -> ParticleTypes.END_ROD;
            case TRUNK      -> ParticleTypes.FLAME;
            case CONNECTOR  -> ParticleTypes.COMPOSTER;
            case LOCAL      -> ParticleTypes.SMOKE;
        };
    }

    private static ParticleOptions particleForNodeType(RoadNode.NodeType type) {
        return switch (type) {
            case GREAT_ROAD_ANCHOR, TRUNK_JUNCTION -> ParticleTypes.END_ROD;
            case VILLAGE_DOCK                      -> ParticleTypes.HAPPY_VILLAGER;
            case TOLL_GATE                         -> ParticleTypes.SOUL_FIRE_FLAME;
            case TERMINUS, POI_STUB, WAYSTATION    -> ParticleTypes.SMOKE;
        };
    }

    private static ParticleOptions trafficParticle(long traffic) {
        if (traffic == 0)  return ParticleTypes.SMOKE;
        if (traffic <= 10) return ParticleTypes.COMPOSTER;
        if (traffic <= 50) return ParticleTypes.FLAME;
        return ParticleTypes.LAVA;
    }

    // =========================================================================
    // cleanup_scan
    // =========================================================================

    private static final int DEFAULT_CLEANUP_RADIUS = 1024;

    private static int cleanupScan(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return runCleanupScan(ctx, DEFAULT_CLEANUP_RADIUS);
    }

    private static int cleanupScanWithRadius(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return runCleanupScan(ctx, IntegerArgumentType.getInteger(ctx, "radius"));
    }

    private static int runCleanupScan(CommandContext<CommandSourceStack> ctx, int radius)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<ParallelismDetector.ParallelPair> pairs =
                ParallelismDetector.findParallelPairs(graph, radius, player.blockPosition());

        if (pairs.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[cleanup_scan] No parallel pairs within " + radius + " blocks."), false);
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "[cleanup_scan] Found " + pairs.size() + " parallel pair(s) within "
                        + radius + " blocks (longest overlap first):"), false);

        List<ParticleEmission> emissions = new ArrayList<>();

        for (int i = 0; i < Math.min(pairs.size(), 10); i++) {
            ParallelismDetector.ParallelPair pair = pairs.get(i);
            RoadEdge ea = graph.getEdge(pair.edgeAId());
            RoadEdge eb = graph.getEdge(pair.edgeBId());
            final String line = "  #" + (i + 1) + ": A=" + pair.edgeAId().toString().substring(0, 8)
                    + " (" + (ea != null ? ea.getTier() : "?") + ")"
                    + " B=" + pair.edgeBId().toString().substring(0, 8)
                    + " (" + (eb != null ? eb.getTier() : "?") + ")"
                    + " overlap=" + pair.sustainedLengthBlocks() + " blocks"
                    + " [" + pair.overlapStartIndexA() + ".." + pair.overlapEndIndexA() + "]";
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);

            // Highlight both edges and draw a line between midpoints
            if (ea != null) emissions.addAll(buildEdgeEmissions(ea, ParticleTypes.WITCH, level,
                    RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL * 2));
            if (eb != null) emissions.addAll(buildEdgeEmissions(eb, ParticleTypes.COMPOSTER, level,
                    RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL * 2));
        }

        if (!emissions.isEmpty()) {
            RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);
        }
        return pairs.size();
    }

    // =========================================================================
    // cleanup_merge
    // =========================================================================

    private static int cleanupMerge(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = roadData.getGraph();

        String prefixA = StringArgumentType.getString(ctx, "edgeAId").toLowerCase(Locale.ROOT);
        String prefixB = StringArgumentType.getString(ctx, "edgeBId").toLowerCase(Locale.ROOT);

        RoadEdge edgeA = resolveEdgeByPrefix(ctx, graph, prefixA, "edgeAId");
        if (edgeA == null) return 0;
        RoadEdge edgeB = resolveEdgeByPrefix(ctx, graph, prefixB, "edgeBId");
        if (edgeB == null) return 0;

        if (edgeA.getEdgeId().equals(edgeB.getEdgeId())) {
            ctx.getSource().sendFailure(Component.literal("edgeAId and edgeBId must be different edges."));
            return 0;
        }

        Optional<ParallelismDetector.ParallelPair> pairOpt =
                ParallelismDetector.findPairBetween(graph, edgeA.getEdgeId(), edgeB.getEdgeId());

        if (pairOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[cleanup_merge] Edges " + edgeA.getEdgeId().toString().substring(0, 8)
                            + " and " + edgeB.getEdgeId().toString().substring(0, 8)
                            + " are not detected as parallel — no merge performed."));
            return 0;
        }

        ParallelismResolver.ResolveResult result =
                ParallelismResolver.resolvePair(level, graph, pairOpt.get());

        if (!result.success()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[cleanup_merge] Merge failed: " + result.failureReason()));
            return 0;
        }

        roadData.markDirty();

        final String survivorStr = result.survivorEdgeId().toString().substring(0, 8);
        final String removedStr  = result.removedEdgeId().toString().substring(0, 8);
        final int    stubCount   = result.leftoverEdgeIds().size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[cleanup_merge] Merged: removed=" + removedStr
                        + " survivor=" + survivorStr
                        + " stubs=" + stubCount), false);
        return 1;
    }

    private static RoadEdge resolveEdgeByPrefix(CommandContext<CommandSourceStack> ctx,
                                                WorldRoadGraph graph,
                                                String prefix,
                                                String argName) {
        List<RoadEdge> matches = new ArrayList<>();
        for (RoadEdge e : graph.allEdges()) {
            if (e.getEdgeId().toString().startsWith(prefix)) matches.add(e);
        }
        if (matches.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "No edge matches prefix '" + prefix + "' for " + argName + "."));
            return null;
        }
        if (matches.size() > 1) {
            String list = matches.stream().limit(5)
                    .map(e -> e.getEdgeId().toString().substring(0, 8))
                    .reduce((a, b) -> a + ", " + b).orElse("");
            ctx.getSource().sendFailure(Component.literal(
                    "Ambiguous prefix '" + prefix + "' for " + argName
                            + ": " + list + (matches.size() > 5 ? "…" : "")));
            return null;
        }
        return matches.get(0);
    }

    // =========================================================================
    // invalidate_cell
    // =========================================================================

    private static int invalidateCell(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        int blockX = IntegerArgumentType.getInteger(ctx, "x");
        int blockZ = IntegerArgumentType.getInteger(ctx, "z");

        int cellX = blockX >> AtlasCell.CELL_SHIFT;
        int cellZ = blockZ >> AtlasCell.CELL_SHIFT;
        long cellKey = AtlasCell.packKey(cellX, cellZ);

        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = roadData.getGraph();

        Set<UUID> edgeIds = graph.edgesInCell(cellKey);
        if (edgeIds.isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("[invalidate_cell] No edges cover cell ("
                            + cellX + "," + cellZ + ")"), false);
            return 0;
        }

        boolean anyDirty = RoadTerrainChangeListener.invalidateCells(
                graph, Set.of(cellKey));
        if (anyDirty) roadData.markDirty();

        int count = (int) edgeIds.stream()
                .map(graph::getEdge)
                .filter(e -> e != null && e.getStaleCells().contains(cellKey))
                .count();

        ctx.getSource().sendSuccess(
                () -> Component.literal("[invalidate_cell] Marked cell ("
                        + cellX + "," + cellZ + ") stale on " + count + " edge(s)"), false);
        return count;
    }

    // =========================================================================
    // material_preview
    // =========================================================================

    private static int materialPreview(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return runMaterialPreview(ctx, null);
    }

    private static int materialPreviewWithSeason(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        String seasonStr = StringArgumentType.getString(ctx, "season").toUpperCase(Locale.ROOT);
        SeasonTracker.Season season;
        try {
            season = SeasonTracker.Season.valueOf(seasonStr);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown season '" + seasonStr + "'. Valid: SPRING, SUMMER, AUTUMN, WINTER."));
            return 0;
        }
        return runMaterialPreview(ctx, season);
    }

    private static int runMaterialPreview(CommandContext<CommandSourceStack> ctx,
                                          @Nullable SeasonTracker.Season season)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException();

        String culture     = StringArgumentType.getString(ctx, "culture");
        String tierStr     = StringArgumentType.getString(ctx, "tier").toUpperCase(Locale.ROOT);
        int    maintenance = IntegerArgumentType.getInteger(ctx, "maintenance");

        RoadShape.RoadTier tier;
        try {
            tier = RoadShape.RoadTier.valueOf(tierStr);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown tier '" + tierStr + "'. Valid: FOOTPATH, VILLAGE_PATH, VILLAGE_ROAD, TOWN_ROAD, CAPITAL_ROAD."));
            return 0;
        }

        PathMaterial mat = PathMaterial.resolve(
                VillageBiomeStyle.PLAINS, culture, maintenance, tier, season);

        String header = "--- material_preview ---"
                + " culture=" + culture
                + " tier=" + tier
                + " maintenance=" + maintenance
                + " season=" + (season != null ? season : "none")
                + " → name=" + mat.getName();
        ctx.getSource().sendSuccess(() -> Component.literal(header), false);

        ctx.getSource().sendSuccess(() -> Component.literal("  Core blocks:"), false);
        for (PathMaterial.WeightedBlock wb : mat.getCoreBlocks()) {
            String key = wb.block().getDescriptionId();
            String line = "    " + key + " weight=" + String.format("%.2f", wb.weight());
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }

        ctx.getSource().sendSuccess(() -> Component.literal("  Edge blocks:"), false);
        for (PathMaterial.WeightedBlock wb : mat.getEdgeBlocks()) {
            String key = wb.block().getDescriptionId();
            String line = "    " + key + " weight=" + String.format("%.2f", wb.weight());
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }

        return 1;
    }

    // =========================================================================
    // force_maintenance
    // =========================================================================

    private static int forceMaintenance(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadSavedData roadData  = WorldRoadSavedData.get(level);
        WorldRoadGraph     graph     = roadData.getGraph();
        VillageSavedData   villageData = VillageSavedData.get(level);

        String edgePrefix = StringArgumentType.getString(ctx, "edgeId").toLowerCase(Locale.ROOT);
        int    value      = IntegerArgumentType.getInteger(ctx, "value");

        RoadEdge edge = resolveEdgeByPrefix(ctx, graph, edgePrefix, "edgeId");
        if (edge == null) return 0;

        int oldMaintenance = edge.getMaintenance();
        edge.setMaintenance(value);

        // Force re-realization so material changes take effect immediately
        edge.unrealize();
        edge.clearStaleness();
        EdgeRealizer.realizeEdge(level, edge, graph, villageData);
        roadData.markDirty();

        String shortId = edge.getEdgeId().toString().substring(0, 8);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[force_maintenance] Edge " + shortId + ": maintenance " + oldMaintenance
                        + " → " + value + ". Re-realized: " + edge.isRealized()
                        + " (" + edge.getBlockPath().size() + " blocks)."), false);
        return 1;
    }

    // =========================================================================
    // redecorate_edge
    // =========================================================================

    private static int redecoratEdge(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException();
        ServerLevel level      = ctx.getSource().getLevel();
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph   = rData.getGraph();
        VillageSavedData vData = VillageSavedData.get(level);

        String prefix = StringArgumentType.getString(ctx, "edgeId").toLowerCase(Locale.ROOT);
        RoadEdge edge = resolveEdgeByPrefix(ctx, graph, prefix, "edgeId");
        if (edge == null) return 0;

        // Remove previously placed decoration blocks from the world
        int removed = 0;
        for (BlockPos pos : edge.getDecorationPositions()) {
            if (level.isLoaded(pos)) {
                level.removeBlock(pos, false);
                removed++;
            }
        }
        edge.clearDecorationPositions();

        // Re-run decoration passes
        MilestoneDecorator.decorate(level, edge, graph);
        RoadOvergrowthDecorator.decorate(level, edge, graph);
        rData.markDirty();

        String shortId = edge.getEdgeId().toString().substring(0, 8);
        int newCount = edge.getDecorationPositions().size();
        int finalRemoved = removed;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[redecorate_edge] Edge " + shortId + ": removed " + finalRemoved
                        + " old blocks, placed " + newCount + " new decoration blocks."), false);
        return 1;
    }

    // =========================================================================
    // redecorate_node
    // =========================================================================

    private static int redecoratNode(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ctx.getSource().getPlayerOrException();
        ServerLevel level      = ctx.getSource().getLevel();
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph   = rData.getGraph();
        VillageSavedData vData = VillageSavedData.get(level);

        String input = StringArgumentType.getString(ctx, "nodeId").toLowerCase(Locale.ROOT);

        List<RoadNode> matches = new ArrayList<>();
        for (RoadNode n : graph.allNodes()) {
            if (n.nodeId().toString().startsWith(input)) matches.add(n);
        }
        if (matches.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No node matches prefix '" + input + "'."));
            return 0;
        }
        if (matches.size() > 1) {
            ctx.getSource().sendFailure(Component.literal(
                    "Ambiguous node prefix '" + input + "' (" + matches.size() + " matches)."));
            return 0;
        }
        RoadNode node = matches.get(0);

        // Remove previously placed decoration blocks
        int removed = 0;
        for (BlockPos pos : node.getDecorationPositions()) {
            if (level.isLoaded(pos)) {
                level.removeBlock(pos, false);
                removed++;
            }
        }
        node.clearDecorationPositions();

        // Re-decorate
        JunctionDecorator.decorate(level, node, graph, vData);
        rData.markDirty();

        String shortId = node.nodeId().toString().substring(0, 8);
        int newCount = node.getDecorationPositions().size();
        int finalRemoved = removed;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[redecorate_node] Node " + shortId + " [" + node.type() + "]: removed "
                        + finalRemoved + " old blocks, placed " + newCount + " new blocks."), false);
        return 1;
    }

    // =========================================================================
    // show_decorations
    // =========================================================================

    private static int showDecorations(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<ParticleEmission> emissions = new ArrayList<>();
        int edgeDecorCount = 0, nodeDecorCount = 0;

        for (RoadEdge edge : graph.edgesNear(player.getBlockX(), player.getBlockZ(), RADIUS_STANDARD)) {
            for (BlockPos pos : edge.getDecorationPositions()) {
                if (pos.distSqr(player.blockPosition()) > (long) RADIUS_STANDARD * RADIUS_STANDARD) continue;
                emissions.add(new ParticleEmission(pos.above(), ParticleTypes.HAPPY_VILLAGER,
                        RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
                edgeDecorCount++;
            }
        }

        for (RoadNode node : graph.allNodes()) {
            if (node.position().distSqr(player.blockPosition()) > (long) RADIUS_STANDARD * RADIUS_STANDARD) continue;
            for (BlockPos pos : node.getDecorationPositions()) {
                emissions.add(new ParticleEmission(pos.above(), ParticleTypes.END_ROD,
                        RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
                nodeDecorCount++;
            }
        }

        if (!emissions.isEmpty()) {
            RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);
        }

        int ec = edgeDecorCount, nc = nodeDecorCount;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[show_decorations] " + ec + " edge decoration blocks, "
                        + nc + " node decoration blocks within " + RADIUS_STANDARD + " blocks."), false);
        return ec + nc;
    }

    // =========================================================================
    // Ground-height helper
    // =========================================================================

    /**
     * Returns the surface Y at (x, z), or 64 if the chunk is not loaded.
     * The unloaded-chunk guard prevents accidental chunk loading during the
     * emission-list build that happens at command time.
     */
    private static int safeGroundHeight(ServerLevel level, int x, int z) {
        if (!level.isLoaded(new BlockPos(x, 0, z))) return 64;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    // =========================================================================
    // D7 — village_upkeep report
    // =========================================================================

    private static int villageUpkeepReport(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        String namePart = StringArgumentType.getString(ctx, "villageName");
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData vData = VillageSavedData.get(level);
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = rData.getGraph();

        Village village = vData.getAllVillages().stream()
                .filter(v -> v.getName().toLowerCase(java.util.Locale.ROOT)
                        .contains(namePart.toLowerCase(java.util.Locale.ROOT)))
                .findFirst().orElse(null);
        if (village == null) {
            ctx.getSource().sendFailure(Component.literal("No village matching: " + namePart));
            return 0;
        }

        VillageSizeTier sizeTier = VillageSizeTier.fromBuildingCount(village.getBuildingIds().size());
        int capacity = RoadUpkeepCalculator.maxUpkeepForTier(sizeTier);
        int totalOwed = RoadUpkeepCalculator.computeVillageUpkeep(village, graph);
        VillageUpkeepLedger ledger = rData.getLedger(village.getId());

        StringBuilder sb = new StringBuilder();
        sb.append("[village_upkeep] ").append(village.getName())
                .append(" [").append(sizeTier.displayName).append("]")
                .append("  capacity=").append(capacity).append("s/cycle")
                .append("  treasury=").append(village.getTreasury().toSilver()).append("s")
                .append("  totalOwed=").append(totalOwed).append("s/cycle");
        if (ledger != null) {
            sb.append("  paid=").append(ledger.getTotalCyclesPaidThisYear())
                    .append("  failed=").append(ledger.getTotalCyclesFailedThisYear());
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);

        // Per-edge detail
        graph.rebuildVillageMaintainerIndex();
        for (UUID eid : graph.edgesForVillage(village.getId())) {
            RoadEdge edge = graph.getEdge(eid);
            if (edge == null || edge.getTier() == RoadEdge.EdgeTier.GREAT_ROAD) continue;
            int share = RoadUpkeepCalculator.villageShareOfEdge(village.getId(), edge);
            int streak = ledger != null ? ledger.getFailureStreak(eid) : 0;
            String shortId = eid.toString().substring(0, 8);
            String edgeInfo = "  edge " + shortId + " [" + edge.getTier() + "]"
                    + " cells=" + edge.getCellPath().size()
                    + " share=" + share + "s"
                    + " maint=" + edge.getMaintenance()
                    + " failStreak=" + streak
                    + (streak >= 10 ? " *** CHRONIC ***" : "");
            ctx.getSource().sendSuccess(() -> Component.literal(edgeInfo), false);
        }
        return 1;
    }

    // =========================================================================
    // D7 — trigger_upkeep
    // =========================================================================

    private static int triggerUpkeep(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData vData = VillageSavedData.get(level);
        long before = level.getGameTime();
        RoadUpkeepSystem.runCycle(level, vData, false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[trigger_upkeep] One upkeep cycle executed at tick " + before + "."), false);
        return 1;
    }

    // =========================================================================
    // D7 — force_decay_cycle
    // =========================================================================

    private static int forceDecayCycle(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        int cycles = IntegerArgumentType.getInteger(ctx, "cycles");
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData vData = VillageSavedData.get(level);

        for (int i = 0; i < cycles; i++) {
            RoadUpkeepSystem.runCycle(level, vData, true);
        }

        int c = cycles;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[force_decay_cycle] " + c + " bankrupt cycle(s) applied to all non-great-road edges."
                + " Use /liv road debug show_maintenance to view."), false);
        return cycles;
    }

    // =========================================================================
    // D6 — donate to village treasury
    // =========================================================================

    private static int donateToVillage(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        String namePart = StringArgumentType.getString(ctx, "villageName");
        int silverAmount = IntegerArgumentType.getInteger(ctx, "amount");
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData vData = VillageSavedData.get(level);

        Village village = vData.getAllVillages().stream()
                .filter(v -> v.getName().toLowerCase(java.util.Locale.ROOT)
                        .contains(namePart.toLowerCase(java.util.Locale.ROOT)))
                .findFirst().orElse(null);
        if (village == null) {
            ctx.getSource().sendFailure(Component.literal("No village matching: " + namePart));
            return 0;
        }

        long bronzeAmount = (long) silverAmount * tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue.SILVER_VALUE;
        village.depositToTreasury(bronzeAmount);
        vData.setDirty();

        String vName = village.getName();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[donate] Added " + silverAmount + "s to " + vName
                        + " treasury (now " + village.getTreasury().toSilver() + "s)."), false);
        return silverAmount;
    }

    // =========================================================================
    // D6 — repair edge (set maintenance to 100, clear failure streaks)
    // =========================================================================

    private static int repairEdge(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        String prefix = StringArgumentType.getString(ctx, "edgeId");
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData vData = VillageSavedData.get(level);
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = rData.getGraph();

        RoadEdge edge = resolveEdgeByPrefix(ctx, graph, prefix, "");
        if (edge == null) {
            ctx.getSource().sendFailure(Component.literal("No edge matching prefix: " + prefix));
            return 0;
        }
        if (edge.getTier() == RoadEdge.EdgeTier.GREAT_ROAD) {
            ctx.getSource().sendFailure(Component.literal(
                    "Great roads cannot be repaired — they never decay (invariant 3)."));
            return 0;
        }

        int prevMaint = edge.getMaintenance();
        edge.setMaintenance(100);
        edge.setNeedsDecorationRefresh(true);

        // Clear failure streaks in all village ledgers for this edge
        UUID edgeId = edge.getEdgeId();
        int streaksCleared = 0;
        for (java.util.Map.Entry<UUID, VillageUpkeepLedger> entry : rData.getLedgers().entrySet()) {
            int streak = entry.getValue().getFailureStreak(edgeId);
            if (streak > 0) {
                entry.getValue().resetFailureStreak(edgeId);
                streaksCleared++;
            }
        }

        rData.markDirty();
        String shortId = edgeId.toString().substring(0, 8);
        int cleared = streaksCleared;
        int prev = prevMaint;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[repair] Edge " + shortId + " maintenance " + prev + " → 100."
                        + " Cleared failure streaks for " + cleared + " village(s)."
                        + " Overgrowth will refresh when in player range."), false);
        return 1;
    }

    // =========================================================================
    // D7 — village_tier report
    // =========================================================================

    private static int villageTierReport(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        String namePart = StringArgumentType.getString(ctx, "villageName");
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData vData = VillageSavedData.get(level);
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = rData.getGraph();

        Village village = vData.getAllVillages().stream()
                .filter(v -> v.getName().toLowerCase(java.util.Locale.ROOT)
                        .contains(namePart.toLowerCase(java.util.Locale.ROOT)))
                .findFirst().orElse(null);
        if (village == null) {
            ctx.getSource().sendFailure(Component.literal("No village matching: " + namePart));
            return 0;
        }

        VillageSizeTier sizeTier = VillageSizeTier.fromBuildingCount(village.getBuildingIds().size());
        RoadEdge.EdgeTier naturalTier = TierPromotionRules.naturalTierForVillage(sizeTier);
        StringBuilder sb = new StringBuilder();
        sb.append("[village_tier] ").append(village.getName())
                .append(" | buildings=").append(village.getBuildingIds().size())
                .append(" | sizeTier=").append(sizeTier)
                .append(" | naturalTier=").append(naturalTier).append("\n");

        int mismatches = 0;
        for (UUID edgeId : graph.edgesForVillage(village.getId())) {
            RoadEdge edge = graph.getEdge(edgeId);
            if (edge == null) continue;
            String shortId = edgeId.toString().substring(0, 8);

            // Compute effective tier for this edge
            List<Village> maintainers = new ArrayList<>();
            for (UUID vid : edge.getMaintainerVillageIds()) {
                vData.getVillageById(vid).ifPresent(maintainers::add);
            }
            RoadEdge.EdgeTier effective = maintainers.isEmpty()
                    ? edge.getTier()
                    : TierPromotionRules.effectiveTier(maintainers);

            boolean mismatch = effective != edge.getTier()
                    && TierPromotionRules.canBeChanged(edge);
            if (mismatch) mismatches++;

            sb.append("  edge ").append(shortId)
                    .append(" current=").append(edge.getTier())
                    .append(" effective=").append(effective)
                    .append(" maintainers=").append(edge.getMaintainerVillageIds().size());
            if (mismatch) sb.append(" *** MISMATCH ***");
            sb.append("\n");
        }

        if (mismatches > 0) {
            sb.append("[village_tier] ").append(mismatches)
                    .append(" mismatch(es) found — run reconcile_all to fix.");
        }

        String report = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    // =========================================================================
    // D7 — promote_village (force size tier for testing)
    // =========================================================================

    private static int promoteVillage(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        String namePart  = StringArgumentType.getString(ctx, "villageName");
        String tierName  = StringArgumentType.getString(ctx, "newTier").toUpperCase(java.util.Locale.ROOT);
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData vData = VillageSavedData.get(level);
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = rData.getGraph();

        Village village = vData.getAllVillages().stream()
                .filter(v -> v.getName().toLowerCase(java.util.Locale.ROOT)
                        .contains(namePart.toLowerCase(java.util.Locale.ROOT)))
                .findFirst().orElse(null);
        if (village == null) {
            ctx.getSource().sendFailure(Component.literal("No village matching: " + namePart));
            return 0;
        }

        VillageSizeTier targetSize;
        try {
            targetSize = VillageSizeTier.valueOf(tierName);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown tier '" + tierName + "'. Use: HAMLET, VILLAGE, TOWN, CITY"));
            return 0;
        }

        // Force the stored tier to the requested value and reconcile edges
        VillageSizeTier oldTier = village.getSizeTier();
        // Bypass building count by directly setting storedSizeTier via reconcile
        // We achieve this by temporarily treating the effective tier as the target
        // and applying it to all maintained edges directly
        List<RoadEdge> changed = new ArrayList<>();
        for (UUID edgeId : graph.edgesForVillage(village.getId())) {
            RoadEdge edge = graph.getEdge(edgeId);
            if (edge == null || !TierPromotionRules.canBeChanged(edge)) continue;

            // Rebuild maintainer list but substitute this village's size with targetSize
            List<Village> maintainers = new ArrayList<>();
            for (UUID vid : edge.getMaintainerVillageIds()) {
                if (vid.equals(village.getId())) continue; // substituted below
                vData.getVillageById(vid).ifPresent(maintainers::add);
            }
            // Add synthetic entry for this village at the forced tier
            RoadEdge.EdgeTier forced = TierPromotionRules.naturalTierForVillage(targetSize);
            // Effective = max of forced + real maintainers' tiers
            RoadEdge.EdgeTier effective = forced;
            for (Village m : maintainers) {
                RoadEdge.EdgeTier t = TierPromotionRules.naturalTierForVillage(
                        VillageSizeTier.fromBuildingCount(m.getBuildingIds().size()));
                if (TierPromotionRules.tierRank(t) > TierPromotionRules.tierRank(effective)) {
                    effective = t;
                }
            }

            if (effective != edge.getTier()) {
                EdgeTierManager.applyTierChange(edge, effective, level, graph);
                changed.add(edge);
            }
        }

        if (!changed.isEmpty()) rData.markDirty();

        String vName = village.getName();
        VillageSizeTier finalTarget = targetSize;
        int cnt = changed.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[promote_village] " + vName
                        + " treated as " + finalTarget
                        + " (was " + oldTier + "); "
                        + cnt + " edge(s) updated."), false);
        return cnt;
    }

    // =========================================================================
    // D7 — reconcile_all
    // =========================================================================

    private static int reconcileAll(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData vData = VillageSavedData.get(level);
        int changed = TierReconciliationSystem.runReconciliation(level, vData);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[reconcile_all] Reconciliation complete. " + changed + " edge(s) changed tier."), false);
        return changed;
    }

    // =========================================================================
    // Phase 7a — great-road generation commands
    // =========================================================================

    private static int showGreatRoads(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel  level  = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<ParticleEmission> emissions = new ArrayList<>();

        List<RoadEdge> edges = graph.edgesNear(player.getBlockX(), player.getBlockZ(), RADIUS_STANDARD);
        int grEdges = 0;
        for (RoadEdge edge : edges) {
            if (edge.getTier() != RoadEdge.EdgeTier.GREAT_ROAD) continue;
            emissions.addAll(buildEdgeEmissions(edge, ParticleTypes.END_ROD, level,
                    RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
            grEdges++;
        }

        int grNodes = 0;
        for (RoadNode node : graph.allNodes()) {
            if (node.type() != RoadNode.NodeType.GREAT_ROAD_ANCHOR) continue;
            if (node.position().distSqr(player.blockPosition()) > (double) RADIUS_STANDARD * RADIUS_STANDARD) continue;
            emissions.addAll(buildNodeBeam(node, 10, ParticleTypes.END_ROD));
            grNodes++;
        }

        RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);

        final int fe = grEdges, fn = grNodes;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[show_great_roads] " + fe + " edges and " + fn + " anchor nodes within "
                        + RADIUS_STANDARD + " blocks for 30 seconds."), false);
        return fe + fn;
    }

    private static int showAnchors(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        List<AnchorCandidate> seeded = GreatRoadGenerationQueue.getSeededAnchors();
        Map<String, AnchorCandidate> seededByPos = new HashMap<>();
        if (seeded != null) {
            for (AnchorCandidate ac : seeded) {
                BlockPos p = ac.position();
                seededByPos.put(p.getX() + ":" + p.getZ(), ac);
            }
        }

        List<RoadNode> anchors = new ArrayList<>();
        for (RoadNode node : graph.allNodes()) {
            if (node.type() == RoadNode.NodeType.GREAT_ROAD_ANCHOR) anchors.add(node);
        }

        if (anchors.isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("[show_anchors] No GREAT_ROAD_ANCHOR nodes in graph."), false);
            return 0;
        }

        anchors.sort(Comparator.comparingInt(n -> n.position().getX()));

        StringBuilder sb = new StringBuilder("[show_anchors] ")
                .append(anchors.size()).append(" anchor(s):\n");
        for (RoadNode node : anchors) {
            BlockPos p = node.position();
            AnchorCandidate ac = seededByPos.get(p.getX() + ":" + p.getZ());
            String quality = ac != null ? "q=" + ac.quality() : "q=?";
            String axis    = ac != null && ac.onDominantAxis() ? " [AXIS]" : "";
            sb.append("  ").append(p.toShortString())
              .append(" ").append(quality).append(axis)
              .append(" id=").append(node.nodeId().toString(), 0, 8)
              .append("\n");
        }
        String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return anchors.size();
    }

    private static int generationStatus(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        boolean complete  = GreatRoadGenerationQueue.isComplete(level);

        StringBuilder sb = new StringBuilder("[generation_status]\n");
        sb.append("  complete=").append(complete).append("\n");
        sb.append("  anchors: seeded=").append(GreatRoadGenerationQueue.getTotalExpectedAnchors())
          .append(" committed=").append(GreatRoadGenerationQueue.getCommittedAnchors()).append("\n");
        sb.append("  trunks:  pairs=").append(GreatRoadGenerationQueue.getTotalPairs())
          .append(" committed=").append(GreatRoadGenerationQueue.getCompletedTrunks()).append("\n");
        sb.append("  queue remaining=").append(GreatRoadGenerationQueue.getRemainingTaskCount()).append("\n");

        Map<GreatRoadGenerationQueue.StepType, Integer> snapshot = GreatRoadGenerationQueue.getQueueSnapshot();
        if (!snapshot.isEmpty()) {
            sb.append("  queue breakdown:");
            snapshot.forEach((type, count) -> sb.append(" ").append(type).append("×").append(count));
            sb.append("\n");
        }

        String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return complete ? 1 : 0;
    }

    private static int forceCompleteGeneration(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        ctx.getSource().sendSuccess(
                () -> Component.literal("[force_complete_generation] Running synchronously — may cause lag..."), false);
        int steps = GreatRoadGenerationQueue.forceComplete(level);
        boolean complete = GreatRoadGenerationQueue.isComplete(level);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[force_complete_generation] Done. steps=" + steps
                        + " complete=" + complete
                        + " trunks=" + GreatRoadGenerationQueue.getCompletedTrunks()), false);
        return steps;
    }

    private static int dominantAxis(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        long seed = level.getSeed();
        int axisIndex = GreatRoadAnchorSeeder.getDominantAxisIndex(seed);
        String axisName = GreatRoadAnchorSeeder.getDominantAxisName(axisIndex);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[dominant_axis] World seed=" + seed
                        + "  axis=" + axisName + " (index " + axisIndex + ")"), false);
        return axisIndex;
    }

    // =========================================================================
    // Phase 7b — named-road commands
    // =========================================================================

    private static int namedRoads(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        List<RoadEdge> named = graph.namedGreatRoads();

        if (named.isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("[named_roads] No named great roads in world."), false);
            return 0;
        }

        named.sort(Comparator.comparing(e -> e.getRoadName().orElse("")));

        StringBuilder sb = new StringBuilder("[named_roads] ").append(named.size()).append(" named road(s):\n");
        for (RoadEdge edge : named) {
            RoadNode nodeA = graph.getNode(edge.getNodeAId());
            RoadNode nodeB = graph.getNode(edge.getNodeBId());
            String posA = nodeA != null ? nodeA.position().toShortString() : "?";
            String posB = nodeB != null ? nodeB.position().toShortString() : "?";

            List<Long> path = edge.getCellPath();
            int rx = 0, rz = 0;
            if (!path.isEmpty()) {
                int[] mid = NamedRoadSelector.midpointBlocks(path);
                rx = Math.floorDiv(mid[0], NamedRoadSelector.REGION_SIZE);
                rz = Math.floorDiv(mid[1], NamedRoadSelector.REGION_SIZE);
            }

            final int frx = rx, frz = rz;
            sb.append("  ").append(edge.getRoadName().get())
              .append(" | cells=").append(path.size())
              .append(" | ").append(posA).append(" → ").append(posB)
              .append(" | region=(").append(frx).append(",").append(frz).append(")\n");
        }
        String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return named.size();
    }

    private static int highlightNamed(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel  level  = ctx.getSource().getLevel();
        String prefix = StringArgumentType.getString(ctx, "namePrefix");
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        Optional<RoadEdge> match = graph.findByName(prefix);
        if (match.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[highlight_named] No named road matches '" + prefix + "'."));
            return 0;
        }

        RoadEdge edge = match.get();
        List<ParticleEmission> emissions = new ArrayList<>(
                buildEdgeEmissions(edge, ParticleTypes.END_ROD, level, RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));

        RoadNode nodeA = graph.getNode(edge.getNodeAId());
        RoadNode nodeB = graph.getNode(edge.getNodeBId());
        if (nodeA != null) emissions.addAll(buildNodeBeam(nodeA, 15, ParticleTypes.END_ROD));
        if (nodeB != null) emissions.addAll(buildNodeBeam(nodeB, 15, ParticleTypes.END_ROD));

        RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);

        String name = edge.getRoadName().get();
        String posA = nodeA != null ? nodeA.position().toShortString() : "?";
        String posB = nodeB != null ? nodeB.position().toShortString() : "?";
        int cells = edge.getCellPath().size();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "[highlight_named] Highlighting '" + name + "' — cells=" + cells
                        + " | " + posA + " → " + posB), false);
        return 1;
    }

    private static int reselectNames(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = roadData.getGraph();

        // Clear existing names
        int cleared = 0;
        for (RoadEdge edge : graph.allEdges()) {
            if (edge.getTier() == RoadEdge.EdgeTier.GREAT_ROAD && edge.getRoadName().isPresent()) {
                edge.clearRoadName();
                cleared++;
            }
        }

        List<UUID> toName = NamedRoadSelector.selectEdgesToName(graph);
        NamedRoadSelector.nameSelectedEdges(graph, toName, level.getSeed());

        if (!toName.isEmpty() || cleared > 0) roadData.markDirty();

        final int named = toName.size();
        int finalCleared = cleared;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[reselect_names] Cleared " + finalCleared + " old name(s). Named "
                        + named + " great road(s)."), false);
        return named;
    }

    // =========================================================================
    // character  (Phase 7c)
    // =========================================================================

    private static int roadCharacter(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        String idPrefix = StringArgumentType.getString(ctx, "edgeId");
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        RoadEdge edge = resolveEdgeByPrefix(ctx, graph, idPrefix, "");
        if (edge == null) return 0;

        if (edge.getTier() != RoadEdge.EdgeTier.GREAT_ROAD) {
            ctx.getSource().sendFailure(Component.literal(
                    "[character] Edge is not a GREAT_ROAD (tier=" + edge.getTier() + ")."));
            return 0;
        }

        Optional<GreatRoadCharacter> charOpt = edge.getCharacter();
        if (charOpt.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[character] Edge " + edge.getEdgeId().toString().substring(0, 8)
                            + " has no character assigned (pre-Phase-7c edge).\n"
                            + "  meander=" + edge.getMeanderProfile().amplitude()
                            + "/" + edge.getMeanderProfile().frequency()
                            + " seed=0x" + Long.toHexString(edge.getMeanderProfile().seed())),
                    false);
            return 0;
        }

        GreatRoadCharacter ch = charOpt.get();
        StringBuilder sb = new StringBuilder("[character] Edge ")
                .append(edge.getEdgeId().toString().substring(0, 8)).append('\n')
                .append("  tag=").append(ch.tag()).append('\n')
                .append("  meander amplitude=").append(ch.meanderAmplitude())
                .append("  frequency=").append(ch.meanderFrequency()).append('\n')
                .append("  characterSeed=0x").append(Long.toHexString(ch.characterSeed())).append('\n')
                .append("  biasHints: steep=").append(ch.hints().steepCostBias())
                .append(" river=").append(ch.hints().riverCostBias())
                .append(" forest=").append(ch.hints().forestCostBias());
        String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    // =========================================================================
    // characters  (Phase 7c)
    // =========================================================================

    private static int roadCharacters(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        Map<CharacterTag, Integer> counts = new EnumMap<>(CharacterTag.class);
        int noCharacter = 0;
        for (RoadEdge edge : graph.allEdges()) {
            if (edge.getTier() != RoadEdge.EdgeTier.GREAT_ROAD) continue;
            Optional<CharacterTag> tag = edge.getCharacterTag();
            if (tag.isEmpty()) { noCharacter++; continue; }
            counts.merge(tag.get(), 1, Integer::sum);
        }

        if (counts.isEmpty() && noCharacter == 0) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("[characters] No GREAT_ROAD edges in graph."), false);
            return 0;
        }

        StringBuilder sb = new StringBuilder("[characters] Great road character distribution:\n");
        for (CharacterTag tag : CharacterTag.values()) {
            int n = counts.getOrDefault(tag, 0);
            if (n > 0) {
                sb.append("  ").append(tag.name()).append(": ").append(n).append('\n');
            }
        }
        if (noCharacter > 0) {
            sb.append("  (no character — pre-7c edges): ").append(noCharacter).append('\n');
        }
        String msg = sb.toString().stripTrailing();
        int total = counts.values().stream().mapToInt(Integer::intValue).sum() + noCharacter;
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return total;
    }

    // =========================================================================
    // highlight_character  (Phase 7c)
    // =========================================================================

    private static int highlightCharacter(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel  level  = ctx.getSource().getLevel();
        String tagStr = StringArgumentType.getString(ctx, "tag").toUpperCase(java.util.Locale.ROOT);
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        CharacterTag tag;
        try {
            tag = CharacterTag.valueOf(tagStr);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "[highlight_character] Unknown tag '" + tagStr + "'. Valid: "
                            + java.util.Arrays.toString(CharacterTag.values())));
            return 0;
        }

        List<RoadEdge> matching = graph.greatRoadsByCharacter(tag);
        if (matching.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[highlight_character] No GREAT_ROAD edges with tag " + tag + "."), false);
            return 0;
        }

        ParticleOptions particle = particleForCharacterTag(tag);
        List<ParticleEmission> emissions = new ArrayList<>();
        for (RoadEdge edge : matching) {
            emissions.addAll(buildEdgeEmissions(edge, particle, level,
                    RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
            RoadNode nodeA = graph.getNode(edge.getNodeAId());
            RoadNode nodeB = graph.getNode(edge.getNodeBId());
            if (nodeA != null) emissions.addAll(buildNodeBeam(nodeA, 12, particle));
            if (nodeB != null) emissions.addAll(buildNodeBeam(nodeB, 12, particle));
        }
        RoadDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);

        final int count = matching.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[highlight_character] Highlighting " + count + " edge(s) with tag " + tag + "."),
                false);
        return count;
    }

    private static ParticleOptions particleForCharacterTag(CharacterTag tag) {
        return switch (tag) {
            case MOUNTAIN_HUGGING  -> ParticleTypes.CLOUD;
            case PLAINS_STRAIGHT   -> ParticleTypes.COMPOSTER;
            case RIVER_FOLLOWING   -> ParticleTypes.WITCH;
            case FOREST_WANDERING  -> ParticleTypes.HAPPY_VILLAGER;
            case COASTAL           -> ParticleTypes.SOUL_FIRE_FLAME;
            case UPLAND_PASS       -> ParticleTypes.FLAME;
            default                -> ParticleTypes.SMOKE;
        };
    }

    // =========================================================================
    // underfoot  (Phase 8a)
    // =========================================================================

    private static int underfoot(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel  level  = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        RoadEdge edge = RoadUnderfootDetector.detectEdge(player, graph);
        if (edge == null) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("[underfoot] Not on any road."), false);
            return 0;
        }

        double bonus = RoadSpeedModifier.speedBonus(edge.getTier(), edge.getMaintenance());
        String shortId = edge.getEdgeId().toString().substring(0, 8);
        String msg = "[underfoot] On edge " + shortId
                + " tier=" + edge.getTier()
                + " maint=" + edge.getMaintenance()
                + " → speed bonus +" + String.format("%.2f", bonus)
                + " (" + String.format("%.0f", bonus * 100) + "%)";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    // =========================================================================
    // simulate_walk  (Phase 8a)
    // =========================================================================

    private static int simulateWalk(CommandContext<CommandSourceStack> ctx) {
        ServerLevel  level  = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        String input = StringArgumentType.getString(ctx, "edgeId").toLowerCase(java.util.Locale.ROOT);
        List<RoadEdge> matches = new ArrayList<>();
        for (RoadEdge e : graph.allEdges()) {
            if (e.getEdgeId().toString().startsWith(input)) matches.add(e);
        }

        if (matches.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[simulate_walk] No edge matches prefix '" + input + "'."));
            return 0;
        }
        if (matches.size() > 1) {
            ctx.getSource().sendFailure(Component.literal(
                    "[simulate_walk] Ambiguous edge prefix — " + matches.size() + " matches."));
            return 0;
        }

        RoadEdge edge = matches.get(0);
        String shortId = edge.getEdgeId().toString().substring(0, 8);

        StringBuilder sb = new StringBuilder("[simulate_walk] Edge ").append(shortId)
                .append(" tier=").append(edge.getTier()).append('\n');
        int[] checkpoints = {100, 80, 60, 40, 20, 10, 0};
        for (int maint : checkpoints) {
            double bonus = RoadSpeedModifier.speedBonus(edge.getTier(), maint);
            double mult  = RoadSpeedModifier.speedMultiplier(edge.getTier(), maint);
            sb.append("  maint=").append(String.format("%3d", maint))
              .append(" → +").append(String.format("%.2f", bonus))
              .append(" (").append(String.format("%.2f", mult)).append("x)\n");
        }
        String msg = sb.toString().stripTrailing();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    // =========================================================================
    // speed_report  (Phase 8a)
    // =========================================================================

    private static int speedReport(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel  level  = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        RoadEdge edge  = RoadUnderfootDetector.detectEdge(player, graph);
        double bonus   = edge != null
                ? RoadSpeedModifier.speedBonus(edge.getTier(), edge.getMaintenance())
                : 0.0;

        var attr = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        double base  = attr != null ? attr.getBaseValue()  : 0.0;
        double final_ = attr != null ? attr.getValue()      : 0.0;

        StringBuilder sb = new StringBuilder("[speed_report] ")
                .append(player.getName().getString()).append('\n')
                .append("  road: ");
        if (edge != null) {
            sb.append("on ").append(edge.getTier())
              .append(" (maint=").append(edge.getMaintenance()).append(')');
        } else {
            sb.append("none");
        }
        sb.append('\n')
          .append("  road speed bonus: +").append(String.format("%.3f", bonus))
          .append(" (").append(String.format("%.0f", bonus * 100)).append("%)\n")
          .append("  attribute base=").append(String.format("%.4f", base))
          .append("  effective=").append(String.format("%.4f", final_));

        String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    // =========================================================================
    // safety_check  (Phase 8b)
    // =========================================================================

    private static int safetyCheck(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);

        boolean isDay       = !level.isDarkOutside();
        ChunkPos chunk      = new ChunkPos(pos);
        boolean chunkInCache = RoadProximityCache.isBuilt()
                && RoadProximityCache.couldChunkBeNearRoad(chunk);

        java.util.Optional<RoadEdge.EdgeTier> nearbyTier =
                RoadProximityChecker.nearestMaintainedRoadTier(graph, pos, 16);

        StringBuilder sb = new StringBuilder("[safety_check] Pos (")
                .append(x).append(", ").append(y).append(", ").append(z).append(")\n")
                .append("  isDay=").append(isDay).append('\n')
                .append("  chunk=(").append(chunk.x).append(", ").append(chunk.z)
                .append(") in cache=").append(chunkInCache)
                .append(" (cache built=").append(RoadProximityCache.isBuilt())
                .append(", size=").append(RoadProximityCache.size()).append(")\n");

        if (nearbyTier.isEmpty()) {
            sb.append("  nearest maintained road: none within corridor\n")
              .append("  suppression: N/A");
        } else {
            RoadEdge.EdgeTier tier = nearbyTier.get();
            float chance = RoadSafetySystem.suppressionChanceForTier(tier);
            sb.append("  nearest maintained road tier: ").append(tier)
              .append(" (corridor=±").append(RoadProximityChecker.corridorWidth(tier)).append(" blocks)\n")
              .append("  suppression chance: ")
              .append(String.format("%.0f%%", chance * 100))
              .append(isDay ? " (active — daytime)" : " (inactive — night)");
        }

        String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return nearbyTier.isPresent() ? 1 : 0;
    }

    // =========================================================================
    // simulate_spawn  (Phase 8b)
    // =========================================================================

    private static int simulateSpawn(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel  level  = ctx.getSource().getLevel();
        String mobType = StringArgumentType.getString(ctx, "mob_type");
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();

        net.minecraft.core.BlockPos pos = player.blockPosition();
        boolean isDay = !level.isDarkOutside();

        java.util.Optional<RoadEdge.EdgeTier> nearbyTier =
                RoadProximityChecker.nearestMaintainedRoadTier(graph, pos, 16);

        StringBuilder sb = new StringBuilder("[simulate_spawn] Simulating '")
                .append(mobType).append("' at ").append(pos.toShortString()).append('\n')
                .append("  isDay=").append(isDay).append('\n');

        if (!isDay) {
            sb.append("  Night: no suppression — spawn ALLOWED");
        } else if (nearbyTier.isEmpty()) {
            sb.append("  No maintained road within corridor — spawn ALLOWED");
        } else {
            RoadEdge.EdgeTier tier = nearbyTier.get();
            float chance = RoadSafetySystem.suppressionChanceForTier(tier);
            boolean suppressed = level.getRandom().nextFloat() < chance;
            sb.append("  Road tier: ").append(tier)
              .append(" suppression=").append(String.format("%.0f%%", chance * 100)).append('\n')
              .append("  Simulated outcome: ")
              .append(suppressed ? "SUPPRESSED (would not spawn)" : "ALLOWED (slipped through)");
        }

        String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    // =========================================================================
    // safety_stats  (Phase 8b)
    // =========================================================================

    private static int safetyStats(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();

        long total      = RoadSafetySystem.getTotalEvents();
        long sup        = RoadSafetySystem.getSuppressedCount();
        long windowStart = RoadSafetySystem.getWindowStartTick();
        long elapsed    = level.getGameTime() - windowStart;
        float rate      = total > 0 ? (float) sup / total : 0f;

        String elapsedStr = elapsed < 20
                ? elapsed + " ticks"
                : String.format("%.1f sec", elapsed / 20.0);

        StringBuilder sb = new StringBuilder("[safety_stats] Road spawn-suppression stats\n")
                .append("  Window: ").append(elapsedStr)
                .append(" (since tick ").append(windowStart).append(")\n")
                .append("  Total daytime hostile spawn events: ").append(total).append('\n')
                .append("  Suppressed by road safety:          ").append(sup).append('\n')
                .append("  Suppression rate: ")
                .append(String.format("%.1f%%", rate * 100)).append('\n')
                .append("  Cache: built=").append(RoadProximityCache.isBuilt())
                .append("  chunks marked=").append(RoadProximityCache.size()).append('\n')
                .append("  (use 'safety_stats reset' argument to clear counters)");

        String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    // =========================================================================
    // shelters_on_edge  (Phase 8c)
    // =========================================================================

    private static int sheltersOnEdge(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level        = ctx.getSource().getLevel();
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph     = rData.getGraph();

        String prefix = StringArgumentType.getString(ctx, "edgeId").toLowerCase(java.util.Locale.ROOT);
        RoadEdge edge = resolveEdgeByPrefix(ctx, graph, prefix, "edgeId");
        if (edge == null) return 0;

        List<ShelterInstance> shelters = rData.getShelters(edge.getEdgeId());
        String shortId = edge.getEdgeId().toString().substring(0, 8);

        if (shelters.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[shelters_on_edge] Edge " + shortId + ": no shelters placed yet."), false);
            return 0;
        }

        StringBuilder sb = new StringBuilder("[shelters_on_edge] Edge ")
                .append(shortId).append(" — ").append(shelters.size()).append(" shelter(s):\n");
        for (ShelterInstance si : shelters) {
            sb.append("  ").append(si.type())
              .append(" at ").append(si.position().toShortString())
              .append(" id=").append(si.instanceId().toString(), 0, 8)
              .append(" blocks=").append(si.placedBlocks().size())
              .append('\n');
        }
        String out = sb.toString().stripTrailing();
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return shelters.size();
    }

    // =========================================================================
    // nearest_shelter  (Phase 8c)
    // =========================================================================

    private static int nearestShelter(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player      = ctx.getSource().getPlayerOrException();
        ServerLevel level        = ctx.getSource().getLevel();
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);

        BlockPos playerPos = player.blockPosition();

        ShelterInstance nearest  = null;
        double nearestDist       = Double.MAX_VALUE;
        UUID nearestEdgeId       = null;

        for (Map.Entry<UUID, List<ShelterInstance>> entry : rData.getAllEdgeShelters().entrySet()) {
            for (ShelterInstance si : entry.getValue()) {
                double dist = si.position().distSqr(playerPos);
                if (dist < nearestDist) {
                    nearestDist   = dist;
                    nearest       = si;
                    nearestEdgeId = entry.getKey();
                }
            }
        }

        if (nearest == null) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[nearest_shelter] No shelters placed in this world yet."), false);
            return 0;
        }

        ShelterInstance si  = nearest;
        UUID eid            = nearestEdgeId;
        double distBlocks   = Math.sqrt(nearestDist);

        String msg = "[nearest_shelter] " + si.type()
                + " at " + si.position().toShortString()
                + " (edge=" + eid.toString().substring(0, 8) + ")"
                + " dist=" + String.format("%.1f", distBlocks) + " blocks"
                + " id=" + si.instanceId().toString().substring(0, 8);
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    // =========================================================================
    // force_place_shelters  (Phase 8c)
    // =========================================================================

    private static int forcePlaceShelters(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level        = ctx.getSource().getLevel();
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph     = rData.getGraph();
        String culture           = "default";

        String prefix = StringArgumentType.getString(ctx, "edgeId").toLowerCase(java.util.Locale.ROOT);
        RoadEdge edge = resolveEdgeByPrefix(ctx, graph, prefix, "edgeId");
        if (edge == null) return 0;

        if (!edge.isRealized() || edge.getBlockPath().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[force_place_shelters] Edge not realized — realize it first."));
            return 0;
        }

        // Clear existing shelters
        rData.clearShelters(edge.getEdgeId());

        List<ShelterPlanner.ShelterPlan> plans = ShelterPlanner.plan(edge);
        if (plans.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[force_place_shelters] Edge " + edge.getEdgeId().toString().substring(0, 8)
                            + " too short or wrong tier for shelters (need GREAT_ROAD/TRUNK ≥ "
                            + ShelterPlanner.MIN_PATH_LENGTH + " blocks)."), false);
            return 0;
        }

        List<ShelterInstance> placed = new ArrayList<>();
        for (ShelterPlanner.ShelterPlan plan : plans) {
            ShelterInstance inst = ShelterBuilder.place(level, plan, culture, graph, level.getGameTime());
            if (inst != null) placed.add(inst);
        }

        rData.setShelters(edge.getEdgeId(), placed);
        rData.markDirty();

        String shortId = edge.getEdgeId().toString().substring(0, 8);
        int planned = plans.size(), built = placed.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[force_place_shelters] Edge " + shortId + ": planned=" + planned
                        + " placed=" + built + " (rejected by terrain: " + (planned - built) + ")."),
                false);
        return built;
    }

    // =========================================================================
    // force_place_shelter_type  (Phase 8c)
    // =========================================================================

    private static int forcePlaceShelterType(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player      = ctx.getSource().getPlayerOrException();
        ServerLevel level        = ctx.getSource().getLevel();
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph     = rData.getGraph();

        String typeName = StringArgumentType.getString(ctx, "type").toUpperCase(java.util.Locale.ROOT);
        ShelterPlanner.ShelterType shelterType;
        try {
            shelterType = ShelterPlanner.ShelterType.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "[force_place_shelter_type] Unknown type '" + typeName + "'. Valid: "
                            + java.util.Arrays.toString(ShelterPlanner.ShelterType.values())));
            return 0;
        }

        BlockPos pos    = player.blockPosition();
        // Use road direction as south (for testing purposes)
        ShelterPlanner.ShelterPlan plan = new ShelterPlanner.ShelterPlan(
                0, pos, shelterType, 0, 1);

        ShelterInstance inst = ShelterBuilder.place(level, plan, "default", graph, level.getGameTime());
        if (inst == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[force_place_shelter_type] Placement rejected — terrain too uneven or chunk unloaded at "
                            + pos.toShortString()));
            return 0;
        }

        // Associate with nearest edge if possible
        UUID edgeId = null;
        double best = Double.MAX_VALUE;
        for (RoadEdge e : graph.edgesNear(pos.getX(), pos.getZ(), 256)) {
            for (BlockPos bp : e.getBlockPath()) {
                double d = bp.distSqr(pos);
                if (d < best) { best = d; edgeId = e.getEdgeId(); }
            }
        }

        if (edgeId != null) {
            rData.getOrCreateShelters(edgeId).add(inst);
            rData.markDirty();
        }

        ShelterInstance finalInst = inst;
        String edgeStr = edgeId != null ? edgeId.toString().substring(0, 8) : "none";
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[force_place_shelter_type] Placed " + finalInst.type()
                        + " at " + finalInst.position().toShortString()
                        + " (" + finalInst.placedBlocks().size() + " blocks)"
                        + " → edge=" + edgeStr), false);
        return 1;
    }

    // =========================================================================
    // replace_shelter  (Phase 8c)
    // =========================================================================

    private static int replaceShelter(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level        = ctx.getSource().getLevel();
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph     = rData.getGraph();

        String prefix = StringArgumentType.getString(ctx, "edgeId").toLowerCase(java.util.Locale.ROOT);
        RoadEdge edge = resolveEdgeByPrefix(ctx, graph, prefix, "edgeId");
        if (edge == null) return 0;

        List<ShelterInstance> existing = new ArrayList<>(rData.getShelters(edge.getEdgeId()));
        if (existing.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[replace_shelter] Edge " + edge.getEdgeId().toString().substring(0, 8)
                            + " has no shelters. Use force_place_shelters to add them."));
            return 0;
        }

        // Remove all existing shelter blocks
        int blocksRemoved = 0;
        for (ShelterInstance si : existing) {
            for (BlockPos bp : si.placedBlocks()) {
                if (level.isLoaded(bp)) {
                    level.removeBlock(bp, false);
                    blocksRemoved++;
                }
            }
        }

        // Clear and re-plan
        rData.clearShelters(edge.getEdgeId());
        List<ShelterPlanner.ShelterPlan> plans = ShelterPlanner.plan(edge);
        List<ShelterInstance> placed = new ArrayList<>();
        for (ShelterPlanner.ShelterPlan plan : plans) {
            ShelterInstance inst = ShelterBuilder.place(level, plan, "default", graph, level.getGameTime());
            if (inst != null) placed.add(inst);
        }

        rData.setShelters(edge.getEdgeId(), placed);
        rData.markDirty();

        String shortId = edge.getEdgeId().toString().substring(0, 8);
        int removed = blocksRemoved, built = placed.size();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[replace_shelter] Edge " + shortId + ": removed " + removed
                        + " old blocks, re-placed " + built + " shelter(s)."), false);
        return built;
    }

    // =========================================================================
    // list_tolls  (Phase 8d)
    // =========================================================================

    private static int listTolls(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        VillageSavedData vData = VillageSavedData.get(level);

        var tollGates = rData.getAllTollGates();
        if (tollGates.isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("[list_tolls] No toll gates registered."), false);
            return 0;
        }

        StringBuilder sb = new StringBuilder("[list_tolls] ")
                .append(tollGates.size()).append(" gate(s):\n");
        for (WorldRoadSavedData.TollGateRecord rec : tollGates.values()) {
            String kName = vData.getKingdomById(rec.kingdomId())
                    .map(Kingdom::getName).orElse("unknown");
            sb.append("  node=").append(rec.nodeId().toString().substring(0, 8))
              .append(" kingdom=").append(kName)
              .append(" tier=").append(rec.tier())
              .append(" revenue=").append(rec.revenueBronze()).append(" bronze\n");
        }
        String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return tollGates.size();
    }

    // =========================================================================
    // simulate_toll  (Phase 8d)
    // =========================================================================

    private static int simulateToll(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getLevel();
        String kingdomName = StringArgumentType.getString(ctx, "kingdomName");
        VillageSavedData vData = VillageSavedData.get(level);

        Kingdom kingdom = vData.getAllKingdoms().stream()
                .filter(k -> k.getName().equalsIgnoreCase(kingdomName))
                .findFirst().orElse(null);
        if (kingdom == null) {
            ctx.getSource().sendFailure(
                    Component.literal("[simulate_toll] Kingdom not found: " + kingdomName));
            return 0;
        }

        // Find the nearest toll gate belonging to this kingdom
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadSavedData.TollGateRecord nearest = null;
        long bestDist = Long.MAX_VALUE;
        BlockPos pPos = player.blockPosition();

        for (WorldRoadSavedData.TollGateRecord rec : rData.getAllTollGates().values()) {
            if (!rec.kingdomId().equals(kingdom.getId())) continue;
            var node = rData.getGraph().getNode(rec.nodeId());
            if (node == null) continue;
            BlockPos gPos = node.position();
            long d = (long)(pPos.getX()-gPos.getX())*(pPos.getX()-gPos.getX())
                    + (long)(pPos.getZ()-gPos.getZ())*(pPos.getZ()-gPos.getZ());
            if (d < bestDist) { bestDist = d; nearest = rec; }
        }

        if (nearest == null) {
            ctx.getSource().sendFailure(
                    Component.literal("[simulate_toll] No toll gates found for " + kingdomName));
            return 0;
        }

        TollFeeCalculator.TollFee fee = TollFeeCalculator.computeFee(
                nearest.tier(), 0, TollFeeCalculator.DEFAULT_REPUTATION);
        WorldRoadSavedData.TollGateRecord finalNearest = nearest;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[simulate_toll] Gate " + finalNearest.nodeId().toString().substring(0, 8)
                        + " (" + kingdom.getName() + ")"
                        + " fee=" + fee.amountBronze() + " bronze"
                        + " reason=" + fee.reason()), false);
        return 1;
    }

    // =========================================================================
    // toll_revenue  (Phase 8d)
    // =========================================================================

    private static int tollRevenue(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        String kingdomName = StringArgumentType.getString(ctx, "kingdomName");
        VillageSavedData vData = VillageSavedData.get(level);
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);

        Kingdom kingdom = vData.getAllKingdoms().stream()
                .filter(k -> k.getName().equalsIgnoreCase(kingdomName))
                .findFirst().orElse(null);
        if (kingdom == null) {
            ctx.getSource().sendFailure(
                    Component.literal("[toll_revenue] Kingdom not found: " + kingdomName));
            return 0;
        }

        long total = rData.getAllTollGates().values().stream()
                .filter(r -> r.kingdomId().equals(kingdom.getId()))
                .mapToLong(WorldRoadSavedData.TollGateRecord::revenueBronze)
                .sum();
        long gateCount = rData.getAllTollGates().values().stream()
                .filter(r -> r.kingdomId().equals(kingdom.getId())).count();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "[toll_revenue] " + kingdom.getName()
                        + " — " + gateCount + " gate(s), total revenue: "
                        + total + " bronze"), false);
        return (int) Math.min(gateCount, Integer.MAX_VALUE);
    }

    // =========================================================================
    // force_place_toll  (Phase 8d)
    // =========================================================================

    private static int forcePlaceToll(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getLevel();
        String edgeIdStr = StringArgumentType.getString(ctx, "edgeId");
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        VillageSavedData vData = VillageSavedData.get(level);

        UUID edgeId;
        try { edgeId = UUID.fromString(edgeIdStr); }
        catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(
                    Component.literal("[force_place_toll] Invalid UUID: " + edgeIdStr));
            return 0;
        }

        RoadEdge edge = rData.getGraph().getEdge(edgeId);
        if (edge == null) {
            ctx.getSource().sendFailure(
                    Component.literal("[force_place_toll] Edge not found: " + edgeIdStr));
            return 0;
        }

        // Use player position as the gate position and pick the first kingdom
        Kingdom kingdom = vData.getAllKingdoms().stream().findFirst().orElse(null);
        if (kingdom == null) {
            ctx.getSource().sendFailure(
                    Component.literal("[force_place_toll] No kingdoms exist yet."));
            return 0;
        }

        long worldSeed = level.getSeed();
        List<TollGatePlanner.TollGatePlan> plans =
                TollGatePlanner.planForGraph(rData.getGraph(), vData, worldSeed);

        TollGatePlanner.TollGatePlan plan = plans.stream()
                .filter(p -> p.edgeId().equals(edgeId))
                .findFirst().orElse(null);

        if (plan == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[force_place_toll] No border crossing detected on edge "
                            + edgeIdStr.substring(0, 8) + ". Check kingdom territory."));
            return 0;
        }

        TollGateBuilder.PlacementResult result =
                TollGateBuilder.build(level, plan, kingdom, rData);
        if (result == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[force_place_toll] Placement failed (terrain unsuitable or chunk unloaded)."));
            return 0;
        }

        rData.markDirty();
        String nodeStr = result.nodeId() != null
                ? result.nodeId().toString().substring(0, 8) : "null";
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[force_place_toll] Placed gate at " + result.gatePosition().toShortString()
                        + " node=" + nodeStr
                        + " blocks=" + result.placedBlocks().size()), false);
        return 1;
    }

    // =========================================================================
    // replace_toll  (Phase 8d)
    // =========================================================================

    private static int replaceToll(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        String nodeIdStr = StringArgumentType.getString(ctx, "nodeId");
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        VillageSavedData vData = VillageSavedData.get(level);

        UUID nodeId;
        try { nodeId = UUID.fromString(nodeIdStr); }
        catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(
                    Component.literal("[replace_toll] Invalid UUID: " + nodeIdStr));
            return 0;
        }

        WorldRoadSavedData.TollGateRecord rec = rData.getTollGate(nodeId).orElse(null);
        if (rec == null) {
            ctx.getSource().sendFailure(
                    Component.literal("[replace_toll] No toll gate record for node: " + nodeIdStr));
            return 0;
        }

        var node = rData.getGraph().getNode(nodeId);
        if (node == null) {
            ctx.getSource().sendFailure(
                    Component.literal("[replace_toll] Node not in graph: " + nodeIdStr));
            return 0;
        }

        Kingdom kingdom = vData.getKingdomById(rec.kingdomId()).orElse(null);
        // Re-register with zero revenue reset
        rData.registerTollGate(nodeId, rec.kingdomId(), rec.tier());
        rData.markDirty();

        String kName = kingdom != null ? kingdom.getName() : rec.kingdomId().toString().substring(0, 8);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[replace_toll] Toll gate record for node "
                        + nodeId.toString().substring(0, 8)
                        + " reset (kingdom=" + kName + " tier=" + rec.tier() + ")."), false);
        return 1;
    }

    // =========================================================================
    // /litv toll pay  (Phase 8d — player-facing)
    // =========================================================================

    private static int playerTollPay(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        VillageSavedData vData = VillageSavedData.get(level);
        BlockPos pPos = player.blockPosition();

        // Find the nearest toll gate within 20 blocks
        WorldRoadSavedData.TollGateRecord nearest = null;
        long bestDistSq = 20L * 20;

        for (WorldRoadSavedData.TollGateRecord rec : rData.getAllTollGates().values()) {
            var node = rData.getGraph().getNode(rec.nodeId());
            if (node == null) continue;
            BlockPos gPos = node.position();
            long dsq = (long)(pPos.getX()-gPos.getX())*(pPos.getX()-gPos.getX())
                    + (long)(pPos.getZ()-gPos.getZ())*(pPos.getZ()-gPos.getZ());
            if (dsq <= bestDistSq) { bestDistSq = dsq; nearest = rec; }
        }

        if (nearest == null) {
            player.sendSystemMessage(
                    Component.literal("There is no toll gate nearby to pay."));
            return 0;
        }

        WorldRoadSavedData.TollGateRecord rec = nearest;
        Kingdom kingdom = vData.getKingdomById(rec.kingdomId()).orElse(null);

        int reputation = vData.getAllKingdoms().stream()
                .filter(k -> k.getId().equals(rec.kingdomId()))
                .findFirst()
                .map(k -> k.getVillageIds().stream()
                        .mapToInt(vid ->
                                vData.getReputation(player.getUUID(), vid)
                                        .map(tterrag1112.life_in_the_village.Village.Reputation.VillageReputation::getScore)
                                        .orElse(TollFeeCalculator.DEFAULT_REPUTATION))
                        .max()
                        .orElse(TollFeeCalculator.DEFAULT_REPUTATION))
                .orElse(TollFeeCalculator.DEFAULT_REPUTATION);

        TollFeeCalculator.TollFee fee = TollFeeCalculator.computeFee(rec.tier(), 0, reputation);
        String kName = kingdom != null ? kingdom.getName() : "Unknown Kingdom";

        if (fee.isFree()) {
            player.sendSystemMessage(
                    Component.literal("Your reputation with " + kName + " earns you free passage."));
            return 1;
        }

        // No currency system yet — log and succeed
        rData.addTollRevenue(rec.nodeId(), fee.amountBronze());
        rData.markDirty();
        System.out.println("[TollGate] Player " + player.getName().getString()
                + " paid " + fee.amountBronze() + " bronze to " + kName
                + " [no currency system — payment recorded only]");

        player.sendSystemMessage(Component.literal(
                "You pay " + fee.amountBronze() + " bronze toll to "
                        + kName + ". Safe travels."));
        return 1;
    }

    // =========================================================================
    // Phase 7d — worldgen_status
    // =========================================================================

    private static int worldgenStatus(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level   = ctx.getSource().getLevel();
        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldAtlas atlas    = WorldAtlas.get(level);

        boolean grComplete   = roadData.isGreatRoadGenerationComplete();
        int completedTrunks  = GreatRoadGenerationQueue.getCompletedTrunks();
        int totalPairs       = GreatRoadGenerationQueue.getTotalPairs();
        int remaining        = GreatRoadGenerationQueue.getRemainingTaskCount();
        long budgetMs        = (level.players().isEmpty() || !grComplete) ? 500L : 50L;
        int atlasCells       = atlas.size();

        StringBuilder sb = new StringBuilder();
        sb.append("[worldgen_status]\n");
        sb.append("  Great roads: ").append(grComplete ? "COMPLETE" : "IN_PROGRESS");
        if (!grComplete) {
            sb.append(" (").append(completedTrunks).append("/").append(totalPairs)
              .append(" trunks, ").append(remaining).append(" tasks queued)");
        }
        sb.append("\n  Kingdom seeding: ").append(WorldgenKingdomSeeder.getStatusString())
          .append("\n  Atlas fill budget: ").append(budgetMs).append("ms/tick")
          .append("\n  Atlas cells filled: ").append(atlasCells);

        String report = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    // =========================================================================
    // Phase 7d — worldgen_timing
    // =========================================================================

    private static int worldgenTiming(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        long anchorMs    = GreatRoadGenerationQueue.getAnchorSeedMs();
        long routeMs     = GreatRoadGenerationQueue.getTotalRouteMs();
        int  attempts    = GreatRoadGenerationQueue.getRoutingAttempts();
        int  prefillCells = GreatRoadGenerationQueue.getTotalPrefillCells();
        long wallMs      = GreatRoadGenerationQueue.getWallTimeMs();
        long avgMs       = attempts > 0 ? routeMs / attempts : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("[worldgen_timing] Last great-road generation run:\n");
        sb.append("  Anchor seeding: ").append(anchorMs).append("ms\n");
        sb.append("  Trunk routing: ").append(routeMs).append("ms total (")
          .append(attempts).append(" attempts");
        if (attempts > 0) sb.append(", avg ").append(avgMs).append("ms");
        sb.append(")\n");
        sb.append("  Atlas cells prefilled: ").append(prefillCells).append("\n");
        sb.append("  Total wall time: ").append(wallMs / 1000).append("s (").append(wallMs).append("ms)");

        String report = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    // =========================================================================
    // B4: detect_feature
    // =========================================================================

    private static int detectFeature(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel  level  = ctx.getSource().getLevel();

        BlockPos seed = player.blockPosition();

        VegetationFeatureDetector.FeatureType type =
                VegetationFeatureDetector.classifyFeature(level, seed);

        if (type == VegetationFeatureDetector.FeatureType.NONE) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("[detect_feature] No vegetation feature at "
                            + seed.toShortString()), false);
            return 0;
        }

        java.util.Set<BlockPos> feature = VegetationFeatureDetector.detectFeature(level, seed);
        boolean hitCap = feature.isEmpty();

        if (hitCap) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[detect_feature] Feature at " + seed.toShortString()
                    + " type=" + type + " EXCEEDED safety cap ("
                    + VegetationFeatureDetector.MAX_FEATURE_BLOCKS_PUBLIC + " blocks) — not cleared"), false);
            return 1;
        }

        int[] bb = VegetationFeatureDetector.boundingBox(feature);
        boolean hasTrunk = VegetationFeatureDetector.hasTrunk(level, feature);
        String msg = "[detect_feature] " + seed.toShortString()
                + "\n  type=" + type
                + " blocks=" + feature.size()
                + " hasTrunk=" + hasTrunk
                + "\n  bbox=[" + bb[0] + "," + bb[1] + "," + bb[2]
                + "] → [" + bb[3] + "," + bb[4] + "," + bb[5] + "]";

        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    // =========================================================================
    // B4: clear_feature
    // =========================================================================

    private static int clearFeature(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel  level  = ctx.getSource().getLevel();

        BlockPos seed = player.blockPosition();

        VegetationFeatureDetector.FeatureType type =
                VegetationFeatureDetector.classifyFeature(level, seed);

        if (type == VegetationFeatureDetector.FeatureType.NONE) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("[clear_feature] No vegetation feature at "
                            + seed.toShortString()), false);
            return 0;
        }

        java.util.Set<BlockPos> feature = VegetationFeatureDetector.detectFeature(level, seed);
        if (feature.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[clear_feature] Feature exceeds safety cap — no action taken"), false);
            return 0;
        }

        int cleared = 0;
        for (BlockPos pos : feature) {
            if (!level.isLoaded(pos)) continue;
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
            cleared++;
        }

        int finalCleared = cleared;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[clear_feature] Cleared " + finalCleared + " blocks of " + type
                + " at " + seed.toShortString()), false);
        return finalCleared;
    }

    // =========================================================================
    // B4: clear_corridor
    // =========================================================================

    private static int clearCorridor(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel  level  = ctx.getSource().getLevel();

        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        BlockPos center = player.blockPosition();

        java.util.Set<Long> corridor = TerrainClearer.buildRadiusCorridor(center, radius);
        TerrainClearer.ClearanceResult result = TerrainClearer.clear(
                level, corridor, 10, TerrainClearer.MushroomPolicy.CLEAR_IF_TRUNK);

        String msg = "[clear_corridor] radius=" + radius
                + " at " + center.toShortString()
                + "\n  trees_cleared=" + result.treesCleared()
                + " trees_partial=" + result.treesPartial()
                + " mushrooms=" + result.mushroomsCleared()
                + " vegetation=" + result.vegetationCleared()
                + " failed=" + result.failedPositions().size();

        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return result.treesCleared() + result.vegetationCleared();
    }

    // =========================================================================
    // 7e: profile — show smoothed elevation profile stats for a GREAT_ROAD edge
    // =========================================================================

    private static int showProfile(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level        = ctx.getSource().getLevel();
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph     = rData.getGraph();

        String prefix = StringArgumentType.getString(ctx, "edgeId").toLowerCase(java.util.Locale.ROOT);
        RoadEdge edge = resolveEdgeByPrefix(ctx, graph, prefix, "edgeId");
        if (edge == null) return 0;

        if (edge.getTier() != RoadEdge.EdgeTier.GREAT_ROAD) {
            ctx.getSource().sendFailure(Component.literal(
                    "[profile] Edge " + prefix + " is not a GREAT_ROAD (tier=" + edge.getTier() + ")."));
            return 0;
        }

        List<BlockPos> blockPath = edge.getBlockPath();
        if (blockPath.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[profile] Edge " + prefix + " has no block path — realize it first."));
            return 0;
        }

        GreatRoadCharacter character = edge.getCharacter().orElse(null);
        int[] profileY = GreatRoadProfile.computeProfile(blockPath, character);

        int minDelta = Integer.MAX_VALUE, maxDelta = Integer.MIN_VALUE;
        long sumDelta = 0;
        for (int i = 0; i < blockPath.size(); i++) {
            int delta = profileY[i] - blockPath.get(i).getY();
            if (delta < minDelta) minDelta = delta;
            if (delta > maxDelta) maxDelta = delta;
            sumDelta += Math.abs(delta);
        }
        int avgAbsDelta = blockPath.isEmpty() ? 0 : (int)(sumDelta / blockPath.size());
        int window = GreatRoadProfile.windowFor(character != null ? character.tag() : null);
        String tag  = character != null ? character.tag().name() : "DEFAULT";

        String msg = "[profile] Edge " + edge.getEdgeId().toString().substring(0, 8)
                + " len=" + blockPath.size()
                + " tag=" + tag + " window=±" + window
                + "\n  profile delta: min=" + minDelta + " max=" + maxDelta
                + " avgAbs=" + avgAbsDelta;
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    // =========================================================================
    // 7e: show_supports — classification breakdown for a GREAT_ROAD edge
    // =========================================================================

    private static int showSupports(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level        = ctx.getSource().getLevel();
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph     = rData.getGraph();

        String prefix = StringArgumentType.getString(ctx, "edgeId").toLowerCase(java.util.Locale.ROOT);
        RoadEdge edge = resolveEdgeByPrefix(ctx, graph, prefix, "edgeId");
        if (edge == null) return 0;

        if (edge.getTier() != RoadEdge.EdgeTier.GREAT_ROAD) {
            ctx.getSource().sendFailure(Component.literal(
                    "[show_supports] Edge " + prefix + " is not a GREAT_ROAD."));
            return 0;
        }

        List<BlockPos> blockPath = edge.getBlockPath();
        if (blockPath.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[show_supports] Edge " + prefix + " has no block path."));
            return 0;
        }

        GreatRoadCharacter character = edge.getCharacter().orElse(null);
        int[] profileY = GreatRoadProfile.computeProfile(blockPath, character);
        List<PositionClassification> classes = GreatRoadProfile.classify(blockPath, profileY, level);

        int normal = 0, raised = 0, lowered = 0, slopedL = 0, slopedR = 0;
        int raisedLong = 0;
        int[] runLens = RoadSupportBuilder.computeRaisedRunLengths(classes, classes.size());
        for (int i = 0; i < classes.size(); i++) {
            switch (classes.get(i)) {
                case NORMAL    -> normal++;
                case RAISED    -> { raised++; if (runLens[i] >= RoadSupportBuilder.VIADUCT_MIN_GAP) raisedLong++; }
                case LOWERED   -> lowered++;
                case SLOPED_LEFT  -> slopedL++;
                case SLOPED_RIGHT -> slopedR++;
            }
        }

        String msg = "[show_supports] Edge " + edge.getEdgeId().toString().substring(0, 8)
                + " len=" + blockPath.size()
                + "\n  NORMAL=" + normal
                + " RAISED=" + raised + " (viaduct≥" + RoadSupportBuilder.VIADUCT_MIN_GAP + ":" + raisedLong + ")"
                + " LOWERED=" + lowered
                + " SLOPED_LEFT=" + slopedL + " SLOPED_RIGHT=" + slopedR;
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return raised + slopedL + slopedR;
    }

    // =========================================================================
    // 7e: force_rebuild_profile — run terrain authority pipeline on an edge
    // =========================================================================

    private static int forceRebuildProfile(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level        = ctx.getSource().getLevel();
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph     = rData.getGraph();

        String prefix = StringArgumentType.getString(ctx, "edgeId").toLowerCase(java.util.Locale.ROOT);
        RoadEdge edge = resolveEdgeByPrefix(ctx, graph, prefix, "edgeId");
        if (edge == null) return 0;

        if (edge.getTier() != RoadEdge.EdgeTier.GREAT_ROAD) {
            ctx.getSource().sendFailure(Component.literal(
                    "[force_rebuild_profile] Edge " + prefix + " is not a GREAT_ROAD."));
            return 0;
        }

        List<BlockPos> blockPath = edge.getBlockPath();
        if (blockPath.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[force_rebuild_profile] Edge " + prefix + " has no block path — realize it first."));
            return 0;
        }

        GreatRoadCharacter character = edge.getCharacter().orElse(null);
        int[] profileY = GreatRoadProfile.computeProfile(blockPath, character);
        List<PositionClassification> classes = GreatRoadProfile.classify(blockPath, profileY, level);

        RoadSmoother.smooth(level, blockPath, profileY, classes);
        RoadSupportBuilder.build(level, blockPath, profileY, classes);
        RetainingWallBuilder.build(level, blockPath, profileY, classes,
                tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape.RoadTier.CAPITAL_ROAD.placedHalfWidth());

        String shortId = edge.getEdgeId().toString().substring(0, 8);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[force_rebuild_profile] Terrain authority applied to edge " + shortId
                + " (" + blockPath.size() + " positions)."), false);
        return 1;
    }

    // =========================================================================
    // 7e: supports_report — summary of RAISED/SLOPED positions near the player
    // =========================================================================

    private static int supportsReport(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player      = ctx.getSource().getPlayerOrException();
        ServerLevel level        = ctx.getSource().getLevel();
        WorldRoadSavedData rData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph     = rData.getGraph();

        BlockPos playerPos = player.blockPosition();
        int searchRadius = 256;

        int totalEdges = 0, greatRoadEdges = 0, totalRaised = 0, totalViaduct = 0, totalSloped = 0;

        for (RoadEdge edge : graph.getEdges().values()) {
            if (edge.getTier() != RoadEdge.EdgeTier.GREAT_ROAD) continue;
            List<BlockPos> bp = edge.getBlockPath();
            if (bp.isEmpty()) continue;

            // Quick bounding-box reject
            BlockPos first = bp.get(0);
            if (Math.abs(first.getX() - playerPos.getX()) > searchRadius + 500
                    || Math.abs(first.getZ() - playerPos.getZ()) > searchRadius + 500) continue;

            totalEdges++;
            greatRoadEdges++;

            GreatRoadCharacter character = edge.getCharacter().orElse(null);
            int[] profileY = GreatRoadProfile.computeProfile(bp, character);
            List<PositionClassification> classes = GreatRoadProfile.classify(bp, profileY, level);
            int[] runLens = RoadSupportBuilder.computeRaisedRunLengths(classes, classes.size());

            for (int i = 0; i < classes.size(); i++) {
                BlockPos p = bp.get(i);
                if (Math.abs(p.getX() - playerPos.getX()) > searchRadius
                        || Math.abs(p.getZ() - playerPos.getZ()) > searchRadius) continue;
                switch (classes.get(i)) {
                    case RAISED -> {
                        totalRaised++;
                        if (runLens[i] >= RoadSupportBuilder.VIADUCT_MIN_GAP) totalViaduct++;
                    }
                    case SLOPED_LEFT, SLOPED_RIGHT -> totalSloped++;
                    default -> {}
                }
            }
        }

        String msg = "[supports_report] within r=" + searchRadius + " of " + playerPos.toShortString()
                + "\n  great_road_edges=" + greatRoadEdges
                + " raised_positions=" + totalRaised
                + " (viaduct: " + totalViaduct + ")"
                + " sloped_positions=" + totalSloped;
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return greatRoadEdges;
    }

    // =========================================================================
    // 7f Slice 1: village_graph — print village internal graph summary
    // =========================================================================

    private static int villageGraph(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level        = ctx.getSource().getLevel();
        VillageSavedData vData   = VillageSavedData.get(level);
        VillageRoadsSavedData rData = VillageRoadsSavedData.get(level);

        String namePart = StringArgumentType.getString(ctx, "villageName");
        tterrag1112.life_in_the_village.Village.Village village = vData.getAllVillages()
                .stream()
                .filter(v -> v.getName().toLowerCase(Locale.ROOT)
                        .contains(namePart.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);

        if (village == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[village_graph] No village matching '" + namePart + "'."));
            return 0;
        }

        VillageRoadGraph graph = rData.getOrCreate(village.getId());

        List<VillageRoadNode> gateways = graph.gateways();
        StringBuilder sb = new StringBuilder("[village_graph] ")
                .append(village.getName())
                .append(" (").append(village.getId().toString().substring(0, 8)).append(")")
                .append("\n  nodes=").append(graph.nodeCount())
                .append(" edges=").append(graph.edgeCount())
                .append(" gateways=").append(gateways.size());

        if (graph.isEmpty()) {
            sb.append("\n  (empty graph — no internal roads generated yet)");
        } else {
            for (VillageRoadNode gw : gateways) {
                sb.append("\n  GW ").append(gw.nodeId().toString().substring(0, 8))
                  .append(" pos=").append(gw.position().toShortString());
                gw.gatewayInfo().ifPresent(info ->
                    sb.append(" dir=").append(info.outwardDirection())
                      .append(" role=").append(info.role())
                );
            }
            for (VillageRoadEdge e : graph.allEdges()) {
                sb.append("\n  Edge ").append(e.edgeId().toString().substring(0, 8))
                  .append(" [").append(e.fromNodeId().toString().substring(0, 8))
                  .append("→").append(e.toNodeId().toString().substring(0, 8))
                  .append("] char=").append(e.character())
                  .append(" len=").append(e.length()).append("b");
            }
        }

        String finalMsg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(finalMsg), false);
        return graph.nodeCount();
    }

    // =========================================================================
    // 7f Slice 1: validate_village_graphs — run invariant validation on all graphs
    // =========================================================================

    private static int validateVillageGraphs(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level        = ctx.getSource().getLevel();
        VillageRoadsSavedData rData = VillageRoadsSavedData.get(level);

        Map<UUID, List<String>> violations = rData.validateAll();

        if (violations.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[validate_village_graphs] All " + rData.graphCount()
                    + " graphs are valid — no violations."), false);
            return rData.graphCount();
        }

        StringBuilder sb = new StringBuilder("[validate_village_graphs] ")
                .append(violations.size()).append(" graph(s) with violations:");
        for (Map.Entry<UUID, List<String>> entry : violations.entrySet()) {
            sb.append("\n  ").append(entry.getKey().toString().substring(0, 8)).append(":");
            for (String v : entry.getValue()) {
                sb.append("\n    - ").append(v);
            }
        }
        String finalMsg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(finalMsg), false);
        return violations.size();
    }

    // =========================================================================
    // 7f Slice 1: show_village_graph — render as particles
    // =========================================================================

    private static int showVillageGraph(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player      = ctx.getSource().getPlayerOrException();
        ServerLevel level        = ctx.getSource().getLevel();
        VillageSavedData vData   = VillageSavedData.get(level);
        VillageRoadsSavedData rData = VillageRoadsSavedData.get(level);

        String namePart = StringArgumentType.getString(ctx, "villageName");
        tterrag1112.life_in_the_village.Village.Village village = vData.getAllVillages()
                .stream()
                .filter(v -> v.getName().toLowerCase(Locale.ROOT)
                        .contains(namePart.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);

        if (village == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[show_village_graph] No village matching '" + namePart + "'."));
            return 0;
        }

        VillageRoadGraph graph = rData.getOrCreate(village.getId());
        if (graph.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[show_village_graph] '" + village.getName()
                    + "' has no internal road graph yet."), false);
            return 0;
        }

        List<ParticleEmission> emissions = new ArrayList<>();

        // GATEWAY nodes — gold beacon beam
        for (VillageRoadNode node : graph.gateways()) {
            for (int dy = 0; dy < 10; dy++) {
                emissions.add(new ParticleEmission(
                        node.position().above(dy),
                        ParticleTypes.FLAME,
                        RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
            }
        }

        // INTERIOR/LANDMARK nodes — enchanted glow beam
        for (VillageRoadNode node : graph.allNodes()) {
            if (node.isGateway()) continue;
            for (int dy = 0; dy < 6; dy++) {
                emissions.add(new ParticleEmission(
                        node.position().above(dy),
                        ParticleTypes.ENCHANT,
                        RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
            }
        }

        // Edges — composter particles along cellPath
        for (VillageRoadEdge edge : graph.allEdges()) {
            List<BlockPos> path = edge.cellPath();
            for (int i = 0; i < path.size(); i += 2) {
                emissions.add(new ParticleEmission(
                        path.get(i).above(1),
                        ParticleTypes.COMPOSTER,
                        RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
            }
        }

        RoadDebugVisualizer.INSTANCE.addSession(
                player.getUUID(), level.getGameTime(), emissions);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "[show_village_graph] '" + village.getName()
                + "' — " + graph.nodeCount() + " nodes, " + graph.edgeCount()
                + " edges. Visualizing for "
                + (RoadDebugVisualizer.DURATION_TICKS / 20) + "s."), false);
        return graph.nodeCount() + graph.edgeCount();
    }

    // =========================================================================
    // 7f Slice 2: village_gateways — list gateways for a named village
    // =========================================================================

    private static int villageGateways(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level       = ctx.getSource().getLevel();
        VillageSavedData vData  = VillageSavedData.get(level);
        VillageRoadsSavedData rData = VillageRoadsSavedData.get(level);

        String namePart = StringArgumentType.getString(ctx, "villageName");
        tterrag1112.life_in_the_village.Village.Village village = vData.getAllVillages()
                .stream()
                .filter(v -> v.getName().toLowerCase(Locale.ROOT)
                        .contains(namePart.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);

        if (village == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[village_gateways] No village matching '" + namePart + "'."));
            return 0;
        }

        VillageRoadGraph graph = rData.getOrCreate(village.getId());
        java.util.List<VillageRoadNode> gateways = graph.gateways();

        if (gateways.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[village_gateways] '" + village.getName()
                    + "' has no gateways (Slice 2 not yet run?)."), false);
            return 0;
        }

        StringBuilder sb = new StringBuilder("[village_gateways] '")
                .append(village.getName()).append("' — ")
                .append(gateways.size()).append(" gateway(s):\n");

        for (VillageRoadNode gw : gateways) {
            VillageRoadNode.GatewayInfo info = gw.gatewayInfo().orElse(null);
            sb.append("  ").append(gw.nodeId().toString().substring(0, 8))
              .append(" pos=").append(gw.position().toShortString());
            if (info != null) {
                sb.append(" role=").append(info.role())
                  .append(" dir=").append(info.outwardDirection())
                  .append(" arm=").append(info.armEndpoint().toShortString());
                String worldId = info.worldNodeId()
                        .map(id -> id.toString().substring(0, 8))
                        .orElse("<unlinked>");
                sb.append(" worldNode=").append(worldId);
            }
            sb.append('\n');
        }

        String msg = sb.toString().trim();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return gateways.size();
    }

    // =========================================================================
    // 7f Slice 2: test_gateway_selection — simulate gateway selection for a point
    // =========================================================================

    private static int testGatewaySelection(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level       = ctx.getSource().getLevel();
        VillageSavedData vData  = VillageSavedData.get(level);
        VillageRoadsSavedData rData = VillageRoadsSavedData.get(level);

        String namePart = StringArgumentType.getString(ctx, "villageName");
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        BlockPos externalPoint = new BlockPos(x, y, z);

        tterrag1112.life_in_the_village.Village.Village village = vData.getAllVillages()
                .stream()
                .filter(v -> v.getName().toLowerCase(Locale.ROOT)
                        .contains(namePart.toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);

        if (village == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[test_gateway_selection] No village matching '" + namePart + "'."));
            return 0;
        }

        VillageRoadGraph graph = rData.getOrCreate(village.getId());
        java.util.List<VillageRoadNode> gateways = graph.gateways();

        if (gateways.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[test_gateway_selection] '" + village.getName() + "' has no gateways."), false);
            return 0;
        }

        // Compute alignment scores for all gateways
        StringBuilder sb = new StringBuilder("[test_gateway_selection] '")
                .append(village.getName()).append("' from ")
                .append(externalPoint.toShortString()).append(":\n");

        for (VillageRoadNode gw : gateways) {
            VillageRoadNode.GatewayInfo info = gw.gatewayInfo().orElse(null);
            if (info == null) continue;

            double dx = externalPoint.getX() - gw.position().getX();
            double dz = externalPoint.getZ() - gw.position().getZ();
            double len = Math.sqrt(dx * dx + dz * dz);

            double alignment;
            if (len < 0.001) {
                alignment = 1.0;
            } else {
                double rad = info.outwardDirection().toRadians();
                alignment = (dx / len) * Math.cos(rad) + (dz / len) * Math.sin(rad);
            }

            boolean rejected = alignment < -0.3;
            sb.append("  ").append(gw.nodeId().toString().substring(0, 8))
              .append(" role=").append(info.role())
              .append(" dir=").append(info.outwardDirection())
              .append(String.format(" alignment=%.3f", alignment))
              .append(rejected ? " [REJECTED]" : "")
              .append('\n');
        }

        java.util.Optional<VillageRoadNode> selected =
                ConnectorPlanner.selectGateway(graph, externalPoint);
        selected.ifPresent(gw -> sb.append("  => SELECTED: ")
                .append(gw.nodeId().toString().substring(0, 8))
                .append(" role=").append(gw.gatewayInfo().map(i -> i.role().name()).orElse("?")));

        String msg = sb.toString().trim();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return selected.isPresent() ? 1 : 0;
    }

    // =========================================================================
    // 7f Slice 2: show_all_gateways — particle visualization of all gateways
    // =========================================================================

    private static int showAllGateways(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player     = ctx.getSource().getPlayerOrException();
        ServerLevel level       = ctx.getSource().getLevel();
        VillageSavedData vData  = VillageSavedData.get(level);
        VillageRoadsSavedData rData = VillageRoadsSavedData.get(level);

        java.util.List<RoadDebugVisualizer.ParticleEmission> emissions = new java.util.ArrayList<>();
        int totalGateways = 0;

        for (tterrag1112.life_in_the_village.Village.Village village : vData.getAllVillages()) {
            VillageRoadGraph graph = rData.getOrCreate(village.getId());
            for (VillageRoadNode gw : graph.gateways()) {
                VillageRoadNode.GatewayInfo info = gw.gatewayInfo().orElse(null);

                // Choose particle color by role
                net.minecraft.core.particles.ParticleOptions particle;
                if (info != null) {
                    particle = switch (info.role()) {
                        case PRIMARY -> ParticleTypes.FLAME;         // bright orange
                        case SIDE    -> ParticleTypes.SOUL_FIRE_FLAME; // blue-green
                        case REAR    -> ParticleTypes.SMOKE;          // grey
                    };
                } else {
                    particle = ParticleTypes.ENCHANT;
                }

                // Vertical beacon at gateway position
                for (int dy = 0; dy < 12; dy++) {
                    emissions.add(new RoadDebugVisualizer.ParticleEmission(
                            gw.position().above(dy),
                            particle,
                            RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
                }

                // Mark arm endpoint too (smaller beacon)
                if (info != null) {
                    for (int dy = 0; dy < 6; dy++) {
                        emissions.add(new RoadDebugVisualizer.ParticleEmission(
                                info.armEndpoint().above(dy),
                                ParticleTypes.END_ROD,
                                RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL));
                    }
                }
                totalGateways++;
            }
        }

        if (emissions.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[show_all_gateways] No gateways found across all villages."), false);
            return 0;
        }

        RoadDebugVisualizer.INSTANCE.addSession(
                player.getUUID(), level.getGameTime(), emissions);

        final int total = totalGateways;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[show_all_gateways] Visualizing " + total
                + " gateway(s) across all villages. FLAME=PRIMARY, SOUL_FIRE=SIDE, SMOKE=REAR."),
                false);
        return totalGateways;
    }

    // =========================================================================
    // Phase 7g — palette + lighting debug
    // =========================================================================

    private static int paletteForEdge(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        VillageSavedData vData = VillageSavedData.get(level);

        RoadEdge edge = resolveEdgeOrFail(ctx, graph,
                StringArgumentType.getString(ctx, "edgeId").toLowerCase(Locale.ROOT));
        if (edge == null) return 0;

        CulturePaletteResolver.Resolved resolved =
                CulturePaletteResolver.resolve(edge, graph, vData);
        CulturePalette palette = resolved.palette();
        RoadLightingProfile lighting = resolved.lighting();

        String shortId = edge.getEdgeId().toString().substring(0, 8);
        String maintainer = edge.getTier() == RoadEdge.EdgeTier.GREAT_ROAD
                ? "great road / old realm"
                : resolved.cultureId();
        String overrideSuffix = edge.getLightingOverride().isPresent() ? " (override)" : "";

        ctx.getSource().sendSuccess(() -> Component.literal(
                "[palette] edge=" + shortId + " tier=" + edge.getTier()
                + " culture=" + maintainer
                + " paletteId=" + palette.cultureId()),
                false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "  surface: primary=" + blockNames(palette.surfacePrimary())
                + " accent=" + blockNames(palette.surfaceAccent())
                + " rare=" + blockNames(palette.surfaceRare())),
                false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "  supports: primary=" + blockNames(palette.supportPrimary())
                + " accent=" + blockNames(palette.supportAccent())
                + " rare=" + blockNames(palette.supportRare())),
                false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "  lighting: " + lighting.frequency() + " / " + lighting.strategy() + overrideSuffix
                + "   base=" + blockName(palette.lightBaseBlock())
                + " light=" + blockName(palette.lightBlock())
                + palette.lightCapBlock().map(b -> " cap=" + blockName(b)).orElse("")),
                false);
        return 1;
    }

    private static int forceLighting(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        VillageSavedData vData = VillageSavedData.get(level);

        RoadEdge edge = resolveEdgeOrFail(ctx, graph,
                StringArgumentType.getString(ctx, "edgeId").toLowerCase(Locale.ROOT));
        if (edge == null) return 0;

        String freqArg = StringArgumentType.getString(ctx, "frequency").toUpperCase(Locale.ROOT);
        String stratArg = StringArgumentType.getString(ctx, "strategy").toUpperCase(Locale.ROOT);
        RoadLightingProfile.Frequency freq;
        RoadLightingProfile.Strategy strat;
        try {
            freq = RoadLightingProfile.Frequency.valueOf(freqArg);
            strat = RoadLightingProfile.Strategy.valueOf(stratArg);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "[force_lighting] Invalid frequency or strategy. Frequency: "
                    + java.util.Arrays.toString(RoadLightingProfile.Frequency.values())
                    + " Strategy: "
                    + java.util.Arrays.toString(RoadLightingProfile.Strategy.values())));
            return 0;
        }

        RoadLightingProfile profile = new RoadLightingProfile(freq, strat);
        edge.setLightingOverride(profile);

        CulturePaletteResolver.Resolved resolved =
                CulturePaletteResolver.resolve(edge, graph, vData);

        // Re-place lights immediately using the new profile.
        RoadLightingPlacer.PlacementResult result = RoadLightingPlacer.placeLighting(
                level, edge, resolved.palette(), profile, graph);

        String shortId = edge.getEdgeId().toString().substring(0, 8);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[force_lighting] edge=" + shortId
                + " override=" + freq + "/" + strat
                + " placed=" + result.lightsPlaced()),
                false);
        WorldRoadSavedData.get(level).markDirty();
        return result.lightsPlaced();
    }

    private static int lightingSummary(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        VillageSavedData vData = VillageSavedData.get(level);

        int[] freqCounts = new int[RoadLightingProfile.Frequency.values().length];
        int[] stratCounts = new int[RoadLightingProfile.Strategy.values().length];
        int realisedEdges = 0;
        int totalLightsPlaced = 0;

        for (RoadEdge edge : graph.allEdges()) {
            if (!edge.isRealized()) continue;
            realisedEdges++;
            CulturePaletteResolver.Resolved resolved =
                    CulturePaletteResolver.resolve(edge, graph, vData);
            RoadLightingProfile p = resolved.lighting();
            freqCounts[p.frequency().ordinal()]++;
            stratCounts[p.strategy().ordinal()]++;

            // Count persisted light decoration positions along the edge — approximate
            // "total lights" from the per-edge decorationPositions list.
            totalLightsPlaced += edge.getDecorationPositions().size();
        }

        int finalRealisedEdges = realisedEdges;
        int finalTotalLightsPlaced = totalLightsPlaced;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[lighting_summary] realisedEdges=" + finalRealisedEdges
                + " approxLightPositions=" + finalTotalLightsPlaced),
                false);

        StringBuilder sb = new StringBuilder("  frequencies:");
        for (RoadLightingProfile.Frequency f : RoadLightingProfile.Frequency.values()) {
            sb.append(' ').append(f).append('=').append(freqCounts[f.ordinal()]);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);

        StringBuilder sb2 = new StringBuilder("  strategies:");
        for (RoadLightingProfile.Strategy s : RoadLightingProfile.Strategy.values()) {
            sb2.append(' ').append(s).append('=').append(stratCounts[s.ordinal()]);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb2.toString()), false);
        return realisedEdges;
    }

    private static int listPalettes(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[list_palettes] Registered palettes (plus always-available Old Realm)."),
                false);
        for (CulturePalette p : PaletteRegistry.all().values()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  " + p.cultureId()
                    + " lighting=" + p.defaultLighting().frequency() + "/"
                    + p.defaultLighting().strategy()
                    + " light=" + blockName(p.lightBlock())
                    + " base=" + blockName(p.lightBaseBlock())
                    + (p.isGreatRoadAlternate() ? " [greatRoadAlternate]" : "")),
                    false);
        }
        CulturePalette or = PaletteRegistry.oldRealm();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "  " + or.cultureId()
                + " lighting=" + or.defaultLighting().frequency() + "/"
                + or.defaultLighting().strategy()
                + " light=" + blockName(or.lightBlock())
                + " base=" + blockName(or.lightBaseBlock())
                + " [great-road default]"),
                false);
        return PaletteRegistry.all().size() + 1;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @Nullable
    private static RoadEdge resolveEdgeOrFail(CommandContext<CommandSourceStack> ctx,
                                               WorldRoadGraph graph,
                                               String prefix) {
        List<RoadEdge> matches = new ArrayList<>();
        for (RoadEdge edge : graph.allEdges()) {
            if (edge.getEdgeId().toString().startsWith(prefix)) matches.add(edge);
        }
        if (matches.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "No edge matches prefix '" + prefix + "'."));
            return null;
        }
        if (matches.size() > 1) {
            String list = matches.stream()
                    .limit(5)
                    .map(e -> e.getEdgeId().toString().substring(0, 8))
                    .reduce((a, b) -> a + ", " + b).orElse("");
            ctx.getSource().sendFailure(Component.literal(
                    "Ambiguous edge prefix '" + prefix + "'. Matches: " + list));
            return null;
        }
        return matches.get(0);
    }

    private static String blockName(net.minecraft.world.level.block.Block b) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath();
    }

    private static String blockNames(List<net.minecraft.world.level.block.Block> blocks) {
        if (blocks.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(blockName(blocks.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    // =========================================================================
    // Phase 9 — dead edges + network alignment debug
    // =========================================================================

    private static int deadEdges(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        long currentTick = level.getGameTime();

        int total = 0;
        for (RoadEdge edge : graph.allEdges()) {
            if (!edge.isDead()) continue;
            DeadEdgeState state = edge.getDeadState().get();
            DeadEdgeState.DeathPhase phase = state.phaseAtTick(currentTick);
            long ticksToNext = state.ticksUntilNextPhase(currentTick);
            String shortId = edge.getEdgeId().toString().substring(0, 8);
            String nextLabel = ticksToNext < 0
                    ? "(none — RECLAIMED)"
                    : (ticksToNext / DeadEdgeState.TICKS_PER_DAY) + " day(s)";
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  " + shortId + " tier=" + edge.getTier()
                    + " phase=" + phase
                    + " markedTick=" + state.firstMarkedTick()
                    + " untilNext=" + nextLabel),
                    false);
            total++;
        }
        final int finalTotal = total;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[dead_edges] " + finalTotal + " edge(s) currently dead."),
                false);
        return total;
    }

    private static int forceDead(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        RoadEdge edge = resolveEdgeOrFail(ctx, graph,
                StringArgumentType.getString(ctx, "edgeId").toLowerCase(Locale.ROOT));
        if (edge == null) return 0;
        if (edge.getTier() == RoadEdge.EdgeTier.GREAT_ROAD) {
            ctx.getSource().sendFailure(Component.literal(
                    "[force_dead] GREAT_ROAD edges never die (invariant 3)."));
            return 0;
        }
        edge.markDead(level.getGameTime());
        WorldRoadSavedData.get(level).markDirty();
        String shortId = edge.getEdgeId().toString().substring(0, 8);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[force_dead] edge=" + shortId + " marked dead at tick "
                + level.getGameTime()),
                false);
        return 1;
    }

    private static int forceDeathPhase(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        RoadEdge edge = resolveEdgeOrFail(ctx, graph,
                StringArgumentType.getString(ctx, "edgeId").toLowerCase(Locale.ROOT));
        if (edge == null) return 0;
        if (edge.getTier() == RoadEdge.EdgeTier.GREAT_ROAD) {
            ctx.getSource().sendFailure(Component.literal(
                    "[force_death_phase] GREAT_ROAD edges never die (invariant 3)."));
            return 0;
        }
        String phaseArg = StringArgumentType.getString(ctx, "phase").toUpperCase(Locale.ROOT);
        DeadEdgeState.DeathPhase phase;
        try {
            phase = DeadEdgeState.DeathPhase.valueOf(phaseArg);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(
                    "[force_death_phase] Unknown phase. Valid: "
                    + java.util.Arrays.toString(DeadEdgeState.DeathPhase.values())));
            return 0;
        }

        DeadEdgeState current = edge.getDeadState().orElse(
                DeadEdgeState.markedAt(level.getGameTime()));
        edge.setDeadState(current.atPhase(phase, level.getGameTime()));
        WorldRoadSavedData.get(level).markDirty();
        String shortId = edge.getEdgeId().toString().substring(0, 8);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[force_death_phase] edge=" + shortId + " phase=" + phase),
                false);
        return 1;
    }

    private static int reclaimEdge(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        RoadEdge edge = resolveEdgeOrFail(ctx, graph,
                StringArgumentType.getString(ctx, "edgeId").toLowerCase(Locale.ROOT));
        if (edge == null) return 0;
        if (!edge.isDead()
                || edge.getDeadState().get().phaseAtTick(level.getGameTime())
                        != DeadEdgeState.DeathPhase.RECLAIMED) {
            ctx.getSource().sendFailure(Component.literal(
                    "[reclaim_edge] edge is not RECLAIMED. Use force_death_phase RECLAIMED first."));
            return 0;
        }
        ReclaimedEdgeCleanup.despawnEdgeBlocks(level, edge);
        UUID edgeId = edge.getEdgeId();
        graph.removeEdge(edgeId);
        DeadEdgeDetector.forget(edgeId);
        WorldRoadSavedData.get(level).markDirty();
        String shortId = edgeId.toString().substring(0, 8);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[reclaim_edge] removed edge " + shortId + " from graph"),
                false);
        return 1;
    }

    private static int networkScore(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        BlockPos pos = new BlockPos(x, y, z);
        float score = NetworkAlignmentScorer.networkAlignmentScore(pos, graph);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[network_score] (" + x + "," + y + "," + z + ") = "
                + String.format(Locale.ROOT, "%.1f", score)
                + " / " + NetworkAlignmentScorer.MAX_SCORE),
                false);
        return Math.round(score);
    }

    private static int villageDeath(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData vData = VillageSavedData.get(level);
        String name = StringArgumentType.getString(ctx, "villageName");
        Village village = vData.getVillageByName(name).orElse(null);
        if (village == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[village_death] no village named '" + name + "'."));
            return 0;
        }
        UUID villageId = village.getId();
        // Remove from kingdoms first so any village->kingdom mapping cleans up.
        vData.getAllKingdoms().forEach(k -> k.removeVillage(villageId));
        vData.removeVillage(villageId);
        // Trigger an immediate scan so dead-edge marking is visible right away.
        int newlyDead = DeadEdgeDetector.scanForDeadEdges(level);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[village_death] removed village '" + name + "' ("
                + villageId.toString().substring(0, 8)
                + "); scan flagged " + newlyDead + " new dead edge(s)."),
                false);
        return newlyDead;
    }

    // =========================================================================
    // Phase 10 — events debug
    // =========================================================================

    /**
     * Lists events on either an edge or a node — argument is matched against
     * both edge and node UUIDs by prefix.
     */
    private static int eventsForTarget(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        WorldRoadSavedData saved = WorldRoadSavedData.get(level);
        long currentTick = level.getGameTime();

        String prefix = StringArgumentType.getString(ctx, "targetId").toLowerCase(Locale.ROOT);
        RoadEdge edge = null;
        for (RoadEdge e : graph.allEdges()) {
            if (e.getEdgeId().toString().startsWith(prefix)) { edge = e; break; }
        }
        RoadNode node = null;
        if (edge == null) {
            for (RoadNode n : graph.allNodes()) {
                if (n.nodeId().toString().startsWith(prefix)) { node = n; break; }
            }
        }
        if (edge == null && node == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[events] No edge or node matches prefix '" + prefix + "'."));
            return 0;
        }

        List<UUID> ids = edge != null ? edge.getEventIds() : node.getEventIds();
        String label = edge != null
                ? ("edge " + edge.getEdgeId().toString().substring(0, 8))
                : ("node " + node.nodeId().toString().substring(0, 8));
        if (ids.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[events] " + label + " has no events."), false);
            return 0;
        }
        for (UUID id : ids) {
            RoadEvent ev = saved.getEvent(id).orElse(null);
            if (ev == null) continue;
            String shortId = id.toString().substring(0, 8);
            String exp = ev.expiresAtTick()
                    .map(t -> String.valueOf(t - currentTick))
                    .orElse("permanent");
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  " + shortId + " type=" + ev.typeId()
                    + " pos=" + ev.position().toShortString()
                    + " placedTick=" + ev.placedTick()
                    + " expires=" + exp),
                    false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[events] " + label + " has " + ids.size() + " event(s)."), false);
        return ids.size();
    }

    private static int eventsNear(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadSavedData saved = WorldRoadSavedData.get(level);
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        long r2 = (long) radius * radius;

        BlockPos here = player.blockPosition();
        List<RoadEvent> sorted = new ArrayList<>();
        for (RoadEvent ev : saved.allEvents()) {
            long dx = ev.position().getX() - here.getX();
            long dz = ev.position().getZ() - here.getZ();
            if (dx * dx + dz * dz > r2) continue;
            sorted.add(ev);
        }
        sorted.sort((a, b) -> Long.compare(distSqXZ(a.position(), here), distSqXZ(b.position(), here)));
        for (RoadEvent ev : sorted) {
            String shortId = ev.eventId().toString().substring(0, 8);
            long dist = (long) Math.sqrt(distSqXZ(ev.position(), here));
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  " + shortId + " type=" + ev.typeId()
                    + " pos=" + ev.position().toShortString()
                    + " dist=" + dist
                    + (ev.properties().isEmpty() ? "" : " props=" + ev.properties())),
                    false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[events_near] " + sorted.size() + " event(s) within " + radius + " blocks."), false);
        return sorted.size();
    }

    private static int spawnEvent(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadSavedData saved = WorldRoadSavedData.get(level);

        String typeId = StringArgumentType.getString(ctx, "typeId");
        RoadEventType type = RoadEventRegistry.get(typeId).orElse(null);
        if (type == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[spawn_event] Unknown typeId '" + typeId + "'. Use event_registry to list."));
            return 0;
        }
        if (type.worldUnique() && saved.isWorldUniquePlaced(typeId)) {
            ctx.getSource().sendFailure(Component.literal(
                    "[spawn_event] type '" + typeId + "' is worldUnique and already placed."));
            return 0;
        }

        BlockPos pos = player.blockPosition();
        net.minecraft.util.RandomSource rng = net.minecraft.util.RandomSource.create(
                ((long) pos.getX() << 32) ^ pos.getZ() ^ level.getGameTime());
        EventPlacementContext placeCtx = new EventPlacementContext(
                level, null, java.util.Optional.empty(), pos, level.getGameTime(), rng);

        RoadEvent ev;
        try {
            ev = type.factory().create(placeCtx);
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal(
                    "[spawn_event] factory threw: " + e));
            return 0;
        }
        if (ev == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[spawn_event] factory returned null (terrain unsuitable?)."));
            return 0;
        }
        saved.registerEvent(ev);
        if (type.worldUnique()) saved.markWorldUniquePlaced(typeId);
        saved.markDirty();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "[spawn_event] placed " + typeId
                + " id=" + ev.eventId().toString().substring(0, 8)
                + " at " + ev.position().toShortString()),
                false);
        return 1;
    }

    private static int despawnEvent(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadSavedData saved = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = saved.getGraph();
        String prefix = StringArgumentType.getString(ctx, "eventId").toLowerCase(Locale.ROOT);

        RoadEvent target = null;
        for (RoadEvent ev : saved.allEvents()) {
            if (ev.eventId().toString().startsWith(prefix)) { target = ev; break; }
        }
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[despawn_event] No event matches prefix '" + prefix + "'."));
            return 0;
        }
        EventLifecycleSystem.despawnEvent(level, saved, graph, target);
        saved.markDirty();
        String shortId = target.eventId().toString().substring(0, 8);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[despawn_event] removed " + shortId), false);
        return 1;
    }

    private static int eventRegistry(CommandContext<CommandSourceStack> ctx) {
        java.util.Map<String, RoadEventType> all = RoadEventRegistry.all();
        if (all.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[event_registry] no event types registered (set -Dlitv.testEvents=true to register placeholders)."),
                    false);
            return 0;
        }
        for (RoadEventType t : all.values()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  " + t.typeId()
                    + " category=" + t.category()
                    + " permanence=" + t.permanence()
                    + " rarity=" + t.rarity()
                    + " spacing=" + t.minSpacingBlocks()
                    + (t.worldUnique() ? " [worldUnique]" : "")
                    + " tiers=" + t.applicableTiers()
                    + (t.applicableBiomes().isEmpty() ? "" : " biomes=" + t.applicableBiomes())),
                    false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[event_registry] " + all.size() + " type(s) registered."), false);
        return all.size();
    }

    private static int eventStats(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        WorldRoadSavedData saved = WorldRoadSavedData.get(level);
        java.util.List<RoadEvent> all = saved.allEvents();

        java.util.Map<String, Integer> byType = new java.util.HashMap<>();
        java.util.Map<RoadEventType.EventCategory, Integer> byCategory =
                new java.util.HashMap<>();
        for (RoadEvent e : all) {
            byType.merge(e.typeId(), 1, Integer::sum);
            RoadEventRegistry.get(e.typeId()).ifPresent(t ->
                    byCategory.merge(t.category(), 1, Integer::sum));
        }

        ctx.getSource().sendSuccess(() -> Component.literal(
                "[event_stats] total=" + all.size()
                + " worldUniquePlaced=" + saved.getWorldUniqueTypesPlaced()),
                false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "  byCategory=" + byCategory), false);
        for (var e : byType.entrySet()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  " + e.getKey() + " = " + e.getValue()), false);
        }
        return all.size();
    }

    private static long distSqXZ(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX();
        long dz = (long) a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    // =========================================================================
    // Track C3.1 — road proposal commands
    // =========================================================================

    private static Village findVillageByName(VillageSavedData vdata, String name) {
        return vdata.getAllVillages().stream()
                .filter(v -> v.getName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    private static tterrag1112.life_in_the_village.Village.Roads.Proposal.RoadProposal
            findProposalByPrefix(ServerLevel level, String prefix) {
        return WorldRoadSavedData.get(level).getAllProposals().values().stream()
                .filter(p -> p.id().toString().startsWith(prefix))
                .findFirst().orElse(null);
    }

    private static int estimateRoadConnector(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData vdata = VillageSavedData.get(level);
        String aName = StringArgumentType.getString(ctx, "villageA");
        String bName = StringArgumentType.getString(ctx, "villageB");
        Village vA = findVillageByName(vdata, aName);
        Village vB = findVillageByName(vdata, bName);
        if (vA == null || vB == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown village name: '" + (vA == null ? aName : bName) + "'"));
            return 0;
        }
        var routed = tterrag1112.life_in_the_village.Village.Roads.Proposal
                .RoadProposalRouter.dryRun(level, vA, vB);
        if (routed instanceof tterrag1112.life_in_the_village.Village.Roads.Proposal
                .RoadProposalRouter.RouteResult.NoRoute(String reason)) {
            ctx.getSource().sendFailure(Component.literal("Cannot route: " + reason));
            return 0;
        }
        var ok = (tterrag1112.life_in_the_village.Village.Roads.Proposal
                .RoadProposalRouter.RouteResult.Routed) routed;
        var est = tterrag1112.life_in_the_village.Village.Roads.Proposal
                .RoadProposalCalculator.estimate(ok.cellPath(),
                        tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge.EdgeTier.CONNECTOR);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Estimate " + vA.getName() + " ↔ " + vB.getName() + ": "
                + ok.cellPath().size() + " cells, " + est.totalBlocks()
                + " blocks, fee = " + est.currencyFee()
                + " (CONNECTOR tier)."), false);
        return 1;
    }

    private static int proposeRoadConnector(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return submitProposal(ctx,
                tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge.EdgeTier.CONNECTOR);
    }

    private static int proposeRoadConnectorWithTier(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        String tierStr = StringArgumentType.getString(ctx, "tier").toUpperCase();
        tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge.EdgeTier tier;
        try {
            tier = tterrag1112.life_in_the_village.Village.Roads.Graph
                    .RoadEdge.EdgeTier.valueOf(tierStr);
        } catch (IllegalArgumentException ex) {
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown tier '" + tierStr + "'. Use LOCAL, CONNECTOR, TRUNK, or GREAT_ROAD."));
            return 0;
        }
        return submitProposal(ctx, tier);
    }

    private static int submitProposal(CommandContext<CommandSourceStack> ctx,
                                       tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge.EdgeTier tier)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData vdata = VillageSavedData.get(level);
        String aName = StringArgumentType.getString(ctx, "villageA");
        String bName = StringArgumentType.getString(ctx, "villageB");
        Village vA = findVillageByName(vdata, aName);
        Village vB = findVillageByName(vdata, bName);
        if (vA == null || vB == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown village name: '" + (vA == null ? aName : bName) + "'"));
            return 0;
        }
        var result = tterrag1112.life_in_the_village.Village.Roads.Proposal
                .RoadProposalManager.submit(level, player, vA, vB, tier);
        if (result instanceof tterrag1112.life_in_the_village.Village.Roads.Proposal
                .RoadProposalManager.SubmitResult.Rejected(String reason)) {
            ctx.getSource().sendFailure(Component.literal("Proposal rejected: " + reason));
            return 0;
        }
        var ok = (tterrag1112.life_in_the_village.Village.Roads.Proposal
                .RoadProposalManager.SubmitResult.Submitted) result;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Proposal " + ok.proposal().id().toString().substring(0, 8)
                + " submitted (" + ok.proposal().totalBlocks() + " blocks, fee paid: "
                + tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue
                        .of(ok.proposal().currencyFeeBronze())
                + "). Crafting will advance over real time."), false);
        return 1;
    }

    private static int cancelProposal(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getLevel();
        String prefix = StringArgumentType.getString(ctx, "proposalIdPrefix");
        var p = findProposalByPrefix(level, prefix);
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No proposal id starts with '" + prefix + "'"));
            return 0;
        }
        // Only the owner (or op via debug variant) can cancel through /litv.
        if (!p.proposingPlayerId().equals(player.getUUID())) {
            ctx.getSource().sendFailure(Component.literal(
                    "That proposal belongs to another player."));
            return 0;
        }
        tterrag1112.life_in_the_village.Village.Roads.Proposal
                .RoadProposalManager.cancel(level, p);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Proposal " + p.id().toString().substring(0, 8) + " cancelled. "
                + "Currency fee is not refunded."), false);
        return 1;
    }

    private static int completeProposal(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        String prefix = StringArgumentType.getString(ctx, "proposalIdPrefix");
        var p = findProposalByPrefix(level, prefix);
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No proposal id starts with '" + prefix + "'"));
            return 0;
        }
        tterrag1112.life_in_the_village.Village.Roads.Proposal
                .RoadProposalManager.complete(level, p);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Forced completion of proposal " + p.id().toString().substring(0, 8)
                + "."), false);
        return 1;
    }

    private static int listProposals(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData vdata = VillageSavedData.get(level);
        var props = WorldRoadSavedData.get(level).getAllProposals().values();
        if (props.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "No road proposals registered."), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "── Road proposals (" + props.size() + ") ──"), false);
        for (var p : props) {
            String fromName = vdata.getVillageById(p.fromVillageId())
                    .map(Village::getName).orElse("(missing)");
            String toName = vdata.getVillageById(p.toVillageId())
                    .map(Village::getName).orElse("(missing)");
            String pid = p.id().toString().substring(0, 8);
            int pct = (int) (p.progressFraction() * 100);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  " + pid + "  " + fromName + " → " + toName
                    + "  " + p.tier() + "  " + p.status()
                    + "  " + pct + "%  ("
                    + p.progressBlocks() + " / " + p.totalBlocks() + ")"), false);
        }
        return props.size();
    }
}
