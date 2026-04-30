package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaShape;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.BaseRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.LayoutPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.AddRing;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.AddSpur;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.FixedGrowth;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.Sector;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.SectorRole;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * PLAZA layout recipe. Phase 11: extends {@link BaseRecipe} and emits sectors.
 *
 * <p>An urban village built around a large central plaza. Civic buildings
 * form a tight ring directly tangent to the plaza, then four short spurs
 * head outward at NE/NW/SE/SW diagonals to production and residential
 * clusters. Agricultural and defensive rings sit outside as the urban edge.
 *
 * <p>Geometry is identical to the pre-conversion version. What changed:
 * each emission zone (civic ring, four spur clusters, agri/defense fringes,
 * straggler band) is wrapped in a {@link Sector} via snapshot-and-drain.
 * The {@code claimByZone} + bucket distribution is replaced by per-spur
 * capacity limits — the matcher distributes buildings across sectors.
 */
public final class PlazaRecipe extends BaseRecipe {

    // ── Sector identifiers ──────────────────────────────────────────────────
    private static final String SECTOR_CIVIC      = "plaza_civic_ring";
    private static final String SECTOR_OUTER_AGRI = "plaza_outer_agri";
    private static final String SECTOR_OUTER_DEF  = "plaza_outer_defense";
    private static final String SECTOR_STRAGGLER  = "plaza_straggler_residential";

    // ── Tag sets ────────────────────────────────────────────────────────────
    private static final Set<SlotTag> TAGS_SPUR_CLUSTER = EnumSet.of(
            SlotTag.PRODUCTION_CLUSTER, SlotTag.PRODUCTION_SPUR_END,
            SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_OUTER_AGRI = EnumSet.of(
            SlotTag.FIELD_EDGE, SlotTag.PASTURE, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_OUTER_DEFENSE = EnumSet.of(
            SlotTag.WALL_ADJACENT, SlotTag.HIGH_GROUND, SlotTag.RESIDENTIAL_OUTER);

    private static final int PLAZA_BUILDINGS     = 6;
    private static final int SPUR_COUNT          = 4;
    private static final int SPUR_CAPACITY       = 6;
    private static final int UNLIMITED_CAPACITY  = 1024;

    @Override
    protected void composeSectors(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();

        // ── Civic ring (plaza installation drains into a CIVIC_RING sector) ─
        int civicSnapshot = pctx.slotPoolSize();
        RecipeHelpers.installPlaza(pctx, centre, PlazaShape.CIRCLE);
        BlockPos squarePos = pctx.layout.getTownSquarePos();
        int civicCap = Math.max(PLAZA_BUILDINGS, Math.min(8, totalBuildings / 3));
        List<PlacementSlot> civicSlots = pctx.drainSlotsSince(civicSnapshot);
        if (!civicSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_CIVIC,
                    SectorRole.CIVIC_RING,
                    BuildingZone.CIVIC,
                    civicSlots, civicCap, false, FixedGrowth.INSTANCE, -1, null,
                    32));
        }

        // ── Choose which spur aligns best with flat terrain (main gate) ──────
        double[] spurAngles = {
                Math.PI / 4,           // SE
                3 * Math.PI / 4,       // SW
                -Math.PI / 4,          // NE
                -3 * Math.PI / 4       // NW
        };
        double terrainRad = RecipeHelpers.directionRadOf(terrain.bestFlatDir());
        int mainSpurIdx = 0;
        double bestDelta = Double.MAX_VALUE;
        for (int i = 0; i < spurAngles.length; i++) {
            double delta = Math.abs(RecipeHelpers.angleDelta(spurAngles[i], terrainRad));
            if (delta < bestDelta) { bestDelta = delta; mainSpurIdx = i; }
        }

        // ── Four spurs — each becomes a SPUR_CLUSTER sector ─────────────────
        List<List<BlockPos>> allRoadsForSnap = new ArrayList<>();
        BlockPos mainGate = null;

        for (int i = 0; i < SPUR_COUNT; i++) {
            double spurAngle = spurAngles[i];
            boolean isMain = (i == mainSpurIdx);
            int spurLength = isMain
                    ? pctx.density.getRing2Radius() * 2 + 24
                    : pctx.density.getRing1Radius() + pctx.density.getRing2Radius() / 2;
            double drift = isMain ? 7.0 : 4.0;
            RoadShape.RoadTier tier = isMain
                    ? RoadShape.RoadTier.VILLAGE_ROAD
                    : RoadShape.RoadTier.VILLAGE_PATH;

            // Branch hint just past the civic ring in the spur direction.
            int branchOffset = pctx.layout.getCivicRingRadius() + 2;
            BlockPos branchHint = new BlockPos(
                    squarePos.getX() + (int) Math.round(Math.cos(spurAngle) * branchOffset),
                    squarePos.getY(),
                    squarePos.getZ() + (int) Math.round(Math.sin(spurAngle) * branchOffset));
            // Synthetic 2-point parent so the Spur primitive has a road to branch from.
            List<BlockPos> synthParent = List.of(squarePos, branchHint);

            int beforeEdges = pctx.layout.getRoadGraph().edgeCount();
            RoadPrimitive.Spur spur = new RoadPrimitive.Spur(
                    synthParent, branchHint, spurAngle, spurLength, drift, tier);
            List<BlockPos> spurCenterline = pctx.layout.addRoad(
                    spur, pctx.level, pctx.worldSeed);
            int spurEdgeId = pctx.layout.getRoadGraph().edgeCount() > beforeEdges
                    ? pctx.layout.getRoadGraph().edgeCount() - 1 : -1;
            allRoadsForSnap.add(spurCenterline);

            BlockPos focal = spurCenterline.isEmpty() ? branchHint
                    : spurCenterline.get(spurCenterline.size() - 1);
            if (isMain) mainGate = focal;
            pctx.layout.addGatePosition(focal);

            // Placeholder list for BuildingCircle size estimation (count only;
            // the matcher selects actual buildings from pctx.remaining).
            int placeholderCount = Math.max(3, totalBuildings / SPUR_COUNT);
            List<VillageTypeData.StarterBuilding> placeholder = new ArrayList<>();
            int copy = Math.min(placeholderCount, pctx.remaining.size());
            for (int k = 0; k < copy; k++) placeholder.add(pctx.remaining.get(k));

            int spurSnapshot = pctx.slotPoolSize();
            LayoutPrimitive.BuildingCircle circle = new LayoutPrimitive.BuildingCircle(
                    focal,
                    LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                    placeholder,
                    spurCenterline);
            circle.emitSlotsWithTags(pctx, TAGS_SPUR_CLUSTER, 65);

            List<PlacementSlot> spurSlots = pctx.drainSlotsSince(spurSnapshot);
            if (!spurSlots.isEmpty()) {
                pctx.offerSector(new Sector(
                        "plaza_spur_" + i,
                        SectorRole.SPUR_CLUSTER,
                        BuildingZone.PRODUCTION,
                        spurSlots, SPUR_CAPACITY, true, new AddSpur(48, 4),
                        spurEdgeId, null,
                        16));
            }
        }

        // Fallback: use first spur end as gate if main spur was empty.
        if (mainGate == null && !allRoadsForSnap.isEmpty()) {
            List<BlockPos> first = allRoadsForSnap.get(0);
            if (!first.isEmpty()) mainGate = first.get(first.size() - 1);
        }
        if (mainGate != null) pctx.layout.setMainGateEndpoint(mainGate);

        // ── Agricultural ring → AGRICULTURAL_FRINGE sector ──────────────────
        int agriSnapshot = pctx.slotPoolSize();
        new LayoutPrimitive.RingBand(
                centre,
                pctx.density.getRing2Radius() + 10,
                pctx.density.getRing2Radius() + 28,
                BuildingZone.AGRICULTURAL,
                new ArrayList<>(),
                allRoadsForSnap
        ).emitSlots(pctx);
        List<PlacementSlot> agriSlots = pctx.drainSlotsSince(agriSnapshot);
        if (!agriSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_OUTER_AGRI,
                    SectorRole.AGRICULTURAL_FRINGE,
                    BuildingZone.AGRICULTURAL,
                    agriSlots, UNLIMITED_CAPACITY, true, new AddRing(16, 8), -1, null,
                    18));
        }

        // ── Defensive ring → DEFENSIVE_FRINGE sector ────────────────────────
        int defenseSnapshot = pctx.slotPoolSize();
        new LayoutPrimitive.RingBand(
                centre,
                pctx.density.getRing2Radius() + 4,
                pctx.density.getRing2Radius() + 14,
                BuildingZone.DEFENSIVE,
                new ArrayList<>(),
                allRoadsForSnap
        ).emitSlots(pctx);
        List<PlacementSlot> defenseSlots = pctx.drainSlotsSince(defenseSnapshot);
        if (!defenseSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_OUTER_DEF,
                    SectorRole.DEFENSIVE_FRINGE,
                    BuildingZone.DEFENSIVE,
                    defenseSlots, UNLIMITED_CAPACITY, true, new AddRing(12, 6), -1, null,
                    18));
        }

        // ── Straggler band (ring1..ring2) → RESIDENTIAL_INFILL sector ───────
        int stragSnapshot = pctx.slotPoolSize();
        new LayoutPrimitive.RingBand(
                centre,
                pctx.density.getRing1Radius(),
                pctx.density.getRing2Radius(),
                BuildingZone.RESIDENTIAL,
                new ArrayList<>(),
                allRoadsForSnap
        ).emitSlots(pctx);
        List<PlacementSlot> stragSlots = pctx.drainSlotsSince(stragSnapshot);
        if (!stragSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_STRAGGLER,
                    SectorRole.RESIDENTIAL_INFILL,
                    BuildingZone.RESIDENTIAL,
                    stragSlots, UNLIMITED_CAPACITY, true, new AddRing(16, 8), -1, null,
                    16));
        }
    }

    @Override
    protected void registerAnchors(PlanContext pctx) {
        super.registerAnchors(pctx);
        // PLAZA has no additional named anchors beyond MAIN_GATE.
    }
}
