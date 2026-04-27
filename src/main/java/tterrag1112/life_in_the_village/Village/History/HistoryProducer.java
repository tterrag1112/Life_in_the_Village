package tterrag1112.life_in_the_village.Village.History;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4 doc 30 — bridges {@link NpcLifeEvent} fan-out into the
 * village history archive. Producers for non-lifecycle events
 * (plague start, law enacted, company founded, trial verdict) call
 * {@link HistoryProducer#record} directly from their own paths;
 * this dispatcher handles the four lifecycle hooks the
 * {@code NpcLifeEventBus} already routes.
 *
 * <p>Lifecycle archival per spec table line 159:</p>
 * <ul>
 *   <li>{@link NpcLifeEvent.BirthInFamily} → {@code BIRTH}.</li>
 *   <li>{@link NpcLifeEvent.Married}        → {@code MARRIAGE}.</li>
 *   <li>{@link NpcLifeEvent.FamilyDeath}    → {@code DEATH_NATURAL}.</li>
 *   <li>{@link NpcLifeEvent.LifeStageAdvanced} (newStage = ADULT) →
 *   {@code COMING_OF_AGE}.</li>
 * </ul>
 */
public final class HistoryProducer implements EventDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String NAME = "history-producer";

    @Override public String name() { return NAME; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        if (event == null) return;
        TownspersonMob subject = event.subject();
        if (subject == null) return;
        if (!(subject.level() instanceof ServerLevel level)) return;
        Village village = villageOf(subject, level);
        if (village == null) return;
        long now = level.getGameTime();

        if (event instanceof NpcLifeEvent.BirthInFamily birth) {
            Map<String, String> details = baseDetails(subject, village);
            details.put("npc_name", "newborn");
            record(level, village, HistoryEventType.BIRTH, now, details,
                    List.of(subject.getUUID(), birth.childId()));
        } else if (event instanceof NpcLifeEvent.Married married) {
            Map<String, String> details = baseDetails(subject, village);
            details.put("spouse_a", subject.getNpcName());
            details.put("spouse_b", spouseName(level, married.spouseId()));
            record(level, village, HistoryEventType.MARRIAGE, now, details,
                    List.of(subject.getUUID(), married.spouseId()));
        } else if (event instanceof NpcLifeEvent.FamilyDeath death) {
            Map<String, String> details = baseDetails(subject, village);
            details.put("npc_name", spouseName(level, death.deceasedId()));
            record(level, village, HistoryEventType.DEATH_NATURAL, now, details,
                    List.of(death.deceasedId()));
        } else if (event instanceof NpcLifeEvent.LifeStageAdvanced advanced
                && "ADULT".equals(advanced.newStage())) {
            Map<String, String> details = baseDetails(subject, village);
            record(level, village, HistoryEventType.COMING_OF_AGE, now, details,
                    List.of(subject.getUUID()));
        }
    }

    // ── Public producer API ────────────────────────────────────────────────

    /** Records an entry against the supplied village. Producers
     *  outside the lifecycle bus call this directly. Returns
     *  true when the entry was actually appended (not deduped). */
    public static boolean record(ServerLevel level, Village village,
                                 HistoryEventType type, long tick,
                                 Map<String, String> details,
                                 List<UUID> relatedNpcs) {
        if (level == null || village == null || type == null) return false;
        return VillageHistoryLog.get(level)
                .record(type, village.getId(), tick, details, relatedNpcs);
    }

    /** Same as {@link #record} but with an explicit importance
     *  override — used by producers that bump per-instance severity
     *  above the type default (e.g. a SERIOUS-tier crime fires
     *  {@code TRIAL_HELD} at MAJOR but a CAPITAL one bumps to
     *  LEGENDARY). */
    public static boolean record(ServerLevel level, Village village,
                                 HistoryEventType type, long tick,
                                 Map<String, String> details,
                                 List<UUID> relatedNpcs,
                                 HistoryImportance importance) {
        if (level == null || village == null || type == null) return false;
        return VillageHistoryLog.get(level)
                .record(type, village.getId(), tick, details, relatedNpcs, importance);
    }

    /** Convenience: resolve the village by UUID and record. */
    public static boolean recordIn(ServerLevel level, UUID villageId,
                                   HistoryEventType type, long tick,
                                   Map<String, String> details,
                                   List<UUID> relatedNpcs) {
        Village v = VillageSavedData.get(level).getVillageById(villageId).orElse(null);
        if (v == null) return false;
        return record(level, v, type, tick, details, relatedNpcs);
    }

    public static boolean recordIn(ServerLevel level, UUID villageId,
                                   HistoryEventType type, long tick,
                                   Map<String, String> details,
                                   List<UUID> relatedNpcs,
                                   HistoryImportance importance) {
        Village v = VillageSavedData.get(level).getVillageById(villageId).orElse(null);
        if (v == null) return false;
        return record(level, v, type, tick, details, relatedNpcs, importance);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static Map<String, String> baseDetails(TownspersonMob subject, Village village) {
        Map<String, String> details = new LinkedHashMap<>();
        if (subject != null) details.put("npc_name", subject.getNpcName());
        if (village != null) details.put("village_name", village.getName());
        return details;
    }

    private static Village villageOf(TownspersonMob npc, ServerLevel level) {
        return npc.getAssignedVillageName()
                .flatMap(VillageSavedData.get(level)::getVillageByName)
                .orElse(null);
    }

    private static String spouseName(ServerLevel level, UUID id) {
        if (id == null) return "(unknown)";
        Optional<TownspersonMob> mob = TownspersonMob.findByUUID(level, id);
        return mob.map(TownspersonMob::getNpcName).orElse(id.toString().substring(0, 8));
    }
}
