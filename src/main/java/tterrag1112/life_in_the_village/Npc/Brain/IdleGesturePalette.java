package tterrag1112.life_in_the_village.Npc.Brain;

import net.minecraft.util.RandomSource;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Time-of-day × mood-band weighted gesture picker.
 *
 * <p>Each (TimeBucket, MoodBand) cell maps to a weight table — a small
 * {@code Map&lt;Gesture, Integer&gt;}. Picking is a uniform-random draw
 * over the summed weights.
 *
 * <p>Spec mapping (Phase 6.1.a §3 / §4):
 * <pre>
 *   0-2000   (early dawn) base: YAWN, STRETCH, LOOK_AROUND
 *   2000-6000 (morning)   base: STRETCH, NOD, WAVE, LOOK_AROUND
 *   6000-7000 (midday)    base: WAVE, FRIENDLY_WAVE, LOOK_AROUND, NOD
 *   7000-11000 (afternoon) base: NOD, WAVE, LOOK_AROUND, LEAN
 *   11000-13000 (evening) base: LEAN, SIGH, LOOK_AROUND
 *   13000-23000 (night/REST): empty — IdleGestureBehavior gates off
 *   23000-24000 (late night): YAWN, SLOUCH, LOOK_AROUND
 * </pre>
 *
 * Mood layers on top: VERY_HIGH boosts FRIENDLY_WAVE / NOD; VERY_LOW
 * boosts HEAD_SHAKE / SIGH / SLOUCH and suppresses expressive ones.
 */
public final class IdleGesturePalette {

    private IdleGesturePalette() {}

    /** Day-time buckets — closed-open intervals on [0, 24000). */
    public enum TimeBucket {
        EARLY_DAWN, MORNING, MIDDAY, AFTERNOON, EVENING, NIGHT, LATE_NIGHT;

        public static TimeBucket of(long dayTime) {
            long t = ((dayTime % 24000L) + 24000L) % 24000L;
            if (t < 2000)  return EARLY_DAWN;
            if (t < 6000)  return MORNING;
            if (t < 7000)  return MIDDAY;
            if (t < 11000) return AFTERNOON;
            if (t < 13000) return EVENING;
            if (t < 23000) return NIGHT;
            return LATE_NIGHT;
        }
    }

    private static final Map<TimeBucket, Map<Gesture, Integer>> BASE = new EnumMap<>(TimeBucket.class);

    static {
        BASE.put(TimeBucket.EARLY_DAWN, Map.of(
                Gesture.YAWN, 3,
                Gesture.STRETCH, 3,
                Gesture.LOOK_AROUND, 2));
        BASE.put(TimeBucket.MORNING, Map.of(
                Gesture.STRETCH, 2,
                Gesture.NOD, 2,
                Gesture.WAVE, 2,
                Gesture.LOOK_AROUND, 2));
        BASE.put(TimeBucket.MIDDAY, Map.of(
                Gesture.WAVE, 2,
                Gesture.FRIENDLY_WAVE, 2,
                Gesture.LOOK_AROUND, 2,
                Gesture.NOD, 2));
        BASE.put(TimeBucket.AFTERNOON, Map.of(
                Gesture.NOD, 2,
                Gesture.WAVE, 2,
                Gesture.LOOK_AROUND, 2,
                Gesture.LEAN, 1));
        BASE.put(TimeBucket.EVENING, Map.of(
                Gesture.LEAN, 3,
                Gesture.SIGH, 2,
                Gesture.LOOK_AROUND, 2));
        BASE.put(TimeBucket.NIGHT, Map.of());
        BASE.put(TimeBucket.LATE_NIGHT, Map.of(
                Gesture.YAWN, 3,
                Gesture.SLOUCH, 2,
                Gesture.LOOK_AROUND, 1));
    }

    /** Gestures classified as "expressive" — boosted by HIGH/VERY_HIGH,
     *  suppressed by VERY_LOW. */
    private static final Set<Gesture> EXPRESSIVE = EnumSet.of(
            Gesture.FRIENDLY_WAVE, Gesture.WAVE, Gesture.NOD);

    /** Gestures classified as "subdued" — boosted by LOW/VERY_LOW,
     *  suppressed by VERY_HIGH. */
    private static final Set<Gesture> SUBDUED = EnumSet.of(
            Gesture.HEAD_SHAKE, Gesture.SIGH, Gesture.SLOUCH);

    /** Short "acknowledgment" pool — what GreetingAcknowledgmentBehavior
     *  draws from. Independent of time-of-day; mood-band still applies. */
    private static final Set<Gesture> ACK_GESTURES = EnumSet.of(
            Gesture.NOD, Gesture.WAVE, Gesture.FRIENDLY_WAVE);

    /**
     * Picks an idle gesture, or null if the current bucket is empty
     * (e.g. NIGHT — REST hours).
     */
    public static Gesture pickIdle(long dayTime, MoodBand band, RandomSource rng) {
        return pick(BASE.get(TimeBucket.of(dayTime)), band, rng);
    }

    /** Picks a short acknowledgment gesture. Always non-null. */
    public static Gesture pickAcknowledgment(MoodBand band, RandomSource rng) {
        EnumMap<Gesture, Integer> pool = new EnumMap<>(Gesture.class);
        for (Gesture g : ACK_GESTURES) pool.put(g, 2);
        // VERY_HIGH unlocks FRIENDLY_WAVE more strongly; VERY_LOW would
        // not use this entry path (greeting is dampened, but the spec
        // doesn't gate the behavior on mood — let it fire with a flat
        // NOD-heavy mix).
        Gesture picked = pick(pool, band, rng);
        return picked != null ? picked : Gesture.NOD;
    }

    /** Mood-weighted weighted-random pick. Null if pool is empty. */
    private static Gesture pick(Map<Gesture, Integer> base, MoodBand band, RandomSource rng) {
        if (base == null || base.isEmpty()) return null;
        EnumMap<Gesture, Integer> weighted = new EnumMap<>(Gesture.class);
        for (var entry : base.entrySet()) {
            int w = applyMood(entry.getKey(), entry.getValue(), band);
            if (w > 0) weighted.put(entry.getKey(), w);
        }
        // VERY_HIGH / VERY_LOW also INJECT bonus gestures that may not
        // be in the time-of-day base. Architectural: mood overrides
        // are additive, not replacement.
        if (band == MoodBand.VERY_HIGH) {
            weighted.merge(Gesture.FRIENDLY_WAVE, 3, Integer::sum);
            weighted.merge(Gesture.NOD, 2, Integer::sum);
        } else if (band == MoodBand.VERY_LOW) {
            weighted.merge(Gesture.HEAD_SHAKE, 3, Integer::sum);
            weighted.merge(Gesture.SIGH, 3, Integer::sum);
            weighted.merge(Gesture.SLOUCH, 2, Integer::sum);
        }
        if (weighted.isEmpty()) return null;

        int total = 0;
        for (int w : weighted.values()) total += w;
        int roll = rng.nextInt(total);
        int acc = 0;
        for (var entry : weighted.entrySet()) {
            acc += entry.getValue();
            if (roll < acc) return entry.getKey();
        }
        return weighted.keySet().iterator().next();
    }

    private static int applyMood(Gesture g, int baseWeight, MoodBand band) {
        return switch (band) {
            case VERY_HIGH -> {
                if (EXPRESSIVE.contains(g)) yield baseWeight * 3;
                if (SUBDUED.contains(g))    yield 0;
                yield baseWeight;
            }
            case HIGH -> {
                if (EXPRESSIVE.contains(g)) yield baseWeight + 1;
                yield baseWeight;
            }
            case NEUTRAL -> baseWeight;
            case LOW -> {
                if (SUBDUED.contains(g))    yield baseWeight + 1;
                yield baseWeight;
            }
            case VERY_LOW -> {
                if (SUBDUED.contains(g))    yield baseWeight * 3;
                if (EXPRESSIVE.contains(g)) yield 0;
                yield baseWeight;
            }
        };
    }
}
