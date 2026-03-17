package tterrag1112.life_in_the_village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Village.Decoration.VillageDecorator;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Entities.ModEntities;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;

import java.util.*;

public class VillageSpawner {

    private static final int MIN_VILLAGE_DISTANCE = 50;
    // Padding between buildings in blocks
    private static final int BUILDING_PADDING     = 6;
    // Max random jitter applied to hint offsets
    private static final int JITTER               = 10;
    // Max attempts to find a non-overlapping position
    private static final int MAX_PLACEMENT_ATTEMPTS = 20;

    public static Optional<Village> spawnVillage(ServerLevel level,
                                                 BlockPos origin,
                                                 String villageType,
                                                 String villageName) {
        VillageTypeData typeData = VillageTypeRegistry.INSTANCE
                .getType(villageType);
        if (typeData == null) {
            System.out.println("VillageSpawner: unknown village type "
                    + villageType);
            return Optional.empty();
        }

        VillageSavedData data = VillageSavedData.get(level);

        // Check minimum distance from existing villages
        for (Village existing : data.getAllVillages()) {
            Optional<net.minecraft.world.phys.AABB> bounds =
                    existing.getBounds(data);
            if (bounds.isPresent()) {
                double cx = (bounds.get().minX + bounds.get().maxX) / 2;
                double cz = (bounds.get().minZ + bounds.get().maxZ) / 2;
                double dist = Math.sqrt(
                        Math.pow(origin.getX() - cx, 2)
                                + Math.pow(origin.getZ() - cz, 2));
                if (dist < MIN_VILLAGE_DISTANCE) {
                    System.out.println("VillageSpawner: too close to '"
                            + existing.getName()
                            + "' (" + (int) dist + " blocks)");
                    return Optional.empty();
                }
            }
        }

        Village village = new Village(villageName);
        data.addVillage(village);

        // -------------------------------------------------------
        // Phase 1 — Generate candidate positions for all buildings
        // -------------------------------------------------------
        Random rng = new Random(origin.hashCode()
                + villageName.hashCode());

        // Track placed footprints for overlap checking
        List<PlacedFootprint> footprints = new ArrayList<>();
        Map<Building.BuildingType, BlockPos> resolvedPositions
                = new LinkedHashMap<>();

        for (VillageTypeData.StarterBuilding sb
                : typeData.getStarterBuildings()) {
            Building.BuildingType buildingType;
            try {
                buildingType = Building.BuildingType
                        .valueOf(sb.type());
            } catch (IllegalArgumentException e) {
                System.out.println("VillageSpawner: unknown type "
                        + sb.type());
                continue;
            }

            BlockPos resolved = resolvePosition(
                    level, origin, sb, footprints, rng);

            if (resolved == null) {
                System.out.println("VillageSpawner: could not place "
                        + sb.type() + " — no valid position found");
                continue;
            }

            // Estimate footprint size from structure name
            // We use a conservative estimate since we don't know
            // the actual size until we load the template
            int estimatedSize = estimateStructureSize(sb.structure());
            footprints.add(new PlacedFootprint(
                    resolved, estimatedSize, estimatedSize));
            resolvedPositions.put(buildingType, resolved);
        }

        // -------------------------------------------------------
        // Phase 2 — Place buildings at resolved positions
        // -------------------------------------------------------
        Map<Building.BuildingType, Building> placedBuildings
                = new HashMap<>();

        int buildingIndex = 0;
        for (VillageTypeData.StarterBuilding sb
                : typeData.getStarterBuildings()) {
            Building.BuildingType buildingType;
            try {
                buildingType = Building.BuildingType
                        .valueOf(sb.type());
            } catch (IllegalArgumentException e) {
                continue;
            }

            BlockPos buildPos = resolvedPositions.get(buildingType);
            if (buildPos == null) continue;

            try {
                Identifier structId = Identifier
                        .fromNamespaceAndPath(
                                Life_in_the_village.MODID,
                                sb.structure());

                String buildingName = villageName + "_"
                        + sb.type().toLowerCase() + "_"
                        + (placedBuildings.values().stream()
                        .filter(b -> b.getType()
                                == buildingType)
                        .count() + 1);

                Rotation rotation = randomRotation(
                        level.getRandom());

                Optional<Building> placed = BuildingPlacer
                        .placeAndRegister(level, buildPos,
                                structId, buildingName,
                                buildingType, rotation);

                if (placed.isEmpty()) {
                    System.out.println("VillageSpawner: "
                            + "placeAndRegister returned empty for "
                            + sb.type());
                    continue;
                }

                village.addBuilding(placed.get());
                placedBuildings.put(buildingType, placed.get());

                // Update footprint with actual building size
                int actualW = placed.get().getShape().getWidth();
                int actualL = placed.get().getShape().getLength();
                footprints.set(buildingIndex,
                        new PlacedFootprint(buildPos,
                                actualW, actualL));

                data.setDirty();
                System.out.println("VillageSpawner: placed "
                        + sb.type() + " at " + buildPos);

            } catch (Exception e) {
                System.out.println("VillageSpawner: failed to place "
                        + sb.type() + " — " + e.getMessage());
            }
            buildingIndex++;
        }

        // -------------------------------------------------------
        // Phase 3 — Stock items
        // -------------------------------------------------------
        for (VillageTypeData.StarterItem si
                : typeData.getStarterItems()) {
            try {
                Building.BuildingType targetType =
                        Building.BuildingType.valueOf(
                                si.buildingType());
                Building targetBuilding =
                        placedBuildings.get(targetType);
                if (targetBuilding == null) continue;

                Identifier itemId = Identifier
                        .parse(si.item());
                var itemHolder = BuiltInRegistries.ITEM
                        .get(itemId);
                if (itemHolder.isEmpty()) continue;

                ItemStack stack = new ItemStack(
                        itemHolder.get().value(), si.count());
                BuildingStorageAccess.storeItem(
                        level, targetBuilding, stack);

            } catch (Exception e) {
                System.out.println("VillageSpawner: failed to stock "
                        + si.item() + " — " + e.getMessage());
            }
        }

        // -------------------------------------------------------
        // Phase 4 — Spawn NPCs
        // -------------------------------------------------------
        for (VillageTypeData.StarterNpc sn
                : typeData.getStarterNpcs()) {
            try {
                Building.BuildingType buildingType =
                        Building.BuildingType.valueOf(
                                sn.buildingType());
                Building assignedBuilding =
                        placedBuildings.get(buildingType);
                if (assignedBuilding == null) continue;

                TownspersonMob npc = ModEntities.TOWNSPERSON
                        .get().create(level,
                                EntitySpawnReason.NATURAL);
                if (npc == null) continue;

                // Spawn at a random point inside the building
                BlockPos spawnPos = randomPosInsideBuilding(
                        level, assignedBuilding, rng);

                npc.setPos(spawnPos.getX() + 0.5,
                        spawnPos.getY(),
                        spawnPos.getZ() + 0.5);

                npc.finalizeSpawn(level,
                        level.getCurrentDifficultyAt(spawnPos),
                        EntitySpawnReason.NATURAL, null);

                TownspersonMob.Profession profession =
                        TownspersonMob.Profession.valueOf(
                                sn.profession());
                npc.setProfession(profession);
                npc.setAssignedBuildingId(
                        assignedBuilding.getId());
                npc.setAssignedVillageName(villageName);
                npc.setFamilyRole(FamilyRole.valueOf(
                        sn.familyRole()));
                npc.setHouseId(
                        buildingType == Building.BuildingType.HOUSE
                                || buildingType
                                == Building.BuildingType.FARMHOUSE
                                ? assignedBuilding.getId() : null);

                if (profession
                        == TownspersonMob.Profession.MERCHANT) {
                    npc.receive(CurrencyValue.ofGold(2));
                } else {
                    npc.receive(CurrencyValue.ofSilver(5));
                }

                level.addFreshEntity(npc);
                System.out.println("VillageSpawner: spawned "
                        + sn.profession() + " '"
                        + npc.getNpcName() + "' in "
                        + sn.buildingType());

            } catch (Exception e) {
                System.out.println(
                        "VillageSpawner: failed to spawn NPC — "
                                + e.getMessage());
            }
        }

        // -------------------------------------------------------
        // Phase 5 — Decorate
        // -------------------------------------------------------
        VillageDecorator.decorateVillage(level, village, data);

        TradeRouteManager.establishRoutes(level, village, data);


        System.out.println("VillageSpawner: village '" + villageName
                + "' spawned with "
                + village.getBuildingIds().size()
                + " buildings");
        return Optional.of(village);
    }

    // -------------------------------------------------------------------------
    // Position resolution
    // -------------------------------------------------------------------------

    /**
     * Finds a valid non-overlapping position for a building.
     * Uses the JSON hint offset as a starting point, then applies
     * random jitter and checks for overlap with already-placed
     * footprints. Falls back to spiral search if jitter fails.
     */
    private static BlockPos resolvePosition(ServerLevel level,
                                            BlockPos origin,
                                            VillageTypeData.StarterBuilding sb,
                                            List<PlacedFootprint> footprints,
                                            Random rng) {
        int[] hint = sb.offset();
        int estSize = estimateStructureSize(sb.structure());

        // Generate a pool of candidate positions
        List<BlockPos> candidates = new ArrayList<>();

        // Always include the exact hint position first
        BlockPos exactHint = findSurface(level,
                origin.offset(hint[0], 0, hint[2]));
        if (!overlapsAny(exactHint, estSize, estSize, footprints)) {
            candidates.add(exactHint);
        }

        // Generate jittered candidates
        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS;
             attempt++) {
            int jitterX = rng.nextInt(JITTER * 2) - JITTER;
            int jitterZ = rng.nextInt(JITTER * 2) - JITTER;

            BlockPos candidate = findSurface(level,
                    origin.offset(hint[0] + jitterX, 0,
                            hint[2] + jitterZ));

            if (!overlapsAny(candidate, estSize, estSize,
                    footprints)) {
                candidates.add(candidate);
            }
        }

        if (!candidates.isEmpty()) {
            // Pick the flattest candidate
            return candidates.stream()
                    .min(Comparator.comparingInt(c ->
                            flatnessScore(level, c, estSize)))
                    .orElse(candidates.get(0));
        }

        // All jittered positions overlapped — fall back to spiral
        // but still score by flatness among spiral candidates
        List<BlockPos> spiralCandidates = spiralSearchAll(
                level, exactHint, footprints, estSize);

        if (!spiralCandidates.isEmpty()) {
            return spiralCandidates.stream()
                    .min(Comparator.comparingInt(c ->
                            flatnessScore(level, c, estSize)))
                    .orElse(spiralCandidates.get(0));
        }

        return null;
    }

    private static List<BlockPos> spiralSearchAll(ServerLevel level,
                                                  BlockPos center,
                                                  List<PlacedFootprint> footprints,
                                                  int size) {
        List<BlockPos> results = new ArrayList<>();
        int step = size + BUILDING_PADDING;

        for (int r = 1; r <= 10; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r
                            && Math.abs(dz) != r) continue;

                    BlockPos candidate = findSurface(level,
                            center.offset(dx * step, 0,
                                    dz * step));

                    if (!overlapsAny(candidate, size, size,
                            footprints)) {
                        results.add(candidate);
                    }
                }
            }

            // Stop spiral once we have enough candidates to
            // compare — no need to search the whole world
            if (results.size() >= 8) break;
        }

        return results;
    }

    /**
     * Searches outward in a spiral from the center position
     * until a non-overlapping spot is found.
     */


    private static boolean overlapsAny(BlockPos pos,
                                       int width, int length,
                                       List<PlacedFootprint> footprints) {
        for (PlacedFootprint fp : footprints) {
            if (overlaps(pos, width, length,
                    fp.origin, fp.width, fp.length)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlaps(BlockPos a, int aw, int al,
                                    BlockPos b, int bw, int bl) {
        int padding = BUILDING_PADDING;
        return a.getX() - padding < b.getX() + bw + padding
                && a.getX() + aw + padding > b.getX() - padding
                && a.getZ() - padding < b.getZ() + bl + padding
                && a.getZ() + al + padding > b.getZ() - padding;
    }

    // -------------------------------------------------------------------------
    // NPC spawn position
    // -------------------------------------------------------------------------

    private static BlockPos randomPosInsideBuilding(
            ServerLevel level, Building building, Random rng) {
        BlockPos min = building.getShape().getMin();
        int w = building.getShape().getWidth();
        int l = building.getShape().getLength();

        // Try up to 10 random positions inside the building
        for (int attempt = 0; attempt < 10; attempt++) {
            int x = min.getX() + 1 + rng.nextInt(
                    Math.max(1, w - 2));
            int z = min.getZ() + 1 + rng.nextInt(
                    Math.max(1, l - 2));

            // Find floor Y inside building
            int floorY = building.getShape().getOrigin().getY();
            BlockPos candidate = new BlockPos(x, floorY + 1, z);

            // Check there's air to stand in
            if (level.getBlockState(candidate).isAir()
                    && level.getBlockState(
                    candidate.above()).isAir()) {
                return candidate;
            }
        }

        // Fallback — center of building at floor level
        return building.getShape().getOrigin().offset(
                w / 2, 1, l / 2);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Estimates building size from structure name.
     * Used before the template is loaded to check overlap.
     * Conservative estimates — real size replaces this after placing.
     */
    private static int estimateStructureSize(String structureName) {
        if (structureName.contains("town_hall")) return 16;
        if (structureName.contains("guild")) return 14;
        if (structureName.contains("stockpile")) return 12;
        if (structureName.contains("farmhouse")) return 14;
        if (structureName.contains("farm")) return 20;
        if (structureName.contains("inn")) return 16;
        if (structureName.contains("blacksmith")) return 10;
        if (structureName.contains("market")) return 12;
        if (structureName.contains("house")) return 10;
        return 12; // default estimate
    }

    private static BlockPos findSurface(ServerLevel level,
                                        BlockPos pos) {
        // Use WORLD_SURFACE heightmap — this finds the highest
        // non-air block exposed to the sky, ignoring caves entirely
        int surfaceY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types
                        .WORLD_SURFACE,
                pos.getX(), pos.getZ());

        // WORLD_SURFACE returns the Y of the first air block
        // above the surface, so the solid block is one below
        BlockPos surface = new BlockPos(pos.getX(), surfaceY,
                pos.getZ());

        // Skip water surfaces — find solid ground instead
        BlockState state = level.getBlockState(surface.below());
        if (state.liquid()) {
            // Search downward for solid non-liquid ground
            for (int y = surfaceY - 1; y > level.getMinY(); y--) {
                BlockPos check = new BlockPos(
                        pos.getX(), y, pos.getZ());
                BlockState s = level.getBlockState(check);
                if (s.isSolidRender() && !s.liquid()) {
                    return check.above();
                }
            }
        }

        return surface;
    }

    public static boolean isFarEnoughFromExistingVillages(
            ServerLevel level, BlockPos pos) {
        VillageSavedData data = VillageSavedData.get(level);
        for (Village village : data.getAllVillages()) {
            Optional<net.minecraft.world.phys.AABB> bounds =
                    village.getBounds(data);
            if (bounds.isPresent()) {
                double cx = (bounds.get().minX
                        + bounds.get().maxX) / 2;
                double cz = (bounds.get().minZ
                        + bounds.get().maxZ) / 2;
                double dist = Math.sqrt(
                        Math.pow(pos.getX() - cx, 2)
                                + Math.pow(pos.getZ() - cz, 2));
                if (dist < MIN_VILLAGE_DISTANCE) return false;
            }
        }
        return true;
    }

    private static Rotation randomRotation(
            net.minecraft.util.RandomSource random) {
        Rotation[] rotations = Rotation.values();
        return rotations[random.nextInt(rotations.length)];
    }

    /**
     * Scores a position by terrain flatness.
     * Lower score = flatter = better.
     * Samples a grid of points across the building footprint
     * and measures the max height variation.
     */
    private static int flatnessScore(ServerLevel level,
                                     BlockPos pos, int size) {
        int samples = 4; // sample every 4 blocks
        int minY    = Integer.MAX_VALUE;
        int maxY    = Integer.MIN_VALUE;

        for (int dx = 0; dx <= size; dx += samples) {
            for (int dz = 0; dz <= size; dz += samples) {
                int y = findSurfaceYAt(level,
                        pos.getX() + dx,
                        pos.getZ() + dz,
                        pos.getY());
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }

        return maxY - minY; // height variance across footprint
    }

    private static int findSurfaceYAt(ServerLevel level,
                                      int x, int z, int nearY) {
        int y = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types
                        .WORLD_SURFACE, x, z);

        // Skip water
        BlockState state = level.getBlockState(
                new BlockPos(x, y - 1, z));
        if (state.liquid()) {
            for (int sy = y - 1; sy > level.getMinY(); sy--) {
                BlockState s = level.getBlockState(
                        new BlockPos(x, sy, z));
                if (s.isSolidRender() && !s.liquid()) {
                    return sy + 1;
                }
            }
        }

        return y;
    }

    // -------------------------------------------------------------------------
    // Helper record for overlap checking
    // -------------------------------------------------------------------------

    private record PlacedFootprint(BlockPos origin,
                                   int width, int length) {}
}