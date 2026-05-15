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
import tterrag1112.life_in_the_village.Npc.Nobility.FealtyChain;
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

    /**
     * Track D3.4b — default vassalage tribute rate (vassal kingdom
     * forwards this fraction of its post-tax revenue to the overlord).
     * Ships at 0.0 to preserve net economic flow per the kingdom-plan
     * "Net economic flow at default parameters preserved" constraint.
     * Tunable per culture in a follow-up; the chain is observable.
     */
    public static final double DEFAULT_VASSAL_TRIBUTE_RATE = 0.0;

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
            long collectedFromVillage = 0L;
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
                    collectedFromVillage += share;
                }
            }

            // Track D3.2b — two-tier fealty: split the village's
            // collected tax between the lord-of-village (if any) and
            // the kingdom. Default skim rate is 0 so net flow matches
            // pre-D3.2b behaviour; the chain is wired and observable.
            FealtyChain.TaxSplit split = FealtyChain.split(
                    collectedFromVillage,
                    FealtyChain.lordOfVillage(level, data, village.get()));
            long lordShare = split.lordShare();
            long postLordKingdomShare = split.kingdomShare();
            if (split.hasLord()) {
                FealtyChain.payLord(split.lord(), lordShare);
            }

            // Track D3.3 — three-tier fealty: insert province
            // governor tier between lord and kingdom. Default skim
            // rate 0 preserves net flow; the ledger entry feeds the
            // daily provincial tick regardless of skim.
            var governorRef = FealtyChain.governorOfVillage(level, data, kingdom, village.get());
            FealtyChain.GovernorSplit govSplit =
                    FealtyChain.splitForGovernor(postLordKingdomShare, governorRef);
            if (govSplit.hasGovernor()) {
                FealtyChain.payGovernor(govSplit.governor(), govSplit.governorShare());
            }
            totalCollected += govSplit.kingdomShare();

            // Per-province ledger: append even when skim is 0 so
            // the daily provincial tick still gets a tax-collected
            // signal for stability + reports.
            governorRef.ifPresent(g -> FealtyChain.recordProvinceLedger(
                    g.province().id(),
                    /*taxCollected=*/ postLordKingdomShare,
                    /*taxRemitted=*/  govSplit.kingdomShare(),
                    /*withhold=*/     0L));

            System.out.println("Kingdom '" + kingdom.getName()
                    + "' collected " + CurrencyValue.of(collectedFromVillage)
                    + " tax from village '" + village.get().getName() + "'"
                    + (split.hasLord()
                            ? " (lord " + split.lord().getNpcName()
                                    + " kept " + CurrencyValue.of(lordShare) + ")"
                            : "")
                    + (govSplit.hasGovernor()
                            ? " (governor " + govSplit.governor().npc().getNpcName()
                                    + " kept " + CurrencyValue.of(govSplit.governorShare()) + ")"
                            : ""));
        }

        kingdom.depositToTreasury(totalCollected);

        // Track D3.4b — VASSALAGE tribute. If this kingdom is a
        // vassal, forward DEFAULT_VASSAL_TRIBUTE_RATE × totalCollected
        // to the overlord kingdom's treasury before any other outflow.
        // Default rate matches the FealtyChain skim pattern (0.0 ships;
        // observable when explicitly tuned).
        long finalTotalCollected = totalCollected;
        kingdom.overlordKingdomId().ifPresent(overlordId -> {
            long tribute = (long) (finalTotalCollected * DEFAULT_VASSAL_TRIBUTE_RATE);
            if (tribute <= 0L) return;
            Kingdom overlord = data.getKingdomById(overlordId).orElse(null);
            if (overlord == null) return;
            long withdrawn = Math.min(tribute, kingdom.getTreasury().toBronze());
            kingdom.depositToTreasury(-withdrawn);
            overlord.depositToTreasury(withdrawn);
            System.out.println("Kingdom '" + kingdom.getName()
                    + "' paid VASSALAGE tribute " + withdrawn
                    + "b to overlord '" + overlord.getName() + "'");
        });

        // Track D3.4 — EDUCATION_STIPEND ScalarLaw outflow. Drains
        // treasury once per tax cycle by (active scalar value) ×
        // (number of villages hosting a Scholar). Demonstrates the
        // ScalarLaw archetype with a concrete observable hook.
        kingdom.lawScalar("education_stipend").ifPresent(stipend -> {
            if (stipend <= 0.0) return;
            int scholarVillages = countScholarVillages(level, kingdom, data);
            long outflow = (long) Math.floor(stipend * scholarVillages);
            if (outflow > 0L) {
                long withdrawn = Math.min(outflow, kingdom.getTreasury().toBronze());
                kingdom.depositToTreasury(-withdrawn);
                System.out.println("Kingdom '" + kingdom.getName()
                        + "' paid education stipend " + withdrawn + "b ("
                        + scholarVillages + " scholar villages × "
                        + stipend + "b/day)");
            }
        });

        System.out.println("Kingdom '" + kingdom.getName()
                + "' treasury: " + kingdom.getTreasury());
    }

    /**
     * Track D3.4 — counts villages in the kingdom that have at
     * least one loaded Scholar NPC. Used for EDUCATION_STIPEND
     * outflow scaling.
     */
    private static int countScholarVillages(ServerLevel level, Kingdom kingdom,
                                            VillageSavedData data) {
        int count = 0;
        for (UUID villageId : kingdom.getVillageIds()) {
            Village v = data.getVillageById(villageId).orElse(null);
            if (v == null) continue;
            var bounds = v.getBounds(data).map(b -> b.inflate(32))
                    .orElse(null);
            if (bounds == null) continue;
            boolean hasScholar = level.getEntitiesOfClass(TownspersonMob.class, bounds,
                    npc -> npc.getProfession()
                            == tterrag1112.life_in_the_village.Profession.Profession.SCHOLAR
                            && npc.getAssignedVillageName()
                                    .map(n -> n.equals(v.getName()))
                                    .orElse(false))
                    .stream().findAny().isPresent();
            if (hasScholar) count++;
        }
        return count;
    }
}