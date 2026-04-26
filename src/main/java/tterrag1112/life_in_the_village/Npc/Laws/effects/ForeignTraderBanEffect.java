package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.LawParams;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * FOREIGN_TRADER_BAN: blocks CARAVAN-channel trades for outsiders. Spec
 * line 169. CaravanChannel reads
 * {@link tterrag1112.life_in_the_village.Npc.Laws.LawPriceHooks#caravanBlocked}
 * at quote time and returns empty when set.
 */
public final class ForeignTraderBanEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.FOREIGN_TRADER_BAN; }
    @Override public boolean blocksForeignCaravan(Village v, LawParams p) { return true; }
}
