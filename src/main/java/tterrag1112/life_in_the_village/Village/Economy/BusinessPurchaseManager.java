
package tterrag1112.life_in_the_village.Village.Economy;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.PlayerProfession;
import tterrag1112.life_in_the_village.Profession.PlayerProfessionData;
import tterrag1112.life_in_the_village.Profession.PlayerWorkplace;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages player purchase and ownership of farm businesses.
 *
 * Players can:
 * - Purchase existing farmhouses from NPCs
 * - Inherit farmhouses when head farmer dies
 * - Request new farmhouse construction
 * - Manage their farm as an owner
 */
public class BusinessPurchaseManager {

    // Minimum requirements to purchase a farm
    private static final int MIN_FARMER_LEVEL = 3;
    private static final long BASE_PURCHASE_PRICE = 500L; // Bronze coins

    // Price multipliers based on farm size/quality
    private static final long PRICE_PER_PLOT = 100L;
    private static final long PRICE_PER_BUSINESS_LEVEL = 200L;

    // =========================================================================
    // Purchase validation
    // =========================================================================

    /**
     * Check if a player can purchase a specific farmhouse
     */
    public static boolean canPurchase(ServerPlayer player, Building farmhouse, ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);

        // Must be farmer profession level 3+
        if (profData.getLevel(PlayerProfession.FARMER) < MIN_FARMER_LEVEL) {
            return false;
        }

        // Must not already own a farm
        if (profData.getWorkplace(PlayerProfession.FARMER)
                .map(PlayerWorkplace.WorkplaceEntry::isOwner)
                .orElse(false)) {
            return false;
        }

        // Calculate purchase price
        CurrencyValue price = calculatePurchasePrice(farmhouse, level);

        // Must have enough money
        return CoinHelper.countCoins(player).toBronze() >= price.toBronze();
    }

    /**
     * Calculate the purchase price for a farmhouse
     */
    public static CurrencyValue calculatePurchasePrice(Building farmhouse, ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);

        // Base price
        long totalPrice = BASE_PURCHASE_PRICE;

        // Add price per plot
        List<FarmPlot> plots = data.getFarmPlotsForFarmhouse(farmhouse.getId());
        totalPrice += plots.size() * PRICE_PER_PLOT;

        // Add price based on business level
        FarmBusinessLevel businessLevel = data.getOrCreateFarmBusinessLevel(farmhouse.getId());
        totalPrice += businessLevel.getBusinessLevel() * PRICE_PER_BUSINESS_LEVEL;

        return CurrencyValue.of(totalPrice);
    }

    // =========================================================================
    // Purchase execution
    // =========================================================================

    /**
     * Execute the purchase of a farmhouse
     */
    public static boolean purchaseFarm(ServerPlayer player, Building farmhouse,
                                       TownspersonMob currentOwner, ServerLevel level) {
        if (!canPurchase(player, farmhouse, level)) {
            player.displayClientMessage(Component.literal(
                            "You cannot purchase this farm. Check requirements: Farmer level 3+, sufficient funds, no existing farm ownership."),
                    false);
            return false;
        }

        VillageSavedData data = VillageSavedData.get(level);
        PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);

        // Calculate and charge price
        CurrencyValue price = calculatePurchasePrice(farmhouse, level);
        if (!CoinHelper.removeCoins(player, price)) {
            player.displayClientMessage(Component.literal(
                            "Transaction failed - insufficient funds."),
                    false);
            return false;
        }

        // Pay the current owner (if NPC)
        if (currentOwner != null) {
            // Give coins to the NPC's personal inventory
            CoinHelper.giveCoins(currentOwner.getPersonalInventory(), price);

            // Remove the NPC's assignment to this farmhouse
            currentOwner.clearAssignedBuilding();

            player.displayClientMessage(Component.literal(
                            "[" + currentOwner.getNpcName() + "] Thank you for purchasing my farm. I wish you prosperity!"),
                    false);
        }

        // Get village ID
        UUID villageId = data.getAllVillages().stream()
                .filter(v -> v.getBuildingIds().contains(farmhouse.getId()))
                .map(Village::getId)
                .findFirst().orElse(null);

        if (villageId == null) {
            player.displayClientMessage(Component.literal(
                            "Error: Could not determine village for this farmhouse."),
                    false);
            return false;
        }

        // Create ownership entry
        PlayerWorkplace.WorkplaceEntry ownerEntry = new PlayerWorkplace.WorkplaceEntry(
                farmhouse.getId(),
                villageId,
                PlayerProfession.FARMER,
                true,
                level.getGameTime(),
                level.getGameTime(),
                null
        );

        profData.setWorkplace(PlayerProfession.FARMER, ownerEntry);
        player.setData(ModData.PROFESSION_DATA, profData);

        // Update business metrics
        FarmBusinessLevel businessLevel = data.getOrCreateFarmBusinessLevel(farmhouse.getId());
        businessLevel.updateMetrics(level);
        data.updateFarmBusinessLevel(businessLevel);

        player.displayClientMessage(Component.literal(
                        "Congratulations! You are now the owner of " + farmhouse.getName() + "!")
                .withStyle(net.minecraft.ChatFormatting.GOLD), false);

        player.displayClientMessage(Component.literal(
                        "Business Level: " + businessLevel.getBusinessLevelName() +
                                " | Plots: " + businessLevel.getPlotCount() +
                                " | Weekly Revenue: " + businessLevel.getWeeklyRevenue())
                .withStyle(net.minecraft.ChatFormatting.GREEN), false);

        return true;
    }

    // =========================================================================
    // Inheritance system
    // =========================================================================

    /**
     * Called when a head farmer dies - offer farm to eligible parties
     */
    public static void handleFarmerDeath(TownspersonMob deadFarmer, ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);

        // Get the farmhouse
        Building farmhouse = deadFarmer.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(null);

        if (farmhouse == null) return;

        System.out.println("Head farmer died at " + farmhouse.getName() + " - handling inheritance");

        // Priority 1: Offer to family members with farming skill
        // Priority 2: Offer to employed player farmhands
        // Priority 3: Offer to other village farmers
        // Priority 4: Assign new NPC farmer

        // Check for player farmhands
        List<ServerPlayer> playerFarmhands = level.getServer().getPlayerList().getPlayers().stream()
                .filter(p -> {
                    PlayerProfessionData profData = p.getData(ModData.PROFESSION_DATA);
                    return profData.getWorkplace(PlayerProfession.FARMER)
                            .map(w -> w.buildingId().equals(farmhouse.getId()) && !w.isOwner())
                            .orElse(false);
                })
                .toList();

        if (!playerFarmhands.isEmpty()) {
            // Offer to highest-level player farmhand
            ServerPlayer heir = playerFarmhands.stream()
                    .max((p1, p2) -> {
                        int l1 = p1.getData(ModData.PROFESSION_DATA).getLevel(PlayerProfession.FARMER);
                        int l2 = p2.getData(ModData.PROFESSION_DATA).getLevel(PlayerProfession.FARMER);
                        return Integer.compare(l1, l2);
                    })
                    .orElse(null);

            if (heir != null) {
                offerInheritance(heir, farmhouse, level);
                return;
            }
        }

        // No eligible heirs - assign new NPC farmer
        assignNewFarmer(farmhouse, level);
    }

    /**
     * Offer farm inheritance to a player
     */
    private static void offerInheritance(ServerPlayer player, Building farmhouse, ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);

        player.displayClientMessage(Component.literal(
                        "===============================================")
                .withStyle(net.minecraft.ChatFormatting.GOLD), false);

        player.displayClientMessage(Component.literal(
                        "The head farmer of " + farmhouse.getName() + " has passed away.")
                .withStyle(net.minecraft.ChatFormatting.YELLOW), false);

        player.displayClientMessage(Component.literal(
                        "As the senior farmhand, you have the right to inherit this farm.")
                .withStyle(net.minecraft.ChatFormatting.GREEN), false);

        FarmBusinessLevel businessLevel = data.getOrCreateFarmBusinessLevel(farmhouse.getId());
        player.displayClientMessage(Component.literal(
                        "Business: " + businessLevel.getBusinessLevelName() +
                                " | Plots: " + businessLevel.getPlotCount())
                .withStyle(net.minecraft.ChatFormatting.AQUA), false);

        player.displayClientMessage(Component.literal(
                        "Use /farm accept to claim ownership, or /farm decline to let it pass to another.")
                .withStyle(net.minecraft.ChatFormatting.WHITE), false);

        player.displayClientMessage(Component.literal(
                        "===============================================")
                .withStyle(net.minecraft.ChatFormatting.GOLD), false);

        // Store pending inheritance offer in player data
        player.getPersistentData().putString("PendingFarmInheritance", farmhouse.getId().toString());
    }

    /**
     * Player accepts farm inheritance
     */
    public static boolean acceptInheritance(ServerPlayer player, ServerLevel level) {
        String farmhouseIdStr = player.getPersistentData().getString("PendingFarmInheritance").get();
        if (farmhouseIdStr.isEmpty()) {
            player.displayClientMessage(Component.literal(
                    "You have no pending farm inheritance."), false);
            return false;
        }

        UUID farmhouseId = UUID.fromString(farmhouseIdStr);
        VillageSavedData data = VillageSavedData.get(level);

        Building farmhouse = data.getBuildingById(farmhouseId).orElse(null);
        if (farmhouse == null) {
            player.displayClientMessage(Component.literal(
                    "Farm no longer exists."), false);
            return false;
        }

        PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);

        // Get village ID
        UUID villageId = data.getAllVillages().stream()
                .filter(v -> v.getBuildingIds().contains(farmhouse.getId()))
                .map(Village::getId)
                .findFirst().orElse(null);

        // Upgrade to owner
        PlayerWorkplace.WorkplaceEntry ownerEntry = new PlayerWorkplace.WorkplaceEntry(
                farmhouse.getId(),
                villageId,
                PlayerProfession.FARMER,
                true,
                level.getGameTime(),
                level.getGameTime(),
                null
        );

        profData.setWorkplace(PlayerProfession.FARMER, ownerEntry);
        player.setData(ModData.PROFESSION_DATA, profData);

        player.getPersistentData().remove("PendingFarmInheritance");

        player.displayClientMessage(Component.literal(
                        "You are now the owner of " + farmhouse.getName() + "! May it prosper under your care.")
                .withStyle(net.minecraft.ChatFormatting.GOLD), false);

        return true;
    }

    /**
     * Assign a new NPC farmer when no heirs are available
     */
    private static void assignNewFarmer(Building farmhouse, ServerLevel level) {
        // Find an unemployed villager or spawn a new one
        // This is a placeholder - you'll need to implement NPC spawning logic
        System.out.println("TODO: Assign new NPC farmer to " + farmhouse.getName());
    }

    // =========================================================================
    // Player business formation
    // =========================================================================

    /**
     * Request construction of a new farmhouse for player ownership
     */
    public static boolean requestFarmhouseConstruction(ServerPlayer player,
                                                       Village village,
                                                       net.minecraft.core.BlockPos location,
                                                       ServerLevel level) {
        PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);

        // Requirements
        if (profData.getLevel(PlayerProfession.FARMER) < MIN_FARMER_LEVEL) {
            player.displayClientMessage(Component.literal(
                            "You need Farmer level " + MIN_FARMER_LEVEL + " or higher to request farmhouse construction."),
                    false);
            return false;
        }

        // Already owns a farm?
        if (profData.getWorkplace(PlayerProfession.FARMER)
                .map(PlayerWorkplace.WorkplaceEntry::isOwner)
                .orElse(false)) {
            player.displayClientMessage(Component.literal(
                            "You already own a farm!"),
                    false);
            return false;
        }

        // Construction cost
        CurrencyValue constructionCost = CurrencyValue.of(1000L); // 1000 bronze

        if (CoinHelper.countCoins(player).toBronze() < constructionCost.toBronze()) {
            player.displayClientMessage(Component.literal(
                            "Farmhouse construction costs " + constructionCost + ". You don't have enough."),
                    false);
            return false;
        }

        // Charge the player
        if (!CoinHelper.removeCoins(player, constructionCost)) {
            return false;
        }

        player.displayClientMessage(Component.literal(
                        "Farmhouse construction requested! A builder will begin work soon.")
                .withStyle(net.minecraft.ChatFormatting.GREEN), false);

        // TODO: Integrate with builder system to actually construct the farmhouse
        // For now, just notify
        System.out.println("Player " + player.getName().getString() +
                " requested farmhouse construction at " + location);

        return true;
    }

    /**
     * Purchase an existing empty farmhouse (no current owner)
     */
    public static boolean purchaseExistingFarmhouse(ServerPlayer player,
                                                    Building farmhouse,
                                                    ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);

        // Check if farmhouse has an owner
        boolean hasOwner = level.getEntitiesOfClass(
                TownspersonMob.class,
                new net.minecraft.world.phys.AABB(farmhouse.getShape().getOrigin()).inflate(100),
                npc -> npc.getAssignedBuildingId()
                        .map(id -> id.equals(farmhouse.getId()))
                        .orElse(false) && npc.getProfession() ==
                        Profession.FARMER
        ).size() > 0;

        if (hasOwner) {
            player.displayClientMessage(Component.literal(
                            "This farmhouse already has an owner. You must negotiate with them to purchase it."),
                    false);
            return false;
        }

        // Purchase at base price (no business value)
        CurrencyValue price = CurrencyValue.of(BASE_PURCHASE_PRICE);

        if (!CoinHelper.removeCoins(player, price)) {
            player.displayClientMessage(Component.literal(
                            "You need " + price + " to purchase this farmhouse."),
                    false);
            return false;
        }

        PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);

        UUID villageId = data.getAllVillages().stream()
                        .filter(v -> v.getBuildingIds().contains(farmhouse.getId()))
                        .map(Village::getId)
                        .findFirst().orElse(null);

        PlayerWorkplace.WorkplaceEntry ownerEntry = new PlayerWorkplace.WorkplaceEntry(
                farmhouse.getId(),
                villageId,
                PlayerProfession.FARMER,
                true,
                level.getGameTime(),
                level.getGameTime(),
                null
        );

        profData.setWorkplace(PlayerProfession.FARMER, ownerEntry);
        player.setData(ModData.PROFESSION_DATA, profData);

        player.displayClientMessage(Component.literal(
                        "You are now the owner of " + farmhouse.getName() + "!")
                .withStyle(net.minecraft.ChatFormatting.GOLD), false);

        return true;
    }
}