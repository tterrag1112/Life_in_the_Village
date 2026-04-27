package tterrag1112.life_in_the_village.Npc.Health;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * Weekly per-village plague roll. Spec line 169.
 *
 * <p>v1 ships a flat baseline chance per week (0.4%) modulated by
 * village size — bigger settlements hit thresholds for traffic /
 * sanitation that raise the rate. Phase 4 swaps the baseline for a
 * visitor-flux integration ("higher probability if visitor from
 * plague-affected region").</p>
 */
public final class PlagueScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Spec line 169 — "Random low-probability per village per year".
     *  At 0.4%/week that's ~19% per year per village; tunable. */
    private static final float WEEKLY_BASE_CHANCE = 0.004f;

    private PlagueScheduler() {}

    public static void weeklyRoll(ServerLevel level) {
        VillageSavedData vdata = VillageSavedData.get(level);
        HealthSavedData hdata  = HealthSavedData.get(level);
        long now = level.getGameTime();
        for (Village v : vdata.getAllVillages()) {
            if (hdata.getActivePlague(v.getId()).isPresent()) continue;
            int population = v.getBuildingIds().size(); // proxy for size
            float chance = WEEKLY_BASE_CHANCE * (1f + 0.05f * population);
            if (level.getRandom().nextFloat() < chance) {
                start(level, hdata, v, now);
            }
        }
    }

    /** Manual trigger for /plague start + spec test paths. */
    public static Plague start(ServerLevel level, Village village, long currentTick) {
        HealthSavedData hdata = HealthSavedData.get(level);
        return start(level, hdata, village, currentTick);
    }

    private static Plague start(ServerLevel level, HealthSavedData hdata,
                                Village village, long now) {
        Plague plague = Plague.start(village.getId(), now);
        // Seed with a single PLAGUE_CARRIER if the village has any
        // villagers; otherwise the plague resolves immediately on the
        // next daily tick.
        var carriers = level.getEntitiesOfClass(
                tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                village.getBounds(VillageSavedData.get(level))
                        .map(b -> b.inflate(8))
                        .orElseThrow(() -> new IllegalStateException("village has no bounds"))
        );
        if (!carriers.isEmpty()) {
            HealthTicker.addCondition(carriers.get(0), HealthCondition.PLAGUE_CARRIER, now);
            plague = plague.withCounts(1, 0, 0);
        }
        hdata.putPlague(plague);
        LOGGER.info("[Plague] Outbreak started in village {}", village.getName());

        // Phase 5 doc 32: also schedule a PLAGUE_OUTBREAK event so the
        // event subsystem (history, mood, gossip, eventOverride on
        // affected NPCs) treats this as a first-class crisis event.
        // Best-effort — health subsystem still owns the simulation.
        try {
            java.util.List<java.util.UUID> requiredIds = new java.util.ArrayList<>();
            if (!carriers.isEmpty()) requiredIds.add(carriers.get(0).getUUID());
            tterrag1112.life_in_the_village.Village.Event.VillageEventScheduler
                    .scheduleLifeEvent(level, village,
                            tterrag1112.life_in_the_village.Village.Event.VillageEvent.EventType.PLAGUE_OUTBREAK,
                            now, null, requiredIds, java.util.List.of());
        } catch (RuntimeException ex) {
            LOGGER.warn("[Plague] failed to schedule PLAGUE_OUTBREAK event: {}", ex.getMessage());
        }

        // Phase 4 doc 30 archival hook.
        java.util.Map<String, String> details = new java.util.LinkedHashMap<>();
        details.put("village_name", village.getName());
        tterrag1112.life_in_the_village.Village.History.HistoryProducer
                .record(level, village,
                        tterrag1112.life_in_the_village.Village.History.HistoryEventType.PLAGUE_OUTBREAK,
                        now, details, java.util.List.of());
        return plague;
    }

    /** Manual stop. */
    public static void resolve(ServerLevel level, java.util.UUID villageId) {
        HealthSavedData hdata = HealthSavedData.get(level);
        long now = level.getGameTime();
        hdata.getActivePlague(villageId).ifPresent(p -> {
            hdata.putPlague(p.resolve(now));
            // Phase 4 doc 30 archival hook.
            Village village = VillageSavedData.get(level).getVillageById(villageId).orElse(null);
            if (village != null) {
                long durationDays = Math.max(0L, (now - p.startTick()) / 24000L);
                java.util.Map<String, String> details = new java.util.LinkedHashMap<>();
                details.put("village_name", village.getName());
                details.put("duration_days", Long.toString(durationDays));
                details.put("death_count", Integer.toString(p.diedCount()));
                tterrag1112.life_in_the_village.Village.History.HistoryProducer
                        .record(level, village,
                                tterrag1112.life_in_the_village.Village.History.HistoryEventType.PLAGUE_RESOLVED,
                                now, details, java.util.List.of());
            }
        });
    }
}
