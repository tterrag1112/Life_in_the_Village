package tterrag1112.life_in_the_village.Items;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Gui.CompanyManagementScreen;
import tterrag1112.life_in_the_village.Guilds.Companies.Company;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.List;

public class CompanyLedgerItem extends Item {

    public CompanyLedgerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        ServerLevel serverLevel = (ServerLevel) level;
        ServerPlayer serverPlayer = (ServerPlayer) player;

        CompanySavedData companyData = CompanySavedData.get(serverLevel);
        VillageSavedData villageData = VillageSavedData.get(serverLevel);

        List<Company> owned =
                companyData.getByOwner(player.getUUID());

        if (owned.isEmpty()) {
            player.displayClientMessage(
                    Component.literal(
                            "You do not own any companies. "
                                    + "Visit a Town-tier village leader "
                                    + "to found one."),
                    false);
            return InteractionResult.FAIL;
        }

        // If the player owns exactly one company, open it directly.
        // If they own multiple, open the first one for now —
        // a company selection screen can be added later.
        var company = owned.get(0);

        CompanyManagementScreen.sendOpenPacket(
                serverPlayer,
                company.getCompanyId(),
                serverLevel,
                companyData,
                villageData);

        return InteractionResult.SUCCESS;
    }
}