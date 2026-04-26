package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.LawParams;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * TOLL_ENTRY: visitor pays {@code toll_amount} bronze on entry. Spec
 * line 168. Phase 4 visitor flux (doc 29) wires the actual collection
 * point; Phase 3 ships the parameter accessor so the visitor system
 * only has to call {@link LawEffect#entryToll}.
 */
public final class TollEntryEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.TOLL_ENTRY; }
    @Override public long entryToll(Village village, LawParams params) {
        return Math.max(0L, Math.round(params.numeric(VillageLaw.TOLL_ENTRY, "toll_amount")));
    }
}
