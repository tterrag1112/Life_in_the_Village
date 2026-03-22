// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Orders/CraftingOrder.java
package tterrag1112.life_in_the_village.Village.Economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * A commission posted to the town hall notice board by an NPC or the village
 * economy, requesting a specific item be crafted and delivered.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>An NPC (blacksmith, stockpile keeper, etc.) or
 *       {@link CraftingOrderManager} posts an OPEN order when its
 *       building is short of a required item.</li>
 *   <li>A player claims the order — status becomes CLAIMED and
 *       {@code claimedByPlayerId} is set.</li>
 *   <li>The player deposits the required items into the town hall chest.
 *       {@link CraftingOrderManager#onItemDelivered} increments
 *       {@code deliveredCount}.</li>
 *   <li>When {@code deliveredCount >= quantity}, status becomes FULFILLED.
 *       The player receives coins and profession XP.</li>
 *   <li>Orders expire after {@code expiryTick} if not fulfilled.</li>
 * </ol>
 *
 * <h3>Reward scaling</h3>
 * Coin reward is pre-calculated when the order is created and reflects
 * the village's current economic state. XP is awarded based on which
 * {@link tterrag1112.life_in_the_village.Profession.PlayerProfession}
 * the target item is relevant to.
 */
public class CraftingOrder {

    // -------------------------------------------------------------------------
    // Status enum
    // -------------------------------------------------------------------------

    public enum OrderStatus {
        OPEN,       // visible on the board, unclaimed
        CLAIMED,    // a player has accepted it
        FULFILLED,  // items delivered, reward paid
        EXPIRED,    // deadline passed without fulfilment
        CANCELLED;  // withdrawn by the requesting NPC/system

        public static final Codec<OrderStatus> CODEC =
                Codec.STRING.xmap(OrderStatus::valueOf, OrderStatus::name);
    }

    // -------------------------------------------------------------------------
    // Codec
    // -------------------------------------------------------------------------

    public static final Codec<CraftingOrder> CODEC =
            RecordCodecBuilder.create(i -> i.group(
                    UUIDUtil.CODEC.fieldOf("orderId")
                            .forGetter(CraftingOrder::getOrderId),
                    UUIDUtil.CODEC.fieldOf("villageId")
                            .forGetter(CraftingOrder::getVillageId),
                    UUIDUtil.CODEC.optionalFieldOf("requestingNpcId",
                                    new UUID(0, 0))
                            .forGetter(CraftingOrder::getRequestingNpcId),
                    Codec.STRING.fieldOf("itemId")
                            .forGetter(CraftingOrder::getItemId),
                    Codec.INT.fieldOf("quantity")
                            .forGetter(CraftingOrder::getQuantity),
                    Codec.INT.fieldOf("deliveredCount")
                            .forGetter(CraftingOrder::getDeliveredCount),
                    Codec.LONG.fieldOf("bronzeReward")
                            .forGetter(CraftingOrder::getBronzeReward),
                    OrderStatus.CODEC.fieldOf("status")
                            .forGetter(CraftingOrder::getStatus),
                    Codec.LONG.fieldOf("postedTick")
                            .forGetter(CraftingOrder::getPostedTick),
                    Codec.LONG.fieldOf("expiryTick")
                            .forGetter(CraftingOrder::getExpiryTick),
                    UUIDUtil.CODEC.optionalFieldOf("claimedByPlayerId",
                                    new UUID(0, 0))
                            .forGetter(CraftingOrder::getClaimedByPlayerId)
            ).apply(i, CraftingOrder::new));

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final UUID        orderId;
    private final UUID        villageId;
    private final UUID        requestingNpcId;   // UUID(0,0) = village economy
    private final String      itemId;
    private final int         quantity;
    private       int         deliveredCount;
    private final long        bronzeReward;
    private       OrderStatus status;
    private final long        postedTick;
    private final long        expiryTick;
    private       UUID        claimedByPlayerId; // UUID(0,0) = unclaimed

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public CraftingOrder(UUID orderId, UUID villageId,
                         UUID requestingNpcId, String itemId,
                         int quantity, int deliveredCount,
                         long bronzeReward, OrderStatus status,
                         long postedTick, long expiryTick,
                         UUID claimedByPlayerId) {
        this.orderId            = orderId;
        this.villageId          = villageId;
        this.requestingNpcId    = requestingNpcId;
        this.itemId             = itemId;
        this.quantity           = quantity;
        this.deliveredCount     = deliveredCount;
        this.bronzeReward       = bronzeReward;
        this.status             = status;
        this.postedTick         = postedTick;
        this.expiryTick         = expiryTick;
        this.claimedByPlayerId  = claimedByPlayerId;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Creates a new OPEN order.
     *
     * @param villageId         which village this board belongs to
     * @param requestingNpcId   UUID of the NPC requesting; pass
     *                          {@code new UUID(0,0)} for an economy-driven order
     * @param itemId            namespaced item id, e.g. "minecraft:iron_sword"
     * @param quantity          how many are needed
     * @param bronzeReward      total coin reward paid on fulfilment
     * @param postedTick        {@code level.getGameTime()} at posting time
     * @param durationTicks     how long before the order expires
     */
    public static CraftingOrder create(UUID villageId,
                                       UUID requestingNpcId,
                                       String itemId,
                                       int quantity,
                                       long bronzeReward,
                                       long postedTick,
                                       long durationTicks) {
        return new CraftingOrder(
                UUID.randomUUID(),
                villageId,
                requestingNpcId,
                itemId,
                quantity,
                0,
                bronzeReward,
                OrderStatus.OPEN,
                postedTick,
                postedTick + durationTicks,
                new UUID(0, 0));
    }

    // -------------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------------

    public boolean claim(UUID playerId) {
        if (status != OrderStatus.OPEN) return false;
        claimedByPlayerId = playerId;
        status = OrderStatus.CLAIMED;
        return true;
    }

    /**
     * Adds delivered items. Returns true if the order is now fulfilled.
     */
    public boolean deliver(int count) {
        if (status != OrderStatus.CLAIMED && status != OrderStatus.OPEN) return false;
        deliveredCount = Math.min(deliveredCount + count, quantity);
        if (deliveredCount >= quantity) {
            status = OrderStatus.FULFILLED;
            return true;
        }
        return false;
    }

    public void expire() {
        if (status == OrderStatus.OPEN || status == OrderStatus.CLAIMED) {
            status = OrderStatus.EXPIRED;
        }
    }

    public void cancel() { status = OrderStatus.CANCELLED; }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    public boolean isOpen()         { return status == OrderStatus.OPEN; }
    public boolean isClaimed()      { return status == OrderStatus.CLAIMED; }
    public boolean isFulfilled()    { return status == OrderStatus.FULFILLED; }
    public boolean isActive()       { return status == OrderStatus.OPEN
            || status == OrderStatus.CLAIMED; }
    public boolean isExpired(long currentTick) {
        return currentTick > expiryTick && isActive();
    }

    /** True if this order is claimed by the given player. */
    public boolean isClaimedBy(UUID playerId) {
        return status == OrderStatus.CLAIMED
                && claimedByPlayerId.equals(playerId);
    }

    /** Progress fraction 0.0–1.0. */
    public float getProgress() {
        return quantity == 0 ? 1f : (float) deliveredCount / quantity;
    }

    public int getRemainingCount() { return Math.max(0, quantity - deliveredCount); }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID        getOrderId()           { return orderId; }
    public UUID        getVillageId()         { return villageId; }
    public UUID        getRequestingNpcId()   { return requestingNpcId; }
    public String      getItemId()            { return itemId; }
    public int         getQuantity()          { return quantity; }
    public int         getDeliveredCount()    { return deliveredCount; }
    public long        getBronzeReward()      { return bronzeReward; }
    public OrderStatus getStatus()            { return status; }
    public long        getPostedTick()        { return postedTick; }
    public long        getExpiryTick()        { return expiryTick; }
    public UUID        getClaimedByPlayerId() { return claimedByPlayerId; }

    @Override
    public String toString() {
        return "CraftingOrder[" + orderId.toString().substring(0, 8)
                + " item=" + itemId + " " + deliveredCount + "/" + quantity
                + " status=" + status + "]";
    }
}