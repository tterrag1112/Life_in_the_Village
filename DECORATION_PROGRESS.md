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
| P0a-02 | `manifest.json` schema + loader | 15-building-variants-and-colors | Implemented | `VariantManifest` record + `VariantManifestLoader` reload listener under `Village/Decoration/Variants`; minimal manifests written for each migrated building. Schema correction: `footprint` field removed from manifest in a follow-up — loader silently ignores any legacy `footprint` block in older manifests. |
| P0a-03 | `BuildingVariant` record + `VariantRegistry` | 15-building-variants-and-colors | Implemented | `BuildingVariant` adds `culture`/`style`/`type` fields; `VariantRegistry.INSTANCE` rebuilds from `VariantManifestLoader` each reload. `eligibleFor` filters by folder style + tier window only — scoring lands in P0a-06. Schema correction: nested `Footprint` record removed in a follow-up; `StructureSizeCache` is the single source of truth for variant geometry. |
| P0a-04 | `CultureResolver` extended fallback chain | 15-building-variants-and-colors | Implemented | Seven-step chain implemented in `resolveInternal`; legacy `resolve(culture, type, level, world)` and `resolveFromPath` retained as thin wrappers. One-time warning per `(type, variantId)` on default-variant fallback; hard-fail logs all six attempted paths on miss. |
| P0a-05 | `StructureSizeCache` keying by `(culture, style, type, variant)` | 15-building-variants-and-colors | Implemented | Cache rekeyed to a `CacheKey(culture, style, type, variantId, level, rotation)` record. Always measures from the NBT (the manifest-override branch was removed in the post-pilot schema correction; doc 15 §"Footprint resolution"). Legacy `get(structurePath, rotation)` kept as a temporary bridge for planning callers (defaults: `culture=default`, `style=RURAL`, `variantId=type-default`). |
| P0a-06 | Variant scoring algorithm in matcher | 15-building-variants-and-colors | Implemented | `VariantSelector` (per-matcher-run, holds the placement counter) stamps `variantId` + `style` onto the `LayoutSlot`; `VillageSpawner` reads them to drive the seven-step resolver. Single-candidate fast path skips RNG to preserve placement determinism for the current pack. **Revision (post-pilot diagnostic):** the call site moved from `PlacementMatcher.commitBest` into `PlanContext.tryCommitBuilding` so recipe-direct placements (`claimByZone` → `LayoutPrimitive.tryCommitWithRetries`) get variant scoring too. Without this every zoned building type (HOUSE, etc.) silently inherited the type-default variant. |
| P0a-07 | Style auto-derivation from layout + tier | 15-building-variants-and-colors | Implemented | `VillageTypeData.style` field added (default `"auto"`); `StyleAutoDeriver` returns `Fixed` / `UrbanLeaning` per doc 15 §"Style determination". Per-slot pick goes through `pickStyleForType` which skips the RNG roll when only one style has authored content for the type. |
| P0a-08 | `Building` record fields: variantId, primaryColor, accentColor, roofColor | 15-building-variants-and-colors | Implemented | All four added; codec uses `optionalFieldOf` for forward-compat. `variantId` defaults to type-default in both constructors. Save-data round-trip handled by the same `optionalFieldOf` defaults — see P0a-20 row. |
| P0a-09 | `ColorPalette` + `ColorPaletteRegistry` with named presets | 15-building-variants-and-colors | Implemented | Six presets registered: `NONE`, `BERGEN_FJORD`, `MEDITERRANEAN`, `MUTED_EARTH`, `MEDIEVAL_PRIMARY`, `NORDIC_SUBDUED`. Sampler supports excludedColors set. Parser accepts both string id and inline JSON object. `mustard`/`light_brown` colour-name divergences flagged in doc 15 revision notes. |
| P0a-10 | Tint pass: white-block → DyeColor swap on placement | 15-building-variants-and-colors | Implemented | `TintBlockTable` validates all 16-DyeColor coverage at class init; `TintPass` runs in `BuildingPlacer.placeAndRegister` between NBT stamp and biome substitution. Village→palette resolution hardcoded to `MUTED_EARTH` for default culture (temporary; replaced by P0a-14). Forced overrides left as a TODO marker in `VillagePaletteResolver` — covered by P0a-12. |
| P0a-11 | Local-neighbor color exclusion | 15-building-variants-and-colors | Implemented | `NeighborColorIndex` accumulates per-village placed primary colours. `ColorPalette.sample(slot, rng, hard, soft)` overload applies the doc 15 ×0.2 multiplier; the resolver retries without the soft set when the first sample collapses to null. Linear scan over the running list — fine for current village sizes (≤30 buildings). |
| P0a-12 | Forced-color overrides (TEMPLE, TOWN_HALL, guild halls) | 15-building-variants-and-colors | Implemented | TEMPLE → WHITE primary, others null (works even on NONE palette). TOWN_HALL → uses `VillageTypeData.signatureColor` when set; otherwise samples normally. Guild-hall override is non-functional today because `GuildData` carries no colour fields — logged once per type and falls through. |
| P0a-13 | `BuilderRepaintGoal` + repaint UI | 15-building-variants-and-colors | Implemented | Three components landed: (a) `RepaintCost` + `RepaintAuthorization` helpers; (b) `RepaintScreen` GUI opened by plain-right-click on a builder NPC, plus `OpenRepaintScreenPacket` (S→C) and `RepaintActionPacket` (C→S); (c) `BuilderRepaintGoal` with persisted `RepaintJob` state on the NPC NBT, modelled on `BuilderMaintenanceGoal`. Visit cadence: 1 visit per in-game day, paints `ceil(area/totalVisits)` blocks per visit. `RuntimeTintPass` reuses the placement-time tint logic but recognises any-colour tintable variants so already-tinted buildings can be repainted. Guild-color overrides still gated on a future `GuildData` extension. |
| P0a-14 | VillageTypeData fields: style, colorPalette | 15-building-variants-and-colors | Implemented | `style` was added in P0a-07. P0a-14 added `colorPalette` (named or inline JSON, parsed via `ColorPaletteRegistry.parse`) and `signatureColor` (DyeColor name string). `CultureDefaultPalettes` provides the culture → default palette fallback (default culture → MUTED_EARTH). `VillagePaletteResolver.paletteFor` uses the three-step chain. Hardcoded P0a-10 bridge is removed. |
| P0a-15 | Default culture RURAL variant pack (cottage, longhouse, etc.) | 15-building-variants-and-colors | Partially-Implemented | HOUSE pilot manifests authored: `cottage`, `house`, `large_house`. NBT files for all three are **currently missing** from the repo (the `Current State` push removed the migrated `house/level_1.nbt` and never added the new ones); placement will hard-fail until NBTs land. Manifests, weights, slot tags, and tier gates match doc 16 §3 verbatim. Other RURAL variants pending. |
| P0a-16 | Default culture URBAN variant pack (townhouse, tenement, etc.) | 15-building-variants-and-colors | Not-Started | Authoring task |
| P0a-17 | Zoning: layouts 2–16 conversion to slot/matcher pattern | 15-building-variants-and-colors | Implemented | All 18 shape recipes route through `pctx.claimByZone(...)` (directly or via `RecipeHelpers.claimAndBucketProdResidential`); none use legacy direct-placement. Slot/matcher conversion was completed earlier in the rework — this row is being marked done after the explicit audit in the Phase 0a closeout pass. |
| P0a-18 | Zoning: `ZoneRegistry` deletion | 15-building-variants-and-colors | Blocked | ZoneRegistry has active production usage outside the recipe pipeline: `BuildSiteFinder` (runtime building expansion), `PlanContext.claimByZone` / terrain / civic-ring policies, and `LayoutPrimitive` civic-ring sizing. The slot/matcher absorption (P0a-17) didn't subsume these — ZoneRegistry now serves as the type → zone tag store rather than the placement engine. Deletion needs follow-up work to migrate those four call sites onto a slimmer tag accessor (or accept a renamed `BuildingZoneRegistry`). Tracked for a later closeout. |
| P0a-19 | Diversity-bonus diminishing returns in matcher | 15-building-variants-and-colors | Implemented | Score multiplier `× pow(0.7, alreadyPlacedCount)` lives in `VariantSelector.score`; the placement counter is keyed by full `VariantKey` so same-id variants from different cultures don't share a counter. Per-village isolation is automatic — each `PlacementMatcher` instance gets a fresh `VariantSelector`. |
| P0a-20 | Codec migration for Building record | 15-building-variants-and-colors | Implemented | The codec extension landed alongside P0a-08 — `variantId` is `optionalFieldOf("variantId")` defaulting in `fromCodec` to `BuildingVariant.defaultVariantId(type)`, and the three colour fields are `optionalFieldOf` with `null` defaults. Existing world saves load with sensible defaults; new saves persist all four. No save migration script needed because the optional-field pattern handles backward compatibility automatically. |

### Phase 0b — Decoration framework

| ID | Task | Subsystem | Status | Notes |
|---|---|---|---|---|
| P0b-01 | `DecorationTag` enum + skeleton | 01-decoration-framework | Implemented | 13 values per doc 01 §"DecorationTag enum"; `StringRepresentable` codec (`DecorationTag.CODEC`) for stable on-disk encoding by name. Lives in `Village/Decoration/Framework/`. |
| P0b-02 | `DecorationSlot` record + codec | 01-decoration-framework | Implemented | Record with `RecordCodecBuilder` codec. Tags stored as `EnumSet`; `parentId` optional; `contextRoad` defaults to empty. `qualityScore` clamped to 0..100. Reusable `TAG_SET_CODEC` exported for `DecorationProfile`. Ephemeral — no `VillageSavedData` persistence path. |
| P0b-03 | `DecorationMatcher` second-pass runner | 01-decoration-framework | Implemented | Single-pass greedy commit ordered by score (descending), stable tie-break on slotId then pieceId. Bonuses: +5 preferred-tag intersect, +3 anchor-rule alignment. Per-profile clustering limit 8 blocks. `DecorationPlacement` record + codec persisted via `VillageSavedData.VillageDecorationData`. `DecorationPass` orchestrates emit → match → NBT stamp → persist; wired into `VillageSpawner` after the legacy `VillageDecorator` and before `TradeRouteManager`. Empty-registry path is the expected state at the close of Phase 0b — matcher logs INFO and exits with zero placements. |
| P0b-04 | Uniform decoration slot emitter | 01-decoration-framework | Implemented | All six sub-algorithms per doc 01 §"Uniform slot emission": road-side, building-gap, building-corner-accent, facade-ornament, village-boundary, trade-road endpoints. Deterministic — slot UUIDs derived from `villageId + algorithm + key + pos` via `UUID.nameUUIDFromBytes` (no randomness). Special-context tags (`GUILD_EMBLEM`, `TRADE_SIGN`, `HEADSTONE`) deferred to their owning subsystems. |
| P0b-05 | `DecorationProfile` registry | 01-decoration-framework | Implemented | `DecorationProfile` record + `AnchorRule` enum + `DecorationProfileRegistry.INSTANCE` (singleton; `register` / `resolve` / `eligibleFor` / `clear` / `all`). Culture fallback chain: same-culture profiles take precedence, otherwise fall through to `default` culture, otherwise empty. Slot with zero tags → empty result (no signal). Registry empty until later subsystems (P1-06 onward) populate it. |
| P0b-06 | `/liv decoration` debug command | 01-decoration-framework | Implemented | Four subcommands: `list <village>`, `slots <village>`, `profiles`, `replay <village> confirm`. Auto-registers via `@EventBusSubscriber` + `RegisterCommandsEvent`. `replay` requires the literal `confirm` token to guard against accidental destructive runs. Closes Phase 0b. |

### Phase 0c — AdjunctPlot framework

| ID | Task | Subsystem | Status | Notes |
|---|---|---|---|---|
| P0c-01 | `AdjunctPlot` record + codec | 02-adjunct-plot-framework | Implemented | Record + `RecordCodecBuilder` codec under `Village/Decoration/Adjunct/`. `AdjunctPlotType` enum (14 values across industry / gardens / homestead categories) carries placer metadata: default footprint, `PlacementStrategy`, slope tolerance, `FaceProbeOrder`. Codec stability via `StringRepresentable`. |
| P0c-02 | `AdjunctPlotPlacer` | 02-adjunct-plot-framework | Implemented | Single static `tryPlace(building, type, level, data)` entry point. Probes faces in `FaceProbeOrder` sequence (back / left / right; never front). Per-face checks: parent overlap, parent-front-extent guard, sibling building overlap, existing plot overlap, slope tolerance. Origin Y = median of sampled heightmap. Determinism: pure function of inputs. Silent drop with INFO log on all-faces-fail. |
| P0c-03 | AdjunctPlot registry on VillageSavedData | 02-adjunct-plot-framework | Implemented | `VillageAdjunctData` sub-record + codec wired into the main `VillageSavedData.CODEC` chain (11th sub-record, `optionalFieldOf` default empty). Authoritative `Map<UUID, AdjunctPlot>` + denormalised per-building map rebuilt from it on load. Accessors: add, get, getForBuilding, getAll, remove, removeForBuilding. `removeBuilding(Building)` cleanup hook removes orphans automatically. |
| P0c-04 | Building → AdjunctPlot spec table | 02-adjunct-plot-framework | Implemented | `AdjunctPlotRegistry` static spec table with the doc 02 §"Building → AdjunctPlot spec" skeleton: APOTHECARY→HERB_GARDEN, BLACKSMITH→FORGE_YARD, INN→KITCHEN_GARDEN, TEMPLE→MEDITATION_GARDEN, STABLE→PADDOCK, WEAVER/FISHERY→DRYING_RACK_YARD, CARPENTRY/WOODCUTTER/MILLER→LOG_YARD, STONEMASON/CANDLEMAKER→KILN_YARD, BAKERY→OVEN_SHED, NOBLE_MANOR→FORMAL_GARDEN+PADDOCK. Hard cap of 3 plots per building (`MAX_PLOTS_PER_BUILDING`). HOUSE → HOMESTEAD_* registrations deferred to subsystem 11; FARMER intentionally absent (FarmPlot system handles it). `AdjunctPlotRealiser` orchestrates per-village placement, wired into `VillageSpawner` between `VillageDecorator` and `DecorationPass`. |

### Phase 0d — Subbuildings

| ID | Task | Subsystem | Status | Notes |
|---|---|---|---|---|
| P0d-01 | `SubBuilding` record + codec | 03-subbuildings | Implemented | `SubBuilding` record at `Village.Decoration.Subbuilding.SubBuilding` with fields `(subBuildingId, parentBuildingId, type, origin, min, max, doorTarget, doorFacing, inhabitants, createdTick)`. `SubBuildingType` is a `StringRepresentable` enum (8 values). `RecordCodecBuilder` codec, `inhabitants` and `createdTick` are `optionalFieldOf` defaults so the record is forward-compatible. |
| P0d-02 | `SubBuildingScanner` anchor-block sweep | 03-subbuildings | Implemented | Block-entity-based sweep, NOT chiseled-block palette (see doc 03 revision notes). `SubBuildingAnchorBlock` + `SubBuildingAnchorBlockEntity` registered in `ModBlocks` / `ModBlockEntities`. Authors place the anchor block in their structure NBT and edit the BE's `subBuildingType` via NBT tooling (workflow documented in `00-conventions.md`). Scanner walks parent chunks, filters BEs by type, sorts by `(Y, Z, X)` for determinism, resolves bounds (explicit override else flood-fill capped at 256 cells), resolves door (explicit override else nearest `DoorBlock`, fall-through to anchor + parent-front), replaces anchor with air. Hooked into `BuildingPlacer.placeAndRegister` between `placeInWorld` and `TintPass.apply`. |
| P0d-03 | SubBuilding registry on VillageSavedData | 03-subbuildings | Implemented | `VillageSubBuildingData` sub-record (12th in the main CODEC chain, `optionalFieldOf` empty default). Authoritative `Map<UUID, SubBuilding>` + denormalised `Map<UUID, List<UUID>>` by parent. Accessors: `addSubBuilding`, `getSubBuilding`, `getSubBuildingsForBuilding`, `getSubBuildingsOfType`, `getAllSubBuildings`, `removeSubBuilding`, `removeSubBuildingsForBuilding`. `removeBuilding(Building)` cleanup hook calls `removeSubBuildingsForBuilding`. |
| P0d-04 | Migrate MarketStallPlacer onto scanner | 03-subbuildings | Deferred | More invasive than expected; split into its own follow-on prompt. The existing `MarketStallPlacer` model is fundamentally lazy / runtime-claim (chiseled-stone-brick anchors are consumed by `claimSlot()` lazily, not scanned at building placement) and `MarketStall` is a mutable class rather than a record. Migration requires either re-authoring binary `market/stall_1.nbt` to use the new anchor block + maintaining a parallel "available pool" for runtime claim, or keeping chiseled-block scanning as a legacy path while new structures use the BE anchor. Framework lands first; stall migration ships separately. |
| P0d-05 | Subbuilding door-target pathing helper | 03-subbuildings | Implemented | `SubBuildingPathing` static helpers: `pathTarget(sub)` returns the door block; `approachTarget(sub)` returns one block outward along `doorFacing` so NPCs stand outside before opening; `hasRealDoor(sub)` distinguishes scanned-door from anchor-fallback subbuildings; `contains(sub, pos)` for "already inside" detection. Goals depend on this rather than touching `SubBuilding` fields directly. |

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
