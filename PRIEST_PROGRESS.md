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

---

## R4d-1 — Recurring tithe (NPC + player) (2026-06-08)

R4 income depth: the STEADY religious income that keeps a devout village's temple
solvent (feeding R4a `BuildingEconomy` → R4c/R4c-2 solvency). Devout adherents
weekly auto-tithe to their same-faith temple, and the player can opt into a weekly
auto-tithe — both reusing the one R4a tithe primitive.

### Disposition (findings, verified on branch)

- **R4a tithe primitive** — `handleTithe` inlined: debit payer wallet
  (`min(TITHE_AMOUNT, wallet)` → `wallet.spend`) → `getOrCreateBuildingEconomy
  (buildingId).depositRevenue`. Extracted to `Tithing.contribute(data, npc,
  buildingId, amount)` and refactored `handleTithe` to call it (one path).
- **`MakeOfferingVerb`** (the player-verb model) — a `PlayerVerb` shown at a PRIEST;
  it deposits 10 br to the temple economy "from thin air" (NO player-coin debit) +
  bumps player piety in `RiteSavedData`. MISMATCH flagged: a tithe must actually
  COST the player (a recurring commitment), so the player tithe debits coins.
- **Player coins** — `CoinHelper.countCoins(player)` / `removeCoins(player,
  amount)` (affordability-gated, returns false if short) is the player debit.
- **Player piety + opt-in** — `RiteSavedData.getOrCreatePlayerPiety`. Added a
  persisted `autoTitheTemple` map (playerId → temple building id) to RiteSavedData
  (3rd codec field, `optionalFieldOf` → pre-R4d saves load empty).
- **Cadence** — `RiteScheduler.dailyTick` runs daily (`ReligionRiteTickSystem`
  interval 24000); weekly + per-payer-staggered (by UUID `Math.floorMod(hash,7)`)
  so payments don't spike. `EconomicBalance.TITHE_AMOUNT` = 8.
- **Recipient building** — NPC: `BuildingFaith.religiousBuildingsByFaith(village)
  .get(faith)` (their same-faith venue; a minority → their shrine; unserved → no
  local tithe). Player: the stored opted-in temple.

### Design / decisions

- **One primitive, two tithers** — `Tithing.contribute` (NPC wallet → economy) is
  the shared R4a primitive (also used by `handleTithe`); `contributePlayer`
  (player coins → economy) is its player counterpart. Both deposit to the same
  `BuildingEconomy`; no parallel payment path.
- **NPC recurring** (`tickNpcTithes`) — weekly staggered; each loaded, devout
  (`primaryStrength ≥ 0.2`, i.e. FAITHFUL+), served (same-faith building exists),
  affordable adherent tithes `TITHE_AMOUNT` to their faith's building + a small
  piety/attendance. Visitors + away pilgrims excluded; lapsed/unserved skip.
- **Player auto-tithe** (`tickPlayerTithes` + `PayTitheVerb`) — the `pay_tithe`
  verb at a priest TOGGLES the opt-in (records playerId → that temple, setting the
  player's faith to the temple's if they have none; toggling again opts out).
  Weekly staggered, the recurring pass debits the online player's coins
  (affordability-gated) → the temple economy + player piety; opts out
  automatically if the temple is gone/RUINED.
- Bounded + anti-runaway: weekly cadence, fixed `TITHE_AMOUNT`, affordability-
  gated (poor payers skip, never negative).

### What shipped

- `Npc/Religion/Tithing.java` (new) — the recurring engine + the shared
  `contribute` primitive.
- `Npc/Religion/RiteExecutor.java` — `handleTithe` routes through
  `Tithing.contribute` (the same primitive).
- `Npc/Religion/RiteSavedData.java` — `autoTitheTemple` opt-in map (codec field +
  `isAutoTithe`/`setAutoTithe`/`clearAutoTithe`/`autoTitheTemples`).
- `Npc/Verbs/Impl/PayTitheVerb.java` (new) + `PlayerVerbRegistry` registration —
  the player opt-in toggle.
- `Npc/Religion/RiteScheduler.java` — daily `Tithing.tick` pass (#5 in dailyTick).

### Tie-In Audit

1. **Upstream feeders** — `PietyComponent` (devout eligibility / player piety),
   wallet + `CoinHelper` (affordability), `BuildingFaith` (recipient building),
   the daily religion tick (cadence).
2. **Downstream callers** — the tithe primitive (`contribute`) → temple
   `BuildingEconomy` (income) → R4c `TempleProsperity` solvency (steady income
   reduces abandonment) + R4c-2 re-staff (solvency gate). The R4a one-shot tithe
   (`handleTithe`) still works (now via the same primitive). The player verb/flag
   system (`RiteSavedData`).
3. **Sibling systems** — R3e served/unserved: an unserved adherent (no same-faith
   building) doesn't tithe locally — consistent. R4a/R4c: this is the income that
   makes "stay solvent" achievable. Pilgrimage: away pilgrims excluded from the
   NPC tithe scan.
4. **Exhaustive switches** — none added; no new enum. Profession checks are `==`.
   Confirmed.

### Simplification Sweep

- Classes in scope: `Tithing` (new — the engine + 1 shared primitive, 2 inbound:
  RiteScheduler tick + handleTithe), `RiteSavedData` (+1 opt-in map),
  `PayTitheVerb` (new, 1 registration), `RiteExecutor`/`RiteScheduler` (wiring).
  NPC + player tithe both reuse `contribute`/`contributePlayer` (one deposit path)
  on the one daily tick — not two payment systems. No new tick. Codec: RiteSavedData
  gains 1 field (3 total). No new enum/brain memory.

### Deviations from prompt

- **The player tithe DEBITS the player's coins** (`CoinHelper.removeCoins`), unlike
  `MakeOfferingVerb`'s free offering — a recurring tithe is a real cost. Flagged
  as an intentional difference from the offering model.
- **`PayTitheVerb` uses the generic priest dialogue tree** (as MakeOfferingVerb
  does) rather than a dedicated set/cancel message — the toggle works; a bespoke
  tithe dialogue (distinct pledge/cancel text) is polish, flagged.
- **The player tithes to the temple they opted in at** (stored building id),
  regardless of where they roam; auto-opts-out if it's gone/RUINED.

### Out-of-scope but flagged

- Alms + library books → R4d-2; ledger pruning → R4e.
- Wealth-scaled tithe amount (kept a flat `TITHE_AMOUNT`); a dedicated
  pledge/cancel dialogue tree; a UI indicator of the player's pledge status.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done:
`CoinHelper.countCoins/removeCoins`, `NpcWallet.toBronze/spend`,
`RiteSavedData` codec (3 fields, `apply` arity), `VerbResult.success(treeId)` (a
dialogue-tree id, not plain text — used the generic priest tree), `VerbContext`
record accessors, `BuildingFaith.religiousBuildingsByFaith/resolveFaith` confirmed;
weekly stagger by UUID; affordability-gated; no new enum/brain memory.

### Smoke test (user-runnable)

1. Populate a village with several devout adherents (piety ≥ 0.2) of the local
   faith. Over a week, confirm their wallets are debited ~8 br (staggered across
   days) and the temple's `BuildingEconomy` treasury rises — keeping it solvent
   (watch R4c: `daysInsolvent` stays 0, no decay/abandonment).
2. Confirm a poor adherent (no coins) and a lapsed one (piety < 0.2) do NOT tithe;
   confirm a minority adherent tithes to their SHRINE's economy, not the temple.
3. At a priest, use "Pledge tithe": confirm it opts you in. Over a week, confirm a
   weekly coin deduction + your player piety rising; use it again to cancel and
   confirm deductions stop. Confirm it's skipped when you have no coins (no
   negative balance).
4. Confirm a well-attended (devout) temple measurably trends solvent / avoids the
   R4c abandonment that an un-tithed temple falls into.
5. Confirm the R4a one-shot tithe rite still deposits to the temple economy (the
   shared primitive is unchanged behaviourally).

---

## R4d-2 — Temple surplus outflows: alms + library books (2026-06-08)

R4 outflow depth. A flourishing temple now spends genuine SURPLUS on good works:
**alms** for the village's needy and **religious books** for the library —
reviving the dead `Religion.preferredBookCategories`. One spend hook in the R4c
`TempleProsperity` daily pass; the solvency buffer protects R4c.

### Disposition (findings, verified on branch)

- **`TempleProsperity` (R4c)** — the daily per-village pass + the solvency model
  (`DAILY_COST = wage+upkeep = 14`). The spend hook lives here (no new tick).
  Surplus = `treasury − SOLVENCY_BUFFER` (a week's runway = 98); spend only above
  it so R4c is never undermined.
- **`BuildingEconomy.withdraw`** funds the spend; each alm/book re-checks
  `treasury − buffer` so spending never crosses the buffer.
- **Recipients / wealth signal** — no poverty stat exists; wallet balance is the
  proxy. Neediest = loaded village residents with `getWallet().toBronze() <
  NEEDY_THRESHOLD`, lowest first. NPC alms go via `npc.getWallet().receive`
  (`CoinHelper` is PLAYER-inventory-side — MISMATCH flagged; the recipients are
  NPCs).
- **Compassion** — `npc.getTraitVector().get(TraitAxis.COMPASSION)` ∈ [-1,1]
  (Callous −1 … Compassionate +1). Scales the alms share.
- **Library** — `VillageSavedData.getOrCreateLibraryCatalogue(libraryBuildingId)`
  (keyed by the LIBRARY building); a book is `new BookRecord(...)` + `cat.acquire`
  (the `ScholarBehavior` path). **MISMATCH: `BookRecord` has NO category field**
  (category drives the subject, not stored) and **`ProceduralBookFactory` only
  authors ledgers** (`generateVillageLedger`), not categorized books — so I author
  the `BookRecord` directly (the catalogue's native unit) and encode the category
  in the title/topics. `Religion.preferredBookCategories()` (the dead field) drives
  WHICH category to author — its first consumer.

### Design / decisions

- **One `spendSurplus` hook** per religious building (end of the per-building loop),
  weekly + staggered by building UUID; needs a seated priest (good works are
  clergy-led — a vacant building doesn't spend). `budget = min(surplus,
  SPEND_PER_PASS=40)`.
- **Alms / book split by compassion** — `almsShare = clamp(0.5 + compassion·0.4,
  0.1, 0.9)`: a Compassionate priest gives ~0.9 to alms, a Callous one ~0.1 (more
  to books). Alms first, then books from the remainder.
- **Alms** (`distributeAlms`) — the ≤3 neediest (`< NEEDY_THRESHOLD=50`) loaded
  villagers each get up to `ALMS_PER_NPC=12` from the economy + a small mood lift
  (`GIFT_RECEIVED`), never dipping below the buffer.
- **Books** (`stockLibraryBook`) — if a village LIBRARY exists and it holds fewer
  than `RELIGIOUS_BOOK_CAP=6` faith books (counted by a `religion.<faith>` topic
  tag), author one `BookRecord` of a rotated preferred category (title = faith +
  category; author = the priest; topics tag the faith + category), `acquire` it,
  and withdraw `BOOK_COST=20`. A Sunstead temple stocks its categories
  (RELIGIOUS/HISTORY/GUIDE), a Tidecall shrine its own (RELIGIOUS/TRAVELOGUE).
- **Per-building** — `resolveFaith`/`getOrCreateBuildingEconomy` are per-building,
  so a shrine's surplus funds its own faith's works (R3e-2).
- Bounded + tunable: weekly cadence, per-pass cap, buffer floor, recipient/book
  caps.

### What shipped

- `Npc/Religion/TempleProsperity.java` — `spendSurplus` + `distributeAlms` +
  `stockLibraryBook` + `firstLibrary`/`readable` helpers + the surplus constants;
  one call added at the end of the per-building loop.

### Tie-In Audit

1. **Upstream feeders** — `TempleProsperity` surplus signal, `BuildingEconomy`
   balance, the wallet wealth proxy (recipients), `TraitVector` COMPASSION,
   `Religion.preferredBookCategories` (now consumed).
2. **Downstream callers** — `BuildingEconomy.withdraw` (each spend), villager
   `getWallet().receive` + mood (alms), `LibraryCatalogue.acquire` (books). The
   solvency buffer is re-checked per withdraw so R4c's `daysInsolvent`/decay is
   never triggered by spending.
3. **Sibling systems** — R4c (the buffer keeps spending from causing abandonment),
   R4d-1 tithe income (funds the surplus), `LibrarianBehavior` (curates the
   now-stocked faith books — they're normal `BookRecord`s), R3e-2 shrines (own
   surplus → own faith's works via per-building keying).
4. **Exhaustive switches** — none added; no new enum. The `readable(BookCategory)`
   helper is string formatting, not a switch. Confirmed.

### Simplification Sweep

- Classes in scope: `TempleProsperity` (the one surplus hook + helpers — alms +
  books in ONE pass, not two ticks). Books reuse `BookRecord` + `LibraryCatalogue.
  acquire` (the existing authoring/stock path, as `ScholarBehavior` uses), not a
  new book pipeline. Alms reuse the wallet + mood. The buffer (`treasury − 98`)
  re-checked per spend protects R4c. No new tick/enum/codec/brain memory.

### Deviations from prompt

- **NPC alms use `getWallet().receive`, not `CoinHelper`** — `CoinHelper` debits/
  credits a player's inventory; the alms recipients are NPC villagers, whose money
  is their `NpcWallet`. (Player-side alms would use CoinHelper, but recipients here
  are villagers.)
- **Book authoring is `new BookRecord(...)` + `LibraryCatalogue.acquire`, not
  `ProceduralBookFactory`** — `ProceduralBookFactory` only generates village
  ledgers, and `BookRecord` carries no `BookCategory`. So `preferredBookCategories`
  is revived as the SUBJECT driver (it picks the category, encoded in the book's
  title/topics), and the book is stocked as a normal catalogue record (the same
  path `ScholarBehavior` uses).
- **Books require a village LIBRARY** — if none exists, books aren't stocked (alms
  still happen). Flagged.
- The stocked faith book grants **no skill buff** (a devotional/lore book) — a
  faith-skill buff is a possible enhancement, flagged.

### Out-of-scope but flagged

- Ledger pruning → R4e. A faith-skill buff on stocked books; a richer procedural
  body for the religious book (currently a catalogue record only); player-side
  alms; wealth-scaled alms beyond the flat per-NPC amount.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done:
`getTraitVector().get(COMPASSION)`, `NpcWallet.receive`/`toBronze`,
`BuildingEconomy.withdraw`, `BookRecord` 9-arg ctor, `LibraryCatalogue.acquire`/
`all`, `getOrCreateLibraryCatalogue(UUID)`, `Religion.preferredBookCategories`/
`displayName`, `BuildingFaith.resolveFaith`, `firstLibrary` (BuildingType.LIBRARY)
confirmed; surplus only spent above the buffer (re-checked per withdraw → R4c
intact); per-pass + recipient + book caps; no new enum/codec/brain memory.

### Smoke test (user-runnable)

1. Give a temple a large surplus (many offerings/tithes so its `BuildingEconomy`
   ≫ 98 br). On its weekly spend day, confirm the poorest ≤3 villagers (wallet
   < 50) gain coins + a mood lift and the temple surplus drops — but never below
   ~98 (the solvency buffer; R4c `daysInsolvent` stays 0).
2. Compare a Compassionate priest's temple (more to alms) vs a Callous one's
   (less alms, more books).
3. Confirm a religious book of the religion's `preferredBookCategories` is added to
   the village LIBRARY catalogue (up to 6), and a Sunstead temple stocks different
   categories than a Tidecall one. With no library present, confirm no book is
   stocked (alms still occur).
4. Confirm a temple AT/BELOW the buffer (treasury ≤ 98) spends NOTHING (R4c
   protected).
5. Confirm a shrine with surplus funds its OWN faith's alms/books (its economy,
   its faith's book categories).

---

## R4e — Rite-ledger pruning (2026-06-08)

The R4 housekeeping finale. `RiteSavedData.rites` has grown unbounded since R1c
(every completed rite stays forever). This prunes stale TRANSIENT completed rites
past a retention window while RETAINING load-bearing markers, PENDING rites, and
the player-piety map. **Completes R4 (the religious economy).**

### Disposition — the marker audit (the central task)

Grepped every reader of the rite ledger and classified each:

- **`collectConsecrationMarkers`** (`RiteScheduler`) — reads CONSECRATION rites:
  `SUCCESSFUL → consecrated` (THE marker), `PENDING → pending`. Consumed by
  `applyConsecrationBlessings` (the ongoing village blessing) + `scheduleConsecrations`
  (skip already-consecrated). → **`(CONSECRATION, SUCCESSFUL)` is a load-bearing
  marker — RETAIN forever.** Pruning it would silently un-consecrate the building
  (it re-schedules + loses its blessing). This is the #1 risk the phase guards.
- **`hasPendingOrdination`** — reads PENDING ordination only (kept by the PENDING
  rule). **`isOrdained` reads the clergy SPEC, not the ledger** — so a SUCCESSFUL
  ORDINATION is NOT a marker → prunable. Confirmed.
- **`dueRites`** (`RiteExecutor.runDue`, `PriestBehavior.findClaimableRite`) — PENDING/due
  only. Kept.
- **`getRite(id)`** — `PriestBehavior` re-fetch while officiating (the PENDING claim);
  `AttendGatheringBehavior`/`PriestBehavior` `linkedRite` (a festival's blessing rite,
  read only during its short active window). In-flight; safe to prune long after.
- **`CommunityGatherings.all()`** (`inVillage` → `activeInVillage`/`activeNear`) — adds
  all rites then **filters `isActiveAt`**; a completed rite's `gatheringStatus()` is
  COMPLETED/CANCELLED (never active), so completed rites are already excluded.
  `inVillage` has no direct non-active callers. Pruning completed rites is a no-op
  for gathering queries. Confirmed.
- **player-piety map** — a SEPARATE structure (`playerPiety`); NOT touched.

**Classification:**
- MARKER (retain forever): `(CONSECRATION, SUCCESSFUL)` — the ONLY one.
- TRANSIENT (prunable after the window): every other completed rite
  (SUCCESSFUL/SKIPPED/DISRUPTED of marriage, funeral, naming, coming-of-age,
  blessing, confession, offering, tithe, harvest, feast, ordination, vigil,
  purification, signature, grand).
- PENDING: always kept (in-flight). player-piety: never touched.

### Design / decisions

- **One classifier in `RiteSavedData`** — `isMarker(r)` (the named, documented
  marker set) + `isPrunable(r, now)` (PENDING → keep; marker → keep; else
  `now − completedTick > RETENTION_TICKS`). The rule is in one place, the marker
  set explicit + auditable.
- **`pruneStaleRites(now)`** — collects prunable ids (capped at
  `MAX_PRUNE_PER_PASS=500` so a huge ledger bounds gradually), removes them,
  `setDirty`. Called from `RiteScheduler.dailyTick` (#6) — the existing daily
  pass, no new tick/store.
- **Retention window** — `RETENTION_TICKS = 30 in-game days`. Conservative: far
  longer than any in-flight read of a completed rite (a festival's linked rite is
  read only during its ≤½-day window) yet bounds the (high-frequency) rite ledger
  to ~a month. Tunable.

### What shipped

- `Npc/Religion/RiteSavedData.java` — `isMarker`/`isPrunable`/`pruneStaleRites` +
  the retention/cap constants. No codec change (just map removal).
- `Npc/Religion/RiteScheduler.java` — daily `pruneStaleRites` pass (#6 in
  `dailyTick`).

### Tie-In Audit

1. **Upstream feeders** — `RiteExecutor` (writes completed outcomes incl. the
   consecration SUCCESS marker via `withOutcome`, which stamps `completedTick`);
   the schedulers (write PENDING). The marker write is unchanged.
2. **Downstream callers** — `collectConsecrationMarkers` (the marker reader —
   `isMarker` retains exactly `(CONSECRATION, SUCCESSFUL)`, so it survives every
   prune); `dueRites`/`getRite`/`ritesForVillage` consumers (PENDING/in-flight —
   kept); `CommunityGatherings` (filters `isActiveAt` — completed rites already
   excluded); player-piety readers (untouched map).
3. **Sibling systems** — R3b-1 consecration (the ongoing blessing depends on the
   marker surviving — it does), `TempleProsperity` (reads building economy, not
   the ledger — unaffected), `/religion` debug listing (sees the last 30 days of
   completed rites + all markers/pending).
4. **Exhaustive switches** — `isMarker`/`isPrunable` branch on `Rite` +
   `RiteOutcome` via `==` (not a `switch`); no enum added. The marker arm is the
   single `CONSECRATION && SUCCESSFUL` condition. Confirmed.

### Simplification Sweep

- Classes in scope: `RiteSavedData` (the one classifier + one sweep), `RiteScheduler`
  (one pass on the existing tick). The marker set is complete (the audit above) and
  centralized in `isMarker`. No new tick/store/enum/codec/brain memory; pruning is
  map removal + `setDirty`.

### Deviations from prompt

- **Retention window is 30 days** (the event store `pruneOldCompletedEvents` keeps
  365). Chose tighter bounding for the higher-frequency rite ledger; 30 days still
  vastly exceeds any in-flight read of a completed rite. Flagged — trivially
  tunable to 365 for parity if preferred.

### Out-of-scope but flagged

- None new — this closes R4. (If future load-bearing completed-rite markers are
  added, they MUST be added to `isMarker` — the single, documented place.)

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done: the
marker reader (`collectConsecrationMarkers` reads `SUCCESSFUL CONSECRATION`) is
exactly what `isMarker` retains; PENDING + player-piety untouched;
`CommunityGatherings` filters `isActiveAt`; ordination uses the spec not the
ledger; `completedTick` is stamped by `withOutcome`; no codec/enum/brain-memory
change; prune is a capped map sweep on the existing daily tick.

### Smoke test (user-runnable)

1. Run many rites of various types (weddings, funerals, namings, blessings,
   offerings, festivals, ordinations) so the ledger grows; check the ledger size
   (e.g. via a debug listing).
2. Advance > 30 in-game days; confirm the transient completed rites are pruned and
   the ledger size is bounded (only the last ~30 days of completed rites remain).
3. **Consecrate a building, then advance past 30 days: confirm it STAYS
   consecrated** — it is NOT re-scheduled for consecration and keeps its ongoing
   daily blessing (the `(CONSECRATION, SUCCESSFUL)` marker survived).
4. Confirm PENDING rites (e.g. a scheduled-but-unperformed marriage) and the
   player-piety values are untouched after pruning.
5. Confirm a recently-completed rite (within 30 days) is still present.

---

## R5a — GRAVEYARD district + grave model + burial on death (2026-06-08)

First phase of R5 (death loop / graves). Builds the foundation: a per-village
graveyard with grave slots, a grave record (who lies in it), burial on death, and
the `HobbyLocation.GRAVEYARD` resolution — so R5b's `visit_grave` has a target.

### Disposition (findings, verified on branch)

- **Decoration-district model — MISMATCH.** Parks/Plaza are PLACEMENT-time
  (`ParkCandidateFinder` reserves, `ParkRenderer` renders) — NOT persisted
  districts, so there's no saved-data district to mirror. The cemetery grave-slot
  `DecorationTag` is **`HEADSTONE`**, which is **defined but UNUSED** (no existing
  graveyard/emitter wires it). So "model on Parks/Plaza + reuse the emitter" isn't
  literally available. → Built a PERSISTED `GraveyardSavedData` (mirroring the R4
  saved-data pattern) with a **manual grid of grave slots** (the HEADSTONE-slot
  semantics, manually placed per "no auto-layout"), not the auto-decoration
  emitter. Flagged.
- **Death hook** — `TownspersonMob.onNpcDeath(LivingDeathEvent)` →
  `DeathArc.onNpcDeath(deceased, level)` is the canonical entry. Burial added
  there.
- **`handleFuneral`** — the FUNERAL rite (grief ease + "remembered kindly"). Burial
  happens at DEATH (independent of the funeral-rite timing); the funeral's abstract
  remembrance IS the no-graveyard fallback.
- **`HobbyLocationResolver.GRAVEYARD`** — was `Optional.empty()`; now resolves to
  the village graveyard's `visitTarget`.
- **Persistence** — `VillageSavedData`'s codec is huge (47 fieldOf across nested
  codecs), so a SEPARATE `GraveyardSavedData` (its own `SavedDataType`, UUID-keyed
  map) is safer + matches the R4 store pattern (Pilgrimage/Rite SavedData).

### Design / decisions

- **`Grave`** (record + codec) — `deceasedId`, `name`, `deathTick`, `slot`
  (BlockPos, the R5b navigation target), `epitaph`.
- **`Graveyard`** (class + codec) — `villageId`, `centre`, `slots` (a fixed grid),
  `graves`. `capacity = slots.size()`; **reuse-oldest** when full (bury at the
  oldest grave's slot — capacity-bounded, no expansion); `visitTarget` = the
  most-recent grave (else the centre).
- **`GraveyardSavedData`** — per-village map; `createGraveyard(villageId, centre,
  rows, cols, spacing)` (manual grid) + `bury(villageId, deceasedId, name, tick)`.
- **Burial on death** (`DeathArc.buryIfGraveyard`) — resolve the deceased's village
  → if a graveyard exists, record a grave (reuse-oldest) + place a **minimal
  best-effort headstone** (a `COBBLESTONE_WALL`, only when the slot's chunk is
  loaded; the DATA is the source of truth). No graveyard / no village → no grave,
  the FUNERAL rite still remembers them (graceful, wrapped in try/catch — never an
  error).
- **Manual creation** — `/religion graveyard [rows] [cols]` (default 4×4 = 16
  slots, spacing 2) at the executor's position, village resolved from position.

### What shipped

- `Village/Graveyard/Grave.java`, `Graveyard.java`, `GraveyardSavedData.java` (new).
- `Npc/Aging/DeathArc.java` — burial hook (`buryIfGraveyard` + `placeMarker`).
- `Npc/Hobby/HobbyLocationResolver.java` — `GRAVEYARD` resolves to the graveyard.
- `Commands/ReligionDebugCommand.java` — `/religion graveyard` creation command.

### Tie-In Audit

1. **Upstream feeders** — NPC death (`DeathArc.onNpcDeath`, the canonical hook),
   the manual `/religion graveyard` command, the deceased's village resolution.
   The FUNERAL rite is unchanged (burial is at death, not in the rite).
2. **Downstream callers** — `HobbyLocationResolver.GRAVEYARD` (now resolves → R5b
   `visit_grave` can target it), `GraveyardSavedData` persistence. The
   `visit_grave` hobby (R5b future consumer) becomes resolvable.
3. **Sibling systems** — decoration districts (Parks/Plaza UNTOUCHED — graveyard
   is a separate saved-data store, not the placement pass; no regression).
   `RelationshipDispatcher`/other death reactions (DeathArc still runs them; burial
   is an additive first step, wrapped in try/catch). R3e-2/R4 unaffected.
4. **Exhaustive switches** — `HobbyLocation`: the `GRAVEYARD` arm is updated (no
   new enum value). No new enum added. Confirmed.

### Simplification Sweep

- Classes in scope: `Grave`/`Graveyard`/`GraveyardSavedData` (new — the model +
  one store), `DeathArc` (one burial hook on the existing death path), `HobbyLocationResolver`
  (the GRAVEYARD arm), `ReligionDebugCommand` (creation). Reuses the R4 saved-data
  pattern + the existing death hook — no parallel death handling. Capacity policy:
  reuse-oldest (bounded). Manual creation via the command (no auto-layout). New
  codec (GraveyardSavedData) is a separate store (no VillageSavedData cap risk); no
  new brain memory.

### Deviations from prompt

- **Built a persisted `GraveyardSavedData`, not a Parks/Plaza-style district nor
  the auto-decoration `HEADSTONE` emitter** — Parks aren't persisted and the
  HEADSTONE tag is unused/unwired. The grave slots are a manual grid (the
  HEADSTONE-slot semantics); wiring graveyards into the decoration pass pairs with
  the deferred placement/auto-layout work, and the `HEADSTONE` tag is the hook for
  that.
- **Burial happens at DEATH** (`DeathArc`), not inside the FUNERAL rite — the grave
  exists immediately; the funeral is the remembrance + the no-graveyard fallback.
- **Minimal cosmetic marker** (one `COBBLESTONE_WALL`, best-effort/loaded-only);
  elaborate headstone NBT deferred.

### Out-of-scope but flagged

- `visit_grave` behaviour + grief → R5b; ancestor veneration → R5c; authored
  grave/headstone NBT + wiring the HEADSTONE decoration emitter / auto-layout for
  graveyard placement. Optional epitaph/cause text is recorded-but-empty (a
  death-cause feed is a later enhancement).

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done:
`Grave`/`Graveyard`/`GraveyardSavedData` codecs (BlockPos/UUIDUtil/unboundedMap);
`SavedDataType` + `computeIfAbsent` (R4 pattern); `DeathArc` burial wrapped in
try/catch + `level.isLoaded`/`setBlockAndUpdate`/`Blocks.COBBLESTONE_WALL`;
`HobbyLocationResolver` GRAVEYARD arm returns `Optional<BlockPos>` (exhaustive
switch intact); `getVillageAt`/`IntegerArgumentType` for the command; reuse-oldest
capacity; no new enum/brain memory.

### Smoke test (user-runnable)

1. Stand in a village and run `/religion graveyard 4 4`; confirm a graveyard with
   16 slots is created and `HobbyLocation.GRAVEYARD` now resolves (an NPC's
   visit-graveyard hobby can target it — visiting itself is R5b).
2. Kill a village NPC (e.g. `/kill` on a TownspersonMob); confirm a grave is
   recorded (deceased name + death tick) at a free slot and a cobblestone-wall
   marker appears there (if the slot's chunk is loaded).
3. Kill enough NPCs to exceed 16; confirm the capacity policy = reuse-oldest (the
   oldest grave's slot is reused) rather than erroring or growing unbounded.
4. In a village with NO graveyard, kill an NPC; confirm it's handled gracefully (no
   grave, no error; the FUNERAL rite still runs its remembrance).
5. Save + reload; confirm the graveyard + its graves persist.

---

## R5b — visit_grave + grief / remembrance (2026-06-08)

Makes the `visit_grave` hobby functional on the R5a graveyard: the bereaved walk
out to a kin's grave, grief eases gradually, and remembrance is recorded — the
legible heart of the death loop.

### Disposition (findings, verified on branch)

- **Hobby perform** — `HobbyBehavior` walks to the hobby's `HobbyLocation` then
  poses for `durationTicks` (`WALKING → PERFORMING → LEAVING`). `VISIT_GRAVE` was
  **pose-only** (a no-op in `performTick`/`equipForActivity`); R5a made
  `HobbyLocation.GRAVEYARD` resolve, so the walk now works — only the EFFECT was
  missing.
- **Grief surface** — a death applies a MOOD low to survivors via `MoodProducer`
  (`FAMILY_DEATH` −60 for KIN/SPOUSE, `CLOSE_FRIEND_DEATH` −35); there is **NO
  persistent grief `HealthCondition`** (no MELANCHOLY on death). `handleFuneral`
  eases grief with `MoodTrigger.LETTER_FROM_FRIEND` (a comfort channel,
  daily-stack-capped at 0.2 → 20/day). → Easing = the same comfort channel; the
  per-day cap is the natural anti-double-dip + anti-farm.
- **Relationships** — `npc.getNpcRelationships().getScore(UUID)` persists by UUID
  (so it works for a DEAD deceased) → find the cared-about buried deceased.
- **Remembrance memory** — no "remembrance" `MemoryType` exists; reused
  `SHARED_HARDSHIP` (POSITIVE), keyed to the deceased's UUID. (A dedicated type
  would be a speculative enum — avoided.)

### Design / decisions

- **`GraveVisit` (new, Village.Graveyard)** — the brains:
  - `caredAboutGrave(level, npc)` — the buried deceased with the highest positive
    relationship (≥ `MIN_CARE=20`); empty when none / no graveyard.
  - `visitTarget(level, npc)` — that cared-about grave's slot, else the graveyard's
    general `visitTarget`; empty with no graveyard.
  - `contemplate(level, npc, now)` — the once-per-visit effect: a bereaved visitor
    gets a bounded grief-ease (`LETTER_FROM_FRIEND +5`, the SAME daily-capped
    channel as the funeral → no double-dip, gradual over repeated visits) + a
    `SHARED_HARDSHIP` remembrance memory; a general visitor gets a small
    contemplative touch (`WEATHER_PLEASANT +2`).
- **Resolver** — `HobbyLocationResolver.GRAVEYARD` now returns
  `GraveVisit.visitTarget` (NPC-aware: the kin grave, else general).
- **Effect hook** — `HobbyBehavior.startPerforming`: when the activity is
  `VISIT_GRAVE`, call `GraveVisit.contemplate` ONCE on arrival (the pose continues
  through PERFORMING). One `if`, not a new switch arm — the `HobbyActivity`
  switches are untouched.
- **The bereaved draw** — `NpcHobbyPreference.generate` adds `BEREAVEMENT_BOOST=0.6`
  to the `visit_grave` candidate's score when `caredAboutGrave` is present (computed
  only for that one candidate), so the recently-bereaved are more likely to pick it.

### What shipped

- `Village/Graveyard/GraveVisit.java` (new) — targeting + grief/remembrance effect.
- `Npc/Hobby/HobbyLocationResolver.java` — `GRAVEYARD` → `GraveVisit.visitTarget`.
- `Npc/Brain/Behaviors/HobbyBehavior.java` — `VISIT_GRAVE` arrival fires the effect.
- `Npc/Hobby/NpcHobbyPreference.java` — `BEREAVEMENT_BOOST` for `visit_grave`.

### Tie-In Audit

1. **Upstream feeders** — hobby selection (the bereaved boost), `HobbyLocationResolver.GRAVEYARD`,
   R5a `Graveyard`/`Grave` lookup, `NpcRelationships` (who cared), the death-mood
   grief state.
2. **Downstream callers** — `HobbyBehavior` (now performs VISIT_GRAVE), mood
   (`LETTER_FROM_FRIEND`/`WEATHER_PLEASANT`), the memory log (remembrance),
   `Graveyard.visitTarget`/`graveOf`.
3. **Sibling systems** — `handleFuneral`: NO double-ease — both use the same
   daily-capped `LETTER_FROM_FRIEND`, so the per-day cap bounds total recovery
   (the funeral + visits share one comfort budget). The liveliness hobby/idle
   system: `visit_grave` is one of the trait-weighted hobbies (fires correctly via
   the L2 LEISURE wiring); the bereaved boost just lifts its score. R5c ancestor
   veneration: a future consumer of the same grave visiting.
4. **Exhaustive switches** — `HobbyActivity`: the existing pose-only arms are
   unchanged; the effect is an `if` in `startPerforming`, not a switch arm. No new
   enum. Confirmed.

### Simplification Sweep

- Classes in scope: `GraveVisit` (new — targeting + effect, 3 inbound: resolver,
  HobbyBehavior, NpcHobbyPreference), `HobbyLocationResolver`/`HobbyBehavior`/
  `NpcHobbyPreference` (one wiring each). `visit_grave` is one hobby-activity
  implementation reusing the hobby behavior + R5a lookup + the mood/memory/relationship
  paths — no parallel visit/grief framework. No new brain memory (reuses the hobby
  cooldown); no new enum; no codec change.

### Deviations from prompt

- **Grief is a mood low (no persistent condition)**, so the ease is a bounded
  mood lift on the funeral's comfort channel rather than "clearing a grief state";
  the daily-stack cap is the anti-double-dip + anti-farm, and recovery is gradual
  over days/visits.
- **Remembrance reuses `SHARED_HARDSHIP`** (no dedicated `MemoryType`) — keyed to
  the deceased; one memory per cared-about visit (bounded by the hobby cadence +
  memory decay). A dedicated REMEMBERED type is a flagged enhancement.
- **The bereaved draw couples `NpcHobbyPreference` → `GraveVisit`** (a graveyard +
  relationship lookup), computed only for the `visit_grave` candidate.

### Out-of-scope but flagged

- Ancestor veneration (Forge Creed / Ancestor Oath at graves) → R5c; graveyard
  auto-placement (deferred). A dedicated remembrance `MemoryType`; refreshing
  (vs re-adding) the remembrance memory; scaling the draw/ease by grief recency.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done:
`getNpcRelationships().getScore`, `getMood().applyWithRawMagnitude`,
`getMemory().add(NpcMemory.create(...))`, `MoodTrigger.LETTER_FROM_FRIEND`/`WEATHER_PLEASANT`,
`MemoryType.SHARED_HARDSHIP`, R5a `Graveyard.graves/visitTarget`/`Grave.slot/deceasedId/name`,
`GraveVisit.visitTarget` returns `Optional<BlockPos>` (resolver arm exhaustive),
`generate(npc, level, rng)` params, `entity.level() instanceof ServerLevel`
confirmed; the funeral + visit share the comfort cap (no double-ease); no new
enum/codec/brain memory.

### Smoke test (user-runnable)

1. In a village with a graveyard, kill an NPC who has surviving kin / close friends
   (high relationship). Confirm a grave is recorded (R5a) and the bereaved kin are
   now drawn to `visit_grave` (the BEREAVEMENT_BOOST lifts it in their hobby
   rotation).
2. Watch a bereaved NPC pick `visit_grave`: confirm they walk to THAT specific
   grave (the cared-about deceased's slot), pose, and on arrival their mood lifts a
   little + a remembrance memory ("Visited X's grave…") appears.
3. Over repeated visits/days, confirm grief eases GRADUALLY (not a one-visit cure,
   not farmable — the LETTER_FROM_FRIEND daily cap bounds it, shared with the
   funeral so no double-ease).
4. Confirm a non-bereaved NPC can still do a general contemplative graveyard visit
   (a small calming touch, no remembrance).
5. Confirm a village with no graveyard / an NPC with no cared-about buried deceased
   degrades gracefully — the hobby simply doesn't resolve a grave (no error).

---

## R5c — Ancestor veneration (Forge Creed at the graves) (2026-06-08)

Final phase of R5 + a capstone tying R3 Forge content to the R5 death loop. The
Forge Creed's Ancestor Oath is now sworn AT the graveyard, and Forge adherents
venerate their ancestors there — a faith-specific payoff distinct from R5b's
grief mourning. **Completes R5 (the death loop).**

### Disposition (findings, verified on branch)

- **ANCESTOR_OATH venue (R3e-2b)** — `checkSignatureRite` → `scheduleSignatureForFaith`
  maps FORGE_CREED → `ANCESTOR_OATH` @ "Ancestor Day", then `scheduleEvent(…,
  faithId, Building venue)` pins the gathering location to the faith's BUILDING
  origin (the Forge building). The location flows to the blessing rite
  (`CeremonyBlessings.attach` → `scheduleBlessingRite`), which is where R2b
  attendance + R3d-1 fronting converge. → Relocating = changing the location passed.
  Since `scheduleEvent` only used the Building for its origin, refactored its venue
  param **Building → BlockPos** so a NON-building location (the graveyard) can be
  passed.
- **R5a/R5b** — `GraveyardSavedData.getGraveyard(villageId)` → `Graveyard.centre`
  (the oath venue); `GraveVisit` (the grave-visiting machinery) extends for
  veneration.
- **R3c Ancestor-Keepers** — the Forge order; R3d-1 fronting walks the priest to
  the LINKED RITE's location, so with the oath relocated to the graveyard the
  Ancestor-Keeper priest fronts there automatically (no fronting change needed).
- **Faith gate** — veneration is gated to FORGE_CREED primary (the ancestor faith);
  others don't venerate (sparse-friendly; a `ReligionContent` opt-in could extend
  it later).
- **Reverence/resolve channel** — used `MoodTrigger.FESTIVAL_ATTENDED` (the bounded
  religious-observance mood, daily-cap 0.2) — DISTINCT from R5b's grief-comfort
  `LETTER_FROM_FRIEND`, so worship and mourning don't share a budget.

### Design / decisions

- **Oath at the graveyard** — `scheduleEvent`'s venue param refactored Building →
  BlockPos (an `originOf(Building)` helper feeds the signature/grand/vigil callers).
  In `scheduleSignatureForFaith`, for `FORGE_CREED` + `ANCESTOR_OATH` the location
  becomes the **graveyard centre** when one exists, else the Forge building origin
  (R5a graceful fallback). R2b congregation + R3d-1 Ancestor-Keeper fronting then
  follow the rite to the graveyard — no scheduler/fronting fork.
- **Veneration** — `GraveVisit.contemplate` now: (R5b) grief-ease + remembrance for
  a cared-about grave (universal mourning), a contemplative touch for a general
  non-venerator; PLUS (R5c) for a Forge adherent (`isAncestorVenerator`), a
  `venerate` — a reverence/resolve mood (`FESTIVAL_ATTENDED +4`) + a small Forge
  piety deepening (the ancestor-connection). The two effects are ADDITIVE on
  DISTINCT channels (a grieving Forge adherent gets both; no shared budget).
- **Draw** — `GraveVisit.drawsToGrave` = a bereaved NPC (cared-about grave) OR a
  Forge venerator where a graveyard exists; `NpcHobbyPreference` boosts `visit_grave`
  for it (replacing R5b's bereaved-only condition), so Forge adherents venerate
  regularly.
- Bounded: the veneration mood is daily-capped (FESTIVAL_ATTENDED 0.2), the piety
  bump is small, and the hobby cadence limits frequency — a steady practice, not a
  farm.

### What shipped

- `Village/Event/VillageEventScheduler.java` — `scheduleEvent` venue Building →
  BlockPos (+ `originOf` helper); the Forge Ancestor Oath relocates to the
  graveyard (fallback to the Forge building).
- `Village/Graveyard/GraveVisit.java` — `isAncestorVenerator`, `venerate`,
  `drawsToGrave`; `contemplate` adds the Forge veneration alongside R5b grief.
- `Npc/Hobby/NpcHobbyPreference.java` — the grave draw now uses `drawsToGrave`
  (bereaved + Forge venerators).

### Tie-In Audit

1. **Upstream feeders** — `checkSignatureRite`/ANCESTOR_OATH + its venue resolution
   (now graveyard-aware), `BuildingFaith` (the fallback building), R5a graveyard,
   R3c Ancestor-Keepers, `FaithReconciliation`/piety (the Forge gate).
2. **Downstream callers** — the gathering venue (relocated → the blessing rite at
   the graveyard → R2b attendance + R3d-1 fronting converge there), R5b `GraveVisit`
   (extended for veneration), mood (`FESTIVAL_ATTENDED`)/piety channels.
3. **Sibling systems** — R5b grief mourning: NO double-dip — veneration uses a
   DIFFERENT mood channel (`FESTIVAL_ATTENDED`) than the grief comfort
   (`LETTER_FROM_FRIEND`); both can target graves and coexist. R3 Forge content
   (oath/funeral/order) unchanged. Other faiths: unaffected — their signatures
   still fire at their building (only FORGE_CREED+ANCESTOR_OATH relocates), and
   they don't venerate.
4. **Exhaustive switches** — no new enum/`Rite`/`EventType` (ANCESTOR_OATH exists);
   `HobbyActivity` switches unchanged; the `scheduleEvent` venue type change has a
   single 7-arg overload (no ambiguity), 3 callers + the delegator updated.
   Confirmed.

### Simplification Sweep

- Classes in scope: `VillageEventScheduler` (one venue-resolution change + the
  Building→BlockPos generalization — not a scheduler fork), `GraveVisit` (veneration
  reuses the R5b visit machinery + the mood/piety channels — not a new behavior),
  `NpcHobbyPreference` (the draw extended). Veneration reuses `GraveVisit` + the
  gathering venue + the order/faith layers; the oath relocation is one location
  override. No new tick/behavior/enum/codec/brain memory.

### Deviations from prompt

- **Refactored `scheduleEvent`'s venue param (Building → BlockPos)** — needed so a
  non-building location (the graveyard centre) can host the oath; a clean
  generalization (only the origin was used), the 3 faith callers + the delegator
  updated.
- **The "kin-bond / ancestor-connection" is a Forge piety deepening** (worship
  deepens ancestor faith) rather than a living-kin relationship bump (finding the
  shared-ancestor kin is complex) — flagged.
- **Faith-gated to FORGE_CREED directly** (the ancestor faith); sparse-friendly —
  other faiths don't venerate. A `ReligionContent` opt-in for additional faiths is
  a flagged extension.

### Out-of-scope but flagged

- Generalizing ancestor veneration to other faiths (beyond the Forge gate);
  graveyard auto-placement (deferred). A living-kin bond on veneration; a dedicated
  reverence MoodTrigger; relocating other Forge rites (funeral) to the graveyard.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done:
`scheduleEvent` Building→BlockPos (single 7-arg overload, no ambiguity; delegator +
3 callers pass `originOf(venue)`/`loc`); the Forge oath relocates to
`Graveyard.centre` with the building fallback; `GraveVisit.venerate` uses
`FESTIVAL_ATTENDED` (distinct from grief `LETTER_FROM_FRIEND`) + a Forge piety bump;
`drawsToGrave` covers bereaved + venerators; no new enum/codec/brain memory.

### Smoke test (user-runnable)

1. In a FORGE_CREED village WITH a graveyard, advance to Ancestor Day; confirm the
   Ancestor Oath gathering fires AT the graveyard centre (not the Forge building),
   fronted by the Ancestor-Keeper priest (R3d-1), with Forge adherents congregating
   there (R2b).
2. Confirm a FORGE_CREED village with NO graveyard keeps the oath at the Forge
   building (graceful fallback, no error).
3. Confirm Forge adherents periodically pick `visit_grave` (drawn even without a
   personal loss) and, on visiting, get a reverence/resolve mood
   (`FESTIVAL_ATTENDED`) + a small Forge piety bump — DISTINCT from the grief-ease,
   and NOT sharing the grief comfort budget (a grieving Forge adherent gets both).
4. Confirm a NON-Forge faith's adherents do NOT venerate (grief mourning still works
   per R5b), and their signature rites still fire at their own building.
5. Confirm veneration is bounded (the FESTIVAL_ATTENDED daily cap + the hobby
   cadence — not a buff farm).

---

## R9a — NPC religion profile panel (2026-06-08)

### Disposition

The prompt asks for a **read-only Religion panel** in the existing
`NpcProfileScreen`, so right-clicking any NPC surfaces their full religious state
(R1–R5) at a glance for testing. Investigation confirmed the profile screen is a
single sidebar-driven hub: `NpcProfileScreen.init()` builds its sidebar from
`NpcProfilePanelRegistry.Section.values()` (so a new enum constant auto-appears as a
tab, in declaration order) and its panel map from `NpcProfilePanelRegistry.build()`;
both `OpenNpcProfilePacket` and `NpcProfileSyncPacket` ride a single
`NpcProfileSnapshot.CODEC`, so religion fields added to that snapshot reach the
client on open AND on every 5s refresh — no parallel screen or packet. The snapshot
uses a hand-rolled append-friendly `StreamCodec.of(...)` (no 16-arg composite
ceiling), so the religion block appends cleanly at the tail of encode/decode/ctor.

All religious state already has server-side accessors (R1–R5): `PietyComponent`
(primaryReligion/primaryStrength/primaryTier/beliefs/ritesAttendedThisMonth/
meetsMonthlyAttendance), `ReligionRegistry.find`, `Religion.displayName/deity`,
`ClergyOrders.assignedOrderName`, `BuildingFaith.resolveFaith`,
`FaithReconciliation.isUnservedLocally`, `ApprenticeRank.fromSkillLevel`. The panel
is pure read — no verbs, no brain memory, no new enum (the `Section.RELIGION`
constant is the only new symbol, and it has a concrete consumer: the tab itself).

### What shipped

- **`Networking/NpcProfileSnapshot.java`** — appended a 12-field "Religion (R9a)"
  block to the record (`religionName`, `deityName`, `pietyStrength`, `pietyTier`,
  `beliefSummary`, `ritesThisMonth`, `meetsMonthlyAttendance`, `isClergy`,
  `clergyOrder`, `clergyTitle`, `staffedFaith`, `isUnservedLocally`) plus matching
  encode writes, decode reads, and constructor args — all at the tail, after the
  R5a.5 nav block, preserving wire order.
- **`Entities/NpcProfileSnapshotBuilder.java`** — a "Religion (R9a)" computation
  block reading `npc.getPiety()`: resolves the primary religion's display name +
  deity, piety strength + tier display, a syncretic `beliefSummary` (only when
  `beliefs.size() > 1`, lines "Faith — NN%"), rite count + monthly-attendance flag,
  clergy status (`prof == PRIEST`), order name, cosmetic title, staffed-building
  faith (display name), and the served/unserved predicate (only when a village is
  present). Added the private `clergyTitleFor(npc)` helper mirroring
  `PriestBehavior.clergyTitle` (APPRENTICE→Initiate, JOURNEYMAN→Priest,
  MASTER→Senior Priest).
- **`Gui/NpcProfile/ReligionPanel.java`** (new) — `NpcProfilePanel` rendering, in
  order: "Religion" header; an early "Unaffiliated" return for atheists (empty
  `religionName`); the faith line (name + optional deity); a piety strength
  `NeedMeter.bar` (clamped 0..1, BLUE_BG) with a right-aligned tier `Pill` and a
  "Piety NN%" line; syncretic belief lines (when present); a served/unserved `Pill`
  (GREEN_BG/RED_BG); an Observance `StatBox`; and, for clergy, a title/order
  `StatBox` + a "Tends a <faith> building." line. Uses only `Gui.Framework`
  primitives.
- **`Gui/NpcProfile/NpcProfilePanelRegistry.java`** — added `RELIGION("Religion")`
  to the `Section` enum (after REPUTATION) and `map.put(Section.RELIGION, new
  ReligionPanel())` to `build()`. The screen's `Section.values()` iteration picks up
  the tab with no screen edits.

### Tie-In Audit

1. **Upstream feeders.** The panel reads only the snapshot; the snapshot is fed by
   `NpcProfileSnapshotBuilder.build`, which reads `PietyComponent` + the R1–R5
   registries. All accessors confirmed present with the used signatures. No feeder
   system is mutated (pure read).
2. **Downstream callers.** `NpcProfileSnapshot`'s constructor is called in exactly
   one place (`NpcProfileSnapshotBuilder.build`) — updated with the 12 new args.
   Its `CODEC` is consumed by `OpenNpcProfilePacket` + `NpcProfileSyncPacket` (both
   unchanged — they ride `CODEC` generically). No other caller constructs the
   record. `NpcProfilePanelRegistry.build`'s map consumers (`NpcProfileScreen`)
   iterate generically over the map / `Section.values()` — unaffected.
3. **Sibling systems.** Other panels (Identity/Family/Work/Reputation/Dialogue/
   Actions) read disjoint snapshot fields — appending fields can't disturb them.
   The sidebar grows by one row (COMPACT chrome has vertical room for 7 rows).
4. **Exhaustive switches.** The only switch added is `clergyTitleFor`'s over
   `ApprenticeRank` (all three arms covered). No existing enum gained a constant
   that an exhaustive switch must handle — `Section` is iterated via `values()`
   (sidebar) and keyed via map lookup, never `switch`ed exhaustively (grep
   confirmed no `switch` over `Section`).

### Simplification Sweep

- Classes in scope: `ReligionPanel` (new, one inbound caller — the registry),
  `NpcProfilePanelRegistry` (+1 enum, +1 map entry), `NpcProfileSnapshot` (+12
  tail fields), `NpcProfileSnapshotBuilder` (+1 block, +1 private helper). No
  orphans created; `ReligionPanel` parallels the existing `ReputationPanel` shape
  (same Framework primitives, same `PageArea` layout idiom) — no new framework, no
  overlapping pair. `clergyTitleFor` duplicates `PriestBehavior.clergyTitle`'s 3-arm
  mapping (flagged below) rather than exposing a new shared helper this phase.

### Deviations from prompt

- **`clergyTitleFor` duplicates `PriestBehavior.clergyTitle`** (the same
  APPRENTICE/JOURNEYMAN/MASTER → Initiate/Priest/Senior-Priest mapping) rather than
  promoting a shared helper. The original is `private` in `PriestBehavior`;
  extracting a shared `ClergyTitles.of(rank)` is a clean follow-up but out of scope
  for a read-only panel — flagged.
- **`beliefSummary` is populated only when `beliefs.size() > 1`** (a genuinely
  syncretic NPC); a single-faith NPC shows just the primary faith line, no
  redundant "Faith — 100%" row. Matches the prompt's "syncretic belief map" intent
  without noise.

### Out-of-scope but flagged

- A shared `ClergyTitles` helper to retire the `clergyTitleFor` /
  `PriestBehavior.clergyTitle` duplication.
- Religion **verbs** (e.g. "convert", "request blessing") — the prompt scoped this
  phase read-only; the panel leaves room for an action row in a later R9 pass.
- The panel surfaces the served/unserved predicate but no remediation affordance
  (e.g. "no temple nearby" guidance) — deferred to the R6–R8 provision work.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done: all R1–R5
accessor signatures confirmed by grep (`primaryReligion`/`primaryStrength`/
`primaryTier`/`beliefs`/`ritesAttendedThisMonth`/`meetsMonthlyAttendance`,
`ReligionRegistry.find`, `Religion.displayName`/`deity`, `ClergyOrders.
assignedOrderName`, `BuildingFaith.resolveFaith`, `FaithReconciliation.
isUnservedLocally`, `ApprenticeRank.fromSkillLevel`, `PietyTier.displayName`,
`TownspersonMob.getPiety`/`getSkills`, `Skill.SOCIAL`); the snapshot's
encode/decode/constructor field order is consistent across all three (12 fields,
same order, tail-appended); `ReligionPanel` uses only public `Gui.Framework`
primitives (avoided `Pill.HEIGHT`, which is private — used a literal 17 = 12 height
+ 5 gap); `Section.RELIGION` is iterated via `values()` (sidebar) and keyed via map
lookup, never exhaustively switched.

### Smoke test (user-runnable)

1. Right-click a **devout, single-faith** NPC → the **Religion** tab appears in the
   sidebar; opening it shows the faith name (+ deity if any), a filled piety bar
   with the tier pill (FAITHFUL/DEVOUT/PIOUS), "Piety NN%", a "Served"/"Unserved
   locally" pill, and the Observance box ("N rite(s) this month — observant" once
   the monthly threshold is met). No belief sub-list (single faith).
2. Right-click an **atheist / unaffiliated** NPC → the Religion tab shows
   "Unaffiliated" and nothing else (no bar, no crash).
3. Right-click a **syncretic migrant** (carries home + local faith) → the Beliefs
   sub-list shows both faiths with percentages summing to ~100%.
4. Right-click a **PRIEST** → an extra clergy box shows the cosmetic title
   (Initiate/Priest/Senior Priest by SOCIAL rank) + the order name (or
   "(generalist)"), and "Tends a <faith> building." when they staff a temple.
5. Open the profile and **wait ≥5s** → the 5s `NpcProfileSyncPacket` refresh keeps
   the Religion panel populated (data rides the same snapshot, not a one-shot open).

---

## R9b — religious-building (temple) screen (2026-06-08)

### Disposition (findings)

The prompt asks for a **read-only religious-building screen** mirroring the
`OpenBusinessFrontPacket → BusinessFrontScreen(data)` pattern: an Open packet
that CARRIES a server-computed snapshot, and a `Screen` that only renders it
(no separate sync, no actions). Investigation confirmed:

- **Screen pattern.** `BusinessFrontScreen` is a single-page `Chrome.COMPACT`
  (320×240) `Screen`; `OpenBusinessFrontPacket` is the data record + a manual
  `StreamCodec.of(...)` + a `handle` that does `mc.setScreen(new …Screen(pkt))`.
  Render order is dim → `Chrome.draw` → content → `super.render` so widgets
  paint on top. Packets register in `ModModEvents.registerPayloads` via
  `registrar.playToClient(TYPE, CODEC, ::handle)`.
- **Open trigger.** No clean block/NPC interaction is religious-building-aware,
  so (per the prompt's "debug command is fine — testability is the goal") I
  reused the existing `/religion` command (`ReligionDebugCommand`) and added a
  `temple` subcommand that finds the nearest religious building in the village
  at the executor's position (mirrors the existing `shrine` subcommand's
  nearest-building scan) and `PacketDistributor.sendToPlayer`s the snapshot.
- **Data accessors (all server-side reads, confirmed by grep):**
  `BuildingFaith.resolveFaith` / `isReligiousBuilding`; `ReligionRegistry.get`
  → `Religion.displayName()`/`deity()`; `VillageSavedData.getOrCreateBuildingEconomy`
  → `BuildingEconomy.getTreasury()`/`getDaysInsolvent()`; `TempleProsperity`'s
  private `DAILY_COST`/`SOLVENCY_BUFFER`/`DECAY_DAYS`/`ABANDON_DAYS` (the R4c
  single source of truth); `Building.getCondition()` (`BuildingCondition`
  enum, `needsRepair()`); the consecration marker = a SUCCESSFUL
  `Rite.CONSECRATION` in `RiteSavedData.ritesForVillage` near the building
  origin (R4e: the one rite pruning never removes); candle stock via
  `BuildingStorageAccess.countItem(level, building, Items.WHITE_CANDLE)`;
  clergy via the village-bounds AABB scan for the seated `PRIEST`
  (`ClergyOrders.assignedOrderName` + the shared title helper); congregation =
  resident NPCs whose `PietyComponent.primaryReligion()` matches the faith
  (+ average `primaryStrength`); upcoming holy days via
  `Religion.calendar().holyDaysByName()` + `effectiveDayOfYear` vs the current
  day-of-year `(gameTime/24000) % DAYS_PER_YEAR` (the same formula
  `VillageEventScheduler` uses).

**Health-state derivation.** Rather than a new enum or duplicating R4c's magic
numbers client-side, I added a read-only `TempleProsperity.healthLabel(condition,
treasury, daysInsolvent, staffed)` (plus `dailyCost()` / `solvencyBuffer()`
accessors) that returns Flourishing / Solvent / At-risk / Decaying / Abandoned
from the SAME solvency + `BuildingCondition` state the R4c tick decays on, so
the screen can never drift from the simulation. The builder calls it; the
screen renders the returned string.

### What shipped

- **`Networking/OpenTempleScreenPacket.java`** (new) — the data-carrying Open
  packet: building identity + faith/deity, economy (treasury, daily cost,
  surplus, days-insolvent, health state), condition + decaying flag,
  consecration, candle count, clergy (staffed/name/order/title), congregation
  (count + aggregate piety), and an upcoming-holy-days list. Manual
  `StreamCodec.of`; `handle` opens `TempleScreen`.
- **`Npc/Religion/TempleSnapshotBuilder.java`** (new) — pure-read server
  gatherer (mirrors `NpcProfileSnapshotBuilder`). Resolves all of the above;
  graceful when faith/clergy/economy are absent (empty strings / 0 / false).
- **`Gui/TempleScreen.java`** (new) — read-only `Chrome.COMPACT` screen.
  Title + faith subtitle; a health pill (green/amber/red) + consecration pill;
  StatBoxes for Treasury / Daily cost / Surplus(or days-insolvent) / Condition
  / Candles / Congregation; a full-width Clergy box ("Vacant" when unstaffed);
  an aggregate-piety `NeedMeter.bar`; and the upcoming-holy-days list ("(none
  scheduled)" when empty). Only `Gui.Framework` primitives; a single Close
  button. No actions.
- **`Npc/Religion/TempleProsperity.java`** — added `dailyCost()`,
  `solvencyBuffer()`, and `healthLabel(...)` read-only views (the R4c
  thresholds stay private and single-sourced).
- **`Npc/Religion/ClergyTitles.java`** (new) — the shared Initiate/Priest/
  Senior-Priest helper the R9a sweep flagged. `of(npc)` / `forSocialLevel(int)`.
- **Refactors retiring the duplication:** `PriestBehavior.clergyTitle()` now
  delegates to `ClergyTitles.of(entity)`; `NpcProfileSnapshotBuilder` drops its
  private `clergyTitleFor` and calls `ClergyTitles.of` (this also re-seated the
  orphaned Step-5 javadoc back onto `resolveNavKind`).
- **`Events/ModModEvents.java`** — registered `OpenTempleScreenPacket`
  (`playToClient`).
- **`Commands/ReligionDebugCommand.java`** — added the `/religion temple`
  subcommand (nearest religious building → send the snapshot).

### Tie-In Audit

1. **Upstream feeders.** `BuildingFaith` (faith), `BuildingEconomy` via
   `VillageSavedData` (treasury/insolvency), `TempleProsperity` (cost/buffer/
   health label — the new read-only views I added), `Building.getCondition`
   (R4c), `RiteSavedData` (consecration marker), `BuildingStorageAccess`
   (candles), the village-bounds AABB scan (clergy + congregation),
   `Religion.calendar()` (festivals), the `/religion temple` trigger. All are
   read-only; the builder mutates nothing.
2. **Downstream callers.** New: `OpenTempleScreenPacket.handle` (client
   setScreen), `TempleScreen` (renderer), `/religion temple` (server send),
   the `ModModEvents` registration. The shared `ClergyTitles` has three
   inbound callers (`PriestBehavior`, `NpcProfileSnapshotBuilder`,
   `TempleSnapshotBuilder`) — all verified to pass a `TownspersonMob` and use
   the identical mapping the originals had (behavior preserved).
3. **Sibling systems.** The screen reuses the BusinessFront screen pattern +
   `Gui.Framework` + `Chrome.COMPACT`/`PARCHMENT` verbatim — no new screen
   framework, no change to existing screens or the shared packet-registration
   shape. `TempleProsperity.tickVillage` (R4c) is untouched; the new helpers
   only READ its constants, so the decay simulation and the screen agree by
   construction.
4. **Exhaustive switches.** No new enum. The only `switch`es added are over the
   existing `ApprenticeRank` (in `ClergyTitles`, all three arms) and a `String`
   health label (in `TempleScreen.healthBg`, with a `default` arm). No existing
   exhaustive switch gained an arm. Confirmed.

### Simplification Sweep

- **GUI/religion classes in scope + inbound callers:** `OpenTempleScreenPacket`
  (1 — `ModModEvents` register + `/religion temple`), `TempleSnapshotBuilder`
  (1 — the command), `TempleScreen` (1 — the packet handler), `ClergyTitles`
  (3 — see audit), `TempleProsperity` (+3 read views, used by the builder).
  No orphans created. The screen parallels `BusinessFrontScreen`'s shape
  (same Chrome, same render order, same Close-button idiom) — no overlapping
  pair, no new framework.
- **Duplication retired:** the R9a-flagged `clergyTitle` triplication
  (`PriestBehavior` + `NpcProfileSnapshotBuilder` + would-be temple builder)
  is now a single `ClergyTitles` helper — exactly the consolidation the R9a
  sweep deferred.

### Deviations from prompt

- **Open trigger is the `/religion temple` debug command**, not a block/NPC
  interaction — the prompt explicitly allowed this ("reuse an existing
  interaction or a debug command — testability is the goal"); no clean
  religious-building-aware interaction exists, and the BusinessFront "Request
  Blessing" route opens the priest's business-front, not a building view.
- **Added three read-only methods to `TempleProsperity`** (`dailyCost`,
  `solvencyBuffer`, `healthLabel`) instead of a screen-only health-state enum.
  This keeps the R4c thresholds single-sourced (the prompt preferred deriving
  over a new enum); the label is a `String` on the wire.
- **Consecration is matched by rite-location proximity** (≤12 blocks of the
  building origin) since `RiteExecution` carries a `BlockPos location` but no
  building id; robust for the common one-temple village, with a small
  ambiguity risk only when two religious buildings sit within 12 blocks
  (flagged).
- **Refactored `PriestBehavior.clergyTitle` + `NpcProfileSnapshotBuilder`** to
  the shared helper — slightly beyond "just the screen", but it's the sweep
  action the prompt called for ("a good moment to factor the shared
  `ClergyTitles` helper").

### Out-of-scope but flagged

- **Actions** (consecrate / seed treasury / assign priest / order candles) —
  the prompt scoped this read-only; the layout leaves a bottom row free for a
  later action pass.
- **A live sync** (the BusinessFront pattern is one-shot; the numbers are a
  snapshot at open — re-run `/religion temple` to refresh). A 5s
  `TempleSyncPacket` (like the NPC profile) is a clean follow-up if live
  tracking is wanted.
- **A real interaction trigger** (right-click the temple block / its priest to
  open) once a religious-building interaction hook exists.
- **Consecration building-id precision** — if `RiteExecution` ever gains a
  building-id field, swap the proximity match for an exact one.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done: all
accessor signatures confirmed by grep (`countItem(ServerLevel,Building,Item)`,
`Items.WHITE_CANDLE`, `RiteSavedData.get`/`ritesForVillage`,
`RiteOutcome.SUCCESSFUL`, `Rite.CONSECRATION`, `Religion.calendar()`/`deity()`,
`ReligiousCalendar.DAYS_PER_YEAR`/`effectiveDayOfYear`/`holyDaysByName`,
`getOrCreateBuildingEconomy`, `BuildingCondition.needsRepair`,
`getBounds(VillageSavedData)`, `PacketDistributor.sendToPlayer`); packet
encode/decode field order is consistent (21 fields + list, same order);
`TempleScreen` uses only public `Gui.Framework` primitives; `ClergyTitles`
preserves the original three-arm mapping; the new `TempleProsperity` views read
(never mutate) its private R4c thresholds; the packet is registered and the
command is wired into `/religion`.

### Smoke test (user-runnable)

1. **Flourishing temple.** Stand in a well-funded village's temple and run
   `/religion temple`. Confirm the screen shows the patron faith (+ deity), a
   green **Flourishing** (or **Solvent**) pill, a **Consecrated** pill,
   Treasury well above 0, Daily cost (14b/day), a positive "+Nb above buffer"
   surplus, Condition Maintained/Weathered (no "decaying"), a non-zero candle
   count, the seated priest's name + order + rank title, a congregation count >
   0 with an aggregate-piety bar, and the next holy day(s).
2. **Starved temple.** Drain it (let R4a/R4c run insolvent, or remove its
   income) and re-run `/religion temple`. Confirm the pill turns amber
   **At-risk** then red **Decaying**, "Surplus" becomes "N days insolvent",
   Condition shows Dilapidated "(decaying)", and candles read "0 — unlit
   rites". After the abandon window, confirm **Abandoned** + Clergy "Vacant".
3. **Minority shrine (R3e-2).** Stand by a shrine of a non-dominant faith and
   run the command. Confirm its faith / deity / clergy / economy are its OWN
   (not the village-dominant temple's) — the nearest-building scan picks the
   shrine you're next to.
4. **Vacant / RUINED / non-religious.** Run `/religion temple` near a
   vacant-but-standing religious building (Clergy "Vacant", congregation may be
   0, no crash); near a RUINED one (Abandoned, graceful); and in a village with
   NO religious building (a clean "No temple/chapel/shrine in <village>"
   failure, no screen).
5. **Numbers track R4.** Seed the temple treasury (or attend rites to raise
   piety), wait a day, re-run the command, and confirm Treasury / surplus /
   health / congregation move with the underlying R4/R4c systems.

---

## R9c — player piety + religious calendar view (2026-06-08)

### Disposition (findings)

The prompt asks for a **read-only player-facing screen** with two sections —
the player's own religious standing, and an upcoming religious-calendar list —
opened via a `/religion me`-style command (testable). Investigation on-branch:

- **Player piety + pledge.** `RiteSavedData` holds the player-side state:
  `getPlayerPiety(UUID)` → `Optional<PietyComponent>` (faith / strength / tier /
  beliefs / `ritesAttendedThisMonth` / `meetsMonthlyAttendance`, same shape R9a
  reads for NPCs), and the R4d-1 auto-tithe pledge map via `isAutoTithe(UUID)` +
  `autoTitheTemples().get(UUID)` → the temple building id. The pledge temple's
  faith resolves through `BuildingFaith.resolveFaith(level, village, building)`
  (finding the owning village by scanning `getAllVillages()` for the building
  id), falling back to `Building.getPatronFaith()`.
- **Calendar axis.** Every faith's holy days, signature-rite day, and
  grand-festival day are **named entries** in its `ReligiousCalendar`
  (`holyDaysByName`); the schedulers (`VillageEventScheduler.checkSignatureRite`
  / `checkGrandFestival` / `checkCalendarVigil`, and the per-faith signature
  day names "Spring Equinox" / "First Threading" / "First Catch" / "Ancestor
  Day") all resolve their fire-day via
  `religion.calendar().effectiveDayOfYear(name)` on the `% 365` liturgical axis
  (`(gameTime/24000) % DAYS_PER_YEAR`) — **NOT** `SeasonTracker`'s 96-day
  seasonal axis. So iterating the named calendar days per faith captures every
  calendar event, exactly as the existing `/religion calendar <village>` command
  already does.
- **Shared computation.** Factored `Npc/Religion/CalendarView` as the single
  source of the `% 365` day math (`dayOfYear`, `upcomingFor(religion)`,
  `upcomingAcross(religions, max)` — soonest-first), and routed `/religion
  calendar` AND the R9b temple builder's `upcomingHolyDays` through it (the R9b
  copy is now deleted), so the day math lives in exactly one place.
- **Screen.** `Chrome.COMPACT` (320×240), no sidebar: a fixed top piety block
  (faith line, piety `NeedMeter.bar` + tier `Pill`, "Piety NN%", a one-line
  syncretic belief summary, tithe-pledge line, observance line) over a
  `ScrollList<CalendarRow>` calendar (today highlighted, own-faith starred), and
  a Close button. Mirrors the `TempleScreen` render order (dim → Chrome →
  content → `super.render`).

### What shipped

- **`Npc/Religion/CalendarView.java`** (new) — the shared `% 365` calendar
  helper (`Entry` record + `dayOfYear` + `upcomingFor` + `upcomingAcross`).
- **`Networking/OpenPlayerReligionPacket.java`** (new) — the data-carrying Open
  packet: faith (name/deity/strength/tier/syncretic beliefs), tithe pledge
  (has/temple/faith), observance (rites this month + observant flag), the
  current day-of-year, and a `List<CalendarRow>` (faith / day label / day-of-year
  / days-away / own-faith). Manual `StreamCodec.of`; `handle` opens the screen.
- **`Npc/Religion/PlayerReligionSnapshotBuilder.java`** (new) — pure-read server
  gatherer; reads the player `PietyComponent` + pledge from `RiteSavedData`, the
  pledge temple's faith, and the merged upcoming calendar across all faiths via
  `CalendarView`. Graceful for an unaffiliated player (empty faith, "Make
  offerings…" hint on the screen).
- **`Gui/PlayerReligionScreen.java`** (new) — the read-only screen + scrolling
  calendar, `Gui.Framework` primitives only.
- **Refactors:** `/religion calendar` (`ReligionDebugCommand.handleCalendar`)
  and `TempleSnapshotBuilder.upcomingHolyDays` now call `CalendarView` (deleted
  the duplicated day math + the now-unused `DAY` field / `ReligiousCalendar`,
  `Map` imports).
- **Wiring:** registered `OpenPlayerReligionPacket` (`playToClient`) in
  `ModModEvents`; added the `/religion me` subcommand.

### Tie-In Audit

1. **Upstream feeders.** `RiteSavedData` (player piety + pledge map),
   `ReligiousCalendar` via the new `CalendarView` (day logic), `ReligionRegistry`
   (`all()` + `find`), `BuildingFaith.resolveFaith` (pledge temple faith). All
   read-only; the builder mutates nothing — notably it uses `getPlayerPiety`
   (Optional) NOT `getOrCreatePlayerPiety`, so merely viewing never creates a
   piety record for an unaffiliated player.
2. **Downstream callers.** New: `OpenPlayerReligionPacket.handle` (client
   setScreen), `PlayerReligionScreen` (renderer), `/religion me` (server send),
   the `ModModEvents` registration. `CalendarView` has three inbound callers
   (`/religion calendar`, `TempleSnapshotBuilder`, `PlayerReligionSnapshotBuilder`)
   — all verified to pass a `Religion` + `gameTime` and consume the sorted
   `Entry` list.
3. **Sibling systems.** `/religion calendar`'s output is now soonest-first
   (previously insertion order) since it shares `CalendarView` — same dates,
   same "(N days away)" text, improved ordering (flagged). R9a/R9b piety/faith
   presentation reused verbatim (primary tier `displayName`, syncretic
   `beliefs.size() > 1` rule). The R4d-1 pledge map is read through its existing
   accessors; no tithe behavior touched.
4. **Exhaustive switches.** No new enum; no `switch` added over any enum (the
   only branching is `Optional`/`if`). Confirmed.

### Simplification Sweep

- **GUI/religion classes in scope + inbound callers:** `CalendarView` (3 —
  command, temple builder, player builder), `OpenPlayerReligionPacket` (2 —
  register + `/religion me`), `PlayerReligionSnapshotBuilder` (1 — the command),
  `PlayerReligionScreen` (1 — the packet handler). No orphans created.
- **Duplication retired:** the `% 365` upcoming-day computation existed inline in
  both `/religion calendar` and R9b's `TempleSnapshotBuilder.upcomingHolyDays`;
  both now delegate to the single `CalendarView` — exactly the consolidation the
  prompt's sweep called for. (This continues R9b's `ClergyTitles` precedent of
  retiring a duplication as part of the same change.)
- The piety block reuses the R9a presentation shape (bar + tier pill + "Piety
  NN%" + syncretic summary) rather than a parallel one.

### Deviations from prompt

- **Open trigger is `/religion me`** (the prompt's suggested form); no item/key
  binding exists, and a command is the testable path the prompt endorsed.
- **The calendar lists ALL four faiths' named days** (capped at 24, scrollable),
  with the player's own-faith entries starred + today highlighted — the prompt
  allowed "showing the four faiths' headline days is fine for testing" and the
  player has no single village context when standing anywhere. Scoping to the
  local village's faith(s) is a flagged refinement.
- **`/religion calendar` ordering changed** from calendar-map insertion order to
  soonest-first (a side effect of sharing `CalendarView`). Same data + text;
  arguably an improvement — flagged rather than special-cased.
- **Calendar "kind" (holy day vs signature rite vs grand festival) is not
  labelled** — all named days render uniformly with their countdown. The kinds
  share the same calendar source and the same `effectiveDayOfYear` fire-day, so
  distinguishing them would require re-deriving the scheduler's per-faith
  signature/grand-festival name matching; deferred (flagged) to keep the helper
  a thin single-source.

### Out-of-scope but flagged

- **Participation verbs** (make offering / pledge tithe / cancel pledge) — the
  prompt scoped this read-only and assigns verbs to R9d; the layout leaves the
  Close-button row free for a later action pass.
- **A live sync** (one-shot snapshot at open; re-run `/religion me` to refresh) —
  a 5s sync packet is a clean follow-up if live piety-growth tracking is wanted.
- **Per-village calendar scoping** + **labelling each entry's kind** (holy day /
  signature / grand festival) as described above.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
Static review done: accessor signatures confirmed by grep (`getPlayerPiety`,
`isAutoTithe`/`autoTitheTemples`, `PietyComponent` accessors, `ReligionRegistry.
all()`/`find`, `BuildingFaith.resolveFaith`, `Building.getPatronFaith`/`getName`,
`getAllVillages`/`getBuildingById`, `ReligiousCalendar.effectiveDayOfYear`/
`holyDaysByName`/`DAYS_PER_YEAR`); the packet encode/decode field order is
consistent (10 scalar/list fields + the nested `CalendarRow` 5-field rows);
`PlayerReligionScreen` uses only public `Gui.Framework` primitives and the
correct NeoForge 1.21 event signatures (`mouseScrolled(d,d,d,d)` /
`mouseClicked(MouseButtonEvent,boolean)`, delegating to `ScrollList`'s
`mouseScrolled(d,d,d)` / `mouseClicked(d,d,int)`); `CalendarView` is the sole
`% 365` computation and its three callers consume it; the packet is registered
and `/religion me` is wired.

### Smoke test (user-runnable)

1. **Affiliated player grows.** Make offerings / attend rites to raise your
   piety, then run `/religion me`. Confirm the screen shows your primary faith
   (+ deity), a filled piety bar + tier pill (UNAFFILIATED→FAITHFUL→DEVOUT→
   PIOUS), "Piety NN%", and that re-running after more offerings shows the bar +
   tier climbing.
2. **Tithe pledge (R4d-1).** Opt into auto-tithing a temple, re-run `/religion
   me`, and confirm "Tithing to <temple> (<faith>)"; opt out and confirm "No
   tithe pledge".
3. **Observance.** Attend rites and confirm "N rite(s) this month — observant"
   appears once you cross the monthly threshold.
4. **Calendar countdowns on the 365 axis.** Confirm the calendar lists upcoming
   named days per faith with "in N days" / "today", soonest-first, your own
   faith starred and today's row highlighted. Cross-check a couple against
   `/religion calendar <village>` (same dates) and against when a festival
   actually fires (e.g. wait until a "today" entry and confirm that faith's
   event/rite triggers that day). Confirm the header day-of-year advances by 1
   per in-game day and wraps at 365.
5. **Graceful unaffiliated.** As a brand-new player with no piety, run `/religion
   me`: confirm "Unaffiliated" + the offerings hint, no pledge, no crash, and
   the calendar still lists the faiths' days (nothing starred).

---

## R9d — player participation verbs + piety payoff (2026-06-08)

### Disposition (findings)

The final R9 phase adds player **actions** (the two remaining designed verbs)
and a **piety payoff** so accumulated piety matters. On-branch verification:

- **Verb system.** `PlayerVerb` (id / `label():Component` / `isAvailable(ctx)` /
  `invoke(ctx)` + args overload / `displayOrder` / `cooldownTicks`); `VerbContext`
  (player, npc, level, tick); `VerbResult` (`success(treeId)` /
  `failure(reason)` / `opensScreen`); `VerbInvocation.invoke` runs availability →
  cooldown → `invoke` → routes a dialogue tree / failure text to chat;
  `PlayerVerbRegistry.register(...)` in `registerDefaults()` +
  `availableFor(ctx)` (sorted by `displayOrder`). The shipped religious verbs
  (`make_offering` / `confess` / `request_blessing` / `pay_tithe`) are the exact
  mirrors — `MakeOfferingVerb` shows the player-piety + relationship + temple-
  economy bookkeeping; `CommissionVerb` shows the work-time gate;
  `PayTitheVerb`/`MakeOfferingVerb` show priest→building→`BuildingEconomy`.
- **UI (no fork).** `NpcInteractionHandler` builds the priest's
  `BusinessFrontScreen` verb grid from `PlayerVerbRegistry.availableFor(ctx)`
  (capped 8, by display order). So a verb that returns `isAvailable == true` for
  a priest context **auto-appears** at the priest — no button wiring, no new
  packet. (The R9b temple action row stays read-only; wiring a button there
  needs the staffing priest resolved client→server — flagged.)
- **Rite scheduling + R1a gate.** `RiteScheduler.schedule(level, village, rite,
  participantIds, delayTicks)` queues a `RiteExecution` (the priest officiates
  via the normal pipeline). `RiteCapability.canOfficiate(priest, rite)` =
  `RiteTier.tierOf(rite).ordinal() <= capOf(priest).ordinal()` (seated village
  priest → GRAND; else SOCIAL/LITERACY-capped) — the R1a gate.
- **Active-gathering detection.** `VillageSavedData.getActiveEventsForVillage(id)`
  (live `VillageEvent`s, faith in `eventData[CeremonyBlessings.FAITH_KEY]`) +
  `RiteSavedData.ritesForVillage(id)` filtered by `outcome == PENDING &&
  isActiveAt(now)` (the `CommunityGathering` 1200-tick window).
- **Payoff site.** `TownspersonMob.getRelationships().adjust(playerId, n)` is the
  player↔NPC channel `make_offering`/`give_gift` already use;
  `PietyComponent.primaryTier()` → `PietyTier` (UNAFFILIATED/FAITHFUL/DEVOUT/
  PIOUS, ordinal 0–3). `FaithReconciliation.faithBenefit(playerFaith, riteFaith)`
  → `FaithBenefit(moodMultiplier, sameFaith)` for the cross-faith reduction.
- **Fee.** `CoinHelper.playerCanAfford` / `playerPay(player, CurrencyValue.of(b))`
  debits the purse; `BuildingEconomy.depositRevenue(b)` credits the temple.

**Payoff design (chosen).** The prompt's first option — *co-religionist NPCs
regard a higher-piety player more warmly* — implemented as a bounded,
faith-aware, tier-scaled relationship regard in a shared `PietyPayoff` helper
(FAITHFUL +2 / DEVOUT +4 / PIOUS +6, capped to 6 nearby same-faith NPCs per
act), applied by the two new verbs as the public act of devotion that earns it.
Reuses the relationship channel; no new mechanism. (The blessing-strength option
is flagged as an alternative not taken — it would require threading a per-player
scalar through the `RiteProfile` execution path.)

### What shipped

- **`Npc/Religion/PietyPayoff.java`** (new) — `regardBonus(PietyTier)` (bounded
  `ordinal * 2`) + `applyCoReligionistRegard(...)` (warms ≤6 loaded same-faith
  villagers by the tier regard; no-op for unaffiliated / zero tier).
- **`Npc/Verbs/Impl/AttendRiteVerb.java`** (new, id `attend_rite`) — available
  only while a rite/festival is active in the npc's village; deepens the player's
  piety (faith-aware via `FaithReconciliation` — full for the rite's faith,
  reduced/syncretic drift otherwise), records attendance, and applies the
  co-religionist payoff. Action-bar feedback; mirrors `MakeOfferingVerb`'s
  player-piety bookkeeping.
- **`Npc/Verbs/Impl/CommissionRiteVerb.java`** (new, id `commission_rite`) — at a
  working priest with a temple: runs the R1a `canOfficiate` gate, checks
  affordability, debits a 25b fee → the temple `BuildingEconomy` (R4a), schedules
  `Rite.BLESSING` for the player (priest officiates via the pipeline), nudges the
  player's piety, and pays the priest-regard. Mirrors `CommissionVerb` +
  `MakeOfferingVerb`.
- **`Npc/Verbs/PlayerVerbRegistry.java`** — registered both verbs after
  `PayTitheVerb`; they auto-surface in the priest's BusinessFront verb grid.

### Tie-In Audit

1. **Upstream feeders.** `PlayerVerbRegistry`/`availableFor` (UI surfacing),
   `RiteScheduler.schedule` (commission), `getActiveEventsForVillage` +
   `ritesForVillage`/`isActiveAt` (attend gating), `RiteSavedData` player piety,
   `CoinHelper`/`CurrencyValue` (fee), `RiteCapability` (gate),
   `FaithReconciliation` (cross-faith), `BuildingFaith`/`BuildingEconomy` (temple
   fee). All consumed read-or-through-existing-mutators; no system forked.
2. **Downstream callers.** `PlayerVerbInvokePacket.handle` →
   `VerbInvocation.invoke` dispatches both new verbs unchanged (no packet/codec
   change — the verb list rides the existing `OpenBusinessFrontPacket.verbIds`,
   which is capped at 8; the two verbs' low displayOrder (64/66) keeps them in
   range). `RiteScheduler`/`RiteSavedData` receive a normal commissioned
   `RiteExecution`. `BuildingEconomy` receives the fee like `make_offering`'s
   deposit. `getRelationships().adjust` receives bounded payoff bumps.
3. **Sibling systems.** Fee/piety/relationship handling matches the shipped
   `make_offering`/`pay_tithe` shape (same `getOrCreatePlayerPiety` +
   `adjustBelief`/`recordRiteAttendance` + `depositRevenue` + `relationships.
   adjust`). The R1a gate is the same `canOfficiate` the priest brain uses.
   Cross-faith attend reuses `FaithReconciliation.faithBenefit`. `PietyPayoff` is
   a new shared helper (2 callers now; adoptable by the shipped verbs later).
4. **Exhaustive switches.** No new enum; no `Rite`/`PietyTier` constant added, so
   no exhaustive `switch` (e.g. `RiteTier.tierOf`, `PietyTier.displayName`)
   needs an arm. The payoff uses `PietyTier.ordinal()`, not a switch. Confirmed.

### Simplification Sweep

- **Verb + GUI + religion classes in scope + inbound callers:** `AttendRiteVerb`
  (1 — the registry), `CommissionRiteVerb` (1 — the registry), `PietyPayoff` (2
  — the two verbs). No orphans. Both verbs reuse the `PlayerVerb` pattern +
  `CommissionVerb`/`MakeOfferingVerb` fee/piety/scheduling idioms; the payoff
  reuses `getRelationships().adjust` + `FaithReconciliation`; the buttons reuse
  the existing `availableFor`-driven BusinessFront verb grid — no parallel verb
  or button framework introduced. (Continues the R9b `ClergyTitles` / R9c
  `CalendarView` precedent of a single shared religion helper per concern —
  `PietyPayoff` is the payoff equivalent.)

### Deviations from prompt

- **Commissioned rite is fixed to `Rite.BLESSING`** (the prompt's first named
  example, always applicable to the player). The R1a `canOfficiate` gate is
  genuinely called and respected, though BLESSING is MINOR so any priest passes;
  **player-selectable grander rites** (NAMING/MARRIAGE/FUNERAL — which need a
  subject and would gate harder) are flagged as a follow-up, since the verb UI
  sends no args and those rites need a target picker.
- **Payoff = co-religionist regard only** (option 1), not blessing-strength
  scaling (option 2) — chosen for boundedness and reuse; the blessing-scaling
  alternative is flagged.
- **UI is the priest BusinessFront verb grid** (auto-populated), not a new
  button on the R9b temple screen — the natural, no-fork home; the temple-row
  button is flagged.
- **`AttendRite` feedback + `CommissionRite` feedback use an action-bar message**
  (`displayClientMessage`) rather than a dialogue tree, since neither maps to an
  existing priest dialogue tree cleanly; success returns `VerbResult.success("")`.

### Out-of-scope but flagged

- **Join-an-order (player order membership)** — OUT per the prompt. It needs a
  *player* order-membership model (the NPC `ClergyOrders` system is NPC-assigned
  only; there is no player-side order store, no membership persistence, no rank
  ladder for players). Flagged as the prerequisite for any "join an order" /
  player religious-office work.
- Player-selectable commissioned rite tiers (NAMING/MARRIAGE/FUNERAL + a subject
  picker); the blessing-strength piety payoff; a `CommissionRite` button on the
  R9b temple action row; `PietyPayoff` adoption by the shipped `make_offering`/
  `request_blessing` verbs for consistency.

### R9 completion

This completes the brought-forward **R9** player layer: R9a (NPC religion
panel), R9b (temple screen), R9c (player piety + calendar), R9d (participation
verbs + payoff). The player can now see NPC / temple / self+calendar religious
state and act on it (offer / tithe / confess / request-blessing / attend /
commission), with piety tier producing a tangible co-religionist regard.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
Static review done: all signatures confirmed by grep against source
(`PlayerVerb`/`VerbContext`/`VerbResult`, `PlayerVerbRegistry.register`,
`RiteScheduler.schedule`, `RiteCapability.canOfficiate`,
`PietyComponent.adjustBelief`/`setBelief`/`recordRiteAttendance`/`primaryTier`,
`NpcRelationshipComponent.adjust`, `CoinHelper.playerCanAfford`/`playerPay`,
`CurrencyValue.of`, `BuildingEconomy.depositRevenue`,
`getActiveEventsForVillage`, `RiteExecution.isActiveAt`/`outcome`,
`FaithReconciliation.faithBenefit`+`FaithBenefit`, `BuildingFaith.
isReligiousBuilding`/`resolveFaith`, `ReligionContent.villageReligionId`); both
verbs mirror `MakeOfferingVerb`/`CommissionVerb` exactly; no packet/codec change
(verbs ride the existing `availableFor`→BusinessFront path); no new enum or
exhaustive-switch arm.

### Smoke test (user-runnable)

1. **Attend during a festival.** While a rite/festival is active in a village
   (e.g. trigger a signature rite via the calendar, or `/religion` a gathering),
   right-click the temple priest → the BusinessFront grid shows **Attend rite**.
   Use it: confirm an action-bar acknowledgement, your piety grows (cross-check
   `/religion me`), and attendance increments. Confirm it is **unavailable** when
   no rite is under way ("no rite to attend").
2. **Cross-faith attend.** Attend a rite of a faith that is NOT your primary:
   confirm the gain is reduced (syncretic drift toward that faith), not a full
   own-faith deepening.
3. **Commission a rite.** At a working temple priest, use **Commission rite**
   with ≥25b in purse: confirm 25b leaves your purse, the temple treasury rises
   by 25 (cross-check `/religion temple`), a BLESSING rite schedules and the
   priest officiates it shortly. With <25b, confirm the "can't afford" failure
   and no charge. Confirm a priest who cannot officiate is refused by the gate.
4. **Piety payoff.** Raise your piety tier (repeated offerings/attendance to
   DEVOUT/PIOUS), then attend a rite / commission with co-religionist villagers
   nearby: confirm same-faith NPCs' regard toward you climbs more at higher tiers
   (re-open their NPC profile → Reputation/personal delta), and that NON-same-
   faith NPCs are unaffected (faith-aware), and the bump is bounded.
5. **UI consistency.** Confirm Attend/Commission sit alongside the shipped
   make-offering/confess/request-blessing/tithe verbs in the priest grid and
   respect cooldowns like the others.
---

## M1 — skill-keyed recipe registry (2026-06-08)

(Production Architecture, not Religion — logged here as the active branch's
running ledger.)

### Disposition (findings)

Foundation for the skills-first production model. Today production is
**profession-owned**: recipes are static constants/built-lists ON the seven
profession behavior classes, with no skill-keyed registry; the 4 homestead
behaviors borrow the profession constants. M1 performs the first half of the
inversion — **move recipe definitions into a skill-keyed registry and source
every existing producer from it, with ZERO behavioral change.**

**Full recipe → owning-skill inventory** (verbatim values preserved):
- **BAKING / PASTRY** (`BakerProductionBehavior`, `RECIPE_PRIORITY` = cake, pie,
  cookie, flour→bread, wheat→bread): `MAKE_CAKE` (PASTRY 40), `MAKE_PUMPKIN_PIE`
  (PASTRY 15) → PASTRY; `MAKE_COOKIE` (BAKING 30), `FLOUR_TO_BREAD`,
  `WHEAT_TO_BREAD` (no gate) → BAKING.
- **MILLING** (`MillerProductionBehavior`, 3 named constants checked directly in
  `chooseRecipe`): `GRIND_WHEAT`, `GRIND_BONES`, `PROCESS_SUGAR_CANE`.
- **CANDLEMAKING** (`CandlemakerProductionBehavior`, 3 named constants):
  `MAKE_CANDLE`, `MAKE_TORCH`, `MAKE_LANTERN` (CANDLEMAKING 50).
- **WEAVING** (`WeaverProductionBehavior`, `buildRecipes()` list): `SPIN_STRING` +
  16 carpets (tiered 0/15/30/40) + `WHITE_BANNER` (WEAVING 50).
- **CARPENTRY** (`CarpenterProductionBehavior`, `buildLogRecipes()` ++
  `buildPlankRecipes()`): 8 log→plank + slabs/stairs/doors/fences/gates/
  chest/barrel/table/bookshelf + `CHISELED_BOOKSHELF` (CARPENTRY 50).
- **MASONRY** (`StonemasonProductionBehavior`, `buildRecipes()` ordered list): 12
  stone/cobble/polished recipes.
- **BLACKSMITHING + children** (`BlacksmithProductionBehavior`): JSON-loaded
  smelting/crafting via `BlacksmithRecipeRegistry` (runtime data feeder) +
  3 inline masterpieces `MAKE_DIAMOND_PICKAXE` (TOOLSMITHING 50),
  `MAKE_DIAMOND_SWORD` (WEAPONSMITHING 50), `MAKE_NETHERITE_INGOT`
  (BLACKSMITHING 65).

**Cross-class references** (must stay compiling): exactly 5 `public static`
constants — `BakerProductionBehavior.{FLOUR_TO_BREAD,WHEAT_TO_BREAD}`,
`MillerProductionBehavior.GRIND_WHEAT`, `WeaverProductionBehavior.SPIN_STRING`,
`CandlemakerProductionBehavior.MAKE_TORCH` — used by the 4 homestead behaviors.
`AbstractProductionBehavior.chooseRecipe` is abstract; each subclass's selection
logic (`RECIPE_PRIORITY` walk / `productionTarget` + `findRecipeForOutput` +
`findBestAvailable` / blacksmith smelt-vs-craft) and `meetsSkillRequirements`
stay untouched — only the recipe SOURCE moves. `ProfessionSupplyChain` is item
buy/sell lists (a separate trade concern), left untouched.

**How behavior is proven unchanged:**
1. Every recipe object's defining expression (`ProductionRecipe.of(...)` +
   `.withSkillRequirement(...)`) was **copied verbatim** into `SkillRecipes` —
   identical inputs/output/count/ticks/byproducts/gates (diff-checkable).
2. The four **named-constant** professions (Baker/Miller/Candlemaker/Blacksmith)
   keep their constants as thin aliases (`= SkillRecipes.X`) → identical object
   references; their `chooseRecipe` / `RECIPE_PRIORITY` / `INLINE_*` lists are
   byte-unchanged.
3. The three **built-list** professions (Weaver/Carpenter/Stonemason) source
   `RECIPES = SkillRecipes.forSkill(SKILL)`, where the registry's per-skill
   bucket is the **verbatim-moved** `buildRecipes()` output — crucially keeping
   the original `Map.of(...).forEach(...)` constructions so the
   implementation-defined iteration order (which `findBestAvailable` tie-breaks
   on, first-encountered-wins) is identical. Carpentry's bucket is
   `buildLogRecipes()` ++ `buildPlankRecipes()`, matching the former
   `allRecipes()` order.
4. `meetsSkillRequirements`, the whole production phase machine, output routing,
   `ProfessionSupplyChain`, and the blacksmith JSON feeder are untouched.

### What shipped

- **`Village/Economy/Resources/SkillRecipes.java`** (new) — the skill-keyed
  registry: named `public static final ProductionRecipe` constants for every
  individually-referenced recipe + verbatim-moved `weave/craft/mason` helpers and
  `buildWeaving/Carpentry/Masonry` builders, all registered into an
  `EnumMap<Skill, List<ProductionRecipe>>` exposed via `forSkill(Skill)` (in
  profession-iteration order). Named constants are declared before the registry
  field so static-init order is safe.
- **7 profession behaviors** re-sourced: Baker/Miller/Candlemaker/Blacksmith keep
  their constants as `= SkillRecipes.X` aliases (selection logic untouched);
  Weaver/Carpenter/Stonemason replace their `buildRecipes()`/`craft`/`mason`/
  `weave` helpers with `SkillRecipes.forSkill(SKILL)` (Carpenter's `allRecipes()`
  now returns the single `RECIPES` bucket; `ALL_OUTPUTS` derived from it; unused
  `CRAFT_TICKS` removed).
- **4 homestead behaviors** now read `SkillRecipes.{WHEAT_TO_BREAD,GRIND_WHEAT,
  MAKE_TORCH,SPIN_STRING}` directly (javadoc `{@link Xxx#CONST}` links retained —
  the public aliases still exist).

### Tie-In Audit

1. **Upstream feeders.** All static recipe definitions now originate in
   `SkillRecipes`. `BlacksmithRecipeRegistry` (JSON smelting/crafting) **feeds**
   the blacksmith directly as before — it does NOT fold into `SkillRecipes` this
   phase (data-driven runtime load; only the inline masterpieces migrated).
   Flagged as a future fold.
2. **Downstream callers.** Every `*ProductionBehavior.chooseRecipe` now reads its
   candidates from the aliases / `forSkill` (same objects, same order). The 4
   homestead behaviors read `SkillRecipes` directly. The 5 public alias constants
   still exist, so any external referencer keeps compiling.
   `AbstractProductionBehavior.meetsSkillRequirements` is unchanged.
3. **Sibling systems.** `ProfessionSupplyChain` untouched (separate buy/sell
   concern — confirmed no recipe coupling). Market/sell routing
   (`executeSellForWorkshop`, `computeSurplusToSell`) and skill-XP routing
   (`awardProductionXp`) untouched.
4. **Exhaustive switches.** No `Skill` enum value added; no exhaustive switch over
   `Skill` touched. Confirmed.

### Simplification Sweep

Recipe definitions now live in ONE place (`SkillRecipes`); the 7 profession + 4
homestead behaviors share them; the per-class duplicate copies are retired
(replaced by aliases / `forSkill`). No divergent recipe copy remains (grep
confirms zero remaining `ProductionRecipe.of(` / `.withSkillRequirement(` in code
across the production behaviors — only comments). Classes in scope + inbound
callers: `SkillRecipes` (new, 11 inbound: 7 production + 4 homestead);
`Baker/Miller/Candlemaker/Weaver/Carpenter/Stonemason/Blacksmith
ProductionBehavior` (each its own brain-registered behavior, unchanged callers);
`Home{Baking,Milling,Candlemaking,Weaving}Behavior` (each homestead-registered).
Removed dead helpers: Weaver `weave`/`buildRecipes`, Carpenter `craft`×2/
`buildLogRecipes`/`buildPlankRecipes`/`LOG_RECIPES`/`PLANK_RECIPES`/`CRAFT_TICKS`,
Stonemason `mason`/`buildRecipes`.

### Deviations from prompt

- **`BlacksmithRecipeRegistry` JSON recipes stay a separate runtime feeder**, not
  folded into `SkillRecipes` — they are data-driven (loaded from JSON at
  runtime), and folding them in would change the blacksmith's sourcing/ordering
  and risk behavior drift. Only the 3 inline masterpieces migrated. The fold is
  flagged for a future data-load-time registration into `SkillRecipes`.
- **The named-constant professions keep `chooseRecipe` byte-identical** (their
  constants became aliases) rather than rewriting them to iterate
  `forSkill(skill)`. This is the strictly behavior-preserving form (the prompt's
  hard constraint); the registry IS the source (the aliases resolve to it), and
  `forSkill` is exercised by the three built-list professions + is ready for M2 /
  the monk. Rewriting the named-constant selectors to `forSkill` is a no-op
  refactor deferrable to M2 if desired.

### Out-of-scope but flagged

- **M2** — the generalized `HomeProductionBehavior` primitive on top of
  `SkillRecipes` (the 4 hand-written homestead behaviors collapse into it).
- **R6** — the monk rides `SkillRecipes` once M2 lands.
- **`ProfessionSupplyChain` derive-from-skills** — deriving the profession→item
  buy/sell lists from the skill buckets (left as-is this phase).
- **Folding the blacksmith JSON registry into `SkillRecipes`** at load time.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
Static review done: brace balance verified on the three large-deletion files;
grep confirms no leftover `buildRecipes`/`weave`/`craft`/`mason`/`LOG_RECIPES`/
`PLANK_RECIPES`/`CRAFT_TICKS` code refs and zero remaining inline
`ProductionRecipe.of(`/`.withSkillRequirement(` in the production behaviors;
`SkillRecipes` is referenced by all 7 production + 4 homestead classes; the 5
public alias constants are retained; recipe expressions copied verbatim;
`Map.of(...).forEach` constructions moved verbatim to preserve iteration order;
named-constant `chooseRecipe` bodies + `RECIPE_PRIORITY` + blacksmith `INLINE_*`
lists unchanged; static-init order safe (constants before the `BY_SKILL` field).

### Smoke test (user-runnable)

1. **Staples unchanged.** Run a village with a baker, miller, candlemaker,
   weaver, blacksmith, carpenter, stonemason. Confirm each still produces the
   SAME goods at the SAME cadence as before: bread (and flour→bread vs
   wheat→bread fallback), flour/bone_meal/sugar, candle/torch, carpets/spun wool,
   ingots/tools, planks/slabs/stairs/etc., bricks/slabs/walls.
2. **Skill gates unchanged.** Confirm gated recipes still gate identically: a
   low-BAKING baker makes bread but not cookie (BAKING 30) / pie (PASTRY 15) /
   cake (PASTRY 40); a low-skill blacksmith can't make diamond tools
   (TOOLSMITHING/WEAPONSMITHING 50) or netherite (BLACKSMITHING 65); carpenter
   doors/fences gate at CARPENTRY 15, masterpieces at 50; mason chiseled bricks at
   MASONRY 50; weaver carpet tiers at 15/30/40, banner at 50.
3. **Selection order unchanged.** Confirm the baker still prefers cake→pie→cookie→
   bread when skilled+stocked, and that weaver/carpenter/stonemason still pick the
   same below-quota item under the same stock (the tie-break order is preserved).
4. **Homesteads unchanged.** Confirm homestead bakers/millers/weavers/chandlers
   still produce bread/flour/spun-wool/torches for the family at the same rate.
5. **Destinations unchanged.** Profession output still routes to building/market
   (miller→stockpile), homestead output still to the household. Nothing observable
   changed — only the code is reorganized.

---

## M2 — general home-production primitive (2026-06-08)

(Production Architecture, not Religion — logged here as the active branch's
running ledger.)

### Disposition (findings)

Second half of the skills-first inversion. M1 moved recipes into `SkillRecipes`;
M2 collapses the **four hand-written homestead behaviors**
(`Home{Baking,Milling,Weaving,Candlemaking}Behavior`) into **one
`HomeProductionBehavior` + a `skill → HomeCraft` table** that reproduces the four
crafts identically. After this, a new home craft is a table row, not a behavior
class — which makes R6's monastic crafts cheap.

**Per-craft parameter table (extracted verbatim — proves the table captures all
four exactly):**

| craft | skill (≥1) | amenities (pref order) | recipe (SkillRecipes) | need good = `recipe.output()` | per-member | econ < | hobby id | XP |
|---|---|---|---|---|---|---|---|---|
| Baking | BAKING | SMOKER, FURNACE | WHEAT_TO_BREAD (3 wheat→1 bread, 200t) | BREAD | 4 | 150 | `home_cooking` | 2 |
| Milling | MILLING | GRINDSTONE | GRIND_WHEAT (2 wheat→3 flour, 80t) | WHEAT_FLOUR | 4 | 150 | *(none → bare LEISURE)* | 2 |
| Weaving | WEAVING | LOOM | SPIN_STRING (4 string→1 wool, 60t) | WHITE_WOOL | 2 | 150 | `home_weaving` | 2 |
| Candlemaking | CANDLEMAKING | *(none)* | MAKE_TORCH (1 stick+1 coal→4 torch, 40t) | TORCH | 4 | 150 | `home_chandlery` | 2 |

**Key observations that make the generalization exact:**
- The four share one skeleton: `checkExtraStartConditions` (not-child + nav-guard
  + skill≥1 + house + amenity + inputs + family-need + motive) → phase machine
  `WALKING_TO_WORKSTATION → PRODUCING → DEPOSITING → DONE`. Candlemaking is the
  sole no-workstation variant (no WALKING phase; produces at the house).
- In every craft the **family-need good is exactly `recipe.output()`**, the
  deposited stack is `new ItemStack(recipe.output(), recipe.outputCount())`, the
  produce duration is `recipe.ticks()`, and inputs come from `recipe.inputs()` —
  so only the per-member threshold, amenity list, hobby id, XP, and activity
  label vary (the table fields). Constants (ARRIVAL 4.0, WALK_SPEED 0.7,
  CLOSE_ENOUGH 1, MAX_RUN 24000, the 600-tick nav safety abort, the WALK_TARGET
  VALUE_ABSENT memory requirement) are identical across all four.
- **Motive** generalizes as `economic OR hobby`, where economic =
  `(wallet+pool) < 150` and hobby = `LEISURE && (hobbyId == null ? true :
  pref.hasCurrent() && hobbyId.equals(pref.currentHobby()))` — the `null` arm
  reproduces milling's bare-LEISURE motive exactly.
- **Registration**: the four are contiguous entries in the IDLE-priority-0 list
  (`TownspersonMob.makeBrain`, lines 1668–1675), tried in order, first-to-pass
  wins. So the single behavior **iterates the table in that same order and picks
  the first qualifying row** — identical selection (not "greatest need").

### What shipped

- **`Npc/Brain/Behaviors/Homestead/HomeProductionBehavior.java`** (new) — one
  `Behavior<TownspersonMob>` with a nested `HomeCraft` record and a 4-row
  `TABLE` (the exact parameters above). `checkExtraStartConditions` resolves the
  house/household once, then iterates the table and selects the first row whose
  skill / amenity / inputs / family-need / motive all qualify; `start` sets the
  WALKING phase + WALK_TARGET when the row has a workstation, else jumps to
  PRODUCING; `tick` runs the shared phase machine; `tickDepositing` consumes all
  recipe inputs, deposits `recipe.output()` to the household via
  `storeWithFallback`, and awards the row's skill XP.
- **`TownspersonMob.makeBrain`** — the four `new Home*Behavior()` registrations
  collapsed to one `new HomeProductionBehavior()` in the same IDLE/0 slot
  (between `ElderlyRelaxBehavior` and `PersonalSpaceBehavior`, unchanged).
- **Deleted** `HomeBakingBehavior`, `HomeMillingBehavior`, `HomeWeavingBehavior`,
  `HomeCandlemakingBehavior`.

### Tie-In Audit

1. **Upstream feeders.** `SkillRecipes` (M1 recipes), `AmenityType.matches`
   (workstation detection), `HouseholdData` (`getMemberNpcIds().size()` need +
   `getPooledWealth()` motive), the hobby ids via `NpcHobbyPreference` +
   `ScheduleResolver.phaseAt` (LEISURE motive). All consumed exactly as the four
   behaviors did; no new feeder.
2. **Downstream callers.** The brain registration (4→1, same slot/priority). No
   other class referenced the four behavior classes by name (grep: only stale
   explanatory comments in the production classes about why a recipe constant
   stays public — left as-is; the 5 public aliases are retained per the prompt).
   Household storage receives the same deposits via the same `storeWithFallback`.
3. **Sibling systems.** The profession production behaviors are untouched (still
   source `SkillRecipes`). The idle/liveliness chain is unchanged: the single
   behavior occupies the former contiguous slot, so when no craft qualifies the
   brain falls through to `PersonalSpace`/`Hobby`/`SoloDevotion`/`IdleDirector`
   exactly as before. Skill XP routing unchanged (`SkillXp.award`).
4. **Exhaustive switches.** No new enum; the only `switch` is the internal
   `Phase` (4 arms, all handled). No existing exhaustive switch touched.
   Confirmed.

### Simplification Sweep

Four behavior classes → one behavior + one table. No per-skill home behavior
remains (grep confirms the four files are deleted and only
`HomeProductionBehavior` lives in `Homestead/`). The `TABLE` is the single place
craft params live. Classes in scope + inbound callers: `HomeProductionBehavior`
(new; 1 inbound — the brain registration); `TownspersonMob.makeBrain` (the 4→1
edit); the 4 deleted classes (0 remaining callers). Net −4 classes, −~600 dup
lines (the four near-identical state machines), +1 behavior + a 4-row table.

### Behavior-preservation proof

Each of the four crafts is reproduced field-for-field by its table row + the
shared skeleton: identical skill+min-level gate, identical amenity set in the
same preference order (SMOKER→FURNACE for baking; single for milling/weaving;
none for candlemaking), identical recipe object (the M1 `SkillRecipes` entry →
same inputs/output/count/ticks), identical family-need check
(`countItem(recipe.output()) < familySize × perMember`, same per-member values),
identical economic (`wallet+pool < 150`) and hobby/LEISURE motive (including
milling's null-hobby bare-LEISURE), identical phase machine + durations + deposit
+ XP, identical constants and memory requirement. Selection across crafts matches
the prior first-contiguous-in-IDLE-list-wins via same-order table iteration.

### Deviations from prompt

- **Selection rule is "first qualifying row in table order", not "greatest
  need".** The prompt offered greatest-need as an example, but the hard
  behavior-preservation constraint requires reproducing the original sequential
  IDLE-list resolution (first contiguous behavior to pass wins) — so the table is
  ordered baking→milling→weaving→candlemaking and the first qualifying row is
  chosen. (A greatest-need tiebreak would change which craft a multi-skilled NPC
  does and is therefore out of bounds this phase.)
- **The per-craft `LOGGER.info` lines collapsed into one generic log line.**
  Logging only — no player-observable behavior change.

### Out-of-scope but flagged

- **R6** adds the monastic-craft rows (book-copying / beekeeping / brewing /
  herbalism) + the monastery/monk context on top of this table.
- **Migrating the profession production behaviors onto the primitive** is a later
  M-step (they still run their own `AbstractProductionBehavior` machinery,
  sourcing `SkillRecipes`).
- A future **greatest-need / weighted** selection across qualifying rows (if the
  first-in-order rule ever proves too rigid) — deliberately not done here.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
Static review done: imports match the originals' package paths exactly; the four
files are deleted and only `HomeProductionBehavior` remains in `Homestead/`; the
brain edit collapses 4→1 in the same IDLE/0 slot; no remaining code reference to
the deleted classes (only stale comments); the table reproduces every extracted
parameter; the phase machine handles the no-workstation (candlemaking) variant;
the motive null-hobby arm reproduces milling.

### Smoke test (user-runnable)

1. **Each craft, unchanged.** Give an NPC the skill + amenity for each craft and
   deplete the family good, then confirm production exactly as in M1:
   - BAKING ≥1 + a smoker (or furnace) + bread below 4×family + ≥3 wheat → bakes
     bread to the household (200t), +2 BAKING.
   - MILLING ≥1 + a grindstone + flour below 4×family + ≥2 wheat → grinds flour
     (80t), +2 MILLING.
   - WEAVING ≥1 + a loom + wool below 2×family + ≥4 string → spins white wool
     (60t), +2 WEAVING.
   - CANDLEMAKING ≥1 + (no workstation) + torches below 4×family + ≥1 stick & ≥1
     coal → makes 4 torches at the house (40t, no walk), +2 CANDLEMAKING.
2. **No skill / no amenity → nothing.** An NPC without the skill, or without the
   required amenity (baking with neither smoker nor furnace; milling with no
   grindstone; weaving with no loom), does not produce that craft.
3. **Both motives.** Confirm the economic motive fires when `wallet+pool < 150`
   (even outside LEISURE), and the hobby motive fires during LEISURE with the
   matching hobby (`home_cooking`/`home_weaving`/`home_chandlery`), and milling
   fires on bare LEISURE — all exactly as before.
4. **Multi-skill NPC picks in order.** An NPC qualifying for several crafts at
   once does baking first, then (next eligibility) milling, etc. — the same craft
   the contiguous-behavior order produced before.
5. **Fall-through unchanged.** With no craft eligible, the NPC proceeds to its
   normal idle/hobby/stroll behaviors as before (the single behavior cedes the
   slot just like the four did).

---

## R6a — MONK profession + MONASTERY/ABBEY scaffolding (2026-06-08)

### Disposition (findings)

First phase of R6 (the monk). With the skills-first foundation (M1 `SkillRecipes`,
M2 `HomeProductionBehavior` + table) in place, the monk is built as a **context**
over that primitive, not a fixed supply chain. R6a is structural scaffolding: the
MONASTERY/ABBEY building types + the MONK profession (full exhaustive-switch sweep
+ monastic schedule + spawn/faith/lock) + a placeholder work registration — a monk
that spawns into a monastery and exists, ready for R6b's skill-driven work.

**Exhaustive-switch sweep (the #1 regression risk).** Cross-checked the explorer's
inventory with an independent repo-wide grep. Two compile-safety axes:
- **Every `Profession` switch EXPRESSION has a `default`** (verified each site;
  the one defaultless `switch(profession)` is over `PlayerProfession`, a different
  enum — false positive). So `MONK` breaks no compilation and falls through to
  defaults; explicit arms were added only where the default is wrong for a monk.
- **Every `BuildingType` switch has a `default`** (the `BuildingType.java`
  audit note + grep: all "defaultless `switch(type)`" sites are over other enums —
  CharterType/QuestType/CrimeType/RouteType/EventType/road-node). So MONASTERY/ABBEY
  break no compilation.

Per-site disposition:

| Site | kind | MONK / MONASTERY disposition |
|---|---|---|
| `Profession` enum | enum | **appended `MONK` at the tail** (ordinal-safe; saves are name-keyed) |
| `Profession.professionFor` | switch (default) | **`case MONASTERY, ABBEY -> MONK`** |
| `Profession.getDisplayName` | switch (default) | none — default yields "Monk" |
| `BuildingType` enum | enum | **appended `MONASTERY, ABBEY`** |
| `ProfessionSkills.buildTable` | EnumMap | **`MONK -> (CRAFTING, LITERACY)`** (justified below) |
| `WeeklyScheduleLibrary.dailyFor` | switch (default) | **`case MONK -> MONASTIC_DAY`** (new template) |
| `WeeklyScheduleLibrary.dayOffsFor` | switch (default) | **`case MONK -> SUNDAY_OFF`** |
| `NpcDialogue.getProfessionLine` | switch (default null) | **`case MONK -> …contemplative lines`** |
| `GreetPlayerBehavior.barkFor` | switch (default) | **`case MONK -> …cloister bark`** |
| `ApprenticeshipManager.masterpieceTargetFor` | switch (default) | **`case MONK -> written_book`** (scriptorium) |
| `BuildingFaith.isReligiousBuilding` | predicate | **+MONASTERY/ABBEY** (faith-bearing) |
| `BuildingFaith.applyClergyFaith` | gate | **+MONK** (monk takes building faith) |
| `BuildingInhabitantRegistry` | registry | **MONASTERY/ABBEY -> worker(MONK)** |
| `NpcSpecializationTypes` | registry | **+`MONK_CONTEMPLATIVE` generalist; MONK ∈ LOCK_GENERALIST_AT_SPAWN** |
| `VillageInhabitantPopulator` | spawn | **MONK-gated `assignInitialSpawnSpec`** (lock) |
| `ProfessionBrainFactory` | registry | **MONK placeholder registrar** (empty WORK; R6b fills) |
| `ProfessionRequirements.literacyRequired` | switch (default 0) | none — monk spawns ungated (R6c may add) |
| `NpcProfileSnapshotBuilder` isProducer/canAssignWork | switch (default false) | none — a monk is neither |
| `TreasuryTickHandler.wageForProfession` / `NpcStartingWealth` | switch (default) | none — monastery-supported; R6c economy |
| `ProfessionSupplyChain` | EnumMap | none — empty (graceful); R6b defines crafts→routing |
| `NpcProfileHub`, `GreetVerb.greetingTreeId`, `VillageEconomy` markup/seller, `BuyGoodsBehavior`, `TownspersonMob.getSellableItems`, `LifeGoal`, `LawPopularity`, `GreeterAssignment`, `FarmerPromotion`, `EventStallManager`, `ParkRenderer`, `ActivityFlavor.craft` | switch/map (default/fallback) | none — defaults correct for a monk (PRIEST also falls to default in the greeting-tree/profile-hub cases) |

**Faith vs rite-venue split (key tie-in).** `isReligiousBuilding` feeds BOTH faith
resolution (which the monk needs) AND the priest rite-venue map
(`religiousBuildingsByFaith`) + the temple-prosperity economy. To get the monk's
faith WITHOUT pulling a monastery into priest rite scheduling / temple decay, I
split: `isReligiousBuilding` = faith-bearing (TEMPLE/CHAPEL/SHRINE **+
MONASTERY/ABBEY**); a new `isRiteVenue` = TEMPLE/CHAPEL/SHRINE only. The three
rite-venue loops in `BuildingFaith` + `TempleProsperity.tickVillage` now use
`isRiteVenue` (identical to the old behavior for TEMPLE/CHAPEL/SHRINE — no
regression), while `resolveFaith` keeps `isReligiousBuilding` so a monastery
resolves its faith. `RiteScheduler`'s own private religious-building check is
already TEMPLE/CHAPEL/SHRINE — untouched.

**Monk ≠ rite officiant.** All rite-claim / ordination / blessing / confess gates
use `== Profession.PRIEST` (exact match) — confirmed in `RiteScheduler`,
`PriestBehavior`, `RiteExecutor`, `Confess/RequestBlessing/CommissionRiteVerb`. A
MONK is excluded by design; no change needed. `assignClergyOrder` stays PRIEST-only
(a monk takes no priest order).

**Skill choice justification.** `MONK -> (CRAFTING, LITERACY)`: CRAFTING is the
parent of the production sub-skills, so the monk's varied monastic crafts (R6b)
cascade XP sensibly; LITERACY is the scriptorium/study secondary. Deliberately not
PRIEST's `(SOCIAL, LITERACY)` — a monk is craft+study, not a congregation-facing
officiant.

**Placement.** `BuildingProfileRegistry` no longer exists (removed in the V1→V2
placement migration) and worldgen/layout is deferred this phase, so there is NO
slot-tier registration — MONASTERY/ABBEY are manual-spawnable and populate a MONK
via `BuildingInhabitantRegistry`.

### What shipped

- **`Profession`**: appended `MONK`; `professionFor` MONASTERY/ABBEY → MONK.
- **`BuildingType`**: appended `MONASTERY`, `ABBEY`.
- **`BuildingFaith`**: `isReligiousBuilding` += MONASTERY/ABBEY; new public
  `isRiteVenue` (TEMPLE/CHAPEL/SHRINE); the three rite-venue loops switched to
  `isRiteVenue`; `applyClergyFaith` gate relaxed to PRIEST||MONK.
- **`TempleProsperity`**: economy/decay tick now iterates `isRiteVenue` (monastery
  excluded — its economy is R6c).
- **`ProfessionSkills`**: `MONK -> (CRAFTING, LITERACY)`.
- **`WeeklyScheduleLibrary`**: new `MONASTIC_DAY` (pre-dawn rise, early retire,
  contemplative dusk); `dailyFor` MONK → MONASTIC_DAY; `dayOffsFor` MONK →
  SUNDAY_OFF.
- **`NpcDialogue`** / **`GreetPlayerBehavior`**: monastic profession lines + bark.
- **`ApprenticeshipManager`**: MONK masterpiece → written_book.
- **`BuildingInhabitantRegistry`**: MONASTERY/ABBEY → single worker(MONK).
- **`NpcSpecializationTypes`**: new `MONK_CONTEMPLATIVE` generalist;
  MONK ∈ `LOCK_GENERALIST_AT_SPAWN`.
- **`VillageInhabitantPopulator`**: after the PRIEST-only `assignClergyOrder`, a
  MONK-gated `assignInitialSpawnSpec` locks the monk's generalist spec.
- **`ProfessionBrainFactory`**: MONK placeholder registrar (empty WORK; R6b fills).

### Tie-In Audit

1. **Upstream feeders.** `BuildingType`/`BuildingInhabitantRegistry` (spawn),
   `BuildingFaith` (faith via the new split), the spec-lock path
   (`NpcSpecializationTypes` + populator). All reused; no new framework.
2. **Downstream callers.** Every exhaustive `Profession` switch dispositioned
   (table above) — all compile (defaults) with explicit arms where semantics
   matter. `ProfessionBrainFactory` gives MONK a valid (placeholder) brain.
   `WeeklyScheduleLibrary` gives it a schedule. R9a's religion panel + the profile
   builder read a monk's piety/faith generically (MONK is `isProducer=false`,
   `canAssignWork=false` — correct). Office eligibility (`VILLAGE_PRIEST`,
   `TEMPLE_HIGH_PRIEST`) gates on PRIEST only — a monk is ineligible (correct; an
   Abbot office is the later offices pass).
3. **Sibling systems.** Priest/clergy rite systems gate on `== PRIEST` — a monk is
   NOT pulled into rite-claim/ordination (verified). The R9 panels show MONK +
   faith generically. The apprenticeship system has a MONK masterpiece descriptor
   (a monastic mentor is later). The M2 home-production primitive runs for a monk
   too (it's profession-agnostic, IDLE) — a nice side-effect, not the monastery
   WORK context (R6b).
4. **Exhaustive switches.** `Profession` (the whole sweep — all defaulted/armed)
   and `BuildingType` (all defaulted; professionFor + BuildingFaith handle
   MONASTERY/ABBEY explicitly). Confirmed no defaultless switch over either enum.

### Simplification Sweep

The monk is a new `Profession` + two `BuildingType`s + a faith/rite-venue predicate
split — no new frameworks. Reused: the building-inhabitant spec (worker(MONK) like
TEMPLE), `BuildingFaith.applyClergyFaith` (relaxed), the `LOCK_GENERALIST_AT_SPAWN`
spec-lock route (shepherd/priest pattern), `WeeklyScheduleLibrary` templates, the
`ProfessionBrainFactory` registrar idiom. Classes in scope (13) + inbound callers
listed in the table; no MONK arm missed (grep: MONK referenced in all 12 expected
code files + TempleProsperity via isRiteVenue).

### Deviations from prompt

- **No `BuildingProfileRegistry` slot-tier entry** — that registry was removed in
  the V1→V2 placement migration and worldgen is deferred; manual spawn populates
  via `BuildingInhabitantRegistry`, which is sufficient. (The `litv-building-profile`
  skill's Step 1 is obsolete here; Step 2 inhabitant spec was used.)
- **Introduced `isRiteVenue` (a faith-bearing vs rite-venue split)** rather than
  blindly widening `isReligiousBuilding` everywhere — required to give the monk a
  faith without pulling monasteries into priest rite scheduling / temple economics
  (the prompt's Tie-In Audit explicitly demanded this confirmation).
- **MONK keeps the PRIEST-only `assignClergyOrder` as a no-op and locks via a
  MONK-gated `assignInitialSpawnSpec`** instead of relaxing `assignClergyOrder`
  (a monk takes no priest order; this keeps the priest path byte-unchanged).
- **The WORK registrar is an explicit empty placeholder** (graceful idle, not an
  error) — R6b replaces the body.

### Out-of-scope but flagged

- **R6b** — the monk's skill-driven monastery production context + the monastic
  crafts (book-copying / beekeeping / brewing / herbalism), and `ProfessionSupplyChain`
  / wage / starting-wealth entries that the crafts imply.
- **R6c** — initiate→skill development + the monastery's economy/needs (the
  monastery is intentionally OUT of `TempleProsperity` for now).
- **Standalone-district worldgen + ABBEY expansion** — deferred to the layout
  rework (no auto-placement this phase; MONASTERY/ABBEY are manual-spawn).
- **The Abbot/Abbess office** (`List.of(Profession.MONK)` eligibility + POWERS) —
  Garrett's later offices pass; no abbot behavior this phase.
- **A true per-religion monastic horarium** (multiple prayer offices) — a later
  depth pass; prayer/study ride SOCIAL/LEISURE for now.
- **A MONASTERY/ABBEY structure NBT / variant manifest** for the actual block
  placement on manual spawn is a content-asset concern outside this code phase.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac). Static review done: the
exhaustive-switch sweep was independently re-grepped — every `Profession` and
`BuildingType` switch expression has a `default` (the defaultless ones are over
other enums), so `MONK`/`MONASTERY`/`ABBEY` break no compilation; explicit arms
added where the default is wrong for a monk; `MONK` appended at the enum tail
(name-keyed saves unaffected); `BuildingFaith` faith/rite-venue split verified
consistent (resolveFaith = faith-bearing; the three venue loops + TempleProsperity
= rite-venue); `MONK_CONTEMPLATIVE` generalist registered + MONK in the lock set so
`assignInitialSpawnSpec(MONK)` finds & locks it; placeholder brain registrar valid;
`SpecializationData.None.INSTANCE` reused (as PRIEST_CLERIC does).

### Smoke test (user-runnable)

1. **Monastery spawns a monk.** Manually spawn a MONASTERY in a village; confirm a
   MONK populates it, takes the monastery's faith (the village dominant, or a
   shrine-style minority if its patron faith is set), holds a LOCKED MONK spec
   (Contemplative), and follows the MONASTIC_DAY schedule (pre-dawn rise, early
   retire) without erroring or freezing. Confirm the same for an ABBEY.
2. **Profile + R9a panel.** Right-click the monk: the profile shows profession
   MONK; the R9a Religion panel shows the monastery's faith + the monk's piety.
   The temple screen (`/religion temple` near the monastery) opens it as a
   faith-bearing building (no priest clergy shown — correct).
3. **Not a rite officiant.** Confirm the monk is NOT pulled into priest behavior:
   it never claims/officiates rites, gets no ordination, and the priest-only verbs
   (confess / request blessing / commission rite) are unavailable at the monk.
   Confirm a village with ONLY a monastery (no temple/chapel/shrine of its faith)
   does NOT schedule signature rites at the monastery (it's not a rite venue).
4. **No temple-economy decay.** Confirm a freshly-spawned monastery does NOT start
   decaying from insolvency (it's excluded from TempleProsperity; its economy is
   R6c).
5. **No regressions.** Confirm existing professions/NPCs (priests, bakers, etc.)
   behave exactly as before — schedules, rites, production, greetings, profiles —
   and that nothing crashes anywhere (the switch sweep is complete; MONK falls
   through correctly where unarmed).

---

## R6b — monastery production context + monastic crafts (2026-06-09)

### Disposition (findings)

The heart of the monk. R6a gave a MONK that spawns into a monastery with a locked
spec + schedule + a placeholder WORK behavior. R6b gives it real work —
skill-driven production in a **monastery context** — built on M1 (`SkillRecipes`)
+ M2 (the home-production primitive). It adds the **second context** to the
production primitive, proving "skills = what you CAN do; context = what you DO
with them" generalizes.

- **M2 `HomeProductionBehavior`** drives a `HomeCraft` table (skill, amenities,
  recipe, family-need + economic/hobby motive, deposit=household) through a phase
  machine (walk→produce→deposit). The cleanest generalization: **extract the
  phase machine into an abstract `ContextProductionBehavior` base**, and have each
  context implement only `selectPlan` (which skills / motive / destination),
  returning a `Plan(building, workstationPos, recipe, skill, xp, label)`. HOME's
  `selectPlan` = the verbatim M2 selection; the base = the verbatim M2 tick logic
  with the deposit target generalized from "the house" to "the plan's building"
  (which IS the house for HOME) → HOME byte-exact.
- **Skills (all exist):** BEEKEEPING (FARMING grandchild), LITERACY (top-level),
  VILLAGE_MEDICINE (MEDICINE child), CANDLEMAKING (CRAFTING child). **No BREWING.**
- **Amenities:** BREWING_STAND exists (reuse for herbal). Added APIARY
  (`BeehiveBlock` — covers BEEHIVE + BEE_NEST) + LECTERN (`LecternBlock`).
- **Items:** all vanilla — HONEY_BOTTLE/GLASS_BOTTLE (honey), BOOK/PAPER
  (manuscript), SUSPICIOUS_STEW/GLOW_BERRIES/BROWN_MUSHROOM (herbal), CANDLE/
  HONEYCOMB/STRING (candles). Remedies are records (not items), so the herbal
  craft uses an item proxy (a tonic). WRITTEN_BOOK needs NBT, so the manuscript
  output is a plain BOOK (no NBT pitfalls).
- **Monastery store:** `BuildingStorageAccess.{countItem,takeItem,storeWithFallback}`
  are generic on any `Building`; the monk's monastery = `getAssignedBuildingId()`.

**BREWING / mead decision — DEFERRED.** A new `Skill` value needs its own
exhaustive-switch sweep + cascade wiring (the same class of work as R6a's
Profession sweep) for a single craft. The prompt explicitly permits deferral, so
this phase ships the four crafts on EXISTING skills and flags mead+BREWING as a
follow-up — no speculative skill.

### What shipped

- **`Npc/Brain/Behaviors/Homestead/ContextProductionBehavior.java`** (new
  abstract base) — the context-parameterized primitive: gates (not-child, nav),
  the phase machine (walk → produce for `recipe.ticks()` → consume inputs +
  deposit `recipe.output()` to the plan's building → award the skill XP), and the
  shared amenity/input helpers. Subclasses implement `selectPlan`.
- **`Homestead/HomeProductionBehavior.java`** — refactored to extend the base;
  `selectPlan` is the verbatim M2 HOME selection (family-need + economic/hobby
  motive → household). Phase machine + helpers now inherited. HOME unchanged.
- **`Production/MonkProductionBehavior.java`** (new) — the MONASTERY context.
  Iterates a monastic-craft table (skill ≥ level + amenity present + inputs
  available + the monastery wants the good — stock below a per-craft quota),
  first qualifying → `Plan(monastery, …)`, depositing to the monastery store.
- **`Village/AmenityType.java`** — added `APIARY`, `LECTERN`.
- **`Village/Economy/Resources/SkillRecipes.java`** — added `HARVEST_HONEY`
  (glass_bottle→honey_bottle, BEEKEEPING), `COPY_MANUSCRIPT` (paper×3→book,
  LITERACY), `BREW_TONIC` (glow_berries+brown_mushroom→suspicious_stew,
  VILLAGE_MEDICINE); registered the BEEKEEPING/LITERACY/VILLAGE_MEDICINE buckets.
  Candles reuse the existing `MAKE_CANDLE` (CANDLEMAKING).
- **`Npc/Brain/ProfessionBrainFactory.java`** — the MONK WORK registrar now adds
  `MonkProductionBehavior` (replacing the R6a placeholder).

**The four monastic crafts (skill × amenity × recipe):**
| craft | skill | amenity | recipe | quota |
|---|---|---|---|---|
| Candles | CANDLEMAKING | *(none)* | MAKE_CANDLE (honeycomb+string→candle) | 16 |
| Honey | BEEKEEPING | APIARY | HARVEST_HONEY (glass_bottle→honey_bottle) | 16 |
| Manuscripts | LITERACY | LECTERN | COPY_MANUSCRIPT (paper×3→book) | 8 |
| Herbal tonic | VILLAGE_MEDICINE | BREWING_STAND | BREW_TONIC (glow_berries+brown_mushroom→suspicious_stew) | 8 |

### Tie-In Audit

1. **Upstream feeders.** `SkillRecipes` (3 new recipes + buckets), `AmenityType`
   (APIARY/LECTERN added; BREWING_STAND reused), the monk's `SkillComponent`
   (developed skills gate each craft), the monastery building store. All reused;
   the monk produces by SKILL gated by AMENITY — never a fixed list.
2. **Downstream callers.** HOME (`HomeProductionBehavior`) preserved (still
   registered in the IDLE/0 slot; its selection logic byte-identical, phase
   machine inherited). The MONK WORK registrar (R6a placeholder → MonkProductionBehavior).
   `BuildingStorageAccess` (the monastery store, generic). The scribal/book +
   beekeeping systems are not modified — the monastic recipes reuse their item
   economy (honey_bottle / book) without touching those behaviors. R4 economy
   (monastery wages/store value) is untouched — flagged for R6c.
3. **Sibling systems.** The home production primitive stays behavior-exact (the
   base is the M2 phase machine verbatim; HOME selectPlan is M2 verbatim). The
   profession production behaviors are untouched (still source `SkillRecipes`).
   Skill XP: the monk gains its produced craft's skill XP via the shared
   `SkillXp.award` in the base (BEEKEEPING/LITERACY/VILLAGE_MEDICINE/CANDLEMAKING,
   cascading up to FARMING/MEDICINE/CRAFTING).
4. **Exhaustive switches.** No new `Skill` (BREWING deferred) → no `Skill` switch
   touched. `AmenityType` is matched via `matches(Block)` (no exhaustive switch
   over it — it's iterated, not switched). Confirmed.

### Simplification Sweep

The monastery context reuses the generalized primitive + `SkillRecipes` + existing
amenities (BREWING_STAND) + 2 new amenities — NOT a new production system. One
primitive (`ContextProductionBehavior`), two thin context subclasses
(`HomeProductionBehavior`, `MonkProductionBehavior`). Classes in scope + inbound
callers: `ContextProductionBehavior` (new; 2 subclasses), `HomeProductionBehavior`
(1 — TownspersonMob IDLE registration, unchanged), `MonkProductionBehavior` (1 —
ProfessionBrainFactory MONK WORK), `SkillRecipes` (+3 recipes/buckets; consumed by
the monk by named constant), `AmenityType` (+2; matched generically). No
duplicate/forked monastery behavior. **BREWING/mead deferred** (stated above).

### Behavior-preservation proof (HOME)

`HomeProductionBehavior.selectPlan` is the M2 `checkExtraStartConditions` body
verbatim (same house/household resolution, same TABLE in the same order, same
skill/amenity/inputs/family-need/economic+hobby-motive gates, first qualifying
wins) — only its tail now returns a `Plan(house, …)` instead of setting fields.
The base's phase machine is the M2 tick logic verbatim — walk to the workstation,
produce for `recipe.ticks()`, consume `recipe.inputs()`, deposit
`recipe.output()` via `storeWithFallback`, award `skill` XP — with the only
generalization being the deposit/consume target = the plan's building, which is
the house for HOME. Constants (ARRIVAL 4.0, WALK 0.7, 600-tick abort, MAX_RUN,
WALK_TARGET VALUE_ABSENT requirement) unchanged. The four home crafts are
indistinguishable from M2.

### Deviations from prompt

- **Mead/BREWING deferred** (decision above) — the four crafts use existing skills.
- **Herbal output is an item proxy (`SUSPICIOUS_STEW`), not a `Remedy`** — remedies
  are records held in `HealerInventory`, not items, so they can't be produced by
  the item-based primitive / deposited to building storage. The tonic is the
  tangible monastic herbal good; deeper integration with the healer remedy system
  is a follow-up.
- **Manuscript output is a plain `BOOK`, not `WRITTEN_BOOK`** — WRITTEN_BOOK
  requires NBT (page/author components) to be a valid stack; a plain BOOK avoids
  that. An illuminated/written-book variant is a later content pass.
- **Candles need externally-stocked honeycomb** — the apiary produces honey_bottle
  (the iconic good), not honeycomb, so the candle craft depends on honeycomb in
  the monastery store (graceful: no honeycomb → no candles). A honeycomb-harvest
  variant feeding candles is a flagged nicety.
- **Extracted `ContextProductionBehavior`** rather than parameterizing
  `HomeProductionBehavior` in place — cleaner (the base is the primitive; HOME and
  MONASTERY are thin routers), and HOME stays byte-exact.

### Out-of-scope but flagged

- **R6c** — initiate→skill development + need-driven assignment; mealtime
  distribution/consumption from the shared store; the monastery economy/wages
  (R4 tie). The monastery is intentionally OUT of `TempleProsperity` (R6a).
- **Mead + a BREWING skill** (with its exhaustive-switch + cascade sweep).
- **Honey→honeycomb chain for candles; WRITTEN_BOOK/illuminated manuscripts;
  remedy-system integration** for the herbal craft.
- **Standalone-district worldgen + the Abbot office** — later.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
Static review done: the base + two contexts compile-consistently (HOME selectPlan
is M2 verbatim; the base phase machine is M2 verbatim with deposit→plan.building);
the 3 new recipes use vanilla items + are declared before the `BY_SKILL` field
(static-init order safe) + registered under their owning skills; APIARY/LECTERN
added with the existing predicate shape (BeehiveBlock/LecternBlock imported);
`MonkProductionBehavior` registered for MONK WORK (replacing the placeholder),
`HomeProductionBehavior` still registered in IDLE; no new `Skill`/exhaustive-switch.

### Smoke test (user-runnable)

1. **Monastery crafts by skill × amenity.** Spawn a MONASTERY; build an APIARY
   (beehive/bee-nest), a LECTERN, and a BREWING_STAND inside it, and stock the
   inputs (glass bottles, paper, glow berries + brown mushrooms, honeycomb +
   string). Give the monk BEEKEEPING + LITERACY + VILLAGE_MEDICINE + CANDLEMAKING
   (`/…` skill debug). During its work schedule, confirm it produces honey
   bottles, books, tonics, and candles INTO the monastery store, and gains the
   matching skill XP.
2. **Skill + amenity gating.** Remove the LECTERN → manuscript copying stops (the
   monk still does the others). Strip a skill → that craft stops. An unskilled
   monk (no developed crafts) produces nothing — no error, just idle/contemplation.
3. **Quota gate.** Confirm a craft stops once the monastery's stock of that good
   reaches its quota (16 honey/candles, 8 books/tonics), and resumes when drawn down.
4. **HOME unchanged.** Confirm family baking/milling/weaving/candlemaking behave
   exactly as M2 (same family-need trigger, same amenities, same household output,
   same XP) — the refactor is invisible.
5. **ABBEY too.** Repeat (1) for an ABBEY — the monk produces identically.

---

## R6c — the self-organizing monastery (initiate → skill → need) (2026-06-09)

### Disposition (findings)

Closes the monk loop. R6b gave a monk skill-driven production, but it only makes
what it ALREADY has skills for, and a fresh skill-less monk does nothing. R6c
makes the monastery **self-organize** around its needs: derive needs from the
shared store, prioritize the most-needed craft in production, and draw idle
initiates to DEVELOP a needed + supported skill (mentors accelerate) — emergent,
not scripted.

1. **Needs.** `VillageNeedsCalculator` exists but is **village-scale** (the
   central stockpile + all NPCs; FOOD/BUILDING_MATERIALS/SEEDS via nutrition).
   It does not fit a per-monastery role-goods need, so — per the prompt's "else a
   small store-stock-vs-target computation" — I derive the monastery's need
   locally: `need(craft) = quota − monasteryStore.countItem(good)`, reusing R6b's
   per-craft quotas as the targets. (Food eating/distribution is R6d; the derived
   needs here are the producible role-goods: candles, honey, books, tonics.)
2. **Need-priority.** R6b's `MonkProductionBehavior.selectPlan` took the first
   qualifying craft; now it takes the **most-needed** qualifying craft (greatest
   `need`), keeping every existing gate (skill ≥ level + amenity + inputs +
   below-quota), tie-broken by craft order.
3. **Development catalysts (all reused):** `FamilySkillSeeder` awards a biased-low
   `1 + min(rand15, rand15)` (~5 XP) one-shot at coming-of-age via
   `SkillXp.award` — I mirror its magnitude with a small **daily directed seed**.
   `SkillXp.award` **already applies `MentorshipBonus.npcMentorshipFor` + the
   AMBITION modifier automatically**; the generic "senior monk in the house"
   accelerator (no apprenticeship contract) I apply by scaling the seed by
   `MentorshipBonus.NPC_MENTORSHIP_MULTIPLIER` (1.5). Hobby steering via
   `NpcHobbyPreference.setTopHobbies` feeds the existing hobby-drift XP path where
   a matching hobby exists (candlemaking → `home_chandlery`).
4. **Hook.** A daily per-village pass, alongside `TempleProsperity.tickVillage` in
   `RiteScheduler.dailyTick` (TickSystems' 24000-tick driver). Monks of a
   monastery are found by the `TempleProsperity.findAssignedPriest` scan pattern
   (AABB + profession == MONK + assignedBuildingId).

### What shipped

- **`Npc/Religion/MonasticCrafts.java`** (new) — the single source of the
  monastic-craft definitions (extracted from R6b's behavior-private table):
  `MonasticCraft(skill, minLevel, amenities, recipe, quota, xp, label, hobbyId)`
  + the `CRAFTS` list + `need` / `amenityPos` / `isSupported` / `supportedAt` /
  `isProducer` helpers. Shared by the behavior + the developer.
- **`Npc/Brain/Behaviors/Production/MonkProductionBehavior.java`** — refactored to
  source `MonasticCrafts.CRAFTS` and pick the **most-needed** qualifying craft
  (R6c need-priority) instead of the first.
- **`Npc/Religion/MonasteryDeveloper.java`** (new) — the daily per-village pass:
  for each MONASTERY/ABBEY, steer each **idle initiate** (a monk not yet producing
  any supported craft) toward a needed + supported craft — preferring an
  **uncovered** one so initiates diversify (beekeepers AND scribes) — via a small
  directed seed (`SEED_XP_PER_DAY = 4`, ×1.5 with a co-located senior monk at
  skill ≥ 30) + hobby steering where a hobby exists. Bounded + gradual (a viable
  producer at skill ≥ 1 in ~1–2 weeks, then production XP takes over and the seed
  stops).
- **`Npc/Religion/RiteScheduler.java`** — step 7 of `dailyTick` runs
  `MonasteryDeveloper.tickVillage` per village (guarded).
- **`Village/AmenityType.java`** — added `firstPresent(level, building, types/type)`
  static helpers (the single home for the building amenity scan).
- **`Npc/Brain/Behaviors/Homestead/ContextProductionBehavior.java`** — its
  `firstAmenityPos` now delegates to `AmenityType.firstPresent` (the private copy
  retired; behavior-identical).

### Tie-In Audit

1. **Upstream feeders.** The needs derivation (`MonasticCrafts.need` =
   store-vs-quota), the monastery amenities (`MonasticCrafts.supportedAt` via the
   consolidated `AmenityType.firstPresent`), the monks' skills
   (`SkillComponent.getLevel`). All read-only.
2. **Downstream callers.** `MonkProductionBehavior.selectPlan` (need-priority);
   `SkillXp.award` (the directed seed — applies its own auto-mentorship/ambition);
   `NpcHobbyPreference.setTopHobbies` (hobby steering → the existing hobby-drift
   XP); the monastery shared store. `RiteScheduler.dailyTick` gains a guarded
   step 7. The R6b `MonkProductionBehavior` table moved into `MonasticCrafts` (its
   only consumer + the new developer).
3. **Sibling systems.** HOME production untouched (the `ContextProductionBehavior`
   change is an internal delegation of the amenity scan — behavior-identical; HOME
   selectPlan is the verbatim M2 logic). Profession production behaviors untouched.
   The apprentice/mentor system is REUSED read-only — the monastic mentor applies
   the `MentorshipBonus` CONSTANT (1.5×) directly; it creates NO apprenticeship
   contract, so it can't clash with R1d's clergy apprenticeship use. The hobby
   system is reused via its public `setTopHobbies`. R6d (distribution/economy)
   flagged.
4. **Exhaustive switches.** None added; no enum touched. Confirmed.

### Simplification Sweep

Need-priority is a `selectPlan` ordering (no new selection framework); development
reuses the seed (`SkillXp.award`) + hobby drift (`setTopHobbies`) + mentor
(`MentorshipBonus`) mechanisms; needs reuse a tiny store-vs-quota computation (no
parallel needs system). Consolidations: the monastic-craft table now lives once in
`MonasticCrafts` (was behavior-private); the building amenity scan now lives once
in `AmenityType.firstPresent` (the R6b `ContextProductionBehavior` copy retired).
Classes in scope + inbound callers: `MonasticCrafts` (new; 2 — the behavior + the
developer), `MonkProductionBehavior` (1 — ProfessionBrainFactory MONK WORK),
`MonasteryDeveloper` (1 — RiteScheduler.dailyTick), `AmenityType.firstPresent` (2 —
ContextProductionBehavior + MonasticCrafts), `RiteScheduler.dailyTick` (the
TickSystems driver). No forked needs/production/mentor framework.

### Deviations from prompt

- **Needs are a small per-monastery store-vs-quota computation, not
  `VillageNeedsCalculator`** — the latter is village-scale (stockpile + all NPCs,
  food/materials/seeds) and doesn't express a monastery's role-goods need; the
  prompt's fallback ("a small store stock vs target computation") is the fit. Food
  need (monks eating) is R6d's distribution concern, noted not built.
- **The mentor accelerator is applied by scaling the seed by the `MentorshipBonus`
  constant**, not by creating an apprenticeship contract. `SkillXp.award`'s
  built-in mentorship only fires for an apprenticeship/elderly relationship; a
  generic "senior monk in the cloister" wouldn't trigger it, so the seed is scaled
  directly (still reusing `MentorshipBonus.NPC_MENTORSHIP_MULTIPLIER`). A formal
  monastic apprenticeship is a flagged richer option.
- **Hobby steering only where a hobby exists** (candlemaking → `home_chandlery`);
  beekeeping/literacy/medicine have no matching hobby today, so they develop via
  the directed seed alone (the "and/or" the prompt allows). Adding monastic
  hobbies is a flagged enhancement.
- **Diversity is "uncovered needs first" within a daily pass**, not a persisted
  assignment — emergent and bounded, no new brain memory.

### Out-of-scope but flagged

- **R6d** — mealtime distribution / consumption from the monastery shared store +
  the monastery money economy/wages (the existing `EatMealBehavior` covers
  individual eating; the monastery is still OUT of `TempleProsperity`). A derived
  FOOD need for the monastery (target = monk-count nutrition) lands with R6d.
- **The Abbot office** issuing assignments (needs are DERIVED this phase, not
  abbot-issued) — offices pass.
- **Mead + a BREWING skill**; monastic hobbies for beekeeping/literacy/medicine;
  a formal monastic apprenticeship contract; re-steering an over-covered producer
  toward an uncovered gap (only idle initiates develop this phase).
- **Standalone-district worldgen** — later.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
Static review done: signatures confirmed (`MentorshipBonus.NPC_MENTORSHIP_MULTIPLIER`
1.5f, `NpcHobbyPreference.setTopHobbies(List<String>)`, `SkillXp.award` float
overload, `AmenityType.firstPresent`); the developer is hooked into `dailyTick`
(guarded, same-package, no import); `MonkProductionBehavior` sources
`MonasticCrafts.CRAFTS` and picks the most-needed qualifying craft (need-priority);
`MonasteryDeveloper` only targets supported crafts (never develops an unsupportable
craft), only seeds idle initiates (self-limiting once skill ≥ 1), and is bounded
(small daily seed); HOME is untouched (the `ContextProductionBehavior` amenity-scan
delegation is behavior-identical; HOME selectPlan unchanged); the monastic-craft
table + amenity scan are each single-sourced now.

### Smoke test (user-runnable)

1. **Needs derived + need-priority.** Build a monastery with an APIARY + a LECTERN;
   draw its candle/book/honey store below target. A monk skilled in two of those
   makes the MOST-needed one first (lowest store relative to its quota); stock one
   good up to quota and confirm the monk switches to the next-most-needed.
2. **Initiates develop toward needed + supported crafts.** Drop several skill-less
   monks into the apiary+scriptorium monastery. Over in-game days confirm idle
   initiates gain skill (check the profile) steered toward the needed crafts —
   some toward BEEKEEPING, some toward LITERACY (diversify) — and, once each
   crosses skill 1, they start producing honey / books into the store.
3. **Mentor acceleration.** With a senior monk (skill ≥ 30 in a craft) in the
   cloister, confirm an initiate developing THAT craft levels noticeably faster
   (×1.5 seed) than without a mentor.
4. **Only supported crafts.** Remove the LECTERN; confirm initiates no longer
   develop LITERACY (the monastery can't support scribing) — they steer to the
   remaining supported needs (honey / candles).
5. **Gradual + bounded.** Confirm development is slow (no instant mastery — a
   fresh initiate takes ~1–2 weeks to become a viable producer) and stops seeding
   a monk once it's a producer.
6. **No regressions.** HOME family production + the profession behaviors are
   unchanged; nothing crashes (the daily pass is guarded).

---

## R6d — mealtime distribution + monastery economy (2026-06-09)

### Disposition (findings)

Closes the monk production → consumption → economy loop. R6b/R6c made monks
produce by skill into the monastery shared store and self-organize around needs.
R6d makes the monastery a living, self-sustaining community: monks eat from the
store, the monastery derives + fills a food need, and surplus monastic goods fund
the inputs it can't make.

1. **Eating.** `EatMealBehavior` is pure item-removal (no hunger stat) gated on
   `entity.hasHome()`, sourcing food from `getHomeBuilding` = the family house
   (then market/bakery). A monk has **no household**, so it never ate. The clean
   redirect (no forked eating system): make `getHomeBuilding` return the **monastery**
   (the monk's assigned MONASTERY/ABBEY) for a MONK — then every existing
   food-source path (`homeHasFood`, walk-home, `tryEatFromHome` which consumes via
   `FoodValueHelper.isFood` + `stack.shrink`) sources from the **shared store** —
   and relax the `hasHome` gate to admit a monk whose food-home exists.
2. **Food need.** Added a **BAKING → bread** monastic craft to `MonasticCrafts`
   (`WHEAT_TO_BREAD` + a furnace/smoker amenity, target 32). A monk with BAKING +
   the amenity bakes bread (R6c need-priority); the developer steers idle initiates
   toward BAKING when the monastery has a furnace/smoker + is short on bread.
   (Target is a fixed buffer; consumption scales the depletion rate with the monk
   count; the economy buys bread/wheat when production can't keep up.)
3. **Economy = the monastery's own `BuildingEconomy`** (the shared pool;
   `getOrCreateBuildingEconomy(monasteryId)` — exactly the R4 temple pattern, but
   monasteries are `isRiteVenue`-excluded (R6a) so they are OUTSIDE
   `TempleProsperity`/decay). Surplus-sell reuses the workshop sell formula
   (`VillageEconomy.getBasePrice × 0.8` + `postListing`) but credits the pool
   (`depositRevenue`) rather than chest coins (so the buy path can spend it).
   Input procurement reuses the `ChannelRouter` path (`TradeIntent.buy` →
   `findBestChannel` → `channel.execute`), funded from the pool. A fresh monastery
   is bootstrap-seeded via `BuildingStarterTable` so it can buy its first inputs.

### What shipped

- **`Npc/Brain/Behaviors/EatMealBehavior.java`** — a monk's `getHomeBuilding`
  resolves to its monastery (so it eats from the shared store); the `hasHome` gate
  admits a monk with a food-home. Non-monks unchanged (the redirect is
  `Profession.MONK`-gated; the gate change is a no-op for them).
- **`Npc/Religion/MonasticCrafts.java`** — added the BAKING → bread food craft
  (first row; food is primary).
- **`Npc/Religion/MonasteryEconomy.java`** (new) — the daily per-village economy
  pass: per MONASTERY/ABBEY with monks, debit `MONASTERY_DAILY_UPKEEP` (+ a
  non-decaying solvency signal), **sell surplus** monastic goods (above target,
  capped) to market crediting the pool, **buy inputs** for producer-backed needed
  crafts (wheat for bread, honeycomb/string for candles, paper for books, …) from
  the pool via `ChannelRouter`, and a **food safety net** (buy bread directly when
  the store dips below a survival floor and there's no baker). The agent monk's
  wallet is a transient conduit, fully restored on failure/leftover (mirrors
  `executeBuy`).
- **`Npc/Religion/MonasteryDeveloper.java`** — `monksOf` made package-visible (the
  economy reuses the same scan).
- **`Npc/Religion/RiteScheduler.java`** — step 8 of `dailyTick` runs
  `MonasteryEconomy.tickVillage` per village (guarded).
- **`Village/Economy/EconomicBalance.java`** — `MONASTERY_DAILY_UPKEEP = 4`.
- **`Village/Economy/BuildingStarterTable.java`** — MONASTERY (120) / ABBEY (160)
  starter pool so a fresh house bootstraps.

### Tie-In Audit

1. **Upstream feeders.** The eating/hunger behavior (food source → the monastery
   store via the `getHomeBuilding` redirect), R6c `MonasticCrafts`/needs (the new
   food need + the supported/need/isProducer helpers reused by the economy), the
   monastery store (R6b `BuildingStorageAccess`), the monastery `BuildingEconomy`
   pool, the `ChannelRouter` + market sell formula, `BuildingStarterTable`.
2. **Downstream callers.** `EatMealBehavior` (monks eat from the store — the only
   change, gated to monks); `MonkProductionBehavior`/`MonasteryDeveloper` (bread is
   now a need-priority craft + a development target); the surplus-sell + `ChannelRouter`
   procurement; the monastery `BuildingEconomy`. `RiteScheduler.dailyTick` gains a
   guarded step 8.
3. **Sibling systems.** The R4 temple economy is SEPARATE — monasteries are not
   rite venues (`isRiteVenue` split, R6a), so `TempleProsperity` never touches them;
   `MonasteryEconomy` is a parallel, decoupled, non-decaying pass on the same
   `BuildingEconomy` primitive. The market/trade channels are reused (sell + buy).
   HOME production + the profession behaviors + the temple economy are untouched.
4. **Exhaustive switches.** None added; no enum touched. Confirmed.

### Simplification Sweep

Eating reuses `EatMealBehavior` (a redirect of the food-home for monks, not a new
eating system); the economy reuses the `BuildingEconomy` pool + the workshop sell
formula + the `ChannelRouter` procurement + the `BuildingStarterTable`/`EconomicBalance`
patterns; food is just another R6c `MonasticCrafts` craft/need. No new eating or
economy framework. Classes in scope + inbound callers: `MonasteryEconomy` (new; 1 —
RiteScheduler.dailyTick), `MonasticCrafts` (+1 craft; consumed by the behavior +
developer + economy), `EatMealBehavior` (the universal eater — monk branch added),
`EconomicBalance`/`BuildingStarterTable` (+constants), `MonasteryDeveloper.monksOf`
(now shared). The monk-scan is single-sourced; the channel-buy mirrors `executeBuy`
(a flagged future extraction of a shared building-level buy helper).

### Deviations from prompt

- **Surplus-sell credits the `BuildingEconomy` pool, not chest coins** — the
  reusable static `executeSellForWorkshop` deposits revenue as coins into the
  building's chest, which the buy path can't spend; the monastery pool IS the
  `BuildingEconomy` (matching R4 + the buy funding), so I reuse the sell FORMULA +
  market routing + `postListing` and credit the pool directly. Justified.
- **The input-buy mirrors `executeBuy`'s core** rather than calling it (it's a
  private instance method on `AbstractProductionBehavior`); the reusable pieces
  (`ChannelRouter.findBestChannel`/`registeredChannels`, `channel.execute`, the
  `BuildingEconomy` two-source funding) are reused. Extracting a shared
  building-level buy helper is flagged.
- **The food target is a fixed buffer (32), not literally monk-count-scaled** — the
  per-tick production behavior stays scan-free; the consumption rate scales the
  depletion with the monk count, and the economy's food safety net + input-buy keep
  a larger house fed. A monk-count-scaled target is a flagged refinement.
- **A monk uses the universal `EatMealBehavior`** (redirected), not a monastic
  refectory behavior — reuse over a new behavior, per the constraint.

### Out-of-scope but flagged

- **This completes the monk's CORE loop** (spawn → produce-by-skill → self-organize
  → eat + economy). Remaining R6: the **standalone-district worldgen** (layout
  rework); the **Abbot office** + the abbot's authority over the pool (offices pass
  — the pool is the `BuildingEconomy` this phase); **mead + a BREWING skill**; the
  in-game **MONASTERY-vs-ABBEY distinction** (both behave identically today).
- A shared building-level buy/sell helper (extract from `executeBuy`/the monastery
  economy); a monk-count-scaled food target; a monastic refectory/communal-meal
  behavior; routing surplus to alms/library like the temple's `spendSurplus`.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
Static review done: all channel/economy signatures confirmed verbatim against
source (`TradeIntent.buy` 8-arg, `ChannelRouter.findBestChannel`/`registeredChannels`,
`ChannelQuote(channel,intent,pricePerUnit,availableQuantity,travelTimeTicks,quoteValidUntilTick,location)`,
`EconomicChannel.type/execute`, `TradeResult(success,quantityTraded,totalBronze)`,
`Urgency.NORMAL`, `MarketPriceHelper.getDynamicSellPrice(level,village,item)`,
`VillageEconomy.postListing/getBasePrice`, `NpcWallet.receive/spend`,
`ProductionHelpers.findMarketInVillage`, `getOrCreateBuildingEconomy` +
`BuildingEconomy.withdraw/depositRevenue`); the agent-wallet conduit nets to zero
(receive − channel-spend − leftover-refund); the buy is affordability-capped
(qty ≤ 0.9·treasury/price) so funding never underflows; the eating redirect is
monk-gated (non-monks byte-unchanged); the economy is `isRiteVenue`-decoupled from
temple decay; the daily pass is guarded; a fresh monastery is starter-seeded.

### Smoke test (user-runnable)

1. **Monks eat from the shared store.** Stock bread in a monastery's store; at the
   monks' meal window, confirm they eat from the MONASTERY store (the bread count
   drops), not personal inventory, and that a monk with no personal food no longer
   starves.
2. **Self-feeding.** Give a monk BAKING + a furnace/smoker in the monastery + stock
   wheat; confirm it bakes bread into the store (need-priority), the others eat it,
   and an idle initiate is steered toward BAKING when bread is needed.
3. **Surplus → pool.** Let the monastery overproduce honey/books (above target);
   confirm the surplus is sold to market and the monastery's `BuildingEconomy`
   treasury rises (cross-check `/religion temple` near the monastery if it reads the
   pool, or observe inputs being bought next).
4. **Buy inputs.** With a baker but no wheat, confirm the monastery BUYS wheat from
   the pool (via the market/channels) so the baker can produce; with no baker and
   low bread, confirm it buys BREAD directly (the food safety net) so monks don't
   starve.
5. **Self-contained run.** Leave a stocked, amenity-equipped monastery with a few
   monks running for several in-game days; confirm it sustains — produces, eats,
   sells surplus, buys inputs, pays upkeep — without starving or going broke, and a
   productive house stays solvent.
6. **No regressions.** Confirm non-monk eating, HOME production, the profession
   behaviors, and the temple economy are unchanged; nothing crashes (the daily pass
   is guarded).

---

## D1 — the ReligionIdentity model + first authoring pass (2026-06-09)

(Religion Deepening, Pillar 1.)

### Disposition (findings)

The four faiths are mechanically distinct (R3) but thin on identity — no cosmology,
no deity with character, no sacred history, no virtues/taboos. D1 builds the MODEL
that holds a realized-culture identity per faith, seeds a first authored pass, and
a debug readout. Per the dead-content rule, the content is authorable + inspectable
NOW even though its behaviour consumer is later (D2).

1. **Registry pattern.** `ReligionContent` (R3a) is the model to mirror: a `final`
   class with a private static `Map<String, …>` keyed by `ReligionRegistry` ids
   (SUNSTEAD/THE_LOOM/TIDECALL/FORGE_CREED), hand-authored `build()` entries, static
   lookups — a parallel registry, NOT a `Religion` record/codec change. The four
   faiths' tenets/deity/rites live in `ReligionRegistry`.
2. **Reconciliation (don't duplicate/break the existing flavor fields).** The deity
   NAME stays the single source in `Religion.deity()` (consumed by
   `ReligionContent.invocation`); the identity adds the rich layer on top (a `Deity`
   = domain + character + demands + rewards, NO name). `Religion.coreTenets()` stay
   put (consumed by `ReligionContent.tenet`); the identity's `virtues` are the
   structured, concept-tagged version (distinct from the raw tenet strings, informed
   by them). The `/religion identity` readout pulls the name from `ReligionRegistry`
   and the rich layer from the identity — one source per attribute.
3. **D2 forward-consumer (the concept representation).** Virtues/taboos are stored
   as `{FaithConcept concept, String text}` so D2 can key behaviour on the concept.
   `FaithConcept` is a small, concrete, top-level enum — every value anchored to one
   of the four faiths' actual values (no speculative concepts), each mappable to an
   observable NPC act (working/idling, honest dealing/theft, sharing/hoarding,
   defending kin/fleeing, ancestor veneration, desecration, …).

### What shipped

- **`Npc/Religion/FaithConcept.java`** (new top-level enum) — the controlled
  vocabulary (16 values: HONEST_LABOUR/GENEROSITY/TRUTHFULNESS/HARMONY/
  RESPECT_THE_SEA/REMEMBRANCE/HONOUR_THE_ANCESTORS/LOYALTY/VALOUR + IDLENESS/GREED/
  DECEIT/DISCORD/RECKLESSNESS/COWARDICE/SACRILEGE), each with a `displayName()`.
  Every value is used by ≥1 faith (D2 is the behaviour consumer).
- **`Npc/Religion/ReligionIdentity.java`** (new) — the record schema (cosmology,
  `Deity` {domain, character, demands, rewards}, `SacredHistory` {foundingMyth,
  ordered `HistoryEvent`s}, `List<Virtue>`, `List<Taboo>`, `Aesthetics` {styleId,
  palette, iconography}, `List<String>` practices), a nested `DeityDomain` enum
  (SUN/SEA/FORGE/FATE — only the four faiths' domains), and the parallel registry
  (`get`/`all`/`build`) with the **first authored pass** for all four faiths.
- **`Commands/ReligionDebugCommand.java`** — `/religion identity <religion>` prints
  the faith's cosmology, deity (name reconciled from `Religion.deity()` + domain +
  character + demands + rewards), sacred history, virtues + taboos (with their
  concept tags), aesthetics, and practices.

**Authored first pass (consistent with each faith's existing tenets/deity/rites):**
- **Sunstead** (Sun-Mother, SUN): cosmology of the turning agrarian wheel; virtues
  HONEST_LABOUR + GENEROSITY; taboos IDLENESS + GREED.
- **The Loom** (no deity, FATE): the impersonal Pattern; virtues TRUTHFULNESS +
  HARMONY; taboos DECEIT + DISCORD.
- **Tidecall** (Sea-Mother, SEA): the deep that gives and takes; virtues
  RESPECT_THE_SEA + REMEMBRANCE; taboos RECKLESSNESS + SACRILEGE.
- **The Forge Creed** (First Forge-Father, FORGE): the line of iron ancestors;
  virtues HONOUR_THE_ANCESTORS + LOYALTY + VALOUR; taboos COWARDICE + SACRILEGE.

### Tie-In Audit

1. **Upstream feeders.** `ReligionRegistry` (the four faiths + their ids); the
   existing `Religion.deity()` NAME + `coreTenets()` — folded in (the readout reads
   the name from `ReligionRegistry`), NOT duplicated or broken. No `Religion` change.
2. **Downstream callers.** The `/religion identity` readout (the only consumer this
   phase). D2 (the forward consumer of `FaithConcept` virtues/taboos — the schema
   carries the concept tag it needs). `ReligionContent` (R3a) is unaffected — it
   still owns deity-NAME/tenet flavor + rite profiles; `ReligionIdentity` is a
   separate, additive content layer. R9 panels could surface identity later (not
   this phase).
3. **Sibling systems.** `ReligionContent`/`MonasticCrafts` — same parallel-registry
   pattern; the calendar/rites are untouched.
4. **Exhaustive switches.** `DeityDomain` is a new enum but is never exhaustively
   switched (rendered via `name()` in the readout); `FaithConcept` likewise
   (rendered via `name()`/`displayName()`). No exhaustive switch over either.
   Confirmed.

### Simplification Sweep

`ReligionIdentity` reuses the `ReligionContent` parallel-registry pattern; the deity
NAME + core tenets fold into the rich identity with one source per attribute (name
in `Religion`, domain/character/virtues in the identity — not duplicated). No new
registry framework. Classes in scope + inbound callers: `ReligionIdentity` (new;
1 — the `/religion identity` readout; D2 to come), `FaithConcept` (new; 1 — the
identity's virtues/taboos; D2 to come), `ReligionDebugCommand` (+1 subcommand),
`ReligionRegistry` (read-only, the name/id source). No duplicate identity copy.

### Deviations from prompt

- **`FaithConcept` is one shared vocabulary for BOTH virtues and taboos** (not two
  enums) — D2 judges actions against a single concept set, and several taboos are the
  inverse of a virtue; one controlled vocabulary is the cleaner key for D2.
- **The deity NAME is not stored in the identity** — it stays in `Religion.deity()`
  and the readout reconciles the two, so the name has exactly one source (the
  alternative — copying the name into the identity — would duplicate it).
- **`FaithConcept`/`DeityDomain` rendered via `name()` in the readout** (the concept
  TAG) so the smoke test can see what D2 keys on; `displayName()` is available for a
  prettier later surface.

### Out-of-scope but flagged

- **D2** — virtues/taboos → NPC behaviour/mood (map an observed act to a
  `FaithConcept`, approve/disapprove per the officiating faith's lists). The schema
  is structured for it (the concept tag is the hook); no behaviour wired here.
- **D3** — consuming deity/sacred-history/cosmology in dialogue/sermons/history.
- **D4** — consuming `Aesthetics` (style/palette/iconography) in the building NBT /
  visual hook.
- Surfacing identity in the R9 player/temple panels; promoting the first-pass
  authored text as Garrett refines it.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
Static review done: no `Religion` record/codec change (git diff clean on
`Religion.java`); `ReligionIdentity` mirrors the `ReligionContent` registry shape +
keys by `ReligionRegistry` ids; all four faiths authored; every `FaithConcept` (16)
and `DeityDomain` (4) value is used by ≥1 faith (no speculative/unused); the readout
resolves the deity name from `Religion.deity()` (reconciliation) and the rich layer
from the identity; the new enums are not exhaustively switched.

### Smoke test (user-runnable)

1. **Readout per faith.** Run `/religion identity sunstead`, `the_loom`, `tidecall`,
   `forge_creed`; confirm each prints a coherent cosmology, deity (name + domain +
   character + demands + rewards), sacred history (founding myth + ordered events),
   virtues, taboos, aesthetics, and practices, and that each reads as a distinct,
   refinable first pass.
2. **Concept tags visible.** Confirm each virtue/taboo line shows its `FaithConcept`
   tag (e.g. `[HONEST_LABOUR]`, `[COWARDICE]`) so D2 has a controlled value to key on.
3. **Reconciliation intact.** Confirm the deity NAME in the readout matches
   `Religion.deity()` (the Sun-Mother / the Sea-Mother / the First Forge-Father / "The
   Loom" for the deity-less Loom), and that R3a effects + the existing deity/tenet
   usage (rite invocations, confession tenet lines) still work unchanged.
4. **Unknown/unauthored.** `/religion identity <bad-id>` fails cleanly ("Unknown
   religion"); an authored-but-future id would report "No authored identity".

---

## D2 — virtues & taboos → NPC behaviour + mood (2026-06-09)

(Religion Deepening, Pillar 1 — the headline.)

### Disposition (findings)

D1 gave each faith authored virtues/taboos as `FaithConcept` tags. D2 makes them
bite: an adherent who lives a virtue gains piety + contentment + standing; one who
breaks a taboo feels guilt + is judged by co-religionist witnesses. Religion
becomes an active force on NPC psychology.

1. **Lookup + faith.** `ReligionIdentity.get(faith).virtues()/.taboos()` (D1, each a
   `{FaithConcept, text}`); the actor's + witnesses' faith via
   `PietyComponent.primaryReligion()`; `FaithReconciliation`'s co-religion notion —
   but the cleaner witness rule is "the witness's faith HOLDS the concept" (a
   co-religionist always does; a different faith sharing the value also does —
   exactly "co-religionist or holds the same value").
2. **Crime (primary taboo hook).** `CrimeReporter.commit → applySideEffects(report)`:
   the perpetrator is a UUID (NPC or player), witnesses are TownspersonMob NPCs (a
   16-block scan), and it already applies victim/witness mood (`CRIME_VICTIM`/
   `CRIME_WITNESSED`) + an NPC↔NPC −60 victim→perp relationship + a player-perp
   village-reputation drop. The faith overlay layers at the END of
   `applySideEffects` (witnesses + perpetrator known), ADDITIVE.
   `CrimeType` (13 values) → `FaithConcept` taboo map.
3. **Virtue hooks (discrete).** R5 `GraveVisit.contemplate` (the remembrance moment,
   when the visitor cares about the grave) → REMEMBRANCE; R4d-2
   `TempleProsperity.spendSurplus` after `distributeAlms` (the priest is the giver)
   → GENEROSITY.
4. **Effect channels.** `PietyComponent.adjustBelief` (clamped [0,1]); mood via
   `applyWithRawMagnitude` — `MoodTrigger` had no guilt/contentment, but its only
   internal switch (`traitMultiplier`) has a `default` arm, so adding values is safe
   → added `GUILT`/`CONTENTMENT`; NPC↔NPC judgment via
   `NpcRelationshipLedger.adjust(otherId, delta, tick, origin)` (clamped [-100,100]).
   `VillageReputation` is player↔village only, so it's NOT the witness-judgment
   channel — NPC↔NPC relationship is.

### What shipped

- **`Npc/Religion/FaithJudgment.java`** (new) — the one canonical helper:
  `judge(actor, FaithConcept, witnesses, now)` looks up the concept in the ACTOR's
  own faith — virtue → reward (piety +0.02 + `CONTENTMENT` mood), taboo → guilt
  (piety −0.02 + `GUILT` mood), neutral/atheist → nothing — then each witness whose
  OWN faith holds the concept judges the actor via NPC↔NPC relationship (+3 for a
  witnessed virtue, −4 for a witnessed taboo). Plus `conceptForCrime(CrimeType)`
  (theft→GREED, assault/murder→DISCORD, fraud/bribery/contract-breach/perjury/
  smuggling/tax-evasion/seal→DECEIT, vandalism→SACRILEGE; `default→null` for
  trespassing + future types).
- **`Npc/Mood/MoodTrigger.java`** — added `CONTENTMENT` (+6, cap 0.2) + `GUILT`
  (−8, cap 0.2). Safe (the lone internal switch has a `default`); no external
  exhaustive switch over `MoodTrigger`.
- **`Npc/Crime/CrimeReporter.java`** — the faith overlay at the end of
  `applySideEffects`: an NPC perpetrator whose crime is a taboo of THEIR faith feels
  guilt + co-religionist (or same-value) witnesses judge them, ON TOP of the existing
  crime effects.
- **`Village/Graveyard/GraveVisit.java`** — when an adherent who cares about a grave
  visits it, `judge(npc, REMEMBRANCE)` rewards a faith that esteems it (Tidecall) —
  a distinct channel from the universal grief-ease.
- **`Npc/Religion/TempleProsperity.java`** — after alms are distributed,
  `judge(priest, GENEROSITY)` rewards the giving priest's faith if it esteems it
  (Sunstead).

### Bounds (anti-spiral)

Per-act magnitudes are tiny and bounded by the existing clamps: piety ±0.02
(`adjustBelief` clamps [0,1]); mood ±6/8 under `MoodTrigger`'s daily cap (0.2 ⇒ ±20
mood/day max); witness relationship ±3/4 (`NpcRelationshipLedger` clamps [-100,100]).
A single sin is a dip + a modest nudge from the faithful, not ruin; a single virtuous
act is a nudge, not sanctification.

### Tie-In Audit

1. **Upstream feeders.** `ReligionIdentity`/`FaithConcept` (D1 — the virtue/taboo
   lookup), the hooked events (`CrimeReporter`, R5 `GraveVisit`, R4d-2 alms via
   `spendSurplus`), `PietyComponent` (the actor's/witnesses' faith).
2. **Downstream callers.** `CrimeReporter.applySideEffects` (overlay), `GraveVisit.
   contemplate`, `TempleProsperity.spendSurplus` — all call the ONE `FaithJudgment`
   helper. Effects hit `PietyComponent.adjustBelief`, mood (`CONTENTMENT`/`GUILT`),
   and `NpcRelationshipLedger`. No new event system.
3. **Sibling systems.** The crime/justice system is unmodified except the additive
   overlay (the existing reputation/mood/relationship effects are untouched — the
   faith effect is on top). R5 grave-visiting: REMEMBRANCE is a DISTINCT faith-virtue
   channel from the R5b grief-ease and the R5c Forge `venerate()` (Forge holds
   HONOUR_THE_ANCESTORS not REMEMBRANCE → only `venerate()`; no double-dip). R4d-2
   alms unchanged except the added priest reward. `FaithReconciliation` — the
   "holds the concept" rule supersedes a bare sameFaith check (covers cross-faith
   shared values).
4. **Exhaustive switches.** `conceptForCrime` switches over `CrimeType` with a
   `default` arm (safe as `CrimeType` grows); `MoodTrigger.traitMultiplier` has a
   `default` (new values safe). No exhaustive switch over either was broken.
   Confirmed.

### Simplification Sweep

One `FaithJudgment` helper; every hook just maps its event to a `FaithConcept` +
context (witnesses) and calls `judge`. The crime overlay reuses `CrimeReporter`'s
witness scan + reputation/mood; the grave/alms hooks reuse the existing call sites.
No new judgment/mood/reputation framework. Classes in scope + inbound callers:
`FaithJudgment` (new; 3 — CrimeReporter, GraveVisit, TempleProsperity), `MoodTrigger`
(+2 values; consumed by FaithJudgment), `ReligionIdentity`/`FaithConcept` (D1, the
lookup), `NpcRelationshipLedger`/`PietyComponent`/mood (effect channels, reused).

### Deviations from prompt

- **Witness rule is "the witness's faith holds the concept", not bare same-faith** —
  this is exactly the prompt's "co-religionist (or hold the same value)" and is the
  cleaner, faith-relative key (a Forge witness judges a Tidecall sacrilege because
  both hold SACRILEGE).
- **The GENEROSITY hook rewards the alms-distributing PRIEST** (the discrete NPC
  generous act available) — there is no NPC→NPC personal gifting today (GiveGiftVerb
  is player→NPC, so the giver is a player with no NPC mood). Only Sunstead priests
  benefit (faith-relative). NPC personal gifting is a flagged future hook.
- **Grave REMEMBRANCE only when the visitor cares about the grave** (a meaningful
  remembrance act); Forge's HONOUR_THE_ANCESTORS stays the existing R5c `venerate()`
  reward (complementary, no double-dip) — so D2 doesn't route HONOUR_THE_ANCESTORS
  through a new hook this phase.
- **Added `MoodTrigger.GUILT`/`CONTENTMENT`** (enum VALUES on an existing enum, not a
  new enum) — semantically correct + consumer-justified (FaithJudgment), and safe
  (the internal switch has a `default`; no external exhaustive switch).
- **Player perpetrators get no faith guilt** (players have no NPC mood); the player's
  own piety judgment is a flagged future extension.

### Out-of-scope but flagged

- **Continuous virtues/taboos** (HONEST_LABOUR / IDLENESS judged per work-cycle) — a
  daily-cadence follow-up (rate-limited), deliberately NOT per-tick this phase.
- **D3** — deity / sacred-history / cosmology consumption (dialogue, sermons).
- **D4** — aesthetics → building NBT / visuals.
- More action→concept hooks (NPC personal gifting → GENEROSITY; valour/loyalty/
  cowardice from combat; truthfulness/deceit from trade); routing player-actor faith
  judgment; HONOUR_THE_ANCESTORS via a dedicated D2 hook.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
Static review done: one `FaithJudgment` helper hit by all three hooks; signatures
confirmed (`adjustBelief` clamp [0,1], `applyWithRawMagnitude(trigger,mag,tick)`,
`NpcRelationshipLedger.adjust(uuid,int,tick,origin)` clamp [-100,100],
`RelationshipOrigin.MET_SOCIALLY/MET_IN_CONFLICT`, `primaryReligion()`); the crime
overlay is additive (after the existing effects, inside `applySideEffects`, with
`now`/`type`/`perpetratorId`/`report` in scope) and skips player/atheist/neutral
cases inside `judge`; `conceptForCrime` + `MoodTrigger.traitMultiplier` both have a
`default` arm (no exhaustive-switch break from the +2 MoodTrigger values); the grave
REMEMBRANCE channel is distinct from grief-ease + `venerate()` (no double-dip);
magnitudes are small + clamped (anti-spiral).

### Smoke test (user-runnable)

1. **Taboo guilt + witness judgment.** Make a devout Sunstead NPC (GREED is its
   taboo) steal in view of co-religionist Sunstead NPCs; confirm — ON TOP of normal
   crime handling — the thief gets a GUILT mood dip + a small piety loss, and the
   Sunstead witnesses' opinion of the thief drops (check their NPC relationship).
   Confirm a Sunstead NPC committing ASSAULT (DISCORD — NOT a Sunstead taboo) gets
   NO faith guilt (faith-relative).
2. **Cross-faith / atheist witnesses.** Confirm a witness of a different faith that
   does NOT hold the violated taboo does not judge; an atheist witness never judges;
   and a different-faith witness that DOES share the taboo (e.g. SACRILEGE held by
   both Tidecall and Forge) does judge.
3. **Virtue rewards.** Have a Tidecall adherent visit a loved one's grave → confirm a
   REMEMBRANCE reward (CONTENTMENT mood + small piety) distinct from the grief-ease;
   let a Sunstead priest distribute alms → confirm a GENEROSITY reward.
4. **Bounded.** Confirm a single sin doesn't tank an NPC (piety/mood/relationship move
   only a little) and a single virtue doesn't sanctify; repeated faith-mood within a
   day is daily-capped.
5. **No regressions.** Confirm normal crime/grave/alms behaviour is otherwise
   unchanged (the faith effect is additive), a Forge venerator still gets the R5c
   `venerate()` reward (not double), and R3a/existing religion behaviour is intact.

## D3 — scripture (the lore becomes readable) (2026-06-09)

(Religion Deepening, Pillar 1 — the lore made tangible.)

### Disposition (findings)

D1 authored each faith's cosmology, founding myth + key events, deity character,
and virtues/taboos (`ReligionIdentity`). D2 made the virtues/taboos *bite*. D3
makes the lore *legible* — it turns that identity into readable in-world
**scripture** and puts it where books already live: the temple/village library
(R4d-2 stocking) and a monk's copying work (R6b `COPY_MANUSCRIPT`).

1. **Book infra (reuse, no parallel system).** `ScribalItems.book(title, author,
   body, BookCategory, topics, authorNpcId, skillBuff, tick)` already paginates a
   plain body (256 chars/page) into a readable WRITTEN_BOOK (vanilla
   `WrittenBookContent` + mod `ExtendedBookContent`). `BookRecord` is catalogue
   metadata (no page text — the borrow→readable path is still a stub, so the
   physical stack is the true readable artifact). `LibraryCatalogue.acquire` stocks
   records. Mirror `ProceduralBookFactory`: compose a body → `ScribalItems.book`.
2. **Faith resolution.** `BuildingFaith.resolveFaith(level, village, building)` —
   the same resolver R4d-2/R6 already use; no new faith plumbing, no `Religion`
   codec change.
3. **Two wire sites.** R4d-2 `TempleProsperity.stockLibraryBook` previously authored
   a *generic subject* book by rotating `preferredBookCategories`; R6b monks
   deposited a plain `BOOK` for `COPY_MANUSCRIPT`. Both become the faith's own
   scripture.
4. **Production hook (no pipeline fork).** The monk runs the shared
   `ContextProductionBehavior` phase machine; only the *deposited stack* needs to
   differ for one recipe. Added a `producedStack(...)` seam (default = the recipe's
   plain output) so HOME stays byte-exact and only the monk overrides it.

### What shipped

- **`Npc/Religion/ScriptureFactory.java`** (new) — the generator. Composes a body
  from `ReligionIdentity` (cosmology → the deity, named from `Religion.deity()`
  with character/demands/rewards → founding myth + key events → `We hold:` virtues
  → `We forbid:` taboos → `We keep:` practices) and renders it three ways:
  `scriptureStack(religionId, copierId, now)` → a readable WRITTEN_BOOK ItemStack
  (via `ScribalItems.book`, `BookCategory.RELIGIOUS`); `scriptureRecord(...)` → a
  catalogue `BookRecord` (page count derived from the capped body); `title(id)` =
  "The Book of <deity>" (or the faith name for a deity-less faith like the Loom).
  Page-capped at 14 pages (`MAX_CHARS = 14×256`, truncated). Graceful fallback to a
  short name + core-tenets body when a faith has no authored identity — never empty.
  Topics `religion.<id>` / `scripture` / `category.religious`.
- **`ContextProductionBehavior.java`** (edit) — added the `producedStack(level,
  entity, building, recipe)` seam (default returns `new ItemStack(recipe.output(),
  recipe.outputCount())`); `tickDepositing` now deposits `producedStack(...)`
  instead of the inline `new ItemStack`. Pure refactor; behaviour identical for
  every context that doesn't override.
- **`MonkProductionBehavior.java`** (edit) — overrides `producedStack`: for
  `SkillRecipes.COPY_MANUSCRIPT`, resolves the monastery's faith
  (`BuildingFaith.resolveFaith`) and deposits `ScriptureFactory.scriptureStack(faith,
  copierId = the monk, now)`; any other recipe (or no resolvable faith) falls back to
  `super` (plain output). Monks now copy their order's scripture.
- **`TempleProsperity.stockLibraryBook`** (edit) — replaced the generic
  category-rotation `BookRecord` with `ScriptureFactory.scriptureRecord(faith,
  priest, now)`; a Sunstead temple's library now holds Sunstead scripture. Kept the
  budget/solvency gate, the `RELIGIOUS_BOOK_CAP` count gate, and `BOOK_COST`. Removed
  the now-dead `readable(BookCategory)` helper + the `BookCategory` import; the
  `preferredBookCategories` field is now unused *by this path* (left on `Religion`
  for any future consumer — no codec change).

### Tie-in audit

1. **Upstream feeders.** `ReligionIdentity` (D1) + `Religion` (deity name) feed the
   body; `BuildingFaith.resolveFaith` feeds the faith id; `ScribalItems`/`BookRecord`/
   `LibraryCatalogue` are the unchanged sinks. No feeder changed shape.
2. **Downstream callers.** `producedStack` has exactly two impls (base default +
   monk) and one call site (`tickDepositing`). `ScriptureFactory` has two callers
   (monk stack, temple record). `stockLibraryBook` keeps its single caller
   (`spendSurplus`) and its `long`-bronze contract. `HomeProductionBehavior` does
   NOT override `producedStack` → HOME deposit byte-exact (verified by grep).
3. **Sibling systems.** Library borrow/read (`LibraryCatalogue`) consumes the
   record unchanged (still metadata; topics now carry `religion.<id>`/`scripture` so
   the existing `startsWith(RELIGION_TOPIC)` cap filter still counts them). Monastery
   store + `MonasticCrafts.need`/quota for `COPY_MANUSCRIPT` are unchanged — the
   monk still produces one "manuscript" unit per cycle; only the stack's identity
   differs.
4. **Exhaustive switches.** None touched — no enum/sealed type added or changed
   (`BookCategory.RELIGIOUS` already existed; no new `MoodTrigger`/`Profession`/
   `BuildingType` values).

### Simplification sweep

- D3 *removes* a code path rather than adding one: `stockLibraryBook` lost the
  category-rotation branch + the `readable` helper, collapsing to a single
  scripture-record call. Net classes added: 1 (`ScriptureFactory`), with three
  inbound callers across two subsystems — not an orphan.
- The `producedStack` seam is the minimal generalization (one protected method, one
  override) — it avoids a second copy of the deposit/XP/log machine for the monk,
  consistent with the M2/R6b "one primitive, thin context routers" split.
- No overlapping pair introduced: `ScriptureFactory` is the *only* scripture
  generator; `ProceduralBookFactory` (generic subject books) is untouched and still
  serves non-religious stocking elsewhere.

### Deviations from prompt

None. Scripture generator + both wire sites (temple stocking R4d-2, monk
`COPY_MANUSCRIPT` R6b) delivered as specified; faith via `BuildingFaith`; book infra
reused; page-capped; no `Religion` codec change.

### Out-of-scope but flagged

- **D3 follow-ups (deferred by the prompt):** deity/cosmology surfacing in NPC
  *dialogue*/sermons (NPC speech layer), and sacred history flowing into the
  village *history/records* systems + festival lore. Scripture is the readable
  artifact; wiring it into spoken/recorded lore is the next pass.
- **D4 (aesthetics):** `ReligionIdentity.Aesthetics` (styleId/palette/iconography)
  is authored but not yet consumed (book cover styling, building dressing) — D4.
- **Borrow→readable stub:** the library borrow path returns a stub, not the page
  text, because `BookRecord` carries no body. The monk's physical scripture stack
  IS readable today; making *borrowed* library copies readable means persisting the
  body (or regenerating it from the `religion.<id>` topic on borrow) — flagged, not
  in scope.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
Static review done: `ScribalItems.book(String,String,String,BookCategory,List,
Optional<UUID>,Optional<SkillBuff>,long)` and `BookRecord(UUID,String,String,
Optional<UUID>,List<String>,Optional<Skill>,int,long,int)` signatures match the
factory calls; `ReligionIdentity` accessors used (`cosmology`,`deity().{domain,
character,demands,rewards}`,`history().{foundingMyth,events}`,`virtues().text`,
`taboos().text`,`practices`) all exist; `producedStack` has one call site + the
base default + the single monk override; `HomeProductionBehavior` does not override
it (HOME byte-exact); removed `readable`/`BookCategory` import are not referenced
elsewhere.

### Smoke test (user-runnable)

1. **Monk copies scripture.** Stock a monastery with `COPY_MANUSCRIPT` inputs and a
   literate monk in a faith-bearing monastery; let it work, then open the deposited
   book — confirm it is a readable WRITTEN_BOOK titled "The Book of <that faith's
   deity>" whose pages are that faith's cosmology/myth/tenets (not a blank `BOOK`).
2. **Per-faith distinctness.** Repeat in monasteries of two different faiths;
   confirm the two scriptures read differently (Sunstead Sun-Mother vs. Forge
   ancestors) and the deity-less Loom's book is titled by the faith name.
3. **Temple library stocks scripture.** Let a solvent temple with surplus + a
   village LIBRARY run its stocking; confirm the library catalogue gains the faith's
   scripture record (topic `religion.<id>`/`scripture`), the `RELIGIOUS_BOOK_CAP`
   still caps it, and `BOOK_COST` is withdrawn from the treasury.
4. **Graceful + bounded.** Confirm a faith with no authored identity still produces a
   non-empty (shorter) book, and a very long identity is page-capped at 14 pages.
5. **No regressions.** Confirm HOME-context homestead production deposits its normal
   item unchanged, non-`COPY_MANUSCRIPT` monk crafts deposit their normal outputs,
   and existing (non-religious) library stocking elsewhere is unaffected.

## D3b — the faith's voice (dialogue + sermons) (2026-06-09)

(Religion Deepening, a D3 follow-up — the lore made audible.)

### Disposition (findings)

D1 gave each faith an identity, D2 behavioural teeth, D3 readable scripture. D3b
gives it a *voice* — a Sunstead priest should greet you differently from a Tidecall
one — drawn straight from `ReligionIdentity`, gated by who actually holds the faith.

1. **Injection point.** `NpcDialogue.getGreeting` is a weighted cascade: the
   dialogue tree runs first (`DialogueRunner.lineFor`), then event → reputation →
   need → season → **profession** (~line 254 switch) → trait → fallback, each later
   pool gated by an `rng.nextInt(n)==0` roll so it's "one of several flavours". The
   clean injection is one more weighted pool here — NOT a tree fork and NOT a hard
   override (the prompt's invariant). Placed at step 4.5 (after season, before
   profession) so a clergy NPC voices faith readily but the profession/trait/season
   colour still surfaces.
2. **Voice source.** `ReligionIdentity` (D1) carries everything needed: the deity
   `domain`/`character`/`demands`/`rewards` (the NAME stays single-sourced in
   `Religion.deity()`), the authored `virtues` (complete sentences), and — for the
   deity-less Loom (`Religion.deity()` is `Optional.empty()`) — the abstract
   Pattern/fate idiom. `ReligionContent.invocation` already proves the
   name-or-abstract pattern; the voice reuses `Religion.deity()` directly.
3. **Eligibility.** `PietyComponent.primaryReligion()` (has a faith?) +
   `primaryTier()` (`UNAFFILIATED`/`FAITHFUL`/`DEVOUT`/`PIOUS`). Rule: clergy
   (PRIEST/MONK) always; lay only DEVOUT/PIOUS; everyone else silent (faith voice
   returns empty → normal lines). The lay-vs-clergy *frequency* is the call-site
   gate (clergy 1/2, lay 1/5), keeping it "one weighted source".

### What shipped

- **`Npc/Religion/FaithVoice.java`** (new) — the single faith-voice line source:
  - `speaks(npc)` — eligibility (clergy always; lay DEVOUT/PIOUS; else false).
  - `isClergy(npc)` — PRIEST/MONK (the call-site frequency split).
  - `line(npc, rng)` — builds a small varied pool **from the speaker's
    `ReligionIdentity`** and returns one at random: a domain-idiom greeting (SUN →
    "The Sun-Mother's light upon you, traveller."; SEA → "…tides carry you safely.";
    FORGE → "…iron at your back…"; FATE/Loom → "May your thread run true in the
    Pattern."), a blessing (`"May " + deity + " grant you " + rewards`, or the
    abstract "Weave true…" for the Loom), what the faith asks (`deity + " asks " +
    demands`), and a virtue spoken plainly (a random authored `Virtue.text()`). The
    deity name is single-sourced from `Religion.deity()`; the deity-less Loom uses
    "the Pattern" with no personification. A faith with no authored identity falls
    back to its `coreTenets` (still on-faith). Empty for ineligible NPCs / unknown
    religion.
- **`Entities/NpcDialogue.java`** (edit) — added step 4.5 in `getGreeting`: consult
  `getFaithLine(npc, rng)` (a thin shim over `FaithVoice.line`) and, when present,
  return it on a `rng.nextInt(clergy ? 2 : 5) == 0` roll — clergy readily, devout
  laity occasionally. Additive; every existing pool and gate is unchanged.

### Tie-in audit

1. **Upstream feeders.** `ReligionIdentity` (voice content), `Religion.deity()`
   (name, single source), `PietyComponent.primaryReligion()/primaryTier()` (faith +
   devoutness). None changed shape; all read-only.
2. **Downstream callers.** `FaithVoice` has exactly one caller (`NpcDialogue`'s new
   step 4.5 via `getFaithLine`). `NpcDialogue.getGreeting`'s callers
   (`mobInteract`, greeting prefixes, `NpcProfileSnapshotBuilder`) are unchanged —
   the method's contract (never-null greeting) holds; the faith pool only ever
   *adds* a possible return, never removes the existing fallbacks.
3. **Sibling systems.** Composes with profession/trait/season/event/need/reputation
   pools (faith is one more weighted source, reached only when the earlier pools
   don't fire and the roll hits). The dialogue tree (`DialogueRunner`) still runs
   first and is untouched (no fork). R9's priest panel and D1–D3 are unaffected
   (read-only reuse of identity/piety).
4. **Exhaustive switches.** One new local switch over `ReligionIdentity.DeityDomain`
   (4 arms: SUN/SEA/FORGE/FATE) — all arms covered, no `default` (a future domain
   would force a compile-time update here, which is desirable). No existing
   exhaustive switch over `Profession`/`Religion`/any enum was touched (the
   PRIEST/MONK check is two `==` comparisons, not a switch).

### Simplification sweep

- One faith-voice source (`FaithVoice`) drawn from `ReligionIdentity`, consulted
  from a single `NpcDialogue` site — faith logic is NOT scattered across the
  dialogue code. Classes in scope: `FaithVoice` (new, 1 inbound caller),
  `NpcDialogue` (1 new private shim + 1 cascade step), `ReligionIdentity`/`Religion`/
  `PietyComponent` (read-only sources). No orphan, no overlapping pair: this is the
  only NPC-speech faith source; `ReligionContent.invocation/tenet` remain the
  rite-text helpers (distinct consumer — rite flavor, not NPC greetings).
- No dead code introduced; the removed `personified` param on `domainGreeting` was
  pruned during authoring.

### Deviations from prompt

None. Faith-aware greeting/idle lines for priests + devout adherents, selected by
the NPC's faith, sourced from `ReligionIdentity`, injected as one weighted source
(no tree fork, no hard override); deity-less Loom uses abstract phrasing; lukewarm/
unaffiliated/atheist speak normally; no `Religion` codec change, no new memory.

### Out-of-scope but flagged

- **D3c** — sacred history → village records + commemorative festivals (the
  `SacredHistory` events feeding history/log systems and festival lore). Not touched
  here; flagged for the next pass.
- **D4** — aesthetics (`ReligionIdentity.Aesthetics`), layout-parked.
- **Full sermon/conversation trees** — this phase is greeting/idle lines only (the
  prompt's "start with greeting/idle lines"). A dedicated multi-turn sermon flow in
  the dialogue tree (`DialogueRunner` options) is a later, larger piece.
- The faith voice currently surfaces through `NpcDialogue.getGreeting` (the
  shim-over-tree path). If/when a faith-specific *tree* is authored, `FaithVoice`
  is the natural content source for it — no rework needed, just an additional
  consumer.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
Static review done: `Religion.deity()` → `Optional<String>` (single-sourced name),
`coreTenets()` → `List<String>`, `PietyComponent.primaryReligion()` → `Optional`,
`primaryTier()` → `PietyTier{UNAFFILIATED,FAITHFUL,DEVOUT,PIOUS}`,
`ReligionIdentity` accessors (`deity().{domain,demands,rewards}`, `virtues().text`)
all exist; the `DeityDomain` switch covers all four arms; `FaithVoice` has the one
`NpcDialogue` caller; the new cascade step is additive (the never-null greeting
contract holds).

### Smoke test (user-runnable)

1. **Sunstead priest.** Talk to a Sunstead PRIEST repeatedly; confirm faith-flavoured
   lines that invoke the Sun-Mother (a "Sun-Mother's light upon you" greeting, a
   "May the Sun-Mother grant you…" blessing, "The Sun-Mother asks…", or an honest-
   labour/generosity virtue) mixed with the occasional normal line.
2. **Tidecall priest.** Confirm a Tidecall priest invokes the Sea-Mother / the tides
   and the respect-the-sea / remembrance virtues — distinctly different from #1.
3. **Loom priest (deity-less).** Confirm a Loom priest speaks of the Pattern / thread
   / weaving with NO deity name (no "Mother"/"Father"), e.g. "May your thread run
   true in the Pattern." and the truthfulness/harmony virtues.
4. **Devout lay adherent.** Talk to a DEVOUT/PIOUS non-clergy adherent several times;
   confirm their faith colours their lines *occasionally* (less often than a priest),
   still mixed with profession/trait lines.
5. **No preaching from the unfaithful.** Confirm a FAITHFUL-but-lukewarm lay NPC, an
   UNAFFILIATED NPC, and an atheist (no primary religion) speak only normal lines —
   never a faith line.
6. **Varied + distinct.** Confirm a single priest doesn't repeat one line every time
   (the pool varies) and two faiths' priests read clearly differently.
7. **No regressions.** Confirm normal profession/trait/season/event/need/reputation
   dialogue still works (faith voice is additive — it only sometimes wins), and the
   dialogue-tree path is unchanged.

## D3c — sacred history in the world (records + commemoration) (2026-06-09)

(Religion Deepening, the final doable deepening phase — D4 aesthetics is
layout-parked.)

### Disposition (findings)

D1 authored each faith's `SacredHistory` (founding myth + ordered key events); it
has been inert lore. D3c gives it presence: the faith's origins enter the world's
chronicle, and its calendar festivals announce *what* they commemorate.

1. **History systems.** `Village/History/`: `VillageHistoryLog` (per-world
   SavedData, `Map<villageId, List<HistoryEntry>>`, `add`/`record`/`byType`/
   eviction). `HistoryEntry` renders its one-line `summary` from
   `HistorySummaryTemplates` (a `{token}` substituting map, `getOrDefault` FALLBACK).
   `HistoryEventType` (typed slots; the three Religion slots —
   `TEMPLE_CONSECRATED`/`HIGH_PRIEST_ANOINTED`/`FEAST_DAY_CELEBRATED` — have **no
   producer yet**, so there is no existing faith→history hook to piggyback). The
   `/history` command reads `renderedSummary`. Eviction: MAJOR/LEGENDARY never
   pruned. The `Lore/` system (`KingdomHistoryData`/`ChronicleGenerator`) is the
   separate AI-prompt kingdom chronicle.
2. **Dedupe gotcha.** `VillageHistoryLog.add` dedupes against the **tail** only,
   keyed on `type + tick + relatedNpcIds`. Seeding several same-type entries at one
   tick with empty NPC lists would collapse to one — so seeded entries must stagger
   their tick.
3. **Festivals.** R3b/R3d faith festivals are `VillageEvent`s scheduled in
   `VillageEventScheduler`; every faith festival passes through one chokepoint —
   `scheduleEvent(…, faithId, venueLocation)` — which also emits the player-facing
   "A {TYPE} will begin soon in {village}!" announcement. The faith festival
   `EventType`s (`FIRST_FURROW`, `HARVEST_HOME`, `THREAD_BINDING`, `GREAT_WEAVING`,
   `VOYAGE_BLESSING`, `TIDES_RETURN`, `ANCESTOR_OATH`, `FOUNDING_DAY`) map cleanly
   onto the authored `SacredHistory` event titles. `faithId` is set on every
   per-faith gathering (signature/grand/vigil); generic/secular events pass null.

**Design.** (1) **Records** — seed a faith's founding myth + key events into the
village log **once**, at the `scheduleEvent` chokepoint when a faith-stamped
gathering first fires (the village demonstrably observes that faith), idempotency-
guarded by a `byType(SACRED_HISTORY)` check, staggered ticks to beat the tail-
dedupe. One new MAJOR `HistoryEventType.SACRED_HISTORY` (never pruned, bounded).
(2) **Commemoration** — enrich the existing announcement with the commemorated
event resolved from `ReligionIdentity`; festival types with no mapped event keep
the plain announcement. No persistence change to festivals, no new festivals.

### What shipped

- **`HistoryEventType.SACRED_HISTORY`** (new value, MAJOR) + its
  `HistorySummaryTemplates` template `"{summary}"` (the producer composes the lore
  text and passes it in the `summary` detail). MAJOR → never pruned;
  `propagatesToKingdom()`'s `default -> false` arm keeps it village-level (no churn).
- **`ReligionIdentity.eventByTitle(religionId, title)`** (new static) — resolves a
  `SacredHistory` event by title (case-insensitive), the commemoration lookup.
- **`Npc/Religion/FaithHistory.java`** (new) — `seedSacredHistory(level, village,
  religionId, now)`: idempotent (no-op if the village already has SACRED_HISTORY,
  or the faith has no identity), seeds the founding myth then the key events (≤
  `MAX_EVENTS`=4) as MAJOR `SACRED_HISTORY` entries with staggered ticks, summary =
  `"<faith> — <myth>"` / `"<title> — <text>"`. Reuses `VillageHistoryLog.record`.
- **`VillageEventScheduler.scheduleEvent`** (edit) — (a) after `addEvent`, when
  `faithId != null`, calls `FaithHistory.seedSacredHistory(...)` (idempotent seed);
  (b) the announcement now appends a `" — in memory of {title}: {text}"` suffix
  via `commemorationSuffix(faithId, type)` → `commemoratedEventTitle(type)` (a
  defaulted `switch` mapping the 8 faith festival types to their sacred-history
  event title) → `ReligionIdentity.eventByTitle`. A null mapping → plain
  announcement (unchanged).

### Tie-in audit

1. **Upstream feeders.** `ReligionIdentity.sacredHistory` (D1, lore source),
   `BuildingFaith`/`ReligionContent.villageReligionId` (the festival's `faithId`,
   already resolved upstream of `scheduleEvent`), the faith calendar (drives which
   festival fires). None changed shape; all read-only.
2. **Downstream callers.** `FaithHistory` has one caller (`scheduleEvent`).
   `SACRED_HISTORY` is read by the generic `/history` readout + eviction (MAJOR →
   retained) with no special-casing. `eventByTitle` has one caller
   (`commemorationSuffix`). `scheduleEvent`'s contract is unchanged (same args, same
   side effects plus the additive seed + a longer announcement string).
3. **Sibling systems.** The live history producers (`HistoryProducer`, lifecycle/
   crime/law) are untouched — sacred history is a distinct MAJOR type seeded once,
   so it neither crowds nor is crowded out. R3d festival **scheduling** is unchanged
   (commemoration only enriches the announcement text); the blessing-rite attach,
   fronting, and crowd-bless paths are untouched. The kingdom `Lore/` chronicle is
   not modified. R9 panels could later surface SACRED_HISTORY but aren't required.
4. **Exhaustive switches.** No *exhaustive* switch broke: the only switch over
   `HistoryEventType` (`propagatesToKingdom`) has `default -> false`; the new
   `commemoratedEventTitle` switch over `VillageEvent.EventType` has `default ->
   null` (not exhaustive by design — new festival types simply get no
   commemoration). Confirmed by grepping every `HistoryEventType`/`EventType`
   switch.

### Simplification sweep

- Records reuse the history system (one new typed slot + the existing
  `VillageHistoryLog.record`/templates); commemoration reuses the existing festival
  announcement path (one suffix); both source from `ReligionIdentity`. No parallel
  store, no new festival, no duplicated lore (the text lives only in D1's identity).
- Classes in scope: `FaithHistory` (new, 1 caller), `ReligionIdentity` (+1 static),
  `HistoryEventType`/`HistorySummaryTemplates` (+1 value/template),
  `VillageEventScheduler` (the seed call + 2 private announcement helpers). No
  orphan, no overlapping pair — `FaithHistory` is the sole faith→chronicle bridge.

### Deviations from prompt

None. Both deliverables shipped: sacred history → village chronicle (seeded once,
bounded, MAJOR), and commemorative festivals (the announcement names the
sacred-history event it remembers; unmatched festivals keep their flavor). Reused
`Village/History` + the festival announcement path; sourced from
`ReligionIdentity.sacredHistory`; no `Religion` codec change; no new brain memory;
one new enum value with a concrete consumer.

### Out-of-scope but flagged

- **D4 aesthetics** (`ReligionIdentity.Aesthetics`) — layout-parked; rides the
  layout rework + authored NBTs. This closes the doable deepening (pillar 1).
- **Next pillars** — pillar 2 (interreligious relations) and pillar 3 (the divine
  layer) are the larger follow-on bodies of work.
- **Kingdom-chronicle propagation** — SACRED_HISTORY stays village-level
  (`propagatesToKingdom` false); surfacing faith origins in the `Lore/` AI kingdom
  chronicle (`ChronicleGenerator`) is a possible later enrichment, not done here.
- **Seeding trigger timing** — seeding fires on the first faith-stamped gathering
  (signature/grand/vigil), i.e. the first time the village celebrates the faith.
  An earlier hook (e.g. temple consecration) would need a new consecration→history
  producer (none exists today); flagged, not built.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
Static review done: `VillageHistoryLog.record(type,villageId,tick,details,npcs,
importance)` + `byType(villageId,type)` exist; all 8 referenced `VillageEvent
.EventType` constants exist; the 8 `commemoratedEventTitle` titles exactly match the
authored `ReligionIdentity` event titles (First Furrow / Harvest Concord / First
Threading / Great Weaving / First Catch / Tides' Return / Anvil Vigil / Founding
Day); `SACRED_HISTORY` has a template and a never-pruned (MAJOR) importance;
staggered ticks dodge the tail-dedupe; the idempotency guard prevents re-seeding.

### Smoke test (user-runnable)

1. **Faith origins in the chronicle.** In a Sunstead village, let a faith festival
   fire (or wait for its signature day), then `/history list <village>` — confirm
   SACRED_HISTORY entries appear: the founding myth ("Sunstead of the Reach — When
   the first field went hungry…") + key events (The First Furrow, The Long Winter,
   The Harvest Concord). Confirm a Tidecall or Forge village shows *different*
   origins (Sea-Mother / First Forge-Father).
2. **Seeded once.** Trigger several more faith festivals in that village and re-run
   `/history list` — confirm the sacred-history entries are NOT duplicated (seeded
   once), and that they survive a `/history prune` (MAJOR, never pruned).
3. **Commemorative festival.** Advance to a faith's grand festival (e.g. Forge
   Founding Day) near the village; confirm the on-screen announcement reads
   "A FOUNDING DAY will begin soon in <village>! — in memory of Founding Day: The
   oath that bound the living to the line of the dead." Confirm a Tidecall Tides'
   Return / Sunstead signature names ITS event, distinctly.
4. **Unmatched festivals keep flavor.** Confirm a generic seasonal festival
   (Harvest Festival, Market Day) or a Vigil announces plainly with no "in memory
   of" suffix.
5. **No regressions.** Confirm normal history producers (births, crimes, offices)
   and festival scheduling/fronting/crowd-bless are otherwise unchanged; the live
   chronicle isn't flooded; per-faith content is distinct.

## V1 — Divine Favour foundation (Divine Layer, Pillar 3) (2026-06-09)

(First phase of the divine layer — the resource miracles/visions/wrath will
later spend or gate on.)

### Disposition (findings)

Before miracles we need the resource: **Divine Favour** — a per-deity measure of
a deity's regard for the player. Piety is *belief*; favour is *standing* — built
on piety but distinct and spendable.

1. **Player religion data** lives on `RiteSavedData` (per-world SavedData): a
   `Map<UUID, PietyComponent> playerPiety` + the R4d-1 `autoTitheTemple` map, both
   `optionalFieldOf` (codec at 3 fields — room under the 16 cap). Favour is a
   sibling per-player structure here.
2. **`ReligionIdentity.deity` (D1)** carries `demands`/`rewards` (prose) and the
   **structured** form — the faith's authored `virtues` (`FaithConcept`-tagged).
   The virtues ARE the machine-readable "what the deity esteems", so favour weights
   an act by whether its concept is one of the faith's virtues.
3. **Earning acts** that fire discrete player events: `MakeOfferingVerb` (resolves
   `religionId` + `playerId`), `AttendRiteVerb` (`targetFaith`), `CommissionRiteVerb`
   (`faith`), and the R4d-1 player auto-tithe (`Tithing.tickPlayerTithes`, `faith`).
   **Pilgrimage is NPC-only** (no player pilgrimage verb), so it is not a player
   hook this phase. D2's player-virtue side is thin (no live player-virtue trigger).
4. **Surfacing**: R9c `PlayerReligionSnapshotBuilder` → `OpenPlayerReligionPacket`
   (manual StreamCodec) → `PlayerReligionScreen`. Add favour-per-deity alongside
   piety.

**Model chosen — one lazy relaxation formula (no per-tick scan).** Favour is
stored per (player, deity) as `(amount, lastTick)` and relaxed on read toward a
piety-tier **equilibrium** with τ ≈ 3 in-game days: `v' = eq + (v−eq)·e^(−Δt/τ)`,
clamped to `[0, cap]`. One formula yields all three required behaviours —
**passive accrual** (idle devout player rises to the tier equilibrium), **gentle
decay** (favour earned above eq by service drifts back to eq), and **lapse-fade**
(losing the faith drops the tier → eq = cap = 0 → favour fades). Tier is computed
from the player's belief in **that** faith, so favour is genuinely coupled to
piety and per-deity. Deity-demand weighting: an act whose `FaithConcept` is one of
the faith's virtues earns ×1.5.

### What shipped

- **`Npc/Religion/PlayerFavour.java`** (new) — pure per-player data: `Map<religionId,
  Entry(amount, lastTick)>` + CODEC (nested record codec; `set` drops non-positive
  entries). Persisted via `RiteSavedData`.
- **`Npc/Religion/DivineFavour.java`** (new) — the one economy helper:
  `FavourAct` enum (OFFERING/TITHE/ATTEND_RITE/COMMISSION_RITE/PILGRIMAGE/VIRTUE,
  each base + concept); `award` (deity-weighted, piety-capped — the verbs/tithe
  call it), `awardVirtue(concept)` (ready for player-virtue hooks), `current`
  (relaxed read), `spend` (V2's entry point), `debugGrant` (raw, cap-bypassing for
  testing). Per-tier `equilibrium` (0/5/15/30) and `cap` (0/30/60/100) as exhaustive
  `PietyTier` switches; alignment via `ReligionIdentity.virtues()`.
- **`RiteSavedData`** — +1 codec field `playerFavour` (`optionalFieldOf` → pre-V1
  saves load empty; now 4 fields, under the cap) + `getOrCreatePlayerFavour` /
  `getPlayerFavour`.
- **Earning hooks** — `MakeOfferingVerb` (OFFERING), `AttendRiteVerb` (ATTEND_RITE),
  `CommissionRiteVerb` (COMMISSION_RITE), `Tithing.tickPlayerTithes` (TITHE), each
  one `DivineFavour.award(...)` after the existing piety bookkeeping. Additive — no
  existing behaviour changed.
- **Surfacing** — `OpenPlayerReligionPacket` +`favourSummary` field (StreamCodec
  write/read appended); `PlayerReligionSnapshotBuilder` builds it from
  `DivineFavour.current` across the player's faiths; `PlayerReligionScreen` renders
  a "Favour: <deity> NN" line in the faith block.
- **Debug** — `/religion favour [view] | grant <faith> <amt> | spend <faith> <amt>`
  (operates on the executing player; `grant` is the raw cap-bypassing test grant,
  `spend` exercises the V2 API).

### Tie-in audit

1. **Upstream feeders.** The earning acts (offering/tithe/attend/commission),
   `ReligionIdentity.virtues()` (the deity-demand weighting), `PietyComponent` (the
   per-faith tier favour is capped/baselined by). All read-only; none changed shape.
2. **Downstream callers.** `RiteSavedData` persists it; the R9c packet/screen
   surface it; `DivineFavour.spend` is V2's future consumer (exercised now by the
   debug command). `current` has the snapshot + command + award callers.
3. **Sibling systems.** Piety is **distinct** — favour reads piety (tier → cap/eq)
   but never writes it; the acts' existing `adjustBelief` calls are untouched, so
   piety is unchanged by favour. R4d-1 tithe / R9d verbs / pilgrimage hooks are
   additive. R9 panels: the screen gains one line; the packet stays backward-shaped
   (new field appended).
4. **Exhaustive switches.** Two new `PietyTier` switches (`equilibrium`, `cap`) —
   both cover all four arms with no `default` (a new tier would force an update —
   intended). No `DeityDomain`/`FaithConcept` switch (alignment uses a stream
   `anyMatch`, not a switch).

### Simplification sweep

- One favour resource (`PlayerFavour`) + one economy helper (`DivineFavour`) the
  acts call; the deity-demand weighting is a single `ReligionIdentity.virtues()`
  lookup. No parallel system: piety stays in `PietyComponent`, favour is a sibling
  map on the same `RiteSavedData`. Classes in scope: `PlayerFavour`/`DivineFavour`
  (new, callers = 4 verbs/tithe + snapshot + command), `RiteSavedData` (+1 field),
  the 4 earning verbs/tithe (+1 call each), the R9c packet/snapshot/screen (+1 field
  / +1 line), `ReligionDebugCommand` (+1 subcommand). No orphan; `FavourAct
  .PILGRIMAGE`/`VIRTUE` are defined for the (flagged) future player-pilgrimage /
  player-virtue hooks + NPC favour.

### Deviations from prompt

- **Pilgrimage not hooked** — there is no player pilgrimage verb (pilgrimage is an
  NPC role/behavior), so no player favour hook exists for it this phase. The
  `FavourAct.PILGRIMAGE` slot is defined for when a player pilgrimage / NPC favour
  lands. Flagged, not invented.
- **Decay model** — implemented the recommended gentle decay (the lazy
  relaxation-to-equilibrium), not deferred.
- **Debug `grant` is a raw cap-bypassing grant** (not the honest `award`) so a
  tester can load favour to exercise the V2 `spend` API regardless of their piety.

### Out-of-scope but flagged

- **V2 miracles** (the first real `spend` consumer — favour gates/pays for them),
  **V3 visions**, **V4 curses/wrath**, **V5 theophany** — no deity *effects* this
  phase, only the ledger + earning + spend API.
- **NPC favour** — player-primary this phase; the favour math is player-keyed but
  the relaxation/weighting model would generalize.
- **Player-virtue earning** — `awardVirtue` is ready, but D2's player-side virtue
  trigger is still thin; wiring a real player-virtue → favour hook is a follow-up
  (favour may be the first meaningful player-virtue reward, as the prompt notes).

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
Static review: `RiteSavedData` codec now 4 fields (under the 16 cap), the new field
`optionalFieldOf` (old saves load empty); the packet StreamCodec write/read are
balanced (favourSummary appended both sides); `DivineFavour` reads piety but never
writes it (piety/favour distinct); the two `PietyTier` switches are exhaustive;
alignment uses a stream (no enum switch); favour clamps to `[0, cap]` everywhere
(bounded).

### Smoke test (user-runnable)

1. **Earning + per-deity surfacing.** As a devout Sunstead player, make an offering,
   tithe, attend a rite, and commission a rite; open `/religion me` (R9c screen) and
   confirm a "Favour: Sunstead NN" line rises with each act, distinct from the piety
   bar. Cross-check with `/religion favour view`.
2. **Deity-demand weighting.** Confirm a giving act (offering/tithe — GENEROSITY)
   earns MORE for a faith that esteems GENEROSITY (Sunstead) than for one that does
   not, and that the four faiths' favour economies differ accordingly (use
   `/religion favour view` after equivalent acts in different faiths).
3. **Passive accrual + gentle decay.** As a PIOUS adherent, let favour sit with no
   service and confirm it settles toward the tier baseline (not zero); after earning
   above the baseline, confirm it drifts gently back down over in-game days.
4. **Lapse-fade.** Drop piety in a faith (e.g. `/religion set` low) and confirm that
   faith's favour fades toward 0 (favour can't be held without standing).
5. **Spend API (for V2).** `/religion favour grant sunstead 50` then `/religion
   favour spend sunstead 20` — confirm the spend succeeds and the balance drops;
   confirm spending more than held fails.
6. **Piety unchanged.** Confirm none of the favour operations move the piety bar —
   they are distinct resources.

## V2 — Miracles (the boon) (2026-06-09)

(Divine Layer, Pillar 3 — the headline: Divine Favour becomes power.)

### Disposition (findings)

V1 built the favour ledger; V2 lets a devout player spend it on **miracles** —
per-deity, domain-flavoured boons that scale from grounded (potion-tier) at low
favour to fantastical at the peaks.

1. **Cost path** — `DivineFavour.current`/`spend` (V1) is the gate + sink; the
   favour scale (per-tier eq 0/5/15/30, cap 0/30/60/100) sets sensible miracle
   costs (8 low → 50 high) and tier gates. Added a public `DivineFavour.tierIn`
   for the per-faith tier gate.
2. **Theming** — `ReligionIdentity.deity().domain()` (SUN/SEA/FORGE/FATE) + rewards
   (D1) drive each faith's set: Sun-Mother heals/grows, Sea-Mother fishing/water/
   storm, Forge-Father strength/ward, Loom luck/foresight/fate-turn.
3. **Effect toolkit** — vanilla `MobEffects` (`new MobEffectInstance(holder, dur,
   amp)` + `player.addEffect`, the `EventEffects.applyPlayerBuff` precedent),
   `player.heal`, `BonemealableBlock.performBonemeal` (area crop growth),
   `ServerLevel.setWeatherParameters` (clear the storm), `GLOWING` reveal — no
   custom effect framework.
4. **Invocation** — chose a command (`/religion miracle list | cast <id>`) + R9c
   surfacing as the testable path (a verb requires an NPC; a miracle is the player
   calling on their deity directly). A dedicated miracle GUI is flagged as deferred
   polish. `RequestBlessingVerb` is the free, NPC-priest *blessing* (mood/rite) —
   miracles are the favour-powered tier above it (distinct, not replaced).

### What shipped

- **`Npc/Religion/Miracle.java`** (new) — the model: `record Miracle(id, religionId,
  displayName, domain, cost, minTier, minFavour, cooldownTicks, flavour, Effect)`
  with a functional `Effect.apply(level, player, favour)` (favour-scaled).
- **`Npc/Religion/Miracles.java`** (new) — the per-deity registry (mirrors
  `ReligionContent`/`MonasticCrafts`): a flat authored list + `byId`/`forReligion`/
  `all`, plus the environment effect helpers (`growCropsAround` via bonemeal,
  `revealNearbyMobs` via GLOWING, `clearHarmfulEffects`). Twelve miracles, three per
  faith, low→high: **Sun** Healing Light → Warmth → Bountiful Harvest; **Sea** The
  Catch → Tide's Grace → Calm the Waters; **Forge** Ancestral Might → Forge-Ward →
  Unbreaking Resolve; **Loom** Fortune's Thread → Foresight → Reweave. A
  `magnitude(favour)` (0/1/2 at <45/45/80) scales amplifier/duration/area within a
  miracle.
- **`Npc/Religion/MiracleInvoker.java`** (new) — the single invocation path:
  `status` (AVAILABLE/LOCKED_TIER/LOCKED_FAVOUR/ON_COOLDOWN) + `cast` (gate → tier →
  favour ≥ max(minFavour,cost) → `DivineFavour.spend` → `Effect.apply` → arm
  cooldown). Per-player, per-miracle cooldown in a small in-memory map (no brain
  memory, no persistence — a short anti-spam timer). Clear denial messages.
- **`DivineFavour.tierIn`** (new public) — the per-faith piety tier for the gate.
- **Command** — `/religion miracle list` (per-deity, with favour, cost, tier, and a
  ✓/🔒/⏳ status glyph + id) and `/religion miracle cast <id>` (invokes; surfaces the
  `Result` message).
- **Surfacing** — `OpenPlayerReligionPacket` +`miracleSummary`; the snapshot builds
  the player's primary-faith miracles with status glyphs; `PlayerReligionScreen`
  renders favour on the piety line and a "Miracles: …" line (✓/🔒/⏳).

### Tie-in audit

1. **Upstream feeders.** `DivineFavour` (V1 — `current`/`spend`/`tierIn`, the cost +
   gate), `ReligionIdentity.deity().domain()` (D1 — theming), vanilla
   `MobEffects`/environment. All read-only except the favour `spend` (the intended
   sink).
2. **Downstream callers.** The command (`MiracleInvoker.cast`/`status`,
   `Miracles.*`); `DivineFavour.spend` (miracles are its first real sink);
   `MobEffect`/weather/bonemeal application; the R9c packet/snapshot/screen.
3. **Sibling systems.** **Piety untouched** — miracles cost favour only (verified:
   the invoker calls `DivineFavour.spend`, never `adjustBelief`). V1 favour is the
   economy; `RequestBlessingVerb` (free NPC blessing) is the lesser, NPC-mediated
   boon below miracles. R9 packet stays backward-shaped (field appended).
4. **Exhaustive switches.** Two new switches over `MiracleInvoker.Status` (command +
   snapshot) — both cover all four arms (LOCKED_TIER/LOCKED_FAVOUR grouped), no
   `default`. No `DeityDomain` switch was needed (sets are authored per-religion;
   the domain is a carried display field), so none added.

### Simplification sweep

- One `Miracle` model + one per-deity registry (`Miracles`) + one invocation path
  (`MiracleInvoker`) that spends favour and applies a vanilla effect — no parallel
  effect framework, no per-faith invoker duplication (the effect is a lambda on the
  record). Classes in scope: `Miracle`/`Miracles`/`MiracleInvoker` (new; callers =
  the command + the snapshot), `DivineFavour` (+1 public accessor), the R9c
  packet/snapshot/screen (+1 field/line), `ReligionDebugCommand` (+1 subcommand). No
  orphan; cooldown state is one small in-memory map.

### Deviations from prompt

- **Invocation is a command + R9c surfacing**, not a player verb — a verb is
  NPC-targeted; a miracle is the player→deity call. A dedicated miracle GUI is
  flagged deferred polish (the R9c line + command cover view + cast).
- **No deity-triggered (automatic) miracle** shipped (the prompt allowed at most one
  light example) — kept this phase to requestable boons; automatic miracles flagged
  for later.
- Effects are starter sets tuned here; balance is a refine pass.

### Out-of-scope but flagged

- **V3 visions**, **V4 curses/wrath**, **V5 theophany**, **NPC miracles**.
- **Deity-triggered (automatic) miracles** — none this phase; flagged.
- **Dedicated miracle GUI** (cast buttons via `litv-gui-screen`) — the R9c line +
  `/religion miracle` command are the surfacing; a rich screen is deferred polish.
- **Cooldown persistence** — in-memory (resets on restart); fine for a short
  anti-spam timer, flagged if a durable cooldown is ever wanted.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
Static review: effects use the `EventEffects.applyPlayerBuff` `MobEffectInstance`
idiom (`Holder<MobEffect>` ctor) + `BonemealableBlock`/`setWeatherParameters`;
invoker gates tier+favour+cooldown then `spend`s then applies (piety never written);
costs/tiers scale (8/FAITHFUL → 50/PIOUS); the two `Status` switches are exhaustive;
packet StreamCodec write/read balanced for `miracleSummary`; no new brain memory; no
`RiteSavedData` codec change.

### Smoke test (user-runnable)

1. **Grounded heal + cooldown.** `/religion favour grant sunstead 30`, then
   `/religion miracle cast sun_healing_light` — confirm favour drops by 8, you heal +
   gain Regeneration, and an immediate re-cast is refused (cooldown).
2. **High-tier gate + fantastical.** With low favour, `cast sun_bountiful_harvest` is
   refused (needs PIOUS + 70 favour); raise belief to PIOUS (`/religion set …`) and
   favour (`/religion favour grant sunstead 80`), then cast — confirm the crops
   around you leap to ripeness.
3. **Per-deity distinctness.** Grant favour and cast a Sea (`sea_calm_the_waters` —
   clears a storm), Forge (`forge_ward` — resistance/absorption), and Loom
   (`loom_reweave` — heal + strip debuffs) miracle; confirm each is domain-distinct
   and grounded at low favour vs fantastical at high.
4. **Clear denial.** With little favour, `cast forge_unbreaking_resolve` — confirm a
   clear "not enough favour / deeper devotion" message, no effect, no spend.
5. **R9c surfacing.** `/religion me` — confirm the screen shows favour on the piety
   line and a "Miracles: …" line marking each ✓ available / 🔒 locked / ⏳ cooldown;
   cross-check `/religion miracle list` for costs + tiers.
6. **Piety untouched.** Confirm casting miracles never moves the piety bar (favour is
   the only cost).

## V3 — Visions (the narrative hook) (2026-06-09)

(Divine Layer, Pillar 3 — where the divine becomes a relationship, not just a
power source.)

### Disposition (findings)

A high-favour player's deity now *speaks* — revealing lore, affirming/admonishing
recent deeds, warning of what's coming, and lightly calling them to a sacred act.
Per-deity voiced from the authored identity (D1), so the Sun-Mother and the Loom
speak utterly differently.

1. **Gate** — V1 `DivineFavour.tierIn`/`current`: visions are for the devout +
   favoured (tier ≥ DEVOUT, favour ≥ 40 with their primary faith).
2. **Voice + content** — `ReligionIdentity` (D1): cosmology, founding myth, deity
   character, sacred-history events (lore); virtues/taboos (affirm/admonish);
   `Religion.deity()` (the name). Same authored source D3b `FaithVoice` / D3
   `ScriptureFactory` draw on (composed fresh into vision messages rather than
   reusing their NPC-line / full-book outputs).
3. **Omen source** — `CalendarView.upcomingFor(religion, now)` gives the next holy
   day + `daysAway` for the "be present" warning.
4. **Recent-deed proxy** — D2's player-side virtue record is thin (V1 finding), so
   guidance reads the player's `VillageReputation.Tier` (low → admonish a taboo)
   and favour level (high → affirm a virtue) as the cheap player-deed signal.
5. **Trigger** — the existing per-player tick (`PlayerEventProximityHandler`) is the
   cadence host; visions self-throttle (cooldown + chance) so they feel special.
6. **Delivery** — `player.sendSystemMessage` with a styled `Component` (the
   `VerbInvocation`/`DialogueRunner` precedent).
7. **Calling persistence** — a current calling per player on `RiteSavedData`
   (`optionalFieldOf`, now 5 fields, under the cap). The `Guilds/.../Requests` board
   is noted for the *deeper* quest tie-in — explicitly NOT wired (flagged); the V3
   calling is light (a tracked task fulfilled by the V1 act hooks).

### What shipped

- **`Npc/Religion/PlayerCalling.java`** (new) — the persisted light task: `(religionId,
  FavourAct act, issuedTick)` + CODEC (act via name xmap) + `describe()` (exhaustive
  `FavourAct` switch). One active calling per player.
- **`Npc/Religion/DivineVision.java`** (new) — the deliverer + trigger + calling
  tracker: `tick(player)` (per-player-tick host; gates tier+favour, rolls behind a
  ~10-min cooldown + 1/6 chance, in-memory last-vision map — transient);
  `composeVision` (weighted pool: lore always, omen when a holy day is within 20
  days, admonish on low reputation / affirm on high favour); a 1/3 chance to lay a
  `PlayerCalling` instead; `onFavourAct` (calling fulfilment from the V1 hooks →
  `DivineFavour.addCapped` bonus + a lore vision); per-deity-voiced styled two-line
  message (deity name + their italic, domain-coloured words).
- **`DivineFavour`** — `addCapped` (capped favour add WITHOUT the calling hook, so
  the bonus can't re-trigger) + a one-line `DivineVision.onFavourAct` call at the end
  of `awardConcept` (so offering/tithe/attend/commission fulfil a matching calling).
- **`RiteSavedData`** — +1 codec field `playerCalling` (`optionalFieldOf`) +
  `getPlayerCalling`/`setPlayerCalling`/`clearPlayerCalling`.
- **Trigger wire** — `PlayerEventProximityHandler.onPlayerTick` → `DivineVision.tick`.
- **Surfacing** — `OpenPlayerReligionPacket` +`activeCalling`; the snapshot fills it
  from `RiteSavedData.getPlayerCalling`; `PlayerReligionScreen` shows "✦ Calling — …"
  on the observance row when active.

### Tie-in audit

1. **Upstream feeders.** `DivineFavour` (V1 — tier/favour gate + the `addCapped`
   reward), `ReligionIdentity`/`Religion.deity()` (D1 voice + content),
   `CalendarView` (omens), `VillageReputation`/`ReputationManager` (the deed proxy),
   the per-player tick. All read-only except the favour reward (intended).
2. **Downstream callers.** `DivineVision.tick` (the trigger), `onFavourAct` (the one
   new call from `DivineFavour.awardConcept`); `RiteSavedData` persists the calling;
   the R9c packet/snapshot/screen surface it.
3. **Sibling systems.** V1 favour (callings grant it via `addCapped`; the favour
   economy is otherwise untouched). **V2 miracles unaffected** (separate path).
   **Piety untouched** (visions read it, never write). D2/D3 content reused. The
   event/calendar system feeds omens read-only.
4. **Exhaustive switches.** Two over `DeityDomain` (`domainColor`) — wait, one over
   `DeityDomain` (`domainColor`, all 4 arms, no default) and one over `FavourAct`
   (`PlayerCalling.describe`, all 6 arms, no default). Both exhaustive; a new
   domain/act forces an update. No new enum added (reused `FavourAct`/`DeityDomain`).

### Simplification sweep

- One vision content source + deliverer (`DivineVision`) + one light calling record
  (`PlayerCalling`) fulfilled by the existing V1 act hooks — no parallel quest
  system, no parallel content store (lore reuses D1; the favour reward reuses V1).
  Classes in scope: `DivineVision`/`PlayerCalling` (new; callers = the per-player
  tick + `DivineFavour.awardConcept` + the snapshot), `DivineFavour` (+`addCapped`,
  +1 hook call), `RiteSavedData` (+1 field/3 accessors), the R9c packet/snapshot/
  screen (+1 field/line), `PlayerEventProximityHandler` (+1 call). No orphan; the
  cooldown is one small in-memory map.

### Deviations from prompt

- **Recent-deed signal** uses `VillageReputation` + favour level as the player-deed
  proxy (D2's player-virtue record is thin) rather than a literal D2 read. Noted.
- **Voice** composes fresh vision text from the D1 identity (the same source
  `FaithVoice`/`ScriptureFactory` use) rather than calling those APIs directly
  (their outputs are an NPC greeting line / a full scripture book, not a vision).
- **Trigger** is the periodic devout-roll (cooldown + chance); temple-visit / rest /
  favour-milestone triggers are noted as possible refinements, not added.
- The `lore()` variety pick uses `System.nanoTime() % n` (no RandomSource threaded
  through `onFavourAct`) — harmless flavour jitter.

### Out-of-scope but flagged

- **V4 curses/wrath**, **V5 theophany**, **NPC visions** (later).
- **Deep quest-board (`Guilds/.../Requests`) integration** for callings — the V3
  calling is a light tracked task fulfilled by existing act hooks; the rich quest
  tie-in is flagged.
- **Deity-triggered automatic events** beyond visions — none here.
- **A vision log / history in R9c** — only the active calling is surfaced; a
  "recent visions" list is deferred.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
[Note: this session's container started on a stale R9c checkout; the local branch
was hard-reset to the pushed remote tip (V2, c9433ec) before V3 work — all V1/V2
files verified present.] Static review: `RiteSavedData` codec now 5 fields (under
16), the new field `optionalFieldOf` (old saves load empty); the packet StreamCodec
write/read are balanced (activeCalling appended both sides); the two switches
(`DeityDomain`, `FavourAct`) are exhaustive; the calling bonus uses `addCapped` (no
re-trigger); visions read piety/favour but never write piety; cadence is
cooldown+chance bounded; no new brain memory.

### Smoke test (user-runnable)

1. **Eligibility + voice.** Make a player PIOUS in Sunstead and grant favour (≥40):
   over time confirm they occasionally receive a "✦ The Sun-Mother speaks ✦" vision
   (lore/guidance), and that a Loom / Forge adherent's visions read distinctly
   (different idiom + colour).
2. **Affirm / admonish.** With high favour, confirm an affirming vision can quote a
   virtue; lower the player's village reputation (transgress) and confirm an
   admonishing vision can quote a taboo.
3. **Omen.** Near a faith's upcoming holy day, confirm a vision can warn "a day
   approaches — … in N days. Be present."
4. **Bounded cadence.** Confirm visions don't spam (a ≥10-min cooldown between them);
   confirm a low-favour / FAITHFUL / unaffiliated player gets NO visions.
5. **Divine calling.** Receive a "I would have you serve…" calling (also shown as
   "✦ Calling —" in `/religion me`); fulfil it via the named act (offering / attend
   rite / commission); confirm bonus favour lands + a fulfilment lore vision, and
   the calling clears.
6. **Isolation.** Confirm miracles (V2) and the piety bar are unaffected — visions
   read favour/piety but only ever grant favour (the calling reward).

## V4 — Curses & wrath (the consequence side) (2026-06-09)

(Divine Layer, Pillar 3 — the mirror of V1–V3: devotion earns favour/miracles/
visions; sacrilege earns the opposite.)

### Disposition (findings)

1. **Displeasure model — signed favour (chosen).** V1's favour relaxes toward a
   piety-tier equilibrium, clamped `[0, cap]`. V4 extends the SAME resource into the
   negative: a `DISPLEASURE_FLOOR = −100`, lower-clamp every favour write to it, and
   below-zero IS displeasure (one resource, not a parallel accumulator). The
   relaxation toward the positive equilibrium means displeasure also heals gently
   over time, while the floor lets it sit deep until repented. Backward-compatible:
   a never-offended player's entries stay positive, so the floor never engages and
   V1–V3 positive behaviour is unchanged. `PlayerFavour.set` now KEEPS negative
   entries (drops only exact zero). No `RiteSavedData` codec change (reuses the
   favour map).
2. **Sacrilege hook — D2 via the crime system.** `FaithJudgment.conceptForCrime`
   maps a `CrimeType` → `FaithConcept`; `CrimeReporter.applySideEffects` already runs
   the D2 faith overlay for NPC perpetrators. The PLAYER branch was empty — V4 fills
   it: a player crime whose concept is a taboo of the player's OWN faith
   (faith-relative, mirroring D2) offends that deity. Temple **desecration** is
   covered by the existing `VANDALISM → SACRILEGE` mapping (SACRILEGE is a Tidecall/
   Forge taboo). **Apostasy** has no clean hook (piety-change detection) — flagged.
3. **Curse registry — mirrors V2 `Miracles`.** A flat per-deity list + a
   by-(faith, severity) pick, reusing vanilla negative `MobEffects` + weather.
4. **Omens — reuse V3 `DivineVision`.** Added `DivineVision.speak(faith, player,
   text)` (public) so the negative side delivers deity-voiced warnings in the same
   styled message. Domain flavour from D1.

### What shipped

- **`DivineFavour`** (signed) — `DISPLEASURE_FLOOR` + the band thresholds
  (`OMEN_AT −1`, `CURSE_AT −25`, `WRATH_AT −60`); `DispleasureTier{NONE,OMEN,CURSE,
  WRATH}` + `displeasureOf(favour)`; `offend(...)` (sacrilege drives favour down,
  clamped to the floor, no-op without standing); all clamps' lower bound moved from
  `0f` to `DISPLEASURE_FLOOR` (so a repenting player's earning climbs GRADUALLY out
  of the negative). `PlayerFavour.set` keeps negatives.
- **`Npc/Religion/Curse.java`** (new) — the model (mirror of `Miracle`): `(religionId,
  domain, severity, displayName, flavour, Effect)`.
- **`Npc/Religion/Curses.java`** (new) — the per-deity registry: 8 curses (CURSE +
  WRATH per faith). Sun Blight/Scorching, Sea Storm's Rebuke/The Drowning Deep, Forge
  Frailty/Dishonour, Loom Misfortune/Tangled Fate. Negative `MobEffects` (WEAKNESS/
  HUNGER/MOVEMENT_SLOWDOWN/DIG_SLOWDOWN/CONFUSION/BLINDNESS/UNLUCK + a short POISON)
  + storm weather; **non-fatal by design** (POISON stops at half a heart; no WITHER/
  fire).
- **`Npc/Religion/DivineWrath.java`** (new) — the consequence service:
  `onPlayerSacrilege(player, concept)` (faith-relative taboo → `offend` + immediate
  warning), `tick(player)` (while displeased, apply the band consequence — omen
  vision / curse / wrath — cooldown-bounded). In-memory cooldown (transient).
- **`DivineVision.speak`** (new public) — deity-voiced delivery reused for omens/curse
  pronouncements.
- **Triggers wired** — `CrimeReporter` (player-perpetrator branch → `DivineWrath
  .onPlayerSacrilege`), `PlayerEventProximityHandler` (→ `DivineWrath.tick`).
- **Surfacing** — the R9c favour line now shows displeasure (e.g. "Sunstead −30
  (cursed)") via `displeasureOf`.
- **Debug** — `/religion sacrilege <faith> <amount>` drives displeasure for testing.

### Repentance & "never damned"

The V1 earning hooks (offerings/tithes/rites/commissions) call `DivineFavour.award`,
now lower-clamped to the floor — so an act ADDS favour, climbing gradually out of
the negative (harder the deeper, since each act is a fixed step). As favour crosses
back above each threshold the band drops (wrath → curse → omen → none) and
consequences stop; the gentle relaxation toward the positive equilibrium also heals
displeasure over time. There is always a road back.

### Tie-in audit

1. **Upstream feeders.** D2 `FaithJudgment.conceptForCrime` (sacrilege),
   `CrimeReporter` (the player branch), `DivineFavour` (the signed scale), D1 deity
   (curse flavour). Read-only except `offend` (the intended down-write).
2. **Downstream callers.** `offend`/`displeasureOf` (DivineWrath + command +
   snapshot); `Curses` (DivineWrath); `DivineVision.speak` (omens); the per-player
   tick; the R9c favour line.
3. **Sibling systems — signed favour did NOT break the positive side.** V2
   `MiracleInvoker.status` (`favour < minFavour` → negative favour fails →
   miracles correctly UNAVAILABLE while displeased, for free); V3 `DivineVision.tick`
   (`favour ≥ 40` gate → negative fails); `DivineFavour.spend` (`current < amount` →
   negative fails). All positive gates still pass on positive favour (the floor only
   engages once a value goes negative). Piety is read, never written (sacrilege
   touches favour only).
4. **Exhaustive switches.** New switches over `DispleasureTier` (`cooldownFor`,
   `applyConsequence`, the snapshot tag) — all cover NONE/OMEN/CURSE/WRATH, no
   `default`. No `DeityDomain` switch added (curses authored per-religion; domain is
   a carried field). The reused `FavourAct` switches are unchanged.

### Simplification sweep

- Displeasure reuses the favour scale (signed — one resource); curses mirror the
  `Miracles` registry; omens reuse `DivineVision`; repentance reuses the V1 earning.
  No parallel system, no codec change. Classes in scope: `Curse`/`Curses`/
  `DivineWrath` (new; callers = `CrimeReporter` + the per-player tick),
  `DivineFavour` (signed: +floor/thresholds/`offend`/`displeasureOf`),
  `PlayerFavour` (keep negatives), `DivineVision` (+`speak`), the R9c snapshot
  (+displeasure tag), `ReligionDebugCommand` (+1 subcommand). No orphan.

### Deviations from prompt

- **Apostasy** is not auto-detected (no clean piety-renounce hook) — sacrilege is
  driven by D2 crime-taboos + temple vandalism (VANDALISM→SACRILEGE). Apostasy
  flagged for a future hook; the debug `/religion sacrilege` exercises displeasure
  directly.
- **Desecration** is the `VANDALISM → SACRILEGE` crime path (reuses the crime hook)
  rather than a dedicated block-break temple listener — flagged as a refinement
  (distinguishing malicious break from maintenance needs more than a block event).
- **Curses cap at WRATH** (the severe band) — the wrath *theophany* manifestation is
  V5 (out of scope); wrath here is debuffs + storm, non-fatal.

### Out-of-scope but flagged

- **V5 theophany** — including the wrath-theophany peak.
- **NPC curses** — player-primary this phase.
- **Auto apostasy detection** + a **dedicated desecration listener** — flagged hooks.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
[Note: this container also started on a stale R9c checkout; hard-reset to the pushed
V3 tip (f05a2f9) before V4 — all V1–V3 files verified present.] Static review: favour
is signed (`DISPLEASURE_FLOOR` lower-clamp everywhere; `PlayerFavour.set` keeps
negatives); the positive gates (V2 status, V3 tick, spend) still fail on negative
favour (miracles blocked while displeased, for free); piety never written; curses
are non-fatal (POISON, no WITHER/fire); the negative `MobEffects` names are standard
Mojmap (WEAKNESS/HUNGER confirmed in-repo); all `DispleasureTier` switches are
exhaustive; repentance via the V1 award climbs out of the negative; no new brain
memory, no codec change.

### Smoke test (user-runnable)

1. **Sacrilege → displeasure + omen.** As a devout Sunstead player (GREED is its
   taboo), steal (a THEFT → GREED) — confirm a warning vision ("You profane what I
   hold…") and that `/religion me` shows favour dropping toward/below zero.
   (`/religion sacrilege sunstead 40` drives it directly.)
2. **Escalation → curse, miracles blocked.** Persist in sacrilege to the CURSE band
   (favour ≤ −25): confirm a per-deity curse lands (Sun Blight: weakness+hunger; vs
   Sea storm, Forge frailty, Loom misfortune — domain-distinct) and that
   `/religion miracle cast …` is refused (favour is negative).
3. **Wrath (non-fatal).** Reach ≤ −60: confirm a severe but non-lethal wrath (strong
   debuffs + storm + brief poison) — you are punished, not killed.
4. **Repentance.** Make offerings / attend rites / commission rites: confirm favour
   climbs back gradually, the band drops (wrath → curse → omen → none), curses stop,
   and miracles return — never permanently damned.
5. **Faith-relative.** Confirm a crime that is NOT your faith's taboo (e.g. a
   Sunstead player committing ASSAULT → DISCORD, not a Sunstead taboo) does NOT anger
   the Sun-Mother.
6. **Positive side intact.** Confirm V1 favour earning, V2 miracles (on positive
   favour), and V3 visions all still work, and that piety is never moved by any of
   the above.

## V5 — Theophany (the manifestation) (2026-06-09)

(Divine Layer, Pillar 3 — the capstone, and the close of the religion rework's
three pillars.)

### Disposition (findings)

At the extremes of the favour relationship the deity MANIFESTS — a glorious
blessing made visible at peak favour, its anger incarnate at the depth of wrath.
Rare, dramatic, overtly fantastical, utterly per-deity.

1. **The extremes.** V1 `DivineFavour.current`/`tierIn` give the positive peak
   (PIOUS + favour ≈ the 100 cap → `FAVOUR_PEAK = 90`); V4's signed scale gives the
   depth (near the −100 floor → `WRATH_DEPTH = −90`). The trigger is *reaching* an
   extreme, milestone-bounded — not a repeatable cast.
2. **Effect basis + voice.** Theophany is the amplified peak of the V2 miracle / V4
   curse, and the deity *speaks* via V3 `DivineVision.speak` (added in V4). D1
   `ReligionIdentity.deity().domain()` → the manifestation's form.
3. **Visual/audio toolkit (vanilla).** `ServerLevel.sendParticles` (domain particles),
   visual-only `LightningBolt` (`setVisualOnly(true)` — drama, no damage/fire),
   `setWeatherParameters` (radiant clear / dread storm), `playSound`
   (BEACON_ACTIVATE/PLAYER_LEVELUP vs LIGHTNING_BOLT_THUNDER/WITHER_SPAWN). No custom
   rendering.
4. **Trigger site + bounding.** The existing per-player tick (host to
   `DivineWrath`/`DivineVision`), gated to the extremes + a **persisted per-(deity,
   pole) milestone** with a long `COOLDOWN` (7 in-game days) so it's rare.

### What shipped

- **`Npc/Religion/DivineTheophany.java`** (new) — the one manifestation path:
  `tick(player)` (per-player tick; fires a favour theophany at PIOUS + favour ≥ 90,
  a wrath theophany at favour ≤ −90, each behind the persisted milestone + 7-day
  cooldown); `fireFavour` / `fireWrath` (public — also the debug force-fire):
  the domain-flavoured `manifest(...)` visual/audio + `DivineVision.speak` revelation
  + the amplified effect. **Favour boon** — full heal + REGEN III / RESISTANCE II /
  ABSORPTION IV / FIRE_RES (long) + the **lasting mark** (favour pinned to the cap
  via `addCapped`). **Wrath calamity** — visual-only lightning + storm + strong long
  WEAKNESS/SLOWNESS/HUNGER + BLINDNESS/NAUSEA + a short POISON (**non-fatal**), favour
  driven to its depth via `offend` (still repentable). `particlesFor(domain, wrath)`
  is the exhaustive `DeityDomain` switch (SUN light/scorch, SEA surge/drown, FORGE
  fire, FATE woven-light/tangle).
- **`RiteSavedData`** — +1 codec field `playerTheophany` (`Map<UUID, Map<"faith|pole",
  tick>>`, `optionalFieldOf`, now 6 fields, under the cap) + `getTheophanyTick`/
  `setTheophanyTick`/`theophanies`.
- **`DivineWrath.armConsequenceCooldown`** (new public) — a wrath theophany arms it
  so the normal curse tick doesn't ALSO fire the same moment (theophany is the
  amplified peak, not an addition).
- **Trigger wire** — `PlayerEventProximityHandler` runs `DivineTheophany.tick` FIRST
  (so a wrath theophany suppresses the normal curse below).
- **Surfacing** — `OpenPlayerReligionPacket` +`theophany`; the snapshot derives the
  most-recent theophany ("✦ Sun-Mother's glory" / "…'s wrath") from the milestone
  ledger; `PlayerReligionScreen` marks it on the title row.
- **Debug** — `/religion theophany favour|wrath` force-fires the manifestation.

### Tie-in audit

1. **Upstream feeders.** `DivineFavour` (the peak/depth + `MAX_FAVOUR`/`addCapped`/
   `offend`/`tierIn`/`current`), `DivineFavour.DispleasureTier` (depth via the signed
   scale), D1 deity domain (form), the per-player tick. Read-only except the
   intended favour writes (the mark / the depth).
2. **Downstream callers.** `DivineTheophany.tick` (trigger) + `fireFavour`/`fireWrath`
   (debug); `DivineVision.speak` (voice); `RiteSavedData` (milestone persistence);
   the R9c packet/snapshot/screen (the record).
3. **Sibling systems — no double-fire.** A wrath theophany arms the V4 curse
   cooldown so a normal curse won't also land that moment; theophany runs first in
   the tick. Miracles (V2) are player-invoked (no auto-fire to collide with). V3
   visions are independent positive-side flavour (theophany's 7-day cooldown makes a
   same-tick overlap negligible). The signed favour scale is unchanged (theophany
   only writes through the existing `addCapped`/`offend`). Piety never written.
4. **Exhaustive switches.** One new `DeityDomain` switch (`particlesFor`) — all four
   arms, no `default`. No new enum.

### Simplification sweep

- One manifestation path (`DivineTheophany`) with a favour pole + a wrath pole, per
  deity — reusing the favour extremes (V1/V4), the miracle/curse effect basis
  (amplified inline), `DivineVision` voice (V3), and the vanilla visual toolkit. No
  parallel system, no new rendering. Classes in scope: `DivineTheophany` (new;
  callers = the per-player tick + the debug command), `RiteSavedData` (+1 field/3
  accessors), `DivineWrath` (+1 public arm), the R9c packet/snapshot/screen (+1
  field/line), `ReligionDebugCommand` (+1 subcommand). No orphan; the milestone is
  the only new persisted state.

### Deviations from prompt

- **Effects authored inline** in `fireFavour`/`fireWrath` (amplified miracle/curse)
  rather than via the `Miracles`/`Curses` registries — a theophany is a one-off
  manifestation, not a registry entry; it reuses the same vanilla effect vocabulary.
- **Surfacing is a title-row mark** (the COMPACT screen is full); the manifestation
  itself (visual + the deity's words) IS the experience. A richer "theophany log" is
  deferred.
- **Wrath lightning is visual-only** + POISON (non-fatal) to honour V4's
  never-permanent rule — the calamity is the sharpest warning, not death.

### Out-of-scope but flagged

- **NPC theophany** — player-primary this phase.
- This **COMPLETES the divine layer (Pillar 3)** and the religion rework's three
  pillars (deepening D1–D3c; the divine layer V1–V5; with Pillar 2 interreligious
  relations as the remaining major body). **Parked religion tails:** D4 aesthetics /
  standalone religious district (layout rework), the Abbot office (offices system),
  mead/BREWING, and the NPC-side divine (NPC favour/miracles/visions/curses).

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
Static review: the visual toolkit is vanilla (`sendParticles`, visual-only
`LightningBolt`, `setWeatherParameters`, `playSound` with standard SoundEvents/
ParticleTypes); the trigger is extreme-gated + persisted-milestone + 7-day cooldown
(rare); a wrath theophany arms the V4 curse cooldown (no double-fire) and runs first
in the tick; favour writes go only through `addCapped`/`offend` (signed scale intact);
wrath is non-fatal (visual-only lightning + POISON) and repentable (favour climbs
back via the V1 acts); the `DeityDomain` switch is exhaustive; `RiteSavedData` codec
now 6 fields (under 16), the new field `optionalFieldOf` (old saves load empty); no
new brain memory.

### Smoke test (user-runnable)

1. **Theophany of favour.** Make a player PIOUS in Sunstead (`/religion set <you>
   sunstead 0.9`) and reach peak favour (`/religion favour grant sunstead 100`) — or
   force it (`/religion theophany favour`): confirm a dramatic light manifestation
   (END_ROD/flame, radiant sky, beacon hum), the Sun-Mother speaks, and a potent
   lasting boon lands (full heal + long regen/resistance/absorption + favour pinned
   to the cap). Confirm it does NOT re-fire casually (milestone + cooldown).
2. **Per-deity distinctness.** Repeat for Tidecall / Forge / Loom (`/religion
   theophany favour` after setting each as primary) — confirm each manifestation is
   visually + thematically distinct (sea surge vs forge fire vs woven-light).
3. **Theophany of wrath.** Drive deep displeasure (`/religion sacrilege sunstead
   200`) or force it (`/religion theophany wrath`): confirm a dread manifestation
   (visual-only lightning + storm + dread sound), the deity's wrath spoken, and a
   severe but NON-FATAL calamity (strong debuffs + brief poison — you survive).
4. **Road back intact.** After a wrath theophany, repent via offerings/rites and
   confirm favour climbs back out of the depth (never permanently damned).
5. **No double-fire.** Confirm a wrath theophany does not also drop a normal curse
   the same moment, and that miracles/visions are otherwise unaffected.
6. **Rare.** Confirm theophany doesn't re-trigger on every eligible tick (the 7-day
   per-pole cooldown holds); confirm `/religion me` marks "✦ <deity>'s glory/wrath".
7. **V1–V4 intact.** Confirm favour earning, miracles, visions, and curses all still
   work, and piety is never moved by any theophany.

## F1a — sub-stage 1: the God entity + GodRegistry (2026-06-09)

(Foundation 1 — separating gods from religions. Pure additive scaffolding; wires
nothing into existing behaviour.)

### Disposition (findings)

Today a religion fuses exactly one deity. `Religion.deity()` is just a name string
("the Sun-Mother"; empty for the Loom), and `Religion` is hard-coded in
`ReligionRegistry` (four faiths: sunstead/the_loom/tidecall/forge_creed). The rich
deity layer lives in the parallel `ReligionIdentity` registry (keyed by religion
id): `Deity(DeityDomain domain, String character, demands, rewards)` + `List<Virtue>`
/ `List<Taboo>` (each `FaithConcept` + text) + cosmology/history/aesthetics.
`DeityDomain` = `SUN/SEA/FORGE/FATE`. **The fusion is 1:1 today** — each religion →
one identity → one `Deity` → one domain; the Loom is the impersonal case
(`Religion.deity()` empty, but its identity gives it a `Deity(FATE, …)`).

The four gods authored this stage (id ← source identity):
- `sun_mother` — "the Sun-Mother", SUN ← `sunstead`
- `the_pattern` — **impersonal** (name empty), FATE ← `the_loom`
- `sea_mother` — "the Sea-Mother", SEA ← `tidecall`
- `forge_father` — "the First Forge-Father", FORGE ← `forge_creed`

**Type-reuse decision:** `God` lives in `Npc.Religion` and reuses the existing
nested `ReligionIdentity.DeityDomain` / `Virtue` / `Taboo` AS-IS (no move/rename —
that churns consumers; a later cleanup sub-stage relocates them). **Derivation
decision:** the registry DERIVES each god from its existing authored source at init
(single source of truth, no drift) rather than re-authoring the strings — transient
until sub-stage 2 makes the god the sole source.

### What shipped

- **`Npc/Religion/God.java`** (new) — the record: `(id, Optional<String> name,
  DeityDomain domain, character, demands, rewards, List<Virtue> virtues, List<Taboo>
  taboos)`. Compact-ctor validates non-blank id + null-safety. `displayName()`
  (name or a domain-sensible fallback — the Pattern), `isImpersonal()`. A `Codec`
  (mirrors `Religion.CODEC`; sub-codecs for `DeityDomain`/`FaithConcept`/`Virtue`/
  `Taboo` via name xmap + RecordCodecBuilder) for later content-pack gods. **8 codec
  fields — well under the 16 ceiling** (headroom for the sacred-space rule / miracle
  set / holy days / oaths gods will gain later).
- **`Npc/Religion/GodRegistry.java`** (new) — mirrors `ReligionRegistry` exactly
  (static, lazy `ensureInit`, idempotent `register`, `get`/`find`/`all`, a
  `LOGGER.info` count). Authors the four canonical gods by `derive(godId,
  religionId)` from `ReligionIdentity.get` + `ReligionRegistry.get().deity()`.
- **`ReligionDebugCommand`** — one new subcommand `/religion gods` (the only consumer
  this stage): lists each god's id, name or "(impersonal)", domain, virtue/taboo
  counts, and demands line. Read-only.

### Tie-in audit

1. **Upstream feeders.** `ReligionIdentity` (the authored content) + `ReligionRegistry`
   (`deity()` name) — both READ-ONLY (the registry only reads them at init).
2. **Downstream callers.** **None** besides the new `/religion gods` readout — the
   point of this stage is that gods have no consumers until sub-stage 2/3.
3. **Sibling systems.** The divine layer (`PlayerFavour`/`DivineFavour`/`Miracles`/
   `Curses`/`DivineVision`/`DivineWrath`/`DivineTheophany`) is **untouched** and still
   keyed as before (favour by `religionId`, miracles/curses by `DeityDomain`). No
   re-keying.
4. **Exhaustive switches.** No enum values added. The existing `DeityDomain`
   switch (`DivineVision` domain→colour) is unaffected. One NEW exhaustive
   `DeityDomain` switch added inside `God.displayName` (all four arms, no default) —
   additive, self-contained.

### Simplification sweep

- New classes: `God`, `GodRegistry` — **inbound callers: 1** (the `/religion gods`
  readout); zero elsewhere by design. No orphan to delete; no overlap to consolidate.
- **Planned (not acted on):** once the religion references gods (sub-stage 2) and the
  divine layer re-keys to godId (sub-stage 3), `ReligionIdentity`'s deity/virtue/taboo
  duplication can be deleted and `Religion.deity()` becomes a thin delegate
  (sub-stages 4/5). The derivation-at-init keeps a single source meanwhile, so that
  deletion is clean later. Do NOT act yet.

### Deviations from prompt

None. Additive `God` + `GodRegistry` + a single `/religion gods` readout; gods derived
from the existing authored source; nested types reused (not moved); divine layer and
`Religion`/`ReligionIdentity` untouched beyond reading.

### Out-of-scope but flagged

- **Sub-stage 2** — `Religion` references `List<godId>`; `ReligionIdentity` /
  `Religion.deity()` delegate to the god.
- **Sub-stage 3** — re-key the divine layer (favour/miracles/curses/visions) to
  `godId` (currently `religionId` / `DeityDomain`).
- **Cleanup sub-stage** — relocate `DeityDomain`/`Virtue`/`Taboo`/`Deity` onto `God`;
  delete the `ReligionIdentity` deity duplication.
- **F1b** — per-world religions (separate arc).

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
[Note: this container started on a stale R9c checkout; hard-reset to the pushed V5
tip (b85d43d) before this work — all prior files verified present.] Static review:
`God` reuses `ReligionIdentity.DeityDomain`/`Virtue`/`Taboo` (no move); its codec
sub-codecs use name-xmap + RecordCodecBuilder (8 fields, under 16); `GodRegistry`
mirrors `ReligionRegistry` (lazy/idempotent/`get`/`find`/`all`/log count) and derives
from `ReligionIdentity` + `Religion.deity()` (single source, no drift); the divine
layer and `Religion`/`ReligionIdentity` are unread-only-touched; `/religion gods` is
the sole consumer; no new enum values; no codec/save change to existing data.

### Smoke test (user-runnable)

1. **Gods list.** Load a world; run `/religion gods` — confirm all four list with the
   right id, name (and `the_pattern` showing "(impersonal)"), and domain
   (SUN/FATE/SEA/FORGE).
2. **Content matches the source.** For each god, confirm its domain / demands /
   virtue-&-taboo counts match the corresponding faith's `/religion identity <faith>`
   readout (e.g. `sun_mother` ↔ `/religion identity sunstead`).
3. **Divine layer unchanged.** Confirm the divine layer behaves exactly as before:
   `/religion favour grant sunstead 30`, `/religion miracle cast sun_healing_light`
   still work; `/religion me` is unchanged. Gods are inert scaffolding — nothing
   else should differ.

## F1a sub-stage 2 — Religion references its god(s) (2026-06-09)

(Foundation 1 — separating gods from religions. Additive + behaviour-preserving;
the link sub-stages 3+ ride on.)

### Disposition (findings)

`Religion` was an 8-field record (id, displayName, coreTenets, rites,
sacredLocations, calendar, `Optional<String> deity`, preferredBookCategories), codec
all `optionalFieldOf`, with a null-guarding compact constructor. `ReligionRegistry`
builds the four via factory methods (`sunstead`/`theLoom`/`tidecall`/`forgeCreed`),
each ending its `new Religion(...)` with the preferred-book-categories list.
Sub-stage 1's `GodRegistry` exposes the four god ids (`sun_mother`/`the_pattern`/
`sea_mother`/`forge_father`) + `get`/`find`/`all`. **Nothing reads a religion→god
link today** — it doesn't exist; the only consumer this stage is the debug readout.
The four `new Religion(...)` call sites are the only constructor uses (no others to
break by the arity change).

### What shipped

- **`Religion`** — +1 record component `List<String> godIds` (ordered, first =
  primary), null-guarded/defensive-copied in the compact constructor like the other
  lists; codec gains `optionalFieldOf("godIds", List.of())` → **9 fields, still under
  the 16 ceiling** (commented; F1b's per-world fields will eat the headroom — nest
  into a sub-record then). `Religion.deity()` kept **exactly as-is** (still the
  invocation/scripture source).
- **`ReligionRegistry`** — the four factory methods now pass the single-god list:
  `sunstead → [SUN_MOTHER]`, `the_loom → [THE_PATTERN]`, `tidecall → [SEA_MOTHER]`,
  `forge_creed → [FORGE_FATHER]` (matching sub-stage 1's derivation).
- **`GodRegistry`** — the resolver (kept off the pure-data record): `godsFor(Religion)`
  maps `godIds()` through `get`, **skipping unknown ids with a `LOGGER.warn`** (a typo
  degrades, never NPEs); `primaryGod(Religion)` → the first resolved god or empty.
- **`/religion gods`** — extended with a "Religion → god(s)" section: each religion's
  god ids + its resolved primary god's display name (the Loom showing `the_pattern` /
  "(impersonal)"). The only behaviour change.

### Tie-in audit

1. **Upstream feeders.** `GodRegistry` (the god ids must exist for the resolver — they
   do, sub-stage 1) + `ReligionRegistry` (authors the link). Confirmed.
2. **Downstream callers.** `Religion.CODEC` — the new field is `optionalFieldOf`, so
   it round-trips and pre-F1a data loads empty; and `Religion` objects aren't
   persisted on entities anyway (v1 persists only the religion id on
   `PietyComponent.beliefs`). The ONLY reader of `godIds`/the resolver is the debug
   readout. The 9-arg constructor change touches only the 4 factory call sites (all
   updated; grep-confirmed no others).
3. **Sibling systems.** The divine layer (favour/miracles/curses/visions/wrath/
   theophany) and every `Religion.deity()` consumer (`ReligionContent.invocation`,
   `ScriptureFactory`, `DivineVision`, `PlayerReligionSnapshotBuilder`,
   `TempleSnapshotBuilder`) are **untouched** — still reading the existing name field;
   nothing re-keyed.
4. **Exhaustive switches.** No enum values added. The two existing `DeityDomain`
   switches (`DivineVision` domain→colour, `God.displayName`) are unaffected.

### Simplification sweep

- Touched: `Religion` (+1 field), `ReligionRegistry` (4 call sites), `GodRegistry`
  (+`godsFor`/`primaryGod`), `ReligionDebugCommand` (the readout). Inbound callers of
  the new resolver: **1** (the readout; `primaryGod` also calls `godsFor`). No orphan.
- **Planned (not acted on):** `godIds` + the resolver are the scaffolding that lets
  sub-stage 5 delete the `ReligionIdentity`/`Religion.deity()` deity duplication once
  the divine layer (sub-stage 3) and the deity-name consumers (sub-stage 4) point at
  the god. Do NOT act yet.

### Deviations from prompt

None. Added `godIds` (optional, 9th field), authored all four single-god, put the
resolver in `GodRegistry` (record stays pure data), surfaced the link in the readout;
`Religion.deity()` and the divine layer untouched; unknown ids warn-and-skip.

### Out-of-scope but flagged

- **Sub-stage 3** — re-key the divine layer (favour/miracles/curses/visions) to
  `godId` via `GodRegistry.godsFor`/`primaryGod` (currently `religionId`/`DeityDomain`).
- **Sub-stage 4** — re-point the virtues/taboos + the six `Religion.deity()`-name
  consumers to the god.
- **Cleanup sub-stage** — invert authoring onto `God`, relocate the nested types,
  delete the `ReligionIdentity` deity duplication.
- **No multi-god religions / pantheons** yet (all four single-god — list mechanism
  only). **F1b** — per-world religions (will eat the codec headroom).

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
[Note: container started current this time (HEAD at the F1a sub-stage-1 tip); no
reset needed.] Static review: the only `new Religion(...)` sites are the 4 factory
methods (all pass the new list; grep-confirmed no others); the codec field is
`optionalFieldOf` (round-trips, old data loads empty), now 9 fields (under 16); the
resolver warn-and-skips unknown ids (no NPE); `Religion.deity()` + every divine-layer
/ deity-name consumer is untouched; the resolver's sole caller is the readout; no new
enum values; no save/codec break for existing data.

### Smoke test (user-runnable)

1. **Religion → god link.** Load a world; run `/religion gods` — confirm the new
   "Religion → god(s)" section lists each of the four with its god id and resolved
   primary god display name (the Loom: `the_pattern` / "(impersonal)").
2. **Graceful unknown id.** (Reason about / temporarily test:) a religion pointing at
   a bogus god id would log "references unknown god id … — skipping" and resolve to
   the remaining/none, NOT crash.
3. **Divine layer unchanged.** Confirm `/religion favour grant sunstead 30` +
   `/religion miracle cast sun_healing_light` still behave exactly as before, and
   `/religion me` is unchanged.
4. **Invocation/scripture text unchanged.** Confirm `Religion.deity()`-driven text
   (rite invocation, scripture title) reads identically — the name field is untouched.

## F1a sub-stage 3a — re-key the FAVOUR ECONOMY to godId (2026-06-09)

(Foundation 1 — separating gods from religions. Favour becomes a per-GOD standing;
behaviour-preserving for the single-god starters.)

### Disposition (findings)

`PlayerFavour` was `Map<religionId, Entry>` + codec `unboundedMap(STRING, Entry)`.
`DivineFavour`'s whole public API took a `religionId`: `award`/`awardVirtue`/
`addCapped`/`offend`/`debugGrant`/`spend`/`current`/`tierIn`; the piety coupling
(`tierFor`) read `PietyComponent.beliefIn(religionId)` (**piety is belief in a
RELIGION, not a god** — the wrinkle); `aligned` read `ReligionIdentity.get(religionId)
.virtues()`. `RiteSavedData` stores the `PlayerFavour` (shape unchanged). Grepped the
FULL caller list (the safety net): the three act verbs + `Tithing` (`award`),
`DivineWrath` (`offend`/`current`), `DivineVision` (`tierIn`/`current`/`addCapped`),
`MiracleInvoker` (`tierIn`/`current`/`spend`), `DivineTheophany` (`current`/`tierIn`/
`addCapped`/`offend`), `PlayerReligionSnapshotBuilder` + the `/religion gods`,
`/religion miracle list`, `/religion favour`, `/religion sacrilege` readouts/debug
(`current`/`debugGrant`/`spend`/`offend`). No religion→god link was read before
sub-stage 2; the divine-event CONTENT selection is 3b (untouched here).

### The piety-coupling resolution

A god's cap/equilibrium derive from a piety TIER, and piety is per-religion. Built a
**`god → religions` reverse index** in `GodRegistry` (at init, from each religion's
`godIds`): `religionsVenerating(godId)`. A god's tier = the **best** `beliefIn` among
its venerating religions → tier. Single-god starters → the one religion's belief
(identical to today).

### What shipped

- **`PlayerFavour`** — inner map re-keyed `byReligion` → `byGod` (params
  `religionId`→`godId`); codec shape unchanged (only the semantic key changed).
- **`GodRegistry`** — `RELIGIONS_BY_GOD` reverse index built at init +
  `religionsVenerating(godId)` (reused by 3b for god→religion content).
- **`DivineFavour` — split into core (godId) + convenience (religionId):**
  - **Core, god-keyed:** `award`/`awardVirtue`/`addCapped`/`offend`/`debugGrant`/
    `spend`/`current` take a `godId`; `tierIn`→`tierForGod`; `tierFor` resolves the
    god's tier via the reverse index (best venerating-religion belief); `aligned`
    checks **`God.virtues()`** (the demand belongs to the god). `awardConcept` no
    longer fires the V3 calling hook (moved — below).
  - **Convenience, religion-keyed (the ONE home for multi-god policy):**
    `awardForReligion` (fan out `act` to every `godsFor(religion)` god + fire the V3
    calling hook once), `awardVirtueForReligion`, `offendForReligion`,
    `addCappedForReligion`, `currentForReligion`/`tierForReligion`/`spendForReligion`
    (the religion's PRIMARY god). Single-god → just the primary, behaviour-identical.
- **Call sites updated** (the signature change + the grep forced every one):
  the act verbs + `Tithing` → `awardForReligion`; `DivineWrath`/`DivineVision`/
  `DivineTheophany`/`MiracleInvoker` favour reads/writes → the `...ForReligion`
  convenience (single-god → primary god); the V3 calling-bonus → `addCappedForReligion`;
  the snapshot + `/religion favour view` → **per-god** (iterate the player's
  favour-entry gods + the gods of their belief religions, show each god's `current`);
  `/religion favour grant|spend` → **god-keyed** (`debugGrant`/`spend` take a god id);
  `/religion sacrilege` → `offendForReligion`; **`/religion miracle list`** favour
  header → `currentForReligion` (the straggler the grep caught — `current` still
  compiles with a String, so the semantic change was invisible to the compiler).

### Tie-in audit

1. **Upstream feeders.** `GodRegistry.godsFor`/`primaryGod` + the new reverse index;
   `PietyComponent.beliefIn` (still per-religion, now read via the reverse index);
   `God.virtues()` (demand bonus). Confirmed.
2. **Downstream callers.** The FULL favour caller list — every one recompiled against
   the new shape; each dispositioned (acts → `...ForReligion`; divine-event reads →
   convenience-resolved to the primary god; debug grant/spend → god-keyed; the favour
   screens → per-god). **No religion-keyed favour path remains** (grep-verified: the
   only bare core `current`/`spend`/etc. outside `DivineFavour` is the snapshot loop
   over genuine god ids).
3. **Sibling systems.** `PietyComponent` unchanged. The divine-event CONTENT
   selection (`Miracles`/`Curses` by `DeityDomain`; vision/wrath/theophany flavour by
   `ReligionIdentity.deity().domain()`) is **untouched** — 3a only changed how they
   READ/SPEND favour (now god-resolved). `ReligionIdentity`/`Religion.deity()`
   untouched.
4. **Exhaustive switches.** No enum change; the two `DeityDomain` switches
   (`DivineVision` colour, `God.displayName`) and the `DispleasureTier`/`Status`
   switches are unaffected.

### Simplification sweep

- The **convenience layer is the single future home** for multi-god favour policy
  (today: fan-out to all gods on writes, primary god on reads). The **reverse index**
  is reused by 3b (god→religion for content). Touched: `PlayerFavour` (re-key),
  `GodRegistry` (+index/+`religionsVenerating`), `DivineFavour` (core+convenience),
  9 caller files. No orphaned religion-keyed favour helper remains (the religion-
  keyed API is now exactly the `...ForReligion` convenience; nothing else).

### Deviations from prompt

None of substance. The V3 calling hook (`DivineVision.onFavourAct`) was moved from
the core `awardConcept` (now god-keyed) to `awardForReligion` (religion-keyed, where
the calling — itself religion-keyed — belongs); its bonus uses `addCappedForReligion`.
The favour debug `grant`/`spend` command now targets a **god id** (per the per-god
verification goal); `sacrilege` stays religion-facing (`offendForReligion`).

### Out-of-scope but flagged

- **3b** — re-point the divine-EVENT content selection (miracles/curses/visions/
  wrath/theophany) to the god (currently `DeityDomain` / `ReligionIdentity.deity()`).
- **Sub-stage 4** — re-point virtues/taboos + the six `Religion.deity()`-name consumers
  to the god.
- **Cleanup** — invert authoring onto `God`, relocate the nested types, delete the
  `ReligionIdentity` deity duplication.
- **F1b** — per-world religions.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
[Container current; no reset needed.] Static review: `PlayerFavour` codec shape
unchanged (only the key semantic); core favour methods take `godId`, resolving tier
via the reverse index (single-god starters → identical numbers); the religion-facing
convenience fans out (writes to all gods, reads from primary); every caller recompiled
and was grep-audited — no bare core call passes a religion id (the `/religion miracle
list` straggler was fixed to `currentForReligion`); the divine-event content selection
+ `ReligionIdentity`/`Religion.deity()` are untouched; no new enum values; favour codec
key may change freely (no save-safety needed).

### Smoke test (user-runnable)

1. **Act → god favour.** As a Sunstead adherent (`/religion set <you> sunstead 0.6`)
   make an offering — confirm `/religion favour view` shows `the Sun-Mother
   (sun_mother)` favour risen by the same amount it used to (≈ +6 for OFFERING,
   GENEROSITY-aligned ×1.5 ⇒ +9), and that `/religion gods` / R9c show it per god.
2. **Decay/cap.** Confirm a devout-idle player holds the tier equilibrium for the god
   and over-cap service decays back — same numbers as before.
3. **Miracle spends the god.** `/religion favour grant sun_mother 50`, then
   `/religion miracle cast sun_healing_light` — confirm it spends the GOD's favour and
   is tier-gated exactly as before (`/religion miracle list` header shows the primary
   god's favour).
4. **Sacrilege.** `/religion sacrilege sunstead 40` (or steal as a Sunstead adherent)
   — confirm `sun_mother`'s favour drops into displeasure as before.
5. **Loom.** Confirm `the_pattern` (impersonal) favour works for the Loom's single god.
6. **Independence.** Confirm standing with each of the four gods is independent and
   matches pre-change behaviour.

## F1a sub-stage 3b — re-point the divine-EVENT layer to the god (2026-06-09)

(Foundation 1 — separating gods from religions. 3a made favour per-god; 3b makes
the event layer treat the GOD as the subject. Behaviour-preserving for single-god.)

### The rule applied

- **Deity attribute → from the `God`**: `domain`, `name`, `character`, `demands`,
  `rewards`, `virtues`, `taboos`. The event layer reads these from the resolved god.
- **Religion attribute → from the religion** (`ReligionIdentity`/`Religion`):
  `cosmology`, sacred history, the holy-day calendar, core tenets. Event text that
  references narrative still reads it from a religion that venerates the god.
- **Iteration subject → the god.** The ticks loop the player's gods, not the primary
  religion's one deity.

### Disposition (findings)

- `Miracles`/`Curses` are authored data carrying both a `religionId` AND a `domain`
  (1:1 today); lookups were religion-keyed (`forReligion`). The visions/wrath/
  theophany ticks all resolved the player's PRIMARY religion (`primaryFaith`) then
  its one deity (`ReligionIdentity.get(faith).deity().domain()` / `Religion.deity()`
  name) — none looped beliefs. Curse/colour/manifestation form all keyed on that
  domain. Favour is already per-god (3a, via the convenience layer).
- Plan: add `Curses.forDomain`/`Miracles.forDomain` (domain-keyed lookups — the
  tables stay `DeityDomain`-keyed, NOT relocated); add ONE shared
  `GodRegistry.playerGods(level, pid)` (the distinct gods of the player's belief
  religions) + `primaryReligionOf(godId)` (for narrative); iterate gods in the ticks;
  read deity attrs from the god; gate/spend god-keyed (3a core).

### What shipped

- **`GodRegistry`** — `playerGods(level, playerId)` (the one shared iteration subject)
  + `primaryReligionOf(godId)` (the religion-narrative source for a god).
- **`Curses.forDomain(domain, severity)`** + **`Miracles.forDomain(domain)`** —
  domain-keyed lookups (data unchanged).
- **`MiracleInvoker`** — `godFor(miracle)` (the miracle's religion's primary god);
  `status`/`cast` gate by the GOD's tier (`tierForGod`) + favour (`current(godId)`)
  and spend the GOD's favour (`spend(godId)`). The god is the subject of the gift.
- **`DivineVision`** — fully god-centric: `tick` iterates `playerGods` (per-god
  cooldown, ≤1 vision/tick); voice (`send`/`speak`) takes a `God` (name +
  `domainColor(god.domain())`); deity-attribute content (lore `character`, guidance
  `virtues`/`taboos`) from the god; narrative (cosmology/holy days) from the god's
  religion via `primaryReligionOf`. `speak(faith,…)` → `speak(God,…)`. The V3 calling
  hook stays religion-keyed (fires from `awardForReligion`), voiced by the religion's
  god.
- **`DivineWrath`** — per-god escalation: `tick` loops `playerGods`, reads each god's
  favour, selects curse content by `god.domain()` (`Curses.forDomain`), voices via
  the god; `onPlayerSacrilege` checks the GOD's taboos + offends the god;
  `armConsequenceCooldown(pid, godId, now)` (per god).
- **`DivineTheophany`** — per-god extremes: `tick` loops `playerGods`, reads each
  god's favour, milestones per god (`godId|favour`/`godId|wrath`); `fireFavour`/
  `fireWrath` take a `God`, form from `god.domain()`, voice via the god, write the
  god's favour, arm that god's curse cooldown.
- **Per-god test commands** — `/religion favour grant|spend|offend <god>` (god-keyed),
  `/religion theophany favour|wrath <god>`, `/religion miracle list` (per-god, union
  of the player's gods' miracles by domain). `/religion sacrilege <religion>` stays
  religion-relative; `/religion gods` (3a) is the god-id discovery surface.
- **Convenience-layer trim** — the event layer moving god-keyed orphaned
  `tierForReligion`/`spendForReligion`/`awardVirtueForReligion`; deleted them (the
  sweep). The remaining religion-facing API is exactly its callers:
  `awardForReligion` (acts), `offendForReligion`/`currentForReligion` (sacrilege
  debug), `addCappedForReligion` (calling bonus).

### Tie-in audit

1. **Upstream feeders.** `God` (domain/character/name/virtues/taboos),
   `GodRegistry.playerGods`/`primaryGod`/`primaryReligionOf`, per-god favour (3a).
   Confirmed.
2. **Downstream callers.** The six event files + the miracle/theophany/favour debug
   commands now resolve a god. **Grep-verified no event-layer path reads
   `ReligionIdentity.deity()`/`Religion.deity()`** for a deity attribute (clean).
3. **Sibling systems.** Sub-stage-4 files (`ReligionContent.invocation`,
   `ScriptureFactory`, the two snapshot builders, `FaithJudgment`, `FaithVoice`) are
   **untouched** (git-confirmed) — they still read deity attrs from `ReligionIdentity`
   (re-pointed next stage); since `God` derives from `ReligionIdentity`, the two
   sources agree, so behaviour is identical. The snapshot's miracle list still calls
   `MiracleInvoker.status` (now god-resolving internally) — unchanged signature.
4. **Exhaustive switches.** The `DivineVision` colour switch now switches on
   `god.domain()` (still 4 arms, no default); `DivineTheophany.particlesFor` on
   `god.domain()` (4 arms); `God.displayName` unchanged. No enum change.

### Simplification sweep

- One shared `playerGods` helper (single definition in `GodRegistry`) — all three
  ticks use it, none re-derive. Content tables stay `DeityDomain`-keyed (selected via
  `god.domain()`); the cleanup stage can later relocate them onto `God` + delete the
  `ReligionIdentity` deity duplication (noted, not acted on). Deleted the three
  orphaned religion-keyed favour helpers (no caller remained). Touched: `GodRegistry`,
  `Curses`, `Miracles`, `MiracleInvoker`, `DivineVision`, `DivineWrath`,
  `DivineTheophany`, `DivineFavour` (trim), `ReligionDebugCommand`. No orphan remains.

### Deviations from prompt

- The vision/wrath/theophany ticks previously used the PRIMARY religion (not a belief
  loop); 3b makes them iterate the player's gods (the prompt's intent). For
  single-faith players (the only ones today) that's the one god → identical; for a
  future syncretic player each god is now evaluated (the correct forward direction).
  Cooldowns/milestones became per-god to match (new world per test — no save concern).

### Out-of-scope but flagged

- **Sub-stage 4** — re-point virtues/taboos in `FaithJudgment`/`FaithVoice` + the four
  `Religion.deity()`-name consumers (`ReligionContent.invocation`, `ScriptureFactory`,
  the two snapshot builders); decide the multi-god union/primary policy.
- **Cleanup** — invert authoring onto `God`, relocate the `Miracles`/`Curses` tables +
  the nested types onto `God`, delete the `ReligionIdentity` deity duplication.
- **F1b** — per-world religions.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
[Container current; no reset.] Static review: every event-layer deity-attribute read
now comes from `God` (grep-confirmed zero `deity()` reads in the six files); the
content tables stay `DeityDomain`-keyed (`forDomain` lookups, data unmoved); the ticks
iterate the shared `playerGods`; miracle gate/spend + theophany/wrath favour are
god-keyed (3a core); the debug commands target god ids; sub-stage-4 files are
untouched (and `God` derives from `ReligionIdentity`, so single-god behaviour is
identical); the three orphaned convenience helpers were deleted; no new enum values.

### Smoke test (user-runnable)

1. **Miracles per god.** `/religion set <you> sunstead 0.9`, `/religion favour grant
   sun_mother 100`, `/religion miracle list` — confirm the Sun god's miracles list
   under `the Sun-Mother (sun_mother)` and `/religion miracle cast sun_healing_light`
   spends `sun_mother`'s favour, gated by its tier (same as before).
2. **Per-god curse escalation.** `/religion favour offend sun_mother 200` → confirm
   the omen→curse→wrath escalation fires the SUN-domain curses (Blight/Scorching),
   voiced as the Sun-Mother in yellow.
3. **Per-god theophany.** `/religion theophany favour sun_mother` and `… wrath
   sun_mother` — confirm the Sun manifestation form (light vs scorch/lightning), the
   right voice, and that it writes `sun_mother`'s favour.
4. **Other gods.** Repeat for `sea_mother` (Tidecall, sea form/aqua) and `the_pattern`
   (Loom, FATE/purple, **impersonal** — confirm the vision voice handles the empty
   name via the god's display fallback "the Pattern").
5. **Independence + colour/voice.** Confirm each god's events are driven by that
   god's favour independently, and visions' colour + character match the god's
   domain.
6. **Sub-stage-4 unchanged.** Confirm rite invocation text, scripture, and faith
   judgment read identically (they still source `Religion.deity()`/`ReligionIdentity`).

## F1a sub-stage 4a — finish the deity re-point + settle multi-god policy (2026-06-09)

(Foundation 1 — the last re-point. After 4a the `God` is the universal RUNTIME
source for every deity attribute; only the cleanup stage's deletion of the now-unread
duplication remains. Behaviour-preserving for single-god.)

### The rule (from 3b), applied to the rest

Deity attribute (name/domain/character/demands/rewards/virtues/taboos) → from the
`God`. Religion narrative (cosmology/sacred history/practices/calendar/tenets/
aesthetics) → stays on the religion.

### The multi-god policy (decided once, in `GodRegistry`)

- **Virtues & taboos (religion-level judgments) → UNION** across the religion's gods
  (`anyGodHoldsVirtue`/`anyGodHoldsTaboo`; `unionVirtues`/`unionTaboos`).
- **Offense targeting → the SPECIFIC god(s) whose taboo was broken** (`godsTabooing`)
  — not a blanket fan-out.
- **Headline deity NAME → the PRIMARY god's name**, falling back to the religion
  display name (`primaryDeityName(r, fallback)` — the old `deity().orElse(...)` shape;
  uses `God.name()` so an impersonal primary god yields the fallback, NOT the
  "the Pattern" voice form).
- **Domain-flavour → the relevant god's domain.**

### Disposition (findings) + re-point

1. **`FaithJudgment`** (NPC virtue/taboo judgment) read `ReligionIdentity.get(faith)
   .virtues()/taboos()`. → virtue/taboo recognition via the **union**
   (`GodRegistry.anyGodHoldsVirtue/Taboo`) for actor + witnesses; deleted the two
   private `ReligionIdentity`-based helpers.
2. **`FaithVoice`** (NPC dialogue) read `religion.deity()` + `id.deity().domain/
   rewards/demands` + `id.virtues()`. → name/domain/demands/rewards from the
   **primary god**; the virtue pool from the **union**; "the Pattern" stays the
   impersonal voice form (`god.name().orElse("the Pattern")`).
3. **`ReligionContent.invocation`** `deity().orElse(displayName)` → `primaryDeityName`.
4. **`ScriptureFactory`** title + body deity name/domain/character/demands/rewards →
   the **primary god** (the deity block is skipped for a god-less faith); the
   "We hold/forbid" lists → **union**; cosmology/sacred history stay the religion's.
   Dropped the defensive `id.deity()` fallback (god-only).
5. **`TempleSnapshotBuilder`** `deity().orElse("")` → `primaryDeityName(r, "")`.
6. **`PlayerReligionSnapshotBuilder`** the deity-NAME field → `primaryDeityName`
   (per-god rows are 4b — NOT added). Also fixed the theophany banner: its milestone
   key is `godId|pole` (3b), so it now resolves the GOD's display name
   (`GodRegistry.find(...)`) instead of `Religion.deity()` (a 3b straggler).
7. **Offense targeting** — `DivineWrath.onPlayerSacrilege` now offends the SPECIFIC
   god(s) whose taboo was broken (`godsTabooing`), via the core `offend(godId,…)`
   (refines the 3b primary-god approach; single-god → identical). Removed the orphaned
   `DivineWrath.primaryGod`.
8. **`/religion identity` debug readout** — re-pointed its deity name/domain/
   character/demands/rewards/virtues/taboos to the god (cosmology/history/aesthetics/
   practices stay the identity), so the readout no longer reads the deity duplication.

### THE CLEANUP-GATE CONFIRMATION (what the cleanup stage depends on)

After 4a, the **only runtime reader** of `Religion.deity()` /
`ReligionIdentity.deity()` / `ReligionIdentity.virtues()/taboos()` is
**`GodRegistry.derive()`** (the god-derivation itself, lines ~227-231) — grep-verified.
Every other `ReligionIdentity.get(...)` call reads only the NARRATIVE
(cosmology/sacred history), which correctly stays on the religion. The cleanup stage
may now invert authoring onto `God` and delete the `ReligionIdentity` deity layer +
`Religion.deity()` with no remaining readers.

### Tie-in audit

1. **Upstream feeders.** `God` (attributes), `GodRegistry.primaryGod/godsFor` + the
   new policy helpers (`primaryDeityName`/`anyGodHolds*`/`godsTabooing`/`union*`), the
   core `offend(godId,…)`. Confirmed.
2. **Downstream callers.** The six scoped files + `DivineWrath.onPlayerSacrilege` +
   the `/religion identity` readout — grep-confirmed none read a deity attribute from
   `ReligionIdentity`/`Religion.deity()` after the change (only `derive()` does).
3. **Sibling systems.** 3a/3b unaffected. `God` derives from `ReligionIdentity`, so
   the two sources still agree — single-god behaviour (invocation text, scripture,
   judgments, offense targets/amounts) is identical.
4. **Exhaustive switches.** No enum change; the two `DeityDomain` switches
   (`DivineVision` colour, `DivineTheophany`/`FaithVoice` greetings) and `God.displayName`
   unaffected.

### Simplification sweep

- The multi-god policy lives in ONE place (`GodRegistry`'s six policy helpers). Deleted
  three orphans: `FaithJudgment.holdsVirtue/holdsTaboo` (ReligionIdentity-based) and
  `DivineWrath.primaryGod`. Touched: `GodRegistry` (+policy), `FaithJudgment`,
  `FaithVoice`, `ReligionContent`, `ScriptureFactory`, `TempleSnapshotBuilder`,
  `PlayerReligionSnapshotBuilder`, `DivineWrath`, `ReligionDebugCommand`. The only
  remaining `deity()`/identity-virtue/taboo reader is `GodRegistry.derive` (the
  cleanup target).

### Deviations from prompt

- Touched two files beyond the six-file list, justified by the policy + gate:
  `DivineWrath.onPlayerSacrilege` (the offended-god(s) targeting the policy mandates
  "at the judgment call site") and the `/religion identity` debug readout (so the
  cleanup-gate is truly clean — no remaining deity-attribute reader outside `derive`).
- `PlayerReligionSnapshotBuilder` also got its theophany-banner god-key resolution
  fixed (a 3b straggler surfaced by removing the `deity()` read) — still only the
  deity-NAME field changed semantically; no per-god rows (4b).

### Out-of-scope but flagged

- **4b** — the per-god player-religion SCREEN (favour/standing/theophany per god;
  `PlayerReligionSnapshotBuilder` per-god rows + the screen layout).
- **Cleanup** — invert authoring onto `God`, relocate `Miracles`/`Curses` + the nested
  types onto `God`, and **delete the `ReligionIdentity` deity duplication +
  `Religion.deity()`** (4a confirmed no readers remain except `derive`).
- **F1b** — per-world religions.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
[Container current; no reset.] Static review: every scoped consumer now reads deity
attributes from the `God` (grep-confirmed the only `.deity()`/identity-virtue/taboo
reader is `GodRegistry.derive`); the headline-name helper uses `God.name()` with the
old fallback shape (impersonal → religion display name, NOT "the Pattern");
virtues/taboos for religion-level judgments use the union; offense targets the
offended god(s); cosmology/history stay the religion's; `God` derives from
`ReligionIdentity` so single-god output is byte-identical; three orphans deleted; no
new enum values; no codec/save change.

### Smoke test (user-runnable)

1. **Unchanged headline text.** For each faith confirm a priest's rite invocation, a
   generated scripture's title + body, and a temple's displayed deity read identically
   (the Loom showing its religion name "The Loom" where its god `the_pattern` is
   impersonal). `/religion identity <faith>` reads the same.
2. **Offense targets one god.** As a Sunstead adherent commit a Sunstead taboo (steal
   → GREED) — confirm ONLY `sun_mother`'s favour drops (`/religion favour view`); the
   other gods are unaffected; the guilt/witness judgment still fires (right virtues).
3. **NPC voice unchanged.** Confirm a priest's faith dialogue lines read as before
   (Sun-Mother greeting/blessing/virtue; the Loom's Pattern phrasing).
4. **Player screen.** `/religion me` opens and shows the (now god-sourced) deity name
   unchanged; the theophany banner shows the god's display name. (Per-god rows = 4b.)
5. **No regressions.** Favour/miracles/curses/visions/theophany (3a/3b) behave exactly
   as before for all four single-god faiths.

## F1a sub-stage 4b — the per-god player-religion screen (2026-06-09)

(Foundation 1 — the presentation half of sub-stage 4. The runtime is god-driven
(3a/3b/4a); this surfaces the divine relationship PER GOD. A display redesign —
values unchanged, regrouped per god + enriched.)

### Disposition (findings)

- **`Gui/PlayerReligionScreen`** (Path A migration) — `Screen` on `Chrome.COMPACT`
  (320×240) + `Gui.Framework` (`Chrome`/`Pill`/`NeedMeter`/`ScrollList`/
  `StyledButton`), a `ScrollList<CalendarRow>`. The divine info was scattered:
  favour on the piety line, miracles on the beliefs line, theophany on the title.
- **`OpenPlayerReligionPacket`** — flat divine fields: `favourSummary`/
  `miracleSummary` (`List<String>`) + a single `theophany` string; hand-written
  `StreamCodec` (count+loop idiom; `CalendarRow` nested).
- **`PlayerReligionSnapshotBuilder`** — already gathered favour **per god** (3a);
  miracles were still `forReligion(primaryFaith)` (primary-only) and theophany the
  single most-recent string (4a fixed its god-key resolution). Stale primary-faith /
  single-string remnants to convert.
- **`rites.theophanies(playerId)`** — per-god store, keys `godId|pole`, value tick.
- Framework check: `ScrollList<T>` (generic), `Pill`, `NeedMeter` cover the need; no
  new primitive required. Packet already registered (`registerPayloads`) — only the
  payload shape changes, `TYPE`/`CODEC`/handler unchanged.

### What shipped

- **`OpenPlayerReligionPacket`** — replaced the three flat divine fields with a
  structured **`List<GodStanding>` gods**. `GodStanding(String name, int favour,
  String band, List<String> miracles, List<String> theophanies)` (band =
  `DispleasureTier.name()`; miracles each "Name glyph"; theophanies each
  "glory (Nd)"/"wrath (Nd)"). Compact ctor `List.copyOf`s the row + its inner lists.
  Extended the `StreamCodec` following the existing count-then-loop idiom, with the
  nested inner-list framing (write/read orders verified identical); favour written as
  a signed `int`. Religion-level fields (faith/piety/beliefs/tithe/observance/
  calendar/`activeCalling`) unchanged.
- **`PlayerReligionSnapshotBuilder`** — builds the per-god `GodStanding` list over the
  standing set (primary faith's gods first, then favour-entry gods, then belief
  gods): per god — `DivineFavour.current` + `displeasureOf` band; that god's miracles
  (`Miracles.forDomain(god.domain())` + `MiracleInvoker.status` glyph, per 3a/3b
  gating); the god's theophany history from `rites.theophanies` filtered to its
  `godId|pole` keys with day-recency. Deleted the old flat
  `favourSummary`/`miracleSummary`/`theophany` build code.
- **`PlayerReligionScreen`** — a `ScrollList<GodStanding>` "Gods" section (3-line rows:
  name + signed favour band-coloured; the god's miracles; its theophany history)
  above the kept `ScrollList<CalendarRow>`; the faith header (name/piety bar+tier
  pill/piety%/beliefs), tithe pledge, and calling/observance rows kept. Band colour
  via a `String` switch (OMEN→amber, CURSE/WRATH→red, NONE→green) — all arms +
  default. A `clip()` helper truncates overflow. Read-only (Close only); both lists
  route scroll + click. Removed the old favour/miracle/theophany render.

### Tie-in audit

1. **Upstream feeders.** `DivineFavour.current`/`displeasureOf`, `Miracles.forDomain`
   + `MiracleInvoker.status` (per god), `rites.theophanies`, `GodRegistry`/`God`, the
   player's-gods standing set. Confirmed.
2. **Downstream callers.** The packet is built in the snapshot path and handled
   client-side into the screen; the `StreamCodec` write order == read order
   (re-verified, incl. the nested miracle/theophany lists); `TYPE`/`CODEC`/handler +
   the `registerPayloads` registration are unchanged, so `/religion me` still opens
   the screen.
3. **Sibling systems.** No server logic changed — favour/miracle/theophany runtime
   (3a/3b/4a) untouched; the 3b debug commands still function (the screen complements
   them). The snapshot is the only producer; the screen the only consumer.
4. **Exhaustive switches.** Reused `MiracleInvoker.Status` (glyph switch — all arms)
   and `DispleasureTier` (the band string, built in the snapshot); the screen's band
   colour switch over the band String covers OMEN/CURSE/WRATH + default. No new enum.

### Simplification sweep

- The flat `favourSummary`/`miracleSummary`/`theophany` strings (field + builder +
  render) are fully replaced by the `GodStanding` structure — no orphaned
  string-summary path remains (grep-confirmed). Touched: `OpenPlayerReligionPacket`
  (restructured), `PlayerReligionSnapshotBuilder` (per-god build), `PlayerReligionScreen`
  (per-god render). One producer, one consumer; no duplicate.

### Deviations from prompt

- Per-god rows render the miracle list + theophany history inline (3-line rows,
  clipped to the COMPACT width) rather than a hover tooltip — keeps it read-at-a-glance
  without `TooltipLayer` wiring; the full miracle names also remain in `/religion
  miracle list`.
- The single-string theophany title mark (V5) is removed; theophany now lives in each
  god's row (history, not just the latest).

### Out-of-scope but flagged

- **Cleanup stage** — invert authoring onto `God`, relocate `Miracles`/`Curses` + the
  nested types onto `God`, and delete the `ReligionIdentity` deity duplication +
  `Religion.deity()` (4a confirmed no readers remain except `GodRegistry.derive`).
- **F1b** — per-world religions.
- This **completes the F1a re-point + surfacing** (3a/3b/4a/4b); only the cleanup
  stage remains before F1b.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava` 403s on `neoform-runtime` before javac; no javac errors surfaced).
[Container current; no reset.] Static review (per the litv-gui-screen contract):
uses only `Gui.Framework` primitives (`Chrome.draw` 5-arg + `PARCHMENT`, `ScrollList`,
`Pill`, `NeedMeter`, `StyledButton`) — no manual panel/scrollbar drawing; the packet
`StreamCodec` write/read orders are identical incl. the nested inner lists (count +
loop), favour as signed `int`; the row renderers match the `RowRenderer` signature;
both lists route scroll/click before `super`; `TYPE`/`CODEC`/handler + payload
registration unchanged; the old flat fields + their build/render code are deleted (no
orphan); single-god data shows as one god row, identical values.

### Smoke test (user-runnable)

1. **Per-god row.** As a Sunstead adherent (`/religion set <you> sunstead 0.9`,
   `/religion favour grant sun_mother 60`) open `/religion me` — confirm a `the
   Sun-Mother` row showing favour (+60, green), her miracles with ✓/⏳/🔒 glyphs, and
   "—" theophany.
2. **Bands + theophany update.** `/religion favour offend sun_mother 200`, reopen —
   confirm the row goes red with the band label (cursed/WRATH); `/religion theophany
   favour sun_mother` then `… wrath sun_mother`, reopen — confirm both glory + wrath
   marks appear with day-recency.
3. **Impersonal god.** As a Loom adherent confirm the `the Pattern` row renders with a
   clean name (no blank), FATE miracles.
4. **Syncretic.** Give a player belief in two faiths (`/religion set` twice) — confirm
   a row per god, each with its own favour/miracles/theophany.
5. **Religion-level unchanged.** Confirm the faith header, beliefs, tithe pledge,
   calling, observance, and the calendar list read as before.
6. **No desync.** Confirm the screen opens cleanly (StreamCodec round-trips — no
   client disconnect) and both lists scroll.

## F1a sub-stage 5 — cleanup: invert authoring onto God, delete the duplication (2026-06-09)

This **completes F1a**. Through 1→4b the `God` was real but *derived*: `GodRegistry`
built each god from the religion's `ReligionIdentity` (deity name/domain/character/
demands/rewards + virtues/taboos) and `Religion.deity()`, so two sources of truth
were kept deliberately in agreement. 4a proved the gate — the ONLY remaining readers
of `Religion.deity()` / the identity deity fields were `GodRegistry.derive()` itself.
This stage inverts the authoring (the god is now hand-authored, the source of truth),
relocates the deity TYPES out of `ReligionIdentity`, and deletes the duplication in
the same change (convert-then-delete).

### Disposition (investigation pass)

- **Authoring inversion.** `GodRegistry.ensureInit` no longer calls a `derive(godId,
  religionId)` that reads `ReligionIdentity`; it registers four hand-authored gods
  (`sunMother`/`thePattern`/`seaMother`/`forgeFather`) whose content is the verbatim
  deity material that used to live on the identity. The reverse index
  (`RELIGIONS_BY_GOD`) is still built from `ReligionRegistry.all()` + `godIds()`.
- **Type relocation.** `DeityDomain`, `Virtue`, `Taboo` were nested in
  `ReligionIdentity`. They are god concepts, so they became standalone top-level
  records/enum in `Npc.Religion` (`DeityDomain.java`, `Virtue.java`, `Taboo.java`).
  Top-level (vs. nesting on `God`) minimises reference churn — every consumer already
  in-package now names them bare; the 11 files the bulk rename touched
  (`God`, `GodRegistry`, `Miracle(s)`, `Curse(s)`, `DivineVision/Wrath/Theophany`,
  `FaithVoice`, `ScriptureFactory`) only had `ReligionIdentity.` prefixes stripped.
- **Deletion.** `Religion.deity()` (component + compact-ctor guard + codec
  `optionalFieldOf("deity")`) deleted → `Religion` goes **9→8 codec fields**; the four
  `ReligionRegistry` factory calls drop the deity arg. `ReligionIdentity` loses its
  `Deity` record, the `deity`/`virtues`/`taboos` fields, and the nested
  `DeityDomain`/`Virtue`/`Taboo` types; it is now a pure narrative record
  `(religionId, cosmology, SacredHistory, Aesthetics, practices)`.

### Tie-in audit

1. **Upstream feeders.** `GodRegistry` authoring no longer feeds off `ReligionIdentity`
   — it is the source. `ReligionRegistry.godIds()` still feeds the reverse index
   (unchanged).
2. **Downstream callers (grep of `Religion.deity()` / identity deity members).**
   - `GodRegistry.derive()` — deleted (replaced by authored gods).
   - **`NpcProfileSnapshotBuilder`** (line 173) — a STRAGGLER the 4a audit missed:
     it still read `religion.flatMap(Religion::deity)` for the NPC profile's
     `deityName`. The grep (not javac — the field still existed until this stage)
     caught it, exactly as the cleanup-gate predicted. Re-pointed to
     `GodRegistry.primaryDeityName(r, "")`, which uses `God.name()` so an impersonal
     god (the Loom) yields the empty fallback — **behaviour-identical** to the old
     `Optional<deity>.orElse("")`.
   - `ReligionDebugCommand` (`/religion identity`) — already migrated in 4a to
     `primaryGod` + `unionVirtues`/`unionTaboos`; reads only kept identity narrative.
   - `FaithVoice`, `ScriptureFactory`, `FaithJudgment`, `DivineVision/Wrath/Theophany`,
     `Miracle(s)`/`Curse(s)`, `PlayerReligionSnapshotBuilder` — all already god-keyed
     from 3a–4b; only same-package imports cleaned here.
3. **Sibling systems.** Persistence — `Religion.CODEC` drops a field; a pre-cleanup
   save that stored a `"deity"` key simply ignores it on load (the other 8 fields are
   all `optionalFieldOf`), and `Religion` is content, not saved per-world, so there is
   no migration. `RiteSavedData` god-keyed favour/calling/theophany untouched.
4. **Exhaustive switches.** No enum values added/removed (`DeityDomain` relocated
   verbatim — same 4 constants); the `switch (domain)` in `God.displayName`,
   `FaithVoice.domainGreeting`, `DivineTheophany.particlesFor`/`manifest`,
   `Curses.forDomain` callers, etc. are unchanged and still exhaustive.

### Simplification sweep

- **Orphan deleted:** `GodRegistry.derive(godId, religionId)` (the last reader of the
  identity deity duplication) — removed with the duplication it bridged.
- **Duplication collapsed:** the deity content existed twice (authored on
  `ReligionIdentity`, mirrored onto `God`). Now once, on `God`. `ReligionIdentity`
  shrank from 8 record components-worth of deity+narrative to 5 narrative-only.
- **Import noise:** the bulk `ReligionIdentity.{DeityDomain,Virtue,Taboo}`→bare rename
  left redundant same-package imports across 9 files; all removed.
- **Left intentionally (out of scope, flagged below):** `Religion.sacredLocations`
  (dead field, not part of this stage); `Miracles`/`Curses` stay `DeityDomain`-keyed
  data (`forDomain`) — the relocation of those tables onto `God` is a later stage.

### Deviations from prompt

- The prompt scoped the readers to "4a confirmed no readers remain except
  `GodRegistry.derive()`." In fact **`NpcProfileSnapshotBuilder` was a second live
  reader** of `Religion.deity()`. Fixed in scope (re-pointed to
  `primaryDeityName`, behaviour-preserving). This is the grep-is-the-safety-net case
  the cleanup-gate exists for; no scope expansion beyond the one-line re-point.
- Stale javadoc that named the now-deleted `Religion.deity()` as a `{@link}` (in
  `God`, `FaithVoice`, `ScriptureFactory`) was corrected to describe the inverted
  authoring (dangling `{@link}` targets would warn under doclint). Historical
  `{@code Religion.deity()}` mentions (code font, not links) left as accurate
  past-tense notes.

### Out-of-scope but flagged

- **Relocating `Miracles`/`Curses` onto `God`** — they remain `DeityDomain`-keyed
  data accessed via `forDomain`; the god is the subject but does not yet OWN the
  table. A later stage (era-2 per-god miracle/curse sets) can move them.
- **`Religion.sacredLocations`** — still a dead field; deliberately left (not this
  stage's concern).
- **F1b** — per-world religions / pantheons (multiple gods per faith). The
  multi-god policy (union virtues/taboos, specific-god offense, primary-god headline)
  is already centralised in `GodRegistry`, so F1b is data, not re-plumbing.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava --offline` cannot resolve `neoform-runtime:2.0.18` before javac; no javac
errors surfaced). [Container current; no reset.] Static review: `Religion` is a clean
8-field record/codec; `ReligionIdentity` is a 5-field narrative record with its
`build()` arities matching; the three relocated types are valid top-level
records/enum in-package; `GodRegistry` authors four gods with `FaithConcept` values
that already existed (copied verbatim); no residual `Religion.deity()` /
`ReligionIdentity.{Deity,DeityDomain,Virtue,Taboo}` references (grep-clean); the only
`new Religion(` callers are the four updated factories; `.virtues()/.taboos()` reads
are all on `God`.

### Smoke test (user-runnable)

1. **Gods author identically.** `/religion identity sunstead` — confirm the Deity
   line still reads `the Sun-Mother` (domain SUN) with the same character/demands/
   rewards, and the same two virtues + two taboos as before the cleanup.
2. **Impersonal god intact.** `/religion identity the_loom` — confirm NO Deity line
   value beyond the fallback (the Pattern is impersonal: `primaryDeityName` falls back
   to the display name), cosmology/history/practices unchanged.
3. **NPC profile deity name.** Open an NPC profile for a Sunstead adherent — confirm
   the deity field reads `the Sun-Mother`; for a Loom adherent it reads blank (was
   blank before — `Religion.deity()` was empty for the Loom).
4. **Favour/miracles/visions unchanged.** Repeat any V1–V5 / 4b smoke step (e.g.
   `/religion favour grant sun_mother 60`, open `/religion me`) — confirm identical
   behaviour; the god content is authored from the same words, just sourced from
   `GodRegistry` now.
5. **Save compatibility.** Load a world saved before this stage — confirm religions
   load cleanly (the dropped `"deity"` codec key is ignored) and faith behaviour is
   unchanged.

## F1b sub-stage 1a — stand up the per-world religion store (additive scaffolding) (2026-06-09)

Opening step of F1b ("religions become per-world instances"). Pure additive
scaffolding, like F1a-1: a per-world `ReligionSavedData` (seeded from the static
templates) + a thin `Religions` facade exist and are verifiable, but **no caller is
migrated** — every existing lookup still routes through the static `ReligionRegistry`.
Sub-stage 1b does the caller migration under compiler/grep coverage.

### Disposition (investigation)

1. **SavedData idiom to mirror.** `RiteSavedData` (the existing separate religion
   SavedData) is the template: a `static final SavedDataType<T> TYPE = new
   SavedDataType<>("life_in_the_village_rites", T::new, RecordCodecBuilder…apply(i,
   T::fromCodec))`, `static T get(ServerLevel) { return
   level.getDataStorage().computeIfAbsent(TYPE); }`, `setDirty()` on mutation (it
   exposes `markDirty()`). `VillageSavedData` uses the same 3-arg `SavedDataType` +
   `computeIfAbsent(TYPE)`. **Decision: `ReligionSavedData` is a SEPARATE SavedData
   (own storage name `life_in_the_village_religions`), NOT folded into
   `VillageSavedData`** — religions are a distinct concern with their own lifecycle
   (later dynamism founds/schisms them), and a separate store keeps that isolated, as
   `RiteSavedData` already does for rites/piety.
2. **`Religion.CODEC`** — round-trips the 8 post-F1a fields (id, displayName,
   coreTenets, rites, sacredLocations, calendar, preferredBookCategories, godIds);
   all list/optional fields `optionalFieldOf`. The store serializes
   `Religion.CODEC.listOf()` and rebuilds the id-keyed map in `fromCodec` (the rites
   idiom — avoids the key/`id()` duplication an `unboundedMap` would carry).
3. **Template source** — `ReligionRegistry.all()` (the four seeded faiths
   sunstead / the_loom / tidecall / forge_creed). The store copies these on seed.
4. **Seed timing** — chose **lazy seed-in-`get` when empty** (templates are static,
   available any tick; no dependence on the `WorldgenKingdomSeeder` first-tick hook).
   `get()` calls `seedIfEmpty()`, which copies the templates ONLY when the map is
   empty (a fresh world) and `setDirty()`s; a loaded world's restored set is never
   re-seeded/clobbered.

### What shipped

- **`Npc/Religion/ReligionSavedData.java`** (new) — `SavedData` holding
  `LinkedHashMap<String, Religion>` (stable order). `TYPE` =
  `SavedDataType<>("life_in_the_village_religions", ReligionSavedData::new,
  Religion.CODEC.listOf() codec)`. `get(level)` → `computeIfAbsent(TYPE)` +
  `seedIfEmpty()`. Read surface `find(id)` / `get(id)` / `all()`; mutation entry
  `put(Religion)` (replace-in-map by `id()` + `setDirty()`, no caller in 1a) +
  `markDirty()`.
- **`Npc/Religion/Religions.java`** (new) — thin facade: `find(level,id)`,
  `get(level,id)`, `all(level)` → `ReligionSavedData.get(level).…`. The one home 1b's
  call sites move onto.
- **`/religion world list`** (in `ReligionDebugCommand`) — the ONLY consumer this
  stage: lists the per-world store (id + displayName + godIds + count), labelled
  distinct from the static `/religion list`, so the seed + reload-persistence are
  verifiable in-world.

### Tie-In Audit

1. **Upstream feeders.** `ReligionRegistry.all()` (seed source — read-only, copied
   into the store) and `Religion.CODEC` (persistence — read-only). Neither mutated.
2. **Downstream callers.** **None besides the `/religion world list` readout** — this
   is the whole point of 1a: the store has zero consumers until 1b migrates them.
   Every `ReligionRegistry.get/find/all`, `dominantReligionFor`, the `GodRegistry`
   reverse index, and `CalendarView.upcomingAcross(...)` still read the STATIC
   registry, unchanged.
3. **Sibling systems.** `RiteSavedData` — sibling SavedData; the new store has a
   distinct storage name (`…_religions` vs `…_rites`) so they coexist cleanly in
   separate `.dat` files. `GodRegistry` / the divine layer — untouched, still
   static-registry-driven (its per-world reverse index is 1b).
4. **Exhaustive switches.** None touched (no enum/sealed change). Confirmed.

### Simplification Sweep

- New classes + inbound callers: `ReligionSavedData` — 1 caller (`Religions` facade)
  + reflective `TYPE` use; `Religions` — 1 caller (`/religion world list`). The
  mutation `put`/`markDirty` have **zero** callers in 1a (the deliberate seam for
  later stages).
- **Noted, not acted on:** the store will let 1b retire the static `ReligionRegistry`
  to TEMPLATE-only (its `get/find/all` accessors restricted, compiler-enforcing the
  caller migration). Not done here — 1a is additive only.

### Deviations from prompt

- None. Built exactly as specified: separate string-keyed SavedData mirroring
  `RiteSavedData`, lazy seed-when-empty, `Religions` facade, single `/religion world
  list` consumer, no caller migration.
- Minor authoring choice within spec: serialized `Religion.CODEC.listOf()` (rebuilt
  to the map in `fromCodec`) rather than `unboundedMap(STRING, Religion.CODEC)` — the
  prompt offered either; the list form avoids duplicating each id as both the map key
  and `Religion.id()`, matching the `RiteSavedData` rites idiom.

### Out-of-scope but flagged

- **F1b sub-stage 1b** — migrate the ~49 lookup sites + `dominantReligionFor` + the
  `GodRegistry` per-world reverse-index build + the `CalendarView.upcomingAcross(...)`
  calendar enumeration onto `Religions`, then restrict the static `ReligionRegistry`
  accessors to template-only (compiler-enforced coverage of the migration).
- **F1b-2** — interreligious relations.
- **Dynamism** (founding / schism / conversion) — later; this store's `put` +
  only-when-empty seed are the seams it will use.
- `ReligionIdentity` narrative stays static/template, resolved by id (per design — no
  change here).

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava --offline` cannot resolve `neoform-runtime:2.0.18` before javac; no javac
errors surfaced). [Container current; no reset.] Static review: `ReligionSavedData`
mirrors `RiteSavedData` field-for-field (3-arg `SavedDataType`, `RecordCodecBuilder`
+ `fromCodec` static, `computeIfAbsent(TYPE)` in `get`, `setDirty` on seed/`put`); the
codec has one `optionalFieldOf` collection field (well under the 16-field ceiling);
`Religions` is pure delegation; the command handler reads `Religions.all(level)` only.
No existing caller touched (grep: the only new inbound references to the store are the
facade and the readout).

### Smoke test (user-runnable)

1. **Seed on a fresh world.** New world → `/religion world list` — confirm it shows
   the four religions (`sunstead`, `the_loom`, `tidecall`, `forge_creed`) with their
   displayNames + godIds, count 4, identical to the static `/religion list`.
2. **Static path unchanged.** `/religion gods`, `/religion list`, `/religion identity
   sunstead`, and any V1–V5 favour/miracle flow — confirm all behave exactly as
   before (nothing migrated; the store has no readers but the new readout).
3. **Persistence across reload.** Save + quit + reload the world → `/religion world
   list` — confirm the four religions still listed (the store round-tripped via
   `Religion.CODEC`).
4. **No re-seed clobber.** After reload (store non-empty), confirm the list is still
   exactly four (seed runs only-when-empty; it does not append duplicates or reset a
   saved set). A later non-template set would likewise survive reload unre-seeded.

## F1b sub-stage 1b — migrate every religion lookup to the per-world store (2026-06-09)

The big behaviour-preserving swap. 1a stood up `ReligionSavedData` + the `Religions`
facade with no callers; 1b points **every real religion lookup** at the per-world
store, then **locks the static `ReligionRegistry` accessors to template-only** so any
straggler fails to compile. The world now owns its religions end-to-end; behaviour is
identical (the seeded store equals the old static set). **This completes the
per-world-ify (F1b-1).**

### Disposition (caller inventory + plan)

- **`get`/`find`/`all` sites (45, grep-audited).** 44 migrated to `Religions.*(level)`;
  the 45th is the SEEDER (`ReligionSavedData.seedIfEmpty`), which stays on the static
  catalog — now `ReligionRegistry.templates()`. Almost every site already had a
  `ServerLevel` (the divine layer, rite scheduling/execution, building faith, temple +
  player snapshot builders, event scheduler, commands are all server-side).
- **Level-threading where a site lacked one** (each flagged):
  - `FaithJudgment.judge(actor, …)` and `FaithVoice.line(npc, …)` — derive the
    (server) level from the NPC entity (`actor.level() instanceof ServerLevel`);
    witnesses share the actor's level. No caller-signature change (the entity carries
    the level).
  - `ScriptureFactory.scriptureStack/scriptureRecord/title/body` — gained a
    `ServerLevel` first param; the 2 callers (`TempleProsperity`,
    `MonkProductionBehavior`) thread their level.
  - `ReligionContent.invocation/tenet` — gained `ServerLevel`; `RiteExecutor`'s
    `riteFlavorSuffix`/`confessionTenetSuffix` thread level (all 5 `handleX` call
    sites already carry it).
  - `MiracleInvoker.godFor(Miracle)` → `godFor(ServerLevel, Miracle)`; both internal
    callers (`status`/`cast`) have level.
  - 3 command suggestion lambdas → `c.getSource().getLevel()`; 3 handlers
    (`handleGods`/`handleIdentity`/`handleList`) gained `ServerLevel level =
    src.getLevel();`.
- **`GodRegistry` per-world links.** Deleted the static `RELIGIONS_BY_GOD` reverse
  index (and its init-time build loop); `religionsVenerating`/`primaryReligionOf`
  **gained a `ServerLevel`** and compute on demand from `Religions.all(level)`
  (religions are few; it walks the in-memory store, not disk). `playerGods(level,pid)`
  keeps its signature, resolves beliefs via `Religions.find(level, rid)`.
  `godsFor`/`primaryGod` are **unchanged** — gods stay GLOBAL; they resolve a
  religion's `godIds` against the global god catalog and need no level.
- **`DivineFavour.tierFor`** threaded level (the favour-cap path): `tierForGod` →
  `tierFor(level, …)` → `religionsVenerating(level, godId)`. The cap resolves
  identically (best belief among venerating religions; single-god starters → the one
  religion).
- **`dominantReligionFor`** — unchanged public culture→default-id helper; its callers
  resolve the returned id against the per-world store via the migrated lookups.
- **`CalendarView.upcomingAcross(...)`** — arg swapped to `Religions.all(level)`.
- **`ReligionIdentity.get` (narrative)** — NOT migrated; narrative stays
  static/template, resolved by id (per the design).

### The coverage lock (compiler proof)

`ReligionRegistry.get/find/all` were renamed to a single package-private
**`templates()`** (template-named, seeder-only). With the old public names gone, every
straggler that still reached for the static registry **fails to compile** — the
compiler proving the migration complete. The id constants and `dominantReligionFor`
stay public (seed/config).

### Tie-In Audit

1. **Upstream feeders.** `ReligionSavedData`/`Religions` are now the lookup source;
   the global god catalog (`GodRegistry.GODS`) is unchanged; `ReligionRegistry`
   demoted to seed templates (read only by the seeder).
2. **Downstream callers.** All 44 live `get/find/all` sites migrated (inventory above).
   `GodRegistry.religionsVenerating`/`primaryReligionOf` now level-threaded; their only
   callers (`DivineFavour.tierFor`, `DivineVision.deliver`) updated. The favour cap
   (`tierForGod` → `religionsVenerating`) resolves identically. The calendar
   enumeration + every debug command migrated.
3. **Sibling systems.** The divine layer (favour / miracles / visions / wrath /
   theophany) now reads religions per-world — behaviour identical (seeded set = old
   static set). `ReligionIdentity` narrative still static. `RiteSavedData` unaffected
   (separate store).
4. **Exhaustive switches.** None touched (no enum change). Confirmed.

### Simplification Sweep

- **Stragglers the coverage lock surfaced (grep found them first, both METHOD
  REFERENCES my `(`-suffixed grep initially skipped):**
  `NpcProfileSnapshotBuilder` (`ReligionRegistry::find` → `Religions.find(level, …)`)
  and `PietyComponent.attendsRite` (`ReligionRegistry::find`). This is exactly the
  F1a-style hidden-reader case the dual grep-+-compiler mechanism exists for.
- **Orphan deleted:** `PietyComponent.attendsRite(Rite)` had **zero callers** —
  removed (rather than thread a level into a data component that shouldn't know the
  world store; per-world rite-ritualisation goes through
  `RiteScheduler.religionRitualises(level, …)`).
- **Field removed (no orphan):** `GodRegistry.RELIGIONS_BY_GOD` static reverse index
  (+ its init build loop) → replaced by on-demand computation.
- **Accessors collapsed:** `ReligionRegistry` `get`/`find`/`all` → one package-private
  `templates()` (the unused `find` and a symmetric `template(id)` dropped — no
  callers).
- **Touched classes:** `GodRegistry`, `DivineFavour`, `DivineVision`, `DivineWrath`,
  `FaithJudgment`, `FaithVoice`, `FaithHistory`, `ScriptureFactory`, `ReligionContent`,
  `MiracleInvoker`, `RiteScheduler`, `RiteExecutor`, `TempleProsperity`,
  `TempleSnapshotBuilder`, `BuildingFaith`, `PlayerReligionSnapshotBuilder`,
  `MonkProductionBehavior`, `PietyComponent`, `NpcProfileSnapshotBuilder`,
  `VillageEventScheduler`, `ReligionDebugCommand`, `ReligionRegistry`,
  `ReligionSavedData`.

### Deviations from prompt

- **Caller count.** The prompt estimated ~49 lookup sites; the actual `get/find/all`
  inventory was 45 (44 live + the seeder), plus 2 method-reference stragglers the
  `(`-form grep missed and the broadened grep/compiler caught. Same coverage outcome.
- **`/religion list`** (the pre-existing command) now reads the per-world store too
  (data-identical to before), so it and the 1a `/religion world list` show the same
  set — acceptable; de-duping the two commands is out of scope.
- Two helpers derive level from the NPC entity rather than gaining a param
  (`FaithJudgment.judge`, `FaithVoice.line`) — lower churn, and the entity always
  carries its (server) level for these server-only paths.

### Out-of-scope but flagged

- **F1b-2** — interreligious relations (KINDRED / NEUTRAL / RIVAL / HERETICAL, a
  god-overlap baseline, per-world; `FaithReconciliation` + Kingdom-tension consumers).
- **Dynamism** — founding / schism / conversion, using `ReligionSavedData.put` (the
  mutation seam stood up in 1a, still caller-less).
- **Official-religion-law → village-faith wiring** — deferred behaviour change;
  `dominantReligionFor` stays culture-default for now.
- **`ReligionIdentity` per-world** — narrative stays template (by design).

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava --offline` fails resolving `neoform-runtime:2.0.18` before javac; no javac
errors surfaced). [Container had drifted to the R9c base on resume; `git reset --hard`
to the F1b-1a remote tip restored all pushed work before this stage — no work lost.]
Static review: final grep shows **zero** `ReligionRegistry.get/find/all` references in
any form (`.`, spaced, `::`); the sole `templates()` reader is the seeder; every
`Religions.*(level)` call site passes a `ServerLevel`; the level-threaded signatures'
callers are all updated (grep-confirmed); gods stay global (`godsFor`/`primaryGod`
unchanged).

### Smoke test (user-runnable)

1. **Everything still resolves (data-identical).** New world → rites still schedule
   per faith (`/religion rite …`), NPC piety/beliefs resolve, building faith resolves
   (`/religion temple`, `/religion shrine`), `/religion identity sunstead` reads as
   before, `/religion list` == `/religion world list` (four faiths).
2. **Divine layer unchanged.** `/religion favour grant sun_mother 60`, `/religion
   miracle cast …`, `/religion sacrilege the_loom 80`, `/religion theophany favour
   sun_mother` — all behave exactly as before; the favour CAP (`tierForGod` via
   `religionsVenerating`) gives identical tiers/caps.
3. **Player screen + calendar.** `/religion me` — per-god rows, calendar lists all
   faiths (cross-faith `upcomingAcross` now per-world), pledge/calling read correctly.
4. **Persistence.** Save + reload → all of the above identical; the per-world store
   round-trips (`/religion world list` still four).
5. **Coverage.** The build has no remaining static `ReligionRegistry.get/find/all`
   runtime callers — compiler-clean after the accessor restriction (only the seeder
   reads `templates()`).

## F1b sub-stage 2 — interreligious relations (2026-06-09)

The feature half of F1b. F1b-1 made religions per-world instances; 2 lets them
**relate** — a stance between two faiths that, for the first time, makes the world
react to *which faiths share gods*. Unlike F1b-1 this **adds behaviour** (not
behaviour-preserving): KINDRED faiths syncretize more readily and clash less.
**This completes F1b** (religions are per-world instances that relate to each other).

### Disposition (investigation)

1. **`FaithReconciliation`** — `applyCommunalBenefit` is the cross-faith drift point:
   a non-co-religionist attendee of a rite (when `scaledPiety > 0`) drifts
   `SYNCRETIC_DRIFT = 0.004` toward the OFFICIATING faith (acculturation). That single
   `adjustBelief(riteFaith, SYNCRETIC_DRIFT)` is what the stance modulates.
2. **`ReligionAuthorityEngine.applyReligiousTension`** — stamps a
   `TENSION_STABILITY_DIP = -3` expiring modifier per province when the kingdom's
   official religion ≠ the province's (culture-default) faith. That dip is what the
   stance between official and province faith modulates. One caller (`dailyTick`,
   which has the `ServerLevel`).
3. **Derivation inputs** — `GodRegistry.godsFor(religion)` (god ids per religion) +
   `Religions.all(level)` (the per-world set).
4. **Override storage** — `ReligionSavedData` already has the per-world store +
   `markDirty`; the override map slots in as a second codec field.

### What shipped

- **`RelationStance`** enum (new): `KINDRED` / `NEUTRAL` / `RIVAL` / `HERETICAL`.
- **`Relations`** facade (new) — the ONE home for the rule, mirroring `Religions`:
  - `relation(level, idA, idB)` = override if set, else `derive(...)`.
  - `derive(level, idA, idB)` = KINDRED when the two religions share ≥1 god (via
    `GodRegistry.godsFor`), else NEUTRAL. Symmetric; computed fresh (never stale).
  - `setRelation(level, idA, idB, stance)` = the override WRITE seam — **no caller
    this stage** (like `ReligionSavedData.put`).
- **`ReligionSavedData`** — gained a `relationOverrides` map (canonical sorted "a|b"
  pair key → stance name) + a 2nd codec field (`unboundedMap(STRING,STRING)`,
  `optionalFieldOf` empty default → round-trips, pre-2 saves load empty);
  `relationOverride(idA,idB)` (unknown-name-safe) + `setRelationOverride(...)`.
- **Consumer A — `FaithReconciliation`**: the syncretic drift is now
  `SYNCRETIC_DRIFT × driftMultiplier(stance)` where the stance is the attendee↔rite
  relation (level derived from the NPC entity). Multipliers — KINDRED 1.5, **NEUTRAL
  1.0 (unchanged)**, RIVAL 0.25, HERETICAL 0 (blocked). 4-arm switch.
- **Consumer B — `ReligionAuthorityEngine`**: the per-province mismatch dip is
  `round(TENSION_STABILITY_DIP × tensionMultiplier(stance))` for the official↔province
  stance (`applyReligiousTension` gained a `ServerLevel`, threaded from `dailyTick`).
  Multipliers — KINDRED 0.5 (dip −1), **NEUTRAL 1.0 (dip −3, unchanged)**, RIVAL 1.5
  (−4), HERETICAL 2.0 (−6). 4-arm switch.
- **Consumer C — `/religion relations`**: prints the stance matrix across the
  per-world religions (each distinct pair + KINDRED/NEUTRAL/override, colour-coded).

### Tie-In Audit

1. **Upstream feeders.** `GodRegistry.godsFor` + `Religions.all(level)` (derivation
   inputs, read-only); `ReligionSavedData` (override storage, gains one map field).
2. **Downstream callers.** `FaithReconciliation.applyCommunalBenefit` (drift) and
   `ReligionAuthorityEngine.applyReligiousTension` (tension) + the readout — each
   reads `Relations.relation(level, …)`. **NEUTRAL reproduces today's numbers
   exactly** (drift 0.004; dip −3), so only a shared-god (KINDRED) pairing or a future
   override visibly changes behaviour. The four single-god starters are currently
   disjoint → all NEUTRAL → no live behaviour change yet.
3. **Sibling systems.** Divine layer + piety untouched; the per-world store's codec
   gains the override map (empty default, round-trips). `RiteSavedData` unaffected.
4. **Exhaustive switches.** Two new `RelationStance` switches (`driftMultiplier`,
   `tensionMultiplier`) cover **all four arms**, incl. the not-yet-fired
   RIVAL/HERETICAL branches; the readout's colour ternary handles all four.

### Simplification Sweep

- **Seam, no callers (noted like `put`):** `Relations.setRelation` /
  `ReligionSavedData.setRelationOverride` — the dynamism/kingdom write seam. Zero
  callers this stage by design.
- **Derivation has ONE home:** `Relations.derive` — both consumers and the readout go
  through `Relations.relation`; the kindred/neutral rule is not duplicated.
- **Touched classes:** `RelationStance` (new), `Relations` (new), `ReligionSavedData`,
  `FaithReconciliation`, `ReligionAuthorityEngine`, `ReligionDebugCommand`.

### Deviations from prompt

- None of substance. The stance multipliers are concrete picks within the prompt's
  "bounded + proportional" guidance (NEUTRAL = identity, so today's numbers hold).
- `applyReligiousTension` gained a `ServerLevel` (one caller threaded it) — needed to
  resolve the per-world stance; no behaviour change at NEUTRAL.
- `FaithReconciliation` derives the level from the NPC entity (no signature change to
  its 6 callers), consistent with the F1b-1b entity-carries-level pattern.

### Out-of-scope but flagged

- **No auto-derivation of RIVAL/HERETICAL** — KINDRED/NEUTRAL only; the hostile
  stances are explicit-override, no writers yet (their consumer branches are wired).
- **Dynamism** — founding / schism / conversion that would call `setRelation` /
  `put`; the seams stand ready, caller-less.
- **Condemned/evil-gods modeling** (the eventual HERETICAL source) — later.
- **Player-screen surfacing** of relations — deferred; the debug readout is the
  required surface this stage.
- **Religion era-2 remaining:** dynamism, then the content layers (sacred space/time,
  covenants/oaths, saints/relics).

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava --offline` fails resolving `neoform-runtime:2.0.18` before javac; no javac
errors surfaced). [Container current at the F1b-1b tip; no reset needed.] Static
review: the override codec is a single `optionalFieldOf` map (empty default → pre-2
saves load clean; `fromCodec` is now a 2-arg BiFunction matching the 2 group fields);
both modulation switches are 4-arm exhaustive with NEUTRAL = 1.0 identity; the
derivation lives only in `Relations.derive`; `applyReligiousTension`'s sole caller
threads the level; pair keys are canonical (sorted, "|"-joined).

### Smoke test (user-runnable)

1. **Baseline matrix.** New world → `/religion relations` — confirm all six pairs of
   the four starters read NEUTRAL (single-god + disjoint), no "(override)" flags.
2. **KINDRED derivation.** Give two religions a shared god (edit a starter's `godIds`
   to add another's god, or add a test religion that venerates an existing god) →
   `/religion relations` shows that pair KINDRED (green), the rest NEUTRAL — proving
   the god-overlap derivation.
3. **Override persists.** Via a temporary `setRelation` hook (or reasoning), set an
   override on a pair → `/religion relations` shows the stance with "(override)";
   save + reload → the override survives (codec round-trip); the derived pairs still
   recompute fresh.
4. **Drift modulation.** An NPC of faith X attending a KINDRED faith Y's communal rite
   drifts toward Y more than the same NPC attending a NEUTRAL faith (KINDRED ×1.5 vs
   ×1.0); a NEUTRAL drift equals the pre-change 0.004.
5. **Tension modulation.** A kingdom whose official religion is KINDRED to a
   province's faith shows a lighter religious-tension stability dip (−1) than a NEUTRAL
   mismatch (−3, unchanged from before).
6. **No regression at NEUTRAL.** With no shared gods and no overrides (the shipped
   default), every drift/tension number equals pre-F1b-2.

## Sacred Space S1a — the SacredSpaceRule model + per-god authoring + the sacredness query (2026-06-10)

The first content layer on the gods-split foundation. S1a builds the VOCABULARY of
sacred space and the QUERY that reads it — **no effects** (favour/miracle/theophany
bonuses are S1b). After S1a you can stand anywhere and read how sacred the spot is to
each god via `/religion sacred`. Additive scaffolding; nothing existing changes.

### Disposition (investigation)

- **`God`** is the authoring home (hand-authored + global in `GodRegistry`); it gains
  the rule list.
- **`BuildingFaith`** supplies the built source: `resolveFaith(level, village, b)` →
  faith → (via `Religions.get` + `GodRegistry.primaryGod`) the god; `isReligiousBuilding`
  covers TEMPLE/CHAPEL/SHRINE/MONASTERY/ABBEY. Building position via
  `b.getShape().getOrigin()`; the village at a pos via `VillageSavedData.getVillageAt`.
- **Vanilla APIs:** `level.getBiome(pos).is(TagKey)`, `level.canSeeSky(pos)`,
  `level.isDay()`, `pos.getY()`, `level.structureManager().getStructureWithPieceAt(pos,
  ResourceKey<Structure>)`, `new PosUtil(pos).horizontalDistanceTo(BlockPos)`.

### Model + decisions

- **`SacredSpaceRule`** (new sealed interface, `Npc/Religion/Sacred/`) — each variant a
  record with `float potencyAt(level, pos)` (0 = not sacred) + a `label()`. A potency,
  not a boolean, so a god's sacred space is a LIST whose contributions **sum** and
  sacredness STACKS. The sealed type is the extension point. Variants built (only what
  the four starters need): **`BiomeRule`** (biome tags), **`AltitudeRule`** (Y band),
  **`CoordinateRule`** (concentric rings from world origin), **`SkyRule`** (dynamic:
  open sky / daytime), **`StructureRule`** (vanilla structures via the structure
  manager). **Skipped** (stated): `DimensionRule` / `ProximityRule` — no starter
  consumer; the sealed type makes adding them later one record + one `permits` entry
  (honours "new primitives only when a concrete consumer needs them").
- **`God.sacredSpace`** — added as a 9th record component (default empty), **outside
  the persisted codec** (the codec's `.apply` supplies `List.of()`). Stated choice:
  gods are hand-authored + global (never deserialized per-world), so a dispatch codec
  for the sealed rule (biome/structure holders, tags) would be pure plumbing for zero
  runtime gain. Decoded gods load empty; authored gods carry the real list.
- **`SacrednessTier`** (new enum) `NONE/MINOR/MAJOR` + `classify(potency)` (MAJOR_AT =
  1.5): one base rule (~1.0) → MINOR; two stacked (~2.0) → MAJOR; the lesser built
  bonus (0.5) → MINOR. So S1b reads a tier, not a hard number.
- **`SacredSpace.sacrednessAt/tierAt/explain`** (new) — the QUERY and single fold home:
  (1) natural = sum of the god's rule contributions; (2) built = a same-faith
  religious building within `BUILT_RADIUS` (48) of pos adds the lesser `BUILT_POTENCY`
  (0.5), resolved via `BuildingFaith` over the village containing pos; (3) a **clean
  commented seam** for the S3 theophany-imprint source. `explain` returns a per-source
  `Breakdown` the readout prints; `sacrednessAt` = `explain().total()` (no duplication).

### Per-god authoring (in `GodRegistry`)

- **Sun-Mother (SUN)** → `SkyRule(openSky + day, 1.0)` — sacred where her light reaches
  (dynamic).
- **Sea-Mother (SEA)** → `BiomeRule([IS_OCEAN, IS_BEACH], 1.0)` **+**
  `StructureRule([OCEAN_MONUMENT, OCEAN_RUIN_COLD, OCEAN_RUIN_WARM], 1.0)` — doubly
  sacred at the sites (biome + structure stack).
- **First Forge-Father (FORGE)** → `AltitudeRule(150…MAX, 1.0)` **+**
  `BiomeRule([IS_MOUNTAIN], 1.0)` — extra-sacred high in mountains (altitude + biome
  stack).
- **The Pattern / Loom (FATE)** → `CoordinateRule(spacing 256, band 8, 1.0)` **+**
  `StructureRule([STRONGHOLD], 1.0)` — extra-sacred at strongholds.

### Readout

`/religion sacred [god]` — at the player's position, per god (or one), prints the
summed potency, the tier (colour-coded), and each contributing source/rule. The ONLY
consumer of `SacredSpace` this stage.

### Tie-In Audit

1. **Upstream feeders.** `God` (gains `sacredSpace`), `BuildingFaith` + `VillageSavedData`
   (built source), vanilla world APIs (biome/sky/heightmap/structure manager) — all
   read-only inputs.
2. **Downstream callers.** **None besides the `/religion sacred` readout** — S1a wires
   no effects; the favour/miracle/theophany consumers are S1b.
3. **Sibling systems.** Divine layer untouched (no effect reads the query yet);
   `Religion.sacredLocations` still dead (not touched, per scope).
4. **Exhaustive switches.** New `SacrednessTier` switch in the readout covers all three
   arms; `God.displayName`'s `DeityDomain` switch unchanged. No existing enum changed.

### Simplification Sweep

- **One home for the rule** (`SacredSpaceRule` + its variants) and **one for the query**
  (`SacredSpace.explain`, which `sacrednessAt`/`tierAt`/the readout all call) — no
  biome/altitude/structure logic duplicated across gods or consumers.
- **New classes + callers:** `SacredSpaceRule` (5 variants), `SacrednessTier`,
  `SacredSpace` — all readout-only this stage. **S3 imprint seam** noted as a commented
  fold-source in `SacredSpace.explain` (no storage added).
- **Cost flagged for S1b:** `StructureRule` (structure-manager query) is the priciest
  rule; the built fold scans the containing village. S1b throttles/caches on hot paths.

### Deviations from prompt

- **`DimensionRule` skipped** (the prompt allowed either) — no starter consumer; the
  sealed type makes it a cheap later addition. `ProximityRule` likewise deferred (the
  Sea-Mother uses biome+structure, not block proximity).
- **Built source scope:** considers the village CONTAINING pos (via `getVillageAt`) —
  cheap and correct for "stand near a temple"; a later stage can widen to nearby
  villages if needed (flagged).
- **Fix-up rider (separate commit):** two line-split `ReligionRegistry.find` calls in
  `NpcProfileSnapshotBuilder` — F1b-1b stragglers the single-line grep missed and the
  compiler-coverage lock would have caught (the user's local build surfaced them; my
  sandbox can't run javac). Re-pointed to `Religions.find(level, …)`. Lesson logged:
  back the grep audit with a multi-line sweep when the compiler can't run here.

### Out-of-scope but flagged

- **S1b** — wire the favour (+ position threading) / miracle / theophany effects to
  `SacredSpace.sacrednessAt`/`tierAt`; throttle/cache the structure query on the
  per-player theophany tick.
- **S2** — sacred time / holy days.
- **S3** — decaying theophany **imprints** (a new BlockPos-keyed SavedData) as the
  third fold-source (the commented seam in `explain`).
- **Future** — holy-city / sacred-kingdom contributor, worldgen shrine-spawn.
- `Religion.sacredLocations` stays dead (sacred space is per-god now).

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava --offline` fails resolving `neoform-runtime:2.0.18` before javac; no javac
errors surfaced). [Container had drifted to the R9c base on resume; fetched + `git
reset --hard` to the F1b-2 remote tip restored all pushed work — no loss.] Static
review: `God` is a 9-component record with an 8-field codec (`.apply` supplies an empty
`sacredSpace`); the four authored gods pass valid `BiomeTags`/`BuiltinStructures`
constants; the sealed `SacredSpaceRule` permits exactly its five nested records;
`SacredSpace.explain` is the single fold; the readout is the only query consumer; all
new vanilla API calls (`getBiome().is`, `canSeeSky`, `isDay`, `getStructureWithPieceAt`)
are standard 1.21.

### Smoke test (user-runnable)

1. **Sea biome + structure stack.** Stand in open ocean → `/religion sacred sea_mother`
   reads MINOR (~1.0, "biome"); swim onto an ocean monument → ~2.0 MAJOR ("biome" +
   "structure").
2. **Sun dynamic sky.** Under open sky at midday → `/religion sacred sun_mother` is
   sacred ("sky"); go underground or wait for night → NONE (re-queried live).
3. **Forge altitude (+ mountain).** On a tall peak → `/religion sacred forge_father`
   sacred ("altitude"), doubled if it's a mountain biome ("altitude" + "biome").
4. **Loom rings + stronghold.** Near an origin ring or at a stronghold →
   `/religion sacred the_pattern` sacred ("rings" and/or "structure"); extra at a
   stronghold on a ring.
5. **Built source.** Stand within ~48 blocks of a temple/shrine of a faith → that
   faith's god gains a lesser "built" +0.5; a god with no rule reads its building's
   built bonus only (else NONE away from its buildings).
6. **No effect change.** Confirm favour/miracle/theophany behaviour is identical to
   before (nothing reads the query but the readout).

## Sacred Space S1b — wire the effects (favour / miracle / theophany) (2026-06-10)

S1a built the `SacredSpace.sacrednessAt` query + the per-god rules (no effects). S1b
makes sacred ground **do something**: a favour grant made in sacred space is
amplified, miracles come easier there, and a theophany is likelier at a god's sacred
place. Intentional **behaviour change**, bounded by `SacrednessTier` (tunable);
NONE / null-position reproduces today's exact numbers. **Completes the sacred-SPACE
effects.**

### Disposition (investigation)

- **Query (S1a):** `SacredSpace.sacrednessAt/tierAt`, `SacrednessTier {NONE,MINOR,MAJOR}`,
  per-god resolution. S1b adds the shared `SacrednessTier.amplifier()` (tier→multiplier)
  + `SacredSpace.amplifierAt(level, godId, pos)` (null-safe).
- **Favour path:** the multiplier applies in `DivineFavour.awardConcept` after the
  alignment weight. **No position was in scope** — S1b threads a nullable `BlockPos`
  via OVERLOADS (existing signatures delegate with `null`). The favour-grant callers
  that route through `awardConcept` are exactly four (RiteExecutor grants no favour —
  the grants live in the player-act paths): `MakeOfferingVerb` (OFFERING),
  `AttendRiteVerb` (ATTEND_RITE), `CommissionRiteVerb` (COMMISSION_RITE) → the
  player's position; `Tithing` (TITHE) → the temple building's origin (the tithe's
  venue; the player isn't physically present for the recurring auto-tithe). The V3
  calling bonus (`addCappedForReligion`) and V4 sacrilege (`offend`) do NOT pass
  through `awardConcept` → no site, unchanged (a null-location grant is identity).
- **Miracle gate:** `MiracleInvoker.status`/`cast` — `player.blockPosition()` in scope.
- **Theophany:** `DivineTheophany.tick` (per-player, every `CHECK_INTERVAL=200`; per-god
  favour extreme + cooldown). Player pos in scope; the S1a `StructureRule` cost flag
  means the sacredness query must be guarded behind the cheap pre-checks.

### What shipped

- **Shared sacred-factor helper (one home):** `SacrednessTier.amplifier()` → NONE 1.0,
  MINOR 1.25, MAJOR 1.5; `SacredSpace.amplifierAt(level, godId, pos)` returns 1.0 when
  `pos == null`. The favour AND miracle paths both call `amplifierAt` — the
  tier→multiplier mapping is never duplicated.
- **Favour (DivineFavour):** added `BlockPos`-carrying OVERLOADS of `award` /
  `awardVirtue` / `awardForReligion`; the legacy signatures delegate with `null`.
  `awardConcept` now multiplies the grant by `amplifierAt(level, godId, pos)` after the
  alignment weight (composes with the 1.5× virtue bonus — two distinct bonuses) and
  still clamps to the piety cap (sacred amplifies, never bypasses the cap). Four
  callers updated to pass position (above).
- **Miracle (MiracleInvoker):** in `status` AND `cast`, the access threshold
  (`minFavour`) is checked against `favour × amplifierAt(...)` so a near-threshold
  miracle becomes castable on sacred ground; the **tier gate and the real `cost` spend
  are unchanged** (sacred eases, never bypasses). `cast` appends a flavour note when
  the ground is sacred.
- **Theophany (DivineTheophany):** sacred ground eases the favour-extreme thresholds —
  the favour-peak drops by `sacredEase` (MINOR 8 / MAJOR 15) and the wrath-depth rises
  likewise. **Perf guard:** `sacrednessAt` runs ONLY after the cheap pre-checks pass
  (within `MAX_SACRED_EASE=15` of the extreme, PIOUS for the favour pole, off cooldown)
  — never unconditionally per tick per player. NONE/non-sacred keeps the exact ±90
  thresholds.

### Tie-In Audit

1. **Upstream feeders.** `SacredSpace.amplifierAt`/`tierAt` (S1a) + the threaded
   `BlockPos`. Read-only.
2. **Downstream callers.** Favour grant path (+ its 4 position-aware callers),
   `MiracleInvoker.status`/`cast`, `DivineTheophany.tick`. **NONE / null = today's exact
   numbers** (amplifier 1.0; theophany thresholds ±90). Verified the 4 favour callers
   explicitly (a missed one is a silent no-bonus, not a crash).
3. **Sibling systems.** Composes cleanly: the sacred multiplier rides on top of the
   3a alignment bonus + the piety cap; the miracle sacred ease rides on top of the 3b
   tier gate + cooldown (cost/spend untouched); the theophany ease rides on the
   existing cooldown/milestone (no double-fire — favour pole `continue`s; the poles are
   mutually exclusive at the eased thresholds).
4. **Exhaustive switches.** New `switch (SacrednessTier)` in `SacrednessTier.amplifier`
   and `DivineTheophany.sacredEase` cover all three arms. No existing enum changed.

### Simplification Sweep

- **One sacred-factor helper** (`SacrednessTier.amplifier()` + `SacredSpace.amplifierAt`)
  shared by favour + miracle — not duplicated. Theophany uses an additive points ease
  (a different effect shape, not a multiplier), so it's a separate small mapping, not a
  duplicate.
- **No dead old-path:** the favour overloads delegate (legacy 5-arg → 6-arg with
  `null`); the private `awardConcept` is the single grant body (no parallel path left).
- **Touched classes:** `SacrednessTier`, `SacredSpace`, `DivineFavour`, `MiracleInvoker`,
  `DivineTheophany`, `MakeOfferingVerb`, `AttendRiteVerb`, `CommissionRiteVerb`,
  `Tithing`.

### Deviations from prompt

- **Miracle easing applies to `minFavour` only**, not the spent `cost` (the prompt said
  "multiply the effective favour read by the gate"): boosting the cost check too would
  let the gate pass while the real spend fails. Easing the ACCESS threshold and paying
  the real cost from real favour is the consistent reading of "eases, never bypasses".
- **Tithe site = the temple origin** (not player pos): the recurring auto-tithe fires
  with the player anywhere, so the temple it flows to is the meaningful sacred site.
- Tier-eased theophany uses additive favour-point shifts (MINOR 8 / MAJOR 15) rather
  than a multiplier — cleaner for a threshold than scaling it.

### Out-of-scope but flagged

- **S2** — sacred TIME / holy-day bonuses (piety-gated; the "highly pious only" gate
  belongs there, not to sacred space).
- **S3** — decaying theophany **imprints** (a new BlockPos-keyed SavedData) folded as
  the third `sacrednessAt` source (the commented seam in `SacredSpace.explain`).
- **Future** — holy-city / sacred-kingdom contributor, worldgen shrine-spawn.
- `addCappedForReligion` (calling bonus) + `offend` (sacrilege) stay non-positional by
  design — no sacred amplification on the calling bonus or on displeasure.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava --offline` fails resolving `neoform-runtime:2.0.18` before javac; no javac
errors surfaced). [Container current at the S1a tip; no reset needed.] Static review +
**multi-line/qualifier-split grep** (the F1b two-line-straggler lesson): all four
`DivineFavour.awardForReligion` call sites now pass the 6th `BlockPos` arg; no external
`award`/`awardVirtue`/`awardConcept` caller exists on a stale signature; the favour
overloads delegate (legacy callers compile unchanged); `MiracleInvoker` imports
`SacredSpace`; `DivineTheophany` references it by FQN; the amplifier switch and the
theophany ease switch are 3-arm exhaustive; NONE/null reproduces the prior numbers.

### Smoke test (user-runnable)

1. **Favour scales with the readout's tier.** Offer to the Sea-Mother in open ocean
   (`/religion sacred sea_mother` MINOR) vs on an ocean monument (MAJOR) — the favour
   gain is ~1.25× vs ~1.5× the flat-ground base; on non-sacred ground it equals today's
   base (unchanged), and still clamps at the piety cap.
2. **Miracle eases on sacred ground.** On a mountaintop (Forge-Father sacred), a
   near-`minFavour` miracle that read LOCKED on flat ground becomes AVAILABLE
   (`/religion miracle list` reflects it) and casts; the tier gate and the favour spent
   are unchanged; off sacred ground it's locked as before.
3. **Theophany likelier in sacred space.** Drive Sun-Mother favour into the 75–90 band
   under open sky at midday (her sacred space) and confirm a glory theophany can fire
   there that would NOT underground/at night (where the threshold stays 90).
4. **Perf guard.** Confirm no per-tick structure-lookup spam / FPS dip — the theophany
   tick only queries sacredness for a near-extreme, off-cooldown player.
5. **Null-location unchanged.** A sacrilege `offend` (no site) and the V3 calling bonus
   behave exactly as before (no sacred factor).

## Sacred Time S2 — holy-day bonuses (2026-06-10)

The temporal mirror of sacred space. On a faith's **holy day**, its devout gain
heightened favour, eased miracles, and a likelier theophany; a rite at a sacred place
ON a holy day is the peak of contact (space × time compound). The deliberate contrast
with sacred SPACE — which any believer feels — is that sacred TIME rewards the
**highly pious** (no bonus below DEVOUT). Bounded, piety-tier-keyed; a non-holy-day or
sub-DEVOUT player sees today's exact numbers. **Completes sacred TIME.**

### Disposition (investigation)

- **Holy-day query:** `ReligiousCalendar.isHolyDay(dayOfYear)` over the per-religion
  holy-day map; `CalendarView.dayOfYear(gameTime)` on the `% 365` axis. Holy days live
  on the RELIGION; favour/miracle/theophany are per-GOD → resolution: today is a holy
  day for god G (for this player) iff a religion the player BELIEVES IN that VENERATES
  G has a holy day today (`GodRegistry.religionsVenerating(level, godId)` ∩
  `piety.beliefs()` ∩ `calendar().isHolyDay`).
- **Piety gate:** `DivineFavour.tierForGod(level, playerId, godId)` → `PietyTier`
  {UNAFFILIATED, FAITHFUL, DEVOUT, PIOUS}. Gate at DEVOUT.
- **Hook points (S1b):** the space amplifier lives in `DivineFavour.awardConcept`
  (per-god, has level/playerId/godId/now), `MiracleInvoker.status`/`cast`, and
  `DivineTheophany.tick`. The time factor composes (multiplies) with it at each.

### What shipped

- **`SacredTime`** (new, `Npc/Religion/Sacred/`) — the single home, mirroring
  `SacredSpace`'s amplifier shape:
  - `isHolyDay(level, pid, godId, now)` — the pure calendar × belief × veneration
    resolution (no tier gate), public so the theophany ease + the readout share it.
  - `holyDayFactor(level, pid, godId, now)` → 1.0 off a holy day OR below DEVOUT;
    **DEVOUT 1.5, PIOUS 2.0** (`factorForTier`, a 4-arm `PietyTier` switch).
- **Favour** — `awardConcept` now multiplies the grant by
  `SacredTime.holyDayFactor` alongside the S1b space amplifier:
  `base × align × space × time`, still clamped to the piety cap (amplifies within
  standing, never bypasses). All award paths inherit it (one site).
- **Miracle** — `status` + `cast` fold the holy-day factor into the effective favour
  the `minFavour` gate reads, compounding with the space amplifier; the tier gate and
  the real `cost` spend are unchanged. `cast`'s eased-flavour line distinguishes a
  holy-day ease from a sacred-ground one.
- **Theophany** — a holy day eases the favour-extreme threshold further (stacking with
  the S1b sacred-space ease), derived from `holyDayFactor` (PIOUS 15 / DEVOUT 7.5
  points). **Perf discipline kept + tightened:** the holy ease is a CHEAP calendar
  lookup evaluated first; the structure-backed `sacredEase` runs only behind the cheap
  pre-checks AND only in a bounded 15-wide band where sacred space could still close
  the gap (so a non-holy day keeps the S1b 75–90 structure-query band; a holy day adds
  a 60–75 band only). NONE/off-holy keeps the exact ±90 thresholds.
- **Surfacing** — `/religion favour view` flags `☀ HOLY DAY` on each god whose holy day
  is today (a believed venerating faith); a light, contained text addition (no GUI/
  packet change).

### Tie-In Audit

1. **Upstream feeders.** `ReligiousCalendar.isHolyDay` + `CalendarView.dayOfYear`
   (holy day), `GodRegistry.religionsVenerating` (god→religions), `tierForGod` (gate),
   `PietyComponent.beliefs` — all read-only.
2. **Downstream callers.** Favour (`awardConcept`), `MiracleInvoker.status`/`cast`,
   `DivineTheophany.tick`, the readout. **Non-holy-day / sub-DEVOUT = `holyDayFactor`
   1.0 = today's exact numbers** (favour grant, miracle gate, ±90 theophany).
3. **Sibling systems.** Composes with S1b sacred space MULTIPLICATIVELY (space × time)
   — no double-count: space reads position, time reads the calendar, distinct inputs.
   The favour cap still bounds the compound; the miracle tier gate + cost and the
   theophany cooldown/milestone are untouched (no double-fire).
4. **Exhaustive switches.** `SacredTime.factorForTier` covers all four `PietyTier`
   arms; the theophany derives its ease from the factor (no separate tier switch). No
   existing enum changed.

### Simplification Sweep

- **One home:** `SacredTime.holyDayFactor` (the bonus) over `SacredTime.isHolyDay` (the
  god→religion resolution); favour, miracle, AND theophany all call `holyDayFactor`
  (theophany derives its additive ease from it), and the readout calls `isHolyDay`. No
  per-consumer reimplementation of the calendar/belief/veneration check.
- **No dead path** — the time factor rides the existing S1b sites (no parallel grant/
  gate path).
- **Touched classes:** `SacredTime` (new), `DivineFavour`, `MiracleInvoker`,
  `DivineTheophany`, `ReligionDebugCommand`.

### Deviations from prompt

- **Favour time factor applied in `awardConcept`** (the per-god grant body), not at
  `awardForReligion`: `awardConcept` already has level/playerId/godId/now and is the
  single site where the space amplifier lives, so both bonuses compose in one place
  and every award path inherits it uniformly. The general `holyDayFactor(godId)`
  resolution (believed venerating religion has a holy day) makes the specific
  `religionId` unnecessary.
- **Theophany ease derived from `holyDayFactor`** ((factor−1)×scale) rather than a
  second tier switch — keeps the holy-day bonus's one home and mirrors how S1b's
  `sacredEase` is a small local mapping.
- **Surfacing via `/religion favour view`** (a per-god flag) rather than the GUI
  calendar — lightest contained change, no packet/screen churn (the prompt allowed
  "a line in the /religion readout").

### Out-of-scope but flagged

- **S3** — decaying theophany **imprints** (a new BlockPos-keyed SavedData) as the
  third `sacrednessAt` fold-source (`SacredSpace.explain`'s commented seam).
- **Future** — NPC holy-day observance / festivals (this is the player-side
  favour/miracle/theophany bonus only), holy-city contributor, worldgen shrine-spawn.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava --offline` fails resolving `neoform-runtime:2.0.18` before javac; no javac
errors surfaced). [Container current at the S1b tip; no reset needed.] Static review +
**multi-line/qualifier-split grep** (the F1b lesson): every `SacredTime` reference
resolves (4 `holyDayFactor` call sites + 1 `isHolyDay` readout + the theophany FQN
use); `factorForTier` is a 4-arm `PietyTier` switch; non-holy/sub-DEVOUT reproduces the
prior numbers; the theophany structure-query band is unchanged on non-holy days.

### Smoke test (user-runnable)

1. **Favour scales with tier on a holy day.** On a Sunstead holy day (`/time set` to
   its day-of-year), a PIOUS Sunstead player's offering grants ~2× the base; a DEVOUT
   one ~1.5×; a FAITHFUL one sees NO holy-day bonus; a non-holy day grants today's base
   (and all still clamp at the piety cap).
2. **Miracle eases for the devout on a holy day.** A near-`minFavour` Sun-Mother
   miracle that read locked becomes AVAILABLE/ castable for a DEVOUT+ player on the
   holy day (`/religion miracle list` reflects it); the tier gate + cost are unchanged;
   a sub-DEVOUT player is unaffected.
3. **Space × time compound.** Stand in the Sun-Mother's sacred space (open sky, day) ON
   her holy day as a PIOUS player — favour compounds (space × time, e.g. ~1.25–1.5 ×
   2.0) and a theophany is at its likeliest (both eases stack).
4. **Surfacing.** `/religion favour view` shows `☀ HOLY DAY` against the right god on
   its holy day.
5. **No regression / perf.** A sub-DEVOUT player, a non-believer, and any non-holy day
   behave exactly as before; confirm no per-tick structure-lookup spam (the holy check
   is a cheap calendar lookup; the sacred query stays behind the staged guard).

## Sacred Space S3 — decaying theophany imprints (the dynamic third source) (2026-06-10)

The capstone of the sacred-space layer and the first **location-tied, persisted**
divine state in the mod. A glory theophany **sanctifies the ground where it occurs** —
a sacred **imprint** that **decays over time**, its decay **slowed by a nearby player
SHRINE** of the god's faith, its strength **raised by nearby rites**. The loop the
design built toward: a divine event makes a place holy for a while; the player keeps it
holy with a shrine + rites; neglect lets it fade. It folds into
`SacredSpace.sacrednessAt` as the **third source**, so every S1b/S2 effect inherits it
automatically. **Completes the Sacred Space + Sacred Time layer.**

### Disposition (investigation)

- **SavedData idiom:** `GraveyardSavedData` / `RiteSavedData` — own `SavedDataType` +
  storage name, a codec over the collection (list rebuilt to a map in `fromCodec`),
  `get(level)` via `computeIfAbsent`, `markDirty`. The new store is imprint-keyed.
- **Fold seam:** the commented S3 spot in `SacredSpace.explain` — the imprint source
  ADDS the summed current strength of the god's nearby imprints.
- **Write point:** `DivineTheophany.fireFavour` (god + player + level + now → the
  player's BlockPos is the site). **Glory only** — `fireWrath` writes nothing
  (profanity is a future option).
- **Refresh point:** `RiteExecutor.runOne` after a SUCCESSFUL rite (`gatheringLocation`
  + `religionId` → `godsFor`). Rites RAISE existing imprints; they do not seed new ones.
- **Shrine detection:** a `SHRINE` of the god's faith near the imprint — the same
  god-aware building scan the built source already does.
- **Lazy decay:** `DivineFavour`'s relax-on-read precedent — store `(anchorStrength,
  anchorTick)`, compute current on query; no per-tick scan.

### Model + decisions

- **`SacredSpaceSavedData`** (new, `Npc/Religion/Sacred/`, storage
  `life_in_the_village_sacred_imprints`) — `Map<String, Imprint>` keyed by
  `godId@pos.asLong()`. **`Imprint`** = `(godId, pos, bornTick, anchorStrength,
  anchorTick)` with its own codec (`BlockPos.CODEC` etc.); the store's codec is a
  `listOf().optionalFieldOf` rebuilt to the map (the Graveyard idiom).
- **Decay (lazy, shrine-slowed):** linear to 0 over `LIFETIME_TICKS` (6 in-game days)
  from `anchorStrength` at `anchorTick`; a same-faith `SHRINE` within `SHRINE_SLOW_RADIUS`
  multiplies the lifetime ×`SHRINE_LIFETIME_MULT` (3) — checked at read time. Computed in
  `currentStrength`; **pruned lazily** when it reaches 0 on access (no global scan).
- **Create:** `addImprint(godId, pos, now, INITIAL_STRENGTH=2.0)` — MAJOR-tier so a
  fresh theophany site reads strongly sacred.
- **Refresh:** `refreshNear(godId, pos, now)` raises imprints of that god within
  `REFRESH_RADIUS` toward `STRENGTH_CAP=3.0` by `RITE_BOOST=0.5` and resets the anchor.
- **Query:** `imprintPotency(level, godId, pos, now)` = summed current strength of the
  god's imprints within `IMPRINT_RADIUS` (24). Short-circuits O(1) when the map is empty
  (the common case — theophanies are rare).
- **Shared scan (de-dup):** added `BuildingFaith.hasBuildingOfGodNear(level, godId, pos,
  radius, typeFilter)` — the ONE god-aware building-near-position scan; the **built**
  source (`isReligiousBuilding` filter) and the imprint **shrine-slow** (`SHRINE` filter)
  both use it. `SacredSpace.builtPotency` was refactored onto it (removing its duplicate
  scan).

### What shipped

- New: `SacredSpaceSavedData` (+ nested `Imprint`).
- `SacredSpace.explain` — the S3 seam is **filled** (imprint contribution added to the
  natural + built sum); `builtPotency` delegates to the shared scan.
- `BuildingFaith.hasBuildingOfGodNear` — the shared scan.
- `DivineTheophany.fireFavour` — writes the imprint (glory only).
- `RiteExecutor.runOne` — refreshes nearby same-god imprints on a successful, located
  rite.
- `/religion sacred` — auto-shows the `imprint` contribution (via `explain`) and flags
  `(shrine-tended)` when a shrine is slowing it.

### Tie-In Audit

1. **Upstream feeders.** `DivineTheophany.fireFavour` (creates), `RiteExecutor`
   (refreshes), `BuildingFaith`/SHRINE (slows decay), `GodRegistry`/`Religions` (god
   resolution) — all confirmed.
2. **Downstream callers.** `SacredSpace.sacrednessAt`/`explain` (the fold) → every S1b
   favour/miracle/theophany and S2 holy-day effect inherits imprints with **no extra
   wiring**. No double-count: natural (rules), built (buildings), imprint (theophany
   sites) are distinct sources summed once. The `/religion sacred` readout.
3. **Sibling systems.** `GraveyardSavedData`/`RiteSavedData` — sibling SavedData, the
   new store has a distinct storage name (`…_sacred_imprints`) so they coexist in
   separate `.dat` files. The per-player theophany ledger (cooldown milestone, in
   `RiteSavedData`) is separate player-state; the imprint is WORLD-state — not conflated.
4. **Exhaustive switches.** None added (reused `SacrednessTier`). Confirmed.

### Simplification Sweep

- **One store** (`SacredSpaceSavedData`), **one decay helper** (`currentStrength`,
  lazy/read-time), **one fold point** (`SacredSpace.explain`). The **S1a seam is now
  filled**, not left dangling.
- **Shrine/building detection de-duplicated:** the new `BuildingFaith.hasBuildingOfGodNear`
  is the single god-aware scan; `SacredSpace.builtPotency` (built source) and the imprint
  shrine-slow both call it — and `SacredSpace` shed its own copy of the scan (and the now-
  unused `VillageSavedData`/`Village`/`Building`/`PosUtil`/`Religion`/`Religions` imports).
- **Touched classes:** `SacredSpaceSavedData` (new), `SacredSpace`, `BuildingFaith`,
  `DivineTheophany`, `RiteExecutor`, `ReligionDebugCommand`.

### Deviations from prompt

- **Lazy prune only** (on access) — no separate periodic sweep added; theophanies are
  rare so the map stays tiny and read-time pruning suffices (a periodic sweep can be
  added if a world ever accrues many).
- **Linear decay** (not exponential) — matches the prompt's "decay toward 0 over a
  lifetime L" most directly and makes the shrine's lifetime-multiplier intuitive.
- **`refreshNear` takes `level`** (to evaluate the shrine-slow when computing the
  current strength it raises from) — a small signature detail; no caller churn (one
  caller, `RiteExecutor`).
- Captured `religionId` into a `final faithId` in `RiteExecutor.runOne` for the refresh
  lambda (it is reassigned earlier, so not effectively final) — a local fix, no
  behaviour change.

### Out-of-scope but flagged

- **Wrath / profanity imprints** (negative sacredness) — glory only this stage;
  `fireWrath` writes nothing. The store + fold could carry a signed strength later.
- **NPC-side** imprint creation / NPC holy-day observance + festivals.
- **Holy-city / sacred-kingdom** contributor; **worldgen** shrine-spawn.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava --offline` fails resolving `neoform-runtime:2.0.18` before javac; no javac
errors surfaced). [Container current at the S2 tip; no reset needed.] Static review +
**multi-line/qualifier-split grep**: all `SacredSpaceSavedData` / `hasBuildingOfGodNear`
/ `addImprint` / `refreshNear` / `imprintPotency` / `isShrineTended` references resolve;
the `Imprint` codec is a 5-field record (BlockPos.CODEC + 2 long + 1 float + 1 string),
under the cap; `fromCodec` is a 1-arg Function matching the single group field; the
fold short-circuits O(1) when no imprints exist; the empty-store default means pre-S3
saves load clean. **Caught locally-only bug class:** `religionId` non-effectively-final
capture (fixed) — exactly what the qualifier/closure grep + a careful read surface when
javac can't run here.

### Smoke test (user-runnable)

1. **Theophany sanctifies ground.** On ordinary ground, force a Sun-Mother glory
   theophany (`/religion theophany favour sun_mother`) → `/religion sacred sun_mother`
   there now reads MAJOR with an `imprint +2.0`, and an offering there gets the sacred
   favour multiplier.
2. **Decay.** Advance time (`/time add`) and re-check — the imprint strength drops, then
   `/religion sacred` reads NONE once it prunes (~6 days bare).
3. **Shrine slows decay.** Repeat, build a `SHRINE` of the faith within ~32 blocks →
   `/religion sacred` shows `imprint … (shrine-tended)` and the decay is slower (×3
   lifetime).
4. **Rites refresh.** Hold a rite of the faith near the imprint → its strength rises
   (toward 3.0) and its clock resets.
5. **Glory only / rites don't seed.** A wrath theophany leaves no sacred imprint; a
   routine rite on un-imprinted ground creates none.
6. **Persistence + perf.** Save+reload → imprints persist; confirm no per-tick spam
   (decay is read-time; the fold is O(1) when no imprints exist).

## Saints & Relics SR1 — living saints (holy people) (2026-06-10)

The first holy-layer stage and the richer saint tier: a **living holy person** — a
status a devout player or NPC attains in life through deep devotion and a god's
recognition. The player's religious summit; an NPC's path to a revered focal point; the
seed of the canonized roster (SR2 auto-canonizes a living saint who dies). SR1 builds
the **status**: who becomes one, what they get (recognition + personal divine ease), how
it's lost, where it's stored and shown. No canonization/death (SR2), intercession (SR3),
or relics (SR4).

### Disposition (investigation)

- **Anointing hook:** `DivineTheophany.fireFavour` already fires only at peak favour for
  a PIOUS player (the favour-pole tick gates `tierForGod == PIOUS`), so the glory
  manifesting IS the recognition — no extra numeric threshold needed.
- **Gates:** `PietyComponent.primaryTier()` / `DivineFavour.tierForGod` (PIOUS),
  `DivineFavour.current` (favour), `meetsMonthlyAttendance()` (NPC sustained signal).
- **Amplifier-composition pattern:** the favour grant (`DivineFavour.awardConcept`) and
  miracle gate (`MiracleInvoker`) already fold `SacredSpace.amplifierAt` ×
  `SacredTime.holyDayFactor` multiplicatively — `SaintFactor` slots in as one more
  factor with identical shape.
- **SavedData idiom:** `SacredSpaceSavedData`/`RiteSavedData` — new `SaintsSavedData`.
- **Daily NPC hook:** `RiteScheduler.dailyTick` already does a per-village pass; the NPC
  saint sweep rides it.

### Model + what shipped

- **`SaintsSavedData`** (new, `Npc/Religion/Saints/`, storage `life_in_the_village_saints`)
  — `Map<UUID beingId, LivingSaint(beingId, godId, becameTick, isPlayer)>` (a being is
  the Holy of at most one god). Reads `isLivingSaint`/`livingSaintGod`/`livingSaintsOf`/
  `all`; writes `add` (idempotent) / `remove`. List-codec rebuilt to the map; empty
  default. Shaped to accept SR2's canonized roster (a second list) later.
- **`SaintFactor`** (new) — the personal-ease multiplier mirroring the sacred amplifiers:
  `amplifierFor(level, beingId, godId)` = `SAINT_AMPLIFIER` (1.25) for a living saint of
  that god, else 1.0. The one home for the saint multiplier.
- **`Saints`** (new) — the one home for the transitions: `anointPlayer` (idempotent),
  `reviewPlayerLapse` (revoke on favour ≤ CURSE band or tier < PIOUS), `dailyNpcSweep`
  (designate sustained-PIOUS NPCs of their faith's primary god; revoke on piety lapse).
- **Anointing (player):** `DivineTheophany.fireFavour` → `Saints.anointPlayer`.
- **Personal ease:** `awardConcept` and `MiracleInvoker.status`/`cast` now multiply by
  `SaintFactor.amplifierFor` beside the space × time amplifiers (composes; still clamped
  to the favour cap — eases, never bypasses the cap or tier gate).
- **Loss:** player on the per-player theophany cadence (`DivineTheophany.tick`); NPC on
  the daily `RiteScheduler` sweep. Both routed through `Saints` (one logic home).
- **Surfacing:** `/religion saints` (living saints by god) + a `★ HOLY OF <god>` flag on
  `/religion favour view`.

### Tie-In Audit

1. **Upstream feeders.** `DivineTheophany.fireFavour` (player anoint), `tierForGod` /
   `DivineFavour.current` / `PietyComponent` (gates), `GodRegistry.primaryGod` (NPC patron
   god). Confirmed.
2. **Downstream callers.** Favour grant + miracle gate now also fold `SaintFactor`; the
   two command readouts. **Non-saints read 1.0 → today's exact numbers.**
3. **Sibling systems.** Composes with sacred space (S1b) × time (S2) MULTIPLICATIVELY —
   distinct sources, no double-count. The anointing rides the existing `fireFavour`
   (after its effect + cooldown stamp) — it does not disturb the theophany's
   cooldown/milestone or effect.
4. **Exhaustive switches.** None added — `SaintFactor` is a boolean→multiplier (no enum);
   `Saints` switches nothing. No existing enum changed.

### Simplification Sweep

- **One store** (`SaintsSavedData`), **one multiplier helper** (`SaintFactor`, folded
  beside the existing amplifiers — not a parallel favour path), **one transitions home**
  (`Saints`: anoint / player-lapse / NPC-sweep all live here; the effect sites only
  READ via `SaintFactor`/`isLivingSaint`).
- **Touched classes:** `SaintsSavedData` (new), `SaintFactor` (new), `Saints` (new),
  `DivineTheophany`, `DivineFavour`, `MiracleInvoker`, `RiteScheduler`,
  `ReligionDebugCommand`.

### Deviations from prompt

- **Optional favour-ceiling bump skipped** — kept to the ease amplifier + recognition
  (the prompt's stated core); the saint's grant is eased but still clamped to the
  piety-tier cap. A bounded ceiling bump can be added later if desired.
- **Surfacing via command readouts** (`/religion saints` + favour-view flag) rather than
  the GUI player-religion screen — avoids packet/screen churn ("keep it light"); the
  screen "Holy of <God>" line is a light future add (flagged).
- **NPC reverence is roster-only** — no greeting/standing AI flag (the prompt allowed
  "a light flag at most / optional"); the `/religion saints` readout surfaces NPC saints.
  Deeper NPC reverence deferred.
- **Player loss reviewed on the theophany cadence** (every 200 ticks per player) and NPC
  on the daily sweep — both lazy/periodic, no per-tick scan, per the discipline.

### Out-of-scope but flagged

- **SR2** — canonized / deceased saints: a living saint who dies → auto-canonize (martyr
  fast-track + Venerable→deliberate canonization for others); a `Saint` record +
  chronicle + grave epitaph + an S3 imprint at the grave + a saint's-day calendar add via
  `ReligionSavedData.put`. (`SaintsSavedData` is shaped to hold the dead roster.)
- **SR3** — intercession (pray-to-saint). **SR4** — relics.
- **Deferred** — NPC favour / NPC theophany to unify the two saint paths; bless-others /
  community aura; the GUI-screen "Holy of" line; deeper NPC reverence.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava --offline` fails resolving `neoform-runtime:2.0.18` before javac; no javac
errors surfaced). [Container had drifted to the R9c base on resume; fetched + `git reset
--hard` to the S3 remote tip restored all pushed work — no loss.] Static review +
**multi-line/qualifier-split grep**: every `SaintsSavedData` / `SaintFactor` /
`Saints.Saints` reference resolves; `LivingSaint` is a 4-field record codec (UUID-string
+ string + long + bool) under the cap; the multiplier reads 1.0 for non-saints
(behaviour-preserving); `DivineFavour.CURSE_AT` / `tierForGod` are public for the
cross-package lapse check; the NPC sweep + player review are both periodic (no per-tick
scan).

### Smoke test (user-runnable)

1. **Player anointing.** As a PIOUS Sunstead player, drive favour to peak and force a
   Sun-Mother glory theophany (`/religion theophany favour sun_mother`) → `/religion
   saints` lists you as Holy of the Sun-Mother and `/religion favour view` shows the
   `★ HOLY OF` flag.
2. **Personal ease.** An offering / near-threshold miracle with the Sun-Mother is then
   eased (×1.25 on top of any sacred space/time) vs a non-saint; the favour still clamps
   at the cap.
3. **Loss.** Drive favour into displeasure (`/religion sacrilege sunstead 200`) or drop
   below PIOUS → within a theophany check the status is revoked (`/religion saints` drops
   you).
4. **NPC designation + loss.** A sustained-PIOUS NPC of a faith is designated (within a
   day's sweep) and shows in `/religion saints` as an `npc`; if its piety lapses below
   PIOUS it's revoked on a later sweep.
5. **No regression / perf.** A non-saint player and NPC see today's exact numbers;
   confirm no per-tick spam (the saint multiplier is an O(1) map read; the NPC sweep is
   daily, the player review on the 200-tick theophany cadence).

## Saints & Relics SR2 — canonized (deceased) saints (2026-06-10)

The second saint tier: the roster of the venerated dead. A living saint who dies is
auto-canonized; a martyr is canonized for dying for the faith; an exceptionally
virtuous NPC becomes a "Venerable" a high priest later elevates. Canonization inscribes
the grave, makes it lasting holy ground, gives the faith a new saint's day, and
chronicles it — all by REUSING the sacred-site, calendar, and chronicle machinery.

### Disposition (investigation)

- **Death pipeline:** `TownspersonMob.onNpcDeath(LivingDeathEvent)` → `DeathArc.onNpcDeath`
  buries via `GraveyardSavedData.bury` (grave slot = `gravePos`). Death cause was NOT
  retained — `event.getSource().getEntity()` (the killer) is now captured + threaded so
  martyrdom is classifiable.
- **SR1 store:** `SaintsSavedData` (living roster) — extended with the canonized `Saint`
  roster (the room SR1 left).
- **Qualification reads:** `Saints.livingSaintGod` (path 1), `getPiety().primaryTier()`
  (DEVOUT/PIOUS), `getRetirementState().mentoredInPast()` (virtue signal),
  `BuildingFaith.hasSeatedPriestOfFaith` (clergy gate).
- **Reuse points:** `Grave.epitaph` (+ a new `inscribe`); `SacredSpaceSavedData`
  imprint (made durable); `ReligionSavedData.get/put` + `ReligiousCalendar` (saint's
  day); `VillageHistoryLog.record` + a new `HistoryEventType.SAINT_CANONIZED`.

### Model + what shipped

- **`SaintsSavedData.Saint`** (new record) `(saintId, name, religionId, godId, virtue
  [FaithConcept], epitaph, martyr, canonized, deathTick, gravePos, saintDay, relicId
  [SR4 seam, empty])` — a `canonized=false` record is a pending **Venerable**. Second
  codec list beside the living roster; accessors `canonizedSaints`/`venerables`/
  `saintsOf`/`saintAtGrave`/`putSaint`.
- **`Canonization`** (new) — the ONE entry point + the three paths + the shared tie-in
  routine:
  - `onNpcDeath(level, deceased, killer, now)` (after burial): **path 1** living saint →
    auto (moved off the living roster); **path 2** martyr (DEVOUT+ slain by another
    living entity) → auto; **path 3** Venerable (PIOUS, or DEVOUT + a mentorship
    legacy) → recorded.
  - `dailyVenerableSweep` — a high priest of the faith (`hasSeatedPriestOfFaith` in any
    village) elevates pending Venerables. From `RiteScheduler.dailyTick`.
  - `applyTieIns` — the ONE reuse routine (auto-canonize and elevation both call it):
    inscribe epitaph → durable imprint → saint's day → chronicle.
- **Durable grave imprint:** `SacredSpaceSavedData.Imprint` gained a `permanent` flag
  (optionalFieldOf, pre-SR2 saves load false); `currentStrength` returns the anchor
  strength with no decay/prune when permanent; `addPermanentImprint` is the saint-grave
  writer (the shrine-slow / rite-refresh still raise it). The decaying theophany imprint
  is unchanged (permanent=false).
- **Saint's day — the FIRST real `ReligionSavedData.put`:** `addSaintDay` fetches the
  per-world religion, copies its calendar + adds `"St. <name>'s Day" → saintDay` (the
  death-day's day-of-year, or the next free day), rebuilds the `Religion` preserving
  every other field (godIds, rites, …), and `put`s it. `SacredTime` then applies the
  holy-day bonus on that day automatically.
- **Chronicle:** `SAINT_CANONIZED` (new `HistoryEventType`, MAJOR → never pruned) in the
  village holding the grave.
- **Epitaph:** the formerly-unused `Grave.epitaph` is inscribed ("Here lies <name>,
  Saint/Martyr of <God>.") via new `Graveyard.inscribe` / `GraveyardSavedData
  .inscribeEpitaph`.
- **Surfacing:** `/religion saints` now also lists the canonized roster (name, martyr?,
  god, virtue, saint's day) + pending Venerables. The grave reads sacred in
  `/religion sacred` via the imprint.

### Tie-In Audit

1. **Upstream feeders.** `LivingDeathEvent` (death + the now-captured killer), SR1
   `Saints` living roster, piety tier + `mentoredInPast`, clergy lookup. Confirmed.
2. **Downstream callers.** `SaintsSavedData` (roster), `Graveyard` (epitaph),
   `SacredSpaceSavedData` (permanent imprint → flows through `sacrednessAt` and every
   sacred effect), `ReligionSavedData.put` (saint's day → `SacredTime`),
   `VillageHistoryLog` (chronicle). Each runs through the single `applyTieIns`.
3. **Sibling systems.** The saint's-day `put` rebuilds the `Religion` field-for-field
   (only the calendar changes) — godIds/rites/preferredBookCategories intact, and the
   relation OVERRIDES live in `ReligionSavedData`'s separate map (untouched). The
   permanent imprint composes additively into `sacrednessAt` with natural/built/theophany
   (no double-count — it's just another imprint summed by god + distance).
4. **Exhaustive switches.** **Grepped every `switch` — none is over `HistoryEventType`**
   (the matches were `goal.type()`/`rite.type()`/`effect.type()`/etc., distinct enums);
   `HistoryEventType` uses a per-value `defaultImportance()` field, not a consumer
   switch. `SAINT_CANONIZED` needs no arm updates.

### Simplification Sweep

- **One canonization entry** (`Canonization.onNpcDeath`) feeding the three paths; **one
  tie-in routine** (`applyTieIns`) so the auto and elevation paths don't each
  re-implement epitaph+imprint+day+chronicle.
- **Durable imprint did NOT fork the store** — a `permanent` flag on the existing
  `Imprint` + one branch in `currentStrength`, reusing `addImprint`'s body.
- **Touched classes:** `SaintsSavedData`, `Canonization` (new), `SacredSpaceSavedData`,
  `Graveyard`, `GraveyardSavedData`, `HistoryEventType`, `TownspersonMob` (death hook),
  `RiteScheduler` (sweep), `ReligionDebugCommand` (readout).

### Deviations from prompt

- **Canonization is NPC-only.** A player living saint who "dies" merely respawns — they
  don't permanently die, get a grave, or canonize; their living-saint status (SR1) is
  their summit. (The prompt's smoke test names a player, but canonizing a respawning
  player is incoherent — the dead roster is for the truly dead.)
- **Martyr bar = the conservative base** ("violent death of a DEVOUT+ believer slain by
  another entity"). The "stronger if near a sacred site / by a rival faith" weighting is
  a tuning refinement deferred (it would LOWER the bar; kept conservative so martyrdom
  stays rare).
- **Venerable virtue signal** = PIOUS, or DEVOUT + a mentorship legacy
  (`mentoredInPast`). "Widely positively remembered" (memory polarity) left as a future
  signal — `mentoredInPast` is a clean, stored proxy.

### Out-of-scope but flagged

- **SR3** — intercession: pray at a saint's grave → cooldowned favour / minor miracle,
  refreshing the grave imprint; the veneration loop that can later auto-elevate
  Venerables or drive a player-driven canonization ceremony.
- **SR4** — relics (a saint's relic item); `relicId` stays empty.
- **Deferred** — player-driven canonization ceremony / UI; NPC favour/theophany to unify
  the saint paths; the richer martyr/Venerable signals (sacred-site/rival-faith
  weighting, memory polarity).

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava --offline` fails resolving `neoform-runtime:2.0.18` before javac; no javac
errors surfaced). [Container had drifted to the R9c base on resume; fetched + `git reset
--hard` to the SR1 remote tip restored all pushed work — no loss.] Static review +
**multi-line/qualifier-split grep** (the F1b lesson — and it mattered: the two
`Canonization` call sites are FQN-split across lines, invisible to the single-line grep,
confirmed present by the plain grep): the `Saint` codec is 12 fields (under the cap);
`Imprint` is now 6 fields (the `permanent` optionalFieldOf keeps pre-SR2 saves loading);
the saint's-day `put` rebuilds all 8 `Religion` fields (no clobber); `SAINT_CANONIZED`
hits no exhaustive switch; `addImprint`'s existing 4-arg caller still resolves via the
delegating overload.

### Smoke test (user-runnable)

1. **Living saint → auto-canonize.** Make an NPC a living saint (SR1: sustained PIOUS),
   then kill it → `/religion saints` lists it under Canonized; `/religion sacred <god>`
   at its grave reads sacred and DOESN'T fade over days; the grave epitaph is inscribed;
   the faith gains a saint's day (verify a `SacredTime` holy-day bonus applies on that
   day-of-year); a `SAINT_CANONIZED` chronicle entry exists.
2. **Martyr.** Kill a DEVOUT+ non-saint NPC by another entity → auto-canonized as a
   Martyr.
3. **Venerable → elevation.** Let a PIOUS (or DEVOUT + mentor) NPC die of old age →
   recorded as a Venerable; with a seated priest of its faith present, a daily sweep
   canonizes it.
4. **Nobody.** An ordinary (sub-DEVOUT, non-saint) NPC death canonizes no one.
5. **`put` integrity + persistence.** Confirm the saint's-day `put` left the religion's
   rites/godIds/relations intact (`/religion world list`, `/religion relations`);
   save+reload and confirm the roster + saint's day + permanent grave imprint persist.

## Saints & Relics SR3 — intercession (pray to a saint) (2026-06-10)

The first player-initiated religious PETITION. At a canonized saint's grave a player
prays for intercession — earning the saint's god's favour + a small blessing, TENDING
the grave (refreshing its imprint), and VENERATING the saint. Veneration is also a
grassroots path to sainthood: enough prayer at a Venerable's grave raises them by
popular devotion, complementing SR2's clergy elevation.

### Disposition (investigation)

- **Prayer input:** graves are generic cobblestone-wall markers (the data is the
  source of truth; `GraveVisit` is NPC-side), so a block-interaction would be
  ambiguous. **Chosen: a `/religion pray` command** requiring the player within
  {@code PRAY_RADIUS}=4 of a saint's grave (`SaintsSavedData.nearestSaintGrave`) — the
  project's command-driven idiom, lower risk.
- **Grave/Venerable resolution + veneration home:** added
  `SaintsSavedData.nearestSaintGrave(pos, radius)` (canonized AND Venerable graves);
  veneration accrues on the `Saint` record (new `veneration` int + `withVeneration`).
- **Favour + blessing:** `DivineFavour.award(level, pid, godId, FavourAct.PRAYER, now,
  gravePos)` — position = the grave, so the SR2 permanent imprint sacred-amplifies it
  (S1b); a bounded `MobEffect` by the god's domain for the lesser blessing.
- **Reuse for elevation:** SR2's tie-in routine, exposed as
  `Canonization.canonizeVenerable` (delegates to the private `canonizeNow`/`applyTieIns`
  — not a second routine).
- **Cooldown:** per-player/per-saint last-prayer tick map in `SaintsSavedData`
  (codec'd, persists).

### What shipped

- **`FavourAct.PRAYER`** (8f, no concept) — the intercession grant. (Enum-sweep: its
  only exhaustive `switch`, `PlayerCalling.describe`, got a `PRAYER` arm; `CALLABLE` is
  an explicit array, so PRAYER is not auto-offered as a divine-calling task.)
- **`SaintsSavedData`** — `Saint.veneration` (+ `withVeneration`); `nearestSaintGrave`;
  a `prayerCooldowns` map (3rd codec field, `optionalFieldOf` empty) + `canPray`/
  `recordPrayer`.
- **`Intercession`** (new, `Saints/`) — the ONE prayer entry point: find the nearest
  saint grave → gate (FAITHFUL+ with the patron god via `tierForGod`) → per-saint/day
  cooldown → favour grant (grave-position, sacred-amplified) → a lesser domain blessing
  (1-min, amp 0) → `refreshNear` the grave imprint (tend) → record cooldown → at a
  Venerable's grave, accrue veneration and, at {@code VENERATION_THRESHOLD}=10, call
  `Canonization.canonizeVenerable`. Returns a `Result(ok, message)`.
- **`Canonization.canonizeVenerable`** (new public) — popular elevation reusing the SR2
  tie-ins (idempotent — no-op if already canonized).
- **Surfacing:** `/religion pray` (chat feedback); `/religion saints` shows each
  Venerable's `veneration N/10` progress.

### Tie-In Audit

1. **Upstream feeders.** `SaintsSavedData` (grave lookup / Venerables / veneration),
   the `/religion pray` input, `DivineFavour` (favour + the PRAYER act),
   `SacredSpaceSavedData.refreshNear` (tend), `tierForGod` (gate). Confirmed.
2. **Downstream callers.** The favour grant (now also from prayer; PRAYER arm added to
   the lone FavourAct switch), the blessing effect, the elevation (SR2 `applyTieIns`
   via `canonizeVenerable`), the readouts. Each dispositioned.
3. **Sibling systems.** **No runaway feedback:** the prayer is amplified by the grave
   imprint AND refreshes it, but (a) the per-saint/day cooldown bounds the loop to one
   grant/day, (b) `refreshNear` raises the imprint only toward `STRENGTH_CAP`, and (c)
   the favour grant is still clamped to the piety cap — so no unbounded farming. **SR2
   compose:** `canonizeVenerable` is idempotent and flips `canonized=true` (the saint
   leaves `venerables()`), so the clergy sweep can't double-canonize it.
4. **Exhaustive switches.** `FavourAct` — the one exhaustive switch
   (`PlayerCalling.describe`) updated with `PRAYER`; the `god.domain()` blessing switch
   covers all four `DeityDomain` arms. No other enum changed.

### Simplification Sweep

- **One intercession entry** (`Intercession.pray`); **one elevation routine** (the
  grassroots path calls SR2's `Canonization.canonizeVenerable` → the same `applyTieIns`
  — no second canonization implementation); **one cooldown/veneration home**
  (`SaintsSavedData`).
- **Touched classes:** `DivineFavour` (PRAYER), `PlayerCalling` (switch arm),
  `SaintsSavedData` (veneration + cooldown + nearest), `Canonization` (public
  elevation), `Intercession` (new), `ReligionDebugCommand` (pray + readout).

### Deviations from prompt

- **Input is `/religion pray`**, not a grave block-interaction — graves are generic
  cobblestone markers (no distinct grave block to hook), so a proximity-gated command
  is the clean, unambiguous surface.
- **Praying at a Venerable grants the favour + blessing too** (not veneration alone) —
  it's a genuine devotional act; the Venerable's patron god is set. (Its grave has no
  imprint until elevation, so no sacred amplification there yet — correct.)
- **Veneration lives on the `Saint` record** (not a separate ledger) — it travels with
  the saint and is naturally dropped when the record is rebuilt as canonized.
- **Blessing is a fixed bounded domain effect** (1-min, amplifier 0) — a lesser boon,
  deliberately not scaled by favour/tier (that's miracle territory).

### Out-of-scope but flagged

- **SR4** — relics (a saint's relic item: a carried personal benefit + an enshrined
  sacred site, sought/stolen); `relicId` stays the empty seam.
- **Deferred** — a player-driven canonization CEREMONY (vs the command); NPC
  favour/theophany; a fuller saint blessing / miracle-as-intercession (relics/clergy
  can grant more later); a real grave-block interaction surface.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava --offline` fails resolving `neoform-runtime:2.0.18` before javac; no javac
errors surfaced). [Container current at the SR2 tip; no reset needed.] Static review +
**multi-line/qualifier-split grep** + the **enum-sweep preflight** (which earned its
keep: adding `FavourAct.PRAYER` broke the exhaustive `PlayerCalling.describe` switch —
caught by grep, fixed): both `Saint` constructors pass the new 13th `veneration` arg;
the cooldown map is a 3rd `optionalFieldOf` codec field (pre-SR3 saves load empty);
`canonizeVenerable` is idempotent; `CALLABLE` is an explicit array (PRAYER not
auto-added); the blessing `DeityDomain` switch is 4-arm exhaustive.

### Smoke test (user-runnable)

1. **Pray at a saint's grave.** As a FAITHFUL+ believer of the saint's faith, stand at
   a canonized saint's grave (SR2) and `/religion pray` → favour with the saint's god
   (amplified by the grave imprint), a short domain blessing, and chat feedback "St. X
   interceded…".
2. **Cooldown + gate.** A second `/religion pray` same day is refused ("already sought…
   today"); a non-believer / sub-FAITHFUL is refused ("not of … faith").
3. **Tend.** Confirm the prayer `refreshNear`s the grave imprint (`/religion sacred`
   strength holds/rises).
4. **Grassroots elevation.** Pray at a Venerable's grave repeatedly (across days, or
   with several players) — `/religion saints` shows `veneration N/10`; at 10 the
   Venerable is canonized (saint's day / chronicle / durable grave appear, same as SR2).
5. **No farming + persistence.** Confirm favour doesn't runaway (the daily cooldown
   holds); save+reload and confirm veneration + cooldowns persist.

## Saints & Relics SR4 — relics (2026-06-10)

The final holy-layer stage: a saint's <b>relic</b> — a real, holdable item carrying a
fragment of the saint's holiness. <b>Carried</b>, it grants a small standing with the
saint's god; <b>right-clicked</b>, it prays anywhere (portable intercession);
<b>used on a block</b>, it enshrines a permanent sacred site. Being a true item, it can
be sought, given, taken, and moved — the "fought over" flavour falls out of its
tangibility. Fills the `relicId` seam. **This completes the Saints & Relics holy
layer** (living saints → canonized roster → intercession → relics).

### Disposition (investigation)

- **Item pattern:** `JobContractItem`/`StallLeaseItem` carry a record component
  (`*.CODEC` persistent + `*.STREAM_CODEC` via `ByteBufCodecs.fromCodecWithRegistries`)
  registered in `ModDataComponents`; the item registers in `ModItems.ITEMS`
  (`registerItem(name, Ctor::new, props)`). `use` returns `InteractionResult` in this
  build; `useOn(UseOnContext)` likewise.
- **Mint seam:** `Canonization.applyTieIns` — every canonization path (SR2 auto/clergy,
  SR3 grassroots) runs it, so minting there mints once for all paths.
- **Carried fold:** SR1 `SaintFactor.amplifierFor(level, beingId, godId)` — the carried
  bonus folds in there, so it composes with the favour/miracle hooks automatically.
- **Prayer reuse:** SR3 `Intercession.pray` — factored so the grave command and the
  relic right-click share one core.
- **Enshrine reuse:** SR2 permanent `Imprint` via `SacredSpaceSavedData.addPermanentImprint`.

### What shipped

- **`RelicData`** (new component record: `saintId`, `saintName`, `godId`) +
  **`RELIC_DATA`** `DataComponentType` (persistent + synced).
- **`RelicItem`** (new, registered as `ModItems.RELIC`, `stacksTo(1).fireResistant()`):
  tooltip (St. <name> / holy to <god>); `use` (air) → portable intercession via
  `Intercession.prayWithRelic`; `useOn` (block) → enshrine a permanent imprint at the
  target.
- **Mint on canonization:** `Canonization.mintRelic` (in `applyTieIns`) sets
  `Saint.relicId` (= the saintId string, one relic per saint) and best-effort spawns the
  relic `ItemEntity` at the grave with `setUnlimitedLifetime()` (the first reliquary;
  the grave is already permanently sacred from SR2).
- **Carried benefit:** `SaintFactor` now multiplies in `RELIC_CARRY_AMPLIFIER` (1.1)
  when an online player carries a relic of that god — bounded, composing with the
  living-saint 1.25 and the sacred space/time amplifiers.
- **Prayer core factored:** `Intercession.prayTo(level, player, saint, now)` is the one
  prayer body; `pray` (grave command) and `prayWithRelic` (relic) both call it — no
  forked prayer logic.
- **Surfacing:** the relic tooltip; `/religion saints` marks a saint with `[relic]`.

### Tie-In Audit

1. **Upstream feeders.** `Canonization.applyTieIns` (mint), `SaintsSavedData`/
   `Saint.relicId`, `ModItems`/`ModDataComponents` (registration). Confirmed.
2. **Downstream callers.** Carried fold (`SaintFactor` → the favour/miracle hooks that
   already call it — no new hook); right-click intercession (the shared
   `Intercession.prayTo`); enshrine (`addPermanentImprint` → flows into `sacrednessAt`).
3. **Sibling systems.** SR3 intercession — the `pray` refactor preserves the grave-
   prayer behaviour (same body, now via `prayTo`); the relic prayer is the same
   gate/cooldown (no new power). The sacred layer — an enshrined imprint composes
   additively into `sacrednessAt` like the grave's (no double-count). SR1 `SaintFactor`
   — the carried bonus is bounded (1.1) and multiplies cleanly.
4. **Exhaustive switches.** No new enum — the relic reuses `FavourAct.PRAYER` (via
   `prayTo`); the blessing's `DeityDomain` switch (in `Intercession`) already covers all
   arms. Nothing to update.

### Simplification Sweep

- **One mint point** (`mintRelic` inside the single `applyTieIns`); **one prayer core**
  (`Intercession.prayTo`, shared by grave + relic); **one enshrine routine** (the SR2
  permanent imprint). The carried bonus rides the existing `SaintFactor` (no parallel
  favour path).
- **Touched classes:** `RelicData` (new), `RelicItem` (new), `ModDataComponents`,
  `ModItems`, `Canonization` (mint), `Intercession` (factor `prayTo` + `prayWithRelic`),
  `SaintFactor` (carried), `SaintsSavedData` (`withRelicId`), `ReligionDebugCommand`.

### Deviations from prompt

- **`RelicData` stores `godId` (not domain)** — the domain/virtue is resolved from the
  god at runtime (tooltip + the blessing), avoiding redundant stored data.
- **Enshrine does NOT consume the relic and has no cooldown** — deliberate per the
  prompt ("moving it later doesn't un-bless"; "a relic carried to a new shrine spreads
  sacredness"). Bounded because the sacred amplifier itself is capped (1.5×), so
  multiple sacred sites don't create unbounded favour.
- **Relic spawn at the grave is best-effort** (chunk-load gated, like the SR2 headstone
  marker); `relicId` is set regardless, and the grave is already sacred. A relic that
  failed to spawn (unloaded chunk) simply isn't in the world — no re-mint path here.
- **Carried benefit folded into `SaintFactor`** (resolves the online player + scans the
  main inventory) — no new favour hook; it inherits the favour + miracle composition for
  free.

### Out-of-scope but flagged

- **Future tails:** a player canonization CEREMONY; NPC favour/theophany (to unify the
  saint paths); **relic quests / theft** (the item's movability is the flavour; deeper
  relic gameplay belongs to the quest base); a bless-others saint AURA.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava --offline` fails resolving `neoform-runtime:2.0.18` before javac; no javac
errors surfaced). [Container current at the SR3 tip; no reset needed.] Static review +
**multi-line/qualifier-split grep**: `RelicData` mirrors `JobContractTerms` (CODEC +
`fromCodecWithRegistries` STREAM_CODEC); `RELIC_DATA`/`RELIC` register on the existing
mod-event-bus `DeferredRegister`s (already wired in `Life_in_the_village`); `RelicItem`'s
`appendHoverText`/`use`/`useOn` signatures match the build's items (`JobContractItem`/
`PriceBoardItem`); the carried-relic scan returns 1.0 for NPCs (no player) and is bounded;
`Saint` is now constructed only via `withVeneration`/`withRelicId`/`buildSaint` (all 13
args). One NeoForge API to watch on the user's build: `ItemEntity.setUnlimitedLifetime()`
(the enshrined relic's no-despawn) — flagged for a quick fix if the name differs here.

### Smoke test (user-runnable)

1. **Mint + enshrine.** Canonize a saint (SR2/SR3) → a relic item appears at the grave,
   `Saint.relicId` is set, `/religion saints` shows `[relic]`.
2. **Carried benefit.** Pick up the relic → favour/miracles with the saint's god are
   slightly eased (×1.1, composing with sacred space/time and any living-saint ease);
   drop it → the bonus is gone.
3. **Portable prayer.** Right-click the relic far from the grave → a portable
   intercession (favour + a domain blessing), on the SAME per-saint/day cooldown as the
   grave prayer (`/religion pray`).
4. **Enshrine.** Use the relic on a block elsewhere → `/religion sacred <god>` there reads
   MAJOR-sacred (a permanent imprint); the relic stays in hand (movable, re-usable).
5. **Tangible + bounded.** Drop / pick up / give the relic freely; confirm no runaway
   favour (cooldown holds; carried + sacred amplifiers are capped). Save+reload → the
   relic component, `relicId`, and the enshrined imprint persist.

## F2 — F2a-1: the quest engine (base + pluggable objective + one proving kind) (2026-06-10)

The first foundation stage of the unified quest base. F2a-1 builds the ENGINE — the
quest model, the pluggable `Objective` (fixing the mod's biggest quest gap: completion
is currently hardcoded per type across separate listeners), a per-player store, and the
full lifecycle — proven end-to-end by ONE religious objective kind. Additive: the legacy
guild quest system is untouched (it re-seats onto this base in F2b).

### Disposition (investigation)

- **Offering source:** `MakeOfferingVerb` makes the offering + `awardForReligion(...
  OFFERING...)` with `ctx.player()` (ServerPlayer) + `ctx.level()` (ServerLevel) in
  scope — the one `QuestEvents.notify` source.
- **SavedData idiom:** `RiteSavedData`/`SacredSpaceSavedData` → new `QuestSavedData`
  (per-player; `unboundedMap(playerId, Quest.CODEC.listOf())`).
- **Reward hooks:** `DivineFavour.addCapped` (favour, within standing);
  `BuiltInRegistries.ITEM` + `player.getInventory().add` (items). ServerPlayer + level in
  scope at completion (the notify carries the player).
- **Guild Quest:** `Guilds.Adventurer.Quest` — NOT touched; the new base is a separate
  package (`Quests/`), re-seated in F2b.

### What shipped (the engine, in a new `Quests/` package)

- **`Quest`** record `(questId, giver, title, description, objectives [ordered N],
  status, rewards, scope, deadlineTick)` + codec + `allComplete`/`withObjectives`/
  `withStatus`. `Quest.Scope{PLAYER}`. F2a-1 completion = ALL objectives done (no
  sequential gating — the list supports N, gating is later).
- **`Objective`** — the extension point: a sealed interface (one kind in F2a-1:
  `MakeOffering(godId, current, target)`) with `matches(QuestContext)` / `advanced()` /
  `isComplete()` / `describe()` and a **dispatch codec** (`Codec.STRING.dispatch("type",
  …)`). Adding a kind = a `permits` entry + a `MAP_CODEC` arm; no engine change.
- **`QuestEventKind{OFFERING}`** + **`QuestContext(kind, godId, religionId)`** — the
  notify payload (the source resolves the god so the matcher needs no world lookup).
- **`QuestReward`** — sealed + dispatch-coded: `Favour(godId, amount)` (via `addCapped`)
  and `Items(itemId, count)` (registry lookup → inventory/drop).
- **`QuestGiver(Type{DIVINE,GUILD,NPC,KINGDOM}, id)`**; **`QuestStatus{OFFERED, ACTIVE,
  COMPLETED, FAILED, ABANDONED}`**.
- **`QuestSavedData`** — per-player quests (`questsOf`/`active`/`add`/`replace`).
- **`QuestEvents.notify(player, ctx)`** — **THE single completion path**: advance every
  matching objective on the player's active quests; on `allComplete` → COMPLETED + grant
  rewards + feedback; replace + markDirty. The whole point — one hook, many kinds,
  replacing the per-type-listener anti-pattern.
- **Source wired:** `MakeOfferingVerb` calls `QuestEvents.notify(player,
  QuestContext.offering(primaryGodOf(religion), religionId))` after the offering —
  side-effect-free on the offering itself.
- **`QuestIssuer.grantOfferingQuest`** (the deity-issuance stub) + **`/quest`** command
  (`grant <god> [count]` → issues the proving quest; bare `/quest` → lists active quests
  with objective progress + completed count).

### Tie-In Audit

1. **Upstream feeders.** The offering act (the one `notify` source); `DivineFavour`
   (favour reward); `BuiltInRegistries.ITEM` (item reward). Confirmed.
2. **Downstream callers.** `QuestSavedData` (store), the reward grant, the `/quest`
   readout. **The guild quest system is not referenced** by any `Quests/` class (only a
   javadoc cross-reference).
3. **Sibling systems.** The legacy guild `Quest` — separate + untouched (F2b re-seats).
   The religion offering/favour systems — the offering now ALSO notifies the quest
   engine, but the notify is purely additive (it reads the player's quests; the offering
   behaviour is unchanged).
4. **Exhaustive switches.** The new enums (`QuestStatus`/`QuestEventKind`/
   `QuestGiver.Type`/`Quest.Scope`) have no exhaustive consumer switch — the dispatch
   codecs switch over STRING type-tags (with a default), not the enums; grep confirms the
   `case ABANDONED`/`switch(status)` hits are over OTHER status enums (guild quest, village
   event), not these.

### Simplification Sweep

- **One completion path** (`QuestEvents.notify`) — explicitly the replacement for the
  per-type listeners; no parallel completion route introduced. **One store**
  (`QuestSavedData`). **One objective extension point** (the sealed `Objective` + dispatch
  codec). **One issuance stub** (`QuestIssuer`).
- **New classes:** `Quest`, `Objective`, `QuestReward`, `QuestGiver`, `QuestStatus`,
  `QuestEventKind`, `QuestContext`, `QuestSavedData`, `QuestEvents`, `QuestIssuer`,
  `QuestCommand`. Touched: `MakeOfferingVerb` (1 notify call), `ModModEvents` (command
  registration).

### Deviations from prompt

- **`/quest grant` targets the executing player** (self), not an arbitrary `<player>` —
  simpler for proving; an `EntityArgument.player()` target is a trivial later add.
- **The debug grant issues the quest directly as ACTIVE** — the `OFFERED` state exists in
  the lifecycle (for real issuance/acceptance later), but the proving command skips to
  ACTIVE so the notify loop is immediately exercisable.
- **Favour reward via `addCapped`** (capped to the player's standing) — a quest reward
  never bypasses the piety cap.

### Out-of-scope but flagged

- **F2a-2** — the other three religious objective kinds (pilgrimage-to-sacred-site,
  recover/return-relic, perform-rites) + V3-calling graduation + rich givers
  (deity/saint/clergy) + the giver-standing / player-career layer.
- **F2b** — re-seat the legacy guild quest system onto this base (behaviour-preserving,
  F1b-style coverage migration).
- **Later** — grand/staged (sequential-gated) quests + a quest journal UI + Request-board
  / pilgrimage convergence.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava --offline` fails resolving `neoform-runtime:2.0.18` before javac; no javac
errors surfaced). [Container current at the SR4 tip; no reset needed.] Static review +
**multi-line/qualifier-split grep** + the exhaustive-switch sweep (clean — no consumer
switch over the new enums): the dispatch codecs follow the vanilla `Codec.STRING.dispatch`
pattern (`MAP_CODEC` map-codec arms keyed by a constant `TYPE` tag); `Quest.CODEC` is 9
fields (under the cap); the offering `notify` is wired with the player/level in scope; the
guild system is unreferenced. One generics watch-item on the user's build: the
`Codec.STRING.dispatch("type", Objective::type, …)` target-typed to `Codec<Objective>` —
the standard dispatch inference; a `Codec<Objective>` witness fixes it if inference
balks here.

### Smoke test (user-runnable)

1. **Issue + track.** `/quest grant sun_mother 3` → `/quest` shows "Offerings to the
   Sun-Mother" ACTIVE with `0/3`.
2. **Advance.** Make an offering to a Sunstead temple → `/quest` shows `1/3`; three
   offerings → the quest completes, the favour reward is granted, a "Quest complete"
   message fires, and it leaves the active list (Completed: 1).
3. **Non-match.** An offering to a DIFFERENT god (or a tithe) does NOT advance it.
4. **No behaviour change.** The offering itself behaves exactly as before (the notify is
   side-effect-free on it); the guild quest system is entirely unaffected.
5. **Persistence.** Save+reload → active + completed quests persist (codec round-trip).

## F2 — F2a-2: the three remaining religious objective kinds (2026-06-10)

F2a-1 proved the quest engine with one kind. F2a-2 fills out the religious objective
vocabulary — pilgrimage, enshrine-a-relic, perform-rites — each a new `Objective` kind +
a `notify` call on an existing religious action. **The engine core was NOT touched**
(git-diff-clean on `QuestEvents`/`Quest`/`QuestReward`/`QuestSavedData`) — the proof the
F2a-1 "kind = permit + codec arm + notify source" design holds. Still additive (guild
quests untouched); issuance stays the `/quest grant` debug path.

### Disposition (investigation)

- **Engine extension points (confirmed from F2a-1):** `Objective` (sealed `permits` +
  dispatch-codec arm), `QuestEventKind`, `QuestEvents.notify` (the one path),
  `QuestContext`. A kind needs only these + a notify source.
- **Notify sources + their god context:**
  - `Intercession.prayTo` (the SR3/SR4 shared prayer core) — the god = `saint.godId()`;
    a prayer at the god's saint grave IS reaching its sacred ground → `VISIT_SITE`.
  - `RelicItem.useOn` enshrine — the god = `RelicData.godId` → `ENSHRINE`.
  - `AttendRiteVerb` (`targetFaith`) / `CommissionRiteVerb` (`faith`) — resolve the
    faith's primary god → `RITE`.
  - `SacredSpace.tierAt` proximity-reach — see Deviations (deferred for perf).

### What shipped

- **`QuestEventKind`** — added `VISIT_SITE`, `ENSHRINE`, `RITE`.
- **`Objective`** — three new permits + dispatch arms (mirroring `MakeOffering`):
  `VisitSacredSite`, `EnshrineRelic`, `PerformRites` (each `(godId, current, target)`,
  matching its kind on god equality). The dispatch `switch` gained three arms; **nothing
  in `QuestEvents`/`Quest` changed.**
- **`QuestContext`** — `visitSite`/`enshrine`/`rite` factories.
- **Four notify sources wired** (each side-effect-free, AFTER the host action's effects):
  `Intercession.prayTo` → `VISIT_SITE`; `RelicItem.useOn` → `ENSHRINE`;
  `AttendRiteVerb` + `CommissionRiteVerb` → `RITE` (faith → primary god).
- **`QuestIssuer.grant(level, player, godId, kind, count)`** (offering / pilgrimage /
  relic / rites) + **`/quest grant <god> <kind> [count]`** (kind-suggested).

### Tie-In Audit

1. **Upstream feeders.** `Intercession.prayTo`, `RelicItem` enshrine,
   `AttendRite`/`CommissionRite`, (deferred: `SacredSpace.tierAt`) — each has the
   god/position context the matcher needs (the source resolves the god).
2. **Downstream callers.** `QuestEvents.notify` (now called from 5 sources); the three
   new `Objective` kinds; `/quest grant`. **The engine core is unchanged** (verified via
   `git diff`).
3. **Sibling systems.** Praying / enshrining / attending / commissioning behave
   identically — the notify runs after the action's own effects and only reads the
   player's quests. The sacred/relic/rite systems are undisturbed.
4. **Exhaustive switches.** The new `QuestEventKind` values have no exhaustive consumer
   switch — the objective matchers use `==` equality, and the dispatch codecs switch over
   STRING type-tags with a default. (The grep's `case OFFERING`/`switch(kind())` hits are
   over OTHER enums — `FavourAct`, layout-event/plaza-piece kinds.)

### Simplification Sweep

- **Engine core NOT touched** (the headline proof) — three kinds added purely via
  permits + codec arms + notify sources. **One notify call per source**; the god
  resolution at the two rite verbs reuses the same `primaryGod(Religions.get(...))`
  one-liner already used by the offering source (no new resolution logic).
- **New arms/classes:** `Objective.VisitSacredSite`/`.EnshrineRelic`/`.PerformRites` (+
  their dispatch arms); 3 `QuestEventKind` values; 3 `QuestContext` factories; the
  generalized `QuestIssuer.grant`. Touched sources: `Intercession`, `RelicItem`,
  `AttendRiteVerb`, `CommissionRiteVerb`, `QuestCommand`.

### Deviations from prompt

- **`VISIT_SITE` (pilgrimage) completes by praying at the god's sacred site** (a saint
  grave, via `Intercession.prayTo`). **The optional "reach the god's sacred space"
  proximity notify is deferred** — passive reach detection needs a per-player tick, and a
  per-tick `SacredSpace.tierAt` is a structure-backed query that would violate the S1b/S2
  perf discipline. The prompt's "and/or" allows scoping to the prayer source; the
  proximity variant can ride a future per-player quest tick (or a cheap cached
  sacredness) when one exists.
- **The four count-kinds share a structure** (`godId, current, target`); kept as distinct
  named permits per the engine model (each a versioned kind for future divergence). A
  shared generic codec helper was tried then reverted to the proven inline
  `RecordCodecBuilder.mapCodec` (avoiding generics-inference risk on the unbuildable
  sandbox).

### Out-of-scope but flagged

- **F2a-3** — V3-calling graduation (`DivineVision` issues a real quest instead of a bare
  `PlayerCalling`; the favour-act hook becomes quest completion) + rich givers
  (deity/saint/clergy issuance) + the giver-standing / player-religious-career layer.
- **F2b** — re-seat the legacy guild quest system onto this base.
- **Later** — recover-a-stolen/moved-relic quests (needs relic-location tracking); staged/
  grand quests; a quest journal UI; the proximity sacred-site-reach notify.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava --offline` fails resolving `neoform-runtime:2.0.18` before javac; no javac
errors surfaced). [Container had drifted to the R9c base on resume; fetched + `git reset
--hard` to the F2a-1 remote tip restored all pushed work — no loss.] Static review +
**multi-line/qualifier-split grep** (the four notify calls are FQN-split across lines —
the plain grep confirmed all five sources present) + the exhaustive-switch sweep (clean):
the engine core is `git diff`-empty vs F2a-1; the three new dispatch arms use the proven
inline `MAP_CODEC` pattern; the rite verbs' god resolution mirrors the offering source;
each notify is additive after its action.

### Smoke test (user-runnable)

1. **Rites.** `/quest grant sun_mother rites 3` → `/quest` shows it 0/3 → attend or
   commission 3 Sunstead rites → advances + completes (favour reward granted).
2. **Pilgrimage.** `/quest grant sun_mother pilgrimage` → pray at a Sun-Mother saint's
   grave (`/religion pray` or the relic right-click) → completes.
3. **Relic.** `/quest grant sun_mother relic` → enshrine a Sun-Mother relic (use it on a
   block) → completes.
4. **Non-match.** A rite of a DIFFERENT faith, enshrining a different god's relic, or
   praying to a different god's saint does NOT advance the quest.
5. **Unchanged hosts + perf.** Praying / enshrining / rites behave exactly as before
   (the notify is side-effect-free); no per-tick perf cost was added (no
   sacred-site-reach tick). Save+reload mid-quest → progress persists.

## F2 — F2a-3: V3 calling graduation + the giver-standing (career) layer (2026-06-10)

The integrative stage that **completes F2a**: graduate the V3 divine callings (the mod's
existing invisible "religious quest") into real first-class quests on the F2 engine
(convert-then-delete the parallel `PlayerCalling`), and add the **giver-standing** layer
— the spine of the player religious career. Self-contained; guild quests untouched.

### Disposition (investigation)

- **V3 calling (as-built):** `DivineVision.deliver` rolls a vision; some lay a
  `PlayerCalling(religionId, FavourAct, issuedTick)` on `RiteSavedData`;
  `DivineVision.onFavourAct` (called from `DivineFavour.awardForReligion`) fulfils it
  (clears it, grants `CALLING_REWARD=15` favour, sends a confirmation vision); the screen
  shows the active calling.
- **`PlayerCalling` usage surface (grepped, the coverage list):** `PlayerCalling.java`
  (the record); `RiteSavedData` (codec field + map + fromCodec param + get/set/clear
  accessors); `DivineVision` (lay in `deliver`, fulfil in `onFavourAct`);
  `DivineFavour.awardForReligion` (the fulfilment call); `PlayerReligionSnapshotBuilder`
  (the screen's calling line); `PlayerReligionScreen` (the "Calling" label).
- **`FavourAct` → `Objective` kind:** `CALLABLE` is only OFFERING / ATTEND_RITE /
  COMMISSION_RITE → MakeOffering / PerformRites (clean; no gap). PILGRIMAGE →
  VisitSacredSite; other acts fall back to an offering objective.
- **Completion path for standing:** `QuestEvents.notify`'s single `allComplete` branch.

### What shipped

**Part A — graduation (convert-then-delete):**
- `QuestIssuer.issueDivineCalling(level, player, religionId, act, callingReward, now)` —
  builds a real ACTIVE DIVINE quest (giver = the faith's primary god), an `Objective` from
  the act, rewards = `Favour(godId, CALLING_REWARD)` + a new `QuestReward.Vision(godId,
  confirmation)` (the god-voiced fulfilment line, reusing `DivineVision.loreFor`).
- `DivineVision.deliver` now **issues that quest** instead of a `PlayerCalling` (gated on
  no active DIVINE quest); the issuance vision ("I would have you serve. … ") still fires.
  The quest engine's existing notify path (offering/rite/pilgrimage, F2a-1/2) tracks +
  completes it + grants the favour/vision reward — **parity with the old `CALLING_REWARD`
  + confirmation vision**.
- **Deleted:** `PlayerCalling.java`; `DivineVision.onFavourAct`; the `DivineFavour
  .awardForReligion` fulfilment call; the `RiteSavedData` calling state (codec field, map,
  fromCodec param, get/set/clear accessors → 6→5 codec fields; a pre-F2a-3 save's
  "playerCalling" key is ignored on load). The screen's calling line is **rerouted** to
  the active DIVINE quest; its label is "✦ Divine quest — …".

**Part B — giver standing (the player religious career):**
- `QuestSavedData` gained a `standingByPlayer` map (playerId → giverKey "TYPE:id" → count;
  3rd... codec field, empty default) + `accrueStanding`/`standing`/`standings`/`giverKey`
  + `hasActiveGiverQuest`.
- **Accrued in the ONE completion path** (`QuestEvents.notify`'s `allComplete` branch) —
  not per-kind.
- `DevotionRank{SUPPLICANT(0), DEVOTEE(1), DISCIPLE(3), CHAMPION(6)}` + `fromCount`.
- Surfaced: a "--- Devotion ---" section in `/quest` (per god: rank + completed count).

### Tie-In Audit

1. **Upstream feeders.** `DivineVision.deliver` (issuance), `QuestEvents` completion
   (→ standing), the act→kind map. Confirmed.
2. **Downstream callers.** Every `PlayerCalling` reader re-pointed/removed (grep-clean —
   zero code refs remain, only comments): the screen line → active DIVINE quest; the
   fulfilment + state deleted; the `DivineFavour` call removed. The standing surfacing in
   `/quest`.
3. **Sibling systems.** The favour economy — the graduated quest's `Favour` reward is the
   old `CALLING_REWARD` (15) via `addCapped` (parity; capped to standing). The SR1
   living-saint status — standing is a COMPLEMENTARY career facet (distinct map/rank),
   not conflated. The F2 engine — standing rides the existing completion path (one accrual
   line; the `Quest` record is `git diff`-untouched).
4. **Exhaustive switches.** New `DevotionRank` — its only switch is its own `displayName`
   (4-arm exhaustive); no external consumer switch. The retired `PlayerCalling.describe`
   `FavourAct` switch is deleted with the class (no orphan). No other enum touched.

### Simplification Sweep

- **The `PlayerCalling` parallel quest system is DELETED** — the convert-then-delete
  payoff: no orphan state (RiteSavedData calling map gone), no orphan reader (all
  re-pointed), no orphan fulfilment (onFavourAct gone). Grep-verified.
- **Standing is one record + one accrual point** (`QuestSavedData.standingByPlayer` +
  `accrueStanding` in `QuestEvents`) + one rank enum.
- **Deleted:** `PlayerCalling`. **New:** `DevotionRank`, `QuestReward.Vision`,
  `QuestIssuer.issueDivineCalling`, the standing map/methods. **Touched:** `DivineVision`,
  `DivineFavour`, `RiteSavedData`, `PlayerReligionSnapshotBuilder`, `PlayerReligionScreen`,
  `QuestEvents`, `QuestSavedData`, `QuestCommand`.

### Deviations from prompt

- **The screen's calling field name (`activeCalling`) is kept** (now sourced from the
  active DIVINE quest, label "Divine quest") — avoids an `OpenPlayerReligionPacket` schema
  change; the screen still shows the divine task line.
- **Standing surfaced in `/quest`** (not the religion screen) — the screen surfacing would
  need a packet field; the prompt allowed "/quest and/or the screen". The screen already
  shows the active divine quest (the calling line); the rank lives in `/quest`.
- **The confirmation vision's lore is generated at ISSUANCE** (stored in the `Vision`
  reward text) rather than at fulfilment — same flavour, fixed at issue time.

### Out-of-scope but flagged

- **F2b** — re-seat the legacy guild quest system onto this base (behaviour-preserving,
  F1b-style coverage migration).
- **Later** — saint/clergy quest givers; rank-gated perks / quest-unlock trees; staged/
  grand quests + a quest journal UI; guild/other-profession careers modelling this
  standing.

### Build verification

Build verification deferred (sandbox blocks maven.neoforged.net — `./gradlew
compileJava --offline` fails resolving `neoform-runtime:2.0.18` before javac; no javac
errors surfaced). [Container had drifted to the R9c base on resume; fetched + `git reset
--hard` to the F2a-2 remote tip restored all pushed work — no loss.] Static review +
**multi-line/qualifier-split grep** (the `PlayerCalling` retirement is grep-clean — zero
code refs, only comments) + the exhaustive-switch sweep (clean): `RiteSavedData` is 6→5
codec fields with `fromCodec` re-arity'd; the `Quest` engine record is `git diff`-empty;
the new `QuestReward.Vision` arm uses the proven inline `MAP_CODEC`; standing accrues in
the single completion path. One generics watch-item carried from F2a-1: the dispatch
codecs' target-typing.

### Smoke test (user-runnable)

1. **Graduation.** Drive a god's favour high so `DivineVision` lays a divine quest →
   `/quest` shows it ACTIVE (a real quest with a tasked objective, not the old invisible
   calling); the religion screen's "✦ Divine quest" line shows it.
2. **Fulfilment parity.** Do the tasked act (offering / rite) → the quest completes, the
   favour reward (15, the old `CALLING_REWARD`) lands, and the god-voiced confirmation
   vision fires — matching the old calling fulfilment.
3. **Career.** Complete several DIVINE quests for a god → `/quest`'s "Devotion" section
   shows the rank climbing (Supplicant → Devotee → Disciple → Champion).
4. **No remnant.** Confirm no old "Calling" flag/state remains; the living-saint status +
   favour are unaffected (complementary).
5. **Persistence.** Save+reload → the active divine quest + the standing/rank persist
   (and a pre-F2a-3 save loads cleanly, its "playerCalling" key ignored).
