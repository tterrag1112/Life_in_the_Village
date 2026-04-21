---
name: litv-village-type-datagen
description: >
  Adds a new village type to the Life in the Village Minecraft mod using
  the VillageTypeBuilder datagen API. Use this skill whenever the user
  wants to create a new village type, add a new settlement variant, define
  a new building combination for a village, or wire a new ShapeType into a
  spawnable village. Output is a new private builder method in
  VillageTypeDatagen.java plus the one-line futures.add() call in run().
  Always use this skill before free-styling village type code.
---

# Life in the Village — Village Type Datagen Skill

## Step 0 — Gather Required Inputs

Do NOT write any code until all items are confirmed.

| # | Input | Notes |
|---|-------|-------|
| 1 | **Type ID** | Snake_case string, e.g. `"desert_trading_post"` — must be unique |
| 2 | **Culture** | Culture string, e.g. `"default"` |
| 3 | **ShapeType** | One value from `VillageTypeData.ShapeType` enum |
| 4 | **TerrainStrategy** | `FLAT`, `SLOPE_AWARE`, `MOUNTAIN`, or `WATERFRONT` |
| 5 | **Town square capacity** | Int 2–8; higher = more civic buildings around the plaza |
| 6 | **Walled** | `true` / `false` |
| 7 | **Building list** | For each building: `BuildingType`, structure path, and count (exact, range, or single) |
| 8 | **Category** | Which section in `run()` does it belong to? (flat-terrain / slope-forest / mountain / water) |

---

## Step 1 — Write the Builder Method

**File:** `src/main/java/tterrag1112/life_in_the_village/Datagen/VillageTypeDatagen.java`

Add a new `private JsonObject build<Name>()` method in the appropriate section.

```java
/** <ShapeType> — <one-line description of the village concept>. */
private JsonObject build<Name>() {
    return VillageTypeBuilder.create("<type_id>", "<culture>")
            .shape(ShapeType.<SHAPE>)
            .terrainStrategy(TerrainStrategy.<STRATEGY>)
            .townSquareCapacity(<N>)
            // .walled(true)            ← include only if true
            // .forcedAxis(true)        ← include only if needed
            // .maxRings(<N>)           ← include only if non-default
            // .streetDensity(<f>)      ← include only if non-default
            .building(BuildingType.TOWN_HALL,  "town_hall/level_1")
            /* ... other buildings ... */
            .build();
}
```

See `references/api.md` for the full `VillageTypeBuilder` method list, all valid `ShapeType` and `TerrainStrategy` values, `BuildingType` catalogue, and guidance on choosing capacity and building counts.

---

## Step 2 — Register in run()

Add one line to the appropriate section in `run()`:

```java
futures.add(saveVillageType(cache, build<Name>()));
```

Sections in `run()`:
- `// ── Flat-terrain villages ──`
- `// ── Slope/forest villages ──`
- `// ── Mountain villages ──`
- `// ── Water villages ──`

---

## Step 3 — Self-check before presenting

- [ ] Type ID is unique (check existing `buildX()` methods)
- [ ] `TOWN_HALL` is always the first building entry
- [ ] Building structure paths follow the `"type/level_1"` convention
- [ ] `walled(true)` is only set for defensive / fortified village types
- [ ] Water-dependent buildings (DOCKS, FISHERY, MILLER, PIER) are only used with `WATERFRONT` strategy
- [ ] `MOUNTAIN` strategy is used for hilltop / terraced / cliff types; `FLAT` for plains

Present the complete builder method inline, followed by the `futures.add()` line.
