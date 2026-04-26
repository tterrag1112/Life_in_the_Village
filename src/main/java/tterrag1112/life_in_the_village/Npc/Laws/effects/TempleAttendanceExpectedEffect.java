package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;

/**
 * TEMPLE_ATTENDANCE_EXPECTED: spec mentions in social-laws block
 * (line 32). Phase 3 doc 20 (religion + priest, future session) will
 * read this to add a small reputation hit on adults who skip rites.
 * Phase 3 ships the registry slot with no behaviour.
 */
public final class TempleAttendanceExpectedEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.TEMPLE_ATTENDANCE_EXPECTED; }
}
