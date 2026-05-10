package tterrag1112.life_in_the_village.Kingdom.Laws;

import com.mojang.serialization.Codec;
import tterrag1112.life_in_the_village.Kingdom.Capabilities.KingdomCapability;

import java.util.List;

/**
 * Track D3.4 — sealed root of the kingdom-tier law typology.
 * Replaces the flat {@link tterrag1112.life_in_the_village.Kingdom.KingdomLaw}
 * enum with a three-archetype interface; the legacy enum stays as
 * a back-compat id namespace and is migrated 1:1 into
 * {@link ToggleLaw} instances on first load.
 *
 * <p><b>Design note.</b> The new sealed interface lives in
 * {@code Kingdom.Laws.KingdomLaw} (this file); the legacy enum
 * stays at {@code Kingdom.KingdomLaw} for save compat and as the
 * keying namespace for existing call sites. New code uses
 * {@link KingdomLawRegistry} to resolve a law by id and
 * {@link KingdomLawInstance} to track per-kingdom state.
 *
 * <h3>Archetypes</h3>
 * <ul>
 *   <li>{@link ToggleLaw} — boolean state. The eight existing
 *       laws (OPEN_BORDERS, TRADE_TARIFFS, etc.) are all toggles.</li>
 *   <li>{@link ScalarLaw} — numeric-parameterized. Holder picks a
 *       value within {@code [min, max]} stepped by
 *       {@code step}; the law's effect reads the active value.</li>
 *   <li>{@link EnumLaw} — one-of-many state. Holder picks an
 *       option from {@code choices}.</li>
 * </ul>
 *
 * <h3>Lifecycle authority (per archetype)</h3>
 * Drafting always requires a Scholar competently held.
 * Enactment authority (which capability gates the
 * {@link KingdomLawState#PROPOSED} → {@link KingdomLawState#ACTIVE}
 * transition):
 * <ul>
 *   <li>{@link ToggleLaw} — King's PASS_LAW capability.
 *       (Magistrate also satisfies via the OR semantics from
 *       D3.3b.)</li>
 *   <li>{@link ScalarLaw} / {@link EnumLaw} — Chancellor's
 *       ISSUE_DECREE capability (per the prompt's "ruler proposes,
 *       Chancellor enacts" rule for parameterized laws).</li>
 * </ul>
 * Repeal mirrors enactment authority. Drafting is always at the
 * Scholar's gate.
 */
public sealed interface KingdomLaw permits ToggleLaw, ScalarLaw, EnumLaw {

    /** Stable canonical id; matches the legacy enum's lowercase name where applicable. */
    String id();

    /** Display name for the GUI / debug commands. */
    String displayName();

    /** Short tooltip / detail-view description. */
    String description();

    KingdomLawCategory category();

    KingdomLawScope scope();

    EnactmentCost enactmentCost();

    /**
     * Capability that gates the PROPOSED → ACTIVE transition. Repeal
     * uses the same capability. Drafting (DRAFT) is always
     * Scholar-gated and not surfaced here.
     */
    KingdomCapability enactmentCapability();

    /**
     * Optional list of other law-ids that must be ACTIVE for this
     * one to be drafted / enacted. Empty list (the default) means
     * no prerequisites. v1 ships with no prerequisites wired; the
     * field is here for Phase 4's extension.
     */
    default List<String> requiredActiveLaws() { return List.of(); }

    /** Identity used by {@link KingdomLawRegistry} for codec dispatch. */
    String archetype();

    /**
     * Codec for the sealed type. Dispatches on {@link #archetype()}
     * — encoded as "toggle" / "scalar" / "enum" — to the correct
     * sub-record codec. Delegates resolution to
     * {@link KingdomLawRegistry} so identical-id law instances
     * persist as their id alone (the registry is the source of
     * truth for static metadata; only dynamic state lives in
     * codec).
     */
    Codec<KingdomLaw> SELF_CODEC = Codec.STRING.xmap(
            KingdomLawRegistry::byId,
            KingdomLaw::id);
}
