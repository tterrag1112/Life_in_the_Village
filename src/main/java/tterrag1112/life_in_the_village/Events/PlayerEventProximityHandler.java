package tterrag1112.life_in_the_village.Events;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Event.EventEffects;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class PlayerEventProximityHandler {

    private static final Map<UUID, UUID> lastEventForPlayer =
            new HashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        // Phase 3 task 24: building presence tracker — runs every tick
        // so building enter/leave fires reliably for greeter assignment
        // (and next session's TRESPASSING crime detection). Cheap: one
        // getBuildingAt per player per tick.
        tterrag1112.life_in_the_village.Npc.BusinessFront.BuildingPresenceTracker
                .onPlayerTick(player);

        // Liveliness — keep the player's current village's chunks (and so all its
        // NPCs) loaded + ticking. Self-throttled (one reconcile per window); no new
        // per-tick scan added here.
        tterrag1112.life_in_the_village.Village.VillageChunkLoader.onPlayerTick(player);

        // Divine Layer V5 — at the favour extremes the deity MANIFESTS (rare,
        // milestone-bounded). Runs first so a wrath theophany suppresses the normal
        // curse tick below (it IS the amplified peak, not an addition).
        tterrag1112.life_in_the_village.Npc.Religion.DivineTheophany.tick(player);

        // Divine Layer V3 — a high-favour player's deity may speak (bounded by a
        // cooldown + chance inside; the per-player tick is the existing cadence).
        tterrag1112.life_in_the_village.Npc.Religion.DivineVision.tick(player);

        // Divine Layer V4 — a displeased deity visits escalating consequences
        // (omen → curse → wrath); self-gated on negative favour + a cooldown.
        tterrag1112.life_in_the_village.Npc.Religion.DivineWrath.tick(player);

        if (level.getGameTime() % 100 != 0) return; // check every 5s

        VillageSavedData data = VillageSavedData.get(level);

        for (Village village : data.getAllVillages()) {
            data.getActiveEventForVillage(village.getId())
                    .ifPresent(activeEvent -> {
                        boolean inVillage = village.getBounds(data)
                                .map(b -> b.inflate(32).contains(
                                        player.getX(), player.getY(), player.getZ()))
                                .orElse(false);

                        if (!inVillage) return;

                        // Only apply buff once per event
                        UUID lastEvent = lastEventForPlayer.get(
                                player.getUUID());
                        if (activeEvent.getId().equals(lastEvent)) return;

                        lastEventForPlayer.put(
                                player.getUUID(), activeEvent.getId());
                        EventEffects.applyPlayerBuff(player, activeEvent);
                    });
        }
    }
}
