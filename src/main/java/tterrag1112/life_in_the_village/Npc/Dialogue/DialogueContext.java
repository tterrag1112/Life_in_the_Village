package tterrag1112.life_in_the_village.Npc.Dialogue;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Context passed to every predicate, effect, and placeholder
 * resolution during a dialogue session. Either {@code player} or
 * {@code listener} should be non-null in practice; both are allowed to
 * be {@code null} for fully-synthetic test contexts.
 *
 * <p>Spec: {@code docs/npc_redesign/08-dialogue-tree.md} (line 107).
 * The {@code scratch} map carries values between nodes inside a single
 * walk — e.g. an option's selected label, a goal's id chosen by an
 * earlier branch.</p>
 */
public record DialogueContext(
        TownspersonMob speaker,
        @Nullable ServerPlayer player,
        @Nullable TownspersonMob listener,
        ServerLevel level,
        long tick,
        Map<String, String> scratch
) {
    public DialogueContext {
        if (scratch == null) scratch = new HashMap<>();
    }

    /** Listener UUID — player UUID if a player is present, otherwise the NPC listener. */
    public UUID listenerUuid() {
        if (player != null) return player.getUUID();
        if (listener != null) return listener.getUUID();
        return null;
    }

    /** Convenience for predicates that resolve a {@link Target} to a UUID. */
    public UUID resolveTarget(Target target) {
        return target == null ? null : target.resolve(this);
    }

    public static DialogueContext forPlayer(TownspersonMob speaker, ServerPlayer player,
                                            ServerLevel level) {
        return new DialogueContext(speaker, player, null, level,
                level.getGameTime(), new HashMap<>());
    }
}
