package tterrag1112.life_in_the_village.Kingdom.Castle;


import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;
import java.util.Optional;

/**
 * Layer 3: Stamps structure-file templates onto positions reserved by Layer 2.
 *
 * This layer is responsible for all visual character differentiation between
 * castle styles. The code is identical for every style; only the template
 * ResourceLocations in CastleStyle differ.
 *
 * Structure templates are expected to be 3 blocks wide (X) and tiled repeatedly
 * along the wall/tower perimeter. Templates are rotated to align with wall direction.
 *
 * Handles:
 *   - Battlement tiling along wall top courses
 *   - Tower roof/cap stamping at tower cap rings
 *   - Gatehouse decorative template stamping
 *   - Window/arrow-slit overlay stamping (optional, for richer window dressings)
 *   - Flag pole placement on towers (if addFlags is true)
 *   - Torch placement on walls and towers (if addTorches is true)
 *
 * Falls back gracefully if a template pool cannot be loaded — the castle is
 * complete structurally even if detail templates are missing.
 */
public class DetailApplicator {

    private final ServerLevelAccessor level;
    private final CastleStyle style;
    private final StructureTemplateManager templateManager;

    public DetailApplicator(ServerLevelAccessor level, CastleStyle style) {
        this.level           = level;
        this.style           = style;
        // StructureTemplateManager is only available on ServerLevel
        this.templateManager = (level instanceof ServerLevel sl)
                ? sl.getStructureManager()
                : null;
    }

    // -------------------------------------------------------------------------
    // Wall battlements
    // -------------------------------------------------------------------------

    /**
     * Tile the battlement template along the top course of a wall segment.
     *
     * The template is expected to be exactly 3 blocks wide (representing one
     * merlon + one gap). It is tiled and rotated to follow the wall direction.
     *
     * Template coordinate convention:
     *   X axis = along-wall axis
     *   Y axis = upward
     *   Z axis = perpendicular to wall (outward = -Z in the template)
     */
    public void applyBattlements(WallSegmentPlacer.PlacementRecord wallRecord, RandomSource rng) {
        if (templateManager == null) return;

        List<BlockPos> top       = wallRecord.topCoursePositions();
        if (top.isEmpty()) return;

        Optional<StructureTemplate> tmplOpt = loadRandomFromPool(
                style.battlementPool(), rng);
        if (tmplOpt.isEmpty()) {
            // Graceful fallback: place simple alternating merlons
            applyFallbackBattlements(top, rng);
            return;
        }

        StructureTemplate tmpl = tmplOpt.get();
        Direction wallDir      = inferWallDirection(wallRecord.wallFrom(), wallRecord.wallTo());
        Rotation rotation = directionToRotation(wallDir);

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .setIgnoreEntities(true);

        // Tile the template along the top course
        // Template is 3 blocks wide — step by 3 along the top course list
        for (int i = 0; i < top.size(); i += 3) {
            // Stamp one template segment
            tmpl.placeInWorld(level, top.get(i), top.get(i),
                    settings, rng, Block.UPDATE_ALL);
        }
    }

    /**
     * Simple fallback when no battlement template is available.
     * Alternates a merlon block with an air gap.
     */
    private void applyFallbackBattlements(List<BlockPos> top, RandomSource rng) {
        for (int i = 0; i < top.size(); i++) {
            if (i % 2 == 0) {
                // Merlon: place one block on top
                BlockPos merlon = top.get(i).above();
                level.setBlock(merlon, style.primaryMaterial().pickMain(rng),
                        Block.UPDATE_CLIENTS);
            }
            // Odd positions stay open (crenels)
        }
    }

    // -------------------------------------------------------------------------
    // Tower roofs / caps
    // -------------------------------------------------------------------------

    /**
     * Stamp a roof/cap template on top of a tower.
     *
     * Tower roof templates are expected to cover the full tower footprint
     * and add 1–3 blocks of decorative cap (conical, flat, pyramidal, etc.).
     */
    public void applyTowerRoof(TowerPlacer.PlacementRecord towerRecord,
                               RandomSource rng) {
        if (templateManager == null) return;

        List<BlockPos> cap = towerRecord.capRingPositions();
        if (cap.isEmpty()) return;

        Optional<StructureTemplate> tmplOpt = loadRandomFromPool(
                style.towerRoofPool(), rng);
        if (tmplOpt.isEmpty()) {
            applyFallbackTowerCap(towerRecord, rng);
            return;
        }

        StructureTemplate tmpl = tmplOpt.get();
        // Tower templates are centered — use the tower node center as the origin
        BlockPos origin = towerRecord.node().center().atY(cap.get(0).getY());

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(directionToRotation(towerRecord.node().facing()))
                .setIgnoreEntities(true);

        tmpl.placeInWorld(level, origin, origin, settings, rng, Block.UPDATE_ALL);
    }

    private void applyFallbackTowerCap(TowerPlacer.PlacementRecord record,
                                       RandomSource rng) {
        // Simple flat platform of slabs
        for (BlockPos pos : record.capRingPositions()) {
            BlockPos above = pos.above();
            if (style.primaryMaterial().hasSlab()) {
                level.setBlock(above,
                        style.primaryMaterial().slabState(
                                net.minecraft.world.level.block.state.properties.SlabType.BOTTOM),
                        Block.UPDATE_CLIENTS);
            } else {
                level.setBlock(above,
                        style.primaryMaterial().pickMain(rng),
                        Block.UPDATE_CLIENTS);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Gatehouse cap
    // -------------------------------------------------------------------------

    public void applyGatehouseCap(GatehousePlacer.PlacementRecord gateRecord,
                                  RandomSource rng) {
        if (templateManager == null) return;

        Optional<StructureTemplate> tmplOpt = loadRandomFromPool(
                style.gatehousePool(), rng);
        if (tmplOpt.isEmpty()) return; // No fallback needed — gatehouse works without it

        StructureTemplate tmpl = tmplOpt.get();
        BlockPos origin = gateRecord.gateNode().center().atY(gateRecord.topY());

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(directionToRotation(gateRecord.gateNode().facing()))
                .setIgnoreEntities(true);

        tmpl.placeInWorld(level, origin, origin, settings, rng, Block.UPDATE_ALL);
    }

    // -------------------------------------------------------------------------
    // Flags
    // -------------------------------------------------------------------------

    /**
     * Place a banner on a fence-post flagpole at the top of each tower.
     * The banner color/pattern is picked from the style's accent material.
     */
    public void applyFlags(List<TowerPlacer.PlacementRecord> towerRecords,
                           RandomSource rng) {
        if (!style.addFlags()) return;

        for (TowerPlacer.PlacementRecord record : towerRecords) {
            if (record.capRingPositions().isEmpty()) continue;

            // Place the flagpole at the center top
            BlockPos centerTop = record.node().center().atY(
                    record.capRingPositions().get(0).getY() + 2);

            // Fence post as pole
            level.setBlock(centerTop, Blocks.OAK_FENCE.defaultBlockState(),
                    Block.UPDATE_CLIENTS);
            level.setBlock(centerTop.above(), Blocks.OAK_FENCE.defaultBlockState(),
                    Block.UPDATE_CLIENTS);

            // Banner facing outward (toward the node's facing direction)
            BlockPos bannerPos = centerTop.above().above();
            // Using white banner as default — a real implementation would vary by style
            level.setBlock(bannerPos,
                    Blocks.WHITE_WALL_BANNER.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.WallBannerBlock.FACING,
                                    record.node().facing()),
                    Block.UPDATE_CLIENTS);
        }
    }

    // -------------------------------------------------------------------------
    // Torches
    // -------------------------------------------------------------------------

    /**
     * Place wall torches at regular intervals on the inner wall face.
     * Positioned 1 block below the battlement line.
     */
    public void applyWallTorches(List<WallSegmentPlacer.PlacementRecord> wallRecords) {
        if (!style.addTorches()) return;

        for (WallSegmentPlacer.PlacementRecord record : wallRecords) {
            List<BlockPos> walkway = record.walkwayPositions();
            // Place a torch every 8 blocks along the walkway
            for (int i = 0; i < walkway.size(); i += 8) {
                BlockPos torchBase = walkway.get(i).above();
                level.setBlock(torchBase, Blocks.TORCH.defaultBlockState(),
                        Block.UPDATE_CLIENTS);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Template loading
    // -------------------------------------------------------------------------

    /**
     * Load a random template from the given pool ResourceLocation.
     *
     * Pool convention: the pool key resolves to a data/pool JSON that lists
     * individual template ResourceLocations. This method picks one at random.
     *
     * For simplicity, this implementation resolves the pool key as a direct
     * template path first, then tries to resolve as a pool via the
     * StructureTemplateManager. In production, hook into your own pool registry.
     */
    private Optional<StructureTemplate> loadRandomFromPool(Identifier poolKey,
                                                           RandomSource rng) {
        if (templateManager == null) return Optional.empty();

        // First: try direct template load (pool key is actually a template path)
        Optional<StructureTemplate> direct = templateManager.get(poolKey);
        if (direct.isPresent()) return direct;

        // Second: try appending common suffixes for pool entries
        // A real implementation would query a pool registry here.
        // This fallback tries _0, _1, _2 and picks one that exists.
        for (int variant = 0; variant < 4; variant++) {
            Identifier variantKey = poolKey.withSuffix("_" + variant);
            Optional<StructureTemplate> variantTmpl = templateManager.get(variantKey);
            if (variantTmpl.isPresent() && rng.nextFloat() < 0.6f) {
                return variantTmpl;
            }
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Direction utilities
    // -------------------------------------------------------------------------

    private Direction inferWallDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private net.minecraft.world.level.block.Rotation directionToRotation(Direction dir) {
        return switch (dir) {
            case NORTH -> net.minecraft.world.level.block.Rotation.NONE;
            case EAST  -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
            case SOUTH -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
            case WEST  -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
            default    -> net.minecraft.world.level.block.Rotation.NONE;
        };
    }
}
