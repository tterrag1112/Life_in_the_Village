package tterrag1112.life_in_the_village.Village.Event;

/**
 * Coarse grouping of {@link VillageEvent.EventType} values, matching
 * {@code docs/npc_redesign/32-events-expanded.md} § Data model.
 *
 * <p>Used by the scheduler / debug commands / UI to filter and label
 * events. Each {@code EventType} reports its category via
 * {@link VillageEvent.EventType#category()}.</p>
 */
public enum EventCategory {
    SEASONAL_FESTIVAL,
    RELIGIOUS_RITE,
    LIFE_STAGE_RITE,
    ECONOMIC,
    CIVIC,
    CULTURAL,
    NOTABLE_VISIT,
    CRISIS
}
