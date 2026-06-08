# Priest / Religion profession — progress log

## Foundation — PriestBehavior (officiate + produce + bless)

Makes PRIEST a working profession: it physically embodies the abstract
Religion subsystem instead of idling. Foundation only — the broader
religion rework (chapel/temple/monastery specialization, monk profession,
multi-religion, new ceremonies) is a separate future effort; this is built
specialization-aware (branch on building type) with identical branches for
now.

### Rite-officiating seam (marquee + riskiest — no double-application)

`RiteExecutor.runDue` (daily) previously applied a rite abstractly whenever
its priest was a *loaded* entity (and SKIPPED when unloaded). That is
exactly the double-apply risk once a priest also performs physically.

Fix — a realized-priest defer gate inside `runOne`:
- `runOne` gained a `deferToRealizedPriest` flag. `runDue` passes `true`;
  `runImmediate` (debug command + PriestBehavior on completion) passes
  `false`.
- When `true` and the rite's presiding priest resolves to a loaded PRIEST
  entity (`TownspersonMob.findByUUID(...)`) within `OFFICIATE_GRACE_TICKS`
  (1 day), `runOne` returns PENDING — leaving the rite for the behavior to
  perform physically. After the grace lapses (priest loaded but unable —
  off-work / unreachable venue), `runDue` falls back to applying it
  abstractly so a rite can't starve.
- `runImmediate` bypasses the gate (the caller IS performing the rite) and
  reuses the existing per-rite handlers + marking + +5 SOCIAL XP — effect
  logic is NOT re-implemented.

`PriestBehavior` (IDLE → WALKING_TO_RITE → OFFICIATING):
- `findClaimableRite` scans `RiteSavedData.dueRites(now)` for a PENDING rite
  in this priest's village whose presider is this priest or vacant.
- **Claims** it by persisting `withPresider(me)` → `runDue` defers (realized
  presider) and other priests skip it.
- Walks to `rite.location()`, officiates for `OFFICIATE_TICKS` (look + swing
  + "Officiating <rite>" text), then re-fetches by `riteId` and — only if
  still PENDING (race guard vs another priest / the grace fallback) — calls
  `RiteExecutor.runImmediate(current, level)` to apply + mark done.

### Production phase

When no rite: produce temple goods from `ProfessionSupplyChain` PRIEST
(inputs WHITE_CANDLE/PAPER/GOLD_INGOT → outputs WHITE_CANDLE/BOOK/
GOLDEN_APPLE/EXPERIENCE_BOTTLE). Tick-budget loop: consume one available
input via `BuildingStorageAccess.takeItem`, deposit one rotated output via
`NpcBehaviorHelpers.depositToBuilding`, award small SOCIAL XP. Mirrors
HealerBehavior's PRODUCING phase.

### Blessing aura

`blessNearby` — a cooldowned (`BLESS_INTERVAL` ~30s), bounded
(`BLESS_RADIUS` 12) mood bump (`MoodTrigger.GIFT_RECEIVED`, small magnitude)
to nearby NPCs. No per-tick world scan (gated by a cooldown field). Runs in
`tick()` while the priest is actively working.

### Specialization stub

`kindOf(building)` → TEMPLE/CHAPEL/SHRINE/OTHER; `analyze` switches on it
with identical arms (the seam the religion rework extends).

### Registration + work-satisfied signal

Registered in `ProfessionBrainFactory` (PRIEST → WORK @0, like HEALER/
INNKEEPER). `goIdle` SETS `NO_ACTIONABLE_WORK` (no expiry) only when there's
no rite AND no production, cleared at real-work-start (analyze), never wiped
in `stop()` — exactly the L1-fix lifecycle, so the idle director fills a
truly-idle priest. A Farmer/Miner-style `idleCooldown` bounds the
re-analyze rate so the rite-ledger / storage reads aren't per-tick.

### Tie-In Audit

- Upstream: `RiteSavedData`/`RiteExecutor`, `ProfessionSupplyChain`, the
  priest's building, `OfficeRegistry.VILLAGE_PRIEST` (via the rite's
  presider / `findPriest`).
- Downstream: the realized-priest gate is the no-double-apply guarantee —
  verified the only `runOne` callers (`runDue` true, `runImmediate` false)
  pass the flag. The three player verbs (blessing/offering/confession)
  schedule rites as PENDING RiteExecutions; a loaded priest now physically
  officiates them (emergent win) and the verb effects still land via the
  unchanged handlers.
- Siblings: idle director fills via the work-satisfied signal; greeting
  still works (PriestBehavior is WORK @0, greet is WORK @0 added earlier
  and pre-empts via GREET_TARGET). Abstract handler EFFECTS unchanged —
  only WHO triggers them and WHEN.
- Switches: new `Phase` + `TempleKind` (every arm handled); the `Rite`
  switch in `runOne` is reused, the behavior's `riteLabel` switch covers
  all 10 rites.

### Memory safety

No new brain memory — PriestBehavior writes only `WALK_TARGET` and reads/
sets `NO_ACTIONABLE_WORK`, both already in `TownspersonMob.brainMemories()`.
No L1-fix2 freeze risk.

### Deviations / flagged

- Branch: developed on `claude/optimistic-cray-MDhGQ` (the working branch
  carrying L1–L4), per the firm session instruction not to push elsewhere
  without explicit permission — the prompt's "new working branch" line was
  a placeholder.
- Blessing aura runs only while the behavior is active (officiating/
  producing); a fully-idle priest (director strolling) doesn't bless.
  Foundation limitation — flagged for a future move to a CORE/always-on
  hook.
- Two-loaded-priests-in-one-village rite race is guarded by the persisted
  claim (presider=me) + the re-fetch-PENDING check before `runImmediate`;
  the rare residual edge is flagged.
- Out of scope (left as-is, per prompt): building-specialization
  differentiation, monk profession/spec, multi-religion, new rites, the
  stubbed blessing 24h skill-buff and tithe-failure path, new building
  types.

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (confirmed neoform-runtime
offline). Static review: PriestBehavior + RiteExecutor + ProfessionBrainFactory
balanced; both `runOne` callers pass the new flag; no new/unregistered brain
memory; rite effects reused via `runImmediate`. Runtime-sensitive — wants an
in-game check.

### Smoke test

1. Priest in a TEMPLE with a due rite: walks to the rite location and
   performs it (`/liv npc brain` shows PriestBehavior, "Officiating <rite>");
   effects apply ONCE on completion (not also abstractly).
2. Player requests blessing/offering/confession: the priest physically
   officiates the scheduled rite; verb effects still land.
3. No due rite: priest produces temple goods (candles/books) — outputs
   appear in temple storage.
4. Nearby NPCs get a periodic small mood bump (blessing aura), throttled.
5. Unloaded village: rites still resolve abstractly via runDue (after the
   grace) — off-screen system intact.
6. Truly idle priest (no rite, no inputs): idle director fills (strolls/
   tidies) via the work-satisfied signal.
7. No movement freeze / brain-tick error (no new memory); /tick no
   per-tick regression.

---

## Religion Rework — Phase R1a: Rite capability model (tiers + skill/office gating)

Establishes the capability foundation later religion phases gate through:
*skills are what an NPC can do; profession/office are what they actually
do.* A brand-new priest can run routine devotions; a seated village
priest can run village-wide ceremonies. The change is the gate only —
no specializations, orders, initiation, attendance, or economy (those
are later phases).

### Disposition (investigation — tree verified, summaries confirmed)

Fast-forwarded onto `origin/main` (55 commits) first: the realized-priest
machinery the prompt assumes (`PriestBehavior`,
`RiteExecutor.runOne(deferToRealizedPriest)`, `OFFICIATE_GRACE_TICKS`) had
landed since the branch point and the prompt's summaries now match the
code. Verified in-tree before coding:
- `Rite` — 10 types (codec by `name()`); exhaustive `Rite` switches live
  in `RiteExecutor.runOne` and `PriestBehavior.riteLabel` (both untouched);
  `RiteLifeEventProducer` does not switch on `Rite`.
- `RiteExecutor.runOne(rite, rdata, level, deferToRealizedPriest)` is the
  single per-rite-effect site; `runDue` passes `true`, `runImmediate`
  (debug command + `PriestBehavior` completion) passes `false`.
- `PriestBehavior.findClaimableRite` claims a due rite whose presider is
  self-or-vacant; the seated-priest path resolves via
  `OfficeRegistry.VILLAGE_PRIEST` (`RiteExecutor.findPriest`).
- Skill read: `npc.getSkills().getLevel(Skill)` (same levels
  `SkillRequirement` gates on); PRIEST primary/secondary = SOCIAL/LITERACY
  (`ProfessionSkills`); `SkillXp.award` is the one XP funnel.
- Offices: `VILLAGE_PRIEST` competence band SOCIAL 30–70, eligibility
  SOCIAL 30 + LITERACY 30; `TEMPLE_HIGH_PRIEST` band SOCIAL 50–85. The
  "holds office X in its village?" query is
  `village.getOffices().get(id).filter(h -> h.isHeldBy(uuid))`.

### Tier enum + Rite→tier mapping (proposed → shipped)

`RiteTier { MINOR(5), STANDARD(10), GRAND(15) }` — ordinals are the
capability ladder, the float is the SOCIAL XP award (see XP below).
Derived mapping `RiteTier.tierOf(Rite)` (the only exhaustive `Rite`
consumer added — no codec/persistence change, tier is a function of
type):
- MINOR — BLESSING, OFFERING, TITHE, CONFESSION
- STANDARD — NAMING, MARRIAGE, FUNERAL, COMING_OF_AGE
- GRAND — FEAST_DAY, HARVEST_THANKSGIVING

**No KINGDOM tier** — no rite maps there this phase; omitted per the "no
speculative enums" rule. The kingdom-rites phase adds the value and the
high-priest raise together.

### The gate (`RiteCapability.canOfficiate(TownspersonMob, Rite)`)

One canonical helper both performance paths call. An officiant's cap is
the higher of skill and office:
- **Skill** — SOCIAL (the PRIEST competence axis) gates the tier;
  LITERACY ≥ `READ_LITERACY_THRESHOLD` (30) is the secondary
  "can read the liturgy" requirement for STANDARD/GRAND (MINOR needs
  neither). Thresholds added to `SkillThresholds`:
  `RITE_STANDARD_SOCIAL = 30` (mirrors VILLAGE_PRIEST competence floor)
  and `RITE_GRAND_SOCIAL = 50` (mirrors TEMPLE_HIGH_PRIEST floor) — not
  fresh magic numbers, anchored to the office bands and parked in the
  canonical skill-threshold file. A non-office priest who meets the
  office's skill bar (SOCIAL 30 + LITERACY 30) can run STANDARD rites
  even before being seated.
- **Office** — holding `VILLAGE_PRIEST` in the officiant's village
  raises the cap to GRAND regardless of skill, so a freshly-seated
  priest is naturally qualified for the ceremonies the seat exists to
  run. Lookup is a single map-get on the village `OfficeState`.

`canOfficiate(rite) = tierOf(rite).ordinal() <= capOf(officiant).ordinal()`.

### Wiring (single helper, both paths)

- `PriestBehavior.findClaimableRite` — added `if (!canOfficiate(entity,
  r.type())) continue;`. An over-tier rite is left unclaimed for a
  qualified officiant (the realized path).
- `RiteExecutor.runOne` — compute `capable = priest != null &&
  canOfficiate(priest, type)`. The realized-priest defer now also
  requires `capable` (don't defer to a priest who'll never claim it,
  which would starve the rite). The no-priest edge branch fires on
  `priest == null || !capable`, so an unstaffable rite waits (MARRIAGE
  14-day defer) or SKIPs rather than applying abstractly. Existing
  realized-vs-abstract gate intact; no double-apply.

### Tier-scaled XP

The single officiation XP site in `runOne` now awards
`RiteTier.tierOf(type).socialXp()` (MINOR 5 / STANDARD 10 / GRAND 15)
instead of flat 5 — MINOR keeps the prior value, harder rites progress
faster once qualified. Both paths route through here (`runImmediate`
calls `runOne`). No outcome-quality / success-chance scaling (flagged
for a later content phase).

### Tie-In Audit

- **Upstream feeders** — `RiteScheduler.schedule` / `RiteLifeEventProducer`
  create rites with an empty presider; the gate runs at claim/perform
  time, not creation, so unstaffable rites still queue and wait.
- **Downstream callers** — `RiteExecutor.runImmediate` (debug command +
  PriestBehavior completion) and `runDue` both funnel through `runOne`;
  both get the capability gate with no signature change.
  `PriestBehavior.findClaimableRite` is the only `findClaimableRite`
  caller. The three player verbs (blessing/offering/confession) schedule
  MINOR-tier rites — performable by any priest, so unaffected.
- **Sibling systems** — Offices: the `VILLAGE_PRIEST` holding query
  already exists and is a cheap map-get (no walk). Skills: read-only
  `getLevel`, plus the existing `SkillXp.award` funnel (mentorship /
  ambition multipliers still apply).
- **Exhaustive switches** — `RiteTier.tierOf` is the only exhaustive
  `Rite` switch added; `RiteTier` itself has no `switch` consumer
  (cap comparison is by ordinal, XP by field). Existing `Rite` switches
  (`RiteExecutor.runOne`, `PriestBehavior.riteLabel`) untouched.

### Simplification Sweep

Classes in scope: `Rite` (untouched), `RiteExecutor` (gate + XP scale),
`PriestBehavior` (claim filter), plus new `RiteTier` / `RiteCapability`.
No orphans introduced; the gate is one helper, not two open-coded checks.
`PriestBehavior.TempleKind` stub intentionally retained (the next phase
differentiates building types) — not dead.

### Memory safety

No new brain `MemoryModuleType` — the gate lives in the claim filter and
the abstract resolver, not in any memory write. No freeze risk.

### Deviations from prompt

- **No KINGDOM tier / no TEMPLE_HIGH_PRIEST → KINGDOM raise.** Section 2
  describes a high-priest branch raising the cap to KINGDOM, but (a) no
  rite maps to KINGDOM this phase (the prompt itself says leave it out
  with no consumer) and (b) temple offices are stubbed in v1
  (`OfficeRegistry.findOfficesHeldBy` produces no temple matches), so a
  seated high priest is not resolvable yet. Both are deferred to the
  kingdom-rites phase, which adds the tier and the branch together. A
  high priest who also holds the village's `VILLAGE_PRIEST` seat still
  reaches GRAND today.
- **Threshold constants added to `SkillThresholds`** rather than reusing
  an existing one verbatim — there was no existing 30/50 SOCIAL gate. The
  two new constants mirror the office competence floors and live in the
  canonical threshold file, consistent with that file's role.

### Out-of-scope but flagged

- Specializations / orders / initiation / apprenticeship, building-type
  differentiation, attendance/congregation, religion economy — later
  phases (the `TempleKind` seam is ready).
- Outcome-quality / success-chance scaling by tier — flagged for a later
  content phase; this phase scales XP only.
- Clearing an incapable explicit presider so `findPriest` can re-resolve
  a capable office holder: in normal flow a presider is only ever a
  capable priest (PriestBehavior now claims capability-filtered; the
  office holder always reaches GRAND), so the `!capable && priest != null`
  branch is a legacy/defensive path that resolves via the existing
  14-day-defer-then-SKIP edge rule. Left as-is.

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (neoform-runtime 2.0.18
POM returns 403). Static review: `RiteTier` covers all 10 rites; new
switch is the only exhaustive `Rite` consumer added; `RiteCapability`
resolves SOCIAL/LITERACY via `getSkills().getLevel` and the office via
the village `OfficeState`; both `runOne` callers unchanged in signature;
XP funnels through the single `SkillXp.award` site; no new brain memory.
Runtime-sensitive (office seating, skill levels) — wants an in-game check.

### Smoke test

1. `/litv spawn` a TEMPLE + a low-skill PRIEST (SOCIAL/LITERACY < 30),
   not seated as `village_priest`.
2. `/religion rite BLESSING <npcUuid>` — MINOR: the low-skill priest
   walks to the temple and performs it; effects land once.
3. `/religion rite MARRIAGE <npcUuid>` — STANDARD: with SOCIAL/LITERACY
   below 30 the priest leaves it unclaimed; it waits (MARRIAGE defers up
   to 14 days) rather than applying.
4. `/religion rite FEAST_DAY <npcUuid>` — GRAND: left unclaimed by the
   low-skill, unseated priest; an unstaffed village resolves it via the
   no-priest edge rule (SKIPPED) rather than applying abstractly.
5. Raise the priest: `/litv ... skill SOCIAL 60` (or seat it as
   `village_priest`); re-run FEAST_DAY — it now performs the GRAND rite.
6. Confirm tier-scaled XP: officiating a GRAND rite grants more SOCIAL XP
   (+15) than a MINOR one (+5) — check `/liv npc` skill readout deltas.
7. No movement freeze / brain-tick error (no new memory); `/tick` shows
   no per-tick regression (the gate is a map-get + two skill reads at
   claim time, behind the existing `idleCooldown`).

---

## Religion Rework — Phase R1b: Building-role differentiation + chapel/shrine staffing + specialization seam

Builds on R1a (the rite-capability gate). This phase differentiates what
a priest does by which religious building they staff, makes chapels and
shrines actually staffed, and lays the priest specialization seam the
content phase (religion-specific orders) will extend. Scope is those
three things only — no concrete orders, no rite-venue relocation, no
initiation/apprenticeship, no layout/placement work.

### Disposition (investigation — tree verified, two mismatches reported)

Verified in-tree before coding; two prompt summaries did NOT match and
are handled accordingly:

- **`BuildingType.forProfession` does not exist** — the actual mapping is
  `Profession.professionFor(BuildingType)`, and it already returns PRIEST
  for `TEMPLE, CHAPEL, SHRINE` (Profession.java:146). So the profession
  side needs no change; only the inhabitant specs were missing.
- **The "existing inhabitant-spawn locked-spec assignment path … as used
  for shepherd/beekeeper" does not exist in the tree.** Grepping every
  `.assign(`, `setLocked(true)`, and `FARMER_SHEPHERD/BEEKEEPER` write
  site: the only locked-spec assignment is the `/liv npc lockspec` admin
  command and the combat-role/legacy-NBT paths. `VillageInhabitantPopulator`
  and `FarmRoleAssigner` only *read* locked specs; nothing auto-assigns a
  locked spec at spawn. The `NpcSpecializationComponent` javadoc ("Locks
  are set by BuildingInhabitantRegistry initial spawn assignment") is
  aspirational — that code was never written. So shepherd/beekeeper specs
  are operator-set today, not worldgen-set. **This phase adds the first
  real spawn-time assignment route**, centralized in the specialization
  registry (not open-coded per-profession) so it is the single canonical
  path future professions/orders reuse.

Confirmed accurate: `PriestBehavior.TempleKind` (TEMPLE/CHAPEL/SHRINE/
OTHER) + `kindOf` + the identical-arms `analyze()` switch (the seam);
`findClaimableRite` gated by `RiteCapability` (R1a); `RiteTier.tierOf`;
`BuildingInhabitantSpec` builder API (`.worker/.resident/.household/
.workerHousehold/.build`); TEMPLE entry `builder().worker(PRIEST).build()`;
`NpcSpecializationTypes` register/`defaultFor` + `FARMER_MIXED`/
`ADVENTURER_ROOKIE` generalist pattern; `NpcSpecializationComponent.assign(
def, owner, force)` + `setLocked`; `SpecializationGate.qualifies`.

### What shipped

**1. Building-role differentiation — rite-claim preference (`PriestBehavior`).**
Replaced the dead identical-arms `analyze()` switch. `findClaimableRite`
now scans all due / claimable / capability-permitted rites in the
priest's village and picks the best by a building-kind preference rank
(`tierPreferenceRank(TempleKind, RiteTier)`), lowest rank first, ties
keeping the earliest-due rite:
- TEMPLE → GRAND (0), STANDARD (1), MINOR (2)
- CHAPEL → STANDARD (0), MINOR (1), GRAND (2)
- SHRINE → MINOR (0), STANDARD (1), GRAND (2)
- OTHER → 0 for all (earliest-due wins — exactly the prior behaviour)

This is preference ordering, NOT a second gate: R1a `canOfficiate`
remains the only hard limit, so a lone qualified priest still performs
everything (the single candidate is trivially "best"). Still the single
claim path — `analyze` claims the chosen rite via `withPresider(me)` as
before; no second claim route, no duplicated capability check.

**2. Chapel & shrine staffing (`BuildingInhabitantRegistry`).** Added
`CHAPEL → worker(PRIEST)` and `SHRINE → worker(PRIEST)`. A manually
spawned chapel/shrine now populates a priest. Both use a single PRIEST
worker (needs separate housing, mirroring TEMPLE) — the simplest correct
staffing; building-kind differentiation lives in the claim preference,
not in distinct inhabitant shapes.

**3. Specialization seam (generalist only).**
- Registered `PRIEST_CLERIC` (`lit:priest/cleric`, Profession.PRIEST,
  `isGeneralist=true`, no requirements, `SpecializationData.None`) —
  mirrors `FARMER_MIXED`.
- Added `NpcSpecializationTypes.assignInitialSpawnSpec(npc, profession)`
  — the canonical spawn-time route. Driven by an opt-in set
  `LOCK_GENERALIST_AT_SPAWN = { PRIEST }`; for opted-in professions it
  assigns the generalist via the canonical component API
  (`assign(force=true)` + `setLocked(true)`), no-op otherwise. Called once
  per spawned NPC from `VillageInhabitantPopulator.spawnNpcInBuilding`
  (after `changeProfession`, which does not touch specialization).
- `PriestBehavior.readOrderSeam()` resolves the spec id each `analyze`
  (behind `idleCooldown`, never per-tick) and DEBUG-logs only on change.
  Generalist is a no-op today; the content phase branches behaviour here.

### Tie-In Audit

- **Upstream feeders** — `Profession.professionFor` already maps CHAPEL/
  SHRINE/TEMPLE → PRIEST (untouched). The populator now runs for CHAPEL/
  SHRINE (new specs) and calls `assignInitialSpawnSpec` for every spawned
  NPC; only PRIEST acts on it.
- **Downstream callers** — `findClaimableRite` has one caller (`analyze`);
  selection logic changed, claim path unchanged. Single-priest case does
  not regress (best-of-one = the one). OTHER-kind buildings reproduce the
  prior first-due pick exactly (all ranks 0, stable tie-break).
- **Sibling systems** — Specialization: the locked generalist is scoped
  to PRIEST, which has no auto-promotion path, so it cannot block
  `trySetSpecialization` (the BLACKSMITH/FARMER bias-not-gate auto-promote
  flow). Farmers are deliberately excluded from the opt-in set, so
  `FarmRoleAssigner`'s locked-spec pinning sees no new locks and is
  unchanged. R1a gate: preference layers on top, never bypasses it.
- **Exhaustive switches** — new `tierPreferenceRank` switches over both
  `TempleKind` (4 arms) and `RiteTier` (3 arms) — all covered, compiler-
  enforced. `kindOf`'s `BuildingType` switch unchanged. The `Rite`
  switches (`RiteExecutor.runOne`, `PriestBehavior.riteLabel`,
  `RiteLifeEventProducer`) are untouched.

### Simplification Sweep

Classes in scope: `PriestBehavior` (differentiation now real — the
`TempleKind` stub is no longer a placeholder; removed the dead identical-
arms switch), `NpcSpecializationTypes` (+priest spec, +canonical spawn
route), `BuildingInhabitantRegistry` (+2 entries), `VillageInhabitant
Populator` (+1 call). No orphans introduced; the spawn-assignment route is
centralized in one helper rather than open-coded at the call site, so it
is the single path, not a parallel mechanism.

### Memory safety

No new brain `MemoryModuleType`. `PriestBehavior` still writes only
`WALK_TARGET` and reads/sets `NO_ACTIONABLE_WORK` (both already in
`TownspersonMob.brainMemories()`). No freeze risk.

### Deviations from prompt

- **No reusable spawn-time locked-spec path existed to reuse** (see
  disposition). Rather than open-code a priest special-case in the
  populator, added `NpcSpecializationTypes.assignInitialSpawnSpec` as the
  canonical, opt-in route (currently PRIEST-only) and called it once from
  the populator. This *is* the "single path, no parallel mechanism" the
  constraint intends — it just had to be created, not reused.
- **`BuildingType.forProfession` → `Profession.professionFor`** naming
  mismatch; the real API already maps CHAPEL/SHRINE/TEMPLE → PRIEST, so no
  profession-side change was needed.
- **Order seam is a DEBUG-log-on-change read**, not a no-op, so the seam
  is demonstrably exercised; analyze runs behind `idleCooldown`, so no
  hot-loop spam.

### Out-of-scope but flagged

- Concrete religion-specific **orders/branches** — content/multi-religion
  phase; they register as gated `PRIEST_*` siblings and assign over the
  locked generalist with `force=true`.
- **Rite-venue relocation** (chapel physically hosting a wedding) —
  congregation/attendance phase. This phase changes which priest
  claims/prefers a rite, not where it is located.
- Initiation rite + apprenticeship arc — R1c.
- **Non-populator priest hires don't yet get the generalist spec.** Only
  the worldgen/manual populator path calls `assignInitialSpawnSpec`; a
  priest created by `VillageLeaderBehavior.assignProfessions` (leader hires
  an unemployed NPC into PRIEST) spawns spec-less. The order seam no-ops
  gracefully (null id → skip). Wiring the leader-hire / career-change path
  is deferred — out of this phase's "buildings spawned manually" scope.
- Multi-priest claim race (two priests picking the same rite before either
  persists the claim) is unchanged from R1a — preference *reduces* it
  (different kinds prefer different tiers) but the persisted-claim +
  re-fetch-PENDING guard remains the backstop.

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (neoform-runtime POM 403);
all in-game testing is the user's. Static review: `tierPreferenceRank`
covers all TempleKind×RiteTier arms (compiler-exhaustive); `PRIEST_CLERIC`
registered before `assignInitialSpawnSpec` reads it via `defaultFor`;
populator call sits after `changeProfession` (which leaves specialization
untouched); locked generalist scoped to PRIEST so no auto-promotion
regression; no new brain memory; `RiteTier` import added, `Identifier`/
`LoggerFactory` used fully-qualified.

### Smoke test

1. Manually spawn a TEMPLE, a CHAPEL, and a SHRINE in one village (e.g.
   `/liv` building spawn or world-edit the building types), then trigger
   population. Confirm each building now spawns a PRIEST.
2. `/liv npc` (or the spec readout) on each new priest → confirms the
   `lit:priest/cleric` specialization, **locked**.
3. Seat/skill the priests so each is qualified for the relevant tiers
   (e.g. seat the temple priest as `village_priest` for GRAND; ensure
   SOCIAL/LITERACY ≥ 30 for STANDARD), so capability is not the variable
   under test.
4. Schedule a mix in that village: `/religion rite FEAST_DAY <p>` (GRAND),
   `/religion rite MARRIAGE <p>` (STANDARD), `/religion rite BLESSING <p>`
   (MINOR). Confirm the TEMPLE priest claims FEAST_DAY first, the CHAPEL
   priest takes the MARRIAGE life-event, and the SHRINE priest takes the
   BLESSING — `/liv npc brain` shows "Officiating <rite>" per priest.
5. Remove all but one priest (e.g. only the SHRINE priest, made GRAND-
   qualified) and re-schedule the GRAND rite: confirm the lone qualified
   priest still performs it — preference, not a gate.
6. No movement freeze / brain-tick error (no new memory); `/tick` shows no
   per-tick regression (preference is a bounded scan behind `idleCooldown`).

---

## Religion Rework — Phase R1c: Ordination / initiation rite

Adds the ordination rite — the officiated ceremony by which a PRIEST-
profession NPC formally becomes clergy. Double duty: gives "joining the
priesthood" a real on-theme ceremony, and becomes the canonical hook
that assigns the clergy specialization, closing the R1b gap (leader-
hired / non-populator priests never received the generalist spec). Scope
is the rite + its handler + the daily trigger; no apprenticeship, no
religion-specific orders, no player verb.

### Disposition (investigation — tree verified, findings)

- **Exhaustive `Rite` switches** = exactly three: `RiteTier.tierOf`,
  `RiteExecutor.runOne` (`switch (rite.type())`), `PriestBehavior.riteLabel`.
  All three updated. `ReligionDebugCommand` iterates `Rite.values()` for
  its `/religion rite <type>` suggestions, so ORDINATION is auto-listed —
  no command change. `RiteLifeEventProducer` is an `if/else if` chain on
  *event* type (not a `Rite` switch) and is life-event-driven; ordination
  is profession-driven, so it correctly needs no arm there. Switch
  expressions are compiler-exhaustive, so a missed arm fails the build.
- **`Religion.ritualises(Rite)`** filters per-religion rite sets, but only
  `RiteLifeEventProducer` and `scheduleCalendarRites` consult it;
  `RiteScheduler.schedule` does not. Scheduling ORDINATION directly
  therefore bypasses the per-religion filter — correct, since ordination
  is universal/profession-driven, not a per-religion ritual choice.
  `ReligionRegistry` rite lists left untouched (no religion needs to
  "opt in" to ordaining its own clergy).
- **`RiteSavedData` is never pruned** — completed/SKIPPED rites accumulate
  in the map forever. This shaped the trigger design (below): a
  no-officiant ordination that resolves to SKIPPED would re-schedule daily
  and churn the un-pruned ledger, so the trigger gates on a capable
  officiant being present instead of scheduling unconditionally.
- **How an NPC becomes `Profession.PRIEST`**: (a) the populator
  (`assignInitialSpawnSpec` runs → spawns already-ordained), and (b)
  `CareerTransitions.changeProfession` via `VillageLeaderBehavior`
  leader-hire / career change (no spec assigned — the R1b gap). The
  trigger covers (b).
- Confirmed `NpcSpecializationTypes.assignInitialSpawnSpec` (opt-in set
  `{PRIEST}`, assigns the locked `PRIEST_CLERIC` generalist via
  `assign(force=true)`+`setLocked`) and `PriestBehavior.findClaimableRite`
  (claims by tier preference within the R1a gate) / `readOrderSeam`.

### What shipped

**1. `Rite.ORDINATION`** — appended (codec round-trips by `name()`, so
pre-R1c saves load clean). Tier = **STANDARD** in `RiteTier.tierOf` (a
seated village priest can ordain new clergy; routine life-event-style
ceremony, not a GRAND village-wide one). `PriestBehavior.riteLabel` →
"an ordination".

**2. `RiteExecutor.handleOrdination`** — on success, for the ordinand
(first participant): assigns the locked clergy spec via the canonical
route (`NpcSpecializationTypes.assignInitialSpawnSpec(ordinand,
ordinand.getProfession())` — the SAME path the populator uses, idempotent,
no third assignment mechanism); then piety +0.10 toward primary religion,
mood +20 (`GIFT_FAVORITE`), an `OFFICIATED_BY` memory naming the
officiant, and a +10 relationship with the officiant — mirroring
`handleComingOfAge`. The officiant still earns tier-scaled SOCIAL XP
(STANDARD → +10) through the unchanged R1a XP site. Self-ordination
fallback: if officiant == ordinand, still ordain but skip the
self-referential relationship/memory writes.

**3. Daily ordination trigger (`RiteScheduler.scheduleOrdinations`)** —
runs once per daily tick (after `runDue`/calendar). One bounded pass over
loaded entities groups PRIEST NPCs by village; for each village that has
at least one priest able to officiate an ORDINATION, every un-ordained
priest (no clergy spec) with no pending ordination gets an ORDINATION
scheduled with a vacant presider, performed through the normal R1a/R1b
claim path. "Ordained" = presence of any PRIEST-profession specialization
(no new persistent field — forward-compatible with future orders).

### Tie-In Audit

- **Upstream feeders** — both `Profession.PRIEST` paths covered: the
  populator pre-ordains (spec assigned at spawn), and the trigger catches
  the leader-hire / `changeProfession` gap (un-ordained → scheduled
  ordination). The trigger reads profession + spec; it does not itself set
  profession.
- **Downstream callers** — the three `Rite` switch consumers updated;
  `readOrderSeam` / `SpecializationGate` read the clergy spec (now set via
  the ceremony as well as spawn) and are unaffected by the new value;
  `RiteScheduler.dailyTick` is the sole driver and gained the pass;
  `RiteScheduler.schedule` reused unchanged.
- **Sibling systems** — Specialization: the assignment is idempotent
  (`force=true`), so re-running on an already-specced NPC is a no-op-equiv;
  the trigger excludes already-ordained NPCs anyway. Piety/mood/memory/
  relationships: reuse the existing component APIs and `OFFICIATED_BY`
  memory type — no new memory type, no new mood trigger.
- **Exhaustive switches** — `Rite` (3, all updated, compiler-checked);
  `RiteTier` unchanged (ORDINATION maps into the existing STANDARD arm).

### Simplification Sweep

Religion + specialization classes in scope: `Rite` (+1 value), `RiteTier`
(+1 arm), `RiteExecutor` (+1 handler), `RiteScheduler` (+ trigger),
`PriestBehavior` (+1 label). **Overlap noted and intentionally kept as two
entry points to the same single assignment route**: the populator's
`assignInitialSpawnSpec` (worldgen priests spawn pre-ordained, so a
founding temple always has a senior officiant — avoids a bootstrap with no
one to officiate) and `handleOrdination` (ceremony-ordained, for the gap
cases). Both call the identical `assignInitialSpawnSpec` helper — one
assignment mechanism, two triggers. They do not consolidate: pre-ordaining
founders at spawn is deliberately not a scheduled ceremony (no senior
would exist to perform it). No orphans introduced.

### Memory safety

No new brain `MemoryModuleType`. The trigger writes only to the rite
ledger (`RiteSavedData`) and reads entity state; the handler writes
piety/mood/relationship/`NpcMemory` (the narrative memory ledger, not a
brain memory module). No `brainMemories()` change → no freeze risk.

### Deviations from prompt

- **Trigger gates on a capable officiant present**, rather than scheduling
  unconditionally and leaning on the no-priest SKIP edge. The prompt
  endorses the SKIP-edge approach, but `RiteSavedData` never prunes, so an
  unconditional schedule would churn the ledger with a fresh SKIPPED
  ordination every day for any un-ordained priest with no officiant.
  Gating yields the same user-visible behaviour ("waits until a senior is
  present") without the leak.
- **Self-ordination is allowed as a fallback** (a lone capable priest with
  no separate senior ordains themselves), rather than forbidding it and
  risking a never-ordained priest. The handler suppresses the self-
  referential relationship/memory in that case. With populator pre-
  ordination this path is rare. Forbidding self-claim would mean special-
  casing the shared `findClaimableRite` path, which the constraints
  discourage; left out.
- **`ReligionRegistry` rite lists untouched** — ordination is universal,
  not a per-religion opt-in, and `schedule` doesn't consult `ritualises`.

### Out-of-scope but flagged

- **Clergy apprenticeship** (mentor-accelerated training, initiate→
  journeyman→master ladder, PRIEST masterpiece) — R1d; `Npc/Apprentice/`
  untouched this phase.
- **Religion-specific orders / multiple specializations** — content/multi-
  religion phase; they register as gated `PRIEST_*` siblings and assign
  over the locked generalist with `force=true`. `isOrdained` already treats
  any PRIEST-profession spec as ordained, so it is forward-compatible.
- **Player-commissioned ordination verb / GUI** — player phase.
- **Senior-officiates-not-self preference**: when both a senior and a
  capable un-ordained ordinand are loaded, whichever priest's
  `PriestBehavior` runs first claims; the ordinand could self-officiate
  (still correctly ordained, minus the self-skipped relationship/memory).
  A claim-side "don't ordain yourself when a senior exists" preference is
  deferred (would touch the shared claim path).
- **Ledger pruning** of completed/SKIPPED rites is a pre-existing
  `RiteSavedData` characteristic, not introduced here; flagged for a future
  housekeeping pass (one SUCCESSFUL ordination per priest persists — small,
  bounded).

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (neoform-runtime POM 403,
build fails before compilation). Static review: all three `Rite` switches
carry an ORDINATION arm (compiler-exhaustive); `handleOrdination` reuses
the canonical spec route + existing effect APIs; the trigger is a daily,
bounded, single entity pass gated on an available officiant + pending-
dedup; no new brain memory; no codec field added (ordained = spec
presence); imports (`TownspersonMob`, `Profession`, `Map`/`HashMap`/
`ArrayList`) added to `RiteScheduler`. Runtime-sensitive (spawn paths,
claim timing) — wants an in-game check.

### Smoke test

1. Spawn a TEMPLE with a senior PRIEST (populator path). `/liv npc` (spec
   readout) → confirm the senior already carries the **locked**
   `lit:priest/cleric` spec (pre-ordained at spawn).
2. Create a NEW priest in the same village via the leader-hire / career-
   change path (e.g. let the village leader hire an unemployed adult into
   a CHAPEL, or `/`-set their profession to PRIEST). Confirm they start
   **without** the clergy spec (un-ordained).
3. Advance ~1 day (`/time add 24000` or wait a daily tick). Confirm an
   ORDINATION is scheduled for the new priest (e.g. `/religion`-side
   inspection or the priest's brain showing "Officiating an ordination"
   on the senior).
4. Confirm the SENIOR priest claims + officiates it (capability-gated,
   STANDARD tier). Afterward: the new priest carries the locked
   `priest/cleric` spec, gained piety/mood, has an "ordained me into the
   clergy" memory naming the senior, and a relationship bump with them.
5. Manual fast-path: `/religion rite ORDINATION <newPriestUuid>` schedules
   it immediately for a hands-on check.
6. No-officiant case: in a village whose only priest is an un-ordained,
   ORDINATION-incapable NPC (low SOCIAL/LITERACY, no seat), confirm NO
   ordination is scheduled (no ledger churn) and nothing mis-applies; once
   a qualified priest is present, the ordination schedules and completes.
7. No movement freeze / brain-tick error (no new brain memory); `/tick`
   shows no per-tick regression (the trigger is one bounded daily pass).

---

## Religion Rework — Phase R1d: Clergy apprenticeship (the initiate→priest arc)

Final R1 sub-phase. Makes the mentored training arc real for clergy: a
freshly-ordained low-skill priest trains under a senior, learning faster
and climbing the `ApprenticeRank` ladder until qualified (via the R1a
gate) for higher-tier rites. Wiring + content + surfacing only — the
apprenticeship machinery (`Npc/Apprentice/`) is already profession-
agnostic.

### Disposition (investigation — tree verified; one premise was already satisfied)

- **`ApprenticeshipManager.startContract`** seeds the +30 mutual relation,
  calls `CareerTransitions.changeProfession(apprentice → master's
  profession)`, **reassigns the apprentice to the master's building**
  (`apprentice.assignToBuilding(masterBuilding, ...)`), and assigns
  MASTER_OF / APPRENTICE_TO role projections. The building reassignment is
  the de-staffing risk the guard addresses.
- **`changeProfession` is a verified no-op for same→same**: `if (from ==
  to) return Accepted // no-op` (CareerTransitions:65). The ordinand is
  already PRIEST and `findMaster` only returns PRIEST masters, so the
  profession-change side effect is inert; only the building reassignment +
  relationship/roles fire. It also does NOT touch the specialization, so
  the R1c clergy spec survives the contract start.
- **`ApprenticeshipDispatcher` fires only on `LifeStageAdvanced` → ADULT**
  (Dispatcher:26-27). A mid-life ordination (leader hire / conversion of
  an already-adult NPC) never re-emits ADULT, so those priests are missed
  — exactly the gap this trigger fills.
- **`findMaster`** filters villagers to `profession == preferred` (the
  candidate's own PRIEST), `skill(primary=SOCIAL) ≥ MASTER_SKILL_THRESHOLD`
  (70), same village, assigned-building present, under the
  MAX_APPRENTICES_PER_MASTER (2) cap; ranks by relationship + skill +
  LifeGoal. The R1c ordination already bumps ordinand→officiant +10
  relationship, which biases `findMaster` toward the ordaining senior —
  so "the ordaining senior is the natural mentor" emerges without forcing
  a specific master.
- **`ApprenticeRank.fromSkillLevel`**: APPRENTICE 0–40, JOURNEYMAN 40–75,
  MASTER 75+.
- **`PriestBehavior` does NOT extend `AbstractProductionBehavior`** (it is
  `extends Behavior<TownspersonMob>`), so it doesn't inherit that base
  class's XP hook. **But task 2's premise is already satisfied another
  way**: the mentorship multiplier is NOT applied in
  `AbstractProductionBehavior` — that base class's `awardProductionXp`
  simply calls `SkillXp.award`. The multiplier lives **inside the single
  `SkillXp.award` funnel** (Phase 6.3.2.b), which calls
  `MentorshipBonus.npcMentorshipFor` → `ApprenticeshipManager
  .mentorshipMultiplierFor`. Since every PriestBehavior XP grant
  (production `SkillXp.award`, and officiation via `RiteExecutor.runOne`'s
  `SkillXp.award`) already flows through that funnel, **the mentorship
  bonus already reaches the apprentice priest**. Multiplying again in
  PriestBehavior would double-apply (1.5×1.5) and violate "no parallel
  XP-bonus path." See Deviations.

### What shipped

**1. Clergy-apprenticeship trigger (`RiteExecutor.tryFormClergyApprenticeship`,
called from `handleOrdination`).** Mirrors the ADULT dispatcher's exact
orchestration — `ApprenticeshipSavedData.getByApprentice` dedup →
`ApprenticeshipMatcher.findMaster` → `masterAccepts` →
`ApprenticeshipManager.startContract` → `ApprenticeshipContractFactory
.queueViaScribe` — with two religion-specific gates:
- **Junior-only**: skip unless the ordinand is APPRENTICE rank (SOCIAL <
  40). A JOURNEYMAN+ priest qualifies solo via the R1a gate.
- **De-staffing guard**: skip when `ordinand.getAssignedBuildingId()` is
  present and differs from the master's building (forming would yank the
  ordinand off a chapel/shrine they staff). Allowed when the ordinand has
  no building yet or already shares the master's. The un-mentored junior
  still grows solo. No forked contract path.

**2. Mentorship XP — already applied; no code (see Disposition).** Task 2
is satisfied by the existing single `SkillXp.award` funnel, which both of
PriestBehavior's XP sites already use. Deliberately did NOT add a second
multiplier (would double-apply).

**3. PRIEST masterpiece** — `masterpieceTargetFor` PRIEST →
`minecraft:golden_apple` (was the "emerald" default). A blessed priest-
supply-chain output reading as a consecration piece, distinct from the
scribes' written book. Descriptor only; the pass condition stays skill-
based (SOCIAL at `MASTERPIECE_PASS_SKILL`).

**4. Rank surfacing** — `PriestBehavior.clergyTitle()` derives a cosmetic
label from SOCIAL via `ApprenticeRank.fromSkillLevel` (Initiate / Priest
/ Senior Priest) and prefixes the priest's activity text ("Initiate:
Officiating a marriage", "Senior Priest: Preparing temple goods"); also
appended to the `readOrderSeam` DEBUG-on-change line. No persistent rank
field — derived from skill, computed for display only.

### Tie-In Audit

- **Upstream feeders** — `handleOrdination` is the hook (event-driven, not
  per-tick); `findMaster` eligibility filters to PRIEST masters via
  `ProfessionSkills.PRIEST = (SOCIAL, LITERACY)` → primary SOCIAL, matching
  the rank gate's axis.
- **Downstream callers** — `startContract` side effects: profession-change
  is a confirmed no-op (PRIEST→PRIEST); building reassignment is guarded;
  relationship seed / role projections / scribe commission fire as for any
  contract. The weekly tick (`ApprenticeshipManager.weeklyTick`) and
  graduation path already handle clergy generically (primary skill SOCIAL
  via `ProfessionSkills`), so milestones/masterpiece/graduation work with
  no religion-specific change. Graduation's mood/history broadcast
  (`fireApprenticeshipGraduation`) is profession-agnostic.
- **Sibling systems** — Specialization: the locked clergy spec assigned in
  R1c is untouched by `changeProfession` (same→same no-op) and does not
  block the apprentice path (the apprentice path reads skills/roles, not
  the spec). Roles: MASTER_OF/APPRENTICE_TO projections assigned/cleared by
  the existing manager. R1a gate: the accelerated SOCIAL growth feeds
  `RiteCapability` so a trained priest naturally unlocks higher tiers — the
  intended arc.
- **Exhaustive switches** — no new enum. New switch over the *existing*
  `ApprenticeRank` (3 arms, exhaustive, compiler-checked) in `clergyTitle`.
  The `masterpieceTargetFor` switch over `Profession` has a `default`, so
  adding the PRIEST arm is safe. No `Rite`/`RiteTier` change.

### Simplification Sweep

Classes in scope: `RiteExecutor` (+ trigger helper), `ApprenticeshipManager`
(+1 masterpiece arm), `PriestBehavior` (+ rank label). The new clergy
trigger does **not** overlap the ADULT-transition dispatcher — different
life moment (ordination vs. coming-of-age), and both guard with the same
`getByApprentice` dedup so a priest can't double-contract. Both call the
identical `startContract`/`findMaster`/`queueViaScribe` path — one
mechanism, two entry points (ADULT for cradle-raised, ordination for
mid-life clergy). No orphans; no parallel XP/contract path introduced
(task 2 explicitly reused the existing funnel rather than adding one).

### Memory safety

No new brain `MemoryModuleType`. The trigger writes the apprenticeship
ledger + `NpcMemory`/relationships/roles via existing manager APIs;
`clergyTitle` is display-only. No `brainMemories()` change → no freeze
risk.

### Deviations from prompt

- **Task 2 (apply `mentorshipMultiplierFor` in PriestBehavior) shipped as
  a no-op with justification.** The prompt's premise — that PriestBehavior
  lacks the multiplier because it doesn't extend `AbstractProductionBehavior`
  — does not hold in the current tree: the multiplier is applied centrally
  inside `SkillXp.award` (the single funnel), which both PriestBehavior XP
  sites already use. Adding the multiplier in PriestBehavior would
  double-apply it. The requested outcome ("apprentice priest learns faster
  near master") is already in effect. Verified `AbstractProductionBehavior
  .awardProductionXp` also just delegates to `SkillXp.award` (it does not
  multiply), confirming the single-funnel model.
- **Master selection via `findMaster`, not the literal officiant.** The
  prompt called the ordaining senior "the natural mentor"; `findMaster`
  reuses the full matcher (correct skill bar, crowd cap, village filter)
  and — thanks to the R1c +10 ordination relationship bump — preferentially
  returns that same senior. This is stronger reuse than hard-coding the
  officiant (who might officiate via office-seat below the
  MASTER_SKILL_THRESHOLD and not qualify as a master).

### Out-of-scope but flagged

- Religion-specific orders / multiple specializations — content phase.
- No change to R1c ordination effects or the R1a gate — this layers on top.
- Player-as-apprentice clergy flows / GUI — player phase.
- Apprenticeship graduation-placement (where a graduate is re-assigned) —
  pre-existing system concern, deliberately not redesigned here.
- De-staffing guard is conservative: a chapel/shrine priest who ordains is
  simply not mentored (grows solo) rather than relocating the building's
  staffing. A future "visiting mentor" model (mentor without relocation)
  could lift this, but that needs the apprenticeship system to support a
  non-co-located contract — out of scope.
- `MASTER_SKILL_THRESHOLD` (70) means a village needs a genuinely senior
  priest before any initiate can be mentored; founders are pre-ordained
  (R1c populator path) and grow into that role. Lone-young-temple villages
  simply have un-mentored initiates until a senior emerges — acceptable.

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (neoform-runtime POM 403;
build fails before compilation). Static review: trigger reuses the
matcher/manager verbatim (same calls as `ApprenticeshipDispatcher`) with a
junior-rank gate + de-staffing guard; `changeProfession` same→same no-op
confirmed in source; `clergyTitle` switch is `ApprenticeRank`-exhaustive;
PRIEST masterpiece arm sits under an existing `default`; mentorship is the
pre-existing single-funnel path (no double-apply); `ApprenticeRank`
imported in PriestBehavior, Apprentice classes fully-qualified in
RiteExecutor; no new brain memory, no new persistent field, no enum added.
Runtime-sensitive (master co-location, contract timing) — wants an in-game
check.

### Smoke test

1. Spawn a TEMPLE with a senior PRIEST: high SOCIAL (≥ 70, MASTER rank).
   `/liv npc` → activity text reads "Senior Priest: ..."; confirm they
   carry the locked clergy spec (pre-ordained at spawn).
2. Create a NEW low-SOCIAL priest (< 40) in the same village via leader-
   hire / career-change (un-ordained, no clergy spec, no building or the
   temple's building).
3. Trigger their ordination (R1c daily pass, or `/religion rite ORDINATION
   <newUuid>`). On completion confirm: the new priest gains the clergy spec
   AND an apprenticeship contract forms with the senior as master (server
   log "[Apprenticeship] ... apprentice of ..."; `/liv npc` shows
   APPRENTICE_TO role).
4. With the apprentice working near the master (same temple), confirm its
   officiation/production SOCIAL XP is multiplied ~1.5× vs. a priest with
   no master present (the existing `SkillXp.award` mentorship funnel).
5. Confirm the rank label: "Initiate" while SOCIAL < 40, advancing to
   "Priest" at 40 and "Senior Priest" at 75.
6. De-staffing guard: give a CHAPEL its own priest (staffing that chapel),
   then ordain them while the temple senior is present — confirm NO
   contract forms (they are not yanked to the temple; the chapel stays
   staffed) and they still officiate solo.
7. Graduation: raise the apprentice's SOCIAL across 40/55/70/75 (or let it
   grow); confirm the existing weekly tick advances milestones, assigns the
   golden-apple masterpiece at milestone 4, and graduates at
   MASTERPIECE_PASS_SKILL — contract COMPLETED, +20 relationship, pinned
   "Graduated under ..." memory.
8. No movement freeze / brain-tick error (no new brain memory); `/tick`
   shows no per-tick regression (the trigger runs once at ordination).

---

## Religion Rework — Phase R2a: Shared event abstraction + ceremony dedup

R2's foundation: make ceremonies located, attended community events, and
first reconcile the TWO parallel systems that currently model the same
ceremonies (so R2b's attend behavior can be built against one contract).

### Disposition (the two systems, verified end to end)

- **`Npc/Religion/`** — `RiteExecution` (record: riteId, type,
  presidingPriestId, participantIds, location, scheduledTick,
  completedTick, outcome, villageId; 9 codec fields), stored in
  `RiteSavedData` (a `Map<UUID,RiteExecution>`, never pruned), scheduled by
  `RiteScheduler` (calendar holy-day rites via `scheduleCalendarRites`) and
  formerly by `RiteLifeEventProducer` (life-event rites), effects applied by
  `RiteExecutor`.
- **`Village/Event/`** — `VillageEvent` (class: id, villageId, EventType[33],
  EventStatus ANNOUNCED→ACTIVE→ENDED/CANCELLED/DISRUPTED, start/end ticks,
  location, primarySubjectId, required/invited/actual attendees, eventData
  String-map, decorations; 14 codec fields), stored in
  `VillageSavedData.events`, ticked by `VillageEventScheduler.tickEvents`
  (→ `EventEffects` → `EventAttendance` sets/clears `eventOverride`),
  scheduled by `VillageEventScheduler` (cultural holy days via
  `checkCulturalHolyDay`; life events via `EventLifeEventProducer`).

**Confirmed double-firing.** Both `RiteLifeEventProducer` (bus line 68) and
`EventLifeEventProducer` (bus line 85) listen to the SAME `NpcLifeEvent`s:
a marriage fired BOTH a `Rite.MARRIAGE` and a `VillageEvent.WEDDING`,
uncoordinated (EventLifeEventProducer's own javadoc admitted it). Holy days
likewise: `checkCulturalHolyDay` made a cultural `VillageEvent` while
`scheduleCalendarRites` independently made a `FEAST_DAY`/`HARVEST` rite off
the religion calendar.

**Grounded facts that shaped the design:**
- All four religions ritualise `FEAST_DAY`; HARVEST_THANKSGIVING / MARRIAGE
  / COMING_OF_AGE are religion-specific. So a uniform `ritualises` gate on
  blessing rites reproduces both old producers' gating.
- `RiteSavedData` is never pruned → avoid any path that re-creates churn.
- `Village.Event` already depends on `Npc.Religion` (EventHandlerRegistry);
  `Npc.Religion` does NOT depend on `Village.Event` — so the abstraction
  must NOT introduce a `Religion → Event` import (cycle), which dictated a
  neutral package.

### Design (presented before implementation)

**1. Abstraction shape — an interface, in a neutral package.**
`Village/Gathering/CommunityGathering` (+ `GatheringStatus`
SCHEDULED/ACTIVE/COMPLETED/CANCELLED) depends on neither subsystem, so both
implement it with no package cycle and future kingdom/military gatherings
can adopt it. Contract: `gatheringId, villageId, gatheringLocation
(Optional), startTick, endTick, gatheringStatus, required/invited/actual
Attendees, primarySubjectId`, plus a default `isActiveAt(tick)` (covers a
rite's derived active window, since rites have no explicit ACTIVE state).
- `VillageEvent implements CommunityGathering` via NEW delegating methods
  alongside the existing getters (no getter renamed). Status maps
  ANNOUNCED→SCHEDULED, ACTIVE→ACTIVE, ENDED→COMPLETED, CANCELLED/DISRUPTED→
  CANCELLED.
- `RiteExecution implements CommunityGathering` with everything DERIVED from
  existing fields — **no new persisted field, codec unchanged**: location
  (ZERO→empty), startTick=scheduledTick, endTick=scheduledTick+1200 (derived
  window), required=participantIds, invited/actual=empty, primarySubject=
  first participant, status PENDING→SCHEDULED / SUCCESSFUL→COMPLETED /
  DISRUPTED|SKIPPED→CANCELLED. (`villageId()` is already the record accessor
  and satisfies the interface directly.)

**2. Registries stay separate; one query helper unions them.**
`Village/Event/CommunityGatherings` (`inVillage` / `activeInVillage` /
`activeNear`) walks BOTH stores and returns `List<CommunityGathering>`.
R2b builds the attend behavior against this — it never touches either store
directly. The helper lives on the event side (it depends on both stores);
the INTERFACE stays dependency-free.

**3. Dedup — the gathering owns; the rite is its blessing-extension.**
A single coordination point, `Village/Event/CeremonyBlessings.attach`,
maps a gathering type → its blessing `Rite` (WEDDING→MARRIAGE, FUNERAL→
FUNERAL, NAMING_CEREMONY→NAMING, COMING_OF_AGE→COMING_OF_AGE, HARVEST_
FESTIVAL→HARVEST_THANKSGIVING, the four cultural holy-day types→FEAST_DAY,
everything else→none), and on gathering creation schedules that rite
(vacant presider, co-timed to the gathering start, gated by
`RiteScheduler.villageRitualises`) and links it into the gathering's
`eventData["riteId"]`. It is wired into the TWO `VillageEventScheduler`
creation methods (`scheduleEvent` + `scheduleLifeEvent`), so EVERY gathering
is blessed at creation through one path. The two independent rite producers
are then removed (below).

### What shipped

- **New** `Village/Gathering/CommunityGathering` + `GatheringStatus`
  (neutral, dependency-free).
- `VillageEvent` and `RiteExecution` now `implement CommunityGathering`
  (delegating / derived; no renames, no codec change).
- **New** `Village/Event/CommunityGatherings` union query helper.
- **New** `Village/Event/CeremonyBlessings` coordination point; hooked into
  `VillageEventScheduler.scheduleEvent` and `.scheduleLifeEvent`.
- `RiteScheduler`: added `scheduleBlessingRite` (returns the rite id for the
  link) + `villageRitualises`; **removed** `scheduleCalendarRites` and its
  daily-tick call (holy-day rites now flow through `CeremonyBlessings`).
- **Deleted** `RiteLifeEventProducer` and removed its `NpcLifeEventBus`
  registration (life-event rites now flow through `CeremonyBlessings`).
- Updated the two stale javadocs that referenced the deleted producer.

### Tie-In Audit

- **Upstream feeders** — `EventLifeEventProducer` (unchanged: it calls
  `scheduleLifeEvent`, which now also attaches the blessing) is the sole
  life-event ceremony creator; `checkCulturalHolyDay` + the seasonal /
  random / crisis rolls all funnel through `scheduleEvent`, which attaches.
  `RiteLifeEventProducer` (deleted) and `scheduleCalendarRites` (deleted) no
  longer create independent rites. Each ceremony is created once now.
- **Downstream callers** — readers of `VillageEvent` (`EventEffects`,
  `EventAttendance`, `eventOverride`, dialogue, plague) and `RiteExecution`
  (`RiteExecutor`, `PriestBehavior.findClaimableRite`) are UNCHANGED: the
  interface only ADDS methods; no existing accessor or field changed. The
  rite still has a vacant presider, still gets claimed/officiated, still
  applies `RiteExecutor` effects. `/religion rite` debug still uses the
  unchanged `RiteScheduler.schedule`.
- **Sibling systems** — the holy-day overlap is resolved by making the
  cultural-holy-day / seasonal-harvest gathering the owner and attaching the
  rite (religion-calendar independent rites removed). `eventOverride`
  schedule resolution is untouched (R2b adds the attend behavior).
- **Exhaustive switches** — new `GatheringStatus` has only the two mapping
  switches I added (over `EventStatus` 5-arm and `RiteOutcome` 4-arm, both
  exhaustive). `blessingRiteFor` switches over `EventType` with a `default`
  (new event types safely map to no blessing). No `Rite`/`EventType` value
  added.

### Simplification Sweep

In scope: `RiteScheduler` (−`scheduleCalendarRites`, +blessing helpers),
`VillageEventScheduler` (+2 attach calls), `EventLifeEventProducer`
(unchanged behavior; doc updated), `RiteLifeEventProducer` (DELETED),
`VillageEvent` / `RiteExecution` (+interface), plus new
`CommunityGathering` / `GatheringStatus` / `CommunityGatherings` /
`CeremonyBlessings`. **Consolidated:** the two life-event producers → one
(EventLifeEventProducer + CeremonyBlessings); the two holy-day paths → one
(gathering creation + CeremonyBlessings). **Stays separate (by design):**
`RiteSavedData` and the `VillageSavedData` event store. No orphans left
(deleted producer fully removed, including its bus line).

### Memory safety

No new brain `MemoryModuleType` (the attend behavior is R2b). The link is a
plain `eventData` string entry; no `brainMemories()` change → no freeze risk.

### Deviations from prompt

- **Interface placed in a NEW neutral package** `Village/Gathering/` rather
  than in `Village/Event/`. Required to avoid a `Npc.Religion → Village.Event`
  package cycle (RiteExecution would otherwise import the event package while
  the event package imports religion). The neutral home also matches the
  prompt's "clean enough to serve future kingdom/military gatherings."
- **`RiteExecution` gained NO new field.** The prompt allowed optional new
  fields (end/duration, invited/actual) under the codec cap; none were
  needed — all are derivable, so the most surgical choice (zero codec change)
  was taken. Invited/actual attendees for a rite return empty (a rite borrows
  its gathering's lists; village-wide rites compute attendance at execute
  time) — R2b decides how rite-gatherings populate live attendees.
- **Holy-day timing re-sourced.** Holy-day rites were previously driven by
  the religion calendar (`isHolyDay`, named days like "Harvest Equinox");
  they are now the blessing of the cultural-holy-day `VillageEvent` (culture
  `holyDayInterval`) and the seasonal HARVEST_FESTIVAL. This is the
  consolidation the prompt asked for (one coordinated holy-day path), but it
  does shift WHEN holy observances fire (culture cadence, not religion
  calendar). Flagged as an intentional behavior change.
- **Dedup is "linked + coordinated," not "single artifact."** A wedding still
  yields a WEDDING gathering AND a MARRIAGE rite — but now coordinated (the
  rite is the gathering's blessing, co-timed, linked by id, created through
  one path), which is exactly what the prompt's smoke test asks for ("not a
  separate event AND a separate rite running uncoordinated").

### Out-of-scope but flagged

- The physical **attend-event behavior** (walk to venue, linger) → R2b, built
  on `CommunityGatherings` + `CommunityGathering`.
- Attendance-affects-outcome tuning, festivals/processions → later phases.
- `RiteExecutor` effect handlers untouched (the rite keeps its officiant /
  effect layer).
- `RiteSavedData` still never prunes completed rites (pre-existing); the
  linked blessing rites add to that ledger like any rite. Flagged for a
  future housekeeping pass; not introduced here.
- A rite→gathering BACK-link (rite carrying its gathering id) was not added
  (no R2a consumer needs it; the gathering→rite `eventData` link suffices).
  R2b can add it if the attend behavior needs reverse lookup.

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (neoform-runtime POM 403;
build fails before javac). Static review: both types implement every
interface method (records auto-satisfy `villageId()`); status maps are
enum-exhaustive; `blessingRiteFor` has a `default`; no `Religion → Event`
import (neutral interface package, verified); deleted producer has no
remaining code references (only historical comments); `distSqr(Vec3i)`,
`getVillageCentre`, `RiteSavedData.all()` confirmed against existing usage.
Runtime-sensitive (scheduler timing, claim path) — wants an in-game check.

### Smoke test

1. Marry two NPCs in a village with a temple + priest. Confirm exactly ONE
   coordinated ceremony: a `WEDDING` gathering whose `eventData["riteId"]`
   points at a `MARRIAGE` rite (check `/event` + rite debug) — NOT a separate
   uncoordinated WEDDING event and MARRIAGE rite. The MARRIAGE rite's
   scheduledTick equals the gathering's startTick.
2. Confirm the priest still claims + officiates the MARRIAGE rite and
   `RiteExecutor` effects still apply (mood/memory/relationship as before).
3. Advance to a cultural holy day (or seasonal autumn for HARVEST_FESTIVAL).
   Confirm ONE coordinated observance: the holy-day / harvest `VillageEvent`
   with a linked `FEAST_DAY` / `HARVEST_THANKSGIVING` rite — not a duplicate
   event + independent calendar rite.
4. Confirm a religion that doesn't ritualise the rite (e.g. The Loom +
   COMING_OF_AGE) produces the gathering but NO blessing rite (no
   `eventData["riteId"]`).
5. Confirm both kinds expose location + attendees through the interface: a
   debug union (`CommunityGatherings.activeInVillage`) lists the active
   WEDDING gathering AND any active rite with their `gatheringStatus`,
   `gatheringLocation`, `requiredAttendees`.
6. Confirm pure `VillageEvent`s are unaffected: a MARKET_DAY / VILLAGE_FAIR
   schedules and runs with no attached rite, no error.
7. No movement freeze / brain-tick error (no new brain memory); `/tick` shows
   no per-tick regression.

---

## Religion Rework — Phase R2b: General attend-gathering behavior

Completes the R2 congregation foundation: the visible payoff. R2a unified
the gathering contract + deduped ceremonies but nothing physically walked
NPCs to a gathering. This phase builds the GENERAL behavior that gathers
attendees to the venue for ALL gathering kinds, with religion (the priest
officiating where the congregation meets) as the first beneficiary.

### Disposition (R2a artifacts verified; TWO premise mismatches reported)

- `CommunityGathering` (Village/Gathering) + `CommunityGatherings`
  (Village/Event — it depends on both stores; the interface stays neutral)
  are as R2a shipped: `gatheringLocation()`, `startTick/endTick`,
  `gatheringStatus`, required/invited/actualAttendees, `isActiveAt(tick)`;
  `activeInVillage(level, villageId, tick)` is the union query.
- `EventAttendance` (unchanged) sets `eventOverride` + adds to
  `actualAttendees` on a gathering going ACTIVE, clears on end.
- `NpcMemoryTypes.UPCOMING_EVENT_TARGET` (UUID) / `EVENT_ATTENDANCE_POS`
  (GlobalPos) exist and were forward-declared with **no existing consumer**
  — nothing half-built to consolidate; and they were NOT in
  `brainMemories()` (the freeze trap).
- `GatherAtSquareBehavior` is the model (walk-to + linger, gated on
  `WALK_TARGET` absent + `GATHER_COOLDOWN` + `BrainNavGuard`, resolve once
  per pick).

**Mismatch 1 — the eventOverride→LEISURE collapse was never wired.** The
prompt (and `ScheduleResolver`'s own class header) state that an
eventOverride collapses `WORK_*` into LEISURE so attendees land in
`Activity.IDLE`. In the actual tree `ScheduleResolver.phaseAt` does NOT
consult `eventOverride` at all — only the legacy `TownspersonMob.isWorkTime()`
reads it, and only for 4 festival types, gating WORK behaviors (not the
brain Activity). The brain Activity is driven by `NpcSchedules.tick →
ScheduleResolver.phaseAt → activityFor` (WORK→`Activity.WORK`,
MEAL/SOCIAL→`SOCIAL`, LEISURE→`IDLE`). So an attendee stayed in their normal
Activity and the IDLE-hosted behavior would never fire. **R2b had to wire
the collapse** (the precondition) — see deliverables.

**Mismatch 2 — attendee population is asymmetric.** Three sources stamp
`eventOverride`: (a) `EventAttendance.applyOverrides` for life events
(required+invited, also into `actualAttendees`); (b) the 5 Phase-3 festival
start handlers in `EventEffects` set it village-wide (NO `actualAttendees`);
(c) holy-day religious events (SUNSTEAD_EQUINOX / LOOM_THREADING /
TIDECALL_FULL_MOON / FORGE_CREED_KINGDOM_DAY) set it for NOBODY (created
attendee-less; their handler stamps nothing). So gating on `actualAttendees`
membership would miss festivals entirely, and holy days would gather no one.

### What shipped

**1. Registered both event memories in `brainMemories()`** (the freeze
trap) — `UPCOMING_EVENT_TARGET` + `EVENT_ATTENDANCE_POS`, with the standard
warning comment. Done before the behavior writes them.

**2. Wired the eventOverride→LEISURE collapse** (`ScheduleResolver.phaseAt`):
`if (npc.isEventTime() && base.isWork()) base = LEISURE`. Collapses WORK_*
only — MEAL / SOCIAL / sleep are preserved so attendees still eat and rest
(a multi-day festival otherwise starves them). The officiating priest is not
an attendee (no override) → keeps WORK → officiates. This is the
long-documented collapse the header promised but `phaseAt` never applied.

**3. `AttendGatheringBehavior` (general)** — modelled on
`GatherAtSquareBehavior`. Gate: `isEventTime()` + `BrainNavGuard` +
(`WALK_TARGET` absent + `GATHER_COOLDOWN` absent via the memory map) + find
the active `VillageEvent` gathering whose type matches the NPC's
`eventOverride` (the **general** signal — works for festivals that set the
override without `actualAttendees`) + the NPC is not its presider. Resolves
the venue once (order below), sets `UPCOMING_EVENT_TARGET` = gathering id and
`EVENT_ATTENDANCE_POS` = GlobalPos(venue), walks to a spot within
`LINGER_RADIUS` (spread + re-cluster), sets activity text, and re-approaches
on the reused `GATHER_COOLDOWN` throttle until the gathering ends
(`EventAttendance.clearOverrides` clears the override → `isEventTime()`
false). Registered HIGH in `Activity.IDLE` (after greet/house/shelter,
above all ambient idle) AND in `SOCIAL` (above the ambient square-gather,
below eat/converse/court/mentor) so MEAL/SOCIAL-phase attendees gather too.

**4. Convergence** — venue resolution order: (1) the gathering's pinned
`gatheringLocation()`; (2) for a gathering with a linked rite
(`eventData["riteId"]` → `RiteSavedData`), the **rite's location** — the
temple, the SAME point `PriestBehavior` walks the presider to; (3) the town
square (`HobbyLocationResolver.TOWN_SQUARE`) / village centre. The first
attendee writes the resolved venue back onto a location-less `VillageEvent`
so everyone (and future consumers) agree. Result: priest + congregation meet
at the temple for weddings and holy days.

**5. Holy-day congregation** — `EventAttendance.applyVillageWideOverride`
(new) stamps the override + `actualAttendees` on every village NPC **except
priests** (they officiate, not attend); called from `EventEffects.onEventStart`
for RELIGIOUS_RITE-category events with no explicit attendee list. Mirrors
the Phase-3 festival pattern so holy days actually gather villagers.

### Tie-In Audit

- **Upstream feeders** — `EventAttendance` (override + actualAttendees on
  ACTIVE; now also `applyVillageWideOverride` for holy days), the Phase-3
  festival handlers (village-wide override), `CommunityGatherings`
  (discovery), the gathering's linked rite (venue source).
- **Downstream callers** — `ScheduleResolver.phaseAt`/`isWorkTime`: the new
  WORK→LEISURE collapse only fires for event-time NPCs and only on WORK
  phases; the legacy `isWorkTime()` festival nuance is now moot for attendees
  (they're in IDLE). `PriestBehavior`: unchanged — it still walks the presider
  to `rite.location()`, which is the venue attendees resolve to (convergence),
  and the officiant is excluded from attendance (not an attendee / presider
  gate / priest-excluded from holy-day override). The idle director /
  `GatherAtSquareBehavior`: AttendGathering sits above them in IDLE and above
  GatherAtSquare in SOCIAL, so a real event pre-empts ambient idle; it yields
  to greet/house/shelter and to nav guard.
- **Sibling systems** — the work-satisfied idle signal: an attendee is in a
  genuine LEISURE collapse running AttendGathering (a real task), not flagged
  idle. Activity text set on approach. No AmbientProps coupling.
- **Exhaustive switches** — none added. (The behavior switches nothing;
  `blessingRiteFor` / status maps are R2a's, untouched.)

### Simplification Sweep

Behaviors in scope: new `AttendGatheringBehavior`; `GatherAtSquareBehavior`
(unchanged, now sits below AttendGathering in SOCIAL). The two event memories
had no prior consumer — no half-built attend path to consolidate. Touched:
`ScheduleResolver` (collapse), `EventAttendance` (+village-wide helper),
`EventEffects` (+holy-day call), `TownspersonMob` (memory registration + 2
registrations). One general behavior over `CommunityGathering` — no
religion-only attend path. Reused `NpcBehaviorHelpers.walkTo`,
`HobbyLocationResolver`, `CommunityGatherings`, `EventAttendance`,
`GATHER_COOLDOWN` (no new cooldown memory — an event-time attendee is never
running the SOCIAL square-gather, so the throttle never collides).

### Memory safety

`UPCOMING_EVENT_TARGET` + `EVENT_ATTENDANCE_POS` are now in
`brainMemories()` — the behavior writes both; without registration
`brain.tick()` faults and freezes ALL movement. No third brain memory added
(GATHER_COOLDOWN reused, already registered).

### Deviations from prompt

- **The eventOverride→LEISURE collapse had to be built** (Mismatch 1): the
  prompt assumed it already existed ("collapse to LEISURE per
  ScheduleResolver"). It did not — `phaseAt` ignored `eventOverride`. R2b
  wires it (WORK_* only, preserving MEAL/SOCIAL/sleep). Without this the
  attend behavior is dead, so it is in scope by necessity.
- **Registered in IDLE AND SOCIAL**, not just "the LEISURE-mapped Activity":
  collapsing only WORK_* (to keep attendees eating/resting) leaves MEAL/SOCIAL
  phases on `Activity.SOCIAL`, so the behavior must also live there to gather
  attendees during those windows.
- **Gate is eventOverride-type-match, not `actualAttendees` membership**
  (Mismatch 2): required so festivals (which set the override village-wide
  without populating `actualAttendees`) are covered — the prompt's
  "general, all gathering kinds" intent.
- **Added `applyVillageWideOverride` for holy days** (a small, festival-
  mirroring addition just beyond the strict behavior scope): holy-day
  religious events populate no attendees, so without it the prompt's holy-day
  smoke-test case gathers no one. Priests are excluded so they still officiate.
- **Festival attendees now fully congregate** rather than partially working
  (the legacy `isWorkTime()` partial-work nuance for HARVEST_FESTIVAL /
  VILLAGE_FAIR is overridden by the collapse). This is the intended "go to the
  festival" behavior; flagged as a behavior change.

### Out-of-scope but flagged

- Making rite *effects* depend on physical arrival — `EventAttendance` still
  records attendance by probability/membership, not presence. Later tuning
  phase. The `EVENT_ATTENDANCE_POS` memory is set so that phase can read it.
- Festivals / processions / priest-fronted spectacle, and tightening the
  milling crowd into a staged arrangement — R3. The crowd currently mills
  within `LINGER_RADIUS` of the venue (GatherAtSquare-style), with some drift.
- Civic-venue refinement (town hall vs square for TRIAL / TOWN_MEETING) —
  resolution currently falls through to the town square; a building-typed
  venue is a later refinement.
- Phase-5 civic/cultural events with no attendee population (TOWN_MEETING /
  TRIAL / lectures) gather no one until their attendee lists are wired — same
  pre-existing gap as holy days were; only the religious holy days are wired
  here (scope discipline).

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (build fails before javac).
Static review: both event memories registered in `brainMemories()`;
`DayPhase.isWork()` / `getEventOverride()` / `getCategory()` /
`getProfession()` / `setMemoryWithExpiry` / `GlobalPos.of(dimension,pos)` /
`VillageSavedData.setDirty()` all confirmed against existing usage;
AttendGatheringBehavior registered in IDLE + SOCIAL; `EventCategory` is
same-package in `EventEffects` (no import needed); officiant excluded three
ways (no override / presider gate / priest-excluded from holy-day override).
Runtime-sensitive (Activity transitions, claim timing, crowd convergence) —
wants an in-game check.

### Smoke test

1. Marry two NPCs → a WEDDING gathering with a linked MARRIAGE rite. Confirm
   the couple + invited family physically walk to the temple and mill there
   for the duration; the priest officiates AT the temple (convergence); they
   disperse when it ends.
2. Advance to a cultural holy day → the religious VillageEvent + linked
   FEAST_DAY rite. Confirm the village congregates at the temple (the new
   village-wide override) and the priest officiates there; villagers are NOT
   pulled from sleep (night) and still break for meals.
3. Trigger a MARKET_DAY (or any Phase-3 festival) → confirm the SAME behavior
   gathers attendees to the town square (general, not religion-only; no rite,
   no priest).
4. Confirm a non-attendee (no eventOverride) goes about its normal day, and a
   priest officiating is never yanked into the congregation.
5. Confirm NO NPC freeze (the `brainMemories()` registration) — movement
   normal across the village while an event runs.
6. `/tick` shows no per-tick regression (the behavior resolves once per pick
   behind `WALK_TARGET`-absent + `GATHER_COOLDOWN`; the collapse is a cheap
   per-phase branch).

---

## Religion Rework — Phase R3a: Per-religion distinctiveness (content model + religion-aware rites)

First R3 sub-phase. Makes the four faiths genuinely different and brings
their dead flavor fields to life — without adding any new rite.

### Disposition (verified; findings)

- **The four religions are barely distinct** — confirmed. `Religion`
  (record, 8 codec fields) carries `deity`, `coreTenets`,
  `sacredLocations`, `preferredBookCategories`, and a grep shows **all four
  are dead** (zero `.deity()/.coreTenets()/.sacredLocations()/
  .preferredBookCategories()` consumers anywhere). Religions differ ONLY in
  their `rites` list + calendar.
- **`RiteExecutor` effects are uniform** — every handler hard-codes its mood
  magnitude / trigger / piety delta / memory text; religion is read only to
  credit piety to the participant's `primaryReligion`, never to vary the
  effect. The religion-aware lookup slots cleanly into the per-rite handlers
  AFTER the R1a capability gate + realized-vs-abstract path (no disturbance
  to either).
- **Religion resolution**: `RiteScheduler` already resolves the village's
  dominant religion via `dominantReligionFor(culture)` (culture from
  `VillageSavedData.getKingdomForVillage`). Chosen as canonical for effect
  tuning (the officiating faith), per the prompt — distinct from the
  participant's personal belief, which still receives the piety credit. With
  one religion per village they coincide.
- **Book system**: `Npc/Letters/` has `BookCategory` / `ProceduralBookFactory`
  / `StarterTextbookLibrary`, but **no temple-library stocking pass** wiring
  `preferredBookCategories` — that would be net-new. Per the prompt: deferred
  (kept minimal), not built here.
- **Verbs** (`ConfessVerb` / `RequestBlessingVerb` / `MakeOfferingVerb`)
  return `VerbResult.success(treeId)` — they open dialogue TREES, not literal
  text, so injecting dynamic deity/tenet text there needs dialogue-tree work
  (deferred). Flavor is surfaced instead in the rite EFFECT text (NPC
  memories / the confession ledger), which every rite already writes.

### Design (presented before implementation)

- **Content model — a parallel registry, not a `Religion` record/codec
  change** (the lower-churn option): new `Npc/Religion/RiteProfile` (a small
  record: `moodScale`, `pietyScale`, `Optional<String> flavor`; `DEFAULT` =
  1.0/1.0/none) + `Npc/Religion/ReligionContent` holding a sparse
  `Map<religionId, Map<Rite, RiteProfile>>`. A religion lists a profile only
  for rites it distinguishes; everything else falls back to `DEFAULT` → exact
  current behavior (sparse-friendly, no regressions). No schema change, no
  field-cap pressure, all new content in ONE file.
- **`ReligionContent` is the single content authority**: `villageReligionId`
  (the canonical culture→religion resolver, now also used by
  `RiteScheduler.villageRitualises` — DRY), `profileFor`, `invocation`
  (deity-aware), `tenet`, `flavor`.
- **`RiteExecutor` religion-aware**: `runOne` resolves the village religion
  once and threads the id into each handler (ORDINATION excluded per the R1c
  constraint). Handlers scale their mood/piety by the profile and weave
  `invocation`/`flavor` into the existing memory text. No per-religion
  `switch` in any handler — all branching lives in `ReligionContent`.

### What shipped

- **`RiteProfile`** + **`ReligionContent`** (new) — the model + the four
  religions' authored, distinct content.
- **`RiteExecutor`** — all 10 effect handlers (every rite except ORDINATION)
  now consult `ReligionContent`: religion-scaled magnitudes + deity/flavor in
  the memory/ledger text. Two private helpers (`riteFlavorSuffix`,
  `confessionTenetSuffix`) centralize the text weaving.
- **`RiteScheduler.villageRitualises`** — refactored to reuse
  `ReligionContent.villageReligionId` (single resolution source).
- **Dead fields consumed**: `deity` (woven into coming-of-age / marriage /
  funeral officiation memory text via `invocation`); `coreTenets` (a tenet
  surfaces in the confession ledger note).

**Authored character (via the model, not new rites):**
- *Sunstead* (Sun-Mother, agrarian): HARVEST_THANKSGIVING ×1.5 mood/piety,
  harvest/labour blessing + coming-of-age flavor.
- *The Loom* (no deity, fate): CONFESSION ×1.5, "pattern/thread" flavor,
  quieter feast days (×0.8).
- *Tidecall* (Sea-Mother): FEAST_DAY ×1.4 (tide feasts), voyage blessing +
  sea-naming flavor.
- *The Forge Creed* (ancestor/martial): FUNERAL ×1.5/×1.3 (honor-recounting),
  martial coming-of-age ×1.3, ancestor offering.

### Tie-In Audit

- **Upstream feeders** — `dominantReligionFor` / culture resolution
  (centralized in `ReligionContent.villageReligionId`); `ReligionRegistry`
  definitions (read for deity/tenets, unchanged).
- **Downstream callers** — all `RiteExecutor` handlers + both callers
  (`runDue`/`runImmediate`) and `PriestBehavior` (which calls `runImmediate`)
  get the religion-aware effects; the R2a `CeremonyBlessings`-linked rites
  flow through the same `runOne` → also religion-aware; `ReligionDebugCommand`
  (`/religion rite`) schedules via `RiteScheduler.schedule` → same path.
  `PietyComponent`: piety is still credited to the participant's own faith
  (WHO is unchanged); only the magnitude is scaled (HOW MUCH).
- **Sibling systems** — book/library: NOT wired (`preferredBookCategories`
  deferred — net-new stocking pass). Dialogue: deity/tenet text surfaces in
  memories/ledger, not dialogue trees (deferred). No new content enum.
- **Exhaustive switches** — the `Rite` switch in `runOne` stays exhaustive
  (all 11 arms, including ORDINATION unchanged); no new enum added.

### Simplification Sweep

Classes in scope: new `RiteProfile` + `ReligionContent`; `RiteExecutor`
(handlers now religion-aware); `RiteScheduler` (resolution reuse). All
per-religion content lives in `ReligionContent` — no parallel flavor source
exists, and no per-religion `switch` is scattered across handlers. The
culture→religion resolution is now single-sourced (was duplicated inline in
`RiteScheduler`).

### Memory safety

No new brain `MemoryModuleType`. The narrative `NpcMemory` / knowledge-ledger
text changes are not brain memories — no `brainMemories()` change, no freeze
risk.

### Deviations from prompt

- **Parallel `ReligionContent` registry, not a `Religion` codec field** —
  chosen as the lower-churn option (the prompt offered both): no schema
  change, no field-cap risk, content centralized. The dead `deity`/
  `coreTenets` fields are consumed by reading the existing `Religion` record
  through `ReligionContent`.
- **Tuning religion = village dominant; piety still credited to the
  participant's faith.** Effect MAGNITUDE is tuned by the officiating
  (village) religion; WHO receives piety is unchanged. With one religion per
  village these coincide; the rare mismatch (a minority NPC) is a multi-faith
  concern deferred to a later R3 phase — flagged.
- **Life-event memory text now always carries the deity invocation** (even
  when a religion has no profile for that rite) — this is the intended deity
  surfacing, not a regression; effect magnitudes are unchanged for
  unspecified (DEFAULT) profiles.

### Out-of-scope but flagged

- **No new `Rite`** (per constraint) — R3b. The model is built and proven so
  R3b can add ceremony types against it.
- **`preferredBookCategories`** — a temple-library stocking pass is net-new;
  deferred (a `ReligionContent.preferredBooks(id)` accessor would slot in
  once a stocking consumer exists).
- **`sacredLocations`** — worldgen concern, no cheap consumer; deferred.
- **Player-facing verb dialogue** (deity/tenet in Confess/Blessing/Offering
  responses) — needs dialogue-tree authoring; deferred. The NPC-side rite
  text already varies per faith.
- Religion-specific **orders** (R3c), **festivals/processions** (R3d),
  **multi-faith villages** (late R3) — untouched.

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (build fails before javac).
Static review: all 10 handler call-sites match their new signatures; the
`Rite` switch stays exhaustive (ORDINATION untouched); `ReligionContent`
profiles are sparse (unspecified → `RiteProfile.DEFAULT` → current
constants); `villageReligionId` handles null village → Sunstead;
`confessor.getRandom()` / `Religion.deity()/coreTenets()/displayName()`
confirmed; no codec/field-cap change. Runtime-sensitive (per-religion
magnitudes + text) — wants an in-game check.

### Smoke test

1. Set up four villages, one per culture (Plainfolk→Sunstead,
   Silkwood→The Loom, Tidereach→Tidecall, Highmarch→Forge Creed).
2. Trigger the SAME rite in each via `/religion rite <TYPE> <uuid>` and
   confirm the EFFECT + TEXT differ: e.g. a FUNERAL in a Forge Creed village
   gives a larger mood shift and the memory reads "...the Forge-Father: their
   name is struck into the anvil of memory", while the same funeral elsewhere
   uses the default magnitude and that faith's invocation.
3. CONFESSION in a Loom village: confirm the larger mood effect and a core
   tenet in the priest's confession ledger note ("...(every thread is seen
   in the pattern)").
4. HARVEST_THANKSGIVING: bigger village-wide mood/piety in a Sunstead village
   than elsewhere; FEAST_DAY bigger in Tidecall, quieter in a Loom village.
5. Regression check: trigger a rite a religion does NOT profile (e.g. a Loom
   BLESSING) and confirm it behaves exactly as before (default magnitude),
   with the faith's invocation in any text it writes.
6. Confirm a deity-less faith (The Loom) reads naturally ("...in the sight of
   The Loom") and never NPEs.

---

## Religion Rework — Phase R3b-1: Holy-day enrichment + Consecration (the new-ceremony pattern)

First of the R3b "new ceremonies" mini-arc. Enriches the existing holy-day
gatherings and adds Consecration — the first genuinely-new ceremony — as the
reusable exemplar R3b-2/3 follow.

### Disposition (verified; findings)

- **Holy-day gatherings already attach a blessing rite.** `CeremonyBlessings
  .blessingRiteFor` (R2a) maps SUNSTEAD_EQUINOX / LOOM_THREADING /
  TIDECALL_FULL_MOON / FORGE_CREED_KINGDOM_DAY → `FEAST_DAY`, scheduled via
  `RiteScheduler.scheduleBlessingRite` from `checkCulturalHolyDay` →
  `scheduleEvent` → `attach`. And R3a already makes `FEAST_DAY` religion-aware.
  So the baseline is *partly* in place — the gap is depth/distinctiveness
  (Sunstead's equinox is just a feast; Forge Creed had no `FEAST_DAY` profile →
  DEFAULT/bland). **This corrects the prompt's "do they do nothing?" — they do
  something; the work is enrichment, not net-new wiring.**
- **`RiteExecutor`/`RiteTier`/`RiteCapability`** — three exhaustive `Rite`
  switches (`RiteExecutor.runOne`, `RiteTier.tierOf`,
  `PriestBehavior.riteLabel`); `RiteCapability.canOfficiate` is the R1a gate a
  new rite slots into via `RiteTier.tierOf`.
- **`scheduleOrdinations` (R1c)** is the bounded daily-scan + officiant-gated
  schedule pattern to mirror; it already does one entity pass to group priests
  by village. `RiteSavedData` is **unpruned** — so a SUCCESSFUL rite persists
  as a durable marker, and the scan must gate on an available officiant to
  avoid SKIP-churn.
- **Village-effect channels** — no village-modifier system exists; the
  available channels are `depositToTreasury`, the needs map, and broadcasting
  mood. `HARVEST_THANKSGIVING` already uses `depositToTreasury(50)`. Chosen for
  Consecration's ongoing blessing (most bounded: one deposit per village/day).
- **Marking "consecrated"** — `Building` has no metadata map; a new field needs
  a codec change. The unpruned rite ledger gives a free durable marker (mirrors
  R1c "ordained = clergy spec / SUCCESSFUL ordination").

### Add-a-ceremony pattern (the reusable template — R3b-2/3 cite this)

1. **Gathering type vs new rite.** If the ceremony is village-wide/attended and
   can reuse an existing rite's effect → a new `EventType` whose
   `CeremonyBlessings.blessingRiteFor` maps to an existing `Rite` (the holy-day
   route). If the mechanic is genuinely distinct (new target / new effect) →
   a new `Rite` with its own handler (the Consecration route). Prefer the
   lower-churn option; avoid a new `EventType` unless attendance is needed now.
2. **Trigger.** Event-driven (life event → `EventLifeEventProducer`) OR a
   bounded daily per-village scan mirroring `scheduleOrdinations` (gate on an
   available officiant to avoid SKIP-churn in the unpruned ledger).
3. **Tier / capability.** Add the `Rite` to `RiteTier.tierOf` at the right tier
   so the existing R1a `RiteCapability` gate picks the right officiant.
4. **Effect via an EXISTING channel.** Reuse mood broadcast / `depositToTreasury`
   / piety / needs — invent no buff system. One-time effects in the handler;
   ongoing effects in the daily scan, tied to a durable marker.
5. **Marker (if stateful).** Prefer the unpruned rite ledger (a SUCCESSFUL rite
   referencing the target) over a new codec field.
6. **`ReligionContent` profile.** Add per-faith `RiteProfile` entries
   (scale + flavor); `DEFAULT` fallback keeps sparse faiths working.
7. **Exhaustive-switch sweep.** New `Rite` → `RiteExecutor.runOne`,
   `RiteTier.tierOf`, `PriestBehavior.riteLabel`. New `EventType` →
   `category` / `getDurationTicks` / `EventAttendance.baseline` / etc.

### What shipped

**Baseline — holy-day enrichment:**
- `CeremonyBlessings.blessingRiteFor`: SUNSTEAD_EQUINOX → `HARVEST_THANKSGIVING`
  (the solar equinox is an agrarian high holy day → the fuller mood+piety+
  treasury blessing); the other three holy days keep `FEAST_DAY`.
- `ReligionContent`: filled the missing **Forge Creed `FEAST_DAY`** profile
  (martial ancestor-day flavor) so all four holy days now read distinctly.

**Consecration (the new-ceremony exemplar) — the new-rite route:**
- New `Rite.CONSECRATION`; `RiteTier.tierOf` → **GRAND** (a village-wide
  spiritual act needing a competent officiant); `PriestBehavior.riteLabel`
  "a consecration".
- `RiteExecutor.handleConsecration` — first participant is the BUILDING id (not
  an NPC); resolves the building, applies a one-time village-wide mood + piety
  blessing (religion-scaled), returns SUCCESSFUL. The SUCCESSFUL rite IS the
  consecrated marker (no new field).
- `RiteScheduler` — `dailyTick` now builds the loaded-priest-by-village map
  ONCE (shared by ordination + consecration), then runs `scheduleConsecrations`
  (officiant-gated daily scan over TEMPLE/CHAPEL/SHRINE buildings; vacant
  presider → normal claim path) and `applyConsecrationBlessings` (a small daily
  `depositToTreasury`, per-faith scaled, per consecrated building that still
  stands). `collectConsecrationMarkers` does one ledger pass per village
  (O(rites+buildings), not O(rites×buildings)).
- `ReligionContent` — CONSECRATION profiles for all four faiths (scale + flavor),
  `DEFAULT` intact.

### Tie-In Audit

- **Upstream feeders** — `checkCulturalHolyDay` → `scheduleEvent` → `attach`
  feeds the (now richer) holy-day blessing; the building-spawn path (manual
  today) feeds the consecration scan via `village.getBuildingIds()`.
- **Downstream callers** — the three `Rite` switches updated (exhaustive);
  `CeremonyBlessings` (holy-day mapping) unchanged in arity (still has a
  `default`); the ongoing blessing uses `depositToTreasury`;
  `CommunityGatherings` surfaces the CONSECRATION rite as a gathering but R2b's
  attend behavior only processes `VillageEvent` gatherings, so the
  building-id-as-participant is never misread as an NPC.
- **Sibling systems** — the ordination scan and consecration scan coexist on
  the shared priest map; PriestBehavior claims by tier preference (GRAND
  CONSECRATION before STANDARD ORDINATION), no conflict. R2b: a consecration is
  a standalone rite (no `VillageEvent`), so no attendees gather at it this
  phase (flagged).
- **Exhaustive switches** — `Rite` (+CONSECRATION in all 3); no new `EventType`
  (so the `VillageEvent` switches are untouched).

### Simplification Sweep

Ceremony/scheduler classes in scope: `RiteScheduler` (consecration scan reuses
the ordination-scan shape — shared `buildPriestsByVillage`, same officiant-gated
structure, NO new scan mechanism), `RiteExecutor` (+1 handler), `Rite` /
`RiteTier` / `PriestBehavior` (+1 arm each), `CeremonyBlessings` (holy-day
remap), `ReligionContent` (+5 profiles). Holy-day enrichment does NOT duplicate
effect logic — it reuses the existing `HARVEST_THANKSGIVING`/`FEAST_DAY`
handlers + R3a scaling. The consecrated-marker reuses the rite ledger rather
than adding a parallel store.

### Memory safety

No new brain `MemoryModuleType`. The consecration scan/handler write only the
rite ledger + mood/piety/treasury — no `brainMemories()` change, no freeze risk.

### Deviations from prompt

- **Consecration is a standalone rite (new `Rite`), not a gathering
  `EventType`.** The mechanic is genuinely distinct (building target + ongoing
  buff), so a new `Rite` is justified; wrapping it in a new `EventType` would
  add the `VillageEvent` switch churn for an attendance feature the prompt
  itself scopes as "eventually". This mirrors ordination (the established
  scan-driven standalone-rite precedent) and is the cleaner exemplar. The
  pattern doc covers BOTH routes so R3b-2/3 can pick either.
- **Baseline was already partly done** (holy-days attach FEAST_DAY since R2a,
  scaled since R3a); the enrichment is the SUNSTEAD_EQUINOX→HARVEST upgrade +
  the Forge-Creed FEAST_DAY gap, not net-new wiring.
- **Building-id stored as the rite's first participant** (participants are
  usually NPC ids). Safe: only `handleConsecration` and the consecration
  helpers read it (as a building), and no NPC-resolving consumer touches a
  CONSECRATION rite's participants. Flagged as a documented convention.

### Out-of-scope but flagged

- Consecration as an ATTENDED gathering (villagers walk to the consecration) —
  needs an `EventType` wrapper; deferred (R3d festivals territory). Today the
  priest officiates at the building; no congregation gathers.
- Vigil / Purification (R3b-2), per-faith signature rites (R3b-3), orders
  (R3c), festivals (R3d), multi-faith (late R3) — untouched.
- Holy-day per-EventType flavor (distinct text per SUNSTEAD_EQUINOX vs the
  rite-level FEAST_DAY flavor) — the rite handler only knows the rite, not the
  event type; deferred. The event type already names the faith's holy day.
- Sunstead now gets two HARVEST_THANKSGIVINGs/year (autumn festival + equinox)
  — intentional for the agrarian faith; treasury impact is minor (+50 each).
- A de-consecration / re-consecration flow (building destroyed → marker stale)
  — the ongoing blessing already ends when the building is gone (existence
  check); the stale SUCCESSFUL rite is harmless. Pruning is a future ledger-
  housekeeping concern, not introduced here.

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (build fails before javac).
Static review: all 3 `Rite` switches carry CONSECRATION (compiler-exhaustive);
no new `EventType`; the consecration scan mirrors `scheduleOrdinations`
(officiant-gated, one shared priest pass, one ledger pass per village);
`markDirty` / `depositToTreasury` / `getBuildingById` / `getShape().getOrigin`
/ `getVillageCentre` confirmed; `RiteProfile`/`ReligionContent`/`RiteCapability`
same-package (no imports); `Set`/`HashSet` imports added. Runtime-sensitive
(scan timing, claim path, per-faith magnitudes) — wants an in-game check.

### Smoke test

1. Four villages, one per culture. Advance to each holy day (or `/religion`-
   force the cultural event): confirm a religion-appropriate blessing fires —
   a Sunstead (Plainfolk) equinox runs HARVEST_THANKSGIVING (village-wide mood +
   piety + treasury, ×1.5) while the others run their per-faith FEAST_DAY
   (Tidecall ×1.4, Loom ×0.8, Forge Creed ×1.1 with its new flavor).
2. Manually spawn a NEW (unconsecrated) TEMPLE/CHAPEL/SHRINE in a village with a
   qualified (GRAND-capable, e.g. seated) priest. Within a daily tick, confirm a
   CONSECRATION is scheduled, the priest walks to the building and officiates
   ("a consecration"), and on completion the village gets the one-time blessing.
3. Confirm the building is now consecrated: re-running the daily scan does NOT
   re-schedule it (`/religion`-inspect the ledger — one SUCCESSFUL CONSECRATION
   naming the building).
4. Confirm the ongoing blessing: the village treasury gains a small daily amount
   while the consecrated building stands; destroy the building and confirm the
   daily blessing stops.
5. Confirm a village with only a low-skill/un-seated priest (no GRAND-capable
   officiant) does NOT schedule a consecration (no churn) until a qualified
   priest is present.
6. Confirm a sparse/unprofiled case (a faith with no CONSECRATION profile, if
   added later) falls back to DEFAULT (×1.0, no flavor) with no error.

---

## Religion Rework — Phase R3b-2: Vigil + Purification/Atonement

Second of the R3b new-ceremony mini-arc. Adds two communal, attendee-
affecting ceremonies — both via the **gathering route** of the R3b-1
"Add-a-ceremony pattern".

### Disposition (verified; key findings)

- **Followed the R3b-1 pattern doc.** Consecration (standalone new-rite +
  daily scan) and the holy-day route (EventType → existing rite via
  `CeremonyBlessings`) are the worked examples. Vigil/Purification are
  *communal + attendee-affecting*, so both take the **gathering route** (new
  `EventType` + new `Rite`).
- **EventType exhaustive switches a new value MUST satisfy** (no default):
  `VillageEvent.category()`, `VillageEvent.getDurationTicks()`,
  `EventAttendance.baseline()`. The rest (`isAnnual`/`annualDay`/
  `minProsperity`/`randomChance`, `EventEffects.onEventStart`/`applyPlayerBuff`/
  `formatEventName`, `VillageEventScheduler` histType, `CeremonyBlessings
  .blessingRiteFor`) all have `default`s — safe. `EventEffects.dispatchStart`
  no-ops for unregistered types (`if (handler == null) return;`), so a new
  RELIGIOUS_RITE EventType's effect correctly comes from its blessing rite,
  exactly like the existing holy-day types.
- **MISMATCH — "reuse the Anvil Vigil holy day" needs the calendar revived.**
  The religion calendar's named days (`religion.calendar().holyDaysByName()`,
  incl. Forge Creed "Anvil Vigil" @150, Tidecall "Storm's Vigil" @200) are
  **dead** — only `/religion calendar` display reads them. R2a removed the
  `scheduleCalendarRites` path that consumed them; holy-day scheduling now uses
  the *Culture's* `holyDayInterval` (one EventType per culture). So Vigil's
  trigger had to RE-WIRE the religion-calendar named-day path.
- **MISMATCH — two year-lengths.** `ReligiousCalendar.DAYS_PER_YEAR = 365`
  (the liturgical calendar's space, what the old calendar-rite code used) but
  `SeasonTracker.DAYS_PER_YEAR = 96` (the seasonal cycle). The calendar entries
  (150, 200…) only make sense in the 365-day space, so the Vigil trigger uses
  `(tick/24000) % 365`, NOT `SeasonTracker.dayOfYear`.
- **Group-effect target.** A gathering's `actualAttendees` is populated at
  ACTIVE time (after scheduling), so it can't be the rite's participant list at
  schedule time. Resolved without a Religion→Village.Event reverse lookup: the
  PURIFICATION gathering is scheduled with the afflicted as **requiredAttendees**,
  which `CeremonyBlessings.participantsFor` flows into the blessing rite's
  participants — so the rite handler targets exactly the afflicted. Vigil is
  village-wide (no participants), like FEAST_DAY.
- **`handleConfession`** clears MELANCHOLY (`HealthComponent.remove`) + a HEALED
  mood bump AND writes a sensitive priest `KnowledgeEntry` — the latter is the
  confession-specific side effect a group rite must NOT replicate.
- **Health API**: `HealthComponent.hasCondition` / `.remove(HealthCondition)`.

### Route + design (each ceremony)

**Vigil — gathering route, new `EventType.VIGIL` + new `Rite.VIGIL`.**
A new rite (not reusing FUNERAL: FUNERAL is participant/deceased-scoped and
writes an "attended funeral" memory — wrong for a village-wide vigil; not
FEAST_DAY: wrong celebratory tone). Tier STANDARD (a seated priest leads it).
Effect: village-wide steadying/comforting mood (resolve/shared mourning),
religion-scaled. **Trigger: revives the religion calendar** — a daily
`checkCalendarVigil` schedules a VIGIL gathering when today (365-day liturgical
space) matches any "Vigil"-named holy day → naturally Forge Creed (Anvil Vigil)
+ Tidecall (Storm's Vigil).

**Purification — gathering route, new `EventType.PURIFICATION` + new
`Rite.PURIFICATION`.** Tier STANDARD. Effect: for each afflicted participant,
ease distress + clear MELANCHOLY via a **shared `easeAndClearMelancholy`
helper** that confession and purification both call — WITHOUT the confession
knowledge entry. **Trigger: distress-driven** — a daily `checkPurification`
schedules a PURIFICATION gathering (afflicted as required attendees) when a
village has ≥ `PURIFICATION_DISTRESS_THRESHOLD` (3) MELANCHOLY villagers and its
religion ritualises PURIFICATION; a 14-day lockout bounds re-scheduling (the
crisis-path pattern), not officiant-gated (the gathering is the observance, the
rite its best-effort blessing).

**Ritualises gating** (each faith only holds what it observes): added VIGIL to
Forge Creed + Tidecall, PURIFICATION to The Loom; Sunstead auto-includes both
(`Rite.values()`). Tidecall/Forge Creed don't ritualise PURIFICATION (no group
atonement); Sunstead/Loom have no "Vigil" calendar day (no VIGIL fires).

### What shipped

- `Rite` +VIGIL +PURIFICATION; swept all 3 exhaustive `Rite` switches
  (`RiteExecutor`, `RiteTier`→STANDARD, `PriestBehavior.riteLabel`).
- `VillageEvent.EventType` +VIGIL +PURIFICATION; the 3 mandatory EventType
  arms (`category`→RELIGIOUS_RITE, `getDurationTicks`→6000, baseline→0.55);
  `CeremonyBlessings.blessingRiteFor` maps each to its rite.
- `RiteExecutor`: `handleVigil` (village-wide) + `handlePurification`
  (participant-targeted) + the shared `easeAndClearMelancholy` helper
  (`handleConfession` refactored to call it, keeping its knowledge entry).
- `VillageEventScheduler`: `checkCalendarVigil` (revives the religion calendar)
  + `checkPurification` (distress scan) wired into `tick`.
- `ReligionRegistry`: VIGIL → Forge Creed + Tidecall; PURIFICATION → The Loom.
- `ReligionContent`: VIGIL profiles (Forge Creed ×1.3 martial-resolve, Tidecall
  sea-mourning) + PURIFICATION profiles (Loom ×1.3, Sunstead) with flavor.

### Tie-In Audit

- **Upstream feeders** — `checkCalendarVigil` (religion calendar, revived);
  `checkPurification` (MELANCHOLY scan); both schedule via the existing
  gathering path → `CeremonyBlessings.attach` → `scheduleBlessingRite` (gated by
  `villageRitualises`).
- **Downstream callers** — `VillageEvent`/`EventAttendance`/`CeremonyBlessings`
  switches updated; `EventEffects.onEventStart` routes the new RELIGIOUS_RITE
  types to the R2b village-wide override (VIGIL: empty attendees → village-wide;
  PURIFICATION: required/invited populated → normal override) and to a no-op
  `dispatchStart`; `RiteExecutor` handlers; the health system (`MELANCHOLY`
  clear) and mood system reused. `CommunityGatherings` surfaces both as
  gatherings (R2b attend behavior will populate them once merged).
- **Sibling systems** — CONFESSION: refactored onto the shared helper with NO
  behavior change (still writes its knowledge entry); FUNERAL grief-ease
  untouched (Vigil is a distinct rite, not a reuse). The R3b-1 Consecration scan
  and R1c ordination scan are unaffected (these triggers live on the
  VillageEventScheduler side).
- **Exhaustive switches** — `Rite` (3, all swept) and `EventType` (3 mandatory,
  all swept); no other enum.

### Simplification Sweep

Classes in scope: `RiteExecutor` (+2 handlers, +1 shared helper — confession &
purification share `easeAndClearMelancholy` rather than copy-pasting the
MELANCHOLY-clear), `VillageEventScheduler` (+2 triggers), `Rite`/`RiteTier`/
`PriestBehavior`/`VillageEvent`/`EventAttendance`/`CeremonyBlessings` (+arms),
`ReligionRegistry`/`ReligionContent` (+content). Vigil reuses the existing Anvil
Vigil **calendar entry** (revived) rather than a parallel trigger. No new scan
mechanism for purification beyond the existing per-village `tick` (alongside
`checkCulturalHolyDay`/`checkCrises`).

### Memory safety

No new brain `MemoryModuleType`. The triggers write the event store + the rite
ledger; handlers touch mood/health/piety. No `brainMemories()` change → no
freeze risk.

### Deviations from prompt

- **Both ceremonies took the gathering route with a NEW `Rite` each** (not
  reusing an existing rite's blessing). FUNERAL's grief-ease was close for Vigil
  but its participant/deceased scope + "attended funeral" memory are wrong for a
  village-wide vigil; confession's clear fit Purification but its knowledge entry
  is wrong for a group rite — so new rites + a shared clear-helper, per the
  prompt's own "shared helper without the knowledge side effect" option.
- **The Anvil Vigil calendar had to be REVIVED**, not merely reused — the
  religion-calendar named days have been dead since R2a. `checkCalendarVigil`
  brings them back (in their 365-day liturgical space), a bonus consumption of
  dead content. Flagged as a deviation since the prompt assumed the entry was
  live.
- **Purification is distress-driven, not calendar-driven** (no "Purification"
  named day exists). Distress is the meaningful, testable trigger (give NPCs
  MELANCHOLY → atonement fires); officiant-gating is replaced by a 14-day
  lockout (the crisis-path pattern) to keep it churn-safe without a priest scan
  on the event side.

### Out-of-scope but flagged

- Per-faith signature rites (R3b-3), orders (R3c), festivals/processions (R3d),
  multi-faith (late R3) — untouched.
- The 365-day liturgical calendar vs the 96-day seasonal cycle: Vigil fires on
  the liturgical day; reconciling the two calendars is a broader content-calendar
  concern, not religion's to solve here. Anvil Vigil @150 / Storm's Vigil @200
  fire once per 365 game-days (testable via `/time`).
- Purification targeting the gathering's live `actualAttendees` (vs the
  scheduled afflicted participants) — would need a rite→gathering reverse lookup
  + Religion→Village.Event coupling that R2a deliberately avoided; the
  required-attendee flow reaches the same group without it.
- Vigil/Purification attendance (villagers physically walking there) arrives
  with R2b (already merged provides the override; the attend behavior populates
  these RELIGIOUS_RITE gatherings).

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (build fails before javac).
Static review: all 3 `Rite` switches + the 3 mandatory `EventType` switches
carry VIGIL+PURIFICATION (compiler-exhaustive); `scheduleLifeEvent` /
`EventAttendance.villageNpcIds` / `HealthComponent.hasCondition` /
`religion.calendar().effectiveDayOfYear` signatures confirmed; confession keeps
its knowledge entry via the shared helper (no regression); ritualises gating
restricts each ceremony to the right faiths; lockout + threshold bound
purification churn. Runtime-sensitive (calendar day match, distress count, claim
path) — wants an in-game check.

### Smoke test

1. Forge Creed (Highmarch) village with a priest: `/time` to the Anvil Vigil
   liturgical day (game-day 150 → tick 150×24000). Confirm a VIGIL gathering
   fires, the priest officiates "a vigil", villagers get the steadying mood, and
   the text reads the Anvil-Vigil martial-resolve flavor.
2. Tidecall village: confirm the Storm's Vigil (day 200) fires with sea-mourning
   flavor; a Sunstead/Loom village (no Vigil calendar day) fires no vigil.
3. Give ≥3 villagers MELANCHOLY (e.g. `/health` or via events) in a Sunstead or
   Loom village with a priest; advance a day. Confirm a PURIFICATION gathering is
   scheduled with the afflicted as attendees, the priest officiates, and the
   afflicted attendees' MELANCHOLY clears + mood eases — WITHOUT the priest
   accruing per-attendee confession knowledge entries (`/npc` knowledge ledger
   unchanged in size).
4. Confirm a Tidecall/Forge Creed village (doesn't ritualise PURIFICATION) never
   schedules one even when distressed.
5. Confirm the 14-day lockout: a second purification doesn't schedule the next
   day even if distress persists.
6. Confirm a sparse/unprofiled case (a faith without a VIGIL/PURIFICATION
   profile that still ritualises it) falls back to DEFAULT (×1.0, no flavor),
   no error. Confirm no ledger churn (gated scheduling).

---

## Religion Rework — Phase R3b-3: Per-faith signature rites (closes the R3b mini-arc)

Final phase of the R3b new-ceremony mini-arc. Gives each of the four faiths
ONE distinctive named headline ceremony — faith-gated, calendar-triggered off
its own holy day via the R3b-2 revived-calendar path (`% 365` liturgical axis).

### Disposition (verified; findings + the enum-minimization decision)

- **Reused the R3b-2 revived-calendar trigger path** (`% 365`, not
  `SeasonTracker`'s 96-day year) and the gathering route from the R3b-1 pattern
  doc — no parallel calendar scan.
- **Named EventTypes are free.** `EventEffects.defaultDisplayName` title-cases
  the enum name (`FIRST_FURROW` → "First Furrow", `THREAD_BINDING` → "Thread
  Binding", `VOYAGE_BLESSING` → "Voyage Blessing", `ANCESTOR_OATH` → "Ancestor
  Oath"), so four distinctly-named gatherings cost nothing beyond the enum
  values + grouped switch arms.
- **EventType switches needing arms** (no default): `category()`,
  `getDurationTicks()`, `EventAttendance.baseline()` — all take the four as one
  grouped arm. `CeremonyBlessings.blessingRiteFor` (has default) maps all four
  → one shared rite.
- **`RiteProfile`** is built only via its builders (no direct `new` callers),
  so it's safe to extend; **not persisted** (no codec/field-cap impact).
- Calendar days confirmed: Sunstead "Spring Equinox" @80, Loom "First Threading"
  @30, Tidecall "First Catch" @105, Forge "Ancestor Day" @330.
- Effect channels confirmed: `getNpcRelationships().adjust(uuid, int, tick,
  RelationshipOrigin)` (MET_SOCIALLY), `depositToTreasury`, mood/piety — all
  existing.

**Enum-minimization decision (total NEW enums = 5: 4 EventType + 1 Rite).**
The genuinely-new, churny layer (a `Rite` with a handler + THREE exhaustive
switches) is minimized to **ONE shared `Rite.SIGNATURE_RITE`** — the four
faiths' signatures differ ENTIRELY through `ReligionContent` (the prompt's "a
single shared signature observance rite differentiated entirely by
ReligionContent"). Their effect MIXES (mood / relationship-among-attendees /
treasury) are encoded by extending `RiteProfile` with two optional knobs
(`relationshipBoost`, `treasuryBoon`), so one handler reads the profile — no
per-faith handler. The four **named EventTypes** are retained because they are
the headline identity (distinct `/event` + history + announcement names, free
via `defaultDisplayName`) and cost only grouped switch arms. Per-rite:
- First Furrow / Thread-Binding / Voyage Blessing / Ancestor Oath = named
  `EventType` → shared `Rite.SIGNATURE_RITE` → per-faith `ReligionContent`.
- NO faith got its own `Rite`: every signature effect is a combination of
  existing primitives (mood, relationship, treasury, piety), so per the
  no-speculative-enum rule none warranted a distinct handler.

### What shipped

- `RiteProfile` +`relationshipBoost` (int) +`treasuryBoon` (long); `DEFAULT`
  and all existing builders default them to 0 (no behavior change); new
  `signature(moodScale, relationshipBoost, treasuryBoon, flavor)` builder.
- `Rite.SIGNATURE_RITE` (one value); swept all 3 `Rite` switches
  (`RiteExecutor`, `RiteTier`→GRAND, `PriestBehavior.riteLabel`).
- `RiteExecutor.handleSignatureRite` — ONE handler: village-wide mood + piety
  always; an O(n) relationship "binding ring" among attendees when
  `relationshipBoost > 0` (Loom/Forge); a treasury boon when `treasuryBoon > 0`
  (Sunstead). All from the profile.
- `VillageEvent.EventType` +FIRST_FURROW +THREAD_BINDING +VOYAGE_BLESSING
  +ANCESTOR_OATH; grouped arms in `category`→RELIGIOUS_RITE,
  `getDurationTicks`→6000, `EventAttendance.baseline`→0.55,
  `CeremonyBlessings.blessingRiteFor`→`Rite.SIGNATURE_RITE`.
- `VillageEventScheduler.checkSignatureRite` — the faith-gated calendar trigger
  (religion → its EventType + signature day; `% 365`; per-type dedup), wired
  into `tick`.
- `ReligionRegistry`: SIGNATURE_RITE → Loom + Tidecall + Forge (Sunstead auto
  via `Rite.values()`), so each faith `ritualises` its own signature.
- `ReligionContent`: the four signature profiles — Sunstead (×1.2 mood, +40
  treasury), Loom (+8 relationship), Tidecall (×1.2 mood, protective), Forge
  (×1.2 mood, +6 relationship) — each with its named flavor line.

### Tie-In Audit

- **Upstream feeders** — `checkSignatureRite` reads each faith's
  `holyDaysByName` (the R3b-2 revived calendar) + `ritualises` (the blessing
  rite is gated by `villageRitualises(SIGNATURE_RITE)`); faith-gated so only the
  matching religion's village schedules its own signature.
- **Downstream callers** — the 3 `Rite` switches + the mandatory `EventType`
  switches updated; `CeremonyBlessings`/`EventAttendance`; `RiteExecutor`
  handler; the relationship (`adjust`), mood, piety, and treasury
  (`depositToTreasury`) systems reused. `EventEffects.onEventStart` routes the
  RELIGIOUS_RITE signatures to the R2b village-wide attendance override + a
  no-op `dispatchStart` (effect is the blessing rite) — identical to the other
  holy-day/observance types.
- **Sibling systems** — no double-scheduling on shared days: each check has its
  own per-EventType dedup, and the signatures fall on DISTINCT days from other
  observances (Forge Anvil Vigil @150 vs Ancestor Oath @330; Loom Threading
  culture-holy-day vs Thread-Binding @30 are different EventTypes/triggers and
  may coexist, which the scheduler explicitly tolerates). R2b attend behavior
  will populate these RELIGIOUS_RITE gatherings.
- **Exhaustive switches** — `Rite` (3, all swept) + `EventType` (3 mandatory,
  all swept). No other enum.

### Simplification Sweep

The four signatures DID reduce to "a faith-flavored observance blessing", so —
exactly as the sweep guidance asks — they share **one** `Rite.SIGNATURE_RITE` +
**one** `handleSignatureRite`, differentiated via `ReligionContent` (+2
`RiteProfile` knobs) rather than four near-duplicate handlers. The calendar
trigger reuses the R3b-2 revived-calendar mechanism (one new `checkSignatureRite`
alongside `checkCalendarVigil`/`checkPurification`/`checkCulturalHolyDay`, no new
scan infra). Classes in scope: `RiteProfile` (+2 fields/1 builder), `RiteExecutor`
(+1 handler), `VillageEventScheduler` (+1 trigger), `Rite`/`RiteTier`/
`PriestBehavior`/`VillageEvent`/`EventAttendance`/`CeremonyBlessings` (+arms),
`ReligionRegistry`/`ReligionContent` (+content). New-enum count: **5** (4
EventType + 1 Rite), the Rite/handler layer minimized to one.

### Memory safety

No new brain `MemoryModuleType`. The trigger writes the event store + rite
ledger; the handler touches mood/piety/relationship/treasury. No
`brainMemories()` change → no freeze risk.

### Deviations from prompt

- **Kept four named `EventType`s** rather than collapsing to one generic
  signature gathering. They are the headline identity (named in `/event` /
  announcements / history, free via `defaultDisplayName`) and cost only grouped
  switch arms; the minimization the prompt stressed was applied to the `Rite`
  layer (one shared rite + handler), which is where the real churn (handler +
  three exhaustive switches) lives. Net new enums = 5, justified above.
- **`RiteProfile` gained two fields** to encode the per-faith effect mix so one
  handler serves all four (the prompt's "differentiate via ReligionContent").
  No codec impact (RiteProfile is pure content).
- **Relationship boost is a village-wide "binding ring"**, not the gathering's
  live `actualAttendees` — same reasoning as R3b-2 (avoids a rite→gathering
  reverse lookup + the Religion→Village.Event coupling R2a avoided); the
  congregation IS the village under the R2b override.
- **Tier GRAND** for the signature (a faith's headline annual rite warrants its
  best officiant; a seated village priest is GRAND-capable). Annual cadence
  means no ledger churn even if it SKIPs when no qualified priest is present.

### Out-of-scope but flagged — R3b mini-arc CLOSED

This closes the **R3b new-ceremony mini-arc** (R3b-1 holy-day enrichment +
Consecration + the pattern doc; R3b-2 Vigil + Purification + calendar revival;
R3b-3 the four signature rites). Remaining R3: religion-specific **orders**
(R3c), **festivals/processions** (R3d, needs R2b), **multi-faith** villages
(late R3). Also still flagged from earlier phases: per-EventType holy-day flavor
text, the 365-day liturgical vs 96-day seasonal calendar reconciliation, and
rite-ledger pruning — none introduced or worsened here.

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (build fails before javac).
Static review: all 3 `Rite` switches + the 3 mandatory `EventType` switches
carry the new values (compiler-exhaustive); every `RiteProfile` constructor is
5-arg (DEFAULT + 4 builders) and all existing builder callers default the new
knobs to 0 (no regression); `checkSignatureRite` is faith-gated + dedup'd +
`% 365`; `Religion.id()` / `effectiveDayOfYear` / `getNpcRelationships().adjust`
/ `depositToTreasury` confirmed; the shared `SIGNATURE_RITE` is `ritualises`-
gated per faith. Runtime-sensitive (calendar-day match, per-faith effect mix,
claim path) — wants an in-game check.

### Smoke test

1. Sunstead (Plainfolk) village with a seated priest: `/time` to Spring Equinox
   (liturgical day 80 → tick 80×24000). Confirm a "First Furrow" gathering fires
   (named in `/event`), the priest officiates, villagers get a bright mood pulse,
   and the village treasury gains the agrarian boon (+40). No relationship change.
2. The Loom (Silkwood) village: `/time` to First Threading (day 30). Confirm a
   "Thread Binding" gathering fires and attendees' relationships toward one
   another strengthen (the binding ring), with the abstract pattern/thread flavor
   and no deity named.
3. Tidecall (Tidereach): First Catch (day 105) → "Voyage Blessing", a protective
   mood pulse, sea flavor (no relationship/treasury).
4. Forge Creed (Highmarch): Ancestor Day (day 330) → "Ancestor Oath", resolve
   mood + a kin-bond relationship nudge among attendees, martial flavor.
5. Confirm a faith does NOT fire another faith's signature (e.g. a Sunstead
   village never holds Thread-Binding).
6. Confirm a day carrying both a signature and another observance doesn't
   double-schedule the SAME ceremony (per-type dedup); distinct ceremonies may
   coexist.
7. Confirm DEFAULT fallback: a faith/rite with no profile (e.g. forcing
   SIGNATURE_RITE where unprofiled) applies ×1.0 mood, no relationship/treasury,
   no error.

---

## Religion Rework — Phase R3c: Religion-specific clergy orders

R3's order layer — the branch/identity dimension of the hybrid clergy model
(skill = capability, **specialization = order**, office = leadership). Turns
the generic `PRIEST_CLERIC` seam into per-faith clergy identity.

### Disposition (verified; findings)

- The R1b/R1c/R1d/R3a seams are exactly as documented: `PRIEST_CLERIC`
  generalist (`isGeneralist=true`); `assignInitialSpawnSpec(npc, profession)`
  (locked generalist at spawn, the single inhabitant-spawn spec route);
  `handleOrdination` calls it; `readOrderSeam`/`findClaimableRite`/
  `tierPreferenceRank(TempleKind, RiteTier)`; `ReligionContent.villageReligionId`
  (single culture→religion resolver); R1d `clergyTitle` (skill-derived rank).
- **`assignInitialSpawnSpec` has exactly two callers** — the populator spawn +
  `handleOrdination`. `SpecializationDef` is `(name, profession, displayName,
  requirements, isGeneralist, extra)`; `NpcSpecializationComponent.assign(def,
  npc, force)` + `setLocked` is the locked-assign API (orders bypass the skill
  gate via `force=true`, like `PRIEST_CLERIC`/combat roles).
- **No existing `Npc.Specialization → Npc.Religion` dependency** (only
  `Npc.Religion → Npc.Specialization`, via the ordination spec assignment). So
  the religion-aware order hub must live in `Npc.Religion` (or it would create a
  package cycle) — `ClergyOrders` does, mirroring `ReligionContent`.
- **Orthogonality confirmed**: R1d `clergyTitle` is `ApprenticeRank
  .fromSkillLevel(SOCIAL)` (Initiate/Priest/Senior Priest) — independent of the
  order spec id. The two compose as "an Initiate of the Threadkeepers".
- **`isOrdained`** (R1c ordination trigger) = "carries any PRIEST-profession
  specialization" — an order IS one, so orders satisfy it (no re-ordination
  loop). No other code keys specifically on `PRIEST_CLERIC`, so a priest holding
  an order instead breaks nothing.

### Design

- **Four order `SpecializationDef`s** registered in `NpcSpecializationTypes`
  (the registry idiom), `isGeneralist=false`, no skill requirements (identity,
  not capability): `PRIEST_DAWN` (Order of the Dawn), `PRIEST_THREADKEEPERS`,
  `PRIEST_TIDEWARDENS`, `PRIEST_ANCESTOR_KEEPERS`. `PRIEST_CLERIC` stays the
  generalist fallback.
- **`Npc/Religion/ClergyOrders`** — the religion-aware hub (no cycle):
  - `ORDERS` map (religionId → order def), keyed by the `ReligionRegistry`
    id constants — expands cleanly to sub-orders later.
  - `assignClergyOrder(level, npc)` — PRIEST-only; resolves the village religion
    via `ReligionContent.villageReligionId`, assigns that religion's order
    (locked); for an order-less religion **delegates to the existing
    `assignInitialSpawnSpec`** (generalist) — so that route stays live (not
    orphaned) and the generalist fallback is sparse-friendly.
  - `assignedOrderName(npc)` — the order's display name (empty for generalist),
    for the initiation flavor.
  - `isFocusRite(orderId, rite)` — each order's focus rites (the behavioral
    signature).
- **Religion-aware assignment at BOTH points**: `handleOrdination` and the
  populator now call `ClergyOrders.assignClergyOrder(level, npc)` instead of
  `assignInitialSpawnSpec` directly — same two assignment points, now
  order-aware, no third mechanism.
- **Behavioral signature** — `findClaimableRite` composes: building band
  (`tierPreferenceRank`) is PRIMARY; the order's focus is a SECONDARY tiebreak
  WITHIN a band (`orderRank` 0 = focus rite, 1 = not). A Threadkeeper in a
  temple still does GRAND rites first, but prefers CONFESSION among same-band
  rites; a generalist's `orderRank` is always 1 → no change. Focus sets: Dawn →
  HARVEST/FEAST, Threadkeepers → CONFESSION/PURIFICATION, Tidewardens →
  BLESSING, Ancestor-Keepers → FUNERAL/VIGIL.
- **Magnitude tie-in skipped (intentionally)** — the order's focus rites are
  ALREADY per-faith-tuned by R3a `ReligionContent` (the order ≙ the religion),
  so no extra magnitude mechanism is needed; the order's job this phase is
  identity + the preference nudge.
- **Initiation flavor** — `handleOrdination`'s memory now reads "initiated me
  into the {order}" (e.g. "the Threadkeepers"), or "ordained me into the clergy"
  for the generalist — reusing the existing memory/text path.

### Tie-In Audit

- **Upstream feeders** — both `assignClergyOrder` callers (populator spawn +
  `handleOrdination`) resolve the religion via `ReligionContent.villageReligionId`
  and assign the order; `ORDERS` keyed by `ReligionRegistry` ids.
- **Downstream callers** — `findClaimableRite` reads the order spec id for the
  secondary preference (the R1a capability gate + R1b building band unchanged);
  `readOrderSeam` still resolves the spec id (now an order); `SpecializationGate`
  is bypassed (force-locked assign, as before). `isOrdained` / `R1d clergyTitle`
  unaffected (orthogonal). No reader keyed on `PRIEST_CLERIC` specifically.
- **Sibling systems** — R1d apprenticeship/rank: an order-holding priest is
  still "ordained" (any PRIEST spec) and still gets a skill-derived title;
  R1b building preference composes as the primary key; R3a `ReligionContent`
  provides the (unchanged) per-faith magnitudes. `assignInitialSpawnSpec`
  retained as the generalist delegate (not deleted).
- **Exhaustive switches** — NONE added (orders are map-driven: `ORDERS`,
  `FOCUS`; no new enum, no new `Rite`/`EventType`). Confirmed.

### Simplification Sweep

The four orders are structurally identical, so they're driven from ONE
`religionId→order` map + shared `assignClergyOrder` (not four bespoke paths) —
mirroring `ReligionContent`. Classes in scope: `NpcSpecializationTypes` (+4
order defs, `PRIEST_CLERIC` retained as the sole generalist), new `ClergyOrders`
(map + assignment + focus + flavor helpers), `RiteExecutor.handleOrdination`
(redirect + flavor), `VillageInhabitantPopulator` (redirect),
`PriestBehavior.findClaimableRite` (composite preference). `assignInitialSpawnSpec`
is NOT orphaned — it remains the generalist-fallback delegate. `PRIEST_CLERIC`
is the only generalist (`isGeneralist=true`) in the priest family.

### Memory safety

No new brain `MemoryModuleType`. The change touches the specialization component
(persisted spec id — the order, no new field), the narrative memory text, and a
read-only preference in `findClaimableRite`. No `brainMemories()` change.

### Deviations from prompt

- **Order hub in `Npc.Religion.ClergyOrders`, and the two call sites call it**
  (rather than literally keeping the `assignInitialSpawnSpec` name at the call
  sites). Putting the religion-aware logic inside `assignInitialSpawnSpec`
  (`Npc.Specialization`) would force a `Specialization → Religion` import and a
  package cycle; the hub belongs on the religion side. The prompt's intent —
  orders assigned at the existing two clergy-assignment points, no third
  mechanism — is met, and `assignInitialSpawnSpec` stays live as the generalist
  delegate (so it's reused, not bypassed).
- **No persistent-field / codec change** — the spec id (the order) is already
  persisted by `NpcSpecializationComponent`.

### Out-of-scope but flagged

- **One order per religion** (the foundation). Multiple sub-orders/branches
  within a religion → deferred; `ORDERS` is structured (map keyed by religion)
  to expand to `Map<String, List<SpecializationDef>>` + a selection rule.
- **Spawn-time religion-resolution edge**: a populator-spawned founder's order
  is resolved + LOCKED at spawn via `villageReligionId`; if the village has no
  kingdom/culture yet (→ the system-wide SUNSTEAD default), the founder gets the
  Dawn order, and (being pre-ordained) is never re-ordained to correct it later.
  This is inherent to resolving religion by kingdom-culture at spawn and is
  consistent with how the whole religion system treats culture-less villages;
  a future "re-ordain on conversion" pass could refresh it. Flagged, not fixed.
- Festivals → R3d (needs R2b); multi-faith → R3e; offices/ranks untouched.

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (build fails before javac).
Static review: no new enum / exhaustive switch (orders are map-driven); the four
order defs use the 6-arg `SpecializationDef` ctor (`isGeneralist=false`);
`assignClergyOrder` is PRIEST-gated, locked, with the generalist delegate
preserved; the composite preference keeps building band primary + order
secondary (generalist ⇒ no change); `assignedOrderName` / `def.name()` /
`displayName().getString()` / `currentId()` confirmed; nothing else keys on
`PRIEST_CLERIC`. Runtime-sensitive (religion resolution timing, claim path) —
wants an in-game check.

### Smoke test

1. Ordain a new (leader-hired) priest in a village of each culture (Plainfolk /
   Silkwood / Tidereach / Highmarch). Confirm each receives the correct LOCKED
   order: Order of the Dawn / Threadkeepers / Tidewardens / Ancestor-Keepers
   (`/liv npc` spec readout), and the ordination memory reads "initiated me into
   the <order>".
2. Confirm the order nudges rite-claim preference composed with building role:
   e.g. a Threadkeeper, given a CONFESSION and another same-band rite both
   pending, prefers the CONFESSION; a temple Threadkeeper still does a pending
   GRAND rite before a MINOR confession (building band primary).
3. Confirm a founder spawned by the populator carries its village religion's
   order (not just the generalist) — assuming the village's culture is resolved
   at spawn.
4. Confirm fallback: a priest whose religion has no registered order (force an
   unknown religion) gets the generalist `PRIEST_CLERIC`, no error; the
   ordination reads "ordained me into the clergy".
5. Confirm the R1d clergy rank title still reads correctly alongside the order
   (e.g. an "Initiate" by skill who is of the Tidewardens) — the two are
   independent.
6. No NPC freeze / brain-tick error (no new brain memory); rite officiation
   still works (orders don't gate, only nudge).

---

## Religion Rework — Phase R3d-1: Priest festival-leading + crowd-blessing payoff

R3's festival phase, part 1. Makes the priest FRONT village-wide religious
gatherings — stay at the venue for the gathering's active window, lead it
(sermon gestures + periodic crowd-blessing pulses), and pay attendees back for
showing up — instead of officiating the one-shot blessing once and leaving.

### Disposition (verified; findings)

- `PriestBehavior` phase machine: `IDLE / WALKING_TO_RITE / OFFICIATING /
  PRODUCING`, fields `claimedRite`/`timer`/`idleCooldown`/`lastBlessTick`;
  `analyze()` → `findClaimableRite` (claim → walk → officiate via
  `runImmediate`, guarded by a re-fetch-PENDING check) → produce → idle;
  `blessNearby` is the R1 ambient aura (every `BLESS_INTERVAL` 600t, radius 12,
  mood +4). State is field-held — so a fronted festival fits the same idiom (no
  new brain memory).
- **The frontable predicate is `EventType.category() == RELIGIOUS_RITE`** —
  confirmed it covers ALL the village-wide religious gatherings (holy-days
  SUNSTEAD_EQUINOX/etc., VIGIL, PURIFICATION, AND the signature rites
  FIRST_FURROW/THREAD_BINDING/VOYAGE_BLESSING/ANCESTOR_OATH — all categorized
  RELIGIOUS_RITE in R3b-3), and naturally EXCLUDES life-events (LIFE_STAGE_RITE)
  and secular festivals (SEASONAL_FESTIVAL: HARVEST_FESTIVAL/VILLAGE_FAIR).
  Standalone rites (ordination/consecration) aren't `VillageEvent` gatherings,
  so they're excluded too. No explicit signature-type carve-out needed.
- `CommunityGatherings.activeInVillage(level, villageId, tick)` is the union
  query; the gathering→blessing-rite link is `eventData["riteId"]`
  (`CeremonyBlessings.RITE_ID_KEY` → `RiteSavedData.getRite`); `VillageEvent`
  exposes `endTick()` + `isActiveAt(tick)` (the R2a `CommunityGathering`
  contract); `VillageSavedData.getEventById` re-resolves a gathering.
- R3b-1 holy-day blessing: the one-shot FEAST_DAY/HARVEST/etc. rite fires at
  gathering start; the priest's `tickOfficiating` re-fetch-PENDING guard already
  prevents re-applying it.
- `ReligionContent.profileFor` + `RiteProfile` (R3a) tune the crowd blessing
  per-faith; R2b excludes PRIEST from the holy-day attendance override, so the
  fronter is NOT pulled into the congregation (stays in WORK, fronts).

### Design

- **Phase-in-PriestBehavior, not a sibling behavior** (Simplification Sweep
  choice): fronting reuses the existing claim→walk→officiate path for the
  opening blessing, then a new `FRONTING` phase continues at the venue. One new
  enum constant + six fields + five methods — no parallel behavior, no new brain
  memory.
- **Fronter election reuses the blessing-rite presider**: `tryStartFronting`
  claims the gathering's linked rite (`presider=me`); other priests see
  `presider≠them` and skip — **one fronter**, no new gating mechanism. The R1a
  `RiteCapability.canOfficiate` gate governs who may front.
- **Precedence**: `analyze()` checks `tryStartFronting` BEFORE `findClaimableRite`
  / produce — fronting an active festival preempts routine work for the (short,
  ~6000t) festival window. Non-festival rites (weddings/funerals) are still
  claimed by OTHER priests; in a single-priest village they defer until the
  festival ends.
- **No double-apply**: entering fronting routes through `WALKING_TO_RITE →
  OFFICIATING`, where the existing re-fetch-PENDING guard performs the one-shot
  blessing exactly once (or skips it if already applied abstractly); then
  `tickOfficiating` transitions to `FRONTING` instead of `IDLE`.
- **Crowd-blessing payoff** (`festivalCrowdBless`): while fronting, every
  `FESTIVAL_PULSE_INTERVAL` (600t), a stronger-than-ambient mood (+6 vs +4) +
  small piety (+0.01) bump to NPCs at the venue (the priest stands there;
  attendees are gathered by R2b), per-faith-scaled by the festival rite's
  `ReligionContent` profile. **Bounded**: `FESTIVAL_MAX_PULSES` (6) caps total
  piety (≤ 0.06/festival) — no piety farming. The **ambient R1 aura is
  superseded** during fronting (`tick` skips `blessNearby` when `FRONTING`).
- **Field-held, exits cleanly**: `frontedGatheringId`/`frontEndTick`/
  `frontReligionId`/`frontRiteType`/`frontPulseCount`/`lastFrontPulseTick`;
  `tickFronting` ends when `endTick` passes or `getEventById(...).isActiveAt`
  goes false; `clearFronting()` runs on end + in `stop`/`goIdle`.

### Tie-In Audit

- **Upstream feeders** — the gathering schedulers (holy-day R3b-1, signature
  R3b-3, VIGIL/PURIFICATION R3b-2) create the RELIGIOUS_RITE `VillageEvent`s +
  their linked blessing rite; `CommunityGatherings.activeInVillage` discovers
  them; R2b populates `actualAttendees` / pulls the congregation to the venue.
- **Downstream callers** — the `PriestBehavior` phase machine (new FRONTING
  case); `blessNearby` (superseded during fronting); attendees' mood + piety
  (`PietyComponent.adjustBelief`/`recordRiteAttendance`); `VillageSavedData
  .getEventById` (re-check active). The one-shot officiation path is unchanged
  for non-festival rites (frontedGatheringId null → IDLE as before).
- **Sibling systems** — R2b `AttendGatheringBehavior`: the priest is excluded
  from the attendance override (PRIEST), so the fronter leads while the
  congregation attends — both at the same venue. R3b-1 holy-day blessing: not
  double-applied (re-fetch-PENDING guard). R1a gate / R1b building preference /
  R3c order: untouched — fronting preempts via `analyze` ordering but
  `findClaimableRite` (with its building/order preference) is unchanged for the
  non-fronting path.
- **Exhaustive switches** — `EventType.category()` arms confirmed (festivals =
  RELIGIOUS_RITE, life-events = LIFE_STAGE_RITE); NO new enum / `Rite` /
  `EventType` added this phase. The `Phase` switch in `tick` is a statement
  switch (FRONTING arm added).

### Simplification Sweep

In scope: `PriestBehavior` only (one file). Chose phase-in-behavior over a
sibling (reuses the claim/walk/officiate path + state fields). The crowd
blessing reuses the `blessNearby` AABB-radius + `ReligionContent` tuning path
(no new effect mechanism); fronter election reuses the rite presider (no new
"who leads" gate); fronted festival is field-held (no new brain memory). The
only ambient-vs-festival interaction (double aura) is resolved by superseding
`blessNearby` during FRONTING.

### Memory safety

No new brain `MemoryModuleType` — fronting is held in PriestBehavior fields;
the behavior writes only `WALK_TARGET` + `NO_ACTIONABLE_WORK` (already
registered). No freeze risk.

### Deviations from prompt

- **Fronting preempts non-festival rite claiming in a single-priest village**
  (a wedding/funeral defers until the short festival window ends), rather than
  interleaving. In multi-priest villages there's no starvation (one fronts,
  others officiate). This keeps the precedence simple (check fronting first in
  `analyze`) and is bounded by the ~6000t festival duration; flagged.
- **All RELIGIOUS_RITE gatherings are frontable**, including VIGIL and
  PURIFICATION (not only "festive" holy-days) — they ARE village-wide religious
  gatherings the priest should lead, so the category predicate includes them
  (the priest leads the vigil / atonement and crowd-blesses attendees). Their
  one-shot effects (clear MELANCHOLY, etc.) still fire once via the linked rite.

### Out-of-scope but flagged

- New grand-festival types → R3d-2 (this phase fronts what exists). Secular
  festivals (HARVEST_FESTIVAL/VILLAGE_FAIR) untouched (SEASONAL_FESTIVAL, not
  fronted). Festival economy/commerce → R4. Life-events/ordination/consecration
  stay one-shot.
- Crowd-blessing targets NPCs within `BLESS_RADIUS` of the priest-at-venue (the
  congregation), not the gathering's exact `actualAttendees` list — avoids a
  per-pulse reverse lookup + keeps the radius-aura idiom; everyone present is
  blessed. A future precise-attendee variant could read `actualAttendees`.
- A fully-idle (non-fronting) priest still doesn't run the ambient aura (R1
  limitation, unchanged).

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (build fails before javac).
Static review: no new enum/`Rite`/`EventType`; the frontable predicate is the
RELIGIOUS_RITE category (excludes life-events + secular); fronter election via
the rite presider (one fronter); the re-fetch-PENDING guard prevents
double-applying the one-shot blessing; crowd pulses are capped
(`FESTIVAL_MAX_PULSES`) and per-faith-scaled; ambient aura superseded while
fronting; field-held (no new brain memory); `endTick`/`isActiveAt`/`getEventById`
/`category`/`FESTIVAL_ATTENDED`/`profileFor` confirmed. Runtime-sensitive
(active-window timing, claim race, crowd radius) — wants an in-game check.

### Smoke test

1. Advance a village to a holy day (e.g. a Sunstead equinox) with a temple +
   priest. Confirm the priest walks to the venue and STAYS for the gathering's
   active window leading it (periodic swing gesture, activity text "Leading
   <festival>"), rather than officiating once and leaving.
2. Confirm attendees (the R2b congregation at the venue) receive the crowd
   blessing on the interval — a mood lift + a small piety gain — and that the
   total piety is capped over the festival (stops after `FESTIVAL_MAX_PULSES`).
3. Separately advance to a signature rite (e.g. Forge Ancestor Oath @ day 330)
   and confirm the priest fronts it the same way, with that faith's scaling.
4. Confirm a wedding/funeral still ONE-SHOT officiates (priest officiates and
   leaves — not fronted); confirm a secular HARVEST_FESTIVAL / VILLAGE_FAIR is
   unaffected (no fronting).
5. In a two-priest village during a festival, confirm only ONE priest fronts
   (no mobbing) and the other handles other rites; confirm the R1a capability
   gate still governs who may front (an under-tier priest can't claim a GRAND
   holy-day blessing).
6. Confirm the priest leaves the venue and resumes produce/idle when the
   gathering ends; no NPC freeze (no new brain memory).

---

## Religion Rework — Phase R3d-2: Grand annual faith festivals (completes the R3d festival arc)

R3's festival phase, part 2 — the close of the festival arc. R3d-1 made any
RELIGIOUS_RITE gathering auto-fronted by the priest + auto-attended (R2b) +
crowd-blessed. So this phase just adds the festivals; fronting/attendance/
crowd-blessing come for free.

### Disposition (verified; findings)

- **`handleSignatureRite` hardcoded `profileFor(religionId, Rite.SIGNATURE_RITE)`**
  — generalized to `rite.type()`, so ONE handler now serves both SIGNATURE_RITE
  and the new GRAND_FESTIVAL, each reading its own per-faith profile.
- **The frontable/attended predicate is `category() == RELIGIOUS_RITE`** (R3d-1
  / R2b). Confirmed the new festivals must be categorized RELIGIOUS_RITE — then
  fronting + congregation + crowd-blessing apply automatically (NOT
  reimplemented).
- **EventType exhaustive switches needing arms** (no default): `category()`,
  `getDurationTicks()`, `EventAttendance.baseline()` — all take the four grand
  types grouped. The rest (`isAnnual`/`annualDay`/`minProsperity`/
  `randomChance`, `EventEffects.onEventStart`/`applyPlayerBuff`/
  `formatEventName`, `histType`, `CeremonyBlessings.blessingRiteFor`) have
  defaults. `defaultDisplayName` title-cases the enum name → free display names
  ("Harvest Home" / "Great Weaving" / "Tides Return" / "Founding Day").
- **Calendar (`% 365`) principal days** chosen from `ReligionRegistry`, each
  distinct from the faith's signature/vigil days: Sunstead **Harvest Equinox**
  (264) [signature First Furrow @ Spring Equinox 80], Loom **Fourth Threading**
  (270) [signature @ First Threading 30], Tidecall **Last Catch** (300)
  [signature @ First Catch 105, vigil @ Storm's Vigil 200], Forge **Founding
  Day** (12) [signature @ Ancestor Day 330, vigil @ Anvil Vigil 150].
- **`checkCulturalHolyDay`** has a per-day dedup; it runs the generic holy-day
  blessing on the *culture interval* (`% 96` via `SeasonTracker`). The grand
  festival runs on the `% 365` liturgical day (revived-calendar path, R3b-2/3).

### Design

- **Enum-minimization (R3b-3 pattern): 1 new `Rite.GRAND_FESTIVAL` (shared
  handler) + 4 named `EventType`s.** The grand festival reuses
  `handleSignatureRite` (now `rite.type()`-keyed) — its richer effect comes
  entirely from its own `ReligionContent.GRAND_FESTIVAL` profile, NOT a new
  handler. A new `Rite` (rather than reusing SIGNATURE_RITE) is needed only so
  the grand festival can carry a DISTINCT, richer profile than the signature
  rite. New-enum count: **5** (1 Rite + 4 EventType), churny handler layer = 0
  new (shared).
- **Trigger**: `VillageEventScheduler.checkGrandFestival` — faith-gated
  (religionId → grand EventType + principal day name), `% 365`, per-day dedup;
  runs BEFORE `checkCulturalHolyDay`.
- **Supersede**: `checkCulturalHolyDay` now skips when `isGrandFestivalDay`
  (today is the village religion's grand-festival principal day) — so on that
  day the grand festival owns it, not a duplicate generic holy-day blessing.
  Both use the shared `grandFestivalDayName` mapping.
- **Effect** (the GRAND_FESTIVAL profile via the shared handler): a richer
  village-wide mood (×1.4–1.6) + piety + a treasury boon (40–80) + (Loom/Forge)
  a relationship ring — per faith. **Bounded**: the one-shot fires once; the
  R3d-1 fronting crowd-pulses (capped at `FESTIVAL_MAX_PULSES`=6) add on top, so
  the combined payoff stays anti-farm — the longer 12000t duration does NOT
  increase the pulse count (cap is on count, not time).
- **Duration** 12000t (2× routine 6000t — the high celebration), kept under the
  priest's 24000t fronting-behavior cap.
- **Per-faith identity**: named EventTypes (display free) + GRAND_FESTIVAL
  flavor lines.

### What shipped

- `Rite` +GRAND_FESTIVAL; swept all 3 `Rite` switches (`RiteExecutor` shares the
  signature handler, `RiteTier`→GRAND, `PriestBehavior.riteLabel`).
- `handleSignatureRite` generalized to `profileFor(religionId, rite.type())`
  (serves SIGNATURE_RITE + GRAND_FESTIVAL).
- `VillageEvent.EventType` +HARVEST_HOME +GREAT_WEAVING +TIDES_RETURN
  +FOUNDING_DAY; grouped arms in `category`→RELIGIOUS_RITE,
  `getDurationTicks`→12000, `EventAttendance.baseline`→0.55;
  `CeremonyBlessings.blessingRiteFor`→`Rite.GRAND_FESTIVAL`.
- `VillageEventScheduler`: `checkGrandFestival` (calendar trigger, deduped) +
  `isGrandFestivalDay` supersede guard in `checkCulturalHolyDay` +
  `grandFestivalType`/`grandFestivalDayName` mappings; wired into `tick` first.
- `ReligionRegistry`: GRAND_FESTIVAL → Loom + Tidecall + Forge (Sunstead auto).
- `ReligionContent`: the four richer GRAND_FESTIVAL profiles + flavor.

### Tie-In Audit

- **Upstream feeders** — `checkGrandFestival` reads each faith's principal
  calendar day (revived calendar) + `ritualises(GRAND_FESTIVAL)` gate (via
  `CeremonyBlessings.attach` → `scheduleBlessingRite`); faith-gated.
- **Downstream callers** — the 3 `Rite` switches + the 3 mandatory `EventType`
  switches updated; `CeremonyBlessings` maps the grand types → GRAND_FESTIVAL;
  `RiteExecutor.handleSignatureRite` (shared) applies the effect;
  `EventEffects.onEventStart` routes the new RELIGIOUS_RITE types to the R2b
  village-wide override + a no-op `dispatchStart` (effect = the blessing rite).
- **Sibling systems** — **R3d-1 fronting: applies automatically** (RELIGIOUS_RITE
  category) — NOT duplicated; **R2b attendance**: the congregation is pulled
  automatically; **R3b-1 holy-day blessing**: superseded on the principal day
  (one gathering); **R3b-3 signature / R3b-2 vigil**: on DISTINCT calendar days,
  no collision; treasury/mood/piety channels reused.
- **Exhaustive switches** — `Rite` (3, all swept) + `EventType` (3 mandatory,
  all swept). No other enum.

### Simplification Sweep

The four grand festivals are structurally identical → driven from
`ReligionContent` (the GRAND_FESTIVAL profiles) + the SHARED `handleSignatureRite`
+ the existing revived-calendar trigger + per-day dedup — no new mechanism,
no per-faith handler, no new effect channel (mood/piety/treasury reused). Fronting/
attendance/crowd-blessing are inherited from R3d-1/R2b via the RELIGIOUS_RITE
category — explicitly NOT reimplemented. Classes in scope: `Rite`/`RiteTier`/
`PriestBehavior` (+arm), `RiteExecutor` (1-line generalization), `VillageEvent`/
`EventAttendance`/`CeremonyBlessings` (+grouped arms), `VillageEventScheduler`
(+trigger/supersede/mappings), `ReligionRegistry`/`ReligionContent` (+content).
New-enum count: **5** (1 Rite + 4 EventType); shared handler.

### Memory safety

No new brain `MemoryModuleType`. Triggers write the event store + rite ledger;
the handler touches mood/piety/relationship/treasury. No `brainMemories()`
change.

### Deviations from prompt

- **Added one new `Rite.GRAND_FESTIVAL`** (not zero). Reusing SIGNATURE_RITE
  would force the grand festival to share the signature rite's profile (same
  `(religionId, rite)` key) — but the grand festival must be RICHER, so it needs
  its own profile, hence its own Rite key. The HANDLER is still shared
  (`handleSignatureRite`), so the churny layer added nothing; this is the
  minimal way to carry a distinct effect. The four EventTypes provide headline
  identity (display names free), as in R3b-3.
- **Supersede via a day-equality guard** in `checkCulturalHolyDay` (skip the
  generic holy day on the grand-festival principal day) rather than a
  post-hoc de-dup of two scheduled events — simpler and guarantees one
  gathering. The grand trigger runs first for clarity.

### Out-of-scope but flagged — R3d festival arc CLOSED

This completes **R3d** (R3d-1 fronting + crowd-blessing payoff; R3d-2 the grand
annual festivals). Remaining R3: **multi-faith** villages (R3e). Out of the
religion rework's festival scope: secular festivals (untouched — SEASONAL_FESTIVAL,
not fronted), festival economy/commerce (→ R4). Still flagged from earlier: the
365-day liturgical vs 96-day seasonal calendars (the grand festival, like the
signature/vigil, fires on the liturgical day — testable via `/time`); rite-ledger
pruning.

### Build verification

Deferred — sandbox blocks `maven.neoforged.net` (build fails before javac).
Static review: all 3 `Rite` switches + the 3 mandatory `EventType` switches
carry the new values; `handleSignatureRite` keyed by `rite.type()` (8 profiles:
4 signature + 4 grand); `checkGrandFestival` is faith-gated + deduped + `% 365`;
the supersede guard shares the day mapping; all faiths `ritualise`
GRAND_FESTIVAL; the combined effect stays bounded (one-shot + capped pulses);
`Religion.id()` / `effectiveDayOfYear` / `defaultDisplayName` confirmed.
Runtime-sensitive (calendar-day match, supersede, fronting handoff) — wants an
in-game check.

### Smoke test

1. In a village of each faith, `/time` to that faith's principal high holy day
   (Sunstead Harvest Equinox = liturgical day 264 → tick 264×24000; Loom Fourth
   Threading 270; Tidecall Last Catch 300; Forge Founding Day 12). Confirm its
   grand festival fires as ONE gathering (named "Harvest Home" / "Great Weaving"
   / "Tides Return" / "Founding Day" in `/event`), NOT a duplicate alongside a
   generic holy-day blessing.
2. Confirm the priest FRONTS it (R3d-1) for the longer 12000t window and the
   congregation gathers (R2b), receiving the crowd-blessing pulses on top of the
   one-shot grand effect (richer mood + piety + treasury boon with per-faith
   flavor).
3. Confirm the combined payoff stays within the anti-farm cap (the fronting
   pulse count is capped regardless of the longer duration).
4. Confirm a NON-principal holy day still fires the routine generic blessing
   (the supersede only applies on the principal day).
5. Confirm a faith does NOT fire another faith's grand festival; confirm secular
   festivals (HARVEST_FESTIVAL / VILLAGE_FAIR) are unaffected (not fronted).
6. No NPC freeze (no new brain memory); rite officiation + fronting unaffected.

---

## R3e-1 — Minority practice ladder + cross-faith effect reconciliation (2026-06-08)

First of the R3e multi-faith mini-arc. Pure behavior + effect-tuning: no
building data, no placement, no travel (those are R3e-2 / R3e-3). Gives an
**unserved minority** believer something to do (solo private devotion) and stops
another faith's ceremony from deepening an attendee's *own* faith (cross-faith
reconciliation).

### Disposition (findings, verified on branch)

- **PietyComponent** — confirmed: `primaryReligion()`, `primaryStrength()`,
  `beliefIn`/`setBelief`/`adjustBelief` (clamped [0,1], multi-belief map →
  syncretism support), `recordRiteAttendance`, `primaryTier()` (UNAFFILIATED
  <0.2 / FAITHFUL <0.5 / DEVOUT <0.8 / PIOUS). The multi-belief map is exactly
  the substrate the syncretic drift writes to.
- **ReligionContent.villageReligionId** — confirmed the dominant/officiating
  resolver (kingdom culture → `ReligionRegistry.dominantReligionFor`). This is
  the served/unserved axis.
- **RiteExecutor** — confirmed the village-wide communal handlers
  (`handleHarvestThanksgiving`, `handleFeastDay`, `handleConsecration`,
  `handleVigil`, `handleSignatureRite`) each iterate village NPCs and credited
  `npc.primaryReligion()` (the ATTENDEE's own faith) regardless of the
  officiating faith — the cross-faith bug, present in the communal handlers too,
  not only R3d-1. The personal/life handlers (offering/tithe/coming-of-age/…)
  correctly credit the participant's own faith (their OWN rite) — left untouched.
- **PriestBehavior.festivalCrowdBless (R3d-1)** — confirmed it credited
  `other.primaryReligion()` regardless of the festival's faith (the named bug)
  and applies mood + a small piety pulse to nearby NPCs, capped by COUNT
  (`FESTIVAL_MAX_PULSES`).
- **Idle/hobby infra** — `HobbyBehavior` is the model: `BrainNavGuard`-gated,
  resolves the spot once per pick via `HobbyLocationResolver.resolve(...)`
  (HOME resolves to the NPC's house — the quiet spot), registered in
  `Activity.IDLE` (LEISURE maps to IDLE) above the idle director. Cooldown idiom
  is a `*_COOLDOWN` brain memory OR a field. Chose a **field** (no new brain
  memory → no freeze-trap surface). `brainMemories()` confirmed unchanged.

### Design

- **One new helper class** `Npc/Religion/FaithReconciliation` holds BOTH canonical
  tests (so neither is scattered):
  - `faithBenefit(attendeeFaith, riteFaith)` → `FaithBenefit(moodMultiplier,
    sameFaith)`. Same faith → 1.0 + own-faith credit; different/none →
    `CROSS_FAITH_MOOD_MULT = 0.4` + no own-faith credit.
  - `applyCommunalBenefit(npc, riteFaith, trigger, scaledMood, scaledPiety, now)`
    — the SINGLE apply site every cross-faith effect routes through. Co-religionist
    gets full mood + deepens own faith by `scaledPiety`; everyone else gets
    `0.4×` mood and (when the rite carries piety) a `SYNCRETIC_DRIFT = 0.004`
    nudge toward the OFFICIATING faith instead of any own-faith credit. Scaling
    still comes from `RiteProfile` (passed in pre-scaled) — no parallel scaler.
  - `isUnservedLocally(level, village, npc)` — the served/unserved predicate:
    primary faith ≠ `villageReligionId`, OR no seated `VILLAGE_PRIEST` (mirrors
    `RiteExecutor.findPriest`). Atheists (no primary) are not "unserved".
- **`SoloDevotionBehavior`** (`Npc/Brain/Behaviors/`) — modelled on
  `HobbyBehavior`: gate (not child + `BrainNavGuard` + field cadence throttle
  `DEVOTION_INTERVAL = 6000t` + `primaryStrength ≥ 0.2` + `isUnservedLocally` +
  HOME spot resolves), WALK→PRAY(100t)→DONE, reward = own-faith `+0.004` piety +
  `+3` mood (`LETTER_FROM_FRIEND`, daily-stack-capped) + `recordRiteAttendance`
  (upkeep so the unserved stay in practice). Cadence held in a **field**
  (`lastDevotionTick`), reset in `stop()` on every run (completed OR aborted) so
  a contended/unreachable spot can't retry per-tick.
- **Registration** — IDLE, directly below `HobbyBehavior`, above
  `IdleDirectorBehavior`. Leisure flavor (hobby) still wins; devotion beats the
  plain stroll. Self-dormant for served majorities + atheists.

### What shipped

- `Npc/Religion/FaithReconciliation.java` (new) — `faithBenefit` + `FaithBenefit`
  record + `applyCommunalBenefit` + `isUnservedLocally`.
- `Npc/Religion/RiteExecutor.java` — 5 communal handlers (harvest, feast,
  consecration, vigil, signature/grand) now call `applyCommunalBenefit` in place
  of the open-coded mood + own-faith-credit pair.
- `Npc/Brain/Behaviors/Production/PriestBehavior.java` — `festivalCrowdBless`
  routes through `applyCommunalBenefit` (fixes the R3d-1 own-faith bug); dropped
  the now-unused `ReligionRegistry` import.
- `Npc/Brain/Behaviors/SoloDevotionBehavior.java` (new) — the unserved-minority
  practice rung.
- `Entities/custom/TownspersonMob.java` — registered `SoloDevotionBehavior` in
  `Activity.IDLE` (below hobby, above the director).

### Tie-In Audit

1. **Upstream feeders** — spawn-time per-culture belief population (@0.3) is the
   source of minorities; `villageReligionId` (kingdom culture) is the local-faith
   axis. Both unchanged. The migrant/visitor path that mixes faiths is the
   minority source and is unaffected (read-only here).
2. **Downstream callers** — `RiteExecutor` communal effects + R3d-1 crowd pulse
   are the only cross-faith credit sites; both now go through the one helper.
   `PietyComponent` consumers (UI/debug, monthly-attendance) read the same map;
   the syncretic drift writes a real belief entry so they stay consistent. Idle
   selection: `SoloDevotionBehavior` self-gates (unserved + devout + cadence) and
   sits below hobby, so it cannot starve hobby/director or trip the
   work-satisfied/`NO_ACTIONABLE_WORK` idle logic (it's an IDLE-activity peer,
   only active when the NPC is already idle).
3. **Sibling systems** — R2b attendance: a minority may still physically attend
   the dominant festival (attendance/override path unchanged) but now benefits
   less (reduced mood, drift not own-faith). R3a/R3d profile tuning unchanged —
   reconciliation layers on top of the scaled magnitudes. Liveliness idle
   director unaffected (devotion sits above it; director is still the catch-all).
4. **Exhaustive switches** — none touched. No new `Rite`/`EventType`/enum.

### Simplification Sweep

- Classes in scope: `FaithReconciliation` (new, 6 inbound call sites — 5
  RiteExecutor handlers + 1 PriestBehavior), `SoloDevotionBehavior` (new, 1
  registration site), `RiteExecutor` (edited), `PriestBehavior` (edited),
  `TownspersonMob` (registration).
- The cross-faith modifier is ONE helper hit from all six effect sites (not
  copy-pasted) — confirmed by grep. Solo devotion reuses the existing
  personal-behavior idiom (`HobbyBehavior` shape + `HobbyLocationResolver` +
  `BrainNavGuard`), no new framework, no new brain memory.
- No orphans created; no overlapping pair introduced (FaithReconciliation is the
  consolidation point the prompt called for).

### Deviations from prompt

- The prompt named only `RiteExecutor` attendee effects + the R3d-1 crowd pulse
  as cross-faith sites. I routed ALL five village-wide communal handlers
  (incl. feast + vigil, which carry mood but no piety) through the helper for
  one consistent reconciliation point — feast/vigil minorities now get the
  reduced mood (no drift, since those rites grant no piety). Bounded, and avoids
  a half-reconciled set of handlers.
- Atheists (no primary faith): previously the communal handlers credited a
  `SUNSTEAD` fallback at full strength. Now an atheist gets the reduced mood and
  a small drift toward the officiating faith (acculturation) — i.e. the
  unaffiliated slowly pick up the local faith rather than silently banking
  Sunstead piety. This is the intended direction; flagged as a behavior change.
- Solo-devotion cadence + reward live in a field, not a new `*_COOLDOWN` memory:
  the only cost is the throttle resets on save/load (worst case one extra
  devotion after a reload) — acceptable for a flavor behavior, and it keeps the
  freeze-trap surface at zero.

### Out-of-scope but flagged

- Secondary shrines / building-faith / minority clergy → R3e-2. Today every
  religious building serves the dominant faith, so "unserved locally" == minority
  faith (the simplification the prompt specified). When R3e-2 adds per-building
  faith, `isUnservedLocally` should consult the building's faith, not just the
  village dominant.
- Pilgrimage / travel to a served venue → R3e-3.
- Solo devotion currently always uses HOME as the quiet spot; a dedicated
  shrine/altar spot is an R3e-2 concern.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac runs). Static review done:
grep-verified all six cross-faith sites route through `applyCommunalBenefit`, the
three remaining own-faith `SUNSTEAD` fallbacks are personal rites (untouched),
no new enum/exhaustive-switch, `brainMemories()` unchanged (no freeze trap),
imports reconciled (`FaithReconciliation` added to PriestBehavior, unused
`ReligionRegistry` dropped).

### Smoke test (user-runnable)

1. In a Sunstead village, drop a minority believer (a Tidecall NPC, Tidecall
   belief ~0.3). When idle/leisure and the village has a priest, confirm they
   periodically walk home and perform "Private devotion" (no officiant) — their
   Tidecall belief ticks up slowly and mood lifts a little.
2. Run a Sunstead grand festival (or any holy day). Confirm the Tidecall NPC, if
   present at the venue, gets a REDUCED mood bump and a tiny SUNSTEAD drift (not
   Tidecall piety), while Sunstead NPCs get the full mood + their Sunstead faith
   deepens. (Check the piety/belief debug readout before/after.)
3. Confirm a served Sunstead majority NPC behaves exactly as in R3d — full
   festival benefit, and they do NOT perform solo devotion.
4. Confirm solo devotion is low-priority: an NPC with real work or an active
   social/hobby task does that instead; devotion only fills genuine idle time,
   on a long cadence (not every tick).
5. Vacate the VILLAGE_PRIEST office in a single-faith Sunstead village; confirm
   devout Sunstead NPCs now also count as "unserved" and perform solo devotion
   (own-faith upkeep while the seat is empty). Re-seat a priest → devotion stops.
6. No NPC freeze (no new brain memory); rite officiation, fronting, and R2b
   attendance unaffected.

---

## R3e-2 — Building-faith + secondary shrines + minority clergy (2026-06-08)

Second of the R3e multi-faith mini-arc. Makes a minority faith *locally served*
by giving it a shrine that **carries its faith**, a priest of that faith, and a
generalized served test — so a minority moves up the practice ladder from solo
devotion (R3e-1) to a real congregation. Core shipped; shrine-faith **calendar**
scheduling split to R3e-2b (see stretch decision). NBT hook **deferred** (no live
faith-selection site).

### Disposition (findings, verified on branch)

- **Building.java codec** — 12 existing fields in `Building.CODEC`
  (id/name/type/shape/structureId/buildingLevel/rotation/condition/variantId/
  primaryColor/accentColor/roofColor) + the inner 4-field `SHAPE_CODEC`. Adding
  `patronFaith` → **13 fields, well under the 16-field RecordCodecBuilder cap**.
  `structureId` comes from the V2 planner; `variantId` defaults via
  `BuildingVariant.defaultVariantId(type)` (type-keyed, in the placement layer).
- **Culture/religion is resolved at the VILLAGE level** (`CultureResolver.of`
  → kingdom → culture → one religion), so a fresh single-kingdom village seeds
  **everyone on one faith @0.3** — minorities only arise from mixing
  (migration/visitors/multi-culture). **Mismatch flagged:** "largest unserved
  minority among existing NPCs" is usually EMPTY in a fresh village, so the
  shrine-faith default needs a deterministic non-dominant fallback (handled).
- **Spawn-belief flow** — `assignToBuilding` → `applyVillageCulture` sets the
  culture religion @0.3 for ALL NPCs (incl. a shrine priest). `spawnNpcInBuilding`
  (VillageInhabitantPopulator) has the `building` object in hand and already
  calls `ClergyOrders.assignClergyOrder` right after `assignToBuilding` — the
  natural hook for shrine faith + clergy belief.
- **ClergyOrders.assignClergyOrder (R3c)** — resolved the order via
  `villageReligionId(level, npc)` (dominant-only). Generalized to building faith.
- **FaithReconciliation.isUnservedLocally (R3e-1)** — was "primary != dominant,
  or no seated VILLAGE_PRIEST office holder." Generalized to "no seated same-faith
  priest" (building-faith driven, covers temple AND shrine).
- **Schedulers** — `VillageEventScheduler` (`checkCulturalHolyDay`,
  `checkSignatureRite`, `checkGrandFestival`) + `RiteScheduler` are ALL keyed on
  `villageReligionId`. Scheduling a non-dominant faith's calendar ceremonies
  means iterating [dominant + shrine faiths] in each scheduler with per-faith
  dedup/supersede — **large, multi-file, touches the supersede guards**. Split to
  R3e-2b (decision below).
- **Building→index gap** — `village.addBuilding` (adapter line 500) stores only
  the building UUID; `data.buildingIndex` is not guaranteed populated at populate
  time, so `getBuildingById` can be empty mid-spawn. Handled (belief fallback in
  `clergyFaith`).
- **No manual shrine-spawn command** exists; the populator is the spawn path.
  `BuildingType.SHRINE` confirmed present (no new enum).

### Design

- **One canonical resolver** `Npc/Religion/BuildingFaith`:
  - `isReligiousBuilding(type)`, `resolveFaith(level, village, building)`
    (patronFaith ?? village dominant; null for non-religious),
  - `clergyFaith(level, village, npc)` (building faith, falling back to the
    priest's own primary belief when the index is cold at spawn — see gap above,
    then village dominant),
  - `applyClergyFaith(...)` (set a shrine priest's belief to the building faith
    @`CLERGY_STRENGTH=0.6`; **no-op when faith already == the dominant seed**, so
    temple/chapel priests + single-faith villages are untouched),
  - `largestUnservedMinority(...)` (most-common non-dominant primary among loaded
    village NPCs; deterministic first-non-dominant fallback when none),
  - `hasSeatedPriestOfFaith(...)` (the served test core — one entity scan resolves
    each loaded village priest's building faith).
- **Minority clergy** wired in `spawnNpcInBuilding`: a SHRINE with no patron
  adopts `largestUnservedMinority` (persisted via `markDirty`, same instance the
  spawner registered); then `applyClergyFaith` sets the priest's belief; then the
  existing `assignClergyOrder` (now building-faith-resolved) gives the Tidewardens.
- **Served test** generalized in `isUnservedLocally` → `!hasSeatedPriestOfFaith`.
- **Debug override** `/religion shrine <religionId>`: sets the nearest shrine's
  patron faith and re-consecrates its loaded priest (unlock spec → `applyClergyFaith`
  → `assignClergyOrder` → relock via assign).

### What shipped

- `Village/Building.java` — `patronFaith` field + 13th codec field
  (`optionalFieldOf`, absent on pre-feature saves) + getter/setter.
- `Npc/Religion/BuildingFaith.java` (new) — the canonical resolver (above).
- `Npc/Religion/ClergyOrders.java` — order resolved by `BuildingFaith.clergyFaith`
  (renamed the private `villageReligionId` → `clergyFaith`); no forked path.
- `Npc/Religion/FaithReconciliation.java` — `isUnservedLocally` now building-aware
  (`hasSeatedPriestOfFaith`); dropped the now-unused `OfficeRegistry` import + the
  `villageHasPriest` helper.
- `Village/Buildings/Inhabitants/VillageInhabitantPopulator.java` — shrine-faith
  default + clergy belief at spawn (before the order assignment).
- `Commands/ReligionDebugCommand.java` — `/religion shrine <religionId>` override.

### Tie-In Audit

1. **Upstream feeders** — `spawnNpcInBuilding` now sets building faith + worker
   belief; `villageReligionId` unchanged; `largestUnservedMinority` reads loaded
   village NPC beliefs (the per-culture spawn seed is the source).
2. **Downstream callers** — `ClergyOrders` (order by building faith),
   `FaithReconciliation` served test (drives `SoloDevotionBehavior`, now dormant
   for a served minority) + the R3e-1 cross-faith benefit (unchanged — minorities
   still get reduced benefit at the dominant festival). `PriestBehavior`
   fronting/officiating/bless aura is faith-agnostic over the village rite pool,
   so a shrine priest already serves the congregation's officiated/personal rites
   + bless aura + fronts village RELIGIOUS_RITE gatherings — no change needed for
   the core. Schedulers left dominant-keyed (R3e-2b). NBT/structure selection —
   deferred (no live site).
3. **Sibling systems** — R3c orders (now building-faith), R3a/R3d effect tuning
   (a shrine-faith ceremony, once R3e-2b schedules it, reads the shrine faith via
   the same `ReligionContent` path), R2b attendance (unchanged).
4. **Exhaustive switches** — `BuildingType`: no new value; `isReligiousBuilding`
   is a 3-way `||`, not a switch. `PriestBehavior.kindOf` already handles
   TEMPLE/CHAPEL/SHRINE — untouched. Confirmed no new enum.

### Simplification Sweep

- Classes in scope: `BuildingFaith` (new — 5 inbound sites: populator,
  ClergyOrders, FaithReconciliation, ReligionDebugCommand, + self),
  `Building`/`ClergyOrders`/`FaithReconciliation`/`VillageInhabitantPopulator`/
  `ReligionDebugCommand` (edited). One resolver feeds every consumer; `ClergyOrders`
  + `isUnservedLocally` were **generalized in place**, not forked. Field-cap
  headroom: 13/16 on `Building`.
- **Core vs R3e-2b:** shipped the building-faith attribute, minority clergy, and
  the generalized served test (core). Deferred shrine-faith **calendar** scheduling
  to R3e-2b because every scheduler is dominant-keyed and threading a second faith
  through `checkCulturalHolyDay`/`checkSignatureRite`/`checkGrandFestival` +
  `RiteScheduler` (with per-faith dedup + the supersede guards) is a large,
  separable change. The shrine still upgrades the congregation solo→served via the
  priest's presence (bless aura + officiating the village rite pool + fronting).

### Deviations from prompt

- **Served = "seated (loaded) same-faith priest."** `hasSeatedPriestOfFaith`
  scans loaded entities, so "seated" means "loaded." In practice a village's
  priests are co-loaded with their congregation (compact bounds, same chunks), so
  this matches the spec intent; the rare edge (a dominant NPC far from an unloaded
  priest doing occasional solo devotion) is acceptable flavor. This also makes the
  test staffing-sensitive rather than office-sensitive — if a temple priest dies,
  dominant NPCs fall back to solo devotion (a reasonable generalization of R3e-1's
  office-vacancy behavior).
- **Shrine-faith default fallback.** Because culture is village-level, a fresh
  village has no minority population, so `largestUnservedMinority` returns the
  first registered non-dominant religion when no real minority exists — so a
  manually-spawned shrine still takes a distinct, testable faith. The
  `/religion shrine` override is the authoritative control.
- **Clergy belief strength 0.6.** A shrine priest is seeded DEVOUT (0.6) in the
  shrine faith (vs the 0.3 culture seed) so they reliably register as a same-faith
  servant; temple/chapel priests are left at their existing seed (no-op), so
  single-faith villages are unchanged.

### Out-of-scope but flagged

- **R3e-2b — shrine-faith calendar scheduling.** Schedule the shrine faith's
  holy-days/signature/grand festivals independently of the village dominant
  (thread a faith arg through the three `VillageEventScheduler` checks +
  `RiteScheduler`, per-faith dedup). Until then a minority is *served* (priest +
  officiated rites + bless aura) but gets no faith-specific holy-day festival.
- **NBT faith-aware structure/variant selection — DEFERRED (no live site).** The
  only selection is `BuildingVariant.defaultVariantId(type)` in the V2 placement
  layer (out of scope). The `patronFaith` field is now in place for the placement
  work / R3e-2b to consume (`defaultVariantId`/structure pick keyed by faith with
  a generic fallback). Adding an unconsumed hook now would violate "no speculative
  hook."
- **Pilgrimage → R3e-3.** Building placement/layout for shrines remains manual.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done:
codec at 13/16 fields; `patronFaith` `optionalFieldOf` (pre-feature saves default
to absent → temple/chapel derive dominant); all five `BuildingFaith` consumers
grep-verified; no new enum/exhaustive-switch; `brainMemories()` untouched (no new
brain memory); spawn-time index-gap handled via the `clergyFaith` belief fallback;
removed-helper (`villageHasPriest`) has no dangling refs; unused `OfficeRegistry`
import dropped.

### Smoke test (user-runnable)

1. In a Sunstead village, set a few NPCs to a Tidecall minority
   (`/religion set <uuid> tidecall 0.3`). Manually spawn (or place) a SHRINE and
   let it staff. Confirm the shrine takes the Tidecall faith (largest unserved
   minority, or via `/religion shrine tidecall`) and its priest is a Tidecall
   priest of the **Tidewardens** (check the priest's activity title / spec).
2. Confirm the Tidecall NPCs now register as SERVED — they STOP solo private
   devotion and gravitate to the shrine; they receive full (own-faith) benefit
   from shrine-priest interactions (bless aura) while still getting REDUCED
   benefit at the dominant Sunstead festivals (R3e-1 reconciliation intact).
3. Confirm a single-faith Sunstead village (no shrine) is UNCHANGED from R3e-1:
   temple/chapel priests stay dominant-faith, dominant NPCs are served, no solo
   devotion.
4. Confirm pre-existing buildings (saved before this phase, no `patronFaith`)
   load cleanly and default to the dominant faith (temple/chapel unaffected).
5. `/religion shrine sunstead` on the Tidecall shrine → it flips to Sunstead and
   the loaded priest is re-consecrated (Order of the Dawn); the Tidecall NPCs
   become unserved again and resume solo devotion.
6. No NPC freeze (no new brain memory); rite officiation, fronting, R2b
   attendance, and R3e-1 cross-faith reconciliation all unaffected. (Shrine-faith
   holy-day festivals do NOT yet fire — that is R3e-2b.)

---

## R3e-2b — Shrine-faith calendar scheduling (2026-06-08)

The deferred piece of R3e-2. Generalizes the 365-axis religion-calendar
schedulers from "the village's dominant faith" to "every faith with a religious
building present," each ceremony at that faith's building, fronted by that
faith's priest, tuned to that faith — so a multi-faith village observes multiple
liturgical calendars in parallel. Completes the secondary-shrine story
(R3e-2 + R3e-2b).

### Disposition (findings, verified on branch)

- **`checkSignatureRite` / `checkGrandFestival` / `checkCalendarVigil`** — all
  three resolve `ReligionContent.villageReligionId` (dominant), check the faith's
  `calendar().effectiveDayOfYear(dayName)` on the **365-day liturgical axis**,
  dedup per day by EventType, and `scheduleEvent` (no explicit location — the
  blessing rite later pins it via `templeLocation`). These cleanly generalize
  per-faith. Signature/grand EventTypes are **faith-unique** (FIRST_FURROW =
  Sunstead, VOYAGE_BLESSING = Tidecall…) so type-dedup already separates faiths;
  **VIGIL is a shared EventType**, so its dedup must additionally key on faith.
- **`checkCulturalHolyDay` — MISMATCH flagged.** This one is NOT a 365-axis
  religion-calendar check: it is keyed on the village **culture**
  (`CultureResolver.of`), fires on the **96-day seasonal axis** via
  `culture.schedule().holyDayInterval()`, and there is exactly one culture per
  village. Generalizing it per-faith would either change the dominant's timing
  (forbidden — "single-faith EXACTLY as before") or graft a second timing model
  onto minorities. **Left dominant-only**; a minority faith's headline
  observances are its signature/grand/vigil days (now per-faith). The minority's
  generic culture-interval feast is the one piece not generalized (flagged).
- **`RiteScheduler.scheduleBlessingRite`** — gated on `villageRitualises`
  (dominant) and located via `templeLocation` (the TEMPLE). Both are
  dominant/temple-bound and must become faith/venue-aware for a shrine gathering.
- **`BuildingFaith` (R3e-2)** — has `resolveFaith`, `isReligiousBuilding`,
  `clergyFaith`; needed (a) faiths-present→venue enumeration and (b)
  faith-at-a-location (to recover a blessing rite's faith from its venue without
  a new rite field). Both added.
- **`EventAttendance`** — `EventEffects.onEventStart` calls
  `applyVillageWideOverride` for any RELIGIOUS_RITE gathering: attendance is
  **village-wide (coarse)**, not faith-scoped. Per the prompt, kept coarse and
  rely on `FaithReconciliation` (R3e-1) to reduce non-adherents' benefit;
  attendees still converge on the linked rite's location (the shrine) via R2b.
- **`PriestBehavior` fronting (R3d-1)** — `tryStartFronting` claims ANY village
  RELIGIOUS_RITE gathering subject only to the R1a tier gate — **NOT faith-gated**.
  In a multi-faith village the temple priest could front a shrine festival.
  Added a same-faith gate (mismatch with the disposition's "naturally satisfied"
  — reported).

### Design

- **`BuildingFaith.religiousBuildingsByFaith(level, village)`** → `Map<faith,
  venue Building>`, TEMPLE-preferred within a faith (so the dominant's venue ==
  the legacy `templeLocation` when a temple exists). **One faith-stamp +
  venue threaded through scheduling:**
  - `VillageEventScheduler.scheduleEvent(…, faithId, venue)` overload stamps
    `CeremonyBlessings.FAITH_KEY` and pins the gathering location to the venue
    origin; the old 4-arg signature delegates with `(null, null)` →
    byte-identical legacy path.
  - `CeremonyBlessings.attach` reads the faith stamp + pinned location and passes
    them to a faith-aware `RiteScheduler.scheduleBlessingRite(…, faithId,
    location)` (gate = `religionRitualises(faithId)`, location = the venue).
  - `RiteExecutor.runOne` recovers the rite's faith via
    `BuildingFaith.faithAtLocation(rite.location())` (the venue building),
    falling back to the dominant — so the one-shot blessing's effect tuning AND
    its `FaithReconciliation` rite-faith are the shrine faith, not the dominant.
  - `PriestBehavior.tryStartFronting` fronts only when the gathering's faith ==
    the priest's `clergyFaith`, and sets `frontReligionId` to the gathering's
    faith (so the crowd-bless profile + reconciliation are faith-correct).
- **Per-faith dedup** — one shared `alreadyScheduledToday(data, village, type,
  faithId, tick)`: filters by faith stamp, so two faiths' same-day ceremonies
  (esp. shared-type VIGIL) both fire. Faith-unique types are unaffected.
- **The three checks become a 1-line per-faith loop** over
  `religiousBuildingsByFaith` + a `…ForFaith` body (the former single-faith body,
  parameterized by `(religionId, venue)`). The dominant-only path is the
  1-faith case.

### What shipped

- `Npc/Religion/BuildingFaith.java` — `religiousBuildingsByFaith` (TEMPLE-pref
  venue) + `faithAtLocation`.
- `Npc/Religion/RiteScheduler.java` — faith+location `scheduleBlessingRite`
  overload + `religionRitualises` (faith-aware gate); `villageRitualises`
  delegates.
- `Npc/Religion/RiteExecutor.java` — `runOne` tunes effects to the rite's venue
  faith (`faithAtLocation`), dominant fallback.
- `Village/Event/CeremonyBlessings.java` — `FAITH_KEY`; `attach` threads faith +
  venue into the blessing rite.
- `Village/Event/VillageEventScheduler.java` — `checkSignatureRite` /
  `checkGrandFestival` / `checkCalendarVigil` generalized per-faith; faith+venue
  `scheduleEvent` overload; shared `alreadyScheduledToday`. `checkCulturalHolyDay`
  untouched.
- `Npc/Brain/Behaviors/Production/PriestBehavior.java` — fronting same-faith gate
  + `frontReligionId` from the gathering faith.

### Tie-In Audit

1. **Upstream feeders** — `BuildingFaith.religiousBuildingsByFaith` (faiths +
   venues), the `% 365` liturgical axis, per-faith `ReligionContent` calendars.
2. **Downstream callers** — the three scheduler checks (per-faith) →
   `scheduleEvent`/`CeremonyBlessings.attach`/`scheduleBlessingRite` (faith + venue)
   → `RiteExecutor.runOne` (venue-faith effects). R3d-1 fronting (same-faith
   gate) + R2b attendance (coarse village-wide, converges on the shrine via the
   linked rite location). `FaithReconciliation` (R3e-1) reduces non-adherents'
   benefit at a shrine festival — over-inclusion self-corrects.
3. **Sibling systems** — R3e-2 building-faith/clergy (the shrine priest fronts +
   officiates its faith's gathering), R3b/R3d effects (faith-keyed via
   ReligionContent), the grand-festival supersede (dominant-only via
   `checkCulturalHolyDay`, unchanged).
4. **Exhaustive switches** — no `EventType`/`Rite` additions. `venueRank` is a
   3-arm `BuildingType` switch with a `default` (safe for the full enum).
   Confirmed dedup/supersede arms hold per-faith (faith-keyed dedup; supersede is
   the dominant's own concern, untouched).

### Simplification Sweep

- Classes in scope: `VillageEventScheduler` (3 checks generalized + 1
  scheduleEvent overload + 1 shared dedup), `BuildingFaith` (+2 methods, 3 new
  inbound sites), `RiteScheduler` (+1 overload +1 gate), `CeremonyBlessings`
  (faith-threading), `RiteExecutor` (venue-faith), `PriestBehavior` (faith gate).
- The dominant-only path is the 1-faith special case of one per-faith loop — not
  a second code path. The per-faith dedup is ONE shared helper. No parallel
  minority scheduler. No new enum/Rite/EventType/brain memory.

### Deviations from prompt

- **`checkCulturalHolyDay` left dominant-only** (disposition mismatch above): it
  is a culture-interval / 96-axis mechanism, not a per-faith religion-calendar
  check, so generalizing it cleanly is impossible without changing the dominant's
  timing. Minorities get their signature/grand/vigil days instead.
- **Fronting required an explicit same-faith gate** — the prompt expected this
  "naturally satisfied" by the presider + R1a gate, but R3d-1 fronting was NOT
  faith-gated (any capable village priest could claim any RELIGIOUS_RITE
  gathering). Added a `gatheringFaith == clergyFaith` gate; single-faith villages
  are unaffected (both resolve to the one faith).
- **Attendance kept coarse (village-wide)** per the prompt's allowance —
  `FaithReconciliation` reduces non-adherents' benefit. With two festivals active
  the same day, an NPC's single `eventOverride` slot resolves to whichever
  started last; both festivals still fire + are fronted. Faith-scoped attendance
  (adherents-only) is flagged below.
- **No-temple dominant edge:** a dominant faith with only a chapel now schedules
  at the chapel (its actual building) rather than the `villageCentre` fallback —
  a minor improvement; the common temple case is byte-identical.

### Out-of-scope but flagged

- **Faith-scoped attendance** — scope a shrine festival's congregation to its
  adherents (not village-wide), so two same-day festivals draw distinct crowds.
  Needs an attendee-model change (per-faith override resolution).
- **Minority generic holy-day feast** — the per-faith equivalent of
  `checkCulturalHolyDay` (a feast on a faith's non-signature/grand/vigil calendar
  days). Needs a faith→culture-schedule inverse that doesn't exist cleanly today.
- **Pilgrimage → R3e-3.** Shrine placement/NBT remains manual.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done: all
three checks route through the per-faith loop + shared faith-keyed dedup; the
5-arg `scheduleBlessingRite` + 4-arg `scheduleEvent` delegate to the new
overloads with nulls (legacy path identical); `faithAtLocation`/venue resolution
grep-verified; `checkCulturalHolyDay`/`isGrandFestivalDay`/supersede untouched;
no new enum/EventType/Rite/brain memory; fronting faith gate added; imports
reconciled (BuildingFaith into scheduler + PriestBehavior).

### Smoke test (user-runnable)

1. Sunstead village with a Tidecall shrine (R3e-2). `/religion calendar <village>`
   to read both faiths' day-of-year. Advance to Tidecall's **Voyage Blessing**
   (First Catch) / **Tides' Return** (Last Catch) / **Storm's Vigil** day and
   confirm a Tidecall gathering fires **at the shrine**, fronted by the Tidewarden
   shrine priest (not the temple priest), Tidecall NPCs converging there.
2. Confirm Tidecall adherents get FULL (own-faith) benefit at the shrine festival
   while any Sunstead NPCs present get REDUCED (FaithReconciliation) — check
   piety/mood before/after.
3. Advance to a Sunstead signature/grand day → its festival still fires at the
   TEMPLE, fronted by the Sunstead priest, exactly as before.
4. Arrange both faiths' ceremonies on the same liturgical day (via the calendars)
   → BOTH fire, each at its own building, no collision/supersede cross-talk
   (faith-keyed dedup).
5. Single-faith Sunstead village (no shrine): scheduling is unchanged — signature
   /grand/vigil at the temple, generic holy day via `checkCulturalHolyDay`.
6. No NPC freeze (no new brain memory); rite officiation, R3e-2 clergy, R3e-1
   reconciliation, and the grand-festival supersede all intact.

---

## R3e-3a — Pilgrim arrival / convergence (2026-06-08)

First half of pilgrimage (R3e-3): a village's grand religious festival draws
bounded PILGRIM visitors of the festival's faith, who converge on the venue,
swell the celebration, and despawn on the normal visitor lifecycle. The
resident-departure half (an adherent leaving and returning) is R3e-3b.

### Disposition (findings, verified on branch)

- **`VisitorFluxEngine`** — `spawnVisitor(level, village, vdata, type)` is the
  public on-demand entry (returns `Optional<TownspersonMob>`); it does NOT itself
  enforce the cap (the daily `tickVillage` does, via `VillageVisitorCapacity`).
  The private spawn builds a one-stop itinerary via `planItinerary` (for PILGRIM,
  `Activity.PRAY` → first TEMPLE/CHAPEL/SHRINE). Gap flagged: the auto-pick lands
  on the FIRST religious building, so a minority-faith pilgrim could be sent to
  the dominant temple, not its shrine — so the caller must specify the venue.
- **`VisitorType.PILGRIM` — MISMATCH flagged:** its `underlyingProfession()` is
  **PRIEST**. Three consequences traced: (a) `EventAttendance.applyVillageWideOverride`
  SKIPS PRIEST, so a pilgrim is NOT given the R2b eventOverride — it converges via
  its visitor itinerary instead (outcome identical: present at the venue). (b)
  `PriestBehavior` self-gates on a non-null assigned building; a visitor's
  buildingId is null, so a pilgrim never officiates/fronts. (c)
  `RiteScheduler.buildPriestsByVillage` groups ALL loaded PRIEST NPCs → a pilgrim
  would be handed an ordination (latent today; festival pilgrims make it more
  likely). Fixed with a visitor guard (below).
- **`BuildingFaith` (R3e-2/2b)** — `religiousBuildingsByFaith(faith→venue)` gives
  the festival venue; `CeremonyBlessings.FAITH_KEY` stamps the festival faith
  (R3e-2b). The served test `hasSeatedPriestOfFaith` already excludes pilgrims
  (no building → resolveFaith null → no match), so a visiting pilgrim does NOT
  falsely mark resident adherents "served" — confirmed, no guard needed there.
- **`EventEffects.onEventStart`** — the festival-ACTIVE hook (category-aware,
  already calls `applyVillageWideOverride` for RELIGIOUS_RITE). The natural,
  once-per-festival trigger point.
- **`FaithReconciliation` (R3e-1)** — a same-faith attendee gets full benefit; a
  faith-tagged pilgrim is a co-religionist, so the R3d-1 crowd pulse + one-shot
  blessing benefit them fully. Over-inclusion would self-correct (reduced
  benefit), but tagging makes it exact.

### Design / decisions

- **Trigger on grand festivals only** (`HARVEST_HOME` / `GREAT_WEAVING` /
  `TIDES_RETURN` / `FOUNDING_DAY`, R3d-2) — the year's biggest, annual, unambiguous
  "grand religious festival." A `drawsPilgrims(EventType)` predicate (switch +
  default; not exhaustive). Signature rites / cultural holy days are an easy
  later addition to that predicate (flagged).
- **Spawn directly on festival start; do NOT mint a `PILGRIM_CONVERGENCE` event.**
  Pilgrims attend the EXISTING grand-festival gathering — a separate convergence
  event would be redundant. `PILGRIM_CONVERGENCE` (the flux-driven path) is left
  untouched.
- **New thin trigger `Npc/Visitor/PilgrimConvergence`** — resolves faith (FAITH_KEY
  → dominant) + venue (`religiousBuildingsByFaith`), bounds the count to remaining
  visitor capacity, and calls a new `VisitorFluxEngine.spawnVisitorTo(..., venueId)`
  per pilgrim. 2–4 pilgrims, clamped to `maxConcurrent − current loaded visitors`
  (the same accounting `tickVillage` uses) — never exceeds the cap.
- **`VisitorFluxEngine.spawnVisitorTo`** — a minimal overload that points the
  visitor's single itinerary stop at a SPECIFIC building (the festival venue),
  reusing the entire spawn/wallet/despawn/`VisitorState` lifecycle (the private
  spawn just took an optional `itinOverride`; both existing callers pass null).
- **Faith tag** — `tagFaith` drops the spawn-time culture seed if it differs and
  seeds the festival faith at DEVOUT (0.6), so the pilgrim reads as a same-faith
  adherent (full benefit) for BOTH a dominant and a shrine festival.

### What shipped

- `Npc/Visitor/PilgrimConvergence.java` (new) — the festival-start trigger.
- `Npc/Visitor/VisitorFluxEngine.java` — `spawnVisitorTo` (targeted-itinerary)
  overload + `itinOverride` plumbing through the private spawn.
- `Village/Event/EventEffects.java` — `onEventStart` calls
  `PilgrimConvergence.onFestivalStart` (no-op for non-grand events).
- `Npc/Religion/RiteScheduler.java` — `buildPriestsByVillage` excludes visitors
  (tie-in fix: a PILGRIM's PRIEST profession must not draw an ordination).

### Tie-In Audit

1. **Upstream feeders** — the R3d-2 grand-festival scheduler → gathering ACTIVE →
   `onEventStart`; `BuildingFaith` (faith + venue); `VillageVisitorCapacity` (the
   bound). All read-only here.
2. **Downstream callers** — `VisitorFluxEngine.spawnVisitorTo` (extra bounded
   spawns, cap honoured); the visitor itinerary/`VisitorGoal` (pilgrims walk to
   the venue to PRAY = attend); `FaithReconciliation` (same-faith full benefit via
   the R3d-1 crowd pulse + one-shot rite); the despawn lifecycle (pilgrims leave a
   day later). `applyVillageWideOverride` SKIPS pilgrims (PRIEST) — they attend via
   itinerary, not the R2b override (deviation, same outcome).
3. **Sibling systems** — R2b attendance (pilgrims converge via itinerary), R3d-1
   fronting (pilgrims have no building → never front), R3d-2 grand festivals (the
   trigger), the economy `VisitorChannel`/`estimateFlux` (a few extra transient
   visitors → incidental trade; no surprise — they use the standard wallet + the
   cap is unchanged). `RiteScheduler` ordination/consecration scans now skip
   visitors. The R3e-2 served test already excludes pilgrims (no building).
4. **Exhaustive switches** — none added; `drawsPilgrims` has a `default`. No new
   enum / EventType / Rite / brain memory.

### Simplification Sweep

- Classes in scope: `PilgrimConvergence` (new, 1 inbound call from EventEffects),
  `VisitorFluxEngine` (+1 overload, lifecycle reused), `EventEffects` (1 trigger
  line), `RiteScheduler` (1-line visitor guard). This is a thin trigger over
  `spawnVisitor` — NO parallel spawn path, NO cap bypass, NO new visitor
  mechanism. The capacity cap + itinerary + despawn are all reused as-is.

### Deviations from prompt

- **Pilgrims attend via their visitor ITINERARY, not the R2b eventOverride.**
  `VisitorType.PILGRIM.underlyingProfession()` is PRIEST, and
  `applyVillageWideOverride` deliberately skips PRIEST (so real clergy officiate
  rather than congregate), so a pilgrim never receives the eventOverride. Pointing
  the pilgrim's itinerary at the festival venue (VisitorGoal → PRAY there)
  achieves the same convergence more reliably for a visitor. Outcome identical:
  present at the venue, full co-religion benefit.
- **Added a visitor guard to `RiteScheduler.buildPriestsByVillage`** — a tie-in
  correction the PILGRIM=PRIEST overlap forced. Latent before this phase (the flux
  engine already spawns pilgrim-priests); festival convergence makes it more
  likely, so fixed here rather than deferred.
- **Grand festivals only.** "High holy day" in the prompt is realized as the four
  grand festivals (the clearest "grand religious festival"); signature rites /
  cultural holy days are a one-line `drawsPilgrims` extension, flagged.

### Out-of-scope but flagged

- **R3e-3b — resident departure:** a realized adherent leaving the village as a
  traveller on pilgrimage and returning (the traveller/caravan map layer).
- **Wider pilgrim draw:** extend `drawsPilgrims` to signature rites / cultural
  high holy days if those should also attract visitors.
- **Faith-scoped festival attendance** (still coarse from R3e-2b) — unchanged here.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done:
`spawnVisitorTo` overload + `itinOverride` threaded through the private spawn (both
existing callers pass null); trigger wired once in `onEventStart`, gated on
`drawsPilgrims`; capacity bound mirrors `tickVillage`; faith tag makes pilgrims
co-religionists; visitor guard added to the ordination/consecration scan; no new
enum/brain memory; accessors (`isVisitor`, `getPiety`, `getVisitorState`)
confirmed.

### Smoke test (user-runnable)

1. Advance a Sunstead village to its grand festival (Harvest Home @ Harvest
   Equinox). Confirm 2–4 PILGRIM visitors spawn at the village edge, walk to the
   temple (the festival venue), and gather there — swelling the crowd.
2. Confirm the pilgrims benefit FULLY (same-faith): watch the R3d-1 crowd-bless /
   festival mood + piety land on them like resident Sunstead adherents.
3. Confirm a SMALL village isn't mobbed — the pilgrim count is clamped to the
   remaining visitor capacity (`/visitor` capacity vs current); at cap, zero
   pilgrims spawn.
4. Confirm a NON-festival day spawns no extra pilgrims (only the normal flux).
5. Confirm a shrine-faith grand festival (R3e-2b, e.g. a Tidecall shrine's Tides'
   Return) draws pilgrims of the SHRINE faith, pointed at the SHRINE, benefiting
   fully — while the dominant faith's NPCs at that festival get reduced benefit.
6. Confirm the pilgrims despawn on the normal visitor lifecycle (~a day after the
   festival), and that none were handed an ordination (the visitor guard) or
   started officiating/fronting (no building).

---

## R3e-3b — Resident pilgrimage: DISPOSITION + SUB-SPLIT PROPOSAL (no code shipped) (2026-06-08)

Per the prompt's instruction ("the disposition is the bulk of the work… if, after
mapping, this is too large for one clean pass, STOP and propose a sub-split rather
than forcing it"), this entry maps the caravan realized↔traveller↔return
lifecycle, specifies the pilgrim mirror, and **proposes a two-way vertical split**.
No code shipped — awaiting greenlight on the split + two design forks.

### Mapped caravan lifecycle (the pattern to mirror)

- **`TravellingGroup`** (interface) + **`TravellingGroupEngine.tick`** (stateless,
  generic): resolves `getPath()` (a plain `List<BlockPos>`; `computePosition`
  interpolates by index — **no TradeRoute required by the engine itself**),
  promotes simulated→spawned within `SPAWN_RADIUS=80` / demotes within
  `DESPAWN_RADIUS=128`, advances progress (`BASE_PROGRESS_PER_TICK=0.002 ×
  speedMult`) while simulated, fires `onPathComplete` once at progress≥1.0. This is
  the **genuinely reusable core**.
- **`Roster`**: persistent `principalId` (the pooled real villager, tracked by
  UUID across spawn/despawn) + `originBuildingId` + transient spawned-id lists.
  Reusable for a single-member pilgrim (principal = the resident).
- **`Caravan implements TravellingGroup`** (concrete): 12-field codec, but
  **trade-coupled** — `routeId`→`TradeRoute`, `getPath`/`getSpeedMultiplier` via
  `WorldRoadGraph` edge maintenance, goods/shoppingList/guardCount/`CaravanKind`,
  economy settlement on delivery. NOT a generic base.
- **`CaravanSavedData`** owns storage + `tick`:
  - **Conversion (resident→traveller)** = dispatch: `villageData.reserveIdleMerchant(villageId)`
    draws a MERCHANT principal; `Caravan.create(...)` records origin/dest/principal;
    realization assigns `NpcRoleTypes.CARAVAN_PRINCIPAL` + `setCaravanId`.
  - **Realize** (`spawnCaravanEntities`): find the pooled principal entity by UUID
    (or spawn fresh on inconsistency), position at the path point, assign role.
  - **Demote** (`despawnCaravanEntities`): `mob.discard()` but KEEP the away-state
    (the role component / `caravanId`) so it re-realizes later.
  - **Return + reintegration** = `tick`, state==RETURNING && progress≥1.0:
    `clearCaravanRoles(mob)` (drops the away-state → normal resident) then despawn
    + remove the caravan.
- **Away-state representation**: `TownspersonMob.setCaravanId/isCaravanMember`
  (a `CARAVAN_ID` entityData string) + the **role component**
  (`NpcRoleTypes.CARAVAN_PRINCIPAL/ESCORT`). The old `currentExpeditionId` was
  migrated INTO the role component (6.3.2.a). So the away-state is **caravan-role-
  specific**, not a generic "resident is away" flag.
- **Map layer**: `KingdomMapScope` GATHERS snapshots by iterating
  `CaravanSavedData` + `BoatCaravanSavedData`, attaching `TravellerType`
  explicitly; `TravellerSnapshot` (network DTO, keyed to its route polyline by the
  **unordered village pair**); `TravellerLayer.iconColor/typeLabel` (switches WITH
  `default` arms — a new enum value renders generic until given arms);
  `ClientTravellerCache` interpolates client-side over the **synced route polyline
  for the village pair**.

### Pilgrim mirror — what's reusable vs new

- **Reuse as-is**: `TravellingGroup` + `TravellingGroupEngine` + `Roster` + the map
  DTO/cache + the engine's realized-vs-simulated + despawn/respawn radii.
- **New (the bulk)**: `TravellerType.PILGRIM` (+ TravellerLayer arms + KingdomMapScope
  gather block); a `Pilgrimage implements TravellingGroup` (single principal,
  home/dest village, progress, OUTBOUND/RETURNING, its OWN path + codec); a
  `PilgrimageSavedData` (store + `tick` + dispatch/decision + conversion +
  reintegration + boon + persistence) registered alongside `CaravanSavedData.tick`.
- **Design forks (NOT cleanly reusable — these are why a sub-split is warranted)**:
  1. **Away-state**: the caravan away-state is `NpcRoleTypes.CARAVAN_PRINCIPAL` +
     `setCaravanId`. A pilgrim needs its OWN away-state (a new
     `NpcRoleTypes.PILGRIM` role mirroring CARAVAN_PRINCIPAL) — reusing the caravan
     role would mis-flag a pilgrim as a caravan member (and could trip
     `CaravanMerchantBehavior`/`isCaravanMember` consumers). Population/needs
     correctness while away rides on this role.
  2. **Map polyline for a non-trade village pair**: the map draws a traveller by
     interpolating a **route polyline keyed by the village pair**, which exists
     only for TradeRoute-connected pairs. A pilgrim to a same-faith festival
     village with no trade route has **no polyline → can't be drawn**. Options:
     (a) restrict destinations to route-connected villages (reuses the polyline
     machinery, simplest); (b) compute + sync a road-graph polyline for the pair;
     (c) straight-line fallback.
  3. **Reintegration headcount/economy**: confirm an away pilgrim doesn't trip
     "missing worker"/needs alarms at home (caravans solved this via the role
     component — the new PILGRIM role must be excluded from the same scans).

### Why this is too large for one clean pass

A faithful mirror is ~6–8 new/changed files including a **new codec-bearing
`PilgrimageSavedData`**, a new `TravellingGroup` impl with its own path strategy,
**map-network wiring** (gather + polyline for non-route pairs), a **new role +
away-state** with population/economy tie-ins, and a tick-loop registration —
none of which I can compile-verify (sandbox blocks maven). Shipping that in one
unverifiable pass is exactly the "works in isolation, downstream untested"
regression the project guards against. The enum also must NOT land speculatively
(no-speculative-enum rule) — it ships WITH its producer.

### Proposed sub-split (vertical; each slice has a real consumer + is verifiable)

- **R3e-3b-1 — Pilgrimage lifecycle infra (debug-triggered).** `TravellerType.PILGRIM`
  (+ TravellerLayer icon/label arms + KingdomMapScope gather), `Pilgrimage`
  TravellingGroup (single principal, home/dest, path, codec), `PilgrimageSavedData`
  (store + engine tick + RETURNING-complete reintegration + persistence + tick
  registration), the new `NpcRoleTypes.PILGRIM` away-state + conversion/reintegration,
  and a `/religion pilgrimage <destVillage>` debug command as the **producer**
  (forces a realized adherent's full convert→travel→return→reintegrate cycle).
  Delivers + verifies the WHOLE lifecycle, the map icon, and reload correctness —
  no autonomous decision yet, so the enum has a concrete consumer (the command).
- **R3e-3b-2 — Autonomous decision + festival attendance + boon.** The realized-only,
  bounded, occasional decision (devout + `isUnservedLocally` + a reachable
  same-faith village with a grand festival due via R3d-2/`BuildingFaith`),
  destination festival attendance (full co-religion benefit), and the modest
  return boon — layered on the R3e-3b-1 infra.

### Open questions for greenlight (these change R3e-3b-1's scope)

- **Away-state**: new `NpcRoleTypes.PILGRIM` role (recommended) vs. generalize the
  existing away-flag?
- **Destination reachability**: restrict pilgrim destinations to TradeRoute-
  connected same-faith villages (recommended for R3e-3b-1 — reuses the map
  polyline) vs. add a road-graph/straight-line polyline fallback now?

### Tie-In Audit (of the proposed work)

1. **Upstream feeders** — the decision trigger (R3e-3b-2): eligible adherent
   (`isUnservedLocally` + piety) + destination (R3d-2 grand-festival schedule +
   `BuildingFaith` faith match + route reachability).
2. **Downstream callers** — `TravellingGroupEngine` (drives the group),
   `KingdomMapScope`/`TravellerLayer`/`ClientTravellerCache` (map), the new
   `PilgrimageSavedData` tick, `TownspersonMob` resident↔traveller (new PILGRIM
   role), persistence.
3. **Sibling systems** — R3e-3a VISITOR pilgrims (DISTINCT path — a resident
   pilgrim keeps their real profession/identity and is never routed through
   `VisitorFluxEngine`; confirmed no cross-talk), home-village population/needs
   while away (the new role must be excluded like CARAVAN roles), R2b attendance
   at the host festival.
4. **Exhaustive switches** — `TravellerType` (`byId` is bounds-safe;
   `TravellerLayer.iconColor/typeLabel` have `default` arms — PILGRIM needs
   explicit arms; `KingdomMapScope` gather needs a pilgrim block). `NpcRoleTypes`
   if a PILGRIM role is added.

### Simplification Sweep (of the proposed work)

The pilgrim path is a **thin specialization** of the TravellingGroup lifecycle
(single member, religious dest/return boon), NOT a copy of Caravan — it reuses the
interface + engine + Roster + map DTO, and adds only `Pilgrimage` +
`PilgrimageSavedData` + the enum/role/command. Genuinely shared: engine, Roster,
map DTO/cache, despawn/respawn. Pilgrim-specific: the group impl, its SavedData,
the away role, the decision, the boon. No parallel traveller/away framework.

### Deviations from prompt

- **No code shipped this phase.** The prompt explicitly authorized a STOP +
  sub-split proposal if mapping showed the work too large for one clean pass; it
  is (new codec SavedData + map-network + away-role + 3 design forks, all
  unverifiable here). Proposing R3e-3b-1 / R3e-3b-2 instead of forcing it.
- The "reuse the caravan conversion/reintegration SITES" framing doesn't hold
  literally — those sites are caravan-role/merchant-specific
  (`reserveIdleMerchant`, `clearCaravanRoles`, CARAVAN_PRINCIPAL). The pilgrim
  reuses the PATTERN (TravellingGroup lifecycle) with its own role + SavedData.

### Out-of-scope but flagged

- R3e-3b-1 + R3e-3b-2 as proposed. Map polyline for non-route village pairs (fork 2)
  if the user wants unrestricted destinations. The autonomous decision tuning.

### Build verification

No code shipped — nothing to compile. (Sandbox blocks maven.neoforged.net
regardless.) The next sub-phase will carry the standard build-verification-deferred
note. This entry is the durable disposition the sub-split builds on.

---

## R3e-3b-1 — implementation paused: identity-preservation blocker discovered (2026-06-08)

Began R3e-3b-1 (approved split + new `NpcRoleTypes.PILGRIM` + route-connected
destinations). Built the foundational pieces (PILGRIM role, `TravellerType.PILGRIM`
+ map arms, the `Pilgrimage` TravellingGroup), then hit a correctness blocker at
the realize/dehydrate layer and **reverted to a compiling state** (no partial code
left on the branch) per "ask before restructuring."

### The blocker

The caravan realize↔simulate machinery **`discard()`s its principal entity** on
despawn and **spawns a FRESH principal** on the next realization when the original
isn't found (`CaravanSavedData.spawnCaravanEntities` fallback; the `Roster` doc
states "only the principalId is currently a 'real' pooled villager… Phase 7d will
make the pool more robust"). For a generic caravan merchant a fresh stand-in is
fine. For a pilgrim — a **specific resident** — faithfully reusing that path means:

- If the player follows the pilgrim the whole way (the common smoke-test path),
  the same entity travels and reintegrates → identity preserved. ✓
- If the pilgrim despawns mid-journey (player leaves) and later re-realizes, the
  original entity was discarded → either a fresh villager is spawned (identity
  reset) or, if not, the resident risks being lost/stranded — colliding with the
  mandatory **"no NPCs lost as permanent travellers."**

This is a genuine design fork (resident identity vs. the simulated-position model)
that the caravan machinery does NOT solve, and it's **persistence-critical code I
cannot compile-verify** in the sandbox. Forcing it risks shipping resident-losing
or unverifiable entity-NBT-snapshot logic — exactly the kind of unaudited
correctness gamble the project's discipline forbids.

### Resolution options (need a decision before resuming)

- **A — Faithful caravan reuse, identity limitation flagged (simplest, lowest
  unverified risk):** mirror the caravan discard/re-find/fresh-fallback exactly;
  the lifecycle always COMPLETES + reintegrates (no permanent traveller). Identity
  is preserved when the player follows (the smoke test) and may reset on a
  despawn-while-unobserved cycle — identical to caravans' current Phase-7c
  behavior, documented as the same inherited limitation.
- **B — Keep the resident loaded (most correct without NBT, diverges from the
  simulated model):** never discard; drive the adherent as a long-range
  walking entity via a `PilgrimTravelBehavior`; the journey pauses when their
  chunk unloads and resumes on reload; the map icon derives from the tracked
  position. No identity loss; lighter persistence (role + a small tracking
  record); but doesn't use the TravellingGroup simulated-position path for the
  entity and the map-while-unloaded is approximate.
- **C — Full entity-NBT snapshot (most correct, highest unverified risk):** on
  depart, snapshot the resident's entity NBT into the `Pilgrimage` and discard;
  on return, recreate from the snapshot. Exact identity across reload, but
  version-specific entity save/load API I can't verify here.

Recommendation: **A** — it best honors the prompt's "reuse the caravan machinery,"
is implementable by mirroring proven code (lowest risk under no-compile), and its
only shortfall (strict identity across an unobserved despawn) is the SAME
limitation caravans already carry and is invisible in the follow-the-pilgrim
smoke test. B is a reasonable alternative if strict identity matters more than
machinery reuse.

No code shipped (reverted clean). Awaiting the identity-fork decision to resume
R3e-3b-1.

---

## R3e-3b-1 — Pilgrimage lifecycle infra, debug-triggered (2026-06-08)

Resumed after the identity-fork decision: **option A (faithful caravan reuse, the
identity limitation flagged and left for the shared/separate hardening the user
will do).** Ships the full resident-pilgrimage lifecycle (convert → travel →
return → reintegrate), map icon, and reload persistence, driven by a debug
command — the autonomous decision is R3e-3b-2.

### What shipped

- `Village/Travel/Pilgrimage.java` (new) — a single-member `TravellingGroup`
  (principal + home + route-connected destination + OUTBOUND/RETURNING + 8-field
  codec). `getPath` mirrors `Caravan.getPath` (segment / graph route);
  `onPathComplete` turns OUTBOUND around; `getSpeedMultiplier` = 1.0.
- `Village/Travel/PilgrimageSavedData.java` (new) — owns + ticks pilgrimages via
  `TravellingGroupEngine`; `realizePilgrim`/`dehydratePilgrim` copy the caravan
  principal machinery (find-by-UUID, discard on dehydrate, fresh fallback);
  `dispatchPilgrimage` converts a realized resident (assigns the PILGRIM role);
  `reintegrate` clears the role + applies a modest boon (piety +0.05, mood +15) on
  return. **Robustness add over caravans:** reintegration fresh-spawns the
  principal at home if it was lost while unobserved, so no adherent is left a
  permanent traveller ("no NPCs lost").
- `Npc/Roles/NpcRoleTypes.java` — new `PILGRIM` role (the away-state mirror of
  CARAVAN_PRINCIPAL; Conditional lifetime).
- `Npc/Brain/Behaviors/Trade/PilgrimTravelBehavior.java` (new) — drives realized
  road-walking + progress, mirroring `CaravanMerchantBehavior`; universal, self-
  gated on the PILGRIM role. Registered WORK @0 in `TownspersonMob.makeBrain`
  before `configureBrain` (so it pre-empts profession work while on pilgrimage,
  same idiom as the WORK @0 GreetPlayer).
- `Village/Travel/TravellerType.java` + `Gui/.../TravellerLayer.java` — `PILGRIM`
  enum value + icon (violet) + label arms.
- `Gui/.../KingdomMapScope.java` — a pilgrim gather block (reuses the land route
  polyline for the {origin,dest} pair — destinations are route-connected, so no
  map-network change).
- `Events/TickSystems.java` + `TickSubsystemRegistry.java` — `PilgrimageTickSystem`
  (priority 119, interval 20) registered alongside the caravan systems.
- `Commands/ReligionDebugCommand.java` — `/religion pilgrimage <npc> <destVillage>`
  the producer: sends a realized resident on a pilgrimage to a route-connected
  destination (validates resident-not-visitor, home/dest villages, and a route).

### Tie-In Audit

1. **Upstream feeders** — the debug command (producer); `getRouteBetween`
   (route-connected gate); the engine constants (SPAWN/DESPAWN radii, base speed).
2. **Downstream callers** — `TravellingGroupEngine` (drives the group);
   `KingdomMapScope`/`TravellerLayer` (map); `PilgrimTravelBehavior` (realized
   walk); `TownspersonMob` roles (PILGRIM away-state); persistence (new SavedData).
3. **Sibling systems** — R3e-3a VISITOR pilgrims: DISTINCT — a resident pilgrim
   keeps its identity/profession and is never routed through `VisitorFluxEngine`;
   the PILGRIM role is consumed ONLY by `PilgrimTravelBehavior` + `PilgrimageSavedData`
   (grep-confirmed), so no caravan/visitor code mis-handles it. Home population/
   needs are entity-scan-based, and the principal is discarded while away (like
   caravans), so it isn't double-counted. Caravan code untouched.
4. **Exhaustive switches** — `TravellerType`: `byId` bounds-safe; `iconColor`/
   `typeLabel` got explicit PILGRIM arms (had defaults); `KingdomMapScope` got the
   gather block. `NpcRoleTypes` is a registry (no switch). No new EventType/Rite.

### Simplification Sweep

A thin specialization of the TravellingGroup lifecycle: reuses the interface,
`TravellingGroupEngine`, the route-path resolution (`Caravan.resolveSegmentBlocks`
/ `GraphTradeRouteEstablisher`), the map DTO/polyline, and the role component.
New: `Pilgrimage` + `PilgrimageSavedData` + the PILGRIM role + the travel behavior
+ the command + tick registration. No parallel traveller/away framework; no new
brain memory (uses WALK_TARGET) → no freeze trap; codec at 8/16 fields.

### Deviations from prompt

- **Identity limitation (approved, option A):** principal handling copies the
  caravan Phase-7c machinery, so a pilgrim discarded while unobserved is re-spawned
  fresh (identity may reset). Preserved when the player follows (the smoke test);
  reintegration always returns *an* adherent home (no permanent traveller). To be
  hardened for caravans + pilgrims together in the user's separate pass.
- **Debug-triggered only (the R3e-3b-1 split):** the autonomous decision (devout +
  `isUnservedLocally` + a reachable same-faith grand festival) is R3e-3b-2.
- **Festival attendance at the destination** is deferred to R3e-3b-2 (R3e-3b-1's
  pilgrim arrives and turns around); the return boon is included so the lifecycle
  has an observable payoff.

### Out-of-scope but flagged

- R3e-3b-2: autonomous departure decision + destination grand-festival attendance
  (full co-religion benefit) + boon tuning.
- The shared caravan/pilgrim principal-identity hardening (the user's separate
  investigation).
- A bespoke pilgrim map icon (currently a violet dot + "Pilgrim" label).

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done: all
signatures cross-checked against the mirrored caravan code (`SavedDataType`,
`RoleAssignment.conditional`, `getRouteBetween`/`getRouteId`, route path accessors,
`findByUUID`, `setAssignedVillageName`, `getVillageCentre`); PILGRIM role consumed
only by its two owners; TravellerType arms + gather complete; tick system
registered; no new brain memory; codec 8/16.

### Smoke test (user-runnable)

1. Find a resident's UUID (e.g. via `/religion list` / an NPC tool) in a village
   that has a TRADE ROUTE to another village. Run
   `/religion pilgrimage <uuid> <destVillageName>`. Confirm the message and that
   the NPC sets out toward the route (activity "On pilgrimage...").
2. Open the kingdom map: confirm a violet PILGRIM icon moves along the route from
   home toward the destination, then reverses and returns.
3. Follow the pilgrim: confirm they walk the road, reach the destination village,
   turn around, and walk home (same entity = identity preserved when followed).
4. On arrival home, confirm they reintegrate as a normal resident (role cleared,
   resumes normal behavior) with a small piety + mood boon.
5. Save + reload mid-journey: confirm the pilgrimage resumes (persisted) and still
   completes + reintegrates.
6. Confirm `/religion pilgrimage` to a village with NO trade route is rejected
   (route-connected destinations only, R3e-3b-1); confirm a visitor UUID is
   rejected (residents only); confirm an ordinary villager (no pilgrimage) is
   unaffected — the PILGRIM behavior is inert without the role.

---

## R3e-3b-2 — Autonomous pilgrimage decision + destination attendance (2026-06-08)

Completes resident pilgrimage, **R3e, and the R3 content arc.** Makes pilgrimage
autonomous and purposeful on top of the R3e-3b-1 infra: a devout, locally-unserved
adherent departs on their own for a reachable same-faith grand festival, **attends
it** at the destination, and returns with a boon scaled by whether they actually
worshipped.

### Disposition (findings, verified on branch)

- `Pilgrimage`/`PilgrimageSavedData` (R3e-3b-1) — confirmed: `onPathComplete`
  (OUTBOUND) **immediately reversed to RETURNING** (no destination dwell). Added
  an AT_DESTINATION dwell. `dispatchPilgrimage` is the single convert path.
- `PilgrimTravelBehavior` — the realized driver; it sets RETURNING on OUTBOUND
  arrival. Redirected to dwell-and-attend.
- **`AttendGatheringBehavior` — MISMATCH flagged:** it is hard-scoped to the NPC's
  **home** village (`entity.getAssignedVillageName()` → `getVillageByName`) and
  gates on `isEventTime()` (an eventOverride). A resident pilgrim standing in a
  **foreign** host village resolves to its HOME village, so it CANNOT attend the
  host festival through R2b. → Destination attendance is done by walking the
  pilgrim to the host's festival venue and lingering (the R3d-1 crowd-bless
  reaches them by proximity) — the SAME mechanism R3e-3a's visitor pilgrims use.
- R3d-2 grand festivals + `BuildingFaith` + `FaithReconciliation.isUnservedLocally`
  — confirmed the inputs for picking a same-faith destination + an eligible
  pilgrim. The grand festival ACTIVE event carries the R3e-2b `FAITH_KEY` stamp,
  so the destination festival's faith is recoverable without the entity.
- Route connectivity — `data.getRouteBetween(a,b)` (the debug producer's gate) is
  reused so only reachable pilgrimages are dispatched.
- Cadence — no daily-scan source fit cleanly; used the **festival-START hook**
  (`EventEffects.onEventStart`, where R3e-3a already fires) as the event-driven
  trigger, plus a new `PILGRIMAGE_COOLDOWN` brain memory (added to
  `brainMemories()` — freeze trap handled) + a low per-adherent probability.

### Design / decisions

- **Trigger on the grand-festival START** (`PilgrimageDeparture.onGrandFestivalStart`,
  called from `onEventStart` after `PilgrimConvergence`): for each village
  route-connected to the host, loaded adherents of the host's faith who are devout
  (`PietyTier.DEVOUT`/`PIOUS`), locally unserved, not already travelling, and
  off-cooldown depart with `DEPART_CHANCE = 0.25`. Triggering on START guarantees
  a live festival at the destination; far adherents who can't arrive in time still
  return gracefully (missed boon). Bounded + rare: grand festivals are annual per
  faith, cooldown ~1 week, realized-only (scans loaded NPCs).
- **Dwell-and-attend** — new `AT_DESTINATION` state: OUTBOUND arrival →
  `arriveAtDestination` (dwell `DWELL_TICKS = 4000`) → RETURNING. The dwell state
  machine (`tickDwell`) runs in BOTH the engine's spawned + simulated ticks; it
  marks `attended` when a grand festival of the pilgrim's `faith` is active at the
  destination, and reverses when the dwell elapses. The realized behavior walks
  the pilgrim to the host's faith building (proximity crowd-bless) during the
  dwell. A new `faith` field on `Pilgrimage` lets the simulated path check the
  festival without the entity.
- **Boon tuning** — `reintegrate` scales by `attended`: piety +0.08 / mood +25 when
  the festival was attended, +0.02 / +8 for a missed journey. Bounded; the rare
  rate self-limits farming.
- **Behavior gate fix** — `PilgrimTravelBehavior` now only drives walking/progress
  while the group `isSpawned()` (realized); when simulated the engine owns
  progress, preventing a loaded-but-simulated pilgrim from double-advancing.

### What shipped

- `Village/Travel/Pilgrimage.java` — AT_DESTINATION + `faith`/`dwellUntilTick`/
  `attended` (codec 11 fields, the 3 new ones `optionalFieldOf` so R3e-3b-1-era
  saves load); `arriveAtDestination`, `tickDwell`, `festivalActiveAtDestination`,
  `isGrandFestival`.
- `Village/Travel/PilgrimageSavedData.java` — dispatch derives the pilgrim's faith;
  attendance-scaled boon.
- `Npc/Brain/Behaviors/Trade/PilgrimTravelBehavior.java` — `isSpawned` gate +
  AT_DESTINATION venue dwell-walk.
- `Npc/Religion/PilgrimageDeparture.java` (new) — the festival-start decision.
- `Village/Event/EventEffects.java` — wires the departure into `onEventStart`.
- `Npc/Brain/Memories/NpcMemoryTypes.java` + `Entities/custom/TownspersonMob.java`
  — `PILGRIMAGE_COOLDOWN` memory (registered + in `brainMemories()`).

### Tie-In Audit

1. **Upstream feeders** — `onEventStart` grand-festival start; the festival faith
   (`FAITH_KEY`); `BuildingFaith` (the destination has the faith), `isUnservedLocally`
   (eligible pilgrim), `getRouteBetween` (reachable), `PietyTier` (devout).
2. **Downstream callers** — `dispatchPilgrimage`/`Pilgrimage` (the dwell change),
   `reintegrate` (boon), `FaithReconciliation` (full co-religion benefit via the
   R3d-1 crowd-bless at the venue), the cooldown memory.
3. **Sibling systems** — R3e-3a VISITOR pilgrims: both now fire from the same
   festival-start hook and coexist — visitors are spawned AT the host (transient,
   PRIEST-profession), residents TRAVEL in from route-connected villages (keep
   identity); they're distinct entities, no double-count. R2b attendance unchanged
   (the foreign pilgrim attends by proximity, not the home-scoped override). The
   home village headcount: the departing adherent is discarded while away (like
   R3e-3b-1), so it isn't double-counted.
4. **Exhaustive switches** — `Pilgrimage.PilgrimState` gained `AT_DESTINATION`;
   grep-confirmed there is NO `switch` over it (only if-checks here + the codec's
   STRING xmap), and the Adventurer `switch (group.getState())` sites are a
   different enum. No new EventType/Rite.

### Simplification Sweep

- Classes in scope: `PilgrimageDeparture` (new, 1 inbound from EventEffects),
  `Pilgrimage`/`PilgrimageSavedData`/`PilgrimTravelBehavior` (edited), `EventEffects`
  /`NpcMemoryTypes`/`TownspersonMob` (wiring). The decision reuses the debug
  producer's `dispatchPilgrimage` + `getRouteBetween` validation (one dispatch
  path, not a parallel one). Destination attendance reuses the venue + R3d-1
  crowd-bless (no pilgrim-specific attend rite). One new brain memory (cooldown),
  in `brainMemories()`. Codec 11/16 (3 optional additions).

### Deviations from prompt

- **Attendance is NOT via `AttendGatheringBehavior`** — it is home-village-scoped
  (+ needs an eventOverride a resident pilgrim doesn't have), so a foreign pilgrim
  cannot use it. The pilgrim instead dwells at the host's faith venue and is
  benefited by the R3d-1 crowd-bless by proximity — the same approach as R3e-3a's
  visitor pilgrims, and the equivalent outcome (present at the venue, full
  co-religion benefit).
- **Trigger is the festival START** (event-driven), not a daily decision scan —
  the prompt offered "e.g. a daily per-village/per-NPC decision"; the start hook is
  more purposeful (guarantees a live festival) and equally bounded (grand festivals
  are annual). Still realized-only, cooldowned, low-probability.
- **Timing is tight for distant villages:** simulated OUTBOUND ≈ 10000t vs a
  12000t festival, so adjacent same-faith villages attend reliably while far ones
  may arrive after the festival and take the reduced boon (the graceful-miss path).

### Out-of-scope but flagged

- The shared caravan/pilgrim principal-identity hardening — **Garrett's separate
  task** (R3e-3b-1 + R3e-3b-2 copy the caravan machinery as-is).
- Wider/earlier departure timing (lead-time before the festival, faster pilgrims)
  if distant attendance should be more reliable.
- R4 economy interactions for pilgrim spending.

**This completes R3e (multi-faith) and the R3 content arc** of the Religion Rework.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done: codec
11/16 with optional R3e-3b-2 fields; no `switch` over the new `AT_DESTINATION`;
`dispatchPilgrimage` callers (decision + debug) both 5-arg; the new brain memory is
in `brainMemories()`; the `isSpawned` gate prevents double-progress; festival-faith
recovered via the R3e-2b `FAITH_KEY` stamp; `PilgrimageDeparture` wired once.

### Smoke test (user-runnable)

1. Two route-connected villages of the SAME faith — village H (host) has a religious
   building of that faith and a grand festival due; village A has a devout
   (DEVOUT/PIOUS piety) adherent of that faith who is locally unserved (A has no
   same-faith building/priest). Stand near A so its NPCs are loaded.
2. Let H's grand festival start (advance to its calendar day, or it fires on
   schedule). Confirm the A adherent occasionally (≈25%, cooldowned) departs as a
   violet PILGRIM on the map toward H.
3. Follow them: confirm they travel to H, walk to H's festival venue, and linger
   during the festival (activity "Attending the festival..."), getting the R3d-1
   crowd-bless as a full co-religionist (alongside any R3e-3a visitor pilgrims).
4. Confirm they then return to A and reintegrate with the **attended** boon (piety
   +0.08, mood +25). Trigger a journey that misses the festival (far village / late)
   and confirm the reduced boon (+0.02 / +8) and a graceful return.
5. Confirm a SERVED adherent (their faith has a local building+priest) does NOT
   depart; confirm departures are RARE (the per-NPC week cooldown blocks immediate
   re-departure); confirm only realized (loaded) adherents depart.
6. Confirm this is distinct from R3e-3a visitor pilgrims (residents keep their
   name/identity and travel in; visitors spawn at the host and are transient) and
   that a save/reload mid-journey resumes + completes (R3e-3b-1 persistence).

---

## R4a — Temple economy core: income → temple, clergy wages, upkeep (2026-06-08)

First phase of R4 (religious economy). The religious building's `BuildingEconomy`
is now the money hub: offerings + tithes flow IN; clergy wages + a daily upkeep
flow OUT — so a temple can run a surplus or a deficit. Civic festival boons stay
in the village treasury (untouched).

### Disposition (findings, verified on branch)

- **Central question — does a worker draw a wage from its `BuildingEconomy`
  today? NO.** The `BuildingEconomy` javadoc ("the NPC draws a wage from this
  treasury at end of work day") is **aspirational**. The real wage paths:
  - `TreasuryTickHandler.tick` (once/day per village, from `TickSystems`'
    daily `%24000` gate) pays GUARD/STOCKPILE_KEEPER/INNKEEPER/MERCHANT via
    `NpcEconomy.payWage` — from the **village treasury**. **PRIEST returns 0L**
    (`wageForProfession` default) → clergy are unpaid today.
  - `WorkplaceAssignmentManager.tickWeeklyPay` is **player-only** (pays from
    thin air via `CoinHelper`).
  - `Business.payAllWorkers` pays from the Business's own `treasuryBronze` (not
    `BuildingEconomy`); clergy have no Business.
  - `AbstractProductionBehavior` uses `BuildingEconomy` for production-input
    **purchases**, not wages.
  - `NpcEconomy.businessPay(buildingId, seller, amount, level, data)` IS the
    canonical **building-economy → wallet** path (withdraw + credit + visual +
    setDirty), but all-or-nothing (`canAfford(amount)`).
- **Two balance configs (mismatch flagged):** civic wages live in the
  `Village/Economy/Currency/EconomyBalance` registry record
  (`Treasury(10,2,1,8,5,4,6)` — small bronze/day). The prompt directs the clergy
  line into the static `Village/Economy/EconomicBalance` class. Followed the
  prompt (clergy is a new line; `EconomicBalance` is the documented balance home)
  and flagged the split.
- **Offerings already routed:** `MakeOfferingVerb:82` —
  `vdata.getOrCreateBuildingEconomy(buildingId).depositRevenue(10L)`. Left as-is.
- **Tithe is NOT an economic flow today:** `handleTithe` only bumps piety (the
  debit "lands in the recurring auto-pay follow-up"). R4a implements the debit +
  routes it to the building economy.
- **No separate "donation" path:** religious giving today == offerings (routed);
  `DonateHerbsVerb` is apothecary, not religious. Flagged (offerings ARE the
  donation path).
- **Civic boons:** `village.depositToTreasury(...)` in `handleHarvestThanksgiving`
  / `handleSignatureRite` / `RiteScheduler` consecration — the civic flows that
  STAY in the village treasury. Untouched.

### Design / decisions

- **Reuse the one daily wage tick** (`TreasuryTickHandler`) — no parallel payroll.
  Added a PRIEST branch in the per-NPC loop that pays from the priest's assigned
  **building** economy via `NpcEconomy.businessPay` with a **pre-capped** amount
  (`min(wage, economy.getTreasury())`) so a poor temple underpays (businessPay's
  all-or-nothing `canAfford` is satisfied by the capped amount). Wage floored by
  the kingdom `MINIMUM_WAGE` law, then capped by the economy. A shrine/chapel
  priest draws from THAT building's economy (the per-building economy is keyed by
  the priest's `assignedBuildingId`, so R3e-2 minority clergy are automatically
  correct).
- **Upkeep** — debited once per religious building per day, deduped via a local
  `Set<UUID>` across however many clergy a building has.
- **Tithe** — `handleTithe` now debits the payer's wallet (`min(TITHE_AMOUNT,
  wallet)`) and deposits it into the rite venue's `BuildingEconomy`, resolved via
  the new `BuildingFaith.buildingIdAtLocation(rite.location())`. On top of the
  existing piety effect; skipped gracefully if the payer is broke / no building.
- **Rates** (`EconomicBalance`, daily, matching the wage cadence):
  `PRIEST_DAILY_WAGE = 10`, `TEMPLE_DAILY_UPKEEP = 4`, `TITHE_AMOUNT = 8`. Sized
  so a temple needs ~14 br/day income (≈1.5 offerings @10 or 2 tithes @8) to stay
  solvent; a neglected temple drains 14/day → deficit (no decay yet — R4c).

### What shipped

- `Village/Economy/EconomicBalance.java` — `PRIEST_DAILY_WAGE`,
  `TEMPLE_DAILY_UPKEEP`, `TITHE_AMOUNT`.
- `Village/Economy/Currency/TreasuryTickHandler.java` — `payClergyFromBuildingEconomy`
  (wage via `businessPay` + per-building upkeep), called for PRIEST in the daily
  loop; civic wage path unchanged.
- `Npc/Religion/RiteExecutor.java` — `handleTithe` routes the tithe payment into
  the venue's `BuildingEconomy`.
- `Npc/Religion/BuildingFaith.java` — `buildingIdAtLocation` helper.
- Offerings (`MakeOfferingVerb`) + civic boons (`depositToTreasury`): unchanged.

### Tie-In Audit

1. **Upstream feeders** — `MakeOfferingVerb` (offerings → economy, already),
   `handleTithe` (tithe → economy, new), `BuildingFaith`/rite venue (which
   building's economy). No separate donation path (offerings cover it).
2. **Downstream callers** — the daily wage tick (now also pays PRIEST from the
   building economy), `NpcEconomy.businessPay`/`getOrCreateBuildingEconomy`,
   `VillageSavedData` persistence (setDirty on each mutation), `HouseholdWealthManager`
   (clergy wage contributes to the household pool like other wages).
3. **Sibling systems** — village treasury: civic boons untouched, and PRIEST is
   excluded from the village-treasury `wageForProfession` (returns 0L), so clergy
   are NOT double-paid. R3e-2 shrine clergy draw from the shrine's own economy
   (per-building keying). Other business workers' wage path is unchanged (the
   PRIEST branch is additive + profession-gated).
4. **Exhaustive switches** — `wageForProfession(Profession)` has a `default`; the
   PRIEST handling is a separate profession-gated `if`, not a new switch arm. No
   enum added. Confirmed.

### Simplification Sweep

- Economy classes in scope: `TreasuryTickHandler` (the one daily wage tick — PRIEST
  added, not a religion payroll), `NpcEconomy.businessPay` (the one building-economy
  → wallet path, reused), `BuildingEconomy`/`getOrCreateBuildingEconomy` (the one
  income hub — offerings + tithe + upkeep all hit it), `EconomicBalance` (rates),
  `BuildingFaith` (+1 venue→building helper). No parallel wage/treasury/income
  path. No codec change (`BuildingEconomy` flows only).

### Deviations from prompt

- **Clergy rates live in the static `EconomicBalance`** (per the prompt), while
  civic wages live in the `EconomyBalance` registry record — a documented split,
  flagged. A later pass could migrate clergy rates into the registry config for
  data-pack tuning.
- **No `BuildingEconomy` wage mechanism existed to "reuse"** — the javadoc claim
  was aspirational. Implemented the wage draw via the canonical building-economy →
  wallet method (`businessPay`) inside the existing daily wage tick — i.e. reused
  the wage TICK + the building-economy payment primitive, rather than a
  pre-existing clergy-wage path (there was none).
- **Upkeep is per-building/day, deduped** — a temple with multiple clergy charges
  upkeep once (the norm is 1 priest/building per `BuildingInhabitantRegistry`).

### Out-of-scope but flagged

- Deficit→decay→abandonment → R4c (a deficit is now POSSIBLE but has no
  consequence). Production↔consumption → R4b. Alms / library books /
  recurring+player tithe → R4d. Ledger pruning → R4e.
- Migrating clergy rates into the `EconomyBalance` registry config for tuning.
- A distinct "donation" verb (today offerings are the donation path).

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done:
`businessPay`/`applyMinimumWage`/`contributeToPool`/`NpcWallet.spend`/`toBronze`
signatures cross-checked; PRIEST excluded from the village-treasury wage (no
double-pay); upkeep deduped per building; no codec change; no new enum/switch arm;
no new brain memory.

### Smoke test (user-runnable)

1. At a temple, make several offerings (`make_offering` verb) and run tithes;
   confirm the temple building's `BuildingEconomy` treasury grows (offerings +10
   each, tithes +8 each, debited from the payer's wallet).
2. Advance one in-game day; confirm the temple priest's wallet grows by the
   PRIEST wage (≤10, capped by the economy) and the temple economy drops by the
   wage + 4 upkeep.
3. Leave a temple un-attended (no offerings/tithes) for several days; confirm its
   economy trends toward 0 (wage+upkeep drain) and the priest is underpaid when it
   hits 0 — no decay yet, just a low/zero balance.
4. In a Sunstead village with a Tidecall SHRINE (R3e-2), confirm the shrine priest
   draws from the SHRINE's own economy (offerings at the shrine fund the shrine
   priest), independent of the temple.
5. Run a grand festival / consecration / harvest; confirm the civic boon still
   lands in the VILLAGE treasury (not the temple economy) — `/building`-style
   readouts show the village treasury rising, the temple economy unchanged by it.

---

## R4b — Production ↔ consumption: candles burned at ceremonies (2026-06-08)

Second phase of R4. Closes the temple GOODS loop (distinct from the R4a money
hub): the priest already PRODUCES candles into building storage but nothing
consumed them. Now village-wide ceremonies burn candles from the venue's stock,
and a candle-short temple holds a dimmer ceremony.

### Disposition (findings, verified on branch)

- **Production** — `PriestBehavior.tickProducing` produces
  `ProfessionSupplyChain.getOutputs(PRIEST)` = `WHITE_CANDLE, BOOK, GOLDEN_APPLE,
  EXPERIENCE_BOTTLE`, **rotated by day** (`outputs.get((gameTime/24000)%size)`),
  one item per `PRODUCE_TICKS` (200t) while inputs exist, deposited via
  `NpcBehaviorHelpers.depositToBuilding`. So `WHITE_CANDLE` is produced ~1 day in
  4. (Note: `WHITE_CANDLE` is BOTH a PRIEST input and output.) Left production
  unchanged per the prompt; the modest candle costs below are sustainable for a
  producing temple (see Sustainability).
- **Storage primitives** — `BuildingStorageAccess.countItem(level, building,
  item)` (int) + `takeItem(level, building, item, count)`. **`takeItem` is
  partial-consuming but reports all-or-nothing** (it shrinks stacks as it goes and
  returns `remaining==0`), so calling it when short would drain the partial stock
  AND return false. → Used **count-then-take**: `take = min(have, cost)` so the
  lit/dim decision is clean and partial stock isn't silently wasted on a query.
- **Venue resolution** — reused the R4a `BuildingFaith.buildingIdAtLocation(
  rite.location())` → the venue building (a shrine festival's `rite.location()` is
  the shrine origin → consumes the shrine's candles; consecration's location is
  the consecrated building's origin).
- **Effect coupling** — the communal handlers apply the village-wide effect via
  `FaithReconciliation.applyCommunalBenefit(npc, faith, trigger, scaledMood,
  scaledPiety, now)` where the magnitudes are `RiteProfile`-scaled. The candle
  multiplier composes by multiplying those already-scaled magnitudes BEFORE the
  cross-faith reconciliation: `RiteProfile × candleFactor × cross-faith`. No new
  scaler.
- **Money vs stock** — confirmed `BuildingEconomy` (R4a money) and
  `BuildingStorageAccess` (item stock) are separate subsystems; this phase touches
  only stock. No cross-wire.

### Design / decisions

- **One helper `CeremonyCandles`** (Npc.Religion), hit by all consuming
  ceremonies (no per-handler copy-paste):
  - `candleCost(rite)` (switch + default): `GRAND_FESTIVAL=4`,
    `HARVEST_THANKSGIVING/SIGNATURE_RITE/CONSECRATION=2`, `FEAST_DAY/VIGIL=1`,
    everything else (personal rites) `0`.
  - `light(level, village, venue, cost)` → `Lighting(effectMultiplier, lit)`:
    count-then-take up to `cost` `WHITE_CANDLE` from the venue building; `lit`
    when the full cost was in stock (×1.0), else dim (×`DIM_MULTIPLIER=0.6`).
    A `cost≤0` / unresolvable venue → lit (no penalty), so personal rites and
    venue-less rites are unaffected.
- **Hooked into the 5 village-wide communal handlers** (`handleHarvestThanksgiving`,
  `handleFeastDay`, `handleConsecration`, `handleVigil`, `handleSignatureRite`):
  one `light(...)` call per ceremony (single consume), then each attendee's
  mood/piety is `Math.round(scaled × effectMultiplier)`. Never a hard cancel — a
  dim ceremony still fires, just lesser.

### What shipped

- `Npc/Religion/CeremonyCandles.java` (new) — `candleCost` + `light` (the one
  consumption + lighting helper).
- `Npc/Religion/RiteExecutor.java` — the 5 communal handlers consume candles once
  and scale their effect by the lighting multiplier.

### Sustainability

A grand festival (annual per faith) burns 4; signature/harvest/consecration burn
2; feast/vigil burn 1. The priest produces ~1 candle per 4 days (rotation) — i.e.
~7/month — so a producing temple sustains routine holy days + the annual grand
festival; a neglected/under-producing temple runs dim. If the day-rotation makes
candles too sporadic in practice, bumping the candle slot in the production
rotation is a flagged follow-up (production left untouched here per the prompt).

### Tie-In Audit

1. **Upstream feeders** — `PriestBehavior` production (the candle supply, into
   building storage), `BuildingStorageAccess.countItem/takeItem`,
   `BuildingFaith.buildingIdAtLocation` (venue → building).
2. **Downstream callers** — the 5 `RiteExecutor` communal handlers (consume +
   couple), `RiteProfile`/`ReligionContent` (the candle factor multiplies the
   scaled magnitude), `FaithReconciliation` (cross-faith reconciliation still
   composes correctly — it receives the candle-reduced magnitudes and applies the
   per-attendee faith factor on top).
3. **Sibling systems** — R4a money: SEPARATE (`BuildingEconomy` vs item storage —
   no cross-wire). R2b/R3d attendance: unaffected (consumption is at rite-execute
   time, not attendance). R3d-1 crowd-bless: NOT candle-coupled (single consume in
   the one-shot handler avoids double-consume / carrying lit-state across the
   festival window) — flagged. Other behaviors' building-storage use: untouched
   (candles are a distinct item; `takeItem` is the shared primitive).
4. **Exhaustive switches** — `candleCost(Rite)` has a `default`; no `Rite`/enum
   added. Confirmed.

### Simplification Sweep

- Classes in scope: `CeremonyCandles` (new — 1 consumption + 1 cost method, 5
  inbound handler sites), `RiteExecutor` (the 5 handlers, edited). One helper hit
  by all consuming ceremonies; the reduction multiplier COMPOSES with the existing
  `RiteProfile`/`FaithReconciliation` scaling (multiplies the scaled magnitude),
  not a replacement. Reuses `BuildingStorageAccess` (no parallel stock) and the
  R4a `buildingIdAtLocation` venue resolver. No new enum/codec/brain memory.

### Deviations from prompt

- **Flavor is encoded in the EFFECT, not separate text.** A dim ceremony literally
  lands less mood/piety (×0.6) — that IS the "dimmer" outcome. A player-facing
  "candlelit vs dim" announcement/memory line is a light nice-to-have, flagged
  (the communal handlers write no per-NPC memory to hang it on; the festival
  announcement is in the scheduler, out of this phase's scope).
- **The R3d-1 crowd-bless is not candle-coupled** — consumption + coupling live in
  the one-shot communal handler (single consume). Coupling the sustained crowd
  pulses too would double-consume or require carrying the lit-state in the
  gathering's eventData across the window. Flagged for a later pass if the crowd
  pulses should also dim.
- **Personal rites (offering/confession) don't consume** — `candleCost` returns 0
  for them (kept light per the prompt's "your call"); a single-candle flavor for
  them is flagged.

### Out-of-scope but flagged

- Books → temple library (R4d, `preferredBookCategories`); blessed consumables
  (golden apple / exp bottle) handed to participants. Money flows (R4a). Decay
  (R4c). Candle-coupling the crowd-bless; player-facing lit/dim flavor text;
  a per-rite candle flavor for personal rites; production-rotation tuning if
  candles prove too sporadic.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done:
`countItem`/`takeItem`/`buildingIdAtLocation` signatures cross-checked; all 5
handlers route through the one `CeremonyCandles.light` (grep-verified); count-then-
take avoids partial-stock waste; the candle factor composes with
`RiteProfile`×`FaithReconciliation`; `candleCost` switch has a default; money
(R4a) untouched; no new enum/codec/brain memory.

### Smoke test (user-runnable)

1. Let a priest run for a few days so `WHITE_CANDLE` accumulates in the temple
   storage (check the temple's containers). Trigger a grand festival / holy day;
   confirm the candle count drops by the ceremony's cost (grand 4, signature/
   harvest/consecration 2, feast/vigil 1) and the ceremony lands at FULL effect
   (full mood/piety on attendees).
2. Empty the temple's candle stock (or withhold production); trigger another
   ceremony; confirm it STILL fires but at REDUCED effect (×0.6 mood/piety) — a
   dim ceremony, never cancelled.
3. Over repeated ceremonies, confirm a well-producing temple stays lit
   (production keeps candles in stock); a neglected one runs dim.
4. In a village with a Tidecall SHRINE (R3e-2), stock candles at the shrine and
   trigger the shrine's grand festival; confirm it consumes the SHRINE's candles,
   not the temple's.
5. Confirm R4a money is unaffected by candle burning (the temple's `BuildingEconomy`
   treasury doesn't change when candles are consumed — stock and money are
   separate).

---

## R4c — Temple prosperity ↔ decay ↔ abandonment (2026-06-08)

The R4 payoff: temple financial health (R4a) + village piety now drive the
EXISTING `BuildingCondition` decay, plus a priest-vacate trigger. A solvent,
devout temple flourishes (holds MAINTAINED); a sustainedly insolvent, low-piety
one decays through the ladder and — after a long deficit window — its priest
leaves, after which the existing resident-less path takes it to RUINED.
Recoverable if income/piety return before abandonment.

### Disposition (findings, verified on branch)

- **Decay machinery** — `VillageAgingManager.tickBuildingDecay` degrades EVERY
  non-NEW building by `BuildingCondition.degrade()` once per `DEGRADE_INTERVAL`
  (7d), but it's gated inside `evaluateAging` which only runs every `CHECK_INTERVAL`
  (3d), so an actual degrade fires when `currentTick` is divisible by BOTH → ~every
  21d (slow). Builders (`BuilderMaintenanceGoal`) repair occupied buildings back
  up, so in practice only un-repaired/abandoned buildings reach DILAPIDATED→RUINED
  ("abandoned only"). `BuildingCondition` has `degrade()`/`repair()` + the
  DILAPIDATED output penalty. This is the path to inject into.
- **Insolvency signal (R4a)** — `BuildingEconomy.getTreasury()`; a temple is
  solvent for a day when it can cover `PRIEST_DAILY_WAGE + TEMPLE_DAILY_UPKEEP`
  (= 14). No persisted insolvency counter existed → added `daysInsolvent`.
- **Village piety aggregate — MISMATCH: none exists.** Computed one
  (`villagePiety` = average `primaryStrength` of loaded village residents;
  neutral 0.4 when none loaded).
- **Vacate mechanism** — `TownspersonMob.clearAssignedBuilding()` (sets
  `assignedBuildingId = null`). No cleaner profession-change/migration vacate that
  fits; used the prompt's minimal: unassign → the building is resident-less and
  `PriestBehavior` self-disables (it gates on a non-null building).
- **Re-staffing — MISMATCH: not automatic.** `VillageInhabitantPopulator` runs
  ONCE at village spawn and "never repopulates" (VillageSavedData comment). So
  recovery-BEFORE-abandonment is automatic, but re-staffing a repaired vacant
  temple relies on the existing manual/populator path, not an auto-rehire.
  Flagged.
- **Cadence** — `RiteScheduler.dailyTick` runs once/day (`ReligionRiteTickSystem`
  interval 24000) — the natural hook for a daily per-village prosperity pass.

### Design / decisions

- **`TempleProsperity` (new, Npc.Religion)**, called per village from
  `RiteScheduler.dailyTick` — a religion-specific nudge that calls the SAME
  `setCondition`/`degrade`/`repair` as `VillageAgingManager` (the prompt's allowed
  alternative). VillageAgingManager is UNTOUCHED — its slow base decay keeps
  running for all buildings; only religious buildings get the financial coupling,
  so other buildings are unaffected. Avoids a Decoration→Religion dependency.
- Per religious building, daily:
  - **Solvency** — `treasury ≥ 14` → `resetDaysInsolvent`, else
    `incrementDaysInsolvent` (the persisted counter).
  - **Abandonment** — `daysInsolvent ≥ ABANDON_DAYS (21)` → `vacatePriest`
    (`clearAssignedBuilding` on the loaded PRIEST staffing it); the now-vacant
    temple decays to RUINED via the base path; counter reset. Last resort.
  - **Decay** (nudge every `NUDGE_DAYS=3`) — `daysInsolvent ≥ DECAY_DAYS (7)`,
    lowered to 4 when village piety `< LOW_PIETY (0.25)` (low piety amplifies):
    degrade one ladder step, but a STAFFED temple bottoms at DILAPIDATED (RUINED
    only once vacant); the recurring nudge re-degrades builder repairs so it stays
    visibly run-down while insolvent.
  - **Flourish** — solvent + piety `≥ HIGH_PIETY (0.5)` and not RUINED → `repair()`
    one step toward MAINTAINED (counters base decay; the visible reward). A RUINED
    temple is NOT auto-resurrected by piety — it recovers via builder/player repair
    + re-staff.
- Thresholds are bounded constants, clearly tunable. Insolvency uses a
  forward-looking "can't cover a day's cost" check (order-independent, no
  payment-log needed).

### What shipped

- `Village/Economy/BuildingEconomy.java` — `daysInsolvent` field (5th codec field,
  `optionalFieldOf` → pre-R4c saves load at 0) + get/increment/reset.
- `Npc/Religion/TempleProsperity.java` (new) — the financial+piety → decay/vacate
  coupling.
- `Npc/Religion/RiteScheduler.java` — daily per-village `TempleProsperity.tickVillage`
  pass (pass #4 in `dailyTick`).

### Tie-In Audit

1. **Upstream feeders** — R4a `BuildingEconomy` treasury (solvency); the daily
   wage/upkeep tick (drains it); `villagePiety` (the modifier).
2. **Downstream callers** — `BuildingCondition.degrade()/repair()` + `setCondition`
   (the same ones VillageAgingManager uses); the DILAPIDATED output penalty
   (layered on, NOT re-implemented — the financial driver just moves the condition,
   the penalty is the existing consequence); `clearAssignedBuilding` (vacate) →
   resident-less → existing RUINED path; R4b candle production/ceremonies stop
   naturally with no priest; R2b/R3d hold no ceremonies at a vacant/RUINED temple.
3. **Sibling systems** — other buildings' decay: UNCHANGED (the nudge only
   processes religious buildings; VillageAgingManager untouched). R3e-2 shrines:
   decay/abandon the SAME way (per-building economy + `isReligiousBuilding`
   includes SHRINE). Village treasury: unaffected (this reads the building
   economy). R4a/R4b: the money + candle deficits this consumes are unchanged.
4. **Exhaustive switches** — no `BuildingCondition`/enum added; read/set via the
   existing `degrade`/`repair` switches. Confirmed.

### Simplification Sweep

- Classes in scope: `TempleProsperity` (new — 1 inbound from RiteScheduler),
  `BuildingEconomy` (+1 counter), `RiteScheduler` (1 pass). ONE hook into the
  existing decay primitives — no parallel decay loop; abandonment reuses
  `clearAssignedBuilding`; recovery reuses the existing builder/player repair.
  Codec 5/16. No new enum/brain memory.

### Deviations from prompt

- **Coupling lives in a religion daily pass, not inside `VillageAgingManager`** —
  the prompt's allowed alternative ("a religion-specific nudge that calls the same
  setCondition/degrade"). Keeps other buildings' decay untouched and avoids a
  Decoration→Religion dependency; the base decay still runs underneath.
- **Re-staffing is NOT automatic** (the populator never repopulates). Recovery
  BEFORE abandonment is automatic (solvency resets the counter + flourish-repair);
  post-abandonment recovery needs the existing builder/player repair + a manual
  re-staff. Flagged — an auto-rehire of vacant religious buildings is a clean
  follow-up.
- **Vacate is the minimal `clearAssignedBuilding`** — the ex-priest remains a
  building-less PRIEST (PriestBehavior dormant) rather than being migrated/made
  unemployed. Flagged.
- **No village-piety aggregate existed** — added a simple loaded-resident average.

### Out-of-scope but flagged

- Alms / library books / recurring+player tithe → R4d. Ledger pruning → R4e.
- Automatic re-staffing of a repaired vacant religious building (auto-rehire).
- Fuller priest off-boarding (unemployment/migration) instead of a building-less
  lingering PRIEST.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done:
`BuildingEconomy` 5-arg ctor (only `create` calls it) + 5/16 codec; `repair()`
climbs WEATHERED→MAINTAINED (flourish holds); RUINED guarded from piety
auto-repair; `clearAssignedBuilding`/`getBounds`/`primaryStrength`/`isReligiousBuilding`
signatures confirmed; nudge only touches religious buildings (no decay regression);
no new enum/switch arm/brain memory.

### Smoke test (user-runnable)

1. Starve a temple: no offerings/tithes (economy < 14 br) and low village piety.
   Over days, confirm `daysInsolvent` climbs; after ~7 days it degrades through the
   ladder (every 3 days) to DILAPIDATED — faster than a normal building and even
   with a builder around (the nudge re-degrades repairs). After ~21 insolvent days
   the priest is unassigned (leaves the temple); the now-vacant temple goes RUINED
   and holds no ceremonies/production.
2. On a second temple, restore income (offerings/tithes) BEFORE day 21; confirm
   `daysInsolvent` resets and decay stops; with high village piety + solvency,
   confirm it climbs back to MAINTAINED (flourishing).
3. Repair a RUINED/vacant temple via a builder/player and re-staff it (existing
   populate path — NOT automatic); confirm it resumes production + ceremonies.
4. Keep a temple solvent (steady offerings) in a high-piety village; confirm it
   stays MAINTAINED (the visible reward) and never decays.
5. Confirm NON-religious buildings' decay is unchanged (the base ~21-day cadence;
   no financial coupling).

---

## R4c-2 — Temple re-staffing (auto-rehire) (2026-06-08)

A focused fix-up completing R4c's recoverable loop. R4c made an insolvent temple
decay and (after 21 insolvent days) the priest abandon it (`clearAssignedBuilding`),
but flagged two gaps: the populator "runs once / never repopulates" so a
repaired vacant temple was never re-staffed, and the vacated priest lingered as a
dormant building-less PRIEST. This closes it: a functional, vacant, solvent
religious building gets a priest again — preferring to rehire the dormant
ex-priest — with the correct building faith.

### Disposition (findings, verified on branch)

- **`TempleProsperity` (R4c)** — the daily per-village pass over religious
  buildings; the natural hook for the re-staff check (no new tick). `vacatePriest`
  already scans the building's assigned priest — refactored to share that lookup.
- **Populator spawn path** — `VillageInhabitantPopulator.spawnNpcInBuilding(level,
  building, village, profession, familyRole, rng)` is **private** and runs once at
  spawn; for a PRIEST it already applies the building faith + clergy order (R3e-2,
  lines added there). Exposed a thin public wrapper `spawnWorkerInBuilding(...,
  PRIEST)` (FamilyRole.HEAD) to REUSE that exact path for the fresh-spawn fallback
  — no duplication of the spawn logic.
- **Assignment API** — `assignToBuilding(buildingId, villageName)` (re-assign) +
  `clearAssignedBuilding()` (vacate). A dormant ex-priest after R4c is a
  `Profession.PRIEST` with `assignedBuildingId` empty but `assignedVillageName`
  intact (clearAssignedBuilding only nulls the building) → findable in the village.
- **Faith re-apply** — `BuildingFaith.applyClergyFaith(level, village, npc,
  building)` + `ClergyOrders.assignClergyOrder(level, npc)` (R3e-2) re-apply the
  building's faith + order to a rehired priest (a recovered shrine → a same-faith
  Tidewarden).
- **Functional predicate** — `BuildingCondition.isFunctional()` (`!= RUINED`). A
  RUINED temple must be repaired (builder/player) before it can be staffed —
  matches the existing model.

### Design / decisions

- **One check in the existing `TempleProsperity` per-building loop** (no new tick,
  no parallel staffing): a building that `isFunctional()`, is **vacant**
  (`findAssignedPriest` empty), and is **solvent** (`treasury ≥ DAILY_COST`) is
  re-staffed via `restaff`.
- **`restaff`** — prefer `findDormantPriest` (a building-less village PRIEST,
  excluding visitors + away pilgrims) → `assignToBuilding` + re-apply faith/order;
  else `VillageInhabitantPopulator.spawnWorkerInBuilding(..., PRIEST)` (the
  populator path applies faith/order itself).
- **Solvency gate** — re-staff only when the building can currently afford a
  priest. This satisfies the prompt's "don't churn" guard (a broke vacant temple
  isn't repeatedly staffed→re-abandoned on 21-day cycles) and ties recovery to
  renewed giving (offerings accumulate → solvent → re-staffed), which is
  thematically right. Flagged as a deviation from the literal "functional vacant".
- **Guards** — one priest per building (`findAssignedPriest` skip when staffed);
  never staff RUINED (`isFunctional`); dormant-priest reuse can't duplicate (once
  assigned, the priest is no longer "dormant" for the next building).

### What shipped

- `Village/Buildings/Inhabitants/VillageInhabitantPopulator.java` — public
  `spawnWorkerInBuilding` wrapper over the existing private spawn path.
- `Npc/Religion/TempleProsperity.java` — the re-staff check + `restaff`,
  `findDormantPriest`, `findAssignedPriest` (vacatePriest refactored to share it).

### Tie-In Audit

1. **Upstream feeders** — R4c state (vacant building + dormant ex-priest);
   `BuildingCondition.isFunctional` (the repaired gate); building economy solvency.
2. **Downstream callers** — the populator spawn path (fresh fallback) +
   `assignToBuilding` (rehire); `applyClergyFaith`/`assignClergyOrder` (faith +
   order); R4a wages / R4b candle production / R2b-R3d ceremonies all RESUME once a
   priest is assigned (they self-gate on a staffed priest). R4c decay/abandon: a
   re-staffed solvent building resets `daysInsolvent` (the existing solvency
   update), so it won't immediately re-abandon.
3. **Sibling systems** — R4c abandonment (the source of the dormant priest +
   vacant building). R3e-2 shrine clergy: re-staff respects the building faith
   (`applyClergyFaith`/`assignClergyOrder` resolve from the building). The initial
   populator: a freshly-placed temple still staffs there at spawn (this pass only
   fills a still-vacant one, and only when solvent). The pilgrimage system: away
   pilgrims are excluded from the dormant pool (PILGRIM role check), so a traveling
   priest isn't yanked into a re-staff.
4. **Exhaustive switches** — none touched; no enum added. Confirmed.

### Simplification Sweep

- Classes in scope: `TempleProsperity` (the re-staff check + 3 helpers — one shared
  with vacate), `VillageInhabitantPopulator` (+1 public wrapper reusing the private
  spawn). No new tick/system; rehire reuses `assignToBuilding`; fresh spawn reuses
  the populator path. Dormant-priest reuse can't duplicate. No new enum/codec/brain
  memory.

### Deviations from prompt

- **Added a solvency gate to re-staffing** (the prompt said "functional vacant").
  It is the prompt's "don't churn" guard made concrete — a broke vacant temple
  isn't staffed→re-abandoned repeatedly — and ties recovery to renewed giving. If
  unconditional re-staff is preferred, drop the `treasury ≥ DAILY_COST` clause.
- **Fresh-spawn fallback exposes a public populator wrapper** (`spawnWorkerInBuilding`)
  rather than calling the private method — reuse of the exact existing path.

### Out-of-scope but flagged

- R4d (alms / library books / recurring + player tithe), R4e (ledger pruning).
- Fuller priest off-boarding on abandonment (R4c still leaves the ex-priest a
  building-less PRIEST until rehired — which R4c-2 now consumes as the rehire
  source, so the lingering is now bounded/useful).

**This completes the R4c recoverable loop** (decay → abandonment → repair →
re-staff → resume).

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done:
`spawnWorkerInBuilding`/`assignToBuilding`/`applyClergyFaith`/`assignClergyOrder`/
`isFunctional`/`isVisitor`/`hasRole(PILGRIM)` signatures confirmed; one priest per
building (assigned-priest guard); dormant pool excludes visitors + pilgrims +
already-assigned; RUINED never staffed; no new enum/codec/brain memory.

### Smoke test (user-runnable)

1. Drive a temple to abandonment (R4c: starve income for 21+ days) and confirm a
   dormant building-less ex-priest remains in the village and the temple is vacant.
2. Repair the temple (builder/player so it's no longer RUINED) and restore solvency
   (offerings/tithes so its economy ≥ 14 br). On the next daily pass, confirm it is
   re-staffed — reusing the DORMANT ex-priest (no new villager spawned) — with the
   building's faith + order; then wages (R4a), candle production (R4b), and
   ceremonies (R2b/R3d) resume.
3. Confirm a still-RUINED (unrepaired) temple is NOT staffed (repair first).
4. Confirm a recovered Tidecall SHRINE gets a same-faith (Tidewarden) priest.
5. Confirm a freshly-placed temple still staffs via the normal populator at spawn
   (this pass doesn't interfere).
6. Confirm one priest per building (a staffed temple is never given a second
   priest), and that a broke (insolvent) vacant temple is NOT re-staffed until
   giving makes it solvent (no churn).
