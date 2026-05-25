package tterrag1112.life_in_the_village.Npc.Aging;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;

/**
 * Phase 6.4.4.4 — probabilistic skill seeding on TEEN→ADULT transition.
 *
 * <p>Pre-6.4.4 family-role NPCs (SPOUSE, CHILD, ELDERLY) entered
 * adulthood with all-zero {@link tterrag1112.life_in_the_village.Npc
 * .Skills.SkillComponent} XP — meaning every emergent-specialty story
 * (homestead baking, side-trade smithing, hobby-driven skill drift) had
 * to grow from scratch. This seeder rolls a small chance of "started
 * with practice as a youth" XP per skill, so a fraction of every
 * cohort enters adulthood already capable of contributing to homestead
 * skill-as-behavior work.</p>
 *
 * <p>Base probabilities (per skill, independent rolls):</p>
 * <ul>
 *   <li>BAKING   — 8% — home cooking is common practice</li>
 *   <li>MILLING  — 5% — grindstones are specialty; rarer drift</li>
 *   <li>CRAFTING — 10% — broadest skill; carving / mending / etc.</li>
 *   <li>FARMING  — 8% — homestead gardens are everywhere</li>
 * </ul>
 *
 * <p>Trait modifier: AMBITION and INDUSTRY above neutral each add up to
 * +10% (proportional to axis magnitude). A maxed-ambition, maxed-
 * industry youth has +20% on every roll above the base. Captures
 * "diligent, driven youth practiced more during their teen years."</p>
 *
 * <p>XP magnitude when a roll succeeds: 1-15 XP, biased low via
 * min-of-two-rolls. Mean ≈ 5. Enough to give the NPC a head start;
 * not enough to skip the apprenticeship arc.</p>
 *
 * <p>Synergy with 6.4.1.3.A: {@link SkillXp#award} applies the
 * AMBITION multiplier, so ambitious NPCs also retain slightly more
 * of the seeded XP — same trait reinforces both probability and
 * retention.</p>
 */
public final class FamilySkillSeeder implements EventDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double BAKING_BASE   = 0.08;
    private static final double MILLING_BASE  = 0.05;
    private static final double CRAFTING_BASE = 0.10;
    private static final double FARMING_BASE  = 0.08;

    /** Max trait-bonus per axis. AMBITION = +1 → +10%; same for INDUSTRY. */
    private static final double TRAIT_BONUS_PER_AXIS = 0.10;

    @Override public String name() { return "family_skill_seeder"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        if (!(event instanceof NpcLifeEvent.LifeStageAdvanced lsa)) return;
        if (!"ADULT".equalsIgnoreCase(lsa.newStage())) return;
        TownspersonMob npc = lsa.subject();
        if (npc == null) return;
        if (!(npc.level() instanceof ServerLevel sl)) return;

        var traits = npc.getTraitVector();
        float ambition = traits.get(TraitAxis.AMBITION);
        float industry = traits.get(TraitAxis.INDUSTRY);
        double traitMod = Math.max(0f, ambition) * TRAIT_BONUS_PER_AXIS
                        + Math.max(0f, industry) * TRAIT_BONUS_PER_AXIS;

        RandomSource rng = sl.getRandom();
        long now = sl.getGameTime();

        trySeed(npc, Skill.BAKING,   BAKING_BASE   + traitMod, rng, now);
        trySeed(npc, Skill.MILLING,  MILLING_BASE  + traitMod, rng, now);
        trySeed(npc, Skill.CRAFTING, CRAFTING_BASE + traitMod, rng, now);
        trySeed(npc, Skill.FARMING,  FARMING_BASE  + traitMod, rng, now);
    }

    private static void trySeed(TownspersonMob npc, Skill skill, double prob,
                                RandomSource rng, long now) {
        if (rng.nextDouble() >= prob) return;
        // Biased-low XP via min-of-two-uniform: skews toward 1-7 range,
        // top end (14) is rare. Average ≈ 5 XP.
        int xp = 1 + Math.min(rng.nextInt(15), rng.nextInt(15));
        SkillXp.award(npc, skill, xp, now);
        LOGGER.info("[FamilySkillSeeder] {} adulthood: seeded {} +{} XP " +
                "(roll succeeded at prob={})",
                npc.getNpcName(), skill, xp, String.format("%.2f", prob));
    }
}
