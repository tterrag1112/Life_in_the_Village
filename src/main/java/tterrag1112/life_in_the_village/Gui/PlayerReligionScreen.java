package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Gui.Framework.Chrome;
import tterrag1112.life_in_the_village.Gui.Framework.NeedMeter;
import tterrag1112.life_in_the_village.Gui.Framework.Pill;
import tterrag1112.life_in_the_village.Gui.Framework.ScrollList;
import tterrag1112.life_in_the_village.Gui.Framework.StyledButton;
import tterrag1112.life_in_the_village.Networking.OpenPlayerReligionPacket;
import tterrag1112.life_in_the_village.Networking.OpenPlayerReligionPacket.CalendarRow;

/**
 * Religion Rework R9c — read-only player-religion + religious-calendar screen.
 * Surfaces the player's own faith / piety / tithe pledge / observance, and a
 * scrolling list of upcoming holy days, signature rites, and grand festivals
 * across faiths (computed on the {@code % 365} liturgical axis). Mirrors the
 * {@link TempleScreen} pattern: the Open packet carries the snapshot; this
 * screen only renders it. No actions this phase.
 */
public class PlayerReligionScreen extends Screen {

    private static final Chrome.Dims DIMS = Chrome.COMPACT;
    private static final int W = DIMS.w();
    private static final int H = DIMS.h();
    private static final int PADDING = 8;
    private static final int ROW_H = 12;

    private final OpenPlayerReligionPacket data;
    private int panelX, panelY;
    private ScrollList<CalendarRow> calendar;

    public PlayerReligionScreen(OpenPlayerReligionPacket data) {
        super(Component.literal("Your Religion"));
        this.data = data;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        panelX = (width - W) / 2;
        panelY = (height - H) / 2;

        int listX = panelX + PADDING;
        int listY = panelY + 104;
        int listW = W - PADDING * 2;
        int listH = H - 104 - 26;     // leave the Close-button row free
        calendar = new ScrollList<>(listX, listY, listW, listH, ROW_H,
                data.calendar(), this::drawCalendarRow, null);

        addRenderableWidget(StyledButton.builder(Component.literal("Close"),
                        b -> this.onClose())
                .pos(panelX + W - 60 - PADDING, panelY + H - 22)
                .size(60, 18).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x88000000);
        Chrome.draw(g, panelX, panelY, DIMS, Chrome.PARCHMENT);

        var font = net.minecraft.client.Minecraft.getInstance().font;
        int x = panelX + PADDING;
        int bw = W - PADDING * 2;

        // ── Title ────────────────────────────────────────────────────────────
        g.drawString(font, "Your Religion", x, panelY + PADDING, BookScreenColors.DARK, false);

        // ── Your faith ───────────────────────────────────────────────────────
        if (data.religionName().isEmpty()) {
            g.drawString(font, "Unaffiliated", x, panelY + 20, BookScreenColors.MID, false);
            g.drawString(font, "Make offerings or attend rites to grow your piety.",
                    x, panelY + 31, BookScreenColors.LIGHT, false);
        } else {
            String faith = data.religionName();
            if (!data.deityName().isEmpty()) faith += "  (" + data.deityName() + ")";
            g.drawString(font, faith, x, panelY + 20, BookScreenColors.MID, false);

            NeedMeter.bar(g, x, panelY + 31, bw, 8, clamp01(data.pietyStrength()),
                    BookScreenColors.BLUE_BG);
            Pill.draw(g, font, x + bw - Pill.width(font, data.pietyTier()), panelY + 30,
                    data.pietyTier(), BookScreenColors.HIGHLIGHT, BookScreenColors.DARK);
            g.drawString(font, "Piety " + Math.round(clamp01(data.pietyStrength()) * 100) + "%",
                    x, panelY + 42, BookScreenColors.DARK, false);

            if (!data.beliefSummary().isEmpty()) {
                g.drawString(font, "Beliefs: " + String.join(" · ", data.beliefSummary()),
                        x, panelY + 53, BookScreenColors.LIGHT, false);
            }
        }

        // ── Tithe pledge + observance ────────────────────────────────────────
        String pledge = data.hasPledge() && !data.pledgeTempleName().isEmpty()
                ? "Tithing to " + data.pledgeTempleName()
                        + (data.pledgeFaithName().isEmpty() ? "" : " (" + data.pledgeFaithName() + ")")
                : "No tithe pledge";
        g.drawString(font, pledge, x, panelY + 66, BookScreenColors.MID, false);
        String obs = data.ritesThisMonth() + " rite(s) this month"
                + (data.meetsMonthlyAttendance() ? " — observant" : "");
        g.drawString(font, obs, x, panelY + 77, BookScreenColors.MID, false);

        // ── Calendar header + list ───────────────────────────────────────────
        g.drawString(font, "Religious Calendar  (day " + data.today() + " of 365)",
                x, panelY + 91, BookScreenColors.DARK, false);
        if (data.calendar().isEmpty()) {
            g.drawString(font, "  (no calendar data)", x, panelY + 104, BookScreenColors.MID, false);
        } else {
            calendar.render(g, mouseX, mouseY);
        }

        super.render(g, mouseX, mouseY, partial);
    }

    /** One calendar row: today highlighted, own-faith entries in DARK ink. */
    private void drawCalendarRow(GuiGraphics g, int rowX, int rowY, int rowW, int rowH,
                                 CalendarRow row, boolean hovered) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        boolean today = row.daysAway() == 0;
        if (today) g.fill(rowX, rowY, rowX + rowW, rowY + rowH, BookScreenColors.HIGHLIGHT);
        int ink = row.ownFaith() ? BookScreenColors.DARK : BookScreenColors.MID;

        String left = row.dayLabel() + " · " + row.faithDisplay()
                + (row.ownFaith() ? " ★" : "");
        g.drawString(font, left, rowX + 2, rowY + 2, ink, false);

        String right = today ? "today"
                : "in " + row.daysAway() + (row.daysAway() == 1 ? " day" : " days");
        g.drawString(font, right, rowX + rowW - font.width(right) - 6, rowY + 2,
                today ? BookScreenColors.RED_TXT : BookScreenColors.LIGHT, false);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (calendar != null && calendar.mouseScrolled(mx, my, sy)) return true;
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        if (calendar != null
                && calendar.mouseClicked(event.x(), event.y(), event.button())) return true;
        return super.mouseClicked(event, isDoubleClick);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
