package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Trade;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Networking.OpenTradeScreenPacket;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Trade.ExoticItemPool;
import tterrag1112.life_in_the_village.Village.Economy.Trade.ExoticItemPool.WanderTrade;
import tterrag1112.life_in_the_village.Village.Economy.Currency.TradeOffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Goal for the wandering exotic trader NPC.
 *
 * <h3>Lifecycle</h3>
 * <pre>
 * SETUP → WANDERING → CAMPED (stays 2 in-game days) → DEPARTING → discard
 * </pre>
 *
 * <ul>
 *   <li>SETUP    — initialises spawn tick and trade list from NBT or current time</li>
 *   <li>WANDERING — walks to a campsite block within 16 blocks of spawn</li>
 *   <li>CAMPED   — stands still, announces wares, handles player trades</li>
 *   <li>DEPARTING — walks away from the road, then discards itself</li>
 * </ul>
 *
 * <h3>Persistence</h3>
 * Spawn tick is stored in {@code entity.getPersistentData()} under the key
 * {@code "wt_spawnTick"} so the lifespan survives server restarts.
 * Trades are regenerated deterministically from the spawn tick as a seed,
 * so no trade serialisation is needed.
 *
 * <h3>Trade execution</h3>
 * Call {@link #openTradeScreen(ServerPlayer)} for right-click interaction.
 * Call {@link #executeBuyTrade(ServerPlayer, Item, int)} when the client sends
 * a {@code TradeActionPacket} and the merchant is a wandering trader.
 */
public class WanderingTraderBehavior extends Behavior<TownspersonMob> {

    // ── Constants ─────────────────────────────────────────────────────────────
    /** How long the trader stays before departing (2 in-game days). */
    public static final long LIFESPAN_TICKS = 24000L * 2;
    /** How far from spawn position to pick the campsite. */
    private static final double CAMP_WANDER_RADIUS = 16.0;
    /** How close to the campsite before switching to CAMPED. */
    private static final double CAMP_REACH = 2.5;
    /** Move speed while wandering to campsite. */
    private static final double MOVE_SPEED = 0.5;

    // ── State ──────────────────────────────────────────────────────────────────
    private enum Phase { SETUP, WANDERING, CAMPED, DEPARTING }

    private TownspersonMob entity;

    public WanderingTraderBehavior() {
        super(com.google.common.collect.ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }
    private Phase phase = Phase.SETUP;
    private long spawnTick = 0L;
    private BlockPos campsite = null;
    private BlockPos spawnPos = null;
    private List<WanderTrade> trades = new ArrayList<>();
    /** Per-trade remaining stock (parallel to trades list). */
    private int[] stock;

    

    // =========================================================================
    // Goal lifecycle
    // =========================================================================

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        // Always active — this goal owns the entire NPC lifecycle
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        return true;
    }

        @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;

        switch (phase) {
            case SETUP    -> tickSetup(level);
            case WANDERING -> tickWandering(level);
            case CAMPED   -> tickCamped(level);
            case DEPARTING -> tickDeparting();
        }
    }

    // =========================================================================
    // Phase ticks
    // =========================================================================

    private void tickSetup(ServerLevel level) {
        // Restore or initialise spawn tick
        long stored = entity.getPersistentData().getLong("wt_spawnTick").orElse(0L);
        if (stored == 0L) {
            stored = level.getGameTime();
            entity.getPersistentData().putLong("wt_spawnTick", stored);
        }
        spawnTick = stored;

        // Generate trades deterministically from spawn tick
        trades = ExoticItemPool.generateTrades(spawnTick);
        stock  = new int[trades.size()];
        for (int i = 0; i < trades.size(); i++) {
            stock[i] = trades.get(i).quantity();
        }

        spawnPos = entity.blockPosition();

        // Pick campsite: walk a short distance from spawn, preferring solid ground
        campsite = findCampsite(level);

        entity.setCurrentActivity("Searching for a place to set up...");
        phase = Phase.WANDERING;
    }

    private void tickWandering(ServerLevel level) {
        if (campsite == null) {
            campsite = entity.blockPosition(); // fall back to current pos
        }

        if (entity.blockPosition().closerThan(campsite, CAMP_REACH)) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            entity.setCurrentActivity("Selling exotic wares!");
            phase = Phase.CAMPED;
            return;
        }

        // Reissue nav if stuck
        if (entity.getNavigation().isDone()) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    campsite.getX() + 0.5,
                    campsite.getY(),
                    campsite.getZ() + 0.5,
                    MOVE_SPEED));
        }
    }

    private void tickCamped(ServerLevel level) {
        long elapsed = level.getGameTime() - spawnTick;

        // Check if it's time to leave
        if (elapsed >= LIFESPAN_TICKS) {
            entity.setCurrentActivity("Packing up...");
            phase = Phase.DEPARTING;
            return;
        }

        // Stay near campsite — re-anchor if nudged away
        if (!entity.blockPosition().closerThan(campsite, CAMP_REACH * 2)) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    campsite.getX() + 0.5,
                    campsite.getY(),
                    campsite.getZ() + 0.5,
                    MOVE_SPEED));
        } else {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }
    }

    private void tickDeparting() {
        // Walk away from campsite, then discard
        if (spawnPos != null) {
            BlockPos awayPos = spawnPos.offset(
                    (int)((entity.blockPosition().getX() - spawnPos.getX()) * 2),
                    0,
                    (int)((entity.blockPosition().getZ() - spawnPos.getZ()) * 2));

            if (!entity.getNavigation().isDone()) return;
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    awayPos.getX() + 0.5,
                    awayPos.getY(),
                    awayPos.getZ() + 0.5,
                    0.7));
        }

        // Discard after a short walk (nav will finish when far enough)
        if (entity.getNavigation().isDone() || phase == Phase.DEPARTING) {
            entity.discard();
        }
    }

    // =========================================================================
    // Trade interaction
    // =========================================================================

    /**
     * Opens the trade screen for a player.
     * Called from {@code NpcInteractionHandler} on right-click.
     */
    public void openTradeScreen(ServerPlayer player) {
        if (phase != Phase.CAMPED) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "[" + entity.getNpcName() + "] I'm not ready yet."),
                    false);
            return;
        }

        List<TradeOffer> offers = new ArrayList<>();
        for (int i = 0; i < trades.size(); i++) {
            WanderTrade t = trades.get(i);
            if (stock[i] <= 0) continue;
            // Wandering trader only sells (canSell = false here means player
            // cannot sell TO the trader — he doesn't buy back)
            offers.add(new TradeOffer(t.item(), t.price(), 0L,
                    true, false, stock[i]));
        }

        if (offers.isEmpty()) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "[" + entity.getNpcName()
                                    + "] I'm afraid I've sold out of everything."),
                    false);
            return;
        }

        long playerWealth = entity.level() instanceof ServerLevel sl
                ? CoinHelper.getPlayerWealth(player).toBronze()
                : 0L;

        PacketDistributor.sendToPlayer(player,
                new OpenTradeScreenPacket(entity.getUUID(),
                        entity.getNpcName(), offers, playerWealth));
    }

    /**
     * Executes a buy transaction (player purchases from trader).
     * Called from {@code TradeHandler.handleTrade} when merchant is a
     * wandering trader.
     *
     * @return true if the trade succeeded
     */
    public boolean executeBuyTrade(ServerPlayer player, Item item, int quantity) {
        int tradeIdx = findTradeIndex(item);
        if (tradeIdx < 0) return false;

        WanderTrade trade = trades.get(tradeIdx);

        if (stock[tradeIdx] < quantity) return false;

        long totalCost = trade.price() * quantity;
        CurrencyValue cost = CurrencyValue.of(totalCost);

        // Check player can afford it
        net.minecraft.world.SimpleContainer inv = buildPlayerContainer(player);
        if (!CoinHelper.playerCanAfford(player, cost)) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "You can't afford that."), true);
            return false;
        }

        // Deduct coins from player inventory
        if (!CoinHelper.playerPay(player, cost)) return false;

        // Give items to player
        ItemStack reward = new ItemStack(item, quantity);
        if (!player.getInventory().add(reward)) {
            player.drop(reward, false);
        }

        // Reduce stock
        stock[tradeIdx] -= quantity;

        // The trader keeps the coins (they have no coin inventory to add to,
        // but the coins are effectively removed from circulation — consistent
        // with the "faraway kingdom" origin of the trader)

        return true;
    }

    // =========================================================================
    // Public accessors
    // =========================================================================

    /** Whether the trader is currently set up and accepting customers. */
    public boolean isCamped() { return phase == Phase.CAMPED; }

    public List<WanderTrade> getTrades() { return trades; }

    public int getStock(int index) { return stock != null ? stock[index] : 0; }

    // =========================================================================
    // Helpers
    // =========================================================================

    private BlockPos findCampsite(ServerLevel level) {
        // Walk CAMP_WANDER_RADIUS blocks in a random cardinal direction
        var rng = entity.getRandom();
        int dx = (rng.nextBoolean() ? 1 : -1) * (8 + rng.nextInt(9));
        int dz = (rng.nextBoolean() ? 1 : -1) * (8 + rng.nextInt(9));
        BlockPos candidate = entity.blockPosition().offset(dx, 0, dz);

        // Find the topmost solid block at that column
        candidate = level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                candidate);

        // Make sure it's not inside a block
        if (!level.getBlockState(candidate).isAir()) {
            candidate = candidate.above();
        }
        return candidate;
    }

    private int findTradeIndex(Item item) {
        for (int i = 0; i < trades.size(); i++) {
            if (trades.get(i).item() == item) return i;
        }
        return -1;
    }

    private static net.minecraft.world.SimpleContainer buildPlayerContainer(
            ServerPlayer player) {
        var inv = player.getInventory();
        net.minecraft.world.SimpleContainer c =
                new net.minecraft.world.SimpleContainer(inv.getContainerSize());
        for (int i = 0; i < inv.getContainerSize(); i++) {
            c.setItem(i, inv.getItem(i));
        }
        return c;
    }

    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}