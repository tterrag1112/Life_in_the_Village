package tterrag1112.life_in_the_village.Npc.Religion.Sacred;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Npc.Religion.BuildingFaith;
import tterrag1112.life_in_the_village.Npc.Religion.God;
import tterrag1112.life_in_the_village.Npc.Religion.GodRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Sacred Space S1a — the <b>query</b>: how sacred a position is to a god, folding
 * (this stage) two sources whose potencies SUM:
 * <ol>
 *   <li><b>Natural</b> — the sum of the god's {@link SacredSpaceRule} contributions
 *       (authored in {@code GodRegistry}); stacks (biome + structure both fire).</li>
 *   <li><b>Built</b> — a religious building (TEMPLE/CHAPEL/SHRINE/MONASTERY/ABBEY) of
 *       <i>that god's faith</i> within {@link #BUILT_RADIUS} of {@code pos} adds a
 *       <b>lesser</b> {@link #BUILT_POTENCY} (built &lt; natural, per design),
 *       resolved through {@link BuildingFaith}.</li>
 * </ol>
 *
 * <p>A third source — the decaying theophany <b>imprint</b> layer — is S3; there is a
 * clean seam for it in {@link #explain} (a BlockPos-keyed SavedData folds in there).
 * S1a wires no effects: only the {@code /religion sacred} readout reads this. The
 * effects (favour / miracle / theophany bonuses) call it in S1b.</p>
 *
 * <p><b>Cost:</b> the natural fold is cheap except {@link SacredSpaceRule.StructureRule}
 * (a structure-manager query); the built fold scans the village containing {@code pos}.
 * S1b throttles/caches when calling on hot paths (per-player tick).</p>
 */
public final class SacredSpace {

    private SacredSpace() {}

    /** The lesser potency a same-faith religious building within range contributes. */
    public static final float BUILT_POTENCY = 0.5f;
    /** Horizontal range (blocks) within which a same-faith building's built
     *  sacredness reaches {@code pos}. */
    public static final int BUILT_RADIUS = 48;

    /** One named contribution to a position's sacredness (for the readout). */
    public record Contribution(String source, float potency) {}

    /** The full breakdown at a position for a god: the summed total + each
     *  contributing source (natural rules + the built bonus). */
    public record Breakdown(float total, List<Contribution> contributions) {}

    /** The total sacredness potency at {@code pos} for {@code godId} (natural +
     *  built). 0 when the god is unknown or the spot is not sacred to it. */
    public static float sacrednessAt(ServerLevel level, String godId, BlockPos pos) {
        return explain(level, godId, pos).total();
    }

    /** The coarse {@link SacrednessTier} the S1b effects read. */
    public static SacrednessTier tierAt(ServerLevel level, String godId, BlockPos pos) {
        return SacrednessTier.classify(sacrednessAt(level, godId, pos));
    }

    /** S1b — the sacred amplifier ({@link SacrednessTier#amplifier()}) for {@code godId}
     *  at {@code pos}, or <b>1.0 when {@code pos == null}</b> (a non-positional grant —
     *  today's numbers, unchanged). The one entry the favour + miracle paths call so
     *  the tier→multiplier mapping is never duplicated. */
    public static float amplifierAt(ServerLevel level, String godId, BlockPos pos) {
        if (pos == null) return 1.0f;
        return tierAt(level, godId, pos).amplifier();
    }

    /**
     * The full breakdown (total + per-source contributions). The single fold home —
     * both {@link #sacrednessAt} and the readout go through here, so the natural /
     * built logic is never duplicated.
     */
    public static Breakdown explain(ServerLevel level, String godId, BlockPos pos) {
        List<Contribution> out = new ArrayList<>();
        float total = 0f;
        if (level == null || godId == null || pos == null) return new Breakdown(0f, out);

        // 1. Natural — the god's authored rules (each contribution sums).
        God god = GodRegistry.get(godId);
        if (god != null) {
            for (SacredSpaceRule rule : god.sacredSpace()) {
                float p = rule.potencyAt(level, pos);
                if (p > 0f) { out.add(new Contribution(rule.label(), p)); total += p; }
            }
        }

        // 2. Built — a same-faith religious building within range adds a lesser bonus
        //    (once; presence, not a per-building stack).
        float built = builtPotency(level, godId, pos);
        if (built > 0f) { out.add(new Contribution("built", built)); total += built; }

        // 3. Imprint (S3) — decaying theophany imprints of this god near pos (their
        //    current decayed strength). The dynamic third source; composes additively
        //    so every S1b/S2 effect inherits it through sacrednessAt.
        long now = level.getGameTime();
        float imprint = SacredSpaceSavedData.get(level).imprintPotency(level, godId, pos, now);
        if (imprint > 0f) { out.add(new Contribution("imprint", imprint)); total += imprint; }

        return new Breakdown(total, out);
    }

    /** {@link #BUILT_POTENCY} when a religious building of {@code godId}'s faith sits
     *  within {@link #BUILT_RADIUS} (horizontal) of {@code pos}; else 0. Delegates to
     *  the shared god-aware building scan ({@link BuildingFaith#hasBuildingOfGodNear}). */
    private static float builtPotency(ServerLevel level, String godId, BlockPos pos) {
        return BuildingFaith.hasBuildingOfGodNear(level, godId, pos, BUILT_RADIUS,
                BuildingFaith::isReligiousBuilding) ? BUILT_POTENCY : 0f;
    }
}
