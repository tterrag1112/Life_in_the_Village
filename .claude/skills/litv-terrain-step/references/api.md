# Terrain Step — API Reference

## Contract

Steps run **after** planning is complete. All slot positions are final.

**Allowed:**
- `level.setBlock(pos, state, 18)` — place/remove blocks
- `level.getBlockState(pos)` — read blocks
- `level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)` — surface Y
- `slot.getPadY()` / `slot.snapY(y)` — read/commit pad Y after levelling
- `slot.getFootprintWidth()` / `slot.getFootprintHeight()` — read footprint
- `profile.*` — read terrain analysis

**Not allowed:**
- `layout.tryAdd()`, `layout.addRoad()`, or any layout structural mutation
- Modifying slot positions, types, or rotations
- Calling `pctx.*` (PlanContext is gone by this stage)

---

## Slot Iteration

```java
for (LayoutSlot slot : layout.buildings()) {
    int w = slot.getFootprintWidth();
    int l = slot.getFootprintLength();
    if (w == 0 || l == 0) continue;      // skip unresolved slots

    BlockPos centre = slot.getPos();
    int padY = slot.getPadY();
    if (padY == 0) padY = centre.getY(); // fallback if not set by planner

    int minX = centre.getX() - w / 2;
    int maxX = centre.getX() + w / 2;
    int minZ = centre.getZ() - l / 2;
    int maxZ = centre.getZ() + l / 2;

    // operate on [minX..maxX] × [minZ..maxZ]

    // If you physically level the pad, commit the new Y:
    slot.snapY(newPadY);
}
```

## Block Placement

```java
// Flag 18 = UPDATE_CLIENTS | BLOCK_UPDATE — standard for world gen
level.setBlock(pos, state, 18);

// Air
level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);

// Use VillageBiomeStyle for palette-aware blocks:
BlockState surface  = style.surfaceBlock();    // grass / sand / etc.
BlockState fill     = style.fillBlock();       // dirt / sandstone / etc.
BlockState stone    = style.stoneBlock();      // stone / sandstone
BlockState wall     = style.wallBlock();       // cobblestone / stone brick
BlockState slab     = style.slabBlock();       // appropriate slab
```

## Surface Y

```java
int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
```

`MOTION_BLOCKING_NO_LEAVES` ignores leaves and returns the solid surface —
use this for all terrain prep work.

## VillageBiomeStyle Palette

| Method | Typical block |
|--------|--------------|
| `style.surfaceBlock()` | GRASS_BLOCK, SAND, SNOW_BLOCK… |
| `style.fillBlock()` | DIRT, SANDSTONE, PACKED_ICE… |
| `style.stoneBlock()` | STONE, SANDSTONE, STONE… |
| `style.wallBlock()` | COBBLESTONE, STONE_BRICKS, SANDSTONE… |
| `style.slabBlock()` | COBBLESTONE_SLAB, STONE_BRICK_SLAB… |
| `style.logBlock()` | OAK_LOG, ACACIA_LOG… |
| `style.plankBlock()` | OAK_PLANKS, ACACIA_PLANKS… |

Always use `style.*` instead of hardcoded block types so steps work
across all biomes.

## TerrainProfile Fields

```java
profile.origin()           // BlockPos village origin
profile.baseY()            // int base surface Y at origin
profile.maxY()             // int highest point in sample
profile.flatRatio()        // 0-1
profile.waterRatio()       // 0-1
profile.steepRatio()       // 0-1
profile.hasSlope()         // boolean
profile.slopeDir()         // FlatDirection or null
profile.waterBody()        // WaterBodyInfo or null
profile.ridges()           // List<RidgeInfo>
profile.flatCandidates()   // List<BlockPos>
```

## Existing Steps — Summary

| Step class | What it does | Used by |
|------------|-------------|---------|
| `ClearTreesStep` | Flood-fill removes trees and surface plants in village radius | All strategies |
| `FillHolesStep` | Fills 1-3 block sub-surface holes under building footprints | FLAT, SLOPE_AWARE, WATERFRONT |
| `LightSmoothStep` | Gentle cardinal-neighbour height averaging, ±1 block max | FLAT only |
| `LevelBuildingPadsStep(false)` | Levels pads with ramp margin, no retaining walls | FLAT |
| `LevelBuildingPadsStep(true)` | Levels pads sharp-edged for retaining wall placement | SLOPE_AWARE, MOUNTAIN, WATERFRONT |
| `RetainingWallStep` | Places retaining walls along uphill pad edges | SLOPE_AWARE, MOUNTAIN, WATERFRONT |
| `FoundationStep` | Places foundation columns on downhill pad edges | SLOPE_AWARE, MOUNTAIN, WATERFRONT |
| `DetectShorelineStep` | Records shoreline position to layout for later use | WATERFRONT |

## Step Ordering Guidelines

```
1. ClearTreesStep          — always first (clears sight lines for subsequent steps)
2. FillHolesStep           — before levelling (fills drops that would appear in pads)
3. LightSmoothStep         — flat terrain only, before levelling
4. DetectShorelineStep     — before levelling (records shoreline before it changes)
5. Custom pre-levelling    — any step that needs natural terrain intact
6. LevelBuildingPadsStep   — always comes after terrain reads
7. RetainingWallStep       — after pads are levelled
8. FoundationStep          — after retaining walls
9. Custom post-levelling   — bridge-building, water-draining, etc.
```

Custom steps that **read** the natural terrain go before step 6.
Custom steps that **respond** to the levelled terrain go after step 6.
