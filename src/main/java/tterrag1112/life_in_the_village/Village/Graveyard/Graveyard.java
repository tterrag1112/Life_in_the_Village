package tterrag1112.life_in_the_village.Village.Graveyard;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Religion Rework R5a — a village's cemetery district: a placed centre + a fixed
 * grid of grave SLOTS (the cemetery {@code HEADSTONE} decoration-slot semantics,
 * placed manually this phase — auto-layout is deferred) + the {@link Grave}s that
 * occupy them. Capacity is the slot count; when full it <b>reuses the oldest</b>
 * grave's slot (bounded, no expansion).
 */
public final class Graveyard {

    private final UUID villageId;
    private final BlockPos centre;
    private final List<BlockPos> slots;
    private final List<Grave> graves;

    public Graveyard(UUID villageId, BlockPos centre, List<BlockPos> slots, List<Grave> graves) {
        this.villageId = villageId;
        this.centre    = centre;
        this.slots     = new ArrayList<>(slots == null ? List.of() : slots);
        this.graves    = new ArrayList<>(graves == null ? List.of() : graves);
    }

    public static final Codec<Graveyard> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("villageId").forGetter(g -> g.villageId),
            BlockPos.CODEC.fieldOf("centre").forGetter(g -> g.centre),
            BlockPos.CODEC.listOf().optionalFieldOf("slots", List.of()).forGetter(g -> g.slots),
            Grave.CODEC.listOf().optionalFieldOf("graves", List.of()).forGetter(g -> g.graves)
    ).apply(i, Graveyard::new));

    public UUID villageId()       { return villageId; }
    public BlockPos centre()      { return centre; }
    public List<BlockPos> slots() { return List.copyOf(slots); }
    public List<Grave> graves()   { return List.copyOf(graves); }
    public int capacity()         { return slots.size(); }
    public boolean isFull()       { return graves.size() >= slots.size(); }

    /** The grave of {@code deceasedId}, if buried here. */
    public Optional<Grave> graveOf(UUID deceasedId) {
        return graves.stream().filter(g -> g.deceasedId().equals(deceasedId)).findFirst();
    }

    /** SR2 — inscribe {@code deceasedId}'s grave epitaph (replace the immutable record).
     *  Returns true when the grave was found + inscribed. */
    public boolean inscribe(UUID deceasedId, String epitaph) {
        for (int i = 0; i < graves.size(); i++) {
            Grave g = graves.get(i);
            if (g.deceasedId().equals(deceasedId)) {
                graves.set(i, new Grave(g.deceasedId(), g.name(), g.deathTick(), g.slot(), epitaph));
                return true;
            }
        }
        return false;
    }

    /**
     * Buries {@code deceasedId} at a free slot, or — when the cemetery is full —
     * reuses the oldest grave's slot (capacity-bounded). Returns the new grave,
     * or empty when the district has no slots at all.
     */
    public Optional<Grave> bury(UUID deceasedId, String name, long deathTick) {
        if (slots.isEmpty()) return Optional.empty();
        if (graveOf(deceasedId).isPresent()) return graveOf(deceasedId); // idempotent
        BlockPos slot = freeSlot().orElse(null);
        if (slot == null) {
            // Full → reuse the oldest grave's slot.
            Grave oldest = graves.stream().min(Comparator.comparingLong(Grave::deathTick)).orElse(null);
            if (oldest != null) { graves.remove(oldest); slot = oldest.slot(); }
            else slot = slots.get(0);
        }
        Grave g = new Grave(deceasedId, name, deathTick, slot, "");
        graves.add(g);
        return Optional.of(g);
    }

    private Optional<BlockPos> freeSlot() {
        Set<BlockPos> used = new HashSet<>();
        for (Grave g : graves) used.add(g.slot());
        return slots.stream().filter(s -> !used.contains(s)).findFirst();
    }

    /** A representative grave position for {@code HobbyLocation.GRAVEYARD} (the
     *  most recent grave, else the district centre). */
    public BlockPos visitTarget() {
        if (graves.isEmpty()) return centre;
        return graves.stream().max(Comparator.comparingLong(Grave::deathTick))
                .map(Grave::slot).orElse(centre);
    }
}
