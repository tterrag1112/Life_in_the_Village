package tterrag1112.life_in_the_village.Npc.Specialization;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Skills.SpecializationGate;

import java.util.Optional;

/**
 * Phase 6.3.2.c — single-slot specialization storage on a
 * {@link TownspersonMob}. Stores only the registry id; the full
 * {@link SpecializationDef} resolves via {@link NpcSpecializationTypes}
 * lookup so the canonical metadata (display name, requirements, payload)
 * lives in one place.
 *
 * <p>Persistence mirrors {@code VisitorState} / {@code SkillComponent}:
 * a single {@code SAVE_KEY} subtree containing the optional id.
 */
public final class NpcSpecializationComponent {

    private static final String SAVE_KEY = "npcSpecialization";

    private Identifier currentId;
    /** Phase 6.7.1 — when true, {@link #assign} refuses reassignment
     *  unless {@code force=true}. Locks are set by
     *  BuildingInhabitantRegistry initial spawn assignment and the
     *  {@code /liv npc lockspec} admin command. Allows specs without
     *  satisfied skill gates (admin-set / inhabitant-set) to survive
     *  drift / re-promotion attempts. */
    private boolean locked = false;

    public NpcSpecializationComponent() {}

    private NpcSpecializationComponent(Optional<Identifier> initial, boolean locked) {
        this.currentId = initial.orElse(null);
        this.locked = locked;
    }

    public Optional<Identifier> currentId() {
        return Optional.ofNullable(currentId);
    }

    public Optional<SpecializationDef> get() {
        return currentId().flatMap(NpcSpecializationTypes::byId);
    }

    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    /**
     * Assigns {@code spec}. When {@code force} is false the call
     * consults {@link SpecializationGate#qualifies} and rejects the
     * assignment (returns false) if the NPC doesn't meet the skill
     * requirements. {@code force=true} is used by save migration and
     * explicit debug grants.
     *
     * <p>Phase 6.7.1 — when {@link #isLocked()} is true, non-forced
     * assignments are also rejected. {@code force=true} bypasses the
     * lock; the locked flag itself is preserved across the change.</p>
     *
     * @return true if assignment took effect.
     */
    public boolean assign(SpecializationDef spec, TownspersonMob owner, boolean force) {
        if (spec == null) { currentId = null; return true; }
        if (!force && locked) return false;
        if (!force && !SpecializationGate.qualifies(spec, owner)) return false;
        currentId = spec.name();
        return true;
    }

    public void clear() { currentId = null; }

    // ── Persistence ──────────────────────────────────────────────────────

    public static final Codec<NpcSpecializationComponent> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.optionalFieldOf("id")
                    .forGetter(NpcSpecializationComponent::currentId),
            Codec.BOOL.optionalFieldOf("locked", false)
                    .forGetter(NpcSpecializationComponent::isLocked)
    ).apply(i, NpcSpecializationComponent::new));

    public void save(ValueOutput output) { output.store(SAVE_KEY, CODEC, this); }

    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        this.currentId = read.get().currentId;
        this.locked = read.get().locked;
        return true;
    }
}
