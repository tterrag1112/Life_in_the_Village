// src/main/java/tterrag1112/life_in_the_village/Profession/WorkplaceAssignmentManager.java
package tterrag1112.life_in_the_village.Profession;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.FarmBusinessLevel;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

public class WorkplaceAssignmentManager {

    private static final long PAY_INTERVAL   = 24000L * 7;  // once per week
    private static final long TASK_DEADLINE  = 24000L * 2;  // 2 days
    private static final long QUOTA_DEADLINE = 24000L * 5;  // 5 days

    // Weekly pay by level (Apprentice → Grandmaster)
    public static final long[] WEEKLY_PAY = {
            8L, 16L, 32L, 64L, 128L
    };

    // =========================================================================
    // Workplace assignment
    // =========================================================================

    /**
     * Called when a player interacts with the NPC in charge of a building
     * and requests work.
     */
    public static void handleWorkRequest(ServerPlayer player,
                                         TownspersonMob npc,
                                         ServerLevel level) {
        VillageSavedData data     = VillageSavedData.get(level);
        PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);

        Building building = npc.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .orElse(null);
        if (building == null) {
            player.displayClientMessage(Component.literal(
                    "[" + npc.getNpcName() + "] I am not assigned to a building."), false);
            return;
        }

        PlayerProfession profession = professionForBuilding(building.getType());
        if (profession == null) {
            player.displayClientMessage(Component.literal(
                    "[" + npc.getNpcName() + "] I cannot offer you work here."), false);
            return;
        }

        // Already assigned to this exact building?
        if (profData.getWorkplace(profession)
                .map(e -> e.buildingId().equals(building.getId()))
                .orElse(false)) {
            handleExistingWorker(player, npc, profession, profData, data, level);
            return;
        }

        // Already assigned elsewhere for this profession?
        if (profData.hasWorkplace(profession)) {
            profData.getWorkplace(profession).ifPresent(e ->
                    data.getBuildingById(e.buildingId()).ifPresent(other ->
                            player.displayClientMessage(Component.literal(
                                            "[" + npc.getNpcName() + "] You already work at "
                                                    + other.getName() + " as a "
                                                    + profession.getDisplayName()
                                                    + ". Leave there first with /profession leave."),
                                    false)));
            return;
        }

        // Assign the player
        Village village = npc.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .orElse(null);
        if (village == null) return;

        boolean isOwner = data.isPlayerOwned(building.getId())
                && data.getPropertyForBuilding(building.getId())
                .map(p -> p.playerId().equals(player.getUUID()))
                .orElse(false);

        PlayerWorkplace.WorkplaceEntry entry = new PlayerWorkplace.WorkplaceEntry(
                building.getId(), village.getId(), profession, isOwner,
                level.getGameTime(), level.getGameTime(), null);

        profData.setWorkplace(profession, entry);
        player.setData(ModData.PROFESSION_DATA, profData);

        player.displayClientMessage(Component.literal(
                        "[" + npc.getNpcName() + "] Welcome to " + building.getName() + "! "
                                + "You are hired as " + profData.getLevelName(profession)
                                + " " + profession.getDisplayName()
                                + ".\nYou will be paid "
                                + CurrencyValue.of(WEEKLY_PAY[profData.getLevel(profession)])
                                + " per week.")
                .withStyle(net.minecraft.ChatFormatting.GREEN), false);

        issueAssignment(player, npc, profession, profData, level);
    }

    // =========================================================================
    // Existing worker interaction
    // =========================================================================

    private static void handleExistingWorker(ServerPlayer player,
                                             TownspersonMob npc,
                                             PlayerProfession profession,
                                             PlayerProfessionData profData,
                                             VillageSavedData data,
                                             ServerLevel level) {
        PlayerWorkplace.WorkplaceEntry entry = profData.getWorkplace(profession).orElse(null);
        if (entry == null) return;

        if (entry.currentAssignment() != null) {
            PlayerWorkplace.WorkAssignment a = entry.currentAssignment();

            if (a.isComplete()) {
                completeAssignment(player, npc, profession, profData, data, level);
            } else if (a.isExpired(level.getGameTime())) {
                player.displayClientMessage(Component.literal(
                        "[" + npc.getNpcName() + "] You missed your deadline. "
                                + "I'll give you another task."), false);
                issueAssignment(player, npc, profession, profData, level);
            } else {
                player.displayClientMessage(Component.literal(
                                "[" + npc.getNpcName() + "] Current task: " + a.description()
                                        + "\nProgress: " + a.currentCount() + "/" + a.targetCount()),
                        false);
            }
        } else {
            issueAssignment(player, npc, profession, profData, level);
        }
    }



    // =========================================================================
    // Quota progress
    // =========================================================================

    /**
     * Called when a player deposits items into a building chest.
     * Advances any matching quota assignment.
     */
    public static void onItemDelivered(ServerPlayer player,
                                       String itemId,
                                       int count,
                                       ServerLevel level) {
        PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);

        profData.getAllWorkplaces().forEach((profession, entry) -> {
            if (entry.currentAssignment() == null) return;
            PlayerWorkplace.WorkAssignment a = entry.currentAssignment();
            if (a.type() != PlayerWorkplace.AssignmentType.QUOTA) return;
            if (!a.targetItem().equals(itemId)) return;

            int newCount = Math.min(a.currentCount() + count, a.targetCount());
            profData.setWorkplace(profession, entry.withAssignment(a.withProgress(newCount)));

            player.displayClientMessage(Component.literal(
                            "Quota progress: " + newCount + "/" + a.targetCount()
                                    + " " + itemId.replace("minecraft:", "").replace("_", " ")),
                    true);
        });

        player.setData(ModData.PROFESSION_DATA, profData);
    }

    // =========================================================================
    // Weekly pay tick
    // =========================================================================

    public static void tickWeeklyPay(ServerLevel level, long currentTick) {
        level.getServer().getPlayerList().getPlayers().forEach(player -> {
            PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);
            boolean dirty = false;

            for (var entry : profData.getAllWorkplaces().entrySet()) {
                PlayerProfession profession = entry.getKey();
                PlayerWorkplace.WorkplaceEntry workplace = entry.getValue();

                if (currentTick - workplace.lastPayTick() < PAY_INTERVAL) continue;
                if (workplace.isOwner()) continue;

                int lvl = profData.getLevel(profession);
                long basePay = WEEKLY_PAY[Math.min(lvl, WEEKLY_PAY.length - 1)];

                // NEW: Apply business level bonus for farmers
                long finalPay = basePay;
                if (profession == PlayerProfession.FARMER) {
                    VillageSavedData data = VillageSavedData.get(level);
                    Optional<FarmBusinessLevel> businessLevel = data.getFarmBusinessLevel(workplace.buildingId());
                    if (businessLevel.isPresent()) {
                        finalPay = businessLevel.get().calculatePlayerWage(lvl);
                    }
                }

                CurrencyValue wage = CurrencyValue.of(finalPay);

                var container = buildTempContainer(player);
                CoinHelper.giveCoins(container, wage);
                syncContainer(player, container);

                player.displayClientMessage(Component.literal(
                                "Weekly wage received: " + wage + " for "
                                        + profession.getDisplayName() + " work.")
                        .withStyle(net.minecraft.ChatFormatting.GREEN), false);

                profData.setWorkplace(profession, workplace.withLastPayTick(currentTick));
                dirty = true;
            }

            if (dirty) player.setData(ModData.PROFESSION_DATA, profData);
        });
    }

    // Update assignment completion to include business bonus
    public static void completeAssignment(ServerPlayer player,
                                          TownspersonMob npc,
                                          PlayerProfession profession,
                                          PlayerProfessionData profData,
                                          VillageSavedData data,
                                          ServerLevel level) {
        PlayerWorkplace.WorkplaceEntry entry = profData.getWorkplace(profession).orElse(null);
        if (entry == null) return;

        PlayerWorkplace.WorkAssignment a = entry.currentAssignment();
        if (a == null || !a.isComplete()) return;

        // Award XP
        ProfessionEvents.onJobPostingCompleted(
                player, profession, a.xpReward(), entry.villageId());

        // Calculate reward with business bonus
        long baseReward = a.coinReward();
        long finalReward = baseReward;

        if (profession == PlayerProfession.FARMER) {
            Optional<FarmBusinessLevel> businessLevel = data.getFarmBusinessLevel(entry.buildingId());
            if (businessLevel.isPresent()) {
                finalReward = businessLevel.get().calculateTaskPayment(
                        baseReward, profData.getLevel(profession));
            }
        }

        CurrencyValue reward = CurrencyValue.of(finalReward);
        var container = buildTempContainer(player);
        CoinHelper.giveCoins(container, reward);
        syncContainer(player, container);

        player.displayClientMessage(Component.literal(
                        "[" + npc.getNpcName() + "] Well done! Here is your reward: "
                                + reward + " + " + a.xpReward() + " XP")
                .withStyle(net.minecraft.ChatFormatting.GREEN), false);

        profData.setWorkplace(profession, entry.withAssignment(null));
        player.setData(ModData.PROFESSION_DATA, profData);

        issueAssignment(player, npc, profession, profData, level);
    }

    // Update sale notification to record business metrics
    public static void onWorkplaceSale(ServerLevel level, UUID buildingId, int saleAmount) {
        // Update business level
        VillageSavedData data = VillageSavedData.get(level);
        FarmBusinessLevel businessLevel = data.getOrCreateFarmBusinessLevel(buildingId);
        businessLevel.recordSale(saleAmount, level);
        data.updateFarmBusinessLevel(businessLevel);

        // Give XP to assigned players
        level.getServer().getPlayerList().getPlayers().forEach(player -> {
            PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);
            profData.getAllWorkplaces().forEach((profession, entry) -> {
                if (!entry.buildingId().equals(buildingId)) return;
                int passiveXp = Math.max(1, saleAmount / 10);
                UUID villageId = entry.villageId();
                ProfessionEvents.onJobPostingCompleted(player, profession, passiveXp, villageId);
            });
        });
    }


    // =========================================================================
    // Issue assignment
    // =========================================================================

    public static void issueAssignment(ServerPlayer player,
                                       TownspersonMob npc,
                                       PlayerProfession profession,
                                       PlayerProfessionData profData,
                                       ServerLevel level) {
        int lvl = profData.getLevel(profession);
        PlayerWorkplace.WorkAssignment assignment =
                generateAssignment(profession, lvl, level.getGameTime());

        PlayerWorkplace.WorkplaceEntry entry = profData.getWorkplace(profession).orElse(null);
        if (entry == null) return;

        profData.setWorkplace(profession, entry.withAssignment(assignment));
        player.setData(ModData.PROFESSION_DATA, profData);

        player.displayClientMessage(Component.literal(
                        "[" + npc.getNpcName() + "] New task: " + assignment.description()
                                + (assignment.type() == PlayerWorkplace.AssignmentType.QUOTA
                                ? "\nQuota: " + assignment.targetCount() + " items" : "")
                                + "\nReward: " + CurrencyValue.of(assignment.coinReward())
                                + " + " + assignment.xpReward() + " XP"),
                false);
    }

    // =========================================================================
    // Assignment generation
    // =========================================================================

    private static PlayerWorkplace.WorkAssignment generateAssignment(
            PlayerProfession profession, int level, long currentTick) {

        boolean isQuota = level >= 2;

        return switch (profession) {

            // ── FARMER ────────────────────────────────────────────────────────
            case FARMER -> {
                if (isQuota) {
                    // High-level quota assignments vary by specialization
                    FarmerQuotaType quotaType = chooseQuotaType(level);
                    yield switch (quotaType) {
                        case CROP_HARVEST -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.QUOTA,
                                "Harvest and deliver " + getQuotaCount(level) + " wheat to farmhouse storage",
                                "minecraft:wheat",
                                getQuotaCount(level), 0,
                                currentTick, currentTick + QUOTA_DEADLINE,
                                profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING),
                                getQuotaCount(level) * 2L);

                        case MIXED_CROPS -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.QUOTA,
                                "Deliver " + (getQuotaCount(level) / 2) + " each of carrots and potatoes",
                                "minecraft:carrot", // Primary tracked item
                                getQuotaCount(level) / 2, 0,
                                currentTick, currentTick + QUOTA_DEADLINE,
                                profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING) + 10,
                                getQuotaCount(level) * 3L);

                        case ANIMAL_PRODUCTS -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.QUOTA,
                                "Collect and deliver " + (getQuotaCount(level) / 4) + " leather from animals",
                                "minecraft:leather",
                                getQuotaCount(level) / 4, 0,
                                currentTick, currentTick + QUOTA_DEADLINE,
                                profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING) + 20,
                                getQuotaCount(level) * 8L);

                        case MARKET_SALES -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.QUOTA,
                                "Sell " + getQuotaCount(level) + " worth of farm goods at market",
                                "minecraft:emerald", // Placeholder for coin tracking
                                getQuotaCount(level), 0,
                                currentTick, currentTick + QUOTA_DEADLINE,
                                profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING) + 30,
                                getQuotaCount(level) * 5L);
                    };
                } else {
                    // Low-level task assignments
                    FarmerTaskType taskType = chooseTaskType(level);
                    yield switch (taskType) {
                        case HARVEST_PLOT -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.TASK,
                                "Harvest all mature crops from the assigned farm plot",
                                null, 1, 0,
                                currentTick, currentTick + TASK_DEADLINE,
                                20, 4L);

                        case PLANT_SEEDS -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.TASK,
                                "Plant 64 seeds in empty farmland",
                                null, 1, 0,
                                currentTick, currentTick + TASK_DEADLINE,
                                15, 3L);

                        case FEED_ANIMALS -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.TASK,
                                "Feed 8 animals in the pen to prepare for breeding",
                                null, 1, 0,
                                currentTick, currentTick + TASK_DEADLINE,
                                25, 5L);

                        case COLLECT_PRODUCTS -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.TASK,
                                "Collect eggs, milk, or wool from animals in the pen",
                                null, 1, 0,
                                currentTick, currentTick + TASK_DEADLINE,
                                20, 4L);

                        case FERTILIZE_CROPS -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.TASK,
                                "Apply bone meal to crops to accelerate growth",
                                null, 1, 0,
                                currentTick, currentTick + TASK_DEADLINE,
                                18, 3L);

                        case MARKET_DELIVERY -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.TASK,
                                "Take farm goods to the market and manage the stall for 2 hours",
                                null, 1, 0,
                                currentTick, currentTick + TASK_DEADLINE,
                                30, 6L);
                    };
                }
            }

            // ── BLACKSMITH ────────────────────────────────────────────────────
            case BLACKSMITH -> isQuota
                    ? new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.QUOTA,
                    "Craft and deliver " + getQuotaCount(level) + " iron tools",
                    "minecraft:iron_pickaxe",
                    getQuotaCount(level), 0,
                    currentTick, currentTick + QUOTA_DEADLINE,
                    profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING),
                    getQuotaCount(level) * 8L)
                    : new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.TASK,
                    getSimpleBlacksmithTask(level),
                    null, 1, 0,
                    currentTick, currentTick + TASK_DEADLINE,
                    30, 6L);

            // ── CARPENTER ────────────────────────────────────────────────────
            case CARPENTER -> isQuota
                    ? new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.QUOTA,
                    "Craft and deliver " + getQuotaCount(level) + " oak planks",
                    "minecraft:oak_planks",
                    getQuotaCount(level), 0,
                    currentTick, currentTick + QUOTA_DEADLINE,
                    profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING),
                    getQuotaCount(level) * 3L)
                    : new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.TASK,
                    getSimpleCarpenterTask(level),
                    null, 1, 0,
                    currentTick, currentTick + TASK_DEADLINE,
                    30, 4L);

            // ── MINER ────────────────────────────────────────────────────────
            case MINER -> isQuota
                    ? new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.QUOTA,
                    "Mine and deliver " + getQuotaCount(level) + " iron ore",
                    "minecraft:raw_iron",
                    getQuotaCount(level), 0,
                    currentTick, currentTick + QUOTA_DEADLINE,
                    profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING),
                    getQuotaCount(level) * 4L)
                    : new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.TASK,
                    getSimpleMinerTask(level),
                    null, 1, 0,
                    currentTick, currentTick + TASK_DEADLINE,
                    30, 5L);

            // ── MERCHANT ─────────────────────────────────────────────────────
            // Merchants are assessed on how much they sell and how many goods
            // they move through the market. Low-level tasks are about stocking
            // and pricing; high-level quotas require selling specific volumes.
            case MERCHANT -> isQuota
                    ? new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.QUOTA,
                    "Sell " + getQuotaCount(level) + " items at the market",
                    "minecraft:paper",          // placeholder — any item sold counts
                    getQuotaCount(level), 0,
                    currentTick, currentTick + QUOTA_DEADLINE,
                    profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING),
                    getQuotaCount(level) * 5L)
                    : new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.TASK,
                    getSimpleMerchantTask(level),
                    null, 1, 0,
                    currentTick, currentTick + TASK_DEADLINE,
                    30, 8L);

            // ── GUARD ─────────────────────────────────────────────────────────
            // Guards are assessed on patrols and security readiness. Low-level
            // tasks are about inspections and equipment; high-level quotas
            // require delivering a stockpile of weapons or armour for the garrison.
            case GUARD -> isQuota
                    ? new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.QUOTA,
                    "Equip the garrison — deliver " + getQuotaCount(level) + " iron swords",
                    "minecraft:iron_sword",
                    getQuotaCount(level), 0,
                    currentTick, currentTick + QUOTA_DEADLINE,
                    profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING),
                    getQuotaCount(level) * 10L)
                    : new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.TASK,
                    getSimpleGuardTask(level),
                    null, 1, 0,
                    currentTick, currentTick + TASK_DEADLINE,
                    30, 7L);
        };
    }

    // =========================================================================
    // Quota count by level
    // =========================================================================

    private static int getQuotaCount(int level) {
        return switch (level) {
            case 2  -> 16;
            case 3  -> 32;
            case 4  -> 64;
            default ->  8;
        };
    }

    // =========================================================================
    // Simple task descriptions
    // =========================================================================

    private static String getSimpleFarmerTask(int level) {
        return switch (level) {
            case 0  -> "Till 10 soil blocks near the farm";
            case 1  -> "Water the crops and report back";
            default -> "Inspect the field for pests";
        };
    }

    private static String getSimpleBlacksmithTask(int level) {
        return switch (level) {
            case 0  -> "Sort the iron ingots in the chest";
            case 1  -> "Repair the tools in the workshop";
            default -> "Assess the quality of the stockpile";
        };
    }

    private static String getSimpleCarpenterTask(int level) {
        return switch (level) {
            case 0  -> "Clean up wood shavings in the shop";
            case 1  -> "Organise the lumber storage";
            default -> "Inspect the building structures";
        };
    }

    private static String getSimpleMinerTask(int level) {
        return switch (level) {
            case 0  -> "Clear the mine entrance of debris";
            case 1  -> "Shore up the mine supports";
            default -> "Survey a new tunnel direction";
        };
    }

    private static String getSimpleMerchantTask(int level) {
        return switch (level) {
            case 0  -> "Restock the market shelves with any available goods";
            case 1  -> "Check the market prices and report discrepancies";
            default -> "Negotiate a trade agreement with a visiting merchant";
        };
    }

    private static String getSimpleGuardTask(int level) {
        return switch (level) {
            case 0  -> "Walk the perimeter and report back";
            case 1  -> "Inspect the gatehouse and check the locks";
            default -> "Assess the village's defensive weak points";
        };
    }

    // =========================================================================
    // Building → profession mapping
    // =========================================================================

    /**
     * Maps a building type to the player profession that can be assigned there.
     * Returns null if the building type does not support player assignment.
     */
    public static PlayerProfession professionForBuilding(BuildingType type) {
        return switch (type) {
            case FARMHOUSE  -> PlayerProfession.FARMER;
            case BLACKSMITH -> PlayerProfession.BLACKSMITH;
            case CARPENTRY  -> PlayerProfession.CARPENTER;
            case MINE       -> PlayerProfession.MINER;
            case MARKET     -> PlayerProfession.MERCHANT;
            // Both barracks and guard tower are guard workplaces;
            // guard tower is the smaller early-game version
            case BARRACKS, GUARD_TOWER -> PlayerProfession.GUARD;
            default         -> null;
        };
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static net.minecraft.world.SimpleContainer buildTempContainer(
            ServerPlayer player) {
        var c = new net.minecraft.world.SimpleContainer(
                player.getInventory().getContainerSize());
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            c.setItem(i, player.getInventory().getItem(i).copy());
        }
        return c;
    }

    private static void syncContainer(ServerPlayer player,
                                      net.minecraft.world.SimpleContainer container) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            player.getInventory().setItem(i, container.getItem(i));
        }
        player.inventoryMenu.broadcastChanges();
    }
    private enum FarmerQuotaType {
        CROP_HARVEST,
        MIXED_CROPS,
        ANIMAL_PRODUCTS,
        MARKET_SALES
    }

    private enum FarmerTaskType {
        HARVEST_PLOT,
        PLANT_SEEDS,
        FEED_ANIMALS,
        COLLECT_PRODUCTS,
        FERTILIZE_CROPS,
        MARKET_DELIVERY
    }

    private static FarmerQuotaType chooseQuotaType(int level) {
        // Higher levels unlock more complex quotas
        if (level >= 4) {
            return FarmerQuotaType.values()[new Random().nextInt(FarmerQuotaType.values().length)];
        } else if (level >= 3) {
            // Level 3-4: no market sales yet
            FarmerQuotaType[] limited = {
                    FarmerQuotaType.CROP_HARVEST,
                    FarmerQuotaType.MIXED_CROPS,
                    FarmerQuotaType.ANIMAL_PRODUCTS
            };
            return limited[new Random().nextInt(limited.length)];
        } else {
            return FarmerQuotaType.CROP_HARVEST;
        }
    }

    private static FarmerTaskType chooseTaskType(int level) {
        // Level 1: only basic tasks
        if (level < 2) {
            FarmerTaskType[] basic = {
                    FarmerTaskType.HARVEST_PLOT,
                    FarmerTaskType.PLANT_SEEDS
            };
            return basic[new Random().nextInt(basic.length)];
        } else {
            // Level 2+: all tasks available
            return FarmerTaskType.values()[new Random().nextInt(FarmerTaskType.values().length)];
        }
    }
}