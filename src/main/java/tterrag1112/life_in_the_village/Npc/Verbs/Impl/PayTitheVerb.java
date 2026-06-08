package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.BuildingFaith;
import tterrag1112.life_in_the_village.Npc.Religion.PietyComponent;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionContent;
import tterrag1112.life_in_the_village.Npc.Religion.RiteSavedData;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;
import java.util.UUID;

/**
 * Religion Rework R4d-1 — the player opt-in for the recurring auto-tithe. Used at
 * a priest: toggles a weekly auto-deduction of the player's tithe to that temple's
 * {@code BuildingEconomy} (handled by {@code Tithing}), growing the player's
 * piety. Toggleable off; affordability is enforced at deduction time.
 */
public final class PayTitheVerb implements PlayerVerb {

    public static final String VERB_ID = "pay_tithe";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Pledge tithe"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Pledge (or cancel) a weekly tithe to this temple."));
    }
    @Override public int displayOrder() { return 69; }
    @Override public int cooldownTicks() { return 200; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        return ctx.npc().getProfession() == Profession.PRIEST
                && ctx.npc().getAssignedBuildingId().isPresent();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        if (ctx.npc().getProfession() != Profession.PRIEST) {
            return VerbResult.failure("They are not a priest.");
        }
        VillageSavedData vdata = VillageSavedData.get(ctx.level());
        Village village = ctx.npc().getAssignedVillageName()
                .flatMap(vdata::getVillageByName).orElse(null);
        UUID buildingId = ctx.npc().getAssignedBuildingId().orElse(null);
        if (village == null || buildingId == null) return VerbResult.failure("No temple here.");

        RiteSavedData rdata = RiteSavedData.get(ctx.level());
        UUID playerId = ctx.player().getUUID();

        // Generic priest acknowledgement tree (as MakeOfferingVerb uses) — a
        // dedicated set/cancel dialogue is a polish follow-up; the toggle itself
        // is what matters.
        if (rdata.isAutoTithe(playerId)) {
            rdata.clearAutoTithe(playerId);
            return VerbResult.success("greeting.business.healer.work");
        }

        // Opt in: give the player a faith (this temple's) if they have none, so
        // the recurring tithe credits the right piety.
        PietyComponent piety = rdata.getOrCreatePlayerPiety(playerId);
        if (piety.primaryReligion().isEmpty()) {
            Building b = vdata.getBuildingById(buildingId).orElse(null);
            String faith = BuildingFaith.resolveFaith(ctx.level(), village, b);
            if (faith == null) faith = ReligionContent.villageReligionId(ctx.level(), village);
            piety.setBelief(faith, 0.05f);
        }
        rdata.setAutoTithe(playerId, buildingId);
        rdata.markDirty();
        return VerbResult.success("greeting.business.healer.work");
    }
}
