package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Layer 3: Applies decorative and structural details after RoomStackBuilder
 * has placed all structural blocks.
 *
 * Consumes StackPlacementRecord instances from RoomStackBuilder and either:
 *   a) Stamps an NBT structure template from the style's pool, or
 *   b) Falls back to fully procedural placement driven by StyleDetailConfig
 *      and ArchitectPalette — no hardcoded blocks anywhere.
 *
 * All public methods are safe to call with empty records — they no-op cleanly.
 * All block choices go through the ArchitectPalette slot system.
 */
public class DetailApplicator {

    private static final Logger LOGGER = LogManager.getLogger(DetailApplicator.class);
    private static final int MAX_VARIANTS = 8;

    private final ServerLevelAccessor level;
    private final ServerLevel serverLevel;
    private final CastleStyle style;
    private final StructureTemplateManager templateManager;
    private final BlockPos castleOrigin;

    // Resolved once at construction — used by every method
    private final ArchitectPalette palette;
    private final StyleDetailConfig detail;

    public DetailApplicator(ServerLevelAccessor level, CastleStyle style,
                            BlockPos castleOrigin) {
        this.level        = level;
        this.style        = style;
        this.castleOrigin = castleOrigin;
        this.palette      = style.palette();
        this.detail       = style.styleDetail();

        if (level instanceof ServerLevel sl) {
            this.serverLevel    = sl;
            this.templateManager = sl.getStructureManager();
        } else {
            this.serverLevel    = null;
            this.templateManager = null;
            LOGGER.error("DetailApplicator created with a non-ServerLevel — " +
                    "structure templates will not be applied.");
        }
    }

    // =========================================================================
    // Public API — called by RoomStackBuilder
    // =========================================================================

    // -------------------------------------------------------------------------
    // Battlements
    // -------------------------------------------------------------------------

    /**
     * Stamp battlement templates along the top course of a wall stack,
     * or fall back to the StyleDetailConfig-driven procedural pattern.
     */
    public void applyBattlements(StackPlacementRecord rec, RandomSource rng) {
        List<BlockPos> top = rec.topCoursePositions();
        if (top.isEmpty()) return;

        Optional<StructureTemplate> tmpl =
                loadRandomFromPool(style.pools().battlementPool(), rng);

        if (tmpl.isPresent()) {
            stampBattlementTemplate(tmpl.get(), top, rec.outwardFacing(), rng);
        } else {
            applyFallbackBattlements(top, rec.outwardFacing(), rng);
        }
    }

    // -------------------------------------------------------------------------
    // Tower roofs
    // -------------------------------------------------------------------------

    /**
     * Place a tower roof template or procedural roof cap on a cylindrical
     * or rectangular stack's cap ring.
     */
    public void applyTowerRoof(StackPlacementRecord rec, RandomSource rng) {
        List<BlockPos> cap = rec.capRingPositions();
        if (cap.isEmpty()) return;

        Optional<StructureTemplate> tmpl =
                loadRandomFromPool(style.pools().towerRoofPool(), rng);

        if (tmpl.isPresent()) {
            stampCenteredTemplate(tmpl.get(), rec.stack().center(),
                    cap.get(0).getY(), rec.outwardFacing(), rng);
        } else {
            applyFallbackTowerCap(rec, rng);
        }
    }

    // -------------------------------------------------------------------------
    // Gatehouse cap
    // -------------------------------------------------------------------------

    /**
     * Place a gatehouse cap template over a gatehouse stack,
     * or no-op if no template exists (the arch is already carved by
     * RoomStackBuilder.placeGatePassage).
     */
    public void applyGatehouseCap(StackPlacementRecord rec, RandomSource rng) {
        Optional<StructureTemplate> tmpl =
                loadRandomFromPool(style.pools().gatehousePool(), rng);

        if (tmpl.isPresent()) {
            stampCenteredTemplate(tmpl.get(), rec.stack().center(),
                    rec.stack().topY(), rec.outwardFacing(), rng);
        }
        // No procedural fallback — the gate arch placed by RoomStackBuilder
        // is the fallback. Adding more here would over-decorate.
    }

    // -------------------------------------------------------------------------
    // Flags
    // -------------------------------------------------------------------------

    public void applyFlagsFromStack(StackPlacementRecord rec, RandomSource rng) {
        if (!style.features().addFlags()) return;
        if (rec.capRingPositions().isEmpty()) return;

        int flagY = rec.capRingPositions().get(0).getY() + 2;
        BlockPos pole = rec.stack().center().atY(flagY);

        placeBlock(pole,        Blocks.OAK_FENCE.defaultBlockState());
        placeBlock(pole.above(), Blocks.OAK_FENCE.defaultBlockState());
        placeBlock(pole.above(2),
                Blocks.WHITE_WALL_BANNER.defaultBlockState()
                        .setValue(WallBannerBlock.FACING, rec.outwardFacing()));
    }

    // -------------------------------------------------------------------------
    // Torches
    // -------------------------------------------------------------------------

    public void applyWallTorchesFromStack(StackPlacementRecord rec) {
        if (!style.features().addTorches()) return;

        List<BlockPos> walkway = rec.walkwayPositions();
        for (int i = 0; i < walkway.size(); i += 8) {
            BlockPos pos = walkway.get(i).above();
            if (level.getBlockState(pos).isAir()) {
                placeBlock(pos, Blocks.TORCH.defaultBlockState());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Window surrounds
    // -------------------------------------------------------------------------

    /**
     * Applies accent surrounds to arrow slit positions on a wall stack.
     * Called after battlements are placed. Stamps window pool templates if
     * available, otherwise places procedural accent frames.
     */
    public void applyWindowSurrounds(StackPlacementRecord rec, RandomSource rng) {
        Optional<StructureTemplate> windowTmpl =
                loadRandomFromPool(style.pools().windowPool(), rng);

        Direction outward = rec.outwardFacing();
        Direction left    = outward.getClockWise();
        Direction right   = outward.getCounterClockWise();

        List<BlockPos> top = rec.topCoursePositions();
        for (int i = 0; i < top.size(); i++) {
            if (i % 5 != 2) continue;
            BlockPos slitBase = top.get(i).below(2);

            if (windowTmpl.isPresent()) {
                Vec3i size = windowTmpl.get().getSize();
                BlockPos origin = new BlockPos(
                        slitBase.getX() - size.getX() / 2,
                        slitBase.getY(),
                        slitBase.getZ() - size.getZ() / 2);
                windowTmpl.get().placeInWorld(level, origin, origin,
                        new StructurePlaceSettings()
                                .setRotation(directionToRotation(outward))
                                .setIgnoreEntities(true),
                        rng, Block.UPDATE_ALL);
            } else {
                // Procedural: accent blocks on each side of the slit
                BlockState accent = palette.pick("trim", rng);
                placeBlock(slitBase.relative(left),        accent);
                placeBlock(slitBase.above().relative(left), accent);
                placeBlock(slitBase.relative(right),        accent);
                placeBlock(slitBase.above().relative(right), accent);
                // Stair lintel above
                placeBlock(slitBase.above(2),
                        palette.stair("stair", outward.getOpposite(), Half.BOTTOM, rng));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Subbuilding pool loading (public — called by SubbuildingPlacer)
    // -------------------------------------------------------------------------

    public Optional<StructureTemplate> loadPoolForSubbuilding(Identifier poolKey,
                                                              RandomSource rng) {
        return loadRandomFromPool(poolKey, rng);
    }

    // =========================================================================
    // Procedural fallback — battlements
    // =========================================================================

    private void applyFallbackBattlements(List<BlockPos> top,
                                          Direction outwardFacing,
                                          RandomSource rng) {
        switch (detail.battlementStyle()) {
            case CRENELLATED -> placeCrenellated(top, rng);
            case STEPPED     -> placeStepped(top, outwardFacing, rng);
            case POINTED     -> placePointed(top, rng);
            case WIDE        -> placeWide(top, rng);
            case PARAPET     -> placeParapet(top, rng);
            case RUINED      -> placeRuinedBattlements(top, rng);
        }
    }

    private void placeCrenellated(List<BlockPos> top, RandomSource rng) {
        int mw = detail.merlonWidth();
        int mh = detail.merlonHeight();
        for (int i = 0; i < top.size(); i++) {
            if (i % (mw + 1) < mw) {
                for (int h = 1; h <= mh; h++) {
                    placeBlock(top.get(i).above(h), palette.pick("fill", rng));
                }
            }
        }
    }

    private void placeStepped(List<BlockPos> top, Direction outward,
                              RandomSource rng) {
        int mw = detail.merlonWidth();
        int mh = detail.merlonHeight();
        for (int i = 0; i < top.size(); i++) {
            int phase = i % (mw * 2 + 2);
            BlockPos pos = top.get(i);
            if (phase == 0) {
                placeBlock(pos.above(),
                        palette.stair("stair", outward.getOpposite(),
                                Half.BOTTOM, rng));
            } else if (phase <= mw) {
                for (int h = 1; h <= mh; h++) {
                    placeBlock(pos.above(h), palette.pick("fill", rng));
                }
            } else if (phase == mw + 1) {
                placeBlock(pos.above(),
                        palette.stair("stair", outward, Half.BOTTOM, rng));
            }
        }
    }

    private void placePointed(List<BlockPos> top, RandomSource rng) {
        for (int i = 0; i < top.size(); i++) {
            if (i % 2 == 0) {
                placeBlock(top.get(i).above(),  palette.pick("fill", rng));
                if (detail.merlonHeight() >= 2) {
                    placeBlock(top.get(i).above(2),
                            palette.slab("slab", SlabType.BOTTOM));
                }
            }
        }
    }

    private void placeWide(List<BlockPos> top, RandomSource rng) {
        int mh = detail.merlonHeight();
        for (int i = 0; i < top.size(); i++) {
            if (i % 3 != 2) {
                for (int h = 1; h <= mh; h++) {
                    placeBlock(top.get(i).above(h), palette.pick("fill", rng));
                }
            }
        }
    }

    private void placeParapet(List<BlockPos> top, RandomSource rng) {
        for (BlockPos pos : top) {
            placeBlock(pos.above(),  palette.pick("fill", rng));
            placeBlock(pos.above(2), palette.slab("slab", SlabType.BOTTOM));
        }
    }

    private void placeRuinedBattlements(List<BlockPos> top, RandomSource rng) {
        float ruin = Math.max(style.ruinationLevel(), 0.3f);
        for (int i = 0; i < top.size(); i++) {
            if (i % 2 == 0 && rng.nextFloat() > ruin) {
                placeBlock(top.get(i).above(),
                        rng.nextFloat() < 0.4f
                                ? palette.pick("cracked", rng)
                                : palette.pick("fill", rng));
            }
        }
    }

    // =========================================================================
    // Procedural fallback — tower roofs
    // =========================================================================

    private void applyFallbackTowerCap(StackPlacementRecord rec, RandomSource rng) {
        switch (detail.towerRoofStyle()) {
            case FLAT      -> placeFlatCap(rec, rng);
            case PYRAMIDAL -> placePyramidalCap(rec, rng);
            case SPIRE     -> placeSpireCap(rec, rng);
            case CONICAL   -> placeConicalCap(rec, rng);
            case PAGODA    -> placePagodaCap(rec, rng);
            case DOME      -> placeDomeCap(rec, rng);
            case RUINED    -> placeFlatCap(rec, rng);
        }
    }

    private void placeFlatCap(StackPlacementRecord rec, RandomSource rng) {
        for (BlockPos pos : rec.capRingPositions()) {
            placeBlock(pos.above(), palette.slab("slab", SlabType.BOTTOM));
        }
    }

    private void placePyramidalCap(StackPlacementRecord rec, RandomSource rng) {
        if (rec.capRingPositions().isEmpty()) return;
        BlockPos base = rec.stack().center()
                .atY(rec.capRingPositions().get(0).getY() + 1);
        int r = rec.stack().wallThickness();

        for (int layer = 0; layer < r; layer++) {
            int lr = r - layer;
            int y  = base.getY() + layer;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                for (int offset = -lr; offset <= lr; offset++) {
                    BlockPos pos = new BlockPos(
                            base.getX() + dir.getStepX() * lr
                                    + dir.getClockWise().getStepX() * offset,
                            y,
                            base.getZ() + dir.getStepZ() * lr
                                    + dir.getClockWise().getStepZ() * offset);
                    placeBlock(pos, palette.stair("roof_trim",
                            dir.getOpposite(), Half.BOTTOM, rng));
                }
            }
        }
        placeBlock(base.above(r), palette.pick("trim", rng));
    }

    private void placeSpireCap(StackPlacementRecord rec, RandomSource rng) {
        if (rec.capRingPositions().isEmpty()) return;
        BlockPos base = rec.stack().center()
                .atY(rec.capRingPositions().get(0).getY() + 1);
        int spireHeight = rec.stack().wallThickness() * 3 + 4;

        placeBlock(base, palette.pick("fill", rng));
        for (int h = 1; h < spireHeight - 1; h++) {
            placeBlock(base.above(h), palette.pick("wall_top", rng));
        }
        placeBlock(base.above(spireHeight - 1), palette.pick("trim", rng));

        if (style.features().addFlags()) {
            placeBlock(base.above(spireHeight),
                    Blocks.WHITE_WALL_BANNER.defaultBlockState()
                            .setValue(WallBannerBlock.FACING, rec.outwardFacing()));
        }
    }

    private void placeConicalCap(StackPlacementRecord rec, RandomSource rng) {
        if (rec.capRingPositions().isEmpty()) return;
        BlockPos base = rec.stack().center()
                .atY(rec.capRingPositions().get(0).getY() + 1);
        int r = rec.stack().wallThickness();

        for (int layer = 0; layer < r; layer++) {
            double lr = r - layer;
            int y = base.getY() + layer;
            for (double angle = 0; angle < Math.PI * 2; angle += Math.PI / (4 * lr)) {
                int dx = (int) Math.round(Math.cos(angle) * lr);
                int dz = (int) Math.round(Math.sin(angle) * lr);
                Direction facing = dx == 0
                        ? (dz > 0 ? Direction.NORTH : Direction.SOUTH)
                        : (dx > 0 ? Direction.WEST  : Direction.EAST);
                placeBlock(new BlockPos(base.getX() + dx, y, base.getZ() + dz),
                        palette.stair("roof_trim", facing, Half.BOTTOM, rng));
            }
        }
        placeBlock(base.above(r), palette.pick("trim", rng));
    }

    private void placePagodaCap(StackPlacementRecord rec, RandomSource rng) {
        if (rec.capRingPositions().isEmpty()) return;
        BlockPos base = rec.stack().center()
                .atY(rec.capRingPositions().get(0).getY() + 1);
        int r     = rec.stack().wallThickness();
        int tiers = Math.min(3, r);

        for (int tier = 0; tier < tiers; tier++) {
            int tr = r - tier;
            int y  = base.getY() + tier * 3;

            // Overhang slab ring
            for (int dx = -tr - 1; dx <= tr + 1; dx++) {
                for (int dz = -tr - 1; dz <= tr + 1; dz++) {
                    if (Math.abs(dx) == tr + 1 || Math.abs(dz) == tr + 1) {
                        placeBlock(new BlockPos(base.getX() + dx, y, base.getZ() + dz),
                                palette.slab("slab", SlabType.BOTTOM));
                    }
                }
            }

            // Tier walls
            for (int dx = -tr; dx <= tr; dx++) {
                for (int dz = -tr; dz <= tr; dz++) {
                    if (Math.abs(dx) == tr || Math.abs(dz) == tr) {
                        placeBlock(new BlockPos(base.getX() + dx, y + 1, base.getZ() + dz),
                                palette.pick("roof", rng));
                        placeBlock(new BlockPos(base.getX() + dx, y + 2, base.getZ() + dz),
                                palette.pick("roof", rng));
                    }
                }
            }
        }
        placeBlock(base.above(tiers * 3), palette.pick("trim", rng));
    }

    private void placeDomeCap(StackPlacementRecord rec, RandomSource rng) {
        if (rec.capRingPositions().isEmpty()) return;
        BlockPos base = rec.stack().center()
                .atY(rec.capRingPositions().get(0).getY() + 1);
        int r = rec.stack().wallThickness();

        for (int layer = 0; layer <= r; layer++) {
            int lr = (int) Math.round(Math.sqrt(r * r - layer * layer));
            int y  = base.getY() + layer;
            for (int dx = -lr; dx <= lr; dx++) {
                for (int dz = -lr; dz <= lr; dz++) {
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist > lr) continue;
                    if (dist > lr - 1.5 || layer == r) {
                        placeBlock(new BlockPos(base.getX() + dx, y, base.getZ() + dz),
                                layer % 2 == 0
                                        ? palette.pick("roof", rng)
                                        : palette.pick("trim", rng));
                    }
                }
            }
        }
    }

    // =========================================================================
    // Template stamping helpers
    // =========================================================================

    private void stampBattlementTemplate(StructureTemplate tmpl,
                                         List<BlockPos> top,
                                         Direction outwardFacing,
                                         RandomSource rng) {
        Vec3i size = tmpl.getSize();
        Rotation rotation = directionToRotation(outwardFacing);

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .setIgnoreEntities(true);

        int templateWidth = (rotation == Rotation.NONE
                || rotation == Rotation.CLOCKWISE_180)
                ? size.getX() : size.getZ();
        if (templateWidth <= 0) templateWidth = 1;

        for (int i = 0; i + templateWidth <= top.size(); i += templateWidth) {
            BlockPos pathPos  = top.get(i);
            BlockPos stampPos = new BlockPos(
                    pathPos.getX() - size.getX() / 2,
                    pathPos.getY(),
                    pathPos.getZ() - size.getZ() / 2);
            tmpl.placeInWorld(level, stampPos, stampPos, settings, rng, Block.UPDATE_ALL);
        }
    }

    private void stampCenteredTemplate(StructureTemplate tmpl, BlockPos center,
                                       int y, Direction facing,
                                       RandomSource rng) {
        Vec3i size = tmpl.getSize();
        BlockPos origin = new BlockPos(
                center.getX() - size.getX() / 2,
                y,
                center.getZ() - size.getZ() / 2);

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(directionToRotation(facing))
                .setIgnoreEntities(true);

        tmpl.placeInWorld(level, origin, origin, settings, rng, Block.UPDATE_ALL);
    }

    // =========================================================================
    // Template loading
    // =========================================================================

    private Optional<StructureTemplate> loadRandomFromPool(Identifier poolKey,
                                                           RandomSource rng) {
        if (templateManager == null) {
            LOGGER.error("DetailApplicator: templateManager is null.");
            return Optional.empty();
        }

        List<StructureTemplate> candidates = new ArrayList<>();
        safeLoad(poolKey).ifPresent(candidates::add);
        for (int i = 0; i < MAX_VARIANTS; i++) {
            safeLoad(poolKey.withSuffix("_" + i)).ifPresent(candidates::add);
        }

        if (candidates.isEmpty()) {
            LOGGER.debug("DetailApplicator: no templates found for pool '{}'.", poolKey);
            return Optional.empty();
        }

        return Optional.of(candidates.get(rng.nextInt(candidates.size())));
    }

    private Optional<StructureTemplate> safeLoad(Identifier key) {
        if (templateManager == null || serverLevel == null) return Optional.empty();

        // Check manager cache first
        Optional<StructureTemplate> cached = templateManager.get(key);
        if (cached.isPresent() && !cached.get().getSize().equals(Vec3i.ZERO)) {
            return cached;
        }

        // Load from classloader — StructureTemplateManager does not auto-scan
        // mod resources in NeoForge 1.21+
        String nbtPath = "data/" + key.getNamespace()
                + "/structures/" + key.getPath() + ".nbt";
        try (java.io.InputStream stream =
                     getClass().getClassLoader().getResourceAsStream(nbtPath)) {
            if (stream == null) return Optional.empty();

            CompoundTag nbt = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
            StructureTemplate template = new StructureTemplate();
            template.load(
                    serverLevel.registryAccess()
                            .lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                    nbt);

            LOGGER.debug("DetailApplicator: loaded '{}' size={}", key, template.getSize());
            return Optional.of(template);

        } catch (Exception e) {
            LOGGER.debug("DetailApplicator: could not load '{}': {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    // =========================================================================
    // Rotation utility
    // =========================================================================

    private Rotation directionToRotation(Direction dir) {
        return switch (dir) {
            case NORTH -> Rotation.NONE;
            case EAST  -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST  -> Rotation.COUNTERCLOCKWISE_90;
            default    -> Rotation.NONE;
        };
    }

    // =========================================================================
    // Block placement
    // =========================================================================

    private void placeBlock(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }
}