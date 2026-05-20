package tterrag1112.life_in_the_village.Entities;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Components.ModDataComponents;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Merchant.WanderingTraderGoal;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Items.JobContractTerms;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Items.StallLeaseTerms;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.BusinessFront.BuildingPresenceTracker;
import tterrag1112.life_in_the_village.Npc.BusinessFront.BuildingTypeFlags;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerbRegistry;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStallPlacer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Player→NPC right-click dispatcher.
 *
 * <h3>Dispatch priority (Track 4 redesign)</h3>
 * Matched in this order; first match wins. The order is significant —
 * gift primer dominates by design (Q7 / 3.3) so players can intentionally
 * over-ride any item route by priming first.
 * <ol>
 *   <li><b>Debug stick</b> — vanilla {@code Items.STICK}: plain → debug
 *       info chat dump, sneak → inventory dump. Dev affordance.</li>
 *   <li><b>Gift primer active</b> — held item is consumed as a gift even
 *       if it would otherwise route somewhere else. Empty hand cancels
 *       the primer and falls through.</li>
 *   <li><b>WRITTEN_LETTER held</b> → LetterDelivery handoff.</li>
 *   <li><b>JOB_CONTRACT held</b> → routed per
 *       {@link JobContractTerms} (hire / apprenticeship / commission).</li>
 *   <li><b>STALL_LEASE held</b> → redeem at a market.</li>
 *   <li><b>Source-book held</b> + SCRIBE → book copy commission stub.</li>
 *   <li><b>Healer-donation item held</b> + HEALER → donation stub.</li>
 *   <li><b>Coin/coin-pouch held</b> + PRIEST → offering stub.</li>
 *   <li><b>WANDERING_TRADER</b> (plain) → trade screen.</li>
 *   <li><b>BUILDER</b> (plain) → repaint screen; sneak falls through.</li>
 *   <li><b>VILLAGE_LEADER / KINGDOM_RULER</b> (plain, empty hand) →
 *       VillageBookScreen / KingdomBookScreen direct; sneak → profile
 *       (Q3).</li>
 *   <li><b>Inside business-front + plain</b> → BusinessFrontScreen.</li>
 *   <li><b>Default</b> → {@link NpcProfileHub#open}.</li>
 * </ol>
 *
 * <p>Per-profession legacy methods (merchant / guildworker / guard / etc.)
 * are gone — they were superseded by either physical item routes
 * (JOB_CONTRACT for hiring) or contextual business-front routes.</p>
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
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!(npc.level() instanceof ServerLevel level)) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

        ItemStack held = player.getMainHandItem();

        // 1. Debug stick (vanilla) — dev affordance.
        if (held.is(Items.STICK)) {
            if (player.isShiftKeyDown()) npc.showInventoryInfo(player);
            else npc.showDebugInfo(player);
            return InteractionResult.SUCCESS;
        }

        // 2. Gift primer wins over every item route. Empty-hand cancels.
        if (GiftPrimer.isPrimed(sp, npc)) {
            if (held.isEmpty()) {
                GiftPrimer.clear(sp.getUUID(), npc.getUUID());
                sp.displayClientMessage(Component.literal(
                                "[" + npc.getNpcName() + "] Never mind, then.")
                        .withStyle(ChatFormatting.GRAY), true);
                // Fall through to default profile.
            } else {
                applyGift(npc, sp, level, held);
                return InteractionResult.SUCCESS;
            }
        }

        // 3. WRITTEN_LETTER handoff.
        if (held.is(ModItems.WRITTEN_LETTER.get())) {
            return tterrag1112.life_in_the_village.Npc.Letters.LetterDelivery
                    .handleHandoff(npc, sp, level, held);
        }

        // 4. JOB_CONTRACT — route by terms.
        if (held.is(ModItems.JOB_CONTRACT.get())) {
            return handleJobContract(npc, sp, level, held);
        }

        // 5. STALL_LEASE redemption.
        if (held.is(ModItems.STALL_LEASE.get())) {
            return handleStallLease(npc, sp, level, held);
        }

        // 6. Book held + SCRIBE → book-copy commission.
        if (held.is(Items.WRITTEN_BOOK) && npc.getProfession() == Profession.SCRIBE) {
            return handleBookCopyCommission(npc, sp, level, held);
        }

        // 7. Healer donation (golden_carrot / glistering_melon_slice — proxy
        //    for the planned lit:healer_donation tag).
        if (npc.getProfession() == Profession.HEALER && isHealerDonation(held)) {
            return handleHealerDonation(npc, sp, level, held);
        }

        // 8. Coin offering + PRIEST.
        if (npc.getProfession() == Profession.PRIEST && CoinHelper.isCoin(held)) {
            return handlePriestOffering(npc, sp, level, held);
        }

        // 9. Wandering trader trades immediately.
        if (npc.getProfession() == Profession.WANDERING_TRADER) {
            WanderingTraderGoal g = npc.getGoal(WanderingTraderGoal.class);
            if (g != null) g.openTradeScreen(sp);
            return InteractionResult.SUCCESS;
        }

        // 10. Builder repaint (plain only). Sneak falls through to profile.
        if (npc.getProfession() == Profession.BUILDER && !player.isShiftKeyDown()) {
            return tterrag1112.life_in_the_village.Village.Decoration.Variants
                    .RepaintRequestDispatch.handle(npc, sp, level);
        }

        // 11. Village leader / kingdom ruler (plain, empty hand) → office screen.
        Profession prof = npc.getProfession();
        if (held.isEmpty() && !player.isShiftKeyDown()
                && (prof == Profession.VILLAGE_LEADER || prof == Profession.KINGDOM_RULER)) {
            return handleLeaderDirect(npc, sp, level);
        }

        // 12. Business-front routing.
        InteractionResult businessRouted = tryRouteBusinessFront(npc, sp, level);
        if (businessRouted != null) return businessRouted;

        // 13. Default — informative profile.
        NpcProfileHub.open(npc, sp, level);
        return InteractionResult.SUCCESS;
    }

    // =========================================================================
    // Gift primer effect
    // =========================================================================

    private static void applyGift(TownspersonMob npc, ServerPlayer player,
                                  ServerLevel level, ItemStack held) {
        // Reuse the GiveGiftVerb's effect for consistency.
        var verb = PlayerVerbRegistry.get("give_gift").orElse(null);
        if (verb != null) {
            var ctx = PlayerVerb.context(player, npc, level);
            verb.invoke(ctx);
        } else {
            // Verb missing — minimal fallback: consume + small rel bump.
            held.shrink(1);
            npc.adjustRelationship(player.getUUID(), 3);
            player.displayClientMessage(Component.literal(
                            "[" + npc.getNpcName() + "] Thank you.")
                    .withStyle(ChatFormatting.GREEN), false);
        }
        GiftPrimer.clear(player.getUUID(), npc.getUUID());
    }

    // =========================================================================
    // JOB_CONTRACT routing
    // =========================================================================

    private static InteractionResult handleJobContract(TownspersonMob npc,
                                                       ServerPlayer player,
                                                       ServerLevel level,
                                                       ItemStack held) {
        JobContractTerms terms = held.get(ModDataComponents.JOB_CONTRACT_TERMS.get());
        if (terms == null) {
            player.displayClientMessage(Component.literal(
                            "[" + npc.getNpcName() + "] This contract is blank.")
                    .withStyle(ChatFormatting.GRAY), false);
            return InteractionResult.SUCCESS;
        }
        if (npc.getProfession() != terms.profession()) {
            player.displayClientMessage(Component.literal(
                            "[" + npc.getNpcName() + "] This contract isn't for my profession.")
                    .withStyle(ChatFormatting.GRAY), false);
            return InteractionResult.SUCCESS;
        }

        // Apprenticeship paths
        if (terms.isApprenticeship()) {
            UUID master = terms.masterUuid().orElse(null);
            if (master != null && master.equals(player.getUUID())) {
                // Player is the master; NPC is the apprentice candidate.
                var verb = PlayerVerbRegistry.get("take_apprentice").orElse(null);
                if (verb != null) verb.invoke(PlayerVerb.context(player, npc, level));
            } else {
                // NPC is the master; player is the apprentice candidate.
                var verb = PlayerVerbRegistry.get("apprentice_under_me").orElse(null);
                if (verb != null) verb.invoke(PlayerVerb.context(player, npc, level));
            }
            held.shrink(1);
            return InteractionResult.SUCCESS;
        }

        // Crafting commission
        if (terms.isCommission()) {
            player.displayClientMessage(Component.literal(
                            "[" + npc.getNpcName() + "] I'll get started on "
                                    + terms.targetItemId().orElse("the order")
                                    + ". Come back when it's ready.")
                    .withStyle(ChatFormatting.GREEN), false);
            // TODO Track 5: enqueue an actual production target on the NPC's
            // workplace. v1 acknowledges + consumes the contract.
            held.shrink(1);
            return InteractionResult.SUCCESS;
        }

        // Plain hire — route through the workplace assignment flow.
        WorkplaceAssignmentManager.handleWorkRequest(player, npc, level);
        // Don't consume on hire — the player keeps the contract as proof
        // until they /profession leave. Future polish can change this.
        return InteractionResult.SUCCESS;
    }

    // =========================================================================
    // STALL_LEASE redemption
    // =========================================================================

    private static InteractionResult handleStallLease(TownspersonMob npc,
                                                      ServerPlayer player,
                                                      ServerLevel level,
                                                      ItemStack held) {
        StallLeaseTerms terms = held.get(ModDataComponents.STALL_LEASE_TERMS.get());
        if (terms == null) {
            player.displayClientMessage(Component.literal(
                            "[" + npc.getNpcName() + "] This lease has no terms.")
                    .withStyle(ChatFormatting.RED), false);
            return InteractionResult.SUCCESS;
        }
        if (!terms.ownerUuid().equals(player.getUUID())) {
            player.displayClientMessage(Component.literal(
                            "[" + npc.getNpcName() + "] This lease belongs to someone else.")
                    .withStyle(ChatFormatting.RED), false);
            return InteractionResult.SUCCESS;
        }

        // Find the market this NPC works at (must be a MERCHANT at a MARKET).
        VillageSavedData data = VillageSavedData.get(level);
        Building market = npc.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType() == BuildingType.MARKET)
                .orElse(null);
        if (market == null) {
            player.displayClientMessage(Component.literal(
                            "[" + npc.getNpcName() + "] I'm not a market merchant.")
                    .withStyle(ChatFormatting.GRAY), false);
            return InteractionResult.SUCCESS;
        }

        // Bound to a different market?
        if (terms.marketBuildingId().isPresent()
                && !terms.marketBuildingId().get().equals(market.getId())) {
            player.displayClientMessage(Component.literal(
                            "[" + npc.getNpcName() + "] This lease is for a different market.")
                    .withStyle(ChatFormatting.RED), false);
            return InteractionResult.SUCCESS;
        }

        long rentUntil = terms.isPurchase()
                ? Long.MAX_VALUE
                : level.getGameTime() + terms.durationTicks();

        Optional<MarketStall> claimed = MarketStallPlacer.claimSlot(
                level, market,
                player.getUUID(),
                MarketStall.OwnerType.PLAYER,
                rentUntil, data);

        if (claimed.isEmpty()) {
            player.displayClientMessage(Component.literal(
                            "[" + npc.getNpcName() + "] All stalls are taken.")
                    .withStyle(ChatFormatting.RED), false);
            return InteractionResult.SUCCESS;
        }
        data.addMarketStall(claimed.get());
        held.shrink(1);
        player.displayClientMessage(Component.literal(
                        "[" + npc.getNpcName() + "] Stall "
                                + (claimed.get().getSlotIndex() + 1) + " is yours.")
                .withStyle(ChatFormatting.GREEN), false);
        return InteractionResult.SUCCESS;
    }

    // =========================================================================
    // Held-item profession routes (book / herbs / coin)
    // =========================================================================

    private static InteractionResult handleBookCopyCommission(TownspersonMob npc,
                                                              ServerPlayer player,
                                                              ServerLevel level,
                                                              ItemStack held) {
        // Stub: delegate to commission_book_copy verb. Real composer GUI
        // is a polish follow-up.
        var verb = PlayerVerbRegistry.get("commission_book_copy").orElse(null);
        if (verb != null) verb.invoke(PlayerVerb.context(player, npc, level));
        return InteractionResult.SUCCESS;
    }

    private static boolean isHealerDonation(ItemStack stack) {
        // TODO Track 5: replace with a proper lit:healer_donation item tag.
        return stack.is(Items.GOLDEN_CARROT)
                || stack.is(Items.GLISTERING_MELON_SLICE)
                || stack.is(Items.SUSPICIOUS_STEW)
                || stack.is(Items.HONEY_BOTTLE);
    }

    private static InteractionResult handleHealerDonation(TownspersonMob npc,
                                                          ServerPlayer player,
                                                          ServerLevel level,
                                                          ItemStack held) {
        var verb = PlayerVerbRegistry.get("donate_herbs").orElse(null);
        if (verb != null) verb.invoke(PlayerVerb.context(player, npc, level));
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult handlePriestOffering(TownspersonMob npc,
                                                          ServerPlayer player,
                                                          ServerLevel level,
                                                          ItemStack held) {
        var verb = PlayerVerbRegistry.get("make_offering").orElse(null);
        if (verb != null) verb.invoke(PlayerVerb.context(player, npc, level));
        return InteractionResult.SUCCESS;
    }

    // =========================================================================
    // Village leader / kingdom ruler direct path
    // =========================================================================

    private static InteractionResult handleLeaderDirect(TownspersonMob npc,
                                                        ServerPlayer player,
                                                        ServerLevel level) {
        if (npc.getProfession() == Profession.VILLAGE_LEADER) {
            VillageSavedData data = VillageSavedData.get(level);
            npc.getAssignedVillageName()
                    .flatMap(data::getVillageByName)
                    .ifPresent(v -> tterrag1112.life_in_the_village.Gui.VillageBookScreen
                            .sendOpenPacket(player, v.getId(), level, data));
            return InteractionResult.SUCCESS;
        }
        // KINGDOM_RULER — open kingdom book if available; fall back to profile.
        // Track 5 may wire a richer kingdom screen.
        NpcProfileHub.open(npc, player, level);
        return InteractionResult.SUCCESS;
    }

    // =========================================================================
    // Business-front routing (preserved from earlier track, slightly trimmed)
    // =========================================================================

    private static InteractionResult tryRouteBusinessFront(TownspersonMob npc,
                                                           ServerPlayer sp,
                                                           ServerLevel level) {
        Optional<UUID> presentBuildingId = BuildingPresenceTracker.getBuildingFor(sp.getUUID());
        if (presentBuildingId.isEmpty()) return null;
        UUID buildingId = presentBuildingId.get();

        Optional<Building> buildingOpt = VillageSavedData.get(level).getBuildingById(buildingId);
        if (buildingOpt.isEmpty()) return null;
        Building building = buildingOpt.get();
        if (!BuildingTypeFlags.hasBusinessFront(building.getType())
                && !BuildingTypeFlags.hasServiceFront(building.getType())) {
            return null;
        }

        boolean npcWorksHere = npc.getAssignedBuildingId()
                .filter(id -> id.equals(buildingId))
                .isPresent();
        if (!npcWorksHere) return null;

        // Off-hours: refusal + repeat-mood-penalty (preserved behavior).
        if (!npc.isWorkTime()) {
            sp.displayClientMessage(Component.literal(
                            "[" + npc.getNpcName() + "] We're closed. Come back during work hours."),
                    /* actionBar */ true);
            int refusals = tterrag1112.life_in_the_village.Npc.BusinessFront
                    .BusinessFrontTracker.recordRefusal(sp.getUUID(), buildingId);
            if (refusals >= 3) {
                npc.getMood().applyWithRawMagnitude(
                        tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger.INSULT_RECEIVED,
                        -2, level.getGameTime());
            }
            return InteractionResult.SUCCESS;
        }

        tterrag1112.life_in_the_village.Village.Village village = npc.getAssignedVillageName()
                .flatMap(VillageSavedData.get(level)::getVillageByName)
                .orElse(null);

        var verbCtx = PlayerVerb.context(sp, npc, level);
        List<String> verbIds = new ArrayList<>();
        List<String> verbLabels = new ArrayList<>();
        for (var verb : PlayerVerbRegistry.availableFor(verbCtx)) {
            verbIds.add(verb.id());
            verbLabels.add(verb.label().getString());
            if (verbIds.size() >= 8) break;
        }

        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                new tterrag1112.life_in_the_village.Networking.OpenBusinessFrontPacket(
                        npc.getUUID(),
                        npc.getNpcName(),
                        npc.getProfession().getDisplayName(),
                        building.getType().name(),
                        village == null ? "" : village.getName(),
                        verbIds,
                        verbLabels));
        return InteractionResult.SUCCESS;
    }

    // =========================================================================
    // Public helpers retained for legacy callers
    // =========================================================================

    /**
     * Public rent-stall flow kept for debug commands and the future
     * StallLeaseScreen mint path (deferred — see report).
     */
    public static boolean tryRentStall(TownspersonMob npc,
                                       ServerPlayer player,
                                       ServerLevel level) {
        // Inlined the legacy implementation to avoid re-exporting it on
        // a per-profession handler. Same shape as before.
        VillageSavedData data = VillageSavedData.get(level);
        Building market = npc.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType() == BuildingType.MARKET)
                .orElse(null);
        if (market == null) return false;

        Optional<MarketStall> existing = data.getStallsForMarket(market.getId()).stream()
                .filter(s -> s.isActive() && s.getOwnerUUID().equals(player.getUUID()))
                .findFirst();

        if (existing.isPresent()) {
            MarketStall stall = existing.get();
            if (stall.isPurchased()) {
                player.displayClientMessage(Component.literal(
                                "[" + npc.getNpcName() + "] You own stall #"
                                        + (stall.getSlotIndex() + 1) + " permanently.")
                        .withStyle(ChatFormatting.YELLOW), false);
                return true;
            }
            long renewCost = MarketStallPlacer.RENT_PER_DAY * 7L;
            long playerWealth = CoinHelper.getPlayerWealth(player).toBronze();
            if (playerWealth < renewCost) {
                player.displayClientMessage(Component.literal(
                                "[" + npc.getNpcName() + "] You need " + renewCost
                                        + " bronze to renew for 7 days.")
                        .withStyle(ChatFormatting.YELLOW), false);
                return true;
            }
            CoinHelper.playerPay(player, CurrencyValue.of(renewCost));
            npc.getAssignedVillageName().flatMap(data::getVillageByName)
                    .ifPresent(v -> v.depositToTreasury(renewCost));
            stall.setRentPaidUntilTick(
                    Math.max(stall.getRentPaidUntilTick(), level.getGameTime()) + 24000L * 7L);
            data.setDirty();
            player.displayClientMessage(Component.literal(
                            "[" + npc.getNpcName() + "] Stall #"
                                    + (stall.getSlotIndex() + 1) + " renewed for 7 days.")
                    .withStyle(ChatFormatting.GREEN), false);
            return true;
        }

        int totalSlots = MarketStallPlacer.findAnchorSlots(level, market).size();
        int occupied = (int) data.getStallsForMarket(market.getId()).stream()
                .filter(MarketStall::isActive).count();
        if (occupied >= totalSlots) {
            player.displayClientMessage(Component.literal(
                            "[" + npc.getNpcName() + "] All stalls are taken.")
                    .withStyle(ChatFormatting.RED), false);
            return true;
        }

        long playerWealth = CoinHelper.getPlayerWealth(player).toBronze();
        boolean purchasing = playerWealth >= MarketStallPlacer.PURCHASE_PRICE;
        long cost = purchasing
                ? MarketStallPlacer.PURCHASE_PRICE
                : MarketStallPlacer.RENT_PER_DAY * 7L;
        if (playerWealth < cost) {
            player.displayClientMessage(Component.literal(
                            "[" + npc.getNpcName() + "] You need " + (cost - playerWealth)
                                    + " more bronze for a stall.")
                    .withStyle(ChatFormatting.RED), false);
            return true;
        }
        CoinHelper.playerPay(player, CurrencyValue.of(cost));
        npc.getAssignedVillageName().flatMap(data::getVillageByName)
                .ifPresent(v -> v.depositToTreasury(cost));
        long rentUntil = purchasing ? Long.MAX_VALUE : level.getGameTime() + 24000L * 7L;
        MarketStallPlacer.claimSlot(level, market, player.getUUID(),
                        MarketStall.OwnerType.PLAYER, rentUntil, data)
                .ifPresent(stall -> {
                    data.addMarketStall(stall);
                    player.displayClientMessage(Component.literal(
                                    "[" + npc.getNpcName() + "] Stall "
                                            + (stall.getSlotIndex() + 1) + " is yours.")
                            .withStyle(ChatFormatting.GREEN), false);
                });
        return true;
    }
}
