package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import tterrag1112.life_in_the_village.Gui.Framework.*;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.KingdomMapPanel;
import tterrag1112.life_in_the_village.Kingdom.*;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Networking.KingdomActionPacket;
import tterrag1112.life_in_the_village.Networking.RequestKingdomMapSyncPacket;

import java.util.*;

public class KingdomBookScreen extends Screen {

    private static final int BOOK_W = 400, BOOK_H = 320;
    private static final int SIDEBAR_W = 140, PAGE_PAD = 16;
    private static final Chrome.Dims DIMS = Chrome.Dims.of(BOOK_W, BOOK_H, SIDEBAR_W, PAGE_PAD);

    private enum SectionType {
        FRONTISPIECE, STATUS, HISTORY, LAWS, ECONOMY,
        APPOINTMENTS, DIPLOMACY, DECREES, KINGDOM_MAP, ROYAL_BUILDS
    }

    private record NavEntry(String label, SectionType section, int historyPageIndex) {}

    record KingdomEntry(UUID id, String name, int villageCount, DiplomaticRelation relation) {}
    record VillageEntry(UUID id, String name, String tier, String leader) {}

    private final UUID kingdomId;
    private int bookX, bookY;
    private int page = 0;

    private String kingdomName = "Loading...";
    private String rulerName   = "Unknown";
    private int villageCount   = 0;
    private long treasury      = 0;
    private int activeLawCount = 0;
    private int taxRate        = 10;
    private long upkeep        = 32;
    private long foundingTick  = 0L;

    private final Set<KingdomLaw> activeLaws               = new HashSet<>();
    private final List<KingdomEntry> kingdoms              = new ArrayList<>();
    private final List<VillageEntry> villages              = new ArrayList<>();
    private final List<HistoryTextGenerator.HistoryPage>
            historyPages                                   = new ArrayList<>();
    private final List<NavEntry> navEntries                = new ArrayList<>();

    private KingdomMapPanel mapPanel;
    private Sidebar<SectionType> sidebar;
    private StyledEditBox decreeBox;

    public KingdomBookScreen(UUID kingdomId) {
        super(Component.literal("Kingdom Book"));
        this.kingdomId = kingdomId;
    }

    public UUID getKingdomId() { return kingdomId; }

    @Override
    protected void init() {
        bookX = (width  - BOOK_W) / 2;
        bookY = (height - BOOK_H) / 2;
        refreshData();
        buildWidgets();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    public void refresh() {
        refreshData();
        buildWidgets();
    }

    public void onMapDataSynced() {
        if (mapPanel != null) mapPanel.refresh();
    }

    public void refreshData() {
        Kingdom k = Kingdom.ClientKingdomCache.getById(kingdomId).orElse(null);
        if (k == null) {
            System.out.println("KingdomBookScreen.refreshData: kingdom not in cache");
            buildNavEntries();
            return;
        }

        kingdomName    = k.getName();
        rulerName      = k.getRulerPlayerId().map(id -> "Player").orElse("Unknown");
        villageCount   = k.getVillageIds().size();
        treasury       = k.getTreasuryBronze();
        taxRate        = (int)(k.getIncomeTaxRate() * 100);
        upkeep         = k.getFlatUpkeepBronze();
        activeLaws.clear();
        activeLaws.addAll(k.getActiveLaws());
        activeLawCount = activeLaws.size();
        foundingTick   = k.getHistory().getOrigin().map(o -> o.foundingTick()).orElse(0L);

        kingdoms.clear();
        k.getAllRelations().forEach((otherId, rel) ->
                Kingdom.ClientKingdomCache.getById(otherId).ifPresent(other ->
                        kingdoms.add(new KingdomEntry(other.getId(), other.getName(),
                                other.getVillageIds().size(), rel))));

        villages.clear();

        long currentTick = net.minecraft.client.Minecraft.getInstance().level != null
                ? net.minecraft.client.Minecraft.getInstance().level.getGameTime() : 0L;

        historyPages.clear();
        historyPages.addAll(HistoryTextGenerator.buildHistoryPages(
                k.getHistory(), kingdomName, rulerName, currentTick, foundingTick));

        System.out.println("KingdomBookScreen.refreshData: " + historyPages.size()
                + " history pages built from " + k.getHistory().getEvents().size() + " events");

        if (mapPanel == null) mapPanel = new KingdomMapPanel(kingdomId);
        mapPanel.refresh();
        ClientPacketDistributor.sendToServer(new RequestKingdomMapSyncPacket(kingdomId));

        buildNavEntries();
    }

    private void buildNavEntries() {
        navEntries.clear();
        navEntries.add(new NavEntry("Frontispiece", SectionType.FRONTISPIECE, -1));
        navEntries.add(new NavEntry("Status",       SectionType.STATUS,       -1));
        navEntries.add(new NavEntry("History",      SectionType.HISTORY,       0));
        navEntries.add(new NavEntry("Laws",         SectionType.LAWS,         -1));
        navEntries.add(new NavEntry("Economy",      SectionType.ECONOMY,      -1));
        navEntries.add(new NavEntry("Appointments", SectionType.APPOINTMENTS, -1));
        navEntries.add(new NavEntry("Diplomacy",    SectionType.DIPLOMACY,    -1));
        navEntries.add(new NavEntry("Decrees",      SectionType.DECREES,      -1));
        navEntries.add(new NavEntry("Kingdom Map",  SectionType.KINGDOM_MAP,  -1));
        navEntries.add(new NavEntry("Royal Builds", SectionType.ROYAL_BUILDS, -1));
        page = Math.min(page, navEntries.size() - 1);
    }

    private void buildWidgets() {
        clearWidgets();
        decreeBox = null;

        sidebar = new Sidebar<>(bookX + 2, bookY + 28, SIDEBAR_W - 2, 18,
                List.of(
                        new Sidebar.Entry<>(SectionType.FRONTISPIECE, "Frontispiece", true),
                        new Sidebar.Entry<>(SectionType.STATUS,       "Status",       true),
                        new Sidebar.Entry<>(SectionType.HISTORY,      "History",      true),
                        new Sidebar.Entry<>(SectionType.LAWS,         "Laws",         true),
                        new Sidebar.Entry<>(SectionType.ECONOMY,      "Economy",      true),
                        new Sidebar.Entry<>(SectionType.APPOINTMENTS, "Appointments", true),
                        new Sidebar.Entry<>(SectionType.DIPLOMACY,    "Diplomacy",    true),
                        new Sidebar.Entry<>(SectionType.DECREES,      "Decrees",      true),
                        new Sidebar.Entry<>(SectionType.KINGDOM_MAP,  "Kingdom Map",  true),
                        new Sidebar.Entry<>(SectionType.ROYAL_BUILDS, "Royal Builds", true)
                ),
                this::currentSection,
                section -> {
                    for (int i = 0; i < navEntries.size(); i++) {
                        if (navEntries.get(i).section() == section) {
                            page = i; buildWidgets(); return;
                        }
                    }
                });

        addRenderableWidget(StyledButton.builder(Component.literal("←"), b -> changePage(-1))
                .pos(bookX + SIDEBAR_W + 8, bookY + BOOK_H - 28).size(32, 18).build());
        addRenderableWidget(StyledButton.builder(Component.literal("→"), b -> changePage(1))
                .pos(bookX + BOOK_W - 40, bookY + BOOK_H - 28).size(32, 18).build());

        int px = bookX + SIDEBAR_W + PAGE_PAD;
        int py = bookY + 36;
        int pw = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;

        switch (currentSection()) {
            case LAWS      -> buildLawWidgets(px, py, pw);
            case ECONOMY   -> buildEconomyWidgets(px, py, pw);
            case DIPLOMACY -> buildDiplomacyWidgets(px, py, pw);
            case DECREES   -> buildDecreeWidgets(px, py, pw);
            default        -> {}
        }
    }

    private void buildLawWidgets(int px, int py, int pw) {
        KingdomLaw[] laws = KingdomLaw.values();
        for (int i = 0; i < laws.length; i++) {
            KingdomLaw law = laws[i];
            boolean active = activeLaws.contains(law);
            int by = py + i * 28;
            if (by + 20 > bookY + BOOK_H - 32) break;
            addRenderableWidget(StyledButton.builder(
                            Component.literal(active ? "Repeal" : "Enact"),
                            b -> sendAction(KingdomActionPacket.ActionType.TOGGLE_LAW, law.name(), 0))
                    .pos(px + pw - 52, by).size(50, 16).build());
        }
    }

    private void buildEconomyWidgets(int px, int py, int pw) {
        addRenderableWidget(StyledButton.builder(Component.literal("-"), b -> {
            taxRate = Math.max(0, taxRate - 5);
            sendAction(KingdomActionPacket.ActionType.SET_TAX_RATE, "", taxRate);
        }).pos(px + pw / 2 - 40, py + 28).size(24, 16).build());

        addRenderableWidget(StyledButton.builder(Component.literal("+"), b -> {
            taxRate = Math.min(50, taxRate + 5);
            sendAction(KingdomActionPacket.ActionType.SET_TAX_RATE, "", taxRate);
        }).pos(px + pw / 2 + 16, py + 28).size(24, 16).build());

        addRenderableWidget(StyledButton.builder(Component.literal("-"), b -> {
            upkeep = Math.max(0, upkeep - 8);
            sendAction(KingdomActionPacket.ActionType.SET_UPKEEP, "", (int) upkeep);
        }).pos(px + pw / 2 - 40, py + 72).size(24, 16).build());

        addRenderableWidget(StyledButton.builder(Component.literal("+"), b -> {
            upkeep = Math.min(256, upkeep + 8);
            sendAction(KingdomActionPacket.ActionType.SET_UPKEEP, "", (int) upkeep);
        }).pos(px + pw / 2 + 16, py + 72).size(24, 16).build());
    }

    private void buildDiplomacyWidgets(int px, int py, int pw) {
        DiplomaticRelation[] rels = DiplomaticRelation.values();
        for (int i = 0; i < kingdoms.size(); i++) {
            KingdomEntry k = kingdoms.get(i);
            int by = py + 16 + i * 32;
            if (by + 20 > bookY + BOOK_H - 32) break;
            int fi = i;
            addRenderableWidget(StyledButton.builder(Component.literal("Change"), b -> {
                DiplomaticRelation cur  = kingdoms.get(fi).relation();
                DiplomaticRelation next = rels[(cur.ordinal() + 1) % rels.length];
                sendAction(KingdomActionPacket.ActionType.SET_RELATION,
                        k.id() + ":" + next.name(), 0);
            }).pos(px + pw - 54, by + 6).size(52, 16).build());
        }
    }

    private void buildDecreeWidgets(int px, int py, int pw) {
        decreeBox = new StyledEditBox(font, px, py + 20, pw, 60,
                Component.literal("Write decree..."));
        decreeBox.setMaxLength(256);
        addRenderableWidget(decreeBox);

        addRenderableWidget(StyledButton.builder(Component.literal("Issue Decree"), b -> {
            if (decreeBox != null && !decreeBox.getValue().isEmpty()) {
                sendAction(KingdomActionPacket.ActionType.ISSUE_DECREE, decreeBox.getValue(), 0);
                decreeBox.setValue("");
            }
        }).pos(px, py + 88).size(80, 18).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        Chrome.draw(g, bookX, bookY, DIMS, Chrome.PARCHMENT);
        Chrome.drawSidebarBg(g, bookX, bookY, DIMS);
        drawSidebarHeader(g);
        sidebar.render(g, mx, my);
        drawPageChrome(g);
        drawPageContent(g, mx, my);
        super.render(g, mx, my, pt);
    }

    private void drawSidebarHeader(GuiGraphics g) {
        g.drawString(font, "♛ " + kingdomName, bookX + 8, bookY + 10,
                BookScreenColors.DARK, false);
        g.fill(bookX + 4, bookY + 22, bookX + SIDEBAR_W - 4, bookY + 23,
                BookScreenColors.BORDER);
    }

    private void drawPageChrome(GuiGraphics g) {
        g.fill(bookX + SIDEBAR_W, bookY + 28,
                bookX + BOOK_W, bookY + 29, BookScreenColors.BORDER);
        g.fill(bookX + SIDEBAR_W, bookY + BOOK_H - 30,
                bookX + BOOK_W, bookY + BOOK_H - 29, BookScreenColors.BORDER);

        String title = page < navEntries.size() ? navEntries.get(page).label() : "";
        if (currentSection() == SectionType.HISTORY) {
            int hi = historySubPage();
            if (hi >= 0 && hi < historyPages.size()) title = historyPages.get(hi).title();
        }
        g.drawString(font, title, bookX + SIDEBAR_W + PAGE_PAD, bookY + 10,
                BookScreenColors.DARK, false);

        String counter = currentSection() == SectionType.HISTORY && !historyPages.isEmpty()
                ? (historySubPage() + 1) + " / " + historyPages.size()
                : (page + 1) + " / " + navEntries.size();
        int cw = font.width(counter);
        g.drawString(font, counter, bookX + BOOK_W - cw - PAGE_PAD, bookY + 10,
                BookScreenColors.LIGHT, false);

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
        int pw = BOOK_W - SIDEBAR_W - PAGE_PAD * 2, maxY = bookY + BOOK_H - 34;
        switch (currentSection()) {
            case FRONTISPIECE -> drawFrontispiece(g, px, py, pw);
            case STATUS       -> drawStatus(g, px, py, pw, maxY);
            case HISTORY      -> drawHistory(g, px, py, pw, maxY);
            case LAWS         -> drawLaws(g, px, py, pw, maxY);
            case ECONOMY      -> drawEconomy(g, px, py, pw, maxY);
            case APPOINTMENTS -> drawAppointments(g, px, py, pw, maxY);
            case DIPLOMACY    -> drawDiplomacy(g, px, py, pw, maxY);
            case DECREES      -> drawDecrees(g, px, py, pw, maxY);
            case KINGDOM_MAP  -> drawKingdomMap(g, px, py, pw, maxY, mx, my);
            case ROYAL_BUILDS -> drawRoyalBuilds(g, px, py, pw, maxY);
        }
    }

    private void drawFrontispiece(GuiGraphics g, int px, int py, int pw) {
        String crown = "♛";
        int cw = font.width(crown);
        g.drawString(font, crown, px + pw / 2 - cw / 2, py + 20,
                BookScreenColors.GOLD, false);

        int nw = font.width(kingdomName);
        g.drawString(font, kingdomName, px + pw / 2 - nw / 2, py + 50,
                BookScreenColors.DARK, false);
        g.fill(px + pw / 4, py + 62, px + pw * 3 / 4, py + 63, BookScreenColors.BORDER);

        String tag = "By right of deed, by will of the realm";
        int tw = font.width(tag);
        g.drawString(font, tag, px + pw / 2 - tw / 2, py + 70,
                BookScreenColors.MID, false);

        String ruler = "Ruler: " + rulerName;
        int rw = font.width(ruler);
        g.drawString(font, ruler, px + pw / 2 - rw / 2, py + 110,
                BookScreenColors.LIGHT, false);
    }

    private void drawStatus(GuiGraphics g, int px, int py, int pw, int maxY) {
        drawStatBox(g, px,       py, 72, 44, "Villages", String.valueOf(villageCount));
        drawStatBox(g, px + 80,  py, 72, 44, "Treasury", treasury + "b");
        drawStatBox(g, px + 160, py, 72, 44, "Laws",     String.valueOf(activeLawCount));

        int y = py + 54;
        g.drawString(font, "Villages", px, y, BookScreenColors.MID, false);
        g.fill(px, y + 10, px + pw, y + 11, BookScreenColors.BORDER);
        y += 14;

        for (VillageEntry v : villages) {
            if (y + 10 > maxY) break;
            g.drawString(font, v.name() + " · " + v.tier(), px, y,
                    BookScreenColors.DARK, false);
            int lw = font.width(v.leader());
            g.drawString(font, v.leader(), px + pw - lw, y, BookScreenColors.MID, false);
            y += 14;
        }
    }

    private void drawStatBox(GuiGraphics g, int x, int y, int w, int h,
                             String label, String value) {
        g.fill(x, y, x + w, y + h, BookScreenColors.HIGHLIGHT);
        g.renderOutline(x, y, w, h, BookScreenColors.BORDER);
        int lw = font.width(label);
        g.drawString(font, label, x + w / 2 - lw / 2, y + 6,
                BookScreenColors.MID, false);
        int vw = font.width(value);
        g.drawString(font, value, x + w / 2 - vw / 2, y + 22,
                BookScreenColors.DARK, false);
    }

    private void drawHistory(GuiGraphics g, int px, int py, int pw, int maxY) {
        if (historyPages.isEmpty()) {
            g.drawString(font, "No history has been recorded yet.", px, py,
                    BookScreenColors.MID, false);
            return;
        }

        int hi = historySubPage();
        if (hi < 0 || hi >= historyPages.size()) return;
        HistoryTextGenerator.HistoryPage hp = historyPages.get(hi);

        int y = py;
        switch (hp.type()) {
            case ORIGIN -> {
                g.drawString(font, "The Founding", px, y, BookScreenColors.GOLD, false);
                g.fill(px, y + 10, px + pw, y + 11, BookScreenColors.BORDER);
                y += 18;
            }
            case EVENT -> {
                g.fill(px, y + 4, px + pw, y + 5, BookScreenColors.HIGHLIGHT);
                y += 12;
            }
            case FILLER -> {
                g.fill(px + pw / 4, y + 4, px + pw * 3 / 4, y + 5,
                        BookScreenColors.BORDER);
                y += 14;
            }
        }

        for (String paragraph : hp.text().split("\n", -1)) {
            if (paragraph.isEmpty()) { y += 6; continue; }
            for (String line : wrapText(paragraph, pw)) {
                if (y + 10 > maxY - 12) break;
                int color = hp.type() == HistoryTextGenerator.HistoryPageType.FILLER
                        ? BookScreenColors.MID : BookScreenColors.DARK;
                g.drawString(font, line, px, y, color, false);
                y += 12;
            }
        }

        if (hp.type() == HistoryTextGenerator.HistoryPageType.FILLER) {
            g.fill(px + pw / 4, y + 6, px + pw * 3 / 4, y + 7, BookScreenColors.BORDER);
        }

        if (historyPages.size() > 1) {
            String hint = "← → to browse history";
            int hw = font.width(hint);
            g.drawString(font, hint, px + pw / 2 - hw / 2, maxY - 10,
                    BookScreenColors.LIGHT, false);
        }
    }

    private void drawLaws(GuiGraphics g, int px, int py, int pw, int maxY) {
        KingdomLaw[] laws = KingdomLaw.values();
        int y = py;
        for (KingdomLaw law : laws) {
            if (y + 22 > maxY) break;
            boolean active = activeLaws.contains(law);
            if (active) g.fill(px - 2, y - 1, px + pw + 2, y + 21, BookScreenColors.GREEN_BG);
            g.drawString(font, formatLawName(law.name()), px, y + 4,
                    active ? BookScreenColors.GREEN_TXT : BookScreenColors.DARK, false);
            g.drawString(font, active ? "[On]" : "[Off]", px + 120, y + 4,
                    active ? BookScreenColors.GREEN_TXT : BookScreenColors.LIGHT, false);
            g.fill(px, y + 21, px + pw, y + 22, BookScreenColors.HIGHLIGHT);
            y += 28;
        }
    }

    private void drawEconomy(GuiGraphics g, int px, int py, int pw, int maxY) {
        g.drawString(font, "Income tax rate", px, py, BookScreenColors.DARK, false);
        int vw1 = font.width(taxRate + "%");
        g.drawString(font, taxRate + "%", px + pw / 2 - vw1 / 2, py + 16,
                BookScreenColors.DARK, false);
        g.fill(px, py + 36, px + pw, py + 37, BookScreenColors.HIGHLIGHT);

        g.drawString(font, "Flat upkeep per village", px, py + 44,
                BookScreenColors.DARK, false);
        int vw2 = font.width(upkeep + " bronze");
        g.drawString(font, upkeep + " bronze", px + pw / 2 - vw2 / 2, py + 60,
                BookScreenColors.DARK, false);
        g.fill(px, py + 80, px + pw, py + 81, BookScreenColors.HIGHLIGHT);

        g.drawString(font, "Treasury", px, py + 90, BookScreenColors.MID, false);
        drawStatBox(g, px,      py + 102, 72, 40, "Balance", treasury + "b");
        drawStatBox(g, px + 80, py + 102, 80, 40, "Est. daily",
                "~" + (villageCount * upkeep + 200L * taxRate / 100) + "b");
    }

    private void drawAppointments(GuiGraphics g, int px, int py, int pw, int maxY) {
        g.drawString(font, "Village leaders", px, py, BookScreenColors.MID, false);
        g.fill(px, py + 10, px + pw, py + 11, BookScreenColors.BORDER);
        int y = py + 16;
        for (VillageEntry v : villages) {
            if (y + 22 > maxY) break;
            g.drawString(font, v.name(), px,      y + 4, BookScreenColors.DARK, false);
            g.drawString(font, v.tier(), px + 80, y + 4, BookScreenColors.MID,  false);
            String leader = v.leader().isEmpty() ? "No leader" : v.leader();
            int lw = font.width(leader);
            g.drawString(font, leader, px + pw - lw - 56, y + 4,
                    v.leader().isEmpty() ? BookScreenColors.LIGHT : BookScreenColors.DARK, false);
            g.fill(px, y + 20, px + pw, y + 21, BookScreenColors.HIGHLIGHT);
            y += 28;
        }
    }

    private void drawDiplomacy(GuiGraphics g, int px, int py, int pw, int maxY) {
        g.drawString(font, "Kingdom relations", px, py, BookScreenColors.MID, false);
        g.fill(px, py + 10, px + pw, py + 11, BookScreenColors.BORDER);
        int y = py + 16;
        for (KingdomEntry k : kingdoms) {
            if (y + 26 > maxY) break;
            g.drawString(font, k.name(), px, y + 4, BookScreenColors.DARK, false);
            int[] rc = relColors(k.relation());
            String rl = formatRelation(k.relation());
            int rw = font.width(rl) + 8;
            g.fill(px + 120, y + 2, px + 120 + rw, y + 14, rc[0]);
            g.drawString(font, rl, px + 124, y + 4, rc[1], false);
            g.fill(px, y + 24, px + pw, y + 25, BookScreenColors.HIGHLIGHT);
            y += 32;
        }
    }

    private void drawDecrees(GuiGraphics g, int px, int py, int pw, int maxY) {
        g.drawString(font, "Issue a royal decree", px, py, BookScreenColors.MID, false);
        g.fill(px, py + 10, px + pw, py + 11, BookScreenColors.BORDER);
    }

    private void drawKingdomMap(GuiGraphics g, int px, int py, int pw, int maxY,
                                int mx, int my) {
        if (mapPanel == null) {
            g.drawString(font, "No kingdom data available.", px, py,
                    BookScreenColors.LIGHT, false);
            return;
        }
        mapPanel.render(g, px, py, pw, maxY - py, mx, my);
    }

    private void drawRoyalBuilds(GuiGraphics g, int px, int py, int pw, int maxY) {
        String[][] builds = {
                {"Royal Keep",      "Seat of power. Unlocks advanced governance.",
                        "Town tier required"},
                {"Royal Barracks",  "Trains elite kingdom guards.",
                        "Village tier required"},
                {"Royal Market",    "Boosts caravan frequency and trade.",
                        "Town tier required"},
                {"Grand Cathedral", "Monument to culture. Locked.",
                        "City tier required"}
        };
        int y = py;
        for (String[] build : builds) {
            if (y + 42 > maxY) break;
            boolean locked = build[2].contains("City");
            g.fill(px, y, px + pw, y + 38,
                    locked ? BookScreenColors.HIGHLIGHT : BookScreenColors.PARCHMENT);
            g.renderOutline(px, y, pw, 38, BookScreenColors.BORDER);
            g.drawString(font, build[0], px + 6, y + 5,
                    locked ? BookScreenColors.LIGHT : BookScreenColors.DARK, false);
            g.drawString(font, build[1], px + 6, y + 17, BookScreenColors.MID, false);
            g.drawString(font, build[2], px + 6, y + 27, BookScreenColors.LIGHT, false);
            y += 44;
        }
    }

    private void changePage(int delta) {
        if (currentSection() == SectionType.HISTORY && !historyPages.isEmpty()) {
            NavEntry entry = navEntries.get(page);
            int hi = Math.max(0, Math.min(historyPages.size() - 1,
                    entry.historyPageIndex() + delta));
            navEntries.set(page, new NavEntry(entry.label(), entry.section(), hi));
            buildWidgets();
            return;
        }
        page = Math.max(0, Math.min(navEntries.size() - 1, page + delta));
        buildWidgets();
    }

    private SectionType currentSection() {
        return page < navEntries.size()
                ? navEntries.get(page).section() : SectionType.FRONTISPIECE;
    }

    private int historySubPage() {
        return page < navEntries.size() ? navEntries.get(page).historyPageIndex() : 0;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (consumed) return super.mouseClicked(event, consumed);
        double mx = event.x(), my = event.y();

        if (sidebar.mouseClicked(mx, my)) return true;

        if (currentSection() == SectionType.KINGDOM_MAP && mapPanel != null) {
            int px = bookX + SIDEBAR_W + PAGE_PAD, py = bookY + 36;
            int pw = BOOK_W - SIDEBAR_W - PAGE_PAD * 2, mapH = BOOK_H - 70;
            if (mx >= px && mx < px + pw && my >= py && my < py + mapH) {
                if (mapPanel.mouseClicked((int) mx, (int) my)) return true;
            }
        }

        return super.mouseClicked(event, consumed);
    }

    private void sendAction(KingdomActionPacket.ActionType type,
                            String strParam, int intParam) {
        ClientPacketDistributor.sendToServer(
                new KingdomActionPacket(type, kingdomId, strParam, intParam));
    }

    private String formatLawName(String name) {
        return name.charAt(0) + name.substring(1).toLowerCase().replace("_", " ");
    }

    private String formatRelation(DiplomaticRelation rel) {
        return rel.name().charAt(0)
                + rel.name().substring(1).toLowerCase().replace("_", " ");
    }

    private int[] relColors(DiplomaticRelation rel) {
        return switch (rel) {
            case ALLIANCE -> new int[]{ BookScreenColors.GREEN_BG, BookScreenColors.GREEN_TXT };
            case TRADE    -> new int[]{ 0xFFD0E8FF, 0xFF1A4A8B };
            case NEUTRAL  -> new int[]{ BookScreenColors.HIGHLIGHT, BookScreenColors.MID };
            case COLD_WAR -> new int[]{ 0xFFFFF0C0, 0xFF8B6B00 };
            case WAR      -> new int[]{ BookScreenColors.RED_BG, BookScreenColors.RED_TXT };
        };
    }

    private List<String> wrapText(String text, int maxW) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = cur.isEmpty() ? word : cur + " " + word;
            if (font.width(test) > maxW) {
                if (!cur.isEmpty()) {
                    lines.add(cur.toString());
                    cur = new StringBuilder(word);
                } else {
                    lines.add(word);
                }
            } else {
                cur = new StringBuilder(test);
            }
        }
        if (!cur.isEmpty()) lines.add(cur.toString());
        return lines;
    }
}
