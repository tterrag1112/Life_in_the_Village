package tterrag1112.life_in_the_village.Npc.Hobby;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Resolves a {@link HobbyLocation} to a concrete {@link BlockPos} for
 * the supplied NPC + level. Returns {@link Optional#empty()} when no
 * suitable location exists, which causes {@link HobbyCatalogue#availableFor}
 * to filter the hobby out (spec line 253).
 */
public final class HobbyLocationResolver {

    /** Outdoor walk distance for NATURE_TRAIL (spec line 250: ~40 blocks). */
    public static final int NATURE_TRAIL_DISTANCE = 40;
    /** Cap on water-edge search radius from village centre. */
    public static final int WATER_EDGE_SEARCH_RADIUS = 48;

    private HobbyLocationResolver() {}

    public static Optional<BlockPos> resolve(HobbyLocation loc,
                                             TownspersonMob npc,
                                             ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        Optional<Village> villageOpt = npc.getAssignedVillageName()
                .flatMap(data::getVillageByName);

        return switch (loc) {
            case HOME              -> resolveHome(npc, data);
            case TOWN_SQUARE       -> villageOpt.map(Village::getTownSquarePos);
            case INN               -> firstBuilding(villageOpt, data, BuildingType.INN);
            case TEMPLE_OR_SHRINE  -> firstBuilding(villageOpt, data,
                    BuildingType.TEMPLE, BuildingType.CHAPEL, BuildingType.SHRINE);
            case LIBRARY           -> firstBuilding(villageOpt, data, BuildingType.LIBRARY);
            case MARKET            -> firstBuilding(villageOpt, data, BuildingType.MARKET);
            case FIELDS_NEARBY     -> firstBuilding(villageOpt, data, BuildingType.FARMHOUSE);
            case WORKSHOP_FREE     -> firstBuilding(villageOpt, data,
                    BuildingType.CARPENTRY, BuildingType.STONEMASON,
                    BuildingType.WEAVER, BuildingType.CANDLEMAKER);
            case WATER_EDGE        -> resolveWaterEdge(villageOpt, level);
            case NATURE_TRAIL      -> resolveNatureTrail(villageOpt, level);
            case FRIEND_HOUSE      -> resolveFriendHouse(npc, data, level);
            // R5a — the village graveyard (its most-recent grave, else the
            // district centre); empty when the village has no graveyard yet.
            case GRAVEYARD         -> villageOpt.flatMap(v ->
                    tterrag1112.life_in_the_village.Village.Graveyard.GraveyardSavedData.get(level)
                            .getGraveyard(v.getId())
                            .map(tterrag1112.life_in_the_village.Village.Graveyard.Graveyard::visitTarget));
        };
    }

    private static Optional<BlockPos> resolveHome(TownspersonMob npc, VillageSavedData data) {
        return npc.getHouseId()
                .flatMap(data::getBuildingById)
                .map(b -> b.getShape().getOrigin());
    }

    @SafeVarargs
    private static Optional<BlockPos> firstBuilding(Optional<Village> villageOpt,
                                                    VillageSavedData data,
                                                    BuildingType... types) {
        if (villageOpt.isEmpty()) return Optional.empty();
        Village village = villageOpt.get();
        for (BuildingType t : types) {
            for (java.util.UUID bid : village.getBuildingIds()) {
                Optional<Building> b = data.getBuildingById(bid);
                if (b.isPresent() && b.get().getType() == t) {
                    return Optional.of(b.get().getShape().getOrigin());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Picks any pier/fishery building if present, otherwise samples
     * blocks around the village centre and returns the first water-touching
     * one. Bounded by {@link #WATER_EDGE_SEARCH_RADIUS} so failure is
     * fast.
     */
    private static Optional<BlockPos> resolveWaterEdge(Optional<Village> villageOpt,
                                                       ServerLevel level) {
        Optional<BlockPos> dock = firstBuilding(villageOpt,
                VillageSavedData.get(level),
                BuildingType.PIER, BuildingType.FISHERY, BuildingType.DOCKS);
        if (dock.isPresent()) return dock;
        if (villageOpt.isEmpty()) return Optional.empty();
        BlockPos centre = villageOpt.get().getVillageCentre();
        if (centre == null) return Optional.empty();

        Random rng = new Random(centre.asLong() ^ 0xFA51L);
        for (int attempt = 0; attempt < 32; attempt++) {
            int dx = rng.nextInt(WATER_EDGE_SEARCH_RADIUS * 2 + 1) - WATER_EDGE_SEARCH_RADIUS;
            int dz = rng.nextInt(WATER_EDGE_SEARCH_RADIUS * 2 + 1) - WATER_EDGE_SEARCH_RADIUS;
            BlockPos sample = centre.offset(dx, 0, dz);
            BlockPos surface = level.getHeightmapPos(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    sample);
            BlockPos below = surface.below();
            if (level.getFluidState(below).is(FluidTags.WATER)) {
                return Optional.of(surface);
            }
        }
        return Optional.empty();
    }

    /**
     * Outdoor wander point ~{@link #NATURE_TRAIL_DISTANCE} blocks from
     * the village centre. Direction is deterministic per village +
     * NPC so the same NPC tends to walk the same trail.
     */
    private static Optional<BlockPos> resolveNatureTrail(Optional<Village> villageOpt,
                                                         ServerLevel level) {
        if (villageOpt.isEmpty()) return Optional.empty();
        BlockPos centre = villageOpt.get().getVillageCentre();
        if (centre == null) return Optional.empty();
        // Use the village hash for stable direction, with a small jitter
        // from the level random so NPCs in the same village pick slightly
        // different headings.
        double angle = (Math.abs(villageOpt.get().getName().hashCode()) % 360) * Math.PI / 180.0
                + level.getRandom().nextDouble() * 0.5;
        int dx = (int) Math.round(Math.cos(angle) * NATURE_TRAIL_DISTANCE);
        int dz = (int) Math.round(Math.sin(angle) * NATURE_TRAIL_DISTANCE);
        BlockPos sample = centre.offset(dx, 0, dz);
        BlockPos surface = level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                sample);
        return Optional.of(surface);
    }

    /**
     * Picks the highest-positive-relationship NPC's house. Skips the
     * NPC's own house. Returns empty if no friend has an assigned
     * building.
     */
    private static Optional<BlockPos> resolveFriendHouse(TownspersonMob npc,
                                                         VillageSavedData data,
                                                         ServerLevel level) {
        List<tterrag1112.life_in_the_village.Npc.Relations.NpcRelationship> friends =
                npc.getNpcRelationships().friendsAndCloser();
        for (var f : friends) {
            Optional<TownspersonMob> other = TownspersonMob.findByUUID(level, f.otherId());
            if (other.isEmpty()) continue;
            Optional<BlockPos> home = other.get().getHouseId()
                    .flatMap(data::getBuildingById)
                    .map(b -> b.getShape().getOrigin());
            if (home.isPresent()) return home;
        }
        return Optional.empty();
    }
}
