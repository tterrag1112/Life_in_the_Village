package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Doc 15 §"Color system" — placement-time tint pass.
 *
 * <p>Walks the just-stamped footprint and replaces every tintable
 * marker block (per {@link TintBlockTable}) with the same block in
 * the chosen {@link DyeColor} for the building's
 * {@link ColorSlot}. Runs <em>after</em> {@link
 * net.minecraft.world.level.levelgen.structure.templatesystem
 * .StructureTemplate#placeInWorld StructureTemplate.placeInWorld} but
 * <em>before</em> {@code VillageBiomeStyle}'s material substitution —
 * order matters because biome substitution may convert
 * {@code white_terracotta} to a biome-appropriate variant and that
 * would hide it from the tint pass.</p>
 *
 * <p>Blocks not on the tintable list are never touched. {@code
 * light_gray_*} is the documented opt-out (it isn't on the table).</p>
 */
public final class TintPass {

    private TintPass() {}

    /**
     * Plan record passed by the placer. {@code null} on any slot
     * means "skip this slot" — the building's matching colour field
     * stays null and the tint pass leaves those tintable blocks alone.
     */
    public record Plan(@Nullable DyeColor primaryColor,
                       @Nullable DyeColor accentColor,
                       @Nullable DyeColor roofColor) {

        public static final Plan NONE = new Plan(null, null, null);

        public boolean isNoOp() {
            return primaryColor == null
                    && accentColor == null
                    && roofColor == null;
        }

        @Nullable
        public DyeColor forSlot(ColorSlot slot) {
            return switch (slot) {
                case PRIMARY -> primaryColor;
                case ACCENT  -> accentColor;
                case ROOF    -> roofColor;
            };
        }
    }

    /**
     * Iterates the placed footprint and applies the tint plan.
     *
     * <p>Footprint extents are derived from the stamped template's
     * size at the placement {@code rotation}, mirroring the loop
     * shape used by {@code applyBiomeSwap} so the tint pass and the
     * biome swap visit the same world cells.</p>
     *
     * @param level    server level the building is in
     * @param origin   pivot position passed to
     *                 {@code StructureTemplate.placeInWorld} — the same
     *                 pivot the placer uses
     * @param template the just-stamped template (for size lookup)
     * @param rotation rotation applied at placement
     * @param plan     tint plan derived from village palette + variant
     */
    public static void apply(ServerLevel level, BlockPos origin,
                             StructureTemplate template,
                             Rotation rotation, Plan plan) {
        if (plan == null || plan.isNoOp()) return;

        Vec3i size = template.getSize();
        int w, l;
        switch (rotation) {
            case CLOCKWISE_90, COUNTERCLOCKWISE_90 -> { w = size.getZ(); l = size.getX(); }
            default                                -> { w = size.getX(); l = size.getZ(); }
        }
        int h = size.getY();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < w; dx++) {
            for (int dy = 0; dy < h; dy++) {
                for (int dz = 0; dz < l; dz++) {
                    cursor.set(origin.getX() + dx,
                            origin.getY() + dy,
                            origin.getZ() + dz);
                    BlockState current = level.getBlockState(cursor);
                    Block source = current.getBlock();
                    var entryOpt = TintBlockTable.entryFor(source);
                    if (entryOpt.isEmpty()) continue;

                    TintBlockTable.TintEntry entry = entryOpt.get();
                    DyeColor dye = plan.forSlot(entry.slot());
                    if (dye == null) continue;

                    Block replacement = entry.byColor().get(dye);
                    if (replacement == null) continue;
                    level.setBlock(cursor.immutable(),
                            replacement.defaultBlockState(), 3);
                }
            }
        }
    }
}
