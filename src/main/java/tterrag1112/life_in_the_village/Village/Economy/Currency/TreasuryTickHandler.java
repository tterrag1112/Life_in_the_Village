package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Economy.VillageTreasury;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;

public final class TreasuryTickHandler {

    // Phase 1d: wages now read from the economy balance config via
    // VillageTreasury accessors (was a duplicate of VillageTreasury's
    // own wage constants — now unified onto the one config).

    private TreasuryTickHandler() {}

    public static void tick(ServerLevel level, Village village,
                            VillageSavedData data, long currentTick) {
        if (!level.isLoaded(village.getVillageCentre())) {
            int estimatedGuards = data.getSimData(village.getId())
                    .map(s -> Math.max(1, s.getSimulatedPopulation() / 5))
                    .orElse(1);
            village.withdrawFromTreasury(estimatedGuards * VillageTreasury.guardWage());
            return;
        }

        var bounds = village.getBounds(data).orElse(null);
        if (bounds == null) return;

        List<TownspersonMob> npcs = level.getEntitiesOfClass(
                TownspersonMob.class, bounds.inflate(32),
                mob -> mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false));

        // Doc 22: SUBSIDIZE_* laws auto-suspend on overdraft and
        // resume when funds recover. Resume gates first so a tick that
        // restored funds via wages/sales doesn't keep subsidies idle.
        tterrag1112.life_in_the_village.Npc.Laws.LawTaxHooks
                .resumeSubsidiesIfFunded(village);

        for (TownspersonMob npc : npcs) {
            long wage = wageForProfession(npc.getProfession());
            if (wage > 0) {
                // Kingdom law: MINIMUM_WAGE — apply floor if the village's
                // kingdom has the law active. Applied per-NPC because
                // different villages in different kingdoms may share this
                // tick pass.
                wage = tterrag1112.life_in_the_village.Kingdom.KingdomLawEffects
                        .applyMinimumWage(data, village.getId(), wage);

                // NpcEconomy.payWage: withdraws from village treasury,
                // credits wallet, fires visual on the NPC
                NpcEconomy.payWage(npc, wage, level, data);

                // Contribute a fraction to household pool after receiving
                tterrag1112.life_in_the_village.Entities.HouseholdWealthManager
                        .contributeToPool(npc, CurrencyValue.of(wage), data);
            }

            // Doc 22: SUBSIDIZE_* laws pay an extra daily stipend on top
            // of wages, drawn from the village treasury. Auto-suspends
            // on overdraft (spec edge case + things-to-flag #2).
            long subsidy = tterrag1112.life_in_the_village.Npc.Laws.LawTaxHooks
                    .dailySubsidyForProfession(village, npc.getProfession());
            if (subsidy > 0) {
                long paid = tterrag1112.life_in_the_village.Npc.Laws.LawTaxHooks
                        .applySubsidyOrSuspend(village, subsidy);
                if (paid > 0) {
                    npc.getWallet().receive(CurrencyValue.of(paid));
                    tterrag1112.life_in_the_village.Entities.HouseholdWealthManager
                            .contributeToPool(npc, CurrencyValue.of(paid), data);
                }
            }

            // Merchant debt maintenance
            if (npc.getProfession() == Profession.MERCHANT) {
                npc.getEconomy().tickDebtDeadline(currentTick);
                npc.getEconomy().autoRepayDebt();
            }
        }
    }

    private static long wageForProfession(Profession prof) {
        return switch (prof) {
            case GUARD            -> VillageTreasury.guardWage();
            case STOCKPILE_KEEPER -> VillageTreasury.keeperWage();
            case INNKEEPER        -> VillageTreasury.innkeeperWage();
            case MERCHANT         -> VillageTreasury.merchantWage();
            default               -> 0L;
        };
    }
}