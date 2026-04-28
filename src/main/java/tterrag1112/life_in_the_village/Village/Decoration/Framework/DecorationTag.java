package tterrag1112.life_in_the_village.Village.Decoration.Framework;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Doc 01 §"DecorationTag enum" — what kind of decoration fits in a
 * given {@link DecorationSlot}.
 *
 * <p>Parallel to {@code SlotTag} (building placement) but in a
 * separate enum so the two matchers — {@code PlacementMatcher} and
 * the eventual {@code DecorationMatcher} — can never accidentally
 * route a slot through the wrong pipeline.</p>
 *
 * <p>Codec stability: new values always append to the end. The
 * codec uses {@link StringRepresentable#fromEnum} so on-disk encoding
 * is the enum name, not the ordinal — values can be reordered or
 * deprecated without breaking saves.</p>
 */
public enum DecorationTag implements StringRepresentable {

    // ── Road-side ────────────────────────────────────────────────────────

    /** Bench, planter, small signpost. */
    ROAD_SIDE_SMALL,
    /** Lamppost, notice board, well. */
    ROAD_SIDE_LARGE,
    /** Edge of village on inbound trade road. */
    WELCOME_MARKER,

    // ── Building-adjacent ────────────────────────────────────────────────

    /** Narrow space between two buildings facing the street. */
    BUILDING_GAP,
    /** Outside-corner slot between angled neighbours. */
    BUILDING_CORNER_ACCENT,
    /** Directly-against-wall ornament (planter, lantern). */
    FACADE_ORNAMENT,

    // ── Civic-adjacent ───────────────────────────────────────────────────

    /** Near the town square; quality bias. */
    CIVIC_ACCENT,
    /** Inside a GardenPlot or TownSquare interior. */
    PARK_FEATURE,

    // ── Periphery ────────────────────────────────────────────────────────

    /** Outermost ring markers / cairns. */
    VILLAGE_BOUNDARY,
    /** Where a trade road enters the built area. */
    ROAD_INBOUND_EDGE,

    // ── Special-context ──────────────────────────────────────────────────

    /** Attached to the face of a guild hall. */
    GUILD_EMBLEM,
    /** Attached to the face of a production building. */
    TRADE_SIGN,
    /** Cemetery grave slot. */
    HEADSTONE,

    // ── Plaza sub-roles (doc 04 §"Sub-slot emission") ────────────────────
    // Always emitted alongside {@link #PARK_FEATURE}; profiles for these
    // require both. Doc 04 originally proposed a {@code PlazaSubSlot}
    // field on {@link DecorationSlot}; we instead extend the tag enum so
    // the existing tag-filter machinery handles sub-role matching with
    // no new field. Codec stability: append-only.

    /** Plaza centre — well / fountain / monument core. */
    PLAZA_FOUNTAIN,
    /** City-tier-only accent next to the central feature. */
    PLAZA_MONUMENT,
    /** Town+ tier off-axis pavilion. */
    PLAZA_GAZEBO,
    /** Perimeter bench (one slot per bench, facing inward). */
    PLAZA_BENCH,
    /** Road-facing edge notice board. */
    PLAZA_NOTICE_BOARD,
    /** Plaza corner lamppost. */
    PLAZA_LAMP,
    /** Perimeter flowerbed slot between benches. */
    PLAZA_FLOWERBED,
    /** Reserved flat slot — stays empty until the MARKET_DAY event
     *  decorator activates it. No profile is registered for this tag
     *  in Phase 1; the slot exists so events can find a deterministic
     *  position to stamp stalls into. */
    PLAZA_VENDOR_ZONE;

    public static final Codec<DecorationTag> CODEC =
            StringRepresentable.fromEnum(DecorationTag::values);

    @Override public String getSerializedName() { return name(); }
}
