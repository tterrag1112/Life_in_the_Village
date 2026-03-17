package tterrag1112.life_in_the_village.Village.Economy;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceRegistry;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeListing;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;
import java.util.stream.Collectors;

public class VillageEconomy {

    // Singleton per server — map of village UUID to its listings
    private static final Map<UUID, List<TradeListing>> LISTINGS =
            new HashMap<>();

    // Listing expiry — refresh every 5 minutes
    private static final long LISTING_TTL = 6000L;

    // Price multipliers by seller type
    private static final double PRODUCER_MARKUP  = 0.8;  // 20% below base
    private static final double MERCHANT_MARKUP  = 1.2;  // 20% above base
    private static final double STOCKPILE_MARKUP = 1.0;  // base price

    // =========================================================================
    // POSTING LISTINGS
    // =========================================================================

    /**
     * Called by producer NPCs to advertise items they have for sale.
     */
    public static void postListing(ServerLevel level, UUID villageId,
                                   TownspersonMob seller, Item item,
                                   int quantity, long tick) {
        if (quantity <= 0) return;



        List<TradeListing> listings = LISTINGS
                .computeIfAbsent(villageId, k -> new ArrayList<>());

        // Remove stale listings from this seller for this item
        listings.removeIf(l ->
                l.getSellerEntityId().equals(seller.getUUID())
                        && l.getItem() == item);

        // Compute asking price based on seller type and supply
        long basePrice = getBasePrice(item);
        double markup = getMarkup(seller.getProfession());

        // Supply modifier — more supply = lower price
        int supply = getTotalSupply(level, villageId, item);
        double supplyModifier = supply > 64 ? 0.8 :
                supply > 32 ? 0.9 : 1.0;

        long askingPrice = Math.max(1,
                Math.round(basePrice * markup * supplyModifier));

        TradeListing.SellerType sellerType = getSellerType(
                seller.getProfession());

        Building building = seller.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .orElse(null);
        if (building == null) return;

        listings.add(new TradeListing(
                seller.getUUID(), building.getId(),
                item, quantity, askingPrice, sellerType, tick
        ));

        System.out.println("Posted listing: " + seller.getNpcName()
                + " selling " + quantity + "x " + item.getDescriptionId()
                + " at " + askingPrice + "b (" + sellerType + ")");
    }

    /**
     * Find the cheapest seller of an item within search radius.
     */
    public static Optional<TradeListResult> findCheapestSeller(
            ServerLevel level, UUID villageId, Item item,
            double buyerX, double buyerZ, long currentTick) {

        List<TradeListing> fromListings = getListingsForItem(
                level, villageId, item, currentTick);

        List<TradeListResult> candidates = fromListings.stream()
                .filter(l -> {
                    TownspersonMob seller =
                            findEntity(level, l.getSellerEntityId());
                    if (seller == null) return false;
                    Building building = VillageSavedData.get(level)
                            .getBuildingById(l.getSellerBuildingId())
                            .orElse(null);
                    if (building == null) return false;
                    double dist = Math.sqrt(
                            Math.pow(building.getShape().getOrigin().getX()
                                    - buyerX, 2) +
                                    Math.pow(building.getShape().getOrigin().getZ()
                                            - buyerZ, 2));
                    return dist <= 128;
                })
                .map(l -> new TradeListResult(l,
                        findEntity(level, l.getSellerEntityId())))
                .filter(r -> r.seller() != null)
                .collect(Collectors.toList());

        // Add stockpile as fallback
        VillageSavedData data = VillageSavedData.get(level);
        data.getVillageById(villageId).ifPresent(village ->
                village.getBuildingIds().stream()
                        .map(data::getBuildingById)
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
                                    mob -> mob.getProfession() ==
                                            Profession.STOCKPILE_KEEPER
                            ).stream().findFirst().ifPresent(keeper -> {
                                long price = Math.max(1, getBasePrice(item));
                                candidates.add(new TradeListResult(
                                        new TradeListing(
                                                keeper.getUUID(), stockpile.getId(),
                                                item, count, price,
                                                TradeListing.SellerType.STOCKPILE,
                                                currentTick),
                                        keeper
                                ));
                            });
                        })
        );

        return candidates.stream()
                .min(Comparator.comparingLong(r ->
                        r.listing().getPricePerItem()));
    }

    // =========================================================================
    // STOCKPILE REQUESTS
    // =========================================================================

    /**
     * Compute what the stockpile needs based on village needs.
     * Returns map of item -> target quantity.
     */
    public static Map<Item, Integer> computeStockpileTargets(
            ServerLevel level, Village village, VillageSavedData data) {
        Map<Item, Integer> targets = new HashMap<>();

        int population = countPopulation(level, village, data);

        // Food targets based on population
        targets.put(net.minecraft.world.item.Items.WHEAT,
                population * 8);
        targets.put(net.minecraft.world.item.Items.BREAD,
                population * 4);
        targets.put(net.minecraft.world.item.Items.CARROT,
                population * 4);
        targets.put(net.minecraft.world.item.Items.POTATO,
                population * 4);

        // Building materials
        targets.put(net.minecraft.world.item.Items.OAK_LOG,
                64);
        targets.put(net.minecraft.world.item.Items.COBBLESTONE,
                128);
        targets.put(net.minecraft.world.item.Items.OAK_PLANKS,
                64);

        // Tools and equipment
        targets.put(net.minecraft.world.item.Items.IRON_PICKAXE,
                countProfession(level, village, data,
                        Profession.MINER) * 2);
        targets.put(net.minecraft.world.item.Items.IRON_SWORD,
                countProfession(level, village, data,
                        Profession.GUARD) * 2);

        // Seeds
        targets.put(net.minecraft.world.item.Items.WHEAT_SEEDS,
                64);

        return targets;
    }

    // =========================================================================
    // CLEANUP
    // =========================================================================

    public static void clearListings(UUID villageId) {
        LISTINGS.remove(villageId);
    }

    public static void purgeStaleListings(UUID villageId, long currentTick) {
        List<TradeListing> active = LISTINGS.get(villageId);
        if (active == null) return;
        LISTINGS.get(villageId).removeIf(l ->
                currentTick - l.getCreatedTick() > LISTING_TTL);
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    public static List<TradeListing> getListingsForItem(
            ServerLevel level, UUID villageId, Item item, long tick) {
        purgeStaleListings(villageId, tick);
        List<TradeListing> all = LISTINGS.getOrDefault(
                villageId, List.of());
        return all.stream()
                .filter(l -> l.getItem() == item)
                .collect(Collectors.toList());
    }

    public static long getBasePrice(Item item) {
        MarketPriceData data = MarketPriceRegistry.INSTANCE.getDefault();
        if (data == null) return 4;
        return data.getPrice(item)
                .map(MarketPriceData.ItemPrice::sellPrice)
                .orElse(4L);
    }

    private static double getMarkup(Profession profession) {
        return switch (profession) {
            case MERCHANT        -> MERCHANT_MARKUP;
            case STOCKPILE_KEEPER -> STOCKPILE_MARKUP;
            default              -> PRODUCER_MARKUP;
        };
    }

    private static TradeListing.SellerType getSellerType(
            Profession profession) {
        return switch (profession) {
            case MERCHANT        -> TradeListing.SellerType.MERCHANT;
            case STOCKPILE_KEEPER -> TradeListing.SellerType.STOCKPILE;
            default              -> TradeListing.SellerType.PRODUCER;
        };
    }

    private static int getTotalSupply(ServerLevel level,
                                      UUID villageId, Item item) {
        List<TradeListing> listings = LISTINGS.getOrDefault(
                villageId, List.of());
        return listings.stream()
                .filter(l -> l.getItem() == item)
                .mapToInt(TradeListing::getQuantity)
                .sum();
    }

    private static TownspersonMob findEntity(ServerLevel level, UUID id) {
        return level.getEntitiesOfClass(
                TownspersonMob.class,
                new net.minecraft.world.phys.AABB(
                        -30000000, -2048, -30000000,
                        30000000, 2048, 30000000),
                mob -> mob.getUUID().equals(id)
        ).stream().findFirst().orElse(null);
    }

    private static int countPopulation(ServerLevel level,
                                       Village village,
                                       VillageSavedData data) {
        return (int) level.getEntitiesOfClass(
                TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> b.inflate(32))
                        .orElse(new net.minecraft.world.phys.AABB(0,0,0,0,0,0)),
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
                        .orElse(new net.minecraft.world.phys.AABB(0,0,0,0,0,0)),
                mob -> mob.getProfession() == profession
                        && mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        ).size();
    }

    // Result record
    public record TradeListResult(TradeListing listing,
                                  TownspersonMob seller) {
    }
}
