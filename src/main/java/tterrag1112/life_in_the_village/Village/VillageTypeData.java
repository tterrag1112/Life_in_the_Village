// FILE: src/main/java/tterrag1112/life_in_the_village/Village/VillageTypeData.java
package tterrag1112.life_in_the_village.Village;

import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.ColorPalette;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable data loaded from a village type JSON file.
 *
 * <h3>What's gone</h3>
 * Capitals, NPCs, and starter items have all been removed from the
 * village type data. NPCs are spawned by the building inhabitant
 * system based on what buildings end up being placed; starter items
 * are seeded elsewhere; capitals will be reintroduced as a shape
 * recipe in a later phase.
 */
public class VillageTypeData {

    public record StarterBuilding(
            String type,
            String structure,
            int minCount,
            int maxCount
    ) {
        public int resolveCount(java.util.Random rng) {
            if (maxCount <= minCount) return minCount;
            return minCount + rng.nextInt(maxCount - minCount + 1);
        }
    }

    // =========================================================================
    // Farm plot config
    // =========================================================================

    public record FarmPlotConfig(
            FarmPlotPlacement placement,
            int minDistance,
            int maxDistance,
            boolean allowAnimalPens,
            int plotsPerFarmhouse
    ) {
        public static FarmPlotConfig defaultConfig() {
            return new FarmPlotConfig(FarmPlotPlacement.PERIMETER_OUTSIDE, 8, 32, true, 1);
        }
        public static FarmPlotConfig integrated() {
            return new FarmPlotConfig(FarmPlotPlacement.INTEGRATED, 0, 16, false, 1);
        }
        public static FarmPlotConfig distantFields() {
            return new FarmPlotConfig(FarmPlotPlacement.DISTANT_FIELDS, 40, 80, true, 2);
        }
    }

    public enum FarmPlotPlacement {
        PERIMETER_OUTSIDE, INTEGRATED, DISTANT_FIELDS, NONE
    }

    // =========================================================================
    // Shape profile
    // =========================================================================

    public record VillageShapeProfile(
            ShapeType shapeType,
            boolean forcedAxis,
            int maxRings,
            float streetDensity,
            boolean walledByDefault
    ) {
        public static VillageShapeProfile defaultProfile() {
            return new VillageShapeProfile(ShapeType.RADIAL, false, 2, 1.0f, false);
        }
    }

    /**
     * All currently implemented village layouts. After Track A1b,
     * the V1 ShapeRecipe registry is gone; V2 maps these via its own
     * culture/shape pipeline. Constants are retained for JSON
     * back-compat and for V2's mapping table.
     */
    public enum ShapeType {
        RADIAL,
        LINEAR,
        CLUSTERED,
        RIVERINE,
        HILLTOP,
        PLAZA,
        CROSSROADS,
        CHAIN,
        ROADSIDE,
        GROVE,
        SPRAWL,
        DOCKSIDE,
        DUAL_PLAZA,
        OUTPOST,
        TERRACED,
        ENCLAVE,
        DUMBELL
    }

    // =========================================================================
    // Fields
    // =========================================================================

    private final String type;
    public final String culture;
    private final List<StarterBuilding> starterBuildings;
    private final FarmPlotConfig farmPlotConfig;
    private final VillageShapeProfile shapeProfile;
    private final int townSquareCapacity;
    private final Set<VillageTag> tags;
    /**
     * Doc 15 — variant style profile. One of {@code "rural"},
     * {@code "urban"}, or {@code "auto"} (the default). When
     * {@code "auto"}, the matcher derives the style from layout +
     * tier per the rules in §"Style determination".
     */
    private String style = "auto";

    /**
     * Doc 15 — the village's colour palette. Resolution order at
     * placement (see {@code VillagePaletteResolver}):
     * <ol>
     *   <li>this field, when non-null;</li>
     *   <li>the culture's default palette
     *       ({@code CultureDefaultPalettes});</li>
     *   <li>{@code ColorPaletteRegistry.NONE} (no tint).</li>
     * </ol>
     * Either a named preset id or an inline weight table from the
     * type JSON parses through {@code ColorPaletteRegistry.parse}
     * before being stored here.
     */
    @Nullable private ColorPalette colorPalette = null;

    /**
     * Doc 15 §"Forced overrides" — village's signature colour. When
     * non-null, {@code TOWN_HALL}'s primary tint is forced to this
     * value rather than rolling from the palette. Null means the
     * town hall samples like any other building.
     */
    @Nullable private DyeColor signatureColor = null;


    // =========================================================================
    // Single canonical constructor
    // =========================================================================

    public VillageTypeData(String type, String culture,
                           List<StarterBuilding> starterBuildings,
                           VillageShapeProfile shapeProfile,
                           FarmPlotConfig farmPlotConfig,
                           int townSquareCapacity,
                           Set<VillageTag> manualTags) {
        this.type = type;
        this.culture = culture;
        this.starterBuildings = List.copyOf(starterBuildings);
        this.shapeProfile = shapeProfile != null
                ? shapeProfile : VillageShapeProfile.defaultProfile();
        this.farmPlotConfig = farmPlotConfig != null
                ? farmPlotConfig : FarmPlotConfig.defaultConfig();
        this.townSquareCapacity = townSquareCapacity > 0 ? townSquareCapacity : 6;

        // Union manual + derived tags
        Set<VillageTag> combined = EnumSet.noneOf(VillageTag.class);
        if (manualTags != null) combined.addAll(manualTags);
        combined.addAll(VillageTagDeriver.derive(
                this.shapeProfile.shapeType(), this.starterBuildings));
        this.tags = java.util.Collections.unmodifiableSet(combined);
    }

    // Keep the old canonical constructor as an overload that delegates
    // with empty manual tags — avoids breaking existing call sites:
    public VillageTypeData(String type, String culture,
                           List<StarterBuilding> starterBuildings,
                           VillageShapeProfile shapeProfile,
                           FarmPlotConfig farmPlotConfig,
                           int townSquareCapacity) {
        this(type, culture, starterBuildings, shapeProfile, farmPlotConfig,
                townSquareCapacity, EnumSet.noneOf(VillageTag.class));
    }

    // Also update the test-convenience constructor — just add empty tags:
    public VillageTypeData(String type, String culture,
                           List<StarterBuilding> starterBuildings) {
        this(type, culture, starterBuildings,
                VillageShapeProfile.defaultProfile(),
                FarmPlotConfig.defaultConfig(),
                4,
                EnumSet.noneOf(VillageTag.class));
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public String getType() { return type; }
    public String getCulture() { return culture; }
    public List<StarterBuilding> getStarterBuildings() { return starterBuildings; }
    public VillageShapeProfile getShapeProfile() { return shapeProfile; }
    public FarmPlotConfig getFarmPlotConfig() { return farmPlotConfig; }
    public int getTownSquareCapacity() { return townSquareCapacity; }

    public Set<VillageTag> getTags() { return tags;}

    // ── Phase 22 cascade fallback chain ────────────────────────────────────
    // List of ShapeTypes to try in order when the primary shape's compose
    // returns FALLBACK (Phase 16b cascade) or when the validator rejects
    // a composed plan with passed/total < 0.6 threshold. Empty list = no
    // schema-declared chain; cascade engine falls back to the per-recipe
    // BaseRecipe.fallbackShape() (Phase 22 deprecated path).
    private List<ShapeType> fallbackChain = List.of();
    public List<ShapeType> getFallbackChain() { return fallbackChain; }
    public void setFallbackChain(List<ShapeType> chain) {
        this.fallbackChain = chain != null ? List.copyOf(chain) : List.of();
    }

    // ── Phase 21 kingdom-rework schema additions ───────────────────────────
    // Declared here so the kingdom rework can read these without needing to
    // extend the schema. Phase 21 wires nothing — VillagePlanner / spawner /
    // matcher are all unaware of these fields. Defaults match the prompt
    // section "Field design" so older datapacks keep loading unchanged.
    //
    // Note: `culture` is NOT here — it already exists at line 111 as a
    // constructor-set final field with a parser default of "default".
    private SettlementTier settlementTier = SettlementTier.HAMLET;
    private Set<String> biomeAffinity = Set.of();
    private Set<String> kingdomRoles = Set.of();
    private int tradePriority = 1;
    private boolean canBeCapital = false;
    private int maxPerKingdom = -1;

    public SettlementTier getSettlementTier() { return settlementTier; }
    public void setSettlementTier(SettlementTier tier) {
        this.settlementTier = tier != null ? tier : SettlementTier.HAMLET;
    }

    public Set<String> getBiomeAffinity() { return biomeAffinity; }
    public void setBiomeAffinity(Set<String> affinity) {
        this.biomeAffinity = affinity != null
                ? Set.copyOf(affinity) : Set.of();
    }

    public Set<String> getKingdomRoles() { return kingdomRoles; }
    public void setKingdomRoles(Set<String> roles) {
        this.kingdomRoles = roles != null ? Set.copyOf(roles) : Set.of();
    }

    public int getTradePriority() { return tradePriority; }
    public void setTradePriority(int priority) {
        this.tradePriority = priority;
    }

    public boolean canBeCapital() { return canBeCapital; }
    public void setCanBeCapital(boolean can) { this.canBeCapital = can; }

    public int getMaxPerKingdom() { return maxPerKingdom; }
    public void setMaxPerKingdom(int max) { this.maxPerKingdom = max; }

    /** Raw style declaration: {@code "rural"}, {@code "urban"}, or
     *  {@code "auto"}. Default is {@code "auto"}. */
    public String getStyle() { return style; }

    /** Sets the style declaration. Unknown values are accepted (the
     *  consumer logs and falls back to {@code "auto"}); validation is
     *  consumer-side so that future style folders don't require a
     *  schema bump here. */
    public void setStyle(String style) {
        this.style = style != null ? style : "auto";
    }

    /** Doc 15 — village colour palette, or {@code null} to fall
     *  through to the culture default. */
    @Nullable public ColorPalette getColorPalette() { return colorPalette; }
    public void setColorPalette(@Nullable ColorPalette palette) {
        this.colorPalette = palette;
    }

    /** Doc 15 §"Forced overrides" — TOWN_HALL signature colour. */
    @Nullable public DyeColor getSignatureColor() { return signatureColor; }
    public void setSignatureColor(@Nullable DyeColor color) {
        this.signatureColor = color;
    }
}