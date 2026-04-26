package tterrag1112.life_in_the_village.Npc.Health;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Per-NPC remedy stash. Healers carry one; players carry one via
 * {@link tterrag1112.life_in_the_village.Npc.Health.PlayerRemedyState}.
 *
 * <p>Capped at {@link #CAPACITY} entries — production halts when full
 * (spec line 124-127, "produce remedies between treatments"). Expired
 * remedies are pruned by the daily tick.</p>
 */
public final class HealerInventory {

    public static final int CAPACITY = 24;
    private static final String SAVE_KEY = "healerInventory";

    private final List<Remedy> remedies = new ArrayList<>();

    public List<Remedy> remedies()     { return Collections.unmodifiableList(new ArrayList<>(remedies)); }
    public boolean      isFull()       { return remedies.size() >= CAPACITY; }
    public int          size()         { return remedies.size(); }

    public boolean add(Remedy r) {
        if (r == null || isFull()) return false;
        remedies.add(r);
        return true;
    }

    /** Removes and returns the first remedy whose type treats the
     *  given condition, or empty if none on hand. Picks higher-
     *  potency remedies first. */
    public Optional<Remedy> takeFor(HealthCondition condition) {
        int bestIdx = -1;
        int bestPot = -1;
        for (int i = 0; i < remedies.size(); i++) {
            Remedy r = remedies.get(i);
            if (r.type().treats(condition) && r.potency() > bestPot) {
                bestIdx = i;
                bestPot = r.potency();
            }
        }
        if (bestIdx < 0) return Optional.empty();
        return Optional.of(remedies.remove(bestIdx));
    }

    /** Drops expired remedies, returning the count removed. */
    public int pruneExpired(long currentTick) {
        int before = remedies.size();
        remedies.removeIf(r -> r.isExpired(currentTick));
        return before - remedies.size();
    }

    public boolean hasRemedyFor(HealthCondition condition) {
        for (Remedy r : remedies) if (r.type().treats(condition)) return true;
        return false;
    }

    // ── Persistence ───────────────────────────────────────────────────────

    public void save(ValueOutput output) { output.store(SAVE_KEY, CODEC, this); }

    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        HealerInventory loaded = read.get();
        remedies.clear();
        remedies.addAll(loaded.remedies);
        return true;
    }

    public static final Codec<HealerInventory> CODEC = RecordCodecBuilder.create(i -> i.group(
            Remedy.CODEC.listOf().optionalFieldOf("remedies", List.of())
                    .forGetter(h -> List.copyOf(h.remedies))
    ).apply(i, HealerInventory::fromCodec));

    private static HealerInventory fromCodec(List<Remedy> remedies) {
        HealerInventory h = new HealerInventory();
        if (remedies != null) h.remedies.addAll(remedies);
        return h;
    }
}
