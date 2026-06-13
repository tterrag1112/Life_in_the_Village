package tterrag1112.life_in_the_village.Kingdom.Settlement;

/**
 * Track C1 — lifecycle stage of a {@link SettlementCharter}.
 *
 * <p>Advances monotonically {@code CHARTERED -> SURVEYED -> REALIZED}.
 * Each stage gates which optional fields of the charter are populated:
 *
 * <ul>
 *   <li>{@code CHARTERED} — issued at kingdom worldgen from atlas-cell
 *       digests only (no block siting). Stage-0 fields set; survey and
 *       realization fields empty.</li>
 *   <li>{@code SURVEYED} — C1-b fills {@code surveyedAnchor} +
 *       {@code footprintScore} by scanning the target cell through
 *       {@code GeneratorTerrainSource}. (Not produced in C1-a.)</li>
 *   <li>{@code REALIZED} — C1-c plans a {@code Village} at the surveyed
 *       anchor and sets {@code realizedVillageId}. (Not produced in
 *       C1-a.)</li>
 * </ul>
 *
 * <p>New enum justified by a concrete consumer: the map pin distinguishes
 * unrealized (charter pin at cell-centre) from realized (village marker),
 * and C1-b/c key their batched passes off the stage.
 */
public enum SettlementCharterStage {
    CHARTERED,
    SURVEYED,
    REALIZED
}
