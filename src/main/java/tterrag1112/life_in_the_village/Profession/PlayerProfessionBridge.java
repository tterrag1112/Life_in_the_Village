package tterrag1112.life_in_the_village.Profession;

import tterrag1112.life_in_the_village.Npc.Skills.Skill;

import java.util.Optional;

/**
 * Pure static lookup tables bridging {@link PlayerProfession} to the NPC
 * skill and profession systems.
 *
 * <h3>primarySkill</h3>
 * <p>Maps each {@link PlayerProfession} to the {@link Skill} that represents
 * the player's competence in that profession. Used by
 * {@link tterrag1112.life_in_the_village.Npc.Tasks.PlayerTaskContext} to
 * answer {@code skillLevel(Skill)} queries: if the player's active profession
 * maps to the queried skill, return the profession level; otherwise 0.</p>
 *
 * <p>Mappings use the inverse of
 * {@link tterrag1112.life_in_the_village.Npc.Skills.ProfessionSkills} where an
 * NPC analog exists:</p>
 * <ul>
 *   <li>FARMER → FARMING (NPC FARMER primary)</li>
 *   <li>BLACKSMITH → BLACKSMITHING (NPC BLACKSMITH primary)</li>
 *   <li>CARPENTER → CARPENTRY (NPC CARPENTER primary)</li>
 *   <li>MINER → MINING (new; NPC MINER primary from P0)</li>
 *   <li>MERCHANT → COMMERCE (NPC MERCHANT primary)</li>
 *   <li>GUARD → COMBAT (NPC GUARD primary)</li>
 *   <li>ROAD_ENGINEER → CRAFTING (no dedicated NPC skill; CRAFTING is the
 *       closest reasonable general-construction axis. Flagged for review when
 *       a road-construction skill is added.)</li>
 * </ul>
 *
 * <h3>toNpcProfession</h3>
 * <p>Maps each {@link PlayerProfession} to the nearest NPC
 * {@link Profession}. Used by {@link tterrag1112.life_in_the_village.Npc.Tasks.PlayerTaskContext}
 * to answer {@code profession()} queries so profession-gated task filters
 * work for players.</p>
 *
 * <p>{@code ROAD_ENGINEER} has no direct NPC analog and returns
 * {@code Optional.empty()}.</p>
 *
 * <p>Nothing in P0 calls these methods from live game code; they are
 * consumed by {@link tterrag1112.life_in_the_village.Npc.Tasks.PlayerTaskContext}
 * which is itself unused-by-default until P1.</p>
 */
public final class PlayerProfessionBridge {

    private PlayerProfessionBridge() {}

    /**
     * The {@link Skill} that represents competence for {@code pp}.
     *
     * <p>Always returns a value; {@code ROAD_ENGINEER} returns
     * {@link Skill#CRAFTING} as a documented approximation. Callers that
     * need to distinguish "no dedicated skill" from a real mapping should
     * check {@link #hasDedicatedSkill(PlayerProfession)}.</p>
     */
    public static Skill primarySkill(PlayerProfession pp) {
        return switch (pp) {
            case FARMER        -> Skill.FARMING;
            case BLACKSMITH    -> Skill.BLACKSMITHING;
            case CARPENTER     -> Skill.CARPENTRY;
            case MINER         -> Skill.MINING;
            case MERCHANT      -> Skill.COMMERCE;
            case GUARD         -> Skill.COMBAT;
            // ROAD_ENGINEER: no dedicated NPC skill yet. CRAFTING is the
            // broadest construction-family axis. Flagged for P-later when
            // a ROAD_ENGINEERING / SURVEYING skill is added.
            case ROAD_ENGINEER -> Skill.CRAFTING;
        };
    }

    /**
     * Whether {@code pp} has a dedicated (non-approximated) NPC skill.
     * Returns {@code false} only for {@link PlayerProfession#ROAD_ENGINEER},
     * which maps to {@link Skill#CRAFTING} as an approximation.
     */
    public static boolean hasDedicatedSkill(PlayerProfession pp) {
        return pp != PlayerProfession.ROAD_ENGINEER;
    }

    /**
     * The nearest NPC {@link Profession} for {@code pp}, if one exists.
     * Returns {@code Optional.empty()} for {@link PlayerProfession#ROAD_ENGINEER}
     * (no NPC analog).
     */
    public static Optional<Profession> toNpcProfession(PlayerProfession pp) {
        return switch (pp) {
            case FARMER        -> Optional.of(Profession.FARMER);
            case BLACKSMITH    -> Optional.of(Profession.BLACKSMITH);
            case CARPENTER     -> Optional.of(Profession.CARPENTER);
            case MINER         -> Optional.of(Profession.MINER);
            case MERCHANT      -> Optional.of(Profession.MERCHANT);
            case GUARD         -> Optional.of(Profession.GUARD);
            // No NPC profession models road-engineering as a spawnable role.
            case ROAD_ENGINEER -> Optional.empty();
        };
    }
}
