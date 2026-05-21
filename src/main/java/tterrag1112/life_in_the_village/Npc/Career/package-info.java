/**
 * Phase 6.3.3.a — career transition facade.
 *
 * <p>{@link tterrag1112.life_in_the_village.Entities.custom.TownspersonMob#setProfession
 * TownspersonMob.setProfession} is a leaf-level setter: it writes entityData,
 * re-registers Profession goals, resets the tenure clock, pays the
 * starter pouch on first non-NONE assignment, runs guild bootstrap, and
 * triggers the appearance rebuild. Critically, it <em>bypasses</em> the
 * coming-of-age / apprenticeship / retirement handlers — any caller that
 * goes straight to {@code setProfession} silently misses their state
 * machine bookkeeping.
 *
 * <p>This package provides {@link tterrag1112.life_in_the_village.Npc.Career.CareerTransitions}
 * as the single observation / veto surface for profession transitions,
 * lifestage transitions, and apprenticeship graduations. Listeners
 * register at mod init via
 * {@link tterrag1112.life_in_the_village.Npc.Career.CareerListenerRegistry};
 * content packs and culture rules plug in here.
 *
 * <h2>Dual API</h2>
 * <ul>
 *   <li>{@link tterrag1112.life_in_the_village.Npc.Career.CareerTransitions#changeProfession
 *       changeProfession} — gated path. Listeners may veto. Use from
 *       ordinary system / player / content paths.</li>
 *   <li>{@link tterrag1112.life_in_the_village.Npc.Career.CareerTransitions#changeProfessionForced
 *       changeProfessionForced} — bypass listeners. Use from debug
 *       commands, save migration, worldgen entity construction.</li>
 * </ul>
 *
 * <h2>Listener registration timing</h2>
 * <p>Listeners are <em>behavior</em>, not data — registered at mod init,
 * never persisted. The registry rebuilds on each launch.
 *
 * <h2>Foundation only</h2>
 * <p>Phase 6.3.3.a ships infrastructure; no built-in listeners are
 * registered. Behavioral impact today is zero — every prior
 * {@code setProfession} call reaches the same leaf with identical
 * effects.
 */
package tterrag1112.life_in_the_village.Npc.Career;
