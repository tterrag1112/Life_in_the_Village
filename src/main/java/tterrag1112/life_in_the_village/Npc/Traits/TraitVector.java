package tterrag1112.life_in_the_village.Npc.Traits;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import tterrag1112.life_in_the_village.Entities.custom.AppearanceComponent.PersonalityTrait;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Eight-axis personality vector attached to every NPC. All values live in
 * {@code [-1, +1]}; absolute magnitudes past {@link #DISPLAY_THRESHOLD}
 * surface as named traits, past {@link #EMPHATIC_THRESHOLD} as "Very ..."
 * labels.
 *
 * <p>Phase 0 storage contract: the vector is persisted alongside the
 * existing {@code PersonalityTrait} list on {@code AppearanceComponent}.
 * Legacy saves are migrated on load via {@link #migrateFromLegacy}. The
 * legacy list stays readable for one release to verify migration.</p>
 *
 * <p>See {@code docs/npc_redesign/01-trait-axes.md} for the full spec.</p>
 */
public final class TraitVector {

    /** {@code |value| >= 0.40} surfaces the axis as a displayed trait. */
    public static final float DISPLAY_THRESHOLD = 0.40f;
    /** {@code |value| >= 0.85} is displayed with "Very ..." emphasis. */
    public static final float EMPHATIC_THRESHOLD = 0.85f;

    /** Standard deviation of the per-axis gaussian at spawn. */
    private static final float SPAWN_STDDEV = 0.30f;
    /** Magnitude applied per matched legacy PersonalityTrait on migration. */
    private static final float LEGACY_DELTA = 0.50f;

    /** Shared NBT-key prefix for flat-key persistence on the entity tag. */
    private static final String KEY_PREFIX = "npcTraits.";

    /**
     * Pole-based mapping from the legacy {@code PersonalityTrait} enum to
     * trait-vector adjustments. Deltas sum and are clamped, so contradictory
     * legacy pairs (e.g. GENEROUS + GREEDY) cancel — matching the spec's
     * edge-case rule.
     *
     * <p>The CHEERFUL/GRUMPY pole is mapped to COMPASSION in this table;
     * see 01-trait-axes.md Revision Notes for the reasoning.</p>
     */
    private static final Map<PersonalityTrait, LegacyMapping> LEGACY_MAP = buildLegacyMap();

    private record LegacyMapping(TraitAxis axis, float delta) {}

    private static Map<PersonalityTrait, LegacyMapping> buildLegacyMap() {
        Map<PersonalityTrait, LegacyMapping> m = new EnumMap<>(PersonalityTrait.class);
        m.put(PersonalityTrait.DILIGENT,   new LegacyMapping(TraitAxis.INDUSTRY,    +LEGACY_DELTA));
        m.put(PersonalityTrait.LAZY,       new LegacyMapping(TraitAxis.INDUSTRY,    -LEGACY_DELTA));
        m.put(PersonalityTrait.GENEROUS,   new LegacyMapping(TraitAxis.GENEROSITY,  +LEGACY_DELTA));
        m.put(PersonalityTrait.GREEDY,     new LegacyMapping(TraitAxis.GENEROSITY,  -LEGACY_DELTA));
        m.put(PersonalityTrait.BRAVE,      new LegacyMapping(TraitAxis.COURAGE,     +LEGACY_DELTA));
        m.put(PersonalityTrait.TIMID,      new LegacyMapping(TraitAxis.COURAGE,     -LEGACY_DELTA));
        m.put(PersonalityTrait.FRIENDLY,   new LegacyMapping(TraitAxis.SOCIABILITY, +LEGACY_DELTA));
        m.put(PersonalityTrait.SUSPICIOUS, new LegacyMapping(TraitAxis.SOCIABILITY, -LEGACY_DELTA));
        m.put(PersonalityTrait.CHEERFUL,   new LegacyMapping(TraitAxis.COMPASSION,  +LEGACY_DELTA));
        m.put(PersonalityTrait.GRUMPY,     new LegacyMapping(TraitAxis.COMPASSION,  -LEGACY_DELTA));
        return m;
    }

    private final float[] values = new float[TraitAxis.values().length];

    public TraitVector() {}

    /** All-axis constructor in enum declaration order; used by the codec. */
    public TraitVector(float industry, float courage, float sociability, float generosity,
                       float honesty, float ambition, float compassion, float temperance,
                       float piety, float scholarship) {
        set(TraitAxis.INDUSTRY,    industry);
        set(TraitAxis.COURAGE,     courage);
        set(TraitAxis.SOCIABILITY, sociability);
        set(TraitAxis.GENEROSITY,  generosity);
        set(TraitAxis.HONESTY,     honesty);
        set(TraitAxis.AMBITION,    ambition);
        set(TraitAxis.COMPASSION,  compassion);
        set(TraitAxis.TEMPERANCE,  temperance);
        set(TraitAxis.PIETY,       piety);
        set(TraitAxis.SCHOLARSHIP, scholarship);
    }

    // ── Value access ────────────────────────────────────────────────────────

    public float get(TraitAxis axis) {
        return values[axis.ordinal()];
    }

    public void set(TraitAxis axis, float value) {
        values[axis.ordinal()] = Mth.clamp(value, -1f, 1f);
    }

    public void adjust(TraitAxis axis, float delta) {
        set(axis, values[axis.ordinal()] + delta);
    }

    /** True when {@code |value| >= DISPLAY_THRESHOLD} and the sign matches. */
    public boolean hasTrait(TraitAxis axis, boolean positivePole) {
        float v = values[axis.ordinal()];
        if (Math.abs(v) < DISPLAY_THRESHOLD) return false;
        return positivePole ? v > 0f : v < 0f;
    }

    // ── Display ─────────────────────────────────────────────────────────────

    /**
     * All axes whose magnitude meets {@link #DISPLAY_THRESHOLD}, sorted by
     * absolute value descending. UI typically shows the top few.
     */
    public List<DisplayedTrait> significantTraits() {
        List<DisplayedTrait> out = new ArrayList<>();
        for (TraitAxis axis : TraitAxis.values()) {
            float v = values[axis.ordinal()];
            float mag = Math.abs(v);
            if (mag < DISPLAY_THRESHOLD) continue;
            TraitIntensity intensity = mag >= EMPHATIC_THRESHOLD
                    ? TraitIntensity.EMPHATIC : TraitIntensity.NORMAL;
            out.add(new DisplayedTrait(axis, v > 0f, intensity));
        }
        out.sort(Comparator.comparingDouble((DisplayedTrait d) -> Math.abs(get(d.axis()))).reversed());
        return out;
    }

    // ── Generation ──────────────────────────────────────────────────────────

    /**
     * Seeds the vector per spec section "Generation → At spawn":
     * zero baseline + per-axis gaussian noise (σ = {@value #SPAWN_STDDEV}),
     * clamped to {@code [-1, +1]}. Culture-pull wiring comes in Phase 5.
     */
    public void randomize(RandomSource random) {
        for (TraitAxis axis : TraitAxis.values()) {
            double noise = random.nextGaussian() * SPAWN_STDDEV;
            set(axis, (float) noise);
        }
    }

    // ── Legacy migration ────────────────────────────────────────────────────

    /**
     * Converts a list of legacy {@link PersonalityTrait}s into axis
     * adjustments. Contradictory pairs (e.g. GENEROUS + GREEDY) cancel via
     * the clamped sum. Unknown traits are silently ignored.
     */
    public void migrateFromLegacy(List<PersonalityTrait> legacy) {
        for (PersonalityTrait t : legacy) {
            LegacyMapping mapping = LEGACY_MAP.get(t);
            if (mapping != null) adjust(mapping.axis(), mapping.delta());
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    /** Writes every axis as {@code npcTraits.<axisKey>} floats. */
    public void save(ValueOutput output) {
        for (TraitAxis axis : TraitAxis.values()) {
            output.store(KEY_PREFIX + axis.key(), Codec.FLOAT, values[axis.ordinal()]);
        }
    }

    /**
     * Reads axis values back into this vector; missing keys default to 0.
     * Returns {@code true} if at least one axis key was actually present —
     * the caller uses that signal to decide whether legacy migration should
     * run.
     */
    public boolean load(ValueInput input) {
        boolean anyPresent = false;
        for (TraitAxis axis : TraitAxis.values()) {
            var read = input.read(KEY_PREFIX + axis.key(), Codec.FLOAT);
            if (read.isPresent()) {
                anyPresent = true;
                set(axis, read.get());
            } else {
                set(axis, 0f);
            }
        }
        return anyPresent;
    }

    // ── Codec (for saved-data contexts where flat NBT keys aren't used) ─────

    public static final Codec<TraitVector> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.optionalFieldOf("industry",    0f).forGetter(v -> v.get(TraitAxis.INDUSTRY)),
            Codec.FLOAT.optionalFieldOf("courage",     0f).forGetter(v -> v.get(TraitAxis.COURAGE)),
            Codec.FLOAT.optionalFieldOf("sociability", 0f).forGetter(v -> v.get(TraitAxis.SOCIABILITY)),
            Codec.FLOAT.optionalFieldOf("generosity",  0f).forGetter(v -> v.get(TraitAxis.GENEROSITY)),
            Codec.FLOAT.optionalFieldOf("honesty",     0f).forGetter(v -> v.get(TraitAxis.HONESTY)),
            Codec.FLOAT.optionalFieldOf("ambition",    0f).forGetter(v -> v.get(TraitAxis.AMBITION)),
            Codec.FLOAT.optionalFieldOf("compassion",  0f).forGetter(v -> v.get(TraitAxis.COMPASSION)),
            Codec.FLOAT.optionalFieldOf("temperance",  0f).forGetter(v -> v.get(TraitAxis.TEMPERANCE)),
            // Track D3.5A — new axes. Old saves load with 0
            // (neutral) so personality stays stable across upgrade.
            Codec.FLOAT.optionalFieldOf("piety",       0f).forGetter(v -> v.get(TraitAxis.PIETY)),
            Codec.FLOAT.optionalFieldOf("scholarship", 0f).forGetter(v -> v.get(TraitAxis.SCHOLARSHIP))
    ).apply(i, TraitVector::new));
}
