package tterrag1112.life_in_the_village.Quests;

/**
 * F2a-1 — the payload a {@link QuestEvents#notify} call carries, so an {@link Objective}
 * can decide whether the event advances it. Kept small + general: the {@code kind} plus
 * the religion/god the event concerns. The SOURCE resolves the god (e.g. an offering's
 * religion → its primary god) so the matcher needs no world lookup. Future kinds add
 * fields as their concrete consumers need them.
 */
public record QuestContext(QuestEventKind kind, String godId, String religionId) {

    public static QuestContext offering(String godId, String religionId) {
        return new QuestContext(QuestEventKind.OFFERING, godId, religionId);
    }

    /** F2a-2 — the player prayed at {@code godId}'s sacred site (a saint grave). */
    public static QuestContext visitSite(String godId) {
        return new QuestContext(QuestEventKind.VISIT_SITE, godId, null);
    }

    /** F2a-2 — the player enshrined a relic of {@code godId}'s faith. */
    public static QuestContext enshrine(String godId) {
        return new QuestContext(QuestEventKind.ENSHRINE, godId, null);
    }

    /** F2a-2 — the player attended/commissioned a rite of {@code religionId} ({@code
     *  godId} resolved by the source). */
    public static QuestContext rite(String godId, String religionId) {
        return new QuestContext(QuestEventKind.RITE, godId, religionId);
    }
}
