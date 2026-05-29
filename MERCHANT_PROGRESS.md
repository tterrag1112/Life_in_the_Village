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

---

## Phase 1c — Inter-village price differentiation

**Goal:** make the same good cost meaningfully different amounts in
different villages, driven by each village's structural surplus/deficit,
so a merchant can buy where a good is abundant and sell where it's scarce
(arbitrage — the economic reason travelling merchants exist, Phase 4).
Prerequisite built first: a canonical `Item → ResourceCategory`
classifier (none existed).

### What shipped

New: `Village/Simulation/ItemResourceClassifier.java` — the single
`Item → ResourceCategory` source of truth. Code-based for 1c (item tags +
the FOOD component + path-substring fallbacks, most-specific first),
returning `null` for the neutral/UNKNOWN case. 1d will swap this static
surface for data.

Modified:
- `DynamicPriceCalculator.computeMultiplier` — now classifies the item
  once, uses the classifier for the FOOD/BUILDING_MATERIALS spot-demand
  reads (replacing the deleted `isFoodItem`/`isMaterialItem`), and
  multiplies in a new `perVillageModifier` before the `[0.5,3.0]` clamp.
  Added named constant `PER_VILLAGE_STRENGTH = 0.4`.
- `VillageNeedsCalculator` — material membership check now calls the
  classifier; the private `BUILDING_MATERIAL_ITEMS` set was moved into
  the classifier verbatim and deleted here.

### Classifier design

`classify(Item) → ResourceCategory|null`, ordered most-specific first:
SEEDS (path `seed`) → FOOD (`DataComponents.FOOD`) → WEAPONS (SWORDS/
ARROWS tags, bow/crossbow/trident, armour path) → TOOLS (AXES/PICKAXES/
SHOVELS/HOES tags, shears, fishing rod, flint&steel) → CLOTH (WOOL/
WOOL_CARPETS tags, string, leather) → PAPER → LITURGICAL → MEDICINE →
LUXURY (gem set) → BUILDING_MATERIALS (LOGS/PLANKS tags ∪ legacy item set
∪ path log/planks/stone/cobblestone/ingot/glass/brick) → `null`.

Two invariants make the consolidation behaviour-preserving:
- **FOOD ≡ has FOOD component** — exactly the legacy `isFoodItem`, so the
  food spot-demand read and food needs are unchanged.
- **BUILDING_MATERIALS ⊇ both legacy heuristics** — superset of the old
  path heuristic and the old explicit set. `VillageNeedsCalculator` only
  tallies `available` for items that are in `required` (from
  `UpgradeRequirements`, all structural blocks), so a superset cannot
  change which required items are counted → **needs output unchanged**.

It does **not** switch over `ResourceCategory` (it maps items via
tags/path; the modifier reads `net(cat)` generically), so there is no
exhaustive-switch obligation. It never returns `COIN_INFLUX`.

### Per-village modifier formula + reconciliation

`perVillageModifier = 1 − PER_VILLAGE_STRENGTH × ratio`, where
`ratio = (production − consumption) / (production + consumption) ∈ [−1,+1]`
read from `VillageSimData.getSimData(villageId)` for the item's category.
Exporter (`ratio > 0`) → modifier `< 1` (cheaper); importer (`ratio < 0`)
→ `> 1` (dearer). The ratio normalisation makes it scale-independent
across categories (FOOD net ±40/day vs MEDICINE ±2/day map to the same
[−1,+1]). At STRENGTH 0.4 the modifier ∈ [0.6, 1.4].

**Reconciliation (no double-count): which signal owns what.**
- *Spot stockpile count* → immediate physical availability at this market
  right now.
- *Spot need level* (FOOD/BUILDING_MATERIALS only) → this-moment urgency.
- *Sim net (new)* → the village's rolling production-vs-consumption
  **character** — the structural arbitrage driver, and the only signal
  covering the other 10 categories that have no spot-need reading.
These measure different things on different timescales (snapshot vs daily
rolling), so applying all three is complementary, not a re-measurement of
one quantity; correlation (an exporter usually also has full stock) is
intended and bounded by the single `[0.5,3.0]` clamp. The modifier sits
inside `computeMultiplier` (the dynamic layer of the 1a pipeline) so all
three fold into one multiplier and one clamp.

### Arbitrage-viability target

Profit needs `importer_buyPrice > exporter_sellPrice`. With the ×0.6 buy
margin and the combined modifiers reaching the clamp: a strong importer's
buy ≈ `base × 1.4 × scarcity × 0.6 ≈ 1.26 × base`; a strong exporter's
sell ≈ `base × 0.6 × oversupply ≈ 0.5 × base`. Gap ≈ `0.75 × base`/unit
(~+150% markup) — comfortably above plausible transport cost/effort.
Strength is the named constant `PER_VILLAGE_STRENGTH` (1d externalises).

### Flagged price changes (for Garrett's approval)

1. **All physical goods are now village-specific.** Large, intended
   change: every classifiable tradeable good is cheaper in villages that
   structurally produce it and dearer in villages that consume it,
   visible on both player and NPC trades.
2. **Minor reclassification side-effects** in the *demand-read* path
   only: gravel/sand and brick items now count as BUILDING_MATERIALS for
   the material spot-demand read (they didn't under the old path
   heuristic), and stone tools that the old path heuristic mis-counted as
   material now classify as TOOLS. Food classification is byte-identical.
   Village needs output is unchanged (superset argument above).
3. Goods of an unclassified/neutral category (`null`) and goods the
   village neither produces nor consumes get a neutral 1.0 — same price
   everywhere, as before.

### Tie-In Audit

1. **Upstream feeders.** `VillageSimData` (daily-refreshed, O(1) read via
   `VillageSavedData.getSimData`) + the new classifier. No new persisted
   state.
2. **Downstream callers.** The modifier lives in `computeMultiplier`, so
   it flows through `getDynamicSellPrice`/`getDynamicBuyPrice` and is
   inherited by **everything**: the 1a `MarketPricing` pipeline (player
   `TradeHandler` + NPC `MarketChannel`/`BuyGoodsBehavior`), plus the
   other channels (Caravan/Stockpile/DirectBusiness), `VillageEconomy`
   listings, and `MerchantBehavior`. All now see per-village prices.
3. **Sibling systems.** `CultureEconomicNorms.categoryDemandMultiplier`
   is a per-*culture* skew with **no callers** today — left untouched and
   not conflated with this per-*village* modifier. `VillageNeedsCalculator`
   shares the classifier post-consolidation; its output is unchanged.
4. **Exhaustive switches.** None over `ResourceCategory` exist in the
   codebase (the `TradeAvailability` switch is over a different Layer3
   `Category`). The classifier adds none.

### Simplification Sweep

Deleted `DynamicPriceCalculator.isFoodItem`/`isMaterialItem` and
`VillageNeedsCalculator.BUILDING_MATERIAL_ITEMS`; both call the one
classifier now. Removed the now-unused `NeedLevel` import.

### Deviations from prompt

- **Modifier placement.** Put it inside `DynamicPriceCalculator.compute
  Multiplier` (the dynamic layer of the 1a pipeline) rather than as a
  standalone `MarketPricing` step. It is still "in the unified pipeline"
  (MarketPricing layers 1–2 *are* `getDynamicSellPrice`), it reconciles
  with the existing supply/demand signals in one multiplier + one clamp,
  and it makes *every* pricing caller inherit per-village prices — broader
  than a MarketPricing-only step and matching the Tie-In intent.
- **UNKNOWN as `null`.** The classifier returns `null` for the neutral
  case instead of adding an `UNKNOWN` constant to `ResourceCategory`,
  avoiding any change to the sim enum / its CODEC and keeping the
  refactor's blast radius bounded. `null` is treated as neutral
  everywhere.

### Out-of-scope but flagged

- Data-externalising the classifier + strength constant (1d).
- Structural wealth (`getTreasuryEstimate`/`getNetIncomePerDay`) and
  remoteness (kingdom distance) modifiers — future tuning layers, not
  built.
- Caravan need-dispatch / arbitrage *behaviour* (Phase 4) — 1c only makes
  prices differ.
- WANDERING_TRADER pricing — untouched; its fast-path prices off base
  only and does not call `getDynamic*`, so it is unaffected by per-village
  pricing (correctly stays flat).

### Preflight

- Classifier does not switch over `ResourceCategory`; no arms to maintain.
- No new persisted fields on `VillageSimData` (modifier computed,
  classifier static). ✓
- No per-tick code — `getSimData` is an O(1) daily-cached read. ✓
- Heuristics consolidated into the classifier and deleted from their old
  homes. ✓
- Prices vary by village in the intended direction (exporter cheaper,
  importer dearer) at the flagged arbitrage magnitude. ✓

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (no cached
`neoform-runtime`). Static review: classifier uses only tags/items/
components proven present in the codebase (`ItemTags.PLANKS/AXES/...`,
`item.getDefaultInstance().is(tag)`, `Items.NETHERITE_INGOT` etc.);
`VillageSimData.production/consumption/getSimData` signatures confirmed;
superset argument walked for unchanged needs output; imports reconciled.

### Smoke-test plan (user-executable)

1. Find/create two villages where the sim shows one a strong **exporter**
   (net surplus) and the other a strong **importer** (net deficit) of the
   same good (economy debug command).
2. Compare the good's buy/sell price in each: exporter noticeably lower,
   importer noticeably higher; confirm the gap is worth a trip.
3. A good neither produced nor consumed (neutral/`null`) prices the same
   in both villages.
4. Player and NPC buyers both see the per-village price (shared dynamic
   layer).
5. `VillageNeedsCalculator` food/material/seed needs compute as before
   (classifier consolidation is behaviour-preserving).
6. No per-tick recompute; no NPE when a village has no sim data yet
   (fresh village → neutral 1.0); prices stable across repeated quotes
   within a day.

---

## Phase 1d — Externalize economy balance knobs to data

**Goal:** move the hardcoded economy/pricing/trade constants (including
the 1b market-tax divisor and the 1c per-village strength) into a
data-driven config so tuning needs no recompile — mirroring the
`MarketPriceRegistry` datapack reload-listener pattern. **Behaviour-
preserving plumbing only:** baked defaults reproduce today's values
exactly; with no config file present the game is identical to pre-1d.

### What shipped

New, in `Village/Economy/Currency/`:
- `EconomyBalance` — immutable config holder, grouped into nested typed
  sub-records (`Treasury`, `Pricing`, `Markups`, `Channels`), each with a
  `DEFAULT` and a codec using `optionalFieldOf` per field (so a missing
  file / section / single field all fall back). `DEFAULTS` = today's
  values. No sub-record exceeds 7 fields (16-field ceiling respected).
- `EconomyBalanceRegistry` — `SimplePreparableReloadListener<EconomyBalance>`
  singleton mirroring `MarketPriceRegistry`: loads `data/<modid>/
  economy_balance/*.json`, seeds `current` to `DEFAULTS`, exposes
  `get()` / `balance()`. Registered in `ModModEvents.onAddServerReload
  Listeners` (so `/reload` re-reads) **and** loaded in `onServerStarting`
  (early load, like `MarketPriceRegistry`).

### Constant inventory (file:line → config field, default)

**Treasury / tax / wages** (`VillageTreasury`, was `public static final`):
- `MARKET_TAX_DIVISOR=10` → `treasury.market_tax_divisor`. Also the 1b
  live tax literal `10.0` in `NpcEconomy.applyMarketTax` now reads it.
- `PROPERTY_TAX_PER_HOUSE=2` → `treasury.property_tax_per_house`.
- `BASELINE_INCOME_PER_NPC=1` → `treasury.baseline_income_per_npc`.
- `GUARD_WAGE=8`, `KEEPER_WAGE=5`, `INNKEEPER_WAGE=4` → `treasury.*_wage`.
- `TreasuryTickHandler.MERCHANT_WAGE=6` → `treasury.merchant_wage` (the
  live wage path; had no `VillageTreasury` twin).

**Pricing** (`DynamicPriceCalculator`):
- `MIN_MULTIPLIER=0.5` → `pricing.min_multiplier`.
- `MAX_MULTIPLIER=3.0` → `pricing.max_multiplier`.
- buy margin `0.6` (was an inline literal) → `pricing.buy_margin`.
- `PER_VILLAGE_STRENGTH=0.4` (1c) → `pricing.per_village_strength`.

**Markups** (`VillageEconomy`):
- `PRODUCER_MARKUP=0.85`, `MERCHANT_MARKUP=1.20`, `STOCKPILE_MARKUP=1.00`,
  `COMPANY_MARKUP=1.10` → `markups.{producer,merchant,stockpile,company}`.

**Channels:**
- `CaravanChannel.CARAVAN_DISCOUNT=0.90` → `channels.caravan_discount`.
- `DirectBusinessChannel` relationship band `0.05` → `channels.direct_
  relationship_band`; generosity band `0.125` → `channels.direct_
  generosity_band`.

### `static final` read-site migration

Every externalized `static final` (which the compiler may inline) was
converted from a *declaration* to a *live read* through the config:
- `VillageTreasury` constants → `public static` accessor **methods**
  (`marketTaxDivisor()`, `guardWage()`, …). Read sites repointed:
  `VillageTreasury.collectMarketTax`, `NpcEconomy.applyMarketTax`,
  `VillageSimEngine` (×3), `TreasuryTickHandler` (×4 + the unloaded-village
  guard-wage drain).
- `DynamicPriceCalculator` MIN/MAX/buyMargin/perVillageStrength → inline
  `EconomyBalanceRegistry.balance().pricing()` reads.
- `VillageEconomy` markups → `getMarkup` + the company-listing path read
  `markups()` live.
- `CaravanChannel`/`DirectBusinessChannel` → read `channels()` live.

No externalized value remains as a `static final` that is read anywhere.

### Risky / kept-in-code (flagged for Garrett)

- **Channel `basePriority`** (MARKET 100, DIRECT_BUSINESS 70, CARAVAN 50,
  VISITOR 45, GUILD_REQUEST 40, STOCKPILE 30) — these change router
  **tiebreak ordering**, not just magnitudes; externalizing risks subtle
  routing changes. **Kept in code.**
- **`DynamicPriceCalculator` supply-band steps** (2.0/1.5/0.85/0.7 at
  thresholds 16/64/128) and the **NeedLevel demand steps** (food
  2.5/1.5/1.0/0.8, materials 2.0/1.4/1.0/0.9) — response-curve *shape*,
  numerous (~16 values); low individual tuning value. **Kept in code**;
  externalize in a later tuning pass if wanted.
- **Starter treasury funds** — `VillageTreasury.create(starterFunds)` has
  **no live callers** (dead factory), so there is no current creation path
  to wire to a config field. **Not externalized**; flagged (smoke #5
  cannot be exercised until a creation path exists).

### Config mechanism + schema

Datapack JSON + reload listener (chosen over NeoForge TOML) for
consistency with `MarketPriceRegistry` and so `/reload` works in-session.
Kept as a **sibling** of `market_prices/` (prices and tuning knobs stay
separable). Schema (all fields optional; shown with defaults):

```json
{
  "treasury": { "market_tax_divisor": 10, "property_tax_per_house": 2,
    "baseline_income_per_npc": 1, "guard_wage": 8, "keeper_wage": 5,
    "innkeeper_wage": 4, "merchant_wage": 6 },
  "pricing":  { "min_multiplier": 0.5, "max_multiplier": 3.0,
    "buy_margin": 0.6, "per_village_strength": 0.4 },
  "markups":  { "producer": 0.85, "merchant": 1.2, "stockpile": 1.0,
    "company": 1.1 },
  "channels": { "caravan_discount": 0.9, "direct_relationship_band": 0.05,
    "direct_generosity_band": 0.125 }
}
```
Place at `data/life_in_the_village/economy_balance/economy_balance.json`.

### Tie-In Audit

1. **Upstream feeders.** `EconomyBalanceRegistry` is the single source;
   datapack reload (`AddServerReloadListenersEvent`) + the ServerStarting
   early load are the refresh triggers (mirrors `MarketPriceRegistry`).
2. **Downstream callers.** Every externalized constant's read sites were
   repointed (enumerated above); each old `static final` declaration is
   removed. The 1b unified-settle tax read and the 1c per-village modifier
   read both now read the config.
3. **Sibling systems.** Item prices stay in `market_prices/*.json`; the
   balance config is a separate file. No overlap.
4. **Exhaustive switches.** None added (no config enum).

### Simplification Sweep

- **Unified a duplicate:** `TreasuryTickHandler` had its own private
  `GUARD_WAGE/KEEPER_WAGE/INNKEEPER_WAGE` (=8/5/4) duplicating
  `VillageTreasury`'s — the *live* wage path used the local copy while
  `VillageSimEngine` used `VillageTreasury`'s. Both now read the one
  config; the duplicates are deleted.
- Removed all the now-dead `static final` economy constants from their
  five home classes.

### Deviations from prompt

- **Scope of pricing externalization.** Externalized the four headline
  pricing knobs (min/max/buyMargin/perVillageStrength) but **kept the
  supply-band and demand-level step values in code** (flagged) to keep
  the schema readable and the migration bounded — the prompt's list is a
  "starting set" and sanctions flagging.
- **Starter funds not wired** — no live `create(...)` caller exists
  (flagged), so smoke #5 is not exercisable yet.
- **`basePriority` kept in code** (routing risk; prompt-sanctioned).

### Out-of-scope but flagged

- Changing any value (defaults equal today's numbers; re-tuning is a
  later pass now that the knobs exist).
- Non-economy constants. WANDERING_TRADER coin-burn. The flagged
  kept-in-code items above.

### Preflight

- Schema grouped/nested (4 sub-records, ≤7 fields each). ✓
- Reload listener registered like the others (`AddServerReloadListeners
  Event`); `/reload` re-reads. ✓
- Every externalized constant: read site moved, not just the declaration;
  no inlinable `static final` left feeding stale values (grep: 0 refs to
  all old constant names). ✓
- Reads are per-trade / per-tick-event (wages, tax, markups), not
  hot-loop; accessor is a volatile field read — no concern. ✓
- Defaults match the enumerated current values one-for-one. ✓

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (no cached
`neoform-runtime`). Static review: codec uses `optionalFieldOf` per field
against `DEFAULT`s; parse/error handling mirrors `CastleStyleLoader`
(`resultOrPartial`); reload registration mirrors the existing listeners;
all old constant names return 0 grep hits (every read site migrated);
accessor return types match the literal types they replace (long/double),
so arithmetic is unchanged.

### Smoke-test plan (user-executable)

1. With **no** `economy_balance.json`, confirm prices/taxes/wages/markups/
   channel behaviour are identical to pre-1d (defaults).
2. Author the file overriding one knob (e.g. `treasury.market_tax_divisor`
   or `pricing.per_village_strength`), `/reload`, confirm the change takes
   effect at the next trade without a restart.
3. Malformed/partial config: missing fields fall back per-field; a
   structurally-bad file logs once and keeps defaults (no crash).
4. Spot-check a wage, a pricing band, and the caravan discount read the
   configured value, not a stale inlined constant.
5. (Starter treasuries) — N/A this pass: no live creation path; flagged.
6. Logs: one load line on reload; no per-tick spam; no NPE on first
   access before the first reload (seeded to DEFAULTS).
