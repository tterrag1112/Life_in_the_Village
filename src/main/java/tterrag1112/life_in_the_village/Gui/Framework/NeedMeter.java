package tterrag1112.life_in_the_village.Gui.Framework;

import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;

import java.util.Map;

/**
 * Need / status indicators in two flavours:
 *
 * <ul>
 *   <li>{@link #dots(GuiGraphics, int, int, Map)} — coloured 8x8 dots in a row,
 *       like the village needs strip in VillageBookScreen.</li>
 *   <li>{@link #bar(GuiGraphics, int, int, int, int, float, int)} — horizontal
 *       fill bar with a parchment-style border.</li>
 * </ul>
 */
public final class NeedMeter {

    private NeedMeter() {}

    private static final int DOT = 8;
    private static final int DOT_GAP = 4;

    /**
     * Draws a row of 8x8 dots, one per need entry, coloured by status.
     * Status strings "SURPLUS" / "SATISFIED" / "SHORTAGE" / "CRISIS"
     * are recognised case-insensitively; anything else falls back to MID.
     */
    public static void dots(GuiGraphics g, int x, int y,
                            Map<String, String> needs) {
        int dx = x;
        for (var e : needs.entrySet()) {
            int color = colorForLevel(e.getValue());
            g.fill(dx, y, dx + DOT, y + DOT, color);
            g.renderOutline(dx, y, DOT, DOT, BookScreenColors.BORDER);
            dx += DOT + DOT_GAP;
        }
    }

    /**
     * Draws a horizontal fill bar (0..1 of the given width) with a bordered
     * parchment background. Fraction is clamped to [0,1].
     */
    public static void bar(GuiGraphics g, int x, int y, int w, int h,
                           float fillFraction, int fillColor) {
        float f = Math.max(0f, Math.min(1f, fillFraction));
        g.fill(x, y, x + w, y + h, 0xFFDDD8C0);
        if (f > 0f) {
            g.fill(x, y, x + (int)(w * f), y + h, fillColor);
        }
        g.renderOutline(x, y, w, h, BookScreenColors.BORDER);
    }

    private static int colorForLevel(String level) {
        return switch (level == null ? "" : level.toUpperCase()) {
            case "SURPLUS"   -> BookScreenColors.GREEN_TXT;
            case "SATISFIED" -> BookScreenColors.DARK;
            case "SHORTAGE"  -> BookScreenColors.AMBER;
            case "CRISIS"    -> BookScreenColors.RED_TXT;
            default          -> BookScreenColors.MID;
        };
    }
}
