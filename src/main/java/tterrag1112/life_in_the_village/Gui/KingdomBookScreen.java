package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.KingdomMapPanel;
import tterrag1112.life_in_the_village.Kingdom.*;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Networking.KingdomActionPacket;
import tterrag1112.life_in_the_village.Networking.RequestKingdomMapSyncPacket;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

public class KingdomBookScreen extends Screen {

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private static final int BOOK_W    = 400;
    private static final int BOOK_H    = 320;
    private static final int SIDEBAR_W = 140;
    private static final int PAGE_PAD  = 16;
    private static final int LINES_PER_PAGE = 18;

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

    // -------------------------------------------------------------------------
    // Page types
    // -------------------------------------------------------------------------

    private enum SectionType {
        FRONTISPIECE, STATUS, HISTORY,
        LAWS, ECONOMY, APPOINTMENTS,
        DIPLOMACY, DECREES, ROYAL_BUILDS,
        KINGDOM_MAP
    }

    private record NavEntry(
            String label, SectionType section,
            int historyPageIndex) {}

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final UUID kingdomId;
    private int bookX, bookY;
    private int page = 0;

    // Kingdom data
    private String kingdomName   = "Loading...";
    private String rulerName     = "Unknown";
    private int villageCount     = 0;
    private long treasury        = 0;
    private int activeLawCount   = 0;
    private int taxRate          = 10;
    private long upkeep          = 32;
    private long foundingTick    = 0L;
    private final Set<KingdomLaw> activeLaws = new HashSet<>();
    private final List<KingdomEntry> kingdoms = new ArrayList<>();
    private final List<VillageEntry> villages = new ArrayList<>();

    // History
    private final List<HistoryTextGenerator.HistoryPage>
            historyPages = new ArrayList<>();

    // Navigation
    private final List<NavEntry> navEntries = new ArrayList<>();

    // Widgets
    private EditBox decreeBox;

    record KingdomEntry(UUID id, String name,
                        int villageCount,
                        DiplomaticRelation relation) {}
    record VillageEntry(UUID id, String name,
                        String tier, String leader) {}

    private KingdomMapPanel mapPanel;


    // -------------------------------------------------------------------------
    // Constructor / init
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Data loading
    // -------------------------------------------------------------------------

    public void refreshData() {
        Kingdom k = Kingdom.ClientKingdomCache
                .getById(kingdomId).orElse(null);

        if (k == null) {
            System.out.println(
                    "KingdomBookScreen.refreshData: "
                            + "kingdom not in cache");
            buildNavEntries();
            return;
        }

        kingdomName    = k.getName();
        rulerName      = k.getRulerPlayerId()
                .map(id -> "Player").orElse("Unknown");
        villageCount   = k.getVillageIds().size();
        treasury       = k.getTreasuryBronze();
        taxRate        = (int)(k.getIncomeTaxRate() * 100);
        upkeep         = k.getFlatUpkeepBronze();
        activeLaws.clear();
        activeLaws.addAll(k.getActiveLaws());
        activeLawCount = activeLaws.size();

        foundingTick = k.getHistory().getOrigin()
                .map(o -> o.foundingTick()).orElse(0L);

        kingdoms.clear();
        k.getAllRelations().forEach((otherId, rel) ->
                Kingdom.ClientKingdomCache.getById(otherId)
                        .ifPresent(other -> kingdoms.add(
                                new KingdomEntry(
                                        other.getId(),
                                        other.getName(),
                                        other.getVillageIds()
                                                .size(),
                                        rel))));

        villages.clear();
        // Villages populated from building cache if needed

        // Build history pages
        long currentTick = net.minecraft.client.Minecraft
                .getInstance().level != null
                ? net.minecraft.client.Minecraft
                .getInstance().level.getGameTime()
                : 0L;

        historyPages.clear();
        historyPages.addAll(
                HistoryTextGenerator.buildHistoryPages(
                        k.getHistory(),
                        kingdomName,
                        rulerName,
                        currentTick,
                        foundingTick));

        System.out.println(
                "KingdomBookScreen.refreshData: "
                        + historyPages.size()
                        + " history pages built from "
                        + k.getHistory().getEvents().size()
                        + " events");

        if (mapPanel == null) mapPanel = new KingdomMapPanel(kingdomId);
        mapPanel.refresh();
        ClientPacketDistributor.sendToServer(
                new RequestKingdomMapSyncPacket(kingdomId));

        buildNavEntries();
    }

    private void buildNavEntries() {
        navEntries.clear();

        // Fixed pages
        navEntries.add(new NavEntry(
                "Frontispiece", SectionType.FRONTISPIECE, -1));
        navEntries.add(new NavEntry(
                "Status", SectionType.STATUS, -1));

        // Single history entry — index 0 is the start
        navEntries.add(new NavEntry(
                "History", SectionType.HISTORY, 0));

        // Governance
        navEntries.add(new NavEntry(
                "Laws", SectionType.LAWS, -1));
        navEntries.add(new NavEntry(
                "Economy", SectionType.ECONOMY, -1));
        navEntries.add(new NavEntry(
                "Appointments", SectionType.APPOINTMENTS,
                -1));

        // Foreign
        navEntries.add(new NavEntry(
                "Diplomacy", SectionType.DIPLOMACY, -1));
        navEntries.add(new NavEntry(
                "Decrees", SectionType.DECREES, -1));
        navEntries.add(new NavEntry("Kingdom Map", SectionType.KINGDOM_MAP, -1));

        // Build
        navEntries.add(new NavEntry(
                "Royal Builds", SectionType.ROYAL_BUILDS,
                -1));

        page = Math.min(page, navEntries.size() - 1);
    }

    // -------------------------------------------------------------------------
    // Widgets
    // -------------------------------------------------------------------------

    private void buildWidgets() {
        clearWidgets();
        decreeBox = null;

        // Prev / Next
        addRenderableWidget(Button.builder(
                        Component.literal("←"),
                        b -> changePage(-1))
                .pos(bookX + SIDEBAR_W + 8,
                        bookY + BOOK_H - 28)
                .size(32, 18).build());

        addRenderableWidget(Button.builder(
                        Component.literal("→"),
                        b -> changePage(1))
                .pos(bookX + BOOK_W - 40,
                        bookY + BOOK_H - 28)
                .size(32, 18).build());

        SectionType section = currentSection();
        int px = bookX + SIDEBAR_W + PAGE_PAD;
        int py = bookY + 36;
        int pw = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;

        switch (section) {
            case LAWS        -> buildLawWidgets(px, py, pw);
            case ECONOMY     -> buildEconomyWidgets(
                    px, py, pw);
            case DIPLOMACY   -> buildDiplomacyWidgets(
                    px, py, pw);
            case DECREES     -> buildDecreeWidgets(
                    px, py, pw);
            default          -> {}
        }
    }

    private void buildLawWidgets(int px, int py, int pw) {
        KingdomLaw[] laws = KingdomLaw.values();
        for (int i = 0; i < laws.length; i++) {
            KingdomLaw law = laws[i];
            boolean active = activeLaws.contains(law);
            int by = py + i * 28;
            if (by + 20 > bookY + BOOK_H - 32) break;

            // active = law is ON → button should say "Repeal"
            // inactive = law is OFF → button should say "Enact"
            // The TOGGLE_LAW handler flips whatever the
            // current state is, so the label just needs
            // to match what clicking WILL do
            addRenderableWidget(Button.builder(
                            Component.literal(
                                    active ? "Repeal" : "Enact"),
                            b -> sendAction(
                                    KingdomActionPacket.ActionType
                                            .TOGGLE_LAW,
                                    law.name(), 0))
                    .pos(px + pw - 52, by)
                    .size(50, 16).build());
        }
    }

    private void buildEconomyWidgets(int px, int py,
                                     int pw) {
        addRenderableWidget(Button.builder(
                        Component.literal("-"),
                        b -> {
                            taxRate = Math.max(0, taxRate - 5);
                            sendAction(KingdomActionPacket
                                            .ActionType.SET_TAX_RATE,
                                    "", taxRate);
                        })
                .pos(px + pw / 2 - 40, py + 28)
                .size(24, 16).build());

        addRenderableWidget(Button.builder(
                        Component.literal("+"),
                        b -> {
                            taxRate = Math.min(50, taxRate + 5);
                            sendAction(KingdomActionPacket
                                            .ActionType.SET_TAX_RATE,
                                    "", taxRate);
                        })
                .pos(px + pw / 2 + 16, py + 28)
                .size(24, 16).build());

        addRenderableWidget(Button.builder(
                        Component.literal("-"),
                        b -> {
                            upkeep = Math.max(0, upkeep - 8);
                            sendAction(KingdomActionPacket
                                            .ActionType.SET_UPKEEP,
                                    "", (int) upkeep);
                        })
                .pos(px + pw / 2 - 40, py + 72)
                .size(24, 16).build());

        addRenderableWidget(Button.builder(
                        Component.literal("+"),
                        b -> {
                            upkeep = Math.min(256, upkeep + 8);
                            sendAction(KingdomActionPacket
                                            .ActionType.SET_UPKEEP,
                                    "", (int) upkeep);
                        })
                .pos(px + pw / 2 + 16, py + 72)
                .size(24, 16).build());
    }

    private void buildDiplomacyWidgets(int px, int py,
                                       int pw) {
        DiplomaticRelation[] rels =
                DiplomaticRelation.values();
        for (int i = 0; i < kingdoms.size(); i++) {
            KingdomEntry k = kingdoms.get(i);
            int by = py + 16 + i * 32;
            if (by + 20 > bookY + BOOK_H - 32) break;
            int fi = i;
            addRenderableWidget(Button.builder(
                            Component.literal("Change"),
                            b -> {
                                DiplomaticRelation cur =
                                        kingdoms.get(fi).relation();
                                DiplomaticRelation next =
                                        rels[(cur.ordinal() + 1)
                                                % rels.length];
                                sendAction(KingdomActionPacket
                                                .ActionType.SET_RELATION,
                                        k.id() + ":" + next.name(),
                                        0);
                            })
                    .pos(px + pw - 54, by + 6)
                    .size(52, 16).build());
        }
    }

    private void buildDecreeWidgets(int px, int py,
                                    int pw) {
        decreeBox = new EditBox(font,
                px, py + 20, pw, 60,
                Component.literal("Write decree..."));
        decreeBox.setMaxLength(256);
        addRenderableWidget(decreeBox);

        addRenderableWidget(Button.builder(
                        Component.literal("Issue Decree"),
                        b -> {
                            if (decreeBox != null
                                    && !decreeBox.getValue()
                                    .isEmpty()) {
                                sendAction(KingdomActionPacket
                                                .ActionType.ISSUE_DECREE,
                                        decreeBox.getValue(), 0);
                                decreeBox.setValue("");
                            }
                        })
                .pos(px, py + 88)
                .size(80, 18).build());
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mx, int my,
                       float pt) {

        drawBook(g);
        drawSidebar(g);
        drawPageContent(g);
        super.render(g, mx, my, pt);
    }

    private void drawBook(GuiGraphics g) {
        // Shadow
        g.fill(bookX + 3, bookY + 3,
                bookX + BOOK_W + 3,
                bookY + BOOK_H + 3, 0x44000000);

        // Background
        g.fill(bookX, bookY,
                bookX + BOOK_W, bookY + BOOK_H,
                COL_PARCHMENT);

        // Borders
        g.renderOutline(bookX, bookY,
                BOOK_W, BOOK_H, COL_BORDER);
        g.renderOutline(bookX + 2, bookY + 2,
                BOOK_W - 4, BOOK_H - 4, COL_HIGHLIGHT);

        // Header separator
        g.fill(bookX + SIDEBAR_W, bookY + 28,
                bookX + BOOK_W, bookY + 29, COL_BORDER);

        // Footer separator
        g.fill(bookX + SIDEBAR_W,
                bookY + BOOK_H - 30,
                bookX + BOOK_W,
                bookY + BOOK_H - 29, COL_BORDER);

        // Page title
        String title = page < navEntries.size()
                ? navEntries.get(page).label() : "";

        // For history, show the sub-page title instead
        if (currentSection() == SectionType.HISTORY) {
            int hi = historySubPage();
            if (hi >= 0 && hi < historyPages.size()) {
                title = historyPages.get(hi).title();
            }
        }

        g.drawString(font, title,
                bookX + SIDEBAR_W + PAGE_PAD,
                bookY + 10, COL_DARK, false);

        // Page counter — for history show sub-page
        String counter;
        if (currentSection() == SectionType.HISTORY
                && !historyPages.isEmpty()) {
            counter = (historySubPage() + 1) + " / "
                    + historyPages.size();
        } else {
            counter = (page + 1) + " / "
                    + navEntries.size();
        }
        int cw = font.width(counter);
        g.drawString(font, counter,
                bookX + BOOK_W - cw - PAGE_PAD,
                bookY + 10, COL_LIGHT, false);

        // Corner flourishes
        drawFlourishCorner(g, bookX + 4, bookY + 4);
        drawFlourishCorner(g,
                bookX + BOOK_W - 12, bookY + 4);
        drawFlourishCorner(g,
                bookX + 4, bookY + BOOK_H - 12);
        drawFlourishCorner(g,
                bookX + BOOK_W - 12,
                bookY + BOOK_H - 12);
    }

    private void drawFlourishCorner(GuiGraphics g,
                                    int x, int y) {
        g.fill(x, y, x + 8, y + 1, COL_BORDER);
        g.fill(x, y, x + 1, y + 8, COL_BORDER);
    }

    private void drawSidebar(GuiGraphics g) {
        g.fill(bookX, bookY,
                bookX + SIDEBAR_W, bookY + BOOK_H,
                COL_SIDEBAR);
        g.fill(bookX + SIDEBAR_W - 1, bookY,
                bookX + SIDEBAR_W, bookY + BOOK_H,
                COL_BORDER);

        // Kingdom name
        g.drawString(font,
                "\u265B " + kingdomName,
                bookX + 8, bookY + 10,
                COL_DARK, false);
        g.fill(bookX + 4, bookY + 22,
                bookX + SIDEBAR_W - 4, bookY + 23,
                COL_BORDER);

        // Nav entries
        String lastSection = "";
        int ny = bookY + 28;

        for (int i = 0; i < navEntries.size(); i++) {
            NavEntry entry = navEntries.get(i);
            String sectionLabel = sectionGroupLabel(
                    entry.section());

            if (!sectionLabel.equals(lastSection)) {
                if (ny + 12 > bookY + BOOK_H - 10) break;
                g.drawString(font,
                        sectionLabel.toUpperCase(),
                        bookX + 8, ny,
                        COL_LIGHT, false);
                ny += 12;
                lastSection = sectionLabel;
            }

            if (ny + 10 > bookY + BOOK_H - 10) break;

            boolean active = (i == page);
            if (active) {
                g.fill(bookX + 2, ny - 1,
                        bookX + SIDEBAR_W - 1, ny + 9,
                        COL_HIGHLIGHT);
                g.fill(bookX + 2, ny - 1,
                        bookX + 4, ny + 9, COL_GOLD);
            }

            g.drawString(font,
                    (active ? "> " : "  ")
                            + entry.label(),
                    bookX + 6, ny,
                    active ? COL_DARK : COL_MID, false);

            ny += 18;
        }
    }

    private String sectionGroupLabel(SectionType s) {
        return switch (s) {
            case FRONTISPIECE,
                 STATUS           -> "Overview";
            case HISTORY          -> "History";
            case LAWS, ECONOMY,
                 APPOINTMENTS     -> "Governance";
            case KINGDOM_MAP -> "Kingdom Map";
            case DIPLOMACY,
                 DECREES          -> "Foreign";
            case ROYAL_BUILDS     -> "Construction";
        };
    }

    private void drawPageContent(GuiGraphics g) {
        int px   = bookX + SIDEBAR_W + PAGE_PAD;
        int py   = bookY + 36;
        int pw   = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;
        int maxY = bookY + BOOK_H - 34;

        switch (currentSection()) {
            case FRONTISPIECE  -> drawFrontispiece(
                    g, px, py, pw);
            case STATUS        -> drawStatus(
                    g, px, py, pw, maxY);
            case HISTORY       -> drawHistory(
                    g, px, py, pw, maxY);
            case LAWS          -> drawLaws(
                    g, px, py, pw, maxY);
            case ECONOMY       -> drawEconomy(
                    g, px, py, pw, maxY);
            case APPOINTMENTS  -> drawAppointments(
                    g, px, py, pw, maxY);
            case DIPLOMACY     -> drawDiplomacy(
                    g, px, py, pw, maxY);
            case DECREES       -> drawDecrees(
                    g, px, py, pw, maxY);
            case KINGDOM_MAP -> drawKingdomMap(g, px, py, pw, maxY);

            case ROYAL_BUILDS  -> drawRoyalBuilds(
                    g, px, py, pw, maxY);
        }
    }

    // -------------------------------------------------------------------------
    // Page renderers
    // -------------------------------------------------------------------------

    private void drawFrontispiece(GuiGraphics g,
                                  int px, int py,
                                  int pw) {
        String crown = "\u265B";
        int cw = font.width(crown);
        g.drawString(font, crown,
                px + pw / 2 - cw / 2,
                py + 20, COL_GOLD, false);

        int nw = font.width(kingdomName);
        g.drawString(font, kingdomName,
                px + pw / 2 - nw / 2,
                py + 50, COL_DARK, false);

        g.fill(px + pw / 4, py + 62,
                px + pw * 3 / 4, py + 63, COL_BORDER);

        String tag = "By right of deed, "
                + "by will of the realm";
        int tw = font.width(tag);
        g.drawString(font, tag,
                px + pw / 2 - tw / 2,
                py + 70, COL_MID, false);

        String ruler = "Ruler: " + rulerName;
        int rw = font.width(ruler);
        g.drawString(font, ruler,
                px + pw / 2 - rw / 2,
                py + 110, COL_LIGHT, false);
    }

    private void drawStatus(GuiGraphics g,
                            int px, int py, int pw,
                            int maxY) {
        drawStatBox(g, px, py, 72, 44,
                "Villages",
                String.valueOf(villageCount));
        drawStatBox(g, px + 80, py, 72, 44,
                "Treasury", treasury + "b");
        drawStatBox(g, px + 160, py, 72, 44,
                "Laws",
                String.valueOf(activeLawCount));

        int y = py + 54;
        g.drawString(font, "Villages",
                px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11,
                COL_BORDER);
        y += 14;

        for (VillageEntry v : villages) {
            if (y + 10 > maxY) break;
            g.drawString(font,
                    v.name() + " \u00b7 " + v.tier(),
                    px, y, COL_DARK, false);
            int lw = font.width(v.leader());
            g.drawString(font, v.leader(),
                    px + pw - lw, y, COL_MID, false);
            y += 14;
        }
    }

    private void drawStatBox(GuiGraphics g,
                             int x, int y, int w, int h,
                             String label, String value) {
        g.fill(x, y, x + w, y + h, COL_HIGHLIGHT);
        g.renderOutline(x, y, w, h, COL_BORDER);
        int lw = font.width(label);
        g.drawString(font, label,
                x + w / 2 - lw / 2,
                y + 6, COL_MID, false);
        int vw = font.width(value);
        g.drawString(font, value,
                x + w / 2 - vw / 2,
                y + 22, COL_DARK, false);
    }

    /**
     * History renders a single HistoryPage at a time.
     * ← → navigate within history sub-pages when on
     * the History nav entry.
     */
    private void drawHistory(GuiGraphics g,
                             int px, int py,
                             int pw, int maxY) {
        if (historyPages.isEmpty()) {
            g.drawString(font,
                    "No history has been recorded yet.",
                    px, py, COL_MID, false);
            return;
        }

        int hi = historySubPage();
        if (hi < 0 || hi >= historyPages.size()) return;

        HistoryTextGenerator.HistoryPage hp =
                historyPages.get(hi);

        int y = py;

        // Type-specific header decoration
        switch (hp.type()) {
            case ORIGIN -> {
                g.drawString(font, "The Founding",
                        px, y, COL_GOLD, false);
                g.fill(px, y + 10, px + pw, y + 11,
                        COL_BORDER);
                y += 18;
            }
            case EVENT -> {
                g.fill(px, y + 4, px + pw, y + 5,
                        COL_HIGHLIGHT);
                y += 12;
            }
            case FILLER -> {
                g.fill(px + pw / 4, y + 4,
                        px + pw * 3 / 4, y + 5,
                        COL_BORDER);
                y += 14;
            }
        }

        // Render each line from the pre-packed page text
        // Use pixel-accurate wrapping here for final display
        for (String paragraph :
                hp.text().split("\n", -1)) {
            if (paragraph.isEmpty()) {
                y += 6; // blank line gap
                continue;
            }
            // Pixel-wrap each paragraph line
            List<String> wrapped = wrapText(
                    paragraph, pw);
            for (String line : wrapped) {
                if (y + 10 > maxY - 12) break;
                int color = hp.type()
                        == HistoryTextGenerator
                        .HistoryPageType.FILLER
                        ? COL_MID : COL_DARK;
                g.drawString(font, line, px, y,
                        color, false);
                y += 12;
            }
        }

        // Bottom filler decoration
        if (hp.type()
                == HistoryTextGenerator
                .HistoryPageType.FILLER) {
            g.fill(px + pw / 4, y + 6,
                    px + pw * 3 / 4, y + 7,
                    COL_BORDER);
        }

        // Navigation hint
        if (historyPages.size() > 1) {
            String hint = "← → to browse history";
            int hw = font.width(hint);
            g.drawString(font, hint,
                    px + pw / 2 - hw / 2,
                    maxY - 10, COL_LIGHT, false);
        }
    }

    private void drawLaws(GuiGraphics g,
                          int px, int py, int pw,
                          int maxY) {
        KingdomLaw[] laws = KingdomLaw.values();
        int y = py;
        for (int i = 0; i < laws.length; i++) {
            if (y + 22 > maxY) break;
            KingdomLaw law = laws[i];
            boolean active = activeLaws.contains(law);
            if (active) {
                g.fill(px - 2, y - 1,
                        px + pw + 2, y + 21,
                        COL_GREEN_BG);
            }
            g.drawString(font,
                    formatLawName(law.name()),
                    px, y + 4,
                    active ? COL_GREEN_TXT : COL_DARK,
                    false);
            String status = active ? "[On]" : "[Off]";
            g.drawString(font, status,
                    px + 120, y + 4,
                    active ? COL_GREEN_TXT : COL_LIGHT,
                    false);
            g.fill(px, y + 21, px + pw, y + 22,
                    COL_HIGHLIGHT);
            y += 28;
        }
    }

    private void drawEconomy(GuiGraphics g,
                             int px, int py, int pw,
                             int maxY) {
        g.drawString(font, "Income tax rate",
                px, py, COL_DARK, false);
        int vw1 = font.width(taxRate + "%");
        g.drawString(font, taxRate + "%",
                px + pw / 2 - vw1 / 2,
                py + 16, COL_DARK, false);

        g.fill(px, py + 36, px + pw, py + 37,
                COL_HIGHLIGHT);

        g.drawString(font, "Flat upkeep per village",
                px, py + 44, COL_DARK, false);
        int vw2 = font.width(upkeep + " bronze");
        g.drawString(font, upkeep + " bronze",
                px + pw / 2 - vw2 / 2,
                py + 60, COL_DARK, false);

        g.fill(px, py + 80, px + pw, py + 81,
                COL_HIGHLIGHT);

        g.drawString(font, "Treasury",
                px, py + 90, COL_MID, false);
        drawStatBox(g, px, py + 102, 72, 40,
                "Balance", treasury + "b");
        drawStatBox(g, px + 80, py + 102, 80, 40,
                "Est. daily",
                "~" + (villageCount * upkeep
                        + 200L * taxRate / 100) + "b");
    }

    private void drawAppointments(GuiGraphics g,
                                  int px, int py,
                                  int pw, int maxY) {
        g.drawString(font, "Village leaders",
                px, py, COL_MID, false);
        g.fill(px, py + 10, px + pw, py + 11,
                COL_BORDER);
        int y = py + 16;
        for (VillageEntry v : villages) {
            if (y + 22 > maxY) break;
            g.drawString(font, v.name(),
                    px, y + 4, COL_DARK, false);
            g.drawString(font, v.tier(),
                    px + 80, y + 4, COL_MID, false);
            String leader = v.leader().isEmpty()
                    ? "No leader" : v.leader();
            int lw = font.width(leader);
            g.drawString(font, leader,
                    px + pw - lw - 56, y + 4,
                    v.leader().isEmpty()
                            ? COL_LIGHT : COL_DARK,
                    false);
            g.fill(px, y + 20, px + pw, y + 21,
                    COL_HIGHLIGHT);
            y += 28;
        }
    }

    private void drawDiplomacy(GuiGraphics g,
                               int px, int py, int pw,
                               int maxY) {
        g.drawString(font, "Kingdom relations",
                px, py, COL_MID, false);
        g.fill(px, py + 10, px + pw, py + 11,
                COL_BORDER);
        int y = py + 16;
        for (KingdomEntry k : kingdoms) {
            if (y + 26 > maxY) break;
            g.drawString(font, k.name(),
                    px, y + 4, COL_DARK, false);
            int[] rc = relColors(k.relation());
            String rl = formatRelation(k.relation());
            int rw = font.width(rl) + 8;
            g.fill(px + 120, y + 2,
                    px + 120 + rw, y + 14, rc[0]);
            g.drawString(font, rl,
                    px + 124, y + 4, rc[1], false);
            g.fill(px, y + 24, px + pw, y + 25,
                    COL_HIGHLIGHT);
            y += 32;
        }
    }

    private void drawDecrees(GuiGraphics g,
                             int px, int py, int pw,
                             int maxY) {
        g.drawString(font, "Issue a royal decree",
                px, py, COL_MID, false);
        g.fill(px, py + 10, px + pw, py + 11,
                COL_BORDER);
        // EditBox and button rendered by widget system
    }

    private void drawRoyalBuilds(GuiGraphics g,
                                 int px, int py, int pw,
                                 int maxY) {
        String[][] builds = {
                {"Royal Keep",
                        "Seat of power. Unlocks advanced "
                                + "governance.",
                        "Town tier required"},
                {"Royal Barracks",
                        "Trains elite kingdom guards.",
                        "Village tier required"},
                {"Royal Market",
                        "Boosts caravan frequency and trade.",
                        "Town tier required"},
                {"Grand Cathedral",
                        "Monument to culture. Locked.",
                        "City tier required"}
        };
        int y = py;
        for (String[] build : builds) {
            if (y + 42 > maxY) break;
            boolean locked =
                    build[2].contains("City");
            g.fill(px, y, px + pw, y + 38,
                    locked
                            ? COL_HIGHLIGHT
                            : COL_PARCHMENT);
            g.renderOutline(px, y, pw, 38, COL_BORDER);
            g.drawString(font, build[0],
                    px + 6, y + 5,
                    locked ? COL_LIGHT : COL_DARK,
                    false);
            g.drawString(font, build[1],
                    px + 6, y + 17, COL_MID, false);
            g.drawString(font, build[2],
                    px + 6, y + 27, COL_LIGHT, false);
            y += 44;
        }
    }

    // -------------------------------------------------------------------------
    // Navigation
    // -------------------------------------------------------------------------

    /**
     * History section has internal sub-pages.
     * ← → navigate those when on History.
     * Otherwise they navigate nav entries.
     */
    private void changePage(int delta) {
        if (currentSection() == SectionType.HISTORY
                && !historyPages.isEmpty()) {
            // Navigate history sub-pages
            NavEntry entry = navEntries.get(page);
            int hi = entry.historyPageIndex() + delta;
            hi = Math.max(0, Math.min(
                    historyPages.size() - 1, hi));
            navEntries.set(page, new NavEntry(
                    entry.label(), entry.section(), hi));
            buildWidgets();
            return;
        }

        // Navigate nav entries
        page = Math.max(0, Math.min(
                navEntries.size() - 1, page + delta));
        buildWidgets();
    }

    private SectionType currentSection() {
        if (page >= navEntries.size())
            return SectionType.FRONTISPIECE;
        return navEntries.get(page).section();
    }

    private int historySubPage() {
        if (page >= navEntries.size()) return 0;
        return navEntries.get(page).historyPageIndex();
    }

    // -------------------------------------------------------------------------
    // Mouse
    // -------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event,
                                boolean consumed) {
        if (consumed)
            return super.mouseClicked(event, consumed);

        double mx = event.x();
        double my = event.y();

        String lastSection = "";
        int ny = bookY + 28;

        for (int i = 0; i < navEntries.size(); i++) {
            NavEntry entry = navEntries.get(i);
            String sectionLabel = sectionGroupLabel(
                    entry.section());

            if (!sectionLabel.equals(lastSection)) {
                ny += 12;
                lastSection = sectionLabel;
            }

            if (ny + 10 > bookY + BOOK_H - 10) break;

            if (mx >= bookX + 2
                    && mx <= bookX + SIDEBAR_W - 1
                    && my >= ny - 1
                    && my <= ny + 9) {
                page = i;
                buildWidgets();
                return true;
            }
            ny += 18;
        }

        return super.mouseClicked(event, consumed);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void sendAction(
            KingdomActionPacket.ActionType type,
            String strParam, int intParam) {
        ClientPacketDistributor.sendToServer(
                new KingdomActionPacket(
                        type, kingdomId,
                        strParam, intParam));
    }

    private String formatLawName(String name) {
        return name.charAt(0)
                + name.substring(1).toLowerCase()
                .replace("_", " ");
    }

    private String formatRelation(
            DiplomaticRelation rel) {
        return rel.name().charAt(0)
                + rel.name().substring(1).toLowerCase()
                .replace("_", " ");
    }

    private int[] relColors(DiplomaticRelation rel) {
        return switch (rel) {
            case ALLIANCE -> new int[]{
                    COL_GREEN_BG, COL_GREEN_TXT};
            case TRADE    -> new int[]{
                    0xFFD0E8FF, 0xFF1A4A8B};
            case NEUTRAL  -> new int[]{
                    COL_HIGHLIGHT, COL_MID};
            case COLD_WAR -> new int[]{
                    0xFFFFF0C0, 0xFF8B6B00};
            case WAR      -> new int[]{
                    COL_RED_BG, COL_RED_TXT};
        };
    }

    private List<String> wrapText(String text, int maxW) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        String[] words = text.split(" ");
        StringBuilder cur = new StringBuilder();
        for (String word : words) {
            String test = cur.isEmpty()
                    ? word : cur + " " + word;
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

    private String truncate(String s, int max) {
        return s.length() <= max ? s
                : s.substring(0, max - 2) + "..";
    }

    public void refresh() {
        refreshData();
        rebuildWidgets();
    }
    private void drawKingdomMap(GuiGraphics g, int px, int py, int pw, int maxY) {
        if (mapPanel == null) {
            g.drawString(font, "No kingdom data available.",
                    px, py, COL_LIGHT, false);
            return;
        }
        int mapH = maxY - py;
        // Mouse coords aren't plumbed through this method — pass the
        // last-known values from render() if/when tooltips become useful
        // on the book page. For now, pass -1,-1 to disable hover tooltip.
        mapPanel.render(g, px, py, pw, mapH, -1, -1);
    }

    public void onMapDataSynced() {
        if (mapPanel != null) mapPanel.refresh();
    }
}