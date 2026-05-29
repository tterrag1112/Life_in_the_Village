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
