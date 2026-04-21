---
name: litv-building-profile
description: >
  Registers a new BuildingType in the Life in the Village Minecraft mod's
  placement and inhabitant systems. Use this skill whenever the user adds a
  new BuildingType enum value and needs to wire it into BuildingProfileRegistry
  (slot preference tiers for the placement matcher) and BuildingInhabitantRegistry
  (NPC spawning). Always use this skill before free-styling registry entries —
  wrong slot tags or anchor policies cause silent placement failures that are
  hard to diagnose. Output is two code blocks: one entry for BuildingProfileRegistry
  and one for BuildingInhabitantRegistry, both ready to paste into registerDefaults().
---

# Life in the Village — Building Profile Skill

## Step 0 — Gather Required Inputs

| # | Input | Notes |
|---|-------|-------|
| 1 | **BuildingType** | The enum value being registered |
| 2 | **Zone / role** | Civic, production, residential, agricultural, defensive, or specialty |
| 3 | **AnchorPolicy** | `CORE` (must place, logged if it fails) or `FILLER` (silently skips) |
| 4 | **Slot preference tiers** | Ordered list of preferred SlotTags, best first |
| 5 | **Avoidance** | Should same-type buildings avoid each other? If so, minimum distance in blocks |
| 6 | **Inhabitants** | Who lives/works here? Profession, resident vs. worker, family structure |
| 7 | **Adjacency constraint** | Does this building require a terrain feature nearby? (water, river, forest, coast) |

---

## Step 1 — BuildingProfileRegistry entry

**File:** `src/main/java/tterrag1112/life_in_the_village/Village/Planning/Zoning/BuildingProfileRegistry.java`

Add inside the `static { }` block in `registerDefaults()`:

```java
put(BuildingType.<TYPE>, AnchorPolicy.<POLICY>, <avoidance>,
        tier(W_PRIMARY,   SlotTag.<BEST_TAG>),
        tier(W_SECONDARY, SlotTag.<NEXT_TAG>),
        tier(W_TERTIARY,  SlotTag.<NEXT_TAG>),   // omit if not needed
        tier(W_FALLBACK,  SlotTag.ROAD_ADJACENT));
```

See `references/api.md` for weight constants, all SlotTag values with guidance,
helper methods (`production()`, `landmarkCivic()`), and AvoidanceRule patterns.

---

## Step 2 — BuildingInhabitantRegistry entry

**File:** `src/main/java/tterrag1112/life_in_the_village/Village/Buildings/Inhabitants/BuildingInhabitantRegistry.java`

Add inside `registerDefaults()`:

```java
register(BuildingType.<TYPE>, BuildingInhabitantSpec.builder()
        .<inhabitant calls>
        .build());
```

If the building also has a terrain adjacency requirement, add after the `register()` call:

```java
registerAdjacency(BuildingType.<TYPE>,
        BuildingAdjacencySpec.builder()
                .<requires/prefers call>
                .build());
```

See `references/api.md` for `BuildingInhabitantSpec` builder methods and
`BuildingAdjacencySpec` patterns.

---

## Step 3 — Self-check before presenting

- [ ] `AnchorPolicy.CORE` used only for buildings that must place (civic, key production, defensive)
- [ ] `AnchorPolicy.FILLER` used for houses, wells, decorative buildings
- [ ] Tier list ends with `tier(W_FALLBACK, ROAD_ADJACENT)` or `tier(W_BACKFILL, BACKFILL)`
- [ ] Water-dependent buildings (DOCKS, FISHERY, MILLER) have `SHORE`/`RIVER_BANK`/`PIER_ADJACENT` as primary tier
- [ ] `AvoidanceRule.sameTypeMin(N)` set for towers/guards that need spacing
- [ ] At least one inhabitant registered (use `BuildingInhabitantSpec.EMPTY` only if building is purely decorative)
- [ ] Adjacency registered for any building with a hard terrain requirement

Present both code blocks inline, clearly labelled by which file/method they go in.
