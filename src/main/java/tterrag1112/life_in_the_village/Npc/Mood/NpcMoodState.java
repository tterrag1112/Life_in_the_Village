package tterrag1112.life_in_the_village.Npc.Mood;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Npc.Traits.TraitVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single-scalar emotional state, decaying toward a trait-influenced
 * baseline. See {@code docs/npc_redesign/04-mood-system.md}.
 *
 * <p>Phase 0 ships storage, decay, baseline computation, and a debug-only
 * trigger path. Production callers (gift handler, family-death handler,
 * etc.) wire in with the Phase 1 life-event bus.</p>
 */
public final class NpcMoodState {

    public static final float MIN_VALUE = -100f;
    public static final float MAX_VALUE = 100f;

    /** Daily decay rate toward {@link #baseline}. */
    public static final float DAILY_DECAY_FRACTION = 0.15f;
    /** Decay halves while a grieving period is active. */
    public static final float GRIEF_DECAY_MULTIPLIER = 0.5f;
    /** Length of the grief window after {@link MoodTrigger#FAMILY_DEATH}. */
    public static final int GRIEF_DAYS = 30;

    /** Maximum days of decay applied at once on long-unload load. */
    public static final int MAX_CATCHUP_DAYS = 30;

    /** Cap on retained recent events (per spec). */
    public static final int RECENT_CAP = 5;

    private static final String SAVE_KEY = "npcMood";

    private float value = 0f;
    private float baseline = 0f;
    private long lastUpdateTick = 0L;
    /** Tick at which the active grief window started, or {@code -1} when none. */
    private long griefStartTick = -1L;
    private final List<MoodEvent> recent = new ArrayList<>();

    public NpcMoodState() {}

    // ── State accessors ────────────────────────────────────────────────────

    public float value()    { return value; }
    public float baseline() { return baseline; }
    public long lastUpdateTick() { return lastUpdateTick; }
    public boolean isGrieving(long currentTick) {
        return griefStartTick >= 0
                && currentTick - griefStartTick < (long) GRIEF_DAYS * 24000L;
    }

    public MoodCategory category() { return MoodCategory.fromValue(value); }

    public List<MoodEvent> recentEvents() {
        return Collections.unmodifiableList(recent);
    }

    /** Sums today's accumulated magnitude for the given trigger. */
    public int todaysAccumulated(MoodTrigger trigger, long currentTick) {
        long earliest = currentTick - 24000L;
        int sum = 0;
        for (MoodEvent e : recent) {
            if (e.trigger() == trigger && e.tick() >= earliest) {
                sum += e.magnitude();
            }
        }
        return sum;
    }

    // ── Mutation ───────────────────────────────────────────────────────────

    /**
     * Computes baseline from the spec formula and snaps {@code value} to
     * it. Used at NPC spawn and as the fallback when no stored mood is
     * found on load.
     */
    public void initializeFromTraits(TraitVector traits) {
        this.baseline = computeBaseline(traits);
        this.value = clamp(this.baseline);
    }

    /** Spec formula (line 139-141 of 04-mood-system.md). */
    public static float computeBaseline(TraitVector traits) {
        float base = 10f
                * (traits.get(TraitAxis.GENEROSITY) + traits.get(TraitAxis.TEMPERANCE)) / 2f
                + 5f * traits.get(TraitAxis.SOCIABILITY);
        return clamp(base);
    }

    /**
     * Applies a trigger: trait-modulated magnitude, daily-stack-capped,
     * clamped to {@code [-100, 100]}, recorded in {@link #recent}.
     *
     * @return the actual magnitude applied (after modulation + capping).
     *         {@code 0} when the cap blocked all of it.
     */
    public int apply(MoodTrigger trigger, TraitVector traits, long currentTick) {
        return applyWithRawMagnitude(trigger,
                Math.round(trigger.defaultMagnitude() * trigger.traitMultiplier(traits)),
                currentTick);
    }

    /**
     * Phase 1 producer hook: applies an explicit pre-computed magnitude
     * (already trait-modulated by the caller) under the same daily-cap
     * + clamp + recent-event-log path as {@link #apply}. Used by
     * {@code MoodProducer.applyWithMagnitude} for spec rows that
     * specify "+15 flat" or "×1.5 magnitude".
     */
    public int applyWithRawMagnitude(MoodTrigger trigger, int rawMag, long currentTick) {
        int delta = rawMag;

        // Daily-stack cap (cumulative cap in mood points; sign-aware).
        float cap = trigger.cumulativeDailyCap();
        if (cap > 0f) {
            int alreadyToday = todaysAccumulated(trigger, currentTick);
            int signedCap = rawMag >= 0 ? (int) cap : -(int) cap;
            int remaining = signedCap - alreadyToday;
            if (rawMag >= 0) {
                delta = Math.max(0, Math.min(rawMag, remaining));
            } else {
                delta = Math.min(0, Math.max(rawMag, remaining));
            }
        }

        if (delta == 0) {
            // Still record a zero-magnitude entry so the recent log shows
            // the trigger fired? Skip — the spec's recent list is "events
            // that affected mood" by intent.
            return 0;
        }

        value = clamp(value + delta);
        lastUpdateTick = currentTick;
        if (trigger == MoodTrigger.FAMILY_DEATH) {
            griefStartTick = currentTick;
        }

        recent.add(new MoodEvent(trigger, delta, currentTick));
        while (recent.size() > RECENT_CAP) recent.remove(0);
        return delta;
    }

    /**
     * Advances decay toward {@link #baseline} by {@code daysElapsed} days.
     * Per spec, 15% of the gap closes per day; the grief window halves
     * decay rate. Long-unload guard: caps catch-up at
     * {@link #MAX_CATCHUP_DAYS} and snaps to baseline beyond that.
     */
    public void decay(float daysElapsed, long currentTick) {
        if (daysElapsed <= 0f) return;
        if (daysElapsed > MAX_CATCHUP_DAYS) {
            value = baseline;
            lastUpdateTick = currentTick;
            return;
        }
        float rate = DAILY_DECAY_FRACTION;
        if (isGrieving(currentTick)) rate *= GRIEF_DECAY_MULTIPLIER;
        // Apply day-by-day so the geometric step matches spec semantics.
        int wholeDays = (int) Math.floor(daysElapsed);
        for (int d = 0; d < wholeDays; d++) {
            value += (baseline - value) * rate;
        }
        float fractional = daysElapsed - wholeDays;
        if (fractional > 0f) {
            value += (baseline - value) * rate * fractional;
        }
        value = clamp(value);
        lastUpdateTick = currentTick;
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    public void save(ValueOutput output) {
        output.store(SAVE_KEY, CODEC, this);
    }

    /** Returns {@code true} if a stored mood was found and loaded. */
    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        NpcMoodState loaded = read.get();
        this.value = loaded.value;
        this.baseline = loaded.baseline;
        this.lastUpdateTick = loaded.lastUpdateTick;
        this.griefStartTick = loaded.griefStartTick;
        this.recent.clear();
        this.recent.addAll(loaded.recent);
        return true;
    }

    // ── Codec ──────────────────────────────────────────────────────────────

    public static final Codec<NpcMoodState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.optionalFieldOf("value", 0f)
                    .forGetter(s -> s.value),
            Codec.FLOAT.optionalFieldOf("baseline", 0f)
                    .forGetter(s -> s.baseline),
            Codec.LONG.optionalFieldOf("lastUpdateTick", 0L)
                    .forGetter(s -> s.lastUpdateTick),
            Codec.LONG.optionalFieldOf("griefStartTick", -1L)
                    .forGetter(s -> s.griefStartTick),
            MoodEvent.CODEC.listOf().optionalFieldOf("recent", List.of())
                    .forGetter(s -> List.copyOf(s.recent))
    ).apply(i, NpcMoodState::fromCodec));

    /** Codec reconstruction factory. */
    public static NpcMoodState fromCodec(float value, float baseline,
                                         long lastUpdateTick, long griefStartTick,
                                         List<MoodEvent> recent) {
        NpcMoodState s = new NpcMoodState();
        s.value = clamp(value);
        s.baseline = clamp(baseline);
        s.lastUpdateTick = lastUpdateTick;
        s.griefStartTick = griefStartTick;
        s.recent.clear();
        for (MoodEvent e : recent) {
            if (s.recent.size() >= RECENT_CAP) break;
            s.recent.add(e);
        }
        return s;
    }

    private static float clamp(float v) {
        return Math.max(MIN_VALUE, Math.min(MAX_VALUE, v));
    }
}
