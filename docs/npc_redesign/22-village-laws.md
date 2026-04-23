# 22 — Village Laws

## Purpose

Leaders enact laws that modify behavior, economy, and crime handling
within their village. Turns `village_leader` from a cosmetic title
into real power. Player-leaders gain meaningful choices; NPC-leaders
decide based on traits and circumstances.

Laws are additive — no-laws village runs on defaults; each enacted
law adds/changes behavior. Hook points exist in economy, crime,
schedule, visitor flux, trade.

## Data model

### VillageLaw

```java
public enum VillageLaw {
    // Economy
    TOLL_ENTRY, MARKET_TAX_DOUBLE, MARKET_TAX_REDUCED,
    SUBSIDIZE_FARMER, SUBSIDIZE_BLACKSMITH, SUBSIDIZE_SCHOLAR,
    SUBSIDIZE_HEALER, PROFITS_TO_TREASURY,
    PROPERTY_TAX_DOUBLE, PROPERTY_TAX_WAIVED,

    // Crime & justice
    CURFEW, DOUBLE_PUNISHMENT, PARDON_FIRST_OFFENSE,
    BAN_EXECUTION,

    // Social / cultural
    FOREIGN_TRADER_BAN, PILGRIM_WELCOME_BONUS,
    COMPULSORY_SCHOOLING, TEMPLE_ATTENDANCE_EXPECTED,
    FESTIVAL_MANDATORY,

    // Economic restrictions
    GUILD_MEMBERSHIP_REQUIRED,
    PRICE_CEILING_FOOD, PRICE_FLOOR_FOOD;

    public LawCategory category();
    public int enactmentDifficulty();    // 1..10
    public int popularityBase();          // -50..+50
}

public enum LawCategory {
    ECONOMY, CRIME, SOCIAL, ECONOMIC_RESTRICTION;
}
```

### VillagePolicy

Per-village state:

```java
public class VillagePolicy {
    private final Set<VillageLaw> activeLaws;
    private final Map<VillageLaw, LawParams> params;
    private final Map<VillageLaw, Long> enactmentTicks;

    public boolean hasLaw(VillageLaw law);
    public Optional<LawParams> getParams(VillageLaw law);
    public void enact(VillageLaw law, LawParams params, long tick);
    public void repeal(VillageLaw law);

    public float popularity(VillageLaw law, Village village, VillageSavedData data);
}

public record LawParams(
    Map<String, Float> numericParams,    // "toll_amount" -> 5.0
    Map<String, String> stringParams
) {}
```

Parametrized examples: TOLL_ENTRY `{"toll_amount": 5.0}`;
SUBSIDIZE_FARMER `{"subsidy_per_day": 3.0}`; PRICE_CEILING_FOOD
`{"max_bronze_per_unit": 15.0}`.

### LawEffect

```java
public interface LawEffect {
    VillageLaw law();
    void onEnact(Village village, VillageSavedData data, LawParams params);
    void onRepeal(Village village, VillageSavedData data);

    default long modifyTax(long baseTax, Village village, LawParams params) { return baseTax; }
    default long modifyToll(long baseToll, Village village, LawParams params) { return baseToll; }
    default float modifyWage(float baseWage, Profession p, LawParams params) { return baseWage; }
    default PunishmentType modifyPunishment(CrimeType crime, PunishmentType base,
                                            boolean firstOffense, LawParams params) { return base; }
    default boolean blocksAction(ActionCheck check) { return false; }
}
```

Each law implements `LawEffect` as a small class registered at mod
init.

## Enactment

### NPC leader decisions

Daily tick runs `LawDecisionEngine`:
1. Assess village needs (food deficit, crime spike, unhappy mood,
   treasury).
2. Score candidate laws by need-fit.
3. Consider leader traits (Ambition aggressive; Compassion pro-
   welfare).
4. Consider popularity (unpopular laws cost rep).
5. Random roll; enactment rare — < 1 per month unless crisis.

Examples: food-deficit + Compassionate leader → SUBSIDIZE_FARMER.
Crime-heavy + Ambitious harsh leader → DOUBLE_PUNISHMENT + CURFEW.

### Player leader decisions

"Laws" panel in office management UI:
- View active laws with stats (popularity, cost, effect).
- Enact new from available list.
- Repeal existing.
- Adjust parameters.

Enactment cost: bronze from treasury + rep hit if unpopular.

### Popularity

```
basePopularity = law.popularityBase()
adjustment = sum(villager traitFit(law))
adjustment -= affectedNegatively(villager) * 5
adjustment += benefitsTo(villager) * 5
final = clamp(-100, +100, ...)
```

Negative-popularity laws decay leader rep (~-1/day), raise rebellion
signal (Phase 5 optional), increase emigration/tax-evasion risk.

Laws stay enacted until explicitly repealed.

### Enactment difficulty

Conditions required:
- SUBSIDIZE_*: treasury buffer.
- BAN_EXECUTION: culture-dependent.
- COMPULSORY_SCHOOLING: librarian/scholar present.
- GUILD_MEMBERSHIP_REQUIRED: formal guild (level ≥ 1).

Checks fire at enactment; unavailable laws hidden from UI.

## Hook points

### Tax system

- Property tax: `VillageTreasury.propertyTax()` checks PROPERTY_TAX_*.
- Market tax: `TradeHandler` applies MARKET_TAX_*.
- Wages: `BuildingEconomy.payWage` reads SUBSIDIZE_* and
  PROFITS_TO_TREASURY.

### Crime system

- `PunishmentSelector` reads policy:
  - DOUBLE_PUNISHMENT upgrades severity.
  - PARDON_FIRST_OFFENSE downgrades first minor.
  - BAN_EXECUTION swaps EXECUTION for EXILE.
- CURFEW via `BuildingPresenceTracker`: night entry → TRESPASSING.
- SMUGGLING under FOREIGN_TRADER_BAN.

### Visitor / caravan

- TOLL_ENTRY intercepts incoming at border; payment before entry.
- FOREIGN_TRADER_BAN prevents caravan trades.
- PILGRIM_WELCOME_BONUS adds rep to pilgrim visitors.

### Schedule

- FESTIVAL_MANDATORY forces event attendance.
- COMPULSORY_SCHOOLING forces children into schooling daily.
- CURFEW forces HOME phase earlier.

### Economy

- PRICE_CEILING_FOOD clamps listings.
- PRICE_FLOOR_FOOD raises floor.
- GUILD_MEMBERSHIP_REQUIRED blocks non-guild production.

## Player influence

"Petition leader" verb on leader NPC:
- Requires rel ≥ +20.
- Presents available laws.
- Leader considers relationship × 10 as decision weight.
- High-trait-fit + good timing more likely succeed.

Failed: no change. Succeeded: leader enacts; rel +5 with petitioner.

## Lifecycle events

Enactment fires:
- Village announcement (dialogue seed, gossip seed).
- Mood shock: +5 supporters, -5 opponents.
- Leader rep delta based on popularity.

Repeal: similar announcement; rep delta reversed.

## Persistence

Attached to `Village` / `VillageSavedData`:

```
villageLaws: {
    active: [
        {
            law: "SUBSIDIZE_FARMER",
            enactedTick: 100000L,
            params: {
                numeric: { "subsidy_per_day": 3.0 },
                string: {}
            }
        }
    ]
}
```

## Integration points

### Phase 3 integration

- `VillagePolicy` on each village.
- `LawEffect` implementations registered.
- Hook points wired: tax, crime, visitor, schedule, economy.
- `LawDecisionEngine` daily for NPC leaders.
- Player leader management UI: "Laws" panel.
- "Petition leader" verb.
- "Protest law" (Phase 5 stretch, stubbed).
- `/law list|enact|repeal|popularity` debug.

### Phase 4+ integration

- Kingdom-level laws (stub).
- Cross-village consistency pressure (neighbors influence).

## Behavior contract

### Does

- Catalogue of enactable laws with parameters.
- Per-village per-law popularity.
- Wire effects into tax, crime, schedule, trade, visitor.
- NPC leaders enact/repeal autonomously.
- Player leader UI for enactment.
- Player petitioning NPC leaders.

### Does not

- Full political modeling (parliaments, vetoes, amendments).
- New-verb-requiring laws.
- Enforce cross-village consistency.
- Retroactive punishment.

## Edge cases

- **Subsidy drains treasury.** Auto-suspends (not repealed) until
  funds recover.
- **Contradictory laws.** MARKET_TAX_DOUBLE vs REDUCED blocked at
  enact time.
- **Leader dies with unpopular laws.** Next leader auto-repeals
  depending on traits; some persist as "inherited policy".
- **Culture shift removes eligibility.** Law remains until explicit
  repeal.
- **Player leader repeals predecessor's law.** Allowed; rep delta
  applies to player.

## Ordering dependencies

Phase 3 depends on:
- Office framework (Phase 3) — leader identity.
- Crime system (Phase 3) — punishment hooks.
- BuildingPresenceTracker (Phase 3) — curfew.
- Existing tax/treasury system.
- Existing rep and gossip.
- Trait system.

## Open decisions

- UI flavor/mechanical mix. **Proposed: flavor name + one-sentence
  mechanical description.**
- Retroactive effects. **Proposed: no — laws apply forward only.**
- Voting mechanic for big laws? **Proposed: not v1; council handled
  via office selection, not law voting.**
- 25+ laws too many? **Proposed: ship set; only ~10 initially used.
  Framework tolerates unused.**

## Does-not-include

- Law court mechanics (judicial review).
- Referendum / plebiscite.
- Cross-village treaties (Phase 4 kingdom-level).
- Constitutional framework (laws that can't be repealed). Every
  law repealable.

## Revision Notes

(changes recorded here as the spec evolves after testing)
