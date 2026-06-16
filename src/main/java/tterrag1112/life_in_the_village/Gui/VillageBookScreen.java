package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Gui.Commissions.CommissionBoardPanel;
import tterrag1112.life_in_the_village.Gui.Framework.*;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Networking.*;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.HousePurchaseManager;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Economy.CraftingOrder;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoute;
import tterrag1112.life_in_the_village.Village.Simulation.ResourceCategory;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import java.util.*;

public class VillageBookScreen extends Screen {

    private static final int BOOK_W    = 420;
    private static final int BOOK_H    = 300;
    private static final int SIDEBAR_W = 130;
    private static final int PAGE_PAD  = 14;
    private static final int ROW_H     = 28;
    private static final int MAP_W     = 220;
    private static final int MAP_H     = 180;

    private enum Section {
        OVERVIEW, ECONOMY, HOUSING, COMPANY_BUILDINGS, STATISTICS, COMMISSIONS, MAP, STANDINGS
    }

    private enum PurchaseAction { BUY_HOUSE, BUY_COMPANY_BUILDING }

    private record PurchaseRow(
            UUID   buildingId,
            String name,
            long   priceBronze,
            long   taxOrFeePerWeek,
            String taxLabel,
            RowStatus status
    ) {}

    private enum RowStatus {
        OWNED_BY_PLAYER, OWNED_BY_OTHER, OCCUPIED_BY_NPC,
        IN_COMPANY, AVAILABLE, CANT_AFFORD
    }

    private final OpenVillageBookPacket data;
    private int bookX, bookY;
    private Section currentSection = Section.OVERVIEW;
    private final VillageMapRenderer mapRenderer = new VillageMapRenderer();
    private Sidebar<Section> sidebar;
    private ScrollList<PurchaseRow> purchaseList;
    private CommissionBoardPanel commissionPanel;

    public VillageBookScreen(OpenVillageBookPacket data) {
        super(Component.literal("Village — " + data.villageName()));
        this.data = data;
        if (!data.openSection().isEmpty()) {
            try { currentSection = Section.valueOf(data.openSection()); }
            catch (IllegalArgumentException ignored) {}
        }
    }

    @Override
    protected void init() {
        bookX = (width  - BOOK_W) / 2;
        bookY = (height - BOOK_H) / 2;
        buildWidgets();
        startMapBake();
    }

    @Override
    public void onClose() {
        mapRenderer.close();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void tick() {
        super.tick();
        mapRenderer.tick();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88000000);
        Chrome.draw(g, bookX, bookY, Chrome.BOOK, Chrome.PARCHMENT);
        Chrome.drawSidebarBg(g, bookX, bookY, Chrome.BOOK);
        drawSidebarContent(g);
        sidebar.render(g, mx, my);
        drawPageChrome(g);
        drawPageContent(g, mx, my);
        super.render(g, mx, my, pt);
    }

    private void drawSidebarContent(GuiGraphics g) {
        g.drawString(font, "♚ " + data.villageName(),
                bookX + 8, bookY + 10, BookScreenColors.DARK, false);
        g.fill(bookX + 4, bookY + 22, bookX + SIDEBAR_W - 4, bookY + 23,
                BookScreenColors.BORDER);
    }

    private void drawPageChrome(GuiGraphics g) {
        g.fill(bookX + SIDEBAR_W, bookY + 28,
                bookX + BOOK_W, bookY + 29, BookScreenColors.BORDER);
        g.fill(bookX + SIDEBAR_W, bookY + BOOK_H - 30,
                bookX + BOOK_W, bookY + BOOK_H - 29, BookScreenColors.BORDER);
        g.drawString(font, sectionLabel(currentSection),
                bookX + SIDEBAR_W + PAGE_PAD, bookY + 10, BookScreenColors.DARK, false);
        drawFlourish(g, bookX + 4,           bookY + 4);
        drawFlourish(g, bookX + BOOK_W - 12, bookY + 4);
        drawFlourish(g, bookX + 4,           bookY + BOOK_H - 12);
        drawFlourish(g, bookX + BOOK_W - 12, bookY + BOOK_H - 12);
    }

    private void drawFlourish(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 8, y + 1, BookScreenColors.BORDER);
        g.fill(x, y, x + 1, y + 8, BookScreenColors.BORDER);
    }

    private void buildWidgets() {
        clearWidgets();
        purchaseList = null;

        sidebar = new Sidebar<>(bookX + 2, bookY + 30, SIDEBAR_W - 2, 18,
                List.of(
                        new Sidebar.Entry<>(Section.OVERVIEW,     "Overview",     true),
                        new Sidebar.Entry<>(Section.ECONOMY,      "Economy",      true),
                        new Sidebar.Entry<>(Section.HOUSING,      "Housing",      true),
                        new Sidebar.Entry<>(Section.STATISTICS,   "Statistics",   true),
                        new Sidebar.Entry<>(Section.COMMISSIONS,  "Commissions",  true),
                        new Sidebar.Entry<>(Section.MAP,          "Map",          true),
                        new Sidebar.Entry<>(Section.STANDINGS,    "My Standing",  true)
                ),
                () -> currentSection == Section.COMPANY_BUILDINGS
                        ? Section.STANDINGS : currentSection,
                s -> { currentSection = s; buildWidgets(); });

        int px   = bookX + SIDEBAR_W + PAGE_PAD;
        int py   = bookY + 36;
        int pw   = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;
        int maxY = bookY + BOOK_H - 34;

        switch (currentSection) {
            case HOUSING ->
                    buildPurchaseSection(px, py, pw, maxY,
                            housePurchaseRows(), PurchaseAction.BUY_HOUSE, true);
            case COMPANY_BUILDINGS ->
                    buildPurchaseSection(px, py + 36, pw, maxY,
                            companyBuildingRows(), PurchaseAction.BUY_COMPANY_BUILDING, false);
            case STANDINGS -> buildStandingsWidgets(px, py, pw, maxY);
            default -> {}
        }
    }

    private void buildStandingsWidgets(int px, int py, int pw, int maxY) {
        boolean isTownPlus = data.tierName().equals("Town") || data.tierName().equals("City");
        if (!isTownPlus) return;
        int btnY = computeCompanyButtonY(py, maxY);
        if (btnY < 0) return;

        if (data.hasCompanyHere()) {
            addRenderableWidget(StyledButton.builder(
                            Component.literal("Manage Business"),
                            b -> ClientPacketDistributor.sendToServer(new BusinessActionPacket(
                                    BusinessActionPacket.ActionType.OPEN_MANAGEMENT,
                                    data.businessId(), new UUID(0, 0), "", 0L, 0)))
                    .pos(px, btnY).size(pw / 2 - 2, 16).build());
            addRenderableWidget(StyledButton.builder(
                            Component.literal("Buy Building"),
                            b -> { currentSection = Section.COMPANY_BUILDINGS; buildWidgets(); })
                    .pos(px + pw / 2 + 2, btnY).size(pw / 2 - 2, 16).build());
        } else {
            addRenderableWidget(StyledButton.builder(
                            Component.literal("Found a Business"),
                            b -> ClientPacketDistributor.sendToServer(new BusinessActionPacket(
                                    BusinessActionPacket.ActionType.FOUND_COMPANY,
                                    UUID.randomUUID(), data.villageId(), "My Business", 0L, 0)))
                    .pos(px, btnY).size(pw, 16).build());
        }
    }

    private void buildPurchaseSection(int px, int py, int pw, int maxY,
                                       List<PurchaseRow> rows,
                                       PurchaseAction action, boolean showTax) {
        int listY = py + 27;
        int listH = maxY - listY;
        purchaseList = new ScrollList<>(px, listY, pw, listH, ROW_H + 2, rows,
                (g, rowX, rowY, rowW, rowH, row, hovered) ->
                        drawPurchaseRow(g, rowX, rowY, rowW, row, showTax,
                                data.playerWealthBronze()),
                (row, btn, relX, relY) -> {
                    if (row.status() != RowStatus.AVAILABLE
                            && row.status() != RowStatus.CANT_AFFORD) return false;
                    if (relX < pw - 34) return false;
                    sendPurchase(action, row.buildingId());
                    return true;
                });
    }

    private void sendPurchase(PurchaseAction action, UUID buildingId) {
        switch (action) {
            case BUY_HOUSE ->
                    ClientPacketDistributor.sendToServer(new VillageActionPacket(
                            VillageActionPacket.ActionType.BUY_HOUSE,
                            data.villageId(), buildingId, "", 0));
            case BUY_COMPANY_BUILDING ->
                    ClientPacketDistributor.sendToServer(new BusinessActionPacket(
                            BusinessActionPacket.ActionType.BUY_COMPANY_BUILDING,
                            data.businessId(), buildingId, "", 0L, 0));
        }
    }

    private void drawPageContent(GuiGraphics g, int mx, int my) {
        int px   = bookX + SIDEBAR_W + PAGE_PAD;
        int py   = bookY + 36;
        int pw   = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;
        int maxY = bookY + BOOK_H - 34;
        switch (currentSection) {
            case OVERVIEW          -> drawOverview(g, px, py, pw, maxY);
            case ECONOMY           -> drawEconomy(g, px, py, pw, maxY);
            case HOUSING           -> drawPurchaseSectionHeader(g, px, py, pw, "Your wallet:", true);
            case COMPANY_BUILDINGS -> drawCompanyBuildingsPage(g, px, py, pw, maxY);
            case STATISTICS        -> drawStatistics(g, px, py, pw, maxY);
            case COMMISSIONS       -> drawCommissions(g, px, py, pw, maxY - py, mx, my);
            case MAP               -> drawMap(g, px, py, pw, maxY, mx, my);
            case STANDINGS         -> drawStandings(g, px, py, pw, maxY);
        }
        if (purchaseList != null) purchaseList.render(g, mx, my);
    }

    private void drawOverview(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;

        // Village name / kingdom banner
        g.fill(px, y, px + pw, y + 18, BookScreenColors.HIGHLIGHT);
        g.renderOutline(px, y, pw, 18, BookScreenColors.BORDER);
        if (!data.kingdomName().isEmpty()) {
            g.drawCenteredString(font, data.villageName(),
                    px + pw / 2, y + 2, BookScreenColors.DARK);
            g.drawCenteredString(font, "Kingdom of " + data.kingdomName(),
                    px + pw / 2, y + 10, BookScreenColors.MID);
        } else {
            g.drawCenteredString(font, data.villageName(),
                    px + pw / 2, y + 5, BookScreenColors.DARK);
        }
        y += 22;

        // Active event
        if (!data.activeEventName().isEmpty()) {
            g.fill(px, y, px + pw, y + 12, 0xFFFFEECC);
            g.renderOutline(px, y, pw, 12, BookScreenColors.AMBER);
            g.drawString(font, "★ " + data.activeEventName(),
                    px + 3, y + 2, BookScreenColors.AMBER, false);
            y += 16;
        }

        // Open orders banner
        if (data.openOrderCount() > 0) {
            g.fill(px, y, px + pw, y + 12, 0xFFEEFFEE);
            g.renderOutline(px, y, pw, 12, BookScreenColors.BORDER);
            g.drawString(font,
                    "✉ " + data.openOrderCount() + " open commission"
                            + (data.openOrderCount() > 1 ? "s" : "") + " — sneak+talk to leader",
                    px + 3, y + 2, BookScreenColors.GREEN_TXT, false);
            y += 16;
        }

        // Stat grid — 2 columns
        int colW = pw / 2 - 4;
        record Stat(String label, String value) {}
        Stat[] stats = {
                new Stat("Leader",     data.leaderName().isEmpty() ? "None" : data.leaderName()),
                new Stat("Tier",       data.tierName()),
                new Stat("Population", String.valueOf(data.population())),
                new Stat("Treasury",   CoinRenderer.format(data.treasuryBronze())),
                new Stat("Routes",     data.tradeRouteCount() + " active"),
                new Stat("Buildings",  String.valueOf(data.buildingTypes().size())),
                new Stat("Season",     data.seasonName()),
        };
        for (int i = 0; i < stats.length; i += 2) {
            if (y + 24 > maxY) break;
            StatBox.draw(g, font, px,          y, colW, 22, stats[i].label(),     stats[i].value());
            if (i + 1 < stats.length)
                StatBox.draw(g, font, px + colW + 4, y, colW, 22, stats[i + 1].label(), stats[i + 1].value());
            y += 26;
        }

        // Needs dots
        y += 4;
        if (y + 12 > maxY) return;
        g.drawString(font, "Needs:", px, y, BookScreenColors.MID, false);
        NeedMeter.dots(g, px + 44, y + 2, data.needs());
        y += 14;

        // Expansion queue
        if (!data.pendingExpansion().isEmpty() && y + 14 < maxY) {
            g.fill(px, y, px + pw, y + 12, BookScreenColors.BLUE_BG);
            g.renderOutline(px, y, pw, 12, BookScreenColors.BORDER);
            g.drawString(font, "→ Expanding: " + data.pendingExpansion(),
                    px + 3, y + 2, 0xFF2255AA, false);
        }
    }

    /**
     * Phase 5c — village economy: treasury + net income (StatBox), needs
     * (NeedMeter dots), and per-{@code ResourceCategory} surplus/deficit
     * rows (green "Exporter" / red "Importer" pills). Read-only.
     */
    private void drawEconomy(GuiGraphics g, int px, int py, int pw, int maxY) {
        var econ = data.economy();
        int y = py;

        g.drawString(font, "Economy", px, y, BookScreenColors.DARK, false);
        y += 14;

        // Treasury + net income/day tiles.
        int boxW = pw / 2 - 4;
        StatBox.draw(g, font, px, y, boxW, 22, "Treasury",
                CoinRenderer.format(econ.treasuryBronze()));
        String income = econ.hasSim()
                ? String.format("%+.0f/day", econ.netIncomePerDay())
                : "—";
        StatBox.draw(g, font, px + boxW + 8, y, boxW, 22, "Net income", income);
        y += 28;

        // Needs row.
        g.drawString(font, "Needs:", px, y, BookScreenColors.MID, false);
        NeedMeter.dots(g, px + 44, y + 2, data.needs());
        y += 16;

        // Per-category surplus / deficit.
        g.drawString(font, "Surplus / Deficit", px, y, BookScreenColors.MID, false);
        y += 12;
        if (!econ.hasSim() || econ.categories().isEmpty()) {
            g.drawString(font, "No economic activity recorded yet.",
                    px + 2, y, BookScreenColors.LIGHT, false);
            return;
        }
        for (var cn : econ.categories()) {
            if (y + 14 > maxY) break;
            String label = cn.category().charAt(0)
                    + cn.category().substring(1).toLowerCase().replace('_', ' ');
            g.drawString(font, label, px + 2, y + 2, BookScreenColors.DARK, false);
            boolean exporter = cn.net() > 0f;
            boolean neutral = cn.net() == 0f;
            String tag = neutral ? "Balanced"
                    : (exporter ? "Exporter +" : "Importer ")
                            + String.format("%.0f", cn.net());
            int bg = neutral ? 0xFF555540 : (exporter ? 0xFF1A4A1A : 0xFF4A1A1A);
            int tx = neutral ? 0xFFCCCCCC : (exporter ? 0xFF90E090 : 0xFFE09090);
            Pill.draw(g, font, px + pw - Pill.width(font, tag) - 2, y, tag, bg, tx);
            y += 14;
        }
    }

    private void drawPurchaseSectionHeader(GuiGraphics g, int px, int py,
                                            int pw, String walletLabel,
                                            boolean showTax) {
        int y = py;
        // Wallet banner
        g.fill(px, y, px + pw, y + 12, BookScreenColors.HIGHLIGHT);
        g.renderOutline(px, y, pw, 12, BookScreenColors.BORDER);
        g.drawString(font, walletLabel, px + 3, y + 2, BookScreenColors.GOLD, false);
        int afterWallet = px + 3 + font.width(walletLabel) + 4;
        CoinRow.draw(g, data.playerWealthBronze(), afterWallet, y);
        y += 14;
        // Column headers
        g.fill(px, y, px + pw, y + 11, 0xFFE8E0C8);
        g.drawString(font, "Building", px + 2,       y + 2, BookScreenColors.MID, false);
        g.drawString(font, "Price",    px + pw / 2,  y + 2, BookScreenColors.MID, false);
        if (showTax)
            g.drawString(font, "Tax/wk", px + pw - 80, y + 2, BookScreenColors.MID, false);
        g.drawString(font, "Status",   px + pw - 32, y + 2, BookScreenColors.MID, false);

        // Empty-list message rendered here (ScrollList won't draw anything)
        List<PurchaseRow> rows = showTax ? housePurchaseRows() : companyBuildingRows();
        if (rows.isEmpty()) {
            g.drawCenteredString(font, "Nothing available to purchase.",
                    px + pw / 2, py + 80, BookScreenColors.MID);
        }
    }

    private void drawCompanyBuildingsPage(GuiGraphics g, int px, int py,
                                           int pw, int maxY) {
        g.drawString(font, "← My Standing › Buy Building",
                px, py - 14, BookScreenColors.LIGHT, false);
        if (!data.companyBuildingNames().isEmpty()) {
            int y = py;
            g.drawString(font, data.businessName() + " owns:",
                    px, y, BookScreenColors.MID, false);
            y += 10;
            for (String bname : data.companyBuildingNames()) {
                if (y + 10 > py + 32) { g.drawString(font, "…", px + 80, y, BookScreenColors.LIGHT, false); break; }
                g.drawString(font, "  • " + bname, px, y, BookScreenColors.DARK, false);
                y += 10;
            }
        }
        drawPurchaseSectionHeader(g, px, py + 36, pw, "Treasury:", false);
    }

    private void drawPurchaseRow(GuiGraphics g, int rowX, int rowY, int rowW,
                                 PurchaseRow row, boolean showTax, long wealth) {
        int rowBg = switch (row.status()) {
            case OWNED_BY_PLAYER -> BookScreenColors.GREEN_BG;
            case OWNED_BY_OTHER  -> BookScreenColors.RED_BG;
            case IN_COMPANY      -> 0xFFCCE8E8;
            case OCCUPIED_BY_NPC -> 0xFFEEEECC;
            default              -> BookScreenColors.PARCHMENT;
        };
        g.fill(rowX, rowY, rowX + rowW, rowY + ROW_H, rowBg);
        g.renderOutline(rowX, rowY, rowW, ROW_H, BookScreenColors.BORDER);

        // Name — truncate if needed
        String name = row.name();
        int maxNameW = rowW / 2 - 6;
        while (font.width(name) > maxNameW && name.length() > 4)
            name = name.substring(0, name.length() - 1);
        if (!name.equals(row.name())) name += "…";
        g.drawString(font, name, rowX + 3, rowY + (ROW_H - 8) / 2,
                BookScreenColors.DARK, false);

        // Price
        CoinRow.draw(g, row.priceBronze(), rowX + rowW / 2, rowY + (ROW_H - 16) / 2);

        // Tax column (housing only)
        if (showTax && row.taxOrFeePerWeek() > 0)
            CoinRow.draw(g, row.taxOrFeePerWeek(), rowX + rowW - 80, rowY + (ROW_H - 16) / 2);

        // Right action: "Buy" button visual or status label
        if (row.status() == RowStatus.AVAILABLE || row.status() == RowStatus.CANT_AFFORD) {
            int bx = rowX + rowW - 34, by = rowY + (ROW_H - 14) / 2;
            g.fill(bx, by, bx + 28, by + 14, BookScreenColors.HIGHLIGHT);
            g.renderOutline(bx, by, 28, 14, BookScreenColors.BORDER);
            int tw = font.width("Buy");
            g.drawString(font, "Buy", bx + (28 - tw) / 2, by + 3,
                    BookScreenColors.DARK, false);
        } else {
            String sl = switch (row.status()) {
                case OWNED_BY_PLAYER -> "Yours";
                case OWNED_BY_OTHER  -> "Owned";
                case IN_COMPANY      -> "Business";
                case OCCUPIED_BY_NPC -> "Occupied";
                default              -> "";
            };
            int sc = switch (row.status()) {
                case OWNED_BY_PLAYER -> BookScreenColors.GREEN_TXT;
                case OWNED_BY_OTHER  -> BookScreenColors.RED_TXT;
                case IN_COMPANY      -> 0xFF1A7A7A;
                case OCCUPIED_BY_NPC -> BookScreenColors.AMBER;
                default              -> BookScreenColors.MID;
            };
            g.drawString(font, sl, rowX + rowW - 32, rowY + (ROW_H - 8) / 2, sc, false);
        }
    }

    private void drawStatistics(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;
        g.drawString(font, "Needs", px, y, BookScreenColors.MID, false);
        g.fill(px, y + 10, px + pw, y + 11, BookScreenColors.BORDER);
        y += 14;

        int labelW = 70, barW = pw - labelW - 44;
        for (var entry : data.needs().entrySet()) {
            if (y + 11 > maxY) break;
            g.drawString(font, entry.getKey(), px, y, BookScreenColors.DARK, false);
            NeedMeter.bar(g, px + labelW, y + 1, barW, 8,
                    needFraction(entry.getValue()), needColor(entry.getValue()));
            g.drawString(font, entry.getValue(),
                    px + labelW + barW + 3, y, BookScreenColors.MID, false);
            y += 13;
        }

        y += 4;
        if (y + 14 > maxY) return;

        // Building types — two columns
        g.drawString(font, "Buildings (" + data.buildingTypes().size() + ")",
                px, y, BookScreenColors.MID, false);
        g.fill(px, y + 10, px + pw, y + 11, BookScreenColors.BORDER);
        y += 14;
        int halfW = pw / 2, col = 0;
        for (String type : data.buildingTypes()) {
            if (y + 10 > maxY) break;
            g.drawString(font, "• " + type, px + col * halfW, y,
                    BookScreenColors.DARK, false);
            if (++col >= 2) { col = 0; y += 11; }
        }
        if (col > 0) y += 11;
        y += 4;

        if (y + 14 > maxY) return;
        g.drawString(font, "Trade Routes", px, y, BookScreenColors.MID, false);
        g.fill(px, y + 10, px + pw, y + 11, BookScreenColors.BORDER);
        y += 14;
        int rc = data.tradeRouteCount();
        g.drawString(font,
                rc == 0 ? "No active routes" : rc + " active route" + (rc > 1 ? "s" : ""),
                px, y, BookScreenColors.DARK, false);
        y += 13;

        if (y + 14 > maxY) return;
        y += 4;
        g.drawString(font, "Expansion", px, y, BookScreenColors.MID, false);
        g.fill(px, y + 10, px + pw, y + 11, BookScreenColors.BORDER);
        y += 14;
        if (data.pendingExpansion().isEmpty()) {
            g.drawString(font, "No expansion queued", px, y, BookScreenColors.MID, false);
        } else {
            g.fill(px, y, px + pw, y + 12, BookScreenColors.BLUE_BG);
            g.renderOutline(px, y, pw, 12, BookScreenColors.BORDER);
            g.drawString(font, "→ " + data.pendingExpansion(),
                    px + 3, y + 2, 0xFF2255AA, false);
        }
    }

    private void drawCommissions(GuiGraphics g, int px, int py, int pw, int ph,
                                  int mx, int my) {
        if (commissionPanel == null) {
            commissionPanel = new CommissionBoardPanel(orderId ->
                    ClientPacketDistributor.sendToServer(new VillageActionPacket(
                            VillageActionPacket.ActionType.CLAIM_COMMISSION,
                            data.villageId(), orderId, "", 0)));
        }
        commissionPanel.setEntries(data.commissions());
        commissionPanel.render(g, font, px, py, pw, ph, mx, my);
    }

    private void drawMap(GuiGraphics g, int px, int py, int pw, int maxY,
                         int mx, int my) {
        int mapLeft = px + (pw - MAP_W) / 2;
        int mapTop  = py;
        int mapH    = Math.min(MAP_H, maxY - py);
        g.fill(mapLeft - 1, mapTop - 1,
                mapLeft + MAP_W + 1, mapTop + mapH + 1, BookScreenColors.BORDER);
        mapRenderer.draw(g, mapLeft, mapTop, MAP_W, mapH, mx, my);
        if (mapTop + mapH + 6 < maxY) {
            g.drawCenteredString(font, data.villageName(),
                    mapLeft + MAP_W / 2, mapTop + mapH + 4, BookScreenColors.MID);
        }
    }

    private void drawStandings(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;
        int rep = data.playerReputation();

        g.drawString(font, "Your Reputation", px, y, BookScreenColors.MID, false);
        g.fill(px, y + 10, px + pw, y + 11, BookScreenColors.BORDER);
        y += 15;

        int barW = pw - 4;
        g.fill(px, y, px + barW, y + 10, 0xFFDDD8C0);
        int zeroX = px + barW / 2;
        g.fill(zeroX, y, zeroX + 1, y + 10, 0xFFAAAAAA);
        int fillPx = (int)(Math.abs(rep) / 1000.0f * (barW / 2));
        int repCol = reputationColor(rep);
        if (rep >= 0) g.fill(zeroX, y, zeroX + fillPx, y + 10, repCol);
        else          g.fill(zeroX - fillPx, y, zeroX, y + 10, repCol);
        g.renderOutline(px, y, barW, 10, BookScreenColors.BORDER);
        y += 13;

        g.drawString(font, rep + "  —  " + reputationLabel(rep), px, y, repCol, false);
        y += 14;

        if (data.playerHasWarning()) {
            g.fill(px, y, px + pw, y + 12, BookScreenColors.RED_BG);
            g.renderOutline(px, y, pw, 12, BookScreenColors.BORDER);
            g.drawString(font, "⚠ Active warning in this village",
                    px + 3, y + 2, BookScreenColors.RED_TXT, false);
            y += 16;
        }

        y += 4;
        g.fill(px, y, px + pw, y + 1, BookScreenColors.BORDER);
        y += 8;

        g.drawString(font, "Property", px, y, BookScreenColors.MID, false);
        g.fill(px, y + 10, px + pw, y + 11, BookScreenColors.BORDER);
        y += 15;

        List<OpenVillageBookPacket.HouseEntry> owned = data.houses().stream()
                .filter(OpenVillageBookPacket.HouseEntry::ownedByThisPlayer).toList();
        if (owned.isEmpty()) {
            g.drawString(font, "You own no property here.", px, y, BookScreenColors.LIGHT, false);
            y += 11;
        } else {
            for (OpenVillageBookPacket.HouseEntry h : owned) {
                if (y + 10 > maxY - 60) break;
                g.fill(px, y, px + pw, y + 10, BookScreenColors.GREEN_BG);
                g.renderOutline(px, y, pw, 10, BookScreenColors.BORDER);
                g.drawString(font, "⌂ " + h.name(), px + 3, y + 1, BookScreenColors.GREEN_TXT, false);
                if (h.taxPerWeekBronze() > 0)
                    CoinRow.draw(g, h.taxPerWeekBronze(),
                            px + pw - CoinRow.width(h.taxPerWeekBronze()) - 4, y);
                y += 12;
            }
        }

        y += 4;
        g.fill(px, y, px + pw, y + 1, BookScreenColors.BORDER);
        y += 8;

        g.drawString(font, "Path to Leadership", px, y, BookScreenColors.MID, false);
        g.fill(px, y + 10, px + pw, y + 11, BookScreenColors.BORDER);
        y += 15;

        record Milestone(int threshold, String label) {}
        Milestone[] milestones = {
                new Milestone(100,  "Resident — trusted community member"),
                new Milestone(300,  "Citizen — eligible for civic roles"),
                new Milestone(600,  "Notable — known to the village leader"),
                new Milestone(1000, "Steward — eligible for leadership"),
        };
        for (Milestone m : milestones) {
            if (y + 10 > maxY - 40) break;
            boolean achieved = rep >= m.threshold();
            g.drawString(font,
                    (achieved ? "✓ " : "○ ") + m.label(),
                    px + 4, y,
                    achieved ? BookScreenColors.GREEN_TXT : BookScreenColors.MID, false);
            y += 11;
        }

        y += 6;

        boolean isTownPlus = data.tierName().equals("Town") || data.tierName().equals("City");
        if (!isTownPlus || y + 14 > maxY - 20) return;

        g.fill(px, y, px + pw, y + 1, BookScreenColors.BORDER);
        y += 8;
        g.drawString(font, "Business", px, y, BookScreenColors.MID, false);
        g.fill(px, y + 10, px + pw, y + 11, BookScreenColors.BORDER);
        y += 15;

        if (data.hasCompanyHere()) {
            g.fill(px, y, px + pw, y + 12, BookScreenColors.GREEN_BG);
            g.renderOutline(px, y, pw, 12, BookScreenColors.BORDER);
            g.drawString(font, "⌂ " + data.businessName(),
                    px + 3, y + 2, BookScreenColors.GREEN_TXT, false);
            String wc = data.companyWorkerCount() + " workers";
            g.drawString(font, wc, px + pw - font.width(wc) - 4, y + 2, BookScreenColors.MID, false);
            y += 14;
            for (String bname : data.companyBuildingNames()) {
                if (y + 9 > maxY - 22) break;
                g.drawString(font, "  • " + bname, px, y, BookScreenColors.DARK, false);
                y += 9;
            }
        } else {
            g.drawString(font, "No business here. Found one to start hiring.",
                    px, y, BookScreenColors.LIGHT, false);
        }
    }

    private List<PurchaseRow> housePurchaseRows() {
        List<PurchaseRow> rows = new ArrayList<>();
        for (OpenVillageBookPacket.HouseEntry h : data.houses()) {
            RowStatus status;
            if (h.ownedByThisPlayer())       status = RowStatus.OWNED_BY_PLAYER;
            else if (h.ownedByOtherPlayer()) status = RowStatus.OWNED_BY_OTHER;
            else if (h.occupiedByNpc())      status = RowStatus.OCCUPIED_BY_NPC;
            else if (data.playerWealthBronze() >= h.priceBronze()) status = RowStatus.AVAILABLE;
            else                             status = RowStatus.CANT_AFFORD;
            rows.add(new PurchaseRow(h.buildingId(), h.name(),
                    h.priceBronze(), h.taxPerWeekBronze(), "Tax/wk", status));
        }
        return rows;
    }

    private List<PurchaseRow> companyBuildingRows() {
        List<PurchaseRow> rows = new ArrayList<>();
        for (OpenVillageBookPacket.PurchasableBuildingEntry b : data.purchasableBuildings()) {
            RowStatus status;
            if (b.inCompany())                status = RowStatus.IN_COMPANY;
            else if (b.ownedByOtherCompany()) status = RowStatus.OWNED_BY_OTHER;
            else if (data.playerWealthBronze() >= b.priceBronze()) status = RowStatus.AVAILABLE;
            else                              status = RowStatus.CANT_AFFORD;
            rows.add(new PurchaseRow(b.buildingId(), b.name(),
                    b.priceBronze(), 0L, "", status));
        }
        return rows;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        double mx = event.x(), my = event.y();

        if (currentSection == Section.COMPANY_BUILDINGS
                && mx >= bookX + SIDEBAR_W && mx <= bookX + BOOK_W
                && my >= bookY + 20 && my <= bookY + 36) {
            currentSection = Section.STANDINGS;
            buildWidgets();
            return true;
        }

        if (sidebar.mouseClicked(mx, my)) return true;
        if (purchaseList != null && purchaseList.mouseClicked(mx, my, event.button())) return true;
        if (currentSection == Section.COMMISSIONS && commissionPanel != null
                && commissionPanel.mouseClicked(mx, my, event.button())) return true;
        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (purchaseList != null && purchaseList.mouseScrolled(mx, my, sy)) return true;
        if (currentSection == Section.COMMISSIONS && commissionPanel != null
                && commissionPanel.mouseScrolled(mx, my, sy)) return true;
        return super.mouseScrolled(mx, my, sx, sy);
    }

    private int computeCompanyButtonY(int py, int maxY) {
        int y = py;
        y += 15 + 10 + 3 + 14;
        if (data.playerHasWarning()) y += 16;
        y += 4 + 1 + 8;
        y += 15;
        List<OpenVillageBookPacket.HouseEntry> owned = data.houses().stream()
                .filter(OpenVillageBookPacket.HouseEntry::ownedByThisPlayer).toList();
        y += owned.isEmpty() ? 11 : owned.size() * 12;
        y += 4 + 1 + 8;
        y += 15;
        y += Math.min(4, 4) * 11;
        y += 6;
        y += 1 + 8 + 15;
        if (data.hasCompanyHere()) {
            y += 14;
            y += Math.min(data.companyBuildingNames().size(), 2) * 9;
        }
        return y + 2 < maxY - 20 ? y + 2 : -1;
    }

    private int needColor(String level) {
        return switch (level.toLowerCase()) {
            case "surplus"   -> BookScreenColors.GREEN_TXT;
            case "satisfied" -> BookScreenColors.DARK;
            case "low"       -> BookScreenColors.AMBER;
            case "critical"  -> BookScreenColors.RED_TXT;
            default          -> BookScreenColors.MID;
        };
    }

    private float needFraction(String level) {
        return switch (level.toLowerCase()) {
            case "surplus"   -> 1.0f;
            case "satisfied" -> 0.75f;
            case "low"       -> 0.33f;
            case "critical"  -> 0.12f;
            default          -> 0f;
        };
    }

    private String reputationLabel(int rep) {
        if (rep >=  600) return "Honored";
        if (rep >=  300) return "Respected";
        if (rep >=  100) return "Friendly";
        if (rep >=    0) return "Neutral";
        if (rep >= -200) return "Unwelcome";
        if (rep >= -500) return "Hostile";
        return "Despised";
    }

    private int reputationColor(int rep) {
        if (rep >= 300)  return BookScreenColors.GREEN_TXT;
        if (rep >= 0)    return BookScreenColors.DARK;
        if (rep >= -200) return BookScreenColors.AMBER;
        return BookScreenColors.RED_TXT;
    }

    private String sectionLabel(Section s) {
        return switch (s) {
            case OVERVIEW          -> "Overview";
            case ECONOMY           -> "Economy";
            case HOUSING           -> "Housing";
            case COMPANY_BUILDINGS -> "Buy Building";
            case STATISTICS        -> "Statistics";
            case COMMISSIONS       -> "Commissions";
            case MAP               -> "Map";
            case STANDINGS         -> "My Standing";
        };
    }

    private void startMapBake() {
        var buildings = Minecraft.getInstance().level != null
                ? Building
                .ClientBuildingCache.getBuildings()
                : List.<Building>of();
        var matchedVillage = Building
                .ClientBuildingCache.getVillages().stream()
                .filter(v -> v.getId().equals(data.villageId()))
                .findFirst().orElse(null);
        if (matchedVillage != null)
            mapRenderer.startBake(matchedVillage, MAP_W, MAP_H);
    }

    private static String formatEnum(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.charAt(0) + s.substring(1).toLowerCase().replace('_', ' ');
    }

    public static void sendOpenPacket(
            ServerPlayer player,
            UUID villageId,
            ServerLevel level,
            VillageSavedData vdata) {
        sendOpenPacket(player, villageId, level, vdata, "");
    }

    public static void sendOpenPacket(
            ServerPlayer player,
            UUID villageId,
            ServerLevel level,
            VillageSavedData vdata,
            String openSection) {

        var village = vdata.getVillageById(villageId).orElse(null);
        if (village == null) return;

        int buildingCount = village.getBuildingIds().size();
        String tier = VillageSizeTier.fromBuildingCount(buildingCount).displayName;

        AABB villageBounds = village.getBounds(vdata)
                .map(b -> b.inflate(32))
                .orElse(new AABB(0, 0, 0, 0, 0, 0));

        int pop = (int) level.getEntitiesOfClass(
                TownspersonMob.class,
                villageBounds,
                npc -> npc.getAssignedVillageName()
                        .map(n -> n.equals(village.getName())).orElse(false)
        ).size();

        String leaderName = level.getEntitiesOfClass(
                        TownspersonMob.class,
                        villageBounds,
                        npc -> npc.getProfession()
                                == Profession.VILLAGE_LEADER
                                && npc.getAssignedVillageName()
                                .map(n -> n.equals(village.getName())).orElse(false)
                ).stream().findFirst()
                .map(npc -> npc.getNpcName()).orElse("None");

        List<OpenVillageBookPacket.HouseEntry>
                houses = new ArrayList<>();
        for (UUID bid : village.getBuildingIds()) {
            vdata.getBuildingById(bid).ifPresent(b -> {
                if (b.getType() != BuildingType.HOUSE) return;
                long price = HousePurchaseManager.calculatePrice(b, village, vdata);
                long tax = HousePurchaseManager.calculateWeeklyTax(b, village, vdata);
                boolean mine = vdata.getPropertyForBuilding(bid)
                        .map(p -> p.playerId().equals(player.getUUID())).orElse(false);
                boolean others = vdata.isPlayerOwned(bid) && !mine;
                boolean npcHere = !level.getEntitiesOfClass(
                        TownspersonMob.class,
                        b.getShape().toAABB().inflate(16),
                        npc -> npc.getHouseId().map(id -> id.equals(bid)).orElse(false)
                ).isEmpty();
                houses.add(new OpenVillageBookPacket.HouseEntry(
                        bid, b.getName(), price, tax, mine, others, npcHere));
            });
        }

        Map<String, String> needs = new LinkedHashMap<>();
        village.getNeeds().forEach((cat, need) ->
                needs.put(formatEnum(cat.name()), formatEnum(need.getLevel().name())));

        String expansion = vdata.getPendingExpansionForVillage(villageId)
                .map(r -> formatEnum(r.getBuildingType().name())).orElse("");

        List<String> btypes = village.getBuildingIds().stream()
                .map(vdata::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(b -> formatEnum(b.getType().name()))
                .distinct().sorted().toList();

        long playerWealth = CoinHelper.getPlayerWealth(player).toBronze();

        int playerRep   = village.getReputation(player.getUUID());
        boolean hasWarn = vdata.hasWarning(player.getUUID(), villageId);

        String kingdomName = vdata.getKingdomForVillage(villageId)
                .map(k -> k.getName()).orElse("");

        String activeEvent = vdata.getActiveEventForVillage(villageId)
                .map(e -> formatEnum(e.getType().name())).orElse("");

        int routeCount = (int) vdata.getAllTradeRoutes().stream()
                .filter(r -> r.getVillageA().equals(villageId)
                        || r.getVillageB().equals(villageId))
                .filter(r -> r.getStatus()
                        == TradeRoute.RouteStatus.ACTIVE)
                .count();

        BusinessSavedData businessData =
                BusinessSavedData.get(level);

        Business playerCompany =
                businessData.getByOwner(player.getUUID()).stream()
                        .filter(c -> c.getHomeVillageId().equals(villageId))
                        .findFirst().orElse(null);

        boolean hasCompanyHere     = playerCompany != null;
        UUID    businessId          = hasCompanyHere ? playerCompany.getBusinessId() : new UUID(0, 0);
        String  businessName        = hasCompanyHere ? playerCompany.getName() : "";
        int     companyWorkerCount = hasCompanyHere ? playerCompany.getWorkers().size() : 0;

        List<String> companyBuildingNames = new ArrayList<>();
        if (hasCompanyHere) {
            for (UUID bid : playerCompany.getBuildingIds()) {
                vdata.getBuildingById(bid)
                        .ifPresent(b -> companyBuildingNames.add(b.getName()));
            }
        }

        Set<String> nonPurchasable = Set.of(
                "HOUSE", "TOWN_HALL", "GUARD_TOWER", "GUILD_HALL",
                "WELL", "BELL_TOWER", "PRISON");

        Set<UUID> allCompanyBuildingIds  = new HashSet<>();
        Set<UUID> playerCompanyBuildingIds = new HashSet<>();
        businessData.getAllBusinesses().forEach(c -> {
            c.getBuildingIds().forEach(allCompanyBuildingIds::add);
            if (c.isOwnedByPlayer(player.getUUID())) // PB1: sealed-owner check
                c.getBuildingIds().forEach(playerCompanyBuildingIds::add);
        });

        List<OpenVillageBookPacket.PurchasableBuildingEntry> purchasable =
                new ArrayList<>();
        for (UUID bid : village.getBuildingIds()) {
            vdata.getBuildingById(bid).ifPresent(b -> {
                if (nonPurchasable.contains(b.getType().name())) return;
                long price = HousePurchaseManager.calculatePrice(b, village, vdata);
                boolean inPlayerCompany = playerCompanyBuildingIds.contains(bid);
                boolean inOtherCompany  = !inPlayerCompany && allCompanyBuildingIds.contains(bid);
                purchasable.add(new OpenVillageBookPacket.PurchasableBuildingEntry(
                        bid, b.getName(), b.getType().name(),
                        price, inPlayerCompany, inOtherCompany));
            });
        }

        String seasonName    = SeasonTracker.currentSeason(level).displayName;
        int openOrderCount   = (int) vdata.getOrdersForVillage(village.getId())
                .stream().filter(CraftingOrder::isOpen).count();

        List<CommissionEntry> commissions =
                vdata.getOrdersForVillage(villageId).stream()
                        .filter(CraftingOrder::isActive)
                        .map(order -> CommissionEntry
                                .fromOrder(order, level, player))
                        .toList();

        // Phase 5c — economy snapshot (per-category surplus/deficit + treasury).
        var economy = buildEconomyData(vdata, village);

        PacketDistributor.sendToPlayer(player,
                new OpenVillageBookPacket(
                        villageId, village.getName(), leaderName, tier,
                        pop, village.getTreasuryBronze(),
                        houses, needs, expansion, btypes,
                        playerWealth, playerRep, hasWarn,
                        kingdomName, activeEvent, routeCount,
                        hasCompanyHere, businessId, businessName,
                        companyWorkerCount, companyBuildingNames,
                        purchasable, seasonName, openOrderCount,
                        commissions, economy, openSection));
    }

    /**
     * Phase 5c — builds the economy snapshot from {@code VillageSimData}
     * (per-{@code ResourceCategory} net) + the village treasury. Returns a
     * neutral empty snapshot when no sim data exists yet.
     */
    private static OpenVillageBookPacket.EconomyData
            buildEconomyData(VillageSavedData vdata,
                             Village village) {
        long treasury = village.getTreasuryBronze();
        var simOpt = vdata.getSimData(village.getId());
        if (simOpt.isEmpty()) {
            return OpenVillageBookPacket
                    .EconomyData.empty(treasury);
        }
        var sim = simOpt.get();
        List<OpenVillageBookPacket.CategoryNet>
                cats = new ArrayList<>();
        for (var cat : ResourceCategory.values()) {
            float net = sim.net(cat);
            // Skip dead-neutral categories the village never touches.
            if (net == 0f && sim.production(cat) == 0f && sim.consumption(cat) == 0f) continue;
            cats.add(new OpenVillageBookPacket
                    .CategoryNet(cat.name(), net));
        }
        return new OpenVillageBookPacket.EconomyData(
                true, treasury, 0L, 0L, sim.getNetIncomePerDay(), cats);
    }
}
