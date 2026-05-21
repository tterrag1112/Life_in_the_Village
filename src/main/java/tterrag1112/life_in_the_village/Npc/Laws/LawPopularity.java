package tterrag1112.life_in_the_village.Npc.Laws;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Map;

/**
 * Computes per-village per-law popularity. Spec line 124.
 *
 * <pre>
 *   basePopularity = law.popularityBase()
 *   adjustment = sum(villager traitFit(law))         // -1..+1 per axis
 *   adjustment -= affectedNegatively(villager) * 5
 *   adjustment += benefitsTo(villager) * 5
 *   final      = clamp(-100, +100, basePopularity + adjustment)
 * </pre>
 *
 * <p>"Benefits / affected negatively" is decided via small hardcoded
 * groups per law (e.g. SUBSIDIZE_FARMER → benefits FARMER + FARMHAND;
 * MARKET_TAX_DOUBLE → affects MERCHANT + STOCKPILE_KEEPER). Scope is
 * intentionally narrow in v1 — anything beyond profession membership
 * lives in dedicated effect logic.</p>
 *
 * <p>Negative-popularity laws decay leader rep ~−1/day, raise rebellion
 * signal (Phase 5 stretch — flagged via {@link #leaderReputationDelta}
 * but not yet acted on outside the player rep ledger).</p>
 */
public final class LawPopularity {

    private LawPopularity() {}

    /** Per-villager bias slice: +5 / -5 per affected/benefits flag. */
    private static final float AFFECTED_PENALTY = 5f;
    private static final float BENEFITS_BONUS   = 5f;

    /** Hard clamp range. */
    public static final int MIN = -100;
    public static final int MAX = +100;

    /** Computes the popularity score (clamped) for one law in one village. */
    public static int compute(VillageLaw law, Village village,
                              VillageSavedData data, ServerLevel level) {
        if (law == null || village == null) return 0;
        List<TownspersonMob> villagers = villagersOf(village, data, level);
        float adjustment = 0f;
        for (TownspersonMob npc : villagers) {
            adjustment += traitFitFor(law, npc);
            if (affectedNegatively(law, npc.getProfession())) adjustment -= AFFECTED_PENALTY;
            if (benefitsTo(law, npc.getProfession()))         adjustment += BENEFITS_BONUS;
        }
        int total = law.popularityBase() + Math.round(adjustment);
        return Math.max(MIN, Math.min(MAX, total));
    }

    /** Population walker — returns NPCs assigned to the village, adults only. */
    private static List<TownspersonMob> villagersOf(Village village, VillageSavedData data, ServerLevel level) {
        if (village == null || level == null) return List.of();
        String name = village.getName();
        java.util.List<TownspersonMob> out = new java.util.ArrayList<>();
        for (var entity : level.getEntities().getAll()) {
            if (!(entity instanceof TownspersonMob npc)) continue;
            if (!npc.isAdult()) continue;
            if (npc.getAssignedVillageName().filter(n -> n.equals(name)).isPresent()) out.add(npc);
        }
        return out;
    }

    /** Applies the law's per-axis trait fit to the NPC's vector, returning a single score. */
    private static float traitFitFor(VillageLaw law, TownspersonMob npc) {
        Map<TraitAxis, Float> fit = law.traitFit();
        if (fit.isEmpty()) return 0f;
        float sum = 0f;
        var traits = npc.getTraitVector();
        for (Map.Entry<TraitAxis, Float> e : fit.entrySet()) {
            sum += traits.get(e.getKey()) * e.getValue();
        }
        return sum;
    }

    /** Coarse "this law makes life harder for {profession}" table. */
    static boolean affectedNegatively(VillageLaw law, Profession p) {
        return switch (law) {
            case TOLL_ENTRY              -> p == Profession.WANDERING_TRADER;
            case MARKET_TAX_DOUBLE       -> p == Profession.MERCHANT || p == Profession.STOCKPILE_KEEPER;
            case PROPERTY_TAX_DOUBLE     -> p != Profession.NONE && p != Profession.CITIZEN;
            case PROFITS_TO_TREASURY     -> isWorkshopProfession(p);
            case CURFEW                  -> p == Profession.GUARD || p == Profession.MERCHANT;
            case DOUBLE_PUNISHMENT       -> false; // affects criminals, not professions
            case FOREIGN_TRADER_BAN      -> p == Profession.MERCHANT || p == Profession.WANDERING_TRADER;
            case GUILD_MEMBERSHIP_REQUIRED -> isWorkshopProfession(p);
            case PRICE_FLOOR_FOOD        -> false;
            case PRICE_CEILING_FOOD      -> p == Profession.FARMER;
            default -> false;
        };
    }

    /** Coarse "this law benefits {profession}" table. */
    static boolean benefitsTo(VillageLaw law, Profession p) {
        return switch (law) {
            case MARKET_TAX_REDUCED      -> p == Profession.MERCHANT;
            case SUBSIDIZE_FARMER        -> p == Profession.FARMER;
            case SUBSIDIZE_BLACKSMITH    -> p == Profession.BLACKSMITH;
            case SUBSIDIZE_SCHOLAR       -> p == Profession.SCHOLAR || p == Profession.SCRIBE || p == Profession.LIBRARIAN;
            case SUBSIDIZE_HEALER        -> false; // HEALER profession lands in doc 21
            case PROPERTY_TAX_WAIVED     -> p != Profession.NONE && p != Profession.CITIZEN;
            case PARDON_FIRST_OFFENSE    -> false;
            case PILGRIM_WELCOME_BONUS   -> p == Profession.PRIEST;
            case PRICE_CEILING_FOOD      -> true; // every villager appreciates cheap food
            case PRICE_FLOOR_FOOD        -> p == Profession.FARMER;
            default -> false;
        };
    }

    private static boolean isWorkshopProfession(Profession p) {
        return switch (p) {
            case BLACKSMITH, BAKER, MILLER, STONEMASON, WEAVER, CANDLEMAKER,
                 CARPENTER, BUILDER, MINER -> true;
            default -> false;
        };
    }

    /**
     * Daily reputation delta the leader takes when the law is unpopular.
     * Spec line 134: ~-1/day; symmetric for popular laws (+1/day, capped).
     */
    public static int leaderReputationDelta(int popularity) {
        if (popularity <= -50) return -1;
        if (popularity >=  50) return +1;
        return 0;
    }
}
