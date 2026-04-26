# 10 — Farm Plot Rework

## Purpose

Replace the current `FarmPlotPlacer` with a system that produces
fields feeling like land cleared for farming, not fields stamped onto
flattened pads. The feel should be: a farmhouse in the middle, patches
of varying shape conforming to terrain around it, hedgerows or drystone
walls on boundaries that snap to natural features, footpaths between
patches, and on slopes terraced fields with retaining walls.

This is the single most algorithmically complex subsystem in the
decoration rework. Terracing in particular is nontrivial and deserves
its own slice.

## Design

### Four-slice plan

Shipping this in four distinct slices with in-world tests between each:

**Slice A — FieldPatch without terracing.** Replace `FarmPlotPlacer`'s
levelPad-every-column approach with a shape that follows terrain
within a tolerance. Steep sites reject (same as today). Single-level
fields only. Fence around perimeter, strips of crops within.

**Slice B — Hedgerow primitive.** Replace the uniform fence-and-gate
boundary with a `Hedgerow` primitive that snaps to natural features.
When the patch edge is near a forest edge, hedgerow merges into the
trees. When near a stream, no hedgerow on that side. When near a ridge,
hedgerow terminates at the ridge. Fence remains as fallback where no
natural feature is nearby.

**Slice C — FarmTerritory aggregate.** Replace single-FarmPlot-per-
farmhouse with a `FarmTerritory`: a connected region containing 3–8
FieldPatches of varying shapes, optional pasture, optional small
orchard, optional farmstead corner (shed, haystack). Plots connect
via a path tree rooted at the farmhouse entrance.

**Slice D — Terracing.** FieldPatches on slopes beyond the single-level
tolerance split into multiple sub-patches at stepped elevations, joined
by low retaining walls. Algorithm: sample slope across the patch bounds,
detect the dominant gradient axis, carve terraces perpendicular to it
with a minimum patch width per terrace. Drainage channels run down the
slope between terraces.

### Data model changes

Current `FarmPlot` record stays. New records added alongside:

```java
public record FieldPatch(
    UUID patchId,
    UUID territoryId,           // parent FarmTerritory
    BlockPos origin,
    PatchShape shape,           // bounds, existing concept extended
    List<TerraceLevel> terraces,// empty → single-level
    CropType crop,
    List<HedgerowSegment> hedges
) {}

public record TerraceLevel(
    int y,                       // absolute world Y
    BoundingBox bounds,          // within the patch
    Direction downslope,         // direction of retaining wall
    int retainingWallHeight
) {}

public record HedgerowSegment(
    BlockPos start,
    BlockPos end,
    HedgerowStyle style,         // BUSH, STONE_WALL, FENCE, TREE_LINE
    boolean snappedToFeature     // forest edge, stream bank, ridge
) {}

public record FarmTerritory(
    UUID territoryId,
    UUID farmhouseId,
    UUID villageId,
    List<UUID> patchIds,
    List<UUID> pastureIds,       // pasture plots share FarmPlot type
    Optional<UUID> orchardId,
    Optional<BlockPos> farmsteadCorner  // shed / haystack position
) {}
```

`FarmPlot` gains a new subtype value set (`HOMESTEAD_*` covered in
subsystem 11). Existing subtypes (`CROP_FIELD`, `PASTURE`) stay.

### Terrain-following algorithm (Slice A)

For each FieldPatch:

1. Sample the `MOTION_BLOCKING_NO_LEAVES` heightmap across the patch
   footprint.
2. Compute slope statistics: mean Y, standard deviation, max gradient.
3. If mean slope < `EASY_THRESHOLD` (2 blocks across footprint),
   single-level patch at the median Y. No leveling; the patch follows
   the terrain within tolerance.
4. If slope is between easy and `STEEP_THRESHOLD`, still single-level
   but carve 1-block steps on the uphill edge only (minimal
   intervention).
5. If slope > `STEEP_THRESHOLD`, proceed to terracing (Slice D).

Single-level patches place farmland blocks directly on the natural Y
at each column, allowing the surface to undulate. Crops still grow;
vanilla farmland supports this.

### Hedgerow snapping (Slice B)

For each patch edge:

1. Probe outward from the edge (up to 6 blocks) looking for natural
   features: forest edge (leaves + logs), stream bank (water), ridge
   (sharp elevation change), cliff.
2. If a feature is found within 6 blocks:
   - Forest edge → omit hedgerow, trees serve as the boundary
   - Stream → omit hedgerow, water serves as the boundary
   - Ridge → hedgerow terminates at the ridge
   - Cliff → same as ridge
3. If no feature, place a hedgerow of the culture-appropriate style
   (bush for default/nordic, stone wall for highland, formal hedge
   for imperial).
4. A gate block is placed where the path from the patch meets the
   hedge.

### FarmTerritory composition (Slice C)

For each farmhouse:

1. Choose a territory radius (15–30 blocks depending on village tier).
2. Divide the territory into 3–8 patches using a relaxed Voronoi or
   Poisson-disk sampling; each patch center is at least 8 blocks from
   every other center.
3. Each patch gets a random subtype from: CROP_FIELD (60%), PASTURE
   (30%), ORCHARD (10%). Pasture and orchard counts scaled to tier.
4. Route footpaths from the farmhouse entrance to each patch's gate,
   forming a tree. Path primitive is the existing dirt-path router.
5. If the territory includes the village's outer residential ring,
   farmstead-corner props (small shed, haystack) are placed at the
   territory's outermost point.

### Terracing (Slice D)

On a steep patch:

1. Determine dominant gradient axis (X-axis vs Z-axis which has more
   slope).
2. Divide patch into N terraces perpendicular to the gradient, each
   3–5 blocks wide on the flat, differing by 1–2 blocks of Y.
3. On the downslope edge of each terrace, place a retaining wall
   (drystone in highland, log in nordic, brick in imperial, dirt +
   cobble in default).
4. A narrow drainage channel runs down the slope between terraces
   (1-block-wide dirt path, doubles as worker access).
5. Crops plant across each flat terrace independently.

Terraces are a significant authoring investment. Slice D is optional —
Slices A-C can ship without it, with steep patches rejected until D
lands.

## Integration points

- **Replaces `FarmPlotPlacer`**. Migration path: keep `FarmPlotPlacer`
  until Slice C is stable, then delete.
- **FarmPlot registry on VillageSavedData** remains; FarmTerritory
  is added alongside.
- **FarmerGoal** (existing) needs to work across multiple patches per
  territory. Currently it works per-plot; adding territory-awareness
  is a small change (iterate patches; currently iterates one).
- **Footpaths** reuse existing dirt-path router.
- **Hedgerow primitive** is shared with subsystems 08, 09.
- **Terrace retaining walls** may reuse `BuildingFoundation` retaining
  code patterns.

## Behavior contract

### Does

- Place fields that follow terrain within tolerance.
- Respect natural features for field boundaries.
- Group multiple patches under a single farmhouse.
- (Slice D) Handle steep terrain with stepped terraces.

### Does not

- Level ground beyond minor step-carving. Terrain wins.
- Produce different crops from the current crop system — this rework
  is placement, not production.
- Move existing fields. Migration is one-shot at rework time.

## Edge cases

- **Farmhouse without surrounding open space.** Territory radius
  shrinks to whatever is available; may be only 1-2 patches.
- **Territory overlaps another farmhouse's territory.** Resolved by
  farmhouse distance; the farther farmhouse forfeits overlapping area.
- **All candidate patch locations are too steep.** Farmhouse gets a
  reduced territory with only a small fenced yard. Acceptable degenerate
  case — it happens at mountain-farm layouts and reads correctly.
- **Hedgerow probe crosses a road.** Road is treated as a hard boundary;
  hedgerow ends at the road edge.
- **Terrace retaining wall would intersect a neighbor's patch.** Wall
  is shortened to patch bounds; minor drainage-channel overlap is
  accepted.

## Ordering dependencies

- Requires zoning rework complete (stable road network and building
  positions for territory computation).
- Slices can ship independently. A typical order: A → B → C → D.
- Hedgerow primitive ships in slice B; reused by subsystems 08, 09.
  If those ship first, the primitive lives in the shared Pieces
  package; slice B uses it.

## Open decisions

- **Thresholds.** EASY_THRESHOLD = 2 blocks over 9-block-radius
  footprint. STEEP_THRESHOLD = 5 blocks. Tunable after in-world test.
- **Territory radius scaling.** Proposed: 15 for HAMLET, 20 for
  VILLAGE, 25 for TOWN, 30 for CITY. City farms are rare but do exist.
- **Patch count per territory.** Proposed: 2-3 HAMLET, 3-5 VILLAGE,
  5-8 TOWN, 5-8 CITY. More patches than farmland area allows gets
  clamped.
- **Orchard crop type.** Proposed: reuse existing ORCHARD CropType,
  place sapling + fruit drops. Matches existing FarmerGoal.
- **Retaining wall material per culture.** See slice D description.
- **Whether Slice D ships before other rework pieces.** Proposed: ship
  Slices A-C and move on; D can land after the rest of the decoration
  rework.

## Does-not-include

- Dynamic field size changes as villages grow.
- Player-placed FarmTerritory.
- Soil quality or crop rotation systems.
- Tractors or mechanized farming (medieval-period mod; manual only).

## Revision notes

(Changes recorded here as the spec evolves.)
