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
