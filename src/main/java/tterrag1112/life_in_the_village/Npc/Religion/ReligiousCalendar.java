package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holy-day map. Spec line 33.
 *
 * <p>Each entry maps a human-readable label (e.g. "Spring Equinox",
 * "Threadings", "Full Moon", "Forge Day") to a day-of-year integer
 * (0..364). The "things to flag" #1 conversion lives here: a Minecraft
 * year is 8 in-game days long by default, so a "Spring Equinox" at
 * day 80 (full Earth-year mapping) translates to day 2 of the in-game
 * year via {@link #effectiveDayOfYear}. v1 stores the canonical day-
 * of-year and applies the modulo at lookup time so authoring stays
 * intuitive.</p>
 */
public record ReligiousCalendar(Map<String, Integer> holyDaysByName) {

    /** Days per in-game year. Phase 5 culture wiring may refine; v1
     *  uses the canonical Minecraft 24000-tick day × 365-day year. */
    public static final int DAYS_PER_YEAR = 365;

    public ReligiousCalendar {
        holyDaysByName = holyDaysByName == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(holyDaysByName));
    }

    public static ReligiousCalendar of(Map<String, Integer> entries) {
        return new ReligiousCalendar(entries);
    }

    /** Effective day-of-year for a holy-day label, or empty when missing. */
    public Integer effectiveDayOfYear(String label) {
        Integer raw = holyDaysByName.get(label);
        if (raw == null) return null;
        return ((raw % DAYS_PER_YEAR) + DAYS_PER_YEAR) % DAYS_PER_YEAR;
    }

    /** True iff the supplied day-of-year matches any holy day. */
    public boolean isHolyDay(int dayOfYear) {
        for (Integer d : holyDaysByName.values()) {
            if (d == null) continue;
            if ((((d % DAYS_PER_YEAR) + DAYS_PER_YEAR) % DAYS_PER_YEAR) == dayOfYear) return true;
        }
        return false;
    }

    public static final Codec<ReligiousCalendar> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Codec.INT)
                    .optionalFieldOf("holyDaysByName", Map.of())
                    .forGetter(ReligiousCalendar::holyDaysByName)
    ).apply(i, ReligiousCalendar::new));
}
