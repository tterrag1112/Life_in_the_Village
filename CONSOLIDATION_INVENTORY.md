# Consolidation Inventory

Inventory of plan documents, code state, V2 impact, and cross-cutting concerns for the Life in the Village mod. Sources: 66 plan markdowns + the pasted Kingdom rework plan.

---

## 1. Document Inventory

### Master plan & progress files (root)

| Doc | Stated status / key facts |
|---|---|
| `CLAUDE.md` | Repository policy. Mandates reading `ROADS_PLAN.md` / `ROADS_PROGRESS.md` before road work. Architectural constraints: no abstract method renames; extend via base hooks; planning-layer correctness over realiser heroics; "tags/enums/primitives only when a concrete consumer needs the distinction." |
| `LAYOUT_OVERVIEW.md` | Descriptive — "what shipped." References Phases 16b–23 as landed: cascade, recipe fallback chains, kingdom-rework schema fields on `VillageTypeData` (`settlementTier`, `biomeAffinity`, `kingdomRoles`, `tradePriority`, `canBeCapital`, `maxPerKingdom`) "added but not wired"; `LayoutPlan` persistence (Phase 19/20a); measurement harness `/litv measure` (Phase 23.1). Says kingdom rework owns site selection. |
| `DECORATION_PLAN.md` | Decoration master plan. Absorbs zoning rework into Phase 0a. Phases 0–4. Sequencing across reworks: "1. This rework, Phase 0; 2. Kingdom planning Slice C; 3. Trade Route Phase 7a/7b/7c; 4. NPC rework Phases 0–5; 5. This rework, Phases 1–4." Revision notes record absorbing zoning + reordering. |
| `DECORATION_PROGRESS.md` | Tracker. **Phase 0a: 17 of 20 Implemented; P0a-15 Partially-Implemented (NBT files missing); P0a-16 Not-Started (URBAN pack); P0a-18 Blocked (ZoneRegistry deletion). Phase 0b: all 6 Implemented. Phase 0c: all 4 Implemented. Phase 0d: 4 of 5 Implemented; P0d-04 Deferred (MarketStallPlacer migration).** **Phases 1–4: every task Not-Started**, except P1-01a..e, P1-02b, P1-04b which landed under the "polygon plaza" doc-04 rollout (prompts 16/17/18). P1-01..05 are marked "Superseded" by polygon work. |
| `NPC_PLAN.md` | Status: "design complete." Phase 0–5 + deferred Phase 6. 8-trait axes, 32-entry memory, 4 cultures, no warfare v1, no JSON day 1, no trade-road work. Revision Notes empty. |
| `NPC_PROGRESS.md` | **Phases 0, 1, 2, 3, 4 declared COMPLETE** with explicit "Phase X is COMPLETE" markers. **Phase 5: tasks 31, 32 shipped; task 33 infrastructure-only (textures deferred); task 34 (content pass) entirely Not-Started.** Multiple per-task deferrals (player-facing UIs, `master_of_apprentices` office, full GuildData → AdventurerGuild rename, Quest → Request migration, Visitor item-transfer, Refugee leader UI, OfficeChange life-event production wiring). Build verification "deferred (sandbox blocks maven.neoforged.net)" on every entry. |
| `ROADS_PLAN.md` | "Phase 10 infrastructure complete; next Phase 11 — player-initiated road construction." Owner: human-managed. 12 invariants. Phases 1–13. Phases 7e, 7f Slice 1+2+3, 7g, 9, 10 marked COMPLETE in plan body; 7f Slice 4 marked PLANNED. Phases 11–13 not yet started. |
| `ROADS_PROGRESS.md` | Append-only log, 5446 lines. Most recent entries cover Phase A/B/C/C.1 of the Adaptive layout cutover (in `Village/Planning/Adaptive/`) — these are placement-rework entries, not road entries. Phase 7a/7b/7c/7d/7e/7f/7g, 8a/8b/8c/8d, 9, 10 are all logged as implemented. |

### `docs/decoration_redesign/` (17 docs)

All docs follow conventions of `00-conventions.md`. Per-doc status from `DECORATION_PROGRESS.md`:

- **00-conventions** — vocabulary + package layout `Village/Decoration/{Variants,Framework,Adjunct,...}`.
- **01-decoration-framework** — Phase 0b. Implemented (P0b-01..06). DecorationTag enum, DecorationProfile registry, DecorationMatcher second-pass, /liv decoration command.
- **02-adjunct-plot-framework** — Phase 0c. Implemented. AdjunctPlot record + AdjunctPlotPlacer + AdjunctPlotRegistry + VillageSavedData wiring.
- **03-subbuildings** — Phase 0d. 4/5 Implemented; MarketStallPlacer migration (P0d-04) deferred. Anchor block + scanner + VillageSavedData registry.
- **04-town-square-rework** — Phase 1 plus the doc-04 polygon migration. Doc-04 polygon work landed (prompts 16/17/18). Legacy P1-01..05 superseded.
- **05-street-furniture** — Phase 1. P1-06..08 Not-Started.
- **06-signs-and-markers** — Phase 1. P1-09..13 Not-Started.
- **07-industry-adjuncts** — Phase 2. P2-01..06 Not-Started (depends P0c-*).
- **08-herb-and-cottage-gardens** — Phase 2. P2-07..11 Not-Started.
- **09-parks-and-gardens** — Phase 3. P3-01..04 Not-Started.
- **10-farm-plot-rework** — Phase 3. P3-05..10 Not-Started.
- **11-homesteading** — Phase 3. P3-11..16 Not-Started; explicitly depends on NPC Phase 3/4.
- **12-village-walls** — Phase 4. P4-01..06 Not-Started.
- **13-festival-grounds** — Phase 4. P4-07..11 Not-Started; depends on NPC Phase 5 events.
- **14-cemeteries** — Phase 4. P4-12..15 Not-Started; depends on NPC Phase 2 + Phase 4.
- **15-building-variants-and-colors** — Phase 0a. Implemented but with caveats: P0a-15 NBTs missing, P0a-16 URBAN pack not authored, P0a-18 ZoneRegistry deletion blocked.
- **16-variant-pack-default-culture** — drives P0a-15/16 content (manifests authored, NBTs missing).

### `docs/npc_redesign/` (35 docs, 00–34)

All match `NPC_PROGRESS.md` status above. Notable per-doc characteristics:

- **00-conventions** — package `tterrag1112.life_in_the_village.Npc.<subsystem>`; legacy migration policy (e.g. PersonalityTrait → TraitVector); save-size budget ~6 KB/NPC.
- Phase 0 docs (01–06) — all six implemented (Traits, Memory, Knowledge, Mood, Skills, Office storage).
- Phase 1 docs (07–10) — all four implemented (LifeGoals, DialogueTree, PlayerVerbs, NpcLifeEventBus).
- Phase 2 docs (11–18) — all eight implemented; child/elderly arc (15) implements `MentorshipBonus`, retirement, unfinished business; scribal professions (17) ship with full commission queue + library catalogue + `scribalData` codec; letters & books (18) introduce `WrittenLetterItem` + `ExtendedBookContent`.
- Phase 3 docs (19–24) — all six implemented; Religion ships 4 starter religions (Sunstead, The Loom, Tidecall, Forge Creed); Medicine ships HEALER profession + HEALER_HUT building + plague rolls; village laws ship 22 laws across 4 categories.
- Phase 4 docs (25–30) — all six implemented; resource categories (12 entries, COIN_INFLUX flux source); guild refactor ships AbstractGuild as additive layer; request board single-global with scope filter; companies ship NPC-owned + TRADING_COMPANY promotion; visitor flux ships 8 visitor types as `VisitorState` component.
- Phase 5 docs (31–34) — 31 (cultures) + 32 (events) shipped; 33 (appearance) infrastructure-only; 34 (content pass) Not-Started.

### `docs/zoningandlayout_redesign/` (7 docs)

- **00-PLACEMENT-REWORK-OVERVIEW.md** — original 23-phase plan; total 45-55 prompts. Four core abstractions: `RoadGraph`, `Sector`, `FeatureMap`, `LayoutPlan`. Predates V2.
- **01-PLACEMENT-ABSTRACTIONS.md** — data type / API contracts. Predates V2.
- **02-PLACEMENT-PHASES.md** — phase-by-phase implementation guide. Predates V2.
- **03-PLACEMENT-DEFERRED.md** — explicit out-of-scope list.
- **PLACEMENT-REWORK-STATE.md** — state-and-direction. Records: Phases 1–10 landed; 11/15 dropped; 16b landed (truncation cascade + general re-emit engine); 17 farm plots landed; 18 polygon consolidation landed; 19 LayoutPlan + AnchorKind landed; 20 BuildSiteFinder migration landed; 20a LayoutPlan persistence landed; 21 kingdom-schema fields landed; 22 fallback chains landed; 23.1 measurement harness landed; 23.2 (the run + polish) PENDING. Section 5 "deferred architectural question" Path 1 vs Path 2.
- **ADAPTIVE-LAYOUT-SPEC.md** — five-prompt spec (Phase A foundation, B SlotEmitter, C port RADIAL, D port the rest, E re-measure). Replaces imperative slot emission with declarative `LayoutBlueprint`+`SlotIntention`+`Anchor` (PlazaPerimeter / RoadAlong / AllSpurs / RingValidated / NamedAnchor / RegionalGather). Per `ROADS_PROGRESS.md`, Phases A, B, C, C.1 logged; Phases C.2 / D / E pending.
- **ADAPTIVE-VILLAGE-DESIGN.md** — supersedes the Adaptive Layout Spec. Defines V2: 5 layers (FeatureMap → SiteContext → Building Placement → Road Network → Validation), kills ShapeType + 17 recipes + slot/intention/anchor machinery + cascade engine + `PlacementMatcher`'s slot-based matching. Migration: build under `Village/Planning/V2/` with config flag `adaptive_v2: true/false`. **Replaces both prior placement rework specs.**

### Kingdom rework plan (pasted, not on disk)

Status quoted: "**design phase**. Implementation starts after the NPC rework and decoration rework are complete. Placement rework lands first."

Phase status grid (from the document):

| Phase | Stated status |
|---|---|
| Phase 0 — Bridge | "0.1 Culture extension; 0.2 Event bus decision; 0.3 Stability scalars; 0.4 Territory vs membership split; 0.5 Legitimacy scalar; 0.6 Estate primitives; 0.7 Heraldry; 0.8 Kingdom-tier office stub" — all design-phase, none implemented per text |
| Phase 1 — Worldgen rewrite | 1.1–1.4 (VillageSeedSampler, three-zone gen, capital claim emission, claim resolution) |
| Phase 2 — Houses, ranks, nobility | 2.1–2.7 |
| Phase 3 — Provinces & offices | 3.1–3.4 |
| Phase 4 — Laws, intrigue, diplomacy v2 | 4.1–4.5 |
| Phase 5 — Player experience | 5.1–5.7 |
| Phase 6 — Decline, conflict, religion-as-authority | 6.1–6.6 |
| Phase 7 — Polish, scale, longevity | 7.1–7.4 |

Stated dependencies (verbatim): "Reworks complete before kingdom work begins, in this order: 1. Placement rework — slot system, layout primitives, robustness passes (90% target), schema reservations for kingdom fields. 2. NPC rework — components, life event bus, traits, memory, mood, skills, office framework, village laws, crime, religion, medicine, schedules, marriages, lineage, cultures, visitor flux, request board, history records. 3. Decoration rework — festival grounds, cemeteries, subbuildings, walls, banners on kits, town square rework."

Section 5 (placement schema reservations) lists slot primitives + `VillageTypeData` schema fields the placement rework MUST land. Per `LAYOUT_OVERVIEW.md` + `PLACEMENT-REWORK-STATE.md`, those fields landed in Phase 21.

Open questions (verbatim): event bus reuse vs peer (gates 0.2); culture extension granularity (gates 0.1); banner format (gates 0.7); heir trait inheritance (gates 5.4); multi-kingdom player standing storage (gates 5.2); province polygon update timing (gates 3.1); war shell scope (gates 6.4).

Revision notes: empty.

---

## 2. Code State vs. Plan

### NPC rework

#### Phase 0 — Skeleton (declared COMPLETE by progress)

- **01 TraitVector** — code exists at `src/main/java/tterrag1112/life_in_the_village/Npc/Traits/`; legacy `PersonalityTrait` migration mentioned. ✓
- **02 NpcMemoryLog** — `Npc/Memory/`; `npc_daily_decay` tick subsystem mentioned. ✓
- **03 NpcKnowledgeLedger** — `Npc/Knowledge/`. ✓
- **04 MoodState** — `Npc/Mood/`. ✓
- **05 SkillComponent** — `Npc/Skills/`. ✓
- **06 Office framework storage** — `Npc/Office/` (with `Powers/` and `Selection/` subdirs that are Phase 3 wiring); legacy migration of `Village.villageLeaderId` / `GuildData.guildmasterId` / `Kingdom.rulerEntityId` / `Kingdom.rulerPlayerId` / `Company.ownerPlayerId` to `OfficeState`.
- **Discrepancy / wiring caveat**: `Npc.Religion` package exists (Phase 3), but spec doc 06 placed `temple_high_priest` "registered but unattached"; revision notes confirm this.

#### Phase 1 — Personhood (COMPLETE)

- **07 LifeGoals** — `Npc/LifeGoal/`. 26 goal types per progress.
- **08 DialogueTree** — `Npc/Dialogue/`. 17 predicate variants, 9 effect types, 25 starter trees.
- **09 PlayerVerbs** — `Npc/Verbs/Impl/`.
- **10 LifeEventBus** — `Npc/Events/Producers/`.

#### Phase 2 — Social fabric (COMPLETE)

- 11 ledger, 12 gossip, 13 schedule, 14 hobby, 15 child/elderly, 16 apprenticeship, 17 scribal, 18 letters/books — packages `Npc/Relations`, `Npc/Gossip`, `Npc/Schedule`, `Npc/Hobby`, `Npc/Aging`, `Npc/Apprentice`, `Npc/Scribal`, `Npc/Letters` confirmed via filesystem.
- **Partial / deferred (per progress)**: gossip world-event seeders deferred; player Laws UI / Office tab GUI deferred; Quest → Request migration deferred; Refugee leader-decision UI deferred.

#### Phase 3 — Offices & adaptive economy (COMPLETE)

- 06-wire offices: `Npc/Office/Selection/` 7 engines + `Npc/Office/Powers/PowerGrant`. ✓
- 19 crime: `Npc/Crime/`. ✓
- 20 religion: `Npc/Religion/`. ✓
- 21 medicine: `Npc/Health/`. ✓
- 22 laws: `Npc/Laws/effects/` ships 22 `LawEffect` impls + `LawEffectRegistry`. `VillagePolicy` codec on `Village`. ✓
- 23 channels: `Npc/Economy/Channels/` ships 4 channels + 2 stubs. ✓
- 24 business front: `Npc/BusinessFront/`. ✓
- **Partial**: `OfficeChange` `LifeEvent` exists but `OfficeElection` finalisation does not yet emit it (called out in 33 progress notes).

#### Phase 4 — Specialization & inter-village (COMPLETE)

- 25 resource categories — `Village.Simulation.ResourceCategory` with 12 enum entries. ✓
- 26 NPC companies — `Company` extended with OwnerType / CompanyType / SuccessionState fields per progress.
- 27 guild refactor — `Guilds.Common/` ships `AbstractGuild` as **additive layer**; legacy `GuildData` retained alongside (Open decisions #1).
- 28 request board — `Guilds.Common.Requests/` ships single-global `RequestBoard` (spec called for per-village/per-kingdom/global; collapsed to one with scope filter at query time).
- 29 visitor flux — `Npc/Visitor/`; ships as `VisitorState` component on `TownspersonMob` rather than separate `Visitor extends TownspersonMob` class (spec deviation).
- 30 village history — `Village.History.VillageHistoryLog` SavedData + `NotablePerson` registry; ~40 `HistoryEventType` entries; PLAGUE_OUTBREAK / TRIAL_HELD / LAW_ENACTED / LAW_REPEALED / COMPANY_FOUNDED / VILLAGE_FOUNDED producers wired.
- **Partial**: visitor `VisitorChannel.execute` returns success without item transfer; envoy letter delivery deferred; trading-company caravan dispatch is a treasury stub; player UIs deferred for guild / company / request board.

#### Phase 5 — Polish & content (PARTIAL)

- 31 cultures — `Cultures/` package: `Culture.java`, `CultureBundles.java`, `CultureLanguage.java`, `CultureNaming.java`, `CultureRegistry.java`, `CultureResolver.java`, `CultureTraitBias.java`. 4 starter cultures registered. ✓
- 32 events — `EventType` extended from 5 → 33 values; 28 handlers in `EventHandlerRegistry`; 8 categories. ✓
- 33 appearance — `Entities.custom.Appearance` package: `AppearanceLayerRegistry` with 5 culture bases, ~17 office marks, ~10 accessories. **Texture / model assets explicitly deferred**; renderer integration deferred (`TownspersonRenderer` unchanged).
- 34 content pass — every sub-task Not-Started.
- **Spec deviations called out**: schedule layering (`ScheduleResolver` not refactored), `ChannelRouter.score` doesn't read `hagglingTendency`, apprenticeship norms have no consumer, NpcProfileSnapshot culture tag deferred, no `OfficeChange` emission yet.

### Decoration rework

#### Phase 0a — Variants, color, zoning absorption (PARTIAL but mostly Implemented)

- P0a-01..14, P0a-17, P0a-19, P0a-20 all marked Implemented in `DECORATION_PROGRESS.md`.
- **Partial — P0a-15** (default RURAL pack): manifests `cottage`, `house`, `large_house` authored; **NBT files missing** from `src/main/resources/`. Placement will hard-fail until NBTs land.
- **Not-Started — P0a-16** (URBAN pack).
- **Blocked — P0a-18**: `ZoneRegistry` deletion blocked. `Village/Planning/ZoneRegistry.java` confirmed present in code; the progress note says ZoneRegistry has active production use in `BuildSiteFinder`, `PlanContext.claimByZone`, terrain / civic-ring policies, and `LayoutPrimitive` civic-ring sizing. Slot/matcher absorption (P0a-17) didn't subsume those four call sites.

#### Phase 0b — Decoration framework (IMPLEMENTED)

- `Village/Decoration/Framework/` ships `DecorationTag` enum (13 values per spec doc 01), `DecorationSlot` record + codec, `DecorationProfileRegistry`, `DecorationMatcher` (second-pass greedy), `DecorationSlotEmitter` (six sub-algorithms: road-side, building-gap, building-corner, facade ornament, village-boundary, trade-road endpoints), `DecorationPass` orchestrator.
- `VillageSavedData.VillageDecorationData` confirmed in code (line 332 of `VillageSavedData.java`).
- `/liv decoration` debug command registered.
- **Caveat**: registry is empty until later subsystems populate it (Phase 1+ tasks).

#### Phase 0c — AdjunctPlot framework (IMPLEMENTED)

- `Village/Decoration/Adjunct/` package ships `AdjunctPlot` record + `AdjunctPlotPlacer` + `AdjunctPlotRegistry` + `AdjunctPlotRealiser` + `AdjunctPlotType` (14 values) + `FaceProbeOrder` + `PlacementStrategy`.
- `VillageSavedData.VillageAdjunctData` confirmed in code.
- HOUSE → HOMESTEAD_* registrations explicitly deferred to subsystem 11 (Phase 3 Homesteading).
- FARMER intentionally absent (FarmPlot system handles it).

#### Phase 0d — Subbuildings (PARTIAL)

- `Village/Decoration/Subbuilding/` ships `SubBuilding` record, `SubBuildingScanner`, `SubBuildingType` enum (8 values), `SubBuildingPathing` helpers.
- `VillageSavedData.VillageSubBuildingData` confirmed.
- **P0d-04 Deferred**: `MarketStallPlacer` migration. `MarketStallPlacer.java` still uses chiseled-block runtime claim (lazy) instead of BE anchor (scanned). Both code paths coexist.

#### Phases 1–4 (NOT-STARTED except polygon plaza work)

- **P1-01..05 Superseded** by polygon plaza work (prompts 16/17/18). `Village/Decoration/Plaza/` ships `PlazaGenerator`, `PlazaPaver`, `PlazaPurpose`, `PlazaRegion`, `PlazaShape`, `PlazaSpec`, `VillageCenterMarker`. `Utilities/Geometry/Polygon.java` exists.
- `LayoutPrimitive.TownSquare` deleted; `TownSquareComposer` + `TownSquareKit` + `TownSquareKitRegistry` + `DefaultTownSquareKits` deleted (`VillageDecorator.java:59` and `Life_in_the_village.java:86–87` confirm).
- `Village/Decoration/TownSquare/` directory still present with `GatheringPoint.java`, `GatheringPointKind.java`, `TownSquareTier.java` only (kit registry gone).
- `Village/Decoration/TownSquarePlacer.java` still exists (legacy code).
- **No code evidence** for: street furniture (`05`), signs/markers (`06`), industry adjuncts (`07`), gardens (`08`), parks (`09`), farm rework (`10`), homesteading (`11`), walls (`12`), festival grounds (`13`), cemeteries (`14`).
- Greps confirm: no `FestivalGround`, `Cemetery`, `Headstone`, `GardenPlot`, `WallPlan`, `WallRealizer`, `FieldPatch`, `Hedgerow`, `FarmTerritory` classes.

#### Discrepancies in decoration rework

- **VariantManifest schema correction**: doc 15 ships, but the `footprint` field was removed from the manifest in a follow-up; loader silently ignores legacy `footprint` blocks. `StructureSizeCache` is the single source of truth for variant geometry.
- **VariantSelector call site moved** post-pilot: from `PlacementMatcher.commitBest` into `PlanContext.tryCommitBuilding`. Without it, every zoned building type silently inherited the type-default variant.
- **HOUSE pilot NBTs missing**: `Current State` push removed migrated `house/level_1.nbt` and never added the new ones.
- **GuildHall colour overrides non-functional**: `GuildData` carries no colour fields; logged once per type, falls through.

### Trade Route rework (Roads)

#### Phases 1, 1.5, 2, 3a, 3b, 4, 5, 6a, 6b, 6c, 6d, 7a, 7b, 7c, 7d, 7e, 7f Slice 1+2+3, 7g, 8a, 8b, 8c, 8d, 9, 10 — all logged COMPLETE in `ROADS_PROGRESS.md`.

- Code confirms: `Village/Roads/Graph/{RoadNode,RoadEdge,EdgeGridIndex,WorldRoadGraph,GraphInvariantValidator,TradeRoadMigration}.java`, `Networking/WorldRoadSavedData.java`, `Village/Roads/Lifecycle/` (Phase 9 dead edges), `Village/Roads/Events/` (Phase 10 infrastructure), `Village/Roads/Lighting/`, `Village/Decoration/Roads/` (palettes), `Village/Roads/Terrain/GreatRoadProfile.java` (Phase 7e), `Village/Roads/Graph/Worldgen/{GreatRoadAnchorSeeder,GreatRoadTrunkRouter,GreatRoadCharacterAnalyzer}.java` (Phase 7a/c).

#### Phase 7c (status was unknown per the brief)

- **Phase 7c is COMPLETE.** `ROADS_PROGRESS.md` line 850 logs "2026-04-23 — Phase 7c implemented: great road character parameters." Plan-document line 282–286 (`### Phase 7c — Great road character`) — no ✓ in heading but the progress log declares "Phase 7 complete. Phases 7a + 7b + 7c all done." `Village/Roads/Graph/GreatRoadCharacter.java` and `GreatRoadCharacterAnalyzer.java` confirmed present.

#### Phase 7f Slice 4 (planned)

- Plan declares "PLANNED." No code evidence.

#### Phases 11, 12, 13 (Not-Started)

- Plan describes player-initiated road construction (11), POI subroads (12), sea route unification (13). No "Phase 11" / Phase 12 entries in progress log. `SeaRoute.java`, `SeaRouteRouter.java`, `BoatCaravan.java` exist as the current pre-unification code.

#### Discrepancies

- `TradeRoad.java` still present in `Village/Economy/Trade/`. Per the plan's invariant 1 ("Graph is canonical. TradeRoute is a lightweight reference") and rejected-alternative ("Central TradeRoad entity persisting after graph migration"), it should have been deleted by Phase 1 migration. `TradeRoadMigration.java` writes the migration but `TradeRoad` source file still exists.
- Caravan diagnostic carryover (Phase 3b log): `TravellingGroupEngine.tick()` calls `caravan.getPath()` which returns empty for synthetic caravans; `overridePath` field only checked in entity AI that is never run because synthetic caravans don't spawn entities. "Fix deferred to Phase 5 or a standalone diagnostic-fix session."

### Kingdom rework

**Pasted plan declares: "design phase. Implementation starts after the NPC rework and decoration rework are complete."** All eight phases (0–7) are design-only.

#### Phase 0 — Bridge (no code)

- **0.1 Culture extension** — `Cultures/Culture.java` exists (NPC Phase 5) but lacks kingdom-tier fields (nobility rank list, succession rule, subdivision model, upkeep mix, kingdom-office requirements). No `succession`, `subdivisionModel`, `upkeepMix`, `nobilityRanks` fields.
- **0.2 Event bus** — `Npc.Events.NpcLifeEventBus` exists; no peer `KingdomEventBus`; no decision recorded in code.
- **0.3 Stability scalars** — no `stability` field on `Village` / `Kingdom`. Greps for `stability`, `Stability` in `Kingdom/` return nothing.
- **0.4 Territory vs membership split** — `Kingdom.java` still uses villageIds list for territory + membership; no explicit `kingdomId` field per village; `KingdomClaim` exists (`Kingdom/KingdomClaim.java`) but is the territorial polygon already.
- **0.5 Legitimacy scalar** — no `legitimacy` field on Kingdom / ruler. Grep returns 0.
- **0.6 Estate primitives** — no `Estate` record; no `NOBLE_MANOR` building type linkage; no estate treasury. Grep returns 0.
- **0.7 Heraldry** — no banner generator; no banner override on Kingdom / House records.
- **0.8 Kingdom-tier office stub** — `Npc.Office.OfficeRegistry` includes `kingdom_king` (extension of NPC office framework) but does not register Chancellor / Scholar / General / Magistrate / Spymaster / Treasurer / Diplomat. The progress log only mentions `kingdom_king` and `village_leader` / `guild_master` / `company_owner`.

#### Phases 1–7 — no code

- No `VillageSeedSampler`, no `KingdomGenerationManager` (three-zone gen).
- No `House` record, no `nobilityRankIndex` field on `TownspersonMob`.
- No `KingdomLaw` typology refactor (existing `Kingdom/KingdomLaw.java` is a flat enum, not the sealed `ToggleLaw / ScalarLaw / EnumLaw` design).
- No rebellion mechanics, kingdom collapse, kingdom merger, war system v2, religion-as-political-authority, kingdom age cycles.

#### Schema reservations (Section 5)

- Per `LAYOUT_OVERVIEW.md` and `PLACEMENT-REWORK-STATE.md`, Phase 21 of placement rework "added but not wired" the kingdom schema fields on `VillageTypeData`: `settlementTier`, `biomeAffinity`, `kingdomRoles`, `tradePriority`, `canBeCapital`, `maxPerKingdom`. The fields the kingdom plan specifies are slightly different (`capital_emits_claim`, `claim_budget_hint`, `vassal_types`, `hostile_types`, `min_nobility_tier`, `province_seat_eligible`, `claim_resistance`). **Discrepancy**: only some of the kingdom plan's required schema names match what placement actually shipped.
- Slot primitives the kingdom plan needs (`CASTLE_SLOT`, `PALACE_SLOT`, `NOBLE_RESIDENCE`, `AUDIENCE`/`COURT`, `CEMETERY` slot, `FESTIVAL_GROUND` slot, `TREASURY` position) — `Village/Planning/Zoning/SlotTag.java` exists; not verified per-slot since plan says the placement rework should land them, and they would normally live as `SlotTag` enum values. Grep would reveal coverage but is not in the brief's grep targets for these names.

### V2 / Placement rework

#### What landed

- **V1 placement rework Phases 1–22**: per `PLACEMENT-REWORK-STATE.md`. Confirmed by directory presence:
  - `Village/Planning/Graph/` — `RoadGraph` data model.
  - `Village/Planning/Features/` — `FeatureMap`.
  - `Village/Planning/Sectors/` — Sector record + GrowthPolicy.
  - `Village/Planning/Primitives/` — `BaseRecipe`, `RoadPrimitive`, `RoadResult`, `TerminationReason`, plus 17 recipe classes in `Recipes/` (all 17 still present: `RadialRecipe`, `CrossroadsRecipe`, `LinearRecipe`, `PlazaRecipe`, `DualPlazaRecipe`, `OutpostRecipe`, `HilltopRecipe`, `SprawlRecipe`, `DocksideRecipe`, `TerracedRecipe`, `EnclaveRecipe`, `GroveRecipe`, `ChainRecipe`, `RoadsideRecipe`, `RiverineRecipe`, `ClusteredRecipe`, `DumbellRecipe`).
  - `Village/Planning/Zoning/` — `PlacementMatcher`, `BuildingProfile`, `BuildingProfileRegistry`, `SlotTag`, `SlotPreference`, `AnchorPolicy`, `AvoidanceRule`.
  - `Village/Planning/LayoutPlan.java`, `LayoutPlanBuilder.java`, `AnchorKind.java` — Phase 19.
  - `Village/Planning/Plaza.java` — Phase 18 wrapper.
  - `Village/Planning/Adaptive/` — Phase A/B/C of the Adaptive Layout Spec: `LayoutBlueprint`, `SlotIntention`, `Anchor`, `RoadDeclaration`, `PlazaDeclaration`, `SectorDeclaration`, `EdgeRef`, `SectorRef`, `SlotEmitter`, `RealisedEdge`, `RecipeNotPortedException`.

- **23.1 (measurement harness)** landed; **23.2** (the actual run + polish) not landed.

#### V2 (separate parallel package)

- `Village/Planning/V2/` exists with all five layers populated:
  - `V2/Layer1/` — `V2FeatureMap.java`, `Cell.java`, `Region.java`, `BlockCategory.java`, `BoundingBox.java`, `ForestRegion.java`, `HighGround.java`, `StoneRegion.java`.
  - `V2/Layer2/` — `SiteAnalyzer.java`, `SiteContext.java`, `ViabilityTier.java`, `Hub.java`, `SpinePathPlanner.java`, `SpinePath.java`, `CardinalAxis.java`.
  - `V2/Layer3/` — `BuildingSelector.java`, `PlacementProfile.java`, `PlacementResult.java`, `PlacedBuilding.java`, `DropReason.java`, `DroppedBuilding.java`, `Footprint.java`, `Priority.java`, `Category.java`, `Provides.java`, `Requires.java`, `SizeClass.java`, `TerrainAggregate.java`, `TerrainFactor.java`, `AdjacencyFactor.java`, `BuildingAvailability.java`, `DependencyResolver.java`, `FrontageStrip.java`, `InclinationProfile.java`, `PlacementDefaults.java`, `ReconciliationEngine.java`, `StructureAvailabilityRegistry.java`, `TradeAvailability.java`, `UnavailableBuilding.java`, `VariantPicker.java`.
  - `V2/Layer4/` — `Skeleton.java`, `RoadNetwork.java`, `RoadCorridors.java`, `RoadSegment.java`, `RoadTier.java`, `Junction.java`, `CrossStreet.java`, `SpineSegment.java`, `PhasedPlanner.java`.
  - `V2/Layer5/` — `MinimalSpawner.java`, `OverlapAuditor.java`, `PadBuilder.java`, `RoadPainter.java`, `TerrainAdapter.java`, `VegetationClearer.java`, `ViabilityValidator.java`.
  - `V2/Culture/` — `Culture.java` (V2's own), `CultureRegistry.java`, `Curvature.java`, `PlazaShape.java`. **Note**: this is a different `Culture` from the NPC `Cultures.Culture`.
  - `V2/Inclination.java` — six values per design.

- V2 has integration with several commands: `PlaceCommand`, `FeatureMapCommand`, `SpawnCommand`, `LayoutCommand`, `SiteCommand` reference V2 classes.

- **Discrepancy / gap**: `VillageSpawner.java` does NOT yet reference V2 / MinimalSpawner / `adaptive_v2` config flag. Grep for `MinimalSpawner|V2|adaptive` in `VillageSpawner.java` returns 0. The migration pattern described in `ADAPTIVE-VILLAGE-DESIGN.md` ("config flag selecting which path runs at spawn time") is not present in the spawner.

- V2's `Culture` is a separate record from `Cultures.Culture` (NPC Phase 5). Two `Culture` types in two packages.

- V2's design says "ShapeType, all 17 Recipe classes, slot/intention/anchor/emitter machinery, cascade engine ... die." None of those have died yet. They coexist with V2.

---

## 3. V2's Impact on the Planned Reworks

V2 (`ADAPTIVE-VILLAGE-DESIGN.md`) was authored after every other plan and explicitly retires the earlier rework's vocabulary.

### V2 vs Decoration rework

- **Replaces / supersedes**: V2's Layer 5 emits a `LayoutPlan` "in the existing format so downstream systems are unchanged" — decoration pipeline is explicitly preserved. Phase 0a's variant + matcher work, Phase 0b's decoration framework, Phase 0c's adjunct plot, Phase 0d's subbuildings — all of these consume `LayoutPlan` and persisted `Building` records, which V2 does produce.
- **Assumptions broken**: 
  - Decoration plan assumes `BuildingProfile` is variant-keyed (P0a-03 ties variants to BuildingProfile). V2 does its own building selection in Layer 3 via `PlacementProfile` / `InclinationProfile`. Two parallel systems for "what building goes here" now exist; V2's `VariantPicker.java` and the variant-aware matcher (P0a-06 in `VariantSelector`) are not the same code path.
  - Decoration phases 1–4 reference recipes / shapes (e.g. festival grounds doc says "PLAZA, CROSSROADS, CIVIC-rich shapes reserve a dedicated 15×15 cleared area"). V2 deletes ShapeType. Phase 4 docs (12-village-walls, 13-festival-grounds, 14-cemeteries) reference recipe-time slot reservation that V2's Layer 3 does not currently emit.
- **Still depends on**: persisted `Building` record + `LayoutPlan` shape; `VillageSavedData.VillageDecorationData` / `VillageAdjunctData` / `VillageSubBuildingData` (V2 does not define its own savedData).
- **API surface ready vs absorption**:
  - **Ready**: AdjunctPlot framework (consumes parent Building), Subbuilding scanner (consumes Building bounds), Variant + colour system (consumes `BuildingProfile`). All Phase 0 work plugs in unchanged.
  - **Needs absorption**: town-square / plaza work was tied to recipe `compose()` slot emission (`installPlaza` is called from 17 recipe classes). V2 owns plaza generation in Layer 4 / Layer 2 (`Hub.java`); the existing `PlazaGenerator` would either be reused under V2's plaza-shape preference or replaced by V2's own anchor selection.
  - **Needs absorption**: festival grounds / cemeteries / walls (Phase 4 of decoration) were specced against shape recipes; V2 has no shapes. New design needed.
  - **Needs absorption**: farm plot rework (10) currently reserves slots in recipes; V2 wants buildings to choose positions, then roads — farm plots fit Layer 3 rather than recipe slots.

### V2 vs NPC rework

- **Replaces / supersedes**: nothing direct. NPC code is downstream of placement.
- **Assumptions broken**:
  - `BuildingType` set is the universe of buildings — V2 says "BuildingType enum survives." Compatible.
  - Schedule / office / law systems all key off Village + buildings within it. V2 produces villages with the same components. Compatible.
- **Still depends on**: Village + Kingdom + Building primitives; `VillageSavedData`; `WorldRoadGraph` (V2 emits roads compatible with the existing graph).
- **API surface ready vs absorption**:
  - **Ready**: every NPC system. NPCs are populated after placement; V2's Layer 5 emits `LayoutPlan` in the existing format, so `VillageInhabitantPopulator`, `GuildBootstrap`, etc. run unchanged.
  - V2 has its own `Culture` record. NPC rework's `Cultures.Culture` is the production source. **Two `Culture` types coexist** — V2 currently uses its own minimal one (id, roadMaterial, preferredCurvature, preferredPlazaShape, inclinationBias). NPC's is much richer. Reconciliation needed if V2 wants to inherit office/visitor/hobby/law biases.

### V2 vs Trade Route rework

- **Replaces / supersedes**: V2 emits its own internal village road graph in Layer 4. Phase 7f Slice 1 / 2 / 3 of the road system already shipped a `VillageRoadGraph`; V2's Layer 4 needs to feed into that or replace it.
- **Assumptions broken**:
  - `VillageDockingPoint` (Roads Phase 2) computes the arm endpoint from `Village.getCapitalGatePositions()` / `getMainGateEndpoint()`. V2 produces gateways in Layer 4 via `Junction.java` / `RoadNetwork.java`. The interface that the dockingpoint reads from `Village` may need to be populated by V2's outputs.
  - Phase 9 `NetworkAlignmentScorer` scores cell distance to live great roads; uses `WorldRoadGraph` and `VillageSavedData`. V2 doesn't change that — placement still happens before V2 runs.
- **Still depends on**: `WorldRoadGraph`, `WorldRoadSavedData`, `VillageDockingPoint`, palette resolution.
- **API surface ready vs absorption**:
  - **Ready**: V2 outputs spine + connectors with culturally chosen `roadMaterial`. The decoration / realisation pipeline (Phase 6a/7g palettes) consumes these in the existing `EdgeRealizer` flow.
  - **Needs absorption**: V2's Layer 4 roads need to populate `VillageRoadGraph` (Phase 7f Slice 1) and `VillageRoadEdge` (Phase 7f Slice 3) for caravan traversal. Currently V2's `RoadNetwork.java` is its own type.

### V2 vs Kingdom rework

- **Replaces / supersedes**: nothing directly — the kingdom rework is pure design phase.
- **Assumptions broken**:
  - Kingdom plan Section 5 declares: "Slot primitives needed: CASTLE_SLOT / PALACE_SLOT, NOBLE_RESIDENCE slot, AUDIENCE / COURT slot, CEMETERY slot, FESTIVAL_GROUND slot, TREASURY position." V2 deletes the slot abstraction entirely ("no slot emission; buildings choose their own positions"). The kingdom plan's hooks into placement need to be re-expressed in V2's vocabulary (terrain weights / adjacency weights / requires_present in the building manifest).
  - Kingdom plan presupposes the placement rework's old `VillageTypeData` schema fields (`capital_emits_claim`, etc.). V2 says "all VillageType data files in their current form" die. The kingdom-tier fields would need to migrate into V2's `Inclination` + `culture` + manifest model.
  - Kingdom plan 1.1 (`VillageSeedSampler`), 1.2 (three-zone gen), 1.3 (capital claim emission), 1.4 (claim resolution) all sit at the atlas/kingdom layer above V2's per-site planning. They feed sites *to* V2; V2 doesn't replace them.
- **Still depends on**: V2 producing villages reliably (kingdom-rework prerequisite ordering says placement first). Kingdom plan's "90% target" is the same exit criterion as V2's success bar.
- **API surface ready vs absorption**:
  - **Ready**: V2's `ViabilityTier` (CITY / TOWN / HAMLET / OUTPOST / UNVIABLE) maps cleanly to the kingdom plan's `min_nobility_tier`, `province_seat_eligible`, `canBeCapital` ideas. V2's `Inclination` (AGRICULTURAL / INDUSTRIAL / DEFENSIVE / CIVIC / RESIDENTIAL / SACRED) provides natural pinning for "capital must be CIVIC" rules.
  - **Needs absorption**: kingdom-claim Dijkstra (`KingdomClaimComputer.java` exists) operates on atlas cells, not V2 layers. Compatible — V2 takes a chosen site and plans inside it; kingdom code chose the site.
  - **Needs absorption**: castle-on-village logic (`Kingdom/Castle/CastleGenerator.java` etc.) presupposes a CASTLE building or capital-village override. V2 doesn't yet ship a castle path; would need a new `Inclination` (e.g. `MONARCHIC`) + manifest entries.

---

## 4. Cross-Cutting Concerns

### Concrete conflicts (two plans describing the same thing differently)

- **Two `Culture` records**: NPC Phase 5 ships `Cultures.Culture` (13 fields, sub-bundles for Schedule / OfficeRules / LawDefaults / EconomicNorms / HobbyWeights / VisitorAffinity / ApprenticeshipNorms / AestheticTokens / Religion). V2 ships `Village.Planning.V2.Culture.Culture` with a different shape (id, roadMaterial, preferredCurvature, preferredPlazaShape, inclinationBias). Both are loaded from JSON paths.
- **Two placement-system specs**: `ADAPTIVE-LAYOUT-SPEC.md` (declarative blueprints + slot intentions + emitter) and `ADAPTIVE-VILLAGE-DESIGN.md` (V2 five-layer) both claim authority. The state doc explicitly says V2 "supersedes the original ADAPTIVE-LAYOUT-SPEC.md," but Adaptive code (`Village/Planning/Adaptive/`) is shipped and Phase A/B/C/C.1 entries exist in `ROADS_PROGRESS.md` post-supersession.
- **Two town-square rework rollouts**: Decoration plan doc 04 specifies polygon plaza model (landed). Decoration progress P1-01..05 marked Superseded by it. Adaptive Layout Spec section 9 also calls `PlazaGenerator.emitPlazaCivicSlots` for deletion (it has been deleted per progress notes). The two specs agree on direction; surface-level conflict on which doc owns the polygon work.
- **Office attachment to Temple**: NPC doc 06 says `temple_high_priest` "registered but unattached." NPC doc 20 (religion) creates the priest profession. The two never crosswire — `temple_high_priest` is still unattached per Phase 0 progress notes.
- **Schema mismatch on kingdom rework prereqs**: Placement Phase 21 added `settlementTier`, `biomeAffinity`, `kingdomRoles`, `tradePriority`, `canBeCapital`, `maxPerKingdom` to `VillageTypeData`. Kingdom plan asks for `capital_emits_claim`, `claim_budget_hint`, `vassal_types`, `hostile_types`, `min_nobility_tier`, `province_seat_eligible`, `claim_resistance`. Names don't all overlap.

### Integration surfaces (rework A produces, rework B consumes)

- **Office framework** — NPC Phase 0 storage produces; NPC Phase 3 wires; Kingdom Phase 0.8 + 3.2 expects to extend with kingdom-tier offices.
- **NpcLifeEventBus** — NPC Phase 1 produces; Kingdom Phase 0.2 explicitly defers between extending bus or peer `KingdomEventBus`.
- **Culture record** — NPC Phase 5 owns `Cultures.Culture`; Kingdom Phase 0.1 wants to extend it with nobility ranks / succession rules / subdivision model. V2 has its own.
- **Channel Router** — NPC Phase 3 owns; `GuildRequestChannel` and `VisitorChannel` were stubs that NPC Phase 4 docs 28/29 filled in.
- **Festival Grounds** — Decoration Phase 4 doc 13 reserves the spatial slot; NPC Phase 5 doc 32 fires the events. Decoration depends on NPC.
- **Cemeteries** — Decoration doc 14 needs NPC Phase 2 (death events) + NPC Phase 4 (history records). Both NPC sides shipped (`DeathArc`, `VillageHistoryLog`); decoration side Not-Started.
- **Homesteading** — Decoration doc 11 explicitly requires NPC Phase 3 doc 23 (DirectBusinessChannel — shipped) + Phase 4 doc 25 (resource categories — shipped). Decoration side Not-Started.
- **Kingdom claim emission** — Kingdom plan 1.3 emits Dijkstra claim over atlas cells. Existing `KingdomClaimComputer.java` already does Dijkstra. Plan reuses existing code.
- **Roads dock node** — Roads Phase 7f Slice 1 introduced `Village.dockNodeId`. V2 produces gateways (Layer 4); these need to register as `VILLAGE_DOCK` nodes in `WorldRoadGraph`.
- **Kingdom-tier office stub** — NPC Phase 0 office storage attached to Kingdom; Kingdom Phase 0.8 wants Chancellor / Scholar / General / Magistrate / Spymaster / Treasurer / Diplomat registered. NPC currently registers only `kingdom_king`.
- **Banner overrides** — Decoration Phase 0a P0a-12 says guild-hall colour override is "non-functional today because GuildData carries no colour fields — logged once per type and falls through." Kingdom Phase 0.7 wants banners on kits to feed decoration's NBT system. Both plans depend on a colour-field extension that has not been written.

### Orphan systems (code that exists but no current plan references)

- **`Village/Decoration/TownSquarePlacer.java`** still present despite plaza polygon migration that deleted `TownSquareComposer`/`TownSquareKit`. Status unclear from plans.
- **`Village/Economy/Trade/TradeRoad.java`** — should have been retired by Roads Phase 1 migration (`TradeRoadMigration.java` exists), but the source file is still there.
- **`Village/Decoration/TownSquare/{GatheringPoint,GatheringPointKind,TownSquareTier}.java`** — partially-migrated remnants; PlazaGenerator now registers gathering points but the legacy `TownSquareTier` may still be referenced.
- **`Village/Planning/LayoutPrimitive`** — referenced by Adaptive spec for deletion; per progress, `LayoutPrimitive.TownSquare` is deleted; `BuildingCircle`, `LinearRow`, `RingBand` are still expected to be deleted in Phase D of the Adaptive Layout Spec, which has not run. V2 supersedes the Adaptive spec, leaving `LayoutPrimitive` in an undefined-status limbo.
- **`Kingdom/Castle/`** entire package — extensive (40+ classes: ArchitectPalette, CastleGenerator, CastleLayout, CastleManifest, CastleRoster, CastleStyle, CastleStyleLoader, DesignChecklist, DesignExporter, DesignSession, DesignType, DetailApplicator, FunctionConfig, GroundPlanConverter, MaterialPalette, PaletteRegistry, RoomStack, RoomStackBuilder, RuinationPass, StackPlacementRecord, StyleDetailConfig, SubbuildingSpec, SubbuildingType, TerrainSampler, WallDesign, WallDesignRegistry, WallFacePlacer, plus `Placers/` subdir). No plan covers it. The Kingdom plan does not list castle generation in the design phases (it is "out of scope for v1"). Code ships without a current plan describing its lifecycle / tests / kingdom integration.
- **`Kingdom/KingsPeaceHandler.java`, `Kingdom/DiplomaticRelation.java`, `Kingdom/KingdomTitleData.java`, `Kingdom/KingdomTitleRegistry.java`** — pre-rework kingdom system. None of these names appear in the kingdom rework plan's named structures (which uses `KingdomLaw` sealed interface, House records, etc.).
- **`Village/Decoration/VillageWeathering.java`**, **`Village/Decoration/VillageAgingManager.java`** — not described in the decoration rework's 17 docs (which exclude polish passes).
- **`Village/Planning/Rules/{BuiltinRules,RuleContext,ShapeRule,ShapeRuleRegistration}.java`** — not referenced in the Placement plan's 23-phase abstraction list. Possibly created during one of the Phase 16+ recipe restructurings.

### Open decisions (deferred / TBD that are now relevant)

- **Kingdom Phase 0.2** — extend `NpcLifeEventBus` vs build peer `KingdomEventBus`. NPC bus is now firmly established; if Kingdom rework uses it, that resolves the question.
- **Kingdom Phase 0.1** — culture extension granularity. NPC Phase 5 already shipped a 13-field Culture record with sub-bundles. Kingdom plan needs to decide whether to extend that record vs add a parallel `KingdomCulture`.
- **Placement rework Section 5** — Path 1 vs Path 2. The state doc says "deferred until after Phase 23." V2 (`ADAPTIVE-VILLAGE-DESIGN.md`) is effectively a Path 2 commitment by retiring shapes; the deferred decision has been made unilaterally by V2 without measuring Phase 23.
- **Adaptive Layout Spec Phase E** — re-measure post-port. Never run (V2 superseded).
- **`OfficeChange` life-event production** — declared in NPC Phase 5 task 32; appearance Layer 1 producer subscribes; election system does not yet emit it. Mentioned in doc 33 progress notes.
- **NPC Phase 4 Quest → Request migration** — explicitly deferred ("adventurer Quest path keeps its existing UI"). Two parallel systems.
- **NPC Phase 4 GuildData → AdventurerGuild rename** — deferred per spec Open Decisions #1.
- **Decoration P0a-18 ZoneRegistry deletion** — Blocked. Active production use in `BuildSiteFinder`, `PlanContext.claimByZone`, terrain / civic-ring policies, `LayoutPrimitive` civic-ring sizing. V2 deletes much of this in its final form, which would unblock — but V2 has not absorbed the four call sites yet.
- **Roads Phase 7f Slice 4** — connector routing through villages. PLANNED, no code.
- **Roads Phase 11 / 12 / 13** — player-initiated road construction, POI subroads, sea-route unification. Not started.
- **V2 wiring** — `VillageSpawner` does not yet route to V2's `MinimalSpawner`; the `adaptive_v2: true/false` flag described in `ADAPTIVE-VILLAGE-DESIGN.md` does not appear in `VillageSpawner.java`.
- **Phase 23.2 measurement run** — not yet executed; success-rate cells in `PLACEMENT-REWORK-STATE.md` Section 9.1 still blank.
- **Decoration NBT files for HOUSE pilot (P0a-15)** — manifests authored, NBTs missing; placement will hard-fail on HOUSE in default culture until landed.
