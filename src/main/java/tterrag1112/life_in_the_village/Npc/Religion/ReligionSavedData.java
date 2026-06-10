package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * F1b sub-stage 1a — the per-world <b>religion store</b>: the live, persisted home
 * of every {@link Religion} instance in a world. Mirrors {@link RiteSavedData} (a
 * separate {@link SavedData}, not folded into {@code VillageSavedData}): a
 * {@link SavedDataType} with its own storage name, a codec over the collection, and
 * {@code markDirty} on mutation.
 *
 * <p><b>Templates → instances.</b> The static {@link ReligionRegistry} (and
 * {@link ReligionIdentity}) become the seed CATALOG; this store holds the world's
 * live set. A fresh world {@linkplain #seedIfEmpty lazily seeds} the four templates
 * on first access; a loaded world restores its saved set (which a later dynamism
 * stage may grow/diverge from the templates). Seeding is <b>only-when-empty</b> so
 * saved state is never clobbered.</p>
 *
 * <p><b>Additive scaffolding (F1b-1a).</b> Nothing reads this store yet except the
 * {@code /religion world list} debug readout. The whole lookup surface still routes
 * through the static {@link ReligionRegistry}; sub-stage 1b migrates callers (via the
 * {@link Religions} facade) and restricts the static registry to template-only.</p>
 *
 * <p><b>Interreligious relations (F1b-2).</b> The store also holds the per-world
 * stance OVERRIDE map (canonical pair key → {@link RelationStance}), empty by
 * default. The KINDRED/NEUTRAL baseline is derived fresh from god overlap by
 * {@link Relations} and never stored; only later writers populate overrides.</p>
 *
 * <p>String-keyed (the stable religion id, e.g. {@code "sunstead"}) — religions have
 * a natural string identity referenced everywhere (beliefs / patron faith / god
 * links), so the 1b migration is a lookup-source swap, not an id-type change.
 * {@link Religion} stays an immutable record; per-world mutation is replace-in-map
 * ({@link #put}), not setters.</p>
 */
public class ReligionSavedData extends SavedData {

    /** Unique world-storage name (sibling to {@code life_in_the_village_rites}). */
    private static final String STORAGE = "life_in_the_village_religions";

    public static final SavedDataType<ReligionSavedData> TYPE = new SavedDataType<>(
            STORAGE,
            ReligionSavedData::new,
            RecordCodecBuilder.create(i -> i.group(
                    Religion.CODEC.listOf().optionalFieldOf("religions", List.of())
                            .forGetter(d -> new ArrayList<>(d.religions.values())),
                    // F1b 2 — interreligious stance OVERRIDES (canonical "a|b" pair key →
                    // stance name). Empty by default; only later writers (kingdom conflict
                    // / dynamism) populate it. The KINDRED/NEUTRAL baseline is derived
                    // fresh from god overlap and never stored.
                    com.mojang.serialization.Codec.unboundedMap(
                                    com.mojang.serialization.Codec.STRING,
                                    com.mojang.serialization.Codec.STRING)
                            .optionalFieldOf("relationOverrides", Map.of())
                            .forGetter(d -> Map.copyOf(d.relationOverrides))
            ).apply(i, ReligionSavedData::fromCodec)));

    /** Live instances, stable (insertion / seed) order. */
    private final Map<String, Religion> religions = new LinkedHashMap<>();
    /** F1b 2 — per-world stance overrides: canonical "a|b" pair key → stance name.
     *  Empty by default; the derived baseline is never stored here. */
    private final Map<String, String> relationOverrides = new LinkedHashMap<>();

    public ReligionSavedData() {}

    private static ReligionSavedData fromCodec(List<Religion> loaded,
                                               Map<String, String> overrides) {
        ReligionSavedData d = new ReligionSavedData();
        if (loaded != null) for (Religion r : loaded) {
            if (r != null) d.religions.put(r.id(), r);
        }
        if (overrides != null) d.relationOverrides.putAll(overrides);
        return d;
    }

    /**
     * The per-world store for {@code level}, seeding the four static templates on
     * first access of a fresh (empty) world. A loaded world returns its restored set
     * untouched (seeding is only-when-empty, so saved state is never clobbered).
     */
    public static ReligionSavedData get(ServerLevel level) {
        ReligionSavedData d = level.getDataStorage().computeIfAbsent(TYPE);
        d.seedIfEmpty();
        return d;
    }

    /** Copy every {@link ReligionRegistry} template into the store, but only when the
     *  store is empty (a fresh world). No-op once seeded or once a saved set loaded. */
    private void seedIfEmpty() {
        if (!religions.isEmpty()) return;
        for (Religion r : ReligionRegistry.templates()) religions.put(r.id(), r);
        setDirty();
    }

    // ── Lookups (the read surface 1b migrates callers onto) ──────────────────

    public Optional<Religion> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(religions.get(id));
    }

    public Religion get(String id) {
        return id == null ? null : religions.get(id);
    }

    public Collection<Religion> all() {
        return java.util.Collections.unmodifiableCollection(new ArrayList<>(religions.values()));
    }

    // ── Mutation entry point (for later stages; no caller yet) ───────────────

    /** Replace-in-map by {@link Religion#id()} (the record is immutable, so per-world
     *  change is a put of the updated instance) + persist. No caller in 1a. */
    public void put(Religion r) {
        if (r == null) return;
        religions.put(r.id(), r);
        setDirty();
    }

    // ── Interreligious stance overrides (F1b 2) ──────────────────────────────

    /** The canonical symmetric pair key for two religion ids — sorted + "|"-joined
     *  (ids are lowercase tokens with no "|"), so {@code (a,b)} and {@code (b,a)} map
     *  to one entry. */
    private static String pairKey(String idA, String idB) {
        return idA.compareTo(idB) <= 0 ? idA + "|" + idB : idB + "|" + idA;
    }

    /** The explicit stance override for the pair, or empty when none is set (then the
     *  caller falls back to the derived god-overlap baseline). Unknown stance names
     *  (a corrupt/old save) degrade to empty rather than throwing. */
    public Optional<RelationStance> relationOverride(String idA, String idB) {
        if (idA == null || idB == null) return Optional.empty();
        String name = relationOverrides.get(pairKey(idA, idB));
        if (name == null) return Optional.empty();
        try {
            return Optional.of(RelationStance.valueOf(name));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Sets (or replaces) the stance override for the pair + persists. The mutation
     *  seam for later writers; no caller in F1b 2. */
    public void setRelationOverride(String idA, String idB, RelationStance stance) {
        if (idA == null || idB == null || stance == null) return;
        relationOverrides.put(pairKey(idA, idB), stance.name());
        setDirty();
    }

    public void markDirty() { setDirty(); }
}
