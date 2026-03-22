package tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.CombatRole;

public class AdventurerEquipment {


    public static void applyRoleEquipment(TownspersonMob npc,
                                          CombatRole role,
                                          boolean elite) {
        net.minecraft.world.item.Item chestItem =
                elite ? Items.DIAMOND_CHESTPLATE : Items.IRON_CHESTPLATE;
        net.minecraft.world.item.Item legItem =
                elite ? Items.DIAMOND_LEGGINGS : Items.IRON_LEGGINGS;
        net.minecraft.world.item.Item bootItem =
                elite ? Items.DIAMOND_BOOTS : Items.IRON_BOOTS;

        switch (role) {
            case SWORDSMAN -> {
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                        new ItemStack(elite ? Items.DIAMOND_SWORD
                                : Items.IRON_SWORD));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                        new ItemStack(elite ? Items.DIAMOND_HELMET
                                : Items.IRON_HELMET));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,
                        new ItemStack(chestItem));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS,
                        new ItemStack(legItem));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,
                        new ItemStack(bootItem));
            }
            case ARCHER -> {
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                        new ItemStack(Items.BOW));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                        new ItemStack(Items.LEATHER_HELMET));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,
                        new ItemStack(Items.LEATHER_CHESTPLATE));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS,
                        new ItemStack(Items.CHAINMAIL_LEGGINGS));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,
                        new ItemStack(Items.LEATHER_BOOTS));
            }
            case MAGE -> {
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                        new ItemStack(Items.BLAZE_ROD));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                        new ItemStack(Items.LEATHER_HELMET));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,
                        new ItemStack(Items.LEATHER_CHESTPLATE));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS,
                        new ItemStack(Items.LEATHER_LEGGINGS));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,
                        new ItemStack(Items.LEATHER_BOOTS));
            }
            case HEALER -> {
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                        new ItemStack(Items.SPLASH_POTION));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                        new ItemStack(Items.CHAINMAIL_HELMET));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,
                        new ItemStack(Items.CHAINMAIL_CHESTPLATE));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS,
                        new ItemStack(Items.CHAINMAIL_LEGGINGS));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,
                        new ItemStack(Items.CHAINMAIL_BOOTS));
            }
            case SCOUT -> {
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                        new ItemStack(elite ? Items.STONE_AXE
                                : Items.STONE_AXE));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                        new ItemStack(Items.LEATHER_HELMET));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,
                        new ItemStack(Items.CHAINMAIL_CHESTPLATE));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS,
                        new ItemStack(Items.LEATHER_LEGGINGS));
                npc.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,
                        new ItemStack(Items.LEATHER_BOOTS));
            }
        }
    }
}
