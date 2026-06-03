package tterrag1112.life_in_the_village.Village.Decoration.Plaza;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Civic-plaza decoration complex — turns the bare paved CIVIC {@link
 * PlazaRegion} into a designed central square: a stone-well FOUNTAIN at the
 * centre, GARDEN greenery at the perimeter, and OPEN cobbled paving between
 * (buildings already ring it). Built on the same typed-sub-piece + per-kind
 * renderer pattern as the farm complex ({@code FarmPlot.PlotSubtype} dispatched
 * in {@code PlotInteriorRenderer}): a {@link PlazaPiece} planner here, a {@link
 * PlazaPieceRenderer} dispatch.
 *
 * <p>v1 is deliberately LEAN + OPEN — accent the centre + perimeter, don't fill
 * the square. The planner emits one FOUNTAIN + a few perimeter GARDENs scaled
 * to plaza size; everything else stays OPEN paving. The framework's staged
 * kinds (WELL / MARKET_STALL / NOTICEBOARD / BENCH_CLUSTER) are accepted by the
 * renderer but not emitted yet. No persistence this pass — the plaza is
 * decorated once at spawn (like the code-gen centerpiece it replaces); a
 * reload-rerender record can be added later if wanted.
 */
public final class CivicPlazaComplex {

    private static final Logger LOGGER = LoggerFactory.getLogger(CivicPlazaComplex.class);

    private CivicPlazaComplex() {}

    /** Half-extent (blocks) around the fountain kept clear of gardens — the
     *  central open zone. */
    private static final int FOUNTAIN_CLEAR = 3;
    /** Inset (blocks) of perimeter gardens from the plaza edge. */
    private static final int GARDEN_INSET = 3;
    /** Garden patch radius (blocks) — small so the square stays open. */
    private static final int GARDEN_RADIUS = 2;
    /** Minimum plaza half-extent for any gardens (tiny plazas: fountain only). */
    private static final int MIN_PLAZA_FOR_GARDENS = 8;
    /** Plaza half-extent at/above which mid-edge gardens are added too. */
    private static final int LARGE_PLAZA = 14;

    /**
     * Decorates the village's CIVIC plaza. Runs AFTER plaza paving (so the
     * floor doesn't overwrite the pieces). No-op if there's no CIVIC plaza.
     */
    public static void decorate(ServerLevel level, Village village) {
        PlazaRegion civic = null;
        for (PlazaRegion r : village.getPlazaRegions()) {
            if (r.purpose() == PlazaPurpose.CIVIC) { civic = r; break; }
        }
        if (civic == null) return;

        List<PlazaPiece> pieces = plan(civic);
        Random rng = new Random(village.getId().getMostSignificantBits()
                ^ civic.centroid().asLong());
        int rendered = 0;
        for (PlazaPiece p : pieces) {
            if (PlazaPieceRenderer.render(level, village, civic, p, rng)) rendered++;
        }
        LOGGER.info("CivicPlazaComplex: planned {} piece(s) over CIVIC plaza at {}"
                + " ({} rendered)", pieces.size(), civic.centroid(), rendered);
    }

    /**
     * Assigns pieces over the plaza region: one FOUNTAIN at the centroid, a few
     * perimeter GARDENs scaled to plaza size, OPEN everywhere else (the OPEN
     * remainder isn't emitted as pieces — the paving is already laid). Package-
     * visible for testing.
     */
    static List<PlazaPiece> plan(PlazaRegion region) {
        List<PlazaPiece> pieces = new ArrayList<>();
        BlockPos c = region.centroid();
        int floorY = region.floorY();

        // Centre fountain (NBT, fixed size).
        pieces.add(new PlazaPiece(PlazaPiece.Kind.FOUNTAIN,
                new BlockPos(c.getX(), floorY, c.getZ()), FOUNTAIN_CLEAR));

        Polygon.AABB bb = Polygon.boundingBox(region.footprint());
        int half = Math.min((bb.maxX() - bb.minX()) / 2, (bb.maxZ() - bb.minZ()) / 2);
        if (half < MIN_PLAZA_FOR_GARDENS) return pieces;   // tiny plaza: fountain only

        int gx0 = bb.minX() + GARDEN_INSET, gx1 = bb.maxX() - GARDEN_INSET;
        int gz0 = bb.minZ() + GARDEN_INSET, gz1 = bb.maxZ() - GARDEN_INSET;
        // Four corner gardens (inset from the edge, clear of the centre).
        addGarden(pieces, gx0, floorY, gz0, c);
        addGarden(pieces, gx1, floorY, gz0, c);
        addGarden(pieces, gx0, floorY, gz1, c);
        addGarden(pieces, gx1, floorY, gz1, c);
        // Larger plazas: mid-edge gardens too (more greenery, still open centre).
        if (half >= LARGE_PLAZA) {
            int mx = (bb.minX() + bb.maxX()) / 2, mz = (bb.minZ() + bb.maxZ()) / 2;
            addGarden(pieces, mx, floorY, gz0, c);
            addGarden(pieces, mx, floorY, gz1, c);
            addGarden(pieces, gx0, floorY, mz, c);
            addGarden(pieces, gx1, floorY, mz, c);
        }
        return pieces;
    }

    /** Adds a GARDEN piece, unless it would sit in the central fountain zone. */
    private static void addGarden(List<PlazaPiece> pieces, int x, int floorY, int z,
                                  BlockPos centre) {
        if (Math.abs(x - centre.getX()) <= FOUNTAIN_CLEAR
                && Math.abs(z - centre.getZ()) <= FOUNTAIN_CLEAR) {
            return;
        }
        pieces.add(new PlazaPiece(PlazaPiece.Kind.GARDEN,
                new BlockPos(x, floorY, z), GARDEN_RADIUS));
    }
}
