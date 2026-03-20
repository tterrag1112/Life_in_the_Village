package tterrag1112.life_in_the_village.Gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;

/**
 * Shared coin display utilities for all GUI screens.
 * Always derives denominations from CurrencyValue constants
 * so they stay in sync with the economy system.
 */
public class CoinRenderer {

    /**
     * Renders coin item icons with stack counts for a bronze amount.
     * Mirrors TradeScreen.renderCoinRow exactly.
     * Returns the X position after the last rendered icon.
     */
    public static int renderCoinRow(GuiGraphics g, long bronze, int x, int y) {
        long gold   = bronze / CurrencyValue.GOLD_VALUE;
        long silver = (bronze % CurrencyValue.GOLD_VALUE) / CurrencyValue.SILVER_VALUE;
        long b      = bronze % CurrencyValue.SILVER_VALUE;

        int offsetX = x;

        if (gold > 0) {
            ItemStack stack = new ItemStack(ModItems.DENIER_OR.get(),
                    (int) Math.min(gold, 99));
            g.renderItem(stack, offsetX, y);
            g.renderItemDecorations(
                    net.minecraft.client.Minecraft.getInstance().font,
                    stack, offsetX, y, null);
            offsetX += 18;
        }

        if (silver > 0) {
            ItemStack stack = new ItemStack(ModItems.DENIER_ARGENT.get(),
                    (int) Math.min(silver, 99));
            g.renderItem(stack, offsetX, y);
            g.renderItemDecorations(
                    net.minecraft.client.Minecraft.getInstance().font,
                    stack, offsetX, y, null);
            offsetX += 18;
        }

        if (b > 0 || (gold == 0 && silver == 0)) {
            ItemStack stack = new ItemStack(ModItems.DENIER.get(),
                    (int) Math.min(Math.max(b, 1), 99));
            g.renderItem(stack, offsetX, y);
            g.renderItemDecorations(
                    net.minecraft.client.Minecraft.getInstance().font,
                    stack, offsetX, y, null);
            offsetX += 18;
        }

        return offsetX;
    }

    /**
     * Text-only fallback using correct CurrencyValue denominatiors.
     * Use this when icons don't fit (e.g. sidebar treasury display).
     */
    public static String format(long bronze) {
        long gold   = bronze / CurrencyValue.GOLD_VALUE;
        long silver = (bronze % CurrencyValue.GOLD_VALUE) / CurrencyValue.SILVER_VALUE;
        long b      = bronze % CurrencyValue.SILVER_VALUE;

        StringBuilder sb = new StringBuilder();
        if (gold   > 0) sb.append(gold).append("g ");
        if (silver > 0) sb.append(silver).append("s ");
        if (b > 0 || sb.isEmpty()) sb.append(b).append("b");
        return sb.toString().trim();
    }

    /**
     * Width in pixels that renderCoinRow will occupy for a given amount.
     * Useful for layout calculations.
     */
    public static int coinRowWidth(long bronze) {
        long gold   = bronze / CurrencyValue.GOLD_VALUE;
        long silver = (bronze % CurrencyValue.GOLD_VALUE) / CurrencyValue.SILVER_VALUE;
        long b      = bronze % CurrencyValue.SILVER_VALUE;
        int count = (gold > 0 ? 1 : 0)
                + (silver > 0 ? 1 : 0)
                + (b > 0 || (gold == 0 && silver == 0) ? 1 : 0);
        return count * 18;
    }
}