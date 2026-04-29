package tterrag1112.life_in_the_village.Village.Planning.Features;

import net.minecraft.core.BlockPos;

import java.util.List;

public record CliffFeature(PolygonXZ ridgeFootprint,
                           int topY, int baseY,
                           List<BlockPos> edgeWalk) {
    public CliffFeature {
        edgeWalk = List.copyOf(edgeWalk);
    }
}
