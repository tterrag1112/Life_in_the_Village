package tterrag1112.life_in_the_village.Village.Decoration.StreetFurniture;

import tterrag1112.life_in_the_village.Village.Decoration.Framework.AnchorRule;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationProfile;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationProfileRegistry;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationTag;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * B2.2 — registers doc-05's "kit contents (per culture)" against
 * {@link DecorationProfileRegistry} for the {@code "default"}
 * culture only. Future cultures register their own pieces (the
 * registry's culture-fallback chain handles culture-missing
 * variants by deferring to default).
 *
 * <p>Doc 05 §"Tier gating":
 * <ul>
 *   <li>HAMLET — bench, planter, signpost only.</li>
 *   <li>VILLAGE — adds lampposts and notice boards.</li>
 *   <li>TOWN — adds handcarts, woodpiles, wells.</li>
 *   <li>CITY — adds shrines, communal ovens, mounting blocks.</li>
 * </ul>
 *
 * <p>Slot-tag mapping per doc 05 §"Placement":</p>
 * <ul>
 *   <li>{@code ROAD_SIDE_SMALL} — benches, planters, hitching
 *       posts, mounting blocks, water troughs, signposts.</li>
 *   <li>{@code ROAD_SIDE_LARGE} — lampposts, notice boards,
 *       handcarts, woodpiles.</li>
 *   <li>{@code BUILDING_GAP} — stacked crates, shrines, small wells.</li>
 *   <li>{@code FACADE_ORNAMENT} — planters (also placed roadside),
 *       lanterns.</li>
 * </ul>
 *
 * <p>NBT files for each piece are user-authored and live at
 * {@code structures/{culture}/decoration/street/{name}.nbt}. The
 * registry survives missing NBTs gracefully — {@code DecorationPass}
 * logs "NBT not found" and burns the slot, mirroring the legacy
 * behaviour from before B2.2 when there was no content at all.</p>
 */
public final class StreetFurnitureProfiles {

    private static final String CULTURE = DecorationProfileRegistry.DEFAULT_CULTURE;
    private static boolean registered = false;

    private StreetFurnitureProfiles() {}

    /** Idempotent — re-registration replaces prior entries with a
     *  warning logged by the registry. Called from common setup. */
    public static void registerDefaults() {
        if (registered) return;
        registered = true;
        DecorationProfileRegistry r = DecorationProfileRegistry.INSTANCE;

        // ── HAMLET tier — minimal subset ────────────────────────────────
        r.register(small("bench",          VillageSizeTier.HAMLET, 12, 6, 3));
        r.register(small("planter",        VillageSizeTier.HAMLET, 10, 6, 3));
        r.register(small("signpost",       VillageSizeTier.HAMLET, 8,  4, 2));

        // ── VILLAGE tier — adds lampposts and notice boards ────────────
        r.register(large("lamppost",       VillageSizeTier.VILLAGE, 14, 7, 4));
        // Plaza notice board uses its own tag; tier gates at VILLAGE so
        // hamlets don't carry a civic noticeboard (they use word of
        // mouth).
        r.register(noticeBoard());

        // ── TOWN tier — adds handcarts, woodpiles, wells ───────────────
        r.register(large("handcart",       VillageSizeTier.TOWN, 8, 4, 2));
        r.register(large("woodpile",       VillageSizeTier.TOWN, 8, 4, 2));
        r.register(buildingGap("well_variant", VillageSizeTier.TOWN, 10, 5, 3, 4));

        // Doc 05 small-pieces extension at TOWN tier (more variety).
        r.register(small("hitching_post",  VillageSizeTier.TOWN, 6,  3, 2));
        r.register(small("water_trough",   VillageSizeTier.TOWN, 6,  3, 2));

        // ── CITY tier — adds shrines, communal ovens, mounting blocks ──
        r.register(buildingGap("small_shrine", VillageSizeTier.CITY, 9, 5, 3, 3));
        r.register(buildingGap("communal_oven",VillageSizeTier.CITY, 7, 4, 2, 4));
        r.register(small("mounting_block", VillageSizeTier.CITY, 5, 3, 2));
        // Stacked crates as additional building-gap flavour.
        r.register(buildingGap("stacked_crates", VillageSizeTier.HAMLET, 6, 3, 2, 2));
    }

    // ── Helpers — tag mappings + sensible defaults ──────────────────────

    private static DecorationProfile small(String name, VillageSizeTier minTier,
                                           int primary, int secondary, int backfill) {
        return profile("street/" + name,
                EnumSet.of(DecorationTag.ROAD_SIDE_SMALL),
                EnumSet.noneOf(DecorationTag.class),
                primary, secondary, backfill,
                /* footprint */ 1,
                AnchorRule.ON_GROUND, minTier);
    }

    private static DecorationProfile large(String name, VillageSizeTier minTier,
                                           int primary, int secondary, int backfill) {
        return profile("street/" + name,
                EnumSet.of(DecorationTag.ROAD_SIDE_LARGE),
                EnumSet.noneOf(DecorationTag.class),
                primary, secondary, backfill,
                /* footprint */ 2,
                AnchorRule.ON_GROUND, minTier);
    }

    private static DecorationProfile buildingGap(String name, VillageSizeTier minTier,
                                                 int primary, int secondary, int backfill,
                                                 int footprint) {
        return profile("street/" + name,
                EnumSet.of(DecorationTag.BUILDING_GAP),
                EnumSet.noneOf(DecorationTag.class),
                primary, secondary, backfill,
                footprint,
                AnchorRule.ON_GROUND, minTier);
    }

    private static DecorationProfile noticeBoard() {
        // PLAZA_NOTICE_BOARD slot is emitted on the south edge of the
        // town square (DecorationSlotEmitter §A.7). One per village.
        // VILLAGE+ tier — hamlets don't run a civic noticeboard.
        // Footprint=1 because the sign-based render (B2.2) is a single
        // block; a future custom BlockEntity may grow this.
        return profile("sign/notice_board",
                EnumSet.of(DecorationTag.PLAZA_NOTICE_BOARD),
                EnumSet.noneOf(DecorationTag.class),
                15, 8, 4,
                /* footprint */ 1,
                AnchorRule.ON_GROUND, VillageSizeTier.VILLAGE);
    }

    private static DecorationProfile profile(String suffix,
                                             Set<DecorationTag> required,
                                             Set<DecorationTag> preferred,
                                             int primary, int secondary, int backfill,
                                             int footprint,
                                             AnchorRule anchor,
                                             VillageSizeTier minTier) {
        return new DecorationProfile(
                CULTURE + "/" + suffix,
                required, preferred,
                primary, secondary, backfill,
                footprint,
                CULTURE,
                Optional.empty(),
                anchor,
                minTier);
    }
}
