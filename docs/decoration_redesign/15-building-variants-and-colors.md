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
  "footprint": { "x": 9, "z": 7 },
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
- `footprint` — declared bounding box at Rotation.NONE; override for
  StructureSizeCache
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
    BoundingBox footprint,
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
