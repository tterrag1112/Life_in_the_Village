package tterrag1112.life_in_the_village.Gui.Framework;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;

/**
 * A {@link Button} subclass that paints with the parchment palette
 * (PARCHMENT / HIGHLIGHT / COL_DISABLED fills, BORDER outline,
 * DARK / LIGHT text) instead of the vanilla widget atlas. Behaviour
 * is otherwise identical to the vanilla button.
 */
public class StyledButton extends Button {

    protected StyledButton(int x, int y, int width, int height,
                           Component msg, OnPress onPress) {
        super(x, y, width, height, msg, onPress, DEFAULT_NARRATION);
    }

    /**
     * Returns a builder that produces a {@link StyledButton}. Use exactly
     * like {@code Button.builder(msg, onPress)}.
     */

    public static StyledButton.Builder builder(Component msg, OnPress onPress) {

        return new StyledButton.Builder(msg, onPress);
    }

    @Override
    protected void renderContents(GuiGraphics g, int mouseX, int mouseY,
                                float partialTick) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        int bg;
        if (!active)              bg = BookScreenColors.COL_DISABLED;
        else if (isHovered())     bg = BookScreenColors.HIGHLIGHT;
        else                      bg = BookScreenColors.PARCHMENT;

        g.fill(x, y, x + w, y + h, bg);
        g.renderOutline(x, y, w, h, BookScreenColors.BORDER);

        int txt = active ? BookScreenColors.DARK : BookScreenColors.LIGHT;
        var font = Minecraft.getInstance().font;
        String s = getMessage().getString();
        int tw = font.width(s);
        g.drawString(font, s,
                x + (w - tw) / 2,
                y + (h - 8) / 2,
                txt, false);
    }



    /**
     * Mirrors {@link Button.Builder} but builds a {@link StyledButton}.
     * Only the most-used setters are surfaced; add more as needed.
     */
    public static class Builder extends Button.Builder {
        private final Component msg;
        private final OnPress onPress;
        private int x, y, width = 150, height = 20;

        public Builder(Component msg, OnPress onPress) {
            super(msg, onPress);
            this.msg = msg;
            this.onPress = onPress;
        }

        /** Sets the top-left position; mirrors {@code Button.Builder.pos}. */
        public Builder pos(int x, int y) { this.x = x; this.y = y; return this; }

        /** Sets the widget size; mirrors {@code Button.Builder.size}. */
        public Builder size(int w, int h) { this.width = w; this.height = h; return this; }

        /** Builds the styled button. */
        public StyledButton build() {
            return new StyledButton(x, y, width, height, msg, onPress);
        }
    }
}
