package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelQuote;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelRouter;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeResult;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.Urgency;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.BuildingEconomy;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CommerceModifier;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceHelper;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * E-S1 — buy-side procurement helper lifted from
 * {@link AbstractProductionBehavior#executeBuy} so that future
 * context-based production behaviors can source workshop inputs through
 * the same ChannelRouter pipeline without inheriting from
 * {@code AbstractProductionBehavior}.
 *
 * <h3>Behavior preserved (zero gameplay delta)</h3>
 * <ul>
 *   <li>ChannelRouter: builds a {@link TradeIntent#buy} intent,
 *       calls {@link ChannelRouter#findBestChannel}, routes via
 *       partial-fill affordability (treasury + wallet, 0.9 safety
 *       margin).</li>
 *   <li>Two-source funding: building treasury drained first; proprietor
 *       wallet covers the shortfall.</li>
 *   <li>Full refund on execute-fail; partial refund on partial-fill.</li>
 *   <li>Bought items stored via {@link BuildingStorageAccess#storeItem}
 *       into {@code workBuilding}.</li>
 *   <li>Per-item-loop diagnostic flags (one-shot per behavior instance)
 *       live on the caller-supplied {@link DiagFlags} object so they
 *       behave identically to the private booleans they replace.</li>
 * </ul>
 *
 * <h3>PB4b — business-treasury-funded buy</h3>
 * {@link #buyForBusiness} is a parallel entry point that funds the
 * purchase exclusively from the business treasury (no NPC wallet
 * involvement beyond the transient top-up the channel machinery requires).
 * Money conservation: debit-before-execute, refund-on-fail/partial,
 * zero wallet remainder, zero duplication.
 *
 * <h3>Package placement</h3>
 * Same package as {@code AbstractProductionBehavior} — no visibility
 * change required on any existing type.
 */
public final class WorkshopProcurement {

    private static final Logger LOGGER = LogUtils.getLogger();

    private WorkshopProcurement() {}

    // =========================================================================
    // Diagnostic flags (one-shot, per behavior instance)
    // =========================================================================

    /**
     * Caller-owned mutable flag bag.  Create one instance per behavior
     * instance (e.g. as a field alongside the behavior) and pass it to
     * every {@link #buy} call.  Flags are set to {@code true} on first
     * fire and never reset — identical to the private booleans they replace
     * on {@code AbstractProductionBehavior}.
     */
    public static final class DiagFlags {
        public boolean loggedBuyEntry       = false;
        public boolean loggedBuyAccepted    = false;
        public boolean loggedBuyNoQuote     = false;
        public boolean loggedBuyAffordFail  = false;
        public boolean loggedBuyExecuteFail = false;
    }

    // =========================================================================
    // Public entry points
    // =========================================================================

    /**
     * Execute buy-side procurement for {@code toBuy} on behalf of
     * {@code entity} operating in {@code workBuilding}.
     *
     * <p>The caller is responsible for supplying a {@link DiagFlags}
     * instance that persists across calls (i.e. a field on the behavior
     * object, not a local).  Diagnostic log wording is identical to the
     * pre-extraction version in {@code AbstractProductionBehavior}.</p>
     *
     * <p>Funding: building economy treasury first, NPC wallet for the
     * shortfall (two-source, unchanged from pre-extraction).</p>
     *
     * @param callerClass  simple name used in log prefixes (pass
     *                     {@code getClass().getSimpleName()} from the
     *                     calling behavior)
     */
    public static void buy(
            TownspersonMob entity,
            Building workBuilding,
            Map<Item, Integer> toBuy,
            ServerLevel level,
            DiagFlags flags,
            String callerClass) {

        UUID buildingId = entity.getAssignedBuildingId().orElse(null);
        if (buildingId == null) return;
        VillageSavedData data = VillageSavedData.get(level);
        BuildingEconomy bEconomy = data.getOrCreateBuildingEconomy(buildingId);
        Village village = entity.getAssignedVillageName()
                .flatMap(data::getVillageByName).orElse(null);
        if (village == null) return;

        if (!flags.loggedBuyEntry) {
            LOGGER.debug("[{}] {} executeBuy: needs={} treasury={}br wallet={}br village={}",
                    callerClass, entity.getNpcName(),
                    toBuy, bEconomy.getTreasury(),
                    entity.getWallet().toBronze(), village.getName());
            flags.loggedBuyEntry = true;
        }

        for (Map.Entry<Item, Integer> entry : toBuy.entrySet()) {
            Item item = entry.getKey();
            int wanted = entry.getValue();
            if (wanted <= 0) continue;

            long perUnitCeiling = Math.max(1L,
                    Math.round(getDynamicBuyPrice(level, village, item)
                            * CommerceModifier.buyMultiplier(entity)));
            TradeIntent intent = TradeIntent.buy(
                    item, wanted, entity.getUUID(), buildingId,
                    village.getId(), perUnitCeiling,
                    Urgency.NORMAL,
                    Set.of());

            ChannelQuote quote = ChannelRouter
                    .findBestChannel(intent, village, data, level).orElse(null);
            if (quote == null) {
                if (!flags.loggedBuyNoQuote) {
                    LOGGER.debug("[{}] {} executeBuy: NO QUOTE for {}x{} " +
                            "(perUnitCeiling={}br). All channels declined; " +
                            "see per-channel diagnostic flags for reasons.",
                            callerClass, entity.getNpcName(),
                            wanted, item, perUnitCeiling);
                    flags.loggedBuyNoQuote = true;
                }
                continue;
            }

            // Phase 6.3.4.7 — partial-fill: cap qty to combined treasury
            // + wallet budget (0.9 safety margin for tax/rounding).
            long pricePerUnit = quote.pricePerUnit();
            long combinedBudget = bEconomy.getTreasury() + entity.getWallet().toBronze();
            long maxAffordableUnits = (long) ((combinedBudget * 0.9) / pricePerUnit);
            int affordableQty = (int) Math.min(quote.availableQuantity(),
                    Math.max(0L, maxAffordableUnits));
            if (affordableQty <= 0) {
                if (!flags.loggedBuyAffordFail) {
                    LOGGER.debug("[{}] {} executeBuy: AFFORD FAIL for {}x{} " +
                            "from channel={} (price={}br/unit, treasury={}br, " +
                            "wallet={}br — combined budget insufficient for " +
                            "even 1 unit)",
                            callerClass, entity.getNpcName(),
                            wanted, item, quote.channel(), pricePerUnit,
                            bEconomy.getTreasury(), entity.getWallet().toBronze());
                    flags.loggedBuyAffordFail = true;
                }
                continue;
            }

            // Build a partial quote clamped to affordableQty.
            ChannelQuote partialQuote = new ChannelQuote(
                    quote.channel(), quote.intent(), pricePerUnit,
                    affordableQty, quote.travelTimeTicks(),
                    quote.quoteValidUntilTick(), quote.location());
            long total = pricePerUnit * affordableQty;

            // Two-source funding: drain treasury first, top up wallet so
            // channel.execute (which spends from wallet) sees the full amount.
            long fromTreasury = Math.min(total, bEconomy.getTreasury());
            if (fromTreasury > 0) {
                bEconomy.withdraw(fromTreasury);
                entity.getWallet().receive(CurrencyValue.of(fromTreasury));
            }

            var channel = ChannelRouter.registeredChannels().stream()
                    .filter(c -> c.type() == partialQuote.channel())
                    .findFirst().orElse(null);
            TradeResult result = channel == null
                    ? TradeResult.fail("channel missing")
                    : channel.execute(partialQuote, intent, level);

            if (!result.success()) {
                if (!flags.loggedBuyExecuteFail) {
                    LOGGER.debug("[{}] {} executeBuy: EXECUTE FAIL for {}x{} " +
                            "from channel={} reason='{}'",
                            callerClass, entity.getNpcName(),
                            affordableQty, item, partialQuote.channel(),
                            result.failureReason());
                    flags.loggedBuyExecuteFail = true;
                }
                // Refund treasury portion; wallet's own contribution stays.
                if (fromTreasury > 0) {
                    entity.getWallet().spend(CurrencyValue.of(fromTreasury));
                    bEconomy.depositRevenue(fromTreasury);
                }
                continue;
            }

            long actualSpent = result.totalBronze();
            long leftover = total - actualSpent;
            if (leftover > 0) {
                // Channel spent less than expected (partial fill). Refund
                // up to the treasury portion first, the rest stays in wallet.
                long refundToTreasury = Math.min(leftover, fromTreasury);
                if (refundToTreasury > 0) {
                    entity.getWallet().spend(CurrencyValue.of(refundToTreasury));
                    bEconomy.depositRevenue(refundToTreasury);
                }
            }

            BuildingStorageAccess.storeItem(level, workBuilding,
                    new ItemStack(item, result.quantityTraded()));
            data.setDirty();

            if (!flags.loggedBuyAccepted) {
                LOGGER.debug("[{}] {} executeBuy: ACCEPTED {}x{} from channel={} " +
                        "at {}br/unit (total={}br, fromTreasury={}br, fromWallet={}br)",
                        callerClass, entity.getNpcName(),
                        result.quantityTraded(), item, partialQuote.channel(),
                        pricePerUnit, actualSpent, fromTreasury,
                        Math.max(0L, actualSpent - fromTreasury));
                flags.loggedBuyAccepted = true;
            }
            // T6 — award COMMERCE XP scaled to actual bronze spent.
            CommerceModifier.awardForTrade(entity, actualSpent, level.getGameTime());
        }
    }

    /**
     * PB4b — business-treasury-funded buy.
     *
     * <p>Identical channel/quote/partial-fill/refund/deposit machinery as
     * {@link #buy}, but the <em>sole</em> funding source is the
     * {@link Business#getTreasuryBronze() business treasury}.  The NPC's
     * personal wallet is used only as the transient conduit the channel
     * machinery requires (top-up → execute → drain back); it is always left
     * net-zero for the transaction.</p>
     *
     * <h3>Money conservation invariants</h3>
     * <ul>
     *   <li><b>Insufficient funds:</b> {@code business.withdrawBronze(total)}
     *       returns {@code false} → no debit, no buy, no wallet touched.</li>
     *   <li><b>No quote (no seller):</b> affordability pre-check may pass but
     *       {@code findBestChannel} returns empty → continue; no debit.</li>
     *   <li><b>Execute fail:</b> debit happened before execute; full refund
     *       via {@code business.depositBronze(total)} + wallet drained back;
     *       net zero on both.</li>
     *   <li><b>Partial fill:</b> debit {@code total = price × affordableQty};
     *       {@code actualSpent = result.totalBronze()}; leftover refunded via
     *       {@code business.depositBronze(leftover)} + wallet drained back.</li>
     *   <li><b>Success:</b> debit {@code total}, wallet pre-funded by same,
     *       channel spends {@code actualSpent} from wallet, leftover = 0.</li>
     * </ul>
     *
     * <p>COMMERCE XP is still awarded to the NPC (discount is their skill;
     * they did the buying on behalf of the business).</p>
     *
     * @param entity        the employee NPC performing the purchase
     * @param workBuilding  the business's production building (items deposited here)
     * @param toBuy         items and quantities to acquire
     * @param business      the business whose treasury bears the cost
     * @param level         server level
     * @param flags         caller-owned one-shot diagnostic flags
     * @param callerClass   simple name for log prefixes
     */
    public static void buyForBusiness(
            TownspersonMob entity,
            Building workBuilding,
            Map<Item, Integer> toBuy,
            Business business,
            ServerLevel level,
            DiagFlags flags,
            String callerClass) {

        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageById(business.getHomeVillageId()).orElse(null);
        if (village == null) return;

        UUID buildingId = workBuilding.getId();

        if (!flags.loggedBuyEntry) {
            LOGGER.debug("[{}] {} buyForBusiness: needs={} bizTreasury={}br village={}",
                    callerClass, entity.getNpcName(),
                    toBuy, business.getTreasuryBronze(), village.getName());
            flags.loggedBuyEntry = true;
        }

        for (Map.Entry<Item, Integer> entry : toBuy.entrySet()) {
            Item item = entry.getKey();
            int wanted = entry.getValue();
            if (wanted <= 0) continue;

            // Per-unit ceiling: plain dynamic price (no per-npc multiplier at
            // generation time, but buy-side COMMERCE discount still applies at
            // execute time via the channel's settlement path).
            long perUnitCeiling = Math.max(1L,
                    Math.round(getDynamicBuyPrice(level, village, item)
                            * CommerceModifier.buyMultiplier(entity)));
            TradeIntent intent = TradeIntent.buy(
                    item, wanted, entity.getUUID(), buildingId,
                    village.getId(), perUnitCeiling,
                    Urgency.NORMAL,
                    Set.of());

            ChannelQuote quote = ChannelRouter
                    .findBestChannel(intent, village, data, level).orElse(null);
            if (quote == null) {
                if (!flags.loggedBuyNoQuote) {
                    LOGGER.debug("[{}] {} buyForBusiness: NO QUOTE for {}x{} " +
                            "(perUnitCeiling={}br). All channels declined.",
                            callerClass, entity.getNpcName(),
                            wanted, item, perUnitCeiling);
                    flags.loggedBuyNoQuote = true;
                }
                continue;
            }

            // Partial-fill: cap qty to business treasury (0.9 safety margin for
            // market tax that settlePurchase applies inside channel.execute).
            long pricePerUnit = quote.pricePerUnit();
            long bizBudget = business.getTreasuryBronze();
            long maxAffordableUnits = (long) ((bizBudget * 0.9) / pricePerUnit);
            int affordableQty = (int) Math.min(quote.availableQuantity(),
                    Math.max(0L, maxAffordableUnits));
            if (affordableQty <= 0) {
                if (!flags.loggedBuyAffordFail) {
                    LOGGER.debug("[{}] {} buyForBusiness: AFFORD FAIL for {}x{} " +
                            "from channel={} (price={}br/unit, bizTreasury={}br — " +
                            "insufficient for even 1 unit)",
                            callerClass, entity.getNpcName(),
                            wanted, item, quote.channel(), pricePerUnit,
                            bizBudget);
                    flags.loggedBuyAffordFail = true;
                }
                continue;
            }

            // Build a partial quote clamped to affordableQty.
            ChannelQuote partialQuote = new ChannelQuote(
                    quote.channel(), quote.intent(), pricePerUnit,
                    affordableQty, quote.travelTimeTicks(),
                    quote.quoteValidUntilTick(), quote.location());
            long total = pricePerUnit * affordableQty;

            // Single-source funding: debit business treasury, top up NPC wallet
            // so channel.execute (which spends from wallet) sees the full amount.
            // The wallet is always left net-zero after the transaction.
            if (!business.withdrawBronze(total)) {
                // Atomic check-and-debit failed (treasury shrank since the
                // affordability check above — race with wage payment etc.).
                LOGGER.debug("[{}] {} buyForBusiness: WITHDRAW FAIL for {}x{} " +
                        "total={}br bizTreasury={}br (treasury shrank since afford check)",
                        callerClass, entity.getNpcName(),
                        affordableQty, item, total, business.getTreasuryBronze());
                continue;
            }
            // Top up wallet so the channel can spend from it.
            entity.getWallet().receive(CurrencyValue.of(total));

            var channel = ChannelRouter.registeredChannels().stream()
                    .filter(c -> c.type() == partialQuote.channel())
                    .findFirst().orElse(null);
            TradeResult result = channel == null
                    ? TradeResult.fail("channel missing")
                    : channel.execute(partialQuote, intent, level);

            if (!result.success()) {
                if (!flags.loggedBuyExecuteFail) {
                    LOGGER.debug("[{}] {} buyForBusiness: EXECUTE FAIL for {}x{} " +
                            "from channel={} reason='{}'",
                            callerClass, entity.getNpcName(),
                            affordableQty, item, partialQuote.channel(),
                            result.failureReason());
                    flags.loggedBuyExecuteFail = true;
                }
                // Full refund to business treasury; drain the wallet top-up back.
                entity.getWallet().spend(CurrencyValue.of(total));
                business.depositBronze(total);
                continue;
            }

            long actualSpent = result.totalBronze();
            long leftover = total - actualSpent;
            if (leftover > 0) {
                // Channel spent less than pre-funded (partial fill or market tax
                // was less than headroom). Drain leftover from wallet, refund to
                // business treasury. Wallet net = 0.
                entity.getWallet().spend(CurrencyValue.of(leftover));
                business.depositBronze(leftover);
            }

            BuildingStorageAccess.storeItem(level, workBuilding,
                    new ItemStack(item, result.quantityTraded()));
            data.setDirty();

            if (!flags.loggedBuyAccepted) {
                LOGGER.debug("[{}] {} buyForBusiness: ACCEPTED {}x{} from channel={} " +
                        "at {}br/unit (total={}br, actualSpent={}br, refund={}br)",
                        callerClass, entity.getNpcName(),
                        result.quantityTraded(), item, partialQuote.channel(),
                        pricePerUnit, total, actualSpent, leftover);
                flags.loggedBuyAccepted = true;
            }
            // Award COMMERCE XP — the employee did the buying.
            CommerceModifier.awardForTrade(entity, actualSpent, level.getGameTime());
        }
    }

    // =========================================================================
    // Internal price helper
    // =========================================================================

    /**
     * Dynamic sell price used as the per-unit buy ceiling.  Mirrors
     * {@code AbstractProductionBehavior#getItemBuyPrice} exactly (Phase
     * 6.3.4.4.4 note: ceiling tracks actual market conditions rather than
     * the raw JSON base, so channel quotes are not systematically rejected).
     *
     * <p>Public so that {@link tterrag1112.life_in_the_village.Npc.Tasks.Business.BusinessProductionTaskSource}
     * can use the same price estimate for affordability gating at task-generation
     * time (PB4b).</p>
     */
    public static long getDynamicBuyPrice(ServerLevel level, Village village, Item item) {
        return Math.max(1L, MarketPriceHelper.getDynamicSellPrice(level, village, item));
    }
}
