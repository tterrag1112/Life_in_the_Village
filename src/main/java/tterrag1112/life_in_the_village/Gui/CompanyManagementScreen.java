package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Gui.Framework.*;
import tterrag1112.life_in_the_village.Guilds.Companies.Company;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Networking.CompanyActionPacket;
import tterrag1112.life_in_the_village.Networking.OpenCompanyManagementPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;

import java.util.*;

public class CompanyManagementScreen extends Screen {

    private static final int BOOK_W=420, BOOK_H=300, SIDEBAR_W=130, PAGE_PAD=14, ROW_H=24;
    private static final int PW = BOOK_W - SIDEBAR_W - PAGE_PAD * 2; // 262

    public enum Section { OVERVIEW, WORKERS, PRICES, SCHEDULE }

    private final OpenCompanyManagementPacket data;
    private int bookX, bookY;
    public Section currentSection = Section.OVERVIEW;

    private final TooltipLayer tooltips = new TooltipLayer();
    private Sidebar<Section> sidebar;
    private ScrollList<OpenCompanyManagementPacket.WorkerEntry> workerList;
    private ScrollList<OpenCompanyManagementPacket.PriceEntry>  priceList;
    private StyledEditBox depositBox, renameBox;

    public CompanyManagementScreen(OpenCompanyManagementPacket data) {
        super(Component.literal(data.companyName()));
        this.data = data;
    }

    public static void sendOpenPacket(ServerPlayer player, UUID companyId,
                                      ServerLevel level, CompanySavedData cdata,
                                      VillageSavedData vdata) {
        sendOpenPacket(player, companyId, level, cdata, vdata, "");
    }

    public static void sendOpenPacket(ServerPlayer player, UUID companyId,
                                      ServerLevel level, CompanySavedData cdata,
                                      VillageSavedData vdata, String restoreSection) {
        Company company = cdata.getById(companyId).orElse(null);
        if (company == null) return;

        List<OpenCompanyManagementPacket.WorkerEntry> workers = new ArrayList<>();
        for (Company.CompanyWorker w : company.getWorkers()) {
            String name = TownspersonMob.findByUUID(level, w.npcId()).map(n -> n.getNpcName()).orElse("Unknown NPC");
            workers.add(new OpenCompanyManagementPacket.WorkerEntry(w.npcId(), name, w.role().name(), w.wagePerDay(), w.assignedItemId(), w.dailyTargetCount()));
        }

        List<OpenCompanyManagementPacket.PriceEntry> prices = new ArrayList<>();
        for (Company.PriceOverride p : company.getAllPriceOverrides()) {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.Identifier.parse(p.itemId())).map(h -> h.value()).orElse(null);
            String display = item != null ? item.getDefaultInstance().getHoverName().getString() : p.itemId();
            prices.add(new OpenCompanyManagementPacket.PriceEntry(p.itemId(), display, p.pricePerUnit()));
        }

        List<OpenCompanyManagementPacket.BuildingEntry> buildings = new ArrayList<>();
        for (UUID bid : company.getBuildingIds())
            vdata.getBuildingById(bid).ifPresent(b -> buildings.add(new OpenCompanyManagementPacket.BuildingEntry(bid, b.getName(), b.getType().name())));

        var wallet = new net.minecraft.world.SimpleContainer(player.getInventory().getContainerSize());
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) wallet.setItem(i, player.getInventory().getItem(i).copy());
        long wealth = tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper.getWealth(wallet).toBronze();

        PacketDistributor.sendToPlayer(player, new OpenCompanyManagementPacket(
                companyId, company.getName(), company.getTreasuryBronze(), wealth,
                company.getWorkSchedule().startHour(), company.getWorkSchedule().endHour(),
                company.getEffectiveMinWage(vdata), workers, prices, buildings, restoreSection));
    }

    @Override
    protected void init() {
        bookX = (width - BOOK_W) / 2;
        bookY = (height - BOOK_H) / 2;
        if (!data.activeSection().isEmpty()) {
            try { currentSection = Section.valueOf(data.activeSection()); }
            catch (IllegalArgumentException ignored) {}
        }

        sidebar = new Sidebar<>(bookX + 2, bookY + 28, SIDEBAR_W - 2, 18,
                List.of(
                        new Sidebar.Entry<>(Section.OVERVIEW,  "Overview",                          true),
                        new Sidebar.Entry<>(Section.WORKERS,   "Workers (" + data.workers().size() + ")", true),
                        new Sidebar.Entry<>(Section.PRICES,    "Prices",                            true),
                        new Sidebar.Entry<>(Section.SCHEDULE,  "Schedule",                          true)
                ),
                () -> currentSection,
                s -> { currentSection = s; buildWidgets(); },
                this::drawSidebarFooter);

        int px = bookX + SIDEBAR_W + PAGE_PAD, py = bookY + 36;
        int maxY = bookY + BOOK_H - 34;
        workerList = new ScrollList<>(px, py + 14, PW, maxY - py - 14, ROW_H + 2, data.workers(), this::drawWorkerRow, null);
        priceList  = new ScrollList<>(px, py + 14, PW, maxY - py - 14, 18, data.priceOverrides(), this::drawPriceRow, this::onPriceClick);

        buildWidgets();
    }

    @Override public boolean isPauseScreen() { return false; }

    private void buildWidgets() {
        clearWidgets();
        depositBox = null;
        renameBox  = null;
        int px = bookX + SIDEBAR_W + PAGE_PAD, py = bookY + 36;
        if (currentSection == Section.OVERVIEW) buildOverviewWidgets(px, py);
        if (currentSection == Section.SCHEDULE) buildScheduleWidgets(px, py);
    }

    private void buildOverviewWidgets(int px, int py) {
        depositBox = new StyledEditBox(font, px, py + 50, PW - 60, 16, Component.literal("Amount"));
        depositBox.setMaxLength(10);
        depositBox.setValue("0");
        addRenderableWidget(depositBox);
        addRenderableWidget(StyledButton.builder(Component.literal("Deposit"), b -> {
            try {
                long amount = Long.parseLong(depositBox.getValue());
                if (amount > 0) sendAction(CompanyActionPacket.ActionType.DEPOSIT_TO_TREASURY, amount);
            } catch (NumberFormatException ignored) {}
        }).pos(px + PW - 56, py + 50).size(54, 16).build());

        renameBox = new StyledEditBox(font, px, py + 100, PW - 60, 16, Component.literal("New name"));
        renameBox.setMaxLength(32);
        renameBox.setValue(data.companyName());
        addRenderableWidget(renameBox);
        addRenderableWidget(StyledButton.builder(Component.literal("Rename"), b -> {
            String n = renameBox.getValue().trim();
            if (!n.isEmpty()) sendAction(CompanyActionPacket.ActionType.RENAME_COMPANY, n, 0, 0);
        }).pos(px + PW - 56, py + 100).size(54, 16).build());

        addRenderableWidget(StyledButton.builder(Component.literal("Dissolve Company"),
                b -> sendAction(CompanyActionPacket.ActionType.DISSOLVE_COMPANY, "", 0, 0))
                .pos(px, py + 140).size(PW, 16).build());
    }

    private void buildScheduleWidgets(int px, int py) {
        addRenderableWidget(StyledButton.builder(Component.literal("-"),
                b -> sendScheduleChange(Math.max(0, data.workStartHour() - 1), data.workEndHour()))
                .pos(px + 60, py + 30).size(16, 14).build());
        addRenderableWidget(StyledButton.builder(Component.literal("+"),
                b -> sendScheduleChange(Math.min(data.workEndHour() - 1, data.workStartHour() + 1), data.workEndHour()))
                .pos(px + 100, py + 30).size(16, 14).build());
        addRenderableWidget(StyledButton.builder(Component.literal("-"),
                b -> sendScheduleChange(data.workStartHour(), Math.max(data.workStartHour() + 1, data.workEndHour() - 1)))
                .pos(px + 60, py + 60).size(16, 14).build());
        addRenderableWidget(StyledButton.builder(Component.literal("+"),
                b -> sendScheduleChange(data.workStartHour(), Math.min(23, data.workEndHour() + 1)))
                .pos(px + 100, py + 60).size(16, 14).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        tooltips.reset();
        g.fill(0, 0, width, height, 0x88000000);
        Chrome.draw(g, bookX, bookY, Chrome.BOOK, Chrome.PARCHMENT);
        Chrome.drawSidebarBg(g, bookX, bookY, Chrome.BOOK);
        drawSidebarHeader(g);
        sidebar.render(g, mx, my);
        drawPageChrome(g);
        drawPageContent(g, mx, my);
        super.render(g, mx, my, pt);
        tooltips.flush(g);
    }

    private void drawSidebarHeader(GuiGraphics g) {
        g.drawString(font, "⌂ " + data.companyName(), bookX + 6, bookY + 8, BookScreenColors.DARK, false);
        g.fill(bookX + 4, bookY + 20, bookX + SIDEBAR_W - 4, bookY + 21, BookScreenColors.BORDER);
    }

    private void drawSidebarFooter(GuiGraphics g) {
        int tyY = bookY + BOOK_H - 36;
        g.fill(bookX + 4, tyY, bookX + SIDEBAR_W - 4, tyY + 1, BookScreenColors.BORDER);
        g.drawString(font, "Treasury",                       bookX + 6, tyY + 4,  BookScreenColors.MID,  false);
        g.drawString(font, CoinRenderer.format(data.treasuryBronze()), bookX + 6, tyY + 14, BookScreenColors.GOLD, false);
    }

    private void drawPageChrome(GuiGraphics g) {
        g.fill(bookX + SIDEBAR_W, bookY + 28, bookX + BOOK_W, bookY + 29, BookScreenColors.BORDER);
        g.fill(bookX + SIDEBAR_W, bookY + BOOK_H - 30, bookX + BOOK_W, bookY + BOOK_H - 29, BookScreenColors.BORDER);
        g.drawString(font, sectionLabel(currentSection), bookX + SIDEBAR_W + PAGE_PAD, bookY + 10, BookScreenColors.DARK, false);
        drawFlourish(g, bookX + 4,           bookY + 4);
        drawFlourish(g, bookX + BOOK_W - 12, bookY + 4);
        drawFlourish(g, bookX + 4,           bookY + BOOK_H - 12);
        drawFlourish(g, bookX + BOOK_W - 12, bookY + BOOK_H - 12);
    }

    private void drawFlourish(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 8, y + 1, BookScreenColors.BORDER);
        g.fill(x, y, x + 1, y + 8, BookScreenColors.BORDER);
    }

    private void drawPageContent(GuiGraphics g, int mx, int my) {
        int px = bookX + SIDEBAR_W + PAGE_PAD, py = bookY + 36;
        int maxY = bookY + BOOK_H - 34;
        switch (currentSection) {
            case OVERVIEW  -> drawOverview(g, px, py, maxY);
            case WORKERS   -> drawWorkers(g, px, py, mx, my);
            case PRICES    -> drawPrices(g, px, py, mx, my);
            case SCHEDULE  -> drawSchedule(g, px, py, maxY);
        }
    }

    private void drawOverview(GuiGraphics g, int px, int py, int maxY) {
        int y = py;
        g.drawString(font, "Treasury", px, y, BookScreenColors.MID, false);
        g.fill(px, y + 10, px + PW, y + 11, BookScreenColors.BORDER); y += 14;
        g.drawString(font, CoinRenderer.format(data.treasuryBronze()), px, y, BookScreenColors.GOLD, false);
        g.drawString(font, "Your wallet: " + CoinRenderer.format(data.playerWealthBronze()), px + 100, y, BookScreenColors.MID, false);
        y += 20;
        g.drawString(font, "Deposit to treasury:", px, y, BookScreenColors.DARK, false);
        g.drawString(font, "1g=" + CurrencyValue.GOLD_VALUE + "b  1s=" + CurrencyValue.SILVER_VALUE + "b",
                px, py + 70, BookScreenColors.LIGHT, false);
        g.fill(px, py + 80, px + PW, py + 81, BookScreenColors.BORDER);
        g.drawString(font, "Rename company:", px, py + 89, BookScreenColors.DARK, false);
        g.fill(px, py + 130, px + PW, py + 131, BookScreenColors.BORDER);
        if (py + 138 < maxY)
            g.drawString(font, "Min. wage: " + CoinRenderer.format(data.effectiveMinWage()) + "/day",
                    px, py + 138, data.effectiveMinWage() > 1 ? BookScreenColors.AMBER : BookScreenColors.MID, false);
    }

    private void drawWorkers(GuiGraphics g, int px, int py, int mx, int my) {
        int y = py;
        g.fill(px, y, px + PW, y + 12, 0xFFE8E0C8);
        g.drawString(font, "Worker",   px + 2,        y + 2, BookScreenColors.MID, false);
        g.drawString(font, "Role",     px + 100,       y + 2, BookScreenColors.MID, false);
        g.drawString(font, "Wage/day", px + PW - 110,  y + 2, BookScreenColors.MID, false);
        if (data.workers().isEmpty()) {
            g.drawCenteredString(font, "No workers hired.",                               px + PW / 2, py + 60, BookScreenColors.MID);
            g.drawCenteredString(font, "Interact with an unemployed NPC while holding a coin.", px + PW / 2, py + 74, BookScreenColors.LIGHT);
        } else {
            workerList.render(g, mx, my);
        }
    }

    private void drawWorkerRow(GuiGraphics g, int rx, int ry, int rw, int rh,
                               OpenCompanyManagementPacket.WorkerEntry w, boolean hovered) {
        boolean active = !w.assignedItemId().isEmpty();
        g.fill(rx, ry, rx + rw, ry + ROW_H, active ? BookScreenColors.GREEN_BG : BookScreenColors.PARCHMENT);
        g.renderOutline(rx, ry, rw, ROW_H, BookScreenColors.BORDER);

        // Truncate name to fit
        String name = w.npcName();
        while (font.width(name) > 90 && name.length() > 3) name = name.substring(0, name.length() - 1);
        if (!name.equals(w.npcName())) name += "…";

        int mid = ry + (ROW_H - 8) / 2;
        g.drawString(font, name, rx + 3, mid, BookScreenColors.DARK, false);
        g.drawString(font, formatRole(w.role()), rx + 100, mid, BookScreenColors.MID, false);

        // Wage text + action mini-buttons
        g.drawString(font, CoinRenderer.format(w.wagePerDay()), rx + rw - 135, mid, BookScreenColors.GOLD, false);
        int bMid = ry + (ROW_H - 14) / 2;
        drawMini(g, rx + rw - 110, bMid, 14, 14, "−", false);
        drawMini(g, rx + rw - 94,  bMid, 14, 14, "+",      false);
        drawMini(g, rx + rw - 78,  bMid, 32, 14, "Manage", false);
        drawMini(g, rx + rw - 44,  bMid, 30, 14, "Fire",   false);

        if (active)
            g.drawString(font, "→ " + formatItemId(w.assignedItemId()) + " ×" + w.dailyTargetCount(),
                    rx + 3, ry + ROW_H - 8, BookScreenColors.GREEN_TXT, false);

        if (hovered) tooltips.queue(rx + 3, ry + ROW_H,
                List.of(Component.literal(w.npcName() + " • " + formatRole(w.role()))));
    }

    private void drawPrices(GuiGraphics g, int px, int py, int mx, int my) {
        int y = py;
        g.drawString(font, "Custom sell prices", px, y, BookScreenColors.MID, false);
        g.fill(px, y + 10, px + PW, y + 11, BookScreenColors.BORDER);
        if (data.priceOverrides().isEmpty()) {
            g.drawCenteredString(font, "No price overrides set.",             px + PW / 2, py + 28, BookScreenColors.MID);
            g.drawCenteredString(font, "Assign a SELLER worker to add items.", px + PW / 2, py + 42, BookScreenColors.LIGHT);
        } else {
            priceList.render(g, mx, my);
        }
    }

    private void drawPriceRow(GuiGraphics g, int rx, int ry, int rw, int rh,
                              OpenCompanyManagementPacket.PriceEntry p, boolean hovered) {
        g.fill(rx, ry, rx + rw, ry + 14, BookScreenColors.PARCHMENT);
        g.renderOutline(rx, ry, rw, 14, BookScreenColors.BORDER);
        g.drawString(font, p.itemName(),                          rx + 3,      ry + 3, BookScreenColors.DARK, false);
        g.drawString(font, CoinRenderer.format(p.pricePerUnit()), rx + rw - 80, ry + 3, BookScreenColors.GOLD, false);
        drawMini(g, rx + rw - 36, ry, 16, 14, "−", false);
        drawMini(g, rx + rw - 18, ry, 16, 14, "+",      false);
    }

    private boolean onPriceClick(OpenCompanyManagementPacket.PriceEntry p, int btn, double relX, double relY) {
        if (relX >= PW - 36 && relX < PW - 20) { sendPriceChange(p.itemId(), Math.max(1, p.pricePerUnit() - 1)); return true; }
        if (relX >= PW - 18 && relX < PW - 2)  { sendPriceChange(p.itemId(), p.pricePerUnit() + 1);              return true; }
        return false;
    }

    private void drawMini(GuiGraphics g, int bx, int by, int bw, int bh, String label, boolean hov) {
        g.fill(bx, by, bx + bw, by + bh, hov ? BookScreenColors.HIGHLIGHT : BookScreenColors.PARCHMENT);
        g.renderOutline(bx, by, bw, bh, BookScreenColors.BORDER);
        int tw = font.width(label);
        g.drawString(font, label, bx + (bw - tw) / 2, by + (bh - 8) / 2, BookScreenColors.DARK, false);
    }

    private void drawSchedule(GuiGraphics g, int px, int py, int maxY) {
        int y = py;
        g.drawString(font, "Work Hours", px, y, BookScreenColors.MID, false);
        g.fill(px, y + 10, px + PW, y + 11, BookScreenColors.BORDER); y += 20;
        g.drawString(font, "Start: " + formatHour(data.workStartHour()), px, y + 8, BookScreenColors.DARK, false);
        y += 20;
        g.drawString(font, "End:   " + formatHour(data.workEndHour()), px, y + 8 + 20, BookScreenColors.DARK, false);
        y += 60;

        // 24-hour visual bar
        g.fill(px, y, px + PW, y + 14, 0xFFDDD8C0);
        g.renderOutline(px, y, PW, 14, BookScreenColors.BORDER);
        int startPx = px + (int)(data.workStartHour() / 24.0 * PW);
        int endPx   = px + (int)(data.workEndHour()   / 24.0 * PW);
        g.fill(startPx, y, endPx, y + 14, BookScreenColors.GREEN_BG);
        g.fill(startPx, y, startPx + 1, y + 14, BookScreenColors.GREEN_TXT);
        g.fill(endPx - 1, y, endPx, y + 14, BookScreenColors.RED_TXT);
        for (int h = 0; h <= 24; h += 6) {
            int tx = px + (int)(h / 24.0 * PW);
            g.fill(tx, y + 12, tx + 1, y + 14, BookScreenColors.DARK);
            g.drawString(font, String.valueOf(h), tx, y + 16, BookScreenColors.LIGHT, false);
        }
        y += 30;
        if (y + 10 < maxY)
            g.drawString(font, "Workers operate during highlighted hours.", px, y, BookScreenColors.LIGHT, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (consumed) return super.mouseClicked(event, consumed);
        double mx = event.x(), my = event.y();
        if (sidebar.mouseClicked(mx, my)) return true;

        if (currentSection == Section.WORKERS && !data.workers().isEmpty()) {
            int listY = bookY + 36 + 14;
            int listH = bookY + BOOK_H - 34 - listY;
            if (my >= listY && my < listY + listH && mx >= bookX + SIDEBAR_W + PAGE_PAD) {
                int rowIdx = workerList.getScrollOffset() + (int)(my - listY) / (ROW_H + 2);
                if (rowIdx >= 0 && rowIdx < data.workers().size()) {
                    var w   = data.workers().get(rowIdx);
                    double rx = mx - (bookX + SIDEBAR_W + PAGE_PAD);
                    if (rx >= PW - 110 && rx < PW - 96) { sendActionLong(CompanyActionPacket.ActionType.SET_WORKER_WAGE, w.npcId(), Math.max(data.effectiveMinWage(), w.wagePerDay() - 1)); return true; }
                    if (rx >= PW -  94 && rx < PW - 80) { sendActionLong(CompanyActionPacket.ActionType.SET_WORKER_WAGE, w.npcId(), w.wagePerDay() + 1); return true; }
                    if (rx >= PW -  78 && rx < PW - 46) { ClientPacketDistributor.sendToServer(new CompanyActionPacket(CompanyActionPacket.ActionType.OPEN_WORKER_SCREEN, data.companyId(), w.npcId(), "", 0L, 0)); return true; }
                    if (rx >= PW -  44 && rx < PW - 14) { sendAction(CompanyActionPacket.ActionType.FIRE_NPC, w.npcId()); return true; }
                }
            }
        }
        if (currentSection == Section.PRICES && priceList.mouseClicked(mx, my, event.button())) return true;
        return super.mouseClicked(event, consumed);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (currentSection == Section.WORKERS && workerList.mouseScrolled(mx, my, dy)) return true;
        if (currentSection == Section.PRICES  && priceList.mouseScrolled(mx, my, dy))  return true;
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (sidebar.keyPressed(event.key())) return true;
        return super.keyPressed(event);
    }

    private void sendAction(CompanyActionPacket.ActionType type, long longParam) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(type, data.companyId(), new UUID(0,0), "", longParam, 0));
    }
    private void sendAction(CompanyActionPacket.ActionType type, String str, long lp, int ip) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(type, data.companyId(), new UUID(0,0), str, lp, ip));
    }
    private void sendAction(CompanyActionPacket.ActionType type, UUID targetId) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(type, data.companyId(), targetId, "", 0, 0));
    }
    private void sendActionLong(CompanyActionPacket.ActionType type, UUID targetId, long lp) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(type, data.companyId(), targetId, "", lp, 0));
    }
    private void sendPriceChange(String itemId, long price) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(CompanyActionPacket.ActionType.SET_ITEM_PRICE, data.companyId(), new UUID(0,0), itemId, price, 0));
    }
    private void sendScheduleChange(int startH, int endH) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(CompanyActionPacket.ActionType.SET_WORK_HOURS, data.companyId(), new UUID(0,0), "", endH, startH));
    }

    private String sectionLabel(Section s) {
        return switch (s) {
            case OVERVIEW -> "Overview";
            case WORKERS  -> "Workers (" + data.workers().size() + ")";
            case PRICES   -> "Prices";
            case SCHEDULE -> "Schedule";
        };
    }
    private String formatHour(int h)    { return String.format("%02d:00", h); }
    private String formatRole(String r) { return (r == null || r.isEmpty()) ? "—" : r.charAt(0) + r.substring(1).toLowerCase(); }
    private String formatItemId(String id) {
        if (id == null || id.isEmpty()) return "—";
        String path = id.contains(":") ? id.split(":")[1] : id;
        return path.replace('_', ' ');
    }
}
