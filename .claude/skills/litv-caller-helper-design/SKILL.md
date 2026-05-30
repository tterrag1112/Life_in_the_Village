---
name: litv-caller-helper-design
description: >
  Design a new cross-system caller helper for Life in the Village
  when a tie-in audit reveals a recurring call pattern across 3+
  consumers. Use this skill whenever a Tie-In Audit (from the
  litv-tie-in-audit skill) surfaces "this same shape of cross-system
  call appears in N places." Output is a complete helper signature,
  the call sites it replaces, and the skill decision-table entry
  that future prompts should consult. Extends the precedent set by
  NpcBehaviorHelpers, SkillXp, NpcEconomy, VillageEconomy.
---

# Life in the Village — Caller Helper Design Skill

Garrett's design goal: "create callers that make utilizing these
interconnected systems easier." When a tie-in audit reveals a
recurring cross-system call pattern, this skill designs the helper
facade that replaces the open-coded version.

## When to use

The trigger is a Tie-In Audit (via `litv-tie-in-audit`) that surfaces:

- The same shape of cross-system call appearing in 3+ downstream
  callers, OR
- A pattern where consumers consistently get the cross-system call
  subtly wrong (omit the `+0.5` centering, skip a multiplier, write
  to the wrong layer), OR
- A new system being added whose consumers will need a clean entry
  point.

If only 1–2 call sites use the pattern, defer the helper — it
probably isn't load-bearing yet (see
[[feedback-no-speculative-abstractions]]).

## Step 0 — Confirm the precedent shape

Existing caller helpers in the codebase, with the pattern each
encodes:

- `NpcBehaviorHelpers.depositToBuilding(level, npc, building, stack)`
  — produced-item storage with building→personal fallback. Wraps
  `BuildingStorageAccess.storeWithFallback`.
- `NpcBehaviorHelpers.walkTo(npc, pos, speed)` — pathfinding with
  WALK_TARGET memory + `navigation.moveTo(x+0.5, y, z+0.5, speed)`
  centering. Does both in one call.
- `SkillXp.award(npc, skill, amount, tick)` — XP grant with mentee
  multiplier composition before `SkillComponent.addXp`.
- `NpcEconomy.businessPay / recordRevenue / marketPurchase / payWage`
  — wealth transfer with treasury + wallet + market routing baked in.
- `VillageEconomy.postListing / findCheapestSeller` — market lookup
  with stockpile fallback.

The pattern: **a single static method that takes the natural caller
arguments, encodes the right routing, and returns whatever the caller
naturally needs.** No abstract interface; no class hierarchy; no
new types added.

## Step 1 — Name the helper

Naming conventions in the existing precedent set:

- **Verb-first.** `walkTo`, `depositToBuilding`, `award`, `payWage`.
  Not `WalkUtil.do`.
- **Namespace by subsystem.** Helpers live in a class named for the
  subsystem they front: `NpcBehaviorHelpers`, `NpcEconomy`,
  `VillageEconomy`, `SkillXp`. Add to an existing namespace if the
  helper extends one; create a new one only for a new subsystem
  surface.
- **Static methods, not instance.** All existing helpers are static.

Propose: `[NamespaceClass].[verbPhrase](naturalArgs...)`.

## Step 2 — Identify call sites the helper replaces

For each downstream caller flagged by the Tie-In Audit:

- Confirm the helper signature accepts the arguments the caller has
  on hand. If a caller needs an additional argument the helper
  doesn't take, either widen the signature or split into two
  overloads. Don't force callers to construct artificial wrappers.
- Confirm the helper's return type matches what callers actually do
  with the result. If most callers ignore the return, the helper
  can return void; if any caller branches on it, return a meaningful
  value (boolean, Optional, the relevant ID).

Output a table:

```markdown
| Caller | Current open-coded shape | After helper |
|---|---|---|
| FarmerBehavior.harvest | 8 lines of raw inventory + storage | `NpcBehaviorHelpers.depositToBuilding(...)` |
| ... | ... | ... |
```

## Step 3 — Decide what the helper does NOT do

Equally important. Pin down the boundary:

- What inputs the helper does NOT validate (assumed already valid).
- What edge cases the helper does NOT handle (caller's
  responsibility — document this in the helper's javadoc).
- What return path the helper does NOT take (e.g. doesn't throw —
  returns boolean failure instead, matching the precedent of
  `walkTo`).

This prevents the helper from growing into a god-method.

## Step 4 — Compose order rule (if any)

Some helpers compose multipliers, fallbacks, or routing in a fixed
order. Document the order in the helper's javadoc:

> `// Composition order: base × specialty × mentee → addXp → cascade`

If the helper has no composition order, skip this step. If it has
one and that order is wrong, callers will silently produce wrong
results — be explicit.

## Step 5 — Update the relevant skill's decision table

Every new helper gets an entry in the SKILL.md of the subsystem
that hosts it. Pattern:

```markdown
| If you're about to write | Use instead |
|---|---|
| `[the open-coded shape]` | `[the new helper]` |
```

For NPC behaviors, the table lives in
`.claude/skills/litv-npc-behavior/SKILL.md`. For other subsystems,
create or update the matching skill.

## Step 6 — Migration scope decision

Decide whether the prompt that introduces the helper ALSO migrates
the existing call sites, or leaves migration as a flagged follow-up:

- **Migrate in the same prompt** when the call sites are few (≤5)
  and the migration is mechanical. Bundles the discipline change
  with the helper introduction.
- **Leave for follow-up** when the call sites are many (>5) or
  require per-call thought. Add a flagged follow-up to "Out-of-
  scope but flagged."

Default bias: migrate in-scope. The longer the open-coded sites
linger, the more they leak into new code.

## Output format (drop into prompt disposition)

```markdown
### Caller helper proposal

**Name:** `[NamespaceClass.verbPhrase]`
**Signature:** `[return type] [name](args...)`
**Hosting class:** `[file path]`

**Pattern encoded:**
[1–2 sentences describing the cross-system routing the helper hides]

**Composition order** (if any):
[explicit order — e.g. "base × specialty × mentee → addXp → cascade"]

**Boundary:**
- Does NOT [thing 1].
- Does NOT [thing 2].
- Caller is responsible for [thing 3].

**Call sites replaced:**

| Caller | Current shape | After helper |
|---|---|---|
| ... | ... | ... |

**Skill decision-table update:**
Add to `.claude/skills/[which-skill]/SKILL.md`:

| If you're about to write | Use instead |
|---|---|
| `[open-coded]` | `[helper]` |

**Migration scope:** [in-scope / flagged for follow-up]
```

## What this skill is NOT for

- Designing a new interface or abstract base class — that's
  speculative abstraction (see
  [[feedback-no-speculative-abstractions]]). Helpers are static
  methods, not hierarchies.
- Replacing helpers that already exist — extend the existing one.
- One-off utility methods that don't encode a cross-system pattern.

## Cross-references

- [[project-caller-helpers-workstream]] — the design goal.
- [[project-npc-behavior-canon]] — the most-developed helper set.
- [[feedback-tie-in-audit]] — the source of the trigger that brings
  you to this skill.
- [[feedback-no-speculative-abstractions]] — when not to build a
  helper.
- `litv-tie-in-audit` skill — generates the audit this skill consumes.
- `litv-npc-behavior` skill — the canonical example skill that
  hosts a decision table of helpers.

## Anti-patterns to flag in PR review

- A new helper that adds a new interface or class hierarchy instead
  of a static method on an existing or new utility class.
- A helper without a Boundary section (what it does NOT do).
- A helper introduced without updating the relevant skill's
  decision table.
- A helper that takes wrapper / context types nobody else
  constructs — the helper signature should accept the natural
  caller arguments.
- A migration "in-scope" claim that leaves >5 call sites unmigrated.
