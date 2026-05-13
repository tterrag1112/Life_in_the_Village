package tterrag1112.life_in_the_village.Village.Planning.V2.Debug;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureRegistry;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PrimitiveContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.Anchor;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.AnchorExtent;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.AnchorType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.LayoutStrategy;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SpinePath;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.StrategySelectionResult;
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
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.OverlapAuditor;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.TerrainAdapter;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Track E1 + E1 follow-up — shared V2 layout-dump JSON serializer.
 *
 * <p>One serializer, three entry points:
 * <ol>
 *   <li>{@link #runPlanAndSerialize} — runs V2 Layers 1-4 fresh
 *       against a given origin (read-only) and emits JSON. Used
 *       by the on-demand {@code /litv layout debug dump} command.
 *       Produces schema-v2 JSON with the {@code realization}
 *       section omitted (Layer 5 didn't run).</li>
 *   <li>{@link #serializeAuto} — serializes from
 *       already-computed V2 outputs + a {@link RealizationLog}.
 *       Used by {@code V2VillageSpawnerAdapter.spawn}'s auto-dump
 *       hook. Produces schema-v2 JSON with the {@code realization}
 *       section populated.</li>
 *   <li>{@link #serializeAbort} — minimal early-abort dump
 *       (origin + reason). Used by the proximity-check abort
 *       which fires before Layers 1-4 even start.</li>
 * </ol>
 *
 * <p>All output is pretty-printed JSON written to
 * {@code <worldSave>/litv-debug/layouts/<slug>-<tick>.json}.
 *
 * <h3>Schema v2 additions vs v1</h3>
 * <ul>
 *   <li>{@code schemaVersion = 2}.</li>
 *   <li>Per placed-building flat fields: {@code realizationMode}
 *       ({@code LEVEL} / {@code PLATFORM} / {@code DROP}),
 *       {@code padTargetY}, {@code realizationReason}.</li>
 *   <li>Top-level {@code realization} section with
 *       {@code overlapConflicts[]}, {@code terrainAdaptation}
 *       summary, {@code pads[]}, {@code vegetationCleared}
 *       aggregate, {@code placementErrors[]}, and
 *       {@code viabilityFailureReasons[]}.</li>
 *   <li>Top-level {@code buildings.viabilityDropped[]} —
 *       plan-survivors that Layer 5 dropped (overlap-audit,
 *       terrain DROP, viability validator).</li>
 * </ul>
 *
 * <p>Schema bump is additive — v1 readers ignoring unknown fields
 * still parse v2 dumps without error.
 */
public final class LayoutDumpSerializer {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int SCHEMA_VERSION = 3;
    public static final int FEATURE_MAP_RADIUS = 96;
    public static final int DEFAULT_DUMP_AT_RADIUS = FEATURE_MAP_RADIUS;

    private LayoutDumpSerializer() {}

    // =========================================================================
    // Public entry points
    // =========================================================================

    /**
     * Plan-only path: runs V2 Layers 1-4 from scratch and returns
     * the JSON. Used by the on-demand command. {@code commandKind}
     * is {@code "dump"} or {@code "dump_at"} per the command
     * variant.
     */
    public static JsonObject runPlanAndSerialize(ServerLevel level, BlockPos origin,
                                                  int radius, String commandKind,
                                                  String villageName, String villageId,
                                                  long tick) {
        long worldSeed = level.getSeed();
        long planSeed = worldSeed
                ^ ((long) origin.hashCode() * 31L
                        + (villageName != null ? villageName.hashCode() : 0));

        V2FeatureMap fmap = V2FeatureMap.scan(level, origin, radius);
        Culture culture = CultureRegistry.getOrDefault(CultureRegistry.DEFAULT_ID);
        SiteContext siteCtx = SiteAnalyzer.analyze(fmap, culture, planSeed, level);

        if (siteCtx.tier() == ViabilityTier.UNVIABLE) {
            JsonObject root = buildHeader(commandKind, tick, worldSeed, planSeed,
                    level, origin, villageName, villageId, culture);
            root.add("siteContext", siteContextJson(siteCtx, level, worldSeed));
            root.addProperty("aborted", true);
            root.addProperty("abortReason", "tier=UNVIABLE; layers 3-4 skipped");
            return root;
        }

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

        PhasedPlanner.Result phased =
                PhasedPlanner.run(siteCtx, fmap, sorted, unavailable, level, tradeFulfilled);

        return assemble(
                commandKind, tick, worldSeed, planSeed, level, origin,
                villageName, villageId, culture,
                siteCtx, sel, recon, phased.placement(), phased.network(),
                phased.events(), /*realizationLog*/ null);
    }

    /**
     * Auto-dump path: serializes from already-computed V2 outputs
     * + realization log. Used by
     * {@code V2VillageSpawnerAdapter.spawn}. Any input may be null
     * for early aborts where state wasn't yet built (e.g. abort
     * before Layer 3 → sel/recon/placement/roads are null).
     */
    public static JsonObject serializeAuto(ServerLevel level, BlockPos origin, long tick,
                                            String villageName, String villageId,
                                            Culture culture,
                                            V2FeatureMap fmap, SiteContext siteCtx,
                                            BuildingSelector.SelectionResult sel,
                                            ReconciliationEngine.ReconciliationResult recon,
                                            PlacementResult placement, RoadNetwork roads,
                                            List<PhasedPlanner.PhaseEvent> events,
                                            RealizationLog log) {
        long worldSeed = level.getSeed();
        long planSeed = worldSeed
                ^ ((long) origin.hashCode() * 31L
                        + (villageName != null ? villageName.hashCode() : 0));
        return assemble("auto", tick, worldSeed, planSeed, level, origin,
                villageName, villageId,
                culture != null ? culture
                        : CultureRegistry.getOrDefault(CultureRegistry.DEFAULT_ID),
                siteCtx, sel, recon, placement, roads, events, log);
    }

    /** Minimal abort dump — origin + reason only. */
    public static JsonObject serializeAbort(ServerLevel level, BlockPos origin, long tick,
                                             String villageName, String reason) {
        Culture culture = CultureRegistry.getOrDefault(CultureRegistry.DEFAULT_ID);
        long worldSeed = level.getSeed();
        long planSeed = worldSeed
                ^ ((long) origin.hashCode() * 31L
                        + (villageName != null ? villageName.hashCode() : 0));
        JsonObject root = buildHeader("auto", tick, worldSeed, planSeed,
                level, origin, villageName, null, culture);
        root.addProperty("aborted", true);
        root.addProperty("abortReason", reason == null ? "unknown" : reason);
        return root;
    }

    /**
     * Pretty-print + write to {@code <worldSave>/litv-debug/layouts/<slug>-<tick>.json}.
     * Returns the path written, or empty on IO failure. Never throws.
     */
    public static Optional<Path> writeDump(ServerLevel level, String slug, long tick,
                                            JsonObject root) {
        try {
            Path outFile = pickOutputFile(level, slug, tick);
            Files.createDirectories(outFile.getParent());
            String pretty = new GsonBuilder().setPrettyPrinting()
                    .serializeNulls()
                    .create()
                    .toJson((JsonElement) root);
            Files.writeString(outFile, pretty);
            return Optional.of(outFile);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[LayoutDumpSerializer] write failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // =========================================================================
    // Internal assembly — handles null inputs defensively for partial-state aborts
    // =========================================================================

    private static JsonObject assemble(String commandKind, long tick,
                                       long worldSeed, long planSeed,
                                       ServerLevel level, BlockPos origin,
                                       String villageName, String villageId,
                                       Culture culture,
                                       SiteContext siteCtx,
                                       BuildingSelector.SelectionResult sel,
                                       ReconciliationEngine.ReconciliationResult recon,
                                       PlacementResult placement, RoadNetwork roads,
                                       List<PhasedPlanner.PhaseEvent> events,
                                       RealizationLog log) {
        JsonObject root = buildHeader(commandKind, tick, worldSeed, planSeed,
                level, origin, villageName, villageId, culture);

        if (siteCtx != null) {
            root.add("siteContext", siteContextJson(siteCtx, level, worldSeed));
        }

        if (log != null && log.aborted()) {
            root.addProperty("aborted", true);
            log.abortReason().ifPresent(r -> root.addProperty("abortReason", r));
        }

        if (placement != null) {
            root.add("buildings", buildingsJson(placement, sel, recon, log));
        }
        if (roads != null) {
            root.add("roads", roadsJson(roads, level, worldSeed));
            root.add("gateways", gatewaysJson(roads));
        }
        if (events != null) {
            root.add("phaseEvents", phaseEventsJson(events));
        }
        if (log != null) {
            root.add("realization", realizationJson(log));
        }
        return root;
    }

    private static JsonObject buildHeader(String commandKind, long tick,
                                          long worldSeed, long planSeed,
                                          ServerLevel level, BlockPos origin,
                                          String villageName, String villageId,
                                          Culture culture) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("command", commandKind);
        root.addProperty("tick", tick);
        root.addProperty("worldSeed", worldSeed);
        root.addProperty("planSeed", planSeed);
        root.addProperty("dimension", level.dimension().location().toString());
        root.add("origin", posJson(origin));
        JsonObject v = new JsonObject();
        if (villageName != null) v.addProperty("name", villageName);
        if (villageId != null)   v.addProperty("id", villageId);
        v.addProperty("culture", culture.id());
        root.add("village", v);
        return root;
    }

    // =========================================================================
    // Section serializers — preserved verbatim from the v1 command
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

        // Track E1 — anchors (schema v3 additive).
        JsonArray anchors = new JsonArray();
        if (ctx.anchors() != null) {
            for (Anchor a : ctx.anchors()) anchors.add(anchorJson(a));
        }
        o.add("anchors", anchors);

        // Track E1B — strategy selection (schema v3 additive).
        if (ctx.strategy() != null) {
            o.add("strategy", strategyJson(ctx.strategy()));
        }
        return o;
    }

    private static JsonObject strategyJson(StrategySelectionResult result) {
        JsonObject o = new JsonObject();
        LayoutStrategy s = result.strategy();
        o.addProperty("id",          s.id());
        o.addProperty("inclination", s.inclination().name());
        o.addProperty("topology",    s.topology().name());
        o.addProperty("description", s.description());
        o.addProperty("score",       result.score());
        if (result.primaryAnchor() != null) {
            o.addProperty("primaryAnchorId", result.primaryAnchor().id());
        } else {
            o.add("primaryAnchorId", com.google.gson.JsonNull.INSTANCE);
        }
        JsonArray secondaries = new JsonArray();
        for (Anchor a : result.secondaryAnchors()) secondaries.add(a.id());
        o.add("secondaryAnchorIds", secondaries);

        JsonArray prims = new JsonArray();
        for (String p : s.primitives()) prims.add(p);
        o.add("intendedPrimitives", prims);

        JsonObject bindings = new JsonObject();
        for (var entry : s.bindings().preferences().entrySet()) {
            JsonArray prefs = new JsonArray();
            for (AnchorType at : entry.getValue()) prefs.add(at.name());
            bindings.add(entry.getKey().name(), prefs);
        }
        o.add("intendedBindings", bindings);

        JsonArray log = new JsonArray();
        for (String line : result.selectionLog()) log.add(line);
        o.add("selectionLog", log);
        return o;
    }

    private static JsonObject anchorJson(Anchor a) {
        JsonObject o = new JsonObject();
        o.addProperty("id", a.id());
        o.addProperty("type", a.type().name());
        o.add("centre", posJson(a.centre()));
        o.addProperty("quality", a.quality());
        AnchorExtent ext = a.extent();
        JsonObject je = new JsonObject();
        je.addProperty("originX", ext.originX());
        je.addProperty("originZ", ext.originZ());
        je.addProperty("width",   ext.width());
        je.addProperty("length",  ext.length());
        o.add("extent", je);
        // Track E1A — linear anchor types always emit a nested
        // dir object with normalized (dirX, dirZ). Non-linear
        // types omit the field entirely (rather than serializing
        // it as null).
        if (a.type().isLinear()) {
            JsonObject dir = new JsonObject();
            dir.addProperty("dirX", a.dirX());
            dir.addProperty("dirZ", a.dirZ());
            o.add("dir", dir);
        }
        if (a.metadata() != null && !a.metadata().isEmpty()) {
            JsonObject m = new JsonObject();
            for (var entry : a.metadata().entrySet()) {
                Object v = entry.getValue();
                if (v instanceof Number n) m.addProperty(entry.getKey(), n);
                else if (v instanceof Boolean bv) m.addProperty(entry.getKey(), bv);
                else m.addProperty(entry.getKey(), v == null ? "" : v.toString());
            }
            o.add("metadata", m);
        }
        return o;
    }

    private static JsonObject buildingsJson(PlacementResult placement,
                                             BuildingSelector.SelectionResult sel,
                                             ReconciliationEngine.ReconciliationResult recon,
                                             RealizationLog log) {
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
            // Track E1 follow-up — flat per-building realization fields.
            if (log != null) {
                log.decisionFor(pb).ifPresent(d -> {
                    b.addProperty("realizationMode", d.mode().name());
                    b.addProperty("padTargetY", d.targetY());
                    d.reason().ifPresent(r -> b.addProperty("realizationReason", r));
                });
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

        // Track E1 follow-up — viabilityDropped[]: plan-survivors that
        // Layer 5 took out (terrain DROP, viability failure). Computed
        // from RealizationLog cross-referenced with placement.placed().
        JsonArray viabilityDropped = new JsonArray();
        if (log != null) {
            for (TerrainAdapter.AdaptationDecision d : log.terrainDecisions()) {
                if (d.mode() != TerrainAdapter.AdaptationMode.DROP) continue;
                JsonObject jv = new JsonObject();
                jv.addProperty("type", d.building().type().name());
                jv.add("plannedCentre", posJson(d.building().centre()));
                jv.addProperty("source", "terrain_adapter");
                d.reason().ifPresent(r -> jv.addProperty("reason", r));
                viabilityDropped.add(jv);
            }
        }
        o.add("viabilityDropped", viabilityDropped);

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

        JsonObject reconObj = new JsonObject();
        if (sel != null)   reconObj.addProperty("selectedCount", sel.selected().size());
        if (recon != null) {
            reconObj.addProperty("dropCount", recon.drops().size());
            reconObj.addProperty("tradeFulfilledCount", recon.tradeFulfilled().size());
        }
        o.add("reconciliation", reconObj);

        return o;
    }

    private static JsonObject roadsJson(RoadNetwork network, ServerLevel level, long seed) {
        JsonObject o = new JsonObject();
        Skeleton skeleton = network.skeleton();
        SpinePath spine = skeleton.spinePath();

        JsonObject sk = new JsonObject();
        sk.add("spineStart", posJson(skeleton.spineStart()));
        sk.add("spineEnd",   posJson(skeleton.spineEnd()));

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

        if (spine != null) {
            JsonArray prims = new JsonArray();
            PrimitiveContext pctx = PrimitiveContext.basic(level, seed);
            for (RoadPrimitive prim : spine.segments()) {
                prims.add(roadPrimitiveJson(prim, pctx));
            }
            sk.add("spinePathPrimitives", prims);
        }

        JsonArray cross = new JsonArray();
        for (CrossStreet cs : skeleton.crossStreets()) {
            JsonObject jc = new JsonObject();
            jc.add("start", posJson(cs.start()));
            jc.add("end",   posJson(cs.end()));
            jc.addProperty("width", cs.width());
            cross.add(jc);
        }
        sk.add("crossStreets", cross);

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

        JsonObject fo = new JsonObject();
        fo.addProperty("count", network.frontageOwners().size());
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
        try {
            o.addProperty("waterCapable", prim.isWaterCapable());
        } catch (Throwable ignored) {}
        JsonArray points = new JsonArray();
        try {
            var cl = prim.computeCenterline(pctx);
            if (cl != null && cl.points() != null) {
                for (BlockPos p : cl.points()) points.add(posJson(p));
            }
        } catch (Throwable t) {
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
            if (e.score() != null) je.addProperty("score", e.score().toString());
            a.add(je);
        }
        return a;
    }

    private static JsonArray gatewaysJson(RoadNetwork roads) {
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
    // Realization section (schema v2)
    // =========================================================================

    private static JsonObject realizationJson(RealizationLog log) {
        JsonObject o = new JsonObject();

        // Overlap conflicts.
        JsonArray conflicts = new JsonArray();
        log.overlapReport().ifPresent(report -> {
            for (OverlapAuditor.Conflict c : report.conflicts()) {
                JsonObject jc = new JsonObject();
                jc.addProperty("description", c.description());
                jc.add("pos", posJson(c.pos()));
                jc.addProperty("aDesc", c.aDesc());
                jc.addProperty("bDesc", c.bDesc());
                conflicts.add(jc);
            }
            o.addProperty("overlapFatal", report.fatal());
        });
        o.add("overlapConflicts", conflicts);

        // Terrain adaptation summary + pads list.
        int level = 0, platform = 0, drop = 0;
        JsonArray pads = new JsonArray();
        for (TerrainAdapter.AdaptationDecision d : log.terrainDecisions()) {
            switch (d.mode()) {
                case LEVEL    -> level++;
                case PLATFORM -> platform++;
                case DROP     -> drop++;
            }
            if (d.mode() == TerrainAdapter.AdaptationMode.DROP) continue;
            JsonObject jp = new JsonObject();
            jp.addProperty("type", d.building().type().name());
            jp.add("centre", posJson(d.building().centre()));
            Footprint fp = d.building().footprint();
            jp.addProperty("footprintWidth", fp.width());
            jp.addProperty("footprintLength", fp.length());
            jp.addProperty("mode", d.mode().name());
            jp.addProperty("padTargetY", d.targetY());
            d.reason().ifPresent(r -> jp.addProperty("reason", r));
            pads.add(jp);
        }
        JsonObject terrain = new JsonObject();
        terrain.addProperty("levelCount", level);
        terrain.addProperty("platformCount", platform);
        terrain.addProperty("dropCount", drop);
        o.add("terrainAdaptation", terrain);
        o.add("pads", pads);

        // Vegetation aggregate.
        JsonObject veg = new JsonObject();
        veg.addProperty("totalBlocks", log.vegetationBlocksTotal());
        veg.addProperty("byBuildingCount", log.vegetationByBuildingCount());
        veg.addProperty("byRoadSegmentCount", log.vegetationByRoadCount());
        o.add("vegetationCleared", veg);

        // Placement errors.
        JsonArray errors = new JsonArray();
        for (RealizationLog.PlacementError pe : log.placementErrors()) {
            JsonObject je = new JsonObject();
            je.addProperty("type", pe.type().name());
            if (pe.variantId() != null) je.addProperty("variantId", pe.variantId());
            je.addProperty("reason", pe.reason());
            errors.add(je);
        }
        o.add("placementErrors", errors);

        // Viability failure reasons (kingdom-level list).
        JsonArray reasons = new JsonArray();
        log.viabilityFailureReasons().ifPresent(list -> list.forEach(reasons::add));
        o.add("viabilityFailureReasons", reasons);

        return o;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    public static JsonObject posJson(BlockPos pos) {
        JsonObject o = new JsonObject();
        if (pos == null) {
            o.addProperty("missing", true);
            return o;
        }
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        return o;
    }

    private static String facingRoadKind(RoadSegment s) {
        if (s instanceof SpineSegment) return "SPINE_SEGMENT";
        if (s instanceof CrossStreet)  return "CROSS_STREET";
        return s.getClass().getSimpleName();
    }

    /**
     * Track E1 — formats a one-line anchor count summary suitable
     * for the INFO log + chat output. Example output:
     * {@code "anchors detected: 5 FLAT_FERTILE, 2 FOREST_EDGE, 1 WATER_EDGE"}.
     */
    public static String anchorSummary(java.util.List<Anchor> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return "anchors detected: none";
        }
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Anchor a : anchors) {
            counts.merge(a.type().name(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("anchors detected: ");
        boolean first = true;
        for (var e : counts.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getValue()).append(' ').append(e.getKey());
            first = false;
        }
        return sb.toString();
    }

    public static Path pickOutputFile(ServerLevel level, String slug, long tick) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        Path dir = worldRoot.resolve("litv-debug").resolve("layouts");
        String safe = slug.replaceAll("[^A-Za-z0-9_.\\-]", "_");
        return dir.resolve(safe + "-" + tick + ".json");
    }
}
