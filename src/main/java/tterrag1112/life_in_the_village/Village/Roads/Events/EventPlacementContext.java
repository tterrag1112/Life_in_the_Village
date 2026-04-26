package tterrag1112.life_in_the_village.Village.Roads.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Phase 10 — context handed to an {@link EventFactory#create} call. Carries the
 * level, the owning edge or node, the chosen site position, the current tick,
 * and a deterministically-seeded {@link RandomSource} so factories can produce
 * stable variants without each one re-deriving its own seed.
 */
public record EventPlacementContext(
        ServerLevel level,
        @Nullable RoadEdge edge,
        Optional<RoadNode> node,
        BlockPos sitePosition,
        long currentTick,
        RandomSource rng
) {}
