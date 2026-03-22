// src/main/java/tterrag1112/life_in_the_village/Events/ServerTickDispatcher.java
package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerSavedData;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Lore.KingdomHistoryData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Profession.ProfessionPerkManager;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.HousePurchaseManager;
import tterrag1112.life_in_the_village.Village.Buildings.VillageExpansionManager;
import tterrag1112.life_in_the_village.Village.Decoration.VillageAgingManager;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Decoration.VillageDecorator;
import tterrag1112.life_in_the_village.Village.Decoration.VillagePath;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Economy.CraftingOrderManager;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Trade.CaravanSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;
import tterrag1112.life_in_the_village.Village.Event.VillageEventScheduler;
import tterrag1112.life_in_the_village.Village.Needs.NeedCategory;
import tterrag1112.life_in_the_village.Village.Needs.VillageNeedsCalculator;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageWarningSystem;

import java.util.Map;
import java.util.UUID;

import static tterrag1112.life_in_the_village.Life_in_the_village.MODID;

@EventBusSubscriber(modid = MODID)
public class ServerTickDispatcher {

    private static final int UPDATE_INTERVAL = 24000;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel overworld = event.getServer().overworld();
        long tick = overworld.getGameTime();

        VillageSavedData    vdata = VillageSavedData.get(overworld);
        CompanySavedData    cdata = CompanySavedData.get(overworld);
        AdventurerSavedData ada   = AdventurerSavedData.get(overworld);
        CaravanSavedData    cara  = CaravanSavedData.get(overworld);

        // ── Every tick (fast) ─────────────────────────────────────────────────
        VillageEventScheduler.tickEvents(overworld, vdata, tick);

        // ── Every second (20 ticks) ───────────────────────────────────────────
        if (tick % 20 == 0) {
            ada.tick(overworld, vdata);
            cara.tick(overworld, vdata);
            cdata.tick(overworld, vdata, tick);
            TradeRouteManager.tick(overworld, vdata, tick);
            VillageWarningSystem.tickWarningSpread(overworld, vdata, tick);
            VillageExpansionManager.tick(overworld, vdata, tick);
            VillageAgingManager.tick(overworld, vdata, tick);
            HousePurchaseManager.tickPropertyTax(overworld, vdata, tick);
            WorkplaceAssignmentManager.tickWeeklyPay(overworld, tick);

            // Crafting order lifecycle — expiry + economy posting (internally
            // gated to once-per-day for the economy pass)
            CraftingOrderManager.tick(overworld, vdata, tick);

            // Passive perk effects — Haste near furnaces/with axe, etc.
            for (net.minecraft.server.level.ServerPlayer player :
                    overworld.getServer().getPlayerList().getPlayers()) {
                ProfessionPerkManager.tickPassiveEffects(player);
            }
        }

        // ── Once per day (staggered per village) ──────────────────────────────
        for (Village village : vdata.getAllVillages()) {
            long offset = Math.abs(village.getName().hashCode() % 24000L);
            if ((tick + offset) % 24000L == 0) {
                var needs = VillageNeedsCalculator.compute(overworld, village, vdata);
                village.setNeeds(needs);
                village.setLastNeedsUpdate(tick);

                applyFoodEffects(overworld, village, vdata);
                VillageEventScheduler.tick(overworld, village, vdata, tick);
                VillageEconomy.purgeStaleListings(village.getId(), tick);
                upgradePathsIfAffordable(overworld, village, vdata);
                checkKingdomFormation(overworld, village, vdata, tick);

                vdata.setDirty();
            }
        }
    }

    // =========================================================================
    // Path upgrade
    // =========================================================================

    static void upgradePathsIfAffordable(ServerLevel level,
                                         Village village,
                                         VillageSavedData data) {
        Map<VillagePath.PathTier, CurrencyValue> thresholds = Map.of(
                VillagePath.PathTier.DIRT,        CurrencyValue.ofGold(5),
                VillagePath.PathTier.GRAVEL,      CurrencyValue.ofGold(15),
                VillagePath.PathTier.COBBLESTONE, CurrencyValue.ofGold(30));

        CurrencyValue wealth = VillageExpansionManager.getVillageWealth(
                village.getBuildingIds().stream()
                        .map(data::getBuildingById)
                        .filter(java.util.Optional::isPresent)
                        .map(java.util.Optional::get)
                        .collect(java.util.stream.Collectors.toList()),
                level, data, village);

        for (VillagePath path : data.getPathsForVillage(village.getId())) {
            if (path.getTier().isMaxTier()) continue;

            CurrencyValue threshold = thresholds.get(path.getTier());
            if (threshold == null) continue;
            if (!wealth.isAffordable(threshold)) continue;

            VillagePath.PathTier nextTier = path.getTier().next();
            path.setTier(nextTier);

            // Place upgraded blocks
            for (BlockPos pos : path.getBlocks()) {
                BlockPos below = pos.below();
                if (level.getBlockState(below).isSolidRender()) {
                    level.setBlock(below,
                            nextTier.getBlock().defaultBlockState(), 3);
                }
            }

            // Add lampposts when reaching cobblestone
            if (nextTier == VillagePath.PathTier.COBBLESTONE) {
                VillageBiomeStyle style = VillageBiomeStyle.detect(
                        level, path.getBlocks().get(0));
                VillageSizeTier tier = VillageSizeTier.fromBuildingCount(
                        village.getBuildingIds().size());
                VillageDecorator.placeLampposts(level, path.getBlocks(), style, tier);
            }

            data.setDirty();
            System.out.println("Village '" + village.getName()
                    + "' upgraded path to " + nextTier);
        }
    }

    // =========================================================================
    // Food need effects
    // =========================================================================

    private static void applyFoodEffects(ServerLevel level,
                                         Village village,
                                         VillageSavedData data) {
        var foodNeed = village.getNeeds().get(NeedCategory.FOOD);
        if (foodNeed == null) return;

        level.getEntitiesOfClass(
                TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> b.inflate(32))
                        .orElse(new AABB(0, 0, 0, 0, 0, 0)),
                mob -> mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        ).forEach(mob -> {
            switch (foodNeed.getLevel()) {
                case LOW -> {
                    mob.addEffect(new MobEffectInstance(
                            MobEffects.SLOWNESS, UPDATE_INTERVAL, 0,
                            false, false));
                    mob.setIsWorkingBlocked(false);
                }
                case CRITICAL -> {
                    mob.addEffect(new MobEffectInstance(
                            MobEffects.SLOWNESS, UPDATE_INTERVAL, 1,
                            false, false));
                    mob.addEffect(new MobEffectInstance(
                            MobEffects.WEAKNESS, UPDATE_INTERVAL, 0,
                            false, false));
                    mob.setIsWorkingBlocked(true);
                }
                default -> {
                    mob.removeEffect(MobEffects.SLOWNESS);
                    mob.removeEffect(MobEffects.WEAKNESS);
                    mob.setIsWorkingBlocked(false);
                }
            }
        });
    }

    // =========================================================================
    // Kingdom formation
    // =========================================================================

    private static void checkKingdomFormation(ServerLevel level,
                                              Village village,
                                              VillageSavedData data,
                                              long tick) {
        // Already in a kingdom
        if (data.getKingdomForVillage(village.getId()).isPresent()) return;

        // Need at least 10 population
        int population = (int) level.getEntitiesOfClass(
                TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> b.inflate(32))
                        .orElse(new AABB(0, 0, 0, 0, 0, 0)),
                mob -> mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        ).size();

        if (population < 10) return;

        // Need a town hall at level 2+ before forming a kingdom
        boolean hasMatureTownHall = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .anyMatch(b -> b.getType() == BuildingType.TOWN_HALL
                        && b.getLevel() >= 2);

        if (!hasMatureTownHall) return;

        // Form kingdom named after the village
        String  kingdomName = village.getName() + " Kingdom";
        Kingdom kingdom     = new Kingdom(kingdomName, "default");
        kingdom.addVillage(village.getId());
        data.addKingdom(kingdom);

        kingdom.getHistory().recordEvent(
                HistoryTextGenerator.kingdomFounded(
                        kingdom.getName(), village.getName(), tick),
                kingdom.getName());

        kingdom.getHistory().setOrigin(
                new KingdomHistoryData.KingdomOriginData(
                        KingdomHistoryData.KingdomOrigins.ANCIENT,
                        "the village elders",
                        new UUID(0, 0),
                        village.getName(),
                        tick,
                        kingdom.getVillageIds().size(),
                        "From many, one."));

        data.setDirty();
        System.out.println("ServerTickDispatcher: formed kingdom '"
                + kingdomName + "' from village '" + village.getName() + "'");
    }
}