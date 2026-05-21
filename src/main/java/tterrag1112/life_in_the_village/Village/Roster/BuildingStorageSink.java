package tterrag1112.life_in_the_village.Village.Roster;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

import java.util.UUID;

/**
 * Phase 6.3.3.g.1 — default {@link ProductionOutputSink} implementation.
 * Deposits each produced item into the host building's storage
 * (chests / barrels found via {@link BuildingStorageAccess.findInventories}).
 *
 * <p>If the building can't be resolved or has no storage, items are
 * silently dropped — acceptable for v1; the cycle still ticks and
 * future stock represents production-going-forward.
 */
public final class BuildingStorageSink {

    private BuildingStorageSink() {}

    public static ProductionOutputSink forBuilding(ServerLevel level, UUID buildingId) {
        return stack -> deposit(level, buildingId, stack);
    }

    private static void deposit(ServerLevel level, UUID buildingId, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        Building building = VillageSavedData.get(level).getBuildingById(buildingId).orElse(null);
        if (building == null) return;
        BuildingStorageAccess.storeItem(level, building, stack);
    }
}
