package tterrag1112.life_in_the_village.Village.Planning.V2.Layer5;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.Footprint;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.Priority;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * V2 Layer 5 — per-building terrain adaptation decisions.
 *
 * <p>Samples the live surface y at every cell under each
 * building's rotated footprint and chooses an
 * {@link AdaptationMode}:
 *
 * <ul>
 *   <li>{@code range ≤ 1} blocks → {@link AdaptationMode#LEVEL},
 *       {@code targetY = median(heights)}</li>
 *   <li>{@code range ≤ 5} → {@link AdaptationMode#PLATFORM},
 *       {@code targetY = max(heights) + 1}</li>
 *   <li>{@code range ≤ largePlatformCap(building)} →
 *       {@link AdaptationMode#PLATFORM} (large),
 *       {@code targetY = percentile_75(heights) + 1}</li>
 *   <li>otherwise → {@link AdaptationMode#DROP}</li>
 * </ul>
 *
 * <h3>Stopgap loosening — Track E1 prompt 3 fix-up 2</h3>
 *
 * The large-platform ceiling was 10 and rejected a substantial
 * fraction of buildings on real terrain (test spawns saw drop
 * ranges of 11, 12, 13, 22, 23, 24, 28, 31). Worse, primary-bound
 * buildings (TOWN_HALL bound to the strategy primary anchor)
 * dropping killed the whole village via
 * {@code missing TOWN_HALL after terrain drops}.
 *
 * <p>Stopgap shape:
 * <ul>
 *   <li>{@link #LARGE_PLATFORM_THRESHOLD} 10 → 16 — catches the
 *       11/12/13 cases; lets PLATFORM mode handle moderately
 *       uneven ground that genuinely deserves a platform.</li>
 *   <li>{@link Priority#CIVIC} / {@link Priority#INFRASTRUCTURE}
 *       get a {@code 1.5×} ceiling ({@code = 24}) — these are the
 *       load-bearing types (TOWN_HALL, MARKET, INN, CHAPEL, WELL,
 *       …); their drop aborts the village.</li>
 * </ul>
 *
 * <p>This is an <strong>explicit stopgap</strong>, not the real
 * terrain-adaptation rework. No new modes (retaining walls,
 * excavation, terracing, cantilever); those land in a separate
 * future prompt. Platforms on steeper ground will look rough for
 * now — the alternative is the village aborting, which is worse.
 *
 * <p>DROP buildings are added to the spawner's drop list with
 * {@code DropReason.TERRAIN_UNADAPTABLE} and skipped at NBT
 * placement time.
 */
public final class TerrainAdapter {

    /** {@code range ≤ this} → flat ground, LEVEL pad. */
    private static final int LEVEL_THRESHOLD = 1;
    /** {@code range ≤ this} → small PLATFORM. */
    private static final int PLATFORM_THRESHOLD = 5;
    /** Track E1 prompt 3 fix-up 2 — base large-platform ceiling.
     *  Raised from the original 10 (see class javadoc). Stopgap
     *  pending real terrain-adaptation rework. */
    private static final int LARGE_PLATFORM_THRESHOLD = 16;
    /** Track E1 prompt 3 fix-up 2 — priority bump for load-
     *  bearing buildings. Multiplier on
     *  {@link #LARGE_PLATFORM_THRESHOLD} for CIVIC /
     *  INFRASTRUCTURE priorities. {@code 1.5×} of 16 = 24,
     *  catching the 22/23/24 TOWN_HALL aborts seen in test
     *  spawns. */
    private static final double LOAD_BEARING_THRESHOLD_MULT = 1.5;

    private TerrainAdapter() {}

    public static List<AdaptationDecision> decide(List<PlacedBuilding> placed,
                                                  ServerLevel level) {
        return decide(placed,
                new tterrag1112.life_in_the_village.Village.Planning.V2.Layer1
                        .LiveTerrainSource(level));
    }

    /**
     * Track E1 — headless overload. Identical logic, height reads via
     * the seam.
     */
    public static List<AdaptationDecision> decide(List<PlacedBuilding> placed,
            tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.TerrainSource source) {
        List<AdaptationDecision> out = new ArrayList<>(placed.size());
        for (PlacedBuilding b : placed) {
            out.add(decideFor(b, source));
        }
        return out;
    }

    private static AdaptationDecision decideFor(PlacedBuilding b,
            tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.TerrainSource source) {
        int[] aabb = footprintAabb(b);
        List<Integer> heights = new ArrayList<>();
        for (int x = aabb[0]; x <= aabb[2]; x++) {
            for (int z = aabb[1]; z <= aabb[3]; z++) {
                int y = source.height(x, z);
                heights.add(y);
            }
        }
        if (heights.isEmpty()) {
            return new AdaptationDecision(b, AdaptationMode.LEVEL, b.centre().getY(),
                    Optional.of("empty footprint"));
        }
        int[] sorted = heights.stream().mapToInt(Integer::intValue).sorted().toArray();
        int min = sorted[0];
        int max = sorted[sorted.length - 1];
        int range = max - min;

        if (range <= LEVEL_THRESHOLD) {
            int median = sorted[sorted.length / 2];
            return new AdaptationDecision(b, AdaptationMode.LEVEL, median,
                    Optional.empty());
        }
        if (range <= PLATFORM_THRESHOLD) {
            return new AdaptationDecision(b, AdaptationMode.PLATFORM, max + 1,
                    Optional.empty());
        }
        int largeCap = largePlatformCap(b);
        if (range <= largeCap) {
            int p75 = sorted[(int) Math.floor(sorted.length * 0.75)];
            return new AdaptationDecision(b, AdaptationMode.PLATFORM, p75 + 1,
                    Optional.of("large platform — range " + range));
        }
        return new AdaptationDecision(b, AdaptationMode.DROP, max,
                Optional.of("terrain range " + range + " > " + largeCap));
    }

    /** Track E1 prompt 3 fix-up 2 — per-priority large-platform
     *  ceiling. CIVIC / INFRASTRUCTURE buildings are load-bearing
     *  (their drop tends to abort the village) and get the
     *  {@link #LOAD_BEARING_THRESHOLD_MULT}× bump. Other
     *  priorities (PRODUCTION, RESIDENTIAL) use the base
     *  {@link #LARGE_PLATFORM_THRESHOLD}. */
    private static int largePlatformCap(PlacedBuilding b) {
        Priority p = b.priority();
        if (p == Priority.CIVIC || p == Priority.INFRASTRUCTURE) {
            return (int) Math.round(LARGE_PLATFORM_THRESHOLD * LOAD_BEARING_THRESHOLD_MULT);
        }
        return LARGE_PLATFORM_THRESHOLD;
    }

    /** Returns {@code [minX, minZ, maxX, maxZ]} for the rotated footprint. */
    private static int[] footprintAabb(PlacedBuilding b) {
        Footprint fp = b.footprint();
        boolean swap = b.rotation() == Rotation.CLOCKWISE_90
                || b.rotation() == Rotation.COUNTERCLOCKWISE_90;
        int rotW = swap ? fp.length() : fp.width();
        int rotL = swap ? fp.width() : fp.length();
        int halfW = rotW / 2;
        int halfL = rotL / 2;
        BlockPos c = b.centre();
        return new int[]{c.getX() - halfW, c.getZ() - halfL,
                c.getX() + halfW, c.getZ() + halfL};
    }

    public enum AdaptationMode { LEVEL, PLATFORM, DROP }

    public record AdaptationDecision(PlacedBuilding building,
                                     AdaptationMode mode,
                                     int targetY,
                                     Optional<String> reason) {}
}
