package tterrag1112.life_in_the_village.Commands;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureRegistry;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PrimitiveContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SpinePath;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.BuildingSelector;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DependencyResolver;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DroppedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.Footprint;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.InclinationProfile;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementResult;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.ReconciliationEngine;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.StructureAvailabilityRegistry;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.UnavailableBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.CrossStreet;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.PhasedPlanner;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadSegment;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.Skeleton;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.SpineSegment;
import tterrag1112.life_in_the_village.Village.Planning.V2.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Village;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Track E1 — read-only V2 layout dump command.
 *
 * <p>Two variants:
 * <ul>
 *   <li>{@code /litv layout debug dump <villageName>} — re-runs V2
 *       Layers 1–4 against the named village's anchor (no
 *       realisation; world is not modified).</li>
 *   <li>{@code /litv layout debug dump_at <radius>} — runs V2
 *       Layers 1–4 at the calling player's position. Useful for
 *       browsing what V2 would build under different terrain
 *       without spawning the village.</li>
 * </ul>
 *
 * <p>Output is pretty-printed JSON written to
 * {@code <worldSave>/litv-debug/layouts/<name>-<tick>.json}. The
 * absolute path is echoed to chat on success.
 *
 * <p>Schema is documented in {@code docs/V2_OVERVIEW.md}.
 */
public final class LayoutDumpCommand {

    /** Schema version stamped into every dump. Bump on incompatible changes. */
    public static final int SCHEMA_VERSION = 1;

    /** V2 feature-map scan radius (matches V2VillageSpawnerAdapter constant). */
    public static final int FEATURE_MAP_RADIUS = 96;

    /** Building level used by V2's synth density profile (matches adapter). */
    public static final int BUILDING_LEVEL = 4;

    /** Default radius for dump_at when not specified. */
    public static final int DEFAULT_DUMP_AT_RADIUS = FEATURE_MAP_RADIUS;

    private LayoutDumpCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("litv")
                .then(Commands.literal("layout")
                        .then(Commands.literal("debug")
                                .then(Commands.literal("dump")
                                        .then(Commands.argument("villageName",
                                                        StringArgumentType.string())
                                                .executes(LayoutDumpCommand::dumpVillage)))
                                .then(Commands.literal("dump_at")
                                        .executes(ctx -> dumpAt(ctx, DEFAULT_DUMP_AT_RADIUS))
                                        .then(Commands.argument("radius",
                                                        IntegerArgumentType.integer(8, 512))
                                                .executes(ctx -> dumpAt(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "radius"))))))));
    }

    // =========================================================================
    // Command handlers
    // =========================================================================

    private static int dumpVillage(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        String villageName = StringArgumentType.getString(ctx, "villageName");

        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageByName(villageName).orElse(null);
        if (village == null) {
            src.sendFailure(Component.literal(
                    "No village named '" + villageName + "'."));
            return 0;
        }

        BlockPos origin = village.getAnchorPos();
        long tick = level.getGameTime();
        Path outFile = pickOutputFile(level, villageName, tick);

        JsonObject root = runPlanAndBuildJson(level, origin,
                FEATURE_MAP_RADIUS, "dump",
                villageName, village.getId() != null ? village.getId().toString() : null,
                tick);

        return writeJsonAndReply(src, outFile, root);
    }

    private static int dumpAt(CommandContext<CommandSourceStack> ctx, int radius)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = src.getLevel();
        BlockPos origin = player.blockPosition();
        long tick = level.getGameTime();

        String slug = "dump_at_" + origin.getX() + "_" + origin.getZ();
        Path outFile = pickOutputFile(level, slug, tick);

        JsonObject root = runPlanAndBuildJson(level, origin, radius,
                "dump_at", null, null, tick);

        return writeJsonAndReply(src, outFile, root);
    }

    // =========================================================================
    // V2 Layers 1-4 — read-only invocation
    // =========================================================================

    /**
     * Runs V2 Layers 1-4 against the named origin and returns the
     * full JSON dump. Read-only — no world mutation. Mirrors
     * {@code V2VillageSpawnerAdapter.spawn} up to (but excluding)
     * Layer 5's vegetation / pad / NBT placement.
     */
    private static JsonObject runPlanAndBuildJson(ServerLevel level, BlockPos origin,
                                                   int radius, String commandKind,
                                                   String villageName, String villageId,
                                                   long tick) {
        long worldSeed = level.getSeed();
        long planSeed = worldSeed
                ^ ((long) origin.hashCode() * 31L
                        + (villageName != null ? villageName.hashCode() : 0));
        Random rng = new Random(planSeed);

        // Layer 1.
        V2FeatureMap fmap = V2FeatureMap.scan(level, origin, radius);

        // Layer 2.
        Culture culture = CultureRegistry.getOrDefault(CultureRegistry.DEFAULT_ID);
        SiteContext siteCtx = SiteAnalyzer.analyze(fmap, culture, planSeed);

        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("command", commandKind);
        root.addProperty("tick", tick);
        root.addProperty("worldSeed", worldSeed);
        root.addProperty("planSeed", planSeed);
        root.addProperty("dimension", level.dimension().location().toString());
        root.add("origin", posJson(origin));
        if (villageName != null) {
            JsonObject v = new JsonObject();
            v.addProperty("name", villageName);
            if (villageId != null) v.addProperty("id", villageId);
            v.addProperty("culture", culture.id());
            root.add("village", v);
        } else {
            JsonObject v = new JsonObject();
            v.addProperty("culture", culture.id());
            root.add("village", v);
        }

        // Site context.
        root.add("siteContext", siteContextJson(siteCtx, level, worldSeed));

        // If unviable, stop early but still dump what we have.
        if (siteCtx.tier() == ViabilityTier.UNVIABLE) {
            root.addProperty("aborted", true);
            root.addProperty("abortReason", "tier=UNVIABLE; layers 3-4 skipped");
            return root;
        }

        // Layer 3.
        InclinationProfile profile = InclinationProfile.forInclination(siteCtx.inclination());
        BuildingSelector.SelectionResult sel =
                BuildingSelector.select(siteCtx, fmap, profile);
        ReconciliationEngine.ReconciliationResult recon =
                ReconciliationEngine.reconcile(sel.selected(), siteCtx.tier(),
                        culture.id(), StructureAvailabilityRegistry.INSTANCE);
        List<BuildingType> sorted =
                DependencyResolver.topoSort(recon.finalSelection(), planSeed);
        List<UnavailableBuilding> unavailable = sel.unavailable();
        Set<BuildingType> tradeFulfilled = new HashSet<>();
        for (var tf : recon.tradeFulfilled()) tradeFulfilled.add(tf.requiringType());

        // Layer 4.
        PhasedPlanner.Result phased =
                PhasedPlanner.run(siteCtx, fmap, sorted, unavailable, level, tradeFulfilled);
        PlacementResult placement = phased.placement();
        RoadNetwork roads = phased.network();

        root.add("buildings", buildingsJson(placement, sel, recon));
        root.add("roads", roadsJson(roads, level, worldSeed));
        root.add("phaseEvents", phaseEventsJson(phased.events()));
        root.add("gateways", gatewaysJson(roads));

        return root;
    }

    // =========================================================================
    // JSON serialisation — keep this self-contained to avoid drifting
    // from the live record shapes.
    // =========================================================================

    private static JsonObject siteContextJson(SiteContext ctx, ServerLevel level, long seed) {
        JsonObject o = new JsonObject();
        o.add("anchor", posJson(ctx.anchor()));
        o.add("originalAnchor", posJson(ctx.originalAnchor()));
        o.addProperty("primaryAxis", ctx.primaryAxis().name());
        o.addProperty("tier", ctx.tier().name());
        o.addProperty("inclination", ctx.inclination().name());
        o.addProperty("cultureId", ctx.culture().id());
        o.addProperty("seed", ctx.seed());

        // SpinePath — primitives + endpoints.
        SpinePath spine = ctx.spinePath();
        if (spine != null) {
            JsonObject sp = new JsonObject();
            sp.addProperty("primaryAxis", spine.primaryAxis().name());
            sp.addProperty("totalLength", spine.totalLength());
            sp.add("start", posJson(spine.start()));
            sp.add("end", posJson(spine.end()));
            JsonArray segs = new JsonArray();
            PrimitiveContext pctx = PrimitiveContext.basic(level, seed);
            for (RoadPrimitive prim : spine.segments()) {
                segs.add(roadPrimitiveJson(prim, pctx));
            }
            sp.add("segments", segs);
            o.add("spinePath", sp);
        } else {
            o.add("spinePath", JsonNull.INSTANCE);
        }

        JsonArray hubs = new JsonArray();
        if (ctx.hubs() != null) {
            for (var hub : ctx.hubs()) {
                JsonObject h = new JsonObject();
                h.add("position", posJson(hub.position()));
                h.addProperty("dirX", hub.direction().x);
                h.addProperty("dirY", hub.direction().y);
                h.addProperty("dirZ", hub.direction().z);
                h.addProperty("openness", hub.openness());
                hubs.add(h);
            }
        }
        o.add("hubs", hubs);
        return o;
    }

    private static JsonObject buildingsJson(PlacementResult placement,
                                             BuildingSelector.SelectionResult sel,
                                             ReconciliationEngine.ReconciliationResult recon) {
        JsonObject o = new JsonObject();
        o.addProperty("villageViable", placement.villageViable());

        JsonArray placed = new JsonArray();
        for (PlacedBuilding pb : placement.placed()) {
            JsonObject b = new JsonObject();
            b.addProperty("type", pb.type().name());
            b.add("centre", posJson(pb.centre()));
            Footprint fp = pb.footprint();
            b.addProperty("footprintWidth", fp.width());
            b.addProperty("footprintLength", fp.length());
            b.addProperty("rotation", pb.rotation().name());
            b.addProperty("priority", pb.priority().name());
            if (pb.variantId() != null) b.addProperty("variantId", pb.variantId());
            if (pb.facingRoad() != null) {
                JsonObject fr = new JsonObject();
                fr.addProperty("kind", facingRoadKind(pb.facingRoad()));
                fr.add("start", posJson(pb.facingRoad().start()));
                fr.add("end",   posJson(pb.facingRoad().end()));
                fr.addProperty("width", pb.facingRoad().width());
                b.add("facingRoad", fr);
            }
            if (pb.frontage() != null) {
                JsonObject fg = new JsonObject();
                fg.add("buildingFront", posJson(pb.frontage().buildingFront()));
                fg.addProperty("frontDirX", pb.frontage().frontDirection().x);
                fg.addProperty("frontDirZ", pb.frontage().frontDirection().z);
                b.add("frontage", fg);
            }
            if (pb.adjunct() != null) {
                b.addProperty("hasAdjunct", true);
            }
            placed.add(b);
        }
        o.add("placed", placed);

        JsonArray dropped = new JsonArray();
        for (DroppedBuilding d : placement.dropped()) {
            JsonObject jd = new JsonObject();
            jd.addProperty("type", d.type().name());
            jd.addProperty("reason", d.reason().name());
            if (d.detail() != null) jd.addProperty("detail", d.detail());
            dropped.add(jd);
        }
        o.add("dropped", dropped);

        JsonArray unavailable = new JsonArray();
        for (UnavailableBuilding u : placement.unavailable()) {
            JsonObject ju = new JsonObject();
            ju.addProperty("type", u.type().name());
            ju.addProperty("reason", u.reason());
            unavailable.add(ju);
        }
        o.add("unavailable", unavailable);

        JsonObject counts = new JsonObject();
        for (Map.Entry<BuildingType, Integer> e : placement.placedCounts().entrySet()) {
            counts.addProperty(e.getKey().name(), e.getValue());
        }
        o.add("placedCounts", counts);

        // Selection-stage diagnostics.
        JsonObject reconObj = new JsonObject();
        reconObj.addProperty("selectedCount", sel.selected().size());
        reconObj.addProperty("dropCount", recon.drops().size());
        reconObj.addProperty("tradeFulfilledCount", recon.tradeFulfilled().size());
        o.add("reconciliation", reconObj);

        return o;
    }

    private static JsonObject roadsJson(RoadNetwork network, ServerLevel level, long seed) {
        JsonObject o = new JsonObject();
        Skeleton skeleton = network.skeleton();

        // Spine path primitives + computed centerlines.
        SpinePath spine = skeleton.spinePath();
        JsonObject sk = new JsonObject();
        sk.add("spineStart", posJson(skeleton.spineStart()));
        sk.add("spineEnd",   posJson(skeleton.spineEnd()));

        // RoadSegment list (concrete, post-frontage-attach).
        JsonArray segs = new JsonArray();
        for (RoadSegment s : skeleton.allSegments()) {
            JsonObject js = new JsonObject();
            js.addProperty("kind", facingRoadKind(s));
            js.add("start", posJson(s.start()));
            js.add("end",   posJson(s.end()));
            js.addProperty("width", s.width());
            segs.add(js);
        }
        sk.add("segments", segs);

        // Spine path RoadPrimitive instances + their centerlines.
        if (spine != null) {
            JsonArray prims = new JsonArray();
            PrimitiveContext pctx = PrimitiveContext.basic(level, seed);
            for (RoadPrimitive prim : spine.segments()) {
                prims.add(roadPrimitiveJson(prim, pctx));
            }
            sk.add("spinePathPrimitives", prims);
        }

        // Cross streets — separate listing for visualisation convenience.
        JsonArray cross = new JsonArray();
        for (CrossStreet cs : skeleton.crossStreets()) {
            JsonObject jc = new JsonObject();
            jc.add("start", posJson(cs.start()));
            jc.add("end",   posJson(cs.end()));
            jc.addProperty("width", cs.width());
            cross.add(jc);
        }
        sk.add("crossStreets", cross);

        // Junctions — pos + connected-segment count.
        JsonArray junc = new JsonArray();
        skeleton.junctions().forEach(j -> {
            JsonObject jj = new JsonObject();
            jj.add("pos", posJson(j.pos()));
            jj.addProperty("segmentCount",
                    j.segments() != null ? j.segments().size() : 0);
            junc.add(jj);
        });
        sk.add("junctions", junc);

        o.add("skeleton", sk);

        // Frontage owners — too large to dump fully; count + sample.
        JsonObject fo = new JsonObject();
        fo.addProperty("count", network.frontageOwners().size());
        // Sample (first 24) for the artifact's visualisation hint.
        JsonArray sample = new JsonArray();
        int n = 0;
        for (Map.Entry<BlockPos, BuildingType> e : network.frontageOwners().entrySet()) {
            if (n++ >= 24) break;
            JsonObject jf = new JsonObject();
            jf.add("pos", posJson(e.getKey()));
            jf.addProperty("type", e.getValue().name());
            sample.add(jf);
        }
        fo.add("sample", sample);
        o.add("frontageOwners", fo);

        return o;
    }

    private static JsonObject roadPrimitiveJson(RoadPrimitive prim, PrimitiveContext pctx) {
        JsonObject o = new JsonObject();
        o.addProperty("type", prim.typeKey());
        o.addProperty("tier", prim.tier().name());
        o.addProperty("intendedLength", prim.intendedLength());
        // Some primitives override water-capable; expose for visualiser.
        try {
            o.addProperty("waterCapable", prim.isWaterCapable());
        } catch (Throwable ignored) {
            // older primitives without the override default false.
        }
        // Computed centerline — point list. computeCenterline can throw
        // on degenerate inputs in some primitives; defend.
        JsonArray points = new JsonArray();
        try {
            var cl = prim.computeCenterline(pctx);
            if (cl != null && cl.points() != null) {
                for (BlockPos p : cl.points()) points.add(posJsonInline(p));
            }
        } catch (Throwable t) {
            // Don't fail the dump; record the failure and move on.
            o.addProperty("centerlineError", t.getClass().getSimpleName()
                    + ": " + (t.getMessage() == null ? "?" : t.getMessage()));
        }
        o.add("centerline", points);
        return o;
    }

    private static JsonArray phaseEventsJson(List<PhasedPlanner.PhaseEvent> events) {
        JsonArray a = new JsonArray();
        for (PhasedPlanner.PhaseEvent e : events) {
            JsonObject je = new JsonObject();
            je.addProperty("kind", e.kind().name());
            if (e.type() != null) je.addProperty("type", e.type().name());
            if (e.detail() != null) je.addProperty("detail", e.detail());
            // ScoreBreakdown is dense; dump its toString() if present.
            if (e.score() != null) je.addProperty("score", e.score().toString());
            a.add(je);
        }
        return a;
    }

    private static JsonArray gatewaysJson(RoadNetwork roads) {
        // Gateway descriptors live on VillageLayout.gatePositions, which
        // is built from spine endpoints + cross-street outer endpoints
        // (V2VillageSpawnerAdapter.buildSynthLayout). Mirror that derivation
        // here so the dump captures gateways without needing the synth
        // layout (which carries other transient state we'd rather omit).
        JsonArray a = new JsonArray();
        Skeleton sk = roads.skeleton();
        BlockPos spineEnd   = sk.spineEnd();
        BlockPos spineStart = sk.spineStart();
        if (spineEnd != null) {
            JsonObject g = new JsonObject();
            g.addProperty("role", "PRIMARY");
            g.add("position", posJson(spineEnd));
            g.addProperty("source", "spineEnd");
            a.add(g);
        }
        if (spineStart != null && (spineEnd == null || !spineStart.equals(spineEnd))) {
            JsonObject g = new JsonObject();
            g.addProperty("role", "SIDE");
            g.add("position", posJson(spineStart));
            g.addProperty("source", "spineStart");
            a.add(g);
        }
        for (CrossStreet cs : sk.crossStreets()) {
            if (cs.start() != null) {
                JsonObject g = new JsonObject();
                g.addProperty("role", "SIDE");
                g.add("position", posJson(cs.start()));
                g.addProperty("source", "crossStreet.start");
                a.add(g);
            }
            if (cs.end() != null) {
                JsonObject g = new JsonObject();
                g.addProperty("role", "SIDE");
                g.add("position", posJson(cs.end()));
                g.addProperty("source", "crossStreet.end");
                a.add(g);
            }
        }
        return a;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static JsonObject posJson(BlockPos pos) {
        JsonObject o = new JsonObject();
        if (pos == null) {
            o.add("missing", new com.google.gson.JsonPrimitive(true));
            return o;
        }
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        return o;
    }

    /** Tighter inline representation for centerline point lists. */
    private static JsonObject posJsonInline(BlockPos pos) {
        return posJson(pos);
    }

    private static String facingRoadKind(RoadSegment s) {
        if (s instanceof SpineSegment) return "SPINE_SEGMENT";
        if (s instanceof CrossStreet)  return "CROSS_STREET";
        return s.getClass().getSimpleName();
    }

    private static Path pickOutputFile(ServerLevel level, String slug, long tick) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        Path dir = worldRoot.resolve("litv-debug").resolve("layouts");
        // Slug sanitisation — strip filename-hostile chars.
        String safe = slug.replaceAll("[^A-Za-z0-9_.\\-]", "_");
        return dir.resolve(safe + "-" + tick + ".json");
    }

    private static int writeJsonAndReply(CommandSourceStack src, Path outFile, JsonObject root) {
        try {
            Files.createDirectories(outFile.getParent());
            String pretty = new GsonBuilder().setPrettyPrinting()
                    .serializeNulls()
                    .create()
                    .toJson((JsonElement) root);
            Files.writeString(outFile, pretty);
        } catch (IOException e) {
            src.sendFailure(Component.literal(
                    "Failed to write dump: " + e.getMessage()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "Layout dumped to " + outFile.toAbsolutePath()), false);
        return 1;
    }
}
