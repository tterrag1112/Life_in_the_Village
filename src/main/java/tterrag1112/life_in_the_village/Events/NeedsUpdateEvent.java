package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.ExpansionRequest;
import tterrag1112.life_in_the_village.Village.Buildings.VillageExpansionManager;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Decoration.VillageDecorator;
import tterrag1112.life_in_the_village.Village.Decoration.VillagePath;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.*;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;
import tterrag1112.life_in_the_village.Village.Event.VillageEventScheduler;
import tterrag1112.life_in_the_village.Village.Needs.NeedCategory;
import tterrag1112.life_in_the_village.Village.Needs.VillageNeedsCalculator;

import java.util.Map;


@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class NeedsUpdateEvent {

    private static final int UPDATE_INTERVAL = 24000;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        long dayTime = overworld.getDayTime();
        long gameTick = overworld.getGameTime();

        VillageSavedData data = VillageSavedData.get(overworld);

        for (Village village : data.getAllVillages()) {
            // Stagger updates by village name hash so not
            // all villages update on the same tick
            long offset = Math.abs(
                    village.getName().hashCode()
                            % UPDATE_INTERVAL);
            if ((dayTime + offset) % UPDATE_INTERVAL != 0)
                continue;

            // -----------------------------------------------
            // Recompute needs
            // -----------------------------------------------
            var needs = VillageNeedsCalculator.compute(
                    overworld, village, data);
            village.setNeeds(needs);
            village.setLastNeedsUpdate(dayTime);
            data.setDirty();

            System.out.println("Village '" + village.getName()
                    + "' needs updated: "
                    + needs.entrySet().stream()
                    .map(e -> e.getKey() + "="
                            + e.getValue().getLevel())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("none"));

            // -----------------------------------------------
            // Purge stale market listings
            // -----------------------------------------------
            data.getAllVillages().forEach(v ->
                    VillageEconomy.purgeStaleListings(
                            v.getId(), dayTime));

            // -----------------------------------------------
            // Village events
            // -----------------------------------------------
            VillageEventScheduler.tick(
                    overworld, village, data, dayTime);

            // -----------------------------------------------
            // Food effects on NPCs
            // -----------------------------------------------
            applyFoodEffects(overworld, village, data);

            // -----------------------------------------------
            // Path upgrades
            // -----------------------------------------------
            upgradePathsIfAffordable(overworld, village, data);

            // -----------------------------------------------
            // Kingdom formation check
            // -----------------------------------------------
            checkKingdomFormation(
                    overworld, village, data, dayTime);

            // -----------------------------------------------
            // Expansion — now handled by
            // VillageExpansionManager in WorldEvents
            // on its own 3-day interval, so we don't
            // duplicate it here. Just log current status.
            // -----------------------------------------------
            data.getPendingExpansionForVillage(village.getId())
                    .ifPresent(req -> System.out.println(
                            "Village '" + village.getName()
                                    + "' has pending expansion: "
                                    + req.getBuildingType()
                                    + " (" + req.getStatus() + ")"));
        }
    }

    // -------------------------------------------------------------------------
    // Fast tick — village events every second
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTickFast(
            ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        long tick = level.getGameTime();

        if (tick % 20 != 0) return;

        VillageSavedData data = VillageSavedData.get(level);
        VillageEventScheduler.tickEvents(level, data, tick);
    }

    // -------------------------------------------------------------------------
    // Path upgrades
    // -------------------------------------------------------------------------

    private static void upgradePathsIfAffordable(
            ServerLevel level, Village village,
            VillageSavedData data) {
        Map<VillagePath.PathTier, CurrencyValue> thresholds =
                Map.of(
                        VillagePath.PathTier.DIRT,
                        CurrencyValue.ofGold(5),
                        VillagePath.PathTier.GRAVEL,
                        CurrencyValue.ofGold(15),
                        VillagePath.PathTier.COBBLESTONE,
                        CurrencyValue.ofGold(30));

        // Use the improved wealth calculation from
        // VillageExpansionManager which includes
        // both NPC wealth and stockpile coins
        CurrencyValue wealth =
                VillageExpansionManager.getVillageWealth(
                        village.getBuildingIds().stream()
                                .map(data::getBuildingById)
                                .filter(java.util.Optional
                                        ::isPresent)
                                .map(java.util.Optional::get)
                                .collect(java.util.stream
                                        .Collectors.toList()),
                        level, data, village);

        for (VillagePath path : data.getPathsForVillage(
                village.getId())) {
            if (path.getTier().isMaxTier()) continue;

            CurrencyValue threshold =
                    thresholds.get(path.getTier());
            if (threshold == null) continue;
            if (!wealth.isAffordable(threshold)) continue;

            VillagePath.PathTier nextTier =
                    path.getTier().next();
            path.setTier(nextTier);

            // Place upgraded blocks
            for (BlockPos pos : path.getBlocks()) {
                BlockPos below = pos.below();
                if (level.getBlockState(below)
                        .isSolidRender()) {
                    level.setBlock(below,
                            nextTier.getBlock()
                                    .defaultBlockState(),
                            3);
                }
            }

            // Add lampposts when reaching cobblestone
            if (nextTier == VillagePath.PathTier.COBBLESTONE) {
                VillageBiomeStyle style =
                        VillageBiomeStyle.detect(level,
                                path.getBlocks().get(0));
                VillageSizeTier tier =
                        VillageSizeTier.fromBuildingCount(
                                village.getBuildingIds().size());
                VillageDecorator.placeLampposts(
                        level, path.getBlocks(), style, tier);
            }

            data.setDirty();
            System.out.println("Village '" + village.getName()
                    + "' upgraded path to " + nextTier);
        }
    }

    // -------------------------------------------------------------------------
    // Food effects
    // -------------------------------------------------------------------------

    private static void applyFoodEffects(ServerLevel level,
                                         Village village,
                                         VillageSavedData data) {
        var foodNeed = village.getNeeds().get(
                NeedCategory.FOOD);
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
                            MobEffects.SLOWNESS,
                            UPDATE_INTERVAL, 0,
                            false, false));
                    // Unblock work — just slowed
                    mob.setIsWorkingBlocked(false);
                }
                case CRITICAL -> {
                    mob.addEffect(new MobEffectInstance(
                            MobEffects.SLOWNESS,
                            UPDATE_INTERVAL, 1,
                            false, false));
                    mob.addEffect(new MobEffectInstance(
                            MobEffects.WEAKNESS,
                            UPDATE_INTERVAL, 0,
                            false, false));
                    // Block work when critically hungry
                    mob.setIsWorkingBlocked(true);
                }
                default -> {
                    // Satisfied — remove effects
                    mob.removeEffect(MobEffects.SLOWNESS);
                    mob.removeEffect(MobEffects.WEAKNESS);
                    mob.setIsWorkingBlocked(false);
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Kingdom formation
    // -------------------------------------------------------------------------

    private static void checkKingdomFormation(
            ServerLevel level, Village village,
            VillageSavedData data, long tick) {
        // Already in a kingdom
        if (data.getKingdomForVillage(
                village.getId()).isPresent()) return;

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

        // Need a town hall at level 2+ before forming
        // a kingdom — prevents immediate formation
        boolean hasMatureTownHall = village.getBuildingIds()
                .stream()
                .map(data::getBuildingById)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .anyMatch(b -> b.getType()
                        == BuildingType.TOWN_HALL
                        && b.getLevel() >= 2);

        if (!hasMatureTownHall) return;

        // Form kingdom named after the village
        String kingdomName = village.getName() + " Kingdom";
        Kingdom kingdom = new Kingdom(kingdomName, "default");
        kingdom.addVillage(village.getId());
        data.addKingdom(kingdom);
        data.setDirty();

        System.out.println("Kingdom '" + kingdomName
                + "' formed from village '"
                + village.getName() + "'");

        // Promote village leader to kingdom ruler
        level.getEntitiesOfClass(
                TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> b.inflate(32))
                        .orElse(new AABB(0, 0, 0, 0, 0, 0)),
                mob -> mob.getProfession()
                        == Profession
                        .VILLAGE_LEADER
                        && mob.getAssignedVillageName()
                        .map(n -> n.equals(
                                village.getName()))
                        .orElse(false)
        ).stream().findFirst().ifPresent(leader -> {
            kingdom.setRulerEntityId(leader.getUUID());
            leader.setProfession(
                    Profession.KINGDOM_RULER);
            System.out.println(leader.getNpcName()
                    + " appointed as ruler of "
                    + kingdom.getName());
            data.setDirty();
        });
    }
}