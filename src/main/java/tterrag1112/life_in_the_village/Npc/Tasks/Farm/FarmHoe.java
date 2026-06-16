package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.FarmerBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.ToolUseSupport;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

/**
 * G1b — hoe-acquisition helper for the farm task executors.
 *
 * <p>Ports {@code FarmerBehavior.tryAcquireHoeFromFarmhouse} as a static
 * utility so the three crop executors ({@link HarvestExecutor},
 * {@link TillExecutor}, {@link ReplantExecutor}) can each call it at the
 * start of their work phase without duplicating logic.</p>
 *
 * <p>{@link #ensureHoe} is a no-op when the NPC already holds a hoe or
 * when no hoe is present in farmhouse storage — in that case the executor
 * degrades to {@link FarmerBehavior#HOE_PRODUCTIVITY_NO_HOE} (0.50×),
 * exactly as the legacy behavior did.</p>
 *
 * <p>The buy-an-IRON_HOE fallback (legacy {@code FarmerBehavior.acquireTool})
 * is deliberately NOT ported here — deferred to a later phase. See
 * "Out-of-scope" in the G1b PROGRESS entry.</p>
 */
public final class FarmHoe {

    private FarmHoe() {}

    /**
     * If {@code npc} has no usable hoe in personal inventory, scan the
     * farmhouse storage containers and pull one hoe stack (single item)
     * into the NPC's personal inventory. No-op if a hoe is already held
     * or none is available in storage.
     *
     * <p>This mirrors {@code FarmerBehavior.tryAcquireHoeFromFarmhouse}
     * verbatim: any hoe tier is accepted, only one item is taken, and
     * the farmhouse container slot is shrunk by 1.</p>
     *
     * @param level     server level (for block-entity lookups)
     * @param farmhouse the farmer's assigned FARMHOUSE building
     * @param npc       the farmer NPC
     */
    public static void ensureHoe(ServerLevel level, Building farmhouse, TownspersonMob npc) {
        // Already has a hoe — nothing to do
        if (ToolUseSupport.hasUsableTool(npc, FarmerBehavior::isHoe)) return;

        // Scan farmhouse inventories for the first hoe stack
        for (Container container : BuildingStorageAccess.findInventories(level, farmhouse)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty() || !FarmerBehavior.isHoe(stack)) continue;
                // Pull one item from the farmhouse slot into personal inventory
                ItemStack one = stack.copy();
                one.setCount(1);
                stack.shrink(1);
                npc.getPersonalInventory().addItem(one);
                return; // one hoe is enough
            }
        }
        // No hoe found in storage — degrade to HOE_PRODUCTIVITY_NO_HOE (0.5×)
    }
}
