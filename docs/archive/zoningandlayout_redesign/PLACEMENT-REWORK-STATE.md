# Village Placement Rework — State, Plan, and Architectural Direction

**Purpose of this document.** This file captures the state of the
Village Placement Rework, the corrected list of remaining work, and
the architectural decision deferred until empirical data is available.
It is intentionally self-contained so a future conversation (or a
compressed version of the current one) can recover the full picture
without searching prior transcripts.

If anything in here conflicts with later decisions, the later
decisions win — but this document should be updated rather than left
to drift.

**Critical: source-of-truth note.** Claude.ai's `project_knowledge_search`
tool returns code that is **stale** relative to the live filesystem
that Claude Code edits. The project knowledge was uploaded once, at
some earlier point, and has not been refreshed as Claude Code has
landed changes. As of this revision, project knowledge does not
reflect the doc-04 polygon migration, the microfix batch, Phase 16b,
or Phase 17. **Claude Code's grep / view of the actual files is
authoritative.** When this document and project knowledge disagree
about what code looks like, this document is closer to truth, but
both can be wrong. Verify by asking Claude Code to read the file.

---

## 1. Scope of the rework

The Village Placement Rework develops the **underlying systems** that
village layouts run on top of: the road graph data model, the feature
map, sectors, growth policies, the matcher, the truncation pipeline,
the layout-plan handoff, and the spawner/decorator wiring.

The rework explicitly **does not** include rewriting individual recipe
authoring (RADIAL, LINEAR, PLAZA, etc. as authored aesthetic intents).
Recipe authoring is a separate creative pass that happens *on* the
finished system, not as part of building it. RADIAL stayed as the
canonical proof during the rework. LINEAR receives a minimal-viable
conversion only when a non-radial test target is required by Phase 17.

Recipe authoring is post-rework work and may itself be made obsolete
by an architectural shift (see Section 5). That shift is also out of
scope for the rework itself; the rework lands the infrastructure that
makes the shift possible later.

## 2. The original 23 phases

The plan was authored in three documents (`00-OVERVIEW`,
`01-ABSTRACTIONS`, `02-PHASES`) that may or may not still exist as
files. The 23 phases were:

| # | Phase | Status |
|---|---|---|
| 1 | RoadGraph data model + VillageLayout migration | landed |
| 2 | Layout debug visualizer + `show_graph` | landed |
| 3 | FeatureMap (planning-pass only) | landed |
| 4 | FeatureMap determinism + two-pass refine | landed |
| 5 | `show_features` / `show_hull` debug | landed |
| 6 | Sector record + GrowthPolicy strategy interface | landed |
| 7 | BaseRecipe abstract (3-step lifecycle) | landed |
| 8 | Convert RADIAL to BaseRecipe + sectors | landed |
| 9 | `show_sectors` debug | landed |
| 10 | Growth integration into matcher | landed |
| 11 | Convert PLAZA / LINEAR / CLUSTERED / ROADSIDE | **dropped from rework** |
| 12 | Convert RIVERINE + Bridge primitive | **see note below** |
| 13 | Convert HILLTOP + Stairway primitive | **see note below** |
| 14 | Convert TERRACED + Causeway primitive | **see note below** |
| 15 | Convert remaining 9 recipes | **dropped from rework** |
| 16 | RoadPrimitive signature change + truncation | landed (plumbing only) |
| 17 | Farm plot sector integration | landed |
| 18 | Plaza polygon ownership consolidation | landed |
| 19 | LayoutPlan + AnchorKind + spawner/decorator wiring | landed |
| 20 | BuildSiteFinder migration to graph + feature queries | landed (incl. Phase 20a LayoutPlan persistence) |
| 21 | VillageTypeData kingdom-rework schema additions | landed |
| 22 | Recipe fallback chains | landed |
| 23 | 90% success measurement + persistent-failure polish | **harness landed (23.1); measurement + polish pending (23.2)** |
| 16b | Truncation reaction + general re-emit pattern | **landed** |
| 17 | Farm plot sector integration | **landed** |
| 18 | Plaza polygon ownership consolidation (Path B framing) | pending — see Section 4 |
| 19 | LayoutPlan + AnchorKind + spawner/decorator wiring | pending |
| 20 | BuildSiteFinder migration to graph + feature queries | pending |
| 21 | VillageTypeData kingdom-rework schema additions | pending |
| 22 | Recipe fallback chains | pending |
| 23 | 90% success measurement + persistent-failure polish | pending |

**Note on Phases 12, 13, 14.** These bundled recipe conversions with
*new road primitives* (Bridge, Stairway, Causeway). The conversions
drop with the rest of the recipe work, but the **primitives stay** —
they are system-level capabilities any recipe can use. They land
decoupled from any recipe converting to use them. Causeway is
optional and may be skipped entirely if Bridge + Stairway prove
sufficient.

**Note on doc-04 polygon migration.** Outside the formal phase
sequence, a separate effort ("Phase 18 doc 04") landed and absorbed
part of the original Phase 18 scope. It deleted `LayoutPrimitive.TownSquare`
entirely and replaced it with a polygon-based plaza model
(`PlazaRegion` + `PlazaGenerator` + `PlazaPaver` +
`RecipeHelpers.installPlaza`). The civic ring road was also deleted
(the prompt-15 audit confirmed no NPC / trade / pathing dependency).
The remaining Phase 18 work is now polygon-aware consolidation, not
TownSquare collapse — see Section 4.

## 3. Truncation reaction (Phase 16 follow-up) — LANDED

Phase 16 landed the plumbing: `RoadPrimitive.computeCenterline` returns
a `RoadResult` with `isComplete()` and a `TerminationReason` (one of
`CLIFF_RISE`, `CLIFF_DROP`, `WATER_CROSSING`, plus `COMPLETE`).

Phase 16b landed the consumer side:

- Recipes capture `RoadResult` per primitive call.
- Slot emission clamps to the actual reachable road via
  `clampToRoadReach` post-emission helper.
- A severe-truncation cascade pivots the recipe (rotate axis once,
  then fall back, then abort) when the primary spine is below a
  viability threshold.
- The cascade is implemented as a general re-emit engine
  (`BaseRecipe.reEmit(reason)`) that future constraint violations
  plug into.

Renames that landed (per Section 8):
- `SpineViability` → `RecipeStatus`
- `checkSpineViability` → `checkPrimarySpine`
- `spineTruncationCount` → `truncationCount`
- `spinePivotCount` → `cascadeRetryCount`

Per-village summary line is printed in `VillagePlanner` after the
matcher returns:
`shape=X status=Y spineRatio=R spineLen=A/B truncations=N retries=M finalShape=F validated=A/B`

The framing as the **first specific case of a general re-emit
engine** is preserved in code. `ReEmitReason` is a sealed interface
permitting `SevereTruncation`, `SlotsDropped`, `SectorStarved`. Only
`SevereTruncation` is consumed today; the other two are scaffolding
for Phase 22 and the post-rework architectural-shift experiment.

This framing is non-negotiable. It is what makes Phase 22 cheap and
what positions the codebase for the architectural choice in Section
5 without committing to either path.

## 4. Pending work, in execution order

The original list undercounted because it lumped recipe conversions
and primitives together and because constraint-propagation emerged
as an extra item. The current ordered list is:

1. **Phase 18 — Plaza polygon ownership consolidation (Path B
   framing).** TownSquare and the civic ring road were deleted in
   the doc-04 migration; the polygon model (PlazaRegion +
   PlazaGenerator + PlazaPaver + installPlaza) replaced them.
   Phase 18's remaining scope is polygon-aware consolidation:
   introduce a `Plaza` record/class that wraps PlazaRegion plus the
   legacy `townSquarePos` / `townSquareRadius` / `civicRingRadius`
   fields; add `getPlaza()` (singular) plus `getPlazas()` (plural);
   move civic slot emission off the flat pool by having
   `PlazaGenerator.emitPlazaCivicSlots` return slots directly to
   `installPlaza` for registration on the Plaza; add a civic-first
   claim path in PlacementMatcher that reads `Plaza.civicSlots()`
   first, falls back to flat pool. Plan dump gains a `--- PLAZA ---`
   section showing polygon centre, area, vertices, civic slot
   count. Out of scope: don't recreate TownSquare, don't recreate
   the ring road, don't change PlazaGenerator's polygon math, don't
   touch HAMLET vs VILLAGE+ branching in installPlaza.

2. **Phase 19 — LayoutPlan + AnchorKind + spawner/decorator wiring.**
   The immutable plan-as-contract handoff. Spawner and decorator
   currently consume the mutable `VillageLayout`. This is the
   load-bearing phase that makes constraint-propagation natural and
   makes any future architectural shift possible. Do not skip.

3. **Phase 20 — BuildSiteFinder migration.** Expansion currently uses
   spiral search on raw block positions. Move it onto the new graph
   and feature-map APIs so expansion participates in the same system
   as initial planning.

4. **Phase 22 — Recipe fallback chains.** Schema-level. Village types
   declare their fallback chain. The cascade in Phase 16b consumes
   this. Generalize the re-emit engine here.

5. **Phase 21 — Kingdom-rework schema additions.** Schema-only;
   small. Can land anytime after Phase 19.

6. **LINEAR recipe authoring rework.** Post-rework backlog item, not
   in the rework's exit criteria. The microfix batch's Fix 2
   tightened LINEAR's slot footprint budget correctly, which exposed
   that LINEAR's recipe authoring is fundamentally broken: civic
   slots overlap the spine reservation, footprint sizes mismatch
   buildings in two dimensions, and `linear_civic` cap=3 caps
   capacity below TOWN_HALL+INN+GUILD_HALL+MARKET requirements.
   LINEAR places 1/27 buildings on superflat. Result: **the
   Phase 16b cascade fallback chain is now RADIAL → ABORT**, not
   RADIAL → LINEAR → ABORT. Reverting requires fixing LINEAR
   authoring first.

7. **Bridge / Stairway / (optional) Causeway primitives.** Decoupled
   from recipe conversion. The primitives ship; recipe authoring
   uses them later.

8. **Phase 23 — 90% success measurement + polish.** The rework's
   exit criterion. Spawn many villages on varied terrain, measure
   the success rate, fix the patterns that surface.

After Phase 23, the rework is done. The architectural-shift
experiment in Section 5 happens *after* this point, on the finished
system.

## 5. The architectural question (deferred decision)

The current architecture is **roads-first**: a recipe lays down road
geometry, slots emit relative to roads, the matcher binds buildings
to slots. Every failure observed during this rework
(GUARD_TOWER 15 blocks from its feeding road, FARMHOUSE on the wrong
side of a truncated arc, WEAVER perturbed past the validator slack)
shares a root cause: the road network commits before knowing whether
the buildings it's planning for will fit.

Three plausible alternatives have been named.

### Path 1 — Constraint-propagation A

Recipes stay as authored aesthetic intents. Each recipe gains the
ability to re-run with adjusted parameters when downstream emission
rejects its plan. The `LayoutPlan` is tentative; sectors report back
"I lost too many slots"; recipes mutate (rotate axis, change radius,
pick a fallback) and re-emit. After N iterations, either the plan
converges or the recipe gives up and falls back to a simpler recipe
in its declared chain.

Trade-off: keeps authored vocabulary and is debuggable; doesn't
fundamentally change the road-first authority problem, only mitigates
it through retries.

### Path 2 — Strategy composition

Delete the recipes-as-shapes concept. A village type picks a
*terrain-response policy* (hill-aware, river-aware, coast-aware, flat)
plus a *building distribution* (civic-heavy, production-heavy,
agricultural-heavy). The planner reads the terrain at the seed point,
accumulates strategies that fire when their conditions match
(river within 40 → spine-along-river; cliff with >12 drop →
terrace; flat → ring), and assembles primitives + sectors from
whichever strategies fired. RADIAL doesn't exist as a recipe; the
planner happens to produce a radial-looking layout on flat plains
because that's what the firing strategies prescribed.

Trade-off: maximally terrain-adaptive; weakens authored
predictability (an author can't say "this village is RADIAL" — only
"this village prefers flat ground"); much harder to debug when
emergent behavior goes wrong.

### Path 3 — Mutual adjustment (the user's original Option C)

All layers (buildings, roads, decorations, terrain prep) compute in
draft form, then iteratively refine together until convergence.

Trade-off: most expressive and slowest. Convergence is not
guaranteed (two conflicting constraints can ping-pong). Highest
codebase complexity. Functionally a superset of Path 1 with weaker
hierarchy guarantees.

### What's been decided

The user characterized Path 3 as roughly equivalent to Path 1 with
explicit hierarchy added. Path 1 with constraint-propagation is the
hierarchy-respecting form of Path 3. This conversation treats
Path 3 as a continuum endpoint of Path 1 rather than a separate path.

The choice between Path 1 and Path 2 is **deferred until empirical
data is available**. Picking now would be by intuition; the
infrastructure to compare them empirically lands in Phases 16b
through 23 of the rework. After Phase 23 ships, a focused experiment
runs.

### The deferred experiment

After the rework is complete:

1. Path 1 already works (constraint-propagation is implemented as
   part of Phases 16b and 22).

2. Write `OrganicRecipe.java` as a new recipe alongside RADIAL et al.
   It emits no fixed road structure. It reads the FeatureMap, fires
   terrain-aware strategies, and emits primitives + sectors based on
   what fires. This is the Path 2 prototype. Two to four prompts of
   work.

3. Spawn 30 villages of `OrganicRecipe` across varied terrain.
   Spawn 30 of constraint-propagating-RADIAL across the same seeds.
   Compare: failure rates, visual quality, terrain integration,
   weirdness factor, debuggability.

4. Decide on the data:
   - If Path 2 wins, migrate other recipes as strategies. Named
     overrides (capitals, landmarks) stay as authored recipes.
   - If Path 1 wins, polish constraint-propagation across all
     recipes. Each recipe declares its own re-run parameter space.

The total cost over "just finish the rework on Path 1" is roughly
4-6 extra prompts for the prototype and the comparison. The
infrastructure work is identical either way.

### Why this sequencing is correct

Phases 16b through 23 are **neutral** between Path 1 and Path 2.
Both paths need: the road graph, the feature map, sectors, growth
policies, the matcher, the truncation pipeline, the layout-plan
handoff, the build-site-finder migration, the schema additions, the
fallback chains, and the success measurement. None of this work
gets thrown away in either direction.

Doing the experiment before this work lands would be premature —
the infrastructure that makes both paths viable doesn't exist yet,
so the comparison would be unfair.

## 6. Microfix batch — LANDED

The original microfix batch had three deferred items
(GUARD_TOWER `useShared`, WEAVER perturbation-clamp, FARMHOUSE
FIELD_EDGE diagnostic). Of these, the first two were superseded by
diagnostic work that surfaced different root causes. The actual
batch that landed:

- **Fix 1 — GUARD_TOWER 15 blocks off OUTER_RING.** Original
  diagnosis (RingBand `useShared` slot math) was wrong; Claude Code
  identified the actual fix. `DEFENSIVE.SAFE_OFFSET` lowered from
  16 to 12. GUARD_TOWER now lands at `chebDist=12` from the
  OUTER_RING centerline, validator passes.

- **Fix 2 — LINEAR slot footprint budget.** Tightened slot footprint
  budget so the matcher correctly rejects oversized candidates.
  Result: LINEAR now places 1/27 buildings on superflat, because
  LINEAR's recipe authoring is fundamentally broken (slots overlap
  spine reservation, sector caps mismatch building counts). This is
  *expected fallout* of the fix — the matcher is now being honest
  about the recipe's brokenness instead of corrupting placements to
  hide it. LINEAR rework is post-rework backlog. **Cascade fallback
  chain is now RADIAL → ABORT** (no LINEAR step).

- **Fix 3 — `sector=unknown` HOUSE attribution.** Spine and arc
  emissions in `RadialRecipe` are now wrapped in sectors
  (`radial_main_road`, `radial_arc_0`, `radial_arc_1`). All HOUSEs
  now report real sectors in plan dumps.

Items not addressed in the batch (deferred):

- **WEAVER perturbation-clamp.** Original concern was that matcher
  perturbation pushed WEAVER past validator slack on a 9×9
  footprint. Not yet observed in current testing; if it surfaces,
  fix is `min(slotFootprint, buildingActualFootprint + slack)` in
  matcher perturbation logic.

- **FARMHOUSE FIELD_EDGE intermittent reject diagnostic.** Original
  concern was that three candidates were offered and all rejected
  without surfacing a reason. Not yet observed in current testing.

New microfix candidate (P3 polish, post-Phase 18):

- **`validated=N/M` summary line displays wrong M.** Per-village
  summary line shows `validated=27/15` instead of `27/27` on a
  successful RADIAL run. Likely a display-only bug in the summary
  formatter, not a regression. Not blocking.

## 7. Out-of-scope items

The rework's "should this be in scope" rule is: does it dictate the
*shape* of the village layout (buildings placed, roads routed,
sectors sized)? If yes, in scope. If it merely consumes the layout,
out of scope.

Explicitly out of scope:

- Atlas-level village placement decisions (kingdoms rework).
- Inter-village political relationships (kingdoms rework).
- NPC profession assignment (its own system).
- Building inhabitant population (its own system).
- Economy and trade route logic beyond the gate-anchor seam
  (economy rework).
- Worldgen-time atlas rebuilds (atlas rework).
- Recipe authoring as creative work — the visual / aesthetic design
  of layouts. Post-rework. (LINEAR's rework is one specific instance
  in this bucket.)
- The Path 1 vs Path 2 architectural decision. Post-rework.
- Decoration content (NBT bridges, festival decor, town square
  rework, parks/gardens). Decoration rework.
- Pathfinding and road decay. Trade road rework.

## 8. Conventions and naming

These names matter because compressed context tends to drop
specifics; explicit naming protects the design.

- **`RecipeStatus`** is the enum returned from constraint checks
  (formerly proposed as `SpineViability`). Generalizes beyond
  truncation. Values: `OK`, `RETRY`, `FALLBACK`, `ABORT`. Landed.
- **`BaseRecipe.reEmit(reason)`** is the general re-emit method
  recipes override. Truncation cascade is one specific call site.
  Landed.
- **`ReEmitReason`** is a sealed interface permitting
  `SevereTruncation`, `SlotsDropped`, `SectorStarved`. Only the
  first is consumed today. Landed.
- **`RoadResult`** is the per-primitive record carrying centerline,
  `isComplete()`, `TerminationReason`, intended length, actual
  length. Recipes capture and pass these into slot emission.
  Landed. Note: the enum is `TerminationReason` (a road can
  terminate complete, too), not `TruncationReason` as originally
  proposed.
- **`LayoutPlan`** is the immutable plan record produced at end of
  planning, consumed by spawner and decorator. Replaces the
  mutable `VillageLayout` as the contract. Phase 19 introduces it.
  Pending.
- **`AnchorKind`** is the enum of named anchor positions (e.g.
  `TOWN_SQUARE`, `MAIN_GATE`, `RIVER_LANDING`) that map to graph
  node IDs on the LayoutPlan. Pending (Phase 19).
- **`Plaza`** is the planned record/class (Phase 18, Path B
  framing) that wraps `PlazaRegion` plus the legacy
  `townSquarePos` / `townSquareRadius` / `civicRingRadius`
  fields. Replaces the deleted TownSquare-as-state-owner pattern.
  Pending.

PlanContext fields added by Phase 16b:
- `cascadeRetryCount` (int)
- `cascadeAxisRotation` (double, radians, accumulated)
- `truncationCount` (int)

VillageLayout fields added by Phase 16b:
- `unplannable` (boolean)
- `unplannableReason` (String)

VillageLayout state from doc-04 migration (post-deletion):
- `townSquarePos` (BlockPos) — kept as legacy field, written by
  installPlaza on the HAMLET path and PlazaGenerator.generate on
  the VILLAGE+ path
- `townSquareRadius` (int) — same
- `civicRingRadius` (int) — same
- `getPlazaRegions()` (List<PlazaRegion>, plural) — current accessor
  for the polygon plaza model
- `addPlazaRegion(PlazaRegion)` — current registrar
- `plotSlots` (Phase 17: list of farm plot PlacementSlots)

Phase 18 adds:
- `getPlaza()` (singular convenience accessor returning the first
  region, or null for plaza-less recipes like ENCLAVE)
- `setPlaza(Plaza)` / `getPlaza().civicSlots()` for the matcher's
  civic-first claim path

## 9. The rework's exit criterion

Phase 23 declares the rework complete when:

- Spawn success rate is ≥ 90% across mixed terrain (default
  Minecraft, Lithosphere, Tectonic, etc.).
- No spawn produces an empty TOWN_HALL-only village; sites that
  cannot host a viable layout are abandoned cleanly via the
  cascade with a clear log.
- The debug visualization commands (`show_graph`, `show_features`,
  `show_hull`, `show_sectors`) cover every layer of the planning
  pipeline.
- The matcher scoring formula is documented in code comments at the
  call site.
- A `LAYOUT_OVERVIEW.md` exists describing the contract that future
  recipe authoring works against.

After the exit criterion is met, the architectural-shift experiment
in Section 5 begins.

## 10. Reference baseline

The canonical baseline for byte-equality validation in subsequent
phases is the most recent successful RADIAL run on superflat:
- centre = (-1881, -60, 237)
- 27/27 placed, 27/27 validated
- status=OK, truncations=0, retries=0
- `--- PLOT SLOTS ---` section present (Phase 17)
- All HOUSE buildings attributed to real sectors (Fix 3)
- GUARD_TOWER lands at chebDist=12 from OUTER_RING (Fix 1)

The current `dumpPlan` output format (VillagePlanner.java line ~553)
includes the following sections:
- `--- ROADS ---`
- `--- SECTORS ---`
- `--- SLOTS BY SECTOR ---`
- `--- COMMITTED BUILDINGS ---`
- `--- PLOT SLOTS ---` (Phase 17)
- `--- VALIDATION SUMMARY ---`
- per-village summary line

It does **not** include a `ringRoadR` field anywhere (the civic ring
road was deleted in doc-04). It does not yet include a `--- PLAZA ---`
section (Phase 18 adds that).

## 11. What this document is not
### 9.1. Measurement results

Phase 23.1 landed the harness (`/litv measure`) and
`LAYOUT_OVERVIEW.md`. The measurement run itself is a separate
follow-up step (Phase 23.2) — it requires a live Minecraft
session.

Once the run is performed, the user fills the table below from
the JSONL output and console summary.

| Metric | Default Minecraft | Lithosphere / rougher mod |
|---|---|---|
| Corpus size | _____ | _____ |
| Master seed used | _____ | _____ |
| Overall success rate | ____% | ____% |
| Primary-shape success | ____% | ____% |
| Fallback-rescued success | ____% | ____% |
| RADIAL primary-success | ____% | ____% |
| Crashes | _____ | _____ |
| Median composition time | ____ ms | ____ ms |
| P95 composition time | ____ ms | ____ ms |

**Dominant failure modes (top 3 by count):** _____, _____, _____.

**Polish-pass items addressed (Phase 23.2):** _____.

**Items deferred to post-rework backlog (Section 10):** _____.

If the measured rates are below the targets and the failures are
structural (not recipe-authoring quality), Phase 23.2 iterates
until the structural fixes land. If the failures are
recipe-authoring quality and fallback chains catch them, the
exit criterion is met regardless of absolute primary-success rate.

## 10. Post-rework backlog

Once Phase 23.2 (measurement + polish + closeout numbers) lands,
the placement rework is structurally complete and the state doc
transitions from "active rework state" to "completed rework
reference." The following items are explicitly out of the
rework's scope but have known callers and are queued as
post-rework work:

### Recipe-authoring quality

1. **LINEAR recipe-authoring rework.** LINEAR currently relies on
   the `RADIAL` fallback chain (Phase 22) to ship on most terrain.
   The recipe itself drops most of its slot pool under the
   tightened footprint budget (microfix Fix 2). A focused
   recipe-authoring pass restores LINEAR as a primary-success
   shape on flat farming terrain.
2. **PLAZA recipe-authoring rework.** Plaza authoring inherits
   PLAZA's superflat issues (Section 6b). Slot-overlap with the
   four spurs causes most failures. Same `RADIAL` fallback
   absorbs failure today; a focused pass would restore primary
   success.
3. **DUAL_PLAZA recipe-authoring rework.** Inherits PLAZA's
   issues. Treated together with PLAZA.
4. **Other shape recipes** (RIVERINE, HILLTOP, ROADSIDE, etc.).
   Add `fallback_chain` declarations as their failure modes are
   surfaced by Phase 23 measurement; recipe-authoring quality
   passes follow if and when measurement blames them.

### Decoupled primitives

5. **Bridge** (water-crossing) and **Stairway** (vertical
   traversal) road primitives. Decoupled from any recipe
   converting to use them. **Causeway** is optional — only land
   if Bridge + Stairway prove insufficient.

### Phase 20 deferred polish items

6. **`scoreSlot` lift.** The matcher's per-slot scoring loop
   uses raw `LayoutSlot` access; lifting it onto the graph view
   would make scoring uniform with other graph queries.
   Promoted to backlog or absorbed by Phase 23.2 if measurement
   surfaces matcher pathology.
7. **Multi-edge footpath BFS.** Expansion currently does
   single-edge BFS; multi-edge would let footpaths cross plaza
   regions cleanly. Deferred unless measurement reveals visible
   regression.
8. **`Plaza.civicSlots` persistence.** The civic-slot list on
   `Plaza` rebuilds on demand; persisting it would save a
   recompute per spawn. Cosmetic optimization; deferred unless
   profiling blames it.

### Architectural-shift experiment (Section 5)

9. **`OrganicRecipe.java` Path 2 prototype.** Two to four prompts
   of work. Reads `FeatureMap`, fires terrain-aware strategies,
   emits primitives + sectors based on what fires. Compared
   empirically against Path 1 + cascade by spawning 30 of each
   across the same seeds via `/litv measure`. The harness from
   Phase 23 is the comparison instrument.

### Adjacent reworks (not the placement rework's job)

10. **Kingdom rework.** Wires Phase 21's schema fields
    (`settlementTier`, `biomeAffinity`, `kingdomRoles`,
    `tradePriority`, `canBeCapital`, `maxPerKingdom`) into
    actual kingdom-level placement and selection logic. The
    placement layer is stable underneath this work.
11. **Trade road rework.** Pathfinding and road decay between
    villages.
12. **Decoration content rework.** NBT bridges, festival decor,
    town square rework, parks/gardens.
13. **Economy rework.** Beyond the gate-anchor seam.
14. **Atlas rework.** Worldgen-time atlas rebuilds.

None of items 10–14 require further structural work in
placement before they can begin.

## 11. What this document is not

This document does not duplicate the original `00-OVERVIEW.md`,
`01-ABSTRACTIONS.md`, or `02-PHASES.md` planning files. If those
files exist in the repository, they are the authoritative source for
data types, contracts, and per-phase file lists. This document is
the **state and direction** layer that sits above them: what's
landed, what's pending, what's deferred, and why.

If those files do not exist or have drifted, recreate them from
this document and from the conversation history. The structure was:
overview (goal, abstractions, build order), abstractions (types,
APIs, contracts), phases (per-phase files affected, validation,
prompt count).
