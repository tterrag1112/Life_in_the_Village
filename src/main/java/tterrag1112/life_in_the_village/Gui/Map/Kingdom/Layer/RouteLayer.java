package tterrag1112.life_in_the_village.Gui.Map.Kingdom.Layer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.KingdomMapData;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.MapProjection;

import java.util.List;

/**
 * Renders trade connections as polylines through their waypoints.
 * Land routes are solid; sea routes are dashed with a distinct colour
 * so they read clearly even when they converge near a dock.
 *
 * <p>One class handles both route lists — pass {@code isSea=true} when
 * constructing for sea routes.
 */
public final class RouteLayer implements MapLayer {

    private final boolean isSea;

    public RouteLayer(boolean isSea) { this.isSea = isSea; }

    @Override public String id()    { return isSea ? "sea_routes" : "land_routes"; }
    @Override public String label() { return isSea ? "Sea Routes" : "Land Routes"; }

    @Override
    public void draw(GuiGraphics g, KingdomMapData data,
                     MapProjection proj, LayerState state,
                     int mouseX, int mouseY) {
        if (!state.isVisible(id())) return;

        List<KingdomMapData.RoutePath> paths = isSea ? data.seaRoutes : data.landRoutes;
        int color = isSea ? BookScreenColors.ROUTE_SEA : BookScreenColors.ROUTE_LAND;

        for (KingdomMapData.RoutePath path : paths) {
            List<BlockPos> wps = path.waypoints();
            if (wps.size() < 2) continue;
            for (int i = 0; i < wps.size() - 1; i++) {
                BlockPos a = wps.get(i), b = wps.get(i + 1);
                float ax = proj.blockToScreenX(a.getX());
                float ay = proj.blockToScreenY(a.getZ());
                float bx = proj.blockToScreenX(b.getX());
                float by = proj.blockToScreenY(b.getZ());
                if (isSea) drawDashedLine(g, ax, ay, bx, by, color);
                else       drawSolidLine(g, ax, ay, bx, by, color);
            }
        }
    }

    /**
     * Draws a 1-pixel line using filled 1×1 rects along a Bresenham-style
     * step. {@link GuiGraphics} has no native line primitive — this is
     * cheap enough for a handful of routes drawn once per frame.
     */
    private static void drawSolidLine(GuiGraphics g, float x0, float y0,
                                      float x1, float y1, int color) {
        drawLineDashed(g, x0, y0, x1, y1, color, 0, 0);
    }

    private static void drawDashedLine(GuiGraphics g, float x0, float y0,
                                       float x1, float y1, int color) {
        drawLineDashed(g, x0, y0, x1, y1, color, 3, 3);
    }

    /** dashLen=0 means solid. */
    private static void drawLineDashed(GuiGraphics g, float x0, float y0,
                                       float x1, float y1, int color,
                                       int dashLen, int gapLen) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return;
        int steps = (int) Math.ceil(len);
        for (int i = 0; i <= steps; i++) {
            if (dashLen > 0) {
                int cycle = dashLen + gapLen;
                if ((i % cycle) >= dashLen) continue;
            }
            float t = i / (float) steps;
            int x = Math.round(x0 + dx * t);
            int y = Math.round(y0 + dy * t);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }
}