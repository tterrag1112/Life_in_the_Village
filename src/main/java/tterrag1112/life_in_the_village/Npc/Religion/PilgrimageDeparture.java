package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes;
import tterrag1112.life_in_the_village.Village.Event.CeremonyBlessings;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;
import tterrag1112.life_in_the_village.Village.Travel.Pilgrimage;
import tterrag1112.life_in_the_village.Village.Travel.PilgrimageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * Religion Rework R3e-3b-2 — the autonomous resident-pilgrimage decision. When a
 * <b>grand festival</b> begins at a host village (the same hook R3e-3a uses to
 * draw visitor pilgrims), devout, locally-unserved adherents of that faith in
 * <em>route-connected</em> villages occasionally depart to attend it — converting
 * to a {@code PILGRIM} traveller via {@link PilgrimageSavedData#dispatchPilgrimage}.
 *
 * <p>Bounded + rare: event-driven (grand festivals are annual per faith), a
 * per-NPC cooldown ({@link NpcMemoryTypes#PILGRIMAGE_COOLDOWN}), and a low
 * per-adherent probability. Realized-only (it scans loaded NPCs). Triggering on
 * the festival START guarantees a live festival at the destination to attend; far
 * adherents who can't arrive in time still return gracefully with the reduced
 * (missed) boon. Distinct from the R3e-3a transient visitor pilgrims — these are
 * residents who keep their identity.</p>
 */
public final class PilgrimageDeparture {

    private PilgrimageDeparture() {}

    /** Per eligible adherent at a festival start. */
    private static final float DEPART_CHANCE = 0.25f;
    /** A long gap so a pilgrimage is a rare, significant act (~a week). */
    private static final long  COOLDOWN_TICKS = 7L * 24000L;

    public static void onGrandFestivalStart(ServerLevel level, Village host,
                                            VillageEvent event, VillageSavedData data, long now) {
        if (level == null || host == null || event == null) return;
        if (!Pilgrimage.isGrandFestival(event.getType())) return;

        String faith = event.getEventData().getOrDefault(
                CeremonyBlessings.FAITH_KEY, ReligionContent.villageReligionId(level, host));

        for (Village v : data.getAllVillages()) {
            if (v.getId().equals(host.getId())) continue;
            var route = data.getRouteBetween(v.getId(), host.getId()).orElse(null);
            if (route == null) continue; // unreachable — pilgrims need a road
            AABB bounds = v.getBounds(data).map(b -> b.inflate(32)).orElse(null);
            if (bounds == null) continue;

            for (TownspersonMob npc : level.getEntitiesOfClass(TownspersonMob.class, bounds,
                    m -> m.isAlive() && !m.isVisitor()
                            && m.getAssignedVillageName()
                                    .map(n -> n.equals(v.getName())).orElse(false))) {
                if (!eligible(level, v, npc, faith)) continue;
                if (npc.getBrain().hasMemoryValue(NpcMemoryTypes.PILGRIMAGE_COOLDOWN.get())) continue;
                if (level.getRandom().nextFloat() >= DEPART_CHANCE) continue;

                PilgrimageSavedData.get(level).dispatchPilgrimage(
                        npc, route.getRouteId(), v.getId(), host.getId(), now);
                npc.getBrain().setMemoryWithExpiry(
                        NpcMemoryTypes.PILGRIMAGE_COOLDOWN.get(), now, COOLDOWN_TICKS);
            }
        }
    }

    /** A realized, devout, locally-unserved adherent of {@code faith} not already
     *  travelling. */
    private static boolean eligible(ServerLevel level, Village home,
                                    TownspersonMob npc, String faith) {
        if (npc.getRoles().hasRole(NpcRoleTypes.PILGRIM)) return false;          // already away
        if (PilgrimageSavedData.get(level).getByPrincipal(npc.getUUID()).isPresent()) return false;
        String mine = npc.getPiety().primaryReligion().orElse(null);
        if (mine == null || !mine.equals(faith)) return false;                  // wrong faith
        PietyTier tier = npc.getPiety().primaryTier();
        if (tier != PietyTier.DEVOUT && tier != PietyTier.PIOUS) return false;  // devout enough
        return FaithReconciliation.isUnservedLocally(level, home, npc);         // unserved at home
    }
}
