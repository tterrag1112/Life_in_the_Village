# Life in the Village — PROGRESS

The single append-only ship log for the mod. This replaces the seven
per-rework logs (UNIFIED_REWORK / NPC / ROADS / DECORATION / KINGDOM /
MERCHANT / PRIEST), all of which are archived under `docs/archive/` as
history.

## Format contract

**Append-only.** Add a new entry at the **bottom** per shipped phase. Never
edit a prior entry. Each entry uses this shape:

```
## <date> — <track/phase id> — <one-line title>

**Disposition.** The investigation pass: what was read, what the current
state was, the tie-in audit findings, and the plan before code.

**What shipped.** The concrete deliverables.

**Files.** New / changed / deleted, by path.

**Tie-in audit.** Upstream feeders, downstream callers (each dispositioned —
updated / flagged out of scope / unaffected), sibling systems, exhaustive
switches if enums/sealed types were touched.

**Deviations from prompt.** What was done differently from the ask, and why.

**Out-of-scope but flagged.** Things noticed but deliberately not touched.

**Smoke-test plan.** Numbered, user-executable in-world steps.

**Build verification.** Result, or: "Build verification deferred (sandbox
blocks maven.neoforged.net)."
```

Tracks and phase ids are defined in `ROADMAP.md`. Invariants that constrain
every entry live in `INVARIANTS.md`.

---

## 2026-06-10 — J1 — Documentation replacement

**Disposition.** The root markdown set had gone majority-stale: several docs
described deleted systems (the V1 pipeline, AdjunctPlot) as current; several
"blocked/deferred" rows described work shipped weeks earlier. A ground-truth
audit (five parallel code-vs-docs passes against tip `748c3e1`) confirmed the
replacement was justified. The new structure is four root docs +
`docs/archive/`.

**What shipped.** The root markdown set is replaced wholesale:

- `STATE.md` — as-built systems overview, present tense, regenerated per
  milestone (from the audit, framing dropped).
- `ROADMAP.md` — the adopted forward plan, human-managed (from the roadmap
  final draft; "draft" framing and satisfied meta-sections removed).
- `PROGRESS.md` — this file; the single append-only log with the format
  contract above.
- `INVARIANTS.md` — one home for the roads invariants, the firm architectural
  constraints, the still-in-force locked decisions, districts-by-default, the
  religion era-2 locks, and the sim-ledger principles.
- `CLAUDE.md` — revised (scoped): the "before rework" reading list now points
  at the four new docs; the stale `MinimalSpawner` reference is corrected to
  `V2VillageSpawnerAdapter.spawn`; the V1→V2 section keeps only the
  convert-on-sight principle and points at `INVARIANTS.md`; all PROGRESS-log
  references collapse to the single `PROGRESS.md`. Policy content is unchanged.

**Files.**

*New (repo root):* `STATE.md`, `ROADMAP.md`, `PROGRESS.md`, `INVARIANTS.md`.
*New:* `docs/archive/README.md`.
*Changed:* `CLAUDE.md`.
*Moved to `docs/archive/`:* `LAYOUT_OVERVIEW.md`, `UNIFIED_REWORK_PLAN.md`,
`UNIFIED_REWORK_PROGRESS.md`, `NPC_PLAN.md`, `NPC_PROGRESS.md`,
`ROADS_PLAN.md`, `ROADS_PROGRESS.md`, `DECORATION_PLAN.md`,
`DECORATION_PROGRESS.md`, `KINGDOM_PROGRESS.md`, `MERCHANT_PROGRESS.md`,
`PRIEST_PROGRESS.md`, `CONSOLIDATION_INVENTORY.md`, `docs/KINGDOM_PLAN.md`,
`docs/V2_OVERVIEW.md`, `docs/zoningandlayout_redesign/`,
`docs/decoration_redesign/`, `docs/npc_redesign/`.
*Kept in place:* `README.md`, `docs/HEADLESS_HARNESS.md`,
`docs/v2-dump-samples/`, `harness/`.

### Mapping table — where everything went

| Old file | New home |
|---|---|
| `LAYOUT_OVERVIEW.md` | `docs/archive/` (V2 picture → `STATE.md`) |
| `UNIFIED_REWORK_PLAN.md` | `docs/archive/` (sequencing → `ROADMAP.md`; locked decisions → `INVARIANTS.md`) |
| `UNIFIED_REWORK_PROGRESS.md` | `docs/archive/` (log continues in `PROGRESS.md`) |
| `NPC_PLAN.md` / `NPC_PROGRESS.md` | `docs/archive/` (NPC state → `STATE.md`; open tasks 33/34 → `ROADMAP.md` H/I) |
| `ROADS_PLAN.md` | `docs/archive/` (12 invariants → `INVARIANTS.md`; state → `STATE.md`) |
| `ROADS_PROGRESS.md` | `docs/archive/` (log continues in `PROGRESS.md`) |
| `DECORATION_PLAN.md` / `DECORATION_PROGRESS.md` | `docs/archive/` (decoration folded into district passes per `ROADMAP.md`) |
| `KINGDOM_PROGRESS.md` | `docs/archive/` (state → `STATE.md`; Phase 7 + unwired fields → `ROADMAP.md` C) |
| `MERCHANT_PROGRESS.md` | `docs/archive/` (state → `STATE.md`) |
| `PRIEST_PROGRESS.md` | `docs/archive/` (state → `STATE.md`; frontier F2b-2 → `ROADMAP.md` B) |
| `CONSOLIDATION_INVENTORY.md` | `docs/archive/` (superseded by the audit; cleanup list → `STATE.md`) |
| `docs/KINGDOM_PLAN.md` | `docs/archive/` (Phase 7 → `ROADMAP.md` C) |
| `docs/V2_OVERVIEW.md` | `docs/archive/` (current V2 → `STATE.md`) |
| `docs/zoningandlayout_redesign/` | `docs/archive/` (superseded) |
| `docs/decoration_redesign/` | `docs/archive/` (survivors need respec; see `ROADMAP.md`) |
| `docs/npc_redesign/` | `docs/archive/` (33/34 are the open specs; `ROADMAP.md` H/I reference the archive paths) |

**Tie-in audit.** Docs-only change; no code touched. *Downstream callers of
the moved docs:* `CLAUDE.md` was the only in-repo file referencing the retired
plan/progress files by name — repointed in scope. The `docs/archive/README.md`
and `ROADMAP.md` Track H/I both reference the archived `npc_redesign/33`/`34`
paths intentionally (the specs are still open). No source code references the
moved markdown. *Sibling:* the `.claude/planning/` content sources (01, 05,
06) live untracked in Garrett's working tree and are not part of this commit.

**Deviations from prompt.**
- `CLAUDE.md`'s "before rework" section named `UNIFIED_PLAN.md` /
  `UNIFIED_PROGRESS.md`, but the actual files were `UNIFIED_REWORK_PLAN.md` /
  `UNIFIED_REWORK_PROGRESS.md`. The revision points at the new four-doc set
  regardless, so the discrepancy is moot — noted here for the record.
- The audit (source 01) cited the spawner as `V2VillageSpawnerAdapter` and
  CLAUDE.md cited `MinimalSpawner`. Re-verified against source:
  `VillageSpawner.java` references `V2VillageSpawnerAdapter`; no
  `MinimalSpawner` class exists. CLAUDE.md and INVARIANTS.md both use the live
  name.

**Out-of-scope but flagged.**
- The V1-era `litv-*` skills (open decision J1/register-#6: refresh vs delete)
  are untouched — `.claude/` was outside this change's scope.
- The `SacredSpaceRule` stray-import fix and the `gradlew.bat` modification
  (Gate-0 working-tree items) are not part of this docs commit.

**Smoke-test plan.**
1. From the repo root, confirm only `CLAUDE.md`, `README.md`, `STATE.md`,
   `ROADMAP.md`, `PROGRESS.md`, `INVARIANTS.md` are top-level `.md` files.
2. Confirm `docs/` contains `HEADLESS_HARNESS.md`, `archive/`, and
   `v2-dump-samples/` only.
3. Open `CLAUDE.md`; confirm the "before rework" list names the four new docs
   and no retired file is referenced anywhere.
4. Spot-check `docs/archive/README.md` links resolve to siblings.

**Build verification.** N/A — documentation-only change, no compilation unit
touched.
