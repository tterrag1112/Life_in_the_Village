# 12 — Village Walls

## Purpose

Replace the disabled wall code with a wall system that integrates
cleanly with the new zoning model. Walls must:

- Follow the outermost ring of defensive and residential buildings
- Align gates with roads that enter the village
- Place flanking towers at corners and along long straight segments
- Adapt to terrain features (ridges, shorelines, cliffs)
- Vary by culture (palisade, earthen rampart, stone curtain, masonry
  fortification)
- Support ruination states for old or war-damaged villages (out of
  scope for v1, but design must not preclude it)

The hard problem is the circular dependency: wall wants to know where
towers and gates are, but towers are buildings that need to be placed
by the matcher, which needs to know where the wall puts them. Solution:
plan the wall *schematically* during recipe compose, then realize it
after buildings.

## Design

### Two-stage construction

**Stage 1 — WallPlan (during compose):**
Compute the wall polyline as a pure-logical object. Emit
`WALL_ADJACENT` and `GATE_ADJACENT` slots at projected tower/gate
positions so the building matcher places defensive buildings there.
No blocks placed.

**Stage 2 — WallRealizer (after buildings):**
Read back the actual placed positions of WALL_ADJACENT and
GATE_ADJACENT buildings. Refine the WallPlan polyline if necessary.
Assemble NBT pieces from the culture's wall kit along the polyline.

### WallPlan computation (Stage 1)

Input: village footprint (shape recipe's existing building position
set), road network, terrain profile.

Algorithm:

1. Compute the concave hull of the village's building footprints.
   Offset outward by a buffer (typically 8 blocks).
2. Snap the hull to natural barriers: water (wall omitted on water-
   side), ridges (wall follows the ridge), cliffs (wall terminates
   at cliff).
3. Identify gate positions: every trade road exiting the village,
   plus every major village-path-tier road exit.
4. Identify tower positions: every corner (polyline bend > 30°), plus
   every straight-run midpoint if straight > 24 blocks.
5. Simplify the polyline: merge close points, enforce minimum segment
   length.
6. Output a `WallPlan` with: polyline, gate positions, tower positions,
   wall thickness hint, wall height hint.

### Slot emission (Stage 1)

For each tower position:
- Emit a `WALL_ADJACENT` slot at the tower position with quality 100
- Emit a `GATE_ADJACENT` slot at each gate position with quality 100

These slots are consumed by the building matcher, which places
defensive buildings (GUARD_TOWER, WATCHTOWER, BARRACKS) at them.

The building profiles already prefer these tags (from zoning rework's
BuildingProfileRegistry). The wall system just guarantees the
positions exist.

### WallRealizer (Stage 2)

Input: WallPlan + list of actually-placed wall-adjacent buildings.

Algorithm:

1. For each tower position in the plan, check whether a building was
   actually placed there.
   - If yes: the wall segment approaches the building face, not the
     projected point. Tower-position-refinement.
   - If no: the wall simply passes through the planned tower spot
     with no tower piece (a tower was specified but no defensive
     building was placed — the village ran out of guard buildings).
2. For each gate position, similarly verify the gate-adjacent building
   exists. If yes, the gate piece's opening aligns with the road
   passing through the building.
3. Walk the refined polyline. For each segment, pick appropriate NBT
   pieces from the wall kit:
   - STRAIGHT pieces fill long runs
   - CORNER pieces at bends
   - GATE pieces at gate positions (roads passing through)
   - TOWER pieces at tower positions (attached to existing defensive
     buildings)
   - STAIRS_TO_WALKWAY interior access, every 20 blocks
   - END_CAP where the wall meets water or cliff
4. Place pieces with biome-style material substitution.

### Culture-specific wall kits

| Culture   | Style                                  | Pieces in kit                       |
|-----------|----------------------------------------|-------------------------------------|
| default   | Stone curtain wall                     | straight, corner, gate_small,       |
|           | (village tier basic)                   | gate_large, tower_round, end_cap,   |
|           |                                        | stairs                              |
| nordic    | Log palisade with earthen rampart      | straight, corner, gate, watchpost,  |
|           | (hamlet) → log stockade (village) →    | end_cap                             |
|           | stone with log battlements (town)      |                                     |
| highland  | Drystone wall                          | straight, corner, gate, tower_round,|
|           |                                        | end_cap, stairs                     |
| imperial  | Full masonry fortification             | All pieces + bastion, barbican,     |
|           | (town+) — the wall castle-code adapts  | crenellated straight, string-course |
|           | well here                              |                                     |

The imperial kit can reuse the existing castle-code `RoomStack` +
`StyleDetailConfig` system for city-tier walls. Simpler styles use
lightweight NBT pieces.

### Tier gating

Walls generate only for:

- **walled_by_default = true** in VillageTypeData (existing flag)
- TOWN tier or above
- ENCLAVE shape always (existing)

Villages below TOWN tier get no wall even if they have guard towers.
Hamlets with palisade walls are rare and tied to FRONTIER-type
villages (border keeps).

### Ruination support

Design does not implement ruination but leaves room:

- WallPlan has a `ruinationLevel` float (0.0–1.0), default 0.0
- WallRealizer skips pieces or swaps in ruined variants per level
- Out of scope for v1; NPC Phase 4 village history may feed
  ruinationLevel later (e.g., sacked villages inherit high ruination)

## Data structures

```java
public record WallPlan(
    UUID wallId,
    UUID villageId,
    List<BlockPos> polyline,
    List<BlockPos> gatePositions,
    List<BlockPos> towerPositions,
    int wallThickness,
    int wallHeight,
    String kitId,                 // "default_stone", "nordic_log", etc.
    float ruinationLevel
) {}

public enum WallSegmentType {
    STRAIGHT, CORNER, GATE_SMALL, GATE_LARGE,
    TOWER_ROUND, TOWER_SQUARE, BASTION,
    END_CAP, STAIRS_TO_WALKWAY, BARBICAN,
    WATCHPOST
}

public record WallSegment(
    BlockPos pos,
    Direction facing,
    WallSegmentType type
) {}
```

WallPlan persists on VillageSavedData. WallSegment is ephemeral —
computed by WallRealizer and placed as NBT.

## Integration points

- **Shape recipes**: emit WallPlan during compose for walled villages,
  integrate with existing slot emission for towers/gates.
- **Building matcher**: existing WALL_ADJACENT / GATE_ADJACENT slots
  consumed by defensive buildings from BuildingProfileRegistry.
- **Realiser pipeline**: new WallRealizer step after building placement
  and foundation.
- **CultureResolver**: wall piece path resolution.
- **Castle code (for imperial)**: reuse `RoomStack.StackRole.OUTER_WALL`
  and associated piece system for cities.

## Behavior contract

### Does

- Plan walls at compose time, with tower/gate slot reservations.
- Realize walls after building placement with concrete alignment.
- Support multiple cultures with distinct kits.
- Follow terrain features; omit walls on natural barriers.

### Does not

- Dynamically update when the village grows (village expansion is
  post-generation and does not trigger wall updates).
- Defend the village mechanically (guards defend; wall is cover).
- Produce damage on mob contact or breach events. Walls are static
  blocks.

## Edge cases

- **Wall polyline crosses a building that was placed outside the
  projected hull.** The realizer either (a) routes around the
  building, adding a bend, or (b) terminates the wall segment at
  the building's outer face. Prefer (a) unless the detour is large.
- **Gate position doesn't have a road passing through.** Rare — the
  algorithm chose gates from existing road exits. If it happens
  (road was dropped), gate becomes a sealed archway (non-passable
  but visually a gate).
- **Tower slot taken by a non-defensive building (matcher fallback).**
  The tower is omitted; the wall passes through normally. Log
  warning.
- **Village has water on three sides (island).** Wall exists only on
  the landward side. Ends with END_CAP pieces where it meets water.
- **Walled village with very few buildings (ENCLAVE hamlet).** Use
  the smallest wall kit pieces; wall perimeter may be only 20 blocks.

## Ordering dependencies

- Requires zoning rework complete (layouts 2–16 in slot/matcher model).
- Runs after building placement AND after foundation placement.
- Runs before the uniform DecorationSlotEmitter so the emitter can
  skip slots inside the wall perimeter (no decoration on wall tops).

## Open decisions

- **Wall height / thickness defaults.** Proposed: thickness 2 blocks
  for palisade, 3 blocks for stone, 4 blocks for imperial. Height
  6 blocks for palisade, 8 for stone, 10 for imperial. Confirm by
  test.
- **Buffer between buildings and wall.** Proposed: 8 blocks buffer;
  adjusted by culture (nordic earthen rampart takes wider buffer).
- **Concave hull algorithm.** Proposed: Alpha-shape with a tuned alpha
  parameter. Simpler alternatives (convex hull + inward-pull) could
  work but leave unused space inside.
- **Imperial wall using castle code.** Clarify: reuse the existing
  `RoomStack` for city-tier only; smaller tiers use lightweight kits.
- **Ruination scope.** Defer to post-v1 work driven by NPC Phase 4
  village history.

## Does-not-include

- Wall upgrade over time as village prospers.
- Siege mechanics (breaches, rebuild after attack).
- Player-built walls integrating with procedural walls.
- Moats (water feature outside walls). Could be added later.
- Wall decoration (banners, shields) — polish pass.

## Revision notes

(Changes recorded here as the spec evolves.)
