package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import tterrag1112.life_in_the_village.Guilds.Adventurer.*;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;
import tterrag1112.life_in_the_village.Gui.Framework.*;
import tterrag1112.life_in_the_village.Networking.GuildActionPacket;
import tterrag1112.life_in_the_village.Networking.OpenGuildScreenPacket;

import java.util.List;
import java.util.UUID;

public class GuildScreen extends Screen {

    private static final int BOOK_W = 420, BOOK_H = 300, SIDEBAR_W = 130, PAGE_PAD = 14, ROW_H = 22;

    public enum Section { OVERVIEW, QUESTS, PARTY, ROSTER }

    private final OpenGuildScreenPacket data;
    private int bookX, bookY;
    public Section currentSection = Section.OVERVIEW;

    private final TooltipLayer tooltips = new TooltipLayer();
    private Sidebar<Section> sidebar;
    private ScrollList<OpenGuildScreenPacket.QuestEntry> questList;
    private ScrollList<OpenGuildScreenPacket.RosterEntry> rosterList;


    public GuildScreen(OpenGuildScreenPacket data) {
        super(Component.literal("Guild Hall"));
        this.data = data;
    }

    public static void sendOpenPacket(
            net.minecraft.server.level.ServerPlayer player,
            UUID guildId,
            net.minecraft.server.level.ServerLevel level,
            PlayerGuildData guildData,
            tterrag1112.life_in_the_village.Networking.VillageSavedData vdata,
            PlayerPartySavedData partyData) {

        GuildData guild = vdata.getGuildById(guildId).orElse(null);
        if (guild == null) return;
        String villageName = vdata.getVillageById(guild.villageId())
                .map(v -> v.getName()).orElse("Unknown");

        GuildMember member = guildData.getMember(player.getUUID()).orElse(null);
        boolean registered = member != null;
        GuildRank rank = registered ? member.currentRank() : GuildRank.BRONZE;
        int xp       = registered ? member.xp() : 0;
        int xpToNext = registered ? member.xpToNextRank() : GuildRank.BRONZE.getMinXp();
        int completed = registered ? member.completedQuestIds().size() : 0;

        // F2b-2 — quests now come from the F2 QuestSavedData store (guild pool + the
        // player's active list), mapped onto the unchanged packet/GUI quest entry shape.
        List<OpenGuildScreenPacket.QuestEntry> available =
                GuildQuests.available(level, guildId, rank).stream()
                        .map(GuildScreen::toEntry).toList();

        List<OpenGuildScreenPacket.QuestEntry> active =
                GuildQuests.activeFor(level, player.getUUID()).stream()
                        .map(GuildScreen::toEntry).toList();

        List<OpenGuildScreenPacket.PartyMemberEntry> party =
                partyData.getPartyForPlayer(player.getUUID())
                        .map(p -> p.getMembers().stream()
                                .map(m -> new OpenGuildScreenPacket.PartyMemberEntry(m.npcId(), m.name(), m.role().name(), m.level(), m.kills(), m.isAlive()))
                                .toList())
                        .orElse(List.of());

        List<OpenGuildScreenPacket.RosterEntry> roster =
                guildData.getAllMembersForGuild(guildId).stream()
                        .map(m -> new OpenGuildScreenPacket.RosterEntry(m.playerId(), m.playerName(), m.currentRank().name(), m.xp(), m.completedQuestIds().size()))
                        .toList();

        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new OpenGuildScreenPacket(guildId, villageName, registered, rank.name(),
                        xp, xpToNext, completed, available, active, party, roster));
    }

    /** Maps an F2 guild quest onto the (unchanged) packet/GUI quest-entry shape. */
    private static OpenGuildScreenPacket.QuestEntry toEntry(
            tterrag1112.life_in_the_village.Quests.Quest q) {
        tterrag1112.life_in_the_village.Quests.QuestDifficulty diff = q.difficulty();
        return new OpenGuildScreenPacket.QuestEntry(
                q.questId(), q.title(), q.description(),
                GuildQuests.kindLabel(q), diff.name(),
                diff.baseCoinReward(), diff.baseXp(), q.status().name());
    }

    @Override
    protected void init() {
        bookX = (width - BOOK_W) / 2;
        bookY = (height - BOOK_H) / 2;

        sidebar = new Sidebar<>(bookX + 2, bookY + 34, SIDEBAR_W - 2, 18,
                List.of(
                        new Sidebar.Entry<>(Section.OVERVIEW, "Overview", true),
                        new Sidebar.Entry<>(Section.QUESTS,   "Quests (" + data.availableQuests().size() + ")", true),
                        new Sidebar.Entry<>(Section.PARTY,    "My Party", true),
                        new Sidebar.Entry<>(Section.ROSTER,   "Roster (" + data.roster().size() + ")", true)
                ),
                () -> currentSection,
                s -> { currentSection = s; buildWidgets(); });

        int px = bookX + SIDEBAR_W + PAGE_PAD, py = bookY + 36;
        int maxY = bookY + BOOK_H - 34;
        int activeH = data.activeQuests().isEmpty() ? 0
                : 14 + data.activeQuests().size() * (ROW_H + 2) + 10;
        int questListY = py + activeH + 14;
        questList  = new ScrollList<>(px, questListY, BOOK_W - SIDEBAR_W - PAGE_PAD * 2,
                Math.max(0, maxY - questListY), ROW_H + 2, data.availableQuests(),
                this::drawAvailableQuestRow, this::onAvailableQuestClick);
        rosterList = new ScrollList<>(px, py + 26, BOOK_W - SIDEBAR_W - PAGE_PAD * 2,
                maxY - py - 26, ROW_H + 2, data.roster(), this::drawRosterRow, null);

        buildWidgets();
    }

    @Override public boolean isPauseScreen() { return false; }

    private void buildWidgets() {
        clearWidgets();
        int px = bookX + SIDEBAR_W + PAGE_PAD;
        int pw = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;
        int py = bookY + 36;
        if (currentSection == Section.OVERVIEW && !data.registered()) {
            addRenderableWidget(StyledButton.builder(Component.literal("Join Guild"),
                    b -> sendAction(GuildActionPacket.ActionType.JOIN_GUILD))
                    .pos(px + pw / 2 - 40, py + 120).size(80, 20).build());
        }
        if (currentSection == Section.PARTY && data.partyMembers().isEmpty()) {
            addRenderableWidget(StyledButton.builder(Component.literal("Form Party"),
                    b -> { onClose(); minecraft.player.connection.sendCommand("party form"); })
                    .pos(px + pw / 2 - 40, py + 100).size(80, 16).build());
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        tooltips.reset();
        g.fill(0, 0, width, height, 0x88000000);
        Chrome.draw(g, bookX, bookY, Chrome.BOOK, Chrome.PARCHMENT);
        Chrome.drawSidebarBg(g, bookX, bookY, Chrome.BOOK);
        drawSidebarContent(g);
        sidebar.render(g, mx, my);
        drawPageChrome(g);
        drawPageContent(g, mx, my);
        super.render(g, mx, my, pt);
        tooltips.flush(g);
    }

    private void drawSidebarContent(GuiGraphics g) {
        g.drawString(font, "\u2302 Guild Hall", bookX + 6, bookY + 8,  BookScreenColors.DARK, false);
        g.drawString(font, data.villageName(),  bookX + 6, bookY + 18, BookScreenColors.MID,  false);
        g.fill(bookX + 4, bookY + 28, bookX + SIDEBAR_W - 4, bookY + 29, BookScreenColors.BORDER);

        int rankY = bookY + BOOK_H - 50;
        g.fill(bookX + 4, rankY, bookX + SIDEBAR_W - 4, rankY + 1, BookScreenColors.BORDER);
        if (data.registered()) {
            g.drawString(font, "Rank",          bookX + 6, rankY + 4,  BookScreenColors.MID,  false);
            g.drawString(font, data.rankName(), bookX + 6, rankY + 14, rankColor(data.rankName()), false);
            float f = data.xpToNext() > 0 ? 1f - (float) data.xpToNext() / (data.xp() + data.xpToNext()) : 1f;
            ProgressBar.drawDefault(g, bookX + 6, rankY + 26, SIDEBAR_W - 12, 6, f);
            g.drawString(font, data.xp() + " XP", bookX + 6, rankY + 34, BookScreenColors.LIGHT, false);
        } else {
            g.drawString(font, "Not a member", bookX + 6, rankY + 10, BookScreenColors.LIGHT, false);
        }
    }

    private void drawPageChrome(GuiGraphics g) {
        g.fill(bookX + SIDEBAR_W, bookY + 28, bookX + BOOK_W, bookY + 29, BookScreenColors.BORDER);
        g.fill(bookX + SIDEBAR_W, bookY + BOOK_H - 30, bookX + BOOK_W, bookY + BOOK_H - 29, BookScreenColors.BORDER);
        g.drawString(font, sectionLabel(currentSection),
                bookX + SIDEBAR_W + PAGE_PAD, bookY + 10, BookScreenColors.DARK, false);
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
        switch (currentSection) {
            case OVERVIEW -> drawOverview(g, px, py, pw, maxY);
            case QUESTS   -> drawQuestsSection(g, px, py, pw, maxY, mx, my);
            case PARTY    -> drawParty(g, px, py, pw, maxY);
            case ROSTER   -> drawRoster(g, px, py, pw, mx, my);
        }
    }

    private void drawOverview(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;
        if (!data.registered()) {
            g.drawCenteredString(font, "You are not a guild member.",        px + pw / 2, y + 40, BookScreenColors.MID);
            g.drawCenteredString(font, "Speak to the guildmaster to join.",  px + pw / 2, y + 54, BookScreenColors.LIGHT);
            g.drawCenteredString(font, "Membership grants access to quests,",px + pw / 2, y + 72, BookScreenColors.LIGHT);
            g.drawCenteredString(font, "party bonuses, and rank rewards.",   px + pw / 2, y + 84, BookScreenColors.LIGHT);
            return;
        }
        Pill.draw(g, font, px, y, data.rankName(), rankBgColor(data.rankName()), rankColor(data.rankName()));
        y += 16;
        g.drawString(font, "XP: " + data.xp()
                + (data.xpToNext() > 0 ? " / +" + data.xpToNext() + " to next rank" : " (Max rank)"),
                px, y, BookScreenColors.MID, false);
        y += 10;
        float f = data.xpToNext() > 0 ? 1f - (float) data.xpToNext() / (data.xp() + data.xpToNext()) : 1f;
        ProgressBar.drawDefault(g, px, y, pw, 6, f);
        y += 12;
        g.fill(px, y, px + pw, y + 1, BookScreenColors.BORDER);
        y += 8;
        StatBox.draw(g, font, px, y, 110, 22, "Quests Completed", String.valueOf(data.questsCompleted()));
        y += 30;
        if (!data.activeQuests().isEmpty()) {
            g.fill(px, y, px + pw, y + 1, BookScreenColors.BORDER);
            y += 6;
            g.drawString(font, "Active Contracts:", px, y, BookScreenColors.AMBER, false);
            y += 12;
            for (var q : data.activeQuests()) {
                if (y + 10 > maxY) break;
                g.drawString(font, "\u2022 " + q.title() + " [" + formatDifficulty(q.difficulty()) + "]",
                        px + 4, y, BookScreenColors.DARK, false);
                y += 10;
            }
        }
        g.fill(px, maxY - 30, px + pw, maxY - 29, BookScreenColors.BORDER);
        g.drawString(font, rankBenefit(data.rankName()), px, maxY - 26, BookScreenColors.LIGHT, false);
    }

    private void drawQuestsSection(GuiGraphics g, int px, int py, int pw, int maxY, int mx, int my) {
        int y = py;
        if (!data.activeQuests().isEmpty()) {
            g.drawString(font, "Active Contracts", px, y, BookScreenColors.AMBER, false);
            g.fill(px, y + 10, px + pw, y + 11, BookScreenColors.BORDER);
            y += 14;
            for (var q : data.activeQuests()) {
                if (y + ROW_H > maxY - 14) break;
                g.fill(px, y, px + pw, y + ROW_H, BookScreenColors.GREEN_BG);
                g.renderOutline(px, y, pw, ROW_H, BookScreenColors.BORDER);
                g.drawString(font, q.title(), px + 3, y + 3,  BookScreenColors.DARK, false);
                g.drawString(font, formatDifficulty(q.difficulty()) + " \u2022 " + q.type(),
                        px + 3, y + 13, BookScreenColors.MID, false);
                int bx = px + pw - 52;
                boolean bHov = mx >= bx && mx < bx + 50 && my >= y + 3 && my < y + 17;
                drawMiniButton(g, bx, y + 3, 50, 14, "Turn In", bHov);
                if (bHov) tooltips.queue(mx, my + 12, List.of(Component.literal(q.description())));
                y += ROW_H + 2;
            }
            y += 4;
            g.fill(px, y, px + pw, y + 1, BookScreenColors.BORDER);
            y += 6;
        }
        g.drawString(font, "Available Quests", px, y, BookScreenColors.MID, false);
        g.fill(px, y + 10, px + pw, y + 11, BookScreenColors.BORDER);
        if (data.availableQuests().isEmpty()) {
            g.drawCenteredString(font, "No quests available for your rank.", px + pw / 2, y + 28, BookScreenColors.MID);
            g.drawCenteredString(font, "Check back tomorrow.",               px + pw / 2, y + 42, BookScreenColors.LIGHT);
        } else {
            questList.render(g, mx, my);
        }
    }

    private void drawAvailableQuestRow(GuiGraphics g, int rx, int ry, int rw, int rh,
                                       OpenGuildScreenPacket.QuestEntry q, boolean hovered) {
        g.fill(rx, ry, rx + rw, ry + rh - 2, BookScreenColors.PARCHMENT);
        g.renderOutline(rx, ry, rw, rh - 2, BookScreenColors.BORDER);
        g.fill(rx, ry, rx + 3, ry + rh - 2, difficultyColor(q.difficulty()));
        g.drawString(font, q.title(), rx + 6, ry + 3, BookScreenColors.DARK, false);
        g.drawString(font, formatDifficulty(q.difficulty()) + " \u2022 "
                + CoinRenderer.format(q.coinReward()) + " \u2022 " + q.xpReward() + " XP",
                rx + 6, ry + 13, BookScreenColors.MID, false);
        drawMiniButton(g, rx + rw - 52, ry + 3, 50, 14, "Accept", hovered);
        if (hovered) tooltips.queue(rx + 6, ry + rh, List.of(Component.literal(q.description())));
    }

    private boolean onAvailableQuestClick(OpenGuildScreenPacket.QuestEntry q, int btn, double relX, double relY) {
        sendQuestAction(GuildActionPacket.ActionType.ACCEPT_QUEST, q.questId());
        return true;
    }

    private void drawMiniButton(GuiGraphics g, int bx, int by, int bw, int bh, String label, boolean hov) {
        g.fill(bx, by, bx + bw, by + bh, hov ? BookScreenColors.HIGHLIGHT : BookScreenColors.PARCHMENT);
        g.renderOutline(bx, by, bw, bh, BookScreenColors.BORDER);
        int tw = font.width(label);
        g.drawString(font, label, bx + (bw - tw) / 2, by + (bh - 8) / 2, BookScreenColors.DARK, false);
    }

    private void drawParty(GuiGraphics g, int px, int py, int pw, int maxY) {
        int y = py;
        if (data.partyMembers().isEmpty()) {
            g.drawCenteredString(font, "No active party.",                        px + pw / 2, y + 30, BookScreenColors.MID);
            g.drawCenteredString(font, "Use /party form [name] to create one.",   px + pw / 2, y + 44, BookScreenColors.LIGHT);
            g.drawCenteredString(font, "Then /party invite near an adventurer.", px + pw / 2, y + 58, BookScreenColors.LIGHT);
            y += 80;
            g.fill(px, y, px + pw, y + 1, BookScreenColors.BORDER); y += 6;
            g.drawString(font, "Combat Roles:", px, y, BookScreenColors.MID, false); y += 12;
            for (CombatRole role : CombatRole.values()) {
                if (y + 10 > maxY) break;
                g.drawString(font, role.symbol + " " + role.getDisplayName() + " — " + role.description,
                        px + 2, y, BookScreenColors.DARK, false);
                y += 10;
            }
            return;
        }
        g.drawString(font, "Current Party", px, y, BookScreenColors.DARK, false);
        g.fill(px, y + 10, px + pw, y + 11, BookScreenColors.BORDER); y += 14;
        g.fill(px, y, px + pw, y + 10, 0xFFE8E0C8);
        g.drawString(font, "Member", px + 2,      y + 1, BookScreenColors.MID, false);
        g.drawString(font, "Role",   px + 110,     y + 1, BookScreenColors.MID, false);
        g.drawString(font, "Lv",     px + pw - 40, y + 1, BookScreenColors.MID, false);
        g.drawString(font, "Kills",  px + pw - 25, y + 1, BookScreenColors.MID, false);
        y += 12;
        for (var m : data.partyMembers()) {
            if (y + ROW_H > maxY) break;
            g.fill(px, y, px + pw, y + ROW_H, m.isAlive() ? BookScreenColors.PARCHMENT : BookScreenColors.RED_BG);
            g.renderOutline(px, y, pw, ROW_H, BookScreenColors.BORDER);
            CombatRole role = CombatRole.valueOf(m.roleName());
            int mid = y + (ROW_H - 8) / 2;
            g.drawString(font, role.symbol + " " + truncate(m.name(), 12), px + 2,      mid, BookScreenColors.DARK,    false);
            g.drawString(font, role.getDisplayName(),                       px + 110,     mid, BookScreenColors.MID,     false);
            g.drawString(font, String.valueOf(m.level()),                   px + pw - 40, mid, BookScreenColors.DARK,    false);
            g.drawString(font, String.valueOf(m.kills()),                   px + pw - 25, mid, BookScreenColors.DARK,    false);
            if (!m.isAlive()) g.drawString(font, "[DEAD]", px + pw - 60, mid, BookScreenColors.RED_TXT, false);
            y += ROW_H + 2;
        }
        y += 4;
        if (y + 10 < maxY) {
            int unique = (int) data.partyMembers().stream()
                    .filter(OpenGuildScreenPacket.PartyMemberEntry::isAlive)
                    .map(OpenGuildScreenPacket.PartyMemberEntry::roleName).distinct().count();
            g.drawString(font, "Composition bonus: "
                    + (unique >= 4 ? "+25%" : unique >= 3 ? "+15%" : unique >= 2 ? "+5%" : "+0%") + " XP",
                    px, y, BookScreenColors.GOLD, false);
        }
    }

    private void drawRoster(GuiGraphics g, int px, int py, int pw, int mx, int my) {
        int y = py;
        g.drawString(font, "Guild Members", px, y, BookScreenColors.MID, false);
        g.fill(px, y + 10, px + pw, y + 11, BookScreenColors.BORDER); y += 14;
        g.fill(px, y, px + pw, y + 10, 0xFFE8E0C8);
        g.drawString(font, "Player",  px + 2,       y + 1, BookScreenColors.MID, false);
        g.drawString(font, "Rank",    px + 120,      y + 1, BookScreenColors.MID, false);
        g.drawString(font, "Quests",  px + pw - 40,  y + 1, BookScreenColors.MID, false);
        if (data.roster().isEmpty()) {
            g.drawCenteredString(font, "No members registered.", px + pw / 2, py + 34, BookScreenColors.MID);
        } else {
            rosterList.render(g, mx, my);
        }
    }

    private void drawRosterRow(GuiGraphics g, int rx, int ry, int rw, int rh,
                               OpenGuildScreenPacket.RosterEntry e, boolean hovered) {
        g.fill(rx, ry, rx + rw, ry + rh - 2,
                e.playerId().equals(minecraft.player.getUUID()) ? BookScreenColors.HIGHLIGHT : BookScreenColors.PARCHMENT);
        g.renderOutline(rx, ry, rw, rh - 2, BookScreenColors.BORDER);
        int mid = ry + (ROW_H - 8) / 2;
        g.drawString(font, truncate(e.playerName(), 16), rx + 2,       mid, BookScreenColors.DARK,               false);
        g.drawString(font, e.rankName(),                 rx + 120,      mid, rankColor(e.rankName()),              false);
        g.drawString(font, String.valueOf(e.questsCompleted()), rx + rw - 40, mid, BookScreenColors.DARK,         false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (consumed) return super.mouseClicked(event, consumed);
        double mx = event.x(), my = event.y();
        if (sidebar.mouseClicked(mx, my)) return true;
        if (currentSection == Section.QUESTS) {
            if (!data.activeQuests().isEmpty()) {
                int px = bookX + SIDEBAR_W + PAGE_PAD, pw = BOOK_W - SIDEBAR_W - PAGE_PAD * 2;
                int y = bookY + 36 + 14, maxY = bookY + BOOK_H - 34;
                for (var q : data.activeQuests()) {
                    if (y + ROW_H > maxY - 14) break;
                    int bx = px + pw - 52;
                    if (mx >= bx && mx < bx + 50 && my >= y + 3 && my < y + 17) {
                        sendQuestAction(GuildActionPacket.ActionType.TURN_IN_QUEST, q.questId());
                        return true;
                    }
                    y += ROW_H + 2;
                }
            }
            if (questList.mouseClicked(mx, my, event.button())) return true;
        }
        return super.mouseClicked(event, consumed);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (currentSection == Section.QUESTS  && questList.mouseScrolled(mx, my, dy))  return true;
        if (currentSection == Section.ROSTER  && rosterList.mouseScrolled(mx, my, dy)) return true;
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (sidebar.keyPressed(event.key())) return true;
        return super.keyPressed(event);
    }

    private void sendAction(GuildActionPacket.ActionType type) {
        ClientPacketDistributor.sendToServer(new GuildActionPacket(
                type, data.guildId(), minecraft.player.getUUID(), null, ""));
    }

    private void sendQuestAction(GuildActionPacket.ActionType type, UUID questId) {
        ClientPacketDistributor.sendToServer(new GuildActionPacket(
                type, data.guildId(), minecraft.player.getUUID(), questId, ""));
    }

    private String sectionLabel(Section s) {
        return switch (s) {
            case OVERVIEW -> "Overview";
            case QUESTS   -> "Quests (" + data.availableQuests().size() + ")";
            case PARTY    -> "My Party";
            case ROSTER   -> "Roster (" + data.roster().size() + ")";
        };
    }

    private int rankColor(String rank) {
        return switch (rank) {
            case "BRONZE"   -> 0xFFCD7F32;
            case "SILVER"   -> 0xFFC0C0C0;
            case "GOLD"     -> BookScreenColors.GOLD;
            case "PLATINUM" -> 0xFF00CED1;
            case "DIAMOND"  -> 0xFF00BFFF;
            default         -> BookScreenColors.MID;
        };
    }

    private int rankBgColor(String rank) {
        return switch (rank) {
            case "BRONZE"   -> 0x33CD7F32;
            case "SILVER"   -> 0x33C0C0C0;
            case "GOLD"     -> 0x33B8860B;
            case "PLATINUM" -> 0x3300CED1;
            case "DIAMOND"  -> 0x3300BFFF;
            default         -> BookScreenColors.SIDEBAR;
        };
    }

    private int difficultyColor(String diff) {
        return switch (diff) {
            case "EASY"      -> BookScreenColors.GREEN_TXT;
            case "MEDIUM"    -> BookScreenColors.AMBER;
            case "HARD"      -> BookScreenColors.RED_TXT;
            case "ELITE"     -> 0xFFAA00FF;
            case "LEGENDARY" -> 0xFFFF4400;
            default          -> BookScreenColors.MID;
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
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }
}
