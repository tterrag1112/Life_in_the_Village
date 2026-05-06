package tterrag1112.life_in_the_village.Village.Planning.Primitives;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.Inhabitants.BuildingInhabitantRegistry;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.AgeCategory;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.BuildingVariant;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.StyleAutoDeriver;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.StyleSelection;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.VariantSelector;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.VillageAgeCategoryHook;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Planning.*;
import tterrag1112.life_in_the_village.Village.Planning.Features.FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.Rules.RuleContext;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.Sector;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;

/**
 * Scratchpad passed through the primitive composition pass. Holds the
 * mutable layout being built plus every helper a layout primitive needs
 * to turn a target position into a committed building slot.
 *
 * <h3>Why this class exists</h3>
 * Layout primitives need: terrain-aware position resolution, adjacency
 * checks, rotation choice from road heading, footprint lookup, overlap
 * testing, pad Y computation. These used to live as private static
 * helpers on VillagePlanner. Moving them here means primitives don't
 * need to reach back into the planner, and the planner becomes a small
 * orchestrator instead of a 900-line toolkit.
 */
public final class PlanContext {

    public final ServerLevel level;
    public final VillageLayout layout;
    public final StructureSizeCache sizes;
    public final Random rng;
    public final RuleContext ruleCtx;
    public final LayoutDensityProfile density;
    public final long worldSeed;

    /** Buildings not yet placed. Primitives consume from this list. */
    public final List<VillageTypeData.StarterBuilding> remaining;
    public boolean rejectWaterForAll = false;
    public boolean allowRidgePlacement = false;

    /**
     * Phase 3: planning-time geometric feature inventory (hull, water,
     * cliffs, plazas, reservations). Recipes still read directly from
     * {@link tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile}
     * for now; recipe migration to consume from this map starts in Phase 8+.
     */
    public final FeatureMap features;

    /**
     * Variant-selection inputs (P0a-06 / P0a-07). Set by
     * {@link tterrag1112.life_in_the_village.Village.Planning
     * .VillagePlanner} once it has the {@link VillageTypeData}; the
     * matcher reads these to pick variants per slot. All four are
     * left null for legacy / test paths that bypass VillagePlanner —
     * the matcher then falls back to "default variant, RURAL style".
     */
    private VillageTypeData typeData;
    private StyleSelection styleSelection;
    private VillageSizeTier sizeTier;
    private AgeCategory ageCategory;

    /** One {@link VariantSelector} per matcher run (per village). */
    private VariantSelector variantSelector;

    private final List<PlacementSlot> offeredSlots = new java.util.ArrayList<>();

    /**
     * Phase 8: sector pool. Recipes converted to {@link BaseRecipe} emit
     * sectors here via {@link #offerSector}; the matcher's sector-aware
     * entry point consumes them. Unconverted recipes use {@link #offerSlot}
     * and the legacy flat-slot path. {@link #runMatcher} dispatches based
     * on whether sectors were offered.
     */
    private final List<Sector> offeredSectors = new java.util.ArrayList<>();

    /**
     * Transient: sector-id keyed by the committed building's centre pos.
     * Written by PlacementMatcher after each successful commit; read by
     * VillagePlanner.dumpPlan. Always empty for flat-slot (non-sector) recipes.
     */
    public final java.util.Map<BlockPos, String> committedSectorIds =
            new java.util.LinkedHashMap<>();

    /**
     * Transient: rejection reasons accumulated during the current
     * tryCommitBuilding / tryCommitWithRetries call sequence. Each FAIL
     * branch in tryCommitBuilding appends one entry. PlacementMatcher
     * clears this before each candidate's commit attempt and snapshots it
     * after a failed return. Other call sites (recipe-direct placement)
     * may ignore it — the list is read-only diagnostic.
     */
    public final java.util.List<String> rejectionLog =
            new java.util.ArrayList<>();

    /**
     * Doc 04 §"Core concepts" — plaza polygon registrations.
     * Populated by recipe compose() for VILLAGE+ tiers (prompt 17);
     * empty list means HAMLET, expansion path, or pre-prompt-17
     * planning. {@link #setVillageCenter} is the HAMLET counterpart.
     */
    private final List<tterrag1112.life_in_the_village.Village
            .Decoration.Plaza.PlazaRegion> plazaRegions = new java.util.ArrayList<>();
    private tterrag1112.life_in_the_village.Village.Decoration
            .Plaza.VillageCenterMarker villageCenter;


    /**
     * Phase: shared outer-ring road wiring. When a recipe emits a single
     * outer perimeter road shared by multiple {@link
     * tterrag1112.life_in_the_village.Village.Planning.Primitives
     * .LayoutPrimitive.RingBand}s (DEFENSIVE inside the road, AGRICULTURAL
     * outside), it stashes the ring's edge id, centerline, and centre-radius
     * here so the bands can attach their slots to the same road instead of
     * floating with no feeding edge. Default {@code edgeId=-1} means "no
     * shared ring road; bands fall back to their {@code outerRadius}-derived
     * safeRadius logic."
     */
    private int outerRingEdgeId = -1;
    private List<BlockPos> outerRingCenterline = List.of();
    private int outerRingRadius = 0;

    private static final int MAX_TERRAIN_ATTEMPTS = 32;

    public PlanContext(ServerLevel level, VillageLayout layout,
                       StructureSizeCache sizes, Random rng,
                       RuleContext ruleCtx, LayoutDensityProfile density,
                       long worldSeed,
                       List<VillageTypeData.StarterBuilding> remaining,
                       FeatureMap features) {
        this.level = level;
        this.layout = layout;
        this.sizes = sizes;
        this.rng = rng;
        this.ruleCtx = ruleCtx;
        this.density = density;
        this.worldSeed = worldSeed;
        this.remaining = remaining;
        this.features = features;
    }

    // =========================================================================
    // Building claim — primitives call this to take buildings from `remaining`
    // =========================================================================

    /** Takes up to {@code max} buildings matching {@code zone} from remaining. */
    public List<VillageTypeData.StarterBuilding> claimByZone(BuildingZone zone, int max) {
        List<VillageTypeData.StarterBuilding> taken = new ArrayList<>();
        Iterator<VillageTypeData.StarterBuilding> it = remaining.iterator();
        while (it.hasNext() && taken.size() < max) {
            VillageTypeData.StarterBuilding sb = it.next();
            BuildingType bt = parseType(sb);
            if (bt == null) continue;
            if (ZoneRegistry.zoneOf(bt) == zone) {
                taken.add(sb);
                it.remove();
            }
        }
        return taken;
    }

    /** Takes and returns the single TOWN_HALL entry, or null. */
    public VillageTypeData.StarterBuilding claimTownHall() {
        Iterator<VillageTypeData.StarterBuilding> it = remaining.iterator();
        while (it.hasNext()) {
            VillageTypeData.StarterBuilding sb = it.next();
            if ("TOWN_HALL".equals(sb.type())) { it.remove(); return sb; }
        }
        return null;
    }

    /** Takes a specific type from remaining, or null. */
    public VillageTypeData.StarterBuilding claimType(BuildingType type) {
        Iterator<VillageTypeData.StarterBuilding> it = remaining.iterator();
        while (it.hasNext()) {
            VillageTypeData.StarterBuilding sb = it.next();
            if (type.name().equals(sb.type())) { it.remove(); return sb; }
        }
        return null;
    }

    public static BuildingType parseType(VillageTypeData.StarterBuilding sb) {
        try { return BuildingType.valueOf(sb.type()); }
        catch (IllegalArgumentException e) { return null; }
    }

    // =========================================================================
    // Variant context (P0a-06 / P0a-07)
    // =========================================================================

    /**
     * Sets the variant-selection inputs. Called by VillagePlanner
     * once it has the {@link VillageTypeData}; the matcher reads
     * these via {@link #variantSelector()} and the related getters.
     */
    public void setVariantContext(VillageTypeData typeData,
                                  StyleSelection styleSelection,
                                  VillageSizeTier sizeTier,
                                  AgeCategory ageCategory) {
        this.typeData = typeData;
        this.styleSelection = styleSelection;
        this.sizeTier = sizeTier;
        this.ageCategory = ageCategory;
    }

    public VillageTypeData typeData() { return typeData; }
    public StyleSelection styleSelection() { return styleSelection; }
    public VillageSizeTier sizeTier() { return sizeTier; }
    public AgeCategory ageCategory() {
        return ageCategory != null ? ageCategory
                : VillageAgeCategoryHook.forNewVillage();
    }

    // ── Plaza accessors (Phase 16 doc 04 scaffolding) ──────────────────
    // No code populates these yet — prompt 17's polygon generator
    // is the producer; building matcher / decoration emitter
    // (prompt 18) are consumers. Accessors exist now so subsequent
    // prompts have stable integration points.

    public void addPlazaRegion(
            tterrag1112.life_in_the_village.Village.Decoration
                    .Plaza.PlazaRegion p) {
        if (p != null) {
            plazaRegions.add(p);
            // Mirror onto VillageLayout so applyLayout carries plaza
            // data onto the persisted Village. PlanContext is the
            // compose-time scratch; layout is the post-compose
            // hand-off to realisation + persistence.
            layout.addPlazaRegion(p);
        }
    }

    public List<tterrag1112.life_in_the_village.Village.Decoration
            .Plaza.PlazaRegion> getPlazaRegions() {
        return java.util.Collections.unmodifiableList(plazaRegions);
    }

    public java.util.Optional<tterrag1112.life_in_the_village.Village
            .Decoration.Plaza.PlazaRegion> getPlazaRegionContaining(
                    BlockPos pos) {
        if (pos == null) return java.util.Optional.empty();
        for (var r : plazaRegions) {
            if (tterrag1112.life_in_the_village.Utilities.Geometry
                    .Polygon.contains(r.footprint(), pos)) {
                return java.util.Optional.of(r);
            }
        }
        return java.util.Optional.empty();
    }

    public java.util.Optional<tterrag1112.life_in_the_village.Village
            .Decoration.Plaza.PlazaRegion> getPlazaRegionNear(
                    BlockPos pos, int distance) {
        if (pos == null) return java.util.Optional.empty();
        tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaRegion best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (var r : plazaRegions) {
            double d = tterrag1112.life_in_the_village.Utilities.Geometry
                    .Polygon.distanceToEdge(r.footprint(), pos);
            if (d <= distance && d < bestD) {
                bestD = d;
                best = r;
            }
        }
        return java.util.Optional.ofNullable(best);
    }

    public void setVillageCenter(
            tterrag1112.life_in_the_village.Village.Decoration
                    .Plaza.VillageCenterMarker m) {
        this.villageCenter = m;
        layout.setVillageCenterMarker(m);
    }

    public java.util.Optional<tterrag1112.life_in_the_village.Village
            .Decoration.Plaza.VillageCenterMarker> getVillageCenter() {
        return java.util.Optional.ofNullable(villageCenter);
    }

    /** Lazy-constructed {@link VariantSelector} for this matcher run. */
    public VariantSelector variantSelector() {
        if (variantSelector == null) variantSelector = new VariantSelector();
        return variantSelector;
    }

    // =========================================================================
    // Rotation from road heading
    // =========================================================================

    /**
     * Chooses a rotation that makes the building face toward the nearest
     * point on the given road centerline. Replaces the old "face the
     * village centre" logic — buildings now face the road they're on.
     */
    public Rotation rotationFacingRoad(BlockPos buildingPos, List<BlockPos> centerline) {
        BlockPos nearest = nearestOn(centerline, buildingPos);
        return chooseFacing(buildingPos, nearest);
    }

    /** Generic facing chooser — buildingPos faces target. */
    public static Rotation chooseFacing(BlockPos buildingPos, BlockPos target) {
        int dx = target.getX() - buildingPos.getX();
        int dz = target.getZ() - buildingPos.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? Rotation.COUNTERCLOCKWISE_90 : Rotation.CLOCKWISE_90;
        } else {
            return dz > 0 ? Rotation.NONE : Rotation.CLOCKWISE_180;
        }
    }

    public static BlockPos nearestOn(List<BlockPos> centerline, BlockPos target) {
        BlockPos best = centerline.get(0);
        double bestDistSq = best.distSqr(target);
        for (BlockPos p : centerline) {
            double d = p.distSqr(target);
            if (d < bestDistSq) { bestDistSq = d; best = p; }
        }
        return best;
    }

    // =========================================================================
    // Slot construction — the full "turn a position into a committed slot" flow
    // =========================================================================

    /**
     * Tries to commit a building slot at {@code target} with rotation
     * derived from {@code feedingRoad}. Performs adjacency resolution,
     * terrain validation, footprint overlap testing, and pad Y computation.
     *
     * @return the committed slot, or null if no valid placement was possible
     */
    public LayoutSlot tryCommitBuilding(BlockPos target,
                                        VillageTypeData.StarterBuilding sb,
                                        BuildingType bt,
                                        List<BlockPos> feedingRoad) {
        return tryCommitBuilding(target, sb, bt, feedingRoad, java.util.Set.of());
    }

    /**
     * Variant-aware overload — same as {@link #tryCommitBuilding(BlockPos,
     * VillageTypeData.StarterBuilding, BuildingType, List)} but takes
     * the originating {@link PlacementSlot}'s tag set so variant
     * scoring can apply slot-tag bonuses. Recipe-direct callers that
     * don't have a {@link PlacementSlot} should pass an empty set
     * (the no-tag overload above does that automatically).
     */
    public LayoutSlot tryCommitBuilding(BlockPos target,
                                        VillageTypeData.StarterBuilding sb,
                                        BuildingType bt,
                                        List<BlockPos> feedingRoad,
                                        java.util.Set<SlotTag> slotTags) {
        // TEMP DIAG — remove after this test
        boolean diag = "TOWN_HALL".equals(sb.type());
        if (diag) System.out.println("[TH] trying " + target
                + " road=" + (feedingRoad != null ? feedingRoad.size() : 0));

        // 1. Adjacency
        List<RuleContext.AdjacencyReq> reqs = effectiveAdjacencyReqs(bt);
        if (!reqs.isEmpty()) {
            BlockPos adjusted = applyAdjacency(target, reqs);
            if (adjusted == null) {
                if (diag) System.out.println("[TH]   FAIL adjacency");
                rejectionLog.add("adjacency unsatisfied (no nearby "
                        + reqs.get(0).feature() + ")");
                return null;
            }
            target = adjusted;
        }

        // Phase 18 doc 04 §"Plaza-aware building rotation" — if the
        // slot is within ~8 blocks of any registered plaza polygon's
        // edge, rotate the building so its front face points at the
        // polygon centroid. Layered ABOVE the existing road-facing
        // logic so plaza-edge civic / market buildings present their
        // facade to the plaza rather than the road they sit on. For
        // slots far from any plaza, the existing logic is unchanged.
        // For LINEAR plazas, the centroid is along the major axis;
        // facing-the-centroid produces the more natural perpendicular-
        // to-axis orientation.
        Rotation rotation;
        var nearbyPlaza = getPlazaRegionNear(target, 8);
        if (nearbyPlaza.isPresent()) {
            rotation = chooseFacing(target, nearbyPlaza.get().centroid());
        } else {
            rotation = feedingRoad != null && !feedingRoad.isEmpty()
                    ? rotationFacingRoad(target, feedingRoad)
                    : Rotation.NONE;
        }

        StructureSizeCache.FootprintInfo info = sizes.get(sb.structure(), rotation);
        int w = info != null ? info.width() : 12;
        int l = info != null ? info.length() : 12;
        int slotRadius = Math.max(w, l) / 2 + 1;
        if (diag) System.out.println("[TH]   footprint w=" + w + " l=" + l);

        boolean agricultural = ZoneRegistry.zoneOf(bt) == BuildingZone.AGRICULTURAL;
        boolean rejectWater = agricultural || rejectWaterForAll;

        BlockPos resolved = resolveOnTerrain(target, density.getBuildingJitter(),
                slotRadius, rejectWater);
        if (resolved == null) {
            if (diag) System.out.println("[TH]   FAIL terrain");
            rejectionLog.add("terrain resolution failed (no flat patch radius="
                    + slotRadius + " jitter=" + density.getBuildingJitter()
                    + " rejectWater=" + rejectWater + ")");
            return null;
        }
        if (diag) System.out.println("[TH]   resolved=" + resolved);

        LayoutSlot slot = new LayoutSlot(resolved, bt, sb.structure(), slotRadius, rotation);
        slot.setFootprint(w, l);
        slot.setPadY(computePadY(resolved, w, l));
        // Carry the originating slot's feeding road through to the
        // committed LayoutSlot so the validator can measure distance to
        // *this* building's feeding road instead of the nearest road in
        // the whole graph. Edge id isn't available here (the matcher's
        // PlacementSlot has it but tryCommitBuilding's parameter list
        // doesn't); -1 is fine — the validator only consumes the
        // centerline list. If feedingRoad is null/empty, the slot is
        // ring-or-floating and the validator will fall back to a
        // hull-distance check.
        slot.setFeedingRoad(feedingRoad, -1);

        int halfW = w / 2;
        int halfL = l / 2;
        BlockPos footprintOrigin = new BlockPos(
                resolved.getX() - halfW, resolved.getY(), resolved.getZ() - halfL);
        if (!layout.getRoadFootprint().isClear(footprintOrigin, w, l, 1)) {
            if (diag) System.out.println("[TH]   FAIL road-footprint overlap");
            rejectionLog.add("footprint " + w + "x" + l
                    + " overlaps reserved road at " + resolved);
            return null;
        }

        BlockPos sqPos = layout.getTownSquarePos();
        int civicRing = layout.getCivicRingRadius();
        if (sqPos != null && civicRing > 0
                && ZoneRegistry.zoneOf(bt) != BuildingZone.CIVIC
                && !"TOWN_HALL".equals(sb.type())) {
            int dx = resolved.getX() - sqPos.getX();
            int dz = resolved.getZ() - sqPos.getZ();
            int distSq = dx * dx + dz * dz;
            int effective = civicRing + Math.max(halfW, halfL);
            if (distSq < effective * effective) {
                rejectionLog.add("inside civic ring guard (radius=" + civicRing
                        + ", footprint=" + w + "x" + l + ")");
                return null;
            }
        }

        if (!layout.tryAdd(slot)) {
            if (diag) System.out.println("[TH]   FAIL layout.tryAdd (overlap with existing forced/building slot)");
            rejectionLog.add("footprint " + w + "x" + l
                    + " overlaps existing committed slot");
            return null;
        }

        // Variant selection runs once per successful placement,
        // regardless of whether the matcher or a recipe-direct
        // primitive committed the slot. The slot has been fully
        // validated (adjacency, terrain, footprint, road overlap,
        // civic-ring guard, layout overlap) by this point.
        applyVariantSelection(slot, bt, slotTags);

        if (diag) System.out.println("[TH]   OK committed");
        return slot;
    }

    // =========================================================================
    // Variant selection (runs at the placement chokepoint)
    // =========================================================================

    /**
     * Picks the {@link BuildingVariant} that will fill {@code slot}
     * and stamps the chosen {@code style} + {@code variantId} onto
     * it. Doc 15 §"Variant selection algorithm".
     *
     * <p>Called from {@link #tryCommitBuilding} after a slot is
     * confirmed committable but before {@code VillageSpawner} reads
     * the variant id to drive the resolver. Centralising the call
     * here means every successful placement — matcher-driven via
     * {@code PlacementMatcher.commitBest} OR recipe-direct via
     * {@link tterrag1112.life_in_the_village.Village.Planning
     * .Primitives.LayoutPrimitive#tryCommitWithRetries
     * LayoutPrimitive} — gets variant scoring exactly once.</p>
     *
     * <p>If {@link #typeData} is null (legacy / test paths that
     * bypass {@code VillagePlanner}) the slot keeps the type-default
     * variant + RURAL it picked up at construction.</p>
     *
     * @param committed the just-committed slot
     * @param bt        building type (carried out of band so we don't
     *                  re-derive from {@code committed})
     * @param slotTags  the originating {@link PlacementSlot}'s tags,
     *                  or empty set when the call site doesn't have
     *                  a {@link PlacementSlot} (every recipe-direct
     *                  path)
     */
    private void applyVariantSelection(LayoutSlot committed,
                                       BuildingType bt,
                                       java.util.Set<SlotTag> slotTags) {
        if (typeData == null) return;
        if (styleSelection == null || sizeTier == null) return;

        // Per-slot style pick. Skips the RNG roll when only one style
        // has authored content for this type — keeps determinism
        // with placements where only one style is authored.
        Style style = StyleAutoDeriver.pickStyleForType(styleSelection, bt, rng);

        VariantSelector selector = variantSelector();
        String culture = typeData.getCulture() != null
                ? typeData.getCulture() : "default";
        BuildingVariant chosen = selector.select(
                culture, bt, style, sizeTier, ageCategory(),
                slotTags != null ? slotTags : java.util.Set.of(),
                java.util.Set.of(), // village preferred tags reserved
                                    // for P0a-14 colour-palette wiring
                rng);

        committed.setStyle(chosen.style());
        committed.setVariantId(chosen.id());
    }


    /**
     * Retries {@link #tryCommitBuilding} at a sequence of positions — the
     * target, then small shifts along the road direction, then shifts
     * perpendicular to it. Used when a primitive wants to place a
     * building near an ideal spot but has some flexibility.
     *
     * <p>Recipe-direct call sites use this overload; variant
     * selection runs inside {@link #tryCommitBuilding} with empty
     * slot tags. The matcher uses
     * {@link #tryCommitWithRetries(BlockPos, VillageTypeData
     * .StarterBuilding, BuildingType, List, int, java.util.Set)} so
     * its {@link PlacementSlot} tags reach the variant scorer.</p>
     */
    public LayoutSlot tryCommitWithRetries(BlockPos target,
                                           VillageTypeData.StarterBuilding sb,
                                           BuildingType bt,
                                           List<BlockPos> feedingRoad,
                                           int maxShift) {
        return tryCommitWithRetries(target, sb, bt, feedingRoad, maxShift,
                java.util.Set.of());
    }

    /**
     * Variant-aware overload. Threads the originating
     * {@link PlacementSlot}'s tags through to
     * {@link #tryCommitBuilding} so variant scoring can apply
     * slot-tag bonuses. Recipe-direct callers should use the no-tag
     * overload above.
     */
    public LayoutSlot tryCommitWithRetries(BlockPos target,
                                           VillageTypeData.StarterBuilding sb,
                                           BuildingType bt,
                                           List<BlockPos> feedingRoad,
                                           int maxShift,
                                           java.util.Set<SlotTag> slotTags) {
        LayoutSlot s = tryCommitBuilding(target, sb, bt, feedingRoad, slotTags);
        if (s != null) return s;

        // Derive road direction for along/perpendicular shifts
        BlockPos near = feedingRoad != null && !feedingRoad.isEmpty()
                ? nearestOn(feedingRoad, target) : target;
        int idx = feedingRoad != null ? feedingRoad.indexOf(near) : -1;
        int headX = 1, headZ = 0;
        if (idx >= 0 && feedingRoad.size() > 1) {
            BlockPos a = feedingRoad.get(Math.max(0, idx - 1));
            BlockPos b = feedingRoad.get(Math.min(feedingRoad.size() - 1, idx + 1));
            headX = Integer.signum(b.getX() - a.getX());
            headZ = Integer.signum(b.getZ() - a.getZ());
            if (headX == 0 && headZ == 0) { headX = 1; headZ = 0; }
        }
        int perpX = -headZ, perpZ = headX;

        // Clamp drift by actual building footprint so a small building placed
        // in a large-budget slot (footprintBudgetW=16 but actual=9) doesn't
        // drift past the validator threshold (roadHalfWidth + footprintHalf +
        // VALIDATOR_ROAD_SLACK). max(w,l) is rotation-invariant.
        StructureSizeCache.FootprintInfo sizeInfo =
                sizes.get(sb.structure(), Rotation.NONE);
        int sizeW = sizeInfo != null ? sizeInfo.width() : 12;
        int sizeL = sizeInfo != null ? sizeInfo.length() : 12;
        int effectiveShift = Math.min(maxShift, Math.max(sizeW, sizeL) / 2);

        int[] shifts = {4, -4, 8, -8};
        for (int shift : shifts) {
            if (Math.abs(shift) > effectiveShift) continue;
            BlockPos along = target.offset(headX * shift, 0, headZ * shift);
            if (driftDistance(target, along) <= effectiveShift) {
                s = tryCommitBuilding(along, sb, bt, feedingRoad, slotTags);
                if (s != null) return s;
            }
            BlockPos perp = target.offset(perpX * shift, 0, perpZ * shift);
            if (driftDistance(target, perp) <= effectiveShift) {
                s = tryCommitBuilding(perp, sb, bt, feedingRoad, slotTags);
                if (s != null) return s;
            }
        }
        return null;
    }

    private static int driftDistance(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        return (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
    }

    // =========================================================================
    // Adjacency (migrated from VillagePlanner)
    // =========================================================================

    public List<RuleContext.AdjacencyReq> effectiveAdjacencyReqs(BuildingType bt) {
        List<RuleContext.AdjacencyReq> ruleReqs = ruleCtx.getAdjacencyReqs(bt);
        var intrinsic = BuildingInhabitantRegistry.getAdjacency(bt);
        if (intrinsic.isEmpty()) return ruleReqs;
        if (ruleReqs.isEmpty()) return intrinsic.getRequirements();
        Set<String> covered = new HashSet<>();
        for (RuleContext.AdjacencyReq r : ruleReqs) covered.add(r.feature());
        List<RuleContext.AdjacencyReq> merged = new ArrayList<>(ruleReqs);
        for (RuleContext.AdjacencyReq r : intrinsic.getRequirements()) {
            if (!covered.contains(r.feature())) merged.add(r);
        }
        return merged;
    }

    public BlockPos applyAdjacency(BlockPos target, List<RuleContext.AdjacencyReq> reqs) {
        WorldAtlas atlas = WorldAtlas.get(level);
        for (RuleContext.AdjacencyReq req : reqs) {
            if (checkFeature(atlas, target, req.feature())) continue;
            BlockPos found = findFeatureNearby(atlas, target, req.feature(), req.maxDist());
            if (found != null) {
                target = new BlockPos(found.getX(), target.getY(), found.getZ());
            } else if (req.required()) {
                return null;
            }
        }
        return target;
    }

    private static boolean checkFeature(WorldAtlas atlas, BlockPos pos, String feature) {
        AtlasCell c = atlas.getCellAtBlock(pos.getX(), pos.getZ());
        if (c == null) return false;
        return switch (feature) {
            case "river" -> c.isRiverAdj() || c.isFreshwater();
            case "coast" -> c.isCoast();
            case "water" -> c.isCoast() || c.isRiverAdj() || c.isFreshwater();
            case "forest" -> c.category() == BiomeCategory.FOREST;
            default -> false;
        };
    }

    private static BlockPos findFeatureNearby(WorldAtlas atlas, BlockPos centre,
                                              String feature, int maxDist) {
        var nearby = atlas.cellsWithinRadius(centre.getX(), centre.getZ(),
                maxDist + AtlasCell.CELL_SIZE);
        AtlasCell best = null;
        long bestSq = Long.MAX_VALUE;
        for (AtlasCell c : nearby) {
            boolean hit = switch (feature) {
                case "river" -> c.isRiverAdj() || c.isFreshwater();
                case "coast" -> c.isCoast();
                case "water" -> c.isCoast() || c.isRiverAdj() || c.isFreshwater();
                case "forest" -> c.category() == BiomeCategory.FOREST;
                default -> false;
            };
            if (!hit) continue;
            long dx = c.blockCenterX() - centre.getX();
            long dz = c.blockCenterZ() - centre.getZ();
            long d = dx * dx + dz * dz;
            if (d < bestSq) { bestSq = d; best = c; }
        }
        if (best == null) return null;
        return new BlockPos(best.blockCenterX(), centre.getY(), best.blockCenterZ());
    }

    // =========================================================================
    // Terrain resolution (migrated)
    // =========================================================================

    public BlockPos resolveOnTerrain(BlockPos ideal, int jitterRange,
                                     int radius, boolean rejectWater) {
        TerrainProfile terrain = layout.getTerrain();

        BlockPos candidate = solidSurface(ideal);
        if (isValidTerrain(terrain, candidate, radius, rejectWater)) return candidate;

        for (int a = 0; a < MAX_TERRAIN_ATTEMPTS / 2; a++) {
            int jx = rng.nextInt(jitterRange * 2 + 1) - jitterRange;
            int jz = rng.nextInt(jitterRange * 2 + 1) - jitterRange;
            candidate = solidSurface(ideal.offset(jx, 0, jz));
            if (isValidTerrain(terrain, candidate, radius, rejectWater)) return candidate;
        }

        BlockPos nearest = terrain.bestFlatNear(
                ideal.getX() - terrain.origin().getX(),
                ideal.getZ() - terrain.origin().getZ());
        candidate = solidSurface(nearest);
        if (isValidTerrain(terrain, candidate, radius, rejectWater)) return candidate;

        int step = Math.max(4, 10);
        for (int ring = 1; ring <= MAX_TERRAIN_ATTEMPTS / 2; ring++) {
            for (int[] off : new int[][]{
                    {ring, 0}, {-ring, 0}, {0, ring}, {0, -ring},
                    {ring, ring}, {-ring, ring}, {ring, -ring}, {-ring, -ring}}) {
                candidate = solidSurface(ideal.offset(off[0] * step, 0, off[1] * step));
                if (isValidTerrain(terrain, candidate, radius, rejectWater)) return candidate;
            }
        }
        return null;
    }

    private boolean isValidTerrain(TerrainProfile terrain, BlockPos pos,
                                   int radius, boolean rejectWater) {
        boolean ridgeBlocked = !allowRidgePlacement && terrain.isOnRidge(pos, radius);
        return !ridgeBlocked && !(rejectWater && isWaterAdjacent(pos));
    }

    private boolean isWaterAdjacent(BlockPos pos) {
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos here = pos.offset(dx, 0, dz);
                BlockPos below = pos.offset(dx, -1, dz);
                if (level.isWaterAt(here)) return true;
                if (level.isWaterAt(below)) return true;
                if (isIce(here) || isIce(below)) return true;
            }
        return false;
    }

    private boolean isIce(BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.is(net.minecraft.world.level.block.Blocks.ICE)
                || state.is(net.minecraft.world.level.block.Blocks.PACKED_ICE)
                || state.is(net.minecraft.world.level.block.Blocks.BLUE_ICE)
                || state.is(net.minecraft.world.level.block.Blocks.FROSTED_ICE);
    }

    public BlockPos solidSurface(BlockPos pos) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), y, pos.getZ());
    }

    public int computePadY(BlockPos centre, int width, int length) {
        int minX = centre.getX() - width / 2;
        int maxX = centre.getX() + width / 2;
        int minZ = centre.getZ() - length / 2;
        int maxZ = centre.getZ() + length / 2;
        List<Integer> samples = new ArrayList<>();
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++)
                samples.add(level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
        Collections.sort(samples);
        return samples.get(samples.size() / 2);
    }
    /**
     * Called by layout primitives during {@code compose()} to advertise
     * a placement opportunity. The matcher consumes these after compose
     * returns. Safe to call any number of times; slots that aren't
     * used are discarded.
     */
    public void offerSlot(
            tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot slot) {
        offeredSlots.add(slot);
    }

    /**
     * Exposed to the matcher only. Returns an unmodifiable view of the
     * current slot pool. Ordering is insertion-order, which loosely
     * reflects layout-construction order (centre → roads → rings).
     */
    public List<PlacementSlot> getOfferedSlots() {
        return java.util.Collections.unmodifiableList(offeredSlots);
    }

    /** Phase 8: recipes call this from composeSectors to add a sector. */
    public void offerSector(Sector sector) {
        offeredSectors.add(sector);
    }

    /** Returns an unmodifiable view of all offered sectors so far. */
    public List<Sector> offeredSectors() {
        return java.util.Collections.unmodifiableList(offeredSectors);
    }

    /** True if any recipe has offered at least one sector during compose. */
    public boolean hasSectors() {
        return !offeredSectors.isEmpty();
    }

    /** Current size of the flat slot pool. Used with
     *  {@link #drainSlotsSince(int)} for the snapshot/drain pattern. */
    public int slotPoolSize() {
        return offeredSlots.size();
    }

    /**
     * Phase 8 transitional helper: removes and returns slots added since
     * the snapshot index. Used by recipes converting to sectors to migrate
     * slots produced by helpers (installPlaza, RingBand.emitSlots, etc.)
     * out of the flat pool into a sector. Remove after Phase 15 when no
     * recipe uses the flat pool.
     */
    public List<PlacementSlot> drainSlotsSince(int sinceIndex) {
        List<PlacementSlot> drained = new ArrayList<>(
                offeredSlots.subList(sinceIndex, offeredSlots.size()));
        offeredSlots.subList(sinceIndex, offeredSlots.size()).clear();
        return drained;
    }

    /**
     * Runs the {@link tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementMatcher}
     * against this context. Called by the planner after recipe.compose()
     * returns. Dispatches to {@link
     * tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementMatcher#runWithSectors}
     * if any sector was offered (BaseRecipe path), otherwise the legacy
     * flat-slot {@link tterrag1112.life_in_the_village.Village.Planning
     * .Zoning.PlacementMatcher#run}. No-op if both pools are empty and
     * nothing remains.
     */
    public void runMatcher() {
        if (offeredSlots.isEmpty() && offeredSectors.isEmpty()
                && remaining.isEmpty()) return;
        var matcher = new tterrag1112.life_in_the_village.Village.Planning
                .Zoning.PlacementMatcher(this, offeredSlots);
        if (hasSectors()) {
            matcher.runWithSectors(offeredSectors);
        } else {
            matcher.run();
        }
    }
    // ── Phase 16b cascade engine state ────────────────────────────────────────
    // truncationCount: number of times any road truncated severely enough
    //                  to drive a re-emit. Generalizes prior spineTruncationCount.
    // cascadeRetryCount: number of RETRY iterations the engine has performed
    //                  for this village. Cascade-aware recipes increment via
    //                  recordCascadeRetry() inside reEmit() before returning RETRY.
    // cascadeAxisRotation: accumulated radian offset applied to the primary
    //                  spine direction by RETRYs. Recipes read this during
    //                  composeOnce to align their spine with the current attempt.
    private int truncationCount = 0;
    private int cascadeRetryCount = 0;
    private double cascadeAxisRotation = 0.0;

    public void recordTruncation()       { truncationCount++; }
    public void recordCascadeRetry()     { cascadeRetryCount++; }
    public int  truncationCount()        { return truncationCount; }
    public int  cascadeRetryCount()      { return cascadeRetryCount; }
    public double cascadeAxisRotation()  { return cascadeAxisRotation; }
    public void setCascadeAxisRotation(double r) { cascadeAxisRotation = r; }
    /** Reset only the per-shape retry counter — preserves total
     *  truncationCount across fallbacks (the summary reports the
     *  cumulative truncation count over the whole compose tree). */
    public void resetCascadeRetryCount() { cascadeRetryCount = 0; }

    // ── Phase 22 cascade chain ────────────────────────────────────────────────
    // Set once at the start of VillagePlanner.plan() from
    // VillageTypeData.getFallbackChain(). Layout: [primary, ...fallbacks].
    // cascadeChainPosition is the index of the CURRENT shape; FALLBACK
    // advances to position+1.
    private java.util.List<tterrag1112.life_in_the_village.Village
            .VillageTypeData.ShapeType> cascadeChain = java.util.List.of();
    private int cascadeChainPosition = 0;

    public java.util.List<tterrag1112.life_in_the_village.Village
            .VillageTypeData.ShapeType> cascadeChain() { return cascadeChain; }
    public int cascadeChainPosition() { return cascadeChainPosition; }
    public void setCascadeChain(
            java.util.List<tterrag1112.life_in_the_village.Village
                    .VillageTypeData.ShapeType> chain) {
        this.cascadeChain = chain != null
                ? java.util.List.copyOf(chain) : java.util.List.of();
    }
    public void setCascadeChainPosition(int pos) {
        this.cascadeChainPosition = pos;
    }
    /** Advances cascadeChainPosition by one. Caller is responsible for
     *  bounds-checking via {@link #cascadeChain()} {@code .size()}. */
    public void advanceCascadeChain() { cascadeChainPosition++; }

    /**
     * Phase 22: clears mutable composition state for a chain advance.
     * Preserves immutable analysis (terrain, density, features), the
     * variant context, the rng (so seed stability holds across the
     * whole plan), and the cascade chain itself (advancing, not
     * resetting). Pairs with {@code VillageLayout.resetForFallback()};
     * runWithCascade and VillagePlanner call both.
     */
    public void resetForFallback() {
        offeredSlots.clear();
        offeredSectors.clear();
        committedSectorIds.clear();
        rejectionLog.clear();
        plazaRegions.clear();
        cascadeAxisRotation = 0.0;
        cascadeRetryCount   = 0;
        truncationCount     = 0;
        primarySpineResult  = null;
        outerRingEdgeId     = -1;
        outerRingCenterline = java.util.List.of();
        outerRingRadius     = 0;
        villageCenter       = null;
        // Phase A: clear realised-edge map and current blueprint —
        // re-emission re-realises roads against the new blueprint.
        edgesByRef.clear();
        currentBlueprint    = null;
        // Don't reset: typeData, styleSelection, sizeTier, ageCategory,
        // variantSelector (variant context — village-wide), rng (seed
        // continuity), cascadeChain / cascadeChainPosition (advancing,
        // not resetting).
    }

    // ── Phase A adaptive-layout state ─────────────────────────────────────────
    // Populated by the planner during realiseRoads / compose so the
    // SlotEmitter resolvers (Phase B) can look up a road by its
    // declarative EdgeRef. Cleared in resetForFallback.
    private final java.util.Map<String,
            tterrag1112.life_in_the_village.Village.Planning.Adaptive
                    .RealisedEdge> edgesByRef = new java.util.HashMap<>();

    public void registerRealisedEdge(
            tterrag1112.life_in_the_village.Village.Planning.Adaptive.EdgeRef ref,
            tterrag1112.life_in_the_village.Village.Planning.Adaptive
                    .RealisedEdge edge) {
        edgesByRef.put(ref.id(), edge);
    }

    @org.jetbrains.annotations.Nullable
    public tterrag1112.life_in_the_village.Village.Planning.Adaptive
            .RealisedEdge findEdge(
            tterrag1112.life_in_the_village.Village.Planning.Adaptive.EdgeRef ref) {
        return edgesByRef.get(ref.id());
    }

    public java.util.Map<String,
            tterrag1112.life_in_the_village.Village.Planning.Adaptive
                    .RealisedEdge> edgesByRef() {
        return java.util.Collections.unmodifiableMap(edgesByRef);
    }

    /** Phase B: Collection accessor over realised edges, for resolvers
     *  that iterate (e.g. {@code resolveAllSpurs} filtering by
     *  EdgeRole, {@code resolvePlazaPerimeter} scanning spur exits). */
    public java.util.Collection<tterrag1112.life_in_the_village.Village
            .Planning.Adaptive.RealisedEdge> realisedEdges() {
        return java.util.Collections.unmodifiableCollection(
                edgesByRef.values());
    }

    /** Currently-active blueprint. Set by the planner at the start of
     *  realisation; updated on re-emission. May be null between
     *  recipe stages. Read by the cascade-engine helpers
     *  (handleSevereTruncation etc.) so they can compare the old
     *  blueprint against the new one returned by reEmit. */
    @org.jetbrains.annotations.Nullable
    private tterrag1112.life_in_the_village.Village.Planning.Adaptive
            .LayoutBlueprint currentBlueprint;

    @org.jetbrains.annotations.Nullable
    public tterrag1112.life_in_the_village.Village.Planning.Adaptive
            .LayoutBlueprint getCurrentBlueprint() {
        return currentBlueprint;
    }

    public void setCurrentBlueprint(
            @org.jetbrains.annotations.Nullable
            tterrag1112.life_in_the_village.Village.Planning.Adaptive
                    .LayoutBlueprint bp) {
        this.currentBlueprint = bp;
    }

    // ── Summary tracking (Phase 16b) ──────────────────────────────────────────
    // Cascade-aware recipes record their primary spine RoadResult here so
    // VillagePlanner can print spineRatio in the per-village summary.
    // finalShape is set by the cascade engine when it falls back; the planner
    // also seeds it with the originally-dispatched shape.
    private tterrag1112.life_in_the_village.Village.Planning.Primitives
            .RoadResult primarySpineResult;
    private tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType
            finalShape;

    public void recordPrimarySpine(
            tterrag1112.life_in_the_village.Village.Planning.Primitives
                    .RoadResult r) { primarySpineResult = r; }
    public tterrag1112.life_in_the_village.Village.Planning.Primitives
            .RoadResult primarySpineResult() { return primarySpineResult; }
    public void setFinalShape(
            tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType s) {
        finalShape = s;
    }
    public tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType
            finalShape() { return finalShape; }

    /**
     * Stashes the shared outer-ring road that subsequent {@link
     * tterrag1112.life_in_the_village.Village.Planning.Primitives
     * .LayoutPrimitive.RingBand}s should attach their slots to. Called
     * once per recipe, before invoking the bands. Pass {@code edgeId=-1}
     * to clear (the default).
     */
    public void setOuterRing(int edgeId, List<BlockPos> centerline, int radius) {
        this.outerRingEdgeId = edgeId;
        this.outerRingCenterline = centerline != null
                ? centerline : java.util.List.of();
        this.outerRingRadius = radius;
    }

    public int outerRingEdgeId() { return outerRingEdgeId; }
    public List<BlockPos> outerRingCenterline() { return outerRingCenterline; }
    public int outerRingRadius() { return outerRingRadius; }

    /**
     * Stamps {@code ROAD_ADJACENT}/{@code BACKFILL} slots along a road
     * centerline at the given spacing, offset perpendicular to the road
     * on both sides. Used by every layout to turn roads into backfill
     * placement opportunities for houses, wells, and shrines.
     *
     * @param centerline the road to stamp
     * @param spacing    block interval between emitted slots along the road
     * @param perpOffset perpendicular distance from centerline (typical: 6-8)
     * @param tags       tag set to stamp on each slot
     * @param quality    base quality score; decays slightly per slot
     */
    public void offerRoadSlots(
            java.util.List<net.minecraft.core.BlockPos> centerline,
            int spacing, int perpOffset,
            java.util.Set<tterrag1112.life_in_the_village.Village.Planning
                    .Zoning.SlotTag> tags,
            int quality) {
        if (centerline == null || centerline.size() < 2) return;
        // Microfix: derive footprint budget from perpOffset so the slot
        // honestly advertises what actually fits next to the road.
        // (perpOffset - reservedHalfWidth(3) - 1 gap) * 2, floored at 4.
        int fp = Math.max(4, (perpOffset - 3 - 1) * 2);
        int q = quality;
        for (int i = spacing; i < centerline.size() - 1; i += spacing) {
            net.minecraft.core.BlockPos on = centerline.get(i);
            net.minecraft.core.BlockPos prev = centerline.get(Math.max(0, i - 1));
            net.minecraft.core.BlockPos next = centerline.get(
                    Math.min(centerline.size() - 1, i + 1));
            int headX = Integer.signum(next.getX() - prev.getX());
            int headZ = Integer.signum(next.getZ() - prev.getZ());
            if (headX == 0 && headZ == 0) { headX = 1; headZ = 0; }
            int perpX = -headZ;
            int perpZ = headX;

            for (int side : new int[]{+1, -1}) {
                net.minecraft.core.BlockPos target = on.offset(
                        perpX * perpOffset * side, 0,
                        perpZ * perpOffset * side);
                offerSlot(new tterrag1112.life_in_the_village.Village.Planning
                        .Zoning.PlacementSlot(target, centerline,
                        tags, fp, q));
            }
            q = Math.max(5, q - 1);
        }
    }
}