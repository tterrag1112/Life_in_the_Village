# 16 — Variant Pack Specification: Default Culture

## Purpose

Concrete authoring specifications for every variant in the default
culture's RURAL and URBAN packs. Each variant entry contains enough
detail to author the NBT directly without further design iteration:
footprint, height, concept, materials, tintable surface plan, key
features, anchor blocks where applicable, and manifest values.

Hand-authored. Use these specs as a checklist; deviate where in-world
results demand it, and record divergences in the revision notes at
the bottom.

After NBTs are authored against this spec, the next prompt (formerly
prompt 8) generates the manifest.json files, validates against
StructureSizeCache, runs distribution tests, and reports back on
matcher behavior with real variants in play.

## Section 1 — Pack style statements

### RURAL pack visual language

Timber-frame construction over a stone or cobblestone foundation.
Wood-shingle or thatched roofs with steep-to-moderate pitch (roughly
45°). One or two stories, ground-hugging proportions, generous wall
thickness implied by deep window reveals. Warm earth-tone palette:
oak and spruce planks for primary timber, cobblestone or stone-brick
for foundations, dark oak for trim. Tintable surfaces sit on
plastered wall panels (white_terracotta) and shutters (white_concrete).
Roofs use white_glazed_terracotta sparingly as accent tiles for the
ridgeline only — main roof bulk is dark oak stairs/slabs and stays
untinted. Buildings have small kitchen gardens or productive yards
gestured at via foundation extensions, even when the AdjunctPlot
system isn't yet attaching real plots.

### URBAN pack visual language

Stone, brick, and plaster construction. Two to three stories typical
with a fourth-story garret on larger buildings. Steep slate-tile
roofs (white_glazed_terracotta as the dominant tintable roof
surface, so palette roof colors apply across the whole roof). Narrow
frontages built deep into the lot, walls implied to be share-able
with neighbors (all urban variants design their side walls flush to
the footprint edge to support future row-house adjacency work).
Plastered upper-story walls in white_terracotta tint heavily; ground
floors in unpainted stone or brick stay neutral. Trim and shutters
in white_concrete tint as accent. Tall narrow windows, prominent
chimney stacks, decorative cornices implied through stair-block
detailing under the eaves.

### Cross-pack consistency

Three rules apply equally to both packs and make a single village
read as one place:

1. **Roofs all tint via white_glazed_terracotta on the dominant roof
   plane.** RURAL uses it sparingly (ridgeline only — most roof bulk
   stays dark wood); URBAN uses it as the primary roof surface. Either
   way, when a village's palette specifies a roof color, every roof
   responds to it visibly.

2. **Primary walls tint via white_terracotta.** Both packs reserve
   white_terracotta for the building's primary palette surface. RURAL
   uses it on plaster panels between timber framing; URBAN uses it on
   upper-story walls. The placement of white_terracotta in the NBT
   determines what the building's primary color paints.

3. **Accents tint via white_concrete.** Shutters, doors trim, signs,
   and small architectural ornaments. Used in moderation — not every
   building has accent surfaces, and that's fine. Variants without
   accent surfaces declare `colorSlots: ["PRIMARY", "ROOF"]` (or
   just `["PRIMARY"]`) in their manifest and the building's
   accentColor stays null.

4. **light_gray_*** is the explicit escape for surfaces that should
   read as white but never tint. Use it for marble floors, sail-cloth
   roofs in coastal variants, or any "actually white" feature.

## Section 2 — Authoring conventions

These are extracted from the existing default-culture NBTs and apply
to every new variant.

**Door placement.** Front door faces the placement-grid road side.
For most variants this is the negative-Z face when the NBT is at
Rotation.NONE; verify against the existing house and blacksmith NBTs
before authoring. Door positioned roughly centered on the front face,
offset by 1 from the corner if the building has a front-facing window
on either side.

**Boundary block convention.** Exterior walls are flush with the
footprint edge declared in the manifest. Roof overhang extends 1
block beyond the wall on all four sides via stair blocks; the
manifest footprint covers the wall footprint, not the overhang.
StructureSizeCache reads the manifest, so authored overhang doesn't
affect placement.

**Multi-level stacking.** `level_1.nbt` is the base building. `level_2.nbt`
extends/upgrades the same footprint with added detail (a second story
where level_1 has only one, a finished trim where level_1 has rough,
a slate roof where level_1 has thatch). Footprint must match exactly
across levels. The matcher places level_n based on building tier; an
upgrade event swaps the structure in place.

**Footprint grid.** Buildings align to a 1-block grid. Footprints are
typically odd-numbered in both dimensions (5×5, 7×5, 9×7) to allow
centered front-door placement and symmetric window layouts. Even-
numbered footprints (4×6, 6×8) are acceptable for asymmetric or
deliberately rectangular variants like coaching inns.

The footprint is a property of the authored NBT, not a manifest
field. Authors target the dimensions while building the NBT;
`StructureSizeCache` reads them at load time. The manifest never
restates the footprint — see doc 15 §"Footprint resolution".

**Anchor block usage.** Buildings that contain subbuildings author
anchor blocks per `03-subbuildings.md`:

- `CHISELED_STONE_BRICKS` for STALL (market stalls — preserved
  convention)
- `CHISELED_DEEPSLATE` for APARTMENT
- `CHISELED_QUARTZ_BLOCK` for SHOP
- `CHISELED_NETHER_BRICKS` for ARCHIVE
- `CHISELED_POLISHED_BLACKSTONE_BRICKS` for INN_ROOM
- `CHISELED_TUFF_BRICKS` for WORKSHOP
- `CHISELED_RED_SANDSTONE` for CHAPEL_ROOM
- `CHISELED_SANDSTONE` for CELLAR

Anchor blocks are scanned and replaced with air at placement time,
so they don't appear in the final structure. Place them at the
center of each subbuilding region. Inn variants ship with INN_ROOM
anchors so the visitor flux system (NPC Phase 4) has subbuilding
targets ready when it lands.

**Tintable surface convention.** Recap from doc 15 for ease of
reference:

- PRIMARY: `white_terracotta`, `white_wool`
- ACCENT: `white_concrete`, `white_concrete_powder`, `white_glazed_terracotta`
- ROOF: `white_glazed_terracotta` on roof slope, `white_carpet`, `white_candle`
- Opt-out: `light_gray_*`

A variant's manifest declares which slots it actually uses. Don't
declare a slot the variant doesn't paint.

## Section 3 — RURAL variant specifications

> **Note on footprints.** Footprint values in each variant entry
> are authoring targets for the NBT, not manifest fields. The
> manifest does not declare footprint; `StructureSizeCache` reads
> it from the NBT at load time. See doc 15 §"Footprint
> resolution".

### HOUSE — 3 variants

#### `cottage`
- **Footprint:** 5 × 5
- **Height:** 5 (single story + steep gable roof)
- **Concept:** Small single-room dwelling for a single occupant or
  a young couple. The most modest residence in any village.
- **Materials:** Cobblestone foundation (1 block tall), oak log corners,
  oak plank walls with white_terracotta plaster panels in the upper half,
  dark oak stairs roof with thatch (hay block) ridge, wooden door front,
  one window on each non-door wall.
- **Tintable surfaces:** Plaster panels (PRIMARY), shutters around
  windows in white_concrete (ACCENT), white_glazed_terracotta accent
  blocks at the gable peaks (ROOF — sparing).
- **Key features:** A single chimney on one side wall (cobblestone
  stack rising past the eaves), a tiny stoop of two stair blocks at
  the door.
- **Anchors:** None.
- **Manifest values:**
  - `weight`: 1.2 (slightly common, fills tight slots)
  - `minTier`: HAMLET, `maxTier`: CITY
  - `stylePreference`: RURAL
  - `tags`: ["compact", "cottage", "single_family"]
  - `preferredTags`: [] (no slot bias)
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 999 (effectively unlimited)

#### `house` (the migrated existing variant — keep as-is)
- **Footprint:** Whatever the existing NBT measures
- **Concept:** The default residence. Mid-sized, two-room, one-story.
- **Manifest values:**
  - `weight`: 1.0
  - `minTier`: HAMLET, `maxTier`: CITY
  - `stylePreference`: RURAL
  - `tags`: ["single_family"]
  - `preferredTags`: []
  - `colorSlots`: derived from existing NBT's white-block usage
  - `maxPerVillage`: 999

#### `big_house`
- **Footprint:** 9 × 7
- **Height:** 8 (two full stories + attic gable)
- **Concept:** Multi-family or wealthy household. Two stories with a
  garret. The aspirational residence in a rural village.
- **Materials:** Stone-brick foundation (2 blocks tall), oak log
  half-timbering across both stories, white_terracotta plaster between
  timbers heavy on the second story, dark oak stairs roof with the
  ridge running long-axis, white_glazed_terracotta accent tiles
  bracketing the gable, oak plank shutters tinted ACCENT.
- **Tintable surfaces:** Heavy white_terracotta on second story
  (PRIMARY), white_concrete shutters and door surround (ACCENT),
  white_glazed_terracotta gable accents (ROOF).
- **Key features:** Front door centered, two windows flanking; second
  story has three windows on front face, two on each side; chimney
  stack on one short side; small attached lean-to (visible foundation
  block extension) on one long side suggesting a future workshop or
  garden.
- **Anchors:** Optional APARTMENT anchor in second story for
  multi-family configurations.
- **Manifest values:**
  - `weight`: 0.6 (less common — the "wealthy" variant)
  - `minTier`: VILLAGE, `maxTier`: CITY
  - `stylePreference`: RURAL
  - `tags`: ["multi_family", "wealthy"]
  - `preferredTags`: ["RESIDENTIAL_CORE", "CIVIC_ADJACENT"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 4

#### `longhouse`
- **Footprint:** 13 × 5
- **Height:** 6
- **Concept:** Extended single-story dwelling for an extended family
  or several generations. Long, narrow, multi-doored.
- **Materials:** Stone foundation, oak log corners and intermediate
  posts every 4 blocks, white_terracotta plaster panels between posts,
  thatched (hay) roof with dark oak stair eaves, two front doors —
  one near each end — implying two household sections under one roof.
- **Tintable surfaces:** Plaster panels (PRIMARY), shutters (ACCENT),
  no roof tinting (thatch stays neutral; declare `colorSlots:
  ["PRIMARY", "ACCENT"]`).
- **Key features:** Two centered chimneys (one per household section),
  windows in alternating pattern, slightly off-center entry porches.
- **Anchors:** Two APARTMENT anchors, one in each section.
- **Manifest values:**
  - `weight`: 0.5
  - `minTier`: HAMLET, `maxTier`: TOWN
  - `stylePreference`: RURAL
  - `tags`: ["multi_family", "longhouse"]
  - `preferredTags`: ["RESIDENTIAL_RING"]
  - `colorSlots`: ["PRIMARY", "ACCENT"]
  - `maxPerVillage`: 3

### BLACKSMITH — 2 variants

#### `blacksmith` (the migrated existing variant)
- Manifest values:
  - `weight`: 1.0
  - `minTier`: HAMLET, `maxTier`: CITY
  - `stylePreference`: RURAL
  - `tags`: []
  - `colorSlots`: derived from existing NBT
  - `maxPerVillage`: 2

#### `forge_house`
- **Footprint:** 9 × 7
- **Height:** 6
- **Concept:** Combined smithy and home — the smith's family lives
  upstairs and works downstairs. More common in established villages
  where the smith has settled.
- **Materials:** Cobblestone ground floor (the workshop level — fire
  resistance implied), oak plank upper story for living quarters,
  steep dark oak roof, exterior chimney stack rising from forge area
  through to ridgeline.
- **Tintable surfaces:** Upper-story walls in white_terracotta
  (PRIMARY), shutters in white_concrete (ACCENT), no roof tint.
- **Key features:** Open-fronted forge area with anvil visible
  through wide doorway (use a fence-gate or no door on the forge
  bay), separate residential entrance on the side, large chimney,
  exterior tool rack implied via item frames or armor stands.
- **Anchors:** APARTMENT anchor in upper story.
- **Manifest values:**
  - `weight`: 0.5
  - `minTier`: VILLAGE, `maxTier`: TOWN
  - `stylePreference`: RURAL
  - `tags`: ["combined_workshop_residence"]
  - `preferredTags`: []
  - `colorSlots`: ["PRIMARY", "ACCENT"]
  - `maxPerVillage`: 1

### CARPENTRY — 2 variants

#### `carpentry` (the migrated existing variant — keep as-is)
- Manifest values:
  - `weight`: 1.0
  - `minTier`: HAMLET, `maxTier`: CITY
  - `stylePreference`: RURAL

#### `sawhouse`
- **Footprint:** 11 × 7
- **Height:** 6
- **Concept:** Larger workshop-focused variant with covered work yard
  on one side. Implies higher-volume carpentry serving multiple
  villages.
- **Materials:** Stone foundation, oak plank walls, exposed timber
  framing, dark oak roof with one large gable, an attached open-roof
  pole barn structure implied by 4 large oak log posts and a stair-
  block roof over the work yard. Stacks of oak logs decoratively
  placed in the yard area.
- **Tintable surfaces:** Plaster panels between timbers
  (PRIMARY), shutters and door trim (ACCENT), no roof tint.
- **Key features:** The pole-barn yard contributes to the building's
  footprint; logs and a sawhorse prop visible; large double-door
  front entrance to indoor workshop.
- **Anchors:** WORKSHOP anchor in the indoor workshop area.
- **Manifest values:**
  - `weight`: 0.5
  - `minTier`: VILLAGE, `maxTier`: TOWN
  - `stylePreference`: RURAL
  - `tags`: ["workshop", "log_yard"]
  - `preferredTags`: ["INDUSTRIAL_RING"]
  - `colorSlots`: ["PRIMARY", "ACCENT"]
  - `maxPerVillage`: 1

### WOODCUTTER — 1 variant (migrated existing)
- Manifest values:
  - `weight`: 1.0
  - `minTier`: HAMLET, `maxTier`: TOWN
  - `stylePreference`: RURAL
  - `tags`: ["log_yard"]

### WEAVER — 2 variants

#### `weaver` (migrated existing)
- Manifest values:
  - `weight`: 1.0
  - `minTier`: HAMLET, `maxTier`: CITY
  - `stylePreference`: RURAL

#### `loom_house`
- **Footprint:** 7 × 9
- **Height:** 6
- **Concept:** Larger weaving operation with prominent drying-rack
  awning along one side. The "village's main weaver" variant.
- **Materials:** Cobblestone foundation, oak walls with lots of
  windows on one long side (light for the loom), steep dark oak
  roof, attached lean-to awning (stair-block roof) on the side
  opposite the windows.
- **Tintable surfaces:** Walls in white_terracotta (PRIMARY),
  shutters and the awning fascia in white_concrete (ACCENT).
- **Key features:** Large multi-window wall, lean-to awning, fence-
  enclosed yard space adjacent (drying-rack territory),
  prominent chimney for dye work.
- **Anchors:** WORKSHOP anchor inside, optional APARTMENT in upper
  story.
- **Manifest values:**
  - `weight`: 0.5
  - `minTier`: VILLAGE, `maxTier`: TOWN
  - `stylePreference`: RURAL
  - `tags`: ["workshop", "drying_rack_yard"]
  - `colorSlots`: ["PRIMARY", "ACCENT"]
  - `maxPerVillage`: 1

### BAKERY — 2 variants

#### `bakery` (migrated existing)

#### `bake_house`
- **Footprint:** 7 × 9
- **Height:** 6
- **Concept:** Combined home and bakery, with a large prominent
  exterior oven. Smaller but more characterful than the indoor-only
  bakery variant.
- **Materials:** Stone foundation, plaster-and-timber walls, dark
  oak roof, prominent exterior oven structure (stone bricks dome
  with a furnace block at the heart) attached to the side of the
  building.
- **Tintable surfaces:** Plaster walls (PRIMARY), shutters (ACCENT).
- **Key features:** The exterior oven is the visual centerpiece;
  small front counter area implied via half-blocks; chimney from
  the oven dome rising past the roof.
- **Anchors:** SHOP anchor near the front, APARTMENT in upper story.
- **Manifest values:**
  - `weight`: 0.6
  - `minTier`: VILLAGE, `maxTier`: TOWN
  - `stylePreference`: RURAL
  - `tags`: ["combined_workshop_residence", "oven_shed"]
  - `colorSlots`: ["PRIMARY", "ACCENT"]
  - `maxPerVillage`: 1

### MILLER — 1 variant (migrated existing)

### FARMER — 2 variants

#### `farmhouse` (migrated existing — most common)

#### `croft`
- **Footprint:** 5 × 5
- **Height:** 4
- **Concept:** The smallest farming dwelling — a tenant farmer's
  one-room cottage. Often appears at field edges in larger villages
  rather than in the residential ring.
- **Materials:** Stone foundation, all-timber walls (no plaster),
  thatched roof, single window, single door, stone chimney.
- **Tintable surfaces:** Door frame and shutters in white_concrete
  (ACCENT only). No primary tinting — the timber walls stay natural.
  Manifest declares `colorSlots: ["ACCENT"]`.
- **Key features:** Notably small; placed near farms rather than in
  the village core. Strong silhouette via steep thatch and tiny
  footprint.
- **Anchors:** None.
- **Manifest values:**
  - `weight`: 0.7
  - `minTier`: HAMLET, `maxTier`: TOWN
  - `stylePreference`: RURAL
  - `tags`: ["compact", "tenant_farmer", "field_edge"]
  - `preferredTags`: ["AGRICULTURAL_FRINGE"]
  - `colorSlots`: ["ACCENT"]
  - `maxPerVillage`: 6

### FISHERY — 2 variants

#### `fishery` (migrated existing)

#### `dock_house`
- **Footprint:** 7 × 9 (long axis perpendicular to shore)
- **Height:** 5
- **Concept:** A fishery built directly onto a dock structure. The
  back third of the building extends over water on stilts, with
  net-drying frames on the dock side.
- **Materials:** Spruce log stilts, spruce plank walls (different
  wood signals coastal vernacular), dark oak roof, fence railings
  along the dock area.
- **Tintable surfaces:** Walls (PRIMARY), shutters and the door
  awning (ACCENT).
- **Key features:** Stilts are critical — the building should appear
  to extend over water, with the dock surface continuous with the
  building's back floor; net-drying frames (fence + item frames)
  on the dock.
- **Anchors:** WORKSHOP anchor inside.
- **Manifest values:**
  - `weight`: 0.5
  - `minTier`: HAMLET, `maxTier`: TOWN
  - `stylePreference`: RURAL
  - `tags`: ["coastal", "fisherman", "dock"]
  - `preferredTags`: ["WATERFRONT", "COASTAL"]
  - `colorSlots`: ["PRIMARY", "ACCENT"]
  - `maxPerVillage`: 2

### APOTHECARY — 1 variant (migrated existing)

### STONEMASON — 2 variants

#### `stonemason` (migrated existing)

#### `stoneyard`
- **Footprint:** 11 × 7
- **Height:** 5
- **Concept:** Stonemason with prominent attached yard for raw stone
  blocks and finished pieces. Heavier industrial feel than the
  default stonemason.
- **Materials:** Almost entirely stone construction (stone bricks,
  cobblestone, andesite — minimal wood), low pitch slate roof.
- **Tintable surfaces:** Limited primary tinting on the door wall
  (PRIMARY), accents on shutters and the yard fence (ACCENT). The
  building deliberately reads as more austere than other variants.
- **Key features:** Open yard (fence-enclosed) attached to one side,
  filled with stone block stockpiles (use various stone block types
  as decorative props), stonemason's bench (smithing table on a
  stone slab) visible.
- **Anchors:** WORKSHOP inside.
- **Manifest values:**
  - `weight`: 0.5
  - `minTier`: VILLAGE, `maxTier`: CITY
  - `stylePreference`: RURAL
  - `tags`: ["workshop", "kiln_yard", "industrial"]
  - `preferredTags`: ["INDUSTRIAL_RING"]
  - `colorSlots`: ["PRIMARY", "ACCENT"]
  - `maxPerVillage`: 1

### CANDLEMAKER — 1 variant (migrated existing)

### STABLE — 2 variants

#### `stable` (migrated existing)

#### `horse_house`
- **Footprint:** 9 × 9
- **Height:** 6
- **Concept:** Combined stable and stablekeeper's residence. Animals
  on ground floor, family above. Implies a larger paddock attached.
- **Materials:** Stone foundation, oak plank walls with prominent
  half-timber pattern, dark oak roof with separated ridges (one over
  living quarters, one over stable area), high arched openings on
  the stable side.
- **Tintable surfaces:** Upper story plaster (PRIMARY), shutters and
  doors (ACCENT), white_glazed_terracotta accent at ridge ends (ROOF).
- **Key features:** Two clearly different exterior treatments on the
  two halves — stable side has wide openings and a prominent fence-
  paddock implication; residential side has windows and a normal
  door.
- **Anchors:** APARTMENT in upper story residential.
- **Manifest values:**
  - `weight`: 0.5
  - `minTier`: VILLAGE, `maxTier`: TOWN
  - `stylePreference`: RURAL
  - `tags`: ["combined_workshop_residence", "paddock"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 1

### INN — 2 variants

#### `inn` (migrated existing)
- **IMPORTANT:** Add INN_ROOM anchors throughout the upper story
  during this prompt's authoring pass even on the existing migrated
  variant. The visitor flux system in NPC Phase 4 needs them and
  forward-compatibility is cheap.

#### `tavern`
- **Footprint:** 9 × 7
- **Height:** 6
- **Concept:** Smaller drinking-and-meals establishment with a few
  upstairs rooms. The "rural pub" — less of a destination than the
  inn but warmer and more local.
- **Materials:** Stone ground floor (heavily reinforced — fights
  happen), timber-and-plaster upper story, dark oak roof, prominent
  hanging sign (item frame on a fence post extension).
- **Tintable surfaces:** Upper story plaster (PRIMARY), the hanging
  sign and door trim (ACCENT), white_glazed_terracotta on roof
  ridgeline (ROOF — sparing).
- **Key features:** Open ground-floor common area with bar (use
  smoker or barrels), benches, fireplace; two-or-three small rooms
  upstairs each with a bed.
- **Anchors:** 2-3 INN_ROOM anchors upstairs. SHOP anchor on ground
  floor (for the bar/serving area).
- **Manifest values:**
  - `weight`: 0.7
  - `minTier`: HAMLET, `maxTier`: CITY
  - `stylePreference`: RURAL
  - `tags`: ["lodging", "tavern"]
  - `preferredTags`: ["CIVIC_ADJACENT", "ROAD_SIDE"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 2

### TEMPLE — 2 variants

#### `chapel` (migrated existing — small temple variant)

#### `shrine`
- **Footprint:** 5 × 7
- **Height:** 7 (taller-than-wide silhouette)
- **Concept:** Tiny one-room sacred space. Used as the temple slot
  in hamlets that are too small for a full chapel. Vertical, narrow,
  reverent.
- **Materials:** Stone-brick walls, deep blue stained-glass windows
  (one tall arched window on the front face), dark oak roof with
  steep pitch, white_concrete trim, single small wooden door.
- **Tintable surfaces:** This is a forced-WHITE building per doc 15
  forced-color overrides. Manifest declares `colorSlots: []` — even
  if white_terracotta is present in the NBT, no palette tinting
  applies, and the building stays uniformly off-white.
- **Key features:** Verticality is everything — narrow footprint,
  tall walls, small high windows, a steepled roof with white_glazed
  _terracotta or light_gray tile (light_gray, since this building
  shouldn't tint).
- **Anchors:** CHAPEL_ROOM anchor centered.
- **Manifest values:**
  - `weight`: 1.0
  - `minTier`: HAMLET, `maxTier`: VILLAGE  (replaced by chapel/cathedral above this tier)
  - `stylePreference`: RURAL
  - `tags`: ["sacred", "compact"]
  - `preferredTags`: []
  - `colorSlots`: []
  - `maxPerVillage`: 1

### LIBRARY — 1 variant (migrated existing)
- **IMPORTANT:** Add ARCHIVE anchor inside during migration's
  manifest pass. Scholar profession in NPC Phase 2 needs it.

### GUILD_HALL — 1 variant (migrated existing)

### TOWN_HALL — 1 variant (migrated existing)

### NOBLE_MANOR — 1 variant (migrated existing)

### MARKET — 1 variant (migrated existing)
- Existing market stalls authored via STALL anchors should remain.

### GUARD_TOWER — 2 variants

#### `tower` (migrated existing)

#### `watchpost`
- **Footprint:** 5 × 5
- **Height:** 8 (notably tall for footprint)
- **Concept:** Smaller, simpler tower for hamlet/village tier
  defense. One ground room, one watch deck above. Wood-and-stone
  construction reads as less imposing than the full GUARD_TOWER.
- **Materials:** Stone-brick foundation 2-3 blocks tall, oak log
  upper structure, fence-railing watch deck at top, no full roof —
  open-air observation platform with a small canopy on one side.
- **Tintable surfaces:** Upper structure walls (PRIMARY only —
  declares `colorSlots: ["PRIMARY"]`).
- **Key features:** Top watch deck must be physically accessible
  via an interior ladder or stair; fence railings around the deck;
  one or two arrow-slit windows in the lower stone section.
- **Anchors:** None.
- **Manifest values:**
  - `weight`: 0.8
  - `minTier`: HAMLET, `maxTier`: VILLAGE
  - `stylePreference`: RURAL
  - `tags`: ["defensive", "compact", "watchpost"]
  - `preferredTags`: ["WALL_ADJACENT", "PERIMETER"]
  - `colorSlots`: ["PRIMARY"]
  - `maxPerVillage`: 4

### WATCHTOWER — 1 variant (migrated existing)

### BARRACKS — 1 variant (migrated existing)

## Section 4 — URBAN variant specifications

> **Note on footprints.** Footprint values in each variant entry
> are authoring targets for the NBT, not manifest fields. The
> manifest does not declare footprint; `StructureSizeCache` reads
> it from the NBT at load time. See doc 15 §"Footprint
> resolution".

URBAN variants share the consistency rules from Section 1: tile
roofs in white_glazed_terracotta, plaster upper stories in
white_terracotta, accent trim in white_concrete, narrow deep
footprints with side walls flush to the footprint edge.

### HOUSE — 3 variants

#### `townhouse`
- **Footprint:** 5 × 7 (narrow front, deep)
- **Height:** 9 (two full stories + attic)
- **Concept:** The standard urban residence. Narrow frontage, three
  stories, share-able side walls.
- **Materials:** Stone or brick ground floor, white_terracotta
  plaster upper stories, white_glazed_terracotta tile roof (steep
  pitch, ridge running short-axis so neighbors can share long
  walls), tall narrow windows on every story.
- **Tintable surfaces:** Upper-story walls (PRIMARY), shutters and
  door trim (ACCENT), the entire main roof (ROOF — a meaningful
  visual mass).
- **Key features:** Front door at street level with a stoop, three
  windows on the front face stacking vertically, a chimney on one
  side wall, side walls deliberately blank to read as share-walls.
- **Anchors:** Optional APARTMENT in upper story.
- **Manifest values:**
  - `weight`: 1.2 (the workhorse urban residence)
  - `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["townhouse"]
  - `preferredTags`: ["RESIDENTIAL_CORE"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 999

#### `tenement`
- **Footprint:** 7 × 7
- **Height:** 11 (three full stories + attic)
- **Concept:** Multi-family urban housing. Multiple households, one
  building. Larger and taller than a townhouse, but plainer.
- **Materials:** Brick or stone ground floor, plaster upper stories,
  tile roof. Less ornament than the townhouse, more windows per
  story (5 on the front face), a single shared front door leading
  to a stairwell.
- **Tintable surfaces:** Upper walls (PRIMARY — broad surface area),
  minimal accent (just door trim — ACCENT), roof (ROOF).
- **Key features:** Scale and repetition. Many windows in regular
  grids, a single street entrance, side walls blank.
- **Anchors:** Multiple APARTMENT anchors (one per story above
  ground level — typically 3 total).
- **Manifest values:**
  - `weight`: 0.7
  - `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["multi_family", "tenement"]
  - `preferredTags`: ["RESIDENTIAL_CORE", "DENSE"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 6

#### `row_house`
- **Footprint:** 4 × 7 (very narrow)
- **Height:** 9
- **Concept:** Narrowest urban variant — designed to share walls
  with neighbors on both sides. In a future row-house adjacency
  pass, multiple of these will place adjacent. For now, ships as a
  standalone narrow building.
- **Materials:** Same urban palette but emphasizing verticality.
  Stone ground floor, plaster middle and upper stories, tile roof.
- **Tintable surfaces:** Same as townhouse.
- **Key features:** Notably narrow front face — only 2 blocks of
  window per story flanking the door. Side walls completely blank
  (foundation for future adjacency work). Tall, slim silhouette.
- **Anchors:** Optional APARTMENT in upper story.
- **Manifest values:**
  - `weight`: 0.6
  - `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["townhouse", "narrow", "share_wall_ready"]
  - `preferredTags`: ["RESIDENTIAL_CORE", "DENSE"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 999

### BLACKSMITH — 1 variant

#### `urban_smithy`
- **Footprint:** 7 × 9
- **Height:** 8
- **Concept:** Two-story urban smithy. Workshop on ground floor with
  large arched street-facing opening; smith's family upstairs.
- **Materials:** All-stone ground floor (fire safety), plaster
  upper story, tile roof, prominent chimney rising from forge.
- **Tintable surfaces:** Upper story (PRIMARY), shutters (ACCENT),
  roof (ROOF).
- **Key features:** Wide street-facing arched opening to workshop
  (use stone arch + fence gate or wide barn-door style), residential
  entrance on side, large chimney.
- **Anchors:** SHOP anchor at workshop, APARTMENT in upper story.
- **Manifest values:**
  - `weight`: 1.0
  - `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["combined_workshop_residence", "urban_workshop"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 2

### CARPENTRY — 1 variant

#### `urban_carpentry`
- **Footprint:** 7 × 9, Height: 8
- **Concept:** Urban carpentry shop. Tall ground-floor workshop,
  smaller upper story for residence.
- **Materials:** Stone ground floor with large windows for daylight,
  plaster upper story, tile roof.
- **Tintable surfaces:** Upper walls (PRIMARY), trim (ACCENT), roof (ROOF).
- **Key features:** Large ground-floor windows showing workshop;
  prominent shop sign over the door; smaller residential window
  pattern upstairs.
- **Anchors:** WORKSHOP anchor at ground floor, APARTMENT upstairs.
- **Manifest values:**
  - `weight`: 1.0, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["combined_workshop_residence", "urban_workshop"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 2

### WEAVER — 1 variant

#### `urban_weaver`
- **Footprint:** 7 × 9, Height: 8
- **Concept:** Multi-story urban weaver. Lots of windows on the
  upper stories for loom light. Workshop ground floor, looms
  upstairs, family in attic.
- **Materials:** Brick ground floor with shop window, plaster
  upper stories with prominent multi-window walls (the "loom light"
  identifier), tile roof.
- **Tintable surfaces:** Upper walls (PRIMARY), shutters (ACCENT), roof (ROOF).
- **Key features:** Distinctive multi-window upper stories — signal
  weaver from across the city.
- **Anchors:** SHOP at street, WORKSHOP on first upper floor,
  APARTMENT in attic.
- **Manifest values:**
  - `weight`: 1.0, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["combined_workshop_residence", "urban_workshop"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 2

### BAKERY — 1 variant

#### `urban_bakery`
- **Footprint:** 7 × 7, Height: 8
- **Concept:** Urban bakery with prominent street-facing shop
  window, internal oven. Smaller than rural bake_house because
  there's no exterior oven.
- **Materials:** Brick ground floor, plaster upper story, tile roof,
  large chimney from internal oven.
- **Tintable surfaces:** Upper walls (PRIMARY), trim and door frame (ACCENT), roof (ROOF).
- **Key features:** Wide street-facing shop window with display
  area visible behind glass (use stained glass or just glass with
  bread item displays via item frames); narrow side door for
  residential access.
- **Anchors:** SHOP at street, APARTMENT upstairs.
- **Manifest values:**
  - `weight`: 1.0, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["combined_workshop_residence", "urban_workshop", "shopfront"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 2

### APOTHECARY — 1 variant

#### `urban_apothecary`
- **Footprint:** 5 × 7, Height: 8
- **Concept:** Tall narrow shop. Distinctive bottle-shelf window on
  the street face. Residence upstairs.
- **Materials:** Stone ground floor, plaster upper story, tile roof.
- **Tintable surfaces:** Upper walls (PRIMARY), shutters and the
  ornate door surround (ACCENT), roof (ROOF).
- **Key features:** Bay-window shopfront with multiple display
  shelves implied via stair blocks and item frames holding potion
  items; hanging sign with mortar-and-pestle imagery.
- **Anchors:** SHOP at street, APARTMENT upstairs.
- **Manifest values:**
  - `weight`: 1.0, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["combined_workshop_residence", "urban_workshop", "shopfront"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 1

### STONEMASON — 1 variant

#### `urban_stonemason`
- **Footprint:** 9 × 7, Height: 6
- **Concept:** Urban stonemason — workshop dominates, residence is
  a small upstairs annex. Less yard space than rural stoneyard.
- **Materials:** Heavy stone construction throughout, low slate
  roof, no plaster.
- **Tintable surfaces:** Limited — the stonemason is deliberately
  austere. Just shutters and door trim (ACCENT only). Manifest
  declares `colorSlots: ["ACCENT"]`.
- **Key features:** Heavy stone walls, large workshop entrance,
  small attached forecourt (1-block-deep stone-paved area in front)
  with stone block samples on display.
- **Anchors:** WORKSHOP at ground.
- **Manifest values:**
  - `weight`: 1.0, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["urban_workshop", "industrial", "stone_construction"]
  - `colorSlots`: ["ACCENT"]
  - `maxPerVillage`: 1

### CANDLEMAKER — 1 variant

#### `urban_candlemaker`
- **Footprint:** 5 × 7, Height: 8
- **Concept:** Small urban shop with distinctive candle-display
  window. Multi-story.
- **Materials:** Brick ground floor, plaster upper story, tile roof.
- **Tintable surfaces:** Upper walls (PRIMARY), shutters and door
  surround (ACCENT), roof (ROOF).
- **Key features:** Distinctive shopfront with multiple sea-pickle
  or candle items displayed in a window; narrow proportions.
- **Anchors:** SHOP at ground, APARTMENT upstairs.
- **Manifest values:**
  - `weight`: 1.0, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["combined_workshop_residence", "urban_workshop", "shopfront"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 1

### INN — 2 variants

#### `urban_inn`
- **Footprint:** 9 × 9, Height: 9
- **Concept:** Three-story urban inn. Many guest rooms, prominent
  street presence, large hanging sign.
- **Materials:** Stone ground floor with large taproom windows,
  plaster upper stories, tile roof, multiple chimneys.
- **Tintable surfaces:** Upper walls (PRIMARY — large surface for
  bold palette colors), shutters and the swinging sign (ACCENT),
  roof (ROOF).
- **Key features:** Prominent hanging sign, large arched ground-
  floor windows, double front doors, multiple stories of guest
  windows; chimneys pluraland prominent.
- **Anchors:** 4-6 INN_ROOM anchors across upper floors, SHOP at
  ground.
- **Manifest values:**
  - `weight`: 1.0, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["lodging", "urban_inn"]
  - `preferredTags`: ["CIVIC_ADJACENT", "ROAD_SIDE"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 2

#### `coaching_inn`
- **Footprint:** 11 × 9, Height: 8
- **Concept:** Inn with attached carriage yard / stable area. Built
  for travelers arriving by horse or cart. The "edge of city" inn
  near gates.
- **Materials:** Stone ground floor, plaster upper, tile roof,
  attached open courtyard with paved stone, fence-railed paddock
  area.
- **Tintable surfaces:** Same as urban_inn.
- **Key features:** Distinctive open courtyard at one end (paved
  stone with watering trough), prominent arched gateway entry to
  courtyard from street, hitching posts.
- **Anchors:** 3-4 INN_ROOM anchors, SHOP at the taproom.
- **Manifest values:**
  - `weight`: 0.6, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["lodging", "urban_inn", "coaching", "paddock"]
  - `preferredTags`: ["GATE_ADJACENT", "ROAD_SIDE", "PERIMETER"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 1

### TEMPLE — 2 variants

#### `cathedral`
- **Footprint:** 13 × 9, Height: 14
- **Concept:** The grand urban temple. Verticality, scale, gravitas.
  Stained-glass windows, twin towers, prominent entrance.
- **Materials:** All stone-brick and chiseled-stone construction.
  Stained-glass windows in many colors. Steep slate roof. White-
  trimmed details throughout.
- **Tintable surfaces:** Forced WHITE per doc 15. Manifest declares
  `colorSlots: []`.
- **Key features:** Twin small towers flanking the entrance, large
  central rose window (use stained glass), tall narrow side
  windows, prominent stone steps leading up to a double door, a
  spire or steeple over the central nave.
- **Anchors:** CHAPEL_ROOM anchor central, optional ARCHIVE in a
  side chapel.
- **Manifest values:**
  - `weight`: 1.0, `minTier`: CITY (cathedral implies city-scale)
  - `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["sacred", "monumental", "civic_landmark"]
  - `preferredTags`: ["CIVIC_CORE", "PLAZA_ADJACENT"]
  - `colorSlots`: []
  - `maxPerVillage`: 1

#### `urban_chapel`
- **Footprint:** 7 × 9, Height: 9
- **Concept:** Smaller urban temple variant for towns that don't
  warrant a cathedral. Stone-built, modest stained-glass, single
  small tower.
- **Materials:** Stone-brick walls, smaller stained-glass windows,
  steep tile roof, modest tower with bell.
- **Tintable surfaces:** Forced WHITE per doc 15. `colorSlots: []`.
- **Key features:** Single tower (bell visible at top), front face
  with three arched windows, stone steps to door.
- **Anchors:** CHAPEL_ROOM central.
- **Manifest values:**
  - `weight`: 1.0, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["sacred", "civic_landmark"]
  - `preferredTags`: ["CIVIC_ADJACENT"]
  - `colorSlots`: []
  - `maxPerVillage`: 1

### LIBRARY — 1 variant

#### `urban_library`
- **Footprint:** 9 × 9, Height: 9
- **Concept:** Multi-story urban library. Stone construction, tall
  ornate windows, formal facade with steps.
- **Materials:** Stone-brick walls, tall ornate windows, slate
  roof, tasteful ornament. White_glazed_terracotta accent on the
  cornice (ROOF tinting if used).
- **Tintable surfaces:** Limited primary tinting on a single recessed
  panel above the door (PRIMARY), main wall stays stone. Roof tints
  via cornice accent (ROOF). Manifest declares `colorSlots:
  ["PRIMARY", "ROOF"]`.
- **Key features:** Formal stone steps to entrance, decorative
  cornice work, tall arched windows, optional small reading-balcony
  on the second story.
- **Anchors:** ARCHIVE anchor (1-2 of them) inside.
- **Manifest values:**
  - `weight`: 1.0, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["scholarly", "civic_landmark"]
  - `preferredTags`: ["CIVIC_ADJACENT"]
  - `colorSlots`: ["PRIMARY", "ROOF"]
  - `maxPerVillage`: 1

### GUILD_HALL — 1 variant

#### `urban_guildhall`
- **Footprint:** 11 × 9, Height: 9
- **Concept:** Imposing two-story urban guild hall. Strong street
  presence, prominent emblem mount, formal entrance.
- **Materials:** Stone-brick walls, plaster upper-story panels
  (where the guild emblem panel is supposed to mount), tile roof.
- **Tintable surfaces:** Upper walls (PRIMARY — but note doc 15
  forced override: guild halls take guild colors not palette colors,
  so this surface paints the guild's color), trim and door
  surround (ACCENT — guild accent color), roof (ROOF — palette).
- **Key features:** Wide front facade with central archway entrance,
  large blank panel above the entrance for guild emblem (set by
  decoration subsystem 06), tall windows on upper story.
- **Anchors:** WORKSHOP or ARCHIVE depending on guild type
  (both possible — author both, registry consumes the appropriate one).
- **Manifest values:**
  - `weight`: 1.0, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["civic_landmark", "guild"]
  - `preferredTags`: ["CIVIC_CORE", "PLAZA_ADJACENT"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 4

### TOWN_HALL — 1 variant

#### `urban_townhall`
- **Footprint:** 13 × 9, Height: 12
- **Concept:** The most important civic building in any urban
  village. Tower or steeple, broad steps, prominent banners.
- **Materials:** Stone-brick walls top to bottom, slate roof, single
  prominent clock-tower or banner-tower over the central entrance.
- **Tintable surfaces:** Upper-story walls flanking the tower take
  the village signature color (PRIMARY — forced via doc 15 override
  if signatureColor declared on village type). Trim and entrance
  surround (ACCENT). Roof (ROOF).
- **Key features:** Central tower with banner mount or clock,
  double-door entrance with stone-step approach, balcony-railing
  detail above the entrance, tall flanking windows.
- **Anchors:** ARCHIVE or WORKSHOP optional.
- **Manifest values:**
  - `weight`: 1.0, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["civic_landmark", "monumental", "town_hall"]
  - `preferredTags`: ["CIVIC_CORE", "PLAZA_ADJACENT"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 1

### NOBLE_MANOR — 1 variant

#### `urban_manor`
- **Footprint:** 13 × 11, Height: 10
- **Concept:** Wealthy urban estate. Compact compared to a rural
  noble manor (no formal-garden footprint) but more vertical and
  ornate. Walled front courtyard implied.
- **Materials:** Stone-brick throughout, plaster upper story,
  prominent slate roof with multiple chimneys, formal entrance gate
  in a small front wall.
- **Tintable surfaces:** Upper walls (PRIMARY), trim and the
  entrance gate (ACCENT), roof (ROOF).
- **Key features:** Stone wall enclosing a small front courtyard
  (3-block-deep) before the main entrance, formal gate with
  decorative archway, multi-chimney roof, multiple stories of
  ornate windows.
- **Anchors:** Optional APARTMENT for noble family.
- **Manifest values:**
  - `weight`: 1.0, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["wealthy", "noble"]
  - `preferredTags`: ["CIVIC_ADJACENT"]
  - `colorSlots`: ["PRIMARY", "ACCENT", "ROOF"]
  - `maxPerVillage`: 1

### MARKET — 1 variant

#### `urban_market`
- **Footprint:** 13 × 9, Height: 6
- **Concept:** Covered market hall. Big enclosed structure with
  multiple stalls inside, prominent street-facing arches, large
  open ground floor.
- **Materials:** Stone arches (multiple) along the street face,
  tile roof, internal columns, paved interior floor.
- **Tintable surfaces:** Roof (ROOF — large surface). Upper walls
  if any (PRIMARY). Limited accent. Declare `colorSlots: ["PRIMARY",
  "ROOF"]`.
- **Key features:** Multiple street-facing arched openings, internal
  market stalls with STALL anchors (5-8 of them), columns supporting
  the roof, paved interior.
- **Anchors:** Many STALL anchors for individual market stalls.
- **Manifest values:**
  - `weight`: 1.0, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["civic_landmark", "market"]
  - `preferredTags`: ["PLAZA_ADJACENT", "CIVIC_CORE"]
  - `colorSlots`: ["PRIMARY", "ROOF"]
  - `maxPerVillage`: 1

### GUARD_TOWER — 1 variant

#### `urban_tower`
- **Footprint:** 7 × 7, Height: 14
- **Concept:** Tall stone defensive tower for urban walls. Designed
  to integrate with curtain wall sections (subsystem 12).
- **Materials:** Stone-brick throughout, narrow slit windows, small
  battlement at top.
- **Tintable surfaces:** Limited — defensive structures stay
  stone. Just trim around the entrance (ACCENT). Declare
  `colorSlots: ["ACCENT"]`.
- **Key features:** Tall vertical silhouette, battlement crenellation
  at the top, narrow arrow-slit windows, single small entrance
  door.
- **Anchors:** None.
- **Manifest values:**
  - `weight`: 1.0, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["defensive", "wall_integration"]
  - `preferredTags`: ["WALL_ADJACENT", "PERIMETER", "GATE_ADJACENT"]
  - `colorSlots`: ["ACCENT"]
  - `maxPerVillage`: 8

### WATCHTOWER — 1 variant

#### `urban_watchtower`
- **Footprint:** 5 × 5, Height: 16
- **Concept:** Tallest urban tower variant. Pure observation, no
  defensive role. Goes on a hill or at the highest point of the
  city.
- **Materials:** Stone-brick, very narrow profile, observation deck
  at top, optional bell.
- **Tintable surfaces:** None practical. Declare `colorSlots: []`.
- **Key features:** Extreme verticality, deck at top, optional small
  bell or beacon, single internal stair.
- **Anchors:** None.
- **Manifest values:**
  - `weight`: 1.0, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["defensive", "observation", "monumental"]
  - `preferredTags`: ["HILLTOP", "CIVIC_CORE"]
  - `colorSlots`: []
  - `maxPerVillage`: 1

### BARRACKS — 1 variant

#### `urban_barracks`
- **Footprint:** 11 × 9, Height: 7
- **Concept:** Two-story barracks for the urban garrison. Plain
  functional construction, prominent training-yard implication on
  one side.
- **Materials:** Stone-brick walls, tile roof, sturdy oak shutters,
  fenced training yard implied via short stone wall on one side.
- **Tintable surfaces:** Upper walls (PRIMARY — primary color often
  shows the kingdom signature color here), shutters (ACCENT). Roof
  stays untinted slate. Declare `colorSlots: ["PRIMARY", "ACCENT"]`.
- **Key features:** Long rectangular building, regular spaced
  windows, large central entrance, attached training yard on one
  side (paved stone with weapon racks via item frames).
- **Anchors:** Multiple APARTMENT anchors (squad bunks).
- **Manifest values:**
  - `weight`: 1.0, `minTier`: TOWN, `maxTier`: CITY
  - `stylePreference`: URBAN
  - `tags`: ["defensive", "garrison", "multi_family"]
  - `preferredTags`: ["WALL_ADJACENT", "CIVIC_CORE"]
  - `colorSlots`: ["PRIMARY", "ACCENT"]
  - `maxPerVillage`: 1

## Section 5 — Authoring sequence recommendation

Suggested order to author the NBTs, prioritizing variants that have
the highest visible impact in test villages:

1. `cottage` and `big_house` (RURAL HOUSE) — most-placed variants;
   the test villages need these to feel varied.
2. `townhouse` and `tenement` (URBAN HOUSE) — same reasoning for
   urban tests.
3. `tavern` (RURAL INN) — small cheap win, plus exercises INN_ROOM
   anchors for visitor flux forward-compat.
4. `cathedral` (URBAN TEMPLE) — flagship civic building; if you can
   only author a few urban variants for first test, this is the
   most visible.
5. `urban_smithy`, `urban_weaver`, `urban_bakery` (URBAN production)
   — fill out the urban village reading.
6. `croft`, `dock_house` (RURAL specialty) — variant tag testing.
7. Remaining variants in any order.

This sequencing means that even partway through the pack, test
villages have meaningful variety. Authoring all 30+ variants before
any test would mean the test happens too late.

## Section 6 — Manifest values reference table

For ease of manifest authoring, all values from Sections 3 and 4
collected here. (Identical to the per-variant values; this section
exists for fast lookup during the manifest-writing pass in the
next prompt.)

| Variant ID            | Type        | Style | Weight | minTier | maxTier | maxPerVillage | colorSlots |
|-----------------------|-------------|-------|--------|---------|---------|---------------|------------|
| cottage               | HOUSE       | RURAL | 1.2    | HAMLET  | CITY    | 999           | P,A,R |
| house                 | HOUSE       | RURAL | 1.0    | HAMLET  | CITY    | 999           | derived |
| big_house             | HOUSE       | RURAL | 0.6    | VILLAGE | CITY    | 4             | P,A,R |
| longhouse             | HOUSE       | RURAL | 0.5    | HAMLET  | TOWN    | 3             | P,A |
| blacksmith            | BLACKSMITH  | RURAL | 1.0    | HAMLET  | CITY    | 2             | derived |
| forge_house           | BLACKSMITH  | RURAL | 0.5    | VILLAGE | TOWN    | 1             | P,A |
| carpentry             | CARPENTRY   | RURAL | 1.0    | HAMLET  | CITY    | -             | derived |
| sawhouse              | CARPENTRY   | RURAL | 0.5    | VILLAGE | TOWN    | 1             | P,A |
| woodcutter            | WOODCUTTER  | RURAL | 1.0    | HAMLET  | TOWN    | -             | derived |
| weaver                | WEAVER      | RURAL | 1.0    | HAMLET  | CITY    | -             | derived |
| loom_house            | WEAVER      | RURAL | 0.5    | VILLAGE | TOWN    | 1             | P,A |
| bakery                | BAKERY      | RURAL | 1.0    | HAMLET  | CITY    | -             | derived |
| bake_house            | BAKERY      | RURAL | 0.6    | VILLAGE | TOWN    | 1             | P,A |
| miller                | MILLER      | RURAL | 1.0    | HAMLET  | TOWN    | -             | derived |
| farmhouse             | FARMER      | RURAL | 1.0    | HAMLET  | CITY    | -             | derived |
| croft                 | FARMER      | RURAL | 0.7    | HAMLET  | TOWN    | 6             | A |
| fishery               | FISHERY     | RURAL | 1.0    | HAMLET  | CITY    | -             | derived |
| dock_house            | FISHERY     | RURAL | 0.5    | HAMLET  | TOWN    | 2             | P,A |
| apothecary            | APOTHECARY  | RURAL | 1.0    | HAMLET  | CITY    | -             | derived |
| stonemason            | STONEMASON  | RURAL | 1.0    | HAMLET  | CITY    | -             | derived |
| stoneyard             | STONEMASON  | RURAL | 0.5    | VILLAGE | CITY    | 1             | P,A |
| candlemaker           | CANDLEMAKER | RURAL | 1.0    | HAMLET  | CITY    | -             | derived |
| stable                | STABLE      | RURAL | 1.0    | HAMLET  | CITY    | -             | derived |
| horse_house           | STABLE      | RURAL | 0.5    | VILLAGE | TOWN    | 1             | P,A,R |
| inn                   | INN         | RURAL | 1.0    | HAMLET  | CITY    | -             | derived |
| tavern                | INN         | RURAL | 0.7    | HAMLET  | CITY    | 2             | P,A,R |
| chapel                | TEMPLE      | RURAL | 1.0    | HAMLET  | CITY    | 1             | derived |
| shrine                | TEMPLE      | RURAL | 1.0    | HAMLET  | VILLAGE | 1             | [] |
| library               | LIBRARY     | RURAL | 1.0    | HAMLET  | CITY    | -             | derived |
| guildhall             | GUILD_HALL  | RURAL | 1.0    | VILLAGE | CITY    | -             | derived |
| townhall              | TOWN_HALL   | RURAL | 1.0    | VILLAGE | CITY    | 1             | derived |
| manor                 | NOBLE_MANOR | RURAL | 1.0    | TOWN    | CITY    | 1             | derived |
| market                | MARKET      | RURAL | 1.0    | HAMLET  | CITY    | -             | derived |
| tower                 | GUARD_TOWER | RURAL | 1.0    | HAMLET  | CITY    | -             | derived |
| watchpost             | GUARD_TOWER | RURAL | 0.8    | HAMLET  | VILLAGE | 4             | P |
| watchtower            | WATCHTOWER  | RURAL | 1.0    | HAMLET  | CITY    | -             | derived |
| barracks              | BARRACKS    | RURAL | 1.0    | VILLAGE | CITY    | -             | derived |
| townhouse             | HOUSE       | URBAN | 1.2    | TOWN    | CITY    | 999           | P,A,R |
| tenement              | HOUSE       | URBAN | 0.7    | TOWN    | CITY    | 6             | P,A,R |
| row_house             | HOUSE       | URBAN | 0.6    | TOWN    | CITY    | 999           | P,A,R |
| urban_smithy          | BLACKSMITH  | URBAN | 1.0    | TOWN    | CITY    | 2             | P,A,R |
| urban_carpentry       | CARPENTRY   | URBAN | 1.0    | TOWN    | CITY    | 2             | P,A,R |
| urban_weaver          | WEAVER      | URBAN | 1.0    | TOWN    | CITY    | 2             | P,A,R |
| urban_bakery          | BAKERY      | URBAN | 1.0    | TOWN    | CITY    | 2             | P,A,R |
| urban_apothecary      | APOTHECARY  | URBAN | 1.0    | TOWN    | CITY    | 1             | P,A,R |
| urban_stonemason      | STONEMASON  | URBAN | 1.0    | TOWN    | CITY    | 1             | A |
| urban_candlemaker     | CANDLEMAKER | URBAN | 1.0    | TOWN    | CITY    | 1             | P,A,R |
| urban_inn             | INN         | URBAN | 1.0    | TOWN    | CITY    | 2             | P,A,R |
| coaching_inn          | INN         | URBAN | 0.6    | TOWN    | CITY    | 1             | P,A,R |
| cathedral             | TEMPLE      | URBAN | 1.0    | CITY    | CITY    | 1             | [] |
| urban_chapel          | TEMPLE      | URBAN | 1.0    | TOWN    | CITY    | 1             | [] |
| urban_library         | LIBRARY     | URBAN | 1.0    | TOWN    | CITY    | 1             | P,R |
| urban_guildhall       | GUILD_HALL  | URBAN | 1.0    | TOWN    | CITY    | 4             | P,A,R |
| urban_townhall        | TOWN_HALL   | URBAN | 1.0    | TOWN    | CITY    | 1             | P,A,R |
| urban_manor           | NOBLE_MANOR | URBAN | 1.0    | TOWN    | CITY    | 1             | P,A,R |
| urban_market          | MARKET      | URBAN | 1.0    | TOWN    | CITY    | 1             | P,R |
| urban_tower           | GUARD_TOWER | URBAN | 1.0    | TOWN    | CITY    | 8             | A |
| urban_watchtower      | WATCHTOWER  | URBAN | 1.0    | TOWN    | CITY    | 1             | [] |
| urban_barracks        | BARRACKS    | URBAN | 1.0    | TOWN    | CITY    | 1             | P,A |

`P` = PRIMARY, `A` = ACCENT, `R` = ROOF, `[]` = no tint, `derived` =
read from existing migrated NBT and infer slots.
`-` in maxPerVillage means no cap.

## Revision notes

(Recorded as authoring deviates from the spec.)

### Schema correction — manifest `footprint` removed

- The `footprint` field has been removed from the manifest
  schema (see doc 15 §"Footprint resolution"). Per-variant
  `Footprint:` lines in §3 / §4 are retained as authoring
  guidance for the NBT itself; they no longer correspond to a
  manifest field. Section 2's "Footprint grid" subsection and
  the Section 3 / Section 4 headers carry a clarifying note.
- Section 6's reference table never had a footprint column, so
  no changes there.
