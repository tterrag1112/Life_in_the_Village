package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;

/**
 * Daily treasury operations: wage payment to salaried NPCs and
 * debt deadline ticking for merchants. Called once per in-game day
 * from VillageDailyTickSystem.
 *
 * <p>Uses the existing {@link Village#depositToTreasury} /
 * {@link Village#withdrawFromTreasury} methods — no separate
 * VillageTreasury class needed.</p>
 */
public final class TreasuryTickHandler {

    private static final long GUARD_WAGE     = 8L;
    private static final long KEEPER_WAGE    = 5L;
    private static final long INNKEEPER_WAGE = 4L;

    private TreasuryTickHandler() {}

    public static void tick(ServerLevel level, Village village,
                            VillageSavedData data, long currentTick) {
        if (!level.isLoaded(village.getVillageCentre())) {
            // Unloaded — simulate wage drain only
            int estimatedGuards = data.getSimData(village.getId())
                    .map(s -> Math.max(1, s.getSimulatedPopulation() / 5))
                    .orElse(1);
            village.withdrawFromTreasury(estimatedGuards * GUARD_WAGE);
            return;
        }

        var bounds = village.getBounds(data).orElse(null);
        if (bounds == null) return;

        List<TownspersonMob> npcs = level.getEntitiesOfClass(
                TownspersonMob.class, bounds.inflate(32),
                mob -> mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false));

        for (TownspersonMob npc : npcs) {
            long wage = wageForProfession(npc.getProfession());
            if (wage > 0 && village.withdrawFromTreasury(wage)) {
                npc.receive(CurrencyValue.of(wage));
            }

            // Tick merchant debt deadlines
            if (npc.getProfession() == Profession.MERCHANT) {
                npc.getEconomy().tickDebtDeadline(currentTick);
                npc.getEconomy().autoRepayDebt();
            }
        }
    }

    private static long wageForProfession(Profession prof) {
        return switch (prof) {
            case GUARD            -> GUARD_WAGE;
            case STOCKPILE_KEEPER -> KEEPER_WAGE;
            case INNKEEPER        -> INNKEEPER_WAGE;
            default               -> 0L;
        };
    }
}