package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

/**
 * Track E1 prompt-3 — declares that a "lead" {@link BuildingType}
 * should be placed at the given anchor position rather than scored
 * by the general selector.
 *
 * <p>Bindings are a strong preference — placement still validates
 * terrain admissibility + reservation overlap at the bound
 * position. If validation fails, the binding's building falls
 * through to the regular foundation/iterative selection path.
 *
 * @param type      lead {@link BuildingType} (typically one-per-
 *                  village; see
 *                  {@link tterrag1112.life_in_the_village.Village
 *                  .Planning.V2.Layer2.LeadBuildingTypes})
 * @param position  bound anchor's centre in world coordinates
 * @param anchorId  source anchor's {@code id} (e.g. {@code "a0"}),
 *                  preserved for dump tracing
 * @param reason    human-readable origin string for the dump log
 */
public record PrimaryBinding(
        BuildingType type,
        BlockPos position,
        String anchorId,
        String reason) {
    public PrimaryBinding {
        if (type == null) {
            throw new IllegalArgumentException("PrimaryBinding type required");
        }
        if (position == null) {
            throw new IllegalArgumentException("PrimaryBinding position required");
        }
        if (anchorId == null) anchorId = "";
        if (reason == null) reason = "";
    }
}
