package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Needs.NeedCategory;
import tterrag1112.life_in_the_village.Village.Simulation.ItemResourceClassifier;
import tterrag1112.life_in_the_village.Village.Simulation.ResourceCategory;
import tterrag1112.life_in_the_village.Village.Simulation.VillageSimData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

public class DynamicPriceCalculator {

    // Price multiplier bounds
    private static final double MIN_MULTIPLIER = 0.5;
    private static final double MAX_MULTIPLIER = 3.0;

    /**
     * Strength of the per-village structural surplus/deficit modifier
     * (economy Phase 1c). The modifier is {@code 1 - STRENGTH * ratio}
     * where {@code ratio = net / (production + consumption) ∈ [-1,+1]},
     * so a pure exporter prices a good at {@code 1 - STRENGTH} and a pure
     * importer at {@code 1 + STRENGTH}. At 0.4 the per-village spread
     * alone is ~2.3× (0.6 ↔ 1.4); combined with the spot supply/demand
     * signals it reliably reaches the {@code [0.5, 3.0]} clamp, giving a
     * cross-village arbitrage gap well above plausible transport cost.
     * 1d will externalise this to data.
     */
    private static final double PER_VILLAGE_STRENGTH = 0.4;

    /**
     * Returns the dynamic sell price (what player pays) for an item
     * in bronze units.
     */
    public static long getSellPrice(ServerLevel level, Village village,
                                    VillageSavedData data, Item item,
                                    long basePrice) {
        double multiplier = computeMultiplier(level, village, data, item);
        return Math.max(1, Math.round(basePrice * multiplier));
    }

    /**
     * Returns the dynamic buy price (what player receives) for an item
     * in bronze units.
     */
    public static long getBuyPrice(ServerLevel level, Village village,
                                   VillageSavedData data, Item item,
                                   long basePrice) {
        // Buy price is always lower than sell — merchant makes margin
        double multiplier = computeMultiplier(level, village, data, item);
        return Math.max(1, Math.round(basePrice * multiplier * 0.6));
    }

    private static double computeMultiplier(ServerLevel level, Village village,
                                            VillageSavedData data, Item item) {
        double multiplier = 1.0;
        ResourceCategory category = ItemResourceClassifier.classify(item);

        // --- Spot supply factor (this-moment physical stockpile) ---
        // Count item in stockpile — more supply = lower price
        int stockpileCount = countInStockpile(level, village, data, item);
        if (stockpileCount == 0) {
            multiplier *= 2.0; // scarce — double price
        } else if (stockpileCount < 16) {
            multiplier *= 1.5; // low stock
        } else if (stockpileCount > 128) {
            multiplier *= 0.7; // oversupplied
        } else if (stockpileCount > 64) {
            multiplier *= 0.85; // well stocked
        }

        // --- Spot demand urgency (NeedCategory snapshot) ---
        // Only FOOD / BUILDING_MATERIALS have a spot-need reading; the
        // classifier replaces the old private isFood/isMaterial heuristics.
        if (category == ResourceCategory.FOOD) {
            multiplier *= switch (village.getNeedLevel(NeedCategory.FOOD)) {
                case CRITICAL -> 2.5;
                case LOW      -> 1.5;
                case SATISFIED -> 1.0;
                case SURPLUS  -> 0.8;
            };
        } else if (category == ResourceCategory.BUILDING_MATERIALS) {
            multiplier *= switch (village.getNeedLevel(NeedCategory.BUILDING_MATERIALS)) {
                case CRITICAL -> 2.0;
                case LOW      -> 1.4;
                case SATISFIED -> 1.0;
                case SURPLUS  -> 0.9;
            };
        }

        // --- Structural per-village surplus/deficit (daily sim) ---
        // The arbitrage driver: exporter villages cheaper, importer dearer.
        multiplier *= perVillageModifier(village, data, category);

        return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, multiplier));
    }

    /**
     * Structural per-village price modifier from the daily resource sim
     * ({@link VillageSimData}). A net exporter of the item's category
     * prices it down; a net importer prices it up. This is distinct from
     * the spot stockpile/need signals above: those are this-moment
     * snapshots (immediate availability / urgency), whereas this is the
     * village's rolling production-vs-consumption character — so the two
     * are complementary, not double-counted, and the final clamp bounds
     * any compounding.
     *
     * <p>O(1): reads the daily-cached sim, no per-tick recompute. Returns
     * neutral {@code 1.0} when the category is null/non-physical or the
     * village has no sim data yet (fresh village).</p>
     */
    private static double perVillageModifier(Village village, VillageSavedData data,
                                             ResourceCategory category) {
        if (village == null || category == null || !category.isPhysical()) return 1.0;
        VillageSimData sim = data.getSimData(village.getId()).orElse(null);
        if (sim == null) return 1.0;
        float prod  = sim.production(category);
        float cons  = sim.consumption(category);
        float total = prod + cons;
        if (total <= 0f) return 1.0; // neither produced nor consumed → neutral
        float ratio = (prod - cons) / total; // [-1,+1]: + exporter, - importer
        return 1.0 - PER_VILLAGE_STRENGTH * ratio; // exporter < 1, importer > 1
    }

    private static int countInStockpile(ServerLevel level, Village village,
                                        VillageSavedData data, Item item) {
        return village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == BuildingType.STOCKPILE)
                .mapToInt(stockpile ->
                        BuildingStorageAccess.countItem(level, stockpile, item))
                .sum();
    }
}
