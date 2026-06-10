package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Npc.Religion.DivineFavour.DispleasureTier;

/**
 * Divine Layer V4 — one <b>curse</b>: a per-deity, domain-flavoured misfortune a
 * displeased deity visits on a sacrilegious player. The negative mirror of
 * {@link Miracle} (authored in {@link Curses}, applied by {@link DivineWrath}).
 * Two severities — {@link DispleasureTier#CURSE} (serious misfortune) and
 * {@link DispleasureTier#WRATH} (severe but, by design, <b>non-fatal</b>).
 *
 * @param religionId  the displeased faith
 * @param domain      the deity's domain (D1) — flavour
 * @param severity    CURSE or WRATH (the band it fires at)
 * @param displayName player-facing name
 * @param flavour     one-line description (the deity's grievance)
 * @param effect      the applied misfortune
 */
public record Curse(
        String religionId,
        DeityDomain domain,
        DispleasureTier severity,
        String displayName,
        String flavour,
        Effect effect
) {
    /** The applied misfortune (negative MobEffects / weather). */
    @FunctionalInterface
    public interface Effect {
        void apply(ServerLevel level, ServerPlayer player);
    }
}
