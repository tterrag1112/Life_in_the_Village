package tterrag1112.life_in_the_village.Village.Decoration.Plaza;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Decoration.TownSquare.GatheringPoint;
import tterrag1112.life_in_the_village.Village.Decoration.TownSquare.GatheringPointKind;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Random;
import java.util.UUID;

/**
 * Per-kind renderer dispatch for {@link PlazaPiece}s — the plaza analogue of the
 * farm complex's {@code PlotInteriorRenderer}. Each piece kind renders
 * independently; a missing NBT / failed piece logs and is skipped (the spawn
 * never aborts on decoration).
 */
public final class PlazaPieceRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlazaPieceRenderer.class);

    private PlazaPieceRenderer() {}

    /** Centerpiece NBT (decisions-locked: reuse {@code well_hamlet} for now). */
    private static final String WELL_NBT =
            "structures/default/decoration/town_square/well_hamlet.nbt";
    /** Capacity of the fountain gathering point (how many NPCs may gather). */
    private static final int FOUNTAIN_CAPACITY = 6;

    /** Renders one piece; returns true if it drew something. */
    public static boolean render(ServerLevel level, Village village,
                                 PlazaRegion region, PlazaPiece piece, Random rng) {
        try {
            return switch (piece.kind()) {
                case FOUNTAIN -> renderFountain(level, village, piece);
                case GARDEN -> renderGarden(level, region, piece, rng);
                case OPEN -> false;   // paving stays as the paver laid it
                // Staged framework kinds — accepted, not yet emitted by the planner.
                case WELL, MARKET_STALL, NOTICEBOARD, BENCH_CLUSTER -> false;
            };
        } catch (Exception e) {
            LOGGER.warn("PlazaPieceRenderer: {} piece at {} failed: {}",
                    piece.kind(), piece.pos(), e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // FOUNTAIN — stamp well_hamlet.nbt at the centre + register a gathering point
    // =========================================================================

    private static boolean renderFountain(ServerLevel level, Village village,
                                          PlazaPiece piece) {
        BlockPos centre = piece.pos();   // (cx, floorY, cz)
        StructureTemplate template = loadTemplate(level, WELL_NBT);
        if (template != null) {
            // Centre the footprint on the plaza centroid.
            Vec3i size = template.getSize();
            BlockPos origin = new BlockPos(
                    centre.getX() - size.getX() / 2,
                    centre.getY(),
                    centre.getZ() - size.getZ() / 2);
            StructurePlaceSettings settings =
                    new StructurePlaceSettings().setRotation(Rotation.NONE);
            template.placeInWorld(level, origin, BlockPos.ZERO, settings,
                    level.getRandom(), 2);
        } else {
            // NBT missing → a small procedural stone-brick well so the centre
            // isn't bare (mirrors the retired code-gen centerpiece).
            renderFallbackWell(level, centre);
        }
        // The social layer navigates here; register it so it persists.
        village.addGatheringPoint(new GatheringPoint(
                UUID.randomUUID(), centre, GatheringPointKind.FOUNTAIN, FOUNTAIN_CAPACITY));
        return true;
    }

    /** Retired-centerpiece fallback: 3×3 stone-brick rim (two high) around a
     *  central water source at the plaza floor. */
    private static void renderFallbackWell(ServerLevel level, BlockPos c) {
        BlockState rim = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        int y = c.getY();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos p = new BlockPos(c.getX() + dx, y, c.getZ() + dz);
                if (dx == 0 && dz == 0) {
                    level.setBlockAndUpdate(p, water);
                } else {
                    level.setBlockAndUpdate(p, rim);
                    level.setBlockAndUpdate(p.above(), rim);
                }
            }
        }
    }

    // =========================================================================
    // GARDEN — procedural greenery on the paving (flower bed or low hedge)
    // =========================================================================

    private static boolean renderGarden(ServerLevel level, PlazaRegion region,
                                        PlazaPiece piece, Random rng) {
        int r = piece.radius();
        BlockPos c = piece.pos();
        int floorY = region.floorY();
        boolean hedge = rng.nextInt(3) == 0;   // ~1/3 hedge, ~2/3 flower bed
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, Boolean.TRUE);
        boolean drew = false;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                int x = c.getX() + dx, z = c.getZ() + dz;
                // Stay inside the plaza polygon (don't spill onto buildings/road).
                if (!Polygon.contains(region.footprint(), x, z)) continue;
                // Soil replaces the paving block (at floorY-1); plant sits at floorY.
                level.setBlock(new BlockPos(x, floorY - 1, z), grass, 3);
                BlockPos above = new BlockPos(x, floorY, z);
                level.setBlock(above, hedge ? leaves : randomFlower(rng), 3);
                drew = true;
            }
        }
        return drew;
    }

    private static BlockState randomFlower(Random rng) {
        return switch (rng.nextInt(6)) {
            case 0 -> Blocks.POPPY.defaultBlockState();
            case 1 -> Blocks.DANDELION.defaultBlockState();
            case 2 -> Blocks.CORNFLOWER.defaultBlockState();
            case 3 -> Blocks.OXEYE_DAISY.defaultBlockState();
            case 4 -> Blocks.AZURE_BLUET.defaultBlockState();
            default -> Blocks.SHORT_GRASS.defaultBlockState();
        };
    }

    // =========================================================================
    // NBT loading (mirrors DecorationPass.stamp)
    // =========================================================================

    private static StructureTemplate loadTemplate(ServerLevel level, String path) {
        Identifier res = Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, path);
        var opt = level.getServer().getResourceManager().getResource(res);
        if (opt.isEmpty()) {
            LOGGER.warn("PlazaPieceRenderer: NBT not found at {} — using fallback well", res);
            return null;
        }
        try {
            StructureTemplate template = new StructureTemplate();
            try (var stream = opt.get().open()) {
                CompoundTag tag = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
                template.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
            }
            return template;
        } catch (Exception e) {
            LOGGER.error("PlazaPieceRenderer: failed to load {}: {}", res, e.getMessage());
            return null;
        }
    }
}
