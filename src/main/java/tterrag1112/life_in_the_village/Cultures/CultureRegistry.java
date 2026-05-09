package tterrag1112.life_in_the_village.Cultures;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureAestheticTokens;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureApprenticeshipNorms;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureEconomicNorms;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureHobbyWeights;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureLawDefaults;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureOfficeRules;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CulturePlanningBias;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureReligion;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureSchedule;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureVisitorAffinity;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Office.SelectionMethod;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionRegistry;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Npc.Visitor.VisitorType;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 5 doc 31 — registry of every {@link Culture} known to the
 * mod. Phase 5 ships the four hardcoded starter cultures
 * (Plainfolk / Highmarch / Silkwood / Tidereach) per spec lines
 * 137-247; Phase 6 swaps in JSON-driven custom cultures (spec line
 * 350).
 *
 * <p>Lookups are case-insensitive on {@link Culture#id} so existing
 * {@code Kingdom.getCulture()} strings (which the codebase stores
 * mixed-case from JSON) resolve cleanly.</p>
 */
public final class CultureRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Culture> REGISTRY = new LinkedHashMap<>();
    private static volatile boolean initialised = false;

    public static final String DEFAULT_ID = "default";

    private CultureRegistry() {}

    /** Returns the culture for {@code id}, or the neutral default
     *  when no match is found. */
    public static Culture getOrDefault(String id) {
        ensureInit();
        if (id == null) return REGISTRY.get(DEFAULT_ID);
        Culture c = REGISTRY.get(id.toLowerCase(Locale.ROOT));
        return c != null ? c : REGISTRY.get(DEFAULT_ID);
    }

    public static Optional<Culture> get(String id) {
        ensureInit();
        if (id == null) return Optional.empty();
        return Optional.ofNullable(REGISTRY.get(id.toLowerCase(Locale.ROOT)));
    }

    public static Collection<Culture> all() {
        ensureInit();
        return java.util.Collections.unmodifiableCollection(REGISTRY.values());
    }

    public static void register(Culture culture) {
        if (culture == null) return;
        REGISTRY.put(culture.id().toLowerCase(Locale.ROOT), culture);
    }

    public static synchronized void ensureInit() {
        if (initialised) return;
        registerStarterCultures();
        initialised = true;
        LOGGER.info("[CultureRegistry] Registered {} cultures", REGISTRY.size());
    }

    /** Used only by /culture set debug to swap a culture entry. */
    public static synchronized void replaceForTesting(Culture culture) {
        if (culture == null) return;
        REGISTRY.put(culture.id().toLowerCase(Locale.ROOT), culture);
    }

    // =========================================================================
    // Starter cultures — spec lines 135-247
    // =========================================================================

    private static void registerStarterCultures() {
        register(buildDefault());
        register(buildPlainfolk());
        register(buildHighmarch());
        register(buildSilkwood());
        register(buildTidereach());
    }

    private static Culture buildDefault() {
        return Culture.neutralDefault();
    }

    // ── Plainfolk ─────────────────────────────────────────────────────────

    private static Culture buildPlainfolk() {
        return new Culture(
                "plainfolk", "Plainfolk",
                CultureSchedule.DEFAULT,
                new CultureNaming("plainfolk", "plainfolk", "plainfolk", List.of()),
                new CultureTraitBias(Map.of(TraitAxis.INDUSTRY, +0.15f)),
                new CultureReligion(ReligionRegistry.SUNSTEAD,
                        List.of("HARVEST_THANKSGIVING", "OFFERING")),
                new CultureOfficeRules(
                        Map.of(
                                OfficeRegistry.VILLAGE_LEADER,    SelectionMethod.MERITOCRATIC,
                                OfficeRegistry.VILLAGE_CONSTABLE, SelectionMethod.APPOINTED,
                                OfficeRegistry.VILLAGE_SCRIBE,    SelectionMethod.APPOINTED),
                        Map.of(OfficeRegistry.VILLAGE_LEADER, 4 * 365),
                        /* councilDominant */ false,
                        /* heredityRespected */ false),
                new CultureLawDefaults(
                        List.of("SUBSIDIZE_FARMER"),
                        List.of("SUBSIDIZE_FARMER", "PARDON_FIRST_OFFENSE"),
                        List.of("BAN_EXECUTION", "FOREIGN_TRADER_BAN")),
                new CultureEconomicNorms(
                        /* giftPropensity */ 0.5f,
                        /* hagglingTendency */ 0.3f,
                        /* creditAccepted */ true,
                        /* luxuryDemand */ 0.3f,
                        Map.of()),
                new CultureHobbyWeights(Map.of(
                        "tend_garden",   1.6f,
                        "long_walk",     1.4f,
                        "inn_drink_talk", 1.3f,
                        "sword_practice", 0.4f,
                        "archery",       0.5f)),
                new CultureVisitorAffinity(Map.of(
                        VisitorType.PILGRIM,           1.2f,
                        VisitorType.TRAVELER,          1.0f,
                        VisitorType.MERCHANT_ITINERANT,1.0f)),
                new CultureApprenticeshipNorms(2 * 365, 2, true, 0.7f),
                new CultureAestheticTokens(
                        List.of("brown", "tan", "ochre", "natural_linen"),
                        List.of("straw_hat", "wool_scarf"),
                        "default", Optional.empty()),
                CulturePlanningBias.DEFAULT,
                // Track D1 — plainfolk: tribal confederation,
                // council-led, levy-heavy (most upkeep is local
                // labour, not coin or tribute).
                new CultureBundles.CultureKingdomDefaults(
                        List.of("Yeoman", "Headman", "Elder", "Chief"),
                        CultureBundles.SuccessionRule.COUNCIL,
                        CultureBundles.SubdivisionModel.TRIBAL_CONFEDERATION,
                        Map.of(
                                CultureBundles.UpkeepSource.TAX,     0.10,
                                CultureBundles.UpkeepSource.TRIBUTE, 0.10,
                                CultureBundles.UpkeepSource.LEVY,    0.60,
                                CultureBundles.UpkeepSource.TRADE,   0.20),
                        List.of("kingdom_king", "kingdom_chancellor",
                                "kingdom_scholar"),
                        // D1.5 fields. Plainfolk territories sprawl
                        // (high budget, low resistance to absorption);
                        // open to default-culture vassalage; hostile to
                        // none by default; nobility minimum at Headman.
                        /* claimBudgetHint */         2400,
                        /* claimResistance */         0.15f,
                        /* vassalEligibleCultures */  List.of("default"),
                        /* hostileCultures */         List.of(),
                        /* minNobilityTier */         1,
                        /* provinceSeatThreshold */   3));
    }

    // ── Highmarch ─────────────────────────────────────────────────────────

    private static Culture buildHighmarch() {
        return new Culture(
                "highmarch", "Highmarch",
                CultureSchedule.DEFAULT,
                new CultureNaming("highmarch", "highmarch", "highmarch",
                        List.of("Lord", "Lady", "Captain")),
                new CultureTraitBias(Map.of(
                        TraitAxis.COURAGE,     +0.4f,
                        TraitAxis.AMBITION,    +0.3f,
                        TraitAxis.SOCIABILITY, -0.2f)),
                new CultureReligion(ReligionRegistry.FORGE_CREED,
                        List.of("FUNERAL", "BLESSING")),
                new CultureOfficeRules(
                        Map.of(
                                OfficeRegistry.VILLAGE_LEADER,    SelectionMethod.HEREDITARY,
                                OfficeRegistry.VILLAGE_BAILIFF,   SelectionMethod.APPOINTED,
                                OfficeRegistry.VILLAGE_CONSTABLE, SelectionMethod.APPOINTED),
                        Map.of(OfficeRegistry.VILLAGE_LEADER, 0), // lifelong
                        /* councilDominant */ false,
                        /* heredityRespected */ true),
                new CultureLawDefaults(
                        List.of("DOUBLE_PUNISHMENT", "MARKET_TAX_REDUCED"),
                        List.of("DOUBLE_PUNISHMENT", "CURFEW", "MARKET_TAX_REDUCED"),
                        List.of("BAN_EXECUTION", "PARDON_FIRST_OFFENSE")),
                new CultureEconomicNorms(
                        /* giftPropensity */ 0.7f,
                        /* hagglingTendency */ 0.2f,
                        /* creditAccepted */ false,
                        /* luxuryDemand */ 0.6f,
                        Map.of(
                                tterrag1112.life_in_the_village.Village.Simulation.ResourceCategory.WEAPONS, 1.5f,
                                tterrag1112.life_in_the_village.Village.Simulation.ResourceCategory.CLOTH,   1.3f)),
                new CultureHobbyWeights(Map.of(
                        "sword_practice", 1.8f,
                        "archery",        1.6f,
                        "tell_story",     1.3f,
                        "visit_grave",    1.4f,
                        "tend_garden",    0.4f)),
                new CultureVisitorAffinity(Map.of(
                        VisitorType.ENVOY,            1.6f,
                        VisitorType.SCHOLAR_VISITING, 1.2f,
                        VisitorType.PILGRIM,          0.4f,
                        VisitorType.MINSTREL,         0.6f)),
                new CultureApprenticeshipNorms(3 * 365, 2, true, 0.3f),
                new CultureAestheticTokens(
                        List.of("dark_grey", "iron_black", "deep_red", "leather_brown"),
                        List.of("iron_pauldron", "heraldic_cape"),
                        "broad", Optional.of("kingdom_heraldry")),
                CulturePlanningBias.DEFAULT,
                // Track D1 — highmarch: hereditary duchies, agnatic
                // primogeniture, tribute-heavy. Vassals owe tribute
                // upward; the central treasury rarely taxes
                // commoners directly.
                new CultureBundles.CultureKingdomDefaults(
                        List.of("Knight", "Baron", "Marquis", "Duke"),
                        CultureBundles.SuccessionRule.AGNATIC_PRIMOGENITURE,
                        CultureBundles.SubdivisionModel.DUCHIES,
                        Map.of(
                                CultureBundles.UpkeepSource.TAX,     0.30,
                                CultureBundles.UpkeepSource.TRIBUTE, 0.40,
                                CultureBundles.UpkeepSource.LEVY,    0.20,
                                CultureBundles.UpkeepSource.TRADE,   0.10),
                        List.of("kingdom_king", "kingdom_chancellor",
                                "kingdom_treasurer", "kingdom_general",
                                "kingdom_magistrate"),
                        // D1.5 fields. Highmarch holds territory tightly:
                        // smaller initial budget, very high resistance to
                        // absorption, hostile to plainfolk by default;
                        // nobility starts at Knight; province seat
                        // threshold high (4 authority units).
                        /* claimBudgetHint */         1500,
                        /* claimResistance */         0.85f,
                        /* vassalEligibleCultures */  List.of("plainfolk", "tidereach"),
                        /* hostileCultures */         List.of("plainfolk"),
                        /* minNobilityTier */         0,
                        /* provinceSeatThreshold */   6));
    }

    // ── Silkwood ──────────────────────────────────────────────────────────

    private static Culture buildSilkwood() {
        return new Culture(
                "silkwood", "Silkwood",
                CultureSchedule.DEFAULT,
                new CultureNaming("silkwood", "silkwood", "silkwood",
                        List.of("Scholar", "Threadkeeper")),
                new CultureTraitBias(Map.of(
                        TraitAxis.TEMPERANCE,  +0.3f,
                        TraitAxis.COMPASSION,  +0.3f,
                        TraitAxis.SOCIABILITY, +0.1f)),
                new CultureReligion(ReligionRegistry.THE_LOOM,
                        List.of("BLESSING", "OFFERING", "FEAST_DAY")),
                new CultureOfficeRules(
                        Map.of(
                                OfficeRegistry.VILLAGE_LEADER, SelectionMethod.HEREDITARY,
                                OfficeRegistry.VILLAGE_SCRIBE, SelectionMethod.COUNCIL),
                        Map.of(OfficeRegistry.VILLAGE_LEADER, 0),
                        /* councilDominant */ true,
                        /* heredityRespected */ true),
                new CultureLawDefaults(
                        List.of("COMPULSORY_SCHOOLING", "BAN_EXECUTION", "SUBSIDIZE_SCHOLAR"),
                        List.of("COMPULSORY_SCHOOLING", "SUBSIDIZE_SCHOLAR",
                                "PARDON_FIRST_OFFENSE", "BAN_EXECUTION"),
                        List.of("DOUBLE_PUNISHMENT", "FOREIGN_TRADER_BAN")),
                new CultureEconomicNorms(
                        /* giftPropensity */ 0.3f,
                        /* hagglingTendency */ 0.7f,
                        /* creditAccepted */ true,
                        /* luxuryDemand */ 0.7f,
                        Map.of(
                                tterrag1112.life_in_the_village.Village.Simulation.ResourceCategory.PAPER,      1.6f,
                                tterrag1112.life_in_the_village.Village.Simulation.ResourceCategory.LITURGICAL, 1.3f,
                                tterrag1112.life_in_the_village.Village.Simulation.ResourceCategory.LUXURY,     1.4f)),
                new CultureHobbyWeights(Map.of(
                        "read_at_library", 1.8f,
                        "write_letter",    1.6f,
                        "meditate",        1.4f,
                        "carve_at_home",   1.2f,
                        "sword_practice",  0.2f,
                        "archery",         0.2f)),
                new CultureVisitorAffinity(Map.of(
                        VisitorType.STUDENT,          1.8f,
                        VisitorType.SCHOLAR_VISITING, 1.7f,
                        VisitorType.MINSTREL,         1.3f,
                        VisitorType.REFUGEE,          0.4f)),
                new CultureApprenticeshipNorms(4 * 365, 2, true, 0.6f),
                new CultureAestheticTokens(
                        List.of("forest_green", "moss", "muted_blue", "ivory"),
                        List.of("scholar_robe", "embroidered_sash"),
                        "tall", Optional.of("geometric")),
                CulturePlanningBias.DEFAULT,
                // Track D1 — silkwood: city-state league, elective
                // archons, trade-heavy. Coastal magnates fund the
                // central treasury through mercantile tariffs more
                // than agrarian taxes.
                new CultureBundles.CultureKingdomDefaults(
                        List.of("Citizen", "Magnate", "Senator", "Archon"),
                        CultureBundles.SuccessionRule.ELECTIVE,
                        CultureBundles.SubdivisionModel.CITY_STATE_LEAGUE,
                        Map.of(
                                CultureBundles.UpkeepSource.TAX,     0.20,
                                CultureBundles.UpkeepSource.TRIBUTE, 0.10,
                                CultureBundles.UpkeepSource.LEVY,    0.10,
                                CultureBundles.UpkeepSource.TRADE,   0.60),
                        List.of("kingdom_king", "kingdom_chancellor",
                                "kingdom_scholar", "kingdom_diplomat",
                                "kingdom_treasurer"),
                        // D1.5 fields. Silkwood city-states hold compact
                        // claims (low budget); resistant to absorption
                        // because city walls + treaties; receptive to
                        // tidereach trade partners; nobility minimum
                        // at Magnate; province threshold low (a single
                        // strong city counts).
                        /* claimBudgetHint */         1200,
                        /* claimResistance */         0.55f,
                        /* vassalEligibleCultures */  List.of("tidereach"),
                        /* hostileCultures */         List.of("highmarch"),
                        /* minNobilityTier */         1,
                        /* provinceSeatThreshold */   2));
    }

    // ── Tidereach ─────────────────────────────────────────────────────────

    private static Culture buildTidereach() {
        return new Culture(
                "tidereach", "Tidereach",
                CultureSchedule.DEFAULT,
                new CultureNaming("tidereach", "tidereach", "tidereach",
                        List.of("Captain", "Skipper")),
                new CultureTraitBias(Map.of(
                        TraitAxis.SOCIABILITY, +0.4f,
                        TraitAxis.GENEROSITY,  +0.3f,
                        TraitAxis.HONESTY,     +0.2f,
                        TraitAxis.TEMPERANCE,  -0.2f)),
                new CultureReligion(ReligionRegistry.TIDECALL,
                        List.of("BLESSING", "FEAST_DAY", "OFFERING")),
                new CultureOfficeRules(
                        Map.of(
                                OfficeRegistry.VILLAGE_LEADER,    SelectionMethod.MERITOCRATIC,
                                OfficeRegistry.VILLAGE_BAILIFF,   SelectionMethod.ELECTIVE),
                        Map.of(OfficeRegistry.VILLAGE_LEADER, 3 * 365),
                        /* councilDominant */ false,
                        /* heredityRespected */ false),
                new CultureLawDefaults(
                        List.of("PILGRIM_WELCOME_BONUS", "BAN_EXECUTION"),
                        List.of("PILGRIM_WELCOME_BONUS", "MARKET_TAX_REDUCED",
                                "PARDON_FIRST_OFFENSE", "BAN_EXECUTION"),
                        List.of("FOREIGN_TRADER_BAN", "DOUBLE_PUNISHMENT", "CURFEW")),
                new CultureEconomicNorms(
                        /* giftPropensity */ 0.7f,
                        /* hagglingTendency */ 0.7f,
                        /* creditAccepted */ true,
                        /* luxuryDemand */ 0.5f,
                        Map.of(
                                tterrag1112.life_in_the_village.Village.Simulation.ResourceCategory.FOOD,    1.1f,
                                tterrag1112.life_in_the_village.Village.Simulation.ResourceCategory.LUXURY,  1.2f)),
                new CultureHobbyWeights(Map.of(
                        "fishing",        1.8f,
                        "tell_story",     1.4f,
                        "inn_drink_talk", 1.5f,
                        "cards_at_inn",   1.4f,
                        "cook",           1.3f)),
                new CultureVisitorAffinity(Map.of(
                        VisitorType.MERCHANT_ITINERANT, 1.8f,
                        VisitorType.TRAVELER,           1.6f,
                        VisitorType.MINSTREL,           1.4f,
                        VisitorType.PILGRIM,            1.0f)),
                new CultureApprenticeshipNorms(2 * 365, 3, false, 0.8f),
                new CultureAestheticTokens(
                        List.of("ocean_blue", "sailcloth_white", "sun_bleached", "rope_hemp"),
                        List.of("fishing_cap", "sea_pendant"),
                        "lean", Optional.of("wave")),
                CulturePlanningBias.DEFAULT,
                // Track D1 — tidereach: maritime provinces under
                // elective leadership; trade-heavy with strong
                // diplomatic emphasis. Tariff revenue dominates;
                // taxation modest.
                new CultureBundles.CultureKingdomDefaults(
                        List.of("Steward", "Reeve", "Magistrate", "Princeps"),
                        CultureBundles.SuccessionRule.ELECTIVE,
                        CultureBundles.SubdivisionModel.PROVINCES,
                        Map.of(
                                CultureBundles.UpkeepSource.TAX,     0.30,
                                CultureBundles.UpkeepSource.TRIBUTE, 0.15,
                                CultureBundles.UpkeepSource.LEVY,    0.15,
                                CultureBundles.UpkeepSource.TRADE,   0.40),
                        List.of("kingdom_king", "kingdom_chancellor",
                                "kingdom_treasurer", "kingdom_diplomat"),
                        // D1.5 fields. Tidereach maritime states sprawl
                        // along coasts (large budget) but accept vassals
                        // (low resistance, open to silkwood + plainfolk
                        // trade-partners); no default hostility; nobility
                        // starts at Steward; medium province threshold.
                        /* claimBudgetHint */         2100,
                        /* claimResistance */         0.25f,
                        /* vassalEligibleCultures */  List.of("silkwood", "plainfolk"),
                        /* hostileCultures */         List.of(),
                        /* minNobilityTier */         0,
                        /* provinceSeatThreshold */   4));
    }
}
