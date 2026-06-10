package tterrag1112.life_in_the_village.Npc.Religion.Saints;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Components.ModDataComponents;
import tterrag1112.life_in_the_village.Items.ModItems;

import java.util.UUID;

/**
 * Saints &amp; Relics SR1 — a living saint's <b>personal divine ease</b> with their
 * patron god: a multiplier ≥ 1.0, mirroring the sacred-space
 * ({@code SacredSpace.amplifierAt}) and sacred-time ({@code SacredTime.holyDayFactor})
 * amplifiers so it composes the same way. Folded beside them into the favour grant and
 * miracle paths; a non-saint (or a saint of a different god) reads <b>1.0</b> —
 * today's exact numbers.
 *
 * <p>Self-focused (SR1 scope): it eases the saint's OWN favour/miracles with the
 * patron god; it never bypasses the tier gate or the favour cap (those live in the
 * effects, unchanged). Bless-others is a later layer.</p>
 */
public final class SaintFactor {

    private SaintFactor() {}

    /** The personal ease a living saint enjoys with their patron god (tunable). */
    public static final float SAINT_AMPLIFIER = 1.25f;
    /** SR4 — the smaller, bounded ease a player carrying a relic of the god enjoys.
     *  Composes (multiplies) with the living-saint ease. */
    public static final float RELIC_CARRY_AMPLIFIER = 1.1f;

    /** The saint ease for {@code beingId} with {@code godId}: the living-saint
     *  amplifier (if any) × the carried-relic amplifier (if carrying a relic of that
     *  god). 1.0 for an ordinary being. The one home for the saint multiplier. */
    public static float amplifierFor(ServerLevel level, UUID beingId, String godId) {
        if (level == null || beingId == null || godId == null) return 1.0f;
        float amp = SaintsSavedData.get(level).isLivingSaint(beingId, godId)
                ? SAINT_AMPLIFIER : 1.0f;
        amp *= relicCarryFactor(level, beingId, godId);
        return amp;
    }

    /** {@link #RELIC_CARRY_AMPLIFIER} when {@code beingId} is an online player carrying a
     *  relic whose patron god is {@code godId}; else 1.0. NPCs don't carry relics. */
    private static float relicCarryFactor(ServerLevel level, UUID beingId, String godId) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(beingId);
        if (player == null) return 1.0f;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty() || !stack.is(ModItems.RELIC.get())) continue;
            RelicData data = stack.get(ModDataComponents.RELIC_DATA.get());
            if (data != null && godId.equals(data.godId())) return RELIC_CARRY_AMPLIFIER;
        }
        return 1.0f;
    }
}
