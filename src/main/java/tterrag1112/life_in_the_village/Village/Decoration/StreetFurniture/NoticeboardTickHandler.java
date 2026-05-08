package tterrag1112.life_in_the_village.Village.Decoration.StreetFurniture;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * B2.2 — slow-tick refresh of village noticeboards.
 *
 * <p>NPC infrastructure (laws, request board, kingdom decrees) does
 * not yet emit a "content changed" event today, so the simplest
 * correct refresh is a periodic poll. Once-per-day is plenty —
 * laws change rarely, requests churn at minute timescales but
 * showing yesterday's count for at most one in-game day is fine.</p>
 *
 * <p>When B2.5+ adds explicit {@code LawEnacted}, {@code RequestPosted},
 * etc. events to {@code NpcLifeEventBus}, this handler should subscribe
 * to those instead and call {@link NoticeboardWriter#writeForVillage}
 * directly. The 24000-tick poll is a stopgap.</p>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class NoticeboardTickHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(NoticeboardTickHandler.class);

    /** One in-game day. Phased against game time so the refresh
     *  triggers at sunrise. */
    public static final long REFRESH_INTERVAL_TICKS = 24000L;

    private NoticeboardTickHandler() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;
        long tick = level.getGameTime();
        // Trigger once per period at sunrise. Tick 0 also matches —
        // intentional, gives noticeboards their initial content
        // shortly after world load.
        if (tick % REFRESH_INTERVAL_TICKS != 0L) return;

        VillageSavedData data = VillageSavedData.get(level);
        int villages = 0;
        int totalSigns = 0;
        for (Village v : data.getAllVillages()) {
            if (v == null) continue;
            int written = NoticeboardWriter.writeForVillage(level, v, data);
            if (written > 0) {
                villages++;
                totalSigns += written;
            }
        }
        if (villages > 0) {
            LOGGER.debug("NoticeboardTickHandler: refreshed {} signs across "
                    + "{} villages at tick {}", totalSigns, villages, tick);
        }
    }
}
