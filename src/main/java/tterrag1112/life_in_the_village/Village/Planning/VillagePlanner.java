// src/main/java/tterrag1112/life_in_the_village/Village/Planning/VillagePlanner.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.TownSquarePlacer;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.*;

/**
 * Produces a {@link VillageLayout} plan for a village before any
 * blocks are placed. The plan is terrain-aware and density-scaled
 * by village level.
 *
 * <h3>Layout model</h3>
 * <pre>
 *               [farm plots]
 *                    ↑  (bestFlatDir)
 *   [ring 3]  [ring 2]  [ring 1]  [TOWN HALL]  [ring 1]  [ring 2]  [ring 3]
 *                    ↓
 *          [decoration clusters]
 * </pre>
 *
 * <ul>
 *   <li>Town hall is always at the adjusted origin.</li>
 *   <li>Remaining buildings fill ring 1, overflow into ring 2/3.</li>
 *   <li>Farm plots cluster in the flattest cardinal direction.</li>
 *   <li>Path nodes are emitted at regular intervals between buildings,
 *       to be connected by {@link
 *       tterrag1112.life_in_the_village.Village.Decoration.PathRouter}.</li>
 *   <li>Decoration clusters are placed in gaps between rings.</li>
 * </ul>
 */
public class VillagePlanner {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Maximum attempts to find a non-overlapping position per slot. */
    private static final int MAX_ATTEMPTS = 32;

    /** Estimated footprint half-size used when we don't know exact size. */
    private static final int DEFAULT_BUILDING_RADIUS = 8;
    private static final int FARM_PLOT_RADIUS         = 10;
    private static final int DECORATION_RADIUS        = 3;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Produces a complete layout plan.
     *
     * @param level       the server level (for terrain queries)
     * @param origin      coarse origin from the spawn event
     * @param typeData    village type definition (starter buildings, etc.)
     * @param rng         seeded RNG for deterministic results
     * @param villageLevel 1 = fresh hamlet, 10 = large city
     * @return a resolved {@link VillageLayout}, or empty if terrain is
     *         unsuitable
     */
    public static Optional<VillageLayout> plan(
            ServerLevel level,
            BlockPos origin,
            VillageTypeData typeData,
            Random rng,
            int villageLevel) {

        TerrainProfile terrain = TerrainAnalyzer.analyze(level, origin);
        if (!terrain.isSuitable()) {
            System.out.println("VillagePlanner: unsuitable terrain at " + origin
                    + " (score=" + String.format("%.2f", terrain.suitability()) + ")");
            return Optional.empty();
        }

        LayoutDensityProfile density = LayoutDensityProfile.forLevel(villageLevel);
        VillageLayout layout = new VillageLayout(terrain, density);

        // ── Snap origin to nearest flat surface ───────────────────────────────
        BlockPos adjustedOrigin = snapToFlat(terrain, origin);
        layout.setCenter(adjustedOrigin);

        // ── Town hall at the centre ───────────────────────────────────────────
        placeTownHall(layout, level, typeData, adjustedOrigin, rng);

        // ── Town square: placed AWAY from town hall, toward village centroid ──
        // We place the square at a fixed offset from the town hall toward the
        // geometric centre of the planned rings, so it never overlaps the hall.
        // The offset is: half of townHall radius + RADIUS of square + gap.
        int squareClearance = DEFAULT_BUILDING_RADIUS + TownSquarePlacer.RADIUS + 6;
        // Use south as the default approach direction; the town hall faces north
        // so its entrance faces south — the square goes directly in front of it.
        BlockPos squareIdeal  = adjustedOrigin.south(squareClearance);
        BlockPos squarePos    = resolveOnTerrain(level, layout, squareIdeal,
                4, TownSquarePlacer.RADIUS + 2, rng);
        if (squarePos == null) squarePos = surfaceOf(level, squareIdeal);

        // Reserve the square footprint so rings avoid it
        LayoutSlot squareSlot = new LayoutSlot(
                LayoutSlot.SlotType.DECORATION,
                squarePos,
                TownSquarePlacer.RADIUS + 2);
        layout.tryAdd(squareSlot);
        layout.setTownSquarePos(squarePos);

        // ── Ring buildings ────────────────────────────────────────────────────
        placeRingBuildings(layout, level, typeData, adjustedOrigin, density, rng);

        // ── Snap nearby buildings to similar Y ────────────────────────────────
        clusterBuildingYLevels(layout, level);

        // ── Farm plots ────────────────────────────────────────────────────────
        placeFarmPlots(layout, level, terrain, density, rng);

        // ── Path nodes ────────────────────────────────────────────────────────
        placePathNodes(layout, squarePos, density);

        // ── Decoration clusters ───────────────────────────────────────────────
        placeDecorationClusters(layout, level, terrain, density, rng);

        System.out.println("VillagePlanner: " + layout);
        return Optional.of(layout);
    }

    /**
     * For each placed building slot, if the majority of its neighbours
     * (within 2 * ring1Radius) share a narrow Y band, snap this slot's
     * Y to the median of that band. This prevents staircase villages on
     * gently sloping terrain.
     */
    private static void clusterBuildingYLevels(VillageLayout layout,
                                               ServerLevel level) {
        List<LayoutSlot> buildings = layout.buildings();
        int searchRadius = layout.getDensity().getRing2Radius();

        for (LayoutSlot slot : buildings) {
            List<Integer> nearbyY = new ArrayList<>();
            for (LayoutSlot other : buildings) {
                if (other == slot) continue;
                int dx = Math.abs(slot.getPos().getX() - other.getPos().getX());
                int dz = Math.abs(slot.getPos().getZ() - other.getPos().getZ());
                if (Math.max(dx, dz) <= searchRadius) {
                    nearbyY.add(other.getPos().getY());
                }
            }
            if (nearbyY.isEmpty()) continue;

            Collections.sort(nearbyY);
            int medianY = nearbyY.get(nearbyY.size() / 2);
            int slotY   = slot.getPos().getY();
            int diff    = Math.abs(slotY - medianY);

            // Only nudge if the building is within 6 blocks of the median
            // and the actual terrain at that X/Z supports it
            if (diff > 0 && diff <= 6) {
                int actualSurface = TerrainAnalyzer.surfaceY(
                        level, slot.getPos());
                // Don't snap if it would bury or float the building badly
                if (Math.abs(actualSurface - medianY) <= 4) {
                    slot.snapY(medianY);
                }
            }
        }
    }

    // =========================================================================
    // Step implementations
    // =========================================================================

    // ── Town hall ─────────────────────────────────────────────────────────────

    private static void placeTownHall(VillageLayout layout,
                                      ServerLevel level,
                                      VillageTypeData typeData,
                                      BlockPos center,
                                      Random rng) {
        // Find the town-hall starter building in typeData
        VillageTypeData.StarterBuilding townHallDef =
                typeData.getStarterBuildings().stream()
                        .filter(sb -> sb.type().equals(
                                BuildingType.TOWN_HALL.name()))
                        .findFirst()
                        .orElse(null);

        String path = townHallDef != null
                ? townHallDef.structure()
                : "town_hall/level_1";

        LayoutSlot slot = new LayoutSlot(
                center,
                BuildingType.TOWN_HALL,
                path,
                DEFAULT_BUILDING_RADIUS);
        layout.tryAdd(slot);
    }

    // ── Ring placement ────────────────────────────────────────────────────────

    /**
     * Places remaining starter buildings in concentric rings around
     * the town hall. Each ring is a set of evenly-spaced angular slots;
     * the building list is consumed in priority order (from the JSON
     * definition).
     */
    private static void placeRingBuildings(VillageLayout layout,
                                           ServerLevel level,
                                           VillageTypeData typeData,
                                           BlockPos center,
                                           LayoutDensityProfile density,
                                           Random rng) {
        // Collect buildings that are NOT the town hall
        List<VillageTypeData.StarterBuilding> remaining =
                new ArrayList<>(typeData.getStarterBuildings());
        remaining.removeIf(sb ->
                sb.type().equals(BuildingType.TOWN_HALL.name()));

        // Define ring radii
        List<Integer> radii = new ArrayList<>();
        radii.add(density.getRing1Radius());
        if (density.isUseRing2()) radii.add(density.getRing2Radius());
        if (density.isUseRing3()) radii.add(density.getRing3Radius());

        int buildingIdx = 0;

        for (int ring = 0; ring < radii.size()
                && buildingIdx < remaining.size(); ring++) {

            int radius = radii.get(ring);
            // How many slots fit on this ring given the spacing?
            int slots = Math.max(4, (int)(
                    2 * Math.PI * radius
                            / density.getBuildingSpacing()));

            // Randomise starting angle so each village looks different
            double angleOffset = rng.nextDouble() * 2 * Math.PI;

            for (int i = 0; i < slots
                    && buildingIdx < remaining.size(); i++) {
                double angle = angleOffset
                        + (2 * Math.PI * i / slots);

                // Ideal position on the ring
                int idealX = center.getX()
                        + (int)(Math.cos(angle) * radius);
                int idealZ = center.getZ()
                        + (int)(Math.sin(angle) * radius);

                VillageTypeData.StarterBuilding sb =
                        remaining.get(buildingIdx);
                BuildingType btype;
                try {
                    btype = BuildingType.valueOf(sb.type());
                } catch (IllegalArgumentException e) {
                    buildingIdx++;
                    continue;
                }

                // Try to find a non-overlapping, terrain-friendly pos
                BlockPos placed = resolveOnTerrain(
                        level, layout,
                        new BlockPos(idealX, center.getY(), idealZ),
                        density.getBuildingJitter(),
                        DEFAULT_BUILDING_RADIUS,
                        rng);

                if (placed != null) {
                    layout.tryAdd(new LayoutSlot(
                            placed, btype, sb.structure(),
                            DEFAULT_BUILDING_RADIUS));
                    buildingIdx++;
                }
                // If we couldn't place, skip this slot (building
                // will try next ring position next iteration)
            }
        }

        if (buildingIdx < remaining.size()) {
            System.out.println("VillagePlanner: could not place "
                    + (remaining.size() - buildingIdx)
                    + " buildings — rings exhausted");
        }
    }

    // ── Farm plots ────────────────────────────────────────────────────────────

    /**
     * Positions farm plots in the flattest cardinal direction, stepping
     * out from center. Multiple plots are tiled side by side.
     *
     * A "farm plot" is a layout hint — the actual farmland blocks
     * are placed by {@link FarmPlotPlacer}.
     */
    private static void placeFarmPlots(VillageLayout layout,
                                       ServerLevel level,
                                       TerrainProfile terrain,
                                       LayoutDensityProfile density,
                                       Random rng) {
        // Number of farm plots scales with density
        int plotCount = Math.max(1, density.getVillageLevel() / 2);

        // Direction vector for the flat cardinal direction
        int[] dir = flatDirVector(terrain.bestFlatDir());

        int farmOffset = density.getFarmOffset();

        // Place plots in a row perpendicular to the flat direction
        int perpX =  dir[1]; // perpendicular in XZ
        int perpZ = -dir[0];

        for (int i = 0; i < plotCount; i++) {
            // Spread plots sideways
            int sideOffset = (i - plotCount / 2)
                    * (FARM_PLOT_RADIUS * 2 + 4);

            int tx = layout.getCenter().getX()
                    + dir[0] * farmOffset
                    + perpX * sideOffset;
            int tz = layout.getCenter().getZ()
                    + dir[1] * farmOffset
                    + perpZ * sideOffset;

            BlockPos idealPos = terrain.bestFlatNear(
                    tx - terrain.origin().getX(),
                    tz - terrain.origin().getZ());

            BlockPos resolved = resolveOnTerrain(
                    level, layout, idealPos,
                    4, FARM_PLOT_RADIUS, rng);

            if (resolved != null) {
                layout.tryAdd(new LayoutSlot(
                        LayoutSlot.SlotType.FARM_PLOT,
                        resolved, FARM_PLOT_RADIUS));
            }
        }
    }

    // ── Path nodes ────────────────────────────────────────────────────────────

    /**
     * Emits PATH_NODE slots connecting each building back toward
     * the village center. These are later connected by PathRouter.
     * Denser villages get more intermediate nodes.
     */
    private static void placePathNodes(VillageLayout layout,
                                       BlockPos center,
                                       LayoutDensityProfile density) {
        List<LayoutSlot> buildings = layout.buildings();

        for (LayoutSlot building : buildings) {
            BlockPos bpos = building.getPos();

            if (bpos.equals(center)) continue; // skip town hall itself

            // Distance from center
            double dist = Math.sqrt(bpos.distSqr(center));
            // Number of intermediate nodes: 1 for sparse, up to 3 for dense
            int nodeCount = Math.max(1,
                    (int)(dist / density.getBuildingSpacing()));
            nodeCount = Math.min(nodeCount, 3);

            for (int n = 1; n <= nodeCount; n++) {
                float t = (float) n / (nodeCount + 1);
                int nx = (int)(center.getX()
                        + (bpos.getX() - center.getX()) * t);
                int nz = (int)(center.getZ()
                        + (bpos.getZ() - center.getZ()) * t);

                BlockPos node = new BlockPos(nx,
                        center.getY(), nz);
                layout.addForced(new LayoutSlot(
                        LayoutSlot.SlotType.PATH_NODE,
                        node, 1));
            }
        }

        // Also add path nodes toward each farm plot
        for (LayoutSlot farm : layout.farmPlots()) {
            BlockPos fpos = farm.getPos();
            BlockPos mid  = new BlockPos(
                    (center.getX() + fpos.getX()) / 2,
                    center.getY(),
                    (center.getZ() + fpos.getZ()) / 2);
            layout.addForced(new LayoutSlot(
                    LayoutSlot.SlotType.PATH_NODE, mid, 1));
        }
    }

    // ── Decoration clusters ───────────────────────────────────────────────────

    /**
     * Positions decoration cluster anchor points in the gaps between
     * buildings, preferring open flat terrain. The actual decoration
     * block placement is handled by {@link
     * tterrag1112.life_in_the_village.Village.Decoration.VillageDecorator}.
     */
    private static void placeDecorationClusters(VillageLayout layout,
                                                ServerLevel level,
                                                TerrainProfile terrain,
                                                LayoutDensityProfile density,
                                                Random rng) {
        int clusters = density.getDecorationClusters();

        for (int i = 0; i < clusters; i++) {
            double angle  = (2 * Math.PI * i / clusters)
                    + rng.nextDouble() * 0.8;
            int gapRadius = (density.getRing1Radius()
                    + density.getRing2Radius()) / 2;

            int dx = (int)(Math.cos(angle) * gapRadius);
            int dz = (int)(Math.sin(angle) * gapRadius);

            BlockPos ideal = terrain.bestFlatNear(dx, dz);
            BlockPos resolved = resolveOnTerrain(
                    level, layout, ideal,
                    6, DECORATION_RADIUS, rng);

            if (resolved != null) {
                layout.addForced(new LayoutSlot(
                        LayoutSlot.SlotType.DECORATION,
                        resolved, DECORATION_RADIUS));
            }
        }
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    /**
     * Tries to find a surface position near {@code ideal} that
     * doesn't overlap any existing layout slot.
     *
     * Strategy:
     * 1. Try the exact ideal position.
     * 2. Try random jitter within ±jitterRange.
     * 3. Spiral outward up to MAX_ATTEMPTS.
     *
     * @return resolved surface BlockPos, or null if all attempts fail.
     */
    private static BlockPos resolveOnTerrain(ServerLevel level,
                                             VillageLayout layout,
                                             BlockPos ideal,
                                             int jitterRange,
                                             int radius,
                                             Random rng) {
        // 1. Exact ideal
        BlockPos candidate = surfaceOf(level, ideal);
        LayoutSlot probe = new LayoutSlot(
                LayoutSlot.SlotType.PATH_NODE, candidate, radius);
        if (noOverlap(layout, probe)) return candidate;

        // 2. Jitter attempts
        for (int a = 0; a < MAX_ATTEMPTS / 2; a++) {
            int jx = rng.nextInt(jitterRange * 2) - jitterRange;
            int jz = rng.nextInt(jitterRange * 2) - jitterRange;
            candidate = surfaceOf(level,
                    ideal.offset(jx, 0, jz));
            probe = new LayoutSlot(
                    LayoutSlot.SlotType.PATH_NODE, candidate, radius);
            if (noOverlap(layout, probe)) return candidate;
        }

        // 3. Spiral outward
        for (int step = 1; step <= MAX_ATTEMPTS / 2; step++) {
            int[][] offsets = {
                    {step, 0}, {-step, 0}, {0, step}, {0, -step},
                    {step, step}, {-step, step},
                    {step, -step}, {-step, -step}
            };
            for (int[] off : offsets) {
                candidate = surfaceOf(level,
                        ideal.offset(off[0] * 6, 0, off[1] * 6));
                probe = new LayoutSlot(
                        LayoutSlot.SlotType.PATH_NODE,
                        candidate, radius);
                if (noOverlap(layout, probe)) return candidate;
            }
        }

        return null; // give up
    }

    /**
     * Returns true if the probe slot doesn't overlap any BUILDING
     * or FARM_PLOT already in the layout (we allow decoration /
     * path nodes to pack more tightly).
     */
    private static boolean noOverlap(VillageLayout layout,
                                     LayoutSlot probe) {
        for (LayoutSlot existing : layout.getAllSlots()) {
            if (existing.getSlotType() == LayoutSlot.SlotType.PATH_NODE
                    || existing.getSlotType()
                    == LayoutSlot.SlotType.DECORATION) {
                continue; // don't gate on these
            }
            if (probe.overlaps(existing)) return false;
        }
        return true;
    }

    /** Returns the surface position (block above the highest solid). */
    private static BlockPos surfaceOf(ServerLevel level,
                                      BlockPos pos) {
        int y = TerrainAnalyzer.surfaceY(level, pos);
        return new BlockPos(pos.getX(), y, pos.getZ());
    }

    /** Snaps the origin to the nearest flat candidate, within 12 blocks. */
    private static BlockPos snapToFlat(TerrainProfile terrain,
                                       BlockPos origin) {
        return terrain.flatCandidates().stream()
                .filter(p -> p.distSqr(origin) < 12 * 12)
                .min(Comparator.comparingDouble(
                        p -> p.distSqr(origin)))
                .orElse(new BlockPos(
                        origin.getX(),
                        terrain.baseY(),
                        origin.getZ()));
    }

    private static int[] flatDirVector(
            TerrainAnalyzer.FlatDirection dir) {
        return switch (dir) {
            case NORTH -> new int[]{ 0, -1};
            case SOUTH -> new int[]{ 0,  1};
            case EAST  -> new int[]{ 1,  0};
            case WEST  -> new int[]{-1,  0};
        };
    }
}