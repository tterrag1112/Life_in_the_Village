package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.UUID;

/**
 * Religion Rework R9d — the player piety <b>payoff</b>: a higher-piety player is
 * regarded more warmly by co-religionist NPCs. The bonus is bounded and scales
 * by the player's {@link PietyTier}, and is faith-aware (only NPCs sharing the
 * player's primary faith warm to them).
 *
 * <p>This is the "piety does something" the design promised: it reuses the
 * existing player↔NPC relationship channel
 * ({@code TownspersonMob.getRelationships().adjust}) rather than a new mechanism,
 * and is applied by the religious player verbs (R9d {@code AttendRite} /
 * {@code CommissionRite}, and adoptable by the shipped offering/blessing verbs)
 * as the public act of devotion that earns the regard.</p>
 */
public final class PietyPayoff {

    private PietyPayoff() {}

    /** Relationship points granted per piety tier above UNAFFILIATED. */
    private static final int REGARD_PER_TIER = 2;     // FAITHFUL +2, DEVOUT +4, PIOUS +6
    /** Cap on how many co-religionists one act warms (anti-spam / bounded). */
    private static final int MAX_REGARD_TARGETS = 6;
    /** Scan inflation around the village bounds. */
    private static final double SCAN_INFLATE = 32;

    /**
     * The bounded same-faith relationship regard for a piety tier.
     * UNAFFILIATED 0, FAITHFUL 2, DEVOUT 4, PIOUS 6.
     */
    public static int regardBonus(PietyTier tier) {
        return tier == null ? 0 : tier.ordinal() * REGARD_PER_TIER;
    }

    /**
     * Warms loaded co-religionist villagers (NPCs whose primary faith matches
     * {@code playerFaith}) toward the player by {@link #regardBonus(PietyTier)},
     * capped at {@link #MAX_REGARD_TARGETS}. No-op for an unaffiliated player or
     * a zero-bonus tier. Returns how many NPCs were warmed (for feedback/testing).
     */
    public static int applyCoReligionistRegard(ServerLevel level, ServerPlayer player,
                                               Village village, VillageSavedData data,
                                               String playerFaith, PietyTier tier) {
        int bonus = regardBonus(tier);
        if (bonus <= 0 || playerFaith == null || village == null) return 0;
        AABB bounds = village.getBounds(data).map(b -> b.inflate(SCAN_INFLATE)).orElse(null);
        if (bounds == null) return 0;

        UUID playerId = player.getUUID();
        int warmed = 0;
        for (TownspersonMob m : level.getEntitiesOfClass(TownspersonMob.class, bounds,
                npc -> npc.isAlive() && !npc.isVisitor())) {
            if (warmed >= MAX_REGARD_TARGETS) break;
            if (m.getPiety().primaryReligion().map(playerFaith::equals).orElse(false)) {
                m.getRelationships().adjust(playerId, bonus);
                warmed++;
            }
        }
        return warmed;
    }
}
