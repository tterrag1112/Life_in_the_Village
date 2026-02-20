package tterrag1112.life_in_the_village.Networking;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacementType;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Magic.ManaSystem.ModManaHandler;
import tterrag1112.life_in_the_village.Village.Buildings.ModBuildings;

import javax.swing.text.html.parser.Entity;

//From Client to Server
public class ClientPayloadHandler {

    public static void handleDataOnMain(KeyData keyData, IPayloadContext context) {
        if(context.player().getData(ModData.MANA)>0) {
            EntityType.MOOSHROOM.spawn(((ServerLevel) context.player().level()), context.player().getOnPos(), EntitySpawnReason.TRIGGERED);
            ModManaHandler.decreaseManaForPlayer(context.player(), 1);
        }
    }
}
