package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.Optional;

/**
 * Spec line 346. Player takes a lesson from a SCHOLAR or LIBRARIAN.
 * Phase 2 v1: bumps the player-facing reputation a touch and grants
 * a small LITERACY trickle to the NPC's nearby children. The
 * children-only restriction is enforced by spec line 346; v1 also
 * lets adults sit lessons since the schooling system (doc 15) isn't
 * here yet to differentiate.
 */
public final class TakeLessonVerb implements PlayerVerb {

    public static final String VERB_ID = "take_lesson";

    public static final int LESSON_LITERACY_XP = 2;

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Take a lesson"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Sit a short lesson — a small literacy bump."));
    }
    @Override public int displayOrder() { return 66; }
    @Override public int cooldownTicks() { return 12000; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        Profession p = ctx.npc().getProfession();
        return p == Profession.SCHOLAR || p == Profession.LIBRARIAN;
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        // Award the player no XP (no player-skill ledger yet); the NPC
        // logs the session and bumps their teaching SOCIAL skill, and
        // any nearby children get a small LITERACY tick. Spec line 305.
        ctx.npc().getSkills().addXp(Skill.SOCIAL, 1, ctx.tick());
        var nearbyChildren = ctx.level().getEntitiesOfClass(
                tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                ctx.npc().getBoundingBox().inflate(8),
                m -> m != ctx.npc() && m.isChild());
        for (var child : nearbyChildren) {
            child.getSkills().addXp(Skill.LITERACY, LESSON_LITERACY_XP, ctx.tick());
        }
        return VerbResult.success("lesson.taken");
    }
}
