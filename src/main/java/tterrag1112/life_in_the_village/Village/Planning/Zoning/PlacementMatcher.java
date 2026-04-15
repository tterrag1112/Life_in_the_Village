// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Zoning/PlacementMatcher.java
package tterrag1112.life_in_the_village.Village.Planning.Zoning;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.LayoutSlot;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.VillageTypeData.StarterBuilding;

import java.util.*;

/**
 * Runs after every recipe's {@code compose()} returns. Consumes the
 * slot pool that primitives emitted via {@code pctx.offerSlot(...)}
 * and places any buildings still sitting in {@code pctx.remaining}.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li><b>Pre-pass.</b> The single {@code TOWN_HALL} instance (if
 *       still in remaining) claims the best {@code PRIME_CIVIC} slot
 *       whose {@code footprintBudget} fits it. If no such slot exists,
 *       falls back to any {@code SECONDARY_CIVIC}. This is the fix for
 *       the historical "town hall can't fit on the ring" failure.</li>
 *   <li><b>Pass 1 — core minimums.</b> For each type whose profile is
 *       {@code CORE}, place up to that type's resolved min count.
 *       Walks preference tiers top-to-bottom, scoring every candidate
 *       slot per tier and committing to the best. Terrain-resolution
 *       failures burn the slot and retry the next-best. If every tier
 *       is exhausted, logs {@code CORE_PLACEMENT_FAILED} — a content
 *       or layout bug.</li>
 *   <li><b>Pass 2 — fill to max.</b> Remaining instances (core
 *       overflow toward max count, then {@code FILLER} types) get
 *       matched against the remaining slots. Filler silently skips
 *       when nothing fits.</li>
 * </ol>
 *
 * <h3>Scoring</h3>
 * {@code score = tier.weight + slot.qualityScore
 *                + preferredTagHits * 5
 *                − avoidancePenalty(sameType within minDist)
 *                − footprintMismatchPenalty}
 * <p>Avoidance is soft: heavy penalty, not a veto. With no alternative
 * a second market will still cluster — but with alternatives available
 * it won't.
 */
public final class PlacementMatcher {

    private static final Logger LOG = LogUtils.getLogger();

    private static final int AVOIDANCE_PENALTY = 80;
    private static final int FOOTPRINT_HARD_REJECT = Integer.MIN_VALUE / 2;

    private final PlanContext pctx;
    private final List<PlacementSlot> slots;
    private final List<PlacedRecord> placed = new ArrayList<>();

    private record PlacedRecord(BuildingType type, BlockPos pos) {}

    public PlacementMatcher(PlanContext pctx, List<PlacementSlot> slots) {
        this.pctx = pctx;
        // Mutable copy — slots are consumed as we commit
        this.slots = new ArrayList<>(slots);
    }

    public void run() {
        if (slots.isEmpty() && pctx.remaining.isEmpty()) return;
        LOG.debug("PlacementMatcher: {} buildings remaining, {} slots offered",
                pctx.remaining.size(), slots.size());

        placeTownHallPrePass();

        // Partition remaining by anchor policy
        List<StarterBuilding> core = new ArrayList<>();
        List<StarterBuilding> filler = new ArrayList<>();
        for (StarterBuilding sb : pctx.remaining) {
            BuildingType bt = PlanContext.parseType(sb);
            if (bt == null) continue;
            BuildingProfile p = BuildingProfileRegistry.get(bt);
            (p.anchor() == AnchorPolicy.CORE ? core : filler).add(sb);
        }
        // Matcher owns placement now — clear remaining; anything
        // unplaced at the end gets logged, not re-dropped elsewhere
        pctx.remaining.clear();

        // Pass 1: meet minimums for core types
        List<StarterBuilding> overflow = passOne(core);
        // Pass 2: fill toward max (overflow) then filler
        passTwo(overflow, filler);
    }

    // ── Pre-pass ─────────────────────────────────────────────────────────

    private void placeTownHallPrePass() {
        Iterator<StarterBuilding> it = pctx.remaining.iterator();
        StarterBuilding th = null;
        while (it.hasNext()) {
            StarterBuilding sb = it.next();
            if ("TOWN_HALL".equals(sb.type())) { th = sb; it.remove(); break; }
        }
        if (th == null) return;

        BuildingType bt = BuildingType.TOWN_HALL;
        // Try PRIME_CIVIC first, then SECONDARY_CIVIC
        if (commitBest(th, bt, Set.of(SlotTag.PRIME_CIVIC), Set.of(), 100)) return;
        if (commitBest(th, bt, Set.of(SlotTag.SECONDARY_CIVIC), Set.of(), 70)) return;
        LOG.warn("PlacementMatcher: TOWN_HALL could not claim any civic slot — "
                + "falling through to standard matcher");
        pctx.remaining.add(0, th); // let pass 1 try everything else
    }

    // ── Pass 1 ───────────────────────────────────────────────────────────

    /**
     * Returns buildings that were requested beyond min count and will
     * be retried in pass 2.
     */
    private List<StarterBuilding> passOne(List<StarterBuilding> core) {
        // Group by type; for each type resolve min/max and split into
        // a "must place" sublist (up to min) and "overflow" (up to max).
        Map<BuildingType, List<StarterBuilding>> byType = new EnumMap<>(BuildingType.class);
        for (StarterBuilding sb : core) {
            BuildingType bt = PlanContext.parseType(sb);
            if (bt == null) continue;
            byType.computeIfAbsent(bt, k -> new ArrayList<>()).add(sb);
        }

        List<StarterBuilding> overflow = new ArrayList<>();
        // Sort types by profile weight of tier 0 (more demanding first so
        // they claim premium slots before generalists consume them)
        List<BuildingType> order = new ArrayList<>(byType.keySet());
        order.sort(Comparator.comparingInt(
                t -> -BuildingProfileRegistry.get(t).preferences().get(0).weight()));

        for (BuildingType bt : order) {
            List<StarterBuilding> instances = byType.get(bt);
            int minCount = instances.stream()
                    .mapToInt(sb -> sb.minCount()).sum();
            int placedOfType = 0;
            for (StarterBuilding sb : instances) {
                int want = sb.resolveCount(pctx.rng);
                int minForThis = sb.minCount();
                // Place up to min in pass 1
                for (int i = 0; i < minForThis; i++) {
                    if (placeOne(sb, bt, true)) placedOfType++;
                }
                // Extras beyond min go into overflow for pass 2
                for (int i = minForThis; i < want; i++) {
                    overflow.add(singleInstance(sb));
                }
            }
            if (placedOfType < minCount) {
                LOG.warn("CORE_PLACEMENT_FAILED type={} placed={}/{} "
                                + "(check that the layout emits slots matching this "
                                + "profile's preference tiers)",
                        bt, placedOfType, minCount);
            }
        }
        return overflow;
    }

    // ── Pass 2 ───────────────────────────────────────────────────────────

    private void passTwo(List<StarterBuilding> overflow,
                         List<StarterBuilding> filler) {
        // Overflow core instances first (toward max), then filler
        for (StarterBuilding sb : overflow) {
            BuildingType bt = PlanContext.parseType(sb);
            if (bt == null) continue;
            placeOne(sb, bt, false);
        }
        for (StarterBuilding sb : filler) {
            BuildingType bt = PlanContext.parseType(sb);
            if (bt == null) continue;
            int want = sb.resolveCount(pctx.rng);
            for (int i = 0; i < want; i++) {
                if (!placeOne(sb, bt, false)) break; // no slot → stop trying
            }
        }
    }

    // ── Core placement: walk preference tiers for one instance ───────────

    /**
     * @param strict when true (pass 1), logs if no tier matches.
     *               When false (pass 2 / filler), silent failure is fine.
     */
    private boolean placeOne(StarterBuilding sb, BuildingType bt, boolean strict) {
        BuildingProfile profile = BuildingProfileRegistry.get(bt);
        for (SlotPreference tier : profile.preferences()) {
            if (commitBest(sb, bt, tier.required(), tier.preferred(), tier.weight())) {
                return true;
            }
        }
        if (strict) {
            LOG.warn("No slot matched any preference tier for type={} "
                            + "profile-tiers={} slots-remaining={}",
                    bt, profile.preferences().size(), slots.size());
        }
        return false;
    }

    /**
     * Scores every slot satisfying {@code required}, picks the best,
     * and attempts to commit. On terrain failure the slot is burned
     * and the next-best is tried. Returns true iff a commit succeeded.
     */
    private boolean commitBest(StarterBuilding sb, BuildingType bt,
                               Set<SlotTag> required, Set<SlotTag> preferred,
                               int tierWeight) {

        AvoidanceRule avoid = BuildingProfileRegistry.get(bt).avoidance();
        int footprintEst = estimateFootprint(sb);

        // Build a scored, sorted candidate list
        record Scored(PlacementSlot slot, int score) {}
        List<Scored> candidates = new ArrayList<>();
        for (PlacementSlot s : slots) {
            if (!s.hasAll(required)) continue;
            int score = scoreSlot(s, bt, preferred, tierWeight,
                    avoid, footprintEst);
            if (score <= FOOTPRINT_HARD_REJECT / 2) continue;
            candidates.add(new Scored(s, score));
        }
        candidates.sort((a, b) -> Integer.compare(b.score, a.score));
        LOG.info("commitBest {} required={} candidates={}", bt, required, candidates.size());


        for (Scored c : candidates) {
            LayoutSlot committed = pctx.tryCommitWithRetries(
                    c.slot.pos(), sb, bt, c.slot.feedingRoad(), 8);
            if (committed != null) {
                slots.remove(c.slot);
                placed.add(new PlacedRecord(bt, committed.getPos()));
                return true;
            }
            // Retries exhausted at this slot position — burn it
            slots.remove(c.slot);
        }
        return false;
    }

    private int scoreSlot(PlacementSlot slot, BuildingType bt,
                          Set<SlotTag> preferred, int tierWeight,
                          AvoidanceRule avoid, int footprintEst) {
        int score = tierWeight + slot.qualityScore();
        score += slot.preferredTagHits(preferred) * 5;

        if (slot.footprintBudget() > 0 && footprintEst > slot.footprintBudget()) {
            // Soft mismatch: small overrun scales, large overrun rejects
            int over = footprintEst - slot.footprintBudget();
            if (over > 6) return FOOTPRINT_HARD_REJECT;
            score -= over * 8;
        }

        if (avoid != null && avoid.sameType()) {
            int minSq = avoid.minDistance() * avoid.minDistance();
            for (PlacedRecord r : placed) {
                if (r.type() != bt) continue;
                if (r.pos().distSqr(slot.pos()) < minSq) {
                    score -= AVOIDANCE_PENALTY;
                }
            }
        }
        return score;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Rough footprint estimate before structure lookup. Conservative. */
    private int estimateFootprint(StarterBuilding sb) {
        // Matches the "12" default in PlanContext.tryCommitBuilding
        // when structure size isn't cached. Good enough for filtering.
        return 12;
    }

    /**
     * Splits a multi-count StarterBuilding into a single-count copy so
     * overflow loop can treat each instance independently.
     */
    private static StarterBuilding singleInstance(StarterBuilding sb) {
        return new StarterBuilding(sb.type(), sb.structure(), 1, 1);
    }
}