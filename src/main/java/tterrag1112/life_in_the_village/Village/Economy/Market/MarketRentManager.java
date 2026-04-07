package tterrag1112.life_in_the_village.Village.Economy.Market;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;

/**
 * Handles daily stall rent collection and expired stall reclamation.
 *
 * <p>Called once per in-game day from {@code VillageDailyTickSystem}.
 * For each active stall in a village:
 * <ul>
 *   <li>If the stall is expired, reclaim it via {@link MarketStallPlacer}.</li>
 *   <li>If the stall has an NPC owner who is loaded, deduct the next day's
 *       rent from their personal inventory and extend {@code rentPaidUntilTick}
 *       by 24000. If they can't pay, mark the stall as expired immediately.</li>
 *   <li>Purchased stalls ({@code rentPaidUntilTick == MAX_VALUE}) are skipped.</li>
 * </ul>
 * Player-owned stalls are not auto-renewed here — players pay at the market
 * NPC directly. If a player's rent lapses the stall is reclaimed like any other.
 * </p>
 */
public final class MarketRentManager {

    private MarketRentManager() {}

    public static void tick(ServerLevel level, Village village,
                            VillageSavedData data, long currentTick) {

        List<MarketStall> stalls = data.getStallsForVillage(village.getId());
        if (stalls.isEmpty()) return;

        AABB villageBounds = village.getBounds(data)
                .map(b -> b.inflate(32))
                .orElse(null);

        for (MarketStall stall : stalls) {
            if (!stall.isActive()) continue;

            // ── Purchased stalls never expire ────────────────────────────────
            if (stall.isPurchased()) continue;

            // ── Expired: reclaim ─────────────────────────────────────────────
            if (stall.isExpired(currentTick)) {
                System.out.println("[MarketRentManager] Stall " + stall.getSlotIndex()
                        + " expired for owner " + stall.getOwnerUUID()
                        + " — reclaiming");
                MarketStallPlacer.reclaimStall(level, stall);
                data.removeMarketStall(stall.getStallId());
                data.setDirty();
                continue;
            }

            // ── NPC auto-renewal: attempt to collect rent ────────────────────
            if (stall.getOwnerType() != MarketStall.OwnerType.NPC) continue;
            if (villageBounds == null) continue;

            TownspersonMob owner = findNpcOwner(level, villageBounds,
                    stall.getOwnerUUID());
            if (owner == null) continue; // unloaded — skip, don't penalise

            CurrencyValue rent = CurrencyValue.of(MarketStallPlacer.RENT_PER_DAY);
            if (owner.canAfford(rent)) {
                owner.spend(rent);
                // Deposit rent to village treasury
                village.depositToTreasury(MarketStallPlacer.RENT_PER_DAY);
                stall.setRentPaidUntilTick(stall.getRentPaidUntilTick() + 24000L);
                data.setDirty();
            } else {
                // Can't afford — mark expired immediately
                stall.setRentPaidUntilTick(currentTick - 1);
                data.setDirty();
            }
        }
    }

    private static TownspersonMob findNpcOwner(ServerLevel level,
                                               AABB bounds, java.util.UUID uuid) {
        return level.getEntitiesOfClass(TownspersonMob.class, bounds,
                        mob -> mob.getUUID().equals(uuid))
                .stream().findFirst().orElse(null);
    }
}