package tterrag1112.life_in_the_village.Items;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Networking.OpenRoadProposalsPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Proposal.RoadProposal;
import tterrag1112.life_in_the_village.Village.Roads.Proposal.RoadProposalManager;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Track C3.1 — Road Engineer's Plans book.
 *
 * <p>Right-clicking opens the {@link tterrag1112.life_in_the_village.Gui.RoadProposalsScreen}
 * with a snapshot of the player's outstanding proposals (read-only).
 * Submission flows through {@code /litv road propose}; this item
 * exposes status only.
 */
public class RoadEngineerPlansItem extends Item {

    public RoadEngineerPlansItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        ServerLevel sLevel = (ServerLevel) level;
        ServerPlayer sp = (ServerPlayer) player;

        VillageSavedData vdata = VillageSavedData.get(sLevel);
        List<RoadProposal> mine = RoadProposalManager.proposalsForPlayer(sLevel, sp.getUUID());
        List<OpenRoadProposalsPacket.Entry> entries = new ArrayList<>(mine.size());
        for (RoadProposal p : mine) {
            String fromName = vdata.getVillageById(p.fromVillageId())
                    .map(Village::getName).orElse("(unknown)");
            String toName = vdata.getVillageById(p.toVillageId())
                    .map(Village::getName).orElse("(unknown)");
            entries.add(new OpenRoadProposalsPacket.Entry(
                    fromName, toName, p.tier().name(), p.status().name(),
                    p.totalBlocks(), p.progressBlocks(), p.currencyFeeBronze()));
        }

        PacketDistributor.sendToPlayer(sp, new OpenRoadProposalsPacket(entries));
        return InteractionResult.SUCCESS;
    }
}
