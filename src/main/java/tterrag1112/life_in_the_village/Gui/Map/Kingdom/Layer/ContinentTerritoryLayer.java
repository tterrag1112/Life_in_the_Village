package tterrag1112.life_in_the_village.Gui.Map.Kingdom.Layer;

import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.ContinentMapData;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.MapProjection;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.Set;

/**
 * Continent-mode territory layer — draws every kingdom region with its
 * own tint + border. Replacement for {@link KingdomTerritoryLayer} on
 * the continent screen. Separated so the kingdom map doesn't need to
 * know about per-kingdom tint palettes.
 */
public final class ContinentTerritoryLayer {

    private ContinentTerritoryLayer() {}

    public static void draw(GuiGraphics g, ContinentMapData data,
                            MapProjection proj) {
        int px = proj.cellPx();
        for (ContinentMapData.KingdomRegion region : data.kingdoms) {
            // Fill
            for (long k : region.cells()) {
                int cx = AtlasCell.unpackX(k), cz = AtlasCell.unpackZ(k);
                if (cx < proj.minCellX() || cz < proj.minCellZ()) continue;
                if (cx > proj.minCellX() + proj.gridW() - 1) continue;
                if (cz > proj.minCellZ() + proj.gridH() - 1) continue;
                int x = proj.cellToScreenX(cx), y = proj.cellToScreenY(cz);
                g.fill(x, y, x + px, y + px, region.tintColor());
            }
            // Border — draw last so it sits on top of the fill
            drawBorder(g, proj, region.cells(), region.borderColor());
        }
    }

    private static void drawBorder(GuiGraphics g, MapProjection proj,
                                   Set<Long> cells, int color) {
        int px = proj.cellPx();
        for (long k : cells) {
            int cx = AtlasCell.unpackX(k), cz = AtlasCell.unpackZ(k);
            int x = proj.cellToScreenX(cx), y = proj.cellToScreenY(cz);
            if (!cells.contains(AtlasCell.packKey(cx, cz - 1)))
                g.fill(x, y, x + px, y + 1, color);
            if (!cells.contains(AtlasCell.packKey(cx, cz + 1)))
                g.fill(x, y + px - 1, x + px, y + px, color);
            if (!cells.contains(AtlasCell.packKey(cx - 1, cz)))
                g.fill(x, y, x + 1, y + px, color);
            if (!cells.contains(AtlasCell.packKey(cx + 1, cz)))
                g.fill(x + px - 1, y, x + px, y + px, color);
        }
    }
}