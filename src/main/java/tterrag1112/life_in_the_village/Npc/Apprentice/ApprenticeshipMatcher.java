package tterrag1112.life_in_the_village.Npc.Apprentice;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.LifeGoal.LifeGoal;
import tterrag1112.life_in_the_village.Npc.LifeGoal.LifeGoalType;
import tterrag1112.life_in_the_village.Npc.Relations.RelationshipMode;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.ProfessionSkills;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Discovery + master-side decision (spec lines 89-119).
 *
 * <p>{@link #findMaster} scans the candidate's village for eligible
 * masters of the candidate's preferred profession, ranks them by
 * relationship + a small competence bonus, and returns the best.
 * {@link #masterAccepts} runs the master-side decision based on
 * relationship, current apprentice load, and trait bias.</p>
 *
 * <p>Phase 2 derives "preferred profession" from the candidate's
 * own profession when it isn't NONE/CITIZEN, else from their
 * highest-XP skill mapped via {@link ProfessionSkills}. Spec line 94
 * mentions a {@code preferredProfession} field; that field doesn't
 * exist yet, so this proxy keeps the discovery path working
 * (documented in 16 Revision Notes).</p>
 */
public final class ApprenticeshipMatcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Skill threshold for an NPC to count as a "master". */
    public static final int MASTER_SKILL_THRESHOLD =
            ApprenticeshipContract.MASTER_SKILL_THRESHOLD;
    /** Master scoring: relationship weight. */
    public static final float REL_WEIGHT = 0.3f;
    /** Master scoring: skill-above-threshold weight. */
    public static final float SKILL_WEIGHT = 0.05f;
    /** Master scoring: master-already-has-apprentice penalty. */
    public static final float CROWD_PENALTY = -1.0f;
    /** Master decision threshold: rejection cutoff. */
    public static final float ACCEPT_FLOOR = -0.4f;
    /** Phase 6.4.2.2 — LifeGoal alignment bonus on scoreMaster. */
    public static final double LIFEGOAL_SKILL_BONUS    = 5.0;
    public static final double LIFEGOAL_BUSINESS_BONUS = 3.0;

    private ApprenticeshipMatcher() {}

    /**
     * Best-match master for {@code candidate}, or empty when no
     * eligible NPC qualifies. Spec line 92.
     */
    public static Optional<TownspersonMob> findMaster(TownspersonMob candidate,
                                                      ServerLevel level) {
        Profession preferred = preferredProfessionFor(candidate);
        if (preferred == Profession.NONE || preferred == Profession.CITIZEN) {
            return Optional.empty();
        }
        VillageSavedData data = VillageSavedData.get(level);
        Village village = candidate.getAssignedVillageName()
                .flatMap(data::getVillageByName).orElse(null);
        if (village == null) return Optional.empty();

        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(level);
        Skill primary = ProfessionSkills.of(preferred)
                .map(ProfessionSkills::primary).orElse(Skill.CRAFTING);

        List<TownspersonMob> villagers = level.getEntitiesOfClass(
                TownspersonMob.class,
                candidate.getBoundingBox().inflate(128.0),
                m -> m.getProfession() == preferred
                        && !m.getUUID().equals(candidate.getUUID())
                        && m.getAssignedBuildingId().isPresent()
                        && m.getSkills().getLevel(primary) >= MASTER_SKILL_THRESHOLD
                        && m.getAssignedVillageName()
                                .map(name -> name.equals(village.getName())).orElse(false)
                        && reg.getActiveByMaster(m.getUUID()).size()
                                < ApprenticeshipContract.MAX_APPRENTICES_PER_MASTER);
        if (villagers.isEmpty()) return Optional.empty();

        Optional<TownspersonMob> picked = villagers.stream()
                .max(Comparator.comparingDouble(m -> scoreMaster(candidate, m, reg, primary)));
        // Phase 6.4.2.2 — log when LifeGoal alignment factored into the
        // pick. Fires per selection call (rare event), no flag needed.
        picked.ifPresent(m -> {
            double bonus = lifeGoalAlignmentBonus(candidate, m);
            if (bonus > 0) {
                LOGGER.info("[ApprenticeshipMatcher] {} selected master {} ({}) " +
                        "with +{} LifeGoal alignment bonus",
                        candidate.getNpcName(), m.getNpcName(),
                        m.getProfession(), bonus);
            }
        });
        return picked;
    }

    /**
     * Master-side accept/reject decision (spec line 106). Returns
     * {@code true} when the master is willing to take the
     * candidate. Compassion masters accept more candidates;
     * Ambition masters get pickier.
     */
    public static boolean masterAccepts(TownspersonMob master,
                                        TownspersonMob candidate,
                                        ApprenticeshipSavedData reg) {
        if (reg.getActiveByMaster(master.getUUID()).size()
                >= ApprenticeshipContract.MAX_APPRENTICES_PER_MASTER) return false;
        RelationshipMode mode = master.getNpcRelationships().getMode(candidate.getUUID());
        if (mode == RelationshipMode.GRUDGE || mode == RelationshipMode.FEUD) return false;

        float compassion = master.getTraitVector().get(TraitAxis.COMPASSION);
        float ambition = master.getTraitVector().get(TraitAxis.AMBITION);
        float bias = compassion * 0.5f - ambition * 0.4f;
        float relScore = relationshipBonus(mode);
        return (relScore + bias) >= ACCEPT_FLOOR;
    }

    private static double scoreMaster(TownspersonMob candidate,
                                      TownspersonMob master,
                                      ApprenticeshipSavedData reg,
                                      Skill primary) {
        double rel = REL_WEIGHT * relationshipBonus(
                candidate.getNpcRelationships().getMode(master.getUUID()));
        double skill = SKILL_WEIGHT * (master.getSkills().getLevel(primary)
                - MASTER_SKILL_THRESHOLD);
        double crowd = CROWD_PENALTY * reg.getActiveByMaster(master.getUUID()).size();
        // Phase 6.4.2.2 — LifeGoal alignment bonus. Magnitude dominates
        // relationship + skill so a goal-matched master wins over a
        // friendly stranger of a different profession. See bonus
        // constants for stacking shape.
        double lifeGoal = lifeGoalAlignmentBonus(candidate, master);
        return rel + skill + crowd + lifeGoal;
    }

    /**
     * Phase 6.4.2.2 — sums any LifeGoal-driven preference bonuses for
     * picking {@code master}. Active REACH_SKILL_LEVEL whose targetParam
     * matches the master's profession primary skill adds
     * {@link #LIFEGOAL_SKILL_BONUS}; active FOUND_BUSINESS whose
     * targetParam names the master's profession adds
     * {@link #LIFEGOAL_BUSINESS_BONUS}. Both can stack — wanting to
     * "master BAKING" and "found a BAKER business" both reinforce the
     * same master pick.
     */
    private static double lifeGoalAlignmentBonus(TownspersonMob candidate,
                                                 TownspersonMob master) {
        double bonus = 0;
        var goals = candidate.getLifeGoals();
        Profession masterProf = master.getProfession();
        if (masterProf == Profession.NONE || masterProf == Profession.CITIZEN) return 0;
        Skill masterPrimary = ProfessionSkills.of(masterProf)
                .map(ProfessionSkills::primary).orElse(null);

        Optional<LifeGoal> skillGoal = goals.activeByType(LifeGoalType.REACH_SKILL_LEVEL);
        if (skillGoal.isPresent() && masterPrimary != null) {
            Skill target = parseSkill(skillGoal.get().targetParam());
            if (target == masterPrimary) bonus += LIFEGOAL_SKILL_BONUS;
        }
        Optional<LifeGoal> businessGoal = goals.activeByType(LifeGoalType.FOUND_BUSINESS);
        if (businessGoal.isPresent()) {
            Profession target = parseProfession(businessGoal.get().targetParam());
            if (target == masterProf) bonus += LIFEGOAL_BUSINESS_BONUS;
        }
        return bonus;
    }

    private static Skill parseSkill(String name) {
        if (name == null || name.isEmpty()) return null;
        try { return Skill.valueOf(name); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static Profession parseProfession(String name) {
        if (name == null || name.isEmpty()) return Profession.NONE;
        try { return Profession.valueOf(name); }
        catch (IllegalArgumentException e) { return Profession.NONE; }
    }

    private static Profession professionForPrimarySkill(Skill target) {
        if (target == null) return Profession.NONE;
        for (Profession p : Profession.values()) {
            var ps = ProfessionSkills.of(p).orElse(null);
            if (ps != null && ps.primary() == target) return p;
        }
        return Profession.NONE;
    }

    private static float relationshipBonus(RelationshipMode mode) {
        return switch (mode) {
            case CLOSE_FRIEND -> 1.0f;
            case FRIEND -> 0.6f;
            case ACQUAINTANCE -> 0.2f;
            case NEUTRAL -> 0.0f;
            case RIVAL -> -0.4f;
            case GRUDGE, FEUD -> -1.0f;
        };
    }

    /**
     * Phase 6.4.2.2 — LifeGoal-aware preference, with the Phase-2
     * proxy as fallback. Resolution order:
     * <ol>
     *   <li>{@link LifeGoalType#REACH_SKILL_LEVEL} active — reverse-
     *       lookup profession whose primary skill is the goal's
     *       targetParam (e.g. "BAKING" → BAKER).</li>
     *   <li>{@link LifeGoalType#FOUND_BUSINESS} active — targetParam
     *       names the profession directly (e.g. "BAKER").</li>
     *   <li>Current profession when set and not NONE/CITIZEN.</li>
     *   <li>Reverse-lookup from the NPC's highest-XP skill.</li>
     * </ol>
     *
     * <p>This is the apprentice's "what do I want to learn?" lookup;
     * {@link #findMaster} uses it to filter candidate masters by
     * profession.</p>
     */
    public static Profession preferredProfessionFor(TownspersonMob npc) {
        var goals = npc.getLifeGoals();
        Optional<LifeGoal> skillGoal = goals.activeByType(LifeGoalType.REACH_SKILL_LEVEL);
        if (skillGoal.isPresent()) {
            Profession fromSkill = professionForPrimarySkill(
                    parseSkill(skillGoal.get().targetParam()));
            if (fromSkill != Profession.NONE) return fromSkill;
        }
        Optional<LifeGoal> businessGoal = goals.activeByType(LifeGoalType.FOUND_BUSINESS);
        if (businessGoal.isPresent()) {
            Profession fromBusiness = parseProfession(businessGoal.get().targetParam());
            if (fromBusiness != Profession.NONE) return fromBusiness;
        }

        Profession current = npc.getProfession();
        if (current != Profession.NONE && current != Profession.CITIZEN) return current;
        Skill primary = npc.getSkills().primary();
        return professionForPrimarySkill(primary);
    }

    /**
     * Convenience: returns true when {@code id} is currently an
     * apprentice or master in any active contract. Used by verb
     * gating.
     */
    public static boolean isInvolvedInActiveContract(UUID id, ApprenticeshipSavedData reg) {
        return reg.getByApprentice(id).isPresent()
                || !reg.getActiveByMaster(id).isEmpty();
    }
}
