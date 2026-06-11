# 13 — Festival Grounds

## Purpose

Reserve a space in each village where festivals and events take place,
and stamp event-specific decorations when the NPC event system fires an
event in that village. A festival ground is a plot, not a fixed
structure — it's empty most of the time, decorated only during active
events, and left with fading "morning-after" residue once the event
concludes.

Depends on NPC Phase 5 doc `32-events-expanded` for event firing, event
attendance, and event duration. This subsystem provides the spatial
target and the decoration kits; the NPC plan provides the trigger.

## Design

### Ground selection

The festival ground is not a new building type. It is a reserved plot
attached to the village at planning time.

Selection priority:

1. **Dedicated festival ground slot** — some layouts (PLAZA, CROSSROADS,
   CIVIC-rich shapes) reserve a dedicated 15×15 cleared area adjacent
   to the town square during compose. If available, this is the ground.
2. **Town square VENDOR_ZONE** — when no dedicated ground exists, the
   town square's reserved vendor zone (from subsystem 04) is used.
   Smaller festivals fit.
3. **Largest available GARDEN_PLOT** — at CITY tier, parks can double
   as festival grounds for outdoor gatherings.
4. **Main road closure** — if none of the above, a road segment of at
   least 12 blocks length within the village's core is temporarily
   closed for the event and used as the ground.

Priority 4 is genuinely last-resort and only used if all three prior
options fail. The village's festival ground is cached on the Village
record at realisation time so selection is deterministic.

### FestivalKit per event type

The NPC plan's event types (from doc 32) map to decoration kits:

| Event type           | Kit                     | Footprint | Contents                                                           |
|----------------------|-------------------------|-----------|--------------------------------------------------------------------|
| HARVEST_FESTIVAL     | harvest_feast           | 15×15     | long trestle tables, hay bales, pumpkin decor, bonfire, banners    |
| FESTIVAL_OF_LIGHTS   | lantern_array           | 12×12     | dense lantern arrangement, paper-lantern strings, small stages     |
| VILLAGE_FAIR         | fair_stalls             | 20×20     | vendor stalls, game booths, animal pens, small stage               |
| MARKET_DAY           | extra_stalls            | 10×10     | additional market stalls beyond the permanent market               |
| TRAINING_DAY         | training_yard           | 15×15     | archery targets, sparring ring (hay bales), weapon rack, waypoints |
| WEDDING              | wedding_canopy          | 8×8       | flower-adorned arch, small altar, seating, flower petals on ground |
| FUNERAL              | funeral_procession      | varies    | black banners along procession route, bier at temple, candles      |
| CRAFT_CONTEST        | contest_display         | 10×10     | display plinths, judges' table, crowd rope, small stage            |
| RELIGIOUS_RITE       | rite_circle             | 8×8       | candle circle, altar, incense stands, blessed-ground marker        |
| ENVOY_RECEPTION      | reception_pavilion      | 10×10     | pavilion tent NBT, banners (visiting kingdom), welcome rug         |
| SCHOLAR_EXCHANGE     | exchange_tables         | 8×8       | tables with books, lecterns, map stands, bench seating             |

Each kit ships as a single NBT stamped at the ground origin. Larger
kits may use piece-kit assembly if a single NBT is too rigid.

### Culture variants

Each kit has per-culture variants:

```
structures/default/decoration/festival/harvest/feast_1.nbt
structures/nordic/decoration/festival/harvest/feast_1.nbt
structures/imperial/decoration/festival/harvest/feast_1.nbt
```

Fallback chain via CultureResolver. Festival kits are mandatory for
default culture; other cultures fall through if content is missing.

### FestivalDecorator lifecycle

The decorator has two hooks aligned with the event lifecycle:

**onEventStart(Event event, Village village):**
1. Look up festival ground for the village.
2. Resolve kit via (event.type, village.culture).
3. Backup the existing blocks in the footprint (store in Event NBT).
4. Stamp the kit NBT.
5. Mark the ground as "decorated" on the Village record.

**onEventComplete(Event event, Village village):**
1. Replace the stamped decoration with residue (see below).
2. Schedule cleanup for `tick + 24000` (one in-game day later).
3. When cleanup fires, restore the backed-up blocks.

### Morning-after residue

After an event ends, the decorator doesn't immediately clean up.
Instead, it replaces the festive decoration with a smaller "residue"
pattern:

```
Harvest festival  → few scattered hay bales, empty mugs, stray pumpkins
Festival of lights → spent lanterns (soul lanterns / redstone lamps off)
Village fair     → folded-up stall frames, scattered coin-shaped items
Market day       → fewer additional stalls (partial cleanup)
Wedding          → flower petals on ground (light-colored wool fragments)
Funeral          → wilted flowers, burned candle stubs, black banner
Training day     → broken arrow shafts, wooden training sword on the ground
```

Residue persists for one in-game day before auto-cleanup. Gives a
visible trail of recent village activity when the player visits a
village they were at during a festival.

### Cleanup and restoration

The decorator tracks the original block state of every affected
position via a `FestivalRestoration` record stored on the Event NBT.
When cleanup fires, each position is restored to its pre-festival
state. No block-update cascades happen during cleanup.

If the player or an NPC modified the ground during the festival (placed
or broke blocks), those modifications are preserved (restoration only
touches blocks that match the kit's placement set).

## Data structures

```java
public record FestivalGround(
    UUID groundId,
    UUID villageId,
    BlockPos origin,
    int halfWidthX,
    int halfLengthZ,
    GroundSource source         // DEDICATED, VENDOR_ZONE, PARK, ROAD
) {}

public enum GroundSource {
    DEDICATED,      // dedicated slot reserved during compose
    VENDOR_ZONE,    // town square vendor zone
    PARK,           // park serves as ground
    ROAD            // last-resort road closure
}

public record FestivalRestoration(
    UUID eventId,
    List<BlockSnapshot> originalBlocks,
    long scheduledCleanupTick
) {}

public record BlockSnapshot(
    BlockPos pos,
    BlockState state,
    CompoundTag blockEntityData  // nullable
) {}
```

FestivalGround persists on VillageSavedData. FestivalRestoration is
attached to the Event record (which lives in the NPC Phase 5 event
system).

## Integration points

- **NPC Phase 5 events (doc 32)**: event start/end hooks call the
  FestivalDecorator. Event location resolver consults
  `village.getFestivalGround()`.
- **Shape recipes**: certain layouts (PLAZA, CROSSROADS) emit a
  `FESTIVAL_GROUND` reservation slot during compose. Other layouts
  rely on fallback.
- **Town square (subsystem 04)**: VENDOR_ZONE can serve as festival
  ground for smaller events.
- **Parks (subsystem 09)**: parks register as candidate festival
  ground when available.
- **Event NBT**: extended to include FestivalRestoration record.

## Behavior contract

### Does

- Reserve a deterministic festival ground per village at realisation.
- Stamp event-specific decorations when events fire.
- Leave a residue trail after event completion.
- Restore the ground to original state after cleanup.

### Does not

- Trigger events. Events are owned by the NPC plan.
- Manage event attendees. Attendance is owned by the NPC plan.
- Support simultaneous events at the same ground. If two events
  overlap (edge case), only the first stamps; the second uses fallback.
- Cross villages. One festival per village at a time.

## Edge cases

- **Event fires on a village whose festival ground is uninitialized.**
  Resolves to fallback (VENDOR_ZONE → PARK → ROAD) at event start.
  Cache the choice on the Village record.
- **Festival footprint extends beyond ground dimensions.** Kit NBTs are
  authored to fit 15×15 or smaller by convention. Larger kits require
  a matching large ground or get clipped (truncation warning).
- **Player destroys decoration during an active event.** Player-broken
  blocks are not restored by the decorator (preserves player action).
  Kit NBT pieces remaining continue to count toward event ambience.
- **Village demolished during an active event.** Event cancels;
  decoration cleans up at cancellation.
- **Multiple festivals in one year at the same ground.** Each has its
  own restoration record. Subsequent events restore to the state
  *after* the previous cleanup, so no state accumulates.

## Ordering dependencies

- Requires NPC Phase 5 events (doc 32) complete.
- Requires decoration framework (subsystem 01) for slot-style
  placement — though festivals actually stamp a pre-authored kit,
  the kit discovery uses DecorationProfile infrastructure.
- Shape recipes optionally emit FESTIVAL_GROUND reservation slots —
  coordinate with whatever layouts have dedicated civic space.

## Open decisions

- **Ground size standardization.** Proposed: 15×15 as standard. Large
  events (Village Fair at 20×20) require either a CITY-tier
  dedicated ground or park fallback.
- **Residue duration.** Proposed: 24000 ticks (one in-game day).
  Adjust after feel test.
- **Dedicated-ground probability per layout.** Proposed: PLAZA, DUAL_PLAZA,
  CROSSROADS always; RADIAL rarely (30%); others never. Other layouts
  rely on VENDOR_ZONE fallback.
- **Festival ground moving with village expansion.** Proposed: no.
  Ground is fixed at initial realisation. Future expansion rework
  may revisit.
- **Envoy reception vs. diplomatic event.** Proposed: same kit
  regardless of visiting kingdom; visiting-kingdom banners applied
  via dynamic NBT overrides (event tag includes origin kingdom).

## Does-not-include

- Player-triggered festivals (celebratory player actions).
- Event invitation mechanics (NPC plan owns this).
- Musical effects during festivals (out of scope — noted in 00).
- Dynamic weather during events (rain cancels outdoor festivals —
  future NPC plan polish).
- Fireworks or particle effects during festivals (scope excluded).

## Revision notes

(Changes recorded here as the spec evolves.)
