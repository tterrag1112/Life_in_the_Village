package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Travel.Roster;
import tterrag1112.life_in_the_village.Village.Travel.TravellingGroup;

import java.util.*;

public class Caravan implements TravellingGroup {

    public enum CaravanState {
        OUTBOUND,    // travelling from A to B
        RETURNING,   // travelling from B back to A
        DELIVERING,  // arrived, transferring goods
        FAILED;      // attacked/destroyed

        public static final Codec<CaravanState> CODEC =
                Codec.STRING.xmap(CaravanState::valueOf,
                        CaravanState::name);
    }

    /**
     * What a caravan is for (merchant arc Phase 4a). EXPORT is the legacy
     * behaviour: loaded at origin, deposits its cargo into the destination
     * stockpile on arrival. PROCUREMENT is the import counterpart: starts
     * <b>empty</b> carrying a {@code shoppingList}, buys those goods at the
     * destination (the surplus source) at the source's per-village price,
     * and sells them back home — both legs settled through {@code
     * NpcEconomy} so the merchant keeps the inter-village spread.
     */
    public enum CaravanKind {
        EXPORT,
        PROCUREMENT;

        public static final Codec<CaravanKind> CODEC =
                Codec.STRING.xmap(CaravanKind::valueOf, CaravanKind::name);
    }

    public static final Codec<Caravan> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("caravanId")
                            .forGetter(Caravan::getCaravanId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("routeId")
                            .forGetter(Caravan::getRouteId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("originVillageId")
                            .forGetter(Caravan::getOriginVillageId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("destVillageId")
                            .forGetter(Caravan::getDestVillageId),
                    CaravanState.CODEC.fieldOf("state")
                            .forGetter(Caravan::getState),
                    Codec.DOUBLE.fieldOf("progress")
                            .forGetter(Caravan::getProgress),
                    Codec.LONG.fieldOf("dispatchTick")
                            .forGetter(Caravan::getDispatchTick),
                    ItemStack.CODEC.listOf().fieldOf("goods")
                            .forGetter(Caravan::getGoods),
                    Codec.INT.fieldOf("guardCount")
                            .forGetter(Caravan::getGuardCount),
                    Roster.CODEC.fieldOf("roster")
                            .forGetter(Caravan::getRoster),
                    // Phase 4a — both optional so pre-4a saves load as
                    // EXPORT with an empty shopping list.
                    CaravanKind.CODEC.optionalFieldOf("kind", CaravanKind.EXPORT)
                            .forGetter(Caravan::getKind),
                    ItemStack.CODEC.listOf().optionalFieldOf("shoppingList", List.of())
                            .forGetter(Caravan::getShoppingList)
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
    private final Roster roster;
    private final CaravanKind kind;
    /** Phase 4a — items a PROCUREMENT caravan intends to buy at its
     *  destination. Empty for EXPORT caravans. */
    private final List<ItemStack> shoppingList;

    // Transient — never persisted directly; rebuilt at spawn
    private transient boolean isSpawned = false;

    public Caravan(UUID caravanId, UUID routeId,
                   UUID originVillageId, UUID destVillageId,
                   CaravanState state, double progress,
                   long dispatchTick, List<ItemStack> goods,
                   int guardCount, Roster roster) {
        this(caravanId, routeId, originVillageId, destVillageId, state, progress,
                dispatchTick, goods, guardCount, roster,
                CaravanKind.EXPORT, List.of());
    }

    public Caravan(UUID caravanId, UUID routeId,
                   UUID originVillageId, UUID destVillageId,
                   CaravanState state, double progress,
                   long dispatchTick, List<ItemStack> goods,
                   int guardCount, Roster roster,
                   CaravanKind kind, List<ItemStack> shoppingList) {
        this.caravanId       = caravanId;
        this.routeId         = routeId;
        this.originVillageId = originVillageId;
        this.destVillageId   = destVillageId;
        this.state           = state;
        this.progress        = progress;
        this.dispatchTick    = dispatchTick;
        this.goods           = new ArrayList<>(goods);
        this.guardCount      = guardCount;
        this.roster          = roster != null ? roster : new Roster();
        this.kind            = kind != null ? kind : CaravanKind.EXPORT;
        this.shoppingList    = new ArrayList<>(shoppingList == null ? List.of() : shoppingList);
    }

    public static Caravan fromCodec(
            UUID caravanId, UUID routeId,
            UUID originVillageId, UUID destVillageId,
            CaravanState state, double progress,
            long dispatchTick, List<ItemStack> goods,
            int guardCount, Roster roster,
            CaravanKind kind, List<ItemStack> shoppingList) {
        return new Caravan(caravanId, routeId,
                originVillageId, destVillageId,
                state, progress, dispatchTick,
                goods, guardCount, roster, kind, shoppingList);
    }

    public static Caravan create(UUID routeId,
                                 UUID originVillageId,
                                 UUID destVillageId,
                                 UUID principalId,
                                 UUID originMarketId,
                                 List<ItemStack> goods,
                                 int guardCount,
                                 long currentTick) {
        Roster r = new Roster();
        r.setPrincipalId(principalId);
        r.setOriginBuildingId(originMarketId);
        return new Caravan(
                UUID.randomUUID(),
                routeId,
                originVillageId,
                destVillageId,
                CaravanState.OUTBOUND,
                0.0,
                currentTick,
                goods,
                guardCount,
                r);
    }

    /**
     * Phase 4a — creates a PROCUREMENT caravan: starts <b>empty</b>
     * (goods loaded by the buy-at-source leg), carrying {@code shoppingList}
     * (the home deficit items to buy at the destination source).
     */
    public static Caravan createProcurement(UUID routeId,
                                            UUID originVillageId,
                                            UUID destVillageId,
                                            UUID principalId,
                                            UUID originMarketId,
                                            List<ItemStack> shoppingList,
                                            int guardCount,
                                            long currentTick) {
        Roster r = new Roster();
        r.setPrincipalId(principalId);
        r.setOriginBuildingId(originMarketId);
        return new Caravan(
                UUID.randomUUID(),
                routeId,
                originVillageId,
                destVillageId,
                CaravanState.OUTBOUND,
                0.0,
                currentTick,
                new ArrayList<>(),   // empty cargo outbound
                guardCount,
                r,
                CaravanKind.PROCUREMENT,
                shoppingList);
    }

    // Phase 4b — the simulation-tick + world-position duplicates that
    // lived here were dead: the live path is TravellingGroupEngine.tick /
    // computePosition, and BASE_PROGRESS_PER_TICK is the engine's constant.
    // Deleted (zero callers; not part of the TravellingGroup contract).

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
    public CaravanKind getKind()         { return kind; }
    public List<ItemStack> getShoppingList() { return shoppingList; }
    public boolean isProcurement()       { return kind == CaravanKind.PROCUREMENT; }

    public Roster getRoster() { return roster; }

    @Override
    public List<UUID> getEntityIds() {
        List<UUID> all = new ArrayList<>();
        if (roster.getPrincipalId() != null) all.add(roster.getPrincipalId());
        all.addAll(roster.getSpawnedEscortIds());
        all.addAll(roster.getSpawnedCarrierIds());
        return all;
    }

    public void setState(CaravanState state) {
        this.state = state;
    }
    public void setProgress(double p) {
        this.progress = Math.max(0, Math.min(1, p));
    }
    public void setSpawned(boolean spawned) {
        this.isSpawned = spawned;
    }

    // =========================================================================
    // TravellingGroup implementation
    // =========================================================================

    @Override
    public UUID groupId() { return caravanId; }

    @Override
    public List<BlockPos> getPath(ServerLevel level, VillageSavedData data) {
        TradeRoute route = data.getRouteById(routeId).orElse(null);
        if (route == null) return List.of();

        if (route.hasSegments()) {
            return resolveSegmentBlocks(level, route.getSegments());
        }

        if (route.hasGraphPath()) {
            return GraphTradeRouteEstablisher.resolveGraphBlocks(
                    WorldRoadSavedData.get(level).getGraph(),
                    route.getEdgeIds(),
                    route.getRouteStartNodeId());
        }

        return List.of();
    }

    /** Concatenates block paths for each segment, skipping the first block of each non-first segment. */
    public static List<BlockPos> resolveSegmentBlocks(ServerLevel level, List<RouteSegment> segments) {
        List<BlockPos> result = new ArrayList<>();
        for (RouteSegment seg : segments) {
            List<BlockPos> segBlocks = seg.resolveBlocks(level);
            if (segBlocks.isEmpty()) continue;
            int skip = result.isEmpty() ? 0 : 1;
            if (segBlocks.size() > skip) result.addAll(segBlocks.subList(skip, segBlocks.size()));
        }
        return result;
    }

    @Override
    public boolean isReversed() {
        return state == CaravanState.RETURNING;
    }

    @Override
    public double getSpeedMultiplier(ServerLevel level, VillageSavedData data) {
        TradeRoute route = data.getRouteById(routeId).orElse(null);
        if (route == null || !route.hasGraphPath()) return 1.0;

        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        int sumMaintenance = 0;
        int countedEdges   = 0;
        for (UUID edgeId : route.getEdgeIds()) {
            RoadEdge edge = graph.getEdge(edgeId);
            if (edge == null) continue;
            sumMaintenance += edge.getMaintenance();
            countedEdges++;
        }
        if (countedEdges == 0) return 1.0;
        int avgQuality = sumMaintenance / countedEdges;
        if (avgQuality <= 20) return 0.0;
        if (avgQuality <= 21) return 1.0;
        double normalized = (avgQuality - 21) / (100.0 - 21);
        return 1.0 + normalized;
    }

    @Override
    public void onSpawn(ServerLevel level, BlockPos spawnPos, VillageSavedData data) {
        // Delegate to the existing spawn logic on CaravanSavedData.
        // We can't call the private method directly from here, so the
        // SavedData provides a public spawn helper that takes a Caravan.
        CaravanSavedData.get(level).spawnCaravanEntities(this, spawnPos, level, data);
    }

    @Override
    public void onDespawn(ServerLevel level) {
        CaravanSavedData.get(level).despawnCaravanEntities(this, level);
    }

    @Override
    public void onTickSpawned(ServerLevel level, VillageSavedData data) {
        // CaravanMerchantGoal handles per-tick walking; nothing here
    }

    @Override
    public void onTickSimulated(ServerLevel level, VillageSavedData data) {
        // Engine advances progress; nothing else needed
    }

    @Override
    public void onPathComplete(ServerLevel level, VillageSavedData data) {
        if (state == CaravanState.OUTBOUND) {
            state = CaravanState.DELIVERING;
        }
        // RETURNING completion is handled by CaravanSavedData.tick which
        // checks for progress >= 1.0 and removes the caravan
    }
    public void onDespawned() {
        this.isSpawned = false;
        roster.clearSpawned();
    }
}