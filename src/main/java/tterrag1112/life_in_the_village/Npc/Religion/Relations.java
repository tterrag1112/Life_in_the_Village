package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * F1b sub-stage 2 — the interreligious <b>relation</b> resolver, the one home for
 * the stance between two faiths. Mirrors {@link Religions}: a thin static facade over
 * the per-world {@link ReligionSavedData} (which holds the override map).
 *
 * <p><b>Override-or-derived.</b> {@link #relation} returns an explicit per-world
 * override if one is set ({@link #setRelation}), else the <b>derived baseline</b>:
 * {@link RelationStance#KINDRED} when the two religions share ≥1 god (resolved via
 * {@link GodRegistry#godsFor}), else {@link RelationStance#NEUTRAL}. The baseline is
 * computed fresh from current god overlap every call, so it is never stale; the
 * override is the persisted, later-written exception (dynamism / kingdom conflict).
 * Symmetric — {@code relation(a,b) == relation(b,a)}.</p>
 */
public final class Relations {

    private Relations() {}

    /**
     * The stance between {@code idA} and {@code idB}: the per-world override if set,
     * otherwise the derived god-overlap baseline. Symmetric. NEUTRAL for an unknown
     * religion id (nothing to relate).
     */
    public static RelationStance relation(ServerLevel level, String idA, String idB) {
        if (level == null || idA == null || idB == null) return RelationStance.NEUTRAL;
        Optional<RelationStance> override = ReligionSavedData.get(level).relationOverride(idA, idB);
        return override.orElseGet(() -> derive(level, idA, idB));
    }

    /**
     * The DERIVED baseline (no override consulted) — the single home of the
     * kindred/neutral rule. KINDRED when the two religions share at least one god,
     * else NEUTRAL. A religion relates to itself as KINDRED when it has any god.
     */
    public static RelationStance derive(ServerLevel level, String idA, String idB) {
        Religion a = Religions.get(level, idA);
        Religion b = Religions.get(level, idB);
        if (a == null || b == null) return RelationStance.NEUTRAL;
        Set<String> godsA = new HashSet<>();
        for (God g : GodRegistry.godsFor(a)) godsA.add(g.id());
        for (God g : GodRegistry.godsFor(b)) {
            if (godsA.contains(g.id())) return RelationStance.KINDRED;
        }
        return RelationStance.NEUTRAL;
    }

    /**
     * Writes a per-world stance override (canonical sorted-pair key) + persists. The
     * mutation seam for later systems (kingdom conflict, dynamism, condemned gods) —
     * <b>no caller this stage</b>, like {@link ReligionSavedData#put}. The stance is
     * stored verbatim, so an explicit NEUTRAL is a deliberate override (e.g. a schism
     * suppressing a derived KINDRED), distinct from the absence of any override.
     */
    public static void setRelation(ServerLevel level, String idA, String idB,
                                   RelationStance stance) {
        if (level == null || idA == null || idB == null || stance == null) return;
        ReligionSavedData.get(level).setRelationOverride(idA, idB, stance);
    }
}
