package tterrag1112.life_in_the_village.Gui.Framework;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;

/**
 * Label/value tile matching the {@code drawStatBox} variant in
 * VillageBookScreen exactly: parchment fill, bordered, label in light
 * brown at (+3,+3), value in dark at (+3,+12).
 */
public final class StatBox {

    private StatBox() {}

    /** Draws a label/value tile at the given rect using the supplied font. */
    public static void draw(GuiGraphics g, Font font,
                            int x, int y, int w, int h,
                            String label, String value) {
        g.fill(x, y, x + w, y + h, BookScreenColors.SIDEBAR);
        g.renderOutline(x, y, w, h, BookScreenColors.BORDER);
        g.drawString(font, label, x + 3, y + 3,  BookScreenColors.LIGHT, false);
        g.drawString(font, value, x + 3, y + 12, BookScreenColors.DARK,  false);
    }
}
