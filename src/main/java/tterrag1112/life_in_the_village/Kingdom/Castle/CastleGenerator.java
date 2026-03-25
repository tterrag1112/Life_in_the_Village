package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;

/**
 * Public entry point for castle generation.
 *
 * This is the only class your mod's structure placement code needs to call.
 * Everything else in this package is an implementation detail.
 *
 * Usage:
 * <pre>
 *   CastleStyle style = CastleStyles.NORMAN;   // or load from datapack
 *   CastleGenerator.generate(level, origin, style, rng);
 * </pre>
 *
 * The generation executes in three phases:
 *   Phase 1 (CastleLayoutGenerator) — produce a CastleLayout with no world access
 *   Phase 2 (CastleBuilder)         — place structural blocks using the layout
 *   Phase 3 (DetailApplicator)      — stamp structure-file templates for visual detail
 *
 * All three phases are invoked synchronously. For chunk-by-chunk or
 * deferred generation, wrap the call in your own scheduling logic.
 */
public final class CastleGenerator {

    private CastleGenerator() {}

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    /**
     * Generate a castle centered on origin using the given style.
     *
     * @param level  The world accessor. Must be a ServerLevel for structure
     *               template loading (Layer 3). On a ClientLevel, Layer 3 is
     *               silently skipped and the castle generates without decoration.
     * @param origin The center of the castle footprint. The Y coordinate is used
     *               as the starting scan point for terrain detection — the actual
     *               placement anchors to the surface automatically.
     * @param style  The CastleStyle controlling all generation parameters.
     * @param rng    A seeded random source. Use a seed derived from the chunk
     *               position + world seed for deterministic generation.
     */
    public static void generate(LevelAccessor level,
                                BlockPos origin,
                                CastleStyle style,
                                RandomSource rng) {

        // Phase 1: Layout (no world access)
        CastleLayoutGenerator layoutGen = new CastleLayoutGenerator();
        CastleLayout layout = layoutGen.generate(style, origin, rng);

        // Phase 2 + 3: Build (world access, includes detail application)
        CastleBuilder builder = new CastleBuilder(level, style);
        builder.build(layout, rng);
    }

    /**
     * Overload that returns the CastleLayout for external inspection
     * (e.g. for placing loot chests, spawners, or NPCs afterward).
     */
    public static CastleLayout generateWithLayout(LevelAccessor level,
                                                  BlockPos origin,
                                                  CastleStyle style,
                                                  RandomSource rng) {
        CastleLayoutGenerator layoutGen = new CastleLayoutGenerator();
        CastleLayout layout = layoutGen.generate(style, origin, rng);

        CastleBuilder builder = new CastleBuilder(level, style);
        builder.build(layout, rng);

        return layout;
    }
}
