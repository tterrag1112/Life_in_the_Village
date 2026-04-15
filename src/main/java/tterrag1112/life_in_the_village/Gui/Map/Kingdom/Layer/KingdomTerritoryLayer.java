package tterrag1112.life_in_the_village.Gui.Map.Kingdom.Layer;

import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.KingdomMapData;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.MapProjection;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.Set;

/**
 * Paints a semi-transparent tint over the focus kingdom's cells and
 * outlines the border edges of both the focus kingdom and any
 * foreign kingdoms in the viewport.
 *
 * <p>A "border edge" is a cell edge where the inside/outside ownership
 * differs. We check the four cardinal neighbours of each owned cell
 * and draw a 1-pixel line on the edges that face non-owned cells.
 */
public final class KingdomTerritoryLayer implements MapLayer {

    @Override public String id()    { return "territory"; }
    @Override public String label() { return "Territory"; }

    @Override
    public void draw(GuiGraphics g, KingdomMapData data,
                     MapProjection proj, LayerState state,
                     int mouseX, int mouseY) {
        if (!state.isVisible(id())) return;

        int px = proj.cellPx();

        // Focus kingdom — fill + border
        fillCells(g, proj, data.focusCells, BookScreenColors.KINGDOM_FOCUS_TINT);
        drawBorder(g, proj, data.focusCells, BookScreenColors.KINGDOM_BORDER_FOCUS);

        // Foreign kingdoms — lighter fill + border
        for (KingdomMapData.ForeignKingdom fk : data.foreignKingdoms) {
            fillCells(g, proj, fk.cells(), BookScreenColors.KINGDOM_FOREIGN_TINT);
            drawBorder(g, proj, fk.cells(), BookScreenColors.KINGDOM_BORDER_FOREIGN);
        }
    }

    private static void fillCells(GuiGraphics g, MapProjection proj,
                                  Set<Long> cells, int color) {
        int px = proj.cellPx();
        for (long k : cells) {
            int cx = AtlasCell.unpackX(k);
            int cz = AtlasCell.unpackZ(k);
            if (cx < proj.minCellX() || cz < proj.minCellZ()) continue;
            if (cx > proj.minCellX() + proj.gridW()  - 1) continue;
            if (cz > proj.minCellZ() + proj.gridH() - 1) continue;
            int x = proj.cellToScreenX(cx);
            int y = proj.cellToScreenY(cz);
            g.fill(x, y, x + px, y + px, color);
        }
    }

    private static void drawBorder(GuiGraphics g, MapProjection proj,
                                   Set<Long> cells, int color) {
        int px = proj.cellPx();
        for (long k : cells) {
            int cx = AtlasCell.unpackX(k);
            int cz = AtlasCell.unpackZ(k);
            int x = proj.cellToScreenX(cx);
            int y = proj.cellToScreenY(cz);

            // North edge
            if (!cells.contains(AtlasCell.packKey(cx, cz - 1)))
                g.fill(x, y, x + px, y + 1, color);
            // South edge
            if (!cells.contains(AtlasCell.packKey(cx, cz + 1)))
                g.fill(x, y + px - 1, x + px, y + px, color);
            // West edge
            if (!cells.contains(AtlasCell.packKey(cx - 1, cz)))
                g.fill(x, y, x + 1, y + px, color);
            // East edge
            if (!cells.contains(AtlasCell.packKey(cx + 1, cz)))
                g.fill(x + px - 1, y, x + px, y + px, color);
        }
    }
}