package tterrag1112.life_in_the_village.Entities.custom.Appearance;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * A wearable / carried item that decorates an NPC's appearance.
 * Phase 5 doc 33 § Cultural accessories.
 *
 * <p>Accessories are added to an NPC's
 * {@link AppearanceComponent#getAccessoryIds()} list at spawn (one
 * per culture, plus a 5% rare variant), through gifts (circlet,
 * pendant), and through professions (Phase 6 expansion). The
 * renderer composites them at their {@link #slot()}.</p>
 *
 * <p>Phase 5 v1 ships definitions without authored art. Resource
 * paths describe the expected file layout for the future art
 * pass.</p>
 */
public record AccessoryDefinition(
        String id,
        ResourceLocation texture,
        AccessorySlot slot,
        Optional<ResourceLocation> model,
        String shortLabel
) {
    public AccessoryDefinition {
        if (model == null) model = Optional.empty();
        if (shortLabel == null) shortLabel = id;
    }

    public static AccessoryDefinition textureOnly(String id, ResourceLocation texture,
                                                  AccessorySlot slot, String label) {
        return new AccessoryDefinition(id, texture, slot, Optional.empty(), label);
    }

    public static AccessoryDefinition withModel(String id, ResourceLocation texture,
                                                AccessorySlot slot,
                                                ResourceLocation model, String label) {
        return new AccessoryDefinition(id, texture, slot, Optional.of(model), label);
    }
}
