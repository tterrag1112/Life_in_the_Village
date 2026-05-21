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
}
