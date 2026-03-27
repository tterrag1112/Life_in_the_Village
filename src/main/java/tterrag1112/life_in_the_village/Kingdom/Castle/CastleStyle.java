package tterrag1112.life_in_the_village.Kingdom.Castle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;

public record CastleStyle(
        String styleId,
        LayoutConfig layout,
        WallConfig walls,
        TowerConfig towers,
        DonjeonConfig donjon,
        FeatureConfig features,
        StructurePoolConfig pools,
        MaterialPalette primaryMaterial,
        MaterialPalette accentMaterial,
        MaterialPalette floorMaterial,
        float ruinationLevel,
        long styleSeed
) {

    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------

    public enum PlanType implements StringRepresentable {
        SQUARE, RECTANGLE, POLYGON, CONCENTRIC, IRREGULAR;

        public static final Codec<PlanType> CODEC =
                StringRepresentable.fromEnum(PlanType::values);

        @Override
        public String getSerializedName() {
            return this.name().toLowerCase();
        }
    }

    public enum TowerShape implements StringRepresentable {
        ROUND, SQUARE, D_SHAPED, POLYGONAL;

        public static final Codec<TowerShape> CODEC =
                StringRepresentable.fromEnum(TowerShape::values);

        @Override
        public String getSerializedName() {
            return this.name().toLowerCase();
        }
    }

    // -------------------------------------------------------------------------
    // Codec — 11 fields, well within the 16-field limit
    // -------------------------------------------------------------------------

    public static final Codec<CastleStyle> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("style_id").forGetter(CastleStyle::styleId),
                    LayoutConfig.CODEC.fieldOf("layout").forGetter(CastleStyle::layout),
                    WallConfig.CODEC.fieldOf("walls").forGetter(CastleStyle::walls),
                    TowerConfig.CODEC.fieldOf("towers").forGetter(CastleStyle::towers),
                    DonjeonConfig.CODEC.fieldOf("donjon").forGetter(CastleStyle::donjon),
                    FeatureConfig.CODEC.fieldOf("features").forGetter(CastleStyle::features),
                    StructurePoolConfig.CODEC.fieldOf("pools").forGetter(CastleStyle::pools),
                    MaterialPalette.CODEC.fieldOf("primary_material").forGetter(CastleStyle::primaryMaterial),
                    MaterialPalette.CODEC.fieldOf("accent_material").forGetter(CastleStyle::accentMaterial),
                    MaterialPalette.CODEC.fieldOf("floor_material").forGetter(CastleStyle::floorMaterial),
                    Codec.FLOAT.fieldOf("ruination_level").forGetter(CastleStyle::ruinationLevel),
                    Codec.LONG.fieldOf("style_seed").forGetter(CastleStyle::styleSeed)
            ).apply(instance, CastleStyle::new)
    );

    // -------------------------------------------------------------------------
    // Convenience helpers — delegate to sub-records
    // -------------------------------------------------------------------------

    public int rollWallHeight(RandomSource rng) {
        return walls.minWallHeight() == walls.maxWallHeight()
                ? walls.minWallHeight()
                : walls.minWallHeight() + rng.nextInt(walls.maxWallHeight() - walls.minWallHeight() + 1);
    }

    public int rollTowerRadius(RandomSource rng) {
        return towers.minTowerRadius() == towers.maxTowerRadius()
                ? towers.minTowerRadius()
                : towers.minTowerRadius() + rng.nextInt(towers.maxTowerRadius() - towers.minTowerRadius() + 1);
    }

    public int rollRadius(RandomSource rng) {
        return layout.minRadius() == layout.maxRadius()
                ? layout.minRadius()
                : layout.minRadius() + rng.nextInt(layout.maxRadius() - layout.minRadius() + 1);
    }

    public int rollDonjeonSize(RandomSource rng) {
        return donjon.donjeonMinSize() == donjon.donjeonMaxSize()
                ? donjon.donjeonMinSize()
                : donjon.donjeonMinSize() + rng.nextInt(donjon.donjeonMaxSize() - donjon.donjeonMinSize() + 1);
    }

    public boolean rollTowerAt(RandomSource rng) {
        return rng.nextFloat() < towers.towerFrequency();
    }

    public record LayoutConfig(
            CastleStyle.PlanType planType,
            int minRadius,
            int maxRadius
    ) {
        public static final Codec<LayoutConfig> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        CastleStyle.PlanType.CODEC.fieldOf("plan_type").forGetter(LayoutConfig::planType),
                        Codec.INT.fieldOf("min_radius").forGetter(LayoutConfig::minRadius),
                        Codec.INT.fieldOf("max_radius").forGetter(LayoutConfig::maxRadius)
                ).apply(instance, LayoutConfig::new)
        );
    }
    public record WallConfig(
            int minWallHeight,
            int maxWallHeight,
            int wallThickness
    ) {
        public static final Codec<WallConfig> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.INT.fieldOf("min_wall_height").forGetter(WallConfig::minWallHeight),
                        Codec.INT.fieldOf("max_wall_height").forGetter(WallConfig::maxWallHeight),
                        Codec.INT.fieldOf("wall_thickness").forGetter(WallConfig::wallThickness)
                ).apply(instance, WallConfig::new)
        );
    }
    public record TowerConfig(
            CastleStyle.TowerShape towerShape,
            int minTowerRadius,
            int maxTowerRadius,
            int towerHeightBonus,
            float towerFrequency
    ) {
        public static final Codec<TowerConfig> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        CastleStyle.TowerShape.CODEC.fieldOf("tower_shape").forGetter(TowerConfig::towerShape),
                        Codec.INT.fieldOf("min_tower_radius").forGetter(TowerConfig::minTowerRadius),
                        Codec.INT.fieldOf("max_tower_radius").forGetter(TowerConfig::maxTowerRadius),
                        Codec.INT.fieldOf("tower_height_bonus").forGetter(TowerConfig::towerHeightBonus),
                        Codec.FLOAT.fieldOf("tower_frequency").forGetter(TowerConfig::towerFrequency)
                ).apply(instance, TowerConfig::new)
        );
    }
    public record DonjeonConfig(
            boolean hasDonjon,
            int donjeonMinSize,
            int donjeonMaxSize,
            int donjeonHeightBonus,
            boolean hasInnerWard,
            float innerWardScale
    ) {
        public static final Codec<DonjeonConfig> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.BOOL.fieldOf("has_donjon").forGetter(DonjeonConfig::hasDonjon),
                        Codec.INT.fieldOf("donjon_min_size").forGetter(DonjeonConfig::donjeonMinSize),
                        Codec.INT.fieldOf("donjon_max_size").forGetter(DonjeonConfig::donjeonMaxSize),
                        Codec.INT.fieldOf("donjon_height_bonus").forGetter(DonjeonConfig::donjeonHeightBonus),
                        Codec.BOOL.fieldOf("has_inner_ward").forGetter(DonjeonConfig::hasInnerWard),
                        Codec.FLOAT.fieldOf("inner_ward_scale").forGetter(DonjeonConfig::innerWardScale)
                ).apply(instance, DonjeonConfig::new)
        );
    }
    public record FeatureConfig(
            boolean hasMoat,
            int moatWidth,
            int moatDepth,
            boolean hasPortcullis,
            boolean hasDrawbridge,
            boolean addTorches,
            boolean addFlags
    ) {
        public static final Codec<FeatureConfig> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.BOOL.fieldOf("has_moat").forGetter(FeatureConfig::hasMoat),
                        Codec.INT.fieldOf("moat_width").forGetter(FeatureConfig::moatWidth),
                        Codec.INT.fieldOf("moat_depth").forGetter(FeatureConfig::moatDepth),
                        Codec.BOOL.fieldOf("has_portcullis").forGetter(FeatureConfig::hasPortcullis),
                        Codec.BOOL.fieldOf("has_drawbridge").forGetter(FeatureConfig::hasDrawbridge),
                        Codec.BOOL.fieldOf("add_torches").forGetter(FeatureConfig::addTorches),
                        Codec.BOOL.fieldOf("add_flags").forGetter(FeatureConfig::addFlags)
                ).apply(instance, FeatureConfig::new)
        );
    }
    public record StructurePoolConfig(
            Identifier battlementPool,
            Identifier gatehousePool,
            Identifier windowPool,
            Identifier towerRoofPool,
            Identifier donjeonRoofPool,
            Identifier interiorPool
    ) {
        public static final Codec<StructurePoolConfig> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Identifier.CODEC.fieldOf("battlement_pool").forGetter(StructurePoolConfig::battlementPool),
                        Identifier.CODEC.fieldOf("gatehouse_pool").forGetter(StructurePoolConfig::gatehousePool),
                        Identifier.CODEC.fieldOf("window_pool").forGetter(StructurePoolConfig::windowPool),
                        Identifier.CODEC.fieldOf("tower_roof_pool").forGetter(StructurePoolConfig::towerRoofPool),
                        Identifier.CODEC.fieldOf("donjon_roof_pool").forGetter(StructurePoolConfig::donjeonRoofPool),
                        Identifier.CODEC.optionalFieldOf(
                                "interior_pool",
                                Identifier.parse("life_in_the_village:castle/interiors/empty")
                        ).forGetter(StructurePoolConfig::interiorPool)
                ).apply(instance, StructurePoolConfig::new)
        );
    }


}