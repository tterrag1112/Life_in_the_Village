# 24 — Business Greeting

## Purpose

Right-clicking an NPC inside their workshop during work hours shouldn't
open the full profile screen. The default assumption should be "the
player is here to do business"; the profile should be secondary,
accessed via a small button.

This subsystem adds:
1. Presence tracker detecting when player enters a business building
   during work time.
2. Greeter behavior: nearest eligible worker walks to player and
   offers services.
3. Context-aware interaction handler: business-front screen by
   default during work time; full profile otherwise.

## Data model

### BuildingPresence

Per-player state:

```java
public record BuildingPresence(
    UUID playerId,
    @Nullable UUID buildingId,
    long enteredAtTick
) {}
```

### BuildingPresenceTracker

Per-server, updated in player tick:

```java
public final class BuildingPresenceTracker {
    private static final Map<UUID, BuildingPresence> state = new HashMap<>();

    public static void onPlayerTick(ServerPlayer player);
    public static Optional<UUID> getBuildingFor(UUID playerId);
    public static boolean isInBusiness(ServerPlayer player, ServerLevel level);
    public static void onPlayerLogout(UUID playerId);
}
```

Cheap: one `getBuildingAt` per player per tick.

```java
public static void onPlayerTick(ServerPlayer player) {
    ServerLevel level = player.level();
    VillageSavedData data = VillageSavedData.get(level);

    BlockPos pos = player.blockPosition();
    Optional<Building> current = data.getBuildingAt(pos);
    BuildingPresence prev = state.get(player.getUUID());

    UUID newId = current.map(Building::getId).orElse(null);
    UUID prevId = prev != null ? prev.buildingId() : null;

    if (!Objects.equals(newId, prevId)) {
        if (prevId != null) onLeftBuilding(player, prevId, level);
        if (newId != null) onEnteredBuilding(player, newId, level);
        state.put(player.getUUID(), new BuildingPresence(
            player.getUUID(), newId, level.getGameTime()));
    }
}
```

### BusinessFrontState

```java
public record BusinessFrontState(
    UUID buildingId,
    UUID greeterNpcId,
    long attendingSinceTick,
    BusinessFrontStatus status
) {}

public enum BusinessFrontStatus {
    NONE, GREETER_APPROACHING, GREETER_ATTENDING, CLOSED;
}
```

## Building classification

`BuildingType` gains a flag:

```java
public enum BuildingType {
    MARKET          (flags = BUSINESS_FRONT),
    BLACKSMITH      (flags = BUSINESS_FRONT),
    CARPENTRY       (flags = BUSINESS_FRONT),
    BAKERY          (flags = BUSINESS_FRONT),
    INN             (flags = BUSINESS_FRONT),
    SCRIBE_WORKSHOP (flags = BUSINESS_FRONT),
    LIBRARY         (flags = BUSINESS_FRONT),
    HEALER_HUT      (flags = BUSINESS_FRONT),
    STOCKPILE       (flags = BUSINESS_FRONT),
    TEMPLE          (flags = SERVICE_FRONT),
    TOWN_HALL       (flags = SERVICE_FRONT),
    FARMHOUSE       (flags = BUSINESS_FRONT | RESIDENCE),
    HOUSE           (flags = RESIDENCE),
    // ...

    public boolean hasBusinessFront() { return flags.contains(BUSINESS_FRONT); }
    public boolean hasServiceFront()  { return flags.contains(SERVICE_FRONT); }
}
```

Flags drive greeter eligibility and GUI selection.

## Greeter assignment

When player enters a BUSINESS_FRONT building during workers'
WORK_PRIMARY/SECONDARY:

1. Look up workers at building.
2. Filter available (not combat, not mid-critical).
3. Sort by priority:
   - MARKET_SELLER / SERVICE_FACING role first.
   - Owner next.
   - Highest-skill worker next.
   - First-free last.
4. Select highest-ranked as greeter.
5. Start `GreetPlayerGoal` on that NPC.
6. If no worker available, no greeter; player interacts normally
   with whomever they right-click.

### GreetPlayerGoal

```java
public class GreetPlayerGoal extends Goal {
    private final TownspersonMob npc;
    private ServerPlayer target;
    private Phase phase = Phase.APPROACH;

    enum Phase { APPROACH, ATTENDING, FOLLOW_UP, DISMISS }

    @Override public boolean canUse() { return target != null && hasBuildingCoverage(); }
    @Override public void start() { phase = APPROACH; entity.setCurrentActivity("Greeting customer"); }
    @Override public void tick() { /* approach; fire greeter dialogue; hold ~30s; dismiss if not engaged */ }
    @Override public void stop() { ... }
}
```

Priority between combat and regular production. When active,
production pauses.

## Business front interaction

Right-click greeter (or any worker) inside BUSINESS_FRONT during
work time:

### NpcInteractionHandler routing

```java
public static InteractionResult handle(TownspersonMob npc, Player player, InteractionHand hand) {
    if (npc.level() instanceof ServerLevel level && player instanceof ServerPlayer sp) {
        Optional<UUID> presentBuilding = BuildingPresenceTracker.getBuildingFor(sp.getUUID());
        Optional<Building> building = presentBuilding
            .flatMap(id -> VillageSavedData.get(level).getBuildingById(id));
        boolean businessFront = building.map(b -> b.getType().hasBusinessFront()).orElse(false);
        boolean workTime = npc.isWorkTime();
        boolean npcWorksHere = npc.getAssignedBuildingId().equals(presentBuilding);

        if (businessFront && workTime && npcWorksHere) {
            BusinessFrontScreen.open(npc, sp, level, building.get());
            return InteractionResult.SUCCESS;
        }
    }
    NpcProfileHub.open(npc, sp, level);
    return InteractionResult.SUCCESS;
}
```

### BusinessFrontScreen

Client-side screen derived from profile but reorganized:

- **Primary action area**: trade/service buttons dominate.
- **Secondary sidebar**: small "View Profile" button in corner.
- Service-specific controls:
  - Market → Trade / Rent stall
  - Workshop → Trade / Commission
  - Inn → Order meal / Rent room (Phase 5)
  - Library → Borrow book / Commission book
  - Healer → Request treatment / Buy remedy

Reuses verb framework:
- Primary verb auto-triggered on greet (trade for market; commission
  for scribe).
- Secondary verbs listed (gift, ask about life, challenge, etc.).
- Profile access via small `[View Profile]` button.

## Greeter dialogue

New trees:
- `greeting.business.shopkeeper.work`
- `greeting.business.craftsman.work`
- `greeting.business.innkeeper.work`
- `greeting.business.scribe.work`
- `greeting.business.healer.work`
- `greeting.business.librarian.work`

Placeholders: `{speaker.name}`, `{building.type}`, etc.

Fires once when `GreetPlayerGoal` reaches ATTENDING. Subsequent
right-clicks go through normal dialogue flow.

## Off-hours

Non-work time:
- Status: CLOSED.
- No greeter.
- "We're closed" response + minor mood penalty on repeat.
- Profile access via right-click still works.

Some services (innkeeper, healer) have "emergency" hours — respond
24/7 with higher fees at night.

## Efficiency

Shopping becomes:
1. Enter workshop → worker greets.
2. Right-click greeter → BusinessFrontScreen with Trade primary.
3. Trade UI up.

vs. current:
1. Right-click worker → NpcProfileScreen.
2. Navigate to Actions.
3. Click "Trade".
4. Trade UI.

Two clicks saved, one greet animation gained. The *feel* changes —
workers acknowledge player arrival.

## Persistence

`BuildingPresenceTracker` session-only.
`BusinessFrontState` per-building session-only.
No persistent data added.

## Integration points

### Phase 3 integration

- `BuildingPresenceTracker` registered to player tick.
- `BuildingType` flag field added and populated.
- `GreetPlayerGoal` via `ProfessionGoalFactory` on business-front
  workers.
- `BusinessFrontScreen` client-side.
- `NpcInteractionHandler.handle` routing updated.
- Greeter dialogue trees registered.
- Role-based greeter priority via existing workshop role system.
- `/business presence` and `/business greeter <npc>` debug.

### Phase 4+ integration

- Lodging / inn room rental uses business-front.
- Visitor-flux NPCs also use greeter behavior.

## Behavior contract

### Does

- Detect player entering/exiting buildings.
- Assign greeter when player enters business during work time.
- Route to BusinessFrontScreen in-business, profile otherwise.
- Fire greeter dialogue.

### Does not

- Force interaction; player can wander, ignore, leave.
- Create new verbs (reuses verb framework).
- Prevent off-hours profile access.
- Modify existing profile screen significantly.

## Edge cases

- **No workers in building.** No greeter; player browses freely.
- **Multiple players simultaneously.** Each gets own greeter if
  available; else first-come-first-serve.
- **Greeter in combat or critical task.** Skip to next eligible.
- **Target leaves before greeter arrives.** Abort; return to
  production.
- **Player right-clicks non-greeter while greeter approaching.**
  That worker handles; greeter aborts.
- **Player moves A→B without leaving tracking range.** Transition
  detected correctly.

## Ordering dependencies

Phase 3 depends on:
- Weekly schedule (Phase 2) — `isWorkTime`.
- Existing building-at-pos query.
- Existing NpcProfileScreen.
- Dialogue tree (Phase 1).
- Player verbs (Phase 1).

## Open decisions

- Screen: overlay vs separate? **Proposed: separate screen with
  "View full profile" back-button; shared widgets.**
- Re-greet on rapid re-entry? **Proposed: yes if >60s gap; no for
  rapid.**
- Greeter barks on approach? **Proposed: yes — floating text on
  approach; screen opens only on right-click.**
- Profile access: greeter only or any worker? **Proposed: any
  worker; profile button always present.**

## Does-not-include

- Multi-NPC commentary during interaction.
- Customer queue simulation (single-player-at-a-time per greeter).
- NPC-to-NPC business-front (NPCs use channel router, don't greet).

## Revision Notes

(changes recorded here as the spec evolves after testing)

### 2026-04-26 — Phase 3 wiring session (task 24)

Implementation in `tterrag1112.life_in_the_village.Npc.BusinessFront`
plus the new packet, screen, debug command, and routing patch. Six
greeter trees + fallback added to `StarterTrees`.

**`BuildingType` flag mapping (spec "Things to flag" #1).**
`BuildingType` is a bare-entry enum (every constant on one line, no
body). Adding constructor args would touch every existing line. v1
ships `BuildingTypeFlags` as a static lookup with the same surface
(`hasBusinessFront(BuildingType)`, `hasServiceFront`, `isResidence`).
Per spec table:
- BUSINESS_FRONT only: MARKET, BLACKSMITH, CARPENTRY, BAKERY, INN,
  SCRIBE_WORKSHOP, LIBRARY, STOCKPILE, STONEMASON, WEAVER,
  CANDLEMAKER, MILLER, APOTHECARY, SCHOLARS_RETREAT, STABLE,
  WAREHOUSE, WINERY, ARMORER, TOOLSMITH, ATELIER, DOCKS, FISHERY,
  WOODCUTTER.
- SERVICE_FRONT only: TEMPLE, CHAPEL, SHRINE, TOWN_HALL, CHANCELLERY,
  GUILD_HALL.
- BUSINESS_FRONT + RESIDENCE: FARMHOUSE.
- RESIDENCE only: HOUSE, NOBLE_MANOR.
- Unflagged (no greeter logic): everything else (CASTLE, BARRACKS,
  GUARD_TOWER, etc. — civic / military spaces with no customer-facing
  surface).

**Greeter cooldown (spec "Things to flag" #2).** v1 ships the spec's
proposed 60-second gap as
`GreetPlayerGoal.RE_GREET_COOLDOWN_TICKS = 1200`.
`GreeterAssignment.assignFor` checks
`now - existing.attendingSinceTick() < cooldown` before re-triggering
for the same player.

**Bark vs. screen (spec "Things to flag" #3).** Bark fires on
`GreetPlayerGoal.tickApproach`'s first tick via
`player.displayClientMessage(..., actionBar=true)` — server-side, so
no extra packet or particle plumbing. Screen opens only on right-click
via `NpcInteractionHandler.tryRouteBusinessFront`.

**Multi-player (spec "Things to flag" #4).** v1 first-come-first-serve:
when a second player enters, the per-building `BusinessFrontTracker`
state is overwritten if the same greeter would be selected. The spec
suggests "each gets own greeter if available; else first-come-first-
serve" — v1 implements the fallback case but skips the per-player
greeter pool because workshops average 1-2 workers in practice.
Documented for follow-up.

**`BusinessFrontScreen` layout.** Spec line 178 calls for a screen
"derived from profile but reorganized". v1 ships a compact custom
Screen (240×160) with: title row, large primary action button, four
secondary verb buttons (Gift / Ask Life / Compliment / Greet), small
"View Profile" + "Close" buttons. The screen uses the existing
`StyledButton` parchment-palette widget so it visually matches the
profile screen without duplicating its panel/sidebar infrastructure.
Phase 5 may align further; v1 prioritises the routing change over
visual polish.

**`OPEN_PROFILE` action.** Added to `NpcProfileActionPacket.Action`
so the "View Profile" button can bounce back through
`NpcProfileHub.open`. Backwards-compatible — old saves don't see the
new enum value.

**Off-hours behaviour.** Spec line 213: "We're closed" response on
right-click + minor mood penalty on repeat. v1 wires the message via
`displayClientMessage(actionBar=true)` and tracks per-(player,
building) refusal counts in `BusinessFrontTracker.recordRefusal`.
Hitting 3 refusals in a single visit applies
`MoodTrigger.INSULT_RECEIVED` with raw magnitude -2. The counter
clears when the player leaves the building, so "rapidly visit-leave-
visit" doesn't accumulate.

**Greeter-priority service-facing list.** Spec line 122:
"MARKET_SELLER / SERVICE_FACING role first." v1 codifies via
`GreeterAssignment.serviceFacingRank` — MERCHANT / INNKEEPER / SCRIBE
/ LIBRARIAN / PRIEST / HERALD = rank 3; craftsmen + SCHOLAR = 2;
STOCKPILE_KEEPER = 1; everything else = 0.

**`GreetPlayerGoal` priority.** Spec line 147 wants "between combat
and regular production". `P_SOCIAL_HIGH` (4) sits between
`P_COMBAT` (2) and `P_WORK_PRIMARY` (8) — production pauses while
the greeter attends; combat / survival still pre-empts.

**Dialogue trees.** 6 greeter trees + `greeting.business` fallback.
Tree IDs match the spec exactly so the dialogue lookup falls back
correctly when a profession-specific tree isn't appropriate (e.g.
`greeting.business.healer.work` falls back to `greeting.business`
via the registry's dotted-suffix walk).

**Audit-discovered fixes (during code-review audit pre-commit).**
- `Player.hasDisconnected()` doesn't exist on this NeoForge / Minecraft
  version — switched to `target.isRemoved()` (covers logout +
  entity-removal alike).
- `PlayerVerbInvokePacket` is a 3-field record `(UUID, String, Map)`
  — `BusinessFrontScreen.sendVerb` now passes `Map.of()` for the
  args slot.

**Build verification deferred.** Sandbox can't reach
`maven.neoforged.net` (HTTP 403 `host_not_allowed`). Code review
covered imports / signatures / lambda captures. Exit-criteria
scenarios (walk in → bark; right-click greeter → screen; off-hours
refusal; tracker handles transitions / logouts) need a dev-box build
to validate.
