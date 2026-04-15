package tterrag1112.life_in_the_village.Gui.Map.Kingdom.Layer;

import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.KingdomMapData;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.MapProjection;

/**
 * A single drawing pass over the kingdom map. Layers are drawn in
 * order (ocean → terrain → territory → routes → villages → labels),
 * each reading from the immutable snapshot and projection.
 */
public interface MapLayer {
    /** Stable identifier used for toggle state and debugging. */
    String id();

    /** Human-readable label shown in the legend/toggle UI. */
    String label();

    void draw(GuiGraphics g, KingdomMapData data,
              MapProjection proj, LayerState state,
              int mouseX, int mouseY);

    /**
     * Per-layer visibility toggle state, keyed by layer id.
     * Stored on the panel so it survives re-opens within the same screen.
     */
    final class LayerState {
        private final java.util.Map<String, Boolean> visible = new java.util.HashMap<>();

        public boolean isVisible(String id) {
            return visible.getOrDefault(id, true);
        }
        public void setVisible(String id, boolean v) { visible.put(id, v); }
        public void toggle(String id) { visible.put(id, !isVisible(id)); }
    }
}