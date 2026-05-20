// src/main/java/tterrag1112/life_in_the_village/Village/Buildings/FarmPlot.java
package tterrag1112.life_in_the_village.Village.Buildings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FarmPlot {

    // =========================================================================
    // CropType — expanded for biome-aware selection
    // =========================================================================

    public enum CropType {

        /**
         * Wheat monoculture — the historical staple.
         * Feeds MILLER; surplus sold as bread at markets.
         * Best grown in plains and temperate biomes.
         */
        WHEAT,

        /**
         * Carrot-dominant plot.
         * High nutrition per block, drought-tolerant.
         * Preferred in desert / mesa biomes.
         */
        CARROTS,

        /**
         * Potato-dominant plot.
         * Highest caloric density; ideal for cold climates.
         * Preferred in snowy / taiga biomes.
         */
        POTATOES,

        /**
         * Beetroot plot.
         * Produces dye ingredients alongside food.
         */
        BEETROOT,

        /**
         * Mixed rotation — wheat, carrots, potatoes in strips.
         * Default fallback for temperate biomes when no specific
         * crop is indicated by biome context.
         */
        MIXED,

        /**
         * Grain-dominated rotation: wheat + some beetroot.
         * Supplies the MILLER for bread production.
         * Favoured by farming villages with a mill building.
         */
        GRAIN,

        /**
         * Vegetable rotation: carrots, potatoes, beetroot in strips.
         * Highest raw nutrition output; no mill required.
         * Favoured when FOOD need is CRITICAL or MILLER is absent.
         */
        VEGETABLE,

        /**
         * Orchard stand-in: jungle / forest biomes.
         * Represented by bone-meal-grown saplings / apples.
         * FarmerGoal uses OAK_SAPLING + bone meal during the
         * replant phase, then harvests apple drops.
         * Purely flavour in the current implementation — produces
         * wheat/carrot as actual drops with a visual difference.
         */
        ORCHARD,

        /**
         * Pasture — cleared, fenced grassland for livestock.
         * No crop rows are planted. FarmerGoal skips the replant
         * phase for PASTURE plots and instead leaves grass.
         * Future: pairs with ANIMAL_KEEPER profession.
         * Currently produces hay bales and occasional leather.
         */
        PASTURE;

        // -------------------------------------------------------------------------
        // Seed / crop identifiers
        // -------------------------------------------------------------------------

        /**
         * Returns the primary seed item for this crop type.
         * MIXED and GRAIN default to wheat seeds.
         * VEGETABLE defaults to carrot.
         * ORCHARD / PASTURE use wheat seeds as a placeholder.
         */
        public Identifier getSeedItem() {
            return switch (this) {
                case WHEAT, GRAIN, MIXED -> Identifier.withDefaultNamespace("wheat_seeds");
                case CARROTS             -> Identifier.withDefaultNamespace("carrot");
                case POTATOES            -> Identifier.withDefaultNamespace("potato");
                case BEETROOT            -> Identifier.withDefaultNamespace("beetroot_seeds");
                case VEGETABLE           -> Identifier.withDefaultNamespace("carrot");
                case ORCHARD             -> Identifier.withDefaultNamespace("wheat_seeds");
                case PASTURE             -> Identifier.withDefaultNamespace("wheat_seeds");
            };
        }

        /**
         * Returns the primary crop block for this type.
         * Used by FarmerGoal.getCropBlock() during the replant phase.
         */
        public Identifier getCropBlock() {
            return switch (this) {
                case WHEAT, GRAIN, MIXED -> Identifier.withDefaultNamespace("wheat");
                case CARROTS             -> Identifier.withDefaultNamespace("carrots");
                case POTATOES            -> Identifier.withDefaultNamespace("potatoes");
                case BEETROOT            -> Identifier.withDefaultNamespace("beetroot");
                case VEGETABLE           -> Identifier.withDefaultNamespace("carrots");
                case ORCHARD             -> Identifier.withDefaultNamespace("wheat");
                case PASTURE             -> Identifier.withDefaultNamespace("wheat");
            };
        }
        // -------------------------------------------------------------------------
        // Resolved block / item helpers
        // -------------------------------------------------------------------------

        /**
         * Returns the actual {@link net.minecraft.world.level.block.Block}
         * this crop type plants. Resolves the {@link #getCropBlock()} Identifier
         * through the block registry.
         *
         * <p>Returns {@code null} for {@link #PASTURE} — pasture plots are not
         * planted and callers must handle the null case.</p>
         */
        public net.minecraft.world.level.block.Block resolveCropBlock() {
            if (this == PASTURE) return null;
            return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .get(getCropBlock())
                    .map(h -> h.value())
                    .orElse(net.minecraft.world.level.block.Blocks.WHEAT);
        }

        /**
         * Returns the actual {@link net.minecraft.world.item.Item} used as
         * seed for this crop type. Resolves the {@link #getSeedItem()}
         * Identifier through the item registry.
         */
        public net.minecraft.world.item.Item resolveSeedItem() {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(getSeedItem())
                    .map(h -> h.value())
                    .orElse(net.minecraft.world.item.Items.WHEAT_SEEDS);
        }

        /**
         * Returns true if this plot type grows plantable crops.
         * PASTURE returns false — no crop rows.
         */
        public boolean isCropPlot() {
            return this != PASTURE;
        }

        /**
         * Returns the nutrition value per unit harvest relative to wheat (1.0).
         * Used by VillageNeedsCalculator when counting food nutrition.
         */
        public float nutritionMultiplier() {
            return switch (this) {
                case WHEAT, GRAIN  -> 1.0f;
                case MIXED         -> 1.0f;
                case CARROTS       -> 0.9f;  // 3 hunger vs wheat's 2.5 — close
                case POTATOES      -> 1.2f;  // higher saturation
                case BEETROOT      -> 0.7f;  // lower nutrition per unit
                case VEGETABLE     -> 1.1f;  // average of carrot/potato
                case ORCHARD       -> 0.8f;  // apple — moderate
                case PASTURE       -> 0.6f;  // meat/leather — indirect food
            };
        }
    }

    // =========================================================================
    // Codec
    // =========================================================================

    public static final Codec<FarmPlot> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("id").forGetter(FarmPlot::getId),
                    Codec.STRING
                            .fieldOf("name").forGetter(FarmPlot::getName),
                    BlockPos.CODEC
                            .fieldOf("origin").forGetter(FarmPlot::getOrigin),
                    Codec.INT
                            .fieldOf("radius").forGetter(FarmPlot::getRadius),
                    Codec.STRING.xmap(CropType::valueOf, CropType::name)
                            .fieldOf("cropType").forGetter(FarmPlot::getCropType),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .optionalFieldOf("farmhouseId")
                            .forGetter(p -> Optional.ofNullable(p.farmhouseId)),
                    Codec.STRING.xmap(PlotSubtype::valueOf, PlotSubtype::name)
                            .optionalFieldOf("subtype", PlotSubtype.CROP_FIELD)
                            .forGetter(FarmPlot::getSubtype),
                    // B2.5 — sector membership; legacy plots load with no sector.
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .optionalFieldOf("sectorId")
                            .forGetter(p -> Optional.ofNullable(p.sectorId)),
                    // B2.5 — polygon bounds; legacy plots fall back to a
                    // rectangular polygon derived from origin+radius.
                    BlockPos.CODEC.listOf()
                            .optionalFieldOf("polygonVertices", List.of())
                            .forGetter(p -> p.polygon == null ? List.of()
                                    : p.polygon.vertices())
            ).apply(instance, (id, name, origin, radius, cropType, farmhouseId,
                              subtype, sectorId, polygonVertices) -> {
                FarmPlot plot = new FarmPlot(id, name, origin, radius, cropType, subtype);
                farmhouseId.ifPresent(plot::setFarmhouseId);
                sectorId.ifPresent(plot::setSectorId);
                if (polygonVertices != null && polygonVertices.size() >= 3) {
                    plot.setPolygon(new Polygon(polygonVertices));
                }
                return plot;
            })
    );

    // =========================================================================
    // Fields
    // =========================================================================

    private final UUID      id;
    private       String    name;
    private       BlockPos  origin;
    private       int       radius;
    private       CropType  cropType;
    private       UUID      farmhouseId; // nullable until assigned
    private PlotSubtype subtype;
    /** B2.5 — owning sector. Null for legacy plots placed before the
     *  sector planner shipped. */
    private       UUID      sectorId;
    /** B2.5 — terrain-following polygon. Null falls back to the
     *  legacy circle-via-origin/radius used by {@link #contains}. */
    private       Polygon   polygon;


    public FarmPlot(UUID id, String name, BlockPos origin,
                    int radius, CropType cropType, PlotSubtype subtype) {
        this.id       = id;
        this.name     = name;
        this.origin   = origin;
        this.radius   = radius;
        this.cropType = cropType;
        this.subtype = subtype;
    }

    // =========================================================================
    // Spatial helpers
    // =========================================================================

    public boolean contains(BlockPos pos) {
        // B2.5 — polygon takes precedence; legacy circle fallback
        // applies for plots without polygon bounds set.
        if (polygon != null && !polygon.vertices().isEmpty()) {
            return Polygon.contains(polygon, pos);
        }
        double dx = pos.getX() - origin.getX();
        double dz = pos.getZ() - origin.getZ();
        return dx * dx + dz * dz <= (double) radius * radius;
    }

    public java.util.List<BlockPos> getFarmlandBlocks(
            net.minecraft.server.level.ServerLevel level) {
        java.util.List<BlockPos> result = new java.util.ArrayList<>();
        // B2.5 — when polygon is set, walk the polygon's bbox so the
        // search covers vertices that may sit past the legacy
        // origin±radius envelope. Polygon-less plots fall back to the
        // legacy circle-in-square walk below.
        int minX, maxX, minZ, maxZ;
        if (polygon != null && !polygon.vertices().isEmpty()) {
            int xmn = Integer.MAX_VALUE, xmx = Integer.MIN_VALUE;
            int zmn = Integer.MAX_VALUE, zmx = Integer.MIN_VALUE;
            for (BlockPos v : polygon.vertices()) {
                xmn = Math.min(xmn, v.getX()); xmx = Math.max(xmx, v.getX());
                zmn = Math.min(zmn, v.getZ()); zmx = Math.max(zmx, v.getZ());
            }
            minX = xmn; maxX = xmx; minZ = zmn; maxZ = zmx;
        } else {
            minX = origin.getX() - radius;
            maxX = origin.getX() + radius;
            minZ = origin.getZ() - radius;
            maxZ = origin.getZ() + radius;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos surface = new BlockPos(x, origin.getY(), z);
                if (!contains(surface)) continue;
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos check = surface.offset(0, dy, 0);
                    net.minecraft.world.level.block.state.BlockState state =
                            level.getBlockState(check);
                    if (state.getBlock() instanceof
                            net.minecraft.world.level.block.FarmBlock) {
                        result.add(check);
                        break;
                    }
                }
            }
        }
        return result;
    }

    public enum PlotSubtype {
        /** Traditional crop field with farmland and crops */
        CROP_FIELD,

        /** Fenced grass area for livestock - no farmland */
        ANIMAL_PEN;

        public boolean isCropPlot() {
            return this == CROP_FIELD;
        }
    }

    // =========================================================================
    // Getters / setters
    // =========================================================================

    public UUID     getId()           { return id; }
    public String   getName()         { return name; }
    public BlockPos getOrigin()       { return origin; }
    public int      getRadius()       { return radius; }
    public CropType getCropType()     { return cropType; }
    public UUID     getFarmhouseId()  { return farmhouseId; }

    /**
     * In-memory crop quality tracker for this plot. Created lazily on
     * first access. Not persisted in v1 — quality state resets when the
     * server restarts. Full persistence belongs in a later phase
     * because {@link tterrag1112.life_in_the_village.Village.Agriculture
     * .CropQualityTracker} doesn't ship a top-level codec yet.
     */
    private transient tterrag1112.life_in_the_village.Village.Agriculture.CropQualityTracker
            qualityTracker;

    public tterrag1112.life_in_the_village.Village.Agriculture.CropQualityTracker
            getOrCreateQualityTracker() {
        if (qualityTracker == null) {
            qualityTracker = new tterrag1112.life_in_the_village.Village
                    .Agriculture.CropQualityTracker(id);
        }
        return qualityTracker;
    }

    public void setName(String name)           { this.name = name; }
    public void setOrigin(BlockPos origin)     { this.origin = origin; }
    public void setRadius(int radius)          { this.radius = radius; }
    public void setCropType(CropType cropType) { this.cropType = cropType; }
    public void setFarmhouseId(UUID id)        { this.farmhouseId = id; }

    public boolean isAssigned() { return farmhouseId != null; }
    public PlotSubtype getSubtype() { return subtype; }
    public void setSubtype(PlotSubtype subtype) { this.subtype = subtype; }

    public UUID getSectorId() { return sectorId; }
    public void setSectorId(UUID sectorId) { this.sectorId = sectorId; }

    public Polygon getPolygon() { return polygon; }
    public void setPolygon(Polygon polygon) { this.polygon = polygon; }
}