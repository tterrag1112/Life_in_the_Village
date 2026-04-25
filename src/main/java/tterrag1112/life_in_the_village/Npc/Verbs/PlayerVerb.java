package tterrag1112.life_in_the_village.Npc.Verbs;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import java.util.Map;
import java.util.Optional;

/**
 * Player-facing action that operates on an NPC. Each implementation
 * declares its own eligibility ({@link #isAvailable}) and side effects
 * ({@link #invoke}).
 *
 * <p>Spec: {@code docs/npc_redesign/09-player-verbs.md}.</p>
 *
 * <p>Phase 1 ships 8 implementations registered by
 * {@link PlayerVerbRegistry#registerDefaults()}. Cooldowns and
 * eligibility gates are evaluated by each verb against the
 * {@link VerbContext} the GUI / debug command supplies.</p>
 */
public interface PlayerVerb {

    /** Stable string id; used in network packets and {@code /verb fire}. */
    String id();

    /** Human-readable label rendered on the verb button. */
    Component label();

    /** Optional tooltip text shown on hover. */
    default Optional<Component> tooltip() { return Optional.empty(); }

    /**
     * Eligibility gate. If {@code false}, the verb is omitted from the
     * action bar and {@code /verb fire} reports an error rather than
     * invoking. Should be cheap — runs every snapshot rebuild.
     */
    boolean isAvailable(VerbContext ctx);

    /** Effect dispatch. Caller is responsible for handling the result. */
    VerbResult invoke(VerbContext ctx);

    /**
     * Verb invocation parameters carried alongside the verb id over
     * the wire (e.g. compliment topic, ask-about target uuid). Most
     * verbs use no params.
     */
    default VerbResult invoke(VerbContext ctx, Map<String, String> args) {
        return invoke(ctx);
    }

    /** Display order on the action bar (per spec line 224). */
    default int displayOrder() { return 100; }

    /**
     * Cooldown in ticks (per-NPC + per-player). 0 means no cooldown.
     * Defaults from spec line 253.
     */
    default int cooldownTicks() { return 0; }

    /** Convenience for callers building VerbContexts. */
    static VerbContext context(ServerPlayer player, TownspersonMob npc, ServerLevel level) {
        return new VerbContext(player, npc, level, level.getGameTime());
    }
}
