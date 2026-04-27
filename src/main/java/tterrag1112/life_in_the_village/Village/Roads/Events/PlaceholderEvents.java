package tterrag1112.life_in_the_village.Village.Roads.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 10 — two placeholder event types for regression testing the
 * infrastructure end-to-end. Not intended as game content; gated behind
 * {@code -Dlitv.testEvents=true} via {@link #shouldRegister()}.
 *
 * <h3>{@code test_marker}</h3>
 * COMMON LONE_STRUCTURE, PERMANENT. A single polished-andesite pedestal with
 * a redstone torch on top, placed 3 blocks perpendicular to the road on the
 * right side, ground-snapped.
 *
 * <h3>{@code test_ephemeral}</h3>
 * UNCOMMON JUNCTION, EPHEMERAL with a 5-day lifespan. A 2x2 cluster of
 * (persistent) oak leaves blocks placed at +1 above the node position.
 * Despawn returns those blocks to air.
 */
public final class PlaceholderEvents {

    private PlaceholderEvents() {}

    public static final String TYPE_TEST_MARKER     = "test_marker";
    public static final String TYPE_TEST_EPHEMERAL  = "test_ephemeral";

    private static final long DAYS = 24000L;

    // =========================================================================
    // Registration entry point
    // =========================================================================

    /**
     * Registers the placeholder types if the {@code litv.testEvents} system
     * property is set. Idempotent.
     */
    public static void registerIfEnabled() {
        if (!shouldRegister()) return;
        RoadEventRegistry.register(buildTestMarker());
        RoadEventRegistry.register(buildTestEphemeral());
        System.out.println("[PlaceholderEvents] registered test_marker + test_ephemeral");
    }

    /** Force-register, regardless of the system property (for tests / debug). */
    public static void register() {
        RoadEventRegistry.register(buildTestMarker());
        RoadEventRegistry.register(buildTestEphemeral());
    }

    public static boolean shouldRegister() {
        return Boolean.getBoolean("litv.testEvents");
    }

    // =========================================================================
    // test_marker
    // =========================================================================

    private static RoadEventType buildTestMarker() {
        return new RoadEventType(
                TYPE_TEST_MARKER,
                RoadEventType.EventCategory.LONE_STRUCTURE,
                RoadEventType.EventPermanence.PERMANENT,
                RoadEventType.EventRarity.COMMON,
                /* minSpacingBlocks */ 2000,
                /* applicableTiers */
                EnumSet.of(RoadEdge.EdgeTier.CONNECTOR, RoadEdge.EdgeTier.TRUNK),
                /* applicableBiomes */ EnumSet.noneOf(BiomeCategory.class),
                /* worldUnique */ false,
                new TestMarkerFactory()
        );
    }

    private static final class TestMarkerFactory implements EventFactory {
        @Override
        public RoadEvent create(EventPlacementContext ctx) {
            ServerLevel level = ctx.level();
            BlockPos site = ctx.sitePosition();

            // Place 3 blocks to the "right" of the road — pick a fixed offset
            // along +X for determinism; the planner already filtered by edge.
            int offX = 3;
            int offZ = 0;
            int gx = site.getX() + offX;
            int gz = site.getZ() + offZ;
            if (!level.isLoaded(new BlockPos(gx, level.getMinY() + 1, gz))) return null;

            int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx, gz);
            BlockPos pedestal = new BlockPos(gx, gy, gz);

            BlockState below = level.getBlockState(pedestal);
            if (!below.isAir() && !below.canBeReplaced()) return null;
            level.setBlock(pedestal, Blocks.POLISHED_ANDESITE.defaultBlockState(), 3);
            level.setBlock(pedestal.above(),
                    Blocks.REDSTONE_TORCH.defaultBlockState(), 3);

            return new RoadEvent(
                    UUID.randomUUID(), TYPE_TEST_MARKER, pedestal,
                    ctx.edge() != null ? Optional.of(ctx.edge().getEdgeId()) : Optional.empty(),
                    ctx.node().map(n -> n.nodeId()),
                    ctx.currentTick(), Optional.empty(),
                    new HashMap<>(), Optional.empty());
        }

        @Override
        public void despawn(ServerLevel level, RoadEvent event) {
            BlockPos pedestal = event.position();
            if (!level.isLoaded(pedestal)) return;
            // Replace the torch and pedestal with air. Best-effort.
            BlockState p = level.getBlockState(pedestal);
            if (p.is(Blocks.POLISHED_ANDESITE)) {
                level.setBlock(pedestal, Blocks.AIR.defaultBlockState(), 3);
            }
            BlockState t = level.getBlockState(pedestal.above());
            if (t.is(Blocks.REDSTONE_TORCH)) {
                level.setBlock(pedestal.above(), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    // =========================================================================
    // test_ephemeral
    // =========================================================================

    private static RoadEventType buildTestEphemeral() {
        return new RoadEventType(
                TYPE_TEST_EPHEMERAL,
                RoadEventType.EventCategory.JUNCTION,
                RoadEventType.EventPermanence.EPHEMERAL,
                RoadEventType.EventRarity.UNCOMMON,
                /* minSpacingBlocks */ 64,
                /* applicableTiers */ EnumSet.allOf(RoadEdge.EdgeTier.class),
                /* applicableBiomes */ EnumSet.noneOf(BiomeCategory.class),
                /* worldUnique */ false,
                new TestEphemeralFactory()
        );
    }

    private static final class TestEphemeralFactory implements EventFactory {
        @Override
        public RoadEvent create(EventPlacementContext ctx) {
            ServerLevel level = ctx.level();
            BlockPos site = ctx.sitePosition().above();
            BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(BlockStateProperties.PERSISTENT, true);

            int placed = 0;
            for (int dx = 0; dx < 2; dx++) {
                for (int dz = 0; dz < 2; dz++) {
                    BlockPos p = site.offset(dx, 0, dz);
                    if (!level.isLoaded(p)) continue;
                    BlockState existing = level.getBlockState(p);
                    if (!existing.isAir() && !existing.canBeReplaced()) continue;
                    level.setBlock(p, leaves, 3);
                    placed++;
                }
            }
            if (placed == 0) return null;

            long expires = ctx.currentTick() + 5L * DAYS;
            return new RoadEvent(
                    UUID.randomUUID(), TYPE_TEST_EPHEMERAL, site,
                    ctx.edge() != null ? Optional.of(ctx.edge().getEdgeId()) : Optional.empty(),
                    ctx.node().map(n -> n.nodeId()),
                    ctx.currentTick(), Optional.of(expires),
                    new HashMap<>(), Optional.empty());
        }

        @Override
        public void despawn(ServerLevel level, RoadEvent event) {
            BlockPos site = event.position();
            for (int dx = 0; dx < 2; dx++) {
                for (int dz = 0; dz < 2; dz++) {
                    BlockPos p = site.offset(dx, 0, dz);
                    if (!level.isLoaded(p)) continue;
                    BlockState s = level.getBlockState(p);
                    if (s.is(Blocks.OAK_LEAVES)) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }
}
