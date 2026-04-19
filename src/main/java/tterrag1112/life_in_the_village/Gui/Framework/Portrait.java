package tterrag1112.life_in_the_village.Gui.Framework;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;

/**
 * NPC portrait widget. <strong>Slice 1 placeholder</strong>: paints a
 * parchment square with the appearance's initials in the centre. Live
 * entity rendering (rotating bust, gear overlay) lands in Slice 4.
 */
public final class Portrait {

    /**
     * Minimal client-safe snapshot of an NPC's appearance, derived from
     * {@code AppearanceComponent}. Lives here so screens and packets can
     * share it without depending on entity internals. Will grow alongside
     * the live entity bust in Slice 4.
     *
     * @param name    display name (used for placeholder initials)
     * @param isMale  biological sex, picks body/hair models later
     * @param age     arbitrary age scale, unused today
     * @param skinId  skin tone id (matches AppearanceComponent.skinTone)
     * @param hairId  hair style id (matches AppearanceComponent.hairStyle)
     */
    public record AppearanceSnapshot(
            String name,
            boolean isMale,
            int age,
            int skinId,
            int hairId) {}

    private Portrait() {}

    /**
     * Draws a {@code size} x {@code size} placeholder bust at (x,y).
     * @param tick game tick — unused today, will drive rotation in Slice 4
     */
    public static void draw(GuiGraphics g, Font font,
                            int x, int y, int size,
                            AppearanceSnapshot appearance,
                            long tick) {
        g.fill(x, y, x + size, y + size, BookScreenColors.SIDEBAR);
        g.renderOutline(x, y, size, size, BookScreenColors.BORDER);

        String initials = initials(appearance == null ? "" : appearance.name());
        int tw = font.width(initials);
        g.drawString(font, initials,
                x + (size - tw) / 2,
                y + (size - 8) / 2,
                BookScreenColors.DARK, false);
    }

    private static String initials(String name) {
        if (name == null || name.isEmpty()) return "?";
        StringBuilder out = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty()) out.append(Character.toUpperCase(part.charAt(0)));
            if (out.length() >= 2) break;
        }
        return out.length() == 0 ? "?" : out.toString();
    }
}
