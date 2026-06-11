# Life in the Village — Claude Code context

This is a NeoForge 1.21 Minecraft mod. Village and kingdom simulation,
procedural worldgen, NPC professions.

## Before doing any rework work

1. Read `STATE.md` — as-built systems overview (the code as it is,
   regenerated per milestone).
2. Read `ROADMAP.md` — the forward plan and track sequencing
   (human-managed; do not edit without permission).
3. Read `PROGRESS.md` — the single append-only ship log.
4. Read `INVARIANTS.md` — the non-negotiables (roads invariants, firm
   architectural constraints, locked decisions, districts-by-default,
   religion locks, sim-ledger principles).

Retired per-rework PLAN/PROGRESS files are under `docs/archive/` for
history only; do not treat them as current.

## Before doing anything on the road system

1. Read the roads section of `STATE.md` for current state and the
   roads track in `ROADMAP.md` for forward work.
2. Log shipped phases in the single `PROGRESS.md` (append-only).
3. Honor the 12 roads invariants in `INVARIANTS.md` §5 (and the Old
   Realm fiction they reference). They are not suggestions.

## Before writing NPC behavior code

Read `.claude/skills/litv-npc-behavior/SKILL.md`. It defines the
canonical helpers for storage, navigation, skill XP, tools, wealth, and
market interactions. Direct calls to underlying utilities bypass the
intended convention layer. Profession behaviors must register in
`ProfessionBrainFactory.REGISTRARS` or they silently never run.

## Firm architectural constraints (apply everywhere, not just roads)

- No abstract method renames on existing interfaces.
- Extend behavior through base-class hooks and shared helpers, not
  per-subclass repetition.
- Planning-layer correctness over realiser heroics: when failures
  cluster, fix the planner, not the realiser.
- New tags/enums/primitives only when a concrete consumer needs the
  distinction.

## Placement pipeline (V1 → V2)

V2 under `Village/Planning/V2/` is the canonical planner. The single
spawner path is `V2VillageSpawnerAdapter.spawn` (older docs called this
`MinimalSpawner.spawn`; no such class exists). V1 planning machinery is
deleted from source.

Encountering V1 vocabulary (`ShapeRecipe`, `LayoutPrimitive` cascade,
slot intentions, `ZoneRegistry`-style zone claims) is a signal to
convert, not to extend. Conversions delete the V1 source in the same
commit; they do not leave the V1 class behind. See `INVARIANTS.md` §3
for the convert-on-sight rule and §2 for the V2-only lock; `STATE.md`
describes the live V2 layers.

## Workflow conventions

- **Disposition before code.** Every non-trivial prompt opens with an
  investigation pass that reads current state and surfaces findings
  before drafting code.
- **The PROGRESS log is append-only.** Add a new bottom entry to the
  single `PROGRESS.md` per shipped phase; do not edit prior entries.
- **Every shipped phase ends with "Deviations from prompt" and
  "Out-of-scope but flagged" sections.**
- **Build verification disclosure.** When `./gradlew build` cannot run
  because the sandbox can't reach `maven.neoforged.net`, close the
  PROGRESS entry with: "Build verification deferred (sandbox blocks
  maven.neoforged.net)." Manual static review acceptable in its place.
- **Surgical fix-ups over redesigns** when user testing surfaces bugs.
  Redesign requires explicit go-ahead.

## Tie-in audit (mandatory on every non-trivial prompt)

Every change above trivial scope requires a Tie-In Audit in the
disposition:

1. **Upstream feeders.** What systems feed inputs into the touched
   system?
2. **Downstream callers.** Grep for inbound usages of every public
   type touched. Each caller gets a disposition — updated in scope,
   flagged out of scope, or unaffected.
3. **Sibling systems.** What depends on shared state or events emitted
   by the touched system?
4. **Exhaustive switches.** If touching enums or sealed types, list
   every exhaustive `switch` and disposition each arm.

The single most common Claude Code regression is "the change works in
isolation but the systems downstream of it weren't audited." Skipping
the tie-in audit is the kind of shortcut the round-two fix-up pass
exists to clean up. The `litv-tie-in-audit` skill produces the audit
template.

## Cross-system calls go through caller helpers

When a cross-system call pattern recurs (e.g. produced item → building
storage with fallback, or NPC walk-to with pathfinding backstop), there
is a canonical helper that encodes the right routing. Read
`.claude/skills/litv-npc-behavior/SKILL.md` for the NPC-side helpers.
If a recurring cross-system pattern doesn't have a helper yet, propose
one in disposition rather than open-coding. See the
`litv-caller-helper-design` skill for the design template.

## Simplification sweep on iterated systems

When working in a subsystem that has been iterated many times
(placement, decoration, roads, NPC), include a Simplification Sweep in
disposition: list classes in scope, count inbound callers, flag orphans
for deletion and overlapping pairs for consolidation. Acting on
confirmed orphans is part of the same change, not deferred work. The
`litv-simplification-audit` skill produces the sweep template.

## Preflight checks (before shipping a change)

- If you added an enum value, grep every exhaustive `switch` over that
  enum and decide each arm. (`EdgeTier.SEA` required 12+ updates.)
- If you added a record field, gate it with `optionalFieldOf` so
  pre-feature saves load cleanly. Watch for the 16-field codec ceiling.
- If you added an override or "synthetic" field on an existing record,
  trace every read site to confirm the override path is consulted.
- If you wrote per-tick code, demote unchanged-state reprints to DEBUG.
  Hot-loop log spam has masked real diagnostics multiple times.
- If you added a new pipeline path, audit every command and call site
  that runs the old path — `/litv spawn` bypassed B2 post-passes for
  weeks.

## Smoke-test plans + pre-commit final check

Every shipped phase's PROGRESS entry includes a user-executable
smoke-test plan — numbered steps the user can run in-world. See the
format contract at the top of `PROGRESS.md` (the Detour-A 2026-05-23
entry that originally modeled it is now in `docs/archive/`).

Before committing a batch of changes, the final prompt in the batch is a
pre-commit final check: re-read the diff, re-run preflight items
(tie-in audit, simplification sweep, V1 sightings, exhaustive-switch
audit, codec field-cap check), and confirm the PROGRESS log entry is
shaped correctly. The final-check prompt does no new work.

## When in doubt

Ask before restructuring. Ask before deleting. Ask before "improving"
documentation files.
