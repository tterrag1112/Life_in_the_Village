package tterrag1112.life_in_the_village.Village.Markets.Complex.Render;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Cultures.CultureRegistry;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Farms.Complex.Render.PathRenderer;
import tterrag1112.life_in_the_village.Village.Markets.Complex.MarketComplexPlanner;

/**
 * Renders a planned market complex as a flat prepared pad (merchant arc
 * Phase 2a). Grades the claimed region to the plan's {@code padY},
 * surfaces it with the culture path palette (or the spec's block
 * override), and knocks out the market building footprint so the
 * building is untouched.
 *
 * <p>Reuses {@link PathRenderer#resolveRoadMaterial} for the palette —
 * the same culture-path block the village roads use, so the plaza reads
 * as continuous with the road network. Fully defensive against null /
 * partial plan data (mirrors {@code FarmComplexRenderer}).
 */
public final class MarketComplexRenderer {

    /** Air cleared above the pad surface (cut leftover vegetation /
     *  low terrain bumps so the pad reads flat). */
    private static final int HEAD_CLEARANCE = 5;
    /** Max blocks of dirt fill below the pad surface to close a dip so
     *  the pad isn't a floating shelf over lower ground. */
    private static final int FILL_DEPTH = 4;

    private MarketComplexRenderer() {}

    public static void render(MarketComplexPlanner.PlanResult plan,
                              String culture, ServerLevel level) {
        if (plan == null || level == null || !plan.success()) return;
        Polygon region = plan.region();
        if (region == null || region.vertices().size() < 3) return;
        Polygon buildingBounds = plan.buildingBounds(); // nullable-safe below

        String blockId = plan.padBlockId() != null
                ? plan.padBlockId()
                : CultureRegistry.getOrDefault(culture).planningBias().roadMaterial();
        BlockState surface = PathRenderer.resolveRoadMaterial(blockId);

        int padY = plan.padY();
        Polygon.AABB bb = Polygon.boundingBox(region);
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                if (!Polygon.contains(region, x, z)) continue;
                // Knock out the building footprint — never grade the
                // building itself.
                if (buildingBounds != null && Polygon.contains(buildingBounds, x, z)) {
                    continue;
                }
                gradeColumn(level, x, z, padY, surface);
            }
        }
    }

    /** Grade one column flat to {@code padY}: surface block at padY,
     *  air above, dirt fill below any dip. */
    private static void gradeColumn(ServerLevel level, int x, int z,
                                    int padY, BlockState surface) {
        if (padY <= level.getMinY() + 1) return;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // Surface.
        cursor.set(x, padY, z);
        level.setBlock(cursor, surface, 2);

        // Cut anything above the surface (vegetation, terrain bumps).
        for (int dy = 1; dy <= HEAD_CLEARANCE; dy++) {
            cursor.set(x, padY + dy, z);
            if (!level.getBlockState(cursor).isAir()) {
                level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
            }
        }

        // Fill a dip below so the pad isn't a floating shelf. Stop at the
        // first solid block.
        for (int dy = 1; dy <= FILL_DEPTH; dy++) {
            cursor.set(x, padY - dy, z);
            BlockState below = level.getBlockState(cursor);
            if (below.isAir() || below.canBeReplaced()) {
                level.setBlock(cursor, Blocks.DIRT.defaultBlockState(), 2);
            } else {
                break;
            }
        }
    }
}
