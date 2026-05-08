# UNIFIED REWORK PLAN — Life in the Village

## Purpose

Consolidates the four in-flight reworks (placement, decoration, roads,
kingdoms) into one sequenced plan. Created because the V2 adaptive
village system, originally scoped as a brief interlude, grew into a
full replacement for V1 planning — invalidating assumptions the other
reworks made when authored.

Owner: human-managed. Do not edit without explicit permission.

## Documents this affects

**Superseded** (now historical):
- `docs/zoningandlayout_redesign/ADAPTIVE-LAYOUT-SPEC.md` — V2 replaces.
- `docs/zoningandlayout_redesign/00..02-PLACEMENT-*.md` — V1 23-phase
  rework retired by V2.
- `docs/zoningandlayout_redesign/PLACEMENT-REWORK-STATE.md` — frozen
  at Phase 23.1.

**Absorbed** (still authoritative for already-shipped work; new work
routed through this plan):
- `DECORATION_PLAN.md` / `DECORATION_PROGRESS.md` — Phase 0 status
  preserved. Phases 1–4 reframed by Track B.
- `NPC_PLAN.md` / `NPC_PROGRESS.md` — Phases 0–4 complete. Phase 5
  deferrals continue tracking there.
- `ROADS_PLAN.md` / `ROADS_PROGRESS.md` — Phases 1–10 complete.
  Phase 7f Slice 4 / 11 / 12 / 13 routed through Track C.
- Kingdom rework plan — Phase 0 reframed by D1. Section 5 rewritten
  by D2. Phases 1–7 routed through D3.

## Locked-in decisions

1. **V2 ships completely.** V1 planning machinery removed in Track A.
2. **Single Culture record.** `Cultures.Culture` (NPC) is canonical;
   V2's four fields (`roadMaterial`, `preferredCurvature`,
   `preferredPlazaShape`, `inclinationBias`) added as additive bundle.
   V2's own `Culture` and `CultureRegistry` deleted.
3. **Variants combined.** One canonical selector. Decoration
   `VariantSelector` and V2 `VariantPicker` merged in A3. Final shape
   is an interface called by V2 Layer 3 and decoration second-pass.
4. **`KingdomEventBus` is a peer**, not an extension of
   `NpcLifeEventBus`. Same scaffolding pattern.

## Invariants

1. **One canonical planning system at runtime.** After A4,
   `VillageSpawner` calls only `MinimalSpawner.spawn`. V1 planning
   code has no callers.
2. **One canonical Culture record.** `Cultures.Culture` read through
   `CultureRegistry`. No parallel Culture types.
3. **One canonical variant selector.** V2 Layer 3 calls into it;
   decoration second-pass calls into it.
4. **Two event buses (Npc, Kingdom) coexist as peers.** Cross-bus
   events are emitted on both buses; producers explicitly choose.
5. **No new shape recipes.** V2 has no shapes. Specs that reference
   shapes (Decoration Phase 4, Kingdom Section 5) are rewritten before
   implementation.
6. **`ZoneRegistry` is gone post-A1.** The four call sites are
   migrated to V2 vocabulary, not preserved with adapters.
7. **Specs that gate code precede the code.** B3 doc rewrite precedes
   B3 implementation. D2 spec rewrite precedes D3 Phase 1.
8. **Kingdom Phase 0.1 builds on already-shipped Culture.** Don't
   redefine the schema; extend it.

## Track A — Placement consolidation

Gates everything else. Linear; no parallelism within the track.

### A1 — V1 cleanup + ZoneRegistry migration

Delete V1 planning machinery:
- `Village/Planning/Primitives/Recipes/` — all 17 recipe classes.
- `Village/Planning/Adaptive/` — entire package.
- `Village/Planning/Primitives/LayoutPrimitive.java` and remaining
  slot/intention/anchor/emitter classes.
- Cascade engine remnants from Phase 16+.
- Slot-based `PlacementMatcher` paths.

Migrate the four `ZoneRegistry` call sites to V2 vocabulary:
- `BuildSiteFinder` — replace zone-claim with V2 site analyzer call.
- `PlanContext.claimByZone` — delete the call path; V2 doesn't claim
  zones.
- Terrain / civic-ring policies — fold into V2 Layer 2 / Layer 4.
- `LayoutPrimitive` civic-ring sizing — irrelevant after deletion.

Delete `Village/Planning/ZoneRegistry.java`. Unblocks decoration
P0a-18.

End state: `git grep ZoneRegistry` returns nothing. V1 planning code
has no source-file presence.

### A2 — Culture unification

Add to `Cultures.Culture`:
- `String roadMaterial`
- `Curvature preferredCurvature`
- `PlazaShape preferredPlazaShape`
- `Map<Inclination, Float> inclinationBias`

Wrap as a `PlanningBias` sub-bundle if structurally cleaner. Update
`CultureBundles` factory; update existing 4 starter culture
definitions.

Migrate V2 callsites in `V2/Layer4/`, `V2/Layer2/`, `V2/Culture/` to
read from `CultureRegistry.get(cultureId)`. Delete
`V2/Culture/Culture.java` and `V2/Culture/CultureRegistry.java`.
Move `Curvature.java` and `PlazaShape.java` to a non-deleted package.

End state: one Culture record; one CultureRegistry; V2 reads through
it.

### A3 — Variant unification

Pre-investigation by user to confirm decoration `VariantSelector` and
V2 `VariantPicker` capabilities; merged design then drafted.

Working assumption: decoration `VariantSelector` is the canonical
entry point; V2 `VariantPicker` folded in. Final shape is a
`VariantResolver` interface taking `(BuildingType, Culture,
Inclination, terrain context)` and returning chosen variant + tint
colors.

V2 Layer 3 calls it; decoration second-pass calls it.

End state: one canonical variant selection codepath. Both consumers
route through it.

### A4 — Spawner wiring

`VillageSpawner.spawnVillage` routes to `MinimalSpawner.spawn` for
all sites. Remove the V1 path.

`adaptive_v2` config flag — keep for one rollout cycle then delete,
or delete immediately. Decide at A4 time.

End state: V2 is the only planner running at runtime. Existing
decoration / road / NPC pipelines run downstream of V2 unchanged.

### A5 — Measurement run

Phase 23.2 from the placement rework state doc, against V2.

Use `/litv measure` harness across the same biome / seed set as the
V1 baseline. Compare success rate, drop rate, conflict count,
viability tier distribution.

If V2 underperforms V1 Phase 22 numbers, queue diagnostic cycles
before declaring Track A done. If it meets or exceeds, fill in
Section 9.1 of the placement state doc and move on.

## Track B — Decoration finishing

Starts after A1.

### B1 — Phase 0a content gaps

- P0a-15: HOUSE pilot NBTs in default culture × RURAL.
- P0a-16: Default culture × URBAN variant pack.
- P0d-04: `MarketStallPlacer` migration onto subbuilding scanner.
- GuildHall colour fields: extend `GuildData` with primaryColor /
  accentColor; wire P0a-12 forced overrides.

End state: Phase 0a complete. HOUSE placement no longer hard-fails;
URBAN villages render with intended variants.

### B2 — Decoration Phases 1–3 (V2 vocabulary pass + execution)

V2-vocabulary pass on each subsystem doc before implementation:
- 05 street furniture, 06 signs and markers, 07 industry adjuncts,
  08 herb and cottage gardens, 09 parks and gardens, 10 farm plot
  rework, 11 homesteading.

Per-doc pass: confirm framework anchors (DecorationSlot, AdjunctPlot,
Subbuilding) still apply; replace shape-recipe / `LayoutPrimitive` /
slot-intention references with V2 equivalents (Inclination biases,
V2 anchor positions). Execution per existing decoration progress
task IDs.

### B3 — Decoration Phase 4 spec redesign + execution

Rewrite for V2 vocabulary:
- 12 walls — village-perimeter post-pass on V2 outputs. Gated by
  `ViabilityTier` (CITY/TOWN gets walls) + `Inclination.DEFENSIVE`
  weight.
- 13 festivals — V2 plaza (Layer 4 hub) hosts festival ground; civic
  AdjunctPlot for dedicated grounds in larger villages. Hooks NPC
  Phase 5 events.
- 14 cemeteries — AdjunctPlot attached to CHAPEL / SHRINE; size
  scales with viability tier. Hooks NPC death events + village
  history.

Doc rewrite first; implementation second. Per-doc execution per
existing P4-01..15 IDs.

## Track C — Roads finishing

### C1 — Cleanup

- Delete `Village/Economy/Trade/TradeRoad.java`. Migration complete;
  source is dead code per Roads invariant 1.
- Fix `TravellingGroupEngine.tick()` synthetic-caravan path
  (carryover from Roads Phase 3b).

Anytime; not blocking.

### C2 — Phase 7f Slice 4 connector routing

Connectors traverse village interiors using V2's village road graph.
Blocks on A4. Per existing `ROADS_PLAN.md` description.

### C3 — Roads Phases 11–13

- Phase 11: Player-initiated road construction.
- Phase 12: POI subroads.
- Phase 13: Sea route unification (folds `SeaRoute` into world graph).

Greenfield; sequential per `ROADS_PLAN.md`. Each phase is its own
design pass.

## Track D — Kingdom rework

### D1 — Phase 0 bridge

- 0.1: Kingdom-tier fields on extended `Cultures.Culture`. Add
  nobility ranks, succession rule, subdivision model, upkeep mix,
  kingdom-office requirements as additional sub-bundle.
- 0.2: `KingdomEventBus` peer. Same scaffolding as `NpcLifeEventBus`.
  Initial event types: succession, law-change, treaty.
- 0.3: Stability scalars at village / province / kingdom level.
- 0.4: Territory vs membership split. `KingdomClaim` provides
  territory; add explicit `kingdomId` per village for membership.
- 0.5: Legitimacy scalar on Kingdom + ruler.
- 0.6: Estate primitives. Bind to noble manor / capital subbuildings
  (deferred until building types exist).
- 0.7: Heraldry generator + persistence on Kingdom + (future) House
  records.
- 0.8: Kingdom-tier office stub completion. Register Chancellor,
  Scholar, General, Magistrate, Spymaster, Treasurer, Diplomat.
  Capabilities wired in D3 Phase 3.

Can run in parallel with B1 + B2 (extends already-shipped systems;
no V2 dependency beyond A2 Culture).

### D2 — Kingdom plan Section 5 rewrite

Replace V1 schema-name + slot-primitive list with V2 equivalents:
- Schema fields: re-express `capital_emits_claim`,
  `claim_budget_hint`, `vassal_types`, `hostile_types`,
  `min_nobility_tier`, `province_seat_eligible`, `claim_resistance`
  as V2 manifest additions. Some map to existing Phase 21 fields
  (`canBeCapital`, `kingdomRoles`); others are new.
- Slot primitives: re-express `CASTLE_SLOT`, `PALACE_SLOT`,
  `NOBLE_RESIDENCE`, `AUDIENCE`, `CEMETERY`, `FESTIVAL_GROUND`,
  `TREASURY` as V2 hooks (`Inclination` biases, manifest
  `provides`/`requires`, AdjunctPlot/Subbuilding bindings).

Doc-only. Output: Section 5 rewrite + any required additions to V2
manifest schema.

### D3 — Kingdom plan Phases 1–7

Sequential per the original plan. Adjustments:
- Phase 1 (worldgen) blocks on A4. `VillageSeedSampler` / three-zone
  gen / capital claim emission feed sites to `MinimalSpawner`.
- Phase 2 (houses & nobility) absorbs NPC family/lineage; reduced.
- Phase 3 (provinces & offices) extends NPC office framework;
  reduced.
- Phase 4 (laws & intrigue) layers on NPC village laws.
- Phase 5 (player experience) builds on NPC PlayerVerbs.
- Phase 6 (decline, conflict, religion-as-authority) — religion-as
  -political-authority absorbs NPC religion records.
- Phase 7 (polish, scale, longevity) — out-of-scope per below.

## Sequencing summary
A1 → A2 → A3 → A4 → A5
↓ (post-A2)
├─→ B1 ─┐
│      │
└─→ D1 ┘
↓ (post-A4)
├─→ B2, B3, D2, C1, C2  (interleavable)
└─→ D3-1 (worldgen)
↓ (post-B3 for kingdom features needing
decoration; sequential thereafter)
└─→ D3-2..D3-6
└─→ C3 (sequential, last)

Hard blocks:
- B1, D1: post-A2 (need unified Culture).
- B2, B3, D2, C1, C2, D3-1: post-A4.
- C3, D3-2..D3-6: last.

## Out-of-scope (explicitly deferred)

- NPC Phase 6 — per `NPC_PLAN.md`.
- Kingdom Phase 7 polish — per kingdom plan.
- Texture / model assets for NPC appearance Layer (Phase 5 task 33).

## Revision notes

(empty)