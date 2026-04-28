package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.EnumSet;
import java.util.Set;

/**
 * Doc 15 §"Player-requested repaint" — runtime variant of the tint
 * pass for {@link
 * tterrag1112.life_in_the_village.Entities.Goals.Profession.Builder
 * .BuilderRepaintGoal}.
 *
 * <p>Differs from {@link TintPass} (placement-time) in two ways:</p>
 * <ol>
 *   <li>Recognises <em>already-tinted</em> blocks via
 *       {@link TintBlockTable#entryForAnyColour}, since a repaint
 *       request rewrites RED → BLUE (not just WHITE → BLUE).</li>
 *   <li>Walks the live {@link Building#getShape()} bounds rather
 *       than a {@code StructureTemplate}.</li>
 * </ol>
 *
 * <p>Per-visit batching is supported via the {@code maxBlocks}
 * parameter — the goal limits each visit to a fraction of the total
 * tintable blocks so the repaint visibly progresses across days.
 * {@code Integer.MAX_VALUE} (or any value ≥ tintable count) paints
 * the whole building in one pass.</p>
 */
public final class RuntimeTintPass {

    private RuntimeTintPass() {}

    /** Result of one paint pass. */
    public record Result(int painted, int remaining) {}

    /**
     * Iterates the building's footprint and replaces tintable blocks
     * for the requested {@code slots} with the colours in
     * {@code plan}. Returns the number of blocks painted this pass
     * and the count still un-painted (still in the old colour).
     *
     * @param level     server level
     * @param building  building whose live footprint we walk
     * @param slots     which colour slots to paint (intersection of
     *                  the variant's {@code colorSlots} and the
     *                  job's requested slots)
     * @param plan      target colours per slot
     * @param maxBlocks soft cap on blocks to paint this pass; pass
     *                  {@link Integer#MAX_VALUE} for "no cap"
     */
    public static Result apply(ServerLevel level, Building building,
                               Set<ColorSlot> slots,
                               TintPass.Plan plan,
                               int maxBlocks) {
        if (level == null || building == null
                || slots == null || slots.isEmpty()
                || plan == null || plan.isNoOp()) {
            return new Result(0, 0);
        }
        if (maxBlocks <= 0) maxBlocks = Integer.MAX_VALUE;

        Building.BuildingShape shape = building.getShape();
        BlockPos min = shape.getMin();
        BlockPos max = shape.getMax();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int painted = 0;
        int remaining = 0;
        Set<ColorSlot> activeSlots = EnumSet.copyOf(slots);

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    cursor.set(x, y, z);
                    BlockState current = level.getBlockState(cursor);
                    Block source = current.getBlock();
                    var entryOpt = TintBlockTable.entryForAnyColour(source);
                    if (entryOpt.isEmpty()) continue;

                    TintBlockTable.TintEntry entry = entryOpt.get();
                    if (!activeSlots.contains(entry.slot())) continue;

                    @Nullable DyeColor target = plan.forSlot(entry.slot());
                    if (target == null) continue;

                    Block replacement = entry.byColor().get(target);
                    if (replacement == null || replacement == source) continue;

                    if (painted >= maxBlocks) {
                        remaining++;
                        continue;
                    }
                    level.setBlock(cursor.immutable(),
                            replacement.defaultBlockState(), 3);
                    painted++;
                }
            }
        }
        return new Result(painted, remaining);
    }
}
