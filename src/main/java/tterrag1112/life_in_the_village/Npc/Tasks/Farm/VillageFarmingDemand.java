package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Tasks.TaskPriority;
import tterrag1112.life_in_the_village.Village.Needs.NeedCategory;
import tterrag1112.life_in_the_village.Village.Needs.NeedLevel;
import tterrag1112.life_in_the_village.Village.Needs.VillageNeed;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * G3a — read-only helper that maps a village's cached FOOD {@link NeedLevel}
 * to {@link TaskPriority} tiers for harvest and replant/till tasks.
 *
 * <p>This class issues <em>nothing</em>; it only reads the snapshot that
 * {@code VillageDailyTickSystem} wrote at most one game-day ago and returns
 * tier values for {@link FarmTaskSource#generate} to apply at its existing
 * upsert call sites.</p>
 *
 * <h3>Tier mapping</h3>
 * <pre>
 *   FOOD CRITICAL → harvest=CRITICAL, replant/till=HIGH
 *   FOOD LOW      → harvest=HIGH,     replant/till=NORMAL
 *   FOOD SATISFIED
 *   FOOD SURPLUS  → harvest=NORMAL,   replant/till=LOW   (behavior-preserving)
 * </pre>
 *
 * <p>COMPOST, SELL-surplus, and seed-ACQUIRE tasks are not affected —
 * they carry their own urgency signals and are not food-critical operations.</p>
 */
public final class VillageFarmingDemand {

    private VillageFarmingDemand() {}

    /**
     * Returns the FOOD {@link NeedLevel} for {@code farmer}'s assigned village.
     *
     * <p>Null-safe: if the farmer has no assigned village, the village has not
     * yet computed needs (world start), or FOOD is absent from the needs map,
     * returns {@link NeedLevel#SATISFIED} — the default no-boost state.</p>
     */
    public static NeedLevel foodLevel(ServerLevel level, TownspersonMob farmer) {
        String villageName = farmer.getAssignedVillageName().orElse(null);
        if (villageName == null) return NeedLevel.SATISFIED;

        Village village = VillageSavedData.get(level)
                .getVillageByName(villageName)
                .orElse(null);
        if (village == null) return NeedLevel.SATISFIED;

        VillageNeed foodNeed = village.getNeeds().get(NeedCategory.FOOD);
        if (foodNeed == null) return NeedLevel.SATISFIED;   // not yet computed

        return foodNeed.getLevel();
    }

    /**
     * Returns the {@link TaskPriority} tier that HARVEST tasks should use
     * given the current food {@link NeedLevel}.
     */
    public static TaskPriority harvestTier(NeedLevel food) {
        return switch (food) {
            case CRITICAL  -> TaskPriority.CRITICAL;
            case LOW       -> TaskPriority.HIGH;
            default        -> TaskPriority.NORMAL;   // SATISFIED / SURPLUS
        };
    }

    /**
     * Returns the {@link TaskPriority} tier that REPLANT and TILL tasks should
     * use given the current food {@link NeedLevel}.
     */
    public static TaskPriority replantTillTier(NeedLevel food) {
        return switch (food) {
            case CRITICAL  -> TaskPriority.HIGH;
            case LOW       -> TaskPriority.NORMAL;
            default        -> TaskPriority.LOW;      // SATISFIED / SURPLUS
        };
    }
}
