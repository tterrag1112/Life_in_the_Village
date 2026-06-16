# 06 — Signs and Markers

## Purpose

Make a village legible at a glance. A player approaching a settlement
should know what it is before they read a single text label: a gate
sign with the village name, emblems on guild halls, trade signs over
shop doors, boundary stones in the perimeter, a noticeboard with real
current events.

Legibility is a functional concern, not polish — the player's ability
to navigate and understand the village depends on it.

## Design

### Four sign categories

**Welcome markers** — placed where a trade road enters the outermost-
building ring. Displays the village name and, optionally, its culture
emblem. Variants:

```
Simple post      hamlet tier; carved log with sign on it
Arch             village tier; freestanding wooden arch spanning road
Stone marker     town tier; chiseled stone with engraved name
Gate structure   city tier (walled villages only); NBT gate piece
                 integrated with wall; see subsystem 12
```

**Guild emblems** — attached to the exterior face of guild halls. The
emblem matches the guild's charter (Blacksmith's Guild = crossed tongs,
Weaver's = loom shuttle, Adventurer's = crossed swords, Merchant's =
scales). Placed as a single NBT panel mounted on the front wall above
the entrance.

**Trade signs** — smaller NBT panels hung over the door of production
buildings, showing the profession. Boot over cobbler (cobbler profession
not yet in mod — placeholder), anvil over blacksmith, loom over weaver,
mortar-and-pestle over apothecary, wheat sheaf over miller, etc.

**Boundary stones** — cairns or carved stones placed at regular
intervals around the village perimeter. Mark the village's legal
territory even when unwalled. Functionally identical to patrol
waypoints but with visible physical representation.

### Noticeboard content integration

The existing notice board (LECTERN-based) currently has no content. Wire
it to display:

```
Recent village history entries     (from NPC Phase 4 doc 30)
Current market prices (top items)  (existing MarketPriceHelper)
Open commissions                   (existing CraftingOrder system)
Open job postings                  (existing WorkAssignment)
Published kingdom laws / decrees   (existing Kingdom laws, if active)
```

Player right-clicks the notice board → opens a screen with tabs for
each category. Purely read-only; acts as an information hub.

This is technically outside the decoration rework (it's a UI feature)
but ties to the notice board placed by this subsystem, so it's tracked
here.

### Placement

| Sign type       | Slot source                     | Emitter        |
|-----------------|----------------------------------|----------------|
| Welcome marker  | WELCOME_MARKER slot             | uniform emitter (one per inbound trade road endpoint) |
| Guild emblem    | GUILD_EMBLEM                    | subsystem 06 emits one per guild building |
| Trade sign      | TRADE_SIGN                      | subsystem 06 emits one per profession building |
| Boundary stone  | VILLAGE_BOUNDARY                | uniform emitter (perimeter ring, angular spacing) |
| Notice board    | existing town square sub-slot   | subsystem 04 TownSquareComposer |

Welcome markers and boundary stones place fine at the uniform emitter's
density. Guild emblems and trade signs are special-context — they need
building-specific placement rather than general ambient.

### Guild emblem set

Per guild type (from existing GuildData):

| Guild             | Emblem imagery                   |
|-------------------|----------------------------------|
| ADVENTURERS       | crossed swords over a shield     |
| MERCHANTS         | balanced scales                  |
| CRAFTSMEN         | hammer on anvil                  |
| FARMERS           | wheat sheaf                      |
| SCHOLARS          | open book                        |
| CLERGY            | radiant sun symbol               |

Emblems ship as NBT panel pieces per culture with fallback to default.

### Trade sign set

Per profession (partial list, expandable):

| Profession  | Trade sign imagery                  |
|-------------|-------------------------------------|
| BLACKSMITH  | anvil                               |
| BAKER       | round loaf                          |
| MILLER      | wheat sheaf                         |
| WEAVER      | loom shuttle                        |
| CANDLEMAKER | candle bundle                       |
| STONEMASON  | chisel on stone                     |
| APOTHECARY  | mortar and pestle                   |
| INNKEEPER   | tankard                             |
| MERCHANT    | coin pouch                          |
| CARPENTER   | crossed saws                        |

## Data structures

No persisted records specific to this subsystem. All signs are static
NBT placements.

Welcome markers register a `VillageEntrance` record on the `Village`:

```java
public record VillageEntrance(
    BlockPos markerPos,
    Direction facing,             // toward road
    UUID tradeRoadId              // which trade road this serves
) {}
```

Gives trade-route AI and future NPC greeter goals a registered target.

## Integration points

- **Trade Route (7b)**: welcome markers anchor at the outermost-building
  end of each trade road. Requires `TradeRoad.getVillageEndpoint` or
  equivalent — the emitter reads this to find the marker position.
- **GuildData**: emitter queries `VillageSavedData.getGuildForVillage`
  to find guild type for each guild hall building.
- **NoticeboardScreen**: new GUI screen (or extends existing UI
  framework). Draws data from multiple existing systems.
- **Village record**: stores list of VillageEntrance records.

## Behavior contract

### Does

- Place one welcome marker per inbound trade road.
- Place one guild emblem per guild hall, matched to the guild type.
- Place one trade sign per production building with a known profession.
- Place boundary stones at consistent intervals around the village.
- Wire the noticeboard to aggregate content from existing systems.

### Does not

- Display actual text on signs (Minecraft sign text limits + culture
  fonts not in scope). Visual imagery only.
- Animate or change imagery over time.
- Support player customization of signs.

## Edge cases

- **Village with no guild halls.** No emblems placed. Fine.
- **Production building without a clear profession.** TRADE_SIGN slot
  emission skips buildings whose profession resolves to NONE or CITIZEN.
- **Multiple trade roads converging on the same gate.** One welcome
  marker serves both; emitter deduplicates by road endpoint proximity
  (within 4 blocks).
- **Walled village.** Welcome marker becomes the gate piece from the
  wall kit (subsystem 12), not a separate marker.
- **Sign NBT missing for a culture.** Fallback to default. If default
  is also missing, that specific sign slot burns with a warning log.

## Ordering dependencies

- Requires subsystem 01 (DecorationFramework) for slot emission.
- Welcome markers require Trade Route 7b complete (trade road endpoints
  registered).
- Guild emblems require the NPC rework complete (GuildData populated).
- Noticeboard content requires NPC Phase 4 doc 30 (village history)
  for the history tab.

## Open decisions

- **Sign imagery authoring format.** Minecraft signs can't render
  arbitrary art. Proposed: use item frames with custom-textured items
  rendered from a small resource pack, OR use oak-plank-plus-blocks
  mosaic for the imagery. Decide based on visual test.
- **Boundary stone frequency.** Proposed: every 24 blocks around the
  perimeter. Adjust after first in-world test.
- **Noticeboard screen scope.** Proposed: 5 tabs (history, prices,
  commissions, jobs, laws). Implement as a single screen extending
  `BookScreenColors` pattern.

## Does-not-include

- Player-authored sign text. All signs are procedural.
- Directional signposts with village names for distant settlements.
  Deferred — possible future feature tied to cartography.
- Kingdom banners on guild halls. Deferred to kingdom-decoration work.
- Interactive emblems (click for faction info). The profile-based UIs
  handle that already.

## Revision notes

(Changes recorded here as the spec evolves.)
