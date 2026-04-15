package tterrag1112.life_in_the_village.Gui.Map.Kingdom.Layer;

import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.KingdomMapData;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.MapProjection;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;

/**
 * Fills the viewport with ocean, then paints each known atlas cell
 * according to its biome category / flags. Unknown cells (absent
 * from the atlas cache) stay ocean-coloured — a visual cue that the
 * cell has not yet been sampled.
 */
public final class TerrainLayer implements MapLayer {

    @Override public String id()    { return "terrain"; }
    @Override public String label() { return "Terrain"; }

    @Override
    public void draw(GuiGraphics g, KingdomMapData data,
                     MapProjection proj, LayerState state,
                     int mouseX, int mouseY) {

        // Ocean fill across the whole grid
        g.fill(proj.originPx(), proj.originPy(),
                proj.originPx() + proj.drawW(),
                proj.originPy() + proj.drawH(),
                BookScreenColors.OCEAN_DEEP);

        if (!state.isVisible(id())) return;

        int cellPx = proj.cellPx();
        for (AtlasCell c : data.terrainGrid.values()) {
            int x = proj.cellToScreenX(c.cellX());
            int y = proj.cellToScreenY(c.cellZ());
            g.fill(x, y, x + cellPx, y + cellPx, colorFor(c));
        }
    }

    private static int colorFor(AtlasCell c) {
        if (c.has(AtlasCell.FLAG_HAS_OCEAN))
            return BookScreenColors.OCEAN_DEEP;
        if (c.has(AtlasCell.FLAG_HAS_BEACH))
            return BookScreenColors.TERRAIN_BEACH;
        if (c.has(AtlasCell.FLAG_HAS_RIVER) || c.has(AtlasCell.FLAG_HAS_SWAMP))
            return c.has(AtlasCell.FLAG_HAS_SWAMP)
                    ? BookScreenColors.TERRAIN_SWAMP
                    : BookScreenColors.FRESHWATER;
        if (c.has(AtlasCell.FLAG_STEEP))
            return BookScreenColors.TERRAIN_MOUNTAIN;

        BiomeCategory cat = c.category();
        return switch (cat) {
            case OCEAN   -> BookScreenColors.OCEAN_DEEP;
            case RIVER   -> BookScreenColors.FRESHWATER;
            case BEACH   -> BookScreenColors.TERRAIN_BEACH;
            case SWAMP   -> BookScreenColors.TERRAIN_SWAMP;
            case FOREST  -> BookScreenColors.TERRAIN_FOREST;
            case DESERT  -> BookScreenColors.TERRAIN_DESERT;
            //case SNOW    -> BookScreenColors.TERRAIN_SNOW;
            case MOUNTAIN-> BookScreenColors.TERRAIN_MOUNTAIN;
            //case HILLS   -> BookScreenColors.TERRAIN_HILLS;
            case PLAINS  -> BookScreenColors.TERRAIN_PLAINS;
            default      -> BookScreenColors.TERRAIN_PLAINS;
        };
    }
}