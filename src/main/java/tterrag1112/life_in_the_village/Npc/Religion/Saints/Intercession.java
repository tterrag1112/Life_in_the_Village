package tterrag1112.life_in_the_village.Npc.Religion.Saints;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import tterrag1112.life_in_the_village.Npc.Religion.DivineFavour;
import tterrag1112.life_in_the_village.Npc.Religion.God;
import tterrag1112.life_in_the_village.Npc.Religion.GodRegistry;
import tterrag1112.life_in_the_village.Npc.Religion.PietyTier;
import tterrag1112.life_in_the_village.Npc.Religion.Sacred.SacredSpaceSavedData;

import java.util.UUID;

/**
 * Saints &amp; Relics SR3 — the player's <b>intercession</b> prayer at a canonized
 * saint's grave: the first player-initiated religious petition. It earns the saint's
 * god's favour (amplified by the sacred grave), grants a small saint's blessing,
 * <b>tends</b> the grave (refreshing its imprint), and — at a Venerable's grave —
 * accrues <b>veneration</b> that can raise the Venerable by popular devotion
 * (complementing SR2's clergy elevation).
 *
 * <p><b>Bounded + gated:</b> FAITHFUL+ standing with the saint's god, a per-saint/day
 * cooldown, a modest favour grant ({@link DivineFavour.FavourAct#PRAYER}) and a
 * <i>lesser</i> domain blessing (not a full miracle). The cooldown bounds the
 * favour-from-a-sacred-grave loop (no power farm).</p>
 */
public final class Intercession {

    private Intercession() {}

    /** How close (horizontal) the player must stand to a saint's grave to pray. */
    public static final int PRAY_RADIUS = 4;
    /** One prayer to a given saint per in-game day, per player. */
    public static final long PRAYER_COOLDOWN = 24000L;
    /** Veneration (player prayers) that raises a Venerable by popular devotion. */
    public static final int VENERATION_THRESHOLD = 10;
    /** The lesser blessing's duration (ticks) — a brief, minor boon, not a miracle. */
    private static final int BLESSING_DURATION = 1200;

    public record Result(boolean ok, String message) {}

    /** Performs an intercession at the saint's grave the player is standing at. */
    public static Result pray(ServerLevel level, ServerPlayer player, long now) {
        SaintsSavedData saints = SaintsSavedData.get(level);
        BlockPos pos = player.blockPosition();
        SaintsSavedData.Saint saint = saints.nearestSaintGrave(pos, PRAY_RADIUS).orElse(null);
        if (saint == null) return new Result(false, "No saint's grave is near. Stand at a grave to pray.");

        UUID pid = player.getUUID();
        String godId = saint.godId();
        God god = GodRegistry.get(godId);
        String godName = god != null ? god.displayName() : godId;

        // Gate — a devotional act, not a free buff: FAITHFUL+ standing with the god.
        PietyTier tier = DivineFavour.tierForGod(level, pid, godId);
        if (tier.ordinal() < PietyTier.FAITHFUL.ordinal()) {
            return new Result(false, "You are not of " + godName + "'s faith — "
                    + saint.name() + " does not hear you.");
        }
        if (!saints.canPray(pid, saint.saintId(), now, PRAYER_COOLDOWN)) {
            return new Result(false, "You have already sought " + saint.name() + "'s intercession today.");
        }

        // Favour with the patron god, at the grave (sacred-amplified by its imprint).
        DivineFavour.award(level, pid, godId, DivineFavour.FavourAct.PRAYER, now, saint.gravePos());
        // A lesser saint's blessing, flavoured by the god's domain (bounded).
        if (god != null) blessing(player, god);
        // Tend the grave — refresh its (permanent) imprint so devotion keeps it strong.
        SacredSpaceSavedData.get(level).refreshNear(level, godId, saint.gravePos(), now);
        saints.recordPrayer(pid, saint.saintId(), now);

        String title = saint.canonized() ? "St. " : "the Venerable ";
        StringBuilder msg = new StringBuilder();
        msg.append(title).append(saint.name())
                .append(" interceded — ").append(godName).append("'s favour is upon you.");

        // Venerable grave → accrue veneration; the people's devotion can raise them.
        if (!saint.canonized()) {
            int v = saint.veneration() + 1;
            SaintsSavedData.Saint bumped = saint.withVeneration(v);
            saints.putSaint(bumped);
            if (v >= VENERATION_THRESHOLD) {
                Canonization.canonizeVenerable(level, bumped, now);
                msg.append(" §dThe people's devotion raises ").append(saint.name())
                        .append(" to sainthood!");
            } else {
                msg.append(" §7(veneration ").append(v).append("/").append(VENERATION_THRESHOLD).append(")");
            }
        }
        return new Result(true, msg.toString());
    }

    /** A brief, minor boon by the god's domain — a lesser blessing, never a miracle. */
    private static void blessing(ServerPlayer player, God god) {
        switch (god.domain()) {
            case SUN   -> player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, BLESSING_DURATION, 0));
            case SEA   -> player.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, BLESSING_DURATION, 0));
            case FORGE -> player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, BLESSING_DURATION, 0));
            case FATE  -> player.addEffect(new MobEffectInstance(MobEffects.LUCK, BLESSING_DURATION, 0));
        }
    }
}
