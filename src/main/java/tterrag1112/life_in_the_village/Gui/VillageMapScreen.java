package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

public class VillageMapScreen extends Screen {

    private static final int FRAME_PAD  = 16;
    private static final int TITLE_H    = 20;
    private static final int LEGEND_H   = 40;

    private final VillageMapRenderer renderer = new VillageMapRenderer();
    private Village village;

    public VillageMapScreen() {
        super(Component.literal("Village Map"));
    }

    @Override
    protected void init() {
        super.init();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        BlockPos playerPos = mc.player.blockPosition();
        Optional<Village> nearest =
                Building.ClientBuildingCache.getNearestVillage(playerPos);
        if (nearest.isEmpty()) return;

        village = nearest.get();

        int mapW = width  - FRAME_PAD * 2;
        int mapH = height - FRAME_PAD * 2 - TITLE_H - LEGEND_H;
        renderer.startBake(village, mapW, mapH);
    }

    @Override
    public void onClose() {
        renderer.close();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        renderer.tick(); // upload texture if bake just finished

        // Dark background
        g.fill(0, 0, width, height, 0xCC000000);

        if (village == null) {
            g.drawCenteredString(font, "No nearby village found.",
                    width / 2, height / 2, 0xAAAAAA);
            super.render(g, mouseX, mouseY, pt);
            return;
        }

        int mapX = FRAME_PAD;
        int mapY = FRAME_PAD + TITLE_H;
        int mapW = width  - FRAME_PAD * 2;
        int mapH = height - FRAME_PAD * 2 - TITLE_H - LEGEND_H;

        // Outer frame
        g.fill(mapX - 2, mapY - 2, mapX + mapW + 2, mapY + mapH + 2, 0xFFB8A878);
        g.fill(mapX - 1, mapY - 1, mapX + mapW + 1, mapY + mapH + 1, 0xFF2A2010);

        // Village name
        g.drawCenteredString(font, village.getName(),
                width / 2, FRAME_PAD + 4, 0xFFFFAA);

        // Map content
        renderer.draw(g, mapX, mapY, mapW, mapH, mouseX, mouseY);

        // Legend
        drawLegend(g, mapX, mapY + mapH + 6, mapW);

        super.render(g, mouseX, mouseY, pt);
    }

    private void drawLegend(GuiGraphics g, int x, int y, int w) {
        // Two rows of color swatches with labels
        record LegendEntry(int color, String label) {}
        LegendEntry[] entries = {
                new LegendEntry(0xCC2255CC, "Town Hall"),
                new LegendEntry(0xCC9922CC, "Guild Hall"),
                new LegendEntry(0xCC228833, "House"),
                new LegendEntry(0xCCCC7700, "Inn"),
                new LegendEntry(0xCCCC3311, "Blacksmith"),
                new LegendEntry(0xCCCCBB00, "Market"),
        };

        int lx = x + 4;
        int ly = y + 2;
        int swatchW = 8, swatchH = 8, gap = 4;

        for (int i = 0; i < entries.length; i++) {
            if (i == entries.length / 2) {
                lx = x + 4;
                ly += 14;
            }
            LegendEntry e = entries[i];
            g.fill(lx, ly, lx + swatchW, ly + swatchH, e.color());
            g.fill(lx, ly, lx + swatchW, ly + 1, 0xFFFFFFFF);
            g.fill(lx, ly + swatchH - 1, lx + swatchW, ly + swatchH, 0xFFFFFFFF);
            g.fill(lx, ly, lx + 1, ly + swatchH, 0xFFFFFFFF);
            g.fill(lx + swatchW - 1, ly, lx + swatchW, ly + swatchH, 0xFFFFFFFF);
            g.drawString(font, e.label(), lx + swatchW + 2, ly, 0xCCCCCC, false);
            lx += font.width(e.label()) + swatchW + gap + 8;
        }

        // North indicator
        g.drawString(font, "N↑", x + w - 20, y + 2, 0xFFFFDD, false);
    }
}