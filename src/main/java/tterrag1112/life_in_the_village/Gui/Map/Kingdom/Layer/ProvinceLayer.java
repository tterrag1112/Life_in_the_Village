package tterrag1112.life_in_the_village.Gui.Map.Kingdom.Layer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.KingdomMapData;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.MapProjection;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.Set;

/**
 * Track D3.3 — paints province polygons on top of the focus
 * kingdom's territory layer. Each province gets a deterministic
 * tint derived from its UUID, blended with a stability-weighted
 * red overlay (low stability = redder). Borders match the
 * {@link KingdomTerritoryLayer} treatment.
 *
 * <p>Renders nothing when the focus kingdom has no provinces
 * (UNITARY culture, ≤3 villages, or pre-D3.3 saves).
 */
public final class ProvinceLayer implements MapLayer {

    /** Alpha applied to the per-province tint (0xAA = ~67%). */
    private static final int TINT_ALPHA = 0x80;

    @Override public String id()    { return "provinces"; }
    @Override public String label() { return "Provinces"; }

    @Override
    public void draw(GuiGraphics g, KingdomMapData data,
                     MapProjection proj, LayerState state,
                     int mouseX, int mouseY) {
        if (!state.isVisible(id())) return;
        if (data.provinces.isEmpty()) return;

        int px = proj.cellPx();

        // Per-province fill + border.
        for (KingdomMapData.ProvinceMarker p : data.provinces) {
            int rgb = colourFor(p);
            fillCells(g, proj, p.cells(), withAlpha(rgb, TINT_ALPHA), px);
            drawBorder(g, proj, p.cells(), rgb, px);
        }

        // Governor name labels at province centroids.
        var font = Minecraft.getInstance().font;
        for (KingdomMapData.ProvinceMarker p : data.provinces) {
            float sx = proj.blockToScreenX(p.centroidBlock().getX());
            float sy = proj.blockToScreenY(p.centroidBlock().getZ());
            String label = p.name();
            int w = font.width(label);
            // Don't draw labels outside the panel.
            if (sx < proj.originPx() || sy < proj.originPy()) continue;
            if (sx > proj.originPx() + proj.drawW()) continue;
            if (sy > proj.originPy() + proj.drawH()) continue;
            int lx = (int) sx - w / 2;
            int ly = (int) sy - font.lineHeight / 2;
            g.fill(lx - 2, ly - 1, lx + w + 2, ly + font.lineHeight, 0xC0202020);
            g.drawString(font, label, lx, ly,
                    BookScreenColors.GOLD, false);
        }
    }

    private static void fillCells(GuiGraphics g, MapProjection proj,
                                  Set<Long> cells, int colour, int px) {
        for (long k : cells) {
            int cx = AtlasCell.unpackX(k);
            int cz = AtlasCell.unpackZ(k);
            if (cx < proj.minCellX() || cz < proj.minCellZ()) continue;
            if (cx > proj.minCellX() + proj.gridW()  - 1) continue;
            if (cz > proj.minCellZ() + proj.gridH() - 1) continue;
            int x = proj.cellToScreenX(cx);
            int y = proj.cellToScreenY(cz);
            g.fill(x, y, x + px, y + px, colour);
        }
    }

    private static void drawBorder(GuiGraphics g, MapProjection proj,
                                   Set<Long> cells, int colour, int px) {
        for (long k : cells) {
            int cx = AtlasCell.unpackX(k);
            int cz = AtlasCell.unpackZ(k);
            int x = proj.cellToScreenX(cx);
            int y = proj.cellToScreenY(cz);
            if (!cells.contains(AtlasCell.packKey(cx, cz - 1)))
                g.fill(x, y, x + px, y + 1, colour);
            if (!cells.contains(AtlasCell.packKey(cx, cz + 1)))
                g.fill(x, y + px - 1, x + px, y + px, colour);
            if (!cells.contains(AtlasCell.packKey(cx - 1, cz)))
                g.fill(x, y, x + 1, y + px, colour);
            if (!cells.contains(AtlasCell.packKey(cx + 1, cz)))
                g.fill(x + px - 1, y, x + px, y + px, colour);
        }
    }

    /**
     * Deterministic per-province colour seeded by UUID. Blended
     * toward red as stability falls (stability=0 → fully red,
     * stability=100 → unblended).
     */
    private static int colourFor(KingdomMapData.ProvinceMarker p) {
        long h = p.id().getMostSignificantBits() ^ p.id().getLeastSignificantBits();
        int r = (int) ((h >>> 16) & 0xFF);
        int g = (int) ((h >>> 8)  & 0xFF);
        int b = (int)  (h         & 0xFF);
        // Pull each channel toward 128 so colours stay in a parchment-y range.
        r = (r + 128) >>> 1;
        g = (g + 128) >>> 1;
        b = (b + 128) >>> 1;
        // Stability tint — low stability shifts colour toward red.
        int stab = Math.max(0, Math.min(100, p.stability()));
        int redLift = (100 - stab) / 2;          // 0..50
        int greenCut = (100 - stab) / 4;         // 0..25
        int blueCut  = (100 - stab) / 4;         // 0..25
        r = Math.min(255, r + redLift);
        g = Math.max(0,   g - greenCut);
        b = Math.max(0,   b - blueCut);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    private static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }
}
