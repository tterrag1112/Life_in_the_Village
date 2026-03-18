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
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

public class WorkplaceAssignmentManager {

    private static final long PAY_INTERVAL   = 24000L * 7;
    private static final long TASK_DEADLINE  = 24000L * 2;
    private static final long QUOTA_DEADLINE = 24000L * 5;

    // Weekly pay by level
    private static final long[] WEEKLY_PAY = {
            8L,   // Apprentice
            16L,  // Journeyman
            32L,  // Expert
            64L,  // Master
            128L  // Grandmaster
    };

    // -------------------------------------------------------------------------
    // Assignment to workplace
    // -------------------------------------------------------------------------

    /**
     * Called when player interacts with the NPC in charge
     * of a building to request work.
     */
    public static void handleWorkRequest(
            ServerPlayer player,
            TownspersonMob npc,
            ServerLevel level) {

        VillageSavedData data = VillageSavedData.get(level);
        PlayerProfessionData profData = player.getData(
                ModData.PROFESSION_DATA);

        Building building = npc.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .orElse(null);
        if (building == null) {
            player.displayClientMessage(
                    Component.literal("[" + npc.getNpcName()
                            + "] I am not assigned to "
                            + "a building."), false);
            return;
        }

        // Determine profession from building type
        PlayerProfession profession =
                professionForBuilding(building.getType());
        if (profession == null) {
            player.displayClientMessage(
                    Component.literal("[" + npc.getNpcName()
                            + "] I cannot offer you work here."),
                    false);
            return;
        }

        // Check not already assigned here
        if (profData.getWorkplace(profession)
                .map(e -> e.buildingId().equals(
                        building.getId()))
                .orElse(false)) {
            handleExistingWorker(player, npc, profession,
                    profData, data, level);
            return;
        }

        // Check not assigned elsewhere for this profession
        if (profData.hasWorkplace(profession)) {
            profData.getWorkplace(profession).ifPresent(e ->
                    data.getBuildingById(e.buildingId())
                            .ifPresent(other ->
                                    player.displayClientMessage(
                                            Component.literal(
                                                    "["
                                                            + npc.getNpcName()
                                                            + "] You already "
                                                            + "work at "
                                                            + other.getName()
                                                            + " as a "
                                                            + profession
                                                            .getDisplayName()
                                                            + ". Leave there "
                                                            + "first with "
                                                            + "/profession leave."),
                                            false)));
            return;
        }

        // Assign player to workplace
        Village village = npc.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .orElse(null);
        if (village == null) return;

        boolean isOwner = data.isPlayerOwned(building.getId())
                && data.getPropertyForBuilding(building.getId())
                .map(p -> p.playerId().equals(
                        player.getUUID()))
                .orElse(false);

        PlayerWorkplace.WorkplaceEntry entry =
                new PlayerWorkplace.WorkplaceEntry(
                        building.getId(),
                        village.getId(),
                        profession,
                        isOwner,
                        level.getGameTime(),
                        level.getGameTime(),
                        null);

        profData.setWorkplace(profession, entry);
        player.setData(ModData.PROFESSION_DATA, profData);



        player.displayClientMessage(
                Component.literal("[" + npc.getNpcName()
                                + "] Welcome to "
                                + building.getName() + "! "
                                + "You are hired as "
                                + profData.getLevelName(profession)
                                + " " + profession.getDisplayName()
                                + ".\nYou will be paid "
                                + CurrencyValue.of(WEEKLY_PAY[
                                profData.getLevel(profession)])
                                + " per week.")
                        .withStyle(
                                net.minecraft.ChatFormatting.GREEN),
                false);

        // Issue first assignment
        issueAssignment(player, npc, profession,
                profData, level);
    }

    private static void handleExistingWorker(
            ServerPlayer player,
            TownspersonMob npc,
            PlayerProfession profession,
            PlayerProfessionData profData,
            VillageSavedData data,
            ServerLevel level) {

        PlayerWorkplace.WorkplaceEntry entry =
                profData.getWorkplace(profession)
                        .orElse(null);
        if (entry == null) return;

        // Check current assignment status
        if (entry.currentAssignment() != null) {
            PlayerWorkplace.WorkAssignment a =
                    entry.currentAssignment();

            if (a.isComplete()) {
                // Complete the assignment
                completeAssignment(player, npc, profession,
                        profData, data, level);
            } else if (a.isExpired(level.getGameTime())) {
                player.displayClientMessage(
                        Component.literal(
                                "[" + npc.getNpcName()
                                        + "] You missed your deadline. "
                                        + "I'll give you another task."),
                        false);
                issueAssignment(player, npc, profession,
                        profData, level);
            } else {
                // Show progress
                player.displayClientMessage(
                        Component.literal(
                                "[" + npc.getNpcName()
                                        + "] Current task: "
                                        + a.description() + "\n"
                                        + "Progress: "
                                        + a.currentCount()
                                        + "/" + a.targetCount()),
                        false);
            }
        } else {
            // No assignment — issue one
            issueAssignment(player, npc, profession,
                    profData, level);
        }
    }

    // -------------------------------------------------------------------------
    // Assignment generation
    // -------------------------------------------------------------------------

    public static void issueAssignment(
            ServerPlayer player,
            TownspersonMob npc,
            PlayerProfession profession,
            PlayerProfessionData profData,
            ServerLevel level) {

        int lvl = profData.getLevel(profession);
        PlayerWorkplace.WorkAssignment assignment =
                generateAssignment(profession, lvl,
                        level.getGameTime());

        PlayerWorkplace.WorkplaceEntry entry =
                profData.getWorkplace(profession)
                        .orElse(null);
        if (entry == null) return;

        profData.setWorkplace(profession,
                entry.withAssignment(assignment));
        player.setData(ModData.PROFESSION_DATA, profData);

        player.displayClientMessage(
                Component.literal("[" + npc.getNpcName()
                        + "] New task: "
                        + assignment.description()
                        + (assignment.type()
                        == PlayerWorkplace
                        .AssignmentType.QUOTA
                        ? "\nQuota: "
                        + assignment.targetCount()
                        + " items"
                        : "")
                        + "\nReward: "
                        + CurrencyValue.of(
                        assignment.coinReward())
                        + " + "
                        + assignment.xpReward() + " XP"),
                false);
    }

    private static PlayerWorkplace.WorkAssignment
    generateAssignment(PlayerProfession profession,
                       int level, long currentTick) {
        // Low levels get simple tasks, high levels get quotas
        boolean isQuota = level >= 2;

        return switch (profession) {
            case FARMER -> isQuota
                    ? new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.QUOTA,
                    "Harvest and deliver "
                            + getQuotaCount(level)
                            + " wheat to the stockpile",
                    "minecraft:wheat",
                    getQuotaCount(level), 0,
                    currentTick,
                    currentTick + QUOTA_DEADLINE,
                    profession.getXpReward(
                            PlayerProfession.XpSource
                                    .JOB_POSTING),
                    getQuotaCount(level) * 2L)
                    : new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.TASK,
                    getSimpleFarmerTask(level),
                    null, 1, 0,
                    currentTick,
                    currentTick + TASK_DEADLINE,
                    30, 4L);

            case BLACKSMITH -> isQuota
                    ? new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.QUOTA,
                    "Craft and deliver "
                            + getQuotaCount(level)
                            + " iron tools",
                    "minecraft:iron_pickaxe",
                    getQuotaCount(level), 0,
                    currentTick,
                    currentTick + QUOTA_DEADLINE,
                    profession.getXpReward(
                            PlayerProfession.XpSource
                                    .JOB_POSTING),
                    getQuotaCount(level) * 8L)
                    : new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.TASK,
                    getSimpleBlacksmithTask(level),
                    null, 1, 0,
                    currentTick,
                    currentTick + TASK_DEADLINE,
                    30, 6L);

            case CARPENTER -> isQuota
                    ? new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.QUOTA,
                    "Craft and deliver "
                            + getQuotaCount(level)
                            + " oak planks",
                    "minecraft:oak_planks",
                    getQuotaCount(level), 0,
                    currentTick,
                    currentTick + QUOTA_DEADLINE,
                    profession.getXpReward(
                            PlayerProfession.XpSource
                                    .JOB_POSTING),
                    getQuotaCount(level) * 3L)
                    : new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.TASK,
                    getSimpleCarpenterTask(level),
                    null, 1, 0,
                    currentTick,
                    currentTick + TASK_DEADLINE,
                    30, 4L);

            case MINER -> isQuota
                    ? new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.QUOTA,
                    "Mine and deliver "
                            + getQuotaCount(level)
                            + " iron ore",
                    "minecraft:raw_iron",
                    getQuotaCount(level), 0,
                    currentTick,
                    currentTick + QUOTA_DEADLINE,
                    profession.getXpReward(
                            PlayerProfession.XpSource
                                    .JOB_POSTING),
                    getQuotaCount(level) * 4L)
                    : new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.TASK,
                    getSimpleMinerTask(level),
                    null, 1, 0,
                    currentTick,
                    currentTick + TASK_DEADLINE,
                    30, 5L);
        };
    }

    private static int getQuotaCount(int level) {
        return switch (level) {
            case 2 -> 16;
            case 3 -> 32;
            case 4 -> 64;
            default -> 8;
        };
    }

    private static String getSimpleFarmerTask(int level) {
        return switch (level) {
            case 0 -> "Till 10 soil blocks near the farm";
            case 1 -> "Water the crops and report back";
            default -> "Inspect the field for pests";
        };
    }

    private static String getSimpleBlacksmithTask(
            int level) {
        return switch (level) {
            case 0 -> "Sort the iron ingots in the chest";
            case 1 -> "Repair the tools in the workshop";
            default -> "Assess the quality of the stockpile";
        };
    }

    private static String getSimpleCarpenterTask(int level) {
        return switch (level) {
            case 0 -> "Clean up wood shavings in the shop";
            case 1 -> "Organise the lumber storage";
            default -> "Inspect the building structures";
        };
    }

    private static String getSimpleMinerTask(int level) {
        return switch (level) {
            case 0 -> "Clear the mine entrance of debris";
            case 1 -> "Shore up the mine supports";
            default -> "Survey a new tunnel direction";
        };
    }

    // -------------------------------------------------------------------------
    // Assignment completion
    // -------------------------------------------------------------------------

    public static void completeAssignment(
            ServerPlayer player,
            TownspersonMob npc,
            PlayerProfession profession,
            PlayerProfessionData profData,
            VillageSavedData data,
            ServerLevel level) {

        PlayerWorkplace.WorkplaceEntry entry =
                profData.getWorkplace(profession)
                        .orElse(null);
        if (entry == null) return;

        PlayerWorkplace.WorkAssignment a =
                entry.currentAssignment();
        if (a == null || !a.isComplete()) return;

        // Award XP and coins
        ProfessionEvents.onJobPostingCompleted(
                player, profession, a.xpReward());

        CurrencyValue reward = CurrencyValue.of(
                a.coinReward());
        var container = buildTempContainer(player);
        CoinHelper.giveCoins(container, reward);
        syncContainer(player, container);

        player.displayClientMessage(
                Component.literal("[" + npc.getNpcName()
                                + "] Well done! Here is your reward: "
                                + reward + " + "
                                + a.xpReward() + " XP")
                        .withStyle(
                                net.minecraft.ChatFormatting.GREEN),
                false);

        // Clear assignment and issue a new one
        profData.setWorkplace(profession,
                entry.withAssignment(null));
        player.setData(ModData.PROFESSION_DATA, profData);

        issueAssignment(player, npc, profession,
                profData, level);
    }

    // -------------------------------------------------------------------------
    // Quota progress — call when player deposits items
    // -------------------------------------------------------------------------

    public static void onItemDelivered(
            ServerPlayer player,
            String itemId,
            int count,
            ServerLevel level) {

        PlayerProfessionData profData = player.getData(
                ModData.PROFESSION_DATA);

        profData.getAllWorkplaces().forEach(
                (profession, entry) -> {
                    if (entry.currentAssignment() == null) return;
                    PlayerWorkplace.WorkAssignment a =
                            entry.currentAssignment();
                    if (a.type() != PlayerWorkplace
                            .AssignmentType.QUOTA) return;
                    if (!a.targetItem().equals(itemId)) return;

                    int newCount = Math.min(
                            a.currentCount() + count,
                            a.targetCount());
                    profData.setWorkplace(profession,
                            entry.withAssignment(
                                    a.withProgress(newCount)));

                    player.displayClientMessage(
                            Component.literal("Quota progress: "
                                    + newCount + "/"
                                    + a.targetCount()
                                    + " " + a.targetItem()
                                    .replace("minecraft:", "")
                                    .replace("_", " ")),
                            true);
                });

        player.setData(ModData.PROFESSION_DATA, profData);
    }

    // -------------------------------------------------------------------------
    // Weekly pay tick
    // -------------------------------------------------------------------------

    public static void tickWeeklyPay(ServerLevel level,
                                     long currentTick) {
        level.getServer().getPlayerList()
                .getPlayers()
                .forEach(player -> {
                    PlayerProfessionData profData = player.getData(
                            ModData.PROFESSION_DATA);
                    boolean dirty = false;

                    for (var entry : profData.getAllWorkplaces()
                            .entrySet()) {
                        PlayerProfession profession = entry.getKey();
                        PlayerWorkplace.WorkplaceEntry workplace =
                                entry.getValue();

                        if (currentTick - workplace.lastPayTick()
                                < PAY_INTERVAL) continue;
                        if (workplace.isOwner()) continue;

                        // Pay the player
                        int lvl = profData.getLevel(profession);
                        long pay = WEEKLY_PAY[Math.min(lvl,
                                WEEKLY_PAY.length - 1)];
                        CurrencyValue wage = CurrencyValue.of(pay);

                        var container = buildTempContainer(player);
                        CoinHelper.giveCoins(container, wage);
                        syncContainer(player, container);

                        player.displayClientMessage(
                                Component.literal(
                                                "Weekly wage received: "
                                                        + wage + " for "
                                                        + profession.getDisplayName()
                                                        + " work.")
                                        .withStyle(
                                                net.minecraft
                                                        .ChatFormatting
                                                        .GREEN),
                                false);

                        profData.setWorkplace(profession,
                                workplace.withLastPayTick(
                                        currentTick));
                        dirty = true;
                    }

                    if (dirty) {
                        player.setData(ModData.PROFESSION_DATA,
                                profData);
                    }
                });
    }

    // -------------------------------------------------------------------------
    // Passive XP from workplace sales
    // -------------------------------------------------------------------------

    public static void onWorkplaceSale(ServerLevel level,
                                       UUID buildingId,
                                       int saleAmount) {
        // Give small XP to any player assigned to this building
        level.getServer().getPlayerList()
                .getPlayers()
                .forEach(player -> {
                    PlayerProfessionData profData = player.getData(
                            ModData.PROFESSION_DATA);

                    profData.getAllWorkplaces().forEach(
                            (profession, entry) -> {
                                if (!entry.buildingId().equals(buildingId))
                                    return;
                                // Small passive XP per sale
                                int passiveXp = Math.max(1,
                                        saleAmount / 10);
                                ProfessionEvents.onJobPostingCompleted(
                                        player, profession, passiveXp);
                            });
                });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public static PlayerProfession professionForBuilding(
            BuildingType type) {
        return switch (type) {
            case FARMHOUSE -> PlayerProfession.FARMER;
            case BLACKSMITH ->
                    PlayerProfession.BLACKSMITH;
            case CARPENTRY ->
                    PlayerProfession.CARPENTER;
            case MINE -> PlayerProfession.MINER;
            default -> null;
        };
    }

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