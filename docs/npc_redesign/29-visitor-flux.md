# 29 — Visitor Flux

## Purpose

Specialized villages need external demand to justify their specialty.
A shrine village with no pilgrims is just a village with a temple; a
market town needs traders; a scholar's retreat needs students
visiting. Visitor flux adds ephemeral visitors who arrive at village
edges, head to target buildings, spend coin, and leave.

Unlike permanent residents, visitors are temporary. They might be
pilgrims to a specific shrine, students seeking a scholar, merchants
bringing exotic goods, or ordinary travelers passing through. Their
economic impact is significant — they inject coin and consume services
— but they're largely automated; the player isn't required to interact
with them.

## Data model

### VisitorType

```java
public enum VisitorType {
    PILGRIM,              // religious visit; tithes, offerings
    MERCHANT_ITINERANT,   // foreign trader bringing goods
    TRAVELER,             // generic; inn stay, meal
    STUDENT,              // visiting scholar; book purchase, lesson
    ENVOY,                // official from another village/kingdom
    REFUGEE,              // fleeing hardship; may settle
    SCHOLAR_VISITING,     // peer-to-peer scholar exchange
    MINSTREL;             // entertainer; village mood boost

    public static final Codec<VisitorType> CODEC;
}
```

### Visitor

```java
public class Visitor extends TownspersonMob {
    // Extended TownspersonMob; inherits all systems but marked temporary
    // Additional fields:
    private VisitorType visitorType;
    private UUID originVillageId;      // if from another village
    private UUID targetBuildingId;     // primary destination in visited village
    private long arrivalTick;
    private long expectedDepartureTick;
    private List<VisitorItinerary> itinerary;  // multi-stop plan
    private boolean settledPermanently;
}

public record VisitorItinerary(
    UUID buildingId,
    Activity activity,
    int expectedDurationTicks
) {}

public enum Activity {
    PRAY,                 // at temple/shrine
    STAY,                 // at inn
    EAT,                  // at inn/bakery
    TRADE,                // at market
    SHOP,                 // buy specific goods
    ATTEND_LESSON,        // at scholar/library
    DELIVER_MESSAGE,      // envoy
    PERFORM,              // minstrel
    SELL_GOODS;           // merchant itinerant
}
```

Visitors are `TownspersonMob` instances with a "temporary" flag;
`VisitorGoal` drives their behavior.

### VillageVisitorCapacity

Per-village capacity configuration:

```java
public record VillageVisitorCapacity(
    int maxConcurrent,
    float arrivalRatePerDay,     // expected arrivals per day
    Map<VisitorType, Float> typeWeights  // which types are drawn
) {}
```

Calibrated by village specialization:
- Shrine village: high PILGRIM rate, maybe 3/day.
- Market town: high MERCHANT_ITINERANT and TRAVELER.
- Scholar's retreat: STUDENT and SCHOLAR_VISITING.
- Generic village: low TRAVELER rate, rare of others.

## Spawning

### Probability-based arrival

Daily tick runs `VisitorFluxEngine` per village:

```java
public static void dailyTick(Village village, VillageSavedData data,
                             ServerLevel level) {
    VillageVisitorCapacity cap = computeCapacity(village, data);

    int current = countActiveVisitors(village, level);
    if (current >= cap.maxConcurrent()) return;

    float arrivalChance = cap.arrivalRatePerDay();
    while (arrivalChance >= 1f || (arrivalChance > 0 && rng.nextFloat() < arrivalChance)) {
        spawnVisitor(village, cap, data, level);
        arrivalChance -= 1f;
    }
}
```

Visitors despawn after their itinerary completes or expected departure
tick.

### Spawn location

Visitors spawn at the village edge:
- Use existing gate endpoint if available.
- Otherwise pick a random outer cell position.
- Path into village following roads.

## Behavior

### VisitorGoal

Low-priority primary goal while visitor is active:

```java
public class VisitorGoal extends Goal {
    private final TownspersonMob visitor;
    private int itineraryIndex;
    private Phase phase;

    enum Phase { WALKING_TO_VILLAGE, AT_LOCATION, WALKING_TO_NEXT, LEAVING }

    @Override public boolean canUse() {
        return visitor.isVisitor() && visitor.getVisitorItinerary().isPresent();
    }

    @Override public void tick() {
        // Advance through itinerary; interact at each location
    }
}
```

At each itinerary stop, visitor performs the `Activity`:

- **PRAY**: walks to temple, kneels, pays offering into temple
  treasury.
- **STAY**: books room at inn (payment to innkeeper); spends idle
  time.
- **EAT**: buys a meal; short interaction at bakery or inn.
- **TRADE**: opens market interaction, buys/sells via channel router.
- **SHOP**: similar to TRADE but specific to one item in mind.
- **ATTEND_LESSON**: walks to scholar, fires a teaching interaction
  (LITERACY XP for both, scholar earns fee).
- **DELIVER_MESSAGE**: hands a letter to a specific NPC (uses
  letter system).
- **PERFORM**: performs at town square; nearby NPCs get small mood
  boost; minstrel collects tips.
- **SELL_GOODS**: opens a temporary stall at market with exotic
  goods (1-day availability).

### Ephemeral economic impact

- Visitors carry 5-50 bronze in wallets (by type).
- Coin spent at each activity location enters that building's
  treasury.
- Village takes market tax if applicable.
- Kingdom takes COIN_INFLUX contribution to sim.

This is the mechanism by which specialized villages earn their
import budget — pilgrims paying for rites = COIN_INFLUX = ability to
buy food from agricultural villages.

## Special visitor types

### Minstrel

Performs at town square during evening; nearby villagers get +5 mood
each; collected tips go to minstrel wallet. Leaves after 1-2 nights.

Minstrels carry rumors; during their stay they gossip with local NPCs,
injecting cross-kingdom knowledge. Good source of foreign-category
knowledge for isolated villages.

### Envoy

Official visit from another village or kingdom. Delivers a sealed
letter to local leader. Letter may contain:
- Treaty proposal (future diplomacy).
- Request (bridges to request board).
- News of notable events elsewhere.

Envoy stays 1 day, awaits response if needed, leaves.

### Refugee

Flees another village due to hardship. Arrives destitute.

- Requests lodging / food at inn or temple.
- High-Compassion leader with housing slot may offer permanent
  settlement.
- If accepted, refugee becomes regular villager (no longer Visitor);
  origin village loses a citizen.
- If rejected, refugee moves on to another village.

Refugees primarily appear during crisis events (plague, famine,
warfare if ever added). Not a regular flow.

### Scholar Visiting

Peer-to-peer scholar exchange. Visits the local scholar:

- High-fidelity knowledge transfer between scholars.
- Guest lecture opportunity at library (villagers can attend).
- Brings books for trade or gift.
- Leaves after 3-5 days.

## Economic coupling

### Sim contribution

`VisitorFluxEngine.estimateFlux(village)` contributes `COIN_INFLUX`
to `VillageSimData`:

```java
public static float estimateFlux(Village v, VillageSavedData data) {
    VillageVisitorCapacity cap = computeCapacity(v, data);
    float expectedCoin = cap.arrivalRatePerDay() * AVG_COIN_PER_VISITOR;
    return expectedCoin;
}
```

Village's COIN_INFLUX is then available for export — e.g. purchasing
food from a farming village via the request board.

### Treasury effect

Visitor coin goes to:
- Temple treasury (offerings).
- Inn building economy (lodging/food).
- Market tax (10% of trades).
- Scholar / library treasuries (lessons, book sales).
- Individual NPC wallets (minstrel performers, merchants).

Aggregate effect is significant for specialized villages.

## Capacity growth

Village capacity scales with infrastructure:

- Base capacity for any village: 1 visitor, 0.1/day.
- Inn building: +5 concurrent, +0.5/day (TRAVELER weight).
- Temple building: +3 concurrent, +0.3/day (PILGRIM weight).
- Market building: +3 concurrent, +0.4/day (TRAVELER, MERCHANT_ITINERANT).
- Scholar's retreat: +2 concurrent, +0.2/day (STUDENT, SCHOLAR_VISITING).
- Library: +2 concurrent, +0.1/day (STUDENT).
- Guild hall (any): +2 concurrent, +0.1/day.

Sum capacities cap at reasonable village size (e.g. max 20 concurrent
across all types).

## Integration points

### Phase 4 integration

- `Visitor` entity class (extends `TownspersonMob`).
- `VisitorType` + `VisitorItinerary` + `Activity` structures.
- `VisitorFluxEngine` ticker.
- `VisitorGoal` registered for visitors.
- `VillageVisitorCapacity` computed per village.
- Spawn locations computed from gates / edges.
- Activity handlers hook into existing systems (trade, rites,
  lessons, letters).
- `EconomicChannel.VisitorChannel` fully wired (was stub in Phase 3).
- `VillageSimData` COIN_INFLUX integration.
- `/visitor spawn <village> <type>` debug command.
- `/visitor list <village>` debug command.

### Phase 5+ integration

- Cultural flavor (different cultures attract different visitor mixes).
- Visitor-generated events (plague carrier, notable visitor).
- Narrative hooks: famous visitor passes through and affects local
  NPC life goals.

## Behavior contract

### Does

- Spawn ephemeral visitors at village edges.
- Drive them through multi-stop itineraries.
- Route their coin through appropriate buildings/NPCs.
- Contribute aggregate COIN_INFLUX to sim.
- Handle specialized visitor types (envoy, refugee, minstrel).
- Despawn on itinerary completion or timeout.

### Does not

- Model inter-visitor interactions (multiple visitors don't talk to
  each other meaningfully).
- Create permanent visitor families or relationships beyond single
  stay.
- Generate visitor memories / backstories beyond type metadata.
- Handle visitor AI safety (combat, etc.) beyond existing
  `TownspersonMob` behavior.

## Edge cases

- **Visitor target building destroyed.** Itinerary entry fails;
  visitor picks next or aborts and leaves.
- **Visitor killed by hostile mob.** Normal death; flags village
  reputation hit for being "unsafe".
- **Visitor refuses to leave** (itinerary mismatch). Failsafe:
  despawn after 7 days maximum, regardless.
- **No suitable building for visitor's activity.** Visitor arrives
  anyway, fails first itinerary entry, picks fallback (inn for
  TRAVELER) or leaves.
- **Village at visitor capacity.** No new arrivals until slot frees.

## Ordering dependencies

Phase 4 depends on:
- Existing TownspersonMob infrastructure.
- Village gate/edge data.
- Trade / channel router (Phase 3).
- Letter system (Phase 2) — envoy/message visitors.
- Resource categories (Phase 4 same-phase) — COIN_INFLUX.
- Religion (Phase 3) — pilgrim behavior.
- Existing wandering-trader system — merchant itinerant is the
  evolution of that.

## Open decisions

- Should refugees bring existing personality/memory data? **Proposed:
  no — refugees spawn fresh with typical baseline. Simplifies.**
- Visitor despawn radius — how far does player need to be for
  despawn to be "safe"? **Proposed: standard Minecraft mob despawn
  rules; extended for visitor itinerary.**
- Visitor-carried rumors: do they transmit to hearing NPCs
  automatically? **Proposed: yes — each visitor comes with 1-2
  seed rumors from origin; these propagate via existing gossip
  system during stay.**

## Does-not-include

- Visitor loyalty / repeat visits (minor stretch). Currently each
  visit is independent.
- Visitor-initiated long-term quests.
- Multi-stop journeys spanning multiple villages (visit A, go to B,
  return). Each visit is single-village in v1.
- Tourist-style visitors (sightseeing for its own sake). Visitors
  come with purpose.

## Revision Notes

(changes recorded here as the spec evolves after testing)
