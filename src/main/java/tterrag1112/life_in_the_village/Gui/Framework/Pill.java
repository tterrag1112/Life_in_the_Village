package tterrag1112.life_in_the_village.Gui.Framework;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;

/**
 * Colored text chip — a 12px-tall pill with 4px horizontal padding,
 * used for reputation tiers, urgency markers, and relation labels.
 */
public final class Pill {

    private Pill() {}

    private static final int H_PAD = 4;
    private static final int HEIGHT = 12;

    /** Draws a pill at (x,y) with the given background and text colors. */
    public static void draw(GuiGraphics g, Font font,
                            int x, int y,
                            String text, int bgColor, int txtColor) {
        int w = width(font, text);
        g.fill(x, y, x + w, y + HEIGHT, bgColor);
        g.renderOutline(x, y, w, HEIGHT, BookScreenColors.BORDER);
        g.drawString(font, text, x + H_PAD, y + 2, txtColor, false);
    }

    /** Total pixel width including padding for the supplied label. */
    public static int width(Font font, String text) {
        return font.width(text) + H_PAD * 2;
    }
}
