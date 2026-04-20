package tterrag1112.life_in_the_village.Gui.Framework;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;

/**
 * An {@link EditBox} subclass that paints with the parchment palette so
 * text fields blend with the book chrome. The interactive behaviour
 * (cursor, selection, key handling) is inherited unchanged from vanilla.
 */
public class StyledEditBox extends EditBox {

    public StyledEditBox(Font font, int x, int y, int width, int height,
                         Component message) {
        super(font, x, y, width, height, message);
        setBordered(false);
        setTextColor(BookScreenColors.DARK);
        setTextColorUneditable(BookScreenColors.LIGHT);
    }



    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY,
                                float partialTick) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        int bg;
        if (!active)        bg = BookScreenColors.COL_DISABLED;
        else if (isFocused()) bg = BookScreenColors.PARCHMENT;
        else                bg = BookScreenColors.SIDEBAR;

        g.fill(x, y, x + w, y + h, bg);
        g.renderOutline(x, y, w, h,
                isFocused() ? BookScreenColors.GOLD : BookScreenColors.BORDER);

        super.renderWidget(g, mouseX, mouseY, partialTick);
    }
}
