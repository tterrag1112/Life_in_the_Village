package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.BuildingEconomy;
import tterrag1112.life_in_the_village.Village.Economy.EconomicBalance;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingCondition;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.UUID;

/**
 * Religion Rework R4c — couples a religious building's financial health (R4a
 * money) and the village's piety into the EXISTING {@link BuildingCondition}
 * decay, plus the priest-vacate trigger. The payoff loop: a solvent, devout
 * temple holds {@code MAINTAINED}/{@code WEATHERED} (flourishing); a sustainedly
 * insolvent, low-piety one decays through the ladder and — after a long deficit
 * window — its priest leaves, after which the existing resident-less path takes
 * it to {@code RUINED}. Recoverable if income/piety return before abandonment.
 *
 * <p>It does NOT fork the decay system: it nudges the same {@code setCondition}/
 * {@code degrade}/{@code repair} as {@code VillageAgingManager} (which keeps
 * running its slow base decay for ALL buildings) and reuses
 * {@code clearAssignedBuilding} for the vacate. Only religious buildings get the
 * financial coupling, so other buildings' decay is unchanged.</p>
 */
public final class TempleProsperity {

    private TempleProsperity() {}

    private static final long DAY = 24000L;

    /** A building is solvent for the day when it can cover one day's wage + upkeep. */
    private static final long DAILY_COST = EconomicBalance.PRIEST_DAILY_WAGE
            + EconomicBalance.TEMPLE_DAILY_UPKEEP;

    /** Sustained-insolvency days before decay kicks in; before abandonment. */
    private static final int  DECAY_DAYS   = 7;
    private static final int  ABANDON_DAYS = 21;
    /** Condition nudges fire on this cadence (days) — layered on the slow base decay. */
    private static final int  NUDGE_DAYS   = 3;

    private static final float LOW_PIETY  = 0.25f;
    private static final float HIGH_PIETY = 0.50f;
    /** Neutral piety used when no villagers are loaded to sample. */
    private static final float NEUTRAL_PIETY = 0.4f;

    /**
     * Daily per-village pass (called from {@link RiteScheduler#dailyTick}). Updates
     * each religious building's insolvency counter and nudges its condition.
     */
    public static void tickVillage(ServerLevel level, Village village,
                                   VillageSavedData data, long currentTick) {
        if (level == null || village == null) return;
        float piety = villagePiety(level, village, data);
        boolean nudgeDay = (currentTick / DAY) % NUDGE_DAYS == 0;

        for (UUID bid : village.getBuildingIds()) {
            Building b = data.getBuildingById(bid).orElse(null);
            if (b == null || !BuildingFaith.isReligiousBuilding(b.getType())) continue;
            BuildingEconomy econ = data.getOrCreateBuildingEconomy(bid);

            // Solvency signal — can it cover a day's costs?
            if (econ.getTreasury() >= DAILY_COST) econ.resetDaysInsolvent();
            else econ.incrementDaysInsolvent();
            int insolvent = econ.getDaysInsolvent();

            // Abandonment — last resort after a long deficit window.
            if (insolvent >= ABANDON_DAYS) {
                if (vacatePriest(level, village, data, bid)) {
                    econ.resetDaysInsolvent(); // the now-vacant temple decays via the base path
                    data.setDirty();
                }
                continue;
            }

            if (!nudgeDay) continue;

            BuildingCondition cur = b.getCondition();
            // Low village piety accelerates the deficit's bite.
            int effDecayDays = piety < LOW_PIETY ? Math.max(1, DECAY_DAYS - 3) : DECAY_DAYS;

            if (insolvent >= effDecayDays) {
                // Failing temple — descend the ladder one step. A STAFFED temple
                // bottoms out at DILAPIDATED (RUINED is reached only once vacant);
                // the recurring nudge re-degrades any builder repair, so it stays
                // visibly run-down while insolvent.
                if (cur == BuildingCondition.NEW || cur == BuildingCondition.MAINTAINED
                        || cur == BuildingCondition.WEATHERED) {
                    b.setCondition(cur.degrade());
                    data.markDirty();
                }
            } else if (cur != BuildingCondition.RUINED
                    && econ.getTreasury() >= DAILY_COST && piety >= HIGH_PIETY) {
                // Flourishing temple — the faithful keep it up (counters base
                // decay). A RUINED temple is NOT auto-resurrected by piety; it
                // recovers via the existing builder/player repair + re-staff.
                BuildingCondition repaired = cur.repair();
                if (repaired != cur) {
                    b.setCondition(repaired);
                    data.markDirty();
                }
            }
        }
    }

    /** Average primary-faith strength of loaded village residents, or
     *  {@link #NEUTRAL_PIETY} when none are loaded to sample. */
    private static float villagePiety(ServerLevel level, Village village, VillageSavedData data) {
        AABB bounds = village.getBounds(data).map(b -> b.inflate(32)).orElse(null);
        if (bounds == null) return NEUTRAL_PIETY;
        float sum = 0f;
        int n = 0;
        for (TownspersonMob m : level.getEntitiesOfClass(TownspersonMob.class, bounds,
                npc -> npc.isAlive() && !npc.isVisitor()
                        && npc.getAssignedVillageName()
                                .map(name -> name.equals(village.getName())).orElse(false))) {
            sum += m.getPiety().primaryStrength();
            n++;
        }
        return n == 0 ? NEUTRAL_PIETY : sum / n;
    }

    /** Unassigns the loaded PRIEST staffing {@code buildingId} (the minimal vacate
     *  — the building becomes resident-less and the existing decay path takes it
     *  to RUINED; {@code PriestBehavior} self-disables without a building). Returns
     *  true when a priest was vacated. */
    private static boolean vacatePriest(ServerLevel level, Village village,
                                        VillageSavedData data, UUID buildingId) {
        AABB bounds = village.getBounds(data).map(b -> b.inflate(32)).orElse(null);
        if (bounds == null) return false;
        for (TownspersonMob m : level.getEntitiesOfClass(TownspersonMob.class, bounds,
                npc -> npc.getProfession() == Profession.PRIEST
                        && npc.getAssignedBuildingId().map(buildingId::equals).orElse(false))) {
            m.clearAssignedBuilding();
            return true;
        }
        return false;
    }
}
