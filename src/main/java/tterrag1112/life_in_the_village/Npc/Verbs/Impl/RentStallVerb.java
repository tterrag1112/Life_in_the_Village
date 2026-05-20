package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Networking.OpenStallLeasePacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.Optional;
import java.util.UUID;

/**
 * Track 5a — opens the {@code StallLeaseScreen}. Surfaced as a verb
 * button on a MERCHANT's BusinessFrontScreen at a MARKET so the lease
 * flow has a discoverable in-screen entry point.
 */
public final class RentStallVerb implements PlayerVerb {

    public static final String VERB_ID = "rent_stall";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Rent a Stall"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Open the stall-lease counter."));
    }
    @Override public int displayOrder() { return 25; }
    @Override public int cooldownTicks() { return 0; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        if (ctx.npc().getProfession() != Profession.MERCHANT) return false;
        var building = ctx.npc().getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(ctx.level()).getBuildingById(id))
                .orElse(null);
        return building != null && building.getType() == BuildingType.MARKET;
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return VerbResult.failure("server-only");
        if (!(sp.level() instanceof ServerLevel)) return VerbResult.failure("level missing");
        UUID market = ctx.npc().getAssignedBuildingId().orElse(new UUID(0L, 0L));
        PacketDistributor.sendToPlayer(sp, new OpenStallLeasePacket(
                ctx.npc().getUUID(), ctx.npc().getNpcName(), market));
        return VerbResult.opensScreen("StallLease");
    }
}
