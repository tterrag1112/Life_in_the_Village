package tterrag1112.life_in_the_village.Npc.Religion.Sacred;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Npc.Religion.CalendarView;
import tterrag1112.life_in_the_village.Npc.Religion.DivineFavour;
import tterrag1112.life_in_the_village.Npc.Religion.GodRegistry;
import tterrag1112.life_in_the_village.Npc.Religion.PietyComponent;
import tterrag1112.life_in_the_village.Npc.Religion.PietyTier;
import tterrag1112.life_in_the_village.Npc.Religion.Religion;
import tterrag1112.life_in_the_village.Npc.Religion.Religions;
import tterrag1112.life_in_the_village.Npc.Religion.RiteSavedData;

import java.util.UUID;

/**
 * Sacred Time S2 — the temporal mirror of {@link SacredSpace}: a faith's <b>holy
 * day</b> heightens its devout's contact with their god (favour / miracles /
 * theophany). The deliberate contrast with sacred space — which <i>any</i> believer
 * feels — is that sacred time rewards the <b>highly pious</b>: below DEVOUT there is
 * no bonus.
 *
 * <p><b>Holy days live on the religion; favour/miracles/theophany are per-god</b>, so
 * the resolution is: today is a holy day for god G (for this player) iff a religion
 * the player <i>believes in</i> that <i>venerates G</i> has a holy day today
 * ({@link #isHolyDay}). The bonus then gates + scales on the player's piety tier with
 * that god ({@link #holyDayFactor}). Composes (multiplies) with the S1b sacred-space
 * amplifier — neither bypasses the tier gate or the favour cap.</p>
 */
public final class SacredTime {

    private SacredTime() {}

    /**
     * True when today is a holy day for {@code godId} <i>for this player</i>: some
     * religion the player believes in that venerates the god has a holy day on the
     * current liturgical day-of-year. No piety gate (that's {@link #holyDayFactor}'s
     * job) — this is the pure calendar × belief × veneration resolution, the one
     * home both the multiplier and the theophany ease read.
     */
    public static boolean isHolyDay(ServerLevel level, UUID playerId, String godId, long now) {
        if (level == null || playerId == null || godId == null) return false;
        PietyComponent piety = RiteSavedData.get(level).getPlayerPiety(playerId).orElse(null);
        if (piety == null || piety.beliefs().isEmpty()) return false;
        int today = CalendarView.dayOfYear(now);
        for (String rid : GodRegistry.religionsVenerating(level, godId)) {
            if (!piety.beliefs().containsKey(rid)) continue;     // not a faith the player holds
            Religion r = Religions.get(level, rid);
            if (r != null && r.calendar().isHolyDay(today)) return true;
        }
        return false;
    }

    /**
     * The holy-day favour/miracle multiplier (≥ 1.0) for {@code godId}: 1.0 off a holy
     * day OR below the DEVOUT gate; DEVOUT 1.5, PIOUS 2.0. Mirrors
     * {@link SacredSpace#amplifierAt} so the two read alike and stack.
     */
    public static float holyDayFactor(ServerLevel level, UUID playerId, String godId, long now) {
        if (!isHolyDay(level, playerId, godId, now)) return 1.0f;
        return factorForTier(DivineFavour.tierForGod(level, playerId, godId));
    }

    /** Piety-gated holy-day scaling — sacred time rewards the committed (DEVOUT+). */
    private static float factorForTier(PietyTier tier) {
        return switch (tier) {
            case UNAFFILIATED, FAITHFUL -> 1.0f;   // below the gate — no holy-day bonus
            case DEVOUT                 -> 1.5f;
            case PIOUS                  -> 2.0f;
        };
    }
}
