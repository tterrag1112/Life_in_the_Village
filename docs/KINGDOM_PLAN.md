# Kingdom Rework — Master Plan

Status: **design phase**. Implementation starts after the NPC rework
and decoration rework are complete. Placement rework lands first and
absorbs several items previously scoped here.

This document is the authoritative source for the kingdom rework. All
slice specs derive from it. Anything not in this document is not in
scope for the kingdom rework.

---

## 1. Scope and intent

The kingdom rework rebuilds kingdoms as a political superstructure on
top of the NPC and decoration reworks. It inverts the worldgen flow
(villages are primary; kingdoms claim them), introduces full nobility
and dynasty simulation, formalizes laws/offices/government as gated
capability graphs, and adds the political content that makes a
running world feel alive — succession, rebellion, intrigue, religion
as authority, charters, war shells, ruler audiences, and dynasty
trees.

What this rework is *not*:
- A from-scratch design of cultures, offices, NPC laws, religion,
  crime, families, schedules, or visitor flux. The NPC rework
  delivers all of these. Kingdom work *extends* them.
- A village placement rewrite. The placement rework lands first and
  delivers reliable village realization at 90%+ success. Kingdom
  work assumes this works.
- A decoration system. The decoration rework delivers festival
  grounds, cemeteries, banners on kits, subbuildings. Kingdom work
  *uses* them.
- Warfare proper. Battle/militia/siege mechanics are their own
  separate session after kingdoms.
- Trade route mechanics rework. Already a separate session.
- Lore content authoring. Already a separate session.
- JSON-driven custom cultures, government types, law packs, modder
  hooks. Deferred to a later authoring pass.

---

## 2. Prerequisites and ordering

These reworks complete before kingdom work begins, in this order:

1. **Placement rework** — slot system, layout primitives, robustness
   passes (90% target), schema reservations for kingdom fields.
2. **NPC rework** — components, life event bus, traits, memory, mood,
   skills, office framework, village laws, crime, religion, medicine,
   schedules, marriages, lineage, cultures, visitor flux, request
   board, history records.
3. **Decoration rework** — festival grounds, cemeteries, subbuildings,
   walls, banners on kits, town square rework.

The kingdom rework reads from all three and adds only what is
genuinely kingdom-scope.

---

## 3. Locked-in design decisions

These are the foundational choices the rework is built on. Any
deviation requires re-review before implementation.

### Worldgen and claiming

- Villages are the primary worldgen entity. Villages exist before
  kingdoms.
- Kingdoms are claims over villages. A kingdom that fails to claim
  a capital simply doesn't exist — there is no failed-kingdom state.
- Independent villages are first-class. Many villages will never
  belong to any kingdom.
- Three-zone generation: eager near spawn, planned-but-unrealized in
  middle ring, unscheduled beyond. Deterministic from world seed so
  exploration order doesn't change kingdom shape.
- Capital villages emit claims via Dijkstra over atlas cells. Each
  village seed resolves to the nearest compatible capital by
  claim-cost distance whose polygon contains it. Compatibility
  requires culture match and absence from `hostile_types`.
- Claim resolution is lazy — runs as seeds enter the planned ring,
  not at world creation.

### Territory vs membership

- Territory is a soft polygon (the Dijkstra claim).
- Membership is an explicit per-village `kingdomId` field.
- The two should mostly agree but may diverge: a frontier village
  outside the polygon is still a member; a foreign village inside
  the polygon is a disputed enclave.

### Nobility

- Up to 16 rank slots in a global enum. No culture uses all 16.
- Each culture declares its rank subset and names. Default eight-rank
  feudal progression: COMMONER → FREEHOLDER → BURGHER → KNIGHT →
  BARON → COUNT → DUKE → MONARCH.
- Nobility is stored as a rank index on each NPC. Culture
  determines what the index *means*.
- Houses persist across all government types. Nobility ranks are the
  monarchy-specific layer. A republic has houses without ranks.
- Estates are the economic substrate of nobility. NOBLE_MANOR
  buildings anchor estates; surrounding cells produce estate income.
- Retinue upkeep is paid from estate treasury. Insufficient upkeep
  causes prestige loss and eventually rank loss.
- Tax flow goes through the noble hierarchy: village → village
  leader → liege → ... → ruler. Each level can withhold; withholding
  causes stability loss in that liege's domain.
- Marriage produces children who inherit house from primary parent
  (culture-determined). Diplomatic marriages between royal houses
  create dual-loyalty descendants.
- Family tree is sparse: living and recently-dead tracked; distant
  ancestors compressed to summary records.
- Succession runs the culture's rule on ruler death. Heir refusal
  and second-in-line usurpation are stochastic outcomes.
- Player nobility is per-kingdom standing, not global. A player is
  COMMONER in foreign kingdoms by default.

### Government and offices

- Government type is determined by culture and matches the NPC
  rework's TRADITIONAL_MONARCHY / MERIT_REPUBLIC / FEUDAL_COUNCIL /
  STRONGMAN. Many more types may be added later.
- Offices extend the NPC rework's office framework with kingdom-tier
  slots: King (or culture-equivalent ruler), Chancellor, Scholar,
  General, Magistrate, Spymaster, Treasurer, Diplomat.
- Office requirements are per-culture: minimum nobility rank,
  required building, required profession.
- Capabilities are gated by office presence and competence. Vacant
  or incompetent offices cause capability loss.
- Holding an office may grant nobility in some cultures (ennobled
  chancellor pattern).

### Laws

- Three archetypes via sealed `KingdomLaw` interface: ToggleLaw,
  ScalarLaw, EnumLaw.
- Kingdom laws are a layer above the NPC rework's village-level
  laws. Categories: Economy, Crime, Education, Diplomacy, Religion,
  Military, Land, Family.
- Drafting requires Scholar; scalar/enum enactment requires
  Chancellor; toggles require only the ruler.
- Laws have prerequisites, enactment costs, scope, and effect hooks.
- Lawmaking lifecycle: draft → propose → enact, with reversibility
  at the draft stage.

### Stability and legitimacy

- Stability is a [0-100] scalar at village, province, and kingdom
  scope. Modifiers tracked transparently as `(source, value, decay)`
  tuples for the GUI.
- Stability thresholds drive visible events, not raw probabilities.
- Legitimacy is a [0-100] scalar per ruler. Set by path to throne
  (inherited / elected / conquered / usurped / founded). Affects
  noble cooperation and rebellion risk.

### Provinces

- Subdivision model is per-culture: territorial / tribal /
  functional / none.
- Territorial provinces use noble-manor Voronoi over the kingdom
  polygon.
- Skip subdivision for kingdoms with ≤3 villages.
- Provinces have governors, treasuries, and stability separate from
  kingdom.

### Realized vs simulated

- Every political event has: trigger predicate, weighted outcome
  table, deterministic seed `(kingdomId, eventType, gameDay)`,
  realize handler.
- The seed must not depend on player presence. Player presence
  changes only whether the event plays out visibly.
- Events span succession, marriages, feasts, rebellions, treaties,
  diplomatic visits, and provincial unrest.
- Either extend `NpcLifeEventBus` for kingdom-scope events or build
  a peer `KingdomEventBus` with the same scaffolding pattern. The
  decision is deferred until Phase 0 implementation begins.

### Resources

- Kingdoms have an upkeep mix declared per culture: e.g. 100%
  currency, or 40% currency + 30% food + 30% mana.
- `KingdomUpkeepSource` interface with `dailyOutput`, `consume`,
  `displayName`. Default `CurrencySource` ships in this rework.
- Mod-compatibility implementations (Mekanism mana, AE2 energy,
  etc.) deferred. Interface is a placeholder for the design.

### Player experience

- Per-kingdom standing rather than global rank. Default COMMONER
  in foreign kingdoms.
- Titled grants: a player may be granted a specific named barony
  with an estate, retinue, and heirs.
- Audience loop: NPCs petition the player-ruler with decisions.
  Builds on the NPC rework's player-verbs system.
- Dynasty tree GUI in the kingdom book.
- Filterable kingdom newsfeed with category and importance filters.
- Multiplayer politics is reserved as design space; concrete
  multiplayer-only mechanics deferred.

### Decline and conflict

- Rebellion is stability-driven and threshold-based. Visible event
  triggers at province stability thresholds (40 grumble, 20
  secession threat, 10 secession).
- Kingdom collapse: extended low stability shatters into independent
  provinces; capital may persist as small successor.
- Merger via marriage-union, voluntary union, or conquest.
- War system v2 is a kingdom state with goals and casus belli;
  battle/militia/siege mechanics are deferred to a separate session.
- Religion gains a political authority layer over the NPC rework's
  religion mechanics.
- Kingdom age cycles: founding-era / mature / decadent state with
  modifiers per state.

---

## 4. What this rework does NOT redefine

These are owned by other reworks. The kingdom rework reads them and
extends them but does not redefine them.

| System | Owned by |
|---|---|
| Office framework, selection methods, competence | NPC rework |
| Cultures (4 starter), CultureResolver | NPC rework |
| Government forms (4 starter) | NPC rework |
| NPC traits, memory, mood, skills, life event bus | NPC rework |
| Village laws, LawEffect, village policies | NPC rework |
| Crime, religion, medicine, priest, healer | NPC rework |
| Marriage, lineage, family tracking | NPC rework |
| Schedules, hobbies, life arcs, apprenticeship | NPC rework |
| Letters, books, scribal professions | NPC rework |
| Visitor flux, request board, guild refactor | NPC rework |
| Village history records | NPC rework |
| Festival grounds, festival kits | Decoration rework |
| Cemeteries, headstones | Decoration rework |
| Subbuildings, castle subbuilding registry | Decoration rework |
| Banners on kits, dynamic NBT overrides | Decoration rework |
| Village walls, town square, signs | Decoration rework |
| Slot system, layout primitives | Placement rework |
| Village type schema, VillageTypeData fields | Placement rework |
| Village placement robustness (90% target) | Placement rework |

---

## 5. What the placement rework MUST include for kingdoms

The placement rework lands first. These items must be in its scope
to avoid retrofitting:

### Slot primitives

- **CASTLE_SLOT / PALACE_SLOT** — capital-tier civic anchor distinct
  from regular civic-anchor.
- **NOBLE_RESIDENCE slot** — manor anchor, reservable independent
  of generic civic placement, scales with village tier.
- **AUDIENCE / COURT slot** — guaranteed point in every capital for
  ruler audiences. Not emergent from subbuilding scan.
- **CEMETERY slot** — large enough in capitals to scale with royal
  lineage accumulation.
- **FESTIVAL_GROUND slot** — every capital has one reliably.
- **TREASURY position** — queryable position in capital-tier
  villages.

### VillageTypeData schema reservations

Schema fields whose values may be placeholders but whose slots must
exist in the JSON to avoid second migration:

- `capital_emits_claim: boolean`
- `claim_budget_hint: int`
- `vassal_types: [string]`
- `hostile_types: [string]`
- `min_nobility_tier: int`
- `province_seat_eligible: boolean`
- `claim_resistance: float` — 0 always claimable, 1 never claimable

### Spacing parameter

- Spacing logic must accept a `culture` parameter on the seed even
  if the cost function ignores it initially. Same-culture villages
  pack tighter; hostile-culture villages require wider spacing.

### Determinism contract

- Village seeds must be deterministic from world seed alone, not
  from accumulated state or atlas-fill order. Kingdom claim
  resolution depends on this.

### Robustness slices land in placement rework, not kingdom rework

- Fallback layout chains (degraded retry on failure)
- Iterative layout expansion (wave-based slot emission)
- Water/cliff-tolerant primitives (bridges, causeways, stepped
  roads)
- Exit criterion: 90%+ village realization success on real terrain

---

## 6. Phases and slices

Each slice is independently testable in-world. Slice ordering within
a phase matters; phase ordering is mostly forced by dependencies.
Each slice produces an in-world test you run before the next one
starts.

### Phase 0 — Bridge to existing infrastructure

Extends what the NPC and decoration reworks already built.

- **0.1 Culture extension.** Add kingdom-tier fields to existing
  `Culture` record: nobility rank list, succession rule,
  subdivision model, upkeep mix, kingdom-office requirements. The
  4 shipped cultures get extended in-place.

- **0.2 Event bus decision.** Implement either an extension to
  `NpcLifeEventBus` for kingdom-scope events or a peer
  `KingdomEventBus` with the same four-part scaffolding pattern
  (trigger predicate, outcome table, deterministic seed, realize
  handler). One trivial test event ("ruler considers their day")
  validates the path.

- **0.3 Stability scalars.** Village, province, kingdom-level
  stability fields with transparent modifier lists. Daily recompute
  via the event scheduler. No consumers yet.

- **0.4 Territory vs membership split.** Refactor: village has
  explicit `kingdomId`. `KingdomClaim` becomes purely the territorial
  polygon. Migrate existing kingdoms by reading `villageIds`.

- **0.5 Legitimacy scalar.** Per-ruler `legitimacy` field with
  source list. Set on ruler change. Display in kingdom book.

- **0.6 Estate primitives.** Skeletal `Estate` record bound to
  NOBLE_MANOR / castle subbuildings. Owner, building set, income,
  treasury. Persistence only. Daily tick is no-op.

- **0.7 Heraldry.** Banner generator: deterministic from kingdom
  seed for kingdoms; from house id for houses. Persisted on Kingdom
  and House records. Banner overrides feed the decoration rework's
  festival kit dynamic NBT system.

- **0.8 Kingdom-tier office stub.** Extend NPC office framework with
  kingdom slots: King/Ruler, Chancellor, Scholar-office, General,
  Magistrate, Spymaster, Treasurer, Diplomat. Storage and
  registration only; no behavior wired.

**Phase 0 exit test:** existing world loads cleanly, kingdoms still
function, /liv debug commands show stability/legitimacy/banner,
manors create estate records, kingdom offices appear (vacant) in the
kingdom book.

### Phase 1 — Worldgen rewrite

Inverts the village/kingdom relationship. Lazy claiming. Independent
villages.

- **1.1 Village seed sampler.** New `VillageSeedSampler` runs over
  filled atlas regions, produces lightweight `VillageSeed` records
  (position, type, derived tags, deterministic id, culture).
  Poisson-disk stratified sampling with per-shape exclusion radius.
  Independent villages now exist and realize correctly.

- **1.2 Three-zone generation policy.** `KingdomGenerationManager`
  defines eager / planned / unscheduled rings. Eager runs at world
  creation around spawn (deterministic seed). Planned populates as
  `AtlasFillSystem` fills regions. Unscheduled is purely lazy. All
  three produce VillageSeeds only.

- **1.3 Capital types and claim emission.** A capital seed runs
  Dijkstra to produce a territorial polygon. Polygons stored as
  `KingdomClaim` records keyed by capital seed id, not by kingdom.

- **1.4 Claiming resolution.** For every village seed, find the
  nearest compatible capital by claim-cost distance. Compatible =
  culture match + not in `hostile_types`. Resolved seeds become
  kingdom members; unresolved become independent villages.
  Deterministic from world seed.

**Phase 1 exit test:** new world generates with mix of kingdoms and
independent villages. All near-spawn villages realize. Far regions
populate as the player explores. No failed kingdoms in the log. Two
players exploring opposite directions get the same kingdom shapes.

### Phase 2 — Houses, ranks, nobility substrate

Houses persist across government types. Ranks layer on monarchies.

- **2.1 Houses (government-type-agnostic).** `House` record: id,
  name, founder, members, head, prestige, banner. NPCs gain
  `houseId`. House founding when a non-house NPC reaches a prestige
  threshold. Persisted via SavedData. Reuses NPC family/lineage
  data.

- **2.2 Nobility ranks.** NPC gains `nobilityRankIndex` (0-15).
  Per-culture rank-index → name resolution. Assignment via
  culture-defined rules: village leader → KNIGHT in feudal cultures;
  office-grant ennoblement; inheritance.

- **2.3 Estate income flow.** Manor estates produce daily income
  from surrounding cells. Income flows estate → noble personal
  treasury. Retinue upkeep deducted. Insufficient upkeep → prestige
  loss → eventual rank loss.

- **2.4 Fealty and tax flow.** Replace direct village → kingdom tax
  flow. Taxes flow through liege chain. Each level can withhold;
  withholding hits stability for that liege's domain. Kingdom
  treasury becomes ruler's treasury minus personal expenses.

- **2.5 Marriage and lineage.** Marriage as an event riding on the
  bus from Slice 0.2. Reuses NPC marriage/lineage. Children inherit
  house from primary parent (culture-determined). Diplomatic
  marriages create dual-loyalty descendants.

- **2.6 Succession.** On ruler death, run culture's succession rule:
  primogeniture, partible, elective, designation, combat. Heir
  refusal and second-in-line usurpation are stochastic. Legitimacy
  set by path. Player heirs go through the same logic.

- **2.7 Dynasty tree GUI.** New section in kingdom book: family tree
  of ruling house, browsable, marriages linking to other houses
  (clickable). Living/dead/heir status visible.

**Phase 2 exit test:** kingdoms run for many simulated days; rulers
die and succeed; houses rise and fall in prestige; family trees
populate. Player can found a house, marry, see descendants.

### Phase 3 — Provinces and offices wired

Mid-tier political structure. Office capabilities wired.

- **3.1 Province subdivision.** Per-culture model: territorial /
  tribal / functional / none. Territorial uses noble-manor Voronoi
  over kingdom polygon. Province records with governor, stability,
  treasury. Skip for kingdoms with ≤3 villages.

- **3.2 Kingdom-tier office wiring.** Wire behavior into the office
  stubs from Slice 0.8. Selection methods come from culture.
  Vacant/incompetent offices cause real capability loss.

- **3.3 Capability gating.** `KingdomCapability` enum with predicate
  evaluator. ISSUE_DECREE, DRAFT_TREATY, DECLARE_WAR, LEVY_TROOPS,
  PASS_LAW, INVESTIGATE_CRIME, ISSUE_CURRENCY, INTRIGUE_FOREIGN.
  GUI gracefully shows locked capabilities with reasons.

- **3.4 Provincial governance.** Governors collect provincial taxes,
  enforce laws within province, can withhold. Provincial reports
  surface in audience loop (Phase 5). Province stability separate
  from kingdom.

**Phase 3 exit test:** a kingdom with chancellor + scholar + general
can do everything; a kingdom with no offices can only do primitives.
Provinces appear on the kingdom map with governors.

### Phase 4 — Laws, intrigue, diplomacy v2

Mechanical depth.

- **4.1 Law typology refactor.** Sealed `KingdomLaw` interface with
  ToggleLaw / ScalarLaw / EnumLaw implementations. Kingdom laws as
  a layer above village laws (NPC rework owns village laws). Each
  law has category, scope, prerequisites, enactment cost, effect
  hooks.

- **4.2 Law GUI rewrite.** Kingdom book section mirroring the
  village-law UI from NPC rework. Category sidebar, active+available
  list, drafting form with effect preview, draft → propose → enact
  lifecycle.

- **4.3 Charters and privileges.** Persistent grants object granted
  to a noble, guild, religious order, or city. Survive ruler
  changes. Revocable at stability cost. Built on NPC rework's
  office-attached powers as the structural template.

- **4.4 Spymaster and intrigue.** Spymaster office unlocks foreign
  intelligence, sowing discontent in rival provinces, discovering
  plots, counter-intelligence. Builds on stability scalars and
  event bus.

- **4.5 Treaties.** Treaty objects: alliance, non-aggression, trade
  deal, vassalage. Drafted by scholar, ratified by both rulers.
  Persistent until broken. Breaking → legitimacy/stability hits.
  Vassalage adds VASSAL_OF / OVERLORD_OF relations.

**Phase 4 exit test:** rich law GUI works; treaties function;
spymaster can be appointed and used; charters persist across ruler
changes.

### Phase 5 — Player experience layer

The systems run; now make them feel like a game.

- **5.1 Ruler council / audience loop.** New verb category
  extending NPC rework's player-verbs. NPCs petition the
  player-ruler periodically: noble requests, governor pleas, scholar
  proposals, dispute arbitration. Queued in kingdom book with
  deadlines.

- **5.2 Player per-kingdom standing.** Player has standing in every
  kingdom they've interacted with; default COMMONER. Foreign rulers
  can promote. Standing gates noble-only mechanics in foreign
  kingdoms.

- **5.3 Titled grants.** Quests, deeds, royal favor can grant
  player a specific barony — named estate, manor, retinue, heir
  designation. Estate income flows to player.

- **5.4 Heir traits.** NPC heirs have personality traits
  (vindictive, scholarly, martial, pious, greedy, paranoid,
  magnanimous, cautious). Traits give starting modifiers when
  taking the throne. Surfaced in dynasty tree.

- **5.5 Kingdom newsfeed.** Filterable event feed in kingdom book:
  marriages, deaths, laws elsewhere, wars, scandals, province
  reports. Filter by category and importance.

- **5.6 Audience and event scenes.** Realize events from Slice 0.2's
  bus when player is nearby — NPCs gather and play out feasts,
  successions, treaty signings. Builds on NPC scheduling.

- **5.7 Heraldry on world objects.** Banners from Slice 0.7 appear
  on capital walls, manors, festival kits, treaty documents,
  letters from the kingdom. Reuses decoration rework's banner
  override system.

**Phase 5 exit test:** player who founds a kingdom has a full daily
loop — petitioners, decisions, news, attending events, managing
nobles. Player who joins a foreign kingdom has a meaningful path
from commoner to titled.

### Phase 6 — Decline, conflict, religion-as-authority

Forces preventing eternal kingdoms.

- **6.1 Rebellion mechanics.** Stability-threshold-driven via event
  bus. Province at stability < 20: secession threat (ruler
  negotiates / crushes / accepts). < 10: province breaks away as
  independent kingdom with governor as ruler. Vassal kingdoms can
  rebel against overlord.

- **6.2 Kingdom collapse.** Total kingdom stability < threshold for
  extended period: shatter into independent provinces. Capital may
  persist as small successor. History records the fall. Other
  kingdoms can claim orphaned villages.

- **6.3 Kingdom merger.** Marriage-union, voluntary union, conquest.
  Marriage-union: royal houses merge; descendants inherit both
  claims. Voluntary: weak kingdom petitions strong neighbor.
  Conquest: war outcome.

- **6.4 War system v2 (political shell only).** War as kingdom
  state with goals, casus belli, scoring. Levies pulled via fealty
  chain. Battles abstracted unless player present. Outcomes:
  territory transfer, tribute, vassalization, status quo.
  Legitimacy modifiers based on outcome. Battle/militia/siege
  mechanics deferred to separate session.

- **6.5 Religion as political authority.** Adds the political
  layer above NPC rework's religion. Religious authority can
  conflict with secular: priest refusing royal sanctification,
  religious rebellion threshold, conversion campaigns, official
  religion declarations, charters granting religious orders land.

- **6.6 Kingdom age cycles.** Founding-era / mature / decadent
  state for kingdoms based on age and ruler succession count.
  Modifiers per state. Decadent kingdoms more prone to collapse,
  mature ones resistant to law change. Emergent fall-of-Rome
  narratives.

**Phase 6 exit test:** running a world for many in-game years
produces realistic political churn — kingdoms rise, decline,
fragment, merge, get conquered. No player intervention required.

### Phase 7 — Polish, scale, longevity

Systems-tier work for long-running worlds.

- **7.1 Sparse simulation pruning.** Garbage-collect dead-end house
  branches after sufficient simulated time. Compress dormant houses
  to summary records. NPC budget caps for noble-tracking per
  kingdom. Performance profiling.

- **7.2 Cross-save legacy hooks.** Optional founder's-legacy carry
  forward — kingdom name, house banner, founding myth. Cosmetic.

- **7.3 Player-driven scenarios.** Tools for authoring world-state:
  pre-built "iron-age starting world", "fragmented post-empire
  world", "single-hegemon world".

- **7.4 Final pass.** Tutorial book, tooltips on kingdom-book
  widgets, edge case fixes from earlier phases.

---

## 7. Cross-cutting concerns

These thread through every phase and need ongoing attention.

**History entries.** Every event, simulated or realized, writes a
history entry. Audit at end of each phase.

**Tick budget.** Profile after each phase. Kingdom-scale simulation
must stay under a fixed share of tick time even at scale.

**GUI consistency.** Every new system gets exposed in the kingdom
book. No command-line-only features accumulating.

**Determinism audits.** Simulated outcomes must not depend on
player presence. Spot-check each phase by running simulated and
realized branches of the same event seed.

**NPC integration discipline.** Every kingdom feature that touches
NPCs goes through the existing component pattern. Never add fields
to TownspersonMob directly.

**Save-size budget.** Kingdom data is bounded per kingdom. House
trees pruned aggressively. Estate/charter/treaty records have hard
caps.

---

## 8. Open questions

These are unresolved and need decisions before relevant slices
begin. Each question gates the slice it's tagged to.

- **Event bus reuse vs peer.** Does `NpcLifeEventBus` cleanly
  support kingdom-scope events, or do kingdom events need a peer
  bus with the same scaffolding? *Gates: Slice 0.2.*

- **Culture extension granularity.** One growing `Culture` record
  vs. a separate `KingdomCultureExtension` looked up by culture id?
  Composition is cleaner long-term but adds an indirection.
  *Gates: Slice 0.1.*

- **Banner format.** Procedural-from-seed (deterministic, but every
  kingdom's banner looks algorithmic) or hand-authored pool with
  procedural fallback? *Gates: Slice 0.7.*

- **Heir trait inheritance.** Are heir traits inherited from
  parents (genealogical realism) or rolled fresh from the existing
  trait system at character generation? *Gates: Slice 5.4.*

- **Multi-kingdom player standing storage.** Per-kingdom map on
  player save, or kingdom-side claim against the player UUID?
  Affects how foreign-kingdom data persists when the player isn't
  online. *Gates: Slice 5.2.*

- **Province polygon update timing.** Recomputed when a manor
  changes hands, when stability changes drastically, or on a fixed
  schedule? *Gates: Slice 3.1.*

- **War shell scope.** Does the political shell in Slice 6.4 abstract
  battles entirely (resolve via score), or hand off to a stub that
  the future warfare session fills in? *Gates: Slice 6.4.*

---

## 9. Deferred work

Explicitly out of scope for the kingdom rework. Each item names a
later session that owns it.

| Item | Owned by |
|---|---|
| Battle/militia/siege mechanics | Warfare session |
| Trade route political overlay | Trade route rework |
| Lore content authoring | Lore session |
| JSON-driven custom cultures | NPC Phase 6 |
| JSON-driven custom government types | NPC Phase 6 |
| JSON-driven law packs | NPC Phase 6 |
| Modder hooks for nobility | NPC Phase 6 |
| Mod-compatible upkeep sources (mana, energy) | Mod compat session |
| Player-player apprenticeship in nobility | Multiplayer session |
| Multi-village outpost claims | Future expansion session |
| Kingdom-scale visitor flux beyond envoys | NPC follow-up |
| Culture drift over time | Long-term sim session |

---

## 10. Estimated scope

Roughly 40 slices across 8 phases. Down ~20-30% from the original
scope estimate because of how much the NPC and decoration reworks
absorb.

Phase 0 is bridge work — relatively light because most of what would
be foundational already exists. Phases 1-2 are the spine. Phases 3-5
are the bulk of player-facing features. Phase 6 closes the
simulation loop. Phase 7 is polish.

Phases cannot be parallelized cleanly. Within a phase, some slices
are independent and can swap order.

---

## Revision notes

(Changes recorded here as the plan evolves.)
