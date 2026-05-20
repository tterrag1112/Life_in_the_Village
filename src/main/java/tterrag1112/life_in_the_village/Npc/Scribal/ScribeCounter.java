package tterrag1112.life_in_the_village.Npc.Scribal;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Components.ModDataComponents;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Items.JobContractTerms;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Networking.ScribeCounterActionPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;

import java.util.Optional;
import java.util.UUID;

/**
 * Server-side handler for {@link ScribeCounterActionPacket}. Charges
 * the fee, mints the artifact (JOB_CONTRACT) or enqueues a
 * {@link ScribeCommission} for the workshop's {@link CommissionQueue}
 * (LETTER / BOOK_COPY), and awards the scribe CRAFTING XP per the
 * Track 2.1 commission XP path.
 */
public final class ScribeCounter {

    /** Matches {@code ScribeWorkGoal.XP_PER_COMMISSION}. */
    private static final float XP_PER_COMMISSION = 4f;
    /** Default contract duration when the player picks "indefinite". */
    private static final long TICKS_PER_DAY = 24000L;

    private ScribeCounter() {}

    public static void handle(ScribeCounterActionPacket pkt,
                              ServerPlayer player,
                              ServerLevel level) {
        TownspersonMob scribe = TownspersonMob.findByUUID(level, pkt.scribeId()).orElse(null);
        if (scribe == null || scribe.getProfession() != Profession.SCRIBE) {
            player.displayClientMessage(Component.literal(
                            "The scribe is no longer available.")
                    .withStyle(ChatFormatting.RED), false);
            return;
        }
        UUID workshopId = scribe.getAssignedBuildingId().orElse(null);
        if (workshopId == null) {
            player.displayClientMessage(Component.literal(
                            "[" + scribe.getNpcName() + "] I'm not working anywhere right now.")
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }

        switch (pkt.tab()) {
            case JOB_CONTRACT -> handleJobContract(pkt, player, level, scribe);
            case LETTER       -> handleLetter(pkt, player, level, scribe, workshopId);
            case BOOK_COPY    -> handleBookCopy(pkt, player, level, scribe, workshopId);
        }
    }

    // ── Job Contract — mint the item directly ───────────────────────────────

    private static void handleJobContract(ScribeCounterActionPacket pkt,
                                          ServerPlayer player,
                                          ServerLevel level,
                                          TownspersonMob scribe) {
        Profession prof = parseProfession(pkt.professionName());
        if (prof == null) {
            player.displayClientMessage(Component.literal(
                            "Unknown profession: " + pkt.professionName())
                    .withStyle(ChatFormatting.RED), false);
            return;
        }
        JobContractTerms.Rank rank = parseRank(pkt.rankName());
        long fee = ScribeCommission.feeFor(ScribeProductType.CONTRACT, 0);
        if (!chargeFee(player, fee, scribe)) return;

        JobContractTerms terms;
        String targetItem = pkt.targetItemId() == null ? "" : pkt.targetItemId().trim();
        long salary = Math.max(0, pkt.salaryBronze());
        long duration = (long) Math.max(0, pkt.durationDays()) * TICKS_PER_DAY;

        if (rank == JobContractTerms.Rank.APPRENTICE) {
            // Master defaults to the player issuing the contract.
            terms = JobContractTerms.apprenticeship(prof, player.getUUID(), duration);
        } else if (!targetItem.isEmpty()) {
            terms = JobContractTerms.commission(prof, targetItem, salary, player.getUUID());
        } else {
            terms = new JobContractTerms(prof, rank, Optional.empty(),
                    salary, Optional.empty(), duration, Optional.of(player.getUUID()));
        }

        ItemStack stack = new ItemStack(ModItems.JOB_CONTRACT.get());
        stack.set(ModDataComponents.JOB_CONTRACT_TERMS.get(), terms);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        awardScribeXp(scribe, level);
        player.displayClientMessage(Component.literal(
                        "[" + scribe.getNpcName() + "] Contract drafted. "
                                + fee + " bronze, please.")
                .withStyle(ChatFormatting.GREEN), false);
    }

    // ── Letter — enqueue on the workshop's commission queue ─────────────────

    private static void handleLetter(ScribeCounterActionPacket pkt,
                                     ServerPlayer player,
                                     ServerLevel level,
                                     TownspersonMob scribe,
                                     UUID workshopId) {
        String body = pkt.body() == null ? "" : pkt.body();
        if (body.isBlank()) {
            player.displayClientMessage(Component.literal(
                            "[" + scribe.getNpcName() + "] What should the letter say?")
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        long fee = ScribeCommission.feeFor(ScribeProductType.LETTER, body.length());
        if (!chargeFee(player, fee, scribe)) return;

        VillageSavedData data = VillageSavedData.get(level);
        CommissionQueue queue = data.getOrCreateCommissionQueue(workshopId);
        ScribeCommission c = new ScribeCommission(
                UUID.randomUUID(), player.getUUID(), true,
                ScribeProductType.LETTER, body,
                Optional.empty(), // recipient UUID unknown; ScribeWorkGoal uses recipientName field
                level.getGameTime(), level.getGameTime() + TICKS_PER_DAY,
                fee, CommissionStatus.PENDING, 5);
        queue.enqueue(c);
        data.setDirty();
        awardScribeXp(scribe, level);
        player.displayClientMessage(Component.literal(
                        "[" + scribe.getNpcName() + "] I'll have your letter ready soon.")
                .withStyle(ChatFormatting.GREEN), false);
    }

    // ── Book Copy — enqueue commission ──────────────────────────────────────

    private static void handleBookCopy(ScribeCounterActionPacket pkt,
                                       ServerPlayer player,
                                       ServerLevel level,
                                       TownspersonMob scribe,
                                       UUID workshopId) {
        String src = pkt.sourceBookId() == null ? "" : pkt.sourceBookId();
        if (src.isBlank()) {
            player.displayClientMessage(Component.literal(
                            "[" + scribe.getNpcName() + "] Bring me the book you want copied "
                                    + "(right-click me holding it).")
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        long fee = ScribeCommission.feeFor(ScribeProductType.BOOK_COPY, src.length());
        if (!chargeFee(player, fee, scribe)) return;

        VillageSavedData data = VillageSavedData.get(level);
        CommissionQueue queue = data.getOrCreateCommissionQueue(workshopId);
        ScribeCommission c = new ScribeCommission(
                UUID.randomUUID(), player.getUUID(), true,
                ScribeProductType.BOOK_COPY, src,
                Optional.empty(),
                level.getGameTime(), level.getGameTime() + TICKS_PER_DAY * 3,
                fee, CommissionStatus.PENDING, 3);
        queue.enqueue(c);
        data.setDirty();
        awardScribeXp(scribe, level);
        player.displayClientMessage(Component.literal(
                        "[" + scribe.getNpcName() + "] I'll copy it when I have time.")
                .withStyle(ChatFormatting.GREEN), false);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static boolean chargeFee(ServerPlayer player, long bronze, TownspersonMob scribe) {
        long wealth = CoinHelper.getPlayerWealth(player).toBronze();
        if (wealth < bronze) {
            player.displayClientMessage(Component.literal(
                            "[" + scribe.getNpcName() + "] That'll be " + bronze
                                    + " bronze. You're short " + (bronze - wealth) + ".")
                    .withStyle(ChatFormatting.RED), false);
            return false;
        }
        CoinHelper.playerPay(player, CurrencyValue.of(bronze));
        return true;
    }

    private static void awardScribeXp(TownspersonMob scribe, ServerLevel level) {
        // Mirror Track 2.1 commission XP path; LITERACY is the scribe's
        // primary, CRAFTING gets the per-commission bump.
        scribe.getSkills().addXp(Skill.LITERACY, XP_PER_COMMISSION, level.getGameTime());
        scribe.getSkills().addXp(Skill.CRAFTING, XP_PER_COMMISSION, level.getGameTime());
    }

    private static Profession parseProfession(String name) {
        if (name == null || name.isEmpty()) return null;
        try { return Profession.valueOf(name); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static JobContractTerms.Rank parseRank(String name) {
        if (name == null || name.isEmpty()) return JobContractTerms.Rank.NONE;
        try { return JobContractTerms.Rank.valueOf(name); }
        catch (IllegalArgumentException e) { return JobContractTerms.Rank.NONE; }
    }

}
