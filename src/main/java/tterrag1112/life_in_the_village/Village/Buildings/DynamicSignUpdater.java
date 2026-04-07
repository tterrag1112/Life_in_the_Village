package tterrag1112.life_in_the_village.Village.Buildings;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for writing dynamic text onto sign blocks inside a building
 * or structure region.
 *
 * <h3>Usage</h3>
 * <pre>
 * DynamicSignUpdater.updateSigns(level, origin, size,
 *     List.of(
 *         Component.literal("John Smith"),
 *         Component.literal("Stall #1"),
 *         Component.literal("Blacksmith")
 *     ));
 * </pre>
 *
 * <p>Lines beyond the four available on a sign are silently ignored.
 * Signs beyond the first found in the region each get the same text —
 * if you need different signs to show different content, place only one
 * sign in each stall NBT.</p>
 *
 * <h3>General applicability</h3>
 * This works for any structure region — not just market stalls. Inn rooms,
 * job boards, guard posts, and any other building can use it.
 */
public final class DynamicSignUpdater {

    private DynamicSignUpdater() {}

    /**
     * Scans the box defined by {@code origin} + {@code size} for sign
     * block entities and writes {@code lines} to the front face of each.
     *
     * @param level  server level
     * @param origin minimum corner of the region (matches NBT placement origin)
     * @param size   size of the structure template (from {@code template.getSize()})
     * @param lines  up to 4 text lines; shorter lists leave remaining lines blank
     */
    public static void updateSigns(ServerLevel level,
                                   BlockPos origin,
                                   net.minecraft.core.Vec3i size,
                                   List<Component> lines) {
        BlockPos max = origin.offset(size.getX(), size.getY(), size.getZ());

        for (BlockPos pos : BlockPos.betweenClosed(origin, max)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof SignBlockEntity sign)) continue;

            writeLines(level, sign, pos, lines);
        }
    }

    /**
     * Convenience overload that takes an explicit min/max rather than
     * an origin + size. Use when you already have a building's
     * {@link tterrag1112.life_in_the_village.Village.Building.BuildingShape}.
     */
    public static void updateSignsInRegion(ServerLevel level,
                                           BlockPos min,
                                           BlockPos max,
                                           List<Component> lines) {
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof SignBlockEntity sign)) continue;

            writeLines(level, sign, pos, lines);
        }
    }

    /**
     * Clears all text on every sign in the region (writes empty lines).
     */
    public static void clearSigns(ServerLevel level,
                                  BlockPos origin,
                                  net.minecraft.core.Vec3i size) {
        updateSigns(level, origin, size, List.of());
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private static void writeLines(ServerLevel level,
                                   SignBlockEntity sign,
                                   BlockPos pos,
                                   List<Component> lines) {
        // Pad to 4 lines
        List<Component> padded = new ArrayList<>(lines);
        while (padded.size() < 4) padded.add(Component.empty());

        SignText text = sign.getFrontText();
        for (int i = 0; i < 4; i++) {
            text = text.setMessage(i, padded.get(i));
        }

        sign.setText(text, true /* front */);
        sign.setChanged();

        // Notify clients
        level.sendBlockUpdated(pos,
                level.getBlockState(pos),
                level.getBlockState(pos),
                3);
    }
}