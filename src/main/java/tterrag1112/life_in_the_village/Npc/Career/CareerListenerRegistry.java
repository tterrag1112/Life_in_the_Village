package tterrag1112.life_in_the_village.Npc.Career;

import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Profession.Profession;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 6.3.3.a — runtime registry of career-transition listeners.
 *
 * <p>Listeners are behavior, not data — registered at mod init by
 * content packs / culture rules / system modules. The registry is
 * in-memory only; nothing persists.
 *
 * <h3>Lookup</h3>
 * <ul>
 *   <li>Profession change: target-profession-specific listeners +
 *       {@link #ANY_PROFESSION} wildcard listeners are returned in
 *       registration order. The wildcard set fires after the targeted
 *       set, mirroring the per-key + global pattern from
 *       {@link tterrag1112.life_in_the_village.Npc.Office.OfficeEligibilityRegistry}.</li>
 *   <li>Lifestage: target-stage-specific listeners +
 *       {@link #ANY_LIFESTAGE} wildcard.</li>
 *   <li>Graduation: single list (no scoping needed; all graduations
 *       share the same shape).</li>
 * </ul>
 */
public final class CareerListenerRegistry {

    private CareerListenerRegistry() {}

    /** Wildcard key for profession-change listeners that observe every target. */
    @Nullable public static final Profession ANY_PROFESSION = null;
    /** Wildcard key for lifestage listeners that observe every transition. */
    @Nullable public static final LifeStage ANY_LIFESTAGE = null;

    private static final Map<Profession, List<ProfessionChangeListener>> PROFESSION_LISTENERS =
            new EnumMap<>(Profession.class);
    private static final List<ProfessionChangeListener> ANY_PROFESSION_LISTENERS = new ArrayList<>();

    private static final Map<LifeStage, List<LifeStageTransitionListener>> LIFESTAGE_LISTENERS =
            new EnumMap<>(LifeStage.class);
    private static final List<LifeStageTransitionListener> ANY_LIFESTAGE_LISTENERS = new ArrayList<>();

    private static final List<ApprenticeshipGraduationListener> GRADUATION_LISTENERS = new ArrayList<>();

    // ── Registration ────────────────────────────────────────────────────────

    public static void registerProfessionListener(@Nullable Profession target,
                                                  ProfessionChangeListener listener) {
        if (listener == null) return;
        if (target == null) ANY_PROFESSION_LISTENERS.add(listener);
        else PROFESSION_LISTENERS.computeIfAbsent(target, k -> new ArrayList<>()).add(listener);
    }

    public static void registerLifeStageListener(@Nullable LifeStage target,
                                                 LifeStageTransitionListener listener) {
        if (listener == null) return;
        if (target == null) ANY_LIFESTAGE_LISTENERS.add(listener);
        else LIFESTAGE_LISTENERS.computeIfAbsent(target, k -> new ArrayList<>()).add(listener);
    }

    public static void registerGraduationListener(ApprenticeshipGraduationListener listener) {
        if (listener == null) return;
        GRADUATION_LISTENERS.add(listener);
    }

    // ── Resolution ──────────────────────────────────────────────────────────

    /** Targeted listeners first, then wildcard. Immutable views. */
    public static List<ProfessionChangeListener> resolveProfession(Profession target) {
        List<ProfessionChangeListener> targeted = PROFESSION_LISTENERS.getOrDefault(target, List.of());
        if (targeted.isEmpty() && ANY_PROFESSION_LISTENERS.isEmpty()) return List.of();
        List<ProfessionChangeListener> combined =
                new ArrayList<>(targeted.size() + ANY_PROFESSION_LISTENERS.size());
        combined.addAll(targeted);
        combined.addAll(ANY_PROFESSION_LISTENERS);
        return Collections.unmodifiableList(combined);
    }

    public static List<LifeStageTransitionListener> resolveLifeStage(LifeStage target) {
        List<LifeStageTransitionListener> targeted = LIFESTAGE_LISTENERS.getOrDefault(target, List.of());
        if (targeted.isEmpty() && ANY_LIFESTAGE_LISTENERS.isEmpty()) return List.of();
        List<LifeStageTransitionListener> combined =
                new ArrayList<>(targeted.size() + ANY_LIFESTAGE_LISTENERS.size());
        combined.addAll(targeted);
        combined.addAll(ANY_LIFESTAGE_LISTENERS);
        return Collections.unmodifiableList(combined);
    }

    public static List<ApprenticeshipGraduationListener> resolveGraduation() {
        return Collections.unmodifiableList(GRADUATION_LISTENERS);
    }

    /** Test / debug — drops every registered listener. */
    public static void clearAll() {
        PROFESSION_LISTENERS.clear();
        ANY_PROFESSION_LISTENERS.clear();
        LIFESTAGE_LISTENERS.clear();
        ANY_LIFESTAGE_LISTENERS.clear();
        GRADUATION_LISTENERS.clear();
    }
}
