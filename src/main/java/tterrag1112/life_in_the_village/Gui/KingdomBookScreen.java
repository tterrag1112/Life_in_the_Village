package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import tterrag1112.life_in_the_village.Gui.Framework.*;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.KingdomMapPanel;
import tterrag1112.life_in_the_village.Kingdom.*;
import tterrag1112.life_in_the_village.Kingdom.Houses.House;
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
        // Phase 5d — cross-village price board (arbitrage view).
        PRICES,
        APPOINTMENTS, DIPLOMACY, DECREES, KINGDOM_MAP, ROYAL_BUILDS,
        // Track D3.2b — noble dynasties owned by this kingdom.
        DYNASTY_TREE,
        // Track D3.5B — audience chamber: pending petitions + grievance submit.
        AUDIENCE,
        // Track D3.5D — charter request builder + newsfeed panel.
        CHARTER_REQUEST,
        NEWSFEED
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
    // Track D3.2b — synced from Kingdom.governance.houses.
    private final List<House> houses                       = new ArrayList<>();
    // Phase 5d — cross-village price board (request/sync; null = not loaded).
    private List<tterrag1112.life_in_the_village.Village.Economy.Currency
            .KingdomPriceAggregator.ItemSpread> priceSpreads = null;
    private boolean pricesRequested = false;
    private String selectedPriceItem = null; // drill-in: item id, or null

    private KingdomMapPanel mapPanel;
    private Sidebar<SectionType> sidebar;
    private StyledEditBox decreeBox;
    /** Track D3.5B — grievance text input on the AUDIENCE page. */
    private StyledEditBox grievanceBox;
    /** Track D3.5D — charter builder param input. */
    private StyledEditBox charterParamBox;
    /** Track D3.5D — currently-selected charter kind in the builder. */
    private tterrag1112.life_in_the_village.Kingdom.Charters.CharterType charterBuilderKind
            = tterrag1112.life_in_the_village.Kingdom.Charters.CharterType.TOLL_RIGHTS;

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

        // Track D3.2b — pull noble houses from the synced Kingdom record.
        houses.clear();
        houses.addAll(k.getHouses());

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
        navEntries.add(new NavEntry("Prices",        SectionType.PRICES,       -1));
        navEntries.add(new NavEntry("Appointments", SectionType.APPOINTMENTS, -1));
        navEntries.add(new NavEntry("Diplomacy",    SectionType.DIPLOMACY,    -1));
        navEntries.add(new NavEntry("Decrees",      SectionType.DECREES,      -1));
        navEntries.add(new NavEntry("Kingdom Map",  SectionType.KINGDOM_MAP,  -1));
        navEntries.add(new NavEntry("Royal Builds", SectionType.ROYAL_BUILDS, -1));
        navEntries.add(new NavEntry("Houses",       SectionType.DYNASTY_TREE, -1));
        // Track D3.5B — audience chamber.
        navEntries.add(new NavEntry("Audience",     SectionType.AUDIENCE,     -1));
        // Track D3.5D — charter builder + newsfeed.
        navEntries.add(new NavEntry("Charter Req",  SectionType.CHARTER_REQUEST, -1));
        navEntries.add(new NavEntry("Newsfeed",     SectionType.NEWSFEED,        -1));
        page = Math.min(page, navEntries.size() - 1);
    }

    private void buildWidgets() {
        clearWidgets();
        decreeBox = null;
        grievanceBox = null;
        charterParamBox = null;

        sidebar = new Sidebar<>(bookX + 2, bookY + 28, SIDEBAR_W - 2, 18,
                List.of(
                        new Sidebar.Entry<>(SectionType.FRONTISPIECE, "Frontispiece", true),
                        new Sidebar.Entry<>(SectionType.STATUS,       "Status",       true),
                        new Sidebar.Entry<>(SectionType.HISTORY,      "History",      true),
                        new Sidebar.Entry<>(SectionType.LAWS,         "Laws",         true),
                        new Sidebar.Entry<>(SectionType.ECONOMY,      "Economy",      true),
                        new Sidebar.Entry<>(SectionType.PRICES,       "Prices",       true),
                        new Sidebar.Entry<>(SectionType.APPOINTMENTS, "Appointments", true),
                        new Sidebar.Entry<>(SectionType.DIPLOMACY,    "Diplomacy",    true),
                        new Sidebar.Entry<>(SectionType.DECREES,      "Decrees",      true),
                        new Sidebar.Entry<>(SectionType.KINGDOM_MAP,  "Kingdom Map",  true),
                        new Sidebar.Entry<>(SectionType.ROYAL_BUILDS, "Royal Builds", true),
                        new Sidebar.Entry<>(SectionType.DYNASTY_TREE, "Houses",       true),
                        // Track D3.5B — audience chamber.
                        new Sidebar.Entry<>(SectionType.AUDIENCE,     "Audience",     true),
                        // Track D3.5D — charter builder + newsfeed.
                        new Sidebar.Entry<>(SectionType.CHARTER_REQUEST, "Charter Req", true),
                        new Sidebar.Entry<>(SectionType.NEWSFEED,       "Newsfeed",    true)
                ),
                this::currentSection,
                section -> {
                    // Phase 5d — lazy-load the price board on first PRICES view.
                    if (section == SectionType.PRICES) requestPricesOnce();
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
            case AUDIENCE  -> buildAudienceWidgets(px, py, pw);
            case CHARTER_REQUEST -> buildCharterRequestWidgets(px, py, pw);
            case NEWSFEED  -> {}
            default        -> {}
        }
    }

    private void buildLawWidgets(int px, int py, int pw) {
        // Track D3.4 — typology-aware Laws panel. Each registered
        // law renders one row showing state (AVAILABLE / DRAFT /
        // PROPOSED / ACTIVE) and a lifecycle action button. Scalar
        // and Enum drafts also surface ± / cycle buttons for
        // parameter editing. Server enforces capability gates;
        // client-side check just greys the action button.
        Kingdom kCap = Kingdom.ClientKingdomCache.getById(kingdomId).orElse(null);
        var laws = tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLawRegistry.all();
        for (int i = 0; i < laws.size(); i++) {
            var law = laws.get(i);
            int by = py + i * 28;
            if (by + 20 > bookY + BOOK_H - 32) break;
            buildLawRow(px, by, pw, law, kCap);
        }
    }

    /** Track D3.4 — one lifecycle action row per registered law. */
    private void buildLawRow(int px, int py, int pw,
                             tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLaw law,
                             Kingdom kCap) {
        var inst = (kCap != null)
                ? kCap.findLawInstance(law.id()).orElse(null)
                : null;
        var state = (inst != null) ? inst.state() : null;

        // Capability gate for this law's enactment authority.
        boolean canEnact = tterrag1112.life_in_the_village.Kingdom.Capabilities
                .ClientCapabilityCheck.canExercise(kCap, law.enactmentCapability());
        String enactTooltip = tterrag1112.life_in_the_village.Kingdom.Capabilities
                .ClientCapabilityCheck.buildTooltip(kCap, law.enactmentCapability());

        // ── Parameter editor (Scalar / Enum on DRAFT) ──────────────────────
        if (state == tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLawState.DRAFT) {
            if (law instanceof tterrag1112.life_in_the_village.Kingdom.Laws.ScalarLaw scalar) {
                double cur = inst.scalarValue().orElse(scalar.defaultValue());
                StyledButton minus = StyledButton.builder(Component.literal("−"), b -> {
                    sendAction(KingdomActionPacket.ActionType.UPDATE_DRAFT_SCALAR,
                            law.id() + ":" + (cur - scalar.step()), 0);
                }).pos(px + pw - 110, py).size(14, 16).build();
                addRenderableWidget(minus);
                StyledButton plus = StyledButton.builder(Component.literal("+"), b -> {
                    sendAction(KingdomActionPacket.ActionType.UPDATE_DRAFT_SCALAR,
                            law.id() + ":" + (cur + scalar.step()), 0);
                }).pos(px + pw - 92, py).size(14, 16).build();
                addRenderableWidget(plus);
            } else if (law instanceof tterrag1112.life_in_the_village.Kingdom.Laws.EnumLaw enumLaw) {
                String cur = inst.enumChoice().orElse(enumLaw.defaultChoice());
                int idx = enumLaw.choices().indexOf(cur);
                String next = enumLaw.choices().get((idx + 1) % enumLaw.choices().size());
                StyledButton cycle = StyledButton.builder(Component.literal("▶"), b -> {
                    sendAction(KingdomActionPacket.ActionType.UPDATE_DRAFT_CHOICE,
                            law.id() + ":" + next, 0);
                }).pos(px + pw - 92, py).size(28, 16).build();
                addRenderableWidget(cycle);
            }
        }

        // ── Lifecycle action button ────────────────────────────────────────
        // AVAILABLE → DRAFT_LAW; DRAFT → PROPOSE_LAW; PROPOSED → ENACT_LAW;
        // ACTIVE → REPEAL_LAW. Single button cycles forward.
        final String actionLabel;
        final KingdomActionPacket.ActionType actionVerb;
        if (state == null) {
            actionLabel = "Draft";
            actionVerb = KingdomActionPacket.ActionType.DRAFT_LAW;
        } else if (state == tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLawState.DRAFT) {
            actionLabel = "Propose";
            actionVerb = KingdomActionPacket.ActionType.PROPOSE_LAW;
        } else if (state == tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLawState.PROPOSED) {
            actionLabel = "Enact";
            actionVerb = KingdomActionPacket.ActionType.ENACT_LAW;
        } else {
            actionLabel = "Repeal";
            actionVerb = KingdomActionPacket.ActionType.REPEAL_LAW;
        }
        StyledButton act = StyledButton.builder(Component.literal(actionLabel),
                        b -> sendAction(actionVerb, law.id(), 0))
                .pos(px + pw - 60, py).size(58, 16).build();
        act.active = canEnact;
        act.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal(enactTooltip)));
        addRenderableWidget(act);

        // ── Repeal-while-drafting / cancel button ──────────────────────────
        // For DRAFT or PROPOSED, also show a small "X" to cancel.
        if (state == tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLawState.DRAFT
                || state == tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLawState.PROPOSED) {
            StyledButton cancel = StyledButton.builder(Component.literal("X"),
                            b -> sendAction(KingdomActionPacket.ActionType.REPEAL_LAW,
                                    law.id(), 0))
                    .pos(px + pw - 76, py).size(14, 16).build();
            addRenderableWidget(cancel);
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

        // Track D3.3b — capability gate. ISSUE_DECREE requires
        // King OR Chancellor.
        Kingdom kCap = Kingdom.ClientKingdomCache.getById(kingdomId).orElse(null);
        var decreeCap = tterrag1112.life_in_the_village.Kingdom.Capabilities
                .KingdomCapability.ISSUE_DECREE;
        boolean canDecree = tterrag1112.life_in_the_village.Kingdom.Capabilities
                .ClientCapabilityCheck.canExercise(kCap, decreeCap);
        String decreeTooltip = tterrag1112.life_in_the_village.Kingdom.Capabilities
                .ClientCapabilityCheck.buildTooltip(kCap, decreeCap);
        StyledButton decreeBtn = StyledButton.builder(Component.literal("Issue Decree"), b -> {
            if (decreeBox != null && !decreeBox.getValue().isEmpty()) {
                sendAction(KingdomActionPacket.ActionType.ISSUE_DECREE, decreeBox.getValue(), 0);
                decreeBox.setValue("");
            }
        }).pos(px, py + 88).size(80, 18).build();
        decreeBtn.active = canDecree;
        decreeBtn.setTooltip(net.minecraft.client.gui.components.Tooltip
                .create(Component.literal(decreeTooltip)));
        addRenderableWidget(decreeBtn);
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
            case PRICES       -> drawPrices(g, px, py, pw, maxY);
            case APPOINTMENTS -> drawAppointments(g, px, py, pw, maxY);
            case DIPLOMACY    -> drawDiplomacy(g, px, py, pw, maxY);
            case DECREES      -> drawDecrees(g, px, py, pw, maxY);
            case KINGDOM_MAP  -> drawKingdomMap(g, px, py, pw, maxY, mx, my);
            case ROYAL_BUILDS -> drawRoyalBuilds(g, px, py, pw, maxY);
            case DYNASTY_TREE -> drawDynastyTree(g, px, py, pw, maxY);
            case AUDIENCE     -> drawAudience(g, px, py, pw, maxY);
            case CHARTER_REQUEST -> drawCharterRequest(g, px, py, pw, maxY);
            case NEWSFEED     -> drawNewsfeed(g, px, py, pw, maxY);
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
        // Track D3.4 — typology-aware row rendering. State badge,
        // archetype tag, parameter display for Scalar / Enum drafts.
        Kingdom kCap = Kingdom.ClientKingdomCache.getById(kingdomId).orElse(null);
        var laws = tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLawRegistry.all();
        int y = py;
        for (var law : laws) {
            if (y + 22 > maxY) break;
            var inst = (kCap != null) ? kCap.findLawInstance(law.id()).orElse(null) : null;
            var state = (inst != null) ? inst.state() : null;
            boolean active = state == tterrag1112.life_in_the_village.Kingdom.Laws
                    .KingdomLawState.ACTIVE;
            if (active) g.fill(px - 2, y - 1, px + pw + 2, y + 21,
                    BookScreenColors.GREEN_BG);
            g.drawString(font, law.displayName(), px, y + 4,
                    active ? BookScreenColors.GREEN_TXT : BookScreenColors.DARK, false);
            // State badge.
            String badge = state == null
                    ? "[available]"
                    : "[" + state.name().toLowerCase(java.util.Locale.ROOT) + "]";
            g.drawString(font, badge, px + 110, y + 4,
                    active ? BookScreenColors.GREEN_TXT : BookScreenColors.LIGHT, false);
            // Parameter display (Scalar / Enum only).
            if (inst != null) {
                String param = "";
                if (law instanceof tterrag1112.life_in_the_village.Kingdom.Laws.ScalarLaw scalar) {
                    double v = inst.scalarValue().orElse(scalar.defaultValue());
                    param = String.format(java.util.Locale.ROOT, "%.0f%s",
                            v, scalar.unit().isEmpty() ? "" : " " + scalar.unit());
                } else if (law instanceof tterrag1112.life_in_the_village.Kingdom.Laws.EnumLaw) {
                    param = inst.enumChoice().orElse("");
                }
                if (!param.isEmpty()) {
                    g.drawString(font, param, px + 170, y + 4,
                            BookScreenColors.LIGHT, false);
                }
            }
            g.fill(px, y + 21, px + pw, y + 22, BookScreenColors.HIGHLIGHT);
            y += 28;
        }
    }

    /** Phase 5d — request the price board once per screen open. */
    private void requestPricesOnce() {
        if (pricesRequested) return;
        pricesRequested = true;
        ClientPacketDistributor.sendToServer(
                new tterrag1112.life_in_the_village.Networking
                        .RequestKingdomPricesPacket(kingdomId));
    }

    /** Phase 5d — server reply from {@code KingdomPricesSyncPacket}. */
    public void applyPrices(List<tterrag1112.life_in_the_village.Village.Economy
            .Currency.KingdomPriceAggregator.ItemSpread> spreads) {
        this.priceSpreads = spreads;
    }

    /**
     * Phase 5d — cross-village price board: each row shows an item, where
     * it's cheapest, where it's dearest, and the spread. Clicking a row
     * drills into per-village prices. Read-only.
     */
    private void drawPrices(GuiGraphics g, int px, int py, int pw, int maxY) {
        g.drawString(font, "Cross-village prices", px, py, BookScreenColors.DARK, false);
        int y = py + 14;

        if (priceSpreads == null) {
            g.drawString(font, "Loading…", px, y, BookScreenColors.LIGHT, false);
            return;
        }
        if (priceSpreads.isEmpty()) {
            g.drawString(font, "No priced markets in this kingdom yet.",
                    px, y, BookScreenColors.LIGHT, false);
            return;
        }

        // Drill-in view for a selected item.
        if (selectedPriceItem != null) {
            var sel = priceSpreads.stream()
                    .filter(s -> s.itemId().equals(selectedPriceItem))
                    .findFirst().orElse(null);
            if (sel != null) {
                g.drawString(font, "‹ " + sel.itemName(), px, y, BookScreenColors.GOLD, false);
                y += 14;
                for (var vp : sel.perVillage()) {
                    if (y + 11 > maxY) break;
                    g.drawString(font, vp.villageName(), px + 4, y, BookScreenColors.DARK, false);
                    String p = CoinRenderer.format(vp.sellPrice());
                    g.drawString(font, p, px + pw - font.width(p) - 4, y,
                            BookScreenColors.MID, false);
                    y += 11;
                }
                return;
            }
            selectedPriceItem = null; // stale — fall through to the list
        }

        // Summary list: item — cheapest@village / dearest@village / spread.
        for (var s : priceSpreads) {
            if (y + 12 > maxY) break;
            g.drawString(font, s.itemName(), px, y, BookScreenColors.DARK, false);
            String spread = "Δ" + CoinRenderer.format(s.spread());
            int sc = s.spread() > 0 ? 0xFF2A7A2A : BookScreenColors.LIGHT;
            g.drawString(font, spread, px + pw - font.width(spread) - 4, y, sc, false);
            g.drawString(font, "low " + s.cheapestPrice() + " @ " + s.cheapestVillage()
                            + "  ·  high " + s.dearestPrice() + " @ " + s.dearestVillage(),
                    px + 4, y + 10, BookScreenColors.MID, false);
            y += 22;
        }
        g.drawString(font, "Click an item for per-village prices.",
                px, Math.min(y, maxY - 10), BookScreenColors.LIGHT, false);
    }

    /** Phase 5d — price-board row click → toggle drill-in. */
    private boolean handlePricesClick(double mx, double my) {
        if (currentSection() != SectionType.PRICES || priceSpreads == null) return false;
        int px = bookX + SIDEBAR_W + PAGE_PAD;
        int py = bookY + 36;
        int pw = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;
        if (mx < px || mx > px + pw) return false;
        if (selectedPriceItem != null) { selectedPriceItem = null; return true; } // back
        int y = py + 14;
        for (var s : priceSpreads) {
            if (my >= y && my < y + 22) { selectedPriceItem = s.itemId(); return true; }
            y += 22;
        }
        return false;
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

    // =========================================================================
    // Track D3.5B — Audience chamber section
    // =========================================================================

    /**
     * Build buttons + edit box for the AUDIENCE page. One row per
     * pending petition with Approve / Deny / Withdraw inline (visible
     * by player role); a grievance submit form below the list.
     */
    private void buildAudienceWidgets(int px, int py, int pw) {
        Kingdom k = Kingdom.ClientKingdomCache.getById(kingdomId).orElse(null);
        if (k == null) return;

        java.util.UUID self = (minecraft != null && minecraft.player != null)
                ? minecraft.player.getUUID() : null;
        boolean isRuler = self != null
                && k.getRulerPlayerId().filter(self::equals).isPresent();

        var pending = k.getPetitions().stream()
                .filter(p -> p.isPending())
                .toList();

        // Per-pending row: text in drawAudience, buttons here.
        int rowH = 22;
        int listTop = py + 32;
        int maxRows = (bookY + BOOK_H - 90 - listTop) / rowH;
        int shown = Math.min(maxRows, pending.size());
        for (int i = 0; i < shown; i++) {
            var pet = pending.get(i);
            int by = listTop + i * rowH;
            int btnX = px + pw - 60;
            boolean isPetitioner = self != null && pet.playerUuid().equals(self);
            if (isRuler) {
                addRenderableWidget(StyledButton.builder(
                        Component.literal("✓"),
                        b -> sendPetitionAction(
                                tterrag1112.life_in_the_village.Networking
                                        .KingdomPetitionPacket.Action.APPROVE,
                                pet.id()))
                        .pos(btnX, by + 2).size(18, 16).build());
                addRenderableWidget(StyledButton.builder(
                        Component.literal("✗"),
                        b -> sendPetitionAction(
                                tterrag1112.life_in_the_village.Networking
                                        .KingdomPetitionPacket.Action.DENY,
                                pet.id()))
                        .pos(btnX + 20, by + 2).size(18, 16).build());
            } else if (isPetitioner) {
                addRenderableWidget(StyledButton.builder(
                        Component.literal("Withdraw"),
                        b -> sendPetitionAction(
                                tterrag1112.life_in_the_village.Networking
                                        .KingdomPetitionPacket.Action.WITHDRAW,
                                pet.id()))
                        .pos(btnX - 18, by + 2).size(58, 16).build());
            }
        }

        // Grievance submit form pinned to bottom of page.
        int submitY = bookY + BOOK_H - 100;
        grievanceBox = new StyledEditBox(font, px, submitY, pw, 16,
                Component.literal("Submit a grievance..."));
        grievanceBox.setMaxLength(256);
        addRenderableWidget(grievanceBox);
        addRenderableWidget(StyledButton.builder(
                Component.literal("Submit grievance"),
                b -> {
                    if (grievanceBox != null && !grievanceBox.getValue().isEmpty()) {
                        submitGrievance(grievanceBox.getValue());
                        grievanceBox.setValue("");
                    }
                })
                .pos(px, submitY + 22).size(pw, 18).build());

        // Track D3.5C — charter-request quick buttons. Two flagship
        // flows: request a title (lowest noble rank) and request a
        // manor land grant at the player's current position.
        int charterY = submitY + 46;
        int halfW = (pw - 4) / 2;
        addRenderableWidget(StyledButton.builder(
                Component.literal("Request title (rank 1)"),
                b -> requestTitleGrant(1))
                .pos(px, charterY).size(halfW, 18).build());
        addRenderableWidget(StyledButton.builder(
                Component.literal("Request land grant"),
                b -> requestLandGrant(4))
                .pos(px + halfW + 4, charterY).size(halfW, 18).build());
    }

    private void drawAudience(GuiGraphics g, int px, int py, int pw, int maxY) {
        Kingdom k = Kingdom.ClientKingdomCache.getById(kingdomId).orElse(null);
        if (k == null) {
            g.drawString(font, "No kingdom data available.", px, py,
                    BookScreenColors.LIGHT, false);
            return;
        }

        java.util.UUID self = (minecraft != null && minecraft.player != null)
                ? minecraft.player.getUUID() : null;

        // Header.
        g.drawString(font, "Audience Chamber", px, py, BookScreenColors.MID, false);
        g.fill(px, py + 10, px + pw, py + 11, BookScreenColors.BORDER);

        // Standing summary for the local player.
        if (self != null) {
            var ps = k.getAllPlayerStandings().get(self);
            int score = (ps != null) ? ps.score() : 0;
            String band = (score >=
                    tterrag1112.life_in_the_village.Kingdom.Audience
                            .PlayerStanding.TRUSTED_THRESHOLD) ? "TRUSTED"
                    : (score <=
                    tterrag1112.life_in_the_village.Kingdom.Audience
                            .PlayerStanding.HOSTILE_THRESHOLD) ? "HOSTILE"
                    : "neutral";
            g.drawString(font,
                    "Your standing: " + score + " [" + band + "]",
                    px, py + 16, BookScreenColors.LIGHT, false);
        }

        // Pending petitions list.
        var pending = k.getPetitions().stream()
                .filter(p -> p.isPending())
                .toList();
        if (pending.isEmpty()) {
            g.drawString(font, "No pending petitions.", px, py + 36,
                    BookScreenColors.LIGHT, false);
            return;
        }

        int rowH = 22;
        int listTop = py + 32;
        int maxRows = (bookY + BOOK_H - 90 - listTop) / rowH;
        int shown = Math.min(maxRows, pending.size());
        for (int i = 0; i < shown; i++) {
            var pet = pending.get(i);
            int by = listTop + i * rowH;
            String shortId = pet.id().toString().substring(0, 6);
            String shortPlayer = pet.playerUuid().toString().substring(0, 6);
            g.drawString(font,
                    pet.kind().name() + " #" + shortId
                            + " by " + shortPlayer,
                    px, by, BookScreenColors.MID, false);
            g.drawString(font,
                    "submitted tick " + pet.submittedTick(),
                    px, by + 10, BookScreenColors.LIGHT, false);
        }
        if (pending.size() > shown) {
            g.drawString(font,
                    "(+" + (pending.size() - shown) + " more)",
                    px, listTop + shown * rowH,
                    BookScreenColors.LIGHT, false);
        }

        // Track D3.5C — show the player's nobility status with this
        // kingdom (rank + manor). Drawn between the petition list
        // and the submit forms.
        if (self != null) {
            var pn = k.getAllPlayerNobles().get(self);
            int statusY = bookY + BOOK_H - 116;
            if (pn != null && pn.rankIndex() >= 0) {
                String rankLabel = "Rank " + pn.rankIndex();
                String manor = pn.hasLandGrant()
                        ? " · manor " + pn.landGrantBlockX().get()
                                + "," + pn.landGrantBlockZ().get()
                                + " (" + pn.landGrantSize().get() + " cells)"
                        : "";
                g.drawString(font,
                        "Noble of " + k.getName() + ": " + rankLabel + manor,
                        px, statusY, BookScreenColors.MID, false);
            } else {
                g.drawString(font, "Commoner", px, statusY,
                        BookScreenColors.LIGHT, false);
            }
        }
    }

    /** Track D3.5B — fires APPROVE/DENY/WITHDRAW for the named petition. */
    private void sendPetitionAction(
            tterrag1112.life_in_the_village.Networking
                    .KingdomPetitionPacket.Action action,
            java.util.UUID petitionId) {
        ClientPacketDistributor.sendToServer(
                new tterrag1112.life_in_the_village.Networking
                        .KingdomPetitionPacket(action, kingdomId, petitionId, ""));
    }

    /** Track D3.5B — submits an AUDIENCE_GRIEVANCE petition. */
    private void submitGrievance(String text) {
        var payload = new tterrag1112.life_in_the_village.Kingdom.Audience
                .PetitionPayload.AudienceGrievance(text);
        sendSubmit(payload);
    }

    /**
     * Track D3.5C — submits a CHARTER_REQUEST petition asking the
     * kingdom to grant a TITLE_GRANT to the local player at the
     * named rank.
     */
    private void requestTitleGrant(int rankIndex) {
        if (minecraft == null || minecraft.player == null) return;
        var self = minecraft.player.getUUID();
        var charterParams = new tterrag1112.life_in_the_village.Kingdom.Charters
                .CharterParams.TitleGrant(rankIndex);
        var payload = new tterrag1112.life_in_the_village.Kingdom.Audience
                .PetitionPayload.CharterRequest(
                "Title rank " + rankIndex,
                self,
                tterrag1112.life_in_the_village.Kingdom.Charters
                        .GranteeRef.GranteeKind.PLAYER.name(),
                charterParams);
        sendSubmit(payload);
    }

    /**
     * Track D3.5C — submits a CHARTER_REQUEST petition asking for a
     * LAND_GRANT at the player's current block position with the
     * named cell footprint.
     */
    private void requestLandGrant(int sizeCells) {
        if (minecraft == null || minecraft.player == null) return;
        var self = minecraft.player.getUUID();
        int bx = (int) minecraft.player.getX();
        int bz = (int) minecraft.player.getZ();
        var charterParams = new tterrag1112.life_in_the_village.Kingdom.Charters
                .CharterParams.LandGrant(bx, bz, sizeCells);
        var payload = new tterrag1112.life_in_the_village.Kingdom.Audience
                .PetitionPayload.CharterRequest(
                "Manor at " + bx + "," + bz,
                self,
                tterrag1112.life_in_the_village.Kingdom.Charters
                        .GranteeRef.GranteeKind.PLAYER.name(),
                charterParams);
        sendSubmit(payload);
    }

    /** Common encode + send path for any petition payload. */
    private void sendSubmit(
            tterrag1112.life_in_the_village.Kingdom.Audience.PetitionPayload payload) {
        byte[] bytes = tterrag1112.life_in_the_village.Networking
                .KingdomPetitionSubmitPacket.encodePayload(payload);
        ClientPacketDistributor.sendToServer(
                new tterrag1112.life_in_the_village.Networking
                        .KingdomPetitionSubmitPacket(kingdomId, bytes));
    }

    // =========================================================================
    // Track D3.5D — Charter request builder section
    // =========================================================================

    /**
     * Builds the cycle button + per-kind input box + submit button.
     * The cycle button rotates {@link #charterBuilderKind} through
     * the four niche charter types; per-kind label hints the input
     * box's expected value.
     */
    private void buildCharterRequestWidgets(int px, int py, int pw) {
        var Type = tterrag1112.life_in_the_village.Kingdom.Charters.CharterType.class;
        var values = Type.getEnumConstants();
        // Track D3.5D — the four niche kinds covered by this builder.
        // TITLE_GRANT and LAND_GRANT have dedicated AUDIENCE-section
        // buttons; this panel covers the remaining four.
        java.util.List<tterrag1112.life_in_the_village.Kingdom.Charters.CharterType> niche =
                java.util.List.of(
                        tterrag1112.life_in_the_village.Kingdom.Charters.CharterType.TOLL_RIGHTS,
                        tterrag1112.life_in_the_village.Kingdom.Charters.CharterType.TAX_EXEMPTION,
                        tterrag1112.life_in_the_village.Kingdom.Charters.CharterType.MARKET_MONOPOLY,
                        tterrag1112.life_in_the_village.Kingdom.Charters.CharterType.ORDINATION_RIGHTS);
        // Cycle button.
        addRenderableWidget(StyledButton.builder(
                Component.literal("Type: " + charterBuilderKind.name()),
                b -> {
                    int idx = niche.indexOf(charterBuilderKind);
                    if (idx < 0) idx = -1;
                    charterBuilderKind = niche.get((idx + 1) % niche.size());
                    buildWidgets();
                })
                .pos(px, py + 30).size(pw, 18).build());
        // Param edit box (semantics depend on kind).
        charterParamBox = new StyledEditBox(font, px, py + 60, pw, 18,
                Component.literal(paramHintFor(charterBuilderKind)));
        charterParamBox.setMaxLength(64);
        addRenderableWidget(charterParamBox);
        // Submit button.
        addRenderableWidget(StyledButton.builder(
                Component.literal("Request " + charterBuilderKind.name()),
                b -> submitCharterRequest())
                .pos(px, py + 86).size(pw, 18).build());
    }

    private static String paramHintFor(
            tterrag1112.life_in_the_village.Kingdom.Charters.CharterType type) {
        return switch (type) {
            case TOLL_RIGHTS       -> "rate% (e.g. 5)";
            case TAX_EXEMPTION     -> "exempt% (e.g. 50)";
            case MARKET_MONOPOLY   -> "market type (e.g. BUTCHER)";
            case ORDINATION_RIGHTS -> "religious order UUID";
            case TITLE_GRANT       -> "rank index";
            case LAND_GRANT        -> "size cells";
        };
    }

    private void drawCharterRequest(GuiGraphics g, int px, int py, int pw, int maxY) {
        g.drawString(font, "Request a charter", px, py, BookScreenColors.MID, false);
        g.fill(px, py + 10, px + pw, py + 11, BookScreenColors.BORDER);

        String hint = switch (charterBuilderKind) {
            case TOLL_RIGHTS       -> "Toll on a road segment. Param = fee rate as %.";
            case TAX_EXEMPTION     -> "Reduce the kingdom-tax obligation. Param = exempt %.";
            case MARKET_MONOPOLY   -> "Exclusive market right (kingdom-wide). Param = market name.";
            case ORDINATION_RIGHTS -> "Religious order may ordain priests. Param = order UUID.";
            case TITLE_GRANT       -> "Use the AUDIENCE section's title button.";
            case LAND_GRANT        -> "Use the AUDIENCE section's land button.";
        };
        // Wrap hint into two lines if needed.
        for (var line : font.split(Component.literal(hint), pw)) {
            g.drawString(font, line, px, py + 16, BookScreenColors.LIGHT, false);
            py += 9;
        }
    }

    /**
     * Parses the charter-builder text input into the right
     * {@link tterrag1112.life_in_the_village.Kingdom.Charters.CharterParams}
     * variant and dispatches a CHARTER_REQUEST petition. Defaults
     * applied on parse failure (empty input → sensible default).
     */
    private void submitCharterRequest() {
        if (minecraft == null || minecraft.player == null) return;
        var self = minecraft.player.getUUID();
        String input = charterParamBox != null ? charterParamBox.getValue().trim() : "";
        var params = parseCharterParams(charterBuilderKind, input, self);
        if (params == null) return;
        var payload = new tterrag1112.life_in_the_village.Kingdom.Audience
                .PetitionPayload.CharterRequest(
                charterBuilderKind.name() + " request",
                self,
                tterrag1112.life_in_the_village.Kingdom.Charters
                        .GranteeRef.GranteeKind.PLAYER.name(),
                params);
        sendSubmit(payload);
        if (charterParamBox != null) charterParamBox.setValue("");
    }

    private static tterrag1112.life_in_the_village.Kingdom.Charters.CharterParams
            parseCharterParams(
                    tterrag1112.life_in_the_village.Kingdom.Charters.CharterType type,
                    String raw, java.util.UUID self) {
        try {
            return switch (type) {
                case TOLL_RIGHTS -> {
                    double rate = parseDouble(raw, 5.0) / 100.0;
                    yield new tterrag1112.life_in_the_village.Kingdom.Charters
                            .CharterParams.TollRights(self, rate);
                }
                case TAX_EXEMPTION -> {
                    double rate = parseDouble(raw, 50.0) / 100.0;
                    yield new tterrag1112.life_in_the_village.Kingdom.Charters
                            .CharterParams.TaxExemption(rate);
                }
                case MARKET_MONOPOLY -> new tterrag1112.life_in_the_village.Kingdom.Charters
                        .CharterParams.MarketMonopoly(
                        raw.isEmpty() ? "MARKET" : raw,
                        tterrag1112.life_in_the_village.Kingdom.Charters
                                .CharterParams.MarketMonopoly.Scope.KINGDOM,
                        java.util.Optional.empty());
                case ORDINATION_RIGHTS -> {
                    java.util.UUID order = raw.isEmpty()
                            ? self : java.util.UUID.fromString(raw);
                    yield new tterrag1112.life_in_the_village.Kingdom.Charters
                            .CharterParams.OrdinationRights(order);
                }
                case TITLE_GRANT -> new tterrag1112.life_in_the_village.Kingdom.Charters
                        .CharterParams.TitleGrant((int) parseDouble(raw, 1.0));
                case LAND_GRANT -> new tterrag1112.life_in_the_village.Kingdom.Charters
                        .CharterParams.LandGrant(0, 0, (int) parseDouble(raw, 4.0));
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static double parseDouble(String s, double fallback) {
        try { return s.isEmpty() ? fallback : Double.parseDouble(s); }
        catch (NumberFormatException e) { return fallback; }
    }

    // =========================================================================
    // Track D3.5D — Newsfeed section
    // =========================================================================

    private void drawNewsfeed(GuiGraphics g, int px, int py, int pw, int maxY) {
        g.drawString(font, "Kingdom Newsfeed", px, py, BookScreenColors.MID, false);
        g.fill(px, py + 10, px + pw, py + 11, BookScreenColors.BORDER);

        Kingdom k = Kingdom.ClientKingdomCache.getById(kingdomId).orElse(null);
        if (k == null) {
            g.drawString(font, "No kingdom data available.", px, py + 16,
                    BookScreenColors.LIGHT, false);
            return;
        }
        var feed = k.getHistory().getNewsfeed();
        if (feed.isEmpty()) {
            g.drawString(font, "No recent activity.", px, py + 16,
                    BookScreenColors.LIGHT, false);
            return;
        }

        int rowH = 18;
        int listTop = py + 18;
        int maxRows = (maxY - listTop) / rowH;
        // Newest first.
        int n = feed.size();
        int shown = Math.min(maxRows, n);
        for (int i = 0; i < shown; i++) {
            var e = feed.get(n - 1 - i);
            int by = listTop + i * rowH;
            int tagColor = colorForTag(e.tag());
            g.drawString(font, "[" + e.tag() + "]", px, by, tagColor, false);
            String text = e.summary();
            int tagWidth = font.width("[" + e.tag() + "] ");
            g.drawString(font, text, px + tagWidth, by, BookScreenColors.MID, false);
            g.drawString(font, "tick " + e.tick(), px, by + 9,
                    BookScreenColors.LIGHT, false);
        }
    }

    private static int colorForTag(String tag) {
        // Simple color-by-prefix scheme so newsfeed entries are
        // scannable: red for hostile (war/intrigue), green for
        // positive (charters/treaties ratified), grey otherwise.
        if (tag.startsWith("treaty.broken") || tag.startsWith("intrigue")
                || tag.startsWith("petition.denied")) {
            return 0xFFB54040;
        }
        if (tag.startsWith("treaty.ratified") || tag.startsWith("charter.granted")
                || tag.startsWith("petition.approved") || tag.startsWith("player.")) {
            return 0xFF407040;
        }
        return BookScreenColors.LIGHT;
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

    /**
     * Track D3.2b — Houses section. Lists each noble dynasty owned
     * by this kingdom with name, heraldry, prestige, head UUID
     * (truncated), and motto. Synced via {@code Kingdom.governance.houses};
     * no extra round-trip needed.
     *
     * <p>Live spouse / children / member-name resolution requires
     * a server-side detail packet (NPC names live on entities, not
     * persisted records). Documented as a follow-up — D3.2b ships
     * the data-visible variant; D3.3 / a follow-up slice can add
     * the detail roundtrip.
     */
    private void drawDynastyTree(GuiGraphics g, int px, int py, int pw, int maxY) {
        if (houses.isEmpty()) {
            g.drawString(font, "No noble houses founded yet.", px, py,
                    BookScreenColors.MID, false);
            g.drawString(font, "Houses appear once an eligible NPC reaches the",
                    px, py + 16, BookScreenColors.LIGHT, false);
            g.drawString(font, "founding threshold (prestige " + 30
                            + ", min nobility tier).",
                    px, py + 28, BookScreenColors.LIGHT, false);
            return;
        }

        g.drawString(font, "Noble Houses (" + houses.size() + ")",
                px, py, BookScreenColors.GOLD, false);
        g.fill(px, py + 10, px + pw, py + 11, BookScreenColors.BORDER);

        int y = py + 16;
        for (House h : houses) {
            if (y + 44 > maxY) break;

            // House name + prestige.
            g.drawString(font, h.name(), px, y, BookScreenColors.DARK, false);
            String prestigeStr = "Prestige: " + h.prestige();
            int pwid = font.width(prestigeStr);
            g.drawString(font, prestigeStr, px + pw - pwid, y,
                    BookScreenColors.MID, false);

            // Heraldry blazon line.
            g.drawString(font, h.heraldry().describe(),
                    px + 4, y + 11, BookScreenColors.LIGHT, false);

            // Head + founder lines.
            String head = h.isExtinct()
                    ? "(extinct line)"
                    : "Head: " + h.headUuid().get().toString().substring(0, 8);
            g.drawString(font, head, px + 4, y + 22,
                    h.isExtinct() ? BookScreenColors.RED_TXT : BookScreenColors.DARK,
                    false);
            String founder = "Founder: " + h.founderUuid().toString().substring(0, 8);
            int fw = font.width(founder);
            g.drawString(font, founder, px + pw - fw, y + 22,
                    BookScreenColors.MID, false);

            // Optional motto.
            if (!h.motto().isEmpty()) {
                g.drawString(font, "\"" + h.motto() + "\"", px + 4, y + 33,
                        BookScreenColors.LIGHT, false);
            }

            g.fill(px, y + 42, px + pw, y + 43, BookScreenColors.HIGHLIGHT);
            y += 48;
        }
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

        // Phase 5d — price-board row drill-in / back.
        if (handlePricesClick(mx, my)) return true;

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
