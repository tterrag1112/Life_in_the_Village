---
name: litv-progress-log-entry
description: >
  Produce the bottom-of-progress-log entry that every Life in the
  Village phase ships with. Use this skill at the end of every Claude
  Code prompt that produces a shipped change — the entry has a
  consistent shape (date-tagged header, what shipped, deviations
  section, out-of-scope-but-flagged section, cumulative pending
  verification, smoke-test plan, build verification disclosure). The
  PROGRESS files are append-only logs; prior entries are never edited.
  Always use this skill instead of free-styling the entry shape.
---

# Life in the Village — Progress Log Entry Skill

Every PROGRESS log (UNIFIED_REWORK_PROGRESS.md, NPC_PROGRESS.md,
ROADS_PROGRESS.md, DECORATION_PROGRESS.md, KINGDOM_PROGRESS.md) is
append-only. Each shipped phase or sub-phase adds one entry at the
bottom; prior entries are never edited. The entry shape is consistent
across 100+ shipped entries — this skill produces a correctly-shaped
entry every time.

## Step 0 — Identify which PROGRESS log

The change determines which log gets the entry:

- Cross-track or unified-plan work → `UNIFIED_REWORK_PROGRESS.md`.
- NPC-only phase or task → `NPC_PROGRESS.md`.
- Road-only phase → `ROADS_PROGRESS.md`.
- Decoration-only phase → `DECORATION_PROGRESS.md`.
- Kingdom-only phase → `KINGDOM_PROGRESS.md`.

If a change spans multiple tracks, the entry goes in UNIFIED with
brief cross-references in the per-track logs (one or two lines, not a
full duplicate).

## Step 1 — Heading

```markdown
### YYYY-MM-DD — [Track/Phase tag] landed ([brief subject])
```

Examples from the logs:
- `### 2026-05-08 — A2 + A3 landed (one cycle)`
- `### 2026-05-09 — Track C3.3 landed (sea route unification); Track C complete`
- `### 2026-05-23 — Detour A — Prompt B complete — Detour A FULL SHIPS`

For surgical fix-ups, suffix with the fix-up tag:
- `### 2026-05-08 — B2.9 landed (round-two diagnostic + perf fixes)`

## Step 2 — Required subsections (in order)

```markdown
### YYYY-MM-DD — [Heading]

**What shipped:** [1–3 paragraph summary of the change]

**Surface area:** ~N new files in [path] + N edits to existing files +
N deletions.

**Files added:** [list, or grouped by package]
**Files modified:** [list, or grouped]
**Files deleted:** [list, if any]

**Deviations from prompt:**
- [where Claude Code chose differently than the prompt asked + why]
- [or "None." if literally no deviations]

**Out-of-scope but flagged:**
- [adjacent things deliberately deferred for later]
- [or "None." if nothing surfaced]

**Cumulative pending verification:** [the full set of phases that
haven't been smoke-tested in-world yet — typically appended to the
prior entry's list, with the new phase added]

**Smoke test plan (user-executable):**
1. [step-by-step instructions Garrett can run in-world]
2. [each step ends with what to expect / observe]
3. ...

**Build verification:** [Almost always one of these two:]
- "Build verification deferred (sandbox blocks
  maven.neoforged.net)."
- "Gradle artifact download unavailable. Full manual static review
  completed."

**Carryovers (unchanged from prior sessions):** [if applicable —
unresolved items from earlier phases that this entry didn't address;
appended forward from prior entry]
```

## Step 3 — Optional subsections (use when applicable)

- **Track E queue (post-[track] follow-ups):** items surfaced for
  Track E that aren't blocking this phase.
- **Known limitations carried forward:** quirks the user should know
  about; not blocking but worth flagging.
- **Cumulative [track] stats:** total commits, files touched, lines
  changed (used for big multi-prompt shipments).
- **Ship status:** "[Phase X] COMPLETE." or "Ready for in-world smoke
  test + [follow-up]."

## Step 4 — Style conventions

- **Append, never reorder.** New entry goes at the very bottom of the
  file.
- **Don't edit prior entries.** If a finding from this phase
  invalidates a claim in a prior entry, note it in this entry's
  "Deviations from prompt" or "Cumulative pending verification" — do
  not retro-edit the prior entry.
- **Concrete file paths.** Use full paths from
  `src/main/java/tterrag1112/life_in_the_village/...` when listing
  files; relative paths get ambiguous fast.
- **Per-user-direction tags.** When a non-obvious decision was
  Garrett's call, tag it: "Per user direction." or "(Path B per user
  direction)." This is how the audit trail of who-decided-what
  survives.

## Output format

The full entry block (Steps 1–3 above) is the output. It goes in the
deliverables list of the prompt — Claude Code appends it to the
relevant PROGRESS log as the last action before commit.

## What this skill is NOT for

- Per-doc Revision Notes (in spec docs under `docs/npc_redesign/` or
  `docs/decoration_redesign/`) — those have their own template per
  `00-conventions.md` in each subsystem.
- PLAN doc updates — PLAN docs are human-managed; do not propose
  edits.
- CLAUDE.md or README updates — different file, different conventions.

## Cross-references

- [[feedback-deviations-explicit]] — why the deviations + out-of-scope
  sections are mandatory.
- [[feedback-human-managed-plans]] — PROGRESS is append-only; PLAN is
  read-only.
- [[feedback-build-verification-disclosure]] — the standard sandbox
  disclosure line.
- [[feedback-testing-workflow]] — smoke-test plans are how Garrett
  decides when to run an in-world test.

## Anti-patterns to flag in PR review

- A PROGRESS entry that edits a prior entry instead of appending.
- A PROGRESS entry with no "Deviations from prompt" section.
- A PROGRESS entry with no smoke-test plan when the change is
  user-visible.
- A PROGRESS entry that omits the build-verification disclosure.
- An entry in the wrong PROGRESS log (e.g. an NPC-only change going
  into UNIFIED for no reason).
