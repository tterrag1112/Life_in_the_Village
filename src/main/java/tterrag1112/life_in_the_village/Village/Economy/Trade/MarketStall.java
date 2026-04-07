// New file: src/main/java/tterrag1112/life_in_the_village/Village/Economy/MarketStall.java

package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Player-owned market stall for selling farm goods.
 *
 * Features:
 * - Set custom prices (within ±20% of market rate)
 * - Build reputation for quality goods
 * - Track sales and earnings
 * - Unlock specialty crops at high reputation
 */
public class MarketStall {

    public static final Codec<MarketStall> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("ownerId").forGetter(m -> m.ownerId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("marketId").forGetter(m -> m.marketId),
                    Codec.INT.fieldOf("reputation").forGetter(m -> m.reputation),
                    Codec.LONG.fieldOf("totalSales").forGetter(m -> m.totalSales),
                    Codec.LONG.fieldOf("rentalPaid").forGetter(m -> m.rentalPaid)
            ).apply(instance, MarketStall::new)
    );

    private final UUID ownerId;        // Player UUID
    private final UUID marketId;       // Market building ID
    private int reputation;             // 0-100, affects unlock
    private long totalSales;            // All-time sales revenue
    private long rentalPaid;            // Rent paid this week

    private final Map<Item, Integer> customPrices; // Item -> price in bronze
    private final Map<Item, Integer> stock;        // Item -> quantity

    private static final long WEEKLY_RENT = 50L; // Bronze coins per week

    public MarketStall(UUID ownerId, UUID marketId, int reputation,
                       long totalSales, long rentalPaid) {
        this.ownerId = ownerId;
        this.marketId = marketId;
        this.reputation = reputation;
        this.totalSales = totalSales;
        this.rentalPaid = rentalPaid;
        this.customPrices = new HashMap<>();
        this.stock = new HashMap<>();
    }

    public MarketStall(UUID ownerId, UUID marketId) {
        this(ownerId, marketId, 0, 0L, 0L);
    }

    /**
     * Set custom price for an item (within ±20% of base market price)
     */
    public boolean setPrice(Item item, long price, long baseMarketPrice) {
        long minPrice = (long)(baseMarketPrice * 0.8);
        long maxPrice = (long)(baseMarketPrice * 1.2);

        if (price < minPrice || price > maxPrice) {
            return false;
        }

        customPrices.put(item, (int)price);
        return true;
    }

    /**
     * Get price for an item (custom or default)
     */
    public long getPrice(Item item, long baseMarketPrice) {
        return customPrices.getOrDefault(item, (int)baseMarketPrice);
    }

    /**
     * Add stock to stall
     */
    public void addStock(Item item, int quantity) {
        stock.put(item, stock.getOrDefault(item, 0) + quantity);
    }

    /**
     * Record a sale
     */
    public void recordSale(Item item, int quantity, long revenue) {
        stock.put(item, Math.max(0, stock.getOrDefault(item, 0) - quantity));
        totalSales += revenue;

        // Reputation increases with consistent sales
        if (revenue > 10) {
            reputation = Math.min(100, reputation + 1);
        }
    }

    /**
     * Check if weekly rent is paid
     */
    public boolean isRentPaid() {
        return rentalPaid >= WEEKLY_RENT;
    }

    /**
     * Pay weekly rent
     */
    public void payRent() {
        rentalPaid = WEEKLY_RENT;
    }

    /**
     * Reset weekly rent (called every 7 days)
     */
    public void resetWeeklyRent() {
        rentalPaid = 0;
    }

    /**
     * Check if player has unlocked specialty crops
     */
    public boolean hasSpecialtyCropsUnlocked() {
        return reputation >= 75;
    }

    /**
     * Get reputation tier name
     */
    public String getReputationTier() {
        if (reputation >= 90) return "Legendary Vendor";
        if (reputation >= 75) return "Master Vendor";
        if (reputation >= 50) return "Skilled Vendor";
        if (reputation >= 25) return "Apprentice Vendor";
        return "Novice Vendor";
    }

    // Getters
    public UUID getOwnerId() { return ownerId; }
    public UUID getMarketId() { return marketId; }
    public int getReputation() { return reputation; }
    public long getTotalSales() { return totalSales; }
    public Map<Item, Integer> getStock() { return stock; }
}