# 25 — Resource Categories

## Purpose

`VillageSimData` currently tracks food and building-materials as two
flat floats. Can't express a mining village producing ore, or a shrine
village producing liturgical goods and consuming food. For supply
chains between specialized villages to work, the sim needs multi-
category resource tracking.

This subsystem expands `NeedCategory` to a full `ResourceCategory`
set, generalizes `VillageSimData` to `Map<ResourceCategory, Float>`,
and wires per-building contribution to the appropriate categories.

## Data model

### ResourceCategory

```java
public enum ResourceCategory {
    // Existing (mapped from NeedCategory):
    FOOD, BUILDING_MATERIALS, SEEDS,

    // New:
    TOOLS,           // iron/wood tools, plows, hammers
    WEAPONS,         // swords, bows, armor
    CLOTH,           // fabric, leather, raw wool
    LUXURY,          // spices, fine goods, art
    PAPER,           // paper, ink, parchment
    LITURGICAL,      // candles, incense, sacred items
    MEDICINE,        // herbs, remedies
    LIVESTOCK,       // live animals
    COIN_INFLUX;     // net bronze from visitors / trade surplus

    public boolean isPhysical();   // false for COIN_INFLUX
}
```

### VillageSimData (refactored)

```java
public class VillageSimData {
    private final UUID villageId;
    private final Map<ResourceCategory, Float> productionPerDay;
    private final Map<ResourceCategory, Float> consumptionPerDay;
    private int simulatedPopulation;
    private int farmhouseCount;   // legacy; may be derived
    private int mineCount;
    private long lastSyncTick;
    private float treasuryBalanceEstimate;
    private float wageDrainPerDay;
    private float taxIncomePerDay;

    public float production(ResourceCategory c);
    public float consumption(ResourceCategory c);
    public float net(ResourceCategory c);
    public boolean isExporterOf(ResourceCategory c);
    public boolean isImporterOf(ResourceCategory c);

    public void blendReal(Map<ResourceCategory, Float> realProd,
                          Map<ResourceCategory, Float> realCons,
                          int realPop, long tick);

    public void advanceSim(float seasonFoodMult, float genericMult);
}
```

Backwards compat: migrate `foodProductionPerDay` → `production.get(FOOD)`;
`materialProductionPerDay` → `production.get(BUILDING_MATERIALS)`.
Unknown categories default 0.

## Per-building contribution

```java
public record BuildingResourceProfile(
    Map<ResourceCategory, Float> productionPerDay,
    Map<ResourceCategory, Float> consumptionPerDay
) {
    public static final Map<BuildingType, BuildingResourceProfile> TABLE = Map.of(
        BuildingType.FARMHOUSE,  new BuildingResourceProfile(
            Map.of(FOOD, 8f, SEEDS, 2f),
            Map.of(SEEDS, 1f, FOOD, 1f)),
        BuildingType.MINE, new BuildingResourceProfile(
            Map.of(BUILDING_MATERIALS, 6f),
            Map.of(TOOLS, 0.5f, FOOD, 2f)),
        BuildingType.BLACKSMITH, new BuildingResourceProfile(
            Map.of(TOOLS, 3f, WEAPONS, 1f),
            Map.of(BUILDING_MATERIALS, 4f, COIN_INFLUX, 2f)),
        BuildingType.CARPENTRY, new BuildingResourceProfile(
            Map.of(TOOLS, 2f, BUILDING_MATERIALS, 1f),
            Map.of(BUILDING_MATERIALS, 3f)),
        BuildingType.BAKERY, new BuildingResourceProfile(
            Map.of(FOOD, 4f),
            Map.of(FOOD, 2f)),
        BuildingType.WEAVER, new BuildingResourceProfile(
            Map.of(CLOTH, 3f),
            Map.of(LIVESTOCK, 0.5f, BUILDING_MATERIALS, 0.5f)),
        BuildingType.SCRIBE_WORKSHOP, new BuildingResourceProfile(
            Map.of(PAPER, 1f),
            Map.of(PAPER, 0.5f, BUILDING_MATERIALS, 0.2f)),
        BuildingType.TEMPLE, new BuildingResourceProfile(
            Map.of(COIN_INFLUX, 5f),
            Map.of(LITURGICAL, 2f, FOOD, 1f)),
        BuildingType.MARKET, new BuildingResourceProfile(
            Map.of(COIN_INFLUX, 3f),
            Map.of()),
        BuildingType.HOUSE, new BuildingResourceProfile(
            Map.of(),
            Map.of(FOOD, 2f, CLOTH, 0.3f, BUILDING_MATERIALS, 0.1f)),
        BuildingType.INN, new BuildingResourceProfile(
            Map.of(COIN_INFLUX, 4f),
            Map.of(FOOD, 2f, CLOTH, 0.5f))
        // ... etc.
    );
}
```

Values tuned later; Phase 4 ships first-pass numbers.

## Sim computation

`VillageSimEngine.syncFromReal` rewritten:

```java
public static void syncFromReal(ServerLevel level, Village village,
                                VillageSavedData data, long tick) {
    VillageSimData sim = data.getSimData(village.getId())
        .orElseGet(() -> VillageSimData.create(village.getId()));

    Map<ResourceCategory, Float> realProd = new EnumMap<>(ResourceCategory.class);
    Map<ResourceCategory, Float> realCons = new EnumMap<>(ResourceCategory.class);
    for (ResourceCategory c : ResourceCategory.values()) {
        realProd.put(c, 0f);
        realCons.put(c, 0f);
    }

    int pop = countPopulation(level, village, data);
    float seasonFoodMult = SeasonTracker.currentSeason(tick).getFoodNeedMultiplier();
    realCons.merge(FOOD, pop * BASE_FOOD_PER_NPC * seasonFoodMult, Float::sum);

    for (var bid : village.getBuildingIds()) {
        Building b = data.getBuildingById(bid).orElse(null);
        if (b == null) continue;
        BuildingResourceProfile profile = BuildingResourceProfile.TABLE.get(b.getType());
        if (profile == null) continue;
        profile.productionPerDay().forEach((c, v) -> realProd.merge(c, v, Float::sum));
        profile.consumptionPerDay().forEach((c, v) -> realCons.merge(c, v, Float::sum));
    }

    realProd.merge(COIN_INFLUX, estimateVisitorFlux(village, data), Float::sum);

    sim.blendReal(realProd, realCons, pop, tick);
}
```

## Kingdom-level economy engine

`KingdomEconomyEngine` consumes multi-category data for partner
selection:

```java
public static Optional<Village> findExportPartner(
        Kingdom seekingKingdom, Village importer,
        ResourceCategory needed, VillageSavedData data) {

    return data.getAllVillages().stream()
        .filter(v -> !seekingKingdom.containsVillage(v.getId()))
        .filter(v -> {
            VillageSimData sim = data.getSimData(v.getId()).orElse(null);
            return sim != null && sim.isExporterOf(needed)
                && sim.net(needed) > MIN_SURPLUS_THRESHOLD;
        })
        .min(Comparator.comparingDouble(v -> distanceTo(v, importer)));
}
```

Generalizes existing food-or-materials split to all categories.

## Integration points

### Phase 4 integration

- `ResourceCategory` enum.
- `BuildingResourceProfile.TABLE` for all existing building types.
- `VillageSimData` refactored to category maps.
- `VillageSimEngine.syncFromReal` rewritten.
- `KingdomEconomyEngine` updated.
- `NeedCategory` aliased to `ResourceCategory` for compat.
- `/sim resources <village>` debug.
- Save migration via codec `optionalFieldOf` defaults.

### Phase 4 consumers

- `TradeRouteManager` uses category-level surplus.
- `GuildRequestChannel` / request board routes by category deficit.

## Behavior contract

### Does

- Replace flat food/material sim with multi-category map.
- Derive per-building contribution via static profile table.
- Surplus/deficit queries across categories.
- Backward-compatible save format.

### Does not

- Simulate every item individually. Categories are coarse.
- Model economic elasticity (price response to surplus).
- Track per-NPC consumption differences beyond category averages.

## Edge cases

- **Building type with no profile.** Empty contribution; neutral.
- **Zero-net category.** Partner search returns empty; request board
  may handle.
- **Negative production.** Clamped 0; interpreted as consumption.

## Ordering dependencies

Phase 4 depends on:
- Existing VillageSimData, VillageSimEngine, KingdomEconomyEngine.
- Existing NeedCategory for migration.

## Open decisions

- LIVESTOCK: category or farmhouse variant? **Proposed: category;
  profile distinguishes.**
- Resync cadence. **Proposed: once per in-game day loaded; advance-
  sim for unloaded. Matches existing.**
- LUXURY → higher COIN_INFLUX? **Proposed: yes; LUXURY producers
  contribute to both LUXURY (supply) and COIN_INFLUX (sales). Special
  case in profile.**

## Does-not-include

- Per-village custom resource profiles (all buildings of a type
  uniform).
- Quality tiers within a category. FOOD is FOOD.
- Commodity-level price data. Stays at aggregates.

## Revision Notes

### Phase 4 task 25 implementation pass

Things-to-flag responses:

1. **First-pass profile values.** Shipped per the spec's example
   table; values feel reasonable for v1 but every entry is a candidate
   for Phase 5 tuning. Particularly subjective: TEMPLE COIN_INFLUX = 5
   (high relative to MARKET = 3 — assumes pilgrim donations dominate
   over market traffic), CARPENTRY producing both TOOLS and
   BUILDING_MATERIALS (real shops only output one or the other), and
   the LIVESTOCK consumption on WEAVER (proxy for "wool source" — a
   real model would have STABLE produce LIVESTOCK and WEAVER consume).
2. **Legacy save migration.** Confirmed field names —
   `foodProductionPerDay`, `foodConsumptionPerDay`,
   `materialProductionPerDay`, `materialConsumptionPerDay`. All four
   read as `optionalFieldOf(name, 0f)` and merge into the new maps via
   `Float::sum` in `VillageSimData.fromCodec`. On round-trip, the
   getters return 0f and DFU's `optionalFieldOf` elides default values
   from the written form, so the legacy keys disappear after the first
   save under the new format.
3. **Negative production clamping.** Ships in the blend step rather
   than the profile-table step. `VillageSimData.blendReal` clamps each
   incoming category value via `clamp(v) = max(0, v)` before the
   80/20 average. This makes the table values informational —
   negative entries would just be flattened. `BuildingResourceProfile`
   itself does not enforce ≥ 0.
4. **COIN_INFLUX placeholder.** `VillageSimEngine.estimateVisitorFlux`
   returns 0 unconditionally. Phase 4 doc 29 (visitor flux) replaces
   this with a real estimate. Documented inline at the call site so
   the wiring point is obvious.

Spec deviations:
- **NeedCategory left in place.** Spec line 188 mentions "NeedCategory
  aliased to ResourceCategory for compat." v1 keeps them as separate
  enums because `NeedCategory` is consumed by `village.getNeeds()` —
  a spot-state stockpile query, distinct from the rolling-average
  sim. The two share names so a future unification is mechanical;
  doing it now would touch every consumer of `village.getNeeds()`,
  which is out of scope for this session.
- **`KingdomEconomyEngine.findExportPartner` no longer takes a
  `ServerLevel`.** The previous signature accepted one but never used
  it — the partner search reads only `VillageSavedData` and per-village
  bounds. Dropped the parameter; the surviving call site
  (`handleDeficit` inside the same class) doesn't need it.
- **`KingdomEconomyEngine.evaluate` log line.** Previously hard-coded
  `food` / `mat` columns; now iterates `EXPORT_THRESHOLDS` so any
  category we add a threshold for shows up in the daily log line.

Audit-discovered fixes:
- `VillageSimEngine.reconcileOnLoad` originally re-blended via
  `new EnumMap<>(sim.productionView())`. The view is
  `Collections.unmodifiableMap` over an `EnumMap`, but the wrapper is
  a generic `Map` — the `EnumMap(Map)` constructor throws on an empty
  generic map. Brand-new villages would crash on their first
  reconcile. Switched to `new EnumMap<>(ResourceCategory.class)` +
  `putAll(view)`.
- `BuildingResourceProfile.FARMHOUSE` consumption listed SEEDS twice
  via the 3-arg `m1` helper (which `Float::sum`-merges duplicates),
  giving a 2-units/day SEEDS drain instead of 1. Trimmed to a single
  SEEDS entry.

Open follow-ups:
- A `LivestockProducer` profile (STABLE → LIVESTOCK production) so
  WEAVER's LIVESTOCK consumption isn't free.
- LUXURY / COIN_INFLUX coupling per spec "Open decisions" #3 —
  currently every LUXURY producer also lists COIN_INFLUX, so the
  effect lands but isn't formalised as a rule.
