package tterrag1112.life_in_the_village.Entities.Goals;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Village;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class GuardEquipmentGoal extends Goal {

    private final TownspersonMob entity;
    private int checkTimer = 0;
    private static final int CHECK_INTERVAL = 200;

    // Armor slot order for evaluation
    private static final List<EquipmentSlot> ARMOR_SLOTS = List.of(
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    );

    // Armor tier ranking for fallback selection
    private static final Map<String, Integer> ARMOR_TIER = Map.of(
            "leather", 1, "chainmail", 2, "iron", 3,
            "golden", 4, "diamond", 5, "netherite", 6
    );

    private static final List<String> WEAPON_PRIORITY = List.of(
            "netherite_sword", "diamond_sword", "iron_sword",
            "stone_sword", "golden_sword", "wooden_sword",
            "netherite_axe", "diamond_axe", "iron_axe"
    );

    public GuardEquipmentGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.noneOf(Flag.class));

    }

    @Override
    public boolean canUse() {
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return true;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
    }

    @Override
    public void tick() {
        //System.out.println("GuardEquipmentGoal ticking: " + checkTimer);
        if (!(entity.level() instanceof ServerLevel serverLevel)) return;
        checkTimer++;
        if (checkTimer >= CHECK_INTERVAL) {
            checkTimer = 0;
            updateEquipment(serverLevel);
        }
    }

    private void updateEquipment(ServerLevel level) {
        Optional<Building> building = entity.getAssignedBuilding(level);

        if (building.isEmpty()) return;

        List<Container> inventories = BuildingStorageAccess.findInventories(level, building.get());

        Optional<Village> village = entity.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name));

        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack current = entity.getItemBySlot(slot);
            ItemStack best = findBestArmor(level, building.get(), slot, village.orElse(null));

            if (best.isEmpty()) continue;
            if (current.isEmpty() || getArmorTier(best) > getArmorTier(current)) {
                if (!current.isEmpty()) {
                    BuildingStorageAccess.storeItem(level, building.get(), current.copy());
                }
                BuildingStorageAccess.takeItem(level, building.get(), best.getItem(), 1);
                entity.setItemSlot(slot, best);
            }
        }
        updateWeapon(level, building.get());

    }

    private ItemStack findBestArmor(ServerLevel level, Building building,
                                    EquipmentSlot slot, @Nullable Village village) {
        if (village != null) {
            Optional<Item> preferred = village.getPreferredArmorPiece(slot);
            if (preferred.isPresent()) {
                if (BuildingStorageAccess.hasItem(level, building, preferred.get(), 1)) {
                    return new ItemStack(preferred.get());
                }
            }
        }

        ItemStack best = ItemStack.EMPTY;
        for (var container : BuildingStorageAccess.findInventories(level, building)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!isArmorForSlot(stack, slot)) continue;
                if (best.isEmpty() || getArmorTier(stack) > getArmorTier(best)) {
                    best = stack.copy();
                }
            }
        }
        return best;
    }
    private boolean isArmorForSlot(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) return false;
        String itemName = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return switch (slot) {
            case HEAD -> itemName.endsWith("_helmet");
            case CHEST -> itemName.endsWith("_chestplate");
            case LEGS -> itemName.endsWith("_leggings");
            case FEET -> itemName.endsWith("_boots");
            default -> false;
        };
    }

    private int getArmorTier(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        for (Map.Entry<String, Integer> entry : ARMOR_TIER.entrySet()) {
            if (name.startsWith(entry.getKey())) return entry.getValue();
        }
        return 1;
    }

    private void updateWeapon(ServerLevel level, Building building) {
        ItemStack currentWeapon = entity.getMainHandItem();

        // Find best weapon in storage by priority list
        for (String weaponName : WEAPON_PRIORITY) {
            Item weapon = BuiltInRegistries.ITEM.get(
                    Identifier.withDefaultNamespace(weaponName)
            ).get().value();
            if (weapon == Items.AIR) continue;

            if (BuildingStorageAccess.hasItem(level, building, weapon, 1)) {
                // Only upgrade if better than current
                int currentTier = getWeaponTier(currentWeapon);
                int newTier = WEAPON_PRIORITY.indexOf(weaponName);

                if (currentWeapon.isEmpty() || newTier < currentTier) {
                    // Store current weapon back
                    if (!currentWeapon.isEmpty()) {
                        BuildingStorageAccess.storeItem(level, building, currentWeapon.copy());
                    }
                    BuildingStorageAccess.takeItem(level, building, weapon, 1);
                    entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(weapon));
                    return;
                }
                break;
            }
        }
    }

    private int getWeaponTier(ItemStack stack) {
        if (stack.isEmpty()) return WEAPON_PRIORITY.size();
        String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        int index = WEAPON_PRIORITY.indexOf(name);
        return index == -1 ? WEAPON_PRIORITY.size() : index;
    }
}
