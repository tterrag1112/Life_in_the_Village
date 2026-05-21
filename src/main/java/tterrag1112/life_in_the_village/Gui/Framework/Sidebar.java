package tterrag1112.life_in_the_village.Gui.Framework;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Vertical section list rendered on the left edge of a book screen.
 * Mirrors the visual style of VillageBookScreen / KingdomBookScreen:
 * highlighted background and gold accent for the active row, a "&gt; "
 * marker prefix, and dim text for disabled rows.
 *
 * <p>An optional {@code footerRenderer} callback is invoked at the end of
 * {@link #render} so screens like BusinessManagementScreen can draw a
 * treasury/summary block at the bottom of the sidebar strip without
 * reaching inside Sidebar internals.
 *
 * @param <S> the section enum the host screen uses for navigation
 */
public class Sidebar<S extends Enum<S>> {

    /** A single row in the sidebar. */
    public record Entry<S>(S section, String label, boolean enabled) {}

    private final int x, y, w, rowH;
    private final List<Entry<S>> entries;
    private final Supplier<S> currentSection;
    private final Consumer<S> onSelect;
    /** Called once per frame after all nav rows are drawn. May be null. */
    private final @Nullable Consumer<GuiGraphics> footerRenderer;

    /**
     * @param x,y            origin of the sidebar (top-left)
     * @param w              width of the sidebar strip in pixels
     * @param rowH           per-row height including the gap below
     * @param entries        the rows to draw, in order
     * @param currentSection supplier for the active section (re-read every frame)
     * @param onSelect       callback fired when the user clicks an enabled row
     * @param footerRenderer optional callback drawn after all nav rows; receives
     *                       the same {@link GuiGraphics}. Pass {@code null} for none.
     */
    public Sidebar(int x, int y, int w, int rowH,
                   List<Entry<S>> entries,
                   Supplier<S> currentSection,
                   Consumer<S> onSelect,
                   @Nullable Consumer<GuiGraphics> footerRenderer) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.rowH = rowH;
        this.entries = entries;
        this.currentSection = currentSection;
        this.onSelect = onSelect;
        this.footerRenderer = footerRenderer;
    }

    /**
     * Convenience constructor without a footer — existing callers unchanged.
     */
    public Sidebar(int x, int y, int w, int rowH,
                   List<Entry<S>> entries,
                   Supplier<S> currentSection,
                   Consumer<S> onSelect) {
        this(x, y, w, rowH, entries, currentSection, onSelect, null);
    }

    /** Draws every row, then the optional footer. Uses the current font from Minecraft. */
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        int ny = y;
        S cur = currentSection.get();
        for (Entry<S> e : entries) {
            boolean active = e.section() == cur;
            if (active) {
                g.fill(x + 2, ny - 1, x + w - 1, ny + rowH - 1,
                        BookScreenColors.HIGHLIGHT);
                g.fill(x + 2, ny - 1, x + 4, ny + rowH - 1,
                        BookScreenColors.GOLD);
            }
            int color = !e.enabled() ? BookScreenColors.LIGHT
                    : active        ? BookScreenColors.DARK
                    :                 BookScreenColors.MID;
            g.drawString(font, (active ? "> " : "  ") + e.label(),
                    x + 6, ny + (rowH - 9) / 2, color, false);
            ny += rowH;
        }
        if (footerRenderer != null) footerRenderer.accept(g);
    }

    /** Tests the click against each row; consumes and dispatches if hit. */
    public boolean mouseClicked(double mx, double my) {
        int ny = y;
        for (Entry<S> e : entries) {
            if (e.enabled()
                    && mx >= x + 2 && mx <= x + w - 1
                    && my >= ny - 1 && my <= ny + rowH - 1) {
                onSelect.accept(e.section());
                return true;
            }
            ny += rowH;
        }
        return false;
    }

    /**
     * Up/down arrow navigation — moves to the previous/next enabled entry.
     * Returns true if the key was consumed.
     */
    public boolean keyPressed(int key) {
        // 264 = down arrow, 265 = up arrow (GLFW key codes used by MC)
        if (key != 264 && key != 265) return false;
        int dir = key == 264 ? 1 : -1;
        S cur = currentSection.get();
        int idx = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).section() == cur) { idx = i; break; }
        }
        for (int step = 1; step <= entries.size(); step++) {
            int next = ((idx + dir * step) % entries.size() + entries.size())
                    % entries.size();
            if (entries.get(next).enabled()) {
                onSelect.accept(entries.get(next).section());
                return true;
            }
        }
        return false;
    }
}
