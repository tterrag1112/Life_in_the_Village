package tterrag1112.life_in_the_village.Cultures;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Cultures.Planning.Curvature;
import tterrag1112.life_in_the_village.Cultures.Planning.PlazaShape;
import tterrag1112.life_in_the_village.Npc.Office.SelectionMethod;
import tterrag1112.life_in_the_village.Npc.Visitor.VisitorType;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;
import tterrag1112.life_in_the_village.Village.Simulation.ResourceCategory;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 5 doc 31 — sub-record bundles for {@link Culture}. Grouped
 * into one file to keep the package surface small; each nested
 * record is a focused, codec-shaped data carrier read by the
 * matching {@link CultureResolver} accessor.
 */
public final class CultureBundles {

    private CultureBundles() {}

    // ── Schedule ──────────────────────────────────────────────────────────

    /**
     * Per-spec line 50. v1 ships {@code professionOverrides} as
     * profession→day-off-days set so {@code WeeklyScheduleLibrary}
     * can apply on top of its base table; full
     * {@code WeeklySchedule} replacement is a Phase 6 concern.
     */
    public record CultureSchedule(
            Map<Profession, List<Integer>> professionDayOffs,
            int weekLength,
            List<Integer> cultureDayOffs,
            Optional<Integer> holyDayInterval
    ) {
        public CultureSchedule {
            professionDayOffs = professionDayOffs == null ? Map.of()
                    : Map.copyOf(professionDayOffs);
            cultureDayOffs    = List.copyOf(cultureDayOffs    != null ? cultureDayOffs    : List.of());
            if (holyDayInterval == null) holyDayInterval = Optional.empty();
            if (weekLength <= 0) weekLength = 7;
        }
        public static final CultureSchedule DEFAULT =
                new CultureSchedule(Map.of(), 7, List.of(), Optional.empty());

        public static final Codec<CultureSchedule> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.unboundedMap(
                        Codec.STRING.xmap(s -> Profession.valueOf(s.toUpperCase(Locale.ROOT)),
                                Profession::name),
                        Codec.INT.listOf()
                ).optionalFieldOf("professionDayOffs", Map.of()).forGetter(CultureSchedule::professionDayOffs),
                Codec.INT.optionalFieldOf("weekLength", 7).forGetter(CultureSchedule::weekLength),
                Codec.INT.listOf().optionalFieldOf("cultureDayOffs", List.of())
                        .forGetter(CultureSchedule::cultureDayOffs),
                Codec.INT.optionalFieldOf("holyDayInterval").forGetter(CultureSchedule::holyDayInterval)
        ).apply(i, CultureSchedule::new));
    }

    // ── Office rules ──────────────────────────────────────────────────────

    /**
     * Per-spec line 62. {@code preferredSelection} keys are office id
     * strings (matching {@code OfficeRegistry.VILLAGE_LEADER} etc).
     */
    public record CultureOfficeRules(
            Map<String, SelectionMethod> preferredSelection,
            Map<String, Integer> termLengthDays,
            boolean councilDominant,
            boolean heredityRespected
    ) {
        public CultureOfficeRules {
            preferredSelection = Map.copyOf(preferredSelection != null ? preferredSelection : Map.of());
            termLengthDays     = Map.copyOf(termLengthDays     != null ? termLengthDays     : Map.of());
        }
        public static final CultureOfficeRules DEFAULT =
                new CultureOfficeRules(Map.of(), Map.of(), false, false);

        public Optional<SelectionMethod> selectionFor(String officeId) {
            if (officeId == null) return Optional.empty();
            return Optional.ofNullable(preferredSelection.get(officeId));
        }

        public static final Codec<CultureOfficeRules> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.unboundedMap(Codec.STRING,
                                Codec.STRING.xmap(s -> SelectionMethod.valueOf(s.toUpperCase(Locale.ROOT)),
                                        SelectionMethod::name))
                        .optionalFieldOf("preferredSelection", Map.of())
                        .forGetter(CultureOfficeRules::preferredSelection),
                Codec.unboundedMap(Codec.STRING, Codec.INT)
                        .optionalFieldOf("termLengthDays", Map.of())
                        .forGetter(CultureOfficeRules::termLengthDays),
                Codec.BOOL.optionalFieldOf("councilDominant", false).forGetter(CultureOfficeRules::councilDominant),
                Codec.BOOL.optionalFieldOf("heredityRespected", false).forGetter(CultureOfficeRules::heredityRespected)
        ).apply(i, CultureOfficeRules::new));
    }

    // ── Law defaults ──────────────────────────────────────────────────────

    /** Per-spec line 76. References {@code VillageLaw} by name string
     *  to avoid the package import; the resolver translates back. */
    public record CultureLawDefaults(
            List<String> initialLaws,
            List<String> preferredLaws,
            List<String> forbiddenLaws
    ) {
        public CultureLawDefaults {
            initialLaws   = List.copyOf(initialLaws   != null ? initialLaws   : List.of());
            preferredLaws = List.copyOf(preferredLaws != null ? preferredLaws : List.of());
            forbiddenLaws = List.copyOf(forbiddenLaws != null ? forbiddenLaws : List.of());
        }
        public static final CultureLawDefaults EMPTY =
                new CultureLawDefaults(List.of(), List.of(), List.of());

        public boolean isForbidden(String lawName) {
            return lawName != null && forbiddenLaws.contains(lawName);
        }
        public boolean isPreferred(String lawName) {
            return lawName != null && preferredLaws.contains(lawName);
        }

        public static final Codec<CultureLawDefaults> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.listOf().optionalFieldOf("initialLaws",   List.of()).forGetter(CultureLawDefaults::initialLaws),
                Codec.STRING.listOf().optionalFieldOf("preferredLaws", List.of()).forGetter(CultureLawDefaults::preferredLaws),
                Codec.STRING.listOf().optionalFieldOf("forbiddenLaws", List.of()).forGetter(CultureLawDefaults::forbiddenLaws)
        ).apply(i, CultureLawDefaults::new));
    }

    // ── Economic norms ────────────────────────────────────────────────────

    public record CultureEconomicNorms(
            float giftPropensity,
            float hagglingTendency,
            boolean creditAccepted,
            float luxuryDemand,
            Map<ResourceCategory, Float> consumptionBias
    ) {
        public CultureEconomicNorms {
            consumptionBias = consumptionBias == null ? Map.of() : Map.copyOf(consumptionBias);
            if (giftPropensity   < 0f) giftPropensity   = 0f;
            if (hagglingTendency < 0f) hagglingTendency = 0f;
            if (luxuryDemand     < 0f) luxuryDemand     = 0f;
        }

        /** Spec "Things to flag" #3 — unspecified fields default to
         *  the neutral midpoint (0.5). Used by Phase 5 hooks that want
         *  a numeric multiplier without checking presence. */
        public static final CultureEconomicNorms NEUTRAL =
                new CultureEconomicNorms(0.5f, 0.5f, true, 0.5f, Map.of());

        public float consumptionMultiplier(ResourceCategory c) {
            if (c == null) return 1f;
            return consumptionBias.getOrDefault(c, 1f);
        }

        public static final Codec<CultureEconomicNorms> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.optionalFieldOf("giftPropensity",   0.5f).forGetter(CultureEconomicNorms::giftPropensity),
                Codec.FLOAT.optionalFieldOf("hagglingTendency", 0.5f).forGetter(CultureEconomicNorms::hagglingTendency),
                Codec.BOOL.optionalFieldOf("creditAccepted",    true).forGetter(CultureEconomicNorms::creditAccepted),
                Codec.FLOAT.optionalFieldOf("luxuryDemand",     0.5f).forGetter(CultureEconomicNorms::luxuryDemand),
                Codec.unboundedMap(ResourceCategory.CODEC, Codec.FLOAT)
                        .optionalFieldOf("consumptionBias", Map.of())
                        .forGetter(CultureEconomicNorms::consumptionBias)
        ).apply(i, CultureEconomicNorms::new));
    }

    // ── Hobby weights ────────────────────────────────────────────────────

    /** Map of hobby id → multiplier on the trait-derived score. Default
     *  multiplier (no entry) is {@code 1.0f} so hobby selection
     *  unchanged when culture doesn't care. */
    public record CultureHobbyWeights(Map<String, Float> weights) {
        public CultureHobbyWeights {
            weights = Map.copyOf(weights != null ? weights : Map.of());
        }
        public static final CultureHobbyWeights NEUTRAL = new CultureHobbyWeights(Map.of());

        public float weightFor(String hobbyId) {
            if (hobbyId == null) return 1f;
            return weights.getOrDefault(hobbyId, 1f);
        }

        public static final Codec<CultureHobbyWeights> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.unboundedMap(Codec.STRING, Codec.FLOAT)
                        .optionalFieldOf("weights", Map.of())
                        .forGetter(CultureHobbyWeights::weights)
        ).apply(i, CultureHobbyWeights::new));
    }

    // ── Visitor affinity ──────────────────────────────────────────────────

    public record CultureVisitorAffinity(Map<VisitorType, Float> weightMultipliers) {
        public CultureVisitorAffinity {
            weightMultipliers = weightMultipliers == null ? Map.of() : Map.copyOf(weightMultipliers);
        }
        public static final CultureVisitorAffinity NEUTRAL = new CultureVisitorAffinity(Map.of());

        public float multiplierFor(VisitorType type) {
            if (type == null) return 1f;
            return weightMultipliers.getOrDefault(type, 1f);
        }

        public static final Codec<CultureVisitorAffinity> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.unboundedMap(
                        Codec.STRING.xmap(s -> VisitorType.valueOf(s.toUpperCase(Locale.ROOT)),
                                VisitorType::name),
                        Codec.FLOAT
                ).optionalFieldOf("weightMultipliers", Map.of())
                        .forGetter(CultureVisitorAffinity::weightMultipliers)
        ).apply(i, CultureVisitorAffinity::new));
    }

    // ── Apprenticeship norms ─────────────────────────────────────────────

    public record CultureApprenticeshipNorms(
            int standardDurationDays,
            int maxApprenticesPerMaster,
            boolean requiresMasterpiece,
            float daughterInheritsShop
    ) {
        public CultureApprenticeshipNorms {
            if (standardDurationDays    < 1) standardDurationDays    = 365 * 2;
            if (maxApprenticesPerMaster < 1) maxApprenticesPerMaster = 2;
            if (daughterInheritsShop < 0f)   daughterInheritsShop = 0f;
            if (daughterInheritsShop > 1f)   daughterInheritsShop = 1f;
        }
        public static final CultureApprenticeshipNorms DEFAULT =
                new CultureApprenticeshipNorms(365 * 2, 2, true, 0.5f);

        public static final Codec<CultureApprenticeshipNorms> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("standardDurationDays",    365 * 2).forGetter(CultureApprenticeshipNorms::standardDurationDays),
                Codec.INT.optionalFieldOf("maxApprenticesPerMaster", 2).forGetter(CultureApprenticeshipNorms::maxApprenticesPerMaster),
                Codec.BOOL.optionalFieldOf("requiresMasterpiece",    true).forGetter(CultureApprenticeshipNorms::requiresMasterpiece),
                Codec.FLOAT.optionalFieldOf("daughterInheritsShop",  0.5f).forGetter(CultureApprenticeshipNorms::daughterInheritsShop)
        ).apply(i, CultureApprenticeshipNorms::new));
    }

    // ── Aesthetic tokens ─────────────────────────────────────────────────

    public record CultureAestheticTokens(
            List<String> clothingPalette,
            List<String> accessoryPool,
            String primarySilhouette,
            Optional<String> bannerPattern
    ) {
        public CultureAestheticTokens {
            clothingPalette = List.copyOf(clothingPalette != null ? clothingPalette : List.of());
            accessoryPool   = List.copyOf(accessoryPool   != null ? accessoryPool   : List.of());
            if (primarySilhouette == null) primarySilhouette = "default";
            if (bannerPattern == null) bannerPattern = Optional.empty();
        }
        public static final CultureAestheticTokens DEFAULT =
                new CultureAestheticTokens(List.of(), List.of(), "default", Optional.empty());

        public static final Codec<CultureAestheticTokens> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.listOf().optionalFieldOf("clothingPalette", List.of())
                        .forGetter(CultureAestheticTokens::clothingPalette),
                Codec.STRING.listOf().optionalFieldOf("accessoryPool", List.of())
                        .forGetter(CultureAestheticTokens::accessoryPool),
                Codec.STRING.optionalFieldOf("primarySilhouette", "default")
                        .forGetter(CultureAestheticTokens::primarySilhouette),
                Codec.STRING.optionalFieldOf("bannerPattern")
                        .forGetter(CultureAestheticTokens::bannerPattern)
        ).apply(i, CultureAestheticTokens::new));
    }

    // ── Religion ─────────────────────────────────────────────────────────

    /** Maps the culture to its dominant religion id and which
     *  rite types receive priority emphasis. The religion id is the
     *  string key in {@code ReligionRegistry}. */
    public record CultureReligion(
            String religionId,
            List<String> ritePreferences
    ) {
        public CultureReligion {
            if (religionId == null) religionId = "sunstead";
            ritePreferences = List.copyOf(ritePreferences != null ? ritePreferences : List.of());
        }
        public static final CultureReligion DEFAULT =
                new CultureReligion("sunstead", List.of());

        public static final Codec<CultureReligion> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("religionId").forGetter(CultureReligion::religionId),
                Codec.STRING.listOf().optionalFieldOf("ritePreferences", List.of())
                        .forGetter(CultureReligion::ritePreferences)
        ).apply(i, CultureReligion::new));
    }

    // ── Planning bias (Track A2 — V2 culture absorption) ────────────────

    /**
     * Track A2 — culture-level planning preferences consumed by the
     * V2 planner. Folds the four fields of the deleted
     * {@code V2.Culture.Culture} record into the canonical
     * {@link Culture}: road material for surface painting (V2 Layer 5),
     * curvature preference for road-primitive selection (V2 Layer 4),
     * plaza shape preference for plaza geometry (V2 Layer 3), and
     * inclination bias multipliers (V2 Layer 2 weighted sample).
     *
     * <p>Defaults match the original {@code default.json}:
     * {@code minecraft:dirt_path} road, {@link Curvature#NATURAL},
     * {@link PlazaShape#IRREGULAR}, uniform 1.0 across all
     * inclinations.
     */
    public record CulturePlanningBias(
            String roadMaterial,
            Curvature preferredCurvature,
            PlazaShape preferredPlazaShape,
            Map<Inclination, Double> inclinationBias
    ) {
        public CulturePlanningBias {
            if (roadMaterial == null) roadMaterial = "minecraft:dirt_path";
            if (preferredCurvature == null) preferredCurvature = Curvature.NATURAL;
            if (preferredPlazaShape == null) preferredPlazaShape = PlazaShape.IRREGULAR;
            inclinationBias = inclinationBias == null ? Map.of()
                    : Map.copyOf(inclinationBias);
        }

        public static final CulturePlanningBias DEFAULT =
                new CulturePlanningBias(
                        "minecraft:dirt_path",
                        Curvature.NATURAL,
                        PlazaShape.IRREGULAR,
                        uniformInclinationBias());

        /** Bias for {@code inc}, defaulting to 1.0 — mirrors the
         *  deleted {@code V2.Culture.Culture#biasFor}. */
        public double biasFor(Inclination inc) {
            Double v = inclinationBias.get(inc);
            return v != null ? v : 1.0;
        }

        private static Map<Inclination, Double> uniformInclinationBias() {
            EnumMap<Inclination, Double> m = new EnumMap<>(Inclination.class);
            for (Inclination inc : Inclination.values()) m.put(inc, 1.0);
            return m;
        }

        public static final Codec<CulturePlanningBias> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("roadMaterial", "minecraft:dirt_path")
                        .forGetter(CulturePlanningBias::roadMaterial),
                Codec.STRING.xmap(s -> Curvature.valueOf(s.toUpperCase(Locale.ROOT)),
                                Curvature::name)
                        .optionalFieldOf("preferredCurvature", Curvature.NATURAL)
                        .forGetter(CulturePlanningBias::preferredCurvature),
                Codec.STRING.xmap(s -> PlazaShape.valueOf(s.toUpperCase(Locale.ROOT)),
                                PlazaShape::name)
                        .optionalFieldOf("preferredPlazaShape", PlazaShape.IRREGULAR)
                        .forGetter(CulturePlanningBias::preferredPlazaShape),
                Codec.unboundedMap(
                                Codec.STRING.xmap(
                                        s -> Inclination.valueOf(s.toUpperCase(Locale.ROOT)),
                                        Inclination::name),
                                Codec.DOUBLE)
                        .optionalFieldOf("inclinationBias", Map.of())
                        .forGetter(CulturePlanningBias::inclinationBias)
        ).apply(i, CulturePlanningBias::new));
    }

    /** Suppresses unused-import warning on EnumMap (used in helpers). */
    @SuppressWarnings("unused")
    private static final EnumMap<Profession, List<Integer>> DOC_HOOK = new EnumMap<>(Profession.class);
}
