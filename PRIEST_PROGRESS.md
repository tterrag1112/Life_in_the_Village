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
