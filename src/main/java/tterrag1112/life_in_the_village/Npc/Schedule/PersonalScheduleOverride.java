package tterrag1112.life_in_the_village.Npc.Schedule;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-NPC schedule deviation. Layered above the
 * {@link WeeklyScheduleLibrary} default by {@link ScheduleResolver}.
 *
 * <p>Spec {@code 13-weekly-schedule.md} (section
 * "PersonalScheduleOverride"). Generated at adulthood from the NPC's
 * {@link tterrag1112.life_in_the_village.Npc.Traits.TraitVector} via
 * {@link PersonalScheduleGenerator}; mostly stable for life with
 * minor drift handled separately.</p>
 *
 * <p>The {@code shiftIndex} field carries shift-rotation slot for
 * professions that need rotating coverage (guards, innkeepers — spec
 * line 217). Stored on the same component to keep all schedule
 * deviations in one place.</p>
 */
public final class PersonalScheduleOverride {

    private static final String SAVE_KEY = "npcSchedule";

    /** Replaces a phase's window when present. */
    private final Map<DayPhase, TimeWindow> phaseShifts = new HashMap<>();
    /** Day-of-week indices (0..6) added on top of the profession default. */
    private final Set<Integer> extraDayOffs = new HashSet<>();
    /** Day-of-week indices that override (remove) profession defaults. */
    private final Set<Integer> overrideDayOffs = new HashSet<>();
    /** Shift slot (0 = default day shift; 1, 2 = rotated shifts). */
    private int shiftIndex = 0;

    public PersonalScheduleOverride() {}

    // ── Queries ────────────────────────────────────────────────────────────

    public boolean isEmpty() {
        return phaseShifts.isEmpty() && extraDayOffs.isEmpty()
                && overrideDayOffs.isEmpty() && shiftIndex == 0;
    }

    public Map<DayPhase, TimeWindow> phaseShifts() { return Map.copyOf(phaseShifts); }
    public Set<Integer> extraDayOffs()             { return Set.copyOf(extraDayOffs); }
    public Set<Integer> overrideDayOffs()          { return Set.copyOf(overrideDayOffs); }
    public int shiftIndex()                        { return shiftIndex; }

    /** Returns the override window for {@code phase}, or {@code null}. */
    public TimeWindow shiftFor(DayPhase phase) {
        return phaseShifts.get(phase);
    }

    // ── Mutations ─────────────────────────────────────────────────────────

    public void setPhaseShift(DayPhase phase, TimeWindow window) {
        if (window == null || window.isEmpty()) phaseShifts.remove(phase);
        else phaseShifts.put(phase, window);
    }

    public void addExtraDayOff(int dayOfWeek)       { extraDayOffs.add(dayOfWeek); }
    public void removeExtraDayOff(int dayOfWeek)    { extraDayOffs.remove(dayOfWeek); }
    public void overrideDayOff(int dayOfWeek)       { overrideDayOffs.add(dayOfWeek); }
    public void clearOverrideDayOff(int dayOfWeek)  { overrideDayOffs.remove(dayOfWeek); }

    public void setShiftIndex(int idx) {
        this.shiftIndex = Math.max(0, idx);
    }

    public void clear() {
        phaseShifts.clear();
        extraDayOffs.clear();
        overrideDayOffs.clear();
        shiftIndex = 0;
    }

    // ── Persistence ────────────────────────────────────────────────────────

    public void save(ValueOutput output) {
        output.store(SAVE_KEY, CODEC, this);
    }

    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        PersonalScheduleOverride loaded = read.get();
        this.phaseShifts.clear();
        this.phaseShifts.putAll(loaded.phaseShifts);
        this.extraDayOffs.clear();
        this.extraDayOffs.addAll(loaded.extraDayOffs);
        this.overrideDayOffs.clear();
        this.overrideDayOffs.addAll(loaded.overrideDayOffs);
        this.shiftIndex = loaded.shiftIndex;
        return true;
    }

    // ── Codec ──────────────────────────────────────────────────────────────

    private record PhaseShiftEntry(DayPhase phase, TimeWindow window) {
        static final Codec<PhaseShiftEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                DayPhase.CODEC.fieldOf("phase").forGetter(PhaseShiftEntry::phase),
                TimeWindow.CODEC.fieldOf("window").forGetter(PhaseShiftEntry::window)
        ).apply(i, PhaseShiftEntry::new));
    }

    public static final Codec<PersonalScheduleOverride> CODEC = RecordCodecBuilder.create(i -> i.group(
            PhaseShiftEntry.CODEC.listOf().optionalFieldOf("phaseShifts", java.util.List.of())
                    .forGetter(o -> o.phaseShifts.entrySet().stream()
                            .map(e -> new PhaseShiftEntry(e.getKey(), e.getValue()))
                            .toList()),
            Codec.INT.listOf().optionalFieldOf("extraDayOffs", java.util.List.of())
                    .forGetter(o -> java.util.List.copyOf(o.extraDayOffs)),
            Codec.INT.listOf().optionalFieldOf("overrideDayOffs", java.util.List.of())
                    .forGetter(o -> java.util.List.copyOf(o.overrideDayOffs)),
            Codec.INT.optionalFieldOf("shiftIndex", 0)
                    .forGetter(o -> o.shiftIndex)
    ).apply(i, PersonalScheduleOverride::fromCodec));

    public static PersonalScheduleOverride fromCodec(java.util.List<PhaseShiftEntry> shifts,
                                                     java.util.List<Integer> extra,
                                                     java.util.List<Integer> override,
                                                     int shiftIndex) {
        PersonalScheduleOverride o = new PersonalScheduleOverride();
        for (PhaseShiftEntry e : shifts) o.phaseShifts.put(e.phase(), e.window());
        o.extraDayOffs.addAll(extra);
        o.overrideDayOffs.addAll(override);
        o.shiftIndex = Math.max(0, shiftIndex);
        return o;
    }
}
