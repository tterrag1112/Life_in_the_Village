package tterrag1112.life_in_the_village.Village.Planning.Primitives;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.Inhabitants.BuildingInhabitantRegistry;
import tterrag1112.life_in_the_village.Village.Planning.*;
import tterrag1112.life_in_the_village.Village.Planning.Rules.RuleContext;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
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

    private final List<PlacementSlot> offeredSlots = new java.util.ArrayList<>();


    private static final int MAX_TERRAIN_ATTEMPTS = 32;

    public PlanContext(ServerLevel level, VillageLayout layout,
                       StructureSizeCache sizes, Random rng,
                       RuleContext ruleCtx, LayoutDensityProfile density,
                       long worldSeed,
                       List<VillageTypeData.StarterBuilding> remaining) {
        this.level = level;
        this.layout = layout;
        this.sizes = sizes;
        this.rng = rng;
        this.ruleCtx = ruleCtx;
        this.density = density;
        this.worldSeed = worldSeed;
        this.remaining = remaining;
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
                return null;
            }
            target = adjusted;
        }

        Rotation rotation = feedingRoad != null && !feedingRoad.isEmpty()
                ? rotationFacingRoad(target, feedingRoad)
                : Rotation.NONE;

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
            return null;
        }
        if (diag) System.out.println("[TH]   resolved=" + resolved);

        LayoutSlot slot = new LayoutSlot(resolved, bt, sb.structure(), slotRadius, rotation);
        slot.setFootprint(w, l);
        slot.setPadY(computePadY(resolved, w, l));

        int halfW = w / 2;
        int halfL = l / 2;
        BlockPos footprintOrigin = new BlockPos(
                resolved.getX() - halfW, resolved.getY(), resolved.getZ() - halfL);
        if (!layout.getRoadFootprint().isClear(footprintOrigin, w, l, 1)) {
            if (diag) System.out.println("[TH]   FAIL road-footprint overlap");
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
                return null;
            }
        }

        if (!layout.tryAdd(slot)) {
            if (diag) System.out.println("[TH]   FAIL layout.tryAdd (overlap with existing forced/building slot)");
            return null;
        }
        if (diag) System.out.println("[TH]   OK committed");
        return slot;
    }


    /**
     * Retries {@link #tryCommitBuilding} at a sequence of positions — the
     * target, then small shifts along the road direction, then shifts
     * perpendicular to it. Used when a primitive wants to place a
     * building near an ideal spot but has some flexibility.
     */
    public LayoutSlot tryCommitWithRetries(BlockPos target,
                                           VillageTypeData.StarterBuilding sb,
                                           BuildingType bt,
                                           List<BlockPos> feedingRoad,
                                           int maxShift) {
        LayoutSlot s = tryCommitBuilding(target, sb, bt, feedingRoad);
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

        int[] offsets = {4, -4, 8, -8, 12, -12, maxShift, -maxShift};
        for (int d : offsets) {
            if (Math.abs(d) > maxShift) continue;
            BlockPos along = target.offset(headX * d, 0, headZ * d);
            s = tryCommitBuilding(along, sb, bt, feedingRoad);
            if (s != null) return s;
        }
        for (int d : new int[]{4, -4, 8, -8}) {
            BlockPos perp = target.offset(perpX * d, 0, perpZ * d);
            s = tryCommitBuilding(perp, sb, bt, feedingRoad);
            if (s != null) return s;
        }
        return null;
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

    /**
     * Runs the {@link tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementMatcher}
     * against this context. Called by the planner after recipe.compose()
     * returns. No-op if no slots were offered and nothing remains to
     * place — i.e. a recipe that still does all its work the old way
     * via claimByZone is unaffected.
     */
    public void runMatcher() {
        if (offeredSlots.isEmpty() && remaining.isEmpty()) return;
        new tterrag1112.life_in_the_village.Village.Planning.Zoning
                .PlacementMatcher(this, offeredSlots).run();
    }
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
                        tags, 16, q));
            }
            q = Math.max(5, q - 1);
        }
    }
}