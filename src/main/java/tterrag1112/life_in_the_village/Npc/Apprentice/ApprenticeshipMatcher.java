package tterrag1112.life_in_the_village.Npc.Apprentice;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
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

        return villagers.stream()
                .max(Comparator.comparingDouble(m -> scoreMaster(candidate, m, reg, primary)));
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
        return rel + skill + crowd;
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
     * Phase 2 proxy for the spec's {@code preferredProfession}: use
     * the NPC's current profession when set, otherwise reverse-lookup
     * profession from the highest-XP skill.
     */
    public static Profession preferredProfessionFor(TownspersonMob npc) {
        Profession current = npc.getProfession();
        if (current != Profession.NONE && current != Profession.CITIZEN) return current;
        Skill primary = npc.getSkills().primary();
        // Reverse lookup: pick first profession whose primary matches.
        for (Profession p : Profession.values()) {
            var ps = ProfessionSkills.of(p).orElse(null);
            if (ps != null && ps.primary() == primary) return p;
        }
        return Profession.NONE;
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
