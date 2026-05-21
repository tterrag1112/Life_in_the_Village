package tterrag1112.life_in_the_village.Npc.Skills;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Apprentice.MentorshipBonus;

/**
 * Phase 6.3.2.b — central XP-award entry point. All NPC skill XP grants
 * flow through {@link #award} so the mentee mentorship multiplier (and
 * any future global modifier) is applied at exactly one site.
 *
 * <p>The previously latent bug (6.3.1.D): {@code MentorshipBonus} +
 * {@code ApprenticeshipManager.mentorshipMultiplierFor} existed but
 * no XP grant site invoked them. {@link #award} resolves the mentor
 * multiplier and applies it before delegating to
 * {@link SkillComponent#addXp}, whose cascade then propagates the
 * (already-boosted) amount upward through the skill tree.
 */
public final class SkillXp {

    private SkillXp() {}

    /**
     * Awards {@code amount} XP for {@code skill} to {@code recipient}.
     * If the recipient is an active apprentice co-located with their
     * master, the amount is scaled by
     * {@link MentorshipBonus#NPC_MENTORSHIP_MULTIPLIER} before being
     * forwarded to the cascade.
     *
     * <p>Safe to call with a non-ServerLevel-resident entity (e.g.
     * during NBT load); the multiplier resolves to 1.0 when no
     * ServerLevel context is available.
     */
    public static void award(TownspersonMob recipient, Skill skill,
                             float amount, long currentTick) {
        if (recipient == null || amount == 0f) return;
        float multiplier = 1f;
        if (recipient.level() instanceof ServerLevel sl) {
            multiplier = MentorshipBonus.npcMentorshipFor(recipient, sl);
        }
        recipient.getSkills().addXp(skill, amount * multiplier, currentTick);
    }

    /** Integer-amount convenience. */
    public static void award(TownspersonMob recipient, Skill skill,
                             int amount, long currentTick) {
        award(recipient, skill, (float) amount, currentTick);
    }
}
