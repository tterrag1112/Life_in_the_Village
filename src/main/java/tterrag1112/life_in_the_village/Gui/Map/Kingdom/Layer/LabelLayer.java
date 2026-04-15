package tterrag1112.life_in_the_village.Gui.Map.Kingdom.Layer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.KingdomMapData;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.MapProjection;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws village name labels near each marker and a hover tooltip
 * showing cell coords, biome, terrain flags, village name, and
 * kingdom name for whatever the cursor is over.
 */
public final class LabelLayer implements MapLayer {

    @Override public String id()    { return "labels"; }
    @Override public String label() { return "Labels"; }

    @Override
    public void draw(GuiGraphics g, KingdomMapData data,
                     MapProjection proj, LayerState state,
                     int mouseX, int mouseY) {
        if (!state.isVisible(id())) return;
        Font font = Minecraft.getInstance().font;

        // Village name labels - placed just below each marker
        for (KingdomMapData.VillageMarker v : data.villages) {
            int x = Math.round(proj.blockToScreenX(v.worldPos().getX()));
            int y = Math.round(proj.blockToScreenY(v.worldPos().getZ()));
            int tw = font.width(v.name());
            g.drawString(font, v.name(), x - tw / 2, y + 6,
                    BookScreenColors.DARK, false);
        }

        // Hover tooltip
        int[] cell = proj.screenToCell(mouseX, mouseY);
        if (cell == null) return;
        drawTooltip(g, font, data, cell[0], cell[1], mouseX, mouseY);


    }

    private static void drawTooltip(GuiGraphics g, Font font,
                                    KingdomMapData data,
                                    int cellX, int cellZ,
                                    int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Cell " + cellX + ", " + cellZ));

        AtlasCell c = data.terrainGrid.get(AtlasCell.packKey(cellX, cellZ));
        if (c != null) {
            lines.add(Component.literal("Biome: " + shortBiome(c.biomeKey())));
            StringBuilder flags = new StringBuilder();
            if (c.has(AtlasCell.FLAG_HAS_OCEAN))  flags.append("ocean ");
            if (c.has(AtlasCell.FLAG_HAS_RIVER))  flags.append("river ");
            if (c.has(AtlasCell.FLAG_HAS_BEACH))  flags.append("beach ");
            if (c.has(AtlasCell.FLAG_HAS_SWAMP))  flags.append("swamp ");
            if (c.has(AtlasCell.FLAG_COAST))      flags.append("coast ");
            if (c.has(AtlasCell.FLAG_STEEP))      flags.append("steep ");
            if (flags.length() > 0)
                lines.add(Component.literal("Flags: " + flags.toString().trim()));
            lines.add(Component.literal("Y: " + c.centerY() + " (" + c.minY() + "-" + c.maxY() + ")"));
        } else {
            lines.add(Component.literal("(unsampled)"));
        }

        long key = AtlasCell.packKey(cellX, cellZ);
        if (data.focusCells.contains(key)) {
            lines.add(Component.literal("\u00A7e" + data.focusKingdomName));
        } else {
            for (KingdomMapData.ForeignKingdom fk : data.foreignKingdoms) {
                if (fk.cells().contains(key)) {
                    lines.add(Component.literal("\u00A77" + fk.name()));
                    break;
                }
            }
        }

        // Village at cursor
        int half = 6;
        for (KingdomMapData.VillageMarker v : data.villages) {
            int bx = (v.worldPos().getX() >> AtlasCell.CELL_SHIFT);
            int bz = (v.worldPos().getZ() >> AtlasCell.CELL_SHIFT);
            if (bx == cellX && bz == cellZ) {
                lines.add(Component.literal("\u00A7b" + v.name()));
                break;
            }
        }

        g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
    }

    private static String shortBiome(String key) {
        int colon = key.indexOf(':');
        return colon >= 0 ? key.substring(colon + 1) : key;
    }
}