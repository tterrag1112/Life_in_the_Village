// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/BoatCaravanSavedData.java
package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Travel.TravellingGroupEngine;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Owns all active {@link BoatCaravan}s. Parallels
 * {@link CaravanSavedData} but for sea routes.
 *
 * <h3>Why a separate SavedData</h3>
 * Land and sea caravans have different state machines (OUTBOUND →
 * DELIVERING → RETURNING for land, similar but with boat-specific
 * delivery for sea), different dispatch logic (trade route daily
 * chance vs sea route chance), and different merchant pools (village
 * market vs village dock). Forcing them into one class would mean
 * every method carries a mode check. Two classes with similar shapes
 * is clearer until there's a third mode that demands genuine unification.
 */
public class BoatCaravanSavedData extends SavedData {

    private static final int TICK_INTERVAL = 20;
    private static final Random RANDOM = new Random();

    /** Daily dispatch chance per sea route. Lower than land — sea trade is rarer. */
    private static final float DAILY_DISPATCH_CHANCE = 0.35f;

    public static final SavedDataType<BoatCaravanSavedData> TYPE =
            new SavedDataType<>(
                    "boat_caravans",
                    BoatCaravanSavedData::new,
                    RecordCodecBuilder.create(instance ->
                            instance.group(
                                    BoatCaravan.CODEC.listOf()
                                            .fieldOf("boatCaravans")
                                            .forGetter(d ->
                                                    new ArrayList<>(d.boatCaravans.values()))
                            ).apply(instance, BoatCaravanSavedData::fromCodec)));

    private static BoatCaravanSavedData fromCodec(List<BoatCaravan> caravanList) {
        BoatCaravanSavedData data = new BoatCaravanSavedData();
        caravanList.forEach(c -> data.boatCaravans.put(c.getCaravanId(), c));
        return data;
    }

    private final Map<UUID, BoatCaravan> boatCaravans = new HashMap<>();
    private long lastTickTime = 0L;

    public static BoatCaravanSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // =========================================================================
    // Tick
    // =========================================================================

    public void tick(ServerLevel level, VillageSavedData villageData) {
        long currentTick = level.getGameTime();
        if (currentTick - lastTickTime < TICK_INTERVAL) return;
        lastTickTime = currentTick;

        boolean dirty = false;
        List<UUID> toRemove = new ArrayList<>();

        for (BoatCaravan caravan : boatCaravans.values()) {
            if (caravan.getState() == BoatCaravan.BoatState.FAILED) {
                toRemove.add(caravan.getCaravanId());
                dirty = true;
                continue;
            }

            // Engine handles spawn/despawn/progress/completion
            if (TravellingGroupEngine.tick(caravan, level, villageData, currentTick)) {
                dirty = true;
            }

            // Delivery handling — separate from engine because it
            // involves treasury, goods transfer, history events
            if (caravan.getState() == BoatCaravan.BoatState.DELIVERING) {
                handleDelivery(caravan, level, villageData);
                dirty = true;
            }

            // Return completion — release merchant, mark for removal
            if (caravan.getState() == BoatCaravan.BoatState.RETURNING
                    && caravan.getProgress() >= 1.0) {
                releaseMerchant(caravan, level);
                toRemove.add(caravan.getCaravanId());
                dirty = true;
            }
        }

        toRemove.forEach(boatCaravans::remove);

        // Daily dispatch
        if (currentTick % 24000L == 0) {
            dispatchNewBoatCaravans(level, villageData, currentTick);
            dirty = true;
        }

        if (dirty) setDirty();
    }

    // =========================================================================
    // Delivery
    // =========================================================================

    private void handleDelivery(BoatCaravan caravan,
                                ServerLevel level,
                                VillageSavedData villageData) {
        // Sea route trade efficiency — simpler than land for now.
        // A future enhancement would factor in weather, piracy,
        // sea route quality etc.
        double efficiency = villageData.getSeaRouteById(caravan.getSeaRouteId())
                .map(r -> r.getQuality() / 100.0)
                .orElse(0.5);

        CaravanGoodsSelector.deliverGoods(
                level,
                caravan.getGoods(),
                caravan.getDestVillageId(),
                efficiency,
                villageData);

        // Switch to returning
        caravan.setProgress(0.0);
        caravan.setState(BoatCaravan.BoatState.RETURNING);

        System.out.println("BoatCaravanSavedData: caravan "
                + caravan.getCaravanId().toString().substring(0, 8)
                + " delivered goods, now returning");
    }

    // =========================================================================
    // Merchant release
    // =========================================================================

    private void releaseMerchant(BoatCaravan caravan, ServerLevel level) {
        UUID principalId = caravan.getRoster().getPrincipalId();
        if (principalId == null) return;
        var ent = level.getEntity(principalId);
        if (ent instanceof TownspersonMob mob) {
            mob.setCurrentExpeditionId(null);
        }
    }

    // =========================================================================
    // Dispatch
    // =========================================================================

    private void dispatchNewBoatCaravans(ServerLevel level,
                                         VillageSavedData villageData,
                                         long currentTick) {
        for (SeaRoute seaRoute : villageData.getAllSeaRoutes()) {
            if (!seaRoute.isActive()) continue;

            // Find the trade route(s) using this sea route
            List<TradeRoute> routesUsingThis = villageData.getAllTradeRoutes().stream()
                    .filter(r -> seaRoute.getRouteIds().contains(r.getRouteId()))
                    .collect(Collectors.toList());

            for (TradeRoute route : routesUsingThis) {
                if (!route.isTradeAllowed()) continue;

                // Already an active boat on this route?
                boolean hasActive = boatCaravans.values().stream()
                        .anyMatch(c -> c.getSeaRouteId().equals(seaRoute.getConnectionId())
                                && c.getOriginVillageId().equals(route.getVillageA())
                                && c.getState() != BoatCaravan.BoatState.FAILED);
                if (hasActive) continue;

                if (RANDOM.nextFloat() > DAILY_DISPATCH_CHANCE) continue;

                Village originVillage = villageData.getVillageById(route.getVillageA()).orElse(null);
                Village destVillage = villageData.getVillageById(route.getVillageB()).orElse(null);
                if (originVillage == null || destVillage == null) continue;

                // Reserve a merchant from the origin village
                UUID principalId = villageData.reserveIdleMerchant(
                        originVillage.getId(), level);
                if (principalId == null) {
                    System.out.println("BoatCaravanSavedData: no idle merchant in "
                            + originVillage.getName() + " — skipping dispatch");
                    continue;
                }

                // Find the origin dock
                UUID originDockId = findDockBuilding(originVillage, villageData);
                if (originDockId == null) {
                    // Shouldn't happen — sea routes should only exist between
                    // villages that have docks. Log and skip defensively.
                    System.out.println("BoatCaravanSavedData: origin village "
                            + originVillage.getName() + " has no dock — skipping dispatch");
                    continue;
                }

                List<ItemStack> goods = CaravanGoodsSelector.selectGoods(
                        level, originVillage, destVillage, villageData);

                BoatCaravan caravan = BoatCaravan.create(
                        seaRoute.getConnectionId(),
                        originVillage.getId(),
                        destVillage.getId(),
                        principalId,
                        originDockId,
                        goods,
                        currentTick);

                boatCaravans.put(caravan.getCaravanId(), caravan);

                // Mark the merchant with the now-known caravan ID
                var mob = level.getEntity(principalId);
                if (mob instanceof TownspersonMob m) {
                    m.setCurrentExpeditionId(caravan.getCaravanId());
                }

                System.out.println("BoatCaravanSavedData: dispatched boat caravan from "
                        + originVillage.getName() + " to " + destVillage.getName()
                        + " (principal " + principalId.toString().substring(0, 8)
                        + ", " + goods.size() + " goods)");
            }
        }
    }

    private UUID findDockBuilding(Village village, VillageSavedData data) {
        for (UUID buildingId : village.getBuildingIds()) {
            var b = data.getBuildingById(buildingId).orElse(null);
            if (b != null && b.getType() == BuildingType.DOCKS) return buildingId;
        }
        return null;
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public Optional<BoatCaravan> getBoatCaravan(UUID id) {
        return Optional.ofNullable(boatCaravans.get(id));
    }

    public List<BoatCaravan> getAllBoatCaravans() {
        return new ArrayList<>(boatCaravans.values());
    }

    public void addBoatCaravan(BoatCaravan caravan) {
        boatCaravans.put(caravan.getCaravanId(), caravan);
        setDirty();
    }

    public void removeBoatCaravan(UUID id) {
        if (boatCaravans.remove(id) != null) setDirty();
    }
}