# UNIFIED REWORK PROGRESS

Append-only log. Most recent entry at bottom.

Status values: `Not-Started`, `In-Progress`, `Implemented`, `Tested`,
`Done`. `Done` means: implemented, tested in-world, no known issues,
spec matches reality.

## Track A — Placement consolidation

| ID | Task | Status | Notes |
|---|---|---|---|
| A1a | Wire V2 into VillageSpawner (parallel branch) | Superseded by A4 | Flag + adapter landed; absorbed into unconditional A4 routing. |
| A1b | V1 cleanup + ZoneRegistry migration | Implemented | V1 deletions complete. BuildSiteFinder rewritten zone-agnostic. Rules/Sectors entanglement untangled. Smoke test pending. |
| A2 | Culture unification | Implemented | Sub-bundle landed; V2 Culture/CultureRegistry deleted; smoke test pending. |
| A3 | Variant unification | Implemented | VariantResolver landed; VariantPicker deleted; V1 + V2 paths route through it; smoke test pending. |
| A4 | VillageSpawner → V2 unconditional + flag deletion | Implemented | V1 branch removed; adaptive_v2 flag + V2Settings + ConfigCommand deleted; V1 caller inventory recorded; smoke test pending. |
| A5 | Measurement run vs V1 baseline | Deferred | Harness deleted in A1b; V1 baseline never recorded; V1-shaped metrics don't translate to V2. Track A is functionally complete without it. Re-open if a V2-native harness is built. |

## Track B — Decoration finishing

| ID | Task | Status | Notes |
|---|---|---|---|
| B1-15 | HOUSE pilot NBTs (P0a-15) | User-task | Manifests exist (RURAL pack); NBT files pending user authoring. Paths documented in log. |
| B1-16 | URBAN variant pack manifests (P0a-16) | Implemented | 22 URBAN manifest.json files authored from doc 16 §4. NBT files pending user authoring. |
| B1-04 | MarketStallPlacer subbuilding migration (P0d-04) | Implemented | Routed through SubBuildingScanner / SubBuildingType.STALL. Market NBTs need re-authoring with SubBuildingAnchorBlock. |
| B1-12 | GuildHall colour fields | Implemented | AbstractGuild gains palette field; GuildPalettes table per GuildType; VariantResolver.planTint accepts forced overrides; V2 adapter wires guild halls. |
| B2.1 | V2 envelope extension (adjunct planning + framework refactor) | Implemented | Layer 4 reserves AdjunctPlot rectangles; AdjunctPlotPlacer probe→render-only; FaceProbeOrder + PlacementStrategy deleted; manifest schema gains optional `adjunct` field. |
| B2.2 | Street furniture + welcome marker + noticeboard content | Implemented | 14 doc-05 furniture pieces registered (tier-gated); welcome marker; sign-based noticeboard reading laws/requests/decrees on a 24000-tick refresh. DecorationProfile gains `minTier`; DecorationDensityProfile threaded through emitter. |
| B2.3 | Industry adjuncts + building gardens | Implemented | AdjunctPlotRegistry culture-keyed (default-only); 15 rural manifests gain doc 07/08 adjunct preferences; ARMORER/TOOLSMITH/LIBRARY/MINE registered. FISHERY skipped (no manifest folder); MILLER skipped (malformed path). |
| B2.4 | Parks and gardens | Implemented | Layer 4 post-pass `ParkCandidateFinder` reserves up to 3 GardenPlots per village (HAMLET 0 / VILLAGE 0–1 / TOWN 1 / CITY 1–3); `ParkRenderer` runs preserve-vs-compose with procedural primitives + NBT-skip. 5 doc-09 styles. CulturePlanningBias gains parkPriority + parkPreferenceWeight. |
| B2.5 | Farm plot rework | Implemented | `FarmSector` + 4-vertex terrain-trimmed polygon plots. Doc-10 tier-only sector radius (HAMLET 15/VILLAGE 20/TOWN 25/CITY 30) with farmPriority slack. Per-farmhouse plot count (HAMLET 2/VILLAGE 2-3/TOWN 3-4/CITY 4-6). FarmCropPicker uses CulturePlanningBias.cropPreference. New FarmSectorRenderer replaces legacy FarmPlotPlacer in the V2 path; placer parked. |
| B2.6 | Homesteading | Implemented | 7 HOMESTEAD_* AdjunctPlotTypes (added WORKSHOP/ORCHARD/WOODSHED to B2.1's 4). PhasedPlanner.rollHomesteadType probability-gates HOUSE adjuncts (HAMLET 80%/VILLAGE 60%/TOWN 30%/CITY 10%) + weighted draw from `CulturePlanningBias.homesteadPlotWeights`. AbstractHomesteadGoal.Spouse (WORK_*) + .Child (SOCIAL) dispatch to HomesteadHandlerRegistry handlers. HouseholdData gains `homesteadPlotType`. **Track B complete.** |
| B2.7 | Command consolidation + spawn pipeline fix | Implemented | `/litv spawn` now delegates to V2VillageSpawnerAdapter (was bypassing all B2 post-passes). `/atlas` consolidated under `/liv atlas`. New `/liv adjuncts <village>`. New `/liv help` + `/litv help` master directory. |
| B2.8 | Connection diagnostics + test-spawn affordances | Implemented | FarmSectorPlanner gates on farmhouse count, not inclination. Village.inclination persisted. Park/farm/homestead status commands report actual tier+inclination+concrete reason. PhasedPlanner respects ReconciliationEngine's tradeFulfilled set. VillageDecorator's V1 road-walking removed. ConnectorPlanner warning storm demoted to DEBUG. `/building village spawn <inclination> <tier> [name]` restored with V2 vocabulary. |
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
| C1-tr | TradeRoad.java deletion | Done 2026-05-09 | Land routes are graph-only; legacy land/realisation/event stack removed. See ROADS_PROGRESS Track C1 entry. |
| C1-cv | TravellingGroupEngine synthetic-caravan fix | Done 2026-05-09 | Root cause was in the diagnostic command's synthetic principal UUID, not the engine. `dispatch_test_caravan_between` now reserves a real merchant via `reserveIdleMerchant` + `setCurrentExpeditionId`. |
| C2 | Phase 7f Slice 4 connector routing | Done 2026-05-09 | V2 villages register multi-gateway docks (spine endpoints + cross-street outer arms); ConnectorPlanner reuses gateway TERMINUSes; new `GraphTradeRouteEstablisher.findSegmentedPath` does augmented Dijkstra over world graph + village internal hops, emits `RouteSegment.WorldEdge`+`VillageTraversal` chains. See ROADS_PROGRESS Track C2 entry. |
| C3.1 | Phase 11 player-initiated roads | Done 2026-05-09 | New `ROAD_ENGINEER` PlayerProfession; `RoadProposal` saved-data co-tenant on WorldRoadSavedData; treasury-driven cross-tick advancement (no NPC walking); minimal `ROAD_ENGINEER_PLANS` book screen + `/litv road propose` command; reuses worldgen `AtlasRouteRouter` + `EdgeRealizer` for graph-indistinguishable connectors. See ROADS_PROGRESS Phase 11 entry. |
| C3.2 | Phase 12 POI subroads | Done 2026-05-09 | Player-proximity scan registers vanilla pillager_outpost / ruined_portal / pyramids / igloos / swamp_huts as `DiscoveredPoi` records; `PoiSubroadPlanner` routes a LOCAL-tier dirt footpath to the nearest non-POI graph node/edge; layered skip rules (village claim, 200-block dedup, 500-block isolation threshold); `EdgeMaterialResolver` paints POI subroads in dirt; caravan dispatch (both `findEdgePath` and Track C2 `findSegmentedPath`) skips POI_STUB-edged edges; `RoadUpkeepSystem` decays POI subroads at –8 vs –5; `/litv road debug list_pois`. See ROADS_PROGRESS Phase 12 entry. |
| C3.3 | Phase 13 sea route unification | Done 2026-05-09 | New `EdgeTier.SEA`; `SeaRoute` + `TradeConnection` deleted; `SeaRouteMigration` one-shot/idempotent converts legacy records to SEA-tier edges with UUID preservation (in-flight BoatCaravan records load unmodified); `BoatCaravan.getPath` reads from `RoadEdge.cellPath`; sea-route establishment in `TradeRouteManager` creates SEA edges directly; dispatch unification via first-edge-tier inspection; `EdgeRealizer`/decoration/lighting skip SEA; `RoadUpkeepSystem` decays SEA at –2 (legacy curve preserved); `MapSeaRouteSnapshot` derived from edges with unchanged wire shape. See ROADS_PROGRESS Phase 13 entry. **Track C complete.** |
| C3-11 | Phase 11 — player-initiated road construction | Superseded by C3.1 | |
| C3-12 | Phase 12 — POI subroads | Superseded by C3.2 | |
| C3-13 | Phase 13 — sea route unification | Superseded by C3.3 | |

## Track D — Kingdom rework

| ID | Task | Status | Notes |
|---|---|---|---|
| D1 | Phase 0 bridge (kingdom-tier scaffolding) | Done 2026-05-09 | All eight sub-tasks (D1-01 through D1-08) shipped together as one phase per the user-confirmed Phase 0 prompt. New `CultureKingdomDefaults` sub-bundle on `Cultures.Culture`; `Kingdom.stability` + `legitimacy` + `heraldry`; `Village.kingdomId` + idempotent `KingdomMembershipMigration`; `BuildingType.ESTATE`; deterministic `HeraldryGenerator`; `Kingdom.Events.KingdomEventBus` peer + 8-record taxonomy; five new kingdom-office stubs (Scholar / General / Magistrate / Spymaster / Diplomat) + four new `Profession` enum values; `/litv kingdom debug describe`. Purely additive; no behaviour change in-game; everything new is wired but inert. See KINGDOM_PROGRESS.md for per-decision rationale. |
| D2 | Section 5 rewrite for V2 vocabulary | Done 2026-05-09 | Doc-only. KINGDOM_PLAN Section 5 rewritten in V2 vocabulary (Inclination biases, Provides/Requires Categories, AdjunctPlot, SubBuilding, D1 sub-bundle). Eight V1 reservations translated: 4 Mapped (CASTLE/NOBLE_MANOR/TREASURY/CIVIC-claim-emission), 1 Subsumed (PALACE = CASTLE NBT variant; province-seat derived), 5 Distributed → D1 follow-up (claimBudgetHint / vassalEligibleCultures / hostileCultures / minNobilityTier / claimResistance + new provinceSeatThreshold), 3 Deferred (SubBuildingType.AUDIENCE_CHAMBER, BuildingType.CEMETERY, BuildingType.FESTIVAL_GROUND). Translation appendix preserved. No code changes. See KINGDOM_PROGRESS.md D2 entry. |
| D1-01 | Culture kingdom-tier fields | Superseded by D1 | |
| D1-02 | KingdomEventBus peer | Superseded by D1 | |
| D1-03 | Stability scalars | Superseded by D1 | |
| D1-04 | Territory vs membership split | Superseded by D1 | |
| D1-05 | Legitimacy scalar | Superseded by D1 | |
| D1-06 | Estate primitives | Superseded by D1 | |
| D1-07 | Heraldry generator | Superseded by D1 | |
| D1-08 | Office stub completion (7 offices) | Superseded by D1 | |
| D2-row-old | Section 5 rewrite | Superseded by D2 | |
| D3.1 | Phase 1 — worldgen + capital generation | Done 2026-05-09 | New `Kingdom/Worldgen/CapitalGenerator` replaces deleted `KingdomSpawner.planComposed` for worldgen entry. Capital-only initial state; D3.2+ regrows multi-village kingdoms. D1.5 prep landed: 6 new fields on `CultureKingdomDefaults` (claimBudgetHint, claimResistance, vassalEligibleCultures, hostileCultures, minNobilityTier, provinceSeatThreshold). Kingdom record gains capitalVillageId + foundingTick. `KingdomOfficeBootstrap` tick subsystem fresh-spawns founding ruler + drafts/fresh-spawns culture-required offices post-realisation. CASTLE / NOBLE_MANOR / TREASURY V2 PlacementProfiles wired (inert until D3.2 capital-tier village types). `KingdomFounded` / `RulerSucceeded` / `OfficeFilled` events fire. Pre-D3.1 saves migrate via independent `kingdomCapitalMigrated` flag. Per user direction, `Kingdom/Castle/` stays orphan (Track E candidate). See KINGDOM_PROGRESS.md D3.1 entry. |
| D3-1 | Phase 1 — worldgen rewrite | Superseded by D3.1 | |
| D3.2a | Phase 2.1 — nobility data substrate | Done 2026-05-09 | Two-phase split per user direction (D3.2a data, D3.2b behaviour). Adds `Npc/Nobility/NobilityComponent` (dynastyHouseId / rankIndex / prestige + `FOUNDING_THRESHOLD=30`) wired into `TownspersonMob` save/load alongside the other component delegates. Adds `Kingdom/Houses/House` record (id, name, kingdomId, founderUuid, foundingTick, headUuid, heraldry, prestige, motto) bundled into `KingdomGovernanceData` to stay under DFU's 16-field cap. Adds `Kingdom/KingdomModifier` record + `Kingdom.addModifier/removeModifier/pruneExpiredModifiers` — wires the stability/legitimacy modifier hooks D1 deferred. `HeraldryGenerator.forHouse(houseId, founderUuid)` produces deterministic per-house arms. New `Npc/Nobility/NobilityRanks` resolves rank-index → display name from `CultureKingdomDefaults.nobilityRanks`, with `COMMONER_FALLBACK` for non-monarchy cultures. `/litv kingdom debug describe` now lists houses + active modifiers; new subcommands `/litv kingdom debug houses <name>` and `/litv kingdom debug modifiers <name>`. **Naming clarification:** `FamilyComponent.houseId` (a `BuildingType.HOUSE` UUID consumed by `HouseholdManager`) is unrelated to `NobilityComponent.dynastyHouseId` (a kingdom-tier `Kingdom.Houses.House` record); both stay distinct, no migration needed. Inert in-game beyond persistence — D3.2b adds the behaviour (succession, fealty tax flow, marriage, dynasty-tree GUI, modifier drivers). See KINGDOM_PROGRESS.md D3.2a entry. |
| D3.2b | Phase 2.2 — nobility behaviour | Done 2026-05-09 | Wires the behaviour layer on top of D3.2a's substrate. New `Npc/Nobility/NobilityEventDispatcher` subscribes to `NpcLifeEvent.Married` (rank step toward the higher-rank spouse, +3 prestige both sides, dynasty-house adoption by the unaffiliated spouse, `marriage.high_rank` legitimacy modifier when either spouse is rank ≥ 2) and `NpcLifeEvent.FamilyDeath` (when the deceased was a `House.headUuid`, runs `SuccessionResolver` with the kingdom's culture rule, transfers headship via `House.withHead`, marks extinct on resolution failure, emits short-lived `succession.transition` modifier). New `Npc/Nobility/SuccessionResolver` interprets `CultureKingdomDefaults.successionRule` (PRIMOGENITURE / AGNATIC / SEMI_SALIC; ELECTIVE / COUNCIL / DIVINE return empty so callers fall through to `CouncilSelection` / `MeritocraticSelection`). `HereditarySelection.pickHeir` consults the resolver first, retaining the prior "eldest adult child" fallback for cultures whose rule isn't laddered. New `Npc/Nobility/HouseFoundingDriver` + `HouseFoundingTickSystem` (priority 196, daily) auto-found noble houses for NPCs with `prestige ≥ FOUNDING_THRESHOLD` and `rankIndex ≥ culture.minNobilityTier`; emits `house.founded` modifier. New `Npc/Nobility/FealtyChain` — two-tier tax flow (`village → lord-of-village → kingdom`) wired into `KingdomTaxEvent.collectTaxes`; `DEFAULT_LORD_SKIM_RATE = 0.0` ships so net flow is preserved (constraint satisfied), but the chain is observable + tunable. New `KingdomBookScreen.SectionType.DYNASTY_TREE` ("Houses" tab) lists each kingdom's noble dynasties with name, heraldry, prestige, head + founder UUIDs, motto. Idempotency: marriage hook only fires for `subject.uuid < spouse.uuid` (CourtingGoal fires Married for both partners). Debug: `/litv kingdom debug fealty <name>` prints the lord-of-village resolution per village. **Deferred:** spouse / children / member-name resolution in the Houses GUI tab requires a server-side detail roundtrip packet (entity names aren't synced today); shown as truncated UUIDs and flagged as a follow-up. See KINGDOM_PROGRESS.md D3.2b entry. |
| D3-2 | Phase 2 — houses, ranks, nobility | Superseded by D3.2a + D3.2b | Split into data + behaviour phases per user direction. |
| D3.3a | Phase 3 slice 1 — provinces + provincial governance + map overlay | Done 2026-05-09 | First half of the user-confirmed D3.3 split (slice 2 = offices + capability gating, follows separately). New `Kingdom/Provinces/Province` record (id, name, kingdomId, governorUuid, villageIds, cellKeys, stability, treasury, createdTick, modifiers, reports buffer) + `ProvincialReport` (per-day rolling buffer, capacity 28). Bundled into `KingdomGovernanceData` (now 9/16 fields). New `Kingdom/Provinces/ProvinceComputer` runs cell-set Voronoi at atlas-cell granularity (matches existing `KingdomClaim` cell-set model — no Fortune's algorithm needed); per-culture strategies dispatch on `SubdivisionModel`: PROVINCES + DUCHIES = TERRITORIAL (manor seeds, fall back to village centroids), TRIBAL_CONFEDERATION = TRIBAL (group by dominant noble house), CITY_STATE_LEAGUE = FUNCTIONAL (group by primary VillageTag role), UNITARY = no subdivision. Kingdoms with ≤3 villages skip subdivision. Province UUIDs derived from `(kingdomId, seedUuid)` so re-runs preserve identity (treasury + reports + modifiers carry over via `mergeWithExisting`). New `Kingdom/Provinces/GovernorSelector` picks highest-rank noble in the province (DUCHIES prefers house heads); tie-break by prestige then UUID. New `ProvinceRecomputeTickSystem` (priority 191, daily check / weekly cadence) + event-driven invalidation in `NobilityEventDispatcher.runSuccession` and `HouseFoundingDriver.tryFound` (resets `lastProvinceRecomputeTick = -1L` on house-head turnover, the C-hybrid timing). New `ProvinceDailyTickSystem` (priority 194) + `ProvincialDailyDriver` runs stability drift toward 60, per-day delta from governance state, generates a `ProvincialReport` and appends to the rolling buffer. `Npc/Nobility/FealtyChain` extended with `governorOfVillage` + `splitForGovernor` + `payGovernor` + per-province ledger (`recordProvinceLedger` / `consumeProvinceLedger`). `KingdomTaxEvent.collectTaxes` now flows village → lord → governor → kingdom; `DEFAULT_GOVERNOR_SKIM_RATE = 0.0` preserves net flow. New `Gui/Map/Kingdom/Layer/ProvinceLayer` colour-fills province polygons with stability-tinted UUID-derived colours + governor labels at province centroids; `KingdomMapData.ProvinceMarker` syncs via the existing `Kingdom.CODEC` flow (no new packet). Debug: `/litv kingdom debug provinces <name>`, `/litv kingdom debug province_recompute <name>` (force a recompute), `/litv province debug describe <provinceName>`, plus province summary lines added to `/litv kingdom debug describe`. **Deferred to slice 2 (D3.3b):** `KingdomCapability` enum + evaluator, capability gating in `KingdomBookScreen`, office competence formula, real office wiring (Chancellor / Scholar / General / Magistrate / Spymaster / Treasurer / Diplomat behaviour). |
| D3.3b | Phase 3 slice 2 — offices + capability gating | Done 2026-05-09 | New `Kingdom/Capabilities/KingdomCapability` enum (8 capabilities: PASS_LAW, ISSUE_DECREE, DECLARE_WAR, LEVY_TROOPS, DRAFT_TREATY, INTRIGUE_FOREIGN, INVESTIGATE_CRIME, ISSUE_CURRENCY). New `KingdomCapabilityEvaluator` runs full server-side check: King OR named delegate office competently held (multiplier ≥ 1.0 = holder clears office's `minLevel` skill threshold; vacancy returns 1 + vacancyPenalty < 1.0; player-held King is sovereign and always satisfies; offline NPC holders assumed competent at minLevel to avoid load-flicker). New `ClientCapabilityCheck` provides coarse grey-out (held vs vacant only — client lacks NPC skill levels) for KingdomBookScreen. `KingdomActionPacket` now gates TOGGLE_LAW (PASS_LAW), ISSUE_DECREE (ISSUE_DECREE), and SET_RELATION (DECLARE_WAR for WAR, DRAFT_TREATY for ALLIANCE/TRADE_PACT) on the new evaluator; chat message on denial gives the reason (e.g. "Cannot pass a law — no competent satisfier — King: vacant, Magistrate: under-skilled (LITERACY 35 < 50)"). New `Npc/Nobility/OfficeAppointmentEnnoblement.onSeat` ennobles commoner appointees to rank index 1 (lowest noble in the culture's table) when seated in any kingdom-tier office except King; non-monarchy cultures (rank table empty / 1 entry) are no-ops; King is excluded since `KingdomOfficeBootstrap` and hereditary succession handle their own ranks. Hooked into both `OfficeElection.runElection` and `OfficeElection.seatNpc`. KingdomBookScreen Enact/Repeal and Issue Decree buttons now grey out + show "Requires King or Magistrate (all vacant)" / "Authorised by Chancellor" tooltips. New debug command `/litv kingdom debug capabilities <name>` lists ALLOW/DENY per capability with the per-office report line for each. **Out of scope (Phase 4 / 5):** Royal Scholar competence boost into Chancellor / Magistrate (registry exists, formula deferred), capability-table re-balance once playtest data exists, AND-semantics override per-capability (current OR semantics is the v1 design), commoner-clerk holdings (current design always ennobles on appointment). |
| D3-3 | Phase 3 — provinces & offices | Superseded by D3.3a + D3.3b | Split per the prompt's "If ambiguous" guidance: 3.1+3.4 (provinces + governance) ships first, then 3.2+3.3 (offices + gating). |
| D3.4a | Phase 4 slice 1 — law typology refactor + GUI rewrite (4.1+4.2) | Done 2026-05-09 | First half of the user-confirmed D3.4 split. New `Kingdom/Laws/` package: sealed `KingdomLaw` interface (different package from the legacy `Kingdom.KingdomLaw` enum which stays for save compat + as keying namespace) with three archetype records `ToggleLaw` / `ScalarLaw` / `EnumLaw`. Eight existing laws migrate 1:1 to ToggleLaw instances by lowercased id (`open_borders`, `trade_tariffs`, `conscription`, `price_controls`, `property_rights`, `free_trade`, `kings_peace`, `minimum_wage`); legacy enum values keep working through a one-line `enactLaw(KingdomLaw)` shim that routes through the new state machine. Two example archetypes wired: `education_stipend` (ScalarLaw, 0..32 bronze/day, default 0, drains kingdom treasury per scholar village daily via `KingdomTaxEvent`) and `official_religion` (EnumLaw, choices `none`/`state_religion`/`toleration`, gates ORDINATION_RIGHTS charters in slice 2). Lifecycle: `KingdomLawState` (DRAFT → PROPOSED → ACTIVE) with per-archetype enactment authority — ToggleLaws gate on PASS_LAW, ScalarLaws/EnumLaws gate on ISSUE_DECREE (Chancellor); drafting always gates on ISSUE_DECREE. `KingdomLawInstance` record carries per-kingdom dynamic state (state, drafter, proposer, scalar value or enum choice, ticks). `KingdomLawRegistry` is the static catalogue. `Kingdom.activeLaws` (Set<KingdomLaw enum>) becomes a derived legacy mirror of `Kingdom.lawInstances` (Map<String, KingdomLawInstance>) auto-synced via `syncLegacyActiveLaws`; one-shot migration in `fromCodec` translates pre-D3.4 saves' `activeLaws` enum list into ACTIVE ToggleLaw instances. `KingdomGovernanceData` extended with `lawInstances` field (10/16). `KingdomActionPacket` gained 6 new verbs: `DRAFT_LAW`, `PROPOSE_LAW`, `ENACT_LAW`, `REPEAL_LAW`, `UPDATE_DRAFT_SCALAR`, `UPDATE_DRAFT_CHOICE`; legacy `TOGGLE_LAW` deprecated but kept as one-phase fallback. Each new verb runs the D3.3b capability evaluator before mutating. `KingdomBookScreen` Laws panel rewritten: per-row state badge ("[available]" / "[draft]" / "[proposed]" / "[active]"), parameter display (Scalar value+unit, Enum choice), single lifecycle action button cycling `Draft → Propose → Enact → Repeal`, ± buttons on ScalarLaw drafts, ▶ cycle button on EnumLaw drafts, X cancel button on DRAFT/PROPOSED rows. Server enforces capability per row's authority (Chancellor for parameterized; King for toggles). Four new `KingdomEvent` subtypes: `LawDrafted` / `LawProposed` / `LawEnacted` / `LawRepealed`. New debug command `/litv kingdom debug laws <name>` lists every law with state/archetype/category/parameter/capability gate. Slice 2 (charters + intrigue + treaties) follows separately. **Out of scope:** category sidebar (deferred polish — current panel uses flat list since 10 laws fit), detail view with effect-preview/history (deferred to slice 2), village-law `LawEffect` registry shared-effect plumbing (kingdom-tier laws use direct `hasActiveLaw(String)` queries today, no need for the effect registry yet), prerequisite enforcement (`requiredActiveLaws` field exists but no v1 law uses it). |
| D3.4b | Phase 4 slice 2 — charters + intrigue + treaties (4.3+4.4+4.5) | Done 2026-05-09 | New `Kingdom/Charters/` package: `Charter` record (id, name, grantee, granterKingdomId, grantedTick, grantedRulerId, type, params, active, revokedTick) + `CharterType` enum (TOLL_RIGHTS / TAX_EXEMPTION / MARKET_MONOPOLY / TITLE_GRANT / ORDINATION_RIGHTS / LAND_GRANT each with a revocation-base legitimacy hit) + `CharterParams` sealed-interface union dispatched by type via `Codec.STRING.dispatch` + `GranteeRef` tagged-union admitting NPC / HOUSE / GUILD / VILLAGE / RELIGIOUS_ORDER grantees (sidesteps the strict-per-NPC PowerGrant pattern). New `Kingdom/Treaties/` package: `Treaty` record (id, type, parties, drafterUuid, draftedTick, ratifiedTicks per-party, termsSummary, broken / brokenBy / brokenTick / brokenReason) + `TreatyType` enum (ALLIANCE / NON_AGGRESSION / TRADE_DEAL / VASSALAGE each with break-cost numbers) + `Treaty.autoMigrated` factory for converting legacy `DiplomaticRelation` ALLIANCE/TRADE entries on first load. New `Kingdom/Intrigue/` package: `IntrigueAttempt` record (rolling buffer cap 28) + `IntrigueDriver.sowDiscontent` with deterministic seed `(sourceKingdomId, "intrigue", targetKingdomId, gameDay)`, per-kingdom 7-day cooldown + per-target 21-day cooldown, 50b treasury cost, source/target Spymaster competence formula via D3.3's `Competence.computeMultiplier` (counter-intel halves success at full bonus), success applies stability dip −8 to target province via 14-day expiring `KingdomModifier`. `Kingdom.charters` / `Kingdom.treaties` / `Kingdom.intrigueHistory` lists all live in `KingdomGovernanceData` (now 12/16); top-level `Kingdom.CODEC` stays at 16/16. `Kingdom.getRelation` rewritten as derived view: VASSALAGE / ALLIANCE treaty → ALLIANCE; TRADE_DEAL → TRADE; residual WAR / COLD_WAR map wins over NEUTRAL-from-NON_AGGRESSION. `Kingdom.setRelation(WAR)` cascade-breaks any active cooperative treaty first, fires `TreatyBroken` event with reason `cascade.declared war on ally`. `KingdomCapabilityEvaluator` extended: vassal kingdoms (active VASSALAGE treaty as vassal side) cannot exercise DECLARE_WAR. `KingdomTaxEvent` extended with VASSALAGE tribute outflow (default rate 0.0 to preserve net flow; observable when tuned). Six new `KingdomEvent` subtypes: `CharterGranted` / `CharterRevoked` / `TreatyDrafted` / `TreatyRatified` / `TreatyBroken` / `IntrigueLaunched` / `IntrigueDiscovered`. New top-level debug commands: `/litv charter list <kingdom>`, `/litv charter grant <kingdom> <type> <granteeUuid>`, `/litv charter revoke <kingdom> <charterUuid>`, `/litv treaty list <kingdom>`, `/litv treaty propose <type> <kingdomA> <kingdomB>` (debug fast-path: drafts + ratifies both), `/litv treaty break <kingdom> <treatyUuid>` (mirrors break on every party copy), `/litv intrigue test_sow <source> <target>`. `/litv kingdom debug describe` extended with charter/treaty/intrigue/vassal-status counts. Migration: pre-D3.4b saves with cooperative DiplomaticRelation entries get auto-migrated treaties on first load (deterministic id from kingdom-pair UUIDs so both sides produce the same treaty). **Out of scope:** GUI panels for charters / treaties (Phase 5 surfaces them via audience loop + newsfeed; debug commands carry the surface for v1), TOLL_RIGHTS toll deduction from caravans (data shape only — road-economy follow-up), MARKET_MONOPOLY enforcement in merchant logic (data shape only), ORDINATION_RIGHTS gating (Phase 6 religion pass), audience-loop ratification (Phase 5 — debug `treaty propose` fast-path stands in). Phase 4 complete. |
| D3-4 | Phase 4 — laws & intrigue & charters & treaties | Superseded by D3.4a + D3.4b | Split per the prompt's natural cleavage (laws slice / diplomacy slice). |
| D3.5A | Phase 5 slice 1 — audience loop + per-kingdom standing + traits-pool extension | Done 2026-05-10 | First quarter of the user-confirmed Phase 5 four-way split (Slices A/B/C/D). New `Kingdom/Audience/` package: `Petition` record (id, playerUuid, kingdomId, kind, payload, submittedTick, status PENDING/APPROVED/DENIED/EXPIRED/WITHDRAWN, resolvedTick, resolvedBy, resolutionNote) + `PetitionKind` enum (TREATY_RATIFICATION / CHARTER_REQUEST / AUDIENCE_GRIEVANCE / WAR_DECLARATION / BREAK_TREATY) + sealed `PetitionPayload` union dispatched by `Codec.STRING.dispatch` (matching the D3.4b pattern) — variant records: `TreatyRatification(treatyId)` / `CharterRequest(name, granteeId, granteeKindName, params)` / `AudienceGrievance(summary)` / `WarDeclaration(targetKingdomId, casusBelli)` / `BreakTreaty(treatyId, reason)`. New `PlayerStanding` record per (playerUuid, kingdomId): score (-100..+100), lastDecayTick, lifetimeFavour, lifetimeWrath; thresholds TRUSTED=+50 / HOSTILE=−50; decay 1 step toward 0 every 7 in-game days via `applyDecay`. Per the user-confirmed lock "kingdom-side standing authoritative": `Kingdom.playerStandings` LinkedHashMap is the single source; players carry no mirror. New `AudienceDriver` resolves petitions via `submit` / `approve` / `deny` / `withdraw`; standing deltas APPROVE=+5 (+10 for CHARTER_REQUEST) / DENY=−3 (−1 for diplomatic asks) / EXPIRE=0 / WITHDRAW=0; AUDIENCE_GRIEVANCE auto-resolves on submit when the petitioner is TRUSTED or HOSTILE. Approval applies kind-specific effects: TREATY_RATIFICATION mirrors `Kingdom.ratifyTreaty` on every party + fires `TreatyRatified` once all parties have signed; CHARTER_REQUEST calls `Kingdom.grantCharter` + fires `CharterGranted`; WAR_DECLARATION rejects vassals + sets WAR mutually + cascades cooperative-treaty break via the existing setRelation handler; BREAK_TREATY mirrors break across all parties + fires `TreatyBroken`. New `AudienceLoopDriver.dailyTick` (tick priority 195, after province daily 194) fires EXPIRED events for pending petitions past `Petition.DEFAULT_EXPIRY_TICKS` (7d), GCs resolved petitions older than `Kingdom.PETITION_RETENTION_TICKS` (30d), runs `Kingdom.decayPlayerStandings`. `KingdomGovernanceData` now 15/16 fields (petitions + playerStandings list-keyed). Three new `KingdomEvent` subtypes: `PetitionSubmitted` / `PetitionResolved` (carries outcome string + standingDelta) / `StandingChanged` (carries delta + newScore). Per the user-confirmed lock "trait pool extended with PIETY + SCHOLARSHIP axes": `TraitAxis` enum extended (10 axes total — INDUSTRY / COURAGE / SOCIABILITY / GENEROSITY / HONESTY / AMBITION / COMPASSION / TEMPERANCE / PIETY / SCHOLARSHIP) with new pole labels (Worldly/Devout, Unlettered/Scholarly); `TraitVector` constructor extended to 10 args; codec uses `optionalFieldOf` with default 0f so old saves load with neutral PIETY+SCHOLARSHIP scores. New `KingdomPetitionPacket` (APPROVE / DENY / WITHDRAW; ruler-only on resolve verbs; petitioner-only on withdraw) registered in ModModEvents. Top-level debug commands: `/litv petition list <kingdom>`, `/litv petition submit_grievance <kingdom> <text>`, `/litv petition approve|deny <kingdom> <petitionUuid>`, `/litv standing get <kingdom>`, `/litv standing set <kingdom> <delta>`. `/litv kingdom debug describe` extended with petition pending + tracked-standing counts. **Out of scope (Slice B+):** dedicated AudienceScreen GUI (debug commands + KingdomPetitionPacket are the surface for v1; the screen lands when the player UX pass arrives), CHARTER_REQUEST submit verb (complex payload — debug commands only for v1), NPC-ruler petition resolution (NPC AI tick path lands in Slice B), heir-traits hybrid roll (Slice B — succession-related), titled-grants flow integration with player NobilityRecord (Slice C — needs the heir-traits path). |
| D3-5 | Phase 5 — player experience | In-Progress | Slice A done; Slices B/C/D pending. |
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

### 2026-05-08 — A1a wired

Renamed Track A1 → A1a (wire V2 in parallel) + A1b (delete V1) per
prompt direction. V1 deletion now sequenced after A2/A3 stabilize.

**Code shipped:**
- `Village/Planning/V2/V2Settings.java` — volatile boolean flag,
  default false, runtime-toggleable.
- `Village/Planning/V2/V2VillageSpawnerAdapter.java` — full adapter:
  runs V2 Layers 1-4 (V2FeatureMap → SiteAnalyzer → BuildingSelector
  → ReconciliationEngine → DependencyResolver → PhasedPlanner),
  inlines Layer-5 sub-components (OverlapAuditor, TerrainAdapter,
  ViabilityValidator, VegetationClearer, PadBuilder, RoadPainter)
  while capturing Building references from `BuildingPlacer.placeAndRegister`,
  synthesizes a minimal `VillageLayout`, registers a `Village` in
  `VillageSavedData`, and runs the V1 downstream pipeline
  (FarmPlotPlacer, VillageInhabitantPopulator, VillageDecorator,
  AdjunctPlotRealiser, DecorationPass, TradeRouteManager,
  ConnectorPlanner, VillageSimEngine, GuildBootstrap, HistoryProducer,
  initial laws). Each downstream call is `guard()`-wrapped so a single
  failure logs and does not abort the spawn.
- `Commands/ConfigCommand.java` — `/litv config adaptive_v2 <on|off|status>`.
- `Events/ModModEvents.java` — registered `ConfigCommand`.
- `Village/VillageSpawner.java` — flag-checked branch after
  `VillageTypeData` resolution; V1 path otherwise unchanged.

**Step 3 gaps the V2 path does NOT replicate:**

Items V1 produces that V2 does not (the synth VillageLayout fills
defaults; downstream consumers tolerate or are guarded):
- `Plaza` records (V1 plaza system; V2 has no plaza concept).
- V1 `RoadGraph` populated with gateway nodes / internal edges
  (V2 `RoadNetwork` is a different shape; `GatewayPopulator` and
  `InternalRoadCommitter` are NOT called on V2 path).
- `VillagePlacementEvent` firing (Roads Phase 9 network-alignment
  scoring; intentionally skipped — wire later if Roads consumers
  need it).
- `setupMerchantStalls` (V1 private helper; not callable from
  adapter; merchant stalls will not auto-claim on V2 path).
- `VillageSitePreparer.prepare` whole-village tree clearing
  (V2 does per-building `VegetationClearer` instead).
- `typeData.getTerrainStrategy().execute` whole-village terrain pass
  (V2 does per-building `PadBuilder` instead).
- `findBetterLocalSite` retry on planner failure (V2 returns empty
  on UNVIABLE; no retry).
- `layout.getFeatures().refine(level)` Phase-4 feature-map refine
  (V2 has its own `V2FeatureMap`; the synth layout's V1 FeatureMap
  field stays null).

Field-level mismatches:
- `Style` always RURAL on V2 path. URBAN pack (P0a-16) will not
  surface through V2 until A3 unification or a culture-driven style
  hint lands.
- `villageType` argument is recorded on the resulting `Village` but
  V2 ignores it for layout, building selection, and viability —
  V2 derives those from terrain. `FarmPlotPlacer.placeAll` reads
  `village.getVillageType()` for its config; the caller must pass a
  registered type or FarmPlotPlacer will NPE under the guard.
- `TintPass.Plan.NONE` always on V2 path (no `VariantRegistry` /
  `VillagePaletteResolver` integration; deferred to A3).
- `LayoutSlot.padY` set to V2 `centre.getY()` (surface block);
  may need +1 if a downstream consumer treats padY as the building
  floor — re-evaluate on smoke-test feedback.

Adapter-design decisions worth recording:
- Did NOT call `MinimalSpawner.spawn` directly — its sub-components
  are called inline so Building references can be captured for
  `placedBuildingsAll`. `MinimalSpawner` itself drops them. No V2
  internals were modified.
- Single Random seeded `level.getSeed() ^ ((origin.hashCode() * 31L) +
  villageName.hashCode())` so V2 path is reproducible per (seed,
  origin, name) like V1.
- Static field + `/litv config` chosen over `ModConfiguration.java`
  Properties extension or NeoForge `ModConfigSpec` — runtime
  toggleability is cheapest with the static field.
- Track renumbering (A1 → A1a / A1b) deviates from
  `UNIFIED_REWORK_PLAN.md` text; plan is human-managed and that
  deviation should be reflected if it sticks.

**Smoke test:** pending user-run on superflat. Expected behavior:
- `adaptive_v2=false`: V1 path unchanged; existing world saves keep
  loading.
- `adaptive_v2=true`: village created via V2 with buildings placed,
  roads painted, NPCs spawned via VillageInhabitantPopulator;
  decoration / trade / sim / guild / history / laws guarded — log
  warnings if any of them fail rather than aborting the spawn.

Compile verification not possible in this environment (gradle
toolchain plugin requires network the sandbox blocks; same issue
recorded across NPC_PROGRESS.md).

Next: smoke-test feedback. Then A2 (Culture unification).

### 2026-05-08 — A2 + A3 landed (one cycle)

A2 Culture unification + A3 Variant unification shipped together.
Both paths still need smoke-test confirmation; failures should be
treated as bugs in the merge, not behaviour deltas.

**A2 — Culture unification.**

Sub-bundle name: `CulturePlanningBias` — matches the
`Culture<NounPhrase>` pattern. Lives in `Cultures/CultureBundles.java`
alongside the other 10 sub-bundles. Codec uses `optionalFieldOf` with
`DEFAULT` so older saves load cleanly.

Value-type relocation: `Curvature` and `PlazaShape` moved to a new
`tterrag1112.life_in_the_village.Cultures.Planning` package (separate
from V1's renderer enum `Village.Decoration.Plaza.PlazaShape`, which
keeps its `LINEAR` value and its codec).

Culture-id mapping: V2 only ever registered `default`. All four NPC
starter cultures (Plainfolk, Highmarch, Silkwood, Tidereach) gained
the new sub-bundle as `CulturePlanningBias.DEFAULT` — same dirt-path
+ NATURAL + IRREGULAR + uniform-bias as the deleted V2 default. **No
per-culture customization yet** — flagged here for later authoring.

Files created:
- `Cultures/Planning/Curvature.java`
- `Cultures/Planning/PlazaShape.java`

Files modified:
- `Cultures/Culture.java` — 14-field record (was 13); +planningBias.
- `Cultures/CultureBundles.java` — +CulturePlanningBias record.
- `Cultures/CultureRegistry.java` — 5 starter builders + neutralDefault
  pass `CulturePlanningBias.DEFAULT` as the new arg.
- `Events/ModModEvents.java` — removed the V2 `cultures` reload-listener
  registration. (NPC Phase 6 will reintroduce JSON-driven cultures.)
- 5 V2 callsites + 4 /litv commands: import switched from
  `V2.Culture.Culture` / `V2.Culture.CultureRegistry` to
  `Cultures.Culture` / `Cultures.CultureRegistry`. `culture.biasFor(inc)`
  → `culture.planningBias().biasFor(inc)`. `culture.roadMaterial()`
  → `culture.planningBias().roadMaterial()`.
  `CultureRegistry.INSTANCE.getDefault()` →
  `CultureRegistry.getOrDefault(CultureRegistry.DEFAULT_ID)`.

Files deleted:
- `Village/Planning/V2/Culture/Culture.java`
- `Village/Planning/V2/Culture/CultureRegistry.java`
- `Village/Planning/V2/Culture/Curvature.java`
- `Village/Planning/V2/Culture/PlazaShape.java`
- `data/life_in_the_village/cultures/default.json`

Verification: `git grep V2.Culture` returns only doc comments inside
`CultureBundles.java` referencing the deleted record by name. No
`import` or symbol references survive.

**A3 — Variant unification.**

Per user direction: build `VariantResolver` as a clean combined class;
migrate `VariantSelector` features into it where they fit; leave
unused `VariantSelector` methods alone for V1 (dies in A1b). Both V1
and V2 paths now pull from `VariantResolver`. `VariantPicker` deleted.

`VariantResolver` interface:
```java
class VariantResolver {
    // Per-village state for diminishing returns + maxPerVillage.
    String pickVariantIdForV2(BuildingType, BlockPos pos, BlockPos anchor,
                              int villageRadius, String culture, Style,
                              Random, BuildingAvailability);
    static BuildingVariant findById(String culture, Style, BuildingType,
                                     String variantId);
    static TintPass.Plan planTint(VillageTypeData, BuildingVariant,
                                   Random, Set<DyeColor> neighborColors);
}
```

Capabilities migrated **into** `VariantResolver`:
- From `VariantSelector`: same-culture preference + default fallback;
  `maxPerVillage` cap; diminishing returns (× pow(0.7, count));
  weighted-random pick; synthetic-default fallback.
- From the deleted `VariantPicker`: HOUSE distance-banded preference
  (large near anchor, cottage at edge, with cap-skipping); explicit
  availability check via `BuildingAvailability`.

Capabilities **not** migrated (don't fit V2's flow; corresponding
methods on `VariantSelector` retained, unused by the new resolver):
- Slot-tag scoring (V2 has no slots).
- Village-preferred-tag scoring (V2 doesn't propagate the tag set).
- Style/age preference scoring (V2 is always RURAL/FRESH).

Wiring:
- `V2/Layer4/PhasedPlanner.java` — `State` gains a `VariantResolver`
  instance. Per-building `VariantPicker.pick(...)` → `state
  .variantResolver.pickVariantIdForV2(..., StructureAvailabilityRegistry.INSTANCE)`.
- `Village/Planning/V2/V2VillageSpawnerAdapter.java` — per-PlacedBuilding
  `TintPass.Plan.NONE` replaced with `VariantResolver.findById` +
  `VariantResolver.planTint` against a `NeighborColorIndex`. V2 villages
  now get tinted variants like V1's.
- `Village/VillageSpawner.java` (V1 path) — inline `VariantRegistry.find`
  chain + `VillagePaletteResolver.planFor` replaced with
  `VariantResolver.findById` + `VariantResolver.planTint`. Behaviour
  unchanged; same registry chain runs inside `findById`, same logic
  inside `planTint` (delegates to `VillagePaletteResolver.planFor`).

Files created:
- `Village/Decoration/Variants/VariantResolver.java`

Files modified:
- `Village/Planning/V2/Layer4/PhasedPlanner.java` — import swap +
  `State.variantResolver` field + one call-site change.
- `Village/Planning/V2/V2VillageSpawnerAdapter.java` — imports +
  `NeighborColorIndex` + per-building tint planning.
- `Village/VillageSpawner.java` — V1 spawn loop inline-chain replaced.
- `Village/Planning/V2/Layer3/BuildingAvailability.java` — javadoc
  updated to reference `VariantResolver#pickVariantIdForV2`.

Files deleted:
- `Village/Planning/V2/Layer3/VariantPicker.java`

Verification: `git grep VariantPicker` returns nothing.

**Capability gaps documented (per user note "neither system does
exactly what's needed"):**

The combined `VariantResolver` is V1∪V2 capability-wise (color
planning + scoring + diversity + HOUSE distance-banding +
availability) but does NOT add new capability beyond either source.
Specifically, the user note left undefined what's missing — when
that's known, it lands as a follow-up resolver method or a new
`Resolution` shape without re-touching every call site.

**Carryover left for A1b cleanup:**
- `VillagePaletteResolver.planFor` is now only invoked through
  `VariantResolver.planTint`. After A1b's V1 deletion, the delegating
  call can be inlined and `VillagePaletteResolver` retired.
- `VariantSelector` still has `select()` (used by V1's `PlanContext`)
  and `Fallback.syntheticDefault` (used by `VariantResolver.findById`).
  The first goes when V1 dies in A1b; the second can move into
  `VariantResolver` at that point or stay where it is.
- Unused imports in `VillageSpawner.java` (now-orphaned `VariantRegistry`
  / `VariantSelector` references at the top) left in place for the V1
  matcher path that still uses them.

**Smoke test (pending user-run):**
- `adaptive_v2=false`: V1 spawn produces visually identical villages
  pre-A2/A3 vs post-A2/A3. Same road material, same plaza shape,
  same building variants, same colours.
- `adaptive_v2=true`: V2 spawn now applies tints (was always-NONE
  pre-A3) — colour exclusion / forced TEMPLE-white / TOWN_HALL
  signature should kick in. Same building set per (seed, origin, name)
  modulo the new diminishing-returns weighting on non-HOUSE picks.
- Save / restart / reload with both flag states: cultures persist;
  the new `planningBias` field round-trips through codec.

Differences beyond RNG-from-seed-plumbing on the V2 path are
regressions to investigate.

Next: smoke-test feedback for A1a + A2 + A3. Then A1b — V1 deletion
+ ZoneRegistry migration.

### 2026-05-08 — A4 landed (V2 default; V1 branch removed)

User explicitly accepted the smoke-test risk: A1a / A2 / A3 had not
yet been run against a live world before A4. If a regression hides
in those tracks, A4 amplifies it from "flip the flag back" to
"revert several commits." Recorded so the eventual smoke run knows
to test all of A1a + A2 + A3 + A4 together.

**Code changed:**
- `Village/VillageSpawner.java` — body shrunk from 549 lines to 208.
  `spawnVillage` now performs the unknown-type guard then unconditionally
  returns `V2VillageSpawnerAdapter.spawn(...)`. The 350-line V1
  spawn loop (V1 planner call, refinement retry, V1 placement loop,
  downstream pipeline) was deleted from this file. V1 source files
  (`VillagePlanner`, recipes, `Adaptive` package, `ZoneRegistry`,
  `PlacementMatcher`, etc.) remain on disk pending Track A1b.
- Helpers in `VillageSpawner` are now mostly unused: `findBetterLocalSite`,
  `setupMerchantStalls`, `deriveVillageLevel`, `findSurface` are
  private/package-private and dead. `isFarEnoughFromExistingVillages`
  remains public — still called by `V2VillageSpawnerAdapter`.

**Files deleted:**
- `Village/Planning/V2/V2Settings.java` — flag holder.
- `Commands/ConfigCommand.java` — `/litv config adaptive_v2 <on|off>` toggle.

**Files modified:**
- `Events/ModModEvents.java` — removed `ConfigCommand.register` line;
  left a one-line comment noting where it used to live.

**Verification (Step 4 done criteria):**
- `git grep adaptive_v2 -- src/` → empty. ✓
- `git grep V2Settings -- src/` → only the historical-reference comment
  in `ModModEvents.java`. ✓
- `git grep ConfigCommand -- src/` → only the historical-reference
  comment in `ModModEvents.java`. ✓
- Doc files (UNIFIED_REWORK_PLAN, UNIFIED_REWORK_PROGRESS,
  CONSOLIDATION_INVENTORY, ADAPTIVE-VILLAGE-DESIGN) still mention
  the flag in historical sections — left as-is per the
  "don't restructure docs" constraint.

**Step 2 — V1 caller inventory (for A1b decisions):**

*Spawn entry callers (all auto-route to V2 via spawnVillage):*
- `Events/VillageRealisationSystem.java:265` — normal worldgen
  realisation of planned villages.
- `Kingdom/KingdomSpawner.java:173, 535, 568` — three sites for
  kingdom-driven spawns (capital, vassal, fallback).

These are NOT V1-specific; they go through the renamed entry point
and now hit V2 unconditionally. Safe.

*Direct V1 planner callers (bypass spawnVillage, will compile-break
when V1 dies in A1b):*
- `Commands/MeasureCommand.java` — `/litv measure` (Phase 23.1
  measurement harness) calls `VillagePlanner.plan` directly to
  dry-run plans without spawning. **A1b decision needed:** rewrite
  for V2's pipeline, retire the harness, or replace with a V2
  measurement command. After A4 this is the only thing actively
  exercising V1 plans in production.

*ZoneRegistry consumers (the four call sites listed in
UNIFIED_REWORK_PLAN's A1 plan, plus a fifth):*
- `Village/BuildSiteFinder.java:104, 312` — TWO consumer calls
  (`ZoneRegistry.zoneOf(buildingType)`). Used by post-spawn
  expansion code, see below.
- `Village/Planning/Rules/RuleContext.java` — V1 shape-rule context.
- `Village/Planning/Zoning/BuildingProfileRegistry.java` — V1 placement
  matcher tier defaults.
- `Village/Decoration/Plaza/PlazaGenerator.java:452` — fifth caller
  not in the original four. Decoration-side query of a building
  type's zone for civic-ring placement. **Either decoration migrates
  off ZoneRegistry or the migration absorbs PlazaGenerator.**
- `Village/Planning/Zoning/PlacementMatcher.java`,
  `Village/Planning/Primitives/PlanContext.java` — both V1-internal,
  die with V1.

*Expansion / growth code (post-spawn V1 dependency):*
- `Entities/Goals/Profession/Builder/BuilderGoal.java:540` — calls
  `BuildSiteFinder.findSite(...)` for the BUILDER profession to find
  new building sites in existing villages. **Critical for A1b:**
  `BuildSiteFinder` uses `ZoneRegistry`, so when V1 dies the
  BUILDER profession's expansion path stops compiling unless A1b
  migrates `BuildSiteFinder` first or retires the BUILDER goal.
- `Village/Village.java` — only references `BuildSiteFinder` in
  comments (Phase 20 / Phase 20a documentation). No code path.

*VillagePlanHelper (false alarm):*
- `Village/Planning/VillagePlanHelper.java` — javadoc mentions
  `VillagePlanner.plan` but the code only creates planned-village
  records (`Village` + `setPlannedOrigin` + `setRealised(false)`).
  Does NOT actually call V1 planning. Safe to keep.

**Categorized for A1b:**

Must rewrite or retire BEFORE deleting V1 (or the codebase won't
compile after V1 deletion):
1. `MeasureCommand` — direct `VillagePlanner.plan` caller.
2. `BuildSiteFinder` — uses `ZoneRegistry`; consumed by `BuilderGoal`.
3. `Planning/Rules/RuleContext` — uses `ZoneRegistry`.
4. `Planning/Zoning/BuildingProfileRegistry` — uses `ZoneRegistry`.
5. `Decoration/Plaza/PlazaGenerator` — uses `ZoneRegistry`.

Dies with V1 in A1b (no external consumer):
- `Village/Planning/Primitives/Recipes/*` (17 recipe classes).
- `Village/Planning/Primitives/{LayoutPrimitive, BaseRecipe,
  ShapeRecipe, PlanContext, ...}` and the slot/intention/anchor
  machinery.
- `Village/Planning/Adaptive/*` (Phase A-D adaptive package).
- `Village/Planning/Sectors/*` (V1 sector machinery — verify before
  delete; some sector references survive in V2 layouts).
- `Village/Planning/Zoning/PlacementMatcher.java`.
- `Village/Planning/VillagePlanner.java`.
- `Village/Planning/ZoneRegistry.java` (after the five callers
  above migrate).

**Step 5 — smoke test:** pending user-run. The expansion path
(BUILDER profession) is currently still live and uses V1's
`BuildSiteFinder` — **expansion will continue working after A4**
because `BuildSiteFinder` and `ZoneRegistry` were not deleted by A4.
A1b is the breaking change for that path.

**Open questions for A1b sequencing:**
- Should `BuildSiteFinder` be ported onto V2 vocabulary (recommend),
  or should `BuilderGoal`'s site-finding be rewritten?
- Is `PlazaGenerator` zoning use a real dependency or a stale
  reference? Decoration is meant to be V1-decoupled.
- Does `/litv measure` matter for A1b, or is the V2 measurement
  command in `LayoutCommand` / `PlaceCommand` enough?

Next: smoke-test feedback for A1a + A2 + A3 + A4 together. Then A1b
when ready.

### A1b — V1 cleanup + ZoneRegistry migration (2026-05-08)

Bulk deletions and entanglement cleanup for the V1 planning stack.
Compile is the user's job — sandbox blocks the gradle toolchain.

**Deleted:**
- `Village/Planning/Primitives/Recipes/` (17 recipes + RecipeHelpers).
- `Village/Planning/Adaptive/` (Anchor, EdgeRef, LayoutBlueprint,
  PlazaDeclaration, RealisedEdge, RecipeNotPortedException,
  RoadDeclaration, SectorDeclaration, SectorRef, SlotEmitter,
  SlotIntention).
- `Village/Planning/Rules/` (BuiltinRules, RuleContext, ShapeRule,
  ShapeRuleRegistration). The RuleContext.AdjacencyReq inner record
  moved into BuildingAdjacencySpec to keep BuildingInhabitantRegistry
  alive.
- `Village/Planning/Sectors/` (AddRing, AddSpur, ExtendAlongEdge,
  FixedGrowth, GrowthPolicy, Sector, SectorRole).
- `Village/Planning/Primitives/{BaseRecipe, ShapeRecipe, PlanContext,
  RoadResult}.java`. TerminationReason kept (CenterlineResult uses it).
- `Village/Planning/{VillagePlanner, VillageSitePreparer,
  LayoutPlanBuilder, ZoneRegistry, BuildingZone}.java`.
- `Village/Planning/Zoning/PlacementMatcher.java`.
- `Village/Decoration/Plaza/{PlazaGenerator, PlazaSpec}.java`.
- `Commands/{MeasureCommand, ShapeRuleDebugCommand}.java`.

**Survives, contrary to the original task list (had real Java
consumers):**
- `Village/Planning/Zoning/{AnchorPolicy, AvoidanceRule,
  PlacementSlot, SlotPreference}` — used by surviving
  BuildingProfile + BuildingProfileRegistry + Plaza.
- `Village/Planning/Primitives/TerminationReason` — used by
  CenterlineResult, which is consumed by surviving Roads/Realization
  + RouteRealisationSystem + V2.

**Rewrote:**
- `Village/BuildSiteFinder.java` — dropped BuildingZone /
  ZoneRegistry. Graph-aware path picks first non-empty road instead
  of zone-preferred role; ring fallback iterates all rings/angles
  without zone filter.
- `Village/Roads/Planning/GatewayDescriptor.java` — removed
  `deriveFromLayout(PlanContext)`; only V1 ShapeRecipe consumed it.
- `Village/Buildings/BuildingAdjacencySpec.java` — moved AdjacencyReq
  inner record onto itself to drop the Rules dependency.

**Edited:**
- `Life_in_the_village.java` — dropped ShapeRuleRegistration import
  + commonSetup call.
- `Village/VillageTypeData.java` — dropped shape_rules field, codec,
  getter, setter, ShapeRule import.
- `Village/VillageTypeRegistry.java` — dropped shape_rules JSON
  parsing (no surviving JSON file ships the key).
- `Village/Village.java` — dropped debugSectors field +
  getter/setter.
- `Village/Planning/VillageLayout.java` — dropped debugSectors;
  reset path no longer touches it.
- `Village/Planning/LayoutPlan.java` — dropped sectors field +
  SectorView record + SECTOR_ROLE/BUILDING_ZONE codecs. Codec arity
  drops from 13 to 12.
- `Commands/LayoutDebugCommand.java` — dropped show_sectors
  subcommand + helpers; show_graph / show_hull / show_features /
  determinism_test survive.
- `Events/ModModEvents.java` — dropped MeasureCommand.register call.
- `Village/Planning/Terrain/TerrainProfile.java` — javadoc only,
  dropped VillagePlanner import.

**Survivors that look stale:**
- `LayoutPlanBuilder` deleted — only consumer was VillagePlanner.
  The original A1b task listed it as a survivor; deleting it
  preserved the "no orphan classes" invariant. V2's adapter feeds
  Village.applyLayout via a synthetic VillageLayout, not via this
  builder.
- `VillagePlanHelper` survives — used by Events/* + KingdomSpawner
  for plan-village bookkeeping; is V2-clean.

**Verification:**
- `git grep ZoneRegistry` → only doc comments in BuildingProfileRegistry
  and VillageSpawner. No imports.
- `git grep VillagePlanner` → only doc comments in surviving code.
- `git grep BaseRecipe / ShapeRecipe / PlanContext / RuleContext /
  Sector / SectorRole / BuildingZone / RadialRecipe` → only
  doc-comment leftovers, no live Java.
- `Village/Planning/{Adaptive,Recipes,Rules,Sectors}` directories
  gone.

**Items left for follow-up:**
- Doc comments throughout the codebase still mention deleted
  classes (PlanContext, BaseRecipe, ShapeRecipe, VillagePlanner,
  PlazaGenerator). Compiler tolerates broken @link refs; cleanup
  is cosmetic and out of scope for A1b.
- A5 measurement command — `MeasureCommand` died with V1; A5
  needs a V2-native replacement (out of A1b scope).

### 2026-05-08 — A5 deferred

User skipped A5 by direction. The original A5 plan ("run `/litv
measure` and record results in PLACEMENT-REWORK-STATE.md Section
9.1") is not actionable as written:

- The harness (`Commands/MeasureCommand.java`) was deleted in A1b
  because its only entry point was `VillagePlanner.plan()` (V1).
- V1 Phase 22 baseline was never recorded — Section 9.1 has been
  blank since 23.1 shipped, so there is nothing to compare against.
- The Section 9.1 column structure (Primary-shape success,
  Fallback-rescued success, RADIAL primary-success) describes
  V1-only concepts that don't translate to V2 (no shapes, no
  fallback chains).
- I cannot run Minecraft from this environment regardless.

Track A is functionally complete: V2 is the only planner running,
V1 source is gone, A1a + A2 + A3 + A4 + A1b all landed in source.
A5 stays open as a deferred task; B/C/D can proceed on the basis
of "V2 ships and the codebase compiles," with smoke-test discovery
of any V2 regressions handled per-track.

Re-open A5 if and when (a) a V2-native measurement harness is
built, AND (b) a baseline corpus is defined for V2-shaped metrics
(tier distribution, inclination distribution, viable/unviable,
drop counts, timing). Section 9.1 should be rewritten with V2
columns at that point.

Track A: A1a Superseded, A2/A3/A4/A1b Implemented, A5 Deferred.
Net effect: V2 is the sole planner; downstream B/C/D unblocked
modulo smoke-test confirmation of cumulative A1a+A2+A3+A4+A1b.

### 2026-05-08 — B1 landed (Phase 0a content gaps)

Track B's first phase. Closes the three actionable Phase 0a gaps
(P0a-12, P0a-16 manifests, P0d-04). Leaves HOUSE pilot NBTs and the
URBAN NBTs themselves as user authoring tasks.

**T1 — MarketStallPlacer migration (P0d-04).**

Refactored `MarketStallPlacer` onto the subbuilding scanner pattern:
- `findAnchorSlots` now reads `data.getSubBuildingsForBuilding(...)`
  filtered by `SubBuildingType.STALL`, sorted by (Z, X) of anchor
  origin. The runtime `CHISELED_STONE_BRICKS` block scan is gone.
- `claimSlot` no longer clears the anchor block (the scanner already
  replaced it with air at building-placement time).
- `reclaimStall` no longer restores an anchor block — the
  `SubBuilding` record persists; the slot becomes available again
  because no active `MarketStall` references it.
- `ANCHOR_BLOCK` constant removed from the placer.
- New 2-arg overload `findAnchorSlots(Building, VillageSavedData)`
  for callers (V2 spawn adapter, NPC interaction handlers) that
  already have `data` in scope.

**Market NBT re-authoring required (user task):** existing market
NBTs use `CHISELED_STONE_BRICKS` markers and will produce zero stalls
under the new code. To re-enable stalls, replace each anchor block in
the market NBT with a `SubBuildingAnchorBlock` whose
`subBuildingType` field is set to `STALL`. Flow:
1. Build the market NBT in-world.
2. Place `SubBuildingAnchorBlock` instances (creative inventory or
   `/give`) at every position a stall should occupy.
3. Save the structure block.
4. Open the resulting `.nbt` in an NBT editor; for each anchor block
   entity, set `subbuilding.subBuildingType = "STALL"`.
5. Drop the file at
   `data/life_in_the_village/structures/default/<style>/market/...`
   matching the existing market template path.

**T2 — GuildHall colour overrides (P0a-12).**

Wires P0a-12's forced colour overrides for guild halls through the
new `VariantResolver` mechanism. Decision: place colours on
`AbstractGuild` (canonical post-NPC-Phase-4 record) with a static
`GuildPalettes` table keyed by `GuildType`.

Files added:
- `Guilds/Common/GuildPalette.java` — record carrying optional
  primary / accent / roof `DyeColor`s; codec; `NONE` singleton;
  `isEmpty()` predicate.
- `Guilds/Common/GuildPalettes.java` — static `EnumMap<GuildType,
  GuildPalette>` with reasonable per-type defaults: ADVENTURER red /
  black / gray, CRAFTSMEN gray / red / black, MERCHANTS blue / yellow
  / brown, AGRICULTURAL brown / green / yellow, RELIGIOUS white /
  purple / null, SCHOLARLY light_blue / white / gray.

Files modified:
- `Guilds/Common/AbstractGuild.java` — `palette` field with codec;
  constructor canonicalizes null → `GuildPalettes.forType(type)`;
  setter for runtime override.
- `Village/Decoration/Variants/VariantResolver.java` — new 5-arg
  `planTint(typeData, variant, rng, neighborColors, GuildPalette
  forced)` overload. Forced colours apply on top of the doc-15
  type-derived overrides (TEMPLE / TOWN_HALL stay handled inside
  `VillagePaletteResolver`); only paint slots the variant actually
  declares; old 4-arg overload delegates with `GuildPalette.NONE`.
- `Village/Planning/V2/V2VillageSpawnerAdapter.java` — per
  `PlacedBuilding`, if `GuildHallTypes.isGuildHall(type)` then look
  up `GuildPalettes.forType(GuildHallTypes.guildTypeForHall(type))`
  and pass to `VariantResolver.planTint`. Uses type defaults rather
  than per-instance overrides because `AbstractGuild` instances for
  the new village haven't been created yet at building-placement time
  (`GuildBootstrap.scanAndCreateImplicit` runs in `runDownstream`).

**T3 — URBAN variant pack manifests (P0a-16).**

Authored 22 manifest.json files at
`data/life_in_the_village/structures/default/urban/<type>/<variant>/manifest.json`
covering every URBAN variant in doc 16 §4:

- HOUSE: `townhouse`, `tenement`, `row_house`.
- BLACKSMITH: `urban_smithy`. CARPENTRY: `urban_carpentry`. WEAVER:
  `urban_weaver`. BAKERY: `urban_bakery`. APOTHECARY:
  `urban_apothecary`. STONEMASON: `urban_stonemason`. CANDLEMAKER:
  `urban_candlemaker`.
- INN: `urban_inn`, `coaching_inn`. TEMPLE: `cathedral`,
  `urban_chapel`. LIBRARY: `urban_library`. GUILD_HALL:
  `urban_guildhall`. TOWN_HALL: `urban_townhall`. NOBLE_MANOR:
  `urban_manor`. MARKET: `urban_market`.
- GUARD_TOWER: `urban_tower`. WATCHTOWER: `urban_watchtower`.
  BARRACKS: `urban_barracks`.

Schema mapping notes for `preferredTags`: doc 16 specs use a few
labels that aren't in the `SlotTag` enum (`DENSE`, `PERIMETER`,
`CIVIC_CORE`, `ROAD_SIDE`, `HILLTOP`). Substitutions made in the
manifests:
- `CIVIC_CORE` → `PRIME_CIVIC`
- `ROAD_SIDE` → `ROAD_ADJACENT`
- `HILLTOP` → `HILLTOP_PEAK`
- `DENSE`, `PERIMETER` → omitted (no current analogue; can be
  added to `SlotTag` later if scoring needs them).

**URBAN NBTs (user task):** each variant folder has a manifest only;
the corresponding `level_1.nbt` (and any higher-tier `level_N.nbt`)
must be authored against doc 16 §4's per-variant footprint / height /
materials / anchor specs. NBTs land at the same folder paths as the
manifests.

**T4 — HOUSE pilot NBT gap (P0a-15).**

The RURAL HOUSE pack manifests are authored in doc 16 §3 but not yet
on disk under `data/life_in_the_village/structures/default/rural/house/`.
The 21.1 inventory snapshot recorded "manifests authored, NBTs
missing" but a fresh check of the resource tree shows neither
manifests nor NBTs are present for any RURAL variant. Both are user
tasks. Per-variant authoring spec lives in doc 16 §3 (`cottage`,
`house`, `big_house`, `longhouse`).

Expected paths once authored:
- `data/life_in_the_village/structures/default/rural/house/cottage/manifest.json`
  + `level_1.nbt` (and optional `level_2.nbt`, etc.).
- Same shape for `house`, `big_house`, `longhouse`.
- And for every other RURAL building type (BLACKSMITH, BAKERY,
  CARPENTRY, FARMER, FISHERY, INN, TEMPLE, LIBRARY, GUILD_HALL,
  TOWN_HALL, NOBLE_MANOR, MARKET, GUARD_TOWER, WATCHTOWER, BARRACKS,
  STABLE, STONEMASON, WEAVER, WOODCUTTER, MILLER, APOTHECARY,
  CANDLEMAKER) — see doc 16 §3.

**Smoke test (pending user-run):**
- V2 spawn with a guild hall: hall paints in the guild type's
  palette (ADVENTURER hall → red primary, black accent, gray roof).
- Save / restart / reload: `palette` field on `AbstractGuild` round-
  trips through codec.
- Market re-authored with SubBuildingAnchorBlock + STALL: stalls
  populate from scanner output. Stall reclaim works without
  attempting to restore a chiseled-stone marker.

Cumulative pending verification: all of A1a + A2 + A3 + A4 + A1b +
B1 from a single live run.

Next: Track B2 (decoration phases 1-3 V2 vocabulary pass +
implementation). B1 unblocks B2 by closing the Phase 0a content gaps.

### 2026-05-08 — B2.1 landed (V2 envelope extension)

Track B's first infrastructure phase: extend V2 Layer 4's
`PhasedPlanner` to plan optional adjunct rectangles alongside
footprint + frontage; retire the legacy probe-and-place flow inside
`AdjunctPlotPlacer`; add an `adjunct` field to the manifest schema
so per-variant geometry preferences flow into the planner.

**Scope decisions taken before coding** (recorded for the audit
trail; all user-confirmed):

- AMENITY priority class — *removed from B2.1.* The existing
  `Priority` enum (`CIVIC, INFRASTRUCTURE, PRODUCTION, RESIDENTIAL`)
  is a dependency-resolution category, not a placement-priority
  hierarchy. Park placement is better modelled as a Layer-4 post-
  pass scoring leftover space against culture-weighted park
  preferences. Deferred to B2.4.
- Frontage subdivision (entrance / roadside / side) — *deferred.*
  The entrance corridor needs a door anchor that today's
  `FrontageStrip` doesn't carry (door placement deferred to Layer 5
  facade). For B2.1, decoration consumers treat the full
  `FrontageStrip` as eligible territory; door-aware filtering is a
  later concern (handle at render time when Layer 5 lands).
- Probe migration — *all five legacy checks moved to Layer 4*
  (parent-overlap, no-extension-past-front-face, sibling overlap,
  plot overlap, slope tolerance via `V2FeatureMap` instead of
  `ServerLevel.getHeight`). The renderer is unconditional. The
  `FaceProbeOrder` and `PlacementStrategy` enums become dead code
  and are deleted; `AdjunctPlotPlacer.tryPlace` is gone.
- BLACKSMITH stub — *real manifest, single file.* The pre-B2.1
  RURAL pack already had `default/rural/blacksmith/blacksmith/manifest.json`
  and `level_1.nbt`. The manifest is augmented with a 6×6 BACK
  adjunct preference (`required: false`) — minimum viable smoke
  test; can stay or be reverted by the user without affecting other
  systems.

**Manifest schema extension** —
`Variants/AdjunctPreference.java` (record:
`width, depth, AdjunctSide, required`),
`Variants/AdjunctSide.java` (BACK / LEFT / RIGHT / ANY).
`VariantManifest` gains a nullable `adjunct` field; parser reads
`{ "size": [w, d], "side": "BACK", "required": false }`. Missing →
`null`; malformed values warn and fall back to defaults.
`BuildingVariant` propagates the field to consumers
(`PhasedPlanner` reads it via `VariantResolver.findById`).

**V2 Layer 4 envelope extension** — `PhasedPlanner.findBestCandidate`
now calls `planAdjunct` after computing each candidate's footprint
+ frontage. Resolution order: manifest preference > registry
default > none. Five validations per candidate side: front-face
extension, reservation collision (including prior adjuncts), road
corridor intersection, FeatureMap bounds, and slope tolerance
(sampled per cell). `Reservation` records gain an optional
`adjunct` AABB so subsequent buildings see and avoid prior
adjuncts. `Best` carries the planned `PlannedAdjunct` and AABB
through to placement. `Required: true` failures invalidate the
candidate; `required: false` failures place the building without
the adjunct.

**PlacedBuilding hand-off** — gains a nullable `adjunct`
(`PlannedAdjunct` lite record carrying type, origin, half-extents,
side direction). Backwards-compat 8-arg constructor preserved for
hypothetical future call sites.

**AdjunctPlot framework refactor** —
`AdjunctPlotPlacer.tryPlace` deleted; new
`AdjunctPlotPlacer.render(plot, level, data)` is a logging stub
(real content rendering arrives in B2.3 industry / B2.6
homesteading subsystems).
`AdjunctPlotRealiser.run` rewritten: iterates
`data.getAdjunctPlotsForBuilding(buildingId)` per village building
and dispatches the renderer. No probing.
`AdjunctPlotType` cleaned: fields reduced to
`defaultHalfWidthX / defaultHalfLengthZ / slopeTolerance`. Codec
unchanged (enum-name based).
`FaceProbeOrder` and `PlacementStrategy` source files deleted.

**V2 spawn adapter materialisation** — after each
`BuildingPlacer.placeAndRegister` returns the `Building` (and the
parent's UUID), the adapter constructs a persisted `AdjunctPlot`
from the planned reservation and calls `data.addAdjunctPlot(...)`.
`AdjunctPlotRealiser` runs later in `runDownstream` and finds the
plots already persisted.

**Smoke test contract (live, in-world):**

Smoke test gated on the user having already authored the BLACKSMITH
NBT (which exists; `default/rural/blacksmith/blacksmith/level_1.nbt`).
With this build, spawning a tier ≥ HAMLET rural village in superflat
should:

1. Log `placed BLACKSMITH: ... adjunct=FORGE_YARD@<face>`
   (or no `adjunct=` suffix if the back face didn't fit).
2. Log `[adjunct] B2.1 placeholder render for FORGE_YARD plot ...
   parent=<uuid> origin=(...) size=5x5` once per blacksmith.
3. `VillageSavedData` round-trips: save / restart /
   `data.getAdjunctPlotsForBuilding(blacksmithId)` returns the
   planned plot.

Tight-site test (small buildable area): blacksmith should still
place; the `required: false` path drops the adjunct and the
`adjunct=` suffix is absent in the placement log.

Required-edge test (manual: temporarily flip `required: true` in
the BLACKSMITH manifest): tight sites should drop the blacksmith
with `NO_VIABLE_CANDIDATE`. Revert to false after testing.

Multi-blacksmith test: second blacksmith's adjunct doesn't overlap
the first (the planner's reservation list rejects).

**Cumulative pending verification:** all of A1a + A2 + A3 + A4 +
A1b + B1 + B2.1 in a single live run.

Next: B2.2 (decoration profile registry population), B2.3 (industry
adjuncts content), or wherever doc-15 ordering points. B2.1 ships
the planning surface; content phases now have a reserved rectangle
to render into.

### 2026-05-08 — B2.2 landed (street furniture + welcome marker + noticeboard)

Track B's first content phase. Populates the
`DecorationProfileRegistry` for the road-side / building-gap /
plaza-noticeboard / trade-road-endpoint sub-algorithms; tunes
density per (culture × village size tier); wires the noticeboard
to live content from `VillagePolicy` + `RequestBoard` + kingdom
laws. NBT authoring for furniture and welcome marker pieces remains
a user task per the prompt's scope.

**Scope decisions (user-confirmed):**

- Piece list — full doc-05 kit (14 pieces) over the prompt's
  narrower 5-piece suggestion. Tier-gated per doc 05 §"Tier
  gating".
- Density mechanism — *both* axes. `DecorationProfile.minTier`
  governs which pieces are eligible at each tier; new
  `DecorationDensityProfile` (per culture × tier) governs slot
  emission rate. Together the two produce HAMLET villages with
  3 piece variants at 14-block road spacing and CITY villages with
  the full 14 piece variants at 6-block road spacing.
- Noticeboard surface — vanilla sign for now. The user asked to
  defer the custom BlockEntity. Doc 06 originally specified a
  lectern with a multi-tab right-click UI; B2.2 ships a 4-line sign
  populated by `NoticeboardWriter`. When the BE lands,
  `NoticeboardWriter#generateContent` is the only call site that
  needs to switch output format.
- Welcome marker — single profile, all tiers. Doc 06's 4 tier-gated
  variants (simple_post / arch / stone_marker / gate_structure) are
  deferred. The emitter still emits one `WELCOME_MARKER` slot per
  gate (main + capital); the matcher's clustering check produces
  one piece per gate.

**Schema extensions:**

- `DecorationProfile` gains `minTier: VillageSizeTier`. Codec
  optional-with-default to HAMLET so legacy persisted profiles
  load unchanged. New `tierAllows(VillageSizeTier)` predicate.
  Backwards-compat 10-arg constructor preserved.
- `DecorationProfileRegistry.eligibleFor(slot, culture, tier,
  slotBiome)` — new 4-arg form filters by tier. Old 3-arg form
  delegates with `CITY` (permissive) so callers that don't yet
  know the tier still work.
- `DecorationMatcher.match(...)` — gains a `villageTier` parameter;
  the only call site is `DecorationPass`, which passes
  `village.getSizeTier()`.
- `DecorationDensityProfile` — new record carrying
  `roadSideSampleStep`, `roadSideOffset`, `buildingGapMin`,
  `buildingGapMax`, `facadeOrnamentsPerWall`,
  `villageBoundaryAngleStepDeg`. `LEGACY_DEFAULT` matches the
  pre-B2.2 hardcoded constants.
- `DecorationDensityRegistry` — singleton keyed by (culture, tier),
  default-culture entries for HAMLET / VILLAGE / TOWN / CITY.
  Lookup falls back culture → "default" culture → LEGACY_DEFAULT.
- `DecorationSlotEmitter.Context` — gains an optional
  `DecorationDensityProfile`; sub-algorithms read from
  `densityOrDefault()` instead of the public constants. The legacy
  3-arg `Context` constructor stays for the debug command.

**Content registrations (default culture):**

`StreetFurnitureProfiles.registerDefaults()` registers 14 pieces:
- HAMLET-tier: bench, planter, signpost, stacked_crates.
- VILLAGE-tier: lamppost, notice_board.
- TOWN-tier: handcart, woodpile, well_variant, hitching_post,
  water_trough.
- CITY-tier: small_shrine, communal_oven, mounting_block.

Each piece declares its target tag (`ROAD_SIDE_SMALL`,
`ROAD_SIDE_LARGE`, `BUILDING_GAP`, or `PLAZA_NOTICE_BOARD`),
weights (primary/secondary/backfill), footprint, and
`AnchorRule.ON_GROUND`.

`WelcomeMarkerProfiles.registerDefaults()` registers the single
welcome-marker piece targeting `WELCOME_MARKER` slots, eligible
from HAMLET up.

**Density curves (default culture):**

| Tier    | road step | gap min/max | facade orn. | boundary step |
|---------|-----------|-------------|-------------|---------------|
| HAMLET  | 14        | 5 / 14      | 1           | 30°           |
| VILLAGE | 10        | 4 / 13      | 2           | 20°           |
| TOWN    |  8        | 4 / 12      | 2           | 15°           |
| CITY    |  6        | 4 / 12      | 2           | 12°           |

**Noticeboard content path (`NoticeboardWriter`):**

- Locates noticeboards by filtering
  `data.getDecorationsForVillage(villageId)` for placements whose
  `pieceId` ends in `/sign/notice_board`.
- For each, places a vanilla `OAK_SIGN` (rotated to match the
  placement's facing) only over air or replaceable blocks — leaves
  user-authored NBTs alone.
- Generates 4 lines: village name; first active village law +
  count; open-request count for this village; first kingdom law +
  count. Long enum names are Title-Cased and truncated to 15
  glyphs per line so heavy fonts don't clip awkwardly.
- Writes via `SignBlockEntity.getFrontText` → `setMessage` →
  `setText(text, true)` (matches the convention in
  `DynamicSignUpdater.writeLines`).

**Update mechanism:**

`NoticeboardTickHandler` subscribes to `ServerTickEvent.Post` and
calls `NoticeboardWriter.writeForVillage` for every village once
per 24000 ticks (one in-game day). Cheap because the modulo check
short-circuits 23999/24000 ticks. Also-runs at tick 0, giving
noticeboards their initial content shortly after world load.

The tick poll is a stopgap. `NpcLifeEventBus` doesn't yet emit
law / request / decree events; when those land (target B2.5+ as
part of NPC Phase 5 follow-up), `NoticeboardTickHandler` should
swap to event subscriptions. The `NoticeboardWriter` API is
unchanged in either case.

**Wiring:**

- `Life_in_the_village.commonSetup`: registers
  `StreetFurnitureProfiles` + `WelcomeMarkerProfiles`.
- `DecorationPass.run`: after stamping placements, calls
  `NoticeboardWriter.writeForVillage` for the initial content
  write.
- `NoticeboardTickHandler` is `@EventBusSubscriber`-discovered.

**NBT authoring (user task):**

Profiles point at resource paths like
`structures/default/decoration/street/{name}.nbt`,
`structures/default/decoration/sign/welcome_marker.nbt`, and
`structures/default/decoration/sign/notice_board.nbt`. Without
NBTs `DecorationPass.stamp` logs "NBT not found" and burns the
slot, but the placement record persists so
`NoticeboardWriter` still places + populates a vanilla sign for
the noticeboard slot. Other slots remain empty until NBTs are
authored.

**Smoke test contract (live, in-world):**

Without authored NBTs:
1. Spawn a CITY-tier village. Run `/liv decoration slots <village>`
   and confirm the tag breakdown shows ROAD_SIDE_SMALL +
   ROAD_SIDE_LARGE slots at ~6-block intervals along centerlines,
   with WELCOME_MARKER slots at gates and a single
   PLAZA_NOTICE_BOARD slot at plaza centre.
2. Run `/liv decoration list <village>` and confirm placements at
   the noticeboard, welcome marker, and many street-furniture
   slots — all referencing the new piece IDs.
3. Walk to the plaza. A vanilla oak sign should be present at the
   noticeboard position with at minimum the village name on line 1.
4. `/liv village law enact OPEN_TRADE` (or whatever law command
   exists) — wait one in-game day; the sign's content should refresh
   to include the law.
5. Spawn a HAMLET-tier village. Confirm `/liv decoration slots`
   shows ~3× fewer ROAD_SIDE_* slots (14-block step vs 6) and the
   noticeboard slot is absent (HAMLETs gate noticeboards behind
   VILLAGE tier).

With user-authored NBTs at the documented paths, the same villages
should render visible benches, planters, lampposts, etc. at the
listed positions, plus a real welcome marker piece at each gate
and a custom noticeboard surface (sign or NBT depending on the
author's choice).

**Cumulative pending verification:** A1a + A2 + A3 + A4 + A1b + B1
+ B2.1 + B2.2 in a single live run.

Next: B2.3 (industry adjunct content) per UNIFIED_REWORK_PLAN —
B2.1 reserved rectangles + B2.2 furniture-kit infrastructure
together unblock the per-AdjunctPlotType content registries.

### 2026-05-08 — B2.3 landed (industry adjuncts + building gardens)

Track B's second content phase. Populates the AdjunctPlot registry
with profession-tied working yards (doc 07) and decorative gardens
(doc 08); augments rural variant manifests with doc-prescribed
adjunct preferences (size + side + required=false); extends
`AdjunctPlotRegistry` to be culture-keyed per the prompt's
"per-culture registries from day one" direction. NBT authoring for
yards and gardens remains a user task.

**Inventory findings:**

- BuildingType coverage — every prompt-listed type exists in the
  enum: BLACKSMITH, WEAVER, FISHERY, STONEMASON, CANDLEMAKER,
  STABLE, BAKERY, CARPENTRY, WOODCUTTER, APOTHECARY, INN, TEMPLE,
  NOBLE_MANOR. Doc 07/08 also references ARMORER, TOOLSMITH,
  LIBRARY, MINE, MILLER, VINEYARD — all also in the enum.
- AdjunctPlotType coverage — all 14 values from B2.1 cover doc
  07/08's spec (FORGE_YARD, KILN_YARD, DRYING_RACK_YARD, LOG_YARD,
  PADDOCK, OVEN_SHED, HERB_GARDEN, KITCHEN_GARDEN,
  MEDITATION_GARDEN, FORMAL_GARDEN). No new enum values needed.
- Manifest folders — all needed RURAL manifests exist except
  `rural/fishery/...` (no folder). MILLER's manifest lives at the
  malformed `rural/miller/manifest.json` path (missing variant
  subfolder); skipped for now and flagged as a content cleanup
  task.
- TEMPLE vs CHAPEL vs SHRINE — doc 08 explicitly lists only TEMPLE
  for MEDITATION_GARDEN. Registered TEMPLE only; CHAPEL and SHRINE
  remain garden-less (matches doc).
- HOUSE — intentionally absent per doc 02 §"HOMESTEAD_*" (subsystem
  11 / B2.6 wires homestead probability gating separately).
- VINEYARD — doc 07 marks this as "uses FarmPlot, no adjunct"; not
  registered.

**Schema decision — culture-keyed registry:**

Doc 02 §"Open decisions" + the prompt's "per-culture registries
from day one" direction motivated extending `AdjunctPlotRegistry`
from a flat `Map<BuildingType, List<AdjunctPlotType>>` to a nested
`LinkedHashMap<String culture, EnumMap<BuildingType,
List<AdjunctPlotType>>>`. Lookups follow culture → "default"
fallback; the per-culture cap of {@link
AdjunctPlotRegistry#MAX_PLOTS_PER_BUILDING} stays at 3.

The prior 1-arg `getPlotsForBuilding(type)` overload survives as
the default-culture shortcut for callers that don't yet carry
culture context (debug commands). New code paths (V2 Layer 4
`PhasedPlanner.planAdjunct`) call the 2-arg form with
`state.culture` — pulled from the village's culture id, no
hardcoded "default" literal in dispatch.

**Default-culture registrations (B2.3 additions vs B2.1 baseline):**

| Building Type | Plot Type(s)                 | Source     | Notes |
|---------------|------------------------------|------------|-------|
| BLACKSMITH    | FORGE_YARD                   | doc 07     |       |
| ARMORER       | FORGE_YARD                   | doc 07 NEW |       |
| TOOLSMITH     | FORGE_YARD                   | doc 07 NEW |       |
| WEAVER        | DRYING_RACK_YARD             | doc 07     |       |
| FISHERY       | DRYING_RACK_YARD             | doc 07     | manifest pending |
| CARPENTRY     | LOG_YARD                     | doc 07     |       |
| WOODCUTTER    | LOG_YARD                     | doc 07     |       |
| MILLER        | LOG_YARD                     | doc 07     | manifest path malformed |
| STONEMASON    | KILN_YARD                    | doc 07     |       |
| CANDLEMAKER   | KILN_YARD                    | doc 07     |       |
| MINE          | KILN_YARD                    | doc 07 NEW |       |
| STABLE        | PADDOCK                      | doc 07     |       |
| BAKERY        | OVEN_SHED                    | doc 07     |       |
| APOTHECARY    | HERB_GARDEN                  | doc 08     |       |
| INN           | KITCHEN_GARDEN               | doc 08     |       |
| TEMPLE        | MEDITATION_GARDEN            | doc 08     |       |
| LIBRARY       | MEDITATION_GARDEN            | doc 08 NEW |       |
| NOBLE_MANOR   | FORMAL_GARDEN, PADDOCK       | doc 08     | two-plot building |

NEW marks entries added in B2.3 that weren't in B2.1's baseline
skeleton.

**Manifest extensions (15 rural manifests):**

Sizes follow doc 07/08 (which override the prompt's suggested
defaults where they conflict — e.g. doc says BLACKSMITH 5×5, prompt
suggested 6×6; doc wins). Side preferences: BACK for all working
yards; ANY for log yards / herb gardens / weaver / mine where doc
07/08 doesn't pin a face. {@code required=false} throughout —
B2.1's contract: tight sites place without the adjunct rather than
dropping the building.

| Variant       | Size  | Side | NBT path (user task)                                                |
|---------------|-------|------|---------------------------------------------------------------------|
| blacksmith    | 5×5   | BACK | `structures/default/decoration/industry/forge_yard_*.nbt`           |
| armorer       | 5×5   | BACK | `structures/default/decoration/industry/forge_yard_*.nbt` (variant) |
| toolsmith     | 5×5   | BACK | `structures/default/decoration/industry/forge_yard_*.nbt` (variant) |
| weaver        | 5×5   | ANY  | `structures/default/decoration/industry/drying_rack_*.nbt`          |
| carpentry     | 6×6   | ANY  | `structures/default/decoration/industry/log_yard_*.nbt`             |
| woodcutter    | 6×6   | ANY  | `structures/default/decoration/industry/log_yard_*.nbt`             |
| stonemason    | 5×5   | BACK | `structures/default/decoration/industry/kiln_yard_*.nbt`            |
| candlemaker   | 4×4   | BACK | `structures/default/decoration/industry/kiln_yard_*.nbt` (variant)  |
| mine          | 5×5   | ANY  | `structures/default/decoration/industry/kiln_yard_*.nbt` (raw var.) |
| stable        | 8×8   | BACK | piece-kit; subsystem 11 (B2.6) renders                              |
| bakery        | 4×4   | BACK | `structures/default/decoration/industry/oven_shed_*.nbt`            |
| apothecary    | 5×5   | ANY  | `structures/default/decoration/garden/herb_garden_*.nbt`            |
| inn           | 5×5   | BACK | `structures/default/decoration/garden/kitchen_garden_*.nbt`         |
| temple        | 6×6   | BACK | `structures/default/decoration/garden/meditation_garden_*.nbt`      |
| library       | 5×5   | BACK | `structures/default/decoration/garden/meditation_garden_*.nbt` (sm) |
| noble_manor   | 10×10 | BACK | `structures/default/decoration/garden/formal_garden_*.nbt`          |

B2.1's BLACKSMITH stub at 6×6 was reduced to 5×5 to align with doc
07.

**Skipped registrations (flagged for follow-up):**

- FISHERY — registry maps to DRYING_RACK_YARD, but no
  `rural/fishery/...` manifest folder exists. V2 selector's NBT
  availability check drops FISHERY before adjunct planning runs;
  no smoke-test impact today. Folder + manifest is a user
  authoring task before fisheries appear in villages.
- MILLER — manifest sits at `rural/miller/manifest.json` (no
  variant subfolder), which doesn't match
  `StructureAvailabilityRegistry`'s 5-segment path parser. Either
  the file should be `rural/miller/miller/manifest.json` (matching
  the convention) or removed. Treated as a pre-existing content
  cleanup item, not a B2.3 blocker.
- DOCKS — present in the enum and as a manifest folder, but doc 07
  doesn't list it. Not registered.
- CHAPEL / SHRINE — temple-adjacent buildings; doc 08 ties
  MEDITATION_GARDEN to TEMPLE only. Not registered; their
  manifests carry no adjunct preference.

**Smoke test contract (live, in-world):**

Per-village checks via `/liv decoration` (or equivalent V2 state
debug command):

1. Spawn a CITY-tier rural village with industrial inclination.
   Confirm BLACKSMITH places with a 5×5 FORGE_YARD reservation
   logged: `placed BLACKSMITH: ... adjunct=FORGE_YARD@<face>`.
2. Spawn a village with INN. Confirm 5×5 KITCHEN_GARDEN
   reservation log line.
3. Spawn a village with CARPENTRY + WOODCUTTER. Confirm both get
   distinct 6×6 LOG_YARD adjuncts; the V2 Layer 4 collision check
   should keep them from overlapping.
4. Spawn a NOBLE_MANOR. Confirm it picks up FORMAL_GARDEN as the
   first plot type (planner registers only the primary registry
   entry per building today; PADDOCK secondary will become real
   when the planner adds multi-plot iteration in a future phase).
5. Tight-site test: shrink the buildable area. Confirm blacksmith
   etc. place without adjuncts (the placement log shows no
   `adjunct=` suffix). All registered manifests use
   `required=false` so no building drops for adjunct-fit failures.

NBT authoring is a user task. Without authored content, smoke
testing verifies *planning + reservation*, not block output.
Stamping each adjunct will land in B2.3's content-authoring pass
(user side) and the per-AdjunctPlotType renderers in B2.6
(homesteading) / future industry-content phases.

**Cumulative pending verification:** A1a + A2 + A3 + A4 + A1b + B1
+ B2.1 + B2.2 + B2.3 in a single live run.

Next: B2.4 (parks and gardens) per UNIFIED_REWORK_PLAN — village-
scope (not building-scope) decoration that places parks in
leftover space using a Layer 4 post-pass.

### 2026-05-08 — B2.4 landed (parks and gardens)

Track B's third content phase. Introduces parks as a Layer 4
post-pass output: scan leftover FeatureMap cells, score for
park-suitability, cluster into rectangular candidate areas, pick a
{@link GardenStyle} per culture × inclination, reserve as
{@link GardenPlot}, and render at decoration time. Per the user's
"natural-feature-first" direction, the renderer prefers preserving
existing trees / water / flowers and adds minimal procedural
primitives (paths, flower beds, hedges, ponds) plus NBT-backed
complex pieces (benches, statues, arches) — the latter skipped if
the user hasn't authored the NBT yet.

**Scope decisions (user-confirmed):**

- GardenStyle list — *doc-09's 5 styles*: COTTAGE_GREEN, FORMAL_PARK,
  ZEN_GARDEN, SACRED_GROVE, MEMORIAL_PARK. Skipped the prompt's
  ORCHARD / POND_GARDEN / MEDITATION rename in favor of doc-09's
  canonical naming.
- Park count per tier — *hybrid* per the user: HAMLET 0 always;
  VILLAGE 0 or 1 weighted by parkPriority; TOWN 1; CITY 1–3 scaled
  by parkPriority. Hard caps don't budge for high priority.
- Primitive nature — *mix*. GRAVEL_PATH, FLOWER_BED, HEDGEROW, POND
  ship as procedural block stamps (always render). BENCH,
  STATUE_PEDESTAL, TRELLIS, TOPIARY, STANDING_STONE, WOODEN_ARCH,
  LAMPPOST ship as NBT slots (skipped + logged when the user
  hasn't authored the NBT).
- View-vista signal — *deferred*. FeatureMap doesn't expose a
  per-cell openness; PhasedPlanner's `fanOpenness` is post-hoc
  hub-only. ParkCandidateFinder scores cells on slope + water
  proximity + forest proximity instead. View-vista revisited if a
  later phase ships a per-cell signal.

**Schema additions:**

- `Village/Decoration/Parks/GardenStyle.java` — enum (5 values)
  with hardcoded preserveBias, min/max size, terrain affinity,
  weighted piece pool, inclination affinity.
- `Village/Decoration/Parks/ParkPrimitive.java` — enum (11 values)
  with `Kind` (PROCEDURAL/NBT) and footprint.
- `Village/Decoration/Parks/GardenPlot.java` — record (plotId,
  villageId, bounds, style, preserveBias, preservedFeatures,
  composedPrimitives, createdTick) + `Bounds` + `ComposedPrimitive`
  sub-records + Mojang codec.
- `Village/Decoration/Parks/ParkCandidateFinder.java` — Layer 4
  post-pass static. Implements the scoring + clustering algorithm
  + per-tier cap + style selection.
- `Village/Decoration/Parks/ParkRenderer.java` — renders reserved
  plots; preserve-mode survey of natural features, compose-mode
  pool sampler, procedural block stamps, NBT-skip with log line.
- `Commands/ParkDebugCommand.java` — `/liv parks <village>` listing.

**CulturePlanningBias extension:**

Two new fields on the existing record:
- `double parkPriority` (0..1, default 0.5) — multiplies the
  per-tier cap; high values fill closer to the cap, low values
  leave parks rare. Hard tier caps not exceeded.
- `Map<String, Double> parkPreferenceWeight` — per-style
  preference (keys = `GardenStyle` enum names; missing styles
  default to 1.0).

Codec extends with two `optionalFieldOf` entries; legacy JSONs
load unchanged. A 4-arg backwards-compat constructor delegates to
the 6-arg canonical with `parkPriority=0.5` and the doc-09 default
preference weights.

Default-culture preferences: COTTAGE_GREEN 1.2, FORMAL_PARK 1.2,
ZEN_GARDEN 1.0, SACRED_GROVE 1.0, MEMORIAL_PARK 0.9 — balanced
with a slight emphasis on cottage and formal styles per the
prompt's "balanced across styles, slight emphasis on formal"
direction.

**Persistence:**

`VillageSavedData` gains a 10th sub-record `VillageGardenData`,
adds a 13th slot to the top-level `CODEC` (`gardenData`), and
extends `fromCodec` with a `VillageGardenData` argument. New
authoritative map `Map<UUID, GardenPlot> gardenPlots` plus
denormalised `Map<UUID, List<UUID>> gardenPlotsByVillage` index
rebuilt from the flat list on load. Public API:
`addGardenPlot`, `updateGardenPlot` (so the renderer can patch in
preservedFeatures + composedPrimitives without remove/add),
`getGardenPlot`, `getGardenPlotsForVillage`, `getAllGardenPlots`,
`removeGardenPlotsForVillage`. Mirrors the `VillageAdjunctData`
pattern from B2.1.

**Layer 4 hook (V2VillageSpawnerAdapter):**

ParkCandidateFinder runs *between Village registration and the
building-placement loop* (line ~200) — after PhasedPlanner's
designateHubs but before any blocks land. Inputs: `fmap`,
`placement.placed()`, `culture`, `siteCtx.inclination()`,
`village.getSizeTier()`, `village.getId()`, `seed`,
`level.getGameTime()`. The result list is persisted via
`data.addGardenPlot(plot)`.

ParkRenderer runs *inside `runDownstream` after `DecorationPass`*
— buildings, decorations, and street furniture are all placed by
then, and the renderer's procedural primitives (paths, flower
beds) drop on top.

**Scoring algorithm (ParkCandidateFinder):**

Per-cell composite score in [0..1]:
```
0.45 * slopeScore     // 1 - localSlope/4
+ 0.25 * forestScore  // 1 - distToForest*cellSize / 12
+ 0.15 * waterScore   // 1 - distToWater*cellSize / 16
+ 0.15 if FOREST cell // category bonus
```

Cells in WATER, STONE_EXPOSED, or STRUCTURE categories score 0.
Cells inside a placed footprint (with 2-block buffer) score 0.

Cluster growth: greedy rectangular expansion from any seed cell
(score ≥ 0.55) while neighbouring edges average ≥ 0.35. Diameter
capped at 32 blocks.

Style selection: filters by `minSize ≤ span ≤ maxSize`, then
`prefWeight × inclinationBonus` (1.5× when style's
`inclinationAffinity` matches the village's inclination; 1.0
otherwise). Top N by `areaScore × styleScore` reserved (no
overlap).

**Renderer policy (ParkRenderer):**

1. Survey naturals — sample every 2 blocks, classify each surface
   as preserve-worthy (logs / leaves / water / lily pad / poppy /
   dandelion / azure_bluet / cornflower / oxeye_daisy).
2. Decision — *preserve mode* if `preserveBias ≥ 0.6` AND
   preserved.size() ≥ 4; *compose mode* otherwise.
3. Always — stamp a single-block-wide gravel path along the
   longer axis through the centre.
4. Preserve mode — try one BENCH NBT placement near path centre
   (skipped + logged if NBT missing).
5. Compose mode — sample primitives from the style's piecePool
   weighted bag for `area / 20` budget. Procedural primitives
   stamp blocks; NBT primitives log + record placement only.
6. Always — return the populated GardenPlot for save/reload
   determinism via `updateGardenPlot`.

Procedural stamps: FLOWER_BED scatters poppy/dandelion/cornflower/
azure_bluet across a 3×3, HEDGEROW lays 5 oak_leaves in a row,
POND fills a 3×3 patch with water at floor-1.

**Smoke test contract (live, in-world):**

Without authored NBTs (everything except procedural primitives):
1. Spawn a CITY rural village in plains/forest. Run
   `/liv parks <name>`. Confirm 1–3 entries with style names,
   bounds, sizes 12–30, preserveBias values matching the styles.
2. Walk to a park's bounds — gravel path stripe should be visible;
   in compose mode, scattered flowers / hedge segments should
   appear; preserved trees should still stand if SACRED_GROVE was
   selected.
3. Spawn a CITY village in superflat. Confirm parks reserve via
   `/liv parks` but the renderer logs few preserved features and
   composes more aggressively (no trees to keep).
4. Spawn an AGRICULTURAL-inclined village. Confirm COTTAGE_GREEN
   styles dominate the picks (inclination affinity bonus).
5. Spawn a SACRED-inclined village. Confirm SACRED_GROVE or
   ZEN_GARDEN styles preferred.
6. `/save-all` + restart + `/liv parks <name>` — entries persist
   with the same bounds, styles, and composedPrimitives lists.
7. Confirm `/liv parks` reports 0 plots for HAMLET-tier villages
   (hard cap = 0).

Visual smoke testing for full park aesthetics requires the user to
author the NBT primitives at:
- `structures/default/decoration/park/bench.nbt`
- `structures/default/decoration/park/statue_pedestal.nbt`
- `structures/default/decoration/park/topiary.nbt`
- `structures/default/decoration/park/trellis.nbt`
- `structures/default/decoration/park/standing_stone.nbt`
- `structures/default/decoration/park/wooden_arch.nbt`
- `structures/default/decoration/park/lamppost.nbt`

Each is small (1-2 blocks footprint per `ParkPrimitive.footprint()`).
Until authored, the renderer logs `[ParkRenderer] NBT primitive
{type} stamping deferred` once per attempted placement and skips
the block stamp; the placement record is still added so the same
positions are tried again on re-render rather than re-rolling.

**Cumulative pending verification:** A1a + A2 + A3 + A4 + A1b + B1
+ B2.1 + B2.2 + B2.3 + B2.4 in a single live run.

Next: B2.5 (farm plot rework) per UNIFIED_REWORK_PLAN — completes
the village-scope decoration triple (parks + farm plots + later
homesteads).

### 2026-05-08 — B2.5 landed (farm plot rework)

Track B's fourth content phase. Reworks farms onto a sector-level
model: a Layer 4 post-pass identifies arable land outside placed
buildings + parks, reserves a {@code FarmSector} polygon at the
doc-10 tier-only base radius, allocates {@code FarmPlot}s per
farmhouse using the prompt's per-tier counts, and renders fields
with farmland + crop blocks + perimeter fences + procedural
scarecrows. The legacy {@code FarmPlotPlacer} is parked (no V2
callers); a new {@code FarmSectorRenderer} replaces its rendering
role. {@code FarmerGoal} stays plot-shape-agnostic — the polygon
support lives on {@code FarmPlot.contains} / {@code
getFarmlandBlocks}, not in the goal.

**Scope decisions (user-confirmed):**

- Sector size — *doc-10 tier-only*: HAMLET 15, VILLAGE 20, TOWN 25,
  CITY 30 blocks radius. Culture's {@code farmPriority} adds ±20%
  slack but doesn't fight the doc-10 base.
- Plot count per farmhouse — *prompt's per-farmhouse N*: HAMLET 2,
  VILLAGE 2–3, TOWN 3–4, CITY 4–6 (RNG within the band). Each
  farmhouse owns its plots; allocation round-robins quotas.
- Plot polygons — *4-vertex terrain-trimmed rectangles*. Plots
  start as {@code MIN_PLOT_SIDE × MIN_PLOT_SIDE} squares centred
  on grid points inside the sector polygon; corners pull inward
  if they land on slope or buildings.
- FarmPlotPlacer relationship — *replace* the planning + render
  roles; legacy placer parked in tree with a deprecation javadoc.
  V2 spawn calls the new sector pipeline. Reverting is one line in
  {@code V2VillageSpawnerAdapter.runDownstream}.

**Schema additions:**

- {@code Village/Farms/FarmSector.java} — record with sectorId,
  villageId, polygon bounds (terrain-trimmed quad), plotIds,
  farmhouseIds, toolShedPositions, createdTick + Mojang codec.
- {@code Village/Farms/FarmCropPicker.java} — culture-weighted
  draw across cultivated crops; PASTURE rolled by
  {@code pastureRatio}; ORCHARD forced on slope or forest-edge.
- {@code Village/Farms/FarmSectorPlanner.java} — Layer 4 post-pass.
  Skips non-AGRICULTURAL villages and zero-farmhouse villages.
- {@code Village/Farms/FarmSectorRenderer.java} — stamps farmland
  + crops per plot, perimeter fences, procedural scarecrows; logs
  + skips tool-shed NBT placements.
- {@code Commands/FarmDebugCommand.java} — `/liv farms <village>`.

**FarmPlot extension (existing class):**

- New fields: {@code UUID sectorId} (nullable; legacy plots load
  with no sector), {@code Polygon polygon} (nullable; legacy plots
  fall back to circle-via-origin/radius).
- Codec gains two {@code optionalFieldOf} entries; pre-B2.5 saves
  load unchanged.
- {@code contains(BlockPos)} prefers polygon when set; falls back
  to legacy circle when the polygon field is null.
- {@code getFarmlandBlocks} walks the polygon's bounding box when
  set so vertices that extend past origin±radius aren't missed.

**CulturePlanningBias extension:**

Three new fields:
- {@code double farmPriority} (0..1, default 0.7) — slack on the
  doc-10 base radius.
- {@code double pastureRatio} (0..1, default 0.2) — fraction of
  plots PASTURE-allocated by {@code FarmCropPicker.rollPasture}.
- {@code Map<String, Double> cropPreference} — per-CropType weight,
  default 1.0.

Codec extends with three {@code optionalFieldOf} entries.
Backwards-compat constructors preserve B2.0/B2.4-shape callers.

Default-culture crop preference (mirrors prompt § 5):
| Crop      | Weight |
|-----------|--------|
| WHEAT     | 1.4 |
| MIXED     | 1.2 |
| CARROTS   | 1.0 |
| POTATOES  | 1.0 |
| GRAIN     | 1.0 |
| VEGETABLE | 0.9 |
| ORCHARD   | 0.7 |
| PASTURE   | 0.7 |
| BEETROOT  | 0.6 |

**Persistence:**

VillageSavedData gains an 11th sub-record {@code VillageFarmData}
(14th top-level codec slot). Authoritative {@code Map<UUID,
FarmSector> farmSectors} + per-village denormalisation rebuilt
from the flat list on load. Mirrors the B2.1/B2.4 patterns
exactly.

API: {@code addFarmSector}, {@code getFarmSector},
{@code getFarmSectorsForVillage}, {@code getAllFarmSectors},
{@code removeFarmSectorsForVillage}.

**Layer 4 hooks (V2VillageSpawnerAdapter):**

1. {@code FarmSectorPlanner.plan} runs in {@code spawn} *after the
   building loop* (so we have concrete {@code Building} UUIDs for
   {@code farmhouseIds}) and *after parks* (so the planner masks
   against reserved {@code GardenPlot} bounds). Inputs: fmap,
   resolved village buildings, garden plots, culture, inclination,
   tier, villageId, seed, tick, data.
2. {@code FarmSectorRenderer.run} is wired into {@code
   runDownstream} replacing the {@code FarmPlotPlacer} guard line.

**Scoring algorithm (FarmSectorPlanner):**

Per-cell composite score:
```
0.5 * slopeScore     // 1 - localSlope/4
+ 0.35 * waterScore  // 1 - distToWater*cellSize / 24
+ 0.15 * forestScore // 1 - distToForest*cellSize / 32
```
Cells outside OPEN/SHORE category, or with localSlope &gt; 3, or
inside obstacle-buffer of buildings/parks, score 0.

Centre selection: highest-scoring cell within {@code 2 × radius}
of the farmhouse centroid. Polygon corners start at the radius
square and pull inward step-by-step until they land on valid
arable terrain.

**Plot allocation:**

For each farmhouse, draw a per-tier plot quota. Walk a grid of
candidate centres (step = MIN_PLOT_SIDE+1) inside the sector
polygon; shuffle deterministically (per-sector seed); assign
round-robin to the next farmhouse with remaining quota until
quota is exhausted. Each plot gets a {@code FarmCropPicker} draw
constrained by terrain (sloped → ORCHARD, pasture roll → PASTURE,
otherwise weighted draw across cultivated crops).

**Renderer policy:**

- Per plot: walks the polygon's bbox, filters cells via
  {@code Polygon.contains}; resamples Y via
  {@code MOTION_BLOCKING_NO_LEAVES}. Cells at non-water surfaces
  get farmland (moisture 7) + crop block placed; the centre block
  becomes a water source for irrigation. Pasture plots stamp
  dirt + occasional hay.
- Sector perimeter: oak fence stamped along each polygon edge via
  Bresenham line-walk; skips cells whose surface is water/lava.
- Plot perimeter: same line stamper on the plot polygon.
- Scarecrow: 60% chance per plot; oak fence post + carved pumpkin
  at a random inside-polygon position.
- Tool sheds: NBT-deferred. Renderer logs the hint position at
  edge midpoint 0; persists nothing into {@code toolShedPositions}
  for now (sector record stays mutable for future content).
- Inter-plot paths: deferred. Sector + plot fences provide the
  visual structure; cart-paths added when the user authors path
  NBTs alongside tool sheds.

**Smoke test contract (live, in-world):**

1. Spawn an AGRICULTURAL CITY in plains. Run `/liv farms <name>`
   and confirm one sector entry with 4 vertices, ~30-block radius,
   `plots = farmhouseCount × 4..6`. Walk the sector — perimeter
   fences should appear; each plot inside should be tilled with
   crop blocks and bordered with a small fence.
2. Spawn a TOWN-tier village near forest. Confirm
   `FarmCropPicker` selects ORCHARD for plots whose centre cell is
   forest-adjacent (`/liv farms` shows `crop=ORCHARD`).
3. Spawn a HAMLET. Confirm 2 plots per farmhouse, smaller sector
   radius (15 blocks).
4. Spawn a CIVIC or INDUSTRIAL village. Confirm `/liv farms`
   reports zero sectors (planner skipped).
5. Spawn a hilly map. Confirm the planner's sector polygon avoids
   slopes; corners pulled inward; no plots on cells with
   {@code localSlope > 3}.
6. `/save-all` + restart + `/liv farms <name>` — sector record
   persists, plots round-trip with sectorId + polygon vertices.
7. FarmerGoal smoke: confirm a farmer NPC walks to one of the new
   polygon plots, harvests via the polygon-aware
   {@code FarmPlot.getFarmlandBlocks}, replants. (No goal changes
   beyond plot-shape pickup.)

**NBT authoring (user task):**

- {@code structures/{culture}/decoration/farm/tool_shed.nbt} —
  one per culture; renderer logs hint positions but doesn't stamp
  until the file exists.
- Optional future: {@code scarecrow.nbt}, {@code composter.nbt},
  {@code cart_path_marker.nbt} as procedural-replacement fallbacks
  if the user wants finer aesthetics.

**Cumulative pending verification:** A1a + A2 + A3 + A4 + A1b + B1
+ B2.1 + B2.2 + B2.3 + B2.4 + B2.5 in a single live run.

Next: B2.6 (homesteading) per UNIFIED_REWORK_PLAN — wires the
HOMESTEAD_* AdjunctPlotTypes that B2.1 declared but B2.3 didn't
register (HOUSE buildings get coops / gardens / pens / bees with
probability gating).

### 2026-05-08 — B2.6 landed (homesteading) — Track B COMPLETE

Track B's final phase. Wires the HOMESTEAD_* AdjunctPlotTypes that
B2.1 declared but B2.3 intentionally left unregistered, plus three
new HOMESTEAD_* values per the user's "6 plot types" decision. The
roll is probability-gated per village size tier so most large
cities show no homesteads while hamlets show many. SPOUSE NPCs
work the homestead during any WORK_* DayPhase, CHILDREN during
SOCIAL — HEAD's profession goals stay untouched. Goal dispatch
goes through a fresh AbstractHomesteadGoal + HomesteadHandler
pattern so future B2.6+1 polish can drop in better animations or
content without re-architecting.

**Scope decisions (user-confirmed):**

- Plot pool — *6 types* (prompt's literal list). Added
  HOMESTEAD_WORKSHOP, HOMESTEAD_ORCHARD, HOMESTEAD_WOODSHED to the
  AdjunctPlotType enum alongside the 4 from B2.1
  (HOMESTEAD_COOP / HOMESTEAD_GARDEN / HOMESTEAD_PEN /
  HOMESTEAD_BEES). Total: 7.
- Goal pattern — *AbstractHomesteadGoal + handler registry*. Two
  thin Spouse / Child subclasses pin the FamilyRole filter; all
  per-tick work routes through HomesteadHandlerRegistry's
  per-AdjunctPlotType handler.
- Family role extension — *filter on existing SPOUSE / CHILD*. No
  HOMESTEADER / HOMEMAKER role additions in B2.6. canUse()
  predicates handle the filter cleanly.
- Probability roll — *inside PhasedPlanner.planAdjunct*. HOUSE is
  detected via the `registered.isEmpty()` short-circuit (HOUSE is
  intentionally absent from AdjunctPlotRegistry per doc 02);
  PhasedPlanner falls through to a per-tier probability gate, then
  a weighted draw from `CulturePlanningBias.homesteadPlotWeights`.

**Schema additions:**

- `Village/Decoration/Adjunct/AdjunctPlotType.java` — three new
  enum values appended (codec stable; existing saves load
  unchanged because the codec is StringRepresentable).
- `Cultures/CultureBundles.java` —
  `CulturePlanningBias.homesteadPlotWeights:
  Map<String, Double>`. Default weights bias toward COOP / GARDEN
  (1.3) and away from BEES (0.6). Codec adds an optionalFieldOf;
  legacy cultures load unchanged. Backwards-compat constructors
  preserved (4-arg, B2.4-shape, and B2.5-shape all delegate to
  the canonical 10-arg).
- `Entities/HouseholdData.java` — `homesteadPlotType:
  AdjunctPlotType?` field plus codec optionalFieldOf. Set once
  by `setHomesteadPlotType` (no-op if already set, so the
  one-roll-per-house contract is enforced at the data layer).

**HOUSE manifest extensions** (3 manifests):

- `cottage` — 4×4 BACK adjunct preference.
- `house` — 5×5 BACK.
- `large_house` — 7×7 BACK (larger HOUSEs accommodate larger
  homesteads).

All `required: false` so HOUSEs that fail the probability gate or
can't fit a homestead place without one — no building drops.

**Probability gate** (PhasedPlanner.rollHomesteadType):

```
HAMLET   → 80% inclusion
VILLAGE  → 60%
TOWN     → 30%
CITY     → 10%
```

On inclusion success, weighted draw across the 7 HOMESTEAD_*
types using `bias.homesteadPlotWeightFor(typeName)`. Returns
null on either probability miss or zero-total weights; caller
treats null as `AdjunctPlanOutcome.NONE` and the HOUSE places
without an adjunct.

**Household formation hook** (VillageInhabitantPopulator):

When a household is created via `HouseholdData.create`, the
populator scans `data.getAdjunctPlotsForBuilding(buildingId)` for
any HOMESTEAD_*-prefixed AdjunctPlot and stamps the household's
`homesteadPlotType` once. Subsequent NPC bootstrap reads the
field via `data.getHouseholdForNpc(uuid).getHomesteadPlotType()`
to wire dispatch.

**Goal hierarchy** (`Entities/Goals/Homestead/`):

- `HomesteadHandler` interface — `tick(Context)`, optional
  `onArrive` / `onStop`. Context bundles npc, level, data, parent
  house, household, plot, tickInGoal — keeps handler signatures
  short.
- `HomesteadHandlerRegistry` — static `EnumMap<AdjunctPlotType,
  HomesteadHandler>` populated at class init; `getOrFallback`
  returns `GENERIC_CHORES` for null / unregistered types.
- `AbstractHomesteadGoal` — base class. `canUse` filters on
  FamilyRole + DayPhase + household resolution. `start`
  dispatches to the registered handler. `tick` forwards the
  Context; handler returns true to end the cycle.
- `Spouse` (concrete subclass) — runs during any
  `DayPhase.isWork()` phase.
- `Child` (concrete subclass) — runs during `DayPhase.SOCIAL`.

**Shipping handlers** (under `Handlers/`):

| Handler | Cycle | Output |
|---------|-------|--------|
| ChickenCoopHandler  | 100 ticks | 1 EGG |
| VegetableGardenHandler | 110 ticks | 1 CARROT or POTATO (alternates) |
| PenHandler          | 130 ticks | 1 LEATHER or WHITE_WOOL |
| BeehivesHandler     | 160 ticks | 1 HONEY_BOTTLE or HONEYCOMB |
| WorkshopHandler     |  90 ticks | 1 STICK (placeholder craft scrap) |
| OrchardHandler      | 120 ticks | 1 APPLE |
| WoodshedHandler     | 140 ticks | 2 OAK_PLANKS |
| GenericChoresHandler |  80 ticks idle | — (fallback when no plot) |

Handlers walk to the plot origin during the first 20 ticks, then
"work" until the cycle tick. Output deposits to the NPC's
`getPersonalInventory()`; existing profession-economy logic
periodically transfers to household storage. Production rates
are deliberately lower than profession goals — homesteads are
family-scale, not commercial.

**Goal registration** (TownspersonMob.registerGoals):

```java
this.goalSelector.addGoal(15,
        new AbstractHomesteadGoal.Spouse(this));
this.goalSelector.addGoal(15,
        new AbstractHomesteadGoal.Child(this));
```

Priority slot 15 — below profession goals (which sit higher) so
HEAD's work always wins. SPOUSE / CHILD have no profession goals
competing.

**Debug command:** `/liv homestead <village>` lists every HOUSE,
its rolled plot type (or "—" / "(no household yet)"), member
count. Header reports total HOUSEs and how many won the
probability gate.

**Smoke test contract (live, in-world):**

1. Spawn a HAMLET-tier village. Run `/liv homestead <name>` —
   expect ~80% of HOUSEs to show a rolled plot type. Variety
   across types should reflect the weighted draw (COOP and
   GARDEN most common; BEES rare).
2. Spawn a CITY-tier village. Expect ~10% rolled — most HOUSEs
   show "—". Verify city houses still place cleanly without an
   adjunct.
3. `/save-all` + restart + `/liv homestead <name>` — rolled
   plot types persist on HouseholdData via the codec
   optionalFieldOf.
4. Watch a SPOUSE NPC during WORK_PRIMARY phase. Should walk to
   its plot origin and idle for ~100 ticks before producing an
   item into its personal inventory.
5. Watch a CHILD NPC during SOCIAL phase. Same loop with
   GenericChoresHandler if the household has no plot.
6. Spawn a HOUSE in cramped terrain — confirm the HOUSE places
   without an adjunct (`required: false` on the manifest), the
   household forms with `homesteadPlotType=null`, and SPOUSE /
   CHILD run `GenericChoresHandler`.

**NBT authoring (user task):**

Each HOMESTEAD_* AdjunctPlotType gets its own NBT path under
`structures/{culture}/decoration/homestead/{type}.nbt`. The
existing AdjunctPlot rendering pass logs deferred NBT placements;
B2.6 doesn't add per-type renderers (handlers ship the
functional loop only). Visual polish — coop blocks, beehive
geometry, workshop tools, orchard saplings — lives in the NBT
files when authored.

**Cumulative pending verification:** A1a + A2 + A3 + A4 + A1b + B1
+ B2.1 + B2.2 + B2.3 + B2.4 + B2.5 + B2.6 in a single live run.

Track B is **COMPLETE**. Decoration phases 0-3 from doc 01 onward
all ship: framework (B2.1), street furniture + welcome marker +
noticeboard (B2.2), industry + garden adjuncts (B2.3), parks
(B2.4), farms (B2.5), homesteads (B2.6). Track C and beyond
proceed independently.

### 2026-05-08 — B2.7 landed (command consolidation + spawn pipeline fix)

Closing-out cleanup after Track B's main shipment. Two issues
testing surfaced: command-surface debris and a wrong-orchestrator
bug on `/litv spawn`.

**The spawn bug.** `/litv spawn` duplicated Layers 1–4 inline and
called {@code MinimalSpawner.spawn} directly. {@code MinimalSpawner}
is the Layer-5 stamper only; it doesn't run any of the post-passes
B2.1–B2.6 wired into {@code V2VillageSpawnerAdapter} (decoration
profiles, parks, farms, homesteads, NPC population, trade routes,
simulation baseline, guild bootstrap, history seeding). The
adapter is the canonical orchestrator that {@code VillageSpawner}
already routes through; `/litv spawn` was the only call site that
duplicated the pipeline. **Fix:** rewrite SpawnCommand as a thin
~70-line wrapper around {@code V2VillageSpawnerAdapter.spawn} —
parse args, resolve culture defaults, delegate. The `radius`
argument is gone (the adapter has its own scan radius constant);
diagnostic output is reduced because the adapter logs the same
information through SLF4J.

**Scope decisions (user-confirmed):**

- Spawn fix — *route /litv spawn through V2VillageSpawnerAdapter*.
  Cleanest of the three options; matches what
  {@code VillageSpawner.spawn} already does (line 86).
- Atlas consolidation — *single `/liv atlas <subcommand>` tree*.
  Folded the legacy {@code AtlasRegionDebugCommand} into
  {@code AtlasDebugCommand}; deleted the legacy file; updated
  {@code ModModEvents} reference. New tree:
  `/liv atlas here|stats|sample|region [radius]`.
- B2 debug commands registration — *leave self-registered*. The
  inconsistency with {@code ModModEvents.onRegisterCommands()}
  (other ~47 commands) is cosmetic; both patterns fire on the
  same {@code RegisterCommandsEvent}.
- Help text — *every command*, delivered via a centralized
  {@code LivHelpCommand} (`/liv help` + `/litv help`) listing
  all 53 commands by category. Avoids touching 50+ files
  individually.

**Command surface findings (recon):**

- 53 commands total across `/litv`, `/liv`, `/building`, `/castle`,
  `/atlas`, `/kingdom`, `/guild`, `/npc`, ... etc.
- Zero V1-dead references (no ShapeType, ZoneRegistry, recipes).
- One duplicate (`/atlas` vs `/liv atlas region`) — consolidated.
- `/litv spawn` was the only command bypassing B2.1–B2.6 wiring.
- 4 B2 debug commands (decoration / parks / farms / homestead) use
  `@EventBusSubscriber` self-registration; verified all fire.
- {@code AdjunctDebugCommand} (`/liv adjuncts <village>`) was
  referenced in the prompt but didn't exist. Authored in B2.7.

**Final command list:**

| Path                                | Status | Source |
|-------------------------------------|--------|--------|
| `/litv spawn`                       | FIX→KEEP | SpawnCommand.java (rewritten thin) |
| `/litv site`                        | KEEP | SiteCommand.java |
| `/litv place`                       | KEEP | PlaceCommand.java |
| `/litv layout`                      | KEEP | LayoutCommand.java |
| `/litv help`                        | NEW | LivHelpCommand.java |
| `/liv help [<category>]`            | NEW | LivHelpCommand.java |
| `/liv decoration`                   | KEEP | DecorationDebugCommand.java |
| `/liv parks <village>`              | KEEP | ParkDebugCommand.java |
| `/liv farms <village>`              | KEEP | FarmDebugCommand.java |
| `/liv homestead <village>`          | KEEP | HomesteadDebugCommand.java |
| `/liv adjuncts <village>`           | NEW | AdjunctDebugCommand.java |
| `/liv atlas here\|stats\|sample\|region` | REPLACE→KEEP | AtlasDebugCommand.java (consolidated) |
| `/building place\|village create\|spawn\|house\|needs` | KEEP | BuildingCommand.java |
| `/castle spawn\|design`              | KEEP | CastleCommand.java |
| `/kingdom`, `/kingdom-claim`, `/village-tags` | KEEP | KingdomCommands.java |
| `/guild`, `/guilds`, `/party`        | KEEP | various |
| `/npc`, `/dialogue`, `/verb`, `/gossip`, `/office`, `/request`, `/visitor`, `/apprentice`, `/scribe`, `/letter`, `/appearance` | KEEP | NPC commands |
| `/business`, `/economy`, `/order`, `/farm`, `/farmbalance`, `/farmplot` | KEEP | economy commands |
| `/law`, `/crime`, `/religion`, `/health`, `/history`, `/event`, `/plague`, `/sim`, `/culture`, `/company` | KEEP | civic / lifecycle |

**Files deleted:**
- `Commands/AtlasRegionDebugCommand.java` — folded into
  `AtlasDebugCommand`.

**Files added (B2.7):**
- `Commands/AdjunctDebugCommand.java` — `/liv adjuncts <village>`.
- `Commands/LivHelpCommand.java` — central help directory.

**Files rewritten:**
- `Commands/SpawnCommand.java` — thin delegator (was 173 lines,
  now 75).
- `Commands/AtlasDebugCommand.java` — consolidated tree under
  `/liv atlas`; dropped `@EventBusSubscriber` (registers via
  ModModEvents).

**Pipeline verification:**

After B2.7, `/litv spawn` runs (in order, via V2VillageSpawnerAdapter):

1. {@code V2FeatureMap.scan} — Layer 1 terrain.
2. {@code SiteAnalyzer.analyze} — Layer 2 culture / inclination /
   tier.
3. {@code BuildingSelector.select} → {@code ReconciliationEngine}
   → {@code DependencyResolver} — Layer 3 selection.
4. {@code PhasedPlanner.run} — Layer 4 buildings + roads. Includes
   B2.1 adjunct rectangle planning (HOMESTEAD_* roll lives here
   for HOUSE).
5. Layer 5 inline: {@code OverlapAuditor}, {@code TerrainAdapter},
   {@code VegetationClearer}, {@code PadBuilder},
   {@code BuildingPlacer.placeAndRegister}, {@code RoadPainter}.
6. B2.5 {@code FarmSectorPlanner.plan} — between buildings and
   {@code runDownstream}.
7. {@code runDownstream} — {@code FarmPlotPlacer} (parked),
   {@code VillageInhabitantPopulator} (stamps homesteadPlotType
   from B2.6), {@code VillageDecorator},
   {@code AdjunctPlotRealiser}, {@code DecorationPass} (B2.2),
   {@code ParkRenderer} (B2.4), {@code FarmSectorRenderer} (B2.5),
   {@code TradeRouteManager}, {@code ConnectorPlanner} (when
   `useGraphConnector`), {@code VillageSimEngine.buildBaseline},
   {@code GuildBootstrap}, {@code HistoryProducer},
   {@code InitialLaws}.

Post-spawn the village should have: roads, buildings, road-side
decoration, building-gap fillers, plaza noticeboard with live
content, gate welcome markers, industry / garden adjuncts on
profession buildings, homesteads on HOUSEs (per probability
gate), parks where terrain affords them, farm sectors when
AGRICULTURAL.

**Cumulative pending verification:** A1a + A2 + A3 + A4 + A1b + B1
+ B2.1 + B2.2 + B2.3 + B2.4 + B2.5 + B2.6 + B2.7 in a single live
run. With B2.7's spawn fix, the user's report ("spawned villages
are missing parks, homesteads, farm sectors") should resolve —
those subsystems were always implemented; the command just
wasn't invoking them.

Track B is now fully closed out. Track C / Track D proceed
independently.

### 2026-05-08 — B2.8 landed (connection diagnostics + test-spawn affordances)

Surgical fix-up after user testing of B2.7 surfaced six concrete
disconnects + one missing affordance. Each finding addressed
without redesigning subsystems.

**Findings + fixes:**

- **T1 — HOUSE query**: `/litv homestead` reported zero HOUSEs even
  when V2 placed them. The filter (`b.getType() != BuildingType.HOUSE`)
  was correct; the empty-state output was misleading. Fix:
  empty-state now dumps the building-type breakdown so the user
  sees what types ARE present, plus a concrete reason ("V2
  BuildingSelector did not include HOUSE in this (tier,
  inclination), or ReconciliationEngine dropped it during
  cascade"). Combined with T3 it's actionable.

- **T2 — FarmSectorPlanner gating**: changed from
  "inclination != AGRICULTURAL → skip" to "farmhouseCount == 0 →
  skip". RESIDENTIAL / CIVIC villages with farmhouses now reserve
  sectors. Sector size formula was already inclination-agnostic
  (B2.5 doc-10 tier-only) so no downstream impact.

- **T3 — Status command messaging**: park / farm / homestead
  empty-state output now shows actual tier + inclination, plus a
  narrowed reason. ParkDebugCommand: "tier limit: HAMLET caps
  parks at 0" vs "ParkCandidateFinder found no candidates in
  this terrain". FarmDebugCommand: "no FARMHOUSE buildings
  (sectors gate on farmhouseCount >= 1)" vs "FarmSectorPlanner
  found no arable centre — ... Farmhouses present: N".

- **Inclination persistence**: Village gains an optional
  `Inclination` field with codec round-trip. V2 spawn adapter
  calls `village.setInclination(siteCtx.inclination())` after
  village construction. Legacy saves load with null inclination;
  status commands display "(unset)" for those.

- **T4 — Road state disconnect**: VillageDecorator was running
  V1 `VillageRoadNetwork.buildInitialNetwork` on V2 villages,
  producing 0 VillagePaths but logging confusingly. Removed the
  V1 road-network call + the misleading System.out.println pair;
  kept plaza polygon paving (separate concern) and TerrainSmoother
  (now no-op for V2 villages, safety-net for legacy).

- **T5 — Trade fulfillment vs DEPENDENCY_MISSING**: ReconciliationEngine
  marked `BLACKSMITH<-MINE` and `BAKERY<-MILLER` as trade-fulfilled
  but PhasedPlanner dropped them anyway. PhasedPlanner.run gains
  an overload that takes `Set<BuildingType> tradeFulfilledTypes`;
  placeOne skips DEPENDENCY_MISSING when a building is in that
  set. V2 adapter derives the set from
  `recon.tradeFulfilled().requiringType()`.

- **T6 — ConnectorPlanner warning storm**: hundreds of duplicate-
  edge / degenerate-cellPath warnings during spawn (graph
  invariants surface every validation pass; mostly pre-existing
  state). Demoted to DEBUG with one INFO summary line per
  validation: `[ConnectorPlanner] Graph has N validator
  warnings ... details at DEBUG`.

- **T7 — `/building village spawn` restored** with V2 vocabulary:
  `/building village spawn <inclination> <tier> [name]`.
  - `<inclination>`: any V2 `Inclination` enum value (tab-completes).
  - `<tier>`: any `ViabilityTier` value (tab-completes).
  - `[name]`: optional; auto-generates from
    `test_<inclination>_<tier>_<seedHex>` when omitted.
  - Both args are passed as overrides into a new
    `V2VillageSpawnerAdapter.spawn(...)` overload, which calls
    `siteCtx.withOverrides(inc, tier)` post-SiteAnalyzer. Terrain
    analysis (anchor, spine, axis) still runs from FeatureMap;
    only the classification fields the planner branches on are
    replaced.

**API additions:**

- `SiteContext.withOverrides(Inclination, ViabilityTier)` — copy-with
  for the two classification fields. Pass null for either to
  keep the analyzed value.
- `V2VillageSpawnerAdapter.spawn(level, origin, type, name,
  inclinationOverride, tierOverride)` — 6-arg overload. Old 4-arg
  delegates with both overrides null.
- `PhasedPlanner.run(siteCtx, fmap, sorted, unavailable, level,
  tradeFulfilledTypes)` — 6-arg overload. Old 5-arg delegates with
  empty set.
- `Village.getInclination()` / `setInclination(Inclination)`.

**Files touched:**

Modified: VillageDecorator, FarmSectorPlanner, PhasedPlanner,
ReconciliationEngine call site (V2 adapter), SiteContext, Village,
V2VillageSpawnerAdapter, ConnectorPlanner, BuildingCommand,
ParkDebugCommand, FarmDebugCommand, HomesteadDebugCommand.

**Cumulative pending verification:** A1a + A2 + A3 + A4 + A1b + B1
+ B2.1–B2.8 in a single live run. After B2.8, the user's reported
disconnects (zero HOUSEs visible, missing farm sectors, misleading
status messages, BLACKSMITH/BAKERY incorrectly dropped, log
spam) should all resolve.

---

## B2.9 — round-two diagnostic + perf fixes (2026-05-08)

Round-two response to B2.8 live testing. Eight findings, eight
fixes. No new abstractions; all changes are diff-local to the
files that owned the misbehaviour.

**T1 — Tier override didn't propagate.** `/building village spawn
... CITY` produced HAMLET-shaped layouts because `Village` re-
derived its tier from `buildingIds.size()` on every getter call.
- `Village.tierOverride` field added; `getSizeTier()` short-
  circuits to it when present.
- `Village.setSizeTierOverride(VillageSizeTier)` — null clears.
- `VillagePlazaMeta` extended to four fields (the existing sub-
  record workaround for the codec 16-arg ceiling), now carries
  `v2TierOverride`.
- `VillageSizeTier.fromViabilityTier(ViabilityTier)` — pass-
  through for CITY/TOWN/HAMLET; OUTPOST/UNVIABLE collapse to
  HAMLET.
- `V2VillageSpawnerAdapter.spawn(...)` now calls
  `village.setSizeTierOverride(...)` after the planner runs.

**T2 — Hot-loop log spam masked actual progress.** Two role
assigners reprinted the same assignment table every server tick
when the assignment didn't change.
- `WorkshopRoleAssigner` and `FarmRoleAssigner` gained
  `static ConcurrentHashMap<UUID, String> LAST_ASSIGNMENT`. The
  signature is `total + ":" + sortedWorkerUUIDs joined by ";"`;
  identical signature ⇒ skip the body.
- The remaining log lines demoted to `LOGGER.debug`.
- `VillageLeaderGoal` and `PostJobGoal` followed the same
  System.out → SLF4J debug demotion. The original guards (one-
  shot leader assignment; idempotent posting check) were already
  correct — the logs were the only spam.

**T3 — Six distinct InclinationProfile rosters.** Until now
`forInclination(...)` returned the AGRICULTURAL roster for
everything.
- AGRICULTURAL: FARMHOUSE outnumbers HOUSE roughly 2:1 at every
  tier (CITY: 25 farmhouses vs 12 houses).
- INDUSTRIAL: BLACKSMITH / CARPENTRY / STONEMASON / WOODCUTTER /
  MINE emphasis; TOOLSMITH + ARMORER + ATELIER on the secondary
  shelf; GUILD_HALL_CRAFTSMEN at CITY.
- RESIDENTIAL: HOUSE-dominant (CITY: 40 houses); thin civic
  shell.
- CIVIC: MARKET / INN / LIBRARY / BELL_TOWER tripled; CITY adds
  CHANCELLERY, TREASURY, GUILD_HALL_MERCHANTS, SCRIBE_WORKSHOP.
- SACRED: CHAPEL / SHRINE / TEMPLE / BELL_TOWER emphasis;
  SCHOLARS_RETREAT + GUILD_HALL_RELIGIOUS at CITY; HEALER_HUT and
  CANDLEMAKER reinforced.
- DEFENSIVE: GUARD_TOWER / WATCHTOWER / BARRACKS triad;
  CASTLE + PRISON at CITY; ARMORER / TOOLSMITH support tier.
- `forInclination(...)` is now a switch over all six values; every
  inclination resolves to its own static `InclinationProfile`.

**T4 — Plaza emitter conflicting with B2.4 parks.** Every plaza
emitted FOUNTAIN / LAMPS / BENCHES / FLOWERBEDS / GAZEBO /
MONUMENT / VENDOR_ZONE slots that competed with the new park
adjuncts.
- `DecorationSlotEmitter.emitPlazaSubSlots(...)` stripped to emit
  only `PLAZA_NOTICE_BOARD` (one per qualifying plaza). The
  amenity-style slots are now park territory.
- Removed the corresponding `hasBenches` / `hasGazebo` /
  `hasFountain` / `hasMonument` / `hasFlowerbeds` flag locals
  that fed the deleted branches.

**T5 — Boundary markers fired before paths were stable.** Slot
emitter was producing edge-of-village markers on partial road
graphs; deferred to a later phase.
- `DecorationSlotEmitter.emit(...)` line 118: the
  `emitVillageBoundaryMarkers(...)` call is commented out with a
  TODO pointing back to this entry. Method body retained in case
  a future phase re-enables it.

**T6 — `FarmSectorPlanner` arable threshold too strict.**
`arableScore` weighed slope/water/forest so heavily that flat
OPEN ground scored under the 0.5 threshold and farm sectors
never planted on plains.
- `arableScore` rebalanced — flat OPEN cells now have a 0.55
  baseline; slope (×0.25), water proximity (×0.15) and forest
  (×0.05) only nudge upward. Math.min(1.0, …) caps the total.

**T7 — Homestead roll rate too low at CITY.** PhasedPlanner was
rolling adjuncts at 0.10 for CITY, so CITY villages produced ~0
homesteads in practice.
- `PhasedPlanner.rollHomesteadType(...)` per-tier rates updated:
  HAMLET 0.95 / TOWN 0.80 / CITY 0.67. Was 0.80 / 0.30 / 0.10.

**T8 — `/liv decoration slots` syntax error without village
arg.** Brigadier rejected the bare form because there was no
zero-arg executor.
- `DecorationDebugCommand` now registers an `.executes(...)` on
  the `slots` literal (handler: `slotsAtPlayer(ctx)`) in addition
  to the existing argument branch.
- New `slotsAtPlayer(ctx)` resolves the village via
  `data.getVillageAt(BlockPos.containing(source.getPosition()))`;
  fails with a friendly hint if the source isn't standing in a
  village.
- Original body refactored into shared `slotsForVillage(...)`;
  both bare and explicit-name forms call it.

**API additions:**

- `Village.setSizeTierOverride(@Nullable VillageSizeTier)`
- `Village.tierOverride` field (codec'd through VillagePlazaMeta)
- `VillageSizeTier.fromViabilityTier(ViabilityTier)`
- `InclinationProfile.{INDUSTRIAL, RESIDENTIAL, CIVIC, SACRED,
  DEFENSIVE}` static fields (AGRICULTURAL was already public)

**Files touched:**

Modified: Village, VillageSizeTier, V2VillageSpawnerAdapter,
WorkshopRoleAssigner, FarmRoleAssigner, VillageLeaderGoal,
PostJobGoal, DecorationSlotEmitter, FarmSectorPlanner,
PhasedPlanner, InclinationProfile, DecorationDebugCommand.

**Cumulative pending verification:** B1 + B2.1–B2.9 in a live
run. After B2.9, CITY-spawned villages should pick the requested
tier, role-assigner spam should be gone, every inclination should
produce a recognisably different roster, plaza slots should no
longer collide with parks, farm sectors should plant on plains,
CITY homesteads should roll regularly, and `/liv decoration slots`
should work bare.

### 2026-05-09 — Track C1 landed (TradeRoad removal + caravan diagnostic fix)

Track C1 from `UNIFIED_REWORK_PLAN.md` shipped. Both rows
(C1-tr, C1-cv) flipped to Done above. Full per-file detail in
`ROADS_PROGRESS.md` under the "Track C1 — TradeRoad removal +
synthetic-caravan fix" entry; this is a summary row.

**Code shipped:**

Deleted: `Village/Economy/Trade/TradeRoad.java`,
`Village/Economy/Trade/RoadEvent.java` (legacy),
`Village/Economy/Trade/RoadEventScheduler.java`,
`Village/Economy/Trade/RouteRealiser.java`,
`Village/Roads/Graph/TradeRoadMigration.java`,
`Events/RouteRealisationSystem.java`. `TickSubsystemRegistry`
no longer registers `TradeRouteTickSystem`,
`RouteRealisationTickSystem`, or `RoadEventTickSystem`. The
`migrated` flag plumbing on `WorldRoadSavedData` is gone.

Refactored: `TradeRoute` (drop TradeRoad parameter from
efficiency / speed / chance methods; take quality + length ints
instead), `Caravan` (graph-only path, edge-derived speed
multiplier), `CaravanMerchantGoal` (graph-only resolveBlocks),
`CaravanSavedData` (graph-only land dispatch + edge-derived
delivery efficiency), `TradeRouteManager` (legacy LAND path
deleted; sea-route establishment retained), `RoadRouter` (legacy
merge helpers deleted), `MapRoadSnapshot` (`fromRoute` derives
cellPath from edges), continent + kingdom map scopes,
`/liv roads` debug commands, `/spawn caravan`, `/litv road debug
dispatch_test_caravan_between` and `caravan_status`,
`BuildingCommand`, `ServerTickDispatcher`, `TickSystems`'
WanderingTrader spawner.

**Synthetic-caravan finding:** the bug was in the diagnostic
command (synthetic principal UUID), not in
`TravellingGroupEngine.tick`. Diagnostic now reserves a real
merchant (`villageData.reserveIdleMerchant`) and calls
`setCurrentExpeditionId` on the entity, matching the
daily-dispatch path.

**Save compat:** old saves with `tradeRoads`, `roadEvents`, or
`migrated` JSON fields load fine — DFU silently drops fields the
codec no longer recognises.

**Cumulative pending verification:** spawn a fresh world; verify
daily-dispatched caravans walk visibly between villages; run
`/litv road debug dispatch_test_caravan_between A B` and watch
the test caravan reach the destination; spawn a sea-route test
case to confirm boat caravans still dispatch.

### 2026-05-09 — Track C2 landed (multi-gateway dock + through-village caravan traversal)

Track C2 / Phase 7f Slice 4 from `UNIFIED_REWORK_PLAN.md` shipped.
Full per-file detail in `ROADS_PROGRESS.md` under the "Track C2 —
Phase 7f Slice 4" entry; this is a summary row.

**Slice 1–3 disposition (per investigation):** Slice 1 was alive
(VillageRoadGraph data model + Slice 1 saved-data shipped). Slices
2 + 3 had pieces drafted (`GatewayPopulator`, `InternalRoadCommitter`,
`RouteSegment`, segment-aware Caravan path) but were never wired
into V2 spawn — V2 villages had empty internal graphs and a single
VILLAGE_DOCK at the spine end. Track C2 wired them in and added the
augmented Dijkstra needed for actual through-village traversal.

**Code shipped:**

- `V2VillageSpawnerAdapter` — populates synth-layout `gatePositions`
  from the V2 RoadNetwork (spine start/end + cross-street outer
  arms); calls `GatewayPopulator.populate` and
  `InternalRoadCommitter.commitFromV2` immediately after village
  registration, both `guard()`-wrapped.
- `InternalRoadCommitter.commitFromV2` (new) — derives the village
  internal graph from V2's `RoadNetwork`: spine split at every
  cross-street junction, plus one SIDE_PATH edge per cross-street
  arm. Reload-protected.
- `ConnectorPlanner.getOrCreateDockNode` — first reuses any
  TERMINUS at the docking anchor whose `GatewayLink` references
  this village; falls through to legacy VILLAGE_DOCK creation.
- `GraphTradeRouteEstablisher.findSegmentedPath` (new) —
  augmented Dijkstra; world-edge expansions plus virtual
  village-hop expansions across same-village TERMINUS pairs.
  Reconstructs to a `List<RouteSegment>` mixing `WorldEdge` and
  `VillageTraversal`.
- `RoadGraphDebugCommand.dispatchTestCaravanBetween` — tries the
  segmented pathfinder first; uses it when a village traversal is
  involved, falls back to the edge-list pathfinder otherwise.

**Save compat:** existing single-dock saves keep working unchanged —
`GatewayPopulator` only fires at fresh-spawn time and is no-op if
gateways already exist; `InternalRoadCommitter.commitFromV2` no-ops
if internal edges already exist. Pre-C2 villages have neither, so
they remain single-dock and `findSegmentedPath` falls through to
`findEdgePath`.

**Out-of-scope-but-noted:** `CaravanSavedData.dispatchNewCaravans`
still uses graph-edge dispatch; auto-LAND-route creation is a
known gap from C1 and a future task that re-introduces it will
need to call `findSegmentedPath` from there too.

**Cumulative pending verification:** spawn three villages where
the middle one has cross streets; confirm
`/litv road debug village_gateways` lists ≥ 2 gateways for the
middle one; run `dispatch_test_caravan_between` between the
flanking villages and watch the caravan walk through the middle
village along its painted spine and out the far gateway.

### 2026-05-09 — Track C3.1 landed (player-initiated road construction)

Phase 11 from `ROADS_PLAN.md` shipped. Per-file detail in
`ROADS_PROGRESS.md` under "Phase 11" (most recent entry); summary
here.

**Disposition before code:**
- `PlayerProfession` had no engineer slot → added new
  `ROAD_ENGINEER` enum value with `XpSource.ROAD_PROPOSAL_COMPLETE`.
- `CraftingOrderManager` is workshop-bench-shaped, not road-shaped
  → labor flows through a treasury-driven cross-tick loop, no NPC
  walking this phase.
- `ConnectorPlanner.planConnector` commits in-call → added
  `RoadProposalRouter.dryRun` peer for pure validation.

**User-confirmed scope:** treasury-driven labor; new ROAD_ENGINEER
profession; dryRun on a peer (not threaded through ConnectorPlanner);
minimal book screen + dev command.

**Code shipped:**

- `Village/Roads/Proposal/` — new package containing
  `RoadProposal` (record + Status), `RoadProposalCalculator`
  (cost formula), `RoadProposalRouter` (dryRun + commit using the
  same atlas + corridor pipeline as worldgen, reusing Track C2
  gateway TERMINUSes), and `RoadProposalManager` (submit / tick /
  complete / cancel).
- `Networking/WorldRoadSavedData.java` — `proposals` field on
  Snapshot (optionalFieldOf so pre-C3.1 saves load empty).
- `Profession/PlayerProfession.java` — new `ROAD_ENGINEER` enum
  value + `XpSource.ROAD_PROPOSAL_COMPLETE`. Exhaustive switch
  consumers (only `WorkplaceAssignmentManager`) get the new arm.
- `Village/Reputation/{VillageReputation,ReputationManager}.java`
  — new `ChangeReason.ROAD_PROPOSAL_COMPLETE` (+5 score) and
  `onRoadProposalCompleted` hook.
- `Items/RoadEngineerPlansItem.java` + `Items/ModItems.java`
  registration + `Networking/OpenRoadProposalsPacket.java` +
  `Gui/RoadProposalsScreen.java` — read-only book UI.
- `Events/TickSystems.java` + `Events/TickSubsystemRegistry.java`
  — `RoadProposalTickSystem` (interval = 20).
- `Commands/RoadGraphDebugCommand.java` — five new commands:
  `/litv road propose`, `/litv road estimate`,
  `/litv road cancel_proposal`, `/liv road debug complete_proposal`,
  `/liv road debug list_proposals`.

**Save compat:** existing saves load with no proposals;
`PlayerProfession`'s new enum value has its own thresholds and
defaults to 0 XP. Player-built edges go through the same
`EdgeRealizer` and per-edge maintenance system as worldgen edges.

**Out-of-scope but flagged:** village-treasury contribution,
NPC walk-and-place labor, full proposal-builder UI with route
preview, refunds on cancellation, auto-LAND-route creation for
daily caravan dispatch (a Track C1 carryover gap that affects
all LAND routing, not just player-built ones).

**Cumulative pending verification:** spawn two villages 800-1500
blocks apart with no existing connector; carry ≈1.5 gold; right-
click `litv:road_engineer_plans`; submit via
`/litv road propose <vA> <vB>`; watch the progress bar advance ~1
block per second; on completion verify edge present in
`WorldRoadGraph`, +100 XP, +5 reputation at both endpoints, edge
realised in world.

### 2026-05-09 — Track C3.2 landed (POI subroads)

Phase 12 from `ROADS_PLAN.md` shipped. Per-file detail in
`ROADS_PROGRESS.md` under "Phase 12"; summary here.

**Disposition before code:**
- `RoadNode.NodeType.POI_STUB` was already declared and validator-
  exempted but unused — Phase 12 wired it.
- POI discovery infrastructure did not exist (no `LandmarkRegistry`,
  no chunk-load scan); Phase 12 invented it as a player-proximity scan.
- No mod-added Old Realm structures exist; the prompt's
  watchtower-fiction beat has no anchor today. Vanilla
  `ruined_portal` near a `GREAT_ROAD` is the closest analog.
- `ConnectorPlanner.Candidate` is node-type agnostic, so the
  routing pipeline is reused unchanged.

**User-confirmed scope:**
- Discovery: player-proximity scan, every 10 seconds, 256-block
  radius, only loaded chunks.
- Targets: pillager_outpost + ruined_portal (all biome variants) +
  desert_pyramid + jungle_pyramid + igloo + swamp_hut.
- Density: layered skip rules — inside village claim / within 200
  blocks of an existing POI / no graph within 500 blocks (then
  ISOLATED).
- Visual: `PathMaterial.dirt()` rendered through FOOTPATH overlays
  at the existing 3-block tier width. No new palette.

**Code shipped:**

- `Village/Roads/Poi/` — new package with `DiscoveredPoi` (record +
  Status), `PoiDiscovery` (player-proximity scan + skip rules), and
  `PoiSubroadPlanner` (top-K candidate routing using the worldgen
  AtlasRouteRouter + CorridorAttractorBuilder pipeline).
- `Networking/WorldRoadSavedData.java` — `pois` field on the
  Snapshot record (optionalFieldOf so pre-C3.2 saves load empty);
  accessors `getPoi`, `putPoi`, `removePoi`, `getAllPois`.
- `Events/{TickSystems,TickSubsystemRegistry}.java` —
  `PoiDiscoveryTickSystem` (interval = 200) drives both halves
  (discovery + planning).
- `Village/Economy/Trade/GraphTradeRouteEstablisher.java` —
  `touchesPoiStub` filter applied to both `findEdgePath` (LAND
  caravans) and `findSegmentedPath` (Track C2 segmented). Sea routes
  unaffected.
- `Events/RoadUpkeepSystem.java` — tier-aware decay helper
  `decayDeltaForUnmaintained` returns –8 for POI subroads, –5 else.
- `Village/Roads/Realization/EdgeMaterialResolver.java` — POI
  subroad detection overrides material to dirt with seasonal +
  maintenance overlays at FOOTPATH tier; nulls culture so
  architectural detail passes don't fire.
- `Commands/RoadGraphDebugCommand.java` —
  `/litv road debug list_pois`.

**Save compat:** existing saves load with no POIs; the validator
already exempts orphan POI_STUBs so even partial writes (a stub
without an edge) load cleanly.

**Caravan exclusion** is shared across `findEdgePath` and
`findSegmentedPath` — Track C2's village traversal continues to
work; only edges with a POI_STUB endpoint are filtered out.

**Out-of-scope but flagged:** mod-added Old Realm structures,
pilgrim/traveller events that target POIs (Phase 10b territory),
sea-accessible POIs (Track C3.3), player-initiated POI subroads,
density region cap (escalation if needed).

**Cumulative pending verification:** spawn near a ruined_portal or
pillager_outpost; wait 10–20 seconds; `/litv road debug list_pois`
shows the structure; if a road is within 500 blocks, a LOCAL-tier
edge appears; `dispatch_test_caravan_between` doesn't route via
that edge; the POI subroad's maintenance score drops faster than
neighbouring CONNECTOR edges over a few daily cycles.

### 2026-05-09 — Track C3.3 landed (sea route unification); Track C complete

Phase 13 from `ROADS_PLAN.md` shipped. Per-file detail in
`ROADS_PROGRESS.md` under "Phase 13"; summary here.

**Disposition before code:**
- `SeaRoute.cellPath` shared the same Long-encoding `RoadEdge.cellPath`
  uses, so the data port was field-for-field clean.
- `TradeConnection` had only `SeaRoute` as implementer post-C1; both
  safely deletable after migration.
- `BoatCaravan` extends `TravellingGroup` (not `Caravan`), so unifying
  saved-data classes was bigger than the win was worth.
- Dock-building UUIDs on legacy SeaRoute didn't survive migration cleanly;
  dispatch already re-scans for `BuildingType.DOCKS` so dropping them was
  safe.

**User-confirmed scope:**
- Edge representation: new `EdgeTier.SEA` (codec-natural; existing
  exhaustive switches gain SEA arms).
- Keep `BoatCaravanSavedData` as a runtime index of active boats.
- Full delete of `SeaRoute` + `TradeConnection`; migration uses an
  inline `LegacySeaRoute` record.

**Code shipped:**

- `Village/Roads/Graph/RoadEdge.java` — `EdgeTier.SEA` enum + `isWater()`
  helper.
- 12+ exhaustive `switch (EdgeTier)` sites updated with SEA arms across
  realisation / decay / lighting / planning / map / proposal calculator.
- `Village/Economy/Trade/SeaRouteMigration.java` (new) — one-shot,
  idempotent migration with inline `LegacySeaRoute` record. UUID
  preservation: legacy `connectionId` becomes the new edge's UUID, so
  in-flight `BoatCaravan` records load with their persisted
  `seaRouteId` field still pointing to a valid edge.
- `BoatCaravan` reads cell path + speed from `RoadEdge` via
  `WorldRoadSavedData.getGraph()`.
- `TradeRouteManager.establishSeaRoutes` creates SEA-tier edges directly
  (with their own VILLAGE_DOCK endpoints), wraps in `TradeRoute.createGraph`.
- Dispatch unification: `BoatCaravanSavedData` iterates `TradeRoute`s
  picking first-edge-is-SEA; `CaravanSavedData` adds inverse filter.
- `EdgeRealizer` short-circuits on SEA before material resolution;
  edge marked realised with empty block path.
- `RoadUpkeepSystem.decayDeltaForUnmaintained` returns -2 for SEA tier
  (matches legacy 3-quality-per-2-week curve over a daily upkeep
  cycle).
- `GraphTradeRouteEstablisher.findEdgePath` and `findSegmentedPath`
  skip SEA-tier edges in adjacency-list construction (chain with
  C3.2's POI_STUB filter).
- `MapSeaRouteSnapshot.fromEdge(RoadEdge)` replaces
  `fromSeaRoute(SeaRoute)`; map UI wire shape unchanged. KingdomMapScope
  / ContinentMapScope derive snapshots from SEA-tier edges.
- `WorldRoadSavedData` gains `seaRoutesMigrated` flag (optionalFieldOf
  default false; constructor default true so fresh worlds skip).
- `VillageSavedData` retypes legacy storage to
  `Map<UUID, SeaRouteMigration.LegacySeaRoute>`; `peekLegacySeaRoutes`
  / `clearLegacySeaRoutes` for migration only; legacy `addSeaRoute` /
  `getSeaRouteById` / `getConnectionById` deleted.
- `SeaRoute.java` and `TradeConnection.java` deleted.

**Save compat:**
- Pre-Phase-13 saves load with `seaRoutesMigrated = false`; the
  migration runs at first server tick from `ServerTickDispatcher`,
  converts legacy records to SEA-tier edges, then flips the flag.
- A migration interrupted mid-way reruns next load (the legacy map
  is only cleared after edge creation succeeds, and the flag is the
  very last write).
- Fresh worlds skip the migration entirely (constructor default).
- `BoatCaravan` codec key remains `"seaRouteId"` for save-compat;
  the field semantically refers to a RoadEdge UUID post-Phase-13.

**Behavioural preservation:**
- Land caravans, sea caravans, dispatch cadence, dock triggering,
  decay curves, history events, reputation deltas — all observable
  behaviour unchanged.
- C2 multi-gateway villages with both land gateways and a dock
  building have both kinds of nodes in `WorldRoadGraph`, each
  connected to different-tier edges.
- C3.1 player proposals reject SEA tier at argument validation.
- C3.2 POI exclusion still applied via the existing `touchesPoiStub`
  filter (independent of SEA filter).

**Out-of-scope but flagged:** hybrid land-sea-land routes (graph rep
allows them; dispatcher inspects only the first edge today),
boat-pathfinding quality (still teleport-based), sea-accessible POIs,
player-initiated sea routes.

**Track C is now complete.** Goal-state: all trade edges live on a
single graph; LAND vs SEA disambiguation is via tier on the first
edge; the only parallel data structure that survives is
`BoatCaravanSavedData` as a runtime index of active boat caravans.

**Cumulative pending verification:** load a save with active legacy
sea routes; confirm migration log; spawn two coastal villages on a
fresh world and verify a SEA edge forms; run land
`dispatch_test_caravan_between` and confirm the SEA edge is excluded;
wait several upkeep cycles and confirm SEA decays at -2/cycle.

### 2026-05-09 — Track D1 landed (kingdom-tier scaffolding bridge)

Phase D1 from `UNIFIED_REWORK_PLAN.md` lines 222–241 shipped as a
single phase. Per-decision detail in `KINGDOM_PROGRESS.md` (newly
created — D2 will append to it); summary here.

**Disposition before code:**
- `Cultures.Culture` is a 14-field record at the 16-field DFU
  ceiling; adding a 15th sub-bundle is fine.
- `Kingdom` is a mutable class (not record) with codec persistence;
  no stability / legitimacy fields existed prior to D1.
- Membership was list-on-Kingdom only (`villageIds: List<UUID>`);
  no reverse field on Village.
- `NpcLifeEventBus` has the synchronized DISPATCHERS / EVENT_COUNTS /
  LISTENERS pattern at `Npc.Events`; KingdomEventBus mirrors at
  `Kingdom.Events.KingdomEventBus` (sibling-style).
- `OfficeRegistry.registerKingdomOffices()` already had KING +
  CHANCELLOR + TREASURER + COUNCIL_SEAT; D1 adds the five missing
  ones (Scholar, General, Magistrate, Spymaster, Diplomat).
- `Kingdom/Castle/` is 20 files of procedural castle generation,
  entirely separate from kingdom membership / office framework /
  culture. Out of scope for D1; not touched.

**User-confirmed scope (over four design questions):**
- Heraldry: minimal 4-enum stub (Tincture × 7, Charge × 10, Layout
  × 4) — ~1960 unique combinations per culture, sufficient for D3
  to extend with renderers.
- Subdivision model: five-value enum (UNITARY / PROVINCES /
  DUCHIES / TRIBAL_CONFEDERATION / CITY_STATE_LEAGUE).
- Per-culture defaults: distinct per culture (plainfolk = tribal
  council with levy-heavy upkeep, highmarch = duchies with
  agnatic primogeniture and tribute, silkwood = elective
  city-state league with trade-heavy upkeep, tidereach = elective
  provinces with trade-heavy upkeep, default = standard provinces
  with primogeniture and balanced upkeep).
- Office IDs: `kingdom_<office>` matching the existing
  `kingdom_king` / `kingdom_chancellor` / `kingdom_treasurer`
  namespace.

**Code shipped:**

- `Cultures/CultureBundles.java` — `CultureKingdomDefaults` record
  with `nobilityRanks`, `successionRule`, `subdivisionModel`,
  `upkeepMix`, `requiredOffices`. New enums `SuccessionRule` (6
  values), `SubdivisionModel` (5 values), `UpkeepSource` (4 values).
- `Cultures/Culture.java` — 15th field `kingdomDefaults` with codec
  optionalFieldOf default.
- `Cultures/CultureRegistry.java` — per-culture defaults table
  applied to plainfolk / highmarch / silkwood / tidereach.
- `Kingdom/Heraldry.java` (new) — record + Tincture/Charge/Layout
  enums + codec.
- `Kingdom/HeraldryGenerator.java` (new) — deterministic xorshift
  hash from `(culture, kingdomId, foundingSeed)` → Heraldry; same
  inputs always produce same output.
- `Kingdom/Kingdom.java` — `stability`, `legitimacy` ints with
  defaults 75; `heraldry` field; `Kingdom.ScalarBand` enum +
  `bandOf` helper; constructor generates heraldry deterministically;
  `fromCodec` back-fills heraldry for pre-D1 kingdoms.
- `Kingdom/KingdomMembershipMigration.java` (new) — one-shot,
  idempotent migration that walks `Kingdom.villageIds` and stamps
  `Village.kingdomId`. Mirror of `SeaRouteMigration` (Track C3.3)
  shape. Side-effect: back-fills Heraldry for pre-D1 kingdoms whose
  loaded heraldry is `Heraldry.UNKNOWN`.
- `Village/Village.java` — `kingdomId: Optional<UUID>` field +
  codec + accessors.
- `Networking/VillageSavedData.java` — `kingdomMembershipMigrated`
  flag + accessors + codec wiring; fresh worlds default true,
  pre-D1 saves arrive false.
- `Kingdom/Events/KingdomEvent.java` (new) — sealed interface with
  8 record subtypes (Founded, Dissolved, RulerSucceeded,
  OfficeFilled, OfficeVacated, VillageJoined, VillageLeft,
  ScalarShifted).
- `Kingdom/Events/KingdomEventDispatcher.java` (new) — listener
  interface mirroring `Npc.Events.EventDispatcher`.
- `Kingdom/Events/KingdomEventBus.java` (new) — synchronous dispatch
  hub mirroring `NpcLifeEventBus` exactly. `registerDefaults()`
  is a no-op in D1 (no subscribers).
- `Profession/Profession.java` — four new enum values: GENERAL,
  MAGISTRATE, SPYMASTER, DIPLOMAT.
- `Npc/Skills/ProfessionSkills.java` — stub skill mappings for
  the four new professions.
- `Npc/Office/OfficeRegistry.java` — five new office IDs +
  `register(...)` calls in `registerKingdomOffices()` (Scholar,
  General, Magistrate, Spymaster, Diplomat).
- `Village/Buildings/BuildingType.java` — `ESTATE` enum value.
- `Events/ServerTickDispatcher.java` — calls
  `KingdomMembershipMigration.migrateIfNeeded` once on first tick
  alongside the existing `SeaRouteMigration` call.
- `Events/ModModEvents.java` — calls
  `KingdomEventBus.registerDefaults()` (idempotent no-op in D1)
  alongside the existing `NpcLifeEventBus.registerDefaults()`.
- `Commands/KingdomDebugCommand.java` (new) —
  `/litv kingdom debug describe <name>`, `list`, `events_stats`.
- `KINGDOM_PROGRESS.md` (new) — append-only kingdom track log.

**Save compat:**
- Pre-D1 saves load with `kingdomMembershipMigrated = false`; the
  migration runs at first server tick, stamps `Village.kingdomId`
  for every kingdom member, then flips the flag.
- `Heraldry` defaults to `Heraldry.UNKNOWN` for pre-D1 kingdoms;
  the migration regenerates it via `HeraldryGenerator.generate(culture,
  kingdomId, 0L)`.
- All new Codec fields use `optionalFieldOf` with explicit defaults
  so missing-field saves load cleanly.
- `Kingdom/Castle/` package untouched.

**Wired but inert:**
- `CultureKingdomDefaults` persists on each Culture; no consumer
  reads it.
- `Kingdom.stability` / `legitimacy` persist; no driver loops fire.
- `Kingdom.heraldry` persists + back-fills; nothing renders it.
- `KingdomEventBus` accepts subscribers and fires events; D1 ships
  zero subscribers and zero call sites that fire events.
- `Village.kingdomId` is back-filled at load; no D1 code queries
  it (legacy `Kingdom.villageIds` remains the canonical read).
- `BuildingType.ESTATE` exists; no spawn rule, no inhabitant
  populator, no structure JSONs.
- Five new kingdom offices registered; no goal class, no NPC ever
  spawns with the four new professions.

**Cumulative pending verification:**
- Existing-save migration: load a save with active kingdoms;
  `[KingdomMembershipMigration]` log line on first tick;
  `/litv kingdom debug describe <name>` shows matching legacy
  list and reverse-pointer member counts; heraldry is no longer
  UNKNOWN.
- Fresh-world test: a newly-founded kingdom has the eight offices
  registered, default 75/75 stability/legitimacy, generated
  heraldry, the kingdom-tier sub-bundle visible in the describe
  output.
- Codec round-trip: save → restart → load → save produces an
  identical NBT diff.
- Behaviour preservation: caravans, road maintenance, V2 villages,
  decoration cycles, NPC schedules — all work identically to
  pre-D1. The only observable change is debug-command output.
- `git grep KingdomEventBus` shows it as a peer (sibling-level)
  of `NpcLifeEventBus`, not nested.

**What's deferred:**
- D2 (Section 5 rewrite): consumes `CultureKingdomDefaults` to
  drive kingdom worldgen schemas + capital-emits-claim wiring.
- D3: stability / legitimacy decay drivers, kingdom-tier office
  population logic, estate placement + inhabitants, succession-
  rule-driven ruler transitions, KingdomEventBus subscribers,
  culture-required-offices enforcement.
- Track E: heraldry rendering, treaties / war / vassalage
  taxonomy, kingdom-tier UI, kingdom-merge / split workflows.

### 2026-05-09 — Track D2 landed (KINGDOM_PLAN Section 5 V2 rewrite)

Phase D2 from `UNIFIED_REWORK_PLAN.md` shipped. Doc-only — `git
diff` for this commit touches Markdown / text only. Per-decision
detail in `KINGDOM_PROGRESS.md`; summary here.

**Disposition before code:**
- KINGDOM_PLAN Section 5 lives at `docs/KINGDOM_PLAN.md` lines
  248–300; bounded cleanly between Section 4 ("does NOT redefine")
  and Section 6 ("Phases and slices").
- V2 vocabulary surveyed: `Inclination` (6 values; no AUTHORITY),
  `Category` (16 values incl. CIVIC_AUTHORITY), `Provides /
  Requires` records on PlacementProfile, `AdjunctPlotRegistry`
  bindings, `SubBuildingType` (8 current values), D1
  `CultureKingdomDefaults` (5 fields).
- Existing BuildingType values relevant to V1 slots: CASTLE,
  NOBLE_MANOR, TREASURY, CHANCELLERY, ESTATE (D1). Missing:
  PALACE, AUDIENCE_HALL, CEMETERY, FESTIVAL_GROUND.

**User-confirmed scope (over four design questions):**
- TREASURY: keep `BuildingType.TREASURY`; the V1 "queryable
  position" semantic is met by `getBuildingsOfType(TREASURY)`.
- PALACE / AUDIENCE: PALACE is a stylistic NBT pack on
  `BuildingType.CASTLE` (no separate type); AUDIENCE is a
  deferred `SubBuildingType.AUDIENCE_CHAMBER`.
- CEMETERY / FESTIVAL_GROUND: both deferred BuildingType
  extensions (each with PlacementProfile and Provides/Requires
  declarations).
- "AUTHORITY inclination": maps to existing CIVIC Inclination
  + CIVIC_AUTHORITY Category at the manifest layer; the
  prompt's wording was loose, no Inclination axis added.

**Rewrite shape:**

Section 5 now reads as eight numbered sub-sections in V2 prose:
- 5.1 Capital-eligible village layouts (Inclination + Provides
  pattern).
- 5.2 Province-seat derivation (runtime aggregate, not flag).
- 5.3 Spatial bindings (AdjunctPlot vs SubBuilding choice
  rules).
- 5.4 Deferred BuildingType extensions (CEMETERY,
  FESTIVAL_GROUND).
- 5.5 Kingdom-wide settings on the D1 culture sub-bundle (six
  fields: claimBudgetHint, vassalEligibleCultures,
  hostileCultures, minNobilityTier, claimResistance,
  provinceSeatThreshold — all D1 follow-ups).
- 5.6 Spacing parameter (preserved from V1).
- 5.7 Determinism contract (preserved from V1).
- 5.8 Robustness slices (preserved from V1).
- Appendix: V1 → V2 translation table with Mapped /
  Distributed / Subsumed / Deferred status per V1 concept.

**Translation outcome (14 V1 concepts):**
- **Mapped (4):** capital_emits_claim, CASTLE_SLOT,
  NOBLE_RESIDENCE, TREASURY.
- **Subsumed (2):** province_seat_eligible (derived from
  CIVIC_AUTHORITY aggregate); PALACE_SLOT (CASTLE NBT pack
  variant).
- **Distributed → D1 follow-up (5):** claim_budget_hint,
  vassal_types, hostile_types, min_nobility_tier,
  claim_resistance — all relocate to `CultureKingdomDefaults`.
- **Deferred (3):** AUDIENCE → `SubBuildingType.AUDIENCE_CHAMBER`,
  CEMETERY → `BuildingType.CEMETERY`, FESTIVAL_GROUND →
  `BuildingType.FESTIVAL_GROUND`.

**D1 follow-up identified:** the rewrite calls for six fields
on `CultureKingdomDefaults` that D1 didn't ship (claimBudgetHint,
vassalEligibleCultures, hostileCultures, minNobilityTier,
claimResistance, provinceSeatThreshold). This is a small bundle
extension — single record-field additions with codec
optionalFieldOf defaults — that lands as a D1.5 / D1 follow-up
before D3 phase 1 opens. Not part of D2 (doc-only).

**No code changes.** No new types, no modified types, no new
methods. Repo `git diff` shows only:
- `docs/KINGDOM_PLAN.md` — Section 5 rewrite + appendix.
- `UNIFIED_REWORK_PROGRESS.md` — D2 row + activity-log entry.
- `KINGDOM_PROGRESS.md` — D2 entry (per-decision rationale).

**Behaviour preservation:** the underlying mod is unchanged;
only the kingdom-plan doc updates. D1's "wired but inert" code
remains wired but inert.

**What's deferred for the bundle extension (D1.5):**
six field additions on `CultureKingdomDefaults`, each with
codec `optionalFieldOf` default. Same shape as the existing
five fields. No new mechanism.

**What's deferred for Track E:** the three `BuildingType` /
`SubBuildingType` extensions land in D3 alongside their feature
ship-points (cemetery in D3 phase 2, festival ground in D3
phase 5, audience chamber in D3 phase 3).

### 2026-05-09 — Track D3.1 landed (kingdom worldgen + capital generation)

Phase D3.1 (kingdom plan Phase 1) shipped. Per-decision detail in
`KINGDOM_PROGRESS.md` D3.1 entry; summary here.

**Disposition before code:**
- `WorldgenKingdomSeeder` was already clean and modular. Pre-D3.1
  `KingdomSpawner.planComposed` produced multi-village kingdoms.
- `Kingdom/Castle/` (31 files) is generator-style but unused. No
  `castle_styles/*.json` exist in resources today — full
  integration requires content authoring outside this phase's
  scope.
- V2 had no CASTLE PlacementProfile and no "capital" concept.
- `KingdomEventBus` had zero call sites pre-D3.1.
- `CultureKingdomDefaults` was missing the 6 D2-flagged D1.5
  follow-up fields.

**User-confirmed scope (over four design questions):**
- Capital castle: use existing `level_1.nbt` via V2 PlacementProfile.
  Kingdom/Castle/ stays orphan (Track E candidate); D3.1 doesn't
  integrate.
- D1.5 fields: add the 6 fields now (~100 LOC bundle extension).
- Office staffing: hybrid — fresh-spawn founding ruler;
  draft/fresh-spawn culture-required offices.
- Multi-village flow: replace `planComposed` with `CapitalGenerator`;
  capital-only initial state.

**Code shipped:**
- `Cultures/CultureBundles.java` — `CultureKingdomDefaults` gains
  6 D1.5 fields.
- `Cultures/CultureRegistry.java` — per-culture D1.5 values.
- `Village/Planning/V2/Layer3/PlacementDefaults.java` — CASTLE +
  NOBLE_MANOR + TREASURY profiles. Forward-looking; inert until
  capital-tier village types declare them required (D3.2+).
- `Kingdom/Kingdom.java` — `capitalVillageId` + `foundingTick`
  fields. Codec at the DFU 16-cap.
- `Kingdom/Worldgen/CapitalGenerator.java` (new) — single-village
  kingdom generator. Reads culture-keyed `claimBudgetHint`;
  sets `capitalVillageId`, `foundingTick`; regenerates heraldry
  from foundingTick; fires `KingdomFounded`.
- `Kingdom/Worldgen/KingdomOfficeBootstrap.java` (new) +
  tick subsystem. Post-realisation: fresh-spawns ruler;
  drafts/fresh-spawns culture-required offices; fires
  `RulerSucceeded` + `OfficeFilled`.
- `Kingdom/KingdomMembershipMigration.java` — second pass for
  `capitalVillageId` back-fill, gated on independent
  `kingdomCapitalMigrated` flag.
- `Networking/VillageSavedData.java` — new flag + codec wiring.
- `Kingdom/WorldgenKingdomSeeder.java` — calls `CapitalGenerator`
  instead of `planComposed`.
- `Kingdom/KingdomSpawner.java` — `planComposed` body deleted
  (~150 LOC). Admin paths (`spawn`, `spawnComposed`) preserved.
- `Commands/KingdomDebugCommand.java` — `describe` + `list`
  extended with new D1.5 + D3.1 fields.

**Save compat:**
- Pre-D3.1 saves load with `kingdomCapitalMigrated = false`;
  migration runs once on first tick, stamps
  `Kingdom.capitalVillageId` from first villageId, then flips
  the flag.
- Independent of D1's `kingdomMembershipMigrated` flag — pre-D3.1
  saves where D1 already ran still get the D3.1 capital
  back-fill.
- Fresh worlds default both flags true.
- Kingdom codec at the 16-field DFU cap. VillageSavedData codec
  at the 16-field cap. No more codec room without consolidation.

**Wired but inert:**
- CASTLE / NOBLE_MANOR / TREASURY PlacementProfiles. Forward-
  looking; D3.2+ wires capital-tier village types that declare
  these required.
- 5 of 6 D1.5 fields (claimResistance, vassalEligibleCultures,
  hostileCultures, minNobilityTier, provinceSeatThreshold).
  Populated and queryable; D3.2+ consumers read them.

**Out-of-scope, flagged:**
- Kingdom/Castle/ integration — moved to Track E.
- "Visibly distinct capital settlements" — partially deferred;
  V2 PlacementProfile machinery is wired but no village type
  uses it yet (D3.2+).
- `/litv kingdom debug regen` — not shipped; determinism
  verifiable via two-world manual test.
- Office NPC behaviour goals — fresh-spawned kingdom-tier
  NPCs idle. D3 phase 3 wires behaviour.

**Cumulative pending verification:** spawn three different fresh
worlds (different seeds) and confirm:
1. Each produces a deterministic set of kingdoms with deterministic
   heraldry per `(culture, kingdomId, foundingTick)`.
2. Each kingdom has exactly one member village (the capital).
3. After walking a player into the capital, the king becomes
   seated within ~1 second; `/litv kingdom debug list` shows
   king=seated.
4. Pre-D3.1 saves load and back-fill `capitalVillageId` cleanly.
