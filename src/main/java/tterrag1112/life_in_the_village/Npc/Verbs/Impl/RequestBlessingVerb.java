package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.Rite;
import tterrag1112.life_in_the_village.Npc.Religion.RiteScheduler;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;

/**
 * Spec line 156. Player asks a priest for a {@link Rite#BLESSING}
 * — small mood + 24h primary-skill buff (the buff plumbing already
 * exists via Phase 2 task 18 ActiveSkillBuff; v1 only ships the
 * mood arm — buff wiring lands when blessings get a fee UI).
 */
public final class RequestBlessingVerb implements PlayerVerb {

    public static final String VERB_ID = "request_blessing";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Request blessing"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Ask the priest for a blessing."));
    }
    @Override public int displayOrder() { return 65; }
    @Override public int cooldownTicks() { return 24000; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        return ctx.npc().getProfession() == Profession.PRIEST;
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        if (ctx.npc().getProfession() != Profession.PRIEST) {
            return VerbResult.failure("They are not a priest.");
        }
        Village village = ctx.npc().getAssignedVillageName()
                .flatMap(VillageSavedData.get(ctx.level())::getVillageByName).orElse(null);
        if (village == null) return VerbResult.failure("No village context.");
        // Schedule a one-shot blessing rite naming the player as
        // participant. Runs immediately (delay = 0) — the priest
        // officiates next daily-tick pass.
        RiteScheduler.schedule(ctx.level(), village, Rite.BLESSING,
                List.of(ctx.player().getUUID()), 0L);
        return VerbResult.success("greeting.business.healer.work");
    }
}
