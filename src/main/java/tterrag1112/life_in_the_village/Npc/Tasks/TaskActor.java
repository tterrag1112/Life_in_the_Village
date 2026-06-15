package tterrag1112.life_in_the_village.Npc.Tasks;

import java.util.UUID;

/**
 * Anything that can claim and perform a {@link Task}.
 *
 * <p>Deliberately minimal and actor-kind agnostic: the Task-System core
 * (filters, assignment, scoring, dispatch) only ever needs the actor's
 * stable {@link #id()} and its {@link #kind()}. Concrete situational
 * data (skills, finances, membership) is supplied per-evaluation through
 * {@link TaskContext}, never read off the actor — this is what keeps a
 * future {@code PlayerActor} addable without changing the core.</p>
 */
public interface TaskActor {

    /** Stable identity of the actor (NPC entity UUID, player UUID, ...). */
    UUID id();

    /** Discriminator so callers can branch without {@code instanceof}. */
    ActorKind kind();
}
