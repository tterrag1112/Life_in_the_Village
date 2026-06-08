package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Letters.BookCategory;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;
import tterrag1112.life_in_the_village.Npc.Scribal.BookRecord;
import tterrag1112.life_in_the_village.Npc.Scribal.LibraryCatalogue;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.BuildingEconomy;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.EconomicBalance;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingCondition;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    // ── R4d-2 surplus spending ───────────────────────────────────────────────
    /** Runway kept untouched so good works never cause insolvency (R4c). */
    private static final long  SOLVENCY_BUFFER = 7 * DAILY_COST;   // a week's costs
    /** Surplus spent per (weekly) pass — bounded, anti-runaway. */
    private static final long  SPEND_PER_PASS  = 40L;
    private static final int   SPEND_PERIOD_DAYS = 7;
    /** A villager with less than this is "needy" (alms recipient). */
    private static final long  NEEDY_THRESHOLD = 50L;
    private static final long  ALMS_PER_NPC    = 12L;
    private static final int   MAX_ALMS_RECIPIENTS = 3;
    private static final int   ALMS_MOOD       = 8;
    /** Cost to author + stock one religious library book. */
    private static final long  BOOK_COST       = 20L;
    /** Don't flood the library — cap temple-stocked religious books. */
    private static final int   RELIGIOUS_BOOK_CAP = 6;
    /** Topic prefix tagging a temple-stocked religious book (for the cap count). */
    private static final String RELIGION_TOPIC = "religion.";

    // ── R9b — read-only views for the temple screen ──────────────────────────
    // The thresholds above are the single source of truth for R4c decay; the
    // temple screen derives its health label through these helpers rather than
    // duplicating the magic numbers client-side.

    /** One day's wage + upkeep — the solvency unit. */
    public static long dailyCost() { return DAILY_COST; }

    /** The runway (a week's costs) kept untouched; surplus is treasury above this. */
    public static long solvencyBuffer() { return SOLVENCY_BUFFER; }

    /**
     * R9b — a player-facing health label for a religious building, derived from
     * the SAME solvency + {@link BuildingCondition} state R4c decays on (so the
     * screen never drifts from the simulation). Read-only; no new enum — the
     * screen renders the returned string.
     *
     * <ul>
     *   <li>{@code Abandoned} — RUINED, or insolvent past the abandon window.</li>
     *   <li>{@code Decaying}  — needs repair, or insolvent past the decay window.</li>
     *   <li>{@code At-risk}   — insolvent today but not yet decaying.</li>
     *   <li>{@code Flourishing} — surplus above the buffer and in good repair.</li>
     *   <li>{@code Solvent}   — covering costs, neither failing nor flush.</li>
     * </ul>
     */
    public static String healthLabel(BuildingCondition condition, long treasury,
                                     int daysInsolvent, boolean staffed) {
        if (condition == BuildingCondition.RUINED || daysInsolvent >= ABANDON_DAYS) {
            return "Abandoned";
        }
        if (condition.needsRepair() || daysInsolvent >= DECAY_DAYS) {
            return "Decaying";
        }
        if (daysInsolvent > 0) {
            return "At-risk";
        }
        if (treasury >= SOLVENCY_BUFFER) {
            return "Flourishing";
        }
        return "Solvent";
    }

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

            // R4c-2 — auto-rehire: a functional (repaired, not RUINED), vacant,
            // SOLVENT religious building gets a priest again — preferring the
            // dormant R4c-abandoned ex-priest. The solvency gate ties recovery to
            // renewed giving and avoids re-abandon churn ("don't churn").
            if (b.getCondition().isFunctional()
                    && econ.getTreasury() >= DAILY_COST
                    && findAssignedPriest(level, village, data, bid).isEmpty()) {
                restaff(level, village, data, b);
            }

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

            // R4d-2 — a temple with genuine surplus spends it on good works
            // (alms + library books). Only surplus ABOVE the solvency buffer is
            // spent, so R4c is never undermined.
            spendSurplus(level, village, data, b, econ, currentTick);
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
        return findAssignedPriest(level, village, data, buildingId)
                .map(p -> { p.clearAssignedBuilding(); return true; })
                .orElse(false);
    }

    /** The loaded PRIEST currently assigned to {@code buildingId}, if any. */
    private static java.util.Optional<TownspersonMob> findAssignedPriest(
            ServerLevel level, Village village, VillageSavedData data, UUID buildingId) {
        AABB bounds = village.getBounds(data).map(b -> b.inflate(32)).orElse(null);
        if (bounds == null) return java.util.Optional.empty();
        return level.getEntitiesOfClass(TownspersonMob.class, bounds,
                        npc -> npc.getProfession() == Profession.PRIEST
                                && npc.getAssignedBuildingId().map(buildingId::equals).orElse(false))
                .stream().findFirst();
    }

    // ── R4d-2 — surplus → good works (alms + library books) ──────────────────

    /**
     * Spends a religious building's GENUINE surplus (treasury above
     * {@link #SOLVENCY_BUFFER}) on alms + library books, weekly + staggered. Needs
     * a seated priest (good works are clergy-led); a {@code TraitAxis.COMPASSION}
     * priest favours alms. Bounded by {@link #SPEND_PER_PASS}; never dips below the
     * buffer, so R4c solvency is untouched.
     */
    private static void spendSurplus(ServerLevel level, Village village,
                                     VillageSavedData data, Building building,
                                     BuildingEconomy econ, long currentTick) {
        long surplus = econ.getTreasury() - SOLVENCY_BUFFER;
        if (surplus <= 0) return;                                  // at/below buffer → nothing
        long day = currentTick / DAY;
        if ((day + Math.floorMod(building.getId().hashCode(), SPEND_PERIOD_DAYS)) % SPEND_PERIOD_DAYS != 0) {
            return;                                                // weekly, staggered
        }
        TownspersonMob priest = findAssignedPriest(level, village, data, building.getId()).orElse(null);
        if (priest == null) return;                                // no clergy → no good works

        long budget = Math.min(surplus, SPEND_PER_PASS);
        // Compassion ∈ [-1,1] → alms share ∈ [0.1,0.9] (Callous gives little).
        float compassion = priest.getTraitVector().get(TraitAxis.COMPASSION);
        float almsShare = Math.max(0.1f, Math.min(0.9f, 0.5f + compassion * 0.4f));
        long almsBudget = Math.round(budget * almsShare);

        long spent = distributeAlms(level, village, data, econ, almsBudget, currentTick);
        spent += stockLibraryBook(level, village, data, econ, building, priest,
                budget - spent, currentTick);
        if (spent > 0) data.setDirty();
    }

    /** Tops up the neediest loaded villagers (wallet &lt; {@link #NEEDY_THRESHOLD})
     *  from the temple economy + a small mood lift. Returns bronze spent. */
    private static long distributeAlms(ServerLevel level, Village village,
                                       VillageSavedData data, BuildingEconomy econ,
                                       long budget, long now) {
        if (budget <= 0) return 0L;
        AABB bounds = village.getBounds(data).map(b -> b.inflate(32)).orElse(null);
        if (bounds == null) return 0L;
        List<TownspersonMob> needy = new ArrayList<>(level.getEntitiesOfClass(
                TownspersonMob.class, bounds,
                m -> m.isAlive() && !m.isVisitor()
                        && m.getAssignedVillageName().map(n -> n.equals(village.getName())).orElse(false)
                        && m.getWallet().toBronze() < NEEDY_THRESHOLD));
        needy.sort(Comparator.comparingLong(m -> m.getWallet().toBronze()));

        long spent = 0L;
        int given = 0;
        for (TownspersonMob m : needy) {
            if (given >= MAX_ALMS_RECIPIENTS || spent >= budget) break;
            long alm = Math.min(ALMS_PER_NPC, Math.min(budget - spent, econ.getTreasury() - SOLVENCY_BUFFER));
            if (alm <= 0) break;
            econ.withdraw(alm);
            m.getWallet().receive(CurrencyValue.of(alm));
            m.getMood().applyWithRawMagnitude(MoodTrigger.GIFT_RECEIVED, ALMS_MOOD, now);
            spent += alm;
            given++;
        }
        return spent;
    }

    /** Authors one religious book of the faith's {@code preferredBookCategories}
     *  (reviving the dead field) and stocks the village library, funded by surplus.
     *  Bounded by {@link #RELIGIOUS_BOOK_CAP}. Returns bronze spent. */
    private static long stockLibraryBook(ServerLevel level, Village village,
                                         VillageSavedData data, BuildingEconomy econ,
                                         Building building, TownspersonMob priest,
                                         long budget, long now) {
        if (budget < BOOK_COST) return 0L;
        if (econ.getTreasury() - SOLVENCY_BUFFER < BOOK_COST) return 0L;
        UUID libraryId = firstLibrary(village, data);
        if (libraryId == null) return 0L;                          // no library to stock

        String faith = BuildingFaith.resolveFaith(level, village, building);
        if (faith == null) return 0L;
        Religion religion = ReligionRegistry.get(faith);
        if (religion == null || religion.preferredBookCategories().isEmpty()) return 0L;

        LibraryCatalogue cat = data.getOrCreateLibraryCatalogue(libraryId);
        long stocked = cat.all().stream()
                .filter(r -> r.topicsCovered().stream().anyMatch(t -> t.startsWith(RELIGION_TOPIC)))
                .count();
        if (stocked >= RELIGIOUS_BOOK_CAP) return 0L;              // library full of faith books

        List<BookCategory> prefs = religion.preferredBookCategories();
        BookCategory category = prefs.get((int) (stocked % prefs.size())); // rotate for variety
        String title = religion.displayName() + " — " + readable(category);

        BookRecord record = new BookRecord(
                UUID.randomUUID(), title, priest.getNpcName(),
                java.util.Optional.of(priest.getUUID()),
                List.of(RELIGION_TOPIC + faith, "category." + category.name().toLowerCase()),
                java.util.Optional.empty(), 3, now, 1);
        cat.acquire(record);
        econ.withdraw(BOOK_COST);
        return BOOK_COST;
    }

    private static UUID firstLibrary(Village village, VillageSavedData data) {
        for (UUID bid : village.getBuildingIds()) {
            Building b = data.getBuildingById(bid).orElse(null);
            if (b != null && b.getType() == BuildingType.LIBRARY) return bid;
        }
        return null;
    }

    private static String readable(BookCategory c) {
        String n = c.name().toLowerCase();
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** A dormant, building-less PRIEST in the village (the R4c-abandoned ex-priest),
     *  excluding visitors and away pilgrims — the preferred re-staff source. */
    private static java.util.Optional<TownspersonMob> findDormantPriest(
            ServerLevel level, Village village, VillageSavedData data) {
        AABB bounds = village.getBounds(data).map(b -> b.inflate(32)).orElse(null);
        if (bounds == null) return java.util.Optional.empty();
        return level.getEntitiesOfClass(TownspersonMob.class, bounds,
                        npc -> npc.isAlive()
                                && npc.getProfession() == Profession.PRIEST
                                && !npc.isVisitor()
                                && !npc.getRoles().hasRole(
                                        tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes.PILGRIM)
                                && npc.getAssignedBuildingId().isEmpty()
                                && npc.getAssignedVillageName()
                                        .map(n -> n.equals(village.getName())).orElse(false))
                .stream().findFirst();
    }

    /** Re-staffs a functional vacant religious building: rehire a dormant
     *  ex-priest where possible (re-applying the building's faith + order), else
     *  spawn a fresh priest via the populator path (which applies faith + order
     *  itself). */
    private static void restaff(ServerLevel level, Village village,
                                VillageSavedData data, Building building) {
        TownspersonMob priest = findDormantPriest(level, village, data).orElse(null);
        if (priest != null) {
            priest.assignToBuilding(building.getId(), village.getName());
            BuildingFaith.applyClergyFaith(level, village, priest, building);
            ClergyOrders.assignClergyOrder(level, priest);
        } else {
            tterrag1112.life_in_the_village.Village.Buildings.Inhabitants.VillageInhabitantPopulator
                    .spawnWorkerInBuilding(level, building, village, Profession.PRIEST);
        }
        data.setDirty();
    }
}
