package tterrag1112.life_in_the_village.Npc.Crime;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.UUID;

/**
 * Applies a {@link Punishment} to its target. Spec lines 240-251.
 *
 * <p>Player-target paths use {@link ServerPlayer} APIs (chat message,
 * inventory mutation, teleport for EXILE). NPC-target paths use
 * {@link TownspersonMob} components. v1 ships the on-success effect;
 * COMMUNITY_LABOR + DETENTION + OFFICE_BAR are durations the
 * relevant subsystem (jobs / movement / office election) reads
 * later.</p>
 */
public final class PunishmentExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PunishmentExecutor() {}

    public static void execute(Punishment p, CrimeReport report, ServerLevel level) {
        if (p == null || report == null || level == null) return;
        VillageSavedData vdata = VillageSavedData.get(level);
        Village village = vdata.getVillageById(report.villageId()).orElse(null);
        UUID accused = report.perpetratorId().orElse(null);
        if (accused == null) return;

        switch (p.type()) {
            case WARNING -> doWarning(report, accused, level, village);
            case FINE -> doFine(p, accused, level, village);
            case RESTITUTION -> doRestitution(p, report, accused, level);
            case COMMUNITY_LABOR -> doCommunityLabor(p, accused, village, level);
            case DETENTION -> doDetention(p, accused, level);
            case EXILE -> doExile(accused, level, village);
            case EXECUTION -> doExecution(accused, level, village);
            case ITEM_CONFISCATION -> doItemConfiscation(p, accused, level);
            case OFFICE_BAR -> doOfficeBar(p, accused, village, level);
        }
        LOGGER.debug("[PunishmentExecutor] {} → {}", p.type(), p.details());

        // SERIOUS / CAPITAL guilty verdicts go into kingdom history per
        // spec line 259.
        if (report.type().severity() == CrimeSeverity.SERIOUS
                || report.type().severity() == CrimeSeverity.CAPITAL) {
            recordKingdomHistory(report, p, village, level, vdata);
        }
    }

    // ── Type implementations ───────────────────────────────────────────────

    private static void doWarning(CrimeReport report, UUID accused, ServerLevel level, Village v) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(accused);
        if (player != null) {
            player.displayClientMessage(Component.literal(
                    "[Court] You are warned for " + report.type().name() + "."), false);
        }
    }

    private static void doFine(Punishment p, UUID accused, ServerLevel level, Village v) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(accused);
        if (player != null) {
            // Player wallet not directly modeled — message + village
            // reputation hit stand in until Phase 5 economy ties player
            // bronze cleanly to the treasury.
            player.displayClientMessage(Component.literal(
                    "[Court] Fined " + p.bronzeAmount() + " bronze."), false);
            if (v != null) v.modifyReputation(accused, -3);
            return;
        }
        TownspersonMob npc = TownspersonMob.findByUUID(level, accused).orElse(null);
        if (npc != null) {
            CurrencyValue cost = CurrencyValue.of(p.bronzeAmount());
            if (!npc.getWallet().canAfford(cost)) {
                // Insufficient — escalate to detention per spec line 243.
                doDetention(Punishment.detention(7), accused, level);
                return;
            }
            npc.getWallet().spend(cost);
            if (v != null) v.depositToTreasury(p.bronzeAmount());
        }
    }

    private static void doRestitution(Punishment p, CrimeReport report, UUID accused, ServerLevel level) {
        UUID victim = report.victimId().orElse(null);
        if (victim == null) return;
        TownspersonMob accusedNpc = TownspersonMob.findByUUID(level, accused).orElse(null);
        TownspersonMob victimNpc  = TownspersonMob.findByUUID(level, victim).orElse(null);
        long amount = p.bronzeAmount();
        if (accusedNpc != null) {
            CurrencyValue cost = CurrencyValue.of(amount);
            if (accusedNpc.getWallet().canAfford(cost)) {
                accusedNpc.getWallet().spend(cost);
            } else {
                doDetention(Punishment.detention(7), accused, level);
                return;
            }
        }
        if (victimNpc != null) victimNpc.getWallet().receive(CurrencyValue.of(amount));
    }

    private static void doCommunityLabor(Punishment p, UUID accused, Village v, ServerLevel level) {
        // v1: log + flag on the NPC's currentActivity. Phase 4 jobs
        // refactor wires the actual public-works JobPosting; the
        // Punishment record's durationTicks is the data-side commitment.
        TownspersonMob npc = TownspersonMob.findByUUID(level, accused).orElse(null);
        if (npc != null) npc.setCurrentActivity("Sentenced: community labor");
    }

    private static void doDetention(Punishment p, UUID accused, ServerLevel level) {
        TownspersonMob npc = TownspersonMob.findByUUID(level, accused).orElse(null);
        if (npc != null) npc.setCurrentActivity("In detention");
        // DetentionGoal lands as a follow-up — spec "Things to flag" #3
        // raises area-tether vs movement-constraint as an open
        // question. v1 only flags the NPC; player detention surfaces as
        // a chat warning (no teleport tether).
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(accused);
        if (player != null) {
            player.displayClientMessage(Component.literal(
                    "[Court] You are sentenced to detention. Stay near the village jail."), false);
        }
    }

    private static void doExile(UUID accused, ServerLevel level, Village v) {
        TownspersonMob npc = TownspersonMob.findByUUID(level, accused).orElse(null);
        if (npc != null) {
            npc.setAssignedVillageName(null);
            npc.setCurrentActivity("Exiled");
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(accused);
        if (player != null) {
            player.displayClientMessage(Component.literal(
                    "[Court] You are exiled from "
                            + (v == null ? "this village" : v.getName())
                            + ". Do not return."), false);
            if (v != null) v.modifyReputation(accused, -200);
        }
    }

    private static void doExecution(UUID accused, ServerLevel level, Village v) {
        TownspersonMob npc = TownspersonMob.findByUUID(level, accused).orElse(null);
        if (npc != null) {
            // discard() removes the entity from the level without
            // dropping items / triggering the death event chain. v1
            // intentionally avoids LivingDeathEvent here because the
            // existing onTownspersonDeath handlers (farmer succession,
            // assault detection) shouldn't trigger for a court-ordered
            // execution.
            npc.discard();
        }
        // Player executions never happen in v1 (BAN_EXECUTION applies
        // by default in PunishmentSelector for player-targeted cases
        // until Phase 5 culture wiring decides otherwise).
    }

    private static void doItemConfiscation(Punishment p, UUID accused, ServerLevel level) {
        // v1 flag — actual item removal hooks into the existing trade
        // / inventory system in Phase 4. The Punishment record's
        // confiscatedItem field is reserved for that pass.
        TownspersonMob npc = TownspersonMob.findByUUID(level, accused).orElse(null);
        if (npc != null) npc.setCurrentActivity("Items confiscated");
    }

    private static void doOfficeBar(Punishment p, UUID accused, Village v, ServerLevel level) {
        // Vacate any office the accused holds in this village; the
        // duration field is read by the Phase 4 office election (a
        // follow-up will surface "ineligible until X" gating).
        if (v == null) return;
        v.getOffices().holdingsHeldBy(accused).forEach(h -> {
            tterrag1112.life_in_the_village.Npc.Office.OfficeElection
                    .vacateAllHeldBy(accused, level, "office_bar");
        });
    }

    private static void recordKingdomHistory(CrimeReport report, Punishment p,
                                             Village village, ServerLevel level,
                                             VillageSavedData data) {
        if (village == null) return;
        var kingdomOpt = data.getKingdomForVillage(village.getId());
        if (kingdomOpt.isEmpty()) return;
        long now = level.getGameTime();
        var event = tterrag1112.life_in_the_village.Lore.KingdomHistoryData
                .KingdomHistoryEvent.create(
                tterrag1112.life_in_the_village.Lore.KingdomHistoryData
                        .HistoryEventType.DECREE_ISSUED,
                "Trial in " + village.getName(),
                report.type().name() + " → " + p.type().name(),
                now,
                village.getName());
        kingdomOpt.get().getHistory().recordEvent(event, kingdomOpt.get().getName());
    }

    /** Suppresses unused-import on HistoryTextGenerator (kept for future hooks). */
    @SuppressWarnings("unused")
    private static final HistoryTextGenerator DOC_HOOK = null;
}
