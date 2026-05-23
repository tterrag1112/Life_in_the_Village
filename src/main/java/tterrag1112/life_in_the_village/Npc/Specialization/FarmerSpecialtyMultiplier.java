package tterrag1112.life_in_the_village.Npc.Specialization;

import net.minecraft.resources.Identifier;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

/**
 * Phase 6.3.3.j — static-helper extraction of the farmer-specialty
 * XP multiplier originally inlined as {@code FarmerBehavior.specialtyMultiplier}.
 *
 * <p>Non-profession grant sites (homestead handlers, child chores,
 * future cross-profession callers) need to compose the same +50%
 * on-spec bonus without routing through {@code FarmerBehavior}, so
 * the resolver is lifted to a static and {@code FarmerBehavior}
 * delegates to it. Composition order is unchanged: callers multiply
 * the base XP by {@link #of} before handing the boosted amount to
 * {@code SkillXp.award}, which then applies the mentee multiplier on
 * top.</p>
 *
 * <p>Bias-not-gate semantics from 6.3.3.i.4:
 * <ul>
 *   <li>{@code FARMER_CROP_FOCUS}  → +50% on {@code CROP_FARMING} and
 *       {@code ORCHARDING}</li>
 *   <li>{@code FARMER_ANIMAL_FOCUS} → +50% on {@code ANIMAL_HUSBANDRY}
 *       and {@code BEEKEEPING}</li>
 *   <li>{@code FARMER_MIXED} / no spec / mismatch → 1.0</li>
 * </ul>
 */
public final class FarmerSpecialtyMultiplier {

    public static final float SPEC_MATCH_MULTIPLIER = 1.5f;

    private FarmerSpecialtyMultiplier() {}

    /**
     * Returns the specialty multiplier for {@code npc} earning XP in
     * {@code target}. Returns {@code 1.0f} when {@code npc} has no
     * specialization, the specialization isn't a farmer focus, or the
     * target skill is outside the farmer-focus skill set.
     */
    public static float of(TownspersonMob npc, Skill target) {
        if (npc == null || target == null) return 1.0f;
        Identifier specId = npc.getSpecializationComponent()
                .currentId().orElse(null);
        if (specId == null) return 1.0f;
        boolean isCropTarget = target == Skill.CROP_FARMING
                || target == Skill.ORCHARDING;
        boolean isAnimalTarget = target == Skill.ANIMAL_HUSBANDRY
                || target == Skill.BEEKEEPING;
        if (isCropTarget
                && specId.equals(NpcSpecializationTypes.FARMER_CROP_FOCUS.name())) {
            return SPEC_MATCH_MULTIPLIER;
        }
        if (isAnimalTarget
                && specId.equals(NpcSpecializationTypes.FARMER_ANIMAL_FOCUS.name())) {
            return SPEC_MATCH_MULTIPLIER;
        }
        return 1.0f;
    }
}
