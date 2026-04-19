package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Gui.Framework.Chrome;
import tterrag1112.life_in_the_village.Gui.Framework.ScrollList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StockpileScreen extends Screen {

    private record Row(ItemStack stack, int count) {}

    private static final int PANEL_W  = 200;
    private static final int ROW_H    = 20;
    private static final int LIST_H   = 10 * ROW_H;
    private static final int HEADER_H = 18;
    private static final int PANEL_H  = HEADER_H + LIST_H + 14;
    private static final Chrome.Dims DIMS = Chrome.Dims.of(PANEL_W, PANEL_H, 0, 4);

    private final List<ItemStack> stacks;
    private final List<Integer> counts;
    private ScrollList<Row> list;
    private int panelX, panelY;

    public StockpileScreen(String buildingName, List<ItemStack> stacks, List<Integer> counts) {
        super(Component.literal(buildingName + " — Stockpile"));
        this.stacks = stacks;
        this.counts = counts;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        panelX = (width  - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;
        List<Row> rows = new ArrayList<>(stacks.size());
        for (int i = 0; i < stacks.size(); i++)
            rows.add(new Row(stacks.get(i), i < counts.size() ? counts.get(i) : 0));
        list = new ScrollList<>(panelX + 4, panelY + HEADER_H,
                PANEL_W - 8, LIST_H, ROW_H, rows, this::drawRow, null);
    }

    private void drawRow(GuiGraphics g, int rx, int ry, int rw, int rh,
                         Row row, boolean hovered) {
        if (hovered) g.fill(rx, ry, rx + rw, ry + rh, BookScreenColors.HIGHLIGHT);
        g.renderItem(row.stack(), rx + 2, ry + 2);
        g.renderItemDecorations(font, row.stack(), rx + 2, ry + 2, null);
        g.drawString(font, row.stack().getHoverName().getString(),
                rx + 22, ry + (rh - 8) / 2, BookScreenColors.DARK, false);
        String c = String.valueOf(row.count());
        g.drawString(font, c, rx + rw - font.width(c) - 4,
                ry + (rh - 8) / 2, BookScreenColors.GOLD, true);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        g.fill(0, 0, width, height, 0xC0000000);
        Chrome.draw(g, panelX, panelY, DIMS);
        g.drawCenteredString(font, title.getString(),
                width / 2, panelY + 5, BookScreenColors.DARK);
        if (stacks.isEmpty()) {
            g.drawCenteredString(font, "Empty",
                    width / 2, panelY + PANEL_H / 2 - 4, BookScreenColors.MID);
        } else {
            list.render(g, mouseX, mouseY);
            g.drawCenteredString(font, stacks.size() + " unique items",
                    width / 2, panelY + HEADER_H + LIST_H + 2, BookScreenColors.LIGHT);
        }
        super.render(g, mouseX, mouseY, pt);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontal, double vertical) {
        return list != null && list.mouseScrolled(mouseX, mouseY, vertical);
    }
}
