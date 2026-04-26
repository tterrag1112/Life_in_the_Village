# 11 — Homesteading

## Purpose

Give small-village houses a visible, productive attachment that reads
as a working homestead — a chicken coop, a kitchen garden, a goat pen,
a bee yard. Combined with new family roles (Homesteader, Homemaker),
homesteads turn houses from dormitories into miniature businesses.

Homesteads are the bridge between industry adjuncts (subsystem 07)
and farm plots (subsystem 10): smaller than a farm, more productive
than a kitchen garden.

## Design

### Homestead AdjunctPlot types

Reuses the AdjunctPlot framework (subsystem 02), with four new
homestead-specific types:

```
HOMESTEAD_COOP     chicken coop + yard; produces eggs
HOMESTEAD_GARDEN   vegetable patch + composter; produces produce
HOMESTEAD_PEN      goat or sheep pen; produces milk / wool
HOMESTEAD_BEES     beehive cluster; produces honeycomb
```

Also a shared WOODSHED attachment (non-productive utility — reduces
household firewood cost).

### House → Homestead assignment

Rules:

- HAMLET / VILLAGE tier houses: 80% chance of one homestead, 20%
  chance of two
- TOWN tier houses: 30% chance of one small homestead (usually
  HOMESTEAD_GARDEN)
- CITY tier houses: 5% chance of HOMESTEAD_GARDEN only (urban kitchen
  gardens); no livestock in cities
- NOBLE_MANOR: its attached FORMAL_GARDEN (subsystem 08) plus a
  HOMESTEAD_PEN if space allows, treated as "estate grounds"

Homestead type selection is random within the tier's allowed pool,
culture-weighted: nordic favors PEN (sheep), imperial favors GARDEN,
highland favors COOP + PEN, default mixed.

### Contents per type

**HOMESTEAD_COOP (4×4):**
- Small coop NBT (wooden, with roof)
- Fenced yard around it
- 3–5 chickens persisting
- Feed trough

**HOMESTEAD_GARDEN (4×4):**
- 3×3 farmland inner area planted with mixed vegetables (carrot,
  potato, beetroot, wheat — random subset)
- Composter at corner
- Small shed or tool rack
- Fence border

**HOMESTEAD_PEN (6×6):**
- Fenced pasture yard
- Small shelter NBT (3-wall lean-to)
- 2–4 sheep or goats persisting (goats approximated by sheep with
  custom NBT data if goats not in mod)
- Water trough
- Hay bale

**HOMESTEAD_BEES (3×3):**
- Bee hive cluster (vanilla beehive blocks, 2–4)
- Flower patch around the hives
- Small wooden stand with honeycomb display

### Family role additions

Two new values in `FamilyRole`:

```java
public enum FamilyRole {
    HEAD,       // existing
    SPOUSE,     // existing
    CHILD,      // existing
    ELDERLY,    // existing
    UNASSIGNED, // existing
    HOMESTEADER,// NEW — spouse (or adult child) running the homestead
    HOMEMAKER   // NEW — spouse managing household in towns/cities
}
```

Selection logic at spawn time:

- HAMLET/VILLAGE house with homestead → spouse is HOMESTEADER
- HAMLET/VILLAGE house without homestead → spouse is SPOUSE (default)
- TOWN/CITY house → spouse is HOMEMAKER
- NOBLE_MANOR → spouse is HOMEMAKER (lady/lord of house)
- Adult children may be assigned HOMESTEADER if the primary spouse
  has a profession

### Homesteader goal

A new goal `HomesteadTendGoal` registered for HOMESTEADER role NPCs.
Runs during work phase:

- Walk to assigned homestead adjunct plot
- Perform tending action based on homestead type:
  - COOP: feed chickens, collect eggs → household inventory
  - GARDEN: till farmland, harvest mature crops, replant
  - PEN: feed animals, shear wool (sheep), milk (goats)
  - BEES: collect honeycomb periodically
- Return to house
- Optional: walk to market, post listings, or directly sell via
  DirectBusinessChannel (see NPC Phase 3 integration below)

### Homemaker goal

A new goal `HomemakerTaskGoal` for HOMEMAKER role NPCs. Runs during
work phase:

- Walk around the house, picking up / rearranging items
- Visit market during market day to buy household goods
- Socialize at the town square during some work hours (a richer
  social layer than other professions)
- Care for young children (path to them, stand nearby — uses NPC
  Phase 2 child arc logic)

Both goals are intentionally light — they're visual presence, not
full simulation. The productive output of HOMESTEADER is concrete
(item deposits to household inventory); HOMEMAKER produces no items.

### Integration with NPC Phase 3 economic channels

Each homestead opens a `DirectBusinessChannel` at its adjunct plot
location once the NPC Phase 3 channel system is in place. Neighbors
and passing NPCs can buy eggs, milk, vegetables, honeycomb directly
at the homestead without visiting the market.

The channel has low volume — homesteaders are hobby producers, not
shops. Daily item cap per homestead: 4 eggs, 2 wool, 1 honeycomb,
6 crops.

### Integration with NPC Phase 4 resource categories

Each homestead type contributes to `VillageSimData` via a
`BuildingResourceProfile` extension that accounts for the homestead's
production:

```
HOMESTEAD_COOP    → +LIVESTOCK (low), +FOOD (low)
HOMESTEAD_GARDEN  → +FOOD (low)
HOMESTEAD_PEN     → +LIVESTOCK (medium), +CLOTH (low, from wool)
HOMESTEAD_BEES    → +LUXURY (low), +FOOD (low)
```

Village with many homesteads appears meaningfully productive in the
sim data even without large farm territories.

### Household treasury integration

Homesteader sales route to the household's treasury (existing
`HouseholdData` record extended with a shared coin pool — already
partially present via `HouseholdWealthManager`). Cost-of-living (fed
in by the NPC Phase plan) is drawn from the treasury.

Excess treasury balance allows household upgrades (buying a larger
homestead plot if space exists, upgrading children to apprenticeships,
buying into a local company). This is a ripe integration point with
NPC Phase 4 NPC-owned companies (doc 26).

## Data structures

No new records beyond `AdjunctPlot`. Extensions:

```java
// FamilyRole gains two values (above).

// HouseholdData gains:
public record HouseholdData(
    ... existing fields ...,
    List<UUID> homesteadPlotIds,    // references to AdjunctPlots
    long sharedTreasuryBronze
) {}
```

Homestead plots are persisted as AdjunctPlots with homestead-specific
types. The `AdjunctPlot.type` field discriminates.

## Integration points

- **AdjunctPlotFramework (subsystem 02)**: all placement.
- **NPC Phase 3 economic channels**: DirectBusinessChannel per
  homestead.
- **NPC Phase 4 resource categories**: production contribution.
- **HouseholdManager**: spouse role assignment at spawn time.
- **HomesteadTendGoal / HomemakerTaskGoal**: new NPC goals registered
  via ProfessionGoalFactory for the new roles.
- **TownspersonMob**: adds `assignedHomesteadPlotId` field (nullable).

## Behavior contract

### Does

- Attach production-capable homesteads to small-village houses.
- Assign Homesteader role to spouses in homestead households.
- Assign Homemaker role to spouses in town/city households.
- Route homestead output to household treasury via NPC Phase 3.
- Contribute to village sim data via NPC Phase 4.

### Does not

- Scale homesteads over time. Fixed at house placement.
- Allow player configuration of homestead type.
- Produce resources while chunks are unloaded (uses the existing
  advance-sim model for unloaded villages).
- Spawn new animals beyond the initial count. If animals die, they
  don't respawn; the homestead's output reduces accordingly.

## Edge cases

- **House with no free face for any homestead.** None attached. Role
  assigns as plain SPOUSE instead of HOMESTEADER.
- **Homesteader profession elected before role assignment.** HOMESTEADER
  takes precedence; any profession is cleared (homesteaders can't hold
  a second profession).
- **Homestead parent building (HOUSE) demolished.** Homestead plot
  orphaned → cleanup removes both.
- **Livestock escapes the pen.** Existing vanilla pathing. No guarantee.
  Shepherding behavior out of scope.
- **Village switches tier after generation (rare — only happens via
  expansion).** Existing homesteads don't relocate. New houses follow
  the current-tier rules.

## Ordering dependencies

- Requires subsystem 02 (AdjunctPlotFramework) for placement.
- Requires NPC Phase 3 economic channels for sales routing.
- Requires NPC Phase 4 resource categories for sim contribution.
- Family role additions are non-breaking enum extensions.

## Open decisions

- **Homestead production rates.** Proposed per-day caps: 4 eggs, 2
  wool, 1 honeycomb, 6 crops, 1 milk (milk not yet in mod — treat
  as placeholder). Tune after testing.
- **Treasury pooling.** Homesteader sales go to household treasury;
  head's profession income also pools in household treasury; spouses
  draw from household treasury for personal needs. Confirm this
  pooling model with the wealth system.
- **Daughters vs. sons as child homesteaders.** Proposed: gender-
  neutral, any adult child can be HOMESTEADER if parents aren't.
- **Noble manor homestead.** Proposed: manor gets HOMESTEAD_PEN (the
  noble keeps fine horses or hunting hounds). Differentiates from
  common homesteads.

## Does-not-include

- Homestead upgrade/downgrade UI.
- Player-created homesteads.
- Winter feeding / animal starvation mechanics.
- Slaughter / animal lifecycle — livestock is persistent until
  natural death or external cause.

## Revision notes

(Changes recorded here as the spec evolves.)
