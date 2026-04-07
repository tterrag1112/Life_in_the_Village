package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Entities.ModEntities;
import tterrag1112.life_in_the_village.Entities.NpcNameRegistry;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;
import java.util.stream.Collectors;

public class CaravanSavedData extends SavedData {

    private static final int    SPAWN_RADIUS  = 64;
    private static final int    TICK_INTERVAL = 20;
    private static final double BASE_PROGRESS_PER_TICK = 0.002;

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

    public void tick(ServerLevel level,
                     VillageSavedData villageData) {
        long currentTick = level.getGameTime();
        if (currentTick - lastTickTime < TICK_INTERVAL) return;
        lastTickTime = currentTick;

        boolean dirty = false;
        List<UUID> toRemove = new ArrayList<>();

        for (Caravan caravan : caravans.values()) {
            if (caravan.getState()
                    == Caravan.CaravanState.FAILED) {
                if (caravan.isSpawned()) {
                    despawnCaravan(caravan, level);
                }
                toRemove.add(caravan.getCaravanId());
                dirty = true;
                continue;
            }

            // Get road for position calculation
            TradeRoad road = villageData
                    .getRouteById(caravan.getRouteId())
                    .flatMap(r -> villageData
                            .getRoadById(r.getRoadId()))
                    .orElse(null);

            double speedMult = road != null
                    ? road.getSpeedMultiplier() : 1.0;

            // Proximity check
            BlockPos caravanPos = road != null
                    ? caravan.getWorldPosition(road.getBlocks())
                    : null;

            if (caravanPos != null) {
                boolean playerNearby = isPlayerNearby(
                        level, caravanPos, SPAWN_RADIUS);

                if (playerNearby && !caravan.isSpawned()) {
                    spawnCaravan(caravan, caravanPos,
                            level, villageData);
                    dirty = true;
                } else if (!playerNearby
                        && caravan.isSpawned()) {
                    despawnCaravan(caravan, level);
                    dirty = true;
                }
            }

            // Tick simulation for unspawned caravans
            if (!caravan.isSpawned()) {
                if (caravan.tick(currentTick, speedMult)) {
                    dirty = true;
                }
            } else {
                // Update spawned caravan position along road
                if (road != null) {
                    updateSpawnedPosition(
                            caravan, road, level, currentTick,
                            speedMult);
                }
            }

            // Handle delivery
            if (caravan.getState()
                    == Caravan.CaravanState.DELIVERING) {
                handleDelivery(caravan, level, villageData);
                dirty = true;
            }

            // Handle return completion
            if (caravan.getState()
                    == Caravan.CaravanState.RETURNING
                    && caravan.getProgress() >= 1.0) {
                toRemove.add(caravan.getCaravanId());
                if (caravan.isSpawned()) {
                    despawnCaravan(caravan, level);
                }
                dirty = true;
            }
        }

        toRemove.forEach(id -> {
            caravans.remove(id);
        });

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

    private void spawnCaravan(Caravan caravan,
                              BlockPos pos,
                              ServerLevel level,
                              VillageSavedData villageData) {
        RandomSource random = level.getRandom();
        // Spawn merchant NPC
        TownspersonMob merchant = ModEntities.TOWNSPERSON
                .get().create(level,
                        net.minecraft.world.entity
                                .EntitySpawnReason.NATURAL);
        if (merchant != null) {
            merchant.setProfession(
                    Profession.MERCHANT);
            merchant.setAssignedVillageName(
                    villageData.getVillageById(
                                    caravan.getOriginVillageId())
                            .map(v -> v.getName())
                            .orElse("Unknown"));
            merchant.setPos(pos.getX() + 0.5,
                    pos.getY(), pos.getZ() + 0.5);
            String firstName = NpcNameRegistry.INSTANCE
                    .generateFirstName(merchant.isMale(), random);
            String surname = NpcNameRegistry.INSTANCE
                    .generateSurname(random);
            merchant.setNpcName(firstName + " " + surname);
            //merchant.setCustomNameVisible(true);
            level.addFreshEntity(merchant);
            caravan.setMerchantEntityId(merchant.getUUID());
            merchant.setCaravanId(caravan.getCaravanId());

        }

        // Spawn guards
        int guardCount = caravan.getGuardCount();
        for (int i = 0; i < guardCount; i++) {
            TownspersonMob guard = ModEntities.TOWNSPERSON
                    .get().create(level,
                            net.minecraft.world.entity
                                    .EntitySpawnReason.NATURAL);
            if (guard == null) continue;

            guard.setProfession(
                    Profession.GUARD);
            guard.setAssignedVillageName(
                    villageData.getVillageById(
                                    caravan.getOriginVillageId())
                            .map(v -> v.getName())
                            .orElse("Unknown"));

            // Spread guards around the caravan
            double angle = (i / (double) guardCount)
                    * Math.PI * 2;
            double offsetX = Math.cos(angle) * 3;
            double offsetZ = Math.sin(angle) * 3;

            guard.setPos(pos.getX() + offsetX + 0.5,
                    pos.getY(),
                    pos.getZ() + offsetZ + 0.5);
            String firstName = NpcNameRegistry.INSTANCE
                    .generateFirstName(guard.isMale(), random);
            String surname = NpcNameRegistry.INSTANCE
                    .generateSurname(random);
            guard.setNpcName(firstName + " " + surname);
            level.addFreshEntity(guard);
            caravan.addGuardEntityId(guard.getUUID());
            guard.setCaravanId(caravan.getCaravanId());
        }

        // Spawn chest minecart
        // Replace MinecartChest spawn with chested llama
        Llama llama =
                new Llama(
                        EntityType.LLAMA, level);

        llama.setPos(
                pos.getX() + 0.5,
                pos.getY(),
                pos.getZ() + 0.5);

// Give it a chest
        llama.setChest(true);

// Fill chest with goods
        List<ItemStack> goods = caravan.getGoods();
        var llamaInv = llama.getInventory();
        for (int i = 0; i < Math.min(goods.size(),
                llamaInv.getContainerSize() - 1); i++) {
            // Slot 0 is saddle/carpet, goods start at slot 1
            llamaInv.setItem(i + 1, goods.get(i).copy());
        }

// Give it a carpet so it looks like a trade llama
        llama.setBodyArmorItem(new ItemStack(
                net.minecraft.world.item.Items.BLUE_CARPET));

        llama.setTamed(true);
        llama.setCustomName(
                net.minecraft.network.chat.Component
                        .literal("Caravan Llama"));
        llama.setCustomNameVisible(true);

        level.addFreshEntity(llama);
        caravan.setCartEntityId(llama.getUUID());

        if (merchant != null
                && caravan.getCartEntityId() != null) {
            var llamaEntity = level.getEntity(
                    caravan.getCartEntityId());
            if (llamaEntity instanceof
                    Llama l) {
                // Leash llama to merchant so it follows automatically
                l.setLeashedTo(merchant, true);
            }
        }

        caravan.setSpawned(true);
    }

    private void despawnCaravan(Caravan caravan,
                                ServerLevel level) {
        // Sync progress from merchant position before despawn
        if (caravan.getMerchantEntityId() != null) {
            var merchant = level.getEntity(
                    caravan.getMerchantEntityId());
            if (merchant != null) merchant.discard();
        }

        caravan.getGuardEntityIds().stream()
                .map(level::getEntity)
                .filter(Objects::nonNull)
                .forEach(net.minecraft.world.entity.Entity
                        ::discard);

        if (caravan.getCartEntityId() != null) {
            var llama = level.getEntity(
                    caravan.getCartEntityId());
            if (llama != null && llama.isAlive()) {
                // Drop leash before discarding
                if (llama instanceof Llama l) {
                    l.dropLeash();
                }
                llama.discard();
            }
        }

        caravan.clearEntityIds();
    }

    // -------------------------------------------------------------------------
    // Spawned position update
    // -------------------------------------------------------------------------

    private void updateSpawnedPosition(Caravan caravan,
                                       TradeRoad road,
                                       ServerLevel level,
                                       long currentTick,
                                       double speedMult) {
        // Advance progress
        double increment = 0.0001 * speedMult;
        caravan.setProgress(Math.min(1.0,
                caravan.getProgress() + increment));

        // Get target position on road
        BlockPos target = caravan.getWorldPosition(
                road.getBlocks());
        if (target == null) return;

        // Move merchant toward target
        if (caravan.getMerchantEntityId() != null) {
            var merchant = level.getEntity(
                    caravan.getMerchantEntityId());
            if (merchant instanceof TownspersonMob npc) {
                npc.getNavigation().moveTo(
                        target.getX() + 0.5,
                        target.getY(),
                        target.getZ() + 0.5,
                        0.6);
            }
        }


        // Check if arrived
        if (caravan.getProgress() >= 1.0) {
            if (caravan.getState()
                    == Caravan.CaravanState.OUTBOUND) {
                caravan.setState(
                        Caravan.CaravanState.DELIVERING);
            } else if (caravan.getState()
                    == Caravan.CaravanState.RETURNING) {
                despawnCaravan(caravan, level);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Delivery
    // -------------------------------------------------------------------------

    private void handleDelivery(Caravan caravan,
                                ServerLevel level,
                                VillageSavedData villageData) {
        // Get trade efficiency from route and road
        double efficiency = villageData
                .getRouteBetween(caravan.getOriginVillageId(),
                        caravan.getDestVillageId())
                .flatMap(route -> villageData
                        .getRoadById(route.getRoadId())
                        .map(road -> route
                                .getTradeEfficiency(road)))
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
        for (TradeRoute route
                : villageData.getAllTradeRoutes()) {
            if (!route.isTradeAllowed()) continue;

            // Check if there's already a caravan on this route
            boolean hasActiveCaravan = caravans.values()
                    .stream()
                    .anyMatch(c -> c.getRouteId()
                            .equals(route.getRouteId())
                            && c.getState()
                            != Caravan.CaravanState.FAILED);
            if (hasActiveCaravan) continue;

            // Roll daily chance
            TradeRoad road = villageData
                    .getRoadById(route.getRoadId())
                    .orElse(null);
            if (road == null) continue;

            float chance = route.getDailyCaravanChance(road);
            if (RANDOM.nextFloat() > chance) continue;

            Village originVillage = villageData.getVillageById(route.getVillageA()).orElse(null);
            Village destVillage = villageData.getVillageById(route.getVillageB()).orElse(null);


            // Select goods
            List<ItemStack> goods =
                    CaravanGoodsSelector.selectGoods(
                            level,
                            originVillage,
                            destVillage,
                            villageData);

            // Scale guard count with cargo value
            int guardCount = calculateGuardCount(goods);

            Caravan caravan = Caravan.create(
                    route.getRouteId(),
                    route.getVillageA(),
                    route.getVillageB(),
                    goods,
                    guardCount,
                    currentTick);

            // In dispatchNewCaravans, after caravan is created
// Check if this is the first ever caravan on this route
            boolean isFirst = caravans.values().stream()
                    .filter(c -> c.getRouteId()
                            .equals(route.getRouteId()))
                    .count() == 1; // just added this one

            if (isFirst) {
                villageData.getKingdomForVillage(
                                route.getVillageA())
                        .ifPresent(k -> {
                            String originName = villageData
                                    .getVillageById(route.getVillageA())
                                    .map(v -> v.getName())
                                    .orElse("unknown");
                            String destName = villageData
                                    .getVillageById(route.getVillageB())
                                    .map(v -> v.getName())
                                    .orElse("unknown");
                            k.getHistory().recordEvent(
                                    HistoryTextGenerator.firstCaravan(
                                            originName, destName,
                                            level.getGameTime()),
                                    k.getName(),
                                    k.getRulerName(level));
                            villageData.setDirty();
                        });
            }

            caravans.put(caravan.getCaravanId(), caravan);
            route.setLastCaravanTick(currentTick);

            System.out.println("CaravanSavedData: dispatched "
                    + "caravan from "
                    + villageData.getVillageById(
                            route.getVillageA())
                    .map(v -> v.getName())
                    .orElse("unknown")
                    + " to "
                    + villageData.getVillageById(
                            route.getVillageB())
                    .map(v -> v.getName())
                    .orElse("unknown")
                    + " with " + goods.size()
                    + " goods types and "
                    + guardCount + " guards");
        }
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
        if (caravan.getMerchantEntityId() == null) return;

        var entity = level.getEntity(caravan.getMerchantEntityId());
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


}