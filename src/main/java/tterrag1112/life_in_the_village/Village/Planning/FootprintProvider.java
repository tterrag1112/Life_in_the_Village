package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;

/**
 * Track E1 — footprint seam for the headless harness.
 *
 * <p>The planner's only access to building dimensions.
 * {@link StructureSizeCache} is the production implementation; it
 * loads dimensions from the authored NBT through the live server's
 * resource manager. The harness uses a fixed in-memory table so it
 * can run without a server.
 *
 * <p>The interface signature exactly matches the existing
 * {@code StructureSizeCache#get(culture, style, type, variantId,
 * level, rotation)} method, so the live path can pass a
 * {@link StructureSizeCache} unchanged — no behavior change, no
 * touch to the warn-once / fallback-to-default-footprint state the
 * cache keeps internally.
 */
public interface FootprintProvider {

    StructureSizeCache.FootprintInfo get(String culture,
                                         Style style,
                                         BuildingType type,
                                         String variantId,
                                         int level,
                                         Rotation rotation);
}
