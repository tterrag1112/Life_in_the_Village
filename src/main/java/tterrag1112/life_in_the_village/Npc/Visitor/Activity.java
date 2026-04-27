package tterrag1112.life_in_the_village.Npc.Visitor;

import com.mojang.serialization.Codec;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 4 doc 29 — what a visitor does at each stop on their
 * itinerary. v1 ships an "atomic" handler per activity in
 * {@link VisitorGoal} that:
 *
 * <ol>
 *   <li>Walks the visitor to the target {@code BlockPos}.</li>
 *   <li>Pays the activity-specific bronze flat into the building's
 *   treasury / NPC's wallet.</li>
 *   <li>Idles for a short duration before advancing the itinerary.</li>
 * </ol>
 *
 * <p>Per-type behaviour (PRAY kneels at the altar, MINSTREL boosts
 * mood +5 to nearby NPCs, ENVOY hands a sealed letter) is Phase 5
 * polish — the activity tag stays so when the polish lands it routes
 * via this enum.</p>
 */
public enum Activity {
    PRAY,
    STAY,
    EAT,
    TRADE,
    SHOP,
    ATTEND_LESSON,
    DELIVER_MESSAGE,
    PERFORM,
    SELL_GOODS;

    /** Building types that satisfy this activity. The visitor flux
     *  engine queries this when planning an itinerary so a PILGRIM
     *  spawning in a temple-less village is dropped onto a SHRINE
     *  or CHAPEL fallback. */
    public Set<BuildingType> targetBuildings() {
        return switch (this) {
            case PRAY            -> EnumSet.of(BuildingType.TEMPLE, BuildingType.CHAPEL,
                                               BuildingType.SHRINE);
            case STAY            -> EnumSet.of(BuildingType.INN);
            case EAT             -> EnumSet.of(BuildingType.INN, BuildingType.BAKERY);
            case TRADE, SHOP, SELL_GOODS -> EnumSet.of(BuildingType.MARKET, BuildingType.STOCKPILE);
            case ATTEND_LESSON   -> EnumSet.of(BuildingType.LIBRARY,
                                               BuildingType.SCHOLARS_RETREAT);
            case DELIVER_MESSAGE -> EnumSet.of(BuildingType.TOWN_HALL);
            case PERFORM         -> EnumSet.of(BuildingType.TOWN_SQUARE, BuildingType.INN);
        };
    }

    /** Spec lines 168-172 — bronze flat the visitor pays at this
     *  activity's location. */
    public long bronzeCost() {
        return switch (this) {
            case PRAY            -> 5L;
            case STAY            -> 8L;
            case EAT             -> 3L;
            case TRADE, SHOP     -> 10L;
            case ATTEND_LESSON   -> 6L;
            case DELIVER_MESSAGE -> 0L;
            case PERFORM         -> 0L;   // minstrel COLLECTS tips, doesn't pay
            case SELL_GOODS      -> 0L;
        };
    }

    /** Per-stop dwell time in ticks. Cheap activities (EAT) are
     *  fast; STAY at an inn covers a full night. */
    public int defaultDurationTicks() {
        return switch (this) {
            case EAT             -> 600;       // 30s
            case PRAY            -> 1200;      // 1 minute
            case ATTEND_LESSON   -> 2400;      // 2 minutes
            case TRADE, SHOP     -> 800;       // 40s
            case STAY            -> 12000;     // half a day
            case PERFORM         -> 4800;      // 4 minutes
            case SELL_GOODS      -> 24000;     // full day stall
            case DELIVER_MESSAGE -> 1200;      // 1 minute
        };
    }

    public static final Codec<Activity> CODEC = Codec.STRING.xmap(
            s -> Activity.valueOf(s.toUpperCase(Locale.ROOT)),
            Activity::name);
}
