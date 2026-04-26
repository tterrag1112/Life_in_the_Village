package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;

/**
 * GUILD_MEMBERSHIP_REQUIRED: blocks non-guild NPCs from selling
 * crafted output via MarketChannel. Spec line 182. Phase 4 task 27
 * (guild refactor) lands the explicit guild-membership ledger; Phase 3
 * keeps the registry slot. The MarketChannel sell path doesn't
 * currently consult guild membership; documented in 22 Revision Notes
 * as a follow-up.
 */
public final class GuildMembershipRequiredEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.GUILD_MEMBERSHIP_REQUIRED; }
}
