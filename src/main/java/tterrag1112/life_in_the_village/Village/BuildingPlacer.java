package tterrag1112.life_in_the_village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.Optional;

public class BuildingPlacer {

    /**
     * Places an NBT structure in the world and returns a Building with the
     * correct bounding box automatically derived from the structure's size.
     *
     * @param level       the server level to place into
     * @param pos         the origin corner to place at
     * @param structureId the ResourceLocation of the .nbt file
     *                    e.g. "life_in_the_village:inn/level_1"
     * @param name        display name for the building
     * @param type        building type enum value
     * @return the registered Building, or empty if the structure wasn't found
     */
    public static Optional<Building> placeAndRegister(
            ServerLevel level,
            BlockPos pos,
            Identifier structureId,
            String name,
            Building.BuildingType type,
            Rotation rotation

    ) {

        Optional<StructureTemplate> templateOpt = loadTemplate(level, structureId);
        if (templateOpt.isEmpty()) {
            System.out.println("Structure not found: " + structureId);
            return Optional.empty();
        }
        StructureTemplate template = templateOpt.get();

        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation);
        boolean placed = template.placeInWorld(level, pos, BlockPos.ZERO, settings, level.random, 2);
        System.out.println("Place result: " + placed);

        Vec3i rawSize = template.getSize();
        int width, length;
        if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
            width = rawSize.getZ();
            length = rawSize.getX();
        } else {
            width = rawSize.getX();
            length = rawSize.getZ();
        }

        Building.BuildingShape shape = new Building.BuildingShape(pos, width, rawSize.getY(), length);

        Building building = new Building(name, type, shape, structureId, rotation, 1);
        VillageSavedData.get(level).addBuilding(building);

        return Optional.of(building);
    }



    public static Optional<StructureTemplate> loadTemplate(ServerLevel level, Identifier structureId) {
        try {
            var path = level.getServer().getResourceManager()
                    .getResource(Identifier.fromNamespaceAndPath(
                            structureId.getNamespace(),
                            "structures/" + structureId.getPath() + ".nbt"
                    ));

            if (path.isEmpty()) return Optional.empty();

            StructureTemplate template = new StructureTemplate();
            try (var stream = path.get().open()) {
                CompoundTag tag = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
                template.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), tag);
            }
            return Optional.of(template);

        } catch (Exception e) {
            System.out.println("Failed to load structure " + structureId + ": " + e.getMessage());
            return Optional.empty();
        }
    }
}
