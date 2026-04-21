package tterrag1112.life_in_the_village.Entities;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.ChatFormatting;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Merchant.MerchantGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Merchant.WanderingTraderGoal;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.CombatRole;
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildData;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerGuildData;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerParty;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Guilds.Companies.Company;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;
import tterrag1112.life_in_the_village.Networking.CraftingOrderInteraction;
import tterrag1112.life_in_the_village.Entities.NpcProfileHub;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.*;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.TradeHandler;
import tterrag1112.life_in_the_village.Village.Economy.FarmBusinessLevel;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStallPlacer;

import java.util.Optional;
import java.util.UUID;

/**
 * Thin delegator for player→NPC right-click interactions.
 *
 * <p>All professions except {@code WANDERING_TRADER} now open the unified
 * NPC profile screen via {@link NpcProfileHub#open}. Individual actions
 * (trade, guild, assign work, etc.) are triggered from within the screen
 * and handled back here or in the Hub as needed.
 *
 * <p>The old per-profession private methods are retained and package-accessible
 * so that {@link NpcProfileHub} can invoke them when processing action packets.
 */
public final class NpcInteractionHandler {

    private NpcInteractionHandler() {}

    // =========================================================================
    // Entry point — thin delegator
    // =========================================================================

    public static InteractionResult handle(TownspersonMob npc,
                                           Player player,
                                           InteractionHand hand) {
        if (npc.level().isClientSide()) return InteractionResult.SUCCESS;

        // ── Debug: stick right-click ─────────────────────────────────────────
        // Normal click → full debug info (phase, blocking reason, XP, role, etc.)
        // Shift + click → list NPC's personal inventory contents
        if (hand == InteractionHand.MAIN_HAND
                && player.getMainHandItem().is(Items.STICK)) {
            if (player.isShiftKeyDown()) {
                npc.showInventoryInfo(player);
            } else {
                npc.showDebugInfo(player);
            }
            return InteractionResult.SUCCESS;
        }

        if (!(npc.level() instanceof ServerLevel level)) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

        // Wandering traders bypass the profile screen
        if (npc.getProfession() == tterrag1112.life_in_the_village.Profession.Profession.WANDERING_TRADER) {
            return handleWanderingTrader(npc, sp, level);
        }

        // All other professions → unified profile screen
        NpcProfileHub.open(npc, sp, level);
        return InteractionResult.SUCCESS;
    }

    // =========================================================================
    // Public helpers called by NpcProfileHub action dispatch
    // =========================================================================

    /**
     * Exposed for {@link NpcProfileHub} to call when the player presses the
     * "Rent Stall" action inside the profile screen.
     */
    public static void handleRentStallAction(TownspersonMob npc,
                                             ServerPlayer player,
                                             ServerLevel level) {
        tryRentStall(npc, player, level);
    }

    // =========================================================================
    // Profession handlers
    // =========================================================================

    private static InteractionResult handleMerchant(TownspersonMob npc,
                                                    ServerPlayer player,
                                                    ServerLevel level) {
        if (player.isShiftKeyDown()) {
            // Shift-click: attempt to rent a stall, or open work assignment
            if (tryRentStall(npc, player, level)) {
                return InteractionResult.SUCCESS;
            }
            WorkplaceAssignmentManager.handleWorkRequest(player, npc, level);
        } else {
            var goal = npc.getGoal(MerchantGoal.class);
            if (goal != null && goal.isOpenForTrade()) {
                TradeHandler.openTradeScreen(player, npc);
            } else {
                // Show stall availability hint alongside the "not open" message
                showStallHint(npc, player, level);
                player.displayClientMessage(
                        Component.literal("[" + npc.getNpcName()
                                        + "] I'm not open for trade right now. "
                                        + "Come back during business hours.")
                                .withStyle(ChatFormatting.GRAY),
                        false);
            }
        }
        return InteractionResult.SUCCESS;
    }
    /**
     * Shows how many stall slots are available and their cost.
     * Called on normal right-click when the merchant is not open for trade,
     * so the player always knows they can rent a stall even off-hours.
     */
    private static void showStallHint(TownspersonMob npc,
                                      ServerPlayer player,
                                      ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);

        Building market = npc.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType()
                        == tterrag1112.life_in_the_village.Village.Buildings.BuildingType.MARKET)
                .orElse(null);
        if (market == null) return;

        int totalSlots = MarketStallPlacer.findAnchorSlots(level, market).size();
        int occupied   = data.getStallsForMarket(market.getId()).stream()
                .filter(MarketStall::isActive).mapToInt(s -> 1).sum();
        int free = totalSlots - occupied;

        if (free <= 0) return; // no hint if market is full

        player.displayClientMessage(
                Component.literal("[" + npc.getNpcName()
                                + "] We have " + free + " stall"
                                + (free == 1 ? "" : "s") + " available. "
                                + "Rent: " + MarketStallPlacer.RENT_PER_DAY + " bronze/day, "
                                + "purchase: " + MarketStallPlacer.PURCHASE_PRICE + " bronze. "
                                + "Shift-click me to claim one.")
                        .withStyle(ChatFormatting.AQUA),
                false);
    }

    /**
     * Attempts to rent or purchase a market stall for the player.
     *
     * <ul>
     *   <li>If the player already has a stall here, tells them so.</li>
     *   <li>If the player holds Shift and has enough for purchase price, purchases.</li>
     *   <li>Otherwise rents for one week as a trial.</li>
     * </ul>
     *
     * @return true if anything was communicated (even a refusal), so the
     *         caller knows not to fall through to work-assignment logic.
     */
    private static boolean tryRentStall(TownspersonMob npc,
                                        ServerPlayer player,
                                        ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);

        Building market = npc.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType()
                        == tterrag1112.life_in_the_village.Village.Buildings.BuildingType.MARKET)
                .orElse(null);
        if (market == null) return false;

        // Check if player already has a stall in this market
        boolean alreadyHasStall = data.getStallsForMarket(market.getId()).stream()
                .anyMatch(s -> s.isActive()
                        && s.getOwnerUUID().equals(player.getUUID()));
        // Replace the early-return block for existing stall with:
        Optional<MarketStall> existingStall = data.getStallsForMarket(market.getId())
                .stream()
                .filter(s -> s.isActive() && s.getOwnerUUID().equals(player.getUUID()))
                .findFirst();

        if (existingStall.isPresent()) {
            MarketStall stall = existingStall.get();

            if (stall.isPurchased()) {
                player.displayClientMessage(
                        Component.literal("[" + npc.getNpcName()
                                        + "] You own stall #" + (stall.getSlotIndex() + 1)
                                        + " permanently.")
                                .withStyle(ChatFormatting.YELLOW),
                        false);
                return true;
            }

            // Offer renewal
            long ticksLeft = stall.getRentPaidUntilTick() - level.getGameTime();
            long daysLeft  = Math.max(0L, ticksLeft / 24000L);
            long renewCost = MarketStallPlacer.RENT_PER_DAY * 7L;

            long playerWealth = CoinHelper.getPlayerWealth(
                            player).toBronze();

            if (playerWealth < renewCost) {
                player.displayClientMessage(
                        Component.literal("[" + npc.getNpcName()
                                        + "] Your stall has " + daysLeft + " day(s) left. "
                                        + "You need " + renewCost + " bronze to renew for 7 days.")
                                .withStyle(ChatFormatting.YELLOW),
                        false);
                return true;
            }

            // Deduct and extend
            CoinHelper.playerPay(player, CurrencyValue.of(renewCost));

            npc.getAssignedVillageName()
                    .flatMap(data::getVillageByName)
                    .ifPresent(v -> v.depositToTreasury(renewCost));

            stall.setRentPaidUntilTick(
                    Math.max(stall.getRentPaidUntilTick(), level.getGameTime())
                            + 24000L * 7L);
            data.setDirty();

            player.displayClientMessage(
                    Component.literal("[" + npc.getNpcName()
                                    + "] Stall #" + (stall.getSlotIndex() + 1)
                                    + " renewed for 7 days. " + renewCost + " bronze paid.")
                            .withStyle(ChatFormatting.GREEN),
                    false);
            return true;
        }

        // Check free slots
        int totalSlots = MarketStallPlacer.findAnchorSlots(level, market).size();
        int occupied   = (int) data.getStallsForMarket(market.getId()).stream()
                .filter(MarketStall::isActive).count();
        if (occupied >= totalSlots) {
            player.displayClientMessage(
                    Component.literal("[" + npc.getNpcName()
                                    + "] I'm sorry, all our stalls are taken.")
                            .withStyle(ChatFormatting.RED),
                    false);
            return true;
        }

        // Determine cost and rental type
        long playerWealth = CoinHelper.getPlayerWealth(player).toBronze();

        boolean purchasing = playerWealth >= MarketStallPlacer.PURCHASE_PRICE;
        long cost = purchasing
                ? MarketStallPlacer.PURCHASE_PRICE
                : MarketStallPlacer.RENT_PER_DAY * 7L; // one week up front

        if (playerWealth < cost) {
            long shortfall = cost - playerWealth;
            player.displayClientMessage(
                    Component.literal("[" + npc.getNpcName()
                                    + "] You need " + shortfall
                                    + " more bronze for a stall.")
                            .withStyle(ChatFormatting.RED),
                    false);
            return true;
        }

        // Deduct from player
        CoinHelper.playerPay(player, CurrencyValue.of(cost));

        // Deposit into village treasury
        npc.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .ifPresent(v -> v.depositToTreasury(cost));

        long rentUntil = purchasing
                ? Long.MAX_VALUE
                : level.getGameTime() + 24000L * 7L;

        MarketStallPlacer.claimSlot(
                        level, market,
                        player.getUUID(),
                        MarketStall.OwnerType.PLAYER,
                        rentUntil, data)
                .ifPresent(stall -> {
                    data.addMarketStall(stall);
                    String type = purchasing ? "purchased" : "rented for 7 days";
                    player.displayClientMessage(
                            Component.literal("[" + npc.getNpcName()
                                            + "] Stall " + (stall.getSlotIndex() + 1)
                                            + " is yours! " + cost + " bronze paid ("
                                            + type + "). Stock it and stand behind "
                                            + "the counter to start selling.")
                                    .withStyle(ChatFormatting.GREEN),
                            false);
                });

        return true;
    }

    /** Builds a SimpleContainer snapshot of the player's inventory for CoinHelper. */
    private static net.minecraft.world.SimpleContainer buildPlayerContainer(
            ServerPlayer player) {
        var inv = player.getInventory();
        net.minecraft.world.SimpleContainer c =
                new net.minecraft.world.SimpleContainer(inv.getContainerSize());
        for (int i = 0; i < inv.getContainerSize(); i++) {
            c.setItem(i, inv.getItem(i));
        }
        return c;
    }

    private static InteractionResult handleGuard(TownspersonMob npc,
                                                 ServerPlayer player,
                                                 ServerLevel level) {
        WorkplaceAssignmentManager.handleWorkRequest(player, npc, level);
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult handleInnkeeper(TownspersonMob npc,
                                                     ServerPlayer player,
                                                     ServerLevel level) {
        npc.handleInnkeeperInteraction(player, level);
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult handleGuildWorker(TownspersonMob npc,
                                                       ServerPlayer player,
                                                       ServerLevel level,
                                                       InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        VillageSavedData vdata = VillageSavedData.get(level);
        Optional<UUID> guildIdOpt = npc.getAssignedVillageName()
                .flatMap(vdata::getVillageByName)
                .flatMap(village -> vdata.getGuildForVillage(village.getId()))
                .map(GuildData::guildId);

        if (guildIdOpt.isEmpty()) {
            player.displayClientMessage(
                    Component.literal("[" + npc.getNpcName()
                                    + "] This village has no guild hall yet.")
                            .withStyle(ChatFormatting.GRAY),
                    false);
        } else {
            UUID guildId = guildIdOpt.get();
            PlayerGuildData guildData =
                    PlayerGuildData.get(level);

            // Register player with the guild if not already a member
            if (!guildData.isRegistered(player.getUUID())) {
                guildData.registerPlayer(player.getUUID(),
                        player.getName().getString(), guildId);
                guildData.setDirty();
                player.displayClientMessage(
                        Component.literal("Welcome to the Adventurers Guild! "
                                        + "You are now registered as a Bronze adventurer.")
                                .withStyle(ChatFormatting.GOLD),
                        false);
            }

            // Open the guild GUI screen
            tterrag1112.life_in_the_village.Gui.GuildScreen.sendOpenPacket(
                    player, guildId, level, guildData, vdata,
                    PlayerPartySavedData.get(level));
        }
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult handleVillageLeader(TownspersonMob npc,
                                                         ServerPlayer player,
                                                         ServerLevel level) {
        if (player.isShiftKeyDown()) {
            // Shift-interact: show crafting orders
            //CraftingOrderInteraction.handleShiftInteract(player, npc, level);
            CraftingOrderInteraction.showOrderHint(player, npc, level);

        } else {
            // Normal: open village book + hint at orders

            // Open the village book
            npc.getAssignedVillageName().ifPresent(villageName -> {
                VillageSavedData data = VillageSavedData.get(level);
                data.getVillageByName(villageName).ifPresent(village ->
                        tterrag1112.life_in_the_village.Gui.VillageBookScreen
                                .sendOpenPacket(player, village.getId(),
                                        level, data));
            });
        }
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult handleAdventurer(TownspersonMob npc,
                                                      ServerPlayer player,
                                                      ServerLevel level,
                                                      InteractionHand hand) {
        if (npc.getCombatRole() == null || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        PlayerPartySavedData partyData = PlayerPartySavedData.get(level);
        Optional<PlayerParty> partyOpt = partyData.getPartyContaining(npc.getUUID());

        if (partyOpt.isPresent()) {
            PlayerParty party = partyOpt.get();
            if (party.getLeaderPlayerId().equals(player.getUUID())) {
                // Show party member status
                party.getMember(npc.getUUID()).ifPresent(m ->
                        player.displayClientMessage(
                                Component.literal(
                                                m.role().symbol + " " + m.role().getDisplayName()
                                                        + " | Lv." + m.level()
                                                        + " | " + m.kills() + " kills\n"
                                                        + "  " + m.role().description)
                                        .withStyle(ChatFormatting.AQUA),
                                false));
            } else {
                player.displayClientMessage(
                        Component.literal("[" + npc.getNpcName()
                                        + "] I'm with another party right now.")
                                .withStyle(ChatFormatting.GRAY),
                        false);
            }
        } else {
            player.displayClientMessage(
                    Component.literal("[" + npc.getNpcName()
                                    + "] I'm ready for adventure! "
                                    + "Use /party invite near me to recruit.")
                            .withStyle(ChatFormatting.GOLD),
                    false);
        }
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult handleCompanyWorker(TownspersonMob npc,
                                                         ServerPlayer player,
                                                         ServerLevel level) {
        CompanySavedData compData = CompanySavedData.get(level);
        Optional<Company> companyOpt = compData.getCompanyForWorker(npc.getUUID());

        companyOpt.ifPresentOrElse(
                company -> {
                    if (company.getOwnerPlayerId().equals(player.getUUID())) {
                        tterrag1112.life_in_the_village.Gui.CompanyWorkerScreen
                                .open(player, npc, company);
                    } else {
                        player.displayClientMessage(
                                Component.literal("[" + npc.getNpcName()
                                                + "] I work for " + company.getName() + ".")
                                        .withStyle(ChatFormatting.GRAY),
                                false);
                    }
                },
                () -> player.displayClientMessage(
                        Component.literal("[" + npc.getNpcName()
                                        + "] I'm not employed right now.")
                                .withStyle(ChatFormatting.GRAY),
                        false)
        );
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult handleWorkAssignable(TownspersonMob npc,
                                                          ServerPlayer player,
                                                          ServerLevel level) {
        if (player.isShiftKeyDown()) {
            if (npc.getProfession() == Profession.FARMER) {
                if (player instanceof ServerPlayer serverPlayer) {
                    joinAsFarmhand(serverPlayer, level, npc);
                    return InteractionResult.SUCCESS;
                }
            } else{
                WorkplaceAssignmentManager.handleWorkRequest(player, npc, level);
            }
        }
        // Normal interact just shows the greeting (already shown above)
        return InteractionResult.SUCCESS;
    }
    private static void joinAsFarmhand(ServerPlayer player, ServerLevel level, TownspersonMob npc) {
        VillageSavedData data = VillageSavedData.get(level);
        PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);

        // Get this farmer's farmhouse
        Building farmhouse = npc.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(null);

        if (farmhouse == null) {
            player.displayClientMessage(Component.literal(
                            "[" + npc.getNpcName() + "] I don't have a farmhouse to offer you work at."),
                    false);
            return;
        }

        // Get village ID
        UUID villageId = npc.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .map(tterrag1112.life_in_the_village.Village.Village::getId)
                .orElse(null);

        if (villageId == null) {
            player.displayClientMessage(Component.literal(
                            "[" + npc.getNpcName() + "] Something went wrong."),
                    false);
            return;
        }

        // Create workplace entry
        PlayerWorkplace.WorkplaceEntry entry = new PlayerWorkplace.WorkplaceEntry(
                farmhouse.getId(),
                villageId,
                PlayerProfession.FARMER,
                false,
                level.getGameTime(),
                level.getGameTime(),
                null  // No current assignment
        );

        profData.setWorkplace(PlayerProfession.FARMER, entry);
        player.setData(ModData.PROFESSION_DATA, profData);

        // Get business level for welcome message
        FarmBusinessLevel businessLevel = data.getOrCreateFarmBusinessLevel(farmhouse.getId());

        player.displayClientMessage(Component.literal(
                        "[" + npc.getNpcName() + "] Welcome to " + businessLevel.getBusinessLevelName()
                                + "! You're now a farmhand here. I'll give you tasks to complete.")
                .withStyle(net.minecraft.ChatFormatting.GREEN), false);

        // Issue first assignment
        WorkplaceAssignmentManager.issueAssignment(
                player, npc, PlayerProfession.FARMER, profData, level);
    }

    private static InteractionResult handleWanderingTrader(TownspersonMob npc,
                                                           ServerPlayer player,
                                                           ServerLevel level) {
        WanderingTraderGoal goal = npc.getGoal(WanderingTraderGoal.class);
        if (goal != null) {
            goal.openTradeScreen(player);
        } else {
            player.displayClientMessage(
                    Component.literal("[" + npc.getNpcName()
                            + "] My wares are not ready yet."),
                    false);
        }
        return InteractionResult.SUCCESS;
    }
}