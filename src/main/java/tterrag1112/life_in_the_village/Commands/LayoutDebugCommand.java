package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Planning.Debug.LayoutDebugVisualizer;
import tterrag1112.life_in_the_village.Village.Planning.Debug.LayoutDebugVisualizer.ParticleEmission;
import tterrag1112.life_in_the_village.Village.Planning.Features.CliffFeature;
import tterrag1112.life_in_the_village.Village.Planning.Features.DeterminismHarness;
import tterrag1112.life_in_the_village.Village.Planning.Features.FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.Features.PolygonXZ;
import tterrag1112.life_in_the_village.Village.Planning.Features.ReservedRegion;
import tterrag1112.life_in_the_village.Village.Planning.Features.WaterFeature;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Graph.NodeKind;
import tterrag1112.life_in_the_village.Village.Planning.Graph.RoadGraph;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.Sector;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class LayoutDebugCommand {

    private static final int SHOW_GRAPH_RADIUS = 512;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("liv")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("layout")
                        .then(Commands.literal("debug")
                                .then(Commands.literal("show_graph")
                                        .executes(LayoutDebugCommand::showGraph))
                                .then(Commands.literal("show_hull")
                                        .executes(LayoutDebugCommand::showHull))
                                .then(Commands.literal("show_features")
                                        .executes(LayoutDebugCommand::showFeatures))
                                .then(Commands.literal("show_sectors")
                                        .executes(LayoutDebugCommand::showSectors))
                                .then(Commands.literal("determinism_test")
                                        .executes(LayoutDebugCommand::determinismTestNoSeed)
                                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                                .executes(LayoutDebugCommand::determinismTestWithSeed)))
                        )
                )
        );
    }

    private static int showGraph(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel  level  = ctx.getSource().getLevel();

        VillageSavedData data = VillageSavedData.get(level);
        List<Village> nearby = villagesNearPlayer(data, player, SHOW_GRAPH_RADIUS);

        List<ParticleEmission> emissions = new ArrayList<>();
        int totalEdges = 0, totalNodes = 0;

        for (Village v : nearby) {
            RoadGraph graph = v.getDebugRoadGraph();
            if (graph == null) continue;

            for (RoadGraph.Edge edge : graph.allEdges()) {
                emissions.addAll(buildEdgeEmissions(edge,
                        particleForRole(edge.role()),
                        LayoutDebugVisualizer.DEFAULT_EMIT_INTERVAL));
                totalEdges++;
            }
            for (RoadGraph.Node node : graph.allNodes()) {
                int height = beamHeightForKind(node.kind());
                emissions.addAll(buildNodeBeam(node, height, particleForKind(node.kind())));
                totalNodes++;
            }
        }

        LayoutDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);

        int vc = nearby.size(), ec = totalEdges, nc = totalNodes;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Showing " + ec + " edges and " + nc + " nodes across " + vc
                        + " villages within " + SHOW_GRAPH_RADIUS + " blocks for 30 seconds."),
                false);
        return ec + nc;
    }

    // =========================================================================
    // determinism_test — console-runnable; verifies FeatureMap.buildPlanning
    // produces identical XZ output for identical inputs across N reps, at
    // five offsets from a seed-derived origin.
    // =========================================================================

    /** Number of repetitions per origin in the determinism harness run. */
    private static final int DETERMINISM_REPS = 5;
    /** Spacing between the five test origins. */
    private static final int DETERMINISM_OFFSET = 512;

    private static int determinismTestNoSeed(CommandContext<CommandSourceStack> ctx) {
        return runDeterminismTest(ctx, ctx.getSource().getLevel().getSeed());
    }

    private static int determinismTestWithSeed(CommandContext<CommandSourceStack> ctx) {
        long seed = LongArgumentType.getLong(ctx, "seed");
        return runDeterminismTest(ctx, seed);
    }

    private static int runDeterminismTest(CommandContext<CommandSourceStack> ctx, long seed) {
        ServerLevel level = ctx.getSource().getLevel();
        // Seed the origin position from the seed itself so repeated runs of
        // the command with the same seed test the same terrain.
        int baseX = (int)(seed & 0xFFFF) - 0x8000;
        int baseZ = (int)((seed >>> 16) & 0xFFFF) - 0x8000;
        int baseY = level.getSeaLevel();

        BlockPos[] origins = {
            new BlockPos(baseX, baseY, baseZ),
            new BlockPos(baseX + DETERMINISM_OFFSET, baseY, baseZ),
            new BlockPos(baseX - DETERMINISM_OFFSET, baseY, baseZ),
            new BlockPos(baseX, baseY, baseZ + DETERMINISM_OFFSET),
            new BlockPos(baseX, baseY, baseZ - DETERMINISM_OFFSET)
        };

        List<VillageTypeData.StarterBuilding> empty = List.of();
        int passes = 0;
        List<String> firstFailureDiffs = null;
        int firstFailureIdx = -1;

        for (int i = 0; i < origins.length; i++) {
            DeterminismHarness.HarnessResult res =
                    DeterminismHarness.run(level, origins[i], seed, empty, DETERMINISM_REPS);
            if (res.deterministic()) {
                passes++;
            } else if (firstFailureDiffs == null) {
                firstFailureDiffs = res.diffs();
                firstFailureIdx = i;
            }
        }

        final int p = passes;
        final int total = origins.length;
        final long s = seed;
        if (firstFailureDiffs == null) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[determinism_test] PASS — seed=" + s + " " + p + "/" + total
                            + " origins deterministic across " + DETERMINISM_REPS + " reps each."),
                    false);
            return 1;
        } else {
            final String diffs = String.join("; ", firstFailureDiffs);
            final int idx = firstFailureIdx;
            ctx.getSource().sendFailure(Component.literal(
                    "[determinism_test] FAIL — seed=" + s + " " + p + "/" + total
                            + " passed; first failure at origin[" + idx + "]="
                            + origins[idx] + " — " + diffs));
            return 0;
        }
    }

    private static List<ParticleEmission> buildEdgeEmissions(
            RoadGraph.Edge edge, ParticleOptions particle, int emitEveryNTicks) {
        List<ParticleEmission> out = new ArrayList<>();
        List<BlockPos> cl = edge.centerline();
        for (int i = 0; i < cl.size(); i += 3) {
            out.add(new ParticleEmission(cl.get(i), particle, emitEveryNTicks));
        }
        return out;
    }

    private static List<ParticleEmission> buildNodeBeam(
            RoadGraph.Node node, int height, ParticleOptions particle) {
        List<ParticleEmission> out = new ArrayList<>(height);
        BlockPos base = node.pos();
        for (int dy = 0; dy < height; dy++) {
            out.add(new ParticleEmission(
                    base.above(dy), particle, LayoutDebugVisualizer.DEFAULT_EMIT_INTERVAL));
        }
        return out;
    }

    private static ParticleOptions particleForRole(EdgeRole role) {
        return switch (role) {
            case SPINE -> ParticleTypes.END_ROD;
            case SPUR  -> ParticleTypes.FLAME;
            case RING  -> ParticleTypes.COMPOSTER;
        };
    }

    private static ParticleOptions particleForKind(NodeKind kind) {
        return switch (kind) {
            case JUNCTION, CASTLE_ANCHOR, MANOR_ANCHOR -> ParticleTypes.END_ROD;
            case GATE        -> ParticleTypes.SOUL_FIRE_FLAME;
            case FOCAL       -> ParticleTypes.HAPPY_VILLAGER;
            case TERMINUS    -> ParticleTypes.SMOKE;
            case BRIDGE_HEAD -> ParticleTypes.FALLING_WATER;
            case SHORE_HEAD  -> ParticleTypes.DRIPPING_WATER;
        };
    }

    private static int beamHeightForKind(NodeKind kind) {
        return switch (kind) {
            case CASTLE_ANCHOR, MANOR_ANCHOR -> 12;
            case TERMINUS                    -> 6;
            case BRIDGE_HEAD, SHORE_HEAD     -> 8;
            default                          -> 10;
        };
    }

    // =========================================================================
    // show_hull
    // =========================================================================

    private static int showHull(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        List<Village> nearby = villagesNearPlayer(data, player, SHOW_GRAPH_RADIUS);
        List<ParticleEmission> emissions = new ArrayList<>();
        int hullCount = 0;

        for (Village v : nearby) {
            FeatureMap fm = featureMapForVillage(v);
            if (fm == null || fm.hull() == null) continue;
            emissions.addAll(tracePolygon(
                    fm.hull(), ParticleTypes.END_ROD, level,
                    LayoutDebugVisualizer.DEFAULT_EMIT_INTERVAL, 3));
            emissions.addAll(buildBeam(
                    polygonCentroid(fm.hull(), level), 12,
                    ParticleTypes.END_ROD,
                    LayoutDebugVisualizer.DEFAULT_EMIT_INTERVAL));
            hullCount++;
        }

        if (emissions.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "No villages with hulls found within " + SHOW_GRAPH_RADIUS + " blocks."));
            return 0;
        }

        LayoutDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);

        int count = hullCount;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Showing " + count + " hull(s) for 30 seconds."), false);
        return count;
    }

    // =========================================================================
    // show_features
    // =========================================================================

    private static int showFeatures(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        List<Village> nearby = villagesNearPlayer(data, player, SHOW_GRAPH_RADIUS);
        List<ParticleEmission> emissions = new ArrayList<>();
        int waterN = 0, cliffN = 0, plazaN = 0, resN = 0;

        for (Village v : nearby) {
            FeatureMap fm = featureMapForVillage(v);
            if (fm == null) continue;

            for (WaterFeature wf : fm.waterFeatures()) {
                emissions.addAll(tracePolygon(
                        wf.outline(), ParticleTypes.FALLING_WATER, level,
                        LayoutDebugVisualizer.DEFAULT_EMIT_INTERVAL, 3));
                emissions.addAll(buildBeam(
                        polygonCentroid(wf.outline(), level), 8,
                        ParticleTypes.DRIPPING_WATER,
                        LayoutDebugVisualizer.DEFAULT_EMIT_INTERVAL));
                waterN++;
            }
            for (CliffFeature cf : fm.cliffFeatures()) {
                emissions.addAll(tracePolygon(
                        cf.ridgeFootprint(), ParticleTypes.LAVA, level,
                        LayoutDebugVisualizer.DEFAULT_EMIT_INTERVAL, 3));
                emissions.addAll(buildBeam(
                        polygonCentroid(cf.ridgeFootprint(), level), 8,
                        ParticleTypes.FLAME,
                        LayoutDebugVisualizer.DEFAULT_EMIT_INTERVAL));
                cliffN++;
            }
            for (PolygonXZ p : fm.plazaPolygons()) {
                emissions.addAll(tracePolygon(
                        p, ParticleTypes.HAPPY_VILLAGER, level,
                        LayoutDebugVisualizer.DEFAULT_EMIT_INTERVAL, 3));
                emissions.addAll(buildBeam(
                        polygonCentroid(p, level), 10,
                        ParticleTypes.HAPPY_VILLAGER,
                        LayoutDebugVisualizer.DEFAULT_EMIT_INTERVAL));
                plazaN++;
            }
            for (ReservedRegion r : fm.reservations()) {
                emissions.addAll(tracePolygon(
                        r.shape(), ParticleTypes.COMPOSTER, level,
                        LayoutDebugVisualizer.DEFAULT_EMIT_INTERVAL, 3));
                emissions.addAll(buildBeam(
                        polygonCentroid(r.shape(), level), 8,
                        ParticleTypes.COMPOSTER,
                        LayoutDebugVisualizer.DEFAULT_EMIT_INTERVAL));
                resN++;
            }
        }

        if (emissions.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "No features found within " + SHOW_GRAPH_RADIUS + " blocks."));
            return 0;
        }

        LayoutDebugVisualizer.INSTANCE.addSession(player.getUUID(), level.getGameTime(), emissions);

        int w = waterN, c = cliffN, p = plazaN, r = resN;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Showing " + w + " water, " + c + " cliff, " + p + " plaza, "
                        + r + " reservation features for 30 seconds."), false);
        return w + c + p + r;
    }

    // =========================================================================
    // Shared polygon/village helpers
    // =========================================================================

    private static List<Village> villagesNearPlayer(
            VillageSavedData data, ServerPlayer player, int radius) {
        BlockPos playerPos = player.blockPosition();
        List<Village> out = new ArrayList<>();
        for (Village v : data.getAllVillages()) {
            BlockPos centre = v.getVillageCentre();
            if (centre == null) continue;
            double dx = centre.getX() - playerPos.getX();
            double dz = centre.getZ() - playerPos.getZ();
            if (dx * dx + dz * dz <= (double) radius * radius) {
                out.add(v);
            }
        }
        return out;
    }

    private static FeatureMap featureMapForVillage(Village v) {
        return v.getDebugFeatureMap();
    }

    private static List<ParticleEmission> tracePolygon(
            PolygonXZ polygon, ParticleOptions particle, ServerLevel level,
            int emitEveryNTicks, int strideBlocks) {
        List<ParticleEmission> out = new ArrayList<>();
        List<BlockPos> verts = polygon.vertices();
        int n = verts.size();
        if (n < 2) return out;

        for (int i = 0; i < n; i++) {
            BlockPos a = verts.get(i);
            BlockPos b = verts.get((i + 1) % n);
            int dx = b.getX() - a.getX();
            int dz = b.getZ() - a.getZ();
            double len = Math.sqrt((double) dx * dx + (double) dz * dz);
            if (len < 1.0) continue;

            int steps = Math.max(1, (int) Math.ceil(len / strideBlocks));
            for (int s = 0; s <= steps; s++) {
                double t = (double) s / steps;
                int x = (int) Math.round(a.getX() + dx * t);
                int z = (int) Math.round(a.getZ() + dz * t);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                out.add(new ParticleEmission(
                        new BlockPos(x, y + 1, z), particle, emitEveryNTicks));
            }
        }
        return out;
    }

    private static BlockPos polygonCentroid(PolygonXZ polygon, ServerLevel level) {
        long sx = 0, sz = 0;
        int n = polygon.vertices().size();
        for (BlockPos v : polygon.vertices()) {
            sx += v.getX();
            sz += v.getZ();
        }
        int cx = (int) (sx / n);
        int cz = (int) (sz / n);
        int cy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
        return new BlockPos(cx, cy, cz);
    }

    private static List<ParticleEmission> buildBeam(
            BlockPos basePos, int height, ParticleOptions particle, int emitEveryNTicks) {
        List<ParticleEmission> out = new ArrayList<>(height);
        for (int dy = 0; dy < height; dy++) {
            out.add(new ParticleEmission(
                    basePos.offset(0, dy, 0), particle, emitEveryNTicks));
        }
        return out;
    }

    // =========================================================================
    // show_sectors
    // =========================================================================

    /**
     * Sector visual states. Phase 9 only emits UNFILLED / AT_CAPACITY
     * (committed-vs-capacity); OVERFLOWED / GREW need matcher
     * instrumentation that lands in Phase 10. The handler still picks
     * particles for all four so Phase 10's behavior surfaces visually
     * without a code change.
     */
    private enum SectorVisualState { UNFILLED, AT_CAPACITY, OVERFLOWED, GREW }

    /** XZ tolerance (blocks) for matching a slot's emitted target position
     *  to a placed building's centre. Slots are stamped pre-terrain-jitter,
     *  so a tolerance of 8 blocks captures the typical building-jitter range. */
    private static final int COMMITTED_MATCH_TOLERANCE = 8;

    private static int showSectors(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        List<Village> nearby = villagesNearPlayer(data, player, SHOW_GRAPH_RADIUS);
        List<ParticleEmission> emissions = new ArrayList<>();
        int sectorCount = 0;
        int villagesWithSectors = 0;

        for (Village v : nearby) {
            List<Sector> sectors = v.getDebugSectors();
            if (sectors == null || sectors.isEmpty()) continue;
            villagesWithSectors++;

            Set<BlockPos> committed = collectCommittedPositions(v, data);

            for (Sector sector : sectors) {
                SectorVisualState state = classify(sector, committed);
                emissions.addAll(traceSectorBounds(
                        sector, edgeParticleFor(state), level,
                        LayoutDebugVisualizer.DEFAULT_EMIT_INTERVAL));
                emissions.addAll(renderSectorSlots(
                        sector, committed, state,
                        LayoutDebugVisualizer.DEFAULT_EMIT_INTERVAL));
                sectorCount++;
            }
        }

        if (emissions.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "No sectors found within " + SHOW_GRAPH_RADIUS + " blocks. "
                            + "(Only converted recipes emit sectors. RADIAL is "
                            + "converted; others convert in Phases 11-15.)"));
            return 0;
        }

        LayoutDebugVisualizer.INSTANCE.addSession(
                player.getUUID(), level.getGameTime(), emissions);

        int sc = sectorCount, vc = villagesWithSectors;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Showing " + sc + " sector(s) across " + vc
                        + " village(s) for 30 seconds."), false);
        return sc;
    }

    private static SectorVisualState classify(Sector sector, Set<BlockPos> committed) {
        int hits = 0;
        for (PlacementSlot s : sector.slots()) {
            if (isCommittedNear(s.pos(), committed)) hits++;
        }
        // Phase 9 sees only UNFILLED / AT_CAPACITY. OVERFLOWED / GREW
        // require matcher-side instrumentation introduced in Phase 10.
        if (sector.capacity() > 0 && hits >= sector.capacity()) {
            return SectorVisualState.AT_CAPACITY;
        }
        return SectorVisualState.UNFILLED;
    }

    private static ParticleOptions edgeParticleFor(SectorVisualState state) {
        return switch (state) {
            case UNFILLED    -> ParticleTypes.COMPOSTER;
            case AT_CAPACITY -> ParticleTypes.FLAME;
            case OVERFLOWED  -> ParticleTypes.ANGRY_VILLAGER;
            case GREW        -> ParticleTypes.ENCHANT;
        };
    }

    private static List<ParticleEmission> traceSectorBounds(
            Sector sector, ParticleOptions edgeParticle, ServerLevel level,
            int emitEveryNTicks) {
        List<PlacementSlot> slots = sector.slots();
        if (slots.isEmpty()) return List.of();

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (PlacementSlot s : slots) {
            BlockPos p = s.pos();
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        // Pad by 4 blocks so the trace sits outside the slot positions
        minX -= 4; maxX += 4; minZ -= 4; maxZ += 4;

        PolygonXZ rect = new PolygonXZ(List.of(
                new BlockPos(minX, 0, minZ),
                new BlockPos(maxX, 0, minZ),
                new BlockPos(maxX, 0, maxZ),
                new BlockPos(minX, 0, maxZ)));
        return tracePolygon(rect, edgeParticle, level, emitEveryNTicks, 3);
    }

    private static List<ParticleEmission> renderSectorSlots(
            Sector sector, Set<BlockPos> committed, SectorVisualState state,
            int emitEveryNTicks) {
        List<ParticleEmission> out = new ArrayList<>();
        for (PlacementSlot s : sector.slots()) {
            ParticleOptions p;
            boolean isCommitted = isCommittedNear(s.pos(), committed);
            if (isCommitted) {
                p = ParticleTypes.END_ROD;
            } else if (state == SectorVisualState.OVERFLOWED) {
                p = ParticleTypes.ANGRY_VILLAGER;
            } else {
                p = ParticleTypes.SMOKE;
            }
            // Three-block vertical stack so slots are visible above grass
            for (int dy = 0; dy < 3; dy++) {
                out.add(new ParticleEmission(
                        s.pos().offset(0, dy + 1, 0), p, emitEveryNTicks));
            }
        }
        return out;
    }

    /**
     * Collects placed-building centres for a village. Building centres are
     * derived from BuildingShape (origin + halfW, origin + halfL) and
     * matched against slot positions with a small XZ tolerance — slots
     * are stamped pre-terrain-jitter, so exact equality systematically
     * misses placements. Phase 10's matcher instrumentation will replace
     * this heuristic with a direct slot-id → committed flag.
     */
    private static Set<BlockPos> collectCommittedPositions(
            Village v, VillageSavedData data) {
        Set<BlockPos> set = new HashSet<>();
        for (UUID id : v.getBuildingIds()) {
            data.getBuildingById(id).ifPresent(b -> {
                Building.BuildingShape shape = b.getShape();
                if (shape == null) return;
                BlockPos origin = shape.getOrigin();
                int w = shape.getWidth();
                int l = shape.getLength();
                set.add(origin.offset(w / 2, 0, l / 2));
            });
        }
        return set;
    }

    private static boolean isCommittedNear(BlockPos slotPos, Set<BlockPos> centres) {
        long tol = (long) COMMITTED_MATCH_TOLERANCE * COMMITTED_MATCH_TOLERANCE;
        for (BlockPos c : centres) {
            long dx = c.getX() - slotPos.getX();
            long dz = c.getZ() - slotPos.getZ();
            if (dx * dx + dz * dz <= tol) return true;
        }
        return false;
    }
}
