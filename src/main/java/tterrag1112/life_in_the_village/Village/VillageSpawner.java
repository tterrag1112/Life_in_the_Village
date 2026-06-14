package tterrag1112.life_in_the_village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.Inhabitants.VillageInhabitantPopulator;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.BuildingVariant;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.NeighborColorIndex;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.TintPass;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.VariantRegistry;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.VariantResolver;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.VariantSelector;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.VillagePaletteResolver;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Decoration.VillageDecorator;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStallPlacer;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.Village.Roads.Planning.ConnectorPlanner;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Kingdom.Placement.DeepTerrainInspector;
import tterrag1112.life_in_the_village.Village.Planning.*;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.World.Atlas.AtlasSampler;
import tterrag1112.life_in_the_village.Village.Simulation.VillageSimEngine;

import java.util.*;

/**
 * Spawns a fully-realised village at an origin position.
 *
 * <h3>Changes in the primitive rewrite</h3>
 * <ul>
 *   <li>Capital branch removed — capitals will be reintroduced as a
 *       ShapeRecipe. There is one spawn path now.</li>
 *   <li>Rotation comes from the slot (set by layout primitives when
 *       they decided which road the building faces), not re-computed
 *       here.</li>
 *   <li>Footprint is pre-checked against an incremental
 *       {@link BuildingFootprint} before calling
 *       {@link BuildingPlacer#placeAndRegister} — fixes the ghost-building
 *       bug where a failed placement left NBT in the world.</li>
 *   <li>Building Y uses the slot's pre-committed pad Y — no
 *       re-querying {@code level.getHeight} at placement time, which
 *       was the source of Y mismatches.</li>
 * </ul>
 */
public class VillageSpawner {

    private static final int MIN_VILLAGE_DISTANCE = 128;
    // distSqr returns the squared Euclidean distance, so compare against the squared threshold.
    private static final long MIN_VILLAGE_DISTANCE_SQ =
            (long) MIN_VILLAGE_DISTANCE * MIN_VILLAGE_DISTANCE;

    /** Maximum offset (blocks) searched during local refinement. */
    private static final int LOCAL_REFINEMENT_RADIUS = 40;
    /** Grid step for the local refinement search. */
    private static final int LOCAL_SEARCH_STEP = 8;

    public static Optional<Village> spawnVillage(ServerLevel level,
                                                 BlockPos origin,
                                                 String villageType,
                                                 String villageName) {
        // ── Guards ──────────────────────────────────────────────────────────
        VillageTypeData typeData = VillageTypeRegistry.INSTANCE.getType(villageType);
        if (typeData == null) {
            System.out.println("VillageSpawner: unknown type '" + villageType + "'");
            return Optional.empty();
        }

        // Track A4 — V2 is now the only planner. The V1 spawn loop
        // that lived inline below was removed; V1 source files
        // (VillagePlanner, all 17 recipes, Adaptive package, the
        // matcher, ZoneRegistry, etc.) remain on disk pending Track
        // A1b deletion. The V2 adapter handles distance guard,
        // planning, placement, and the V1-equivalent downstream
        // pipeline (decoration, NPC population, trade routes, sim
        // baseline, guild bootstrap, history, initial laws).
        return tterrag1112.life_in_the_village.Village.Planning.V2
                .V2VillageSpawnerAdapter.spawn(level, origin, villageType, villageName);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Searches a grid of offsets within {@code radius} blocks of {@code origin}
     * for the position with the highest predicted terrain suitability (using
     * {@link DeepTerrainInspector} — noise-only, no chunk loading). Returns the
     * best candidate whose predicted type-aware suitability exceeds the threshold,
     * or {@code null} if none do.
     *
     * <p>Uses a 20-block inspection radius for speed — enough to rank candidates.
     * The type-aware formula is used so RIVERSIDE villages are scored on their
     * proximity to water rather than penalised for it.
     */
    private static BlockPos findBetterLocalSite(ServerLevel level,
                                                BlockPos origin,
                                                int radius,
                                                VillageTypeData typeData) {
        java.util.Set<tterrag1112.life_in_the_village.Village.VillageTag> tags = typeData.getTags();
        BlockPos best = null;
        float bestSuit = 0.05f;

        for (int dx = -radius; dx <= radius; dx += LOCAL_SEARCH_STEP) {
            for (int dz = -radius; dz <= radius; dz += LOCAL_SEARCH_STEP) {
                if (dx == 0 && dz == 0) continue; // origin already failed
                if ((long) dx * dx + (long) dz * dz > (long) radius * radius) continue;

                int bx = origin.getX() + dx;
                int bz = origin.getZ() + dz;
                int y  = AtlasSampler.sampleHeight(level, bx, bz);
                BlockPos candidate = new BlockPos(bx, y, bz);

                var result = DeepTerrainInspector.inspect(level, candidate, 20);
                float suit = tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile
                        .computeSuitability(result.flatRatio(), result.waterRatio(),
                                result.steepRatio(), tags);
                if (suit > bestSuit) {
                    bestSuit = suit;
                    best = candidate;
                }
            }
        }
        return best;
    }

    // =========================================================================
    // Unchanged helpers
    // =========================================================================



    private static void setupMerchantStalls(ServerLevel level, Village village,
                                            VillageSavedData data,
                                            Map<BuildingType, List<Building>> placedBuildingsAll,
                                            Random rng) {
        net.minecraft.world.phys.AABB villageBounds = village.getBounds(data)
                .map(b -> b.inflate(32)).orElse(null);
        if (villageBounds == null) return;

        List<TownspersonMob> merchants = level.getEntitiesOfClass(
                TownspersonMob.class, villageBounds,
                mob -> mob.getProfession() == Profession.MERCHANT
                        && mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false));

        for (TownspersonMob merchant : merchants) {
            Building market = merchant.getAssignedBuildingId()
                    .flatMap(data::getBuildingById)
                    .filter(b -> b.getType() == BuildingType.MARKET)
                    .orElse(null);
            if (market == null) continue;

            MarketStallPlacer.claimSlot(
                            level, market,
                            merchant.getUUID(),
                            tterrag1112.life_in_the_village.Village.Economy.Market
                                    .MarketStall.OwnerType.NPC,
                            Long.MAX_VALUE, data)
                    .ifPresent(stall -> {
                        data.addMarketStall(stall);
                        MarketStallPlacer.assignGoalIfNpc(level, stall);
                    });
        }
    }

    public static boolean isFarEnoughFromExistingVillages(ServerLevel level,
                                                          BlockPos candidate) {
        VillageSavedData data = VillageSavedData.get(level);
        for (Village v : data.getAllVillages()) {
            BlockPos anchor = v.getAnchorPos();
            if (anchor == null) continue;
            if (anchor.distSqr(candidate) < MIN_VILLAGE_DISTANCE_SQ) return false;
        }
        return true;
    }

    private static int deriveVillageLevel(VillageTypeData typeData) {
        int count = typeData.getStarterBuildings().size();
        if (count <= 3) return Math.max(1, count);
        if (count <= 6) return 3 + (count - 4);
        if (count <= 9) return 6 + (count - 7);
        return Math.min(10, 9 + (count - 10));
    }

    static BlockPos findSurface(ServerLevel level, BlockPos pos) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
        BlockPos surface = new BlockPos(pos.getX(), surfaceY, pos.getZ());
        BlockState state = level.getBlockState(surface.below());
        if (state.liquid()) {
            for (int y = surfaceY - 1; y > level.getMinY(); y--) {
                BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
                BlockState s = level.getBlockState(check);
                if (s.isSolidRender() && !s.liquid()) return check.above();
            }
        }
        return surface;
    }
}