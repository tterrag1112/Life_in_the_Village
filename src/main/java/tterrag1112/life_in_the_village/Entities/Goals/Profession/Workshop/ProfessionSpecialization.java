package tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop;

import net.minecraft.world.item.Item;

/**
 * Marker interface for profession specialization enums.
 *
 * <p>A specialization narrows what a production NPC actually makes without
 * introducing new building types or new goal classes. It is stored alongside
 * the NPC's {@link tterrag1112.life_in_the_village.Profession.Roles.ProfessionRole}
 * but in a separate NBT slot, so an NPC can have both a role (e.g.
 * {@code PRODUCER}) and a specialization (e.g. {@code TOOLSMITH}) at once.</p>
 *
 * <h3>Why this exists</h3>
 * The "Life in the Village" design principle is <em>specialization via
 * systems, not buildings</em>. Rather than adding a separate "Armory" or
 * "Weaponsmith" building, a single {@code BLACKSMITH} building can host
 * workers with different specializations. The goal class consults the
 * specialization to filter its recipe pool.
 *
 * <h3>Adding a new specialization</h3>
 * <pre>
 * public enum BlacksmithSpecialization implements ProfessionSpecialization {
 *     GENERALIST, TOOLSMITH, ARMORER, WEAPONSMITH;
 *
 *     static {
 *         SpecializationManager.register("BlacksmithSpecialization",
 *                                         BlacksmithSpecialization::valueOf);
 *     }
 *
 *     &#64;Override
 *     public boolean allowsOutput(Item item) { ... }
 *
 *     &#64;Override
 *     public String displayName() { ... }
 * }
 * </pre>
 *
 * <h3>Reading a specialization in a goal</h3>
 * <pre>
 * BlacksmithSpecialization spec = getSpecialization(BlacksmithSpecialization.class);
 * if (spec == null) spec = BlacksmithSpecialization.GENERALIST;
 * if (!spec.allowsOutput(recipe.output())) continue;
 * </pre>
 */
public interface ProfessionSpecialization {

    /** Enum constant name — provided automatically by all enums. */
    String name();

    /**
     * Returns true if this specialization permits producing the given item.
     * The default implementation allows everything — specializations that
     * restrict output should override.
     */
    default boolean allowsOutput(Item item) { return true; }

    /** Human-readable name for UI display. */
    String displayName();

    /**
     * True if this specialization is the "no specialization" default.
     * Generalists accept all recipes their profession can produce.
     */
    default boolean isGeneralist() { return false; }
}