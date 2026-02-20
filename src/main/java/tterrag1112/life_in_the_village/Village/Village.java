package tterrag1112.life_in_the_village.Village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class Village {
    private String name;
    private final List<UUID> buildingIds;

    public Village(String name, List<UUID> buildingIds) {
        this.name = name;
        this.buildingIds = new ArrayList<>(buildingIds);
    }

    public Village(String name) {
        this(name, new ArrayList<>());
    }

    public String getName() { return name; }
    public List<UUID> getBuildingIds() { return List.copyOf(buildingIds); }

    public void addBuilding(Building building) {
        if (!buildingIds.contains(building.getId())) {
            buildingIds.add(building.getId());
        }
    }

    public void removeBuilding(Building building) {
        buildingIds.remove(building.getId());
    }

    public Optional<AABB> getBounds(VillageSavedData data) {
        List<Building> buildings = buildingIds.stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        if (buildings.isEmpty()) return Optional.empty();

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (Building b : buildings) {
            BlockPos min = b.getShape().getMin();
            BlockPos max = b.getShape().getMax();
            minX = Math.min(minX, min.getX());
            minY = Math.min(minY, min.getY());
            minZ = Math.min(minZ, min.getZ());
            maxX = Math.max(maxX, max.getX());
            maxY = Math.max(maxY, max.getY());
            maxZ = Math.max(maxZ, max.getZ());
        }

        return Optional.of(new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1));
    }

    public boolean contains(BlockPos pos, VillageSavedData data) {
        return getBounds(data)
                .map(aabb -> aabb.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5))
                .orElse(false);
    }

    public static final Codec<Village> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("name").forGetter(Village::getName),
                    Building.UUID_CODEC.listOf()
                            .fieldOf("buildingIds")
                            .forGetter(Village::getBuildingIds)
            ).apply(instance, Village::new)
    );
}

