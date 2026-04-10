package tterrag1112.life_in_the_village.Village.Buildings.Inhabitants;

import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Declarative description of who lives or works in a building of a
 * given {@link tterrag1112.life_in_the_village.Village.Buildings.BuildingType}.
 *
 * <h3>Why this exists</h3>
 * Replaces the old village-type-level {@code starter_npcs} JSON list,
 * which decoupled NPCs from the buildings that should produce them.
 * That decoupling caused several persistent bugs:
 * <ul>
 *   <li>NPCs spawned for buildings that the planner didn't place,
 *       triggering the old emergency-placement pass.</li>
 *   <li>The household post-pass had to reverse-engineer "who lives where"
 *       from NPCs that were already spawned.</li>
 *   <li>Adding a new village type meant duplicating an NPC list across
 *       every type that contained the same buildings.</li>
 * </ul>
 *
 * <h3>Model</h3>
 * Each building type maps to one {@code BuildingInhabitantSpec}. The
 * spec contains zero or more {@link Inhabitant} entries describing the
 * NPCs the building produces when it is placed in a village. The
 * inhabitant populator walks each placed building, asks for its spec,
 * and spawns the inhabitants directly into the building.
 *
 * <h3>Specs vs. instances</h3>
 * Counts are evaluated per-building-instance — a village with three
 * houses gets three households, not one.
 */
public final class BuildingInhabitantSpec {

    // =========================================================================
    // Inhabitant entry
    // =========================================================================

    /**
     * One NPC produced by a building.
     *
     * @param profession        what they do for a living
     * @param familyRole        their default role in any household formed here
     * @param livesHere         true if they sleep in this building (HOUSE/INN/etc.)
     *                          false if they only work here and need a separate house
     * @param chance            0..1 probability of being spawned (1 = always)
     * @param spouseChance      0..1 probability of receiving a spouse, if applicable
     * @param childChanceEach   0..1 probability of EACH potential child slot
     *                          (up to {@code maxChildren})
     * @param maxChildren       upper bound on number of children
     */
    public record Inhabitant(
            Profession profession,
            FamilyRole familyRole,
            boolean    livesHere,
            float      chance,
            float      spouseChance,
            int        maxChildren,
            float      childChanceEach
    ) {
        // ── Convenience builders ─────────────────────────────────────────────

        /** A worker who lives in the building (e.g. innkeeper, stockpile keeper). */
        public static Inhabitant resident(Profession profession) {
            return new Inhabitant(profession, FamilyRole.HEAD,
                    true, 1.0f, 0.0f, 0, 0.0f);
        }

        /** A worker who needs separate housing (e.g. blacksmith, miller). */
        public static Inhabitant worker(Profession profession) {
            return new Inhabitant(profession, FamilyRole.HEAD,
                    false, 1.0f, 0.0f, 0, 0.0f);
        }

        /** A standard household with HEAD + spouse + children. */
        public static Inhabitant household(float spouseChance,
                                           int maxChildren,
                                           float childChanceEach) {
            return new Inhabitant(Profession.NONE, FamilyRole.HEAD,
                    true, 1.0f, spouseChance, maxChildren, childChanceEach);
        }

        /** A worker household — like household but with a profession. */
        public static Inhabitant workerHousehold(Profession profession,
                                                 float spouseChance,
                                                 int maxChildren,
                                                 float childChanceEach) {
            return new Inhabitant(profession, FamilyRole.HEAD,
                    true, 1.0f, spouseChance, maxChildren, childChanceEach);
        }
    }

    // =========================================================================
    // Spec body
    // =========================================================================

    private final List<Inhabitant> inhabitants;

    public BuildingInhabitantSpec(List<Inhabitant> inhabitants) {
        this.inhabitants = List.copyOf(inhabitants);
    }

    public List<Inhabitant> getInhabitants() { return inhabitants; }

    public boolean isEmpty() { return inhabitants.isEmpty(); }

    /** Empty spec — building produces no NPCs. */
    public static final BuildingInhabitantSpec EMPTY =
            new BuildingInhabitantSpec(Collections.emptyList());

    // =========================================================================
    // Builder for static initializers
    // =========================================================================

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<Inhabitant> list = new ArrayList<>();

        public Builder add(Inhabitant inh) { list.add(inh); return this; }

        public Builder resident(Profession p)             { return add(Inhabitant.resident(p)); }
        public Builder worker(Profession p)               { return add(Inhabitant.worker(p)); }
        public Builder household(float spouseChance,
                                 int maxChildren,
                                 float childChanceEach) {
            return add(Inhabitant.household(spouseChance, maxChildren, childChanceEach));
        }
        public Builder workerHousehold(Profession p, float spouseChance,
                                       int maxChildren, float childChanceEach) {
            return add(Inhabitant.workerHousehold(p, spouseChance, maxChildren, childChanceEach));
        }

        public BuildingInhabitantSpec build() {
            return new BuildingInhabitantSpec(list);
        }
    }
}