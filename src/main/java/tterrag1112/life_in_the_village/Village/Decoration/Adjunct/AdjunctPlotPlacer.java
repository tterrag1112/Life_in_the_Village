package tterrag1112.life_in_the_village.Village.Decoration.Adjunct;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Doc 02 §"Placement algorithm" — finds a valid face on a parent
 * {@link Building} and lays out an {@link AdjunctPlot} of the
 * requested {@link AdjunctPlotType} on it.
 *
 * <p>Probe order is the type's {@link FaceProbeOrder} translated
 * via the parent's front direction. A face passes if the probed
 * footprint clears the parent's own footprint, every other building
 * in the village, every previously-placed adjunct plot, and the
 * type's {@link AdjunctPlotType#slopeTolerance() slope tolerance}.
 * Front-of-parent is never probed (would block the door).</p>
 *
 * <p>Phase 0c materialises plot bounds only — no NBT stamping or
 * piece-kit assembly happens here. Subsystems 07 (industry), 08
 * (gardens), and 11 (homesteading) read the persisted record and
 * stamp content from their own realisers in later phases.</p>
 *
 * <p>Determinism: pure function of the placer inputs (parent
 * building, village state, world heightmap). No RNG.</p>
 */
public final class AdjunctPlotPlacer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdjunctPlotPlacer.class);

    /** Air gap between parent and plot, doc 02 §B.2. */
    public static final int PARENT_BUFFER = 1;
    /** Minimum gap between an adjunct plot and the nearest road
     *  centerline, in blocks. */
    public static final int ROAD_BUFFER = 2;

    private AdjunctPlotPlacer() {}

    /**
     * Probes for a free face on {@code parent} and returns a
     * placed {@link AdjunctPlot} on success.
     *
     * <p>{@code data} is consulted for sibling-building footprint
     * and existing adjunct-plot overlap checks. Pass the same
     * {@link VillageSavedData} the caller will later persist into.</p>
     *
     * @return populated plot on success; empty when every face
     *         failed every probe (silent drop per doc 02 §B.4)
     */
    public static Optional<AdjunctPlot> tryPlace(Building parent,
                                                 AdjunctPlotType type,
                                                 ServerLevel level,
                                                 VillageSavedData data) {
        if (parent == null || type == null || level == null) return Optional.empty();
        Direction parentFront = frontFacingOf(parent);
        Building.BuildingShape shape = parent.getShape();

        for (FaceProbeOrder.RelativeFace rel : type.faceOrder().sequence()) {
            Direction face = rel.toAbsolute(parentFront);
            Optional<AdjunctPlot> placed = probe(parent, parentFront, face,
                    type, shape, level, data);
            if (placed.isPresent()) return placed;
        }

        LOGGER.info("AdjunctPlot {} could not be placed for building {}; "
                + "all faces failed probes.", type, parent.getId());
        return Optional.empty();
    }

    // ── Single-face probe ────────────────────────────────────────────────

    private static Optional<AdjunctPlot> probe(Building parent,
                                               Direction parentFront,
                                               Direction face,
                                               AdjunctPlotType type,
                                               Building.BuildingShape shape,
                                               ServerLevel level,
                                               VillageSavedData data) {
        // Origin: parent face centre + outward step of (parent half-extent
        // along face) + buffer + plot half-extent along face.
        BlockPos faceCentre = faceCentreOf(shape, face);
        int parentHalfAlongFace = halfAlongFace(shape, face);
        int plotHalfAlongFace = halfAlongFace(type, face, parentFront);
        int outwardSteps = parentHalfAlongFace + PARENT_BUFFER + plotHalfAlongFace + 1;
        BlockPos origin = faceCentre.offset(
                face.getStepX() * outwardSteps, 0,
                face.getStepZ() * outwardSteps);

        // Local half-extents (matched to parent's facing).
        int halfX = type.defaultHalfWidthX();
        int halfZ = type.defaultHalfLengthZ();
        // If the placement face is oriented along the parent's local
        // X axis (rotation NONE / 180 → front is N/S → side faces
        // are E/W → axis X), keep halfWidthX as the perpendicular-
        // to-face dimension. Otherwise swap.
        if (face.getAxis() == Direction.Axis.X) {
            // halfX is along the outward direction; swap so halfWidth
            // is the lateral extent.
            int t = halfX; halfX = halfZ; halfZ = t;
        }

        // Footprint AABB at the proposed origin.
        int minX = origin.getX() - halfX;
        int maxX = origin.getX() + halfX;
        int minZ = origin.getZ() - halfZ;
        int maxZ = origin.getZ() + halfZ;

        // Probe 1: parent footprint overlap.
        if (boxesOverlap(minX, minZ, maxX, maxZ,
                shape.getMin().getX(), shape.getMin().getZ(),
                shape.getMax().getX(), shape.getMax().getZ())) {
            return Optional.empty();
        }

        // Probe 2: don't extend past the parent's own front face.
        if (extendsBeyondParentFront(parent, parentFront, minX, minZ, maxX, maxZ)) {
            return Optional.empty();
        }

        // Probe 3: any other building's footprint.
        for (Building other : data.getAllBuildings()) {
            if (other.getId().equals(parent.getId())) continue;
            Building.BuildingShape s = other.getShape();
            if (boxesOverlap(minX, minZ, maxX, maxZ,
                    s.getMin().getX(), s.getMin().getZ(),
                    s.getMax().getX(), s.getMax().getZ())) {
                return Optional.empty();
            }
        }

        // Probe 4: any previously-placed adjunct plot.
        // (Plots placed earlier in the same realiser pass live in
        // VillageSavedData.adjunctPlots already.)
        for (AdjunctPlot existing : data.getAllAdjunctPlots()) {
            int eMinX = existing.origin().getX() - existing.halfWidthX();
            int eMaxX = existing.origin().getX() + existing.halfWidthX();
            int eMinZ = existing.origin().getZ() - existing.halfLengthZ();
            int eMaxZ = existing.origin().getZ() + existing.halfLengthZ();
            if (boxesOverlap(minX, minZ, maxX, maxZ, eMinX, eMinZ, eMaxX, eMaxZ)) {
                return Optional.empty();
            }
        }

        // Probe 5: slope tolerance.
        SlopeReading slope = sampleSlope(level, minX, minZ, maxX, maxZ);
        if (slope == null) return Optional.empty();
        if (slope.maxY - slope.minY > type.slopeTolerance()) return Optional.empty();

        // Origin Y matches the building floor convention: median of
        // sampled heightmap values.
        BlockPos placedOrigin = new BlockPos(origin.getX(), slope.medianY, origin.getZ());
        return Optional.of(new AdjunctPlot(
                UUID.randomUUID(),
                parent.getId(),
                type,
                placedOrigin,
                halfX, halfZ,
                face,
                parent.getRotation()));
    }

    // ── Geometry helpers ─────────────────────────────────────────────────

    /** Front face convention from the building placer:
     *  rotation NONE → SOUTH-facing. */
    private static Direction frontFacingOf(Building b) {
        return switch (b.getRotation()) {
            case NONE -> Direction.SOUTH;
            case CLOCKWISE_90 -> Direction.WEST;
            case CLOCKWISE_180 -> Direction.NORTH;
            case COUNTERCLOCKWISE_90 -> Direction.EAST;
        };
    }

    /** Centre of the parent's face in world coords. */
    private static BlockPos faceCentreOf(Building.BuildingShape s, Direction face) {
        BlockPos min = s.getMin();
        BlockPos max = s.getMax();
        int cx = (min.getX() + max.getX()) / 2;
        int cz = (min.getZ() + max.getZ()) / 2;
        return switch (face) {
            case SOUTH -> new BlockPos(cx, min.getY(), max.getZ());
            case NORTH -> new BlockPos(cx, min.getY(), min.getZ());
            case EAST  -> new BlockPos(max.getX(), min.getY(), cz);
            case WEST  -> new BlockPos(min.getX(), min.getY(), cz);
            default -> new BlockPos(cx, min.getY(), cz);
        };
    }

    /** Parent's half-extent perpendicular to the given face. */
    private static int halfAlongFace(Building.BuildingShape s, Direction face) {
        return face.getAxis() == Direction.Axis.X
                ? Math.max(0, s.getWidth()  / 2)
                : Math.max(0, s.getLength() / 2);
    }

    /** Plot's half-extent perpendicular to the given face. The
     *  plot's own halfWidthX is "along the parent's local X"; the
     *  parent's local X axis is N/S when rotation is NONE/180 and
     *  E/W when rotation is 90/270. The face we're probing decides
     *  which of width/length is the outward dimension. */
    private static int halfAlongFace(AdjunctPlotType type, Direction face,
                                     Direction parentFront) {
        // "Along the face" means perpendicular to the face's tangent
        // direction. For a SOUTH face the outward axis is Z; the
        // outward dimension is whichever of (halfWidthX, halfLengthZ)
        // lines up with the parent's local Z axis.
        boolean parentLocalXIsWorldX = parentFront.getAxis() == Direction.Axis.Z;
        if (face.getAxis() == Direction.Axis.Z) {
            return parentLocalXIsWorldX
                    ? type.defaultHalfLengthZ()
                    : type.defaultHalfWidthX();
        } else {
            return parentLocalXIsWorldX
                    ? type.defaultHalfWidthX()
                    : type.defaultHalfLengthZ();
        }
    }

    private static boolean boxesOverlap(int aMinX, int aMinZ, int aMaxX, int aMaxZ,
                                        int bMinX, int bMinZ, int bMaxX, int bMaxZ) {
        return aMinX <= bMaxX && aMaxX >= bMinX
                && aMinZ <= bMaxZ && aMaxZ >= bMinZ;
    }

    /** Doc 02 §B.2: "the plot's footprint must not extend in the
     *  +front direction of the parent beyond the parent's own front
     *  face". Compares the plot's footprint to the parent's front
     *  face line. */
    private static boolean extendsBeyondParentFront(Building parent,
                                                    Direction parentFront,
                                                    int plotMinX, int plotMinZ,
                                                    int plotMaxX, int plotMaxZ) {
        Building.BuildingShape s = parent.getShape();
        return switch (parentFront) {
            case SOUTH -> plotMaxZ > s.getMax().getZ();
            case NORTH -> plotMinZ < s.getMin().getZ();
            case EAST  -> plotMaxX > s.getMax().getX();
            case WEST  -> plotMinX < s.getMin().getX();
            default -> false;
        };
    }

    private record SlopeReading(int minY, int maxY, int medianY) {}

    /** Samples the world heightmap across the footprint and returns
     *  min / max / median Y. Returns null if the sample range is
     *  empty (shouldn't happen given a non-zero footprint). */
    private static SlopeReading sampleSlope(ServerLevel level,
                                            int minX, int minZ,
                                            int maxX, int maxZ) {
        List<Integer> ys = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int y = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                ys.add(y);
            }
        }
        if (ys.isEmpty()) return null;
        ys.sort(Integer::compareTo);
        int min = ys.get(0);
        int max = ys.get(ys.size() - 1);
        int median = ys.get(ys.size() / 2);
        return new SlopeReading(min, max, median);
    }
}
