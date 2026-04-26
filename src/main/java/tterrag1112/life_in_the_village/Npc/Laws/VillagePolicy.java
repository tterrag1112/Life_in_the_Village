package tterrag1112.life_in_the_village.Npc.Laws;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-village law state. Spec line 53.
 *
 * <p>Three parallel maps:</p>
 * <ul>
 *   <li>{@link #activeLaws} — set of currently-enacted laws.</li>
 *   <li>{@link #params}     — parameters per active law.</li>
 *   <li>{@link #enactmentTicks} — when each law was enacted (used to
 *   age subsidy auto-suspend, popularity decay, audit timestamps).</li>
 * </ul>
 *
 * <p>A separate {@link #suspendedLaws} flags laws that are still
 * enacted (per spec "Subsidy drains treasury → auto-suspends, not
 * repealed") but currently inactive in their hooks. Returns true to
 * {@link #hasLaw} (the law still exists for popularity / repeal
 * purposes) but {@link #isActive} returns false. The treasury-aware
 * subsidy hook flips this back when funds recover.</p>
 *
 * <p>Mutators do NOT fire lifecycle events themselves —
 * {@link tterrag1112.life_in_the_village.Npc.Laws.LawAnnouncement}
 * orchestrates that on top. This separation keeps the policy unit-
 * testable without dragging in mood / gossip / reputation.</p>
 */
public final class VillagePolicy {

    private final Set<VillageLaw> activeLaws    = EnumSet.noneOf(VillageLaw.class);
    private final Set<VillageLaw> suspendedLaws = EnumSet.noneOf(VillageLaw.class);
    private final Map<VillageLaw, LawParams> params         = new EnumMap<>(VillageLaw.class);
    private final Map<VillageLaw, Long>      enactmentTicks = new EnumMap<>(VillageLaw.class);

    public VillagePolicy() {}

    // ── Queries ────────────────────────────────────────────────────────────

    public boolean hasLaw(VillageLaw law)        { return law != null && activeLaws.contains(law); }
    public boolean isSuspended(VillageLaw law)   { return law != null && suspendedLaws.contains(law); }
    public boolean isActive(VillageLaw law)      { return hasLaw(law) && !isSuspended(law); }
    public Optional<LawParams> getParams(VillageLaw law) { return Optional.ofNullable(params.get(law)); }
    public Optional<Long> getEnactmentTick(VillageLaw law) { return Optional.ofNullable(enactmentTicks.get(law)); }
    public Set<VillageLaw> activeLaws()          { return Collections.unmodifiableSet(activeLaws); }
    public Set<VillageLaw> suspendedLaws()       { return Collections.unmodifiableSet(suspendedLaws); }

    /** Returns laws of the given category currently enacted (active or suspended). */
    public List<VillageLaw> ofCategory(LawCategory cat) {
        return activeLaws.stream().filter(l -> l.category() == cat).toList();
    }

    /**
     * Returns the conflict that prevents {@code law} from being enacted,
     * or empty when there is none. Spec "Things to flag" #1: contradictory
     * laws blocked at enact time.
     */
    public Optional<VillageLaw> firstConflictWith(VillageLaw law) {
        if (law == null) return Optional.empty();
        for (VillageLaw c : law.conflicts()) {
            if (activeLaws.contains(c)) return Optional.of(c);
        }
        return Optional.empty();
    }

    // ── Mutations ─────────────────────────────────────────────────────────

    /**
     * Enacts the law. No-op if already active or if a conflicting law is
     * present (caller checks {@link #firstConflictWith} first if it
     * cares to give a meaningful failure message).
     */
    public boolean enact(VillageLaw law, LawParams paramsIn, long tick) {
        if (law == null) return false;
        if (activeLaws.contains(law)) return false;
        if (firstConflictWith(law).isPresent()) return false;
        activeLaws.add(law);
        params.put(law, paramsIn != null ? paramsIn : LawParams.withDefaults(law));
        enactmentTicks.put(law, tick);
        return true;
    }

    /** Repeals the law and clears its parameters. */
    public boolean repeal(VillageLaw law) {
        if (law == null || !activeLaws.contains(law)) return false;
        activeLaws.remove(law);
        suspendedLaws.remove(law);
        params.remove(law);
        enactmentTicks.remove(law);
        return true;
    }

    /** Marks the law as suspended without removing it. Idempotent. */
    public void suspend(VillageLaw law) {
        if (law != null && activeLaws.contains(law)) suspendedLaws.add(law);
    }

    /** Resumes a suspended law. Idempotent. */
    public void resume(VillageLaw law) {
        if (law != null) suspendedLaws.remove(law);
    }

    // ── Codec ──────────────────────────────────────────────────────────────

    /**
     * Persistence layout (spec line 207-219):
     * <pre>
     * villageLaws: {
     *   active: [
     *     { law: "SUBSIDIZE_FARMER", enactedTick: 100000, params: {numeric:{...}, string:{...}}, suspended: false }
     *   ]
     * }
     * </pre>
     * Encoded as a single list of {@link Entry} records to keep the codec
     * simple; activeLaws / params / enactmentTicks rebuilt at load.
     */
    public static final Codec<VillagePolicy> CODEC = RecordCodecBuilder.create(i -> i.group(
            Entry.CODEC.listOf().optionalFieldOf("active", List.of()).forGetter(VillagePolicy::toEntries)
    ).apply(i, VillagePolicy::fromCodec));

    private List<Entry> toEntries() {
        List<Entry> out = new java.util.ArrayList<>();
        for (VillageLaw law : activeLaws) {
            out.add(new Entry(law,
                    enactmentTicks.getOrDefault(law, 0L),
                    params.getOrDefault(law, LawParams.empty()),
                    suspendedLaws.contains(law)));
        }
        return out;
    }

    private static VillagePolicy fromCodec(List<Entry> entries) {
        VillagePolicy out = new VillagePolicy();
        for (Entry e : entries) {
            if (e.law() == null) continue;
            out.activeLaws.add(e.law());
            out.params.put(e.law(), e.params());
            out.enactmentTicks.put(e.law(), e.enactedTick());
            if (e.suspended()) out.suspendedLaws.add(e.law());
        }
        return out;
    }

    /** Codec entry record — single source of truth for one law's state. */
    public record Entry(VillageLaw law, long enactedTick, LawParams params, boolean suspended) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                VillageLaw.CODEC.fieldOf("law").forGetter(Entry::law),
                Codec.LONG.optionalFieldOf("enactedTick", 0L).forGetter(Entry::enactedTick),
                LawParams.CODEC.optionalFieldOf("params", LawParams.empty()).forGetter(Entry::params),
                Codec.BOOL.optionalFieldOf("suspended", false).forGetter(Entry::suspended)
        ).apply(i, Entry::new));
    }

    /** Snapshot helper for debug commands. Returns an unmodifiable view. */
    public Map<VillageLaw, LawParams> snapshot() {
        Map<VillageLaw, LawParams> out = new LinkedHashMap<>();
        for (VillageLaw law : activeLaws) {
            out.put(law, params.getOrDefault(law, LawParams.empty()));
        }
        return Collections.unmodifiableMap(out);
    }
}
