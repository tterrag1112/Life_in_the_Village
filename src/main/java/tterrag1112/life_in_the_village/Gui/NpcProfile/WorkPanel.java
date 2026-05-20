package tterrag1112.life_in_the_village.Gui.NpcProfile;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Framework.NeedMeter;
import tterrag1112.life_in_the_village.Gui.Framework.Pill;
import tterrag1112.life_in_the_village.Gui.Framework.StatBox;
import tterrag1112.life_in_the_village.Networking.NpcProfileSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;

/** Renders profession assignment, schedule status, building, and current activity. */
public class WorkPanel implements NpcProfilePanel {

    @Override
    public void render(GuiGraphics g, Font font,
                       PageArea area, NpcProfileSnapshot s,
                       int mouseX, int mouseY) {
        int x = area.x() + 4;
        int y = area.y() + 6;
        int bw = area.w() - 12;

        g.drawString(font, "Work", x, y, BookScreenColors.DARK, false);
        y += 12;

        StatBox.draw(g, font, x, y, bw, 22, "Profession",
                s.professionName().replace('_', ' '));
        y += 25;

        StatBox.draw(g, font, x, y, bw, 22, "Assigned To",
                s.buildingName().isEmpty() ? "—" : s.buildingName());
        y += 25;

        StatBox.draw(g, font, x, y, bw, 22, "Village",
                s.villageName().isEmpty() ? "—" : s.villageName());
        y += 25;

        // Schedule indicators
        g.drawString(font, "Schedule:", x, y, BookScreenColors.LIGHT, false);
        y += 10;

        int px = x;
        px = drawSchedulePill(g, font, px, y, "Work",  s.isWorkTime());
        px = drawSchedulePill(g, font, px + 3, y, "Sleep", s.isSleepTime());
        drawSchedulePill(g, font, px + 3, y, "Social", s.isSocialTime());
        y += 16;

        // Activity
        if (!s.currentActivity().isEmpty()) {
            StatBox.draw(g, font, x, y, bw, 22, "Activity", s.currentActivity());
            y += 25;
        }

        // Track 5a.3 — adventurer party-member status. Surfaces what
        // the deleted "handleAdventurer" chat dump used to communicate.
        if ("Adventurer".equalsIgnoreCase(s.professionName())
                || s.professionName().toLowerCase().contains("adventurer")
                || !s.combatRoleName().isEmpty()) {
            renderAdventurerStatus(g, font, x, y, bw, s);
        }
    }

    private static void renderAdventurerStatus(GuiGraphics g, Font font,
                                               int x, int y, int bw,
                                               NpcProfileSnapshot s) {
        g.drawString(font, "Adventurer", x, y, BookScreenColors.DARK, false);
        y += 12;

        if (!s.combatRoleName().isEmpty()) {
            StatBox.draw(g, font, x, y, bw, 22, "Combat Role",
                    s.combatRoleName().replace('_', ' '));
            y += 25;
        }
        if (!s.adventurerTitle().isEmpty()) {
            StatBox.draw(g, font, x, y, bw, 22, "Title", s.adventurerTitle());
            y += 25;
        }

        // Party-status hint: server-side, if this NPC is in the
        // viewing player's party, the nav-button kind is PARTY_STATUS
        // and the profile gains a "Party Status" nav button. The
        // detailed expedition / kill-count info still arrives through
        // the nav-button chat dump until a dedicated party screen lands.
        if (s.hasNavTarget()
                && s.navTargetKind() == NpcProfileSnapshot.NavTargetKind.PARTY_STATUS) {
            g.drawString(font, "In your party — click 'Party Status' for details.",
                    x, y, BookScreenColors.LIGHT, false);
        }
    }

    private static int drawSchedulePill(GuiGraphics g, Font font,
                                        int x, int y, String label, boolean active) {
        int bg  = active ? BookScreenColors.GREEN_BG  : BookScreenColors.SIDEBAR;
        int txt = active ? BookScreenColors.GREEN_TXT : BookScreenColors.LIGHT;
        Pill.draw(g, font, x, y, label, bg, txt);
        return x + Pill.width(font, label);
    }
}
