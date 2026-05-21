package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Npc.Health.ActiveCondition;
import tterrag1112.life_in_the_village.Npc.Health.HealerInventory;
import tterrag1112.life_in_the_village.Npc.Health.HealthCondition;
import tterrag1112.life_in_the_village.Npc.Health.Remedy;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.Optional;

/**
 * Spec line 192. Player asks the healer for treatment of a condition
 * the player believes they have. v1 cannot diagnose the player
 * (player uses vanilla HP, not HealthComponent), so this verb is a
 * minimal "buy a remedy" surrogate when no NPC patient is present —
 * Phase 4 expansion will diagnose mounted players, child NPCs, etc.
 *
 * <p>Effect: takes the highest-potency remedy from the healer's
 * stash and gives it to the player's stash on
 * {@code HealthSavedData}. The healer earns a small MEDICINE XP
 * blip as if they treated a patient.</p>
 */
public final class RequestTreatmentVerb implements PlayerVerb {

    public static final String VERB_ID = "request_treatment";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Request treatment"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Ask the healer to treat your wounds."));
    }
    @Override public int displayOrder() { return 70; }
    @Override public int cooldownTicks() { return 6000; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        return ctx.npc().getProfession() == Profession.HEALER
                && !ctx.npc().getHealerInventory().remedies().isEmpty();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        if (ctx.npc().getProfession() != Profession.HEALER) {
            return VerbResult.failure("They are not a healer.");
        }
        HealerInventory stash = ctx.npc().getHealerInventory();
        // Pick a remedy that targets the player's "implied" condition.
        // Without HealthComponent on the player, default to MINOR_WOUND
        // (covers the common case "I got hurt fighting").
        Optional<Remedy> taken = stash.takeFor(HealthCondition.MINOR_WOUND);
        if (taken.isEmpty() && !stash.remedies().isEmpty()) {
            // Fall back to any remedy on hand: pick the first stash
            // entry, then take it via its primary target so the stash
            // state stays consistent.
            Remedy first = stash.remedies().get(0);
            HealthCondition target = first.type().targets().iterator().hasNext()
                    ? first.type().targets().iterator().next()
                    : HealthCondition.MINOR_WOUND;
            taken = stash.takeFor(target);
        }
        if (taken.isEmpty()) {
            return VerbResult.failure("They have nothing to give you.");
        }
        // Hand the remedy to the player's stash.
        var hdata = tterrag1112.life_in_the_village.Npc.Health.HealthSavedData.get(ctx.level());
        hdata.getOrCreatePlayerStash(ctx.player().getUUID()).add(taken.get());
        hdata.markDirty();
        ctx.npc().getRelationships().adjust(ctx.player().getUUID(), 5);
        tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(ctx.npc(), 
                tterrag1112.life_in_the_village.Npc.Skills.Skill.MEDICINE, 2,
                ctx.level().getGameTime());
        // Suppress unused-import warnings on ActiveCondition.
        @SuppressWarnings("unused") ActiveCondition __ = null;
        return VerbResult.success("greeting.business.healer.work");
    }
}
