package tterrag1112.life_in_the_village.Items;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Networking.OpenKingdomBookPacket;
import tterrag1112.life_in_the_village.Networking.SyncKingdomPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.UUID;

public class KingdomBookItem extends Item {

    public KingdomBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(
            Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        // Find the kingdom this player rules
        var data = VillageSavedData.get(
                (net.minecraft.server.level.ServerLevel) level);

        UUID kingdomId = data.getAllKingdoms().stream()
                .filter(k -> k.getRulerPlayerId()
                        .map(id -> id.equals(player.getUUID()))
                        .orElse(false))
                .map(k -> k.getId())
                .findFirst()
                .orElse(null);

        if (kingdomId == null) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component
                            .literal("You are not the ruler "
                                    + "of any kingdom."), true);
            return InteractionResult.FAIL;
        }

        // Send kingdoms first
        PacketDistributor.sendToPlayer(
                (ServerPlayer) player,
                new SyncKingdomPacket(data.getAllKingdoms()));

// Then open — arrives after sync due to packet ordering
        PacketDistributor.sendToPlayer(
                (ServerPlayer) player,
                new OpenKingdomBookPacket(kingdomId));

        return InteractionResult.SUCCESS;
    }
}