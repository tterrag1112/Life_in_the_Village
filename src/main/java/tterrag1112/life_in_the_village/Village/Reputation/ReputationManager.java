// src/main/java/tterrag1112/life_in_the_village/Village/Reputation/ReputationManager.java
package tterrag1112.life_in_the_village.Village.Reputation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;
import java.util.UUID;

/**
 * Central entry point for all reputation mutations.
 * <p>
 * Every system that should affect a player's village standing calls a method
 * here rather than touching {@link VillageSavedData} directly. This keeps all
 * reputation logic in one auditable location.
 * </p>
 *
 * <h3>Hook sites</h3>
 * <ul>
 *   <li>{@link tterrag1112.life_in_the_village.Village.Economy.Currency.TradeHandler}
 *       — call {@link #onTradeCompleted} after each successful transaction</li>
 *   <li>{@link tterrag1112.life_in_the_village.Profession.ProfessionEvents}
 *       — call {@link #onJobPostingCompleted} when a job posting reward is paid</li>
 *   <li>{@link tterrag1112.life_in_the_village.Village.Buildings.HousePurchaseManager}
 *       — call {@link #onPropertyPurchased} when the purchase finishes</li>
 *   <li>{@link tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData}
 *       — call {@link #onCompanyWagesPaid} during payroll</li>
 *   <li>Crime / guard system — call {@link #}</li>
 * </ul>
 */
public final class ReputationManager {

    private ReputationManager() {}

    // =========================================================================
    // Public hook API
    // =========================================================================

    /** Player completed a job posting for a village NPC. */
    public static void onJobPostingCompleted(ServerPlayer player,
                                             UUID villageId,
                                             ServerLevel level) {
        modify(player, villageId, VillageReputation.ChangeReason.JOB_POSTING_COMPLETE, level);
    }

    /** Player completed a single trade at a village market. */
    public static void onTradeCompleted(ServerPlayer player,
                                        UUID villageId,
                                        ServerLevel level) {
        modify(player, villageId, VillageReputation.ChangeReason.TRADE_WITH_MERCHANT, level);
    }

    /** Player just purchased property in a village. */
    public static void onPropertyPurchased(ServerPlayer player,
                                           UUID villageId,
                                           ServerLevel level) {
        modify(player, villageId, VillageReputation.ChangeReason.OWN_PROPERTY, level);
    }

    /** Player paid weekly tax on their property. */
    public static void onPropertyTaxPaid(ServerPlayer player,
                                         UUID villageId,
                                         ServerLevel level) {
        modify(player, villageId, VillageReputation.ChangeReason.PROPERTY_TAX_PAID, level);
    }

    /** Player failed to pay property tax. */
    public static void onPropertyTaxFailed(ServerPlayer player,
                                           UUID villageId,
                                           ServerLevel level) {
        modify(player, villageId, VillageReputation.ChangeReason.FAILED_TO_PAY_TAX, level);
    }

    /** Player's company paid wages this tick cycle. */
    public static void onCompanyWagesPaid(ServerPlayer player,
                                          UUID villageId,
                                          ServerLevel level) {
        modify(player, villageId, VillageReputation.ChangeReason.COMPANY_WAGES_PAID, level);
    }

    /** Player fulfilled a crafting order. */
    public static void onCraftingOrderFilled(ServerPlayer player,
                                             UUID villageId,
                                             ServerLevel level) {
        modify(player, villageId, VillageReputation.ChangeReason.CRAFTING_ORDER_FILLED, level);
    }

    /** Player donated coins directly to the village treasury. */
    public static void onDonationMade(ServerPlayer player,
                                      UUID villageId,
                                      ServerLevel level) {
        modify(player, villageId, VillageReputation.ChangeReason.DONATED_TO_VILLAGE, level);
    }

    /** Player stole from a container inside the village. */
    public static void onStolenFromContainer(ServerPlayer player,
                                             UUID villageId,
                                             ServerLevel level) {
        modify(player, villageId, VillageReputation.ChangeReason.STOLE_FROM_CONTAINER, level);
    }

    /** Player attacked an NPC. */
    public static void onAttackedNpc(ServerPlayer player,
                                     UUID villageId,
                                     ServerLevel level) {
        modify(player, villageId, VillageReputation.ChangeReason.ATTACKED_NPC, level);
    }

    /** Player trespassed in a restricted building area. */
    public static void onTrespassed(ServerPlayer player,
                                    UUID villageId,
                                    ServerLevel level) {
        modify(player, villageId, VillageReputation.ChangeReason.TRESPASSED, level);
    }

    // =========================================================================
    // Query API
    // =========================================================================

    /**
     * Returns the player's current reputation tier in a village.
     * Returns {@link VillageReputation.Tier#NEWCOMER} if no record exists yet.
     */
    public static VillageReputation.Tier getTier(ServerPlayer player,
                                                 UUID villageId,
                                                 ServerLevel level) {
        return getOrCreate(player.getUUID(), villageId,
                VillageSavedData.get(level)).getTier();
    }

    /**
     * Returns the raw {@link VillageReputation} record, creating a NEWCOMER
     * entry if none exists.
     */
    public static VillageReputation getReputation(UUID playerId,
                                                  UUID villageId,
                                                  VillageSavedData data) {
        return getOrCreate(playerId, villageId, data);
    }

    /**
     * Convenience — returns the sell price after applying the player's
     * discount for the nearest village to the given player.
     *
     * @param basePrice   base sell price in bronze
     * @param player      the buyer
     * @param villageId   which village the merchant belongs to
     * @param level       server level
     * @return discounted price (never below 1)
     */
    public static long applyDiscount(long basePrice,
                                     ServerPlayer player,
                                     UUID villageId,
                                     ServerLevel level) {
        VillageReputation rep = getOrCreate(
                player.getUUID(), villageId, VillageSavedData.get(level));
        return rep.applyDiscount(basePrice);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Core mutation — modifies the reputation record and notifies the player
     * if a tier boundary is crossed.
     */
    private static void modify(ServerPlayer player,
                               UUID villageId,
                               VillageReputation.ChangeReason reason,
                               ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        VillageReputation rep = getOrCreate(player.getUUID(), villageId, data);

        VillageReputation.Tier before = rep.getTier();
        boolean crossed = rep.apply(reason);
        data.markDirty();

        // Notify on tier change
        if (crossed) {
            VillageReputation.Tier after = rep.getTier();
            String villageName = data.getVillageById(villageId)
                    .map(Village::getName)
                    .orElse("this village");

            if (after.ordinal() > before.ordinal()) {
                // Improved
                player.displayClientMessage(
                        Component.literal("Your standing in " + villageName
                                        + " has improved: ")
                                .append(after.toComponent()),
                        false);
            } else {
                // Worsened
                player.displayClientMessage(
                        Component.literal("Your standing in " + villageName
                                        + " has fallen: ")
                                .append(after.toComponent()),
                        false);
            }
        }
    }

    private static VillageReputation getOrCreate(UUID playerId,
                                                 UUID villageId,
                                                 VillageSavedData data) {
        Optional<VillageReputation> existing =
                data.getReputation(playerId, villageId);
        if (existing.isPresent()) return existing.get();

        VillageReputation fresh = VillageReputation.createNew(playerId, villageId);
        data.addReputation(fresh);
        return fresh;
    }
}