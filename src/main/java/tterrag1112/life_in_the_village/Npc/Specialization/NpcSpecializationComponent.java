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

    public NpcSpecializationComponent() {}

    private NpcSpecializationComponent(Optional<Identifier> initial) {
        this.currentId = initial.orElse(null);
    }

    public Optional<Identifier> currentId() {
        return Optional.ofNullable(currentId);
    }

    public Optional<SpecializationDef> get() {
        return currentId().flatMap(NpcSpecializationTypes::byId);
    }

    /**
     * Assigns {@code spec}. When {@code force} is false the call
     * consults {@link SpecializationGate#qualifies} and rejects the
     * assignment (returns false) if the NPC doesn't meet the skill
     * requirements. {@code force=true} is used by save migration and
     * explicit debug grants.
     *
     * @return true if assignment took effect.
     */
    public boolean assign(SpecializationDef spec, TownspersonMob owner, boolean force) {
        if (spec == null) { currentId = null; return true; }
        if (!force && !SpecializationGate.qualifies(spec, owner)) return false;
        currentId = spec.name();
        return true;
    }

    public void clear() { currentId = null; }

    // ── Persistence ──────────────────────────────────────────────────────

    public static final Codec<NpcSpecializationComponent> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.optionalFieldOf("id")
                    .forGetter(NpcSpecializationComponent::currentId)
    ).apply(i, NpcSpecializationComponent::new));

    public void save(ValueOutput output) { output.store(SAVE_KEY, CODEC, this); }

    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        this.currentId = read.get().currentId;
        return true;
    }
}
