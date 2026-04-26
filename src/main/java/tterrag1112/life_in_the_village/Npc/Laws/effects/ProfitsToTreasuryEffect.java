package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.LawParams;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * PROFITS_TO_TREASURY: workshop profits are skimmed at the configured
 * fraction. Hooked by the workshop's daily-pay routine via
 * {@link tterrag1112.life_in_the_village.Npc.Laws.LawTaxHooks#profitsToTreasuryFraction}.
 */
public final class ProfitsToTreasuryEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.PROFITS_TO_TREASURY; }
    @Override public float profitsToTreasuryFraction(Village v, LawParams params) {
        return Math.max(0f, Math.min(0.5f,
                params.numeric(VillageLaw.PROFITS_TO_TREASURY, "profit_fraction")));
    }
}
