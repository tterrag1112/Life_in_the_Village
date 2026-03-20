package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.world.item.Item;
import java.util.UUID;

public class TradeListing {

    public enum SellerType {
        PRODUCER,   // blacksmith, miner, farmer etc — generally cheaper
        MERCHANT,   // merchant NPC — markup applied
        STOCKPILE ,  // stockpile keeper — village price
        COMPANY
    }

    private final UUID sellerEntityId;
    private final UUID sellerBuildingId;
    private final Item item;
    private final int quantity;
    private final long pricePerItemBronze;
    private final SellerType sellerType;
    private long createdTick;

    public TradeListing(UUID sellerEntityId, UUID sellerBuildingId,
                        Item item, int quantity, long pricePerItemBronze,
                        SellerType sellerType, long createdTick) {
        this.sellerEntityId    = sellerEntityId;
        this.sellerBuildingId  = sellerBuildingId;
        this.item              = item;
        this.quantity          = quantity;
        this.pricePerItemBronze = pricePerItemBronze;
        this.sellerType        = sellerType;
        this.createdTick       = createdTick;
    }

    public UUID getSellerEntityId()     { return sellerEntityId; }
    public UUID getSellerBuildingId()   { return sellerBuildingId; }
    public Item getItem()               { return item; }
    public int getQuantity()            { return quantity; }
    public long getPricePerItem()       { return pricePerItemBronze; }
    public SellerType getSellerType()   { return sellerType; }
    public long getCreatedTick()        { return createdTick; }
}