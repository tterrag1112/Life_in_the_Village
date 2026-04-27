package tterrag1112.life_in_the_village.Events;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.VillageRoadsSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Roads.Events.EventLifecycleSystem;
import tterrag1112.life_in_the_village.Village.Roads.Lifecycle.DeadEdgeDetector;
import tterrag1112.life_in_the_village.Village.Roads.Lifecycle.ReclaimedEdgeCleanup;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.Optional;
import java.util.UUID;

@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class KingdomTaxEvent {

    private static final long TAX_INTERVAL = 24000L;

    /** Set to true once the first-tick village-road bootstrap has run for this session. */
    private static boolean roadsBootstrapped = false;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        long tick = level.getGameTime();

        // One-shot bootstrap: ensure every existing village has an empty road graph.
        // Handles worlds created before VillageRoadsSavedData existed.
        if (!roadsBootstrapped && tick >= 1) {
            VillageSavedData vData = VillageSavedData.get(level);
            VillageRoadsSavedData roads = VillageRoadsSavedData.get(level);
            int created = roads.bootstrapFromVillageSavedData(vData);
            if (created > 0) {
                System.out.println("[VillageRoads] Bootstrapped " + created
                        + " empty road graph(s) for existing villages.");
            }
            roadsBootstrapped = true;
        }

        // Phase 9 — once-per-day dead-edge scan and once-per-month reclaim cleanup.
        // Both operations self-throttle and no-op on intermediate ticks.
        DeadEdgeDetector.maybeScan(level);
        ReclaimedEdgeCleanup.maybeCleanup(level);

        // Phase 10 — once-per-day expiration sweep for ephemeral road events.
        EventLifecycleSystem.maybeTickExpirations(level);

        VillageSavedData data = VillageSavedData.get(level);

        for (Kingdom kingdom : data.getAllKingdoms()) {
            long lastTax = kingdom.getLastTaxTick();
            if (lastTax >= 0 && tick - lastTax < TAX_INTERVAL) continue;

            collectTaxes(level, kingdom, data, tick);
            kingdom.setLastTaxTick(tick);
            data.setDirty();
        }
    }

    private static void collectTaxes(ServerLevel level, Kingdom kingdom,
                                     VillageSavedData data, long tick) {
        long totalCollected = 0;

        for (UUID villageId : kingdom.getVillageIds()) {
            Optional<Village> village = data.getVillageById(villageId);
            if (village.isEmpty()) continue;

            // Estimate village income from NPC wealth
            long villageWealth = level.getEntitiesOfClass(
                    TownspersonMob.class,
                    village.get().getBounds(data)
                            .map(b -> b.inflate(32))
                            .orElse(new net.minecraft.world.phys.AABB(0,0,0,0,0,0)),
                    mob -> mob.getAssignedVillageName()
                            .map(n -> n.equals(village.get().getName()))
                            .orElse(false)
            ).stream().mapToLong(mob -> mob.getWealth().toBronze()).sum();

            long taxOwed = kingdom.computeDailyTax(villageWealth);
            CurrencyValue tax = CurrencyValue.of(taxOwed);

            // Collect tax from village NPCs proportionally
            long remaining = taxOwed;
            for (TownspersonMob mob : level.getEntitiesOfClass(
                    TownspersonMob.class,
                    village.get().getBounds(data)
                            .map(b -> b.inflate(32))
                            .orElse(new net.minecraft.world.phys.AABB(
                                    0,0,0,0,0,0)),
                    m -> m.getAssignedVillageName()
                            .map(n -> n.equals(village.get().getName()))
                            .orElse(false))) {

                if (remaining <= 0) break;
                long mobWealth = mob.getWealth().toBronze();
                if (mobWealth <= 0) continue;

                // Take proportional share
                long share = Math.min(remaining,
                        (long)(mobWealth * kingdom.getIncomeTaxRate()));
                if (share <= 0) continue;

                if (CoinHelper.spend(mob.getPersonalInventory(),
                        CurrencyValue.of(share))) {
                    remaining -= share;
                    totalCollected += share;
                }
            }

            System.out.println("Kingdom '" + kingdom.getName()
                    + "' collected " + CurrencyValue.of(taxOwed - remaining)
                    + " tax from village '" + village.get().getName() + "'");
        }

        kingdom.depositToTreasury(totalCollected);
        System.out.println("Kingdom '" + kingdom.getName()
                + "' treasury: " + kingdom.getTreasury());
    }
}