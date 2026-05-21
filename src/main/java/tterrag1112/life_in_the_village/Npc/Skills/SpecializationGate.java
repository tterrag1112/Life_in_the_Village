package tterrag1112.life_in_the_village.Npc.Skills;

import java.util.List;

/**
 * Phase 6.3.2.b — scaffold for the 6.3.2.c specialization-promotion
 * pipeline. A gate aggregates {@link SkillRequirement}s; the
 * {@link #check} method returns true iff every requirement is met by
 * the supplied {@link SkillComponent}.
 *
 * <p>Not yet wired to any specialization. 6.3.2.c will create gates
 * for BlacksmithSpecialization (Toolsmith → TOOLSMITHING ≥ N, etc.)
 * and the eventual ADVENTURER class gates (Swordsman → MELEE ≥ N,
 * Mage → MAGIC ≥ N, ...).
 */
public final class SpecializationGate {

    private final List<SkillRequirement> requirements;

    public SpecializationGate(List<SkillRequirement> requirements) {
        this.requirements = List.copyOf(requirements);
    }

    public boolean check(SkillComponent component) {
        for (SkillRequirement r : requirements) {
            if (!r.isSatisfiedBy(component)) return false;
        }
        return true;
    }

    public List<SkillRequirement> requirements() { return requirements; }

    /**
     * Phase 6.3.2.c — static convenience wrapper. Builds an ad-hoc gate
     * from the spec's requirements and evaluates against the NPC's
     * skill component.
     *
     * <p>Generalist specs always qualify regardless of skill state (the
     * "no real spec" default for new NPCs). Other specs check every
     * listed {@link SkillRequirement}; a missing requirement entry
     * means "no skill gate" (also always qualifies).
     */
    public static boolean qualifies(
            tterrag1112.life_in_the_village.Npc.Specialization.SpecializationDef spec,
            tterrag1112.life_in_the_village.Entities.custom.TownspersonMob npc) {
        if (spec == null || npc == null) return false;
        if (spec.isGeneralist()) return true;
        if (spec.requirements().isEmpty()) return true;
        return new SpecializationGate(spec.requirements()).check(npc.getSkills());
    }
}
