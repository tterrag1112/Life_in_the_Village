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

        /**
         * Phase 6.3.3.k.3 — frost tolerance per crop. Drives the
         * yield penalty under frost conditions (see
         * {@code WeatherContext.frostYieldMultiplier}) and lets
         * skilled farmers avoid warm-season plantings in cold biomes.
         */
        public ColdTolerance coldTolerance() {
            return switch (this) {
                // Wheat / mixed grains: cold-hardy staples.
                case WHEAT, GRAIN, MIXED -> ColdTolerance.HARDY;
                // Root vegetables: some frost tolerance, damage at
                // sustained cold.
                case POTATOES, CARROTS, BEETROOT -> ColdTolerance.COOL_SEASON;
                // General vegetable rotation: less hardy than the
                // single-root crops.
                case VEGETABLE -> ColdTolerance.WARM_SEASON;
                // Trees survive frost; blossom / fruit do not.
                case ORCHARD -> ColdTolerance.TREE_FRUIT;
                // Pasture grass: hardy enough for the climates we'd
                // plant pens in.
                case PASTURE -> ColdTolerance.HARDY;
            };
        }

        /**
         * Phase 6.3.3.k.3 — cold-tolerance tiers. The yield penalty
         * under frost is a function of (tolerance, frost-active) and
         * is queried via {@code WeatherContext.frostYieldMultiplier}.
         */
        public enum ColdTolerance {
            /** Frost causes no yield penalty. */
            HARDY,
            /** Small penalty under frost — root crops survive light frost. */
            COOL_SEASON,
            /** Full penalty under frost — warm-season plantings fail. */
            WARM_SEASON,
            /** Trees survive but fruit / blossom yield drops. */
            TREE_FRUIT
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
                    // B2.5 — polygon bounds; legacy plots fall back to a
                    // rectangular polygon derived from origin+radius.
                    BlockPos.CODEC.listOf()
                            .optionalFieldOf("polygonVertices", List.of())
                            .forGetter(p -> p.polygon == null ? List.of()
                                    : p.polygon.vertices()),
                    // Phase 6.3.3.h.1 — soil quality / fallow state. All
                    // optional with sensible defaults so legacy saves load
                    // cleanly (pristine soil, empty history, no fallow).
                    Codec.FLOAT.optionalFieldOf("soilQuality", 1.0f)
                            .forGetter(FarmPlot::getSoilQuality),
                    Codec.STRING.listOf().optionalFieldOf("cropHistory", List.of())
                            .forGetter(FarmPlot::getCropHistory),
                    Codec.LONG.optionalFieldOf("fallowSinceTick", 0L)
                            .forGetter(FarmPlot::getFallowSinceTick),
                    Codec.LONG.optionalFieldOf("lastCompostedTick", 0L)
                            .forGetter(FarmPlot::getLastCompostedTick),
                    // Phase 6.3.3.h.6 — ANIMAL_PEN grazing tracking.
                    // Optional with default 0 (never grazed); ignored
                    // for CROP_FIELD plots.
                    Codec.LONG.optionalFieldOf("lastGrazedTick", 0L)
                            .forGetter(FarmPlot::getLastGrazedTick),
                    // Phase 6.3.3.k.4 — crop blight onset tick. 0 = healthy;
                    // > 0 means the plot is currently diseased and dates the
                    // onset for auto-fallow and yield-penalty math.
                    Codec.LONG.optionalFieldOf("blightSinceTick", 0L)
                            .forGetter(FarmPlot::getBlightSinceTick),
                    // Detour A — owning complex. Optional; pre-Detour-A
                    // plots load with no complex. (B2.5's sectorId has
                    // been retired; the old field is silently ignored
                    // on load — pre-Stage-5 saves were flagged as
                    // discardable.)
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .optionalFieldOf("complexId")
                            .forGetter(p -> Optional.ofNullable(p.complexId))
            ).apply(instance, (id, name, origin, radius, cropType, farmhouseId,
                              subtype, polygonVertices,
                              soilQuality, cropHistory, fallowSinceTick, lastCompostedTick,
                              lastGrazedTick, blightSinceTick, complexId) -> {
                FarmPlot plot = new FarmPlot(id, name, origin, radius, cropType, subtype);
                farmhouseId.ifPresent(plot::setFarmhouseId);
                if (polygonVertices != null && polygonVertices.size() >= 3) {
                    plot.setPolygon(new Polygon(polygonVertices));
                }
                plot.soilQuality = soilQuality;
                if (cropHistory != null && !cropHistory.isEmpty()) {
                    plot.cropHistory.addAll(cropHistory);
                }
                plot.fallowSinceTick = fallowSinceTick;
                plot.lastCompostedTick = lastCompostedTick;
                plot.lastGrazedTick = lastGrazedTick;
                plot.blightSinceTick = blightSinceTick;
                complexId.ifPresent(plot::setComplexId);
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
    /** B2.5 — terrain-following polygon. Null falls back to the
     *  legacy circle-via-origin/radius used by {@link #contains}. */
    private       Polygon   polygon;
    /** Detour A — owning complex. Null for pre-Detour-A plots or
     *  for plots not yet associated with a complex. */
    private       UUID      complexId;

    // ── Phase 6.3.3.h.1 — soil quality + fallow state ─────────────────
    /** 0.1 floor → 1.5 ceiling. Default 1.0 (pristine). Drives the
     *  yield multiplier in {@code FarmerBehavior.harvest()}. */
    private float soilQuality = 1.0f;
    /** Capped at {@link #MAX_HISTORY} (last 4 plantings). Stores
     *  {@link CropType#name()} strings for codec stability across
     *  enum additions/removals. */
    private final java.util.List<String> cropHistory = new java.util.ArrayList<>();
    /** Game tick when the plot entered fallow. 0 means not fallow. */
    private long fallowSinceTick;
    /** Game tick of the most recent composting. 0 means never. */
    private long lastCompostedTick;
    /** Phase 6.3.3.h.6 — ANIMAL_PEN: tick the pen was last grazed.
     *  Rotation picks the pen with the smallest (oldest) value so
     *  livestock spend time on the freshest pen. 0 = never grazed. */
    private long lastGrazedTick;
    /** Phase 6.3.3.k.4 — crop blight onset tick. 0 = healthy. Drives
     *  yield penalty, auto-fallow, and spread eligibility. */
    private long blightSinceTick;

    public static final int   MAX_HISTORY            = 4;
    public static final float SOIL_FLOOR             = 0.1f;
    public static final float SOIL_CEILING           = 1.5f;
    public static final float SOIL_FALLOW_ENTER      = 0.3f;
    public static final float SOIL_FALLOW_EXIT       = 0.7f;
    public static final float SOIL_DEC_PLANT         = 0.02f;

    // Phase 6.3.3.k.4 — blight tuning.
    /** Yield multiplier applied to a blighted plot's harvest output. */
    public static final float BLIGHT_YIELD_MULT      = 0.5f;
    /** Days a blight may persist before the plot is auto-fallowed
     *  (which clears the blight as a side effect). */
    public static final long  BLIGHT_AUTO_FALLOW_DAYS = 3L;
    /** Composting cure chance per composting event on a blighted plot. */
    public static final float BLIGHT_COMPOST_CURE_CHANCE = 0.30f;
    /** Base daily blight onset probability for a healthy plot. */
    public static final float BLIGHT_BASE_DAILY_RISK = 0.01f;
    /** Daily blight risk added when soil quality is below the
     *  poor-soil threshold. */
    public static final float BLIGHT_POOR_SOIL_RISK_ADD = 0.02f;
    /** Daily blight risk added when the last MONOCROP_STREAK
     *  entries of cropHistory are the same family (mono-cropping). */
    public static final float BLIGHT_MONOCROP_RISK_ADD = 0.02f;
    /** Daily blight risk added during drought conditions. */
    public static final float BLIGHT_DROUGHT_RISK_ADD = 0.01f;
    /** History entries that count toward mono-cropping detection. */
    public static final int   BLIGHT_MONOCROP_STREAK = 3;
    /** Soil quality below this is treated as poor (risk modifier). */
    public static final float BLIGHT_POOR_SOIL_THRESHOLD = 0.4f;
    /** Per-day spread chance to a same-farmhouse plot per blighted plot. */
    public static final float BLIGHT_SPREAD_DAILY_CHANCE = 0.05f;
    public static final float SOIL_DEC_SAME_FAMILY   = 0.05f;
    public static final float SOIL_RECOVER_PER_DAY   = 0.01f;
    public static final float SOIL_COMPOST_BOOST     = 0.15f;
    public static final long  DAY_TICKS              = 24000L;


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

    /**
     * Phase 6.3.3.s.2 — sibling to {@link #getFarmlandBlocks}.
     * Returns positions in the plot bounds where the surface block is
     * dirt or grass (i.e., tillable to farmland) AND the air block
     * above is clear (i.e., a crop could be planted after tilling).
     *
     * <p>Used by {@code FarmerBehavior.scanPlotForTasks} so the
     * farmer queues dirt-needs-tilling positions alongside the
     * normal replant queue. Without this scan, a plot with bare
     * dirt (newly generated, trampled, or otherwise reverted from
     * farmland) would never be worked — the existing scan only
     * sees positions where farmland already exists.</p>
     *
     * <p>Returns the surface (dirt/grass) position; the crop will
     * be planted at {@code surface.above()} after tilling.</p>
     */
    public java.util.List<BlockPos> getTillableSurfaces(
            net.minecraft.server.level.ServerLevel level) {
        java.util.List<BlockPos> result = new java.util.ArrayList<>();
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
                    boolean tillable = state.is(net.minecraft.tags.BlockTags.DIRT)
                            || state.getBlock() instanceof
                                net.minecraft.world.level.block.GrassBlock;
                    // FarmBlock extends Block (not in DIRT tag) — exclude
                    // it explicitly so this method doesn't overlap with
                    // getFarmlandBlocks.
                    if (state.getBlock() instanceof
                            net.minecraft.world.level.block.FarmBlock) break;
                    if (tillable) {
                        // Clear air above is required so a crop can be
                        // planted post-till; skip if blocked by snow,
                        // grass, sapling, etc.
                        if (level.getBlockState(check.above()).isAir()) {
                            result.add(check);
                        }
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

    public Polygon getPolygon() { return polygon; }
    public void setPolygon(Polygon polygon) { this.polygon = polygon; }

    public UUID getComplexId() { return complexId; }
    public void setComplexId(UUID complexId) { this.complexId = complexId; }

    // ── Phase 6.3.3.h.1 — soil / rotation / fallow API ────────────────

    public float getSoilQuality()             { return soilQuality; }
    public java.util.List<String> getCropHistory() {
        return java.util.Collections.unmodifiableList(cropHistory);
    }
    public long  getFallowSinceTick()         { return fallowSinceTick; }
    public long  getLastCompostedTick()       { return lastCompostedTick; }
    public long  getLastGrazedTick()          { return lastGrazedTick; }
    public boolean isFallow()                 { return fallowSinceTick > 0L; }

    // Phase 6.3.3.k.4 — blight accessors.
    public long    getBlightSinceTick()       { return blightSinceTick; }
    public boolean isBlighted()               { return blightSinceTick > 0L; }
    /** Marks the plot as blighted at {@code now}. No-op if already
     *  diseased — the onset tick stays at the original date so the
     *  auto-fallow clock keeps running. */
    public void setBlight(long now) {
        if (blightSinceTick == 0L) blightSinceTick = now;
    }
    /** Clears any blight. Called by composting cures, by the post-
     *  fallow exit path, and after a successful rotation replant. */
    public void clearBlight() {
        blightSinceTick = 0L;
    }

    /** Phase 6.3.3.h.6 — mark this pen as actively grazed at {@code now};
     *  applies a small soilQuality (grass) penalty per call. */
    public void onGrazed(long now) {
        lastGrazedTick = now;
        setSoilQuality(soilQuality - SOIL_DEC_PLANT);
    }

    /** Phase 6.3.3.h.6 — passive grass regrowth for ANIMAL_PEN plots
     *  that haven't been grazed recently. Same daily curve as fallow
     *  CROP_FIELDs but keyed off lastGrazedTick instead of
     *  fallowSinceTick (pens never enter fallow state). */
    public void tickPenRecovery(long now) {
        if (lastGrazedTick == 0L) return;
        long daysSince = (now - lastGrazedTick) / DAY_TICKS;
        if (daysSince <= 0L) return;
        setSoilQuality(soilQuality + SOIL_RECOVER_PER_DAY * (float) daysSince);
        lastGrazedTick = now;
    }

    public void setSoilQuality(float v) {
        this.soilQuality = Math.max(SOIL_FLOOR, Math.min(SOIL_CEILING, v));
    }

    /** Records a planting against this plot. Decrements soil quality
     *  (bigger hit on same-family repeat), trims cropHistory to
     *  {@link #MAX_HISTORY}, and may auto-enter fallow when quality
     *  falls below {@link #SOIL_FALLOW_ENTER}.
     *
     *  <p>Phase 6.3.3.k.4: a different-family replant on a blighted
     *  plot is itself the cure path — rotation breaks the disease
     *  cycle. Same-family replant leaves the blight in place.</p> */
    public void onPlanted(CropType justPlanted, long now) {
        boolean sameFamily = !cropHistory.isEmpty()
                && CropFamily.of(CropType.valueOf(cropHistory.get(cropHistory.size() - 1)))
                       == CropFamily.of(justPlanted);
        setSoilQuality(soilQuality - (sameFamily ? SOIL_DEC_SAME_FAMILY : SOIL_DEC_PLANT));
        cropHistory.add(justPlanted.name());
        while (cropHistory.size() > MAX_HISTORY) cropHistory.remove(0);
        if (soilQuality <= SOIL_FALLOW_ENTER && fallowSinceTick == 0L) {
            fallowSinceTick = now;
        }
        if (isBlighted() && !sameFamily) {
            clearBlight();
        }
    }

    /** Per-day recovery while fallow; auto-exits fallow once quality
     *  recovers above {@link #SOIL_FALLOW_EXIT}. */
    public void tickFallowRecovery(long now) {
        if (fallowSinceTick == 0L) return;
        long daysFallow = (now - fallowSinceTick) / DAY_TICKS;
        if (daysFallow <= 0L) return;
        setSoilQuality(soilQuality + SOIL_RECOVER_PER_DAY * (float) daysFallow);
        fallowSinceTick = now;
        if (soilQuality >= SOIL_FALLOW_EXIT) fallowSinceTick = 0L;
    }

    /** Records a composting event; boosts soil quality and stamps the
     *  cooldown clock. Phase 6.3.3.k.4: each composting on a blighted
     *  plot has a {@link #BLIGHT_COMPOST_CURE_CHANCE} probability of
     *  clearing the blight — the realistic cure is repeated composting
     *  over multiple days, not a single application. */
    public void onComposted(long now) {
        setSoilQuality(soilQuality + SOIL_COMPOST_BOOST);
        lastCompostedTick = now;
        if (soilQuality >= SOIL_FALLOW_EXIT) fallowSinceTick = 0L;
        if (isBlighted()
                && new java.util.Random(now ^ id.getMostSignificantBits())
                        .nextFloat() < BLIGHT_COMPOST_CURE_CHANCE) {
            clearBlight();
        }
    }

    /**
     * Phase 6.3.3.k.4 — daily blight onset roll. Returns true if the
     * plot transitioned from healthy to blighted on this roll. The
     * caller is responsible for spread (which depends on the wider
     * farm context this method doesn't have access to) and for
     * marking the saved data dirty.
     *
     * <p>Risk factors:</p>
     * <ul>
     *   <li>{@code BLIGHT_BASE_DAILY_RISK} baseline (1%/day)</li>
     *   <li>+{@code BLIGHT_POOR_SOIL_RISK_ADD} when soilQuality below threshold</li>
     *   <li>+{@code BLIGHT_MONOCROP_RISK_ADD} when last 3 history entries are same family</li>
     *   <li>+{@code BLIGHT_DROUGHT_RISK_ADD} when {@code droughtActive}</li>
     * </ul>
     */
    public boolean rollDailyBlight(long now, boolean droughtActive,
                                   java.util.Random rng) {
        if (isBlighted()) return false;
        if (subtype != PlotSubtype.CROP_FIELD) return false;
        float risk = BLIGHT_BASE_DAILY_RISK;
        if (soilQuality < BLIGHT_POOR_SOIL_THRESHOLD) {
            risk += BLIGHT_POOR_SOIL_RISK_ADD;
        }
        if (isMonocropStreak()) {
            risk += BLIGHT_MONOCROP_RISK_ADD;
        }
        if (droughtActive) {
            risk += BLIGHT_DROUGHT_RISK_ADD;
        }
        if (rng.nextFloat() < risk) {
            setBlight(now);
            return true;
        }
        return false;
    }

    /** Phase 6.3.3.k.4 — auto-fallow check. Returns true if the plot
     *  was just auto-fallowed by this call (blight persisted longer
     *  than {@link #BLIGHT_AUTO_FALLOW_DAYS}). Fallow clears the
     *  blight as a side effect since the next replant will rotate. */
    public boolean tickBlightAutoFallow(long now) {
        if (!isBlighted()) return false;
        long daysBlighted = (now - blightSinceTick) / DAY_TICKS;
        if (daysBlighted < BLIGHT_AUTO_FALLOW_DAYS) return false;
        if (fallowSinceTick == 0L) {
            fallowSinceTick = now;
        }
        clearBlight();
        return true;
    }

    private boolean isMonocropStreak() {
        if (cropHistory.size() < BLIGHT_MONOCROP_STREAK) return false;
        int n = cropHistory.size();
        CropFamily first = CropFamily.of(CropType.valueOf(
                cropHistory.get(n - 1)));
        for (int i = 2; i <= BLIGHT_MONOCROP_STREAK; i++) {
            CropFamily other = CropFamily.of(CropType.valueOf(
                    cropHistory.get(n - i)));
            if (other != first) return false;
        }
        return true;
    }

    /**
     * CropFamily classification (Phase 6.3.3.h.2). Same-family
     * consecutive planting triggers the larger soil hit; rotation
     * across families preserves quality.
     */
    public enum CropFamily {
        GRAINS, ROOTS, ORCHARD, PASTURE_GRASS;

        public static CropFamily of(CropType t) {
            return switch (t) {
                case WHEAT, GRAIN, MIXED          -> GRAINS;
                case CARROTS, POTATOES, BEETROOT,
                     VEGETABLE                    -> ROOTS;
                case ORCHARD                      -> ORCHARD;
                case PASTURE                      -> PASTURE_GRASS;
            };
        }

        /**
         * Phase 6.3.3.h.2 — picks a {@link CropType} from a family
         * different from the most-recent {@code history} entries.
         * Falls back to {@code current} when no rotation candidate
         * fits (e.g. ORCHARD / PASTURE plots that aren't part of the
         * GRAINS↔ROOTS swap loop).
         *
         * <p>Looks at the last 2 entries of {@code history}; the
         * suggested type's family must differ from both. Picks from a
         * fixed family→candidates table to avoid drift across enum
         * additions.
         */
        public static CropType suggestRotation(CropType current,
                                               java.util.List<String> history,
                                               net.minecraft.util.RandomSource random) {
            CropFamily currentFamily = of(current);
            // Rotation only meaningful between GRAINS and ROOTS. Other
            // families (ORCHARD, PASTURE_GRASS) stay put.
            if (currentFamily != GRAINS && currentFamily != ROOTS) return current;
            java.util.Set<CropFamily> recent = new java.util.HashSet<>();
            int n = Math.min(2, history.size());
            for (int i = history.size() - n; i < history.size(); i++) {
                try { recent.add(of(CropType.valueOf(history.get(i)))); }
                catch (IllegalArgumentException ignore) { /* dropped enum value */ }
            }
            CropType[] grainOptions = { CropType.WHEAT, CropType.GRAIN };
            CropType[] rootOptions  = { CropType.CARROTS, CropType.POTATOES,
                                         CropType.BEETROOT, CropType.VEGETABLE };
            CropType[] candidates = (currentFamily == GRAINS) ? rootOptions : grainOptions;
            CropFamily targetFamily = (currentFamily == GRAINS) ? ROOTS : GRAINS;
            if (recent.contains(targetFamily)) {
                // Rotating away from the same family we'd land in would
                // be a wash — fall through to current and accept the
                // same-family soil hit (player still needs to fertilize).
                return current;
            }
            return candidates[random.nextInt(candidates.length)];
        }
    }
}