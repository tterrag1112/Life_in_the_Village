package tterrag1112.life_in_the_village.Npc.Apprentice;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import java.util.Optional;

/**
 * Static helper for the +50% NPC and +10% player mentorship XP
 * bonuses (spec lines 128-130 and 246-248).
 *
 * <p>For NPCs the bonus applies only when the master is co-located
 * at the workshop with the apprentice — see
 * {@link ApprenticeshipManager#mentorshipMultiplierFor}.</p>
 *
 * <p>For players the bonus applies only when the player stands
 * within {@link #PLAYER_MENTORSHIP_RADIUS} blocks of their master.
 * Existing player-XP grant call-sites that opt in get the bump;
 * the helper does not refactor every {@code addXp} caller.</p>
 */
public final class MentorshipBonus {

    /** Spec line 130 multiplier for NPC apprentices. */
    public static final float NPC_MENTORSHIP_MULTIPLIER = 1.5f;
    /** Spec line 248 multiplier for player apprentices. */
    public static final float PLAYER_MENTORSHIP_MULTIPLIER = 1.1f;
    /** Spec line 248 distance the player must be within. */
    public static final double PLAYER_MENTORSHIP_RADIUS = 32.0;

    private MentorshipBonus() {}

    /**
     * Returns {@link #PLAYER_MENTORSHIP_MULTIPLIER} when the player
     * has an active apprenticeship contract and stands within the
     * radius of their master; else {@code 1.0f}.
     */
    public static float playerMultiplierFor(ServerPlayer player, ServerLevel level) {
        if (player == null || level == null) return 1f;
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(level);
        Optional<ApprenticeshipContract> opt = reg.getByApprentice(player.getUUID());
        if (opt.isEmpty() || !opt.get().apprenticeIsPlayer()) return 1f;
        ApprenticeshipContract c = opt.get();
        if (c.masterIsPlayer()) return 1f; // player-player out of scope
        Optional<TownspersonMob> master = TownspersonMob.findByUUID(level, c.masterId());
        if (master.isEmpty()) return 1f;
        if (player.distanceToSqr(master.get())
                > PLAYER_MENTORSHIP_RADIUS * PLAYER_MENTORSHIP_RADIUS) return 1f;
        return PLAYER_MENTORSHIP_MULTIPLIER;
    }

    /** True when the supplied actor is currently an active apprentice. */
    public static boolean isApprentice(java.util.UUID actorId, ServerLevel level) {
        return ApprenticeshipSavedData.get(level).getByApprentice(actorId).isPresent();
    }

    /**
     * Player-XP grant scaler — caller passes raw XP and this returns
     * the post-mentorship amount. Existing PlayerProfessionData.addXp
     * call sites can wrap their amount through this to opt in.
     */
    public static float scalePlayerXp(float baseXp, ServerPlayer player, ServerLevel level) {
        return baseXp * playerMultiplierFor(player, level);
    }

    /**
     * NPC mentorship multiplier from <em>any</em> source — combines the
     * apprenticeship master-presence bonus (Phase 2 task 16) with the
     * elderly-mentor presence bonus (Phase 2 task 15). Returns the
     * largest multiplier for which the mentor is currently co-located.
     *
     * <p>Returns {@link #NPC_MENTORSHIP_MULTIPLIER} when either:</p>
     * <ul>
     *   <li>The NPC is an active apprentice and their master is at
     *       the same workshop within range, or</li>
     *   <li>An ELDERLY mentor lists this NPC as their {@code mentorTargetId}
     *       and is within range.</li>
     * </ul>
     */
    public static float npcMentorshipFor(TownspersonMob apprentice, ServerLevel level) {
        if (apprentice == null || level == null) return 1f;
        // Apprenticeship path.
        float apprenticeBonus = ApprenticeshipManager.mentorshipMultiplierFor(apprentice, level);
        if (apprenticeBonus > 1f) return apprenticeBonus;
        // Elderly-mentor path: scan loaded NPCs for a mentor whose
        // RetirementState targets this NPC and who is co-located.
        for (var entity : level.getEntitiesOfClass(TownspersonMob.class,
                apprentice.getBoundingBox().inflate(16.0),
                m -> m != apprentice && m.isElderly())) {
            var rs = entity.getRetirementState();
            if (rs.mentorTargetId().map(id -> id.equals(apprentice.getUUID())).orElse(false)) {
                return NPC_MENTORSHIP_MULTIPLIER;
            }
        }
        return 1f;
    }
}
