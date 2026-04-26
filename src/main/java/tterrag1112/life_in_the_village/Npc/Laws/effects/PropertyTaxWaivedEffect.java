package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.LawParams;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Village.Village;

/** PROPERTY_TAX_WAIVED: suspends property tax for as long as enacted. */
public final class PropertyTaxWaivedEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.PROPERTY_TAX_WAIVED; }
    @Override public double propertyTaxMultiplier(Village v, LawParams p) { return 0.0; }
}
