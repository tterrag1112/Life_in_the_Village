# Life in the Village — INVARIANTS

The non-negotiables, in one place. These are load-bearing: do not violate
without explicit human approval. Where a constraint was the root of past
regressions, that history is noted. The forward plan is `ROADMAP.md`; the
as-built picture is `STATE.md`; the ship log is `PROGRESS.md`.

---

## 1. Firm architectural constraints (apply everywhere)

1. **No abstract method renames on existing interfaces.** New behavior comes
   through hooks and helpers.
2. **Extend through base-class hooks and shared helpers, not per-subclass
   repetition.**
3. **Planning-layer correctness over realiser heroics.** When failures
   cluster, the fix belongs in the planner, not in realisation retry loops.
4. **New tags, enums, and primitives only when a concrete consumer needs the
   distinction.** No speculative abstractions.

## 2. Locked decisions still in force (from the unified rework plan)

These survive the doc replacement because the code still depends on them.

1. **V2-only planning.** V2 ships completely; V1 planning machinery is removed
   from source. `VillageSpawner` calls only `V2VillageSpawnerAdapter.spawn`
   (historically referred to as `MinimalSpawner.spawn` — that class does not
   exist; the live name is `V2VillageSpawnerAdapter`). V1 planning code has no
   callers.
2. **One canonical Culture record.** `Cultures.Culture` is the single record,
   read through `CultureRegistry`. V2's four placement fields (`roadMaterial`,
   `preferredCurvature`, `preferredPlazaShape`, `inclinationBias`) live on it
   as an additive bundle. No parallel Culture types; V2's own `Culture` /
   `CultureRegistry` are deleted.
3. **One canonical variant selector.** A single interface is called by V2
   Layer 3 *and* the decoration second pass. The decoration `VariantSelector`
   and V2 `VariantPicker` are merged (the live implementation is
   `VariantResolver`).
4. **Two event buses (Npc, Kingdom) coexist as peers.** `KingdomEventBus` is a
   peer of `NpcLifeEventBus`, not an extension. Cross-bus events are emitted on
   both buses; producers choose explicitly.
5. **No new shape recipes.** V2 has no shapes. Any spec that references shapes
   is rewritten before implementation.
6. **`ZoneRegistry` is gone.** No adapters preserve it; call sites use V2
   vocabulary.

## 3. Convert-on-sight (V1 → V2)

The V1 → V2 placement migration is functionally complete in source (V1
planning machinery is deleted). Encountering V1 vocabulary — `ShapeRecipe`,
the `LayoutPrimitive` cascade, slot intentions, `ZoneRegistry`-style zone
claims — is a signal to **convert, not extend**. Conversions delete the V1
source in the same commit; they do not leave the V1 class behind. (Some
V1-era *class names* survive as live V2 dependencies — `SlotTag`, `LayoutPlan`
+ `AnchorKind`, `RoadPrimitive`, `FeatureMap`, `RoadGraph` — and are kept.
See `STATE.md`.)

## 4. Districts by default

Every building belongs to a footprint-sized district. The district layer is
the placement substrate, not an optional overlay. Exceptions exist only at the
tiniest settlement tiers, where a full district set would overwhelm the
footprint. Decoration rides the district pass it belongs to and is never a
standalone track.

## 5. Roads invariants (12)

From the canonical roads plan; all 12 still honored.

1. **Graph is canonical.** `WorldRoadGraph` is the source of truth.
   `TradeRoute` is a lightweight reference into it. Block placement is derived
   from graph state, not the reverse.
2. **Routes own no blocks.** Routes hold `List<UUID> edgeIds`. Edges own their
   block paths (lazily realized).
3. **Great roads never decay.** Maintenance does not apply. Period.
4. **Great roads have no maintainer kingdom.** They are neutral.
5. **Village upkeep is village-local, not kingdom-central.** Each edge's
   `maintainerVillageIds` list drives decay; kingdom treasuries do not pay road
   upkeep directly.
6. **Connector tier derives from village size tier, not caravan traffic
   count.** The traffic counter remains for internal event-density decisions
   but is not the promotion driver.
7. **Planning-layer correctness over realiser heroics.** (Mirrors §1.3.)
8. **No abstract method renames on existing interfaces.** (Mirrors §1.1.)
9. **Tags, primitives, and enums are added only when a concrete consumer
   requires the distinction.** (Mirrors §1.4.)
10. **The great-road logical graph exists at worldgen, not lazily.** Only block
    realization is lazy. Village placement depends on the graph being queryable
    from the first tick.
11. **Road signs respect the sign-text constraint.** Village name + direction
    glyph, no distances.
12. **All material and flavor choices are consistent with the Old Realm
    fiction.** Great roads are the relics of a fallen precursor empire; that
    fiction governs materials, naming (scarcity-based, ~one named road per
    8000×8000 region), and decorative choices.

## 6. Religion era-2 locks

1. **Gods are not religions.** Gods are first-class entities with a favour
   economy; religions are a separate per-world layer that venerates them.
2. **Religions are per-world.** The religion store is per-world, with
   interreligious relations tracked between them.
3. **Favour is per-god.** The favour economy is keyed per god, not per
   religion.

## 7. Sim-ledger principles

1. **Sim is truth until realized.** A settlement's simulated digest is the
   authoritative state until the village is realized in blocks.
2. **One truth-handoff write-path per direction.** Realization materializes the
   digest into the world; unload checkpoints observed truth (realized NPC work,
   income) back into the sim and recalibrates its rates. Exactly one write-path
   each way — no ad-hoc back-channels.
3. **Graduated commitment: charter → survey → realization.** Commitment sharpens
   in stages against data at matching resolution — charter commits to an atlas
   cell + role + size band (worldgen, ~free); survey commits to an exact anchor
   using load-free generator-backed terrain sampling; realization commits
   blocks on player approach. Block-level promises are never made from
   cell-level data.

---

*See also: `CLAUDE.md` for workflow conventions (tie-in audit, preflight
checks, simplification sweep, smoke-test plans) — those are process rules, not
invariants, and live there.*
