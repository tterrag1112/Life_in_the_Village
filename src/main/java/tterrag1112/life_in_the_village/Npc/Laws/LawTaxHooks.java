package tterrag1112.life_in_the_village.Npc.Laws;

import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * Tax / wage / treasury accessors. Called from
 * {@code VillageSimEngine.tick} (property tax),
 * {@code TreasuryTickHandler.tick} (wages + subsidies), and
 * {@code MarketChannel.executeBuy} (market tax).
 *
 * <p>Treasury overdraft (spec "Things to flag" #2): callers that
 * actually pay subsidies should consult {@link
 * #applySubsidyOrSuspend} which atomically pays from the treasury or
 * suspends the law for next-tick re-evaluation.</p>
 */
public final class LawTaxHooks {

    private LawTaxHooks() {}

    /** Multiplier applied to baseline property tax. */
    public static double propertyTaxMultiplier(Village village) {
        VillagePolicy policy = policyOf(village);
        if (policy == null) return 1.0;
        double mult = 1.0;
        for (VillageLaw law : policy.activeLaws()) {
            if (policy.isSuspended(law)) continue;
            LawEffect e = LawEffectRegistry.get(law);
            if (e == null) continue;
            mult *= e.propertyTaxMultiplier(village, paramsOf(policy, law));
        }
        return mult;
    }

    /** Multiplier applied to the market-tax slice. */
    public static double marketTaxMultiplier(Village village) {
        VillagePolicy policy = policyOf(village);
        if (policy == null) return 1.0;
        double mult = 1.0;
        for (VillageLaw law : policy.activeLaws()) {
            if (policy.isSuspended(law)) continue;
            LawEffect e = LawEffectRegistry.get(law);
            if (e == null) continue;
            mult *= e.marketTaxMultiplier(village, paramsOf(policy, law));
        }
        return mult;
    }

    /**
     * Daily subsidy bronze for the profession, summed across every
     * applicable subsidy law. Spec line 154.
     */
    public static long dailySubsidyForProfession(Village village, Profession profession) {
        VillagePolicy policy = policyOf(village);
        if (policy == null) return 0L;
        long sum = 0L;
        for (VillageLaw law : policy.activeLaws()) {
            if (policy.isSuspended(law)) continue;
            LawEffect e = LawEffectRegistry.get(law);
            if (e == null) continue;
            sum += e.subsidyForProfession(village, profession, paramsOf(policy, law));
        }
        return sum;
    }

    /** Workshop-profit fraction redirected to treasury daily (PROFITS_TO_TREASURY). */
    public static float profitsToTreasuryFraction(Village village) {
        VillagePolicy policy = policyOf(village);
        if (policy == null) return 0f;
        float frac = 0f;
        for (VillageLaw law : policy.activeLaws()) {
            if (policy.isSuspended(law)) continue;
            LawEffect e = LawEffectRegistry.get(law);
            if (e == null) continue;
            frac += e.profitsToTreasuryFraction(village, paramsOf(policy, law));
        }
        return Math.min(1f, frac);
    }

    /**
     * Pays {@code subsidy} from the village treasury and returns the
     * actual amount paid. If treasury can't cover the full subsidy,
     * suspends every active SUBSIDIZE_* law on this village so the
     * next tick re-evaluates after funds recover (spec edge case
     * "Subsidy drains treasury → auto-suspends").
     */
    public static long applySubsidyOrSuspend(Village village, long subsidy) {
        if (village == null || subsidy <= 0L) return 0L;
        long balance = village.getTreasuryBronze();
        if (balance >= subsidy) {
            if (village.withdrawFromTreasury(subsidy)) return subsidy;
            return 0L;
        }
        // Insufficient — suspend subsidies and return whatever the
        // treasury can manage this tick. {@link
        // VillagePolicy#suspend} flags the law as inactive without
        // repealing it; {@link #resumeSubsidiesIfFunded} re-enables
        // them on the next tick when funds recover.
        VillagePolicy policy = village.getPolicy();
        if (policy != null) {
            for (VillageLaw law : policy.activeLaws()) {
                if (law.name().startsWith("SUBSIDIZE_")) policy.suspend(law);
            }
        }
        long paid = Math.max(0L, balance);
        if (paid > 0 && village.withdrawFromTreasury(paid)) return paid;
        return 0L;
    }

    /**
     * Resumes subsidy laws when the treasury has recovered enough to
     * keep paying. Called daily before the next tick of
     * {@link TreasuryTickHandler}; one-shot since {@link
     * VillagePolicy#resume} is idempotent.
     */
    public static void resumeSubsidiesIfFunded(Village village) {
        VillagePolicy policy = policyOf(village);
        if (policy == null) return;
        if (village.getTreasuryBronze() <= 0L) return;
        for (VillageLaw law : policy.suspendedLaws()) {
            if (law.name().startsWith("SUBSIDIZE_")) policy.resume(law);
        }
    }

    private static VillagePolicy policyOf(Village village) {
        return village == null ? null : village.getPolicy();
    }

    private static LawParams paramsOf(VillagePolicy policy, VillageLaw law) {
        return policy.getParams(law).orElse(LawParams.empty());
    }
}
