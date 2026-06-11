# Archived documentation

These files were retired on **2026-06-10** when the root markdown set was
replaced wholesale. They are kept for historical reference and git
archaeology only — they are **not** maintained and describe the mod as it
was, not as it is.

Several describe systems that have since been deleted (the V1 placement
pipeline, AdjunctPlot decoration) or work that shipped far beyond what the
docs claim (roads through Phase 13, kingdom through Phase 6, the religion
arc ~40 phases past its original spec). Do not treat anything here as
ground truth.

For the current picture, read the live root docs instead:

- **`STATE.md`** — as-built systems overview, regenerated per milestone.
- **`ROADMAP.md`** — forward plan (human-managed).
- **`PROGRESS.md`** — the single append-only ship log.
- **`INVARIANTS.md`** — non-negotiables (roads invariants, architectural
  constraints, locked decisions).

## What's here and why

| Archived path | Was | Retired because |
|---|---|---|
| `LAYOUT_OVERVIEW.md` | Root layout overview | Described the deleted V1 pipeline end-to-end. |
| `UNIFIED_REWORK_PLAN.md` | Cross-rework sequencing plan | Tracks A/C/D essentially done; superseded by `ROADMAP.md`. Locked decisions carried into `INVARIANTS.md`. |
| `UNIFIED_REWORK_PROGRESS.md` | Unified ship log | Accurate through 2026-06-06 but Track B table contradicted by its own later entries. Superseded by `PROGRESS.md`. |
| `NPC_PLAN.md` / `NPC_PROGRESS.md` | NPC rework plan + log | Phases 0–4 + liveliness L0–L6 shipped; open remainder (tasks 33/34, culture wiring) folded into `ROADMAP.md`/`STATE.md`. |
| `ROADS_PLAN.md` / `ROADS_PROGRESS.md` | Roads canonical plan + log | Plan understated reality by 4 phases; ~900 lines of log describe since-deleted systems. The 12 invariants live in `INVARIANTS.md`. |
| `DECORATION_PLAN.md` / `DECORATION_PROGRESS.md` | Decoration plan + log | Phases 1–4 predate the district-era substrate and need respec; Phase 0 ledger contradicted by code (AdjunctPlot deleted). |
| `KINGDOM_PROGRESS.md` | Kingdom ship log | Accurate through Phase 6; Phase 7 + unwired fields carried into `ROADMAP.md`. |
| `MERCHANT_PROGRESS.md` | Merchant arc log | Arc complete and accurate; history only. |
| `PRIEST_PROGRESS.md` | Religion/priest ship log | The live frontier log until F2b-2; archived as the new `PROGRESS.md` takes over. |
| `CONSOLIDATION_INVENTORY.md` | Consolidation inventory | Superseded by the 2026-06-10 state-of-the-mod audit. |
| `KINGDOM_PLAN.md` (was `docs/`) | Kingdom rework plan | Phases 0–6 done; Phase 7 + unwired-field list carried into `ROADMAP.md`. |
| `V2_OVERVIEW.md` (was `docs/`) | V2 planner overview | Layer table still broadly right but predates all district work; claimed V1 "parked" (it's deleted). Current V2 picture lives in `STATE.md`. |
| `zoningandlayout_redesign/` (was `docs/`) | Placement-rework design set | Explicitly superseded; code agrees. |
| `decoration_redesign/` (was `docs/`) | Decoration design set | 00–04/15–16 describe shipped/changed systems; 05–14 are unbuilt specs against a vanished substrate (respec needed). |
| `npc_redesign/` (was `docs/`) | NPC redesign spec set (00–34) | 00–32 shipped; 33 (appearance) and 34 (content pass) are the two still-open specs — `ROADMAP.md` (Tracks H/I) references their archive paths. |

Retired 2026-06-10. See `STATE.md` / `ROADMAP.md` at the repo root.
