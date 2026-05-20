package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import tterrag1112.life_in_the_village.Gui.Framework.Chrome;
import tterrag1112.life_in_the_village.Gui.Framework.StyledButton;
import tterrag1112.life_in_the_village.Networking.OpenLibraryScreenPacket;
import tterrag1112.life_in_the_village.Networking.PlayerVerbInvokePacket;

/**
 * Track 5a.8 — minimum-real LibraryScreen. Shows the library's
 * catalogue, with per-book "Borrow" buttons and a single "Take Lesson"
 * button. Borrow / Lesson actions delegate to the existing
 * {@code borrow_book} / {@code take_lesson} verbs via
 * {@link PlayerVerbInvokePacket}. Library content polish lands later.
 */
public class LibraryScreen extends Screen {

    private static final int PANEL_W = 280;
    private static final int PANEL_H = 220;
    private static final Chrome.Dims DIMS = Chrome.Dims.of(PANEL_W, PANEL_H, 0, 6);

    private final OpenLibraryScreenPacket data;
    private int panelX, panelY;

    public LibraryScreen(OpenLibraryScreenPacket data) {
        super(Component.literal("Library — " + data.librarianName()));
        this.data = data;
    }

    public static void openOnClient(OpenLibraryScreenPacket data) {
        Minecraft.getInstance().setScreen(new LibraryScreen(data));
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;

        addRenderableWidget(StyledButton.builder(Component.literal("Take Lesson"),
                        b -> sendVerb("take_lesson"))
                .pos(panelX + 8, panelY + 22).size(120, 18).build());

        int listX = panelX + 12;
        int listY = panelY + 50;
        int row = 0;
        int max = 6;
        for (OpenLibraryScreenPacket.Entry e : data.entries()) {
            if (row >= max) break;
            int y = listY + row * 22;
            String label = e.title() + (e.available() ? "" : " [out]");
            if (label.length() > 28) label = label.substring(0, 25) + "…";
            String labelFinal = label;
            addRenderableWidget(StyledButton.builder(
                            Component.literal((e.available() ? "Borrow: " : "View: ") + labelFinal),
                            b -> sendVerb("borrow_book"))
                    .pos(listX, y).size(PANEL_W - 24, 18).build());
            row++;
        }
        if (data.entries().isEmpty()) {
            // Render a hint via overlay text in render(); button-free row.
        }

        addRenderableWidget(StyledButton.builder(Component.literal("Close"),
                        b -> onClose())
                .pos(panelX + PANEL_W - 68, panelY + PANEL_H - 22)
                .size(60, 18).build());
    }

    private void sendVerb(String verbId) {
        ClientPacketDistributor.sendToServer(
                new PlayerVerbInvokePacket(data.librarianId(), verbId,
                        java.util.Map.of()));
        onClose();
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        gfx.fill(0, 0, width, height, 0x88000000);
        Chrome.draw(gfx, panelX, panelY, DIMS, Chrome.PARCHMENT);
        gfx.drawString(font, "Library — " + data.librarianName(),
                panelX + 8, panelY + 6, BookScreenColors.DARK, false);
        gfx.drawString(font, data.entries().size() + " book(s) catalogued",
                panelX + 140, panelY + 28, BookScreenColors.LIGHT, false);
        if (data.entries().isEmpty()) {
            gfx.drawString(font, "The shelves are empty.",
                    panelX + 12, panelY + 60, BookScreenColors.LIGHT, false);
        }
        super.render(gfx, mouseX, mouseY, partial);
    }
}
