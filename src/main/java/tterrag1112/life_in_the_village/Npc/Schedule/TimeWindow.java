package tterrag1112.life_in_the_village.Npc.Schedule;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Inclusive-start, exclusive-end window within the 24000-tick
 * Minecraft day cycle. Supports wrapping across midnight
 * (e.g. {@code TimeWindow(22000, 2000)} covers 22000..23999 and
 * 0..1999).
 *
 * <p>Identical semantics to the legacy {@code WorkSchedule.TimeWindow};
 * relocated into the {@code Npc.Schedule} package as part of the
 * Phase 2 weekly-schedule migration.</p>
 */
public record TimeWindow(int startTick, int endTick) {

    /** Empty/inactive window — never matches any tick. */
    public static final TimeWindow EMPTY = new TimeWindow(0, 0);

    public boolean isActive(long dayTime) {
        // Empty window (start == end) never matches.
        if (startTick == endTick) return false;
        long t = ((dayTime % 24000) + 24000) % 24000;
        if (startTick <= endTick) {
            return t >= startTick && t < endTick;
        }
        return t >= startTick || t < endTick;
    }

    public boolean isEmpty() { return startTick == endTick; }

    /** Returns a window shifted by {@code deltaTicks} (mod 24000). */
    public TimeWindow shifted(int deltaTicks) {
        if (isEmpty()) return this;
        int s = ((startTick + deltaTicks) % 24000 + 24000) % 24000;
        int e = ((endTick + deltaTicks) % 24000 + 24000) % 24000;
        return new TimeWindow(s, e);
    }

    public static final Codec<TimeWindow> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("start").forGetter(TimeWindow::startTick),
            Codec.INT.fieldOf("end").forGetter(TimeWindow::endTick)
    ).apply(i, TimeWindow::new));
}
