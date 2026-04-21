# Building Profile — API Reference

## BuildingProfileRegistry

### Weight constants (already defined in the registry)
```
W_PRIMARY   = 100  — best-matching slot
W_SECONDARY =  70
W_TERTIARY  =  45
W_FALLBACK  =  25
W_BACKFILL  =  10  — absolute last resort
```

### put() signature
```java
put(BuildingType type, AnchorPolicy anchor, AvoidanceRule avoid, SlotPreference... tiers)
```

### Helper shorthands (use instead of put() when applicable)
```java
// Standard civic landmark (TOWN_HALL, GUILD_HALL, CHANCELLERY…)
landmarkCivic(BuildingType.X);
// → CORE, sameTypeMin(24), PRIME_CIVIC → SECONDARY_CIVIC → CIVIC_ADJACENT

// Standard production worker (BLACKSMITH, CARPENTRY, STONEMASON…)
production(BuildingType.X);
// → CORE, null avoidance, PRODUCTION_CLUSTER → PRODUCTION_SPUR_END → PRODUCTION_INFILL → ROAD_ADJACENT
```

### AvoidanceRule
```java
null                              // no avoidance (most buildings)
AvoidanceRule.sameTypeMin(24)     // keep same-type buildings 24+ blocks apart
AvoidanceRule.sameTypeMin(32)     // use for towers / defensive structures
```

### SlotTag quick reference

| Tag | Primary user |
|-----|-------------|
| `PRIME_CIVIC` | TOWN_HALL, MARKET, INN |
| `SECONDARY_CIVIC` | Secondary civic buildings |
| `CIVIC_ADJACENT` | CHAPEL, auxiliary civic |
| `PRODUCTION_CLUSTER` | Standard workshop |
| `PRODUCTION_SPUR_END` | Spur-tip production |
| `PRODUCTION_INFILL` | Road-side production |
| `RESIDENTIAL_INFILL` | HOUSE |
| `RESIDENTIAL_OUTER` | STABLE, NOBLE_MANOR |
| `FIELD_EDGE` | FARMHOUSE, STOCKPILE |
| `PASTURE` | FARMHOUSE (alternate) |
| `WALL_ADJACENT` | GUARD_TOWER, PRISON, TREASURY |
| `GATE_ADJACENT` | BARRACKS, GUARD_TOWER |
| `HIGH_GROUND` | WATCHTOWER, MINE, CASTLE |
| `SHORE` | FISHERY, PIER |
| `PIER_ADJACENT` | DOCKS |
| `RIVER_BANK` | MILLER, DOCKS |
| `TERRACE_EDGE` | VINEYARD |
| `FOREST_EDGE` | WOODCUTTER |
| `HILLTOP_PEAK` | CASTLE |
| `ROAD_ADJACENT` | Generic fallback |
| `BACKFILL` | Last resort filler |

### Example entries
```java
// Specialty water building
put(BuildingType.FISHERY, AnchorPolicy.CORE, null,
        tier(W_PRIMARY,   SHORE),
        tier(W_SECONDARY, PIER_ADJACENT),
        tier(W_TERTIARY,  RIVER_BANK));

// Defensive tower with spacing
put(BuildingType.WATCHTOWER, AnchorPolicy.CORE, AvoidanceRule.sameTypeMin(32),
        tier(W_PRIMARY,   HIGH_GROUND),
        tier(W_SECONDARY, WALL_ADJACENT),
        tier(W_FALLBACK,  RESIDENTIAL_OUTER));

// Filler residential
put(BuildingType.HOUSE, AnchorPolicy.FILLER, null,
        tier(W_PRIMARY,   RESIDENTIAL_CORE),
        tier(W_SECONDARY, RESIDENTIAL_INFILL),
        tier(W_TERTIARY,  RESIDENTIAL_OUTER),
        tier(W_BACKFILL,  BACKFILL));
```

---

## BuildingInhabitantSpec

### Builder methods
```java
BuildingInhabitantSpec.builder()
    .resident(Profession.X)          // lives here, no separate housing needed
    .worker(Profession.X)            // works here, needs separate housing
    .household(spouseChance,         // generic household: HEAD + optional spouse + children
               maxChildren,
               childChanceEach)
    .workerHousehold(Profession.X,   // professional household (e.g. FARMHOUSE)
                     spouseChance,
                     maxChildren,
                     childChanceEach)
    .build()
```

### Profession values (common ones)
```
VILLAGE_LEADER, BUILDER, CHANCELLOR, HERALD, GUILDMASTER, GUILDWORKER,
MERCHANT, INNKEEPER, SCHOLAR, PRIEST, KINGDOM_RULER,
FARMER, BLACKSMITH, CARPENTER, MILLER, BAKER, STONEMASON, WEAVER,
CANDLEMAKER, MINER, STOCKPILE_KEEPER, GUARD, CITIZEN, NONE
```

### FamilyRole (set automatically by builder methods)
- `resident()` / `worker()` → `FamilyRole.HEAD`
- `household()` → HEAD + optional SPOUSE + CHILD members

### Typical patterns
```java
// Single professional worker (most production buildings)
.worker(Profession.BLACKSMITH)

// Building manager who lives on-site
.resident(Profession.INNKEEPER)

// Household with profession (farm, noble manor)
.workerHousehold(Profession.FARMER, 0.6f, 3, 0.4f)

// Multiple workers (BARRACKS, MARKET)
.worker(Profession.GUARD)
.worker(Profession.GUARD)

// Civic with leader + support
.resident(Profession.VILLAGE_LEADER)
.worker(Profession.BUILDER)

// Purely decorative / no NPC
BuildingInhabitantSpec.EMPTY  // or omit registration entirely
```

---

## BuildingAdjacencySpec

Hard or soft terrain feature constraints:
```java
BuildingAdjacencySpec.builder()
    .requires("water",  12)   // hard: must be within 12 blocks of water
    .requires("river",  24)   // hard: must be within 24 blocks of river
    .prefers("forest",  48)   // soft: tries to be within 48 blocks of forest
    .build()
```

Feature strings: `"water"`, `"river"`, `"coast"`, `"forest"`.

Use `requires` for buildings that literally cannot function without the feature
(DOCKS, MILLER). Use `prefers` for buildings that benefit from proximity
but don't strictly require it (WOODCUTTER).
