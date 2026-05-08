package tterrag1112.life_in_the_village.Village.Decoration.StreetFurniture;

import tterrag1112.life_in_the_village.Village.Decoration.Framework.AnchorRule;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationProfile;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationProfileRegistry;
import tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationTag;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;

import java.util.EnumSet;
import java.util.Optional;

/**
 * B2.2 — registers the welcome marker profile against the
 * {@link DecorationProfileRegistry}.
 *
 * <p>{@link tterrag1112.life_in_the_village.Village.Decoration.Framework.DecorationSlotEmitter}
 * emits a {@code WELCOME_MARKER} slot at every gate (main gate
 * endpoint + each capital gate) facing inward. This profile claims
 * those slots; the matcher's clustering check ensures one marker
 * per gate. {@code ROAD_INBOUND_EDGE} slots remain unfilled in B2.2
 * — doc 06 reserves them for the trade-road boundary system.</p>
 *
 * <p>Per the user direction: a single welcome marker piece for now
 * (HAMLET-tier). Doc 06's tier-gated variants (simple_post / arch /
 * stone_marker / gate_structure) are deferred — when authored, they
 * register here under different {@code minTier}s and the highest-
 * eligible variant per village's size tier wins via the matcher.</p>
 */
public final class WelcomeMarkerProfiles {

    private static final String CULTURE = DecorationProfileRegistry.DEFAULT_CULTURE;
    private static boolean registered = false;

    private WelcomeMarkerProfiles() {}

    public static void registerDefaults() {
        if (registered) return;
        registered = true;
        DecorationProfileRegistry r = DecorationProfileRegistry.INSTANCE;

        // Single welcome marker piece, available from HAMLET up.
        r.register(new DecorationProfile(
                CULTURE + "/sign/welcome_marker",
                EnumSet.of(DecorationTag.WELCOME_MARKER),
                EnumSet.noneOf(DecorationTag.class),
                /* primary   */ 20,
                /* secondary */ 10,
                /* backfill  */  5,
                /* footprint */  3,
                CULTURE,
                Optional.empty(),
                AnchorRule.ON_GROUND,
                VillageSizeTier.HAMLET));
    }
}
