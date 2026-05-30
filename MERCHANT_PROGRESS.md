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

---

## Phase 2a — Market complex spec + bounded region + prepared pad + V2 hook

**Goal (worldgen only):** make a MARKET generate as a "market complex" —
a bounded claimed region around the market building, graded to a flat
prepared pad surfaced with the culture's path palette. **No stalls.**
Deliverable: generate a village, see a flat market pad around the market.

### What shipped

New, in `Village/Buildings/Complex/` (sibling to the farm spec/registry):
- `MarketAisleModel` — enum, only `PERIMETER` (typed scaffold for 2b).
- `MarketComplexSpec` — market-shaped record (padMargin, minPadMargin,
  aisleModel, nullable padBlockId, stallPool stub). **Not** an extension
  of the farm-shaped `BuildingComplexSpec`.
- `MarketComplexRegistry` — culture×BuildingType registry mirroring
  `BuildingComplexRegistry`; registers `default × MARKET`
  (padMargin 4, minPadMargin 1, PERIMETER, culture-path surface).

New, in `Village/Markets/Complex/`:
- `MarketComplexPlanner` — pure planner. Computes a bounded rectangle
  around the placed MARKET (footprint ± margin), collision-checks against
  caller-supplied obstacle rects, shrinks uniformly to `minPadMargin`,
  then skips. Returns region + buildingBounds polygons + padY + margin.
- `Render/MarketComplexRenderer` — grades the region flat to padY, paints
  the culture path palette via `PathRenderer.resolveRoadMaterial`, knocks
  out the building footprint, fully null-defensive.

Wired in `V2VillageSpawnerAdapter`: capture placed MARKETs
(`PlacedMarket`, mirroring `PlacedFarmhouse`); a post-farm-loop market
loop builds obstacles (other building AABBs + farm regions), plans,
renders. Each market in try/catch — one failure logs and continues.

### Tie-In Audit

1. **Upstream feeders.** MARKET is a V2 nucleus building; its placed
   `PlacedBuilding` (footprint width/length, centre, padY) is captured in
   the building loop. Footprint dims come from the real placed building
   (which `StructureSizeCache` ultimately feeds), so the pad scales off
   the actual NBT — no hardcoded market size. The placed-MARKET set is
   available at the post-loop point (captured inline, like farmhouses).
2. **Downstream callers.** Nothing consumes a market complex yet. The pad
   render only grades terrain around the building and **knocks out the
   building footprint** — it does not touch the MARKET building, its
   storage, or its merchant. `MarketChannel` / market lookups resolve the
   MARKET building unchanged (no building-side change at all).
3. **Sibling systems — collision (the big risk).** The pad collision-
   checks against every other placed building's footprint AABB (inflated
   1 block) **and** existing farm complex regions, and **shrinks uniformly
   then skips** — a market that can't claim a pad renders no pad (building
   still works), never stomps a neighbour. Roads are intentionally not
   collision-checked: the pad uses the same culture path palette, so any
   overlap is the same block (cosmetically continuous plaza↔road).
   Frontage strips / adjunct plots are not separately checked — they hug
   their parent building (covered by the building-AABB + buffer) and are
   peripheral; low risk, flagged.
4. **Exhaustive switches.** `MarketAisleModel` added with one value
   (`PERIMETER`); no `switch` over it exists (the pad render is aisle-
   agnostic — aisles are 2b). Nothing to maintain.

### Simplification Sweep

- A clean `MarketComplexSpec`/`MarketComplexRegistry` sibling rather than
  generalizing `BuildingComplexSpec`. Markets are the *second* complex
  consumer, so the project rule (abstractions only when a second concrete
  consumer forces it) would now *permit* a shared base — but the two
  specs share almost no fields (farm: flood-fill/plots/borders; market:
  bounded margin/aisle), so a forced common base would be hollow.
  **Flagged, not built**: if a third domain (forge yard, sacred grove)
  arrives, revisit a per-domain registry abstraction then.
- Reused `PathRenderer.resolveRoadMaterial` for the palette (same block
  the roads + farm paths use) rather than a new resolver.

### Deviations from prompt

- **No persistence in 2a.** The prompt says "persist it the way the farm
  complex persists its envelope/record," but the market region is a
  **deterministic** rectangle (footprint + spec margin), unlike the
  farm's terrain-dependent flood-fill which *must* persist to be
  reproduced. Nothing consumes a market complex in 2a, and the preflight
  scopes the diff to "the new market-complex spec/registry/planner/
  renderer + the one adapter loop" — adding a `VillageSavedData` codec
  slot would exceed that. So 2a renders the pad transiently; 2b will
  persist a market-complex record (or recompute the region from the spec)
  when stall allocation becomes a real consumer. Flagged for 2b.
- **Uniform shrink, not per-side clip.** The pad shrinks symmetrically
  before skipping. Per-side clipping (clip only the colliding edge) would
  yield larger pads in tight cores; deferred as a 2b/tuning follow-up.
  Uniform shrink is the safest "never stomp" rule for 2a.
- **padY = placed building centre Y** (the V2 ground/pad Y, one below the
  building floor), so the pad surface sits flush with the building floor.

### Out-of-scope but flagged

- Stall pool / allocator / stall NBTs / signs / occupancy (2b). The
  `stallPool` field is an empty typed stub; `MarketAisleModel.PERIMETER`
  is the 2b aisle model.
- Deleting the old `MarketStallPlacer` / `setupMerchantStalls` /
  `facingRotation` path — left intact; 2b removes it once the replacement
  exists.
- Market-complex persistence (see Deviations) — 2b.
- Any economy / inventory / settlement change.

### Preflight

- New enum (`MarketAisleModel`, 1 value): no exhaustive switch exists. ✓
- No new persisted record/field (see Deviations — deterministic, deferred
  to 2b); no `VillageSavedData` codec change. ✓
- No per-tick code — runs once at spawn in the post-loop. ✓
- Diff confined to the new market-complex spec/registry/enum + planner/
  renderer + the one adapter loop; existing MARKET placement untouched. ✓

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (no cached
`neoform-runtime`). Static review: planner is pure (no world/persistence);
`Polygon.AABB` constructor/accessor order verified; `Building.getShape()
.toAABB()` returns the net AABB (fields floored/ceiled to an inclusive
int rect); renderer reuses the proven `PathRenderer.resolveRoadMaterial`
+ surface/air/fill column pattern from the road/farm painters;
`getMinY()` confirmed present (upper-bound guard dropped to avoid an
unverifiable `getMaxY()` call); adapter loop mirrors the farm loop's
try/catch-and-continue shape.

### Smoke-test plan (user-executable)

1. Generate a fresh village with a MARKET; locate the market building.
2. Confirm a flat, path-palette pad is graded around it, knocking out the
   building's own footprint, sized sensibly off the building.
3. Confirm the pad didn't clip adjacent buildings (collision works); in a
   dense core, confirm a smaller pad or none — never a stomped neighbour
   (log line "market pad skipped … NO_REGION").
4. Confirm the MARKET building, its storage, and its merchant still work
   (right-click trade opens) — the building path is untouched.
5. Generate several cultures; confirm pads appear and the palette matches
   each culture's roads.
6. Logs: per-market "market pad rendered (margin=N)" or a clean skip
   reason; no exception aborts the spawn.

---

## Phase 2b — Stall pool + allocator + aisle-facing rotation (safe core)

**Goal:** make stalls actually appear — a stall pool, a runtime allocator
that fits them onto the 2a pad, aisle-facing rotation, a few seeded at
spawn, and removal of the dead fixed-anchor stall path.

**Scope decision (important).** The 2c/2d prompts confirm their only 2b
dependencies are: stalls *placed* with chests, `MarketStall` records, a
claim API, and work-post placement geometry — **not** the
`MARKET_STALL` `BuildingType` promotion or the manifest variant system.
In a no-compile sandbox, adding `MARKET_STALL` to `BuildingType` means a
blind exhaustive-switch audit across **60 files** (a single missed arm =
undetectable broken build), and the variant-system promotion also needs
binary NBT relocation + `manifest.json` authoring (asset work). Both are
**deferred** (flagged below); neither blocks 2c/2d. 2b ships the
allocator + seeding + claim rewire + dead-path removal as the safe,
additive core.

### What shipped

New, in `Village/Markets/Complex/`:
- `StallVariant(id, nbt, weight)` — typed pool entry (replaces 2a's
  `List<String>` stub). Footprint is read from the loaded template at
  allocation time, never hardcoded.
- `StallAllocator` — fits a stall onto the pad's perimeter band and
  places it **facing outward onto the aisle**. Seat search scans the
  region inset by the aisle, queries the stall's actual placed
  `BoundingBox` via `StructureTemplate.getBoundingBox(settings, origin)`,
  and accepts the first seat fully inside the inset region, entirely on
  one side of the building footprint (+gap), and clear of occupied
  boxes. First-fit; callers accumulate occupancy → no overlap → "market
  full" when nothing fits.
- `MarketStallSeeder` — seeds up to `SEED_COUNT` (4) vacant stalls onto a
  freshly graded pad in the V2 spawn hook (region geometry in hand → no
  runtime recompute).

Changed:
- `MarketComplexSpec.stallPool` → `List<StallVariant>`; registry default
  `padMargin` 6 / `minPadMargin` 2 (deeper band to host stalls) + the one
  authored stall NBT resolved by its **real** path
  (`default/rural/market/stall/stall_1`).
- `MarketStall` — added `VACANT_UUID` sentinel + `isVacant()` (a seeded,
  unclaimed "for rent" stall) **without** a new `OwnerType` arm or codec
  field.
- `V2VillageSpawnerAdapter` — after rendering the pad, seeds stalls.
- `MarketStallPlacer.claimSlot` — **rewired** to assign ownership to a
  vacant seeded stall (signature preserved → callers unchanged); the
  broken `STALL_TEMPLATE` path corrected (used by `reclaimStall` sizing).
- `VillageSpawner.setupMerchantStalls` — **deleted** (orphan, broken path).

### Allocator algorithm (pseudocode)

```
place(level, market, region, footprint, padY, variant, owner…, occupied):
  template = loadTemplate(variant.nbt)            # else empty
  seat = findSeat(template, regionAABB, footprintAABB, padY, occupied)
  if no seat: return empty                         # market full
  placeInWorld(seat.origin, seat.rotation)
  stall = MarketStall.create(…, seat.origin, owner…)
  stall.chestPos = first chest in seat.box
  occupied += seat.box; return stall

findSeat:
  inset = region shrunk by AISLE_WIDTH (the walkable perimeter ring)
  for side in [SOUTH, EAST, NORTH, WEST]:
    rot = rotationForOutward(side)                 # aisle-facing, below
    for origin in inset grid at padY+1:
      box = template.getBoundingBox(settings(rot), origin)
      accept iff box ⊆ inset AND box on `side` of footprint(+gap)
             AND box ∩ footprint(+gap) = ∅ AND box ∩ occupied = ∅
```

### Aisle-facing rotation derivation (replaces dominant-axis snap)

Stall NBT authored facing SOUTH (+Z). A seat on a side faces **outward**
on that side, so rotation comes from the *side*, never from a vector to
the building centre (the old `facingRotation` bug):
`SOUTH→NONE, WEST→CLOCKWISE_90, NORTH→CLOCKWISE_180, EAST→COUNTERCLOCKWISE_90`.
`onSide` guarantees the stall sits wholly on that side, so its front
opens onto the perimeter aisle.

### Claim-caller re-point

`claimSlot`'s signature is **unchanged**, so its callers
(`NpcInteractionHandler` ×2, `WorkshopStallDecisionGoal`,
`StallLeaseActionPacket`/lease) were **not** re-pointed — they keep
calling `claimSlot`, which now assigns a vacant seeded stall instead of
stamping an anchor. `findAnchorSlots` is kept (still read by
`MarketApproach` + the lease slot-count display); it is now vestigial for
stall placement and may return 0 if the market NBT has no STALL anchors —
cosmetic, flagged.

### Delete list

- `VillageSpawner.setupMerchantStalls` — **deleted** (orphan).
- `MarketStallPlacer.claimSlot` anchor/STALL_TEMPLATE-stamping body —
  **replaced** with the assign-vacant body.
- `MarketStallPlacer.findChestInRegion` — **deleted** (chest detection
  moved to `StallAllocator.findChestInBox`).
- `MarketStallPlacer.facingRotation` (dominant-axis) — **orphaned**; the
  Edit tooling couldn't match its unicode-comment body cleanly in this
  no-compile session, so it is left in place but **unused** (no callers;
  a warning, not a build error). Flagged for a trivial follow-up delete.

### `MARKET_STALL` enum switch audit

Not performed — `MARKET_STALL` was **not** added to `BuildingType` (see
Scope decision). No enum/switch surface touched. `OwnerType` unchanged
(vacancy uses a sentinel UUID, not a new arm).

### Tie-In / Simplification / Deviations

- **Tie-In.** Upstream: 2a region/pad (recomputed in the spawn hook),
  `StructureSizeCache`-backed footprints (via the loaded template).
  Downstream: `MarketStall` record shape unchanged → all readers
  unaffected; `claimSlot` callers unchanged. Sibling: `MarketApproach`/
  lease slot-count read `findAnchorSlots` (kept); `MarketRentManager`
  `reclaimStall` kept (STALL_TEMPLATE path now valid).
- **Simplification.** Placement logic consolidated into `StallAllocator`;
  the old anchor-claim + dominant-axis path removed from `claimSlot`.
  `MarketStallPlacer` survives as a thin shell (claim + reclaim + helpers).
- **Deviations:** (1) `MARKET_STALL` BuildingType + manifest variant
  system **deferred** (60-switch blind audit + binary asset authoring);
  the single stall NBT loads by direct path via the pool — the 7-step/
  manifest fallback isn't wired, acceptable with one variant. (2) Claim
  uses **assign-vacant** (seed at spawn, assign on claim) rather than
  runtime allocate-on-claim — simpler, and avoids needing to persist or
  recompute the pad region at claim time (the 2a region wasn't
  persisted). Runtime allocation still exists (`StallAllocator.place`)
  for 2d event stalls. (3) `facingRotation` left orphaned (tooling).
- **Out-of-scope but flagged:** per-stall inventory authority, sign
  lifecycle, work-post behaviour, rent/lease money (all 2c); event stalls
  (2d); WANDERING_TRADER.

### Build verification

Deferred — sandbox blocks `maven.neoforged.net`. Static review:
allocator uses `StructureTemplate.getBoundingBox`/`placeInWorld` +
`BoundingBox` accessors (vanilla); `MarketStall.create`/`addMarketStall`/
`getStallsForMarket` signatures confirmed; `claimSlot` contract preserved
for callers; no `BuildingType`/`OwnerType` enum change. Unused imports +
the orphaned `facingRotation` are warnings, not errors.

### Smoke test

1. Generate a village → a few stalls seeded on the market pad, flat, not
   overlapping each other or the building, fronts on the perimeter aisle.
2. Rotation faces the aisle per side (no dominant-axis backwards snap).
3. One authored NBT places everywhere via the pool (clean "not found"
   log only if the path is wrong).
4. NPC (`WorkshopStallDecisionGoal`) + player (lease) claim → a vacant
   seeded stall becomes owned (old anchor/STALL_TEMPLATE path gone).
5. Claims past the seeded count → "market full" (empty), no overlap.
6. No NPE from the deleted `setupMerchantStalls`; non-stall SubBuilding
   anchors untouched.

---

## Phase 2c — Stall inventory authority + sign funnel + work-post + rent/lease

**Goal:** the economic/lifecycle layer on 2b's stalls — make the per-stall
chest the authoritative goods endpoint (1b did money, 2c does goods), a
drift-proof owner-sign funnel, work-post data for Phase 3, and route
rent/lease money correctly.

### What shipped

New, in `Village/Markets/Complex/`:
- `StallGoods` — per-stall goods authority. `available` / `take` / `store`
  with **stall chest first, market hub as overflow/backstock**; a
  {@code null} stall = stall-less sale → hub. Consolidates chest take/
  store logic.
- `MarketWorkPost` — pure `WorkPost(stand, facing)` accessor: the vendor
  stands at the counter (chest, else origin) and faces **outward onto the
  perimeter aisle** (matching the 2b allocator's aisle-facing rotation).
  Data only — Phase 3 occupancy + 2d producers consume it.

New, in `Village/Economy/Market/`:
- `MarketStallOwnership` — the single funnel for owner changes:
  `assign` / `vacate` mutate ownership **and** rewrite the sign in the
  same call, so the sign can't desync.

Routed:
- `MarketChannel.executeBuy` — availability + take now go through
  `StallGoods` (stall chest first, then hub); restore-on-fail stores back
  to the same endpoint. `executeSell` stores via `StallGoods` (hub
  backstock). The 1b money endpoint (`resolveStallEndpoint`) and the 2c
  goods endpoint now resolve to the **same stall**.
- `MarketStallPlacer.claimSlot` — ownership assignment routed through
  `MarketStallOwnership.assign` (sign rewritten on claim).

### Inventory-authority model

Stall chest = authoritative for goods traded at that stall; market hub =
overflow/backstock. Buy: draw stall→hub. Sell: fill stall→hub. Stall-less
sale: hub. `MarketChannel` (was hub-only) now routes through this, closing
the "goods come from the building, money goes to the stall" disconnect.
`SellToMarketBehavior`/producer sells deposit to the **hub** (backstock),
unchanged.

### `OwnerType` switch audit

`OwnerType` (NPC/PLAYER/WANDERING_TRADER) is unchanged. Routing/goods don't
switch on it (the stall chest is the endpoint regardless of owner type);
sign text reads the display name, not the type; vacancy uses the
`VACANT_UUID` sentinel, not a new arm. WANDERING_TRADER arm untouched.

### Tie-In / Simplification / Deviations

- **Tie-In.** Upstream: 2b stall records + chest + placement geometry,
  1b settle, `BuildingStorageAccess`. Downstream: both trade paths' goods
  go through `StallGoods` where a stall is the endpoint; `MarketStall`
  record shape unchanged → readers unaffected. Phase-3 contract:
  `MarketWorkPost.forStall(market, stall)` → stand + facing; an event
  stall (2d) exposes the same.
- **Simplification.** `StallGoods` consolidates chest take/store;
  `MarketStallOwnership` consolidates the sign write for owner changes.
- **Deviations (flagged):**
  1. **Rent/lease NOT re-pointed through `settlePurchase`/`settleSale`.**
     Those are party↔party *trade* settlements that apply the market-tax/
     burn rule; rent (NPC wallet → village treasury) and lease (player →
     treasury) are *treasury fee* flows the 1b model doesn't represent —
     forcing them through the trade settle would mis-tax/burn the payment.
     They remain clean treasury transactions (`wallet.spend` +
     `depositToTreasury`), which is the correct model. The "no bespoke
     poke" intent is met by their being simple + correct, not by a wrong
     mapping.
  2. **`TradeHandler` goods not fully consolidated.** Its buy path is
     already stall-authoritative (sources from the merchant's stall); its
     sell goes to the hub because the merchant being traded with isn't a
     stall owner in the seeded model. Its private chest helpers remain
     (not migrated to `StallGoods`) to bound risk in the no-compile
     session. Flagged for a follow-up sweep.
  3. **Sign funnel covers owner *changes* (claim).** `reclaimStall`
     (a destroy path, not an owner change) keeps its legacy
     `updateStallSigns` call; the live owner-change path goes through the
     funnel. Trivial follow-up to retire `updateStallSigns`.
- **Out-of-scope but flagged:** merchant standing/tending behaviour
  (Phase 3, consumes `MarketWorkPost`); temporary/event stalls (2d);
  pricing/settlement core (1a/1b).

### Build verification

Deferred — sandbox blocks `maven.neoforged.net`. Static review: `StallGoods`
uses `Container`/`BuildingStorageAccess` (existing APIs); `MarketChannel`
keeps a single `stall` decl (moved up) and still imports
`BuildingStorageAccess` (used in `quote`); `DynamicSignUpdater.updateSigns`
signature matched; `MarketStall` record/codec unchanged (no field added).

### Smoke test

1. Buy at an NPC-owned stall: goods come from the stall chest (then hub
   backstock), money to the stall owner (1b endpoint) — disconnect gone.
2. Sell into the market (NPC channel): goods land in the hub backstock.
3. Stall-less market sale still works via the hub.
4. Claim a stall: the sign updates in the same call (no drift) for the
   owned state; vacant stalls read "For Rent".
5. Rent/lease still move money correctly (treasury flows; not the trade
   settle, by design).
6. `MarketWorkPost.forStall` returns a sane stand + outward facing for a
   stall (Phase 3 input; no standing behaviour yet).

---

## Phase 2d — Temporary / event stalls (farmer's-market connection)

**Goal:** connect market/fair events to real, temporary producer stalls —
recruit surplus producers into `MarketStall` records for the event window,
stocked from their goods, torn down cleanly (leak-proof) at event end.

**Sequencing:** infra now, tending follows in Phase 3 (per the prompt's
recommendation). Stalls appear + are owned + stocked; producers man them
once Phase 3 occupancy consumes the 2c work-post.

### What shipped

- `MarketStall.eventId` — nullable event marker, persisted via an
  `optionalFieldOf("eventId","")` + a `fromCodec` adapter (canonical
  constructor + `create()`/legacy callers unchanged; pre-2d saves load).
  `isEventScoped()` / `getEventId()` / `setEventId()`.
- `EventStallManager` (new, `Village/Markets/Complex/`):
  - `recruit` — on a market/fair start: recompute the 2a pad region
    (deterministic), pick eligible surplus producers, place stalls via the
    2b `StallAllocator` up to `MAX_RECRUITS`/pad capacity, tag with
    `eventId`, owner = producer, stock from the producer's building.
  - `teardown` — on event end: per event stall, return goods to the
    owner's building, `reclaimStall` (clear structure to pad), remove
    record.
  - `reconcile` — load-time: tear down any event-scoped stall whose event
    isn't in `EventScheduleData.getActiveEvents` — covers crash/restart/
    unload orphans.
- Hooks: `EventEffects.onEventStart` calls `recruit` centrally (after the
  type switch, so registry-routed `SUMMER_MARKET` is covered too);
  `onEventEnd` calls `teardown`; `ModModEvents.onServerStarting` calls
  `reconcile` once.
- `MarketRentManager` skips `isEventScoped()` (and `isVacant()`) stalls.

### Temporary-stall lifecycle

start → `recruit` (place + tag + stock + owner) → [Phase 3 tend] → end →
`teardown` (return goods + clear-to-pad + remove). Crash between start and
end → `reconcile` at next load tears the orphan down. The `eventId` is the
single source of truth for "is this stall temporary + which event."

### Recruitment policy

Eligible = producer professions (FARMER, BAKER, FISHERMAN, BUTCHER,
WEAVER, CARPENTER, BLACKSMITH, MINER, MASON, SHEPHERD) assigned to the
village with non-empty building storage (surplus). Capped at
`MAX_RECRUITS` (6) and by allocator pad capacity (respects existing
permanent/seeded occupancy → never evicts them; "market full" simply
recruits fewer). Each stall stocked with up to `STOCK_PER_STALL` (16)
items pulled from the producer's building (overflow stays in the
building — nothing duplicated/lost).

### Teardown robustness (leak-proofing)

- **Normal end:** `teardown(eventId)` removes all matching stalls.
- **Restart/crash mid-event:** stall persists with `eventId`; `reconcile`
  at server start removes any whose event is no longer active.
- **Chunk unload/reload:** the record persists (not block-scanned), so
  reconciliation/teardown still find it by id.
- **Clear-to-pad:** reuses `MarketStallPlacer.reclaimStall` (clears the
  stall structure to air over the 2a pad surface — no per-block snapshot,
  no holes), then goods are returned first so nothing is destroyed.

### EventType / OwnerType switch audits

- `EventStallManager.wantsProducerStalls` switches `EventType`:
  `MARKET_DAY, SUMMER_MARKET, VILLAGE_FAIR → true`; `GUILD_FAIR` and all
  others → `false` (default arm). GUILD_FAIR is guild-scoped, not a
  producer farmer's-market — keeps its wandering trader + cosmetics only.
  No new EventType added; the existing `onEventStart`/`onEventEnd` switches
  are untouched (recruit/teardown are called outside the switch).
- `OwnerType` — recruited producers are `NPC`-owned; no new arm. The 2c
  goods/sign paths already handle `NPC` uniformly. WANDERING_TRADER arm
  untouched (the market-day trader is unchanged).

### Tie-In / Simplification / Deviations

- **Tie-In.** Upstream: event start/end (`EventEffects`), 2b allocator,
  2a deterministic pad recompute, producer building storage. Downstream:
  Phase-3 occupancy consumes `MarketWorkPost.forStall` + owner=producer on
  these stalls; 2c `StallGoods` means event-stall goods live in their own
  chest. `MarketRentManager` skips them. `EventScheduleData` is the
  liveness source for reconciliation.
- **Simplification.** Cosmetic `placeMarketDecorations` (carpet+barrels)
  is **kept as ambient filler** around the functional stalls rather than
  deleted — it's cheap visual dressing, not a parallel "stall" system, so
  it doesn't conflict with the real stalls. (Flagged: drop it later if the
  real stalls read well enough alone.)
- **Deviations (flagged):**
  1. **Tending not implemented** (Phase 3, per the prompt's sequencing
     recommendation) — stalls are owned + stocked but unmanned until then.
  2. **Recruitment surplus signal is "building has items"**, not a
     `VillageSimData.net` threshold — simpler and robust; refine to a
     true surplus metric in a tuning pass.
  3. **`existingOccupancy` uses a 3×3×4 origin proxy** for permanent
     stalls (the exact box needs the loaded template); the allocator's own
     gap checks prevent overlap among newly placed event stalls. Worst
     case a slightly conservative pack, never an overlap. Flagged.

### Out-of-scope but flagged

- Producer tending behaviour (Phase 3). Caravans/inter-village (Phase 4).
- WANDERING_TRADER (market-day trader unchanged). New event types.

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (and the session's shell/
read tooling was intermittently returning empty output, so this phase
leaned harder on static reasoning). Static review: `EventScheduleData
.getActiveEvents(UUID)` + `.get(level)` confirmed; producer enum values
confirmed against `Profession`; `MarketComplexPlanner.Input` /
`StallAllocator.place` / `StallGoods.store` / `reclaimStall` signatures
matched; `MarketStall` codec extended with one optional field via
`fromCodec` (canonical ctor untouched); `server.overworld()` is standard.
**Caveat:** the no-compile + flaky-tooling combination means residual
risk is higher than prior phases — a compile pass is the first thing to
run when maven is reachable.

### Smoke test

1. MARKET_DAY in a village with surplus producers → real temporary stalls
   on the pad, owned by producers, stocked — not just carpet+barrels.
2. Event stalls fill only free pad space; never evict permanent/seeded
   stalls; pad full → fewer producers, no overlap.
3. Event ends → all temporary stalls removed, pad restored, unsold goods
   returned to the producer's building.
4. Restart mid-event → `reconcile` tears down orphans; none leak.
5. `MarketRentManager` charges no rent on temporary stalls.
6. Market-day wandering trader still spawns (untouched).
7. (After Phase 3) producers man their event stalls; before: stocked but
   unmanned (expected).

---

## Phase 2b (addendum) — MARKET_STALL promotion (partial; tooling-degraded session)

The user opted to attempt 2b's deferred parts in full. This session's
shell/Read tooling degraded badly mid-way (stdout + file reads returning
empty), so only the verified, self-consistent, compilable core landed.

### Landed (committed)

1. **`BuildingType.MARKET_STALL`** added (final enum value). Gated on a
   scripted, brace-matched exhaustive-switch audit of all switch blocks:
   **every switch whose *case-labels* are BuildingType constants (12) has
   a `default` arm**, so the addition keeps them exhaustive. Switches that
   merely *return* BuildingType constants switch over other enums
   (`ProducerType.requiredBuilding`, `BlacksmithSpecialization`,
   `VisitorActivity`, `HobbyLocation`, castle sub-pieces, packet actions)
   and are unaffected. `BuildingType.values()` loops (debug suggestions,
   `AdjunctPlotRegistry`) and the EnumMap registries use tolerant
   `.get()`/suggest patterns — an unmapped MARKET_STALL is the normal
   no-entry path. Conclusion: compile-safe.
2. **`StallAllocator.resolveTemplate`** — resolves the stall template via
   `Village.CultureResolver.resolve(culture, Style.RURAL, MARKET_STALL,
   variantId, 1, level)` first, falling back to the variant's direct NBT
   path. Culture from `Cultures.CultureResolver.of(level, village)`
   (null-safe). Added null-safe `marketVillage()` lookup. Verified deps:
   `getAllVillages()`, `loadTemplate()`, `Culture.id()`, and the correct
   `Style` FQN (`Village.Decoration.Variants.Style`).
3. **`StallVariant.nbt` → `directNbt`** (the fallback location); `id`
   doubles as the variantId path segment. Positional constructor caller
   (`MarketComplexRegistry`) unaffected; no `.nbt()` callers remain.

Because the variant NBT has NOT yet been relocated (see below), resolution
falls through to `directNbt` — the original `…/rural/market/stall/
stall_1.nbt`, which still exists — so stalls keep placing. This is the
intended graceful-degradation path.

### NOT done (blocked by tooling failure — next session)

- **Relocate the stall NBT** into the variant layout
  `…/rural/market_stall/stall_1/level_1.nbt` + author the in-folder
  `manifest.json` (`{"id":"stall_1","stylePreference":"RURAL"}`). The
  binary `cp` could not be verified this session. Until done, the variant
  path is unauthored and the direct fallback is used.
- **Delete `findAnchorSlots`** (both overloads) and re-point its two
  display callers (`MarketApproach.hasAvailableStall`,
  `NpcInteractionHandler` slot summary) to the seeded-stall model.
  `findAnchorSlots` still exists, so current callers still compile — this
  is optional cleanup, not a correctness blocker.
- Import cleanup in `MarketStallPlacer`.

### Residual risk

Still **uncompiled** (sandbox blocks maven; tooling degraded). The switch
audit is high-confidence (scripted, exhaustive). The 3 committed files
were dependency-checked individually and form a self-consistent unit with
safe fallback. First action next session: `./gradlew build`, then finish
the NBT relocation + dead-path deletion above.

### Follow-up (same session, tooling recovered): variant NBT authored

The deferred NBT relocation is now done. Copied (not moved — legacy kept
as the `directNbt` fallback) the stall template into the variant layout:
`structures/default/rural/market_stall/stall_1/level_1.nbt` (md5
`fd038914…`, checksum-verified identical to the source) + the in-folder
`manifest.json` (`{"id":"stall_1","stylePreference":"RURAL"}`). The
`MARKET_STALL` variant path is now authored, so `StallAllocator.resolve
Template` resolves via CultureResolver step-1
(`default/rural/market_stall/stall_1/level_1`) instead of falling back.

Still outstanding (deferred, behavioral — not pure cleanup): deleting
`findAnchorSlots` and re-pointing `MarketApproach`/`NpcInteractionHandler`.
`MarketApproach` uses anchor positions as live NPC standing spots, not
just a count, so that change needs compile + in-game verification; left
intact (callers still compile) for a session with maven access.

---

## TEST SCAFFOLD — perimeter-offset farm + market complexes

**Temporary content-testing scaffold, not the real fix.** Lets both
complexes generate reliably in open terrain so merchant/stall content
(2a–2d) is actually testable.

### Reservation gap this works around

Farm + market complexes run as a post-placement pass in
`V2VillageSpawnerAdapter` and only *avoid* existing reservations — nothing
reserves space *for* them at plan time. In a dense village they starve:
farms log `SEED_NOT_ADMISSIBLE` (seed lands inside a park/building
exclusion); markets log `NO_REGION` (pad centre is surrounded by the ~27
building obstacles, so no full-margin pad fits). The real fix — emit a
`ComplexRegion` Layer-3 envelope at plan time so buildings/parks route
around the complex — is queued **layout-rework** and is out of scope here.

### The scaffold

- One gating flag `PERIMETER_OFFSET_COMPLEXES = true` (+ `PERIMETER_
  OFFSET_CLEARANCE = 64`). Confined to the adapter's two complex loops
  plus one private helper `perimeterAnchor`.
- `perimeterAnchor(villageCentre, buildingCentre, placedBuildingsAll)`:
  direction = village centre → building (unit vector); distance = furthest
  placed building from the village centre + clearance; Y = building's own
  Y (superflat-safe). Degenerate (building on centre) → building centre.
- Farm loop: feeds the anchor as the planner **seed** (was
  `fh.placed().centre()`), so the seed lands clear of every exclusion
  polygon → no more `SEED_NOT_ADMISSIBLE`.
- Market loop: feeds the anchor as the **pad centre** (was
  `mk.placed().centre()`), far from the building obstacles, so a
  full-margin pad fits → no more `NO_REGION`. Stall seeding (2b) then runs
  on the graded pad as before.
- Planner internals, seed-admissibility gate, obstacle math, shrink logic
  untouched — purely "move the anchor before calling the planner".

### Flag toggle

`PERIMETER_OFFSET_COMPLEXES = false` restores today's behavior exactly:
both loops use `placed().centre()` (the flag-off branch is the original
expression, just held in a local). Clean revert; whole scaffold deletes
with the flag when the ComplexRegion reservation lands.

### Accepted scaffold limitations (by design, not bugs)

- Complexes detach from their building and ring the village perimeter.
- Multiple complexes pushed the same direction may sit near/overlap (no
  angular spread — nice-to-have, not done).
- May land on bad terrain off superflat (testing on superflat).

### Build verification

Deferred (sandbox blocks `maven.neoforged.net`). Static review: imports
present (`BlockPos`, `Map`, `List`); helper signature matches
`placedBuildingsAll` (`Map<BuildingType,List<Building>>`); `Building
.getShape().getOrigin()` returns `BlockPos`; `origin`/`placedBuildingsAll`
in scope in both loops; flag-off branch byte-identical to prior code.

### Smoke test (user-executable, superflat)

1. Regenerate an AGRICULTURAL CITY. Farm complexes now generate out beyond
   the buildings (no `SEED_NOT_ADMISSIBLE` for every farmhouse).
2. Market pads now generate (no `NO_REGION`) with seeded stalls, out
   beyond the buildings.
3. Merchant/stall content testable: stalls exist; merchant can claim/man
   one (after 3a); market-day recruits producers (2d).
4. Flip flag false, regenerate → starved in-place result returns (proves
   clean toggle).
5. Logs show complex success, not skip reasons; no exceptions.

---

## Phase 3a — Merchant claims and mans a market stall

The keystone: the stationary MERCHANT now owns a market stall and mans it
during work hours, and selling resolves to that stall — closing the 2c
"sell goes to the hub" gap automatically.

### Ownership model + rent treatment

On entering work (`start`), the merchant acquires a home stall:
`VillageSavedData.getStallByOwner(uuid)` if it still owns an active stall
at this market, else `MarketStallPlacer.claimSlot(level, market, uuid,
OwnerType.NPC, Long.MAX_VALUE, data)`. **Rent-free**: claiming with
`rentUntil = Long.MAX_VALUE` makes `MarketStall.isPurchased()` true, and
`MarketRentManager` skips purchased stalls (line 54) *before* its NPC-rent
branch — so the resident merchant's workplace is never billed. **No
MarketRentManager change required** (confirmed against as-built). No new
codec field — ownership uses the existing `MarketStall` owner record.
Graceful no-vacant fallback: `acquireStall` returns null → the merchant
mans the market origin and `StallGoods.store(null stall)` routes deposits
to the hub (a DEBUG log notes it).

### Standing-surface reconciliation

`MarketWorkPost.forStall(market, stall)` (counter + aisle facing) is now
the single "where the owner stands" answer. `MerchantBehavior` walks to
`workPostStand()` (work-post stand, fallback stall/market origin). In
`MarketApproach.resolveSellSpot`, the **owned-stall branch** is repointed
from `own.getStallOrigin()` to the work-post stand (fallback origin). The
**non-owner fan-out** (`findAnchorSlots` over legacy anchors, capped
`SOFT_CAPACITY`) is left intact — full `findAnchorSlots` retirement needs
in-game verification (deferred since 2b/2c). Only the owned-stall path is
reconciled here; flagged the rest.

### Start-gate fix

`checkExtraStartConditions` dropped the `entity.getRandom().nextInt(40)`
lottery (which left the stall unmanned ~39/40 start checks). Now: nav-guard
+ `isWorkTime()` + `idleCooldown` + `phase == IDLE` → start deterministically.
`canStillUse` keeps it running until work ends; `manStall` holds position
at the post for the whole work period (replacing the old fixed
`TRADE_DURATION` timer). No per-tick log spam (state-unchanged path just
holds; the one new log is DEBUG).

### Deposit to the stall

`stockMarket` now deposits the merchant's existing personal stock into its
**owned stall** via `StallGoods.store` (stall-chest first, hub overflow),
not `BuildingStorageAccess.storeItem` into the market building. 3a only
moves stock the merchant already has — producer-buying restock is 3b.

### Tie-In Audit

- **Upstream**: assigned MARKET building; 2b-seeded vacant stalls
  (`MarketStallSeeder`); `MarketWorkPost`; `claimSlot` (assign-vacant).
- **Downstream**: `TradeHandler` buy/sell + `MarketChannel` now resolve
  the merchant's owned stall (`findOwnedStallWithItem` /
  `findStallWithItem`) → 2c hub gap closes. `NpcProfileSnapshotBuilder`
  still reads `isOpenForTrade()` (kept). Producer sellers still resolve
  via `MarketApproach` (non-owner path unchanged).
- **Siblings**: `MarketRentManager` exempts the stall (purchased).
  `WorkshopStallDecisionGoal` producers still claim *other* vacant stalls
  — the merchant takes exactly one home stall via the same assign-vacant
  pool, so it can't starve producers (one claim, the rest stay vacant).
  `CaravanMerchantBehavior` (WORK pri 0) still pre-empts on caravan duty;
  3a governs only the stationary WORK pri 1 fallback.
- **Exhaustive switches**: none — no new enum (the `Phase` enum is
  unchanged in 3a; `COLLECTING` is 3b).

### Simplification Sweep

Reconciled the two standing surfaces onto `MarketWorkPost` for owners.
Bounded: the legacy `findAnchorSlots` non-owner fan-out stays (needs
in-game verification before retirement). The dead `collect()` /
`productionBuildings` / `regenerateMarketOffers` are left in place for 3b
to rewire (not deleted here to keep 3a's diff scoped to ownership+manning).

### Deviations from prompt

- The prompt suggested "a sentinel rent-until / a MarketRentManager
  exemption" — chose `Long.MAX_VALUE` (the existing "purchased" sentinel),
  which needs **no** MarketRentManager change. Cleaner than a new exemption.
- Kept `regenerateMarketOffers` (no-op) for now — prompt says leave it for
  3b/cleanup; the player screen is built by `TradeHandler.openTradeScreen`.

### Out-of-scope but flagged

- 3b: producer-buying, `collect()` revival, COLLECTING phase,
  `productionBuildings`.
- Full `findAnchorSlots` deletion (non-owner path) — in-game verification.
- `regenerateMarketOffers` no-op removal → 3b.

### Build verification

Deferred (sandbox blocks `maven.neoforged.net`). Static review: API
signatures confirmed via recon against as-built (`getStallByOwner`,
`claimSlot`, `MarketWorkPost.forStall`, `StallGoods.store`,
`isPurchased`); imports resolve (price helpers in `Economy.Currency`);
no new enum/codec; `MarketApproach` owned-stall branch repointed.

### Smoke test

1. Spawn village w/ MARKET + merchant; advance to work hours → merchant
   claims a seeded vacant stall and stands at its counter (aisle-facing),
   not flocking to a corner.
2. Confirm the merchant's stall is not rent-charged (`MarketRentManager`
   skips purchased).
3. Buy from + sell to the merchant → goods come from / go to the
   merchant's stall chest (2c gap closed); money via the 1b endpoint.
4. Producers still claim other vacant stalls (merchant took one, not all).
5. Producer sellers still resolve a sane sell spot via `MarketApproach`.
6. Caravan duty still pre-empts.
7. Logs: deterministic work-time presence; no per-tick spam; no NPE when
   no vacant stall (graceful fallback to origin + hub).
