package tterrag1112.life_in_the_village.Networking;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class VillageSavedData extends SavedData {
    private static final Codec<VillageSavedData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Building.CODEC.listOf()
                            .fieldOf("buildings")
                            .forGetter(data -> data.buildings),
                    Village.CODEC.listOf()
                            .fieldOf("villages")
                            .forGetter(data -> data.villages)
            ).apply(instance, VillageSavedData::fromLists)
    );


    public static final SavedDataType<VillageSavedData> TYPE = new SavedDataType<>(
            "life_in_the_village_buildings",
            VillageSavedData::new,  // factory for a fresh empty instance
            CODEC
    );

    private final List<Building> buildings;
    private final List<Village> villages = new ArrayList<>();


    // Used when creating a fresh instance (no saved data exists yet)
    public VillageSavedData() {
        this.buildings = new ArrayList<>();
    }

    // Used by the codec when loading from disk
    private VillageSavedData(List<Building> buildings) {
        this.buildings = new ArrayList<>(buildings);
    }

    // Helper so the codec lambda can call this cleanly
    private static VillageSavedData fromList(List<Building> buildings) {
        return new VillageSavedData(buildings);
    }

    // --- Access point ---

    public static VillageSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // --- Building management ---

    public void addBuilding(Building building) {
        buildings.add(building);
        setDirty();
    }

    public void removeBuilding(Building building) {
        buildings.remove(building);
        setDirty();
    }

    public List<Building> getAllBuildings() {
        return List.copyOf(buildings);
    }

    public Optional<Building> getBuildingById(UUID id) {
        return buildings.stream()
                .filter(b -> b.getId().equals(id))
                .findFirst();
    }

    // Keep getBuildingAt for position-based lookup
    public Optional<Building> getBuildingAt(BlockPos pos) {
        return buildings.stream()
                .filter(b -> b.getShape().contains(pos))
                .findFirst();
    }

    public List<Building> getBuildingsByType(Building.BuildingType type) {
        return buildings.stream()
                .filter(b -> b.getType() == type)
                .toList();
    }

    public void addVillage(Village village) {
        villages.add(village);
        setDirty();
    }

    public List<Village> getAllVillages() {
        return List.copyOf(villages);
    }

    public Optional<Village> getVillageByName(String name) {
        return villages.stream()
                .filter(v -> v.getName().equals(name))
                .findFirst();
    }

    public Optional<Village> getVillageAt(BlockPos pos) {
        return villages.stream()
                .filter(v -> v.contains(pos, this))
                .findFirst();
    }

    // Used by Village.getBounds to resolve building names to objects
    public Optional<Building> getBuildingByName(String name) {
        return buildings.stream()
                .filter(b -> b.getName().equals(name))
                .findFirst();
    }

    // Update the codec to include villages


    private static VillageSavedData fromLists(List<Building> buildings, List<Village> villages) {
        VillageSavedData data = new VillageSavedData();
        data.buildings.addAll(buildings);
        data.villages.addAll(villages);
        return data;
    }





}
