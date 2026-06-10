package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.BuildingFaith;
import tterrag1112.life_in_the_village.Npc.Religion.PietyComponent;
import tterrag1112.life_in_the_village.Npc.Religion.PietyPayoff;
import tterrag1112.life_in_the_village.Npc.Religion.Rite;
import tterrag1112.life_in_the_village.Npc.Religion.RiteCapability;
import tterrag1112.life_in_the_village.Npc.Religion.RiteSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.RiteScheduler;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Religion Rework R9d — the player commissions a personal blessing rite from a
 * priest for a coin fee. The fee debits the player's purse and is deposited into
 * the temple's {@link tterrag1112.life_in_the_village.Village.Economy.BuildingEconomy}
 * (R4a income), and the rite is scheduled through the normal pipeline (the priest
 * then officiates it). Respects the R1a capability gate
 * ({@link RiteCapability#canOfficiate}) and affordability. Mirrors
 * {@link CommissionVerb}'s fee/work-time shape and {@link MakeOfferingVerb}'s
 * temple-economy + player-piety bookkeeping.
 */
public final class CommissionRiteVerb implements PlayerVerb {

    public static final String VERB_ID = "commission_rite";

    /** The commissioned rite — a personal blessing (always applicable). */
    private static final Rite COMMISSIONED = Rite.BLESSING;
    /** Flat commission fee in bronze → the temple economy. */
    private static final long FEE_BRONZE = 25L;
    /** Small player-piety deepening for sponsoring a rite. */
    private static final float COMMISSION_PIETY = 0.03f;

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Commission rite"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal(
                "Pay the priest to perform a blessing rite (" + FEE_BRONZE + "b)."));
    }
    @Override public int displayOrder() { return 64; }
    @Override public int cooldownTicks() { return 1200; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        return ctx.npc().getProfession() == Profession.PRIEST
                && ctx.npc().getAssignedBuildingId().isPresent()
                && ctx.npc().isWorkTime();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        if (ctx.npc().getProfession() != Profession.PRIEST) {
            return VerbResult.failure("They are not a priest.");
        }
        if (!ctx.npc().isWorkTime()) {
            return VerbResult.failure("Come back during their temple hours.");
        }
        VillageSavedData vdata = VillageSavedData.get(ctx.level());
        Village village = ctx.npc().getAssignedVillageName()
                .flatMap(vdata::getVillageByName).orElse(null);
        UUID buildingId = ctx.npc().getAssignedBuildingId().orElse(null);
        if (village == null || buildingId == null) return VerbResult.failure("No temple here.");
        Building temple = vdata.getBuildingById(buildingId).orElse(null);
        if (temple == null || !BuildingFaith.isReligiousBuilding(temple.getType())) {
            return VerbResult.failure("No temple here.");
        }

        // R1a capability gate — the priest must be able to officiate this rite.
        if (!RiteCapability.canOfficiate(ctx.npc(), COMMISSIONED)) {
            return VerbResult.failure("This priest cannot lead that rite yet.");
        }

        // Affordability + fee → temple economy (R4a income).
        CurrencyValue fee = CurrencyValue.of(FEE_BRONZE);
        if (!CoinHelper.playerCanAfford(ctx.player(), fee)) {
            return VerbResult.failure("You can't afford the " + FEE_BRONZE + "b fee.");
        }
        if (!CoinHelper.playerPay(ctx.player(), fee)) {
            return VerbResult.failure("Payment failed.");
        }
        vdata.getOrCreateBuildingEconomy(buildingId).depositRevenue(FEE_BRONZE);

        // Schedule the rite through the normal pipeline (priest officiates it).
        RiteScheduler.schedule(ctx.level(), village, COMMISSIONED,
                List.of(ctx.player().getUUID()), 0L);

        // Player-piety touch + the R9d co-religionist payoff with the priest.
        UUID playerId = ctx.player().getUUID();
        PietyComponent piety = RiteSavedData.get(ctx.level()).getOrCreatePlayerPiety(playerId);
        String faith = piety.primaryReligion().orElseGet(() -> {
            String f = BuildingFaith.resolveFaith(ctx.level(), village, temple);
            if (f != null) piety.setBelief(f, 0.05f);
            return f;
        });
        if (faith != null) piety.adjustBelief(faith, COMMISSION_PIETY);
        piety.recordRiteAttendance(ctx.level().getGameTime());
        RiteSavedData.get(ctx.level()).markDirty();

        // Divine Layer V1 — sponsoring a rite earns Divine Favour with the deity
        // (deity-demand weighted via GENEROSITY).
        if (faith != null) {
            tterrag1112.life_in_the_village.Npc.Religion.DivineFavour.awardForReligion(
                    ctx.level(), playerId, faith,
                    tterrag1112.life_in_the_village.Npc.Religion.DivineFavour.FavourAct.COMMISSION_RITE,
                    ctx.level().getGameTime(), ctx.player().blockPosition());  // S1b — sacred at the commission site
        }

        ctx.npc().getRelationships().adjust(playerId,
                3 + PietyPayoff.regardBonus(piety.primaryTier()));
        ctx.player().displayClientMessage(Component.literal(
                "You commission a blessing. The priest will perform it shortly."), true);
        return VerbResult.success("");
    }
}
