# 14 — Cemeteries

## Purpose

Give villages a physical memory of their dead. As NPCs die of old age,
accident, crime, or war, graves accumulate in a dedicated cemetery
plot. Each grave is named. The player reading headstones encounters
the village's history as specific named individuals — a grandmother
who ran the bakery, a guard who fell defending the village, a child
who died in a harsh winter.

Cemeteries complete the time-passing loop started by NPC Phase 2
(child/elderly arcs) and NPC Phase 4 (village history). This subsystem
is the physical layer for both.

## Design

### Cemetery placement

A village gets exactly one cemetery plot, placed according to the
following priority:

1. **Attached to the Temple** — if a temple exists, a 6×8 plot on the
   temple's back or side face (AdjunctPlot-like, but with its own
   type).
2. **Village edge** — if no temple, a 6×8 plot on the outermost residential
   ring of the village, oriented to face inward (a quiet corner of the
   settlement rather than a roadside).
3. **Deferred** — tiny hamlets (under 5 buildings) may not have a
   cemetery plot at all until the village expands.

Cemetery placement happens during village realisation alongside other
adjunct-style features but registered as its own record type (because
it's not quite an AdjunctPlot — it grows over time and tracks named
graves).

### Grave placement within the cemetery

The cemetery plot has a fixed grid of grave positions (typically 3×6
slots, growing with village tier). Graves are assigned sequentially as
deaths occur. Older graves sit toward the back of the cemetery; fresh
graves at the front.

Each grave is a `Grave` record with:
- Position within the cemetery
- Headstone type (culture + style)
- Deceased NPC's identity (name, profession, death cause, death year)
- Epitaph text (procedurally generated, see below)

When a village has been around long enough that all grid slots are
full, the oldest graves are "decommissioned" — the headstone is
replaced with a sunken or moss-covered variant, and the slot is reused
for a new grave. This keeps cemetery size bounded while still
displaying the oldest lore via decommissioned markers.

### Headstone NBTs

Per culture:

| Culture   | Fresh grave                     | Old grave                      |
|-----------|----------------------------------|--------------------------------|
| default   | upright chiseled stone slab      | moss-covered slab, tilted      |
| nordic    | rune stone                       | fallen rune stone, overgrown   |
| highland  | cairn mound                      | scattered cairn, grass-covered |
| imperial  | carved tomb plaque + urn         | weathered tomb + cracked urn   |

Variants authored per culture. Fallback via CultureResolver.

Grave positions include a sign block adjacent to the headstone that
displays the deceased's name and short epitaph (readable in-world).

### Epitaph generation

Epitaphs are generated at death time from a template system keyed off
NPC attributes:

```
"Here lies [Name], [profession] of [village], [life-stage descriptor].
 [Cause-specific line]. [Relationship line if applicable]."
```

Example generations:

- *"Here lies Edda Grenholm, weaver of Kalsmere, mother of three. Passed
  peacefully in her seventieth winter. Remembered by her loom."*
- *"Here lies Torvik Aalson, guard of Rosefield. Fell defending the
  western gate. His watch is ended."*
- *"Here lies Brigid Fane, apprentice baker, aged twelve. Taken by the
  black cough in the winter of the deep snows. Her mother tends her
  flowers yet."*

Templates live in a culture-keyed JSON resource. Each death event
passes NPC context to the generator, which fills in slots. Output is
written to the grave's Sign block text.

### Grave placement lifecycle

**On NPC death** (driven by NPC Phase 2 doc 15):
1. Death event fires with NPC reference + cause.
2. Cemetery finds next available grid slot (or decommissions oldest).
3. Headstone NBT stamps at slot.
4. Epitaph generator produces text.
5. Sign block placed and written.
6. Grave record persisted on Village.
7. Village history entry added: `NOTABLE_DEATH` or `COMMON_DEATH`.

**On village history event** (driven by NPC Phase 4 doc 30):
A battle or plague that kills multiple NPCs at once produces a batch
of graves, possibly with a shared "mass grave" marker if the cemetery
runs out of slots.

### Village-year tracking

To produce epitaphs with "winter of the deep snows" or "year of the
great harvest," cemeteries read from the village's yearly history
records (NPC Phase 4 doc 30). Each year has a short descriptor;
epitaphs pick the descriptor matching the death year.

This is a small but meaningful integration — it turns the cemetery
into the most concentrated source of village history the player can
browse.

## Data structures

```java
public record Cemetery(
    UUID cemeteryId,
    UUID villageId,
    BlockPos origin,
    int gridSlotsX,              // 3 typically
    int gridSlotsZ,              // 6 typically, scales with tier
    List<UUID> graveIds,
    CemeteryAttachment attachment // how it relates to the village
) {}

public enum CemeteryAttachment {
    TEMPLE_ADJACENT,
    VILLAGE_EDGE,
    MASS_GRAVE_ONLY        // post-catastrophe degenerate state
}

public record Grave(
    UUID graveId,
    UUID cemeteryId,
    int slotIndex,                // 0 = oldest grave
    GravePosition gridPos,        // within the cemetery
    UUID deceasedNpcId,           // may point to a purged NPC record
    String deceasedName,          // cached copy, in case NPC is purged
    String deceasedProfession,    // cached copy
    String deathCause,
    long deathTick,
    String epitaph,
    boolean decommissioned        // true if moss-covered / old state
) {}

public record GravePosition(int x, int z) {}
```

Cemeteries persist on VillageSavedData. Graves persist as children.

## Integration points

- **NPC Phase 2 doc 15 (child/elderly arcs)**: source of death events
  via old age.
- **NPC Phase 3 doc 19 (crime/justice)**: source of death events via
  execution or murder victim.
- **NPC Phase 4 doc 30 (village history)**: supplies yearly descriptors
  for epitaph generation; receives `NOTABLE_DEATH` entries.
- **Event system**: `FUNERAL` event (NPC Phase 5 doc 32) stamps
  funeral procession decoration (subsystem 13) ending at the cemetery.
- **Temple building**: cemetery attaches to it if present.
- **AdjunctPlot framework**: informal reuse of placement probe pattern,
  not a strict AdjunctPlot (because cemeteries grow).

## Behavior contract

### Does

- Place exactly one cemetery per village at realisation.
- Place a grave when any NPC assigned to that village dies.
- Generate procedural epitaphs using culture templates.
- Decommission old graves when the grid fills up.

### Does not

- Place graves for NPCs that haven't died (duh).
- Resurrect NPCs or otherwise interact with death as a reversible state.
- Handle player death or graves for player characters.
- Migrate graves if the cemetery is destroyed or moved.

## Edge cases

- **Temple doesn't exist at realisation but gets built later (village
  expansion).** Cemetery stays at village edge; does not relocate.
- **NPC dies in a village they don't belong to.** Grave placed in the
  home village's cemetery, not the death location. If the NPC has no
  home village, no grave is placed (rare: wandering traders with no
  assignment).
- **Village with no cemetery (tiny hamlet).** Deaths are recorded to
  village history but no physical grave. If the village expands
  past 5 buildings, a cemetery plot is allocated and existing history-
  recorded deaths are retroactively populated in the grid.
- **Cemetery cycle reached (grid full, all slots decommissioned).**
  Oldest decommissioned grave is removed entirely and reused.
  Village history retains the death record.
- **Player destroys a headstone.** The grave record persists but the
  block is not auto-restored (preserve player action). Re-stamping
  could be offered via `/liv cemetery rebuild <villageId>`.
- **Mass death event (war, plague) overflows grid.** Excess deaths
  accumulate as a single "mass grave" marker with a plaque listing
  the dead rather than individual headstones.

## Ordering dependencies

- Requires NPC Phase 2 doc 15 complete (death events fire).
- Requires NPC Phase 4 doc 30 complete (village history + year
  descriptors).
- Runs during village realisation (for initial placement).
- Grave placement is runtime-reactive (on death events).

## Open decisions

- **Grid size by tier.** Proposed: 3×4 HAMLET, 3×6 VILLAGE, 4×8 TOWN,
  5×10 CITY. Small enough to fill in a decade of play at typical
  death rates.
- **Decommission threshold.** Proposed: when grid reaches 80% full,
  start decommissioning oldest graves. When grid reaches 100% full,
  remove oldest decommissioned entry and reuse slot.
- **Epitaph template count per culture.** Proposed: 12 templates per
  culture as a minimum viable pool. Variation via slot-filling.
- **Notable-NPC differentiation.** Proposed: founders, village leaders,
  and kingdom rulers get a dedicated "monument" within the cemetery —
  larger NBT than a headstone. One monument slot per cemetery.
- **Readability.** Minecraft signs have 4-line text limits. Epitaphs
  must fit. Proposed: max 80 characters per epitaph, truncate if
  longer.

## Does-not-include

- Player-placed graves.
- Funeral rites (gameplay) — ceremony is an event (NPC Phase 5).
- Mourning animations / mood on attending NPCs — NPC Phase 1 mood
  handles this when event fires.
- Grave robbing or loot in graves.
- Ghosts or undead spawning — purely symbolic / narrative.

## Revision notes

(Changes recorded here as the spec evolves.)
