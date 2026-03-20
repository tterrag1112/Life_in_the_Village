package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
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
    private static final int COL_SELECTED  = BookScreenColors.COL_SELECTED;
    private static final int COL_ACTIVE    = BookScreenColors.COL_ACTIVE;
    private static final int COL_DISABLED  = BookScreenColors.COL_DISABLED;

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

    // Seller state
    private String  searchText       = "";
    private boolean dropdownOpen     = false;
    private int     dropdownScroll   = 0;
    private int     sellListScroll   = 0;
    private List<OpenCompanyWorkerPacket.AvailableItem> filteredItems = List.of();

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

        String npcName = TownspersonMob.findByUUID(level, npcId).map(npc -> npc.getNpcName()).orElse("Worker");

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
        }// After building the availableItems list:
        List<OpenCompanyWorkerPacket.SellListing> sellListings = new ArrayList<>();
        if (worker != null && worker.role() == Company.WorkerRole.SELLER) {
            // All company price overrides that have stock in company buildings
            for (var override : company.getAllPriceOverrides()) {
                String itemId = override.itemId();
                // Get total stock across all company buildings
                int totalStock = 0;
                for (UUID bid : company.getBuildingIds()) {
                    var b = vdata.getBuildingById(bid).orElse(null);
                    if (b == null) continue;
                    var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .get(net.minecraft.resources.Identifier.parse(itemId))
                            .map(h -> h.value()).orElse(null);
                    if (item != null)
                        totalStock += tterrag1112.life_in_the_village.Village
                                .BuildingStorageAccess.countItem(level, b, item);
                }
                if (totalStock == 0) continue;

                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .get(net.minecraft.resources.Identifier.parse(itemId))
                        .map(h -> h.value()).orElse(null);
                long marketP = item != null
                        ? tterrag1112.life_in_the_village.Village.Economy.VillageEconomy
                        .getDynamicPrice(level, company.getHomeVillageId(), item)
                        : 0L;
                String displayName = item != null
                        ? item.getDefaultInstance().getHoverName().getString()
                        : itemId;

                sellListings.add(new OpenCompanyWorkerPacket.SellListing(
                        itemId, displayName, totalStock,
                        override.pricePerUnit(), marketP));
            }
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
                        currentMarketPrice,
                        sellListings));
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
        int searchY = contentTop + 2;
        int searchW = W - 20;

        // Search bar EditBox
        EditBox search = new EditBox(font,
                panelX + 8, searchY, searchW, 14,
                Component.literal("Search items…"));
        search.setMaxLength(32);
        search.setValue(searchText);
        search.setResponder(text -> {
            searchText = text;
            dropdownOpen = !text.isBlank() || isFocused();
            dropdownScroll = 0;
            rebuildFilteredItems();
            buildWidgets();
        });
        search.setFocused(dropdownOpen);
        addRenderableWidget(search);

        // Dropdown entries (max 5 visible)
        if (dropdownOpen) {
            int dy = searchY + 16;
            int shown = 0;
            for (int i = dropdownScroll;
                 i < filteredItems.size() && shown < 5; i++, shown++) {
                final OpenCompanyWorkerPacket.AvailableItem item = filteredItems.get(i);
                final int fi = i;
                addRenderableWidget(Button.builder(
                                Component.literal(item.displayName()),
                                b -> {
                                    // Add this item to sell listings via SET_ITEM_PRICE
                                    sendSellerAdd(item.itemId(), item.marketPrice());
                                    dropdownOpen = false;
                                    searchText = "";
                                    buildWidgets();
                                })
                        .pos(panelX + 8, dy + shown * 14)
                        .size(searchW, 13).build());
            }
            // Dropdown scroll
            if (dropdownScroll > 0)
                addRenderableWidget(Button.builder(Component.literal("▲"),
                                b -> { dropdownScroll--; buildWidgets(); })
                        .pos(panelX + 8 + searchW - 12, searchY + 16).size(12, 10).build());
            if (dropdownScroll + 5 < filteredItems.size())
                addRenderableWidget(Button.builder(Component.literal("▼"),
                                b -> { dropdownScroll++; buildWidgets(); })
                        .pos(panelX + 8 + searchW - 12, searchY + 86).size(12, 10).build());
        }

        // Sell list — price +/− buttons per row
        int listTop = contentTop + 20 + (dropdownOpen ? 72 : 0);
        int rowH    = 20;
        int shown   = 0;
        var listings = data.currentSellListings();

        for (int i = sellListScroll;
             i < listings.size() && shown < visibleSellRows(listTop); i++, shown++) {
            final OpenCompanyWorkerPacket.SellListing listing = listings.get(i);
            int ry = listTop + shown * rowH;

            // Price −
            addRenderableWidget(Button.builder(Component.literal("◀"),
                            b -> sendPriceAdjust(listing.itemId(),
                                    Math.max(1, listing.effectivePrice() - 1)))
                    .pos(panelX + W - 46, ry + 3).size(12, 14).build());

            // Price +
            addRenderableWidget(Button.builder(Component.literal("▶"),
                            b -> sendPriceAdjust(listing.itemId(), listing.effectivePrice() + 1))
                    .pos(panelX + W - 32, ry + 3).size(12, 14).build());

            // Remove (×)
            addRenderableWidget(Button.builder(Component.literal("×"),
                            b -> {
                                sendPriceRemove(listing.itemId());
                                buildWidgets();
                            })
                    .pos(panelX + W - 18, ry + 3).size(12, 14).build());
        }

        // Sell list scroll
        if (sellListScroll > 0)
            addRenderableWidget(Button.builder(Component.literal("▲"),
                            b -> { sellListScroll--; buildWidgets(); })
                    .pos(panelX + W - 18, listTop).size(12, 10).build());
        if (sellListScroll + visibleSellRows(listTop) < listings.size())
            addRenderableWidget(Button.builder(Component.literal("▼"),
                            b -> { sellListScroll++; buildWidgets(); })
                    .pos(panelX + W - 18, listTop + visibleSellRows(listTop) * 20 - 12)
                    .size(12, 10).build());
    }

    private int visibleSellRows(int listTop) {
        int available = (panelY + H - FOOTER_H - 4) - listTop;
        return Math.max(1, available / 20);
    }

    private void rebuildFilteredItems() {
        String query = searchText.toLowerCase();
        filteredItems = data.availableItems().stream()
                .filter(item -> query.isBlank()
                        || item.displayName().toLowerCase().contains(query)
                        || item.itemId().toLowerCase().contains(query))
                // Exclude items already in the sell list
                .filter(item -> data.currentSellListings().stream()
                        .noneMatch(l -> l.itemId().equals(item.itemId())))
                .toList();
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
    private void drawSellerContent(GuiGraphics g, int contentTop, int mx, int my) {
        int searchY = contentTop + 2;
        int searchW = W - 20;

        // Search bar background
        g.fill(panelX + 7, searchY - 1,
                panelX + 7 + searchW + 2, searchY + 15, COL_BORDER);
        g.fill(panelX + 8, searchY,
                panelX + 8 + searchW, searchY + 14, 0xFFFFFFFF);

        // Dropdown
        if (dropdownOpen && !filteredItems.isEmpty()) {
            int dy    = searchY + 16;
            int shown = 0;
            for (int i = dropdownScroll;
                 i < filteredItems.size() && shown < 5; i++, shown++) {
                var item = filteredItems.get(i);
                int ry = dy + shown * 14;
                boolean hov = mx >= panelX + 8 && mx < panelX + 8 + searchW
                        && my >= ry && my < ry + 14;

                g.fill(panelX + 8, ry, panelX + 8 + searchW, ry + 13,
                        hov ? COL_HIGHLIGHT : 0xFFEEE8D0);
                g.renderOutline(panelX + 8, ry, searchW, 13, COL_BORDER);

                // Item icon
                var stack = resolveStack(item.itemId(), 1);
                if (stack != null) g.renderItem(stack, panelX + 8, ry - 1);

                // Name
                g.drawString(font, item.displayName(),
                        panelX + 26, ry + 2, COL_DARK, false);

                // Qty
                String qty = "×" + item.stockCount();
                int qtyX = panelX + 8 + searchW - CoinRenderer.coinRowWidth(item.marketPrice()) - font.width(qty) - 8;
                g.drawString(font, qty, qtyX, ry + 2, COL_MID, false);

                // Market price
                CoinRenderer.renderCoinRow(g, item.marketPrice(),
                        panelX + 8 + searchW - CoinRenderer.coinRowWidth(item.marketPrice()) - 2,
                        ry - 2);
            }
            // Dropdown border
            g.renderOutline(panelX + 8, searchY + 16,
                    searchW, Math.min(filteredItems.size(), 5) * 14, COL_BORDER);
        }

        // Sell listings
        int listTop = contentTop + 20 + (dropdownOpen ? 72 : 0);
        int rowH    = 20;

        if (data.currentSellListings().isEmpty()) {
            g.drawString(font, "No items assigned to sell.",
                    panelX + 8, listTop + 10, COL_LIGHT, false);
            g.drawString(font, "Use the search bar above to add items.",
                    panelX + 8, listTop + 22, COL_LIGHT, false);
            return;
        }

        // List header
        g.fill(panelX + 8, listTop - 12, panelX + W - 8, listTop - 1, 0xFFE8E0C8);
        g.drawString(font, "Item",    panelX + 26,       listTop - 10, COL_MID, false);
        g.drawString(font, "Stock",   panelX + W - 140,  listTop - 10, COL_MID, false);
        g.drawString(font, "Price",   panelX + W - 100,  listTop - 10, COL_MID, false);

        int shown = 0;
        var listings = data.currentSellListings();

        for (int i = sellListScroll;
             i < listings.size() && shown < visibleSellRows(listTop); i++, shown++) {
            var listing = listings.get(i);
            int ry = listTop + shown * rowH;
            boolean hasOverride = listing.customPrice() > 0
                    && listing.customPrice() != listing.marketPrice();

            g.fill(panelX + 8, ry, panelX + W - 8, ry + rowH - 1,
                    shown % 2 == 0 ? 0xFFEEE8D0 : COL_PARCHMENT);
            g.renderOutline(panelX + 8, ry, W - 16, rowH - 1, COL_BORDER);

            // Icon
            var stack = resolveStack(listing.itemId(), 1);
            if (stack != null) g.renderItem(stack, panelX + 8, ry + 2);

            // Name
            String name = listing.displayName();
            while (font.width(name) > 80 && name.length() > 3)
                name = name.substring(0, name.length() - 1);
            if (!name.equals(listing.displayName())) name += "…";
            g.drawString(font, name, panelX + 26, ry + 6, COL_DARK, false);

            // Stock in parentheses
            g.drawString(font, "(" + listing.stockCount() + ")",
                    panelX + W - 140, ry + 6, COL_MID, false);

            // Price display
            int priceX = panelX + W - 100;
            if (hasOverride) {
                // Draw market price with red diagonal slash through it
                int mpWidth = CoinRenderer.coinRowWidth(listing.marketPrice());
                CoinRenderer.renderCoinRow(g, listing.marketPrice(), priceX, ry + 2);
                // Red strikethrough line — diagonal from top-left to bottom-right of the coin row
                g.fill(priceX, ry + 8, priceX + mpWidth, ry + 9, 0xFFCC0000);

                // Custom price beside it
                CoinRenderer.renderCoinRow(g, listing.customPrice(),
                        priceX + mpWidth + 4, ry + 2);
            } else {
                CoinRenderer.renderCoinRow(g, listing.marketPrice(), priceX, ry + 2);
            }

            // ◀ ▶ × buttons drawn by buildSellerWidgets
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

        if (activeRole == Company.WorkerRole.SELLER) {
            int searchY = panelY + CONTENT_Y + 2;
            int searchW = W - 20;
            if (event.x() >= panelX + 8 && event.x() < panelX + 8 + searchW
                    && event.y() >= searchY && event.y() < searchY + 14) {
                dropdownOpen = true;
                rebuildFilteredItems();
                buildWidgets();
                return true;
            }
            // Click outside dropdown closes it
            if (dropdownOpen) {
                dropdownOpen = false;
                buildWidgets();
                return true;
            }
        }

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
    private void sendSellerAdd(String itemId, long marketPrice) {
        // Set price to market price as default
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                CompanyActionPacket.ActionType.SET_ITEM_PRICE,
                data.companyId(), new UUID(0, 0), itemId, marketPrice, 0));
        // Assign SELLER role if not already set
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                CompanyActionPacket.ActionType.SET_WORKER_ROLE,
                data.companyId(), data.npcId(), "", 0L,
                Company.WorkerRole.SELLER.ordinal()));
    }

    private void sendPriceAdjust(String itemId, long newPrice) {
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                CompanyActionPacket.ActionType.SET_ITEM_PRICE,
                data.companyId(), new UUID(0, 0), itemId, newPrice, 0));
    }

    private void sendPriceRemove(String itemId) {
        // Price of 0 signals removal on server
        ClientPacketDistributor.sendToServer(new CompanyActionPacket(
                CompanyActionPacket.ActionType.REMOVE_ITEM_PRICE,
                data.companyId(), new UUID(0, 0), itemId, 0L, 0));
    }
}