package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CaravanSavedData.class);

    private static final int    TICK_INTERVAL = 20;
    private static final Random RANDOM        = new Random();

    /** Phase 4a — at most this many procurement caravans dispatched per
     *  daily roll, across all villages, so deficits don't spam caravans. */
    private static final int MAX_PROCUREMENT_DISPATCH_PER_DAY = 2;

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
                // Phase 4a — a PROCUREMENT caravan SELLS its bought goods at
                // home before the merchant is released.
                if (caravan.isProcurement()) {
                    handleProcurementSell(caravan, level, villageData);
                }
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
            dispatchProcurementCaravans(level, villageData, currentTick);
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
            LOGGER.warn("[Caravan] pooled merchant {} unavailable — spawning fresh merchant",
                    principalId);
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
        // Phase 4a — a PROCUREMENT caravan BUYS at the destination (the
        // surplus source) instead of depositing cargo. Settlement legs are
        // per-village (1c) via NpcEconomy; the export path below is
        // unchanged (its rewire is 4b).
        if (caravan.isProcurement()) {
            handleProcurementBuy(caravan, level, villageData);
            caravan.setProgress(0.0);
            caravan.setState(Caravan.CaravanState.RETURNING);
            return;
        }

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

        LOGGER.debug("[Caravan] {} delivered goods, now returning", shortId(caravan));
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

            LOGGER.debug("[Caravan] export dispatched: {} -> {} (principal {}, {} goods, {} guards)",
                    originVillage.getName(), destVillage.getName(),
                    principalId.toString().substring(0, 8), goods.size(), guardCount);
        }
    }

    /**
     * Phase 4a — need-driven procurement dispatch. For each village with a
     * deficit it can't fill locally (a non-empty {@link
     * CaravanGoodsSelector#buildShoppingList}), find a <b>reachable surplus
     * source</b> (an existing {@link TradeRoute} to a village whose
     * inventory can spare a needed item), reserve a home merchant with
     * funds, and dispatch an empty PROCUREMENT caravan to buy there and
     * sell back home. Capped per day so deficits don't spam caravans.
     *
     * <p>Route dependency: a procurement caravan only dispatches over an
     * existing land {@link TradeRoute} (same pathing the export caravan
     * uses) — never an unpathable ad-hoc journey. No reachable source ⇒
     * skip silently. Source selection allows intra- and inter-kingdom
     * partners (unlike {@code KingdomEconomyEngine.findExportPartner}'s
     * non-kingdom filter): a sister village's surplus is a valid import.
     */
    private void dispatchProcurementCaravans(ServerLevel level,
                                             VillageSavedData villageData,
                                             long currentTick) {
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        int dispatched = 0;

        for (Village home : villageData.getAllVillages()) {
            if (dispatched >= MAX_PROCUREMENT_DISPATCH_PER_DAY) break;

            List<ItemStack> shoppingList = CaravanGoodsSelector.buildShoppingList(home);
            if (shoppingList.isEmpty()) continue; // no unmet deficit

            // Find a reachable source: an existing land route to a village
            // that can spare one of the needed items.
            TradeRoute route = null;
            Village source = null;
            for (TradeRoute r : villageData.getRoutesForVillage(home.getId())) {
                if (!r.isTradeAllowed() || !r.hasGraphPath()) continue;
                if (r.getEdgeIds().isEmpty()) continue;
                RoadEdge firstEdge = graph.getEdge(r.getEdgeIds().get(0));
                if (firstEdge != null && firstEdge.getTier() == RoadEdge.EdgeTier.SEA) continue;

                // Path orientation: Caravan.getPath always runs
                // villageA→villageB (OUTBOUND) / reverse (RETURNING),
                // independent of origin/dest. The buy leg fires at the
                // OUTBOUND end (villageB) and the sell leg at the RETURNING
                // end (villageA), so the geography only matches when home is
                // villageA and the source is villageB. Routes where home is
                // villageB are skipped (a sister route with home as A, or no
                // procurement on that pair — acceptable for the scaffold).
                if (!r.getVillageA().equals(home.getId())) continue;
                UUID otherId = r.getVillageB();
                Village candidate = villageData.getVillageById(otherId).orElse(null);
                if (candidate == null) continue;

                // Already a procurement caravan in flight on this route? skip.
                boolean active = caravans.values().stream()
                        .anyMatch(c -> c.isProcurement()
                                && c.getRouteId().equals(r.getRouteId())
                                && c.getState() != Caravan.CaravanState.FAILED);
                if (active) continue;

                if (sourceCanSpareAny(level, candidate, shoppingList, villageData)) {
                    route = r;
                    source = candidate;
                    break;
                }
            }
            if (route == null || source == null) continue;

            // Reserve a home merchant (by-id; see reserveIdleMerchant).
            UUID principalId = villageData.reserveIdleMerchant(home.getId(), level);
            if (principalId == null) continue;

            // Funds gate: the merchant must be able to buy at least one item.
            var ent = level.getEntity(principalId);
            if (!(ent instanceof TownspersonMob merchant)
                    || merchant.getWallet().toBronze() <= 0) {
                continue;
            }

            UUID originMarketId = findFirstBuildingOfType(
                    home, BuildingType.MARKET, villageData);
            // Route origin must match the caravan origin for path direction:
            // build origin=home, dest=source regardless of route A/B order.
            Caravan caravan = Caravan.createProcurement(
                    route.getRouteId(),
                    home.getId(),
                    source.getId(),
                    principalId,
                    originMarketId,
                    shoppingList,
                    /* guardCount */ 1,
                    currentTick);

            assignCaravanRole(merchant, caravan, tterrag1112.life_in_the_village
                    .Npc.Roles.NpcRoleTypes.CARAVAN_PRINCIPAL);
            caravans.put(caravan.getCaravanId(), caravan);
            route.setLastCaravanTick(currentTick);
            dispatched++;

            LOGGER.info("[Caravan] procurement dispatched: {} -> {} (shopping {} item-type(s), merchant {})",
                    home.getName(), source.getName(), shoppingList.size(),
                    principalId.toString().substring(0, 8));
        }
    }

    /** True iff {@code source} can spare any shopping-list item above the
     *  procurement reserve buffer. */
    private static boolean sourceCanSpareAny(ServerLevel level, Village source,
                                             List<ItemStack> shoppingList,
                                             VillageSavedData data) {
        for (ItemStack want : shoppingList) {
            if (want.isEmpty()) continue;
            int have = countAcrossVillage(level, source, want.getItem(), data);
            if (have - SOURCE_RESERVE > 0) return true;
        }
        return false;
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

    // Phase 4b — isPlayerNearby deleted (zero callers; dead private).

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
     * Phase 4b — pays the export merchant for the surplus delivered to the
     * destination, settled through the same per-village / taxed path as 4a.
     *
     * <p>The export sells its cargo where it's needed (dear): the merchant
     * is paid the <b>destination's</b> per-village sell price
     * ({@code getDynamicSellPrice}) for the quantity that actually arrived
     * (cargo × efficiency, matching {@link CaravanGoodsSelector#deliverGoods}),
     * via {@code settlePurchase(buyer = none /*destination demand*&#47;,
     * endpoint = merchant, village = dest)} so the destination's 1b market
     * tax applies and the merchant is credited the proceeds. Replaces the
     * pre-1b flat base-price × 0.15 treasury-withdraw + raw mint.
     */
    private void payMerchantProfit(Caravan caravan, ServerLevel level,
                                   VillageSavedData villageData,
                                   double efficiency) {
        if (caravan.getRoster().getPrincipalId() == null) return;

        var entity = level.getEntity(caravan.getRoster().getPrincipalId());
        if (!(entity instanceof TownspersonMob merchant)) return;

        Village dest = villageData.getVillageById(caravan.getDestVillageId()).orElse(null);
        if (dest == null) return;

        long proceeds = 0;
        for (ItemStack stack : caravan.getGoods()) {
            if (stack.isEmpty()) continue;
            int deliveredQty = (int) (stack.getCount() * efficiency);
            if (deliveredQty <= 0) continue;
            long unit = tterrag1112.life_in_the_village.Village.Economy.Currency
                    .MarketPriceHelper.getDynamicSellPrice(level, dest, stack.getItem());
            proceeds += unit * deliveredQty;
        }
        if (proceeds <= 0) return;

        // Destination "buys" the surplus: tax credits the dest treasury, the
        // merchant is credited the sale proceeds. (Goods themselves are
        // deposited separately by deliverGoods.)
        tterrag1112.life_in_the_village.Village.Economy.Currency.NpcEconomy.settlePurchase(
                tterrag1112.life_in_the_village.Village.Economy.Currency
                        .SettlementParty.none(),
                tterrag1112.life_in_the_village.Village.Economy.Currency
                        .SettlementParty.npc(merchant),
                CurrencyValue.of(proceeds), dest, level, villageData);

        LOGGER.debug("[Caravan] export {} paid merchant {} {} bronze (dest price, taxed)",
                shortId(caravan), merchant.getNpcName(), proceeds);
    }

    // =========================================================================
    // Phase 4a — need-driven procurement legs (buy abroad, sell at home)
    // =========================================================================

    /**
     * Buy-at-source leg: at the destination (the surplus source), the
     * merchant buys each shopping-list item at the <b>source village's</b>
     * per-village price ({@code MarketPriceHelper.getDynamicSellPrice}, the
     * price a buyer pays), funded from the merchant's wallet, and loads it
     * into the caravan cargo. Quantity is clamped to what the source can
     * spare (its surplus inventory) and to the merchant's funds. Settles
     * through {@link tterrag1112.life_in_the_village.Village.Economy
     * .Currency.NpcEconomy#settlePurchase} so the source's 1b market tax
     * applies; the goods are pulled from the source stockpile.
     */
    private void handleProcurementBuy(Caravan caravan, ServerLevel level,
                                      VillageSavedData villageData) {
        UUID principalId = caravan.getRoster().getPrincipalId();
        var ent = principalId == null ? null : level.getEntity(principalId);
        if (!(ent instanceof TownspersonMob merchant)) return;

        Village source = villageData.getVillageById(caravan.getDestVillageId()).orElse(null);
        if (source == null) return;

        UUID sourceStockpile = findFirstBuildingOfType(
                source, BuildingType.STOCKPILE, villageData);

        for (ItemStack want : caravan.getShoppingList()) {
            if (want.isEmpty()) continue;
            net.minecraft.world.item.Item item = want.getItem();

            int spare = countAcrossVillage(level, source, item, villageData);
            if (spare <= 0) continue;
            // Leave the source a buffer so we don't drain it below its own use.
            int sparable = Math.max(0, spare - SOURCE_RESERVE);
            int qty = Math.min(want.getCount(), sparable);
            if (qty <= 0) continue;

            long unit = tterrag1112.life_in_the_village.Village.Economy.Currency
                    .MarketPriceHelper.getDynamicSellPrice(level, source, item);
            if (unit <= 0) continue;

            // Clamp to what the merchant can afford.
            long wallet = merchant.getWallet().toBronze();
            int affordable = (int) Math.min(qty, wallet / unit);
            if (affordable <= 0) continue;

            long cost = unit * affordable;
            boolean taken = takeFromVillage(level, source, item, affordable, villageData);
            if (!taken) continue;

            boolean paid = tterrag1112.life_in_the_village.Village.Economy.Currency
                    .NpcEconomy.settlePurchase(
                            tterrag1112.life_in_the_village.Village.Economy.Currency
                                    .SettlementParty.npc(merchant),
                            tterrag1112.life_in_the_village.Village.Economy.Currency
                                    .SettlementParty.none(), // source stockpile isn't a party; tax credits its treasury
                            CurrencyValue.of(cost), source, level, villageData);
            if (!paid) {
                // Refund the goods if the debit somehow failed.
                if (sourceStockpile != null) {
                    tterrag1112.life_in_the_village.Village.BuildingStorageAccess.storeItem(
                            level, villageData.getBuildingById(sourceStockpile).orElse(null),
                            new ItemStack(item, affordable));
                }
                continue;
            }
            caravan.getGoods().add(new ItemStack(item, affordable));
        }
        LOGGER.debug("[Caravan] procurement {} bought {} item-type(s) at {}",
                shortId(caravan), caravan.getGoods().size(), source.getName());
    }

    /**
     * Sell-at-home leg: on home arrival, the merchant sells the bought
     * goods at the <b>home village's</b> per-village price (higher, per
     * 1c), depositing them into the home stockpile so they fill the
     * deficit. Settles through {@code settlePurchase} with the home village
     * as buyer-context so the home 1b market tax applies and the merchant
     * is credited the proceeds — keeping the source→home spread.
     */
    private void handleProcurementSell(Caravan caravan, ServerLevel level,
                                       VillageSavedData villageData) {
        UUID principalId = caravan.getRoster().getPrincipalId();
        var ent = principalId == null ? null : level.getEntity(principalId);
        TownspersonMob merchant = ent instanceof TownspersonMob m ? m : null;

        Village home = villageData.getVillageById(caravan.getOriginVillageId()).orElse(null);
        if (home == null) return;
        UUID homeStockpile = findFirstBuildingOfType(home, BuildingType.STOCKPILE, villageData);

        long proceeds = 0;
        for (ItemStack stack : caravan.getGoods()) {
            if (stack.isEmpty()) continue;
            net.minecraft.world.item.Item item = stack.getItem();
            int qty = stack.getCount();
            long unit = tterrag1112.life_in_the_village.Village.Economy.Currency
                    .MarketPriceHelper.getDynamicSellPrice(level, home, item);
            proceeds += unit * qty;
            // Goods land in the home stockpile to fill the deficit.
            if (homeStockpile != null) {
                tterrag1112.life_in_the_village.Village.BuildingStorageAccess.storeItem(
                        level, villageData.getBuildingById(homeStockpile).orElse(null),
                        new ItemStack(item, qty));
            }
        }
        caravan.getGoods().clear();
        if (proceeds <= 0) return;

        // The home village "buys" the imports: tax credits the home
        // treasury, the merchant is credited the proceeds (the sale price).
        if (merchant != null) {
            tterrag1112.life_in_the_village.Village.Economy.Currency.NpcEconomy.settlePurchase(
                    tterrag1112.life_in_the_village.Village.Economy.Currency
                            .SettlementParty.none(),  // home demand mints the payment (village-funded import)
                    tterrag1112.life_in_the_village.Village.Economy.Currency
                            .SettlementParty.npc(merchant),
                    CurrencyValue.of(proceeds), home, level, villageData);
        }
        LOGGER.debug("[Caravan] procurement {} sold imports at home {} for {} bronze",
                shortId(caravan), home.getName(), proceeds);
    }

    private static final int SOURCE_RESERVE = 16;

    /** Total count of {@code item} across all of a village's building
     *  inventories (its sparable stock for procurement). */
    private static int countAcrossVillage(ServerLevel level, Village village,
                                          net.minecraft.world.item.Item item,
                                          VillageSavedData data) {
        int n = 0;
        for (UUID bid : village.getBuildingIds()) {
            var b = data.getBuildingById(bid).orElse(null);
            if (b == null) continue;
            n += tterrag1112.life_in_the_village.Village.BuildingStorageAccess
                    .countItem(level, b, item);
        }
        return n;
    }

    /** Takes {@code count} of {@code item} from the village's buildings
     *  (stockpile/markets first-come). Returns true iff the full count was
     *  removed. */
    private static boolean takeFromVillage(ServerLevel level, Village village,
                                           net.minecraft.world.item.Item item, int count,
                                           VillageSavedData data) {
        int remaining = count;
        for (UUID bid : village.getBuildingIds()) {
            if (remaining <= 0) break;
            var b = data.getBuildingById(bid).orElse(null);
            if (b == null) continue;
            int have = tterrag1112.life_in_the_village.Village.BuildingStorageAccess
                    .countItem(level, b, item);
            if (have <= 0) continue;
            int take = Math.min(remaining, have);
            if (tterrag1112.life_in_the_village.Village.BuildingStorageAccess
                    .takeItem(level, b, item, take)) {
                remaining -= take;
            }
        }
        return remaining <= 0;
    }

    private static String shortId(Caravan caravan) {
        return caravan.getCaravanId().toString().substring(0, 8);
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