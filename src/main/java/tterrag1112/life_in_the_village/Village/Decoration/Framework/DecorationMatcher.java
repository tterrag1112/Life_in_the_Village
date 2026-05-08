package tterrag1112.life_in_the_village.Village.Decoration.Framework;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Doc 01 §"DecorationMatcher" — single-pass matcher that resolves
 * a list of {@link DecorationSlot}s against the registered
 * {@link DecorationProfile}s and produces a list of placements.
 *
 * <p>Algorithm:</p>
 * <ol>
 *   <li>Build (slot, profile) candidate pairs by iterating slots and
 *       calling {@link DecorationProfileRegistry#eligibleFor} for
 *       each.</li>
 *   <li>Score each pair: {@code primaryWeight × tagMatchCount + 0.1
 *       × slot.qualityScore}, plus +5 for preferred-tag intersection
 *       and +3 for anchor-rule alignment with the slot's tag class.</li>
 *   <li>Sort by score descending; ties broken by slot UUID then
 *       profile pieceId for stability.</li>
 *   <li>Greedy commit: walk the sorted list. For each pair, commit
 *       if the slot is unmatched and the profile hasn't been placed
 *       within {@link #CLUSTERING_LIMIT_BLOCKS} of an earlier
 *       commit.</li>
 *   <li>Slots without a matching profile burn silently. Profiles
 *       without a matching slot do nothing.</li>
 * </ol>
 *
 * <p>Determinism: no RNG. Same slot list + same registry → same
 * placements in the same order.</p>
 */
public final class DecorationMatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(DecorationMatcher.class);

    /** Doc 01 §"DecorationMatcher": "no two of the same profile
     *  within 8 blocks". */
    public static final int CLUSTERING_LIMIT_BLOCKS = 8;

    /** Bonus added to a candidate score when the profile's
     *  preferred-tag set intersects the slot's tags. */
    public static final double PREFERRED_TAG_BONUS = 5.0;

    /** Bonus added when the profile's anchor rule lines up with the
     *  slot's tag class (e.g. AGAINST_WALL piece in a FACADE_ORNAMENT
     *  slot). */
    public static final double ANCHOR_ALIGNMENT_BONUS = 3.0;

    /** Multiplier on the slot's quality score in the base score. */
    public static final double QUALITY_WEIGHT = 0.1;

    private DecorationMatcher() {}

    /**
     * Doc 01 entry point. Returns the placements committed by the
     * greedy walk. The placement list is in commit order (i.e. the
     * sort order, modulo skips for clustering / already-matched).
     *
     * @param slots             slot list from {@link DecorationSlotEmitter}
     * @param registry          the (eventual) profile pool — empty
     *                          today; the matcher logs "no profiles"
     *                          and returns an empty placement list
     * @param villageId         attached to every placement
     * @param villageCulture    drives the registry's culture fallback
     * @param slotBiomes        per-slot biome resource locations,
     *                          empty when biome data isn't available
     *                          (the registry then drops profiles
     *                          with biome constraints)
     * @param tickNow           server tick, recorded on every
     *                          placement
     */
    public static List<DecorationPlacement> match(
            List<DecorationSlot> slots,
            DecorationProfileRegistry registry,
            UUID villageId,
            String villageCulture,
            VillageSizeTier villageTier,
            Map<UUID, net.minecraft.resources.Identifier> slotBiomes,
            long tickNow) {

        if (slots == null || slots.isEmpty()) return List.of();
        if (registry == null || registry.size() == 0) {
            LOGGER.info("DecorationMatcher: village {} — {} slots emitted "
                    + "but no DecorationProfiles registered. Returning "
                    + "no placements.", villageId, slots.size());
            return List.of();
        }
        Map<UUID, net.minecraft.resources.Identifier> biomes =
                slotBiomes != null ? slotBiomes : Map.of();
        VillageSizeTier resolvedTier = villageTier != null
                ? villageTier : VillageSizeTier.CITY;

        // ── Pass 1: enumerate scored candidates ──────────────────────────
        record Candidate(DecorationSlot slot, DecorationProfile profile, double score) {}
        List<Candidate> candidates = new ArrayList<>();
        for (DecorationSlot slot : slots) {
            Optional<net.minecraft.resources.Identifier> biome =
                    Optional.ofNullable(biomes.get(slot.slotId()));
            List<DecorationProfile> eligible =
                    registry.eligibleFor(slot, villageCulture, resolvedTier, biome);
            for (DecorationProfile p : eligible) {
                candidates.add(new Candidate(slot, p, score(slot, p)));
            }
        }
        if (candidates.isEmpty()) return List.of();

        // ── Pass 2: sort by score desc; stable tie-break ────────────────
        candidates.sort(
                Comparator.<Candidate>comparingDouble(c -> -c.score())
                        .thenComparing(c -> c.slot().slotId())
                        .thenComparing(c -> c.profile().pieceId()));

        // ── Pass 3: greedy commit with per-profile clustering check ────
        Set<UUID> matchedSlots = new HashSet<>();
        Map<String, List<BlockPos>> placedByProfile = new HashMap<>();
        List<DecorationPlacement> placements = new ArrayList<>();

        int clusterLimitSq = CLUSTERING_LIMIT_BLOCKS * CLUSTERING_LIMIT_BLOCKS;
        for (Candidate c : candidates) {
            if (matchedSlots.contains(c.slot().slotId())) continue;
            List<BlockPos> existing =
                    placedByProfile.getOrDefault(c.profile().pieceId(), List.of());
            boolean tooClose = false;
            for (BlockPos prev : existing) {
                int dx = prev.getX() - c.slot().pos().getX();
                int dz = prev.getZ() - c.slot().pos().getZ();
                if (dx * dx + dz * dz < clusterLimitSq) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) continue;

            DecorationPlacement placement = new DecorationPlacement(
                    c.slot().slotId(),
                    villageId,
                    c.slot().pos(),
                    c.slot().facing(),
                    c.profile().pieceId(),
                    c.profile().culture(),
                    tickNow);
            placements.add(placement);
            matchedSlots.add(c.slot().slotId());
            placedByProfile
                    .computeIfAbsent(c.profile().pieceId(), k -> new ArrayList<>())
                    .add(c.slot().pos());
        }

        return placements;
    }

    // ── Scoring ──────────────────────────────────────────────────────────

    private static double score(DecorationSlot slot, DecorationProfile profile) {
        // Tag match count: how many profile.requiredTags are
        // present on the slot. By the registry's filter contract
        // every required tag is present, so this equals
        // requiredTags.size(); we still recompute here so the
        // formula reads correctly.
        int tagMatches = 0;
        for (DecorationTag t : profile.requiredTags()) {
            if (slot.tags().contains(t)) tagMatches++;
        }
        double s = profile.primaryWeight() * tagMatches
                + slot.qualityScore() * QUALITY_WEIGHT;
        if (slot.preferredTagHits(profile.preferredTags()) > 0) {
            s += PREFERRED_TAG_BONUS;
        }
        if (anchorAlignment(slot, profile)) s += ANCHOR_ALIGNMENT_BONUS;
        return s;
    }

    /** Doc 01 anchor alignment: AGAINST_WALL pieces line up with
     *  FACADE_ORNAMENT or BUILDING_GAP slots; ON_GROUND aligns
     *  with everything else; FLOATING is universal. */
    private static boolean anchorAlignment(DecorationSlot slot,
                                           DecorationProfile profile) {
        return switch (profile.anchorRule()) {
            case AGAINST_WALL -> slot.tags().contains(DecorationTag.FACADE_ORNAMENT)
                    || slot.tags().contains(DecorationTag.BUILDING_GAP);
            case ON_GROUND -> !slot.tags().contains(DecorationTag.FACADE_ORNAMENT);
            case FLOATING -> true;
        };
    }
}
