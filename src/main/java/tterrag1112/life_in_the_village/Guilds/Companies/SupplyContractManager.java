// src/main/java/tterrag1112/life_in_the_village/Guilds/Companies/SupplyContractManager.java
package tterrag1112.life_in_the_village.Guilds.Companies;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.resources.Identifier;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;

import java.util.*;
import java.util.stream.Stream;

/**
 * Processes supply contract deliveries each payroll cycle.
 *
 * <h3>Called from</h3>
 * {@code BusinessSavedData.tickPayroll()} — once per payroll tick (every in-game day).
 *
 * <h3>Delivery logic per contract</h3>
 * <ol>
 *   <li>Find the supplier business's primary storage building.</li>
 *   <li>Check whether {@code weeklyQuantity} of {@code itemId} is available.</li>
 *   <li>If yes: deduct goods from supplier, deposit goods into buyer, transfer coins.</li>
 *   <li>If no: increment {@code missedDeliveries}. If ≥ 3, mark BREACHED and notify.</li>
 * </ol>
 */
public final class SupplyContractManager {

    /** Number of consecutive misses before a contract becomes BREACHED. */
    private static final int BREACH_THRESHOLD = 3;

    private SupplyContractManager() {}

    // =========================================================================
    // Main tick entry point — called from BusinessSavedData.tickPayroll()
    // =========================================================================

    /**
     * Processes all active supply contracts stored in {@code cdata}.
     *
     * @param level   the server level (needed for building storage access)
     * @param cdata   business saved data containing contracts
     * @param vdata   village saved data for building lookups
     */
    public static void tickContracts(ServerLevel level,
                                     BusinessSavedData cdata,
                                     VillageSavedData vdata) {
        List<SupplyContract> contracts = cdata.getAllContracts();

        for (SupplyContract contract : contracts) {
            if (!contract.isActive()) continue;

            processContract(contract, level, cdata, vdata);
        }
    }

    // =========================================================================
    // Per-contract processing
    // =========================================================================

    private static void processContract(SupplyContract contract,
                                        ServerLevel level,
                                        BusinessSavedData cdata,
                                        VillageSavedData vdata) {

        Business supplier = cdata.getById(contract.supplierCompanyId()).orElse(null);
        Business buyer    = cdata.getById(contract.buyerCompanyId()).orElse(null);

        if (supplier == null || buyer == null) {
            // One party no longer exists — cancel the contract
            cdata.updateContract(contract.withStatus(
                    SupplyContract.ContractStatus.CANCELLED));
            return;
        }

        // Resolve item
        Item item = BuiltInRegistries.ITEM
                .get(Identifier.parse(contract.itemId()))
                .map(h -> h.value())
                .orElse(null);
        if (item == null) return;

        // Find the best storage building for each party
        Building supplierStorage = findPrimaryStorage(supplier, vdata, level);
        Building buyerStorage    = findPrimaryStorage(buyer, vdata, level);

        if (supplierStorage == null || buyerStorage == null) {
            recordMiss(contract, supplier, buyer, level, cdata,
                    "No storage building available.");
            return;
        }

        int availableStock = BuildingStorageAccess.countItem(
                level, supplierStorage, item);

        if (availableStock < contract.weeklyQuantity()) {
            recordMiss(contract, supplier, buyer, level, cdata,
                    "Insufficient stock (" + availableStock
                            + "/" + contract.weeklyQuantity() + " "
                            + item.getName().getString() + ").");
            return;
        }

        // Check buyer treasury can cover the cost
        long totalCost = contract.totalWeeklyCost();
        if (buyer.getTreasuryBronze() < totalCost) {
            recordMiss(contract, supplier, buyer, level, cdata,
                    "Insufficient treasury funds.");
            return;
        }

        // === Successful delivery ===

        // 1. Deduct goods from supplier
        BuildingStorageAccess.takeItem(
                level, supplierStorage, item, contract.weeklyQuantity());

        // 2. Deposit goods into buyer storage
        net.minecraft.world.item.ItemStack toGive =
                new net.minecraft.world.item.ItemStack(
                        item, contract.weeklyQuantity());
        BuildingStorageAccess.storeItem(level, buyerStorage, toGive);

        // 3. Transfer coins: buyer treasury → supplier treasury
        buyer.withdrawFromTreasury(totalCost);
        supplier.depositBronze(totalCost);
        cdata.markDirty();

        // 4. Reset missed deliveries counter
        if (contract.missedDeliveries() > 0) {
            cdata.updateContract(contract.withMissedDeliveries(0));
        }

        System.out.println("SupplyContractManager: delivered "
                + contract.weeklyQuantity() + "x "
                + item.getName().getString()
                + " from " + supplier.getName()
                + " → " + buyer.getName()
                + " for " + CurrencyValue.of(totalCost));

        // 5. Notify both owners
        notifyPlayer(level, supplier.getPlayerOwnerId().orElse(null), // PB1: sealed-owner notification
                "[" + supplier.getName() + "] Supply contract fulfilled: "
                        + contract.weeklyQuantity() + "x "
                        + item.getName().getString()
                        + " delivered. +" + CurrencyValue.of(totalCost));

        notifyPlayer(level, buyer.getPlayerOwnerId().orElse(null), // PB1: sealed-owner notification
                "[" + buyer.getName() + "] Supply received: "
                        + contract.weeklyQuantity() + "x "
                        + item.getName().getString()
                        + " from " + supplier.getName() + ".");
    }

    // =========================================================================
    // Miss / breach handling
    // =========================================================================

    private static void recordMiss(SupplyContract contract,
                                   Business supplier,
                                   Business buyer,
                                   ServerLevel level,
                                   BusinessSavedData cdata,
                                   String reason) {
        int newMisses = contract.missedDeliveries() + 1;

        if (newMisses >= BREACH_THRESHOLD) {
            SupplyContract breached = contract
                    .withMissedDeliveries(newMisses)
                    .withStatus(SupplyContract.ContractStatus.BREACHED);
            cdata.updateContract(breached);

            notifyPlayer(level, supplier.getPlayerOwnerId().orElse(null), // PB1: sealed-owner notification
                    "[" + supplier.getName() + "] Supply contract with "
                            + buyer.getName() + " has been BREACHED. Reason: " + reason);
            notifyPlayer(level, buyer.getPlayerOwnerId().orElse(null), // PB1: sealed-owner notification
                    "[" + buyer.getName() + "] Supply contract with "
                            + supplier.getName() + " has been BREACHED. Reason: " + reason
                            + " — Renegotiate in the business management screen.");
        } else {
            cdata.updateContract(contract.withMissedDeliveries(newMisses));
            notifyPlayer(level, buyer.getPlayerOwnerId().orElse(null), // PB1: sealed-owner notification
                    "[" + buyer.getName() + "] Missed supply delivery from "
                            + supplier.getName() + " (" + newMisses
                            + "/" + BREACH_THRESHOLD + " misses). Reason: " + reason);
        }
        cdata.markDirty();
    }

    // =========================================================================
    // Proposal & acceptance helpers (called from packet handlers)
    // =========================================================================

    /**
     * Creates a new PENDING contract and stores it in {@code cdata}.
     * The supplier player will see a notification to accept or decline.
     */
    public static SupplyContract proposeContract(UUID buyerCompanyId,
                                                 UUID supplierCompanyId,
                                                 String itemId,
                                                 int weeklyQuantity,
                                                 long bronzePricePerUnit,
                                                 BusinessSavedData cdata,
                                                 ServerLevel level) {
        SupplyContract contract = SupplyContract.propose(
                buyerCompanyId, supplierCompanyId,
                itemId, weeklyQuantity, bronzePricePerUnit);
        cdata.addContract(contract);
        cdata.markDirty();

        // Notify supplier owner
        cdata.getById(supplierCompanyId).ifPresent(supplier ->
                notifyPlayer(level, supplier.getPlayerOwnerId().orElse(null), // PB1: sealed-owner notification
                        "[" + supplier.getName() + "] New supply contract proposed. "
                                + "Open your business management screen to accept or decline.")
        );

        return contract;
    }

    /**
     * Accepts a PENDING contract, making it ACTIVE.
     */
    public static void acceptContract(UUID contractId,
                                      BusinessSavedData cdata,
                                      ServerLevel level) {
        cdata.getAllContracts().stream()
                .filter(c -> c.contractId().equals(contractId)
                        && c.isPending())
                .findFirst()
                .ifPresent(c -> {
                    cdata.updateContract(
                            c.withStatus(SupplyContract.ContractStatus.ACTIVE));
                    cdata.markDirty();

                    cdata.getById(c.buyerCompanyId()).ifPresent(buyer ->
                            notifyPlayer(level, buyer.getPlayerOwnerId().orElse(null), // PB1: sealed-owner notification
                                    "[" + buyer.getName()
                                            + "] Your supply contract has been accepted!")
                    );
                });
    }

    /**
     * Cancels any contract (PENDING, ACTIVE, or BREACHED).
     */
    public static void cancelContract(UUID contractId,
                                      BusinessSavedData cdata) {
        cdata.getAllContracts().stream()
                .filter(c -> c.contractId().equals(contractId))
                .findFirst()
                .ifPresent(c -> {
                    cdata.updateContract(
                            c.withStatus(SupplyContract.ContractStatus.CANCELLED));
                    cdata.markDirty();
                });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Finds the best storage building for a business.
     * Prefers STOCKPILE, then falls back to the first building with any storage.
     */
    private static Building findPrimaryStorage(Business business,
                                               VillageSavedData vdata,
                                               ServerLevel level) {
        List<Building> buildings = business.getBuildingIds().stream()
                .map(vdata::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        // Prefer a stockpile building
        return buildings.stream()
                .filter(b -> b.getType()
                        == tterrag1112.life_in_the_village.Village.Buildings.BuildingType.STOCKPILE)
                .findFirst()
                // Fall back to any building that has storage containers
                .or(() -> buildings.stream()
                        .filter(b -> !BuildingStorageAccess
                                .findInventories(level, b).isEmpty())
                        .findFirst())
                .orElse(null);
    }

    private static void notifyPlayer(ServerLevel level,
                                     UUID playerId,
                                     String message) {
        ServerPlayer player = level.getServer()
                .getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.displayClientMessage(
                    Component.literal(message), false);
        }
    }
}