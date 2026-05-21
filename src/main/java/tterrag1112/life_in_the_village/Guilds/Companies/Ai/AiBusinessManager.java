package tterrag1112.life_in_the_village.Guilds.Companies.Ai;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 4 doc 26 — daily decision loop for NPC-owned businesses.
 *
 * <h3>Per-tick responsibilities</h3>
 * <ol>
 *   <li>Check the owner: alive and still a MERCHANT? If dead or
 *   demoted, run {@link #handleSuccession}.</li>
 *   <li>Run the bankruptcy clock — 14 days below the warning floor
 *   triggers a warning; 30 more days dissolves the business.</li>
 *   <li>Eligibility scan: any unpromoted MERCHANT in the village
 *   who meets {@link MerchantPromotion#isEligible} is promoted on
 *   their next workday.</li>
 *   <li>Trading-business caravan dispatch — stub for v1; the wire
 *   into {@code CaravanSavedData} lands when the caravan goods-
 *   selector exposes a public dispatch API.</li>
 * </ol>
 *
 * <h3>Trait-biased decisions</h3>
 * The wage / hire / dispatch decisions read the owner's
 * {@link TraitAxis}: high Ambition → expand aggressively; high
 * Industry → maintain; low Temperance → risky; high Compassion →
 * above-market wages. v1 surfaces the trait values via
 * {@link OwnerBias} so future logic can read them without re-
 * fetching the owner mob each tick.
 */
public final class AiBusinessManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Spec "Open decisions" — 50 br below expense floor for 14
     *  days triggers a warning. */
    public static final long BANKRUPTCY_FLOOR    = 50L;
    /** Spec — once warned, 30 more days to dissolution. */
    public static final long DISSOLUTION_GRACE_DAYS = 30L;
    /** Spec — initial warning fires after 14 days underwater. */
    public static final long WARNING_THRESHOLD_DAYS = 14L;
    /** Spec line 125 — UNDECIDED grace period after owner death. */
    public static final long UNDECIDED_GRACE_DAYS   = 30L;
    /** Spec line 144 — trading-business route distance multiplier
     *  on top of the existing village-merchant cap (3000 blocks). */
    public static final double TRADING_RANGE_MULTIPLIER = 3.0;

    private static final long DAY = 24000L;

    private AiBusinessManager() {}

    /** Daily entry point — iterates every NPC-owned business. */
    public static void dailyTick(ServerLevel level) {
        long now = level.getGameTime();
        BusinessSavedData cdata = BusinessSavedData.get(level);
        for (Business business : new ArrayList<>(cdata.getAllBusinesses())) {
            if (!business.isNpcOwned()) continue;
            tickBusiness(level, business, now);
        }
        // Run the merchant promotion scan on the same daily cadence.
        runPromotionScan(level, now);
    }

    private static void tickBusiness(ServerLevel level, Business business, long now) {
        // Already dissolved — nothing to do.
        if (business.getSuccessionState() == Business.SuccessionState.DISSOLVED) return;

        // Owner liveness / profession check.
        UUID ownerId = business.getOwnerId();
        TownspersonMob owner = ownerId == null ? null
                : TownspersonMob.findByUUID(level, ownerId).orElse(null);
        boolean ownerLost = owner == null || !owner.isAlive()
                || owner.getProfession() == Profession.NONE;
        if (ownerLost && business.getSuccessionState() == Business.SuccessionState.ACTIVE) {
            handleSuccession(level, business, now);
            BusinessSavedData.get(level).addBusiness(business);
            return;
        }

        // Run the bankruptcy clock.
        long expenseFloor = estimateDailyExpenses(business);
        long balance = business.getTreasuryBronze();
        if (balance + BANKRUPTCY_FLOOR < expenseFloor) {
            if (business.getDissolutionWarningTick() == 0L) {
                business.setDissolutionWarningTick(now);
                LOGGER.info("[AiBusinessManager] {} ({}) — bankruptcy warning",
                        business.getName(), business.getBusinessId());
            } else {
                long daysWarned = (now - business.getDissolutionWarningTick()) / DAY;
                if (daysWarned >= WARNING_THRESHOLD_DAYS + DISSOLUTION_GRACE_DAYS) {
                    dissolve(level, business, now);
                    BusinessSavedData.get(level).addBusiness(business);
                    return;
                }
            }
        } else {
            // Recovery — reset the warning clock.
            if (business.getDissolutionWarningTick() != 0L) {
                business.setDissolutionWarningTick(0L);
            }
        }

        // UNDECIDED grace window — dissolve if no resolution.
        if (business.getSuccessionState() == Business.SuccessionState.UNDECIDED
                && business.getUndecidedSinceTick() != 0L
                && now - business.getUndecidedSinceTick() >= UNDECIDED_GRACE_DAYS * DAY) {
            dissolve(level, business, now);
            BusinessSavedData.get(level).addBusiness(business);
            return;
        }

        // Trait-biased reaffirm of the owner is fetched but the
        // hire/fire/wage decisions live on the existing payroll path
        // (Business.runPayroll); the AI manager reads OwnerBias for the
        // future caravan-dispatch extension.
        OwnerBias.of(owner);
    }

    // ── Succession ────────────────────────────────────────────────────────

    /**
     * Phase 6.3.3.c.6 — succession via {@link SuccessionRegistry}
     * predicate stack. The legacy linear heir iteration is now
     * predicate #1 (DESIGNATED_HEIR); additional predicates
     * (FAMILY_SUCCESSOR, APPRENTICE_HEIR) cover cases the empty
     * heir list misses. Result is sealed:
     * <ul>
     *   <li>HeirFound → seat the new owner, exit UNDECIDED</li>
     *   <li>TransferredToPool → mark UNDECIDED but no dissolve clock
     *       (future content reassigns from the pool; here we just
     *       set the state)</li>
     *   <li>NoEligibleSuccessor → legacy UNDECIDED + 30-day clock,
     *       eventually dissolve</li>
     * </ul>
     */
    public static void handleSuccession(ServerLevel level, Business business, long now) {
        String cultureId = resolveCulture(level, business);
        SuccessionResult result = SuccessionRegistry.resolve(cultureId, business, level);
        switch (result) {
            case SuccessionResult.HeirFound found -> {
                business.setOwnership(new tterrag1112.life_in_the_village
                        .Guilds.Companies.BusinessOwner.NpcOwner(found.uuid()));
                business.setSuccessionState(Business.SuccessionState.ACTIVE);
                business.setUndecidedSinceTick(0L);
                // Refresh heirs on the new owner's family tree.
                BusinessHeirs.populateForOwner(level, business);
                LOGGER.info("[AiBusinessManager] {} succeeded by {} (predicate-stack)",
                        business.getName(), found.uuid());
            }
            case SuccessionResult.TransferredToPool ignored -> {
                business.setSuccessionState(Business.SuccessionState.UNDECIDED);
                // Park without starting the dissolve clock — content
                // pack will reassign from village pool.
                business.setUndecidedSinceTick(0L);
                LOGGER.info("[AiBusinessManager] {} transferred to village allocation pool",
                        business.getName());
            }
            case SuccessionResult.NoEligibleSuccessor ignored -> {
                business.setSuccessionState(Business.SuccessionState.UNDECIDED);
                business.setUndecidedSinceTick(now);
                LOGGER.info("[AiBusinessManager] {} entered UNDECIDED (no eligible successor)",
                        business.getName());
            }
        }
    }

    /** Resolves the culture id from the business's home village. */
    private static String resolveCulture(ServerLevel level, Business business) {
        var vdata = tterrag1112.life_in_the_village.Networking.VillageSavedData.get(level);
        return vdata.getVillageById(business.getHomeVillageId())
                .map(v -> vdata.getKingdomForVillage(v.getId())
                        .map(tterrag1112.life_in_the_village.Kingdom.Kingdom::getCulture)
                        .orElse(null))
                .orElse(null);
    }

    /**
     * Distributes the treasury as severance to remaining workers and
     * marks the business DISSOLVED. The BusinessSavedData entry stays
     * (so historical lookups still resolve) but {@link Business#isActive()}
     * goes false.
     */
    public static void dissolve(ServerLevel level, Business business, long now) {
        long treasury = business.getTreasuryBronze();
        List<Business.BusinessWorker> workers = new ArrayList<>(business.getWorkers());
        if (!workers.isEmpty() && treasury > 0L) {
            long share = Math.max(1L, treasury / workers.size());
            for (Business.BusinessWorker w : workers) {
                TownspersonMob mob = TownspersonMob.findByUUID(level, w.npcId()).orElse(null);
                if (mob != null) {
                    mob.getWallet().receive(share);
                    business.withdrawBronze(share);
                }
            }
        }
        business.setSuccessionState(Business.SuccessionState.DISSOLVED);
        business.setActive(false);
        LOGGER.info("[AiBusinessManager] {} ({}) dissolved — {} br severance",
                business.getName(), business.getBusinessId(), treasury);
        // Phase 4 doc 30 archival hook.
        tterrag1112.life_in_the_village.Village.Village v =
                tterrag1112.life_in_the_village.Networking.VillageSavedData.get(level)
                        .getVillageById(business.getHomeVillageId()).orElse(null);
        if (v != null) {
            java.util.Map<String, String> details = new java.util.LinkedHashMap<>();
            details.put("village_name", v.getName());
            details.put("company_name", business.getName());
            tterrag1112.life_in_the_village.Village.History.HistoryProducer.record(
                    level, v,
                    tterrag1112.life_in_the_village.Village.History.HistoryEventType.BUSINESS_DISSOLVED,
                    now, details, java.util.List.of());
        }
    }

    // ── Promotion scan ───────────────────────────────────────────────────

    private static void runPromotionScan(ServerLevel level, long now) {
        VillageSavedData vdata = VillageSavedData.get(level);
        for (var village : vdata.getAllVillages()) {
            var bounds = village.getBounds(vdata).orElse(null);
            if (bounds == null) continue;
            List<TownspersonMob> merchants = level.getEntitiesOfClass(
                    TownspersonMob.class, bounds.inflate(16),
                    m -> m.getProfession() == Profession.MERCHANT
                            && m.getAssignedVillageName()
                                    .map(n -> n.equals(village.getName()))
                                    .orElse(false));
            for (TownspersonMob merchant : merchants) {
                if (!MerchantPromotion.isEligible(merchant, level)) continue;
                // Skip if the NPC already owns a TRADING_COMPANY.
                boolean alreadyOwns = BusinessSavedData.get(level).getAllBusinesses().stream()
                        .anyMatch(c -> c.isNpcOwned()
                                && merchant.getUUID().equals(c.getOwnerId())
                                && c.isTradingBusiness()
                                && c.isActive());
                if (alreadyOwns) continue;
                MerchantPromotion.promote(level, merchant);
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Rough daily expenses: sum of worker wages. Phase 5 polish can
     *  add hall maintenance and request bounties. */
    public static long estimateDailyExpenses(Business business) {
        long total = 0L;
        for (Business.BusinessWorker w : business.getWorkers()) {
            total += w.wagePerDay();
        }
        return total;
    }

    /**
     * Trait snapshot read off the owner. Used by future caravan
     * dispatch + wage adjustments. v1 just surfaces the values — the
     * decision rules live where the actions are taken (e.g. the
     * caravan dispatch extension).
     */
    public record OwnerBias(float ambition, float industry,
                            float temperance, float compassion) {

        public static final OwnerBias NEUTRAL = new OwnerBias(0f, 0f, 0f, 0f);

        public static OwnerBias of(TownspersonMob owner) {
            if (owner == null) return NEUTRAL;
            var traits = owner.getTraitVector();
            return new OwnerBias(
                    traits.get(TraitAxis.AMBITION),
                    traits.get(TraitAxis.INDUSTRY),
                    traits.get(TraitAxis.TEMPERANCE),
                    traits.get(TraitAxis.COMPASSION));
        }

        public boolean isAggressiveExpander()  { return ambition  > 0.4f; }
        public boolean isMaintenanceOriented() { return industry  > 0.4f; }
        public boolean isRiskTaker()           { return temperance < -0.4f; }
        public boolean isCompassionateEmployer() { return compassion > 0.4f; }
    }

    /**
     * Trading-business caravan dispatch. Spec line 144 — 3x village
     * range. Real wiring depends on a public dispatch API on
     * {@code CaravanSavedData} (not yet exposed); v1 still skips the
     * physical caravan but accounts a worker-count-scaled profit so
     * trading-business treasuries actually grow at a rate that reflects
     * payroll capacity. Phase 5 wires the actual caravan goods
     * selector + travel + per-goods revenue.
     */
    public static long dispatchTradingCaravan(ServerLevel level, Business business) {
        if (!business.isTradingBusiness()) return 0L;
        // Minimum 30br even for a sole proprietor; +20br per additional
        // worker; capped so a max business doesn't print money.
        int workerCount = Math.max(1, business.getWorkers().size());
        long profit = Math.min(500L, 30L + (long) (workerCount - 1) * 20L);
        business.depositBronze(profit);
        LOGGER.info("[AiBusinessManager] {} dispatched trading caravan (+{} br, {} worker(s))",
                business.getName(), profit, workerCount);
        return profit;
    }
}
