package tterrag1112.life_in_the_village.Items;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import tterrag1112.life_in_the_village.Gui.VillageBookScreen;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * Village economy scroll (merchant arc Phase 5c). Right-click to open the
 * economy view of the village the holder is standing in — the non-office
 * access route (bought from a village/guild treasurer). Read-only; opens
 * {@link VillageBookScreen} straight to its Economy section.
 */
public class VillageEconomyScrollItem extends Item {

    public VillageEconomyScrollItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel serverLevel)
                || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }

        VillageSavedData data = VillageSavedData.get(serverLevel);
        Village village = data.getVillageAt(player.blockPosition()).orElse(null);
        if (village == null) {
            player.displayClientMessage(Component.literal(
                    "You must be inside a village to read its economy."), true);
            return InteractionResult.FAIL;
        }

        VillageBookScreen.sendOpenPacket(serverPlayer, village.getId(),
                serverLevel, data, "ECONOMY");
        return InteractionResult.SUCCESS;
    }
}
