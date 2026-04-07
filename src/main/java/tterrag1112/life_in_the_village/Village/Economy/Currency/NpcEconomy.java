package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.BuildingEconomy;

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
    // Market purchases (NPC buyer → stall owner or merchant)
    // =========================================================================

    /**
     * NPC buys from a market stall or main chest. Routes payment to the
     * stall owner if applicable, otherwise to the merchant NPC.
     * This is the standard method for all NPC market purchases.
     */
    public static boolean marketPurchase(TownspersonMob buyer,
                                         TownspersonMob merchantFallback,
                                         CurrencyValue amount,
                                         ServerLevel level,
                                         VillageSavedData data,
                                         tterrag1112.life_in_the_village
                                                 .Village.Economy.Market.MarketStall stall) {
        if (!buyer.getWallet().canAfford(amount)) return false;

        if (stall != null && stall.isActive()) {
            // Stall owner receives
            if (stall.getOwnerType() == tterrag1112.life_in_the_village
                    .Village.Economy.Market.MarketStall.OwnerType.NPC) {
                TownspersonMob owner = findNpc(level, stall.getOwnerUUID());
                if (owner != null) {
                    return npcPay(buyer, owner, amount, level);
                }
            }
            // Player stall: coins go into stall chest (handled in TradeHandler)
        }

        // No stall or stall owner missing — pay merchant
        if (merchantFallback != null) {
            return npcPay(buyer, merchantFallback, amount, level);
        }

        // No merchant — spend into the void (market tax simulation)
        if (!buyer.getWallet().spend(amount)) return false;
        NpcTransactionVisual.showPayment(buyer, level);
        return true;
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