package tterrag1112.life_in_the_village.Gui.Framework;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;

/**
 * Renders a row of gold/silver/bronze coin item icons for a bronze amount.
 * Mirrors the {@code renderCoinRow} pattern used in TradeScreen — each
 * non-zero denomination gets one item slot (18px wide), highest first.
 *
 * <p>Use {@link #width(long)} to size a layout that contains the row.
 */
public final class CoinRow {

    private CoinRow() {}

    private static final int SLOT_W = 18;

    /**
     * Draws coin icons starting at (x,y). Only non-zero denominations are
     * rendered; an amount of zero draws nothing.
     */
    public static void draw(GuiGraphics g, long bronze, int x, int y) {
        long gold   = bronze / CurrencyValue.GOLD_VALUE;
        long silver = (bronze % CurrencyValue.GOLD_VALUE) / CurrencyValue.SILVER_VALUE;
        long b      = bronze % CurrencyValue.SILVER_VALUE;

        int offsetX = x;
        var font = Minecraft.getInstance().font;

        if (gold > 0) {
            ItemStack stack = new ItemStack(ModItems.DENIER_OR.get(),
                    (int) Math.min(gold, 99));
            g.renderItem(stack, offsetX, y);
            g.renderItemDecorations(font, stack, offsetX, y, null);
            offsetX += SLOT_W;
        }
        if (silver > 0) {
            ItemStack stack = new ItemStack(ModItems.DENIER_ARGENT.get(),
                    (int) Math.min(silver, 99));
            g.renderItem(stack, offsetX, y);
            g.renderItemDecorations(font, stack, offsetX, y, null);
            offsetX += SLOT_W;
        }
        if (b > 0) {
            ItemStack stack = new ItemStack(ModItems.DENIER.get(),
                    (int) Math.min(b, 99));
            g.renderItem(stack, offsetX, y);
            g.renderItemDecorations(font, stack, offsetX, y, null);
        }
    }

    /**
     * Width in pixels the coin row will occupy for the given amount.
     * Returns zero when nothing would render.
     */
    public static int width(long bronze) {
        long gold   = bronze / CurrencyValue.GOLD_VALUE;
        long silver = (bronze % CurrencyValue.GOLD_VALUE) / CurrencyValue.SILVER_VALUE;
        long b      = bronze % CurrencyValue.SILVER_VALUE;
        int slots = (gold > 0 ? 1 : 0)
                + (silver > 0 ? 1 : 0)
                + (b > 0 ? 1 : 0);
        return slots * SLOT_W;
    }
}
