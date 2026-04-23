# 12 — Gossip & Rumor

## Purpose

The knowledge system (`03-knowledge-system.md`) stores what NPCs know.
Gossip is how knowledge propagates through a village — NPCs chatting
during SOCIAL phase, conversations at the well, shared drinks at the
inn. Rumors mutate as they pass: fidelity degrades, details distort,
names get swapped.

Gossip is the primary way asymmetric information becomes interesting:
the blacksmith's apprentice tells the baker who tells the innkeeper who
tells the player — each layer colored by the teller's traits and
relationship to the subject. The player hears a rumor; maybe it's true,
maybe garbled, maybe a fabrication by a grudged rival.

Gossip is also a player-facing discovery mechanism. The "listen in"
action near an ongoing chat reveals what NPCs are saying about each
other and about recent events.

## Data model

### GossipChannel

A conversation instance where knowledge exchange can happen:

```java
public record GossipChannel(
    UUID speakerId,
    UUID listenerId,
    long startedTick,
    GossipContext context
) {}

public enum GossipContext {
    CASUAL_MEETING,      // passed on the road, brief exchange
    AT_WELL,             // fetching water together
    AT_INN,              // drinks and stories
    AT_WORKPLACE,        // quiet moment during work
    AT_MARKET,           // while shopping
    AT_FESTIVAL,         // during event
    AFTER_RITE;          // following a religious/social ceremony
}
```

Context affects exchange volume: `AT_INN` exchanges 2–4 knowledge
entries; `CASUAL_MEETING` exchanges at most 1.

### RumorSeed

When gossip creates a rumor-source (rather than passing existing
knowledge), a `RumorSeed` defines the initial content:

```java
public record RumorSeed(
    String topic,
    String initialContent,
    KnowledgeCategory category,
    UUID originId,               // the NPC who first told (or UUID.NIL if unknown)
    long seedTick,
    float startFidelity          // usually 0.7-0.9
) {}
```

Rumor seeds are typically generated from NPC memories — a recent
significant event becomes a rumor the witness can share.

## Gossip execution

### Triggering a gossip channel

During SOCIAL phase, NPCs within 4 blocks of each other have a chance
per 20-tick bucket to start a `GossipChannel`:

```java
// Per 20-tick check:
baseProb = 0.10;
modifier = 1.0;
if relationship(A, B) >= FRIEND: modifier *= 2.0;
if relationship(A, B) <= GRUDGE: modifier *= 0.0;  // no gossip with feuded
if A.sociability > 0: modifier *= (1.0 + A.sociability * 0.5);
// ... similar for B
chance = baseProb * modifier;
if (rng < chance) startGossip(A, B, contextByLocation());
```

Gossip also fires at scheduled social events (festivals, markets) and
when NPCs share a meal at the inn.

### Exchange protocol

```
For each knowledge exchange (1-4 depending on context):
  1. Speaker picks a topic to share:
       - 30% chance: a "hot" topic (knowledge acquired in last 7 days)
       - 30% chance: a topic relevant to the listener (about listener's
         family, workplace, village)
       - 20% chance: a topic about a shared acquaintance
       - 20% chance: random from ledger
  2. Speaker's copy becomes source; fidelity for listener =
     max(0.1, source.fidelity - 0.20).
  3. If speaker has a grudge against the topic's subject, speaker
     adds negative-slant content mutation (see below).
  4. If speaker has low Honesty and fidelity < 0.5, speaker may
     embellish/fabricate (add exaggerated detail, raise own fidelity
     in their telling to 0.7 even though ground truth is lower).
  5. Listener adds entry via NpcKnowledgeLedger.add (upgrade if their
     existing entry had lower fidelity).
  6. If the topic is about the listener themselves, fire mood trigger
     RUMOR_POSITIVE_ABOUT_SELF or RUMOR_NEGATIVE_ABOUT_SELF based on
     content polarity.
```

Speaker chooses topics only from entries with fidelity ≥ 0.3 — they
don't repeat things they know nothing about.

### Content mutation

Knowledge entries have a `content` string. Mutations happen
deterministically given `(topic, acquiredTick, mutatorUuid)`:

- Number swap: "four bandits" → "six bandits" (±50%, rounded).
- Name swap (low fidelity only): "Alric" → "Aldric" or a random
  plausible name from same culture.
- Intensity swap: "serious injury" → "minor wound" or vice-versa
  depending on teller's disposition.
- Subject swap (rare, very low fidelity): "the blacksmith" → "a
  blacksmith" (generalizes).
- Addition (for low-Honesty tellers): appends a plausible detail
  that isn't in the source.

Implementation: `RumorMutator.mutate(content, fidelity, teller)`
returns the new content string. Seeded RNG from
`(content.hashCode() + teller.UUID)` ensures the same chain produces
the same mutation across save/load.

### Slanted retelling

When a speaker has a negative relationship with the topic's subject
(GRUDGE or worse), the content slant shifts negative:

- "The baker is saving for his daughter's wedding" becomes
- "The baker is hoarding coins while his daughter goes unwed"

When relationship is positive (FRIEND+):

- "The blacksmith shut his shop early yesterday" becomes
- "The poor blacksmith was too exhausted — he works so hard"

Slanting is a template-style transform; Phase 5 content pass writes
the transform library. Phase 2 ships with 10-15 basic slant patterns.

## Rumor sources

### Generated from memories

Once per day, each NPC has a small chance (proportional to Sociability
+ 1) to generate a new RumorSeed from a recent high-value memory. The
seed becomes a knowledge entry in their own ledger that they can then
gossip about.

Types of memories that become rumors:
- Witnessed crime (high-value)
- Witnessed death
- Shared hardship (becomes a recounting of the event)
- Major goal completed by someone they know
- Marriage / birth / death in village

Memories involving the *player* become rumors. Other NPCs discuss the
player's actions, positively or negatively. This is the "reputation
spreads" mechanic you wanted.

### Generated from events

Some world events seed rumors directly (no memory required):

- Festival ends → seed "The X festival went well/poorly this year"
- Caravan arrival → seed "Traders from Y arrived with Z goods"
- Building burns / is built → seed about the building
- Office change (new leader, guildmaster) → seed political rumor

These originate from an "ambient" source and propagate via the normal
gossip protocol.

### Fabricated rumors

Low-Honesty NPCs may create knowledge entries from nothing. Trigger:
when asked about a topic they don't know, with Honesty < -0.4 and
Sociability > +0.4 (see `03-knowledge-system.md` don't-know response).
Fabricated entries have `source = FABRICATED` and `fidelity = 0.3`,
but the creator treats them as reliable. When gossiped, they degrade
further.

A rumor network can thus contain false knowledge that spreads and
slowly fades — a key realism feature.

## Player gossip access

### Listening in

A new player action (verb) "listen in" during an ongoing gossip
channel:

- Available when player is within 6 blocks of an active
  `GossipChannel`.
- Reveals the latest exchange: "You overhear [speaker] telling
  [listener]: '[content]'"
- No relationship effect, but NPCs notice being overheard (if player
  is visible and within 3 blocks) and speaker's next exchange may
  switch to a more guarded topic.

### Asking about rumors directly

The `ask_about` verb (Phase 1) already queries the NPC's knowledge
ledger. Gossip-sourced entries respond differently than direct-
witness entries:

- Direct witness: "I saw it myself — ..."
- First-hand rumor: "I heard from [source] that..."
- Higher-chain rumor: "Word is that..." or "Some say..."

Dialogue trees recognize the knowledge source type and pick appropriate
intro phrasing.

### Reporting rumors

Player can make a fresh rumor by telling an NPC something. New verb
"Tell a rumor" (available after a relationship milestone):

- Player picks topic from a list (or types free-form — v1 uses a
  menu of player-known facts).
- NPC adds a knowledge entry with source `RUMOR_HEARD` from player,
  fidelity 0.7 (player is a witness).
- NPC may then gossip this forward, introducing player-authored
  rumors into the network.

Caveat: false player-rumors can be tracked back. If the rumor is
contradicted by another NPC's witness knowledge of the same topic,
the listener's trust in the player drops slightly (memory entry
`INSULTED_BY` or a new `LIED_TO_BY` type).

## Persistence

Gossip channels are transient — not persisted. They live only during
the in-progress conversation.

Rumor knowledge entries persist via the existing knowledge ledger
(`03-knowledge-system.md`). No new persistence surface.

A per-village `GossipTopicHeat` tracker records which topics are
currently circulating:

```java
public class GossipTopicHeat {
    private final Map<String, Float> topicHeat;  // topic -> heat 0..1
    // Increments when a topic is exchanged; decays slowly.
}
```

Attached to `Village` in `VillageSavedData`. Used by dialogue to
surface hot topics ("everyone's talking about...") without querying
every NPC's ledger.

## Integration points

### Phase 2 integration

- `GossipScheduler` runs during daily tick: identifies gossip
  opportunities, rolls chances, invokes `GossipExchange.run`.
- `GossipExchange` implements the exchange protocol.
- `RumorMutator` implements content mutation.
- `RumorSeeder` hooks into daily tick and world events to generate
  new rumor seeds.
- `VillageSavedData` gains `Map<UUID, GossipTopicHeat>` keyed by
  village.
- Knowledge ledger's `add` method gains awareness of higher-fidelity
  upgrades (replacing existing lower-fidelity entry for same topic).
- Dialogue predicates extended: `IsTopicHot`, `HasHeardRumor`.
- New dialogue trees: `gossip.tell`, `gossip.listener_response`.
- Player verb: "Listen in" (conditional on nearby channel).
- Player verb: "Tell a rumor" (gated on relationship ≥ +20).
- `/gossip` debug commands:
  - `/gossip list <village>` — current hot topics
  - `/gossip trace <topic>` — show fidelity chain
  - `/gossip seed <topic> <npc>` — force-seed for testing

### Phase 3+ integration

- Crime gossip: witnessed crimes auto-seed rumors that can lead to
  constable investigation.
- Political gossip: campaign periods before elections amplify
  political topic heat.

## Behavior contract

### Does

- Create transient gossip channels between nearby NPCs during
  SOCIAL phase.
- Exchange knowledge entries with fidelity degradation.
- Mutate content deterministically based on teller traits and
  relationship to subject.
- Slant content negative for grudged subjects, positive for friends.
- Seed rumors from memories and world events.
- Track per-village topic heat for dialogue queries.
- Enable player listening-in and rumor-telling.

### Does not

- Persist gossip channels across save/load.
- Guarantee rumor reaches the entire village — propagation is random.
- Distinguish "public knowledge" from "secret" beyond fidelity.
  Rumors can include anything from the teller's ledger.
- Suppress rumors about a topic the listener already knows — the
  existing higher-fidelity entry wins via knowledge-ledger upgrade
  rule.
- Model rumor fatigue (old rumors losing interest). Topic heat decay
  approximates this.

## Edge cases

- **NPC has empty knowledge ledger.** Skip gossip — nothing to share.
- **Listener already has same topic at higher fidelity.** Exchange
  skipped for this topic; try another.
- **Channel interrupted by conflict/event.** Clean up channel; no
  partial state.
- **Cycle detection.** If A→B→C→A all share the same topic, fidelity
  floor at 0.1 prevents infinite degradation. Acceptable; nothing
  extra needed.
- **Player listens in from 5 blocks away through a wall.** Line of
  sight not required in v1 — audio-esque detection. Realism pass in
  Phase 5 if needed.
- **Mutator produces nonsensical output.** Mutation is bounded;
  names come from registry, numbers clamped. Content can't become
  structurally invalid.

## Ordering dependencies

Phase 2 depends on:
- Knowledge ledger (Phase 0 storage) — exists.
- NPC relationship ledger (Phase 2, same phase) — must be
  implemented first for gossip bias.
- Dialogue tree (Phase 1) — for tell/hear dialogue.
- Mood system — for `RUMOR_*_ABOUT_SELF` triggers.
- Existing `SocialWalkGoal` and `EatMealGoal` for channel trigger
  points.

## Open decisions

- Player-authored rumor free-form text vs menu? **Proposed: menu in
  v1 (lists player-known facts from memory/knowledge); free-form
  would require content parsing and is scope-creep.**
- How aggressively should negative rumors hurt reputation? If an NPC
  hears 5 negative rumors about the player, do they refuse trade?
  **Proposed: accumulate into standard reputation score via existing
  reputation hooks; 5 negative rumors ≈ -15 reputation. Handled by
  reputation system, not specially.**
- Should rumors have a notion of "secrets that should not spread"?
  A high-Honesty NPC given a secret may refuse to gossip it. **Proposed:
  yes — `KnowledgeEntry` gains an optional `sensitive` flag; tellers
  with Honesty > +0.4 skip sensitive entries.**

## Does-not-include

- Cross-kingdom rumor networks (limited to village for v1;
  caravans can carry rumors via `Caravan` travel in Phase 4).
- Rumor verification minigame. Truth vs. fabrication detection is
  not a player-facing puzzle; it's narrative flavor.
- Written-rumor preservation (printed tabloids, proclamations).
  Letter system in `18-letters-and-books.md` handles written info.
- Weighted influence of high-Sociability NPCs propagating rumors
  faster specifically — already emerges from per-NPC exchange
  probability weighting.

## Revision Notes

(changes recorded here as the spec evolves after testing)
