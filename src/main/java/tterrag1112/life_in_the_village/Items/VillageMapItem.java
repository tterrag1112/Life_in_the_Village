package tterrag1112.life_in_the_village.Items;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import tterrag1112.life_in_the_village.Client.ClientProxy;
import tterrag1112.life_in_the_village.Gui.VillageMapScreen;

public class VillageMapItem extends Item {

    public VillageMapItem(Properties properties) {
        super(properties);
    }


    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            ClientProxy.openVillageMap();
        }
        return InteractionResult.SUCCESS;
    }

}
