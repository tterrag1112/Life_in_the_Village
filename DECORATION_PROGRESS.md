# Decoration Rework — Progress Tracker

Status values: `Not-Started`, `In-Progress`, `Implemented`, `Tested`, `Done`

`Done` means: implemented, tested in-world, no known issues, spec
matches reality. Revisions after `Done` add a new row.

## Phase 0 — Foundation

Phase 0 absorbs the zoning rework. The variant/color tasks are listed
first because they unblock everything else — read `15-building-
variants-and-colors.md` before any other Phase 0 doc.

### Phase 0a — Variants, color, zoning absorption

| ID | Task | Subsystem | Status | Notes |
|---|---|---|---|---|
| P0a-01 | Folder migration: existing NBTs → `{culture}/{style}/{type}/{variant}/` | 15-building-variants-and-colors | Implemented | One-shot migration; resolver + StructureSizeCache rewrite legacy `{type}/level_{n}` paths via `CultureResolver.toVariantAwarePath`. Full fallback chain still pending P0a-04. |
| P0a-02 | `manifest.json` schema + loader | 15-building-variants-and-colors | Implemented | `VariantManifest` record + `VariantManifestLoader` reload listener under `Village/Decoration/Variants`; minimal manifests written for each migrated building. |
| P0a-03 | `BuildingVariant` record + `VariantRegistry` | 15-building-variants-and-colors | Implemented | `BuildingVariant` adds `culture`/`style`/`type` fields and uses a nested `Footprint(int x, int z)` record (see doc 15 revision notes); `VariantRegistry.INSTANCE` rebuilds from `VariantManifestLoader` each reload. `eligibleFor` filters by folder style + tier window only — scoring lands in P0a-06. |
| P0a-04 | `CultureResolver` extended fallback chain | 15-building-variants-and-colors | Implemented | Seven-step chain implemented in `resolveInternal`; legacy `resolve(culture, type, level, world)` and `resolveFromPath` retained as thin wrappers. One-time warning per `(type, variantId)` on default-variant fallback; hard-fail logs all six attempted paths on miss. |
| P0a-05 | `StructureSizeCache` keying by `(culture, style, type, variant)` | 15-building-variants-and-colors | Implemented | Cache rekeyed to a `CacheKey(culture, style, type, variantId, level, rotation)` record. Manifest footprint declarations override the NBT measurement when present. Legacy `get(structurePath, rotation)` kept as a temporary bridge for planning callers (defaults: `culture=default`, `style=RURAL`, `variantId=type-default`). |
| P0a-06 | Variant scoring algorithm in matcher | 15-building-variants-and-colors | Not-Started | Depends P0a-03 |
| P0a-07 | Style auto-derivation from layout + tier | 15-building-variants-and-colors | Not-Started | Depends P0a-04 |
| P0a-08 | `Building` record fields: variantId, primaryColor, accentColor, roofColor | 15-building-variants-and-colors | Not-Started | |
| P0a-09 | `ColorPalette` + `ColorPaletteRegistry` with named presets | 15-building-variants-and-colors | Not-Started | |
| P0a-10 | Tint pass: white-block → DyeColor swap on placement | 15-building-variants-and-colors | Not-Started | Depends P0a-08, P0a-09 |
| P0a-11 | Local-neighbor color exclusion | 15-building-variants-and-colors | Not-Started | Depends P0a-10 |
| P0a-12 | Forced-color overrides (TEMPLE, TOWN_HALL, guild halls) | 15-building-variants-and-colors | Not-Started | Depends P0a-10 |
| P0a-13 | `BuilderRepaintGoal` + repaint UI | 15-building-variants-and-colors | Not-Started | Depends P0a-08, P0a-10 |
| P0a-14 | VillageTypeData fields: style, colorPalette | 15-building-variants-and-colors | Not-Started | |
| P0a-15 | Default culture RURAL variant pack (cottage, longhouse, etc.) | 15-building-variants-and-colors | Not-Started | Authoring task |
| P0a-16 | Default culture URBAN variant pack (townhouse, tenement, etc.) | 15-building-variants-and-colors | Not-Started | Authoring task |
| P0a-17 | Zoning: layouts 2–16 conversion to slot/matcher pattern | 15-building-variants-and-colors | Not-Started | Absorbed from prior zoning rework |
| P0a-18 | Zoning: `ZoneRegistry` deletion | 15-building-variants-and-colors | Not-Started | Depends P0a-17 |
| P0a-19 | Diversity-bonus diminishing returns in matcher | 15-building-variants-and-colors | Not-Started | Depends P0a-06 |
| P0a-20 | Codec migration for Building record | 15-building-variants-and-colors | Not-Started | Depends P0a-08 |

### Phase 0b — Decoration framework

| ID | Task | Subsystem | Status | Notes |
|---|---|---|---|---|
| P0b-01 | `DecorationTag` enum + skeleton | 01-decoration-framework | Not-Started | |
| P0b-02 | `DecorationSlot` record + codec | 01-decoration-framework | Not-Started | Depends P0b-01 |
| P0b-03 | `DecorationMatcher` second-pass runner | 01-decoration-framework | Not-Started | Depends P0b-02 |
| P0b-04 | Uniform decoration slot emitter | 01-decoration-framework | Not-Started | Depends P0b-02 |
| P0b-05 | `DecorationProfile` registry | 01-decoration-framework | Not-Started | Depends P0b-01 |
| P0b-06 | `/liv decoration` debug command | 01-decoration-framework | Not-Started | Depends P0b-03 |

### Phase 0c — AdjunctPlot framework

| ID | Task | Subsystem | Status | Notes |
|---|---|---|---|---|
| P0c-01 | `AdjunctPlot` record + codec | 02-adjunct-plot-framework | Not-Started | |
| P0c-02 | `AdjunctPlotPlacer` | 02-adjunct-plot-framework | Not-Started | Depends P0c-01 |
| P0c-03 | AdjunctPlot registry on VillageSavedData | 02-adjunct-plot-framework | Not-Started | Depends P0c-01 |
| P0c-04 | Building → AdjunctPlot spec table | 02-adjunct-plot-framework | Not-Started | Depends P0c-01 |

### Phase 0d — Subbuildings

| ID | Task | Subsystem | Status | Notes |
|---|---|---|---|---|
| P0d-01 | `SubBuilding` record + codec | 03-subbuildings | Not-Started | |
| P0d-02 | `SubBuildingScanner` anchor-block sweep | 03-subbuildings | Not-Started | Depends P0d-01 |
| P0d-03 | SubBuilding registry on VillageSavedData | 03-subbuildings | Not-Started | Depends P0d-01 |
| P0d-04 | Migrate MarketStallPlacer onto scanner | 03-subbuildings | Not-Started | Depends P0d-02 |
| P0d-05 | Subbuilding door-target pathing helper | 03-subbuildings | Not-Started | Depends P0d-01 |

## Phase 1 — Public spaces

| ID | Task | Subsystem | Status | Notes |
|---|---|---|---|---|
| P1-01 | `TownSquareComposer` replaces TownSquarePlacer | 04-town-square-rework | Not-Started | Depends P0b-* |
| P1-02 | Sub-slot emission (fountain, monument, benches, etc.) | 04-town-square-rework | Not-Started | Depends P1-01 |
| P1-03 | Per-tier square kit selection | 04-town-square-rework | Not-Started | Depends P1-01 |
| P1-04 | Gathering-point registration for NPC hobbies | 04-town-square-rework | Not-Started | Depends P1-01 |
| P1-05 | Existing plaza geometry migration | 04-town-square-rework | Not-Started | Depends P1-01 |
| P1-06 | Street furniture kit per culture | 05-street-furniture | Not-Started | Depends P0b-* |
| P1-07 | Road-side slot density tuning | 05-street-furniture | Not-Started | Depends P0b-04 |
| P1-08 | Building-gap alcove detector | 05-street-furniture | Not-Started | Depends P0b-04 |
| P1-09 | Guild emblem NBTs per guild type | 06-signs-and-markers | Not-Started | |
| P1-10 | Trade sign NBTs per profession | 06-signs-and-markers | Not-Started | |
| P1-11 | Welcome marker at village entrances | 06-signs-and-markers | Not-Started | |
| P1-12 | Boundary stone placement along perimeter | 06-signs-and-markers | Not-Started | |
| P1-13 | Noticeboard content integration | 06-signs-and-markers | Not-Started | |

## Phase 2 — Working yards

| ID | Task | Subsystem | Status | Notes |
|---|---|---|---|---|
| P2-01 | Forge yard AdjunctPlot (blacksmith) | 07-industry-adjuncts | Not-Started | Depends P0c-* |
| P2-02 | Drying rack yard (weaver, fishery) | 07-industry-adjuncts | Not-Started | Depends P0c-* |
| P2-03 | Kiln yard (stonemason, candlemaker) | 07-industry-adjuncts | Not-Started | Depends P0c-* |
| P2-04 | Paddock (stable) | 07-industry-adjuncts | Not-Started | Depends P0c-* |
| P2-05 | Oven shed (bakery) | 07-industry-adjuncts | Not-Started | Depends P0c-* |
| P2-06 | Log yard (carpentry, woodcutter) | 07-industry-adjuncts | Not-Started | Depends P0c-* |
| P2-07 | Herb garden (apothecary) | 08-herb-and-cottage-gardens | Not-Started | Depends P0c-* |
| P2-08 | Kitchen garden (inn) | 08-herb-and-cottage-gardens | Not-Started | Depends P0c-* |
| P2-09 | Meditation garden (temple) | 08-herb-and-cottage-gardens | Not-Started | Depends P0c-* |
| P2-10 | Formal garden (noble manor) | 08-herb-and-cottage-gardens | Not-Started | Depends P0c-* |
| P2-11 | Culture variant pass for all gardens | 08-herb-and-cottage-gardens | Not-Started | Depends P2-07..P2-10 |

## Phase 3 — Landscape

| ID | Task | Subsystem | Status | Notes |
|---|---|---|---|---|
| P3-01 | `GardenPlot` record + VillageSavedData registry | 09-parks-and-gardens | Not-Started | |
| P3-02 | Public park `GardenStyle` catalog | 09-parks-and-gardens | Not-Started | Depends P3-01 |
| P3-03 | Park placement via GARDEN_PLOT slot | 09-parks-and-gardens | Not-Started | Depends P3-01 |
| P3-04 | Park piece primitives (FlowerBed, Hedgerow, Pond, etc.) | 09-parks-and-gardens | Not-Started | |
| P3-05 | `FieldPatch` primitive (terrain-following, no levelPad) | 10-farm-plot-rework | Not-Started | |
| P3-06 | `Hedgerow` primitive + natural-feature snapping | 10-farm-plot-rework | Not-Started | |
| P3-07 | `FarmTerritory` aggregate per farmhouse | 10-farm-plot-rework | Not-Started | Depends P3-05, P3-06 |
| P3-08 | Footpath tree rooted at farmhouse | 10-farm-plot-rework | Not-Started | Depends P3-07 |
| P3-09 | Terrace support for steep patches | 10-farm-plot-rework | Not-Started | Depends P3-05 |
| P3-10 | Retire old `FarmPlotPlacer` | 10-farm-plot-rework | Not-Started | Depends P3-05..P3-09 |
| P3-11 | Homestead `FarmPlot.PlotSubtype` additions | 11-homesteading | Not-Started | Requires NPC Phase 3 |
| P3-12 | House → HomesteadAdjunctPlot spec | 11-homesteading | Not-Started | Depends P0c-* |
| P3-13 | Homesteader FamilyRole + goal | 11-homesteading | Not-Started | Requires NPC Phase 3 |
| P3-14 | Homemaker FamilyRole + role logic | 11-homesteading | Not-Started | Requires NPC Phase 3 |
| P3-15 | Homestead DirectBusinessChannel wiring | 11-homesteading | Not-Started | Requires NPC Phase 3 |
| P3-16 | ResourceCategory contribution per homestead type | 11-homesteading | Not-Started | Requires NPC Phase 4 |

## Phase 4 — Defenses and history

| ID | Task | Subsystem | Status | Notes |
|---|---|---|---|---|
| P4-01 | `WallPlan` computed at recipe compose | 12-village-walls | Not-Started | Requires Phase 0a complete |
| P4-02 | Projected tower/gate slot emission | 12-village-walls | Not-Started | Depends P4-01 |
| P4-03 | `WallRealizer` polyline-to-piece assembly | 12-village-walls | Not-Started | Depends P4-01 |
| P4-04 | Palisade wall kit (wooden, hamlet/village) | 12-village-walls | Not-Started | Depends P4-03 |
| P4-05 | Stone curtain wall kit (town) | 12-village-walls | Not-Started | Depends P4-03 |
| P4-06 | Culture-specific wall variants | 12-village-walls | Not-Started | Depends P4-04, P4-05 |
| P4-07 | `FestivalGround` plot + registry | 13-festival-grounds | Not-Started | Requires NPC Phase 5 |
| P4-08 | Festival ground selection by layout/size | 13-festival-grounds | Not-Started | Depends P4-07 |
| P4-09 | Festival kits per event type | 13-festival-grounds | Not-Started | Depends P4-07, NPC Phase 5 |
| P4-10 | `FestivalDecorator` start/end hooks | 13-festival-grounds | Not-Started | Depends P4-09 |
| P4-11 | Morning-after residue pass | 13-festival-grounds | Not-Started | Depends P4-10 |
| P4-12 | `Cemetery` plot + registry | 14-cemeteries | Not-Started | Requires NPC Phase 2, Phase 4 |
| P4-13 | Headstone NBTs per culture | 14-cemeteries | Not-Started | Depends P4-12 |
| P4-14 | Death event → grave placement hook | 14-cemeteries | Not-Started | Depends P4-12, NPC Phase 2 |
| P4-15 | Grave epitaphs from village history | 14-cemeteries | Not-Started | Depends P4-14, NPC Phase 4 |

## Pre-rework dependencies (not part of this tracker)

These must be `Done` before later phases of this rework start.
**Phase 0 of this rework runs first**, before all of these:

- Kingdom planning Slice C — regional viability scoring + deep-inspection
- Trade Route rework — Phases 7a, 7b, 7c
- NPC rework — Phases 0, 1, 2, 3, 4, 5

(The zoning rework was previously listed here; it is now absorbed into
Phase 0a of this rework.)

## Revision notes

- Initial tracker had `Phase 0` flat; expanded into 0a / 0b / 0c / 0d
  to make the variant/color tasks visible at the top.
- Zoning rework tasks (P0a-17, P0a-18) absorbed from previously-
  separate zoning rework.
- Sequencing note added at bottom — Phase 0 runs first; Phases 1–4
  wait for the other reworks.
