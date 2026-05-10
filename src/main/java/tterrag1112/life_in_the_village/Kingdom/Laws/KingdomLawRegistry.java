package tterrag1112.life_in_the_village.Kingdom.Laws;

import tterrag1112.life_in_the_village.Kingdom.Capabilities.KingdomCapability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Track D3.4 — central registry of every defined kingdom-tier
 * {@link KingdomLaw}. Lookup by id; iteration order is stable
 * (insertion order in the registration block).
 *
 * <p>All eight legacy enum values are registered as
 * {@link ToggleLaw} instances with the same lowercase id as their
 * legacy {@code .name()} converted to {@code _}-separated lower
 * case (e.g. {@code KingdomLaw.TRADE_TARIFFS} → id
 * {@code "trade_tariffs"}). v1 also ships:
 * <ul>
 *   <li>{@code education_stipend} — {@link ScalarLaw}, 0..32
 *       bronze/day, default 0. Effect drains kingdom treasury
 *       per scholar village daily.</li>
 *   <li>{@code official_religion} — {@link EnumLaw}, choices
 *       {@code "none" / "state_religion" / "toleration"}. Effect
 *       gates ORDINATION_RIGHTS charters and modulates inter-
 *       religious-order conflict (Phase 6).</li>
 * </ul>
 *
 * <p>Static catalogue — never mutated at runtime. New laws
 * require code changes (this is intentional; data-driven law
 * registration is a Phase 7 polish target).
 */
public final class KingdomLawRegistry {

    private static final Map<String, KingdomLaw> CATALOGUE = new LinkedHashMap<>();

    private KingdomLawRegistry() {}

    static {
        // ── Eight legacy ToggleLaw migrations ────────────────────────────────
        register(ToggleLaw.of("open_borders",
                "Open Borders",
                "Citizens of other kingdoms may travel freely.",
                KingdomLawCategory.DIPLOMACY));

        register(new ToggleLaw("trade_tariffs",
                "Trade Tariffs",
                "Outsiders pay extra (+20%) on trades; receive less (-20%) when selling.",
                KingdomLawCategory.ECONOMY,
                KingdomLawScope.KINGDOM,
                EnactmentCost.ZERO,
                KingdomCapability.PASS_LAW,
                List.of()));

        register(ToggleLaw.of("conscription",
                "Conscription",
                "Able-bodied citizens may be called to guard duty.",
                KingdomLawCategory.MILITARY));

        register(ToggleLaw.of("price_controls",
                "Price Controls",
                "Merchant prices are regulated by the crown.",
                KingdomLawCategory.ECONOMY));

        register(ToggleLaw.of("property_rights",
                "Property Rights",
                "Only kingdom citizens may own buildings.",
                KingdomLawCategory.LAND));

        register(ToggleLaw.of("free_trade",
                "Free Trade",
                "All trade restrictions are lifted.",
                KingdomLawCategory.ECONOMY));

        register(ToggleLaw.of("kings_peace",
                "King's Peace",
                "Violence among citizens is criminal regardless of citizenship.",
                KingdomLawCategory.CRIME));

        register(ToggleLaw.of("minimum_wage",
                "Minimum Wage",
                "All hired workers must be paid at least 8 bronze per day.",
                KingdomLawCategory.ECONOMY));

        // ── ScalarLaw example ────────────────────────────────────────────────
        // EDUCATION_STIPEND: per-day kingdom-treasury debit per scholar-village.
        // Demonstrates the ScalarLaw archetype + Chancellor enactment authority.
        register(new ScalarLaw(
                "education_stipend",
                "Education Stipend",
                "Daily bronze paid from the kingdom treasury per village hosting a Scholar.",
                KingdomLawCategory.EDUCATION,
                KingdomLawScope.KINGDOM,
                EnactmentCost.ZERO,
                KingdomCapability.ISSUE_DECREE,
                List.of(),
                /*min=*/        0.0,
                /*max=*/        32.0,
                /*step=*/       4.0,
                /*unit=*/       "bronze/day",
                /*default=*/    0.0));

        // ── EnumLaw example ──────────────────────────────────────────────────
        // OFFICIAL_RELIGION: gates ORDINATION_RIGHTS charters (slice 2) and
        // signals Phase 6's religious-political conflict shape.
        register(new EnumLaw(
                "official_religion",
                "Official Religion",
                "Kingdom's stance on religious orders and ordination.",
                KingdomLawCategory.RELIGION,
                KingdomLawScope.KINGDOM,
                EnactmentCost.ZERO,
                KingdomCapability.ISSUE_DECREE,
                List.of(),
                List.of("none", "state_religion", "toleration"),
                "none"));
    }

    private static void register(KingdomLaw law) {
        if (CATALOGUE.containsKey(law.id())) {
            throw new IllegalStateException("Duplicate KingdomLaw id: " + law.id());
        }
        CATALOGUE.put(law.id(), law);
    }

    /** Lookup by id; throws if unknown. Use sparingly — prefer {@link #find}. */
    public static KingdomLaw byId(String id) {
        KingdomLaw law = CATALOGUE.get(id);
        if (law == null) {
            throw new IllegalArgumentException("Unknown KingdomLaw id: " + id);
        }
        return law;
    }

    public static java.util.Optional<KingdomLaw> find(String id) {
        return java.util.Optional.ofNullable(CATALOGUE.get(id));
    }

    /** Snapshot of every registered law in registration order. */
    public static List<KingdomLaw> all() {
        return List.copyOf(CATALOGUE.values());
    }

    /** Filtered snapshot by category. */
    public static List<KingdomLaw> byCategory(KingdomLawCategory category) {
        return CATALOGUE.values().stream()
                .filter(l -> l.category() == category)
                .toList();
    }

    public static Map<String, KingdomLaw> snapshot() {
        return Collections.unmodifiableMap(CATALOGUE);
    }
}
