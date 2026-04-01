package tterrag1112.life_in_the_village.Kingdom.Castle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * Defines the functional programme of a castle — what subbuildings it contains,
 * how many of each, and who lives there.
 *
 * Separating function from visual style (CastleStyle's other sub-records) means
 * the same functional layout can be applied to different architectural styles,
 * and datapacks can override function without touching visual config.
 */
public record FunctionConfig(
        CastleRoster roster,
        List<SubbuildingSpec> subbuildingSpecs,
        boolean hasStable,
        boolean hasDungeon,
        boolean hasChapel,
        boolean hasLibrary,
        boolean hasAlchemistLab,
        boolean hasTrainingYard
) {

    public static final Codec<FunctionConfig> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    CastleRoster.CODEC
                            .fieldOf("roster").forGetter(FunctionConfig::roster),
                    SubbuildingSpec.CODEC.listOf()
                            .fieldOf("subbuilding_specs").forGetter(FunctionConfig::subbuildingSpecs),
                    Codec.BOOL
                            .optionalFieldOf("has_stable",       false).forGetter(FunctionConfig::hasStable),
                    Codec.BOOL
                            .optionalFieldOf("has_dungeon",      false).forGetter(FunctionConfig::hasDungeon),
                    Codec.BOOL
                            .optionalFieldOf("has_chapel",       false).forGetter(FunctionConfig::hasChapel),
                    Codec.BOOL
                            .optionalFieldOf("has_library",      false).forGetter(FunctionConfig::hasLibrary),
                    Codec.BOOL
                            .optionalFieldOf("has_alchemist_lab",false).forGetter(FunctionConfig::hasAlchemistLab),
                    Codec.BOOL
                            .optionalFieldOf("has_training_yard",false).forGetter(FunctionConfig::hasTrainingYard)
            ).apply(instance, FunctionConfig::new)
    );

    // ---- Preset configs matching the four CastleStyles presets ----

    public static final FunctionConfig NORMAN_DEFAULT = new FunctionConfig(
            CastleRoster.MILITARY,
            List.of(
                    SubbuildingSpec.required(SubbuildingType.THRONE_ROOM),
                    SubbuildingSpec.required(SubbuildingType.ROYAL_QUARTERS),
                    SubbuildingSpec.required(SubbuildingType.TREASURY),
                    SubbuildingSpec.required(SubbuildingType.BARRACKS),
                    SubbuildingSpec.required(SubbuildingType.WELL),
                    SubbuildingSpec.optional(SubbuildingType.ARMOURY,       1),
                    SubbuildingSpec.optional(SubbuildingType.GUARDHOUSE,    2),
                    SubbuildingSpec.optional(SubbuildingType.KITCHEN,       1),
                    SubbuildingSpec.optional(SubbuildingType.GRANARY,       1),
                    SubbuildingSpec.optional(SubbuildingType.SMITHY,        1),
                    SubbuildingSpec.optional(SubbuildingType.DUNGEON,       1)
            ),
            true,  // hasStable
            true,  // hasDungeon
            true,  // hasChapel
            false, // hasLibrary
            false, // hasAlchemistLab
            true   // hasTrainingYard
    );

    public static final FunctionConfig MOORISH_DEFAULT = new FunctionConfig(
            CastleRoster.SCHOLARLY,
            List.of(
                    SubbuildingSpec.required(SubbuildingType.THRONE_ROOM),
                    SubbuildingSpec.required(SubbuildingType.ROYAL_QUARTERS),
                    SubbuildingSpec.required(SubbuildingType.TREASURY),
                    SubbuildingSpec.required(SubbuildingType.BARRACKS),
                    SubbuildingSpec.required(SubbuildingType.WELL),
                    SubbuildingSpec.required(SubbuildingType.LIBRARY),
                    SubbuildingSpec.optional(SubbuildingType.COUNCIL_CHAMBER, 1),
                    SubbuildingSpec.optional(SubbuildingType.SCRIPTORIUM,     1),
                    SubbuildingSpec.optional(SubbuildingType.OBSERVATORY,     1),
                    SubbuildingSpec.optional(SubbuildingType.ALCHEMIST_LAB,   1),
                    SubbuildingSpec.optional(SubbuildingType.KITCHEN,         1),
                    SubbuildingSpec.optional(SubbuildingType.NOBLE_QUARTERS,  2),
                    SubbuildingSpec.optional(SubbuildingType.TAILORS_ROOM,    1)
            ),
            false, // hasStable
            false, // hasDungeon
            true,  // hasChapel
            true,  // hasLibrary
            true,  // hasAlchemistLab
            false  // hasTrainingYard
    );

    public static final FunctionConfig FANTASY_DEFAULT = new FunctionConfig(
            CastleRoster.FANTASY,
            List.of(
                    SubbuildingSpec.required(SubbuildingType.THRONE_ROOM),
                    SubbuildingSpec.required(SubbuildingType.ROYAL_QUARTERS),
                    SubbuildingSpec.required(SubbuildingType.TREASURY),
                    SubbuildingSpec.required(SubbuildingType.BARRACKS),
                    SubbuildingSpec.required(SubbuildingType.WELL),
                    SubbuildingSpec.required(SubbuildingType.LIBRARY),
                    SubbuildingSpec.required(SubbuildingType.ALCHEMIST_LAB),
                    SubbuildingSpec.optional(SubbuildingType.COUNCIL_CHAMBER, 1),
                    SubbuildingSpec.optional(SubbuildingType.NOBLE_QUARTERS,  3),
                    SubbuildingSpec.optional(SubbuildingType.OBSERVATORY,     1),
                    SubbuildingSpec.optional(SubbuildingType.DUNGEON,         1),
                    SubbuildingSpec.optional(SubbuildingType.SCRIPTORIUM,     1),
                    SubbuildingSpec.optional(SubbuildingType.CISTERN,         1)
            ),
            true,  // hasStable
            true,  // hasDungeon
            false, // hasChapel
            true,  // hasLibrary
            true,  // hasAlchemistLab
            true   // hasTrainingYard
    );

    public static final FunctionConfig RUIN_DEFAULT = new FunctionConfig(
            new CastleRoster(0,0,0,0,0,0,0,0,0,0,0,0,0,0), // no residents
            List.of(
                    // Ruins keep only structural placeholders — no functional rooms
                    SubbuildingSpec.optional(SubbuildingType.DUNGEON, 1)
            ),
            false, false, false, false, false, false
    );
}