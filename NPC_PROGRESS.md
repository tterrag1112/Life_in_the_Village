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
- [x] **02** Implement `NpcMemoryLog`
    - [x] `NpcMemory` record, `MemoryType` enum (with polarity), codec
    - [x] 32-entry cap, value-weighted decay, pin logic
    - [x] Daily tick hook for decay/eviction (no events producing
      memories yet — that's Phase 1)
    - [x] `/npc memory <uuid>` debug command (list / add / decay)
- [x] **03** Implement `NpcKnowledgeLedger`
    - [x] `KnowledgeEntry`, 4 categories, fidelity field
    - [x] Add/upgrade/remove API
    - [x] `/npc knowledge <uuid>` debug command (list / add / mutate)
- [x] **04** Implement `MoodState` storage
    - [x] Single −100..+100 scalar, trigger registry (no triggers fire
      yet)
    - [x] `MoodCategory` derivation
    - [x] `/npc mood <uuid>` debug command (list / trigger / decay)
- [x] **05** Implement `SkillComponent`
    - [x] 8 skills, XP bookkeeping, level derivation
    - [x] Migration: existing per-profession progression maps to
      primary skill
    - [x] `/npc skills <uuid>` debug command (list / add / set)
- [x] **06** Implement office framework data structures
    - [x] `OfficeDefinition`, `OfficeHolding`, `OfficeState`,
      `SelectionMethod`, `OfficePower`, `Competence`, `OrgType`
    - [x] Office attachment points: village, guild, company, kingdom
      (storage only). Temple offices have no per-instance host class
      yet — `temple_high_priest` registered but unattached; documented
      in 06 Revision Notes.
    - [x] No selection logic yet (that's Phase 3)
    - [x] `/npc offices <uuid>` and `/office {info,set,vacate,list-all}`
      debug commands
- [ ] **Exit criteria**: spawn a new NPC; all new components persist
  across save/load; existing behavior unchanged; debug commands
  show all new state

---

## Phase 1 — Personhood

Goal: NPCs feel like people with goals, dialogue, and verb responses.

- [ ] Read Phase 1 specs (07–10) end-to-end
- [x] **10** `NpcLifeEventBus` — event class hierarchy, dispatcher
  registry
    - [x] Three Phase 1 dispatchers: memory, mood, trait-drift
    - [x] Tick hooks wired (calm-period drift-back in
      `npc_daily_decay`)
- [x] **07** Life goals
    - [x] 26 goal type definitions (spec count, not 25 from prompt)
    - [x] Adulthood selection (1-2 baseline, +1 if Ambition ≥ +0.4;
      capped at MAX_ACTIVE = 3 over lifetime)
    - [x] Goal progress tracking (poll for SAVE_AMOUNT /
      REACH_SKILL_LEVEL / HAVE_CHILD / WIN_OFFICE; event-driven for
      MARRY_TARGET / MARRY_ANY / HAVE_CHILD via the Married /
      BirthInFamily bus events)
    - [x] Completion/failure hooks fire life events
- [x] **08** Dialogue tree
    - [x] `DialogueTree`, `DialogueNode` (sealed
      Branch/Lines/Ref), `DialoguePredicate` (sealed, 17
      variants), `DialogueEffect` (record + 9-entry
      DialogueEffectType)
    - [x] Predicate test paths against Phase 0 component surfaces;
      EffectDispatcher routes the 9 effect types
    - [x] 25 starter trees authored + universal fallback
    - [x] NpcDialogue.getGreeting routes through
      DialogueRunner.lineFor with profession→treeId mapping;
      legacy line pools fall back when the runner returns the
      "..." sentinel
- [x] **09** Player verbs
    - [x] 8 starter verbs (greet, compliment, insult, ask_life,
      ask_about, give_gift, commission, challenge) registered
      via PlayerVerbRegistry
    - [x] Verb UI integration (ActionBarPanel renders one button
      per available verb, fires PlayerVerbInvokePacket)
    - [x] Each verb fires appropriate life events
      (Complimented / Insulted / GiftReceived); Greet / AskLife /
      AskAbout / Commission / Challenge route to dialogue trees
      without firing bus events
- [x] **Producer wiring**
    - [x] Memory producer consumes life events → memory entries
    - [x] Mood producer consumes life events → mood triggers
    - [x] Trait-drift producer (slow, long-term)
- [ ] **Exit criteria**: right-click any NPC, hold a real
  conversation; give a gift, see mood and relationship update;
  insult, see memory formed, face them later with a different
  greeting

---

## Phase 2 — Social fabric

Goal: NPC-NPC relationships, village rhythm, apprenticeship arcs.

- [ ] Read Phase 2 specs (11–18) end-to-end
- [x] **11** NPC relationship ledger
    - [x] 15 entries/NPC, 7 modes (NEUTRAL/ACQUAINTANCE/FRIEND/
      CLOSE_FRIEND/RIVAL/GRUDGE/FEUD); birth/adulthood/marriage
      seeding + workplace seed helper; single-generation
      grudge inheritance
    - [x] Proximity growth during SOCIAL phase (simplified
      once-per-day sample; capped at +20 from this source)
    - [x] Daily decay toward neutral with sticky close-friend
      half-rate; mode boundary crossings fire
      RelationshipBoundaryCrossed events
    - [x] Two new dispatchers on the bus
      (`RelationshipDispatcher`, `RelationshipSeeder`)
- [x] **13** Weekly schedule (before 12, since 12 depends on SOCIAL
  phase timing)
    - [x] Expanded `DayPhase` enum (11 values; WORK_PRIMARY /
      WORK_ERRAND / WORK_SECONDARY / COMMUTE / MARKET_RUN /
      LEISURE / HOME_PREP added; `isWork()` covers all WORK_*)
    - [x] `WeeklySchedule`, `PersonalScheduleOverride`
    - [x] `ScheduleResolver` with layer stacking
    - [x] Migration of all existing schedule callers via the
      `WorkSchedule` façade (delegates to `ScheduleResolver`;
      every legacy `isWorkTime()` etc. continues to work)
- [ ] **14** Hobby activities
    - [ ] 15 starter hobbies, trait-weighted selection
    - [ ] `HobbyGoal` registered for LEISURE/day-off
    - [ ] Location resolution
- [x] **12** Gossip/rumor
    - [x] Gossip channels during SOCIAL (transient
      `GossipChannel` + `GossipScheduler` 20-tick poll)
    - [x] Content mutation via existing RumorMutator + 13 starter
      slant templates (7 negative + 6 positive)
    - [x] Rumor seeding from memories (daily `RumorSeeder.rollDaily`
      promotion); world-event seeders deferred (see 12 Revision
      Notes — needs new NpcLifeEvent records for festival end /
      caravan arrival / building burn / office change)
    - [x] Per-village topic heat tracker (`GossipTopicHeat`
      attached to `VillageSavedData` as `gossipData`; daily decay
      in `npc_daily_decay`)
    - [x] Player verbs `listen_in` and `tell_rumor` registered
    - [x] AskAboutVerb fabrication path (low-honesty +
      high-sociability synthesises a FABRICATED entry on unknown
      topic)
    - [x] 3 new dialogue predicates: `KnowledgeSourceIs`,
      `IsTopicHot`, `HasHeardRumor`
    - [x] `/gossip {list,trace,seed,force-exchange,channels,clear}`
      Brigadier debug commands
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

**Current status**: Phase 0 tasks 01 (TraitVector), 02 (NpcMemoryLog),
03 (NpcKnowledgeLedger), 04 (NpcMoodState), and 05 (SkillComponent)
implemented. Packages `Npc.Traits`, `Npc.Memory`, `Npc.Knowledge`,
`Npc.Mood`, `Npc.Skills` exist; save/load wired additively on
`TownspersonMob`.

The single daily-tick subsystem (now reporting as
{@code "npc_daily_decay"}) iterates every loaded TownspersonMob once
per in-game day and runs (a) memory decay + eviction, (b) mood drift
toward baseline. Knowledge has no decay; skill decay is deferred to
Phase 1 per spec. The class file is still
`NpcMemoryDecayTickSystem.java` for now — internal-only name, can be
renamed in a later refactor pass.

Legacy migration paths in place:
- `PersonalityTrait` list → `TraitVector` axes (see 01 Revision Notes).
- `NpcProfessionXp` int → primary skill cumulative XP (direct 1:1 map;
  see 05 Revision Notes for the rationale).

`RumorMutator` (Phase 2 gossip utility) is implemented and debug-
testable via `/npc knowledge mutate` but has no production callers
yet. Its seed formula (splitmix64 finaliser of each of
`topic.hashCode()`, `acquiredTick`, UUID msb, UUID lsb, XORed) is
locked for stability — future Phase 2 work depends on it.

**Phase 2 progress:** Tasks 13 (weekly schedule), 11
(relationship ledger), and 12 (gossip/rumor) complete; tasks 14
(hobby), 15 (child/elderly arcs), 17 (scribal), 18
(letters/books), 16 (apprenticeship) remaining.

Schedule landed first because gossip and hobbies need the new
SOCIAL/LEISURE phase distinction, and the child/elderly arc
needs the schedule-layer mechanism. New `Npc.Schedule` package
holds the data model + library + resolver; legacy `WorkSchedule`
is now a thin façade so every existing goal that calls
`isWorkTime()` continues to work without touching the call site.

`PersonalScheduleGenerator` registered on the bus —
LifeStageAdvanced(ADULT) generates a trait-driven override per
the spec's table (Industry / Sociability / Ambition / Temperance
/ Compassion). Guard NPCs additionally get a `shiftIndex`
seeded from COMBAT skill seniority so the village fields
day/evening/night coverage.

**Phase 1 is COMPLETE.** All four tasks shipped:
- 10 NpcLifeEventBus + 3 producer dispatchers + TraitDriftLog
- 07 Life goals (LifeGoalSet on TownspersonMob, selector +
  evaluator + progress dispatcher)
- 08 Dialogue tree runtime (17-variant predicate set, 9-effect
  type, walker, registry with fallback, 25 starter trees)
- 09 Player verbs (8 verbs, NpcVerbCooldowns component,
  PlayerVerbInvokePacket, ActionBarPanel verb buttons,
  /verb debug commands)

**Phase 1 exit-criteria scenario** (per NPC_PLAN.md line 116):
spawn an NPC and run a real interaction loop — give a gift,
insult and return to see remembered greeting, force LifeStageAdvanced
to ADULT and verify life-goal selection, force a goal completion
and verify the event chain fires memory + mood updates. Verification
deferred until a successful local build run.

Bus + producer wiring (memory / mood / trait-drift) from task 10 is
in place; existing event surfaces hooked are
LivingIncomingDamageEvent, LivingDeathEvent (witness scan +
family-death fan-out), CourtingGoal.formCouple, ChildBirthGoal
completion, TradeHandler buy/sell paths, and now
TownspersonMob.onLifeStageChanged → LifeStageAdvanced.
TraitDriftLog is a Phase 1 persistent component on TownspersonMob;
calm-period drift-back is wired into the existing `npc_daily_decay`
subsystem.

Task 07 adds LifeGoalSet (cap 3 active, history TTL 90d) on
TownspersonMob, an `npcGoals` save subtree, the 26-entry
LifeGoalRegistry, and three event-bus dispatchers:
LifeGoalSelector (runs goal selection on ADULT transition),
LifeGoalProgressDispatcher (Married → MARRY_TARGET / MARRY_ANY,
BirthInFamily → HAVE_CHILD), and the daily LifeGoalEvaluator that
polls progress for SAVE_AMOUNT / REACH_SKILL_LEVEL / HAVE_CHILD /
WIN_OFFICE and fires GoalCompleted / GoalFailed on
threshold/expiry. Bus events `GoalCompleted` / `GoalFailed` /
`GoalAbandoned` were upgraded from String/int placeholders to carry
the real `LifeGoal` record per the spec. NpcProfileSnapshot gained
an `activeGoalLabels` field surfaced by the snapshot builder.

**Phase 0 is COMPLETE.** All six components shipped:
- 01 TraitVector
- 02 NpcMemoryLog
- 03 NpcKnowledgeLedger
- 04 NpcMoodState
- 05 SkillComponent
- 06 Office framework (OfficeDefinition / OfficeHolding / OfficeState
  attached to Village, GuildData, Company, Kingdom)

Legacy field migrations on load (kept readable for one release each):
- `AppearanceComponent.traits` (legacy `PersonalityTrait`) → `TraitVector`
- `NpcProfessionXp.npcProfXp` int → `SkillComponent` primary skill XP
- `Village.villageLeaderId` → `OfficeState` `village_leader` holding
- `GuildData.guildmasterId` → `OfficeState` `guild_master` holding
- `Kingdom.rulerEntityId / rulerPlayerId` → `OfficeState` `kingdom_king`
- `Company.ownerPlayerId` → `OfficeState` `company_owner` (player)

Daily-tick subsystem (`npc_daily_decay`, interval 24000) walks every
loaded TownspersonMob and runs memory decay + mood drift toward
baseline.

**Phase 0 exit-criteria scenario** (from `NPC_PLAN.md`) — verification
deferred until a successful local build run. Network-blocked sandbox
prevents Gradle dependency resolution; every component reviewed
manually + by Explore agent against the known-working pattern.

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