package tterrag1112.life_in_the_village.Entities.custom.Appearance;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Per-culture base texture set. Phase 5 doc 33 § Data model.
 *
 * <p>{@code variants} is a list of skin-tone variant textures (3-4
 * per culture per spec line 113). The renderer picks an entry by
 * the NPC's {@code skinToneVariant} index modulo
 * {@code variants.size()}.</p>
 *
 * <p>Phase 5 v1 ships this registry without authored textures —
 * each {@link CultureBase} carries the resource paths the renderer
 * <em>will</em> read once the art lands. The paths follow the
 * convention {@code lifeinthevillage:textures/entity/townsperson/
 * culture/{cultureId}/base_{variant}.png}.</p>
 */
public record CultureBase(
        String cultureId,
        ResourceLocation baseTexture,
        List<ResourceLocation> variants
) {
    public CultureBase {
        variants = List.copyOf(variants == null ? List.of() : variants);
    }

    public ResourceLocation variantFor(int variantIndex) {
        if (variants.isEmpty()) return baseTexture;
        int safeIndex = ((variantIndex % variants.size()) + variants.size()) % variants.size();
        return variants.get(safeIndex);
    }

    public int variantCount() {
        return variants.isEmpty() ? 1 : variants.size();
    }
}
