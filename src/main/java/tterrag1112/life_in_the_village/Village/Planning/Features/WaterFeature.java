package tterrag1112.life_in_the_village.Village.Planning.Features;

import net.minecraft.core.BlockPos;

public record WaterFeature(PolygonXZ outline, int waterY,
                           WaterKind kind, BlockPos centroid) {}
