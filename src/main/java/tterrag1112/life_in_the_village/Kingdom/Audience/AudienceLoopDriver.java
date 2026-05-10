package tterrag1112.life_in_the_village.Kingdom.Audience;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

/**
 * Track D3.5A — daily audience-loop sweep. Two responsibilities:
 *
 * <ol>
 *   <li><b>Petition lifecycle:</b> expires PENDING petitions older
 *       than {@link Petition#DEFAULT_EXPIRY_TICKS}; GCs resolved
 *       entries older than {@link Kingdom#PETITION_RETENTION_TICKS}.
 *       Fires {@link KingdomEvent.PetitionResolved} with outcome
 *       "EXPIRED" for each newly-expired petition so newsfeed
 *       subscribers can surface the timeout.</li>
 *   <li><b>Standing decay:</b> each player standing moves 1 step
 *       toward 0 per
 *       {@link PlayerStanding#DECAY_INTERVAL_TICKS}. Decay only
 *       applied when the interval is met; partial-day catchup is
 *       computed inside {@link PlayerStanding#applyDecay}.</li>
 * </ol>
 *
 * <p>Runs at priority 195 (immediately after province daily) so
 * province-tied standing changes from today's events feed in
 * before the decay step.
 */
public final class AudienceLoopDriver {

    private static final Logger LOGGER = LogUtils.getLogger();

    private AudienceLoopDriver() {}

    public static void dailyTick(ServerLevel level, VillageSavedData data, long tick) {
        int totalSwept = 0;
        for (Kingdom k : data.getAllKingdoms()) {
            // Fire EXPIRED events for each pending petition past
            // its TTL, before sweepPetitions mutates them.
            for (Petition p : k.getPetitions()) {
                if (p.isPending() && p.isExpiredAt(tick)) {
                    KingdomEventBus.fire(new KingdomEvent.PetitionResolved(
                            k.getId(), p.id(), p.playerUuid(),
                            p.kind().name(), "EXPIRED", 0, tick));
                }
            }
            totalSwept += k.sweepPetitions(tick);
            k.decayPlayerStandings(tick);
        }
        if (totalSwept > 0) {
            LOGGER.debug("[AudienceLoopDriver] daily sweep: {} petitions swept",
                    totalSwept);
        }
    }
}
