package tterrag1112.life_in_the_village.Village.Roads.Events;

import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;

import java.util.EnumSet;

/**
 * Phase 10 — descriptor for a kind of road event. The factory and the
 * eligibility filters are code-only; persistence stores the {@code typeId} on
 * each {@link RoadEvent} and the type is re-resolved through
 * {@link RoadEventRegistry} on load.
 *
 * <h3>Eligibility</h3>
 * An event of this type may be placed at a site only if the edge or node's
 * tier is in {@link #applicableTiers} and (when non-empty) the site biome
 * category is in {@link #applicableBiomes}.
 *
 * <h3>Spacing</h3>
 * {@link #minSpacingBlocks} is the minimum distance between two events of the
 * same type along the same edge. Multiple types coexist freely; spacing is
 * type-local.
 */
public record RoadEventType(
        String typeId,
        EventCategory category,
        EventPermanence permanence,
        EventRarity rarity,
        int minSpacingBlocks,
        EnumSet<RoadEdge.EdgeTier> applicableTiers,
        EnumSet<BiomeCategory> applicableBiomes,
        boolean worldUnique,
        EventFactory factory
) {

    public enum EventCategory {
        TRAVELER,
        LONE_STRUCTURE,
        JUNCTION,
        LANDMARK
    }

    public enum EventPermanence {
        PERMANENT,
        EPHEMERAL,
        MIXED
    }

    /**
     * Rarity bands. {@link #targetDensityMultiplier()} scales the base
     * "1 site per minSpacing" to fewer sites as rarity increases.
     */
    public enum EventRarity {
        COMMON(1.00f),
        UNCOMMON(0.50f),
        RARE(0.10f),
        WORLD_UNIQUE(0.0f); // capped at 1 in registry; per-edge density unused

        private final float multiplier;

        EventRarity(float multiplier) { this.multiplier = multiplier; }

        public float targetDensityMultiplier() { return multiplier; }
    }
}
