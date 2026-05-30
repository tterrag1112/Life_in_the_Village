---
name: litv-v1-survey
description: >
  Produce a current snapshot of remaining V1 placement vocabulary in
  the Life in the Village codebase, with each file categorised as
  deletable / converts-to-V2-X / archival. Use this skill whenever a
  prompt is about to convert V1 placement code, or whenever Garrett
  asks for "where is V1 still alive" or "what's left of the V1
  cleanup." Output is a queueable list of conversion tasks. The V1 →
  V2 migration is ongoing, not complete; this skill is the periodic
  audit that drives the next round of conversions.
---

# Life in the Village — V1 Survey Skill

Track A1b (2026-05-08) deleted the identified V1 placement machinery,
but the V1 → V2 conversion is ongoing. Decoration, road, NPC, and
command surfaces still call into V1-flavored patterns in places.
Garrett's direction: full conversion, convert-then-delete, no
perpetual coexistence. This skill produces the current V1 footprint
snapshot.

## When to run

- Before a Track E1 placement-retuning pass.
- Before any prompt that converts V1 code to V2.
- Periodically (quarterly-ish) to keep the conversion queue current.
- When Garrett asks "where is V1 still alive."

## Step 1 — Grep the V1 vocabulary

```bash
# V1 placement vocabulary list:
PATTERNS=(
  "ShapeRecipe"
  "LayoutPrimitive"
  "ZoneRegistry"
  "PlacementMatcher"
  "SlotIntention"
  "SlotEmitter"
  "AnchorKind"
  "VillageRoadNetwork"
  "BuildSiteFinder"
  "PlanContext\.claimByZone"
  "cascadeChainPosition"
  "RecipeStatus"
  "RecipeHelpers"
)

for p in "${PATTERNS[@]}"; do
  echo "=== $p ==="
  git grep -l "$p" src/main/java | grep -v archived
done
```

Adjust the pattern list when new V1 names surface, or when known V1
names disappear (because the conversion completed for that token).

## Step 2 — Categorise each file

For each file containing V1 vocabulary, assign one of:

- **Deletable.** The file is V1 machinery that has no surviving
  consumer. Propose deletion in the next conversion prompt.
- **Converts to V2-X.** The file is a tie-in calling into V1 from a
  still-active subsystem (decoration, road, NPC, command). Plan a
  conversion: route the call through V2 instead, then verify the V1
  source becomes deletable.
- **Archival reference.** The file is a doc / spec under
  `docs/zoningandlayout_redesign/` or similar. Keep but mark
  superseded; no code action.
- **False positive.** The token appears for unrelated reasons
  (e.g. a generic word). Note and exclude.

## Step 3 — Build the conversion queue

Order tie-ins by impact (high-traffic surfaces first) and by V2
readiness (some V2 surfaces aren't ready yet — note dependencies).

## Output format

```markdown
### V1 Survey — 2026-MM-DD

**V1-vocabulary inventory:**

| Token | Files | Category breakdown |
|---|---|---|
| ShapeRecipe | 14 | 2 deletable, 9 convert-to-V2, 3 archival |
| LayoutPrimitive | 8 | 0 deletable, 6 convert-to-V2, 2 archival |
| ZoneRegistry | 0 | clean — A1b deletion held |
| ... | ... | ... |

**Conversion queue (priority order):**

1. `VillageDecorator.composeRoads` — calls `ShapeRecipe.forShape`.
   Convert to `V2.Layer4.RoadComposer`. Estimated scope: 1 file,
   ~30 lines.
2. `/litv recipe-debug` command — reads `LayoutPrimitive` directly.
   Convert or remove (legacy debug command, low-priority).
3. ...

**Files marked archival:**
- `docs/zoningandlayout_redesign/00-PLACEMENT-OVERVIEW.md`
- `docs/zoningandlayout_redesign/PLACEMENT-REWORK-STATE.md`
- ...

**V1-era skills (also archival):**
- `.claude/skills/litv-layout-recipe/SKILL.md`
- `.claude/skills/litv-layout-primitive/SKILL.md`
- `.claude/skills/litv-road-primitive/SKILL.md`
- `.claude/skills/litv-shape-rule/SKILL.md`
```

## What this skill is NOT for

- Auditing non-placement subsystems for iteration debt — use
  `litv-simplification-audit` instead.
- Authoring the V2 successor — use `litv-caller-helper-design` if a
  helper is needed, or the relevant per-subsystem skill.
- Designing the V1 → V2 conversion of a specific tie-in — that's a
  per-conversion prompt; this skill produces the queue, not the
  execution plan.

## Cross-references

- [[project-v1-v2-layout]] — current conversion status.
- [[project-v1-v2-conversion-strategy]] — convert-then-delete shape.
- [[project-iteration-debt]] — the broader debt this is a slice of.
