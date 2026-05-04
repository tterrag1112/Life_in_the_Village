package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaRegion;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.SectorRole;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 19: immutable plan-as-contract handoff between the planner
 * and downstream consumers (spawner, decorator, expansion).
 *
 * <p>Built ONCE at the end of {@code VillagePlanner.plan()} from the
 * fully-composed {@link VillageLayout} state. {@link VillageLayout}
 * remains as planning-time scratch — recipes still build into it,
 * the matcher still consumes from it. Only the consumption boundary
 * moves: spawner and decorator read this record instead of the
 * mutable layout fields.
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

        List<SectorView> sectors,

        Map<AnchorKind, BlockPos> anchors,

        /** OK if planning succeeded; ABORT if marked unplannable. */
        Status status,
        int truncations,
        int cascadeRetries,

        /** Non-null when {@link #status} == ABORT. */
        @Nullable String unplannableReason) {

    /** Final cascade status visible on a plan. RETRY/FALLBACK never
     *  appear — they're resolved before {@code build()} returns. */
    public enum Status { OK, ABORT }

    public LayoutPlan {
        roads        = List.copyOf(roads);
        buildings    = List.copyOf(buildings);
        plotSlots    = List.copyOf(plotSlots);
        sectors      = List.copyOf(sectors);
        anchors      = Map.copyOf(anchors);
        plazaRegions = List.copyOf(plazaRegions);
    }

    public Optional<BlockPos> anchor(AnchorKind kind) {
        return Optional.ofNullable(anchors.get(kind));
    }

    public Optional<Plaza> primaryPlaza() {
        return Optional.ofNullable(plaza);
    }

    public boolean isOk() { return status == Status.OK; }

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
    }

    /**
     * One planned farm plot. Carries everything FarmPlotPlacer needs
     * to regenerate the plot's deterministic shape and place it on
     * the planned position.
     */
    public record PlannedPlot(
            BlockPos ownerFarmhousePos,
            FarmPlot.PlotSubtype subtype,
            BlockPos centre,
            int halfW,
            int halfL,
            int edgeJitterSeed) {}

    /**
     * Sector metadata for decoration / debug. Slot positions are NOT
     * inlined — committed positions are on {@link PlacedBuilding} via
     * {@link PlacedBuilding#sectorId()}, and uncommitted positions
     * aren't structurally interesting after the matcher has run.
     */
    public record SectorView(
            String id,
            SectorRole role,
            BuildingZone zone,
            int slotCount,
            int capacity,
            int parentEdgeId,
            int expectedMaxFootprint) {}
}
