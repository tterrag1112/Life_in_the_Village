# 00 — Conventions

Ground rules and vocabulary that apply to every subsystem doc in this
plan. Short and opinionated on purpose.

## Vocabulary

**Decoration** — any non-building, non-road, non-farm-plot addition to a
village's built environment. Covers street furniture, signs, ornamental
pieces, wall pieces, cemetery headstones, festival kits, etc.

**DecorationTag** — a tag advertising what kind of decoration a slot is
looking for. Parallel to `SlotTag` (building placement) but in a
separate enum. Lives in `Village.Planning.Decoration`.

**DecorationSlot** — a spatial slot emitted by the decoration framework
after building placement. Consumed by `DecorationMatcher`.

**AdjunctPlot** — a small plot attached to a specific parent building
(examples: herb garden beside the apothecary, forge yard behind the
blacksmith). Has its own content NBT or piece-kit. Registered per-plot
on `VillageSavedData` alongside `FarmPlot`.

**SubBuilding** — a named logical region *inside* a parent building,
detected at placement time by scanning for anchor blocks in the NBT.
Used for inn rooms, shop-houses, archives inside guild halls.

**Piece-kit** — a set of small NBT templates + assembly rules used to
build a larger feature (wall segments, festival stalls, park features).
Parallel to how `BridgeKit` will work in Trade Route 7b.

**Gathering point** — a named position within a town square or park
that NPCs visit during social time. Registered on the `Village` record.

**Welcome marker** — the structure placed where a trade road enters the
village's outermost-building ring. Gate variants for walled villages,
arch/sign variants for unwalled.

## File layout

All new decoration code lives under
`src/main/java/tterrag1112/life_in_the_village/Village/Decoration/`.
Sub-packages:

```
Village/Decoration/
├── Variants/           BuildingVariant, VariantRegistry, ColorPalette
│                       (also affects existing BuildingPlacer)
├── Framework/          DecorationTag, DecorationSlot, DecorationMatcher
├── Adjunct/            AdjunctPlot, AdjunctPlotPlacer, AdjunctPlotType
├── Subbuilding/        SubBuilding, SubBuildingScanner, SubBuildingRegistry
├── Pieces/             primitives shared between multiple subsystems
│                       (Hedgerow, FlowerBed, GravelPath, Pond, etc.)
├── TownSquare/         TownSquareComposer + kit resolution
├── StreetFurniture/    piece kit + road-adjacent emission
├── Signs/              guild emblems, trade signs, welcome markers
├── Industry/           forge yard, drying rack, kiln, paddock, etc.
├── Gardens/            herb garden, kitchen garden, formal garden, etc.
├── Park/               public GardenPlot, ParkStyle catalog
├── Farm/               FieldPatch, FarmTerritory, Terracing
├── Homestead/          HomesteadAdjunctPlot variants, roles hook
├── Wall/               WallPlan, WallRealizer, WallKit
├── Festival/           FestivalGround, FestivalKit, FestivalDecorator
└── Cemetery/           Cemetery, Headstone, epitaph generation
```

NBT resource paths follow the existing CultureResolver convention:

```
data/life_in_the_village/structures/{culture}/decoration/{category}/{name}.nbt
```

Examples:
```
structures/default/decoration/street_furniture/bench_1.nbt
structures/nordic/decoration/industry/forge_yard_anvil.nbt
structures/imperial/decoration/wall/tower_round_1.nbt
structures/default/decoration/festival/harvest/feast_table.nbt
```

**Building NBT paths** follow the variant-aware convention introduced
by subsystem 15:

```
data/life_in_the_village/structures/{culture}/{style}/{type}/{variant}/level_{n}.nbt
data/life_in_the_village/structures/{culture}/{style}/{type}/{variant}/manifest.json
```

Where `{style}` is `rural` or `urban` and `{variant}` is the variant
ID (matching the folder name). Existing buildings migrate to a `{type}`
variant with the same name as the type:

```
structures/default/rural/house/house/level_1.nbt           (was: house/level_1.nbt)
structures/default/urban/house/townhouse/level_1.nbt       (new variant)
structures/default/rural/blacksmith/smithy/level_1.nbt     (was: blacksmith/level_1.nbt)
```

## Codec conventions

- All persisted records (AdjunctPlot, SubBuilding, WallPlan, GardenPlot,
  Cemetery, FestivalGround) carry a UUID identity and live on
  `VillageSavedData`.
- Codecs are `RecordCodecBuilder.create` with `optionalFieldOf` defaults
  for forward compatibility.
- Enum fields serialize via `Codec.STRING.xmap(EnumType::valueOf,
  EnumType::name)` unless `StringRepresentable` is already defined.
- Position fields use `BlockPos.CODEC`. Avoid long-packed positions —
  readability matters for save inspection.
- Never store live entity references; store UUIDs.

## Naming conventions

- Records use nouns: `AdjunctPlot`, `SubBuilding`, `WallPlan`,
  `FestivalGround`.
- Placers/emitters use verbs: `AdjunctPlotPlacer`, `StreetFurnitureEmitter`,
  `WallRealizer`.
- Registries use `Registry` suffix: `AdjunctPlotRegistry`.
- Kits use `Kit` suffix: `FestivalKit`, `WallKit`, `StreetFurnitureKit`.
- Matcher passes always named `{Thing}Matcher`: `DecorationMatcher`.

## Debug commands

Every subsystem with runtime state gets a debug subcommand:

```
/liv decoration list <village>     — list decoration slots + matches
/liv adjunct list <village>        — list AdjunctPlots and their parents
/liv subbuilding list <village>    — list SubBuildings and types
/liv variant list <building>       — show variant + colors of a building
/liv variant repaint <building> <primary> [accent] [roof]  — admin repaint
/liv wall show <village>           — visualize WallPlan polyline
/liv festival show <village>       — list festival ground + active kit
/liv cemetery list <village>       — list graves and epitaphs
```

Pattern: match the existing `/liv` command structure. Output to the
source player via `sendSuccess` with `sendFailure` on errors.

## Integration boundaries

- **Zoning** — decoration code never emits `SlotTag` slots and never
  consumes them. DecorationTag is its own enum and matcher.
- **Trade Route** — decoration code never plans road centerlines. It
  may read existing centerlines to position slots adjacent to roads.
- **NPC** — decoration code never owns NPC goals. It registers
  gathering points and plot metadata; NPC goals consume them.
- **Economy** — decoration code never owns channels or sim data. It
  registers production locations (homesteads) and the economy layer
  consumes them via building/plot profiles.

## Things that must not happen

- No `setBlock` calls from planning code. Placement happens only in
  realiser passes.
- No hardcoded block palettes inside placement code. All blocks come
  from `VillageBiomeStyle` or a per-culture palette lookup.
- No duplicated codec definitions. Share records via the model package.
- No bypass of the matcher — every decoration piece goes through
  DecorationSlot → DecorationMatcher, never direct placement.

## Out of scope (deferred polish)

For reference, the following will be added in a later polish pass and
must not be added during this rework:

- Particle emitters (smoke from chimneys, steam from ovens)
- Live decorative animals (cats, dogs, pigeons, moths)
- Scripted lantern lighting (dusk/dawn toggling)
- Wear-and-tear weathering on buildings
- Laundry, firewood piles, tool props
- Seasonal block-state shifts in gardens
- Open-business indicators (door ajar, lantern lit)
- Ambient sound placement beyond what mob entities provide naturally

If one of these would "naturally" show up in a content pass, resist it.
Land the structural work first.
