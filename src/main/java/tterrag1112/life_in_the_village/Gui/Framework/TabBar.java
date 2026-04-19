package tterrag1112.life_in_the_village.Gui.Framework;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Horizontal section selector — the same role as {@link Sidebar} but laid out
 * as a row of tabs across the top of a panel. Each tab is sized individually
 * to fit its label plus padding.
 *
 * @param <S> the section enum the host screen uses for navigation
 */
public class TabBar<S extends Enum<S>> {

    /** A single tab in the bar. */
    public record Entry<S>(S section, String label, boolean enabled) {}

    private static final int H_PAD = 6;
    private static final int TAB_GAP = 2;

    private final int x, y, h;
    private final List<Entry<S>> entries;
    private final Supplier<S> currentSection;
    private final Consumer<S> onSelect;

    /**
     * @param x,y origin of the bar (top-left of the first tab)
     * @param h tab height in pixels
     * @param entries the tabs to draw, in order
     * @param currentSection supplier for the active section (re-read every frame)
     * @param onSelect callback fired when an enabled tab is clicked
     */
    public TabBar(int x, int y, int h,
                  List<Entry<S>> entries,
                  Supplier<S> currentSection,
                  Consumer<S> onSelect) {
        this.x = x;
        this.y = y;
        this.h = h;
        this.entries = entries;
        this.currentSection = currentSection;
        this.onSelect = onSelect;
    }

    /** Draws every tab; uses the current font from Minecraft. */
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        int nx = x;
        S cur = currentSection.get();
        for (Entry<S> e : entries) {
            int tw = font.width(e.label()) + H_PAD * 2;
            boolean active = e.section() == cur;
            int bg = active ? BookScreenColors.HIGHLIGHT : BookScreenColors.SIDEBAR;
            g.fill(nx, y, nx + tw, y + h, bg);
            g.renderOutline(nx, y, tw, h, BookScreenColors.BORDER);
            if (active) {
                g.fill(nx, y + h - 2, nx + tw, y + h, BookScreenColors.GOLD);
            }
            int color = !e.enabled() ? BookScreenColors.LIGHT
                    : active        ? BookScreenColors.DARK
                    :                 BookScreenColors.MID;
            g.drawString(font, e.label(), nx + H_PAD, y + (h - 8) / 2,
                    color, false);
            nx += tw + TAB_GAP;
        }
    }

    /** Hit-tests the click against each tab; consumes and dispatches if hit. */
    public boolean mouseClicked(double mx, double my) {
        Font font = Minecraft.getInstance().font;
        int nx = x;
        for (Entry<S> e : entries) {
            int tw = font.width(e.label()) + H_PAD * 2;
            if (e.enabled()
                    && mx >= nx && mx <= nx + tw
                    && my >= y && my <= y + h) {
                onSelect.accept(e.section());
                return true;
            }
            nx += tw + TAB_GAP;
        }
        return false;
    }
}
