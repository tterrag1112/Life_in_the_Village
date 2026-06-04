package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

/**
 * Layout Rework — residential block internal-layout variant. Each reserved
 * residential block is arranged by one of these (see {@link ResidentialArranger}):
 * the variant decides where its houses sit and which way they face.
 *
 * <p>This pass ships {@link #STREET_ROW} + {@link #COURTYARD} (explicit house
 * arrangement only — the decorative render passes, internal lane / tofts / well
 * / borders, are staged follow-ups). The remaining values are reserved slots so
 * the dispatch + auto-selection widen as variants land; until then they are NOT
 * auto-selected and, if forced, fall back (never a silent no-op — see
 * {@link ResidentialArranger#arrange}).
 */
public enum ResidentialVariant {
    /** Houses in two rows facing a central lane along the block's long axis. */
    STREET_ROW,
    /** Houses ringing a central open yard, facing inward. */
    COURTYARD,

    // ── Reserved (later passes) — not auto-selected; forced → fallback. ──
    CLUSTER,
    GREEN,
    GRID_BLOCKS,
    TERRACE,
    HILLSIDE;

    /** Parses a command argument to a variant, or null for unknown/absent
     *  (→ auto-select). Case-insensitive. */
    public static ResidentialVariant parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
