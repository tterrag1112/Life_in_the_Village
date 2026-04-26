package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.LawParams;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * MARKET_TAX_DOUBLE: doubles the village's market-tax slice. Spec line
 * 153. Multiplier consumed by {@link
 * tterrag1112.life_in_the_village.Npc.Laws.LawTaxHooks#marketTaxMultiplier}.
 */
public final class MarketTaxDoubleEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.MARKET_TAX_DOUBLE; }
    @Override public double marketTaxMultiplier(Village v, LawParams p) { return 2.0; }
}
