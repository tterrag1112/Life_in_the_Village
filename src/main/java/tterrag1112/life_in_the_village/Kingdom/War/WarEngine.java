package tterrag1112.life_in_the_village.Kingdom.War;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Kingdom.Audience.KingdomNewsfeed;
import tterrag1112.life_in_the_village.Kingdom.Capabilities.KingdomCapability;
import tterrag1112.life_in_the_village.Kingdom.Capabilities.KingdomCapabilityEvaluator;
import tterrag1112.life_in_the_village.Kingdom.DiplomaticRelation;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomModifier;
import tterrag1112.life_in_the_village.Kingdom.Merger.MergerEngine;
import tterrag1112.life_in_the_village.Kingdom.Treaties.Treaty;
import tterrag1112.life_in_the_village.Kingdom.Treaties.TreatyType;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Track D3.6.4 — political war shell engine.
 *
 * <p>Per the user-locked design "(B) Stub handoff": Phase 6 produces
 * a {@link Battle} record with all inputs ready; the default
 * scoring resolver in {@link BattleResolver#DEFAULT} computes
 * outcomes. The warfare session later replaces only the resolver
 * via {@link #setResolver}; everything else here remains stable.
 *
 * <h3>Determinism</h3>
 * War declaration is deterministic (no RNG). Daily battle
 * scheduling rolls per
 * {@code (warId, "battle", gameDay)}. Battle resolution seeds the
 * resolver from {@code (battleId, gameDay)}.
 *
 * <h3>Cascade-break + treaty integration</h3>
 * Declaring WAR while bound by NON_AGGRESSION breaks that
 * treaty (reason {@code "war.declared"}) and applies the locked
 * "broken treaty during war" major legitimacy malus.
 * Cooperative treaties (ALLIANCE / TRADE_DEAL) cascade-break
 * naturally via {@link Kingdom#setRelation}'s WAR cascade
 * (D3.4b).
 */
public final class WarEngine {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Pluggable resolver — the warfare session replaces this. */
    private static BattleResolver resolver = BattleResolver.DEFAULT;

    public static void setResolver(BattleResolver r) {
        resolver = r != null ? r : BattleResolver.DEFAULT;
    }

    /** Battle scheduling: every N game days, attacker probes for a battle. */
    public static final long BATTLE_INTERVAL_DAYS = 5;

    /** Daily attrition score nudge applied to whoever has fewer levies. */
    public static final int ATTRITION_DAILY = -1;

    /** Aggressive war legitimacy malus (applied at declaration). */
    public static final int AGGRESSIVE_WAR_MALUS = -8;

    /** Defensive war legitimacy bonus (applied to defender's eventual victor). */
    public static final int DEFENSIVE_VICTORY_BONUS = 10;

    /** Major malus when a non-aggression treaty was broken to declare. */
    public static final int BROKEN_TREATY_MALUS = -20;

    public record DeclareResult(boolean declared, UUID warId, String reason) {
        public static DeclareResult fail(String r) {
            return new DeclareResult(false, null, r);
        }
    }

    private WarEngine() {}

    /**
     * Declares war. Validates DECLARE_WAR capability + treaty
     * preconditions; mirrors WAR diplomatic relation; creates the
     * war record on the attacker; fires {@code WarDeclared}.
     */
    public static DeclareResult declareWar(ServerLevel level, VillageSavedData data,
                                           Kingdom attacker, Kingdom defender,
                                           CasusBelli cb, List<WarGoal> goals,
                                           long tick) {
        if (attacker == null || defender == null) {
            return DeclareResult.fail("missing kingdom");
        }
        if (attacker.getId().equals(defender.getId())) {
            return DeclareResult.fail("cannot declare war on self");
        }
        // Capability check (King OR General per D3.3b).
        var capResult = KingdomCapabilityEvaluator.evaluate(
                level, data, attacker, KingdomCapability.DECLARE_WAR);
        if (!capResult.allowed()) {
            return DeclareResult.fail("DECLARE_WAR denied: " + capResult.reason());
        }
        // Validate goals against casus belli.
        for (WarGoal g : goals) {
            if (g.kind() == WarGoal.Kind.TERRITORY && !cb.allowsTerritory()) {
                return DeclareResult.fail(cb.name() + " does not admit territory goals");
            }
            if (g.kind() == WarGoal.Kind.VASSALIZE && !cb.allowsVassalization()) {
                return DeclareResult.fail(cb.name() + " does not admit vassalization");
            }
            if (g.kind() == WarGoal.Kind.REGIME_CHANGE && !cb.allowsRegimeChange()) {
                return DeclareResult.fail(cb.name() + " does not admit regime change");
            }
        }
        // Already at war?
        for (War w : attacker.getActiveWars()) {
            if (w.defenderKingdomId().equals(defender.getId())) {
                return DeclareResult.fail("already at war with this kingdom");
            }
        }
        // NON_AGGRESSION blocks declaration without first breaking the treaty.
        Treaty nonAgg = activeNonAggressionWith(attacker, defender);
        boolean treatyBroken = false;
        if (nonAgg != null) {
            attacker.breakTreaty(nonAgg.id(), attacker.getId(), tick,
                    "war.declared");
            defender.breakTreaty(nonAgg.id(), attacker.getId(), tick,
                    "war.declared");
            treatyBroken = true;
        }

        // Set diplomatic relation (cascade-breaks cooperative treaties on both sides).
        attacker.setRelation(defender.getId(), DiplomaticRelation.WAR);
        defender.setRelation(attacker.getId(), DiplomaticRelation.WAR);

        // Apply legitimacy modifiers.
        attacker.applyLegitimacyDelta(cb.legitimacyOnDeclaration());
        if (cb == CasusBelli.HONOUR || cb == CasusBelli.RECONQUEST) {
            attacker.applyLegitimacyDelta(AGGRESSIVE_WAR_MALUS);
        }
        if (treatyBroken) {
            attacker.applyLegitimacyDelta(BROKEN_TREATY_MALUS);
            attacker.addModifier(KingdomModifier.expiring(
                    "war.broken_treaty",
                    "Declared war while bound by NON_AGGRESSION",
                    -2, BROKEN_TREATY_MALUS,
                    tick, 24000L * 60));
        }
        attacker.addModifier(KingdomModifier.expiring(
                "war.active." + defender.getId(),
                "At war with " + defender.getName(),
                -3, 0,
                tick, 24000L * 30));

        // Create war record.
        UUID warId = UUID.randomUUID();
        War war = War.freshDeclaration(warId, attacker.getId(), defender.getId(),
                cb, goals, tick);
        attacker.addWar(war);

        KingdomEventBus.fire(new KingdomEvent.WarDeclared(
                attacker.getId(), defender.getId(), warId,
                cb.name(), tick));
        KingdomNewsfeed.append(attacker, "war.declared",
                "Declared war on " + defender.getName() + " (" + cb.name() + ")", tick);
        KingdomNewsfeed.append(defender, "war.declared",
                attacker.getName() + " declared war (" + cb.name() + ")", tick);
        data.setDirty();

        LOGGER.info("[WarEngine] {} declared war on {} ({})",
                attacker.getName(), defender.getName(), cb);
        return new DeclareResult(true, warId, "war declared");
    }

    /**
     * Daily entry — for each active war, possibly schedule a
     * battle (every BATTLE_INTERVAL_DAYS days), apply attrition,
     * check resolution thresholds.
     */
    public static void dailyTick(ServerLevel level, VillageSavedData data, long tick) {
        long gameDay = tick / 24000L;
        for (Kingdom attacker : new ArrayList<>(data.getAllKingdoms())) {
            for (War war : new ArrayList<>(attacker.getActiveWars())) {
                Kingdom defender = data.getKingdomById(war.defenderKingdomId()).orElse(null);
                if (defender == null) {
                    // Defender vanished (collapse?). Resolve to white peace.
                    resolveWar(level, data, attacker, war, War.Status.WHITE_PEACE,
                            tick, "defender vanished");
                    continue;
                }
                War updated = war;

                // Battle scheduling.
                if ((gameDay - war.startTick() / 24000L) % BATTLE_INTERVAL_DAYS == 0L
                        && tick - war.startTick() >= 24000L) {
                    Battle pending = scheduleBattle(data, attacker, defender,
                            war, tick);
                    Battle resolved = resolver.resolve(pending);
                    updated = updated.withBattleAppended(resolved)
                            .withScore(updated.attackerScore()
                                    + resolved.attackerScoreDelta());
                    KingdomEventBus.fire(new KingdomEvent.BattleResolved(
                            war.id(), resolved.id(), resolved.outcome().name(),
                            resolved.attackerScoreDelta(), tick));
                    KingdomNewsfeed.append(attacker, "war.battle",
                            "Battle: " + resolved.outcome().name()
                                    + " (Δ " + resolved.attackerScoreDelta() + ")",
                            tick);
                    KingdomNewsfeed.append(defender, "war.battle",
                            "Battle vs " + attacker.getName() + ": "
                                    + resolved.outcome().name(),
                            tick);
                }

                // Daily attrition: whoever has fewer effective levies bleeds.
                int aLevy = LevyComputer.computeLevy(data, attacker);
                int dLevy = LevyComputer.computeLevy(data, defender);
                if (aLevy < dLevy) {
                    updated = updated.withScore(updated.attackerScore() + ATTRITION_DAILY);
                } else if (dLevy < aLevy) {
                    updated = updated.withScore(updated.attackerScore() - ATTRITION_DAILY);
                }

                attacker.replaceWar(updated);

                // Resolution thresholds.
                if (updated.attackerScore() >= War.ATTACKER_VICTORY_THRESHOLD) {
                    resolveWar(level, data, attacker, updated, War.Status.WON,
                            tick, "attacker victory");
                } else if (updated.attackerScore() <= War.DEFENDER_VICTORY_THRESHOLD) {
                    resolveWar(level, data, attacker, updated, War.Status.LOST,
                            tick, "defender victory");
                } else if (tick - updated.startTick() >= War.STALEMATE_TICKS) {
                    resolveWar(level, data, attacker, updated, War.Status.STALEMATE,
                            tick, "stalemate timeout");
                }
            }
        }
    }

    private static Battle scheduleBattle(VillageSavedData data,
                                         Kingdom attacker, Kingdom defender,
                                         War war, long tick) {
        int aLevy = LevyComputer.computeLevy(data, attacker);
        int dLevy = LevyComputer.computeLevy(data, defender);
        // Leadership: ruler legitimacy + General competence proxy via stability.
        double aLead = 0.7 + attacker.getLegitimacy() / 200.0;
        double dLead = 0.7 + defender.getLegitimacy() / 200.0;
        // Terrain: defender bonus when fighting on home turf.
        // v1 simplification: fixed +0.10 defender terrain bonus.
        double terrain = 0.10;
        // Supply: attacker penalty proportional to relative claim sizes.
        // v1 simplification: -0.15 fixed.
        double supply = -0.15;
        long battleSeed = war.id().getMostSignificantBits()
                ^ Long.rotateLeft("battle".hashCode(), 17)
                ^ (tick / 24000L);
        return new Battle(
                UUID.randomUUID(), war.id(),
                attacker.getId(), defender.getId(),
                aLevy, dLevy, aLead, dLead, terrain, supply,
                battleSeed, tick, Battle.Outcome.PENDING, 0, 0);
    }

    /**
     * Resolves a war with the given final status. Applies outcome
     * effects per status + war goals.
     */
    public static void resolveWar(ServerLevel level, VillageSavedData data,
                                  Kingdom attacker, War war, War.Status finalStatus,
                                  long tick, String summary) {
        Kingdom defender = data.getKingdomById(war.defenderKingdomId()).orElse(null);
        War resolved = war.asResolved(finalStatus, tick, summary);
        attacker.replaceWar(resolved);

        // Clear WAR relation back to NEUTRAL on both sides.
        attacker.setRelation(war.defenderKingdomId(), DiplomaticRelation.NEUTRAL);
        if (defender != null) {
            defender.setRelation(attacker.getId(), DiplomaticRelation.NEUTRAL);
        }
        attacker.removeModifier("war.active." + war.defenderKingdomId());

        switch (finalStatus) {
            case WON -> {
                if (defender != null) applyVictoryGoals(level, data, attacker, defender, war, tick);
                KingdomNewsfeed.append(attacker, "war.won",
                        "Won war against "
                                + (defender != null ? defender.getName() : "?")
                                + " — " + summary, tick);
                if (defender != null) {
                    KingdomNewsfeed.append(defender, "war.lost",
                            "Lost war to " + attacker.getName(), tick);
                    defender.applyLegitimacyDelta(-12);
                }
            }
            case LOST -> {
                attacker.applyLegitimacyDelta(-15);
                if (defender != null) {
                    defender.applyLegitimacyDelta(DEFENSIVE_VICTORY_BONUS);
                    KingdomNewsfeed.append(defender, "war.won",
                            "Repelled invasion by " + attacker.getName(), tick);
                }
                KingdomNewsfeed.append(attacker, "war.lost",
                        "Lost war against "
                                + (defender != null ? defender.getName() : "?"), tick);
            }
            case WHITE_PEACE, STALEMATE -> {
                KingdomNewsfeed.append(attacker, "war.peace",
                        "War with "
                                + (defender != null ? defender.getName() : "?")
                                + " ended (" + finalStatus.name() + ")", tick);
                if (defender != null) {
                    KingdomNewsfeed.append(defender, "war.peace",
                            "War with " + attacker.getName() + " ended ("
                                    + finalStatus.name() + ")", tick);
                }
            }
            default -> {}
        }

        KingdomEventBus.fire(new KingdomEvent.WarConcluded(
                war.id(), attacker.getId(), war.defenderKingdomId(),
                finalStatus.name(), summary, tick));
        data.setDirty();

        LOGGER.info("[WarEngine] War {} resolved as {} ({})",
                war.id(), finalStatus, summary);
    }

    private static void applyVictoryGoals(ServerLevel level, VillageSavedData data,
                                          Kingdom attacker, Kingdom defender,
                                          War war, long tick) {
        boolean conquestPath = false;
        for (WarGoal goal : war.goals()) {
            switch (goal) {
                case WarGoal.Territory t -> {
                    for (UUID villageId : t.villageIds()) {
                        if (defender.getVillageIds().contains(villageId)) {
                            data.getVillageById(villageId).ifPresent(v ->
                                    v.setKingdomId(attacker.getId()));
                            defender.removeVillage(villageId);
                            attacker.addVillage(villageId);
                        }
                    }
                    attacker.setLastProvinceRecomputeTick(-1L);
                    defender.setLastProvinceRecomputeTick(-1L);
                }
                case WarGoal.Tribute tr -> {
                    long taken = Math.min(tr.bronzeAmount(), defender.getTreasuryBronze());
                    defender.depositToTreasury(-taken);
                    attacker.depositToTreasury(taken);
                    if (tr.recurringDays() > 0) {
                        // v1: log only; recurring tribute via TRADE_DEAL
                        // wiring lands later.
                        KingdomNewsfeed.append(attacker, "war.tribute",
                                "Imposed " + tr.recurringDays()
                                        + "-day recurring tribute on "
                                        + defender.getName(), tick);
                    }
                }
                case WarGoal.Vassalize v -> {
                    // Create VASSALAGE treaty: vassal = defender, overlord = attacker.
                    UUID treatyId = new UUID(
                            attacker.getId().getMostSignificantBits()
                                    ^ defender.getId().getMostSignificantBits(),
                            tick);
                    Treaty vassalage = Treaty.autoMigrated(
                            treatyId, TreatyType.VASSALAGE,
                            defender.getId(), attacker.getId());
                    attacker.addTreaty(vassalage);
                    defender.addTreaty(vassalage);
                    KingdomNewsfeed.append(attacker, "war.vassalized",
                            defender.getName() + " is now my vassal", tick);
                    KingdomNewsfeed.append(defender, "war.vassalized",
                            "Forced to swear vassalage to " + attacker.getName(), tick);
                }
                case WarGoal.RegimeChange rc -> {
                    // v1: clear ruler so the next succession tick picks a new
                    // one with usurped legitimacy. The actual depose-and-
                    // reseat path lives in NobilityEventDispatcher.
                    defender.setRulerEntityId(null);
                    defender.setLegitimacy(35);
                    KingdomNewsfeed.append(attacker, "war.regime_change",
                            "Deposed the ruler of " + defender.getName(), tick);
                    KingdomNewsfeed.append(defender, "war.regime_change",
                            "Ruler deposed by " + attacker.getName(), tick);
                    conquestPath = true;
                }
            }
        }
        // If goals include both Territory + Vassalize + RegimeChange, treat
        // as a full conquest and absorb defender into attacker via the
        // merger engine.
        if (conquestPath && hasTerritoryAndVassalize(war.goals())) {
            MergerEngine.triggerConquestMerger(level, data, attacker, defender, tick);
        }
    }

    private static boolean hasTerritoryAndVassalize(List<WarGoal> goals) {
        boolean t = false, v = false;
        for (WarGoal g : goals) {
            if (g.kind() == WarGoal.Kind.TERRITORY) t = true;
            if (g.kind() == WarGoal.Kind.VASSALIZE) v = true;
        }
        return t && v;
    }

    private static Treaty activeNonAggressionWith(Kingdom a, Kingdom b) {
        for (Treaty t : a.getTreaties()) {
            if (t.isActive() && t.type() == TreatyType.NON_AGGRESSION
                    && t.involves(b.getId())) {
                return t;
            }
        }
        return null;
    }

    /** Finds an active war between two kingdoms, considering both directions. */
    public static War findActiveWarBetween(VillageSavedData data, Kingdom a, Kingdom b) {
        for (War w : a.getActiveWars()) {
            if (w.defenderKingdomId().equals(b.getId())) return w;
        }
        for (War w : b.getActiveWars()) {
            if (w.defenderKingdomId().equals(a.getId())) return w;
        }
        return null;
    }
}
