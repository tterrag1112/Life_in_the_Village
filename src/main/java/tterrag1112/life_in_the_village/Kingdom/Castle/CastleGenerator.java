package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.List;

/**
 * Public entry point for castle generation.
 *
 * All styles now use the TMA-style room stack pipeline.
 * The old CastleBuilder pipeline has been removed.
 *
 * Pipeline:
 *   Phase 1 — CastleLayoutGenerator  (no world access, pure spatial data)
 *   Phase 2 — GroundPlanConverter    (CastleLayout → List<RoomStack>)
 *   Phase 3 — RoomStackBuilder       (places blocks, applies detail)
 *
 * Returns a CastleManifest for consumption by the capital system.
 */
public final class CastleGenerator {

    private CastleGenerator() {}

    // =========================================================================
    // Main entry point
    // =========================================================================

    /**
     * Generate a castle centered on origin using the given style.
     *
     * @param level  Must be a ServerLevel for structure template loading.
     * @param origin Center of the castle footprint. Y is the terrain scan start.
     * @param style  All generation parameters.
     * @param rng    Seeded from chunk position + world seed for determinism.
     * @return       CastleManifest with positions for capital/quest systems.
     */
    public static CastleManifest generate(ServerLevelAccessor level,
                                          BlockPos origin,
                                          CastleStyle style,
                                          RandomSource rng) {
        // Phase 1: Layout — no world access
        CastleLayoutGenerator layoutGen = new CastleLayoutGenerator();
        CastleLayout layout = layoutGen.generate(style, origin, rng);

        // Phase 2: Convert layout to room stacks
        TerrainSampler terrain = new TerrainSampler(level);
        GroundPlanConverter converter = new GroundPlanConverter(style, terrain);
        List<RoomStack> stacks = converter.convert(layout, rng);

        // Phase 3: Build and detail
        RoomStackBuilder builder = new RoomStackBuilder(level, style, terrain);
        return builder.build(layout, stacks, rng);
    }

    // =========================================================================
    // Layout-only overload (for preview / debug)
    // =========================================================================

    /**
     * Run Phase 1 only — produces the layout without touching the world.
     * Used by the debug command and preview tools.
     */
    public static CastleLayout generateLayout(CastleStyle style,
                                              BlockPos origin,
                                              RandomSource rng) {
        return new CastleLayoutGenerator().generate(style, origin, rng);
    }

    // =========================================================================
    // Layout + build overload (returns layout for external inspection)
    // =========================================================================

    /**
     * Full generation that also returns the CastleLayout for callers that
     * need tower positions, wall edges, or zone boundaries.
     */
    public static GenerationResult generateWithLayout(ServerLevelAccessor level,
                                                      BlockPos origin,
                                                      CastleStyle style,
                                                      RandomSource rng) {
        CastleLayoutGenerator layoutGen = new CastleLayoutGenerator();
        CastleLayout layout = layoutGen.generate(style, origin, rng);

        TerrainSampler terrain = new TerrainSampler(level);
        GroundPlanConverter converter = new GroundPlanConverter(style, terrain);
        List<RoomStack> stacks = converter.convert(layout, rng);

        RoomStackBuilder builder = new RoomStackBuilder(level, style, terrain);
        CastleManifest manifest = builder.build(layout, stacks, rng);

        return new GenerationResult(layout, manifest);
    }

    // =========================================================================
    // Result record
    // =========================================================================

    public record GenerationResult(CastleLayout layout, CastleManifest manifest) {}
}