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
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.net.IDN;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class DetailApplicator {

    private static final Logger LOGGER = LogManager.getLogger(DetailApplicator.class);

    // Maximum number of _N suffixed variants to scan per pool key
    private static final int MAX_VARIANTS = 8;

    private final ServerLevelAccessor level;
    private final ServerLevel serverLevel;  // add this
    private final CastleStyle style;
    private final StructureTemplateManager templateManager;
    private final BlockPos castleOrigin;


    public DetailApplicator(ServerLevelAccessor level, CastleStyle style, BlockPos origin) {
        this.level = level;
        this.style = style;
        this.castleOrigin = origin;


        if (level instanceof ServerLevel sl) {
            this.serverLevel = sl;
            this.templateManager = sl.getStructureManager();
        } else {
            this.serverLevel = null;
            this.templateManager = null;
            LOGGER.error("DetailApplicator created with a non-ServerLevel — " +
                    "structure templates will not be applied. " +
                    "Ensure CastleGenerator is called from server-side code only.");
        }
    }

    // -------------------------------------------------------------------------
    // Wall battlements
    // -------------------------------------------------------------------------

    public void applyBattlements(WallSegmentPlacer.PlacementRecord wallRecord, RandomSource rng) {
        List<BlockPos> top = wallRecord.topCoursePositions();
        if (top.isEmpty()) {
            LOGGER.debug("applyBattlements: top course is empty, skipping");
            return;
        }

        LOGGER.info("DetailApplicator: manager class = {}", templateManager.getClass().getName());

        Optional<StructureTemplate> tmplOpt = loadRandomFromPool(style.pools().battlementPool(), rng);
        if (tmplOpt.isEmpty()) {
            LOGGER.debug("applyBattlements: no template found for pool {}, using fallback",
                    style.pools().battlementPool());
            applyFallbackBattlements(top, rng);
            return;
        }


        StructureTemplate tmpl = tmplOpt.get();
        Direction wallDir = inferWallOutwardFacing(wallRecord.wallFrom(), wallRecord.wallTo(), castleOrigin);
        Rotation rotation      = directionToRotation(wallDir);

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .setIgnoreEntities(true);

        // Template is 3 blocks wide — tile it along the top course in steps of 3
        int templateWidth = 3;
        for (int i = 0; i + templateWidth <= top.size(); i += templateWidth) {
            BlockPos stampPos = top.get(i);
            tmpl.placeInWorld(level, stampPos, stampPos, settings, rng, Block.UPDATE_ALL);
        }
    }

    private void applyFallbackBattlements(List<BlockPos> top, RandomSource rng) {
        for (int i = 0; i < top.size(); i++) {
            if (i % 2 == 0) {
                level.setBlock(top.get(i).above(),
                        style.primaryMaterial().pickMain(rng),
                        Block.UPDATE_CLIENTS);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Tower roofs
    // -------------------------------------------------------------------------

    public void applyTowerRoof(TowerPlacer.PlacementRecord towerRecord, RandomSource rng) {
        List<BlockPos> cap = towerRecord.capRingPositions();
        if (cap.isEmpty()) {
            LOGGER.debug("applyTowerRoof: cap ring is empty for tower at {}",
                    towerRecord.node().center());
            return;
        }

        Optional<StructureTemplate> tmplOpt = loadRandomFromPool(style.pools().towerRoofPool(), rng);
        if (tmplOpt.isEmpty()) {
            LOGGER.debug("applyTowerRoof: no template found for pool {}, using fallback",
                    style.pools().towerRoofPool());
            applyFallbackTowerCap(towerRecord, rng);
            return;
        }

        StructureTemplate tmpl = tmplOpt.get();
        // Center the template on the tower node at cap height
        BlockPos origin = towerRecord.node().center().atY(cap.get(0).getY());

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(directionToRotation(towerRecord.node().facing()))
                .setIgnoreEntities(true);

        tmpl.placeInWorld(level, origin, origin, settings, rng, Block.UPDATE_ALL);
    }

    private void applyFallbackTowerCap(TowerPlacer.PlacementRecord record, RandomSource rng) {
        for (BlockPos pos : record.capRingPositions()) {
            BlockPos above = pos.above();
            if (style.primaryMaterial().hasSlab()) {
                level.setBlock(above,
                        style.primaryMaterial().slabState(SlabType.BOTTOM),
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

    public void applyGatehouseCap(GatehousePlacer.PlacementRecord gateRecord, RandomSource rng) {
        Optional<StructureTemplate> tmplOpt = loadRandomFromPool(style.pools().gatehousePool(), rng);
        if (tmplOpt.isEmpty()) {
            LOGGER.debug("applyGatehouseCap: no template found for pool {}",
                    style.pools().gatehousePool());
            return;
        }

        StructureTemplate tmpl  = tmplOpt.get();
        BlockPos          origin = gateRecord.gateNode().center().atY(gateRecord.topY());

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(directionToRotation(gateRecord.gateNode().facing()))
                .setIgnoreEntities(true);

        tmpl.placeInWorld(level, origin, origin, settings, rng, Block.UPDATE_ALL);
    }

    // -------------------------------------------------------------------------
    // Flags
    // -------------------------------------------------------------------------

    public void applyFlags(List<TowerPlacer.PlacementRecord> towerRecords, RandomSource rng) {
        if (!style.features().addFlags()) return;

        for (TowerPlacer.PlacementRecord record : towerRecords) {
            if (record.capRingPositions().isEmpty()) continue;

            BlockPos centerTop = record.node().center()
                    .atY(record.capRingPositions().get(0).getY() + 2);

            level.setBlock(centerTop,
                    Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(centerTop.above(),
                    Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);

            level.setBlock(centerTop.above().above(),
                    Blocks.WHITE_WALL_BANNER.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.WallBannerBlock.FACING,
                                    record.node().facing()),
                    Block.UPDATE_CLIENTS);
        }
    }

    // -------------------------------------------------------------------------
    // Torches
    // -------------------------------------------------------------------------

    public void applyWallTorches(List<WallSegmentPlacer.PlacementRecord> wallRecords) {
        if (!style.features().addTorches()) return;

        for (WallSegmentPlacer.PlacementRecord record : wallRecords) {
            List<BlockPos> walkway = record.walkwayPositions();
            for (int i = 0; i < walkway.size(); i += 8) {
                level.setBlock(walkway.get(i).above(),
                        Blocks.TORCH.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Template loading — fixed
    // -------------------------------------------------------------------------

    /**
     * Load a random template from a pool.
     *
     * The pool ResourceLocation (e.g. "yourmod:castle/battlements/norman") is
     * treated as a BASE path. This method scans for variants by appending
     * "_0", "_1" ... "_N" and collecting every one that actually exists, then
     * picks uniformly at random from those found.
     *
     * It also tries the bare key with no suffix first, in case you have a single
     * file without a variant number.
     *
     * The StructureTemplateManager resolves keys against:
     *   data/<namespace>/structures/<path>.nbt
     *
     * So "yourmod:castle/battlements/norman_0" resolves to:
     *   data/yourmod/structures/castle/battlements/norman.nbt
     *
     * Note: templateManager.get() returns an Optional — it will NOT throw or
     * log an error if the file is missing, it simply returns empty. This means
     * a wrong path fails completely silently. The LOGGER.warn below is essential.
     */
    private Optional<StructureTemplate> loadRandomFromPool(Identifier poolKey,
                                                           RandomSource rng) {
        if (templateManager == null) {
            LOGGER.error("DetailApplicator: templateManager is null.");
            return Optional.empty();
        }

        try {
            String nbtPath = "data/" + poolKey.getNamespace() + "/structures/" + poolKey.getPath() + ".nbt";
            java.net.URL url = getClass().getClassLoader().getResource(nbtPath);
            LOGGER.info("DetailApplicator: classloader URL for '{}' = {}", nbtPath, url);

            java.util.Enumeration<java.net.URL> allUrls =
                    getClass().getClassLoader().getResources(nbtPath);
            int count = 0;
            while (allUrls.hasMoreElements()) {
                LOGGER.info("DetailApplicator: also found at = {}", allUrls.nextElement());
                count++;
            }
            if (count == 0) {
                LOGGER.warn("DetailApplicator: classloader finds NOTHING for '{}'", nbtPath);
            }
        } catch (Exception e) {
            LOGGER.warn("DetailApplicator: classloader check failed: {}", e.getMessage());
        }

        List<StructureTemplate> candidates = new ArrayList<>();

        // getOrCreate reads from disk if not cached — unlike get() which only checks cache
        safeLoad(poolKey).ifPresent(candidates::add);

        for (int i = 0; i < MAX_VARIANTS; i++) {
            safeLoad(poolKey.withSuffix("_" + i)).ifPresent(candidates::add);
        }

        if (candidates.isEmpty()) {
            LOGGER.warn("DetailApplicator: no templates found for pool '{}'.\n" +
                            "  Looked for:\n" +
                            "    data/{}/structures/{}.nbt\n" +
                            "    data/{}/structures/{}_{0..{}}.nbt",
                    poolKey,
                    poolKey.getNamespace(), poolKey.getPath(),
                    poolKey.getNamespace(), poolKey.getPath(), MAX_VARIANTS - 1);
            return Optional.empty();
        }

        return Optional.of(candidates.get(rng.nextInt(candidates.size())));
    }

    private Optional<StructureTemplate> safeLoad(Identifier key) {
        if (templateManager == null || serverLevel == null) return Optional.empty();

        // Check manager cache first
        Optional<StructureTemplate> cached = templateManager.get(key);
        if (cached.isPresent() && !cached.get().getSize().equals(Vec3i.ZERO)) {
            LOGGER.debug("DetailApplicator: safeLoad CACHED '{}' size={}", key, cached.get().getSize());
            return cached;
        }

        // Load directly from classloader since StructureTemplateManager
        // does not scan mod resources automatically
        String nbtPath = "data/" + key.getNamespace() + "/structures/" + key.getPath() + ".nbt";
        try (java.io.InputStream stream = getClass().getClassLoader().getResourceAsStream(nbtPath)) {
            if (stream == null) {
                LOGGER.debug("DetailApplicator: safeLoad EMPTY (no file) '{}'", key);
                return Optional.empty();
            }

            CompoundTag nbt = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());

            StructureTemplate template = new StructureTemplate();
            template.load(
                    serverLevel.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                    nbt
            );

            LOGGER.debug("DetailApplicator: safeLoad OK '{}' size={}", key, template.getSize());
            return Optional.of(template);

        } catch (Exception e) {
            LOGGER.warn("DetailApplicator: safeLoad FAILED '{}' — {}: {}",
                    key, e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
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

    private Rotation directionToRotation(Direction dir) {
        return switch (dir) {
            case NORTH -> Rotation.NONE;
            case EAST  -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST  -> Rotation.COUNTERCLOCKWISE_90;
            default    -> Rotation.NONE;
        };
    }
    private Direction inferWallOutwardFacing(BlockPos from, BlockPos to, BlockPos castleOrigin) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();

        // Midpoint of this wall segment
        BlockPos mid = new BlockPos(
                (from.getX() + to.getX()) / 2,
                from.getY(),
                (from.getZ() + to.getZ()) / 2
        );

        // Outward = away from castle center
        int odx = mid.getX() - castleOrigin.getX();
        int odz = mid.getZ() - castleOrigin.getZ();

        if (Math.abs(odx) >= Math.abs(odz)) {
            return odx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return odz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}