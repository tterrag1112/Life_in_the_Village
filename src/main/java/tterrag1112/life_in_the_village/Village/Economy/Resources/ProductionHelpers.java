// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Production/ProductionHelpers.java
package tterrag1112.life_in_the_village.Village.Economy.Resources;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.Optional;
import java.util.Set;

/**
 * Shared static helpers for profession production goals.
 *
 * <h3>Scope</h3>
 * Anything that would otherwise be copy-pasted between {@code FarmerGoal},
 * {@code BlacksmithGoal}, {@code CarpenterGoal}, {@code MinerGoal}, etc.
 * belongs here. Concrete goals should delegate to these methods instead
 * of reimplementing them.
 *
 * <h3>Categories</h3>
 * <ul>
 *   <li><b>Building resolution</b> — find assigned / village buildings</li>
 *   <li><b>Workstation discovery</b> — find blocks inside buildings</li>
 *   <li><b>Movement</b> — walk-to-or-arrived helper</li>
 *   <li><b>Inventory</b> — count / remove / check fullness</li>
 *   <li><b>Storage</b> — count / take / deposit building storage</li>
 *   <li><b>Market</b> — find market / merchant in the NPC's village</li>
 *   <li><b>Effects</b> — swing / sound helpers</li>
 * </ul>
 */
public final class ProductionHelpers {

    private ProductionHelpers() {}

    // =========================================================================
    // Building resolution
    // =========================================================================

    /** Returns the NPC's assigned building if it matches the required type. */
    public static Optional<Building> findAssignedBuilding(TownspersonMob npc,
                                                          ServerLevel level,
                                                          BuildingType type) {
        VillageSavedData data = VillageSavedData.get(level);
        return npc.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType() == type);
    }

    /** Find the first building of the given type in the NPC's village. */
    public static Optional<Building> findVillageBuilding(TownspersonMob npc,
                                                         ServerLevel level,
                                                         BuildingType type) {
        VillageSavedData data = VillageSavedData.get(level);
        return npc.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .flatMap(v -> v.getBuildingIds().stream()
                        .map(data::getBuildingById)
                        .filter(Optional::isPresent).map(Optional::get)
                        .filter(b -> b.getType() == type)
                        .findFirst());
    }

    // =========================================================================
    // Workstation discovery
    // =========================================================================

    /** Finds the first block inside the building matching the given class. */
    public static Optional<BlockPos> findBlockInBuilding(ServerLevel level,
                                                         Building building,
                                                         Class<? extends Block> blockClass) {
        if (building == null) return Optional.empty();
        BlockPos min = building.getShape().getMin();
        BlockPos max = building.getShape().getMax();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (blockClass.isInstance(level.getBlockState(pos).getBlock())) {
                return Optional.of(pos.immutable());
            }
        }
        return Optional.empty();
    }

    // =========================================================================
    // Movement
    // =========================================================================

    /**
     * Walks the NPC towards {@code target}. Returns true if the NPC is
     * already within {@code rangeSq} of the target (in range), false
     * otherwise (still walking).
     */
    public static boolean moveToOrArrived(TownspersonMob npc,
                                          BlockPos target, double rangeSq) {
        if (target == null) return false;
        double distSq = npc.distanceToSqr(
                target.getX(), target.getY(), target.getZ());
        if (distSq <= rangeSq) {
            npc.getNavigation().stop();
            return true;
        }
        if (!npc.getNavigation().isInProgress()) {
            npc.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 1.0);
        }
        return false;
    }

    // =========================================================================
    // Inventory
    // =========================================================================

    public static int countInInventory(TownspersonMob npc, Item item) {
        SimpleContainer inv = npc.getPersonalInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    /** Removes up to {@code count} of {@code item} from the NPC's inventory. */
    public static int removeFromInventory(TownspersonMob npc, Item item, int count) {
        SimpleContainer inv = npc.getPersonalInventory();
        int remaining = count;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.is(item)) continue;
            int take = Math.min(stack.getCount(), remaining);
            stack.shrink(take);
            remaining -= take;
            if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
        }
        return count - remaining;
    }

    /** True if fewer than {@code minEmptySlots} slots are empty. */
    public static boolean isInventoryNearlyFull(TownspersonMob npc, int minEmptySlots) {
        SimpleContainer inv = npc.getPersonalInventory();
        int empty = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) empty++;
        }
        return empty < minEmptySlots;
    }

    // =========================================================================
    // Building storage
    // =========================================================================

    public static int countInBuilding(ServerLevel level, Building building, Item item) {
        return BuildingStorageAccess.countItem(level, building, item);
    }

    public static int countInBuildingAndInventory(ServerLevel level,
                                                  Building building,
                                                  TownspersonMob npc,
                                                  Item item) {
        return countInBuilding(level, building, item) + countInInventory(npc, item);
    }

    public static boolean takeFromBuilding(ServerLevel level,
                                           Building building,
                                           Item item, int count) {
        return BuildingStorageAccess.takeItem(level, building, item, count);
    }

    /**
     * Deposits the NPC's entire personal inventory into the building,
     * skipping any items in {@code exclude} (e.g. tools the NPC should keep).
     */
    public static void depositAllToBuilding(ServerLevel level,
                                            Building building,
                                            TownspersonMob npc,
                                            Set<Item> exclude) {
        SimpleContainer inv = npc.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (exclude.contains(stack.getItem())) continue;
            if (BuildingStorageAccess.storeItem(level, building, stack.copy())) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    // =========================================================================
    // Market / merchant lookup
    // =========================================================================

    public static Optional<Building> findMarketInVillage(TownspersonMob npc,
                                                         ServerLevel level) {
        return findVillageBuilding(npc, level, BuildingType.MARKET);
    }

    public static Optional<TownspersonMob> findMerchantAt(ServerLevel level,
                                                          Building market) {
        if (market == null) return Optional.empty();
        return level.getEntitiesOfClass(
                TownspersonMob.class,
                market.getShape().toAABB().inflate(16),
                mob -> mob.getProfession() == Profession.MERCHANT
        ).stream().findFirst().map(m -> (TownspersonMob) m);
    }

    // =========================================================================
    // Work effects
    // =========================================================================

    /** Standard work swing + sound pulse. Call every N ticks during WORKING phase. */
    public static void performWorkPulse(TownspersonMob npc, ServerLevel level,
                                        BlockPos target, SoundEvent sound) {
        if (target != null) {
            npc.getLookControl().setLookAt(
                    target.getX(), target.getY(), target.getZ());
        }
        npc.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, npc.blockPosition(), sound,
                SoundSource.NEUTRAL, 0.5f,
                0.9f + npc.getRandom().nextFloat() * 0.2f);
    }
}