package tterrag1112.life_in_the_village.Npc.Health;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-NPC health state. Spec lines 50-67. Tracks active conditions +
 * a hidden {@code constitution} score (0..100) that gates contagion
 * resistance and mortality rolls.
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>Adding a condition that already exists upgrades the severity
 *   to the higher value rather than stacking duplicates.</li>
 *   <li>{@link #tickConditions} resolves any condition whose
 *   resolution tick has passed, applying complication / mortality
 *   rolls for the untreated path.</li>
 *   <li>All write paths also adjust the cached aggregates
 *   {@link #workEfficiencyModifier()} and friends — callers do not
 *   need to invalidate state.</li>
 * </ul>
 */
public final class HealthComponent {

    private static final String SAVE_KEY = "npcHealth";
    private static final int    DEFAULT_CONSTITUTION = 70;

    private final List<ActiveCondition> conditions = new ArrayList<>();
    private int constitution = DEFAULT_CONSTITUTION;
    /** Days worked without rest — drives EXHAUSTION onset (spec line 79). */
    private int consecutiveWorkDays;
    /** Days at DISTRESSED mood — drives NERVOUS_BREAKDOWN (spec line 89). */
    private int distressedDayCount;
    /** Last full plague-mortality roll tick (per-day cap). */
    private long lastMortalityRollTick;
    /** Pending death flag — set by {@link #tickConditions} when a
     *  mortality roll succeeds; the daily tick subsystem reads this
     *  and kills the entity outside the iteration. */
    private boolean pendingDeath;

    public HealthComponent() {}

    // ── Conditions ─────────────────────────────────────────────────────────

    public List<ActiveCondition> active() {
        return Collections.unmodifiableList(new ArrayList<>(conditions));
    }

    public boolean hasCondition(HealthCondition type) {
        for (ActiveCondition c : conditions) if (c.type() == type) return true;
        return false;
    }

    public Optional<ActiveCondition> get(HealthCondition type) {
        for (ActiveCondition c : conditions) if (c.type() == type) return Optional.of(c);
        return Optional.empty();
    }

    /** Adds the condition, upgrading severity in place if it already
     *  exists. Returns true when something changed. */
    public boolean add(ActiveCondition c) {
        if (c == null) return false;
        for (int i = 0; i < conditions.size(); i++) {
            ActiveCondition existing = conditions.get(i);
            if (existing.type() == c.type()) {
                if (c.severity() > existing.severity()) {
                    conditions.set(i, c);
                    return true;
                }
                return false;
            }
        }
        conditions.add(c);
        return true;
    }

    public boolean remove(HealthCondition type) {
        return conditions.removeIf(c -> c.type() == type);
    }

    /** Marks the named condition as treated by {@code healerId}. */
    public boolean markTreated(HealthCondition type, UUID healerId, long currentTick) {
        for (int i = 0; i < conditions.size(); i++) {
            ActiveCondition c = conditions.get(i);
            if (c.type() == type) {
                conditions.set(i, c.markTreated(healerId, currentTick));
                return true;
            }
        }
        return false;
    }

    /** Daily resolution sweep. Returns the list of conditions that
     *  resolved this tick (the daily subsystem uses the list to fire
     *  recovery memories + mood blips). */
    public List<HealthCondition> tickConditions(long currentTick, RandomSource random) {
        List<HealthCondition> resolved = new ArrayList<>();
        for (int i = conditions.size() - 1; i >= 0; i--) {
            ActiveCondition c = conditions.get(i);
            // FRAILTY never resolves (permanent until death by age).
            if (c.type() == HealthCondition.FRAILTY) continue;
            if (!c.isResolved(currentTick)) continue;

            if (!c.treated()) {
                float complication = HealthDurations.untreatedComplicationChance(c.type());
                if (complication > 0f && random.nextFloat() < complication) {
                    // Complication: bump severity instead of resolving.
                    conditions.set(i, c.withSeverity(c.severity() + 1));
                    continue;
                }
                float mortality = HealthDurations.untreatedMortalityChance(c.type(), c.severity());
                if (mortality > 0f
                        && currentTick - lastMortalityRollTick >= HealthDurations.DAY
                        && random.nextFloat() < mortality) {
                    pendingDeath = true;
                    lastMortalityRollTick = currentTick;
                    continue;
                }
            }
            conditions.remove(i);
            resolved.add(c.type());
        }
        return resolved;
    }

    // ── Constitution ───────────────────────────────────────────────────────

    public int  constitution()                  { return constitution; }
    public void setConstitution(int value)      { constitution = Math.max(0, Math.min(100, value)); }
    public void adjustConstitution(int delta)   { setConstitution(constitution + delta); }

    public int  consecutiveWorkDays()           { return consecutiveWorkDays; }
    public void setConsecutiveWorkDays(int n)   { consecutiveWorkDays = Math.max(0, n); }
    public void noteWorkDay()                   { consecutiveWorkDays++; }
    public void noteRestDay()                   { consecutiveWorkDays = 0; }

    public int  distressedDayCount()            { return distressedDayCount; }
    public void setDistressedDayCount(int n)    { distressedDayCount = Math.max(0, n); }

    public boolean pendingDeath()               { return pendingDeath; }
    public void    clearPendingDeath()          { pendingDeath = false; }

    // ── Aggregate queries ─────────────────────────────────────────────────

    /** Spec line 63: 0..1 multiplier on production output. */
    public float workEfficiencyModifier() {
        float mult = 1f;
        for (ActiveCondition c : conditions) {
            float drop = switch (c.type()) {
                case MINOR_WOUND, BURN, STOMACH_AILMENT      -> 0.10f;
                case SERIOUS_WOUND                            -> 0.40f;
                case BROKEN_BONE                              -> 0.50f;
                case EXHAUSTION                               -> 0.30f;
                case FEVER, RESPIRATORY_ILLNESS, FRAILTY      -> 0.25f;
                case PLAGUE_CARRIER                           -> 0.60f;
                case MELANCHOLY                               -> 0.10f;
                case NERVOUS_BREAKDOWN                        -> 0.50f;
            };
            mult -= drop * (1f + 0.10f * (c.severity() - 3));
        }
        return Math.max(0f, mult);
    }

    /** Spec line 64. SERIOUS_WOUND, BROKEN_BONE, PLAGUE_CARRIER, and
     *  NERVOUS_BREAKDOWN block work entirely. */
    public boolean canWork() {
        for (ActiveCondition c : conditions) {
            switch (c.type()) {
                case SERIOUS_WOUND, BROKEN_BONE, PLAGUE_CARRIER, NERVOUS_BREAKDOWN -> {
                    return false;
                }
                default -> {}
            }
        }
        return true;
    }

    /** Quarantine + serious immobility conditions block travel. */
    public boolean canTravel() {
        for (ActiveCondition c : conditions) {
            switch (c.type()) {
                case PLAGUE_CARRIER, BROKEN_BONE, SERIOUS_WOUND, NERVOUS_BREAKDOWN -> {
                    return false;
                }
                default -> {}
            }
        }
        return true;
    }

    /** Aggregate mood penalty fed into the daily mood tick (negative). */
    public int moodPenalty() {
        int total = 0;
        for (ActiveCondition c : conditions) {
            int per = switch (c.type()) {
                case MELANCHOLY            -> -8;
                case NERVOUS_BREAKDOWN     -> -15;
                case SERIOUS_WOUND         -> -6;
                case BROKEN_BONE           -> -5;
                case PLAGUE_CARRIER        -> -10;
                case FEVER, RESPIRATORY_ILLNESS, BURN, STOMACH_AILMENT -> -3;
                case MINOR_WOUND, EXHAUSTION -> -2;
                case FRAILTY               -> -1;
            };
            total += per;
        }
        return total;
    }

    /** True when the NPC carries any contagious condition that should
     *  drive a daily proximity-spread roll. */
    public boolean isContagious() {
        for (ActiveCondition c : conditions) if (c.type().isContagious()) return true;
        return false;
    }

    // ── Persistence ───────────────────────────────────────────────────────

    public void save(ValueOutput output)  { output.store(SAVE_KEY, CODEC, this); }

    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        HealthComponent loaded = read.get();
        conditions.clear();
        conditions.addAll(loaded.conditions);
        constitution           = loaded.constitution;
        consecutiveWorkDays    = loaded.consecutiveWorkDays;
        distressedDayCount     = loaded.distressedDayCount;
        lastMortalityRollTick  = loaded.lastMortalityRollTick;
        pendingDeath           = loaded.pendingDeath;
        return true;
    }

    public static final Codec<HealthComponent> CODEC = RecordCodecBuilder.create(i -> i.group(
            ActiveCondition.CODEC.listOf().optionalFieldOf("conditions", List.of())
                    .forGetter(h -> List.copyOf(h.conditions)),
            Codec.INT.optionalFieldOf("constitution", DEFAULT_CONSTITUTION)
                    .forGetter(HealthComponent::constitution),
            Codec.INT.optionalFieldOf("consecutiveWorkDays", 0)
                    .forGetter(HealthComponent::consecutiveWorkDays),
            Codec.INT.optionalFieldOf("distressedDayCount", 0)
                    .forGetter(HealthComponent::distressedDayCount),
            Codec.LONG.optionalFieldOf("lastMortalityRollTick", 0L)
                    .forGetter(h -> h.lastMortalityRollTick),
            Codec.BOOL.optionalFieldOf("pendingDeath", false)
                    .forGetter(HealthComponent::pendingDeath)
    ).apply(i, HealthComponent::fromCodec));

    private static HealthComponent fromCodec(List<ActiveCondition> conditions,
                                             int constitution,
                                             int consecutiveWorkDays,
                                             int distressedDayCount,
                                             long lastMortalityRollTick,
                                             boolean pendingDeath) {
        HealthComponent h = new HealthComponent();
        if (conditions != null) h.conditions.addAll(conditions);
        h.constitution           = constitution;
        h.consecutiveWorkDays    = consecutiveWorkDays;
        h.distressedDayCount     = distressedDayCount;
        h.lastMortalityRollTick  = lastMortalityRollTick;
        h.pendingDeath           = pendingDeath;
        return h;
    }
}
