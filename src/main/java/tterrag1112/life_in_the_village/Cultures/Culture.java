package tterrag1112.life_in_the_village.Cultures;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureAestheticTokens;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureApprenticeshipNorms;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureEconomicNorms;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureHobbyWeights;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureKingdomDefaults;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureLawDefaults;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureOfficeRules;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CulturePlanningBias;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureReligion;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureSchedule;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureVisitorAffinity;

/**
 * Phase 5 doc 31 — full culture record. Replaces the prior empty
 * stub class. Each sub-record bundles the spec's per-category data
 * for its hook point; subsystems read via {@link CultureResolver}
 * rather than reaching into the record directly.
 *
 * <p>Outer codec arity = 14 fields, well under DFU's 16-field
 * {@code RecordCodecBuilder} cap (the same wall doc 26 hit). Each
 * sub-record carries its own focused codec.</p>
 *
 * <p>Backward-compat note: the prior {@code Culture} class shipped
 * a {@code key} string field and static {@code ListCultures}
 * collection. No production code consumed those fields (only an
 * unused {@code AxolotlingCulture} subclass referenced them); v1
 * removes both. {@link #id} replaces {@code key}.</p>
 */
public record Culture(
        String id,
        String displayName,
        CultureSchedule schedule,
        CultureNaming naming,
        CultureTraitBias traitBias,
        CultureReligion religion,
        CultureOfficeRules officeRules,
        CultureLawDefaults lawDefaults,
        CultureEconomicNorms economicNorms,
        CultureHobbyWeights hobbyWeights,
        CultureVisitorAffinity visitorAffinity,
        CultureApprenticeshipNorms apprenticeshipNorms,
        CultureAestheticTokens aesthetics,
        CulturePlanningBias planningBias,
        // Track D1 — kingdom-tier defaults. Inert this phase; D2/D3
        // read these to drive succession, subdivision, and upkeep.
        CultureKingdomDefaults kingdomDefaults
) {
    public Culture {
        if (id == null) throw new IllegalArgumentException("culture id required");
        if (displayName == null) displayName = id;
        if (schedule == null)            schedule            = CultureSchedule.DEFAULT;
        if (naming == null)              naming              = CultureNaming.DEFAULT;
        if (traitBias == null)           traitBias           = CultureTraitBias.NEUTRAL;
        if (religion == null)            religion            = CultureReligion.DEFAULT;
        if (officeRules == null)         officeRules         = CultureOfficeRules.DEFAULT;
        if (lawDefaults == null)         lawDefaults         = CultureLawDefaults.EMPTY;
        if (economicNorms == null)       economicNorms       = CultureEconomicNorms.NEUTRAL;
        if (hobbyWeights == null)        hobbyWeights        = CultureHobbyWeights.NEUTRAL;
        if (visitorAffinity == null)     visitorAffinity     = CultureVisitorAffinity.NEUTRAL;
        if (apprenticeshipNorms == null) apprenticeshipNorms = CultureApprenticeshipNorms.DEFAULT;
        if (aesthetics == null)          aesthetics          = CultureAestheticTokens.DEFAULT;
        if (planningBias == null)        planningBias        = CulturePlanningBias.DEFAULT;
        if (kingdomDefaults == null)     kingdomDefaults     = CultureKingdomDefaults.DEFAULT;
    }

    /** Convenience: a barebones culture for the {@code "default"}
     *  fallback used by villages with no explicit culture set. */
    public static Culture neutralDefault() {
        return new Culture(
                "default", "Default",
                CultureSchedule.DEFAULT,
                CultureNaming.DEFAULT,
                CultureTraitBias.NEUTRAL,
                CultureReligion.DEFAULT,
                CultureOfficeRules.DEFAULT,
                CultureLawDefaults.EMPTY,
                CultureEconomicNorms.NEUTRAL,
                CultureHobbyWeights.NEUTRAL,
                CultureVisitorAffinity.NEUTRAL,
                CultureApprenticeshipNorms.DEFAULT,
                CultureAestheticTokens.DEFAULT,
                CulturePlanningBias.DEFAULT,
                CultureKingdomDefaults.DEFAULT);
    }

    public static final Codec<Culture> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(Culture::id),
            Codec.STRING.fieldOf("displayName").forGetter(Culture::displayName),
            CultureSchedule.CODEC.optionalFieldOf("schedule", CultureSchedule.DEFAULT).forGetter(Culture::schedule),
            CultureNaming.CODEC.optionalFieldOf("naming", CultureNaming.DEFAULT).forGetter(Culture::naming),
            CultureTraitBias.CODEC.optionalFieldOf("traitBias", CultureTraitBias.NEUTRAL).forGetter(Culture::traitBias),
            CultureReligion.CODEC.optionalFieldOf("religion", CultureReligion.DEFAULT).forGetter(Culture::religion),
            CultureOfficeRules.CODEC.optionalFieldOf("officeRules", CultureOfficeRules.DEFAULT).forGetter(Culture::officeRules),
            CultureLawDefaults.CODEC.optionalFieldOf("lawDefaults", CultureLawDefaults.EMPTY).forGetter(Culture::lawDefaults),
            CultureEconomicNorms.CODEC.optionalFieldOf("economicNorms", CultureEconomicNorms.NEUTRAL).forGetter(Culture::economicNorms),
            CultureHobbyWeights.CODEC.optionalFieldOf("hobbyWeights", CultureHobbyWeights.NEUTRAL).forGetter(Culture::hobbyWeights),
            CultureVisitorAffinity.CODEC.optionalFieldOf("visitorAffinity", CultureVisitorAffinity.NEUTRAL).forGetter(Culture::visitorAffinity),
            CultureApprenticeshipNorms.CODEC.optionalFieldOf("apprenticeshipNorms", CultureApprenticeshipNorms.DEFAULT).forGetter(Culture::apprenticeshipNorms),
            CultureAestheticTokens.CODEC.optionalFieldOf("aesthetics", CultureAestheticTokens.DEFAULT).forGetter(Culture::aesthetics),
            CulturePlanningBias.CODEC.optionalFieldOf("planningBias", CulturePlanningBias.DEFAULT).forGetter(Culture::planningBias),
            CultureKingdomDefaults.CODEC.optionalFieldOf("kingdomDefaults", CultureKingdomDefaults.DEFAULT).forGetter(Culture::kingdomDefaults)
    ).apply(i, Culture::new));
}
