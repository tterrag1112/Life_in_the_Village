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
                CulturePlanningBias.DEFAULT);
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
                CulturePlanningBias.DEFAULT);
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
                CulturePlanningBias.DEFAULT);
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
                CulturePlanningBias.DEFAULT);
    }
}
