package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Track E1 prompt-4 — strategy-scoped nucleus configuration.
 *
 * <p>Declares which anchors and building types act as nuclei
 * (civic / rural / resource / sacred / gateway), which buildings
 * are pulled toward which nucleus kind, and which building pairs
 * should keep distance from each other.
 *
 * <p>Strategies pass {@code null} for the {@code nucleusRules}
 * field to inherit the inclination default from
 * {@link #forInclination(Inclination, LayoutTopology)}; strategies
 * with unusual placement needs override.
 *
 * @param civicNucleus       what counts as the civic nucleus
 *                           (default: the strategy's primary anchor)
 * @param resourceNucleus    optional resource nucleus; null when
 *                           the strategy has no resource core
 * @param sacredNucleus      optional sacred nucleus; null when the
 *                           strategy has no sacred core
 * @param ruralNucleusTypes  building types whose instances act as
 *                           rural nuclei (typically FARMHOUSE for
 *                           AGRICULTURAL strategies; empty otherwise)
 * @param affinities         per-building pulls. Buildings not in
 *                           this map don't get a nucleus pull and
 *                           rely on base terrain + road frontage
 *                           bonus only.
 * @param penalties          symmetric proximity penalties between
 *                           building-type pairs
 */
public record NucleusRules(
        NucleusRef civicNucleus,
        NucleusRef resourceNucleus,
        NucleusRef sacredNucleus,
        Set<BuildingType> ruralNucleusTypes,
        Map<BuildingType, NucleusAffinity> affinities,
        List<ProximityPenalty> penalties) {

    public NucleusRules {
        if (civicNucleus == null) civicNucleus = new NucleusRef.PrimaryAnchorRef();
        ruralNucleusTypes = ruralNucleusTypes == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(ruralNucleusTypes));
        affinities = affinities == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(affinities));
        penalties = penalties == null
                ? List.of()
                : List.copyOf(penalties);
    }

    /** Empty-everything; used as a degenerate fallback for the
     *  cluster-fallback strategies whose intent is "place anywhere
     *  that fits." */
    public static NucleusRules empty() {
        return new NucleusRules(new NucleusRef.PrimaryAnchorRef(),
                null, null, Set.of(), Map.of(), List.of());
    }

    /**
     * Inclination-default rules. Most strategies inherit these
     * via the registry's {@code withDefaults} helper; strategies
     * with unusual placement needs (DEFENSIVE einzelhof, SACRED
     * isolated shrine) override.
     */
    public static NucleusRules forInclination(Inclination inc,
                                              LayoutTopology topology) {
        return switch (inc) {
            case AGRICULTURAL -> agricultural();
            case RESIDENTIAL  -> residential();
            case CIVIC        -> civic();
            case INDUSTRIAL   -> industrial();
            case SACRED       -> sacred();
            case DEFENSIVE    -> defensive(topology);
        };
    }

    // =========================================================================
    // Per-inclination defaults
    // =========================================================================

    private static NucleusRules agricultural() {
        var aff = new LinkedHashMap<BuildingType, NucleusAffinity>();
        // HOUSE prefers a rural nucleus (a FARMHOUSE); falls back to
        // the civic core when no farmhouse exists in range.
        aff.put(BuildingType.HOUSE, NucleusAffinity.withFallback(
                NucleusKind.RURAL, 0.85, 12, 30,
                NucleusAffinity.of(NucleusKind.CIVIC, 0.5, 18, 35)));
        aff.put(BuildingType.TOWN_HALL,  NucleusAffinity.of(NucleusKind.CIVIC, 1.0, 0, 8));
        aff.put(BuildingType.MARKET,     NucleusAffinity.of(NucleusKind.CIVIC, 0.9, 0, 12));
        aff.put(BuildingType.BLACKSMITH, NucleusAffinity.of(NucleusKind.CIVIC, 0.7, 6, 20));
        aff.put(BuildingType.BAKERY,     NucleusAffinity.of(NucleusKind.CIVIC, 0.7, 6, 20));
        aff.put(BuildingType.INN,        NucleusAffinity.of(NucleusKind.CIVIC, 0.7, 6, 20));
        aff.put(BuildingType.CHAPEL,     NucleusAffinity.of(NucleusKind.CIVIC, 0.7, 6, 25));
        return new NucleusRules(
                new NucleusRef.PrimaryAnchorRef(), null, null,
                Set.of(BuildingType.FARMHOUSE), aff,
                List.of(new ProximityPenalty(BuildingType.HOUSE,
                        BuildingType.BLACKSMITH, 8, 0.8)));
    }

    private static NucleusRules residential() {
        var aff = new LinkedHashMap<BuildingType, NucleusAffinity>();
        // HOUSE dominates; tight cluster around the civic nucleus.
        aff.put(BuildingType.HOUSE,     NucleusAffinity.of(NucleusKind.CIVIC, 0.9, 8, 25));
        aff.put(BuildingType.TOWN_HALL, NucleusAffinity.of(NucleusKind.CIVIC, 1.0, 0, 8));
        aff.put(BuildingType.INN,       NucleusAffinity.of(NucleusKind.CIVIC, 0.6, 6, 20));
        return new NucleusRules(
                new NucleusRef.PrimaryAnchorRef(), null, null,
                Set.of(), aff, List.of());
    }

    private static NucleusRules civic() {
        var aff = new LinkedHashMap<BuildingType, NucleusAffinity>();
        // Civic-core cluster: TOWN_HALL on the anchor, support
        // buildings within ~10 blocks. HOUSE rings outside the
        // civic core, idealDistance 18 (outside the typical
        // ANGERDORF ring radius).
        aff.put(BuildingType.TOWN_HALL,  NucleusAffinity.of(NucleusKind.CIVIC, 1.0, 0, 8));
        aff.put(BuildingType.MARKET,     NucleusAffinity.of(NucleusKind.CIVIC, 0.9, 6, 18));
        aff.put(BuildingType.INN,        NucleusAffinity.of(NucleusKind.CIVIC, 0.9, 6, 18));
        aff.put(BuildingType.CHAPEL,     NucleusAffinity.of(NucleusKind.CIVIC, 0.8, 6, 22));
        aff.put(BuildingType.BLACKSMITH, NucleusAffinity.of(NucleusKind.CIVIC, 0.7, 8, 22));
        aff.put(BuildingType.BAKERY,     NucleusAffinity.of(NucleusKind.CIVIC, 0.7, 6, 20));
        aff.put(BuildingType.HOUSE,      NucleusAffinity.of(NucleusKind.CIVIC, 0.7, 18, 35));
        return new NucleusRules(
                new NucleusRef.PrimaryAnchorRef(), null, null,
                Set.of(), aff,
                List.of(new ProximityPenalty(BuildingType.HOUSE,
                                BuildingType.BLACKSMITH, 6, 0.8),
                        new ProximityPenalty(BuildingType.CHAPEL,
                                BuildingType.BLACKSMITH, 12, 1.0)));
    }

    private static NucleusRules industrial() {
        var aff = new LinkedHashMap<BuildingType, NucleusAffinity>();
        // RESOURCE core (the cliff_face / forest_edge primary):
        // MINE / WOODCUTTER on top, workshop-class buildings
        // within ~8 blocks. HOUSE goes to the CIVIC (secondary
        // flat_fertile) zone, kept well clear of MINE.
        aff.put(BuildingType.MINE,       NucleusAffinity.of(NucleusKind.RESOURCE, 1.0, 0, 8));
        aff.put(BuildingType.WOODCUTTER, NucleusAffinity.of(NucleusKind.RESOURCE, 1.0, 0, 8));
        aff.put(BuildingType.BLACKSMITH, NucleusAffinity.of(NucleusKind.RESOURCE, 0.8, 8, 20));
        aff.put(BuildingType.CARPENTRY,  NucleusAffinity.of(NucleusKind.RESOURCE, 0.8, 8, 20));
        aff.put(BuildingType.STOCKPILE,  NucleusAffinity.of(NucleusKind.RESOURCE, 0.6, 6, 18));
        aff.put(BuildingType.TOWN_HALL,  NucleusAffinity.of(NucleusKind.CIVIC, 0.9, 0, 8));
        aff.put(BuildingType.INN,        NucleusAffinity.of(NucleusKind.CIVIC, 0.8, 4, 16));
        aff.put(BuildingType.MARKET,     NucleusAffinity.of(NucleusKind.CIVIC, 0.8, 4, 16));
        aff.put(BuildingType.HOUSE,      NucleusAffinity.of(NucleusKind.CIVIC, 0.8, 10, 28));
        return new NucleusRules(
                // Civic nucleus is whichever secondary anchor exists
                // (placer treats null civicNucleus.resolve as
                // "fall back to primary"); resource nucleus is the
                // strategy's primary.
                new NucleusRef.PrimaryAnchorRef(),
                new NucleusRef.PrimaryAnchorRef(), null,
                Set.of(), aff,
                List.of(new ProximityPenalty(BuildingType.HOUSE,
                        BuildingType.MINE, 20, 1.2)));
    }

    private static NucleusRules sacred() {
        var aff = new LinkedHashMap<BuildingType, NucleusAffinity>();
        // SACRED core (the primary anchor): CHAPEL / SHRINE on top.
        // INN at moderate distance (pilgrim accommodation).
        // HOUSE rings well outside.
        aff.put(BuildingType.CHAPEL,    NucleusAffinity.of(NucleusKind.SACRED, 1.0, 0, 8));
        aff.put(BuildingType.SHRINE,    NucleusAffinity.of(NucleusKind.SACRED, 1.0, 0, 8));
        aff.put(BuildingType.INN,       NucleusAffinity.of(NucleusKind.SACRED, 0.7, 12, 24));
        aff.put(BuildingType.HOUSE,     NucleusAffinity.of(NucleusKind.SACRED, 0.6, 18, 36));
        return new NucleusRules(
                new NucleusRef.PrimaryAnchorRef(), null,
                new NucleusRef.PrimaryAnchorRef(),
                Set.of(), aff,
                List.of(new ProximityPenalty(BuildingType.CHAPEL,
                                BuildingType.BLACKSMITH, 15, 1.2),
                        new ProximityPenalty(BuildingType.SHRINE,
                                BuildingType.BLACKSMITH, 15, 1.2),
                        new ProximityPenalty(BuildingType.SHRINE,
                                BuildingType.HOUSE, 8, 0.6)));
    }

    private static NucleusRules defensive(LayoutTopology topology) {
        var aff = new LinkedHashMap<BuildingType, NucleusAffinity>();
        // DEFENSIVE: TOWN_HALL / CASTLE = the keep (civic core).
        // HOUSE concentrates at GATEWAY positions (settlement-at-
        // gates pattern). INN also gateway-adjacent. Support
        // buildings (BARRACKS, TREASURY) inside the keep.
        aff.put(BuildingType.TOWN_HALL, NucleusAffinity.of(NucleusKind.CIVIC, 1.0, 0, 10));
        aff.put(BuildingType.CASTLE,    NucleusAffinity.of(NucleusKind.CIVIC, 1.0, 0, 10));
        aff.put(BuildingType.TREASURY,  NucleusAffinity.of(NucleusKind.CIVIC, 0.9, 4, 12));
        aff.put(BuildingType.HOUSE,     NucleusAffinity.withFallback(
                NucleusKind.GATEWAY, 0.9, 6, 18,
                NucleusAffinity.of(NucleusKind.CIVIC, 0.5, 25, 45)));
        aff.put(BuildingType.INN,       NucleusAffinity.of(NucleusKind.GATEWAY, 0.8, 10, 24));
        aff.put(BuildingType.STABLE,    NucleusAffinity.of(NucleusKind.GATEWAY, 0.7, 8, 20));
        aff.put(BuildingType.MARKET,    NucleusAffinity.of(NucleusKind.GATEWAY, 0.7, 8, 22));
        return new NucleusRules(
                new NucleusRef.PrimaryAnchorRef(), null, null,
                Set.of(), aff,
                List.of(new ProximityPenalty(BuildingType.HOUSE,
                        BuildingType.TOWN_HALL, 12, 0.9)));
    }
}
