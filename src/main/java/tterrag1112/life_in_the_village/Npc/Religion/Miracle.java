package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Divine Layer V2 — one requestable <b>miracle</b>: a per-deity, domain-flavoured
 * boon a devout player buys with Divine Favour (V1). Authored per faith in
 * {@link Miracles}; invoked through {@link MiracleInvoker} (gate → spend → apply).
 *
 * <p><b>Scaling.</b> Two axes make the religious path "grounded → fantastical":
 * across miracles (a faith's set runs low-cost/low-tier potion-tier → high-cost/
 * PIOUS fantastical), and <i>within</i> a miracle (the {@link Effect} receives the
 * caster's current favour and scales its magnitude/duration with it).</p>
 *
 * @param id            unique miracle id (also the {@code /religion miracle cast} arg)
 * @param religionId    the owning faith (the favour pool spent)
 * @param displayName   player-facing name
 * @param domain        the deity's domain (D1) — theming
 * @param cost          favour spent per cast
 * @param minTier       piety-tier gate in the owning faith (PietyTier ordinal)
 * @param minFavour     favour the player must hold to invoke (≥ cost; high miracles
 *                      demand real standing, not just the exact cost)
 * @param cooldownTicks per-player anti-spam cooldown
 * @param flavour       one-line description
 * @param effect        the applied effect (favour-scaled)
 */
public record Miracle(
        String id,
        String religionId,
        String displayName,
        DeityDomain domain,
        float cost,
        PietyTier minTier,
        float minFavour,
        int cooldownTicks,
        String flavour,
        Effect effect
) {
    /** The applied boon. Receives the caster's current favour so the effect can
     *  scale (grounded at low favour, fantastical at the peaks). */
    @FunctionalInterface
    public interface Effect {
        void apply(ServerLevel level, ServerPlayer player, float favour);
    }
}
