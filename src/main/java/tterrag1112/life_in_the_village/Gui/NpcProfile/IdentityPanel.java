package tterrag1112.life_in_the_village.Gui.NpcProfile;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Framework.Pill;
import tterrag1112.life_in_the_village.Gui.Framework.Portrait;
import tterrag1112.life_in_the_village.Gui.Framework.StatBox;
import tterrag1112.life_in_the_village.Networking.NpcProfileSnapshot;

import java.util.List;

/** Renders name, portrait, age, life stage, profession, and personality traits. */
public class IdentityPanel implements NpcProfilePanel {

    private static final int PORTRAIT_SIZE = 48;

    @Override
    public void render(GuiGraphics g, Font font,
                       PageArea area, NpcProfileSnapshot s,
                       int mouseX, int mouseY) {
        int x = area.x() + 4, y = area.y() + 6;

        // Portrait
        Portrait.AppearanceSnapshot appearance = new Portrait.AppearanceSnapshot(
                s.name(), s.isMale(), s.age(), s.skinId(), s.hairStyle());
        Portrait.draw(g, font, x, y, PORTRAIT_SIZE, appearance, 0L);

        // Name + profession to the right of the portrait
        int tx = x + PORTRAIT_SIZE + 6;
        g.drawString(font, s.name(), tx, y + 2, BookScreenColors.DARK, false);
        g.drawString(font, s.professionName().replace('_', ' '), tx, y + 13,
                BookScreenColors.MID, false);
        if (!s.combatRoleName().isEmpty()) {
            g.drawString(font, s.combatRoleName(), tx, y + 22, BookScreenColors.LIGHT, false);
        }
        if (!s.adventurerTitle().isEmpty()) {
            g.drawString(font, "\"" + s.adventurerTitle() + "\"",
                    tx, y + 31, BookScreenColors.GOLD, false);
        }

        // Stat boxes: age + life stage
        int bx = x, by = y + PORTRAIT_SIZE + 6;
        int bw = (area.w() - 16) / 2 - 2;
        StatBox.draw(g, font, bx, by, bw, 22,
                "Age", s.age() + " (" + s.lifeStage() + ")");
        StatBox.draw(g, font, bx + bw + 4, by, bw, 22,
                "Sex", s.isMale() ? "Male" : "Female");

        // Traits
        int py = by + 28;
        if (!s.traits().isEmpty()) {
            g.drawString(font, "Traits:", x, py, BookScreenColors.LIGHT, false);
            py += 10;
            int px = x;
            for (String trait : s.traits()) {
                String label = trait.replace('_', ' ');
                int pw = Pill.width(font, label);
                if (px + pw > area.x() + area.w() - 8) { px = x; py += 14; }
                Pill.draw(g, font, px, py, label,
                        BookScreenColors.SIDEBAR, BookScreenColors.MID);
                px += pw + 3;
            }
        }
    }
}
