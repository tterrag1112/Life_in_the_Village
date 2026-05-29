package tterrag1112.life_in_the_village.Village.Economy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.DynamicPriceCalculator;
import tterrag1112.life_in_the_village.Village.Economy.Currency.EconomyBalance;
import tterrag1112.life_in_the_village.Village.Economy.Currency.EconomyBalanceRegistry;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceRegistry;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeListing;
import tterrag1112.life_in_the_village.Village.Needs.NeedCategory;
import tterrag1112.life_in_the_village.Village.Needs.NeedLevel;
import tterrag1112.life_in_the_village.Village.Needs.VillageNeedsCalculator;
import tterrag1112.life_in_the_village.Village.Village;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

public class VillageEconomy {

    // =========================================================================
    // LISTING STORE
    // =========================================================================

    // Per-village listing map. In-memory — repopulated each session as
    // NPCs post their goods. Indexed by villageId -> sellerId -> item -> listing
    // so updates and removals are O(1) without scanning the full list.
    private static final Map<UUID,
            Map<UUID, Map<Item, TradeListing>>> LISTINGS = new HashMap<>();

    // How long a listing lives without being refreshed (ticks).
    // Sellers re-post every 1200 ticks; this gives 4× headroom so listings
    // survive brief unloads.
    private static final long LISTING_TTL = 4800L;

    // Default search radius for findCheapestSeller
    private static final double DEFAULT_SEARCH_RADIUS = 256.0;

    // Price multipliers by seller role are externalized to the economy
    // balance config (Phase 1d); read live via EconomyBalanceRegistry
    // .balance().markups(). Defaults: producer 0.85, merchant 1.20,
    // stockpile 1.00, company 1.10.

    // =========================================================================
    // POSTING LISTINGS — NPC overload
    // =========================================================================

    /**
     * Standard posting path for NPC sellers.
     * Price is computed automatically from base price, profession markup,
     * supply level, and village needs.
     */
    public static void postListing(ServerLevel level,
                                   UUID villageId,
                                   TownspersonMob seller,
                                   Item item,
                                   int quantity,
                                   long tick) {
        if (quantity <= 0) return;

        VillageSavedData vdata = VillageSavedData.get(level);
        Village village = vdata.getVillageById(villageId).orElse(null);

        Building building = seller.getAssignedBuildingId()
                .flatMap(id -> vdata.getBuildingById(id))
                .orElse(null);
        if (building == null) return;

        long basePrice = getBasePrice(item);
        long price = village != null
                ? DynamicPriceCalculator.getSellPrice(
                level, village, vdata, item, basePrice)
                : basePrice;

        // Apply profession markup on top of dynamic price
        price = Math.max(1, Math.round(price * getMarkup(seller.getProfession())));

        // Discount for oversupply in physical stockpile
        price = applySupplyDiscount(level, villageId, item, price, vdata);

        TradeListing.SellerType sellerType = getSellerType(seller.getProfession());

        postListingInternal(villageId, seller.getUUID(), building.getId(),
                item, quantity, price, sellerType, tick);
    }

    // =========================================================================
    // POSTING LISTINGS — business / price-override overload
    // =========================================================================

    /**
     * Posting path for business SELLER workers.
     * Uses the business's price override if set, otherwise falls back to
     * the auto-computed price.
     */
    public static void postCompanyListing(ServerLevel level,
                                          UUID villageId,
                                          TownspersonMob seller,
                                          Business business,
                                          Item item,
                                          int quantity,
                                          long tick) {
        if (quantity <= 0) return;

        VillageSavedData vdata = VillageSavedData.get(level);

        Building building = seller.getAssignedBuildingId()
                .flatMap(id -> vdata.getBuildingById(id))
                .orElse(null);
        if (building == null) return;

        // Use business price override if available, else auto-compute
        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(item).toString();
        long price = business.getPriceOverride(itemId)
                .orElseGet(() -> {
                    long base = getBasePrice(item);
                    Village village = vdata.getVillageById(villageId).orElse(null);
                    long dynamic = village != null
                            ? DynamicPriceCalculator.getSellPrice(
                            level, village, vdata, item, base)
                            : base;
                    return Math.max(1, Math.round(dynamic
                            * EconomyBalanceRegistry.balance().markups().company()));
                });

        postListingInternal(villageId, seller.getUUID(), building.getId(),
                item, quantity, price, TradeListing.SellerType.COMPANY, tick);
    }

    /**
     * Direct listing post — for any caller that has already computed a price.
     * Used by the stockpile keeper acting as a fallback market.
     */
    public static void postListingDirect(UUID villageId,
                                         UUID sellerId,
                                         UUID buildingId,
                                         Item item,
                                         int quantity,
                                         long price,
                                         TradeListing.SellerType sellerType,
                                         long tick) {
        if (quantity <= 0 || price <= 0) return;
        postListingInternal(villageId, sellerId, buildingId,
                item, quantity, price, sellerType, tick);
    }

    // =========================================================================
    // INTERNAL POSTING
    // =========================================================================

    private static void postListingInternal(UUID villageId,
                                            UUID sellerId,
                                            UUID buildingId,
                                            Item item,
                                            int quantity,
                                            long price,
                                            TradeListing.SellerType sellerType,
                                            long tick) {
        LISTINGS
                .computeIfAbsent(villageId, k -> new HashMap<>())
                .computeIfAbsent(sellerId,  k -> new HashMap<>())
                .put(item, new TradeListing(
                        sellerId, buildingId,
                        item, quantity, price,
                        sellerType, tick));
    }

    // =========================================================================
    // REMOVING LISTINGS
    // =========================================================================

    /** Remove a specific seller's listing for one item. */
    public static void removeListing(UUID villageId, UUID sellerId, Item item) {
        Map<UUID, Map<Item, TradeListing>> bySeller = LISTINGS.get(villageId);
        if (bySeller == null) return;
        Map<Item, TradeListing> byItem = bySeller.get(sellerId);
        if (byItem == null) return;
        byItem.remove(item);
        if (byItem.isEmpty()) bySeller.remove(sellerId);
    }

    /** Remove all listings for a seller (e.g. when they are fired or die). */
    public static void removeAllListingsFor(UUID villageId, UUID sellerId) {
        Map<UUID, Map<Item, TradeListing>> bySeller = LISTINGS.get(villageId);
        if (bySeller != null) bySeller.remove(sellerId);
    }

    /** Wipe all listings for a village (e.g. on village removal). */
    public static void clearListings(UUID villageId) {
        LISTINGS.remove(villageId);
    }

    // =========================================================================
    // QUERYING LISTINGS
    // =========================================================================

    /**
     * All active listings for a given item in a village.
     * Purges stale entries before returning — safe to iterate but not to mutate.
     */
    public static List<TradeListing> getListingsForItem(
            ServerLevel level, UUID villageId, Item item, long tick) {
        purgeStaleListings(villageId, tick);
        Map<UUID, Map<Item, TradeListing>> bySeller =
                LISTINGS.getOrDefault(villageId, Map.of());
        return bySeller.values().stream()
                .map(byItem -> byItem.get(item))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * All active listings in a village across all items.
     */
    public static List<TradeListing> getAllListings(UUID villageId, long tick) {
        purgeStaleListings(villageId, tick);
        Map<UUID, Map<Item, TradeListing>> bySeller =
                LISTINGS.getOrDefault(villageId, Map.of());
        return bySeller.values().stream()
                .flatMap(byItem -> byItem.values().stream())
                .collect(Collectors.toList());
    }

    /**
     * All items currently being sold anywhere in a village.
     */
    public static Set<Item> getAvailableItems(UUID villageId, long tick) {
        purgeStaleListings(villageId, tick);
        Map<UUID, Map<Item, TradeListing>> bySeller =
                LISTINGS.getOrDefault(villageId, Map.of());
        return bySeller.values().stream()
                .flatMap(byItem -> byItem.keySet().stream())
                .collect(Collectors.toSet());
    }

    // =========================================================================
    // FINDING SELLERS
    // =========================================================================

    /**
     * Finds the cheapest available seller of an item within DEFAULT_SEARCH_RADIUS.
     * Falls back to the village stockpile if no listings are found.
     */
    public static Optional<TradeListResult> findCheapestSeller(
            ServerLevel level, UUID villageId, Item item,
            double buyerX, double buyerZ, long currentTick) {
        return findCheapestSeller(level, villageId, item,
                buyerX, buyerZ, currentTick, DEFAULT_SEARCH_RADIUS);
    }

    /**
     * Finds the cheapest available seller within a custom radius.
     */
    public static Optional<TradeListResult> findCheapestSeller(
            ServerLevel level, UUID villageId, Item item,
            double buyerX, double buyerZ, long currentTick,
            double searchRadius) {

        List<TradeListing> active = getListingsForItem(
                level, villageId, item, currentTick);

        VillageSavedData vdata = VillageSavedData.get(level);

        List<TradeListResult> candidates = active.stream()
                .filter(l -> {
                    Building b = vdata.getBuildingById(l.getSellerBuildingId())
                            .orElse(null);
                    if (b == null) return false;
                    return distanceTo(b, buyerX, buyerZ) <= searchRadius;
                })
                .map(l -> {
                    TownspersonMob seller = findEntity(level, l.getSellerEntityId());
                    return seller != null ? new TradeListResult(l, seller) : null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // Stockpile fallback — always available but at base price
        addStockpileFallback(level, vdata, villageId, item,
                currentTick, candidates);

        return candidates.stream()
                .min(Comparator.comparingLong(r ->
                        r.listing().getPricePerItem()));
    }

    /**
     * Finds ALL sellers of an item sorted cheapest-first,
     * useful for bulk purchasing decisions.
     */
    public static List<TradeListResult> findAllSellers(
            ServerLevel level, UUID villageId, Item item, long currentTick) {

        List<TradeListing> active = getListingsForItem(
                level, villageId, item, currentTick);
        VillageSavedData vdata = VillageSavedData.get(level);

        List<TradeListResult> results = active.stream()
                .map(l -> {
                    TownspersonMob seller = findEntity(level, l.getSellerEntityId());
                    return seller != null ? new TradeListResult(l, seller) : null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        addStockpileFallback(level, vdata, villageId, item,
                currentTick, results);

        results.sort(Comparator.comparingLong(r ->
                r.listing().getPricePerItem()));
        return results;
    }

    // =========================================================================
    // STOCKPILE TARGETS
    // =========================================================================

    /**
     * Compute what the stockpile should be stocking based on current village
     * needs. Delegates to VillageNeedsCalculator for the need state, then
     * translates need levels into concrete target quantities.
     */
    public static Map<Item, Integer> computeStockpileTargets(
            ServerLevel level, Village village, VillageSavedData data) {

        Map<Item, Integer> targets = new LinkedHashMap<>();
        int population = countPopulation(level, village, data);

        // Get current needs to scale targets
        Map<tterrag1112.life_in_the_village.Village.Needs.NeedCategory,
                tterrag1112.life_in_the_village.Village.Needs.VillageNeed> needs =
                VillageNeedsCalculator.compute(level, village, data);

        NeedLevel foodNeed = needLevel(needs, NeedCategory.FOOD);
        NeedLevel materialNeed = needLevel(needs, NeedCategory.BUILDING_MATERIALS);
        NeedLevel seedNeed = needLevel(needs, NeedCategory.SEEDS);

        // --- Food — scale with population and urgency ---
        int foodMultiplier = switch (foodNeed) {
            case CRITICAL  -> 16;
            case LOW       -> 12;
            case SATISFIED -> 8;
            case SURPLUS   -> 4;
        };
        targets.put(net.minecraft.world.item.Items.WHEAT,
                population * foodMultiplier);
        targets.put(net.minecraft.world.item.Items.BREAD,
                population * (foodMultiplier / 2));
        targets.put(net.minecraft.world.item.Items.CARROT,
                population * (foodMultiplier / 2));
        targets.put(net.minecraft.world.item.Items.POTATO,
                population * (foodMultiplier / 2));

        // --- Building materials — scale with urgency ---
        int materialMultiplier = switch (materialNeed) {
            case CRITICAL  -> 4;
            case LOW       -> 3;
            case SATISFIED -> 2;
            case SURPLUS   -> 1;
        };
        targets.put(net.minecraft.world.item.Items.OAK_LOG,
                64 * materialMultiplier);
        targets.put(net.minecraft.world.item.Items.COBBLESTONE,
                128 * materialMultiplier);
        targets.put(net.minecraft.world.item.Items.OAK_PLANKS,
                64 * materialMultiplier);
        targets.put(net.minecraft.world.item.Items.STONE_BRICKS,
                32 * materialMultiplier);

        // --- Seeds — always keep a buffer ---
        int seedBuffer = seedNeed == NeedLevel.CRITICAL ? 128 :
                seedNeed == NeedLevel.LOW      ? 96  : 64;
        targets.put(net.minecraft.world.item.Items.WHEAT_SEEDS, seedBuffer);
        targets.put(net.minecraft.world.item.Items.CARROT,
                Math.max(targets.getOrDefault(
                                net.minecraft.world.item.Items.CARROT, 0),
                        seedBuffer / 2));
        targets.put(net.minecraft.world.item.Items.POTATO,
                Math.max(targets.getOrDefault(
                                net.minecraft.world.item.Items.POTATO, 0),
                        seedBuffer / 2));

        // --- Tools — one per worker of each type, plus one spare ---
        int miners = countProfession(level, village, data, Profession.MINER);
        int guards = countProfession(level, village, data, Profession.GUARD);
        int farmers = countProfession(level, village, data, Profession.FARMER)
                /* Phase 6.3.3.f — FARMHAND folded into FARMER tier APPRENTICE */;

        if (miners > 0)
            targets.put(net.minecraft.world.item.Items.IRON_PICKAXE, miners + 1);
        if (guards > 0)
            targets.put(net.minecraft.world.item.Items.IRON_SWORD, guards + 1);
        if (farmers > 0)
            targets.put(net.minecraft.world.item.Items.IRON_HOE, farmers + 1);

        return targets;
    }

    // =========================================================================
    // PRICE UTILITIES
    // =========================================================================

    public static long getBasePrice(Item item) {
        return MarketPriceHelper.getBaseSellPrice(item);
    }

    public static long getDynamicPrice(ServerLevel level, UUID villageId, Item item) {
        return MarketPriceHelper.getDynamicSellPrice(level, villageId, item);
    }

    /**
     * Total quantity of an item available across all listings in a village.
     */
    public static int getTotalListedQuantity(UUID villageId, Item item, long tick) {
        purgeStaleListings(villageId, tick);
        Map<UUID, Map<Item, TradeListing>> bySeller =
                LISTINGS.getOrDefault(villageId, Map.of());
        return bySeller.values().stream()
                .map(byItem -> byItem.get(item))
                .filter(Objects::nonNull)
                .mapToInt(TradeListing::getQuantity)
                .sum();
    }

    // =========================================================================
    // STALE LISTING CLEANUP
    // =========================================================================

    public static void purgeStaleListings(UUID villageId, long currentTick) {
        Map<UUID, Map<Item, TradeListing>> bySeller = LISTINGS.get(villageId);
        if (bySeller == null) return;

        bySeller.values().forEach(byItem ->
                byItem.entrySet().removeIf(e ->
                        currentTick - e.getValue().getCreatedTick() > LISTING_TTL));

        // Remove seller entries that are now empty
        bySeller.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    private static void addStockpileFallback(ServerLevel level,
                                             VillageSavedData vdata,
                                             UUID villageId,
                                             Item item,
                                             long currentTick,
                                             List<TradeListResult> results) {
        vdata.getVillageById(villageId).ifPresent(village ->
                village.getBuildingIds().stream()
                        .map(vdata::getBuildingById)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(b -> b.getType() == BuildingType.STOCKPILE)
                        .forEach(stockpile -> {
                            int count = BuildingStorageAccess.countItem(
                                    level, stockpile, item);
                            if (count <= 0) return;

                            level.getEntitiesOfClass(
                                            TownspersonMob.class,
                                            stockpile.getShape().toAABB().inflate(16),
                                            mob -> mob.getProfession()
                                                    == Profession.STOCKPILE_KEEPER)
                                    .stream().findFirst()
                                    .ifPresent(keeper -> {
                                        long price = Math.max(1,
                                                getDynamicPrice(level,
                                                        villageId, item));
                                        results.add(new TradeListResult(
                                                new TradeListing(
                                                        keeper.getUUID(),
                                                        stockpile.getId(),
                                                        item, count, price,
                                                        TradeListing.SellerType.STOCKPILE,
                                                        currentTick),
                                                keeper));
                                    });
                        }));
    }

    private static long applySupplyDiscount(ServerLevel level,
                                            UUID villageId,
                                            Item item,
                                            long price,
                                            VillageSavedData vdata) {
        // Count physical stock in all stockpiles
        int physicalStock = vdata.getVillageById(villageId)
                .map(v -> v.getBuildingIds().stream()
                        .map(vdata::getBuildingById)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(b -> b.getType() == BuildingType.STOCKPILE)
                        .mapToInt(s -> BuildingStorageAccess.countItem(
                                level, s, item))
                        .sum())
                .orElse(0);

        if (physicalStock > 128) return Math.max(1, Math.round(price * 0.75));
        if (physicalStock > 64)  return Math.max(1, Math.round(price * 0.90));
        return price;
    }

    private static double getMarkup(Profession profession) {
        EconomyBalance.Markups markups = EconomyBalanceRegistry.balance().markups();
        return switch (profession) {
            case MERCHANT         -> markups.merchant();
            case STOCKPILE_KEEPER -> markups.stockpile();
            case COMPANY_WORKER   -> markups.company();
            default               -> markups.producer();
        };
    }

    private static TradeListing.SellerType getSellerType(Profession profession) {
        return switch (profession) {
            case MERCHANT         -> TradeListing.SellerType.MERCHANT;
            case STOCKPILE_KEEPER -> TradeListing.SellerType.STOCKPILE;
            case COMPANY_WORKER   -> TradeListing.SellerType.COMPANY;
            default               -> TradeListing.SellerType.PRODUCER;
        };
    }

    private static double distanceTo(Building building,
                                     double x, double z) {
        BlockPos origin = building.getShape().getOrigin();
        return Math.sqrt(Math.pow(origin.getX() - x, 2)
                + Math.pow(origin.getZ() - z, 2));
    }

    @Nullable
    private static TownspersonMob findEntity(ServerLevel level, UUID id) {
        return TownspersonMob.findByUUID(level, id).orElse(null);
    }

    private static int countPopulation(ServerLevel level,
                                       Village village,
                                       VillageSavedData data) {
        return (int) level.getEntitiesOfClass(
                TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> b.inflate(32))
                        .orElse(new AABB(0, 0, 0, 0, 0, 0)),
                mob -> mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        ).size();
    }

    private static int countProfession(ServerLevel level,
                                       Village village,
                                       VillageSavedData data,
                                       Profession profession) {
        return (int) level.getEntitiesOfClass(
                TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> b.inflate(32))
                        .orElse(new AABB(0, 0, 0, 0, 0, 0)),
                mob -> mob.getProfession() == profession
                        && mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        ).size();
    }

    private static NeedLevel needLevel(
            Map<NeedCategory,
                    tterrag1112.life_in_the_village.Village.Needs.VillageNeed> needs,
            NeedCategory category) {
        var need = needs.get(category);
        return need != null ? need.getLevel() : NeedLevel.SATISFIED;
    }

    // =========================================================================
    // RESULT RECORD
    // =========================================================================

    public record TradeListResult(TradeListing listing,
                                  TownspersonMob seller) {}
}