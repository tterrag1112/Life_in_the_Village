package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Company;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Networking.CompanyActionPacket;
import tterrag1112.life_in_the_village.Networking.OpenCompanyManagementPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;

import java.awt.print.Book;
import java.util.*;

public class CompanyManagementScreen extends Screen {

    // -------------------------------------------------------------------------
    // Layout — same book chrome
    // -------------------------------------------------------------------------
    private static final int BOOK_W    = 420;
    private static final int BOOK_H    = 300;
    private static final int SIDEBAR_W = 130;
    private static final int PAGE_PAD  = 14;
    private static final int ROW_H     = 24;

    // Colors — same palette
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

    public enum Section { OVERVIEW, WORKERS, PRICES, SCHEDULE }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final OpenCompanyManagementPacket data;
    private int bookX, bookY;
    public Section currentSection = Section.OVERVIEW;
    private int workerScroll = 0;
    private EditBox depositBox;
    private EditBox renameBox;

    // -------------------------------------------------------------------------
    // Constructor & server-side helpers
    // -------------------------------------------------------------------------

    public CompanyManagementScreen(OpenCompanyManagementPacket data) {
        super(Component.literal(data.companyName()));
        this.data = data;
    }
    public static void sendOpenPacket(ServerPlayer player, UUID companyId,
                                      ServerLevel level, CompanySavedData cdata, VillageSavedData vdata) {
        sendOpenPacket(player, companyId, level, cdata, vdata, "");
    }

    /** Called server-side to build and send the packet. */
    public static void sendOpenPacket(ServerPlayer player, UUID companyId,
                                      ServerLevel level, CompanySavedData cdata, VillageSavedData vdata,
                                      String restoreSection) {

        Company company = cdata.getById(companyId).orElse(null);
        if (company == null) return;

        // Workers
        List<OpenCompanyManagementPacket.WorkerEntry> workers = new ArrayList<>();
        for (Company.CompanyWorker w : company.getWorkers()) {
            // Find NPC name
            String npcName = TownspersonMob.findByUUID(level, w.npcId())
                    .map(npc -> npc.getNpcName())
                    .orElse("Unknown NPC");
            workers.add(new OpenCompanyManagementPacket.WorkerEntry(
                    w.npcId(), npcName, w.role().name(),
                    w.wagePerDay(), w.assignedItemId(), w.dailyTargetCount()));
        }

        // Price overrides — resolve item display names
        List<OpenCompanyManagementPacket.PriceEntry> prices = new ArrayList<>();
        for (Company.PriceOverride p : company.getAllPriceOverrides()) {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.Identifier.parse(p.itemId()))
                    .map(h -> h.value()).orElse(null);
            String displayName = item != null
                    ? item.getDefaultInstance().getHoverName().getString()
                    : p.itemId();
            prices.add(new OpenCompanyManagementPacket.PriceEntry(
                    p.itemId(), displayName, p.pricePerUnit()));
        }

        // Buildings
        List<OpenCompanyManagementPacket.BuildingEntry> buildings = new ArrayList<>();
        for (UUID bid : company.getBuildingIds()) {
            vdata.getBuildingById(bid).ifPresent(b ->
                    buildings.add(new OpenCompanyManagementPacket.BuildingEntry(
                            bid, b.getName(),
                            b.getType().name())));
        }

        // Player wealth
        var playerContainer = new net.minecraft.world.SimpleContainer(
                player.getInventory().getContainerSize());
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            playerContainer.setItem(i, player.getInventory().getItem(i).copy());
        long wealth = tterrag1112.life_in_the_village.Village.Economy.Currency
                .CoinHelper.getWealth(playerContainer).toBronze();

        PacketDistributor.sendToPlayer(player,
                new OpenCompanyManagementPacket(
                        companyId, company.getName(),
                        company.getTreasuryBronze(), wealth,
                        company.getWorkSchedule().startHour(),
                        company.getWorkSchedule().endHour(),
                        company.getEffectiveMinWage(vdata),
                        workers, prices, buildings, restoreSection));
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void init() {
        bookX = (width - BOOK_W) / 2;
        bookY = (height - BOOK_H) / 2;
        buildWidgets();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // -------------------------------------------------------------------------
    // Widgets
    // -------------------------------------------------------------------------

    private void buildWidgets() {
        clearWidgets();
        depositBox = null;
        renameBox  = null;

        int px   = bookX + SIDEBAR_W + PAGE_PAD;
        int py   = bookY + 36;
        int pw   = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;
        int maxY = bookY + BOOK_H - 34;

        switch (currentSection) {
            case OVERVIEW  -> buildOverviewWidgets(px, py, pw);
            case WORKERS   -> buildWorkerWidgets(px, py, pw, maxY);
            case PRICES    -> buildPriceWidgets(px, py, pw, maxY);
            case SCHEDULE  -> buildScheduleWidgets(px, py, pw);
        }
    }

    private void buildOverviewWidgets(int px, int py, int pw) {
        // Deposit field + button
        depositBox = new EditBox(font, px, py + 50, pw - 60, 16,
                Component.literal("Amount"));
        depositBox.setMaxLength(10);
        depositBox.setValue("0");
        addRenderableWidget(depositBox);

        addRenderableWidget(Button.builder(
                        Component.literal("Deposit"),
                        b -> {
                            try {
                                long amount = Long.parseLong(depositBox.getValue());
                                if (amount > 0)
                                    sendAction(CompanyActionPacket.ActionType
                                            .DEPOSIT_TO_TREASURY, amount);
                            } catch (NumberFormatException ignored) {}
                        })
                .pos(px + pw - 56, py + 50).size(54, 16).build());

        // Rename field + button
        renameBox = new EditBox(font, px, py + 100, pw - 60, 16,
                Component.literal("New name"));
        renameBox.setMaxLength(32);
        renameBox.setValue(data.companyName());
        addRenderableWidget(renameBox);

        addRenderableWidget(Button.builder(
                        Component.literal("Rename"),
                        b -> {
                            String newName = renameBox.getValue().trim();
                            if (!newName.isEmpty())
                                sendAction(CompanyActionPacket.ActionType
                                        .RENAME_COMPANY, newName, 0, 0);
                        })
                .pos(px + pw - 56, py + 100).size(54, 16).build());

        // Dissolve
        addRenderableWidget(Button.builder(
                        Component.literal("Dissolve Company"),
                        b -> sendAction(CompanyActionPacket.ActionType
                                .DISSOLVE_COMPANY, "", 0, 0))
                .pos(px, py + 140).size(pw, 16).build());
    }


    private void buildPriceWidgets(int px, int py, int pw, int maxY) {
        int y = py + 14;
        for (OpenCompanyManagementPacket.PriceEntry price : data.priceOverrides()) {
            if (y + 16 > maxY) break;
            final String itemId = price.itemId();

            addRenderableWidget(Button.builder(Component.literal("-"),
                            b -> sendPriceChange(itemId,
                                    Math.max(1, price.pricePerUnit() - 1)))
                    .pos(px + pw - 36, y).size(16, 14).build());

            addRenderableWidget(Button.builder(Component.literal("+"),
                            b -> sendPriceChange(itemId, price.pricePerUnit() + 1))
                    .pos(px + pw - 18, y).size(16, 14).build());

            y += 18;
        }
    }

    private void buildScheduleWidgets(int px, int py, int pw) {
        // Start hour - / +
        addRenderableWidget(Button.builder(Component.literal("-"),
                        b -> sendScheduleChange(
                                Math.max(0, data.workStartHour() - 1),
                                data.workEndHour()))
                .pos(px + 60, py + 30).size(16, 14).build());

        addRenderableWidget(Button.builder(Component.literal("+"),
                        b -> sendScheduleChange(
                                Math.min(data.workEndHour() - 1,
                                        data.workStartHour() + 1),
                                data.workEndHour()))
                .pos(px + 100, py + 30).size(16, 14).build());

        // End hour - / +
        addRenderableWidget(Button.builder(Component.literal("-"),
                        b -> sendScheduleChange(data.workStartHour(),
                                Math.max(data.workStartHour() + 1,
                                        data.workEndHour() - 1)))
                .pos(px + 60, py + 60).size(16, 14).build());

        addRenderableWidget(Button.builder(Component.literal("+"),
                        b -> sendScheduleChange(data.workStartHour(),
                                Math.min(23, data.workEndHour() + 1)))
                .pos(px + 100, py + 60).size(16, 14).build());
    }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88000000);
        drawBook(g);
        drawSidebar(g);
        drawPageContent(g, mx, my);
        super.render(g, mx, my, pt);
    }

    private void drawBook(GuiGraphics g) {
        g.fill(bookX + 3, bookY + 3,
                bookX + BOOK_W + 3, bookY + BOOK_H + 3, 0x44000000);
        g.fill(bookX, bookY, bookX + BOOK_W, bookY + BOOK_H, COL_PARCHMENT);
        g.renderOutline(bookX, bookY, BOOK_W, BOOK_H, COL_BORDER);
        g.renderOutline(bookX + 2, bookY + 2, BOOK_W - 4, BOOK_H - 4,
                COL_HIGHLIGHT);
        g.fill(bookX + SIDEBAR_W, bookY + 28,
                bookX + BOOK_W, bookY + 29, COL_BORDER);
        g.fill(bookX + SIDEBAR_W, bookY + BOOK_H - 30,
                bookX + BOOK_W, bookY + BOOK_H - 29, COL_BORDER);
        String title = sectionLabel(currentSection);
        g.drawString(font, title,
                bookX + SIDEBAR_W + PAGE_PAD, bookY + 10, COL_DARK, false);
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

        // Company icon + name
        g.drawString(font, "\u2302 " + data.companyName(),
                bookX + 6, bookY + 8, COL_DARK, false);
        g.fill(bookX + 4, bookY + 20,
                bookX + SIDEBAR_W - 4, bookY + 21, COL_BORDER);

        int ny = bookY + 28;
        for (Section s : Section.values()) {
            boolean active = s == currentSection;
            if (active) {
                g.fill(bookX + 2, ny - 1,
                        bookX + SIDEBAR_W - 1, ny + 9, COL_HIGHLIGHT);
                g.fill(bookX + 2, ny - 1, bookX + 4, ny + 9, COL_GOLD);
            }
            g.drawString(font, (active ? "> " : "  ") + sectionLabel(s),
                    bookX + 6, ny, active ? COL_DARK : COL_MID, false);
            ny += 18;
        }

        // Treasury display at bottom of sidebar
        int tyY = bookY + BOOK_H - 36;
        g.fill(bookX + 4, tyY, bookX + SIDEBAR_W - 4, tyY + 1, COL_BORDER);
        g.drawString(font, "Treasury", bookX + 6, tyY + 4, COL_MID, false);
        g.drawString(font, formatBronze(data.treasuryBronze()),
                bookX + 6, tyY + 14, COL_GOLD, false);
    }

    private void drawPageContent(GuiGraphics g, int mx, int my) {
        int px   = bookX + SIDEBAR_W + PAGE_PAD;
        int py   = bookY + 36;
        int pw   = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;
        int maxY = bookY + BOOK_H - 34;

        switch (currentSection) {
            case OVERVIEW  -> drawOverview(g, px, py, pw, maxY);
            case WORKERS   -> drawWorkers(g, px, py, pw, maxY);
            case PRICES    -> drawPrices(g, px, py, pw, maxY);
            case SCHEDULE  -> drawSchedule(g, px, py, pw, maxY);
        }
    }

    private void drawOverview(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;
        g.drawString(font, "Treasury", px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 14;
        g.drawString(font, formatBronze(data.treasuryBronze()),
                px, y, COL_GOLD, false);
        g.drawString(font, "Your wallet: " + formatBronze(data.playerWealthBronze()),
                px + 100, y, COL_MID, false);
        y += 20;
        // Deposit label is above the widget
        g.drawString(font, "Deposit to treasury:", px, y, COL_DARK, false);
        y += 14; // widget at y+50 from py — handled by buildOverviewWidgets

        // Show denomination guide below deposit field
        g.drawString(font, "1g=" + CurrencyValue.GOLD_VALUE
                        + "b  1s=" + CurrencyValue.SILVER_VALUE + "b",
                px, py + 70, COL_LIGHT, false);

        y = py + 80;
        g.fill(px, y, px + pw, y + 1, COL_BORDER);
        y += 8;
        g.drawString(font, "Rename company:", px, y, COL_DARK, false);

        y = py + 130;
        g.fill(px, y, px + pw, y + 1, COL_BORDER);

        // Min wage notice
        if (y + 28 < maxY) {
            y += 8;
            g.drawString(font,
                    "Min. wage: " + formatBronze(data.effectiveMinWage()) + "/day",
                    px, y, data.effectiveMinWage() > 1 ? COL_AMBER : COL_MID, false);
        }
    }

    // drawWorkers — add ROW_H to accommodate the extra buttons, add Manage button column
    private void drawWorkers(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;

        // Column headers
        g.fill(px, y, px + pw, y + 12, 0xFFE8E0C8);
        g.drawString(font, "Worker",   px + 2,       y + 2, COL_MID, false);
        g.drawString(font, "Role",     px + 100,      y + 2, COL_MID, false);
        g.drawString(font, "Wage/day", px + pw - 110, y + 2, COL_MID, false);
        y += 14;

        int visibleStart = workerScroll;
        int index = 0;

        for (OpenCompanyManagementPacket.WorkerEntry worker : data.workers()) {
            if (index < visibleStart) { index++; continue; }
            if (y + ROW_H > maxY - 12) break;

            boolean isWorking = !worker.assignedItemId().isEmpty();
            int rowBg = isWorking ? COL_GREEN_BG : COL_PARCHMENT;
            g.fill(px, y, px + pw, y + ROW_H, rowBg);
            g.renderOutline(px, y, pw, ROW_H, COL_BORDER);

            // Name
            String name = worker.npcName();
            while (font.width(name) > 90 && name.length() > 3)
                name = name.substring(0, name.length() - 1);
            if (!name.equals(worker.npcName())) name += "…";
            g.drawString(font, name, px + 3, y + (ROW_H - 8) / 2, COL_DARK, false);

            // Role
            g.drawString(font, formatRole(worker.role()),
                    px + 100, y + (ROW_H - 8) / 2, COL_MID, false);

            // Wage — coin icons
            CoinRenderer.renderCoinRow(g, worker.wagePerDay(),
                    px + pw - 110, y + (ROW_H - 16) / 2);

            // Task sub-label
            if (!worker.assignedItemId().isEmpty()) {
                g.drawString(font,
                        "\u2192 " + formatItemId(worker.assignedItemId())
                                + " \u00D7" + worker.dailyTargetCount(),
                        px + 3, y + ROW_H - 8, COL_GREEN_TXT, false);
            }

            y += ROW_H + 2;
            index++;
        }

        if (data.workers().isEmpty()) {
            g.drawCenteredString(font, "No workers hired.",
                    px + pw / 2, py + 60, COL_MID);
            g.drawCenteredString(font,
                    "Interact with an unemployed NPC while holding a coin.",
                    px + pw / 2, py + 74, COL_LIGHT);
        }
    }

    // buildWorkerWidgets — wages stay here, Manage button opens worker screen
    private void buildWorkerWidgets(int px, int py, int pw, int maxY) {
        int y = py + 14;
        int visibleStart = workerScroll;
        int index = 0;

        for (OpenCompanyManagementPacket.WorkerEntry worker : data.workers()) {
            if (index < visibleStart) { index++; continue; }
            if (y + ROW_H > maxY - 12) break;

            final UUID npcId      = worker.npcId();
            final long currentWage = worker.wagePerDay();
            int mid = y + (ROW_H - 14) / 2;

            // Wage −
            addRenderableWidget(Button.builder(Component.literal("−"),
                            b -> sendActionLong(CompanyActionPacket.ActionType.SET_WORKER_WAGE,
                                    npcId,
                                    Math.max(data.effectiveMinWage(), currentWage - 1)))
                    .pos(px + pw - 110, mid).size(14, 14).build());

            // Wage +
            addRenderableWidget(Button.builder(Component.literal("+"),
                            b -> sendActionLong(CompanyActionPacket.ActionType.SET_WORKER_WAGE,
                                    npcId, currentWage + 1))
                    .pos(px + pw - 94, mid).size(14, 14).build());

            // Fire
            addRenderableWidget(Button.builder(Component.literal("Fire"),
                            b -> sendAction(CompanyActionPacket.ActionType.FIRE_NPC, npcId))
                    .pos(px + pw - 44, mid).size(30, 14).build());

            // Manage — opens CompanyWorkerScreen for this NPC
            addRenderableWidget(Button.builder(Component.literal("Manage"),
                            b -> ClientPacketDistributor.sendToServer(
                                    new CompanyActionPacket(
                                            CompanyActionPacket.ActionType.OPEN_WORKER_SCREEN,
                                            data.companyId(), npcId, "", 0L, 0)))
                    .pos(px + pw - 78, mid).size(32, 14).build());

            y += ROW_H + 2;
            index++;
        }

        // Scroll
        if (workerScroll > 0)
            addRenderableWidget(Button.builder(Component.literal("▲"),
                            b -> { workerScroll--; buildWidgets(); })
                    .pos(px + pw - 14, py + 14).size(12, 10).build());

        int maxVisible = (maxY - (py + 14)) / (ROW_H + 2);
        if (workerScroll + maxVisible < data.workers().size())
            addRenderableWidget(Button.builder(Component.literal("▼"),
                            b -> { workerScroll++; buildWidgets(); })
                    .pos(px + pw - 14, maxY - 12).size(12, 10).build());
    }

    private void drawPrices(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;
        g.drawString(font, "Custom sell prices", px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 14;

        if (data.priceOverrides().isEmpty()) {
            g.drawCenteredString(font, "No price overrides set.",
                    px + pw / 2, y + 20, COL_MID);
            g.drawCenteredString(font,
                    "Assign a SELLER worker to add items.",
                    px + pw / 2, y + 34, COL_LIGHT);
            return;
        }

        for (OpenCompanyManagementPacket.PriceEntry price : data.priceOverrides()) {
            if (y + 16 > maxY) break;
            g.fill(px, y, px + pw, y + 14, COL_PARCHMENT);
            g.renderOutline(px, y, pw, 14, COL_BORDER);
            g.drawString(font, price.itemName(), px + 3, y + 3, COL_DARK, false);
            g.drawString(font, formatBronze(price.pricePerUnit()),
                    px + pw - 80, y + 3, COL_GOLD, false);
            y += 18;
        }
    }

    private void drawSchedule(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;
        g.drawString(font, "Work Hours", px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 20;

        g.drawString(font, "Start: " + formatHour(data.workStartHour()),
                px, y + 8, COL_DARK, false);
        // - button at px+60, + at px+100 (built by buildScheduleWidgets)
        y += 20;

        g.drawString(font, "End:   " + formatHour(data.workEndHour()),
                px, y + 8 + 20, COL_DARK, false);

        y += 60;
        // Visual schedule bar — 24 hours across pw
        g.fill(px, y, px + pw, y + 14, 0xFFDDD8C0);
        g.renderOutline(px, y, pw, 14, COL_BORDER);
        int startPx = px + (int)(data.workStartHour() / 24.0 * pw);
        int endPx   = px + (int)(data.workEndHour()   / 24.0 * pw);
        g.fill(startPx, y, endPx, y + 14, COL_GREEN_BG);
        g.fill(startPx, y, startPx + 1, y + 14, COL_GREEN_TXT);
        g.fill(endPx - 1, y, endPx, y + 14, COL_RED_TXT);
        // Hour tick marks
        for (int h = 0; h <= 24; h += 6) {
            int tx = px + (int)(h / 24.0 * pw);
            g.fill(tx, y + 12, tx + 1, y + 14, COL_DARK);
            g.drawString(font, String.valueOf(h), tx, y + 16, COL_LIGHT, false);
        }

        y += 30;
        if (y + 10 < maxY) {
            g.drawString(font, "Workers operate during highlighted hours.",
                    px, y, COL_LIGHT, false);
        }
    }

    // -------------------------------------------------------------------------
    // Mouse — sidebar navigation
    // -------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (consumed) return super.mouseClicked(event, consumed);
        double mx = event.x(), my = event.y();
        int ny = bookY + 28;
        for (Section s : Section.values()) {
            if (mx >= bookX + 2 && mx <= bookX + SIDEBAR_W - 1
                    && my >= ny - 1 && my <= ny + 9) {
                currentSection = s;
                workerScroll = 0;
                buildWidgets();
                return true;
            }
            ny += 18;
        }
        return super.mouseClicked(event, consumed);
    }

    // -------------------------------------------------------------------------
    // Packet helpers
    // -------------------------------------------------------------------------

    private void sendAction(CompanyActionPacket.ActionType type,
                            long longParam) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                type, data.companyId(),
                new UUID(0, 0), "", longParam, 0));
    }

    private void sendAction(CompanyActionPacket.ActionType type,
                            String strParam, long longParam, int intParam) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                type, data.companyId(),
                new UUID(0, 0), strParam, longParam, intParam));
    }

    private void sendAction(CompanyActionPacket.ActionType type, UUID targetId) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                type, data.companyId(), targetId, "", 0, 0));
    }

    private void sendActionLong(CompanyActionPacket.ActionType type,
                                UUID targetId, long longParam) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                type, data.companyId(), targetId, "", longParam, 0));
    }

    private void sendPriceChange(String itemId, long newPrice) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                CompanyActionPacket.ActionType.SET_ITEM_PRICE,
                data.companyId(), new UUID(0, 0), itemId, newPrice, 0));
    }

    private void sendScheduleChange(int startH, int endH) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                CompanyActionPacket.ActionType.SET_WORK_HOURS,
                data.companyId(), new UUID(0, 0), "", endH, startH));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String sectionLabel(Section s) {
        return switch (s) {
            case OVERVIEW  -> "Overview";
            case WORKERS   -> "Workers (" + data.workers().size() + ")";
            case PRICES    -> "Prices";
            case SCHEDULE  -> "Schedule";
        };
    }

    private String formatBronze(long bronze) {
        return CoinRenderer.format(bronze);
    }

    private String formatHour(int hour) {
        return String.format("%02d:00", hour);
    }

    private String formatRole(String role) {
        if (role == null || role.isEmpty()) return "—";
        return role.charAt(0) + role.substring(1).toLowerCase();
    }

    private String formatItemId(String itemId) {
        if (itemId == null || itemId.isEmpty()) return "—";
        String path = itemId.contains(":") ? itemId.split(":")[1] : itemId;
        return path.replace('_', ' ');
    }

}