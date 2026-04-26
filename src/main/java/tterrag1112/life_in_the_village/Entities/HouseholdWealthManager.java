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

    // tickHouseholdSpending — called once per day from VillageDailyTickSystem.
    // PHASE-3-MIGRATION-23: shopping is now routed through ChannelRouter.
    // The previous market-only path is preserved as MarketChannel.execute,
    // so a village with a MARKET keeps its prior behaviour; a village
    // without one now falls through to DirectBusinessChannel.
    public static void tickHouseholdSpending(ServerLevel level,
                                             Village village,
                                             VillageSavedData data) {
        data.getHouseholdsForVillage(village.getId()).forEach(household -> {
            if (household.getPooledWealth() <= 0) return;

            Building house = data.getBuildingById(household.getBuildingId())
                    .orElse(null);
            // Head NPC drives the trade — needed as the actorId on the
            // intent so DirectBusinessChannel can read relationships and
            // find the wallet for any non-pool components.
            TownspersonMob headNpc = household.getMemberNpcIds().stream()
                    .flatMap(id -> level.getEntitiesOfClass(
                                    TownspersonMob.class,
                                    new net.minecraft.world.phys.AABB(
                                            BlockPos.ZERO).inflate(30000000),
                                    mob -> mob.getUUID().equals(id))
                            .stream())
                    .filter(mob -> mob.getFamilyRole() == FamilyRole.HEAD)
                    .findFirst().orElse(null);

            List<ShopEntry> shoppingList = getShoppingList(household.getPooledWealth());

            for (ShopEntry entry : shoppingList) {
                long perUnitCeiling = entry.qty() > 0 ? entry.maxSpendBronze() / entry.qty() : 0L;
                if (perUnitCeiling <= 0 || headNpc == null) continue;

                tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent intent =
                        tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent.buy(
                                entry.item(), entry.qty(),
                                headNpc.getUUID(), house == null ? null : house.getId(),
                                village.getId(),
                                perUnitCeiling,
                                tterrag1112.life_in_the_village.Npc.Economy.Channels.Urgency.NORMAL,
                                java.util.Set.of());

                var quote = tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelRouter
                        .findBestChannel(intent, village, data, level)
                        .orElse(null);
                if (quote == null) continue;

                long total = quote.totalBronze();
                if (!household.canWithdraw(total, FamilyRole.HEAD)) continue;
                if (!household.withdrawFromPool(total, FamilyRole.HEAD)) continue;

                // Top up the head's wallet with the pool draw so the
                // channel's execute can spend it; the channel returns
                // bronze to the seller. The pool already paid out so no
                // double-charging.
                headNpc.getWallet().receive(
                        tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue.of(total));

                var result = quote.channel() == tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelType.MARKET
                        ? new tterrag1112.life_in_the_village.Npc.Economy.Channels.impl.MarketChannel()
                            .execute(quote, intent, level)
                        : tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelRouter
                            .registeredChannels().stream()
                            .filter(c -> c.type() == quote.channel())
                            .findFirst()
                            .map(c -> c.execute(quote, intent, level))
                            .orElse(tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeResult
                                    .fail("channel missing"));

                if (!result.success()) {
                    // Refund pool on failure.
                    headNpc.getWallet().spend(total);
                    household.depositToPool(total);
                    continue;
                }

                // Goods → house storage if available, else personal inventory.
                int qty = result.quantityTraded();
                if (house != null) {
                    BuildingStorageAccess.storeItem(level, house,
                            new ItemStack(entry.item(), qty));
                } else {
                    headNpc.getPersonalInventory().addItem(new ItemStack(entry.item(), qty));
                }

                NpcTransactionVisual.showPayment(headNpc, level);
                data.markDirty();
            }
        });
    }

    private static List<ShopEntry> getShoppingList(long pooledWealth) {
        if (pooledWealth < POOR_THRESHOLD)        return POOR_SHOPPING;
        if (pooledWealth < MODEST_THRESHOLD)      return MODEST_SHOPPING;
        if (pooledWealth < COMFORTABLE_THRESHOLD) return COMFORTABLE_SHOPPING;
        return WEALTHY_SHOPPING;
    }
}