package tterrag1112.life_in_the_village.Npc.Office;

import com.mojang.serialization.Codec;

/**
 * How an office's holder is chosen. Phase 0 implements only
 * {@link #MERITOCRATIC} (highest skilled wins) as a stub; the rest are
 * defined here so {@link OfficeDefinition} entries can reference them
 * and Phase 3 can fill in behaviour without schema changes.
 */
public enum SelectionMethod {
    /** Highest eligible skill wins. */
    MERITOCRATIC,
    /** Must hold a subordinate office for a minimum tenure. */
    ASCENSION,
    /** Internal vote among a named pool. */
    COUNCIL,
    /** All citizens vote, weighted by relationships. */
    ELECTIVE,
    /** Passes to the named heir; succession crisis if none. */
    HEREDITARY,
    /** Superior office holder appoints. */
    APPOINTED,
    /** One office appoints all others; comes with a stability cost. */
    DICTATORIAL;

    public static final Codec<SelectionMethod> CODEC =
            Codec.STRING.xmap(SelectionMethod::valueOf, SelectionMethod::name);
}
