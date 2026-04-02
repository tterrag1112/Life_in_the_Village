package tterrag1112.life_in_the_village.Village.Decoration.Roads;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent record of a road segment in a village.
 *
 * <h3>Changes from previous version</h3>
 * <ul>
 *   <li>Added {@code centerline} — the center positions of the road,
 *       separate from all placed blocks. This enables upgrades to widen
 *       the road without re-routing.</li>
 *   <li>Added {@code obsolete} flag — marks paths that are no longer
 *       part of the active network and should weather away.</li>
 *   <li>Added {@code weatherProgress} — tracks how far weathering has
 *       progressed (0.0 = fresh road, 1.0 = fully naturalized).</li>
 * </ul>
 *
 * <h3>Backward compatibility</h3>
 * The codec reads old VillagePaths without centerline/obsolete fields
 * by defaulting centerline to the full block list and obsolete to false.
 * Existing saves load correctly.
 */
public class VillagePath {

    // =========================================================================
    // Path tier
    // =========================================================================

    public enum PathTier {
        DIRT(0),
        GRAVEL(1),
        COBBLESTONE(2),
        STONE_BRICK(3);

        private final int level;
        PathTier(int level) { this.level = level; }
        public int getLevel() { return level; }

        public Block getBlock() {
            return switch (this) {
                case DIRT        -> Blocks.DIRT_PATH;
                case GRAVEL      -> Blocks.GRAVEL;
                case COBBLESTONE -> Blocks.COBBLESTONE;
                case STONE_BRICK -> Blocks.STONE_BRICKS;
            };
        }

        public PathTier next() {
            return switch (this) {
                case DIRT        -> GRAVEL;
                case GRAVEL      -> COBBLESTONE;
                case COBBLESTONE -> STONE_BRICK;
                case STONE_BRICK -> STONE_BRICK;
            };
        }

        public boolean isMaxTier() { return this == STONE_BRICK; }
    }

    // =========================================================================
    // Codec — backward compatible
    // =========================================================================

    public static final Codec<VillagePath> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("id").forGetter(VillagePath::getId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("villageId").forGetter(VillagePath::getVillageId),
                    BlockPos.CODEC.listOf()
                            .fieldOf("blocks").forGetter(VillagePath::getBlocks),
                    BlockPos.CODEC.listOf()
                            .optionalFieldOf("centerline", List.of())
                            .forGetter(VillagePath::getCenterline),
                    Codec.STRING.xmap(PathTier::valueOf, PathTier::name)
                            .fieldOf("tier").forGetter(VillagePath::getTier),
                    Codec.BOOL.optionalFieldOf("obsolete", false)
                            .forGetter(VillagePath::isObsolete),
                    Codec.FLOAT.optionalFieldOf("weatherProgress", 0.0f)
                            .forGetter(VillagePath::getWeatherProgress)
            ).apply(instance, VillagePath::new)
    );

    // =========================================================================
    // Fields
    // =========================================================================

    private final UUID id;
    private final UUID villageId;
    private List<BlockPos> blocks;
    private List<BlockPos> centerline;
    private PathTier tier;
    private boolean obsolete;
    private float weatherProgress;

    // =========================================================================
    // Constructors
    // =========================================================================

    /** Full constructor (used by codec). */
    public VillagePath(UUID id, UUID villageId,
                       List<BlockPos> blocks, List<BlockPos> centerline,
                       PathTier tier, boolean obsolete,
                       float weatherProgress) {
        this.id = id;
        this.villageId = villageId;
        this.blocks = new ArrayList<>(blocks);
        this.centerline = centerline.isEmpty()
                ? new ArrayList<>(blocks)  // backward compat: use blocks as centerline
                : new ArrayList<>(centerline);
        this.tier = tier;
        this.obsolete = obsolete;
        this.weatherProgress = weatherProgress;
    }

    /** New-style constructor with centerline. */
    public VillagePath(UUID id, UUID villageId,
                       List<BlockPos> blocks, List<BlockPos> centerline,
                       PathTier tier) {
        this(id, villageId, blocks, centerline, tier, false, 0.0f);
    }

    /** Legacy constructor (old code compatibility). */
    public VillagePath(UUID id, UUID villageId,
                       List<BlockPos> blocks, PathTier tier) {
        this(id, villageId, blocks, blocks, tier, false, 0.0f);
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public UUID getId()            { return id; }
    public UUID getVillageId()     { return villageId; }
    public List<BlockPos> getBlocks()     { return blocks; }
    public List<BlockPos> getCenterline() { return centerline; }
    public PathTier getTier()      { return tier; }
    public boolean isObsolete()    { return obsolete; }
    public float getWeatherProgress() { return weatherProgress; }

    // =========================================================================
    // Mutation
    // =========================================================================

    public void setTier(PathTier tier)          { this.tier = tier; }
    public void setObsolete(boolean obsolete)   { this.obsolete = obsolete; }

    /**
     * Advances weathering progress by one step.
     * @return true if weathering is complete (≥ 1.0)
     */
    public boolean advanceWeathering(float step) {
        this.weatherProgress = Math.min(1.0f, this.weatherProgress + step);
        return this.weatherProgress >= 1.0f;
    }

    /**
     * Replaces the placed block list after a road upgrade widens the road.
     */
    public void updatePlacedBlocks(List<BlockPos> newBlocks) {
        this.blocks = new ArrayList<>(newBlocks);
    }
}