package tterrag1112.life_in_the_village.Blocks.Entity.custom;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import tterrag1112.life_in_the_village.Blocks.Entity.ModBlockEntities;
import tterrag1112.life_in_the_village.Village.Decoration.Subbuilding.SubBuildingType;

import java.util.Map;

/**
 * Doc 03 §"Detection by anchor block entities" — the block entity
 * that travels with a {@code SubBuildingAnchorBlock} inside an
 * authored structure NBT and tells the {@code SubBuildingScanner}
 * what kind of subbuilding the anchor marks.
 *
 * <p>Authoring workflow (doc 00 §"Authoring workflow for subbuilding
 * anchors"): place the anchor block inside the structure, save it,
 * then edit the resulting {@code .nbt} via an external NBT editor to
 * set the {@code subBuildingType} field on this block entity. The
 * scanner reads the field at building-placement time, replaces the
 * anchor with air, and registers a {@code SubBuilding} record on
 * {@code VillageSavedData}.</p>
 *
 * <p>The block entity is intentionally inert at runtime: it does not
 * tick, does not render anything custom, and does not retain state
 * after the scanner has consumed it. Optional override fields exist
 * for authors who want to skip flood-fill or override door
 * detection.</p>
 */
public class SubBuildingAnchorBlockEntity extends BlockEntity {

    /** Persisted state of the anchor — what the codec serializes. */
    public record State(
            SubBuildingType type,
            @Nullable BlockPos explicitMin,
            @Nullable BlockPos explicitMax,
            @Nullable BlockPos explicitDoorTarget,
            Map<String, String> hints
    ) {
        public State {
            // Either both bounds are present or neither is. If a tampered
            // save provides only one side, drop the override quietly
            // rather than failing the whole codec roundtrip.
            if ((explicitMin == null) != (explicitMax == null)) {
                explicitMin = null;
                explicitMax = null;
            }
            hints = hints == null ? Map.of() : Map.copyOf(hints);
        }

        public static final State DEFAULT =
                new State(SubBuildingType.STALL, null, null, null, Map.of());
    }

    public static final Codec<State> STATE_CODEC = RecordCodecBuilder.create(i -> i.group(
            SubBuildingType.CODEC.fieldOf("subBuildingType")
                    .forGetter(State::type),
            BlockPos.CODEC.optionalFieldOf("explicitMin")
                    .forGetter(s -> java.util.Optional.ofNullable(s.explicitMin())),
            BlockPos.CODEC.optionalFieldOf("explicitMax")
                    .forGetter(s -> java.util.Optional.ofNullable(s.explicitMax())),
            BlockPos.CODEC.optionalFieldOf("explicitDoorTarget")
                    .forGetter(s -> java.util.Optional.ofNullable(s.explicitDoorTarget())),
            Codec.unboundedMap(Codec.STRING, Codec.STRING)
                    .optionalFieldOf("hints", Map.of())
                    .forGetter(State::hints)
    ).apply(i, (type, min, max, door, hints) -> new State(
            type,
            min.orElse(null),
            max.orElse(null),
            door.orElse(null),
            hints)));

    private static final String SAVE_KEY = "subbuilding";

    private State state = State.DEFAULT;

    public SubBuildingAnchorBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.SUBBUILDING_ANCHOR_BE.get(), pos, blockState);
    }

    public State getState() {
        return state;
    }

    public SubBuildingType getSubBuildingType() {
        return state.type();
    }

    public @Nullable BlockPos getExplicitMin() {
        return state.explicitMin();
    }

    public @Nullable BlockPos getExplicitMax() {
        return state.explicitMax();
    }

    public @Nullable BlockPos getExplicitDoorTarget() {
        return state.explicitDoorTarget();
    }

    public Map<String, String> getHints() {
        return state.hints();
    }

    /** For runtime mutation (debug/admin commands, future configurator UI). */
    public void setState(State newState) {
        this.state = newState == null ? State.DEFAULT : newState;
        setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store(SAVE_KEY, STATE_CODEC, state);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.read(SAVE_KEY, STATE_CODEC).ifPresent(s -> this.state = s);
    }
}
