package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import tterrag1112.life_in_the_village.Gui.Framework.Chrome;
import tterrag1112.life_in_the_village.Gui.Framework.CoinRow;
import tterrag1112.life_in_the_village.Gui.Framework.Pill;
import tterrag1112.life_in_the_village.Gui.Framework.ScrollList;
import tterrag1112.life_in_the_village.Gui.Framework.TooltipLayer;
import tterrag1112.life_in_the_village.Networking.OpenTradeScreenPacket;
import tterrag1112.life_in_the_village.Networking.TradeActionPacket;
import tterrag1112.life_in_the_village.Village.Economy.Currency.TradeOffer;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Redesigned trade screen (merchant arc Phase 5a). Two <b>independent</b>
 * lists — "Buy from …" (what the NPC stocks) and "Sell to …" (what the NPC
 * will buy that you hold) — on the current Gui.Framework dark-trade chrome,
 * with an adaptive header (NPC + role + stall owner + reputation tier) and
 * per-row provenance (village price, custom-price flag, stock).
 */
public class TradeScreen extends Screen {

    private static final int PANEL_WIDTH  = 340;
    private static final int PANEL_HEIGHT = 240;
    private static final int ROW_HEIGHT   = 22;
    private static final int HEADER_H     = 40;
    private static final int COL_GAP      = 8;

    private static final int CUSTOM_PILL_BG  = 0xFF5A3A1A;
    private static final int CUSTOM_PILL_TXT = 0xFFFFD27F;

    private static final Chrome.Dims DIMS = Chrome.Dims.of(PANEL_WIDTH, PANEL_HEIGHT, 0, 0);

    /** A row is tagged with its side so the click handler dispatches the
     *  correct buy/sell action without re-deriving it from geometry. */
    private record TradeRow(TradeOffer offer, boolean buySide) {}

    private final UUID merchantId;
    private final String merchantName;
    private final String npcRole;
    private final String villageName;
    private final String stallOwner;
    private final String repTier;
    private final int repDiscountPct;
    private final List<TradeOffer> buyOffers;
    private final List<TradeOffer> sellOffers;
    private long playerWealth;

    private ScrollList<TradeRow> buyList;
    private ScrollList<TradeRow> sellList;
    private final TooltipLayer tooltips = new TooltipLayer();
    private int panelX, panelY;
    private int lastMouseX, lastMouseY;
    private boolean shiftHeld = false;

    public TradeScreen(OpenTradeScreenPacket pkt) {
        super(Component.literal(pkt.merchantName()));
        this.merchantId     = pkt.merchantId();
        this.merchantName   = pkt.merchantName();
        this.npcRole        = pkt.npcRole();
        this.villageName    = pkt.villageName();
        this.stallOwner     = pkt.stallOwner();
        this.repTier        = pkt.repTier();
        this.repDiscountPct = pkt.repDiscountPct();
        this.buyOffers      = pkt.buyOffers();
        this.sellOffers     = pkt.sellOffers();
        this.playerWealth   = pkt.playerWealth();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        panelX = (width  - PANEL_WIDTH)  / 2;
        panelY = (height - PANEL_HEIGHT) / 2;

        int colW       = (PANEL_WIDTH - COL_GAP * 3) / 2;
        int listY      = panelY + HEADER_H + 12;
        int listH      = PANEL_HEIGHT - HEADER_H - 12 - 22;
        int buyX       = panelX + COL_GAP;
        int sellX      = panelX + COL_GAP * 2 + colW;

        List<TradeRow> buyRows = buyOffers.stream()
                .map(o -> new TradeRow(o, true)).collect(Collectors.toList());
        List<TradeRow> sellRows = sellOffers.stream()
                .map(o -> new TradeRow(o, false)).collect(Collectors.toList());

        buyList = new ScrollList<>(buyX, listY, colW, listH, ROW_HEIGHT,
                buyRows, this::drawRow, this::onRowClick);
        sellList = new ScrollList<>(sellX, listY, colW, listH, ROW_HEIGHT,
                sellRows, this::drawRow, this::onRowClick);
    }

    private void drawRow(GuiGraphics g, int rx, int ry, int rw, int rh,
                         TradeRow row, boolean hovered) {
        TradeOffer offer = row.offer();
        if (hovered) g.fill(rx, ry, rx + rw, ry + rh, 0x44FFFFFF);

        ItemStack icon = offer.getIcon();
        g.renderItem(icon, rx + 2, ry + 2);
        g.renderItemDecorations(font, icon, rx + 2, ry + 2, null);

        // Price for this side (buy rows show buyPrice; sell rows sellPrice).
        long price = row.buySide() ? offer.buyPrice() : offer.sellPrice();
        CoinRow.draw(g, price, rx + 22, ry + 2);

        // Second line: item name + provenance.
        String name = offer.item().getName().getString();
        g.drawString(font, name, rx + 22, ry + 12, 0xFFCCCCCC, false);

        if (row.buySide()) {
            // Stock count, right-aligned.
            String stock = "x" + offer.stockCount();
            g.drawString(font, stock, rx + rw - font.width(stock) - 4, ry + 2,
                    0xFF88CC88, false);
            // Custom-price flag.
            if (offer.customPriced()) {
                Pill.draw(g, font, rx + rw - Pill.width(font, "set price") - 4,
                        ry + 11, "set price", CUSTOM_PILL_BG, CUSTOM_PILL_TXT);
            }
        }
    }

    private boolean onRowClick(TradeRow row, int button, double relX, double relY) {
        if (button != 0) return false;
        TradeOffer offer = row.offer();
        Identifier itemId = BuiltInRegistries.ITEM.getKey(offer.item());
        if (itemId == null) return false;
        int quantity = shiftHeld ? 64 : 1;
        ClientPacketDistributor.sendToServer(
                new TradeActionPacket(merchantId, itemId, row.buySide(), quantity));
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        tooltips.reset();
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        int colW  = (PANEL_WIDTH - COL_GAP * 3) / 2;
        int buyX  = panelX + COL_GAP;
        int sellX = panelX + COL_GAP * 2 + colW;
        int colHeaderY = panelY + HEADER_H - 2;

        // Dim background, then chrome.
        g.fill(0, 0, width, height, 0xC0000000);
        Chrome.draw(g, panelX, panelY, DIMS, Chrome.DARK_TRADE);

        // ── Adaptive header ───────────────────────────────────────────────
        g.drawString(font, merchantName, panelX + 8, panelY + 6, 0xFFFFFFFF, false);
        String roleLine = npcRole.isEmpty() ? "" : npcRole;
        if (!villageName.isEmpty()) {
            roleLine = roleLine.isEmpty() ? villageName : roleLine + " · " + villageName;
        }
        if (!roleLine.isEmpty()) {
            g.drawString(font, roleLine, panelX + 8, panelY + 17, 0xFFAAAAAA, false);
        }
        // Stall owner (only when wares come from a stall).
        if (!stallOwner.isEmpty()) {
            String s = "Stall: " + stallOwner;
            g.drawString(font, s, panelX + PANEL_WIDTH - font.width(s) - 8,
                    panelY + 6, 0xFFD0B070, false);
        }
        // Reputation tier + discount effect.
        if (!repTier.isEmpty()) {
            String r = repDiscountPct > 0
                    ? repTier + " (-" + repDiscountPct + "%)"
                    : repTier;
            g.drawString(font, r, panelX + PANEL_WIDTH - font.width(r) - 8,
                    panelY + 17, 0xFF90C090, false);
        }

        // ── Column headers ────────────────────────────────────────────────
        g.fill(buyX,  colHeaderY, buyX  + colW, colHeaderY + 12, 0xFF1A4A1A);
        g.fill(sellX, colHeaderY, sellX + colW, colHeaderY + 12, 0xFF4A1A1A);
        g.drawString(font, "Buy from " + merchantName, buyX + 3, colHeaderY + 2,
                0xFFFFFFFF, false);
        g.drawString(font, "Sell to " + merchantName, sellX + 3, colHeaderY + 2,
                0xFFFFFFFF, false);

        // ── Lists ─────────────────────────────────────────────────────────
        buyList.render(g, mouseX, mouseY);
        sellList.render(g, mouseX, mouseY);

        // Empty-state hints.
        if (buyOffers.isEmpty()) {
            g.drawString(font, "Nothing in stock", buyX + 4,
                    panelY + HEADER_H + 16, 0xFF777777, false);
        }
        if (sellOffers.isEmpty()) {
            g.drawString(font, "Wants nothing you carry", sellX + 4,
                    panelY + HEADER_H + 16, 0xFF777777, false);
        }

        // Wallet + shift hint.
        CoinRow.draw(g, playerWealth, panelX + 8, panelY + PANEL_HEIGHT - 16);
        String hint = "Shift = x64";
        g.drawString(font, hint, panelX + PANEL_WIDTH - font.width(hint) - 8,
                panelY + PANEL_HEIGHT - 14, 0xFF888888, false);

        super.render(g, mouseX, mouseY, pt);
        tooltips.flush(g);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        shiftHeld = event.hasShiftDown();
        if (buyList != null
                && buyList.mouseClicked(lastMouseX, lastMouseY, event.button())) {
            return true;
        }
        if (sellList != null
                && sellList.mouseClicked(lastMouseX, lastMouseY, event.button())) {
            return true;
        }
        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double dx, double dy) {
        if (buyList != null && buyList.mouseScrolled(mouseX, mouseY, dy)) return true;
        if (sellList != null && sellList.mouseScrolled(mouseX, mouseY, dy)) return true;
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) { // ESC
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}
