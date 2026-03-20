package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Networking.CompanyActionPacket;
import tterrag1112.life_in_the_village.Networking.OpenVillageBookPacket;
import tterrag1112.life_in_the_village.Networking.VillageActionPacket;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoute;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

public class VillageBookScreen extends Screen {

    // -------------------------------------------------------------------------
    // Layout — same book dimensions as KingdomBookScreen
    // -------------------------------------------------------------------------

    private static final int BOOK_W    = 420;
    private static final int BOOK_H    = 300;
    private static final int SIDEBAR_W = 130;
    private static final int PAGE_PAD  = 14;
    private static final int ROW_H     = 28;

    // -------------------------------------------------------------------------
    // Colors — same palette as KingdomBookScreen
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
    private static final int COL_RED_BG    = 0xFFEAC8C8;
    private static final int COL_RED_TXT   = 0xFF8B1A1A;
    private static final int COL_GOLD      = 0xFFB8860B;
    private static final int COL_AMBER     = 0xFFE8A020;
    private static final int COL_BLUE_BG   = 0xFFD0E4F8;

    // -------------------------------------------------------------------------
    // Sections
    // -------------------------------------------------------------------------

    private enum Section {
        OVERVIEW, HOUSING, STATISTICS, MAP, STANDINGS
    }

    private record NavEntry(String label, Section section) {}

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final OpenVillageBookPacket data;
    private int bookX, bookY;
    private Section currentSection = Section.OVERVIEW;

    // Housing scroll
    private int housingScroll = 0;

    // Map state — mirrors VillageMapScreen
    private final VillageMapRenderer mapRenderer = new VillageMapRenderer();


    // Map display inside the book
    private static final int MAP_W = 220;
    private static final int MAP_H = 180;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public VillageBookScreen(OpenVillageBookPacket data) {
        super(Component.literal("Village — " + data.villageName()));
        this.data = data;
    }

    // -------------------------------------------------------------------------
    // Static helper — called server-side to build and send packet
    // -------------------------------------------------------------------------

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
        int pop = (int) level.getEntitiesOfClass(
                tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                village.getBounds(vdata)
                        .map(b -> b.inflate(32))
                        .orElse(new AABB(0,0,0,0,0,0)),
                npc -> npc.getAssignedVillageName()
                        .map(n -> n.equals(village.getName())).orElse(false)
        ).size();

        // Leader name
        String leaderName = level.getEntitiesOfClass(
                tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                village.getBounds(vdata).map(b -> b.inflate(32))
                        .orElse(new AABB(0,0,0,0,0,0)),
                npc -> npc.getProfession() ==
                        tterrag1112.life_in_the_village.Profession.Profession.VILLAGE_LEADER
        ).stream().findFirst().map(npc -> npc.getNpcName()).orElse("None");

        // Houses
        List<OpenVillageBookPacket.HouseEntry> houses = new ArrayList<>();
        for (UUID bid : village.getBuildingIds()) {
            vdata.getBuildingById(bid).ifPresent(b -> {
                if (b.getType() != BuildingType.HOUSE) return;
                long price = tterrag1112.life_in_the_village.Village.Buildings
                        .HousePurchaseManager.calculatePrice(b, village, vdata);
                long tax   = tterrag1112.life_in_the_village.Village.Buildings
                        .HousePurchaseManager.calculateWeeklyTax(b, village, vdata);
                boolean mine = vdata.getPropertyForBuilding(bid)
                        .map(p -> p.playerId().equals(player.getUUID())).orElse(false);
                boolean others = vdata.isPlayerOwned(bid) && !mine;
                boolean npcHere = !level.getEntitiesOfClass(
                        tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                        b.getShape().toAABB().inflate(16),
                        npc -> npc.getHouseId().map(id -> id.equals(bid)).orElse(false)
                ).isEmpty();
                houses.add(new OpenVillageBookPacket.HouseEntry(
                        bid, b.getName(), price, tax, mine, others, npcHere));
            });
        }

        // Needs
        Map<String, String> needs = new LinkedHashMap<>();
        village.getNeeds().forEach((cat, need) ->
                needs.put(formatEnum(cat.name()), formatEnum(need.getLevel().name())));

        // Pending expansion
        String expansion = vdata.getPendingExpansionForVillage(villageId)
                .map(r -> formatEnum(r.getBuildingType().name()))
                .orElse("");

        // Building types present
        List<String> btypes = village.getBuildingIds().stream()
                .map(vdata::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(b -> formatEnum(b.getType().name()))
                .distinct().sorted().toList();

        // Player wealth
        SimpleContainer playerContainer =
                new net.minecraft.world.SimpleContainer(
                        player.getInventory().getContainerSize());
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            playerContainer.setItem(i, player.getInventory().getItem(i).copy());
        }
        long playerWealth = CoinHelper.getWealth(playerContainer).toBronze();

        // Player reputation
        int playerRep = village.getReputation(player.getUUID());
        boolean hasWarn = vdata.hasWarning(player.getUUID(), villageId);

// Kingdom name
        String kingdomName = vdata.getKingdomForVillage(villageId)
                .map(k -> k.getName()).orElse("");

// Active event
        String activeEvent = vdata.getActiveEventForVillage(villageId)
                .map(e -> formatEnum(e.getType().name())).orElse("");

// Trade route count
        int routeCount = (int) vdata.getAllTradeRoutes().stream()
                .filter(r -> r.connects(villageId, r.getVillageA())
                        || r.connects(villageId, r.getVillageB()))
                .filter(r -> r.getStatus() ==
                        TradeRoute.RouteStatus.ACTIVE)
                .count();

        PacketDistributor.sendToPlayer(player,
                new OpenVillageBookPacket(villageId, village.getName(), leaderName,
                        tier, pop, village.getTreasuryBronze(), houses, needs,
                        expansion, btypes, playerWealth, playerRep, hasWarn, kingdomName, activeEvent, routeCount));
    }

    private static String formatEnum(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.charAt(0) + s.substring(1).toLowerCase().replace('_', ' ');
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Widget construction
    // -------------------------------------------------------------------------

    private void buildWidgets() {
        clearWidgets();

        // ---- Sidebar nav buttons ----
        Section[] sections = Section.values();
        int ny = bookY + 44;
        for (Section s : sections) {
            final Section sec = s;
            // Use invisible region — clicking is handled in mouseClicked
            // (same pattern as KingdomBookScreen)
            ny += 20;
        }

        // ---- Housing buy buttons ----
        if (currentSection == Section.HOUSING) {
            buildHousingButtons();
        }

        if(currentSection== Section.STANDINGS) {
            // Found company button — only shown for Town+ villages
            if (data.tierName().equals("Town") || data.tierName().equals("City")) {
                int px   = bookX + SIDEBAR_W + PAGE_PAD;
                int pw   = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;
                int btnY = bookY + 36 + 120;

                addRenderableWidget(Button.builder(
                                Component.literal("Found a Company"),
                                b -> ClientPacketDistributor.sendToServer(
                                        new CompanyActionPacket(
                                                CompanyActionPacket.ActionType.FOUND_COMPANY,
                                                UUID.randomUUID(),
                                                data.villageId(),
                                                "My Company",
                                                0L, 0)))
                        .pos(px, btnY).size(pw, 16).build());
            }
        }
    }

    private void buildHousingButtons() {
        int px   = bookX + SIDEBAR_W + PAGE_PAD;
        int py   = bookY + 40;
        int pw   = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;
        int maxY = bookY + BOOK_H - 36;

        int visibleStart = housingScroll;
        int y = py;
        int index = 0;

        for (OpenVillageBookPacket.HouseEntry house : data.houses()) {
            if (index < visibleStart) { index++; continue; }
            if (y + ROW_H + 4 > maxY) break;

            boolean canBuy = !house.ownedByThisPlayer()
                    && !house.ownedByOtherPlayer()
                    && !house.occupiedByNpc();

            if (canBuy) {
                final UUID buildingId = house.buildingId();
                int btnX = px + pw - 46;
                int btnY = y + (ROW_H - 14) / 2;
                addRenderableWidget(Button.builder(
                                Component.literal("Buy"),
                                b -> {
                                    ClientPacketDistributor.sendToServer(
                                            new VillageActionPacket(
                                                    VillageActionPacket.ActionType.BUY_HOUSE,
                                                    data.villageId(),
                                                    buildingId,
                                                    "", 0));
                                })
                        .pos(btnX, btnY)
                        .size(44, 14).build());
            }

            y += ROW_H + 4;
            index++;
        }

        // Scroll arrows
        int maxY2 = bookY + BOOK_H - 36;
        int px2 = bookX + SIDEBAR_W + PAGE_PAD;
        int pw2 = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;

        if (housingScroll > 0) {
            addRenderableWidget(Button.builder(Component.literal("▲"),
                            b -> { housingScroll--; buildWidgets(); })
                    .pos(px2 + pw2 - 18, bookY + 40)
                    .size(14, 10).build());
        }
        int maxVisible = (maxY2 - (bookY + 40)) / (ROW_H + 4);
        if (housingScroll + maxVisible < data.houses().size()) {
            addRenderableWidget(Button.builder(Component.literal("▼"),
                            b -> { housingScroll++; buildWidgets(); })
                    .pos(px2 + pw2 - 18, maxY2 - 10)
                    .size(14, 10).build());
        }
    }

    // -------------------------------------------------------------------------
    // Map baking — same logic as VillageMapScreen, bounded to MAP_W×MAP_H
    // -------------------------------------------------------------------------

    private void startMapBake() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Village village = Building.ClientBuildingCache.getNearestVillage(
                        mc.player != null ? mc.player.blockPosition() : BlockPos.ZERO)
                .orElse(null);
        if (village == null) return;

        mapRenderer.startBake(village, MAP_W, MAP_H);
    }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        mapRenderer.tick();

        // dim background
        g.fill(0, 0, width, height, 0x88000000);
        drawBook(g);
        drawSidebar(g);
        drawPageContent(g, mx, my);
        super.render(g, mx, my, pt);
    }

    private void drawBook(GuiGraphics g) {
        // shadow
        g.fill(bookX + 3, bookY + 3, bookX + BOOK_W + 3, bookY + BOOK_H + 3, 0x44000000);
        // body
        g.fill(bookX, bookY, bookX + BOOK_W, bookY + BOOK_H, COL_PARCHMENT);
        // border × 2
        g.renderOutline(bookX, bookY, BOOK_W, BOOK_H, COL_BORDER);
        g.renderOutline(bookX + 2, bookY + 2, BOOK_W - 4, BOOK_H - 4, COL_HIGHLIGHT);
        // header separator
        g.fill(bookX + SIDEBAR_W, bookY + 28, bookX + BOOK_W, bookY + 29, COL_BORDER);
        // footer separator
        g.fill(bookX + SIDEBAR_W, bookY + BOOK_H - 30, bookX + BOOK_W, bookY + BOOK_H - 29, COL_BORDER);
        // page title
        String title = currentSection.name().charAt(0)
                + currentSection.name().substring(1).toLowerCase();
        g.drawString(font, title, bookX + SIDEBAR_W + PAGE_PAD, bookY + 10, COL_DARK, false);
        // corner flourishes
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
        g.fill(bookX + SIDEBAR_W - 1, bookY, bookX + SIDEBAR_W, bookY + BOOK_H, COL_BORDER);

        // Village name header
        g.drawString(font, "\u265A " + data.villageName(),
                bookX + 8, bookY + 10, COL_DARK, false);
        g.fill(bookX + 4, bookY + 22, bookX + SIDEBAR_W - 4, bookY + 23, COL_BORDER);

        // Nav entries
        int ny = bookY + 30;
        String lastGroup = "";
        for (Section s : Section.values()) {
            String group = sectionGroup(s);
            if (!group.equals(lastGroup)) {
                g.drawString(font, group.toUpperCase(), bookX + 8, ny, COL_LIGHT, false);
                ny += 12;
                lastGroup = group;
            }
            boolean active = s == currentSection;
            if (active) {
                g.fill(bookX + 2, ny - 1, bookX + SIDEBAR_W - 1, ny + 9, COL_HIGHLIGHT);
                g.fill(bookX + 2, ny - 1, bookX + 4, ny + 9, COL_GOLD);
            }
            g.drawString(font, (active ? "> " : "  ") + sectionLabel(s),
                    bookX + 6, ny, active ? COL_DARK : COL_MID, false);
            ny += 18;
        }
    }

    private String sectionGroup(Section s) {
        return switch (s) {
            case OVERVIEW, STATISTICS -> "Village";
            case HOUSING              -> "Property";
            case MAP                  -> "Map";
            case STANDINGS            -> "Player";
        };
    }

    private String sectionLabel(Section s) {
        return switch (s) {
            case OVERVIEW   -> "Overview";
            case HOUSING    -> "Housing";
            case STATISTICS -> "Statistics";
            case MAP        -> "Map";
            case STANDINGS  -> "My Standing";
        };
    }

    private void drawPageContent(GuiGraphics g, int mx, int my) {
        int px   = bookX + SIDEBAR_W + PAGE_PAD;
        int py   = bookY + 36;
        int pw   = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;
        int maxY = bookY + BOOK_H - 34;

        switch (currentSection) {
            case OVERVIEW   -> drawOverview(g, px, py, pw, maxY);
            case HOUSING    -> drawHousing(g, px, py, pw, maxY, mx, my);
            case STATISTICS -> drawStatistics(g, px, py, pw, maxY);
            case MAP        -> drawMap(g, px, py, pw, maxY, mx, my);
            case STANDINGS  -> drawStandings(g, px, py, pw, maxY);
        }
    }

    // -------------------------------------------------------------------------
    // Overview page
    // -------------------------------------------------------------------------

    private void drawOverview(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;

        // Village name banner
        g.fill(px, y, px + pw, y + 18, COL_HIGHLIGHT);
        g.renderOutline(px, y, pw, 18, COL_BORDER);
        g.drawCenteredString(font, data.villageName(),
                px + pw / 2, y + 5, COL_DARK);
        if (!data.kingdomName().isEmpty()) {
            String sub = "Kingdom of " + data.kingdomName();
            int sw = font.width(sub);
            g.drawString(font, sub, px + pw / 2 - sw / 2, y + 5, COL_MID, false);
            // shift village name up
            g.fill(px, y, px + pw, y + 18, COL_HIGHLIGHT);
            g.renderOutline(px, y, pw, 18, COL_BORDER);
            g.drawCenteredString(font, data.villageName(),
                    px + pw / 2, y + 2, COL_DARK);
            g.drawString(font, sub, px + pw / 2 - sw / 2,
                    y + 10, COL_MID, false);
        }
        y += 24;

        // Active event notice
        if (!data.activeEventName().isEmpty()) {
            g.fill(px, y, px + pw, y + 12, 0xFFFFEECC);
            g.renderOutline(px, y, pw, 12, COL_AMBER);
            String ev = "\u2605 Event: " + data.activeEventName();
            g.drawString(font, ev, px + 3, y + 2, COL_AMBER, false);
            y += 16;
        }

        // Stat grid — 2 columns × 3 rows
        int colW = pw / 2 - 4;
        record StatEntry(String label, String value) {}
        StatEntry[] stats = {
                new StatEntry("Leader",     data.leaderName().isEmpty() ? "None" : data.leaderName()),
                new StatEntry("Tier",       data.tierName()),
                new StatEntry("Population", String.valueOf(data.population())),
                new StatEntry("Treasury",   formatBronze(data.treasuryBronze())),
                new StatEntry("Trade Routes", String.valueOf(data.tradeRouteCount())),
                new StatEntry("Buildings",  String.valueOf(data.buildingTypes().size())),
        };
        for (int i = 0; i < stats.length; i++) {
            if (y + 11 > maxY) break;
            int col = i % 2;
            int row = i / 2;
            int lx = px + col * (colW + 8);
            int ly = y + row * 13;
            g.drawString(font, stats[i].label() + ":", lx, ly, COL_MID, false);
            g.drawString(font, stats[i].value(),
                    lx + font.width(stats[i].label() + ": "), ly, COL_DARK, false);
        }
        y += ((stats.length + 1) / 2) * 13 + 6;

        if (y + 1 < maxY) {
            g.fill(px, y, px + pw, y + 1, COL_BORDER);
            y += 7;
        }

        // Needs summary — compact colored dots
        g.drawString(font, "Needs:", px, y, COL_MID, false);
        y += 12;
        int needX = px;
        for (Map.Entry<String, String> need : data.needs().entrySet()) {
            if (y + 10 > maxY) break;
            int col = needColor(need.getValue());
            g.fill(needX, y + 2, needX + 6, y + 8, col);
            g.drawString(font, need.getKey(), needX + 8, y, COL_DARK, false);
            int entryW = font.width(need.getKey()) + 14;
            needX += entryW;
            // Wrap to next line if out of space
            if (needX > px + pw - 60) {
                needX = px;
                y += 12;
            }
        }
        if (needX > px) y += 12;

        // Expansion
        if (!data.pendingExpansion().isEmpty() && y + 10 < maxY) {
            g.fill(px, y, px + pw, y + 12, COL_BLUE_BG);
            g.renderOutline(px, y, pw, 12, COL_BORDER);
            g.drawString(font, "\u2192 Building: " + data.pendingExpansion(),
                    px + 3, y + 2, 0xFF2255AA, false);
        }
    }

    // -------------------------------------------------------------------------
    // Housing page
    // -------------------------------------------------------------------------

    private void drawHousing(GuiGraphics g, int px, int py, int pw, int maxY,
                             int mx, int my) {
        int y = py;

        // Wallet banner
        g.fill(px, y, px + pw, y + 12, COL_HIGHLIGHT);
        g.renderOutline(px, y, pw, 12, COL_BORDER);
        String wallet = "Your wallet: " + formatBronze(data.playerWealthBronze());
        g.drawString(font, wallet, px + 3, y + 2, COL_GOLD, false);
        y += 14;

        // Column headers
        g.fill(px, y, px + pw, y + 11, 0xFFE8E0C8);
        g.drawString(font, "House",   px + 2,          y + 2, COL_MID, false);
        g.drawString(font, "Price",   px + pw / 2 - 8, y + 2, COL_MID, false);
        g.drawString(font, "Tax/wk",  px + pw - 74,    y + 2, COL_MID, false);
        g.drawString(font, "Status",  px + pw - 36,    y + 2, COL_MID, false);
        y += 13;

        if (data.houses().isEmpty()) {
            g.drawCenteredString(font, "No houses in this village.",
                    px + pw / 2, py + 60, COL_MID);
            return;
        }

        int visibleStart = housingScroll;
        int index = 0;

        for (OpenVillageBookPacket.HouseEntry house : data.houses()) {
            if (index < visibleStart) { index++; continue; }
            if (y + ROW_H > maxY - 12) break;

            boolean hovered = mx >= px && mx <= px + pw
                    && my >= y && my <= y + ROW_H;

            int rowBg;
            String statusLabel;
            int statusColor;

            if (house.ownedByThisPlayer()) {
                rowBg = COL_GREEN_BG; statusLabel = "Yours";  statusColor = COL_GREEN_TXT;
            } else if (house.ownedByOtherPlayer()) {
                rowBg = COL_RED_BG;   statusLabel = "Owned";  statusColor = COL_RED_TXT;
            } else if (house.occupiedByNpc()) {
                rowBg = 0xFFEEEECC;   statusLabel = "Occupied"; statusColor = COL_MID;
            } else {
                boolean canAfford = data.playerWealthBronze() >= house.priceBronze();
                rowBg = hovered ? 0xFFEEE8D0 : COL_PARCHMENT;
                statusLabel = canAfford ? "Buy" : "Costly";
                statusColor = canAfford ? COL_GREEN_TXT : COL_RED_TXT;
            }

            g.fill(px, y, px + pw, y + ROW_H, rowBg);
            g.renderOutline(px, y, pw, ROW_H, COL_BORDER);

            // Name — truncate if too long
            String name = house.name();
            while (font.width(name) > pw / 2 - 4 && name.length() > 4)
                name = name.substring(0, name.length() - 1);
            if (!name.equals(house.name())) name += "..";

            g.drawString(font, name, px + 3, y + (ROW_H - 8) / 2, COL_DARK, false);
            g.drawString(font, formatBronze(house.priceBronze()),
                    px + pw / 2 - 8, y + (ROW_H - 8) / 2, COL_GOLD, false);
            String taxStr = house.taxPerWeekBronze() > 0
                    ? formatBronze(house.taxPerWeekBronze()) : "—";
            g.drawString(font, taxStr, px + pw - 74,
                    y + (ROW_H - 8) / 2, COL_MID, false);
            g.drawString(font, statusLabel, px + pw - 36,
                    y + (ROW_H - 8) / 2, statusColor, false);

            y += ROW_H + 2;
            index++;
        }
    }

    // -------------------------------------------------------------------------
    // Statistics page
    // -------------------------------------------------------------------------

    private void drawStatistics(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;

        // --- Needs bars ---
        g.drawString(font, "Needs", px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 14;

        int labelW = 70;
        int barW   = pw - labelW - 40; // leave room for level text

        for (Map.Entry<String, String> need : data.needs().entrySet()) {
            if (y + 11 > maxY) break;
            g.drawString(font, need.getKey(), px, y, COL_DARK, false);
            int filled = needBarFill(need.getValue(), barW);
            int barCol = needColor(need.getValue());
            // Track
            g.fill(px + labelW, y + 1, px + labelW + barW, y + 9, 0xFFDDD8C0);
            // Fill
            if (filled > 0)
                g.fill(px + labelW, y + 1, px + labelW + filled, y + 9, barCol);
            g.renderOutline(px + labelW, y + 1, barW, 8, COL_BORDER);
            // Level label
            g.drawString(font, need.getValue(),
                    px + labelW + barW + 3, y, COL_MID, false);
            y += 13;
        }

        y += 4;
        if (y + 14 > maxY) return;

        // --- Buildings ---
        g.drawString(font, "Buildings (" + data.buildingTypes().size() + ")",
                px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 14;

        // Two-column list — properly tracks x position per item
        List<String> types = data.buildingTypes();
        int halfW = pw / 2;
        int col = 0;
        int rowStartY = y;
        for (int i = 0; i < types.size(); i++) {
            if (y + 10 > maxY) break;
            int lx = px + col * halfW;
            g.drawString(font, "\u2022 " + types.get(i), lx, y, COL_DARK, false);
            col++;
            if (col >= 2) {
                col = 0;
                y += 11;
            }
        }
        if (col > 0) y += 11; // finish last partial row
        y += 4;

        // --- Trade routes ---
        if (y + 14 > maxY) return;
        g.drawString(font, "Trade Routes", px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 14;

        int routeCount = data.tradeRouteCount();
        g.drawString(font,
                routeCount == 0 ? "No active trade routes"
                        : routeCount + " active route" + (routeCount > 1 ? "s" : ""),
                px, y, COL_DARK, false);
        y += 13;

        // --- Expansion queue ---
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

    // -------------------------------------------------------------------------
    // Map page — embedded village map
    // -------------------------------------------------------------------------

    private void drawMap(GuiGraphics g, int px, int py, int pw, int maxY,
                         int mx, int my) {
        int mapLeft = px + (pw - MAP_W) / 2;
        int mapTop  = py;
        int mapH    = Math.min(MAP_H, maxY - py);

        // Frame
        g.fill(mapLeft - 1, mapTop - 1,
                mapLeft + MAP_W + 1, mapTop + mapH + 1, 0xFFB8A878);

        // Delegate all rendering to shared renderer
        mapRenderer.draw(g, mapLeft, mapTop, MAP_W, mapH, mx, my);

        // Village name below
        if (mapTop + mapH + 6 < maxY) {
            g.drawCenteredString(font, data.villageName(),
                    mapLeft + MAP_W / 2, mapTop + mapH + 4, 0xFF7A6040);
        }
    }



    // -------------------------------------------------------------------------
    // My Standing page — placeholder for future systems
    // -------------------------------------------------------------------------

    private void drawStandings(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;

        // --- Reputation ---
        g.drawString(font, "Your Reputation", px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 15;

        int rep = data.playerReputation();
        String repLabel = reputationLabel(rep);
        int repColor = reputationColor(rep);

        // Reputation bar — -1000 to 1000, centre is 0
        int barW = pw - 4;
        int barH = 10;
        // Track
        g.fill(px, y, px + barW, y + barH, 0xFFDDD8C0);
        // Zero line
        int zeroX = px + barW / 2;
        g.fill(zeroX, y, zeroX + 1, y + barH, 0xFFAAAAAA);
        // Filled portion from centre
        int fillPx = (int)(Math.abs(rep) / 1000.0f * (barW / 2));
        if (rep >= 0) {
            g.fill(zeroX, y, zeroX + fillPx, y + barH, repColor);
        } else {
            g.fill(zeroX - fillPx, y, zeroX, y + barH, repColor);
        }
        g.renderOutline(px, y, barW, barH, COL_BORDER);
        y += barH + 3;

        g.drawString(font, rep + "  —  " + repLabel,
                px, y, repColor, false);
        y += 14;

        if (data.playerHasWarning()) {
            g.fill(px, y, px + pw, y + 12, COL_RED_BG);
            g.renderOutline(px, y, pw, 12, COL_BORDER);
            g.drawString(font, "\u26A0 You have an active warning here",
                    px + 3, y + 2, COL_RED_TXT, false);
            y += 16;
        }

        y += 4;
        g.fill(px, y, px + pw, y + 1, COL_BORDER);
        y += 8;

        // --- Property ---
        g.drawString(font, "Property", px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 15;

        List<OpenVillageBookPacket.HouseEntry> ownedHere = data.houses().stream()
                .filter(OpenVillageBookPacket.HouseEntry::ownedByThisPlayer)
                .toList();

        if (ownedHere.isEmpty()) {
            g.drawString(font, "You own no property here.", px, y, COL_MID, false);
        } else {
            for (OpenVillageBookPacket.HouseEntry h : ownedHere) {
                if (y + 10 > maxY) break;
                g.fill(px, y, px + pw, y + 10, COL_GREEN_BG);
                g.renderOutline(px, y, pw, 10, COL_BORDER);
                g.drawString(font, "\u2302 " + h.name(), px + 3, y + 1, COL_GREEN_TXT, false);
                String tax = h.taxPerWeekBronze() > 0
                        ? formatBronze(h.taxPerWeekBronze()) + "/wk"
                        : "No tax";
                int tw = font.width(tax);
                g.drawString(font, tax, px + pw - tw - 2, y + 1, COL_MID, false);
                y += 14;
            }
        }

        y += 8;
        if (y + 1 < maxY) g.fill(px, y, px + pw, y + 1, COL_BORDER);
        y += 8;

        if (data.tierName().equals("Town") || data.tierName().equals("City")) {
            // Check if player already has a company here — requires sending that
            // info in OpenVillageBookPacket (add boolean hasCompanyHere)
            // For now, always show the button
            g.drawString(font, "Company", px, y, COL_MID, false);
            g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
            y += 15;
            // Button added in buildWidgets for the STANDINGS section
        }

        // --- Leadership path (placeholder but structured) ---
        if (y + 14 > maxY) return;
        g.drawString(font, "Path to Leadership", px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 15;

        // Threshold tiers — show progress toward each
        record Milestone(int threshold, String label) {}
        Milestone[] milestones = {
                new Milestone(100,  "Resident — trusted community member"),
                new Milestone(300,  "Citizen — eligible for civic roles"),
                new Milestone(600,  "Notable — known to the village leader"),
                new Milestone(1000, "Steward — eligible for leadership"),
        };
        for (Milestone m : milestones) {
            if (y + 10 > maxY) break;
            boolean achieved = rep >= m.threshold();
            int mc = achieved ? COL_GREEN_TXT : COL_MID;
            String prefix = achieved ? "\u2713 " : "\u25CB ";
            g.drawString(font, prefix + m.label(), px + 4, y, mc, false);
            y += 11;
        }

        y += 6;
        if (y + 10 < maxY) {
            g.drawString(font, "Company system — coming soon",
                    px, y, COL_LIGHT, false);
        }
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

    // -------------------------------------------------------------------------
    // Mouse — sidebar navigation
    // -------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (consumed) return super.mouseClicked(event, consumed);

        double mx = event.x(), my = event.y();
        String lastGroup = "";
        int ny = bookY + 30;

        for (Section s : Section.values()) {
            String group = sectionGroup(s);
            if (!group.equals(lastGroup)) { ny += 12; lastGroup = group; }
            if (ny + 10 > bookY + BOOK_H - 10) break;
            if (mx >= bookX + 2 && mx <= bookX + SIDEBAR_W - 1
                    && my >= ny - 1 && my <= ny + 9) {
                currentSection = s;
                housingScroll = 0;
                buildWidgets();
                return true;
            }
            ny += 18;
        }
        return super.mouseClicked(event, consumed);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String formatBronze(long bronze) {
        long gold   = bronze / 10000L;
        long silver = (bronze % 10000L) / 100L;
        long b      = bronze % 100L;
        StringBuilder sb = new StringBuilder();
        if (gold   > 0) sb.append(gold).append("g ");
        if (silver > 0) sb.append(silver).append("s ");
        if (b > 0 || sb.isEmpty()) sb.append(b).append("b");
        return sb.toString().trim();
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
}