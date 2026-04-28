package tterrag1112.life_in_the_village.Village.Decoration.Framework;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Planning.VillageLayout;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Doc 01 §"Uniform slot emission" — walks a realised village and
 * emits a consistent density of {@link DecorationSlot}s regardless
 * of layout shape.
 *
 * <p>Six sub-algorithms (per doc 01):</p>
 * <ol>
 *   <li>Road-side slots along every road centerline.</li>
 *   <li>Building-gap slots between adjacent street-facing buildings.</li>
 *   <li>Building-corner-accent slots between angled neighbours.</li>
 *   <li>Facade-ornament slots along road-facing walls.</li>
 *   <li>Village-boundary markers at angular spacing.</li>
 *   <li>Welcome-marker / road-inbound-edge at trade road endpoints.</li>
 * </ol>
 *
 * <p>Special-context slots ({@code GUILD_EMBLEM}, {@code TRADE_SIGN},
 * {@code HEADSTONE}) are emitted by their owning subsystems
 * (signs in P1-09, cemeteries in P4-12), not by this emitter.</p>
 *
 * <p>Deterministic: same village input → same slot list, same
 * order. No RNG.</p>
 */
public final class DecorationSlotEmitter {

    /** Doc 01 §"Open decisions": "one road-side slot every 6 blocks". */
    public static final int ROAD_SIDE_SAMPLE_STEP = 6;
    /** Perpendicular distance from the road centerline to a road-side slot. */
    public static final int ROAD_SIDE_OFFSET = 3;

    /** Doc 01 §A.2: gap between street-facing buildings, min..max. */
    public static final int BUILDING_GAP_MIN = 4;
    public static final int BUILDING_GAP_MAX = 12;

    /** Doc 01 §A.3: corner proximity threshold + angle threshold. */
    public static final int CORNER_PROXIMITY = 6;
    public static final double CORNER_ANGLE_DEG = 30.0;

    /** Doc 01 §A.4: max facade ornaments per wall + min wall length. */
    public static final int FACADE_ORNAMENTS_PER_WALL = 2;
    public static final int FACADE_MIN_WALL_LENGTH = 5;

    /** Doc 01 §A.5: angular step (degrees) for boundary marker spacing. */
    public static final double VILLAGE_BOUNDARY_ANGLE_STEP_DEG = 15.0;
    public static final int VILLAGE_BOUNDARY_OUTSET = 4;

    /** Doc 01 §A.6: distance from a welcome marker to the inbound-edge slot. */
    public static final int INBOUND_EDGE_OFFSET = 4;

    /** Quality-score bounds derived from distance to town square. */
    public static final int QUALITY_MIN = 20;
    public static final int QUALITY_MAX = 100;
    /** Distance (blocks) at which qualityScore decays from MAX to MIN. */
    private static final int QUALITY_DECAY_RANGE = 64;

    private DecorationSlotEmitter() {}

    /** Bundle of emitter inputs — keeps the entry point's signature
     *  short while letting the algorithms reach village + buildings
     *  + roads. */
    public record Context(Village village, VillageSavedData data,
                          @Nullable VillageLayout layout) {}

    /**
     * Doc 01 entry point. Returns slots in deterministic emission
     * order: road-side first, then building-gap / corner / facade,
     * then village-boundary, then trade-road endpoints. Within each
     * algorithm slots are emitted in iteration order over its
     * deterministic inputs (centerlines, sorted building list, etc.).
     */
    public static List<DecorationSlot> emit(Context ctx) {
        List<DecorationSlot> out = new ArrayList<>();
        if (ctx == null || ctx.village() == null) return out;

        // Realise the building list once — every algorithm uses it.
        List<Building> buildings = collectBuildings(ctx);
        BlockPos centre = ctx.village().getTownSquarePos() != null
                ? ctx.village().getTownSquarePos()
                : ctx.village().getVillageCentre();

        emitRoadSide(ctx, centre, out);
        emitBuildingGaps(ctx, buildings, centre, out);
        emitBuildingCornerAccents(ctx, buildings, out);
        emitFacadeOrnaments(ctx, buildings, out);
        emitVillageBoundaryMarkers(ctx, buildings, centre, out);
        emitTradeRoadEndpoints(ctx, centre, out);

        return out;
    }

    // ── A.1 Road-side ────────────────────────────────────────────────────

    private static void emitRoadSide(Context ctx, @Nullable BlockPos centre,
                                     List<DecorationSlot> out) {
        if (ctx.layout() == null) return; // no road centerlines reachable
        boolean alternate = false;
        for (List<BlockPos> centerline : ctx.layout().getAllCenterlines()) {
            if (centerline == null || centerline.size() < 2) continue;
            for (int i = ROAD_SIDE_SAMPLE_STEP;
                 i < centerline.size() - 1;
                 i += ROAD_SIDE_SAMPLE_STEP) {
                BlockPos on = centerline.get(i);
                BlockPos prev = centerline.get(Math.max(0, i - 1));
                BlockPos next = centerline.get(Math.min(centerline.size() - 1, i + 1));
                int headX = Integer.signum(next.getX() - prev.getX());
                int headZ = Integer.signum(next.getZ() - prev.getZ());
                if (headX == 0 && headZ == 0) { headX = 1; headZ = 0; }
                int perpX = -headZ, perpZ = headX;

                for (int side : new int[]{+1, -1}) {
                    BlockPos pos = on.offset(perpX * ROAD_SIDE_OFFSET * side, 0,
                            perpZ * ROAD_SIDE_OFFSET * side);
                    DecorationTag tag = alternate
                            ? DecorationTag.ROAD_SIDE_LARGE
                            : DecorationTag.ROAD_SIDE_SMALL;
                    Direction facing = directionFromOffset(perpX * side, perpZ * side);
                    int budget = tag == DecorationTag.ROAD_SIDE_LARGE ? 3 : 1;
                    out.add(new DecorationSlot(
                            slotIdFor(ctx, "ROAD_SIDE", tag.name(), pos, side),
                            pos, facing,
                            EnumSet.of(tag),
                            budget,
                            qualityFromDistance(pos, centre),
                            null,
                            contextWindow(centerline, i, 4)));
                    alternate = !alternate;
                }
            }
        }
    }

    // ── A.2 Building gaps ────────────────────────────────────────────────

    private static void emitBuildingGaps(Context ctx, List<Building> buildings,
                                         @Nullable BlockPos centre,
                                         List<DecorationSlot> out) {
        for (int i = 0; i < buildings.size(); i++) {
            Building a = buildings.get(i);
            BlockPos aFront = frontFaceCentre(a);
            for (int j = i + 1; j < buildings.size(); j++) {
                Building b = buildings.get(j);
                if (a.getRotation() != b.getRotation()) continue; // both face same road
                BlockPos bFront = frontFaceCentre(b);
                int dx = bFront.getX() - aFront.getX();
                int dz = bFront.getZ() - aFront.getZ();
                double d = Math.sqrt(dx * (double) dx + dz * (double) dz);
                if (d < BUILDING_GAP_MIN || d > BUILDING_GAP_MAX) continue;

                BlockPos mid = new BlockPos(
                        (aFront.getX() + bFront.getX()) / 2,
                        (aFront.getY() + bFront.getY()) / 2,
                        (aFront.getZ() + bFront.getZ()) / 2);
                Direction facing = frontFacingOf(a);
                out.add(new DecorationSlot(
                        slotIdFor(ctx, "BUILDING_GAP", a.getId().toString(), mid, j),
                        mid, facing,
                        EnumSet.of(DecorationTag.BUILDING_GAP),
                        2,
                        qualityFromDistance(mid, centre),
                        a.getId(),
                        List.of()));
            }
        }
    }

    // ── A.3 Building corner accents ──────────────────────────────────────

    private static void emitBuildingCornerAccents(Context ctx,
                                                  List<Building> buildings,
                                                  List<DecorationSlot> out) {
        for (int i = 0; i < buildings.size(); i++) {
            Building a = buildings.get(i);
            for (int j = i + 1; j < buildings.size(); j++) {
                Building b = buildings.get(j);
                double angleA = facingAngleDeg(a);
                double angleB = facingAngleDeg(b);
                double diff = Math.abs(((angleA - angleB) + 540) % 360 - 180);
                if (diff <= CORNER_ANGLE_DEG) continue;
                BlockPos[] cornersA = corners(a);
                BlockPos[] cornersB = corners(b);
                for (BlockPos ca : cornersA) {
                    for (BlockPos cb : cornersB) {
                        int dx = cb.getX() - ca.getX();
                        int dz = cb.getZ() - ca.getZ();
                        if (dx * dx + dz * dz > CORNER_PROXIMITY * CORNER_PROXIMITY) continue;
                        BlockPos mid = new BlockPos(
                                (ca.getX() + cb.getX()) / 2,
                                (ca.getY() + cb.getY()) / 2,
                                (ca.getZ() + cb.getZ()) / 2);
                        // Bisect the angle between facing-A and facing-B for the slot direction.
                        double bisect = ((angleA + angleB) / 2.0);
                        out.add(new DecorationSlot(
                                slotIdFor(ctx, "CORNER",
                                        a.getId().toString() + "/" + b.getId(), mid, 0),
                                mid, directionFromAngleDeg(bisect),
                                EnumSet.of(DecorationTag.BUILDING_CORNER_ACCENT),
                                1,
                                60,
                                a.getId(),
                                List.of()));
                    }
                }
            }
        }
    }

    // ── A.4 Facade ornaments ─────────────────────────────────────────────

    private static void emitFacadeOrnaments(Context ctx, List<Building> buildings,
                                            List<DecorationSlot> out) {
        for (Building b : buildings) {
            Direction facing = frontFacingOf(b);
            int wallLength = wallLengthAlongFacing(b, facing);
            if (wallLength < FACADE_MIN_WALL_LENGTH) continue;
            BlockPos[] wallEnds = frontWallEnds(b);
            BlockPos start = wallEnds[0];
            BlockPos end = wallEnds[1];
            for (int n = 1; n <= FACADE_ORNAMENTS_PER_WALL; n++) {
                double t = n / (double) (FACADE_ORNAMENTS_PER_WALL + 1);
                int x = (int) Math.round(start.getX() + (end.getX() - start.getX()) * t);
                int z = (int) Math.round(start.getZ() + (end.getZ() - start.getZ()) * t);
                BlockPos onWall = new BlockPos(x, b.getShape().getOrigin().getY(), z);
                BlockPos pos = onWall.relative(facing); // 1 block out from the wall
                out.add(new DecorationSlot(
                        slotIdFor(ctx, "FACADE", b.getId().toString(), pos, n),
                        pos, facing,
                        EnumSet.of(DecorationTag.FACADE_ORNAMENT),
                        1,
                        50,
                        b.getId(),
                        List.of()));
            }
        }
    }

    // ── A.5 Village boundary markers ─────────────────────────────────────

    private static void emitVillageBoundaryMarkers(Context ctx,
                                                   List<Building> buildings,
                                                   @Nullable BlockPos centre,
                                                   List<DecorationSlot> out) {
        if (centre == null || buildings.isEmpty()) return;
        // Sample at every angular step. For each angle, find the
        // outermost building roughly in that direction and emit a
        // slot just outside it.
        int steps = (int) Math.round(360.0 / VILLAGE_BOUNDARY_ANGLE_STEP_DEG);
        for (int i = 0; i < steps; i++) {
            double angle = Math.toRadians(i * VILLAGE_BOUNDARY_ANGLE_STEP_DEG);
            double cx = Math.cos(angle), cz = Math.sin(angle);
            int outerR = outerRingRadiusInDirection(centre, buildings, cx, cz);
            if (outerR <= 0) continue;
            int markerR = outerR + VILLAGE_BOUNDARY_OUTSET;
            BlockPos pos = new BlockPos(
                    centre.getX() + (int) Math.round(cx * markerR),
                    centre.getY(),
                    centre.getZ() + (int) Math.round(cz * markerR));
            Direction facing = directionFromOffset((int) Math.signum(cx), (int) Math.signum(cz));
            out.add(new DecorationSlot(
                    slotIdFor(ctx, "BOUNDARY", "step", pos, i),
                    pos, facing,
                    EnumSet.of(DecorationTag.VILLAGE_BOUNDARY),
                    2,
                    40,
                    null,
                    List.of()));
        }
    }

    // ── A.6 Trade-road endpoints ─────────────────────────────────────────

    private static void emitTradeRoadEndpoints(Context ctx,
                                               @Nullable BlockPos centre,
                                               List<DecorationSlot> out) {
        // Doc 01: "for each trade road endpoint on the village
        // perimeter". Post-realisation we don't yet have a
        // first-class TradeRoad-to-Village endpoint accessor, so
        // we approximate via the village's gate positions
        // (capital gates + main gate endpoint). Each gate is the
        // intersection of an inbound road with the built area.
        Village v = ctx.village();
        UUID parentId = v.getId(); // attach to village for lifecycle

        // Combine gate sources into a deterministic list.
        List<BlockPos> gates = new ArrayList<>();
        if (v.getMainGateEndpoint() != null) gates.add(v.getMainGateEndpoint());
        gates.addAll(v.getCapitalGatePositions());
        if (gates.isEmpty()) return;

        for (BlockPos gate : gates) {
            Direction inward = centre == null
                    ? Direction.SOUTH
                    : directionFromOffset(
                            Integer.signum(centre.getX() - gate.getX()),
                            Integer.signum(centre.getZ() - gate.getZ()));
            // WELCOME_MARKER at the gate, facing inward.
            out.add(new DecorationSlot(
                    slotIdFor(ctx, "WELCOME", "gate", gate, 0),
                    gate, inward,
                    EnumSet.of(DecorationTag.WELCOME_MARKER),
                    3,
                    80,
                    parentId,
                    List.of()));
            // ROAD_INBOUND_EDGE one step further out, facing outward.
            Direction outward = inward.getOpposite();
            BlockPos edge = gate.offset(
                    outward.getStepX() * INBOUND_EDGE_OFFSET, 0,
                    outward.getStepZ() * INBOUND_EDGE_OFFSET);
            out.add(new DecorationSlot(
                    slotIdFor(ctx, "INBOUND", "gate", edge, 0),
                    edge, outward,
                    EnumSet.of(DecorationTag.ROAD_INBOUND_EDGE),
                    2,
                    70,
                    parentId,
                    List.of()));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Derives a deterministic slot UUID from the village id + algorithm
     * tag + a per-algorithm key. Same village + same emitter run →
     * same slotId; different villages or different per-algorithm keys
     * → different ids. Doc 01 deterministic-pass principle.
     */
    private static UUID slotIdFor(Context ctx, String algorithm,
                                  String key, BlockPos pos, int extra) {
        String composed = ctx.village().getId() + "/" + algorithm + "/"
                + key + "/" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + "/" + extra;
        return UUID.nameUUIDFromBytes(composed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static List<Building> collectBuildings(Context ctx) {
        List<Building> out = new ArrayList<>();
        for (UUID id : ctx.village().getBuildingIds()) {
            Optional<Building> b = ctx.data().getBuildingById(id);
            b.ifPresent(out::add);
        }
        // Sort by id string so iteration is deterministic across
        // runs (the underlying list iteration order is already
        // stable, but UUID-sort makes the algorithm seed-resilient).
        out.sort((a, b) -> a.getId().compareTo(b.getId()));
        return out;
    }

    private static int qualityFromDistance(BlockPos pos, @Nullable BlockPos centre) {
        if (centre == null) return QUALITY_MIN;
        int dx = pos.getX() - centre.getX();
        int dz = pos.getZ() - centre.getZ();
        double d = Math.sqrt(dx * (double) dx + dz * (double) dz);
        if (d <= 0) return QUALITY_MAX;
        double t = Math.min(1.0, d / QUALITY_DECAY_RANGE);
        return Math.max(QUALITY_MIN,
                (int) Math.round(QUALITY_MAX - (QUALITY_MAX - QUALITY_MIN) * t));
    }

    private static List<BlockPos> contextWindow(List<BlockPos> centerline,
                                                int i, int radius) {
        int from = Math.max(0, i - radius);
        int to = Math.min(centerline.size(), i + radius + 1);
        return List.copyOf(centerline.subList(from, to));
    }

    private static Direction directionFromOffset(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static Direction directionFromAngleDeg(double angleDeg) {
        double a = ((angleDeg % 360) + 360) % 360;
        if (a < 45 || a >= 315) return Direction.EAST;
        if (a < 135) return Direction.SOUTH;
        if (a < 225) return Direction.WEST;
        return Direction.NORTH;
    }

    /** Front-face-of-building convention from the placer: rotation
     *  NONE → SOUTH (front faces +Z). */
    private static Direction frontFacingOf(Building b) {
        return switch (b.getRotation()) {
            case NONE -> Direction.SOUTH;
            case CLOCKWISE_90 -> Direction.WEST;
            case CLOCKWISE_180 -> Direction.NORTH;
            case COUNTERCLOCKWISE_90 -> Direction.EAST;
        };
    }

    private static double facingAngleDeg(Building b) {
        return switch (b.getRotation()) {
            case NONE -> 90.0;          // SOUTH
            case CLOCKWISE_90 -> 180.0;  // WEST
            case CLOCKWISE_180 -> 270.0; // NORTH
            case COUNTERCLOCKWISE_90 -> 0.0; // EAST
        };
    }

    private static BlockPos frontFaceCentre(Building b) {
        Building.BuildingShape s = b.getShape();
        BlockPos min = s.getMin();
        BlockPos max = s.getMax();
        int cx = (min.getX() + max.getX()) / 2;
        int cz = (min.getZ() + max.getZ()) / 2;
        return switch (b.getRotation()) {
            case NONE -> new BlockPos(cx, min.getY(), max.getZ());
            case CLOCKWISE_90 -> new BlockPos(min.getX(), min.getY(), cz);
            case CLOCKWISE_180 -> new BlockPos(cx, min.getY(), min.getZ());
            case COUNTERCLOCKWISE_90 -> new BlockPos(max.getX(), min.getY(), cz);
        };
    }

    private static BlockPos[] frontWallEnds(Building b) {
        Building.BuildingShape s = b.getShape();
        BlockPos min = s.getMin();
        BlockPos max = s.getMax();
        return switch (b.getRotation()) {
            case NONE -> new BlockPos[]{
                    new BlockPos(min.getX(), min.getY(), max.getZ()),
                    new BlockPos(max.getX(), min.getY(), max.getZ())};
            case CLOCKWISE_90 -> new BlockPos[]{
                    new BlockPos(min.getX(), min.getY(), min.getZ()),
                    new BlockPos(min.getX(), min.getY(), max.getZ())};
            case CLOCKWISE_180 -> new BlockPos[]{
                    new BlockPos(min.getX(), min.getY(), min.getZ()),
                    new BlockPos(max.getX(), min.getY(), min.getZ())};
            case COUNTERCLOCKWISE_90 -> new BlockPos[]{
                    new BlockPos(max.getX(), min.getY(), min.getZ()),
                    new BlockPos(max.getX(), min.getY(), max.getZ())};
        };
    }

    private static int wallLengthAlongFacing(Building b, Direction facing) {
        Building.BuildingShape s = b.getShape();
        return facing.getAxis() == Direction.Axis.Z ? s.getWidth() : s.getLength();
    }

    private static BlockPos[] corners(Building b) {
        Building.BuildingShape s = b.getShape();
        BlockPos min = s.getMin();
        BlockPos max = s.getMax();
        int y = min.getY();
        return new BlockPos[]{
                new BlockPos(min.getX(), y, min.getZ()),
                new BlockPos(max.getX(), y, min.getZ()),
                new BlockPos(min.getX(), y, max.getZ()),
                new BlockPos(max.getX(), y, max.getZ()),
        };
    }

    /** Returns the radius (from {@code centre}) of the outermost
     *  building roughly in the {@code (cx, cz)} direction. Buildings
     *  whose direction-to-centre dot product with the test direction
     *  is positive (≥0) are considered "in that direction". */
    private static int outerRingRadiusInDirection(BlockPos centre,
                                                  List<Building> buildings,
                                                  double cx, double cz) {
        int best = 0;
        for (Building b : buildings) {
            BlockPos o = b.getShape().getOrigin();
            int dx = o.getX() - centre.getX();
            int dz = o.getZ() - centre.getZ();
            double dot = dx * cx + dz * cz;
            if (dot < 0) continue;
            int dist = (int) Math.round(Math.sqrt(dx * (double) dx + dz * (double) dz));
            if (dist > best) best = dist;
        }
        return best;
    }
}
