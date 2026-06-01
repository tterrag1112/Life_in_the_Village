package tterrag1112.life_in_the_village.Village.Planning.V2.Harness;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Debug.LayoutDumpSerializer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkSpec;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.PrimaryBinding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DroppedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementResult;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.CrossStreet;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.Junction;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.PhasedPlanner;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadSegment;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.Skeleton;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.SpineSegment;

import java.util.Map;

/**
 * Track E1 Phase A regression diagnostic — v4-shaped JSON dump
 * emitted from the harness for visualizer load.
 *
 * <p>Mirrors the field shape of
 * {@code LayoutDumpSerializer.assemble}'s output, but built
 * harness-side from the in-memory {@link PhasedPlanner.Result} +
 * {@link SiteContext} since the harness has no {@code ServerLevel}.
 * ServerLevel-only fields are omitted intentionally — header has
 * no {@code dimension}, road primitive {@code centerline}s are
 * absent (the visualizer can reconstruct them from segment
 * endpoints), and {@code worldSeed} mirrors the run's plan seed.
 *
 * <p>What IS emitted: schema version, header, siteContext (anchor,
 * tier, inclination, strategy id, network nodes/edges/bindings,
 * spine endpoints + segments), buildings (placed + dropped), and
 * the road skeleton (segments + cross-streets + junctions). That's
 * the subset the connectivity audit + a basic visualizer need.
 *
 * <p>Behavior-neutral; gated by {@link HarnessDebugSink} so the
 * file is only written when {@code -Dharness.debug.candidates=true}
 * is set, matching the rest of the diagnostic tooling.
 */
public final class HarnessDump {

    private HarnessDump() {}

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    public static String toJson(Battery.Run run, RunExecutor.ExecResult result) {
        return GSON.toJson(build(run, result));
    }

    private static JsonObject build(Battery.Run run,
                                    RunExecutor.ExecResult result) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", LayoutDumpSerializer.SCHEMA_VERSION);
        root.addProperty("command", "harness");
        root.addProperty("tick", 0);
        root.addProperty("worldSeed", run.config().seed());
        root.addProperty("planSeed", run.config().seed());
        // dimension intentionally omitted — no ServerLevel.
        root.add("origin", LayoutDumpSerializer.posJson(new BlockPos(0, 64, 0)));

        JsonObject village = new JsonObject();
        village.addProperty("name", run.label());
        village.addProperty("id", run.shape().name() + "_" + run.config().label());
        SiteContext siteCtx = result.details() == null ? null
                : result.details().siteCtx();
        if (siteCtx != null && siteCtx.culture() != null) {
            village.addProperty("culture", siteCtx.culture().id());
        }
        root.add("village", village);

        JsonObject harnessMeta = new JsonObject();
        harnessMeta.addProperty("terrain", run.shape().name());
        harnessMeta.addProperty("config", run.config().label());
        harnessMeta.addProperty("note", "v4-shaped; harness-emitted; "
                + "dimension and road-primitive centerlines omitted (no ServerLevel)");
        root.add("harness", harnessMeta);

        if (siteCtx != null) {
            root.add("siteContext", siteContextJson(siteCtx));
        }

        PhasedPlanner.Result phased = result.details() == null ? null
                : result.details().phased();
        if (phased != null) {
            root.add("buildings", buildingsJson(phased.placement(),
                    phased.nucleusContexts()));
            if (phased.network() != null) {
                root.add("roads", roadsJson(phased.network()));
            }
            root.add("phaseEvents", phaseEventsJson(phased.events()));
        }
        return root;
    }

    // =========================================================================
    // siteContext
    // =========================================================================

    private static JsonObject siteContextJson(SiteContext ctx) {
        JsonObject o = new JsonObject();
        o.add("anchor", LayoutDumpSerializer.posJson(ctx.anchor()));
        if (ctx.originalAnchor() != null) {
            o.add("originalAnchor", LayoutDumpSerializer.posJson(ctx.originalAnchor()));
        }
        if (ctx.primaryAxis() != null) {
            o.addProperty("primaryAxis", ctx.primaryAxis().name());
        }
        o.addProperty("tier", ctx.tier().name());
        o.addProperty("inclination", ctx.inclination().name());
        o.addProperty("seed", ctx.seed());

        if (ctx.strategy() != null && ctx.strategy().strategy() != null) {
            JsonObject strat = new JsonObject();
            strat.addProperty("id", ctx.strategy().strategy().id());
            strat.addProperty("score", ctx.strategy().score());
            o.add("strategy", strat);
        }

        NetworkSpec spec = ctx.network();
        if (spec != null) {
            JsonObject net = new JsonObject();
            net.addProperty("topology", spec.topology().name());
            net.addProperty("nodes", spec.nodes().size());
            net.addProperty("edges", spec.edges().size());
            JsonArray bindings = new JsonArray();
            for (PrimaryBinding pb : spec.primaryBindings()) {
                JsonObject b = new JsonObject();
                b.addProperty("type", pb.type().name());
                b.add("position", LayoutDumpSerializer.posJson(pb.position()));
                bindings.add(b);
            }
            net.add("primaryBindings", bindings);
            o.add("network", net);
        }
        return o;
    }

    // =========================================================================
    // buildings
    // =========================================================================

    private static JsonObject buildingsJson(PlacementResult placement,
            Map<PlacedBuilding, PhasedPlanner.NucleusContext> nucleusContexts) {
        JsonObject o = new JsonObject();
        if (placement == null) return o;
        o.addProperty("villageViable", placement.villageViable());

        JsonArray placed = new JsonArray();
        for (PlacedBuilding pb : placement.placed()) {
            JsonObject b = new JsonObject();
            b.addProperty("type", pb.type().name());
            b.add("centre", LayoutDumpSerializer.posJson(pb.centre()));
            b.addProperty("footprintWidth", pb.footprint().width());
            b.addProperty("footprintLength", pb.footprint().length());
            b.addProperty("rotation", pb.rotation().name());
            b.addProperty("priority", pb.priority().name());
            if (pb.variantId() != null) b.addProperty("variantId", pb.variantId());
            if (pb.facingRoad() != null) {
                JsonObject fr = new JsonObject();
                fr.addProperty("kind", segmentKind(pb.facingRoad()));
                fr.add("start", LayoutDumpSerializer.posJson(pb.facingRoad().start()));
                fr.add("end",   LayoutDumpSerializer.posJson(pb.facingRoad().end()));
                fr.addProperty("width", pb.facingRoad().width());
                b.add("facingRoad", fr);
            }
            if (pb.frontage() != null) {
                JsonObject fg = new JsonObject();
                fg.add("buildingFront",
                        LayoutDumpSerializer.posJson(pb.frontage().buildingFront()));
                fg.addProperty("frontDirX", pb.frontage().frontDirection().x);
                fg.addProperty("frontDirZ", pb.frontage().frontDirection().z);
                b.add("frontage", fg);
            }
            PhasedPlanner.NucleusContext nc = nucleusContexts == null
                    ? null : nucleusContexts.get(pb);
            if (nc != null) {
                JsonObject nctx = new JsonObject();
                nctx.addProperty("primaryNucleusKind", nc.kind().name());
                if (nc.anchorId() != null) {
                    nctx.addProperty("primaryNucleusAnchorId", nc.anchorId());
                }
                if (nc.buildingType() != null) {
                    nctx.addProperty("primaryNucleusBuildingType",
                            nc.buildingType().name());
                }
                nctx.addProperty("distanceToPrimaryNucleus", nc.distance());
                b.add("nucleusContext", nctx);
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

        JsonObject countsObj = new JsonObject();
        for (Map.Entry<BuildingType, Integer> e : placement.placedCounts().entrySet()) {
            countsObj.addProperty(e.getKey().name(), e.getValue());
        }
        o.add("placedCounts", countsObj);
        return o;
    }

    // =========================================================================
    // roads
    // =========================================================================

    private static JsonObject roadsJson(RoadNetwork network) {
        JsonObject o = new JsonObject();
        Skeleton skeleton = network.skeleton();

        JsonObject sk = new JsonObject();
        sk.add("spineStart", LayoutDumpSerializer.posJson(skeleton.spineStart()));
        sk.add("spineEnd",   LayoutDumpSerializer.posJson(skeleton.spineEnd()));

        JsonArray segs = new JsonArray();
        int spineIdx = 0, crossIdx = 0;
        for (RoadSegment s : skeleton.allSegments()) {
            JsonObject js = new JsonObject();
            String id;
            if (s instanceof SpineSegment) id = "S" + (spineIdx++);
            else if (s instanceof CrossStreet) id = "X" + (crossIdx++);
            else id = s.getClass().getSimpleName() + spineIdx + crossIdx;
            js.addProperty("id", id);
            js.addProperty("kind", segmentKind(s));
            js.add("start", LayoutDumpSerializer.posJson(s.start()));
            js.add("end",   LayoutDumpSerializer.posJson(s.end()));
            js.addProperty("width", s.width());
            segs.add(js);
        }
        sk.add("segments", segs);

        JsonArray cross = new JsonArray();
        for (CrossStreet cs : skeleton.crossStreets()) {
            JsonObject jc = new JsonObject();
            jc.add("start", LayoutDumpSerializer.posJson(cs.start()));
            jc.add("end",   LayoutDumpSerializer.posJson(cs.end()));
            jc.add("spineJunction",
                    LayoutDumpSerializer.posJson(cs.spineJunction()));
            jc.addProperty("width", cs.width());
            cross.add(jc);
        }
        sk.add("crossStreets", cross);

        JsonArray junc = new JsonArray();
        for (Junction j : skeleton.junctions()) {
            JsonObject jj = new JsonObject();
            jj.add("pos", LayoutDumpSerializer.posJson(j.pos()));
            jj.addProperty("segmentCount",
                    j.segments() != null ? j.segments().size() : 0);
            junc.add(jj);
        }
        sk.add("junctions", junc);

        o.add("skeleton", sk);

        JsonObject fo = new JsonObject();
        fo.addProperty("count", network.frontageOwners().size());
        o.add("frontageOwners", fo);
        return o;
    }

    private static String segmentKind(RoadSegment s) {
        if (s instanceof SpineSegment) return "SPINE_SEGMENT";
        if (s instanceof CrossStreet)  return "CROSS_STREET";
        return s.getClass().getSimpleName();
    }

    // =========================================================================
    // phaseEvents
    // =========================================================================

    private static JsonArray phaseEventsJson(java.util.List<PhasedPlanner.PhaseEvent> events) {
        JsonArray arr = new JsonArray();
        if (events == null) return arr;
        for (PhasedPlanner.PhaseEvent e : events) {
            JsonObject je = new JsonObject();
            je.addProperty("kind", e.kind().name());
            if (e.type() != null) je.addProperty("type", e.type().name());
            if (e.detail() != null) je.addProperty("detail", e.detail());
            if (e.score() != null) {
                JsonObject sc = new JsonObject();
                sc.addProperty("terrain", e.score().terrain());
                sc.addProperty("adjacency", e.score().adjacency());
                sc.addProperty("centrality", e.score().centrality());
                sc.addProperty("total", e.score().total());
                je.add("score", sc);
            }
            arr.add(je);
        }
        return arr;
    }
}
