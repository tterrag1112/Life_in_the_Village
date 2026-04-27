package tterrag1112.life_in_the_village.Npc.Office;

import java.util.Optional;

/**
 * Phase 5 hook for letting cultures override an office's default
 * selection method (per spec section "Phase 3 + culture (government
 * forms)"):
 *
 * <pre>
 * Cultures define default selection methods for top offices. A
 * TRADITIONAL_MONARCHY culture uses HEREDITARY for king; a
 * MERIT_REPUBLIC uses ELECTIVE.
 * </pre>
 *
 * <p>Phase 3 wires the call site so Phase 5 only has to populate this
 * resolver. Until then every call returns {@link Optional#empty()} and
 * {@link OfficeElection} falls back on
 * {@link OfficeDefinition#defaultSelection}.</p>
 */
public final class CultureSelectionResolver {

    private CultureSelectionResolver() {}

    /**
     * Returns the culture's preferred selection method for the office,
     * or empty when no override applies. Phase 5 doc 31 wires this to
     * {@code CultureResolver.selectionFor}; the Phase 3 stub returned
     * empty unconditionally so existing callers fall back to
     * {@link OfficeDefinition#defaultSelection} when the culture has
     * no preference for this office.
     *
     * @param culture  the org's culture identifier (kingdom culture for
     *                 kingdom-typed offices; village culture otherwise).
     *                 May be {@code null} for orgs that have no culture
     *                 set yet.
     * @param officeId the office id being filled
     */
    public static Optional<SelectionMethod> cultureSelectionFor(String culture, String officeId) {
        return tterrag1112.life_in_the_village.Cultures.CultureResolver
                .selectionFor(culture, officeId);
    }
}
