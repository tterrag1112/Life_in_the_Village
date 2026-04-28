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

### Detection by anchor block entities

Building NBTs are authored with a single mod-defined **anchor block**
(`life_in_the_village:subbuilding_anchor`) at every subbuilding origin.
The block carries a block entity (`SubBuildingAnchorBlockEntity`) that
records the subbuilding's `SubBuildingType` plus optional overrides
(explicit bounds, explicit door target, free-form hints map).

The scanner sweeps the parent building's footprint at placement time,
collects every block entity that is a `SubBuildingAnchorBlockEntity`,
resolves each one to a subbuilding, and registers the result.

A single block type (rather than one block per `SubBuildingType`) means:

1. Authors only need one block in their palette.
2. New `SubBuildingType` values never require a new block.
3. There's no chance of a vanilla decorative block being mistaken for
   an anchor — only the mod block triggers the scan.

Anchor blocks (and their block entities) are replaced with air on first
scan so they don't persist in the final structure.

**Bounds resolution.** If the block entity carries an `explicitBounds`
override, that wins. Otherwise the scanner runs a connected-room
flood-fill from the anchor position, walking air + transparent blocks
until it hits walls or the parent footprint edge. Flood-fill is capped
at 256 cubic blocks per anchor to bound runaway scans on accidentally
under-walled rooms.

### Door target

Each subbuilding needs a registered entrance for NPC pathing. If the
anchor block entity carries an `explicitDoorTarget` override, that
position is used. Otherwise the scanner looks for any `DoorBlock`
within or along the region's exterior walls and uses the closest one.
`doorFacing` is the outward normal of that door (the side away from
the room). NPCs path to this door; in-building wander logic remains
the existing building-wide pattern.

If no door is found (open-concept regions like market stalls, or the
author forgot one) the door target is the anchor position and
`doorFacing` falls back to the parent building's front-face direction.
Logged once per (subBuildingType, parent) at INFO.

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

- **Anchor palette rot.** Resolved by the block-entity approach (see
  §"Detection by anchor block entities"). One mod block, no palette
  collisions. The original chiseled-palette scheme is retained in the
  revision notes for context.
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

### P0d-01 / P0d-02 / P0d-03 / P0d-05 — framework data model + scanner landed

- **Changed from chiseled-block palette to block-entity approach.**
  The original spec used 8 distinct vanilla chiseled blocks (one per
  `SubBuildingType`) as anchors. That scheme had two problems: vanilla
  decorative chiseled blocks could be mistaken for anchors when authors
  legitimately wanted those blocks in their palette, and adding a new
  `SubBuildingType` required a new vanilla-block mapping. Replaced with
  a single mod-defined block (`life_in_the_village:subbuilding_anchor`)
  carrying a block entity that records the type and optional overrides.
  Removes conflict risk with decorative chiseled blocks; allows authors
  to specify explicit bounds, door target, or hints per anchor.
- **Authoring workflow.** For Phase 0d v1, authors place the anchor
  block in a structure and then edit the structure's NBT directly (or
  via an external tool) to set the block entity's `subBuildingType`
  and any overrides. A configurator item / GUI is deferred polish.
  Documented in `00-conventions.md` §"Authoring workflow for
  subbuilding anchors".
- **Scanner pipeline order.** Runs inside `BuildingPlacer.placeAndRegister`
  immediately after `template.placeInWorld(...)` returns and BEFORE
  `TintPass.apply` and `applyBiomeSwap`. Anchor blocks are replaced
  with air during the scan so they never reach the tint or biome
  passes.
- **Determinism.** Block-entity iteration order in chunks is not
  guaranteed stable. The scanner sorts collected anchors by their
  `BlockPos` (Y, then Z, then X) before processing, so subbuilding
  UUIDs derive deterministically and registry order is stable across
  re-runs.
- **MarketStallPlacer migration deferred.** P0d-04 was scoped here
  but the existing market stall lifecycle is fundamentally
  runtime-claim (chiseled-stone-brick anchors are consumed by
  `claimSlot()` lazily, not scanned at building placement) and
  `MarketStall` is a mutable class rather than a record. Migration
  requires either re-authoring binary market NBTs to use the new
  anchor block or building a parallel placement-time stall registry.
  Split into its own follow-on prompt; framework lands first.
