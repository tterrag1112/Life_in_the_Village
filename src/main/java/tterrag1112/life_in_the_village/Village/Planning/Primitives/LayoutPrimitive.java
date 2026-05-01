package tterrag1112.life_in_the_village.Village.Planning.Primitives;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.LayoutSlot;
import tterrag1112.life_in_the_village.Village.Planning.StructureSizeCache;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.List;

/**
 * Places buildings onto roads. Layout primitives read cached centerlines
 * out of {@link PlanContext#layout} and call {@link PlanContext#tryCommitBuilding}
 * to commit slots. They do NOT paint road blocks — that's rendering.
 */
public sealed interface LayoutPrimitive
        permits LayoutPrimitive.BuildingCircle,
        LayoutPrimitive.LinearRow,
        LayoutPrimitive.RingBand {

    default void emitSlots(PlanContext pctx) {}
    void place(PlanContext pctx);

    // =========================================================================
    // TownSquare — REMOVED in Phase 18 doc 04. The polygon-based plaza
    // model (PlazaGenerator + PlazaPaver + RecipeHelpers.installPlaza)
    // now handles every responsibility this primitive previously had:
    //   - DECORATION reserve slot   → emitted by PlazaGenerator at the
    //                                 polygon's bounding circle.
    //   - townSquarePos / Radius    → set by PlazaGenerator from the
    //                                 polygon centroid + sqrt(area/π).
    //   - civicRingRadius           → set by PlazaGenerator as
    //                                 polygonRadius + civic outset.
    //   - PRIME_CIVIC / SECONDARY_CIVIC slot emission → PlazaGenerator
    //                                 emits along polygon edges.
    //   - Ring road                 → DELETED. Was purely decorative;
    //                                 prompt-15 audit confirmed no
    //                                 NPC / trade / pathing dependency.
    // =========================================================================
    // BuildingCircle
    // =========================================================================

    /**
     * Places buildings around a focal point with size derived from the
     * buildings' own front-face lengths.
     *
     * <p><b>TIGHT mode:</b> buildings are tangent to a small focal circle.
     * Focal radius = {@code sum(frontFace) / (2π)}, so the buildings form
     * a closed ring touching each other. Use for dense clusters.
     *
     * <p><b>LOOSE mode:</b> focal radius is doubled, leaving gaps between
     * buildings for gardens, paths, and open ground. Use for outer
     * clusters on long spurs where breathing room matters.
     */
    record BuildingCircle(
            BlockPos focus,
            Mode mode,
            List<VillageTypeData.StarterBuilding> buildings,
            List<BlockPos> feedingRoad
    ) implements LayoutPrimitive {

        public enum Mode { TIGHT, LOOSE, SCATTER }

        @Override
        public void place(PlanContext pctx) {
            if (buildings.isEmpty()) return;

            if (mode == Mode.SCATTER) {
                placeScatter(pctx);
                return;
            }

            // Compute sum of front-face lengths (post-rotation width from cache)
            int totalFrontFace = 0;
            int maxFrontFace = 0;
            for (VillageTypeData.StarterBuilding sb : buildings) {
                StructureSizeCache.FootprintInfo info =
                        pctx.sizes.get(sb.structure(), net.minecraft.world.level.block.Rotation.NONE);
                int ff = info != null ? info.width() : 12;
                totalFrontFace += ff + 3;  // include gap
                if (ff > maxFrontFace) maxFrontFace = ff;
            }

            // Base radius is circumference-derived. For TIGHT mode we want
            // buildings just separated enough; for LOOSE we want real gaps.
            double baseRadius = totalFrontFace / (2 * Math.PI);
            double focalRadius = mode == Mode.TIGHT ? baseRadius : baseRadius * 1.8;

            int count = buildings.size();
            int maxDiagonal = maxFrontFace + 10;
            double angleStepForFloor = 2 * Math.PI / Math.max(2, count);
            double requiredRadius = (maxDiagonal + 10)
                    / (2 * Math.sin(angleStepForFloor / 2));
            if (mode == Mode.LOOSE) requiredRadius *= 1.5;
            // Hard floor to clear the feeding spur's reserved road footprint
            requiredRadius = Math.max(requiredRadius, 12);
            focalRadius = Math.max(focalRadius, requiredRadius);

            BlockPos focalCentre = pctx.solidSurface(focus);

            double angleStep = 2 * Math.PI / count;
            // Start angle faces away from the feeding road so the first
            // building sits on the "far side" of the circle from the spur
            double startAngle = 0;
            if (feedingRoad != null && !feedingRoad.isEmpty()) {
                BlockPos roadEnd = feedingRoad.get(feedingRoad.size() - 1);
                double dx = focalCentre.getX() - roadEnd.getX();
                double dz = focalCentre.getZ() - roadEnd.getZ();
                startAngle = Math.atan2(dz, dx);
            }

            for (int i = 0; i < count; i++) {
                VillageTypeData.StarterBuilding sb = buildings.get(i);
                BuildingType bt = PlanContext.parseType(sb);
                if (bt == null) continue;

                LayoutSlot slot = null;
                // Try the preferred angle, then nudge angularly if it collides
                double[] angleOffsets = {0, 0.25, -0.25, 0.5, -0.5, 0.75, -0.75, 1.0, -1.0};
                for (double offset : angleOffsets) {
                    double angle = startAngle + (i + offset) * angleStep;
                    BlockPos target = new BlockPos(
                            focalCentre.getX() + (int) Math.round(Math.cos(angle) * focalRadius),
                            focalCentre.getY(),
                            focalCentre.getZ() + (int) Math.round(Math.sin(angle) * focalRadius));

                    // Tangent "road" pair for retry shifts
                    double tangent = angle + Math.PI / 2;
                    BlockPos t1 = target.offset(
                            (int) (Math.cos(tangent) * 6), 0,
                            (int) (Math.sin(tangent) * 6));
                    BlockPos t2 = target.offset(
                            (int) (-Math.cos(tangent) * 6), 0,
                            (int) (-Math.sin(tangent) * 6));
                    List<BlockPos> facing = List.of(t1, t2);

                    slot = pctx.tryCommitWithRetries(target, sb, bt, facing, 6);
                    if (slot != null) {
                        slot.setRotation(PlanContext.chooseFacing(slot.getPos(), focalCentre));
                        break;
                    }
                }
                if (slot == null) {
                    System.out.println("BuildingCircle: dropped " + bt + " at angle "
                            + (startAngle + i * angleStep));
                }
            }}
            /**
             * Tags a caller wants stamped on the emitted slots. If null,
             * defaults to {@code PRODUCTION_CLUSTER} — the commonest use.
             * Can be overridden via {@link #withClusterTags}.
             */
            private static final java.util.Set<tterrag1112.life_in_the_village
                    .Village.Planning.Zoning.SlotTag> DEFAULT_CLUSTER_TAGS =
                    java.util.EnumSet.of(
                            tterrag1112.life_in_the_village.Village.Planning
                                    .Zoning.SlotTag.PRODUCTION_CLUSTER,
                            tterrag1112.life_in_the_village.Village.Planning
                                    .Zoning.SlotTag.ROAD_ADJACENT);

            @Override
            public void emitSlots(PlanContext pctx) {
                // Recipes that want different tagging can call the static
                // helper below. For now every BuildingCircle defaults to
                // PRODUCTION_CLUSTER — override by calling emitSlotsWithTags
                // from the recipe.
                emitSlotsWithTags(pctx, DEFAULT_CLUSTER_TAGS, 60);
            }

            /**
             * Emit variant that lets a recipe stamp any tag set (e.g.
             * {@code RESIDENTIAL_CORE} for a housing circle,
             * {@code CIVIC_ADJACENT} for a chapel cluster). Caller also
             * picks the base quality score.
             */
            public void emitSlotsWithTags(PlanContext pctx,
                    java.util.Set<tterrag1112.life_in_the_village.Village
                            .Planning.Zoning.SlotTag> tags,
            int baseQuality) {
                int count = Math.max(buildings.size(), 4); // always offer 4 even if remaining is small
                BlockPos focalCentre = pctx.solidSurface(focus);

                int maxFrontFace = 0;
                for (VillageTypeData.StarterBuilding sb : buildings) {
                    StructureSizeCache.FootprintInfo info =
                            pctx.sizes.get(sb.structure(),
                                    net.minecraft.world.level.block.Rotation.NONE);
                    int ff = info != null ? info.width() : 12;
                    if (ff > maxFrontFace) maxFrontFace = ff;
                }
                if (maxFrontFace == 0) maxFrontFace = 16;

                // Reasonable radius: matches the TIGHT/LOOSE derivation
                int totalFF = 0;
                for (int i = 0; i < count; i++) totalFF += maxFrontFace + 3;
                double baseRadius = totalFF / (2 * Math.PI);
                double focalRadius = mode == Mode.TIGHT ? baseRadius
                        : mode == Mode.SCATTER ? baseRadius * 1.4
                        : baseRadius * 1.8;
                focalRadius = Math.max(focalRadius, 12);

                double startAngle = 0;
                if (feedingRoad != null && !feedingRoad.isEmpty()) {
                    BlockPos roadEnd = feedingRoad.get(feedingRoad.size() - 1);
                    double dx = focalCentre.getX() - roadEnd.getX();
                    double dz = focalCentre.getZ() - roadEnd.getZ();
                    startAngle = Math.atan2(dz, dx);
                }
                double angleStep = 2 * Math.PI / count;

                for (int i = 0; i < count; i++) {
                    double angle = startAngle + i * angleStep;
                    BlockPos target = new BlockPos(
                            focalCentre.getX() + (int) Math.round(Math.cos(angle) * focalRadius),
                            focalCentre.getY(),
                            focalCentre.getZ() + (int) Math.round(Math.sin(angle) * focalRadius));

                    pctx.offerSlot(new tterrag1112.life_in_the_village.Village.Planning
                            .Zoning.PlacementSlot(
                            target, feedingRoad,
                            tags, maxFrontFace + 4, baseQuality - i));
                }

        }
        /**
         * SCATTER: place buildings at random radii within a band on the
         * hemisphere opposite the feeding road. Produces an organic clump
         * rather than a geometric ring. The "back side" bias keeps the
         * spur's road end visually clear and makes the cluster feel like
         * the spur is arriving at a settlement rather than threading
         * through one.
         */
        private void placeScatter(PlanContext pctx) {
            BlockPos focalCentre = pctx.solidSurface(focus);

            int maxFrontFace = 0;
            for (VillageTypeData.StarterBuilding sb : buildings) {
                StructureSizeCache.FootprintInfo info =
                        pctx.sizes.get(sb.structure(),
                                net.minecraft.world.level.block.Rotation.NONE);
                int ff = info != null ? info.width() : 12;
                if (ff > maxFrontFace) maxFrontFace = ff;
            }

            // Band: inner edge clears the focal point + half a building,
            // outer edge gives enough room for `count` buildings to spread.
            int innerR = Math.max(8, maxFrontFace / 2 + 4);
            int outerR = innerR + Math.max(12, maxFrontFace + buildings.size() * 2);

            // "Back" angle = direction from feeding road end toward focal centre
            double backAngle = 0;
            if (feedingRoad != null && !feedingRoad.isEmpty()) {
                BlockPos roadEnd = feedingRoad.get(feedingRoad.size() - 1);
                double dx = focalCentre.getX() - roadEnd.getX();
                double dz = focalCentre.getZ() - roadEnd.getZ();
                backAngle = Math.atan2(dz, dx);
            }
            // Hemisphere: backAngle ± π/2, so a 180° arc on the far side
            double arcStart = backAngle - Math.PI / 2;
            double arcSpan  = Math.PI;

            for (VillageTypeData.StarterBuilding sb : buildings) {
                BuildingType bt = PlanContext.parseType(sb);
                if (bt == null) continue;

                LayoutSlot slot = null;
                // Up to 8 randomized attempts per building
                for (int attempt = 0; attempt < 8; attempt++) {
                    double angle = arcStart + pctx.rng.nextDouble() * arcSpan;
                    double r = innerR + pctx.rng.nextDouble() * (outerR - innerR);
                    BlockPos target = new BlockPos(
                            focalCentre.getX() + (int) Math.round(Math.cos(angle) * r),
                            focalCentre.getY(),
                            focalCentre.getZ() + (int) Math.round(Math.sin(angle) * r));

                    // Tangent fake-road for retry shifts (perpendicular to radial)
                    double tangent = angle + Math.PI / 2;
                    BlockPos t1 = target.offset(
                            (int)(Math.cos(tangent) * 6), 0,
                            (int)(Math.sin(tangent) * 6));
                    BlockPos t2 = target.offset(
                            (int)(-Math.cos(tangent) * 6), 0,
                            (int)(-Math.sin(tangent) * 6));
                    List<BlockPos> facing = List.of(t1, t2);

                    slot = pctx.tryCommitWithRetries(target, sb, bt, facing, 6);
                    if (slot != null) {
                        slot.setRotation(PlanContext.chooseFacing(slot.getPos(), focalCentre));
                        break;
                    }
                }
                if (slot == null) {
                    System.out.println("BuildingCircle[SCATTER]: dropped " + bt);
                }
            }
        }
    }

    // =========================================================================
    // LinearRow
    // =========================================================================

    /**
     * Places buildings in a straight row along a heading, using each
     * placed building's actual footprint width to determine where the
     * next one starts. Buildings face perpendicular to the heading
     * (toward {@code facingSide}).
     *
     * <p>Used by TERRACED for terrace rows where buildings need to
     * line up along a contour, and by ENCLAVE for wall-segment
     * placements where buildings sit shoulder-to-shoulder.
     *
     * <p>Unlike {@link BuildingCircle}, this primitive does NOT use
     * SCATTER-style randomization — positions are deterministic,
     * computed from cumulative footprint width. If a building fails
     * to place, the cursor advances by an estimated width and the
     * next building tries from the new position.
     *
     * @param start starting position of the row
     * @param headingRad direction of the row in radians
     * @param facingSide +1 for buildings facing left of heading,
     *                   -1 for right
     * @param spacing extra blocks between adjacent buildings
     */
    record LinearRow(
            BlockPos start,
            double headingRad,
            int facingSide,
            int spacing,
            List<VillageTypeData.StarterBuilding> buildings,
            List<BlockPos> facingRoad
    ) implements LayoutPrimitive {

        @Override
        public void place(PlanContext pctx) {
            if (buildings.isEmpty()) return;

            double headX = Math.cos(headingRad);
            double headZ = Math.sin(headingRad);
            // Perpendicular vector for facing-side offset (small back-step
            // so the building sits "behind" the row line, facing forward)
            double sideX = -headZ * facingSide;
            double sideZ = headX * facingSide;

            // Synthetic facing road: a 2-point line just in front of
            // the row, so chooseFacing makes buildings face outward.
            double cumulativeDist = 0;
            for (int i = 0; i < buildings.size(); i++) {
                VillageTypeData.StarterBuilding sb = buildings.get(i);
                BuildingType bt = PlanContext.parseType(sb);
                if (bt == null) continue;

                StructureSizeCache.FootprintInfo info = pctx.sizes.get(
                        sb.structure(), net.minecraft.world.level.block.Rotation.NONE);
                int w = info != null ? info.width() : 12;
                int halfW = w / 2;

                // Advance the cursor by half this building's width before placing
                cumulativeDist += halfW;
                BlockPos target = new BlockPos(
                        start.getX() + (int) Math.round(headX * cumulativeDist),
                        start.getY(),
                        start.getZ() + (int) Math.round(headZ * cumulativeDist));

                // Build a synthetic facing road in front of the building
                BlockPos facingA = target.offset(
                        (int) Math.round(sideX * 6 + headX * 4), 0,
                        (int) Math.round(sideZ * 6 + headZ * 4));
                BlockPos facingB = target.offset(
                        (int) Math.round(sideX * 6 - headX * 4), 0,
                        (int) Math.round(sideZ * 6 - headZ * 4));
                List<BlockPos> facing = facingRoad != null && !facingRoad.isEmpty()
                        ? facingRoad : List.of(facingA, facingB);

                LayoutSlot slot = pctx.tryCommitWithRetries(target, sb, bt, facing, 6);
                if (slot != null) {
                    slot.setRotation(PlanContext.chooseFacing(slot.getPos(),
                            target.offset((int) Math.round(sideX * 8), 0,
                                    (int) Math.round(sideZ * 8))));
                }
                // Advance the cursor by the other half plus spacing,
                // regardless of whether the placement succeeded
                cumulativeDist += halfW + spacing;
            }
        }
        @Override
        public void emitSlots(PlanContext pctx) {
            double headX = Math.cos(headingRad);
            double headZ = Math.sin(headingRad);
            int count = Math.max(buildings.size(), 3);
            int step = 14; // approx building spacing

            java.util.Set<tterrag1112.life_in_the_village.Village.Planning
                    .Zoning.SlotTag> tags = java.util.EnumSet.of(
                    tterrag1112.life_in_the_village.Village.Planning
                            .Zoning.SlotTag.ROAD_ADJACENT,
                    tterrag1112.life_in_the_village.Village.Planning
                            .Zoning.SlotTag.RESIDENTIAL_INFILL);

            for (int i = 0; i < count; i++) {
                int dist = (i + 1) * step;
                BlockPos target = new BlockPos(
                        start.getX() + (int) Math.round(headX * dist),
                        start.getY(),
                        start.getZ() + (int) Math.round(headZ * dist));
                pctx.offerSlot(new tterrag1112.life_in_the_village.Village.Planning
                        .Zoning.PlacementSlot(
                        target,
                        facingRoad != null ? facingRoad : java.util.List.of(start),
                        tags, 16, 40 - i));
            }
        }
    }

    // =========================================================================
    // RingBand
    // =========================================================================

    /**
     * Distributes buildings within an annulus around {@code centre},
     * snapping each one toward the nearest road centerline so every
     * building has road access. Used for agricultural and residential
     * outer rings where exact positioning matters less than the overall
     * distribution.
     */
    record RingBand(
            BlockPos centre,
            int innerRadius,
            int outerRadius,
            BuildingZone zone,
            List<VillageTypeData.StarterBuilding> buildings,
            List<List<BlockPos>> snapRoads
    ) implements LayoutPrimitive {

        @Override
        public void place(PlanContext pctx) {
            if (buildings.isEmpty()) return;

            int count = buildings.size();
            double angleStep = 2 * Math.PI / count;

            for (int i = 0; i < count; i++) {
                VillageTypeData.StarterBuilding sb = buildings.get(i);
                BuildingType bt = PlanContext.parseType(sb);
                if (bt == null) continue;

                LayoutSlot slot = null;
                double[] angleOffsets = {0, 0.3, -0.3, 0.6, -0.6, 1.0, -1.0, 1.5, -1.5};
                double[] radiusFactors = {0.5, 0.7, 0.3, 0.9, 0.1};

                outer:
                for (double rf : radiusFactors) {
                    int r = innerRadius
                            + (int) ((outerRadius - innerRadius) * rf);
                    for (double offset : angleOffsets) {
                        double angle = (i + offset) * angleStep;
                        BlockPos target = new BlockPos(
                                centre.getX() + (int) Math.round(Math.cos(angle) * r),
                                centre.getY(),
                                centre.getZ() + (int) Math.round(Math.sin(angle) * r));

                        List<BlockPos> nearestRoad = null;
                        double bestDistSq = Double.MAX_VALUE;
                        if (snapRoads != null) {
                            for (List<BlockPos> road : snapRoads) {
                                if (road.isEmpty()) continue;
                                BlockPos p = PlanContext.nearestOn(road, target);
                                double d = p.distSqr(target);
                                if (d < bestDistSq) {
                                    bestDistSq = d;
                                    nearestRoad = road;
                                }
                            }
                        }

                        slot = pctx.tryCommitWithRetries(target, sb, bt, nearestRoad, 12);
                        if (slot != null) break outer;
                    }
                }
                if (slot == null) {
                    System.out.println("RingBand[" + zone + "]: dropped " + bt);
                }
            }

            // Add the angleStep declaration you'll also need:
        }

        @Override
        public void emitSlots(PlanContext pctx) {
            java.util.Set<tterrag1112.life_in_the_village.Village.Planning
                    .Zoning.SlotTag> tags = switch (zone) {
                case AGRICULTURAL -> java.util.EnumSet.of(
                        tterrag1112.life_in_the_village.Village.Planning
                                .Zoning.SlotTag.FIELD_EDGE,
                        tterrag1112.life_in_the_village.Village.Planning
                                .Zoning.SlotTag.ROAD_ADJACENT);
                case DEFENSIVE -> java.util.EnumSet.of(
                        tterrag1112.life_in_the_village.Village.Planning
                                .Zoning.SlotTag.WALL_ADJACENT,
                        tterrag1112.life_in_the_village.Village.Planning
                                .Zoning.SlotTag.ROAD_ADJACENT);
                case RESIDENTIAL -> java.util.EnumSet.of(
                        tterrag1112.life_in_the_village.Village.Planning
                                .Zoning.SlotTag.RESIDENTIAL_OUTER,
                        tterrag1112.life_in_the_village.Village.Planning
                                .Zoning.SlotTag.BACKFILL,
                        tterrag1112.life_in_the_village.Village.Planning
                                .Zoning.SlotTag.ROAD_ADJACENT);
                default -> java.util.EnumSet.of(
                        tterrag1112.life_in_the_village.Village.Planning
                                .Zoning.SlotTag.BACKFILL,
                        tterrag1112.life_in_the_village.Village.Planning
                                .Zoning.SlotTag.ROAD_ADJACENT);
            };

            // SLOT_FOOTPRINT chosen so the largest WALL_ADJACENT (guard tower)
            // and FIELD_EDGE (farmhouse) buildings fit. SAFE_OFFSET clears
            // the road's reserved corridor (reservedHalfWidth=3 for every
            // tier) plus the slot's own footprint half plus MIN_BUILDING_GAP.
            // Validator's road-distance threshold is roadHalfWidth(3) +
            // halfFp(9) + slack(6) = 18, so SAFE_OFFSET=16 sits inside the
            // 16–18 window where slots both clear the road footprint and
            // satisfy the validator.
            final int SLOT_FOOTPRINT = 18;
            final int SAFE_OFFSET = 16;

            // Shared outer-ring path (Phase 18+ fix): when the recipe has
            // staged a single perimeter road for both DEFENSIVE and
            // AGRICULTURAL bands, both bands attach to it — DEFENSIVE on the
            // inside, AGRICULTURAL on the outside. The ring road itself is
            // ALREADY in the road graph; this band only emits slots and
            // points them at the shared edge id / centerline.
            int outerRingEdgeId = pctx.outerRingEdgeId();
            List<BlockPos> outerRingCenterline = pctx.outerRingCenterline();
            int outerRingRadius = pctx.outerRingRadius();
            boolean useSharedRing = outerRingEdgeId >= 0
                    && !outerRingCenterline.isEmpty()
                    && outerRingRadius > 0
                    && (zone == BuildingZone.DEFENSIVE
                            || zone == BuildingZone.AGRICULTURAL);

            // Slot radius differs by code path:
            //   shared-ring path: ringR ± SAFE_OFFSET (DEFENSIVE inside, AGRI outside)
            //   fallback path:    outerRadius + SAFE_OFFSET (legacy band-derived)
            int slotRadius = useSharedRing
                    ? (zone == BuildingZone.DEFENSIVE
                            ? outerRingRadius - SAFE_OFFSET
                            : outerRingRadius + SAFE_OFFSET)
                    : outerRadius + SAFE_OFFSET;

            System.out.println("RingBand[" + zone + "] emitSlots: useShared="
                    + useSharedRing + " ringR=" + outerRingRadius
                    + " slotR=" + slotRadius);

            int count = Math.max(buildings.size(), 8);
            double angleStep = 2 * Math.PI / count;

            for (int i = 0; i < count; i++) {
                double angle = i * angleStep;
                BlockPos target = new BlockPos(
                        centre.getX() + (int) Math.round(Math.cos(angle) * slotRadius),
                        centre.getY(),
                        centre.getZ() + (int) Math.round(Math.sin(angle) * slotRadius));

                List<BlockPos> feedingRoad;
                int feedingEdgeId;
                if (useSharedRing) {
                    feedingRoad   = outerRingCenterline;
                    feedingEdgeId = outerRingEdgeId;
                } else {
                    // Per-target nearest snap (legacy behaviour preserved
                    // for recipes that haven't been migrated to the shared
                    // ring path).
                    feedingRoad   = nearestRoadFor(target);
                    feedingEdgeId = -1;
                }

                pctx.offerSlot(new PlacementSlot(
                        target, feedingRoad, feedingEdgeId, tags,
                        SLOT_FOOTPRINT, SLOT_FOOTPRINT,
                        null, 30 - i, 0));
            }
        }

        /** Per-target nearest-road snap for the fallback emitSlots path. */
        private List<BlockPos> nearestRoadFor(BlockPos target) {
            if (snapRoads == null) return null;
            List<BlockPos> nearest = null;
            double bestDist = Double.MAX_VALUE;
            for (List<BlockPos> road : snapRoads) {
                if (road == null || road.isEmpty()) continue;
                BlockPos p = PlanContext.nearestOn(road, target);
                double d = p.distSqr(target);
                if (d < bestDist) { bestDist = d; nearest = road; }
            }
            return nearest;
        }
    }
}