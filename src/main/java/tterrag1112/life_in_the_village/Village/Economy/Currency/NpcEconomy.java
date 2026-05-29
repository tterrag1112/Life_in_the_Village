package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Laws.LawTaxHooks;
import tterrag1112.life_in_the_village.Village.Economy.BuildingEconomy;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.UUID;

/**
 * Single entry point for all NPC money movement.
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li>Personal purchases → {@link #npcPay}: wallet → wallet</li>
 *   <li>Business purchases → {@link #businessPay}: building treasury → wallet</li>
 *   <li>Wages → {@link #payWage}: village treasury → wallet</li>
 *   <li>Revenue → {@link #recordRevenue}: wallet → building treasury</li>
 *   <li>Market trades → {@link #settlePurchase} / {@link #settleSale}: the
 *       one money-movement path both the player and NPC trade paths call
 *       (Phase 1b). Applies the village market tax once, on purchases.</li>
 * </ul>
 * All methods that involve a visible NPC fire {@link NpcTransactionVisual}.
 * Purely ledger transfers (treasury → treasury) are silent.
 */
public final class NpcEconomy {

    private NpcEconomy() {}

    // =========================================================================
    // NPC personal → NPC personal
    // =========================================================================

    /**
     * Payer NPC pays receiver NPC from personal wallets.
     * Both must be loaded entities. Visual plays at the payer's location.
     *
     * @return true if the full amount was transferred
     */
    public static boolean npcPay(TownspersonMob payer,
                                 TownspersonMob receiver,
                                 CurrencyValue amount,
                                 ServerLevel level) {
        if (!payer.getWallet().canAfford(amount)) return false;
        long transferred = payer.getWallet().payTo(
                receiver.getWallet(), amount.toBronze());
        if (transferred == 0) return false;
        NpcTransactionVisual.showPayment(payer, receiver, level);
        return transferred == amount.toBronze();
    }

    // =========================================================================
    // Building treasury → NPC personal (wages, contract payments)
    // =========================================================================

    /**
     * Pays a wage from the village treasury to an NPC's personal wallet.
     * No physical payer — visual plays on the receiver only.
     */
    public static boolean payWage(TownspersonMob receiver,
                                  long bronzeAmount,
                                  ServerLevel level,
                                  VillageSavedData data) {
        String villageName = receiver.getAssignedVillageName().orElse(null);
        if (villageName == null) return false;

        var village = data.getVillageByName(villageName).orElse(null);
        if (village == null) return false;

        if (!village.withdrawFromTreasury(bronzeAmount)) return false;
        receiver.getWallet().receive(bronzeAmount);
        NpcTransactionVisual.showReceive(receiver, level);
        return true;
    }

    /**
     * Business purchase: deducts from the building's treasury and pays
     * the seller NPC's wallet. Use for production input purchases
     * (farmer buys seeds, blacksmith buys ore).
     *
     * @return true if the full amount was paid
     */
    public static boolean businessPay(UUID buyerBuildingId,
                                      TownspersonMob seller,
                                      CurrencyValue amount,
                                      ServerLevel level,
                                      VillageSavedData data) {
        BuildingEconomy economy = data.getBuildingEconomy(buyerBuildingId)
                .orElse(null);
        if (economy == null) return false;
        if (!economy.canAfford(amount.toBronze())) return false;

        economy.withdraw(amount.toBronze());
        seller.getWallet().receive(amount);
        NpcTransactionVisual.showReceive(seller, level);
        data.setDirty();
        return true;
    }

    /**
     * Records revenue into a building's business treasury when an NPC
     * sells produced goods. Also plays a brief visual on the selling NPC.
     */
    public static void recordRevenue(TownspersonMob seller,
                                     UUID buildingId,
                                     CurrencyValue amount,
                                     ServerLevel level,
                                     VillageSavedData data) {
        data.getOrCreateBuildingEconomy(buildingId)
                .depositRevenue(amount.toBronze());
        NpcTransactionVisual.showReceive(seller, level);
        data.setDirty();
    }

    // =========================================================================
    // Unified market settlement (economy Phase 1b) — money only
    // =========================================================================
    //
    // Both the player trade path (TradeHandler) and the NPC channel path
    // (MarketChannel) settle coins here. Goods movement (chest take/store,
    // player-inventory delivery) stays with each caller until 2c. These
    // functions move MONEY and apply the village market tax — nothing else.

    /**
     * Settles a market purchase: the {@code buyer} pays {@code amount} to
     * {@code endpoint}, and the village collects its market-tax slice once.
     * Used by both player buys and NPC buys.
     *
     * <p>The tax is the same 10%-of-total slice the NPC path always took
     * (scaled by {@link LawTaxHooks#marketTaxMultiplier}); pulling it here
     * means player buys now pay it too — the one intended behaviour change
     * of Phase 1b.</p>
     *
     * @return {@code false} (and nothing moved) if the buyer cannot pay.
     */
    public static boolean settlePurchase(SettlementParty buyer,
                                         SettlementParty endpoint,
                                         CurrencyValue amount,
                                         Village village,
                                         ServerLevel level,
                                         VillageSavedData data) {
        if (!debit(buyer, amount)) return false;
        credit(endpoint, amount, level);
        applyMarketTax(village, amount.toBronze(), data);
        firePaymentVisual(buyer, endpoint, level);
        return true;
    }

    /**
     * Settles a market sale: the {@code payer} (merchant for a player sell;
     * none for an NPC selling into the market) pays {@code amount} to the
     * {@code receiver} (the seller). No market tax — neither path taxed
     * sales before 1b, so none is added.
     *
     * <p>The payer debit is best-effort, matching the pre-1b player-sell
     * path which ignored the merchant {@code spend} result; callers
     * pre-check merchant funds (quantity is capped to merchant wealth).</p>
     */
    public static boolean settleSale(SettlementParty payer,
                                     SettlementParty receiver,
                                     CurrencyValue amount,
                                     ServerLevel level) {
        debit(payer, amount);
        credit(receiver, amount, level);
        return true;
    }

    /**
     * Resolves the credit endpoint for a market purchase: the stall owner's
     * wallet (NPC) or the stall chest (player-owned) when a stall sources
     * the item, otherwise the merchant fallback. Returns {@link
     * SettlementParty#none()} when neither exists (coins spent into the
     * void — preserves the legacy merchant-less case).
     */
    public static SettlementParty resolveStallEndpoint(ServerLevel level,
                                                       MarketStall stall,
                                                       TownspersonMob merchantFallback) {
        if (stall != null && stall.isActive()) {
            if (stall.getOwnerType() == MarketStall.OwnerType.NPC) {
                TownspersonMob owner = findNpc(level, stall.getOwnerUUID());
                if (owner != null) return SettlementParty.npc(owner);
                // owner not loaded — fall through to merchant fallback
            } else if (stall.getOwnerType() == MarketStall.OwnerType.PLAYER) {
                return SettlementParty.stallChest(stall);
            }
            // WANDERING_TRADER-owned stalls fall through to the merchant.
        }
        return merchantFallback != null
                ? SettlementParty.npc(merchantFallback)
                : SettlementParty.none();
    }

    // ── Settlement primitives ────────────────────────────────────────────

    /** Debits a party. Returns false (nothing moved) if it cannot pay. */
    private static boolean debit(SettlementParty party, CurrencyValue amount) {
        return switch (party) {
            case SettlementParty.Player p -> CoinHelper.playerPay(p.player(), amount);
            case SettlementParty.Npc n -> {
                if (!n.npc().getWallet().canAfford(amount)) yield false;
                yield n.npc().getWallet().spend(amount);
            }
            // A stall chest never pays; treat as unsupported payer.
            case SettlementParty.StallChest ignored -> false;
            // No source — mint (always "succeeds", moves nothing).
            case SettlementParty.None ignored -> true;
        };
    }

    /** Credits a party. */
    private static void credit(SettlementParty party, CurrencyValue amount,
                               ServerLevel level) {
        switch (party) {
            case SettlementParty.Player p -> CoinHelper.playerReceive(p.player(), amount);
            case SettlementParty.Npc n -> n.npc().getWallet().receive(amount);
            case SettlementParty.StallChest sc -> depositToStallChest(level, sc.stall(), amount);
            case SettlementParty.None ignored -> { /* burn — no sink */ }
        }
    }

    /**
     * Deposits physical coins into a player-owned stall's chest. Folded in
     * from the old {@code TradeHandler.forwardPaymentToStallOwner}
     * player-stall branch; also makes the previously no-op player-stall
     * case real for NPC buyers.
     */
    private static void depositToStallChest(ServerLevel level, MarketStall stall,
                                            CurrencyValue amount) {
        BlockEntity be = level.getBlockEntity(stall.getChestPos());
        if (!(be instanceof Container chest)) return;
        SimpleContainer temp = new SimpleContainer(9);
        CoinHelper.giveCoins(temp, amount);
        for (int i = 0; i < temp.getContainerSize(); i++) {
            ItemStack coin = temp.getItem(i);
            if (coin.isEmpty()) continue;
            for (int ci = 0; ci < chest.getContainerSize(); ci++) {
                if (chest.getItem(ci).isEmpty()) {
                    chest.setItem(ci, coin.copy());
                    break;
                }
            }
        }
    }

    /** NPC→NPC plays the full visual; NPC→other plays the payer-only one. */
    private static void firePaymentVisual(SettlementParty buyer,
                                          SettlementParty endpoint,
                                          ServerLevel level) {
        if (buyer instanceof SettlementParty.Npc b) {
            if (endpoint instanceof SettlementParty.Npc e) {
                NpcTransactionVisual.showPayment(b.npc(), e.npc(), level);
            } else {
                NpcTransactionVisual.showPayment(b.npc(), level);
            }
        }
    }

    /**
     * Applies the village market tax once. Identical computation to the
     * slice MarketChannel used to apply inline: 10% of the transaction
     * total scaled by {@link LawTaxHooks#marketTaxMultiplier}, deposited to
     * the village treasury.
     */
    private static void applyMarketTax(Village village, long total,
                                       VillageSavedData data) {
        if (village == null || total <= 0) return;
        double mult = LawTaxHooks.marketTaxMultiplier(village);
        long tax = Math.round((total
                / (double) tterrag1112.life_in_the_village.Village.Economy.VillageTreasury
                        .marketTaxDivisor()) * mult);
        if (tax > 0) {
            village.depositToTreasury(tax);
            data.markDirty();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static TownspersonMob findNpc(ServerLevel level, java.util.UUID uuid) {
        return level.getEntitiesOfClass(
                        TownspersonMob.class,
                        new net.minecraft.world.phys.AABB(
                                net.minecraft.core.BlockPos.ZERO).inflate(30000000),
                        mob -> mob.getUUID().equals(uuid))
                .stream().findFirst().orElse(null);
    }
}