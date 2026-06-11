# Life in the Village — ROADMAP

**Human-managed.** Garrett owns this document; Claude does not edit it
without explicit permission. Last revised 2026-06-10.

**Structural convention:** every track is a **base → growth** arc. The
religion rework is the template — a finished base reliably sprouts features
beyond the initial plan, so "growth" lines are direction, not commitment, and
each track carries an implicit expansion budget. Dependencies are stated per
track. Decoration work is folded into whatever district/system pass it
belongs to (never a standalone track).

Open decisions are flagged ⚑ inline and collected in the register near the
end.

---

## Gate 0 — before new work

1. Consolidated in-world verification: layout 4c-a/#2/#3/4c-b, quest engine
   F2a/F2b-1, SR4 relics, liveliness L5/L6. Commit the `SacredSpaceRule` fix
   (drop the stray okhttp import); check `gradlew.bat` modification is
   intentional.
2. Doc replacement (Track J.1) — cheap, and everything after logs into the
   new structure.

---

## Track A — Layout & Districts *(active spine)*

**Base (the DISTRICT_ONLY_MODE arc):**
- A1. Residential variants — locked six: STREET_ROW (+tofts), COURTYARD,
  GRID_BLOCKS, CLUSTER, GREEN, TERRACE (attached-row NBT needed, or
  gap+shared-fence v1). HILLSIDE deferred. Test via `/litv district residential`.
- A2. Homestead toft tie-in (typed back-of-house plots read by the HOMESTEAD
  system).
- A3. Workshop completion — 4c-c lever choice ⚑ (cap bump ~132 / grouped
  craft cluster / one-sided row).
- A4. **Flip DISTRICT_ONLY_MODE off** + rural re-enable (relaxed farm gate:
  modest planning clearance, post-spawn flood-fill sizing); re-tighten the
  ViabilityValidator relaxation.
- A5. Full-village validation across tiers + regression seeds.
- A6. Stage-3e/4 teardown (compilable pass): migrate GATEWAY nucleus +
  designateHubs to ctx.gateways()/routed network, then delete NetworkPlanner
  recipe bodies, SpinePath, CrossStreet.

**Growth:**
- A7. District catalog: port/dock, garrison, farm hamlet, graveyard, festival
  ground — sequence by appetite ⚑. Each district pass carries its own
  decoration (lazy-per-district).
- A8. Monastery district — first non-village district (dummy-village pipeline
  + monastery roster); abbey = expansion over time (verify the
  village-expansion mechanism actually works first). Unblocks the religion
  parked tails (D4 per-faith aesthetics ride this too).
- A9. Capital district set (castle district, noble quarter, walls) — with
  Track C; existing castle gen is quarry, not foundation.
- A10. **Districts as sim units** — digest estimation from district
  composition (with D2).

**Depends:** A8 needs monastery NBTs (Track I); A9 needs Track C sequencing.

## Track B — Religion era-2 *(active)*

**Base:** B1. **F2b-2** — re-seat legacy guild player quests onto the F2 base
(next prompt, drafted direction exists). B2. Quest polish: journal UI,
consuming Deliver, Escort wiring, pilgrimage proximity-notify.

**Growth (sequence confirmed):** B3. Covenants/oaths → B4. Dynamism
(conversion/apostasy, schism/sects, founding, religious events) → B5. NPC-side
divine → B6. Fantasy arc: pantheons → **god entities (new feature — dedicated
design pass)** → Olympus void dimension → angels/demons (event-summoned
servants) → world tree → afterlife. Player religious career woven throughout
(the template for all professions' player arcs).
**Pause point:** after B2, the track can idle while C/D run; B3+ interleaves
freely.

## Track C — Kingdom rework *(the next major arc; co-designed with D)*

**Base:**
- C0. **Feasibility spike:** generator-backed terrain sampling
  (`ChunkGenerator.getBaseHeight` / climate sampler as a V2 `TerrainSource`
  impl) — cost at survey density, off-thread safety, worldgen-mod
  (Lithosphere) behavior. Headless-harness microbenchmark. Fallback exists
  (atlas guess + relocate-within-cell), so C1 proceeds either way.
- C1. **Charter generation** (design: archived `docs/archive/` notes folded
  here): kingdoms born abstract on atlas-cell claims; settlement charters
  (cell + role + size band + name + digest); graduated commitment charter →
  survey (exact anchor, load-free) → realization (existing
  `VillageRealisationSystem`, adaptation allowed, kingdom adopts the result);
  ready-band (~1–2k blocks) realized at world start; emergent formation in
  frontier cells. Defaults ≈ 3 kingdoms / 5000-block radius, configurable;
  frontiers via biome tags ⚑ (mechanism needs its own planning).
- C2. **State separation:** `Kingdom` → own SavedData; begin VillageSavedData
  decomposition (each rework extracts its slice; no big-bang).
- C3. Settlement portfolio + territory: wire/replace the dead placement fields
  (kingdomRoles/biomeAffinity/tradePriority/maxPerKingdom) as the
  charter-issuing logic; geography-driven borders; provinces on real
  territory.

**Growth:**
- C4. Capitals far grander than cities (with A9; charter-realization
  showcase).
- C5. Politics & diplomacy depth: intra-kingdom (nobles/factions/houses on the
  new stage) + inter-kingdom (trade/treaties/wars); distant kingdoms interact
  at digest fidelity.
- C6. Player arc: noble → office → ruler → founder; megaprojects
  (player-issued charters: roads, walls, monuments, settlements);
  reshape-the-map payoff.
- C7. Pregen support: `/litv pregen survey|realize <radius>` +
  realize-on-chunkgen toggle (Distant Horizons/Voxy worlds).
- C8. **Ruins & archaeology** (promoted): collapsed kingdoms + failed charters
  leave ruins — "realize as ruin" charter variant; ties the great-road
  old-realm lore (`OldRealmNamePool` realms become *findable*); vanilla
  archaeology integration (suspicious blocks + sherds with history-log
  inscriptions); region naming extends the same lore layer.

**Unlocks:** kingdom-system testing (provinces/diplomacy/laws) decoupled from
village spawn success; Track W (warfare). **Depends:** D (one design, two
faces).

## Track D — LOD / Sim Ledger *(co-designed with C)*

**Base:**
- D0. Quick fixes: kingdom-economy loop nested in per-village daily loop;
  population reads silently 0 for unloaded villages.
- D1. **Substrate** (ships early, standalone perf win): one `Relevance`
  predicate unifying the four realized/abstract idioms; tick-registry systems
  declare minimum relevance tier; brain cadence LOD (near = full brain, far =
  vitals at reduced cadence).
- D2. **Digest standard:** production/consumption/stock per category estimated
  from building roster — or district composition (A10);
  population/happiness/loyalty as resources; kingdom aggregates member
  digests; estimation rules for unknowns.
- D3. **Truth handoff contract** (one write-path per direction): sim is truth
  until realization; realization materializes the digest; realized NPC work is
  truth; unload checkpoints truth back and recalibrates sim rates from
  observed income.

**Growth:**
- D4. DORMANT tier + hydration (with C1 charters — same machinery from birth).
- D5. Sim-nudge pathing: far NPCs advance by schedule waypoints, full pathing
  near players (fixes big-city pathfinding cost + lost-NPC problem together).
- D6. Dormant demographics ⚑ (recommended: rates in the digest, individuals
  not simulated; persistence tiered by player relationship — known NPCs are
  record-level and consistent, background NPCs fungible; gap deaths → history
  log). Garrett's call pending.
- D7. **World events/disasters** (promoted): famine, harsh winter, bandit
  waves (plague exists) as digest-level events — work on DORMANT villages too;
  gives kingdoms problems to govern and rulers reasons to act.

## Track E — Professions, Players, Businesses, Offices, Households

**Base (continuing):**
- E1. Per-profession passes, NPC side first (current approach). Template =
  librarian: profession ↔ building(s) (standalone or embedded), real work loop
  with real blocks, player services, per-business policies under the
  **business → village law → kingdom law policy cascade** (reusable pattern).
- E2. Behavior gaps: HERALD, CHANCELLOR, GUILDMASTER (PriestBehavior-style
  embodiment).
- E3. Producer-seller as a real behavior (with F1 stall fix).

**Growth:**
- E4. **Player parity:** unified GUI system + employment channels (openings,
  tasks, wages — same channels NPCs use); job requests via the scholar/scribal
  system.
- E5. Companies → corporations: join/found businesses, buy/sell/build
  buildings, supply-demand balancing, multi-village → multi-kingdom (digests
  make remote branches cheap — D2).
- E6. **Offices model** (between E and C; design once, instantiate per scale —
  kingdom/village/business): required offices gate functionality (vacancy =
  succession crisis); upgrade offices unlock+improve; multi-office holders
  capped at reduced effective level.
- E7. **Households & lifecycle:** replace arbitrary spawn households with born
  → grow → employ → move out/migrate (traveller record transfer; realization
  grants/auto-builds the house); ties D6 demographics + A1 residential growth.
- E8. Dormant recipe unblocks (cake/pie, sugar, netherite, chiseled bookshelf)
  as their suppliers appear.
- E9. **Player skill progression** (RPG addition): player skills mirror the
  NPC skill axes, earned by doing profession work through the E4 employment
  channels — the leveling system IS the profession system (no separate class
  layer: professions + offices + races are the classes).
- E10. Promoted backlog: **player marriage/family** (rides E7 households +
  relationship ledger); **taverns/food depth** (innkeeper's E1 pass —
  meals/recipes as social glue); **children schooling** (scholar/scribe
  employment + child arc; feeds literacy/skills); **companions/hirelings**
  (hire adventurers/guards as followers — party + traveller machinery exists)
  ⚑ scope; **item quality/masterwork loot** (NPC masterpiece system
  generalizes to quality tiers on crafted goods) ⚑ scope.
- "Good & useful" bar settled empirically per profession pass; skills-first
  chores/hobbies keep weak professions contributing.

## Track F — Economy & Markets

**Base:** F1. Stall-inventory bug (merchants use market inventory, not their
stall's) + the TradeHandler→StallGoods consolidation + ⚑ do player stalls draw
hub stock. F2. Market-day stalls place wrong NBT / bypass the right system. F3.
Treasury cleanup (delete-or-wire VillageTreasury instance ledger; re-home live
constants).
**Growth:** F4. Economic GUIs: trade-route map, business graphs, market
displays (pairs with dynamic wall maps, Track I). F5. Alternate currencies
(minor): set list, per-kingdom, exchange rates; adjacent kingdoms often share;
bronze/silver/gold normalization is the base.

## Track G — Trade-Roads polish *(close; small list)*

G1. Slow road self-repair to a believable cadence. G2. Lantern re-stamp
idempotency (no more lantern towers). G3. Roadside decorations → NBTs
(authoring in Track I). G4. Simplify sea + hybrid routes (dispatcher reads only
first edge today). G5. Real boat pathfinding (currently teleport). G6. Stairway
primitive paint. G7. **Transportation depth** (promoted): player carts/wagons
on roads (speed bonuses exist), riding with caravans as fast-travel-with-risk.
Road event content rides Track C (patrols, tolls, convoys as kingdom
expressions); **adventure sites** (bandit camps, lairs, ruin POIs) ride C8 +
the road POI system + adventurer-guild Hunt/Explore objectives — the mod's
dungeon-adjacent layer.

## Track H — Client & Identity (appearance, clothing, races, cultures)

**Base:** H1. **Dynamic-texture feasibility spike** (MCA-style composited
per-NPC skins: runtime composition on NeoForge 1.21, caching, sync) — before
committing task-33 art direction. H2. Task 33 completion: renderer reads
Layer-1, sync packet, profile line (+ textures per spike outcome).
**Growth:** H3. Clothing — NPCs *and* players. H4. Improved NPC animations.
H5. **Races = cultures+** — race as a dimension above culture (inherited
traits: height, lifespan, tendencies; own models/appearance); racial
settlement archetypes (dwarven holds) become charter types (C1). H6.
Additional cultures (machinery for 4 exists; 1 content pack authored — mostly
Track I authoring + per-culture norms wiring from the audit's gap list).

## Track I — Content & Assets *(mostly Garrett-owned authoring)*

- NBTs: urban pack (22 manifests, 0 NBTs), MILLER/WAREHOUSE/WELL, monastery +
  abbey (A8), TERRACE attached-row, park accents, roadside decorations (G3),
  market-stall anchor check.
- Blocks/items: building blocks; **relics = component + a few dedicated items**
  (any item relic-capable; amulet/divine scepter carry world-story; display
  casing block); dynamic wall maps (live kingdom/trade/war state — with F4 +
  C); quest boards + notice-board upgrades (world-surface for the F2 quest base
  + request board).
- Task 34 content pass (dialogue, slants, tuning, QA) — after A4/A5 so tuning
  sees full villages.
- **Tutorial/onboarding + guidebook** (promoted): in-game scholar-written
  guidebook (book system exists; Tutorial package has 1 file) — the mod's
  depth badly outruns discoverability.
- **Archaeology assets** (C8): sherd patterns, ruin NBT variants, inscribed
  pottery.
- Cultures content (H6), god-entity assets when B6 opens.

## Track J — Hygiene, Docs, Verification

- J1. **Doc replacement (shipped 2026-06-10):** root set is now `STATE.md`
  (as-built, regenerated per milestone) + `ROADMAP.md` (this doc;
  human-managed) + `PROGRESS.md` (single append-only log) + `INVARIANTS.md`
  (roads invariants + firm constraints + locked decisions + districts-by-
  default + religion locks + sim-ledger principles) + revised `CLAUDE.md`.
  Everything else lives under `docs/archive/`. npc_redesign 33/34 content is
  absorbed into ROADMAP/STATE. Refresh/delete V1-era litv-* skills ⚑.
- J2. Dead-code sweep (STATE.md cleanup list): TownSquarePlacer, dispatchTick
  fields, guard goals, KingdomEventBus.addListener, stale comments/strings
  (CaravanMerchantGoal refs, VillageSpawner header, DecorationSlotEmitter,
  PhasedPlanner drop-log wording).
- J3. Goal→Brain consolidation (empty registrars, FARMHAND duplicate,
  stragglers).
- J4. Save-migration retirements (KingdomLaw legacy, PersonalityTrait,
  treasuries codec field) — new-world-per-test policy makes these free ⚑
  confirm.
- J5. Smoke-test cadence: every shipped arc ends with an in-world session
  before the next arc piles on (the Gate-0 debt should not recur).
- J6. **Config/balance surface** (promoted): hardcoded knobs (caps, radii,
  rates, kingdom count/radius per C1) → config layer; pre-empts the
  JSON-content era cheaply.

## Deferred ledger (explicitly not now)

- **Warfare** (Track W): after C — garrison districts (A7), road marches
  (caravan/travelling-group machinery), sieges at walls/castles. No design
  yet, by intent.
- **JSON-driven content:** after fully-playable test state + stable systems.
- **Mod integration:** after standalone-good.
- Smaller: HILLSIDE residential variant; satellite markets in huge cities;
  sea-accessible POIs; player sea routes; refugee settlement / envoy letters /
  multi-stop itineraries (visitor tails); brain-tick-LOD beyond D1 as needed.

## RPG completeness review (2026-06-10)

All backlog candidates **approved and promoted** into tracks (C8, D7, E9/E10,
G7, I, J6). RPG-gap dispositions:

- **Classes:** intentionally NO separate class system — professions + offices
  + races *are* the class layer; adding one would fight the mod's identity.
- **Stats/leveling:** absorbed as E9 player skill progression (skills-first,
  like NPCs); vanilla attributes suffice underneath.
- **Questlines:** the F2 quest base is the engine; authored multi-stage
  questlines (guild ranks, kingdom arcs, religion arcs) are its growth content,
  and the **grand quest / main story** (world-tree candidate) caps the
  religion fantasy arc (B6). Content-era work, base already built.
- **Magic:** the divine layer (favour/miracles/curses/theophany) IS the mod's
  first magic system. **Arcane magic = post-plan**, but design-hook now: build
  B6 (god entities, Olympus, world tree) and scholar/library research (E1) as
  the spine arcane can join later (a favour-like resource model, research as
  the player verbs). No arcane work in this plan.
- **Dungeons/exploration:** covered as adventure sites (G7) + ruins (C8) +
  road events — the overworld-RPG flavor fits better than instanced dungeons.
  Post-plan candidate: the Olympus/afterlife dimensions are where bespoke
  "dungeon" content naturally lives (B6).
- **Combat depth:** deferred with warfare (Track W) — player combat stays
  vanilla until armies exist; companions/hirelings (E10) are the interim combat
  feature.
- **Player legacy/heirs:** post-plan candidate — player death/inheritance
  riding the nobility succession machinery once the player arc (C6) matures.
- Verdict: no plan-breaking gap found; everything else named (magic, deep
  combat, grand questline content) is correctly *after* this plan, with its
  hooks built *during* it.

## Open decisions register ⚑

1. A3 workshop lever (4c-c: cap / cluster / one-sided row).
2. A7 district catalog order.
3. C1 frontier mechanism (biome tags + what else).
4. D6 dormant demographics (recommendation on file).
5. F1 player stalls draw hub stock?
6. J1 V1-era skills: refresh vs delete.
7. J4 save-migration deletions: confirm policy.
8. E6 offices: standalone arc vs folded into C.
9. E10 scopes: companions/hirelings; item quality/masterwork tiers.

## Near-term sequence (first moves)

1. **Gate 0** verification sweep + working-tree commits.
2. **J1** doc replacement. *(shipped 2026-06-10)*
3. Parallel: **A1** residential variants ‖ **B1** F2b-2 (separate subsystems,
   prompts ready).
4. **D0+D1** LOD substrate (standalone perf win) ‖ **C0** sampling spike
   (read-only).
5. **A3–A5** complete the full-village milestone.
6. **C1** charter generation (the kingdom arc proper opens).
7. Then by appetite: E2/E3, F1–F3, H1 spike, J2/J3 — all parallel-friendly
   fillers between the big arcs.
