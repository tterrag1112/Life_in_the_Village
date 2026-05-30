package tterrag1112.life_in_the_village.Village.Economy.Market;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingPlacer;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.DynamicSignUpdater;
import tterrag1112.life_in_the_village.Village.Decoration.Subbuilding.SubBuilding;
import tterrag1112.life_in_the_village.Village.Decoration.Subbuilding.SubBuildingType;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the physical placement of market stall NBT structures.
 *
 * <h3>Anchor convention (Track B1, P0d-04)</h3>
 * Market NBTs author {@code SubBuildingAnchorBlock} instances tagged
 * {@link SubBuildingType#STALL} at each stall position. The
 * {@code SubBuildingScanner} runs at building-placement time, replaces
 * each anchor with air, and registers a {@link SubBuilding} record on
 * {@code VillageSavedData}. This class then queries those records;
 * the runtime scan for chiseled stone bricks that the legacy P0d-04-pre
 * code used has been removed.
 *
 * <p>Slot numbering is the deterministic sort order of the stall
 * subbuildings by their anchor (Z, X). A stall record's
 * {@code slotIndex} is its position in that sorted list.
 *
 * <h3>Chest detection</h3>
 * After placing a stall NBT, this class scans the stall footprint for a
 * Chest block and records its position in the {@link MarketStall} record so
 * that {@code TradeHandler} can route payments correctly.
 *
 * <h3>Usage</h3>
 * <pre>
 * // When a seller claims a slot:
 * Optional<MarketStall> stall = MarketStallPlacer.claimSlot(
 *         level, marketBuilding, ownerUUID, ownerType,
 *         rentUntilTick, data);
 * stall.ifPresent(s -> data.addMarketStall(s));
 * </pre>
 */
public final class MarketStallPlacer {

    /** Stall structure template path. Phase 2b: corrected to the real
     *  resource location (the legacy value omitted the {@code /rural/}
     *  style segment, so {@code loadTemplate} always failed). Used by
     *  {@link #reclaimStall} for sizing; placement now goes through the
     *  pad allocator ({@code StallAllocator}). */
    private static final Identifier STALL_TEMPLATE =
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID,
                    "default/rural/market/stall/stall_1");

    /** Daily rent cost in bronze per stall. */
    public static final long RENT_PER_DAY = 10L;

    /** One-time purchase cost in bronze. */
    public static final long PURCHASE_PRICE = 500L;

    private MarketStallPlacer() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns the anchor positions of every {@link SubBuildingType#STALL}
     * subbuilding registered against this market, sorted by (Z, X) for
     * consistent slot numbering. P0d-04: queries {@code VillageSavedData}
     * for scanner output rather than walking blocks at runtime.
     *
     * <p>Call this once after a market is placed to know how many slots
     * are available, or to display slot availability to a player.</p>
     */
    public static List<BlockPos> findAnchorSlots(ServerLevel level,
                                                 Building marketBuilding) {
        return findAnchorSlots(marketBuilding,
                VillageSavedData.get(level));
    }

    /**
     * Overload that takes an explicit {@link VillageSavedData}, for
     * callers (V2 spawn adapter, NPC interaction handlers) that already
     * have one.
     */
    public static List<BlockPos> findAnchorSlots(Building marketBuilding,
                                                 VillageSavedData data) {
        if (marketBuilding == null || data == null) return List.of();
        return data.getSubBuildingsForBuilding(marketBuilding.getId()).stream()
                .filter(sb -> sb.type() == SubBuildingType.STALL)
                .map(SubBuilding::origin)
                // Witness <BlockPos> on the first comparator —
                // chain inference trips on Vec3i bridge methods
                // otherwise.
                .sorted(Comparator.<BlockPos>comparingInt(BlockPos::getZ)
                        .thenComparingInt(BlockPos::getX))
                .collect(Collectors.toList());
    }

    /**
     * Claims a free stall in the market. Phase 2b: stalls are seeded onto
     * the market pad as vacant records by {@code MarketStallSeeder}; a
     * claim assigns ownership to the first vacant one. This replaces the
     * old authored-anchor + {@code STALL_TEMPLATE} stamping path (which
     * was broken — the template path didn't exist). The placement geometry
     * + aisle-facing rotation now live in {@code StallAllocator} (run at
     * spawn). Sign rewriting is consolidated in Phase 2c.
     *
     * @param rentUntilTick pass {@code Long.MAX_VALUE} for a purchase
     * @return empty if the market has no free (vacant) stall
     */
    public static Optional<MarketStall> claimSlot(
            ServerLevel level,
            Building marketBuilding,
            UUID ownerUUID,
            MarketStall.OwnerType ownerType,
            long rentUntilTick,
            VillageSavedData data) {

        MarketStall vacant = null;
        for (MarketStall s : data.getStallsForMarket(marketBuilding.getId())) {
            if (s.isActive() && s.isVacant()) { vacant = s; break; }
        }
        if (vacant == null) return Optional.empty(); // market full / none seeded

        String displayName = resolveOwnerName(level, ownerUUID, ownerType);
        // 2c: ownership + sign go through the single funnel so the sign
        // can't desync from the owner.
        MarketStallOwnership.assign(level, vacant, ownerUUID, ownerType,
                displayName, rentUntilTick);
        data.markDirty();

        System.out.println("[MarketStallPlacer] Claimed stall slot "
                + vacant.getSlotIndex() + " for owner " + ownerUUID);
        return Optional.of(vacant);
    }

    /**
     * Reclaims a stall: clears the stall structure from the world and
     * marks the {@link MarketStall} record inactive. P0d-04: the stall
     * slot's {@link SubBuilding} record persists on
     * {@code VillageSavedData} unchanged — the slot becomes available
     * again because no active {@link MarketStall} references its
     * slot index. No anchor block needs to be restored (the legacy
     * chiseled-stone-brick marker has been removed entirely).
     */
    public static void reclaimStall(ServerLevel level, MarketStall stall) {

        Optional<StructureTemplate> templateOpt =
                BuildingPlacer.loadTemplate(level, STALL_TEMPLATE);

        if (templateOpt.isEmpty()) return;

        net.minecraft.core.Vec3i size = templateOpt.get().getSize();
        BlockPos origin = stall.getStallOrigin();

        // Clear the stall footprint
        for (int x = 0; x < size.getX(); x++) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    level.setBlock(origin.offset(x, y, z),
                            Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        // Reset signs to "For Rent"
        updateStallSigns(level, stall.getStallOrigin(),
                size, stall.getSlotIndex(), stall, 0L);

        stall.setActive(false);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    public static void assignGoalIfNpc(ServerLevel level, MarketStall stall) {
        // Phase 6.2.d.5: StallKeeperGoal was removed in the Goal→Brain migration.
        // Stall ownership is now read directly off MarketStall state by
        // MerchantBehavior (WORK @1 on MERCHANT brain); no per-stall goal wiring
        // is required here. Kept as a stub so existing call sites compile.
    }

    // Phase 2b: facingRotation (dominant-axis snap) deleted — stall
    // placement + aisle-facing rotation now live in StallAllocator.

    /**
     * Writes stall ownership info onto any signs within the stall footprint.
     * If {@code ownerName} is null, writes a "For Rent" message instead.
     */
    private static void updateStallSigns(ServerLevel level,
                                         BlockPos origin,
                                         net.minecraft.core.Vec3i size,
                                         int slotIndex,
                                         MarketStall stall,
                                         long rentUntilTick) {
        List<Component> lines;
        String ownerName = (stall != null && !stall.getOwnerDisplayName().isEmpty())
                ? stall.getOwnerDisplayName()
                : null;

        if (ownerName != null) {
            String rentLabel = rentUntilTick == Long.MAX_VALUE
                    ? "Purchased"
                    : "Rented";
            lines = List.of(
                    Component.literal("Stall #" + (slotIndex + 1))
                            .withStyle(ChatFormatting.DARK_GREEN),
                    Component.literal(ownerName)
                            .withStyle(ChatFormatting.WHITE),
                    Component.literal(rentLabel)
                            .withStyle(ChatFormatting.GRAY),
                    Component.empty()
            );
        } else {
            lines = List.of(
                    Component.literal("Stall #" + (slotIndex + 1))
                            .withStyle(ChatFormatting.DARK_GREEN),
                    Component.literal("For Rent")
                            .withStyle(ChatFormatting.YELLOW),
                    Component.literal(RENT_PER_DAY + "b/day")
                            .withStyle(ChatFormatting.GRAY),
                    Component.empty()
            );
        }

        DynamicSignUpdater.updateSigns(level, origin, size, lines);
    }

    /**
     * Resolves a display name for the stall owner.
     * For NPCs, searches loaded entities. For players, searches the online
     * player list. Falls back to a shortened UUID if neither is found.
     */
    private static String resolveOwnerName(ServerLevel level,
                                           java.util.UUID ownerUUID,
                                           MarketStall.OwnerType ownerType) {
        if (ownerType == MarketStall.OwnerType.NPC) {
            return level.getEntitiesOfClass(
                            tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                            new net.minecraft.world.phys.AABB(
                                    BlockPos.ZERO).inflate(30000000),
                            mob -> mob.getUUID().equals(ownerUUID))
                    .stream()
                    .findFirst()
                    .map(mob -> mob.getNpcName())
                    .orElse("NPC");
        }

        if (ownerType == MarketStall.OwnerType.WANDERING_TRADER) {
            return "Travelling Merchant";
        }

        // Player
        var onlinePlayer = level.getServer()
                .getPlayerList().getPlayer(ownerUUID);
        if (onlinePlayer != null) {
            return onlinePlayer.getName().getString();
        }


// Offline player — no profile cache available, use shortened UUID
        return "Player (" + ownerUUID.toString().substring(0, 6) + ")";
    }
}