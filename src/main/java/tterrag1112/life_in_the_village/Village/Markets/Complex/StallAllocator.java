package tterrag1112.life_in_the_village.Village.Markets.Complex;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingPlacer;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fits stall structures into a market pad's perimeter band and places
 * them facing the surrounding aisle (merchant arc Phase 2b). Replaces
 * {@code MarketStallPlacer}'s authored-anchor + dominant-axis-rotation
 * path: stalls now sit on the 2a prepared pad, ringing the nucleus
 * building, fronts opening outward onto the perimeter aisle.
 *
 * <h3>Aisle-facing rotation</h3>
 * The stall NBT is authored facing SOUTH (+Z). A seat on a given side of
 * the building faces <em>outward</em> on that side, so rotation is
 * derived from the side — never from a dominant-axis snap toward the
 * centre (the old bug):
 * <pre>
 *   south side → faces +Z → Rotation.NONE
 *   west  side → faces -X → Rotation.CLOCKWISE_90
 *   north side → faces -Z → Rotation.CLOCKWISE_180
 *   east  side → faces +X → Rotation.COUNTERCLOCKWISE_90
 * </pre>
 *
 * <h3>Fit</h3>
 * Seats are scanned over the region inset by the aisle width; a seat is
 * accepted iff the stall's actual placed {@link BoundingBox} (queried via
 * {@link StructureTemplate#getBoundingBox}) lies fully inside the inset
 * region, entirely on one side of the building footprint (+gap), and
 * clear of every already-occupied box. First fit wins; callers place
 * repeatedly, accumulating occupancy, until {@code empty} ("market full").
 */
public final class StallAllocator {

    private static final Logger LOGGER = LoggerFactory.getLogger(StallAllocator.class);

    /** Walkable ring kept clear just inside the pad edge. */
    private static final int AISLE_WIDTH = 1;
    /** Gap between a stall and the building wall / another stall. */
    private static final int GAP = 1;

    private StallAllocator() {}

    /** A chosen stall position: placement origin, rotation, outward
     *  facing, and the exact world box the stall occupies. */
    public record Seat(BlockPos origin, Rotation rotation, Direction facing, BoundingBox box) {}

    // =========================================================================
    // Placement
    // =========================================================================

    /**
     * Places one stall variant on the pad for the given owner, if a seat
     * is free. Loads the template, finds an aisle-facing seat clear of the
     * building and {@code occupied} boxes, stamps the NBT, detects the
     * stall's chest, and returns a populated {@link MarketStall}. The
     * placed box is appended to {@code occupied} so repeated calls pack
     * without overlap.
     *
     * @return empty when the template is missing or the market is full.
     */
    public static Optional<MarketStall> place(ServerLevel level, Building market,
                                              Polygon region, Polygon footprint, int padY,
                                              StallVariant variant,
                                              UUID ownerUUID, MarketStall.OwnerType ownerType,
                                              String displayName, long rentUntil,
                                              int slotIndex, List<BoundingBox> occupied) {
        StructureTemplate template = resolveTemplate(level, market, variant).orElse(null);
        if (template == null) {
            LOGGER.warn("[StallAllocator] stall template not found (variant-system "
                    + "+ direct fallback both missing): {}", variant.directNbt());
            return Optional.empty();
        }

        Polygon.AABB regionRect = Polygon.boundingBox(region);
        Polygon.AABB footprintRect = Polygon.boundingBox(footprint);
        Optional<Seat> seatOpt = findSeat(template, regionRect, footprintRect, padY, occupied);
        if (seatOpt.isEmpty()) return Optional.empty();
        Seat seat = seatOpt.get();

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(seat.rotation())
                .setIgnoreEntities(false);
        template.placeInWorld(level, seat.origin(), BlockPos.ZERO, settings,
                level.getRandom(), 2);

        MarketStall stall = MarketStall.create(market.getId(), slotIndex,
                seat.origin(), ownerUUID, displayName, ownerType, rentUntil);
        BlockPos chestPos = findChestInBox(level, seat.box());
        if (chestPos != null) stall.setChestPos(chestPos);

        occupied.add(seat.box());
        return Optional.of(stall);
    }

    /**
     * Resolves the stall template through the {@code MARKET_STALL} variant
     * system first (culture-aware, manifest-gated), falling back to the
     * variant's direct NBT path when the variant-layout template isn't
     * authored. This is what lets stalls keep placing with the single
     * existing NBT until it is relocated into the variant layout.
     */
    private static Optional<StructureTemplate> resolveTemplate(
            ServerLevel level, Building market, StallVariant variant) {
        try {
            String culture = tterrag1112.life_in_the_village.Cultures.CultureResolver
                    .of(level, marketVillage(level, market)).id();
            net.minecraft.resources.Identifier viaVariant =
                    tterrag1112.life_in_the_village.Village.CultureResolver.resolve(
                            culture,
                            tterrag1112.life_in_the_village.Village.Decoration.Variants.Style.RURAL,
                            tterrag1112.life_in_the_village.Village.Buildings.BuildingType.MARKET_STALL,
                            variant.id(), 1, level);
            if (viaVariant != null) {
                Optional<StructureTemplate> t = BuildingPlacer.loadTemplate(level, viaVariant);
                if (t.isPresent()) return t;
            }
        } catch (Exception ignored) {
            // variant resolution unavailable — fall through to direct path
        }
        return BuildingPlacer.loadTemplate(level, variant.directNbt());
    }

    /** The village owning this market (for culture resolution); null-safe. */
    private static tterrag1112.life_in_the_village.Village.Village marketVillage(
            ServerLevel level, Building market) {
        var data = tterrag1112.life_in_the_village.Networking.VillageSavedData.get(level);
        for (var v : data.getAllVillages()) {
            if (v.getBuildingIds().contains(market.getId())) return v;
        }
        return null;
    }

    // =========================================================================
    // Seat search
    // =========================================================================

    private static Optional<Seat> findSeat(StructureTemplate template,
                                           Polygon.AABB region, Polygon.AABB footprint,
                                           int padY, List<BoundingBox> occupied) {
        // Region inset by the aisle ring — stalls live inside this; the
        // ring between it and the pad edge is the walkable aisle.
        int rMinX = region.minX() + AISLE_WIDTH, rMaxX = region.maxX() - AISLE_WIDTH;
        int rMinZ = region.minZ() + AISLE_WIDTH, rMaxZ = region.maxZ() - AISLE_WIDTH;
        if (rMinX > rMaxX || rMinZ > rMaxZ) return Optional.empty();
        int seatY = padY + 1; // stalls stand on top of the pad surface

        for (Direction side : new Direction[]{
                Direction.SOUTH, Direction.EAST, Direction.NORTH, Direction.WEST}) {
            Rotation rot = rotationForOutward(side);
            StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rot);
            for (int oz = rMinZ; oz <= rMaxZ; oz++) {
                for (int ox = rMinX; ox <= rMaxX; ox++) {
                    BlockPos origin = new BlockPos(ox, seatY, oz);
                    BoundingBox box = template.getBoundingBox(settings, origin);
                    if (box.minX() < rMinX || box.maxX() > rMaxX) continue;
                    if (box.minZ() < rMinZ || box.maxZ() > rMaxZ) continue;
                    if (!onSide(side, box, footprint)) continue;
                    if (overlapsFootprint(box, footprint)) continue;
                    if (overlapsAny(box, occupied)) continue;
                    return Optional.of(new Seat(origin, rot, side, box));
                }
            }
        }
        return Optional.empty();
    }

    /** Outward facing for a stall on this side maps to the rotation that
     *  turns the SOUTH-authored front to face that way. */
    private static Rotation rotationForOutward(Direction side) {
        return switch (side) {
            case SOUTH -> Rotation.NONE;
            case WEST  -> Rotation.CLOCKWISE_90;
            case NORTH -> Rotation.CLOCKWISE_180;
            case EAST  -> Rotation.COUNTERCLOCKWISE_90;
            default    -> Rotation.NONE; // UP/DOWN never used
        };
    }

    /** True iff the box sits entirely on {@code side} of the footprint
     *  (with the wall gap), so its front opens outward, not into the
     *  building. */
    private static boolean onSide(Direction side, BoundingBox box, Polygon.AABB fp) {
        return switch (side) {
            case SOUTH -> box.minZ() >= fp.maxZ() + GAP;
            case NORTH -> box.maxZ() <= fp.minZ() - GAP;
            case EAST  -> box.minX() >= fp.maxX() + GAP;
            case WEST  -> box.maxX() <= fp.minX() - GAP;
            default    -> false;
        };
    }

    private static boolean overlapsFootprint(BoundingBox box, Polygon.AABB fp) {
        return box.minX() <= fp.maxX() + GAP && box.maxX() >= fp.minX() - GAP
                && box.minZ() <= fp.maxZ() + GAP && box.maxZ() >= fp.minZ() - GAP;
    }

    private static boolean overlapsAny(BoundingBox box, List<BoundingBox> occupied) {
        for (BoundingBox o : occupied) {
            if (box.minX() <= o.maxX() + GAP && box.maxX() >= o.minX() - GAP
                    && box.minZ() <= o.maxZ() + GAP && box.maxZ() >= o.minZ() - GAP) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos findChestInBox(ServerLevel level, BoundingBox box) {
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof ChestBlockEntity) return pos;
                }
            }
        }
        return null;
    }
}
