package tterrag1112.life_in_the_village.Village;

import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.SmokerBlock;

import java.util.function.Predicate;

/**
 * Phase 6.4.3.2 — amenity classification for {@link Building} content
 * scans. A building "has" an amenity when at least one block inside
 * its {@link Building.BuildingShape} bounds satisfies the matching
 * predicate.
 *
 * <p>Used by LifeGoal-driven housing preference: an NPC with a
 * {@code REACH_SKILL_LEVEL=BAKING} goal prefers houses that contain
 * a {@link #SMOKER} or {@link #FURNACE} so they can practice the
 * skill in their own home.</p>
 *
 * <p>Start small. Add new amenities here as new LifeGoals or
 * profession-self-sufficiency stories need them. The matcher
 * predicate is the only per-amenity data needed.</p>
 */
public enum AmenityType {

    /** Vanilla furnace — general cooking / smelting. */
    FURNACE      (b -> b instanceof FurnaceBlock || b instanceof BlastFurnaceBlock),
    /** Vanilla smoker — food-specific (faster meat / bread cook). */
    SMOKER       (b -> b instanceof SmokerBlock),
    /** Vanilla grindstone — milling / repair. */
    GRINDSTONE   (b -> b instanceof GrindstoneBlock),
    /** Vanilla anvil — smithing repairs + enchant-combine. */
    ANVIL        (b -> b instanceof AnvilBlock),
    /** Vanilla crafting table — general crafting. */
    CRAFTING_TABLE(b -> b instanceof CraftingTableBlock),
    /** Vanilla brewing stand — potion work. */
    BREWING_STAND(b -> b instanceof BrewingStandBlock),
    /** Vanilla chest — storage. ChestBlock covers single + double chests. */
    CHEST        (b -> b instanceof ChestBlock);

    // GARDEN_PLOT intentionally omitted at v1 — adjunct plots live on
    // HouseholdData, not on the building's own block bounds, so the
    // detection shape differs. Lands when HOMESTEAD_GARDEN linkage is
    // wired through the amenity scan in a later sub-phase.

    private final Predicate<Block> match;

    AmenityType(Predicate<Block> match) {
        this.match = match;
    }

    public boolean matches(Block block) {
        return block != null && match.test(block);
    }
}
