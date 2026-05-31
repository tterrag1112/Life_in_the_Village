package tterrag1112.life_in_the_village.Items;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.OpenKingdomBookPacket;
import tterrag1112.life_in_the_village.Networking.SyncKingdomPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.UUID;

/**
 * Kingdom price-board item (merchant arc Phase 5d). The non-ruler access
 * route to the cross-village price board: right-click to open the kingdom
 * book for the kingdom of the village you're standing in (or the kingdom
 * you rule). Unlike {@code KingdomBookItem} this is not ruler-gated — any
 * holder can view the board's Prices section. Read-only.
 */
public class PriceBoardItem extends Item {

    public PriceBoardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel serverLevel)
                || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.SUCCESS;
        }

        VillageSavedData data = VillageSavedData.get(serverLevel);

        // Prefer the kingdom of the village the holder stands in; else the
        // kingdom they rule.
        UUID kingdomId = data.getVillageAt(player.blockPosition())
                .flatMap(v -> data.getKingdomForVillage(v.getId()))
                .map(Kingdom::getId)
                .orElseGet(() -> data.getAllKingdoms().stream()
                        .filter(k -> k.getRulerPlayerId()
                                .map(id -> id.equals(player.getUUID())).orElse(false))
                        .map(Kingdom::getId).findFirst().orElse(null));

        if (kingdomId == null) {
            player.displayClientMessage(Component.literal(
                    "Stand in a kingdom's village to read its price board."), true);
            return InteractionResult.FAIL;
        }

        // Sync kingdoms first (client cache), then open the book — the
        // Prices section lazy-loads its aggregate on first view.
        PacketDistributor.sendToPlayer(sp, new SyncKingdomPacket(data.getAllKingdoms()));
        PacketDistributor.sendToPlayer(sp, new OpenKingdomBookPacket(kingdomId));
        return InteractionResult.SUCCESS;
    }
}
