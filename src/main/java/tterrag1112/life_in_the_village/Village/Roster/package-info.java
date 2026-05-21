/**
 * Phase 6.3.3.d — building-fixed two-level entity simulation.
 *
 * <p>Pattern: a building hosts a roster of entities (chickens in a
 * coop, sheep in a pen, bees in a hive, future content). Each slot is
 * either <em>realized</em> (a real entity in the world with a UUID,
 * loaded chunks) or <em>simulated</em> (just a count + template state,
 * never realized OR de-realized after chunk unload). Production loops
 * (egg-laying, milk, honey, wool) advance in BOTH modes; breeding +
 * slaughter operate on simulated slots too. Transitions are proximity-
 * driven by chunk load / unload.
 *
 * <h2>Relationship to TravellingGroup</h2>
 * <p>Conceptually parallel to {@code Village.Travel.TravellingGroup}
 * (used by caravans / adventurer groups) but with different semantics:
 * caravans / parties travel along a path with progress 0.0 → 1.0;
 * rosters live at a fixed building position. The lifecycle hook shape
 * mirrors TravellingGroup (onRealize / onDerealize / per-cycle tick)
 * but the proximity attachment is to a {@code BlockPos}, not a moving
 * group.
 *
 * <h2>Foundation only</h2>
 * <p>6.3.3.d ships the infrastructure ({@link RosterSlot},
 * {@link RosterDefinition}, {@link BuildingRoster},
 * {@link RosterRegistry}). The animal-husbandry content pass (6.3.3.g)
 * registers concrete definitions (CHICKEN / PIG / COW / SHEEP / BEE)
 * and wires the building-attachment / chunk-load triggers. Until then
 * no rosters exist and behavioral impact is zero.
 *
 * <h2>Content-pass registration timing</h2>
 * <p>Roster definitions register at mod init via
 * {@link RosterRegistry#register}. Buildings acquire rosters lazily —
 * content checks if a building's adjunct plots include a
 * {@code POULTRY}-tagged plot and registers a CHICKEN roster on first
 * tick.
 */
package tterrag1112.life_in_the_village.Village.Roster;
