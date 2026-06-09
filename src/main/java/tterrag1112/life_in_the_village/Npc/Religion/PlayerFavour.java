package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Divine Layer V1 — a player's <b>Divine Favour</b> ledger: a per-god standing,
 * <b>distinct from piety</b> (piety is belief; favour is the god's regard) and
 * spendable (V2 miracles). Pure data — the earning, decay, cap, and deity-demand
 * weighting all live in {@link DivineFavour}; this just persists, <b>per god id</b>
 * (F1a sub-stage 3a — re-keyed from religion id to god id, so a player's standing
 * with each god is independent once religions venerate several), the last-known
 * favour {@code amount} and the {@code lastTick} it was rebased, so
 * {@link DivineFavour} can relax it lazily (no per-tick scan).
 *
 * <p>Stored on the player's religion data in {@code RiteSavedData} (sibling to the
 * player {@link PietyComponent}).</p>
 */
public final class PlayerFavour {

    /** One god's stored favour: the amount at {@code lastTick} (relaxation toward
     *  the piety-tier equilibrium is applied lazily by {@link DivineFavour}). */
    public record Entry(float amount, long lastTick) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("amount").forGetter(Entry::amount),
                Codec.LONG.fieldOf("lastTick").forGetter(Entry::lastTick)
        ).apply(i, Entry::new));
    }

    /** god id → stored favour entry (F1a sub-stage 3a — re-keyed from religion id). */
    private final Map<String, Entry> byGod = new LinkedHashMap<>();

    public PlayerFavour() {}

    /** The stored (un-relaxed) entry for {@code godId}, or null. */
    public Entry raw(String godId) {
        return godId == null ? null : byGod.get(godId);
    }

    /** Rebases the stored favour for {@code godId} to {@code amount} at {@code tick}.
     *  Exactly zero drops the entry (no clutter); a NEGATIVE amount is KEPT — it's
     *  displeasure (Divine Layer V4, signed favour). */
    public void set(String godId, float amount, long tick) {
        if (godId == null) return;
        if (amount == 0f) byGod.remove(godId);
        else byGod.put(godId, new Entry(amount, tick));
    }

    /** Read-only snapshot (god id → stored entry). */
    public Map<String, Entry> all() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byGod));
    }

    public boolean isEmpty() { return byGod.isEmpty(); }

    // ── Persistence (codec shape unchanged — only the semantic key changed) ───

    public static final Codec<PlayerFavour> CODEC =
            Codec.unboundedMap(Codec.STRING, Entry.CODEC)
                    .xmap(PlayerFavour::fromMap, p -> Map.copyOf(p.byGod));

    private static PlayerFavour fromMap(Map<String, Entry> map) {
        PlayerFavour p = new PlayerFavour();
        if (map != null) p.byGod.putAll(map);
        return p;
    }
}
