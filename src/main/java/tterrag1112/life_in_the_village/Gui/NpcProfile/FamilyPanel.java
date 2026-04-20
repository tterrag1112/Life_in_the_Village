package tterrag1112.life_in_the_village.Gui.NpcProfile;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Framework.StatBox;
import tterrag1112.life_in_the_village.Networking.NpcProfileSnapshot;

/** Renders family role, spouse, household head, children count, and home. */
public class FamilyPanel implements NpcProfilePanel {

    @Override
    public void render(GuiGraphics g, Font font,
                       PageArea area, NpcProfileSnapshot s,
                       int mouseX, int mouseY) {
        int x = area.x() + 4;
        int y = area.y() + 6;
        int bw = area.w() - 12;
        int rowH = 24;

        g.drawString(font, "Family", x, y, BookScreenColors.DARK, false);
        y += 12;

        StatBox.draw(g, font, x, y, bw, rowH, "Role",
                s.familyRole().replace('_', ' '));
        y += rowH + 3;

        StatBox.draw(g, font, x, y, bw, rowH, "Spouse",
                s.spouseName().isEmpty() ? "—" : s.spouseName());
        y += rowH + 3;

        StatBox.draw(g, font, x, y, bw, rowH, "Household Head",
                s.headName().isEmpty() ? "—" : s.headName());
        y += rowH + 3;

        StatBox.draw(g, font, x, y, bw, rowH, "Children",
                String.valueOf(s.childrenCount()));
        y += rowH + 3;

        StatBox.draw(g, font, x, y, bw, rowH, "Home",
                s.houseName().isEmpty() ? "Homeless" : s.houseName());
    }
}
