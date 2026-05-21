package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Entities.ModEntities;
import tterrag1112.life_in_the_village.Entities.NpcNameRegistry;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Travel.Roster;
import tterrag1112.life_in_the_village.Village.Travel.TravellingGroupEngine;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;
import java.util.stream.Collectors;

public class CaravanSavedData extends SavedData {

    private static final int    TICK_INTERVAL = 20;
    private static final Random RANDOM        = new Random();

    public static final SavedDataType<CaravanSavedData> TYPE =
            new SavedDataType<>(
                    "caravans",
                    CaravanSavedData::new,
                    RecordCodecBuilder.create(instance ->
                            instance.group(
                                    Caravan.CODEC.listOf()
                                            .fieldOf("caravans")
                                            .forGetter(d ->
                                                    new ArrayList<>(
                                                            d.caravans
                                                                    .values()))
                            ).apply(instance,
                                    CaravanSavedData::fromCodec))
            );

    private static CaravanSavedData fromCodec(
            List<Caravan> caravanList) {
        CaravanSavedData data = new CaravanSavedData();
        caravanList.forEach(c ->
                data.caravans.put(c.getCaravanId(), c));
        return data;
    }

    private final Map<UUID, Caravan> caravans = new HashMap<>();
    private long lastTickTime = 0L;

    public static CaravanSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    public void tick(ServerLevel level, VillageSavedData villageData) {
        long currentTick = level.getGameTime();
        if (currentTick - lastTickTime < TICK_INTERVAL) return;
        lastTickTime = currentTick;

        boolean dirty = false;
        List<UUID> toRemove = new ArrayList<>();

        for (Caravan caravan : caravans.values()) {
            if (caravan.getState() == Caravan.CaravanState.FAILED) {
                if (caravan.isSpawned()) {
                    despawnCaravanEntities(caravan, level);
                }
                toRemove.add(caravan.getCaravanId());
                dirty = true;
                continue;
            }

            // ── Engine tick: handles spawn/despawn/progress/completion ──────
            if (TravellingGroupEngine.tick(caravan, level, villageData, currentTick)) {
                dirty = true;
            }

            // ── Caravan-specific delivery handling ──────────────────────────
            if (caravan.getState() == Caravan.CaravanState.DELIVERING) {
                handleDelivery(caravan, level, villageData);
                dirty = true;
            }

            // ── Returning caravan reached origin ────────────────────────────
            if (caravan.getState() == Caravan.CaravanState.RETURNING
                    && caravan.getProgress() >= 1.0) {
                // Caravan home — release the merchant
                UUID principalId = caravan.getRoster().getPrincipalId();
                if (principalId != null) {
                    var ent = level.getEntity(principalId);
                    if (ent instanceof TownspersonMob mob) {
                        clearCaravanRoles(mob);
                    }
                }
                if (caravan.isSpawned()) {
                    despawnCaravanEntities(caravan, level);
                }
                toRemove.add(caravan.getCaravanId());
                dirty = true;
            }
        }

        toRemove.forEach(id -> caravans.remove(id));

        // Try to dispatch new caravans
        if (currentTick % 24000L == 0) {
            dispatchNewCaravans(level, villageData, currentTick);
            dirty = true;
        }

        if (dirty) setDirty();
    }

    // -------------------------------------------------------------------------
    // Spawning
    // -------------------------------------------------------------------------

    public void spawnCaravanEntities(Caravan caravan,
                                     BlockPos pos,
                                     ServerLevel level,
                                     VillageSavedData villageData) {
        // This method is called from Caravan.onSpawn via the travelling
        // group engine. Scope: bring the caravan from dehydrated to
        // realised, using the pooled principal from the village merchant
        // pool.
        if (level == null) return;

        RandomSource random = level.getRandom();
        Roster roster = caravan.getRoster();
        UUID principalId = roster.getPrincipalId();

        // ── Principal: find the pooled merchant ───────────────────────────
        TownspersonMob merchant = null;
        if (principalId != null) {
            var existing = level.getEntity(principalId);
            if (existing instanceof TownspersonMob mob && !mob.isRemoved()) {
                merchant = mob;
            }
        }

        // Fallback: if the pooled merchant can't be found (saved data
        // inconsistency, villager died while simulated, etc.), spawn a
        // fresh merchant and log the issue. This keeps caravans working
        // even if pooling fails; Phase 7d will make the pool more robust.
        if (merchant == null) {
            System.out.println("CaravanSavedData: pooled merchant " + principalId
                    + " unavailable — spawning fresh merchant");
            merchant = ModEntities.TOWNSPERSON.get()
                    .create(level, EntitySpawnReason.NATURAL);
            if (merchant != null) {
                // Phase 6.3.3.a — gated, OTHER/SYSTEM (caravan merchant spawn).
                tterrag1112.life_in_the_village.Npc.Career.CareerTransitions.changeProfession(
                        merchant, Profession.MERCHANT,
                        tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Reason.OTHER,
                        tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Source.SYSTEM);
                merchant.setAssignedVillageName(
                        villageData.getVillageById(caravan.getOriginVillageId())
                                .map(Village::getName)
                                .orElse("Unknown"));
                String firstName = NpcNameRegistry.INSTANCE
                        .generateFirstName(merchant.isMale(), random);
                String surname = NpcNameRegistry.INSTANCE.generateSurname(random);
                merchant.setNpcName(firstName + " " + surname);
                roster.setPrincipalId(merchant.getUUID());
            }
        }

        if (merchant != null) {
            merchant.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            if (!merchant.isAlive() || merchant.isRemoved()) {
                level.addFreshEntity(merchant);
            }
            merchant.setCaravanId(caravan.getCaravanId());
            assignCaravanRole(merchant, caravan, tterrag1112.life_in_the_village
                    .Npc.Roles.NpcRoleTypes.CARAVAN_PRINCIPAL);
        }

        // ── Guards: still spawned fresh in Phase 7c ────────────────────────
        // Phase 7d will pull guards from the village barracks inventory.
        int guardCount = caravan.getGuardCount();
        for (int i = 0; i < guardCount; i++) {
            TownspersonMob guard = ModEntities.TOWNSPERSON.get()
                    .create(level, EntitySpawnReason.NATURAL);
            if (guard == null) continue;

            // Phase 6.3.3.a — gated, OTHER/SYSTEM (caravan guard spawn).
            tterrag1112.life_in_the_village.Npc.Career.CareerTransitions.changeProfession(
                    guard, Profession.GUARD,
                    tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Reason.OTHER,
                    tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Source.SYSTEM);
            guard.setAssignedVillageName(
                    villageData.getVillageById(caravan.getOriginVillageId())
                            .map(Village::getName)
                            .orElse("Unknown"));

            double angle = (i / (double) guardCount) * Math.PI * 2;
            double offsetX = Math.cos(angle) * 3;
            double offsetZ = Math.sin(angle) * 3;
            guard.setPos(pos.getX() + offsetX + 0.5,
                    pos.getY(),
                    pos.getZ() + offsetZ + 0.5);

            String firstName = NpcNameRegistry.INSTANCE
                    .generateFirstName(guard.isMale(), random);
            String surname = NpcNameRegistry.INSTANCE.generateSurname(random);
            guard.setNpcName(firstName + " " + surname);
            level.addFreshEntity(guard);
            guard.setCaravanId(caravan.getCaravanId());
            assignCaravanRole(guard, caravan, tterrag1112.life_in_the_village
                    .Npc.Roles.NpcRoleTypes.CARAVAN_ESCORT);
            roster.getSpawnedEscortIds().add(guard.getUUID());
        }

        // ── Llama: still spawned fresh in Phase 7c ────────────────────────
        Llama llama = new Llama(EntityType.LLAMA, level);
        llama.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        llama.setChest(true);
        List<ItemStack> goods = caravan.getGoods();
        var llamaInv = llama.getInventory();
        for (int i = 0; i < Math.min(goods.size(), llamaInv.getContainerSize() - 1); i++) {
            llamaInv.setItem(i + 1, goods.get(i).copy());
        }
        llama.setBodyArmorItem(new ItemStack(Items.BLUE_CARPET));
        llama.setTamed(true);
        llama.setCustomName(Component.literal("Caravan Llama"));
        llama.setCustomNameVisible(true);
        level.addFreshEntity(llama);
        roster.getSpawnedCarrierIds().add(llama.getUUID());

        if (merchant != null) {
            llama.setLeashedTo(merchant, true);
        }

        caravan.setSpawned(true);
    }

    public void despawnCaravanEntities(Caravan caravan, ServerLevel level) {
        if (level == null) return;
        Roster roster = caravan.getRoster();

        // Principal: discard the entity but KEEP the villager "away"
        // status. The entity record will be restored when the caravan
        // is re-realised later.
        UUID principalId = roster.getPrincipalId();
        if (principalId != null) {
            var ent = level.getEntity(principalId);
            if (ent instanceof TownspersonMob mob && !mob.isRemoved()) {
                // Don't clear currentExpeditionId — the merchant is
                // still on the caravan, just not currently in the world
                mob.discard();
            }
        }

        // Escorts: discard entirely in Phase 7c (they're spawned fresh each time)
        for (UUID id : roster.getSpawnedEscortIds()) {
            var ent = level.getEntity(id);
            if (ent != null && !ent.isRemoved()) ent.discard();
        }

        // Carriers (llama): discard — the animal is ephemeral until Phase 7d
        for (UUID id : roster.getSpawnedCarrierIds()) {
            var ent = level.getEntity(id);
            if (ent instanceof Llama llama && llama.isAlive()) {
                llama.dropLeash();
            }
            if (ent != null && !ent.isRemoved()) ent.discard();
        }

        caravan.onDespawned();
    }



    // -------------------------------------------------------------------------
    // Delivery
    // -------------------------------------------------------------------------

    private void handleDelivery(Caravan caravan,
                                ServerLevel level,
                                VillageSavedData villageData) {
        // Get trade efficiency from the route's edges
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        double efficiency = villageData
                .getRouteBetween(caravan.getOriginVillageId(),
                        caravan.getDestVillageId())
                .map(route -> route.getTradeEfficiency(
                        avgEdgeMaintenance(graph, route),
                        totalEdgeBlockLength(graph, route)))
                .orElse(0.5);

        // Deliver goods
        CaravanGoodsSelector.deliverGoods(
                level,
                caravan.getGoods(),
                caravan.getDestVillageId(),
                efficiency,
                villageData);

        // ── Pay the merchant their profit ─────────────────────────────────────
        payMerchantProfit(caravan, level, villageData, efficiency);

        // Switch to returning
        caravan.setProgress(0.0);
        caravan.setState(Caravan.CaravanState.RETURNING);

        System.out.println("CaravanSavedData: caravan "
                + caravan.getCaravanId().toString()
                .substring(0, 8)
                + " delivered goods, now returning");
    }

    // -------------------------------------------------------------------------
    // Dispatching
    // -------------------------------------------------------------------------

    private void dispatchNewCaravans(ServerLevel level,
                                     VillageSavedData villageData,
                                     long currentTick) {
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        for (TradeRoute route : villageData.getAllTradeRoutes()) {
            if (!route.isTradeAllowed()) continue;

            boolean hasActiveCaravan = caravans.values().stream()
                    .anyMatch(c -> c.getRouteId().equals(route.getRouteId())
                            && c.getState() != Caravan.CaravanState.FAILED);
            if (hasActiveCaravan) continue;

            // LAND-only here. Sea routes go through BoatCaravanSavedData.
            if (!route.hasGraphPath()) continue;
            // Track C3.3 — both land and sea routes now have edgeIds
            // (sea routes carry one SEA-tier edge), so the LAND-only
            // guard now also inspects the first edge's tier.
            RoadEdge firstEdgeProbe = graph.getEdge(route.getEdgeIds().get(0));
            if (firstEdgeProbe != null
                    && firstEdgeProbe.getTier() == RoadEdge.EdgeTier.SEA) continue;

            int quality      = avgEdgeMaintenance(graph, route);
            int lengthBlocks = totalEdgeBlockLength(graph, route);
            float chance     = route.getDailyCaravanChance(quality, lengthBlocks);
            if (RANDOM.nextFloat() > chance) continue;

            Village originVillage = villageData.getVillageById(route.getVillageA()).orElse(null);
            Village destVillage = villageData.getVillageById(route.getVillageB()).orElse(null);
            if (originVillage == null || destVillage == null) continue;

            // ── Reserve a merchant from the origin village ────────────────
            UUID principalId = villageData.reserveIdleMerchant(originVillage.getId(), level);
            if (principalId == null) {
                // No idle merchant available — skip this route today
                continue;
            }

            // Find the origin market building to record as the roster's origin
            UUID originMarketId = findFirstBuildingOfType(
                    originVillage, BuildingType.MARKET, villageData);

            List<ItemStack> goods = CaravanGoodsSelector.selectGoods(
                    level, originVillage, destVillage, villageData);
            int guardCount = calculateGuardCount(goods);

            Caravan caravan = Caravan.create(
                    route.getRouteId(),
                    route.getVillageA(),
                    route.getVillageB(),
                    principalId,
                    originMarketId,
                    goods,
                    guardCount,
                    currentTick);

            // After Caravan.create:
            var mob = level.getEntity(principalId);
            if (mob instanceof TownspersonMob m) {
                assignCaravanRole(m, caravan, tterrag1112.life_in_the_village
                        .Npc.Roles.NpcRoleTypes.CARAVAN_PRINCIPAL);
            }

            caravans.put(caravan.getCaravanId(), caravan);
            route.setLastCaravanTick(currentTick);

            // History event for first caravan on a new route
            boolean isFirst = caravans.values().stream()
                    .filter(c -> c.getRouteId().equals(route.getRouteId()))
                    .count() == 1;
            if (isFirst) {
                villageData.getKingdomForVillage(route.getVillageA())
                        .ifPresent(k -> {
                            String originName = originVillage.getName();
                            String destName = destVillage.getName();
                            k.getHistory().recordEvent(
                                    HistoryTextGenerator.firstCaravan(
                                            originName, destName,
                                            level.getGameTime()),
                                    k.getName(),
                                    k.getRulerName(level));
                            villageData.setDirty();
                        });
            }

            System.out.println("CaravanSavedData: dispatched caravan from "
                    + originVillage.getName() + " to " + destVillage.getName()
                    + " with principal " + principalId.toString().substring(0, 8)
                    + ", " + goods.size() + " goods, " + guardCount + " guards");
        }
    }



    /**
     * Finds the first building of the given type in a village, or null.
     */
    private UUID findFirstBuildingOfType(Village village,
                                         BuildingType type,
                                         VillageSavedData villageData) {
        for (UUID buildingId : village.getBuildingIds()) {
            var building = villageData.getBuildingById(buildingId).orElse(null);
            if (building != null && building.getType() == type) {
                return buildingId;
            }
        }
        return null;
    }

    private static int calculateGuardCount(
            List<ItemStack> goods) {
        // Count total items to estimate cargo value
        int totalItems = goods.stream()
                .mapToInt(ItemStack::getCount).sum();
        if (totalItems >= 128) return 3;
        if (totalItems >= 64)  return 2;
        return 1;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isPlayerNearby(ServerLevel level,
                                          BlockPos pos,
                                          int radius) {
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().closerThan(pos, radius))
                return true;
        }
        return false;
    }

    public void addCaravan(Caravan caravan) {
        caravans.put(caravan.getCaravanId(), caravan);
        setDirty();
    }

    public Optional<Caravan> getCaravan(UUID id) {
        return Optional.ofNullable(caravans.get(id));
    }

    public List<Caravan> getAllCaravans() {
        return new ArrayList<>(caravans.values());
    }

    public List<Caravan> getCaravansOnRoute(UUID routeId) {
        return caravans.values().stream()
                .filter(c -> c.getRouteId().equals(routeId))
                .collect(Collectors.toList());
    }
    public void removeCaravan(UUID id) {
        caravans.remove(id);
        setDirty();
    }
    /**
     * Pays the caravan merchant a profit based on the trade efficiency and
     * the approximate value of goods delivered.
     *
     * Profit = sum(item base prices) * efficiency * PROFIT_MARGIN
     * Paid directly into the merchant entity's coin inventory.
     */
    private void payMerchantProfit(Caravan caravan, ServerLevel level,
                                   VillageSavedData villageData,
                                   double efficiency) {
        if (caravan.getRoster().getPrincipalId() == null) return;

        var entity = level.getEntity(caravan.getRoster().getPrincipalId());
        if (!(entity instanceof TownspersonMob merchant)) return;

        long totalValue = caravan.getGoods().stream()
                .mapToLong(stack -> {
                    long base = tterrag1112.life_in_the_village
                            .Village.Economy.VillageEconomy
                            .getBasePrice(stack.getItem());
                    return base * stack.getCount();
                })
                .sum();

        if (totalValue <= 0) return;

        long profit = Math.max(1L, Math.round(totalValue * 0.15 * efficiency));

        // Withdraw from destination village treasury
        villageData.getVillageById(caravan.getDestVillageId())
                .ifPresent(v -> v.withdrawFromTreasury(profit));

        merchant.receive(CurrencyValue.of(profit));

        System.out.println("CaravanSavedData: merchant "
                + merchant.getNpcName() + " earned " + profit
                + " bronze profit from delivery");
    }

    /**
     * Average maintenance score (0–100) across the route's graph edges.
     * Returns 100 if the route has no resolvable edges (treat as pristine
     * so callers don't accidentally penalise an unrouted journey).
     */
    private static int avgEdgeMaintenance(WorldRoadGraph graph, TradeRoute route) {
        int sum = 0;
        int count = 0;
        for (UUID edgeId : route.getEdgeIds()) {
            RoadEdge edge = graph.getEdge(edgeId);
            if (edge == null) continue;
            sum += edge.getMaintenance();
            count++;
        }
        return count == 0 ? 100 : sum / count;
    }

    /** Sum of block-path lengths across the route's realised edges. */
    private static int totalEdgeBlockLength(WorldRoadGraph graph, TradeRoute route) {
        int total = 0;
        for (UUID edgeId : route.getEdgeIds()) {
            RoadEdge edge = graph.getEdge(edgeId);
            if (edge == null) continue;
            total += edge.getBlockPath().size();
        }
        return total;
    }

    // =========================================================================
    // Phase 6.3.2.a — role projection helpers
    // =========================================================================

    private static void assignCaravanRole(
            TownspersonMob npc, Caravan caravan,
            tterrag1112.life_in_the_village.Npc.Roles.RoleType type) {
        npc.getRoles().assignRole(
                tterrag1112.life_in_the_village.Npc.Roles.RoleAssignment.conditional(
                        type,
                        java.util.Map.of(
                                tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes.P_CARAVAN_ID,
                                caravan.getCaravanId().toString(),
                                tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes.P_CARAVAN_STATE,
                                caravan.getState().name())));
    }

    private static void clearCaravanRoles(TownspersonMob npc) {
        var r = npc.getRoles();
        r.removeRole(tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes.CARAVAN_PRINCIPAL);
        r.removeRole(tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes.CARAVAN_ESCORT);
        r.removeRole(tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes.CARAVAN_CARRIER);
    }
}