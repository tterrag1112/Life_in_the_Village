package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Items.ModItems;

public class CurrencyValue {

    public static final int BRONZE_VALUE = 1;
    public static final int SILVER_VALUE = 64;
    public static final int GOLD_VALUE = 4096;

    private final long bronzeUnits;

    private CurrencyValue(long bronzeUnits) {
        this.bronzeUnits = bronzeUnits;
    }

    public static CurrencyValue of(long bronzeUnits) {
        return new CurrencyValue(bronzeUnits);
    }

    public static CurrencyValue ofSilver(long silver) {
        return new CurrencyValue(silver * SILVER_VALUE);
    }

    public static CurrencyValue ofGold(long gold) {
        return new CurrencyValue(gold * GOLD_VALUE);
    }

    public static CurrencyValue ofCoins(long gold, long silver, long bronze) {
        return new CurrencyValue(
                gold * GOLD_VALUE + silver * SILVER_VALUE + bronze * BRONZE_VALUE
        );
    }

    public long toBronze() { return bronzeUnits; }
    public long toSilver() { return bronzeUnits / SILVER_VALUE; }
    public long toGold()   { return bronzeUnits / GOLD_VALUE; }

    public long remainingSilverAfterGold() {
        return (bronzeUnits % GOLD_VALUE) / SILVER_VALUE;
    }

    public long remainingBronzeAfterSilver() {
        return bronzeUnits % SILVER_VALUE;
    }

    public CurrencyValue add(CurrencyValue other) {
        return new CurrencyValue(this.bronzeUnits + other.bronzeUnits);
    }

    public CurrencyValue subtract(CurrencyValue other) {
        return new CurrencyValue(this.bronzeUnits - other.bronzeUnits);
    }

    public boolean isAffordable(CurrencyValue price) {
        return this.bronzeUnits >= price.bronzeUnits;
    }

    public boolean isNegative() {
        return bronzeUnits < 0;
    }

    public static int valuePerCoin(Item item) {
        if (item == ModItems.DENIER_OR.get())   return GOLD_VALUE;
        if (item == ModItems.DENIER_ARGENT.get()) return SILVER_VALUE;
        if (item == ModItems.DENIER.get()) return BRONZE_VALUE;
        return 0;
    }

    @Override
    public String toString() {
        // Delegate to CoinRenderer.format for consistency
        // (server-side fallback without GuiGraphics)
        long gold   = toGold();
        long silver = remainingSilverAfterGold();
        long bronze = remainingBronzeAfterSilver();
        StringBuilder sb = new StringBuilder();
        if (gold   > 0) sb.append(gold).append("g ");
        if (silver > 0) sb.append(silver).append("s ");
        if (bronze > 0 || sb.isEmpty()) sb.append(bronze).append("b");
        return sb.toString().trim();
    }
}
