package tterrag1112.life_in_the_village.Village.Economy.Market;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

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
 */
public class MarketStall {

    // ── Codec ──────────────────────────────────────────────────────────────────

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

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
                    .forGetter(MarketStall::isActive)
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

    // ── Fields ─────────────────────────────────────────────────────────────────

    private final UUID stallId;
    private final UUID marketBuildingId;
    private final int  slotIndex;
    private final BlockPos stallOrigin;   // absolute world pos the stall NBT was placed at
    private       BlockPos chestPos;      // absolute pos of the stall chest (set post-placement)
    private       UUID ownerUUID;
    private       OwnerType ownerType;
    private       long rentPaidUntilTick;
    private       boolean active;

    public MarketStall(UUID stallId, UUID marketBuildingId, int slotIndex,
                       BlockPos stallOrigin, BlockPos chestPos,
                       UUID ownerUUID,String ownerDisplayName, OwnerType ownerType,
                       long rentPaidUntilTick, boolean active) {
        this.stallId           = stallId;
        this.marketBuildingId  = marketBuildingId;
        this.slotIndex         = slotIndex;
        this.stallOrigin       = stallOrigin;
        this.chestPos          = chestPos;
        this.ownerUUID         = ownerUUID;
        this.ownerDisplayName = ownerDisplayName;
        this.ownerType         = ownerType;
        this.rentPaidUntilTick = rentPaidUntilTick;
        this.active            = active;
    }

    // ── Factory ────────────────────────────────────────────────────────────────

    /**
     * Creates a new stall record before the NBT has been stamped.
     * {@code chestPos} defaults to {@code BlockPos.ZERO} and should be
     * updated by {@link MarketStallPlacer} after the stall structure is placed.
     */
    public static MarketStall create(UUID marketBuildingId, int slotIndex,
                                    BlockPos stallOrigin,
                                    UUID ownerUUID, String ownerDisplayName, OwnerType ownerType,
                                    long rentPaidUntilTick) {
        MarketStall s = new MarketStall(UUID.randomUUID(), marketBuildingId,
                slotIndex, stallOrigin, BlockPos.ZERO,
                ownerUUID, ownerDisplayName, ownerType, rentPaidUntilTick, true);
        return s;
    }

    // ── Getters ────────────────────────────────────────────────────────────────

    public UUID      getStallId()           { return stallId; }
    private String ownerDisplayName;
    public UUID      getMarketBuildingId()  { return marketBuildingId; }
    public int       getSlotIndex()         { return slotIndex; }
    public BlockPos  getStallOrigin()       { return stallOrigin; }
    public BlockPos  getChestPos()          { return chestPos; }
    public UUID      getOwnerUUID()         { return ownerUUID; }
    public OwnerType getOwnerType()         { return ownerType; }
    public long      getRentPaidUntilTick() { return rentPaidUntilTick; }
    public boolean   isActive()            { return active; }

    // ── Mutators ───────────────────────────────────────────────────────────────

    public void setChestPos(BlockPos pos)            { this.chestPos = pos; }
    public void setOwner(UUID uuid, OwnerType type)  { this.ownerUUID = uuid; this.ownerType = type; }
    public void setRentPaidUntilTick(long tick)      { this.rentPaidUntilTick = tick; }
    public void setActive(boolean active)            { this.active = active; }

    public String getOwnerDisplayName()            { return ownerDisplayName; }
    public void setOwnerDisplayName(String name)   { this.ownerDisplayName = name; }

    /** Permanent purchase — stall never expires. */
    public void setPurchased()                       { this.rentPaidUntilTick = Long.MAX_VALUE; }

    public boolean isExpired(long currentTick) {
        return active && rentPaidUntilTick != Long.MAX_VALUE
                && currentTick > rentPaidUntilTick;
    }

    public boolean isPurchased() {
        return rentPaidUntilTick == Long.MAX_VALUE;
    }
}