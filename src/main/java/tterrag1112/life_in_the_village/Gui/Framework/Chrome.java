package tterrag1112.life_in_the_village.Gui.Framework;

import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;

/**
 * Shared "book" window chrome shared by every parchment-style screen
 * in the mod. Provides two preset sizes (BOOK and COMPACT) plus a
 * factory for custom sizes; the actual paint is palette-driven.
 *
 * <p>Drawing is split so callers can intersperse content between
 * {@link #draw(GuiGraphics, int, int, Dims, Palette)} (frame + body) and
 * {@link #drawSidebarBg(GuiGraphics, int, int, Dims)} (left strip).
 */
public final class Chrome {

    private Chrome() {}

    /** Layout dimensions for a chrome instance. */
    public record Dims(int w, int h, int sidebarW, int pagePad) {
        /** Convenience factory for arbitrary sizes; preserves the standard padding ratios when omitted. */
        public static Dims of(int w, int h, int sidebarW, int pagePad) {
            return new Dims(w, h, sidebarW, pagePad);
        }
    }

    /** Colour scheme for the chrome frame. */
    public record Palette(int body, int border, int highlight) {}

    /** Parchment — default for all book/compact/custom screens. */
    public static final Palette PARCHMENT = new Palette(
            BookScreenColors.PARCHMENT, BookScreenColors.BORDER, BookScreenColors.HIGHLIGHT);

    /** Dark trade panel — used by TradeScreen. */
    public static final Palette DARK_TRADE = new Palette(0xFF222222, 0xFF444444, 0xFF2E2E2E);

    /** 420x300 book chrome used by VillageBook / KingdomBook / CompanyManagement. */
    public static final Dims BOOK    = new Dims(420, 300, 130, 14);

    /** 320x240 chrome for smaller screens such as the upcoming NpcProfile. */
    public static final Dims COMPACT = new Dims(320, 240,  96, 10);

    /**
     * Draws the panel body: drop shadow, fill, and the double outline
     * (dark border + inner highlight). No content or sidebar — pair with
     * {@link #drawSidebarBg} when the screen has a sidebar.
     */
    public static void draw(GuiGraphics g, int x, int y, Dims d, Palette p) {
        g.fill(x + 3, y + 3, x + d.w() + 3, y + d.h() + 3, 0x44000000);
        g.fill(x, y, x + d.w(), y + d.h(), p.body());
        g.renderOutline(x, y, d.w(), d.h(), p.border());
        g.renderOutline(x + 2, y + 2, d.w() - 4, d.h() - 4, p.highlight());
    }

    /**
     * Draws the left-aligned sidebar strip and the divider against the body.
     * Call after {@link #draw} so the sidebar paints over the parchment body.
     */
    public static void drawSidebarBg(GuiGraphics g, int x, int y, Dims d) {
        g.fill(x, y, x + d.sidebarW(), y + d.h(), BookScreenColors.SIDEBAR);
        g.fill(x + d.sidebarW() - 1, y, x + d.sidebarW(), y + d.h(),
                BookScreenColors.BORDER);
    }
}
