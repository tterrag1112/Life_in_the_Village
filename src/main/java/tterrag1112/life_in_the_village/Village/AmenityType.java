package tterrag1112.life_in_the_village.Village;

import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.StonecutterBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
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
    CHEST        (b -> b instanceof ChestBlock),
    /** Vanilla stonecutter — STONEMASON workstation (Phase 6.6.1.3). */
    STONECUTTER  (b -> b instanceof StonecutterBlock),
    /** Vanilla loom — WEAVER workstation (Phase 6.6.1.3). */
    LOOM         (b -> b instanceof LoomBlock),
    /** Vanilla beehive / bee-nest — R6b monastic apiary (BEEKEEPING honey work).
     *  Both BEEHIVE and BEE_NEST are {@link BeehiveBlock}. */
    APIARY       (b -> b instanceof BeehiveBlock),
    /** Vanilla lectern — R6b monastic scriptorium (LITERACY manuscript copying). */
    LECTERN      (b -> b instanceof LecternBlock);

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

    /**
     * The first block position inside {@code b}'s bounds matching one of
     * {@code types}, in preference order, or {@code null} if none (an empty
     * list ⇒ null = "no workstation needed"). Single home for the building
     * amenity scan — shared by the production primitive (R6b) + the monastery
     * developer (R6c).
     */
    public static BlockPos firstPresent(ServerLevel level, Building b, List<AmenityType> types) {
        for (AmenityType t : types) {
            BlockPos p = firstPresent(level, b, t);
            if (p != null) return p;
        }
        return null;
    }

    /** The first block position inside {@code b}'s bounds matching {@code type},
     *  or {@code null} if none. */
    public static BlockPos firstPresent(ServerLevel level, Building b, AmenityType type) {
        BlockPos min = b.getShape().getMin();
        BlockPos max = b.getShape().getMax();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (type.matches(level.getBlockState(pos).getBlock())) return pos.immutable();
        }
        return null;
    }
}
