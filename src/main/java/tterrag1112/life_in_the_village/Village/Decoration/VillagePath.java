package tterrag1112.life_in_the_village.Village.Decoration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.UUID;

public class VillagePath {

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

    public static final Codec<VillagePath> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("id").forGetter(VillagePath::getId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("villageId").forGetter(VillagePath::getVillageId),
                    BlockPos.CODEC.listOf()
                            .fieldOf("blocks").forGetter(VillagePath::getBlocks),
                    Codec.STRING.xmap(PathTier::valueOf, PathTier::name)
                            .fieldOf("tier").forGetter(VillagePath::getTier)
            ).apply(instance, VillagePath::new)
    );

    private final UUID id;
    private final UUID villageId;
    private final List<BlockPos> blocks;
    private PathTier tier;

    public VillagePath(UUID id, UUID villageId,
                       List<BlockPos> blocks, PathTier tier) {
        this.id = id;
        this.villageId = villageId;
        this.blocks = List.copyOf(blocks);
        this.tier = tier;
    }

    public UUID getId()            { return id; }
    public UUID getVillageId()     { return villageId; }
    public List<BlockPos> getBlocks() { return blocks; }
    public PathTier getTier()      { return tier; }
    public void setTier(PathTier tier) { this.tier = tier; }
}
