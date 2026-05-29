# MERCHANT ARC — PROGRESS (append-only)

Economy foundation for the Merchant arc. Append entries at the end of each
session. Per-phase specs live in the prompt; this log records what shipped.

---

## Phase 1a — Unified pricing quote (one price function, both paths)

**Goal:** consolidate the player trade path and the NPC channel path onto a
single canonical pricing function with one documented modifier pipeline, and
make both honour stall custom prices (consulted by neither before). Pricing
only — no settlement, no inter-village modifier, no balance externalization.

### What shipped

New, in `Village/Economy/Currency/`:
- `BuyerType` — `{ PLAYER, NPC }`. Gates relationship modifiers.
- `PricingContext` — transient record (no codec): item, level, village,
  saved-data, buyer type, player (nullable), stall (nullable). Factories
  `forPlayer(...)` / `forNpc(...)`.
- `MarketPricing` — the canonical function. `sellPrice(ctx)` = price a buyer
  pays; `buyPrice(ctx)` = price a seller receives. Composes the existing
  base + supply/demand core with the law / stall / relationship layers.

Rewired:
- `TradeHandler.openTradeScreen` (display), `handleTrade` buy branch, and
  `handleTrade` sell branch now call `MarketPricing`. The inline reputation
  → perk → tariff stacks were removed.
- `MarketChannel.quote` now calls `MarketPricing` for both directions; its
  private `applyPolicy` (LawPriceHooks tax/floor/ceiling/subsidy) moved into
  `MarketPricing` (shared). The intent min/max clamp stays in the channel
  (it is a routing concern, not a price modifier).

### Modifier pipeline (canonical, ordered)

| # | Layer | Source |
|---|-------|--------|
| 1 | Base price | `MarketPriceHelper` (culture table) |
| 2 | Supply/demand | `DynamicPriceCalculator` (×0.5–3.0; ×0.6 buy margin) |
| 3 | Stall custom price | `MarketStall.getEffectivePrice` (buyer-pays side) |
| 4 | Village law | `LawPriceHooks` tax mult + food floor/ceiling + subsidy |
| 5 | Reputation discount | `ReputationManager` (player buy side) |
| 6 | Profession perk | `ProfessionPerkManager` (player sell side) |
| 7 | Kingdom tariff | `KingdomLawEffects` (player, cross-kingdom) |

`sellPrice` runs 1→2→3(stall)→4→5(rep)→7(tariff buy mult). `buyPrice` runs
1→2→4(+subsidy)→6(perk)→7(tariff sell mult). Stall override is buyer-pays
only (a stall is a sell endpoint, not a buyer); reputation discount is buy
side; perk is sell side, matching the pre-refactor behaviour exactly.

The pipeline is *shaped* to accept a per-village modifier later (1c) — it
would slot between layers 2 and 4 — but none is added now.

### Applicability matrix

| Modifier | PLAYER | NPC | Side | Why |
|----------|:------:|:---:|------|-----|
| Base | ✓ | ✓ | both | registry |
| Supply/demand | ✓ | ✓ | both | village market state |
| Stall custom price | ✓ | ✓ | sell (buyer pays) | applies whenever a stall sources the item |
| Village law (tax/floor/ceiling/subsidy) | ✓ | ✓ | both | village law binds all sales |
| Reputation discount | ✓ | ✗ | sell | relationship/player concept; NPCs have no village reputation |
| Profession perk | ✓ | ✗ | buy | player profession concept |
| Kingdom tariff | ✓ | ✗ | both | citizenship/cross-kingdom; keyed on the player |

NPC-only path gains nothing player-specific (rep/perk/tariff stay gated on
`BuyerType.PLAYER`); it gains only the stall-override layer it lacked.

### Intended price changes (enumerated — for Garrett's approval)

By design, unification closes two gaps. **No other price changes.** With no
laws active and no stall custom price set, every player- and NPC-visible
price is bit-identical to pre-refactor (same operations, same order, same
rounding — verified by walking the arithmetic).

1. **Player path now honours village law** (`LawPriceHooks`): market tax
   multiplier (`MARKET_TAX_DOUBLE` / `MARKET_TAX_REDUCED`), food price
   floor/ceiling, and subsidy. Previously the player path ignored village law
   entirely; the NPC path already applied it. → In a village with an active
   pricing law, player buy/sell prices now move. (Smoke test #1.)
2. **Both paths now honour stall custom prices** (`MarketStall.customPrices`
   / `getEffectivePrice`), consulted by neither path before. → If a stall
   sources the item and the owner set a custom price (±20% band), both a
   player trade and an NPC market buy reflect it. (Smoke test #2.)

Design note on layer 3 composition: a set stall price replaces the
base+dynamic result (the owner's number wins), then village law (4) may
still clamp it (e.g. food ceiling) and relationship modifiers (5–7) still
apply for players. Rationale: law binds all sales; reputation/tariff are
buyer-relationship concerns independent of who set the shelf price.

### Tie-In Audit

1. **Upstream feeders** — all reachable from one context:
   `MarketPriceRegistry`/`MarketPriceHelper` (base), `DynamicPriceCalculator`
   (supply/demand), `MarketStall.getEffectivePrice`, `LawPriceHooks`,
   `ReputationManager`, `ProfessionPerkManager`, `KingdomLawEffects`. ✓
2. **Downstream callers** of the base/dynamic API — dispositioned:
   - `TradeHandler` (player path) → **rewired** to `MarketPricing`.
   - `MarketChannel.quote` (NPC MARKET path) → **rewired**.
   - `CaravanChannel`, `StockpileChannel`, `DirectBusinessChannel` → use
     `getDynamicSellPrice` + their own policy. **Unchanged** — 1a scope is
     the MARKET path; folding the other channels onto one pricing fn is
     out of scope. Flagged.
   - `VillageEconomy.postListing` / `postCompanyListing` / `getMarketSellPrice`
     → the listings/posting system (dynamic × profession markup). **Unchanged
     and kept separate** (see Simplification Sweep). Flagged for later.
   - `MerchantBehavior` (~:247) → builds vanilla-merchant offers that are a
     documented no-op ("offers is unused currently"). **Unchanged.** Flagged.
   - `AbstractProductionBehavior` (~:1006) → uses dynamic sell price for a
     production decision, not a trade quote. **Unchanged.**
3. **Sibling systems** — `VillageEconomy` markups (`PRODUCER_MARKUP` 0.85,
   `MERCHANT_MARKUP` 1.20, `STOCKPILE_MARKUP`, `COMPANY_MARKUP`) overlap
   pricing but live in the listings layer, not the quote path. **Kept
   separate** so they are not double-applied; `MarketPricing` does not touch
   them. Flag: reconcile markups vs the unified pipeline in a later phase.
4. **Exhaustive switches** — `BuyerType` added. No `switch` over it exists
   (the pipeline uses `if (buyerType == PLAYER)`); nothing to update.

### Simplification Sweep

- Deleted the duplicated inline modifier stacks in `TradeHandler` (three
  sites) and the private `MarketChannel.applyPolicy`; one shared
  `MarketPricing` now owns the modifier layer.
- Reused `MarketStall.getEffectivePrice` verbatim for the stall layer (no
  new accessor).
- Player display now sources its pricing stall via the existing
  `findOwnedStallWithItem`, the same rule the buy execution uses — so the
  displayed price always equals the charged price.
- Removed unused imports (`LawPriceHooks`, `MarketPriceHelper`) from
  `MarketChannel` and the now-dead `basePrice` locals from `TradeHandler`.

### Deviations from prompt

- None functional. The prompt offered "extend `MarketPriceHelper` or add a
  small pricing service" — chose the service (`MarketPricing` +
  `PricingContext`) so `MarketPriceHelper` stays the base/dynamic primitive
  layer and the modifier pipeline is a clean separate layer.
- Created this `MERCHANT_PROGRESS.md` (no prior economy progress file
  existed) rather than appending to an unrelated log.

### Out-of-scope but flagged

- Settlement / coin movement / treasury tax / `CoinHelper` (1b) — untouched;
  `MarketChannel.executeBuy` treasury slice + `LawTaxHooks` left as-is.
- Per-village price differentiation (1c) — pipeline shaped to accept it; not
  added.
- Balance constant externalization to data (1d).
- `VillageEconomy` markup reconciliation — flagged above.
- Non-MARKET channels (`Caravan`/`Stockpile`/`DirectBusiness`) onto the
  unified fn — deferred.
- `WANDERING_TRADER` pricing (`TradeHandler.handleWanderingTrade`) — left
  exactly as-is (base-only fast path), per prompt. `BuyerType` deliberately
  has no `WANDERING_TRADER` value.

### Preflight

- `BuyerType` enum added; no exhaustive switch to update. ✓
- No persisted record/field — `PricingContext` is transient, no codec. ✓
- No new per-tick logging (pricing runs on screen-open / shopping-plan
  build / quote, all event-driven). ✓
- Settlement code in `TradeHandler` / `MarketChannel` only *reads* the new
  price; coin movement untouched. ✓

### Build verification

Deferred (sandbox blocks `maven.neoforged.net` — 403 Forbidden on
`neoform-runtime` POM). Static review performed instead: arithmetic walked
for the no-law/no-stall case (bit-identical to pre-refactor), imports
reconciled, all rewired call sites confirmed in-scope of their variables.

### Smoke-test plan (user-executable)

1. Right-click a market merchant; trade screen opens, buy/sell prices match
   pre-refactor values **except** where a village pricing law is active
   (now reflected — intended change #1).
2. Set a custom price on a stall holding an item; both a player trade and an
   NPC market buy now reflect it (previously ignored — intended change #2).
3. Trigger an NPC market buy (`BuyGoodsBehavior`); prices via the unified
   function, behaves as before.
4. Cross-kingdom player trade still applies the tariff; reputation discount
   still applies — unchanged.
5. WANDERING_TRADER screen unchanged (base pricing).
6. Logs: no NPE, no double-applied modifier; prices stable across repeated
   quotes for unchanged supply/demand.

---

## Phase 1b — Unified settlement (one money-movement path, both paths)

**Goal:** consolidate coin settlement into one place. Before 1b the player
path hand-rolled coin movement in `TradeHandler` (never calling
`NpcEconomy`, never paying market tax) while the NPC path settled via
`NpcEconomy.marketPurchase` plus a tax bolted onto `MarketChannel`. Money
movement only — goods movement (chest take/store, player-inventory
delivery) stays per-caller until 2c.

### What shipped

New, in `Village/Economy/Currency/`:
- `SettlementParty` — a sealed interface (transient, no codec) modelling one
  side of a money transfer: `Player` (physical coins via `CoinHelper`),
  `Npc` (virtual `NpcWallet`), `StallChest` (player-owned stall chest,
  receive-only), `None` (mint source / burn sink). Factories
  `player/npc/stallChest/none`.

Added to `NpcEconomy` (the one money path both callers use):
- `settlePurchase(buyer, endpoint, amount, village, level, data)` — debits
  buyer, credits endpoint, applies the village market tax once. Returns
  false (nothing moved) if the buyer can't pay.
- `settleSale(payer, receiver, amount, level)` — payer pays receiver, no
  market tax (preserves: sales were untaxed on both paths).
- `resolveStallEndpoint(level, stall, merchantFallback)` — stall owner
  wallet / player-stall chest / merchant fallback / none.
- private `debit` / `credit` / `depositToStallChest` / `firePaymentVisual`
  / `applyMarketTax`.

Rewired (convert-then-delete):
- `TradeHandler` buy branch → take goods, then `settlePurchase`. Sell branch
  → `settleSale`. Deleted `forwardPaymentToStallOwner` (its NPC-owner arm is
  now `resolveStallEndpoint`; its player-stall-chest arm is now
  `NpcEconomy.depositToStallChest`). Added `returnToStallChest` for the
  settle-failure goods rollback.
- `MarketChannel.executeBuy` → `settlePurchase` (inline tax block removed).
  `executeSell` → `settleSale`.
- Deleted `NpcEconomy.marketPurchase` (its only caller was `executeBuy`).

### Settlement-party abstraction

A buyer/payer or receiver/endpoint is one `SettlementParty`. `debit`
switches over it (Player→`playerPay`, Npc→wallet `spend`, StallChest→false
(stalls never pay), None→true (mint)); `credit` switches over it
(Player→`playerReceive`, Npc→wallet `receive`, StallChest→deposit coins to
chest, None→burn). Purchase endpoints are resolved by
`resolveStallEndpoint`; sale payer/receiver are chosen by the caller.

### Current-vs-unified map

| Path / dir | Today | Unified |
|------------|-------|---------|
| Player BUY | `playerPay` debit; on take: stall→`forwardPaymentToStallOwner` (NPC owner wallet / player chest) else merchant `getWallet().receive`; **no tax** | take goods → `settlePurchase(Player, resolveStallEndpoint\|merchant, …)` → debit player, credit endpoint, **+market tax** |
| Player SELL | `merchant.getWallet().spend` + `playerReceive`; no tax | `settleSale(Npc(merchant), Player, …)` → debit merchant, credit player; no tax |
| NPC BUY | `marketPurchase` (buyer wallet→owner/merchant/void) + inline tax in `MarketChannel` | `settlePurchase(Npc(buyer)\|None, resolveStallEndpoint, …)` → debit, credit, +tax |
| NPC SELL | `seller.getWallet().receive` (mint) + `postListing` | `settleSale(None, Npc(seller), …)` (mint) ; `postListing` stays in channel |

### Intended behaviour change (for Garrett's approval)

1. **Player BUYS now pay the village market tax (10%, scaled by
   `LawTaxHooks.marketTaxMultiplier`).** Previously only NPC buys were
   taxed; the player path skipped it. The tax is minted into the village
   treasury exactly as the NPC path always did — buyer still pays full
   price to the merchant/stall; the treasury slice is added on top. Sells
   remain untaxed on both paths (unchanged). **Tax applies exactly once**:
   it now lives only in `settlePurchase`; the inline `MarketChannel` tax
   block was deleted (convert-then-delete, no double-tax). On a failed
   purchase (buyer can't pay) no tax is taken — `settlePurchase` returns
   before taxing, matching the old `marketPurchase`-fails path.
2. **NPC buys from a PLAYER-owned stall now deposit coins into the stall
   chest** instead of the old no-op that silently fell through to paying
   the merchant. (Prompt-mandated fix; player-path stalls are always
   merchant-NPC-owned so this only changes the NPC path.)

Everything else is preserved bit-for-bit (same wallets debited/credited,
same amounts, same NPC payment visuals for NPC→NPC and NPC→void).

### Tie-In Audit

1. **Upstream feeders.** Price comes from 1a (`MarketPricing`, passed in as
   `pricePerUnit`/`pricePerItem`); buyer/endpoint identities from the trade
   context (player + merchant + stall). The market-tax sink is
   `Village.depositToTreasury` scaled by `LawTaxHooks.marketTaxMultiplier`
   — the same sink the NPC path already used. **Note:**
   `VillageTreasury.collectMarketTax` (referenced in the prompt) has **no
   live callers** — it belongs to the separate `VillageTreasury` ledger
   used by `VillageSimEngine`/`RequestSettlement`, not the live trade tax.
   Using it would have *changed* NPC behaviour, so 1b deliberately keeps
   `Village.depositToTreasury` (see Deviations).
2. **Downstream callers re-pointed / dispositioned:**
   - `NpcEconomy.marketPurchase` → **deleted**; sole caller (`executeBuy`)
     now calls `settlePurchase`.
   - `TradeHandler.forwardPaymentToStallOwner` → **deleted**; folded into
     `resolveStallEndpoint` + `depositToStallChest`.
   - `CoinHelper.playerPay/playerReceive` in `TradeHandler` main branches →
     now reached only through `settlePurchase`/`settleSale`. The
     WANDERING_TRADER fast-path keeps its own direct `CoinHelper` calls
     (out of scope).
   - `merchant.getWallet().receive/spend` inline in `TradeHandler` →
     removed (now via the helper).
   - `MarketChannel` inline tax → removed.
3. **Sibling systems.** `MarketChannel.executeSell`'s
   `VillageEconomy.postListing` **stays in the channel** (listing posting,
   not money) so settle stays money-only — flagged. `BuyGoodsBehavior`
   (NPC buyer) is unchanged and still works end-to-end (it calls
   `channel.execute` → `executeBuy` → `settlePurchase`).
4. **Exhaustive switches.** `SettlementParty` is a new sealed type; the
   only switches over it are `debit` and `credit` in `NpcEconomy`, both
   exhaustive over all four permitted records with no `default`.
   `instanceof` checks in `firePaymentVisual` are not exhaustive switches.

### Simplification Sweep

- One money path: deleted `marketPurchase`, `forwardPaymentToStallOwner`,
  the inline `MarketChannel` tax, and the hand-rolled `TradeHandler` coin
  ops — all replaced by `settlePurchase`/`settleSale`.
- Reused the existing stall-chest deposit logic verbatim (moved, not
  rewritten). Removed unused `BlockPos` import and unused `level`/`data`
  params from the settle primitives.

### Deviations from prompt

- **Tax sink:** used `Village.depositToTreasury` + `LawTaxHooks`
  (the live NPC-path mechanism) rather than the prompt-suggested
  `VillageTreasury.collectMarketTax`, which has no live callers and feeds a
  different ledger. This is required to "preserve behaviour exactly" for
  the NPC path; documented in the Tie-In Audit.
- **Ordering:** the player buy now takes goods *before* settling (was: pay
  first, then take). Net-identical because quantity is pre-capped to player
  wealth so the debit cannot fail; on the (unreachable) failure path goods
  are restored via `returnToStallChest` / main-chest store. This makes the
  rollback trivial (no money moved yet) and removes the old
  pay-then-refund denomination reshuffle on the main-chest-take-fail path.

### Out-of-scope but flagged

- Goods/inventory consolidation → 2c (stays per-caller).
- `VillageEconomy.postListing` relocation → flagged, kept in channel.
- WANDERING_TRADER settlement → untouched (still burns coins).
- 1c (inter-village price differentiation) / 1d (balance externalization).
- `VillageTreasury.collectMarketTax` vs `Village.depositToTreasury` are two
  parallel treasury mechanisms; reconciling them is out of scope.

### Preflight

- `SettlementParty` sealed type added; switches (`debit`/`credit`)
  exhaustive. ✓
- No new persisted fields; the party type is transient (no codec). ✓
- No new per-tick logging. ✓
- Money movement lives in one place (`NpcEconomy`); `TradeHandler` and
  `MarketChannel` call it; inline tax + hand-rolled coin ops deleted. ✓
- Tax applied exactly once (only in `settlePurchase`); player coin in/out
  unchanged except the intended treasury tax slice. ✓

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (no cached
`neoform-runtime` for offline mode). Static review performed: all
`settlePurchase`/`settleSale`/`resolveStallEndpoint` call sites checked
against signatures and variable scope; switch exhaustiveness over the
sealed `SettlementParty` confirmed; behaviour walked per the current-vs-
unified map (preserved except the two enumerated changes).

### Smoke-test plan (user-executable)

1. Player buys from a merchant: coins leave player, endpoint credited, and
   — intended — the village treasury collects the 10% tax (check via
   economy debug / treasury balance).
2. Player sells to a merchant: player paid, merchant wallet debited, as
   before (no sell tax).
3. Buy from an NPC-owned stall and a PLAYER-owned stall: NPC owner wallet
   credited; player-owned stall now receives coins in its chest (was a
   no-op).
4. NPC-to-NPC market buy (`BuyGoodsBehavior`): settles via the helper, tax
   applied once (not zero, not twice).
5. Insufficient funds (player and NPC): graceful — no partial settle, no
   item granted without payment (purchase aborts before taxing/crediting).
6. WANDERING_TRADER trade still burns coins as before (untouched).
7. Logs: no double-tax, no double-spend, no NPE in the stall-chest deposit.
