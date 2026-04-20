package tterrag1112.life_in_the_village.Gui.NpcProfile;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Gui.Framework.NeedMeter;
import tterrag1112.life_in_the_village.Gui.Framework.Pill;
import tterrag1112.life_in_the_village.Gui.Framework.StatBox;
import tterrag1112.life_in_the_village.Networking.NpcProfileSnapshot;

/** Renders village reputation score + tier, and the personal NPC relationship delta. */
public class ReputationPanel implements NpcProfilePanel {

    @Override
    public void render(GuiGraphics g, Font font,
                       PageArea area, NpcProfileSnapshot s,
                       int mouseX, int mouseY) {
        int x = area.x() + 4;
        int y = area.y() + 6;
        int bw = area.w() - 12;

        g.drawString(font, "Reputation", x, y, BookScreenColors.DARK, false);
        y += 12;

        // Village section
        if (!s.villageName().isEmpty()) {
            g.drawString(font, s.villageName(), x, y, BookScreenColors.MID, false);
            y += 12;

            // Rep score bar: −100…+100 mapped to 0..1
            float fill = (s.villageReputationScore() + 100f) / 200f;
            int barColor = s.villageReputationScore() >= 0
                    ? BookScreenColors.GREEN_BG
                    : BookScreenColors.RED_BG;
            NeedMeter.bar(g, x, y, bw, 10, fill, barColor);
            y += 14;

            String scoreStr = (s.villageReputationScore() >= 0 ? "+" : "")
                    + s.villageReputationScore();
            StatBox.draw(g, font, x, y, bw, 22, "Standing",
                    scoreStr + " — " + s.villageTierName());
            y += 25;
        }

        // Personal delta bar: −20…+20 mapped to 0..1
        g.drawString(font, "Personal Relationship", x, y, BookScreenColors.LIGHT, false);
        y += 10;
        float pFill = (s.personalDelta() + 20f) / 40f;
        int pColor = s.personalDelta() >= 0
                ? BookScreenColors.GREEN_BG
                : BookScreenColors.RED_BG;
        NeedMeter.bar(g, x, y, bw, 10, pFill, pColor);
        y += 14;
        String deltaStr = (s.personalDelta() >= 0 ? "+" : "") + s.personalDelta();
        g.drawString(font, deltaStr, x, y, BookScreenColors.DARK, false);
        y += 14;

        // Hint about gift
        g.drawString(font, "Give a gift (action bar) to improve standing.",
                x, y, BookScreenColors.LIGHT, false);
    }
}
