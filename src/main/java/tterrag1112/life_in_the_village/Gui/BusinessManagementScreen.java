package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Gui.Framework.*;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Networking.BusinessActionPacket;
import tterrag1112.life_in_the_village.Networking.OpenBusinessManagementPacket;
import tterrag1112.life_in_the_village.Networking.OpenBusinessManagementPacket.ProductionDiagnostic;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Tasks.Assignment;
import tterrag1112.life_in_the_village.Npc.Tasks.IssuerRef;
import tterrag1112.life_in_the_village.Npc.Tasks.LevelKind;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskBoard;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskSavedData;
import tterrag1112.life_in_the_village.Npc.Tasks.Business.BusinessProductionTaskSource;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

import java.util.*;

public class BusinessManagementScreen extends Screen {

    private static final int BOOK_W=420, BOOK_H=300, SIDEBAR_W=130, PAGE_PAD=14, ROW_H=24;
    private static final int PW = BOOK_W - SIDEBAR_W - PAGE_PAD * 2; // 262

    public enum Section { OVERVIEW, WORKERS, PRICES, SCHEDULE, PRODUCTION }

    private final OpenBusinessManagementPacket data;
    private int bookX, bookY;
    public Section currentSection = Section.OVERVIEW;

    private final TooltipLayer tooltips = new TooltipLayer();
    private Sidebar<Section> sidebar;
    private ScrollList<OpenBusinessManagementPacket.WorkerEntry>     workerList;
    private ScrollList<OpenBusinessManagementPacket.PriceEntry>      priceList;
    private ScrollList<OpenBusinessManagementPacket.ProductionEntry> productionList;
    private StyledEditBox depositBox, renameBox;

    public BusinessManagementScreen(OpenBusinessManagementPacket data) {
        super(Component.literal(data.businessName()));
        this.data = data;
    }

    // =========================================================================
    // Server-side snapshot builder
    // =========================================================================

    public static void sendOpenPacket(ServerPlayer player, UUID businessId,
                                      ServerLevel level, BusinessSavedData cdata,
                                      VillageSavedData vdata) {
        sendOpenPacket(player, businessId, level, cdata, vdata, "");
    }

    public static void sendOpenPacket(ServerPlayer player, UUID businessId,
                                      ServerLevel level, BusinessSavedData cdata,
                                      VillageSavedData vdata, String restoreSection) {
        Business business = cdata.getById(businessId).orElse(null);
        if (business == null) return;

        List<OpenBusinessManagementPacket.WorkerEntry> workers = new ArrayList<>();
        for (Business.BusinessWorker w : business.getWorkers()) {
            String name = TownspersonMob.findByUUID(level, w.npcId())
                    .map(n -> n.getNpcName()).orElse("Unknown NPC");
            workers.add(new OpenBusinessManagementPacket.WorkerEntry(
                    w.npcId(), name, w.role().name(), w.wagePerDay(),
                    w.assignedItemId(), w.dailyTargetCount()));
        }

        List<OpenBusinessManagementPacket.PriceEntry> prices = new ArrayList<>();
        for (Business.PriceOverride p : business.getAllPriceOverrides()) {
            var item = BuiltInRegistries.ITEM
                    .get(Identifier.parse(p.itemId())).map(h -> h.value()).orElse(null);
            String display = item != null
                    ? item.getDefaultInstance().getHoverName().getString() : p.itemId();
            prices.add(new OpenBusinessManagementPacket.PriceEntry(
                    p.itemId(), display, p.pricePerUnit()));
        }

        List<OpenBusinessManagementPacket.BuildingEntry> buildings = new ArrayList<>();
        for (UUID bid : business.getBuildingIds())
            vdata.getBuildingById(bid).ifPresent(b -> buildings.add(
                    new OpenBusinessManagementPacket.BuildingEntry(
                            bid, b.getName(), b.getType().name())));

        var wallet = new net.minecraft.world.SimpleContainer(
                player.getInventory().getContainerSize());
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            wallet.setItem(i, player.getInventory().getItem(i).copy());
        long wealth = tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper
                .getWealth(wallet).toBronze();

        // PB3 — build production snapshot
        List<OpenBusinessManagementPacket.ProductionEntry> prodEntries =
                buildProductionEntries(business, level, vdata);

        PacketDistributor.sendToPlayer(player, new OpenBusinessManagementPacket(
                businessId, business.getName(), business.getTreasuryBronze(), wealth,
                business.getWorkSchedule().startHour(), business.getWorkSchedule().endHour(),
                business.getEffectiveMinWage(vdata), workers, prices, buildings,
                restoreSection, prodEntries));
    }

    /**
     * PB3 — server-side production snapshot builder. One entry per PRODUCER worker
     * that has a non-empty assignedItemId. Reads TaskSavedData read-only for board
     * status; reads BuildingStorageAccess for current stock and input checks.
     */
    private static List<OpenBusinessManagementPacket.ProductionEntry> buildProductionEntries(
            Business business, ServerLevel level, VillageSavedData vdata) {

        List<OpenBusinessManagementPacket.ProductionEntry> entries = new ArrayList<>();
        IssuerRef issuer = new IssuerRef(LevelKind.BUSINESS, business.getBusinessId());
        TaskSavedData taskData = TaskSavedData.get(level);
        TaskBoard board = taskData.boardIfPresent(issuer).orElse(null);

        for (Business.BusinessWorker w : business.getWorkers()) {
            if (w.role() != Business.WorkerRole.PRODUCER) continue;
            if (w.assignedItemId().isEmpty()) continue;

            String itemId = w.assignedItemId();
            net.minecraft.world.item.Item goalItem =
                    BusinessProductionTaskSource.resolveItem(itemId);
            String itemName = goalItem != null
                    ? goalItem.getDefaultInstance().getHoverName().getString()
                    : itemId;

            String npcName = TownspersonMob.findByUUID(level, w.npcId())
                    .map(n -> n.getNpcName()).orElse("Worker");

            int dailyTarget = Math.max(1, w.dailyTargetCount());

            // ── Diagnostic derivation ─────────────────────────────────────
            ProductionDiagnostic diagnostic;
            String boardStatus = "NONE";
            String requiredSkill = "";
            int currentStock = 0;

            if (goalItem == null) {
                diagnostic = ProductionDiagnostic.BLOCKED_NO_RECIPE;
            } else {
                ProductionRecipe recipe = BusinessProductionTaskSource.recipeFor(goalItem);
                if (recipe == null) {
                    diagnostic = ProductionDiagnostic.BLOCKED_NO_RECIPE;
                } else {
                    // Build requiredSkill display string
                    if (!recipe.skillRequirements().isEmpty()) {
                        var entry = recipe.skillRequirements().entrySet().iterator().next();
                        requiredSkill = entry.getKey().name() + " ≥" + entry.getValue();
                    }

                    // Count stock across all business buildings
                    for (UUID bid : business.getBuildingIds()) {
                        var building = vdata.getBuildingById(bid).orElse(null);
                        if (building != null)
                            currentStock += BuildingStorageAccess.countItem(
                                    level, building, goalItem);
                    }

                    // Board task status
                    if (board != null) {
                        // Stable task id mirrors BusinessProductionTaskSource
                        var prodKey = "business-produce:"
                                + net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(goalItem).toString();
                        var taskId = tterrag1112.life_in_the_village.Npc.Tasks.Producer
                                .ProductionTaskIds.stable(issuer, prodKey);
                        var taskOpt = board.get(taskId);
                        if (taskOpt.isPresent()) {
                            Assignment.Status s = taskOpt.get().assignment().status();
                            boardStatus = s.name();
                        }
                    }

                    if (currentStock >= dailyTarget) {
                        // Stock already at/above target — source would remove task
                        diagnostic = ProductionDiagnostic.PRODUCING;
                    } else if ("CLAIMED".equals(boardStatus) || "IN_PROGRESS".equals(boardStatus)) {
                        diagnostic = ProductionDiagnostic.PRODUCING;
                    } else if ("OPEN".equals(boardStatus)) {
                        // Check if inputs are present in any assigned building
                        boolean inputsMissing = false;
                        var assignedBuilding = vdata.getBuildingById(w.assignedBuildingId())
                                .orElse(null);
                        if (assignedBuilding != null) {
                            for (var inputEntry : recipe.inputs().entrySet()) {
                                int needed = inputEntry.getValue();
                                int have = BuildingStorageAccess.countItem(
                                        level, assignedBuilding, inputEntry.getKey());
                                if (have < needed) { inputsMissing = true; break; }
                            }
                        }
                        diagnostic = inputsMissing
                                ? ProductionDiagnostic.BLOCKED_MISSING_INPUTS
                                : ProductionDiagnostic.IDLE_OPEN;
                    } else {
                        // No board task yet — source hasn't run or goal was just set
                        diagnostic = ProductionDiagnostic.IDLE_NO_TASK;
                    }
                }
            }

            entries.add(new OpenBusinessManagementPacket.ProductionEntry(
                    w.npcId(), npcName, itemId, itemName,
                    dailyTarget, currentStock,
                    boardStatus, diagnostic.name(), requiredSkill));
        }
        return entries;
    }

    // =========================================================================
    // Screen lifecycle
    // =========================================================================

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
                        new Sidebar.Entry<>(Section.OVERVIEW,    "Overview",                               true),
                        new Sidebar.Entry<>(Section.WORKERS,     "Workers (" + data.workers().size() + ")", true),
                        new Sidebar.Entry<>(Section.PRICES,      "Prices",                                 true),
                        new Sidebar.Entry<>(Section.SCHEDULE,    "Schedule",                               true),
                        new Sidebar.Entry<>(Section.PRODUCTION,  "Production",                             true)
                ),
                () -> currentSection,
                s -> { currentSection = s; buildWidgets(); },
                this::drawSidebarFooter);

        int px = bookX + SIDEBAR_W + PAGE_PAD, py = bookY + 36;
        int maxY = bookY + BOOK_H - 34;
        workerList = new ScrollList<>(px, py + 14, PW, maxY - py - 14, ROW_H + 2,
                data.workers(), this::drawWorkerRow, null);
        priceList  = new ScrollList<>(px, py + 14, PW, maxY - py - 14, 18,
                data.priceOverrides(), this::drawPriceRow, this::onPriceClick);
        productionList = new ScrollList<>(px, py + 14, PW, maxY - py - 14, 38,
                data.productionEntries(), this::drawProductionRow, null);

        buildWidgets();
    }

    @Override public boolean isPauseScreen() { return false; }

    private void buildWidgets() {
        clearWidgets();
        depositBox = null;
        renameBox  = null;
        int px = bookX + SIDEBAR_W + PAGE_PAD, py = bookY + 36;
        if (currentSection == Section.OVERVIEW)  buildOverviewWidgets(px, py);
        if (currentSection == Section.SCHEDULE)  buildScheduleWidgets(px, py);
    }

    private void buildOverviewWidgets(int px, int py) {
        depositBox = new StyledEditBox(font, px, py + 50, PW - 60, 16, Component.literal("Amount"));
        depositBox.setMaxLength(10);
        depositBox.setValue("0");
        addRenderableWidget(depositBox);
        addRenderableWidget(StyledButton.builder(Component.literal("Deposit"), b -> {
            try {
                long amount = Long.parseLong(depositBox.getValue());
                if (amount > 0) sendAction(BusinessActionPacket.ActionType.DEPOSIT_TO_TREASURY, amount);
            } catch (NumberFormatException ignored) {}
        }).pos(px + PW - 56, py + 50).size(54, 16).build());

        renameBox = new StyledEditBox(font, px, py + 100, PW - 60, 16, Component.literal("New name"));
        renameBox.setMaxLength(32);
        renameBox.setValue(data.businessName());
        addRenderableWidget(renameBox);
        addRenderableWidget(StyledButton.builder(Component.literal("Rename"), b -> {
            String n = renameBox.getValue().trim();
            if (!n.isEmpty()) sendAction(BusinessActionPacket.ActionType.RENAME_COMPANY, n, 0, 0);
        }).pos(px + PW - 56, py + 100).size(54, 16).build());

        addRenderableWidget(StyledButton.builder(Component.literal("Dissolve Business"),
                b -> sendAction(BusinessActionPacket.ActionType.DISSOLVE_COMPANY, "", 0, 0))
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

    // =========================================================================
    // Render
    // =========================================================================

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
        g.drawString(font, "⌂ " + data.businessName(), bookX + 6, bookY + 8, BookScreenColors.DARK, false);
        g.fill(bookX + 4, bookY + 20, bookX + SIDEBAR_W - 4, bookY + 21, BookScreenColors.BORDER);
    }

    private void drawSidebarFooter(GuiGraphics g) {
        int tyY = bookY + BOOK_H - 36;
        g.fill(bookX + 4, tyY, bookX + SIDEBAR_W - 4, tyY + 1, BookScreenColors.BORDER);
        g.drawString(font, "Treasury",                            bookX + 6, tyY + 4,  BookScreenColors.MID,  false);
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
            case OVERVIEW    -> drawOverview(g, px, py, maxY);
            case WORKERS     -> drawWorkers(g, px, py, mx, my);
            case PRICES      -> drawPrices(g, px, py, mx, my);
            case SCHEDULE    -> drawSchedule(g, px, py, maxY);
            case PRODUCTION  -> drawProduction(g, px, py, mx, my);
        }
    }

    // ── Overview ─────────────────────────────────────────────────────────────

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
        g.drawString(font, "Rename business:", px, py + 89, BookScreenColors.DARK, false);
        g.fill(px, py + 130, px + PW, py + 131, BookScreenColors.BORDER);
        if (py + 138 < maxY)
            g.drawString(font, "Min. wage: " + CoinRenderer.format(data.effectiveMinWage()) + "/day",
                    px, py + 138, data.effectiveMinWage() > 1 ? BookScreenColors.AMBER : BookScreenColors.MID, false);
    }

    // ── Workers ──────────────────────────────────────────────────────────────

    private void drawWorkers(GuiGraphics g, int px, int py, int mx, int my) {
        int y = py;
        g.fill(px, y, px + PW, y + 12, 0xFFE8E0C8);
        g.drawString(font, "Worker",   px + 2,        y + 2, BookScreenColors.MID, false);
        g.drawString(font, "Role",     px + 100,       y + 2, BookScreenColors.MID, false);
        g.drawString(font, "Wage/day", px + PW - 110,  y + 2, BookScreenColors.MID, false);
        if (data.workers().isEmpty()) {
            g.drawCenteredString(font, "No workers hired.",                                    px + PW / 2, py + 60, BookScreenColors.MID);
            g.drawCenteredString(font, "Interact with an unemployed NPC while holding a coin.", px + PW / 2, py + 74, BookScreenColors.LIGHT);
        } else {
            workerList.render(g, mx, my);
        }
    }

    private void drawWorkerRow(GuiGraphics g, int rx, int ry, int rw, int rh,
                               OpenBusinessManagementPacket.WorkerEntry w, boolean hovered) {
        boolean active = !w.assignedItemId().isEmpty();
        g.fill(rx, ry, rx + rw, ry + ROW_H, active ? BookScreenColors.GREEN_BG : BookScreenColors.PARCHMENT);
        g.renderOutline(rx, ry, rw, ROW_H, BookScreenColors.BORDER);

        String name = w.npcName();
        while (font.width(name) > 90 && name.length() > 3) name = name.substring(0, name.length() - 1);
        if (!name.equals(w.npcName())) name += "…";

        int mid = ry + (ROW_H - 8) / 2;
        g.drawString(font, name, rx + 3, mid, BookScreenColors.DARK, false);
        g.drawString(font, formatRole(w.role()), rx + 100, mid, BookScreenColors.MID, false);

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

    // ── Prices ───────────────────────────────────────────────────────────────

    private void drawPrices(GuiGraphics g, int px, int py, int mx, int my) {
        g.drawString(font, "Custom sell prices", px, py, BookScreenColors.MID, false);
        g.fill(px, py + 10, px + PW, py + 11, BookScreenColors.BORDER);
        if (data.priceOverrides().isEmpty()) {
            g.drawCenteredString(font, "No price overrides set.",             px + PW / 2, py + 28, BookScreenColors.MID);
            g.drawCenteredString(font, "Assign a SELLER worker to add items.", px + PW / 2, py + 42, BookScreenColors.LIGHT);
        } else {
            priceList.render(g, mx, my);
        }
    }

    private void drawPriceRow(GuiGraphics g, int rx, int ry, int rw, int rh,
                              OpenBusinessManagementPacket.PriceEntry p, boolean hovered) {
        g.fill(rx, ry, rx + rw, ry + 14, BookScreenColors.PARCHMENT);
        g.renderOutline(rx, ry, rw, 14, BookScreenColors.BORDER);
        g.drawString(font, p.itemName(),                           rx + 3,       ry + 3, BookScreenColors.DARK, false);
        g.drawString(font, CoinRenderer.format(p.pricePerUnit()),  rx + rw - 80, ry + 3, BookScreenColors.GOLD, false);
        drawMini(g, rx + rw - 36, ry, 16, 14, "−", false);
        drawMini(g, rx + rw - 18, ry, 16, 14, "+",      false);
    }

    private boolean onPriceClick(OpenBusinessManagementPacket.PriceEntry p, int btn, double relX, double relY) {
        if (relX >= PW - 36 && relX < PW - 20) { sendPriceChange(p.itemId(), Math.max(1, p.pricePerUnit() - 1)); return true; }
        if (relX >= PW - 18 && relX < PW - 2)  { sendPriceChange(p.itemId(), p.pricePerUnit() + 1);              return true; }
        return false;
    }

    // ── Schedule ─────────────────────────────────────────────────────────────

    private void drawSchedule(GuiGraphics g, int px, int py, int maxY) {
        int y = py;
        g.drawString(font, "Work Hours", px, y, BookScreenColors.MID, false);
        g.fill(px, y + 10, px + PW, y + 11, BookScreenColors.BORDER); y += 20;
        g.drawString(font, "Start: " + formatHour(data.workStartHour()), px, y + 8, BookScreenColors.DARK, false);
        y += 20;
        g.drawString(font, "End:   " + formatHour(data.workEndHour()), px, y + 8 + 20, BookScreenColors.DARK, false);
        y += 60;

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

    // ── Production ───────────────────────────────────────────────────────────

    private void drawProduction(GuiGraphics g, int px, int py, int mx, int my) {
        // Header
        g.drawString(font, "Active production goals", px, py, BookScreenColors.MID, false);
        g.fill(px, py + 10, px + PW, py + 11, BookScreenColors.BORDER);

        if (data.productionEntries().isEmpty()) {
            g.drawCenteredString(font, "No production goals set.",                    px + PW / 2, py + 32, BookScreenColors.MID);
            g.drawCenteredString(font, "Open a PRODUCER worker and assign an item.", px + PW / 2, py + 46, BookScreenColors.LIGHT);
            return;
        }

        // Column headers
        int hy = py + 14;
        g.fill(px, hy, px + PW, hy + 10, 0xFFE8E0C8);
        g.drawString(font, "Worker / Item",  px + 2,        hy + 1, BookScreenColors.MID, false);
        g.drawString(font, "Stock/Target",   px + PW - 130, hy + 1, BookScreenColors.MID, false);
        g.drawString(font, "Status",         px + PW - 60,  hy + 1, BookScreenColors.MID, false);

        productionList.render(g, mx, my);
    }

    /** Row height = 38px: 2 text lines + progress bar + status pill. */
    private void drawProductionRow(GuiGraphics g, int rx, int ry, int rw, int rh,
                                   OpenBusinessManagementPacket.ProductionEntry e, boolean hovered) {
        ProductionDiagnostic diag = parseDiagnostic(e.diagnostic());

        // Row background by status
        int rowBg = switch (diag) {
            case PRODUCING            -> BookScreenColors.GREEN_BG;
            case IDLE_NO_TASK, IDLE_OPEN -> BookScreenColors.PARCHMENT;
            case BLOCKED_NO_RECIPE,
                 BLOCKED_MISSING_INPUTS -> BookScreenColors.RED_BG;
        };
        g.fill(rx, ry, rx + rw, ry + rh, rowBg);
        g.renderOutline(rx, ry, rw, rh, BookScreenColors.BORDER);

        // Item icon
        var stack = resolveStack(e.itemId(), 1);
        if (stack != null) {
            g.renderItem(stack, rx + 2, ry + 1);
        }

        int textX = rx + 20;

        // Line 1: worker name
        String workerLabel = e.npcName();
        if (font.width(workerLabel) > 110) workerLabel = workerLabel.substring(0, 12) + "…";
        g.drawString(font, workerLabel, textX, ry + 2, BookScreenColors.DARK, false);

        // Line 2: item name + required skill
        String itemLabel = formatItemId(e.itemId());
        if (font.width(itemLabel) > 90) itemLabel = itemLabel.substring(0, 10) + "…";
        g.drawString(font, itemLabel, textX, ry + 12, BookScreenColors.MID, false);
        if (!e.requiredSkill().isEmpty()) {
            String skillLabel = "[" + e.requiredSkill() + "]";
            g.drawString(font, skillLabel, textX + 100, ry + 12, BookScreenColors.AMBER, false);
        }

        // Progress bar: currentStock / dailyTarget (clamped to [0,1])
        int barX = rx + 2, barY = ry + 24, barW = rw - 130, barH = 6;
        float fill = e.dailyTarget() > 0
                ? Math.min(1f, (float) e.currentStock() / e.dailyTarget()) : 0f;
        int fillColor = switch (diag) {
            case PRODUCING            -> BookScreenColors.GREEN_TXT;
            case IDLE_NO_TASK, IDLE_OPEN -> BookScreenColors.MID;
            case BLOCKED_NO_RECIPE,
                 BLOCKED_MISSING_INPUTS -> BookScreenColors.RED_TXT;
        };
        ProgressBar.draw(g, barX, barY, barW, barH, fill,
                0xFFD8D0B8, fillColor, BookScreenColors.BORDER);
        g.drawString(font, e.currentStock() + "/" + e.dailyTarget(),
                barX + barW + 3, barY - 1, BookScreenColors.MID, false);

        // Status pill
        String pillLabel;
        int pillBg, pillTxt;
        switch (diag) {
            case PRODUCING -> {
                pillLabel = "Producing";
                pillBg = BookScreenColors.GREEN_BG;
                pillTxt = BookScreenColors.GREEN_TXT;
            }
            case IDLE_NO_TASK -> {
                pillLabel = "Idle";
                pillBg = 0xFFDDD8C0;
                pillTxt = BookScreenColors.MID;
            }
            case IDLE_OPEN -> {
                pillLabel = "Queued";
                pillBg = BookScreenColors.BLUE_BG;
                pillTxt = 0xFF2A5A8A;
            }
            case BLOCKED_NO_RECIPE -> {
                pillLabel = "No Recipe";
                pillBg = BookScreenColors.RED_BG;
                pillTxt = BookScreenColors.RED_TXT;
            }
            case BLOCKED_MISSING_INPUTS -> {
                pillLabel = "No Inputs";
                pillBg = 0xFFFFDDC0;
                pillTxt = 0xFF8B4A00;
            }
            default -> {
                pillLabel = diag.name();
                pillBg = BookScreenColors.PARCHMENT;
                pillTxt = BookScreenColors.MID;
            }
        }
        int pillW = Pill.width(font, pillLabel);
        Pill.draw(g, font, rx + rw - pillW - 2, ry + 24, pillLabel, pillBg, pillTxt);

        // Tooltip: full diagnostic reason on hover
        if (hovered) {
            String reason = diagnosticTooltip(diag, e);
            tooltips.queue(rx + 2, ry + rh,
                    List.of(Component.literal(e.npcName() + " → " + formatItemId(e.itemId())),
                            Component.literal(reason)));
        }
    }

    // =========================================================================
    // Input
    // =========================================================================

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
                    if (rx >= PW - 110 && rx < PW - 96) { sendActionLong(BusinessActionPacket.ActionType.SET_WORKER_WAGE, w.npcId(), Math.max(data.effectiveMinWage(), w.wagePerDay() - 1)); return true; }
                    if (rx >= PW -  94 && rx < PW - 80) { sendActionLong(BusinessActionPacket.ActionType.SET_WORKER_WAGE, w.npcId(), w.wagePerDay() + 1); return true; }
                    if (rx >= PW -  78 && rx < PW - 46) { ClientPacketDistributor.sendToServer(new BusinessActionPacket(BusinessActionPacket.ActionType.OPEN_WORKER_SCREEN, data.businessId(), w.npcId(), "", 0L, 0)); return true; }
                    if (rx >= PW -  44 && rx < PW - 14) { sendAction(BusinessActionPacket.ActionType.FIRE_NPC, w.npcId()); return true; }
                }
            }
        }
        if (currentSection == Section.PRICES && priceList.mouseClicked(mx, my, event.button())) return true;
        // Production section: rows are display-only (set goals via Worker screen Manage button)
        return super.mouseClicked(event, consumed);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (currentSection == Section.WORKERS    && workerList.mouseScrolled(mx, my, dy))     return true;
        if (currentSection == Section.PRICES     && priceList.mouseScrolled(mx, my, dy))      return true;
        if (currentSection == Section.PRODUCTION && productionList.mouseScrolled(mx, my, dy)) return true;
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (sidebar.keyPressed(event.key())) return true;
        return super.keyPressed(event);
    }

    // =========================================================================
    // Packet helpers
    // =========================================================================

    private void sendAction(BusinessActionPacket.ActionType type, long longParam) {
        ClientPacketDistributor.sendToServer(new BusinessActionPacket(type, data.businessId(), new UUID(0,0), "", longParam, 0));
    }
    private void sendAction(BusinessActionPacket.ActionType type, String str, long lp, int ip) {
        ClientPacketDistributor.sendToServer(new BusinessActionPacket(type, data.businessId(), new UUID(0,0), str, lp, ip));
    }
    private void sendAction(BusinessActionPacket.ActionType type, UUID targetId) {
        ClientPacketDistributor.sendToServer(new BusinessActionPacket(type, data.businessId(), targetId, "", 0, 0));
    }
    private void sendActionLong(BusinessActionPacket.ActionType type, UUID targetId, long lp) {
        ClientPacketDistributor.sendToServer(new BusinessActionPacket(type, data.businessId(), targetId, "", lp, 0));
    }
    private void sendPriceChange(String itemId, long price) {
        ClientPacketDistributor.sendToServer(new BusinessActionPacket(BusinessActionPacket.ActionType.SET_ITEM_PRICE, data.businessId(), new UUID(0,0), itemId, price, 0));
    }
    private void sendScheduleChange(int startH, int endH) {
        ClientPacketDistributor.sendToServer(new BusinessActionPacket(BusinessActionPacket.ActionType.SET_WORK_HOURS, data.businessId(), new UUID(0,0), "", endH, startH));
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private void drawMini(GuiGraphics g, int bx, int by, int bw, int bh, String label, boolean hov) {
        g.fill(bx, by, bx + bw, by + bh, hov ? BookScreenColors.HIGHLIGHT : BookScreenColors.PARCHMENT);
        g.renderOutline(bx, by, bw, bh, BookScreenColors.BORDER);
        int tw = font.width(label);
        g.drawString(font, label, bx + (bw - tw) / 2, by + (bh - 8) / 2, BookScreenColors.DARK, false);
    }

    private static net.minecraft.world.item.ItemStack resolveStack(String itemId, int count) {
        try {
            return BuiltInRegistries.ITEM
                    .get(Identifier.parse(itemId))
                    .map(h -> new net.minecraft.world.item.ItemStack(h.value(), count))
                    .orElse(null);
        } catch (Exception e) { return null; }
    }

    private static ProductionDiagnostic parseDiagnostic(String name) {
        try { return ProductionDiagnostic.valueOf(name); }
        catch (Exception e) { return ProductionDiagnostic.IDLE_NO_TASK; }
    }

    private static String diagnosticTooltip(ProductionDiagnostic diag,
                                            OpenBusinessManagementPacket.ProductionEntry e) {
        return switch (diag) {
            case PRODUCING            -> "Worker is actively crafting or stock is at target.";
            case IDLE_NO_TASK         -> "Goal is set but the task board hasn't issued a task yet. Wait a moment.";
            case IDLE_OPEN            -> "Task queued on board but no worker has claimed it yet.";
            case BLOCKED_NO_RECIPE    -> "Item \"" + formatItemId(e.itemId()) + "\" has no known recipe. Set a different goal.";
            case BLOCKED_MISSING_INPUTS -> "Recipe ingredients missing in the assigned building's storage.";
        };
    }

    private String sectionLabel(Section s) {
        return switch (s) {
            case OVERVIEW    -> "Overview";
            case WORKERS     -> "Workers (" + data.workers().size() + ")";
            case PRICES      -> "Prices";
            case SCHEDULE    -> "Schedule";
            case PRODUCTION  -> "Production (" + data.productionEntries().size() + " goals)";
        };
    }

    private String formatHour(int h)    { return String.format("%02d:00", h); }
    private String formatRole(String r) { return (r == null || r.isEmpty()) ? "—" : r.charAt(0) + r.substring(1).toLowerCase(); }
    private static String formatItemId(String id) {
        if (id == null || id.isEmpty()) return "—";
        String path = id.contains(":") ? id.split(":")[1] : id;
        return Character.toUpperCase(path.charAt(0)) + path.substring(1).replace('_', ' ');
    }
}
