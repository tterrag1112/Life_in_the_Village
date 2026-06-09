package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity.HistoryEvent;
import tterrag1112.life_in_the_village.Village.History.HistoryEventType;
import tterrag1112.life_in_the_village.Village.History.HistoryImportance;
import tterrag1112.life_in_the_village.Village.History.VillageHistoryLog;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Religion Deepening D3c — gives a faith's authored sacred history (D1
 * {@link ReligionIdentity.SacredHistory}: founding myth + ordered key events)
 * <b>presence in the world's chronicle</b>. When a village's faith is established
 * (the first religious gathering it schedules), its founding myth and key events
 * are seeded — once — into the village {@link VillageHistoryLog} as
 * {@link HistoryEventType#SACRED_HISTORY} entries, so reading the village's history
 * includes its religious origins (distinct per faith).
 *
 * <p>Reuses the existing history system (no parallel store): one new typed slot
 * ({@code SACRED_HISTORY}, MAJOR → never pruned) carrying the lore text in the
 * entry's {@code summary}. Bounded — the founding myth plus the faith's key events
 * (≤ {@link #MAX_EVENTS}), seeded once and never again (idempotency guard), so it
 * never floods the live chronicle.</p>
 */
public final class FaithHistory {

    private FaithHistory() {}

    /** Cap on seeded key events (in addition to the founding myth) — the authored
     *  faiths carry three; the cap guards against an over-long future identity. */
    private static final int MAX_EVENTS = 4;

    /**
     * Seeds {@code religionId}'s founding myth + key events into {@code village}'s
     * history log, once. No-op (returns 0) when the village already carries sacred
     * history (idempotent), when the faith has no authored identity, or on bad
     * input. Entries are MAJOR (never pruned) and use staggered ticks so the
     * tail-dedupe in {@link VillageHistoryLog#add} keeps them all.
     *
     * @return the number of entries actually seeded.
     */
    public static int seedSacredHistory(ServerLevel level, Village village,
                                        String religionId, long now) {
        if (level == null || village == null || religionId == null) return 0;
        ReligionIdentity id = ReligionIdentity.get(religionId);
        if (id == null) return 0;
        VillageHistoryLog log = VillageHistoryLog.get(level);
        // Idempotency — a village's religious origins are written once.
        if (!log.byType(village.getId(), HistoryEventType.SACRED_HISTORY).isEmpty()) return 0;

        Religion religion = ReligionRegistry.get(religionId);
        String faithName = religion != null ? religion.displayName() : religionId;

        int seeded = 0;
        long tick = now;
        // The founding myth — the faith's origin in this village's chronicle.
        if (record(log, village, faithName + " — " + id.history().foundingMyth(), tick)) {
            seeded++;
        }
        // The ordered key events (schisms, saints, foundational moments).
        List<HistoryEvent> events = id.history().events();
        for (int i = 0; i < events.size() && i < MAX_EVENTS; i++) {
            HistoryEvent e = events.get(i);
            tick++;                                            // stagger to dodge tail-dedupe
            if (record(log, village, e.title() + " — " + e.text(), tick)) seeded++;
        }
        return seeded;
    }

    private static boolean record(VillageHistoryLog log, Village village,
                                  String summary, long tick) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("village_name", village.getName());
        details.put("summary", summary);
        return log.record(HistoryEventType.SACRED_HISTORY, village.getId(), tick,
                details, List.of(), HistoryImportance.MAJOR);
    }
}
