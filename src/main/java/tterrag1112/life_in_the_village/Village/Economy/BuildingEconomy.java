package tterrag1112.life_in_the_village.Village.Economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.UUID;

/**
 * Virtual business treasury attached to a building.
 *
 * <p>Separate from the NPC's personal wallet. Funds here pay for
 * production inputs (seeds, ore, raw materials). Revenue from selling
 * goods flows back in here, not into the NPC's personal wallet.</p>
 *
 * <h3>Replenishment</h3>
 * When an NPC sells produced goods to the merchant or directly to another
 * NPC, the payment goes to the business treasury. The NPC draws a personal
 * wage from this treasury at the end of each work day.
 *
 * <h3>Stored in VillageSavedData</h3>
 * Keyed by building UUID, stored in a new {@code buildingEconomies} map in
 * {@code VillageEconomyData}.
 */
public final class BuildingEconomy {

    public static final Codec<BuildingEconomy> CODEC = RecordCodecBuilder.create(i ->
            i.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("buildingId")
                            .forGetter(b -> b.buildingId),
                    Codec.LONG.optionalFieldOf("treasury", 0L)
                            .forGetter(b -> b.treasury),
                    Codec.LONG.optionalFieldOf("totalRevenue", 0L)
                            .forGetter(b -> b.totalRevenue),
                    Codec.LONG.optionalFieldOf("totalSpent", 0L)
                            .forGetter(b -> b.totalSpent),
                    // R4c — consecutive days the building couldn't sustain its
                    // upkeep+wage (drives temple decay/abandonment). Optional so
                    // pre-R4c saves load at 0.
                    Codec.INT.optionalFieldOf("daysInsolvent", 0)
                            .forGetter(b -> b.daysInsolvent)
            ).apply(i, BuildingEconomy::new));

    private final UUID buildingId;
    private long treasury;
    private long totalRevenue;
    private long totalSpent;
    private int  daysInsolvent;

    public BuildingEconomy(UUID buildingId, long treasury,
                           long totalRevenue, long totalSpent, int daysInsolvent) {
        this.buildingId    = buildingId;
        this.treasury      = treasury;
        this.totalRevenue  = totalRevenue;
        this.totalSpent    = totalSpent;
        this.daysInsolvent = daysInsolvent;
    }

    public static BuildingEconomy create(UUID buildingId, long starterFunds) {
        return new BuildingEconomy(buildingId, starterFunds, 0, 0, 0);
    }

    public UUID getBuildingId()    { return buildingId; }
    public long getTreasury()      { return treasury; }
    public long getTotalRevenue()  { return totalRevenue; }
    public long getTotalSpent()    { return totalSpent; }

    /** R4c — consecutive days this building couldn't cover its daily costs. */
    public int  getDaysInsolvent()       { return daysInsolvent; }
    public void incrementDaysInsolvent() { daysInsolvent++; }
    public void resetDaysInsolvent()     { daysInsolvent = 0; }

    public boolean canAfford(long bronze) { return treasury >= bronze; }

    /** Deposit revenue into the business treasury. */
    public void depositRevenue(long bronze) {
        treasury      += bronze;
        totalRevenue  += bronze;
    }

    /**
     * Withdraw for a business purchase. Returns actual amount withdrawn
     * (may be less if treasury is low).
     */
    public long withdraw(long bronze) {
        long actual = Math.min(bronze, treasury);
        treasury    -= actual;
        totalSpent  += actual;
        return actual;
    }

    /** Seed the treasury without tracking as revenue (e.g. starter funds). */
    public void seed(long bronze) { treasury += bronze; }
}