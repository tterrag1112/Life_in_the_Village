package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.UUID;

/**
 * Everything {@link MarketPricing} needs to resolve a final price for one
 * item, parameterized so the player trade path and the NPC channel path
 * call the <em>same</em> pipeline (economy Phase 1a).
 *
 * <p>Transient and lookup-only — like {@code TradeIntent}, a context lives
 * for the duration of a single quote and is never persisted, so it carries
 * no codec.</p>
 *
 * @param item       the item being priced
 * @param level      server level (supply/demand + tariff lookups)
 * @param village    owning village; {@code null} falls back to base price
 * @param data       saved data (kingdom tariff lookup); may be {@code null}
 *                   only when {@code village} is {@code null}
 * @param buyerType  PLAYER or NPC — gates relationship modifiers
 * @param player     the buying/selling player; {@code null} for NPC buyers.
 *                   Drives reputation discount, profession perk and tariff
 * @param stall      the market stall sourcing the item, if any; lets the
 *                   pipeline honour the stall's custom price. {@code null}
 *                   when no stall holds the item (or on the sell-to-market
 *                   side, where stall custom prices do not apply)
 */
public record PricingContext(
        Item item,
        ServerLevel level,
        @Nullable Village village,
        @Nullable VillageSavedData data,
        BuyerType buyerType,
        @Nullable ServerPlayer player,
        @Nullable MarketStall stall
) {
    /** Owning village id, or {@code null} when there is no village. */
    @Nullable
    public UUID villageId() {
        return village == null ? null : village.getId();
    }

    /** Player-path context (reputation / perk / tariff eligible). */
    public static PricingContext forPlayer(Item item, ServerLevel level,
                                           @Nullable Village village,
                                           @Nullable VillageSavedData data,
                                           ServerPlayer player,
                                           @Nullable MarketStall stall) {
        return new PricingContext(item, level, village, data,
                BuyerType.PLAYER, player, stall);
    }

    /** NPC-path context — no relationship modifiers. */
    public static PricingContext forNpc(Item item, ServerLevel level,
                                        @Nullable Village village,
                                        @Nullable VillageSavedData data,
                                        @Nullable MarketStall stall) {
        return new PricingContext(item, level, village, data,
                BuyerType.NPC, null, stall);
    }
}
