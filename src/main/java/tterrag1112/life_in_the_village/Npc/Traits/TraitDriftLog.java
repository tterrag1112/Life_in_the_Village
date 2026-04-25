package tterrag1112.life_in_the_village.Npc.Traits;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-NPC ledger of cumulative trait drift, the lifetime keys that have
 * already fired, and the last tick at which any drift was recorded.
 * Persisted under {@code npcTraitDrift}.
 *
 * <p>Spec: {@code docs/npc_redesign/10-phase1-integration.md}, section
 * "Trait drift rules". Cap is ±{@link #LIFETIME_CAP_PER_AXIS} per axis;
 * once-per-lifetime events use {@link #hasFired}/{@link #markFired};
 * {@link #lastDriftTick} drives the calm-period drift-back applied
 * daily.</p>
 */
public final class TraitDriftLog {

    /** Per-event drift delta is hard-capped at this magnitude. */
    public static final float MAX_PER_EVENT_DELTA = 0.05f;
    /** Total cumulative drift on any axis can't exceed this magnitude. */
    public static final float LIFETIME_CAP_PER_AXIS = 0.40f;
    /** Days of inactivity required before calm-period drift-back kicks in. */
    public static final int CALM_PERIOD_DAYS = 180;
    /** Drift-back rate while calm: 0.01 per axis per year. */
    public static final float CALM_DRIFT_BACK_PER_YEAR = 0.01f;
    /** Per-day equivalent applied in the daily tick. */
    public static final float CALM_DRIFT_BACK_PER_DAY = CALM_DRIFT_BACK_PER_YEAR / 365f;

    private static final String SAVE_KEY = "npcTraitDrift";

    /** Cumulative drift per axis. Always {@code |value| <= LIFETIME_CAP_PER_AXIS}. */
    private final Map<TraitAxis, Float> totalDrift = new EnumMap<>(TraitAxis.class);
    /** Once-per-lifetime keys that have fired — see {@link #hasFired}. */
    private final Set<String> lifetimeKeysFired = new HashSet<>();
    /** Server tick of the most recent drift call. {@code 0L} means none yet. */
    private long lastDriftTick = 0L;

    public TraitDriftLog() {}

    // ── Queries ─────────────────────────────────────────────────────────────

    public float totalDrift(TraitAxis axis) {
        return totalDrift.getOrDefault(axis, 0f);
    }

    public boolean hasFired(String lifetimeKey) {
        return lifetimeKey != null && lifetimeKeysFired.contains(lifetimeKey);
    }

    public long lastDriftTick() { return lastDriftTick; }

    public Map<TraitAxis, Float> snapshotDrift() {
        return Collections.unmodifiableMap(totalDrift);
    }

    public Set<String> snapshotLifetimeKeys() {
        return Collections.unmodifiableSet(lifetimeKeysFired);
    }

    /**
     * Returns the actual {@code delta} that {@link #recordDrift} would
     * apply for this axis. {@code 0} when capped or when {@code delta}'s
     * magnitude exceeds {@link #MAX_PER_EVENT_DELTA}.
     */
    public float clampToCap(TraitAxis axis, float delta) {
        if (axis == null) return 0f;
        if (Math.abs(delta) > MAX_PER_EVENT_DELTA) {
            // Spec hard-cap: per-event delta ≤ 0.05.
            delta = Math.copySign(MAX_PER_EVENT_DELTA, delta);
        }
        float current = totalDrift(axis);
        float proposed = current + delta;
        if (proposed > LIFETIME_CAP_PER_AXIS) {
            return Math.max(0f, LIFETIME_CAP_PER_AXIS - current);
        }
        if (proposed < -LIFETIME_CAP_PER_AXIS) {
            return Math.min(0f, -LIFETIME_CAP_PER_AXIS - current);
        }
        return delta;
    }

    public boolean canDrift(TraitAxis axis, float delta) {
        return clampToCap(axis, delta) != 0f;
    }

    // ── Mutations ──────────────────────────────────────────────────────────

    /** Records {@code delta} into {@link #totalDrift}. Caller has already clamped. */
    public void recordDrift(TraitAxis axis, float delta, long currentTick) {
        if (delta == 0f) return;
        float current = totalDrift(axis);
        totalDrift.put(axis, current + delta);
        lastDriftTick = currentTick;
    }

    public void markFired(String lifetimeKey) {
        if (lifetimeKey != null) lifetimeKeysFired.add(lifetimeKey);
    }

    /**
     * Calm-period drift-back: if {@code currentTick} is more than
     * {@link #CALM_PERIOD_DAYS} past {@link #lastDriftTick}, decay each
     * axis's accumulated drift toward zero by
     * {@link #CALM_DRIFT_BACK_PER_DAY}. Caller is responsible for
     * applying the matching adjustment to {@link TraitVector}; this
     * method returns the per-axis deltas applied so the caller can do
     * that without re-deriving them.
     */
    public Map<TraitAxis, Float> applyCalmDriftBack(long currentTick) {
        Map<TraitAxis, Float> applied = new EnumMap<>(TraitAxis.class);
        if (lastDriftTick == 0L) {
            // No drift has ever happened — nothing to drift back.
            return applied;
        }
        long elapsedTicks = currentTick - lastDriftTick;
        long elapsedDays = elapsedTicks / 24000L;
        if (elapsedDays < CALM_PERIOD_DAYS) return applied;
        // One day's worth of drift-back per call (this is the daily tick).
        for (TraitAxis axis : TraitAxis.values()) {
            float current = totalDrift.getOrDefault(axis, 0f);
            if (current == 0f) continue;
            float magnitude = Math.min(Math.abs(current), CALM_DRIFT_BACK_PER_DAY);
            float change = -Math.copySign(magnitude, current); // toward zero
            totalDrift.put(axis, current + change);
            applied.put(axis, change);
        }
        return applied;
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    public void save(ValueOutput output) {
        output.store(SAVE_KEY, CODEC, this);
    }

    /** Returns {@code true} if a stored log was found. */
    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        TraitDriftLog loaded = read.get();
        this.totalDrift.clear();
        this.totalDrift.putAll(loaded.totalDrift);
        this.lifetimeKeysFired.clear();
        this.lifetimeKeysFired.addAll(loaded.lifetimeKeysFired);
        this.lastDriftTick = loaded.lastDriftTick;
        return true;
    }

    // ── Codec ──────────────────────────────────────────────────────────────

    private record AxisDrift(TraitAxis axis, float drift) {
        static final Codec<AxisDrift> CODEC = RecordCodecBuilder.create(i -> i.group(
                TraitAxis.CODEC.fieldOf("axis").forGetter(AxisDrift::axis),
                Codec.FLOAT.fieldOf("drift").forGetter(AxisDrift::drift)
        ).apply(i, AxisDrift::new));
    }

    public static final Codec<TraitDriftLog> CODEC = RecordCodecBuilder.create(i -> i.group(
            AxisDrift.CODEC.listOf().optionalFieldOf("totalDrift", List.of())
                    .forGetter(l -> {
                        List<AxisDrift> out = new ArrayList<>();
                        for (Map.Entry<TraitAxis, Float> e : l.totalDrift.entrySet()) {
                            out.add(new AxisDrift(e.getKey(), e.getValue()));
                        }
                        return out;
                    }),
            Codec.STRING.listOf().optionalFieldOf("lifetimeKeysFired", List.of())
                    .forGetter(l -> List.copyOf(l.lifetimeKeysFired)),
            Codec.LONG.optionalFieldOf("lastDriftTick", 0L)
                    .forGetter(l -> l.lastDriftTick)
    ).apply(i, TraitDriftLog::fromCodec));

    public static TraitDriftLog fromCodec(List<AxisDrift> drifts,
                                          List<String> keys,
                                          long lastDriftTick) {
        TraitDriftLog l = new TraitDriftLog();
        for (AxisDrift d : drifts) {
            // Defensive clamp on load — outdated saves shouldn't allow values
            // past the cap.
            float clamped = Math.max(-LIFETIME_CAP_PER_AXIS,
                    Math.min(LIFETIME_CAP_PER_AXIS, d.drift));
            l.totalDrift.put(d.axis, clamped);
        }
        l.lifetimeKeysFired.addAll(keys);
        l.lastDriftTick = lastDriftTick;
        return l;
    }
}
