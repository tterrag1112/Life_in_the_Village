---
name: litv-tie-in-audit
description: >
  Produce the Tie-In Audit subsection that every non-trivial Life in
  the Village prompt requires in its disposition. Use this skill
  whenever a prompt touches an existing system (anything beyond a
  brand-new isolated file). The audit enumerates upstream feeders,
  downstream callers, sibling systems, and exhaustive switches that
  depend on the touched code, then assigns a per-tie-in disposition.
  Skipping the audit is the #1 root cause of silent regressions in
  this codebase ("the change works in isolation, but X / Y / Z
  weren't updated"). Always use this skill before drafting code that
  modifies an existing public type, enum, or pipeline path.
---

# Life in the Village — Tie-In Audit Skill

The single most common Claude Code regression in this codebase is:
**a change works in the system being modified, but the systems
downstream of it weren't updated.** Examples from the progress logs:

- `/litv spawn` bypassed B2 post-passes for weeks because the spawn
  command wasn't audited when V2 wired in.
- `VillageDecorator` ran V1 networks on V2 villages because no one
  checked the decorator after the V2 pipeline shipped.
- New `EdgeTier.SEA` enum arm landed without updating 12+ exhaustive
  switches across realisation / decay / lighting / planning / map /
  proposal calculator.
- `overridePath` field was read only by entity AI that never ran for
  synthetic caravans.
- Tier overrides didn't propagate because `Village.getTier()`
  re-derived from `buildingIds.size()` every call.

This skill produces the Tie-In Audit subsection that catches these
before they ship.

## Step 0 — Identify scope

Determine what's being touched. The skill applies whenever the change
modifies any of:

- A public type (class, record, interface, enum) used outside its own
  file.
- A pipeline path (spawner, planner, matcher, realiser, decorator,
  populator, etc.).
- A shared registry (BuildingProfileRegistry, CultureRegistry, etc.).
- An event producer (NpcLifeEventBus emitter, KingdomEventBus
  emitter).
- A serialised data field (codec, savedata, NBT).

For trivial changes (single private field, single internal method,
one-file isolated work), a one-line audit is acceptable
("Touches X only; no downstream callers — see grep below").

## Step 1 — Gather the four audit lists

### 1a. Upstream feeders

What systems supply inputs to the touched system?

- Read who calls into the touched type and what arguments they pass.
- Read who writes the data the touched system reads.
- Note any timing assumptions (e.g. "runs after `finalizeSpawn` but
  before `assignToBuilding`" — see the NPC `cultureApplied` precedent).

### 1b. Downstream callers

Grep for every inbound usage of every public type touched:

```bash
# For each touched type T:
git grep -n "\bT\b" src/main/java | grep -v "src/main/java/.../T.java"
# Also check for sealed-interface permits, import statements,
# generic type parameters that mention T:
git grep -n "import .*\.T;" src/main/java
git grep -n "extends T\b\| implements T\b" src/main/java
```

Each downstream caller gets a per-call disposition (see Step 2).

### 1c. Sibling systems

What depends on shared state or events emitted by the touched system?

- Event bus subscribers (NpcLifeEventBus, KingdomEventBus).
- Persistence / SavedData readers (VillageSavedData,
  LayoutPlan reads).
- Cross-track touch points (placement → decoration → roads → NPC
  spawning).
- Commands and debug entry points (`/liv ...`, `/litv ...`,
  `/building ...`).

### 1d. Exhaustive switches

If the change touches a sealed type or enum:

```bash
# For each touched enum E:
git grep -n "switch *(.*E)" src/main/java
git grep -n "case E\." src/main/java
```

List every exhaustive switch and decide each arm explicitly.

## Step 2 — Per-tie-in disposition

For each item from Step 1, assign one of:

- **In scope.** Updated by this prompt; include the change.
- **Out-of-scope but flagged.** Not done now, but should be tracked.
  Add to the prompt's "Out-of-scope but flagged" section so it lands
  in the PROGRESS entry's tracking list.
- **Unaffected.** Use of the touched type is read-only / interface-
  stable; no change needed. Briefly say why.

The disposition decisions are the load-bearing output of the audit —
they're what Garrett will scan to decide whether the prompt is shaped
correctly.

## Step 3 — Caller-helper opportunity check

When the audit reveals a recurring cross-system call pattern (same
shape of calls appearing in 3+ downstream callers), flag it as a
caller-helper candidate. Existing precedents:

- `NpcBehaviorHelpers.depositToBuilding` / `walkTo`
- `SkillXp.award`
- `NpcEconomy.businessPay` / `marketPurchase` / `payWage`
- `VillageEconomy.postListing` / `findCheapestSeller`

If a new helper makes sense, propose it inside the audit and reference
the `litv-caller-helper-design` skill for the design template.

## Output format (drop into prompt disposition)

```markdown
### Tie-In Audit

**Touched surface:** [list of types / pipelines / fields being modified]

**Upstream feeders:**
- [feeder 1] → [what it provides]
- [feeder 2] → ...

**Downstream callers** (grep results inline or summarised):
| Caller | Call site | Disposition |
|---|---|---|
| ClassA.methodX | line 142 | In scope — update to new signature |
| ClassB.methodY | line 88  | Unaffected — read-only |
| ClassC | n/a (subclass) | Out-of-scope but flagged — needs profile update |

**Sibling systems:**
- [system 1] — disposition
- [system 2] — disposition

**Exhaustive switches** (if applicable):
- `Foo.java:23` switch on EnumE — needs new arm
- `Bar.java:99` switch on EnumE — needs new arm
- ... [list all sites]

**Caller-helper opportunity:** [if any — describe the shape and which
existing helper module would host it]
```

## What this skill is NOT for

- Trivial single-file isolated work — a one-line audit suffices.
- Pure documentation changes — no tie-ins to audit.
- New brand-new subsystems with no existing consumers — there's
  nothing downstream yet.
- Investigative / read-only disposition passes — they're producing
  the audit, not consuming it.

## Common audit traps (verify these before signing off)

- **Getters that re-derive from base state.** If the touched type has
  an override field, audit every getter to confirm it respects the
  override rather than recomputing.
- **Commands as silent bypass points.** Check every `/liv` and
  `/litv` and `/building` command that touches the surface.
- **Synthetic / fake objects.** If the touched type has a
  "synthetic"/"override" path, verify every reader checks it.
- **Per-tick code paths.** If touching anything that runs in a tick
  loop, audit for hot-loop log spam and idempotence.
- **Codec / SavedData fields.** New fields need `optionalFieldOf`;
  watch the 16-field codec ceiling.

## Anti-patterns to flag in PR review

- A prompt that proposes a public-type or pipeline change with no
  Tie-In Audit subsection in the disposition.
- An audit that lists "no downstream callers" without showing the
  grep used.
- "Unaffected" dispositions with no reason given.
- Exhaustive-switch updates that miss arms (cross-check the grep
  output against the per-site decisions).
