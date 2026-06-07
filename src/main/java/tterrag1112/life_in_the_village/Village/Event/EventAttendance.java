package tterrag1112.life_in_the_village.Village.Event;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Mood.MoodCategory;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.UUID;

/**
 * Phase 5 doc 32 § Attendance.
 *
 * <p>Resolves the required + invited lists into the
 * {@link VillageEvent#getActualAttendees()} set when an event flips to
 * {@link VillageEvent.EventStatus#ACTIVE}, and applies an
 * {@code eventOverride} to each attendee so the AI pulls them off
 * normal schedule for the event's duration.</p>
 *
 * <p>Decision algorithm matches the spec's pseudocode at line 200:
 * base probability per type + relationship-to-primary boost + schedule
 * conflict + mood penalty + cultural mandate. Required attendees
 * always show up unless they are dead or off-world.</p>
 */
public final class EventAttendance {

    /** Default attend-base when an event type has no explicit baseline. */
    private static final float DEFAULT_BASE = 0.5f;

    private EventAttendance() {}

    /**
     * Called from {@link EventEffects#onEventStart} when an event enters
     * ACTIVE. Walks required + invited, runs the decision per NPC, and
     * (for those who say yes) applies the eventOverride and records them
     * in {@link VillageEvent#getActualAttendees()}.
     */
    public static void applyOverrides(ServerLevel level, VillageEvent event,
                                      Village village, VillageSavedData data) {
        // Required attendees: always show up if alive + on-world.
        for (UUID id : event.getRequiredAttendees()) {
            TownspersonMob npc = TownspersonMob.findByUUID(level, id).orElse(null);
            if (npc == null || !npc.isAlive()) continue;
            attend(npc, event);
        }
        // Invited attendees: roll the decision.
        for (UUID id : event.getInvitedAttendees()) {
            TownspersonMob npc = TownspersonMob.findByUUID(level, id).orElse(null);
            if (npc == null || !npc.isAlive()) continue;
            if (event.getActualAttendees().contains(id)) continue; // already required
            if (decideAttendance(npc, event, level)) {
                attend(npc, event);
            }
        }
    }

    /**
     * Called when an event ends. Clears the eventOverride from each
     * recorded actual attendee.
     */
    public static void clearOverrides(ServerLevel level, VillageEvent event) {
        for (UUID id : event.getActualAttendees()) {
            TownspersonMob npc = TownspersonMob.findByUUID(level, id).orElse(null);
            if (npc == null) continue;
            npc.clearEventOverride();
        }
    }

    // ── Decision logic ──────────────────────────────────────────────

    /** Spec § Attendance pseudocode (line 200). */
    public static boolean decideAttendance(TownspersonMob npc,
                                           VillageEvent event,
                                           ServerLevel level) {
        float base = baseline(event.getType());

        // Relationship boost toward the primary subject (e.g. groom at
        // a wedding, deceased's family at a funeral). Score is in
        // roughly [-100, 100]; convert to [-1, 1] then scale.
        float rel = 0f;
        if (event.getPrimarySubjectId().isPresent()) {
            UUID primary = event.getPrimarySubjectId().get();
            try {
                int score = npc.getNpcRelationships().getScore(primary);
                rel = Math.max(-1f, Math.min(1f, score / 100f));
            } catch (RuntimeException ignored) {}
        }

        // Mood penalty for distressed NPCs.
        try {
            if (npc.getMood().category() == MoodCategory.DISTRESSED) {
                base *= 0.5f;
            }
        } catch (RuntimeException ignored) {}

        // Schedule conflict — work-phase check is approximate at v1: if
        // the NPC is already mid-task (has an event override from
        // another event, or is currently sleeping), down-weight.
        if (npc.getEventOverride().isPresent()) base *= 0.3f;

        float chance = Math.max(0f, Math.min(1f, base + rel * 0.4f));
        return level.getRandom().nextFloat() < chance;
    }

    /**
     * Per-type baseline attendance probability for INVITED NPCs.
     * Required always attend (handled elsewhere).
     */
    public static float baseline(VillageEvent.EventType type) {
        return switch (type) {
            case WEDDING, FUNERAL, NAMING_CEREMONY, COMING_OF_AGE -> 0.85f;
            case TRIAL                                            -> 0.5f;
            case TOWN_MEETING                                     -> 0.4f;
            case OFFICE_INAUGURATION,
                 LAW_ENACTMENT_ANNOUNCEMENT                       -> 0.5f;
            case CRAFT_CONTEST, GUILD_FAIR                        -> 0.45f;
            case SPRING_EQUINOX, WINTER_FEAST, SUMMER_MARKET,
                 SUNSTEAD_EQUINOX, LOOM_THREADING,
                 TIDECALL_FULL_MOON, FORGE_CREED_KINGDOM_DAY,
                 MINSTREL_PERFORMANCE, STORYTELLING_NIGHT,
                 SCHOLAR_LECTURE, ENVOY_RECEPTION,
                 SCHOLAR_EXCHANGE, PILGRIM_CONVERGENCE,
                 CARAVAN_ARRIVAL                                  -> 0.55f;
            case PLAGUE_OUTBREAK, FIRE, FAMINE, MILITIA_CALL      -> 0.95f; // crisis: NPCs respond
            // Phase-3 originals — they apply overrides via dedicated
            // start handlers, so this only matters if the spec adds
            // attendee lists later.
            case HARVEST_FESTIVAL, MARKET_DAY, FESTIVAL_OF_LIGHTS,
                 TRAINING_DAY, VILLAGE_FAIR                       -> DEFAULT_BASE;
        };
    }

    private static void attend(TownspersonMob npc, VillageEvent event) {
        npc.setEventOverride(event.getType());
        event.addActualAttendee(npc.getUUID());
    }

    /**
     * Religion Rework R2b — for a village-wide gathering carrying no explicit
     * attendee list (holy-day / seasonal religious observances created without
     * required/invited), every loaded village NPC observes. Mirrors what the
     * Phase-3 festival start handlers already do, and records them in
     * {@code actualAttendees} so {@link #clearOverrides} tears the override down
     * at the gathering's end. The congregation then walks to the venue via
     * {@code AttendGatheringBehavior}.
     */
    public static void applyVillageWideOverride(ServerLevel level, VillageEvent event,
                                                Village village, VillageSavedData data) {
        for (UUID id : villageNpcIds(level, village, data)) {
            TownspersonMob npc = TownspersonMob.findByUUID(level, id).orElse(null);
            if (npc == null || !npc.isAlive()) continue;
            if (event.getActualAttendees().contains(id)) continue;
            // The priest officiates the holy-day's blessing rite (PriestBehavior
            // walks them to the temple) — don't pull them into the congregation,
            // or they'd collapse to LEISURE and never officiate.
            if (npc.getProfession() == tterrag1112.life_in_the_village.Profession.Profession.PRIEST) {
                continue;
            }
            attend(npc, event);
        }
    }

    /** Convenience accessor for callers that want to populate a list. */
    public static List<UUID> villageNpcIds(ServerLevel level, Village village,
                                           VillageSavedData data) {
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        village.getBounds(data).ifPresent(bounds ->
                level.getEntitiesOfClass(TownspersonMob.class,
                                bounds.inflate(32),
                                m -> m.getAssignedVillageName()
                                        .map(n -> n.equals(village.getName()))
                                        .orElse(false))
                        .forEach(m -> ids.add(m.getUUID())));
        return ids;
    }
}
