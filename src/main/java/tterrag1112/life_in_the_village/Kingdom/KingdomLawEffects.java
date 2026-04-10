package tterrag1112.life_in_the_village.Kingdom;

import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.Optional;
import java.util.UUID;

/**
 * Central evaluation point for all {@link KingdomLaw} gameplay effects.
 *
 * <p>Rather than scattering {@code hasLaw()} checks throughout the codebase,
 * all law-driven logic calls one of these helpers. Each method accepts the
 * context needed to locate the relevant kingdom and returns a simple result
 * (boolean, multiplier, surcharge amount) that the call site applies.</p>
 *
 * <h3>Citizenship model</h3>
 * Until a full citizenship system exists, a player is considered a "citizen"
 * of a kingdom if they are its {@link Kingdom#getRulerPlayerId ruler}. All
 * other players are "outsiders" with respect to that kingdom. This is the
 * minimum that makes PROPERTY_RIGHTS and TRADE_TARIFFS mechanically
 * meaningful; richer citizenship can slot in by replacing
 * {@link #isCitizen} without touching the call sites.
 */
public final class KingdomLawEffects {

    private KingdomLawEffects() {}

    // =========================================================================
    // Citizenship
    // =========================================================================

    /**
     * Returns true if the player should be treated as a citizen of the
     * given kingdom. Currently this is only true for the ruler.
     */
    public static boolean isCitizen(ServerPlayer player, Kingdom kingdom) {
        if (kingdom == null) return false;
        return kingdom.getRulerPlayerId()
                .map(id -> id.equals(player.getUUID()))
                .orElse(false);
    }

    /** Returns the kingdom for the given village id, or empty. */
    public static Optional<Kingdom> kingdomFor(VillageSavedData data, UUID villageId) {
        if (villageId == null) return Optional.empty();
        return data.getKingdomForVillage(villageId);
    }

    // =========================================================================
    // TRADE_TARIFFS
    // =========================================================================

    /**
     * Returns the tariff multiplier a player pays when buying from a village
     * merchant. 1.0 = no tariff, 1.2 = +20% surcharge on buy prices.
     *
     * <p>Tariffs apply only when the village is in a kingdom that has
     * {@link KingdomLaw#TRADE_TARIFFS} enacted AND the player is not a
     * citizen of that kingdom. Merchant trade exemption perk overrides.</p>
     */
    public static double tradeBuyMultiplier(ServerPlayer player,
                                            VillageSavedData data,
                                            UUID villageId) {
        Kingdom k = kingdomFor(data, villageId).orElse(null);
        if (k == null || !k.hasLaw(KingdomLaw.TRADE_TARIFFS)) return 1.0;
        if (isCitizen(player, k)) return 1.0;

        // MERCHANT_TRADE_EXEMPTION perk skips tariffs
        if (tterrag1112.life_in_the_village.Profession.ProfessionPerkManager
                .hasTradeExemption(player)) return 1.0;

        return 1.20;
    }

    /**
     * Returns the tariff multiplier a player receives when selling to a
     * village merchant. 1.0 = no tariff, 0.8 = 20% less paid to outsider.
     */
    public static double tradeSellMultiplier(ServerPlayer player,
                                             VillageSavedData data,
                                             UUID villageId) {
        Kingdom k = kingdomFor(data, villageId).orElse(null);
        if (k == null || !k.hasLaw(KingdomLaw.TRADE_TARIFFS)) return 1.0;
        if (isCitizen(player, k)) return 1.0;

        if (tterrag1112.life_in_the_village.Profession.ProfessionPerkManager
                .hasTradeExemption(player)) return 1.0;

        return 0.80;
    }

    // =========================================================================
    // PROPERTY_RIGHTS
    // =========================================================================

    /**
     * Returns true if the player is allowed to buy property in the given
     * village. Always true unless {@link KingdomLaw#PROPERTY_RIGHTS} is
     * enacted by the village's kingdom and the player is not a citizen.
     */
    public static boolean canPurchaseProperty(ServerPlayer player,
                                              VillageSavedData data,
                                              UUID villageId) {
        Kingdom k = kingdomFor(data, villageId).orElse(null);
        if (k == null || !k.hasLaw(KingdomLaw.PROPERTY_RIGHTS)) return true;
        return isCitizen(player, k);
    }

    // =========================================================================
    // KINGS_PEACE
    // =========================================================================

    /**
     * Returns true if violence against NPCs in this village is a criminal
     * act — used by the reputation damage hook. Independent of whether the
     * player is a citizen: even the ruler is bound by their own law.
     */
    public static boolean isKingsPeaceActive(VillageSavedData data, UUID villageId) {
        Kingdom k = kingdomFor(data, villageId).orElse(null);
        return k != null && k.hasLaw(KingdomLaw.KINGS_PEACE);
    }

    // =========================================================================
    // MINIMUM_WAGE
    // =========================================================================

    /** Hard floor for daily NPC wages when MINIMUM_WAGE is active. */
    public static final long MINIMUM_WAGE_BRONZE = 8L;

    /**
     * Applies the minimum wage floor to a proposed wage amount if the
     * village's kingdom has {@link KingdomLaw#MINIMUM_WAGE} enacted.
     * Returns the original amount if the law is not active.
     */
    public static long applyMinimumWage(VillageSavedData data,
                                        UUID villageId,
                                        long proposedWage) {
        Kingdom k = kingdomFor(data, villageId).orElse(null);
        if (k == null || !k.hasLaw(KingdomLaw.MINIMUM_WAGE)) return proposedWage;
        return Math.max(proposedWage, MINIMUM_WAGE_BRONZE);
    }
}