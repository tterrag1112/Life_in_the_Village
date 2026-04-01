package tterrag1112.life_in_the_village.Kingdom.Castle.Placers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.loot.LootTable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tterrag1112.life_in_the_village.Kingdom.Castle.*;

import java.util.*;

/**
 * Places functional subbuildings within a generated castle.
 *
 * Placement strategy per zone:
 *   DONJON_GROUND / DONJON_UPPER — inside the donjon footprint
 *   INNER_WARD                   — ring inside the inner wall
 *   OUTER_WARD                   — ring inside the outer wall
 *   WALL_INTERIOR                — near tower positions
 *   TOWER_INTERIOR               — inside a tower stack
 *   UNDERGROUND                  — below ground at castle center
 *   COURTYARD                    — open ground areas
 *
 * Uses ArchitectPalette for all block choices.
 * No dependency on deleted files.
 */
public class SubbuildingPlacer {

    private static final Logger LOGGER = LogManager.getLogger(SubbuildingPlacer.class);
    private static final int WARD_SPACING = 10;

    private final ServerLevelAccessor level;
    private final CastleStyle style;
    private final TerrainSampler terrain;
    private final DetailApplicator detail;
    private final ArchitectPalette palette;

    public SubbuildingPlacer(ServerLevelAccessor level, CastleStyle style,
                             TerrainSampler terrain, DetailApplicator detail) {
        this.level   = level;
        this.style   = style;
        this.terrain = terrain;
        this.detail  = detail;
        this.palette = style.palette();
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public Map<SubbuildingType, List<BlockPos>> placeAll(CastleLayout layout,
                                                         RandomSource rng) {
        Map<SubbuildingType, List<BlockPos>> placed =
                new EnumMap<>(SubbuildingType.class);

        ZoneAnchors anchors = resolveAnchors(layout);
        placeCourtyardGround(layout, rng);

        for (SubbuildingSpec spec : style.function().subbuildingSpecs()) {
            int count = spec.minCount() == spec.maxCount()
                    ? spec.minCount()
                    : spec.minCount()
                    + rng.nextInt(spec.maxCount() - spec.minCount() + 1);

            List<BlockPos> positions = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                placeOne(spec, anchors, i, rng).ifPresent(positions::add);
            }

            if (!positions.isEmpty()) {
                placed.put(spec.type(), positions);
            } else if (spec.minCount() > 0) {
                LOGGER.warn("SubbuildingPlacer: required subbuilding {} " +
                        "could not be placed.", spec.type());
            }
        }

        return placed;
    }

    // =========================================================================
    // Single placement
    // =========================================================================

    private Optional<BlockPos> placeOne(SubbuildingSpec spec, ZoneAnchors anchors,
                                        int index, RandomSource rng) {
        BlockPos anchor = anchors.get(spec.zone(), index);
        if (anchor == null) return Optional.empty();

        int groundY = terrain.groundY(anchor);
        BlockPos origin = anchor.atY(groundY);

        // Try NBT template first
        if (spec.hasPool()) {
            Optional<BlockPos> nbtPos = placeFromPool(spec, origin, rng);
            if (nbtPos.isPresent()) {
                if (spec.hasLoot()) placeLootChest(nbtPos.get().above(), spec.lootTable());
                return nbtPos;
            }
        }

        // Procedural fallback
        BlockPos fallbackPos = placeProceduralFallback(spec.type(), origin, rng);
        if (spec.hasLoot()) placeLootChest(fallbackPos.above(), spec.lootTable());
        return Optional.of(fallbackPos);
    }

    // =========================================================================
    // NBT placement
    // =========================================================================

    private Optional<BlockPos> placeFromPool(SubbuildingSpec spec, BlockPos origin,
                                             RandomSource rng) {
        Optional<StructureTemplate> tmpl =
                detail.loadPoolForSubbuilding(spec.pool(), rng);
        if (tmpl.isEmpty()) return Optional.empty();

        StructureTemplate t = tmpl.get();
        net.minecraft.core.Vec3i size = t.getSize();
        BlockPos centered = new BlockPos(
                origin.getX() - size.getX() / 2,
                origin.getY(),
                origin.getZ() - size.getZ() / 2);

        t.placeInWorld(level, centered, centered,
                new StructurePlaceSettings().setIgnoreEntities(false),
                rng, Block.UPDATE_ALL);
        return Optional.of(origin);
    }

    // =========================================================================
    // Procedural fallbacks
    // =========================================================================

    private BlockPos placeProceduralFallback(SubbuildingType type, BlockPos origin,
                                             RandomSource rng) {
        return switch (type) {
            case THRONE_ROOM      -> placeThroneRoom(origin, rng);
            case COUNCIL_CHAMBER  -> placeCouncilChamber(origin, rng);
            case ROYAL_QUARTERS   -> placeRoyalQuarters(origin, rng);
            case NOBLE_QUARTERS   -> placeNobleQuarters(origin, rng);
            case BARRACKS         -> placeBarracks(origin, rng);
            case ARMOURY          -> placeArmoury(origin, rng);
            case TRAINING_YARD    -> placeTrainingYard(origin, rng);
            case GUARDHOUSE       -> placeGuardhouse(origin, rng);
            case LIBRARY          -> placeLibrary(origin, rng);
            case CHAPEL           -> placeChapel(origin, rng);
            case OBSERVATORY      -> placeObservatory(origin, rng);
            case ALCHEMIST_LAB    -> placeAlchemistLab(origin, rng);
            case TREASURY         -> placeTreasury(origin, rng);
            case GRANARY          -> placeGranary(origin, rng);
            case STABLE           -> placeStable(origin, rng);
            case KITCHEN          -> placeKitchen(origin, rng);
            case SMITHY           -> placeSmity(origin, rng);
            case TAILORS_ROOM     -> placeTailors(origin, rng);
            case SCRIPTORIUM      -> placeScriptorium(origin, rng);
            case WELL             -> placeWell(origin, rng);
            case CISTERN          -> placeCistern(origin, rng);
            case DUNGEON          -> placeDungeon(origin, rng);
            case GATEHOUSE_OFFICE -> placeGatehouseOffice(origin, rng);
        };
    }

    // ---- Nobility ----

    private BlockPos placeThroneRoom(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 9, 7);
        clearRoom(o, 9, 5, 7, rng);
        placeBlock(o.offset(4, 0, 1), Blocks.ENCHANTING_TABLE.defaultBlockState());
        placeBlock(o.offset(3, 2, 1), bannerState(Direction.SOUTH));
        placeBlock(o.offset(5, 2, 1), bannerState(Direction.SOUTH));
        placeColumn(o.offset(1, 0, 2), 4, palette.pick("support", rng));
        placeColumn(o.offset(7, 0, 2), 4, palette.pick("support", rng));
        placeColumn(o.offset(1, 0, 5), 4, palette.pick("support", rng));
        placeColumn(o.offset(7, 0, 5), 4, palette.pick("support", rng));
        for (int z = 0; z < 7; z++)
            placeBlock(o.offset(4, 0, z), Blocks.RED_CARPET.defaultBlockState());
        return anchor;
    }

    private BlockPos placeCouncilChamber(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 7, 7);
        clearRoom(o, 7, 4, 7, rng);
        for (int x = 1; x <= 5; x++)
            for (int z = 1; z <= 5; z++)
                placeBlock(o.offset(x, 0, z),
                        palette.slab("slab", net.minecraft.world.level.block.state.properties.SlabType.TOP));
        placeBlock(o.offset(3, 0, 0),
                palette.stair("stair", Direction.NORTH, net.minecraft.world.level.block.state.properties.Half.BOTTOM, rng));
        placeBlock(o.offset(3, 0, 6),
                palette.stair("stair", Direction.SOUTH, net.minecraft.world.level.block.state.properties.Half.BOTTOM, rng));
        return anchor;
    }

    private BlockPos placeRoyalQuarters(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 7, 6);
        clearRoom(o, 7, 4, 6, rng);
        placeBlock(o.offset(1, 0, 1), bedState(Direction.EAST,
                net.minecraft.world.level.block.state.properties.BedPart.FOOT, Blocks.RED_BED));
        placeBlock(o.offset(2, 0, 1), bedState(Direction.EAST,
                net.minecraft.world.level.block.state.properties.BedPart.HEAD, Blocks.RED_BED));
        placeBlock(o.offset(5, 0, 1), Blocks.BOOKSHELF.defaultBlockState());
        placeBlock(o.offset(5, 0, 3), Blocks.CHEST.defaultBlockState());
        for (int x = 1; x < 6; x++)
            for (int z = 1; z < 5; z++)
                if (level.getBlockState(o.offset(x, 0, z)).isAir())
                    placeBlock(o.offset(x, 0, z), Blocks.PURPLE_CARPET.defaultBlockState());
        return anchor;
    }

    private BlockPos placeNobleQuarters(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 5, 5);
        clearRoom(o, 5, 4, 5, rng);
        placeBlock(o.offset(1, 0, 1), bedState(Direction.EAST,
                net.minecraft.world.level.block.state.properties.BedPart.FOOT, Blocks.WHITE_BED));
        placeBlock(o.offset(2, 0, 1), bedState(Direction.EAST,
                net.minecraft.world.level.block.state.properties.BedPart.HEAD, Blocks.WHITE_BED));
        placeBlock(o.offset(3, 0, 1), Blocks.CHEST.defaultBlockState());
        return anchor;
    }

    // ---- Military ----

    private BlockPos placeBarracks(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 9, 7);
        clearRoom(o, 9, 4, 7, rng);
        for (int i = 0; i < 3; i++) {
            int bx = 1 + i * 3;
            placeBlock(o.offset(bx, 0, 1), bedState(Direction.NORTH,
                    net.minecraft.world.level.block.state.properties.BedPart.FOOT, Blocks.WHITE_BED));
            placeBlock(o.offset(bx, 0, 2), bedState(Direction.NORTH,
                    net.minecraft.world.level.block.state.properties.BedPart.HEAD, Blocks.WHITE_BED));
            placeBlock(o.offset(bx, 0, 4), bedState(Direction.SOUTH,
                    net.minecraft.world.level.block.state.properties.BedPart.FOOT, Blocks.WHITE_BED));
            placeBlock(o.offset(bx, 0, 5), bedState(Direction.SOUTH,
                    net.minecraft.world.level.block.state.properties.BedPart.HEAD, Blocks.WHITE_BED));
        }
        placeBlock(o.offset(8, 1, 3), Blocks.OAK_FENCE.defaultBlockState());
        return anchor;
    }

    private BlockPos placeArmoury(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 5, 5);
        clearRoom(o, 5, 4, 5, rng);
        placeBlock(o.offset(1, 0, 1), Blocks.ANVIL.defaultBlockState());
        placeBlock(o.offset(3, 0, 1), Blocks.CHEST.defaultBlockState());
        placeBlock(o.offset(1, 0, 3), Blocks.CHEST.defaultBlockState());
        placeBlock(o.offset(3, 0, 3), Blocks.GRINDSTONE.defaultBlockState());
        return anchor;
    }

    private BlockPos placeTrainingYard(BlockPos anchor, RandomSource rng) {
        int w = 11, d = 11;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                BlockPos floor = anchor.offset(x - w / 2, -1, z - d / 2);
                if (level.getBlockState(floor).isAir())
                    placeBlock(floor, palette.pick("floor", rng));
                placeBlock(anchor.offset(x - w / 2, 0, z - d / 2),
                        Blocks.AIR.defaultBlockState());
            }
        }
        placeBlock(anchor.offset(-3, 0, -3), Blocks.HAY_BLOCK.defaultBlockState());
        placeBlock(anchor.offset(3, 0, -3), Blocks.HAY_BLOCK.defaultBlockState());
        placeBlock(anchor, Blocks.TARGET.defaultBlockState());
        return anchor;
    }

    private BlockPos placeGuardhouse(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 3, 3);
        clearRoom(o, 3, 3, 3, rng);
        placeBlock(o.offset(1, 0, 1), Blocks.CHEST.defaultBlockState());
        placeBlock(o.offset(1, 1, 1), Blocks.TORCH.defaultBlockState());
        return anchor;
    }

    // ---- Scholarly ----

    private BlockPos placeLibrary(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 7, 7);
        clearRoom(o, 7, 5, 7, rng);
        for (int x = 0; x < 7; x++) {
            placeBlock(o.offset(x, 0, 0), Blocks.BOOKSHELF.defaultBlockState());
            placeBlock(o.offset(x, 1, 0), Blocks.BOOKSHELF.defaultBlockState());
            placeBlock(o.offset(x, 0, 6), Blocks.BOOKSHELF.defaultBlockState());
            placeBlock(o.offset(x, 1, 6), Blocks.BOOKSHELF.defaultBlockState());
        }
        for (int z = 1; z < 6; z++) {
            placeBlock(o.offset(0, 0, z), Blocks.BOOKSHELF.defaultBlockState());
            placeBlock(o.offset(0, 1, z), Blocks.BOOKSHELF.defaultBlockState());
            placeBlock(o.offset(6, 0, z), Blocks.BOOKSHELF.defaultBlockState());
            placeBlock(o.offset(6, 1, z), Blocks.BOOKSHELF.defaultBlockState());
        }
        placeBlock(o.offset(2, 0, 3), Blocks.LECTERN.defaultBlockState());
        placeBlock(o.offset(4, 0, 3), Blocks.LECTERN.defaultBlockState());
        placeBlock(o.offset(3, 0, 3), Blocks.ENCHANTING_TABLE.defaultBlockState());
        return anchor;
    }

    private BlockPos placeChapel(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 7, 9);
        clearRoom(o, 7, 6, 9, rng);
        for (int z = 5; z <= 7; z++) {
            placeBlock(o.offset(1, 0, z), stairState(Direction.NORTH, rng));
            placeBlock(o.offset(2, 0, z), stairState(Direction.NORTH, rng));
            placeBlock(o.offset(4, 0, z), stairState(Direction.NORTH, rng));
            placeBlock(o.offset(5, 0, z), stairState(Direction.NORTH, rng));
        }
        placeBlock(o.offset(3, 0, 1), Blocks.LODESTONE.defaultBlockState());
        placeBlock(o.offset(2, 0, 1), Blocks.CANDLE.defaultBlockState());
        placeBlock(o.offset(4, 0, 1), Blocks.CANDLE.defaultBlockState());
        for (int z = 1; z < 9; z++)
            placeBlock(o.offset(3, 0, z), Blocks.WHITE_CARPET.defaultBlockState());
        return anchor;
    }

    private BlockPos placeObservatory(BlockPos anchor, RandomSource rng) {
        placeBlock(anchor, Blocks.LODESTONE.defaultBlockState());
        placeBlock(anchor.offset(1, 0, 0), Blocks.LECTERN.defaultBlockState());
        placeBlock(anchor.offset(-1, 0, 0), Blocks.BOOKSHELF.defaultBlockState());
        return anchor;
    }

    private BlockPos placeAlchemistLab(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 5, 5);
        clearRoom(o, 5, 4, 5, rng);
        placeBlock(o.offset(1, 0, 1), Blocks.BREWING_STAND.defaultBlockState());
        placeBlock(o.offset(3, 0, 1), Blocks.BREWING_STAND.defaultBlockState());
        placeBlock(o.offset(1, 0, 3), Blocks.CAULDRON.defaultBlockState());
        placeBlock(o.offset(3, 0, 3), Blocks.CHEST.defaultBlockState());
        placeBlock(o.offset(2, 0, 2), Blocks.CRAFTING_TABLE.defaultBlockState());
        placeBlock(o.offset(0, 1, 0), Blocks.BOOKSHELF.defaultBlockState());
        placeBlock(o.offset(4, 1, 0), Blocks.BOOKSHELF.defaultBlockState());
        return anchor;
    }

    // ---- Economic ----

    private BlockPos placeTreasury(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 5, 5);
        clearRoom(o, 5, 3, 5, rng);
        for (int x = 0; x < 5; x++)
            if (x != 2) {
                placeBlock(o.offset(x, 0, 4), Blocks.IRON_BARS.defaultBlockState());
                placeBlock(o.offset(x, 1, 4), Blocks.IRON_BARS.defaultBlockState());
            }
        placeBlock(o.offset(1, 0, 1), Blocks.CHEST.defaultBlockState());
        placeBlock(o.offset(3, 0, 1), Blocks.CHEST.defaultBlockState());
        placeBlock(o.offset(2, 0, 1), Blocks.GOLD_BLOCK.defaultBlockState());
        placeBlock(o.offset(1, 0, 3), Blocks.GOLD_BLOCK.defaultBlockState());
        placeBlock(o.offset(3, 0, 3), Blocks.GOLD_BLOCK.defaultBlockState());
        return anchor;
    }

    private BlockPos placeGranary(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 7, 5);
        clearRoom(o, 7, 5, 5, rng);
        for (int x = 1; x <= 5; x += 2)
            for (int z = 1; z <= 3; z++)
                placeBlock(o.offset(x, 0, z), Blocks.BARREL.defaultBlockState());
        return anchor;
    }

    private BlockPos placeStable(BlockPos anchor, RandomSource rng) {
        for (int i = 0; i < 3; i++) {
            int ox = anchor.getX() - 6 + i * 4;
            int z  = anchor.getZ();
            placeBlock(new BlockPos(ox + 3, anchor.getY(), z), Blocks.OAK_FENCE.defaultBlockState());
            placeBlock(new BlockPos(ox + 3, anchor.getY() + 1, z), Blocks.OAK_FENCE.defaultBlockState());
            placeBlock(new BlockPos(ox + 3, anchor.getY(), z + 1), Blocks.OAK_FENCE.defaultBlockState());
            placeBlock(new BlockPos(ox + 3, anchor.getY() + 1, z + 1), Blocks.OAK_FENCE.defaultBlockState());
            placeBlock(new BlockPos(ox + 1, anchor.getY(), z + 1), Blocks.HAY_BLOCK.defaultBlockState());
            placeBlock(new BlockPos(ox + 2, anchor.getY(), z + 1), Blocks.WATER_CAULDRON.defaultBlockState());
        }
        return anchor;
    }

    private BlockPos placeKitchen(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 7, 5);
        clearRoom(o, 7, 4, 5, rng);
        placeBlock(o.offset(1, 0, 1), Blocks.SMOKER.defaultBlockState());
        placeBlock(o.offset(2, 0, 1), Blocks.SMOKER.defaultBlockState());
        placeBlock(o.offset(3, 0, 1), Blocks.CAMPFIRE.defaultBlockState());
        placeBlock(o.offset(4, 0, 1), Blocks.CAULDRON.defaultBlockState());
        placeBlock(o.offset(5, 0, 1), Blocks.BARREL.defaultBlockState());
        placeBlock(o.offset(1, 0, 3), palette.slab("slab", net.minecraft.world.level.block.state.properties.SlabType.TOP));
        placeBlock(o.offset(2, 0, 3), palette.slab("slab", net.minecraft.world.level.block.state.properties.SlabType.TOP));
        placeBlock(o.offset(3, 0, 3), palette.slab("slab", net.minecraft.world.level.block.state.properties.SlabType.TOP));
        placeBlock(o.offset(4, 0, 3), Blocks.CHEST.defaultBlockState());
        return anchor;
    }

    // ---- Crafting ----

    private BlockPos placeSmity(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 5, 5);
        clearRoom(o, 5, 4, 5, rng);
        placeBlock(o.offset(1, 0, 1), Blocks.BLAST_FURNACE.defaultBlockState());
        placeBlock(o.offset(2, 0, 1), Blocks.BLAST_FURNACE.defaultBlockState());
        placeBlock(o.offset(3, 0, 1), Blocks.ANVIL.defaultBlockState());
        placeBlock(o.offset(1, 0, 3), Blocks.CHEST.defaultBlockState());
        placeBlock(o.offset(3, 0, 3), Blocks.SMITHING_TABLE.defaultBlockState());
        placeBlock(o.offset(2, 0, 3), Blocks.NETHER_BRICKS.defaultBlockState());
        placeBlock(o.offset(2, 1, 3), Blocks.LAVA.defaultBlockState());
        return anchor;
    }

    private BlockPos placeTailors(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 5, 5);
        clearRoom(o, 5, 4, 5, rng);
        placeBlock(o.offset(1, 0, 1), Blocks.LOOM.defaultBlockState());
        placeBlock(o.offset(3, 0, 1), Blocks.LOOM.defaultBlockState());
        placeBlock(o.offset(2, 0, 3), Blocks.CHEST.defaultBlockState());
        placeBlock(o.offset(1, 0, 3), Blocks.WHITE_WOOL.defaultBlockState());
        placeBlock(o.offset(3, 0, 3), Blocks.WHITE_WOOL.defaultBlockState());
        return anchor;
    }

    private BlockPos placeScriptorium(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 5, 5);
        clearRoom(o, 5, 4, 5, rng);
        placeBlock(o.offset(1, 0, 1), Blocks.LECTERN.defaultBlockState());
        placeBlock(o.offset(3, 0, 1), Blocks.LECTERN.defaultBlockState());
        placeBlock(o.offset(1, 0, 3), Blocks.LECTERN.defaultBlockState());
        placeBlock(o.offset(3, 0, 3), Blocks.BOOKSHELF.defaultBlockState());
        placeBlock(o.offset(2, 0, 2), Blocks.BOOKSHELF.defaultBlockState());
        return anchor;
    }

    // ---- Infrastructure ----

    private BlockPos placeWell(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 3, 3);
        BlockState brick = palette.pick("fill", rng);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                boolean isCorner = (x == 0 || x == 2) && (z == 0 || z == 2);
                if (isCorner) {
                    placeBlock(o.offset(x, 0, z), brick);
                    placeBlock(o.offset(x, 1, z), brick);
                    placeBlock(o.offset(x, 2, z), Blocks.OAK_FENCE.defaultBlockState());
                } else if (x == 1 && z == 1) {
                    placeBlock(o.offset(x, -1, z), Blocks.WATER.defaultBlockState());
                } else {
                    placeBlock(o.offset(x, 0, z), brick);
                }
            }
        }
        placeBlock(o.offset(1, 3, 0), Blocks.OAK_FENCE.defaultBlockState());
        placeBlock(o.offset(1, 3, 2), Blocks.OAK_FENCE.defaultBlockState());
        placeBlock(o.offset(1, 3, 1), Blocks.IRON_CHAIN.defaultBlockState());
        return anchor;
    }

    private BlockPos placeCistern(BlockPos anchor, RandomSource rng) {
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                for (int y = -3; y < 0; y++) {
                    BlockPos pos = anchor.offset(x - 2, y, z - 2);
                    boolean isWall = x == 0 || x == 4 || z == 0 || z == 4 || y == -3;
                    placeBlock(pos, isWall
                            ? palette.pick("fill", rng)
                            : Blocks.WATER.defaultBlockState());
                }
            }
        }
        return anchor;
    }

    private BlockPos placeDungeon(BlockPos anchor, RandomSource rng) {
        BlockPos o = anchor.offset(-3, 0, -2);
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 5; z++) {
                boolean isWall     = x == 0 || x == 6 || z == 0 || z == 4;
                boolean isDivider  = x == 3;
                boolean isDoorGap  = isDivider && z == 2;
                BlockPos pos = o.offset(x, 0, z);
                if (isWall) {
                    placeBlock(pos,         palette.pick("fill", rng));
                    placeBlock(pos.above(),  palette.pick("fill", rng));
                } else if (isDivider && !isDoorGap) {
                    placeBlock(pos,         Blocks.IRON_BARS.defaultBlockState());
                    placeBlock(pos.above(), Blocks.IRON_BARS.defaultBlockState());
                }
            }
        }
        placeBlock(o.offset(1, 1, 2), Blocks.IRON_CHAIN.defaultBlockState());
        placeBlock(o.offset(5, 1, 2), Blocks.IRON_CHAIN.defaultBlockState());
        return anchor;
    }

    private BlockPos placeGatehouseOffice(BlockPos anchor, RandomSource rng) {
        BlockPos o = centerRoom(anchor, 3, 3);
        clearRoom(o, 3, 3, 3, rng);
        placeBlock(o.offset(1, 0, 1), Blocks.LECTERN.defaultBlockState());
        placeBlock(o.offset(2, 0, 1), Blocks.CHEST.defaultBlockState());
        return anchor;
    }

    // =========================================================================
    // Courtyard ground treatment
    // =========================================================================

    private void placeCourtyardGround(CastleLayout layout, RandomSource rng) {
        StyleDetailConfig.CourtyardStyle cs = style.styleDetail().courtyardStyle();
        if (cs == StyleDetailConfig.CourtyardStyle.BARE) return;

        BlockPos origin = layout.getOrigin();
        int groundY = terrain.groundY(origin);
        int innerR  = 8;

        switch (cs) {
            case PAVED    -> paveCourt(origin, groundY, innerR, rng);
            case GARDEN   -> plantGarden(origin, groundY, innerR, rng);
            case FOUNTAIN -> placeFountain(origin, groundY, rng);
            default -> {}
        }
    }

    private void paveCourt(BlockPos origin, int groundY, int r, RandomSource rng) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                BlockPos floor = origin.offset(dx, groundY - 1, dz);
                BlockState cur = level.getBlockState(floor);
                if (cur.is(Blocks.GRASS_BLOCK) || cur.is(Blocks.DIRT)) {
                    placeBlock(floor, palette.pick("floor", rng));
                }
            }
        }
    }

    private void plantGarden(BlockPos origin, int groundY, int r, RandomSource rng) {
        Block[] flowers = {Blocks.DANDELION, Blocks.POPPY, Blocks.BLUE_ORCHID,
                Blocks.ALLIUM, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY};
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                BlockPos surface = origin.offset(dx, groundY, dz);
                if (!level.getBlockState(surface).isAir()) continue;
                if (!level.getBlockState(surface.below()).is(Blocks.GRASS_BLOCK)) continue;
                if (rng.nextFloat() < 0.08f)
                    placeBlock(surface,
                            flowers[rng.nextInt(flowers.length)].defaultBlockState());
            }
        }
        BlockPos center = origin.atY(groundY);
        if (level.getBlockState(center).isAir())
            placeBlock(center, Blocks.OAK_SAPLING.defaultBlockState());
    }

    private void placeFountain(BlockPos origin, int groundY, RandomSource rng) {
        BlockState brick = palette.pick("fill", rng);
        BlockState accent = palette.pick("trim", rng);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean isEdge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                BlockPos pos = origin.offset(dx, groundY - 1, dz);
                if (isEdge) {
                    placeBlock(pos,         brick);
                    placeBlock(pos.above(),  brick);
                } else if (dx != 0 || dz != 0) {
                    placeBlock(pos, Blocks.WATER.defaultBlockState());
                } else {
                    placeBlock(pos,          accent);
                    placeBlock(pos.above(),   accent);
                    placeBlock(pos.above(2), Blocks.WATER.defaultBlockState());
                }
            }
        }
    }

    // =========================================================================
    // Zone anchor resolution
    // =========================================================================

    private ZoneAnchors resolveAnchors(CastleLayout layout) {
        ZoneAnchors anchors = new ZoneAnchors();
        BlockPos origin = layout.getOrigin();
        int outerRadius = layout.getOuterTowers().stream()
                .mapToInt(t -> (int)Math.sqrt(t.center().distSqr(origin)))
                .max().orElse(20);

        if (layout.hasDonjon()) {
            BlockPos dc = layout.getDonjon().center();
            int gy = terrain.groundY(dc);
            anchors.add(SubbuildingType.PlacementZone.DONJON_GROUND, dc.atY(gy));
            anchors.add(SubbuildingType.PlacementZone.DONJON_UPPER,
                    dc.atY(gy + CastleConstants.FLOOR_INTERVAL));
            anchors.add(SubbuildingType.PlacementZone.DONJON_UPPER,
                    dc.atY(gy + CastleConstants.FLOOR_INTERVAL * 2));
        } else {
            int gy = terrain.groundY(origin);
            anchors.add(SubbuildingType.PlacementZone.DONJON_GROUND, origin.atY(gy));
            anchors.add(SubbuildingType.PlacementZone.DONJON_UPPER,
                    origin.atY(gy + CastleConstants.FLOOR_INTERVAL));
        }

        int innerR = layout.hasInnerWard()
                ? (int)(outerRadius * style.donjon().innerWardScale()) - WARD_SPACING
                : outerRadius / 2;
        addRingAnchors(anchors, SubbuildingType.PlacementZone.INNER_WARD,
                origin, innerR, 8);
        addRingAnchors(anchors, SubbuildingType.PlacementZone.OUTER_WARD,
                origin, outerRadius - WARD_SPACING * 2, 8);
        addRingAnchors(anchors, SubbuildingType.PlacementZone.COURTYARD,
                origin, innerR + WARD_SPACING / 2, 4);

        anchors.add(SubbuildingType.PlacementZone.UNDERGROUND,
                origin.atY(terrain.groundY(origin) - 6));

        for (CastleLayout.TowerNode tower : layout.getOuterTowers()) {
            if (tower.role() != CastleLayout.TowerNode.TowerRole.GATEHOUSE) {
                anchors.add(SubbuildingType.PlacementZone.TOWER_INTERIOR,
                        tower.center().atY(terrain.groundY(tower.center())));
            }
            anchors.add(SubbuildingType.PlacementZone.WALL_INTERIOR,
                    tower.center().atY(terrain.groundY(tower.center())));
        }

        return anchors;
    }

    private void addRingAnchors(ZoneAnchors anchors,
                                SubbuildingType.PlacementZone zone,
                                BlockPos center, int radius, int count) {
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            int x = center.getX() + (int)(Math.cos(angle) * radius);
            int z = center.getZ() + (int)(Math.sin(angle) * radius);
            anchors.add(zone, new BlockPos(x, terrain.groundY(x, z), z));
        }
    }

    // =========================================================================
    // Loot chest
    // =========================================================================

    private void placeLootChest(BlockPos pos, Identifier lootTable) {
        if (lootTable.equals(SubbuildingSpec.NO_LOOT)) return;
        if (!level.getBlockState(pos).isAir()) return;

        placeBlock(pos, Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.SOUTH));

        if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            ResourceKey<LootTable> key = ResourceKey.create(
                    net.minecraft.core.registries.Registries.LOOT_TABLE, lootTable);
            chest.setLootTable(key, pos.asLong());
        }
    }

    // =========================================================================
    // Room clearing
    // =========================================================================

    private void clearRoom(BlockPos origin, int width, int height, int depth,
                           RandomSource rng) {
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                BlockPos floor = origin.offset(x, -1, z);
                if (level.getBlockState(floor).isAir())
                    placeBlock(floor, palette.pick("floor", rng));
                for (int y = 0; y < height; y++) {
                    BlockPos pos = origin.offset(x, y, z);
                    boolean isWall = x == 0 || x == width - 1
                            || z == 0 || z == depth - 1;
                    if (!isWall)
                        placeBlock(pos, Blocks.AIR.defaultBlockState());
                    if (y == height - 1 && !isWall)
                        placeBlock(pos, palette.pick("fill", rng));
                }
            }
        }
    }

    private BlockPos centerRoom(BlockPos anchor, int width, int depth) {
        return anchor.offset(-width / 2, 0, -depth / 2);
    }

    // =========================================================================
    // Block helpers
    // =========================================================================

    private void placeColumn(BlockPos base, int height, BlockState state) {
        for (int i = 0; i < height; i++) placeBlock(base.above(i), state);
    }

    private BlockState bannerState(Direction facing) {
        return Blocks.WHITE_WALL_BANNER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.WallBannerBlock.FACING, facing);
    }

    private BlockState bedState(Direction facing,
                                net.minecraft.world.level.block.state.properties.BedPart part,
                                Block bedBlock) {
        return bedBlock.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, facing)
                .setValue(net.minecraft.world.level.block.BedBlock.PART, part);
    }

    private BlockState stairState(Direction facing, RandomSource rng) {
        return palette.stair("stair", facing,
                net.minecraft.world.level.block.state.properties.Half.BOTTOM, rng);
    }

    private void placeBlock(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    // =========================================================================
    // ZoneAnchors
    // =========================================================================

    private static class ZoneAnchors {
        private final Map<SubbuildingType.PlacementZone, List<BlockPos>> map =
                new EnumMap<>(SubbuildingType.PlacementZone.class);

        void add(SubbuildingType.PlacementZone zone, BlockPos pos) {
            map.computeIfAbsent(zone, z -> new ArrayList<>()).add(pos);
        }

        BlockPos get(SubbuildingType.PlacementZone zone, int index) {
            List<BlockPos> list = map.get(zone);
            if (list == null || list.isEmpty()) return null;
            return list.get(index % list.size());
        }
    }
}