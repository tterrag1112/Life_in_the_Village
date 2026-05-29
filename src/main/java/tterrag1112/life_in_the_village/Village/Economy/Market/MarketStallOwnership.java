package tterrag1112.life_in_the_village.Village.Economy.Market;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Village.Buildings.DynamicSignUpdater;

import java.util.List;
import java.util.UUID;

/**
 * The single funnel for market-stall ownership changes + the stall sign
 * lifecycle (merchant arc Phase 2c). All owner transitions — claim,
 * transfer, vacate — go through {@link #assign} / {@link #vacate}, each of
 * which rewrites the stall sign from the resulting state. Because the sign
 * is rewritten in the same call that mutates ownership, it can no longer
 * drift out of sync (the old failure where signs "didn't always update").
 *
 * <p>Sign text is derived from the stall record: an owned stall shows the
 * owner + rented/purchased; a {@linkplain MarketStall#isVacant() vacant}
 * stall shows "For Rent".
 */
public final class MarketStallOwnership {

    /** Cubic scan extent (blocks) around the stall origin used to find the
     *  stall's sign(s). Stalls are small; a small box suffices. */
    private static final int SIGN_SCAN = 5;

    private MarketStallOwnership() {}

    /** Assigns an owner to a stall and rewrites its sign. */
    public static void assign(ServerLevel level, MarketStall stall,
                              UUID ownerUUID, MarketStall.OwnerType ownerType,
                              String displayName, long rentUntilTick) {
        if (stall == null) return;
        stall.setOwner(ownerUUID, ownerType);
        stall.setOwnerDisplayName(displayName == null ? "" : displayName);
        stall.setRentPaidUntilTick(rentUntilTick);
        refreshSign(level, stall);
    }

    /** Returns a stall to the vacant ("for rent") state and rewrites its sign. */
    public static void vacate(ServerLevel level, MarketStall stall) {
        if (stall == null) return;
        stall.setOwner(MarketStall.VACANT_UUID, MarketStall.OwnerType.NPC);
        stall.setOwnerDisplayName("");
        refreshSign(level, stall);
    }

    /** Rewrites the stall's sign(s) from its current state. */
    public static void refreshSign(ServerLevel level, MarketStall stall) {
        if (level == null || stall == null) return;
        BlockPos origin = stall.getStallOrigin();
        if (origin == null) return;
        Vec3i size = new Vec3i(SIGN_SCAN, SIGN_SCAN, SIGN_SCAN);
        DynamicSignUpdater.updateSigns(level, origin, size, signLines(stall));
    }

    private static List<Component> signLines(MarketStall stall) {
        int slot = stall.getSlotIndex() + 1;
        if (stall.isVacant() || stall.getOwnerDisplayName().isEmpty()) {
            return List.of(
                    Component.literal("Stall #" + slot).withStyle(ChatFormatting.DARK_GREEN),
                    Component.literal("For Rent").withStyle(ChatFormatting.YELLOW),
                    Component.literal(MarketStallPlacer.RENT_PER_DAY + "b/day")
                            .withStyle(ChatFormatting.GRAY),
                    Component.empty());
        }
        String tenure = stall.isPurchased() ? "Purchased" : "Rented";
        return List.of(
                Component.literal("Stall #" + slot).withStyle(ChatFormatting.DARK_GREEN),
                Component.literal(stall.getOwnerDisplayName()).withStyle(ChatFormatting.WHITE),
                Component.literal(tenure).withStyle(ChatFormatting.GRAY),
                Component.empty());
    }
}
