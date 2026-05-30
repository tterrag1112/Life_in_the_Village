---
name: litv-claude-code-prompt
description: >
  Produce a complete, well-shaped prompt to hand to Claude Code for
  Life in the Village mod work. Use this skill whenever Garrett asks
  for "a prompt for Claude Code" — even informally ("write me a
  prompt to add X"). The template encodes disposition-before-code,
  tie-in audit, simplification sweep, user-confirmed scope, out-of-
  scope-but-flagged, deliverables, smoke-test plan, and the
  build-verification disclosure. This is for me (the Cowork chat
  instance), not Claude Code itself — it's the prompt-writer's
  reference, not a Claude Code skill.
---

# Cowork-side — Claude Code Prompt Template Skill

This skill is for me to use when Garrett asks for a Claude Code prompt.
It encodes the shape every prompt should take so I don't drift between
templates across sessions.

## When to use

Whenever the deliverable for Garrett is "a self-contained prompt that
he will paste into a Claude Code session." This is the most common
deliverable in this Cowork space; see [[user-role]].

Skip the template only for:
- Pure conversation / planning discussion that won't be handed off.
- One-line follow-up corrections to an already-in-flight Claude Code
  session.

## Required structure

Every prompt I produce has these sections, in order:

### 1. Heading + summary

```markdown
# [Phase/Track tag] — [brief subject]

[1–2 sentences describing the goal of this prompt in plain English.]
```

### 2. Disposition / Investigation pass

```markdown
## Disposition

Before drafting any code, read:
- [file 1] — [why]
- [file 2] — [why]
- ...

Surface findings on:
- [question 1]
- [question 2]
- ...

If [specific finding], propose [path A]. If [other finding], propose
[path B]. If neither, ask before proceeding.
```

Even for surgical fixes, the disposition section is mandatory — see
[[feedback-disposition-before-code]].

### 3. Tie-In Audit

Apply the `litv-tie-in-audit` skill template here. Upstream feeders,
downstream callers, sibling systems, exhaustive switches. Even one-
line summaries for surgical fixes. Skipping this is the #1 root cause
of regressions — see [[feedback-tie-in-audit]].

### 4. Simplification Sweep (if touching iterated subsystems)

If the prompt touches `Village/Planning/`, `Village/Decoration/`,
`Village/Roads/`, or `Npc/`, apply the `litv-simplification-audit`
skill template. Otherwise, omit. See
[[feedback-simplification-sweep]].

### 5. User-confirmed scope

```markdown
## Scope (confirmed with Garrett)

- [decision 1]
- [decision 2]
- ...
```

These are the decisions Garrett has signed off on. Claude Code does
not deviate from them without flagging. See
[[feedback-deviations-explicit]].

### 6. Out-of-scope but flagged

```markdown
## Out-of-scope but flagged

- [adjacent thing 1] — [why deferred + where it should be tracked]
- ...
```

### 7. Invariants honoured

```markdown
## Invariants honoured

- No abstract-method renames on existing interfaces.
- Planning-layer correctness over realiser heroics.
- New tags/enums/primitives only when a concrete consumer needs the
  distinction.
- [any phase-specific invariants from the relevant PLAN]
```

Cross-references: [[feedback-no-abstract-renames]],
[[feedback-planner-over-realiser]],
[[feedback-no-speculative-abstractions]].

### 8. Deliverables

```markdown
## Deliverables

1. [code change 1]
2. [code change 2]
3. ...
N-1. Append an entry to [the right PROGRESS log] using the
     `litv-progress-log-entry` skill. Entry includes:
     - What shipped
     - Deviations from prompt
     - Out-of-scope but flagged
     - Smoke-test plan
     - Build verification disclosure
N. [optional: pre-commit final check]
```

### 9. Smoke-test plan placeholder

If user-visible, name the smoke-test steps the PROGRESS entry should
include. Garrett executes them in-world; see
[[feedback-testing-workflow]].

### 10. Reference the relevant skills

```markdown
## Use these skills

- `litv-tie-in-audit` — for the disposition's Tie-In Audit subsection.
- `litv-simplification-audit` — if touching iterated subsystems.
- `litv-progress-log-entry` — for the PROGRESS log entry.
- `litv-<subsystem>` — the per-subsystem skill (e.g.
  `litv-npc-behavior` for NPC behavior code, `litv-village-type-datagen`
  for new village types).
```

## Optional sections (use when applicable)

- **Background.** When the prompt context is non-obvious from a fresh
  CLAUDE.md read, give Claude Code a paragraph of background.
- **References.** Specific lines or doc sections to read.
- **Constraints.** Performance, save-compat, ordering — any constraint
  not captured by the standard invariants.
- **Pre-commit final check.** If this is the last prompt in a batch
  before Garrett tests, instruct Claude Code to do nothing but
  re-verify preflight items and confirm the PROGRESS log entry is
  shaped. See [[feedback-testing-workflow]].

## Style conventions

- **Self-contained.** A Claude Code session reads CLAUDE.md plus the
  prompt; assume nothing else is in context.
- **Concrete file paths.** Always
  `src/main/java/tterrag1112/life_in_the_village/...` not abbreviated.
- **Date the prompt** when it's part of a sequence Garrett will batch.
- **Don't promise specific numbers** (token counts, file counts) —
  Claude Code does the work, the prompt frames it.

## What this skill is NOT for

- Writing CLAUDE.md or memory files — different shape, different
  audience.
- Drafting per-doc spec text (under `docs/npc_redesign/` etc.) —
  Garrett authors those.
- Writing skills — see `skill-creator` for new skill authoring; this
  one is for Claude Code prompts.

## Cross-references

- [[user-role]] — the plan-then-prompt workflow.
- [[user-collaboration-style]] — what Garrett looks for in a prompt.
- [[feedback-disposition-before-code]] — required section.
- [[feedback-tie-in-audit]] — required section.
- [[feedback-simplification-sweep]] — required when touching iterated
  systems.
- [[feedback-deviations-explicit]] — required PROGRESS-entry sections.
- [[feedback-build-verification-disclosure]] — required PROGRESS-entry
  line.
- [[feedback-testing-workflow]] — smoke-test plan + pre-commit final
  check.
- [[reference-skills]] — what the per-subsystem skills cover.
