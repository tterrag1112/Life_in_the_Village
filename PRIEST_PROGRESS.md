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
