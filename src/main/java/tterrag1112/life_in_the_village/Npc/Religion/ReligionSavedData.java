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
                            .forGetter(d -> new ArrayList<>(d.religions.values()))
            ).apply(i, ReligionSavedData::fromCodec)));

    /** Live instances, stable (insertion / seed) order. */
    private final Map<String, Religion> religions = new LinkedHashMap<>();

    public ReligionSavedData() {}

    private static ReligionSavedData fromCodec(List<Religion> loaded) {
        ReligionSavedData d = new ReligionSavedData();
        if (loaded != null) for (Religion r : loaded) {
            if (r != null) d.religions.put(r.id(), r);
        }
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
        for (Religion r : ReligionRegistry.all()) religions.put(r.id(), r);
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

    public void markDirty() { setDirty(); }
}
