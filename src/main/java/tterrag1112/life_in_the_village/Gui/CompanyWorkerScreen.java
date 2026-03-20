package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Guilds.Companies.Company;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Networking.CompanyActionPacket;
import tterrag1112.life_in_the_village.Networking.OpenCompanyWorkerPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

import java.util.*;

public class CompanyWorkerScreen extends Screen {

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------
    private static final int W            = 300;
    private static final int H            = 280;
    private static final int HEADER_H     = 20;
    private static final int TAB_H        = 18;
    private static final int CONTENT_Y    = HEADER_H + TAB_H + 6;
    private static final int ITEM_ROW_H   = 20;
    private static final int VISIBLE_ROWS = 5;
    private static final int LIST_H       = ITEM_ROW_H * VISIBLE_ROWS;
    private static final int FOOTER_H     = 54; // wage row + confirm button

    // -------------------------------------------------------------------------
    // Colors
    // -------------------------------------------------------------------------
    private static final int COL_PARCHMENT = 0xFFF5F0E0;
    private static final int COL_SIDEBAR   = 0xFFEDE8D5;
    private static final int COL_BORDER    = 0xFFB8A878;
    private static final int COL_DARK      = 0xFF3B2E1A;
    private static final int COL_MID       = 0xFF7A6040;
    private static final int COL_LIGHT     = 0xFFA89060;
    private static final int COL_HIGHLIGHT = 0xFFD4C48A;
    private static final int COL_GREEN_BG  = 0xFFD4EAC8;
    private static final int COL_GREEN_TXT = 0xFF2D6B1A;
    private static final int COL_RED_TXT   = 0xFF8B1A1A;
    private static final int COL_GOLD      = 0xFFB8860B;
    private static final int COL_AMBER     = 0xFFE8A020;
    private static final int COL_SELECTED  = 0xFFB8D4A8;
    private static final int COL_ACTIVE    = 0xFFD4B870;
    private static final int COL_DISABLED  = 0xFFCCBB99;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    private final OpenCompanyWorkerPacket data;
    private int panelX, panelY;

    private Company.WorkerRole  activeRole;
    private Company.ProducerType activeProducerType;

    private int  selectedItemIndex = -1;
    private int  itemScroll        = 0;
    private int  targetCount;
    private long editWage;
    private long customSellPrice   = 0L;

    // Seller price input
    private EditBox priceBox;

    public CompanyWorkerScreen(OpenCompanyWorkerPacket data) {
        super(Component.literal(data.npcName()));
        this.data = data;
        this.activeRole         = parseRole(data.role());
        this.activeProducerType = parseProducerType(data.producerType());
        this.targetCount        = Math.max(1, data.currentTargetCount());
        this.editWage           = data.wage();
        // Pre-select current item
        for (int i = 0; i < data.availableItems().size(); i++) {
            if (data.availableItems().get(i).itemId()
                    .equals(data.currentItemId())) {
                selectedItemIndex = i;
                break;
            }
        }
    }

    // =========================================================================
    // SERVER-SIDE — kept here for locality, same as before
    // =========================================================================

    public static void open(
            net.minecraft.server.level.ServerPlayer player,
            tterrag1112.life_in_the_village.Entities.custom.TownspersonMob npc,
            tterrag1112.life_in_the_village.Guilds.Companies.Company company) {
        var level = (net.minecraft.server.level.ServerLevel) npc.level();
        sendOpenPacket(player, npc.getUUID(), company.getCompanyId(),
                level, CompanySavedData.get(level), VillageSavedData.get(level));
    }

    public static void sendOpenPacket(
            net.minecraft.server.level.ServerPlayer player,
            UUID npcId, UUID companyId,
            net.minecraft.server.level.ServerLevel level,
            CompanySavedData cdata, VillageSavedData vdata) {

        tterrag1112.life_in_the_village.Guilds.Companies.Company company =
                cdata.getById(companyId).orElse(null);
        if (company == null) return;

        tterrag1112.life_in_the_village.Guilds.Companies.Company.CompanyWorker worker =
                company.getWorker(npcId).orElse(null);

        String npcName = level.getEntitiesOfClass(
                tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                new net.minecraft.world.phys.AABB(
                        -30000000, -2048, -30000000, 30000000, 2048, 30000000),
                mob -> mob.getUUID().equals(npcId)
        ).stream().findFirst().map(npc -> npc.getNpcName()).orElse("Worker");

        // Aggregate items across all company buildings (deduplicated by type)
        Map<String, OpenCompanyWorkerPacket.AvailableItem> itemMap = new LinkedHashMap<>();
        UUID NULL_BUILDING = UUID.fromString("00000000-0000-0000-0000-000000000000");

        // If worker has a valid building, scan it first
        if (worker != null && !worker.assignedBuildingId().equals(NULL_BUILDING)) {
            vdata.getBuildingById(worker.assignedBuildingId()).ifPresent(building -> {
                scanBuildingIntoMap(level, building, itemMap, company, vdata);
            });
        }
        // Also scan all other company buildings
        for (UUID bid : company.getBuildingIds()) {
            if (worker != null && bid.equals(worker.assignedBuildingId())) continue;
            vdata.getBuildingById(bid).ifPresent(building ->
                    scanBuildingIntoMap(level, building, itemMap, company, vdata));
        }

        // Company buildings list for producer type availability
        List<OpenCompanyWorkerPacket.CompanyBuildingEntry> companyBuildings = new ArrayList<>();
        for (UUID bid : company.getBuildingIds()) {
            vdata.getBuildingById(bid).ifPresent(b ->
                    companyBuildings.add(new OpenCompanyWorkerPacket.CompanyBuildingEntry(
                            bid, b.getName(), b.getType().name())));
        }

        // Market price for current item
        long currentMarketPrice = 0L;
        if (worker != null && !worker.assignedItemId().isEmpty()) {
            var assignedItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.Identifier.parse(worker.assignedItemId()))
                    .map(h -> h.value()).orElse(null);
            if (assignedItem != null)
                currentMarketPrice = tterrag1112.life_in_the_village.Village.Economy
                        .VillageEconomy.getDynamicPrice(
                                level, company.getHomeVillageId(), assignedItem);
        }

        long minWage      = company.getEffectiveMinWage(vdata);
        long wage         = worker != null ? worker.wagePerDay() : minWage;
        String currentId  = worker != null ? worker.assignedItemId()   : "";
        int    currentQty = worker != null ? worker.dailyTargetCount()  : 8;
        String role       = worker != null ? worker.role().name()
                : tterrag1112.life_in_the_village.Guilds.Companies.Company
                .WorkerRole.PRODUCER.name();
        String prodType   = worker != null ? worker.producerType().name()
                : tterrag1112.life_in_the_village.Guilds.Companies.Company
                .ProducerType.GENERIC.name();

        PacketDistributor.sendToPlayer(player,
                new OpenCompanyWorkerPacket(
                        npcId, companyId, npcName,
                        wage, minWage, currentId, currentQty,
                        role, prodType,
                        new ArrayList<>(itemMap.values()),
                        companyBuildings,
                        currentMarketPrice));
    }

    private static void scanBuildingIntoMap(
            net.minecraft.server.level.ServerLevel level,
            tterrag1112.life_in_the_village.Village.Building building,
            Map<String, OpenCompanyWorkerPacket.AvailableItem> itemMap,
            tterrag1112.life_in_the_village.Guilds.Companies.Company company,
            VillageSavedData vdata) {
        for (var container : tterrag1112.life_in_the_village.Village
                .BuildingStorageAccess.findInventories(level, building)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                var stack = container.getItem(i);
                if (stack.isEmpty()) continue;
                String key = net.minecraft.core.registries.BuiltInRegistries
                        .ITEM.getKey(stack.getItem()).toString();
                var item = stack.getItem();
                long marketPrice = tterrag1112.life_in_the_village.Village.Economy
                        .VillageEconomy.getDynamicPrice(
                                level, company.getHomeVillageId(), item);
                if (itemMap.containsKey(key)) {
                    var existing = itemMap.get(key);
                    itemMap.put(key, new OpenCompanyWorkerPacket.AvailableItem(
                            key, existing.displayName(),
                            existing.stockCount() + stack.getCount(),
                            marketPrice));
                } else {
                    itemMap.put(key, new OpenCompanyWorkerPacket.AvailableItem(
                            key, stack.getHoverName().getString(),
                            stack.getCount(), marketPrice));
                }
            }
        }
    }

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    @Override
    protected void init() {
        panelX = (width - W) / 2;
        panelY = (height - H) / 2;
        buildWidgets();
    }

    @Override public boolean isPauseScreen() { return false; }

    // =========================================================================
    // WIDGETS
    // =========================================================================

    private void buildWidgets() {
        clearWidgets();
        priceBox = null;

        int py = panelY;

        // --- Role tabs ---
        int tabW = W / Company.WorkerRole.values().length;
        int ti = 0;
        for (Company.WorkerRole role : Company.WorkerRole.values()) {
            final Company.WorkerRole r = role;
            addRenderableWidget(Button.builder(
                            Component.literal(formatRole(role.name())),
                            b -> { activeRole = r; selectedItemIndex = -1;
                                itemScroll = 0; buildWidgets(); })
                    .pos(panelX + ti * tabW, py + HEADER_H)
                    .size(tabW, TAB_H).build());
            ti++;
        }

        // --- Role-specific widgets ---
        int contentTop = py + CONTENT_Y;
        switch (activeRole) {
            case PRODUCER -> buildProducerWidgets(contentTop);
            case SELLER   -> buildSellerWidgets(contentTop);
            case COURIER  -> buildCourierWidgets(contentTop);
        }

        // --- Item list scroll ---
        int listY = contentTop + (activeRole == Company.WorkerRole.PRODUCER ? 42 : 14);
        buildScrollButtons(listY);

        // --- Wage row ---
        int wageY = panelY + H - FOOTER_H + 4;
        addRenderableWidget(Button.builder(Component.literal("−"),
                        b -> { editWage = Math.max(data.minWage(), editWage - 1);
                            sendWageChange(editWage); })
                .pos(panelX + 8, wageY).size(16, 14).build());
        addRenderableWidget(Button.builder(Component.literal("+"),
                        b -> { editWage++; sendWageChange(editWage); })
                .pos(panelX + 28, wageY).size(16, 14).build());
    }

    // --- PRODUCER ---
    private void buildProducerWidgets(int contentTop) {
        // Producer type selector — one button per type
        // Only show types where the company owns the required building
        Set<String> ownedTypes = new HashSet<>();
        ownedTypes.add("GENERIC");
        for (var entry : data.companyBuildings()) {
            for (Company.ProducerType pt : Company.ProducerType.values()) {
                if (pt.requiredBuilding() != null
                        && pt.requiredBuilding().name().equals(entry.buildingType())) {
                    ownedTypes.add(pt.name());
                }
            }
        }

        int ptX = panelX + 8;
        int ptY = contentTop + 2;
        for (Company.ProducerType pt : Company.ProducerType.values()) {
            boolean available = ownedTypes.contains(pt.name());
            final Company.ProducerType t = pt;
            Button btn = Button.builder(
                            Component.literal(formatRole(pt.name())),
                            b -> {
                                if (!available) return;
                                activeProducerType = t;
                                selectedItemIndex = -1;
                                sendProducerTypeChange(t);
                                buildWidgets();
                            })
                    .pos(ptX, ptY).size(48, 12).build();
            addRenderableWidget(btn);
            ptX += 52;
            if (ptX + 48 > panelX + W - 8) { ptX = panelX + 8; ptY += 16; }
        }

        // Daily target controls
        int countY = contentTop + LIST_H + 62;
        addRenderableWidget(Button.builder(Component.literal("−"),
                        b -> { targetCount = Math.max(1, targetCount - 1); buildWidgets(); })
                .pos(panelX + W / 2 - 24, countY).size(16, 12).build());
        addRenderableWidget(Button.builder(Component.literal("+"),
                        b -> { targetCount = Math.min(64, targetCount + 1); buildWidgets(); })
                .pos(panelX + W / 2 + 8, countY).size(16, 12).build());

        // Confirm
        addRenderableWidget(Button.builder(
                        Component.literal("Assign Production Task"),
                        b -> {
                            if (selectedItemIndex >= 0
                                    && selectedItemIndex < data.availableItems().size()) {
                                sendTask(data.availableItems()
                                        .get(selectedItemIndex).itemId(), targetCount);
                            }
                        })
                .pos(panelX + 8, panelY + H - 24).size(W - 16, 16).build());
    }

    // --- SELLER ---
    private void buildSellerWidgets(int contentTop) {
        // Price input
        int priceY = contentTop + LIST_H + 22;
        priceBox = new EditBox(font,
                panelX + 8, priceY, W - 80, 14,
                Component.literal("Price"));
        priceBox.setMaxLength(10);
        priceBox.setValue(customSellPrice > 0
                ? String.valueOf(customSellPrice) : "");
        priceBox.setHint(Component.literal("bronze/unit"));
        addRenderableWidget(priceBox);

        // Quick price buttons — set to market, +10%, -10%
        addRenderableWidget(Button.builder(Component.literal("Market"),
                        b -> {
                            if (selectedItemIndex >= 0) {
                                customSellPrice = data.availableItems()
                                        .get(selectedItemIndex).marketPrice();
                                if (priceBox != null)
                                    priceBox.setValue(String.valueOf(customSellPrice));
                                buildWidgets();
                            }
                        })
                .pos(panelX + W - 68, priceY).size(60, 14).build());

        // Confirm sell button
        addRenderableWidget(Button.builder(
                        Component.literal("Assign Sell Task"),
                        b -> {
                            if (selectedItemIndex >= 0 && customSellPrice > 0) {
                                String itemId = data.availableItems()
                                        .get(selectedItemIndex).itemId();
                                sendSellerTask(itemId, customSellPrice,
                                        data.availableItems()
                                                .get(selectedItemIndex).stockCount());
                            }
                        })
                .pos(panelX + 8, panelY + H - 24).size(W - 16, 16).build());
    }

    // --- COURIER ---
    private void buildCourierWidgets(int contentTop) {
        int countY = contentTop + LIST_H + 22;
        addRenderableWidget(Button.builder(Component.literal("−"),
                        b -> { targetCount = Math.max(1, targetCount - 1); buildWidgets(); })
                .pos(panelX + W / 2 - 24, countY).size(16, 12).build());
        addRenderableWidget(Button.builder(Component.literal("+"),
                        b -> { targetCount = Math.min(64, targetCount + 1); buildWidgets(); })
                .pos(panelX + W / 2 + 8, countY).size(16, 12).build());

        addRenderableWidget(Button.builder(
                        Component.literal("Assign Courier Route"),
                        b -> {
                            if (selectedItemIndex >= 0) {
                                sendTask(data.availableItems()
                                        .get(selectedItemIndex).itemId(), targetCount);
                            }
                        })
                .pos(panelX + 8, panelY + H - 24).size(W - 16, 16).build());
    }

    private void buildScrollButtons(int listY) {
        if (itemScroll > 0)
            addRenderableWidget(Button.builder(Component.literal("▲"),
                            b -> { itemScroll--; buildWidgets(); })
                    .pos(panelX + W - 20, listY).size(14, 10).build());
        if (itemScroll + VISIBLE_ROWS < data.availableItems().size())
            addRenderableWidget(Button.builder(Component.literal("▼"),
                            b -> { itemScroll++; buildWidgets(); })
                    .pos(panelX + W - 20, listY + LIST_H - 12).size(14, 10).build());
    }

    // =========================================================================
    // RENDER
    // =========================================================================

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88000000);
        g.fill(panelX, panelY, panelX + W, panelY + H, COL_PARCHMENT);
        g.renderOutline(panelX, panelY, W, H, COL_BORDER);
        g.renderOutline(panelX + 2, panelY + 2, W - 4, H - 4, COL_HIGHLIGHT);

        // Header
        g.fill(panelX, panelY, panelX + W, panelY + HEADER_H, COL_SIDEBAR);
        g.drawCenteredString(font, data.npcName(),
                panelX + W / 2, panelY + 6, COL_DARK);

        // Tab highlight for active role
        int tabW = W / Company.WorkerRole.values().length;
        int ti = 0;
        for (Company.WorkerRole role : Company.WorkerRole.values()) {
            if (role == activeRole) {
                int tx = panelX + ti * tabW;
                g.fill(tx, panelY + HEADER_H, tx + tabW,
                        panelY + HEADER_H + TAB_H, COL_ACTIVE);
                g.fill(tx, panelY + HEADER_H + TAB_H - 1,
                        tx + tabW, panelY + HEADER_H + TAB_H, COL_BORDER);
            }
            ti++;
        }

        int contentTop = panelY + CONTENT_Y;

        switch (activeRole) {
            case PRODUCER -> drawProducerContent(g, contentTop, mx, my);
            case SELLER   -> drawSellerContent(g, contentTop, mx, my);
            case COURIER  -> drawCourierContent(g, contentTop, mx, my);
        }

        // Divider before footer
        g.fill(panelX + 4, panelY + H - FOOTER_H,
                panelX + W - 4, panelY + H - FOOTER_H + 1, COL_BORDER);

        drawWageRow(g);

        super.render(g, mx, my, pt);
    }

    // -------------------------------------------------------------------------
    // PRODUCER render
    // -------------------------------------------------------------------------
    private void drawProducerContent(GuiGraphics g, int contentTop,
                                     int mx, int my) {
        // Producer type buttons — color available vs locked
        Set<String> ownedTypes = getOwnedProducerTypes();
        int ptX = panelX + 8;
        int ptY = contentTop + 2;
        for (Company.ProducerType pt : Company.ProducerType.values()) {
            boolean available  = ownedTypes.contains(pt.name());
            boolean isActive   = pt == activeProducerType;
            int bgColor = isActive   ? COL_ACTIVE
                    : available  ? COL_HIGHLIGHT
                    : COL_DISABLED;
            g.fill(ptX - 1, ptY - 1, ptX + 49, ptY + 13, bgColor);
            ptX += 52;
            if (ptX + 48 > panelX + W - 8) { ptX = panelX + 8; ptY += 16; }
        }

        // Required building hint
        if (activeProducerType.requiredBuilding() != null) {
            String hint = "Uses: " + activeProducerType.requiredBuilding()
                    .name().toLowerCase().replace('_', ' ');
            g.drawString(font, hint, panelX + 8, contentTop + 36, COL_MID, false);
        }

        // Item list
        int listY = contentTop + 42;
        drawItemList(g, listY, mx, my, "Select item to produce:");

        // Count row
        int countY = contentTop + 42 + LIST_H + 8;
        g.drawString(font, "Daily target:",
                panelX + 8, countY + 2, COL_MID, false);
        g.drawCenteredString(font, String.valueOf(targetCount),
                panelX + W / 2, countY + 2, COL_DARK);

        // Current assignment
        if (!data.currentItemId().isEmpty()) {
            g.drawString(font,
                    "Now: " + formatItemId(data.currentItemId())
                            + " ×" + data.currentTargetCount() + "/day",
                    panelX + 8, countY + 18, COL_GREEN_TXT, false);
        }
    }

    // -------------------------------------------------------------------------
    // SELLER render
    // -------------------------------------------------------------------------
    private void drawSellerContent(GuiGraphics g, int contentTop,
                                   int mx, int my) {
        int listY = contentTop + 14;
        drawItemList(g, listY, mx, my, "Choose item to sell:");

        int priceAreaY = contentTop + 14 + LIST_H + 8;

        // Price label
        g.drawString(font, "Your price (bronze/unit):",
                panelX + 8, priceAreaY, COL_MID, false);

        // Market price comparison for selected item
        if (selectedItemIndex >= 0
                && selectedItemIndex < data.availableItems().size()) {
            long marketP = data.availableItems().get(selectedItemIndex).marketPrice();
            if (marketP > 0) {
                g.drawString(font, "Market: ",
                        panelX + 8, priceAreaY + 20, COL_MID, false);
                CoinRenderer.renderCoinRow(g, marketP, panelX + 52, priceAreaY + 18);

                // Show comparison arrow
                if (customSellPrice > 0) {
                    String cmp = customSellPrice > marketP ? "▲ Above market"
                            : customSellPrice < marketP ? "▼ Below market"
                            : "= At market";
                    int cmpColor = customSellPrice > marketP ? COL_AMBER
                            : customSellPrice < marketP ? COL_GREEN_TXT
                            : COL_MID;
                    int cmpX = panelX + 52 + CoinRenderer.coinRowWidth(marketP) + 4;
                    g.drawString(font, cmp, cmpX, priceAreaY + 20, cmpColor, false);
                }
            }
        }

        // Current assignment
        if (!data.currentItemId().isEmpty()) {
            String curr = "Now selling: " + formatItemId(data.currentItemId());
            g.drawString(font, curr, panelX + 8, priceAreaY + 36, COL_GREEN_TXT, false);
            if (data.currentMarketPrice() > 0) {
                g.drawString(font, "at market ",
                        panelX + 8, priceAreaY + 46, COL_MID, false);
                CoinRenderer.renderCoinRow(g, data.currentMarketPrice(),
                        panelX + 58, priceAreaY + 44);
            }
        }
    }

    // -------------------------------------------------------------------------
    // COURIER render
    // -------------------------------------------------------------------------
    private void drawCourierContent(GuiGraphics g, int contentTop,
                                    int mx, int my) {
        int listY = contentTop + 14;
        drawItemList(g, listY, mx, my, "Choose item to courier:");

        int countY = contentTop + 14 + LIST_H + 8;
        g.drawString(font, "Batch size per trip:",
                panelX + 8, countY + 2, COL_MID, false);
        g.drawCenteredString(font, String.valueOf(targetCount),
                panelX + W / 2, countY + 2, COL_DARK);

        if (!data.currentItemId().isEmpty()) {
            g.drawString(font,
                    "Couriering: " + formatItemId(data.currentItemId())
                            + " ×" + data.currentTargetCount(),
                    panelX + 8, countY + 18, COL_GREEN_TXT, false);
        }
    }

    // -------------------------------------------------------------------------
    // SHARED item list
    // -------------------------------------------------------------------------
    private void drawItemList(GuiGraphics g, int listY, int mx, int my,
                              String header) {
        int listX = panelX + 8;
        int listW = W - 36;

        g.drawString(font, header, listX, listY - 10, COL_MID, false);

        g.fill(listX, listY, listX + listW, listY + LIST_H, 0xFFEEE8D0);
        g.renderOutline(listX, listY, listW, LIST_H, COL_BORDER);

        if (data.availableItems().isEmpty()) {
            g.drawCenteredString(font, "No items found in company buildings",
                    listX + listW / 2, listY + LIST_H / 2 - 4, COL_LIGHT);
            return;
        }

        for (int i = itemScroll;
             i < Math.min(itemScroll + VISIBLE_ROWS, data.availableItems().size()); i++) {
            var item   = data.availableItems().get(i);
            int rowY   = listY + (i - itemScroll) * ITEM_ROW_H;
            boolean sel = i == selectedItemIndex;
            boolean hov = mx >= listX && mx < listX + listW
                    && my >= rowY && my < rowY + ITEM_ROW_H;

            int bg = sel ? COL_SELECTED : hov ? COL_HIGHLIGHT
                    : (i % 2 == 0 ? 0xFFEEE8D0 : 0xFFE8E0C8);
            g.fill(listX, rowY, listX + listW, rowY + ITEM_ROW_H, bg);

            // Item icon
            var stack = resolveStack(item.itemId(), 1);
            if (stack != null) {
                g.renderItem(stack, listX + 2, rowY + 2);
                g.renderItemDecorations(font, stack, listX + 2, rowY + 2, null);
            }

            // Name
            String label = item.displayName();
            if (font.width(label) > listW - 90)
                label = label.substring(0, 9) + "…";
            g.drawString(font, label, listX + 22, rowY + 6,
                    sel ? COL_DARK : COL_MID, false);

            // Stock count
            String stock = "×" + item.stockCount();
            g.drawString(font, stock,
                    listX + listW - CoinRenderer.coinRowWidth(item.marketPrice()) - font.width(stock) - 22,
                    rowY + 6, COL_GOLD, false);

            // Market price (coin icons, right side)
            if (item.marketPrice() > 0)
                CoinRenderer.renderCoinRow(g, item.marketPrice(),
                        listX + listW - CoinRenderer.coinRowWidth(item.marketPrice()) - 2,
                        rowY + 2);
        }
    }

    // -------------------------------------------------------------------------
    // WAGE ROW
    // -------------------------------------------------------------------------
    private void drawWageRow(GuiGraphics g) {
        int wageY = panelY + H - FOOTER_H + 4;
        g.drawString(font, "Daily wage:", panelX + 50, wageY + 2, COL_MID, false);
        int afterCoin = CoinRenderer.renderCoinRow(g, editWage, panelX + 115, wageY);
        g.drawString(font, "/day", afterCoin + 2, wageY + 2, COL_MID, false);
        if (editWage < data.minWage())
            g.drawString(font, "below min. wage!",
                    panelX + 8, wageY + 16, COL_RED_TXT, false);
    }

    // =========================================================================
    // MOUSE
    // =========================================================================

    @Override
    public boolean mouseClicked(
            net.minecraft.client.input.MouseButtonEvent event, boolean consumed) {
        if (consumed) return super.mouseClicked(event, consumed);

        int listX = panelX + 8;
        int listW = W - 36;
        int contentTop = panelY + CONTENT_Y;
        int listY = contentTop + (activeRole == Company.WorkerRole.PRODUCER ? 42 : 14);

        if (event.x() >= listX && event.x() < listX + listW
                && event.y() >= listY && event.y() < listY + LIST_H) {
            int clicked = (int)((event.y() - listY) / ITEM_ROW_H) + itemScroll;
            if (clicked >= 0 && clicked < data.availableItems().size()) {
                selectedItemIndex = clicked;
                // Seed price box for SELLER
                if (activeRole == Company.WorkerRole.SELLER && priceBox != null) {
                    long marketP = data.availableItems().get(clicked).marketPrice();
                    if (customSellPrice == 0 && marketP > 0) {
                        customSellPrice = marketP;
                        priceBox.setValue(String.valueOf(customSellPrice));
                    }
                }
            }
        }

        return super.mouseClicked(event, consumed);
    }



    // =========================================================================
    // PACKET HELPERS
    // =========================================================================

    private void sendTask(String itemId, int count) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                CompanyActionPacket.ActionType.SET_WORKER_ROLE,
                data.companyId(), data.npcId(), "", 0L, activeRole.ordinal()));
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                CompanyActionPacket.ActionType.ASSIGN_WORKER_TASK,
                data.companyId(), data.npcId(), itemId, 0L, count));
    }

    private void sendSellerTask(String itemId, long price, int stock) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                CompanyActionPacket.ActionType.SET_ITEM_PRICE,
                data.companyId(), new UUID(0, 0), itemId, price, 0));
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                CompanyActionPacket.ActionType.SET_WORKER_ROLE,
                data.companyId(), data.npcId(), "", 0L, activeRole.ordinal()));
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                CompanyActionPacket.ActionType.ASSIGN_WORKER_TASK,
                data.companyId(), data.npcId(), itemId, 0L, stock));
    }

    private void sendWageChange(long wage) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                CompanyActionPacket.ActionType.SET_WORKER_WAGE,
                data.companyId(), data.npcId(), "", wage, 0));
    }

    private void sendProducerTypeChange(Company.ProducerType type) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                CompanyActionPacket.ActionType.SET_PRODUCER_TYPE,
                data.companyId(), data.npcId(), "", 0L, type.ordinal()));
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private Set<String> getOwnedProducerTypes() {
        Set<String> owned = new HashSet<>();
        owned.add("GENERIC");
        for (var entry : data.companyBuildings()) {
            for (Company.ProducerType pt : Company.ProducerType.values()) {
                if (pt.requiredBuilding() != null
                        && pt.requiredBuilding().name().equals(entry.buildingType())) {
                    owned.add(pt.name());
                }
            }
        }
        return owned;
    }

    private static Company.WorkerRole parseRole(String s) {
        try { return Company.WorkerRole.valueOf(s); }
        catch (Exception e) { return Company.WorkerRole.PRODUCER; }
    }

    private static Company.ProducerType parseProducerType(String s) {
        try { return Company.ProducerType.valueOf(s); }
        catch (Exception e) { return Company.ProducerType.GENERIC; }
    }

    private static net.minecraft.world.item.ItemStack resolveStack(
            String itemId, int count) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.Identifier.parse(itemId))
                    .map(h -> new net.minecraft.world.item.ItemStack(h.value(), count))
                    .orElse(null);
        } catch (Exception e) { return null; }
    }

    private String formatRole(String r) {
        if (r == null || r.isEmpty()) return "—";
        String lower = r.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private String formatItemId(String itemId) {
        if (itemId == null || itemId.isEmpty()) return "—";
        String path = itemId.contains(":") ? itemId.split(":")[1] : itemId;
        return path.replace('_', ' ');
    }
}