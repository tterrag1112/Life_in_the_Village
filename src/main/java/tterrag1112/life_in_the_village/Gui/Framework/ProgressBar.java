package tterrag1112.life_in_the_village.Gui.Framework;

import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;

/**
 * Horizontal fill bar representing progress toward a goal (XP, construction,
 * time-to-event, etc.). Semantically distinct from {@link NeedMeter#bar},
 * which represents a current need level — keep them separate even though the
 * rendering is similar.
 */
public final class ProgressBar {

    private ProgressBar() {}

    /**
     * Draws a filled bar at the given rect.
     *
     * @param fill        fraction filled, clamped to [0, 1]
     * @param bgColor     background (unfilled) color
     * @param fillColor   fill color
     * @param borderColor outline color
     */
    public static void draw(GuiGraphics g, int x, int y, int w, int h,
                            float fill,
                            int bgColor, int fillColor, int borderColor) {
        float f = Math.max(0f, Math.min(1f, fill));
        g.fill(x, y, x + w, y + h, bgColor);
        if (f > 0f) {
            g.fill(x, y, x + (int)(w * f), y + h, fillColor);
        }
        g.renderOutline(x, y, w, h, borderColor);
    }

    /** Parchment-palette XP bar: sidebar background, gold fill, standard border. */
    public static void drawDefault(GuiGraphics g, int x, int y, int w, int h,
                                   float fill) {
        draw(g, x, y, w, h, fill,
                BookScreenColors.SIDEBAR,
                BookScreenColors.GOLD,
                BookScreenColors.BORDER);
    }
}
