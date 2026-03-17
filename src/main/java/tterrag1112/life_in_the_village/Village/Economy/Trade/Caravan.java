package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.*;

public class Caravan {

    public enum CaravanState {
        OUTBOUND,    // travelling from A to B
        RETURNING,   // travelling from B back to A
        DELIVERING,  // arrived, transferring goods
        FAILED;      // attacked/destroyed

        public static final Codec<CaravanState> CODEC =
                Codec.STRING.xmap(CaravanState::valueOf,
                        CaravanState::name);
    }

    public static final Codec<Caravan> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.xmap(UUID::fromString,
                                    UUID::toString)
                            .fieldOf("caravanId")
                            .forGetter(Caravan::getCaravanId),
                    Codec.STRING.xmap(UUID::fromString,
                                    UUID::toString)
                            .fieldOf("routeId")
                            .forGetter(Caravan::getRouteId),
                    Codec.STRING.xmap(UUID::fromString,
                                    UUID::toString)
                            .fieldOf("originVillageId")
                            .forGetter(Caravan::getOriginVillageId),
                    Codec.STRING.xmap(UUID::fromString,
                                    UUID::toString)
                            .fieldOf("destVillageId")
                            .forGetter(Caravan::getDestVillageId),
                    CaravanState.CODEC.fieldOf("state")
                            .forGetter(Caravan::getState),
                    Codec.DOUBLE.fieldOf("progress")
                            .forGetter(Caravan::getProgress),
                    Codec.LONG.fieldOf("dispatchTick")
                            .forGetter(Caravan::getDispatchTick),
                    ItemStack.CODEC.listOf()
                            .fieldOf("goods")
                            .forGetter(Caravan::getGoods),
                    Codec.INT.fieldOf("guardCount")
                            .forGetter(Caravan::getGuardCount),
                    Codec.BOOL.fieldOf("isSpawned")
                            .forGetter(c -> false),
                    Codec.STRING.xmap(UUID::fromString,
                                    UUID::toString)
                            .optionalFieldOf("merchantEntityId")
                            .forGetter(c -> Optional.ofNullable(
                                    c.getMerchantEntityId())),
                    Codec.STRING.xmap(UUID::fromString,
                                    UUID::toString)
                            .listOf()
                            .fieldOf("guardEntityIds")
                            .forGetter(Caravan::getGuardEntityIds),
                    Codec.STRING.xmap(UUID::fromString,
                                    UUID::toString)
                            .optionalFieldOf("cartEntityId")
                            .forGetter(c -> Optional.ofNullable(
                                    c.getCartEntityId()))
            ).apply(instance, Caravan::fromCodec));

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final UUID caravanId;
    private final UUID routeId;
    private final UUID originVillageId;
    private final UUID destVillageId;
    private CaravanState state;
    private double progress;       // 0.0 = at origin, 1.0 = at dest
    private final long dispatchTick;
    private final List<ItemStack> goods;
    private final int guardCount;

    // Transient — never persisted
    private boolean isSpawned       = false;
    private UUID merchantEntityId   = null;
    private List<UUID> guardEntityIds = new ArrayList<>();
    private UUID cartEntityId       = null;

    public Caravan(UUID caravanId, UUID routeId,
                   UUID originVillageId, UUID destVillageId,
                   CaravanState state, double progress,
                   long dispatchTick, List<ItemStack> goods,
                   int guardCount) {
        this.caravanId       = caravanId;
        this.routeId         = routeId;
        this.originVillageId = originVillageId;
        this.destVillageId   = destVillageId;
        this.state           = state;
        this.progress        = progress;
        this.dispatchTick    = dispatchTick;
        this.goods           = new ArrayList<>(goods);
        this.guardCount      = guardCount;
    }

    public static Caravan fromCodec(
            UUID caravanId, UUID routeId,
            UUID originVillageId, UUID destVillageId,
            CaravanState state, double progress,
            long dispatchTick, List<ItemStack> goods,
            int guardCount, boolean isSpawned,
            Optional<UUID> merchantEntityId,
            List<UUID> guardEntityIds,
            Optional<UUID> cartEntityId) {
        Caravan c = new Caravan(caravanId, routeId,
                originVillageId, destVillageId, state,
                progress, dispatchTick, goods, guardCount);
        // Never restore spawned state
        c.isSpawned = false;
        return c;
    }

    public static Caravan create(UUID routeId,
                                 UUID originVillageId,
                                 UUID destVillageId,
                                 List<ItemStack> goods,
                                 int guardCount,
                                 long currentTick) {
        return new Caravan(
                UUID.randomUUID(),
                routeId,
                originVillageId,
                destVillageId,
                CaravanState.OUTBOUND,
                0.0,
                currentTick,
                goods,
                guardCount
        );
    }

    // -------------------------------------------------------------------------
    // Simulation tick
    // -------------------------------------------------------------------------

    /**
     * Advances caravan progress when unobserved.
     * Returns true if dirty.
     */
    public boolean tick(long currentTick,
                        double speedMultiplier) {
        if (isSpawned) return false;
        if (state == CaravanState.FAILED) return false;
        if (state == CaravanState.DELIVERING) return false;

        // speedMultiplier is now a bonus — 1.0 minimum
        double increment = BASE_PROGRESS_PER_TICK
                * Math.max(1.0, speedMultiplier);
        progress = Math.min(1.0, progress + increment);

        if (progress >= 1.0) {
            if (state == CaravanState.OUTBOUND) {
                state    = CaravanState.DELIVERING;
                progress = 1.0;
            } else if (state == CaravanState.RETURNING) {
                progress = 1.0;
            }
            return true;
        }

        return true;
    }

    private static final double BASE_PROGRESS_PER_TICK = 0.002;

    /**
     * Gets the current world position based on progress
     * along the road path.
     */
    public BlockPos getWorldPosition(
            List<BlockPos> roadBlocks) {
        if (roadBlocks.isEmpty()) return null;

        if (state == CaravanState.RETURNING) {
            // Returning — traverse road in reverse
            int index = (int)((1.0 - progress)
                    * (roadBlocks.size() - 1));
            index = Math.max(0, Math.min(
                    roadBlocks.size() - 1, index));
            return roadBlocks.get(index);
        }

        int index = (int)(progress
                * (roadBlocks.size() - 1));
        index = Math.max(0, Math.min(
                roadBlocks.size() - 1, index));
        return roadBlocks.get(index);
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public UUID getCaravanId()           { return caravanId; }
    public UUID getRouteId()             { return routeId; }
    public UUID getOriginVillageId()     { return originVillageId; }
    public UUID getDestVillageId()       { return destVillageId; }
    public CaravanState getState()       { return state; }
    public double getProgress()          { return progress; }
    public long getDispatchTick()        { return dispatchTick; }
    public List<ItemStack> getGoods()    { return goods; }
    public int getGuardCount()           { return guardCount; }
    public boolean isSpawned()           { return isSpawned; }
    public UUID getMerchantEntityId()    { return merchantEntityId; }
    public List<UUID> getGuardEntityIds(){ return guardEntityIds; }
    public UUID getCartEntityId()        { return cartEntityId; }

    public void setState(CaravanState state) {
        this.state = state;
    }
    public void setProgress(double p) {
        this.progress = Math.max(0, Math.min(1, p));
    }
    public void setSpawned(boolean spawned) {
        this.isSpawned = spawned;
    }
    public void setMerchantEntityId(UUID id) {
        this.merchantEntityId = id;
    }
    public void addGuardEntityId(UUID id) {
        this.guardEntityIds.add(id);
    }
    public void setCartEntityId(UUID id) {
        this.cartEntityId = id;
    }
    public void clearEntityIds() {
        this.merchantEntityId = null;
        this.guardEntityIds.clear();
        this.cartEntityId = null;
        this.isSpawned = false;
    }
}