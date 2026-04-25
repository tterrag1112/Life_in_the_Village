package tterrag1112.life_in_the_village.Npc.LifeGoal;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static catalogue of every {@link LifeGoalDefinition}. Lazy-init on
 * first access; idempotent. Trait weights mirror the spec's
 * "Trait-goal alignment" examples; missing axes contribute 0.
 *
 * <p>See {@code docs/npc_redesign/07-life-goals.md}.</p>
 */
public final class LifeGoalRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<LifeGoalType, LifeGoalDefinition> DEFS = new EnumMap<>(LifeGoalType.class);
    private static volatile boolean initialised = false;

    private LifeGoalRegistry() {}

    public static LifeGoalDefinition get(LifeGoalType type) {
        ensureInit();
        return DEFS.get(type);
    }

    public static List<LifeGoalDefinition> all() {
        ensureInit();
        return List.copyOf(DEFS.values());
    }

    private static synchronized void ensureInit() {
        if (initialised) return;
        registerAll();
        initialised = true;
        LOGGER.info("[LifeGoalRegistry] Registered {} life-goal definitions", DEFS.size());
    }

    private static void registerAll() {
        // ── Wealth & property ────────────────────────────────────────────
        define(LifeGoalType.SAVE_AMOUNT, "Save up", Map.of(
                TraitAxis.INDUSTRY, +0.5f,
                TraitAxis.TEMPERANCE, +0.4f,
                TraitAxis.GENEROSITY, -0.3f),
                null, allAdult(), 4, 500, "I need {amount} bronze put aside.");
        define(LifeGoalType.BUY_HOUSE, "Buy a home", Map.of(
                TraitAxis.INDUSTRY, +0.4f,
                TraitAxis.AMBITION, +0.2f),
                null, allAdult(), 5, 1, "I want a house of my own.");
        define(LifeGoalType.BUY_BUSINESS, "Buy a business", Map.of(
                TraitAxis.AMBITION, +0.6f,
                TraitAxis.INDUSTRY, +0.3f),
                Skill.COMMERCE, EnumSet.of(LifeGoalDefinition.Stage.ADULT), 6, 1,
                "I want my own business.");
        define(LifeGoalType.PAY_OFF_DEBT, "Clear my debts", Map.of(
                TraitAxis.HONESTY, +0.4f,
                TraitAxis.TEMPERANCE, +0.3f),
                null, allAdult(), 6, 1, "I owe a debt I'll see settled.");

        // ── Career ───────────────────────────────────────────────────────
        define(LifeGoalType.REACH_SKILL_LEVEL, "Master a skill", Map.of(
                TraitAxis.INDUSTRY, +0.5f,
                TraitAxis.AMBITION, +0.3f),
                null, allAdult(), 5, 60, "I want to be properly skilled.");
        define(LifeGoalType.REACH_PROFESSION_TIER, "Climb my craft", Map.of(
                TraitAxis.AMBITION, +0.5f,
                TraitAxis.INDUSTRY, +0.4f),
                null, allAdult(), 5, 1, "I want to be respected at my craft.");
        define(LifeGoalType.WIN_OFFICE, "Hold an office", Map.of(
                TraitAxis.AMBITION, +0.7f,
                TraitAxis.SOCIABILITY, +0.4f),
                Skill.SOCIAL, EnumSet.of(LifeGoalDefinition.Stage.ADULT), 7, 1,
                "I can do better than the current officer.");
        define(LifeGoalType.FOUND_BUSINESS, "Found a business", Map.of(
                TraitAxis.AMBITION, +0.7f,
                TraitAxis.INDUSTRY, +0.5f),
                Skill.COMMERCE, EnumSet.of(LifeGoalDefinition.Stage.ADULT), 7, 1,
                "I'll start something of my own.");
        define(LifeGoalType.MASTERPIECE, "Make a masterpiece", Map.of(
                TraitAxis.AMBITION, +0.4f,
                TraitAxis.INDUSTRY, +0.4f),
                Skill.CRAFTING, EnumSet.of(LifeGoalDefinition.Stage.ADULT), 6, 1,
                "I'll produce a masterpiece worth remembering.");

        // ── Family ───────────────────────────────────────────────────────
        define(LifeGoalType.MARRY_TARGET, "Marry someone special", Map.of(
                TraitAxis.SOCIABILITY, +0.5f,
                TraitAxis.COMPASSION, +0.4f),
                null, allAdult(), 7, 1, "There's only one for me.");
        define(LifeGoalType.MARRY_ANY, "Find a spouse", Map.of(
                TraitAxis.SOCIABILITY, +0.4f,
                TraitAxis.COMPASSION, +0.3f),
                null, allAdult(), 5, 1, "I should find a partner.");
        define(LifeGoalType.HAVE_CHILD, "Raise a family", Map.of(
                TraitAxis.COMPASSION, +0.6f,
                TraitAxis.SOCIABILITY, +0.3f),
                null, allAdult(), 6, 1, "I want children of my own.");
        define(LifeGoalType.SEE_CHILD_APPRENTICED, "See my child apprenticed", Map.of(
                TraitAxis.COMPASSION, +0.5f,
                TraitAxis.AMBITION, +0.3f),
                null, EnumSet.of(LifeGoalDefinition.Stage.ADULT, LifeGoalDefinition.Stage.ELDERLY),
                5, 1, "I want my child placed with a master.");
        define(LifeGoalType.SEE_CHILD_MARRIED, "See my child wed", Map.of(
                TraitAxis.COMPASSION, +0.5f),
                null, EnumSet.of(LifeGoalDefinition.Stage.ADULT, LifeGoalDefinition.Stage.ELDERLY),
                5, 1, "I want my child to marry well.");
        define(LifeGoalType.ARRANGE_CHILD_MATCH, "Arrange a match", Map.of(
                TraitAxis.AMBITION, +0.4f,
                TraitAxis.SOCIABILITY, +0.3f),
                null, EnumSet.of(LifeGoalDefinition.Stage.ADULT), 5, 1,
                "I'll find a fitting match for my child.");

        // ── Social ───────────────────────────────────────────────────────
        define(LifeGoalType.BEFRIEND_TARGET, "Befriend someone", Map.of(
                TraitAxis.SOCIABILITY, +0.5f,
                TraitAxis.COMPASSION, +0.4f),
                null, allAdult(), 4, 1, "I'd like them as a friend.");
        define(LifeGoalType.BEFRIEND_COUNT, "Build a circle of friends", Map.of(
                TraitAxis.SOCIABILITY, +0.7f,
                TraitAxis.COMPASSION, +0.3f),
                null, allAdult(), 4, 5, "I want friends I can count on.");
        define(LifeGoalType.AVENGE_WRONG, "Avenge a wrong", Map.of(
                TraitAxis.COURAGE, +0.5f,
                TraitAxis.TEMPERANCE, -0.5f,
                TraitAxis.COMPASSION, -0.3f),
                null, allAdult(), 7, 1, "They will answer for what they did.");
        define(LifeGoalType.RESTORE_FAMILY_HONOR, "Restore family honor", Map.of(
                TraitAxis.AMBITION, +0.4f,
                TraitAxis.COURAGE, +0.3f),
                null, allAdult(), 7, 1, "My family's name will be clean again.");

        // ── Spiritual / cultural ─────────────────────────────────────────
        define(LifeGoalType.PILGRIMAGE, "Make a pilgrimage", Map.of(
                TraitAxis.TEMPERANCE, +0.3f,
                TraitAxis.COMPASSION, +0.2f),
                null, allAdult(), 5, 1, "Once before I die.");
        define(LifeGoalType.AUTHOR_BOOK, "Write a book", Map.of(
                TraitAxis.AMBITION, +0.4f),
                Skill.LITERACY, EnumSet.of(LifeGoalDefinition.Stage.ADULT,
                        LifeGoalDefinition.Stage.ELDERLY), 6, 1,
                "I have something worth writing down.");
        define(LifeGoalType.TEACH_APPRENTICE, "Train an apprentice", Map.of(
                TraitAxis.COMPASSION, +0.4f,
                TraitAxis.INDUSTRY, +0.3f),
                null, EnumSet.of(LifeGoalDefinition.Stage.ADULT,
                        LifeGoalDefinition.Stage.ELDERLY), 5, 1,
                "I'll see someone trained as I was.");

        // ── Location ─────────────────────────────────────────────────────
        define(LifeGoalType.MOVE_TO_VILLAGE, "Move to another village", Map.of(
                TraitAxis.AMBITION, +0.4f,
                TraitAxis.SOCIABILITY, +0.2f),
                null, allAdult(), 5, 1, "I'll settle elsewhere.");
        define(LifeGoalType.VISIT_VILLAGES, "See other villages", Map.of(
                TraitAxis.SOCIABILITY, +0.3f,
                TraitAxis.AMBITION, +0.2f),
                null, allAdult(), 3, 3, "I want to see more of the world.");

        // ── Survival / dark ──────────────────────────────────────────────
        define(LifeGoalType.OUTLIVE_RIVAL, "Outlive a rival", Map.of(
                TraitAxis.COMPASSION, -0.4f,
                TraitAxis.TEMPERANCE, -0.3f),
                null, allAdult(), 5, 1, "I'll dance on their grave.");
        define(LifeGoalType.DIE_WITH_HONOR, "Die with honor", Map.of(
                TraitAxis.COURAGE, +0.7f,
                TraitAxis.AMBITION, +0.4f),
                null, EnumSet.of(LifeGoalDefinition.Stage.ELDERLY), 8, 1,
                "One great deed before the end.");
    }

    private static EnumSet<LifeGoalDefinition.Stage> allAdult() {
        return EnumSet.of(LifeGoalDefinition.Stage.ADULT, LifeGoalDefinition.Stage.ELDERLY);
    }

    private static void define(LifeGoalType type, String label,
                               Map<TraitAxis, Float> weights, Skill skillBonus,
                               Set<LifeGoalDefinition.Stage> stages,
                               int importance, int targetCount, String narrative) {
        DEFS.put(type, new LifeGoalDefinition(
                type, label, weights, skillBonus, stages, importance, targetCount, narrative));
    }
}
