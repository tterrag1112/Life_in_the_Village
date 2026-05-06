// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Zoning/PlacementMatcher.java
package tterrag1112.life_in_the_village.Village.Planning.Zoning;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.LayoutSlot;
import tterrag1112.life_in_the_village.Village.Planning.Plaza;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.Sector;
import tterrag1112.life_in_the_village.Village.Planning.ZoneRegistry;
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
    /** Identity map: slot reference → sector id, built in runWithSectors. */
    private final Map<PlacementSlot, String> slotSectorIds = new IdentityHashMap<>();

    private record PlacedRecord(BuildingType type, BlockPos pos) {}
    private record CandidateLog(PlacementSlot slot, List<String> reasons) {}

    public PlacementMatcher(PlanContext pctx, List<PlacementSlot> slots) {
        this.pctx = pctx;
        // Mutable copy — slots are consumed as we commit
        this.slots = new ArrayList<>(slots);
    }

    /**
     * Sector-aware placement entry point. Used by recipes converted to
     * {@link tterrag1112.life_in_the_village.Village.Planning.Primitives
     * .BaseRecipe}. The legacy flat-slot {@link #run} stays valid for
     * unconverted recipes during the Phase 8-15 migration.
     *
     * <p>Phase 8 implementation: flatten all sector slots into a single
     * pool (preserving sector emission order, then per-sector slot order)
     * and append any remaining flat-pool slots, then run the existing
     * {@link #run} algorithm against that pool. This produces results
     * equivalent to flat-slot mode for now. Phase 10 wires sector
     * capacity tracking and growth into this method.
     */
    public void runWithSectors(List<Sector> sectors) {
        // Build slot→sectorId map before flattening (identity equality is required
        // because PlacementSlot is a record — two slots at the same pos are equal).
        for (Sector s : sectors) {
            for (PlacementSlot slot : s.slots()) {
                slotSectorIds.put(slot, s.id());
            }
        }
        List<PlacementSlot> flat = new ArrayList<>();
        for (Sector s : sectors) {
            flat.addAll(s.slots());
        }
        flat.addAll(this.slots);
        this.slots.clear();
        this.slots.addAll(flat);
        run();
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
        // Phase 18: civic-first claim path. Try the Plaza's owned civic
        // pool before the flat pool. Plaza-less layouts (ENCLAVE) skip
        // straight to the flat-pool walk via the existing commitBest.
        Plaza plaza = pctx.layout.getPlaza();
        if (plaza != null && !plaza.civicSlots().isEmpty()) {
            if (commitBestFromPool(th, bt, plaza.civicSlots(),
                    Set.of(SlotTag.PRIME_CIVIC), Set.of(), 100)) return;
            if (commitBestFromPool(th, bt, plaza.civicSlots(),
                    Set.of(SlotTag.SECONDARY_CIVIC), Set.of(), 70)) return;
        }

        // Try PRIME_CIVIC first, then SECONDARY_CIVIC against the flat pool
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

        // Phase 18: civic-first claim path. CIVIC-zone buildings try the
        // Plaza's owned civic pool before the flat pool. Other zones skip
        // straight to the flat tier walk.
        if (ZoneRegistry.zoneOf(bt) == BuildingZone.CIVIC) {
            Plaza plaza = pctx.layout.getPlaza();
            if (plaza != null && !plaza.civicSlots().isEmpty()) {
                for (SlotPreference tier : profile.preferences()) {
                    if (commitBestFromPool(sb, bt, plaza.civicSlots(),
                            tier.required(), tier.preferred(), tier.weight())) {
                        return true;
                    }
                }
            }
        }

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


        // Per-candidate rejection snapshots — populated only when
        // tryCommitWithRetries returns null. Dumped as one [REJECT_DEBUG]
        // block at the bottom if every candidate failed.
        List<CandidateLog> rejected = new ArrayList<>();

        for (Scored c : candidates) {
            // Variant-aware overload: thread the slot tags through so
            // PlanContext.applyVariantSelection (called inside
            // tryCommitBuilding) gives them the slot-tag scoring
            // bonus. Recipe-direct callers use the no-tag overload.
            pctx.rejectionLog.clear();
            // Phase C.3: recalibrate the slot's commit position based
            // on the candidate building's actual footprint. Slots
            // were emitted at perpOffset for the intention's maxFp;
            // the recalibrated target satisfies the validator-cap for
            // the building's actual fp. Perturbation in
            // tryCommitWithRetries then operates around the
            // recalibrated centre.
            BlockPos target = recalibrateSlotForBuilding(c.slot, sb);
            LayoutSlot committed = pctx.tryCommitWithRetries(
                    target, sb, bt, c.slot.feedingRoad(),
                    c.slot.maxDriftBlocks(),
                    c.slot.tags());
            if (committed != null) {
                slots.remove(c.slot);
                burnNearbySlots(committed);
                placed.add(new PlacedRecord(bt, committed.getPos()));
                String sectorId = slotSectorIds.get(c.slot);
                if (sectorId != null) {
                    pctx.committedSectorIds.put(committed.getPos(), sectorId);
                }
                return true;
            }
            // Retries exhausted at this slot position — burn it.
            // Snapshot the rejection reasons so we can dump them after the
            // tier walk completes if no candidate committed.
            rejected.add(new CandidateLog(c.slot,
                    new ArrayList<>(pctx.rejectionLog)));
            slots.remove(c.slot);
        }

        if (!rejected.isEmpty()) {
            dumpRejectedCandidates(bt, required, rejected);
        }
        return false;
    }

    /**
     * Phase 18: civic-pool variant of {@link #commitBest}. Mirrors the
     * scoring + commit path but draws candidates from the passed
     * {@code pool} (typically {@link Plaza#civicSlots()}) instead of
     * {@link #slots}. On commit, the slot is removed from the pool so
     * successive civic placements don't pick it again.
     *
     * <p>Slot attribution: civic slots aren't in {@link #slotSectorIds}
     * (Plaza is not a Sector), so committed positions don't get a
     * sector entry in {@link PlanContext#committedSectorIds}. Plan-dump
     * attribution falls back to "unknown" — which is correct: these
     * buildings live on the Plaza, not in any sector. The new
     * --- PLAZA --- dump section provides the equivalent visibility.
     */
    private boolean commitBestFromPool(StarterBuilding sb, BuildingType bt,
                                       List<PlacementSlot> pool,
                                       Set<SlotTag> required, Set<SlotTag> preferred,
                                       int tierWeight) {
        if (pool.isEmpty()) return false;
        AvoidanceRule avoid = BuildingProfileRegistry.get(bt).avoidance();
        int footprintEst = estimateFootprint(sb);

        record Scored(PlacementSlot slot, int score) {}
        List<Scored> candidates = new ArrayList<>();
        for (PlacementSlot s : pool) {
            if (!s.hasAll(required)) continue;
            int score = scoreSlot(s, bt, preferred, tierWeight,
                    avoid, footprintEst);
            if (score <= FOOTPRINT_HARD_REJECT / 2) continue;
            candidates.add(new Scored(s, score));
        }
        candidates.sort((a, b) -> Integer.compare(b.score, a.score));
        LOG.info("commitBestFromPool {} required={} candidates={}/{}",
                bt, required, candidates.size(), pool.size());

        List<CandidateLog> rejected = new ArrayList<>();
        for (Scored c : candidates) {
            pctx.rejectionLog.clear();
            // Phase C.3: recalibrate. Civic-pool slots from
            // PlazaPerimeter have feedingRoad=null, so recalibration
            // is a no-op for them — they commit at the slot's
            // original vertex position and validate via the
            // hull-distance branch.
            BlockPos target = recalibrateSlotForBuilding(c.slot, sb);
            LayoutSlot committed = pctx.tryCommitWithRetries(
                    target, sb, bt, c.slot.feedingRoad(),
                    c.slot.maxDriftBlocks(),
                    c.slot.tags());
            if (committed != null) {
                pool.remove(c.slot);
                burnNearbySlots(committed);
                placed.add(new PlacedRecord(bt, committed.getPos()));
                return true;
            }
            rejected.add(new CandidateLog(c.slot,
                    new ArrayList<>(pctx.rejectionLog)));
            pool.remove(c.slot);
        }
        if (!rejected.isEmpty()) {
            dumpRejectedCandidates(bt, required, rejected);
        }
        return false;
    }

    /**
     * Per-candidate rejection dump. Called from {@link #commitBest} when
     * every candidate slot failed to commit. Each candidate's
     * {@link PlanContext#rejectionLog} snapshot is summarised with unique
     * reasons + counts so a tier of three slots that all failed for the
     * same root cause shows up clearly.
     */
    private void dumpRejectedCandidates(BuildingType bt,
                                        Set<SlotTag> required,
                                        List<CandidateLog> rejected) {
        StringBuilder sb = new StringBuilder();
        sb.append("[REJECT_DEBUG] commitBest type=").append(bt)
          .append(" tier=").append(required)
          .append(" candidates=").append(rejected.size())
          .append('\n');
        for (int idx = 0; idx < rejected.size(); idx++) {
            CandidateLog cl = rejected.get(idx);
            PlacementSlot slot = cl.slot();
            List<String> reasons = cl.reasons();
            String sectorId = slotSectorIds.get(slot);
            sb.append("  candidate#").append(idx)
              .append(" pos=").append(slot.pos())
              .append(" tags=").append(slot.tags())
              .append(" fpW=").append(slot.footprintBudgetW())
              .append(" fpL=").append(slot.footprintBudgetL())
              .append(" feedRoad=").append(slot.feedingRoad() != null
                      ? slot.feedingRoad().size() + "pts" : "none")
              .append(" sector=").append(sectorId != null ? sectorId : "unknown")
              .append('\n');
            if (reasons.isEmpty()) {
                sb.append("    REJECT: no reason recorded "
                        + "(commit returned null on a path that bypassed all FAIL hooks)\n");
            } else {
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (String reason : reasons) counts.merge(reason, 1, Integer::sum);
                sb.append("    REJECT: ").append(reasons.size()).append(" attempt(s); ");
                boolean first = true;
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    if (!first) sb.append("; ");
                    sb.append(e.getKey()).append(" (x").append(e.getValue()).append(")");
                    first = false;
                }
                sb.append('\n');
            }
        }
        sb.append("[REJECT_DEBUG] all candidates exhausted — falling to next tier\n");
        System.out.print(sb);
    }

    /**
     * Removes pool slots whose ideal position falls inside the just-placed
     * building's footprint plus a {@link tterrag1112.life_in_the_village
     * .Village.Planning.VillageLayout#MIN_BUILDING_GAP} buffer. Without this,
     * a 29×29 town hall commit would leave 8-12 nearby slots in the pool
     * that look like valid candidates but always fail
     * {@code tryCommitBuilding}'s overlap check, burning perturbation
     * attempts on each one before falling through.
     *
     * <p>Burn radius is footprint-driven:
     * {@code max(W, L) / 2 + MIN_BUILDING_GAP}. For a 14×14 building that's
     * 7 + 4 = 11; for a 29×29 town hall it's 15 + 4 = 19; a hypothetical
     * 40×40 castle gets 24. Large commits sweep more slots — by design.
     */
    private void burnNearbySlots(LayoutSlot committed) {
        int w = committed.getFootprintWidth();
        int l = committed.getFootprintLength();
        int burnRadius = Math.max(w, l) / 2
                + tterrag1112.life_in_the_village.Village.Planning
                        .VillageLayout.MIN_BUILDING_GAP;
        long rSq = (long) burnRadius * burnRadius;
        BlockPos centre = committed.getPos();

        Iterator<PlacementSlot> it = slots.iterator();
        while (it.hasNext()) {
            PlacementSlot s = it.next();
            if (s.pos().distSqr(centre) < rSq) {
                it.remove();
            }
        }
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

    /**
     * Phase C.3: recalibrates the slot's commit position based on the
     * candidate building's actual footprint. The slot acts as a
     * road-anchor hint (which road, which side, roughly which spot
     * along the road). The actual commit position is computed
     * perpendicular to the road at the distance that satisfies BOTH
     * the road-overlap constraint AND the validator-cap, given the
     * building's actual footprint.
     *
     * <p>Why: SlotEmitter resolvers position slots at perpOffset
     * computed from the intention's {@code maxFootprint} (e.g., 24
     * blocks for a fp=40 production sector). When a small building
     * (fp=9 GUARD_TOWER, STONEMASON, etc.) is committed there, the
     * validator's cap depends on the *building's* footprint, not the
     * intention's. The recalibration moves the building closer to
     * the road so the validator's cap holds.
     *
     * <p>Returns the slot's original position when:
     * <ul>
     *   <li>{@code slot.feedingRoad()} is null or has fewer than 2
     *       points (PlazaPerimeter civic slots, RegionalGather
     *       slots — these go through the validator's hull-distance
     *       branch, not the road-distance branch).</li>
     *   <li>The building's footprint can't be looked up (fall back
     *       to the slot's original position as a safe default).</li>
     * </ul>
     */
    private BlockPos recalibrateSlotForBuilding(
            PlacementSlot slot, StarterBuilding sb) {
        java.util.List<BlockPos> feedingRoad = slot.feedingRoad();
        if (feedingRoad == null || feedingRoad.size() < 2) {
            return slot.pos();
        }

        // Look up the building's actual footprint. If unknown, fall
        // back to slot.pos() — same default-12 fallback PlanContext
        // .tryCommitWithRetries uses for unrecognised structures.
        var sizeInfo = pctx.sizes.get(sb.structure(),
                net.minecraft.world.level.block.Rotation.NONE);
        if (sizeInfo == null) return slot.pos();
        int buildingFpW = sizeInfo.width();
        int buildingFpL = sizeInfo.length();

        BlockPos slotPos = slot.pos();

        // Find the centerline point nearest the slot.
        int nearestIdx = 0;
        int nearestDist = Integer.MAX_VALUE;
        for (int i = 0; i < feedingRoad.size(); i++) {
            int d = chebDist(slotPos, feedingRoad.get(i));
            if (d < nearestDist) {
                nearestDist = d;
                nearestIdx = i;
            }
        }
        BlockPos roadPt = feedingRoad.get(nearestIdx);

        // Tangent via Integer.signum — matches BuildSiteFinder /
        // SlotEmitter conventions. Falls back to previous-segment
        // tangent at end of centerline.
        int tangentX, tangentZ;
        if (nearestIdx + 1 < feedingRoad.size()) {
            BlockPos next = feedingRoad.get(nearestIdx + 1);
            tangentX = Integer.signum(next.getX() - roadPt.getX());
            tangentZ = Integer.signum(next.getZ() - roadPt.getZ());
        } else {
            BlockPos prev = feedingRoad.get(nearestIdx - 1);
            tangentX = Integer.signum(roadPt.getX() - prev.getX());
            tangentZ = Integer.signum(roadPt.getZ() - prev.getZ());
        }
        if (tangentX == 0 && tangentZ == 0) {
            tangentX = 1;
            tangentZ = 0;
        }
        int perpX = -tangentZ;
        int perpZ = tangentX;

        // Determine which side of the road the slot is on.
        int slotDx = slotPos.getX() - roadPt.getX();
        int slotDz = slotPos.getZ() - roadPt.getZ();
        int sign = Integer.signum(slotDx * perpX + slotDz * perpZ);
        if (sign == 0) sign = 1;

        // Target perpOffset for THIS building's footprint.
        // roadHalfWidth=3 hardcoded — matches VillagePlanner
        // .VALIDATOR_ROAD_HALF_WIDTH and the value all current road
        // tiers use (RoadShape.RoadTier.reservedHalfWidth() = 3 for
        // VILLAGE_PATH and VILLAGE_ROAD). If a future tier widens
        // its reservation, lift this constant or thread the slot's
        // tier through.
        int halfW = buildingFpW / 2;
        int halfL = buildingFpL / 2;
        int roadHalfWidth = 3;
        int targetPerpOffset = roadHalfWidth + Math.max(halfW, halfL) + 1;

        BlockPos recalibrated = new BlockPos(
                roadPt.getX() + perpX * targetPerpOffset * sign,
                slotPos.getY(),
                roadPt.getZ() + perpZ * targetPerpOffset * sign);

        if (!recalibrated.equals(slotPos)) {
            int origPerpOffset = chebDist(slotPos, roadPt);
            System.out.println("[Matcher] recalibrate type=" + sb.type()
                    + " slot=" + slotPos + " -> " + recalibrated
                    + " feedingRoad=" + feedingRoad.size() + "pts"
                    + " perpOffset=" + origPerpOffset
                    + "->" + targetPerpOffset
                    + " buildingFp=" + buildingFpW + "x" + buildingFpL);
        }
        return recalibrated;
    }

    private static int chebDist(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()),
                Math.abs(a.getZ() - b.getZ()));
    }

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