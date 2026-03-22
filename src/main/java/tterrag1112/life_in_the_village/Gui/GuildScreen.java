// src/main/java/tterrag1112/life_in_the_village/Village/Guild/GuildScreen.java
package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import tterrag1112.life_in_the_village.Guilds.Adventurer.*;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;
import tterrag1112.life_in_the_village.Networking.GuildActionPacket;
import tterrag1112.life_in_the_village.Networking.OpenGuildScreenPacket;

import java.util.List;
import java.util.UUID;

/**
 * Guild hall management screen — matches the book chrome of
 *
 * <h3>Sections</h3>
 * <ul>
 *   <li><b>Overview</b> — player rank, XP bar, quests completed,
 *       guild treasury, register button for unregistered players</li>
 *   <li><b>Quests</b> — available quests filtered to player rank,
 *       accept and turn-in buttons</li>
 *   <li><b>Party</b> — current party composition from guild perspective,
 *       shows member roles and levels</li>
 *   <li><b>Roster</b> — all registered guild members with rank and XP</li>
 * </ul>
 */
public class GuildScreen extends Screen {

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final int BOOK_W    = 420;
    private static final int BOOK_H    = 300;
    private static final int SIDEBAR_W = 130;
    private static final int PAGE_PAD  = 14;
    private static final int ROW_H     = 22;

    // ── Colors — same palette as CompanyManagementScreen ─────────────────────
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

    public enum Section { OVERVIEW, QUESTS, PARTY, ROSTER }

    // ── State ─────────────────────────────────────────────────────────────────
    private final OpenGuildScreenPacket data;
    private int bookX, bookY;
    public Section currentSection = Section.OVERVIEW;
    private int questScroll  = 0;
    private int rosterScroll = 0;

    // ── Constructor ───────────────────────────────────────────────────────────

    public GuildScreen(OpenGuildScreenPacket data) {
        super(Component.literal("Guild Hall"));
        this.data = data;
    }

    // ── Server-side open helper ───────────────────────────────────────────────

    public static void sendOpenPacket(
            net.minecraft.server.level.ServerPlayer player,
            UUID guildId,
            net.minecraft.server.level.ServerLevel level,
            PlayerGuildData guildData,
            tterrag1112.life_in_the_village.Networking.VillageSavedData vdata,
            PlayerPartySavedData partyData) {

        GuildData guild = vdata.getGuildById(guildId).orElse(null);
        if (guild == null) return;

        tterrag1112.life_in_the_village.Village.Village village =
                vdata.getVillageById(guild.villageId()).orElse(null);
        String villageName = village != null ? village.getName() : "Unknown";

        // Player membership
        GuildMember member = guildData.getMember(player.getUUID())
                .orElse(null);
        boolean registered = member != null;
        GuildRank rank = registered ? member.currentRank() : GuildRank.BRONZE;
        int xp           = registered ? member.xp() : 0;
        int xpToNext     = registered ? member.xpToNextRank() : GuildRank.BRONZE.getMinXp();
        int completed    = registered ? member.completedQuestIds().size() : 0;

        // Available quests
        List<OpenGuildScreenPacket.QuestEntry> questEntries =
                guildData.getAvailableQuestsForGuild(guildId, rank)
                        .stream()
                        .map(q -> new OpenGuildScreenPacket.QuestEntry(
                                q.getId(),
                                q.getTitle(),
                                q.getDescription(),
                                q.getType().name(),
                                q.getDifficulty().name(),
                                q.getCoinReward(),
                                q.getXpReward(),
                                q.getStatus().name()))
                        .toList();

        // Active quests (for turn-in)
        List<OpenGuildScreenPacket.QuestEntry> activeEntries =
                guildData.getActiveQuestsForPlayer(player.getUUID())
                        .stream()
                        .map(q -> new OpenGuildScreenPacket.QuestEntry(
                                q.getId(),
                                q.getTitle(),
                                q.getDescription(),
                                q.getType().name(),
                                q.getDifficulty().name(),
                                q.getCoinReward(),
                                q.getXpReward(),
                                q.getStatus().name()))
                        .toList();

        // Party members
        List<OpenGuildScreenPacket.PartyMemberEntry> partyEntries =
                partyData.getPartyForPlayer(player.getUUID())
                        .map(party -> party.getMembers().stream()
                                .map(m -> new OpenGuildScreenPacket.PartyMemberEntry(
                                        m.npcId(),
                                        m.name(),
                                        m.role().name(),
                                        m.level(),
                                        m.kills(),
                                        m.isAlive()))
                                .toList())
                        .orElse(List.of());

        // Roster
        List<OpenGuildScreenPacket.RosterEntry> rosterEntries =
                guildData.getAllMembersForGuild(guildId).stream()
                        .map(m -> new OpenGuildScreenPacket.RosterEntry(
                                m.playerId(),
                                m.playerName(),
                                m.currentRank().name(),
                                m.xp(),
                                m.completedQuestIds().size()))
                        .toList();

        net.neoforged.neoforge.network.PacketDistributor
                .sendToPlayer(player, new OpenGuildScreenPacket(
                        guildId, villageName,
                        registered, rank.name(),
                        xp, xpToNext, completed,
                        questEntries, activeEntries,
                        partyEntries, rosterEntries));
    }

    // =========================================================================
    // Screen lifecycle
    // =========================================================================

    @Override
    protected void init() {
        bookX = (width  - BOOK_W) / 2;
        bookY = (height - BOOK_H) / 2;
        buildWidgets();
    }

    @Override public boolean isPauseScreen() { return false; }

    // =========================================================================
    // Widgets
    // =========================================================================

    private void buildWidgets() {
        clearWidgets();
        int px   = bookX + SIDEBAR_W + PAGE_PAD;
        int py   = bookY + 36;
        int pw   = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;
        int maxY = bookY + BOOK_H - 34;

        switch (currentSection) {
            case OVERVIEW -> buildOverviewWidgets(px, py, pw);
            case QUESTS   -> buildQuestWidgets(px, py, pw, maxY);
            case PARTY    -> buildPartyWidgets(px, py, pw, maxY);
            case ROSTER   -> buildRosterWidgets(px, py, pw, maxY);
        }
    }

    // ── Overview ──────────────────────────────────────────────────────────────

    private void buildOverviewWidgets(int px, int py, int pw) {
        if (!data.registered()) {
            addRenderableWidget(Button.builder(
                            Component.literal("Join Guild"),
                            b -> sendAction(GuildActionPacket.ActionType.JOIN_GUILD))
                    .pos(px + pw / 2 - 40, py + 120).size(80, 20).build());
        }
    }

    // ── Quests ────────────────────────────────────────────────────────────────

    private void buildQuestWidgets(int px, int py, int pw, int maxY) {
        // Active quests — turn-in buttons
        int y = py + 14;
        for (OpenGuildScreenPacket.QuestEntry q : data.activeQuests()) {
            if (y + ROW_H > maxY - 14) break;
            boolean canTurnIn = "ACTIVE".equals(q.status());
            if (canTurnIn) {
                addRenderableWidget(Button.builder(
                                Component.literal("Turn In"),
                                b -> sendQuestAction(
                                        GuildActionPacket.ActionType.TURN_IN_QUEST,
                                        q.questId()))
                        .pos(px + pw - 52, y + 3).size(50, 14).build());
            }
            y += ROW_H + 2;
        }

        int dividerY = y + 4;

        // Available quests — accept buttons
        int avIdx = 0;
        for (OpenGuildScreenPacket.QuestEntry q : data.availableQuests()) {
            if (avIdx < questScroll) { avIdx++; continue; }
            if (y + ROW_H > maxY - 14) break;
            addRenderableWidget(Button.builder(
                            Component.literal("Accept"),
                            b -> sendQuestAction(
                                    GuildActionPacket.ActionType.ACCEPT_QUEST,
                                    q.questId()))
                    .pos(px + pw - 52, y + 3).size(50, 14).build());
            y += ROW_H + 2;
            avIdx++;
        }

        // Scroll buttons
        if (questScroll > 0) {
            addRenderableWidget(Button.builder(Component.literal("▲"),
                            b -> { questScroll--; buildWidgets(); })
                    .pos(px + pw - 12, py + 14).size(10, 10).build());
        }
        int visible = (maxY - py - 28) / (ROW_H + 2);
        if (questScroll + visible < data.availableQuests().size()) {
            addRenderableWidget(Button.builder(Component.literal("▼"),
                            b -> { questScroll++; buildWidgets(); })
                    .pos(px + pw - 12, maxY - 14).size(10, 10).build());
        }
    }

    // ── Party ─────────────────────────────────────────────────────────────────

    private void buildPartyWidgets(int px, int py, int pw, int maxY) {
        if (data.partyMembers().isEmpty()) {
            // No party — show form button
            addRenderableWidget(Button.builder(
                            Component.literal("Form Party"),
                            b -> {
                                // Close this screen and run command
                                onClose();
                                minecraft.player.connection.sendCommand(
                                        "party form");
                            })
                    .pos(px + pw / 2 - 40, py + 100).size(80, 16).build());
        }
    }

    // ── Roster ────────────────────────────────────────────────────────────────

    private void buildRosterWidgets(int px, int py, int pw, int maxY) {
        int y = py + 14;
        int idx = 0;
        for (OpenGuildScreenPacket.RosterEntry entry : data.roster()) {
            if (idx < rosterScroll) { idx++; continue; }
            if (y + ROW_H > maxY - 14) break;
            y += ROW_H + 2;
            idx++;
        }

        if (rosterScroll > 0) {
            addRenderableWidget(Button.builder(Component.literal("▲"),
                            b -> { rosterScroll--; buildWidgets(); })
                    .pos(px + pw - 12, py + 14).size(10, 10).build());
        }
        int vis = (maxY - py - 28) / (ROW_H + 2);
        if (rosterScroll + vis < data.roster().size()) {
            addRenderableWidget(Button.builder(Component.literal("▼"),
                            b -> { rosterScroll++; buildWidgets(); })
                    .pos(px + pw - 12, maxY - 14).size(10, 10).build());
        }
    }

    // =========================================================================
    // Render
    // =========================================================================

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88000000);
        drawBook(g);
        drawSidebar(g);
        drawPageContent(g, mx, my);
        super.render(g, mx, my, pt);
    }

    private void drawBook(GuiGraphics g) {
        g.fill(bookX + 3, bookY + 3,
                bookX + BOOK_W + 3, bookY + BOOK_H + 3, 0x44000000);
        g.fill(bookX, bookY,
                bookX + BOOK_W, bookY + BOOK_H, COL_PARCHMENT);
        g.renderOutline(bookX, bookY, BOOK_W, BOOK_H, COL_BORDER);
        g.renderOutline(bookX + 2, bookY + 2,
                BOOK_W - 4, BOOK_H - 4, COL_HIGHLIGHT);
        g.fill(bookX + SIDEBAR_W, bookY + 28,
                bookX + BOOK_W, bookY + 29, COL_BORDER);
        g.fill(bookX + SIDEBAR_W, bookY + BOOK_H - 30,
                bookX + BOOK_W, bookY + BOOK_H - 29, COL_BORDER);

        g.drawString(font, sectionLabel(currentSection),
                bookX + SIDEBAR_W + PAGE_PAD, bookY + 10,
                COL_DARK, false);
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
        g.fill(bookX, bookY,
                bookX + SIDEBAR_W, bookY + BOOK_H, COL_SIDEBAR);
        g.fill(bookX + SIDEBAR_W - 1, bookY,
                bookX + SIDEBAR_W, bookY + BOOK_H, COL_BORDER);

        // Guild name + village
        g.drawString(font, "\u2302 Guild Hall",
                bookX + 6, bookY + 8, COL_DARK, false);
        g.drawString(font, data.villageName(),
                bookX + 6, bookY + 18, COL_MID, false);
        g.fill(bookX + 4, bookY + 28,
                bookX + SIDEBAR_W - 4, bookY + 29, COL_BORDER);

        // Section nav
        int ny = bookY + 34;
        for (Section s : Section.values()) {
            boolean active = s == currentSection;
            if (active) {
                g.fill(bookX + 2, ny - 1,
                        bookX + SIDEBAR_W - 1, ny + 9, COL_HIGHLIGHT);
                g.fill(bookX + 2, ny - 1, bookX + 4, ny + 9, COL_GOLD);
            }
            g.drawString(font, (active ? "> " : "  ") + sectionLabel(s),
                    bookX + 6, ny,
                    active ? COL_DARK : COL_MID, false);
            ny += 18;
        }

        // Rank badge at bottom of sidebar
        int rankY = bookY + BOOK_H - 50;
        g.fill(bookX + 4, rankY,
                bookX + SIDEBAR_W - 4, rankY + 1, COL_BORDER);
        if (data.registered()) {
            g.drawString(font, "Rank",
                    bookX + 6, rankY + 4, COL_MID, false);
            g.drawString(font, data.rankName(),
                    bookX + 6, rankY + 14,
                    rankColor(data.rankName()), false);
            // XP progress bar
            int barW = SIDEBAR_W - 12;
            int filled = data.xpToNext() > 0
                    ? (int)((float)(barW)
                    * (1.0f - (float) data.xpToNext()
                    / (float)(data.xp() + data.xpToNext())))
                    : barW;
            g.fill(bookX + 6, rankY + 26,
                    bookX + 6 + barW, rankY + 32, COL_BORDER);
            g.fill(bookX + 6, rankY + 26,
                    bookX + 6 + filled, rankY + 32, COL_GOLD);
            g.drawString(font, data.xp() + " XP",
                    bookX + 6, rankY + 34, COL_LIGHT, false);
        } else {
            g.drawString(font, "Not a member",
                    bookX + 6, rankY + 10, COL_LIGHT, false);
        }
    }

    private void drawPageContent(GuiGraphics g, int mx, int my) {
        int px   = bookX + SIDEBAR_W + PAGE_PAD;
        int py   = bookY + 36;
        int pw   = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;
        int maxY = bookY + BOOK_H - 34;

        switch (currentSection) {
            case OVERVIEW -> drawOverview(g, px, py, pw, maxY);
            case QUESTS   -> drawQuests(g, px, py, pw, maxY);
            case PARTY    -> drawParty(g, px, py, pw, maxY);
            case ROSTER   -> drawRoster(g, px, py, pw, maxY);
        }
    }

    // ── Overview page ─────────────────────────────────────────────────────────

    private void drawOverview(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;

        if (!data.registered()) {
            g.drawCenteredString(font,
                    "You are not a guild member.",
                    px + pw / 2, y + 40, COL_MID);
            g.drawCenteredString(font,
                    "Speak to the guildmaster to join.",
                    px + pw / 2, y + 54, COL_LIGHT);
            g.drawCenteredString(font,
                    "Membership grants access to quests,",
                    px + pw / 2, y + 72, COL_LIGHT);
            g.drawCenteredString(font,
                    "party bonuses, and rank rewards.",
                    px + pw / 2, y + 84, COL_LIGHT);
            return;
        }

        // Rank and XP
        g.drawString(font, "Rank: " + data.rankName(),
                px, y, rankColor(data.rankName()), false);
        y += 12;
        g.drawString(font, "XP: " + data.xp()
                        + (data.xpToNext() > 0
                        ? " / +" + data.xpToNext() + " to next rank"
                        : " (Max rank)"),
                px, y, COL_MID, false);
        y += 12;
        g.fill(px, y, px + pw, y + 1, COL_BORDER);
        y += 8;

        // Stats
        g.drawString(font, "Quests Completed: " + data.questsCompleted(),
                px, y, COL_DARK, false);
        y += 12;

        // Active quests summary
        if (!data.activeQuests().isEmpty()) {
            g.fill(px, y, px + pw, y + 1, COL_BORDER);
            y += 6;
            g.drawString(font, "Active Contracts:",
                    px, y, COL_AMBER, false);
            y += 12;
            for (OpenGuildScreenPacket.QuestEntry q : data.activeQuests()) {
                if (y + 10 > maxY) break;
                g.drawString(font,
                        "\u2022 " + q.title()
                                + " [" + formatDifficulty(q.difficulty()) + "]",
                        px + 4, y, COL_DARK, false);
                y += 10;
            }
        }

        // Rank benefits hint
        y = maxY - 30;
        g.fill(px, y, px + pw, y + 1, COL_BORDER);
        y += 4;
        String benefit = rankBenefit(data.rankName());
        g.drawString(font, benefit, px, y, COL_LIGHT, false);
    }

    // ── Quests page ───────────────────────────────────────────────────────────

    private void drawQuests(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;

        // Active quests first
        if (!data.activeQuests().isEmpty()) {
            g.drawString(font, "Active Contracts",
                    px, y, COL_AMBER, false);
            g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
            y += 14;

            for (OpenGuildScreenPacket.QuestEntry q : data.activeQuests()) {
                if (y + ROW_H > maxY - 14) break;

                g.fill(px, y, px + pw, y + ROW_H, COL_GREEN_BG);
                g.renderOutline(px, y, pw, ROW_H, COL_BORDER);
                g.drawString(font, q.title(),
                        px + 3, y + 3, COL_DARK, false);
                g.drawString(font,
                        formatDifficulty(q.difficulty())
                                + " \u2022 " + q.type(),
                        px + 3, y + 13, COL_MID, false);
                // Turn-in button placed by buildQuestWidgets
                y += ROW_H + 2;
            }
            y += 4;
            g.fill(px, y, px + pw, y + 1, COL_BORDER);
            y += 6;
        }

        // Available quests
        g.drawString(font, "Available Quests",
                px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 14;

        if (data.availableQuests().isEmpty()) {
            g.drawCenteredString(font,
                    "No quests available for your rank.",
                    px + pw / 2, y + 20, COL_MID);
            g.drawCenteredString(font,
                    "Check back tomorrow.",
                    px + pw / 2, y + 34, COL_LIGHT);
            return;
        }

        int avIdx = 0;
        for (OpenGuildScreenPacket.QuestEntry q : data.availableQuests()) {
            if (avIdx < questScroll) { avIdx++; continue; }
            if (y + ROW_H > maxY - 14) break;

            int diffColor = difficultyColor(q.difficulty());
            g.fill(px, y, px + pw, y + ROW_H, COL_PARCHMENT);
            g.renderOutline(px, y, pw, ROW_H, COL_BORDER);
            g.fill(px, y, px + 3, y + ROW_H, diffColor); // difficulty stripe
            g.drawString(font, q.title(),
                    px + 6, y + 3, COL_DARK, false);
            g.drawString(font,
                    formatDifficulty(q.difficulty())
                            + " \u2022 "
                            + CoinRenderer.format(q.coinReward())
                            + " \u2022 " + q.xpReward() + " XP",
                    px + 6, y + 13, COL_MID, false);
            // Accept button placed by buildQuestWidgets
            y += ROW_H + 2;
            avIdx++;
        }
    }

    // ── Party page ────────────────────────────────────────────────────────────

    private void drawParty(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;

        if (data.partyMembers().isEmpty()) {
            g.drawCenteredString(font, "No active party.",
                    px + pw / 2, y + 30, COL_MID);
            g.drawCenteredString(font,
                    "Use /party form [name] to create one.",
                    px + pw / 2, y + 44, COL_LIGHT);
            g.drawCenteredString(font,
                    "Then /party invite near an adventurer.",
                    px + pw / 2, y + 58, COL_LIGHT);

            // Role guide
            y += 80;
            g.fill(px, y, px + pw, y + 1, COL_BORDER);
            y += 6;
            g.drawString(font, "Combat Roles:", px, y, COL_MID, false);
            y += 12;

            for (CombatRole role
                    : CombatRole.values()) {
                if (y + 10 > maxY) break;
                g.drawString(font,
                        role.symbol + " " + role.getDisplayName()
                                + " — " + role.description,
                        px + 2, y, COL_DARK, false);
                y += 10;
            }
            return;
        }

        // Party header
        g.drawString(font, "Current Party",
                px, y, COL_DARK, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 14;

        // Column headers
        g.fill(px, y, px + pw, y + 10, 0xFFE8E0C8);
        g.drawString(font, "Member",  px + 2,       y + 1, COL_MID, false);
        g.drawString(font, "Role",    px + 110,      y + 1, COL_MID, false);
        g.drawString(font, "Lv",      px + pw - 40,  y + 1, COL_MID, false);
        g.drawString(font, "Kills",   px + pw - 25,  y + 1, COL_MID, false);
        y += 12;

        for (OpenGuildScreenPacket.PartyMemberEntry m : data.partyMembers()) {
            if (y + ROW_H > maxY) break;

            int bg = m.isAlive() ? COL_PARCHMENT : COL_RED_BG;
            g.fill(px, y, px + pw, y + ROW_H, bg);
            g.renderOutline(px, y, pw, ROW_H, COL_BORDER);

            // Role symbol + name
            CombatRole role =
                    CombatRole.valueOf(m.roleName());

            g.drawString(font,
                    role.symbol + " " + truncate(m.name(), 12),
                    px + 2, y + (ROW_H - 8) / 2, COL_DARK, false);
            g.drawString(font,
                    role.getDisplayName(),
                    px + 110, y + (ROW_H - 8) / 2, COL_MID, false);
            g.drawString(font,
                    String.valueOf(m.level()),
                    px + pw - 40, y + (ROW_H - 8) / 2, COL_DARK, false);
            g.drawString(font,
                    String.valueOf(m.kills()),
                    px + pw - 25, y + (ROW_H - 8) / 2, COL_DARK, false);

            if (!m.isAlive()) {
                g.drawString(font, "[DEAD]",
                        px + pw - 60, y + (ROW_H - 8) / 2,
                        COL_RED_TXT, false);
            }
            y += ROW_H + 2;
        }

        // XP multiplier
        y += 4;
        if (y + 10 < maxY) {
            // Rough multiplier — display only, real calc is server-side
            int unique = (int) data.partyMembers().stream()
                    .filter(OpenGuildScreenPacket.PartyMemberEntry::isAlive)
                    .map(OpenGuildScreenPacket.PartyMemberEntry::roleName)
                    .distinct().count();
            String bonus = unique >= 4 ? "+25%"
                    : unique >= 3 ? "+15%"
                    : unique >= 2 ? "+5%"
                    : "+0%";
            g.drawString(font,
                    "Composition bonus: " + bonus + " XP",
                    px, y, COL_GOLD, false);
        }
    }

    // ── Roster page ───────────────────────────────────────────────────────────

    private void drawRoster(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;
        g.drawString(font, "Guild Members",
                px, y, COL_MID, false);
        g.fill(px, y + 10, px + pw, y + 11, COL_BORDER);
        y += 14;

        // Column headers
        g.fill(px, y, px + pw, y + 10, 0xFFE8E0C8);
        g.drawString(font, "Player",    px + 2,       y + 1, COL_MID, false);
        g.drawString(font, "Rank",      px + 120,      y + 1, COL_MID, false);
        g.drawString(font, "Quests",    px + pw - 40,  y + 1, COL_MID, false);
        y += 12;

        if (data.roster().isEmpty()) {
            g.drawCenteredString(font, "No members registered.",
                    px + pw / 2, y + 20, COL_MID);
            return;
        }

        int idx = 0;
        for (OpenGuildScreenPacket.RosterEntry entry : data.roster()) {
            if (idx < rosterScroll) { idx++; continue; }
            if (y + ROW_H > maxY - 14) break;

            boolean isSelf = entry.playerId().equals(
                    minecraft.player.getUUID());
            int bg = isSelf ? COL_HIGHLIGHT : COL_PARCHMENT;
            g.fill(px, y, px + pw, y + ROW_H, bg);
            g.renderOutline(px, y, pw, ROW_H, COL_BORDER);

            g.drawString(font,
                    truncate(entry.playerName(), 16),
                    px + 2, y + (ROW_H - 8) / 2, COL_DARK, false);
            g.drawString(font, entry.rankName(),
                    px + 120, y + (ROW_H - 8) / 2,
                    rankColor(entry.rankName()), false);
            g.drawString(font,
                    String.valueOf(entry.questsCompleted()),
                    px + pw - 40, y + (ROW_H - 8) / 2,
                    COL_DARK, false);

            y += ROW_H + 2;
            idx++;
        }
    }

    // =========================================================================
    // Mouse — sidebar navigation
    // =========================================================================

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (consumed) return super.mouseClicked(event, consumed);
        double mx = event.x(), my = event.y();
        int ny = bookY + 34;
        for (Section s : Section.values()) {
            if (mx >= bookX + 2 && mx < bookX + SIDEBAR_W - 1
                    && my >= ny - 1 && my < ny + 9) {
                currentSection = s;
                questScroll  = 0;
                rosterScroll = 0;
                buildWidgets();
                return true;
            }
            ny += 18;
        }
        return super.mouseClicked(event, consumed);
    }

    // =========================================================================
    // Packet helpers
    // =========================================================================

    private void sendAction(GuildActionPacket.ActionType type) {
        ClientPacketDistributor
                .sendToServer(new GuildActionPacket(
                        type, data.guildId(),
                        minecraft.player.getUUID(),
                        null, ""));
    }

    private void sendQuestAction(GuildActionPacket.ActionType type,
                                 UUID questId) {
        ClientPacketDistributor
                .sendToServer(new GuildActionPacket(
                        type, data.guildId(),
                        minecraft.player.getUUID(),
                        questId, ""));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String sectionLabel(Section s) {
        return switch (s) {
            case OVERVIEW -> "Overview";
            case QUESTS   -> "Quests ("
                    + data.availableQuests().size() + ")";
            case PARTY    -> "My Party";
            case ROSTER   -> "Roster ("
                    + data.roster().size() + ")";
        };
    }

    private int rankColor(String rank) {
        return switch (rank) {
            case "BRONZE"   -> 0xFFCD7F32;
            case "SILVER"   -> 0xFFC0C0C0;
            case "GOLD"     -> COL_GOLD;
            case "PLATINUM" -> 0xFF00CED1;
            case "DIAMOND"  -> 0xFF00BFFF;
            default         -> COL_MID;
        };
    }

    private int difficultyColor(String diff) {
        return switch (diff) {
            case "EASY"      -> COL_GREEN_TXT;
            case "MEDIUM"    -> COL_AMBER;
            case "HARD"      -> COL_RED_TXT;
            case "ELITE"     -> 0xFFAA00FF;
            case "LEGENDARY" -> 0xFFFF4400;
            default          -> COL_MID;
        };
    }

    private String formatDifficulty(String diff) {
        return diff.charAt(0) + diff.substring(1).toLowerCase();
    }

    private String rankBenefit(String rank) {
        return switch (rank) {
            case "BRONZE"   -> "Tip: Complete Easy quests to earn Silver rank.";
            case "SILVER"   -> "Medium quests now available.";
            case "GOLD"     -> "Hard quests available. Party bonus active.";
            case "PLATINUM" -> "Elite contracts available.";
            case "DIAMOND"  -> "Legendary contracts available. Maximum rank!";
            default         -> "";
        };
    }

    private String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "\u2026";
    }
}