# Village Type Datagen — API Reference

## VillageTypeBuilder Methods

```java
VillageTypeBuilder.create("type_id", "culture")   // required — start here

// Shape profile
.shape(ShapeType.RADIAL)           // required
.forcedAxis(true)                  // default false — forces road along bestFlatDir
.maxRings(2)                       // default 2
.streetDensity(1.0f)               // default 1.0
.walled(true)                      // default false

// Top-level
.terrainStrategy(TerrainStrategy.FLAT)   // required
.townSquareCapacity(5)                   // required, typical 2–8

// Buildings
.building(BuildingType.X, "structure/level_1")           // exactly 1
.buildingN(BuildingType.X, "structure/level_1", 3)       // exactly N
.buildingRange(BuildingType.X, "structure/level_1", 2, 4) // random in [min, max]

.build()  // returns JsonObject
```

## ShapeType Values

| Value | Terrain | Notes |
|-------|---------|-------|
| `RADIAL` | Flat | Default balanced village |
| `LINEAR` | Any | Main road with side spurs |
| `CLUSTERED` | Flat | Loose cluster, no formal road |
| `RIVERINE` | Water | Parallel to river/shore |
| `HILLTOP` | Mountain | Defensive citadel |
| `PLAZA` | Flat | Urban central plaza |
| `CROSSROADS` | Flat | Four-armed trade hub |
| `CHAIN` | Any | Curved arc around obstacle |
| `ROADSIDE` | Any | One-sided road strip |
| `GROVE` | Forest | Buildings among trees |
| `SPRAWL` | Flat | Low-density spread |
| `DOCKSIDE` | Water | Pier running into water |
| `DUAL_PLAZA` | Flat | Two connected plazas |
| `OUTPOST` | Any | Small remote settlement |
| `TERRACED` | Slope | Stepped along contour |
| `ENCLAVE` | Any | Walled perimeter |
| `DUMBELL` | Any | Ring–trunk–ring |

## TerrainStrategy Values

| Value | Best for | Steps |
|-------|----------|-------|
| `FLAT` | Plains, farming | Clear trees → fill holes → light smooth → level pads |
| `SLOPE_AWARE` | Forest, mixed | Clear trees → fill holes → level pads → retaining walls → foundations |
| `MOUNTAIN` | Hilltop, cliff, terraced | Clear trees → level pads → retaining walls → foundations |
| `WATERFRONT` | River, coastal, dockside | Clear trees → fill holes → detect shoreline → level pads → retaining walls → foundations |

## BuildingType Catalogue

**Civic:** `TOWN_HALL`, `MARKET`, `INN`, `GUILD_HALL`, `CHANCELLERY`, `LIBRARY`, `TEMPLE`, `TREASURY`, `CHAPEL`, `BAKERY`

**Production:** `BLACKSMITH`, `TOOLSMITH`, `ARMORER`, `CARPENTRY`, `STONEMASON`, `WEAVER`, `CANDLEMAKER`, `APOTHECARY`, `ATELIER`, `WINERY`, `WAREHOUSE`, `WOODCUTTER`, `MINE`, `MILLER`, `DOCKS`, `FISHERY`, `PIER`, `VINEYARD`, `STOCKPILE`

**Residential:** `HOUSE`, `NOBLE_MANOR`, `STABLE`

**Agricultural:** `FARMHOUSE`

**Defensive:** `GUARD_TOWER`, `WATCHTOWER`, `BARRACKS`, `PRISON`, `CASTLE`

## Typical Building Counts by Village Theme

| Theme | TOWN_HALL | HOUSE | FARMHOUSE | Notes |
|-------|-----------|-------|-----------|-------|
| Hamlet | 1 | 3–4 | 1–2 | Minimal civic, few houses |
| Standard | 1 | 3–5 | 1–2 | Balanced production + residential |
| Urban | 1 | 4–6 | 0 | Heavy civic + production |
| Farming | 1 | 3–5 | 3–4 | Many farmhouses |
| Military | 1 | 3–5 | 0 | BARRACKS, GUARD_TOWER, ARMORER |
| Water | 1 | 3–4 | 0 | FISHERY/DOCKS/MILLER + waterfront strategy |

## Town Square Capacity Guidelines

| Capacity | Use |
|----------|-----|
| 2–3 | Small hamlet / outpost; few civic buildings |
| 4–5 | Standard village; MARKET + INN + a few others |
| 6–8 | Urban / trade city; many civic buildings needed |

## Structure Path Convention

All structure paths follow `"building_type/level_1"` for level-1 structures.
Examples: `"town_hall/level_1"`, `"blacksmith/level_1"`, `"house/level_1"`.
The structure file must exist at `data/<modid>/structures/<path>.nbt`.

## Water-Dependent Buildings

These buildings require `WATERFRONT` terrain strategy and should only
appear in RIVERINE, DOCKSIDE, or other water layouts:

`DOCKS`, `FISHERY`, `PIER` — require water adjacency to function.
`MILLER` — requires river adjacency (hard adjacency constraint in registry).
