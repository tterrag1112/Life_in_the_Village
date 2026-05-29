package tterrag1112.life_in_the_village.Village.Economy.Market;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Represents a single rented/purchased market stall slot.
 *
 * <p>Each stall corresponds to one anchor slot in the market NBT. When a
 * seller claims a slot, {@link MarketStallPlacer} stamps the stall NBT into
 * the world and this record is persisted in {@code VillageSavedData}.</p>
 *
 * <h3>Chest position</h3>
 * {@code chestPos} is the absolute world position of the stall's chest block,
 * derived by {@code MarketStallPlacer} from the anchor offset at placement
 * time. {@code TradeHandler} uses it to route payment directly to the
 * stall owner rather than the market merchant.
 *
 * <h3>Rent</h3>
 * {@code rentPaidUntilTick} tracks when the current rental period expires.
 * A value of {@code Long.MAX_VALUE} indicates a purchased (permanent) stall.
 * Stalls with an expired tick are reclaimed by {@code MarketRentManager}
 * during the daily village tick.
 *
 * <h3>Pricing & reputation</h3>
 * Stall owners may set custom prices on individual items within ±20% of the
 * market base price via {@link #setCustomPrice}. Completed sales accumulate
 * into {@link #totalSales} and {@link #reputation} (0–100), which drives
 * {@link ReputationTier} for UI display and future perk gating.
 */
public class MarketStall {

    // ── Codec ──────────────────────────────────────────────────────────────────

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    /** Item id (string) → custom price (bronze). Stored as a Map codec. */
    private static final Codec<Map<String, Long>> CUSTOM_PRICES_CODEC =
            Codec.unboundedMap(Codec.STRING, Codec.LONG);

    public static final Codec<MarketStall> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("stallId")
                    .forGetter(MarketStall::getStallId),
            UUID_CODEC.fieldOf("marketBuildingId")
                    .forGetter(MarketStall::getMarketBuildingId),
            Codec.INT.fieldOf("slotIndex")
                    .forGetter(MarketStall::getSlotIndex),
            BlockPos.CODEC.fieldOf("stallOrigin")
                    .forGetter(MarketStall::getStallOrigin),
            BlockPos.CODEC.optionalFieldOf("chestPos", BlockPos.ZERO)
                    .forGetter(MarketStall::getChestPos),
            UUID_CODEC.fieldOf("ownerUUID")
                    .forGetter(MarketStall::getOwnerUUID),
            Codec.STRING.optionalFieldOf("ownerDisplayName", "")
                    .forGetter(MarketStall::getOwnerDisplayName),
            Codec.STRING.xmap(OwnerType::valueOf, OwnerType::name)
                    .fieldOf("ownerType")
                    .forGetter(MarketStall::getOwnerType),
            Codec.LONG.fieldOf("rentPaidUntilTick")
                    .forGetter(MarketStall::getRentPaidUntilTick),
            Codec.BOOL.fieldOf("active")
                    .forGetter(MarketStall::isActive),
            Codec.INT.optionalFieldOf("reputation", 0)
                    .forGetter(MarketStall::getReputation),
            Codec.LONG.optionalFieldOf("totalSales", 0L)
                    .forGetter(MarketStall::getTotalSales),
            CUSTOM_PRICES_CODEC.optionalFieldOf("customPrices", Map.of())
                    .forGetter(MarketStall::getCustomPricesRaw)
    ).apply(i, MarketStall::new));

    // ── Owner type ─────────────────────────────────────────────────────────────

    public enum OwnerType {
        /** Village NPC (merchant, producer, etc.) */
        NPC,
        /** Human player */
        PLAYER,
        /** A wandering trader visiting for an event */
        WANDERING_TRADER
    }

    // ── Reputation tier ────────────────────────────────────────────────────────

    /**
     * Stall reputation tier, derived from {@link #reputation} (0-100).
     * Shown in stall UI and used to gate future perks (discounts, rare stock,
     * specialty items).
     */
    public enum ReputationTier {
        NOVICE     (  0, "Novice Vendor",     ChatFormatting.GRAY),
        APPRENTICE ( 25, "Apprentice Vendor", ChatFormatting.WHITE),
        SKILLED    ( 50, "Skilled Vendor",    ChatFormatting.GREEN),
        MASTER     ( 75, "Master Vendor",     ChatFormatting.AQUA),
        LEGENDARY  ( 90, "Legendary Vendor",  ChatFormatting.GOLD);

        public final int minScore;
        public final String displayName;
        public final ChatFormatting colour;

        ReputationTier(int minScore, String displayName, ChatFormatting colour) {
            this.minScore    = minScore;
            this.displayName = displayName;
            this.colour      = colour;
        }

        public static ReputationTier fromScore(int score) {
            ReputationTier result = NOVICE;
            for (ReputationTier t : values()) {
                if (score >= t.minScore) result = t;
            }
            return result;
        }
    }

    /** Custom price must stay within ±20% of the base market price. */
    public static final double PRICE_BAND = 0.20;
    public static final int    MAX_REPUTATION = 100;

    /**
     * Sentinel owner for a placed-but-unclaimed ("for rent") stall
     * (merchant arc Phase 2b). Lets a stall be seeded on the pad with no
     * real owner without adding a {@code VACANT} {@link OwnerType} arm
     * (which would force an enum-switch audit). {@link #isVacant()} is the
     * canonical check; vacant stalls are skipped by rent and read as
     * "For Rent" on their sign.
     */
    public static final UUID VACANT_UUID = new UUID(0L, 0L);

    // ── Fields ─────────────────────────────────────────────────────────────────

    private final UUID     stallId;
    private final UUID     marketBuildingId;
    private final int      slotIndex;
    private final BlockPos stallOrigin;  // absolute world pos the stall NBT was placed at
    private       BlockPos chestPos;     // absolute pos of the stall chest (set post-placement)
    private       UUID     ownerUUID;
    private       String   ownerDisplayName;
    private       OwnerType ownerType;
    private       long     rentPaidUntilTick;
    private       boolean  active;

    // Stats
    private       int      reputation;   // 0..MAX_REPUTATION
    private       long     totalSales;   // all-time bronze earned through this stall
    private final Map<String, Long> customPrices; // itemId → bronze price override

    // ── Canonical constructor (matches codec) ─────────────────────────────────

    public MarketStall(UUID stallId,
                       UUID marketBuildingId,
                       int slotIndex,
                       BlockPos stallOrigin,
                       BlockPos chestPos,
                       UUID ownerUUID,
                       String ownerDisplayName,
                       OwnerType ownerType,
                       long rentPaidUntilTick,
                       boolean active,
                       int reputation,
                       long totalSales,
                       Map<String, Long> customPrices) {
        this.stallId           = stallId;
        this.marketBuildingId  = marketBuildingId;
        this.slotIndex         = slotIndex;
        this.stallOrigin       = stallOrigin;
        this.chestPos          = chestPos;
        this.ownerUUID         = ownerUUID;
        this.ownerDisplayName  = ownerDisplayName == null ? "" : ownerDisplayName;
        this.ownerType         = ownerType;
        this.rentPaidUntilTick = rentPaidUntilTick;
        this.active            = active;
        this.reputation        = Math.max(0, Math.min(MAX_REPUTATION, reputation));
        this.totalSales        = totalSales;
        this.customPrices      = new HashMap<>(customPrices);
    }

    // ── Legacy constructor for back-compat with existing callers ─────────────

    public MarketStall(UUID stallId, UUID marketBuildingId, int slotIndex,
                       BlockPos stallOrigin, BlockPos chestPos,
                       UUID ownerUUID, String ownerDisplayName, OwnerType ownerType,
                       long rentPaidUntilTick, boolean active) {
        this(stallId, marketBuildingId, slotIndex, stallOrigin, chestPos,
                ownerUUID, ownerDisplayName, ownerType, rentPaidUntilTick, active,
                0, 0L, Map.of());
    }

    // ── Factory ────────────────────────────────────────────────────────────────

    /**
     * Creates a new stall record before the NBT has been stamped.
     * {@code chestPos} defaults to {@code BlockPos.ZERO} and should be
     * updated by {@link MarketStallPlacer} after the stall structure is placed.
     */
    public static MarketStall create(UUID marketBuildingId, int slotIndex,
                                     BlockPos stallOrigin,
                                     UUID ownerUUID, String ownerDisplayName,
                                     OwnerType ownerType,
                                     long rentPaidUntilTick) {
        return new MarketStall(UUID.randomUUID(), marketBuildingId,
                slotIndex, stallOrigin, BlockPos.ZERO,
                ownerUUID, ownerDisplayName, ownerType, rentPaidUntilTick, true);
    }

    // ── Getters ────────────────────────────────────────────────────────────────

    public UUID      getStallId()           { return stallId; }
    public UUID      getMarketBuildingId()  { return marketBuildingId; }
    public int       getSlotIndex()         { return slotIndex; }
    public BlockPos  getStallOrigin()       { return stallOrigin; }
    public BlockPos  getChestPos()          { return chestPos; }
    public UUID      getOwnerUUID()         { return ownerUUID; }
    public String    getOwnerDisplayName()  { return ownerDisplayName; }
    public OwnerType getOwnerType()         { return ownerType; }
    public long      getRentPaidUntilTick() { return rentPaidUntilTick; }
    public boolean   isActive()             { return active; }
    public int       getReputation()        { return reputation; }
    public long      getTotalSales()        { return totalSales; }

    /** Exposed for codec serialization — do not mutate. */
    public Map<String, Long> getCustomPricesRaw() { return customPrices; }

    public ReputationTier getReputationTier() {
        return ReputationTier.fromScore(reputation);
    }

    // ── Mutators ───────────────────────────────────────────────────────────────

    public void setChestPos(BlockPos pos)            { this.chestPos = pos; }
    public void setOwner(UUID uuid, OwnerType type)  { this.ownerUUID = uuid; this.ownerType = type; }
    public void setOwnerDisplayName(String name)     { this.ownerDisplayName = name == null ? "" : name; }
    public void setRentPaidUntilTick(long tick)      { this.rentPaidUntilTick = tick; }
    public void setActive(boolean active)            { this.active = active; }

    /** Permanent purchase — stall never expires. */
    public void setPurchased()                       { this.rentPaidUntilTick = Long.MAX_VALUE; }

    public boolean isExpired(long currentTick) {
        return active && rentPaidUntilTick != Long.MAX_VALUE
                && currentTick > rentPaidUntilTick;
    }

    public boolean isPurchased() {
        return rentPaidUntilTick == Long.MAX_VALUE;
    }

    /** True when this stall is placed but unclaimed (seeded "for rent"). */
    public boolean isVacant() {
        return VACANT_UUID.equals(ownerUUID);
    }

    // =========================================================================
    // Custom pricing (±20% band)
    // =========================================================================

    /**
     * Sets a custom price for an item, constrained to within
     * {@link #PRICE_BAND} of the base market price.
     *
     * @return true if accepted, false if out of band
     */
    public boolean setCustomPrice(Item item, long price, long baseMarketPrice) {
        if (baseMarketPrice <= 0) return false;
        long min = (long) Math.floor(baseMarketPrice * (1.0 - PRICE_BAND));
        long max = (long) Math.ceil (baseMarketPrice * (1.0 + PRICE_BAND));
        if (price < min || price > max) return false;
        customPrices.put(itemKey(item), price);
        return true;
    }

    /** Clears any custom override for an item — stall reverts to base price. */
    public void clearCustomPrice(Item item) {
        customPrices.remove(itemKey(item));
    }

    /** Returns the custom price for an item if set. */
    public Optional<Long> getCustomPrice(Item item) {
        Long p = customPrices.get(itemKey(item));
        return Optional.ofNullable(p);
    }

    /**
     * Returns the effective price for this stall — custom override if set,
     * otherwise the supplied base market price.
     */
    public long getEffectivePrice(Item item, long baseMarketPrice) {
        return getCustomPrice(item).orElse(baseMarketPrice);
    }

    private static String itemKey(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    // =========================================================================
    // Sales tracking & reputation
    // =========================================================================

    /**
     * Records a completed sale through this stall. Updates lifetime totals
     * and nudges reputation upward for meaningful transactions.
     *
     * @param revenueBronze total bronze earned from this sale
     */
    public void recordSale(long revenueBronze) {
        if (revenueBronze <= 0) return;
        totalSales += revenueBronze;
        // Meaningful sales (>10 bronze) nudge reputation; trivial sales don't.
        if (revenueBronze > 10 && reputation < MAX_REPUTATION) {
            reputation = Math.min(MAX_REPUTATION, reputation + 1);
        }
    }

    /** Manual reputation adjustment — used by events, penalties, etc. */
    public void adjustReputation(int delta) {
        reputation = Math.max(0, Math.min(MAX_REPUTATION, reputation + delta));
    }
}