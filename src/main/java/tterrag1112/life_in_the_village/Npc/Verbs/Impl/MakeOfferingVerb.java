package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.PietyComponent;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionRegistry;
import tterrag1112.life_in_the_village.Npc.Religion.Rite;
import tterrag1112.life_in_the_village.Npc.Religion.RiteSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.RiteScheduler;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spec line 155. Player makes an offering to the temple — piety
 * gain proportional to the (here unscaled) offering plus a small
 * relationship bump with the priest. v1 schedules a
 * {@link Rite#OFFERING} immediately and updates the player-piety
 * ledger directly so save/reload preserves it.
 */
public final class MakeOfferingVerb implements PlayerVerb {

    public static final String VERB_ID = "make_offering";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Make offering"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Leave an offering at the temple."));
    }
    @Override public int displayOrder() { return 68; }
    @Override public int cooldownTicks() { return 6000; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        return ctx.npc().getProfession() == Profession.PRIEST;
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        if (ctx.npc().getProfession() != Profession.PRIEST) {
            return VerbResult.failure("They are not a priest.");
        }
        VillageSavedData vdata = VillageSavedData.get(ctx.level());
        Village village = ctx.npc().getAssignedVillageName()
                .flatMap(vdata::getVillageByName).orElse(null);
        if (village == null) return VerbResult.failure("No village context.");

        // Schedule the rite (handler handles npc piety + mood arms).
        RiteScheduler.schedule(ctx.level(), village, Rite.OFFERING,
                List.of(ctx.player().getUUID()), 0L);

        // Player-side piety + relationship + treasury bump.
        UUID playerId = ctx.player().getUUID();
        PietyComponent piety = RiteSavedData.get(ctx.level()).getOrCreatePlayerPiety(playerId);
        String religionId = piety.primaryReligion().orElseGet(() -> {
            String fallback = ReligionRegistry.dominantReligionFor(
                    vdata.getKingdomForVillage(village.getId())
                            .map(tterrag1112.life_in_the_village.Kingdom.Kingdom::getCulture)
                            .orElse("default"));
            piety.setBelief(fallback, 0.05f);
            return fallback;
        });
        piety.adjustBelief(religionId, 0.05f);
        piety.recordRiteAttendance(ctx.level().getGameTime());
        RiteSavedData.get(ctx.level()).markDirty();

        ctx.npc().getRelationships().adjust(playerId, 5);
        // Temple treasury bump — uses the existing per-building economy
        // attached to the temple if present.
        UUID buildingId = ctx.npc().getAssignedBuildingId().orElse(null);
        if (buildingId != null) {
            Building b = vdata.getBuildingById(buildingId).orElse(null);
            if (b != null && b.getType() == BuildingType.TEMPLE) {
                vdata.getOrCreateBuildingEconomy(buildingId).depositRevenue(10L);
            }
        }
        return VerbResult.success("greeting.business.healer.work");
    }
}
