// src/main/java/tterrag1112/life_in_the_village/Village/Buildings/HousePurchaseManager.java
package tterrag1112.life_in_the_village.Village.Buildings;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Reputation.ReputationManager;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class HousePurchaseManager {

    // Base price per block of building footprint
    private static final long BASE_PRICE_PER_BLOCK = 5L;
    // Property tax interval — every in-game week
    private static final long TAX_INTERVAL = 24000L * 7;

    // =========================================================================
    // Purchase
    // =========================================================================

    /**
     * Called when a player interacts with a Village Leader NPC and requests
     * to buy a house.
     */
    public static void handlePurchaseRequest(
            ServerPlayer player,
            TownspersonMob villageLeader,
            UUID buildingId,
            ServerLevel level) {

        VillageSavedData data = VillageSavedData.get(level);

        Building building = data.getBuildingById(buildingId).orElse(null);
        if (building == null || building.getType() != BuildingType.HOUSE) {
            player.displayClientMessage(
                    Component.literal("That is not a house."), false);
            return;
        }

        // Already owned?
        if (data.isPlayerOwned(buildingId)) {
            data.getPropertyForBuilding(buildingId).ifPresent(prop -> {
                if (prop.playerId().equals(player.getUUID())) {
                    player.displayClientMessage(
                            Component.literal("You already own this house."), false);
                } else {
                    player.displayClientMessage(
                            Component.literal("This house is already owned."), false);
                }
            });
            return;
        }

        // Occupied by an NPC?
        boolean npcAssigned = level.getEntitiesOfClass(
                TownspersonMob.class,
                building.getShape().toAABB().inflate(32),
                npc -> npc.getHouseId()
                        .map(id -> id.equals(buildingId))
                        .orElse(false)
        ).size() > 0;

        if (npcAssigned) {
            player.displayClientMessage(
                    Component.literal("This house is occupied by a resident."), false);
            return;
        }

        // Find village
        Village village = villageLeader.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .orElse(null);

        if (village == null) {
            player.displayClientMessage(
                    Component.literal("Could not determine village."), false);
            return;
        }

        long price = calculatePrice(building, village, data);
        CurrencyValue cost = CurrencyValue.of(price);

        // Can the player afford it?
        var playerContainer = buildTempContainer(player);
        if (!CoinHelper.canAfford(playerContainer, cost)) {
            player.displayClientMessage(
                    Component.literal("This house costs " + cost + ".\n"
                            + "You cannot afford it."), false);
            return;
        }

        // Deduct payment
        CoinHelper.spend(playerContainer, cost);
        syncContainer(player, playerContainer);

        // Half the purchase price goes to the village treasury
        data.getKingdomForVillage(village.getId())
                .ifPresent(k -> k.depositToTreasury(price / 2));

        // Register the property
        PlayerHousingData.PlayerProperty property =
                new PlayerHousingData.PlayerProperty(
                        player.getUUID(),
                        buildingId,
                        village.getId(),
                        PlayerHousingData.OwnershipType.PURCHASED,
                        level.getGameTime(),
                        level.getGameTime(),
                        price);

        data.addPlayerProperty(property);
        data.setDirty();

        // ── Reputation: owning property raises standing ───────────────────────
        ReputationManager.onPropertyPurchased(player, village.getId(), level);

        // Notify player
        player.displayClientMessage(
                Component.literal("You have purchased "
                                + building.getName() + " for " + cost + "!\n"
                                + "Weekly tax: "
                                + CurrencyValue.of(calculateWeeklyTax(building, village, data)))
                        .withStyle(net.minecraft.ChatFormatting.GREEN),
                false);
    }

    // =========================================================================
    // Weekly tax processing
    // =========================================================================

    /**
     * Should be called once per tax interval (every {@link #TAX_INTERVAL} ticks)
     * for each online player. Deducts weekly tax and awards/penalises reputation.
     */
    public static void processTax(ServerPlayer player,
                                  ServerLevel level,
                                  long currentTick) {
        VillageSavedData data = VillageSavedData.get(level);
        List<PlayerHousingData.PlayerProperty> properties =
                data.getPropertiesForPlayer(player.getUUID());

        if (properties.isEmpty()) return;

        for (PlayerHousingData.PlayerProperty prop : properties) {
            // Only charge once per interval
            if (currentTick - prop.lastTaxTick() < TAX_INTERVAL) continue;

            Village village = data.getVillageById(prop.villageId()).orElse(null);
            Building building = data.getBuildingById(prop.buildingId()).orElse(null);
            if (village == null || building == null) continue;

            long tax = calculateWeeklyTax(building, village, data);
            if (tax == 0L) {
                // No tax law active — still update the tick so we don't catch up
                data.updatePropertyTaxTick(prop.buildingId(), currentTick);
                continue;
            }

            CurrencyValue taxCost = CurrencyValue.of(tax);
            var playerContainer   = buildTempContainer(player);

            if (CoinHelper.canAfford(playerContainer, taxCost)) {
                CoinHelper.spend(playerContainer, taxCost);
                syncContainer(player, playerContainer);

                // Half goes to kingdom treasury
                data.getKingdomForVillage(village.getId())
                        .ifPresent(k -> k.depositToTreasury(tax / 2));

                // ── Reputation: paying tax on time is a positive signal ────────
                ReputationManager.onPropertyTaxPaid(player, village.getId(), level);

                player.displayClientMessage(
                        Component.literal("Weekly tax of " + taxCost
                                + " collected for " + building.getName() + "."),
                        false);
            } else {
                // Couldn't pay — penalise reputation and warn player
                ReputationManager.onPropertyTaxFailed(player, village.getId(), level);

                player.displayClientMessage(
                        Component.literal("You could not afford the weekly tax ("
                                        + taxCost + ") for " + building.getName()
                                        + ". Pay soon or risk losing the property.")
                                .withStyle(net.minecraft.ChatFormatting.RED),
                        false);
            }

            data.updatePropertyTaxTick(prop.buildingId(), currentTick);
        }
    }
    public static void tickPropertyTax(ServerLevel level,
                                       VillageSavedData data,
                                       long currentTick) {
        if (currentTick % TAX_INTERVAL != 0) return;

        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            processTax(player, level, currentTick);
        }
    }

    // =========================================================================
    // Price calculation
    // =========================================================================

    public static long calculatePrice(Building building,
                                      Village village,
                                      VillageSavedData data) {
        int  footprint = building.getShape().getWidth()
                * building.getShape().getLength();
        long baseRate  = data.getPropertyTaxRate(village.getId());
        // Purchase price = 50× the weekly tax rate × footprint
        return footprint * baseRate * 50;
    }

    public static long calculateWeeklyTax(Building building,
                                          Village village,
                                          VillageSavedData data) {
        // Tax is only levied when the PROPERTY_RIGHTS law is active
        boolean taxEnabled = data.getKingdomForVillage(village.getId())
                .map(k -> k.hasLaw(
                        tterrag1112.life_in_the_village.Kingdom.KingdomLaw.PROPERTY_RIGHTS))
                .orElse(false);

        if (!taxEnabled) return 0L;

        int  footprint = building.getShape().getWidth()
                * building.getShape().getLength();
        long rate      = data.getPropertyTaxRate(village.getId());
        return footprint * rate;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static net.minecraft.world.SimpleContainer buildTempContainer(
            ServerPlayer player) {
        return CoinHelper.snapshotInventory(player);
    }

    private static void syncContainer(ServerPlayer player,
                                      net.minecraft.world.SimpleContainer container) {
        CoinHelper.syncInventory(player, container);
    }
}