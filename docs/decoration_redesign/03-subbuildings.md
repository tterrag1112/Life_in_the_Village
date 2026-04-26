# 03 — Subbuildings

## Purpose

Formalize the "logical region inside a parent building" pattern that
MarketStallPlacer already uses ad-hoc. A SubBuilding is a named region
within a larger building that behaves as its own logical unit — its own
inhabitants, its own economic context, its own target for NPC pathing.

Use cases:
- **Inn rooms** — rentable guest rooms for visitors and players
- **Shop-house apartments** — multiple households in one urban building
- **Shop-house shops** — ground-floor commerce + upper-floor living
- **Archives** — scholar workspace inside a guild hall or town hall
- **Chapel rooms** — small shrines inside a larger temple
- **Inn kitchen / cellar** — dedicated regions for production goals

Market stalls migrate onto this framework as a special case.

## Design

### Detection by anchor blocks

Building NBTs are authored with special anchor blocks marking subbuilding
regions. The scanner sweeps the building's footprint at placement time,
finds anchor blocks, resolves each one to a subbuilding, and registers
the result.

Two anchor block types:

**Primary anchor** — marks the origin of a subbuilding. A distinct block
for each SubBuildingType so one sweep resolves them all:

```
CHISELED_STONE_BRICKS  → STALL           (existing convention, keep)
CHISELED_DEEPSLATE     → APARTMENT
CHISELED_QUARTZ_BLOCK  → SHOP
CHISELED_NETHER_BRICKS → ARCHIVE
CHISELED_POLISHED_BB   → INN_ROOM
CHISELED_TUFF_BRICKS   → WORKSHOP
CHISELED_RED_SANDSTONE → CHAPEL_ROOM
CHISELED_SANDSTONE     → CELLAR
```

Anchor blocks are replaced with air on first scan so they don't persist
in the final structure.

**Corner markers (optional)** — wool blocks at each corner of the
subbuilding region to give explicit bounds. If corners aren't present,
the scanner infers bounds via connected-room flood-fill from the anchor,
stopping at walls. Corner markers are preferred for deterministic bounds.

### Door target

Each subbuilding needs a registered entrance for NPC pathing. The scanner
looks for a door block (any DoorBlock) along the region's exterior
walls and records its BlockPos. NPCs path to this door; in-building
wander logic remains the existing building-wide pattern.

If no door is found (open-concept regions like market stalls), the
door target is the anchor block's position itself.

### Registry

SubBuildings live on `VillageSavedData`:

```
Map<UUID, SubBuilding> subBuildings
Map<UUID, List<UUID>>  subBuildingsByParent  // denormalized index
```

Accessors: `addSubBuilding`, `getSubBuildingsForBuilding(UUID)`,
`getSubBuildingById(UUID)`, `getSubBuildingsOfType(BuildingType)`.

## Data structures

```java
public record SubBuilding(
    UUID subBuildingId,
    UUID parentBuildingId,
    SubBuildingType type,
    BlockPos origin,            // anchor block position
    BlockPos min,               // bounding box min
    BlockPos max,               // bounding box max
    BlockPos doorTarget,        // NPC pathing target
    Direction doorFacing,       // outward normal of the door
    List<UUID> inhabitants,     // household members, scholar, guests, etc.
    long createdTick
) {}

public enum SubBuildingType {
    STALL,          // market stall (migrates from MarketStallPlacer)
    APARTMENT,      // a single household's living space
    SHOP,           // a commercial space within a larger building
    ARCHIVE,        // scholar workspace
    INN_ROOM,       // rentable room for a visitor or player
    WORKSHOP,       // production space (larger than a stall)
    CHAPEL_ROOM,    // small shrine inside a temple
    CELLAR          // storage subregion, contributes to inventory cap
}
```

## Integration points

- **Scanner hook**: runs inside `BuildingPlacer` immediately after NBT
  stamp, before any NPC spawns for that building.
- **MarketStallPlacer migration**: existing market-stall code calls into
  `SubBuildingScanner` for STALL type; the MarketStall record gains
  a `subBuildingId` field linking it to the scanned region.
- **Visitor flux (NPC Phase 4)**: visitor assignment targets an
  INN_ROOM subbuilding; the NPC's `assignedBuildingId` is the parent,
  and a new `assignedSubBuildingId` field is added to TownspersonMob.
- **Scholar profession (NPC Phase 2 doc 17)**: scholar NPCs spawn with
  `assignedSubBuildingId` pointing to the ARCHIVE they work in.
- **Households (existing)**: an APARTMENT subbuilding gets its own
  HouseholdData record instead of sharing the parent building's.
  HouseholdManager gains a "household owns subbuilding, not building"
  path.
- **Company (NPC Phase 4 doc 26)**: a SHOP subbuilding can be the
  workplace of an NPC-owned Company's workers.

## Behavior contract

### Does

- Detect subbuildings deterministically at building placement time.
- Provide door-based pathing targets for NPC goals.
- Register inhabitants per subbuilding, not just per building.
- Persist across saves with stable UUIDs.

### Does not

- Re-scan buildings at runtime. Scan once at placement; subsequent
  building changes (condition, upgrades) don't re-derive subbuildings.
- Interact with vanilla jigsaw blocks. Anchor blocks are mod-specific.
- Support overlapping subbuildings. Each anchor owns a disjoint region.
- Pathfind to arbitrary positions inside a subbuilding. Door-target
  only; in-building wander handles the rest.

## Edge cases

- **NBT without any anchor blocks.** Scanner returns empty list; the
  building has zero subbuildings. Fine.
- **Anchor block without a nearby door.** Scanner logs a warning;
  `doorTarget` falls back to the anchor position. Works but NPC will
  path to the anchor rather than a door.
- **Corner markers misplaced (outside the actual room).** Scanner uses
  markers as hint bounds only; if a marker is in an obviously-invalid
  location (inside a wall, underground), flood-fill bounds are used
  instead and a warning is logged.
- **Upgrade/rebuild of parent building.** Existing subbuildings are
  discarded and re-scanned. Inhabitants in the OLD subbuilding list are
  migrated best-effort (same type + same region → same subbuilding).
- **Parent building partial destruction.** If the parent loses its
  roof/walls enough that the scan produces different bounds, cleanup
  is manual via `/liv subbuilding rescan <building>`.

## Ordering dependencies

- `BuildingPlacer` must be able to call the scanner inline. No
  changes to `BuildingPlacer`'s public signature.
- `VillageSavedData` codec must be extended — non-breaking addition
  via `optionalFieldOf` default (empty map).
- `TownspersonMob` adds `assignedSubBuildingId` field, non-breaking
  (nullable + default null).

## Open decisions

- **Anchor palette rot.** The chiseled-block anchor scheme risks
  collisions with player placement of the same blocks. Mitigation:
  scan only at NBT stamp time (not runtime), so player-placed
  chiseled blocks never trigger. Confirmed acceptable.
- **CELLAR underground detection.** Do we require CELLAR anchors to be
  below the building's ground floor? Proposed: yes, enforce via Y
  check against the building's floor plane.
- **Workshop vs shop distinction.** Proposed: SHOP = customer-facing,
  WORKSHOP = production-only. Both allowed in the same building
  (upstairs workshop, downstairs shop).
- **Migration tool for existing market stalls.** Proposed: a one-time
  `/liv subbuilding migrate-stalls` command that converts persisted
  MarketStall records to SubBuilding + MarketStall pairs. Confirm
  whether this is acceptable disruption vs. writing a silent codec
  migration.

## Does-not-include

- Jigsaw-block integration. Explicitly using anchor blocks instead.
- Dynamic subbuilding spawning mid-game (e.g., player adds a room).
  Subbuildings are fixed at placement time only.
- Multi-parent subbuildings (a room that spans two buildings).
  One parent, one subbuilding.

## Revision notes

(Changes recorded here as the spec evolves.)
