package tterrag1112.life_in_the_village.Npc.Nobility;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Comparator;
import java.util.Optional;

/**
 * Track D3.2b — two-tier tax flow shape.
 *
 * <p>D3.2a's substrate gives every NPC a {@link NobilityComponent}
 * with a rank index and an optional dynasty house affiliation.
 * D3.2b inserts the noble overlord into the tax flow:
 *
 * <pre>
 *     Old:  village NPCs ──tax──&gt; kingdom treasury
 *     New:  village NPCs ──tax──&gt; lord-of-village ─────&gt; kingdom treasury
 *                                          (skim {@link #DEFAULT_LORD_SKIM_RATE})
 * </pre>
 *
 * <h3>Lord-of-village resolution</h3>
 * The "lord of a village" is the loaded NPC assigned to the village
 * with the highest {@link NobilityComponent#getRankIndex()} who
 * also has a {@code dynastyHouseId} set (i.e., is recognised by a
 * noble house). Ties broken by prestige.
 *
 * <p>If no NPC qualifies, the village has no lord; tax flows direct
 * to the kingdom treasury (the pre-D3.2b behaviour, preserved).
 *
 * <h3>Skim rate</h3>
 * {@link #DEFAULT_LORD_SKIM_RATE} ships at {@code 0.0} to satisfy
 * the kingdom plan's "net economic flow at default parameters
 * preserved" constraint. The hook is wired and observable; D3.3
 * can lift the rate per culture or per kingdom without restructuring
 * the chain.
 */
public final class FealtyChain {

    /**
     * Default per-village skim rate the lord-of-village takes from
     * each tax cycle. Ships at {@code 0.0} so net kingdom revenue is
     * unchanged. The constant is here so callers tune in one place
     * (D3.3 can replace this with a per-culture pull).
     */
    public static final double DEFAULT_LORD_SKIM_RATE = 0.0;

    /** Search radius around a village's bounds when scanning for the lord. */
    private static final double VILLAGE_LORD_SCAN_INFLATE = 32.0;

    private FealtyChain() {}

    /**
     * Returns the noble overlord of {@code village}, if any. The
     * search walks loaded NPCs assigned to the village; ranks them
     * by {@link NobilityComponent#getRankIndex()} (descending),
     * tie-breaking by prestige.
     */
    public static Optional<TownspersonMob> lordOfVillage(ServerLevel level,
                                                         VillageSavedData data,
                                                         Village village) {
        if (level == null || village == null) return Optional.empty();
        AABB bounds = village.getBounds(data)
                .map(b -> b.inflate(VILLAGE_LORD_SCAN_INFLATE))
                .orElse(null);
        if (bounds == null) return Optional.empty();
        return level.getEntitiesOfClass(TownspersonMob.class, bounds,
                        npc -> npc.getAssignedVillageName()
                                .map(n -> n.equals(village.getName()))
                                .orElse(false))
                .stream()
                .filter(FealtyChain::qualifiesAsLord)
                .max(Comparator.<TownspersonMob>comparingInt(n -> n.getNobility().getRankIndex())
                        .thenComparingInt(n -> n.getNobility().getPrestige()));
    }

    /**
     * Splits a tax payment between the lord-of-village and the kingdom
     * per {@link #DEFAULT_LORD_SKIM_RATE}. When no lord exists, the
     * full amount remits to the kingdom (lord share = 0).
     *
     * <p>Caller is responsible for actually moving coins into the
     * lord's inventory and the kingdom treasury — this returns the
     * split shape only.
     */
    public static TaxSplit split(long taxOwed, Optional<TownspersonMob> lord) {
        if (lord.isEmpty() || taxOwed <= 0L) return new TaxSplit(taxOwed, 0L, lord.orElse(null));
        long lordShare = (long) (taxOwed * DEFAULT_LORD_SKIM_RATE);
        long kingdomShare = taxOwed - lordShare;
        return new TaxSplit(kingdomShare, lordShare, lord.get());
    }

    /**
     * Pays the lord their share into their personal purse via
     * {@link CoinHelper#giveCoins}. Caller passes the resolved
     * {@link TaxSplit#lordShare()} amount.
     */
    public static void payLord(TownspersonMob lord, long bronze) {
        if (lord == null || bronze <= 0L) return;
        CoinHelper.giveCoins(lord.getPersonalInventory(), CurrencyValue.of(bronze));
    }

    /**
     * Lord eligibility: must hold a dynasty house affiliation AND
     * a non-zero rank index. Pure commoner NPCs (rank 0, no house)
     * are skipped — they aren't nobles.
     */
    private static boolean qualifiesAsLord(TownspersonMob npc) {
        NobilityComponent nob = npc.getNobility();
        return nob.hasDynastyHouse() && nob.getRankIndex() > 0;
    }

    /**
     * Result shape from {@link #split}. {@code kingdomShare + lordShare ==
     * taxOwed} always; {@code lord} is null when no lord exists.
     */
    public record TaxSplit(long kingdomShare, long lordShare, TownspersonMob lord) {
        public boolean hasLord() { return lord != null && lordShare > 0L; }
    }
}
