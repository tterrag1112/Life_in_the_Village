package tterrag1112.life_in_the_village.Village.Decoration.Framework;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Doc 01 §"DecorationSlot record" — a placement opportunity emitted
 * by the (eventual) {@code DecorationSlotEmitter} and consumed by
 * the {@code DecorationMatcher}.
 *
 * <p>Parallel to {@link tterrag1112.life_in_the_village.Village
 * .Planning.Zoning.PlacementSlot} (building placement) but
 * intentionally <em>separate</em>: no shared {@code SlotTag} enum,
 * no shared registry, and no shared matcher path. The two
 * subsystems should never accidentally route slots through each
 * other's pipeline.</p>
 *
 * <p><b>Ephemeral.</b> Doc 01: "Lives only during a DecorationPass
 * invocation." The codec exists for in-memory serialisation (debug
 * commands, tests, future cross-system messaging) but the slot
 * does <em>not</em> persist across save/load. Nothing in
 * {@code VillageSavedData} stores it.</p>
 *
 * @param slotId           opaque identity for this slot, regenerated
 *                         per emission
 * @param pos              ideal target position; the matcher may
 *                         nudge during placement
 * @param facing           direction toward the nearest road or
 *                         building face — drives orientation
 * @param tags             set of {@link DecorationTag}s the slot
 *                         advertises; matchers filter profiles
 *                         against this set
 * @param footprintBudget  largest piece footprint (max of width /
 *                         length) the slot can accommodate
 * @param qualityScore     emitter-derived ranking, 0–100 — higher
 *                         is better; matcher uses this in scoring
 * @param parentId         id of the building / plot / village this
 *                         slot belongs to; {@code null} for
 *                         village-level slots like village boundary
 *                         markers
 * @param contextRoad      nearest road centerline points, used by
 *                         the matcher to derive orientation when
 *                         {@code facing} alone isn't enough; empty
 *                         list when no road is nearby
 */
public record DecorationSlot(
        UUID slotId,
        BlockPos pos,
        Direction facing,
        Set<DecorationTag> tags,
        int footprintBudget,
        int qualityScore,
        @Nullable UUID parentId,
        List<BlockPos> contextRoad) {

    public DecorationSlot {
        // Defensive copy + canonical empty set / list values.
        // Note: EnumSet.copyOf(Collection) throws on empty non-EnumSet
        // input, so we always go through the safe noneOf-plus-addAll
        // path to handle the empty-Set case cleanly.
        EnumSet<DecorationTag> safeTags = EnumSet.noneOf(DecorationTag.class);
        if (tags != null) safeTags.addAll(tags);
        tags = safeTags;
        contextRoad = contextRoad == null
                ? List.of() : List.copyOf(contextRoad);
        // qualityScore is documented as 0..100 but we don't reject
        // out-of-range values — emitters that compute scores
        // probabilistically may overshoot slightly. Clamp instead.
        if (qualityScore < 0) qualityScore = 0;
        if (qualityScore > 100) qualityScore = 100;
        if (footprintBudget < 0) footprintBudget = 0;
    }

    /** Returns true iff every required tag is present on this slot. */
    public boolean hasAll(Set<DecorationTag> required) {
        return tags.containsAll(required);
    }

    /** Counts how many of the requested tags are present. */
    public int preferredTagHits(Set<DecorationTag> preferred) {
        int n = 0;
        for (DecorationTag t : preferred) if (tags.contains(t)) n++;
        return n;
    }

    // ── Codec ────────────────────────────────────────────────────────────

    /** Reusable {@code Set<DecorationTag>} codec. EnumSet on decode
     *  (empty-safe — {@link EnumSet#copyOf(java.util.Collection)}
     *  rejects empty non-EnumSet inputs, so we rebuild manually). */
    public static final Codec<Set<DecorationTag>> TAG_SET_CODEC =
            DecorationTag.CODEC.listOf().<Set<DecorationTag>>xmap(
                    list -> {
                        EnumSet<DecorationTag> out = EnumSet.noneOf(DecorationTag.class);
                        out.addAll(list);
                        return out;
                    },
                    set -> List.copyOf(set));

    /**
     * RecordCodecBuilder per doc 00 conventions. Tags are encoded as
     * a list and re-collected into an {@link EnumSet} on decode;
     * {@code parentId} is optional; {@code contextRoad} defaults to
     * empty when omitted on decode.
     */
    public static final Codec<DecorationSlot> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUIDUtil.CODEC.fieldOf("slotId")
                            .forGetter(DecorationSlot::slotId),
                    BlockPos.CODEC.fieldOf("pos")
                            .forGetter(DecorationSlot::pos),
                    Direction.CODEC.fieldOf("facing")
                            .forGetter(DecorationSlot::facing),
                    TAG_SET_CODEC.fieldOf("tags")
                            .forGetter(DecorationSlot::tags),
                    Codec.INT.fieldOf("footprintBudget")
                            .forGetter(DecorationSlot::footprintBudget),
                    Codec.INT.fieldOf("qualityScore")
                            .forGetter(DecorationSlot::qualityScore),
                    UUIDUtil.CODEC.optionalFieldOf("parentId")
                            .forGetter(s -> Optional.ofNullable(s.parentId)),
                    BlockPos.CODEC.listOf().optionalFieldOf("contextRoad", List.of())
                            .forGetter(DecorationSlot::contextRoad)
            ).apply(instance, DecorationSlot::fromCodec));

    private static DecorationSlot fromCodec(UUID slotId, BlockPos pos,
                                            Direction facing,
                                            Set<DecorationTag> tags,
                                            int footprintBudget,
                                            int qualityScore,
                                            Optional<UUID> parentId,
                                            List<BlockPos> contextRoad) {
        return new DecorationSlot(slotId, pos, facing, tags, footprintBudget,
                qualityScore, parentId.orElse(null), contextRoad);
    }
}
