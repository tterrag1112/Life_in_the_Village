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
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.CombatRole;
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildData;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerGuildData;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerParty;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Guilds.Companies.Company;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;
import tterrag1112.life_in_the_village.Networking.CraftingOrderInteraction;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.*;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.TradeHandler;
import tterrag1112.life_in_the_village.Village.Economy.FarmBusinessLevel;

import java.util.Optional;
import java.util.UUID;

/**
 * Handles all player→NPC interactions, extracting the logic from
 * {@code TownspersonMob.mobInteract()}.
 *
 * <h3>Why extract this?</h3>
 * The old {@code mobInteract} was a 200+ line switch statement that mixed
 * profession routing, GUI opening, debug info, and greeting logic in one
 * method. Every new profession or interaction type required editing
 * TownspersonMob directly.
 *
 * <h3>Design</h3>
 * Each profession's interaction is a private static method. The public
 * {@link #handle} method does common checks (client-side guard, debug stick,
 * greeting prefix) and then dispatches to the appropriate handler.
 *
 * <h3>Greeting integration</h3>
 * Before any profession-specific interaction, the NPC speaks a contextual
 * one-liner via {@link NpcDialogue#getGreeting}. This is shown as a chat
 * message prefixed with the NPC's name in brackets. Professions that open
 * a GUI (merchant, guild worker) show the greeting before the screen opens.
 *
 * <h3>Usage</h3>
 * In {@code TownspersonMob.mobInteract}:
 * <pre>
 * {@code @Override}
 * public InteractionResult mobInteract(Player player, InteractionHand hand) {
 *     return NpcInteractionHandler.handle(this, player, hand);
 * }
 * </pre>
 */
public final class NpcInteractionHandler {

    private NpcInteractionHandler() {}

    // =========================================================================
    // Entry point
    // =========================================================================

    public static InteractionResult handle(TownspersonMob npc,
                                           Player player,
                                           InteractionHand hand) {
        if (npc.level().isClientSide()) return InteractionResult.SUCCESS;

        // ── Debug: stick shows info ──────────────────────────────────────────
        if (hand == InteractionHand.MAIN_HAND
                && player.getMainHandItem().is(Items.STICK)) {
            npc.showDebugInfo(player);
            return InteractionResult.SUCCESS;
        }

        if (!(npc.level() instanceof ServerLevel level)) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

        // ── Contextual greeting (shown before any GUI) ───────────────────────
        String greeting = NpcDialogue.getGreeting(npc, sp, level);
        sp.displayClientMessage(
                Component.literal("[" + npc.getNpcName() + "] ")
                        .withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal(greeting)
                                .withStyle(ChatFormatting.WHITE)),
                false);

        // ── Profession dispatch ──────────────────────────────────────────────
        return switch (npc.getProfession()) {
            case MERCHANT       -> handleMerchant(npc, sp, level);
            case GUARD          -> handleGuard(npc, sp, level);
            case INNKEEPER      -> handleInnkeeper(npc, sp, level);
            case GUILDWORKER    -> handleGuildWorker(npc, sp, level, hand);
            case VILLAGE_LEADER -> handleVillageLeader(npc, sp, level);
            case ADVENTURER     -> handleAdventurer(npc, sp, level, hand);
            case COMPANY_WORKER -> handleCompanyWorker(npc, sp, level);

            // Work-assignment professions: shift-interact for assignment
            case FARMER, BLACKSMITH, CARPENTER, MINER, STOCKPILE_KEEPER ->
                    handleWorkAssignable(npc, sp, level);

            default -> {
                // NPCs without special interactions just greet
                // (greeting was already shown above)
                yield InteractionResult.SUCCESS;
            }
        };
    }

    // =========================================================================
    // Profession handlers
    // =========================================================================

    private static InteractionResult handleMerchant(TownspersonMob npc,
                                                    ServerPlayer player,
                                                    ServerLevel level) {
        if (player.isShiftKeyDown()) {
            WorkplaceAssignmentManager.handleWorkRequest(player, npc, level);
        } else {
            var goal = npc.getGoal(
                    tterrag1112.life_in_the_village.Entities.Goals
                            .Profession.Merchant.MerchantGoal.class);
            if (goal != null && goal.isOpenForTrade()) {
                TradeHandler.openTradeScreen(player, npc);
            } else {
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
}