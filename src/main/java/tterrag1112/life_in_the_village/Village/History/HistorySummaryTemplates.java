package tterrag1112.life_in_the_village.Village.History;

import java.util.EnumMap;
import java.util.Map;

/**
 * Phase 4 doc 30 lines 176-189 — string-template registry for the
 * one-line summary rendered onto each {@link HistoryEntry}. v1
 * ships English templates directly; spec line 188-189 leaves
 * culture-localised templates for Phase 5.
 *
 * <p>Token format is the spec's {@code {placeholder}} substitution.
 * Missing keys leave the placeholder text intact rather than
 * throwing, so a producer that forgets a detail key produces a
 * slightly ugly entry instead of a crash.</p>
 */
public final class HistorySummaryTemplates {

    private static final Map<HistoryEventType, String> TEMPLATES = build();
    private static final String FALLBACK = "A notable event occurred: {type}";

    private HistorySummaryTemplates() {}

    public static String render(HistoryEventType type, Map<String, String> details) {
        if (type == null) return "";
        String template = TEMPLATES.getOrDefault(type, FALLBACK);
        if (details == null) details = Map.of();
        Map<String, String> merged = new java.util.LinkedHashMap<>(details);
        merged.putIfAbsent("type", type.name());
        return substitute(template, merged);
    }

    private static String substitute(String template, Map<String, String> details) {
        StringBuilder out = new StringBuilder(template.length() + 32);
        int i = 0;
        int n = template.length();
        while (i < n) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i);
                if (end < 0) { out.append(template.substring(i)); break; }
                String key = template.substring(i + 1, end);
                String val = details.get(key);
                if (val != null) out.append(val);
                else out.append('{').append(key).append('}'); // leave unresolved
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static Map<HistoryEventType, String> build() {
        EnumMap<HistoryEventType, String> m = new EnumMap<>(HistoryEventType.class);

        // ── Life events ───────────────────────────────────────────────────
        m.put(HistoryEventType.BIRTH,                "{npc_name} was born to {family_name} of {village_name}.");
        m.put(HistoryEventType.DEATH_NATURAL,        "{npc_name} died peacefully in {village_name}.");
        m.put(HistoryEventType.DEATH_ACCIDENT,       "{npc_name} died in an accident at {village_name}.");
        m.put(HistoryEventType.DEATH_VIOLENT,        "{npc_name} was killed at {village_name}.");
        m.put(HistoryEventType.MARRIAGE,             "{spouse_a} and {spouse_b} were wed at {village_name}.");
        m.put(HistoryEventType.COMING_OF_AGE,        "{npc_name} came of age in {village_name}.");
        m.put(HistoryEventType.APPRENTICESHIP_STARTED,
                "{apprentice_name} began apprenticeship under {master_name}.");
        m.put(HistoryEventType.APPRENTICESHIP_COMPLETED,
                "{apprentice_name} completed apprenticeship under {master_name}.");

        // ── Office and politics ──────────────────────────────────────────
        m.put(HistoryEventType.LEADER_ELECTED,       "{npc_name} became leader of {village_name}.");
        m.put(HistoryEventType.LEADER_DEPOSED,       "{npc_name} was deposed as leader of {village_name}.");
        m.put(HistoryEventType.LEADER_DIED_IN_OFFICE,
                "Leader {npc_name} of {village_name} died in office.");
        m.put(HistoryEventType.OFFICE_APPOINTED,     "{npc_name} was appointed {office_name} of {village_name}.");
        m.put(HistoryEventType.OFFICE_VACATED,       "{npc_name} vacated the {office_name} of {village_name}.");
        m.put(HistoryEventType.LAW_ENACTED,          "{leader_name} enacted the law of {law_name}.");
        m.put(HistoryEventType.LAW_REPEALED,         "{leader_name} repealed the law of {law_name}.");

        // ── Crime and justice ────────────────────────────────────────────
        m.put(HistoryEventType.CRIME_REPORTED,       "A {crime_type} was reported at {village_name}.");
        m.put(HistoryEventType.TRIAL_HELD,           "Trial of {accused_name} for {crime_type} ended in {verdict}.");
        m.put(HistoryEventType.EXILE_PASSED,         "{accused_name} was exiled from {village_name}.");
        m.put(HistoryEventType.EXECUTION_CARRIED_OUT,"{accused_name} was executed at {village_name}.");

        // ── Economy and infrastructure ───────────────────────────────────
        m.put(HistoryEventType.BUILDING_CONSTRUCTED, "A new {building_type} was raised in {village_name}.");
        m.put(HistoryEventType.BUILDING_DESTROYED,   "The {building_type} of {village_name} was destroyed.");
        m.put(HistoryEventType.BUSINESS_FOUNDED,      "{npc_name} founded {company_name} at {village_name}.");
        m.put(HistoryEventType.BUSINESS_DISSOLVED,    "{company_name} was dissolved at {village_name}.");
        m.put(HistoryEventType.MASTERPIECE_CERTIFIED,
                "{apprentice_name} certified their masterpiece — a {item_type} — under Master {master_name}.");
        m.put(HistoryEventType.BOOK_AUTHORED,        "Scholar {npc_name} finished '{book_title}'.");

        // ── Religion ─────────────────────────────────────────────────────
        m.put(HistoryEventType.TEMPLE_CONSECRATED,   "The temple of {village_name} was consecrated.");
        m.put(HistoryEventType.HIGH_PRIEST_ANOINTED, "{npc_name} was anointed high priest of {village_name}.");
        m.put(HistoryEventType.FEAST_DAY_CELEBRATED, "{village_name} celebrated {feast_name}.");

        // ── Village-wide ─────────────────────────────────────────────────
        m.put(HistoryEventType.FESTIVAL_HELD,        "{village_name} held the {festival_name}.");
        m.put(HistoryEventType.PLAGUE_OUTBREAK,      "Plague broke out in {village_name}.");
        m.put(HistoryEventType.PLAGUE_RESOLVED,
                "The plague of {village_name} ended after {duration_days} days. {death_count} perished.");
        m.put(HistoryEventType.FAMINE,               "Famine struck {village_name}.");
        m.put(HistoryEventType.SEASON_FIRST_HARVEST, "{village_name} brought in the first harvest of the year.");
        m.put(HistoryEventType.VILLAGE_FOUNDED,      "{village_name} was founded.");

        // ── Visitors and diplomacy ───────────────────────────────────────
        m.put(HistoryEventType.FAMOUS_VISITOR,
                "The renowned {visitor_role} {visitor_name} visited {village_name}.");
        m.put(HistoryEventType.ENVOY_RECEIVED,       "An envoy from {origin_village} arrived at {village_name}.");
        m.put(HistoryEventType.TREATY_SIGNED,        "{village_name} signed a treaty with {partner}.");
        m.put(HistoryEventType.CARAVAN_LOST,         "A caravan from {village_name} was lost.");

        m.put(HistoryEventType.KINGDOM_EVENT,        "Kingdom-wide: {summary}");
        return m;
    }
}
