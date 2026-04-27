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
- [x] **15** Child/elderly arcs
    - [x] `ChildhoodSkill` / `ProfessionPreference` / `ChildhoodState`
      with codec save/load, attached to `TownspersonMob`
    - [x] `UnfinishedBusinessType` / `UnfinishedBusiness` /
      `RetirementState` with codec save/load, attached to
      `TownspersonMob`
    - [x] Caregiver attachment seeded at +50 (primary) / +30
      (secondary) via `ChildhoodInitializer` on `BirthInFamily`;
      mirrored on caregiver where loaded
    - [x] Coming-of-age handler on `LifeStageAdvanced(ADULT)`:
      leader-presence rite stub (officiated-by/-for memories),
      preferred-profession recompute, fallback to FARMHAND when
      no master is available; `ApprenticeshipDispatcher` already
      handles the contract creation
    - [x] Retirement decision on `LifeStageAdvanced(ELDERLY)`:
      hard never-retire roles (priest/scholar/librarian/scribe/
      leaders/herald/chancellor), physical professions retire
      below Industry +0.5, others stay active
    - [x] Unfinished-business roll on ELDERLY entry weighted by
      Ambition + memory state; 5 types each map to an existing
      `LifeGoalType` proxy
    - [x] `MentorGoal` for elderly (skill ≥ 60); shares the
      apprenticeship's `MentorshipBonus.npcMentorshipFor` so
      the mentee's existing work-goal XP path picks up the +50%
      multiplier when co-located
    - [x] Elderly-slowdown layer in `ScheduleResolver`: earlier
      WAKE_UP, halved WORK_PRIMARY, +60% LEISURE, earlier
      HOME_PREP
    - [x] `ChildPlayGoal` extended with shared-interest skill XP
      grant on play-tag; `LibrarianWorkGoal` schooling stub from
      task 17 already runs
    - [x] `DeathArc.onNpcDeath` wired into
      `TownspersonMob.onNpcDeath`: peaceful-death memory for
      attendees, gossip-topic seed + small mood drop on
      DIE_WITH_REGRET when an unresolved business survives the NPC
    - [x] /npc {children,elderly,force-coming-of-age,
      set-unfinished} debug commands
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
- [x] **16** Apprenticeship
    - [x] `ApprenticeRank` (APPRENTICE/JOURNEYMAN/MASTER) +
      `ContractStatus` (ACTIVE/COMPLETED/TERMINATED/BROKEN/
      ABANDONED) enums
    - [x] `ApprenticeshipContract` 16-field record + codec;
      masterpiece phase fields (target, deadline, attempts)
      rolled into the contract record rather than introducing a
      `COMMISSION_MASTERPIECE` order type
    - [x] `ApprenticeshipSavedData` standalone with 3 indices
      (byContractId / apprenticeToContract / masterToContracts)
    - [x] `ApprenticeshipMatcher` discovery + master-side decision
      with relationship + Compassion/Ambition trait bias
    - [x] `ApprenticeshipManager` weekly tick: milestone advance
      (skill 20/40/55/70), TAUGHT_BY memories every 30 days,
      masterpiece auto-evaluation (skill ≥ 75 → MASTER, else
      JOURNEYMAN after 2 retries)
    - [x] `MentorshipBonus` helper: NPC +50% (master co-located),
      player +10% (within 32 blocks); apprentice wage halved in
      `WorkplaceAssignmentManager.tickWeeklyPay`
    - [x] `ApprenticeshipContractFactory` queues a CONTRACT-tagged
      letter via the village scribe's commission queue;
      direct-mint fallback when no scribe exists
    - [x] Bus dispatcher on `LifeStageAdvanced(ADULT)` + weekly
      tick subsystem (`apprenticeship_weekly`, interval 7 days);
      master death triggers BROKEN cleanup
    - [x] 3 player verbs: apprentice_under_me, take_apprentice,
      release_apprenticeship
    - [x] /apprentice {list,info,promote,masterpiece,complete,
      terminate} debug commands
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
- [x] **25** Resource categories
    - [x] Expanded `ResourceCategory` enum
    - [x] `BuildingResourceProfile.TABLE` populated
    - [x] `VillageSimData` refactor to category maps
    - [x] Save migration
- [x] **27** Guild refactor (before 26 and 28 — they depend on
  abstract guild)
    - [x] `AbstractGuild` base + 6 subclasses
    - [x] Implicit L0 guilds, L1+ upgrade on guild hall
    - [x] 6 guild hall building types
    - [~] Migrate existing `GuildData` to `AdventurerGuild`
      (deferred — `GuildData` left intact alongside; the new
      abstract layer is additive per spec "Open decisions" #1)
- [x] **28** Request board
    - [x] `Request` + `RequestBoard` per scope (v1: single board,
      scope-filtered at query time)
    - [x] Posting, acceptance, fulfillment, escalation
    - [x] `GuildRequestChannel` fully wired (was stub)
    - [~] Player fulfillment UI (deferred — debug command path
      lands now; screen lands with the Phase 5 GUI polish pass)
    - [~] Migrate existing `Quest` to `Request.HUNT`/`SURVEY`
      (deferred — adventurer Quest path keeps its existing UI;
      see Revision Notes)
- [x] **26** NPC companies
    - [x] Company ownership extension (PLAYER/NPC)
    - [x] `AiCompanyManager` daily
    - [x] Merchant → trading company promotion
    - [x] Succession on owner death
    - [x] `CARAVAN_ATTENDANT` worker role
- [x] **29** Visitor flux
    - [x] `Visitor` entity (component on TownspersonMob), 8 visitor types, itinerary
    - [x] `VisitorFluxEngine` daily arrival
    - [~] Activity handlers (uniform walk-pay-leave handler in v1; per-type
      polish is Phase 5)
    - [x] `VisitorChannel` fully wired
    - [x] Coin flow into village treasuries
- [x] **30** Village history
    - [x] Event archival after 365 days (per-importance retention)
    - [x] Prestigious name tracking (NotablePersonRegistry)
    - [x] Kingdom history compilation (`kingdomCompilation` query)
- [ ] **Exit criteria**: 3 specialized villages (agricultural,
  craft, scholar) running 30+ days in a kingdom trade together
  via requests/caravans; visitors arrive at appropriate rates;
  village history tracks notable events

---

## Phase 5 — Polish & content

Goal: visual differentiation, cultural character, event richness,
authored content.

- [ ] Read Phase 5 specs (31–34) end-to-end
- [x] **31** Cultures wired
    - [x] Extended `Culture` record with all sub-bundles
    - [x] 4 starter cultures fully specified
    - [x] `CultureResolver` utility
    - [~] Wiring in schedule/office/law/economy/hobby/visitor/
      apprenticeship (office, law, hobby, visitor, religion,
      trait-bias wired; schedule layering + economic norms +
      apprenticeship deferred — see Revision Notes)
- [x] **32** Events expanded
    - [x] Full `EventType` enum (~33 types) + `EventCategory`
    - [x] `EventScheduler` with calendar / life-event / crisis sources
      (player + visitor sources via existing UI/visitor hooks; richer
      player-host UI deferred — see Revision Notes)
    - [x] Type-specific handlers (`EventHandlerRegistry` — functional
      but content-thin per spec; flavor pass is doc 34)
    - [x] Attendance logic (`EventAttendance`) with required + invited
      decision + per-attendee `eventOverride` apply / clear
    - [x] 365-day archival via `pruneOldCompletedEvents`
    - [x] PLAGUE_OUTBREAK migrated from Phase 3 to schedule a
      first-class event in addition to the health record
    - [x] `/event list / schedule / start / complete / cancel`
      debug commands
- [~] **33** Appearance Layer 1 — infrastructure only
    - [x] `AppearanceComponent` extended (cultureBaseId, skinToneVariant,
      officeMarks, accessoryIds, lifeStageDecoration, lastRebuildTick;
      additive NBT save/load)
    - [x] `AppearanceLayerRegistry` with 4 cultures, ~17 office marks,
      ~10 accessories — every entry carries the canonical resource
      path the renderer will read once art lands
    - [ ] Culture base textures authored (4 × 3-4 variants) — DEFERRED
      to art pass
    - [ ] Office mark overlays authored — DEFERRED to art pass
    - [ ] Cultural accessory models authored — DEFERRED to art pass
    - [x] Rebuild hooks on state change (LifeStageAdvanced /
      OfficeChange / Hired / Fired / Promoted / Demoted via
      `AppearanceLifeEventProducer`; profession change inline in
      `setProfession`; spawn-time generation in
      `applyVillageCulture`)
    - [x] `/appearance show / rebuild / set culture / set variant /
      gift` debug commands
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

**Phase 2 is COMPLETE.** All 8 phase 2 tasks shipped:
- 11 NPC relationship ledger
- 12 Gossip/rumor
- 13 Weekly schedule
- 14 Hobby activities
- 15 Child/elderly arcs
- 16 Apprenticeship
- 17 Scribal professions
- 18 Letters/books

**Phase 2 exit-criteria scenario** (per NPC_PLAN.md): NPC
relationships, gossip, apprenticeship arcs, letters,
schooling-to-apprenticeship handoff, and elderly
mentorship/unfinished-business → DIE_WITH_REGRET all
implemented. Verification deferred until a successful
local build run.

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

---

**Phase 4 progress (next session)**: task 25 (resource
categories) complete. Phase 4 opens here.

New types in `Village.Simulation`:
- `ResourceCategory` (12 entries: FOOD, BUILDING_MATERIALS,
  SEEDS, TOOLS, WEAPONS, CLOTH, LUXURY, PAPER, LITURGICAL,
  MEDICINE, LIVESTOCK, COIN_INFLUX) with `isPhysical()` helper
  (false only for COIN_INFLUX) and a STRING xmap codec.
- `BuildingResourceProfile` record (production / consumption
  enum-keyed maps) + static `TABLE` populated for ~30 building
  types per the spec's first-pass numbers. Buildings with no
  entry contribute neutrally per spec line 214.

`VillageSimData` refactor:
- `Map<ResourceCategory, Float>` for production / consumption,
  replacing the four flat float fields. Queries `production(c)`,
  `consumption(c)`, `net(c)`, `isExporterOf(c)`,
  `isImporterOf(c)`. Read-only views via `productionView()` /
  `consumptionView()`.
- Backward-compat codec: legacy `foodProductionPerDay`,
  `materialProductionPerDay`, `foodConsumptionPerDay`,
  `materialConsumptionPerDay` still read with `optionalFieldOf`
  default 0f and merged into the maps in `fromCodec`. Writes
  always emit zero for those fields, which DFU's optional codec
  elides — so a v1 save migrates cleanly and the next save
  drops the legacy keys.
- `blendReal(realProd, realCons, pop, tick)` — 80/20 blend per
  category, with a `putOrRemove` floor that drops categories
  whose blended value decays to 0.
- `advanceSim(seasonFoodMult, genericMult)` — multiplicative
  drift on FOOD via merge with delta `current * (mult - 1)`.

`VillageSimEngine` rewrite:
- `syncFromReal` walks village buildings, sums each
  `BuildingResourceProfile` entry into per-category EnumMaps,
  adds population-driven FOOD consumption with seasonal
  multiplier, adds direct FOOD production from STOCKPILE
  nutrition counts, and stubs COIN_INFLUX visitor flux at 0
  pending Phase 4 doc 29.
- `buildBaseline` — same accumulation but no real measurement,
  used at village spawn and as the orElseGet path in tick.
- `reconcileOnLoad` deduped (the old file shipped two copies);
  uses `sim.net(FOOD)` and `sim.net(BUILDING_MATERIALS)` for
  bread / log / cobblestone materialisation.
- `advanceSim` delegates to `VillageSimData.advanceSim` with
  the season's food multiplier.

`KingdomEconomyEngine.findExportPartner` generalised to take a
`ResourceCategory`. Per-category surplus thresholds in
`EXPORT_THRESHOLDS` (FOOD ≥ 200, BUILDING_MATERIALS ≥ 32) with
a fallback `defaultExportThreshold` for any other category.
`evaluate` loops the registered thresholds and calls
`handleDeficit` per category in negative net.

Debug:
- `/sim resources <village>` — table of every category's
  production / consumption / net per day with sign-coloured
  net column.
- `/sim category <village> <category>` — single-category
  detail.
- `/sim profile <buildingType>` — print the
  `BuildingResourceProfile.TABLE` entry.

Audit-discovered fixes:
- `reconcileOnLoad` re-blend was passing
  `new EnumMap<>(unmodifiableViewOfEnumMap)` which throws
  IllegalArgumentException when the view is empty (brand-new
  village in its first day). Switched to
  `new EnumMap<>(ResourceCategory.class)` + `putAll(view)` so
  the empty-map path is safe.
- FARMHOUSE consumption table listed SEEDS twice via the 3-arg
  `m1` helper, doubling the seed-consumption rate. Trimmed to
  one SEEDS entry.

Spec deviations + deferrals (logged in 25 Revision Notes):
- `NeedCategory` left intact rather than aliased — it's a spot-
  state stockpile query (`village.getNeeds().get(...)`),
  semantically separate from the rolling-average sim. The
  shared FOOD / BUILDING_MATERIALS / SEEDS naming makes a
  future unification mechanical.
- COIN_INFLUX visitor-flux source is a 0 stub — Phase 4 doc 29
  wires the real estimate.
- `BuildingResourceProfile.TABLE` numbers are first-pass per
  spec line 118; Phase 5 content tuning revisits.
- Negative production in a profile is preserved as-given (the
  per-tick blend clamps inputs ≥ 0 via `clamp` in
  `blendReal`); spec line 217 calls for clamping which is
  honoured at the blend step rather than the table step.

Build verification deferred (sandbox blocks maven.neoforged.net).

---

**Phase 4 progress (next session)**: task 27 (guild refactor)
shipped as an additive abstract layer per spec "Open decisions"
#1.

New package `Guilds.Common`:
- `GuildType` (ADVENTURER / CRAFTSMEN / MERCHANTS /
  AGRICULTURAL / RELIGIOUS / SCHOLARLY) with profession
  membership tables and `primaryFor(Profession)` resolver.
- `GuildLevel` (IMPLICIT / ESTABLISHED / RECOGNIZED /
  PROMINENT) with `canPostRequests` /
  `canAcceptRemoteRequests` gates.
- `GuildRankTier` (APPLICANT / BRONZE / SILVER / GOLD /
  PLATINUM / ELDER) — distinct from the legacy adventurer
  `GuildRank` (BRONZE..DIAMOND, XP-driven).
- `GuildMemberRef` record + `GuildTreasury` (200-bronze L1
  starting balance) + `GuildHallTypes` bidirectional
  hall-type lookup.
- `AbstractGuild` — single concrete class for all six
  categories; the polymorphic codec is a tagged record
  with `GuildType` as the discriminator.
- `GuildSavedData` (`SavedDataType`
  "life_in_the_village_guilds_v2") storing the abstract
  guilds keyed by guildId.
- `GuildBootstrap` — `scanAndCreateImplicit` (cluster ≥ 2
  spawns implicit guilds), `onProfessionChanged` (auto-
  drop / auto-join on profession swaps), `onHallConstructed`
  (L0 → ESTABLISHED on hall placement).

Building system:
- 5 new `BuildingType` entries: `GUILD_HALL_CRAFTSMEN`,
  `GUILD_HALL_MERCHANTS`, `GUILD_HALL_AGRICULTURAL`,
  `GUILD_HALL_RELIGIOUS`, `GUILD_HALL_SCHOLARLY`. The
  existing `GUILD_HALL` keeps its meaning as the adventurer
  hall — no rename, preserves save compat for the 25-file
  consumer surface that references `BuildingType.GUILD_HALL`.
- 6 registry locations updated for the 5 new halls:
  ZoneRegistry, BuildingProfileRegistry,
  BuildingInhabitantRegistry, BuildingTypeFlags,
  BuildingResourceProfile, and `Profession.professionFor`.

Hooks:
- `TownspersonMob.setProfession` records the previous
  profession and routes through
  `GuildBootstrap.onProfessionChanged` on every change.
- `VillageSpawner` ends each spawn with a
  `scanAndCreateImplicit` pass and an `onHallConstructed`
  loop over each placed hall.

Debug:
- `/guilds list / info / members / promote / upgrade /
  create / contribute` — abstract layer parallel to the
  legacy `/guild` adventurer commands.

Spec deviations + deferrals (logged in 27 Revision Notes):
- Big-bang `GuildData → AdventurerGuild` rename
  **deferred**. Spec "Open decisions" #1 already proposed
  the additive path; v1 follows it. The legacy `GuildData`
  record stays intact alongside the new abstract layer.
  An adapter pass migrates the 25 consumer files
  incrementally.
- Single concrete `AbstractGuild` class instead of six
  thin subclasses. Spec line 63 already calls them "thin
  extensions"; v1 collapses to a tagged class. Phase 5
  can split when type-specific behaviour diverges
  (masterpiece certification, trade-volume tracker, etc.).
- `master_of_apprentices` office wiring deferred —
  apprenticeship (Phase 2 task 16) hasn't shipped.
- Treasury L1 starting balance set at 200 bronze.
  Documented as a tuning candidate per "Things to flag" #2.
- Member skill minimum gating ("Things to flag" #3) not
  enforced in v1 — belongs on a future explicit-join
  verb (Phase 5 polish).
- `/guilds` plural literal instead of extending `/guild`
  to avoid stomping the existing `GuildCommands` tree.
  Both literals coexist.
- Auto-spawn recipe for the new halls in `BuildingRegistry`
  deferred — registering them now would force every new-
  spawned village to ship one of each. Manual placement
  via debug commands works; Phase 5 worldgen tuning
  can register selective spawn rules.

Build verification deferred (sandbox blocks maven.neoforged.net).

---

**Phase 4 progress (next session)**: task 26 (NPC-owned companies)
shipped.

Company extension:
- `OwnerType` (PLAYER / NPC), `CompanyType` (STANDARD /
  TRADING_COMPANY), `SuccessionState` (ACTIVE / UNDECIDED /
  DISSOLVED) inner enums on Company.
- `CARAVAN_ATTENDANT` added to `Company.WorkerRole`.
- 8 new fields: ownerType, ownerId, heirs, companyType,
  successionState, foundedTick, dissolutionWarningTick,
  undecidedSinceTick. All persisted via the codec's
  optionalFieldOf so v1 saves migrate cleanly: ownerType
  defaults PLAYER, ownerId.orElse(ownerPlayerId), companyType
  STANDARD, successionState ACTIVE, all timestamps 0.
- `setNpcOwner(UUID)` flips ownership and replaces the
  company_owner office holding (previously seeded by the
  legacy player path).

Promotion path:
- `MerchantPromotion.isEligible` enforces COMMERCE >= 70,
  wallet >= 500 br, MARKET assignment, and 365-day continuous
  merchant tenure via the new `professionStartedTick` field
  on TownspersonMob (resets on every setProfession change).
- `MerchantPromotion.promote` creates a TRADING_COMPANY
  named "Elara's Trading House" (NPC name + " 's Trading
  House"), transfers 100 br from NPC wallet to company
  treasury, makes the NPC owner-as-PRODUCER worker, registers
  via CompanySavedData. forcePromote skips eligibility for
  /company promote testing.

AI manager:
- `AiCompanyManager.dailyTick` registered as
  `CompanyAiTickSystem` (interval 24000, priority 202 — after
  health/plague subsystems). Per company:
  - Owner liveness check; dead/demoted owner triggers
    handleSuccession.
  - Bankruptcy clock — 14-day warning at treasury <
    expenses + 50 br, 30 more days dissolves.
  - UNDECIDED grace window — 30 days to resolve heirs;
    failing dissolves.
  - Promotion scan iterates loaded merchants per village.
- `handleSuccession` walks the heir chain; first living
  adult heir takes over; otherwise UNDECIDED for 30 days.
- `dissolve` distributes treasury as severance to remaining
  workers and marks DISSOLVED + isActive=false (record
  preserved for historical lookups).
- `OwnerBias` snapshot reads owner traits (ambition /
  industry / temperance / compassion) for caravan dispatch +
  wage decisions; surfaced now, consumed by Phase 5 polish.

Caravan dispatch (stub):
- `dispatchTradingCaravan` deposits a fixed 50 br profit so
  the trading-company branch is verifiable end-to-end via
  /company dispatch. Real wiring into CaravanSavedData /
  CaravanGoodsSelector deferred — those classes don't yet
  expose a public dispatch API. Spec line 144 (3x
  village-merchant range = 9000 blocks) is encoded as
  `TRADING_RANGE_MULTIPLIER = 3.0` on the manager so the
  follow-up wire-up reads it.

TownspersonMob:
- `professionStartedTick` field — reset to current tick on
  every profession change in setProfession; persisted via
  NBT key "professionStartedTick"; getter
  `getProfessionStartedTick()`. Backed off to 0 on legacy
  saves.

Debug:
- `/company list-npc <village>` — all NPC-owned companies
  in the village with type, state, treasury, worker count.
- `/company promote <npc>` — force-promote (bypasses
  eligibility checks).
- `/company owner <companyId>` — owner type / id / state /
  heirs / treasury / dissolution clocks.
- `/company succeed <companyId>` — force succession run.
- `/company dispatch <companyId>` — trigger the trading-
  caravan stub.

Spec deviations + deferrals (logged in 26 Revision Notes):
- Caravan goods selection / actual travel deferred —
  `dispatchTradingCaravan` is a treasury-deposit stub. Phase
  5 wire-up reads ResourceCategory surplus / deficit per
  destination once CaravanSavedData exposes a dispatch API.
- Bankruptcy floor set at 50 br + 14-day warning + 30-day
  grace per spec "Open decisions"; tunable.
- Will-overridable heir designation deferred — succession
  defaults to the static heir list (initialised empty;
  caller adds via addHeir / setHeirs). Spec line 134 calls
  for a scribe-produced will via the letter / contract path,
  which doesn't ship until Phase 4 doc 28+ wires the
  contract surface.
- CARAVAN_ATTENDANT role enum added but the existing
  caravan crew assignment (CaravanGuardGoal etc.) doesn't
  yet read it — wires when the caravan dispatch lands.
- company_foreman / company_bookkeeper office population
  deferred — owner-appointed offices belong on the office
  selection extension that Phase 5 ships.
- Player-NPC competition pricing pressure deferred — both
  flavours of company coexist via DirectBusinessChannel
  reading multiple producers, but the AI manager doesn't
  yet adjust prices in response to player presence.

Build verification deferred (sandbox blocks maven.neoforged.net).

---

**Phase 4 progress (next session)**: task 28 (request board)
shipped.

New package `Guilds.Common.Requests`:
- `RequestType` (GATHER / CRAFT / DELIVER / HUNT / SURVEY /
  ESCORT), `RequestStatus` (OPEN / ACCEPTED / IN_PROGRESS /
  FULFILLED / FAILED / EXPIRED), `RequestScope`
  (VILLAGE_INTERNAL / VILLAGE_PUBLIC / KINGDOM_WIDE /
  GLOBAL) — escalation cadence 3 / 7 / 14 days per spec.
- `Request` record split into `Target` + `Progress` sub-
  records so the outer codec stays under DFU's 16-field cap
  (same pattern as `Company.OwnershipInfo`).
- `RequestBoard` SavedData
  ("life_in_the_village_request_board") — single global
  store; `availableFor(guild, level)` filters by scope at
  query time so the spec's three nested boards collapse to
  one structure with a discriminator field.
- `RequestPosting` — escrow withdraw of bounty + 10%
  platform fee from origin guild treasury; builder
  helpers for items / hunt / location target families.
- `RequestSettlement` — fulfilled / failed / expired flows
  per spec lines 164-179: 80% of bounty to accepting guild,
  70% of that to fulfiller wallet, 10% platform fee to the
  origin village's treasury; failed gives accepting guild
  50% penalty cut and refunds the rest to origin.
- `RequestBoardTicker` daily — fulfils completed requests,
  expires stale ones, escalates scope per the spec ladder,
  runs the acceptance pass (highest-rank-member fulfiller;
  treasury tiebreak), prunes terminal records after 30 days.

Channel wire-up:
- `GuildRequestChannel` (Phase 3 stub) replaced with the
  real implementation. Quotes for non-IMMEDIATE BUY intents
  at `maxPrice + 10 br`, validity 1 day, deferred 7 days.
  `execute` posts a Request via the actor's primary L1+
  guild (or any village L1+ guild as fallback) and returns
  partial-success (quantityTraded=0, totalBronze=bounty)
  per spec line 232.

Daily tick:
- `RequestBoardTickSystem` (interval 24000, priority 203 —
  after company AI at 202 so trading-company posts settle
  before the request-board pass picks them up).

Debug (`/request` top-level literal):
- `/request list [scope]`
- `/request post <guildId> <type> <targetId> <count> <bounty> [scope]`
- `/request accept <requestId> <guildId>`
- `/request fulfill <requestId>`
- `/request escalate <requestId>`

Spec deviations + deferrals (logged in 28 Revision Notes):
- **Single global RequestBoard** instead of separate per-
  village + per-kingdom + global boards. Scope filtering
  happens at query time. Profiling can drive a split later.
- **Quest → Request migration deferred.** Existing
  adventurer `Quest` records keep their dedicated UI /
  data store; the new abstract layer runs alongside per
  the same additive pattern as the guild refactor.
- **Player fulfillment UI deferred.** Debug command path
  works; the in-game Request Board screen lands with
  Phase 5's GUI polish pass.
- **Caravan dispatch on accept deferred.** Acceptance
  flips status to ACCEPTED + records the fulfiller;
  IN_PROGRESS → FULFILLED reads `progress.totalProgress`
  set on accept and current driven by `/request fulfill`
  (debug) or future production-cycle hooks. Real caravan
  dispatch wires when `CaravanSavedData` exposes a public
  dispatch entry — same blocker as doc 26's trading
  caravan.
- **Office posting screens deferred.** v1 ships the data
  path + the `/request post` debug command; the in-game
  office UI for posting lands with Phase 5.
- **Acceptance scoring** is first-eligible (with member-
  count + treasury tiebreak). Spec line 131's "reward-to-
  effort" scoring needs a per-guild capacity model
  (workshop load, member skill match) that v1 doesn't
  ship.

Build verification deferred (sandbox blocks maven.neoforged.net).

---

**Phase 4 progress (next session)**: task 29 (visitor flux)
shipped.

New package `Npc.Visitor`:
- `VisitorType` (PILGRIM / MERCHANT_ITINERANT / TRAVELER /
  STUDENT / ENVOY / REFUGEE / SCHOLAR_VISITING / MINSTREL)
  with walletMin/Max ranges, `underlyingProfession()`
  mapping (MERCHANT_ITINERANT → existing
  Profession.WANDERING_TRADER per "Things to flag" #4),
  and `carriesGoods()` for the channel filter.
- `Activity` enum with `targetBuildings`, `bronzeCost`,
  `defaultDurationTicks`.
- `VisitorItinerary` record (buildingId, activity,
  expectedDurationTicks).
- `VisitorState` component on TownspersonMob — visitorType,
  origin village, arrival/departure ticks, itinerary,
  current index, settledPermanently. Codec arity 7 (well
  under DFU's 16-field cap). `isVisitor()` / `shouldDespawn`
  helpers; settledPermanently flips off the visitor flag
  when a refugee is accepted.
- `VillageVisitorCapacity` record + `compute(village,
  data)` per spec lines 252-264 (INN +5/+0.5 TRAVELER,
  TEMPLE +3/+0.3 PILGRIM, MARKET +3/+0.4 MERCHANT, etc).
  Hard ceiling 20 concurrent.
- `VisitorFluxEngine.dailyTick` — despawn expired,
  capacity check, weighted-random arrival roll, spawn
  via `ModEntities.TOWNSPERSON.create` at a random side
  of the village bounds AABB, basic single-stop itinerary
  keyed off visitor type.
- `VisitorFluxEngine.estimateFlux` replaces the old
  zero-stub on `VillageSimEngine.estimateVisitorFlux` so
  the COIN_INFLUX category in the resource sim now
  reflects expected visitor spending.

New goal:
- `VisitorGoal` (in Entities.Goals.Visitor) registered at
  P_SOCIAL_HIGH via `ProfessionGoalFactory.registerUniversal`.
  Three phases (WALKING_TO_LOCATION / AT_LOCATION /
  LEAVING). At each stop: walk → idle for the activity's
  default duration → pay the activity's bronze cost into
  the building's economy. PERFORM additionally applies +5
  mood (FESTIVAL_ATTENDED trigger) to nearby residents.

Channel wire-up:
- `VisitorChannel` (Phase 3 stub) replaced. Available when
  the village currently hosts a MERCHANT_ITINERANT or
  SCHOLAR_VISITING visitor. Quotes BUY intents at
  `intent.maxPrice() + 5 br` foreign-trader markup.
  v1 execute is a data-layer success without inventory
  transfer (Phase 5 polish wires the stall stock + market
  tax cut).

Daily tick:
- `VisitorFluxTickSystem` (interval 24000, priority 204 —
  after request board at 203 so the same-day economic
  state has settled before deciding who's interested in
  showing up).

Debug:
- `/visitor spawn <village> <type>` — force-spawn an
  arrival outside the daily roll.
- `/visitor list <village>` — currently-active visitors
  with type, arrival tick, current step, wallet.
- `/visitor capacity <village>` — computed
  VillageVisitorCapacity.
- `/visitor itinerary <visitorId>` — full plan for one
  visitor with the current step highlighted.

Spec deviations + deferrals (logged in 29 Revision Notes):
- **No separate `Visitor extends TownspersonMob` entity
  class.** v1 ships the visitor as a `VisitorState`
  component on the existing TownspersonMob — same
  pattern as Phase 3 PietyComponent / HealthComponent.
  Sidesteps NeoForge entity-registration boilerplate
  and lets the wandering trader path remain unchanged.
- **Per-type activity behaviour is uniform in v1.**
  Every Activity walks to target → idles for the default
  duration → pays the flat bronze cost. The spec's
  type-specific surfaces (PILGRIM kneels at altar,
  MINSTREL spawns rumors via gossip, ENVOY hands a
  sealed letter via Phase 2 letter system, REFUGEE
  triggers leader-decision dialogue, SCHOLAR_VISITING
  fires high-fidelity knowledge transfer) are Phase 5
  polish. The Activity tag stays so the polish routes
  via the existing VisitorGoal switch.
- **Single-stop itinerary** in v1; multi-stop planning
  is the natural Phase 5 follow-up.
- **VisitorChannel.execute does not transfer items**
  yet — the spec's stall-stock model isn't implemented.
  v1 returns success and the buyer's bronze flow happens
  at the channel router level; Phase 5 wires withdrawal
  from the seller's stash + market tax cut.
- **Refugee settlement** — the `settledPermanently`
  flag on VisitorState exists for this transition, but
  the leader-decision UI / auto-accept rules per spec
  lines 199-208 are deferred. v1 refugees stay as
  ephemeral STAY visitors until their 7-day ceiling.
- **Envoy letter content** is deferred per "Things to
  flag" #3. The envoy walks to the TOWN_HALL and idles;
  no actual sealed-letter delivery yet.

Build verification deferred (sandbox blocks maven.neoforged.net).

---

**Phase 4 progress (next session)**: task 30 (village history)
shipped. **Phase 4 closes here.**

New package `Village.History`:
- `HistoryImportance` (MINOR / NOTABLE / MAJOR / LEGENDARY)
  with `retentionTicks` per spec lines 138-145 (1y / 2y / never
  / never).
- `HistoryEventType` — ~40 entries spanning every Phase 1-4
  system. Each carries a `defaultImportance` and a
  `propagatesToKingdom` flag for the kingdom-history feed.
- `HistoryEntry` record (10 fields, codec arity well under
  DFU's 16-field cap) with `create` / `of` factories that
  route through the template registry.
- `HistorySummaryTemplates` registry — string templates per
  event type with `{placeholder}` substitution. Missing keys
  leave the literal placeholder so producers that forget a
  detail key produce a slightly ugly entry instead of a crash.
- `VillageHistoryLog` SavedData
  ("life_in_the_village_history") — single per-world store
  with a `byVillage` map. Spec's per-village log shape
  preserved without one SavedData slot per village.
  Queries: all / recent / byType / byImportance / byNpc /
  inRange / kingdomCompilation. `prune` enforces the
  retention windows + 200 MINOR cap + 1000 total cap per
  village.
- `NotablePerson` record + `NotablePersonRegistry` SavedData
  ("life_in_the_village_notable_persons") — kingdom-keyed
  store of NPCs who reached legendary status. Cached
  npcName so old entries resolve after the NPC's own data
  is evicted (spec line 345).
- `HistoryProducer` — `EventDispatcher` registered on
  `NpcLifeEventBus` for the four lifecycle archival hooks
  (BIRTH, MARRIAGE, DEATH_NATURAL, COMING_OF_AGE) plus
  static `record` / `recordIn` helpers used by the
  non-lifecycle producers.

Producer hooks wired:
- `VillageSpawner` → VILLAGE_FOUNDED (LEGENDARY) on every
  new village.
- `NpcLifeEventBus` → BIRTH / MARRIAGE / DEATH_NATURAL /
  COMING_OF_AGE via the HistoryProducer dispatcher.
- `PlagueScheduler.start` + `HealthTicker` auto-resolve
  paths → PLAGUE_OUTBREAK + PLAGUE_RESOLVED (with duration
  + death-count details).
- `LawEnactment.enact / repeal` → LAW_ENACTED / LAW_REPEALED.
- `MerchantPromotion.promoteUnchecked` → COMPANY_FOUNDED.
- `AiCompanyManager.dissolve` → COMPANY_DISSOLVED.
- `VisitorFluxEngine.spawnVisitor` → ENVOY_RECEIVED for
  ENVOY, FAMOUS_VISITOR for SCHOLAR_VISITING / MINSTREL.
  Routine traveler / pilgrim / merchant arrivals don't
  bloat the log.
- `TrialExecutor` verdict path → TRIAL_HELD; bumped to
  LEGENDARY when the punishment is EXECUTION or EXILE.

Daily tick:
- `HistoryPruneTickSystem` (interval 24000, priority 205 —
  runs last so producers fired during the same wave land
  in the log first).

Debug:
- `/history list <village> [importance]`
- `/history recent <village> <count>`
- `/history legendary <village>`
- `/history add <village> <type> <summary>`
- `/history prune <village>`
- `/history notable [count]`

Spec deviations + deferrals (logged in 30 Revision Notes):
- **Single per-world `VillageHistoryLog`** instead of one
  SavedData per village. The byVillage map gives the same
  shape with one less slot per village; queries filter by
  villageId at the API surface.
- **No history viewer / chronicle UI screen** (spec lines
  230-233) — debug commands cover the query surface;
  Phase 5 GUI polish ships the screen.
- **No "Read village history" player verb** (spec line 11
  in the brief) — same UI deferral.
- **No procedural ledger book authoring** by the village
  scribe (spec lines 192-208). Phase 2's scribal-book
  authoring path didn't ship a finished generator;
  hooking it requires landing the production-cycle wire
  first.
- **Lifecycle-event archival uses NpcLifeEventBus events**
  rather than firing dedicated history-only events. The
  spec's "births / deaths / marriages / coming-of-age"
  table (line 159) maps to existing
  Married / BirthInFamily / FamilyDeath / LifeStageAdvanced
  events.
- **No archival hooks for masterpiece, festival, famine,
  caravan-loss, harvest, building construction.** The
  source events don't fire today (Phase 2 apprenticeship,
  Phase 5 festivals + famine, doc 26 caravan failure
  events all unimplemented). The HistoryEventType slots
  exist so the eventual producers route via the same
  archival API.
- **Kingdom-history compilation** is a query-side
  aggregator (`VillageHistoryLog.kingdomCompilation`)
  that walks village logs and filters by
  `propagatesToKingdom`. No separate `KingdomHistoryData`
  store; every entry stays village-scoped.
- **NotablePerson auto-population** is opt-in by
  producers — the registry exists and the archival hooks
  fire, but the spec's "prestige >= 40 author" /
  "10-year leader" / "master craftsman with multiple
  certified masterpieces" criteria need their source
  systems (Phase 2 author prestige tracking, masterpiece
  certification) to ship before the auto-promotion rules
  can run.

**Phase 4 closes here.** Next session opens Phase 5 per
NPC_PLAN.md: culture across all subsystems, ~35-event
expansion, appearance Layer 1, and the content/tuning pass.

Build verification deferred (sandbox blocks maven.neoforged.net).

---

**Phase 5 progress (next session)**: task 31 (cultures wired)
shipped. Phase 5 opens here.

Data layer:
- `Cultures.Culture` rewritten as a 13-field record with codec
  (well under DFU's 16-field cap). The prior empty-stub
  `Culture` class + unused `AxolotlingCulture` subclass are
  removed — no production code consumed them.
- `Cultures.CultureBundles` packs nine focused sub-records
  (Schedule / OfficeRules / LawDefaults / EconomicNorms /
  HobbyWeights / VisitorAffinity / ApprenticeshipNorms /
  AestheticTokens / Religion). Each carries its own codec.
- `Cultures.CultureNaming` + `Cultures.CultureTraitBias` —
  carried over from the existing partial spec at the top
  level.
- `Cultures.CultureRegistry` — registers `default` plus the
  four spec starter cultures (Plainfolk / Highmarch /
  Silkwood / Tidereach) fully populated per spec lines
  137-247.
- `Cultures.CultureResolver` — single behavior-resolver entry
  point. Lives in the `Cultures` package so it doesn't
  collide with the unrelated `Village.CultureResolver` (which
  is the structure-template path resolver — different concept,
  same name).

Hooks wired:
- **Religion + trait bias** — `TownspersonMob.assignToBuilding`
  fires `applyVillageCulture` once per NPC the first time a
  village name is set. The audit caught that finalizeSpawn
  runs before assignToBuilding in the populator flow, so the
  culture-aware overwrite has to defer. Persisted via a new
  `cultureApplied` boolean field.
- **Office selection** — `Npc/Office/CultureSelectionResolver`
  (Phase 3 stub) now delegates to
  `CultureResolver.selectionFor`. Highmarch leader becomes
  HEREDITARY, Silkwood scribe COUNCIL, Tidereach bailiff
  ELECTIVE, Plainfolk leader MERITOCRATIC.
- **Visitor affinity** — `VillageVisitorCapacity.compute`
  reads the village's culture and multiplies the per-building
  base type-weights by the affinity table. Silkwood attracts
  more STUDENT / SCHOLAR_VISITING; Tidereach more
  MERCHANT_ITINERANT / TRAVELER; Highmarch more ENVOY.
- **Hobby weights** — `NpcHobbyPreference.generate`
  multiplies trait-derived score by
  `CultureResolver.hobbyWeightFor`. Highmarch sword_practice
  +80%; Plainfolk sword_practice -60%; etc.
- **Law defaults at founding** — `VillageSpawner` enacts each
  `culture.lawDefaults().initialLaws()` entry after the
  history archival step. Plainfolk village → SUBSIDIZE_FARMER;
  Highmarch → DOUBLE_PUNISHMENT + MARKET_TAX_REDUCED; Silkwood
  → COMPULSORY_SCHOOLING + BAN_EXECUTION + SUBSIDIZE_SCHOLAR;
  Tidereach → PILGRIM_WELCOME_BONUS + BAN_EXECUTION.

Debug:
- `/culture list` — every registered culture.
- `/culture info <id>` — full sub-record dump.
- `/culture resolve npc <uuid> <hook>` — invoke a resolver
  hook for one NPC and print the result.
- `/culture resolve village <name> <hook>`.

Spec deviations + deferrals (logged in 31 Revision Notes):
- **No `/culture set <village> <id>` debug.** A village's
  culture is derived from its kingdom's culture (the
  canonical source); to "change" a village's culture you
  change the kingdom's culture. Documented in 31 Revision
  Notes.
- **Schedule layering deferred.** Spec line 273 calls for
  inserting CULTURE between EVENT and WEEKLY in the
  ScheduleResolver layer stack. The data shape ships
  (`CultureSchedule.professionDayOffs` carried), but
  `ScheduleResolver.phaseAt` isn't yet refactored to query
  the layer. Phase 5 follow-up.
- **Economic norms wiring deferred.** `CultureEconomicNorms`
  ships with full data + a resolver accessor, but
  `ChannelRouter.score` doesn't yet read `hagglingTendency`
  and `VillageSimEngine.syncFromReal` doesn't yet apply
  `consumptionBias`. Phase 5 follow-up.
- **Apprenticeship norms deferred.** Phase 2 task 16
  (apprenticeship) hasn't shipped, so
  `CultureApprenticeshipNorms` has no consumer. The data
  + resolver accessor are in place for the eventual wire.
- **Aesthetic tokens deferred** — next session implements
  appearance Layer 1 per spec doc 33.
- **NpcProfileSnapshot culture tag deferred** — Phase 5
  GUI polish.
- **Village-culture resolution** — currently reads
  village → kingdom → registry default. The "founding NPC
  determines initial dominant" / "majority wins" rules
  aren't yet implemented; the kingdom-culture acts as
  the village's culture for v1. Documented as a known
  simplification.
- **Mixed-culture village dominant** ambiguity ("Things
  to flag" #1) — n/a in v1 since culture follows the
  kingdom, not the population.
- **Big-bang culture-id casing** — `CultureRegistry`
  normalises ids to lowercase on lookup so existing
  mixed-case `Kingdom.getCulture()` strings continue to
  resolve.

Audit-discovered fix:
- `TownspersonMob.finalizeSpawn` originally tried to apply
  culture biases + religion at spawn time, but the
  populator flow calls `finalizeSpawn` BEFORE
  `assignToBuilding` — so `getAssignedVillageName()` was
  always empty and the lookup degraded to "default" for
  every populator-spawned NPC, making culture inert.
  Refactored to defer the apply to a one-time
  `applyVillageCulture` call inside `assignToBuilding`,
  guarded by a persisted `cultureApplied` flag.

Build verification deferred (sandbox blocks maven.neoforged.net).

---

**Phase 5 progress (current session)**: task 32 (events
expanded) shipped. The pre-existing 5-type Phase-3 event
system (`Village.Event.VillageEvent` + `VillageEventScheduler`
+ `EventEffects`) is extended in place — no parallel
infrastructure — to satisfy the spec's ~33-type catalogue
and 5-source scheduler.

Data layer:
- `VillageEvent.EventType` extended from 5 → 33 values
  spanning 8 categories. New `EventCategory` enum + per-
  type `category()` helper. `EventStatus` keeps
  `ANNOUNCED` / `ACTIVE` / `ENDED` for save compat
  (aliased to spec's SCHEDULED / ACTIVE / COMPLETED) and
  adds `CANCELLED` + `DISRUPTED`.
- `VillageEvent` gains `location`, `primarySubjectId`,
  `requiredAttendees`, `invitedAttendees`,
  `actualAttendees`, `eventData (Map<String,String>)`,
  and `completedTick`. Codec is 14 fields total — every
  Phase-5 addition is `optionalFieldOf` so pre-Phase-5
  saves still load.

Handlers:
- `Village.Event.EventHandler` interface + `EventHandlerRegistry`
  ship 28 handlers covering every Phase-5 type. Phase-3
  originals (HARVEST_FESTIVAL / MARKET_DAY /
  FESTIVAL_OF_LIGHTS / TRAINING_DAY / VILLAGE_FAIR) keep
  their bespoke logic in `EventEffects`; the registry
  takes over for everything new via a `default ->`
  routing branch.
- Per spec "functional but content-thin" — each handler
  on completion writes a SHARED_FESTIVAL memory to each
  actual attendee, fires
  `NpcLifeEvent.SharedFestival` on the bus per
  attendee, and records one village-history entry.
  Religious-rite events also fire the matching
  `Rite` via `RiteScheduler.schedule` at start.
  Per-type rumor flavor + dialogue hooks are deferred to
  the doc 34 content pass.

Sources wired:
- **Calendar** — annual season-transition events
  (HARVEST_FESTIVAL, FESTIVAL_OF_LIGHTS) are inherited
  from Phase 3. New `checkCulturalHolyDay` runs once per
  in-game day and schedules a culture-specific religious
  event (Plainfolk → SUNSTEAD_EQUINOX, Highmarch →
  FORGE_CREED_KINGDOM_DAY, Silkwood → LOOM_THREADING,
  Tidereach → TIDECALL_FULL_MOON) every
  `culture.schedule().holyDayInterval()` days.
- **Life-event** — `EventLifeEventProducer` subscribes to
  the bus and schedules WEDDING (on `Married`),
  NAMING_CEREMONY (`BirthInFamily`), FUNERAL
  (`FamilyDeath`, deduped by deceased UUID), COMING_OF_AGE
  (`LifeStageAdvanced` TEEN→ADULT), OFFICE_INAUGURATION
  (`OfficeChange`). Required = primary subject(s);
  invited = household. Lead-in is 1 day.
- **Crisis** — `checkCrises` polls village state once per
  day. FAMINE fires when `village.isCritical()` (food)
  with a 7-day re-trigger lockout. FIRE rolls 0.5%/day
  with the same lockout. PLAGUE_OUTBREAK is now scheduled
  by `PlagueScheduler.start` in addition to the existing
  health record (single source of truth for the disease,
  but the event subsystem now sees it for archival /
  attendance / history).
- **Player + visitor** — Phase-3 player notice and visitor
  hooks already exist; Phase 5 leaves the existing UI
  path in place. A richer "Schedule Event" office UI is
  deferred to a polish pass (see Revision Notes).

New life event:
- `NpcLifeEvent.OfficeChange(subject, officeId, previousHolder)`
  — the office-election system doesn't yet fire this; the
  producer is in place to consume it as soon as the
  election layer wires it (one-line hook).

Attendance:
- `EventAttendance.applyOverrides` runs from `onEventStart`
  for every event. Required attendees always show up if
  alive + on-world; invited go through the spec
  attendance algorithm (base + relationship-to-primary +
  mood penalty + schedule conflict). Each attendee gets
  `setEventOverride(type)` so the AI pulls them off
  schedule for the duration. `clearOverrides` runs on
  ENDED + CANCELLED.

Persistence + archival:
- `VillageSavedData.pruneOldCompletedEvents(currentTick)`
  drops ENDED / CANCELLED / DISRUPTED events 365 in-game
  days after their `completedTick`. The existing
  `removeEndedEvents` is left in place as a hard-delete
  utility.
- The event sub-system writes a single
  `VillageHistoryLog.record` per completed event (per
  type's mapped HistoryEventType), so the village
  history retains a permanent summary even after the
  365-day prune.

Debug:
- `/event list <village>` — every event with status,
  type, category, ticks, attendance, primary subject.
- `/event schedule <village> <type>` — force-schedule for
  start in 1 minute, with the whole village as invited.
- `/event start <eventId>` — flip ANNOUNCED → ACTIVE.
- `/event complete <eventId>` — flip ACTIVE → ENDED with
  full attendance teardown.
- `/event cancel <eventId>` — set CANCELLED + clear
  overrides.

Spec deviations + deferrals (logged in 32 Revision Notes):
- **EventStatus naming** — kept ANNOUNCED / ENDED instead
  of the spec's SCHEDULED / COMPLETED to preserve save
  compatibility. Documented as aliases.
- **Player "Schedule Event" UI** — spec § 332 calls for an
  office-holder picker UI. v1 ships only the
  `/event schedule` command; UI is a polish pass.
- **UpcomingEventsScreen** — spec § 322 client-side
  panel deferred; `/event list` covers the read path.
- **1-day-before greeting reminder** — spec open-decision
  proposes a "don't forget the wedding tomorrow"
  greeting branch; deferred to dialogue polish.
- **Failed-attendance penalty** — spec § 422 proposes
  -15 relationship for required attendees who miss; not
  enforced in v1 (required always attend if alive).
- **Crisis event disrupts active event** — spec § 397
  suggests crisis takes priority. v1 lets concurrent
  events coexist; DISRUPTED status is plumbed but not
  yet applied automatically.
- **Visitor-driven scheduling** — spec § 178 lists
  CARAVAN_ARRIVAL / ENVOY_RECEPTION / SCHOLAR_EXCHANGE
  as visitor-spawn triggers. The event types and
  handlers ship; the `VisitorFluxEngine.spawnVisitor`
  → schedule hook is deferred (one call per spawn type).
- **GUILD_FAIR / CRAFT_CONTEST player-host trigger** —
  data shape ships; UI is the polish pass.
- **Event-duration scaling for small villages** — spec
  open-decision proposes scaling. v1 uses the per-type
  flat `getDurationTicks`.

Build verification deferred (sandbox blocks maven.neoforged.net).

---

**Phase 5 progress (current session)**: task 33 (appearance
Layer 1) — **infrastructure only, by user direction**. The
Java data + wiring layer ships in full; texture / model
assets are deferred entirely to a future art pass. The
renderer is left untouched; it will read the new fields once
the art lands.

Data layer:
- New `Entities.custom.Appearance` package with five types:
  `AccessorySlot` (HEAD/SHOULDER/BELT/BACK enum),
  `CultureBase` (cultureId + base + variant texture list),
  `OfficeMark` (officeId + overlay + optional model
  attachment + priority + short label),
  `AccessoryDefinition` (id + texture + slot + optional
  model + label), `LifeStageDecoration` (postureOffset +
  limbProportion + usesCane).
- `AppearanceLayerRegistry` ships defaults: 5 culture bases
  (4 spec cultures + a `default` fallback) each with 3-4
  registered skin-tone variant paths; ~17 office marks
  matching every `OfficeRegistry.*` constant; ~10
  accessories covering the four cultures plus
  gift-circlet / gift-pendant. Resource paths follow
  `lifeinthevillage:textures/entity/townsperson/{culture
  |office|accessory}/...`.
- `AppearanceComponent` extended: cultureBaseId,
  skinToneVariant, officeMarks list, accessoryIds list,
  lifeStageDecoration, lastRebuildTick. Additive NBT
  save/load so pre-Phase-5 saves load cleanly. New
  `generateLayer1` (spawn) and `rebuild` (state-change)
  methods. `describeAppearance()` returns a single-line
  summary. Office-mark visual cap = top 3 by priority via
  `visibleOfficeMarks`.

Rebuild triggers:
- Spawn-time: `applyVillageCulture` (which already runs
  once per NPC after village assignment) calls
  `appearance.generateLayer1` with the resolved culture,
  current life-stage, and a stable UUID-derived seed.
- Profession change: inline rebuild call in
  `TownspersonMob.setProfession`.
- Bus-driven: `AppearanceLifeEventProducer`
  (registered in `NpcLifeEventBus.registerDefaults`)
  rebuilds on `LifeStageAdvanced`, `OfficeChange`,
  `Hired`, `Fired`, `Promoted`, `Demoted`.
- `AppearanceRebuilder.rebuild(npc)` walks every village /
  guild / company / kingdom on the level, collects the
  offices the NPC currently holds, then forwards to
  `AppearanceComponent.rebuild` so office-mark state
  always matches actual office-holdership.

Debug:
- `/appearance show <npc>` — culture + variant + accessory
  list with labels + office marks with priorities +
  life-stage decoration + lastRebuildTick + the resolved
  culture for cross-check.
- `/appearance rebuild <npc>` — force a fresh rebuild.
- `/appearance set culture <npc> <id>` — debug-override the
  base culture.
- `/appearance set variant <npc> <int>` — debug-override
  the skin-tone variant.
- `/appearance gift <npc> <accessoryId>` — debug-add a
  persistent accessory.

Asset deferral (deliberate):
- **No PNG textures and no JSON model attachments are
  authored in this session.** The user explicitly chose
  option (b): infrastructure only, no resource files. The
  registry stores canonical paths; Minecraft will render
  the missing-texture magenta-checker pattern for any
  layer whose texture file is absent until art lands.
- Per-culture base PNGs (4 cultures × 3-4 skin-tone
  variants ≈ 16 files), office overlay PNGs (~17), office
  attachment models (~5 .json), accessory PNGs (~10), and
  accessory attachment models — all deferred.
- The renderer integration itself is also deferred — Layer
  1 fields are persisted, syncable, and queryable, but the
  existing `TownspersonRenderer` is unchanged.

Spec deviations + deferrals (logged in 33 Revision Notes):
- **No client-server sync packet.** Spec § 215 calls for
  server-authoritative sync via entity data serializer or
  custom packet. Fields persist server-side and the
  rebuilder runs on the server tick; client-side
  visibility waits on the renderer integration.
- **No `NpcProfileSnapshot.appearance` field.** Spec § 253
  asks for a "Wearing: ..." line on the profile screen.
  Adding a field to the snapshot record requires touching
  StreamCodec + builder + screen — invasive for an
  invisible feature in v1. `describeAppearance()` is in
  place; the snapshot wire is a one-line addition.
- **No render pipeline composite pass.**
- **OfficeChange not yet fired.** Phase 5-32 added the
  life event and the appearance producer subscribes;
  `OfficeElection` finalisation doesn't yet emit it.
  Other rebuild triggers cover the gap eventually.
- **Multi-office cap visual** — top 3 by priority is
  enforced in `visibleOfficeMarks()`; the cap matters
  only at render time, which is deferred.
- **Culture migration appearance shift speed** — spec
  open-decision says "slowly". v1 rebuilds instantly on
  culture change.
- **Layer 2/3 (hair, face, wear, damage)** — Phase 6 per
  spec.

Build verification deferred (sandbox blocks maven.neoforged.net).