package tterrag1112.life_in_the_village.Gui.Framework;

import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;

/**
 * Shared window chrome for every screen in the mod. Provides two preset
 * sizes (BOOK and COMPACT) and two preset palettes (PARCHMENT and DARK_TRADE).
 *
 * <p>Drawing is split so callers can intersperse content between
 * {@link #draw(GuiGraphics, int, int, Dims, Palette)} (frame + body) and
 * {@link #drawSidebarBg(GuiGraphics, int, int, Dims)} (left strip).
 */
public final class Chrome {

    private Chrome() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Palette
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Colour and geometry palette for a chrome instance.
     *
     * @param shadowArgb      drop-shadow colour, or 0 to skip the shadow
     * @param outerFillArgb   outer rectangle fill colour
     * @param borderArgb      border outline colour; equal to outerFillArgb skips the border
     * @param innerFillArgb   inner rectangle fill colour; equal to outerFillArgb skips the fill
     * @param innerBorderArgb inner highlight outline; equal to innerFillArgb skips the outline
     * @param outerMargin     pixels by which the outer rect expands beyond (x, y, w, h)
     */
    public record Palette(
            int shadowArgb,
            int outerFillArgb,
            int borderArgb,
            int innerFillArgb,
            int innerBorderArgb,
            int outerMargin) {}

    /** Parchment book style — used by NpcProfileScreen, StockpileScreen, etc. */
    public static final Palette PARCHMENT = new Palette(
            0x44000000,
            BookScreenColors.PARCHMENT,
            BookScreenColors.BORDER,
            BookScreenColors.PARCHMENT,
            BookScreenColors.HIGHLIGHT,
            0);

    /** Dark trade panel style — used by TradeScreen. */
    public static final Palette DARK_TRADE = new Palette(
            0,
            0xFF222222,
            0xFF222222,
            0xFF2E2E2E,
            0xFF2E2E2E,
            2);

    // ─────────────────────────────────────────────────────────────────────────
    // Dims
    // ─────────────────────────────────────────────────────────────────────────

    /** Layout dimensions for a chrome instance. */
    public record Dims(int w, int h, int sidebarW, int pagePad) {
        public static Dims of(int w, int h, int sidebarW, int pagePad) {
            return new Dims(w, h, sidebarW, pagePad);
        }
    }

    /** 420×300 book chrome used by VillageBook / KingdomBook / CompanyManagement. */
    public static final Dims BOOK    = new Dims(420, 300, 130, 14);

    /** 320×240 chrome for smaller screens such as NpcProfileScreen. */
    public static final Dims COMPACT = new Dims(320, 240,  96, 10);

    // ─────────────────────────────────────────────────────────────────────────
    // Drawing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Draws the window chrome at (x, y) with the given palette.
     * Pair with {@link #drawSidebarBg} for screens that have a sidebar.
     */
    public static void draw(GuiGraphics g, int x, int y, Dims d, Palette p) {
        int m = p.outerMargin();
        if (p.shadowArgb() != 0) {
            g.fill(x + 3, y + 3, x + d.w() + 3, y + d.h() + 3, p.shadowArgb());
        }
        g.fill(x - m, y - m, x + d.w() + m, y + d.h() + m, p.outerFillArgb());
        if (p.borderArgb() != p.outerFillArgb()) {
            g.renderOutline(x - m, y - m, d.w() + m * 2, d.h() + m * 2, p.borderArgb());
        }
        if (p.innerFillArgb() != p.outerFillArgb()) {
            g.fill(x, y, x + d.w(), y + d.h(), p.innerFillArgb());
        }
        if (p.innerBorderArgb() != p.innerFillArgb()) {
            g.renderOutline(x + 2, y + 2, d.w() - 4, d.h() - 4, p.innerBorderArgb());
        }
    }

    /**
     * Draws the left-aligned sidebar strip and divider over the chrome body.
     * Call after {@link #draw} so the sidebar paints over the parchment body.
     */
    public static void drawSidebarBg(GuiGraphics g, int x, int y, Dims d) {
        g.fill(x, y, x + d.sidebarW(), y + d.h(), BookScreenColors.SIDEBAR);
        g.fill(x + d.sidebarW() - 1, y, x + d.sidebarW(), y + d.h(),
                BookScreenColors.BORDER);
    }
}
