package tterrag1112.life_in_the_village.Npc.Office.Selection;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

import java.util.UUID;

/**
 * One scored candidate for a selection run. Built by the candidate-pool
 * walker; consumed by {@link OfficeSelectionEngine} for ranking.
 *
 * <p>Score is selection-method specific — meritocratic uses skill +
 * trait bonus; elective uses summed relationship weights from voters;
 * etc. Tiebreaks always fall back to {@link SelectionTiebreaker}'s
 * deterministic order.</p>
 */
public record OfficeCandidate(TownspersonMob npc, UUID uuid, double score,
                              int primarySkillLevel, Skill primarySkill,
                              float ambition, int age) {

    /** Convenience builder for engines that compute score later. */
    public OfficeCandidate withScore(double newScore) {
        return new OfficeCandidate(npc, uuid, newScore, primarySkillLevel, primarySkill, ambition, age);
    }
}
