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
- [x] **14** Hobby activities
    - [x] 17 starter hobbies (15 from spec table + visit_grave +
      tell_story_inn), trait-weighted selection with softmax
      over top 5 of preferences
    - [x] `HobbyGoal` registered at P_SOCIAL_LOW for all NPCs
      via `ProfessionGoalFactory`; canUse honours LEISURE phase
      and day-off
    - [x] Location resolver covers HOME / TOWN_SQUARE / INN /
      TEMPLE_OR_SHRINE (with CHAPEL fallback) / LIBRARY /
      MARKET / FIELDS_NEARBY / WATER_EDGE / WORKSHOP_FREE /
      NATURE_TRAIL / FRIEND_HOUSE; resolution failure filters
      hobby out so missing infra never causes a stuck goal
    - [x] Adulthood `HobbyPreferenceGenerator` registered on
      bus seeds 3-5 preferred hobbies on LifeStageAdvanced(ADULT)
    - [x] Per-session XP award on clean PERFORMING completion;
      recent-use map down-weights repeats within 3 days
    - [x] /npc hobby {list,set,regenerate} debug subcommands
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
- [x] **17** Scribal professions (before 16 and 18 — they depend on
  scribes)
    - [x] SCRIBE + LIBRARIAN added to Profession enum (SCHOLAR
      already existed; rewired to SCHOLARS_RETREAT). LIBRARY now
      maps to LIBRARIAN; SCRIBE_WORKSHOP → SCRIBE;
      SCHOLARS_RETREAT → SCHOLAR via `professionFor`
    - [x] SCRIBE_WORKSHOP + SCHOLARS_RETREAT added to BuildingType
      (LIBRARY already existed). Both registered in
      BuildingProfileRegistry (workshop civic-adjacent,
      retreat landmark civic)
    - [x] ProfessionSkills mappings: SCRIBE = LITERACY/COMMERCE,
      LIBRARIAN = LITERACY/SOCIAL, SCHOLAR = LITERACY/MEDICINE
    - [x] Weekly schedule: late-rising SCHOLAR_DAY for the trio,
      WEEKEND_OFF
    - [x] ProfessionRequirements helper enforces LITERACY ≥
      50/60/80 hire gates; populator bumps bootstrap NPCs to the
      required minimum so spawn-time literacy meets the bar
    - [x] ScribeCommission / ScribeProductType / CommissionStatus
      / CommissionQueue with persistence per workshop UUID on
      VillageSavedData (9th codec field `scribalData`)
    - [x] BookRecord / LibraryLending / LibraryCatalogue with
      per-LIBRARY persistence on VillageSavedData
    - [x] AuthorStatus + ScholarProgress components on
      TownspersonMob with codec/save/load
    - [x] ScribalItems helper produces placeholder
      WRITTEN_BOOK / PAPER items with content data; doc 18 swaps
      for real letter/book item types
    - [x] ScribeWorkGoal / LibrarianWorkGoal / ScholarWorkGoal
      registered via ProfessionGoalFactory at P_WORK_PRIMARY
    - [x] 4 new player verbs: commission_letter,
      commission_book_copy, borrow_book, take_lesson
    - [x] /scribe commissions, /library catalogue,
      /scholar books debug commands
- [x] **18** Letters and books
    - [x] `WrittenLetterItem` registered with `LetterContent` data
      component (Codec + StreamCodec); right-click reads body in
      chat and flips seal flag if the player isn't the recipient
    - [x] `ExtendedBookContent` rides alongside vanilla
      `WRITTEN_BOOK_CONTENT` so the vanilla read screen still
      opens; carries topicsCovered + optional skill buff
    - [x] `LetterContent` / `BookCategory` / `LetterSpecial` /
      `SkillBuff` / `ActiveSkillBuff` records + codecs
    - [x] `SkillComponent` extended with active-buff list; new
      buffs apply a multiplicative XP multiplier on
      `addXp(skill, ...)`; expired buffs prune lazily; codec +
      save/load round-trip
    - [x] Delivery paths: right-click handoff via
      `NpcInteractionHandler` + `LetterDelivery`; scribe
      `PostalGoal` runs during SOCIAL phase scanning for
      outbox letters and walking them to recipients in-village
    - [x] `onLetterReceived` fires `NpcLifeEvent.LetterReceived`
      (memory + mood producers updated to handle the new
      sealed-interface case); refreshes sender memory; +2
      relationship; LITERACY trickle on read; CONTRACT-tagged
      letters seed knowledge entry
    - [x] `onBookRead` literacy gating: <30 silent skip, 30-59
      partial (50% topic count + half buff XP, no rate
      multiplier), ≥60 full effects
    - [x] 4 starter textbooks in `StarterTextbookLibrary`
      (forges → CRAFTING, herbal → MEDICINE, swordsmanship →
      COMBAT, ledger-keeping → COMMERCE)
    - [x] Procedural village ledger generator
      (`ProceduralBookFactory.generateVillageLedger`); spec
      line 273 — debug-gated only via `/book ledger`; Phase 3
      Village_Scribe office wires it into authorship pipeline
    - [x] Player verbs: write_letter, send_letter, read_book
    - [x] /letter {create,deliver} and /book
      {create,read,starter,ledger} debug commands
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
- [x] **06 → wire** Office framework behavior
    - [x] Selection engine per method (7 implementations + tiebreak helper
      under `Npc.Office.Selection`; `SelectionEngines` static lookup)
    - [x] Office appointment/election at village bootstrap
      (founding-elections queue on `addVillage`, drained by the daily
      `office_elections` tick subsystem)
    - [x] Office holder memory/relationship/mood integration
      (Promoted / Demoted life-events fire on every seat change)
    - [x] `OfficeElection` driver: `runElection`, `runFoundingElections`,
      `dailyTick`, `vacateAllHeldBy`, `seatPlayer`, `seatNpc`
    - [x] `PowerGrant` capability lookup utility (single-org and
      cross-level walkers)
    - [x] `CultureSelectionResolver` Phase 5 stub wired at the resolution
      site (Phase 5 only has to populate the resolver)
    - [x] `LivingDeathEvent` hook now vacates every held office and
      re-runs elections so seats refill same-tick
    - [x] Legacy migration cutover: `KingdomActionPacket.TOGGLE_LAW`
      and `KingdomLawEffects.isCitizen` route through `PowerGrant`
      (legacy `rulerPlayerId` stays populated for the one-release window)
    - [x] Player verbs: `petition_for_office` (rel ≥ +20 gate),
      `appoint_to_office`, `resign_from_office`
    - [x] `/office` subcommands extended: `election`, `grant`,
      `vacate-and-elect`, `powers <uuid>`, `me`
- [x] **23** Economic channels
    - [x] `EconomicChannel`, `ChannelRouter` (new package
      `Npc.Economy.Channels`)
    - [x] MARKET, DIRECT_BUSINESS, CARAVAN, STOCKPILE channels
    - [x] GUILD_REQUEST, VISITOR stubs (registered, always-unavailable
      until Phase 4 docs 28/29 fill them in)
    - [x] Migrate existing market-only goals to router:
      - `HouseholdWealthManager.tickHouseholdSpending`
      - `AbstractWorkstationProductionGoal.executeBuy`
      - `BuyFromMarketGoal` → `BuyGoodsGoal` (renamed, routed)
    - [x] `VillagePolicy` Phase-3-doc-22 stub wired at quote time
    - [x] `/economy quote|channels|migrate-status` debug commands
- [x] **24** Business greeting
    - [x] `BuildingPresenceTracker` per-player (player-tick driven,
      enter/leave listeners, logout cleanup)
    - [x] `BuildingTypeFlags` static lookup — BUSINESS_FRONT /
      SERVICE_FRONT / RESIDENCE per spec table (separate class because
      `BuildingType` is bare-entry; spec deviation logged in 24
      Revision Notes)
    - [x] `GreetPlayerGoal` slotted at `P_SOCIAL_HIGH` via
      `ProfessionGoalFactory.registerUniversal`; externally seated by
      `GreeterAssignment`; bark on APPROACH; auto-DISMISS after 600
      ticks
    - [x] `GreeterAssignment` selector — SERVICE_FACING > master >
      first; 60-second re-greet cooldown
    - [x] `BusinessFrontScreen` (compact functional layout: title +
      type-aware primary action button + 4 verb buttons + View Profile
      + Close)
    - [x] `OpenBusinessFrontPacket` + `NpcProfileActionPacket.Action.OPEN_PROFILE`
      back-route
    - [x] `NpcInteractionHandler.tryRouteBusinessFront` — opens
      BusinessFrontScreen during work hours; off-hours sends
      "We're closed" + 3-strike mood penalty
    - [x] 6 greeter dialogue trees + `greeting.business` fallback
      registered in `StarterTrees.registerAll`
    - [x] `/business presence|greeter|flags` debug commands
- [x] **22** Village laws (before 19, since crime reads laws)
    - [x] `VillageLaw` enum (22 entries across 4 categories),
      `VillagePolicy` per village with codec
    - [x] `LawEffect` interface + 22 impls; registry covers every law
    - [x] NPC leader decision engine + daily tick subsystem
      (`law_decision`, priority 197)
    - [x] Tax/wage/treasury hooks (`LawTaxHooks` — property tax mult,
      market tax mult, daily subsidies w/ overdraft suspend, profits
      to treasury fraction)
    - [x] Channel hooks (`LawPriceHooks` — replaces last-session
      stub; food price ceiling/floor + caravan-ban + market multipliers)
    - [x] Schedule hooks (`LawScheduleHooks` — CURFEW shifts phase;
      FESTIVAL_MANDATORY + COMPULSORY_SCHOOLING flag for downstream
      systems)
    - [x] Lifecycle: `LawAnnouncement` (gossip seed via topic-heat
      bump, mood shock via new MoodTrigger.LAW_TRANSITION, leader rep
      delta for player-leaders)
    - [x] Player verb: `petition_leader` (rel ≥ +20 gate, picks
      highest-scoring law via the same engine the NPC leader uses)
    - [x] `/law` debug subcommands: list, enact, repeal, popularity,
      audit
    - [ ] Player leader Laws UI panel — deferred (the proper Office
      tab GUI is itself a Phase 3 follow-up; `/law enact|repeal`
      stands in until then)
- [x] **19** Crime & justice
    - [x] `CrimeType` (13), `CrimeReport`, `Trial`, `Punishment` records
      with codecs; `CrimeSavedData` indexed by village + accused + with
      conviction counter
    - [x] Detection hooks: theft (extends existing `onContainerClose`),
      assault (LivingIncomingDamageEvent), murder (LivingDeathEvent),
      vandalism (BlockEvent.BreakEvent), trespassing (presence-tracker
      enter listener), seal violation (entry point), fraud (entry
      point); CONTRACT_BREACH stub deferred to Phase 2 task 16
      apprenticeship
    - [x] `CrimeReporter` Builder — witness scan (16-block radius +
      line-of-sight via AABB containment), `WITNESSED_CRIME_BY` /
      `VICTIM_OF_CRIME_BY` memory writes, relationship -60, mood
      shock, reputation drop
    - [x] `ConstableInvestigationGoal` — picks highest-severity oldest
      FILED report, walks to scene, validates witnesses via memory,
      builds `TrialEvidence` weighted per spec, transitions to
      TRIAL_SCHEDULED or DISMISSED
    - [x] `TrialExecutor` daily tick (`crime_trial`, priority 198) —
      runs due trials, picks bailiff/leader presider, computes
      verdict via spec-line-215 credibility + judge skill +
      Compassion bias, ages stale FILED → COLD after 30 days
    - [x] `PunishmentSelector` reads `VillagePolicy` (DOUBLE_PUNISHMENT,
      PARDON_FIRST_OFFENSE, BAN_EXECUTION) + repeat-offender ≥3
      escalation
    - [x] `PunishmentExecutor` — WARNING / FINE / RESTITUTION /
      COMMUNITY_LABOR / DETENTION / EXILE / EXECUTION /
      ITEM_CONFISCATION / OFFICE_BAR; SERIOUS+CAPITAL guilty record
      kingdom history
    - [x] `accuse_of_crime` player verb
    - [x] Dialogue trees: `constable.investigation`, `trial.testimony`,
      `trial.verdict-announcement`
    - [x] `/crime list|report|trial|punish|convict` debug commands
- [x] **20** Religion & priest
    - [x] 4 religion records, PRIEST profession
    - [x] `PietyComponent`, temple treasury
    - [x] 10 rite handlers
    - [x] Village priest office wiring
- [x] **21** Medicine & healer
    - [x] `HealthComponent`, condition enum, onset rules
    - [x] HEALER profession, HEALER_HUT building
    - [x] Remedy items and recipes
    - [x] PLAGUE_OUTBREAK event integration
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
(relationship ledger), 12 (gossip/rumor), 14 (hobby
activities), 17 (scribal professions), and 18
(letters/books) complete; tasks 15 (child/elderly arcs)
and 16 (apprenticeship) remaining.

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

---

**Phase 3 progress (this session)**: task 06 (office framework wiring)
complete. New packages:

- `Npc.Office.Selection` — 7 engines (`MeritocraticSelection`,
  `AscensionSelection`, `CouncilSelection`, `ElectiveSelection`,
  `HereditarySelection`, `AppointedSelection`, `DictatorialSelection`)
  + `OfficeCandidate`, `OfficeSelectionContext`, `CandidatePool`,
  `CouncilResolver`, `SelectionTiebreaker`, `SelectionEngines` lookup.
- `Npc.Office.Powers.PowerGrant` — capability lookup utility used by
  every "is this player allowed to ..." gate going forward.
- `Npc.Office.OfficeElection` — driver: `runElection`,
  `runFoundingElections`, `dailyTick`, `vacateAllHeldBy`, `seatPlayer`,
  `seatNpc`. `Npc.Office.CultureSelectionResolver` is the Phase 5 stub
  consulted before each engine pick.

Tick wiring: new `OfficeElectionTickSystem` (interval 24000, priority
195) registered in `TickSubsystemRegistry`. Drains
`VillageSavedData.pendingFoundingElections` then sweeps every loaded
Village / GuildData / Kingdom / Company.

Lifecycle hooks: `TownspersonMob.onNpcDeath` calls
`OfficeElection.vacateAllHeldBy` so a leader's death triggers
immediate refill; the player verbs `petition_for_office`,
`appoint_to_office`, `resign_from_office` are registered on the
PlayerVerbRegistry.

Legacy migration cutover: `KingdomActionPacket.TOGGLE_LAW` and
`KingdomLawEffects.isCitizen` route via `PowerGrant`; the legacy
`Kingdom.rulerPlayerId` / `Village.villageLeaderId` /
`GuildData.guildmasterId` / `Company.ownerPlayerId` fields stay
populated for the one-release window — but are no longer the source of
truth for any *gate*.

Office Tab UI replaced with `/office me` text listing for this session
— a full inventory tab is documented as a follow-up. Action buttons per
power are deferred to the per-power UIs (constable/leader/healer
sessions) per the brief's explicit Phase 3 scope cut.

---

**Phase 3 progress (next session)**: task 23 (economic channels) complete.
New package `Npc.Economy.Channels`:

- Core types: `TradeIntent`, `TradeDirection`, `Urgency`, `ChannelType`,
  `ChannelQuote`, `TradeResult`, `EconomicChannel` interface.
- 4 channel impls (`MarketChannel`, `DirectBusinessChannel`,
  `CaravanChannel`, `StockpileChannel`) + 2 stubs
  (`GuildRequestChannel`, `VisitorChannel`) registered.
- `ChannelRouter.findBestChannel` / `rankAllChannels` with the spec's
  scoring formula (priority − price penalty − travel penalty +
  urgency bonus).
- `VillagePolicy` Phase-3-doc-22 stub at the law-aware-pricing call
  site so doc 22 (next session) only has to populate the resolver.

Legacy trade-caller migrations:
- `Entities/HouseholdWealthManager.tickHouseholdSpending` — household
  daily shopping now routes via `ChannelRouter`; villages without a
  market fall through to `DirectBusinessChannel`.
- `Entities/Goals/Profession/Workshop/AbstractWorkstationProductionGoal.executeBuy`
  — workshops bridge `BuildingEconomy` → NPC wallet → channel →
  seller, with refund on partial fills.
- `Entities/Goals/Social/BuyFromMarketGoal` deleted; replaced by
  `Entities/Goals/Social/BuyGoodsGoal`. Registered in
  `ProfessionGoalFactory`.

Workshop production goals still gate the MARKET_VISIT phase on a
present `MARKET` building — the buy *path* is migrated, but the goal's
trigger is unchanged. A follow-up may unlock workshop-to-workshop
input sourcing without a market; flagged in 23 Revision Notes.

Debug commands: `/economy quote <actor> <buy|sell> <item> <qty>
[urgency]`, `/economy channels`, `/economy migrate-status`.

Spec deviation (signature): `EconomicChannel.isAvailable` adds a
`ServerLevel` parameter that the spec omits — needed so `CaravanChannel`
/ `VisitorChannel` can read level-scoped saved data without a second
quote round-trip. Logged in 23 Revision Notes.

---

**Phase 3 progress (next session)**: task 22 (village laws) complete.
New package `Npc.Laws`:

- 22 `VillageLaw` enum entries across 4 categories (10 economy / 4 crime
  / 5 social / 3 economic restriction).
- `VillagePolicy` per-village state class with codec on `Village` (15-
  field codec group, still under the 16-arity limit).
- `LawEffect` interface + 22 small impls in `Npc.Laws.effects`.
- `LawEffectRegistry` static map.
- 3 hook facades: `LawPriceHooks` (replaces last session's
  channels-side stub), `LawTaxHooks`, `LawScheduleHooks`.
- `LawPopularity` calculator (per-villager trait fit + benefits/affected
  bias); `LawAnnouncement` lifecycle (gossip seed + mood shock + leader
  rep delta); `LawEnactment` orchestrator.
- `LawDecisionEngine` daily autonomy for NPC leaders; new tick
  subsystem `law_decision` (interval 24000, priority 197).
- `petition_leader` verb (rel ≥ +20 gate).
- `/law list|enact|repeal|popularity|audit` debug commands.

Hook wiring:
- `VillageSimEngine` property tax now multiplies by
  `LawTaxHooks.propertyTaxMultiplier`.
- `TreasuryTickHandler` resumes suspended subsidies, then pays daily
  subsidies via `applySubsidyOrSuspend`.
- `MarketChannel` market-tax slice scales by law multiplier; food
  ceiling / floor applied to all three concrete sell-side channels.
- `CaravanChannel.isAvailable` honours FOREIGN_TRADER_BAN.
- `ScheduleResolver.phaseAt` runs results through
  `LawScheduleHooks.applyLaws` so CURFEW forces HOME after daytick
  12000.

`MoodTrigger.LAW_TRANSITION` added (signed magnitude per-NPC, ±25 cap)
so enactment / repeal mood shocks reuse the existing daily-stack
infrastructure.

Spec deviations: `LawEffect` defines small primitive accessors per
hook surface rather than the spec's monolithic `modifyTax /
modifyToll / modifyWage / modifyPunishment / blocksAction` shape —
keeps the hook-facade classes (`LawPriceHooks`, `LawTaxHooks`,
`LawScheduleHooks`) thin and lets crime / visitor / festival sessions
add their own facade classes for their hook surfaces. Logged in 22
Revision Notes.

NPC-leader daily reputation drift skipped in v1 (no player ledger to
consult for an NPC leader); player-leader rep delta still applies at
enact / repeal time. SUBSIDIZE_HEALER and GUILD_MEMBERSHIP_REQUIRED
ship as registered no-ops awaiting their dependent subsystems
(profession enum + guild refactor). Player Laws UI panel deferred
behind the Office tab GUI follow-up; `/law` commands serve as the v1
surface.

Build verification deferred (sandbox blocks maven.neoforged.net).

---

**Phase 3 progress (next session)**: task 24 (business greeting) complete.
New package `Npc.BusinessFront`:

- `BuildingPresence` record + `BuildingPresenceTracker` (player-tick
  driven; enter/leave listener registry; logout cleanup).
- `BuildingTypeFlags` (separate static lookup, not enum body) — BUSINESS_FRONT
  / SERVICE_FRONT / RESIDENCE per the spec table.
- `BusinessFrontStatus` enum + `BusinessFrontState` record + `BusinessFrontTracker`
  (transient state map + closed-refusal counter).
- `GreetPlayerGoal` (APPROACH / ATTENDING / FOLLOW_UP / DISMISS phases;
  externally seated; bark on approach; 30s auto-dismiss).
- `GreeterAssignment` (priority ranking — service-facing → master → first;
  60s re-greet cooldown).

GUI / packet wiring:
- `OpenBusinessFrontPacket` (server→client, minimal payload).
- `BusinessFrontScreen` (compact: title, type-aware primary action,
  4 verb buttons, View Profile, Close).
- `NpcProfileActionPacket.Action.OPEN_PROFILE` lets the View Profile
  button bounce back through `NpcProfileHub.open`.

Routing:
- `NpcInteractionHandler.tryRouteBusinessFront` opens the new screen
  during work hours iff player is in a BUSINESS_FRONT/SERVICE_FRONT
  building AND the NPC works there. Off-hours sends "We're closed"
  + INSULT_RECEIVED mood penalty (-2) on the third refusal in a visit.
- Outside any business-front / in a residence: falls through to the
  existing `NpcProfileHub.open` path.

Tick + listeners:
- `PlayerEventProximityHandler.onPlayerTick` calls
  `BuildingPresenceTracker.onPlayerTick(player)` every tick (cheap —
  one `getBuildingAt` per player).
- `ServerStartingEvent` registers `GreeterAssignment::onPlayerEntered`
  and `onPlayerLeft` as enter/leave listeners.
- `PlayerLoggedOutEvent` clears the player's tracker entry.

Goal wiring:
- `GreetPlayerGoal` added at `P_SOCIAL_HIGH` (between combat and work)
  via `ProfessionGoalFactory.registerUniversal`. Stays no-op until
  `GreeterAssignment.assign()` seats a target.

Dialogue:
- 6 greeter trees + `greeting.business` fallback registered in
  `StarterTrees.registerAll`. Phase 5 polishes the content.

Debug: `/business presence|greeter <npc>|flags <type>`.

Spec deviations:
- `BuildingTypeFlags` is a separate static lookup rather than a
  constructor-arg field on `BuildingType` because the enum has no
  body and adding constructor args would touch every existing line.
  Same surface (`hasBusinessFront`, `hasServiceFront`, `isResidence`).
- `BusinessFrontScreen` is a compact custom Screen, not a re-skin of
  `NpcProfileScreen`. v1 prioritises functional flow over visual
  polish — Phase 5 may align styling with the profile screen.

Build verification deferred (sandbox blocks maven.neoforged.net).

---

**Phase 3 progress (next session)**: task 19 (crime & justice) complete.
New package `Npc.Crime`:

- 8 enums + 5 records (`CrimeType`, `CrimeSeverity`, `ReportStatus`,
  `EvidenceType`, `TrialVerdict`, `PunishmentType`, `Punishment`,
  `TrialEvidence`, `TrialTestimony`, `CrimeReport`, `Trial`).
- `CrimeSavedData extends SavedData` — keyed by reportId + indexed by
  village + accused + conviction counter; codec persists every
  report and trial.
- `CrimeReporter` Builder — single entry point for "a crime
  happened"; runs witness scan (16-block AABB), writes
  `WITNESSED_CRIME_BY` / `VICTIM_OF_CRIME_BY` memories, applies
  relationship -60 + mood shock + reputation drop.
- `CrimeDetectionHooks` (`@EventBusSubscriber`) — wires
  LivingIncomingDamageEvent (ASSAULT), LivingDeathEvent (MURDER),
  BlockEvent.BreakEvent (VANDALISM), presence-tracker enter listener
  (TRESPASSING), entry-point methods for SEAL_VIOLATION + FRAUD that
  Phase 5 / Phase 2-task-16 fill in.
- `ConstableInvestigationGoal` (registered at `P_WORK_PRIMARY` via
  `ProfessionGoalFactory`) — gated by INVESTIGATE_CRIME power; walks
  scene, validates witness memories, builds evidence list,
  transitions to TRIAL_SCHEDULED (≥2 corroborating witnesses OR
  physical-item note) or DISMISSED.
- `TrialExecutor` daily tick subsystem (`crime_trial`, priority 198)
  — runs due trials per spec line 215 credibility + judge skill +
  Compassion bias formula; ages stale FILED → COLD after 30 days.
- `PunishmentSelector` reads `VillagePolicy.hasLaw` for
  DOUBLE_PUNISHMENT (severity bump), PARDON_FIRST_OFFENSE (downgrade
  to WARNING), BAN_EXECUTION (substitute EXILE); repeat-offender ≥3
  priors swaps to the spec table's repeat row.
- `PunishmentExecutor` applies all 9 PunishmentTypes; SERIOUS /
  CAPITAL guilty verdicts record `DECREE_ISSUED` kingdom history.
- `accuse_of_crime` player verb; 3 dialogue trees
  (`constable.investigation`, `trial.testimony`,
  `trial.verdict-announcement`).
- `/crime list|report|trial|punish|convict` debug commands.

Existing theft hook (`ModModEvents.onContainerClose`) extended:
witnessed theft now files a CrimeReport with THEFT_MINOR /
THEFT_MAJOR based on a per-stack value estimate via
`MarketPriceHelper.getBaseSellPrice`.

Spec deviations + deferrals (logged in 19 Revision Notes):
- Self-defense exemption checks `getLastHurtByMob() != null` rather
  than a tick-window timestamp (no public timestamp accessor in
  this Minecraft version).
- DETENTION ships as a `setCurrentActivity` + chat warning; the
  movement-tether implementation (spec "Things to flag" #3) lands
  with the Phase 4 jobs/AI refactor.
- ITEM_CONFISCATION + COMMUNITY_LABOR are flag-only; the actual
  inventory removal + JobPosting wiring lands with the dependent
  subsystems (Phase 4 jobs).
- CONTRACT_BREACH detection deferred until Phase 2 task 16
  (apprenticeship) ships.
- PERJURY detection is a stub; full check in Phase 5.
- Repeat-offender threshold counts lifetime convictions; rolling-
  window counting deferred per spec "Things to flag" #5.

Audit-discovered fixes: `LivingEntity.getLastHurtByMobTimestamp()`
doesn't exist on this Minecraft version (switched to
`getLastHurtByMob() != null`); `Player.hasPermissions(int)` doesn't
either (switched to `isCreative() || isSpectator()` for the
vandalism-bypass check).

---

**Phase 3 progress (next session)**: task 20 (religion & priest)
complete. New package `Npc.Religion`:

- 4 enums (`Rite` (10 values), `RiteOutcome`, `PietyTier`,
  `ReligiousCalendar` constants).
- 6 records (`Religion`, `ReligiousCalendar`, `RiteExecution`,
  `PietyComponent`, plus the existing `RiteOutcome` codec hook).
- `ReligionRegistry` ships 4 starter religions per spec line 36 —
  Sunstead, The Loom, Tidecall, Forge Creed — each with rite
  filter, sacred-location tags, holy-day calendar (4 named days
  per religion, mapped to day-of-year integers), preferred book
  categories. `dominantReligionFor(culture)` maps Plainfolk →
  Sunstead, Silkwood → The Loom, Tidereach → Tidecall, Highmarch
  → Forge Creed.
- `PietyComponent` per-NPC: `Map<String, Float> beliefs`, plus
  rolling 30-day attendance counter for the rite-attendance
  modifier in spec line 167. `setBelief` / `adjustBelief` /
  `beliefIn`, `primaryReligion` / `primaryStrength` /
  `primaryTier` (UNAFFILIATED < 0.2, FAITHFUL < 0.5, DEVOUT < 0.8,
  PIOUS), `attendsRite(Rite)` per spec line 162. Saves under
  `npcPiety` on the NPC and on `RiteSavedData` for player piety.
- `RiteSavedData extends SavedData` — keyed by riteId (every
  scheduled rite, completed or pending) + per-player
  `PietyComponent` map. `dueRites(tick)` /
  `ritesForVillage(villageId)` indexers; codec persists
  everything.
- `RiteExecutor` — one handler per rite (10 total) per spec line
  113. Handlers grant the priest +5 SOCIAL XP; fire mood arms
  per spec line 119 (COMING_OF_AGE → GIFT_FAVORITE +20, MARRIAGE
  → MARRIAGE +50, NAMING → GIFT_RECEIVED +15, FUNERAL +6,
  BLESSING → GIFT_RECEIVED +8, CONFESSION → LETTER_FROM_FRIEND
  +12, OFFERING +5, HARVEST_THANKSGIVING → FESTIVAL_ATTENDED +12
  village-wide + 50 bronze treasury, FEAST_DAY +8 village-wide).
  CONFESSION grants the priest a sensitive `KnowledgeEntry` with
  `KnowledgeCategory.PERSONAL`. MARRIAGE without a priest defers
  up to 14 days (`MARRIAGE_DEFER_LIMIT_TICKS`); COMING_OF_AGE
  without a priest skips silently.
- `RiteScheduler` — daily-tick subsystem; runs due rites then
  schedules calendar rites (HARVEST_THANKSGIVING on the
  religion's named "Harvest Equinox" / "Last Catch" day if it
  ritualises that rite, otherwise FEAST_DAY for other holy
  days). Public `schedule(level, village, rite, participantIds,
  delayTicks)` for lifecycle-event triggers and verb invocations.
- `RiteLifeEventProducer` — registered on `NpcLifeEventBus`.
  Reacts to `LifeStageAdvanced` (newStage = "ADULT") →
  COMING_OF_AGE 1 day out, `Married` → MARRIAGE 1 day out,
  `BirthInFamily` → NAMING 2 days out, `FamilyDeath` → FUNERAL 1
  day out. Each gated on the village's primary religion's
  `ritualises(rite)` filter.
- 3 player verbs (`request_blessing`, `confess`, `make_offering`)
  + `/religion list|set|rite|calendar|tithe` debug commands.
- `Profession.PRIEST` already existed (Phase 2-task-15);
  `professionFor(BuildingType.TEMPLE)` returns it; `ProfessionSkills`
  already maps PRIEST → (SOCIAL, LITERACY); weekly schedule
  PRIEST → MONDAY_OFF.
- `MakeOfferingVerb` deposits 10 bronze to the temple
  `BuildingEconomy` if the priest's assigned building is
  TEMPLE (spec line 141 temple treasury seed).
- `TownspersonMob` integration: piety field, save/load,
  `getPiety()` accessor, `finalizeSpawn` seeding from the
  village's kingdom culture (initial belief 0.3).

Spec deviations + deferrals (logged in 20 Revision Notes):
- `PriestWorkGoal` (spec line 86) deferred — the lifecycle-
  dispatcher + scheduler covers most rite triggering without
  requiring a dedicated priest goal in v1.
- `AttendRite`, `PayTithe`, `CommissionRite` verbs deferred —
  recurring tithe + rite-attendance scanning is heavier
  infrastructure that lands with the priest goal.
- BLESSING skill-buff arm (spec line 121 — temporary +1 skill
  modifier) deferred; the mood arm fires.
- Officiation animation (spec "Things to flag" #2) deferred —
  the goal pass will add the priest stand-and-gesture.
- Treasury fund priorities (spec line 145 — temple fund use
  cases) tracked as TODO; depositRevenue lands but the priest
  doesn't yet draw on it for repairs / aid.
- Calendar tick conversion (spec "Things to flag" #1) uses
  `ReligiousCalendar.DAYS_PER_YEAR = 365` and `(gameTime / 24000)
  % DAYS_PER_YEAR` for day-of-year, matching the existing
  daily-tick subsystem cadence.

Audit-discovered fixes: operator-precedence bug in
`RiteScheduler.scheduleCalendarRites` (the harvest-equinox check
read `DAYS_PER_YEAR / 4 + 80 % DAYS_PER_YEAR = 171`, hitting
Midsummer instead of day 264). Replaced with a direct calendar
lookup of "Harvest Equinox" / "Last Catch" by name.

Build verification deferred (sandbox blocks maven.neoforged.net).

---

**Phase 3 progress (next session)**: task 21 (medicine & healer)
complete. Phase 3 closes with this entry.
New package `Npc.Health`:

- `HealthCondition` (12 entries: 5 injuries, 4 illnesses,
  FRAILTY, MELANCHOLY, NERVOUS_BREAKDOWN) with `isContagious()`
  + `requiresMedicine()` + `isFatal()` helpers.
- `ActiveCondition` record with severity 1-5, treated flag,
  treatedById; `markTreated` halves the remaining duration per
  spec line 112.
- `HealthDurations` table — untreated durations, complication
  rates (SERIOUS_WOUND 10%, PLAGUE_CARRIER 20%, etc.) and
  mortality rates per spec lines 100-114.
- `HealthComponent` — conditions list, hidden constitution
  (0..100), consecutiveWorkDays + distressedDayCount counters,
  pendingDeath flag for daily-tick mortality. Aggregates:
  `workEfficiencyModifier`, `canWork`, `canTravel`,
  `moodPenalty`, `isContagious`. Codec round-trips everything.
- `RemedyType` (7 entries) + `Remedy` record with potency 1-3
  + per-type shelf life (30-60d). `HealerInventory` 24-slot
  stash with potency-aware `takeFor(HealthCondition)`.
- `Plague` record with stage / counts / on-duty healer +
  `withCounts/withHealer/resolve` mutators.
- `HealthSavedData extends SavedData` — active plague per
  village (one map keyed by villageId) + per-player remedy
  stashes.
- `HealthTicker.dailyTick` — conditions resolve + complications
  + mortality; seasonal RESPIRATORY/STOMACH onset; contagion
  proximity scan (4 blocks); plague daily 10% infection roll
  with quarantine 60% reduction; healer overwork 5%/day plague
  mortality; FRAILTY at ELDERLY + monthly constitution decay;
  EXHAUSTION at 20 consecutive work days; NERVOUS_BREAKDOWN at
  30 distressed days.
- `PlagueScheduler.weeklyRoll` — 0.4%/week baseline scaled by
  village size; `start(level, village, tick)` API for the
  /plague debug + spec test paths.
- `InjuryHooks` — `LivingIncomingDamageEvent` listener that
  routes combat damage into condition onset (BURN for fire,
  BROKEN_BONE for fall / heavy blunt, SERIOUS_WOUND ≥ 6 dmg,
  MINOR_WOUND otherwise).

Profession + building:
- `Profession.HEALER` with `professionFor(HEALER_HUT)` mapping.
- `BuildingType.HEALER_HUT` + ZoneRegistry / BuildingRegistry /
  BuildingProfileRegistry / BuildingInhabitantRegistry /
  BuildingTypeFlags entries (BUSINESS_FRONT — "Request
  Treatment" already wired in `BusinessFrontScreen`).
- `ProfessionSkills.HEALER → (MEDICINE, SURVIVAL)`.
- `WeeklyScheduleLibrary.HEALER → SUNDAY_OFF`; plague response
  overrides the schedule via `HealerWorkGoal.isPlagueOverride`.

Office + law + powers:
- `OfficePower.QUARANTINE_VILLAGE`, `REQUISITION_REMEDIES`.
- `OfficeRegistry.VILLAGE_HEALER` (APPOINTED, MEDICINE 40 +
  SURVIVAL 20, competence (MEDICINE 40-80, 1.20x, -0.08).
- `VillageLaw.QUARANTINE_VILLAGE` (CRIME category, popularity
  -25, +Compassion / -Sociability trait fit).

Goal:
- `HealerWorkGoal` registered at `P_WORK_PRIMARY` via
  `ProfessionGoalFactory`. Phases: WALKING → TREATING (400t
  default, 200t plague triage, consumes one remedy) → IDLE,
  or PRODUCING (200t to brew one remedy, type cycles by day).
  Plague triage prioritises PLAGUE_CARRIER then severity.

Daily tick:
- `HealthDailyTickSystem` (interval 24000, priority 200) runs
  after religion (199).
- `PlagueRollTickSystem` (interval 7×24000, priority 201).

Hooks:
- `RiteExecutor.handleConfession` extended — confession clears
  MELANCHOLY directly (spec "Open decisions" #4) and applies a
  HEALED mood blip when it does.
- `MoodTrigger` adds `INJURY_SUSTAINED`, `HEALED`,
  `PLAGUE_AMBIENT`.

Player verbs:
- `request_treatment` — buy / receive a remedy from the healer's
  stash; healer earns +2 MEDICINE XP and +5 relationship.
- `donate_herbs` — adds one HERBAL_POULTICE pot 2 to the stash.
- `buy_remedy` — picks the highest-potency entry, transfers it
  to the player's `HealthSavedData` stash.

Debug:
- `/health show <npc>` — list active conditions + constitution.
- `/health add <npc> <condition> [severity]`.
- `/health treat <npc> <condition> [healer]`.
- `/health clear <npc>`.
- `/plague start|status|end <village>`.

Spec deviations + deferrals (logged in 21 Revision Notes):
- Remedy is a record-shaped saved-data entry, not a vanilla
  Item with NBT — v1 ships without item-side recipes; "buy
  remedy" hands a record from the healer's stash to the
  player's stash on `HealthSavedData`.
- Recipe-side ingredient consumption is abstracted: producing a
  remedy doesn't yet consume herbs / honey / cloth from a
  workshop chest. Phase 4 can wire this once the apprentice
  inventory hooks ship.
- Player diagnosis (spec line 192 — "Request treatment" with a
  player condition) is stubbed: the player has no
  HealthComponent (vanilla HP only), so the verb defaults to
  MINOR_WOUND and falls back to any-on-hand remedy.
- Misdiagnosis (spec line 158) deferred — v1's diagnosis is
  always exact (highest-severity treatable condition).
- Healer apprenticeship (spec line 163) untouched — Phase 2
  task 16 hasn't shipped the apprenticeship machinery yet.
- Constitution-reveal dialogue (spec "Things to flag" #2)
  deferred to Phase 5 dialogue polish.
- Player remedy expiry — when expired, the remedy stays in the
  player's stash until the next stash-open verb prunes it
  (spec "Things to flag" #4). The daily sweep does not iterate
  player stashes.
- Visitor-flux plague seeding (spec "Things to flag" #3) is
  unimplemented; v1 ships only the random per-village weekly
  roll. Phase 4 wires the inter-village vector.
- DAY length = 24000 ticks (matches the existing daily tick
  systems); seasonal calendar uses a 384-day year split into
  4 × 96-day seasons (no vanilla season system).

Audit-discovered fixes:
- `HealerWorkGoal.canUse` originally called `takeFor` to remove
  the remedy before `start()` was guaranteed to run; reworked
  to check `hasRemedyFor` only and call `takeFor` in `start()`
  with a fallback to abort if the stash drained between phases.
- `RequestTreatmentVerb` had a redundant double-`takeFor` in
  the fallback path (the `ifPresent` lambda was a no-op);
  rewrote as a clean if-else that takes once.
- `BuyRemedyVerb` selected the highest-potency remedy via
  `stream().max(...)` but then called `takeFor` separately —
  result: the stash could keep the highest-potency entry while
  the player got a lower-potency duplicate. Now uses the
  `takeFor` return value directly.

Build verification deferred (sandbox blocks maven.neoforged.net).