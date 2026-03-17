package tterrag1112.life_in_the_village.Village.Buildings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.util.Optional;
import java.util.UUID;

public class FarmPlot {

    public enum CropType {
        WHEAT, CARROTS, POTATOES, BEETROOT, MIXED;

        public Identifier getSeedItem() {
            return switch (this) {
                case WHEAT    -> Identifier.withDefaultNamespace("wheat_seeds");
                case CARROTS  -> Identifier.withDefaultNamespace("carrot");
                case POTATOES -> Identifier.withDefaultNamespace("potato");
                case BEETROOT -> Identifier.withDefaultNamespace("beetroot_seeds");
                case MIXED    -> Identifier.withDefaultNamespace("wheat_seeds");
            };
        }

        public Identifier getCropBlock() {
            return switch (this) {
                case WHEAT    -> Identifier.withDefaultNamespace("wheat");
                case CARROTS  -> Identifier.withDefaultNamespace("carrots");
                case POTATOES -> Identifier.withDefaultNamespace("potatoes");
                case BEETROOT -> Identifier.withDefaultNamespace("beetroot");
                case MIXED    -> Identifier.withDefaultNamespace("wheat");
            };
        }
    }

    public static final Codec<FarmPlot> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("id").forGetter(FarmPlot::getId),
                    Codec.STRING
                            .fieldOf("name").forGetter(FarmPlot::getName),
                    BlockPos.CODEC
                            .fieldOf("origin").forGetter(FarmPlot::getOrigin),
                    Codec.INT
                            .fieldOf("radius").forGetter(FarmPlot::getRadius),
                    Codec.STRING.xmap(CropType::valueOf, CropType::name)
                            .fieldOf("cropType").forGetter(FarmPlot::getCropType),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .optionalFieldOf("farmhouseId")
                            .forGetter(p -> Optional.ofNullable(p.getFarmhouseId()))
            ).apply(instance, (id, name, origin, radius, cropType, farmhouseId) -> {
                FarmPlot plot = new FarmPlot(id, name, origin, radius, cropType);
                farmhouseId.ifPresent(plot::setFarmhouseId);
                return plot;
            })
    );

    private final UUID id;
    private String name;
    private BlockPos origin;
    private int radius;
    private CropType cropType;
    private UUID farmhouseId; // nullable until assigned

    public FarmPlot(UUID id, String name, BlockPos origin, int radius, CropType cropType) {
        this.id = id;
        this.name = name;
        this.origin = origin;
        this.radius = radius;
        this.cropType = cropType;
    }

    // --- Spatial helpers ---

    public boolean contains(BlockPos pos) {
        double dx = pos.getX() - origin.getX();
        double dz = pos.getZ() - origin.getZ();
        return dx * dx + dz * dz <= (double) radius * radius;
    }

    public java.util.List<BlockPos> getFarmlandBlocks(net.minecraft.server.level.ServerLevel level) {
        java.util.List<BlockPos> result = new java.util.ArrayList<>();
        for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
            for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                BlockPos surface = new BlockPos(x, origin.getY(), z);
                if (!contains(surface)) continue;
                // Walk up/down a few blocks to find farmland
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos check = surface.offset(0, dy, 0);
                    net.minecraft.world.level.block.state.BlockState state =
                            level.getBlockState(check);
                    if (state.getBlock() instanceof net.minecraft.world.level.block.FarmBlock) {
                        result.add(check);
                        break;
                    }
                }
            }
        }
        return result;
    }

    // --- Getters/Setters ---

    public UUID getId()           { return id; }
    public String getName()       { return name; }
    public BlockPos getOrigin()   { return origin; }
    public int getRadius()        { return radius; }
    public CropType getCropType() { return cropType; }
    public UUID getFarmhouseId()  { return farmhouseId; }

    public void setName(String name)           { this.name = name; }
    public void setOrigin(BlockPos origin)     { this.origin = origin; }
    public void setRadius(int radius)          { this.radius = radius; }
    public void setCropType(CropType cropType) { this.cropType = cropType; }
    public void setFarmhouseId(UUID id)        { this.farmhouseId = id; }

    public boolean isAssigned() { return farmhouseId != null; }
}
