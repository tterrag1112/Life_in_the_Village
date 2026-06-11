package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

/**
 * Layout Rework — residential block internal-layout variant. Each reserved
 * residential block is arranged by one of these (see {@link ResidentialArranger}):
 * the variant decides where its houses sit and which way they face.
 *
 * <p>A1 stage 1 — live set: {@link #STREET_ROW}, {@link #COURTYARD},
 * {@link #GREEN}, {@link #CLUSTER}, {@link #GRID_BLOCKS}. The variant is
 * auto-selected per block by piece size + seed (mixed across a village's
 * blocks — see {@code PhasedPlanner.chooseVariant}); the {@code /litv district}
 * command's explicit variant overrides. A1 stage 2 adds {@link #TERRACE}
 * (attached row-house segments from the authored {@code row_house} pieces) —
 * auto-selected only when the pieces are authored for the village's culture
 * chain; forced without pieces falls back to a street row. {@link #HILLSIDE}
 * is deferred — reserved: NOT auto-selected and, if forced, falls back to a
 * street row (never a silent no-op — see {@link ResidentialArranger#arrange}).
 */
public enum ResidentialVariant {
    /** Houses in two rows facing a central lane along the block's long axis. */
    STREET_ROW,
    /** Houses ringing a central open yard (well + fenced borders), facing inward. */
    COURTYARD,
    /** Haufendorf — irregular organic placement: jittered positions off a
     *  relaxed grid, short lane spurs from a central knot to each house. */
    CLUSTER,
    /** Angerdorf — houses ring a central communal green (open lawn + scattered
     *  flora, optional well); the lane skirts the green's perimeter. */
    GREEN,
    /** BSP streets + alleys: internal street grid (VILLAGE_PATH) with alley
     *  cuts (FOOTPATH); houses fill the cells facing the internal streets. */
    GRID_BLOCKS,
    /** Attached row-house segments (A1 stage 2): one or two flush runs of
     *  LEFT cap + interior pieces + RIGHT cap (shared walls by construction,
     *  each segment its own HOUSE/home) fronting a central lane. Requires the
     *  authored {@code row_house} piece set; otherwise street-row fallback. */
    TERRACE,

    // ── Reserved (later passes) — not auto-selected; forced → fallback. ──
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
