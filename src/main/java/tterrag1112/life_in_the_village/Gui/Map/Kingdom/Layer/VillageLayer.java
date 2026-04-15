package tterrag1112.life_in_the_village.Gui.Map.Kingdom.Layer;

import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.KingdomMapData;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.MapProjection;

import java.util.UUID;

/**
 * Draws a coloured square per village. {@link MapSymbol} abstraction
 * (TODO for v1.1) will let this be swapped for an icon sprite without
 * changing the layer. Villages belonging to the focus kingdom use
 * {@code VILLAGE_MARKER}; foreign villages use a muted variant so
 * the player can tell which side of the border they're on.
 */
public final class VillageLayer implements MapLayer {

    /** Marker size in pixels. Held at a fixed size regardless of cell scale
     *  so villages stay clickable even on large kingdoms. */
    private static final int MARKER_SIZE = 6;

    @Override public String id()    { return "villages"; }
    @Override public String label() { return "Villages"; }

    @Override
    public void draw(GuiGraphics g, KingdomMapData data,
                     MapProjection proj, LayerState state,
                     int mouseX, int mouseY) {
        if (!state.isVisible(id())) return;

        for (KingdomMapData.VillageMarker v : data.villages) {
            int x = Math.round(proj.blockToScreenX(v.worldPos().getX()));
            int y = Math.round(proj.blockToScreenY(v.worldPos().getZ()));
            int half = MARKER_SIZE / 2;
            boolean focus = data.focusKingdomId.equals(v.kingdomId());
            int fill = v.isCapital() ? BookScreenColors.VILLAGE_CAPITAL
                    : (focus ? BookScreenColors.VILLAGE_MARKER
                    : BookScreenColors.KINGDOM_BORDER_FOREIGN);

            // Marker with dark outline for contrast against any terrain
            g.fill(x - half - 1, y - half - 1,
                    x + half + 1, y + half + 1,
                    BookScreenColors.DARK);
            g.fill(x - half, y - half, x + half, y + half, fill);
        }
    }

    /**
     * Hit-test a mouse position against village markers. Used by the
     * panel to route clicks to {@code VillageMapScreen}.
     */
    public static UUID hitTest(KingdomMapData data, MapProjection proj,
                               int mouseX, int mouseY) {
        int half = MARKER_SIZE / 2 + 1;
        for (KingdomMapData.VillageMarker v : data.villages) {
            int x = Math.round(proj.blockToScreenX(v.worldPos().getX()));
            int y = Math.round(proj.blockToScreenY(v.worldPos().getZ()));
            if (mouseX >= x - half && mouseX <= x + half
                    && mouseY >= y - half && mouseY <= y + half) {
                return v.id();
            }
        }
        return null;
    }
}