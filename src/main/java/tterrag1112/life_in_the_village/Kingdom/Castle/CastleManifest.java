package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Produced after castle generation completes.
 *
 * Consumed by:
 *  - The capital generation system (road network, kingdom registry)
 *  - The TownspersonMob spawner (Phase 3)
 *  - The quest system (throne room, dungeon, treasury positions)
 *  - The region ownership system (bounds)
 *
 * Immutable after construction. The capital system should persist this
 * to world-saved data via a Codec (added in Phase 3 integration).
 */
public record CastleManifest(

        // ---- Identity ----
        UUID castleId,
        CastleStyle style,
        BlockPos origin,

        // ---- Capital integration ----
        UUID kingdomId,                    // set by capital system after generation
        BlockPos gatePos,                  // road network connects here
        AABB bounds,                       // region ownership claim

        // ---- Key positions for NPC pathfinding and quests ----
        BlockPos throneRoomPos,
        BlockPos treasuryPos,
        Optional<BlockPos> dungeonPos,
        Optional<BlockPos> stablePos,

        // ---- All subbuilding positions ----
        Map<SubbuildingType, List<BlockPos>> subbuildingPositions,

        // ---- Tower positions (for patrol routes) ----
        List<BlockPos> towerPositions,

        // ---- Population ----
        CastleRoster roster

) {

    /**
     * Convenience: check if the castle has a dungeon for imprisonment mechanics.
     */
    public boolean hasDungeon() {
        return dungeonPos.isPresent();
    }

    /**
     * Convenience: find any subbuilding position by type.
     */
    public Optional<BlockPos> findSubbuilding(SubbuildingType type) {
        List<BlockPos> positions = subbuildingPositions.get(type);
        if (positions == null || positions.isEmpty()) return Optional.empty();
        return Optional.of(positions.get(0));
    }

    /**
     * Builder — CastleBuilder populates this incrementally during generation
     * then calls build() at the end.
     */
    public static class Builder {
        private final UUID castleId = UUID.randomUUID();
        private CastleStyle style;
        private BlockPos origin;
        private UUID kingdomId = new UUID(0, 0); // placeholder until capital assigns
        private BlockPos gatePos;
        private AABB bounds;
        private BlockPos throneRoomPos;
        private BlockPos treasuryPos;
        private Optional<BlockPos> dungeonPos   = Optional.empty();
        private Optional<BlockPos> stablePos    = Optional.empty();
        private Map<SubbuildingType, List<BlockPos>> subbuildingPositions = Map.of();
        private List<BlockPos> towerPositions = List.of();
        private CastleRoster roster;

        public Builder style(CastleStyle style)   { this.style = style;   return this; }
        public Builder origin(BlockPos origin)     { this.origin = origin; return this; }
        public Builder gatePos(BlockPos pos)       { this.gatePos = pos;   return this; }
        public Builder bounds(AABB bounds)         { this.bounds = bounds; return this; }

        public Builder throneRoom(BlockPos pos)    { this.throneRoomPos = pos; return this; }
        public Builder treasury(BlockPos pos)      { this.treasuryPos = pos;   return this; }
        public Builder dungeon(BlockPos pos)       { this.dungeonPos = Optional.of(pos); return this; }
        public Builder stable(BlockPos pos)        { this.stablePos = Optional.of(pos);  return this; }

        public Builder subbuildings(Map<SubbuildingType, List<BlockPos>> map) {
            this.subbuildingPositions = map;
            // Extract key positions from the map for convenience fields
            findFirst(map, SubbuildingType.THRONE_ROOM).ifPresent(p -> throneRoomPos = p);
            findFirst(map, SubbuildingType.TREASURY).ifPresent(p -> treasuryPos = p);
            findFirst(map, SubbuildingType.DUNGEON).ifPresent(p -> dungeonPos = Optional.of(p));
            findFirst(map, SubbuildingType.STABLE).ifPresent(p -> stablePos = Optional.of(p));
            return this;
        }

        public Builder towers(List<BlockPos> towers) {
            this.towerPositions = towers; return this;
        }

        public Builder roster(CastleRoster roster) {
            this.roster = roster; return this;
        }

        public CastleManifest build() {
            // Derive bounds from origin + outerRadius if not set
            if (bounds == null) bounds = new AABB(origin).inflate(64);
            if (throneRoomPos == null) throneRoomPos = origin;
            if (treasuryPos == null) treasuryPos = origin;
            if (roster == null) roster = CastleRoster.STANDARD;

            return new CastleManifest(
                    castleId, style, origin, kingdomId, gatePos, bounds,
                    throneRoomPos, treasuryPos, dungeonPos, stablePos,
                    subbuildingPositions, towerPositions, roster);
        }

        private static Optional<BlockPos> findFirst(
                Map<SubbuildingType, List<BlockPos>> map, SubbuildingType type) {
            List<BlockPos> list = map.get(type);
            return (list != null && !list.isEmpty())
                    ? Optional.of(list.get(0))
                    : Optional.empty();
        }
    }
}