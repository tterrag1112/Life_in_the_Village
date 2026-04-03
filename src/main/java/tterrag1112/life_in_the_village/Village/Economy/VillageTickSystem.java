// src/main/java/tterrag1112/life_in_the_village/Village/Economy/TreasuryTickHandler.java
package tterrag1112.life_in_the_village.Village.Economy;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;

/**
 * Daily treasury operations: tax collection and wage payment.
 * Called from VillageDailyTickSystem after needs computation.
 */
public final class TreasuryTickHandler {

    private TreasuryTickHandler() {}

    /**
     * Run once per in-game day per village.
     */
    public static void tick(ServerLevel level, Village village,
                            VillageSavedData data, long currentTick) {
        VillageTreasury treasury = data.getTreasury(village.getId())
                .orElseGet(() -> {
                    // Bootstrap treasury with starter funds based on village size
                    long starter = village.getBuildingIds().size() * 50L;
                    VillageTreasury t = VillageTreasury.create(
                            village.getId(), starter);
                    data.putTreasury(t);
                    return t;
                });

        collectPropertyTax(level, village, data, treasury);
        collectBaselineIncome(village, data, treasury, currentTick);
        payWages(level, village, data, treasury, currentTick);

        treasury.setLastPayday(currentTick);
        data.putTreasury(treasury);
        data.setDirty();
    }

    // ── Property tax ───────────────────────────────────────────────────

    private static void collectPropertyTax(ServerLevel level, Village village,
                                           VillageSavedData data,
                                           VillageTreasury treasury) {
        long houseCount = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == BuildingType.HOUSE
                        || b.getType() == BuildingType.FARMHOUSE)
                .count();

        long tax = houseCount * VillageTreasury.PROPERTY_TAX_PER_HOUSE;
        if (tax > 0) {
            treasury.deposit(tax);
        }
    }

    // ── Baseline income (for unloaded pops or as minimum) ──────────────

    private static void collectBaselineIncome(Village village,
                                              VillageSavedData data,
                                              VillageTreasury treasury,
                                              long currentTick) {
        int pop = data.getSimData(village.getId())
                .map(sim -> sim.getSimulatedPopulation())
                .orElse(village.getBuildingIds().size()); // rough fallback

        long income = pop * VillageTreasury.BASELINE_INCOME_PER_NPC;
        treasury.deposit(income);
    }

    // ── Wage payment ───────────────────────────────────────────────────

    private static void payWages(ServerLevel level, Village village,
                                 VillageSavedData data,
                                 VillageTreasury treasury,
                                 long currentTick) {
        if (!level.isLoaded(village.getVillageCentre())) {
            // Unloaded — simulate wage drain
            int guardCount = data.getSimData(village.getId())
                    .map(s -> Math.max(1, s.getSimulatedPopulation() / 5))
                    .orElse(1);
            long simWages = guardCount * VillageTreasury.GUARD_WAGE;
            treasury.withdraw(simWages); // just drain, don't track per-NPC
            return;
        }

        // Loaded — pay each wage-earning NPC directly
        var bounds = village.getBounds(data).orElse(null);
        if (bounds == null) return;

        List<TownspersonMob> npcs = level.getEntitiesOfClass(
                TownspersonMob.class, bounds.inflate(32),
                mob -> mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false));

        for (TownspersonMob npc : npcs) {
            long wage = getWageForProfession(npc.getProfession());
            if (wage <= 0) continue;

            if (treasury.payWage(wage)) {
                npc.receive(CurrencyValue.of(wage));
            }
            // If treasury can't afford wages, NPCs don't get paid.
            // This creates natural economic pressure — villages need income.
        }
    }

    private static long getWageForProfession(Profession prof) {
        return switch (prof) {
            case GUARD           -> VillageTreasury.GUARD_WAGE;
            case STOCKPILE_KEEPER-> VillageTreasury.KEEPER_WAGE;
            case INNKEEPER       -> VillageTreasury.INNKEEPER_WAGE;
            default              -> 0L; // producers earn from sales, not wages
        };
    }
}