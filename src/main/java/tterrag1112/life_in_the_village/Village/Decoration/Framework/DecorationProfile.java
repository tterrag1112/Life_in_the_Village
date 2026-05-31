package tterrag1112.life_in_the_village.Village.Decoration.Framework;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Doc 01 §"DecorationProfile" — declares everything the matcher
 * needs to score and place one decoration piece.
 *
 * <p>Parallel to the building-placement profile system but
 * intentionally separate. Registered in
 * {@link DecorationProfileRegistry} keyed by {@link #pieceId} —
 * doc 01 §"DecorationProfile": "Piece ID is
 * {@code {culture}/{category}/{name}} matching the NBT path".</p>
 *
 * @param pieceId          stable identifier (doubles as resource
 *                         path under {@code structures/decoration/})
 * @param requiredTags     tags every matching slot MUST advertise
 * @param preferredTags    tags that boost the matcher's score
 * @param primaryWeight    matcher weight when treated as a primary
 *                         tier candidate
 * @param secondaryWeight  matcher weight when treated as a secondary
 *                         tier candidate
 * @param backfillWeight   matcher weight when treated as a backfill
 *                         tier candidate
 * @param footprintSize    the piece's bounding extent (max of
 *                         width / length); a slot with smaller
 *                         {@code footprintBudget} won't match
 * @param culture          culture id for the fallback chain (use
 *                         {@code "default"} for the universal pool)
 * @param biomeConstraint  optional biome resource location; when
 *                         present, the matcher only places this
 *                         piece in slots whose biome equals the id
 * @param anchorRule       structural-attachment requirement
 *                         (see {@link AnchorRule})
 * @param minTier          B2.2 — minimum
 *                         {@link VillageSizeTier} the piece is
 *                         eligible for. The matcher discards profiles
 *                         whose {@code minTier} is strictly above the
 *                         village's current tier. Defaults to
 *                         {@link VillageSizeTier#HAMLET} (always
 *                         eligible). Doc 05 §"Tier gating": "Hamlets
 *                         get a minimal subset; villages add
 *                         lampposts and notice boards; towns add
 *                         handcarts, woodpiles, wells; cities add
 *                         shrines, communal ovens, mounting blocks."
 *
 * <p><b>Deviation from doc 01 spec.</b> The doc lists
 * {@code Optional<Biome> biomeConstraint}. {@link
 * net.minecraft.world.level.biome.Biome} doesn't have a stand-alone
 * codec (it needs registry context), so this implementation stores
 * {@link Optional}{@code <}{@link Identifier}{@code >} — the biome
 * resource location. The matcher resolves and compares at match
 * time via {@code level.getBiome(pos).unwrapKey()}.</p>
 */
public record DecorationProfile(
        String pieceId,
        Set<DecorationTag> requiredTags,
        Set<DecorationTag> preferredTags,
        int primaryWeight,
        int secondaryWeight,
        int backfillWeight,
        int footprintSize,
        String culture,
        Optional<Identifier> biomeConstraint,
        AnchorRule anchorRule,
        VillageSizeTier minTier) {

    public DecorationProfile {
        if (pieceId == null || pieceId.isBlank()) {
            throw new IllegalArgumentException("DecorationProfile.pieceId must be non-blank");
        }
        if (culture == null || culture.isBlank()) {
            throw new IllegalArgumentException("DecorationProfile.culture must be non-blank (use \"default\" if unspecified)");
        }
        if (anchorRule == null) {
            throw new IllegalArgumentException("DecorationProfile.anchorRule must be non-null");
        }
        // EnumSet.copyOf(Collection) throws on empty non-EnumSet
        // input; rebuild via noneOf + addAll to handle the empty
        // case cleanly.
        EnumSet<DecorationTag> safeRequired = EnumSet.noneOf(DecorationTag.class);
        if (requiredTags != null) safeRequired.addAll(requiredTags);
        requiredTags = safeRequired;
        EnumSet<DecorationTag> safePreferred = EnumSet.noneOf(DecorationTag.class);
        if (preferredTags != null) safePreferred.addAll(preferredTags);
        preferredTags = safePreferred;
        biomeConstraint = biomeConstraint == null
                ? Optional.empty() : biomeConstraint;
        if (footprintSize < 0) footprintSize = 0;
        if (minTier == null) minTier = VillageSizeTier.HAMLET;
    }

    /** Backwards-compatible constructor — preserves call sites that
     *  predate B2.2's tier gating. Defaults {@code minTier} to
     *  {@link VillageSizeTier#HAMLET} (always eligible). */
    public DecorationProfile(String pieceId,
                             Set<DecorationTag> requiredTags,
                             Set<DecorationTag> preferredTags,
                             int primaryWeight, int secondaryWeight,
                             int backfillWeight, int footprintSize,
                             String culture,
                             Optional<Identifier> biomeConstraint,
                             AnchorRule anchorRule) {
        this(pieceId, requiredTags, preferredTags,
                primaryWeight, secondaryWeight, backfillWeight,
                footprintSize, culture, biomeConstraint, anchorRule,
                VillageSizeTier.HAMLET);
    }

    /** True iff this profile is allowed at {@code villageTier} —
     *  i.e. {@code minTier} is at or below the village's current
     *  size tier. */
    public boolean tierAllows(VillageSizeTier villageTier) {
        if (villageTier == null) return true;
        return minTier.ordinal() <= villageTier.ordinal();
    }

    /** True iff the slot satisfies this profile's hard filters
     *  ({@link #requiredTags} ⊆ slot.tags AND
     *  {@link #footprintSize} ≤ slot.footprintBudget). Biome and
     *  culture constraints are evaluated by
     *  {@link DecorationProfileRegistry#eligibleFor}. */
    public boolean matchesSlot(DecorationSlot slot) {
        if (slot == null) return false;
        if (!slot.hasAll(requiredTags)) return false;
        return footprintSize <= slot.footprintBudget();
    }

    // ── Codec ────────────────────────────────────────────────────────────

    public static final Codec<DecorationProfile> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("pieceId")
                            .forGetter(DecorationProfile::pieceId),
                    DecorationSlot.TAG_SET_CODEC
                            .optionalFieldOf("requiredTags",
                                    EnumSet.noneOf(DecorationTag.class))
                            .forGetter(DecorationProfile::requiredTags),
                    DecorationSlot.TAG_SET_CODEC
                            .optionalFieldOf("preferredTags",
                                    EnumSet.noneOf(DecorationTag.class))
                            .forGetter(DecorationProfile::preferredTags),
                    Codec.INT.fieldOf("primaryWeight")
                            .forGetter(DecorationProfile::primaryWeight),
                    Codec.INT.fieldOf("secondaryWeight")
                            .forGetter(DecorationProfile::secondaryWeight),
                    Codec.INT.fieldOf("backfillWeight")
                            .forGetter(DecorationProfile::backfillWeight),
                    Codec.INT.fieldOf("footprintSize")
                            .forGetter(DecorationProfile::footprintSize),
                    Codec.STRING.fieldOf("culture")
                            .forGetter(DecorationProfile::culture),
                    Identifier.CODEC.optionalFieldOf("biomeConstraint")
                            .forGetter(DecorationProfile::biomeConstraint),
                    AnchorRule.CODEC.fieldOf("anchorRule")
                            .forGetter(DecorationProfile::anchorRule),
                    Codec.STRING.xmap(VillageSizeTier::valueOf, Enum::name)
                            .optionalFieldOf("minTier", VillageSizeTier.HAMLET)
                            .forGetter(DecorationProfile::minTier)
            ).apply(instance, DecorationProfile::new));
}
