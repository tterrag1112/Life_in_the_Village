package tterrag1112.life_in_the_village.Npc.Skills;

/**
 * Phase 6.3.2.b — a single skill-level gate. Reads the recipient's
 * level for the named {@link Skill} and compares it against
 * {@link #minLevel}. The check does NOT auto-walk the parent chain:
 * a requirement on TOOLSMITHING is satisfied only by the TOOLSMITHING
 * level (which already includes propagation FROM children, but the
 * caller decides which skill is the gate).
 *
 * <p>Consumed by {@link SpecializationGate} from 6.3.2.c onward.
 */
public record SkillRequirement(Skill skill, int minLevel) {

    public boolean isSatisfiedBy(SkillComponent component) {
        return component.getLevel(skill) >= minLevel;
    }
}
