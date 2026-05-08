# UNIFIED REWORK PROGRESS

Append-only log. Most recent entry at bottom.

Status values: `Not-Started`, `In-Progress`, `Implemented`, `Tested`,
`Done`. `Done` means: implemented, tested in-world, no known issues,
spec matches reality.

## Track A — Placement consolidation

| ID | Task | Status | Notes |
|---|---|---|---|
| A1 | V1 cleanup + ZoneRegistry migration | Not-Started | Gates everything. Unblocks decoration P0a-18. |
| A2 | Culture unification | Not-Started | Depends A1. |
| A3 | Variant unification | Not-Started | Depends A2. User to investigate codepaths first. |
| A4 | VillageSpawner → MinimalSpawner wiring | Not-Started | Depends A3. |
| A5 | Measurement run vs V1 baseline | Not-Started | Depends A4. |

## Track B — Decoration finishing

| ID | Task | Status | Notes |
|---|---|---|---|
| B1-15 | HOUSE pilot NBTs (P0a-15) | Not-Started | Hard-fails placement until landed. |
| B1-16 | URBAN variant pack (P0a-16) | Not-Started | |
| B1-04 | MarketStallPlacer subbuilding migration (P0d-04) | Not-Started | |
| B1-12 | GuildHall colour fields | Not-Started | Wires P0a-12 overrides. |
| B2-pass | V2 vocabulary pass on docs 05–11 | Not-Started | Doc-only. Depends A4. |
| B2-05 | Street furniture impl | Not-Started | P1-06..08. |
| B2-06 | Signs and markers impl | Not-Started | P1-09..13. |
| B2-07 | Industry adjuncts impl | Not-Started | P2-01..06. |
| B2-08 | Herb and cottage gardens impl | Not-Started | P2-07..11. |
| B2-09 | Parks and gardens impl | Not-Started | P3-01..04. |
| B2-10 | Farm plot rework impl | Not-Started | P3-05..10. |
| B2-11 | Homesteading impl | Not-Started | P3-11..16. NPC Phase 3+4 already shipped. |
| B3-12-doc | Walls spec rewrite | Not-Started | V2 vocabulary. |
| B3-13-doc | Festivals spec rewrite | Not-Started | V2 vocabulary. |
| B3-14-doc | Cemeteries spec rewrite | Not-Started | V2 vocabulary. |
| B3-12-impl | Walls implementation | Not-Started | Depends B3-12-doc. |
| B3-13-impl | Festivals implementation | Not-Started | Depends B3-13-doc + NPC Phase 5 events (shipped). |
| B3-14-impl | Cemeteries implementation | Not-Started | Depends B3-14-doc. |

## Track C — Roads finishing

| ID | Task | Status | Notes |
|---|---|---|---|
| C1-tr | TradeRoad.java deletion | Not-Started | Migration complete; source is dead code. |
| C1-cv | TravellingGroupEngine synthetic-caravan fix | Not-Started | Carryover from Roads Phase 3b. |
| C2 | Phase 7f Slice 4 connector routing | Not-Started | Depends A4. |
| C3-11 | Phase 11 — player-initiated road construction | Not-Started | |
| C3-12 | Phase 12 — POI subroads | Not-Started | |
| C3-13 | Phase 13 — sea route unification | Not-Started | Folds SeaRoute into world graph. |

## Track D — Kingdom rework

| ID | Task | Status | Notes |
|---|---|---|---|
| D1-01 | Culture kingdom-tier fields | Not-Started | Depends A2. |
| D1-02 | KingdomEventBus peer | Not-Started | Mirror NpcLifeEventBus. |
| D1-03 | Stability scalars | Not-Started | |
| D1-04 | Territory vs membership split | Not-Started | |
| D1-05 | Legitimacy scalar | Not-Started | |
| D1-06 | Estate primitives | Not-Started | |
| D1-07 | Heraldry generator | Not-Started | |
| D1-08 | Office stub completion (7 offices) | Not-Started | Chancellor / Scholar / General / Magistrate / Spymaster / Treasurer / Diplomat. |
| D2 | Section 5 rewrite | Not-Started | Doc-only. Depends A4. |
| D3-1 | Phase 1 — worldgen rewrite | Not-Started | Depends A4 + D2. |
| D3-2 | Phase 2 — houses, ranks, nobility | Not-Started | Depends D3-1. |
| D3-3 | Phase 3 — provinces & offices | Not-Started | Depends D3-2. |
| D3-4 | Phase 4 — laws & intrigue | Not-Started | Depends D3-3. |
| D3-5 | Phase 5 — player experience | Not-Started | Depends D3-4. |
| D3-6 | Phase 6 — decline, conflict, religion-as-authority | Not-Started | Depends D3-5. |
| D3-7 | Phase 7 — polish, scale, longevity | Out-of-scope | Per master plan. |

## Carryover from absorbed plans (not re-tracked here)

- NPC Phase 5 task 33 (textures) — `NPC_PROGRESS.md`.
- NPC Phase 5 task 34 (content pass) — `NPC_PROGRESS.md`.
- NPC deferrals (Office tab GUI, Quest→Request migration, GuildData
  rename, Refugee leader UI, OfficeChange emission, etc.) —
  `NPC_PROGRESS.md`.

---

## Log

### 2026-05-07 — Plan committed

Unified plan written. All four tracks defined. No code changes yet.

Next: A1 — V1 cleanup + ZoneRegistry migration.