package tterrag1112.life_in_the_village.Village.Simulation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight simulation snapshot for a single village.
 *
 * <h3>Phase 4 doc 25 refactor</h3>
 * Production / consumption are now {@link ResourceCategory}-keyed
 * maps. The previous flat {@code foodProductionPerDay} /
 * {@code materialProductionPerDay} fields are read-only on the codec
 * for save migration: a v1 save populates {@code production[FOOD]} and
 * {@code production[BUILDING_MATERIALS]} from the legacy fields and
 * subsequent saves drop them.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li><b>At village spawn</b> — {@link VillageSimEngine#buildBaseline}
 *       seeds an EnumMap from the village's building list +
 *       {@link BuildingResourceProfile#TABLE}.</li>
 *   <li><b>While loaded</b> — each daily tick samples real production
 *       and {@link #blendReal} runs an 80/20 weighted average.</li>
 *   <li><b>While unloaded</b> — {@link #advanceSim} nudges
 *       consumption by the season multiplier; production is held.</li>
 * </ol>
 */
public class VillageSimData {

    // =========================================================================
    // Codec
    // =========================================================================

    private static final Codec<Map<ResourceCategory, Float>> CATEGORY_MAP_CODEC =
            Codec.unboundedMap(ResourceCategory.CODEC, Codec.FLOAT);

    public static final Codec<VillageSimData> CODEC = RecordCodecBuilder.create(i ->
            i.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("villageId")
                            .forGetter(d -> d.villageId),
                    CATEGORY_MAP_CODEC.optionalFieldOf("productionPerDay", Map.of())
                            .forGetter(d -> Collections.unmodifiableMap(
                                    new LinkedHashMap<>(d.productionPerDay))),
                    CATEGORY_MAP_CODEC.optionalFieldOf("consumptionPerDay", Map.of())
                            .forGetter(d -> Collections.unmodifiableMap(
                                    new LinkedHashMap<>(d.consumptionPerDay))),
                    // Legacy float fields — read-only for migration. Defaults
                    // to 0; the constructor merges any non-zero value into
                    // the new map. Once a save round-trips, these write
                    // back as 0 and DFU's optionalFieldOf elides them.
                    Codec.FLOAT.optionalFieldOf("foodProductionPerDay", 0f)
                            .forGetter(d -> 0f),
                    Codec.FLOAT.optionalFieldOf("foodConsumptionPerDay", 0f)
                            .forGetter(d -> 0f),
                    Codec.FLOAT.optionalFieldOf("materialProductionPerDay", 0f)
                            .forGetter(d -> 0f),
                    Codec.FLOAT.optionalFieldOf("materialConsumptionPerDay", 0f)
                            .forGetter(d -> 0f),
                    Codec.INT.fieldOf("simulatedPopulation")
                            .forGetter(d -> d.simulatedPopulation),
                    Codec.INT.optionalFieldOf("farmhouseCount", 0)
                            .forGetter(d -> d.farmhouseCount),
                    Codec.INT.optionalFieldOf("mineCount", 0)
                            .forGetter(d -> d.mineCount),
                    Codec.LONG.fieldOf("lastSyncTick")
                            .forGetter(d -> d.lastSyncTick),
                    Codec.FLOAT.optionalFieldOf("treasuryBalanceEstimate", 0f)
                            .forGetter(d -> d.treasuryBalanceEstimate),
                    Codec.FLOAT.optionalFieldOf("wageDrainPerDay", 0f)
                            .forGetter(d -> d.wageDrainPerDay),
                    Codec.FLOAT.optionalFieldOf("taxIncomePerDay", 0f)
                            .forGetter(d -> d.taxIncomePerDay)
            ).apply(i, VillageSimData::fromCodec));

    // =========================================================================
    // Fields
    // =========================================================================

    private final UUID villageId;

    /** Production rates (units / in-game day) per category. EnumMap so
     *  iteration order is the enum-declaration order. */
    private final EnumMap<ResourceCategory, Float> productionPerDay  =
            new EnumMap<>(ResourceCategory.class);
    private final EnumMap<ResourceCategory, Float> consumptionPerDay =
            new EnumMap<>(ResourceCategory.class);

    private int   simulatedPopulation;
    /** Legacy aggregate counts kept for the property-tax estimate in
     *  {@link VillageSimEngine#reconcileOnLoad} and the seed-rate in
     *  {@link VillageSimEngine#buildBaseline}. */
    private int   farmhouseCount;
    private int   mineCount;
    private long  lastSyncTick;
    private boolean wasUnloaded;
    private float treasuryBalanceEstimate;
    private float wageDrainPerDay;
    private float taxIncomePerDay;

    // =========================================================================
    // Constructors
    // =========================================================================

    /** Creates a fresh sim record with empty rate maps. */
    public static VillageSimData create(UUID villageId) {
        return new VillageSimData(villageId, Map.of(), Map.of(),
                0, 0, 0, 0L, 0f, 0f, 0f);
    }

    public VillageSimData(UUID villageId,
                          Map<ResourceCategory, Float> productionPerDay,
                          Map<ResourceCategory, Float> consumptionPerDay,
                          int simulatedPopulation,
                          int farmhouseCount,
                          int mineCount,
                          long lastSyncTick,
                          float treasuryBalanceEstimate,
                          float wageDrainPerDay,
                          float taxIncomePerDay) {
        this.villageId = villageId;
        if (productionPerDay  != null) this.productionPerDay.putAll(productionPerDay);
        if (consumptionPerDay != null) this.consumptionPerDay.putAll(consumptionPerDay);
        this.simulatedPopulation     = simulatedPopulation;
        this.farmhouseCount          = farmhouseCount;
        this.mineCount               = mineCount;
        this.lastSyncTick            = lastSyncTick;
        this.treasuryBalanceEstimate = treasuryBalanceEstimate;
        this.wageDrainPerDay         = wageDrainPerDay;
        this.taxIncomePerDay         = taxIncomePerDay;
    }

    /** Codec entry point. Merges the legacy float fields into the
     *  category maps so v1 saves keep their sim values. */
    private static VillageSimData fromCodec(UUID villageId,
                                            Map<ResourceCategory, Float> production,
                                            Map<ResourceCategory, Float> consumption,
                                            float legacyFoodProd, float legacyFoodCons,
                                            float legacyMatProd, float legacyMatCons,
                                            int simulatedPopulation,
                                            int farmhouseCount,
                                            int mineCount,
                                            long lastSyncTick,
                                            float treasuryBalanceEstimate,
                                            float wageDrainPerDay,
                                            float taxIncomePerDay) {
        Map<ResourceCategory, Float> prod = production == null ? new HashMap<>() : new HashMap<>(production);
        Map<ResourceCategory, Float> cons = consumption == null ? new HashMap<>() : new HashMap<>(consumption);
        if (legacyFoodProd != 0f) prod.merge(ResourceCategory.FOOD, legacyFoodProd, Float::sum);
        if (legacyMatProd  != 0f) prod.merge(ResourceCategory.BUILDING_MATERIALS, legacyMatProd, Float::sum);
        if (legacyFoodCons != 0f) cons.merge(ResourceCategory.FOOD, legacyFoodCons, Float::sum);
        if (legacyMatCons  != 0f) cons.merge(ResourceCategory.BUILDING_MATERIALS, legacyMatCons, Float::sum);
        return new VillageSimData(villageId, prod, cons, simulatedPopulation,
                farmhouseCount, mineCount, lastSyncTick,
                treasuryBalanceEstimate, wageDrainPerDay, taxIncomePerDay);
    }

    // =========================================================================
    // Category queries
    // =========================================================================

    public float production(ResourceCategory c) {
        if (c == null) return 0f;
        return productionPerDay.getOrDefault(c, 0f);
    }

    public float consumption(ResourceCategory c) {
        if (c == null) return 0f;
        return consumptionPerDay.getOrDefault(c, 0f);
    }

    public float net(ResourceCategory c) {
        return production(c) - consumption(c);
    }

    public boolean isExporterOf(ResourceCategory c) { return net(c) > 0f; }
    public boolean isImporterOf(ResourceCategory c) { return net(c) < 0f; }

    /** Read-only snapshots for debug + UI. */
    public Map<ResourceCategory, Float> productionView() {
        return Collections.unmodifiableMap(productionPerDay);
    }
    public Map<ResourceCategory, Float> consumptionView() {
        return Collections.unmodifiableMap(consumptionPerDay);
    }

    // =========================================================================
    // Sim updates
    // =========================================================================

    /**
     * Blends new real-data measurements into the rolling per-category
     * average. 80% real, 20% existing — matches the legacy formula.
     * Categories present in either side are preserved; any category
     * dropping to zero on the real side decays the stored value
     * toward zero on subsequent days.
     *
     * <p>Negative inputs are clamped to 0 per spec line 217.</p>
     */
    public void blendReal(Map<ResourceCategory, Float> realProd,
                          Map<ResourceCategory, Float> realCons,
                          int realPop, long tick) {
        for (ResourceCategory c : ResourceCategory.values()) {
            float rp = clamp(realProd.getOrDefault(c, 0f));
            float rc = clamp(realCons.getOrDefault(c, 0f));
            float sp = productionPerDay.getOrDefault(c, 0f);
            float sc = consumptionPerDay.getOrDefault(c, 0f);
            float blendedProd = rp * 0.8f + sp * 0.2f;
            float blendedCons = rc * 0.8f + sc * 0.2f;
            putOrRemove(productionPerDay,  c, blendedProd);
            putOrRemove(consumptionPerDay, c, blendedCons);
        }
        simulatedPopulation = realPop;
        lastSyncTick        = tick;
    }

    /**
     * Advances the sim by the given seasonal pressure. FOOD consumption
     * scales with {@code seasonFoodMult} (winter eats more); other
     * categories scale with {@code genericMult} which v1 leaves at 1.0.
     */
    public void advanceSim(float seasonFoodMult, float genericMult) {
        consumptionPerDay.merge(ResourceCategory.FOOD,
                consumptionPerDay.getOrDefault(ResourceCategory.FOOD, 0f) * (seasonFoodMult - 1f),
                Float::sum);
        if (genericMult != 1f) {
            for (ResourceCategory c : ResourceCategory.values()) {
                if (c == ResourceCategory.FOOD) continue;
                float v = consumptionPerDay.getOrDefault(c, 0f);
                if (v != 0f) consumptionPerDay.put(c, v * genericMult);
            }
        }
        treasuryBalanceEstimate += (taxIncomePerDay - wageDrainPerDay);
        treasuryBalanceEstimate = Math.max(0, treasuryBalanceEstimate);
    }

    public void blendRealEconomy(float realTreasuryBalance,
                                 float realWageDrain, float realTaxIncome) {
        this.treasuryBalanceEstimate = realTreasuryBalance;
        this.wageDrainPerDay = realWageDrain * 0.8f + wageDrainPerDay * 0.2f;
        this.taxIncomePerDay = realTaxIncome * 0.8f + taxIncomePerDay * 0.2f;
    }

    // =========================================================================
    // Identity / aggregate getters
    // =========================================================================

    public UUID  getVillageId()              { return villageId; }
    public int   getSimulatedPopulation()    { return simulatedPopulation; }
    public int   getFarmhouseCount()         { return farmhouseCount; }
    public int   getMineCount()              { return mineCount; }
    public long  getLastSyncTick()           { return lastSyncTick; }

    public void setFarmhouseCount(int count) { this.farmhouseCount = count; }
    public void setMineCount(int count)      { this.mineCount = count; }
    public void setSimulatedPopulation(int p){ this.simulatedPopulation = p; }

    public float getTreasuryEstimate()       { return treasuryBalanceEstimate; }
    public float getNetIncomePerDay()        { return taxIncomePerDay - wageDrainPerDay; }
    public boolean isTreasuryHealthy()       { return getNetIncomePerDay() > 0; }

    public boolean wasUnloaded()             { return wasUnloaded; }
    public void setWasUnloaded(boolean v)    { this.wasUnloaded = v; }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static float clamp(float v) {
        return v < 0f ? 0f : v;
    }

    private static void putOrRemove(EnumMap<ResourceCategory, Float> m,
                                    ResourceCategory c, float value) {
        if (value <= 0f) m.remove(c);
        else m.put(c, value);
    }
}
