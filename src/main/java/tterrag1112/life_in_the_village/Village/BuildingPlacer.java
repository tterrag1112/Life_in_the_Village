package tterrag1112.life_in_the_village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.VillageExpansionManager;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
            BuildingType type,
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
        VillageBiomeStyle style = VillageBiomeStyle.detect(level, pos);
        if (style != VillageBiomeStyle.PLAINS) {
            applyBiomeSwap(level, pos, template, rotation, style);
        }
        System.out.println("Place result: " + placed);

        Vec3i rawSize = template.getSize();

        int w = rawSize.getX();
        int h = rawSize.getY();
        int l = rawSize.getZ();

        BlockPos origin;
        int width, length;

        switch (rotation) {
            case CLOCKWISE_90 -> {
                // Rotated 90 CW: X becomes Z, Z becomes -X
                origin = pos.offset(-(l - 1), 0, 0);
                width = l;
                length = w;
            }
            case COUNTERCLOCKWISE_90 -> {
                // Rotated 90 CCW: X becomes -Z, Z becomes X
                origin = pos.offset(0, 0, -(w - 1));
                width = l;
                length = w;
            }
            case CLOCKWISE_180 -> {
                // Rotated 180: both axes flipped
                origin = pos.offset(-(w - 1), 0, -(l - 1));
                width = w;
                length = l;
            }
            default -> {
                // NONE: pos is already the northwest corner
                origin = pos;
                width = w;
                length = l;
            }
        }

        Building.BuildingShape shape = new Building.BuildingShape(
                origin, width, h, length
        );

        Building building = new Building(name, type, shape, structureId, rotation, 1);
        VillageSavedData data = VillageSavedData.get(level);

        data.addBuilding(building);

        if (type == BuildingType.GUILD_HALL) {
            // Check pos, origin, and center of the building shape
            // since any of these might fall inside village bounds
            var village = data.getVillageAt(pos)
                    .or(() -> data.getVillageAt(origin))
                    .or(() -> data.getVillageAt(shape.getOrigin()))
                    .or(() -> {
                        // Last resort — nearest village within 200 blocks
                        return data.getAllVillages().stream()
                                .filter(v -> v.getBounds(data)
                                        .map(b -> BlockPos.containing(b.getCenter())
                                                .closerThan(pos, 200))
                                        .orElse(false))
                                .min(Comparator.comparingDouble(v ->
                                        v.getBounds(data)
                                                .map(b -> BlockPos.containing(
                                                        b.getCenter()).distSqr(pos))
                                                .orElse(Double.MAX_VALUE)));
                    })
                    .orElse(null);

            if (village == null) {
                System.out.println("Guild Hall placed at " + pos.toShortString()
                        + " but no village found within 200 blocks.");
                return Optional.of(building);
            }

            if (data.getGuildForVillage(village.getId()).isEmpty()) {
                GuildData guild = new GuildData(
                        UUID.randomUUID(),
                        village.getId(),
                        UUID.fromString("00000000-0000-0000-0000-000000000000"),
                        0L);
                data.addGuild(guild);
                data.setDirty();
                System.out.println("Created guild for village: "
                        + village.getName()
                        + " | Guild ID: "
                        + guild.guildId());
            } else {
                System.out.println("Guild already registered for village: "
                        + village.getName());
            }
        }


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
    public static Profession getProfessionForType(BuildingType type) {
        return Profession.professionFor(type);
    }
    private static void applyBiomeSwap(ServerLevel level,
                                       BlockPos origin,
                                       StructureTemplate template,
                                       Rotation rotation,
                                       VillageBiomeStyle style) {
        Vec3i size = template.getSize();

        // Compute the actual world bounds after rotation
        int w, l;
        switch (rotation) {
            case CLOCKWISE_90, COUNTERCLOCKWISE_90 -> { w = size.getZ(); l = size.getX(); }
            default                                -> { w = size.getX(); l = size.getZ(); }
        }
        int h = size.getY();

        // Build swap table: default block → biome block
        // Only swap when the target style actually differs from the source.
        Map<Block, Block> swaps = buildSwapTable(style);
        if (swaps.isEmpty()) return;

        for (int dx = 0; dx < w; dx++) {
            for (int dy = 0; dy < h; dy++) {
                for (int dz = 0; dz < l; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState current = level.getBlockState(pos);
                    Block replacement = swaps.get(current.getBlock());
                    if (replacement == null) continue;

                    // Preserve block state properties where possible
                    // (facing, waterlogged, etc.) by mapping onto the
                    // replacement block's default state.
                    level.setBlock(pos,
                            replacement.defaultBlockState(), 3);
                }
            }
        }
    }

    /**
     * Builds the block-swap table for a given biome style.
     * Maps default (PLAINS/oak/cobblestone) blocks → style-appropriate blocks.
     * Returns an empty map for PLAINS since no swap is needed.
     */
    private static Map<Block, Block> buildSwapTable(VillageBiomeStyle style) {
        if (style == VillageBiomeStyle.PLAINS) return Map.of();

        Map<Block, Block> swaps = new java.util.HashMap<>();

        // Wood swaps — planks, logs, stairs, slabs, fences
        if (style.planks != Blocks.OAK_PLANKS) {
            swaps.put(Blocks.OAK_PLANKS,      style.planks);
            swaps.put(Blocks.OAK_LOG,          style.log);
            swaps.put(Blocks.OAK_STAIRS,       style.woodStairs);
            swaps.put(Blocks.OAK_SLAB,         style.woodSlab);
            swaps.put(Blocks.OAK_FENCE,        style.fence);
            swaps.put(Blocks.OAK_FENCE_GATE,   style.fenceGate);
            swaps.put(Blocks.OAK_DOOR,         doorFor(style));
            swaps.put(Blocks.OAK_TRAPDOOR,     trapdoorFor(style));
        }

        // Stone swaps — cobblestone, walls, stairs, slabs
        if (style.stone != Blocks.COBBLESTONE) {
            swaps.put(Blocks.COBBLESTONE,        style.stone);
            swaps.put(Blocks.COBBLESTONE_WALL,   style.stoneWall);
            swaps.put(Blocks.COBBLESTONE_STAIRS, style.stoneStairs);
            swaps.put(Blocks.COBBLESTONE_SLAB,   style.stoneSlab);
            // Stone bricks → style stone for taiga/jungle
            swaps.put(Blocks.STONE_BRICKS,       style.stone);
        }

        // Lantern swap
        if (style.lantern != Blocks.LANTERN) {
            swaps.put(Blocks.LANTERN, style.lantern);
        }

        return swaps;
    }

    private static Block doorFor(VillageBiomeStyle style) {
        return switch (style) {
            case TAIGA, SNOWY -> Blocks.SPRUCE_DOOR;
            case JUNGLE       -> Blocks.JUNGLE_DOOR;
            case SAVANNA      -> Blocks.ACACIA_DOOR;
            case SWAMP        -> Blocks.DARK_OAK_DOOR;
            case DESERT       -> Blocks.OAK_DOOR; // no sandstone door
            default           -> Blocks.OAK_DOOR;
        };
    }

    private static Block trapdoorFor(VillageBiomeStyle style) {
        return switch (style) {
            case TAIGA, SNOWY -> Blocks.SPRUCE_TRAPDOOR;
            case JUNGLE       -> Blocks.JUNGLE_TRAPDOOR;
            case SAVANNA      -> Blocks.ACACIA_TRAPDOOR;
            case SWAMP        -> Blocks.DARK_OAK_TRAPDOOR;
            case DESERT       -> Blocks.OAK_TRAPDOOR;
            default           -> Blocks.OAK_TRAPDOOR;
        };
    }
}
