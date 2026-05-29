// src/main/java/tterrag1112/life_in_the_village/Village/Economy/VillageTreasury.java
package tterrag1112.life_in_the_village.Village.Economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.EconomyBalanceRegistry;

import java.util.UUID;

/**
 * Persistent village treasury — the shared coin pool that receives taxes
 * and pays wages.
 *
 * <p>Stored inside {@code VillageSavedData} alongside the Village record.
 * The treasury is NOT a physical chest — it's an abstract ledger that
 * represents coins held by the village government (town hall, lord, etc.).</p>
 *
 * <h3>Income sources</h3>
 * <ul>
 *   <li>Property tax — collected from house owners (players and NPCs)</li>
 *   <li>Market tax — percentage cut of every market transaction</li>
 *   <li>Trade tariff — cut of inter-village caravan trade</li>
 *   <li>Baseline income — simulated tax from unloaded population</li>
 * </ul>
 *
 * <h3>Expenses</h3>
 * <ul>
 *   <li>Guard wages — paid daily to each guard NPC</li>
 *   <li>Stockpile purchases — keeper buys from market using treasury funds</li>
 *   <li>Building maintenance — repair costs</li>
 * </ul>
 */
public class VillageTreasury {

    public static final Codec<VillageTreasury> CODEC = RecordCodecBuilder.create(i ->
            i.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("villageId")
                            .forGetter(t -> t.villageId),
                    Codec.LONG.fieldOf("balance")
                            .forGetter(t -> t.balance),
                    Codec.LONG.fieldOf("totalTaxCollected")
                            .forGetter(t -> t.totalTaxCollected),
                    Codec.LONG.fieldOf("totalWagesPaid")
                            .forGetter(t -> t.totalWagesPaid),
                    Codec.LONG.fieldOf("lastPayday")
                            .forGetter(t -> t.lastPayday)
            ).apply(i, VillageTreasury::new));

    // ── Tax / wage rates ───────────────────────────────────────────────
    // Phase 1d: externalized to the data-driven economy balance config.
    // These accessors read live (so /reload takes effect) and default to
    // the pre-1d values when no config is present. Replaces the old
    // public static final constants (which the compiler would have inlined).

    /** Fraction of market transactions taken as tax (1/DIVISOR). */
    public static long marketTaxDivisor() {
        return EconomyBalanceRegistry.balance().treasury().marketTaxDivisor();
    }
    /** Bronze per house per day — property tax. */
    public static long propertyTaxPerHouse() {
        return EconomyBalanceRegistry.balance().treasury().propertyTaxPerHouse();
    }
    /** Bronze per NPC per day — simulated baseline income for unloaded. */
    public static long baselineIncomePerNpc() {
        return EconomyBalanceRegistry.balance().treasury().baselineIncomePerNpc();
    }
    /** Bronze per guard per day. */
    public static long guardWage() {
        return EconomyBalanceRegistry.balance().treasury().guardWage();
    }
    /** Bronze per stockpile keeper per day. */
    public static long keeperWage() {
        return EconomyBalanceRegistry.balance().treasury().keeperWage();
    }
    /** Bronze per innkeeper per day. */
    public static long innkeeperWage() {
        return EconomyBalanceRegistry.balance().treasury().innkeeperWage();
    }
    /** Bronze per merchant per day. */
    public static long merchantWage() {
        return EconomyBalanceRegistry.balance().treasury().merchantWage();
    }

    // ── Fields ─────────────────────────────────────────────────────────
    private final UUID villageId;
    private long balance;
    private long totalTaxCollected;
    private long totalWagesPaid;
    private long lastPayday;

    public VillageTreasury(UUID villageId, long balance,
                           long totalTaxCollected, long totalWagesPaid,
                           long lastPayday) {
        this.villageId = villageId;
        this.balance = balance;
        this.totalTaxCollected = totalTaxCollected;
        this.totalWagesPaid = totalWagesPaid;
        this.lastPayday = lastPayday;
    }

    /** Create a new treasury with starter funds. */
    public static VillageTreasury create(UUID villageId, long starterFunds) {
        return new VillageTreasury(villageId, starterFunds, 0, 0, 0);
    }

    // ── Deposit / withdraw ─────────────────────────────────────────────

    /** Add coins to the treasury (tax income, tariffs, etc). */
    public void deposit(long amount) {
        if (amount <= 0) return;
        balance += amount;
        totalTaxCollected += amount;
    }

    /**
     * Withdraw coins from the treasury (wages, purchases).
     * Returns the actual amount withdrawn (may be less if balance is low).
     */
    public long withdraw(long amount) {
        if (amount <= 0) return 0;
        long actual = Math.min(amount, balance);
        balance -= actual;
        return actual;
    }

    /**
     * Pay a wage — withdraw and track separately.
     * Returns true if the full wage was paid.
     */
    public boolean payWage(long amount) {
        if (balance < amount) return false;
        balance -= amount;
        totalWagesPaid += amount;
        return true;
    }

    /** Collect market tax from a transaction total. */
    public long collectMarketTax(long transactionTotal) {
        long tax = transactionTotal / marketTaxDivisor();
        if (tax <= 0) return 0;
        deposit(tax);
        return tax;
    }

    // ── Queries ────────────────────────────────────────────────────────

    public UUID getVillageId()       { return villageId; }
    public long getBalance()         { return balance; }
    public long getTotalTaxCollected(){ return totalTaxCollected; }
    public long getTotalWagesPaid()   { return totalWagesPaid; }
    public long getLastPayday()      { return lastPayday; }

    public CurrencyValue getBalanceAsCurrency() {
        return CurrencyValue.of(balance);
    }

    public boolean canAfford(long amount) {
        return balance >= amount;
    }

    public void setLastPayday(long tick) {
        this.lastPayday = tick;
    }

    @Override
    public String toString() {
        return "Treasury[" + CurrencyValue.of(balance) + "]";
    }
}