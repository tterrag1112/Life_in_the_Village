package tterrag1112.life_in_the_village.Village.Planning.V2.Layer5;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingPlacer;
import tterrag1112.life_in_the_village.Village.CultureResolver;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.TintPass;
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.Culture;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DropReason;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DroppedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.Footprint;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementResult;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadSegment;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * V2 Layer 5 — minimal village spawner.
 *
 * <p>Per the design discussion: deliberate scope limit. Layer 5
 * places only buildings + roads + per-building pads + scoped
 * vegetation clearing. It does NOT call TerrainStrategy,
 * VillageDecorator, PlazaPaver, NPC population, FarmPlotPlacer,
 * trade-route, sim, guild bootstrap, or law subsystems. Trees
 * stay everywhere they aren't in a building or road region.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>{@link OverlapAuditor#audit} — fatal abort on conflicts
 *       involving required types.</li>
 *   <li>{@link TerrainAdapter#decide} — DROP buildings move from
 *       placed → dropped; PLATFORM/LEVEL pads queued.</li>
 *   <li>{@link VegetationClearer} — clear foliage in
 *       building footprints + road corridors.</li>
 *   <li>{@link PadBuilder} — execute level/platform pads.</li>
 *   <li>{@link BuildingPlacer#placeAndRegister} per surviving
 *       building (variant-aware overload).</li>
 *   <li>{@link RoadPainter} — paint road material along skeleton.</li>
 * </ol>
 */
public final class MinimalSpawner {

    private static final Logger LOGGER = LoggerFactory.getLogger(MinimalSpawner.class);
    /** All V1 placement happens at level 1. */
    private static final int BUILDING_LEVEL = 1;
    /** Buffer (in blocks) around each building footprint cleared
     *  of foliage. */
    private static final int BUILDING_VEGETATION_BUFFER = 2;
    /** Buffer beyond {@code segment.width()/2} along road corridor. */
    private static final int ROAD_VEGETATION_BUFFER = 1;

    private MinimalSpawner() {}

    public static SpawnResult spawn(ServerLevel level,
                                    PlacementResult placement,
                                    RoadNetwork roads,
                                    Culture culture,
                                    String villageName) {
        long t0 = System.currentTimeMillis();

        // 1. Overlap audit.
        OverlapAuditor.OverlapReport audit =
                OverlapAuditor.audit(placement.placed(), roads);
        if (audit.fatal()) {
            return SpawnResult.aborted(audit, "fatal overlap conflict",
                    System.currentTimeMillis() - t0);
        }

        // 2. Terrain adaptation. Mutate placement: move DROP
        //    buildings from placed → dropped.
        List<TerrainAdapter.AdaptationDecision> decisions =
                TerrainAdapter.decide(placement.placed(), level);
        List<PlacedBuilding> survivors = new ArrayList<>();
        List<TerrainAdapter.AdaptationDecision> survivorDecisions = new ArrayList<>();
        List<DroppedBuilding> additionalDrops = new ArrayList<>();
        int levelCount = 0, platformCount = 0, dropCount = 0;
        for (TerrainAdapter.AdaptationDecision d : decisions) {
            switch (d.mode()) {
                case LEVEL -> { survivors.add(d.building()); survivorDecisions.add(d); levelCount++; }
                case PLATFORM -> { survivors.add(d.building()); survivorDecisions.add(d); platformCount++; }
                case DROP -> {
                    additionalDrops.add(new DroppedBuilding(d.building().type(),
                            DropReason.TERRAIN_UNADAPTABLE,
                            d.reason().orElse("terrain too steep")));
                    dropCount++;
                }
            }
        }

        // 3. Vegetation clearing — building footprints + road corridors.
        int veg = 0;
        for (PlacedBuilding b : survivors) {
            veg += VegetationClearer.clearForBuilding(level, b, BUILDING_VEGETATION_BUFFER);
        }
        for (RoadSegment seg : roads.skeleton().allSegments()) {
            veg += VegetationClearer.clearForRoadSegment(level, seg, ROAD_VEGETATION_BUFFER);
        }

        // 4. Pads — flatten / platform per surviving building.
        for (TerrainAdapter.AdaptationDecision d : survivorDecisions) {
            PadBuilder.buildPad(level, d);
        }

        // 5. NBT placement.
        EnumMap<tterrag1112.life_in_the_village.Village.Buildings.BuildingType, Integer>
                typeCounters = new EnumMap<>(
                tterrag1112.life_in_the_village.Village.Buildings.BuildingType.class);
        int placedOk = 0;
        int placeFail = 0;
        for (PlacedBuilding b : survivors) {
            Identifier structureId = CultureResolver.resolve(
                    culture.id(), Style.RURAL, b.type(), b.variantId(),
                    BUILDING_LEVEL, level);
            BlockPos buildPos = centreToPlaceArg(b);
            int idx = typeCounters.merge(b.type(), 1, Integer::sum);
            String name = villageName + "_" + b.type().name().toLowerCase() + "_" + idx;
            try {
                Optional<Building> placedOpt = BuildingPlacer.placeAndRegister(
                        level, buildPos, structureId, name, b.type(), b.rotation(),
                        b.variantId(), TintPass.Plan.NONE);
                if (placedOpt.isPresent()) placedOk++;
                else placeFail++;
            } catch (Exception e) {
                LOGGER.warn("MinimalSpawner: failed to place {} at {}: {}",
                        b.type(), buildPos, e.getMessage());
                placeFail++;
            }
        }

        // 6. Roads.
        int roadBlocks = RoadPainter.paintAll(level, roads, culture);

        long elapsed = System.currentTimeMillis() - t0;
        return new SpawnResult(placedOk, placeFail, dropCount, levelCount, platformCount,
                roadBlocks, veg, additionalDrops, audit, elapsed,
                /*aborted*/ false, /*abortReason*/ null);
    }

    /**
     * V1 semantics: {@code BuildingPlacer.placeAndRegister} treats
     * its {@code pos} arg as the pivot that
     * {@code StructureTemplate.placeInWorld} rotates around. For
     * each rotation we work backwards from "the rotated footprint
     * should be centred on {@code pb.centre()}" to compute the
     * pivot. Algorithm cribbed from VillageSpawner.java:230–256 so
     * V2 spawns line up with V1's placement convention.
     */
    private static BlockPos centreToPlaceArg(PlacedBuilding pb) {
        Footprint fp = pb.footprint();
        // V2's Footprint stores raw (Rotation.NONE) dimensions; V1's
        // slot.getFootprintWidth() stores POST-rotation. Compute both.
        int rawW = fp.width();
        int rawL = fp.length();
        Rotation rot = pb.rotation();
        boolean swap = rot == Rotation.CLOCKWISE_90
                || rot == Rotation.COUNTERCLOCKWISE_90;
        int rotW = swap ? rawL : rawW;
        int rotL = swap ? rawW : rawL;
        int halfW = rotW / 2;
        int halfL = rotL / 2;
        BlockPos centre = pb.centre();
        BlockPos targetNW = new BlockPos(
                centre.getX() - halfW, centre.getY(), centre.getZ() - halfL);
        return switch (rot) {
            case CLOCKWISE_90 -> targetNW.offset(rawL - 1, 0, 0);
            case COUNTERCLOCKWISE_90 -> targetNW.offset(0, 0, rawW - 1);
            case CLOCKWISE_180 -> targetNW.offset(rawW - 1, 0, rawL - 1);
            default -> targetNW;
        };
    }

    public record SpawnResult(int buildingsPlaced,
                              int buildingsFailedPlacement,
                              int buildingsDroppedTerrain,
                              int padsLevel,
                              int padsPlatform,
                              int roadBlocksPainted,
                              int vegetationCleared,
                              List<DroppedBuilding> additionalDrops,
                              OverlapAuditor.OverlapReport overlapReport,
                              long elapsedMs,
                              boolean aborted,
                              String abortReason) {

        static SpawnResult aborted(OverlapAuditor.OverlapReport audit,
                                   String reason, long elapsedMs) {
            return new SpawnResult(0, 0, 0, 0, 0, 0, 0,
                    List.of(), audit, elapsedMs, true, reason);
        }
    }
}
