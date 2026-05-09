package tterrag1112.life_in_the_village.Npc.Nobility;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.SuccessionRule;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Track D3.2b — culture-driven heir resolution. Reads
 * {@link SuccessionRule} from the predecessor's culture and walks
 * the predecessor's children list, applying the rule's filter +
 * ordering to produce the heir.
 *
 * <p>Pure helper — no state, no side effects. Consumers:
 * <ul>
 *   <li>{@code HereditarySelection.pickHeir} (kingdom_king + any
 *       hereditary office) — replaces the prior "eldest adult child,
 *       any gender" hard-coded behaviour.</li>
 *   <li>{@code NobilityEventDispatcher.onFamilyDeath} — when a
 *       house head dies, hand off the dynasty house to the heir
 *       using the same rule the kingdom would use.</li>
 * </ul>
 *
 * <p>For ELECTIVE / COUNCIL / DIVINE_DESIGNATION rules, returns
 * {@link Optional#empty()} — the caller should fall back to the
 * existing {@code CouncilSelection} / {@code MeritocraticSelection}
 * path, which approximates the ELECTIVE outcome until D3.3 wires
 * province-tier electors. Documented per
 * {@code KINGDOM_PROGRESS.md}.
 */
public final class SuccessionResolver {

    private SuccessionResolver() {}

    /**
     * Picks the heir to {@code predecessor} per the predecessor's
     * culture's {@link SuccessionRule}. Returns empty when the rule
     * doesn't ladder through children (ELECTIVE / COUNCIL / DIVINE)
     * or when no eligible child exists.
     */
    public static Optional<TownspersonMob> pickHeir(ServerLevel level,
                                                    TownspersonMob predecessor) {
        if (predecessor == null) return Optional.empty();
        Culture culture = CultureResolver.of(predecessor);
        SuccessionRule rule = culture.kingdomDefaults().successionRule();
        return pickHeir(level, predecessor, rule);
    }

    /** Variant taking an explicit {@link SuccessionRule}. */
    public static Optional<TownspersonMob> pickHeir(ServerLevel level,
                                                    TownspersonMob predecessor,
                                                    SuccessionRule rule) {
        if (predecessor == null || rule == null) return Optional.empty();

        return switch (rule) {
            case PRIMOGENITURE         -> eldestChild(level, predecessor, /*malesOnly=*/false);
            case AGNATIC_PRIMOGENITURE -> eldestChild(level, predecessor, /*malesOnly=*/true);
            case SEMI_SALIC            -> semiSalic(level, predecessor);
            case ELECTIVE,
                 COUNCIL,
                 DIVINE_DESIGNATION    -> Optional.empty();
        };
    }

    // ── Rule implementations ────────────────────────────────────────────────

    private static Optional<TownspersonMob> eldestChild(ServerLevel level,
                                                        TownspersonMob predecessor,
                                                        boolean malesOnly) {
        return loadChildren(level, predecessor)
                .filter(TownspersonMob::isAdult)
                .filter(c -> !malesOnly || c.isMale())
                .max(Comparator.comparingInt(TownspersonMob::getAge));
    }

    /**
     * SEMI_SALIC: eldest son first; if no son survives, daughters
     * are bypassed in favour of grandsons (eldest grandson via the
     * eldest-daughter line). v1 grandson resolution: eldest grandson
     * across all daughter-lines, since the dynasty tree GUI only
     * surfaces 1-2 generations deep this phase.
     */
    private static Optional<TownspersonMob> semiSalic(ServerLevel level,
                                                      TownspersonMob predecessor) {
        Optional<TownspersonMob> son = eldestChild(level, predecessor, /*malesOnly=*/true);
        if (son.isPresent()) return son;
        // No surviving son. Walk daughters' children for a grandson.
        List<TownspersonMob> daughters = loadChildren(level, predecessor)
                .filter(c -> !c.isMale())
                .toList();
        return daughters.stream()
                .flatMap(d -> loadChildren(level, d))
                .filter(TownspersonMob::isAdult)
                .filter(TownspersonMob::isMale)
                .max(Comparator.comparingInt(TownspersonMob::getAge));
    }

    private static Stream<TownspersonMob> loadChildren(ServerLevel level,
                                                       TownspersonMob parent) {
        return parent.getFamily().getChildrenIds().stream()
                .map(cid -> TownspersonMob.findByUUID(level, cid))
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    // ── Inspection helper ───────────────────────────────────────────────────

    /**
     * True for rules that walk a hereditary line. Used by callers
     * deciding whether to consult the resolver vs. fall through to
     * a council / electoral selection engine.
     */
    public static boolean isHereditary(SuccessionRule rule) {
        return rule == SuccessionRule.PRIMOGENITURE
                || rule == SuccessionRule.AGNATIC_PRIMOGENITURE
                || rule == SuccessionRule.SEMI_SALIC;
    }
}
