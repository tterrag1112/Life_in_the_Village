package tterrag1112.life_in_the_village.Village.Economy.Resources;

import net.minecraft.world.item.Item;

import java.util.List;

public class MiningYieldData {

    public enum Rarity {
        COMMON, UNCOMMON, RARE, EPIC;

        /** Base weight for weighted random selection */
        public int weight() {
            return switch (this) {
                case COMMON   -> 60;
                case UNCOMMON -> 25;
                case RARE     -> 12;
                case EPIC     -> 3;
            };
        }

        /** Base ticks between yields at level 1 */
        public int baseInterval() {
            return switch (this) {
                case COMMON   -> 200;
                case UNCOMMON -> 400;
                case RARE     -> 800;
                case EPIC     -> 2400;
            };
        }
    }

    public record YieldEntry(Item item, Rarity rarity, int min, int max) {
        public int roll(net.minecraft.util.RandomSource random, int mineLevel) {
            int base = min + random.nextInt(Math.max(1, max - min + 1));
            // Each mine level adds 20% more yield
            double multiplier = 1.0 + (mineLevel - 1) * 0.2;
            return Math.max(1, (int)(base * multiplier));
        }

        public int tickInterval(int mineLevel) {
            // Each mine level reduces interval by 15%
            double reduction = Math.pow(0.85, mineLevel - 1);
            return Math.max(20, (int)(rarity.baseInterval() * reduction));
        }
    }

    private final String type;
    private final List<YieldEntry> yields;

    public MiningYieldData(String type, List<YieldEntry> yields) {
        this.type   = type;
        this.yields = List.copyOf(yields);
    }

    public String getType()           { return type; }
    public List<YieldEntry> getYields() { return yields; }

    /**
     * Picks a random yield entry using weighted rarity selection.
     */
    public YieldEntry rollYield(net.minecraft.util.RandomSource random) {
        int totalWeight = yields.stream()
                .mapToInt(e -> e.rarity().weight())
                .sum();

        int roll = random.nextInt(totalWeight);
        int cumulative = 0;

        for (YieldEntry entry : yields) {
            cumulative += entry.rarity().weight();
            if (roll < cumulative) return entry;
        }

        return yields.get(yields.size() - 1);
    }
}
