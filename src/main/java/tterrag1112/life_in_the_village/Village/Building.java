// src/main/java/tterrag1112/life_in_the_village/Village/Building.java
package tterrag1112.life_in_the_village.Village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingCondition;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.BuildingVariant;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;

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

    // ── New field: tracks maintenance/decay state ────────────────────────────
    private BuildingCondition condition = BuildingCondition.NEW;

    // ── P0a-08: variant + color fields ──────────────────────────────────────
    /**
     * Doc 15 — which authored variant was placed at this site. Defaults
     * to {@code type.name().toLowerCase()} (the type-default variant)
     * for any building constructed without an explicit variant id; the
     * matcher writes the chosen variant via {@link #setVariantId} at
     * placement time.
     */
    private String variantId;

    // ── Phase 6.4.3.2 — amenity cache (transient, not persisted) ────────────
    // Lazy scan + TTL refresh keeps the cost negligible while accommodating
    // mid-game block changes (players adding a furnace etc.). Refresh happens
    // on read when AMENITY_CACHE_TTL ticks have elapsed since the prior scan.
    private transient EnumSet<AmenityType> cachedAmenities;
    private transient long cachedAmenitiesTick = Long.MIN_VALUE;
    private static final long AMENITY_CACHE_TTL = 24000L; // ~one game day

    /** Doc 15 — primary tint colour. Null = no tint (current behaviour). */
    @Nullable private DyeColor primaryColor = null;
    /** Doc 15 — accent tint colour. */
    @Nullable private DyeColor accentColor  = null;
    /** Doc 15 — roof tint colour. */
    @Nullable private DyeColor roofColor    = null;

    /**
     * Religion Rework R3e-2 — patron faith (religion id) for a religious
     * building. A SHRINE carries its minority faith here; a TEMPLE/CHAPEL leaves
     * it unset and the canonical resolver
     * ({@code tterrag1112.life_in_the_village.Npc.Religion.BuildingFaith}) derives
     * the village dominant. {@code null} on every non-religious building and on
     * pre-feature saves (the codec field is {@code optionalFieldOf}).
     */
    @Nullable private String patronFaith = null;

    /** Track A2 — the back-of-house TOFT plot (rear garden/yard strip) the
     *  residential arranger reserved behind this dwelling, as an XZ AABB
     *  {@code [minX, minZ, maxX, maxZ]}. Null for every building without a
     *  toft (non-STREET_ROW homes, civic/market/farm buildings, and any home
     *  whose toft was dropped on a tight block). The HOMESTEAD system reads
     *  this to give a resident a private garden region to tend. */
    @Nullable private Polygon.AABB toft = null;

    // =========================================================================
    // UUID codec helper
    // =========================================================================

    /** Track A2 — codec for the toft plot AABB: a 4-int XZ rectangle. */
    public static final Codec<Polygon.AABB> TOFT_AABB_CODEC =
            Codec.INT.listOf().comapFlatMap(
                    list -> list.size() == 4
                            ? com.mojang.serialization.DataResult.success(
                                    new Polygon.AABB(list.get(0), list.get(1),
                                            list.get(2), list.get(3)))
                            : com.mojang.serialization.DataResult.error(
                                    () -> "toft AABB needs 4 ints"),
                    a -> List.of(a.minX(), a.minZ(), a.maxX(), a.maxZ()));

    public static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(
            UUID::fromString, UUID::toString
    );

    // =========================================================================
    // Codec
    // =========================================================================

    /**
     * DyeColor codec. Stored as the lowercase enum name so JSON
     * inspection in saves stays readable. Optional fields use the
     * {@link Codec#STRING} → {@link DyeColor} mapping wrapped in
     * {@code optionalFieldOf} for forward-compat.
     */
    public static final Codec<DyeColor> DYE_COLOR_CODEC = Codec.STRING.xmap(
            s -> DyeColor.valueOf(s.toUpperCase()),
            d -> d.name().toLowerCase()
    );

    public static final Codec<Building> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUID_CODEC.fieldOf("id")
                            .forGetter(Building::getId),
                    Codec.STRING.fieldOf("name")
                            .forGetter(Building::getName),
                    Codec.STRING.xmap(BuildingType::valueOf, BuildingType::name)
                            .fieldOf("type")
                            .forGetter(Building::getType),
                    BuildingShape.SHAPE_CODEC.fieldOf("shape")
                            .forGetter(Building::getShape),
                    Identifier.CODEC.fieldOf("structureId")
                            .forGetter(Building::getStructureId),
                    Codec.INT.fieldOf("buildingLevel")
                            .forGetter(Building::getLevel),
                    Codec.STRING.xmap(
                                    Rotation::valueOf,
                                    Rotation::name)
                            .fieldOf("rotation")
                            .forGetter(Building::getRotation),
                    // BuildingCondition — optional so existing saves load with WEATHERED default
                    BuildingCondition.CODEC
                            .optionalFieldOf("condition", BuildingCondition.WEATHERED)
                            .forGetter(Building::getCondition),
                    // P0a-08 additions — all optional so pre-P0a-08
                    // saves load cleanly. variantId defaults to the
                    // type-default variant (folder named identically
                    // to the type); the three colour fields default
                    // to absent (no tint).
                    Codec.STRING.optionalFieldOf("variantId")
                            .forGetter(b -> Optional.ofNullable(b.variantId)),
                    DYE_COLOR_CODEC.optionalFieldOf("primaryColor")
                            .forGetter(b -> Optional.ofNullable(b.primaryColor)),
                    DYE_COLOR_CODEC.optionalFieldOf("accentColor")
                            .forGetter(b -> Optional.ofNullable(b.accentColor)),
                    DYE_COLOR_CODEC.optionalFieldOf("roofColor")
                            .forGetter(b -> Optional.ofNullable(b.roofColor)),
                    // R3e-2 — patron faith (optional; absent on pre-feature
                    // saves and every non-religious building). 13th field — well
                    // under the 16-field RecordCodecBuilder cap.
                    Codec.STRING.optionalFieldOf("patronFaith")
                            .forGetter(b -> Optional.ofNullable(b.patronFaith)),
                    // Track A2 — back-of-house toft plot (optional; absent on
                    // every building without one). 14th field — under the
                    // 16-field RecordCodecBuilder cap.
                    TOFT_AABB_CODEC.optionalFieldOf("toft")
                            .forGetter(b -> Optional.ofNullable(b.toft))
            ).apply(instance, Building::fromCodec)
    );

    private static Building fromCodec(UUID id, String name, BuildingType type,
                                      BuildingShape shape, Identifier structureId,
                                      int buildingLevel,
                                      Rotation rotation,
                                      BuildingCondition condition,
                                      Optional<String> variantId,
                                      Optional<DyeColor> primaryColor,
                                      Optional<DyeColor> accentColor,
                                      Optional<DyeColor> roofColor,
                                      Optional<String> patronFaith,
                                      Optional<Polygon.AABB> toft) {
        Building b = new Building(id, name, type, shape, structureId, buildingLevel, rotation);
        b.condition = condition;
        b.variantId = variantId.orElse(BuildingVariant.defaultVariantId(type));
        b.primaryColor = primaryColor.orElse(null);
        b.accentColor  = accentColor.orElse(null);
        b.roofColor    = roofColor.orElse(null);
        b.patronFaith  = patronFaith.orElse(null);
        b.toft         = toft.orElse(null);
        return b;
    }

    // =========================================================================
    // Constructors
    // =========================================================================

    /** Public constructor — generates a new random UUID. */
    public Building(String name, BuildingType type, BuildingShape shape,
                    Identifier structureId,
                    Rotation rotation,
                    int level) {
        this.buildingName  = name;
        this.buildingType  = type;
        this.buildingShape = shape;
        this.structureId   = structureId;
        this.rotation      = rotation;
        this.buildingLevel = level;
        this.id            = UUID.randomUUID();
        this.condition     = BuildingCondition.NEW;
        this.variantId     = BuildingVariant.defaultVariantId(type);
    }

    /** Private constructor — used by codec (preserves persisted UUID). */
    private Building(UUID id, String name, BuildingType type, BuildingShape shape,
                     Identifier structureId, int buildingLevel,
                     Rotation rotation) {
        this.id            = id;
        this.buildingName  = name;
        this.buildingType  = type;
        this.buildingShape = shape;
        this.structureId   = structureId;
        this.buildingLevel = buildingLevel;
        this.rotation      = rotation;
        this.condition     = BuildingCondition.WEATHERED; // safe default for loaded buildings
        this.variantId     = BuildingVariant.defaultVariantId(type);
    }

    // =========================================================================
    // Getters / setters
    // =========================================================================

    public UUID              getId()            { return id; }
    public String            getName()          { return buildingName; }
    public BuildingType      getType()          { return buildingType; }
    public BuildingShape     getShape()         { return buildingShape; }
    public Identifier        getStructureId()   { return structureId; }
    public int               getLevel()         { return buildingLevel; }
    public Rotation getRotation() { return rotation; }
    public BuildingCondition getCondition()     { return condition; }

    /**
     * Phase 6.4.3.2 — block-scan amenity inventory for this building.
     * Walks every block inside {@link BuildingShape}, classifying via
     * {@link AmenityType#matches}. Result is cached for
     * {@link #AMENITY_CACHE_TTL} ticks (~one game day) so repeat reads
     * (housing decisions, profession-self-sufficiency checks) don't
     * re-scan; mid-day block edits are eventually consistent.
     *
     * <p>For a typical 12×6×12 house this is ~860 block lookups, run
     * once per game day per asking NPC. Acceptable.</p>
     *
     * <p>Returns an immutable view; callers that want a copy should
     * wrap with {@code EnumSet.copyOf}.</p>
     */
    public Set<AmenityType> getAmenities(ServerLevel level) {
        long now = level.getGameTime();
        if (cachedAmenities != null && (now - cachedAmenitiesTick) < AMENITY_CACHE_TTL) {
            return Collections.unmodifiableSet(cachedAmenities);
        }
        EnumSet<AmenityType> found = EnumSet.noneOf(AmenityType.class);
        AmenityType[] all = AmenityType.values();
        BlockPos min = buildingShape.getMin();
        BlockPos max = buildingShape.getMax();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            Block block = level.getBlockState(pos).getBlock();
            for (AmenityType t : all) {
                if (!found.contains(t) && t.matches(block)) found.add(t);
            }
            if (found.size() == all.length) break; // early exit — all types found
        }
        cachedAmenities = found;
        cachedAmenitiesTick = now;
        return Collections.unmodifiableSet(found);
    }

    /** Convenience: true iff {@code type} is present inside this
     *  building per the cached scan. */
    public boolean hasAmenity(ServerLevel level, AmenityType type) {
        return getAmenities(level).contains(type);
    }

    /** Drops the amenity cache so the next {@link #getAmenities} read
     *  re-scans. Use when a known block edit has invalidated the cache
     *  ahead of its TTL — usually not needed; TTL is short enough that
     *  most callers don't bother. */
    public void invalidateAmenityCache() {
        cachedAmenitiesTick = Long.MIN_VALUE;
    }

    public void setName(String name)             { this.buildingName  = name; }
    public void setStructureId(Identifier id)    { this.structureId   = id; }
    public void setUpgradeLevel(int level)       { this.buildingLevel = level; }
    public void setLevel(int level)              { this.buildingLevel = level; }
    public void setCondition(BuildingCondition c){ this.condition     = c; }

    public String getVariantId() { return variantId; }
    public void   setVariantId(String variantId) {
        this.variantId = variantId != null
                ? variantId
                : BuildingVariant.defaultVariantId(buildingType);
    }

    /** R3e-2 — raw patron-faith id, or {@code null} when unset. Prefer the
     *  canonical resolver {@code BuildingFaith.resolveFaith} for effect logic
     *  (it derives the village dominant for an unset temple/chapel). */
    @Nullable public String getPatronFaith()      { return patronFaith; }
    public void setPatronFaith(@Nullable String religionId) { this.patronFaith = religionId; }

    /** Track A2 — the back-of-house toft plot AABB, or null if this building
     *  has none. The HOMESTEAD system reads this for the resident's garden. */
    @Nullable public Polygon.AABB getToft() { return toft; }
    public void setToft(@Nullable Polygon.AABB toft) { this.toft = toft; }
    /** Centre of the toft plot (floor-Y carried from the building origin), or
     *  null when there is no toft — the homestead nav target for tending. */
    @Nullable public BlockPos getToftCentre() {
        if (toft == null) return null;
        return new BlockPos((toft.minX() + toft.maxX()) / 2,
                buildingShape.getOrigin().getY(),
                (toft.minZ() + toft.maxZ()) / 2);
    }

    @Nullable public DyeColor getPrimaryColor() { return primaryColor; }
    @Nullable public DyeColor getAccentColor()  { return accentColor; }
    @Nullable public DyeColor getRoofColor()    { return roofColor; }

    public void setPrimaryColor(@Nullable DyeColor c) { this.primaryColor = c; }
    public void setAccentColor(@Nullable DyeColor c)  { this.accentColor  = c; }
    public void setRoofColor(@Nullable DyeColor c)    { this.roofColor    = c; }

    // =========================================================================
    // Legacy NBT save/load (used by old code paths, kept for compatibility)
    // =========================================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("name",     buildingName);
        tag.putString("type",     buildingType.name());
        tag.put("shape",          buildingShape.save());
        tag.putString("id",       structureId.getNamespace());
        tag.putString("rotation", rotation.name());
        tag.putInt("level",       buildingLevel);
        tag.putString("condition", condition.name());
        return tag;
    }

    public Building load(CompoundTag tag) {
        String       name     = tag.getString("name").get();
        BuildingType type     = BuildingType.valueOf(tag.getString("type").get());
        BuildingShape shape   = BuildingShape.load(tag.getCompound("shape").get());
        Identifier   sid      = this.getStructureId();
        Rotation rot = this.getRotation();
        int          level    = tag.getIntOr("level", 0);
        BuildingCondition cond;
        try {
            cond = BuildingCondition.valueOf(
                    tag.getString("condition").orElse("WEATHERED"));
        } catch (IllegalArgumentException e) {
            cond = BuildingCondition.WEATHERED;
        }
        Building b = new Building(name, type, shape, sid, rot, level);
        b.condition = cond;
        return b;
    }

    // =========================================================================
    // Block-fill utility
    // =========================================================================

    public void fillBlock(Building building, ServerLevel level,
                          Block target, Block replacement) {
        BuildingShape shape = building.getShape();
        BlockPos min = shape.getMin();
        BlockPos max = shape.getMax();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).getBlock() == target) {
                        level.setBlock(pos, replacement.defaultBlockState(), 3);
                    }
                }
            }
        }
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
