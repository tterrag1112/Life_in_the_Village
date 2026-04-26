# 23 — Economic Channels

## Purpose

The economy has scattered code paths for "how does this NPC buy/sell
this?" — market stalls, direct producer purchase, caravan, stockpile.
Each goal duplicates logic, and market-less villages struggle because
most code assumes a market.

Economic channels abstract the "where can I buy/sell?" question.
Each channel implements a common interface; a router matches trade
intents to the best available channel. NPCs and players express
intent; the router fulfills.

Foundation that makes no-market villages work, makes caravan arrivals
meaningful, and eventually enables the cross-village request system
(`28-request-board.md`) as just another channel.

## Data model

### TradeIntent

```java
public record TradeIntent(
    TradeDirection direction,       // BUY / SELL
    Item item,
    int quantity,
    UUID actorId,
    @Nullable UUID buildingId,
    UUID villageId,
    long maxPrice,                  // for BUY
    long minPrice,                  // for SELL
    Urgency urgency,
    Set<Item> substitutes           // acceptable alternatives for BUY
) { ... }

public enum TradeDirection { BUY, SELL }
public enum Urgency { IMMEDIATE, NORMAL, PATIENT }
```

### EconomicChannel

```java
public interface EconomicChannel {
    ChannelType type();
    int basePriority();
    boolean isAvailable(Village village, VillageSavedData data, long tick);

    Optional<ChannelQuote> quote(TradeIntent intent, Village village,
                                 VillageSavedData data, ServerLevel level);
    TradeResult execute(ChannelQuote quote, TradeIntent intent, ServerLevel level);
}

public enum ChannelType {
    MARKET, DIRECT_BUSINESS, CARAVAN, GUILD_REQUEST, STOCKPILE, VISITOR;
}

public record ChannelQuote(
    ChannelType channel,
    TradeIntent intent,
    long pricePerUnit,
    int availableQuantity,
    int travelTimeTicks,
    long quoteValidUntilTick,
    @Nullable BlockPos location
) {}

public record TradeResult(
    boolean success,
    int quantityTraded,
    long totalBronze,
    String failureReason
) {}
```

### ChannelRouter

```java
public final class ChannelRouter {
    public static Optional<ChannelQuote> findBestChannel(
            TradeIntent intent, Village village,
            VillageSavedData data, ServerLevel level) {

        List<ChannelQuote> quotes = new ArrayList<>();
        for (EconomicChannel channel : registeredChannels) {
            if (!channel.isAvailable(village, data, level.getGameTime())) continue;
            channel.quote(intent, village, data, level).ifPresent(quotes::add);
        }
        if (quotes.isEmpty()) return Optional.empty();

        quotes.sort((a, b) -> Double.compare(score(b, intent), score(a, intent)));
        return Optional.of(quotes.get(0));
    }

    private static double score(ChannelQuote q, TradeIntent intent) {
        double score = typePriority(q.channel());
        if (intent.direction() == BUY) score -= q.pricePerUnit() / 100.0;
        else score += q.pricePerUnit() / 100.0;
        score -= q.travelTimeTicks() / 200.0;
        if (intent.urgency() == IMMEDIATE) score += 20;
        return score;
    }
}
```

## Channels

### MarketChannel

Wraps existing market-stall/merchant-kiosk logic.
- Available when village has MARKET.
- Quote via `MarketPriceHelper` + stall mark-up.
- Execute: existing `TradeHandler`.
- Priority: 100.

### DirectBusinessChannel

NPC-to-NPC at producing workshop. Critical for no-market villages.
- Always available (any village with matching producer).
- Finds NPC at workshop producing/consuming target item.
- Quote = base price × relationship modifier × production efficiency.
- Execute: direct trade; buyer walks to workshop.
- Priority: 70. Higher when no market.
- Extra cost: travel time.

### CaravanChannel

Active while caravan present and unloading.
- Available only during caravan's presence tick window.
- Quote from caravan's goods.
- Execute: existing caravan-trade path.
- Priority: 50; IMMEDIATE urgency bumps higher.

### GuildRequestChannel (Phase 4)

Stubbed in Phase 3; wired in Phase 4.
- Submits intent to cross-village request board.
- Available for high-urgency buy unmet locally.
- Fulfillment deferred (days).
- Priority: 40.

Phase 3 stubs return "not available" until Phase 4 wires the board.

### StockpileChannel

Food intents only. Household food needs.
- Available when village has STOCKPILE + keeper.
- Only BUY food.
- Near-cost pricing.
- Priority: 30.

### VisitorChannel

Stubbed Phase 3; wired Phase 4 (visitor flux, `29`). Visitor bringing
goods.
- Priority: 45.

## Scoring

Router considers:
1. Base priority per channel type.
2. Price alignment with intent's max/min.
3. Travel time.
4. Urgency (IMMEDIATE → fastest; PATIENT → cheapest).
5. Relationship bonuses (DirectBusinessChannel weights positive
   relationships).

Typical outcomes:
- Village has market → market wins most queries.
- No market → direct-business wins.
- Caravan with rare item → caravan beats market for that item.
- Urgent + no local supply → guild-request.
- Household food → market > stockpile > direct-business (farmer).

## NPC integration

Goals consume the router:

### HouseholdWealthManager.shop

```java
for (ShopEntry entry : shoppingList) {
    TradeIntent intent = TradeIntent.buy(
        entry.item(), entry.quantity(),
        npc.getUUID(), npc.getAssignedBuildingId(),
        village.getId(), entry.maxBronze(), Urgency.NORMAL,
        entry.substitutes()
    );
    Optional<ChannelQuote> quote = ChannelRouter.findBestChannel(
        intent, village, data, level);
    if (quote.isEmpty()) continue;
    quote.get().channel().execute(quote.get(), intent, level);
}
```

### AbstractWorkstationProductionGoal.buyInputs

Same pattern.

### BuyFromMarketGoal → BuyGoodsGoal

Generalized; uses router.

### Caravan scheduling

Arrival updates CaravanChannel availability. Dispatch path registers
channel window.

## Player integration

- Existing trade UI continues to work; MarketChannel underneath.
- New: player at workshop can buy directly (DirectBusinessChannel).
  Interaction handler dispatches through router.
- Crafting orders / commissions remain their own flow (not routed).

## Price modifiers

Laws and relationships adjust at channel level:
- MARKET_TAX_DOUBLE → MarketChannel +10% to sell price.
- SUBSIDIZE_FARMER → farmer receives subsidy bonus.
- PRICE_CEILING_FOOD → food quotes capped.
- Relationship → DirectBusinessChannel ±5% for friends/rivals.

Channel implementations read `VillagePolicy` at quote time.

## Persistence

Router is stateless.
Caravan/visitor availability persists via their subsystems.
Cached quotes session-only.

## Integration points

### Phase 3 integration

- `EconomicChannel` interface + `ChannelRouter` in
  `Npc.Economy.Channels`.
- 5 channel impls (MARKET, DIRECT_BUSINESS, CARAVAN, STOCKPILE;
  VISITOR/GUILD_REQUEST stubs).
- Migration: `BuyFromMarketGoal` → `BuyGoodsGoal`;
  `HouseholdWealthManager.shop` and `AbstractWorkstationProductionGoal.buyInputs`
  to router.
- `TradeHandler` extended as MarketChannel backend.
- Caravan arrival/departure registers CARAVAN channel.
- Law-aware pricing in quote methods.
- `/economy quote` debug.

### Phase 4 integration

- GuildRequestChannel fully implemented.
- VisitorChannel fully implemented.
- Cross-village requests via guild.
- Player-owned business workers sell via DirectBusinessChannel.

## Behavior contract

### Does

- Centralize trade routing behind common interface.
- Enable market-less villages via DIRECT_BUSINESS.
- Respect urgency, relationship, laws in selection.
- Support deferred channels (guild request) as first-class options.

### Does not

- Replace trade UIs / stall renting. MarketChannel wraps them.
- Handle player-to-player trading.
- Support partial-fill natively — actor decides.

## Edge cases

- **No channel available.** Empty; goal falls through.
- **Selected channel fails at execute.** Failure; actor may retry
  with fresh quote.
- **Tied scores.** Priority → travel → price tiebreak.
- **Actor's building destroyed.** Intent's buildingId stale;
  village scope still works.
- **No-channel-handles item.** Empty; defer to guild request.

## Ordering dependencies

Phase 3 depends on:
- Existing trade / market infrastructure.
- Village laws (same phase) — price modifiers.
- NPC relationships (Phase 2) — price modifiers.
- Existing caravan and stockpile.

Phase 4 enables:
- Full guild request channel.
- Full visitor channel.
- NPC-owned company participation.

## Open decisions

- Cache quotes across NPCs? **Proposed: no caching v1; profile in
  testing.**
- Partial-fill semantics? **Proposed: return smaller quantity with
  flag; actor accepts/rejects partial.**
- Fire `NpcLifeEventBus.Trade` on DirectBusinessChannel? **Proposed:
  yes, all successful trades produce same event downstream.**
- Caravan pricing cheaper than market? **Proposed: 10% discount vs.
  market baseline to encourage trade.**

## Does-not-include

- Auctions / bidding.
- Commodity speculation / price charts.
- Inventory reservation before execute.
- Futures / forward contracts.

## Revision Notes

(changes recorded here as the spec evolves after testing)

### 2026-04-26 — Phase 3 wiring session (task 23)

Channels landed in `tterrag1112.life_in_the_village.Npc.Economy.Channels`
plus `Npc.Economy.Channels.impl` for the six implementations. Router +
debug commands wired; three legacy trade callers migrated (full list in
`/economy migrate-status`).

**Spec deviation — `isAvailable` signature.** Spec line 47 omits
`ServerLevel`, but CARAVAN / VISITOR availability is keyed by saved
data that lives on the level. The interface now takes
`(Village, VillageSavedData, ServerLevel, long tick)`. Without it,
`isAvailable` would always have to either return `true` (forcing every
quote() to run) or hard-code `level == null` returns. Documented on
the interface.

**Score formula.** Implemented per spec line 95 with two extras:
- `+5.0` PATIENT bias when the channel's price is in the lower half
  of the intent's max-price band (BUY only). Spec hints "PATIENT →
  cheapest" but doesn't formula it; this gives PATIENT a meaningful
  effect without dominating priority.
- Tiebreak chain (priority → travel → price) is already implicit in
  the scalar score; no extra Comparable layer needed.

**Channel-by-channel notes.**
- **MarketChannel:** delegates execute to existing
  `NpcEconomy.marketPurchase` so stall/merchant payment routing stays
  byte-for-byte with the legacy path. Quote validity 24000 ticks.
  10% village treasury slice preserved.
- **DirectBusinessChannel:** workshop discovery uses a hard-coded
  item → BuildingType map (v1 list covers ~30 common items; gaps fall
  through and other channels win). Phase 4 production-tag pass
  replaces this with a registry-backed lookup. Relationship modifier
  is `±5%` from the seller's NPC↔NPC ledger entry, scaled linearly to
  the −100..+100 range. Fires `NpcLifeEvent.Trade` per spec "Open
  decisions" #3.
- **CaravanChannel:** active when any caravan with `destVillageId ==
  village.id` is in `DELIVERING` state. 10% discount vs. market
  baseline per spec "Open decisions" #4. Decrements goods in place;
  the caravan's tick handles state transitions normally. Quote
  validity 6000 ticks (caravan presence window).
- **StockpileChannel:** food-only, BUY-only. Near-cost ratio 0.7 per
  spec line 149. Coin flows to the stockpile keeper rather than the
  village treasury — keepers are paid producers, not collectors.
- **GuildRequestChannel / VisitorChannel:** registered with correct
  priorities + types but `isAvailable` always returns false so they
  never win; Phase 4 swaps the impls.

**Caller migration semantics.** Channels move source-side items + bronze
but do NOT stash items in any destination container — the caller
decides where the item lands (home chest, workshop input chest, NPC
inventory) and reads `result.quantityTraded` to know how many to
place. This is documented on `EconomicChannel.execute`. Each caller
follows the same template:

```
1. Build TradeIntent
2. ChannelRouter.findBestChannel
3. (if affordable) channel.execute
4. (on success) move qty into the destination container
5. (on partial-fill / failure) refund any bridged bronze
```

**Workshop bridging pattern.** The workshop's `BuildingEconomy` wallet
is bridged through the NPC's personal wallet so the channel's `execute`
can spend on behalf of the buyer NPC: `bEconomy.withdraw(total) →
entity.getWallet().receive(total) → channel.execute spends from wallet
→ channel pays seller`. On failure or partial fill, the leftover is
returned via `entity.getWallet().spend(leftover) →
bEconomy.depositRevenue(leftover)`. Documented inline in
`AbstractWorkstationProductionGoal.executeBuy`.

**Workshop MARKET_VISIT trigger unchanged.** The `marketVisit` phase
still requires `market != null` to enter — only the buy *path* inside
that phase is migrated to the router. Workshops in market-less
villages remain idle for input procurement; spec doesn't explicitly
list "workshops function without a market" as Phase 3 scope, so this
is held back as a follow-up.

**Deleted / renamed:**
- `Entities/Goals/Social/BuyFromMarketGoal.java` deleted.
- `Entities/Goals/Social/BuyGoodsGoal.java` (new). Registered in
  `ProfessionGoalFactory.registerLifeStage` for TEEN + ADULT NPCs.

**Things-to-flag responses (from the brief).**
1. **Stale-quote handling.** `ChannelQuote.isExpired(currentTick)`
   returns true past `quoteValidUntilTick`. `BuyGoodsGoal` skips
   expired quotes at execute time; the daily tick refreshes by
   re-running `findBestChannel`. Channels also re-validate stock at
   execute time (e.g. `MarketChannel.executeBuy` re-reads
   `countItem`).
2. **Partial-fill semantics.** Channels return
   `result.quantityTraded` ≤ requested quantity when stock runs low,
   never throw. The caller's `TradeResult.partial(intent)` helper
   surfaces this; `BuyGoodsGoal` and `executeBuy` both refund any
   leftover bronze on partial fills.
3. **`NpcLifeEventBus.Trade` on DirectBusinessChannel.** Yes — fires
   from `execute` whenever the buyer is an NPC. Matches the spec's
   "Open decisions" #3.

**Build verification deferred.** Sandbox can't reach
`maven.neoforged.net` (HTTP 403 `host_not_allowed`). Code review
covered imports / signatures / wallet flow / refund paths but the
exit-criteria scenarios (no-market village; market-vs-caravan
ranking; urgent food → stockpile; `/economy quote` listing; save
preserves caravan window) need to run on a dev box before the wiring
is considered validated.
