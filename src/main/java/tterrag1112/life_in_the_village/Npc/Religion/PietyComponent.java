package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-NPC religious belief state. Spec line 53.
 *
 * <p>Belief strength is in {@code [0.0, 1.0]}. Most NPCs carry a
 * single primary religion at 0.3-0.6 (set at spawn from the village's
 * culture); migrant NPCs carry their home religion at higher
 * strength + the local at low strength (syncretism).</p>
 *
 * <p>Players use the same component shape via the {@link
 * tterrag1112.life_in_the_village.Npc.Religion.PlayerPietySavedData}
 * (Phase 4 attachment hook is heavier than v1 needs).</p>
 */
public final class PietyComponent {

    public static final int RITES_PER_MONTH_THRESHOLD = 3;
    private static final String SAVE_KEY = "npcPiety";

    private final Map<String, Float> beliefs = new LinkedHashMap<>();
    private long lastRiteAttendedTick;
    private int  ritesAttendedThisMonth;
    private long monthAnchorTick;  // tick at which the rolling month started

    public PietyComponent() {}

    // ── Beliefs ────────────────────────────────────────────────────────────

    /** Returns the belief strength for the religion id, or 0 when unknown. */
    public float beliefIn(String religionId) {
        if (religionId == null) return 0f;
        return beliefs.getOrDefault(religionId, 0f);
    }

    /** Sets / clamps belief strength; removes the entry when strength ≤ 0. */
    public void setBelief(String religionId, float strength) {
        if (religionId == null) return;
        float clamped = Math.max(0f, Math.min(1f, strength));
        if (clamped <= 0f) beliefs.remove(religionId);
        else beliefs.put(religionId, clamped);
    }

    public void adjustBelief(String religionId, float delta) {
        setBelief(religionId, beliefIn(religionId) + delta);
    }

    /** Read-only snapshot for debug + UI. */
    public Map<String, Float> beliefs() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(beliefs));
    }

    /** Returns the religion id with the highest strength, or empty
     *  when the NPC has no beliefs (atheist). */
    public java.util.Optional<String> primaryReligion() {
        String best = null;
        float bestStrength = 0f;
        for (Map.Entry<String, Float> e : beliefs.entrySet()) {
            if (e.getValue() > bestStrength) {
                best = e.getKey();
                bestStrength = e.getValue();
            }
        }
        return java.util.Optional.ofNullable(best);
    }

    public float primaryStrength() {
        return primaryReligion().map(this::beliefIn).orElse(0f);
    }

    /** Spec piety tier per line 163. */
    public PietyTier primaryTier() {
        float s = primaryStrength();
        if (s < 0.2f) return PietyTier.UNAFFILIATED;
        if (s < 0.5f) return PietyTier.FAITHFUL;
        if (s < 0.8f) return PietyTier.DEVOUT;
        return PietyTier.PIOUS;
    }

    // ── Rite attendance ────────────────────────────────────────────────────

    /** Spec line 60. True when the NPC plausibly attends rites of
     *  type {@code r} for their primary religion. v1 returns
     *  {@code primaryStrength() >= 0.2 && primary religion ritualises r}. */
    public boolean attendsRite(Rite r) {
        if (primaryStrength() < 0.2f) return false;
        return primaryReligion()
                .flatMap(ReligionRegistry::find)
                .map(rel -> rel.ritualises(r))
                .orElse(false);
    }

    public long lastRiteAttendedTick()    { return lastRiteAttendedTick; }
    public int  ritesAttendedThisMonth()  { return ritesAttendedThisMonth; }

    /** Records a rite attendance. Rolls the per-month counter when 30
     *  in-game days have passed since the last anchor. */
    public void recordRiteAttendance(long currentTick) {
        long monthTicks = 30L * 24000L;
        if (monthAnchorTick == 0L || currentTick - monthAnchorTick > monthTicks) {
            monthAnchorTick = currentTick;
            ritesAttendedThisMonth = 0;
        }
        ritesAttendedThisMonth++;
        lastRiteAttendedTick = currentTick;
    }

    public boolean meetsMonthlyAttendance() {
        return ritesAttendedThisMonth >= RITES_PER_MONTH_THRESHOLD;
    }

    // ── Persistence ───────────────────────────────────────────────────────

    public void save(ValueOutput output)  { output.store(SAVE_KEY, CODEC, this); }
    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        PietyComponent loaded = read.get();
        beliefs.clear();
        beliefs.putAll(loaded.beliefs);
        lastRiteAttendedTick   = loaded.lastRiteAttendedTick;
        ritesAttendedThisMonth = loaded.ritesAttendedThisMonth;
        monthAnchorTick        = loaded.monthAnchorTick;
        return true;
    }

    public static final Codec<PietyComponent> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Codec.FLOAT)
                    .optionalFieldOf("beliefs", Map.of())
                    .forGetter(p -> Map.copyOf(p.beliefs)),
            Codec.LONG.optionalFieldOf("lastRiteAttendedTick", 0L)
                    .forGetter(PietyComponent::lastRiteAttendedTick),
            Codec.INT.optionalFieldOf("ritesAttendedThisMonth", 0)
                    .forGetter(PietyComponent::ritesAttendedThisMonth),
            Codec.LONG.optionalFieldOf("monthAnchorTick", 0L)
                    .forGetter(p -> p.monthAnchorTick)
    ).apply(i, PietyComponent::fromCodec));

    public static PietyComponent fromCodec(Map<String, Float> beliefs,
                                           long lastTick, int monthCount, long anchor) {
        PietyComponent p = new PietyComponent();
        if (beliefs != null) p.beliefs.putAll(beliefs);
        p.lastRiteAttendedTick   = lastTick;
        p.ritesAttendedThisMonth = monthCount;
        p.monthAnchorTick        = anchor;
        return p;
    }

    /** Suppresses unused-import on List for follow-up overloads. */
    @SuppressWarnings("unused")
    private static final List<String> DOC_HOOK = List.of();
}
