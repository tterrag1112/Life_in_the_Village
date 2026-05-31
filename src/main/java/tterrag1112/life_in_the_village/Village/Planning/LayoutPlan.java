package tterrag1112.life_in_the_village.Village.Planning;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaRegion;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 19: immutable plan-as-contract handoff between the planner
 * and downstream consumers (spawner, decorator, expansion).
 *
 * <p>Built ONCE at the end of planning from the fully-composed
 * planning state. Spawner and decorator read this record instead of
 * mutable planning-time scratch fields.
 *
 * <p>The arrow is one-way. There is no {@code toLayout()} accessor;
 * downstream code that wants to mutate state has to do so against
 * its own concrete types (Village, VillageSavedData, ServerLevel),
 * not back into the layout.
 *
 * <h3>Status semantics</h3>
 * {@link #status} is the FINAL recipe status after the cascade
 * settled. Values: {@code OK} (plan succeeded, buildings+plots
 * valid) or {@code ABORT} (plan was marked unplannable; spawner
 * should refuse to attempt placement). Intermediate cascade states
 * (RETRY, FALLBACK) don't appear on the plan because the engine
 * resolves them before calling {@code build()}.
 *
 * <h3>UUIDs</h3>
 * {@link PlacedBuilding#id()} is a planning-time deterministic UUID
 * derived from the village centre and the slot index. Realisation
 * may use this id or substitute its own; the plan is immutable
 * BEFORE realisation and stable across re-builds with the same
 * centre+seed.
 */
public record LayoutPlan(
        BlockPos centre,
        ShapeType finalShape,

        @Nullable Plaza plaza,
        List<PlazaRegion> plazaRegions,

        List<RoadEdge> roads,

        List<PlacedBuilding> buildings,

        List<PlannedPlot> plotSlots,

        Map<AnchorKind, BlockPos> anchors,

        /** OK if planning succeeded; ABORT if marked unplannable. */
        Status status,
        int truncations,
        int cascadeRetries,

        /** Non-null when {@link #status} == ABORT. */
        @Nullable String unplannableReason) {

    /** Final cascade status visible on a plan. RETRY/FALLBACK never
     *  appear — they're resolved before {@code build()} returns. */
    public enum Status {
        OK, ABORT;
        public static final Codec<Status> CODEC =
                Codec.STRING.xmap(Status::valueOf, Status::name);
    }

    // ── Phase 20a inline enum codecs (centralised here to avoid
    // touching every enum file) ──────────────────────────────────────
    private static final Codec<EdgeRole> EDGE_ROLE_CODEC =
            Codec.STRING.xmap(EdgeRole::valueOf, EdgeRole::name);
    private static final Codec<RoadShape.RoadTier> ROAD_TIER_CODEC =
            Codec.STRING.xmap(RoadShape.RoadTier::valueOf, RoadShape.RoadTier::name);
    private static final Codec<BuildingType> BUILDING_TYPE_CODEC =
            Codec.STRING.xmap(BuildingType::valueOf, BuildingType::name);
    private static final Codec<Rotation> ROTATION_CODEC =
            Codec.STRING.xmap(Rotation::valueOf, Rotation::name);
    private static final Codec<FarmPlot.PlotSubtype> PLOT_SUBTYPE_CODEC =
            Codec.STRING.xmap(FarmPlot.PlotSubtype::valueOf,
                              FarmPlot.PlotSubtype::name);
    private static final Codec<ShapeType> SHAPE_TYPE_CODEC =
            Codec.STRING.xmap(ShapeType::valueOf, ShapeType::name);
    private static final Codec<AnchorKind> ANCHOR_KIND_CODEC =
            Codec.STRING.xmap(AnchorKind::valueOf, AnchorKind::name);

    // ── Top-level CODEC (Phase 20a) ───────────────────────────────────
    public static final Codec<LayoutPlan> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.fieldOf("centre").forGetter(LayoutPlan::centre),
            SHAPE_TYPE_CODEC.fieldOf("finalShape").forGetter(LayoutPlan::finalShape),
            Plaza.CODEC.optionalFieldOf("plaza")
                    .forGetter(p -> Optional.ofNullable(p.plaza())),
            PlazaRegion.CODEC.listOf().optionalFieldOf("plazaRegions", List.of())
                    .forGetter(LayoutPlan::plazaRegions),
            RoadEdge.CODEC.listOf().optionalFieldOf("roads", List.of())
                    .forGetter(LayoutPlan::roads),
            PlacedBuilding.CODEC.listOf().optionalFieldOf("buildings", List.of())
                    .forGetter(LayoutPlan::buildings),
            PlannedPlot.CODEC.listOf().optionalFieldOf("plotSlots", List.of())
                    .forGetter(LayoutPlan::plotSlots),
            Codec.unboundedMap(ANCHOR_KIND_CODEC, BlockPos.CODEC)
                    .optionalFieldOf("anchors", Map.of())
                    .forGetter(LayoutPlan::anchors),
            Status.CODEC.optionalFieldOf("status", Status.OK).forGetter(LayoutPlan::status),
            Codec.INT.optionalFieldOf("truncations", 0).forGetter(LayoutPlan::truncations),
            Codec.INT.optionalFieldOf("cascadeRetries", 0).forGetter(LayoutPlan::cascadeRetries),
            Codec.STRING.optionalFieldOf("unplannableReason")
                    .forGetter(p -> Optional.ofNullable(p.unplannableReason()))
    ).apply(i, (centre, finalShape, plaza, plazaRegions, roads, buildings,
                plotSlots, anchors, status, truncations, retries,
                reason) -> new LayoutPlan(
                    centre, finalShape, plaza.orElse(null), plazaRegions,
                    roads, buildings, plotSlots, anchors,
                    status, truncations, retries, reason.orElse(null))));

    /**
     * One road edge as the spawner / decorator / expansion sees it.
     * The {@link RoadGraph} stores {@code from}/{@code to} as node IDs;
     * we resolve them to {@link BlockPos} at build-time so the plan is
     * self-contained.
     */
    public record RoadEdge(
            int edgeId,
            EdgeRole role,
            RoadShape.RoadTier tier,
            List<BlockPos> centerline,
            BlockPos from,
            BlockPos to) {
        public RoadEdge { centerline = List.copyOf(centerline); }

        public static final Codec<RoadEdge> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("edgeId").forGetter(RoadEdge::edgeId),
                EDGE_ROLE_CODEC.fieldOf("role").forGetter(RoadEdge::role),
                ROAD_TIER_CODEC.fieldOf("tier").forGetter(RoadEdge::tier),
                BlockPos.CODEC.listOf().fieldOf("centerline").forGetter(RoadEdge::centerline),
                BlockPos.CODEC.fieldOf("from").forGetter(RoadEdge::from),
                BlockPos.CODEC.fieldOf("to").forGetter(RoadEdge::to)
        ).apply(i, RoadEdge::new));
    }

    /**
     * One committed building. Spawner consumes these in iteration
     * order to call {@code BuildingPlacer.placeAndRegister}. The
     * spawner's own pivot computation derives from
     * {@code centre + rotation + (footprintW, footprintL)} — the plan
     * does not pre-resolve a pivot because the computation is
     * tightly coupled to NeoForge's structure-template placement
     * convention.
     *
     * @param sectorId  matcher's recorded sector for this building, or
     *                  {@code "unknown"} if it claimed a flat-pool or
     *                  Plaza slot (Plaza slots aren't in any Sector).
     */
    public record PlacedBuilding(
            UUID id,
            BuildingType type,
            BlockPos centre,
            Rotation rotation,
            int footprintWidth,
            int footprintLength,
            String sectorId,
            List<BlockPos> feedingRoad) {
        public PlacedBuilding {
            feedingRoad = feedingRoad != null
                    ? List.copyOf(feedingRoad) : List.of();
        }

        public static final Codec<PlacedBuilding> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(PlacedBuilding::id),
                BUILDING_TYPE_CODEC.fieldOf("type").forGetter(PlacedBuilding::type),
                BlockPos.CODEC.fieldOf("centre").forGetter(PlacedBuilding::centre),
                ROTATION_CODEC.fieldOf("rotation").forGetter(PlacedBuilding::rotation),
                Codec.INT.fieldOf("footprintWidth").forGetter(PlacedBuilding::footprintWidth),
                Codec.INT.fieldOf("footprintLength").forGetter(PlacedBuilding::footprintLength),
                Codec.STRING.optionalFieldOf("sectorId", "unknown").forGetter(PlacedBuilding::sectorId),
                BlockPos.CODEC.listOf().optionalFieldOf("feedingRoad", List.of())
                        .forGetter(PlacedBuilding::feedingRoad)
        ).apply(i, PlacedBuilding::new));
    }

    /**
     * One planned farm plot. Carries everything the farm realiser needs
     * to regenerate the plot's deterministic shape and place it on
     * the planned position.
     */
    public record PlannedPlot(
            BlockPos ownerFarmhousePos,
            FarmPlot.PlotSubtype subtype,
            BlockPos centre,
            int halfW,
            int halfL,
            int edgeJitterSeed) {

        public static final Codec<PlannedPlot> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("ownerFarmhousePos").forGetter(PlannedPlot::ownerFarmhousePos),
                PLOT_SUBTYPE_CODEC.fieldOf("subtype").forGetter(PlannedPlot::subtype),
                BlockPos.CODEC.fieldOf("centre").forGetter(PlannedPlot::centre),
                Codec.INT.fieldOf("halfW").forGetter(PlannedPlot::halfW),
                Codec.INT.fieldOf("halfL").forGetter(PlannedPlot::halfL),
                Codec.INT.fieldOf("edgeJitterSeed").forGetter(PlannedPlot::edgeJitterSeed)
        ).apply(i, PlannedPlot::new));
    }

}
