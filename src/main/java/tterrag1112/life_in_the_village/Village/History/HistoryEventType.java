package tterrag1112.life_in_the_village.Village.History;

import com.mojang.serialization.Codec;

import java.util.Locale;

/**
 * Phase 4 doc 30 lines 41-98 — every notable event the village
 * archive can carry. Each entry returns a {@link HistoryImportance}
 * default that the producer can override per-instance.
 *
 * <p>The full list is intentionally generous so Phase 5 producers
 * (festivals, religion ceremonies, treaties) have a typed slot
 * already in place. Producers that haven't shipped yet stay
 * inactive — no consumer side requires every type to fire.</p>
 */
public enum HistoryEventType {
    // ── Life events ───────────────────────────────────────────────────────
    BIRTH                    (HistoryImportance.MINOR),
    DEATH_NATURAL            (HistoryImportance.MINOR),
    DEATH_ACCIDENT           (HistoryImportance.NOTABLE),
    DEATH_VIOLENT            (HistoryImportance.NOTABLE),
    MARRIAGE                 (HistoryImportance.NOTABLE),
    COMING_OF_AGE            (HistoryImportance.MINOR),
    APPRENTICESHIP_STARTED   (HistoryImportance.MINOR),
    APPRENTICESHIP_COMPLETED (HistoryImportance.NOTABLE),

    // ── Office and politics ───────────────────────────────────────────────
    LEADER_ELECTED           (HistoryImportance.NOTABLE),
    LEADER_DEPOSED           (HistoryImportance.MAJOR),
    LEADER_DIED_IN_OFFICE    (HistoryImportance.MAJOR),
    OFFICE_APPOINTED         (HistoryImportance.MINOR),
    OFFICE_VACATED           (HistoryImportance.MINOR),
    LAW_ENACTED              (HistoryImportance.NOTABLE),
    LAW_REPEALED             (HistoryImportance.NOTABLE),

    // ── Crime and justice ─────────────────────────────────────────────────
    CRIME_REPORTED           (HistoryImportance.NOTABLE),
    TRIAL_HELD               (HistoryImportance.MAJOR),
    EXILE_PASSED             (HistoryImportance.MAJOR),
    EXECUTION_CARRIED_OUT    (HistoryImportance.LEGENDARY),

    // ── Economy and infrastructure ────────────────────────────────────────
    BUILDING_CONSTRUCTED     (HistoryImportance.MINOR),
    BUILDING_DESTROYED       (HistoryImportance.NOTABLE),
    COMPANY_FOUNDED          (HistoryImportance.NOTABLE),
    COMPANY_DISSOLVED        (HistoryImportance.NOTABLE),
    MASTERPIECE_CERTIFIED    (HistoryImportance.MAJOR),
    BOOK_AUTHORED            (HistoryImportance.NOTABLE),

    // ── Religion ──────────────────────────────────────────────────────────
    TEMPLE_CONSECRATED       (HistoryImportance.MAJOR),
    HIGH_PRIEST_ANOINTED     (HistoryImportance.NOTABLE),
    FEAST_DAY_CELEBRATED     (HistoryImportance.MINOR),

    // ── Village-wide ──────────────────────────────────────────────────────
    FESTIVAL_HELD            (HistoryImportance.MINOR),
    PLAGUE_OUTBREAK          (HistoryImportance.MAJOR),
    PLAGUE_RESOLVED          (HistoryImportance.MAJOR),
    FAMINE                   (HistoryImportance.MAJOR),
    SEASON_FIRST_HARVEST     (HistoryImportance.MINOR),
    VILLAGE_FOUNDED          (HistoryImportance.LEGENDARY),

    // ── Visitors and diplomacy ────────────────────────────────────────────
    FAMOUS_VISITOR           (HistoryImportance.NOTABLE),
    ENVOY_RECEIVED           (HistoryImportance.NOTABLE),
    TREATY_SIGNED            (HistoryImportance.MAJOR),
    CARAVAN_LOST             (HistoryImportance.NOTABLE),

    // ── Kingdom-linked ────────────────────────────────────────────────────
    KINGDOM_EVENT            (HistoryImportance.NOTABLE);

    private final HistoryImportance defaultImportance;

    HistoryEventType(HistoryImportance defaultImportance) {
        this.defaultImportance = defaultImportance;
    }

    public HistoryImportance defaultImportance() { return defaultImportance; }

    /** True when entries of this type should propagate to the
     *  kingdom-level history feed per spec lines 213-220. */
    public boolean propagatesToKingdom() {
        return switch (this) {
            case VILLAGE_FOUNDED, LEADER_ELECTED, LEADER_DEPOSED, LEADER_DIED_IN_OFFICE,
                 PLAGUE_OUTBREAK, PLAGUE_RESOLVED, TREATY_SIGNED, ENVOY_RECEIVED,
                 EXECUTION_CARRIED_OUT, FAMINE, KINGDOM_EVENT,
                 BOOK_AUTHORED, MASTERPIECE_CERTIFIED -> true;
            default -> false;
        };
    }

    public static final Codec<HistoryEventType> CODEC = Codec.STRING.xmap(
            s -> HistoryEventType.valueOf(s.toUpperCase(Locale.ROOT)),
            HistoryEventType::name);
}
