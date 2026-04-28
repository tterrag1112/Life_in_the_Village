package tterrag1112.life_in_the_village.Village.Decoration.TownSquare;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.PathMaterial;
import tterrag1112.life_in_the_village.Village.Decoration.TerrainSmoother;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Village;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Doc 04 §"Composition model" — drop-in replacement for the legacy
 * {@code TownSquarePlacer.place(...)} that:
 *
 * <ol>
 *   <li>Resolves the tier-scaled plaza half-extent via
 *       {@link TownSquareTier}.</li>
 *   <li>Paves the plaza programmatically using {@link
 *       VillageBiomeStyle} blocks (per-culture / per-biome variation
 *       handled by the style, not by NBTs).</li>
 *   <li>Registers the plaza as a synthetic
 *       {@link BuildingType#TOWN_SQUARE} building so road / path
 *       systems treat it as a routable target.</li>
 *   <li>Stores the chosen plaza radius on the {@link Village} for
 *       {@link tterrag1112.life_in_the_village.Village.Decoration
 *       .Framework.DecorationSlotEmitter} to pick up at decoration
 *       time.</li>
 *   <li>Registers per-feature {@link GatheringPoint}s on the
 *       {@code Village} for the NPC social layer (Phase 2) to
 *       consume.</li>
 * </ol>
 *
 * <p>The composer does NOT stamp central feature / bench / gazebo
 * etc. NBTs directly. That happens through the standard
 * {@code DecorationProfile} → {@code DecorationMatcher} pipeline:
 * {@link tterrag1112.life_in_the_village.Village.Decoration.Framework
 * .DecorationSlotEmitter} emits PARK_FEATURE + sub-role slots over
 * the plaza, profile registrations from
 * {@link DefaultTownSquareKits} match each slot to a piece, and
 * {@code DecorationPass} stamps the NBT. This keeps plaza decoration
 * uniform with the rest of the village's decoration pipeline and
 * lets the per-tier kit selection logic ship without re-authoring
 * the placement code.</p>
 *
 * <p>Doc 04 §"Edge cases" — silent drop on terrain failure: if the
 * plaza is rejected by the recipe (steep slope, water), it never
 * reaches this composer. Inside the composer, paving is best-effort
 * — water columns get filled with dirt, vegetation gets cleared.</p>
 */
public final class TownSquareComposer {

    private static final Logger LOGGER = LoggerFactory.getLogger(TownSquareComposer.class);

    private TownSquareComposer() {}

    /**
     * Drop-in replacement for {@code TownSquarePlacer.place(...)}.
     * Same signature, same return type (the set of paved positions
     * for road-network protection).
     */
    public static Set<BlockPos> compose(ServerLevel level,
                                        BlockPos center,
                                        VillageBiomeStyle style,
                                        VillageSizeTier tier,
                                        Village village,
                                        VillageSavedData data) {
        int radius = TownSquareTier.plazaRadiusFor(tier);
        int targetY = medianGroundY(level, center, radius);
        BlockPos flatCenter = new BlockPos(center.getX(), targetY, center.getZ());

        Set<BlockPos> pavedBlocks = pavePlaza(level, flatCenter, radius, style, village);

        registerAsBuilding(level, flatCenter, radius, village, data);

        // Doc 04 §"Tier scaling" — store the tier-scaled half-extent
        // on the Village so the slot emitter can size sub-slots
        // without round-tripping through VillageLayout (which is
        // planning-only and may be discarded by the time the
        // decoration pass runs).
        village.setTownSquareRadius(radius);

        // Doc 04 §"NPC gathering points" — register one named position
        // per sub-feature.
        registerGatheringPoints(village, flatCenter, radius, tier);

        // Resolve the kit so we can log what's missing if the user
        // hasn't authored NBTs yet. The actual NBT stamping happens
        // in DecorationPass via the registered profiles.
        Optional<TownSquareKit> kit = TownSquareKitRegistry.INSTANCE
                .resolve(DefaultTownSquareKits_culture(village), tier);
        if (kit.isEmpty()) {
            LOGGER.warn("TownSquareComposer: no kit registered for ({}, {}). "
                    + "Plaza will be paved but feature NBTs will not be stamped.",
                    DefaultTownSquareKits_culture(village), tier);
        }

        LOGGER.info("TownSquareComposer: village {} ({}) — paved {}-radius plaza "
                + "at {} (Y={}), {} gathering points",
                village.getName(), tier.displayName, radius,
                flatCenter.toShortString(), targetY,
                village.getGatheringPoints().size());

        return pavedBlocks;
    }

    // ── Paving ──────────────────────────────────────────────────────────

    /**
     * Plaza paving — palette-continuous with surrounding roads.
     *
     * <p>Prompt 16 §"Task A" surgical fix: the legacy paving used
     * {@code style.stone} (outer border), {@code style.stoneSlab}
     * (inner slab ring), and {@code style.pathState} (interior).
     * Roads use {@link PathMaterial}, which is a different palette
     * source — that's why the plaza read as a "stamped square on top
     * of unaware roads." This rewrite resolves the road's
     * {@code PathMaterial} for this village (same source the road
     * realiser uses) and samples interior blocks from
     * {@link PathMaterial#sampleCore} and the outer ring from
     * {@link PathMaterial#sampleEdge}, so the plaza palette matches
     * the surrounding road palette exactly.</p>
     *
     * <p>The legacy "inner slab ring" is dropped intentionally —
     * roads don't have a slab ring, so keeping it would re-introduce
     * the visual "this is a stamped square, not a paved area" cue
     * the prompt 15 diagnostic identified. The plaza now reads as a
     * wide patch of the same paving the village's roads use; the
     * polygon-shape work in prompt 17 will give it shape variation
     * per layout.</p>
     */
    private static Set<BlockPos> pavePlaza(ServerLevel level,
                                           BlockPos flatCenter,
                                           int radius,
                                           VillageBiomeStyle style,
                                           Village village) {
        int targetY = flatCenter.getY();
        Set<BlockPos> paved = new HashSet<>();

        // Resolve the same PathMaterial the road realiser would use
        // for this village. Same source (biome + village's path
        // tier) — same palette — no visual seam.
        PathMaterial material = PathMaterial.forBiomeAndTier(
                style, village.getPathTier());
        RandomSource random = level.getRandom();

        TerrainSmoother.levelPad(level,
                flatCenter.getX() - radius, flatCenter.getZ() - radius,
                flatCenter.getX() + radius, flatCenter.getZ() + radius,
                targetY - 1,
                Collections.emptySet());

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos pavePos = new BlockPos(
                        flatCenter.getX() + dx,
                        targetY,
                        flatCenter.getZ() + dz);

                int colSurfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        pavePos.getX(), pavePos.getZ());

                if (colSurfY < targetY) {
                    for (int y = colSurfY + 1; y < targetY; y++) {
                        BlockPos fill = new BlockPos(pavePos.getX(), y, pavePos.getZ());
                        if (level.getBlockState(fill).isAir()
                                || level.getBlockState(fill).is(BlockTags.REPLACEABLE)) {
                            level.setBlock(fill, Blocks.DIRT.defaultBlockState(), 3);
                        }
                    }
                } else if (colSurfY > targetY) {
                    for (int y = colSurfY; y > targetY; y--) {
                        BlockPos carve = new BlockPos(pavePos.getX(), y, pavePos.getZ());
                        BlockState cs = level.getBlockState(carve);
                        if (cs.is(Blocks.GRASS_BLOCK) || cs.is(Blocks.DIRT)
                                || cs.is(Blocks.COARSE_DIRT)
                                || cs.is(BlockTags.REPLACEABLE)) {
                            level.setBlock(carve, Blocks.AIR.defaultBlockState(), 3);
                        } else break;
                    }
                }

                // Outer ring uses the road palette's edge mix (same
                // blocks the road realiser stamps at perpendicular
                // distance == halfWidth); interior uses the core mix.
                // Note that no edge-noise placement is applied — the
                // plaza is a deterministic flat pad, not an organic
                // strip. Polygon-shape work in prompt 17 will revisit
                // edge softening.
                boolean isBorder = Math.abs(dx) == radius
                        || Math.abs(dz) == radius;
                BlockState pave = isBorder
                        ? material.sampleEdge(random)
                        : material.sampleCore(random);
                level.setBlock(pavePos, pave, 3);

                BlockPos above = pavePos.above();
                BlockState aboveState = level.getBlockState(above);
                if (!aboveState.isAir()
                        && (aboveState.is(BlockTags.REPLACEABLE)
                        || aboveState.is(BlockTags.FLOWERS)
                        || aboveState.is(BlockTags.SMALL_FLOWERS)
                        || aboveState.is(BlockTags.LEAVES))) {
                    level.setBlock(above, Blocks.AIR.defaultBlockState(), 3);
                }

                paved.add(pavePos);
            }
        }
        return paved;
    }

    // ── Building registration ───────────────────────────────────────────

    private static void registerAsBuilding(ServerLevel level,
                                           BlockPos center,
                                           int radius,
                                           Village village,
                                           VillageSavedData data) {
        int side = radius * 2 + 1;
        BlockPos origin = new BlockPos(
                center.getX() - radius,
                center.getY(),
                center.getZ() - radius);

        Building.BuildingShape shape = new Building.BuildingShape(
                origin, side, 64, side);

        Identifier structId = Identifier.fromNamespaceAndPath(
                Life_in_the_village.MODID, "town_square");

        Building square = new Building(
                "town_square", BuildingType.TOWN_SQUARE, shape, structId,
                Rotation.NONE, 0);

        data.addBuilding(square);
        village.addBuilding(square);
        village.checkAndFireTierChangeHook(level);
        data.setDirty();
    }

    // ── Gathering points (Phase 1 doc 04) ───────────────────────────────

    /**
     * Doc 04 §"NPC gathering points" — one point per placed sub-
     * feature, with capacity per the doc's "Open decisions" defaults.
     * Position formulas match the slot positions emitted by
     * {@code DecorationSlotEmitter} so NPCs path to the same spot
     * the matcher places the piece at.
     */
    private static void registerGatheringPoints(Village village,
                                                BlockPos flatCenter,
                                                int radius,
                                                VillageSizeTier tier) {
        // FOUNTAIN at plaza centre (capacity 3). Always present.
        addPoint(village, flatCenter, GatheringPointKind.FOUNTAIN, 3,
                "fountain", flatCenter);

        // BENCHES at perimeter (capacity 1 each). Tier-gated.
        BlockPos[] benchPositions = benchPositions(flatCenter, radius,
                TownSquareTier.benchCountFor(tier));
        for (int i = 0; i < benchPositions.length; i++) {
            addPoint(village, flatCenter, GatheringPointKind.BENCH, 1,
                    "bench-" + i, benchPositions[i]);
        }

        // NOTICE_BOARD on the road-facing edge (capacity 1).
        BlockPos noticePos = noticeBoardPosition(flatCenter, radius);
        addPoint(village, flatCenter, GatheringPointKind.NOTICE_BOARD, 1,
                "notice-board", noticePos);

        // GAZEBO off-axis (TOWN+ only, capacity 5).
        if (TownSquareTier.hasGazebo(tier)) {
            BlockPos gazeboPos = gazeboPosition(flatCenter, radius);
            addPoint(village, flatCenter, GatheringPointKind.GAZEBO, 5,
                    "gazebo", gazeboPos);
        }

        // VENDOR_ZONE diagonal (VILLAGE+ only, capacity 4).
        // Doc 04 §"Vendor zone" — reserved space; the gathering point
        // marks where merchants gather during MARKET_DAY.
        if (TownSquareTier.hasVendorZone(tier)) {
            BlockPos vendorPos = vendorZonePosition(flatCenter, radius);
            addPoint(village, flatCenter, GatheringPointKind.VENDOR_ZONE, 4,
                    "vendor-zone", vendorPos);
        }
    }

    private static void addPoint(Village village, BlockPos plazaCentre,
                                 GatheringPointKind kind, int capacity,
                                 String tag, BlockPos pos) {
        UUID id = deterministicId(village.getId(), plazaCentre, tag);
        village.addGatheringPoint(new GatheringPoint(id, pos, kind, capacity));
    }

    // ── Sub-feature positions (mirror DecorationSlotEmitter) ───────────

    /**
     * Returns up to {@code count} bench positions equally spaced
     * around the plaza interior, just inside the inner border.
     * Always cardinals first (south, north, east, west) to match the
     * legacy layout's bench offsets.
     */
    public static BlockPos[] benchPositions(BlockPos centre, int radius, int count) {
        if (count <= 0 || radius < 4) return new BlockPos[0];
        int benchDist = Math.max(1, radius - 2);
        int[][] cardinals = {{0, benchDist}, {0, -benchDist},
                             {benchDist, 0}, {-benchDist, 0}};
        BlockPos[] out = new BlockPos[Math.min(count, cardinals.length)];
        for (int i = 0; i < out.length; i++) {
            out[i] = new BlockPos(
                    centre.getX() + cardinals[i][0],
                    centre.getY(),
                    centre.getZ() + cardinals[i][1]);
        }
        return out;
    }

    /** Notice board sits on the south edge (the "road-facing" face
     *  by the building-placer convention: {@code Rotation.NONE} →
     *  SOUTH). One block in from the inner border. */
    public static BlockPos noticeBoardPosition(BlockPos centre, int radius) {
        return new BlockPos(centre.getX(),
                centre.getY(),
                centre.getZ() + Math.max(1, radius - 2));
    }

    /** Gazebo offset to the SW so it doesn't block the south-facing
     *  view down the main road. */
    public static BlockPos gazeboPosition(BlockPos centre, int radius) {
        int off = Math.max(1, radius - 3);
        return new BlockPos(centre.getX() - off,
                centre.getY(),
                centre.getZ() - off);
    }

    /** Doc 04 §"Vendor zone" + Phase 1 review note — placed on the
     *  NE diagonal so it doesn't collide with the legacy
     *  {@code TownSquarePlacer.decorateForEvent} hardcoded MARKET_DAY
     *  cardinals (±4 along axes). */
    public static BlockPos vendorZonePosition(BlockPos centre, int radius) {
        int off = Math.max(1, radius - 2);
        return new BlockPos(centre.getX() + off,
                centre.getY(),
                centre.getZ() - off);
    }

    /** Lamp posts at the four plaza corners. */
    public static BlockPos[] lampCornerPositions(BlockPos centre, int radius) {
        int corner = Math.max(1, radius - 1);
        return new BlockPos[]{
                new BlockPos(centre.getX() + corner, centre.getY(), centre.getZ() + corner),
                new BlockPos(centre.getX() - corner, centre.getY(), centre.getZ() + corner),
                new BlockPos(centre.getX() + corner, centre.getY(), centre.getZ() - corner),
                new BlockPos(centre.getX() - corner, centre.getY(), centre.getZ() - corner)
        };
    }

    /** Flowerbed positions interleaved between benches on the
     *  diagonals. Empty when the tier has no flowerbeds. */
    public static BlockPos[] flowerbedPositions(BlockPos centre, int radius,
                                                VillageSizeTier tier) {
        if (!TownSquareTier.hasFlowerbeds(tier)) return new BlockPos[0];
        int off = Math.max(1, radius - 2);
        return new BlockPos[]{
                new BlockPos(centre.getX() + off, centre.getY(), centre.getZ() + off),
                new BlockPos(centre.getX() - off, centre.getY(), centre.getZ() + off),
                // NE diagonal slot is taken by VENDOR_ZONE for VILLAGE+,
                // so SW gets a flowerbed. Symmetric pair below.
                new BlockPos(centre.getX() - off, centre.getY(), centre.getZ() - off)
        };
    }

    /** Monument accent — slightly offset from the plaza centre so it
     *  reads as an accent next to the central fountain. CITY only. */
    public static BlockPos monumentPosition(BlockPos centre, int radius) {
        // Two blocks north of centre so the monument flanks the
        // fountain when the player approaches from the south road.
        return new BlockPos(centre.getX(),
                centre.getY(),
                centre.getZ() - 2);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static int medianGroundY(ServerLevel level,
                                     BlockPos center, int radius) {
        // Sample a coarse grid; same algorithm as the legacy placer
        // but parameterised on radius so larger plazas sample more
        // points.
        List<Integer> ys = new ArrayList<>();
        int step = Math.max(1, radius / 2);
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                ys.add(level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        center.getX() + dx, center.getZ() + dz));
            }
        }
        Collections.sort(ys);
        return ys.get(ys.size() / 2);
    }

    private static UUID deterministicId(UUID villageId, BlockPos plazaCentre,
                                        String tag) {
        String seed = villageId.toString() + "/plaza/"
                + plazaCentre.getX() + "," + plazaCentre.getY() + ","
                + plazaCentre.getZ() + "/" + tag;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    /** Until {@code VillageSavedData} exposes a per-village culture
     *  accessor, all villages report the default culture. Phase 1
     *  matches the convention used by {@code DecorationPass}. */
    private static String DefaultTownSquareKits_culture(Village v) {
        return tterrag1112.life_in_the_village.Village.Decoration
                .Framework.DecorationProfileRegistry.DEFAULT_CULTURE;
    }
}
