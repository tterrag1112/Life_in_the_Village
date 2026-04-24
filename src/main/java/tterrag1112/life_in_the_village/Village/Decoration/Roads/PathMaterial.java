package tterrag1112.life_in_the_village.Village.Decoration.Roads;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Defines a road surface as a weighted mix of blocks.
 *
 * <h3>Resolution</h3>
 * The primary entry point is {@link #resolve} which applies biome, culture,
 * maintenance, tier, and season axes in priority order. The legacy
 * {@link #forBiomeAndTier} delegate is kept for backward compatibility.
 *
 * <h3>Culture palettes</h3>
 * <ul>
 *   <li><b>default</b> — biome-tier base unchanged (dirt/gravel/cobble/stone brick)</li>
 *   <li><b>imperial</b> — stone_bricks + polished_andesite core, stone_slab drainage edge</li>
 *   <li><b>highland</b> — cobblestone + mossy + gravel core, cobblestone edge</li>
 *   <li><b>nordic</b> — smooth_stone + planks core, spruce_slab edge</li>
 *   <li>Unknown/null → default</li>
 * </ul>
 *
 * <h3>Maintenance decay</h3>
 * Maintenance &lt; 40: mossy variants in core, coarse_dirt weight on edge.
 * Maintenance &lt; 15: gravel replaces some cobble, grass_block on edge.
 *
 * <h3>Old Realm (GREAT_ROAD)</h3>
 * {@link #oldRealm()} returns a fixed "eternally weathered" palette that ignores
 * all other axes. Great roads never decay (invariant 3).
 */
public class PathMaterial {

    /**
     * A single weighted block entry.
     */
    public record WeightedBlock(
            Block block,
            float weight
    ) {
        public static final Codec<WeightedBlock> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("block")
                        .forGetter(wb -> BuiltInRegistries.BLOCK.getKey(wb.block)),
                Codec.FLOAT.fieldOf("weight")
                        .forGetter(WeightedBlock::weight)
        ).apply(i, (id, w) -> new WeightedBlock(
                BuiltInRegistries.BLOCK.get(id).map(h -> h.value()).orElse(Blocks.COBBLESTONE), w)));

        public BlockState state() {
            return block.defaultBlockState();
        }
    }

    // =========================================================================
    // Fields
    // =========================================================================

    private final String name;
    private final List<WeightedBlock> coreBlocks;
    private final List<WeightedBlock> edgeBlocks;
    private final float totalCoreWeight;
    private final float totalEdgeWeight;

    // =========================================================================
    // Constructor
    // =========================================================================

    public PathMaterial(String name,
                        List<WeightedBlock> coreBlocks,
                        List<WeightedBlock> edgeBlocks) {
        this.name = name;
        this.coreBlocks = List.copyOf(coreBlocks);
        this.edgeBlocks = List.copyOf(edgeBlocks);
        this.totalCoreWeight = (float) coreBlocks.stream()
                .mapToDouble(WeightedBlock::weight).sum();
        this.totalEdgeWeight = (float) edgeBlocks.stream()
                .mapToDouble(WeightedBlock::weight).sum();
    }

    // =========================================================================
    // Sampling
    // =========================================================================

    public BlockState sampleCore(RandomSource random) {
        return sample(coreBlocks, totalCoreWeight, random);
    }

    public BlockState sampleEdge(RandomSource random) {
        return sample(edgeBlocks, totalEdgeWeight, random);
    }

    public Block primaryBlock() {
        return coreBlocks.stream()
                .max(Comparator.comparingDouble(WeightedBlock::weight))
                .map(WeightedBlock::block)
                .orElse(Blocks.COBBLESTONE);
    }

    public String getName() { return name; }
    public List<WeightedBlock> getCoreBlocks() { return coreBlocks; }
    public List<WeightedBlock> getEdgeBlocks() { return edgeBlocks; }

    // =========================================================================
    // Internal sampling
    // =========================================================================

    private static BlockState sample(List<WeightedBlock> blocks,
                                     float totalWeight,
                                     RandomSource random) {
        if (blocks.isEmpty()) return Blocks.DIRT_PATH.defaultBlockState();
        if (blocks.size() == 1) return blocks.get(0).state();

        float roll = random.nextFloat() * totalWeight;
        float cumulative = 0;
        for (WeightedBlock wb : blocks) {
            cumulative += wb.weight;
            if (roll < cumulative) return wb.state();
        }
        return blocks.get(blocks.size() - 1).state();
    }

    // =========================================================================
    // Full resolution entry point
    // =========================================================================

    /**
     * Resolves the PathMaterial for a road position, applying all axes in order:
     * biome → culture → maintenance → season.
     *
     * @param biome       biome style at the road location
     * @param culture     culture string ("imperial", "highland", "nordic", "default", or null)
     * @param maintenance 0–100; 100 = perfect, 0 = abandoned
     * @param tier        road width tier (determines base material class)
     * @param season      current season, or null for no seasonal overlay
     */
    public static PathMaterial resolve(VillageBiomeStyle biome,
                                       @Nullable String culture,
                                       int maintenance,
                                       RoadShape.RoadTier tier,
                                       @Nullable SeasonTracker.Season season) {
        // 1. Culture overlay (supersedes biome for imperial/highland/nordic)
        PathMaterial base;
        String eff = (culture == null || culture.isEmpty()) ? "default" : culture;
        base = switch (eff) {
            case "imperial" -> imperial();
            case "highland" -> highland();
            case "nordic"   -> nordic(biome);
            default         -> baseForRoadTier(biome, tier);
        };

        // 2. Maintenance decay (skipped for Great Roads — callers must not pass those here)
        if (maintenance < 40) {
            base = applyMaintenanceDecay(base, maintenance);
        }

        // 3. Seasonal overlay
        if (season == SeasonTracker.Season.WINTER
                && isDirtTier(tier)) {
            base = applyWinterDirtOverlay(base);
        } else if (season == SeasonTracker.Season.AUTUMN) {
            base = applyAutumnEdge(base);
        }

        return base;
    }

    /**
     * Backward-compatible delegate. Equivalent to
     * {@code resolve(biome, "default", 100, RoadShape.fromPathTier(tier), null)}.
     */
    public static PathMaterial forBiomeAndTier(VillageBiomeStyle biome,
                                               VillagePath.PathTier tier) {
        if (biome == VillageBiomeStyle.DESERT) return desert();
        if (biome == VillageBiomeStyle.SNOWY) return snowy();

        return switch (tier) {
            case DIRT        -> dirt();
            case GRAVEL      -> gravel();
            case COBBLESTONE -> cobblestone();
            case STONE_BRICK -> stoneBrick();
        };
    }

    // =========================================================================
    // Base palette by road tier (default culture)
    // =========================================================================

    private static PathMaterial baseForRoadTier(VillageBiomeStyle biome,
                                                 RoadShape.RoadTier tier) {
        if (biome == VillageBiomeStyle.DESERT) return desert();
        if (biome == VillageBiomeStyle.SNOWY)  return snowy();
        return switch (tier) {
            case FOOTPATH, VILLAGE_PATH -> dirt();
            case VILLAGE_ROAD           -> cobblestone();
            case TOWN_ROAD, CAPITAL_ROAD -> stoneBrick();
        };
    }

    private static boolean isDirtTier(RoadShape.RoadTier tier) {
        return tier == RoadShape.RoadTier.FOOTPATH || tier == RoadShape.RoadTier.VILLAGE_PATH;
    }

    // =========================================================================
    // Maintenance decay modifier
    // =========================================================================

    private static PathMaterial applyMaintenanceDecay(PathMaterial base, int maintenance) {
        List<WeightedBlock> newCore = new ArrayList<>(base.coreBlocks.size());
        for (WeightedBlock wb : base.coreBlocks) {
            Block decayed = decayBlock(wb.block, maintenance);
            newCore.add(new WeightedBlock(decayed, wb.weight));
        }

        List<WeightedBlock> newEdge = new ArrayList<>(base.edgeBlocks);
        // maintenance < 40: add coarse_dirt weight to edge (20%)
        float edgeDirtWeight = base.totalEdgeWeight * 0.20f;
        newEdge.add(new WeightedBlock(Blocks.COARSE_DIRT, edgeDirtWeight));

        // maintenance < 15: further deterioration — grass on edge
        if (maintenance < 15) {
            float grassWeight = base.totalEdgeWeight * 0.15f;
            newEdge.add(new WeightedBlock(Blocks.GRASS_BLOCK, grassWeight));
        }

        return new PathMaterial(base.name + "_worn", newCore, newEdge);
    }

    /** Substitutes stone-family blocks for their weathered equivalents. */
    private static Block decayBlock(Block b, int maintenance) {
        if (b == Blocks.COBBLESTONE) {
            if (maintenance < 15) return Blocks.GRAVEL;
            return Blocks.MOSSY_COBBLESTONE;
        }
        if (b == Blocks.STONE_BRICKS || b == Blocks.CHISELED_STONE_BRICKS) {
            return Blocks.MOSSY_STONE_BRICKS;
        }
        return b;
    }

    // =========================================================================
    // Seasonal overlays
    // =========================================================================

    private static PathMaterial applyWinterDirtOverlay(PathMaterial base) {
        // Swap 40% of core blocks to snow-related variants on dirt-tier roads
        List<WeightedBlock> newCore = new ArrayList<>(base.coreBlocks.size() + 2);
        float totalW = base.totalCoreWeight;
        float snowWeight = totalW * 0.4f;
        float remaining = totalW * 0.6f;
        float scale = remaining / totalW;
        for (WeightedBlock wb : base.coreBlocks) {
            newCore.add(new WeightedBlock(wb.block, wb.weight * scale));
        }
        newCore.add(new WeightedBlock(Blocks.SNOW_BLOCK, snowWeight * 0.5f));
        newCore.add(new WeightedBlock(Blocks.GRASS_BLOCK, snowWeight * 0.5f));
        return new PathMaterial(base.name + "_winter", newCore, base.edgeBlocks);
    }

    private static PathMaterial applyAutumnEdge(PathMaterial base) {
        List<WeightedBlock> newEdge = new ArrayList<>(base.edgeBlocks);
        float leafWeight = base.totalEdgeWeight * 0.05f;
        newEdge.add(new WeightedBlock(Blocks.OAK_LEAVES, leafWeight));
        return new PathMaterial(base.name + "_autumn", base.coreBlocks, newEdge);
    }

    // =========================================================================
    // Built-in presets — biome base palettes
    // =========================================================================

    /** Dirt path — bare earth with occasional grass. */
    public static PathMaterial dirt() {
        return new PathMaterial("dirt",
                List.of(
                        new WeightedBlock(Blocks.DIRT_PATH, 0.7f),
                        new WeightedBlock(Blocks.COARSE_DIRT, 0.2f),
                        new WeightedBlock(Blocks.DIRT, 0.1f)
                ),
                List.of(
                        new WeightedBlock(Blocks.COARSE_DIRT, 0.5f),
                        new WeightedBlock(Blocks.DIRT, 0.3f),
                        new WeightedBlock(Blocks.GRASS_BLOCK, 0.2f)
                ));
    }

    /** Gravel path — packed gravel with stone chips. */
    public static PathMaterial gravel() {
        return new PathMaterial("gravel",
                List.of(
                        new WeightedBlock(Blocks.GRAVEL, 0.55f),
                        new WeightedBlock(Blocks.DIRT_PATH, 0.25f),
                        new WeightedBlock(Blocks.COARSE_DIRT, 0.2f)
                ),
                List.of(
                        new WeightedBlock(Blocks.COARSE_DIRT, 0.4f),
                        new WeightedBlock(Blocks.DIRT_PATH, 0.3f),
                        new WeightedBlock(Blocks.GRASS_BLOCK, 0.3f)
                ));
    }

    /** Cobblestone road — mixed stone surface. */
    public static PathMaterial cobblestone() {
        return new PathMaterial("cobblestone",
                List.of(
                        new WeightedBlock(Blocks.STONE, 0.55f),
                        new WeightedBlock(Blocks.ANDESITE, 0.20f),
                        new WeightedBlock(Blocks.COBBLESTONE, 0.15f),
                        new WeightedBlock(Blocks.STONE_BRICKS, 0.10f)
                ),
                List.of(
                        new WeightedBlock(Blocks.ANDESITE, 0.4f),
                        new WeightedBlock(Blocks.STONE, 0.3f),
                        new WeightedBlock(Blocks.COBBLESTONE, 0.3f)
                ));
    }

    /** Stone brick road — refined surface for wealthy villages. */
    public static PathMaterial stoneBrick() {
        return new PathMaterial("stone_brick",
                List.of(
                        new WeightedBlock(Blocks.STONE_BRICKS, 0.50f),
                        new WeightedBlock(Blocks.STONE, 0.25f),
                        new WeightedBlock(Blocks.POLISHED_ANDESITE, 0.15f),
                        new WeightedBlock(Blocks.COBBLESTONE, 0.10f)
                ),
                List.of(
                        new WeightedBlock(Blocks.COBBLESTONE, 0.4f),
                        new WeightedBlock(Blocks.STONE, 0.3f),
                        new WeightedBlock(Blocks.GRAVEL, 0.3f)
                ));
    }

    /** Desert path — sand and sandstone mix. */
    public static PathMaterial desert() {
        return new PathMaterial("desert",
                List.of(
                        new WeightedBlock(Blocks.SMOOTH_SANDSTONE, 0.50f),
                        new WeightedBlock(Blocks.SANDSTONE, 0.30f),
                        new WeightedBlock(Blocks.SAND, 0.20f)
                ),
                List.of(
                        new WeightedBlock(Blocks.SAND, 0.6f),
                        new WeightedBlock(Blocks.SANDSTONE, 0.4f)
                ));
    }

    /** Snowy path — packed ice and stone. */
    public static PathMaterial snowy() {
        return new PathMaterial("snowy",
                List.of(
                        new WeightedBlock(Blocks.STONE_BRICKS, 0.40f),
                        new WeightedBlock(Blocks.COBBLESTONE, 0.30f),
                        new WeightedBlock(Blocks.GRAVEL, 0.20f),
                        new WeightedBlock(Blocks.PACKED_ICE, 0.10f)
                ),
                List.of(
                        new WeightedBlock(Blocks.GRAVEL, 0.4f),
                        new WeightedBlock(Blocks.SNOW_BLOCK, 0.3f),
                        new WeightedBlock(Blocks.COARSE_DIRT, 0.3f)
                ));
    }

    // =========================================================================
    // Culture-specific palettes
    // =========================================================================

    /**
     * Imperial culture: formal paved road imitating Old Realm engineering.
     * Straight, stone-brick dominant with polished accents. Drainage edge.
     */
    public static PathMaterial imperial() {
        return new PathMaterial("imperial",
                List.of(
                        new WeightedBlock(Blocks.STONE_BRICKS, 0.50f),
                        new WeightedBlock(Blocks.COBBLESTONE, 0.30f),
                        new WeightedBlock(Blocks.CHISELED_STONE_BRICKS, 0.10f),
                        new WeightedBlock(Blocks.POLISHED_ANDESITE, 0.10f)
                ),
                List.of(
                        new WeightedBlock(Blocks.STONE_BRICK_SLAB, 0.6f),
                        new WeightedBlock(Blocks.STONE_SLAB, 0.4f)
                ));
    }

    /**
     * Highland culture: rugged terrain-adapted road.
     * Cobblestone/mossy/gravel mix. Retaining walls placed architecturally.
     */
    public static PathMaterial highland() {
        return new PathMaterial("highland",
                List.of(
                        new WeightedBlock(Blocks.COBBLESTONE, 0.50f),
                        new WeightedBlock(Blocks.MOSSY_COBBLESTONE, 0.20f),
                        new WeightedBlock(Blocks.STONE, 0.20f),
                        new WeightedBlock(Blocks.GRAVEL, 0.10f)
                ),
                List.of(
                        new WeightedBlock(Blocks.GRAVEL, 0.5f),
                        new WeightedBlock(Blocks.COBBLESTONE, 0.5f)
                ));
    }

    /**
     * Nordic culture: mixed stone-and-plank road.
     * In swamp/river biomes, planks dominate (corduroy road style).
     *
     * @param biome biome context; swamp and jungle shift plank weight higher
     */
    public static PathMaterial nordic(VillageBiomeStyle biome) {
        boolean wetBiome = biome == VillageBiomeStyle.SWAMP;
        if (wetBiome) {
            return new PathMaterial("nordic_wet",
                    List.of(
                            new WeightedBlock(Blocks.SPRUCE_PLANKS, 0.60f),
                            new WeightedBlock(Blocks.OAK_PLANKS, 0.20f),
                            new WeightedBlock(Blocks.SMOOTH_STONE, 0.10f),
                            new WeightedBlock(Blocks.STONE_BRICKS, 0.10f)
                    ),
                    List.of(
                            new WeightedBlock(Blocks.SPRUCE_SLAB, 0.6f),
                            new WeightedBlock(Blocks.GRAVEL, 0.4f)
                    ));
        }
        return new PathMaterial("nordic",
                List.of(
                        new WeightedBlock(Blocks.SMOOTH_STONE, 0.30f),
                        new WeightedBlock(Blocks.STONE_BRICKS, 0.20f),
                        new WeightedBlock(Blocks.OAK_PLANKS, 0.30f),
                        new WeightedBlock(Blocks.SPRUCE_PLANKS, 0.20f)
                ),
                List.of(
                        new WeightedBlock(Blocks.SPRUCE_SLAB, 0.5f),
                        new WeightedBlock(Blocks.GRAVEL, 0.5f)
                ));
    }

    // =========================================================================
    // CulturePalette → PathMaterial conversion (Phase 7g)
    // =========================================================================

    /**
     * Builds a PathMaterial from a {@link CulturePalette}'s surface block lists.
     *
     * <p>Weights: primary 60 % total, accent 30 %, rare 10 %, distributed evenly
     * within each list. The edge ring reuses the accent list so the outer blend
     * reads as a softer version of the primary. Empty lists are tolerated —
     * they simply contribute no weight.
     *
     * <p>This returns a raw palette-based material; call
     * {@link #applyOverlays} to layer maintenance decay and seasonal overlays
     * on top, mirroring {@link #resolve}'s downstream passes.
     */
    public static PathMaterial fromCulturePalette(CulturePalette palette) {
        List<WeightedBlock> core = new ArrayList<>();
        addWeighted(core, palette.surfacePrimary(), 0.60f);
        addWeighted(core, palette.surfaceAccent(),  0.30f);
        addWeighted(core, palette.surfaceRare(),    0.10f);

        List<WeightedBlock> edge = new ArrayList<>();
        // Edge blend: accent blocks, plus a dash of primary to read as the same road.
        addWeighted(edge, palette.surfaceAccent(),  0.70f);
        addWeighted(edge, palette.surfacePrimary(), 0.30f);

        if (core.isEmpty()) core.add(new WeightedBlock(Blocks.COBBLESTONE, 1.0f));
        if (edge.isEmpty()) edge.add(new WeightedBlock(Blocks.GRAVEL, 1.0f));

        return new PathMaterial("palette_" + palette.cultureId(), core, edge);
    }

    private static void addWeighted(List<WeightedBlock> out, List<Block> blocks, float totalWeight) {
        if (blocks == null || blocks.isEmpty() || totalWeight <= 0) return;
        float perBlock = totalWeight / blocks.size();
        for (Block b : blocks) out.add(new WeightedBlock(b, perBlock));
    }

    /**
     * Applies maintenance-decay and seasonal overlays to a base PathMaterial
     * built from a palette. {@code tier} is used to decide whether winter snow
     * applies (dirt tiers only).
     */
    public static PathMaterial applyOverlays(PathMaterial base,
                                              int maintenance,
                                              RoadShape.RoadTier tier,
                                              @Nullable SeasonTracker.Season season) {
        PathMaterial result = base;
        if (maintenance < 40) result = applyMaintenanceDecay(result, maintenance);
        if (season == SeasonTracker.Season.WINTER && isDirtTier(tier)) {
            result = applyWinterDirtOverlay(result);
        } else if (season == SeasonTracker.Season.AUTUMN) {
            result = applyAutumnEdge(result);
        }
        return result;
    }

    /**
     * Old Realm great-road palette: ancient, eternally weathered, durable.
     * This palette is applied to all GREAT_ROAD edges regardless of culture or
     * maintenance (invariant 3 — great roads never decay).
     */
    public static PathMaterial oldRealm() {
        return new PathMaterial("old_realm",
                List.of(
                        new WeightedBlock(Blocks.MOSSY_COBBLESTONE, 0.35f),
                        new WeightedBlock(Blocks.CRACKED_STONE_BRICKS, 0.30f),
                        new WeightedBlock(Blocks.STONE_BRICKS, 0.20f),
                        new WeightedBlock(Blocks.MOSSY_STONE_BRICKS, 0.10f),
                        new WeightedBlock(Blocks.COBBLESTONE, 0.05f)
                ),
                List.of(
                        new WeightedBlock(Blocks.STONE_BRICK_SLAB, 0.45f),
                        new WeightedBlock(Blocks.STONE_SLAB, 0.40f),
                        new WeightedBlock(Blocks.GRASS_BLOCK, 0.15f)
                ));
    }
}
