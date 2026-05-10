package tterrag1112.life_in_the_village.Kingdom.Laws;

/**
 * Track D3.4 — scope at which a kingdom-tier law's effect applies.
 *
 * <ul>
 *   <li>{@link #KINGDOM} — global to every village in the kingdom.
 *       Today's eight ToggleLaw migrations are all KINGDOM scope.</li>
 *   <li>{@link #PROVINCE} — applies only inside a specific province.
 *       Reserved for Phase 4's province-scoped charters and laws;
 *       no v1 ToggleLaw uses it.</li>
 *   <li>{@link #VILLAGE} — applies only inside a specific village.
 *       The village-tier {@code VillageLaw} system already covers
 *       most of this surface; kingdom-scope village-only laws are
 *       rare (e.g. a "free port" charter on a single port village).
 *       Reserved.</li>
 * </ul>
 */
public enum KingdomLawScope {
    KINGDOM,
    PROVINCE,
    VILLAGE;
}
