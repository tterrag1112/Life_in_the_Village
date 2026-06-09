package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.Font;
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
import tterrag1112.life_in_the_village.Networking.OpenPlayerReligionPacket.GodStanding;

/**
 * Religion Rework R9c — read-only player-religion screen. F1a sub-stage 4b — the
 * divine relationship is now shown <b>per god</b>: a scrolling list of the player's
 * gods, each with its signed favour + displeasure band, its miracles' availability,
 * and its theophany history — alongside the religion-level faith header, tithe/
 * observance, the active calling, and the holy-day calendar. Read-only (Close only);
 * the per-god test/grant actions live in the {@code /religion} debug commands.
 */
public class PlayerReligionScreen extends Screen {

    private static final Chrome.Dims DIMS = Chrome.COMPACT;
    private static final int W = DIMS.w();
    private static final int H = DIMS.h();
    private static final int PADDING = 8;
    private static final int CAL_ROW_H = 12;
    private static final int GOD_ROW_H = 31;

    private final OpenPlayerReligionPacket data;
    private int panelX, panelY;
    private ScrollList<GodStanding> godList;
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
        int listW = W - PADDING * 2;

        godList = new ScrollList<>(listX, panelY + 95, listW, 62, GOD_ROW_H,
                data.gods(), this::drawGodRow, null);
        calendar = new ScrollList<>(listX, panelY + 169, listW, H - 169 - 24, CAL_ROW_H,
                data.calendar(), this::drawCalendarRow, null);

        addRenderableWidget(StyledButton.builder(Component.literal("Close"), b -> this.onClose())
                .pos(panelX + W - 60 - PADDING, panelY + H - 22).size(60, 18).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x88000000);
        Chrome.draw(g, panelX, panelY, DIMS, Chrome.PARCHMENT);
        Font font = net.minecraft.client.Minecraft.getInstance().font;
        int x = panelX + PADDING;
        int bw = W - PADDING * 2;

        g.drawString(font, "Your Religion", x, panelY + PADDING, BookScreenColors.DARK, false);

        // ── Faith header (religion-level) ─────────────────────────────────────
        if (data.religionName().isEmpty()) {
            g.drawString(font, "Unaffiliated", x, panelY + 20, BookScreenColors.MID, false);
            g.drawString(font, "Make offerings or attend rites to grow your piety.",
                    x, panelY + 31, BookScreenColors.LIGHT, false);
        } else {
            String faith = data.religionName();
            if (!data.deityName().isEmpty()) faith += "  (" + data.deityName() + ")";
            g.drawString(font, faith, x, panelY + 20, BookScreenColors.MID, false);
            NeedMeter.bar(g, x, panelY + 31, bw, 8, clamp01(data.pietyStrength()), BookScreenColors.BLUE_BG);
            Pill.draw(g, font, x + bw - Pill.width(font, data.pietyTier()), panelY + 30,
                    data.pietyTier(), BookScreenColors.HIGHLIGHT, BookScreenColors.DARK);
            g.drawString(font, "Piety " + Math.round(clamp01(data.pietyStrength()) * 100) + "%",
                    x, panelY + 42, BookScreenColors.DARK, false);
            if (!data.beliefSummary().isEmpty()) {
                g.drawString(font, clip(font, "Beliefs: " + String.join(" · ", data.beliefSummary()), bw),
                        x, panelY + 52, BookScreenColors.LIGHT, false);
            }
        }

        // ── Tithe pledge + (calling | observance) ─────────────────────────────
        String pledge = data.hasPledge() && !data.pledgeTempleName().isEmpty()
                ? "Tithing to " + data.pledgeTempleName()
                        + (data.pledgeFaithName().isEmpty() ? "" : " (" + data.pledgeFaithName() + ")")
                : "No tithe pledge";
        g.drawString(font, clip(font, pledge, bw), x, panelY + 63, BookScreenColors.MID, false);
        if (!data.activeCalling().isEmpty()) {
            g.drawString(font, clip(font, "✦ Calling — " + data.activeCalling(), bw),
                    x, panelY + 73, BookScreenColors.DARK, false);
        } else {
            g.drawString(font, data.ritesThisMonth() + " rite(s) this month"
                            + (data.meetsMonthlyAttendance() ? " — observant" : ""),
                    x, panelY + 73, BookScreenColors.MID, false);
        }

        // ── Gods (per-god divine standing) ────────────────────────────────────
        g.drawString(font, "Gods", x, panelY + 85, BookScreenColors.DARK, false);
        if (data.gods().isEmpty()) {
            g.drawString(font, "  (no standing with any god)", x, panelY + 96, BookScreenColors.MID, false);
        } else {
            godList.render(g, mouseX, mouseY);
        }

        // ── Calendar ──────────────────────────────────────────────────────────
        g.drawString(font, "Religious Calendar  (day " + data.today() + " of 365)",
                x, panelY + 159, BookScreenColors.DARK, false);
        if (data.calendar().isEmpty()) {
            g.drawString(font, "  (no calendar data)", x, panelY + 169, BookScreenColors.MID, false);
        } else {
            calendar.render(g, mouseX, mouseY);
        }

        super.render(g, mouseX, mouseY, partial);
    }

    /** One per-god standing row: name + signed favour (band-coloured), the god's
     *  miracles, and its theophany history. */
    private void drawGodRow(GuiGraphics g, int rowX, int rowY, int rowW, int rowH,
                            GodStanding row, boolean hovered) {
        Font font = net.minecraft.client.Minecraft.getInstance().font;
        int ink = bandColour(row.band());
        g.drawString(font, row.name(), rowX + 2, rowY + 1, ink, false);
        String fav = (row.favour() >= 0 ? "+" : "") + row.favour()
                + ("NONE".equals(row.band()) ? "" : " " + row.band().toLowerCase());
        g.drawString(font, fav, rowX + rowW - font.width(fav) - 4, rowY + 1, ink, false);

        String miracles = row.miracles().isEmpty() ? "—" : String.join(" · ", row.miracles());
        g.drawString(font, clip(font, "Miracles: " + miracles, rowW - 4),
                rowX + 2, rowY + 11, BookScreenColors.MID, false);

        String theo = row.theophanies().isEmpty() ? "—" : String.join(" · ", row.theophanies());
        g.drawString(font, clip(font, "✦ Theophany: " + theo, rowW - 4),
                rowX + 2, rowY + 21, BookScreenColors.LIGHT, false);
    }

    private void drawCalendarRow(GuiGraphics g, int rowX, int rowY, int rowW, int rowH,
                                 CalendarRow row, boolean hovered) {
        Font font = net.minecraft.client.Minecraft.getInstance().font;
        boolean today = row.daysAway() == 0;
        if (today) g.fill(rowX, rowY, rowX + rowW, rowY + rowH, BookScreenColors.HIGHLIGHT);
        int ink = row.ownFaith() ? BookScreenColors.DARK : BookScreenColors.MID;
        String left = row.dayLabel() + " · " + row.faithDisplay() + (row.ownFaith() ? " ★" : "");
        g.drawString(font, clip(font, left, rowW - 60), rowX + 2, rowY + 2, ink, false);
        String right = today ? "today"
                : "in " + row.daysAway() + (row.daysAway() == 1 ? " day" : " days");
        g.drawString(font, right, rowX + rowW - font.width(right) - 6, rowY + 2,
                today ? BookScreenColors.RED_TXT : BookScreenColors.LIGHT, false);
    }

    /** Band → ink (positive standing vs displeasure escalation). */
    private static int bandColour(String band) {
        return switch (band) {
            case "OMEN"  -> BookScreenColors.AMBER;
            case "CURSE" -> BookScreenColors.RED_TXT;
            case "WRATH" -> BookScreenColors.RED_TXT;
            default      -> BookScreenColors.GREEN_TXT;   // NONE (positive standing)
        };
    }

    /** Truncates {@code text} to fit {@code maxW} px, adding an ellipsis. */
    private static String clip(Font font, String text, int maxW) {
        if (font.width(text) <= maxW) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (font.width(sb.toString() + text.charAt(i) + "…") > maxW) break;
            sb.append(text.charAt(i));
        }
        return sb + "…";
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (godList != null && godList.mouseScrolled(mx, my, sy)) return true;
        if (calendar != null && calendar.mouseScrolled(mx, my, sy)) return true;
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        if (godList != null && godList.mouseClicked(event.x(), event.y(), event.button())) return true;
        if (calendar != null && calendar.mouseClicked(event.x(), event.y(), event.button())) return true;
        return super.mouseClicked(event, isDoubleClick);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
