package tterrag1112.life_in_the_village.Entities.Goals;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

public class PostListingGoal extends Goal {

    private static final int UPDATE_INTERVAL = 1200;

    private final TownspersonMob entity;
    private final Set<Item> sellableItems;
    private int timer = 0;

    public PostListingGoal(TownspersonMob entity,
                           Set<Item> sellableItems) {
        this.entity        = entity;
        this.sellableItems = sellableItems;
        setFlags(EnumSet.noneOf(Flag.class)); // non-blocking
    }

    @Override
    public boolean canUse() { return true; }

    @Override
    public boolean canContinueToUse() { return true; }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        timer++;
        if (timer < UPDATE_INTERVAL) return;
        timer = 0;

        if (!(entity.level() instanceof ServerLevel level)) return;

        UUID villageId = entity.getAssignedVillageName()
                .flatMap(name ->
                        VillageSavedData.get(level).getVillageByName(name))
                .map(v -> v.getId())
                .orElse(null);

        if (villageId == null) return;

        Building building = entity.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .orElse(null);

        if (building == null) return;

        long tick = level.getGameTime();

        // Post listing for each item we have in stock
        for (Item item : sellableItems) {
            int count = BuildingStorageAccess.countItem(
                    level, building, item);
            if (count > 0) {
                VillageEconomy.postListing(
                        level, villageId, entity, item, count, tick);
            }
        }
    }
}