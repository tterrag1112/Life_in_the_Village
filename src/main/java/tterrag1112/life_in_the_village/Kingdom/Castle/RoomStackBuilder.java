package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tterrag1112.life_in_the_village.Kingdom.Castle.Placers.MoatPlacer;
import tterrag1112.life_in_the_village.Kingdom.Castle.Placers.SubbuildingPlacer;
import tterrag1112.life_in_the_village.Kingdom.Castle.Placers.TowerPlacer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * TMA-style castle builder.
 *
 * Iterates the RoomStack list from GroundPlanConverter and builds each stack:
 *   CYLINDRICAL → TowerPlacer
 *   RECTANGULAR → WallFacePlacer
 *
 * After structural placement, runs DetailApplicator, SubbuildingPlacer,
 * and RuinationPass. Returns a CastleManifest.
 *
 * No dependency on deleted files (WallSegmentPlacer, CastleBuilder,
 * GatehousePlacer, DrawbridgePlacer, BarbicanPlacer, WallTurretPlacer).
 */
public class RoomStackBuilder {

    private static final Logger LOGGER = LogManager.getLogger(RoomStackBuilder.class);

    private final ServerLevelAccessor level;
    private final CastleStyle style;
    private final TerrainSampler terrain;
    private final ArchitectPalette palette;

    private static final Direction[] HORIZONTAL =
            {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    public RoomStackBuilder(ServerLevelAccessor level, CastleStyle style,
                            TerrainSampler terrain) {
        this.level   = level;
        this.style   = style;
        this.terrain = terrain;
        this.palette = style.palette();
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public CastleManifest build(CastleLayout layout,
                                List<RoomStack> stacks,
                                RandomSource rng) {

        WallFacePlacer   facePlacer  = new WallFacePlacer(level, style, terrain);
        TowerPlacer towerPlacer = new TowerPlacer(level, style, terrain);
        DetailApplicator detail      = new DetailApplicator(
                level, style, layout.getOrigin());

        List<StackPlacementRecord> wallRecords  = new ArrayList<>();
        List<StackPlacementRecord> towerRecords = new ArrayList<>();
        List<StackPlacementRecord> gateRecords  = new ArrayList<>();
        StackPlacementRecord       donjeonRecord = null;

        // ---- 1. Moat -------------------------------------------------------
        if (layout.hasMoat()) {
            new MoatPlacer(level, style, terrain).place(
                    layout.getMoatBounds(),
                    layout.getGatehouse() != null
                            ? layout.getGatehouse().center()
                            : layout.getOrigin(),
                    rng);
        }

        // ---- 2. Sort stacks by build order ---------------------------------
        List<RoomStack> towers   = filter(stacks, RoomStack.StackRole.TOWER);
        List<RoomStack> gates    = filter(stacks, RoomStack.StackRole.GATEHOUSE);
        List<RoomStack> walls    = filter(stacks, RoomStack.StackRole.OUTER_WALL,
                RoomStack.StackRole.INNER_WALL);
        List<RoomStack> donjons  = filter(stacks, RoomStack.StackRole.DONJON);
        List<RoomStack> barbs    = filter(stacks, RoomStack.StackRole.BARBICAN);

        // ---- 3. Towers -----------------------------------------------------
        for (RoomStack stack : towers) {
            TowerPlacer.PlacementRecord rec =
                    towerPlacer.place(stackToNode(stack), rng);
            towerRecords.add(toStackRecord(rec, stack));
        }

        // ---- 4. Gatehouse towers + passage ---------------------------------
        for (RoomStack stack : gates) {
            if (stack.shape() == RoomStack.StackShape.CYLINDRICAL) {
                TowerPlacer.PlacementRecord rec =
                        towerPlacer.place(stackToNode(stack), rng);
                gateRecords.add(toStackRecord(rec, stack));
            } else {
                gateRecords.add(placeGatePassage(stack, rng));
            }
        }

        // ---- 5. Drawbridge -------------------------------------------------
        if (style.features().hasDrawbridge() && layout.hasMoat()
                && layout.getGatehouse() != null) {
            placeDrawbridge(layout.getGatehouse(), rng);
        }

        // ---- 6. Wall panels ------------------------------------------------
        for (RoomStack stack : walls) {
            wallRecords.add(facePlacer.placeStack(stack, rng));
        }

        // ---- 7. Donjon -----------------------------------------------------
        for (RoomStack stack : donjons) {
            donjeonRecord = placeDonjeon(stack, facePlacer, rng);
        }

        // ---- 8. Barbican ---------------------------------------------------
        for (RoomStack stack : barbs) {
            facePlacer.placeStack(stack, rng);
        }

        // ---- 9. Wall-walk doorways (post-pass) -----------------------------
        carveWalkwayDoorways(towers, wallRecords, towerPlacer, rng);

        // ---- 10. Detail application ----------------------------------------
        applyDetails(detail, wallRecords, towerRecords, gateRecords,
                donjeonRecord, rng);

        // ---- 11. Subbuildings ----------------------------------------------
        SubbuildingPlacer subPlacer = new SubbuildingPlacer(
                level, style, terrain, detail);
        Map<SubbuildingType, List<BlockPos>> subPositions =
                subPlacer.placeAll(layout, rng);

        // ---- 12. Ruination post-pass ---------------------------------------
        if (style.isRuin()) {
            StackPlacementRecord gateRec =
                    gateRecords.isEmpty() ? null : gateRecords.get(0);
            new RuinationPass(level, style)
                    .apply(layout, wallRecords, towerRecords, gateRec, rng);
        }

        // ---- 13. Build manifest --------------------------------------------
        return buildManifest(layout, towerRecords, subPositions);
    }

    // =========================================================================
    // Gate passage
    // =========================================================================

    private StackPlacementRecord placeGatePassage(RoomStack stack, RandomSource rng) {
        int groundY  = terrain.groundY(stack.center());
        int topY     = stack.topY();
        Direction facing  = stack.facing();
        Direction lateral = facing.getClockWise();
        int gateWidth  = 4;
        int gateHeight = 5;

        // Fill passage volume
        for (int dx = 0; dx < stack.outerWidthX(); dx++) {
            for (int dz = 0; dz < stack.outerDepthZ(); dz++) {
                for (int y = groundY; y <= topY; y++) {
                    BlockPos pos = new BlockPos(
                            stack.origin().getX() + dx, y,
                            stack.origin().getZ() + dz);
                    if (level.getBlockState(pos).isAir())
                        placeBlock(pos, pickBlock(rng));
                }
            }
        }

        // Carve arch
        BlockPos gateCenter = stack.center().atY(groundY);
        for (int t = 0; t < stack.outerDepthZ(); t++) {
            BlockPos depthPos = gateCenter.relative(facing,
                    t - stack.outerDepthZ() / 2);
            for (int lat = -(gateWidth / 2); lat <= gateWidth / 2; lat++) {
                BlockPos latPos = depthPos.relative(lateral, lat);
                for (int h = 0; h < gateHeight; h++) {
                    if (isInsideArch(lat, h, gateWidth, gateHeight)) {
                        placeBlock(latPos.atY(groundY + h),
                                Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }

        // Portcullis
        if (style.features().hasPortcullis()) {
            for (int h = 1; h < gateHeight - 1; h++) {
                placeBlock(gateCenter.atY(groundY + h),
                        Blocks.IRON_BARS.defaultBlockState());
            }
        }

        List<BlockPos> cap = new ArrayList<>();
        for (int dx = 0; dx < stack.outerWidthX(); dx++) {
            for (int dz = 0; dz < stack.outerDepthZ(); dz++) {
                cap.add(new BlockPos(stack.origin().getX() + dx, topY,
                        stack.origin().getZ() + dz));
            }
        }

        return new StackPlacementRecord(cap, List.of(), List.of(),
                facing, groundY, stack);
    }

    private boolean isInsideArch(int lat, int h, int gateWidth, int gateHeight) {
        int halfW = gateWidth / 2;
        return switch (style.styleDetail().archStyle()) {
            case ROUND -> {
                if (h < gateHeight - 2) yield Math.abs(lat) < halfW;
                int relH = h - (gateHeight - 2);
                yield Math.abs(lat) < halfW
                        && (lat * lat + relH * relH) < 4.0;
            }
            case POINTED -> {
                if (h < gateHeight - 2) yield Math.abs(lat) < halfW;
                int al = gateHeight - h;
                yield Math.abs(lat) < al && al > 0;
            }
            case HORSESHOE -> {
                if (h < gateHeight - 3) yield Math.abs(lat) < halfW;
                if (h <= gateHeight - 2) yield Math.abs(lat) <= halfW + 1;
                int al = gateHeight - h;
                yield Math.abs(lat) < al && al > 0;
            }
            case FLAT  -> h < gateHeight && Math.abs(lat) < halfW;
            case TUDOR -> {
                if (h < gateHeight - 1) yield Math.abs(lat) < halfW;
                yield Math.abs(lat) < halfW - 1;
            }
        };
    }

    // =========================================================================
    // Donjon
    // =========================================================================

    private StackPlacementRecord placeDonjeon(RoomStack stack,
                                              WallFacePlacer facePlacer,
                                              RandomSource rng) {
        StackPlacementRecord rec = facePlacer.placeStack(stack, rng);
        placeDonjeonInterior(stack, rng);
        return rec;
    }

    private void placeDonjeonInterior(RoomStack stack, RandomSource rng) {
        int th       = stack.wallThickness();
        int ox       = stack.origin().getX() + th;
        int oz       = stack.origin().getZ() + th;
        int wx       = stack.widthX();
        int wz       = stack.depthZ();
        int currentY = stack.origin().getY();

        for (int fi = 0; fi < stack.floors().size(); fi++) {
            RoomStack.FloorDef floor = stack.floors().get(fi);
            int inset = floor.offsetX();

            // Clear interior and place floor
            for (int dx = inset; dx < wx - inset; dx++) {
                for (int dz = inset; dz < wz - inset; dz++) {
                    BlockPos floorPos = new BlockPos(ox + dx, currentY - 1, oz + dz);
                    if (level.getBlockState(floorPos).isAir())
                        placeBlock(floorPos, palette.pick("floor", rng));
                    for (int h = 0; h < floor.height() - 1; h++) {
                        placeBlock(new BlockPos(ox + dx, currentY + h, oz + dz),
                                Blocks.AIR.defaultBlockState());
                    }
                }
            }

            // Ladder on north inner wall
            int ladderX = ox + wx / 2;
            int ladderZ = oz + inset;
            for (int h = 1; h < floor.height(); h++) {
                BlockPos ladderPos = new BlockPos(ladderX, currentY + h, ladderZ);
                if (level.getBlockState(ladderPos.north()).isSolid()) {
                    placeBlock(ladderPos, Blocks.LADDER.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.LadderBlock.FACING,
                                    Direction.SOUTH));
                }
            }

            currentY += floor.height();
        }
    }

    // =========================================================================
    // Drawbridge
    // =========================================================================

    private void placeDrawbridge(CastleLayout.TowerNode gatehouse, RandomSource rng) {
        BlockPos gateCenter = gatehouse.center();
        Direction facing    = gatehouse.facing();
        int groundY         = terrain.groundY(gateCenter);
        int bridgeLength    = style.features().moatWidth() + 4;

        for (int d = 1; d <= bridgeLength; d++) {
            for (int lat = -2; lat <= 2; lat++) {
                BlockPos pos = gateCenter
                        .relative(facing, d)
                        .relative(facing.getClockWise(), lat)
                        .atY(groundY - 1);
                BlockState cur = level.getBlockState(pos);
                if (cur.isAir() || cur.is(Blocks.WATER)) {
                    placeBlock(pos, Blocks.OAK_PLANKS.defaultBlockState());
                }
                placeBlock(pos.above(), Blocks.AIR.defaultBlockState());
            }
        }
    }

    // =========================================================================
    // Wall-walk doorway carving
    // =========================================================================

    private void carveWalkwayDoorways(List<RoomStack> towers,
                                      List<StackPlacementRecord> wallRecords,
                                      TowerPlacer towerPlacer,
                                      RandomSource rng) {
        for (StackPlacementRecord wallRec : wallRecords) {
            if (wallRec.walkwayFloorY() <= wallRec.stack().origin().getY()) continue;

            BlockPos wallStart = wallRec.stack().origin();
            BlockPos wallEnd   = new BlockPos(
                    wallRec.stack().origin().getX() + wallRec.stack().outerWidthX(),
                    wallRec.stack().origin().getY(),
                    wallRec.stack().origin().getZ() + wallRec.stack().outerDepthZ());

            for (RoomStack tower : towers) {
                double ds = tower.center().distSqr(wallStart);
                double de = tower.center().distSqr(wallEnd);
                if (ds > 400 && de > 400) continue;

                Direction dir = ds < de
                        ? approxDir(tower.center(), wallStart)
                        : approxDir(tower.center(), wallEnd);

                towerPlacer.carveWallWalkDoorway(
                        stackToNode(tower), dir, wallRec.walkwayFloorY(), rng);
            }
        }
    }

    // =========================================================================
    // Detail application
    // =========================================================================

    private void applyDetails(DetailApplicator detail,
                              List<StackPlacementRecord> wallRecords,
                              List<StackPlacementRecord> towerRecords,
                              List<StackPlacementRecord> gateRecords,
                              StackPlacementRecord donjeonRecord,
                              RandomSource rng) {
        for (StackPlacementRecord rec : wallRecords) {
            detail.applyBattlements(rec, rng);
            detail.applyWindowSurrounds(rec, rng);
            detail.applyWallTorchesFromStack(rec);
        }
        for (StackPlacementRecord rec : towerRecords) {
            detail.applyTowerRoof(rec, rng);
            detail.applyFlagsFromStack(rec, rng);
        }
        for (StackPlacementRecord rec : gateRecords) {
            if (rec.isCylindrical()) {
                detail.applyTowerRoof(rec, rng);
            } else {
                detail.applyGatehouseCap(rec, rng);
            }
        }
        if (donjeonRecord != null) {
            detail.applyTowerRoof(donjeonRecord, rng);
        }
    }

    // =========================================================================
    // Manifest
    // =========================================================================

    private CastleManifest buildManifest(CastleLayout layout,
                                         List<StackPlacementRecord> towerRecords,
                                         Map<SubbuildingType, List<BlockPos>> subPositions) {
        List<BlockPos> towerPositions = towerRecords.stream()
                .map(r -> r.stack().center())
                .collect(Collectors.toList());

        AABB bounds = layout.hasMoat()
                ? new AABB(
                layout.getMoatBounds().min().getX(),
                layout.getOrigin().getY() - 16,
                layout.getMoatBounds().min().getZ(),
                layout.getMoatBounds().max().getX(),
                layout.getOrigin().getY() + 128,
                layout.getMoatBounds().max().getZ())
                : new AABB(layout.getOrigin()).inflate(64);

        return new CastleManifest.Builder()
                .style(style)
                .origin(layout.getOrigin())
                .gatePos(layout.getGatehouse() != null
                        ? layout.getGatehouse().center()
                        : layout.getOrigin())
                .bounds(bounds)
                .subbuildings(subPositions)
                .towers(towerPositions)
                .roster(style.function().roster())
                .build();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private List<RoomStack> filter(List<RoomStack> stacks,
                                   RoomStack.StackRole... roles) {
        List<RoomStack.StackRole> roleList = List.of(roles);
        return stacks.stream()
                .filter(s -> roleList.contains(s.role()))
                .collect(Collectors.toList());
    }

    private StackPlacementRecord toStackRecord(TowerPlacer.PlacementRecord rec,
                                               RoomStack stack) {
        return new StackPlacementRecord(
                rec.capRingPositions(), List.of(),
                rec.capRingPositions(),
                stack.facing(), rec.wallWalkY(), stack);
    }

    private CastleLayout.TowerNode stackToNode(RoomStack stack) {
        CastleLayout.TowerNode.TowerRole role = switch (stack.role()) {
            case DONJON    -> CastleLayout.TowerNode.TowerRole.DONJON;
            case GATEHOUSE -> CastleLayout.TowerNode.TowerRole.GATEHOUSE;
            default        -> CastleLayout.TowerNode.TowerRole.CORNER;
        };
        return new CastleLayout.TowerNode(
                stack.center(), role,
                stack.wallThickness(),
                stack.totalHeight(),
                stack.facing());
    }

    private Direction approxDir(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz))
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private BlockState pickBlock(RandomSource rng) {
        return rng.nextFloat() < 0.2f
                ? palette.pick("trim", rng)
                : palette.pick("fill", rng);
    }

    private void placeBlock(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }
}