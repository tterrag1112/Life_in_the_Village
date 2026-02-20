package tterrag1112.life_in_the_village.Networking;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.DataAttachments.ModData;

public class ServerPayloadHandler {
    // HERE WE ARE ON THE CLIENT
    public static void handleManaOnClient(ManaData manaData, IPayloadContext context) {
        context.player().setData(ModData.MANA, manaData.newValue());
    }
}
