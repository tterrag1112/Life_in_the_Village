package tterrag1112.life_in_the_village.Village.Economy.Resources;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * One physical stage in a multi-step production workflow.
 *
 * <p>The NPC walks to {@link #station}, works for {@link #ticks}, then
 * consumes {@link #consumes} from its personal inventory and adds
 * {@link #produces} to its personal inventory.</p>
 *
 * <h3>Station</h3>
 * {@code null} means "work at the building origin" — used for professions
 * that don't require a specific block (tanner, scribe, etc.).
 *
 * <h3>Consumes / Produces</h3>
 * Both maps hold <em>total</em> amounts (already scaled by batch size by the
 * caller). For multi-step workflows, intermediate items flow through the
 * personal inventory between steps — only external inputs (from building
 * storage) are consumed in the first step; intermediate products from step
 * N are consumed in step N+1.
 *
 * <h3>Ticks</h3>
 * Duration is already speed-multiplied; the goal class does not apply
 * any further scaling to this value.
 */
public record ProductionStep(
        @Nullable BlockPos         station,    // null → building origin
        int                        ticks,      // pre-scaled duration
        SoundEvent                 sound,
        Item                       heldTool,   // Items.AIR = no tool change
        Map<Item, Integer>         consumes,   // taken from personal inv at step end
        Map<Item, Integer>         produces    // added to personal inv at step end
) {
    /**
     * Convenience factory for a step with no item transformations
     * (e.g. an intermediate animation step, or when the subclass manages
     * consumption manually via {@link
     */
    public static ProductionStep empty(@Nullable BlockPos station, int ticks,
                                       SoundEvent sound, Item heldTool) {
        return new ProductionStep(station, ticks, sound, heldTool,
                Map.of(), Map.of());
    }

    /** True if this step requires no specific block. */
    public boolean isStationless() { return station == null; }
}