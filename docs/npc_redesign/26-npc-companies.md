# 26 — NPC-Owned Companies

## Purpose

`Company` currently only supports player ownership. Extending to NPC
ownership is small but unlocks several narrative and mechanical
threads:

- A successful merchant can evolve into a trading company.
- Trading companies run long-haul caravans across kingdoms.
- Village merchant pool can grow beyond single-proprietor stalls.
- NPC companies compete with player companies, creating economic
  rivalry.
- Inheritance and succession of companies (NPC owner dies → heir).

This subsystem generalizes ownership, adds an AI manager for NPC-owned
companies, and wires the merchant → trading company upgrade path.

## Data model

### Ownership extension

```java
public class Company {
    // ... existing

    private OwnerType ownerType;
    private UUID ownerId;              // player UUID or NPC UUID depending on type
    private List<UUID> heirs;          // ordered succession list

    public enum OwnerType { PLAYER, NPC }
    // Codec extended with optionalFieldOf for backward compat
}
```

Existing `Company.owner` field (player-only) migrates to OwnerType=PLAYER
at load time.

### Trading company

A `Company` tagged as `TRADING_COMPANY` (new flag on company type):

```java
public enum CompanyType {
    STANDARD,       // existing
    TRADING_COMPANY;
}
```

Trading companies have:
- Access to caravans with longer max-routes (including cross-kingdom).
- Can hire dedicated CARAVAN_ATTENDANT workers.
- Can post requests to the inter-village request board (Phase 4
  same-phase).
- Treasury can receive trade profits directly.

### AiCompanyManager

Runs daily decisions for NPC-owned companies:

```java
public final class AiCompanyManager {
    public static void dailyTick(ServerLevel level, Company company);
    // - Evaluate treasury vs. expenses
    // - Decide: hire, fire, adjust wages
    // - Dispatch caravans if trading company
    // - Accept/post requests
    // - Handle succession on owner death
}
```

Decision logic biased by owner's traits:

- High Ambition → expand aggressively, hire more, post more caravans.
- High Industry → maintain current operations well.
- Low Temperance → risky ventures, may overextend.
- High Compassion → pay above-market wages, reluctant to fire.

## Merchant → Trading Company promotion

### Eligibility

A MERCHANT NPC becomes eligible for trading company promotion when:

1. COMMERCE skill ≥ 70 (Grandmaster tier).
2. Currently runs a market stall or a market building.
3. Personal wallet ≥ 500 bronze (initial capital).
4. Has been employed as MERCHANT for ≥ 365 in-game days continuously.

### Promotion path

When eligible, the NPC fires a `CompanyPromotion` event during next
workday:

1. Creates a new `Company` with OwnerType = NPC.
2. Sets CompanyType = TRADING_COMPANY.
3. Transfers initial 100 bronze from NPC wallet to company treasury.
4. NPC becomes owner + first worker (PRODUCER role at own market).
5. Village-history records "Elara the Merchant founded Elara's Trading
   House".
6. NPC gains office `company_owner` for this company.

### Expansion

Trading companies can:

- Hire additional workers via existing `JobPosting` flow, extended with
  `CARAVAN_ATTENDANT` role.
- Post long-haul caravan dispatch events (`CaravanSavedData.dispatch`
  with `isTradingCompany = true`).
- Own multiple buildings (market stall + dedicated warehouse, Phase
  4 stretch).

Expansion decisions via `AiCompanyManager`:
- Hire when workload exceeds current staff and treasury allows.
- Adjust wages based on market conditions.
- Accept inter-village contracts that fit capacity.

## Succession

On NPC owner death, `AiCompanyManager.handleSuccession` fires:

1. Check `heirs` list — succession chain.
2. If first heir exists and is alive: transfer ownership.
3. If no heir: company enters `UNDECIDED` state for 30 days.
   During this period, family members can claim (automatic if single
   child exists; disputed if multiple).
4. If still no resolution: company dissolves — treasury distributed to
   workers as severance, buildings released.

Heir designation:
- Default: oldest living adult child of owner.
- NPC can change heirs during life via a scribe-produced will (Phase
  4 stretch; uses existing letter/contract infrastructure).

Player-owned companies don't auto-succeed; player owns them for life.
If player death is modeled (not in v1 scope), separate rules.

## NPC-run caravans

Trading companies dispatch caravans similarly to existing village
merchant caravans, but:

- Route distance up to 3x village-merchant limit.
- Cross-kingdom routes allowed if diplomatic conditions permit
  (Phase 4 stub: yes always; future gated by kingdom relations).
- Company pays wages to the caravan crew.
- Profit returns to company treasury.

Caravan assembly:
- Select goods based on destination's needs (queries
  `ResourceCategory` surplus/deficit via engine).
- Load warehouse or company-owned stockpile.
- Assign CARAVAN_ATTENDANT workers to crew.
- Dispatch via existing caravan travel system.

## Worker role: CARAVAN_ATTENDANT

New `Company.WorkerRole`:

```java
public enum WorkerRole {
    PRODUCER,
    SELLER,
    COURIER,
    CARAVAN_ATTENDANT;   // new
}
```

CARAVAN_ATTENDANT workers:
- Assigned to specific caravan dispatches.
- Travel with caravan (AI handles via existing caravan system).
- Earn wages regardless of caravan outcome.
- On successful caravan return: bonus from company profit share.
- On caravan failure (bandits, sea loss): may return alone, wounded,
  or die.

## Cross-village operation

Trading companies can own workers based in different villages:

- Worker at home village: PRODUCER / SELLER.
- Worker at partner village: SELLER (outpost) — maintains presence,
  handles arriving caravans.

Phase 4 ships with single-village trading companies; multi-village
support is explicitly next phase.

## Competition with player companies

If a village has both player-owned and NPC-owned companies in the
same profession:

- Both compete for worker pool.
- Pricing pressures: if one sets high prices, other undercuts.
- NPC company's AI notes player behavior and adjusts.
- Reputation effects: worker chooses employer partly by relationship
  + wage + company reputation.

## Office integration

- `company_owner`: held by NPC or player depending on OwnerType.
- `company_foreman` and `company_bookkeeper`: appointed by owner.
- Office framework from Phase 3 applies uniformly.

## Integration points

### Phase 4 integration

- `Company.OwnerType` enum + fields.
- `AiCompanyManager` runs daily.
- Merchant promotion check in merchant NPC daily tick.
- Trading company caravan dispatch via extended
  `CaravanSavedData`.
- `CARAVAN_ATTENDANT` role in existing company worker system.
- Succession handler on NPC owner death event.
- Village history events for founding, dissolution.
- `/company` debug commands extended:
  - `/company promote <npc>` — force-promote merchant
  - `/company owner <company>` — show owner type + ID
  - `/company succeed <company>` — force succession

### Phase 4+ integration

- Request board (`27-request-board.md`, same phase) — trading
  companies both post and accept.
- Multi-village outposts (future).
- Bankruptcy / liquidation mechanics (future).

## Behavior contract

### Does

- Extend Company ownership to include NPC owners.
- Provide automated daily management via AiCompanyManager.
- Wire merchant → trading company promotion path.
- Handle succession on owner death.
- Enable long-haul caravan dispatch for trading companies.

### Does not

- Implement full corporate governance (board, shareholders). Simple
  owner model.
- Support company mergers/acquisitions in v1.
- Model employee unions or worker disputes beyond basic wage
  satisfaction.
- Handle bankruptcy gracefully beyond dissolution on owner death or
  empty treasury.

## Edge cases

- **Promoted NPC dies shortly after founding.** Succession runs;
  company may dissolve if no heirs.
- **Player owns market stall; NPC merchant promotes to trading
  company in same village.** Both operate; competition rules apply.
- **Trading company dispatches caravan, but all its workers are
  busy.** Caravan delayed until worker availability.
- **NPC owner demoted from MERCHANT profession** (via retraining).
  Loses company-owner office; company enters UNDECIDED until heir
  assumes or dissolves.
- **Caravan crew all die.** Goods lost; company treasury absorbs loss.
  If severe, may trigger rival trade route events.

## Ordering dependencies

Phase 4 depends on:
- Existing Company system.
- Office framework (Phase 3).
- Caravan system (existing).
- Resource categories (Phase 4 same-phase) — for caravan goods
  selection.
- Request board (Phase 4 same-phase) — for contract acceptance.
- Village history (Phase 4 same-phase) — for founding events.

## Open decisions

- Minimum treasury before dissolving idle company: **Proposed: 50
  bronze below expenses for 14+ days triggers dissolution warning;
  30 more days to dissolution.**
- Should NPC companies advertise job openings differently? **Proposed:
  use existing JobPosting; AI manager posts when needed.**
- Heir disputes: how resolved? **Proposed: simple rule in v1 —
  eldest-adult-child; disputes handled in Phase 5 content.**

## Does-not-include

- Corporate hostile-takeover mechanics.
- Stock market / investment in other companies.
- Multi-owner partnerships.
- Player employment at NPC-owned companies as a first-class career
  path (stub exists; full in Phase 5).

## Revision Notes

(changes recorded here as the spec evolves after testing)
