package tterrag1112.life_in_the_village.Gui.NpcProfile;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Networking.NpcProfileSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the NPC's current contextual dialogue line, word-wrapped inside the
 * page area. The text is a verbatim excerpt from {@link NpcProfileSnapshot#dialogueLine()},
 * which is pre-generated server-side by {@code NpcDialogue.getGreeting}.
 */
public class DialoguePanel implements NpcProfilePanel {

    @Override
    public void render(GuiGraphics g, Font font,
                       PageArea area, NpcProfileSnapshot s,
                       int mouseX, int mouseY) {
        int x = area.x() + 4;
        int y = area.y() + 6;
        int maxW = area.w() - 12;

        g.drawString(font, "Dialogue", x, y, BookScreenColors.DARK, false);
        y += 14;

        // Quoted dialogue line in a parchment box
        int boxH = 48;
        g.fill(x, y, x + maxW, y + boxH, BookScreenColors.SIDEBAR);
        g.renderOutline(x, y, maxW, boxH, BookScreenColors.BORDER);

        // Word-wrap the line
        List<String> lines = wrapText(font, "\"" + s.dialogueLine() + "\"", maxW - 8);
        int ly = y + 6;
        for (String line : lines) {
            g.drawString(font, line, x + 4, ly, BookScreenColors.DARK, false);
            ly += 10;
            if (ly + 10 > y + boxH) break;
        }
        y += boxH + 8;

        g.drawString(font, "— " + s.name(), x + 4, y, BookScreenColors.MID, false);
        y += 16;

        g.drawString(font, "Use Request Sync (action bar) to hear something new.",
                x, y, BookScreenColors.LIGHT, false);
    }

    private static List<String> wrapText(Font font, String text, int maxW) {
        List<String> result = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.width(candidate) <= maxW) {
                line = new StringBuilder(candidate);
            } else {
                if (!line.isEmpty()) result.add(line.toString());
                line = new StringBuilder(word);
            }
        }
        if (!line.isEmpty()) result.add(line.toString());
        return result;
    }
}
