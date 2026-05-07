package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.Map;

/**
 * V2 Layer 4 hand-off. Wraps the frozen {@link Skeleton} (spine +
 * cross streets + junctions) plus an optional reverse lookup from
 * frontage cells to building types — Layer 5 will use this to know
 * which face a road tile is the "front of".
 *
 * <p>No more Road wrappers; the Skeleton's segment instances ARE
 * the roads. Materials are resolved at decoration time from the
 * culture's {@code roadMaterial}.
 */
public record RoadNetwork(Skeleton skeleton,
                          Map<BlockPos, BuildingType> frontageOwners) {
}
