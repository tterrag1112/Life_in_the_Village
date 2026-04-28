# 15 — Building Variants and Colors

## Purpose

Replace the one-NBT-per-building-type model with a variant system that
lets every building type ship multiple distinct designs, distinguishes
RURAL and URBAN style profiles within the same culture, supports
weathered/aged variants tied to village history, and applies a per-
building color so settlements read like Bergen rather than a model
village.

Three intertwined features in one doc because they share infrastructure
(BuildingProfile extension, manifest.json, CultureResolver path
resolution).

## Design

### Folder restructure

Current path:
```
structures/{culture}/{type}/level_{n}.nbt
```

New path:
```
structures/{culture}/{style}/{type}/{variant}/level_{n}.nbt
structures/{culture}/{style}/{type}/{variant}/manifest.json
```

Where `{style}` is `rural` or `urban` (more later as needed) and
`{variant}` is the variant id (`cottage`, `townhouse`, `longhouse`,
`tenement`, `big_house`, etc.).

Backward compatibility is **not** preserved. Migration pass moves all
existing files at rework start: every `{culture}/{type}/level_n.nbt`
becomes `{culture}/rural/{type}/{type}/level_n.nbt` (the variant
defaults to the type name, so a HOUSE becomes `house/house/level_n`).

This is the single migration; afterwards the resolver supports only
the new path layout.

### Manifest format

Each variant directory contains `manifest.json`:

```json
{
  "id": "big_house",
  "displayName": "Big House",
  "minTier": "VILLAGE",
  "maxTier": "CITY",
  "weight": 1.0,
  "preferredTags": ["RESIDENTIAL_CORE", "CIVIC_ADJACENT"],
  "stylePreference": "URBAN",
  "agePreference": "ANY",
  "tags": ["multi_family", "townhouse"],
  "maxPerVillage": 3,
  "colorSlots": ["PRIMARY", "ACCENT", "ROOF"]
}
```

**Field semantics:**
- `id` — variant identifier, matches folder name
- `minTier`/`maxTier` — village tier gating
- `weight` — base placement weight (1.0 default)
- `preferredTags` — SlotTag bonus list; matcher gives this variant
  priority for slots with these tags
- `stylePreference` — RURAL, URBAN, or ANY; soft bias on top of folder
  position
- `agePreference` — FRESH, WEATHERED, ANCIENT, ANY (see §"Age
  variants" below)
- `tags` — free-form archetype tags (e.g. `fisherman`, `farmer`,
  `multi_family`); village types match against these
- `maxPerVillage` — hard cap on placements within a single village
- `colorSlots` — which color slots this variant uses; missing slots
  skipped during the swap pass

Missing manifest defaults to:
```json
{ "id": "{folder_name}", "weight": 1.0, "stylePreference": "ANY",
  "agePreference": "ANY", "tags": [], "colorSlots": ["PRIMARY"] }
```

### Footprint resolution

The variant's footprint is measured by `StructureSizeCache` from
the NBT and is **not** declared in the manifest. The cache is the
single source of truth for variant geometry.

Authors target a footprint while building the NBT (see doc 16
authoring guidance for spec'd footprints per variant), but the
manifest never restates it. Loaders ignore any legacy `footprint`
field that appears in older manifests; callers that need the
footprint at runtime go through `StructureSizeCache.get(...)`.

### CultureResolver fallback chain

For lookup `(culture, style, type, variant, level)`:

1. `{culture}/{style}/{type}/{variant}/level_{n}.nbt`
2. `{culture}/{type}/{variant}/level_{n}.nbt`         (style-agnostic)
3. `default/{style}/{type}/{variant}/level_{n}.nbt`
4. `default/{type}/{variant}/level_{n}.nbt`
5. `default/{style}/{type}/{type}/level_{n}.nbt`      (default variant)
6. `default/{type}/{type}/level_{n}.nbt`
7. fail with logged warning

Step 5–6 ensure that even if a culture omits a variant entirely, the
matcher can fall through to a default-typed building. No silent fail.

### Style determination

Each VillageTypeData adds:
```json
"style": "rural"   // explicit
```
or
```json
"style": "auto"    // derived from layout + tier
```

Auto-derivation rules:
- TOWN or CITY tier → URBAN
- HAMLET or VILLAGE on RADIAL/PLAZA/CROSSROADS layout → URBAN-leaning
  (40% urban variants permitted, 60% rural)
- HAMLET or VILLAGE on CLUSTERED/SPRAWL/RIVERINE/GROVE → RURAL
- ENCLAVE → URBAN regardless of tier
- TERRACED, HILLTOP → RURAL
- COASTAL → tag-driven, prefers `coastal` and `fisherman` tags

Auto fallback never picks a style with zero authored content; it
defaults to the available style when only one exists.

### Variant selection algorithm

Replaces the current single-NBT pickup in BuildingPlacer:

1. Resolve eligible variants for `(buildingType, villageStyle,
   villageTier, villageAgeYears, slotTags)`.
2. Filter by tier gates and `maxPerVillage` already-placed counts.
3. Score each candidate:
   - `weight` from manifest
   - `+0.3` per matching `preferredTag` ∩ `slot.tags`
   - `+0.2` per matching variant tag ∩ village preferred tags
   - `+0.4` if `stylePreference` matches the village style exactly
   - `+0.2` if `agePreference` matches the village's age category
   - Diminishing returns: `× pow(0.7, alreadyPlacedCount)` to encourage
     diversity within the same village
4. Roll weighted-random across surviving candidates.
5. If no candidates pass, fall back to the type's default variant
   (folder named identically to the type).

The matcher already handles slot/profile compatibility; variant
selection layers on top.

## Color system

### Tintable blocks (opt-out convention)

These blocks are always replaced with the building's color during the
post-stamp tint pass:

```
PRIMARY slot:
  white_terracotta, white_wool

ACCENT slot:
  white_concrete, white_concrete_powder, white_glazed_terracotta

ROOF slot:
  white_glazed_terracotta (when on a roof slope), white_carpet,
  white_candle (decorative roof candle markers)
```

`Light_gray_*` is the escape hatch for authors who want a literal white
that won't be tinted. The convention applies project-wide; document
in the authoring guide.

The tint pass runs immediately after NBT stamp, before
VillageBiomeStyle's material substitution. Order matters: tint the
white-marker blocks → biome-substitute the rest.

### Color assignment

Each `Building` record gains:
```java
DyeColor primaryColor;       // nullable; null = no tint
DyeColor accentColor;        // nullable
DyeColor roofColor;          // nullable
```

Assignment at placement time:

1. Look up village's `colorPalette` (named preset or explicit list).
2. If null, building gets all-null colors → no tint pass runs (current
   behavior).
3. If palette is set:
   - Roll a primary color from the palette, weighted by frequency
     declared in the preset.
   - Apply local-neighbor exclusion: scan buildings within 12 blocks;
     if any share a palette color, downweight that color × 0.2.
   - Roll accent color: same palette but excluding the chosen primary.
   - Roll roof color: same palette but with reduced weight on bright
     colors (roofs typically muted).
4. Forced overrides:
   - `TEMPLE` → primaryColor = WHITE, others null
   - `TOWN_HALL` → primaryColor = village signature color (declared
     in VillageTypeData)
   - Guild halls → primaryColor = guild color (from GuildData)

### Color palettes

Named presets registered in `ColorPaletteRegistry`:

```java
BERGEN_FJORD       red, yellow, white, mustard, light_blue
                   weights: high red, medium yellow, low blue
MEDITERRANEAN      orange, red, yellow, white, light_blue
                   weights: even with bright accents
MUTED_EARTH        brown, light_gray, gray, white, light_brown_(hex)
                   weights: most muted
MEDIEVAL_PRIMARY   red, blue, yellow, white, brown
                   weights: heavy red+brown, accent blue
NORDIC_SUBDUED     gray, white, brown, red, dark_gray
                   weights: subdued with rare red highlight
NONE               no palette; buildings remain untinted
```

Initial set; expand with cultures over time.

VillageTypeData specifies palette either by name:
```json
"colorPalette": "BERGEN_FJORD"
```
or by explicit color list with weights:
```json
"colorPalette": {
  "primary":  { "RED": 0.4, "YELLOW": 0.3, "WHITE": 0.2, "BLUE": 0.1 },
  "accent":   { "WHITE": 0.6, "BROWN": 0.3, "BLACK": 0.1 },
  "roof":     { "BROWN": 0.7, "DARK_GRAY": 0.3 }
}
```

Culture supplies the default palette when the village type doesn't.
Default culture defaults to `MUTED_EARTH`.

### Player-requested repaint

Player right-clicks a builder NPC at any building site → `Request
Repaint` action.

UI:
- Pick PRIMARY, ACCENT, ROOF colors from a palette
- Cost: 4 dye items per slot per building footprint area / 64
  (rounded up). A 9×7 cottage with all three slots = 4 × 3 ×
  ceil(63/64) = 12 dye items
- Confirms cost in dye + bronze (small labor fee)
- Builder begins a multi-day BuilderRepaintGoal: walks to building,
  re-stamps tintable blocks with new color over N visits
- Building record updates immediately for save-data; visual update
  progressive

Restrictions:
- Owned-by-player or unowned only — cannot repaint someone else's house
- One repaint job per building at a time
- Notifies homeowner NPC if applicable; if relationship < neutral,
  builder declines

### NPC-triggered auto-recolor (adjacent feature)

When a household's shared treasury crosses a wealth threshold, the
household commissions a repaint on itself. Hooks into NPC Phase 3
economic channels:

- Treasury threshold: `BUILDING_FOOTPRINT × 32 bronze`
- Triggers once per building lifetime (no infinite repaint loop)
- New color: weighted-random from village palette, biased toward
  brighter / less common colors than the current one
- Visible village-history entry: "The Aalson family painted their
  house red in the spring."

Defer to NPC Phase 3 implementation; this doc reserves the integration
hook.

## Age variants

Variants declare `agePreference`:

- `FRESH` — recently built; bright colors, intact blocks
- `WEATHERED` — moss patches, slightly cracked stone, faded paint
- `ANCIENT` — heavy moss, ivy, weathered timber
- `ANY` — eligible regardless

Village supplies an `ageCategory` derived from `villageAgeYears` (NPC
Phase 4 village history doc 30):

- 0–5 years → favors FRESH
- 5–25 years → mixed (light bias to FRESH/WEATHERED)
- 25–100 years → favors WEATHERED
- 100+ years → favors ANCIENT (some buildings still FRESH because
  individual buildings get rebuilt over time)

When village history isn't yet available (decoration rework lands
before NPC Phase 4), default to FRESH for all newly-spawned villages.
Older villages introduced via expansion/ruination later use the proper
distribution.

## Ruination variants

Same pattern as age but for damaged/abandoned states. Variants
declare:
```json
"ruinationLevel": 0.7  // 0.0 = pristine, 1.0 = ruin
```

Used by:
- Sacked-village village-history flags (NPC Phase 4)
- Abandoned-settlement special village types (future)
- The wall ruination hook (subsystem 12)

Out of scope for v1 implementation but the manifest field reserves
the slot for future use.

## Data structures

```java
public record BuildingVariant(
    String id,
    String displayName,
    VillageSizeTier minTier, VillageSizeTier maxTier,
    float weight,
    Set<SlotTag> preferredTags,
    StylePreference stylePref,
    AgePreference agePref,
    Set<String> tags,
    int maxPerVillage,
    Set<ColorSlot> colorSlots,
    float ruinationLevel
) {}
// Footprint is queried via StructureSizeCache, never carried on the variant.

public enum StylePreference { RURAL, URBAN, ANY }
public enum AgePreference { FRESH, WEATHERED, ANCIENT, ANY }
public enum ColorSlot { PRIMARY, ACCENT, ROOF }

public class VariantRegistry {
    Map<BuildingType, List<BuildingVariant>> variantsByType;
    Optional<BuildingVariant> resolve(BuildingType type, String variantId);
    List<BuildingVariant> eligibleFor(BuildingType type, Style style,
                                     VillageSizeTier tier, AgeCategory age);
}

public record ColorPalette(
    String id,
    Map<DyeColor, Float> primaryWeights,
    Map<DyeColor, Float> accentWeights,
    Map<DyeColor, Float> roofWeights
) {}

public class ColorPaletteRegistry { ... }

// Building record extension:
public class Building {
    // ... existing fields ...
    @Nullable DyeColor primaryColor;
    @Nullable DyeColor accentColor;
    @Nullable DyeColor roofColor;
    String variantId;          // NEW — which variant was placed
}
```

Codec extends Building with three optional `DyeColor` fields and a
`variantId` string. Migration: existing Building records get
`primaryColor=null` and `variantId=type.name().toLowerCase()` defaults.

## Integration points

- **VillagePlanner / BuildingPlacer**: variant selection layers on
  top of profile selection.
- **StructureSizeCache**: keyed by `(culture, style, type, variant)`
  instead of just `(culture, type)`.
- **CultureResolver**: extended fallback chain (above).
- **VillageBiomeStyle**: tint pass runs *before* biome material
  substitution.
- **Building / VillageSavedData codec**: new fields on Building.
- **VillageTypeData**: new `style` and `colorPalette` fields.
- **GuildData**: guild halls force primaryColor by guild color
  lookup.
- **BuilderMaintenanceGoal**: extends with `BuilderRepaintGoal`
  variant for player-requested repaints.
- **NPC Phase 3 economic channels**: treasury-threshold trigger for
  auto-recolor.
- **NPC Phase 4 village history**: `agePreference` driven by
  villageAgeYears; auto-recolor produces history entries.

## Behavior contract

### Does

- Provide multiple variants per building type with manifest-driven
  selection.
- Distinguish RURAL and URBAN styles within a single culture.
- Apply per-building primary/accent/roof colors from village palette.
- Allow player to commission repaints via builders.
- Reserve hooks for age variants and ruination variants.

### Does not

- Resize variants at runtime. Variant chosen at placement; permanent.
- Change colors over time except via explicit repaint actions.
- Generate variants procedurally. All authored.
- Support per-block color overrides. Slot-level granularity only.

## Edge cases

- **Variant has no NBT for required level.** Falls back to default
  variant for that type at the same level.
- **Slot too small for any variant.** Building drops; matcher logs
  warning. Same as today, just with more variants to fall through.
- **Palette weight sums to zero.** Treated as no palette; building
  gets null colors.
- **Local-neighbor exclusion eliminates all candidates.** Best-effort:
  drop the exclusion, place anyway. Two same-color neighbors
  acceptable in dense urban cores.
- **Builder unavailable for repaint** (no builder NPC in village).
  Repaint UI shows "no builder available"; player can travel to
  another village's builder.
- **Player paints already-painted building same color.** Cost still
  applies (labor); silent no-op visually.
- **Building variant with `colorSlots: []`** (intentionally untintable
  variant). Tint pass skips; building stays whatever the NBT
  authored.
- **Manifest missing.** Defaults applied (above); behavior is
  predictable but minimal.

## Ordering dependencies

- **Absorbs the zoning rework.** Layouts 2–16 conversion to slot/
  matcher pattern + ZoneRegistry deletion happen as part of this
  phase, because BuildingProfile becomes variant-keyed and it's
  cleaner to do that change once.
- Must precede NPC rework so the NPC plan can reference variants.
- Must precede Trade Route rework where it reads building positions
  for route endpoints — variants don't affect endpoints, but the
  zoning-rework absorption does.
- Must precede the rest of the decoration rework (this is its
  Phase 0).

## Open decisions (resolved)

- **Tint marker convention.** Opt-out: `white_*` always tints.
  Confirmed.
- **Color slot count.** Three: PRIMARY, ACCENT, ROOF. Confirmed.
- **Style profile precedence.** Explicit `style` field on village
  type wins; auto-derive on `style: "auto"`. Confirmed.
- **Launch scope.** Default culture only, RURAL + URBAN both
  authored. Confirmed.
- **Migration approach.** One-shot path migration; no dual-path
  support. Confirmed.
- **Sequence position.** Phase 0 of decoration rework; absorbs
  zoning rework. Confirmed.

## Does-not-include

- Per-block color customization (granular palette painting).
- Procedural variant generation.
- Random color rerolls on building condition changes.
- Multi-tile variants spanning multiple building footprints.
- Player-authored variants (no in-world variant editor).
- Trade-good color variation (e.g. dyed-cloth bonus from local
  weavers). Could be a future economy hook.
- Banner / heraldry on buildings as a color extension. Banners
  belong to subsystem 06.
- Seasonal palette shifts.

## Revision notes

(Changes recorded here as the spec evolves.)

### P0a-01 / P0a-02 — initial migration + manifest plumbing

- The minimal manifests written for each migrated building omit
  `footprint`. The spec example shows an explicit footprint, but
  the "Missing manifest defaults" entry omits it too. Treating an
  absent `footprint` as "ask the NBT" keeps `StructureSizeCache`
  as the source of truth and avoids hand-typed dimensions
  drifting from the actual NBT geometry. Authored variant packs
  (P0a-15 / P0a-16) can declare `footprint` explicitly when
  overriding NBT geometry.
- `CultureResolver` keeps the (culture → default) two-level
  fallback chain it had before, just rewritten onto the new
  `{culture}/rural/{type}/{type}/level_{n}` shape. The richer
  variant-aware chain in §"CultureResolver fallback chain" lands
  in P0a-04 as planned; the interim shape is documented in the
  resolver's javadoc.
- A small helper `CultureResolver.toVariantAwarePath` translates
  the legacy `{type}/level_{n}` path (still emitted by
  `BuildingRegistry` and `VillageTypeBuilder`) into the new
  `rural/{type}/{type}/level_{n}` layout. Reused from
  `StructureSizeCache` so both code paths agree on the
  translation. Non-canonical paths (e.g. the market-stall
  `default/market/stall/stall_1`) pass through unchanged so the
  market subsystem is unaffected.
- Migration only touches the default-culture building NBTs at
  the `{type}/level_{n}.nbt` shape. The kingdom-castle test
  fixtures under `default/castle/test/...` are kit pieces (not
  building level NBTs) and stay where they are. Non-default
  cultures had no authored buildings and so had nothing to move.

### P0a-03 / P0a-04 / P0a-05 — variants + 7-step resolver + size cache rekey

- `BuildingVariant` deviates from the doc's "Data structures"
  snippet in two ways:
    - Adds `String culture`, `Style style`, and `BuildingType type`
      to the record. The doc's flat
      `Map<BuildingType, List<BuildingVariant>>` doesn't carry
      culture/style any other way, and downstream callers
      (`CultureResolver`, the planner) need them on the variant
      itself rather than via a parallel index map.
    - Uses a nested `Footprint(int x, int z)` record instead of
      `BoundingBox`. The manifest is XZ-only; synthesizing a Y
      just to wrap in a 3D box would be lossy and would force
      every reader (size cache, scoring) to re-extract XZ.
- A new `Style` enum (`RURAL`, `URBAN`) and `AgeCategory` enum
  (`FRESH`, `WEATHERED`, `ANCIENT`) were added alongside the
  existing `StylePreference` / `AgePreference`. The doc's
  `eligibleFor(BuildingType, Style, VillageSizeTier, AgeCategory)`
  signature implies these as concrete (no `ANY` member) sibling
  types to the `*Preference` bias enums.
- Per the P0a-05 task brief, an explicit manifest `footprint`
  takes precedence over the NBT-measured size. When a variant has
  no override (i.e. all migrated buildings, since their minimal
  manifests omit `footprint`), the cache loads the NBT through
  the same seven-step resolver the placer uses, so size and
  geometry stay in sync.
- `StructureSizeCache` keeps a legacy `get(structurePath,
  rotation)` overload as a one-step bridge for planning-layer
  callers (`LayoutPrimitive`, `PlanContext`) that still pass
  `"{type}/level_{n}"` strings. Defaults: `culture=default`,
  `style=RURAL`, `variantId=type-default`. P0a-06 upgrades these
  callers when the matcher learns variant selection.
- The interim `CultureResolver.toVariantAwarePath` helper added
  in P0a-01 / P0a-02 was deleted — the seven-step resolver
  constructs paths directly and there is no longer any string-
  level rewriting. `parseLegacyTypeLevel` replaces it as the
  one-line bridge used by the size cache and `resolveFromPath`.
- The default-of-default fallback (steps 5–6) emits a one-time
  warning per `(BuildingType, variantId)` combination so an
  authored variant going missing for a culture is loud once but
  not noisy. Step 7 (no NBT anywhere) is a logged error listing
  all six tried paths.

### P0a-06 / P0a-07 / P0a-19 — variant scoring + style auto + diversity bonus

- `VillageTypeData.style` is a free-form `String` (default
  `"auto"`) rather than an enum. Doc 15 lists `"rural"` /
  `"urban"` / `"auto"` today but the auto-derivation rules already
  reference layout categories that may grow new style folders
  before the enum does. The consumer (`StyleAutoDeriver`) lower-
  cases and switches; unknown values fall through to `"auto"`.
- `StyleSelection` is a sealed interface with `Fixed` and
  `UrbanLeaning` variants. The doc describes the URBAN-leaning
  case as a per-building dice roll, so the resolution can't
  collapse to a single `Style` at the village level. The
  per-slot roll happens in `PlacementMatcher.applyVariantSelection`
  via `StyleSelection.pickStyle(rng)`.
- **Determinism preserved for the current pack.** Two RNG calls
  could leak into the existing villages' planning stream and
  shift downstream rolls (terrain jitter, etc.):
  - `StyleSelection.pickStyle` for `UrbanLeaning`
  - `VariantSelector.select`'s weighted-random roll
  Both are short-circuited when only one style or one variant is
  authored for the type — exactly the situation today, since
  P0a-15 / P0a-16 (variant pack authoring) hasn't shipped.
  `StyleAutoDeriver.pickStyleForType` and `VariantSelector`'s
  single-candidate fast path encode this. Once additional
  variants are authored, the RNG stream will diverge from the
  pre-P0a-06 stream — that's expected and acceptable per the
  task brief.
- **Recipe-direct commits keep the default variant.** Layout
  primitives that bypass `PlacementMatcher` and call
  `tryCommitWithRetries` themselves (most shape recipes do this
  for the town hall and a few feature placements) end up with
  the `LayoutSlot` constructor's default-variant + RURAL.
  That's fine while only RURAL content exists — the seven-step
  resolver handles the path lookup either way. P0a-15 / P0a-16
  authoring will need to revisit this and either route those
  commits through the selector or add a recipe-side variant
  pick, depending on how much variation the recipe wants.
- **Placement counter scope.** The counter lives on a
  `VariantSelector` instance owned by `PlanContext` (lazily
  constructed). One `PlanContext` per `VillagePlanner.plan` call
  → one matcher run → one counter. A second village in the same
  matcher pass starts fresh because each call gets its own
  `PlanContext`. The counter is keyed by full `VariantKey`
  (culture + style folder + type + variantId) so two cultures
  that happen to share a variantId don't share a counter.
- **`AgeCategory` is wired through `VillageAgeCategoryHook`** —
  a single static method returning `FRESH` until NPC Phase 4
  doc 30 lands. Swap the implementation there to turn on
  weighted age selection.
- **Village preferred tags are reserved for P0a-14.** The
  scoring formula reads them via the `VariantSelector.select`
  parameter, but the matcher passes an empty set today. P0a-14
  (VillageTypeData colour-palette work) is the natural place to
  land the field.

### P0a-08 / P0a-09 / P0a-10 — colour data model + tint pass

- **`white_glazed_terracotta` slot decision.** Doc 15 lists this
  block under both `ACCENT` and `ROOF` ("when on a roof slope").
  The P0a-10 brief says the slot is decided by block type, not
  position. Treating glazed terracotta as `ACCENT` only matches
  the brief; authors who want a roof-glazed effect should use
  `white_carpet` or `white_candle` (both already on the `ROOF`
  list). If we ever need glazed-terracotta roof tinting, we'll
  need a position-aware second-look path.
- **Palette colour names.** Two doc 15 colour names don't have
  exact `DyeColor` matches. The presets use the closest
  available substitute and flag the choice here:
  - `BERGEN_FJORD` lists "mustard" — uses `ORANGE` as the
    nearest dye proxy. (`YELLOW` reads too bright; `ORANGE`
    sits closer to the historical mustard pigment.)
  - `MUTED_EARTH` lists "light_brown_(hex)" — uses `YELLOW`
    as the proxy at low weight. The note in doc 15 implies a
    custom hex value, which `DyeColor` can't represent.
- **Tint pass insertion point.** Runs inside
  `BuildingPlacer.placeAndRegister`, sandwiched between
  `template.placeInWorld(...)` and `applyBiomeSwap(...)`. The
  loop walks the same rotated footprint extents that the biome
  swap walks so the two passes visit the same world cells.
- **Forced colour overrides (TEMPLE / TOWN_HALL / guild halls)
  are P0a-12.** Marked with a `TODO P0a-12` comment in
  `VillagePaletteResolver.planFor` — the override branch should
  short-circuit before sampling and is the natural place to
  consult `GuildData` and `VillageTypeData`'s eventual
  signature-colour field.
- **Building codec migration.** P0a-08 adds four new
  `optionalFieldOf` fields, so pre-P0a-08 saves load cleanly
  with `variantId = type-default` and all colours `null`. The
  full save-data migration pass (rewriting old records to carry
  the new fields explicitly) is P0a-20.
- **Per-building RNG.** Colour sampling uses the village's
  existing deterministic RNG (`new Random((long) origin.hashCode()
  * 31L + villageName.hashCode())`) which `VillageSpawner`
  already creates and threads through farm plots and inhabitant
  population. Same seed + village → same colour assignments.
  Note: this is distinct from the planning RNG (which lives on
  `PlanContext`); the spawn-time stream and the planning stream
  diverge by design — colours don't affect layout, layout
  doesn't affect colours.
- **Village→palette is hardcoded for now.** Default culture
  → `MUTED_EARTH`; everything else → `NONE` (no tint). P0a-14
  replaces this with `VillageTypeData.colorPalette` parsing.

### P0a-11 / P0a-12 / P0a-14 — palette resolution + neighbour exclusion + forced overrides

- **`VillageTypeData.colorPalette` storage.** Pre-parsed at type-
  load time into a `ColorPalette` record (or `null` to fall
  through). Both shapes from doc 15 (string preset id and inline
  `{primary,accent,roof}` object) go through
  `ColorPaletteRegistry.parse` so the on-disk format and the
  in-memory representation stay consistent.
- **`CultureDefaultPalettes`.** Holds the culture → default
  palette mapping. Currently `default → MUTED_EARTH`; future
  cultures register defaults here. Replaces the hardcoded P0a-10
  bridge in `VillagePaletteResolver.paletteFor`. Unknown
  cultures fall through to `NONE`.
- **TEMPLE override is unconditional.** Applies even when the
  village's resolved palette is `NONE`, because the spec frames
  TEMPLE → WHITE as a culture-agnostic constant. TOWN_HALL with
  a non-null `signatureColor` follows the same rule for the
  same reason — when explicit colour is declared, it shouldn't
  be silently dropped just because the rest of the village is
  un-tinted.
- **Guild-hall override is partial.** `GuildData` (the existing
  record under `Guilds.Adventurer`) has no colour fields today.
  The override falls through to palette sampling and emits a
  one-time warning per guild type. Adding `guildColor`/
  `guildAccent` to `GuildData` is a separate task — when those
  fields land, the override resolution in
  `VillagePaletteResolver.planFor` is the only call site that
  needs an update. The matching block in the resolver lives
  immediately after the TOWN_HALL branch, marked with the
  one-time warning logic.
- **Neighbour exclusion implementation.** `NeighborColorIndex`
  is a per-village list of `(centre, primaryColor)` tuples
  built up during `VillageSpawner`'s placement loop. Lookup is
  a linear scan within an XZ Euclidean radius of 12 blocks. With
  current villages capped at ~30 buildings, scan cost stays in
  the low hundreds of comparisons per village. Flagged for a
  future spatial-index pass if village sizes climb. The index is
  fed only successful placements with non-null primary colour,
  so untintable buildings (NONE palette, variants without
  PRIMARY in `colorSlots`, TEMPLE not contributing because it
  forces WHITE — actually TEMPLE *does* contribute since its
  primary is WHITE) don't poison subsequent samples.
- **Soft-exclusion fallback.** `VillagePaletteResolver` retries
  primary sampling without the soft set when the first call
  collapses to `null` (only happens when the soft multiplier
  zeroes every weight, which the doc calls "pathological"). The
  retry is hard-exclusion-free as well, so the building always
  gets *some* primary colour rather than silently going un-
  tinted.
- **Determinism preserved.** The neighbour index reads the
  matcher's placement order via `layout.buildings()` iteration
  in `VillageSpawner`. No separate ordering for colour purposes;
  same `(worldSeed, origin, villageName)` produces the same
  building order, the same neighbour sets at sample time, and
  therefore the same colours.

### P0a-13 — repaint goal + UI

- **Right-click flow uses a profession bypass, not the profile
  action route.** `WANDERING_TRADER` already bypasses the unified
  profile screen on plain right-click; builders now do the same
  and open the repaint screen directly. Shift-right-click still
  opens the profile so the rest of the verb framework (rep,
  hobbies, etc.) remains accessible. This avoids the five-point
  `NpcProfileActionPacket` wiring and matches the existing
  precedent for direct-access professions.
- **Persistence — repaint job lives on the builder NPC's NBT.**
  Stored as flat `repaint.*` keys via the existing
  `addAdditionalSaveData` / `readAdditionalSaveData` flow on
  `TownspersonMob`. One job per builder; multi-job queues are a
  future iteration. If the builder dies the job is dropped with
  the entity — doc 15 mentions transferring jobs to another
  builder, but with no current test for that scenario the
  implementation skips it and flags it as a follow-up.
- **Runtime tint pass recognises already-tinted blocks.** The
  initial-placement `TintPass` only matches `white_*` markers;
  the runtime path needs to swap RED → BLUE on a repaint, so
  `TintBlockTable.entryForAnyColour` returns the family entry
  for any colour of a tintable family. The placement path stays
  white-only — accidentally tinting an authored grey-terracotta
  block would be a regression, and authors deliberately use
  `light_gray_*` as the opt-out.
- **Per-visit batching.** Doc 15 says "footprint / 20 blocks
  repainted per visit (rounded up)". The goal interprets that
  as `totalVisits = ceil(area / 20)` with a per-visit block cap
  derived from the same. Each in-game day the builder walks to
  the building, plays a 30-second working animation, then
  applies one batch. Building's `primaryColor` / `accentColor` /
  `roofColor` fields update at confirm time so save-data is
  always current; in-world blocks catch up over visits.
- **Guild-hall colour override is still partial.** P0a-12 left
  the override falling through to palette sampling because
  `GuildData` carries no colour fields. The repaint screen
  doesn't special-case guild halls either — players can repaint
  a guild hall as if it were any other building (subject to the
  same ownership rules). Once `GuildData` gains colour fields,
  the placement-time and repaint-time paths both need updates;
  marker remains in `VillagePaletteResolver.planFor`.
- **DyeColor → RGB swatches in the UI** are hardcoded rather
  than read from `DyeColor.getTextureDiffuseColor()`. The RGB
  table in `RepaintScreen.rgbOf` mirrors the standard Minecraft
  dye palette and avoids tying the screen to a specific
  Mojang/NeoForge accessor that has shifted between versions.

### P0a-15 — HOUSE pilot manifests + missing-NBT regression

- The pilot manifests for `cottage`, `house`, and `large_house`
  were authored verbatim from doc 16 §3 (with the spec's
  `big_house` renamed to `large_house` to match the actual
  folder name).
- The migrated `house` variant's manifest was set to
  `colorSlots: ["PRIMARY"]` conservatively. Doc 16 says the
  slot list should be "derived from existing NBT's white-block
  usage", but the NBT was deleted from main before this pass
  could inspect it (see below). When the NBT returns, run a
  block-content scan to upgrade the slot list if the NBT also
  uses `white_concrete` / `white_carpet` / etc.
- **Missing NBT regression in `043db2a "Current State"`.** That
  commit added the three HOUSE manifests but also deleted
  `default/rural/house/house/level_1.nbt` (the migrated
  variant) without adding `cottage/level_1.nbt` or
  `large_house/level_1.nbt`. Every HOUSE placement in main
  now hits the resolver's step-7 hard fail. The pilot can't be
  meaningfully tested until those NBT files land. P0a-15 is
  flagged Partially-Implemented in the tracker rather than
  Implemented.

### Schema correction — manifest `footprint` removed

- The `footprint` field has been removed from the manifest
  schema and from the `BuildingVariant` record. The NBT is the
  single source of truth for variant geometry;
  `StructureSizeCache` reads dimensions from it on first load
  and caches them. Carrying a parallel value on the manifest
  created a sync burden during authoring and gave authors a
  way to disagree silently with the actual NBT.
- The change is non-breaking. The loader still parses
  manifests that carry a stale `footprint` field — JSON
  unknown-field tolerance is unchanged — and any cached value
  is simply ignored. Per-variant authoring spec footprints
  remain in doc 16 §3 / §4 as authoring targets for the NBT,
  not manifest fields.
- The "manifest footprint as authoritative override" branch in
  `StructureSizeCache.load(CacheKey)` has been removed; the
  cache always measures from the NBT now.
