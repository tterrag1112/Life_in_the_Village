package tterrag1112.life_in_the_village.Gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Kingdom.DiplomaticRelation;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomLaw;
import tterrag1112.life_in_the_village.Networking.KingdomActionPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.*;

public class KingdomBookScreen extends Screen {

    // Layout constants
    private static final int BOOK_W    = 400;
    private static final int BOOK_H    = 320;
    private static final int SIDEBAR_W = 140;
    private static final int PAGE_PAD  = 16;

    // Colors
    private static final int COL_PARCHMENT  = 0xFFF5F0E0;
    private static final int COL_SIDEBAR    = 0xFFEDE8D5;
    private static final int COL_BORDER     = 0xFFB8A878;
    private static final int COL_DARK       = 0xFF3B2E1A;
    private static final int COL_MID        = 0xFF7A6040;
    private static final int COL_LIGHT      = 0xFFA89060;
    private static final int COL_HIGHLIGHT  = 0xFFD4C48A;
    private static final int COL_GREEN_BG   = 0xFFD4EAC8;
    private static final int COL_GREEN_TXT  = 0xFF2D6B1A;
    private static final int COL_RED_BG     = 0xFFEAC8C8;
    private static final int COL_RED_TXT    = 0xFF8B1A1A;
    private static final int COL_GOLD       = 0xFFB8860B;

    private final UUID kingdomId;
    private int page = 0;
    private int bookX, bookY;

    // Pages
    private static final String[] PAGE_TITLES = {
            "Frontispiece",
            "Kingdom Status",
            "History",
            "Laws",
            "Economy",
            "Appointments",
            "Diplomacy",
            "Decrees",
            "Royal Builds"
    };

    private static final String[] NAV_LABELS = {
            "Frontispiece",
            "Status",
            "History",
            "Laws",
            "Economy",
            "Appointments",
            "Diplomacy",
            "Decrees",
            "Royal Builds"
    };

    // Widgets
    private final List<Button> pageButtons = new ArrayList<>();
    private EditBox decreeBox;

    // Live kingdom data (refreshed on open and after each action)
    private String kingdomName    = "Loading...";
    private String rulerName      = "";
    private int villageCount      = 0;
    private long treasury         = 0;
    private int activeLawCount    = 0;
    private int taxRate           = 10;
    private long upkeep           = 32;
    private final Set<KingdomLaw> activeLaws = new HashSet<>();
    private final List<KingdomEntry> kingdoms = new ArrayList<>();
    private final List<VillageEntry> villages = new ArrayList<>();

    record KingdomEntry(UUID id, String name, int villageCount,
                        DiplomaticRelation relation) {}
    record VillageEntry(UUID id, String name, String tier,
                        String leader) {}

    public KingdomBookScreen(UUID kingdomId) {
        super(Component.literal("Kingdom Book"));
        this.kingdomId = kingdomId;
    }

    @Override
    protected void init() {
        bookX = (width  - BOOK_W) / 2;
        bookY = (height - BOOK_H) / 2;

        refreshData();
        buildWidgets();
    }

    private void refreshData() {
        Kingdom.ClientKingdomCache.getById(kingdomId)
                .ifPresent(k -> {
                    kingdomName    = k.getName();
                    rulerName      = "Dev"; // swap for player name lookup
                    villageCount   = k.getVillageIds().size();
                    treasury       = k.getTreasuryBronze();
                    taxRate        = (int)(k.getIncomeTaxRate() * 100);
                    upkeep         = k.getFlatUpkeepBronze();
                    activeLaws.clear();
                    activeLaws.addAll(k.getActiveLaws());
                    activeLawCount = activeLaws.size();

                    // Populate kingdoms list for diplomacy page
                    kingdoms.clear();
                    k.getAllRelations().forEach((otherId, rel) ->
                            Kingdom.ClientKingdomCache
                                    .getById(otherId)
                                    .ifPresent(other -> kingdoms.add(
                                            new KingdomEntry(
                                                    other.getId(),
                                                    other.getName(),
                                                    other.getVillageIds()
                                                            .size(),
                                                    rel))));
                });
    }

    // -------------------------------------------------------------------------
    // Widget construction
    // -------------------------------------------------------------------------

    private void buildWidgets() {
        clearWidgets();
        pageButtons.clear();

        int px = bookX + SIDEBAR_W + PAGE_PAD;
        int py = bookY + 36;
        int pw = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;

        // Prev/Next buttons
        addRenderableWidget(Button.builder(
                        Component.literal("←"),
                        b -> changePage(-1))
                .pos(bookX + SIDEBAR_W + 8, bookY + BOOK_H - 28)
                .size(32, 18)
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal("→"),
                        b -> changePage(1))
                .pos(bookX + BOOK_W - 40, bookY + BOOK_H - 28)
                .size(32, 18)
                .build());

        // Page-specific widgets
        buildPageWidgets(px, py, pw);
    }

    private void buildPageWidgets(int px, int py, int pw) {
        if (page == 3) buildLawWidgets(px, py, pw);
        if (page == 4) buildEconomyWidgets(px, py, pw);
        if (page == 6) buildDiplomacyWidgets(px, py, pw);
        if (page == 7) buildDecreeWidgets(px, py, pw);
    }

    private void buildLawWidgets(int px, int py, int pw) {
        KingdomLaw[] laws = KingdomLaw.values();
        for (int i = 0; i < laws.length; i++) {
            KingdomLaw law = laws[i];
            boolean active = activeLaws.contains(law);
            int by = py + i * 28;
            if (by + 20 > bookY + BOOK_H - 32) break;

            Button btn = Button.builder(
                            Component.literal(active ? "Repeal" : "Enact"),
                            b -> sendAction(
                                    KingdomActionPacket.ActionType.TOGGLE_LAW,
                                    law.name(), 0))
                    .pos(px + pw - 52, by)
                    .size(50, 16)
                    .build();
            addRenderableWidget(btn);
            pageButtons.add(btn);
        }
    }

    private void buildEconomyWidgets(int px, int py, int pw) {
        // Tax rate buttons
        addRenderableWidget(Button.builder(
                        Component.literal("-"),
                        b -> {
                            taxRate = Math.max(0, taxRate - 5);
                            sendAction(
                                    KingdomActionPacket.ActionType.SET_TAX_RATE,
                                    "", taxRate);
                        })
                .pos(px + pw / 2 - 40, py + 28)
                .size(24, 16)
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal("+"),
                        b -> {
                            taxRate = Math.min(50, taxRate + 5);
                            sendAction(
                                    KingdomActionPacket.ActionType.SET_TAX_RATE,
                                    "", taxRate);
                        })
                .pos(px + pw / 2 + 16, py + 28)
                .size(24, 16)
                .build());

        // Upkeep buttons
        addRenderableWidget(Button.builder(
                        Component.literal("-"),
                        b -> {
                            upkeep = Math.max(0, upkeep - 8);
                            sendAction(
                                    KingdomActionPacket.ActionType.SET_UPKEEP,
                                    "", (int) upkeep);
                        })
                .pos(px + pw / 2 - 40, py + 72)
                .size(24, 16)
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal("+"),
                        b -> {
                            upkeep = Math.min(256, upkeep + 8);
                            sendAction(
                                    KingdomActionPacket.ActionType.SET_UPKEEP,
                                    "", (int) upkeep);
                        })
                .pos(px + pw / 2 + 16, py + 72)
                .size(24, 16)
                .build());
    }

    private void buildDiplomacyWidgets(int px, int py, int pw) {
        DiplomaticRelation[] rels = DiplomaticRelation.values();
        for (int i = 0; i < kingdoms.size(); i++) {
            KingdomEntry k = kingdoms.get(i);
            int by = py + i * 32;
            if (by + 20 > bookY + BOOK_H - 32) break;

            // Cycle through relations on click
            int finalI = i;
            addRenderableWidget(Button.builder(
                            Component.literal("Change"),
                            b -> {
                                DiplomaticRelation cur =
                                        kingdoms.get(finalI).relation();
                                DiplomaticRelation next =
                                        rels[(cur.ordinal() + 1)
                                                % rels.length];
                                sendAction(
                                        KingdomActionPacket.ActionType
                                                .SET_RELATION,
                                        k.id() + ":" + next.name(), 0);
                            })
                    .pos(px + pw - 54, by + 6)
                    .size(52, 16)
                    .build());
        }
    }

    private void buildDecreeWidgets(int px, int py, int pw) {
        decreeBox = new EditBox(font,
                px, py + 20, pw, 60,
                Component.literal("Write decree..."));
        decreeBox.setMaxLength(256);
        addRenderableWidget(decreeBox);

        addRenderableWidget(Button.builder(
                        Component.literal("Issue Decree"),
                        b -> {
                            if (decreeBox != null
                                    && !decreeBox.getValue().isEmpty()) {
                                sendAction(
                                        KingdomActionPacket.ActionType
                                                .ISSUE_DECREE,
                                        decreeBox.getValue(), 0);
                                decreeBox.setValue("");
                            }
                        })
                .pos(px, py + 88)
                .size(80, 18)
                .build());
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        //renderBackground(g, mx, my, pt);
        drawBook(g);
        drawSidebar(g);
        drawPageContent(g);
        super.render(g, mx, my, pt);
    }

    private void drawBook(GuiGraphics g) {
        // Outer shadow
        g.fill(bookX + 3, bookY + 3,
                bookX + BOOK_W + 3, bookY + BOOK_H + 3,
                0x44000000);

        // Main book background
        g.fill(bookX, bookY, bookX + BOOK_W,
                bookY + BOOK_H, COL_PARCHMENT);

        // Border
        g.renderOutline(bookX, bookY,
                BOOK_W, BOOK_H, COL_BORDER);

        // Inner border inset
        g.renderOutline(bookX + 2, bookY + 2,
                BOOK_W - 4, BOOK_H - 4, COL_HIGHLIGHT);

        // Page header separator
        g.fill(bookX + SIDEBAR_W, bookY + 28,
                bookX + BOOK_W, bookY + 29, COL_BORDER);

        // Page footer separator
        g.fill(bookX + SIDEBAR_W, bookY + BOOK_H - 30,
                bookX + BOOK_W, bookY + BOOK_H - 29,
                COL_BORDER);

        // Page title
        g.drawString(font,
                PAGE_TITLES[page],
                bookX + SIDEBAR_W + PAGE_PAD,
                bookY + 10,
                COL_DARK, false);

        // Page number
        String pageNum = (page + 1) + " / "
                + PAGE_TITLES.length;
        int pnw = font.width(pageNum);
        g.drawString(font, pageNum,
                bookX + BOOK_W - pnw - PAGE_PAD,
                bookY + 10,
                COL_LIGHT, false);

        // Decorative corner flourishes
        drawFlourishCorner(g, bookX + 4, bookY + 4);
        drawFlourishCorner(g, bookX + BOOK_W - 12,
                bookY + 4);
        drawFlourishCorner(g, bookX + 4,
                bookY + BOOK_H - 12);
        drawFlourishCorner(g, bookX + BOOK_W - 12,
                bookY + BOOK_H - 12);
    }

    private void drawFlourishCorner(GuiGraphics g,
                                    int x, int y) {
        g.fill(x, y, x + 8, y + 1, COL_BORDER);
        g.fill(x, y, x + 1, y + 8, COL_BORDER);
    }

    private void drawSidebar(GuiGraphics g) {
        // Sidebar background
        g.fill(bookX, bookY,
                bookX + SIDEBAR_W, bookY + BOOK_H,
                COL_SIDEBAR);

        // Sidebar right border
        g.fill(bookX + SIDEBAR_W - 1, bookY,
                bookX + SIDEBAR_W, bookY + BOOK_H,
                COL_BORDER);

        // Kingdom name and crown
        g.drawString(font, "\u265B " + kingdomName,
                bookX + 8, bookY + 10,
                COL_DARK, false);

        g.fill(bookX + 4, bookY + 22,
                bookX + SIDEBAR_W - 4, bookY + 23,
                COL_BORDER);

        // Nav section labels and items
        int[] sectionStarts = {0, 3, 6, 8};
        String[] sectionLabels = {
                "Overview", "Governance",
                "Foreign", "Build"
        };

        int ny = bookY + 28;
        int section = 0;

        for (int i = 0; i < NAV_LABELS.length; i++) {
            // Section header
            if (section < sectionStarts.length
                    && i == sectionStarts[section]) {
                g.drawString(font,
                        sectionLabels[section].toUpperCase(),
                        bookX + 8, ny,
                        COL_LIGHT, false);
                ny += 12;
                section++;
            }

            boolean active = (i == page);

            if (active) {
                g.fill(bookX + 2, ny - 1,
                        bookX + SIDEBAR_W - 1, ny + 9,
                        COL_HIGHLIGHT);
                g.fill(bookX + 2, ny - 1,
                        bookX + 4, ny + 9,
                        COL_GOLD);
            }

            // Make nav items clickable
            int finalI = i;
            int ny2 = ny;
            g.drawString(font,
                    (active ? "> " : "  ")
                            + NAV_LABELS[i],
                    bookX + 6, ny,
                    active ? COL_DARK : COL_MID, false);

            ny += 18;
        }
    }

    private void drawPageContent(GuiGraphics g) {
        int px = bookX + SIDEBAR_W + PAGE_PAD;
        int py = bookY + 36;
        int pw = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;
        int maxY = bookY + BOOK_H - 34;

        switch (page) {
            case 0 -> drawFrontispiece(g, px, py, pw);
            case 1 -> drawStatus(g, px, py, pw, maxY);
            case 2 -> drawHistory(g, px, py, pw, maxY);
            case 3 -> drawLaws(g, px, py, pw, maxY);
            case 4 -> drawEconomy(g, px, py, pw, maxY);
            case 5 -> drawAppointments(g, px, py, pw, maxY);
            case 6 -> drawDiplomacy(g, px, py, pw, maxY);
            case 7 -> drawDecrees(g, px, py, pw, maxY);
            case 8 -> drawRoyalBuilds(g, px, py, pw, maxY);
        }
    }

    private void drawFrontispiece(GuiGraphics g,
                                  int px, int py, int pw) {
        // Crown symbol large
        String crown = "\u265B";
        int cw = font.width(crown) * 2;
        g.drawString(font, crown,
                px + pw / 2 - cw / 4,
                py + 20, COL_GOLD, false);

        // Kingdom name centered
        int nw = font.width(kingdomName);
        g.drawString(font, kingdomName,
                px + pw / 2 - nw / 2,
                py + 50, COL_DARK, false);

        // Decorative line
        g.fill(px + pw / 4, py + 62,
                px + pw * 3 / 4, py + 63,
                COL_BORDER);

        // Tagline
        String tag = "By right of deed, by will of the realm";
        int tw = font.width(tag);
        g.drawString(font, tag,
                px + pw / 2 - tw / 2,
                py + 70, COL_MID, false);

        // Ruler line
        String ruler = "Ruler: " + rulerName;
        int rw = font.width(ruler);
        g.drawString(font, ruler,
                px + pw / 2 - rw / 2,
                py + 110, COL_LIGHT, false);
    }

    private void drawStatus(GuiGraphics g,
                            int px, int py, int pw,
                            int maxY) {
        // Stat boxes
        drawStatBox(g, px, py, 72, 44,
                "Villages", String.valueOf(villageCount));
        drawStatBox(g, px + 80, py, 72, 44,
                "Treasury", treasury + "b");
        drawStatBox(g, px + 160, py, 72, 44,
                "Laws", String.valueOf(activeLawCount));

        int y = py + 54;
        g.drawString(font, "Villages",
                px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 14;

        for (VillageEntry v : villages) {
            if (y + 10 > maxY) break;
            g.drawString(font, v.name() + " \u00b7 " + v.tier(),
                    px, y, COL_DARK, false);
            int lw = font.width(v.leader());
            g.drawString(font, v.leader(),
                    px + pw - lw, y, COL_MID, false);
            y += 14;
        }
    }

    private void drawStatBox(GuiGraphics g, int x, int y,
                             int w, int h,
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

    private void drawHistory(GuiGraphics g,
                             int px, int py, int pw,
                             int maxY) {
        String[] lines = {
                "In the age before the great roads,",
                "the lands now called " + kingdomName,
                "were divided among scattered hamlets.",
                "",
                "It was not until a wandering adventurer",
                "earned the trust of every village elder",
                "that the villages agreed to speak",
                "with one voice.",
                "",
                "The crown rests not in iron and stone,",
                "but in the faith of those who toil."
        };

        int y = py;
        for (String line : lines) {
            if (y + 10 > maxY) break;
            if (line.isEmpty()) {
                y += 6;
                continue;
            }
            g.drawString(font, line, px, y,
                    COL_DARK, false);
            y += 12;
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

            // Row background for active laws
            if (active) {
                g.fill(px - 2, y - 1,
                        px + pw + 2, y + 21,
                        COL_GREEN_BG);
            }

            // Law name
            g.drawString(font,
                    formatLawName(law.name()),
                    px, y + 4,
                    active ? COL_GREEN_TXT : COL_DARK,
                    false);

            // Status indicator
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
        // Tax rate
        g.drawString(font, "Income tax rate",
                px, py, COL_DARK, false);
        drawValueControl(g, px, py + 14, pw,
                taxRate + "%");

        g.fill(px, py + 36, px + pw, py + 37, COL_HIGHLIGHT);

        // Upkeep
        g.drawString(font, "Flat upkeep per village",
                px, py + 44, COL_DARK, false);
        drawValueControl(g, px, py + 58, pw,
                upkeep + " bronze");

        g.fill(px, py + 80, px + pw, py + 81, COL_HIGHLIGHT);

        // Treasury stats
        g.drawString(font, "Treasury",
                px, py + 90, COL_MID, false);
        drawStatBox(g, px, py + 102, 72, 40,
                "Balance", treasury + "b");
        drawStatBox(g, px + 80, py + 102, 80, 40,
                "Est. daily",
                "~" + (villageCount * upkeep
                        + 200 * taxRate / 100) + "b");
    }

    private void drawValueControl(GuiGraphics g,
                                  int px, int py,
                                  int pw, String value) {
        // [-] value [+] layout — buttons added in buildWidgets
        int vw = font.width(value);
        g.drawString(font, value,
                px + pw / 2 - vw / 2, py + 2,
                COL_DARK, false);
    }

    private void drawAppointments(GuiGraphics g,
                                  int px, int py, int pw,
                                  int maxY) {
        g.drawString(font, "Village leaders",
                px, py, COL_MID, false);
        g.fill(px, py + 10, px + pw, py + 11, COL_BORDER);

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
        g.fill(px, py + 10, px + pw, py + 11, COL_BORDER);

        int y = py + 16;
        for (KingdomEntry k : kingdoms) {
            if (y + 26 > maxY) break;

            g.drawString(font, k.name(),
                    px, y + 4, COL_DARK, false);

            // Relation badge
            int[] relColors = relColors(k.relation());
            String relLabel = formatRelation(k.relation());
            int rw = font.width(relLabel) + 8;
            g.fill(px + 120, y + 2,
                    px + 120 + rw, y + 14,
                    relColors[0]);
            g.drawString(font, relLabel,
                    px + 124, y + 4,
                    relColors[1], false);

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
        g.fill(px, py + 10, px + pw, py + 11, COL_BORDER);
        // EditBox and button rendered by widget system

        g.drawString(font, "Recent decrees",
                px, py + 114, COL_MID, false);
        g.fill(px, py + 124, px + pw, py + 125,
                COL_BORDER);

        String[] recent = {
                "Day 14 \u2014 Three days of rest proclaimed.",
                "Day 7 \u2014 Trade tariffs extended."
        };
        int y = py + 130;
        for (String decree : recent) {
            if (y + 10 > maxY) break;
            g.drawString(font, decree, px, y,
                    COL_DARK, false);
            y += 14;
        }
    }

    private void drawRoyalBuilds(GuiGraphics g,
                                 int px, int py, int pw,
                                 int maxY) {
        String[][] builds = {
                {"Royal Keep",
                        "Seat of power. Unlocks advanced governance.",
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
            boolean locked = build[2].contains("City");

            g.fill(px, y, px + pw, y + 38,
                    locked ? COL_HIGHLIGHT : COL_PARCHMENT);
            g.renderOutline(px, y, pw, 38, COL_BORDER);

            g.drawString(font, build[0],
                    px + 6, y + 5,
                    locked ? COL_LIGHT : COL_DARK, false);
            g.drawString(font, build[1],
                    px + 6, y + 17,
                    COL_MID, false);
            g.drawString(font, build[2],
                    px + 6, y + 27,
                    COL_LIGHT, false);

            y += 44;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void changePage(int delta) {
        page = Math.max(0, Math.min(
                PAGE_TITLES.length - 1, page + delta));
        buildWidgets();
    }

    private void sendAction(KingdomActionPacket.ActionType type,
                            String strParam, int intParam) {
        ClientPacketDistributor.sendToServer(
                new KingdomActionPacket(type, kingdomId,
                        strParam, intParam));
    }

    private String formatLawName(String name) {
        return name.charAt(0)
                + name.substring(1).toLowerCase()
                .replace("_", " ");
    }

    private String formatRelation(DiplomaticRelation rel) {
        return rel.name().charAt(0)
                + rel.name().substring(1).toLowerCase()
                .replace("_", " ");
    }

    private int[] relColors(DiplomaticRelation rel) {
        return switch (rel) {
            case ALLIANCE  -> new int[]{COL_GREEN_BG, COL_GREEN_TXT};
            case TRADE     -> new int[]{0xFFD0E8FF, 0xFF1A4A8B};
            case NEUTRAL   -> new int[]{COL_HIGHLIGHT, COL_MID};
            case COLD_WAR  -> new int[]{0xFFFFF0C0, 0xFF8B6B00};
            case WAR       -> new int[]{COL_RED_BG, COL_RED_TXT};
        };
    }


  @Override
    public boolean mouseClicked(MouseButtonEvent event,
                                boolean consumed) {
        if (consumed) return super.mouseClicked(event, consumed);

        double mx = event.x();
        double my = event.y();

        // Check sidebar nav clicks
        int ny = bookY + 28;
        int section = 0;
        int[] sectionStarts = {0, 3, 6, 8};

        for (int i = 0; i < NAV_LABELS.length; i++) {
            if (section < sectionStarts.length
                    && i == sectionStarts[section]) {
                ny += 12;
                section++;
            }
            if (mx >= bookX + 2 && mx <= bookX + SIDEBAR_W - 1
                    && my >= ny - 1 && my <= ny + 9) {
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

    // Set kingdom data — called when server sends updated data
    public void setKingdomData(String name, String ruler,
                               int villages, long treasury,
                               Set<KingdomLaw> laws,
                               int taxRate, long upkeep) {
        this.kingdomName   = name;
        this.rulerName     = ruler;
        this.villageCount  = villages;
        this.treasury      = treasury;
        this.activeLaws.clear();
        this.activeLaws.addAll(laws);
        this.activeLawCount = laws.size();
        this.taxRate       = taxRate;
        this.upkeep        = upkeep;
        buildWidgets();
    }
}