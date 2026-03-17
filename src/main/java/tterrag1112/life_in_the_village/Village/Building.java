package tterrag1112.life_in_the_village.Village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.*;

import static com.ibm.icu.text.PluralRules.Operand.i;


public class Building {
    private String buildingName;
    private final UUID id;
    private BuildingType buildingType;
    private BuildingShape buildingShape;
    private int buildingLevel;
    private Identifier structureId;
    private Rotation rotation;

    public static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(
            UUID::fromString, UUID::toString
    );
    public static final Codec<Building> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUID_CODEC.fieldOf("id").forGetter(Building::getId),
                    Codec.STRING.fieldOf("name").forGetter(Building::getName),
                    Codec.STRING.xmap(BuildingType::valueOf, BuildingType::name)
                            .fieldOf("type").forGetter(Building::getType),
                    BuildingShape.SHAPE_CODEC.fieldOf("shape").forGetter(Building::getShape),
                    Identifier.CODEC.fieldOf("structureId").forGetter(Building::getStructureId),
                    Codec.INT.fieldOf("buildingLevel").forGetter(Building::getLevel),
                    Codec.STRING.xmap(Rotation::valueOf, Rotation::name)
                            .fieldOf("rotation").forGetter(Building::getRotation)
            ).apply(instance, Building::new) // calls the private constructor with UUID
    );




    public Building(String name, BuildingType type, BuildingShape shape, Identifier structureId, Rotation rotation, int level){
        this.buildingName = name;
        this.buildingType = type;
        this.buildingShape = shape;
        this.structureId = structureId;
        this.rotation = rotation;
        this.buildingLevel = level;
        this.id = UUID.randomUUID();
    }
    private Building(UUID id, String name, BuildingType type, BuildingShape shape, Identifier structureId, int buildingLevel, Rotation rotation) {
        this.id = id;
        this.buildingName = name;
        this.buildingType = type;
        this.buildingShape = shape;
        this.structureId = structureId;
        this.buildingLevel = buildingLevel;
        this.rotation = rotation;
    }


    public Identifier getStructureId() { return structureId; }
    public void setStructureId(Identifier id) {
        this.structureId = id;
    }

    public void setUpgradeLevel(int level) {
        this.buildingLevel = level;
    }
    public UUID getId() { return id; }
    public String getName(){
        return this.buildingName;
    }
    public int getLevel(){return this.buildingLevel;}
    public void setLevel(int level){this.buildingLevel = level;}
    public BuildingType getType(){
        return this.buildingType;
    }
    public Rotation getRotation() { return rotation; }
    public BuildingShape getShape(){
        return this.buildingShape;
    }



    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", buildingName);
        tag.putString("type", buildingType.name());
        tag.put("shape", buildingShape.save());
        tag.putString("id", structureId.getNamespace());
        tag.putString("rotation", rotation.name());
        tag.putInt("level", buildingLevel);
        return tag;
    }
    public Building load(CompoundTag tag) {
        String name = tag.getString("name").get();
        BuildingType type = BuildingType.valueOf(tag.getString("type").get());
        BuildingShape shape = BuildingShape.load(tag.getCompound("shape").get());
        Identifier id = this.getStructureId();
        Rotation rotation = this.getRotation();
        int level = tag.getIntOr("level", 0);
        return new Building(name, type, shape, id, rotation, level);
    }

    public void fillBlock(Building building, ServerLevel level, Block target, Block replacement) {
        BuildingShape shape = building.getShape();
        BlockPos min = shape.getMin();
        BlockPos max = shape.getMax();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).is(target)) {
                        level.setBlock(pos, replacement.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    public boolean upgrade(ServerLevel level) {
        int nextLevel = buildingLevel + 1;
        Identifier nextStructure = Identifier.fromNamespaceAndPath(
                structureId.getNamespace(),
                structureId.getPath().replaceAll("level_\\d+", "level_" + nextLevel)
        );

        System.out.println("Attempting upgrade to: " + nextStructure);

        Optional<StructureTemplate> templateOpt = BuildingPlacer.loadTemplate(level, nextStructure);
        if (templateOpt.isEmpty()) {
            System.out.println("No higher level structure found");
            return false;
        }
        StructureTemplate template = templateOpt.get();

        System.out.println("Upgrade template size: " + template.getSize());

        StructurePlaceSettings settings = new StructurePlaceSettings();
        template.placeInWorld(level, buildingShape.getOrigin(), BlockPos.ZERO, settings, level.random, 2);

        this.structureId = nextStructure;
        this.buildingLevel = nextLevel;
        VillageSavedData.get(level).setDirty();

        return true;
    }
    public boolean downgrade(ServerLevel level) {
        int previousLevel = buildingLevel - 1;
        Identifier nextStructure = Identifier.fromNamespaceAndPath(
                structureId.getNamespace(),
                structureId.getPath().replaceAll("level_\\d+", "level_" + previousLevel)
        );

        System.out.println("Attempting downgrade to: " + nextStructure);

        Optional<StructureTemplate> templateOpt = BuildingPlacer.loadTemplate(level, nextStructure);
        if (templateOpt.isEmpty()) {
            System.out.println("No lower level structure found");
            return false;
        }
        StructureTemplate template = templateOpt.get();

        System.out.println("Downgrade template size: " + template.getSize());

        StructurePlaceSettings settings = new StructurePlaceSettings();
        template.placeInWorld(level, buildingShape.getOrigin(), BlockPos.ZERO, settings, level.random, 2);

        this.structureId = nextStructure;
        this.buildingLevel = previousLevel;
        VillageSavedData.get(level).setDirty();

        return true;
    }




    public static class BuildingShape {
        private final BlockPos originPoint;
        private final int width;
        private final int length;
        private final int height;
        public static final Codec<BuildingShape> SHAPE_CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        BlockPos.CODEC.fieldOf("origin").forGetter(BuildingShape::getOrigin),
                        Codec.INT.fieldOf("width").forGetter(BuildingShape::getWidth),
                        Codec.INT.fieldOf("height").forGetter(BuildingShape::getHeight),
                        Codec.INT.fieldOf("length").forGetter(BuildingShape::getLength)
                ).apply(instance, BuildingShape::new)
        );


        public BuildingShape(BlockPos origin, int width, int height, int length) {
            this.originPoint = origin;
            this.width = width;
            this.height = height;
            this.length = length;
        }

        public AABB toAABB() {
            BlockPos max = getMax();
            return new AABB(
                    originPoint.getX(), originPoint.getY(), originPoint.getZ(),
                    max.getX() + 1, max.getY() + 1, max.getZ() + 1
            );
        }

        public BlockPos getOrigin() {
            return this.originPoint;
        }

        public int getWidth() {
            return this.width;
        }

        public int getLength() {
            return this.length;
        }

        public int getHeight() {
            return this.height;
        }

        public BlockPos getMin() {
            return originPoint;
        }

        public BlockPos getMax() {

            return originPoint.offset(width - 1, height - 1, length - 1);
        }

        /**
         * Check if a given position falls inside this building's bounds.
         */
        public boolean contains(BlockPos pos) {
            BlockPos max = getMax();
            return pos.getX() >= originPoint.getX() && pos.getX() <= max.getX()
                    && pos.getY() >= originPoint.getY() && pos.getY() <= max.getY()
                    && pos.getZ() >= originPoint.getZ() && pos.getZ() <= max.getZ();
        }


        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("ox", originPoint.getX());
            tag.putInt("oy", originPoint.getY());
            tag.putInt("oz", originPoint.getZ());
            tag.putInt("width", width);
            tag.putInt("height", height);
            tag.putInt("length", length);
            return tag;
        }

        public static BuildingShape load(CompoundTag tag) {
            BlockPos origin = new BlockPos(
                    tag.getInt("ox").get(), tag.getInt("oy").get(), tag.getInt("oz").get()
            );
            return new BuildingShape(
                    origin,
                    tag.getInt("width").get(),
                    tag.getInt("height").get(),
                    tag.getInt("length").get()
            );
        }
    }


    public class ClientBuildingCache {
        private static final List<Building> buildings = new ArrayList<>();
        private static final List<Village> villages = new ArrayList<>();

        public static void setBuildings(List<Building> incoming) {
            buildings.clear();
            buildings.addAll(incoming);
        }

        public static void setVillages(List<Village> incoming) {
            villages.clear();
            villages.addAll(incoming);
        }

        public static List<Building> getBuildings() { return List.copyOf(buildings); }
        public static List<Village> getVillages() { return List.copyOf(villages); }

        /**
         * Finds the nearest village to a given position using the village bounds center.
         */
        public static Optional<Village> getNearestVillage(BlockPos playerPos) {
            return villages.stream()
                    .min(Comparator.comparingDouble(v -> {
                        // Use the center of the first building as a rough distance proxy
                        // since we don't have full SavedData on the client
                        return v.getBuildingIds().stream()
                                .map(id -> buildings.stream()
                                        .filter(b -> b.getId().equals(id))
                                        .findFirst())
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .mapToDouble(b -> {
                                    BlockPos origin = b.getShape().getOrigin();
                                    double dx = origin.getX() - playerPos.getX();
                                    double dz = origin.getZ() - playerPos.getZ();
                                    return Math.sqrt(dx * dx + dz * dz);
                                })
                                .min()
                                .orElse(Double.MAX_VALUE);
                    }));
        }
    }


    }
