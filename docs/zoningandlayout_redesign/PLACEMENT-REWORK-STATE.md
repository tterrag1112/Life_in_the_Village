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
| 17 | Farm plot sector integration | pending |
| 18 | Plaza polygon ownership consolidation | pending |
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

## 3. Truncation reaction (Phase 16 follow-up)

Phase 16 landed the plumbing: `RoadPrimitive.computeCenterline` now
returns a result with `isComplete()` and a `TruncationReason` (one of
`CLIFF_RISE`, `CLIFF_DROP`, `WATER_CROSSING`). Truncation logs fire
correctly. Nothing consumes the result yet.

The follow-up phase — call it **Phase 16b** — wires consumption:

- Recipes capture the truncation result per primitive call.
- Slot emission clamps to the actual reachable road, not the
  geometric intent.
- A severe-truncation cascade pivots the recipe (rotate axis, fall
  back to a simpler shape, or abort the site cleanly) when the
  primary spine is below a viability threshold.

This phase also frames the cascade as the **first instance of a
general constraint-propagation pattern**, not as truncation-specific
logic. The enum is `RecipeStatus` (not `SpineViability`); the
re-emit method on `BaseRecipe` is `reEmit(reason)` and accepts any
constraint violation, not only truncation. Future cases (slot loss
beyond threshold, footprint conflict, sector starvation) plug into
the same engine. Phase 22 generalizes it.

This framing is non-negotiable. It is what makes Phase 22 cheap and
what positions the codebase for the architectural choice in Section
5 without committing to either path.

## 4. Pending work, in execution order

This is the corrected, complete list. The original plan undercounted
because it lumped recipe conversions and primitives together and
because constraint-propagation emerged as an extra item.

1. **Phase 16b — truncation reaction + general re-emit pattern.**
   RADIAL gets full slot clamping. LINEAR gets a minimal-viable
   conversion (just enough to run a non-radial test). The cascade
   is implemented as a general re-emit engine.

2. **Phase 17 — Farm plot sector integration.** Farm plots currently
   live outside the sector framework, handled by `FarmPlotPlacer`.
   Move them inside.

3. **Phase 18 — Plaza polygon ownership consolidation.** TownSquare
   slot emission moves out of the flat pool. Removes the
   `drainSlotsSince` awkwardness. Cleans up the
   `sector=unknown` attribution in plan dumps.

4. **Phase 19 — LayoutPlan + AnchorKind + spawner/decorator wiring.**
   The immutable plan-as-contract handoff. Spawner and decorator
   currently consume the mutable `VillageLayout`. This is the
   load-bearing phase that makes constraint-propagation natural and
   makes any future architectural shift possible. Do not skip.

5. **Phase 20 — BuildSiteFinder migration.** Expansion currently uses
   spiral search on raw block positions. Move it onto the new graph
   and feature-map APIs so expansion participates in the same system
   as initial planning.

6. **Phase 22 — Recipe fallback chains.** Schema-level. Village types
   declare their fallback chain (e.g. `RADIAL → LINEAR → ABORT`).
   The cascade in Phase 16b consumes this. Generalize the re-emit
   engine here.

7. **Phase 21 — Kingdom-rework schema additions.** Schema-only;
   small. Can land anytime after Phase 19.

8. **Microfix batch.** Three deferred matcher/layout polish items
   (see Section 6).

9. **Bridge / Stairway / (optional) Causeway primitives.** Decoupled
   from recipe conversion. The primitives ship; recipe authoring
   uses them later.

10. **Phase 23 — 90% success measurement + polish.** The rework's
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

## 6. Deferred microfixes

These are small bugs surfaced during the rework but not blocking it.
Batch them in a single prompt after Phase 16b lands.

- **GUARD_TOWER RingBand `useShared` bug.** When `useShared=true`,
  RingBand should override its own inner/outer radii with
  `sharedRingR ± footprintOffset`. Currently the diagnostic prints
  `useShared=true` but the slot math ignores the shared ring and
  uses the band's nominal inner/outer instead. AGRI happens to work
  because its footprint is large enough to absorb the gap; DEFENSE
  fails because GUARD_TOWER's 9×9 footprint can't.

- **WEAVER perturbation-clamp.** The matcher uses slot `fpW=16
  fpL=16` budget when perturbing a building, but WEAVER is 9×9.
  This lets the matcher push WEAVER 5+ blocks off the road; the
  validator's `max=13` for a 9×9 then fails. Clamp matcher
  perturbation to `min(slotFootprint, buildingActualFootprint +
  slack)`.

- **FARMHOUSE FIELD_EDGE intermittent reject diagnostic.** Three
  candidates are offered; all reject. Surface the reject reason in
  the dump so the cause becomes diagnosable. Fix follows once the
  cause is known.

- **`sector=unknown` attribution for HOUSE.** Houses commit through
  the legacy flat-slot path and don't carry sector tags into the
  plan dump. Cosmetic but useful for debugging future authoring
  work. Likely resolves itself when Phase 18 (plaza polygon
  ownership) lands; if not, surface and fix.

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
  of layouts. Post-rework.
- The Path 1 vs Path 2 architectural decision. Post-rework.
- Decoration content (NBT bridges, festival decor, town square
  rework, parks/gardens). Decoration rework.
- Pathfinding and road decay. Trade road rework.

## 8. Conventions and naming

These names matter because compressed context tends to drop
specifics; explicit naming protects the design.

- **`RecipeStatus`** is the enum returned from constraint checks
  (formerly proposed as `SpineViability`). Generalizes beyond
  truncation.
- **`BaseRecipe.reEmit(reason)`** is the general re-emit method
  recipes override. Truncation cascade is one specific call site.
- **`RoadResult`** is the per-primitive record carrying centerline,
  `isComplete()`, `TruncationReason`, intended length, actual
  length. Recipes capture and pass these into slot emission.
- **`LayoutPlan`** is the immutable plan record produced at end of
  planning, consumed by spawner and decorator. Replaces the
  mutable `VillageLayout` as the contract. Phase 19 introduces it.
- **`AnchorKind`** is the enum of named anchor positions (e.g.
  `TOWN_SQUARE`, `MAIN_GATE`, `RIVER_LANDING`) that map to graph
  node IDs on the LayoutPlan.

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

## 10. What this document is not

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
