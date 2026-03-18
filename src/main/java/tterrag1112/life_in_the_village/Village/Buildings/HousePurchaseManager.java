package tterrag1112.life_in_the_village.Village.Buildings;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class HousePurchaseManager {

    // Base price per block of building footprint
    // Village leader can adjust via setPropertyTaxRate
    private static final long BASE_PRICE_PER_BLOCK = 5L;
    // Property tax interval — every in-game week
    private static final long TAX_INTERVAL = 24000L * 7;

    // -------------------------------------------------------------------------
    // Purchase
    // -------------------------------------------------------------------------

    /**
     * Called when player interacts with Village Leader NPC
     * and requests to buy a house.
     */
    public static void handlePurchaseRequest(
            ServerPlayer player,
            TownspersonMob villageLeader,
            UUID buildingId,
            ServerLevel level) {

        VillageSavedData data = VillageSavedData.get(level);

        // Check building exists and is a HOUSE type
        Building building = data.getBuildingById(buildingId)
                .orElse(null);
        if (building == null
                || building.getType()
                != BuildingType.HOUSE) {
            player.displayClientMessage(
                    Component.literal("That is not a house."),
                    false);
            return;
        }

        // Check not already owned
        if (data.isPlayerOwned(buildingId)) {
            data.getPropertyForBuilding(buildingId)
                    .ifPresent(prop -> {
                        if (prop.playerId()
                                .equals(player.getUUID())) {
                            player.displayClientMessage(
                                    Component.literal(
                                            "You already own "
                                                    + "this house."),
                                    false);
                        } else {
                            player.displayClientMessage(
                                    Component.literal(
                                            "This house is "
                                                    + "already owned."),
                                    false);
                        }
                    });
            return;
        }

        // Check not currently assigned to an NPC
        boolean npcAssigned = level.getEntitiesOfClass(
                        TownspersonMob.class,
                        building.getShape().toAABB().inflate(32),
                        npc -> npc.getHouseId()
                                .map(id -> id.equals(buildingId))
                                .orElse(false))
                .size() > 0;

        if (npcAssigned) {
            player.displayClientMessage(
                    Component.literal(
                            "This house is occupied by "
                                    + "a resident."),
                    false);
            return;
        }

        // Calculate price
        Village village = villageLeader
                .getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .orElse(null);

        if (village == null) {
            player.displayClientMessage(
                    Component.literal(
                            "Could not determine village."),
                    false);
            return;
        }

        long price = calculatePrice(building, village, data);
        CurrencyValue cost = CurrencyValue.of(price);

        // Check player can afford it
        var playerContainer =
                buildTempContainer(player);
        if (!CoinHelper.canAfford(playerContainer, cost)) {
            player.displayClientMessage(
                    Component.literal(
                            "This house costs "
                                    + cost + ".\n"
                                    + "You cannot afford it."),
                    false);
            return;
        }

        // Deduct payment
        CoinHelper.spend(playerContainer, cost);
        syncContainer(player, playerContainer);

        // Pay village treasury
        data.getKingdomForVillage(village.getId())
                .ifPresent(k -> k.depositToTreasury(
                        price / 2));

        // Register property
        PlayerHousingData.PlayerProperty property =
                new PlayerHousingData.PlayerProperty(
                        player.getUUID(),
                        buildingId,
                        village.getId(),
                        PlayerHousingData.OwnershipType
                                .PURCHASED,
                        level.getGameTime(),
                        level.getGameTime(),
                        price);

        data.addPlayerProperty(property);
        data.setDirty();

        // Set player as house owner on TownspersonMob
        // side — so kingdom progression can check it
        player.displayClientMessage(
                Component.literal(
                                "You have purchased "
                                        + building.getName()
                                        + " for " + cost + "!\n"
                                        + "Weekly tax: "
                                        + CurrencyValue.of(
                                        calculateWeeklyTax(
                                                building, village,
                                                data)))
                        .withStyle(
                                net.minecraft.ChatFormatting
                                        .GREEN),
                false);

        // Notify village leader
        /*villageLeader.displayClientMessage(
                Component.literal(
                        "Welcome to " + village.getName()
                                + ", " + player.getName()
                                .getString() + "!"),
                false);

         */
    }

    // -------------------------------------------------------------------------
    // Tax collection — called from WorldEvents tick
    // -------------------------------------------------------------------------

    public static void tickPropertyTax(ServerLevel level,
                                       VillageSavedData data,
                                       long currentTick) {
        if (currentTick % TAX_INTERVAL != 0) return;

        data.getPropertiesForPlayer(null); // iterate all
        // Collect all properties
        var allProperties = data.getAllPlayerProperties();

        for (var prop : allProperties) {
            if (currentTick - prop.lastTaxTick()
                    < TAX_INTERVAL) continue;

            Building building = data.getBuildingById(
                    prop.buildingId()).orElse(null);
            Village village   = data.getVillageById(
                    prop.villageId()).orElse(null);
            if (building == null || village == null) continue;

            long taxAmount = calculateWeeklyTax(
                    building, village, data);
            if (taxAmount == 0) continue;

            // Try to collect from online player
            var player = level.getServer()
                    .getPlayerList()
                    .getPlayer(prop.playerId());

            if (player != null) {
                var container = buildTempContainer(player);
                CurrencyValue tax = CurrencyValue.of(
                        taxAmount);

                if (CoinHelper.canAfford(container, tax)) {
                    CoinHelper.spend(container, tax);
                    syncContainer(player, container);

                    // Pay to village treasury
                    data.getKingdomForVillage(
                                    village.getId())
                            .ifPresent(k ->
                                    k.depositToTreasury(
                                            taxAmount));

                    player.displayClientMessage(
                            Component.literal(
                                            "Property tax collected: "
                                                    + tax + " for "
                                                    + building.getName())
                                    .withStyle(
                                            net.minecraft
                                                    .ChatFormatting
                                                    .YELLOW),
                            false);
                } else {
                    // Can't afford — grace period warning
                    player.displayClientMessage(
                            Component.literal(
                                            "Warning: You cannot "
                                                    + "afford your property "
                                                    + "tax of " + tax
                                                    + " for "
                                                    + building.getName()
                                                    + ". Pay soon or risk "
                                                    + "losing the property.")
                                    .withStyle(
                                            net.minecraft
                                                    .ChatFormatting
                                                    .RED),
                            false);
                }
            }

            // Update lastTaxTick regardless
            data.updatePropertyTaxTick(
                    prop.buildingId(), currentTick);
        }
    }

    // -------------------------------------------------------------------------
    // Price calculation
    // -------------------------------------------------------------------------

    public static long calculatePrice(Building building,
                                      Village village,
                                      VillageSavedData data) {
        int footprint = building.getShape().getWidth()
                * building.getShape().getLength();
        long baseRate = data.getPropertyTaxRate(
                village.getId());
        // Purchase price = 50x the weekly tax rate
        return footprint * baseRate * 50;
    }

    public static long calculateWeeklyTax(Building building,
                                          Village village,
                                          VillageSavedData data) {
        // Check if village has property tax law active
        boolean taxEnabled = data.getKingdomForVillage(
                        village.getId())
                .map(k -> k.hasLaw(
                        tterrag1112.life_in_the_village
                                .Kingdom.KingdomLaw
                                .PROPERTY_RIGHTS))
                .orElse(false);

        if (!taxEnabled) return 0L;

        int footprint = building.getShape().getWidth()
                * building.getShape().getLength();
        long rate = data.getPropertyTaxRate(village.getId());
        return footprint * rate;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static net.minecraft.world.SimpleContainer
    buildTempContainer(ServerPlayer player) {
        var c = new net.minecraft.world.SimpleContainer(
                player.getInventory().getContainerSize());
        for (int i = 0; i < player.getInventory()
                .getContainerSize(); i++) {
            c.setItem(i, player.getInventory()
                    .getItem(i).copy());
        }
        return c;
    }

    private static void syncContainer(
            ServerPlayer player,
            net.minecraft.world.SimpleContainer container) {
        for (int i = 0; i < player.getInventory()
                .getContainerSize(); i++) {
            player.getInventory().setItem(
                    i, container.getItem(i));
        }
        player.inventoryMenu.broadcastChanges();
    }
}