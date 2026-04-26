package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.LawParams;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * PRICE_CEILING_FOOD: clamps food quotes to {@code max_bronze_per_unit}.
 * Spec line 181. Read by every channel that quotes food via
 * {@link tterrag1112.life_in_the_village.Npc.Laws.LawPriceHooks#priceCeiling}.
 */
public final class PriceCeilingFoodEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.PRICE_CEILING_FOOD; }
    @Override public long foodPriceCeiling(Village v, LawParams params) {
        return Math.max(1L, Math.round(params.numeric(VillageLaw.PRICE_CEILING_FOOD, "max_bronze_per_unit")));
    }
}
