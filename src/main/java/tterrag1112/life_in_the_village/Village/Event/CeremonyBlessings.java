package tterrag1112.life_in_the_village.Village.Event;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Npc.Religion.Rite;
import tterrag1112.life_in_the_village.Npc.Religion.RiteScheduler;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Religion Rework R2a — the single coordination point that attaches a
 * ceremony's <em>blessing rite</em> to its gathering ({@link VillageEvent}).
 *
 * <p>Before R2a a wedding fired BOTH a {@code VillageEvent.WEDDING} (via
 * {@code EventLifeEventProducer}) AND an independent {@code Rite.MARRIAGE}
 * (via {@code RiteLifeEventProducer}); holy days double-fired a cultural
 * event AND a religion-calendar rite. Both pairs ran uncoordinated. Now the
 * gathering is the owner and the rite is its blessing-extension: whenever a
 * gathering with a religious counterpart is created, {@link #attach} schedules
 * the linked rite (vacant presider, co-timed to the gathering start, gated by
 * the village religion's {@code ritualises} filter) and records the rite id in
 * the gathering's {@code eventData} under {@link #RITE_ID_KEY}.</p>
 *
 * <p>Wired into the two creation methods of {@code VillageEventScheduler}
 * ({@code scheduleEvent} for festival / holy-day / random gatherings,
 * {@code scheduleLifeEvent} for life-event gatherings), so every gathering is
 * blessed at creation — there is no second producer. Gathering types with no
 * religious counterpart (MARKET_DAY, crises, OFFICE_INAUGURATION…) map to no
 * rite and are untouched.</p>
 */
public final class CeremonyBlessings {

    /** {@code eventData} key linking a gathering to its blessing rite id. */
    public static final String RITE_ID_KEY = "riteId";

    private CeremonyBlessings() {}

    /**
     * The blessing {@link Rite} for a gathering type, if it has one. Life-event
     * ceremonies map to their life rite; the seasonal harvest festival and the
     * per-culture holy days map to their thanksgiving / feast rite. Everything
     * else (markets, civic, crises, cultural) has no blessing.
     */
    public static Optional<Rite> blessingRiteFor(VillageEvent.EventType type) {
        return switch (type) {
            case WEDDING          -> Optional.of(Rite.MARRIAGE);
            case FUNERAL          -> Optional.of(Rite.FUNERAL);
            case NAMING_CEREMONY  -> Optional.of(Rite.NAMING);
            case COMING_OF_AGE    -> Optional.of(Rite.COMING_OF_AGE);
            case HARVEST_FESTIVAL -> Optional.of(Rite.HARVEST_THANKSGIVING);
            // R3b-1 baseline enrichment: the Sunstead solar equinox is an
            // agrarian high holy day → the fuller HARVEST_THANKSGIVING (mood +
            // piety + treasury), not a plain feast. The other faiths' holy days
            // keep FEAST_DAY, distinguished per-faith by their ReligionContent
            // profile (scale + flavor).
            case SUNSTEAD_EQUINOX -> Optional.of(Rite.HARVEST_THANKSGIVING);
            case LOOM_THREADING, TIDECALL_FULL_MOON, FORGE_CREED_KINGDOM_DAY
                                  -> Optional.of(Rite.FEAST_DAY);
            // R3b-2 — communal gatherings whose blessing is their own rite.
            case VIGIL            -> Optional.of(Rite.VIGIL);
            case PURIFICATION     -> Optional.of(Rite.PURIFICATION);
            // R3b-3 — the four named signature gatherings share one blessing
            // rite, differentiated by ReligionContent (per the village's faith).
            case FIRST_FURROW, THREAD_BINDING, VOYAGE_BLESSING, ANCESTOR_OATH
                                  -> Optional.of(Rite.SIGNATURE_RITE);
            // R3d-2 — the four grand annual festivals share one (richer)
            // blessing rite, likewise differentiated by ReligionContent.
            case HARVEST_HOME, GREAT_WEAVING, TIDES_RETURN, FOUNDING_DAY
                                  -> Optional.of(Rite.GRAND_FESTIVAL);
            default               -> Optional.empty();
        };
    }

    /**
     * Attaches the blessing rite for {@code event}, if its type has one and the
     * village religion ritualises it. Idempotent per gathering — re-running on
     * an already-linked gathering is a no-op. Safe to call for any event;
     * non-ceremony types simply do nothing.
     */
    public static void attach(ServerLevel level, Village village, VillageEvent event) {
        if (level == null || village == null || event == null) return;
        if (event.getEventData().containsKey(RITE_ID_KEY)) return; // already linked

        Optional<Rite> rite = blessingRiteFor(event.getType());
        if (rite.isEmpty()) return;

        Optional<UUID> riteId = RiteScheduler.scheduleBlessingRite(
                level, village, rite.get(), participantsFor(event), event.getStartTick());
        riteId.ifPresent(id -> event.putEventData(RITE_ID_KEY, id.toString()));
    }

    /**
     * Rite participants drawn from the gathering: the primary subject plus the
     * required attendees, de-duplicated. For a wedding that is {subject,
     * spouse}; for a funeral {deceased, mourner}; for a village-wide festival /
     * holy day it is empty (attendance is computed at execute time).
     */
    private static List<UUID> participantsFor(VillageEvent event) {
        Set<UUID> ordered = new LinkedHashSet<>();
        event.getPrimarySubjectId().ifPresent(ordered::add);
        ordered.addAll(event.getRequiredAttendees());
        return new ArrayList<>(ordered);
    }
}
