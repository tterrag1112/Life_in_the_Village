package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import tterrag1112.life_in_the_village.Gui.Framework.Chrome;
import tterrag1112.life_in_the_village.Gui.Framework.CoinRow;
import tterrag1112.life_in_the_village.Gui.Framework.Pill;
import tterrag1112.life_in_the_village.Gui.Framework.ScrollList;
import tterrag1112.life_in_the_village.Gui.Framework.StatBox;
import tterrag1112.life_in_the_village.Gui.Framework.StyledButton;
import tterrag1112.life_in_the_village.Gui.Framework.StyledEditBox;
import tterrag1112.life_in_the_village.Gui.Framework.TooltipLayer;
import tterrag1112.life_in_the_village.Networking.OpenStallManagementPacket;
import tterrag1112.life_in_the_village.Networking.StallManagementActionPacket;

import java.util.UUID;

/**
 * Player-owned-stall management (merchant arc Phase 5b). Owner-only: a
 * per-item custom-price editor (±20% band, server-validated), stock view,
 * an "Open Stall Chest" button, and read-only sales / reputation / rent.
 * Built on the parchment book chrome via Gui.Framework.
 */
public class StallManagementScreen extends Screen {

    private static final int BOOK_W = 360, BOOK_H = 260, PAGE_PAD = 12;
    private static final int ROW_H = 26;
    private static final Chrome.Dims DIMS = Chrome.Dims.of(BOOK_W, BOOK_H, 0, PAGE_PAD);

    private record Row(OpenStallManagementPacket.ItemRow data) {}

    private final OpenStallManagementPacket data;
    private int bookX, bookY;
    private final TooltipLayer tooltips = new TooltipLayer();

    private ScrollList<Row> itemList;
    private StyledEditBox priceBox;
    private OpenStallManagementPacket.ItemRow selected;
    private int lastMouseX, lastMouseY;

    public StallManagementScreen(OpenStallManagementPacket data) {
        super(Component.literal(data.stallLabel()));
        this.data = data;
    }

    public UUID getStallId() { return data.stallId(); }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        bookX = (width - BOOK_W) / 2;
        bookY = (height - BOOK_H) / 2;

        int listY = bookY + 64;
        int listH = ROW_H * 5;
        int listW = BOOK_W - PAGE_PAD * 2 - 120; // leave room for the editor column

        java.util.List<Row> rows = data.rows().stream().map(Row::new)
                .collect(java.util.stream.Collectors.toList());
        itemList = new ScrollList<>(bookX + PAGE_PAD, listY, listW, listH, ROW_H,
                rows, this::drawItemRow, this::onItemClick);

        // Editor column (right of the list).
        int editX = bookX + PAGE_PAD + listW + 8;
        priceBox = new StyledEditBox(font, editX, listY + 26, 100, 16,
                Component.literal("Price"));
        priceBox.setMaxLength(9);
        addRenderableWidget(priceBox);

        addRenderableWidget(StyledButton.builder(Component.literal("Set Price"),
                        b -> setPrice())
                .pos(editX, listY + 46).size(100, 16).build());
        addRenderableWidget(StyledButton.builder(Component.literal("Clear"),
                        b -> clearPrice())
                .pos(editX, listY + 66).size(100, 16).build());

        // Open Stall Chest (top-right).
        addRenderableWidget(StyledButton.builder(Component.literal("Open Stall Chest"),
                        b -> sendAction(StallManagementActionPacket.Action.OPEN_CHEST,
                                "minecraft:air", 0))
                .pos(bookX + BOOK_W - PAGE_PAD - 120, bookY + 34).size(120, 16).build());
    }

    private void drawItemRow(GuiGraphics g, int rx, int ry, int rw, int rh,
                             Row row, boolean hovered) {
        OpenStallManagementPacket.ItemRow d = row.data();
        boolean sel = selected != null && selected.itemId().equals(d.itemId());
        if (hovered || sel) g.fill(rx, ry, rx + rw, ry + rh, sel ? 0x40FFD27F : 0x33FFFFFF);

        g.drawString(font, d.itemName(), rx + 2, ry + 2, 0xFF3A2A18, false);
        CoinRow.draw(g, d.effectivePrice(), rx + 2, ry + 12);
        String stock = "x" + d.stock();
        g.drawString(font, stock, rx + rw - font.width(stock) - 4, ry + 2, 0xFF2A6A2A, false);
        if (d.hasCustom()) {
            Pill.draw(g, font, rx + rw - Pill.width(font, "custom") - 4, ry + 12,
                    "custom", 0xFF5A3A1A, 0xFFFFD27F);
        }
    }

    private boolean onItemClick(Row row, int button, double relX, double relY) {
        if (button != 0) return false;
        selected = row.data();
        priceBox.setValue(String.valueOf(row.data().effectivePrice()));
        return true;
    }

    private void setPrice() {
        if (selected == null) return;
        long price;
        try {
            price = Long.parseLong(priceBox.getValue().trim());
        } catch (NumberFormatException e) {
            return;
        }
        sendAction(StallManagementActionPacket.Action.SET_PRICE, selected.itemId(), price);
    }

    private void clearPrice() {
        if (selected == null) return;
        sendAction(StallManagementActionPacket.Action.CLEAR_PRICE, selected.itemId(), 0);
    }

    private void sendAction(StallManagementActionPacket.Action action,
                            String itemId, long price) {
        ClientPacketDistributor.sendToServer(new StallManagementActionPacket(
                action, data.stallId(), Identifier.parse(itemId), price));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        tooltips.reset();
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        g.fill(0, 0, width, height, 0xC0000000);
        Chrome.draw(g, bookX, bookY, DIMS, Chrome.PARCHMENT);

        g.drawString(font, data.stallLabel(), bookX + PAGE_PAD, bookY + 8, 0xFF3A2A18, false);

        // Stat row.
        int sy = bookY + 20;
        int sw = 80, sh = 22;
        StatBox.draw(g, font, bookX + PAGE_PAD, sy, sw, sh,
                "Sales", String.valueOf(data.totalSales()));
        StatBox.draw(g, font, bookX + PAGE_PAD + sw + 4, sy, sw, sh,
                "Reputation", data.reputationTier());
        String rent = data.purchased() ? "Owned"
                : "Rented " + Math.max(0, (data.rentUntilTick() - data.currentTick()) / 24000L) + "d";
        StatBox.draw(g, font, bookX + PAGE_PAD + (sw + 4) * 2, sy, sw, sh, "Tenure", rent);

        // Column captions.
        g.drawString(font, "Items / Prices", bookX + PAGE_PAD, bookY + 52, 0xFF5A4A38, false);

        itemList.render(g, mouseX, mouseY);

        if (data.rows().isEmpty()) {
            g.drawString(font, "Stock the chest to set prices.",
                    bookX + PAGE_PAD, bookY + 70, 0xFF777777, false);
        }
        if (selected != null) {
            int editX = bookX + PAGE_PAD + (BOOK_W - PAGE_PAD * 2 - 120) + 8;
            g.drawString(font, "Band: " + selected.minPrice() + "–" + selected.maxPrice(),
                    editX, bookY + 64 + 10, 0xFF5A4A38, false);
        }

        super.render(g, mouseX, mouseY, pt);
        tooltips.flush(g);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        if (itemList != null
                && itemList.mouseClicked(lastMouseX, lastMouseY, event.button())) {
            return true;
        }
        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (itemList != null && itemList.mouseScrolled(mx, my, dy)) return true;
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) { onClose(); return true; }
        return super.keyPressed(event);
    }
}
