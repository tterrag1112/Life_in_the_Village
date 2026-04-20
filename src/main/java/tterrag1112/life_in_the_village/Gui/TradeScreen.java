package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Gui.Framework.Chrome;
import tterrag1112.life_in_the_village.Gui.Framework.CoinRow;
import tterrag1112.life_in_the_village.Gui.Framework.ScrollList;
import tterrag1112.life_in_the_village.Gui.Framework.TooltipLayer;
import tterrag1112.life_in_the_village.Networking.TradeActionPacket;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.TradeOffer;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class TradeScreen extends Screen {

    private static final int PANEL_WIDTH  = 320;
    private static final int PANEL_HEIGHT = 240;
    private static final int ROW_HEIGHT   = 24;
    private static final int ROWS_VISIBLE = 8;
    private static final int COL_WIDTH    = PANEL_WIDTH / 2 - 8;

    private static final Chrome.Dims DIMS = Chrome.Dims.of(PANEL_WIDTH, PANEL_HEIGHT, 0, 0);

    private record TradeRow(TradeOffer offer) {}

    private final UUID merchantId;
    private final String merchantName;
    private final List<TradeOffer> offers;
    private long playerWealth;

    private ScrollList<TradeRow> scrollList;
    private final TooltipLayer tooltips = new TooltipLayer();
    private int panelX, panelY;
    private int lastMouseX, lastMouseY;

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
    protected void init() {
        panelX = (width  - PANEL_WIDTH)  / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        int headerY   = panelY + 18;
        int rowsStart = headerY + 20;
        List<TradeRow> rows = offers.stream().map(TradeRow::new).collect(Collectors.toList());
        scrollList = new ScrollList<>(
                panelX + 4, rowsStart,
                PANEL_WIDTH - 8, ROWS_VISIBLE * ROW_HEIGHT,
                ROW_HEIGHT, rows, this::drawRow, this::onRowClick);
    }

    private void drawRow(GuiGraphics g, int rx, int ry, int rw, int rh,
                         TradeRow row, boolean hovered) {
        TradeOffer offer = row.offer();
        int halfW  = rw / 2;
        int sellX  = rx + halfW;

        if (hovered) {
            boolean overSell = lastMouseX >= sellX;
            if (overSell && offer.canSell())
                g.fill(sellX, ry, rx + rw, ry + rh, 0x44FFFFFF);
            else if (!overSell && offer.canBuy())
                g.fill(rx, ry, sellX, ry + rh, 0x44FFFFFF);
        }

        // Buy column: item icon + price
        ItemStack buyIcon = new ItemStack(offer.item(), Math.min(64, getSellCount(offer)));
        g.renderItem(buyIcon, rx + 2, ry + 2);
        g.renderItemDecorations(font, buyIcon, rx + 2, ry + 2, null);
        CoinRow.draw(g, offer.buyPrice(), rx + 22, ry + 6);

        // Sell column: icon + price
        ItemStack sellIcon = offer.getIcon();
        g.renderItem(sellIcon, sellX + 2, ry + 2);
        CoinRow.draw(g, offer.sellPrice(), sellX + 22, ry + 6);
    }

    private boolean onRowClick(TradeRow row, int button, double relX, double relY) {
        if (button != 0) return false;
        TradeOffer offer = row.offer();
        boolean isBuying = relX < PANEL_WIDTH / 2.0;
        if (isBuying && !offer.canBuy()) return false;
        if (!isBuying && !offer.canSell()) return false;
        Identifier itemId = BuiltInRegistries.ITEM.getKey(offer.item());
        if (itemId == null) return false;
        int quantity = Screen.hasShiftDown() ? 64 : 1;
        PacketDistributor.sendToServer(
                new TradeActionPacket(merchantId, itemId, isBuying, quantity));
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        tooltips.reset();
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        int headerY   = panelY + 18;
        int rowsStart = headerY + 20;
        int buyColX   = panelX + 4;
        int sellColX  = panelX + PANEL_WIDTH / 2 + 4;

        // Dim background
        g.fill(0, 0, width, height, 0xC0000000);

        // Chrome
        Chrome.draw(g, panelX, panelY, DIMS, Chrome.DARK_TRADE);

        // Title
        g.drawCenteredString(font, merchantName,
                panelX + PANEL_WIDTH / 2, panelY + 5, 0xFFFFFF);

        // Column header backgrounds
        g.fill(buyColX,  headerY, buyColX  + COL_WIDTH, headerY + 14, 0xFF1A4A1A);
        g.fill(sellColX, headerY, sellColX + COL_WIDTH, headerY + 14, 0xFF4A1A1A);

        // Header icons
        ItemStack buyHeader  = new ItemStack(Items.EMERALD);
        ItemStack sellHeader = new ItemStack(Items.EMERALD);
        g.renderItem(buyHeader,  buyColX  + COL_WIDTH / 2 - 8, headerY - 1);
        g.renderItem(sellHeader, sellColX + COL_WIDTH / 2 - 8, headerY - 1);

        // Centre divider
        g.fill(panelX + PANEL_WIDTH / 2, headerY,
                panelX + PANEL_WIDTH / 2 + 1,
                panelY + PANEL_HEIGHT - 16, 0xFF444444);

        // Wallet
        CoinRow.draw(g, playerWealth, panelX + 4, panelY + PANEL_HEIGHT - 18);

        // Trade rows
        scrollList.render(g, mouseX, mouseY);

        super.render(g, mouseX, mouseY, pt);
        tooltips.flush(g);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        if (scrollList != null
                && scrollList.mouseClicked(lastMouseX, lastMouseY, event.button()))
            return true;
        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double dx, double dy) {
        return scrollList != null && scrollList.mouseScrolled(mouseX, mouseY, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) { // ESC
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private int getSellCount(TradeOffer offer) {
        return offer.stockCount();
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
