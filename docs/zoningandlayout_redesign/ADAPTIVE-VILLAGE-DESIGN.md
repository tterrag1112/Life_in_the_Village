# Adaptive Village Generation — Design Document

## Status and Provenance

This document defines the adaptive village generation system that replaces the multi-shape recipe architecture built across Phases A-D and supersedes the original `ADAPTIVE-LAYOUT-SPEC.md`. It is the authoritative reference for the system's design.

**Replaces / deletes**: ShapeType enum, all 17 Recipe classes, the slot/intention/anchor/emitter machinery, the cascade engine, the validator-cap-by-construction approach, all VillageType data files in their current form, and most of PlacementMatcher.

**Subject to revision**, but should not be silently mutated. Any architectural change here should be a deliberate edit recorded in this document.

---

## Goal

Produce villages that:
- Spawn reliably at any viable site (every site that admits at least a 3-building hamlet should produce a working village)
- Adapt their layout to the surrounding terrain (rivers, forests, slopes, coasts shape the village)
- Feel varied across instances (no two villages on different terrain look the same)
- Have a coherent gathering point (every village has a recognisable centre, the town hall by default)
- Compose from simple components rather than 17 hand-authored shape templates

The success bar is **"every viable site spawns the largest village it can support; smaller sites get smaller villages; only genuinely unviable terrain returns empty"** — not the impossible "every random world position spawns a village."

---

## Core Philosophy

**Terrain-first.** What's on the ground determines what kind of village can exist there. Rivers create riverine villages. Mountains create mining camps. Plains create farms or cities. The system reads terrain and adapts; it doesn't impose a pre-chosen shape and try to fit it.

**Buildings-first.** Buildings choose their own positions based on what they need (terrain, adjacency, centrality). Roads are built afterward to connect them. This inverts the previous architecture (recipe → roads → slots → buildings) into (terrain → buildings → roads).

**Composable layers.** Five layers, each does one thing, each replaceable. No overlapping responsibilities. No layer reaches into another's data.

**Culture as a thin overlay.** Culture biases choices (road material, plaza shape preference, building variant selection) but doesn't change layout fundamentals. Culture is regional (assigned at world-gen, currently always "default").

**Inclination as a small bias set.** Each village leans toward an inclination (agricultural, industrial, defensive, civic, residential, sacred), chosen from terrain + culture + seed. Inclination biases which buildings are selected and how heavily different placement factors weigh.

---

## Architecture: Five Layers

### Layer 1 — Terrain Feature Map

**Input**: Candidate centre position, scan radius (~150 blocks).

**Output**: A queryable map with per-cell labels and aggregate queries.

**Per-cell labels**:
- Elevation (y)
- Local slope (max neighbour delta over 3×3)
- Block category at top: water / shore / open / forest / stone-exposed / structure
- Distance to nearest water
- Distance to nearest forest edge
- Distance to nearest exposed stone

**Aggregate queries**:
- `largestFlatRegion()` → centre and area of the largest contiguous flat patch
- `riverPath()` → ordered points if a river runs through the area
- `coastline()` → ordered points if shoreline present
- `peakPoints()` → list of local maxima
- `forestEdges()` → boundary lines of forest patches
- `stoneExposedRegions()` → contiguous patches of exposed stone

**Reuses**: existing `FeatureMap`, expanded with the aggregate queries.

### Layer 2 — Site Scoring & Anchor Selection

**Input**: FeatureMap, Culture, RNG seed.

**Output** (`SiteContext`):
- Viability tier (CITY / TOWN / HAMLET / OUTPOST / UNVIABLE)
- Anchor point (the village's primary fixed point)
- Inclination
- Primary spine direction (a hint for Layer 4 — usually parallel to dominant terrain feature)

**Viability ladder**:

| Tier     | Min flat region | Max slope fraction | Target buildings |
|----------|-----------------|--------------------|-----------------|
| CITY     | 80×80           | 0.15               | 50+             |
| TOWN     | 40×40           | 0.25               | 20-30           |
| HAMLET   | 20×20           | 0.40               | 8-12            |
| OUTPOST  | 10×10           | 0.60               | 3-5             |
| UNVIABLE | —               | —                  | 0               |

Numbers indicative; tunable once Layer 1 is real.

**Anchor selection** is biased by which terrain features dominate the scan area:
- Default: centroid of largest flat region
- Riverine bias: a point along the riverbank near the largest flat region
- Coastal bias: a point inland from the shoreline near the largest flat region
- Hilltop bias: the highest peak
- Mining bias: junction of mountain and flat ground

Multiple eligible biases → seed-based pick weighted by cultural preference.

**Inclination selection**:
1. Compute a terrain-affinity score for each inclination (large flat → AGRICULTURAL+CIVIC; exposed stone → INDUSTRIAL; high ground → DEFENSIVE; etc.)
2. Multiply by `culture.inclinationBias` (currently identity for default)
3. Sample weighted by combined score with seed

### Layer 3 — Building Placement

**Input**: FeatureMap, SiteContext, building library, RNG seed.

**Output** (`PlacementResult`): list of placed buildings (positions, footprints, rotations) and list of dropped buildings.

**Algorithm**:

1. **Compute building selection.** Walk the building library. For each building:
   - If `applicable_terrain` doesn't match (e.g., MILL requires water present in the FeatureMap aggregates), exclude.
   - Apply inclination weighting (`InclinationProfile.buildingTypeMultipliers`).
   - Sample from weighted distribution to fit the viability tier's target count.

2. **Topologically sort** by `requires_present` dependencies. Tie-break by priority (civic > infrastructure > production > residential), then by manifest-declared sub-priority.

3. **Place in dependency order.** For each building:
   - **Dependency check**: every BuildingType in `requires_present` has been successfully placed earlier. If any was dropped, drop this building.
   - **Generate candidates**: positions within scan area, not yet reserved, not on roads.
   - **Score each candidate** by summing weighted factors from the building's manifest (terrain weights, adjacency weights, centrality).
   - **Pick best**. If best score below threshold, drop building.
   - **Reserve actual footprint** (the variant selected, not a default).

4. **Output** the placed list and the dropped list.

**Drop semantics**:
- `required: true` failing → village fails entirely (Layer 2 should have prevented this; if it happens we bail to UNVIABLE).
- Non-required failing → drop, continue.
- Dropped due to missing dependency → cascades: any later building that required IT also drops automatically.

### Layer 4 — Road Network

**Input**: PlacedBuildings, primary spine direction, Culture, RNG seed.

**Output**: List of roads with centerlines, tiers, and primitive-type assignments.

**Algorithm**:

1. **Compute primary spine.** A single road through or near the anchor, oriented along `siteContext.primarySpineDirection`. Tier = VILLAGE_ROAD.

2. **Connect each building to the spine.** For each placed building, find the nearest point on the spine (or on an existing connector road), draw a connector. Tier = VILLAGE_PATH. If the building is already within a small distance (~3 cells) of a road, no new connector needed.

3. **Optional ring or loop** for larger villages. CITY and TOWN may add a ring road if building density admits it. HAMLET and OUTPOST do not.

4. **Material and curvature** chosen per culture: `culture.roadMaterial()` for blocks, `culture.preferredCurvature()` for choosing among StraightRoad / CurvedRoad / NaturalRoad primitives.

**Reuses**: existing RoadPrimitives (StraightRoad, CurvedRoad, Ring, Arc, Spur).

### Layer 5 — Validation & Realisation

**Input**: PlacedBuildings, RoadNetwork.

**Output**: A `LayoutPlan` compatible with the existing decoration / NPC / spawning pipelines.

**Algorithm**:

1. **Overlap check.** No two building footprints overlap. No building footprint overlaps a road's reserved corridor. If overlap detected: log warning, drop the lower-priority building, restart Layer 4. Should be rare if Layer 3 was honest about reservations.

2. **Per-building terrain adaptation.** For each placed building, check local slope under its actual variant footprint:
   - Slope ≤ 1-2 blocks → mark for **LEVEL** (existing TerrainStrategy LevelBuildingPads).
   - Slope moderate (3-5 blocks) → mark for **PLATFORM** (foundation pillars).
   - Slope severe (≥6 blocks) → drop the building (shouldn't happen if Layer 3 scored terrain correctly).

3. **Emit LayoutPlan** in the existing format so downstream systems are unchanged.

---

## Data Models

### Culture

```java
record Culture(
    String id,                              // "default", future: "stone_folk", etc.
    String roadMaterial,                    // block ID
    Curvature preferredCurvature,           // STRAIGHT | CURVED | NATURAL
    PlazaShape preferredPlazaShape,         // CIRCLE | SQUARE | IRREGULAR
    Map<Inclination, Double> inclinationBias  // weight multipliers
)
```

Loaded from JSON files in `data/cultures/`. Currently only `default.json` exists. Lookup falls back to default for any missing field.

### Inclination

```java
enum Inclination {
    AGRICULTURAL,
    INDUSTRIAL,
    DEFENSIVE,
    CIVIC,
    RESIDENTIAL,
    SACRED
}
```

Limited to 6 to avoid recreating the ShapeType problem. Each has an associated `InclinationProfile` JSON specifying:
- `buildingTypeMultipliers`: e.g. `"FARMHOUSE": 3.0, "SMITHY": 0.5`
- `wallPreference`: 0.0 = no wall, 1.0 = always wall (deferred to V2)
- `plazaPreference`: 0.0 = no formal plaza, 1.0 = always plaza
- `centralitySkew`: bias for civic vs sprawl shape

### Viability Tier

```java
enum ViabilityTier {
    CITY    (50, 80, 0.15),
    TOWN    (25, 40, 0.25),
    HAMLET  (10, 20, 0.40),
    OUTPOST (4,  10, 0.60),
    UNVIABLE(0,  0,  0.0);

    final int targetBuildingCount;
    final int minFlatRegionSize;
    final float maxSlopeFraction;
}
```

### Building Manifest — Placement Block

Added to each building's existing manifest:

```json
{
  "placement": {
    "required": false,
    "priority": "production",
    "size_class": "medium",
    "centrality": 0.4,

    "applicable_terrain": ["plains", "forest", "hills"],

    "requires_present": ["MINE"],

    "terrain_weights": {
      "flat": 1.0,
      "near_water": 0.5,
      "near_forest": 0.0,
      "near_stone": 0.0
    },

    "adjacency_weights": {
      "near_civic_centre": 0.5,
      "near_main_road": 1.0,
      "far_from_same_type": 0.5
    }
  }
}
```

**Field semantics**:

- `required` (bool): if true, the village fails entirely if this building can't be placed. Used by TOWN_HALL only in V1.
- `priority` (enum): `civic` | `infrastructure` | `production` | `residential`. Drives tie-breaking in topological sort.
- `size_class` (enum): `small` | `medium` | `large`. Affects which structure variant is selected at decoration time.
- `centrality` (float 0-1): 0 = wants outskirts, 1 = wants centre. Implemented as scoring against distance from anchor.
- `applicable_terrain` (list): biome filter. Building isn't selected if surrounding biome doesn't match.
- `requires_present` (list of BuildingType): every type listed must be successfully placed in the village before this building can be placed. If any required type is dropped, this building is dropped.
- `terrain_weights` (map of factor → weight ≥0): weights for normalised terrain factors at each candidate position. Higher weight = more important. Factor names: `flat`, `near_water`, `near_forest`, `near_stone`, `near_coast`.
- `adjacency_weights` (map of factor → weight ≥0): `near_X` (closer is better) and `far_from_X` (further is better) prefixes are mutually exclusive. Factor names: `near_civic_centre`, `near_main_road`, `near_anchor`, `far_from_same_type`, `far_from_water`, etc.

**Defaults** live in code keyed by BuildingType. Manifests only override what differs from default.

### Building Priority Order

Priority tiers used for topological tie-breaking:

- **civic**: TOWN_HALL → MARKET → INN → GUILD_HALL → TEMPLE → LIBRARY → CHAPEL → SHRINE
- **infrastructure**: WELL → BRIDGE → GATEHOUSE
- **production**: MINE → FARMHOUSE → MILL → SMITHY → BAKERY → CARPENTRY → STONEMASON → WEAVER → APOTHECARY → STABLE → STOCKPILE → WAREHOUSE → CANDLEMAKER
- **residential**: HOUSE (variants chosen by `size_class` × position-from-anchor at decoration time)

**V1 dependency chain (illustrative)**:
- `SMITHY.requires_present = [MINE]` — smithy needs the mine to exist somewhere in the village (not adjacent to it).
- `BAKERY.requires_present = [MILL]`
- `MILL.requires_present` is empty, but its `terrain_weights["near_water"]` is high enough that without a river, MILL won't be selected (gets very low scores).

A SMITHY placed before MINE in priority order would fail dependency check. Topological sort places MINE first (production tier, no deps); SMITHY follows (production tier, deps on MINE).

### Layer Interface Types

```java
// Layer 1 → Layer 2
record FeatureMap(
    BlockPos centre,
    int radius,
    Cell[][] cells,
    Map<FeatureType, FeatureRegion> aggregates
);

// Layer 2 → Layer 3
record SiteContext(
    BlockPos anchor,
    Vec3 primarySpineDirection,
    ViabilityTier tier,
    Inclination inclination,
    Culture culture,
    long seed
);

// Layer 3 → Layer 4
record PlacedBuilding(
    BuildingType type,
    BlockPos centre,
    Footprint footprint,        // ACTUAL variant footprint, not default
    Rotation rotation,
    Priority priority,
    String variant              // for size-class-based variant selection
);

record PlacementResult(
    List<PlacedBuilding> placed,
    List<DroppedBuilding> dropped,
    Map<BuildingType, Integer> counts
);

// Layer 4 → Layer 5
record RoadNetwork(
    List<Road> spine,
    List<Road> connectors,
    Optional<Road> ringRoad
);
```

**Layer 5 → existing decoration**: `LayoutPlan` in the existing format, populated from Layer 3 + 4 outputs. Decoration pipeline is unchanged.

---

## Worked Examples

### Example 1: Plains Farming Hamlet

**Terrain.** Mostly flat 80×80 region, small forest at edge, no water, no slope.

**Layer 1.** FeatureMap labels: cells mostly `open` with no slope. `largestFlatRegion()` returns the bulk of the area. `forestEdges()` returns the forest boundary. No river, no exposed stone.

**Layer 2.** Largest flat region area = 6400 → TOWN tier. Anchor: centroid of flat region. Inclination scoring: AGRICULTURAL high (large flat region affords farms), CIVIC moderate (default fallback), others low. Sampled: AGRICULTURAL. Primary spine: arbitrary (no dominant feature) — chosen by seed.

**Layer 3.** Building selection: AGRICULTURAL multiplier ×3 farms. Target count for TOWN = 25 buildings. Selected:
- 1 TOWN_HALL
- 1 MARKET, 1 INN
- 1 WELL
- 6 FARMHOUSE
- 1 MILL — but `near_water` weight high and no water → low scores → drops in selection sampling
- 1 SMITHY — `requires_present: [MINE]`, no MINE in selection → drops at dependency check
- 1 BAKERY — `requires_present: [MILL]`, MILL absent → drops
- 1 STABLE
- 12 HOUSE

After cascading drops: ~22-23 buildings.

Topological order: TOWN_HALL → MARKET → INN → WELL → FARMHOUSE×6 → STABLE → HOUSE×12.

Placement: TOWN_HALL at anchor. MARKET nearby (high centrality, near civic centre). INN along emerging spine. WELL central. FARMHOUSE on flattest patches further out (low centrality, terrain prefers flat). HOUSE filling the middle, mid centrality.

**Layer 4.** Spine through anchor along seed-chosen direction. Each building gets a connector to spine. No ring (TOWN tier may or may not have ring; this one chooses no ring by seed).

**Layer 5.** All footprints flat → all marked LEVEL. LayoutPlan with ~22 buildings emitted.

Result: a coherent farming hamlet on plains.

### Example 2: Riverine Trade Town

**Terrain.** River winds through a 100×100 area, flat banks on either side, light forest patches.

**Layer 1.** `riverPath()` returns the river points. `largestFlatRegion()` returns one of the riverbank flats.

**Layer 2.** Tier = TOWN (40×40 flat region available). Anchor: along the riverbank, at the largest flat region. Inclination: AGRICULTURAL and CIVIC tied; sampled CIVIC. Primary spine: parallel to the river.

**Layer 3.** Building selection: CIVIC weights MARKET, INN, GUILD_HALL higher. Selected:
- 1 TOWN_HALL
- 1 MARKET, 1 INN, 1 GUILD_HALL
- 1 MILL (river present, terrain weights satisfied)
- 1 BAKERY (MILL present → kept)
- 1 SMITHY (no MINE → dropped)
- 4 FARMHOUSE
- 1 STABLE, 1 WAREHOUSE
- 12 HOUSE

After: ~22 buildings.

TOWN_HALL placed at anchor (flat, near river). MARKET next, near TOWN_HALL. MILL placed on river itself. WAREHOUSE near river but slightly back. FARMHOUSE on flat patches. HOUSE filling in.

**Layer 4.** Spine parallel to river, passing near anchor. Connectors from each building to spine. No ring (river constrains the shape).

**Layer 5.** All flat → LEVEL. LayoutPlan emitted.

Result: a town stretched along the river with mill at the water and houses inland.

### Example 3: Mountain Mining Camp

**Terrain.** Mountain meeting flat ground. Small flat patch (15×15) at base. Exposed stone on the mountain slopes. Steep slopes beyond the flat.

**Layer 1.** `largestFlatRegion()` returns the small patch. `peakPoints()` returns mountain peak. Stone-exposed cells abundant on slopes.

**Layer 2.** Tier = OUTPOST (only 15×15 flat). Anchor: centre of flat patch. Inclination: stone-exposed dominant → INDUSTRIAL strong. Sampled: INDUSTRIAL. Primary spine: along the mountain base (the long axis of flat ground).

**Layer 3.** Building selection: INDUSTRIAL multiplier ×3 SMITHY, ×3 MINE, ×2 STOCKPILE. Target count for OUTPOST = 5 buildings. Selected:
- 1 TOWN_HALL
- 1 MINE
- 1 SMITHY (MINE present → kept)
- 1 STOCKPILE
- 1 HOUSE

Topological: TOWN_HALL → MINE → SMITHY → STOCKPILE → HOUSE.

TOWN_HALL placed at anchor. MINE placed against the mountain (stone-exposed cells satisfy `near_stone` weight). SMITHY near the centre of flat (good `near_civic_centre` score). STOCKPILE on flat. HOUSE on flat.

**Layer 4.** Spine along mountain base. Connectors from each building. MINE's connector is short (it's against the mountain at the spine's edge).

**Layer 5.** TOWN_HALL, STOCKPILE, HOUSE, SMITHY → LEVEL. MINE → PLATFORM (foundation pillars where it meets the slope). LayoutPlan emitted.

Result: a 5-building camp wedged between flat and mountain, mine entrance against the rock.

---

## What Survives, What Dies

### Survives

- **RoadPrimitives** (StraightRoad, CurvedRoad, Ring, Arc, Spur) — used by Layer 4
- **FeatureMap** — expanded into Layer 1
- **Plaza system** — used as one of several anchor types in Layer 2 / one of several layouts in Layer 3
- **TerrainStrategy steps** (ClearTrees, FillHoles, LightSmooth, LevelBuildingPads) — used by Layer 5 / decoration
- **Decoration pipeline** (PlazaPaver, VillageDecorator, road realisation, weathering) — unchanged
- **NPC population, GuildBootstrap, VillageInhabitantPopulator** — unchanged
- **BuildingType enum** — still the universe of buildings
- **Existing structure files** — still the actual building geometry
- **Building manifests** — extended with placement block

### Dies

- **ShapeType enum** — replaced by emergent shape from terrain
- **VillageType data files** — replaced by Inclination + building manifests + Culture
- **All 17 Recipe classes** — no recipes in adaptive system
- **LayoutBlueprint, SlotIntention, Anchor sealed interface, SectorRef, EdgeRef, RealisedEdge** — the entire Phase A-D adaptive package
- **SlotEmitter and the 6 resolvers** — buildings choose their own positions; no slot emission
- **PlacementMatcher's slot-based matching, recalibration, civic ring guard, fallback tiers, perturbation** — direct placement instead
- **Cascade engine, RecipeStatus, ReEmitReason** — Layer 3 just drops buildings on failure
- **Validator-cap-by-construction** — replaced by simple overlap check in Layer 5
- **Slot/Sector machinery** — buildings reference building-type and position; no slot abstraction
- **TerrainSuitability hard threshold** — replaced by viability ladder

### Migration

Build under `Village/Planning/V2/` (or similar) alongside the existing system. Add a config flag (`adaptive_v2: true/false`) selecting which path runs at spawn time. Build Layer 1, validate. Build Layer 2, validate. Build Layer 3 with one inclination (AGRICULTURAL), validate end-to-end on flat terrain. Build Layer 4 and 5. When five worked-example-equivalent villages produce visually good results on diverse terrain, flip the flag to default-on. Delete the old system in a single commit.

---

## Out of Scope (V1)

Explicit non-features. These are valid future work but not built into V1:

- **Walled / fortified villages.** Need a wall-planning step between Layers 4 and 5.
- **Multi-level terraced villages on steep slopes.** Need vertical-aware road planning (stair primitives).
- **Twin / divided villages.** Single-anchor model only.
- **Villages dominated by megastructures** (cathedral, castle). Mostly works in current model with high-priority placement, but custom anchor logic would help.
- **Sprawl perturbation pass.** No deliberate "kink" / irregularity added to make villages look unplanned.
- **Procedural / dynamic building generation.** Buildings are picked from existing structure files only.
- **Market-as-resource-wildcard.** SMITHY requires MINE; markets do NOT satisfy production dependencies in V1.
- **Adjacency requirements** (`requires_adjacency`). V1 has only `requires_present`. A SMITHY needs a MINE somewhere in the village, not next to it.
- **Cross-village trade or shared resources.** Each village is self-contained.
- **Modern, non-medieval, fantasy-extreme architectural styles.**
- **Cultural style biases beyond a few simple parameters.** V1 culture has roadMaterial, preferredCurvature, preferredPlazaShape, inclinationBias only.
- **Density gradient with explicit sub-zones** (CITY_CORE / CITY_OUTER). V1 uses only the per-building `centrality` scalar.

---

## Future Considerations

Things to design later, flagged for awareness:

- **Wall planning** layer between 4 and 5.
- **Stair / vertical road primitive** for terraced villages.
- **Multi-anchor / divided villages** for twin-cluster layouts.
- **Market-as-satisfier**: each civic building lists `satisfies` tags. Markets satisfy `raw_resources`, so SMITHY could require either MINE OR MARKET. V1 keeps it simple.
- **Adjacency requirements**: `requires_adjacency: [MINE]` for buildings that must be physically next to another. V1 has only presence requirements.
- **Density gradient zones** (CITY_CORE / CITY_INNER / CITY_OUTER) with different building variant selection rules per zone.
- **Road materials per tier**: spine in stone, paths in dirt; drives off culture.
- **Cultural building variant selection**: a "stone_folk" culture replaces wooden HOUSE variants with stone ones at decoration time.
- **Regional culture map**: world-gen produces a tiled map assigning cultures to regions. Currently every region is "default."
- **Inclination influence on TOWN_HALL variant**: a CIVIC TOWN_HALL is grand; an OUTPOST one is a longhouse.
- **Performance tuning**: scoring N_buildings × N_candidates × N_factors. Profile and optimise once Layer 3 is real.
- **Sprawl perturbation pass** for character/irregularity.

---

## Open Decisions Still Needed

These should be resolved before Layer 1 implementation begins. Each has a recommended default that can be overridden:

1. **FeatureMap cell size.** 1 block per cell → ~90,000 cells at radius 150 (memory-heavy). 2 or 4 blocks per cell loses some resolution but is cheaper. **Recommend: 2 blocks per cell.**

2. **Inclination weighting formula.** How are terrain-affinity, culture-bias, and seed combined? **Recommend**: `weight = terrain_affinity × culture_bias`, then weighted random sample with seed.

3. **ViabilityTier numbers.** The 80/40/20/10 region size thresholds and 0.15/0.25/0.40/0.60 slope fractions are guesses. **Recommend**: ship with these, tune from real measurements once Layer 1 is real.

4. **Building drop threshold in Layer 3.** Below what score is a candidate position rejected? **Recommend**: a position is acceptable if at least one terrain-weight factor is satisfied above 0.3. Tune later.

5. **Centrality scoring formula.** How does `centrality: 0.4` translate to a score against distance-from-anchor? **Recommend**: `score = 1 - |centrality - normalised_distance_from_anchor|`. A centrality-0.4 building scores best at 40% of village radius from the anchor.

6. **Default culture file contents.** Needs to be authored. **Recommend**: dirt-path roads, NATURAL curvature, IRREGULAR plaza shape, equal inclination bias for all six inclinations.

7. **Building selection sample method.** Once weighted, how to draw the actual N selected? **Recommend**: weighted reservoir sampling with the seed, ensuring required buildings (TOWN_HALL) are always drawn first.

---

## Foundational Issues to Fix in Parallel

Independent of layers but blocking real measurement:

1. **Footprint data flow.** Each PlacedBuilding must reference the ACTUAL variant footprint, not a default. The existing `StructureSizeCache: could not load ... using default radius` pathway is currently masking this. Trace the variant-selection → footprint-lookup chain and ensure layers 3 and 5 use the actual footprint.

2. **TOWN_HALL placement guarantee.** TOWN_HALL is `required: true`. Layer 2 must guarantee an anchor with enough clearance for the TOWN_HALL variant before declaring viability. If TOWN_HALL can't fit at the chosen anchor in Layer 3, the whole plan bails to UNVIABLE rather than spawning a townhall-less village.

---

## Implementation Order

1. **Layer 1** — Expand FeatureMap with aggregate queries. Validate with a debug command that prints/visualises the feature map at a position.
2. **Layer 2** — Build viability ladder, anchor selection, inclination selection. Validate with a debug command that prints SiteContext for a position.
3. **Foundational fix** — trace and fix the footprint data flow so PlacedBuilding can reference real variant footprints.
4. **Layer 3 (AGRICULTURAL only)** — Hand-author placement manifest fields for ~10 buildings. Build the placement loop. Validate by spawning villages on flat ground, walking them in survival.
5. **Layer 4** — Spine + connectors. Validate by spawning end-to-end.
6. **Layer 5** — Overlap check + per-building terrain adaptation + LayoutPlan emit. Validate end-to-end with the existing decoration pipeline.
7. **More inclinations** — Add INDUSTRIAL, RESIDENTIAL, etc. Test on varied terrain.
8. **Cleanup** — Delete the old system once V2 is producing good villages on a representative spread of terrain.

---

## Summary

The system is: terrain → site → buildings → roads → realisation. Five layers, each replaceable. Buildings own their placement via manifest-declared preferences and dependencies. Roads serve buildings. Culture and inclination provide variation; terrain provides structure.

V1 ships with default culture only, six inclinations, six building dependency relationships (SMITHY→MINE, BAKERY→MILL, etc.), and a viability ladder that admits everything from 5-building outposts to 50+-building cities.

Variety comes from terrain — no two terrains produce the same village. The 17 hand-authored shapes are gone. So is the slot/recipe machinery. So is most of the matcher.

Future work (walls, terraces, twin villages, market-as-satisfier, regional cultures) is flagged but out of scope for V1.
