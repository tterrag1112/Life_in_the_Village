package tterrag1112.life_in_the_village.Npc.Tasks;

import java.util.UUID;

/**
 * {@link TaskActor} implementation for a player. Mirrors {@link NpcActor}
 * exactly: holds only the stable UUID, never the live entity, so it is
 * cheap to construct and safe to retain across ticks.
 *
 * <p>Nothing in P0 consumes {@code PlayerActor} yet; it is the identity
 * anchor that P1+ dispatcher code will use when wiring player task
 * evaluation.</p>
 */
public record PlayerActor(UUID id) implements TaskActor {

    @Override
    public ActorKind kind() {
        return ActorKind.PLAYER;
    }
}
