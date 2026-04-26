package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.LawParams;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * MARKET_TAX_REDUCED: halves the village's market-tax slice. Spec line
 * 153.
 */
public final class MarketTaxReducedEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.MARKET_TAX_REDUCED; }
    @Override public double marketTaxMultiplier(Village v, LawParams p) { return 0.5; }
}
