package tterrag1112.life_in_the_village.Gui.NpcProfile;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Framework.NeedMeter;
import tterrag1112.life_in_the_village.Gui.Framework.Pill;
import tterrag1112.life_in_the_village.Gui.Framework.StatBox;
import tterrag1112.life_in_the_village.Networking.NpcProfileSnapshot;

/**
 * Religion Rework R9a — read-only Religion panel: the NPC's faith, piety, clergy
 * status, and provision. Surfaces R1–R5 religious state at a glance for testing.
 * Degrades gracefully for atheists/non-clergy/unserved (no actions this phase).
 */
public class ReligionPanel implements NpcProfilePanel {

    @Override
    public void render(GuiGraphics g, Font font,
                       PageArea area, NpcProfileSnapshot s,
                       int mouseX, int mouseY) {
        int x = area.x() + 4;
        int y = area.y() + 6;
        int bw = area.w() - 12;

        g.drawString(font, "Religion", x, y, BookScreenColors.DARK, false);
        y += 12;

        // ── Faith ────────────────────────────────────────────────────────────
        if (s.religionName().isEmpty()) {
            g.drawString(font, "Unaffiliated", x, y, BookScreenColors.MID, false);
            return; // nothing else to show for an atheist
        }

        String faithLine = s.religionName();
        if (!s.deityName().isEmpty()) faithLine += "  (" + s.deityName() + ")";
        g.drawString(font, faithLine, x, y, BookScreenColors.MID, false);
        y += 12;

        // Piety strength bar (0..1) + tier pill.
        NeedMeter.bar(g, x, y, bw, 10, clamp01(s.pietyStrength()), BookScreenColors.BLUE_BG);
        Pill.draw(g, font, x + bw - Pill.width(font, s.pietyTier()), y - 1,
                s.pietyTier(), BookScreenColors.HIGHLIGHT, BookScreenColors.DARK);
        y += 14;
        g.drawString(font, "Piety " + Math.round(s.pietyStrength() * 100) + "%",
                x, y, BookScreenColors.DARK, false);
        y += 13;

        // Syncretic beliefs (a migrant carrying home + local faith).
        if (!s.beliefSummary().isEmpty()) {
            g.drawString(font, "Beliefs:", x, y, BookScreenColors.LIGHT, false);
            y += 10;
            for (String line : s.beliefSummary()) {
                g.drawString(font, "  " + line, x, y, BookScreenColors.MID, false);
                y += 10;
            }
            y += 2;
        }

        // ── Provision (served vs unserved — validates R3e) ───────────────────
        Pill.draw(g, font, x, y,
                s.isUnservedLocally() ? "Unserved locally" : "Served",
                s.isUnservedLocally() ? BookScreenColors.RED_BG : BookScreenColors.GREEN_BG,
                BookScreenColors.DARK);
        y += 17; // Pill height (12) + gap

        // ── Observance ───────────────────────────────────────────────────────
        String obs = s.ritesThisMonth() + " rite(s) this month"
                + (s.meetsMonthlyAttendance() ? " — observant" : "");
        StatBox.draw(g, font, x, y, bw, 22, "Observance", obs);
        y += 25;

        // ── Clergy ───────────────────────────────────────────────────────────
        if (s.isClergy()) {
            String title = s.clergyTitle().isEmpty() ? "Clergy" : s.clergyTitle();
            String order = s.clergyOrder().isEmpty() ? "(generalist)" : s.clergyOrder();
            StatBox.draw(g, font, x, y, bw, 22, title, order);
            y += 25;
            if (!s.staffedFaith().isEmpty()) {
                g.drawString(font, "Tends a " + s.staffedFaith() + " building.",
                        x, y, BookScreenColors.LIGHT, false);
            }
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
