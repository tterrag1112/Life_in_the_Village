package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureRegistry;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingPlacer;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.CultureResolver;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.BuildingVariant;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;
import tterrag1112.life_in_the_village.Village.Farms.Complex.FarmComplex;
import tterrag1112.life_in_the_village.Village.Farms.Complex.FarmComplexPlanner;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Detour A — `/liv farms` debug commands.
 *
 * <p>Two subcommands:
 * <ul>
 *   <li>{@code /liv farms <village>} — list every {@link FarmComplex}
 *       in the named village; per-complex region / plot / path /
 *       border / gate / shed detail.</li>
 *   <li>{@code /liv farms test_spawn [name]} — Stage 6 smoke test
 *       harness. Places a default-culture farmhouse NBT at the
 *       caller's position, builds a synthetic Village (HAMLET tier,
 *       AGRICULTURAL inclination, just the farmhouse), runs
 *       {@link FarmComplexPlanner#planAndPersist}, then dumps the
 *       result in human-readable form. Surfaces
 *       {@link FarmComplexPlanner.PlanResult#detail()} when the
 *       plan fails.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class FarmDebugCommand {

    private FarmDebugCommand() {}

    /** Synthetic village name prefix — used by Stage 6's test_spawn
     *  so the harness-created villages are visually distinct from
     *  organic worldgen villages in {@code /liv village list}. */
    private static final String SYNTH_PREFIX = "harness_";

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("liv")
                        .then(Commands.literal("farms")
                                .then(Commands.literal("test_spawn")
                                        .executes(ctx -> testSpawn(ctx, "default"))
                                        .then(Commands.argument("name",
                                                        StringArgumentType.string())
                                                .executes(ctx -> testSpawn(ctx,
                                                        StringArgumentType.getString(ctx, "name")))))
                                // Phase 6.3.3.m.2 — building-scoped plot
                                // listing. Argument is a building UUID
                                // (full or 8-char prefix); resolves to a
                                // FARMHOUSE and dumps its registered plots
                                // with soil / history / fallow / blight
                                // state. Used to diagnose plot-registration
                                // failures ("farmer has no plots to work").
                                .then(Commands.literal("plots")
                                        .then(Commands.argument("building",
                                                        StringArgumentType.string())
                                                .executes(ctx -> listPlotsForBuilding(ctx,
                                                        StringArgumentType.getString(ctx, "building")))))
                                .then(Commands.argument("village",
                                                StringArgumentType.string())
                                        .executes(ctx -> list(ctx,
                                                StringArgumentType.getString(ctx, "village")))))
        );
    }

    private static int list(CommandContext<CommandSourceStack> ctx,
                            String villageName) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/liv farms must be run on a server level."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageByName(villageName).orElse(null);
        if (village == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No village named '" + villageName + "'."));
            return 0;
        }

        List<FarmComplex> complexes = data.getFarmComplexesForVillage(village.getId());
        var tier = village.getSizeTier();
        var inclination = village.getInclination();
        String tierStr = "tier=" + tier
                + " inclination=" + (inclination != null ? inclination.name() : "(unset)");

        int farmhouseCount = 0;
        for (UUID bid : village.getBuildingIds()) {
            var b = data.getBuildingById(bid).orElse(null);
            if (b != null && b.getType() == tterrag1112.life_in_the_village
                    .Village.Buildings.BuildingType.FARMHOUSE) {
                farmhouseCount++;
            }
        }

        if (complexes.isEmpty()) {
            String reason = farmhouseCount == 0
                    ? "no FARMHOUSE buildings in this village"
                    : "FarmComplexPlanner found no viable claim — terrain may be too steep, "
                            + "biome-blocked, or below the arable-cell minimum. "
                            + "Farmhouses present: " + farmhouseCount;
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Village " + villageName + ": no farm complexes.\n"
                            + "  " + tierStr + "\n"
                            + "  reason: " + reason),
                    false);
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(complexes.size()).append(" farm complex(es) in ")
                .append(villageName).append(" (").append(tierStr).append("):\n");
        for (FarmComplex c : complexes) {
            int verts = c.region().vertices().size();
            int area = (int) Polygon.area(c.region());
            int pathSegs = c.pathSegments().size();
            int gates = c.gatePositions().size();
            boolean hasShed = c.toolShedPosition() != null;
            sb.append("  Complex ").append(shortId(c.id()))
                    .append(" farmhouse=").append(shortId(c.farmhouseId()))
                    .append(" — region: ").append(verts).append("v area≈").append(area)
                    .append(", plots=").append(c.plotIds().size())
                    .append(", paths=").append(pathSegs)
                    .append(", gates=").append(gates)
                    .append(", shed=").append(hasShed ? "yes" : "no")
                    .append("\n");
            for (UUID plotId : c.plotIds()) {
                FarmPlot p = data.getFarmPlotById(plotId).orElse(null);
                if (p == null) {
                    sb.append("    Plot ").append(shortId(plotId))
                            .append(" (missing from farmPlots index)\n");
                    continue;
                }
                sb.append("    Plot ").append(shortId(p.getId()))
                        .append(" crop=").append(p.getCropType().name())
                        .append(" subtype=").append(p.getSubtype().name())
                        .append(" origin=(").append(p.getOrigin().getX()).append(",")
                        .append(p.getOrigin().getZ()).append(")")
                        .append(p.getPolygon() != null
                                ? " poly=" + p.getPolygon().vertices().size() + "v"
                                : " poly=none")
                        .append("\n");
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return complexes.size();
    }

    private static String shortId(UUID id) {
        if (id == null) return "(null)";
        return id.toString().substring(0, 8);
    }

    // =========================================================================
    // Phase 6.3.3.m.2 — /liv farms plots <building>
    // =========================================================================

    /**
     * Lists every {@link FarmPlot} registered to {@code buildingArg}.
     * Argument accepts a full UUID or an 8-char prefix; if multiple
     * buildings match the prefix, the command fails with the candidate
     * list. Output shows plot subtype, crop, soil quality, fallow /
     * blight state, last 3 history entries, and tick-age of the most
     * recent compost / fallow / blight event in in-game days.
     *
     * <p>Primary use: diagnose "farmer has no plots to work" — if this
     * command returns "0 plots" for a FARMHOUSE that has farmers
     * assigned to it, the plot-registration path (FarmComplexPlanner
     * → VillageSavedData.addFarmPlot) didn't fire.</p>
     */
    private static int listPlotsForBuilding(CommandContext<CommandSourceStack> ctx,
                                            String buildingArg) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/liv farms plots must be run on a server level."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);

        UUID buildingId = resolveBuildingId(data, buildingArg, ctx);
        if (buildingId == null) return 0;

        var b = data.getBuildingById(buildingId).orElse(null);
        if (b == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Building " + shortId(buildingId) + " not found in VillageSavedData."));
            return 0;
        }

        List<FarmPlot> plots = data.getFarmPlotsForFarmhouse(buildingId);
        long now = level.getGameTime();

        StringBuilder sb = new StringBuilder();
        sb.append("Building ").append(shortId(buildingId))
                .append(" (").append(b.getType().name()).append(") — ")
                .append(plots.size()).append(" plot(s)");
        if (b.getType() != tterrag1112.life_in_the_village.Village.Buildings
                .BuildingType.FARMHOUSE) {
            sb.append("\n  WARNING: not a FARMHOUSE — farmers only resolve plots ")
                    .append("via FARMHOUSE assignment");
        }
        sb.append("\n");

        if (plots.isEmpty()) {
            sb.append("  no FarmPlot records registered to this building.\n")
                    .append("  Probable causes: FarmComplexPlanner never ran for ")
                    .append("this farmhouse's village, or planner ran but didn't ")
                    .append("call addFarmPlot. Cross-check /liv farms <village>.\n");
        } else {
            for (FarmPlot p : plots) {
                sb.append("  ").append(shortId(p.getId()))
                        .append("  subtype=").append(p.getSubtype().name())
                        .append("  crop=").append(p.getCropType().name())
                        .append("  soil=").append(String.format("%.2f", p.getSoilQuality()));
                if (p.isFallow()) {
                    long days = (now - p.getFallowSinceTick()) / 24000L;
                    sb.append("  FALLOW(").append(days).append("d)");
                }
                if (p.isBlighted()) {
                    long days = (now - p.getBlightSinceTick()) / 24000L;
                    sb.append("  BLIGHT(").append(days).append("d)");
                }
                if (p.getLastCompostedTick() > 0L) {
                    long days = (now - p.getLastCompostedTick()) / 24000L;
                    sb.append("  composted=").append(days).append("d ago");
                }
                List<String> history = p.getCropHistory();
                if (!history.isEmpty()) {
                    int start = Math.max(0, history.size() - 3);
                    sb.append("  history=[");
                    for (int i = start; i < history.size(); i++) {
                        if (i > start) sb.append(",");
                        sb.append(history.get(i));
                    }
                    sb.append("]");
                }
                sb.append("\n");
            }
        }

        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return plots.size();
    }

    /**
     * Resolves a building UUID from a string that's either a full UUID
     * or an 8-char prefix. Sends a failure component on the source and
     * returns {@code null} when resolution fails / is ambiguous.
     */
    private static UUID resolveBuildingId(VillageSavedData data, String arg,
                                          CommandContext<CommandSourceStack> ctx) {
        // Full UUID path.
        try {
            return UUID.fromString(arg);
        } catch (IllegalArgumentException ignore) { /* try prefix below */ }
        // Prefix path — find matching buildings.
        List<UUID> matches = new java.util.ArrayList<>();
        for (var b : data.getAllBuildings()) {
            if (b.getId().toString().startsWith(arg)) matches.add(b.getId());
        }
        if (matches.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "No building UUID matches '" + arg + "'."));
            return null;
        }
        if (matches.size() > 1) {
            StringBuilder msg = new StringBuilder(
                    "Ambiguous prefix '" + arg + "' — " + matches.size() + " matches:");
            for (UUID id : matches) msg.append("\n  ").append(id);
            ctx.getSource().sendFailure(Component.literal(msg.toString()));
            return null;
        }
        return matches.get(0);
    }

    // =========================================================================
    // Stage 6 — test_spawn harness
    // =========================================================================

    /** Run a single farmhouse → FarmComplex plan at the caller's
     *  position. Places the farmhouse NBT (so the user sees it in-
     *  world), constructs a synthetic Village (or reuses one if
     *  the caller invokes the command multiple times with the same
     *  name), runs the planner, persists on success, then prints
     *  the dump. Block-level rendering of paths / borders / crops
     *  is deferred to Prompt B; the farmhouse itself is the only
     *  visible structure. */
    private static int testSpawn(CommandContext<CommandSourceStack> ctx, String baseName) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/liv farms test_spawn must be run on a server level."));
            return 0;
        }
        BlockPos pos = BlockPos.containing(ctx.getSource().getPosition());

        VillageSavedData data = VillageSavedData.get(level);
        Culture culture = CultureRegistry.getOrDefault(CultureRegistry.DEFAULT_ID);

        // Synthetic village. One per invocation (suffixed by game
        // time) so consecutive test_spawn calls don't collide on
        // the same village. Per the user's "synthetic-village shape"
        // brief: HAMLET tier, AGRICULTURAL inclination, only the
        // farmhouse, everything else default / empty. The village
        // exists solely so VillageSavedData can host the complex.
        String villageName = SYNTH_PREFIX + baseName + "_"
                + Long.toHexString(level.getGameTime() & 0xFFFFL);
        Village village = new Village(villageName, "synthetic");
        village.setVillageCentre(pos);
        village.setInclination(
                tterrag1112.life_in_the_village.Village.Planning.V2.Inclination.AGRICULTURAL);
        data.addVillage(village);

        // Place the farmhouse NBT so the user sees it. Default
        // variant, NONE rotation, default tint plan.
        String variantId = BuildingVariant.defaultVariantId(BuildingType.FARMHOUSE);
        Identifier structureId = CultureResolver.resolve(
                culture.id(), Style.RURAL, BuildingType.FARMHOUSE, variantId,
                /* level */ 1, level);
        String farmhouseName = villageName + "_farmhouse";
        Optional<Building> placedOpt = BuildingPlacer.placeAndRegister(
                level, pos, structureId, farmhouseName,
                BuildingType.FARMHOUSE, Rotation.NONE);
        if (placedOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Farmhouse NBT failed to load/place at " + pos
                            + " (culture=" + culture.id() + ", variant=" + variantId + ")."));
            return 0;
        }
        Building farmhouse = placedOpt.get();
        village.addBuilding(farmhouse);

        // BuildingPlacer with Rotation.NONE treats `pos` as the
        // building's NW corner — its actual centre is offset by
        // (width/2, _, length/2). Read the placed shape so the
        // planner gets the same "centre of footprint" value the
        // spawn adapter passes via PlacedBuilding.centre().
        var shape = farmhouse.getShape();
        BlockPos farmhouseCentre = new BlockPos(
                shape.getOrigin().getX() + shape.getWidth()  / 2,
                pos.getY(),
                shape.getOrigin().getZ() + shape.getLength() / 2);

        // V2FeatureMap centered on the farmhouse. 100-block radius
        // mirrors the spawn-adapter default (FEATURE_MAP_RADIUS).
        V2FeatureMap fmap = V2FeatureMap.scan(level, farmhouseCentre, 100);

        // Half-extents from the placed Building's footprint —
        // honest dims rather than fixed defaults.
        int halfX = Math.max(1, shape.getWidth()  / 2);
        int halfZ = Math.max(1, shape.getLength() / 2);

        // Seed = (gameTime ^ position hash). Different positions get
        // different seeds; same (pos, gameTime) reproduces. Long
        // enough that two runs within the same gameTick at the
        // same position are likely rare in practice.
        long seed = level.getGameTime() ^ ((long) pos.hashCode() << 16);

        FarmComplexPlanner.Input input = new FarmComplexPlanner.Input(
                farmhouseCentre,
                /* complexExtendsToward */ Direction.SOUTH,
                halfX,
                halfZ,
                village.getId(),
                farmhouse.getId(),
                culture.id(),
                BuildingType.FARMHOUSE,
                fmap,
                /* biomeCheck */ null,
                seed,
                /* excludedPolygons */ java.util.List.of(),
                /* verbose */ true,
                /* namePrefix */ village.getName() + "_complex");
        FarmComplexPlanner.PlanResult result =
                FarmComplexPlanner.planAndPersist(input, data);
        if (result.success()) {
            // Phase 6.7.2.2 — seed sheep rosters for newly-minted
            // ANIMAL_PEN plots. No-op when no pens were planted.
            tterrag1112.life_in_the_village.Village.Roster.AnimalRosterSeeder
                    .seedFor(level, result.complex(), result.newPlots());
        }

        if (!result.success()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Complex plan failed for " + villageName + " @ " + pos
                            + "\n  status: " + result.status()
                            + "\n  detail: " + result.detail()
                            + "\n  village: kept (no complex attached)"));
            return 0;
        }

        // Prompt B Stage G — render the complex, then populate the
        // farmhouse so the user has a working scene to inspect.
        // Failures here don't fail the command; the dump still
        // reports the planned state for debugging.
        String renderNote = "";
        try {
            List<FarmPlot> plots = data.getFarmPlotsForFarmhouse(farmhouse.getId());
            tterrag1112.life_in_the_village.Village.Farms.Complex.Render
                    .FarmComplexRenderer.render(result.complex(), plots,
                            culture.id(), level, /* verbose */ true);
        } catch (Exception e) {
            renderNote = "\n  (render warning: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + ")";
        }
        int npcCount = 0;
        boolean populatorCalled = false;
        try {
            java.util.Map<BuildingType, java.util.List<Building>> roster =
                    java.util.Map.of(BuildingType.FARMHOUSE,
                            java.util.List.of(farmhouse));
            tterrag1112.life_in_the_village.Village.Buildings.Inhabitants
                    .VillageInhabitantPopulator.populate(
                            level, village, data, roster, new java.util.Random(seed));
            populatorCalled = true;
            npcCount = data.getHouseholdForBuilding(farmhouse.getId())
                    .map(tterrag1112.life_in_the_village.Entities
                            .HouseholdData::size)
                    .orElse(0);
        } catch (Throwable t) {
            renderNote += "\n  (populator warning: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + ")";
        }

        String dump = dumpComplex(result.complex(), data, pos, halfX, halfZ);
        String tail = "\n  populator: " + (populatorCalled
                        ? "invoked, NPCs in household=" + npcCount
                        : "skipped (see warning above)")
                + renderNote;
        String finalText = dump + tail;
        ctx.getSource().sendSuccess(() -> Component.literal(finalText), false);
        return 1;
    }

    /** Human-readable dump matching the Stage 6 brief's shape:
     *
     *  <pre>
     *  Complex <id> @ <pos> villageId=<id>
     *    region: 42 vertices, 384 cells
     *    plots: 4 (WHEAT, MIXED, PASTURE, ORCHARD)
     *      plot 0: 96 cells, polygon vertex count 8
     *      ...
     *    paths: 1 spine + 4 branches, total 67 blocks
     *    toolShed: (x, y, z) | none
     *    borders: HEDGE×3, STONE_WALL×1, ...
     *    gates: N
     *  </pre>
     *
     *  Cell count is approximated from polygon area divided by
     *  cellSize² (cellSize=2 ⇒ /4). Paths "total" reports the sum
     *  of Manhattan-ish segment lengths in blocks. */
    private static String dumpComplex(FarmComplex c, VillageSavedData data,
                                       BlockPos farmhousePos,
                                       int halfX, int halfZ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Complex ").append(shortId(c.id()))
                .append(" @ (").append(farmhousePos.getX()).append(", ")
                .append(farmhousePos.getY()).append(", ")
                .append(farmhousePos.getZ()).append(")")
                .append(" villageId=").append(shortId(c.villageId()))
                .append(" farmhouse=").append(shortId(c.farmhouseId()))
                .append("\n");

        // Region.
        double regionArea = Polygon.area(c.region());
        int regionCells = (int) Math.round(regionArea / 4.0);   // cellSize=2 ⇒ /4
        sb.append("  region: ").append(c.region().vertices().size()).append(" vertices, ")
                .append(regionCells).append(" cells (area≈").append((int) regionArea)
                .append(" blocks²)\n");

        // Per-plot detail + crop summary.
        List<UUID> plotIds = c.plotIds();
        sb.append("  plots: ").append(plotIds.size());
        if (!plotIds.isEmpty()) {
            sb.append(" (");
            for (int i = 0; i < plotIds.size(); i++) {
                FarmPlot p = data.getFarmPlotById(plotIds.get(i)).orElse(null);
                if (i > 0) sb.append(", ");
                sb.append(p == null ? "?" : p.getCropType().name());
            }
            sb.append(")");
        }
        sb.append("\n");
        for (int i = 0; i < plotIds.size(); i++) {
            FarmPlot p = data.getFarmPlotById(plotIds.get(i)).orElse(null);
            if (p == null) {
                sb.append("    plot ").append(i).append(": (missing)\n");
                continue;
            }
            int pvc = p.getPolygon() == null ? 0 : p.getPolygon().vertices().size();
            int pArea = p.getPolygon() == null ? 0 : (int) Polygon.area(p.getPolygon());
            int pCells = pArea / 4;
            sb.append("    plot ").append(i).append(": ").append(pCells)
                    .append(" cells, polygon vertex count ").append(pvc)
                    .append(", crop=").append(p.getCropType().name())
                    .append("\n");
        }

        // Paths.
        int spineCount = 0, branchCount = 0;
        long totalLen = 0;
        for (var s : c.pathSegments()) {
            if (s.isSpine()) spineCount++; else branchCount++;
            long dx = s.end().getX() - s.start().getX();
            long dz = s.end().getZ() - s.start().getZ();
            totalLen += Math.round(Math.sqrt(dx * dx + dz * dz));
        }
        sb.append("  paths: ").append(spineCount).append(" spine + ")
                .append(branchCount).append(" branches, total ").append(totalLen).append(" blocks\n");

        // Tool shed.
        sb.append("  toolShed: ");
        if (c.toolShedPosition() == null) {
            sb.append("none (positioning probe found no clear spot)");
        } else {
            sb.append("(").append(c.toolShedPosition().getX()).append(", ")
                    .append(c.toolShedPosition().getY()).append(", ")
                    .append(c.toolShedPosition().getZ()).append(")");
        }
        sb.append("\n");

        // Border style distribution (primary style per plot).
        EnumMap<tterrag1112.life_in_the_village.Village.Buildings.Complex.BorderStyleId,
                Integer> styleCount = new EnumMap<>(
                        tterrag1112.life_in_the_village.Village.Buildings.Complex.BorderStyleId.class);
        for (var b : c.plotBorders()) {
            styleCount.merge(b.primary(), 1, Integer::sum);
        }
        sb.append("  borders: ");
        boolean first = true;
        for (var s :
                tterrag1112.life_in_the_village.Village.Buildings.Complex.BorderStyleId.values()) {
            if (!first) sb.append(", ");
            sb.append(s.name()).append("×").append(styleCount.getOrDefault(s, 0));
            first = false;
        }
        sb.append("\n");

        // Footprint context (so the user can sanity-check the
        // farmhouse vs region scale).
        sb.append("  farmhouse footprint half-dims: halfX=")
                .append(halfX).append(", halfZ=").append(halfZ).append("\n");

        return sb.toString();
    }
}
