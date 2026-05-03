package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaRegion;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 18: polygon-aware plaza façade.
 *
 * <p>Single-owner wrapper around {@link PlazaRegion} (the polygon-based
 * plaza model) plus the legacy {@code townSquarePos} / {@code townSquareRadius}
 * / {@code civicRingRadius} fields and the civic placement slots. Recipes
 * register exactly one {@code Plaza} per {@code installPlaza} call; the
 * matcher's civic-first claim path consumes {@link #civicSlots()} directly.
 *
 * <p>Why a façade instead of pushing more state onto {@link PlazaRegion}:
 * {@link PlazaRegion} is persisted (it has a Codec). The civic slots are
 * planning-time-only — they exist between {@code compose()} and the matcher's
 * claim pass, then they're consumed and discarded. Mixing planning-only state
 * into a persisted record would expand the persistence surface for no gain.
 *
 * <h3>Mutability — Option A from the Phase 18 prompt</h3>
 * Geometry fields ({@link #region}, {@link #townSquarePos}, etc.) are
 * effectively final (the surrounding immutability story matches a record).
 * The civic-slot list is INTENTIONALLY mutable: the matcher's
 * {@code commitBestFromPool} removes claimed slots so successive civic
 * placements don't pick the same slot twice. Documented; do not "fix"
 * by switching to {@code List.copyOf}.
 *
 * <h3>HAMLET tier</h3>
 * HAMLETs don't have a {@link PlazaRegion} (no polygon — just a
 * {@link tterrag1112.life_in_the_village.Village.Decoration.Plaza
 * .VillageCenterMarker}). Their {@code Plaza} has {@code region == null}
 * and an empty {@link #civicSlots()} list. The legacy field values
 * ({@code townSquarePos = snapped}, {@code townSquareRadius = 3},
 * {@code civicRingRadius = 11}) are populated so downstream consumers
 * read sane values.
 */
public final class Plaza {

    @Nullable private final PlazaRegion region;
    private final BlockPos townSquarePos;
    private final int townSquareRadius;
    private final int civicRingRadius;
    private final List<PlacementSlot> civicSlots;

    public Plaza(@Nullable PlazaRegion region,
                 BlockPos townSquarePos,
                 int townSquareRadius,
                 int civicRingRadius,
                 List<PlacementSlot> civicSlots) {
        this.region            = region;
        this.townSquarePos     = townSquarePos;
        this.townSquareRadius  = townSquareRadius;
        this.civicRingRadius   = civicRingRadius;
        // Mutable copy on construction; matcher consumes from this list.
        this.civicSlots        = new ArrayList<>(civicSlots);
    }

    @Nullable public PlazaRegion region() { return region; }
    public BlockPos townSquarePos()       { return townSquarePos; }
    public int      townSquareRadius()    { return townSquareRadius; }
    public int      civicRingRadius()     { return civicRingRadius; }

    /** Convenience alias matching the Phase 18 prompt's vocabulary. */
    public BlockPos centre() { return townSquarePos; }

    /**
     * Mutable view of the civic slot pool. The matcher removes slots
     * here as it commits civic buildings; subsequent commits draw from
     * the shrinking pool. Recipes typically don't mutate this list —
     * use the read-only {@link #civicSlotsView()} when you only need to
     * inspect.
     */
    public List<PlacementSlot> civicSlots() { return civicSlots; }

    /** Read-only view for inspection (e.g. plan dump). */
    public List<PlacementSlot> civicSlotsView() {
        return Collections.unmodifiableList(civicSlots);
    }

    /**
     * Returns a new {@link Plaza} with the civic ring radius overridden.
     * Civic slots are NOT regenerated; callers that change the ring also
     * need to call {@link #withCivicSlots(List)} if they want the slot
     * positions moved to the new ring.
     */
    public Plaza withCivicRingRadius(int newRadius) {
        return new Plaza(region, townSquarePos, townSquareRadius,
                newRadius, civicSlots);
    }

    /**
     * Returns a new {@link Plaza} with civic slots replaced. Used after
     * {@link #withCivicRingRadius(int)} to regenerate slot positions on
     * the new ring.
     */
    public Plaza withCivicSlots(List<PlacementSlot> newSlots) {
        return new Plaza(region, townSquarePos, townSquareRadius,
                civicRingRadius, newSlots);
    }
}
