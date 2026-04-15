package tterrag1112.life_in_the_village.Gui.Map.Kingdom;

import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

/**
 * Converts between atlas-cell space and screen pixel space using a
 * strict square grid — cells never deform into rectangles.
 *
 * <p>The projection takes the viewport's grid bounds and available
 * pixel area, then picks the largest integer pixel size that fits
 * the grid in both dimensions. The grid is centred within the
 * available area, leaving equal margin on each side.
 *
 * <p>Single point of change for future zoom/pan: replace {@link #build}
 * with a factory that accepts a zoom level and pan offset. The rest of
 * the renderer calls only {@link #cellToScreenX(int)} etc. and needs
 * no changes.
 */
public final class MapProjection {

    /** Minimum pixel size per cell so very large kingdoms stay visible. */
    public static final int MIN_CELL_PX = 2;
    /** Cap so tiny kingdoms don't render comically huge cells. */
    public static final int MAX_CELL_PX = 24;

    private final int minCellX, minCellZ;   // inclusive
    private final int gridW,    gridH;       // in cells
    private final int cellPx;                // pixels per cell
    private final int originPx, originPy;    // screen pixel of (minCellX, minCellZ)
    private final int drawW,    drawH;

    private MapProjection(int minCellX, int minCellZ,
                          int gridW, int gridH,
                          int cellPx,
                          int originPx, int originPy) {
        this.minCellX = minCellX;
        this.minCellZ = minCellZ;
        this.gridW    = gridW;
        this.gridH    = gridH;
        this.cellPx   = cellPx;
        this.originPx = originPx;
        this.originPy = originPy;
        this.drawW    = gridW * cellPx;
        this.drawH    = gridH * cellPx;
    }

    /**
     * Build a projection fitting {@code [minCellX..maxCellX] × [minCellZ..maxCellZ]}
     * (inclusive on both ends) into the pixel rectangle {@code (panelX, panelY, panelW, panelH)},
     * centred with equal margins.
     */
    public static MapProjection build(int minCellX, int minCellZ,
                                      int maxCellX, int maxCellZ,
                                      int panelX, int panelY,
                                      int panelW, int panelH) {
        int gridW = Math.max(1, maxCellX - minCellX + 1);
        int gridH = Math.max(1, maxCellZ - minCellZ + 1);

        int cellPx = Math.min(panelW / gridW, panelH / gridH);
        cellPx = Math.max(MIN_CELL_PX, Math.min(MAX_CELL_PX, cellPx));

        int drawW = gridW * cellPx;
        int drawH = gridH * cellPx;
        int ox = panelX + (panelW - drawW) / 2;
        int oy = panelY + (panelH - drawH) / 2;

        return new MapProjection(minCellX, minCellZ, gridW, gridH, cellPx, ox, oy);
    }

    // ── Cell → screen (top-left pixel of the cell) ───────────────────────────

    public int cellToScreenX(int cellX) { return originPx + (cellX - minCellX) * cellPx; }
    public int cellToScreenY(int cellZ) { return originPy + (cellZ - minCellZ) * cellPx; }

    // ── World block → screen (floating, for sub-cell route waypoints) ────────

    public float blockToScreenX(double blockX) {
        double cellXF = blockX / AtlasCell.CELL_SIZE;
        return (float) (originPx + (cellXF - minCellX) * cellPx);
    }
    public float blockToScreenY(double blockZ) {
        double cellZF = blockZ / AtlasCell.CELL_SIZE;
        return (float) (originPy + (cellZF - minCellZ) * cellPx);
    }

    // ── Screen → cell (for hover) ────────────────────────────────────────────

    /** Returns {cellX, cellZ} or null if the pixel is outside the grid. */
    public int[] screenToCell(double sx, double sy) {
        if (sx < originPx || sy < originPy) return null;
        int dx = (int) ((sx - originPx) / cellPx);
        int dy = (int) ((sy - originPy) / cellPx);
        if (dx < 0 || dx >= gridW || dy < 0 || dy >= gridH) return null;
        return new int[] { minCellX + dx, minCellZ + dy };
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public int minCellX() { return minCellX; }
    public int minCellZ() { return minCellZ; }
    public int gridW()    { return gridW; }
    public int gridH()    { return gridH; }
    public int cellPx()   { return cellPx; }
    public int originPx() { return originPx; }
    public int originPy() { return originPy; }
    public int drawW()    { return drawW; }
    public int drawH()    { return drawH; }
}