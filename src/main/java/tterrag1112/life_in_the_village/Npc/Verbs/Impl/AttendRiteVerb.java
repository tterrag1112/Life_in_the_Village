package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.FaithReconciliation;
import tterrag1112.life_in_the_village.Npc.Religion.PietyComponent;
import tterrag1112.life_in_the_village.Npc.Religion.PietyPayoff;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionContent;
import tterrag1112.life_in_the_village.Npc.Religion.RiteExecution;
import tterrag1112.life_in_the_village.Npc.Religion.RiteOutcome;
import tterrag1112.life_in_the_village.Npc.Religion.RiteSavedData;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;
import tterrag1112.life_in_the_village.Village.Event.CeremonyBlessings;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;
import java.util.UUID;

/**
 * Religion Rework R9d — the player attends a religious gathering that is
 * currently under way in the village: a small piety gain (faith-aware via
 * {@link FaithReconciliation} — reduced / syncretic for another faith's rite)
 * recorded as attendance, plus the R9d piety payoff (co-religionists present
 * warm to a devout attendant). Gated to an actually-active rite/festival so it
 * is meaningful. Mirrors {@link MakeOfferingVerb}'s player-piety bookkeeping.
 */
public final class AttendRiteVerb implements PlayerVerb {

    public static final String VERB_ID = "attend_rite";

    /** Base piety deepening for attending (scaled down for a cross-faith rite). */
    private static final float ATTEND_PIETY = 0.04f;

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Attend rite"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Join the rite under way and deepen your faith."));
    }
    @Override public int displayOrder() { return 66; }
    @Override public int cooldownTicks() { return 6000; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        return activeRiteFaith(ctx) != null;
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        String riteFaith = activeRiteFaith(ctx);
        if (riteFaith == null) {
            return VerbResult.failure("There is no rite under way to attend.");
        }
        VillageSavedData vdata = VillageSavedData.get(ctx.level());
        Village village = ctx.npc().getAssignedVillageName()
                .flatMap(vdata::getVillageByName).orElse(null);
        if (village == null) return VerbResult.failure("No village context.");

        UUID playerId = ctx.player().getUUID();
        RiteSavedData rdata = RiteSavedData.get(ctx.level());
        PietyComponent piety = rdata.getOrCreatePlayerPiety(playerId);
        String playerFaith = piety.primaryReligion().orElse(null);

        // Faith-aware benefit: a co-religionist deepens their own faith fully; a
        // different / unaffiliated attendant drifts (reduced) toward the rite's
        // faith — no own-faith credit (mirrors FaithReconciliation's policy).
        FaithReconciliation.FaithBenefit fb =
                FaithReconciliation.faithBenefit(playerFaith, riteFaith);
        String targetFaith = fb.sameFaith() ? playerFaith : riteFaith;
        float gain = ATTEND_PIETY * fb.moodMultiplier();
        piety.adjustBelief(targetFaith, gain);
        piety.recordRiteAttendance(ctx.level().getGameTime());
        rdata.markDirty();

        // R9d payoff — the public act of devotion warms co-religionists present.
        int warmed = PietyPayoff.applyCoReligionistRegard(ctx.level(), ctx.player(),
                village, vdata, piety.primaryReligion().orElse(null), piety.primaryTier());

        ctx.player().displayClientMessage(Component.literal(
                fb.sameFaith() ? "You attend the rite and your faith deepens."
                        : "You observe the rite respectfully."), true);
        if (warmed > 0) ctx.npc().getRelationships().adjust(playerId,
                PietyPayoff.regardBonus(piety.primaryTier()));
        return VerbResult.success("");
    }

    /**
     * The faith of a gathering currently active in the npc's village, or null if
     * none. Prefers a live {@link VillageEvent}'s {@code FAITH_KEY}; falls back
     * to the village religion for a bare active {@link RiteExecution}.
     */
    private static String activeRiteFaith(VerbContext ctx) {
        VillageSavedData vdata = VillageSavedData.get(ctx.level());
        Village village = ctx.npc().getAssignedVillageName()
                .flatMap(vdata::getVillageByName).orElse(null);
        if (village == null) return null;

        for (VillageEvent e : vdata.getActiveEventsForVillage(village.getId())) {
            String faith = e.getEventData().get(CeremonyBlessings.FAITH_KEY);
            if (faith != null && !faith.isEmpty()) return faith;
            return ReligionContent.villageReligionId(ctx.level(), village);
        }

        long now = ctx.level().getGameTime();
        for (RiteExecution r : RiteSavedData.get(ctx.level()).ritesForVillage(village.getId())) {
            if (r.outcome() == RiteOutcome.PENDING && r.isActiveAt(now)) {
                return ReligionContent.villageReligionId(ctx.level(), village);
            }
        }
        return null;
    }
}
