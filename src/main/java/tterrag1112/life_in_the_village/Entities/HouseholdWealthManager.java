package tterrag1112.life_in_the_village.Entities;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.NpcTransactionVisual;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * Manages household wealth pooling and priority-based spending.
 *
 * <h3>Wealth pooling</h3>
 * When a household member receives wages or sells goods, a portion flows
 * into the shared household pool via {@link #contributeToPool}. The HEAD
 * contributes 40%, SPOUSE 30%, others 20%. Personal coins are kept for
 * individual spending (tools, personal market trips).
 *
 * <h3>Spending tiers</h3>
 * Once per in-game day, the HEAD checks the pool and spends on the
 * village market based on wealth:
 * <pre>
 *   POOR       (< 100b)   : only buys basic food (bread, carrot, potato)
 *   MODEST     (100-500b) : basic food + occasional tool
 *   COMFORTABLE(500-2000b): varied food, wool, simple furnishings
 *   WEALTHY    (> 2000b)  : luxury foods, better goods, varied items
 * </pre>
 */
public final class HouseholdWealthManager {

    private HouseholdWealthManager() {}

    // ── Wealth tier thresholds (bronze) ──────────────────────────────────────
    private static final long POOR_THRESHOLD        = 100L;
    private static final long MODEST_THRESHOLD      = 500L;
    private static final long COMFORTABLE_THRESHOLD = 2000L;

    // ── Pool contribution fractions ───────────────────────────────────────────
    private static final double HEAD_CONTRIB   = 0.40;
    private static final double SPOUSE_CONTRIB = 0.30;
    private static final double OTHER_CONTRIB  = 0.20;

    // ── Shopping lists per tier ───────────────────────────────────────────────

    private record ShopEntry(Item item, int qty, long maxSpendBronze) {}

    private static final List<ShopEntry> POOR_SHOPPING = List.of(
            new ShopEntry(Items.BREAD,   4, 20L),
            new ShopEntry(Items.CARROT,  4, 12L),
            new ShopEntry(Items.POTATO,  4, 12L)
    );

    private static final List<ShopEntry> MODEST_SHOPPING = List.of(
            new ShopEntry(Items.BREAD,         6,  30L),
            new ShopEntry(Items.CARROT,        6,  18L),
            new ShopEntry(Items.POTATO,        6,  18L),
            new ShopEntry(Items.COOKED_BEEF,   2,  20L),
            new ShopEntry(Items.WOODEN_AXE,    1,  10L),
            new ShopEntry(Items.TORCH,         8,   8L)
    );

    private static final List<ShopEntry> COMFORTABLE_SHOPPING = List.of(
            new ShopEntry(Items.BREAD,          8,  40L),
            new ShopEntry(Items.COOKED_BEEF,    4,  40L),
            new ShopEntry(Items.COOKED_PORKCHOP,4,  40L),
            new ShopEntry(Items.APPLE,          4,  16L),
            new ShopEntry(Items.BAKED_POTATO,   4,  16L),
            new ShopEntry(Items.WHITE_WOOL,     4,  24L),
            new ShopEntry(Items.WHITE_BED,      1,  30L),
            new ShopEntry(Items.IRON_AXE,       1,  40L),
            new ShopEntry(Items.CHEST,          1,  20L),
            new ShopEntry(Items.TORCH,         16,  16L)
    );

    private static final List<ShopEntry> WEALTHY_SHOPPING = List.of(
            new ShopEntry(Items.COOKED_BEEF,    8,   80L),
            new ShopEntry(Items.COOKED_PORKCHOP,8,   80L),
            new ShopEntry(Items.MUSHROOM_STEW,  2,   20L),
            new ShopEntry(Items.PUMPKIN_PIE,    4,   32L),
            new ShopEntry(Items.COOKIE,         8,   24L),
            new ShopEntry(Items.APPLE,          8,   32L),
            new ShopEntry(Items.IRON_PICKAXE,   1,   60L),
            new ShopEntry(Items.IRON_SWORD,     1,   80L),
            new ShopEntry(Items.IRON_CHESTPLATE,1,  120L),
            new ShopEntry(Items.BOOKSHELF,      1,   50L),
            new ShopEntry(Items.WHITE_WOOL,     8,   48L),
            new ShopEntry(Items.LANTERN,        2,   20L)
    );

    // =========================================================================
    // Pool contribution — call from TreasuryTickHandler when NPC is paid
    // =========================================================================

    /**
     * Called when an NPC receives wages or sells goods. Moves a fraction
     * of the received amount into the household pool.
     *
     * @param npc    the NPC who received coins
     * @param amount what they received
     * @param data   saved data
     */
    // contributeToPool — called after TreasuryTickHandler pays a wage
    public static void contributeToPool(TownspersonMob npc,
                                        CurrencyValue amount,
                                        VillageSavedData data) {
        HouseholdData household = npc.getHouseId()
                .flatMap(data::getHouseholdForBuilding)
                .orElse(null);
        if (household == null) return;

        double fraction = switch (npc.getFamilyRole()) {
            case HEAD   -> HEAD_CONTRIB;
            case SPOUSE -> SPOUSE_CONTRIB;
            default     -> OTHER_CONTRIB;
        };

        long contribution = (long)(amount.toBronze() * fraction);
        if (contribution <= 0) return;

        // Deduct from NPC's personal wallet directly (no visual — this is bookkeeping)
        if (npc.getWallet().spend(contribution)) {
            household.depositToPool(contribution);
            data.markDirty();
        }
    }

    // tickHouseholdSpending — called once per day from VillageDailyTickSystem
    public static void tickHouseholdSpending(ServerLevel level,
                                             Village village,
                                             VillageSavedData data) {
        data.getHouseholdsForVillage(village.getId()).forEach(household -> {
            if (household.getPooledWealth() <= 0) return;

            Building market = village.getBuildingIds().stream()
                    .map(data::getBuildingById)
                    .filter(Optional::isPresent).map(Optional::get)
                    .filter(b -> b.getType() == BuildingType.MARKET)
                    .findFirst().orElse(null);
            if (market == null) return;

            Building house = data.getBuildingById(household.getBuildingId())
                    .orElse(null);

            List<ShopEntry> shoppingList = getShoppingList(household.getPooledWealth());

            for (ShopEntry entry : shoppingList) {
                long cost = entry.maxSpendBronze();
                if (!household.canWithdraw(cost, FamilyRole.HEAD)) continue;

                int inMarket = BuildingStorageAccess.countItem(
                        level, market, entry.item());
                if (inMarket <= 0) continue;

                int qty = Math.min(entry.qty(), inMarket);
                if (qty <= 0) continue;

                if (!BuildingStorageAccess.takeItem(level, market, entry.item(), qty))
                    continue;

                // Withdraw from pool (pool is a virtual long — no NPC involved)
                if (!household.withdrawFromPool(cost, FamilyRole.HEAD)) continue;

                // Route payment to stall owner or merchant
                // Find head-of-household NPC for visual purposes
                TownspersonMob headNpc = household.getMemberNpcIds().stream()
                        .flatMap(id -> level.getEntitiesOfClass(
                                        TownspersonMob.class,
                                        new net.minecraft.world.phys.AABB(
                                                BlockPos.ZERO).inflate(30000000),
                                        mob -> mob.getUUID().equals(id))
                                .stream())
                        .filter(mob -> mob.getFamilyRole() == FamilyRole.HEAD)
                        .findFirst().orElse(null);

                TownspersonMob merchant = findMerchantAtBuilding(level, market);
                if (headNpc != null && merchant != null) {
                    // Credit merchant wallet — visual fires on head NPC
                    headNpc.getWallet().spend(0); // ensure wallet is initialized
                    NpcTransactionVisual.showPayment(headNpc, merchant, level);
                    merchant.getWallet().receive(cost);
                } else if (merchant != null) {
                    merchant.getWallet().receive(cost);
                }
                // 10% to treasury
                village.depositToTreasury(cost / 10);

                // Put goods in house
                if (house != null) {
                    BuildingStorageAccess.storeItem(level, house,
                            new ItemStack(entry.item(), qty));
                }

                data.markDirty();
            }
        });
    }

    private static TownspersonMob findMerchantAtBuilding(ServerLevel level,
                                                         Building building) {
        return level.getEntitiesOfClass(
                TownspersonMob.class,
                building.getShape().toAABB().inflate(16),
                mob -> mob.getProfession()
                        == tterrag1112.life_in_the_village.Profession.Profession.MERCHANT
        ).stream().findFirst().orElse(null);
    }

    private static List<ShopEntry> getShoppingList(long pooledWealth) {
        if (pooledWealth < POOR_THRESHOLD)        return POOR_SHOPPING;
        if (pooledWealth < MODEST_THRESHOLD)      return MODEST_SHOPPING;
        if (pooledWealth < COMFORTABLE_THRESHOLD) return COMFORTABLE_SHOPPING;
        return WEALTHY_SHOPPING;
    }
}