package tterrag1112.life_in_the_village.Npc.Crime;

import com.mojang.serialization.Codec;

/**
 * Lifecycle states for a {@link CrimeReport}. Spec line 58.
 *
 * <ul>
 *   <li>{@link #FILED} — witnesses logged, awaits investigation.</li>
 *   <li>{@link #INVESTIGATING} — constable picked it up.</li>
 *   <li>{@link #DISMISSED} — investigation found insufficient
 *   evidence; no trial.</li>
 *   <li>{@link #TRIAL_SCHEDULED} — investigation forwarded; trial
 *   tick scheduled.</li>
 *   <li>{@link #TRIAL_COMPLETE} — trial concluded; verdict +
 *   punishment recorded on the {@link Trial} record.</li>
 *   <li>{@link #COLD} — 30+ days FILED with no investigator;
 *   reputation effects still apply but no formal trial.</li>
 * </ul>
 */
public enum ReportStatus {
    FILED,
    INVESTIGATING,
    DISMISSED,
    TRIAL_SCHEDULED,
    TRIAL_COMPLETE,
    COLD;

    public static final Codec<ReportStatus> CODEC =
            Codec.STRING.xmap(ReportStatus::valueOf, ReportStatus::name);
}
