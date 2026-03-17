package tterrag1112.life_in_the_village.Village.Buildings;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Needs.NeedCategory;
import tterrag1112.life_in_the_village.Village.Needs.NeedLevel;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;
import java.util.stream.Collectors;

public class VillageExpansionManager {

    private static final long EXPANSION_CHECK_INTERVAL
            = 3000L * 1; // check every 3 in-game days 24000 * 3
    private static final int  WEALTH_BUFFER_MULTIPLIER = 3;

    // -------------------------------------------------------------------------
    // Main tick — call from WorldEvents
    // -------------------------------------------------------------------------

    public static void tick(ServerLevel level,
                            VillageSavedData data,
                            long currentTick) {
        if (currentTick % EXPANSION_CHECK_INTERVAL != 0)
            return;

        for (Village village : data.getAllVillages()) {
            // Skip if already has a pending expansion
            if (data.getPendingExpansionForVillage(
                    village.getId()).isPresent()) continue;

            evaluateExpansion(village, level, data,
                    currentTick);
        }
    }

    // -------------------------------------------------------------------------
    // Expansion evaluation
    // -------------------------------------------------------------------------

    private static void evaluateExpansion(
            Village village, ServerLevel level,
            VillageSavedData data, long currentTick) {

        List<Building> buildings = village.getBuildingIds()
                .stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        VillageSizeTier tier = VillageSizeTier
                .fromBuildingCount(buildings.size());
        int population = countPopulation(buildings, level,
                data);
        CurrencyValue wealth = getVillageWealth(
                buildings, level, data, village);

        // Priority 1 — upgrade existing buildings that
        // are below max level and have high activity
        Building upgradeTarget = findUpgradeCandidate(
                buildings, wealth);
        if (upgradeTarget != null) {
            requestUpgrade(upgradeTarget, village,
                    data, currentTick);
            return;
        }

        // Priority 2 — build new buildings that are
        // unlocked and needed
        BuildingType newType = findNewBuildingType(
                buildings, tier, population, wealth, data,
                village);
        if (newType != null) {
            requestNewBuilding(newType, village,
                    data, currentTick);
        }
    }

    // -------------------------------------------------------------------------
    // Upgrade candidate selection
    // -------------------------------------------------------------------------

    private static Building findUpgradeCandidate(
            List<Building> buildings,
            CurrencyValue wealth) {
        // Priority order for upgrades
        List<BuildingType> upgradePriority = List.of(
                BuildingType.TOWN_HALL,
                BuildingType.STOCKPILE,
                BuildingType.FARMHOUSE,
                BuildingType.BLACKSMITH,
                BuildingType.MARKET,
                BuildingType.GUILD_HALL,
                BuildingType.BARRACKS
        );

        for (BuildingType type : upgradePriority) {
            Optional<Building> candidate = buildings.stream()
                    .filter(b -> b.getType() == type)
                    .filter(BuildingRegistry::canUpgrade)
                    .findFirst();

            if (candidate.isEmpty()) continue;

            Building b = candidate.get();
            BuildingRegistry.get(type).ifPresent(def -> {
                // Check can afford upgrade cost
            });

            // Check wealth is sufficient with buffer
            int cost = BuildingRegistry.get(type)
                    .map(d -> d.constructionCost())
                    .orElse(0);
            CurrencyValue needed = CurrencyValue.ofSilver(
                    cost * WEALTH_BUFFER_MULTIPLIER);

            if (wealth.isAffordable(needed)) {
                return b;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // New building selection
    // -------------------------------------------------------------------------

    private static BuildingType findNewBuildingType(
            List<Building> buildings,
            VillageSizeTier tier,
            int population,
            CurrencyValue wealth,
            VillageSavedData data,
            Village village) {

        // Score each unlocked building type by need
        Map<BuildingType, Integer> scores
                = new LinkedHashMap<>();

        for (var entry : BuildingRegistry.getAll()
                .entrySet()) {
            BuildingType type = entry.getKey();
            BuildingRegistry.BuildingDef def = entry.getValue();

            // Check if unlocked
            if (!BuildingRegistry.isUnlocked(type,
                    buildings, tier)) continue;

            // Check population requirement
            if (population < def.populationRequirement())
                continue;

            // Check can afford
            CurrencyValue cost = CurrencyValue.ofSilver(
                    def.constructionCost()
                            * WEALTH_BUFFER_MULTIPLIER);
            if (!wealth.isAffordable(cost)) continue;

            // Score based on need
            int score = scoreBuilding(type, buildings,
                    population, tier, village);
            if (score > 0) {
                scores.put(type, score);
            }
        }

        if (scores.isEmpty()) return null;

        // Return highest scored type
        return scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static int scoreBuilding(
            BuildingType type,
            List<Building> existing,
            int population,
            VillageSizeTier tier,
            Village village) {

        Set<BuildingType> existingTypes =
                existing.stream()
                        .map(Building::getType)
                        .collect(Collectors.toSet());

        // Check village needs if available
        Map<NeedCategory, NeedLevel> needs =
                village.getNeeds().entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> e.getValue().getLevel()));

        NeedLevel foodLevel = needs.getOrDefault(
                NeedCategory.FOOD, NeedLevel.SATISFIED);
        NeedLevel materialLevel = needs.getOrDefault(
                NeedCategory.BUILDING_MATERIALS,
                NeedLevel.SATISFIED);

        return switch (type) {
            case HOUSE -> population > existing.stream()
                    .filter(b -> b.getType()
                            == BuildingType.HOUSE)
                    .count() * 2 ? 90 : 10;

            // Boost farmhouse if food is low
            case FARMHOUSE -> (foodLevel == NeedLevel.CRITICAL
                    ? 100
                    : foodLevel == NeedLevel.LOW ? 85 : 40);

            case STOCKPILE -> !existingTypes.contains(
                    BuildingType.STOCKPILE) ? 95 : 0;

            // Boost blacksmith if materials are low
            case BLACKSMITH -> !existingTypes.contains(
                    BuildingType.BLACKSMITH)
                    ? (materialLevel == NeedLevel.CRITICAL
                    ? 95 : 80)
                    : 0;

            case MARKET -> !existingTypes.contains(
                    BuildingType.MARKET) ? 75 : 0;
            case INN      -> population > 8  ? 60 : 20;
            case BAKERY   -> population > 6  ? 55 : 15;
            case STABLE   -> population > 8  ? 50 : 10;
            case MILLER   -> existingTypes.contains(
                    BuildingType.FARMHOUSE) ? 50 : 5;
            case CARPENTRY -> existingTypes.contains(
                    BuildingType.STOCKPILE) ? 45 : 5;
            case WOODCUTTER -> population > 5 ? 40 : 10;
            case BARRACKS  -> population > 10 ? 70 : 0;
            case TEMPLE    -> population > 12 ? 55 : 0;
            case LIBRARY   -> population > 14 ? 50 : 0;
            case WATCHTOWER -> existingTypes.contains(
                    BuildingType.BARRACKS) ? 45 : 0;
            case PRISON    -> population > 15 ? 40 : 0;
            case GUILD_HALL -> population > 10 ? 65 : 0;
            case NOBLE_MANOR -> population > 20 ? 50 : 0;
            case CASTLE      -> population > 25 ? 60 : 0;
            case DOCKS       -> population > 18 ? 45 : 0;
            case ARMORER     -> population > 20 ? 50 : 0;
            case TOOLSMITH   -> population > 18 ? 45 : 0;
            default -> 20;
        };
    }

    // -------------------------------------------------------------------------
    // Request creation
    // -------------------------------------------------------------------------

    private static void requestUpgrade(Building building,
                                       Village village,
                                       VillageSavedData data,
                                       long currentTick) {
        // Upgrade uses existing ExpansionRequest with
        // the same building type — builder detects it's
        // an upgrade by checking if building already exists
        ExpansionRequest request = ExpansionRequest.create(
                village.getId(),
                building.getType(),
                currentTick);
        data.addExpansionRequest(request);

        System.out.println("VillageExpansionManager: "
                + "queued upgrade for "
                + building.getType() + " in "
                + village.getName());
    }

    private static void requestNewBuilding(
            BuildingType type,
            Village village,
            VillageSavedData data,
            long currentTick) {
        ExpansionRequest request = ExpansionRequest.create(
                village.getId(), type, currentTick);
        data.addExpansionRequest(request);

        System.out.println("VillageExpansionManager: "
                + "queued new building "
                + type + " for " + village.getName());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int countPopulation(List<Building> buildings,
                                       ServerLevel level,
                                       VillageSavedData data) {
        Village village = buildings.stream()
                .findFirst()
                .flatMap(b -> data.getVillageById(
                        b.getId()))
                .orElse(null);

        if (village == null) return 0;

        return (int) level.getEntitiesOfClass(
                tterrag1112.life_in_the_village.Entities
                        .custom.TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> b.inflate(32))
                        .orElse(new net.minecraft.world.phys
                                .AABB(0, 0, 0, 0, 0, 0)),
                npc -> npc.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        ).size();
    }

    public static CurrencyValue getVillageWealth(
            List<Building> buildings,
            ServerLevel level,
            VillageSavedData data,
            Village village) {

        java.util.concurrent.atomic.AtomicLong totalCopper =
                new java.util.concurrent.atomic.AtomicLong(0);

        // Sum NPC personal wealth
        level.getEntitiesOfClass(
                tterrag1112.life_in_the_village.Entities
                        .custom.TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> b.inflate(32))
                        .orElse(new net.minecraft.world.phys
                                .AABB(0, 0, 0, 0, 0, 0)),
                npc -> npc.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        ).forEach(npc -> totalCopper.addAndGet(
                npc.getWealth().toBronze()));

        // Sum stockpile coins
        buildings.stream()
                .filter(b -> b.getType()
                        == BuildingType.STOCKPILE)
                .forEach(stockpile -> {
                    for (var inv : BuildingStorageAccess
                            .findInventories(level, stockpile)) {
                        for (int i = 0;
                             i < inv.getContainerSize(); i++) {
                            var stack = inv.getItem(i);
                            totalCopper.addAndGet(
                                    CurrencyValue.valuePerCoin(
                                            stack.getItem())
                                            * stack.getCount());
                        }
                    }
                });

        return CurrencyValue.of(totalCopper.get());
    }
}