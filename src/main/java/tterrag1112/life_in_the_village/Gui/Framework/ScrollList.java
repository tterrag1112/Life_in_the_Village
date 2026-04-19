package tterrag1112.life_in_the_village.Gui.Framework;

import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * Generic scrollable list with the StockpileScreen / TradeScreen scrollbar
 * style: 4px-wide track at {@code 0xFF444444}, thumb at {@code 0xFFAAAAAA},
 * hidden entirely when all items fit. Rows are drawn by a caller-supplied
 * renderer so the same widget serves every list-style page in the mod.
 *
 * @param <T> the row item type
 */
public class ScrollList<T> {

    /** Renders one row at the given coordinates. Called once per visible row per frame. */
    public interface RowRenderer<T> {
        void draw(GuiGraphics g, int rowX, int rowY, int rowW, int rowH,
                  T item, boolean hovered);
    }

    /** Handles a click on a row. Return true to consume the click. */
    public interface RowClickHandler<T> {
        boolean onClick(T item, int button, double relX, double relY);
    }

    private static final int SCROLLBAR_W   = 4;
    private static final int SCROLLBAR_TRK = 0xFF444444;
    private static final int SCROLLBAR_THM = 0xFFAAAAAA;

    private final int x, y, w, h, rowH;
    private final RowRenderer<T> rowDraw;
    private final RowClickHandler<T> rowClick;

    private List<T> items;
    private int scrollOffset = 0;

    /**
     * @param x,y top-left of the list area
     * @param w,h interior dimensions including any scrollbar gutter
     * @param rowH height of a single row in pixels
     * @param items initial items
     * @param rowDraw row renderer (required)
     * @param rowClick click handler, may be null
     */
    public ScrollList(int x, int y, int w, int h, int rowH,
                      List<T> items,
                      RowRenderer<T> rowDraw,
                      RowClickHandler<T> rowClick) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.rowH = rowH;
        this.items = items;
        this.rowDraw = rowDraw;
        this.rowClick = rowClick;
    }

    private int rowsVisible() { return Math.max(1, h / rowH); }

    private int maxScroll() {
        return Math.max(0, items.size() - rowsVisible());
    }

    /** Replaces the item list, clamping the scroll offset to the new size. */
    public void setItems(List<T> items) {
        this.items = items;
        if (scrollOffset > maxScroll()) scrollOffset = maxScroll();
    }

    /** Current scroll offset in row units. */
    public int getScrollOffset() { return scrollOffset; }

    /** Sets the scroll offset, clamped to valid range. */
    public void setScrollOffset(int offset) {
        this.scrollOffset = Math.max(0, Math.min(maxScroll(), offset));
    }

    /** Draws all visible rows and the scrollbar (if needed). */
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        boolean hasBar = items.size() > rowsVisible();
        int contentW  = hasBar ? w - SCROLLBAR_W - 2 : w;

        int start = scrollOffset;
        int end   = Math.min(start + rowsVisible(), items.size());

        for (int i = start; i < end; i++) {
            int rowY = y + (i - start) * rowH;
            boolean hovered = mouseX >= x && mouseX < x + contentW
                    && mouseY >= rowY && mouseY < rowY + rowH;
            rowDraw.draw(g, x, rowY, contentW, rowH, items.get(i), hovered);
        }

        if (hasBar) {
            int barX  = x + w - SCROLLBAR_W;
            int barH  = rowsVisible() * rowH;
            int thumbH = Math.max(10, barH * rowsVisible() / items.size());
            int thumbY = y + scrollOffset * (barH - thumbH)
                    / Math.max(1, items.size() - rowsVisible());
            g.fill(barX, y, barX + SCROLLBAR_W, y + barH, SCROLLBAR_TRK);
            g.fill(barX, thumbY, barX + SCROLLBAR_W, thumbY + thumbH,
                    SCROLLBAR_THM);
        }
    }

    /** Routes a click to the appropriate row; returns true if consumed. */
    public boolean mouseClicked(double mx, double my, int button) {
        if (rowClick == null) return false;
        boolean hasBar = items.size() > rowsVisible();
        int contentW  = hasBar ? w - SCROLLBAR_W - 2 : w;
        if (mx < x || mx >= x + contentW || my < y || my >= y + h) return false;

        int relRow = (int) ((my - y) / rowH);
        int idx    = scrollOffset + relRow;
        if (idx < 0 || idx >= items.size()) return false;

        double relX = mx - x;
        double relY = my - y - relRow * rowH;
        return rowClick.onClick(items.get(idx), button, relX, relY);
    }

    /** Vertical scroll handler — advances/rewinds the list by whole rows. */
    public boolean mouseScrolled(double mx, double my, double dy) {
        if (mx < x || mx >= x + w || my < y || my >= y + h) return false;
        setScrollOffset(scrollOffset - (int) Math.signum(dy));
        return true;
    }
}
