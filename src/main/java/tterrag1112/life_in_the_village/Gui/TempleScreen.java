package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Gui.Framework.Chrome;
import tterrag1112.life_in_the_village.Gui.Framework.NeedMeter;
import tterrag1112.life_in_the_village.Gui.Framework.Pill;
import tterrag1112.life_in_the_village.Gui.Framework.StatBox;
import tterrag1112.life_in_the_village.Gui.Framework.StyledButton;
import tterrag1112.life_in_the_village.Networking.OpenTempleScreenPacket;

import java.util.Locale;

/**
 * Religion Rework R9b — read-only religious-building (temple) screen. Surfaces a
 * temple/chapel/shrine's otherwise-invisible R4 economy + R4c decay, plus faith,
 * consecration, candle stock, clergy, congregation, and upcoming holy days, so
 * the player can see at a glance whether it is flourishing or sliding toward
 * abandonment.
 *
 * <p>Mirrors {@link BusinessFrontScreen}: the Open packet carries the full
 * server-computed snapshot; this screen only renders it. Render order is dim →
 * Chrome → content → {@code super.render} so the Close button paints on top.
 * No actions this phase.</p>
 */
public class TempleScreen extends Screen {

    private static final Chrome.Dims DIMS = Chrome.COMPACT;
    private static final int W = DIMS.w();
    private static final int H = DIMS.h();
    private static final int PADDING = 8;
    private static final int COL_GAP = 8;
    private static final int BOX_H = 22;
    private static final int ROW_GAP = 4;

    private final OpenTempleScreenPacket data;
    private int panelX, panelY;

    public TempleScreen(OpenTempleScreenPacket data) {
        super(Component.literal(data.buildingName()));
        this.data = data;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        panelX = (width - W) / 2;
        panelY = (height - H) / 2;

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
        int colW = (W - PADDING * 2 - COL_GAP) / 2;
        int xR = x + colW + COL_GAP;

        // ── Title + subtitle ─────────────────────────────────────────────────
        g.drawString(font, data.buildingName(), x, panelY + PADDING,
                BookScreenColors.DARK, false);
        String faith = data.faithName().isEmpty() ? "No patron faith" : data.faithName();
        if (!data.deityName().isEmpty()) faith += " (" + data.deityName() + ")";
        String subtitle = pretty(data.buildingTypeName()) + " — " + faith
                + (data.villageName().isEmpty() ? "" : " · " + data.villageName());
        g.drawString(font, subtitle, x, panelY + PADDING + 11,
                BookScreenColors.LIGHT, false);

        // ── Health + consecration pills ──────────────────────────────────────
        int y = panelY + 32;
        int hbg = healthBg(data.healthState());
        Pill.draw(g, font, x, y, data.healthState(), hbg, BookScreenColors.DARK);
        int px2 = x + Pill.width(font, data.healthState()) + 4;
        String consText = data.consecrated() ? "Consecrated" : "Unconsecrated";
        Pill.draw(g, font, px2, y, consText,
                data.consecrated() ? BookScreenColors.BLUE_BG : BookScreenColors.SIDEBAR,
                BookScreenColors.DARK);
        y += 16;

        // ── Economy (R4) ─────────────────────────────────────────────────────
        StatBox.draw(g, font, x,  y, colW, BOX_H, "Treasury", data.treasury() + "b");
        StatBox.draw(g, font, xR, y, colW, BOX_H, "Daily cost", data.dailyCost() + "b/day");
        y += BOX_H + ROW_GAP;

        String econRight;
        if (data.daysInsolvent() > 0) {
            econRight = data.daysInsolvent() + (data.daysInsolvent() == 1 ? " day insolvent" : " days insolvent");
        } else if (data.surplus() >= 0) {
            econRight = "+" + data.surplus() + "b above buffer";
        } else {
            econRight = data.surplus() + "b (below buffer)";
        }
        String condVal = pretty(data.condition()) + (data.decaying() ? " (decaying)" : "");
        StatBox.draw(g, font, x,  y, colW, BOX_H, "Surplus", econRight);
        StatBox.draw(g, font, xR, y, colW, BOX_H, "Condition", condVal);
        y += BOX_H + ROW_GAP;

        // ── Candles (R4b) + congregation count ───────────────────────────────
        String candleVal = data.candleCount() + (data.candleCount() == 0 ? " — unlit rites" : " white");
        StatBox.draw(g, font, x,  y, colW, BOX_H, "Candles", candleVal);
        StatBox.draw(g, font, xR, y, colW, BOX_H, "Congregation",
                data.congregationCount() + " served");
        y += BOX_H + ROW_GAP;

        // ── Clergy (full width) ──────────────────────────────────────────────
        String clergyLabel = data.staffed()
                ? (data.clergyTitle().isEmpty() ? "Clergy" : data.clergyTitle())
                : "Clergy";
        String clergyVal;
        if (!data.staffed()) {
            clergyVal = "Vacant";
        } else {
            clergyVal = data.clergyName()
                    + (data.clergyOrder().isEmpty() ? "" : " · " + data.clergyOrder());
        }
        StatBox.draw(g, font, x, y, W - PADDING * 2, BOX_H, clergyLabel, clergyVal);
        y += BOX_H + ROW_GAP;

        // ── Aggregate piety bar ──────────────────────────────────────────────
        g.drawString(font, "Aggregate piety " + Math.round(clamp01(data.aggregatePiety()) * 100) + "%",
                x, y, BookScreenColors.MID, false);
        y += 10;
        NeedMeter.bar(g, x, y, W - PADDING * 2, 6, clamp01(data.aggregatePiety()),
                BookScreenColors.BLUE_BG);
        y += 11;

        // ── Upcoming holy days ───────────────────────────────────────────────
        g.drawString(font, "Upcoming holy days:", x, y, BookScreenColors.LIGHT, false);
        y += 10;
        if (data.upcoming().isEmpty()) {
            g.drawString(font, "  (none scheduled)", x, y, BookScreenColors.MID, false);
        } else {
            for (String line : data.upcoming()) {
                g.drawString(font, "  " + line, x, y, BookScreenColors.MID, false);
                y += 10;
            }
        }

        super.render(g, mouseX, mouseY, partial);
    }

    /** Health-state pill background: green flourishing/solvent, amber at-risk,
     *  red decaying/abandoned. */
    private static int healthBg(String state) {
        return switch (state) {
            case "Flourishing", "Solvent" -> BookScreenColors.GREEN_BG;
            case "At-risk"                 -> BookScreenColors.HIGHLIGHT;
            default                        -> BookScreenColors.RED_BG; // Decaying / Abandoned
        };
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** TITLE_CASE a SCREAMING_SNAKE enum name (TEMPLE → Temple, NEW → New). */
    private static String pretty(String name) {
        if (name == null || name.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '_') { sb.append(' '); cap = true; }
            else if (cap) { sb.append(Character.toUpperCase(c)); cap = false; }
            else sb.append(c);
        }
        return sb.toString();
    }
}
