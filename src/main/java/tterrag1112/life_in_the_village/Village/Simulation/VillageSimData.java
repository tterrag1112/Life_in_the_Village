// src/main/java/tterrag1112/life_in_the_village/Village/Simulation/VillageSimData.java
package tterrag1112.life_in_the_village.Village.Simulation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.UUID;

/**
 * Lightweight simulation snapshot for a single village.
 *
 * <h3>Purpose</h3>
 * When a village's chunks are not loaded, the game cannot read actual NPC
 * counts, crop states, or stockpile inventories. This record stores a
 * rolling average of key production and consumption rates so the kingdom
 * economy engine can reason about all villages, loaded or not.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li><b>At village spawn</b> — a baseline estimate is computed from
 *       the village type's building and NPC counts. See
 *       {@link VillageSimEngine#buildBaseline}.</li>
 *   <li><b>While loaded</b> — each server day the engine samples real
 *       production rates and runs a weighted average: 80% real, 20% sim.
 *       This smooths out outlier ticks (bad harvest, player theft, etc.)
 *       while converging toward truth quickly.</li>
 *   <li><b>While unloaded</b> — the engine advances the sim forward using
 *       only the stored rates and the current season multiplier. No chunk
 *       access is needed.</li>
 * </ol>
 *
 * <h3>Units</h3>
 * All rates are in <em>nutrition units per in-game day</em> for food, and
 * <em>item units per in-game day</em> for materials. One in-game day =
 * 24 000 ticks.
 */
public class VillageSimData {

    // =========================================================================
    // Codec
    // =========================================================================

    public static final Codec<VillageSimData> CODEC = RecordCodecBuilder.create(i ->
            i.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("villageId")
                            .forGetter(d -> d.villageId),
                    Codec.FLOAT.fieldOf("foodProductionPerDay")
                            .forGetter(d -> d.foodProductionPerDay),
                    Codec.FLOAT.fieldOf("foodConsumptionPerDay")
                            .forGetter(d -> d.foodConsumptionPerDay),
                    Codec.FLOAT.fieldOf("materialProductionPerDay")
                            .forGetter(d -> d.materialProductionPerDay),
                    Codec.FLOAT.fieldOf("materialConsumptionPerDay")
                            .forGetter(d -> d.materialConsumptionPerDay),
                    Codec.INT.fieldOf("simulatedPopulation")
                            .forGetter(d -> d.simulatedPopulation),
                    Codec.INT.fieldOf("farmhouseCount")
                            .forGetter(d -> d.farmhouseCount),
                    Codec.INT.fieldOf("mineCount")
                            .forGetter(d -> d.mineCount),
                    Codec.LONG.fieldOf("lastSyncTick")
                            .forGetter(d -> d.lastSyncTick),
                    Codec.FLOAT.optionalFieldOf("treasuryBalanceEstimate", 0f)
                            .forGetter(d -> d.treasuryBalanceEstimate),
                    Codec.FLOAT.optionalFieldOf("wageDrainPerDay", 0f)
                            .forGetter(d -> d.wageDrainPerDay),
                    Codec.FLOAT.optionalFieldOf("taxIncomePerDay", 0f)
                            .forGetter(d -> d.taxIncomePerDay)
            ).apply(i, VillageSimData::new));

    // =========================================================================
    // Fields
    // =========================================================================

    private final UUID  villageId;

    /** Nutrition units produced per in-game day (all farmhouses combined). */
    private float foodProductionPerDay;
    /** Nutrition units consumed per in-game day (population × season factor). */
    private float foodConsumptionPerDay;
    /** Building material units produced per day (mines + lumber). */
    private float materialProductionPerDay;
    /** Building material units consumed per day (builder goals, repairs). */
    private float materialConsumptionPerDay;
    /** Simulated population (NPCs alive in this village). */
    private int   simulatedPopulation;
    /** Number of farmhouse buildings — drives food production estimate. */
    private int   farmhouseCount;
    /** Number of mine buildings — drives material production estimate. */
    private int   mineCount;
    /** Game tick of the last real-data sync (0 = never synced). */
    private long  lastSyncTick;
    private boolean wasUnloaded = false;
    private float treasuryBalanceEstimate;
    private float wageDrainPerDay;
    private float taxIncomePerDay;

    // =========================================================================
    // Constructor
    // =========================================================================

    public VillageSimData(UUID villageId,
                          float foodProductionPerDay,
                          float foodConsumptionPerDay,
                          float materialProductionPerDay,
                          float materialConsumptionPerDay,
                          int   simulatedPopulation,
                          int   farmhouseCount,
                          int   mineCount,
                          long  lastSyncTick,
                          float treasuryBalanceEstimate,
                          float wageDrainPerDay,
                          float taxIncomePerDay) {
        this.villageId              = villageId;
        this.foodProductionPerDay   = foodProductionPerDay;
        this.foodConsumptionPerDay  = foodConsumptionPerDay;
        this.materialProductionPerDay  = materialProductionPerDay;
        this.materialConsumptionPerDay = materialConsumptionPerDay;
        this.simulatedPopulation    = simulatedPopulation;
        this.farmhouseCount         = farmhouseCount;
        this.mineCount              = mineCount;
        this.lastSyncTick           = lastSyncTick;
        this.treasuryBalanceEstimate = treasuryBalanceEstimate;
        this.wageDrainPerDay = wageDrainPerDay;
        this.taxIncomePerDay = taxIncomePerDay;
    }

    // =========================================================================
    // Derived queries
    // =========================================================================

    /**
     * Net food balance per day. Positive = surplus, negative = deficit.
     */
    public float foodNetPerDay() {
        return foodProductionPerDay - foodConsumptionPerDay;
    }

    /**
     * Net material balance per day.
     */
    public float materialNetPerDay() {
        return materialProductionPerDay - materialConsumptionPerDay;
    }

    /**
     * True if this village is a net food exporter (produces more than it needs).
     */
    public boolean isFoodExporter() { return foodNetPerDay() > 0; }

    /**
     * True if this village has a material surplus.
     */
    public boolean isMaterialExporter() { return materialNetPerDay() > 0; }

    // =========================================================================
    // Update methods — called by VillageSimEngine
    // =========================================================================

    /**
     * Blends new real-data measurements into the rolling average.
     * 80% weight on the new measurement, 20% on the existing estimate.
     * This converges quickly toward truth while smoothing bad ticks.
     */
    public void blendReal(float realFoodProd, float realFoodCons,
                          float realMatProd,  float realMatCons,
                          int   realPop,      long  tick) {
        foodProductionPerDay   = realFoodProd   * 0.8f + foodProductionPerDay   * 0.2f;
        foodConsumptionPerDay  = realFoodCons   * 0.8f + foodConsumptionPerDay  * 0.2f;
        materialProductionPerDay  = realMatProd * 0.8f + materialProductionPerDay  * 0.2f;
        materialConsumptionPerDay = realMatCons * 0.8f + materialConsumptionPerDay * 0.2f;
        simulatedPopulation    = realPop;
        lastSyncTick           = tick;


    }

    /** Advances the sim by the given day count using only stored rates + season. */
    public void advanceSim(float seasonFoodMult, float seasonMatMult) {
        foodConsumptionPerDay     *= seasonFoodMult;
        materialConsumptionPerDay *= seasonMatMult;
        // Treasury simulation
        treasuryBalanceEstimate += (taxIncomePerDay - wageDrainPerDay);
        treasuryBalanceEstimate = Math.max(0, treasuryBalanceEstimate);
    }

    // =========================================================================
    // Getters / setters
    // =========================================================================

    public UUID  getVillageId()              { return villageId;              }
    public float getFoodProductionPerDay()   { return foodProductionPerDay;   }
    public float getFoodConsumptionPerDay()  { return foodConsumptionPerDay;  }
    public float getMaterialProductionPerDay()  { return materialProductionPerDay;  }
    public float getMaterialConsumptionPerDay() { return materialConsumptionPerDay; }
    public int   getSimulatedPopulation()    { return simulatedPopulation;    }
    public int   getFarmhouseCount()         { return farmhouseCount;         }
    public int   getMineCount()              { return mineCount;              }
    public long  getLastSyncTick()           { return lastSyncTick;           }

    public void setFarmhouseCount(int count) { this.farmhouseCount = count; }
    public void setMineCount(int count)      { this.mineCount = count;      }
    public void setSimulatedPopulation(int p){ this.simulatedPopulation = p; }
    // Coin tracking
    public float getTreasuryEstimate()  { return treasuryBalanceEstimate; }
    public float getNetIncomePerDay()   { return taxIncomePerDay - wageDrainPerDay; }
    public boolean isTreasuryHealthy()  { return getNetIncomePerDay() > 0; }

    public void blendRealEconomy(float realTreasuryBalance,
                                 float realWageDrain, float realTaxIncome) {
        this.treasuryBalanceEstimate = realTreasuryBalance;
        this.wageDrainPerDay = realWageDrain * 0.8f + wageDrainPerDay * 0.2f;
        this.taxIncomePerDay = realTaxIncome * 0.8f + taxIncomePerDay * 0.2f;
    }

    // Unloaded tracking (runtime only)
    public boolean wasUnloaded()          { return wasUnloaded; }
    public void setWasUnloaded(boolean v) { this.wasUnloaded = v; }
}