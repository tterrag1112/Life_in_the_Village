package tterrag1112.life_in_the_village.Village.Simulation;

import com.mojang.serialization.Codec;

import java.util.Locale;

/**
 * Phase 4 doc 25 — multi-category resource taxonomy used by the
 * {@link VillageSimData} production / consumption maps and by the
 * {@link KingdomEconomyEngine} partner search.
 *
 * <p>Note on relationship to {@code NeedCategory}: the existing
 * {@code NeedCategory} (FOOD / BUILDING_MATERIALS / SEEDS) tracks the
 * spot-state of a village's stockpile via {@code village.getNeeds()}.
 * That's a different concept from the rolling-average sim. The two
 * enums share names so the mapping is mechanical, but they're kept
 * separate to keep this refactor's blast radius bounded.</p>
 */
public enum ResourceCategory {

    // ── Existing (mapped from NeedCategory) ────────────────────────────────
    FOOD,
    BUILDING_MATERIALS,
    SEEDS,

    // ── Phase 4 expansion ──────────────────────────────────────────────────
    /** Iron / wood tools, plows, hammers. */
    TOOLS,
    /** Swords, bows, armor. */
    WEAPONS,
    /** Fabric, leather, raw wool. */
    CLOTH,
    /** Spices, fine goods, art. */
    LUXURY,
    /** Paper, ink, parchment. */
    PAPER,
    /** Candles, incense, sacred items. */
    LITURGICAL,
    /** Herbs, remedies. */
    MEDICINE,
    /** Live animals. */
    LIVESTOCK,
    /** Net bronze inflow from visitors / trade surplus. Non-physical;
     *  populated by the visitor-flux integration in doc 29. */
    COIN_INFLUX;

    /** True for categories that represent moveable goods. COIN_INFLUX
     *  is the only non-physical entry — bronze is a sim-side metric, not
     *  a tradable item. */
    public boolean isPhysical() {
        return this != COIN_INFLUX;
    }

    public static final Codec<ResourceCategory> CODEC = Codec.STRING.xmap(
            s -> ResourceCategory.valueOf(s.toUpperCase(Locale.ROOT)),
            ResourceCategory::name);
}
