package tterrag1112.life_in_the_village.Npc.Economy.Channels;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * One trade request fed to {@link ChannelRouter}. The actor expresses
 * what they want; the router and a chosen {@link EconomicChannel}
 * fulfill.
 *
 * <p>Spec section "Data model → TradeIntent" (line 23). Codec is omitted
 * for v1 — intents live for the duration of a goal tick and are never
 * persisted; transient lookup-only.</p>
 *
 * @param direction   BUY or SELL
 * @param item        target item
 * @param quantity    desired count (channels may quote less; partial-fill
 *                    semantics are caller-decided per spec line 268)
 * @param actorId     UUID of the NPC or player driving the trade
 * @param buildingId  optional originating building (workshop, household,
 *                    stockpile); used by {@link
 *                    tterrag1112.life_in_the_village.Npc.Economy.Channels.impl.DirectBusinessChannel
 *                    DirectBusinessChannel} for "buyer is the workshop"
 *                    routing
 * @param villageId   the village whose channels we're polling
 * @param maxPrice    BUY ceiling (per-unit, bronze). Channels above the
 *                    ceiling are dropped at quote time. Use
 *                    {@link Long#MAX_VALUE} for "no limit"
 * @param minPrice    SELL floor (per-unit, bronze). Symmetric to maxPrice
 * @param urgency     drives the score weighting in {@link ChannelRouter}
 * @param substitutes BUY-only acceptable alternatives (e.g. carrot OK
 *                    when potato isn't available); empty for "exact item
 *                    only"
 */
public record TradeIntent(
        TradeDirection direction,
        Item item,
        int quantity,
        UUID actorId,
        @Nullable UUID buildingId,
        UUID villageId,
        long maxPrice,
        long minPrice,
        Urgency urgency,
        Set<Item> substitutes
) {
    public TradeIntent {
        if (direction == null) throw new IllegalArgumentException("direction is required");
        if (item == null) throw new IllegalArgumentException("item is required");
        if (actorId == null) throw new IllegalArgumentException("actorId is required");
        if (villageId == null) throw new IllegalArgumentException("villageId is required");
        if (urgency == null) urgency = Urgency.NORMAL;
        substitutes = substitutes == null ? Set.of() : Collections.unmodifiableSet(Set.copyOf(substitutes));
        quantity = Math.max(0, quantity);
    }

    /** Convenience BUY constructor — minPrice fixed at 0. */
    public static TradeIntent buy(Item item, int quantity, UUID actorId,
                                  @Nullable UUID buildingId, UUID villageId,
                                  long maxPrice, Urgency urgency, Set<Item> substitutes) {
        return new TradeIntent(TradeDirection.BUY, item, quantity, actorId, buildingId,
                villageId, maxPrice, 0L, urgency, substitutes);
    }

    /** Convenience SELL constructor — maxPrice fixed at MAX_VALUE. */
    public static TradeIntent sell(Item item, int quantity, UUID actorId,
                                   @Nullable UUID buildingId, UUID villageId,
                                   long minPrice, Urgency urgency) {
        return new TradeIntent(TradeDirection.SELL, item, quantity, actorId, buildingId,
                villageId, Long.MAX_VALUE, minPrice, urgency, Set.of());
    }

    /** Display string for debug commands. Item id only — no namespace. */
    public String shortDescription() {
        var key = BuiltInRegistries.ITEM.getKey(item);
        String itemName = key == null ? item.toString() : key.getPath();
        return direction.name() + " " + quantity + "× " + itemName
                + " (urgency=" + urgency.name() + ")";
    }
}
