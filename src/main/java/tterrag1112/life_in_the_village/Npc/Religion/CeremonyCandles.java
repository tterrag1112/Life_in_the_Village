package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * Religion Rework R4b — the temple GOODS loop (distinct from the R4a money hub).
 * The priest already produces {@code WHITE_CANDLE} into the building's item
 * storage; this is the one place that <em>consumes</em> it: a village-wide
 * ceremony burns a modest number of candles from its venue building's stock.
 *
 * <p>Consumption is literal but never a hard block — a temple that is out of
 * candles still holds the ceremony, just <b>dim</b> ({@link #DIM_MULTIPLIER}
 * applied to the ceremony's effect, composed on top of the existing
 * {@link RiteProfile}/{@link ReligionContent} scaling). A healthily-producing
 * temple stays lit; a neglected/under-producing one runs dim.</p>
 */
public final class CeremonyCandles {

    private CeremonyCandles() {}

    /** Effect multiplier when the ceremony is short on candles (a dimmer rite). */
    public static final float DIM_MULTIPLIER = 0.6f;

    /** The lighting outcome of a ceremony: its effect multiplier + whether the
     *  venue had the full candle cost. */
    public record Lighting(float effectMultiplier, boolean lit) {}

    /** Candle cost for a ceremony, scaled to its size (a grand festival burns
     *  more than a routine holy day). Non-communal / personal rites cost 0 (no
     *  consumption this phase). */
    public static int candleCost(Rite rite) {
        return switch (rite) {
            case GRAND_FESTIVAL                                  -> 4;
            case HARVEST_THANKSGIVING, SIGNATURE_RITE, CONSECRATION -> 2;
            case FEAST_DAY, VIGIL                                -> 1;
            default                                              -> 0;
        };
    }

    /**
     * Consumes up to {@code cost} white candles from the ceremony's venue
     * building (resolved from {@code venue} via {@link BuildingFaith}). Returns
     * full {@link Lighting} (×1.0) when the full cost was in stock, else dim
     * (×{@link #DIM_MULTIPLIER}); whatever partial stock exists is still burned.
     * A {@code cost ≤ 0} or an unresolvable venue is treated as lit (no penalty).
     */
    public static Lighting light(ServerLevel level, Village village, BlockPos venue, int cost) {
        if (level == null || village == null || venue == null || cost <= 0) {
            return new Lighting(1f, true);
        }
        Building building = BuildingFaith.buildingIdAtLocation(level, village, venue)
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id)).orElse(null);
        if (building == null) return new Lighting(1f, true); // no venue building → don't penalize

        int have = BuildingStorageAccess.countItem(level, building, Items.WHITE_CANDLE);
        int take = Math.min(have, cost);
        if (take > 0) BuildingStorageAccess.takeItem(level, building, Items.WHITE_CANDLE, take);
        boolean lit = have >= cost;
        return new Lighting(lit ? 1f : DIM_MULTIPLIER, lit);
    }
}
