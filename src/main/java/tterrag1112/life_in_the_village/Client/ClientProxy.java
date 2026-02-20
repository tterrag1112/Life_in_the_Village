package tterrag1112.life_in_the_village.Client;

import net.minecraft.client.Minecraft;
import tterrag1112.life_in_the_village.Gui.VillageMapScreen;

public class ClientProxy {
    public static void openVillageMap() {
        Minecraft.getInstance().setScreen(new VillageMapScreen());
    }
}
