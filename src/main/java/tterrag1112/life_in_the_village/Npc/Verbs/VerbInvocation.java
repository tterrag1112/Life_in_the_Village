package tterrag1112.life_in_the_village.Npc.Verbs;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Dialogue.DialogueRunner;

import java.util.Map;
import java.util.Optional;

/**
 * Server-side verb invocation glue. Centralises cooldown enforcement,
 * verb dispatch via {@link PlayerVerbRegistry}, dialogue routing via
 * {@link DialogueRunner}, and chat feedback to the player.
 *
 * <p>Used by both the network packet handler ({@code PlayerVerbInvokePacket})
 * and the {@code /verb fire} debug command.</p>
 */
public final class VerbInvocation {

    private static final Logger LOGGER = LogUtils.getLogger();

    private VerbInvocation() {}

    /**
     * Looks up the verb, checks availability + cooldown, calls invoke,
     * routes the resulting dialogue tree to chat, and stamps the
     * cooldown.
     *
     * @return the {@link VerbResult} returned by the verb (or a
     *         failure result if cooldown / unavailable).
     */
    public static VerbResult invoke(ServerPlayer player, TownspersonMob npc,
                                    ServerLevel level, String verbId,
                                    Map<String, String> args) {
        Optional<PlayerVerb> verbOpt = PlayerVerbRegistry.get(verbId);
        if (verbOpt.isEmpty()) {
            return failAndNotify(player, "Unknown verb: " + verbId);
        }
        PlayerVerb verb = verbOpt.get();
        VerbContext ctx = PlayerVerb.context(player, npc, level);

        if (!verb.isAvailable(ctx)) {
            return failAndNotify(player, verb.label().getString() + " is not available right now.");
        }

        long now = level.getGameTime();
        var cooldowns = npc.getVerbCooldowns();
        if (cooldowns.isOnCooldown(verb.id(), player.getUUID(), now)) {
            long remainingTicks = cooldowns.ticksRemaining(verb.id(), player.getUUID(), now);
            return failAndNotify(player,
                    verb.label().getString() + " is on cooldown ("
                            + Math.max(1L, remainingTicks / 20L) + "s remaining).");
        }

        VerbResult result;
        try {
            result = args == null || args.isEmpty()
                    ? verb.invoke(ctx)
                    : verb.invoke(ctx, args);
        } catch (Throwable t) {
            LOGGER.warn("[Verbs] {} threw on invoke: {}", verb.id(), t.getMessage());
            return failAndNotify(player, "That didn't work. (" + t.getMessage() + ")");
        }

        if (!result.success()) {
            if (!result.statusText().isEmpty()) notify(player, result.statusText());
            return result;
        }

        // Stamp cooldown only on a successful invoke.
        if (verb.cooldownTicks() > 0) {
            cooldowns.setCooldown(verb.id(), player.getUUID(),
                    now + verb.cooldownTicks());
        }

        // Surface dialogue tree (if any) in chat.
        if (!result.resultTreeId().isEmpty()) {
            DialogueRunner.runAndSendChat(npc, player, level, result.resultTreeId());
        }
        return result;
    }

    private static VerbResult failAndNotify(ServerPlayer player, String reason) {
        notify(player, reason);
        return VerbResult.failure(reason);
    }

    private static void notify(ServerPlayer player, String text) {
        if (player == null) return;
        player.sendSystemMessage(Component.literal(text));
    }
}
