package tterrag1112.life_in_the_village.Kingdom.Castle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Defines how one type of subbuilding should be placed in this castle style.
 *
 * The spec overrides the SubbuildingType's defaults, allowing a Moorish castle
 * to place its library in a different zone than a Norman castle, or to require
 * more barracks than the base type suggests.
 *
 * pool is optional — if absent, SubbuildingPlacer uses its procedural fallback.
 */
public record SubbuildingSpec(
        SubbuildingType type,
        SubbuildingType.PlacementZone zone,
        int minCount,
        int maxCount,
        Identifier lootTable,   // null key = no chest loot
        Identifier pool         // null key = use procedural fallback
) {

    public static final Identifier NO_LOOT =
            Identifier.parse("life_in_the_village:castle/empty");

    public static final Codec<SubbuildingSpec> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    SubbuildingType.CODEC
                            .fieldOf("type").forGetter(SubbuildingSpec::type),
                    SubbuildingType.PlacementZone.CODEC
                            .fieldOf("zone").forGetter(SubbuildingSpec::zone),
                    Codec.INT
                            .optionalFieldOf("min_count", 1).forGetter(SubbuildingSpec::minCount),
                    Codec.INT
                            .optionalFieldOf("max_count", 1).forGetter(SubbuildingSpec::maxCount),
                    Identifier.CODEC
                            .optionalFieldOf("loot_table", NO_LOOT)
                            .forGetter(SubbuildingSpec::lootTable),
                    Identifier.CODEC
                            .optionalFieldOf("pool", NO_LOOT)
                            .forGetter(SubbuildingSpec::pool)
            ).apply(instance, SubbuildingSpec::new)
    );

    // ---- Convenience factories for use in CastleStyles.java ----

    public static SubbuildingSpec required(SubbuildingType type) {
        return new SubbuildingSpec(
                type, type.defaultZone, 1, 1,
                lootKey(type), NO_LOOT);
    }

    public static SubbuildingSpec optional(SubbuildingType type, int max) {
        return new SubbuildingSpec(
                type, type.defaultZone, 0, max,
                lootKey(type), NO_LOOT);
    }

    public static SubbuildingSpec withPool(SubbuildingType type, String styleName) {
        return new SubbuildingSpec(
                type, type.defaultZone, 1, 1,
                lootKey(type),
                Identifier.parse("life_in_the_village:castle/subbuildings/"
                        + type.getSerializedName() + "/" + styleName));
    }

    public boolean hasLoot() {
        return !lootTable.equals(NO_LOOT);
    }

    public boolean hasPool() {
        return !pool.equals(NO_LOOT);
    }

    private static Identifier lootKey(SubbuildingType type) {
        return Identifier.parse(
                "life_in_the_village:castle/" + type.getSerializedName());
    }
}