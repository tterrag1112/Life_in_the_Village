package tterrag1112.life_in_the_village.Village.Decoration.Plaza;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Planning.LayoutSlot;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Doc 04 §"Plaza generation algorithm" — produces a polygon-based
 * plaza for a given recipe spec. Single entry point
 * {@link #generate(PlanContext, PlazaSpec)} returns
 * {@link Optional#empty()} if no viable polygon could be generated;
 * the recipe falls back to its existing legacy mechanism in that
 * case.
 *
 * <h3>Determinism</h3>
 * Random rolls (square 0° vs 45°, IRREGULAR per-vertex noise) are
 * seeded deterministically from {@code (worldSeed XOR
 * targetCenter.hashCode())} so the same village seed produces the
 * same polygon across regenerations.
 *
 * <h3>Compose-time scope</h3>
 * Runs during recipe {@code compose()}. Several inputs that the
 * full doc-04 algorithm describes (committed building footprints,
 * road centerline UUIDs) aren't yet known at compose time —
 * buildings haven't been placed, road UUIDs are assigned at
 * realisation. This prompt's generator therefore:
 * <ul>
 *   <li>Skips the building-recession branch of terrain
 *       accommodation. The polygon's centre lands at
 *       {@code targetCenter} (typically village centre); the
 *       primitive layer reserves that area via
 *       {@code LayoutSlot.SlotType.DECORATION} the same way it
 *       has since pre-prompt-16.</li>
 *   <li>Leaves {@code connectedRoadIds} empty. Prompt 18 backfills
 *       these post-realisation when {@link
 *       tterrag1112.life_in_the_village.Village.Decoration.Roads
 *       .VillagePath} UUIDs exist.</li>
 * </ul>
 */
public final class PlazaGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlazaGenerator.class);

    /** Doc 04 §"Plaza generation algorithm" — minimum acceptable
     *  fraction of {@code targetArea} after terrain accommodation.
     *  Below this we still accept the plaza (better than no plaza)
     *  but log a warning. */
    private static final double MIN_AREA_FRACTION = 0.6;

    /** Doc 04 §"IRREGULAR" — Douglas-Peucker tolerance for the
     *  irregular boundary smoothing. 1.0 keeps the irregular feel
     *  while dropping noise within ~1 block. */
    private static final double IRREGULAR_DP_TOLERANCE = 1.0;

    /** Number of vertices for CIRCLE polygons. 16-gon reads as a
     *  smooth circle at plaza scale. */
    private static final int CIRCLE_VERTICES = 16;

    /** Default LINEAR plaza width when the recipe doesn't pin one. */
    private static final int LINEAR_DEFAULT_WIDTH = 5;

    /** Slope tolerance for IRREGULAR sampling — vertices whose
     *  surrounding 3-block radius drops more than this many blocks
     *  recede inward. */
    private static final int SLOPE_TOLERANCE = 2;

    /** Civic / PLAZA_ADJACENT slots are emitted along polygon edges
     *  at this step (in blocks of polygon perimeter). At ~6 blocks
     *  per slot, a CITY plaza (perimeter ~75) gets ~12 slots —
     *  enough variety for civic placement to choose from. */
    private static final int PLAZA_ADJACENT_STEP = 6;

    /** Plaza-edge civic slot baseline standoff from polygon edge,
     *  before the per-village {@code maxCivicFrontFace/2} bonus is
     *  added. Matches the legacy
     *  {@code RING_ROAD_GAP + RING_ROAD_HALFWIDTH + CIVIC_CLEARANCE}
     *  = 3 + 3 + 2 = 8 standoff. */
    private static final int PLAZA_ADJACENT_BASE_OUTSET = 8;

    /** Default {@code maxCivicFrontFace} when scanning
     *  {@code pctx.remaining} returns no civic candidates (e.g. a
     *  village with no civic buildings to place). Matches the legacy
     *  primitive's default. */
    private static final int DEFAULT_MAX_CIVIC_FRONT_FACE = 12;

    /** Maximum civic building front face for the slot footprint
     *  budget (matches the legacy primitive's {@code +4} margin). */
    private static final int CIVIC_FOOTPRINT_BUDGET = 16;

    private PlazaGenerator() {}

    /**
     * Generate a polygon plaza for {@code spec} and register it on
     * {@code pctx}. Returns the registered region or
     * {@link Optional#empty()} if generation failed (e.g. terrain
     * is hostile enough that the polygon shrunk to nothing).
     */
    public static Optional<PlazaRegion> generate(PlanContext pctx, PlazaSpec spec) {
        if (pctx == null || spec == null) return Optional.empty();

        // Step 1 — floor Y
        int floorY = resolveFloorY(pctx.level, spec.targetCenter());

        // Step 2 — raw polygon
        long seed = pctx.worldSeed
                ^ ((long) spec.targetCenter().getX() * 0x9E3779B97F4A7C15L)
                ^ ((long) spec.targetCenter().getZ() * 0xC6BC279692B5C323L);
        Random rng = new Random(seed);

        Polygon raw;
        float orientationRad = 0f;
        switch (spec.preferredShape()) {
            case CIRCLE -> raw = circle(spec, floorY);
            case SQUARE -> {
                boolean diagonal = rng.nextDouble() < 0.30; // doc 04 70/30
                orientationRad = diagonal ? (float) (Math.PI / 4) : 0f;
                raw = square(spec, floorY, orientationRad);
            }
            case LINEAR -> {
                Direction axis = spec.majorAxis().orElse(Direction.EAST);
                orientationRad = axisAngle(axis);
                raw = linear(spec, floorY, axis);
            }
            case IRREGULAR -> raw = irregular(spec, floorY, rng);
            default -> raw = circle(spec, floorY);
        }
        if (raw == null) {
            LOGGER.warn("PlazaGenerator: shape {} produced no polygon at {}",
                    spec.preferredShape(), spec.targetCenter().toShortString());
            return Optional.empty();
        }

        // Step 3 — terrain accommodation
        Polygon shaped = accommodateTerrain(pctx.level, raw, spec.targetCenter(), floorY);
        if (shaped == null || shaped.vertices().size() < 3) {
            LOGGER.warn("PlazaGenerator: polygon collapsed at {}",
                    spec.targetCenter().toShortString());
            return Optional.empty();
        }

        double finalArea = Polygon.area(shaped);
        if (finalArea < spec.targetArea() * MIN_AREA_FRACTION) {
            LOGGER.warn("PlazaGenerator: plaza at {} shrank to {} of "
                    + "target {} (area {})",
                    spec.targetCenter().toShortString(),
                    String.format("%.0f%%", 100 * finalArea / spec.targetArea()),
                    spec.targetArea(), (int) finalArea);
            // Still register — empty plaza is worse than a small one.
        }

        // Step 4 — connected road IDs left empty until prompt 18
        Set<UUID> connectedRoadIds = Set.of();

        // Step 7 — construct + register
        BlockPos centroid = Polygon.centroid(shaped);
        UUID id = UUID.nameUUIDFromBytes(
                ("plaza/" + spec.targetCenter().getX() + ","
                        + spec.targetCenter().getZ() + "/"
                        + spec.preferredShape() + "/"
                        + spec.purpose())
                        .getBytes(StandardCharsets.UTF_8));
        PlazaRegion region = new PlazaRegion(
                id, spec.purpose(), spec.preferredShape(),
                shaped, centroid, floorY,
                connectedRoadIds, orientationRad);
        pctx.addPlazaRegion(region);

        // Plaza area reservation. With the legacy LayoutPrimitive.TownSquare
        // gone, the polygon is the source of truth — we add the DECORATION
        // reservation at the polygon's bounding circle so non-civic
        // matcher commits don't overlap the plaza interior.
        Polygon.AABB bbox = Polygon.boundingBox(shaped);
        int reserveR = Math.max(bbox.width(), bbox.length()) / 2 + 1;
        pctx.layout.addForced(new tterrag1112.life_in_the_village.Village
                .Planning.LayoutSlot(
                        tterrag1112.life_in_the_village.Village.Planning
                                .LayoutSlot.SlotType.DECORATION,
                        centroid, reserveR));

        int polygonRadius = (int) Math.max(3, Math.sqrt(finalArea / Math.PI));
        // Civic standoff includes the largest civic building's
        // half-front-face so the building's far edge clears the
        // polygon. A 29×29 TOWN_HALL needs ~15 blocks of bonus
        // standoff on top of the 8-block base.
        int maxCivicFrontFace = scanMaxCivicFrontFace(pctx);
        int civicOutset = PLAZA_ADJACENT_BASE_OUTSET + maxCivicFrontFace / 2;
        int civicRingR = polygonRadius + civicOutset;

        // Phase 18: civic slots collected into a Plaza façade rather than
        // pushed to pctx.offerSlot. The matcher's civic-first claim path
        // consumes from Plaza.civicSlots() directly.
        java.util.List<tterrag1112.life_in_the_village.Village.Planning.Zoning
                .PlacementSlot> civicSlots = buildPlazaCivicSlots(
                        region, civicOutset, maxCivicFrontFace);

        tterrag1112.life_in_the_village.Village.Planning.Plaza plaza =
                new tterrag1112.life_in_the_village.Village.Planning.Plaza(
                        region, centroid, polygonRadius, civicRingR,
                        civicSlots);
        pctx.layout.addPlaza(plaza);

        // Legacy field setters — downstream consumers (DecorationSlotEmitter,
        // DumbellRecipe's trunk start, footprint-occupy in VillageDecorator)
        // still read pctx.layout.getTownSquarePos / getCivicRingRadius. The
        // getters delegate to Plaza when present so these explicit setters
        // are redundant on the new path, but cheap and they keep the
        // plaza-less fallback (ENCLAVE) consistent.
        pctx.layout.setTownSquarePos(centroid);
        pctx.layout.setTownSquareRadius(polygonRadius);
        pctx.layout.setCivicRingRadius(civicRingR);

        // Gathering points — migrated from TownSquareComposer
        // (deleted in Phase 18). Registered onto Village via
        // VillageLayout so persistence works the same way.
        int gpCount = registerGatheringPoints(pctx, region);

        LOGGER.info("PlazaGenerator: {} {} plaza at {} (area {}/{}, "
                + "vertices {}, civic slots {}, gathering points {})",
                spec.purpose(), spec.preferredShape(),
                centroid.toShortString(),
                (int) finalArea, spec.targetArea(),
                shaped.vertices().size(),
                civicSlots.size(), gpCount);
        return Optional.of(region);
    }

    // ── Step 1: floor Y ─────────────────────────────────────────────────

    private static int resolveFloorY(ServerLevel level, BlockPos centre) {
        int median = sampleHeightMedian(level, centre, 1);
        return median;
    }

    /** Median MOTION_BLOCKING_NO_LEAVES height over a {@code radius}
     *  square centered at {@code at}. Used both for plaza floor and
     *  for slope checks. */
    private static int sampleHeightMedian(ServerLevel level, BlockPos at, int radius) {
        List<Integer> ys = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ys.add(level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        at.getX() + dx, at.getZ() + dz));
            }
        }
        ys.sort(Comparator.naturalOrder());
        return ys.get(ys.size() / 2);
    }

    // ── Step 2: shape generators ────────────────────────────────────────

    private static Polygon circle(PlazaSpec spec, int floorY) {
        double radius = Math.max(2, Math.sqrt(spec.targetArea() / Math.PI));
        List<BlockPos> v = new ArrayList<>(CIRCLE_VERTICES);
        for (int i = 0; i < CIRCLE_VERTICES; i++) {
            double angle = 2 * Math.PI * i / CIRCLE_VERTICES;
            int x = spec.targetCenter().getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = spec.targetCenter().getZ() + (int) Math.round(Math.sin(angle) * radius);
            v.add(new BlockPos(x, floorY, z));
        }
        return new Polygon(v);
    }

    private static Polygon square(PlazaSpec spec, int floorY, float orientationRad) {
        double side = Math.max(3, Math.sqrt(spec.targetArea()));
        double half = side / 2.0;
        double cos = Math.cos(orientationRad);
        double sin = Math.sin(orientationRad);
        double[][] corners = {{-half, -half}, {half, -half}, {half, half}, {-half, half}};
        List<BlockPos> v = new ArrayList<>(4);
        for (double[] c : corners) {
            double rx = c[0] * cos - c[1] * sin;
            double rz = c[0] * sin + c[1] * cos;
            v.add(new BlockPos(
                    spec.targetCenter().getX() + (int) Math.round(rx),
                    floorY,
                    spec.targetCenter().getZ() + (int) Math.round(rz)));
        }
        return new Polygon(v);
    }

    private static Polygon linear(PlazaSpec spec, int floorY, Direction axis) {
        // width × length = targetArea. Width fixed; length derived.
        int width = LINEAR_DEFAULT_WIDTH;
        int length = Math.max(width + 2, spec.targetArea() / width);
        double halfL = length / 2.0;
        double halfW = width / 2.0;

        // Local axes: along = axis direction; across = perpendicular.
        int alongDx = axis.getStepX();
        int alongDz = axis.getStepZ();
        int acrossDx = -alongDz;
        int acrossDz = alongDx;

        List<BlockPos> v = new ArrayList<>(4);
        // Walk corners CCW: -along/-across, +along/-across, +along/+across, -along/+across
        v.add(corner(spec.targetCenter(), floorY, alongDx, alongDz, -halfL,
                acrossDx, acrossDz, -halfW));
        v.add(corner(spec.targetCenter(), floorY, alongDx, alongDz, halfL,
                acrossDx, acrossDz, -halfW));
        v.add(corner(spec.targetCenter(), floorY, alongDx, alongDz, halfL,
                acrossDx, acrossDz, halfW));
        v.add(corner(spec.targetCenter(), floorY, alongDx, alongDz, -halfL,
                acrossDx, acrossDz, halfW));
        return new Polygon(v);
    }

    private static BlockPos corner(BlockPos c, int floorY,
                                   int alongDx, int alongDz, double alongDist,
                                   int acrossDx, int acrossDz, double acrossDist) {
        double x = c.getX() + alongDx * alongDist + acrossDx * acrossDist;
        double z = c.getZ() + alongDz * alongDist + acrossDz * acrossDist;
        return new BlockPos((int) Math.round(x), floorY, (int) Math.round(z));
    }

    private static Polygon irregular(PlazaSpec spec, int floorY, Random rng) {
        // Start with a CIRCLE, perturb each vertex radially by a
        // deterministic noise term, then run Douglas-Peucker simplify
        // to drop small jitter. Produces visually-distinct polygons
        // per (worldSeed, position) without a wandering boundary
        // sampler.
        double baseRadius = Math.max(3, Math.sqrt(spec.targetArea() / Math.PI));
        List<BlockPos> v = new ArrayList<>(CIRCLE_VERTICES);
        for (int i = 0; i < CIRCLE_VERTICES; i++) {
            double angle = 2 * Math.PI * i / CIRCLE_VERTICES;
            // Perturbation in [-0.30, +0.30] of base radius — keeps
            // the polygon recognizable as a plaza but not perfectly
            // round.
            double jitter = 1.0 + (rng.nextDouble() - 0.5) * 0.6;
            double r = baseRadius * jitter;
            int x = spec.targetCenter().getX() + (int) Math.round(Math.cos(angle) * r);
            int z = spec.targetCenter().getZ() + (int) Math.round(Math.sin(angle) * r);
            v.add(new BlockPos(x, floorY, z));
        }
        Polygon raw = new Polygon(v);
        return Polygon.simplify(raw, IRREGULAR_DP_TOLERANCE);
    }

    private static float axisAngle(Direction d) {
        return switch (d) {
            case EAST  -> 0f;
            case SOUTH -> (float) (Math.PI / 2);
            case WEST  -> (float) Math.PI;
            case NORTH -> (float) (-Math.PI / 2);
            default    -> 0f;
        };
    }

    // ── Step 3: terrain accommodation ──────────────────────────────────

    /**
     * Single-pass per-vertex probe. For each vertex, recede inward
     * if it lands on water, deep slope, or far below the plaza
     * floor. Building-recession is deferred to prompt 18 (committed
     * buildings don't exist at compose time).
     */
    private static Polygon accommodateTerrain(ServerLevel level, Polygon raw,
                                              BlockPos centre, int floorY) {
        List<BlockPos> in = raw.vertices();
        List<BlockPos> out = new ArrayList<>(in.size());
        for (BlockPos vIn : in) {
            BlockPos adjusted = vIn;
            if (touchesWater(level, adjusted)) {
                adjusted = nudgeToward(adjusted, centre, 2);
            }
            int slope = slopeAt(level, adjusted, 3);
            if (slope > SLOPE_TOLERANCE) {
                adjusted = nudgeToward(adjusted, centre, 2);
            }
            // Drop vertex Y down to floorY for consistency — the
            // polygon is 2D in XZ; Y is informational.
            out.add(new BlockPos(adjusted.getX(), floorY, adjusted.getZ()));
        }
        // After nudging, simplify in case multiple vertices collapsed
        // onto the same XZ.
        Polygon adjusted = new Polygon(out);
        return Polygon.simplify(adjusted, 0.5);
    }

    private static boolean touchesWater(ServerLevel level, BlockPos pos) {
        int surfaceY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
        BlockState blockBelow = level.getBlockState(
                new BlockPos(pos.getX(), surfaceY - 1, pos.getZ()));
        BlockState blockAt = level.getBlockState(
                new BlockPos(pos.getX(), surfaceY, pos.getZ()));
        return blockBelow.liquid() || blockAt.liquid();
    }

    private static int slopeAt(ServerLevel level, BlockPos pos, int radius) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dx = -radius; dx <= radius; dx += radius) {
            for (int dz = -radius; dz <= radius; dz += radius) {
                int y = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        pos.getX() + dx, pos.getZ() + dz);
                if (y < min) min = y;
                if (y > max) max = y;
            }
        }
        return max - min;
    }

    private static BlockPos nudgeToward(BlockPos from, BlockPos toward, int blocks) {
        int dx = toward.getX() - from.getX();
        int dz = toward.getZ() - from.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 0.5) return from;
        double scale = blocks / len;
        return new BlockPos(
                from.getX() + (int) Math.round(dx * scale),
                from.getY(),
                from.getZ() + (int) Math.round(dz * scale));
    }

    // ── Step 6: PLAZA_ADJACENT slots ───────────────────────────────────

    /**
     * Phase 18 doc 04 §"Civic placement" — emits civic slots along
     * the polygon edge, just outside the boundary. Replaces the
     * legacy {@code LayoutPrimitive.TownSquare.emitSlots} so the
     * matcher's {@code PlacementMatcher.placeTownHallPrePass}
     * (which scans for {@link SlotTag#PRIME_CIVIC} / {@link
     * SlotTag#SECONDARY_CIVIC}) keeps working sourced from polygon
     * edges instead of ring perimeter.
     *
     * <p>The first slot (closest to the south / approach face) gets
     * {@link SlotTag#PRIME_CIVIC} and is what the prepass tries
     * first; remaining slots get {@link SlotTag#SECONDARY_CIVIC}
     * and serve TOWN_HALL fallback + GUILD_HALL / TEMPLE / MARKET /
     * INN. All slots additionally carry {@link SlotTag#PLAZA_ADJACENT}
     * + {@link SlotTag#ROAD_ADJACENT} so non-civic profiles that
     * prefer those tags get a small score nudge.</p>
     *
     * <p>Quality score decays by 2 per slot (matches the legacy
     * primitive's {@code quality = 90 - i * 2}) so the prepass's
     * tie-breaking lands on a stable south-first ordering.</p>
     */
    /**
     * Phase 18: returns the civic slot pool for {@link Plaza#civicSlots()}
     * instead of pushing slots into the flat pool. Geometry, tags, and
     * quality scoring are unchanged from the prior {@code emitPlazaCivicSlots}
     * (which used to call {@code pctx.offerSlot}).
     */
    private static java.util.List<PlacementSlot> buildPlazaCivicSlots(
            PlazaRegion region,
            int civicOutset,
            int maxCivicFrontFace) {
        List<BlockPos> verts = region.footprint().vertices();
        BlockPos centroid = region.centroid();
        int footprintBudget = Math.max(CIVIC_FOOTPRINT_BUDGET,
                maxCivicFrontFace + 4);
        java.util.List<PlacementSlot> out = new java.util.ArrayList<>();
        int n = verts.size();
        for (int i = 0; i < n; i++) {
            BlockPos a = verts.get(i);
            BlockPos b = verts.get((i + 1) % n);
            double edgeLen = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
            int samples = Math.max(1, (int) Math.round(edgeLen / PLAZA_ADJACENT_STEP));
            for (int s = 0; s < samples; s++) {
                double t = (s + 0.5) / samples;
                int mx = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t);
                int mz = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t);
                BlockPos midpoint = new BlockPos(mx, region.floorY(), mz);
                BlockPos outward = nudgeAwayFromCentroid(midpoint, centroid,
                        civicOutset);
                // First emitted slot → PRIME_CIVIC; rest →
                // SECONDARY_CIVIC. Matches the legacy primitive's
                // "first angle wins TOWN_HALL" convention.
                int emitted = out.size();
                EnumSet<SlotTag> tags = (emitted == 0)
                        ? EnumSet.of(SlotTag.PRIME_CIVIC,
                                SlotTag.PLAZA_ADJACENT, SlotTag.ROAD_ADJACENT)
                        : EnumSet.of(SlotTag.SECONDARY_CIVIC,
                                SlotTag.PLAZA_ADJACENT, SlotTag.ROAD_ADJACENT);
                int quality = Math.max(40, 90 - emitted * 2);
                out.add(new PlacementSlot(
                        outward,
                        /* feedingRoad */ null,
                        tags,
                        /* footprintBudget */ footprintBudget,
                        /* qualityScore */ quality));
            }
        }
        return out;
    }

    /**
     * Scans {@code pctx.remaining} for the largest civic-building
     * front face. Mirrors the deleted {@code LayoutPrimitive.TownSquare
     * .scanMaxCivicFrontFace}; the polygon civic outset uses this to
     * keep buildings' far edges clear of the polygon interior.
     */
    private static int scanMaxCivicFrontFace(PlanContext pctx) {
        int max = DEFAULT_MAX_CIVIC_FRONT_FACE;
        for (var sb : pctx.remaining) {
            var bt = PlanContext.parseType(sb);
            if (bt == null) continue;
            boolean isCivic = tterrag1112.life_in_the_village.Village.Planning
                    .ZoneRegistry.zoneOf(bt) == tterrag1112.life_in_the_village
                            .Village.Planning.BuildingZone.CIVIC
                    || "TOWN_HALL".equals(sb.type());
            if (!isCivic) continue;
            var info = pctx.sizes.get(sb.structure(),
                    net.minecraft.world.level.block.Rotation.NONE);
            int ff = info != null ? Math.max(info.width(), info.length()) : 12;
            if (ff > max) max = ff;
        }
        return max;
    }

    // ── Gathering point registration (migrated from TownSquareComposer) ──

    /**
     * Phase 18 doc 04 §"NPC gathering points" — migrated from the
     * deleted {@code TownSquareComposer}. Registers one
     * {@link tterrag1112.life_in_the_village.Village.Decoration
     * .TownSquare.GatheringPoint} per sub-feature (FOUNTAIN at
     * centroid, BENCHes around the polygon perimeter, NOTICE_BOARD
     * along the south edge, GAZEBO off-axis for TOWN+, VENDOR_ZONE
     * for MARKET-purpose plazas).
     *
     * <p>Position rules adapted to polygon shape — the polygon's
     * centroid + bounding box drive sub-feature placement instead
     * of a fixed radius. NPC consumption is unchanged; the
     * Village.getGatheringPoints API is stable.</p>
     */
    private static int registerGatheringPoints(PlanContext pctx, PlazaRegion region) {
        BlockPos centre = region.centroid();
        int radius = (int) Math.max(3, Math.sqrt(
                Polygon.area(region.footprint()) / Math.PI));
        var village = pctx.layout;
        int count = 0;

        // FOUNTAIN at plaza centre — always present.
        addGP(village, region, centre,
                tterrag1112.life_in_the_village.Village.Decoration
                        .TownSquare.GatheringPointKind.FOUNTAIN,
                3, "fountain");
        count++;

        // BENCHes at four cardinal positions just inside the polygon
        // edge (radius - 2) so NPCs sit looking inward at the centre.
        if (radius >= 4) {
            int benchDist = Math.max(1, radius - 2);
            int[][] cards = {{0, benchDist}, {0, -benchDist},
                             {benchDist, 0}, {-benchDist, 0}};
            for (int i = 0; i < cards.length; i++) {
                BlockPos bp = new BlockPos(
                        centre.getX() + cards[i][0],
                        centre.getY(),
                        centre.getZ() + cards[i][1]);
                addGP(village, region, bp,
                        tterrag1112.life_in_the_village.Village.Decoration
                                .TownSquare.GatheringPointKind.BENCH,
                        1, "bench-" + i);
                count++;
            }
        }

        // NOTICE_BOARD on the south edge.
        BlockPos notice = new BlockPos(centre.getX(), centre.getY(),
                centre.getZ() + Math.max(1, radius - 2));
        addGP(village, region, notice,
                tterrag1112.life_in_the_village.Village.Decoration
                        .TownSquare.GatheringPointKind.NOTICE_BOARD,
                1, "notice");
        count++;

        // GAZEBO at SW off-axis for TOWN+ (radius >= 8) plazas.
        if (radius >= 8) {
            int off = Math.max(1, radius - 3);
            BlockPos gz = new BlockPos(centre.getX() - off, centre.getY(),
                    centre.getZ() - off);
            addGP(village, region, gz,
                    tterrag1112.life_in_the_village.Village.Decoration
                            .TownSquare.GatheringPointKind.GAZEBO,
                    5, "gazebo");
            count++;
        }

        // VENDOR_ZONE for MARKET-purpose plazas + larger CIVIC plazas
        // (size hint to NPCs that the plaza can host a market).
        if (region.purpose() == PlazaPurpose.MARKET
                || (region.purpose() == PlazaPurpose.CIVIC && radius >= 5)) {
            int off = Math.max(1, radius - 2);
            BlockPos vz = new BlockPos(centre.getX() + off, centre.getY(),
                    centre.getZ() - off);
            addGP(village, region, vz,
                    tterrag1112.life_in_the_village.Village.Decoration
                            .TownSquare.GatheringPointKind.VENDOR_ZONE,
                    4, "vendor");
            count++;
        }

        return count;
    }

    private static void addGP(tterrag1112.life_in_the_village.Village
                                      .Planning.VillageLayout village,
                              PlazaRegion region, BlockPos pos,
                              tterrag1112.life_in_the_village.Village.Decoration
                                      .TownSquare.GatheringPointKind kind,
                              int capacity, String tag) {
        UUID gpId = UUID.nameUUIDFromBytes(
                ("plaza-gp/" + region.plazaId() + "/" + tag)
                        .getBytes(StandardCharsets.UTF_8));
        village.addGatheringPoint(
                new tterrag1112.life_in_the_village.Village.Decoration
                        .TownSquare.GatheringPoint(gpId, pos, kind, capacity));
    }

    private static BlockPos nudgeAwayFromCentroid(BlockPos from, BlockPos centroid,
                                                  int blocks) {
        int dx = from.getX() - centroid.getX();
        int dz = from.getZ() - centroid.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 0.5) return from;
        double scale = blocks / len;
        return new BlockPos(
                from.getX() + (int) Math.round(dx * scale),
                from.getY(),
                from.getZ() + (int) Math.round(dz * scale));
    }
}
