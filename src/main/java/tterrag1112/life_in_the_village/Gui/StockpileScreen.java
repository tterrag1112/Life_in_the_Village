package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

public class StockpileScreen extends Screen {

    private final String buildingName;
    private final List<ItemStack> stacks;
    private final List<Integer> counts;

    //private final Map<Item, Integer> counts; // actual counts, uncapped

    private int scrollOffset = 0;
    private static final int ROWS_VISIBLE = 10;
    private static final int ROW_HEIGHT = 20;
    private static final int PANEL_WIDTH = 200;

    public StockpileScreen(String buildingName, List<ItemStack> stacks, List<Integer> counts) {
        super(Component.literal(buildingName + " — Stockpile"));
        this.buildingName = buildingName;
        this.stacks = stacks;
        this.counts = counts;

    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xC0000000);

        int panelX = (width - PANEL_WIDTH) / 2;
        int panelY = (height - (ROWS_VISIBLE * ROW_HEIGHT + 30)) / 2;

        // Panel background
        graphics.fill(panelX - 4, panelY - 4,
                panelX + PANEL_WIDTH + 4,
                panelY + ROWS_VISIBLE * ROW_HEIGHT + 30, 0xFF2A2A2A);
        graphics.fill(panelX - 3, panelY - 3,
                panelX + PANEL_WIDTH + 3,
                panelY + ROWS_VISIBLE * ROW_HEIGHT + 29, 0xFF3D3D3D);

        graphics.drawCenteredString(font, title.getString(),
                width / 2, panelY - 2, 0xFFFFAA);

        if (stacks.isEmpty()) {
            graphics.drawCenteredString(font, "Empty",
                    width / 2, panelY + 20, 0xAAAAAA);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }
        graphics.drawString(font, "TEST: " + (counts.isEmpty() ? "empty" : counts.get(0)),
                10, 10, 0xFF0000, true);

        int startIndex = scrollOffset;
        int endIndex = Math.min(startIndex + ROWS_VISIBLE, stacks.size());

        // First pass — render items and names
        for (int i = startIndex; i < endIndex; i++) {
            ItemStack stack = stacks.get(i);
            int rowY = panelY + (i - startIndex) * ROW_HEIGHT + 8;

            graphics.renderItem(stack, panelX + 2, rowY);

            String name = stack.getHoverName().getString();
            graphics.drawString(font, name, panelX + 22, rowY + 4, 0xFFFFFF, false);


            // Count rendered immediately after item in same pass
            String countStr = String.valueOf(counts.get(i));
            graphics.renderItemDecorations(font, stack, panelX + 2, rowY, countStr);
        }

        // Scrollbar
        if (stacks.size() > ROWS_VISIBLE) {
            int scrollbarHeight = ROWS_VISIBLE * ROW_HEIGHT;
            int thumbHeight = Math.max(10,
                    scrollbarHeight * ROWS_VISIBLE / stacks.size());
            int thumbY = panelY + 8 + scrollOffset
                    * (scrollbarHeight - thumbHeight)
                    / Math.max(1, stacks.size() - ROWS_VISIBLE);
            graphics.fill(panelX + PANEL_WIDTH + 2, panelY + 8,
                    panelX + PANEL_WIDTH + 6,
                    panelY + 8 + scrollbarHeight, 0xFF555555);
            graphics.fill(panelX + PANEL_WIDTH + 2, thumbY,
                    panelX + PANEL_WIDTH + 6,
                    thumbY + thumbHeight, 0xFFAAAAAA);
        }

        graphics.drawCenteredString(font,
                stacks.size() + " unique items",
                width / 2, panelY + ROWS_VISIBLE * ROW_HEIGHT + 14, 0x888888);

        // Call super before the count pass so counts render on top

        // Second pass — render counts on top of everything
        // Use PoseStack to ensure these draw above the item layer
        for (int i = startIndex; i < endIndex; i++) {
            int rowY = panelY + (i - startIndex) * ROW_HEIGHT + 8;
            String countStr = String.valueOf(counts.get(i));

            // Draw with a dark shadow behind the text so it's readable over any icon
            graphics.drawString(font, countStr,
                    panelX + PANEL_WIDTH - font.width(countStr) - 4,
                    rowY + 4, 0xFFFFAA, true);
            // true = draw shadow
        }
        super.render(graphics, mouseX, mouseY, partialTick);

    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontal, double vertical) {
        scrollOffset = Math.max(0,
                Math.min(scrollOffset - (int) vertical,
                        Math.max(0, stacks.size() - ROWS_VISIBLE)));
        return true;
    }
}