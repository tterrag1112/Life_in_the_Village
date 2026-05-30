---
name: litv-simplification-audit
description: >
  Produce the Simplification Sweep subsection that every Life in the
  Village prompt touching an iterated subsystem (placement, decoration,
  roads, NPC) requires in its disposition. The sweep lists classes in
  scope, counts inbound callers, flags zero-caller orphans for
  deletion, and identifies overlapping-responsibility pairs for
  consolidation. Use this skill whenever a prompt's scope plausibly
  touches code under Village/Planning/, Village/Decoration/,
  Village/Roads/, or Npc/. Acting on confirmed orphans is part of the
  same change, not deferred work. Always use this skill before
  extending an iterated subsystem.
---

# Life in the Village — Simplification Audit Skill

The placement, decoration, road, and NPC subsystems have each been
iterated through 10+ phases. Earlier phases added types that later
phases superseded; some old types were never deleted. Garrett values
succinct classes and expects iterated systems to be pruned, not just
extended.

This skill produces the Simplification Sweep subsection that every
non-trivial prompt in an iterated subsystem must include in its
disposition.

## Step 0 — Determine if the sweep applies

The sweep is required when the prompt touches any of:

- `Village/Planning/` (placement)
- `Village/Decoration/` (decoration)
- `Village/Roads/` or `Village/Economy/Trade/` (roads + trade graph)
- `Npc/` (NPC rework)

For trivial surgical fixes (one-file, one-class change), the sweep can
be a single line ("Touches 1 class, 4 inbound callers — no debt").
For multi-class changes the sweep should be a real audit.

## Step 1 — Inventory classes in scope

List every class the prompt will touch, including any helper classes
the touched classes call into. Group by package.

```bash
# Example for a placement change:
ls src/main/java/tterrag1112/life_in_the_village/Village/Planning/V2/Layer3/
```

## Step 2 — Inbound caller count per class

For each class, count inbound references:

```bash
# For each class C in scope:
git grep -l "\bC\b" src/main/java | grep -v "src/main/java/.../C.java" | wc -l
# More precise (catches imports + bare references):
git grep -c "\bC\b" src/main/java | grep -v "C\.java:0$"
```

Record counts. A class with zero inbound callers is an orphan.

## Step 3 — Classify each class

For each class in scope, assign one of:

- **Active.** Multiple inbound callers; clearly load-bearing.
- **Orphan.** Zero inbound callers. Deletion candidate — propose
  removing it in the same prompt unless there's a documented reason to
  keep it.
- **Likely-orphan.** One or two inbound callers that look vestigial
  (e.g., a single test, a single legacy command). Investigate and
  propose action.
- **Overlap.** Two classes with overlapping public surface — likely
  consolidation candidate. Examples: pre-A2 `Cultures.Culture` vs
  `V2.Culture.Culture` (resolved in A2); pre-A3 `VariantSelector` vs
  `VariantPicker` (resolved in A3). Find these by name similarity +
  shared method signatures.
- **Premature abstraction.** Interface or base class with one impl
  and no near-term plan for a second. Propose collapsing.

## Step 4 — Propose actions

For each non-Active class:

- **Delete now (in scope).** Orphans go in scope by default. The
  prompt's deliverables include the deletion.
- **Consolidate now (in scope).** Overlap pairs collapsed into one
  type if the consolidation isn't enormous.
- **Defer + flag.** If the deletion or consolidation would balloon
  scope, add it to the prompt's "Out-of-scope but flagged" section
  with a note for future cleanup.

The default bias is "act on debt in scope unless there's a reason to
defer." Garrett confirmed: simplification sweep fires on every
non-trivial prompt, not just designated cleanup passes.

## Output format (drop into prompt disposition)

```markdown
### Simplification Sweep

**Classes in scope:**

| Class | Inbound callers | Status | Action |
|---|---|---|---|
| Foo | 14 | Active | (no action) |
| BarHelper | 0 | Orphan | Delete in scope |
| BazSelector | 1 | Likely-orphan | Investigate; probable delete |
| OldStyle / NewStyle | 3 / 7 | Overlap | Collapse `OldStyle` into `NewStyle` in scope |
| AbstractQux | 1 | Premature abstraction | Collapse into `ConcreteQux` |

**Out-of-scope but flagged:**
- [class or cluster that wants cleanup but balloons scope]
```

## What this skill is NOT for

- Pure documentation changes — no classes to audit.
- Brand-new subsystems with no historical iterations — there's no debt
  yet.
- Changes outside the iterated subsystem list (Village/Planning,
  Village/Decoration, Village/Roads, Npc) — a sweep usually isn't
  required for ad-hoc utility code.

## Cross-references

- [[user-simplification-preferences]] — Garrett's value framing.
- [[project-iteration-debt]] — what the debt looks like.
- [[feedback-simplification-sweep]] — the discipline rule.
- `litv-v1-survey` skill — V1-specific subset of this audit, focused
  on the placement V1 → V2 conversion footprint.

## Anti-patterns to flag in PR review

- A prompt extending an iterated subsystem with no Simplification
  Sweep subsection in disposition.
- A sweep that lists "no orphans found" without showing the grep
  used to verify.
- "Defer + flag" actions with no notes section to land in.
- Adding a new abstract base class without an Action column entry
  showing why the abstraction is justified (cross-reference
  [[feedback-no-speculative-abstractions]]).
