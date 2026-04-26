package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.LawParams;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * PRICE_FLOOR_FOOD: raises food quotes to at least {@code
 * min_bronze_per_unit}. Spec line 182.
 */
public final class PriceFloorFoodEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.PRICE_FLOOR_FOOD; }
    @Override public long foodPriceFloor(Village v, LawParams params) {
        return Math.max(1L, Math.round(params.numeric(VillageLaw.PRICE_FLOOR_FOOD, "min_bronze_per_unit")));
    }
}
