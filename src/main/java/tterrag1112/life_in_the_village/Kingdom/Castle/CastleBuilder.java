package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 2 orchestrator: Walks a CastleLayout and dispatches to the
 * appropriate placer for each element.
 *
 * Execution order matters:
 *   1. Moat first (terrain excavation before anything is built above it)
 *   2. Outer towers (establish the vertical reference points)
 *   3. Outer wall segments (connect towers, fill in between)
 *   4. Inner ward (if present)
 *   5. Donjon (if present)
 *
 * All PlacementRecords from Layer 2 are collected and passed to
 * DetailApplicator (Layer 3) after structural placement is complete.
 */
public class CastleBuilder {

    private final ServerLevelAccessor level;
    private final CastleStyle style;

    public CastleBuilder(ServerLevelAccessor level, CastleStyle style) {
        this.level = level;
        this.style = style;
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Build the full castle described by layout.
     * Calls Layer 3 (DetailApplicator) internally after structural work is done.
     */
    public void build(CastleLayout layout, RandomSource rng) {
        TerrainSampler terrain  = new TerrainSampler(level);
        DetailApplicator detail = new DetailApplicator(level, style, layout.getOrigin());

        // Accumulated placement records from all Layer 2 work
        List<WallSegmentPlacer.PlacementRecord> wallRecords   = new ArrayList<>();
        List<TowerPlacer.PlacementRecord>       towerRecords  = new ArrayList<>();
        GatehousePlacer.PlacementRecord         gateRecord    = null;

        // ---- 1. Moat --------------------------------------------------------
        if (layout.hasMoat()) {
            MoatPlacer moatPlacer = new MoatPlacer(level, style, terrain);
            MoatPlacer.FillMode fill = determineMoatFill();
            BlockPos gatehousePos = layout.getGatehouse() != null
                    ? layout.getGatehouse().center()
                    : layout.getOrigin();
            moatPlacer.place(layout.getMoatBounds(), fill, gatehousePos, rng);
        }

        // ---- 2. Outer towers ------------------------------------------------
        TowerPlacer towerPlacer     = new TowerPlacer(level, style, terrain);
        GatehousePlacer gatePlacer  = new GatehousePlacer(level, style, terrain);

        for (CastleLayout.TowerNode node : layout.getOuterTowers()) {
            if (node.role() == CastleLayout.TowerNode.TowerRole.GATEHOUSE) {
                gateRecord = gatePlacer.place(node, rng);
            } else {
                TowerPlacer.PlacementRecord record = towerPlacer.place(node, rng);
                towerRecords.add(record);
                System.out.println(record);
            }
        }

        // ---- 3. Outer wall segments -----------------------------------------
        WallSegmentPlacer wallPlacer = new WallSegmentPlacer(level, style, terrain);

        for (CastleLayout.WallEdge edge : layout.getOuterWallEdges()) {
            CastleLayout.TowerNode from = layout.resolveFrom(edge);
            CastleLayout.TowerNode to   = layout.resolveTo(edge);

            // Skip the edge that connects directly into the gatehouse (the gate
            // placer already covers those wall stubs)
            // Only skip if BOTH endpoints are gatehouse — that would be a zero-length self-edge
// Single gatehouse endpoint: place the wall stub up to the gatehouse face
            if (from.role() == CastleLayout.TowerNode.TowerRole.GATEHOUSE && to.role() == CastleLayout.TowerNode.TowerRole.GATEHOUSE) {
                continue;
            }

            CastleLayout.TowerNode fromNode = layout.resolveFrom(edge);
            CastleLayout.TowerNode toNode   = layout.resolveTo(edge);
// For edges touching the gatehouse, shorten the segment so it meets the gate face
            BlockPos wallFrom = fromNode.center();
            BlockPos wallTo   = toNode.center();

            if (fromNode.role() == CastleLayout.TowerNode.TowerRole.GATEHOUSE) {
                wallFrom = offsetTowardTarget(from.center(), to.center(), from.radius() + 1);
            } else if (toNode.role() == CastleLayout.TowerNode.TowerRole.GATEHOUSE) {
                wallTo = offsetTowardTarget(to.center(), from.center(), to.radius() + 1);
            }


            WallSegmentPlacer.PlacementRecord record = wallPlacer.place(
                    fromNode, toNode, edge.wallHeight(), rng);
            wallRecords.add(record);

        }

        // ---- 4. Inner ward --------------------------------------------------
        if (layout.hasInnerWard()) {
            buildInnerWard(layout, terrain, towerPlacer, wallPlacer,
                    towerRecords, wallRecords, rng);
        }

        // ---- 5. Donjon ------------------------------------------------------
        if (layout.hasDonjon()) {
            TowerPlacer.PlacementRecord donjeonRecord = towerPlacer.place(
                    layout.getDonjon(), rng);
            towerRecords.add(donjeonRecord);
        }

        // ---- Layer 3: Detail application ------------------------------------
        applyDetails(detail, wallRecords, towerRecords, gateRecord, rng);
    }

    // -------------------------------------------------------------------------
    // Inner ward
    // -------------------------------------------------------------------------

    private void buildInnerWard(CastleLayout layout,
                                TerrainSampler terrain,
                                TowerPlacer towerPlacer,
                                WallSegmentPlacer wallPlacer,
                                List<TowerPlacer.PlacementRecord> towerRecords,
                                List<WallSegmentPlacer.PlacementRecord> wallRecords,
                                RandomSource rng) {

        CastleLayout.InnerWard ward = layout.getInnerWard();

        // Place inner ward towers
        for (CastleLayout.TowerNode node : ward.towers()) {
            TowerPlacer.PlacementRecord rec = towerPlacer.place(node, rng);
            towerRecords.add(rec);
        }

        // Place inner ward wall segments
        for (CastleLayout.WallEdge edge : ward.edges()) {
            CastleLayout.TowerNode from = ward.towers().get(edge.fromIndex());
            CastleLayout.TowerNode to   = ward.towers().get(edge.toIndex());
            WallSegmentPlacer.PlacementRecord rec = wallPlacer.place(
                    from, to, edge.wallHeight(), rng);
            wallRecords.add(rec);
        }
    }

    // -------------------------------------------------------------------------
    // Detail application (Layer 3)
    // -------------------------------------------------------------------------

    private void applyDetails(DetailApplicator detail,
                              List<WallSegmentPlacer.PlacementRecord> wallRecords,
                              List<TowerPlacer.PlacementRecord> towerRecords,
                              GatehousePlacer.PlacementRecord gateRecord,
                              RandomSource rng) {

        // Battlements on all wall segments
        for (WallSegmentPlacer.PlacementRecord rec : wallRecords) {
            detail.applyBattlements(rec, rng);
        }

        // Tower roofs
        for (TowerPlacer.PlacementRecord rec : towerRecords) {
            detail.applyTowerRoof(rec, rng);
        }

        // Gatehouse cap
        if (gateRecord != null) {
            detail.applyGatehouseCap(gateRecord, rng);
        }

        // Flags and torches
        detail.applyFlags(towerRecords, rng);
        detail.applyWallTorches(wallRecords);
    }

    // -------------------------------------------------------------------------
    // Moat fill mode determination
    // -------------------------------------------------------------------------

    /**
     * Choose the moat fill mode based on style context.
     * Override this in a subclass or pass a parameter if you want
     * to drive fill mode from the CastleStyle.
     */
    protected MoatPlacer.FillMode determineMoatFill() {
        // Default: water. Override for lava/dry-moat styles.
        return MoatPlacer.FillMode.WATER;
    }
    private BlockPos offsetTowardTarget(BlockPos from, BlockPos toward, int distance) {
        double dx = toward.getX() - from.getX();
        double dz = toward.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len == 0) return from;
        return from.offset((int)(dx / len * distance), 0, (int)(dz / len * distance));
    }
}
