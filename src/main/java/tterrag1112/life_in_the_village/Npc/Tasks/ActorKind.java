package tterrag1112.life_in_the_village.Npc.Tasks;

/**
 * What kind of entity is claiming / performing a {@link Task}.
 *
 * <p>The Task-System core is player-ready: nothing in the core assumes
 * the actor is an NPC. A {@code PLAYER} actor can be added later (its
 * own {@link TaskActor} impl) without touching any core type. T0 ships
 * only the NPC path; {@code PLAYER} is the reserved extension point.</p>
 */
public enum ActorKind {
    NPC,
    PLAYER
}
