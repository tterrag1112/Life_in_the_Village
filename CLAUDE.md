# Life in the Village — Claude Code context

This is a NeoForge 1.21 Minecraft mod. Village and kingdom simulation, procedural worldgen, NPC professions.

## Before doing anything on the road system

1. Read `ROADS_PLAN.md` — the canonical plan. Human-managed. Do not edit without explicit permission.
2. Read `ROADS_PROGRESS.md` — current state. Append-only log. You add entries at the end of sessions.
3. Honor the invariants section of `ROADS_PLAN.md`. They are not suggestions.

## Firm architectural constraints (apply everywhere, not just roads)

- No abstract method renames on existing interfaces.
- Extend behavior through base-class hooks and shared helpers, not per-subclass repetition.
- Planning-layer correctness over realiser heroics: when failures cluster, fix the planner, not the realiser.
- New tags/enums/primitives only when a concrete consumer needs the distinction.

## When in doubt

Ask before restructuring. Ask before deleting. Ask before "improving" documentation files.