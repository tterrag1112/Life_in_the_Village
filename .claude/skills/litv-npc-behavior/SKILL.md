---
name: litv-npc-behavior
description: >
  Writing or modifying Minecraft NPC behaviors in the Life in the Village
  mod (Brain BehaviorControl, vanilla Goal, or Homestead handler classes
  that interact with inventory, storage, commerce, economy, navigation,
  tools, or skill XP). Use this skill whenever you're about to write
  `npc.getPersonalInventory().addItem`, `mob.getNavigation().moveTo`,
  `npc.getSkills().addXp`, or any equivalent direct-utility call —
  there is a canonical helper for each and bypassing it scatters
  conventions across the codebase. Always consult before adding a new
  behavior or modifying an existing one.
---

# Life in the Village — NPC Behavior Conventions

All NPC behavior code (`BehaviorControl` implementations, vanilla `Goal`s,
and Homestead handlers) must route common operations through canonical
helpers. Direct calls to underlying utilities are not necessarily wrong
in every case, but they bypass the layer that future audits read as
"the right way" and complicate refactors.

## Decision table

| If you're about to write | Use instead |
|---|---|
| `npc.getPersonalInventory().addItem(stack)` for produced/harvested item that should be stored | `NpcBehaviorHelpers.depositToBuilding(level, npc, building, stack)` |
| `npc.getPersonalInventory().addItem(stack)` for item the NPC will USE (hoe, weapon, food they're about to eat) | Direct personal-inventory call is correct — staged for use, not for storage |
| `BuildingStorageAccess.storeWithFallback(...)` | `NpcBehaviorHelpers.depositToBuilding(...)` (the facade for the same call) |
| `BuildingStorageAccess.storeItem(...)` | OK if you specifically want building-only (no fallback) — e.g., batch deposit phase |
| `BuildingStorageAccess.takeItem(...)` for withdrawing items | OK; no facade method yet — withdraw patterns are profession-specific |
| `mob.getNavigation().moveTo(x, y, z, speed)` | `NpcBehaviorHelpers.walkTo(npc, pos, speed)` |
| `entity.getBrain().setMemory(WALK_TARGET, new WalkTarget(pos, speed, 1))` | `NpcBehaviorHelpers.walkTo(npc, pos, speed)` (does BOTH, with +0.5 centering) |
| `npc.getSkills().addXp(skill, amount, tick)` | `SkillXp.award(npc, skill, amount, tick)` — composes mentee + specialty multipliers |
| `npc.getNavigation()...` for any path-driving call | Go through `NpcBehaviorHelpers.walkTo` |

## Common helpers in detail

### Storage
- `NpcBehaviorHelpers.depositToBuilding(level, npc, building, stack)` — building first, personal inventory fallback. Wraps `BuildingStorageAccess.storeWithFallback`.
- `BuildingStorageAccess.takeItem(level, building, item, count)` — withdraw from building storage (no facade yet; profession-specific).
- `BuildingStorageAccess.countItem(level, building, item)` — count item across all containers in building bounds.

### Navigation
- `NpcBehaviorHelpers.walkTo(npc, BlockPos, speed)` — writes `WALK_TARGET` memory (Brain idiom, MoveToTargetSink in CORE consumes it) AND calls `navigation.moveTo(x+0.5, y, z+0.5, speed)` as backstop. Returns the moveTo boolean so callers can log unreachable targets.
- The +0.5 X/Z centering matches vanilla idiom. Targeting integer NW-corner coords confuses pathfinding around walkable-edge thresholds — always go through `walkTo`.

### Skill XP
- `SkillXp.award(npc, skill, amount, tick)` — central XP entry point. Composes mentee multiplier (apprenticeship co-location bonus) before passing to `SkillComponent.addXp`, whose internal cascade then propagates XP up the hierarchical skill tree (e.g., a CROP_FARMING grant adds 25% to FARMING).
- Specialty multipliers (e.g., `FarmerSpecialtyMultiplier.of(npc, skill)`) are applied to the BASE amount before calling `SkillXp.award`. Composition order: `base × specialty × mentee` → addXp → cascade.

### Tools
- `ToolUseSupport.useToolFromInventory(npc, predicate, level, hand)` — equips matching tool, damages by 1, clears slot on break. Returns true when a tool was used.
- `ToolUseSupport.hasUsableTool(npc, predicate)` — read-only probe.
- `ToolUseSupport.bestToolMultiplier(npc, predicate, scoring, noToolFallback)` — highest productivity multiplier across matching tools in inventory. Pattern: `FarmerBehavior::isHoe + ::hoeProductivityMultiplier + HOE_PRODUCTIVITY_NO_HOE = 0.5f`.

### Wealth and payments
- `NpcEconomy.businessPay(...)` — building treasury → NPC wallet.
- `NpcEconomy.recordRevenue(...)` — NPC wallet → building treasury (production revenue).
- `NpcEconomy.marketPurchase(...)` — buyer wallet → market stall / merchant fallback, with visual effects.
- `NpcEconomy.payWage(...)` — village treasury → NPC wallet.

Do not touch `HouseholdWealthManager` or `VillageTreasury` directly from behavior code — use `NpcEconomy` payment methods.

### Market interactions
- `VillageEconomy.postListing(level, villageId, seller, item, qty, tick)` — auto-priced post.
- `VillageEconomy.findCheapestSeller(level, villageId, item, x, z, tick)` — cheapest available seller / stockpile fallback.
- `VillageEconomy.getBasePrice(item)` / `VillageEconomy.getDynamicPrice(level, villageId, item)` — price queries.
- `ProductionHelpers.findMarketInVillage(npc, level)` — find a market building near the NPC.

## Profession-Brain wiring

New profession-specific behaviors register via `ProfessionBrainFactory.REGISTRARS`:

```java
REGISTRARS.put(Profession.X, (npc, brain) -> {
    brain.addActivity(NpcActivities.WORK.get(), 0,
            ImmutableList.of(new XBehavior()));
    // CORE behaviors (scan / always-on) go on Activity.CORE
    // FIGHT behaviors go on NpcActivities.FIGHT
});
```

The `configureBrain` path runs on the first server tick after `setProfession`
(per the p.3 deferred-configure fix). Profession changes mid-life trigger
a re-config on the next tick via the `professionBrainConfigured` flag in
`TownspersonMob`. Repeat-profession-changes accumulate behaviors in the
Brain (documented follow-up); for first-time setup this works cleanly.

## checkExtraStartConditions gate pattern

Every behavior's `checkExtraStartConditions` should:
1. Check `BrainNavGuard.canSteerNavigation(entity)` if it's going to set WALK_TARGET.
2. Check `entity.isWorkTime()` if it's a WORK-activity behavior.
3. Check any profession-specific preconditions (assigned building, plot, etc.).

For diagnostic visibility, add one-shot LOGGER.warn at each early-exit
condition (see `FarmerBehavior.checkExtraStartConditions` for the
`warnedNoNav` / `warnedOffWorkTime` / etc. pattern from o.3). This
catches "behavior not running, but why?" without an attach-debugger
pass.

## Examples

### Harvesting a crop and depositing

```java
// Walk to crop
boolean reached = NpcBehaviorHelpers.walkTo(npc, cropPos, 1.0);
if (!reached) { /* unreachable — log + skip */ return; }

// Break crop, get drops
List<ItemStack> drops = Block.getDrops(state, level, cropPos, null);
level.setBlock(cropPos, Blocks.AIR.defaultBlockState(), 3);

// Deposit each drop: building first, personal inventory fallback
for (ItemStack drop : drops) {
    NpcBehaviorHelpers.depositToBuilding(level, npc, farmhouse, drop);
}

// Award XP — composes mentee + specialty multipliers
float boosted = 1f * FarmerSpecialtyMultiplier.of(npc, Skill.CROP_FARMING);
SkillXp.award(npc, Skill.CROP_FARMING, boosted, level.getGameTime());

// Damage hoe (if any); no-hoe path proceeds at 0.5x productivity
ToolUseSupport.useToolFromInventory(npc, FarmerBehavior::isHoe,
        level, InteractionHand.MAIN_HAND);
```

### Composing yield multipliers

```java
float yieldMult = seasonMult
        * soilMult
        * weatherMult
        * droughtMult
        * frostMult
        * blightMult
        * hoeMult;
// One multiplier per layer; no double-counting. Specialty / mentee
// stay on the XP-grant side via SkillXp.award.
```

## What this skill is NOT for

- Adding new BuildingTypes: use `litv-building-profile`.
- Generating world content (roads, terrain steps, layouts): use the
  matching `litv-*` skill.
- Skill enum additions: those are mechanical; this skill is about
  HOW behaviors USE skills, not registering new ones.

## Anti-patterns to flag in PR review

- `npc.getPersonalInventory().addItem(producedStack)` — should be
  `NpcBehaviorHelpers.depositToBuilding(...)` when the item is
  produced output meant for storage.
- `npc.getNavigation().moveTo(pos)` without going through
  `NpcBehaviorHelpers.walkTo` — bypasses the +0.5 centering and
  the WALK_TARGET memory pairing.
- `npc.getSkills().addXp(...)` direct — bypasses mentee multiplier.
- New profession behaviors that DON'T register in
  `ProfessionBrainFactory.REGISTRARS` — silently never run.
