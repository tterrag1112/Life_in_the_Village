package tterrag1112.life_in_the_village.Guilds.Companies.Ai;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Company;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 4 doc 26 — daily decision loop for NPC-owned companies.
 *
 * <h3>Per-tick responsibilities</h3>
 * <ol>
 *   <li>Check the owner: alive and still a MERCHANT? If dead or
 *   demoted, run {@link #handleSuccession}.</li>
 *   <li>Run the bankruptcy clock — 14 days below the warning floor
 *   triggers a warning; 30 more days dissolves the company.</li>
 *   <li>Eligibility scan: any unpromoted MERCHANT in the village
 *   who meets {@link MerchantPromotion#isEligible} is promoted on
 *   their next workday.</li>
 *   <li>Trading-company caravan dispatch — stub for v1; the wire
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
public final class AiCompanyManager {

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
    /** Spec line 144 — trading-company route distance multiplier
     *  on top of the existing village-merchant cap (3000 blocks). */
    public static final double TRADING_RANGE_MULTIPLIER = 3.0;

    private static final long DAY = 24000L;

    private AiCompanyManager() {}

    /** Daily entry point — iterates every NPC-owned company. */
    public static void dailyTick(ServerLevel level) {
        long now = level.getGameTime();
        CompanySavedData cdata = CompanySavedData.get(level);
        for (Company company : new ArrayList<>(cdata.getAllCompanies())) {
            if (!company.isNpcOwned()) continue;
            tickCompany(level, company, now);
        }
        // Run the merchant promotion scan on the same daily cadence.
        runPromotionScan(level, now);
    }

    private static void tickCompany(ServerLevel level, Company company, long now) {
        // Already dissolved — nothing to do.
        if (company.getSuccessionState() == Company.SuccessionState.DISSOLVED) return;

        // Owner liveness / profession check.
        UUID ownerId = company.getOwnerId();
        TownspersonMob owner = ownerId == null ? null
                : TownspersonMob.findByUUID(level, ownerId).orElse(null);
        boolean ownerLost = owner == null || !owner.isAlive()
                || owner.getProfession() == Profession.NONE;
        if (ownerLost && company.getSuccessionState() == Company.SuccessionState.ACTIVE) {
            handleSuccession(level, company, now);
            CompanySavedData.get(level).addCompany(company);
            return;
        }

        // Run the bankruptcy clock.
        long expenseFloor = estimateDailyExpenses(company);
        long balance = company.getTreasuryBronze();
        if (balance + BANKRUPTCY_FLOOR < expenseFloor) {
            if (company.getDissolutionWarningTick() == 0L) {
                company.setDissolutionWarningTick(now);
                LOGGER.info("[AiCompanyManager] {} ({}) — bankruptcy warning",
                        company.getName(), company.getCompanyId());
            } else {
                long daysWarned = (now - company.getDissolutionWarningTick()) / DAY;
                if (daysWarned >= WARNING_THRESHOLD_DAYS + DISSOLUTION_GRACE_DAYS) {
                    dissolve(level, company, now);
                    CompanySavedData.get(level).addCompany(company);
                    return;
                }
            }
        } else {
            // Recovery — reset the warning clock.
            if (company.getDissolutionWarningTick() != 0L) {
                company.setDissolutionWarningTick(0L);
            }
        }

        // UNDECIDED grace window — dissolve if no resolution.
        if (company.getSuccessionState() == Company.SuccessionState.UNDECIDED
                && company.getUndecidedSinceTick() != 0L
                && now - company.getUndecidedSinceTick() >= UNDECIDED_GRACE_DAYS * DAY) {
            dissolve(level, company, now);
            CompanySavedData.get(level).addCompany(company);
            return;
        }

        // Trait-biased reaffirm of the owner is fetched but the
        // hire/fire/wage decisions live on the existing payroll path
        // (Company.runPayroll); the AI manager reads OwnerBias for the
        // future caravan-dispatch extension.
        OwnerBias.of(owner);
    }

    // ── Succession ────────────────────────────────────────────────────────

    /**
     * Spec lines 121-129. Tries the heir chain; if no heir, transitions
     * the company to {@link Company.SuccessionState#UNDECIDED} for 30
     * days; failing that, calls {@link #dissolve}.
     */
    public static void handleSuccession(ServerLevel level, Company company, long now) {
        for (UUID heirId : company.getHeirs()) {
            TownspersonMob heir = TownspersonMob.findByUUID(level, heirId).orElse(null);
            if (heir != null && heir.isAlive() && heir.isAdult()) {
                company.setNpcOwner(heirId);
                company.setSuccessionState(Company.SuccessionState.ACTIVE);
                LOGGER.info("[AiCompanyManager] {} succeeded by heir {}",
                        company.getName(), heirId);
                return;
            }
        }
        // No heir on file — enter UNDECIDED for the grace period.
        company.setSuccessionState(Company.SuccessionState.UNDECIDED);
        company.setUndecidedSinceTick(now);
        LOGGER.info("[AiCompanyManager] {} entered UNDECIDED (no heir)",
                company.getName());
    }

    /**
     * Distributes the treasury as severance to remaining workers and
     * marks the company DISSOLVED. The CompanySavedData entry stays
     * (so historical lookups still resolve) but {@link Company#isActive()}
     * goes false.
     */
    public static void dissolve(ServerLevel level, Company company, long now) {
        long treasury = company.getTreasuryBronze();
        List<Company.CompanyWorker> workers = new ArrayList<>(company.getWorkers());
        if (!workers.isEmpty() && treasury > 0L) {
            long share = Math.max(1L, treasury / workers.size());
            for (Company.CompanyWorker w : workers) {
                TownspersonMob mob = TownspersonMob.findByUUID(level, w.npcId()).orElse(null);
                if (mob != null) {
                    mob.getWallet().receive(share);
                    company.withdrawBronze(share);
                }
            }
        }
        company.setSuccessionState(Company.SuccessionState.DISSOLVED);
        company.setActive(false);
        LOGGER.info("[AiCompanyManager] {} ({}) dissolved — {} br severance",
                company.getName(), company.getCompanyId(), treasury);
        // Phase 4 doc 30 archival hook.
        tterrag1112.life_in_the_village.Village.Village v =
                tterrag1112.life_in_the_village.Networking.VillageSavedData.get(level)
                        .getVillageById(company.getHomeVillageId()).orElse(null);
        if (v != null) {
            java.util.Map<String, String> details = new java.util.LinkedHashMap<>();
            details.put("village_name", v.getName());
            details.put("company_name", company.getName());
            tterrag1112.life_in_the_village.Village.History.HistoryProducer.record(
                    level, v,
                    tterrag1112.life_in_the_village.Village.History.HistoryEventType.COMPANY_DISSOLVED,
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
                boolean alreadyOwns = CompanySavedData.get(level).getAllCompanies().stream()
                        .anyMatch(c -> c.isNpcOwned()
                                && merchant.getUUID().equals(c.getOwnerId())
                                && c.isTradingCompany()
                                && c.isActive());
                if (alreadyOwns) continue;
                MerchantPromotion.promote(level, merchant);
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Rough daily expenses: sum of worker wages. Phase 5 polish can
     *  add hall maintenance and request bounties. */
    public static long estimateDailyExpenses(Company company) {
        long total = 0L;
        for (Company.CompanyWorker w : company.getWorkers()) {
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
     * Trading-company caravan dispatch stub. Spec line 144 — 3x village
     * range. Real wiring depends on a public dispatch API on
     * CaravanSavedData (not yet exposed); v1 deposits a fixed
     * profit-on-arrival to the treasury so debug commands can verify
     * the trading-company branch fires. Phase 5 wires the actual
     * caravan goods selector + travel.
     */
    public static long dispatchTradingCaravan(ServerLevel level, Company company) {
        if (!company.isTradingCompany()) return 0L;
        long profit = 50L;
        company.depositBronze(profit);
        LOGGER.info("[AiCompanyManager] {} dispatched trading caravan (+{} br placeholder)",
                company.getName(), profit);
        return profit;
    }
}
