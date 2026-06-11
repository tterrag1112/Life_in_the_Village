package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;

/**
 * A1 stage 1 — GREEN (Angerdorf) decoration carried planner→adapter render
 * (sibling to {@link CourtyardDecor}; the skirt lane + entry ride the
 * {@link InternalPath} seam, this carries the communal green + optional well):
 * <ul>
 *   <li>{@code green} — the communal green's bounds (inside the skirt lane).
 *       The adapter registers it as a COTTAGE_GREEN {@code GardenPlot} so
 *       {@code ParkRenderer} composes the open lawn + scattered flora from the
 *       existing park primitives — no new flora painter. 4c-c: NULL marks a
 *       WELL-ONLY entry (the workshop quarter's work-yard) — the adapter
 *       stamps the well + gathering point but registers no flora; the yard
 *       stays open ground.</li>
 *   <li>{@code wellCentre} — surface-snapped centre for the plaza
 *       {@code well_hamlet} stamp + a FOUNTAIN {@code GatheringPoint};
 *       null when this green rolled no well (seeded ~2/3 chance).</li>
 *   <li>{@code seed} — the block's deterministic seed (RNG continuity).</li>
 * </ul>
 */
public record GreenDecor(Polygon.AABB green, BlockPos wellCentre, long seed) {}
