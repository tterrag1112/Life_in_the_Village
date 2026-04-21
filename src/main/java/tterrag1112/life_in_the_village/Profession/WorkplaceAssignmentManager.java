// src/main/java/tterrag1112/life_in_the_village/Profession/WorkplaceAssignmentManager.java
package tterrag1112.life_in_the_village.Profession;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Profession.Tasks.TaskPriority;
import tterrag1112.life_in_the_village.Profession.Tasks.WorkTaskType;
import tterrag1112.life_in_the_village.Village.Economy.CraftingOrder;
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

        List<PlayerWorkplace.WorkAssignment> tasks = new ArrayList<>(entry.activeTasks());
        // Pull legacy currentAssignment into the display list if the new list is empty
        if (tasks.isEmpty() && entry.currentAssignment() != null) {
            tasks.add(entry.currentAssignment());
        }

        // 1) Complete any finished task first
        for (PlayerWorkplace.WorkAssignment t : tasks) {
            if (t.isComplete()) {
                completeAssignment(player, npc, profession, profData, data, level, t);
                return;
            }
        }

        // 2) Drop any expired tasks and issue replacements
        long now = level.getGameTime();
        boolean removedExpired = false;
        List<PlayerWorkplace.WorkAssignment> stillActive = new ArrayList<>();
        for (PlayerWorkplace.WorkAssignment t : tasks) {
            if (t.isExpired(now)) removedExpired = true;
            else stillActive.add(t);
        }
        if (removedExpired) {
            profData.setWorkplace(profession, entry.withTasks(stillActive));
            player.setData(ModData.PROFESSION_DATA, profData);
            player.displayClientMessage(Component.literal(
                    "[" + npc.getNpcName() + "] You missed a deadline. "
                            + "I'll give you another task."), false);
            issueAssignment(player, npc, profession, profData, level);
            return;
        }

        // 3) If no active tasks, issue a new one
        if (stillActive.isEmpty()) {
            issueAssignment(player, npc, profession, profData, level);
            return;
        }

        // 4) Otherwise print the full active list, sorted by priority (already sorted)
        StringBuilder msg = new StringBuilder();
        msg.append("[").append(npc.getNpcName()).append("] Your tasks:");
        for (PlayerWorkplace.WorkAssignment t : stillActive) {
            msg.append("\n  [").append(t.priority().name()).append("] ")
               .append(t.taskType().displayName()).append(" — ")
               .append(t.description());
            if (t.targetCount() > 1) {
                msg.append("  (").append(t.currentCount()).append("/")
                   .append(t.targetCount()).append(")");
            }
        }
        player.displayClientMessage(Component.literal(msg.toString()), false);
    }



    // =========================================================================
    // Quota progress
    // =========================================================================

    /**
     * Called when a player deposits items into a building chest.
     * Advances every matching task across all of the player's active workplaces.
     *
     * <p>Matches any task whose {@link WorkTaskType#isItemDeliveryType()} is
     * true (GATHER, DELIVER, RESTOCK) and whose {@code targetItem} equals the
     * deposited itemId. Also matches legacy QUOTA-type assignments that don't
     * have a proper task type set, for backward compatibility with old saves.</p>
     */
    public static void onItemDelivered(ServerPlayer player,
                                       String itemId,
                                       int count,
                                       ServerLevel level) {
        PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);
        boolean anyChanged = false;

        for (var wp : profData.getAllWorkplaces().entrySet()) {
            PlayerProfession profession = wp.getKey();
            PlayerWorkplace.WorkplaceEntry entry = wp.getValue();

            // Iterate active tasks (new multi-task list)
            List<PlayerWorkplace.WorkAssignment> updated =
                    new ArrayList<>(entry.activeTasks());
            boolean thisChanged = false;
            for (int i = 0; i < updated.size(); i++) {
                PlayerWorkplace.WorkAssignment t = updated.get(i);
                if (t.isComplete()) continue;
                if (!t.hasTargetItem() || !t.targetItem().equals(itemId)) continue;
                if (!t.taskType().isItemDeliveryType()
                        && t.type() != PlayerWorkplace.AssignmentType.QUOTA) continue;

                int newCount = Math.min(t.currentCount() + count, t.targetCount());
                updated.set(i, t.withProgress(newCount));
                thisChanged = true;

                player.displayClientMessage(Component.literal(
                                t.taskType().displayName() + " progress: " + newCount
                                        + "/" + t.targetCount() + " "
                                        + itemId.replace("minecraft:", "").replace("_", " ")),
                        true);
            }
            if (thisChanged) {
                profData.setWorkplace(profession, entry.withTasks(updated));
                anyChanged = true;
            }

            // Legacy fallback — old saves that still store the task in
            // currentAssignment rather than activeTasks
            PlayerWorkplace.WorkAssignment legacy = entry.currentAssignment();
            if (legacy != null && !legacy.isComplete()
                    && legacy.type() == PlayerWorkplace.AssignmentType.QUOTA
                    && legacy.hasTargetItem()
                    && legacy.targetItem().equals(itemId)) {
                int newCount = Math.min(legacy.currentCount() + count, legacy.targetCount());
                profData.setWorkplace(profession, entry.withAssignment(legacy.withProgress(newCount)));
                anyChanged = true;
                player.displayClientMessage(Component.literal(
                                "Quota progress: " + newCount + "/" + legacy.targetCount()
                                        + " " + itemId.replace("minecraft:", "").replace("_", " ")),
                        true);
            }
        }
        if (anyChanged) player.setData(ModData.PROFESSION_DATA, profData);
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

    /**
     * Legacy single-task completion — finds the first completed task in the
     * active list (or the legacy currentAssignment) and pays it out. Kept so
     * existing call sites that don't specify which task compile.
     */
    public static void completeAssignment(ServerPlayer player,
                                          TownspersonMob npc,
                                          PlayerProfession profession,
                                          PlayerProfessionData profData,
                                          VillageSavedData data,
                                          ServerLevel level) {
        PlayerWorkplace.WorkplaceEntry entry = profData.getWorkplace(profession).orElse(null);
        if (entry == null) return;

        // Try the active list first
        for (PlayerWorkplace.WorkAssignment t : entry.activeTasks()) {
            if (t.isComplete()) {
                completeAssignment(player, npc, profession, profData, data, level, t);
                return;
            }
        }
        // Fall back to the legacy currentAssignment
        PlayerWorkplace.WorkAssignment legacy = entry.currentAssignment();
        if (legacy != null && legacy.isComplete()) {
            completeAssignment(player, npc, profession, profData, data, level, legacy);
        }
    }

    /**
     * Completes a specific task and pays out rewards. Removes the task from
     * the active list (or clears the legacy currentAssignment if that's where
     * it came from) and issues a replacement task automatically.
     */
    public static void completeAssignment(ServerPlayer player,
                                          TownspersonMob npc,
                                          PlayerProfession profession,
                                          PlayerProfessionData profData,
                                          VillageSavedData data,
                                          ServerLevel level,
                                          PlayerWorkplace.WorkAssignment completed) {
        PlayerWorkplace.WorkplaceEntry entry = profData.getWorkplace(profession).orElse(null);
        if (entry == null || completed == null || !completed.isComplete()) return;

        // Award XP
        ProfessionEvents.onJobPostingCompleted(
                player, profession, completed.xpReward(), entry.villageId());

        // Calculate reward with business bonus (farmer only)
        long baseReward  = completed.coinReward();
        long finalReward = baseReward;
        if (profession == PlayerProfession.FARMER) {
            Optional<FarmBusinessLevel> businessLevel =
                    data.getFarmBusinessLevel(entry.buildingId());
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
                                + reward + " + " + completed.xpReward() + " XP")
                .withStyle(net.minecraft.ChatFormatting.GREEN), false);

        // Remove the completed task — from activeTasks if present, else
        // clear the legacy currentAssignment
        PlayerWorkplace.WorkplaceEntry updated = entry;
        boolean inActiveList = entry.activeTasks().stream()
                .anyMatch(t -> t.issuedTick() == completed.issuedTick()
                        && t.description().equals(completed.description()));
        if (inActiveList) {
            updated = updated.withTaskRemoved(completed);
        }
        if (entry.currentAssignment() != null
                && entry.currentAssignment().issuedTick() == completed.issuedTick()) {
            updated = updated.withAssignment(null);
        }

        profData.setWorkplace(profession, updated);
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

    /**
     * Checks village-level demand (open crafting orders) and returns a typed,
     * HIGH-priority task if one matches the player's profession. When there
     * are active needs, this returns a concrete {@link WorkTaskType#CRAFT}
     * or {@link WorkTaskType#DELIVER} task that is actually completable by
     * {@code TaskCompletionEvents}. When nothing is pending it returns empty
     * and the caller falls back to the operational quota/busywork generator.
     *
     * <p>Priority tiers applied:</p>
     * <ul>
     *   <li>Open crafting order the profession can fulfil → CRAFT, HIGH</li>
     *   <li>No demand → caller's fallback (NORMAL quota or LOW busywork)</li>
     * </ul>
     */
    private static Optional<PlayerWorkplace.WorkAssignment> tryGenerateNeedBasedTask(
            PlayerProfession profession,
            PlayerProfessionData profData,
            ServerLevel level) {

        PlayerWorkplace.WorkplaceEntry entry = profData.getWorkplace(profession).orElse(null);
        if (entry == null) return Optional.empty();

        VillageSavedData data = VillageSavedData.get(level);
        List<CraftingOrder> orders = data.getOrdersForVillage(entry.villageId());
        if (orders.isEmpty()) return Optional.empty();

        // Find the most urgent open order this profession can produce
        for (CraftingOrder order : orders) {
            if (!order.isOpen()) continue;
            if (order.getRemainingCount() <= 0) continue;

            Item item = resolveItem(order.getItemId());
            if (item == null) continue;

            // Filter by profession — does a Blacksmith normally craft this item?
            if (!professionProducesItem(profession, new ItemStack(item))) continue;

            int remaining = order.getRemainingCount();
            long tick = level.getGameTime();
            int xp = profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING);

            String desc = "Craft and deliver " + remaining + "× "
                    + item.getDescription().getString()
                    + " (open village order)";

            return Optional.of(PlayerWorkplace.WorkAssignment.quota(
                    desc, order.getItemId(), remaining,
                    tick, tick + QUOTA_DEADLINE,
                    xp + 20,
                    order.getBronzeReward(),
                    WorkTaskType.CRAFT,
                    TaskPriority.HIGH,
                    entry.buildingId().toString()));
        }
        return Optional.empty();
    }

    /**
     * True if the profession's relevance checks recognise the given item
     * as something they normally craft. Delegates to the profession's own
     * {@code isRelevantCraft} method so the logic stays in one place.
     */
    private static boolean professionProducesItem(PlayerProfession profession,
                                                  ItemStack stack) {
        return profession.isRelevantCraft(stack);
    }

    private static Item resolveItem(String itemId) {
        try {
            var key = net.minecraft.resources.Identifier.parse(itemId);
            return net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(key).map(h -> h.value()).orElse(null);
        } catch (Exception e) { return null; }
    }

    public static void issueAssignment(ServerPlayer player,
                                       TownspersonMob npc,
                                       PlayerProfession profession,
                                       PlayerProfessionData profData,
                                       ServerLevel level) {
        int lvl = profData.getLevel(profession);

        // Step 1: Try a need-based HIGH-priority task (crafting orders).
        // Falls back to operational NORMAL task if nothing is pending.
        PlayerWorkplace.WorkAssignment assignment =
                tryGenerateNeedBasedTask(profession, profData, level)
                        .orElseGet(() -> generateAssignment(
                                profession, lvl, level.getGameTime()));

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
                                getQuotaCount(level) * 2L)
                                .withType(WorkTaskType.GATHER, TaskPriority.NORMAL);

                        case MIXED_CROPS -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.QUOTA,
                                "Deliver " + (getQuotaCount(level) / 2) + " each of carrots and potatoes",
                                "minecraft:carrot", // Primary tracked item
                                getQuotaCount(level) / 2, 0,
                                currentTick, currentTick + QUOTA_DEADLINE,
                                profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING) + 10,
                                getQuotaCount(level) * 3L)
                                .withType(WorkTaskType.GATHER, TaskPriority.NORMAL);

                        case ANIMAL_PRODUCTS -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.QUOTA,
                                "Collect and deliver " + (getQuotaCount(level) / 4) + " leather from animals",
                                "minecraft:leather",
                                getQuotaCount(level) / 4, 0,
                                currentTick, currentTick + QUOTA_DEADLINE,
                                profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING) + 20,
                                getQuotaCount(level) * 8L)
                                .withType(WorkTaskType.GATHER, TaskPriority.NORMAL);

                        // MARKET_SALES tracks coin volume, not item deposits.
                        // Kept as BUSYWORK for now — triggers through onWorkplaceSale.
                        case MARKET_SALES -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.QUOTA,
                                "Sell " + getQuotaCount(level) + " worth of farm goods at market",
                                "minecraft:emerald",
                                getQuotaCount(level), 0,
                                currentTick, currentTick + QUOTA_DEADLINE,
                                profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING) + 30,
                                getQuotaCount(level) * 5L)
                                .withType(WorkTaskType.BUSYWORK, TaskPriority.NORMAL);
                    };
                } else {
                    // Low-level task assignments
                    FarmerTaskType taskType = chooseTaskType(level);
                    yield switch (taskType) {
                        // HARVEST_PLOT is a proper HARVEST task — 10 mature crop
                        // breaks anywhere count toward it, detected by
                        // TaskCompletionEvents.onCropHarvest.
                        case HARVEST_PLOT -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.QUOTA,
                                "Harvest 10 mature crops from the farm plots",
                                "", 10, 0,
                                currentTick, currentTick + TASK_DEADLINE,
                                20, 4L)
                                .withType(WorkTaskType.HARVEST, TaskPriority.NORMAL);

                        // The following are filler tasks until dedicated event
                        // hooks are written (seed planting, bone-meal usage,
                        // animal feeding). BUSYWORK at LOW so real work preempts.
                        case PLANT_SEEDS -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.TASK,
                                "Plant 64 seeds in empty farmland",
                                "", 1, 0,
                                currentTick, currentTick + TASK_DEADLINE,
                                15, 3L)
                                .withType(WorkTaskType.BUSYWORK, TaskPriority.LOW);

                        case FEED_ANIMALS -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.TASK,
                                "Feed 8 animals in the pen to prepare for breeding",
                                "", 1, 0,
                                currentTick, currentTick + TASK_DEADLINE,
                                25, 5L)
                                .withType(WorkTaskType.BUSYWORK, TaskPriority.LOW);

                        case COLLECT_PRODUCTS -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.TASK,
                                "Collect eggs, milk, or wool from animals in the pen",
                                "", 1, 0,
                                currentTick, currentTick + TASK_DEADLINE,
                                20, 4L)
                                .withType(WorkTaskType.BUSYWORK, TaskPriority.LOW);

                        case FERTILIZE_CROPS -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.TASK,
                                "Apply bone meal to crops to accelerate growth",
                                "", 1, 0,
                                currentTick, currentTick + TASK_DEADLINE,
                                18, 3L)
                                .withType(WorkTaskType.BUSYWORK, TaskPriority.LOW);

                        case MARKET_DELIVERY -> new PlayerWorkplace.WorkAssignment(
                                PlayerWorkplace.AssignmentType.TASK,
                                "Take farm goods to the market and manage the stall for 2 hours",
                                "", 1, 0,
                                currentTick, currentTick + TASK_DEADLINE,
                                30, 6L)
                                .withType(WorkTaskType.BUSYWORK, TaskPriority.LOW);
                    };
                }
            }

            // ── BLACKSMITH ────────────────────────────────────────────────────
            // Quota = craft iron pickaxes (CRAFT type — detected via ItemCraftedEvent).
            // Simple tasks are BUSYWORK until dedicated event hooks exist.
            case BLACKSMITH -> isQuota
                    ? new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.QUOTA,
                    "Craft and deliver " + getQuotaCount(level) + " iron pickaxes",
                    "minecraft:iron_pickaxe",
                    getQuotaCount(level), 0,
                    currentTick, currentTick + QUOTA_DEADLINE,
                    profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING),
                    getQuotaCount(level) * 8L)
                    .withType(WorkTaskType.CRAFT, TaskPriority.NORMAL)
                    : new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.TASK,
                    getSimpleBlacksmithTask(level),
                    "", 1, 0,
                    currentTick, currentTick + TASK_DEADLINE,
                    30, 6L)
                    .withType(WorkTaskType.BUSYWORK, TaskPriority.LOW);

            // ── CARPENTER ────────────────────────────────────────────────────
            // Quota = craft oak planks (CRAFT type — detected via ItemCraftedEvent).
            case CARPENTER -> isQuota
                    ? new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.QUOTA,
                    "Craft and deliver " + getQuotaCount(level) + " oak planks",
                    "minecraft:oak_planks",
                    getQuotaCount(level), 0,
                    currentTick, currentTick + QUOTA_DEADLINE,
                    profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING),
                    getQuotaCount(level) * 3L)
                    .withType(WorkTaskType.CRAFT, TaskPriority.NORMAL)
                    : new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.TASK,
                    getSimpleCarpenterTask(level),
                    "", 1, 0,
                    currentTick, currentTick + TASK_DEADLINE,
                    30, 4L)
                    .withType(WorkTaskType.BUSYWORK, TaskPriority.LOW);

            // ── MINER ────────────────────────────────────────────────────────
            // Quota = deliver raw iron (GATHER type — detected via onItemDelivered).
            case MINER -> isQuota
                    ? new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.QUOTA,
                    "Mine and deliver " + getQuotaCount(level) + " raw iron",
                    "minecraft:raw_iron",
                    getQuotaCount(level), 0,
                    currentTick, currentTick + QUOTA_DEADLINE,
                    profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING),
                    getQuotaCount(level) * 4L)
                    .withType(WorkTaskType.GATHER, TaskPriority.NORMAL)
                    : new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.TASK,
                    getSimpleMinerTask(level),
                    "", 1, 0,
                    currentTick, currentTick + TASK_DEADLINE,
                    30, 5L)
                    .withType(WorkTaskType.BUSYWORK, TaskPriority.LOW);

            // ── MERCHANT ─────────────────────────────────────────────────────
            // Merchant quota tracks sale volume, which comes through
            // onWorkplaceSale rather than item deposits — so it's BUSYWORK
            // at NORMAL priority until a dedicated sale-based advancement
            // hook is written. Simple tasks remain BUSYWORK/LOW.
            case MERCHANT -> isQuota
                    ? new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.QUOTA,
                    "Sell " + getQuotaCount(level) + " items at the market",
                    "minecraft:paper",
                    getQuotaCount(level), 0,
                    currentTick, currentTick + QUOTA_DEADLINE,
                    profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING),
                    getQuotaCount(level) * 5L)
                    .withType(WorkTaskType.BUSYWORK, TaskPriority.NORMAL)
                    : new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.TASK,
                    getSimpleMerchantTask(level),
                    "", 1, 0,
                    currentTick, currentTick + TASK_DEADLINE,
                    30, 8L)
                    .withType(WorkTaskType.BUSYWORK, TaskPriority.LOW);

            // ── GUARD ─────────────────────────────────────────────────────────
            // Quota = deliver iron swords to the garrison (GATHER type —
            // detected via onItemDelivered to the guard tower's chest).
            // Simple tasks remain BUSYWORK/LOW until patrol waypoints /
            // bounty boards are wired up.
            case GUARD -> isQuota
                    ? new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.QUOTA,
                    "Equip the garrison — deliver " + getQuotaCount(level) + " iron swords",
                    "minecraft:iron_sword",
                    getQuotaCount(level), 0,
                    currentTick, currentTick + QUOTA_DEADLINE,
                    profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING),
                    getQuotaCount(level) * 10L)
                    .withType(WorkTaskType.GATHER, TaskPriority.NORMAL)
                    : new PlayerWorkplace.WorkAssignment(
                    PlayerWorkplace.AssignmentType.TASK,
                    getSimpleGuardTask(level),
                    "", 1, 0,
                    currentTick, currentTick + TASK_DEADLINE,
                    30, 7L)
                    .withType(WorkTaskType.BUSYWORK, TaskPriority.LOW);
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
    // =========================================================================
// NPC production notification
// =========================================================================

    /**
     * Called when an NPC at a building produces crafted items (e.g. carpenter
     * finishes a batch). For each online player assigned to that building:
     * <ul>
     *   <li>Advances any active QUOTA assignment whose target item matches</li>
     *   <li>Awards a small amount of passive profession XP</li>
     * </ul>
     * This is distinct from {@link #onWorkplaceSale} (which fires on a market
     * transaction) — production and sale are separate events. A carpenter
     * produces planks, then later sells them; both events reward the player.
     *
     * @param level      server level
     * @param buildingId UUID of the building where production occurred
     * @param itemId     registry ID of the produced item (e.g. "minecraft:oak_planks")
     * @param count      number of items produced in this batch
     */
    public static void onWorkplaceProduction(ServerLevel level,
                                             UUID buildingId,
                                             String itemId,
                                             int count) {
        if (count <= 0 || itemId == null || itemId.isEmpty()) return;

        // Passive XP scales with output but is smaller than a full sale reward
        int passiveXp = Math.max(1, count / 4);

        level.getServer().getPlayerList().getPlayers().forEach(player -> {
            PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);
            boolean dirty = false;

            for (Map.Entry<PlayerProfession, PlayerWorkplace.WorkplaceEntry> e
                    : profData.getAllWorkplaces().entrySet()) {

                PlayerWorkplace.WorkplaceEntry entry = e.getValue();
                if (!entry.buildingId().equals(buildingId)) continue;

                PlayerProfession profession = e.getKey();

                // Advance matching tasks in the active list — CRAFT/SMELT/
                // PROCESS/GATHER/DELIVER with this item
                List<PlayerWorkplace.WorkAssignment> updated =
                        new ArrayList<>(entry.activeTasks());
                boolean thisChanged = false;
                for (int i = 0; i < updated.size(); i++) {
                    PlayerWorkplace.WorkAssignment t = updated.get(i);
                    if (t.isComplete()) continue;
                    if (!t.hasTargetItem() || !t.targetItem().equals(itemId)) continue;
                    boolean matches = t.taskType().isProductionType()
                            || t.taskType().isItemDeliveryType()
                            || t.type() == PlayerWorkplace.AssignmentType.QUOTA;
                    if (!matches) continue;

                    int newCount = Math.min(t.currentCount() + count, t.targetCount());
                    updated.set(i, t.withProgress(newCount));
                    thisChanged = true;

                    player.displayClientMessage(Component.literal(
                            t.taskType().displayName() + " progress: "
                                    + newCount + "/" + t.targetCount() + " "
                                    + itemId.replace("minecraft:", "")
                                    .replace("_", " ")), true);
                }
                if (thisChanged) {
                    profData.setWorkplace(profession, entry.withTasks(updated));
                    dirty = true;
                }

                // Legacy fallback — update the single currentAssignment
                PlayerWorkplace.WorkAssignment legacy = entry.currentAssignment();
                if (legacy != null
                        && legacy.type() == PlayerWorkplace.AssignmentType.QUOTA
                        && legacy.hasTargetItem()
                        && legacy.targetItem().equals(itemId)
                        && !legacy.isComplete()) {
                    int newCount = Math.min(
                            legacy.currentCount() + count, legacy.targetCount());
                    profData.setWorkplace(profession,
                            entry.withAssignment(legacy.withProgress(newCount)));
                    dirty = true;
                }

                // Award passive XP regardless of assignment type
                UUID villageId = entry.villageId();
                ProfessionEvents.onJobPostingCompleted(
                        player, profession, passiveXp, villageId);
            }

            if (dirty) {
                player.setData(ModData.PROFESSION_DATA, profData);
            }
        });
    }
}