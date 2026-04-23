# NPC Redesign — Progress

Companion to `NPC_PLAN.md`. This file tracks what's been done and
what's next. Keep it updated as you work — it's the single place to
check "where am I?"

Legend: `[x]` = complete, `[~]` = in progress, `[ ]` = not started.

---

## Design phase

- [x] Scope locked: 8 traits, 32-entry memory, 4 starter cultures,
  no warfare v1, no JSON day 1, no trade-road work
- [x] Phase structure defined (0–5 implementation + 6 future)
- [x] All 34 subsystem specs written under `docs/npc-redesign/`
- [x] `NPC_PLAN.md` master plan document
- [x] `NPC_PROGRESS.md` tracker (this file)

**Design phase complete.** Implementation begins with Phase 0.

---

## Phase 0 — Skeleton (storage-only)

Goal: new components attached to `TownspersonMob` with codecs and
save/load. No behavior change yet.

- [ ] Read all Phase 0 specs (00–06) end-to-end before coding
- [ ] Create package `tterrag1112.life_in_the_village.Npc` structure
  per `00-conventions.md`
- [x] **01** Implement `TraitVector`, 8 axes with codec
    - [x] Field on `TownspersonMob`, save/load, debug command
    - [x] Migration from legacy `PersonalityTrait` enum
    - [x] `/npc traits <uuid>` prints full vector
- [ ] **02** Implement `NpcMemoryLog`
    - [ ] `MemoryEntry`, `MemoryType`, codec
    - [ ] 32-entry cap, value-weighted decay, pin logic
    - [ ] Daily tick hook for decay/eviction (no events producing
      memories yet — that's Phase 1)
    - [ ] `/npc memory <uuid>` debug command
- [ ] **03** Implement `NpcKnowledgeLedger`
    - [ ] `KnowledgeEntry`, 4 categories, fidelity field
    - [ ] Add/upgrade/remove API
    - [ ] `/npc knowledge <uuid>` debug command
- [ ] **04** Implement `MoodState` storage
    - [ ] Single −100..+100 scalar, trigger registry (no triggers fire
      yet)
    - [ ] `MoodCategory` derivation
    - [ ] `/npc mood <uuid>` debug command
- [ ] **05** Implement `SkillComponent`
    - [ ] 8 skills, XP bookkeeping, level derivation
    - [ ] Migration: existing per-profession progression maps to
      primary skill
    - [ ] `/npc skills <uuid>` debug command
- [ ] **06** Implement office framework data structures
    - [ ] `OfficeId`, `OfficeHolder`, `OfficeState`, `OfficeSelectionMethod`
    - [ ] Office attachment points: village, guild, company, temple,
      kingdom (storage only)
    - [ ] No selection logic yet (that's Phase 3)
    - [ ] `/npc offices <uuid>` debug command
- [ ] **Exit criteria**: spawn a new NPC; all new components persist
  across save/load; existing behavior unchanged; debug commands
  show all new state

---

## Phase 1 — Personhood

Goal: NPCs feel like people with goals, dialogue, and verb responses.

- [ ] Read Phase 1 specs (07–10) end-to-end
- [ ] **10** `NpcLifeEventBus` — event class hierarchy, dispatcher
  registry
    - [ ] Three Phase 1 dispatchers: memory, mood, trait-drift
    - [ ] Tick hooks wired
- [ ] **07** Life goals
    - [ ] 25 goal type definitions
    - [ ] Adulthood selection (1–3 goals per NPC, trait-weighted)
    - [ ] Goal progress tracking
    - [ ] Completion/failure hooks fire life events
- [ ] **08** Dialogue tree
    - [ ] `DialogueTree`, `DialogueNode`, `DialoguePredicate`,
      `DialogueEffect`
    - [ ] Predicate/effect registries
    - [ ] 25 starter trees authored
    - [ ] Existing dialogue system integration / replacement path
- [ ] **09** Player verbs
    - [ ] 8 starter verbs: greet, compliment, insult, ask_life,
      ask_about, give_gift, commission, challenge
    - [ ] Verb UI integration
    - [ ] Each verb fires appropriate life events
- [ ] **Producer wiring**
    - [ ] Memory producer consumes life events → memory entries
    - [ ] Mood producer consumes life events → mood triggers
    - [ ] Trait-drift producer (slow, long-term)
- [ ] **Exit criteria**: right-click any NPC, hold a real
  conversation; give a gift, see mood and relationship update;
  insult, see memory formed, face them later with a different
  greeting

---

## Phase 2 — Social fabric

Goal: NPC-NPC relationships, village rhythm, apprenticeship arcs.

- [ ] Read Phase 2 specs (11–18) end-to-end
- [ ] **11** NPC relationship ledger
    - [ ] 15 entries/NPC, 7 modes, seeding rules
    - [ ] Proximity growth during SOCIAL phase
    - [ ] Daily decay
    - [ ] Relationship dispatcher subscribes to life-event bus
- [ ] **13** Weekly schedule (before 12, since 12 depends on SOCIAL
  phase timing)
    - [ ] Expanded `DayPhase` enum
    - [ ] `WeeklySchedule`, `PersonalScheduleOverride`
    - [ ] `ScheduleResolver` with layer stacking
    - [ ] Migration of all existing schedule callers
- [ ] **14** Hobby activities
    - [ ] 15 starter hobbies, trait-weighted selection
    - [ ] `HobbyGoal` registered for LEISURE/day-off
    - [ ] Location resolution
- [ ] **12** Gossip/rumor
    - [ ] Gossip channels during SOCIAL
    - [ ] Content mutation, slant templates (15 starter)
    - [ ] Rumor seeding from memories and world events
    - [ ] Per-village topic heat tracker
- [ ] **15** Child/elderly arcs
    - [ ] `ChildhoodState`, `RetirementState`
    - [ ] Child play, attachment, schooling
    - [ ] Elderly retirement, mentor role, unfinished business
    - [ ] Coming-of-age transition hook
- [ ] **17** Scribal professions (before 16 and 18 — they depend on
  scribes)
    - [ ] SCRIBE, LIBRARIAN, SCHOLAR professions
    - [ ] SCRIBE_WORKSHOP, LIBRARY, SCHOLARS_RETREAT building types
    - [ ] Work goals, commission queue, library catalogue
- [ ] **18** Letters and books
    - [ ] `WrittenLetterItem`, `ExtendedBookContent`
    - [ ] Delivery paths (self, courier, postal)
    - [ ] onLetterReceived / onBookRead effects
    - [ ] Procedural history/ledger book generation
- [ ] **16** Apprenticeship
    - [ ] `ApprenticeshipContract`, 4 milestones, masterpiece phase
    - [ ] `ApprenticeshipSavedData`
    - [ ] NPC-NPC, player-as-apprentice, player-as-master paths
- [ ] **Exit criteria**: NPCs greet each other with familiarity,
  gossip about recent events, master-apprentice arcs run to
  graduation, letters flow between NPCs

---

## Phase 3 — Offices & adaptive economy

Goal: villages have real politics and economics that work without
markets.

- [ ] Read Phase 3 specs (19–24) end-to-end
- [ ] **06 → wire** Office framework behavior
    - [ ] Selection engine per method
    - [ ] Office appointment/election at village bootstrap
    - [ ] Office holder memory/relationship/mood integration
- [ ] **23** Economic channels
    - [ ] `EconomicChannel`, `ChannelRouter`
    - [ ] MARKET, DIRECT_BUSINESS, CARAVAN, STOCKPILE channels
    - [ ] GUILD_REQUEST, VISITOR stubs
    - [ ] Migrate existing market-only goals to router
- [ ] **24** Business greeting
    - [ ] `BuildingPresenceTracker` per-player
    - [ ] `GreetPlayerGoal` assignment
    - [ ] `BusinessFrontScreen` with profile as secondary
    - [ ] Greeter dialogue trees (6 trees)
- [ ] **22** Village laws (before 19, since crime reads laws)
    - [ ] `VillageLaw` enum, `VillagePolicy` per village
    - [ ] `LawEffect` implementations wired into subsystem hooks
    - [ ] NPC leader decision engine
    - [ ] Player leader laws UI panel
- [ ] **19** Crime & justice
    - [ ] CrimeType, CrimeReport, Trial, Punishment
    - [ ] Detection hooks (theft, assault, vandalism, trespass, seal,
      contract breach)
    - [ ] Constable investigation goal
    - [ ] Trial scheduling and execution
    - [ ] Punishment executor
- [ ] **20** Religion & priest
    - [ ] 4 religion records, PRIEST profession
    - [ ] `PietyComponent`, temple treasury
    - [ ] 10 rite handlers
    - [ ] Village priest office wiring
- [ ] **21** Medicine & healer
    - [ ] `HealthComponent`, condition enum, onset rules
    - [ ] HEALER profession, HEALER_HUT building
    - [ ] Remedy items and recipes
    - [ ] PLAGUE_OUTBREAK event integration
- [ ] **Exit criteria**: a market-less village functions
  economically; a crime is witnessed, investigated, tried, and
  punished; priest officiates a wedding; healer treats an
  injured NPC

---

## Phase 4 — Specialization & inter-village

Goal: specialized villages exist and trade with each other;
companies and guilds have structure; visitors bring coin.

- [ ] Read Phase 4 specs (25–30) end-to-end
- [ ] **25** Resource categories
    - [ ] Expanded `ResourceCategory` enum
    - [ ] `BuildingResourceProfile.TABLE` populated
    - [ ] `VillageSimData` refactor to category maps
    - [ ] Save migration
- [ ] **27** Guild refactor (before 26 and 28 — they depend on
  abstract guild)
    - [ ] `AbstractGuild` base + 6 subclasses
    - [ ] Implicit L0 guilds, L1+ upgrade on guild hall
    - [ ] 6 guild hall building types
    - [ ] Migrate existing `GuildData` to `AdventurerGuild`
- [ ] **28** Request board
    - [ ] `Request` + `RequestBoard` per scope
    - [ ] Posting, acceptance, fulfillment, escalation
    - [ ] `GuildRequestChannel` fully wired (was stub)
    - [ ] Player fulfillment UI
    - [ ] Migrate existing `Quest` to `Request.HUNT`/`SURVEY`
- [ ] **26** NPC companies
    - [ ] Company ownership extension (PLAYER/NPC)
    - [ ] `AiCompanyManager` daily
    - [ ] Merchant → trading company promotion
    - [ ] Succession on owner death
    - [ ] `CARAVAN_ATTENDANT` worker role
- [ ] **29** Visitor flux
    - [ ] `Visitor` entity, 8 visitor types, itinerary
    - [ ] `VisitorFluxEngine` daily arrival
    - [ ] Activity handlers (pray, stay, eat, trade, lecture, etc.)
    - [ ] `VisitorChannel` fully wired
    - [ ] Coin flow into village treasuries
- [ ] **30** Village history
    - [ ] Event archival after 365 days
    - [ ] Prestigious name tracking
    - [ ] Kingdom history compilation
- [ ] **Exit criteria**: 3 specialized villages (agricultural,
  craft, scholar) running 30+ days in a kingdom trade together
  via requests/caravans; visitors arrive at appropriate rates;
  village history tracks notable events

---

## Phase 5 — Polish & content

Goal: visual differentiation, cultural character, event richness,
authored content.

- [ ] Read Phase 5 specs (31–34) end-to-end
- [ ] **31** Cultures wired
    - [ ] Extended `Culture` record with all sub-bundles
    - [ ] 4 starter cultures fully specified
    - [ ] `CultureResolver` utility
    - [ ] Wiring in schedule/office/law/economy/hobby/visitor/
      apprenticeship
- [ ] **32** Events expanded
    - [ ] Full `EventType` enum (~35 types)
    - [ ] `EventScheduler` with all sources (calendar/life-event/
      crisis/player/visitor)
    - [ ] Type-specific handlers
    - [ ] Attendance logic
- [ ] **33** Appearance Layer 1
    - [ ] `AppearanceComponent` extended
    - [ ] `AppearanceLayerRegistry`
    - [ ] Culture base textures authored (4 × 3-4 variants)
    - [ ] Office mark overlays authored
    - [ ] Cultural accessory models authored
    - [ ] Rebuild hooks on state change
- [ ] **34** Content pass
    - [ ] Dialogue trees: expand 25 → 80
    - [ ] Rumor slant templates: 15 → 60
    - [ ] Memory description generators for all memory types
    - [ ] Letter templates: expand to 30
    - [ ] Books: 20 textbooks + 28 cultural works
    - [ ] Life goal flavor text
    - [ ] Event content text
    - [ ] Profession flavor dialogue
    - [ ] Cultural expressions (proverbs, etc.)
    - [ ] **Tuning pass**: mood, memory, relationship, economy,
      event, crime
    - [ ] 8 QA scenarios documented and tested
- [ ] **Exit criteria**: all 8 QA scenarios pass; a village feels
  culturally distinct at a glance; events happen at pleasant
  frequency; content holds up during extended play

---

## Phase 6 — Future (deferred)

Not in v1 scope. Items here are intentionally deferred and should
not be started during Phases 0–5:

- JSON-driven culture/event/dialogue/book packs
- Appearance Layers 2–3 (hair, face, wear, damage, seasonal)
- Warfare and militia systems
- Culture drift over time
- Inter-kingdom diplomacy (treaties, wars, embassies)
- Corporate mergers, acquisitions, bankruptcy
- Multi-village company outposts
- Player-player apprenticeships
- Localization beyond English

---

## Ongoing work

Things that don't fit the phase model and run continuously:

- [ ] Keep `NPC_PLAN.md` Revision Notes updated as specs change
- [ ] Keep per-doc Revision Notes updated as implementation discovers
  issues
- [ ] Run the current phase's exit-criteria scenario before moving on
- [ ] Capture player feedback post-Phase-5 for Phase 6 prioritization

---

## Notes & blockers

(current blockers and observations tracked here)

**Current status**: Phase 0 task 01 (TraitVector) implemented. The
`tterrag1112.life_in_the_village.Npc.Traits` package exists; save/load,
generation, legacy migration, and `/npc traits <uuid>` are wired. Legacy
`PersonalityTrait` list kept intact as a readable field on
`AppearanceComponent` for the one-release migration window.

**Template pattern established for the remaining Phase 0 components**:
- New subsystem package under `Npc.<Subsystem>` (e.g. `Npc.Memory`).
- Component class exposes `save(ValueOutput)` / `load(ValueInput)` and
  a `Codec<T>`. Returns a presence boolean from `load(...)` so the
  caller can drive legacy migration.
- `TownspersonMob` holds a `private final` field, calls `randomize` or
  equivalent fresh-spawn init in `finalizeSpawn`, persists at the end
  of `addAdditionalSaveData`, and loads at the end of
  `readAdditionalSaveData` (legacy fields already populated by then).
- Public accessor named `get<Subsystem>()` returning the raw component
  (no defensive copy), matching `getFamily()` / `getEconomy()` style.
  Exception: `getTraitVector()` because `getTraits()` is still held by
  the legacy list during the migration window.
- Debug subcommand registers onto the shared `/npc` root in
  `NpcDebugCommand.java` (not a new root command).

**Suggested next session**: task 02 (NpcMemoryLog). The 32-entry cap and
decay math are new territory vs. the straightforward TraitVector — read
`docs/npc_redesign/02-memory-system.md` fully before coding.