package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import tterrag1112.life_in_the_village.Networking.CompanyActionPacket;
import tterrag1112.life_in_the_village.Networking.OpenVillageBookPacket;
import tterrag1112.life_in_the_village.Networking.VillageActionPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.CraftingOrder;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import java.util.*;

public class VillageBookScreen extends Screen {

    // =========================================================================
    // LAYOUT
    // =========================================================================

    private static final int BOOK_W    = 420;
    private static final int BOOK_H    = 300;
    private static final int SIDEBAR_W = 130;
    private static final int PAGE_PAD  = 14;
    private static final int ROW_H     = 28;
    private static final int MAP_W     = 220;
    private static final int MAP_H     = 180;

    // =========================================================================
    // COLORS
    // =========================================================================

    private static final int COL_PARCHMENT = BookScreenColors.PARCHMENT;
    private static final int COL_SIDEBAR   = BookScreenColors.SIDEBAR;
    private static final int COL_BORDER    = BookScreenColors.BORDER;
    private static final int COL_DARK      = BookScreenColors.DARK;
    private static final int COL_MID       = BookScreenColors.MID;
    private static final int COL_LIGHT     = BookScreenColors.LIGHT;
    private static final int COL_HIGHLIGHT = BookScreenColors.HIGHLIGHT;
    private static final int COL_GREEN_BG  = BookScreenColors.GREEN_BG;
    private static final int COL_GREEN_TXT = BookScreenColors.GREEN_TXT;
    private static final int COL_RED_BG    = BookScreenColors.RED_BG;
    private static final int COL_RED_TXT   = BookScreenColors.RED_TXT;
    private static final int COL_GOLD      = BookScreenColors.GOLD;
    private static final int COL_AMBER     = BookScreenColors.AMBER;
    private static final int COL_BLUE_BG   = BookScreenColors.BLUE_BG;

    // =========================================================================
    // SECTIONS
    // =========================================================================

    private enum Section {
        OVERVIEW, HOUSING, COMPANY_BUILDINGS, STATISTICS, MAP, STANDINGS
    }

    // =========================================================================
    // PURCHASE ROW — shared data model used by both Housing and Company
    //                Buildings pages so a single set of draw/widget methods
    //                serves both.
    // =========================================================================

    /**
     * A single purchasable building row.
     * For houses:           action = BUY_HOUSE   via VillageActionPacket
     * For company buildings: action = BUY_COMPANY_BUILDING via CompanyActionPacket
     */
    private enum PurchaseAction { BUY_HOUSE, BUY_COMPANY_BUILDING }

    private record PurchaseRow(
            UUID   buildingId,
            String name,
            long   priceBronze,
            long   taxOrFeePerWeek,   // tax for houses, 0 for company buildings
            String taxLabel,          // "Tax/wk" for houses, "" for company buildings
            RowStatus status
    ) {}

    private enum RowStatus {
        OWNED_BY_PLAYER,    // green — player owns it
        OWNED_BY_OTHER,     // red — someone else owns it
        OCCUPIED_BY_NPC,    // yellow — NPC lives here (houses)
        IN_COMPANY,         // teal — already part of the player's company
        AVAILABLE,          // white — buyable
        CANT_AFFORD         // white dim — buyable but too expensive
    }

    // =========================================================================
    // STATE
    // =========================================================================

    private final OpenVillageBookPacket data;
    private int bookX, bookY;
    private Section currentSection = Section.OVERVIEW;
    private int listScroll = 0;  // unified scroll for housing and company buildings

    private final VillageMapRenderer mapRenderer = new VillageMapRenderer();

    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================

    public VillageBookScreen(OpenVillageBookPacket data) {
        super(Component.literal("Village — " + data.villageName()));
        this.data = data;
    }

    // =========================================================================
    // SCREEN LIFECYCLE
    // =========================================================================

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
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88000000);
        drawBook(g);
        drawSidebar(g);
        drawPageContent(g, mx, my);
        super.render(g, mx, my, pt);
    }

    // =========================================================================
    // BOOK CHROME
    // =========================================================================

    private void drawBook(GuiGraphics g) {
        // Shadow
        g.fill(bookX + 3, bookY + 3,
                bookX + BOOK_W + 3, bookY + BOOK_H + 3, 0x44000000);
        // Body
        g.fill(bookX, bookY, bookX + BOOK_W, bookY + BOOK_H, COL_PARCHMENT);
        g.renderOutline(bookX, bookY, BOOK_W, BOOK_H, COL_BORDER);
        g.renderOutline(bookX + 2, bookY + 2, BOOK_W - 4, BOOK_H - 4, COL_HIGHLIGHT);
        // Page dividers
        g.fill(bookX + SIDEBAR_W, bookY + 28,
                bookX + BOOK_W, bookY + 29, COL_BORDER);
        g.fill(bookX + SIDEBAR_W, bookY + BOOK_H - 30,
                bookX + BOOK_W, bookY + BOOK_H - 29, COL_BORDER);
        // Page title
        g.drawString(font, sectionLabel(currentSection),
                bookX + SIDEBAR_W + PAGE_PAD, bookY + 10, COL_DARK, false);
        // Corner flourishes
        drawFlourish(g, bookX + 4, bookY + 4);
        drawFlourish(g, bookX + BOOK_W - 12, bookY + 4);
        drawFlourish(g, bookX + 4, bookY + BOOK_H - 12);
        drawFlourish(g, bookX + BOOK_W - 12, bookY + BOOK_H - 12);
    }

    private void drawFlourish(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 8, y + 1, COL_BORDER);
        g.fill(x, y, x + 1, y + 8, COL_BORDER);
    }

    private void drawSidebar(GuiGraphics g) {
        g.fill(bookX, bookY, bookX + SIDEBAR_W, bookY + BOOK_H, COL_SIDEBAR);
        g.fill(bookX + SIDEBAR_W - 1, bookY,
                bookX + SIDEBAR_W, bookY + BOOK_H, COL_BORDER);
        g.drawString(font, "\u265A " + data.villageName(),
                bookX + 8, bookY + 10, COL_DARK, false);
        g.fill(bookX + 4, bookY + 22,
                bookX + SIDEBAR_W - 4, bookY + 23, COL_BORDER);

        int ny = bookY + 30;
        String lastGroup = "";
        for (Section s : Section.values()) {
            // Hide COMPANY_BUILDINGS from the sidebar nav — it is reached via
            // the Standings button, not clicked directly.
            if (s == Section.COMPANY_BUILDINGS) continue;

            String group = sectionGroup(s);
            if (!group.equals(lastGroup)) {
                g.drawString(font, group.toUpperCase(),
                        bookX + 8, ny, COL_LIGHT, false);
                ny += 12;
                lastGroup = group;
            }
            boolean active = s == currentSection
                    || (s == Section.STANDINGS
                    && currentSection == Section.COMPANY_BUILDINGS);
            if (active) {
                g.fill(bookX + 2, ny - 1,
                        bookX + SIDEBAR_W - 1, ny + 9, COL_HIGHLIGHT);
                g.fill(bookX + 2, ny - 1, bookX + 4, ny + 9, COL_GOLD);
            }
            g.drawString(font, (active ? "> " : "  ") + sectionLabel(s),
                    bookX + 6, ny,
                    active ? COL_DARK : COL_MID, false);
            ny += 18;
        }
    }

    // =========================================================================
    // WIDGETS
    // =========================================================================

    private void buildWidgets() {
        clearWidgets();

        int px   = bookX + SIDEBAR_W + PAGE_PAD;
        int py   = bookY + 36;
        int pw   = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;
        int maxY = bookY + BOOK_H - 34;

        switch (currentSection) {
            case HOUSING ->
                    buildPurchaseWidgets(px, py, pw, maxY,
                            housePurchaseRows(),
                            PurchaseAction.BUY_HOUSE);

            case COMPANY_BUILDINGS ->
                    buildPurchaseWidgets(px, py, pw, maxY,
                            companyBuildingRows(),
                            PurchaseAction.BUY_COMPANY_BUILDING);

            case STANDINGS ->
                    buildStandingsWidgets(px, py, pw, maxY);

            default -> {}
        }
    }

    // -------------------------------------------------------------------------
    // SHARED PURCHASE WIDGETS
    //
    // Handles scroll buttons and per-row Buy buttons for both
    // the Housing page and the Company Buildings page.
    // -------------------------------------------------------------------------

    private void buildPurchaseWidgets(int px, int py, int pw, int maxY,
                                      List<PurchaseRow> rows,
                                      PurchaseAction action) {
        // Wallet + headers take ~27px; rows start below that
        int y      = py + 27;
        int vis    = listScroll;
        int index  = 0;

        for (PurchaseRow row : rows) {
            if (index < vis) { index++; continue; }
            if (y + ROW_H + 4 > maxY - 20) break;

            if (row.status() == RowStatus.AVAILABLE
                    || row.status() == RowStatus.CANT_AFFORD) {
                final UUID bid = row.buildingId();
                addRenderableWidget(Button.builder(
                                Component.literal("Buy"),
                                b -> sendPurchase(action, bid))
                        .pos(px + pw - 30, y + (ROW_H - 14) / 2)
                        .size(28, 14).build());
            }

            y += ROW_H + 2;
            index++;
        }

        // Scroll buttons
        if (listScroll > 0) {
            addRenderableWidget(Button.builder(Component.literal("▲"),
                            b -> { listScroll--; buildWidgets(); })
                    .pos(px + pw - 14, py + 27).size(12, 10).build());
        }
        // Count how many rows we drew
        int rowsVisible = (maxY - 20 - (py + 27)) / (ROW_H + 2);
        if (listScroll + rowsVisible < rows.size()) {
            addRenderableWidget(Button.builder(Component.literal("▼"),
                            b -> { listScroll++; buildWidgets(); })
                    .pos(px + pw - 14, maxY - 20).size(12, 10).build());
        }
    }

    private void sendPurchase(PurchaseAction action, UUID buildingId) {
        switch (action) {
            case BUY_HOUSE ->
                    ClientPacketDistributor.sendToServer(
                            new VillageActionPacket(
                                    VillageActionPacket.ActionType.BUY_HOUSE,
                                    data.villageId(), buildingId, "", 0));

            case BUY_COMPANY_BUILDING ->
                    ClientPacketDistributor.sendToServer(
                            new CompanyActionPacket(
                                    CompanyActionPacket.ActionType.BUY_COMPANY_BUILDING,
                                    data.companyId(), buildingId,
                                    "", 0L, 0));
        }
    }

    // -------------------------------------------------------------------------
    // STANDINGS WIDGETS — company buttons
    // -------------------------------------------------------------------------

    private void buildStandingsWidgets(int px, int py, int pw, int maxY) {
        boolean isTownPlus = data.tierName().equals("Town")
                || data.tierName().equals("City");
        if (!isTownPlus) return;

        // Position the company button(s) just below the company section header.
        // We measure how far down drawStandings will have drawn by the time it
        // reaches the company section, then place buttons there.
        int btnY = computeCompanyButtonY(py, maxY);
        if (btnY < 0) return;

        if (data.hasCompanyHere()) {
            // Manage button
            addRenderableWidget(Button.builder(
                            Component.literal("Manage Company"),
                            b -> ClientPacketDistributor.sendToServer(
                                    new CompanyActionPacket(
                                            CompanyActionPacket.ActionType.OPEN_MANAGEMENT,
                                            data.companyId(), new UUID(0, 0),
                                            "", 0L, 0)))
                    .pos(px, btnY).size(pw / 2 - 2, 16).build());

            // Buy Building button → switches to COMPANY_BUILDINGS sub-page
            addRenderableWidget(Button.builder(
                            Component.literal("Buy Building"),
                            b -> {
                                currentSection = Section.COMPANY_BUILDINGS;
                                listScroll = 0;
                                buildWidgets();
                            })
                    .pos(px + pw / 2 + 2, btnY).size(pw / 2 - 2, 16).build());
        } else {
            addRenderableWidget(Button.builder(
                            Component.literal("Found a Company"),
                            b -> ClientPacketDistributor.sendToServer(
                                    new CompanyActionPacket(
                                            CompanyActionPacket.ActionType.FOUND_COMPANY,
                                            UUID.randomUUID(), data.villageId(),
                                            "My Company", 0L, 0)))
                    .pos(px, btnY).size(pw, 16).build());
        }
    }

    // =========================================================================
    // PAGE CONTENT RENDERING
    // =========================================================================

    private void drawPageContent(GuiGraphics g, int mx, int my) {
        int px   = bookX + SIDEBAR_W + PAGE_PAD;
        int py   = bookY + 36;
        int pw   = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;
        int maxY = bookY + BOOK_H - 34;

        switch (currentSection) {
            case OVERVIEW          -> drawOverview(g, px, py, pw, maxY);
            case HOUSING           -> drawPurchasePage(g, px, py, pw, maxY,
                    housePurchaseRows(), "Your wallet:", true);
            case COMPANY_BUILDINGS -> drawCompanyBuildingsPage(
                    g, px, py, pw, maxY, mx, my);
            case STATISTICS        -> drawStatistics(g, px, py, pw, maxY);
            case MAP               -> drawMap(g, px, py, pw, maxY, mx, my);
            case STANDINGS         -> drawStandings(g, px, py, pw, maxY);
        }
    }

    // =========================================================================
    // OVERVIEW
    // =========================================================================

    private void drawOverview(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;

        // Village name / kingdom banner
        g.fill(px, y, px + pw, y + 18, COL_HIGHLIGHT);
        g.renderOutline(px, y, pw, 18, COL_BORDER);
        if (!data.kingdomName().isEmpty()) {
            g.drawCenteredString(font, data.villageName(),
                    px + pw / 2, y + 2, COL_DARK);
            g.drawCenteredString(font, "Kingdom of " + data.kingdomName(),
                    px + pw / 2, y + 10, COL_MID);
        } else {
            g.drawCenteredString(font, data.villageName(),
                    px + pw / 2, y + 5, COL_DARK);
        }
        y += 22;

        // Active event
        if (!data.activeEventName().isEmpty()) {
            g.fill(px, y, px + pw, y + 12, 0xFFFFEECC);
            g.renderOutline(px, y, pw, 12, COL_AMBER);
            g.drawString(font, "\u2605 " + data.activeEventName(),
                    px + 3, y + 2, COL_AMBER, false);
            y += 16;
        }

        // Stat grid — 2 columns
        int colW = pw / 2 - 4;
        record Stat(String label, String value) {}
        Stat[] stats = {
                new Stat("Leader",     data.leaderName().isEmpty() ? "None" : data.leaderName()),
                new Stat("Tier",       data.tierName()),
                new Stat("Population", String.valueOf(data.population())),
                new Stat("Treasury",   formatBronze(data.treasuryBronze())),
                new Stat("Routes",     data.tradeRouteCount() + " active"),
                new Stat("Buildings",  String.valueOf(data.buildingTypes().size())),
                new Stat("Season",   data.seasonName()),

        };
        if (data.openOrderCount() > 0) {
            g.fill(px, y, px + pw, y + 12, 0xFFEEFFEE);
            g.renderOutline(px, y, pw, 12, COL_BORDER);
            g.drawString(font,
                    "\u2709 " + data.openOrderCount() + " open commission"
                            + (data.openOrderCount() > 1 ? "s" : "") + " — sneak+talk to leader",
                    px + 3, y + 2, COL_GREEN_TXT, false);
            y += 16;
        }

        for (int i = 0; i < stats.length; i += 2) {
            if (y + 24 > maxY) break;
            drawStatBox(g, px,          y, colW, 22, stats[i].label(),     stats[i].value());
            if (i + 1 < stats.length)
                drawStatBox(g, px + colW + 4, y, colW, 22, stats[i+1].label(), stats[i+1].value());
            y += 26;
        }

        // Needs dots
        y += 4;
        if (y + 12 > maxY) return;
        g.drawString(font, "Needs:", px, y, COL_MID, false);
        int dotX = px + 44;
        for (var entry : data.needs().entrySet()) {
            int col = needColor(entry.getValue());
            g.fill(dotX, y + 2, dotX + 8, y + 10, col);
            g.renderOutline(dotX, y + 2, 8, 8, COL_BORDER);
            dotX += 12;
        }
        y += 14;

        // Expansion queue
        if (!data.pendingExpansion().isEmpty() && y + 14 < maxY) {
            g.fill(px, y, px + pw, y + 12, COL_BLUE_BG);
            g.renderOutline(px, y, pw, 12, COL_BORDER);
            g.drawString(font, "\u2192 Expanding: " + data.pendingExpansion(),
                    px + 3, y + 2, 0xFF2255AA, false);
        }
    }

    private void drawStatBox(GuiGraphics g, int x, int y, int w, int h,
                             String label, String value) {
        g.fill(x, y, x + w, y + h, 0xFFEDE8D5);
        g.renderOutline(x, y, w, h, COL_BORDER);
        g.drawString(font, label, x + 3, y + 3, COL_LIGHT, false);
        g.drawString(font, value, x + 3, y + 12, COL_DARK, false);
    }

    // =========================================================================
    // SHARED PURCHASE PAGE
    //
    // Used by Housing and Company Buildings. Both pages share the same
    // scroll state (listScroll), wallet header, column headers, and row
    // drawing logic. Only the column labels and row data differ.
    // =========================================================================

    /**
     * Draws the full purchase page (wallet banner + headers + rows).
     *
     * @param showTax  true = show the tax/fee column (housing), false = hide it
     */
    private void drawPurchasePage(GuiGraphics g, int px, int py, int pw,
                                  int maxY, List<PurchaseRow> rows,
                                  String walletLabel, boolean showTax) {
        int y = py;

        // Wallet banner
        g.fill(px, y, px + pw, y + 12, COL_HIGHLIGHT);
        g.renderOutline(px, y, pw, 12, COL_BORDER);
        g.drawString(font, walletLabel, px + 3, y + 2, COL_GOLD, false);
        int afterWallet = px + 3 + font.width(walletLabel) + 4;
        CoinRenderer.renderCoinRow(g, data.playerWealthBronze(), afterWallet, y);
        y += 14;

        // Column headers
        g.fill(px, y, px + pw, y + 11, 0xFFE8E0C8);
        g.drawString(font, "Building", px + 2,       y + 2, COL_MID, false);
        g.drawString(font, "Price",    px + pw / 2,  y + 2, COL_MID, false);
        if (showTax)
            g.drawString(font, "Tax/wk", px + pw - 80, y + 2, COL_MID, false);
        g.drawString(font, "Status",   px + pw - 32, y + 2, COL_MID, false);
        y += 13;

        if (rows.isEmpty()) {
            g.drawCenteredString(font, "Nothing available to purchase.",
                    px + pw / 2, py + 80, COL_MID);
            return;
        }

        int vis   = listScroll;
        int index = 0;

        for (PurchaseRow row : rows) {
            if (index < vis) { index++; continue; }
            if (y + ROW_H > maxY - 20) break;

            drawPurchaseRow(g, px, y, pw, row, showTax,
                    data.playerWealthBronze());

            y += ROW_H + 2;
            index++;
        }
    }

    /**
     * Draws a single purchase row. Used by both Housing and Company Buildings.
     */
    private void drawPurchaseRow(GuiGraphics g, int px, int y, int pw,
                                 PurchaseRow row, boolean showTax,
                                 long playerWealth) {
        // Row background by status
        int rowBg = switch (row.status()) {
            case OWNED_BY_PLAYER -> COL_GREEN_BG;
            case OWNED_BY_OTHER  -> COL_RED_BG;
            case IN_COMPANY      -> 0xFFCCE8E8;
            case OCCUPIED_BY_NPC -> 0xFFEEEECC;
            default              -> COL_PARCHMENT;
        };
        g.fill(px, y, px + pw, y + ROW_H, rowBg);
        g.renderOutline(px, y, pw, ROW_H, COL_BORDER);

        // Name — truncate if needed
        String name = row.name();
        int maxNameW = pw / 2 - 6;
        while (font.width(name) > maxNameW && name.length() > 4)
            name = name.substring(0, name.length() - 1);
        if (!name.equals(row.name())) name += "…";
        g.drawString(font, name, px + 3,
                y + (ROW_H - 8) / 2, COL_DARK, false);

        // Price (coin icons)
        CoinRenderer.renderCoinRow(g, row.priceBronze(),
                px + pw / 2, y + (ROW_H - 16) / 2);

        // Tax column (housing only)
        if (showTax && row.taxOrFeePerWeek() > 0) {
            CoinRenderer.renderCoinRow(g, row.taxOrFeePerWeek(),
                    px + pw - 80, y + (ROW_H - 16) / 2);
        }

        // Status label
        String statusLabel = switch (row.status()) {
            case OWNED_BY_PLAYER -> "Yours";
            case OWNED_BY_OTHER  -> "Owned";
            case IN_COMPANY      -> "Company";
            case OCCUPIED_BY_NPC -> "Occupied";
            case AVAILABLE       -> "Buy";
            case CANT_AFFORD     -> "Costly";
        };
        int statusColor = switch (row.status()) {
            case OWNED_BY_PLAYER -> COL_GREEN_TXT;
            case OWNED_BY_OTHER  -> COL_RED_TXT;
            case IN_COMPANY      -> 0xFF1A7A7A;
            case OCCUPIED_BY_NPC -> COL_AMBER;
            case AVAILABLE       -> COL_GREEN_TXT;
            case CANT_AFFORD     -> COL_RED_TXT;
        };
        g.drawString(font, statusLabel, px + pw - 32,
                y + (ROW_H - 8) / 2, statusColor, false);
    }

    // =========================================================================
    // COMPANY BUILDINGS PAGE
    // =========================================================================

    private void drawCompanyBuildingsPage(GuiGraphics g, int px, int py,
                                          int pw, int maxY, int mx, int my) {
        // Back breadcrumb
        g.drawString(font, "\u2190 My Standing \u203A Buy Building",
                px, py - 14, COL_LIGHT, false);

        // Owned buildings info at top
        if (!data.companyBuildingNames().isEmpty()) {
            int y = py;
            g.drawString(font, data.companyName() + " owns:",
                    px, y, COL_MID, false);
            y += 10;
            for (String bname : data.companyBuildingNames()) {
                if (y + 10 > py + 32) { g.drawString(font, "…", px + 80, y, COL_LIGHT, false); break; }
                g.drawString(font, "  \u2022 " + bname, px, y, COL_DARK, false);
                y += 10;
            }
        }

        // Shared purchase page — no tax column for company buildings
        drawPurchasePage(g, px, py + 36, pw, maxY,
                companyBuildingRows(), "Treasury:", false);
    }

    // =========================================================================
    // STATISTICS
    // =========================================================================

    private void drawStatistics(GuiGraphics g, int px, int py,
                                int pw, int maxY) {
        int y = py;

        // Needs bars
        g.drawString(font, "Needs", px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 14;

        int labelW = 70;
        int barW   = pw - labelW - 44;

        for (var entry : data.needs().entrySet()) {
            if (y + 11 > maxY) break;
            g.drawString(font, entry.getKey(), px, y, COL_DARK, false);
            int filled = needBarFill(entry.getValue(), barW);
            int barCol = needColor(entry.getValue());
            g.fill(px + labelW, y + 1, px + labelW + barW, y + 9, 0xFFDDD8C0);
            if (filled > 0)
                g.fill(px + labelW, y + 1, px + labelW + filled, y + 9, barCol);
            g.renderOutline(px + labelW, y + 1, barW, 8, COL_BORDER);
            g.drawString(font, entry.getValue(),
                    px + labelW + barW + 3, y, COL_MID, false);
            y += 13;
        }

        y += 4;
        if (y + 14 > maxY) return;

        // Building types — two columns
        g.drawString(font, "Buildings (" + data.buildingTypes().size() + ")",
                px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 14;

        List<String> types = data.buildingTypes();
        int halfW = pw / 2;
        int col   = 0;
        for (String type : types) {
            if (y + 10 > maxY) break;
            g.drawString(font, "\u2022 " + type,
                    px + col * halfW, y, COL_DARK, false);
            col++;
            if (col >= 2) { col = 0; y += 11; }
        }
        if (col > 0) y += 11;
        y += 4;

        // Trade routes
        if (y + 14 > maxY) return;
        g.drawString(font, "Trade Routes", px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 14;
        int rc = data.tradeRouteCount();
        g.drawString(font,
                rc == 0 ? "No active routes"
                        : rc + " active route" + (rc > 1 ? "s" : ""),
                px, y, COL_DARK, false);
        y += 13;

        // Expansion
        if (y + 14 > maxY) return;
        y += 4;
        g.drawString(font, "Expansion", px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 14;
        if (data.pendingExpansion().isEmpty()) {
            g.drawString(font, "No expansion queued", px, y, COL_MID, false);
        } else {
            g.fill(px, y, px + pw, y + 12, COL_BLUE_BG);
            g.renderOutline(px, y, pw, 12, COL_BORDER);
            g.drawString(font, "\u2192 " + data.pendingExpansion(),
                    px + 3, y + 2, 0xFF2255AA, false);
        }
    }

    // =========================================================================
    // MAP
    // =========================================================================

    private void drawMap(GuiGraphics g, int px, int py, int pw,
                         int maxY, int mx, int my) {
        int mapLeft = px + (pw - MAP_W) / 2;
        int mapTop  = py;
        int mapH    = Math.min(MAP_H, maxY - py);
        g.fill(mapLeft - 1, mapTop - 1,
                mapLeft + MAP_W + 1, mapTop + mapH + 1, COL_BORDER);
        mapRenderer.draw(g, mapLeft, mapTop, MAP_W, mapH, mx, my);
        if (mapTop + mapH + 6 < maxY) {
            g.drawCenteredString(font, data.villageName(),
                    mapLeft + MAP_W / 2, mapTop + mapH + 4, COL_MID);
        }
    }

    // =========================================================================
    // STANDINGS
    // =========================================================================

    private void drawStandings(GuiGraphics g, int px, int py,
                               int pw, int maxY) {
        int y = py;
        int rep = data.playerReputation();

        // --- Reputation bar ---
        g.drawString(font, "Your Reputation", px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 15;

        int barW = pw - 4;
        g.fill(px, y, px + barW, y + 10, 0xFFDDD8C0);
        int zeroX  = px + barW / 2;
        g.fill(zeroX, y, zeroX + 1, y + 10, 0xFFAAAAAA);
        int fillPx = (int)(Math.abs(rep) / 1000.0f * (barW / 2));
        int repCol = reputationColor(rep);
        if (rep >= 0)
            g.fill(zeroX, y, zeroX + fillPx, y + 10, repCol);
        else
            g.fill(zeroX - fillPx, y, zeroX, y + 10, repCol);
        g.renderOutline(px, y, barW, 10, COL_BORDER);
        y += 13;

        g.drawString(font, rep + "  —  " + reputationLabel(rep),
                px, y, repCol, false);
        y += 14;

        if (data.playerHasWarning()) {
            g.fill(px, y, px + pw, y + 12, COL_RED_BG);
            g.renderOutline(px, y, pw, 12, COL_BORDER);
            g.drawString(font, "\u26A0 Active warning in this village",
                    px + 3, y + 2, COL_RED_TXT, false);
            y += 16;
        }

        y += 4;
        g.fill(px, y, px + pw, y + 1, COL_BORDER);
        y += 8;

        // --- Owned property ---
        g.drawString(font, "Property", px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 15;

        List<OpenVillageBookPacket.HouseEntry> owned = data.houses().stream()
                .filter(OpenVillageBookPacket.HouseEntry::ownedByThisPlayer)
                .toList();

        if (owned.isEmpty()) {
            g.drawString(font, "You own no property here.",
                    px, y, COL_LIGHT, false);
            y += 11;
        } else {
            for (OpenVillageBookPacket.HouseEntry h : owned) {
                if (y + 10 > maxY - 60) break;
                g.fill(px, y, px + pw, y + 10, COL_GREEN_BG);
                g.renderOutline(px, y, pw, 10, COL_BORDER);
                g.drawString(font, "\u2302 " + h.name(),
                        px + 3, y + 1, COL_GREEN_TXT, false);
                if (h.taxPerWeekBronze() > 0) {
                    CoinRenderer.renderCoinRow(g, h.taxPerWeekBronze(),
                            px + pw - CoinRenderer.coinRowWidth(h.taxPerWeekBronze()) - 4,
                            y);
                }
                y += 12;
            }
        }

        y += 4;
        g.fill(px, y, px + pw, y + 1, COL_BORDER);
        y += 8;

        // --- Milestones ---
        g.drawString(font, "Path to Leadership", px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
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
                    (achieved ? "\u2713 " : "\u25CB ") + m.label(),
                    px + 4, y,
                    achieved ? COL_GREEN_TXT : COL_MID, false);
            y += 11;
        }

        y += 6;

        // --- Company section (Town+ only) ---
        boolean isTownPlus = data.tierName().equals("Town")
                || data.tierName().equals("City");
        if (!isTownPlus || y + 14 > maxY - 20) return;

        g.fill(px, y, px + pw, y + 1, COL_BORDER);
        y += 8;
        g.drawString(font, "Company", px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 15;

        if (data.hasCompanyHere()) {
            // Company name + worker count
            g.fill(px, y, px + pw, y + 12, COL_GREEN_BG);
            g.renderOutline(px, y, pw, 12, COL_BORDER);
            g.drawString(font, "\u2302 " + data.companyName(),
                    px + 3, y + 2, COL_GREEN_TXT, false);
            String wc = data.companyWorkerCount() + " workers";
            g.drawString(font, wc,
                    px + pw - font.width(wc) - 4, y + 2, COL_MID, false);
            y += 14;

            // Company buildings list (compact)
            for (String bname : data.companyBuildingNames()) {
                if (y + 9 > maxY - 22) break;
                g.drawString(font, "  \u2022 " + bname,
                        px, y, COL_DARK, false);
                y += 9;
            }
        } else {
            g.drawString(font,
                    "No company here. Found one to start hiring.",
                    px, y, COL_LIGHT, false);
        }

        // Buttons are placed by buildStandingsWidgets at this same y position
        // (computeCompanyButtonY mirrors this layout exactly)
    }

    // =========================================================================
    // ROW DATA BUILDERS
    // =========================================================================

    /** Builds PurchaseRow list for the HOUSING page. */
    private List<PurchaseRow> housePurchaseRows() {
        List<PurchaseRow> rows = new ArrayList<>();
        for (OpenVillageBookPacket.HouseEntry h : data.houses()) {
            RowStatus status;
            if (h.ownedByThisPlayer())   status = RowStatus.OWNED_BY_PLAYER;
            else if (h.ownedByOtherPlayer()) status = RowStatus.OWNED_BY_OTHER;
            else if (h.occupiedByNpc())   status = RowStatus.OCCUPIED_BY_NPC;
            else if (data.playerWealthBronze() >= h.priceBronze())
                status = RowStatus.AVAILABLE;
            else                          status = RowStatus.CANT_AFFORD;

            rows.add(new PurchaseRow(h.buildingId(), h.name(),
                    h.priceBronze(), h.taxPerWeekBronze(), "Tax/wk", status));
        }
        return rows;
    }

    /** Builds PurchaseRow list for the COMPANY_BUILDINGS page.
     *  Only shows buildings that are neither houses nor already in the company.
     *  Excludes TOWN_HALL and other non-purchasable buildings. */
    private List<PurchaseRow> companyBuildingRows() {
        List<PurchaseRow> rows = new ArrayList<>();
        for (OpenVillageBookPacket.PurchasableBuildingEntry b
                : data.purchasableBuildings()) {

            RowStatus status;
            if (b.inCompany())
                status = RowStatus.IN_COMPANY;
            else if (b.ownedByOtherCompany())
                status = RowStatus.OWNED_BY_OTHER;
            else if (data.playerWealthBronze() >= b.priceBronze())
                status = RowStatus.AVAILABLE;
            else
                status = RowStatus.CANT_AFFORD;

            rows.add(new PurchaseRow(
                    b.buildingId(),
                    b.name(),
                    b.priceBronze(),
                    0L,        // no weekly tax for company buildings
                    "",
                    status));
        }
        return rows;
    }

    private long defaultCompanyBuildingPrice() {
        // Placeholder — real price comes from HousePurchaseManager-style
        // calculation once per-building entries are in the packet.
        return 200L;
    }

    // =========================================================================
    // MOUSE — sidebar navigation
    // =========================================================================

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (consumed) return super.mouseClicked(event, consumed);

        double mx = event.x(), my = event.y();

        // Back arrow on COMPANY_BUILDINGS page — click anywhere in the
        // breadcrumb area returns to STANDINGS
        if (currentSection == Section.COMPANY_BUILDINGS
                && mx >= bookX + SIDEBAR_W && mx <= bookX + BOOK_W
                && my >= bookY + 20 && my <= bookY + 36) {
            currentSection = Section.STANDINGS;
            listScroll = 0;
            buildWidgets();
            return true;
        }

        // Sidebar navigation
        String lastGroup = "";
        int ny = bookY + 30;
        for (Section s : Section.values()) {
            if (s == Section.COMPANY_BUILDINGS) continue;
            String group = sectionGroup(s);
            if (!group.equals(lastGroup)) { ny += 12; lastGroup = group; }
            if (ny + 10 > bookY + BOOK_H - 10) break;
            if (mx >= bookX + 2 && mx <= bookX + SIDEBAR_W - 1
                    && my >= ny - 1 && my <= ny + 9) {
                currentSection = s;
                listScroll = 0;
                buildWidgets();
                return true;
            }
            ny += 18;
        }

        return super.mouseClicked(event, consumed);
    }

    // =========================================================================
    // MAP BAKE
    // =========================================================================

    private void startMapBake() {
        // Kick off the background terrain bake using the client building cache
        var buildings = net.minecraft.client.Minecraft.getInstance().level != null
                ? tterrag1112.life_in_the_village.Village.Building
                .ClientBuildingCache.getBuildings()
                : List.<tterrag1112.life_in_the_village.Village.Building>of();

        var matchedVillage = tterrag1112.life_in_the_village.Village.Building
                .ClientBuildingCache.getVillages().stream()
                .filter(v -> v.getId().equals(data.villageId()))
                .findFirst().orElse(null);

        if (matchedVillage != null)
            mapRenderer.startBake(matchedVillage, MAP_W, MAP_H);
    }

    @Override
    public void tick() {
        super.tick();
        mapRenderer.tick();
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private String sectionLabel(Section s) {
        return switch (s) {
            case OVERVIEW          -> "Overview";
            case HOUSING           -> "Housing";
            case COMPANY_BUILDINGS -> "Buy Building";
            case STATISTICS        -> "Statistics";
            case MAP               -> "Map";
            case STANDINGS         -> "My Standing";
        };
    }

    private String sectionGroup(Section s) {
        return switch (s) {
            case OVERVIEW, STATISTICS  -> "Village";
            case HOUSING               -> "Property";
            case COMPANY_BUILDINGS     -> "Property";
            case MAP                   -> "Map";
            case STANDINGS             -> "Player";
        };
    }

    /** Computes the Y position where company action buttons should appear
     *  by mirroring the drawStandings layout exactly. Returns -1 if off-screen. */
    private int computeCompanyButtonY(int py, int maxY) {
        int y = py;
        int rep = data.playerReputation();

        // Reputation section
        y += 15 + 10 + 3 + 14; // header + bar + gap + rep text
        if (data.playerHasWarning()) y += 16;

        y += 4 + 1 + 8; // separator

        // Property section
        y += 15; // header
        List<OpenVillageBookPacket.HouseEntry> owned = data.houses().stream()
                .filter(OpenVillageBookPacket.HouseEntry::ownedByThisPlayer).toList();
        y += owned.isEmpty() ? 11 : owned.size() * 12;
        y += 4 + 1 + 8; // separator

        // Milestones section
        y += 15; // header
        y += Math.min(4, 4) * 11; // 4 milestones
        y += 6;

        // Company section header
        y += 1 + 8 + 15; // separator + gap + header

        if (data.hasCompanyHere()) {
            y += 14; // company name row
            y += Math.min(data.companyBuildingNames().size(), 2) * 9;
        }

        return y + 2 < maxY - 20 ? y + 2 : -1;
    }

    private String formatBronze(long bronze) {
        return CoinRenderer.format(bronze);
    }

    private int needColor(String level) {
        return switch (level.toLowerCase()) {
            case "surplus"   -> COL_GREEN_TXT;
            case "satisfied" -> COL_DARK;
            case "low"       -> COL_AMBER;
            case "critical"  -> COL_RED_TXT;
            default          -> COL_MID;
        };
    }

    private int needBarFill(String level, int barW) {
        return switch (level.toLowerCase()) {
            case "surplus"   -> barW;
            case "satisfied" -> barW * 3 / 4;
            case "low"       -> barW / 3;
            case "critical"  -> barW / 8;
            default          -> 0;
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
        if (rep >= 300)  return COL_GREEN_TXT;
        if (rep >= 0)    return COL_DARK;
        if (rep >= -200) return COL_AMBER;
        return COL_RED_TXT;
    }

    public static void sendOpenPacket(
            net.minecraft.server.level.ServerPlayer player,
            UUID villageId,
            net.minecraft.server.level.ServerLevel level,
            tterrag1112.life_in_the_village.Networking.VillageSavedData vdata) {

        var village = vdata.getVillageById(villageId).orElse(null);
        if (village == null) return;

        // Tier
        int buildingCount = village.getBuildingIds().size();
        String tier = tterrag1112.life_in_the_village.Village.Decoration
                .VillageSizeTier.fromBuildingCount(buildingCount).displayName;

        // Population
        net.minecraft.world.phys.AABB villageBounds = village.getBounds(vdata)
                .map(b -> b.inflate(32))
                .orElse(new net.minecraft.world.phys.AABB(0, 0, 0, 0, 0, 0));

        int pop = (int) level.getEntitiesOfClass(
                tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                villageBounds,
                npc -> npc.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        ).size();

        // Leader name
        String leaderName = level.getEntitiesOfClass(
                        tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                        villageBounds,
                        npc -> npc.getProfession()
                                == tterrag1112.life_in_the_village.Profession.Profession.VILLAGE_LEADER
                                && npc.getAssignedVillageName()
                                .map(n -> n.equals(village.getName()))
                                .orElse(false)
                ).stream().findFirst()
                .map(npc -> npc.getNpcName())
                .orElse("None");

        // Houses
        java.util.List<tterrag1112.life_in_the_village.Networking.OpenVillageBookPacket.HouseEntry>
                houses = new java.util.ArrayList<>();
        for (UUID bid : village.getBuildingIds()) {
            vdata.getBuildingById(bid).ifPresent(b -> {
                if (b.getType() != tterrag1112.life_in_the_village.Village.Buildings
                        .BuildingType.HOUSE) return;
                long price = tterrag1112.life_in_the_village.Village.Buildings
                        .HousePurchaseManager.calculatePrice(b, village, vdata);
                long tax = tterrag1112.life_in_the_village.Village.Buildings
                        .HousePurchaseManager.calculateWeeklyTax(b, village, vdata);
                boolean mine = vdata.getPropertyForBuilding(bid)
                        .map(p -> p.playerId().equals(player.getUUID()))
                        .orElse(false);
                boolean others = vdata.isPlayerOwned(bid) && !mine;
                boolean npcHere = !level.getEntitiesOfClass(
                        tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                        b.getShape().toAABB().inflate(16),
                        npc -> npc.getHouseId().map(id -> id.equals(bid)).orElse(false)
                ).isEmpty();
                houses.add(new tterrag1112.life_in_the_village.Networking
                        .OpenVillageBookPacket.HouseEntry(
                        bid, b.getName(), price, tax, mine, others, npcHere));
            });
        }

        // Needs
        java.util.Map<String, String> needs = new java.util.LinkedHashMap<>();
        village.getNeeds().forEach((cat, need) ->
                needs.put(formatEnum(cat.name()), formatEnum(need.getLevel().name())));

        // Pending expansion
        String expansion = vdata.getPendingExpansionForVillage(villageId)
                .map(r -> formatEnum(r.getBuildingType().name()))
                .orElse("");

        // Building types present
        java.util.List<String> btypes = village.getBuildingIds().stream()
                .map(vdata::getBuildingById)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .map(b -> formatEnum(b.getType().name()))
                .distinct().sorted().toList();

        // Player wealth

        long playerWealth = CoinHelper.getPlayerWealth(player).toBronze();

        // Reputation and warning
        int playerRep   = village.getReputation(player.getUUID());
        boolean hasWarn = vdata.hasWarning(player.getUUID(), villageId);

        // Kingdom name
        String kingdomName = vdata.getKingdomForVillage(villageId)
                .map(k -> k.getName()).orElse("");

        // Active event
        String activeEvent = vdata.getActiveEventForVillage(villageId)
                .map(e -> formatEnum(e.getType().name())).orElse("");

        // Trade route count
        int routeCount = (int) vdata.getAllTradeRoutes().stream()
                .filter(r -> r.getVillageA().equals(villageId)
                        || r.getVillageB().equals(villageId))
                .filter(r -> r.getStatus()
                        == tterrag1112.life_in_the_village.Village.Economy.Trade
                        .TradeRoute.RouteStatus.ACTIVE)
                .count();

        // Company data
        tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData companyData =
                tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData.get(level);

        tterrag1112.life_in_the_village.Guilds.Companies.Company playerCompany =
                companyData.getByOwner(player.getUUID()).stream()
                        .filter(c -> c.getHomeVillageId().equals(villageId))
                        .findFirst().orElse(null);

        boolean hasCompanyHere     = playerCompany != null;
        UUID    companyId          = hasCompanyHere
                ? playerCompany.getCompanyId() : new UUID(0, 0);
        String  companyName        = hasCompanyHere ? playerCompany.getName() : "";
        int     companyWorkerCount = hasCompanyHere
                ? playerCompany.getWorkers().size() : 0;

        java.util.List<String> companyBuildingNames = new java.util.ArrayList<>();
        if (hasCompanyHere) {
            for (UUID bid : playerCompany.getBuildingIds()) {
                vdata.getBuildingById(bid)
                        .ifPresent(b -> companyBuildingNames.add(b.getName()));
            }
        }

        // Purchasable buildings
        java.util.Set<String> nonPurchasable = java.util.Set.of(
                "HOUSE", "TOWN_HALL", "GUARD_TOWER", "GUILD_HALL",
                "WELL", "BELL_TOWER", "PRISON");

        java.util.Set<UUID> allCompanyBuildingIds = new java.util.HashSet<>();
        java.util.Set<UUID> playerCompanyBuildingIds = new java.util.HashSet<>();
        companyData.getAllCompanies().forEach(c -> {
            c.getBuildingIds().forEach(allCompanyBuildingIds::add);
            if (c.getOwnerPlayerId().equals(player.getUUID()))
                c.getBuildingIds().forEach(playerCompanyBuildingIds::add);
        });

        java.util.List<tterrag1112.life_in_the_village.Networking
                .OpenVillageBookPacket.PurchasableBuildingEntry> purchasable =
                new java.util.ArrayList<>();
        for (UUID bid : village.getBuildingIds()) {
            vdata.getBuildingById(bid).ifPresent(b -> {
                if (nonPurchasable.contains(b.getType().name())) return;
                long price = tterrag1112.life_in_the_village.Village.Buildings
                        .HousePurchaseManager.calculatePrice(b, village, vdata);
                boolean inPlayerCompany  = playerCompanyBuildingIds.contains(bid);
                boolean inOtherCompany   = !inPlayerCompany
                        && allCompanyBuildingIds.contains(bid);
                purchasable.add(new tterrag1112.life_in_the_village.Networking
                        .OpenVillageBookPacket.PurchasableBuildingEntry(
                        bid, b.getName(), b.getType().name(),
                        price, inPlayerCompany, inOtherCompany));
            });
        }
        String seasonName = SeasonTracker.currentSeason(level).displayName;
        int openOrderCount = (int) vdata.getOrdersForVillage(village.getId())
                .stream().filter(CraftingOrder::isOpen).count();

        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new tterrag1112.life_in_the_village.Networking.OpenVillageBookPacket(
                        villageId, village.getName(), leaderName, tier,
                        pop, village.getTreasuryBronze(),
                        houses, needs, expansion, btypes,
                        playerWealth, playerRep, hasWarn,
                        kingdomName, activeEvent, routeCount,
                        hasCompanyHere, companyId, companyName,
                        companyWorkerCount, companyBuildingNames,
                        purchasable, seasonName, openOrderCount));
    }

    private static String formatEnum(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.charAt(0) + s.substring(1).toLowerCase().replace('_', ' ');
    }
}