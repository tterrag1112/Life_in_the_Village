package tterrag1112.life_in_the_village.Kingdom.War;

import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.UUID;

/**
 * Track D3.6.4 — levy aggregation through the fealty chain.
 *
 * <p>Per the prompt: "each levying kingdom's General computes a
 * levy size via fealty chain — sum of per-village levy
 * contributions proportional to village size. Levies are an
 * abstract scalar for now, not in-world units."
 *
 * <p>v1 levy formula:
 * {@code levy = Σ_villages (village.currentLevel × LEVY_PER_LEVEL)
 *               × kingdomLevyEfficiency}
 *
 * <p>Kingdom levy efficiency is a function of stability +
 * legitimacy combined: scales 0.5..1.2 across the band.
 * Active wars reduce efficiency further (war exhaustion).
 *
 * <p>The General office holder (Phase 3) gives a competence
 * multiplier; vacant General gives a small malus.
 */
public final class LevyComputer {

    /** Base levy contribution per village level (hamlet=1, town=2, city=3, etc.). */
    public static final int LEVY_PER_LEVEL = 8;

    private LevyComputer() {}

    /** Computes the kingdom's total raised levy as an abstract scalar. */
    public static int computeLevy(VillageSavedData data, Kingdom kingdom) {
        int villageSum = 0;
        for (UUID villageId : kingdom.getVillageIds()) {
            Village v = data.getVillageById(villageId).orElse(null);
            if (v == null) continue;
            int level = Math.max(1, v.getCurrentLevel());
            villageSum += level * LEVY_PER_LEVEL;
        }
        double efficiency = efficiency(kingdom);
        // War exhaustion: each ongoing war reduces by 10%.
        int activeWars = kingdom.getActiveWars().size();
        efficiency *= Math.max(0.5, 1.0 - 0.10 * activeWars);
        return Math.max(1, (int) Math.round(villageSum * efficiency));
    }

    /**
     * Stability + legitimacy combine to a 0.5..1.2 efficiency
     * scalar. Mid-50s combined → 1.0; very stable → 1.2; failing
     * states → 0.5 floor.
     */
    public static double efficiency(Kingdom kingdom) {
        int combined = kingdom.getStability() + kingdom.stabilityModifierSum()
                + kingdom.getLegitimacy() + kingdom.legitimacyModifierSum();
        if (combined <= 50) return 0.5;
        if (combined >= 180) return 1.2;
        return 0.5 + (combined - 50) * (0.7 / 130.0);
    }
}
