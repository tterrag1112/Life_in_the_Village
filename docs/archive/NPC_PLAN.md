# NPC Redesign — Master Plan

Status: **design complete** — 34-doc spec finished in
`docs/npc-redesign/`. Implementation begins with Phase 0.

This plan covers the full NPC redesign: personhood systems (traits,
memory, knowledge, mood, skills, goals, dialogue, verbs, life arcs,
hobbies), social fabric (NPC relationships, gossip, apprenticeship,
scribal roles, letters, children/elderly), political/economic layers
(offices, laws, crime, religion, medicine, channels, business-front),
specialization and inter-village flow (resource categories,
companies, guilds, requests, visitors, history), and polish (cultures
wired, events, appearance, content pass).

For progress tracking see `NPC_PROGRESS.md`.

---

## Locked-in design decisions

These are the core choices the spec is built on. Any deviation
requires re-review before implementation.

- **8 trait axes** (Industry, Courage, Sociability, Generosity,
  Honesty, Ambition, Compassion, Temperance), bipolar −1..+1
  continuous. Display at |v| ≥ 0.40 normal, ≥ 0.85 emphatic.
- **Memory**: 32-entry cap, value-weighted decay, pinned at
  initialValue ≥ 90, refresh-on-reminder. Situational events only —
  NOT marriages/employment (those persist elsewhere).
- **Knowledge**: 4 categories (PERSONAL, LOCAL, REGIONAL, FOREIGN),
  fidelity 0..1 degrades 0.20 per retelling, deterministic content
  mutation.
- **Mood**: single −100..+100 scalar, trait-modulated, trait-
  influenced baseline, 15%/day decay toward baseline.
- **Skills**: 8 (FARMING, CRAFTING, COMBAT, COMMERCE, SOCIAL,
  LITERACY, SURVIVAL, MEDICINE). Persist across profession changes.
- **Offices**: 1-per-organization for player. Selection methods:
  MERITOCRATIC, ASCENSION, COUNCIL, ELECTIVE, HEREDITARY, APPOINTED,
  DICTATORIAL.
- **Information asymmetry**: NPCs don't know everything; knowledge
  requires witness/rumor/education.
- **4 hardcoded starter cultures**: Plainfolk, Highmarch, Silkwood,
  Tidereach. Cultures are *biases*, never *locks*.
- **No warfare in v1.**
- **No JSON content day one** — hardcoded cultures, events, books,
  dialogue; JSON packs are Phase 6.
- **No trade-road work included** — user has that rework in progress
  separately.

---

## Phase structure

| Phase | Theme | Docs | Status |
|---|---|---|---|
| 0 | Skeleton (storage-only) | 00–06 | design complete, impl not started |
| 1 | Personhood | 07–10 | design complete |
| 2 | Social fabric | 11–18 | design complete |
| 3 | Offices & adaptive economy | 19–24 | design complete |
| 4 | Specialization & inter-village | 25–30 | design complete |
| 5 | Polish & content | 31–34 | design complete |
| 6 | Future | — | deferred |

Each phase builds on the previous; ordering is important because
later systems consume events, data, or components introduced
earlier.

---

## Phase → subsystem mapping

### Phase 0 — Skeleton

Storage-only infrastructure. No new behavior; new components land on
`TownspersonMob` with codecs, save/load, and debug commands.

- `00-conventions.md` — naming, package layout, codec conventions
- `01-trait-axes.md` — `TraitVector`, 8 axes, getters, debug
- `02-memory-system.md` — `NpcMemoryLog`, 32-entry cap, decay math
- `03-knowledge-system.md` — `NpcKnowledgeLedger`, fidelity, sources
- `04-mood-system.md` — `MoodState`, single-scalar, triggers (storage)
- `05-skill-system.md` — `SkillComponent`, 8 skills, XP bookkeeping
- `06-office-framework.md` — office data structures (no behavior yet)

**Exit criteria**: new NPC spawns with all components persisting
correctly across save/load; no runtime behavior changes yet.

### Phase 1 — Personhood

Behavior layer over Phase 0 storage. This is where NPCs start feeling
like people: they have goals, they talk with structure, they respond
to player verbs.

- `07-life-goals.md` — 25 goal types, selection at adulthood
- `08-dialogue-tree.md` — `DialogueTree`, `DialogueNode`, predicates/
  effects, 25 starter trees
- `09-player-verbs.md` — 8 starter verbs (greet, compliment, insult,
  ask_life, ask_about, give_gift, commission, challenge)
- `10-phase1-integration.md` — `NpcLifeEventBus`, producers (memory,
  mood, trait-drift), tick budget

**Exit criteria**: player can hold a real conversation with any NPC
— ask about life, give a gift, witness the response update memory
and mood.

### Phase 2 — Social fabric

NPC-NPC relationships make villages feel inhabited. Apprenticeship
and scribal roles give craft and literacy real arc.

- `11-npc-relationship-ledger.md` — 15 entries/NPC, 7 modes
- `12-gossip-rumor.md` — channel exchange during SOCIAL, content
  mutation, fabrication
- `13-weekly-schedule.md` — 7-day schedules, personal overrides,
  expanded `DayPhase`
- `14-hobby-activities.md` — 15 starter hobbies, trait selection
- `15-child-elderly-arcs.md` — childhood state, retirement,
  unfinished business
- `16-apprenticeship.md` — formal contracts, milestones, masterpiece
- `17-scribal-professions.md` — SCRIBE, LIBRARIAN, SCHOLAR
- `18-letters-and-books.md` — letter items, book content,
  procedural history/ledger books

**Exit criteria**: villages show daily rhythm; relationships form
and decay between NPCs; apprenticeships run full arc; letters flow
between NPCs.

### Phase 3 — Offices & adaptive economy

Political and economic layer lights up. Crime, religion, medicine,
laws. Channel router makes trade work regardless of market presence.
Business-front fixes the always-profile interaction bug.

- `19-crime-justice.md` — CrimeType, reports, investigation, trials,
  punishments
- `20-religion-priest.md` — 4 religions, rites, piety, temple
  treasury
- `21-medicine-healer.md` — health conditions, remedies, plague
- `22-village-laws.md` — enactable laws, popularity, hook points
- `23-economic-channels.md` — `EconomicChannel` interface,
  `ChannelRouter`, 6 channel types
- `24-business-greeting.md` — `BuildingPresenceTracker`,
  `GreetPlayerGoal`, `BusinessFrontScreen` routing

**Exit criteria**: villages with no market still function
economically. Crimes are investigated and tried. Priests, healers,
and leaders hold office with real powers.

### Phase 4 — Specialization & inter-village

Cross-village flow and village specialization. Trading companies,
guilds beyond adventurers, inter-village request board, visitor
ecosystems, and village history.

- `25-resource-categories.md` — expanded `ResourceCategory`, per-
  building production/consumption profiles
- `26-npc-companies.md` — NPC-owned companies, merchant → trading
  company promotion
- `27-guild-refactor.md` — `AbstractGuild`, 6 guild types, L0–L3
  level hierarchy
- `28-request-board.md` — cross-village requests, escalation,
  fulfillment routing
- `29-visitor-flux.md` — 8 visitor types, capacity-driven arrival
- `30-village-history.md` — kingdom history, prestigious names,
  event archival

**Exit criteria**: specialized villages (shrine, mining, scholar,
market) support themselves through inter-village trade;
history tracks notable people and events across generations.

### Phase 5 — Polish & content

Wiring culture into everything, expanded event catalogue, visible
appearance differentiation, authored content sweep.

- `31-cultures.md` — 4 starter cultures fully specified, resolver
  wiring across all subsystems
- `32-events-expanded.md` — ~35 event types, scheduler, handlers
- `33-appearance-layer1.md` — 5-layer composition (culture, profession,
  office, accessory, life-stage)
- `34-content-pass.md` — dialogue/rumor/book authoring, numerical
  tuning, QA scenarios

**Exit criteria**: villages have distinct cultural character. Events
feel frequent without overwhelming. NPCs look different enough that
offices and cultures are visible at a glance.

### Phase 6 — Future (deferred)

Explicitly *not* in the v1 scope:

- JSON-driven custom cultures, events, dialogue, books
- Appearance Layers 2–3 (hair/face variation, wear/damage)
- Warfare (raids, sieges, militia, kingdom combat)
- Culture drift over time
- Inter-kingdom diplomacy mechanics
- Player-player apprenticeship
- Multi-village outposts for trading companies
- Corporate mergers, unions, bankruptcy handling

---

## Architectural principles

Consistent across all 34 docs:

**Component pattern on `TownspersonMob`.** Every new subsystem attaches
a component (`TraitVector`, `NpcMemoryLog`, `PietyComponent`, etc.)
mirroring existing `FamilyComponent`/`EconomyComponent`/
`AppearanceComponent` patterns. Each has its own codec + save/load.

**`NpcLifeEventBus`** introduced in Phase 1 is the primary fan-out
surface. Four dispatchers subscribe: memory, mood, trait-drift
(Phase 1), relationship ledger (Phase 2). New subsystems add dispatchers
rather than hooking events directly.

**Tick budget.** 20-bucket NPC split for per-second checks;
5-minute intervals for relationship decay; daily for memory eviction,
schedule rebuild, sim resync. Documented per-subsystem.

**Save-size budget** ~6 KB/NPC across all new state. At 200 NPCs × 50
villages = ~60 MB uncompressed. Monitor during Phase 0 implementation.

**Legacy migration.** Old `PersonalityTrait` enum values convert to
trait vector (`GENEROUS` → `GENEROSITY = +0.5`). Old single-day
profession schedules migrate to 7-day weekly. Existing `Quest`
migrates to `Request` subtype.

**Package layout.** New code under
`tterrag1112.life_in_the_village.Npc.<subsystem>`. Public entry
points exposed via components; internal state package-private.

---

## Cross-phase integration surfaces

Systems that multiple phases wire into:

- **Memory system** — producers in Phase 1; consumers in Phases 1–5.
- **Life event bus** — defined Phase 1; fan-out expanded each phase.
- **Office framework** — storage Phase 0; wiring Phase 3; offices
  appointed/elected through Phases 3–5.
- **Channel router** — Phase 3; channels registered incrementally
  (MARKET/DIRECT/STOCKPILE Phase 3, GUILD_REQUEST/VISITOR Phase 4).
- **Culture resolver** — shell Phase 0 (culture record); wiring
  Phase 5.
- **Weekly schedule** — Phase 2; cultural layer Phase 5.

---

## Testing strategy

**Per-phase**: each phase specifies an exit-criteria test scenario.
Phase 0 is pure save/load round-trip; Phase 1 is a dialogue test;
Phase 2–5 involve longer simulations.

**End-of-Phase-5 integration**: 8 scenarios in `34-content-pass.md`:

1. Year-one village full simulation
2. Plague response
3. Full wedding flow (courtship → child → adulthood → apprenticeship)
4. Crime investigation to punishment
5. Three-village specialized trade network
6. Player as apprentice (full arc)
7. Player as leader (enact law, face consequences)
8. Elderly death with unfinished business

---

## Document index

All 34 specification documents live under `docs/npc-redesign/`:

```
docs/npc-redesign/
├── 00-conventions.md
├── 01-trait-axes.md
├── 02-memory-system.md
├── 03-knowledge-system.md
├── 04-mood-system.md
├── 05-skill-system.md
├── 06-office-framework.md
├── 07-life-goals.md
├── 08-dialogue-tree.md
├── 09-player-verbs.md
├── 10-phase1-integration.md
├── 11-npc-relationship-ledger.md
├── 12-gossip-rumor.md
├── 13-weekly-schedule.md
├── 14-hobby-activities.md
├── 15-child-elderly-arcs.md
├── 16-apprenticeship.md
├── 17-scribal-professions.md
├── 18-letters-and-books.md
├── 19-crime-justice.md
├── 20-religion-priest.md
├── 21-medicine-healer.md
├── 22-village-laws.md
├── 23-economic-channels.md
├── 24-business-greeting.md
├── 25-resource-categories.md
├── 26-npc-companies.md
├── 27-guild-refactor.md
├── 28-request-board.md
├── 29-visitor-flux.md
├── 30-village-history.md
├── 31-cultures.md
├── 32-events-expanded.md
├── 33-appearance-layer1.md
└── 34-content-pass.md
```

Each doc follows the template in `00-conventions.md`: Purpose, Data
model, Persistence, Integration points, Behavior contract, Edge
cases, Ordering dependencies, Open decisions, Does-not-include,
Revision Notes.

---

## How to work through this plan

1. Read `NPC_PROGRESS.md` for current task state.
2. For each task, start by reading the relevant spec doc fully.
3. If implementation reveals a spec problem, update the doc's
   Revision Notes section and this plan's Revision Notes below
   — don't silently diverge.
4. Mark tasks complete in `NPC_PROGRESS.md` as you go.
5. Each phase ends with its exit-criteria scenario test before
   moving on.

---

## Revision Notes

(changes to this plan recorded here as implementation proceeds)