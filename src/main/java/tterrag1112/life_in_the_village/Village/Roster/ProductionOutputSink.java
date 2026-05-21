package tterrag1112.life_in_the_village.Village.Roster;

import net.minecraft.world.item.ItemStack;

/**
 * Phase 6.3.3.d — receiver for items produced by a {@link BuildingRoster}
 * cycle (egg-laying, milk, honey, wool) or by a slaughter event. Decouples
 * the roster from the storage mechanism so different building types can
 * route output differently:
 * <ul>
 *   <li>Homestead coops drop eggs into the host household's chest</li>
 *   <li>Farm pens drop milk into the farmhouse storage</li>
 *   <li>Test harnesses can capture output into a list</li>
 * </ul>
 *
 * <p>Default implementation lives in 6.3.3.g; 6.3.3.d ships only the
 * interface.
 */
@FunctionalInterface
public interface ProductionOutputSink {
    void accept(ItemStack stack);
}
