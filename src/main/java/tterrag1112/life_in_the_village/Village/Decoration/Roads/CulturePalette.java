package tterrag1112.life_in_the_village.Village.Decoration.Roads;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import tterrag1112.life_in_the_village.Village.Roads.Lighting.RoadLightingProfile;

import java.util.List;
import java.util.Optional;

/**
 * Culture-scoped palette used by Phase 7g to drive road surface colour,
 * support/retaining-wall blocks, and the roadside lighting rig.
 *
 * <p>Registered in {@link PaletteRegistry}. Resolved at realisation time from
 * the edge's maintaining kingdom culture (or the nearest village's kingdom
 * culture for great roads). Palettes do not persist on edges — the resolution
 * happens each time the edge is realised.
 *
 * <h3>Block lists</h3>
 * Surface and support block lists are ordered {@code primary / accent / rare};
 * callers sample them with position-deterministic noise so patterns stay
 * stable across re-realisations. The retaining-wall lists are optional — if
 * absent, the effective retaining-wall palette falls back to the support
 * lists.
 *
 * <h3>Great-road alternate</h3>
 * When {@link #isGreatRoadAlternate} is {@code true} this palette replaces the
 * default Old Realm palette for {@code GREAT_ROAD} edges whose nearest
 * culture matches this palette's {@link #cultureId}. This is the hook that
 * turns Imperial-maintained great roads into royal roads.
 */
public record CulturePalette(
        String cultureId,

        // Surface — road placement
        List<Block> surfacePrimary,
        List<Block> surfaceAccent,
        List<Block> surfaceRare,

        // Support blocks — retaining walls and road supports (Phase 7e)
        List<Block> supportPrimary,
        List<Block> supportAccent,
        List<Block> supportRare,

        // Lighting
        RoadLightingProfile defaultLighting,
        Block lightBlock,
        Block lightBaseBlock,
        Optional<Block> lightCapBlock,

        // Retaining wall palette — optional; falls back to support palette.
        Optional<List<Block>> retainingWallPrimary,
        Optional<List<Block>> retainingWallAccent,

        // Metadata
        boolean isGreatRoadAlternate
) {

    /**
     * Retaining wall primary list, falling back to supportPrimary if unset.
     */
    public List<Block> effectiveRetainingWallPrimary() {
        return retainingWallPrimary.orElse(supportPrimary);
    }

    /**
     * Retaining wall accent list, falling back to supportAccent if unset.
     */
    public List<Block> effectiveRetainingWallAccent() {
        return retainingWallAccent.orElse(supportAccent);
    }

    // =========================================================================
    // Codec
    // =========================================================================

    private static final Codec<Block> BLOCK_CODEC = Identifier.CODEC.xmap(
            id -> BuiltInRegistries.BLOCK.get(id).map(h -> h.value()).orElse(Blocks.COBBLESTONE),
            BuiltInRegistries.BLOCK::getKey
    );

    public static final Codec<CulturePalette> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("cultureId").forGetter(CulturePalette::cultureId),
            BLOCK_CODEC.listOf().fieldOf("surfacePrimary").forGetter(CulturePalette::surfacePrimary),
            BLOCK_CODEC.listOf().fieldOf("surfaceAccent").forGetter(CulturePalette::surfaceAccent),
            BLOCK_CODEC.listOf().fieldOf("surfaceRare").forGetter(CulturePalette::surfaceRare),
            BLOCK_CODEC.listOf().fieldOf("supportPrimary").forGetter(CulturePalette::supportPrimary),
            BLOCK_CODEC.listOf().fieldOf("supportAccent").forGetter(CulturePalette::supportAccent),
            BLOCK_CODEC.listOf().fieldOf("supportRare").forGetter(CulturePalette::supportRare),
            RoadLightingProfile.CODEC.fieldOf("defaultLighting").forGetter(CulturePalette::defaultLighting),
            BLOCK_CODEC.fieldOf("lightBlock").forGetter(CulturePalette::lightBlock),
            BLOCK_CODEC.fieldOf("lightBaseBlock").forGetter(CulturePalette::lightBaseBlock),
            BLOCK_CODEC.optionalFieldOf("lightCapBlock").forGetter(CulturePalette::lightCapBlock),
            BLOCK_CODEC.listOf().optionalFieldOf("retainingWallPrimary").forGetter(CulturePalette::retainingWallPrimary),
            BLOCK_CODEC.listOf().optionalFieldOf("retainingWallAccent").forGetter(CulturePalette::retainingWallAccent),
            Codec.BOOL.optionalFieldOf("isGreatRoadAlternate", false).forGetter(CulturePalette::isGreatRoadAlternate)
    ).apply(i, CulturePalette::new));
}
