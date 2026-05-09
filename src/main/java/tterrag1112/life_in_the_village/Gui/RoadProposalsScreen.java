package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Gui.Framework.Chrome;
import tterrag1112.life_in_the_village.Gui.Framework.ProgressBar;
import tterrag1112.life_in_the_village.Networking.OpenRoadProposalsPacket;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;

import java.util.List;

/**
 * Track C3.1 — read-only book screen showing the player's road
 * proposals (status, progress bar, fee).
 *
 * <h3>Design note</h3>
 * Minimal by design: the user-answered phase scope was "Minimal book
 * screen + dev command". Submission still goes through
 * {@code /litv road propose}; this screen never accepts input. A
 * future phase can extend this into a full proposal-builder with
 * village pickers and a route-preview widget.
 */
public class RoadProposalsScreen extends Screen {

    private static final Chrome.Dims DIMS = Chrome.COMPACT;
    private static final int ROW_H = 32;

    private final List<OpenRoadProposalsPacket.Entry> entries;
    private int bookX, bookY;

    public RoadProposalsScreen(List<OpenRoadProposalsPacket.Entry> entries) {
        super(Component.literal("Road Proposals"));
        this.entries = entries;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        bookX = (width - DIMS.w()) / 2;
        bookY = (height - DIMS.h()) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88000000);
        Chrome.draw(g, bookX, bookY, DIMS, Chrome.PARCHMENT);

        // Header
        g.drawString(font, "⌂ Road Engineer's Plans",
                bookX + 12, bookY + 10, BookScreenColors.DARK, false);
        g.fill(bookX + 8, bookY + 24, bookX + DIMS.w() - 8, bookY + 25,
                BookScreenColors.BORDER);

        if (entries.isEmpty()) {
            g.drawString(font,
                    "No proposals yet. Use /litv road propose <vA> <vB>.",
                    bookX + 12, bookY + 40, BookScreenColors.MID, false);
            super.render(g, mx, my, pt);
            return;
        }

        // List
        int y = bookY + 32;
        for (int i = 0; i < entries.size(); i++) {
            OpenRoadProposalsPacket.Entry e = entries.get(i);
            renderRow(g, e, bookX + 12, y, DIMS.w() - 24);
            y += ROW_H;
            if (y + ROW_H > bookY + DIMS.h() - 12) break; // overflow → silently truncate
        }

        super.render(g, mx, my, pt);
    }

    private void renderRow(GuiGraphics g, OpenRoadProposalsPacket.Entry e,
                            int x, int y, int w) {
        // Border
        g.fill(x, y + ROW_H - 2, x + w, y + ROW_H - 1, BookScreenColors.BORDER);

        // Title
        String title = e.fromVillage() + " → " + e.toVillage();
        g.drawString(font, title, x, y, BookScreenColors.DARK, false);

        // Right-aligned status
        Component statusText = Component.literal(e.status());
        int sw = font.width(statusText);
        g.drawString(font, statusText,
                x + w - sw, y, statusColor(e.status()), false);

        // Sub-line: tier, fee
        String sub = e.tier() + "  ·  " + CurrencyValue.of(e.currencyFeeBronze())
                + "  ·  " + e.totalBlocks() + " blocks";
        g.drawString(font, sub, x, y + 11, BookScreenColors.MID, false);

        // Progress bar
        float frac = e.totalBlocks() == 0
                ? 0f : (float) e.progressBlocks() / e.totalBlocks();
        ProgressBar.drawDefault(g, x, y + 22, w, 6, frac);
    }

    private static int statusColor(String status) {
        return switch (status) {
            case "IN_PROGRESS" -> 0xFFC58A2C;
            case "COMPLETE"    -> 0xFF3F8F3F;
            case "CANCELLED"   -> 0xFF8F3F3F;
            default            -> BookScreenColors.MID;
        };
    }
}
