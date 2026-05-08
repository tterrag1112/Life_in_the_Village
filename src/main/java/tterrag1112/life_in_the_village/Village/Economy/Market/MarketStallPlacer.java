package tterrag1112.life_in_the_village.Village.Economy.Market;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
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

    /** Stall structure template path. */
    private static final Identifier STALL_TEMPLATE =
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID,
                    "default/market/stall/stall_1");

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
     * Claims the next free anchor slot in the given market building.
     * Places the stall NBT at that position, detects the chest inside it,
     * and returns a populated {@link MarketStall} ready to be persisted.
     *
     * @param rentUntilTick pass {@code Long.MAX_VALUE} for a purchase
     * @return empty if no free slots exist or the stall template is missing
     */
    public static Optional<MarketStall> claimSlot(
            ServerLevel level,
            Building marketBuilding,
            UUID ownerUUID,
            MarketStall.OwnerType ownerType,
            long rentUntilTick,
            VillageSavedData data) {

        List<BlockPos> allAnchors = findAnchorSlots(marketBuilding, data);
        if (allAnchors.isEmpty()) return Optional.empty();

        // Find which slot indices are already occupied
        Set<Integer> occupied = new HashSet<>();
        data.getStallsForMarket(marketBuilding.getId())
                .forEach(s -> { if (s.isActive()) occupied.add(s.getSlotIndex()); });

        // Pick the first free slot
        int freeSlot = -1;
        BlockPos anchorPos = null;
        for (int i = 0; i < allAnchors.size(); i++) {
            if (!occupied.contains(i)) {
                freeSlot  = i;
                anchorPos = allAnchors.get(i);
                break;
            }
        }

        if (freeSlot < 0 || anchorPos == null) return Optional.empty(); // market full

        // Place the stall NBT at the anchor position
        Optional<StructureTemplate> templateOpt =
                BuildingPlacer.loadTemplate(level, STALL_TEMPLATE);
        if (templateOpt.isEmpty()) {
            System.err.println("[MarketStallPlacer] Stall template not found: "
                    + STALL_TEMPLATE);
            return Optional.empty();
        }

        // P0d-04: anchor block has already been replaced with air by
        // SubBuildingScanner at building-placement time, so no extra
        // clearing is required before stamping.

        Rotation stallRotation = facingRotation(anchorPos, marketBuilding);

        StructureTemplate template = templateOpt.get();
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(stallRotation)
                .setIgnoreEntities(false);

        template.placeInWorld(level, anchorPos, BlockPos.ZERO, settings,
                level.getRandom(), 2);
        String displayName = resolveOwnerName(level, ownerUUID, ownerType);


        // Create the stall record
        MarketStall stall = MarketStall.create(
                marketBuilding.getId(),
                freeSlot,
                anchorPos,
                ownerUUID,
                displayName,
                ownerType,
                rentUntilTick);

        // Scan for a chest inside the stall footprint
        net.minecraft.core.Vec3i size = template.getSize();
        BlockPos chestPos = findChestInRegion(level, anchorPos,
                anchorPos.offset(size.getX(), size.getY(), size.getZ()));
        if (chestPos != null) {
            stall.setChestPos(chestPos);
        }
        String ownerName = resolveOwnerName(level, ownerUUID, ownerType);
        updateStallSigns(level, anchorPos, template.getSize(),
                stall.getSlotIndex(), stall, rentUntilTick);

        System.out.println("[MarketStallPlacer] Placed stall slot " + freeSlot
                + " at " + anchorPos + " for owner " + ownerUUID
                + " (chest: " + chestPos + ")");

        return Optional.of(stall);
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

    private static BlockPos findChestInRegion(ServerLevel level,
                                              BlockPos min, BlockPos max) {
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof ChestBlockEntity) return pos;
                }
            }
        }
        return null;
    }
    public static void assignGoalIfNpc(ServerLevel level, MarketStall stall) {
        if (stall.getOwnerType() != MarketStall.OwnerType.NPC) return;

        level.getEntitiesOfClass(
                        tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                        new net.minecraft.world.phys.AABB(stall.getStallOrigin()).inflate(128),
                        mob -> mob.getUUID().equals(stall.getOwnerUUID()))
                .stream().findFirst().ifPresent(npc -> {
                    // Remove any existing StallKeeperGoal first
                    npc.goalSelector.removeAllGoals(g ->
                            g instanceof tterrag1112.life_in_the_village
                                    .Entities.Goals.Profession.Merchant.StallKeeperGoal);
                    npc.goalSelector.addGoal(
                            tterrag1112.life_in_the_village.Entities.Goals
                                    .Profession.ProfessionGoalFactory.P_WORK_PRIMARY,
                            new tterrag1112.life_in_the_village.Entities.Goals
                                    .Profession.Merchant.StallKeeperGoal(npc, stall));
                });
    }
    /**
     * Returns the Rotation that makes a stall placed at {@code anchorPos}
     * face toward the market building's centre.
     *
     * <h3>Convention</h3>
     * The stall NBT is authored facing SOUTH (i.e. Rotation.NONE = facing south,
     * the "front" of the stall opens toward positive Z). The four rotations map to:
     * <pre>
     *   NONE              → faces south  (+Z)
     *   CLOCKWISE_90      → faces west   (-X)
     *   CLOCKWISE_180     → faces north  (-Z)
     *   COUNTERCLOCKWISE_90 → faces east (+X)
     * </pre>
     * Adjust the base direction below if your stall NBT is authored facing
     * a different direction.
     */
    private static Rotation facingRotation(BlockPos anchorPos,
                                           Building marketBuilding) {
        // Market centre in world space
        net.minecraft.world.phys.AABB bounds = marketBuilding.getShape().toAABB();
        double centerX = bounds.getCenter().x;
        double centerZ = bounds.getCenter().z;

        double dx = centerX - (anchorPos.getX() + 0.5);
        double dz = centerZ - (anchorPos.getZ() + 0.5);

        // Dominant axis determines which way to face
        if (Math.abs(dx) >= Math.abs(dz)) {
            // Stall is to the east or west of centre
            return dx > 0
                    ? Rotation.COUNTERCLOCKWISE_90  // anchor is west  → face east  (+X)
                    : Rotation.CLOCKWISE_90;         // anchor is east  → face west  (-X)
        } else {
            // Stall is to the north or south of centre
            return dz > 0
                    ? Rotation.NONE                  // anchor is north → face south (+Z)
                    : Rotation.CLOCKWISE_180;        // anchor is south → face north (-Z)
        }
    }
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