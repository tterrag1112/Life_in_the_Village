package tterrag1112.life_in_the_village.Kingdom.Castle;


import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 2: Places the gatehouse complex at the designated gatehouse TowerNode.
 *
 * The gatehouse is two flanking towers straddling a gateway passage.
 * This placer handles:
 *   - Two square flanking towers on either side of the gate passage
 *   - The gate passage itself (arched tunnel through the wall)
 *   - Portcullis groove channels if hasPortcullis is true
 *   - A drawbridge slot/channel if hasDrawbridge is true
 *   - Murder holes (machicolation slots) over the passage ceiling
 *   - The cap reservation for Layer 3 gatehouse template stamping
 *
 * The gatehouse replaces what would be a corner or flanking tower at that node.
 * Wall edges still connect to the gatehouse's outer face.
 */
public class GatehousePlacer {

    // Width of the gate passage in blocks (clear opening)
    private static final int GATE_WIDTH = 4;
    // Height of the arched gate opening
    private static final int GATE_HEIGHT = 5;

    private final LevelAccessor level;
    private final CastleStyle style;
    private final TerrainSampler terrain;
    CastleStyle.FeatureConfig featureConfig;
    CastleStyle.TowerConfig towerConfig;
    CastleStyle.WallConfig wallConfig;




    public GatehousePlacer(LevelAccessor level, CastleStyle style, TerrainSampler terrain) {
        this.level   = level;
        this.style   = style;
        this.terrain = terrain;
        this.featureConfig = style.features();
        this.towerConfig = style.towers();
        this.wallConfig = style.walls();


    }


    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Place the full gatehouse at the given node.
     * The node's facing direction determines which way the gate opens.
     *
     * @return PlacementRecord with the top course positions for the gatehouse
     *         template stamping by Layer 3.
     */
    public PlacementRecord place(CastleLayout.TowerNode gateNode, RandomSource rng) {
        Direction facing  = gateNode.facing();    // direction gate opens toward
        Direction lateral = facing.getClockWise(); // axis along which towers flank

        int groundY  = terrain.groundY(gateNode.center());
        int wallH    = gateNode.wallHeight();
        int topY     = groundY + wallH + towerConfig.towerHeightBonus() + 2; // gatehouses taller
        int towerR   = gateNode.radius();

        // The two flanking tower centers
        BlockPos leftCenter  = gateNode.center().relative(lateral,  towerR + GATE_WIDTH / 2);
        BlockPos rightCenter = gateNode.center().relative(lateral, -(towerR + GATE_WIDTH / 2));

        // Place left and right flanking towers
        placeFlankingTower(leftCenter,  towerR, groundY, topY, rng);
        placeFlankingTower(rightCenter, towerR, groundY, topY, rng);

        // Place the gate passage connecting the two towers
        List<BlockPos> passageCeiling = new ArrayList<>();
        placeGatePassage(gateNode.center(), facing, lateral, groundY, topY,
                wallH, rng, passageCeiling);

        // Portcullis groove
        if (featureConfig.hasPortcullis()) {
            placePortcullisGroove(gateNode.center(), lateral, groundY, wallH);
        }

        // Drawbridge channel
        if (featureConfig.hasDrawbridge()) {
            placeDrawbridgeChannel(gateNode.center(), facing, groundY, rng);
        }

        // Collect cap positions for both towers + the gate span
        List<BlockPos> capPositions = collectCapPositions(
                leftCenter, rightCenter, towerR, topY);

        return new PlacementRecord(capPositions, passageCeiling, gateNode, topY);
    }

    // -------------------------------------------------------------------------
    // Flanking tower
    // -------------------------------------------------------------------------

    private void placeFlankingTower(BlockPos center, int radius,
                                    int groundY, int topY, RandomSource rng) {
        for (int y = groundY; y <= topY; y++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    boolean isShell = Math.abs(dx) > radius - wallConfig.wallThickness()
                            || Math.abs(dz) > radius - wallConfig.wallThickness();
                    BlockPos pos    = new BlockPos(
                            center.getX() + dx, y, center.getZ() + dz);

                    if (isShell) {
                        placeBlock(pos, pickBlock(rng));
                    } else {
                        placeBlock(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Gate passage
    // -------------------------------------------------------------------------

    /**
     * Carves the gate tunnel through the wall and builds the connecting span above it.
     * Places murder holes in the ceiling of the tunnel.
     */
    private void placeGatePassage(BlockPos gateCenter, Direction facing, Direction lateral,
                                  int groundY, int topY, int wallH,
                                  RandomSource rng, List<BlockPos> ceilingOut) {
        // Tunnel depth = wall thickness + a short barbican extension
        int tunnelDepth = wallConfig.wallThickness() + 3;

        for (int t = -tunnelDepth / 2; t <= tunnelDepth / 2; t++) {
            BlockPos depthPos = gateCenter.relative(facing, t);

            for (int lat = -(GATE_WIDTH / 2); lat <= GATE_WIDTH / 2; lat++) {
                BlockPos lateralPos = depthPos.relative(lateral, lat);

                for (int y = groundY; y <= topY; y++) {
                    BlockPos pos = lateralPos.atY(y);
                    boolean inArch    = isInsideArch(lat, y - groundY);
                    boolean isCeiling = (y == groundY + GATE_HEIGHT);

                    if (y < groundY + GATE_HEIGHT && inArch) {
                        // Clear the passage
                        placeBlock(pos, Blocks.AIR.defaultBlockState());
                        // Murder holes in the ceiling
                        if (isCeiling && lat == 0 && t % 2 == 0) {
                            ceilingOut.add(pos);
                        }
                    } else {
                        // Build the wall above and to the sides of the arch
                        if (!level.getBlockState(pos).isAir()) continue;
                        placeBlock(pos, pickBlock(rng));
                    }
                }
            }
        }
    }

    /**
     * Returns true if the given lateral offset and height offset are inside the
     * gate arch opening. Uses a simple pointed arch shape.
     */
    private boolean isInsideArch(int lat, int heightOffset) {
        if (heightOffset < 0) return false;
        if (heightOffset < GATE_HEIGHT - 2) {
            // Rectangular lower section
            return Math.abs(lat) < GATE_WIDTH / 2;
        }
        // Arch top: narrow toward the center
        int archLat = GATE_HEIGHT - heightOffset;
        return Math.abs(lat) < archLat && archLat > 0;
    }

    // -------------------------------------------------------------------------
    // Portcullis groove
    // -------------------------------------------------------------------------

    /**
     * Carves vertical 1-wide grooves on either side of the gate for portcullis guides.
     * In a real implementation you'd place a custom block entity or fence gate here.
     */
    private void placePortcullisGroove(BlockPos gateCenter, Direction lateral,
                                       int groundY, int wallH) {
        for (int side : new int[]{-(GATE_WIDTH / 2), GATE_WIDTH / 2}) {
            BlockPos grooveBase = gateCenter.relative(lateral, side);
            for (int y = groundY; y <= groundY + wallH - 1; y++) {
                // Cut a 1-block-wide vertical slot for the portcullis channel
                // In production, place your custom portcullis channel block here
                BlockPos pos = grooveBase.atY(y);
                placeBlock(pos, Blocks.AIR.defaultBlockState());
            }
        }
        // Place iron bars as a stand-in for the portcullis
        for (int lat = -(GATE_WIDTH / 2); lat <= GATE_WIDTH / 2; lat++) {
            BlockPos barPos = gateCenter.relative(lateral, lat).atY(groundY + 1);
            placeBlock(barPos, Blocks.IRON_BARS.defaultBlockState());
        }
    }

    // -------------------------------------------------------------------------
    // Drawbridge channel
    // -------------------------------------------------------------------------

    /**
     * Excavates a shallow channel in front of the gate into which the drawbridge
     * would lower. Also places a floor of planks as the raised drawbridge placeholder.
     */
    private void placeDrawbridgeChannel(BlockPos gateCenter, Direction facing,
                                        int groundY, RandomSource rng) {
        int bridgeLength = featureConfig.moatWidth() > 0 ? featureConfig.moatWidth() : 5;

        for (int step = 1; step <= bridgeLength; step++) {
            BlockPos stepPos = gateCenter.relative(facing, step);

            // Excavate one block below ground level for the channel
            for (int lat = -(GATE_WIDTH / 2); lat <= GATE_WIDTH / 2; lat++) {
                BlockPos channelPos = stepPos.relative(
                        facing.getClockWise(), lat).atY(groundY - 1);
                placeBlock(channelPos, Blocks.AIR.defaultBlockState());
            }

            // Place oak planks as the bridge surface
            for (int lat = -(GATE_WIDTH / 2); lat <= GATE_WIDTH / 2; lat++) {
                BlockPos bridgePos = stepPos.relative(
                        facing.getClockWise(), lat).atY(groundY);
                placeBlock(bridgePos, Blocks.OAK_PLANKS.defaultBlockState());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cap collection
    // -------------------------------------------------------------------------

    private List<BlockPos> collectCapPositions(BlockPos leftCenter, BlockPos rightCenter,
                                               int towerR, int topY) {
        List<BlockPos> caps = new ArrayList<>();
        for (BlockPos center : List.of(leftCenter, rightCenter)) {
            for (int dx = -towerR; dx <= towerR; dx++) {
                for (int dz = -towerR; dz <= towerR; dz++) {
                    boolean isShell = Math.abs(dx) >= towerR - wallConfig.wallThickness()
                            || Math.abs(dz) >= towerR - wallConfig.wallThickness();
                    if (isShell) {
                        caps.add(new BlockPos(
                                center.getX() + dx, topY, center.getZ() + dz));
                    }
                }
            }
        }
        return caps;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private BlockState pickBlock(RandomSource rng) {
        // Gatehouses use accent material for variation
        if (rng.nextFloat() < 0.25f) {
            return style.accentMaterial().pickMain(rng);
        }
        return style.primaryMaterial().pickMain(rng);
    }

    private void placeBlock(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    // -------------------------------------------------------------------------
    // PlacementRecord
    // -------------------------------------------------------------------------

    public record PlacementRecord(
            List<BlockPos> capPositions,      // Tops of both flanking towers
            List<BlockPos> ceilingPositions,  // Murder hole ceiling positions
            CastleLayout.TowerNode gateNode,
            int topY
    ) {}
}
