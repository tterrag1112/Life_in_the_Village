package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Gui.Framework.*;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Networking.BusinessActionPacket;
import tterrag1112.life_in_the_village.Networking.OpenBusinessWorkerPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.*;

public class BusinessWorkerScreen extends Screen {

    private static final int W = 300, H = 280;
    private static final int HEADER_H = 20, TAB_H = 18;
    private static final int CONTENT_Y = HEADER_H + TAB_H + 6;
    private static final int ITEM_ROW_H = 20, VISIBLE_ROWS = 5;
    private static final int LIST_H = ITEM_ROW_H * VISIBLE_ROWS;
    private static final int FOOTER_H = 54;
    private static final Chrome.Dims DIMS = Chrome.Dims.of(W, H, 0, 0);

    private final OpenBusinessWorkerPacket data;
    private int panelX, panelY;
    private Business.WorkerRole activeRole;
    private Business.ProducerType activeProducerType;
    private int selectedItemIndex = -1;
    private int targetCount;
    private long editWage;
    private String searchText = "";
    private boolean dropdownOpen = false;
    private int dropdownScroll = 0;
    private List<OpenBusinessWorkerPacket.AvailableItem> filteredItems = List.of();
    private TabBar<Business.WorkerRole> tabBar;
    private ScrollList<OpenBusinessWorkerPacket.AvailableItem> itemList;
    private ScrollList<OpenBusinessWorkerPacket.SellListing>   sellList;
    private StyledEditBox searchBox;
    private final TooltipLayer tooltips = new TooltipLayer();

    public BusinessWorkerScreen(OpenBusinessWorkerPacket data) {
        super(Component.literal(data.npcName()));
        this.data               = data;
        this.activeRole         = parseRole(data.role());
        this.activeProducerType = parseProducerType(data.producerType());
        this.targetCount        = Math.max(1, data.currentTargetCount());
        this.editWage           = data.wage();
        for (int i = 0; i < data.availableItems().size(); i++) {
            if (data.availableItems().get(i).itemId().equals(data.currentItemId())) {
                selectedItemIndex = i; break;
            }
        }
    }

    public static void open(net.minecraft.server.level.ServerPlayer player,
                            TownspersonMob npc, Business business) {
        var level = (net.minecraft.server.level.ServerLevel) npc.level();
        sendOpenPacket(player, npc.getUUID(), business.getBusinessId(),
                level, BusinessSavedData.get(level), VillageSavedData.get(level));
    }

    public static void sendOpenPacket(net.minecraft.server.level.ServerPlayer player,
                                      UUID npcId, UUID businessId,
                                      net.minecraft.server.level.ServerLevel level,
                                      BusinessSavedData cdata, VillageSavedData vdata) {
        Business business = cdata.getById(businessId).orElse(null);
        if (business == null) return;

        Business.BusinessWorker worker = business.getWorker(npcId).orElse(null);
        String npcName = TownspersonMob.findByUUID(level, npcId)
                .map(n -> n.getNpcName()).orElse("Worker");

        Map<String, OpenBusinessWorkerPacket.AvailableItem> itemMap = new LinkedHashMap<>();
        UUID NULL_BUILDING = UUID.fromString("00000000-0000-0000-0000-000000000000");

        if (worker != null && !worker.assignedBuildingId().equals(NULL_BUILDING))
            vdata.getBuildingById(worker.assignedBuildingId()).ifPresent(b ->
                    scanBuildingIntoMap(level, b, itemMap, business, vdata));
        for (UUID bid : business.getBuildingIds()) {
            if (worker != null && bid.equals(worker.assignedBuildingId())) continue;
            vdata.getBuildingById(bid).ifPresent(b ->
                    scanBuildingIntoMap(level, b, itemMap, business, vdata));
        }

        List<OpenBusinessWorkerPacket.CompanyBuildingEntry> companyBuildings = new ArrayList<>();
        for (UUID bid : business.getBuildingIds())
            vdata.getBuildingById(bid).ifPresent(b ->
                    companyBuildings.add(new OpenBusinessWorkerPacket.CompanyBuildingEntry(
                            bid, b.getName(), b.getType().name())));

        long currentMarketPrice = 0L;
        if (worker != null && !worker.assignedItemId().isEmpty()) {
            var ai = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.Identifier.parse(worker.assignedItemId()))
                    .map(h -> h.value()).orElse(null);
            if (ai != null)
                currentMarketPrice = tterrag1112.life_in_the_village.Village.Economy
                        .VillageEconomy.getDynamicPrice(level, business.getHomeVillageId(), ai);
        }

        List<OpenBusinessWorkerPacket.SellListing> sellListings = new ArrayList<>();
        if (worker != null && worker.role() == Business.WorkerRole.SELLER) {
            for (var override : business.getAllPriceOverrides()) {
                String itemId = override.itemId();
                int totalStock = 0;
                for (UUID bid : business.getBuildingIds()) {
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
                        .getDynamicPrice(level, business.getHomeVillageId(), item) : 0L;
                String displayName = item != null
                        ? item.getDefaultInstance().getHoverName().getString() : itemId;
                sellListings.add(new OpenBusinessWorkerPacket.SellListing(
                        itemId, displayName, totalStock, override.pricePerUnit(), marketP));
            }
        }

        long minWage    = business.getEffectiveMinWage(vdata);
        long wage       = worker != null ? worker.wagePerDay()       : minWage;
        String currentId  = worker != null ? worker.assignedItemId()   : "";
        int    currentQty = worker != null ? worker.dailyTargetCount() : 8;
        String role     = worker != null ? worker.role().name()
                : Business.WorkerRole.PRODUCER.name();
        String prodType = worker != null ? worker.producerType().name()
                : Business.ProducerType.GENERIC.name();

        PacketDistributor.sendToPlayer(player, new OpenBusinessWorkerPacket(
                npcId, businessId, npcName, wage, minWage, currentId, currentQty,
                role, prodType, new ArrayList<>(itemMap.values()),
                companyBuildings, currentMarketPrice, sellListings));
    }

    private static void scanBuildingIntoMap(
            net.minecraft.server.level.ServerLevel level,
            tterrag1112.life_in_the_village.Village.Building building,
            Map<String, OpenBusinessWorkerPacket.AvailableItem> itemMap,
            Business business, VillageSavedData vdata) {
        for (var container : tterrag1112.life_in_the_village.Village
                .BuildingStorageAccess.findInventories(level, building)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                var stack = container.getItem(i);
                if (stack.isEmpty()) continue;
                String key = net.minecraft.core.registries.BuiltInRegistries
                        .ITEM.getKey(stack.getItem()).toString();
                long marketPrice = tterrag1112.life_in_the_village.Village.Economy
                        .VillageEconomy.getDynamicPrice(
                                level, business.getHomeVillageId(), stack.getItem());
                if (itemMap.containsKey(key)) {
                    var ex = itemMap.get(key);
                    itemMap.put(key, new OpenBusinessWorkerPacket.AvailableItem(
                            key, ex.displayName(), ex.stockCount() + stack.getCount(), marketPrice));
                } else {
                    itemMap.put(key, new OpenBusinessWorkerPacket.AvailableItem(
                            key, stack.getHoverName().getString(), stack.getCount(), marketPrice));
                }
            }
        }
    }

    @Override
    protected void init() {
        panelX = (width - W) / 2;
        panelY = (height - H) / 2;
        List<TabBar.Entry<Business.WorkerRole>> tabs = new ArrayList<>();
        for (Business.WorkerRole r : Business.WorkerRole.values())
            tabs.add(new TabBar.Entry<>(r, formatRole(r.name()), true));
        tabBar = new TabBar<>(panelX, panelY + HEADER_H, TAB_H, tabs,
                () -> activeRole,
                r -> { activeRole = r; selectedItemIndex = -1; buildWidgets(); });
        buildWidgets();
    }

    @Override public boolean isPauseScreen() { return false; }

    private void buildWidgets() {
        clearWidgets();
        itemList  = null;
        sellList  = null;
        searchBox = null;
        int contentTop = panelY + CONTENT_Y;
        switch (activeRole) {
            case PRODUCER -> buildProducerWidgets(contentTop);
            case SELLER   -> buildSellerWidgets(contentTop);
            case COURIER  -> buildCourierWidgets(contentTop);
        }
        int wageY = panelY + H - FOOTER_H + 4;
        addRenderableWidget(StyledButton.builder(Component.literal("−"),
                b -> { editWage = Math.max(data.minWage(), editWage - 1); sendWageChange(editWage); })
            .pos(panelX + 8, wageY).size(16, 14).build());
        addRenderableWidget(StyledButton.builder(Component.literal("+"),
                b -> { editWage++; sendWageChange(editWage); })
            .pos(panelX + 28, wageY).size(16, 14).build());
    }

    private void buildProducerWidgets(int contentTop) {
        Set<String> ownedTypes = getOwnedProducerTypes();
        int ptX = panelX + 8, ptY = contentTop + 2;
        for (Business.ProducerType pt : Business.ProducerType.values()) {
            boolean available = ownedTypes.contains(pt.name());
            final Business.ProducerType t = pt;
            StyledButton btn = StyledButton.builder(Component.literal(formatRole(pt.name())),
                            b -> { if (!available) return; activeProducerType = t;
                                selectedItemIndex = -1; sendProducerTypeChange(t); buildWidgets(); })
                    .pos(ptX, ptY).size(48, 12).build();
            btn.active = available;
            addRenderableWidget(btn);
            ptX += 52;
            if (ptX + 48 > panelX + W - 8) { ptX = panelX + 8; ptY += 16; }
        }
        int listY = contentTop + 42;
        itemList = new ScrollList<>(panelX + 8, listY, W - 36, LIST_H, ITEM_ROW_H,
                data.availableItems(), this::drawItemRow, this::onItemClick);
        int countY = contentTop + 42 + LIST_H + 8;
        addRenderableWidget(StyledButton.builder(Component.literal("−"),
                b -> { targetCount = Math.max(1, targetCount - 1); buildWidgets(); })
            .pos(panelX + W / 2 - 24, countY).size(16, 12).build());
        addRenderableWidget(StyledButton.builder(Component.literal("+"),
                b -> { targetCount = Math.min(64, targetCount + 1); buildWidgets(); })
            .pos(panelX + W / 2 + 8, countY).size(16, 12).build());
        addRenderableWidget(StyledButton.builder(Component.literal("Assign Production Task"),
                b -> { if (selectedItemIndex >= 0 && selectedItemIndex < data.availableItems().size())
                           sendTask(data.availableItems().get(selectedItemIndex).itemId(), targetCount); })
            .pos(panelX + 8, panelY + H - 24).size(W - 16, 16).build());
    }

    private void buildSellerWidgets(int contentTop) {
        rebuildFilteredItems();
        int searchY = contentTop + 2, searchW = W - 20;
        searchBox = new StyledEditBox(font, panelX + 8, searchY, searchW, 14,
                Component.literal("Search items…"));
        searchBox.setMaxLength(32);
        searchBox.setValue(searchText);
        searchBox.setResponder(text -> {
            searchText = text;
            dropdownOpen = !text.isBlank();
            dropdownScroll = 0;
            rebuildFilteredItems();
            buildWidgets();
        });
        searchBox.setFocused(dropdownOpen);
        addRenderableWidget(searchBox);

        if (dropdownOpen) {
            int dy = searchY + 16, shown = 0;
            for (int i = dropdownScroll; i < filteredItems.size() && shown < 5; i++, shown++) {
                final var item = filteredItems.get(i);
                addRenderableWidget(StyledButton.builder(Component.literal(item.displayName()),
                        b -> { sendSellerAdd(item.itemId(), item.marketPrice());
                               dropdownOpen = false; searchText = ""; buildWidgets(); })
                    .pos(panelX + 8, dy + shown * 14).size(searchW, 13).build());
            }
            if (dropdownScroll > 0)
                addRenderableWidget(StyledButton.builder(Component.literal("▲"),
                        b -> { dropdownScroll--; buildWidgets(); })
                    .pos(panelX + 8 + searchW - 12, searchY + 16).size(12, 10).build());
            if (dropdownScroll + 5 < filteredItems.size())
                addRenderableWidget(StyledButton.builder(Component.literal("▼"),
                        b -> { dropdownScroll++; buildWidgets(); })
                    .pos(panelX + 8 + searchW - 12, searchY + 86).size(12, 10).build());
        }

        int listTop = contentTop + 20 + (dropdownOpen ? 72 : 0);
        int listH   = Math.max(20, (panelY + H - FOOTER_H - 4) - listTop);
        sellList = new ScrollList<>(panelX + 8, listTop, W - 16, listH, 20,
                data.currentSellListings(), this::drawSellRow, this::onSellClick);
    }

    private void buildCourierWidgets(int contentTop) {
        int listY = contentTop + 14;
        itemList = new ScrollList<>(panelX + 8, listY, W - 36, LIST_H, ITEM_ROW_H,
                data.availableItems(), this::drawItemRow, this::onItemClick);
        int countY = contentTop + 14 + LIST_H + 8;
        addRenderableWidget(StyledButton.builder(Component.literal("−"),
                b -> { targetCount = Math.max(1, targetCount - 1); buildWidgets(); })
            .pos(panelX + W / 2 - 24, countY).size(16, 12).build());
        addRenderableWidget(StyledButton.builder(Component.literal("+"),
                b -> { targetCount = Math.min(64, targetCount + 1); buildWidgets(); })
            .pos(panelX + W / 2 + 8, countY).size(16, 12).build());
        addRenderableWidget(StyledButton.builder(Component.literal("Assign Courier Route"),
                b -> { if (selectedItemIndex >= 0)
                           sendTask(data.availableItems().get(selectedItemIndex).itemId(), targetCount); })
            .pos(panelX + 8, panelY + H - 24).size(W - 16, 16).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        tooltips.reset();
        g.fill(0, 0, width, height, 0x88000000);
        Chrome.draw(g, panelX, panelY, DIMS, Chrome.PARCHMENT);
        g.fill(panelX, panelY, panelX + W, panelY + HEADER_H, BookScreenColors.SIDEBAR);
        g.drawCenteredString(font, data.npcName(),
                panelX + W / 2, panelY + 6, BookScreenColors.DARK);
        tabBar.render(g, mx, my);

        int contentTop = panelY + CONTENT_Y;
        switch (activeRole) {
            case PRODUCER -> drawProducerContent(g, contentTop, mx, my);
            case SELLER   -> drawSellerContent(g, contentTop, mx, my);
            case COURIER  -> drawCourierContent(g, contentTop, mx, my);
        }
        g.fill(panelX + 4, panelY + H - FOOTER_H,
                panelX + W - 4, panelY + H - FOOTER_H + 1, BookScreenColors.BORDER);
        drawWageRow(g);
        super.render(g, mx, my, pt);
        tooltips.flush(g);
    }

    private void drawProducerContent(GuiGraphics g, int contentTop, int mx, int my) {
        Set<String> ownedTypes = getOwnedProducerTypes();
        int ptX = panelX + 8, ptY = contentTop + 2;
        for (Business.ProducerType pt : Business.ProducerType.values()) {
            boolean available = ownedTypes.contains(pt.name());
            boolean isActive  = pt == activeProducerType;
            int bg = isActive   ? BookScreenColors.COL_ACTIVE
                    : available ? BookScreenColors.HIGHLIGHT
                                : BookScreenColors.COL_DISABLED;
            g.fill(ptX - 1, ptY - 1, ptX + 49, ptY + 13, bg);
            ptX += 52;
            if (ptX + 48 > panelX + W - 8) { ptX = panelX + 8; ptY += 16; }
        }
        if (activeProducerType.requiredBuilding() != null) {
            String hint = "Uses: " + activeProducerType.requiredBuilding()
                    .name().toLowerCase().replace('_', ' ');
            g.drawString(font, hint, panelX + 8, contentTop + 36,
                    BookScreenColors.MID, false);
        }
        drawItemList(g, contentTop + 42, mx, my, "Select item to produce:");

        int countY = contentTop + 42 + LIST_H + 8;
        g.drawString(font, "Daily target:", panelX + 8, countY + 2,
                BookScreenColors.MID, false);
        g.drawCenteredString(font, String.valueOf(targetCount),
                panelX + W / 2, countY + 2, BookScreenColors.DARK);
        if (!data.currentItemId().isEmpty())
            g.drawString(font, "Now: " + formatItemId(data.currentItemId())
                    + " ×" + data.currentTargetCount() + "/day",
                    panelX + 8, countY + 18, BookScreenColors.GREEN_TXT, false);
    }

    private void drawCourierContent(GuiGraphics g, int contentTop, int mx, int my) {
        drawItemList(g, contentTop + 14, mx, my, "Choose item to courier:");
        int countY = contentTop + 14 + LIST_H + 8;
        g.drawString(font, "Batch size per trip:", panelX + 8, countY + 2,
                BookScreenColors.MID, false);
        g.drawCenteredString(font, String.valueOf(targetCount),
                panelX + W / 2, countY + 2, BookScreenColors.DARK);
        if (!data.currentItemId().isEmpty())
            g.drawString(font, "Couriering: " + formatItemId(data.currentItemId())
                    + " ×" + data.currentTargetCount(),
                    panelX + 8, countY + 18, BookScreenColors.GREEN_TXT, false);
    }

    private void drawWageRow(GuiGraphics g) {
        int wageY = panelY + H - FOOTER_H + 4;
        g.drawString(font, "Daily wage:", panelX + 50, wageY + 2,
                BookScreenColors.MID, false);
        int afterCoin = CoinRenderer.renderCoinRow(g, editWage, panelX + 115, wageY);
        g.drawString(font, "/day", afterCoin + 2, wageY + 2, BookScreenColors.MID, false);
        if (editWage < data.minWage())
            g.drawString(font, "below min. wage!",
                    panelX + 8, wageY + 16, BookScreenColors.RED_TXT, false);
    }

    private void drawItemList(GuiGraphics g, int listY, int mx, int my, String header) {
        int listX = panelX + 8, listW = W - 36;
        g.drawString(font, header, listX, listY - 10, BookScreenColors.MID, false);
        g.fill(listX, listY, listX + listW, listY + LIST_H, 0xFFEEE8D0);
        g.renderOutline(listX, listY, listW, LIST_H, BookScreenColors.BORDER);
        if (data.availableItems().isEmpty())
            g.drawCenteredString(font, "No items found in business buildings",
                    listX + listW / 2, listY + LIST_H / 2 - 4, BookScreenColors.LIGHT);
        else if (itemList != null)
            itemList.render(g, mx, my);
    }

    private void drawItemRow(GuiGraphics g, int rx, int ry, int rw, int rh,
                             OpenBusinessWorkerPacket.AvailableItem item, boolean hovered) {
        int idx = data.availableItems().indexOf(item);
        boolean sel = idx == selectedItemIndex;
        int bg = sel     ? BookScreenColors.COL_SELECTED
                : hovered ? BookScreenColors.HIGHLIGHT
                : (idx % 2 == 0 ? 0xFFEEE8D0 : 0xFFE8E0C8);
        g.fill(rx, ry, rx + rw, ry + ITEM_ROW_H, bg);
        var stack = resolveStack(item.itemId(), 1);
        if (stack != null) {
            g.renderItem(stack, rx + 2, ry + 2);
            g.renderItemDecorations(font, stack, rx + 2, ry + 2, null);
        }
        String label = item.displayName();
        if (font.width(label) > rw - 90) label = label.substring(0, 9) + "…";
        g.drawString(font, label, rx + 22, ry + 6,
                sel ? BookScreenColors.DARK : BookScreenColors.MID, false);
        String stock = "×" + item.stockCount();
        int coinW = CoinRenderer.coinRowWidth(item.marketPrice());
        g.drawString(font, stock, rx + rw - coinW - font.width(stock) - 4,
                ry + 6, BookScreenColors.GOLD, false);
        if (item.marketPrice() > 0)
            CoinRenderer.renderCoinRow(g, item.marketPrice(),
                    rx + rw - coinW - 2, ry + 2);
    }

    private boolean onItemClick(OpenBusinessWorkerPacket.AvailableItem item,
                                int btn, double relX, double relY) {
        selectedItemIndex = data.availableItems().indexOf(item);
        return true;
    }

    private void drawSellerContent(GuiGraphics g, int contentTop, int mx, int my) {
        int searchY = contentTop + 2, searchW = W - 20;

        if (dropdownOpen && !filteredItems.isEmpty()) {
            int dy = searchY + 16, shown = 0;
            for (int i = dropdownScroll;
                 i < filteredItems.size() && shown < 5; i++, shown++) {
                var item = filteredItems.get(i);
                int ry = dy + shown * 14;
                boolean hov = mx >= panelX + 8 && mx < panelX + 8 + searchW
                        && my >= ry && my < ry + 14;
                g.fill(panelX + 8, ry, panelX + 8 + searchW, ry + 13,
                        hov ? BookScreenColors.HIGHLIGHT : 0xFFEEE8D0);
                g.renderOutline(panelX + 8, ry, searchW, 13, BookScreenColors.BORDER);
                var stack = resolveStack(item.itemId(), 1);
                if (stack != null) g.renderItem(stack, panelX + 8, ry - 1);
                g.drawString(font, item.displayName(), panelX + 26, ry + 2,
                        BookScreenColors.DARK, false);
                String qty = "×" + item.stockCount();
                int coinW = CoinRenderer.coinRowWidth(item.marketPrice());
                g.drawString(font, qty,
                        panelX + 8 + searchW - coinW - font.width(qty) - 8,
                        ry + 2, BookScreenColors.MID, false);
                CoinRenderer.renderCoinRow(g, item.marketPrice(),
                        panelX + 8 + searchW - coinW - 2, ry - 2);
            }
            g.renderOutline(panelX + 8, searchY + 16, searchW,
                    Math.min(filteredItems.size(), 5) * 14, BookScreenColors.BORDER);
        }

        int listTop = contentTop + 20 + (dropdownOpen ? 72 : 0);
        if (data.currentSellListings().isEmpty()) {
            g.drawString(font, "No items assigned to sell.",
                    panelX + 8, listTop + 10, BookScreenColors.LIGHT, false);
            g.drawString(font, "Use the search bar above to add items.",
                    panelX + 8, listTop + 22, BookScreenColors.LIGHT, false);
            return;
        }
        g.fill(panelX + 8, listTop - 12, panelX + W - 8, listTop - 1, 0xFFE8E0C8);
        g.drawString(font, "Item",  panelX + 26,      listTop - 10, BookScreenColors.MID, false);
        g.drawString(font, "Stock", panelX + W - 140, listTop - 10, BookScreenColors.MID, false);
        g.drawString(font, "Price", panelX + W - 100, listTop - 10, BookScreenColors.MID, false);
        if (sellList != null) sellList.render(g, mx, my);
    }

    private void drawSellRow(GuiGraphics g, int rx, int ry, int rw, int rh,
                             OpenBusinessWorkerPacket.SellListing listing, boolean hovered) {
        int idx = data.currentSellListings().indexOf(listing);
        boolean hasOverride = listing.customPrice() > 0
                && listing.customPrice() != listing.marketPrice();
        g.fill(rx, ry, rx + rw, ry + 19,
                idx % 2 == 0 ? 0xFFEEE8D0 : BookScreenColors.PARCHMENT);
        g.renderOutline(rx, ry, rw, 19, BookScreenColors.BORDER);
        var stack = resolveStack(listing.itemId(), 1);
        if (stack != null) g.renderItem(stack, rx, ry + 2);
        String name = listing.displayName();
        while (font.width(name) > 80 && name.length() > 3)
            name = name.substring(0, name.length() - 1);
        if (!name.equals(listing.displayName())) name += "…";
        g.drawString(font, name, rx + 18, ry + 6, BookScreenColors.DARK, false);
        g.drawString(font, "(" + listing.stockCount() + ")",
                rx + rw - 132, ry + 6, BookScreenColors.MID, false);
        int priceX = rx + rw - 92;
        if (hasOverride) {
            int mpWidth = CoinRenderer.coinRowWidth(listing.marketPrice());
            CoinRenderer.renderCoinRow(g, listing.marketPrice(), priceX, ry + 2);
            g.fill(priceX, ry + 8, priceX + mpWidth, ry + 9, 0xFFCC0000);
            CoinRenderer.renderCoinRow(g, listing.customPrice(),
                    priceX + mpWidth + 4, ry + 2);
        } else {
            CoinRenderer.renderCoinRow(g, listing.marketPrice(), priceX, ry + 2);
        }
        drawMini(g, rx + rw - 38, ry + 3, 12, 14, "◀", false);
        drawMini(g, rx + rw - 24, ry + 3, 12, 14, "▶", false);
        drawMini(g, rx + rw - 10, ry + 3, 10, 14, "×", false);
    }

    private boolean onSellClick(OpenBusinessWorkerPacket.SellListing listing,
                                int btn, double relX, double relY) {
        int lw = W - 16;
        if (relX >= lw - 38 && relX < lw - 26) {
            sendPriceAdjust(listing.itemId(), Math.max(1, listing.effectivePrice() - 1));
            return true;
        }
        if (relX >= lw - 24 && relX < lw - 12) {
            sendPriceAdjust(listing.itemId(), listing.effectivePrice() + 1);
            return true;
        }
        if (relX >= lw - 10 && relX < lw) {
            sendPriceRemove(listing.itemId()); buildWidgets();
            return true;
        }
        return false;
    }

    private void drawMini(GuiGraphics g, int bx, int by, int bw, int bh,
                          String label, boolean hov) {
        g.fill(bx, by, bx + bw, by + bh,
                hov ? BookScreenColors.HIGHLIGHT : BookScreenColors.PARCHMENT);
        g.renderOutline(bx, by, bw, bh, BookScreenColors.BORDER);
        int tw = font.width(label);
        g.drawString(font, label, bx + (bw - tw) / 2, by + (bh - 8) / 2,
                BookScreenColors.DARK, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (consumed) return super.mouseClicked(event, consumed);
        double mx = event.x(), my = event.y();
        int button = event.button();
        if (tabBar.mouseClicked(mx, my)) return true;

        if (activeRole == Business.WorkerRole.SELLER) {
            int searchY = panelY + CONTENT_Y + 2, searchW = W - 20;
            if (mx >= panelX + 8 && mx < panelX + 8 + searchW
                    && my >= searchY && my < searchY + 14) {
                dropdownOpen = true; rebuildFilteredItems(); buildWidgets();
                return true;
            }
            if (dropdownOpen) { dropdownOpen = false; buildWidgets(); return true; }
            if (sellList != null && sellList.mouseClicked(mx, my, button)) return true;
        }

        if (activeRole != Business.WorkerRole.SELLER && itemList != null
                && itemList.mouseClicked(mx, my, button)) return true;

        return super.mouseClicked(event, consumed);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (activeRole == Business.WorkerRole.SELLER) {
            if (sellList != null && sellList.mouseScrolled(mx, my, dy)) return true;
        } else {
            if (itemList != null && itemList.mouseScrolled(mx, my, dy)) return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) { return super.keyPressed(event); }

    private void send(BusinessActionPacket.ActionType type, UUID target,
                      String str, long lp, int ip) {
        ClientPacketDistributor.sendToServer(new BusinessActionPacket(
                type, data.businessId(), target, str, lp, ip));
    }
    private void sendTask(String itemId, int count) {
        send(BusinessActionPacket.ActionType.SET_WORKER_ROLE, data.npcId(), "", 0L, activeRole.ordinal());
        send(BusinessActionPacket.ActionType.ASSIGN_WORKER_TASK, data.npcId(), itemId, 0L, count);
    }
    private void sendWageChange(long wage) {
        send(BusinessActionPacket.ActionType.SET_WORKER_WAGE, data.npcId(), "", wage, 0);
    }
    private void sendProducerTypeChange(Business.ProducerType type) {
        send(BusinessActionPacket.ActionType.SET_PRODUCER_TYPE, data.npcId(), "", 0L, type.ordinal());
    }
    private void sendSellerAdd(String itemId, long marketPrice) {
        send(BusinessActionPacket.ActionType.SET_ITEM_PRICE, new UUID(0, 0), itemId, marketPrice, 0);
        send(BusinessActionPacket.ActionType.SET_WORKER_ROLE, data.npcId(), "", 0L, Business.WorkerRole.SELLER.ordinal());
    }
    private void sendPriceAdjust(String itemId, long newPrice) {
        send(BusinessActionPacket.ActionType.SET_ITEM_PRICE, new UUID(0, 0), itemId, newPrice, 0);
    }
    private void sendPriceRemove(String itemId) {
        send(BusinessActionPacket.ActionType.REMOVE_ITEM_PRICE, new UUID(0, 0), itemId, 0L, 0);
    }

    private void rebuildFilteredItems() {
        String query = searchText.toLowerCase();
        filteredItems = data.availableItems().stream()
                .filter(item -> query.isBlank()
                        || item.displayName().toLowerCase().contains(query)
                        || item.itemId().toLowerCase().contains(query))
                .filter(item -> data.currentSellListings().stream()
                        .noneMatch(l -> l.itemId().equals(item.itemId())))
                .toList();
    }

    private Set<String> getOwnedProducerTypes() {
        Set<String> owned = new HashSet<>();
        owned.add("GENERIC");
        for (var entry : data.companyBuildings()) {
            for (Business.ProducerType pt : Business.ProducerType.values()) {
                if (pt.requiredBuilding() != null
                        && pt.requiredBuilding().name().equals(entry.buildingType()))
                    owned.add(pt.name());
            }
        }
        return owned;
    }

    private static Business.WorkerRole parseRole(String s) {
        try { return Business.WorkerRole.valueOf(s); }
        catch (Exception e) { return Business.WorkerRole.PRODUCER; }
    }

    private static Business.ProducerType parseProducerType(String s) {
        try { return Business.ProducerType.valueOf(s); }
        catch (Exception e) { return Business.ProducerType.GENERIC; }
    }

    private static net.minecraft.world.item.ItemStack resolveStack(String itemId, int count) {
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
