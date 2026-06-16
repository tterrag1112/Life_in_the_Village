# State of the Mod — as-built systems overview

*Regenerated per milestone; describes the code as it is. Last regenerated
2026-06-10.*

This is a structural snapshot of what exists in the codebase and where each
system's live frontier and known gaps sit. It is verified against the code,
not against the older plan docs. One standing caveat applies everywhere:
**in-world verification debt.** Every recent phase shipped with "build
verification deferred (sandbox blocks maven.neoforged.net)"; the mod
compiles on Garrett's machine but no smoke-test results are recorded. Treat
runtime behavior as unconfirmed until a consolidated in-world session runs.

---

## Village layout / placement

V2 is the only planner. Entry path: `VillageSpawner.spawnVillage` →
`V2VillageSpawnerAdapter.spawn` (the single spawner path; `/litv spawn`
routes here too). Five layers live under `Village/Planning/V2/`:

- **L1** terrain / feature scan
- **L2** site analysis + zones / nuclei
- **L3** building selection / reconciliation
- **L4** `PhasedPlanner` (~3,300 lines — the center of gravity)
- **L5** realization (overlap audit, terrain adapter, viability, roads)

**Current frontier.** `DISTRICT_ONLY_MODE = true` is hardcoded in
PhasedPlanner. Districts that ship today: civic core, market sub-district,
residential band (courtyard + street-row arrangers, green-commons fill),
workshop craft row (4c-b, with per-craft-lot fallback), parks (≤3). Farm
complexes are skipped under the flag (`farmReserve = 0`). The next moves are
flag-OFF farm validation, then 4c-c (workshop ring / cap bump ~132 / grouped
craft cluster — lever choice open), then retiring DISTRICT_ONLY_MODE.

**V1 is gone from source.** Zero classes remain for ZoneRegistry /
ShapeRecipe / LayoutPrimitive cascade / Adaptive / slot-based
PlacementMatcher. Residue is comments and strings only. Surviving V1-era
classes that are **live V2 dependencies** (do not delete): `SlotTag`,
`LayoutPlan` + `AnchorKind`, `RoadPrimitive`, `FeatureMap` (17 consumers),
`RoadGraph`.

**Known gaps.** Full-village validation across tiers + regression seeds is
unrun. Rural re-enable (relaxed farm gate) is pending the flag flip.

## Decoration

Live: the variant system (manifest-driven; `VariantResolver` is the unified
selector), the decoration framework second pass, the plaza complex
(`CivicPlazaComplex` renders the town square), the parks renderer, the
sub-building BE-anchor scanner, the street-furniture package, the organic
road placer + culture palettes, and the courtyard border painter.

**Known gaps.** The AdjunctPlot system was deleted (~13 files); industry
adjuncts / gardens are blocked on a new design, not the old one. Phases 1–4
(street-furniture impl, signs, gardens, farm-plot rework, homesteading,
walls, festivals, cemeteries) are genuinely not started, and their specs
predate the district-era substrate — they need respec, not just doing.
Decoration is now folded into whatever district/system pass it belongs to,
never a standalone track.

## Roads (complete through Phase 13)

The world graph is canonical (`WorldRoadGraph`, edge tiers
GREAT_ROAD / TRUNK / CONNECTOR / LOCAL / SEA). Shipped: worldgen great roads
with lazy realization, the unified placer pipeline shared by world and
village roads, segmented trade routes (land + boat caravans), player road
proposals (Phase 11), POI discovery + sub-roads (Phase 12), and sea routes
(Phase 13). Great-road terrain authority (profiles, retaining walls,
supports/viaducts), lighting + culture palettes, and the Phase 9 network-
evolution layer (dead edges, network-attracted placement) and Phase 10 event
infrastructure are all in.

**Known gaps.** Phase 10 event *content* is only test placeholders; hybrid
land–sea–land dispatch is unbuilt (the dispatcher reads only the first edge);
boat pathfinding is teleport-based; the stairway primitive paint is stubbed.

## NPC core (Phases 0–4 complete; 5 partial; liveliness through L6)

All Phase 0–4 systems are present and wired: traits, memory, knowledge,
mood, skills, specialization, offices, life goals, dialogue (~25 trees), 31
player verbs, gossip, schedules, hobbies, child/elderly arcs, apprenticeship,
scribal, letters/books, crime/justice, medicine/plague, laws (22), economy
channels, companies, request board, visitors, village history, cultures (4),
events (~33 types), nobility, business fronts.

**Liveliness has shipped L0–L6**: idle director, the NO_ACTIONABLE_WORK
work-satisfied signal (brainMemories registration fixed), hobby re-wiring,
gather-at-square + ambient props, activity flavor, committing-preempts-
ambient, and the village chunk loader + festival preemption.

**Profession roster:** 26 professions with registered brain behaviors.

**Known gaps.** Appearance (task 33) is data-only — Layer-1 fields + a
life-event producer exist, but the renderer never reads them, there is no
sync packet, and there are no textures beyond `townsperson.png`. The content
pass (task 34) has not started (dialogue is still the starter set, slants
unexpanded, no tuning/QA pass). **No behavior at all** for HERALD, CHANCELLOR
(both spawn-capable — real silent gaps) or GUILDMASTER (acknowledged content
gap). GENERAL / MAGISTRATE / SPYMASTER / DIPLOMAT are documented placeholders
that never spawn. The legacy `ProfessionGoalFactory` persists with ~20 empty
stubs (migration debt — the Goal layer still runs beside the Brain layer
with `BrainNavGuard` arbitration; FARMHAND has a duplicate registrar).

## Religion / Priest / Monk / Quests (recently active; large)

The as-built far exceeds the original spec: gods as first-class entities with
a favour economy (F1a), a per-world religion store + interreligious relations
(F1b), rites end-to-end (executor / scheduler / ledger), sacred spaces + holy
time + theophany imprints (S1–S3), saints / canonization / relics (SR1–SR4),
divine systems V1–V5 (favour, miracles, visions, curses/wrath, theophany),
temple economy + graveyards + UI screens, kingdom religion-as-authority, and
the MONK profession + a self-organizing monastery economy (R6a–d).

**Current frontier.** The quest engine (F2) is the live edge: F2a-1..3 +
F2b-1 have shipped (sealed Objective kinds, event+poll completion paths,
devotion ranks, divine callings; `PlayerCalling` deleted). Next is **F2b-2** —
re-seat legacy guild player quests onto the F2 base.

**Known gaps.** The Escort objective is vocabulary-only; Deliver is
non-consuming; pilgrimage proximity-notify is deferred; monastery/abbey have
no structure NBTs and no auto-placement; the quest journal UI is absent
(`/quest` command only); idle priests don't bless. `Quests/` (F2) and
`Guilds/Adventurer/Quest.java` (NPC parties) coexist deliberately until
F2b-2 re-seats the guild player path.

## Kingdom (Phases 0–6 complete; 7 not started)

Fully built: worldgen seeding (2 kingdoms/world), capitals + castle
generation (~38 files), nobility houses / fealty / succession, provinces +
offices + capabilities, law typology, charters / treaties / intrigue, the
audience loop + standing, rebellion / collapse / merger, war v2, and
religion-as-authority. `KingdomEventBus` is real with 51 fire sites.

**Known gaps.** The kingdom-placement schema fields are unwired:
`biomeAffinity`, `kingdomRoles`, `tradePriority`, `maxPerKingdom` have zero
readers; `canBeCapital` / `settlementTier` are lint-only. Capital selection
uses a hardcoded culture→type table in the seeder — the entire kingdom rework
shipped without consuming the fields added for it. Phase 7 (polish/scale) is
not started; flagged-not-done items: Royal Scholar competence multiplier,
stability gates on capabilities, the player-held delegate office gate
decision, capability denials → kingdom history, office competence in
`KingdomBookScreen`.

## Merchant / markets / economy (arc complete)

Live: unified pricing (board == trade screen), the market complex + stall
pool + event stalls, merchant claim/restock, procurement + export caravans
with guards and a map layer, player stall leasing (whole-footprint
right-click + sign retained for display), the economy view, and the kingdom
price board. The stall redesign shipped as the sign-funnel direction; a
custom stall block remains a floated idea only.

**Known gaps.** Three treasury ledgers coexist. Live: `Village.treasuryBronze`
(`depositToTreasury` + `LawTaxHooks`, ~25 caller files) and
`Kingdom.treasuryBronze` (tax cascade). Dead-ish: the
`Village/Economy/VillageTreasury` instance ledger — `create()` has zero
callers and both mutation sites are no-ops on post-legacy worlds, but its
static wage/income constants are live (delete-or-wire decision needed; re-home
the constants if deleting). There is a known stall-inventory bug (merchants
use the market inventory, not their own stall's) and a market-day NBT/right-
system mismatch. Guilds: `AbstractGuild` + per-type instantiation + the
request board shipped; the legacy adventurer `GuildData` is retained
intentionally.

---

## Cross-cutting

- **In-world verification debt** is the single biggest unknown — the
  cumulative untested set spans the 2026-06-06 layout work, the F2 quest
  engine, SR4 relics, and effectively the whole NPC stack's logged history.
- **Goal-vs-Brain coexistence** is the standing architectural debt: the
  legacy `Entities/Goals/**` layer runs beside the Brain layer with
  `BrainNavGuard` arbitration and ~20 empty registrars.
- **Authored-but-unconsumed data** recurs: kingdom placement fields, culture
  schedule layering / economic norms / apprenticeship norms, appearance
  Layer-1 fields — three reworks each left a data layer ahead of its
  consumer.

## Cleanup inventory (hygiene)

Dead code (verified zero callers): `Village/Decoration/TownSquarePlacer.java`;
the `VillageTreasury` instance-ledger path (`create()`, the
`VillageSavedData.{get,put,remove,getAll}Treasury` surface + `treasuries`
codec field, `RequestSettlement.DOC_HOOK`); `Caravan.dispatchTick` /
`BoatCaravan.dispatchTick`; the preserved-dead Guard goals
(`GuardEquipmentGoal`, `GuardAwarenessGoal`); `KingdomEventBus.addListener`.
Stale comments/strings: `VillageSpawner` header (V1 references), the
`CaravanMerchantGoal` comment refs, `DecorationSlotEmitter:300` (deleted
TradeRoad), `BuildingComplexRegistry` javadoc (deleted AdjunctPlotRegistry),
`MarketStallPlacer:193` tombstone, the pre-3c PhasedPlanner drop-log wording.
