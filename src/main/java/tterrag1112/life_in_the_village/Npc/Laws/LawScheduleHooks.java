package tterrag1112.life_in_the_village.Npc.Laws;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Schedule.DayPhase;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * Schedule-side facade. Called from {@link
 * tterrag1112.life_in_the_village.Npc.Schedule.ScheduleResolver#phaseAt}
 * after the base phase is resolved, so a CURFEW law can shove the NPC
 * into HOME after the curfew daytick.
 *
 * <p>FESTIVAL_MANDATORY and COMPULSORY_SCHOOLING expose flags here but
 * have no behaviour change in v1 — the festival-attendance enforcer
 * lives in Phase 5 doc 32, the child schooling lives with the Phase 2
 * task 15 child-arc system. Both effects' registry slots and flag
 * accessors land now so the future sessions only have to call them.</p>
 */
public final class LawScheduleHooks {

    private LawScheduleHooks() {}

    /**
     * Possibly returns an overridden {@link DayPhase} based on the
     * NPC's village policy. Currently only CURFEW shifts the phase.
     * Other laws return the base unchanged.
     */
    public static DayPhase applyLaws(TownspersonMob npc, long gameTick, DayPhase base) {
        if (npc == null) return base;
        // ScheduleResolver runs hot — bail when the NPC has no village
        // assignment to dodge the saved-data lookup.
        if (npc.getAssignedVillageName().isEmpty()) return base;
        if (!(npc.level() instanceof net.minecraft.server.level.ServerLevel sl)) return base;
        VillageSavedData data = VillageSavedData.get(sl);
        Village village = npc.getAssignedVillageName().flatMap(data::getVillageByName).orElse(null);
        if (village == null) return base;
        VillagePolicy policy = village.getPolicy();
        if (policy == null) return base;

        long dayTick = ((gameTick % 24000L) + 24000L) % 24000L;
        // CURFEW: any active CURFEW law forces HOME after the start daytick.
        for (VillageLaw law : policy.activeLaws()) {
            if (policy.isSuspended(law)) continue;
            LawEffect e = LawEffectRegistry.get(law);
            if (e == null) continue;
            int curfewStart = e.curfewDaytickStart(village, policy.getParams(law).orElse(LawParams.empty()));
            if (curfewStart >= 0 && dayTick >= curfewStart) {
                return DayPhase.HOME;
            }
        }
        return base;
    }

    /**
     * True iff the village has any active law forcing festival
     * attendance. Phase 5 doc 32 reads this when scheduling festival
     * events to flip every adult's event override.
     */
    public static boolean festivalMandatory(Village village) {
        return anyEffect(village, e -> e.festivalAttendanceMandatory(village, paramsFor(village, e.law())));
    }

    /**
     * True iff the village forces children into a daily schooling
     * window. Phase 2 task 15 child-arc system reads this.
     */
    public static boolean compulsorySchooling(Village village) {
        return anyEffect(village, e -> e.compelsChildSchooling(village, paramsFor(village, e.law())));
    }

    private static boolean anyEffect(Village village, java.util.function.Predicate<LawEffect> test) {
        if (village == null) return false;
        VillagePolicy policy = village.getPolicy();
        if (policy == null) return false;
        for (VillageLaw law : policy.activeLaws()) {
            if (policy.isSuspended(law)) continue;
            LawEffect e = LawEffectRegistry.get(law);
            if (e != null && test.test(e)) return true;
        }
        return false;
    }

    private static LawParams paramsFor(Village v, VillageLaw law) {
        if (v == null) return LawParams.empty();
        VillagePolicy policy = v.getPolicy();
        return policy == null ? LawParams.empty() : policy.getParams(law).orElse(LawParams.empty());
    }
}
