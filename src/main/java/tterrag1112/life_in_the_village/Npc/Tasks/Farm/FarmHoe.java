package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.ToolUseSupport;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

/**
 * G1b/G2 — hoe identity, productivity constants, and acquisition helper for
 * the farm task executors.
 *
 * <p>The hoe identity predicate ({@link #isHoe}) and the productivity-ladder
 * constants/scorer ({@link #hoeProductivityMultiplier},
 * {@code HOE_PRODUCTIVITY_*}) were relocated here from
 * {@code FarmerBehavior} (G2 Part B) so the executors no longer depend on
 * the deleted class. The logic is identical — this is a pure relocation.</p>
 *
 * <p>{@link #ensureHoe} is a no-op when the NPC already holds a hoe or
 * when no hoe is present in farmhouse storage — in that case the executor
 * degrades to {@link #HOE_PRODUCTIVITY_NO_HOE} (0.50×),
 * exactly as the legacy behavior did.</p>
 *
 * <p>The buy-an-IRON_HOE fallback (legacy {@code FarmerBehavior.acquireTool})
 * is deliberately NOT ported here — deferred to a later phase. See
 * "Out-of-scope" in the G1b PROGRESS entry.</p>
 *
 * <h3>Call sites for ToolUseSupport</h3>
 * Pass {@code FarmHoe::isHoe} and {@code FarmHoe::hoeProductivityMultiplier}
 * to {@link ToolUseSupport#bestToolMultiplier} and
 * {@link ToolUseSupport#useToolFromInventory}.
 */
public final class FarmHoe {

    private FarmHoe() {}

    // ── Hoe productivity ladder (relocated from FarmerBehavior.HOE_PRODUCTIVITY_*) ──
    /** No hoe in inventory — 50% yield baseline. */
    public static final float HOE_PRODUCTIVITY_NO_HOE    = 0.50f;
    public static final float HOE_PRODUCTIVITY_WOOD      = 0.70f;
    public static final float HOE_PRODUCTIVITY_STONE     = 0.85f;
    public static final float HOE_PRODUCTIVITY_IRON      = 1.00f;
    public static final float HOE_PRODUCTIVITY_DIAMOND   = 1.10f;
    public static final float HOE_PRODUCTIVITY_NETHERITE = 1.10f;

    // ── Hoe identity (relocated from FarmerBehavior.isHoe) ───────────────────

    /** Predicate identifying farming hoes (wood/stone/iron/golden/diamond/netherite). */
    public static boolean isHoe(ItemStack s) {
        return s.is(Items.WOODEN_HOE) || s.is(Items.STONE_HOE)
                || s.is(Items.IRON_HOE) || s.is(Items.GOLDEN_HOE)
                || s.is(Items.DIAMOND_HOE) || s.is(Items.NETHERITE_HOE);
    }

    // ── Productivity scorer (relocated from FarmerBehavior.hoeProductivityMultiplier) ──

    /**
     * Per-stack productivity score for the hoe ladder. Used as the scoring
     * lambda passed to {@link ToolUseSupport#bestToolMultiplier} so the helper
     * returns the highest tier present in the entity's inventory. Golden hoes
     * score as iron-tier (same operational tier, worse durability).
     */
    public static double hoeProductivityMultiplier(ItemStack s) {
        if (s.is(Items.NETHERITE_HOE)) return HOE_PRODUCTIVITY_NETHERITE;
        if (s.is(Items.DIAMOND_HOE))   return HOE_PRODUCTIVITY_DIAMOND;
        if (s.is(Items.IRON_HOE)
                || s.is(Items.GOLDEN_HOE)) return HOE_PRODUCTIVITY_IRON;
        if (s.is(Items.STONE_HOE))     return HOE_PRODUCTIVITY_STONE;
        if (s.is(Items.WOODEN_HOE))    return HOE_PRODUCTIVITY_WOOD;
        return HOE_PRODUCTIVITY_NO_HOE;
    }

    // ── Acquisition helper ────────────────────────────────────────────────────

    /**
     * If {@code npc} has no usable hoe in personal inventory, scan the
     * farmhouse storage containers and pull one hoe stack (single item)
     * into the NPC's personal inventory. No-op if a hoe is already held
     * or none is available in storage.
     *
     * <p>Mirrors {@code FarmerBehavior.tryAcquireHoeFromFarmhouse}
     * verbatim: any hoe tier is accepted, only one item is taken, and
     * the farmhouse container slot is shrunk by 1.</p>
     *
     * @param level     server level (for block-entity lookups)
     * @param farmhouse the farmer's assigned FARMHOUSE building
     * @param npc       the farmer NPC
     */
    public static void ensureHoe(ServerLevel level, Building farmhouse, TownspersonMob npc) {
        // Already has a hoe — nothing to do
        if (ToolUseSupport.hasUsableTool(npc, FarmHoe::isHoe)) return;

        // Scan farmhouse inventories for the first hoe stack
        for (Container container : BuildingStorageAccess.findInventories(level, farmhouse)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty() || !FarmHoe.isHoe(stack)) continue;
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
