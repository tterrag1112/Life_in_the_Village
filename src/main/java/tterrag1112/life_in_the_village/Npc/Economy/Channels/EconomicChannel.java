package tterrag1112.life_in_the_village.Npc.Economy.Channels;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

/**
 * One way to trade an item: market stall, direct producer, caravan,
 * stockpile, etc. Spec line 44.
 *
 * <p>Channels are stateless singletons registered in
 * {@link ChannelRouter#registeredChannels}. Each implementation:</p>
 * <ol>
 *   <li>Declares its {@link #type} and {@link #basePriority}.</li>
 *   <li>Reports {@link #isAvailable} cheaply (no per-NPC scan; this is
 *   queried by every router call).</li>
 *   <li>Returns an optional {@link ChannelQuote} for an intent.</li>
 *   <li>Executes the chosen quote — moves items, moves coins, fires
 *   downstream events (Trade, etc.).</li>
 * </ol>
 *
 * <p>Failure modes: returning {@link Optional#empty()} from
 * {@link #quote} means "I can't fill this intent right now"; throwing
 * is reserved for genuine programmer errors. Execute may return a
 * {@link TradeResult} with {@code success=false} and a reason string —
 * that's the caller's signal to retry or fall back.</p>
 */
public interface EconomicChannel {

    /** Discriminator. */
    ChannelType type();

    /**
     * Tiebreak weight in the {@link ChannelRouter} score formula. Spec
     * priorities: MARKET 100, DIRECT_BUSINESS 70, CARAVAN 50, VISITOR
     * 45, GUILD_REQUEST 40, STOCKPILE 30.
     */
    int basePriority();

    /**
     * Cheap "is this channel even an option in this village right now?"
     * gate. Examples: MARKET checks for a MARKET building; CARAVAN
     * checks the caravan presence window via {@code level}. Heavy work
     * belongs in {@link #quote}.
     *
     * <p>Spec deviation: the spec signature omits {@code level} (line
     * 47), but CARAVAN / VISITOR availability is keyed by saved data
     * that lives on the level (caravan list, visitor list). Adding the
     * parameter here keeps {@link #isAvailable} useful as a router
     * pre-filter without forcing every call site to also call
     * {@link #quote}. Documented in 23 Revision Notes.</p>
     */
    boolean isAvailable(Village village, VillageSavedData data, ServerLevel level, long tick);

    /**
     * Returns the channel's offer for {@code intent}, or empty when the
     * channel cannot fill the intent (out of stock, no producer, item
     * mismatch, etc.). Channels are responsible for clamping
     * {@code availableQuantity} to what they can actually deliver.
     */
    Optional<ChannelQuote> quote(TradeIntent intent, Village village,
                                 VillageSavedData data, ServerLevel level);

    /**
     * Performs the trade described by {@code quote} for the original
     * {@code intent}. Implementations:
     * <ul>
     *   <li>Move items OUT of the source container (market chest,
     *   workshop chest, stockpile, caravan inventory).</li>
     *   <li>Move bronze between buyer / seller.</li>
     *   <li>Fire downstream events (e.g. {@code NpcLifeEvent.Trade}).</li>
     * </ul>
     *
     * <p>What the channel does NOT do: store the purchased items in the
     * buyer's destination container. The caller decides where the item
     * lands (home chest, workshop input chest, NPC personal inventory)
     * based on context, and reads {@link TradeResult#quantityTraded} to
     * know how many were moved. This keeps every channel uniform — the
     * channel handles the source-side and bronze flow; the caller
     * handles the destination-side.</p>
     */
    TradeResult execute(ChannelQuote quote, TradeIntent intent, ServerLevel level);
}
