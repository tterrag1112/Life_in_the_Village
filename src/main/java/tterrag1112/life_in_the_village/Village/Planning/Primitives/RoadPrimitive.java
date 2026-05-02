package tterrag1112.life_in_the_village.Village.Planning.Primitives;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Economy.Trade.RoutePathSmoother;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A composable road centerline primitive.
 *
 * <h3>What it is</h3>
 * A primitive describes HOW a road curves through space — its centerline —
 * but not how it's painted. The organic block painting is done later by
 * {@code OrganicRoadPlacer}, which takes the computed centerline and
 * handles perpendicular block expansion and edge noise. Primitives are
 * the geometric intent; the placer is the rendering.
 *
 * <h3>Drift</h3>
 * Every road wanders slightly, because Minecraft's square grid makes
 * perfectly-straight roads look artificial. Drift is a 1D value-noise
 * offset applied perpendicular to the local heading. Drift amplitude
 * scales with {@code min(1.0, length / 64.0)} so short connector roads
 * stay visibly straight while long trunk roads have room to wander.
 *
 * <h3>Determinism</h3>
 * Noise is seeded from the primitive's endpoints plus the world seed.
 * Planning the same village twice with the same seed produces identical
 * centerlines — important for validation and for planned-but-unrealised
 * villages that will be rendered later.
 *
 * <h3>Spur parent reference</h3>
 * {@link Spur} holds its parent centerline as a direct field rather than
 * a reference to the parent primitive. The recipe that constructs the
 * spur already has the parent's centerline in hand, so there's no reason
 * to thread a "computed so far" map through the API.
 *
 * <h3>Codec</h3>
 * {@link #CODEC} is a dispatch codec keyed on {@link #typeKey()}.
 * Each record sub-type carries its own CODEC constant. The dispatch lambda
 * is evaluated lazily (only at encode/decode time), so subtype CODEC
 * initialization order is not constrained.
 */
public sealed interface RoadPrimitive
        permits RoadPrimitive.StraightRoad,
        RoadPrimitive.CurvedRoad,
        RoadPrimitive.Ring,
        RoadPrimitive.Arc,
        RoadPrimitive.Spur,
        RoadPrimitive.SmoothedPath,
        RoadPrimitive.ArmApproach,
        RoadPrimitive.Bridge,
        RoadPrimitive.Stairway {

    /**
     * Computes the centerline of this road, snapped to the surface Y at
     * every step. Pure — no blocks are placed.
     *
     * <p>Implementations may truncate the centerline where terrain refuses
     * traversal (cliffs beyond {@link PrimitiveContext#maxStepDeltaY()},
     * water for non-water-capable primitives, missing surface). The
     * returned {@link CenterlineResult} carries the accepted points plus
     * a {@link TerminationReason}; on default-Minecraft terrain the
     * reason is {@link TerminationReason#COMPLETED}.
     *
     * @param ctx primitive inputs (level, seed, feature map, cliff threshold)
     */
    CenterlineResult computeCenterline(PrimitiveContext ctx);

    /**
     * Whether this primitive is permitted to walk over water. Defaults
     * false; {@link Bridge} and future Causeway-style primitives override
     * to true. When false, a centerline point that maps to a water
     * feature truncates the walk with
     * {@link TerminationReason#WATER_CROSSING}.
     */
    default boolean isWaterCapable() {
        return false;
    }

    /** Tier this road should be painted at. */
    RoadShape.RoadTier tier();

    /**
     * Phase 16b: pre-truncation target length in blocks. Recipes compare
     * the actual centerline length against this to detect severe
     * truncation (see {@link RoadResult#completionRatio()}).
     *
     * <p>Default throws {@link UnsupportedOperationException} — every
     * subtype that flows through {@code computeAndRecord} must override.
     * This makes accidental omission fail loudly instead of silently
     * returning 0 (which would make every road look 100%-complete).
     */
    default int intendedLength() {
        throw new UnsupportedOperationException(
                "intendedLength() not implemented for " + typeKey());
    }

    /**
     * Stable string key used as the discriminator in {@link #CODEC}.
     * Must be unique across all permitted subtypes and must not change
     * once edges have been serialized to disk.
     */
    String typeKey();

    /**
     * Dispatch codec for all permitted subtypes. Each subtype's CODEC is
     * a {@link MapCodec} so its fields sit as siblings of the "type"
     * discriminator rather than nested in a sub-object. The lambda is
     * evaluated lazily so subtype CODEC initialization order is unconstrained.
     */
    Codec<RoadPrimitive> CODEC = Codec.STRING.dispatch(
            "type",
            RoadPrimitive::typeKey,
            type -> switch (type) {
                case "StraightRoad"  -> StraightRoad.CODEC;
                case "CurvedRoad"    -> CurvedRoad.CODEC;
                case "Ring"          -> Ring.CODEC;
                case "Arc"           -> Arc.CODEC;
                case "Spur"          -> Spur.CODEC;
                case "SmoothedPath"  -> SmoothedPath.CODEC;
                case "ArmApproach"   -> ArmApproach.CODEC;
                case "Bridge"        -> Bridge.CODEC;
                case "Stairway"      -> Stairway.CODEC;
                default -> throw new IllegalArgumentException(
                        "Unknown RoadPrimitive type: '" + type + "'");
            }
    );

    // =========================================================================
    // StraightRoad
    // =========================================================================

    /**
     * A nearly-straight road from {@code from} to {@code to} with natural
     * perpendicular drift. Use for main roads leading out of the village,
     * direct links between the town square and an inner focal point,
     * and any other connection where the intent is "go directly there
     * but don't look like a ruler drew it."
     *
     * @param driftAmplitude maximum perpendicular offset in blocks.
     *                       3 = subtle wander, 6 = visible wander,
     *                       10+ = hairpin territory (don't).
     */
    record StraightRoad(
            BlockPos from,
            BlockPos to,
            double driftAmplitude,
            RoadShape.RoadTier tier
    ) implements RoadPrimitive {

        static final MapCodec<StraightRoad> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BlockPos.CODEC.fieldOf("from").forGetter(StraightRoad::from),
                BlockPos.CODEC.fieldOf("to").forGetter(StraightRoad::to),
                Codec.DOUBLE.fieldOf("driftAmplitude").forGetter(StraightRoad::driftAmplitude),
                Codec.STRING.xmap(RoadShape.RoadTier::valueOf, RoadShape.RoadTier::name)
                        .fieldOf("tier").forGetter(StraightRoad::tier)
        ).apply(i, StraightRoad::new));

        @Override public String typeKey() { return "StraightRoad"; }

        @Override
        public int intendedLength() {
            return (int) Math.round(Math.sqrt(from.distSqr(to)));
        }

        @Override
        public CenterlineResult computeCenterline(PrimitiveContext ctx) {
            long localSeed = DriftNoise.localSeed(ctx.worldSeed(), from, to);
            List<BlockPos> line = driftedLine(ctx.level(), from, to,
                    driftAmplitude, localSeed);
            return applyTerrainChecks(line, ctx, isWaterCapable());
        }
    }

    // =========================================================================
    // CurvedRoad
    // =========================================================================

    /**
     * A bowed arc between {@code from} and {@code to}. The midpoint of the
     * arc is offset perpendicular to the chord by {@code curvature *
     * chordLength}. Use for ring segments and for roads that need to
     * sweep around an obstacle rather than going straight through it.
     *
     * @param curvature bow amount as a fraction of chord length.
     *                  0 = straight, 0.15 = gentle curve, 0.3 = strong arc,
     *                  0.5+ = nearly semicircular.
     * @param driftAmplitude additional wander on top of the curve
     */
    record CurvedRoad(
            BlockPos from,
            BlockPos to,
            double curvature,
            double driftAmplitude,
            RoadShape.RoadTier tier
    ) implements RoadPrimitive {

        static final MapCodec<CurvedRoad> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BlockPos.CODEC.fieldOf("from").forGetter(CurvedRoad::from),
                BlockPos.CODEC.fieldOf("to").forGetter(CurvedRoad::to),
                Codec.DOUBLE.fieldOf("curvature").forGetter(CurvedRoad::curvature),
                Codec.DOUBLE.fieldOf("driftAmplitude").forGetter(CurvedRoad::driftAmplitude),
                Codec.STRING.xmap(RoadShape.RoadTier::valueOf, RoadShape.RoadTier::name)
                        .fieldOf("tier").forGetter(CurvedRoad::tier)
        ).apply(i, CurvedRoad::new));

        @Override public String typeKey() { return "CurvedRoad"; }

        @Override
        public CenterlineResult computeCenterline(PrimitiveContext ctx) {
            ServerLevel level = ctx.level();
            long localSeed = DriftNoise.localSeed(ctx.worldSeed(), from, to)
                    ^ Double.doubleToLongBits(curvature);

            int dx = to.getX() - from.getX();
            int dz = to.getZ() - from.getZ();
            double chordLen = Math.sqrt(dx * dx + dz * dz);
            if (chordLen < 1) {
                List<BlockPos> out = new ArrayList<>();
                out.add(surfaceAt(level, from.getX(), from.getZ()));
                return CenterlineResult.complete(out);
            }

            // Unit perpendicular (rotate heading 90° CCW)
            double perpX = -dz / chordLen;
            double perpZ = dx / chordLen;
            double bow = curvature * chordLen;

            int steps = Math.max(2, (int) Math.ceil(chordLen));
            List<BlockPos> line = new ArrayList<>(steps + 1);

            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                // Quadratic Bezier with midpoint pulled along perpendicular
                double s = 4 * t * (1 - t);  // 0 at ends, 1 at middle
                double baseX = from.getX() + dx * t;
                double baseZ = from.getZ() + dz * t;
                double arcX = baseX + perpX * bow * s;
                double arcZ = baseZ + perpZ * bow * s;

                double driftOffset = DriftNoise.sample(t, localSeed)
                        * driftAmplitude
                        * Math.min(1.0, chordLen / 64.0);

                int x = (int) Math.round(arcX + perpX * driftOffset);
                int z = (int) Math.round(arcZ + perpZ * driftOffset);
                line.add(surfaceAt(level, x, z));
            }
            return applyTerrainChecks(dedupe(line), ctx, isWaterCapable());
        }
    }

    // =========================================================================
    // Ring
    // =========================================================================

    /**
     * A closed ring around {@code centre} at {@code radius}, with per-angle
     * drift so the ring wobbles organically rather than tracing a perfect
     * circle. Use for ring roads around plazas or around the entire village.
     *
     * <p>The centerline closes on itself — last point equals first point —
     * so the road placer paints a continuous loop.
     */
    record Ring(
            BlockPos centre,
            int radius,
            double driftAmplitude,
            RoadShape.RoadTier tier
    ) implements RoadPrimitive {

        static final MapCodec<Ring> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BlockPos.CODEC.fieldOf("centre").forGetter(Ring::centre),
                Codec.INT.fieldOf("radius").forGetter(Ring::radius),
                Codec.DOUBLE.fieldOf("driftAmplitude").forGetter(Ring::driftAmplitude),
                Codec.STRING.xmap(RoadShape.RoadTier::valueOf, RoadShape.RoadTier::name)
                        .fieldOf("tier").forGetter(Ring::tier)
        ).apply(i, Ring::new));

        @Override public String typeKey() { return "Ring"; }

        @Override
        public int intendedLength() {
            return (int) Math.round(2.0 * Math.PI * radius);
        }

        @Override
        public CenterlineResult computeCenterline(PrimitiveContext ctx) {
            ServerLevel level = ctx.level();
            long localSeed = DriftNoise.localSeed(ctx.worldSeed(), centre, centre)
                    ^ ((long) radius * 2654435761L);

            // One sample per block of circumference
            int steps = Math.max(16, (int) Math.ceil(2 * Math.PI * radius));
            List<BlockPos> line = new ArrayList<>(steps + 1);

            double ampScale = Math.min(1.0, (2 * Math.PI * radius) / 64.0);

            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                double angle = t * 2 * Math.PI;
                double drift = DriftNoise.sample(t, localSeed) * driftAmplitude * ampScale;
                double r = radius + drift;
                int x = centre.getX() + (int) Math.round(Math.cos(angle) * r);
                int z = centre.getZ() + (int) Math.round(Math.sin(angle) * r);
                line.add(surfaceAt(level, x, z));
            }
            return applyTerrainChecks(dedupe(line), ctx, isWaterCapable());
        }
    }

    // =========================================================================
    // Arc
    // =========================================================================

    /**
     * A partial-circle road segment — a slice of a Ring spanning
     * {@code arcSpan} radians starting from {@code startAngle}. Used by
     * radial layouts to create concentric half-rings that visually
     * connect adjacent spurs without forming closed loops.
     *
     * <p>Where {@link Ring} closes on itself, an Arc has two distinct
     * endpoints — its start and end coordinates can serve as branch
     * targets for spurs that want to T-into the arc midway.
     *
     * @param centre  centre of the parent circle
     * @param radius  radius of the arc
     * @param startAngle starting angle in radians (math convention)
     * @param arcSpan angular extent in radians (e.g. Math.PI for a half-ring)
     */
    record Arc(
            BlockPos centre,
            int radius,
            double startAngle,
            double arcSpan,
            double driftAmplitude,
            RoadShape.RoadTier tier
    ) implements RoadPrimitive {

        static final MapCodec<Arc> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BlockPos.CODEC.fieldOf("centre").forGetter(Arc::centre),
                Codec.INT.fieldOf("radius").forGetter(Arc::radius),
                Codec.DOUBLE.fieldOf("startAngle").forGetter(Arc::startAngle),
                Codec.DOUBLE.fieldOf("arcSpan").forGetter(Arc::arcSpan),
                Codec.DOUBLE.fieldOf("driftAmplitude").forGetter(Arc::driftAmplitude),
                Codec.STRING.xmap(RoadShape.RoadTier::valueOf, RoadShape.RoadTier::name)
                        .fieldOf("tier").forGetter(Arc::tier)
        ).apply(i, Arc::new));

        @Override public String typeKey() { return "Arc"; }

        @Override
        public int intendedLength() {
            return (int) Math.round(radius * Math.abs(arcSpan));
        }

        @Override
        public CenterlineResult computeCenterline(PrimitiveContext ctx) {
            ServerLevel level = ctx.level();
            long localSeed = DriftNoise.localSeed(ctx.worldSeed(), centre, centre)
                    ^ ((long) radius * 2654435761L)
                    ^ Double.doubleToLongBits(startAngle)
                    ^ Double.doubleToLongBits(arcSpan);

            // One sample per block of arc length
            double arcLength = Math.abs(arcSpan) * radius;
            int steps = Math.max(8, (int) Math.ceil(arcLength));
            List<BlockPos> line = new ArrayList<>(steps + 1);

            double ampScale = Math.min(1.0, arcLength / 64.0);

            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                double angle = startAngle + arcSpan * t;
                double drift = DriftNoise.sample(t, localSeed) * driftAmplitude * ampScale;
                double r = radius + drift;
                int x = centre.getX() + (int) Math.round(Math.cos(angle) * r);
                int z = centre.getZ() + (int) Math.round(Math.sin(angle) * r);
                line.add(surfaceAt(level, x, z));
            }
            return applyTerrainChecks(dedupe(line), ctx, isWaterCapable());
        }
    }

    // =========================================================================
    // Spur
    // =========================================================================

    /**
     * A road that branches off an existing road at a specific point.
     *
     * <h3>Branch snapping</h3>
     * {@code branchPointHint} is the recipe's ideal location for the
     * branch — e.g. "halfway along the main road" or "east end of the
     * ring." The spur snaps this hint to the nearest actual block on the
     * parent centerline so the two roads meet exactly, not one block off.
     *
     * <h3>Parent centerline as a field</h3>
     * The parent centerline is stored directly on the spur, not looked
     * up via a reference. The recipe already has it in hand when it
     * builds the spur, and this keeps {@code computeCenterline}'s signature
     * simple with no ordering dependency between primitives.
     *
     * @param parentCenterline the centerline the spur branches off
     * @param branchPointHint recipe's ideal branch position (will snap)
     * @param directionRad outward direction from the parent in radians
     * @param length spur length in blocks from branch point to endpoint
     */
    record Spur(
            List<BlockPos> parentCenterline,
            BlockPos branchPointHint,
            double directionRad,
            int length,
            double driftAmplitude,
            RoadShape.RoadTier tier
    ) implements RoadPrimitive {

        static final MapCodec<Spur> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BlockPos.CODEC.listOf().fieldOf("parentCenterline")
                        .forGetter(Spur::parentCenterline),
                BlockPos.CODEC.fieldOf("branchPointHint").forGetter(Spur::branchPointHint),
                Codec.DOUBLE.fieldOf("directionRad").forGetter(Spur::directionRad),
                Codec.INT.fieldOf("length").forGetter(Spur::length),
                Codec.DOUBLE.fieldOf("driftAmplitude").forGetter(Spur::driftAmplitude),
                Codec.STRING.xmap(RoadShape.RoadTier::valueOf, RoadShape.RoadTier::name)
                        .fieldOf("tier").forGetter(Spur::tier)
        ).apply(i, Spur::new));

        @Override public String typeKey() { return "Spur"; }

        @Override
        public int intendedLength() {
            return length;
        }

        @Override
        public CenterlineResult computeCenterline(PrimitiveContext ctx) {
            ServerLevel level = ctx.level();
            BlockPos snappedStart = nearestOnCenterline(parentCenterline, branchPointHint);
            int endX = snappedStart.getX() + (int) Math.round(Math.cos(directionRad) * length);
            int endZ = snappedStart.getZ() + (int) Math.round(Math.sin(directionRad) * length);
            BlockPos end = surfaceAt(level, endX, endZ);

            long localSeed = DriftNoise.localSeed(ctx.worldSeed(), snappedStart, end);
            List<BlockPos> line = driftedLine(level, snappedStart, end,
                    driftAmplitude, localSeed);
            return applyTerrainChecks(line, ctx, isWaterCapable());
        }

        private static BlockPos nearestOnCenterline(List<BlockPos> centerline, BlockPos hint) {
            BlockPos best = centerline.get(0);
            double bestDistSq = best.distSqr(hint);
            for (BlockPos p : centerline) {
                double d = p.distSqr(hint);
                if (d < bestDistSq) { bestDistSq = d; best = p; }
            }
            return best;
        }
    }

    // =========================================================================
    // SmoothedPath  (Phase 5a)
    // =========================================================================

    /**
     * A road primitive that wraps an ordered list of waypoints and produces
     * a Catmull-Rom smoothed centerline. Used for trade roads and long
     * connectors where the cell path provides routing but Catmull-Rom
     * removes the angular joins at cell boundaries.
     *
     * <p>Waypoints are typically: [hub A, cell-center 1 … cell-center N-1, hub B].
     * The smoothing inserts {@link RoutePathSmoother#SUBDIVISIONS_PER_SEGMENT}
     * interpolated points between each consecutive pair, surface-snapped.
     *
     * <p>{@code driftAmplitude} is stored for future use but not applied in
     * Phase 5a — the cell-path A* routing already produces inherent waviness,
     * and Catmull-Rom smoothing amplifies natural variation.
     */
    record SmoothedPath(
            List<BlockPos> waypoints,
            float tension,
            double driftAmplitude,
            RoadShape.RoadTier tier,
            long seed
    ) implements RoadPrimitive {

        static final MapCodec<SmoothedPath> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BlockPos.CODEC.listOf().fieldOf("waypoints").forGetter(SmoothedPath::waypoints),
                Codec.FLOAT.fieldOf("tension").forGetter(SmoothedPath::tension),
                Codec.DOUBLE.fieldOf("driftAmplitude").forGetter(SmoothedPath::driftAmplitude),
                Codec.STRING.xmap(RoadShape.RoadTier::valueOf, RoadShape.RoadTier::name)
                        .fieldOf("tier").forGetter(SmoothedPath::tier),
                Codec.LONG.optionalFieldOf("seed", 0L).forGetter(SmoothedPath::seed)
        ).apply(i, SmoothedPath::new));

        @Override public String typeKey() { return "SmoothedPath"; }

        @Override
        public CenterlineResult computeCenterline(PrimitiveContext ctx) {
            ServerLevel level = ctx.level();
            if (waypoints.size() < 2) {
                return CenterlineResult.complete(new ArrayList<>(waypoints));
            }
            List<BlockPos> smoothed = RoutePathSmoother.smooth(level, waypoints);
            if (driftAmplitude > 0) {
                long effectiveSeed = seed != 0 ? seed
                        : DriftNoise.localSeed(ctx.worldSeed(),
                                waypoints.get(0), waypoints.get(waypoints.size() - 1));
                smoothed = applyLateralDrift(level, smoothed, driftAmplitude, effectiveSeed);
            }
            // Trade roads were routed by cell-path A* before reaching this
            // primitive; truncating mid-path here would orphan the far
            // hub. Skip terrain checks — return the full smoothed path.
            return CenterlineResult.complete(smoothed);
        }

        private static List<BlockPos> applyLateralDrift(
                ServerLevel level,
                List<BlockPos> centerline,
                double amplitude,
                long localSeed) {
            int n = centerline.size();
            if (n < 2) return centerline;

            // Compute cumulative arc length for t-parameterization
            double[] cumLen = new double[n];
            for (int i = 1; i < n; i++) {
                BlockPos a = centerline.get(i - 1), b = centerline.get(i);
                int dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
                cumLen[i] = cumLen[i - 1] + Math.sqrt((double)(dx * dx + dz * dz));
            }
            double totalLen = cumLen[n - 1];
            if (totalLen < 1) return centerline;

            double ampScale = Math.min(1.0, totalLen / 64.0);
            List<BlockPos> out = new ArrayList<>(n);

            for (int i = 0; i < n; i++) {
                double t = cumLen[i] / totalLen;

                // Local heading: use neighbours for perpendicular direction
                int prevI = Math.max(0, i - 1);
                int nextI = Math.min(n - 1, i + 1);
                int hdx = centerline.get(nextI).getX() - centerline.get(prevI).getX();
                int hdz = centerline.get(nextI).getZ() - centerline.get(prevI).getZ();
                double hlen = Math.sqrt((double)(hdx * hdx + hdz * hdz));
                if (hlen < 0.001) { out.add(centerline.get(i)); continue; }

                double perpX = -hdz / hlen;
                double perpZ =  hdx / hlen;
                double drift = DriftNoise.sample(t, localSeed) * amplitude * ampScale;

                int x = centerline.get(i).getX() + (int) Math.round(perpX * drift);
                int z = centerline.get(i).getZ() + (int) Math.round(perpZ * drift);
                out.add(surfaceAt(level, x, z));
            }
            return dedupe(out);
        }
    }

    // =========================================================================
    // ArmApproach  (Phase 5a)
    // =========================================================================

    /**
     * The village-arm approach segment: a nearly-straight connection from the
     * trade road's docking anchor to the village's arm endpoint (gate).
     *
     * <p>This primitive exists as a distinct type because its PLACEMENT rules
     * differ from {@link StraightRoad}:
     * <ul>
     *   <li>Material is the village's own internal road material, not the
     *       trade-road cobblestone — creating a seamless visual join.</li>
     *   <li>After block placement, {@code ConnectorAllee.place} is called for
     *       roadside saplings (culturally themed per the village type).</li>
     * </ul>
     *
     * <p>Centerline direction: dockingAnchor → armEndpoint. The placer reverses
     * this when assembling the blockPath for an edge where this is the leading
     * approach (nodeA side).
     *
     * @param villageId identifies the village whose material and culture apply
     */
    record ArmApproach(
            BlockPos dockingAnchor,
            BlockPos armEndpoint,
            UUID villageId,
            RoadShape.RoadTier tier
    ) implements RoadPrimitive {

        private static final Codec<UUID> UUID_CODEC =
                Codec.STRING.xmap(UUID::fromString, UUID::toString);

        static final MapCodec<ArmApproach> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BlockPos.CODEC.fieldOf("dockingAnchor").forGetter(ArmApproach::dockingAnchor),
                BlockPos.CODEC.fieldOf("armEndpoint").forGetter(ArmApproach::armEndpoint),
                UUID_CODEC.fieldOf("villageId").forGetter(ArmApproach::villageId),
                Codec.STRING.xmap(RoadShape.RoadTier::valueOf, RoadShape.RoadTier::name)
                        .fieldOf("tier").forGetter(ArmApproach::tier)
        ).apply(i, ArmApproach::new));

        @Override public String typeKey() { return "ArmApproach"; }

        /** Low drift (2.0 blocks) keeps the approach visually straight. */
        private static final double DRIFT = 2.0;

        @Override
        public CenterlineResult computeCenterline(PrimitiveContext ctx) {
            long seed = DriftNoise.localSeed(ctx.worldSeed(),
                    dockingAnchor, armEndpoint);
            List<BlockPos> line = driftedLine(ctx.level(),
                    dockingAnchor, armEndpoint, DRIFT, seed);
            return applyTerrainChecks(line, ctx, isWaterCapable());
        }
    }

    // =========================================================================
    // Bridge  (Phase 12.1)
    // =========================================================================

    /**
     * A road primitive that spans water with a plank deck. Used by recipes
     * that intentionally connect across water (RIVERINE may stub a bridge
     * to a far bank; CROSSROADS may bridge a stream).
     *
     * <p>The centerline is a {@link #driftedLine}-style straight surface-snapped
     * line from {@code from} to {@code to} with low drift (1.5) so the
     * bridge reads as a deliberate structure. The realiser
     * ({@code EdgeRealizer}) recognises Bridge primitives and dispatches
     * to {@code RoadRouter.placeBridge} instead of the surface-paint
     * pipeline — no road blocks are stamped under the deck.
     *
     * <p>Tier governs deck width via the same conventions used by
     * {@code OrganicRoadPlacer}, although the current
     * {@code RoadRouter.placeBridge} implementation hard-codes a default
     * width; tier is preserved on the record so future deck-width
     * dispatch can read it without an interface change.
     *
     * @param from bridge head on the village side; should be a SHORE_HEAD
     *             or BRIDGE_HEAD node position
     * @param to   bridge tail on the far side
     * @param tier road tier (deck width derives from tier)
     */
    record Bridge(
            BlockPos from,
            BlockPos to,
            RoadShape.RoadTier tier
    ) implements RoadPrimitive {

        static final MapCodec<Bridge> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BlockPos.CODEC.fieldOf("from").forGetter(Bridge::from),
                BlockPos.CODEC.fieldOf("to").forGetter(Bridge::to),
                Codec.STRING.xmap(RoadShape.RoadTier::valueOf, RoadShape.RoadTier::name)
                        .fieldOf("tier").forGetter(Bridge::tier)
        ).apply(i, Bridge::new));

        @Override public String typeKey() { return "Bridge"; }

        /** Bridges span water by design. */
        @Override public boolean isWaterCapable() { return true; }

        @Override
        public CenterlineResult computeCenterline(PrimitiveContext ctx) {
            long seed = DriftNoise.localSeed(ctx.worldSeed(), from, to);
            // Drift amplitude 1.5: keeps the bridge straight enough to read
            // as a deliberate structure, with just enough wobble that the
            // deck endpoints don't hit obviously-gridded coordinates.
            // Bridges intentionally span water and Y discontinuities at the
            // shoreline; skip terrain checks so the deck reaches the far
            // bank rather than truncating at the first water cell.
            List<BlockPos> line = driftedLine(ctx.level(), from, to, 1.5, seed);
            return CenterlineResult.complete(line);
        }
    }

    // =========================================================================
    // Stairway  (Phase 13.1)
    // =========================================================================

    /**
     * A road primitive that climbs or descends a slope via stair-step
     * blocks. Used where regular surface paint would produce a bumpy,
     * unwalkable path — HILLTOP's switchback main road and TERRACED's
     * ramps are the canonical use cases.
     *
     * <h3>Centerline shape</h3>
     * The centerline is sampled along the chord from {@code from} to
     * {@code to}. Y interpolates LINEARLY from {@code from.getY()} to
     * {@code to.getY()} — the centerline does <em>not</em> surface-snap.
     * This is the entire point: a stairway intentionally deviates from
     * the natural heightmap so the realiser can place stair blocks at
     * regular intervals.
     *
     * <p>The realiser ({@code EdgeRealizer}) recognises Stairway primitives
     * and dispatches to {@code StairwayPlacer}, which fills support
     * underneath each stair when natural ground is lower and clears
     * headroom above when natural ground is higher.
     *
     * <p>Drift amplitude is small (1.0): stairway paths read better as
     * straight than as wandering. Use a chain of multiple Stairway
     * primitives at different angles for switchback effects, not drift.
     *
     * @param from stairway base (lower Y)
     * @param to   stairway top (higher Y)
     * @param tier road tier; governs stair material and width via the realiser
     */
    record Stairway(
            BlockPos from,
            BlockPos to,
            RoadShape.RoadTier tier
    ) implements RoadPrimitive {

        static final MapCodec<Stairway> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BlockPos.CODEC.fieldOf("from").forGetter(Stairway::from),
                BlockPos.CODEC.fieldOf("to").forGetter(Stairway::to),
                Codec.STRING.xmap(RoadShape.RoadTier::valueOf, RoadShape.RoadTier::name)
                        .fieldOf("tier").forGetter(Stairway::tier)
        ).apply(i, Stairway::new));

        @Override public String typeKey() { return "Stairway"; }

        @Override
        public CenterlineResult computeCenterline(PrimitiveContext ctx) {
            int dx = to.getX() - from.getX();
            int dz = to.getZ() - from.getZ();
            int dy = to.getY() - from.getY();
            double chord = Math.sqrt(dx * dx + dz * dz);

            if (chord < 1) {
                return CenterlineResult.complete(List.of(from));
            }

            int steps = Math.max(2, (int) Math.ceil(chord));
            List<BlockPos> line = new ArrayList<>(steps + 1);

            long seed = DriftNoise.localSeed(ctx.worldSeed(), from, to);
            double perpX = -dz / chord;
            double perpZ =  dx / chord;
            double driftAmp = 1.0;
            double ampScale = Math.min(1.0, chord / 64.0);

            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                double bx = from.getX() + dx * t;
                double bz = from.getZ() + dz * t;
                // Y interpolates linearly — DO NOT surface-snap. The deviation
                // from natural heightmap is the whole point of a stairway.
                int y = (int) Math.round(from.getY() + dy * t);

                double drift = DriftNoise.sample(t, seed) * driftAmp * ampScale;
                int x = (int) Math.round(bx + perpX * drift);
                int z = (int) Math.round(bz + perpZ * drift);

                line.add(new BlockPos(x, y, z));
            }
            // Stairways intentionally produce Y deltas larger than
            // maxStepDeltaY between consecutive points — that's what
            // makes them stairways. Skip terrain checks.
            return CenterlineResult.complete(dedupe(line));
        }
    }

    // =========================================================================
    // Shared helpers — line walking, surface snap, dedupe
    // =========================================================================

    /**
     * Walks a straight line from {@code from} to {@code to} in 1-block
     * steps along the dominant axis, applying a drift offset perpendicular
     * to the heading at each step. Used by StraightRoad and Spur.
     */
    static List<BlockPos> driftedLine(ServerLevel level,
                                      BlockPos from, BlockPos to,
                                      double driftAmplitude,
                                      long localSeed) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        double chordLen = Math.sqrt(dx * dx + dz * dz);
        if (chordLen < 1) {
            List<BlockPos> out = new ArrayList<>();
            out.add(surfaceAt(level, from.getX(), from.getZ()));
            return out;
        }

        double perpX = -dz / chordLen;
        double perpZ = dx / chordLen;
        double ampScale = Math.min(1.0, chordLen / 64.0);

        int steps = Math.max(2, (int) Math.ceil(chordLen));
        List<BlockPos> line = new ArrayList<>(steps + 1);

        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double baseX = from.getX() + dx * t;
            double baseZ = from.getZ() + dz * t;
            double drift = DriftNoise.sample(t, localSeed) * driftAmplitude * ampScale;
            int x = (int) Math.round(baseX + perpX * drift);
            int z = (int) Math.round(baseZ + perpZ * drift);
            line.add(surfaceAt(level, x, z));
        }
        return dedupe(line);
    }

    static BlockPos surfaceAt(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    /**
     * Walks an already-computed centerline and truncates it at the first
     * point that fails a terrain check. Water and Y-discontinuity checks
     * are applied; the {@code refusedAt} of any non-COMPLETED result is
     * the rejected point itself (i.e. the first point not appended).
     *
     * <p>Used by primitives that produce surface-snapped centerlines and
     * want the standard truncation behaviour. Bridge/Stairway-style
     * primitives bypass this entirely and return
     * {@link CenterlineResult#complete} unconditionally.
     */
    static CenterlineResult applyTerrainChecks(List<BlockPos> points,
                                               PrimitiveContext ctx,
                                               boolean waterCapable) {
        if (points.isEmpty()) return CenterlineResult.complete(points);

        List<BlockPos> accepted = new ArrayList<>(points.size());
        BlockPos prev = null;
        int maxDy = ctx.maxStepDeltaY();
        for (BlockPos p : points) {
            if (!waterCapable && ctx.features().isOnWater(p)) {
                return new CenterlineResult(accepted,
                        TerminationReason.WATER_CROSSING, p);
            }
            if (prev != null) {
                int dy = p.getY() - prev.getY();
                if (dy < -maxDy) {
                    return new CenterlineResult(accepted,
                            TerminationReason.CLIFF_DROP, p);
                }
                if (dy > maxDy) {
                    return new CenterlineResult(accepted,
                            TerminationReason.CLIFF_RISE, p);
                }
            }
            accepted.add(p);
            prev = p;
        }
        return CenterlineResult.complete(accepted);
    }

    /** Removes consecutive XZ duplicates. Y differences are ignored. */
    static List<BlockPos> dedupe(List<BlockPos> line) {
        if (line.size() < 2) return line;
        List<BlockPos> out = new ArrayList<>(line.size());
        BlockPos prev = null;
        for (BlockPos p : line) {
            if (prev == null || p.getX() != prev.getX() || p.getZ() != prev.getZ()) {
                out.add(p);
                prev = p;
            }
        }
        return out;
    }

    /**
     * Generates a surface-snapped drift-noise centerline for trade road
     * realisation. Replaces A* diagonals with organic curves — the same
     * DriftNoise used by {@link StraightRoad}, seeded from the world seed
     * and the endpoint positions for determinism.
     *
     * @param driftAmplitude max perpendicular wander in blocks (6 = visible)
     */
    public static List<BlockPos> tradeCenterline(ServerLevel level,
                                                 BlockPos from, BlockPos to,
                                                 double driftAmplitude,
                                                 long worldSeed) {
        long seed = DriftNoise.localSeed(worldSeed, from, to);
        return driftedLine(level, from, to, driftAmplitude, seed);
    }

    // =========================================================================
    // Drift noise — deterministic 1D value noise
    // =========================================================================

    /**
     * Deterministic smoothed noise used to wander road centerlines.
     * Sampled along a 0..1 parameter (the road's arc position) and
     * returns a value in [-1, 1]. Two samples at similar t return
     * similar values, which is what makes the wander smooth rather
     * than jittery.
     */
    final class DriftNoise {
        private DriftNoise() {}

        /** Number of control points along the road's parameter. */
        private static final int CONTROL_POINTS = 8;

        static long localSeed(long worldSeed, BlockPos a, BlockPos b) {
            long h = worldSeed;
            h = h * 6364136223846793005L + a.getX() * 2862933555777941757L;
            h = h * 6364136223846793005L + a.getZ() * 2862933555777941757L;
            h = h * 6364136223846793005L + b.getX() * 2862933555777941757L;
            h = h * 6364136223846793005L + b.getZ() * 2862933555777941757L;
            return h ^ (h >>> 33);
        }

        /**
         * Samples smoothed noise at {@code t} in [0, 1]. Value at t=0
         * and t=1 is always 0 so road endpoints meet their targets exactly.
         */
        static double sample(double t, long localSeed) {
            if (t <= 0 || t >= 1) return 0;
            double scaled = t * (CONTROL_POINTS - 1);
            int idx = (int) Math.floor(scaled);
            double frac = scaled - idx;
            double a = hashToUnit(localSeed, idx);
            double b = hashToUnit(localSeed, idx + 1);
            // Smoothstep interpolation
            double s = frac * frac * (3 - 2 * frac);
            double value = a * (1 - s) + b * s;
            // Clamp endpoints to 0 for clean meeting
            double fadeIn = Math.min(1.0, t * 4);
            double fadeOut = Math.min(1.0, (1 - t) * 4);
            return value * fadeIn * fadeOut;
        }

        private static double hashToUnit(long seed, int i) {
            long h = seed ^ ((long) i * 0x9E3779B97F4A7C15L);
            h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
            h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
            h = h ^ (h >>> 31);
            // Map to [-1, 1]
            return ((h & 0xFFFFL) / 32767.5) - 1.0;
        }
    }
}
