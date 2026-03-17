package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.TradeOffer;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Networking.TradeActionPacket;

import java.util.List;
import java.util.UUID;

public class TradeScreen extends Screen {

    private static final int PANEL_WIDTH  = 320;
    private static final int PANEL_HEIGHT = 240;
    private static final int ROW_HEIGHT   = 24;
    private static final int ROWS_VISIBLE = 8;
    private static final int COL_WIDTH    = PANEL_WIDTH / 2 - 8;

    private final UUID merchantId;
    private final String merchantName;
    private final List<TradeOffer> offers;
    private long playerWealth;

    private int scrollOffset = 0;
    private int hoveredRow = -1;
    private boolean hoveredBuy = false;

    public TradeScreen(UUID merchantId, String merchantName,
                       List<TradeOffer> offers, long playerWealth) {
        super(Component.literal(merchantName));
        this.merchantId   = merchantId;
        this.merchantName = merchantName;
        this.offers       = offers;
        this.playerWealth = playerWealth;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        int panelX    = (width  - PANEL_WIDTH)  / 2;
        int panelY    = (height - PANEL_HEIGHT) / 2;
        int buyColX   = panelX + 4;
        int sellColX  = panelX + PANEL_WIDTH / 2 + 4;
        int headerY   = panelY + 18;
        int rowsStart = headerY + 20;
        int startIdx  = scrollOffset;
        int endIdx    = Math.min(startIdx + ROWS_VISIBLE, offers.size());

        // Background
        g.fill(0, 0, width, height, 0xC0000000);
        g.fill(panelX - 2, panelY - 2,
                panelX + PANEL_WIDTH + 2,
                panelY + PANEL_HEIGHT + 2, 0xFF222222);
        g.fill(panelX, panelY,
                panelX + PANEL_WIDTH,
                panelY + PANEL_HEIGHT, 0xFF2E2E2E);

        // Column header backgrounds
        g.fill(buyColX, headerY,
                buyColX + COL_WIDTH, headerY + 14, 0xFF1A4A1A);
        g.fill(sellColX, headerY,
                sellColX + COL_WIDTH, headerY + 14, 0xFF4A1A1A);

        // Header icons — green emerald for buy, red for sell
        ItemStack buyHeader  = new ItemStack(net.minecraft.world.item.Items.EMERALD);
        ItemStack sellHeader = new ItemStack(net.minecraft.world.item.Items.EMERALD);
        g.renderItem(buyHeader,  buyColX  + COL_WIDTH / 2 - 8, headerY - 1);
        g.renderItem(sellHeader, sellColX + COL_WIDTH / 2 - 8, headerY - 1);

        // Divider
        g.fill(panelX + PANEL_WIDTH / 2, headerY,
                panelX + PANEL_WIDTH / 2 + 1,
                panelY + PANEL_HEIGHT - 16, 0xFF444444);

        // Wallet coins display
        renderCoinRow(g, playerWealth,
                panelX + 4, panelY + PANEL_HEIGHT - 18);

        // Hover detection
        hoveredRow = -1;
        hoveredBuy = false;

        for (int i = startIdx; i < endIdx; i++) {
            TradeOffer offer = offers.get(i);
            int rowY = rowsStart + (i - startIdx) * ROW_HEIGHT;

            boolean hoverBuyRow = mouseX >= buyColX
                    && mouseX < buyColX + COL_WIDTH
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT - 2;
            boolean hoverSellRow = mouseX >= sellColX
                    && mouseX < sellColX + COL_WIDTH
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT - 2;

            if (hoverBuyRow && offer.canBuy()) {
                hoveredRow = i;
                hoveredBuy = true;
                g.fill(buyColX, rowY,
                        buyColX + COL_WIDTH, rowY + ROW_HEIGHT - 2,
                        0x44FFFFFF);
            }
            if (hoverSellRow && offer.canSell()) {
                hoveredRow = i;
                hoveredBuy = false;
                g.fill(sellColX, rowY,
                        sellColX + COL_WIDTH, rowY + ROW_HEIGHT - 2,
                        0x44FFFFFF);
            }
        }

        // Render rows
        for (int i = startIdx; i < endIdx; i++) {
            TradeOffer offer = offers.get(i);
            int rowY = rowsStart + (i - startIdx) * ROW_HEIGHT;

            // --- BUY column ---
            // Item icon
            ItemStack buyIcon = new ItemStack(offer.item(),
                    Math.min(64, getSellCount(offer)));
            g.renderItem(buyIcon, buyColX + 2, rowY + 2);
            g.renderItemDecorations(font, buyIcon, buyColX + 2, rowY + 2, null);

            // Coin price row beside item
            renderCoinRow(g, offer.buyPrice(), buyColX + 22, rowY + 6);

            // --- SELL column ---
            // Item icon with quantity
            ItemStack sellIcon = offer.getIcon();
            g.renderItem(sellIcon, sellColX + 2, rowY + 2);

            // Coin price row beside item
            renderCoinRow(g, offer.sellPrice(), sellColX + 22, rowY + 6);
        }

        // Scrollbar
        if (offers.size() > ROWS_VISIBLE) {
            int sbX      = panelX + PANEL_WIDTH - 6;
            int sbTop    = rowsStart;
            int sbHeight = ROWS_VISIBLE * ROW_HEIGHT;
            int thumbH   = Math.max(10,
                    sbHeight * ROWS_VISIBLE / offers.size());
            int thumbY   = sbTop + scrollOffset
                    * (sbHeight - thumbH)
                    / Math.max(1, offers.size() - ROWS_VISIBLE);
            g.fill(sbX, sbTop, sbX + 4, sbTop + sbHeight, 0xFF444444);
            g.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, 0xFFAAAAAA);
        }

        super.render(g, mouseX, mouseY, pt);
    }

    /**
     * Renders coin icons in a row for a given bronze amount.
     * Only shows non-zero denominations.
     */
    private void renderCoinRow(GuiGraphics g, long bronze, int x, int y) {
        long gold   = bronze / CurrencyValue.GOLD_VALUE;
        long silver = (bronze % CurrencyValue.GOLD_VALUE) / CurrencyValue.SILVER_VALUE;
        long b      = bronze % CurrencyValue.SILVER_VALUE;

        int offsetX = x;

        if (gold > 0) {
            ItemStack goldStack = new ItemStack(ModItems.DENIER_OR.get(),
                    (int) Math.min(gold, 99));
            g.renderItem(goldStack, offsetX, y);
            g.renderItemDecorations(font, goldStack, offsetX, y, null);
            offsetX += 18;
        }

        if (silver > 0) {
            ItemStack silverStack = new ItemStack(ModItems.DENIER_ARGENT.get(),
                    (int) Math.min(silver, 99));
            g.renderItem(silverStack, offsetX, y);
            g.renderItemDecorations(font, silverStack, offsetX, y, null);
            offsetX += 18;
        }

        if (b > 0) {
            ItemStack bronzeStack = new ItemStack(ModItems.DENIER.get(),
                    (int) Math.min(b, 99));
            g.renderItem(bronzeStack, offsetX, y);
            g.renderItemDecorations(font, bronzeStack, offsetX, y, null);
        }
    }

    private int getSellCount(TradeOffer offer) {
        return offer.stockCount();
    }


    private void renderTradeRow(GuiGraphics g, TradeOffer offer,
                                int colX, int rowY, boolean isBuy) {
        boolean available = isBuy ? offer.canBuy() : offer.canSell();
        int textColor = available ? 0xFFFFFF : 0x666666;

        // Item icon first
        g.renderItem(offer.getIcon(), colX + 2, rowY + 3);
        g.renderItemDecorations(font, offer.getIcon(), colX + 2, rowY + 3, null);

        // Text after icons so it renders on top
        String name = offer.getIcon().getHoverName().getString();
        if (name.length() > 10) name = name.substring(0, 10) + "..";

        long price = isBuy ? offer.buyPrice() : offer.sellPrice();
        String priceStr = formatPrice(price);

        // Draw after super.render() call — move these to the second pass
        // by storing them and drawing after super.render()
    }

    private void renderCoinIndicator(GuiGraphics g, long bronze,
                                     int x, int y) {
        long gold   = bronze / CurrencyValue.GOLD_VALUE;
        long silver = (bronze % CurrencyValue.GOLD_VALUE)
                / CurrencyValue.SILVER_VALUE;

        // Tiny colored dot indicating highest denomination
        int color;
        if (gold > 0)        color = 0xFFD700; // gold
        else if (silver > 0) color = 0xC0C0C0; // silver
        else                 color = 0xCD7F32; // bronze

        g.fill(x, y, x + 4, y + 4, color);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        if (event.button() == 0 && hoveredRow >= 0
                && hoveredRow < offers.size()) {
            TradeOffer offer = offers.get(hoveredRow);
            Identifier itemId =
                    BuiltInRegistries.ITEM.getKey(offer.item());
            if (itemId != null) {
                // Shift = bulk trade up to 64
                boolean shift = event.hasShiftDown();
                int quantity = shift ? 64 : 1;

                net.minecraft.client.Minecraft.getInstance().getConnection()
                        .send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                                new TradeActionPacket(merchantId, itemId, hoveredBuy, quantity)
                        ));
            }
            return true;
        }
        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double dx, double dy) {
        scrollOffset = Math.max(0,
                Math.min(scrollOffset - (int) dy,
                        Math.max(0, offers.size() - ROWS_VISIBLE)));
        return true;
    }


    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) { // ESC
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private static String formatPrice(long bronze) {
        long g = bronze / CurrencyValue.GOLD_VALUE;
        long s = (bronze % CurrencyValue.GOLD_VALUE) / CurrencyValue.SILVER_VALUE;
        long b = bronze % CurrencyValue.SILVER_VALUE;

        StringBuilder sb = new StringBuilder();
        if (g > 0) sb.append(g).append("g ");
        if (s > 0) sb.append(s).append("s ");
        if (b > 0 || sb.isEmpty()) sb.append(b).append("b");
        return sb.toString().trim();
    }
}
