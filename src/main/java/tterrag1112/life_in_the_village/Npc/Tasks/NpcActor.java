package tterrag1112.life_in_the_village.Npc.Tasks;

import java.util.UUID;

/**
 * The only {@link TaskActor} impl shipped in T0: wraps a
 * {@code TownspersonMob}'s UUID.
 *
 * <p>Holds the UUID only, not the entity, so it is cheap to construct
 * and safe to retain. The acting entity (when one is resolvable) is
 * reached through {@link TaskContext#npc()} during evaluation.</p>
 */
public record NpcActor(UUID id) implements TaskActor {

    @Override
    public ActorKind kind() {
        return ActorKind.NPC;
    }
}
