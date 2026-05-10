package tterrag1112.life_in_the_village.Gui.Map.Kingdom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.Layer.*;
import tterrag1112.life_in_the_village.Gui.VillageMapScreen;

import java.util.*;

/**
 * Embeddable kingdom map — reusable inside {@code KingdomBookScreen}'s
 * map page, inside the standalone {@link KingdomMapScreen}, and anywhere
 * else a kingdom overview is useful.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Construct with a kingdom UUID.</li>
 *   <li>Call {@link #refresh()} when the data caches are known to be
 *       fresh — typically right after a sync-on-open packet lands.</li>
 *   <li>Call {@link #render} from the containing screen's render.</li>
 *   <li>Route mouse clicks through {@link #mouseClicked}.</li>
 * </ol>
 *
 * <p>No per-tick work. Data is built once per {@link #refresh()}.
 */
public final class KingdomMapPanel {

    private final UUID kingdomId;
    private final KingdomBoundsProvider boundsProvider;
    private final List<MapLayer> layers = new ArrayList<>();
    private final MapLayer.LayerState layerState = new MapLayer.LayerState();


    private KingdomMapData data;
    private MapProjection  projection;
    private int lastPanelX, lastPanelY, lastPanelW, lastPanelH;
    public UUID getKingdomId() { return kingdomId; }


    public KingdomMapPanel(UUID kingdomId) {
        this(kingdomId, new KingdomBoundsProvider.ClaimBasedProvider());
    }

    public KingdomMapPanel(UUID kingdomId, KingdomBoundsProvider boundsProvider) {
        this.kingdomId = kingdomId;
        this.boundsProvider = boundsProvider;

        // Draw order matters — first added is drawn first (bottom layer)
        layers.add(new TerrainLayer());
        layers.add(new KingdomTerritoryLayer());
        // Track D3.3 — province polygons + governor labels above the
        // territory tint, below routes and village markers.
        layers.add(new ProvinceLayer());
        layers.add(new RouteLayer(false));   // land routes
        layers.add(new RouteLayer(true));    // sea routes
        layers.add(new VillageLayer());
        layers.add(new LabelLayer());
    }

    /** Rebuild the snapshot from current client caches. */
    public void refresh() {
        data = KingdomMapDataBuilder.build(kingdomId, boundsProvider).orElse(null);
        projection = null; // force reproject on next render
    }

    public boolean hasData() { return data != null; }

    public List<MapLayer> getLayers() { return Collections.unmodifiableList(layers); }
    public MapLayer.LayerState getLayerState() { return layerState; }

    /**
     * Render into the pixel rectangle {@code (panelX, panelY, panelW, panelH)}.
     * Safe to call every frame — only reprojects if the rectangle changed.
     */
    public void render(GuiGraphics g, int panelX, int panelY,
                       int panelW, int panelH,
                       int mouseX, int mouseY) {
        // Frame
        g.fill(panelX - 1, panelY - 1,
                panelX + panelW + 1, panelY + panelH + 1,
                BookScreenColors.BORDER);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH,
                BookScreenColors.OCEAN_DEEP);

        if (data == null) {
            var font = Minecraft.getInstance().font;
            String msg = "No kingdom data available.";
            g.drawString(font, msg,
                    panelX + (panelW - font.width(msg)) / 2,
                    panelY + panelH / 2 - 4,
                    BookScreenColors.LIGHT, false);
            return;
        }

        if (projection == null
                || panelX != lastPanelX || panelY != lastPanelY
                || panelW != lastPanelW || panelH != lastPanelH) {
            projection = MapProjection.build(
                    data.minCellX, data.minCellZ,
                    data.maxCellX, data.maxCellZ,
                    panelX, panelY, panelW, panelH);
            lastPanelX = panelX; lastPanelY = panelY;
            lastPanelW = panelW; lastPanelH = panelH;
        }

        for (MapLayer layer : layers) {
            layer.draw(g, data, projection, layerState, mouseX, mouseY);
        }
    }

    /**
     * Handle a mouse click inside the panel. Currently routes clicks on
     * village markers to {@link VillageMapScreen}. Returns true if the
     * click was consumed.
     */
    public boolean mouseClicked(int mouseX, int mouseY) {
        if (data == null || projection == null) return false;
        UUID clickedVillage = VillageLayer.hitTest(data, projection, mouseX, mouseY);
        if (clickedVillage != null) {
            // VillageMapScreen currently picks "nearest village" rather than
            // accepting a UUID. Opening the existing constructor is the
            // minimal-invasive option; extending it to accept a UUID is a
            // follow-up when village map sees wider use.
            Minecraft.getInstance().setScreen(new VillageMapScreen());
            return true;
        }
        return false;
    }
}