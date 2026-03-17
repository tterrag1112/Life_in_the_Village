package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

public class VillageDecorator {

    public static void decorateVillage(ServerLevel level, Village village,
                                       VillageSavedData data) {
        List<Building> buildings = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        if (buildings.isEmpty()) return;

        // Detect biome style and size tier
        BlockPos center = buildings.get(0).getShape().getOrigin();
        VillageBiomeStyle style = VillageBiomeStyle.detect(level, center);
        VillageSizeTier tier = VillageSizeTier
                .fromBuildingCount(buildings.size());

        System.out.println("VillageDecorator: decorating '"
                + village.getName() + "' as " + tier.displayName
                + " in " + style.name() + " style");

        // Terrain smoothing
        buildings.forEach(b -> smoothTerrainAroundBuilding(level, b));

        // Paths with style-aware blocks
        placePaths(level, village, buildings, data, style, tier);

        // Well — only for village tier and above
        if (tier.hasWell) {
            findTownHall(buildings).ifPresent(townHall ->
                    placeWell(level, townHall, style));
        }

        // Fences around buildings
        buildings.forEach(b -> placeFence(level, b, style));

        // Social decorations
        buildings.stream()
                .filter(b -> b.getType() == Building.BuildingType.INN
                        || b.getType()
                        == Building.BuildingType.GUILD_HALL)
                .forEach(b -> placeSocialDecorations(
                        level, b, style));

        // Market stalls — town and city only
        if (tier.hasMarket) {
            findTownHall(buildings).ifPresent(townHall ->
                    placeMarketStalls(level, townHall, style));
        }

        // Landmark — town and city only
        if (tier.hasLandmark) {
            findTownHall(buildings).ifPresent(townHall ->
                    placeLandmark(level, townHall, style, village));
        }

        // Flowers
        if (tier.flowerAttempts > 0) {
            scatterFlowers(level, village, data, style,
                    tier.flowerAttempts);
        }
    }

    // -------------------------------------------------------------------------
    // Paths — now style-aware
    // -------------------------------------------------------------------------

    private static void placePaths(ServerLevel level, Village village,
                                   List<Building> buildings,
                                   VillageSavedData data,
                                   VillageBiomeStyle style,
                                   VillageSizeTier tier) {
        Optional<Building> townHall = findTownHall(buildings);
        if (townHall.isEmpty()) return;

        BlockPos townHallEntrance =
                PathRouter.getBuildingEntrance(townHall.get());

        for (Building building : buildings) {
            if (building == townHall.get()) continue;

            BlockPos entrance = PathRouter.getBuildingEntrance(building);
            List<BlockPos> pathBlocks = PathRouter.findPath(
                    level, townHallEntrance, entrance);

            if (pathBlocks.isEmpty()) continue;

            for (int i = 0; i < pathBlocks.size(); i++) {
                BlockPos pos = pathBlocks.get(i);
                BlockPos prev = i > 0
                        ? pathBlocks.get(i - 1) : pos;
                BlockPos next = i < pathBlocks.size() - 1
                        ? pathBlocks.get(i + 1) : pos;

                int dx = next.getX() - prev.getX();
                int dz = next.getZ() - prev.getZ();
                int perpX = dz != 0 ? 1 : 0;
                int perpZ = dx != 0 ? 1 : 0;

                for (int offset = -1; offset <= 1; offset++) {
                    BlockPos wide = pos.offset(
                            perpX * offset, 0, perpZ * offset);
                    BlockPos below = wide.below();
                    BlockState belowState =
                            level.getBlockState(below);
                    if (!belowState.isSolidRender()) continue;

                    // Use style path block
                    level.setBlock(below,
                            style.pathState(), 3);

                    BlockState atWide = level.getBlockState(wide);
                    if (!atWide.isAir()
                            && !atWide.isSolidRender()) {
                        level.setBlock(wide,
                                Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }

            // Place lampposts along path
            placeLampposts(level, pathBlocks, style, tier);

            VillagePath path = new VillagePath(
                    UUID.randomUUID(), village.getId(),
                    pathBlocks, VillagePath.PathTier.DIRT);
            data.addVillagePath(path);
        }
    }

    // -------------------------------------------------------------------------
    // Lampposts — now style-aware with tier spacing
    // -------------------------------------------------------------------------

    public static void placeLampposts(ServerLevel level,
                                      List<BlockPos> pathBlocks,
                                      VillageBiomeStyle style,
                                      VillageSizeTier tier) {
        for (int i = 0; i < pathBlocks.size();
             i += tier.lampostSpacing) {
            BlockPos pos = pathBlocks.get(i);
            BlockPos polePos = pos.above();
            BlockPos lampPos = pos.above(2);

            if (!level.getBlockState(polePos).isAir()) continue;

            level.setBlock(polePos,
                    style.fenceState(), 3);
            level.setBlock(lampPos,
                    style.lanternState(), 3);
        }
    }

    // -------------------------------------------------------------------------
    // Well — now style-aware
    // -------------------------------------------------------------------------

    private static void placeWell(ServerLevel level,
                                  Building townHall,
                                  VillageBiomeStyle style) {
        // Place well in front of the town hall entrance
        // rather than at its center
        BlockPos entrance = PathRouter.getBuildingEntrance(townHall);

        // Offset 5 blocks out from the entrance
        BlockPos wellBase = entrance.offset(
                0, 0, 5); // adjust direction based on your entrance facing

        // Find actual surface at that position
        BlockPos surface = findSurface(level, wellBase);

        // Make sure we're not inside the building
        if (townHall.getShape().contains(surface)) {
            // Try other directions
            for (int dist = 4; dist <= 10; dist++) {
                for (BlockPos candidate : List.of(
                        entrance.north(dist),
                        entrance.south(dist),
                        entrance.east(dist),
                        entrance.west(dist))) {
                    BlockPos s = findSurface(level, candidate);
                    if (!townHall.getShape().contains(s)) {
                        surface = s;
                        break;
                    }
                }
                if (!townHall.getShape().contains(surface)) break;
            }
        }

        placeWellAt(level, surface, style);
    }

    private static void placeWellAt(ServerLevel level,
                                    BlockPos surface,
                                    VillageBiomeStyle style) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = surface.offset(dx, 0, dz);
                if (dx == 0 && dz == 0) {
                    level.setBlock(pos,
                            Blocks.WATER.defaultBlockState(), 3);
                    level.setBlock(pos.above(),
                            style.stoneWallState(), 3);
                } else if (Math.abs(dx) == 1
                        ^ Math.abs(dz) == 1) {
                    level.setBlock(pos,
                            style.stoneWallState(), 3);
                    level.setBlock(pos.above(),
                            style.fenceState(), 3);
                } else {
                    level.setBlock(pos,
                            style.stoneSlab(), 3);
                }
            }
        }
        level.setBlock(surface.above(2),
                style.lanternState(), 3);
    }

    // -------------------------------------------------------------------------
    // Fence — now style-aware and re-enabled
    // -------------------------------------------------------------------------

    private static void placeFence(ServerLevel level, Building building,
                                   VillageBiomeStyle style) {
        BlockPos min = building.getShape().getMin();
        BlockPos max = building.getShape().getMax();
        int midX = (min.getX() + max.getX()) / 2;

        // North and south walls
        for (int x = min.getX() - 1; x <= max.getX() + 1; x++) {
            placeGateOrFence(level,
                    findSurface(level, new BlockPos(
                            x, min.getY(), min.getZ() - 1)),
                    x == midX, style);
            placeGateOrFence(level,
                    findSurface(level, new BlockPos(
                            x, min.getY(), max.getZ() + 1)),
                    x == midX, style);
        }

        // East and west walls
        for (int z = min.getZ() - 1; z <= max.getZ() + 1; z++) {
            placeGateOrFence(level,
                    findSurface(level, new BlockPos(
                            min.getX() - 1, min.getY(), z)),
                    false, style);
            placeGateOrFence(level,
                    findSurface(level, new BlockPos(
                            max.getX() + 1, min.getY(), z)),
                    false, style);
        }
    }

    private static void placeGateOrFence(ServerLevel level,
                                         BlockPos pos, boolean isGate,
                                         VillageBiomeStyle style) {
        BlockPos below = pos.below();
        if (!level.getBlockState(below).isSolidRender()) return;
        if (level.getBlockState(pos).isSolidRender()) return;

        level.setBlock(pos, isGate
                ? style.fenceGateState()
                : style.fenceState(), 3);
    }

    // -------------------------------------------------------------------------
    // Social decorations — now style-aware
    // -------------------------------------------------------------------------

    private static void placeSocialDecorations(ServerLevel level,
                                               Building building,
                                               VillageBiomeStyle style) {
        BlockPos entrance = PathRouter.getBuildingEntrance(building);

        // Barrels on each side
        placeIfClear(level, entrance.east(),
                Blocks.BARREL.defaultBlockState());
        placeIfClear(level, entrance.west(),
                Blocks.BARREL.defaultBlockState());

        // Style-appropriate lantern above entrance
        BlockPos aboveEntrance = entrance.above();
        if (level.getBlockState(aboveEntrance).isAir()) {
            level.setBlock(aboveEntrance,
                    style.lanternState(), 3);
        }

        // Bench — two slabs side by side
        BlockPos benchPos = entrance.south(2);
        placeIfClear(level, benchPos, style.woodSlab());
        placeIfClear(level, benchPos.east(), style.woodSlab());
    }

    // -------------------------------------------------------------------------
    // Market stalls — new, towns and cities only
    // -------------------------------------------------------------------------

    private static void placeMarketStalls(ServerLevel level,
                                          Building townHall,
                                          VillageBiomeStyle style) {
        BlockPos center = townHall.getShape().getOrigin()
                .offset(townHall.getShape().getWidth() / 2, 0,
                        townHall.getShape().getLength() / 2);
        BlockPos surface = findSurface(level, center);

        // Place 2 stalls on either side of the well
        placeMarketStall(level, surface.east(4), style);
        placeMarketStall(level, surface.west(4), style);
    }

    private static void placeMarketStall(ServerLevel level,
                                         BlockPos origin,
                                         VillageBiomeStyle style) {
        BlockPos surface = findSurface(level, origin);

        // Counter — row of slabs
        for (int i = -1; i <= 1; i++) {
            placeIfClear(level, surface.offset(i, 0, 0),
                    style.woodSlab());
        }

        // Back wall — fence posts
        placeIfClear(level, surface.offset(-1, 1, 1),
                style.fenceState());
        placeIfClear(level, surface.offset(1, 1, 1),
                style.fenceState());

        // Roof — slabs on top of fence posts
        placeIfClear(level, surface.offset(-1, 2, 1),
                style.woodSlab());
        placeIfClear(level, surface.offset(0, 2, 1),
                style.woodSlab());
        placeIfClear(level, surface.offset(1, 2, 1),
                style.woodSlab());

        // Some items on the counter
        placeIfClear(level, surface.offset(-1, 1, 0),
                Blocks.FLOWER_POT.defaultBlockState());
        placeIfClear(level, surface.offset(1, 1, 0),
                Blocks.BARREL.defaultBlockState());
    }

    // -------------------------------------------------------------------------
    // Landmark — new, towns and cities only
    // -------------------------------------------------------------------------

    private static void placeLandmark(ServerLevel level,
                                      Building townHall,
                                      VillageBiomeStyle style,
                                      Village village) {
        // Place landmark opposite side of well from town hall
        BlockPos center = townHall.getShape().getOrigin()
                .offset(townHall.getShape().getWidth() / 2, 0,
                        -(townHall.getShape().getLength() / 2 + 6));
        BlockPos surface = findSurface(level, center);

        // Use village name hash to pick landmark type
        int landmarkType = Math.abs(village.getName().hashCode()) % 3;

        switch (landmarkType) {
            case 0 -> placeObelisk(level, surface, style);
            case 1 -> placeStatue(level, surface, style);
            case 2 -> placeNoticeBoard(level, surface, style);
        }
    }

    private static void placeObelisk(ServerLevel level,
                                     BlockPos base,
                                     VillageBiomeStyle style) {
        // 5-block tall tapering obelisk
        level.setBlock(base,
                style.stone.defaultBlockState(), 3);
        level.setBlock(base.above(),
                style.stone.defaultBlockState(), 3);
        level.setBlock(base.above(2),
                style.stone.defaultBlockState(), 3);
        level.setBlock(base.above(3),
                style.stoneSlab(), 3);
        level.setBlock(base.above(4),
                style.lanternState(), 3);

        // Decorative base
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                placeIfClear(level, base.offset(dx, 0, dz),
                        style.stoneSlab());
            }
        }
    }

    private static void placeStatue(ServerLevel level,
                                    BlockPos base,
                                    VillageBiomeStyle style) {
        // Stone pedestal with an armor stand on top
        level.setBlock(base,
                style.stone.defaultBlockState(), 3);
        level.setBlock(base.above(),
                style.stoneStairs != null
                        ? style.stoneStairs.defaultBlockState()
                        : style.stoneSlab(), 3);

        // Armor stand as statue
        net.minecraft.world.entity.decoration.ArmorStand statue =
                new net.minecraft.world.entity.decoration.ArmorStand(
                        net.minecraft.world.entity.EntityType.ARMOR_STAND,
                        level);
        statue.setPos(base.getX() + 0.5,
                base.getY() + 2, base.getZ() + 0.5);
        statue.setInvisible(false);
        statue.setNoGravity(true);

        // Give it a helmet based on biome style
        net.minecraft.world.item.ItemStack helmet = switch (style) {
            case DESERT  -> new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.GOLDEN_HELMET);
            case SNOWY   -> new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.LEATHER_HELMET);
            case JUNGLE  -> new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.CHAINMAIL_HELMET);
            default      -> new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.IRON_HELMET);
        };
        statue.setItemSlot(
                net.minecraft.world.entity.EquipmentSlot.HEAD, helmet);
        level.addFreshEntity(statue);

        // Decorative base
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                placeIfClear(level, base.offset(dx, 0, dz),
                        style.stoneSlab());
            }
        }
    }

    private static void placeNoticeBoard(ServerLevel level,
                                         BlockPos base,
                                         VillageBiomeStyle style) {
        // Two fence posts with a sign between them
        level.setBlock(base,
                style.fenceState(), 3);
        level.setBlock(base.east(),
                style.fenceState(), 3);
        level.setBlock(base.above(),
                style.fenceState(), 3);
        level.setBlock(base.east().above(),
                style.fenceState(), 3);

        // Oak sign on the front face
        placeIfClear(level, base.above().north(),
                Blocks.OAK_SIGN.defaultBlockState());

        // Flower pots at base
        placeIfClear(level, base.west(),
                Blocks.FLOWER_POT.defaultBlockState());
        placeIfClear(level, base.east(2),
                Blocks.FLOWER_POT.defaultBlockState());
    }

    // -------------------------------------------------------------------------
    // Flowers — now style-aware
    // -------------------------------------------------------------------------

    private static void scatterFlowers(ServerLevel level, Village village,
                                       VillageSavedData data,
                                       VillageBiomeStyle style,
                                       int attempts) {
        Optional<net.minecraft.world.phys.AABB> bounds =
                village.getBounds(data);
        if (bounds.isEmpty()) return;

        net.minecraft.world.phys.AABB bb = bounds.get().inflate(8);
        Random random = new Random(village.getName().hashCode());

        for (int i = 0; i < attempts; i++) {
            int x = (int)(bb.minX + random.nextDouble()
                    * (bb.maxX - bb.minX));
            int z = (int)(bb.minZ + random.nextDouble()
                    * (bb.maxZ - bb.minZ));

            BlockPos surface = findSurface(level,
                    new BlockPos(x, 64, z));

            BlockState below = level.getBlockState(surface.below());
            if (!below.is(Blocks.GRASS_BLOCK)
                    && !below.is(Blocks.SAND)
                    && !below.is(Blocks.MUD)) continue;
            if (!level.getBlockState(surface).isAir()) continue;
            if (data.getBuildingAt(surface).isPresent()) continue;

            // Use style flower
            level.setBlock(surface,
                    style.flowerState(), 3);
        }
    }

    // -------------------------------------------------------------------------
    // Unchanged helpers
    // -------------------------------------------------------------------------

    private static Optional<Building> findTownHall(
            List<Building> buildings) {
        return buildings.stream()
                .filter(b -> b.getType()
                        == Building.BuildingType.TOWN_HALL)
                .findFirst();
    }

    private static BlockPos findSurface(ServerLevel level,
                                        BlockPos pos) {
        int surfaceY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types
                        .WORLD_SURFACE,
                pos.getX(), pos.getZ());

        BlockPos surface = new BlockPos(
                pos.getX(), surfaceY, pos.getZ());

        // Skip water
        if (level.getBlockState(surface.below()).liquid()) {
            for (int y = surfaceY - 1; y > level.getMinY(); y--) {
                BlockPos check = new BlockPos(
                        pos.getX(), y, pos.getZ());
                if (level.getBlockState(check).isSolidRender()
                        && !level.getBlockState(check).liquid()) {
                    return check.above();
                }
            }
        }

        return surface;
    }

    private static void placeIfClear(ServerLevel level, BlockPos pos,
                                     BlockState state) {
        BlockPos below = pos.below();
        if (level.getBlockState(below).isSolidRender()
                && level.getBlockState(pos).isAir()) {
            level.setBlock(pos, state, 3);
        }
    }

    public static void smoothTerrainAroundBuilding(ServerLevel level,
                                                   Building building) {
        BlockPos min  = building.getShape().getMin();
        BlockPos max  = building.getShape().getMax();
        int originY   = building.getShape().getOrigin().getY();

        // Scale margin based on building size, minimum 24
        int buildingWidth  = building.getShape().getWidth();
        int buildingLength = building.getShape().getLength();
        int margin = Math.max(24,
                Math.max(buildingWidth, buildingLength) / 2);

        for (int x = min.getX() - margin;
             x <= max.getX() + margin; x++) {
            for (int z = min.getZ() - margin;
                 z <= max.getZ() + margin; z++) {

                // Skip inside building footprint with 1 block padding
                if (x >= min.getX() - 1 && x <= max.getX() + 1
                        && z >= min.getZ() - 1
                        && z <= max.getZ() + 1) continue;

                // Distance from nearest building edge
                int distX = Math.max(0, Math.max(
                        min.getX() - x, x - max.getX()));
                int distZ = Math.max(0, Math.max(
                        min.getZ() - z, z - max.getZ()));
                int dist  = Math.max(distX, distZ);

                int surfaceY = findSurfaceYWide(
                        level, x, z, originY);

                // Find the terrain Y at the nearest building edge
                // so we know what we're transitioning from
                int edgeX = Math.max(min.getX(),
                        Math.min(max.getX(), x));
                int edgeZ = Math.max(min.getZ(),
                        Math.min(max.getZ(), z));
                int edgeY = findSurfaceYWide(
                        level, edgeX, edgeZ, originY);

                // Only intervene if jump exceeds 3 blocks
                int jump = Math.abs(surfaceY - edgeY);
                if (jump <= 3) continue;

                // Calculate how many blocks we need to close
                // the gap — spread the difference over the
                // distance from the edge
                float t = Math.min(1f, (float) dist / jump);
                int targetY = (int)(edgeY
                        + t * (surfaceY - edgeY));

                // Detect biome-appropriate blocks
                Block surfaceBlock    = detectSurfaceBlock(
                        level, x, z, surfaceY);
                Block subsurfaceBlock = detectSubsurfaceBlock(
                        level, x, z, surfaceY);

                if (surfaceY < targetY) {
                    // Too low — fill conservatively
                    // Never fill more than the jump amount
                    int fillLimit = Math.min(targetY,
                            surfaceY + jump);
                    fillColumn(level, x, z, surfaceY,
                            fillLimit, surfaceBlock,
                            subsurfaceBlock);

                } else if (surfaceY > targetY) {
                    // Too high — carve conservatively
                    // Only carve natural terrain blocks,
                    // never stone, ores, or logs
                    int carveLimit = Math.max(targetY,
                            surfaceY - jump);
                    carveColumn(level, x, z,
                            surfaceY, carveLimit);
                }

                // Clean up surface cap
                ensureSurfaceCap(level, x, z,
                        Math.min(surfaceY, targetY),
                        surfaceBlock);
            }
        }
    }

    private static void fillColumn(ServerLevel level,
                                   int x, int z,
                                   int fromY, int toY,
                                   Block surfaceBlock,
                                   Block subsurfaceBlock) {
        for (int y = fromY + 1; y <= toY; y++) {
            BlockPos pos    = new BlockPos(x, y, z);
            BlockState existing = level.getBlockState(pos);

            // Never fill solid blocks or non-replaceable blocks
            if (!existing.isAir() && !existing.liquid()
                    && !existing.is(BlockTags.REPLACEABLE)) continue;

            boolean isTop = (y == toY);
            level.setBlock(pos, isTop
                    ? surfaceBlock.defaultBlockState()
                    : subsurfaceBlock.defaultBlockState(), 3);
        }
    }

    private static void carveColumn(ServerLevel level,
                                    int x, int z,
                                    int fromY, int toY) {
        for (int y = fromY; y > toY; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (canCarve(level.getBlockState(pos))) {
                level.setBlock(pos,
                        Blocks.AIR.defaultBlockState(), 3);
            } else {
                // Hit something uncarve-able — stop
                break;
            }
        }
    }

    private static void ensureSurfaceCap(ServerLevel level,
                                         int x, int z,
                                         int targetY,
                                         Block surfaceBlock) {
        BlockPos capPos   = new BlockPos(x, targetY, z);
        BlockPos aboveCap = new BlockPos(x, targetY + 1, z);

        // If the top block is subsurface material, replace with surface
        BlockState capState = level.getBlockState(capPos);
        if (capState.is(Blocks.DIRT)
                || capState.is(Blocks.SAND)
                || capState.is(Blocks.GRAVEL)) {
            level.setBlock(capPos,
                    surfaceBlock.defaultBlockState(), 3);
        }

        // Clear any stray fill above the cap
        BlockState aboveState = level.getBlockState(aboveCap);
        if (aboveState.is(Blocks.DIRT)
                || aboveState.is(Blocks.GRASS_BLOCK)
                || aboveState.is(Blocks.SAND)) {
            level.setBlock(aboveCap,
                    Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /**
     * Detects the appropriate surface block for the biome at
     * this column by sampling nearby existing surface blocks.
     */
    private static Block detectSurfaceBlock(ServerLevel level,
                                            int x, int z,
                                            int surfaceY) {
        // Sample the block just at the surface
        BlockState state = level.getBlockState(
                new BlockPos(x, surfaceY, z));

        // If we're in a desert/sand biome
        if (state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND)) {
            return state.getBlock();
        }

        // If we're in a swamp/mud biome
        if (state.is(Blocks.MUD)
                || state.is(Blocks.MUDDY_MANGROVE_ROOTS)) {
            return Blocks.MUD;
        }

        // Check biome via holder for more accurate detection
        var biomeHolder = level.getBiome(
                new BlockPos(x, surfaceY, z));
        String biomeId = biomeHolder.unwrapKey()
                .map(k -> k.registry().toString())
                .orElse("");

        if (biomeId.contains("desert")) return Blocks.SAND;
        if (biomeId.contains("red_sand")
                || biomeId.contains("badlands")) {
            return Blocks.RED_SAND;
        }
        if (biomeId.contains("swamp")
                || biomeId.contains("mangrove")) return Blocks.MUD;
        if (biomeId.contains("snowy")
                || biomeId.contains("frozen")) {
            return Blocks.SNOW_BLOCK;
        }
        if (biomeId.contains("mushroom")) {
            return Blocks.MYCELIUM;
        }

        // Default — grass
        return Blocks.GRASS_BLOCK;
    }

    private static Block detectSubsurfaceBlock(ServerLevel level,
                                               int x, int z,
                                               int surfaceY) {
        // Sample a few blocks below the surface
        for (int dy = 1; dy <= 3; dy++) {
            BlockState state = level.getBlockState(
                    new BlockPos(x, surfaceY - dy, z));
            if (state.is(Blocks.SAND)
                    || state.is(Blocks.RED_SAND)) {
                return state.getBlock();
            }
            if (state.is(Blocks.MUD)) return Blocks.MUD;
            if (state.is(Blocks.DIRT)
                    || state.is(Blocks.GRASS_BLOCK)) {
                return Blocks.DIRT;
            }
        }
        return Blocks.DIRT;
    }

    private static int findSurfaceYWide(ServerLevel level,
                                        int x, int z, int nearY) {
        int searchTop    = nearY + 16;
        int searchBottom = nearY - 32;
        for (int y = searchTop; y > searchBottom; y--) {
            BlockPos pos   = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            BlockState above = level.getBlockState(pos.above());
            if (state.isSolidRender() && !state.liquid()
                    && (above.isAir() || above.liquid()
                    || above.is(BlockTags.REPLACEABLE)
                    || above.is(BlockTags.FLOWERS))) {
                return y;
            }
        }
        return nearY - 1;
    }

    private static boolean canCarve(BlockState state) {
        if (state.isAir()) return true;
        if (state.liquid()) return true;
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.MUD)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SMALL_FLOWERS)
                || state.is(BlockTags.REPLACEABLE);
    }
}