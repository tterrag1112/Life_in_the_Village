package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import tterrag1112.life_in_the_village.Gui.Framework.Chrome;
import tterrag1112.life_in_the_village.Gui.Framework.StyledButton;
import tterrag1112.life_in_the_village.Networking.OpenStallLeasePacket;
import tterrag1112.life_in_the_village.Networking.StallLeaseActionPacket;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStallPlacer;

import java.util.UUID;

/**
 * Compact lease-purchase screen. Shows three options (1 week, 4 weeks,
 * outright purchase) plus a "bind to this market" toggle (Q1 — leases
 * always bound to player; this option toggles per-market binding so
 * the lease can only be redeemed at this specific market).
 */
public class StallLeaseScreen extends Screen {

    private static final int PANEL_W = 220;
    private static final int PANEL_H = 180;
    private static final Chrome.Dims DIMS = Chrome.Dims.of(PANEL_W, PANEL_H, 0, 6);

    private final UUID merchantId;
    private final String merchantName;

    private boolean bindToMarket = false;

    public StallLeaseScreen(OpenStallLeasePacket data) {
        super(Component.literal("Rent a Stall"));
        this.merchantId = data.merchantId();
        this.merchantName = data.merchantName();
    }

    public static void openOnClient(OpenStallLeasePacket data) {
        Minecraft.getInstance().setScreen(new StallLeaseScreen(data));
    }

    @Override public boolean isPauseScreen() { return false; }

    private int panelX, panelY;

    @Override
    protected void init() {
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;
        int x = panelX + 16;
        int y = panelY + 36;

        long week = MarketStallPlacer.RENT_PER_DAY * 7L;
        long month = MarketStallPlacer.RENT_PER_DAY * 28L;
        long buy = MarketStallPlacer.PURCHASE_PRICE;

        addRenderableWidget(StyledButton.builder(
                        Component.literal("Lease 7 days — " + week + " br"),
                        b -> submit(7))
                .pos(x, y).size(PANEL_W - 32, 20).build());
        y += 26;
        addRenderableWidget(StyledButton.builder(
                        Component.literal("Lease 28 days — " + month + " br"),
                        b -> submit(28))
                .pos(x, y).size(PANEL_W - 32, 20).build());
        y += 26;
        addRenderableWidget(StyledButton.builder(
                        Component.literal("Purchase outright — " + buy + " br"),
                        b -> submit(0))
                .pos(x, y).size(PANEL_W - 32, 20).build());
        y += 32;
        addRenderableWidget(StyledButton.builder(
                        Component.literal("Bind to this market: " + (bindToMarket ? "Yes" : "No")),
                        b -> { bindToMarket = !bindToMarket; clearWidgets(); init(); })
                .pos(x, y).size(PANEL_W - 32, 18).build());

        addRenderableWidget(StyledButton.builder(
                        Component.literal("Close"),
                        b -> onClose())
                .pos(panelX + PANEL_W - 68, panelY + PANEL_H - 22)
                .size(60, 18).build());
    }

    private void submit(int durationDays) {
        ClientPacketDistributor.sendToServer(
                new StallLeaseActionPacket(merchantId, durationDays, bindToMarket));
        onClose();
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partial) {
        gfx.fill(0, 0, width, height, 0x88000000);
        Chrome.draw(gfx, panelX, panelY, DIMS, Chrome.PARCHMENT);
        gfx.drawString(font, "Rent a Stall — " + merchantName,
                panelX + 8, panelY + 6, BookScreenColors.DARK, false);
        gfx.drawString(font, "Right-click the merchant later with the lease to claim.",
                panelX + 8, panelY + PANEL_H - 40, BookScreenColors.LIGHT, false);
        super.render(gfx, mouseX, mouseY, partial);
    }
}
