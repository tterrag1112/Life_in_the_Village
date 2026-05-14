package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

/**
 * Track E1 prompt-4 — declarative reference to a nucleus.
 *
 * <p>Nucleus rules name "what counts as a nucleus" abstractly so
 * the registry doesn't depend on anchor-id resolution. Three
 * forms:
 *
 * <ul>
 *   <li>{@link AnchorRef} — a specific anchor by id. Rare; mostly
 *       used in tests / debug.</li>
 *   <li>{@link PrimaryAnchorRef} — the strategy's primary anchor
 *       (resolved by {@code StrategySelectionResult.primaryAnchor()}).
 *       The default for most CIVIC / SACRED / RESOURCE rules.</li>
 *   <li>{@link BuildingRef} — every placed instance of a given
 *       {@link BuildingType} becomes a nucleus. The default for
 *       RURAL ({@code BuildingRef(FARMHOUSE)}).</li>
 * </ul>
 */
public sealed interface NucleusRef
        permits NucleusRef.AnchorRef, NucleusRef.PrimaryAnchorRef,
        NucleusRef.BuildingRef {

    /** Anchor by id. */
    record AnchorRef(String anchorId) implements NucleusRef {
        public AnchorRef {
            if (anchorId == null || anchorId.isBlank()) {
                throw new IllegalArgumentException("AnchorRef.anchorId must be non-blank");
            }
        }
    }

    /** The strategy's selected primary anchor. */
    record PrimaryAnchorRef() implements NucleusRef {}

    /** Every placed instance of {@code type}. */
    record BuildingRef(BuildingType type) implements NucleusRef {
        public BuildingRef {
            if (type == null) {
                throw new IllegalArgumentException("BuildingRef.type required");
            }
        }
    }
}
