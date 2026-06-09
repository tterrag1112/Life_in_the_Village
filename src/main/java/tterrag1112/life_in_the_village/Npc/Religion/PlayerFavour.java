package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Divine Layer V1 — a player's <b>Divine Favour</b> ledger: a per-deity standing,
 * <b>distinct from piety</b> (piety is belief; favour is the deity's regard) and
 * spendable (V2 miracles). Pure data — the earning, decay, cap, and deity-demand
 * weighting all live in {@link DivineFavour}; this just persists, per religion id,
 * the last-known favour {@code amount} and the {@code lastTick} it was rebased, so
 * {@link DivineFavour} can relax it lazily (no per-tick scan).
 *
 * <p>Stored on the player's religion data in {@code RiteSavedData} (sibling to the
 * player {@link PietyComponent}).</p>
 */
public final class PlayerFavour {

    /** One deity's stored favour: the amount at {@code lastTick} (relaxation toward
     *  the piety-tier equilibrium is applied lazily by {@link DivineFavour}). */
    public record Entry(float amount, long lastTick) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("amount").forGetter(Entry::amount),
                Codec.LONG.fieldOf("lastTick").forGetter(Entry::lastTick)
        ).apply(i, Entry::new));
    }

    private final Map<String, Entry> byReligion = new LinkedHashMap<>();

    public PlayerFavour() {}

    /** The stored (un-relaxed) entry for {@code religionId}, or null. */
    public Entry raw(String religionId) {
        return religionId == null ? null : byReligion.get(religionId);
    }

    /** Rebases the stored favour for {@code religionId} to {@code amount} at
     *  {@code tick}. Exactly zero drops the entry (no clutter); a NEGATIVE amount is
     *  KEPT — it's displeasure (Divine Layer V4, signed favour). */
    public void set(String religionId, float amount, long tick) {
        if (religionId == null) return;
        if (amount == 0f) byReligion.remove(religionId);
        else byReligion.put(religionId, new Entry(amount, tick));
    }

    /** Read-only snapshot (religion id → stored entry). */
    public Map<String, Entry> all() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byReligion));
    }

    public boolean isEmpty() { return byReligion.isEmpty(); }

    // ── Persistence ──────────────────────────────────────────────────────────

    public static final Codec<PlayerFavour> CODEC =
            Codec.unboundedMap(Codec.STRING, Entry.CODEC)
                    .xmap(PlayerFavour::fromMap, p -> Map.copyOf(p.byReligion));

    private static PlayerFavour fromMap(Map<String, Entry> map) {
        PlayerFavour p = new PlayerFavour();
        if (map != null) p.byReligion.putAll(map);
        return p;
    }
}
