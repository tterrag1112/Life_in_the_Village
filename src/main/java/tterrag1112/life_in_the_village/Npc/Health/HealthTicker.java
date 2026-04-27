package tterrag1112.life_in_the_village.Npc.Health;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Npc.Mood.MoodCategory;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.UUID;

/**
 * Daily medical sweep. Spec lines 70-114.
 *
 * <p>Order of operations per village (run inside the daily
 * subsystem):</p>
 * <ol>
 *   <li>{@link #resolveConditions} — every NPC's {@code tickConditions}
 *   call. Resolved conditions fire HEALED mood + recovery memories.
 *   Mortality flags handled at the end via {@code pendingDeath}.</li>
 *   <li>{@link #applyOnsetRolls} — seasonal illness, exhaustion,
 *   trauma onset.</li>
 *   <li>{@link #applyContagion} — proximity-spread for contagious
 *   conditions; PLAGUE_CARRIER spread bumped + quarantine cut.</li>
 *   <li>{@link #applyAmbientMood} — plague villages take a daily
 *   ambient mood hit; resolved-plague villages take a 2-week post-
 *   mood low (handled via the plague's resolved status).</li>
 *   <li>{@link #applyHealerOverwork} — 5%/day mortality on the on-
 *   duty healer per spec line 182.</li>
 *   <li>Pending deaths get killed.</li>
 * </ol>
 */
public final class HealthTicker {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Spec line 84 — 0.02/day winter respiratory. */
    private static final float WINTER_RESPIRATORY_BASE = 0.02f;
    /** Spec line 84 — 0.01/day summer stomach. */
    private static final float SUMMER_STOMACH_BASE     = 0.01f;
    /** Spec line 85 — proximity scan radius for contagion. */
    private static final double CONTAGION_RADIUS = 4.0;
    /** Spec line 88 — daily plague infection chance. */
    private static final float PLAGUE_INFECTION_RATE = 0.10f;
    /** Spec line 174 — quarantine reduces spread by 60%. */
    private static final float QUARANTINE_REDUCTION  = 0.40f;
    /** Spec line 79 — exhaustion threshold in consecutive work days. */
    private static final int EXHAUSTION_THRESHOLD     = 20;
    /** Spec line 89 — distressed-day threshold for NERVOUS_BREAKDOWN. */
    private static final int NERVOUS_BREAKDOWN_THRESHOLD = 30;
    /** Spec line 96 — frailty constitution decay (1/month past elderly). */
    private static final int MONTHLY_TICKS = 30 * 24000;
    /** Spec line 182 — 5%/day plague exposure mortality for the healer. */
    private static final float HEALER_PLAGUE_MORTALITY = 0.05f;

    private HealthTicker() {}

    public static void dailyTick(ServerLevel level) {
        long now = level.getGameTime();
        RandomSource rng = level.getRandom();
        VillageSavedData vdata = VillageSavedData.get(level);
        HealthSavedData hdata = HealthSavedData.get(level);

        for (Village village : vdata.getAllVillages()) {
            tickVillage(level, vdata, hdata, village, now, rng);
        }

        // Player remedy stash pruning lives in the verb that opens
        // the stash (no scan-all-stashes hook in v1); the daily sweep
        // intentionally leaves player remedies alone.
    }

    private static void tickVillage(ServerLevel level, VillageSavedData vdata,
                                    HealthSavedData hdata, Village village,
                                    long now, RandomSource rng) {
        boolean quarantined = village.getPolicy().isActive(VillageLaw.QUARANTINE_VILLAGE);
        java.util.Optional<Plague> activePlague = hdata.getActivePlague(village.getId());

        List<TownspersonMob> villagers = villagersOf(level, village);
        if (villagers.isEmpty()) return;

        // 1) Resolve conditions; fire HEALED + recovery side effects.
        int recovered = 0;
        int died = 0;
        for (TownspersonMob npc : villagers) {
            HealthComponent h = npc.getHealthComponent();
            List<HealthCondition> resolved = h.tickConditions(now, rng);
            for (HealthCondition c : resolved) {
                npc.getMood().applyWithRawMagnitude(MoodTrigger.HEALED,
                        MoodTrigger.HEALED.defaultMagnitude(), now);
                if (c == HealthCondition.PLAGUE_CARRIER) recovered++;
            }
            if (h.pendingDeath()) {
                h.clearPendingDeath();
                died++;
                npc.hurt(level.damageSources().generic(), Float.MAX_VALUE);
            }
        }
        if (activePlague.isPresent() && (recovered > 0 || died > 0)) {
            Plague p = activePlague.get();
            int newInfected = Math.max(0, p.infectedCount() - recovered - died);
            hdata.putPlague(p.withCounts(newInfected,
                    p.recoveredCount() + recovered,
                    p.diedCount() + died));
        }

        // 2) Onset rolls — seasonal, exhaustion, trauma, frailty.
        boolean isWinter = isWinter(level);
        boolean isSummer = isSummer(level);
        for (TownspersonMob npc : villagers) {
            HealthComponent h = npc.getHealthComponent();

            if (isWinter && rng.nextFloat() < WINTER_RESPIRATORY_BASE
                    * constitutionMultiplier(h.constitution())) {
                addCondition(npc, HealthCondition.RESPIRATORY_ILLNESS, now);
            }
            if (isSummer && rng.nextFloat() < SUMMER_STOMACH_BASE
                    * constitutionMultiplier(h.constitution())) {
                addCondition(npc, HealthCondition.STOMACH_AILMENT, now);
            }

            // Exhaustion (spec line 79).
            if (h.consecutiveWorkDays() >= EXHAUSTION_THRESHOLD
                    && !h.hasCondition(HealthCondition.EXHAUSTION)) {
                addCondition(npc, HealthCondition.EXHAUSTION, now);
                h.setConsecutiveWorkDays(0);
            }

            // Trauma — track DISTRESSED days; trigger NERVOUS_BREAKDOWN.
            if (npc.getMood().category() == MoodCategory.DISTRESSED) {
                h.setDistressedDayCount(h.distressedDayCount() + 1);
                if (h.distressedDayCount() >= NERVOUS_BREAKDOWN_THRESHOLD
                        && !h.hasCondition(HealthCondition.NERVOUS_BREAKDOWN)) {
                    addCondition(npc, HealthCondition.NERVOUS_BREAKDOWN, now);
                    h.setDistressedDayCount(0);
                }
            } else {
                h.setDistressedDayCount(0);
            }

            // Frailty (spec line 94-96).
            if (npc.getLifeStage() == LifeStage.ELDERLY) {
                if (!h.hasCondition(HealthCondition.FRAILTY) && rng.nextFloat() < 0.1f) {
                    addCondition(npc, HealthCondition.FRAILTY, now);
                }
                if ((now / MONTHLY_TICKS) != ((now - 24000L) / MONTHLY_TICKS)) {
                    h.adjustConstitution(-1);
                }
            }
        }

        // 3) Contagion — proximity-spread for contagious conditions.
        applyContagion(level, villagers, quarantined, now, rng);

        // 4) Plague: roll daily infections (independent of contagion).
        activePlague.ifPresent(p -> applyPlagueDay(level, vdata, hdata,
                village, p, villagers, quarantined, now, rng));

        // 5) Plague mood pressure on the village.
        if (activePlague.isPresent()) {
            for (TownspersonMob npc : villagers) {
                npc.getMood().applyWithRawMagnitude(MoodTrigger.PLAGUE_AMBIENT,
                        MoodTrigger.PLAGUE_AMBIENT.defaultMagnitude(), now);
            }
        }

        // 6) Healer overwork — only during plague.
        activePlague.ifPresent(p -> applyHealerOverwork(level, hdata, p, villagers, now, rng));
    }

    private static void applyContagion(ServerLevel level, List<TownspersonMob> villagers,
                                       boolean quarantined, long now, RandomSource rng) {
        float reduction = quarantined ? QUARANTINE_REDUCTION : 1f;
        for (TownspersonMob carrier : villagers) {
            if (!carrier.getHealthComponent().isContagious()) continue;
            AABB box = new AABB(carrier.blockPosition()).inflate(CONTAGION_RADIUS);
            List<TownspersonMob> nearby = level.getEntitiesOfClass(
                    TownspersonMob.class, box, t -> t != carrier && t.isAlive());
            for (TownspersonMob other : nearby) {
                HealthComponent oh = other.getHealthComponent();
                if (oh.hasCondition(HealthCondition.PLAGUE_CARRIER)) continue;
                float chance = 0.05f * reduction
                        * constitutionMultiplier(oh.constitution());
                if (rng.nextFloat() >= chance) continue;
                // Infect with the carrier's specific condition.
                for (ActiveCondition c : carrier.getHealthComponent().active()) {
                    if (!c.type().isContagious()) continue;
                    addCondition(other, c.type(), now);
                    break;
                }
            }
        }
    }

    private static void applyPlagueDay(ServerLevel level, VillageSavedData vdata,
                                       HealthSavedData hdata, Village village,
                                       Plague plague, List<TownspersonMob> villagers,
                                       boolean quarantined, long now, RandomSource rng) {
        if (plague.isExpired(now)) {
            hdata.putPlague(plague.resolve(now));
            archivePlagueResolved(level, village, plague, now);
            return;
        }
        float reduction = quarantined ? QUARANTINE_REDUCTION : 1f;
        int newInfections = 0;
        for (TownspersonMob npc : villagers) {
            HealthComponent h = npc.getHealthComponent();
            if (h.hasCondition(HealthCondition.PLAGUE_CARRIER)) continue;
            float chance = PLAGUE_INFECTION_RATE * reduction
                    * constitutionMultiplier(h.constitution());
            if (rng.nextFloat() < chance) {
                addCondition(npc, HealthCondition.PLAGUE_CARRIER, now);
                newInfections++;
            }
        }
        if (newInfections > 0) {
            hdata.putPlague(plague.withCounts(
                    plague.infectedCount() + newInfections,
                    plague.recoveredCount(), plague.diedCount()));
        }
        // Resolution check — zero infections remaining.
        long remaining = villagers.stream()
                .filter(n -> n.getHealthComponent().hasCondition(HealthCondition.PLAGUE_CARRIER))
                .count();
        if (remaining <= 0) {
            Plague resolved = plague.resolve(now);
            hdata.putPlague(resolved);
            archivePlagueResolved(level, village, resolved, now);
        }
    }

    /** Phase 4 doc 30 archival hook for the auto-resolve paths. The
     *  manual {@code PlagueScheduler.resolve} fires its own copy. */
    private static void archivePlagueResolved(ServerLevel level, Village village,
                                              Plague plague, long now) {
        long durationDays = Math.max(0L, (now - plague.startTick()) / 24000L);
        java.util.Map<String, String> details = new java.util.LinkedHashMap<>();
        details.put("village_name", village.getName());
        details.put("duration_days", Long.toString(durationDays));
        details.put("death_count", Integer.toString(plague.diedCount()));
        tterrag1112.life_in_the_village.Village.History.HistoryProducer
                .record(level, village,
                        tterrag1112.life_in_the_village.Village.History.HistoryEventType.PLAGUE_RESOLVED,
                        now, details, java.util.List.of());
    }

    private static void applyHealerOverwork(ServerLevel level, HealthSavedData hdata,
                                            Plague plague, List<TownspersonMob> villagers,
                                            long now, RandomSource rng) {
        UUID healerId = plague.healerOnDuty().orElse(null);
        if (healerId == null) {
            // No on-duty healer — pick the first HEALER profession in
            // the village and pin them.
            for (TownspersonMob npc : villagers) {
                if (npc.getProfession() == tterrag1112.life_in_the_village.Profession.Profession.HEALER) {
                    hdata.putPlague(plague.withHealer(npc.getUUID()));
                    return;
                }
            }
            return;
        }
        TownspersonMob healer = TownspersonMob.findByUUID(level, healerId).orElse(null);
        if (healer == null || !healer.isAlive()) {
            // Healer is gone — clear the on-duty pointer; the plague
            // will continue without medical leadership.
            hdata.putPlague(plague.withHealer(null));
            return;
        }
        if (rng.nextFloat() < HEALER_PLAGUE_MORTALITY) {
            healer.hurt(level.damageSources().generic(), Float.MAX_VALUE);
            hdata.putPlague(plague.withHealer(null));
            LOGGER.info("[Plague] Healer {} died of plague exposure in {}",
                    healerId, plague.villageId());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Convenience: assemble + insert a condition with severity 2 +
     *  spec-default duration. Used by all onset paths. */
    public static void addCondition(TownspersonMob npc, HealthCondition type, long now) {
        addCondition(npc, type, 2, now);
    }

    public static void addCondition(TownspersonMob npc, HealthCondition type,
                                    int severity, long now) {
        long dur = HealthDurations.durationFor(type, severity);
        ActiveCondition ac = new ActiveCondition(type, now, dur, severity, false,
                java.util.Optional.empty());
        boolean changed = npc.getHealthComponent().add(ac);
        if (changed) {
            npc.getMood().applyWithRawMagnitude(MoodTrigger.INJURY_SUSTAINED,
                    MoodTrigger.INJURY_SUSTAINED.defaultMagnitude(), now);
        }
    }

    /** Lower constitution → higher contagion / illness onset chance.
     *  Spec line 86 — constitution-weighted spread. */
    public static float constitutionMultiplier(int constitution) {
        // 100 → 0.5x, 50 → 1.0x, 0 → 1.5x.
        return Math.max(0.5f, Math.min(1.5f, 1.0f - (constitution - 50) * 0.01f));
    }

    private static List<TownspersonMob> villagersOf(ServerLevel level, Village village) {
        AABB box = village.getBounds(VillageSavedData.get(level)).orElse(null);
        if (box == null) return List.of();
        return level.getEntitiesOfClass(TownspersonMob.class, box.inflate(8),
                t -> t.getAssignedVillageName()
                        .map(n -> n.equals(village.getName())).orElse(false));
    }

    private static boolean isWinter(ServerLevel level) {
        // Vanilla doesn't ship seasons; spec line 84 expects them. Use
        // a 384-day calendar split into 4 seasons of 96 days each.
        long dayOfYear = (level.getGameTime() / 24000L) % 384L;
        return dayOfYear >= 288;
    }

    private static boolean isSummer(ServerLevel level) {
        long dayOfYear = (level.getGameTime() / 24000L) % 384L;
        return dayOfYear >= 96 && dayOfYear < 192;
    }

    /** Suppresses unused-import warning on LivingEntity. */
    @SuppressWarnings("unused")
    private static final LivingEntity DOC_HOOK = null;
    /** Suppresses unused-import warning on BlockPos. */
    @SuppressWarnings("unused")
    private static final BlockPos DOC_HOOK_2 = null;
}
