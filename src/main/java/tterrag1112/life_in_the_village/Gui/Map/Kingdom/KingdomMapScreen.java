package tterrag1112.life_in_the_village.Gui.Map.Kingdom;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.Layer.MapLayer;
import tterrag1112.life_in_the_village.Networking.RequestKingdomMapSyncPacket;

import java.util.UUID;

/**
 * Fullscreen kingdom map used for dev inspection and player reference.
 * The book-screen map page uses {@link KingdomMapPanel} directly; this
 * screen wraps that same panel with sidebar controls for layer toggles
 * and a manual refresh button.
 *
 * <p>Bind to a keybind (e.g. default {@code K}) in the mod's client
 * init. Construction takes a kingdom UUID — the keybind handler should
 * pick the player's current kingdom, or the nearest if unaffiliated.
 */
public class KingdomMapScreen extends Screen {

    private static final int SIDEBAR_W = 132;
    private static final int PAD       = 10;

    private final KingdomMapPanel panel;

    public KingdomMapScreen(UUID kingdomId) {
        super(Component.literal("Kingdom Map"));
        this.panel = new KingdomMapPanel(kingdomId);
    }

    @Override
    protected void init() {
        super.init();

        // TODO: when the atlas/route sync packet lands, request it here
        // before calling refresh() so the panel sees the freshest data.
        panel.refresh();
        ClientPacketDistributor.sendToServer(
                new RequestKingdomMapSyncPacket(panel.getKingdomId()));

        int btnX = PAD;
        int btnY = PAD + 20;

        // Refresh button
        addRenderableWidget(Button.builder(
                        Component.literal("Refresh"),
                        b -> panel.refresh())
                .pos(btnX, btnY).size(110, 16).build());
        btnY += 22;

        // Layer toggles
        for (MapLayer layer : panel.getLayers()) {
            final String id = layer.id();
            addRenderableWidget(Button.builder(
                            Component.literal(toggleLabel(layer)),
                            b -> {
                                panel.getLayerState().toggle(id);
                                b.setMessage(Component.literal(toggleLabel(layer)));
                            })
                    .pos(btnX, btnY).size(110, 14).build());
            btnY += 16;
        }
    }

    private String toggleLabel(MapLayer layer) {
        return (panel.getLayerState().isVisible(layer.id()) ? "[x] " : "[ ] ")
                + layer.label();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        // Background
        g.fill(0, 0, width, height, 0xCC000000);

        // Sidebar panel
        g.fill(0, 0, SIDEBAR_W, height, BookScreenColors.SIDEBAR);
        g.fill(SIDEBAR_W, 0, SIDEBAR_W + 1, height, BookScreenColors.BORDER);
        g.drawString(font, "Kingdom Map",
                PAD, PAD, BookScreenColors.DARK, false);

        // Map fills the rest
        int mapX = SIDEBAR_W + PAD;
        int mapY = PAD;
        int mapW = width  - mapX - PAD;
        int mapH = height - PAD * 2;
        panel.render(g, mapX, mapY, mapW, mapH, mouseX, mouseY);

        super.render(g, mouseX, mouseY, pt);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (!consumed && panel.mouseClicked((int) event.x(), (int) event.y())) {
            return true;
        }
        return super.mouseClicked(event, consumed);
    }

    @Override
    public boolean isPauseScreen() { return false; }
    public void onMapDataSynced() { panel.refresh(); }

}