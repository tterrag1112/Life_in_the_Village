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
        // Track C3.3 — sea routes migrated to SEA-tier RoadEdges in
        // WorldRoadGraph; read maintenance as the quality scalar.
        var roadGraph = tterrag1112.life_in_the_village.Networking
                .WorldRoadSavedData.get(level).getGraph();
        var edge = roadGraph.getEdge(caravan.getEdgeId());
        double efficiency = edge != null
                ? edge.getMaintenance() / 100.0
                : 0.5;

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
            mob.getRoles().removeRole(
                    tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes.CARAVAN_PRINCIPAL);
        }
    }

    // =========================================================================
    // Dispatch
    // =========================================================================

    /**
     * Track C3.3 — boat-caravan dispatch is now driven by the unified
     * world graph. The dispatcher iterates {@link TradeRoute}s whose
     * first edge is SEA-tier, treating land-vs-sea as an inspect-the-
     * first-edge fork. Land caravan dispatch in {@code CaravanSavedData}
     * does the inverse: skip routes whose first edge is SEA.
     */
    private void dispatchNewBoatCaravans(ServerLevel level,
                                         VillageSavedData villageData,
                                         long currentTick) {
        var graph = tterrag1112.life_in_the_village.Networking
                .WorldRoadSavedData.get(level).getGraph();

        for (TradeRoute route : villageData.getAllTradeRoutes()) {
            if (!route.isTradeAllowed()) continue;

            List<UUID> edgeIds = route.getEdgeIds();
            if (edgeIds.isEmpty()) continue;

            tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge firstEdge =
                    graph.getEdge(edgeIds.get(0));
            if (firstEdge == null
                    || firstEdge.getTier() != tterrag1112.life_in_the_village
                            .Village.Roads.Graph.RoadEdge.EdgeTier.SEA) {
                continue; // land route — handled by CaravanSavedData
            }
            UUID seaEdgeId = firstEdge.getEdgeId();

            // Already an active boat on this route?
            boolean hasActive = boatCaravans.values().stream()
                    .anyMatch(c -> c.getEdgeId().equals(seaEdgeId)
                            && c.getOriginVillageId().equals(route.getVillageA())
                            && c.getState() != BoatCaravan.BoatState.FAILED);
            if (hasActive) continue;

            if (RANDOM.nextFloat() > DAILY_DISPATCH_CHANCE) continue;

            Village originVillage = villageData.getVillageById(route.getVillageA()).orElse(null);
            Village destVillage = villageData.getVillageById(route.getVillageB()).orElse(null);
            if (originVillage == null || destVillage == null) continue;

            UUID principalId = villageData.reserveIdleMerchant(
                    originVillage.getId(), level);
            if (principalId == null) {
                System.out.println("BoatCaravanSavedData: no idle merchant in "
                        + originVillage.getName() + " — skipping dispatch");
                continue;
            }

            UUID originDockId = findDockBuilding(originVillage, villageData);
            if (originDockId == null) {
                System.out.println("BoatCaravanSavedData: origin village "
                        + originVillage.getName() + " has no dock — skipping dispatch");
                continue;
            }

            List<ItemStack> goods = CaravanGoodsSelector.selectGoods(
                    level, originVillage, destVillage, villageData);

            BoatCaravan caravan = BoatCaravan.create(
                    seaEdgeId,
                    originVillage.getId(),
                    destVillage.getId(),
                    principalId,
                    originDockId,
                    goods,
                    currentTick);

            boatCaravans.put(caravan.getCaravanId(), caravan);

            var mob = level.getEntity(principalId);
            if (mob instanceof TownspersonMob m) {
                m.getRoles().assignRole(
                        tterrag1112.life_in_the_village.Npc.Roles.RoleAssignment.conditional(
                                tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes.CARAVAN_PRINCIPAL,
                                java.util.Map.of(
                                        tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes.P_CARAVAN_ID,
                                        caravan.getCaravanId().toString())));
            }

            System.out.println("BoatCaravanSavedData: dispatched boat caravan from "
                    + originVillage.getName() + " to " + destVillage.getName()
                    + " (principal " + principalId.toString().substring(0, 8)
                    + ", " + goods.size() + " goods, edge="
                    + seaEdgeId.toString().substring(0, 8) + ")");
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