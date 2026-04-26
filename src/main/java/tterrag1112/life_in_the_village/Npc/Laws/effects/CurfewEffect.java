package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.LawParams;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * CURFEW: forces HOME phase from the configured daytick onward. Spec
 * line 176. v1 hardcodes the curfew start to 12000 (mid-evening,
 * Minecraft daytick); the crime-system pass (next session) will read
 * the same constant when it triggers TRESPASSING for nighttime
 * outside-home presence.
 */
public final class CurfewEffect implements LawEffect {

    /** Daytick at which CURFEW shifts every NPC's resolved phase to HOME. */
    public static final int CURFEW_DAYTICK = 12000;

    @Override public VillageLaw law() { return VillageLaw.CURFEW; }
    @Override public int curfewDaytickStart(Village v, LawParams p) { return CURFEW_DAYTICK; }
}
