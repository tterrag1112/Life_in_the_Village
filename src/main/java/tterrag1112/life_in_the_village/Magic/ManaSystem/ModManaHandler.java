package tterrag1112.life_in_the_village.Magic.ManaSystem;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Networking.ManaData;

public class ModManaHandler {
    public static final int PLAYER_MANA = 0;
    public static final int DEATH_MANA_PENALTY_PERCENTAGE = 8;
    public static final int MANA_REGEN_RATE = 10;

    public static void setManaForPlayer(Player player, int value) {
        player.setData(ModData.MANA, value);
        PacketDistributor.sendToPlayer(((ServerPlayer) player), new ManaData(0, value));
    }

    public static void increaseManaForPlayer(Player player, int value) {
        int newValue = player.getData(ModData.MANA) + value;
        player.setData(ModData.MANA, newValue);
        PacketDistributor.sendToPlayer(((ServerPlayer) player), new ManaData(0, newValue));
    }

    public static void decreaseManaForPlayer(Player player, int value) {
        int newValue = player.getData(ModData.MANA) - value;
        player.setData(ModData.MANA, newValue);
        PacketDistributor.sendToPlayer(((ServerPlayer) player), new ManaData(0, newValue));
    }








}
