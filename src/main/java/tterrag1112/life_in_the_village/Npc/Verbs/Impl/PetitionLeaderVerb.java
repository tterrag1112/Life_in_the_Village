package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Laws.LawDecisionEngine;
import tterrag1112.life_in_the_village.Npc.Laws.LawEnactment;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

/**
 * "Petition leader" verb. Spec line 186.
 *
 * <p>Available iff the targeted NPC is the village_leader and the
 * player has a per-NPC relationship score of {@link
 * #RELATIONSHIP_GATE} or higher (spec: "rel ≥ +20").</p>
 *
 * <p>v1 invocation picks the highest-scoring law on the leader's
 * decision-engine table and pushes the per-day roll to a higher chance
 * (relationship × 10 weight, per spec line 189). The rest of the
 * lifecycle reuses {@link LawEnactment#enact} so gossip / mood / rep
 * fire identically. The full picker UI lands in the player Office tab
 * follow-up — flagged in 22 Revision Notes.</p>
 */
public final class PetitionLeaderVerb implements PlayerVerb {

    public static final String VERB_ID = "petition_leader";
    public static final int RELATIONSHIP_GATE = 20;

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Petition leader"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Ask them to enact a new village law."));
    }
    @Override public int displayOrder() { return 72; }
    @Override public int cooldownTicks() { return 24000; } // once per in-game day

    @Override
    public boolean isAvailable(VerbContext ctx) {
        if (!isLeader(ctx)) return false;
        return ctx.npc().getRelationships().getDelta(ctx.player().getUUID()) >= RELATIONSHIP_GATE;
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        if (!isLeader(ctx)) return VerbResult.failure("They don't make law here.");
        if (ctx.npc().getRelationships().getDelta(ctx.player().getUUID()) < RELATIONSHIP_GATE) {
            return VerbResult.failure("They don't trust you that much yet.");
        }

        Village village = villageOf(ctx);
        if (village == null) return VerbResult.failure("No village here.");

        // Score every plausible law via the engine + add the spec-line-189
        // relationship-weighted bias.
        int rel = ctx.npc().getRelationships().getDelta(ctx.player().getUUID());
        VillageLaw best = null;
        float bestScore = 0f;
        for (VillageLaw law : VillageLaw.values()) {
            if (village.getPolicy().hasLaw(law)) continue;
            if (village.getPolicy().firstConflictWith(law).isPresent()) continue;
            float score = LawDecisionEngine.candidateScore(law, village,
                    VillageSavedData.get(ctx.level()), ctx.level(), ctx.npc());
            // Spec line 189: relationship × 10 weight added to enactment bias.
            score += rel * 10f / 20f; // normalised so +20 rel = +10 score
            if (score > bestScore) { best = law; bestScore = score; }
        }
        if (best == null) {
            return VerbResult.failure("They have no law to enact today.");
        }

        LawEnactment.Outcome out = LawEnactment.enact(village, best, null,
                ctx.level(), ctx.npc().getUUID());
        if (!out.success()) {
            return VerbResult.failure("They consider but decline (" + out.reason() + ").");
        }

        // Reward the petitioner +5 per spec line 192 ("succeeded: rel +5
        // with petitioner").
        ctx.npc().getRelationships().adjust(ctx.player().getUUID(), 5);
        ctx.player().sendSystemMessage(Component.literal(
                "After your petition, " + ctx.npc().getNpcName()
                        + " enacts: " + best.description()));
        return VerbResult.success("petition_leader.response.default");
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private static boolean isLeader(VerbContext ctx) {
        Village village = villageOf(ctx);
        if (village == null) return false;
        java.util.UUID npcId = ctx.npc().getUUID();
        return village.getOffices().get(OfficeRegistry.VILLAGE_LEADER)
                .filter(h -> h.isHeldBy(npcId)).isPresent();
    }

    private static Village villageOf(VerbContext ctx) {
        return ctx.npc().getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(ctx.level()).getVillageByName(name))
                .orElse(null);
    }
}
