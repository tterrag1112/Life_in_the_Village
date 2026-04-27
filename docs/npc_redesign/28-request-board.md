# 28 — Request Board

## Purpose

When a village can't meet a demand locally (needs swords, but no
blacksmith; needs food, but farmers are out), guilds post that need to
a higher-scope request board. Other villages' guilds poll the board
for opportunities to fulfill — mutual benefit: the originator gets
what they need, the fulfiller earns coin.

This is the system that turns the earlier `EconomicChannel` abstraction
into real inter-village commerce, and gives the guild structure
(Phase 4) a concrete purpose for existing.

## Data model

### Request

Generalized from the existing `Quest` record:

```java
public record Request(
    UUID requestId,
    RequestType type,
    UUID originGuildId,
    UUID originVillageId,
    @Nullable UUID originatorNpcId,      // who asked
    Item targetItem,                      // for GATHER / CRAFT / DELIVER
    int targetCount,
    String targetMobId,                   // for HUNT
    String targetBiome,                   // for SURVEY
    @Nullable BlockPos targetLocation,    // for specific-location requests
    long bronzeReward,
    long postedTick,
    long deadlineTick,
    RequestStatus status,
    UUID acceptingGuildId,                // null until accepted
    UUID fulfillerNpcId,                  // individual in the accepting guild
    long acceptedTick,
    int currentProgress,
    int totalProgress,
    RequestScope scope
) {
    public static final Codec<Request> CODEC;
}

public enum RequestType {
    GATHER,       // raw materials (herbs, ore, wheat)
    CRAFT,        // finished goods (swords, tools, furniture)
    DELIVER,      // move items from A to B
    HUNT,         // kill specific mob type
    SURVEY,       // explore specific biome/location
    ESCORT;       // travel protection
}

public enum RequestStatus {
    OPEN,           // on the board, unaccepted
    ACCEPTED,       // claimed by a fulfilling guild
    IN_PROGRESS,    // fulfillment active
    FULFILLED,      // complete; payment owed
    FAILED,         // deadline passed or fulfiller aborted
    EXPIRED;        // deadline passed without acceptance
}

public enum RequestScope {
    VILLAGE_INTERNAL,    // only members of origin guild eligible
    VILLAGE_PUBLIC,      // any guild in same village
    KINGDOM_WIDE,        // any guild in same kingdom
    GLOBAL;              // any guild anywhere
}
```

### RequestBoard

```java
public class RequestBoard {
    private final Map<UUID, Request> requests;
    private final Map<UUID, List<UUID>> byOriginGuild;
    private final Map<UUID, List<UUID>> byAcceptingGuild;
    private final Map<RequestScope, List<UUID>> byScope;

    public void post(Request req);
    public Optional<Request> get(UUID requestId);
    public List<Request> availableFor(AbstractGuild guild, long tick);  // filtered by scope
    public void accept(UUID requestId, UUID guildId, UUID fulfillerId, long tick);
    public void updateProgress(UUID requestId, int progress);
    public void fulfill(UUID requestId, long tick);
    public void fail(UUID requestId, String reason);

    // Periodic cleanup
    public void expireStale(long tick);

    // Codec, save / load
}
```

Kingdom-scoped request board stored on `Kingdom`; village-scope on
`Village`; global on a singleton `GlobalRequestBoard` saved data.

## Posting

### Guild posts a request

Initiated by:

1. `GuildRequestChannel` (from `23-economic-channels.md`) — when an
   economic intent can't be fulfilled locally, the channel posts a
   Request on behalf of the guild.
2. Guild office actions — village leader, guild master, treasurer can
   directly post requests via office UI.
3. Player action — a player holding a guild office can post.

Posting cost: bronze from guild treasury = bounty × 0.10
(platform fee held in escrow until fulfillment).

Scope decision:
- Village leader's law-related request: VILLAGE_INTERNAL (if
  constable needs equipment, ask local smith first).
- Normal economic shortfall: VILLAGE_PUBLIC first, KINGDOM_WIDE on
  escalation (after 7 days unfulfilled).
- Rare or expensive items: KINGDOM_WIDE or GLOBAL from the start.

### Acceptance

Guild master (or delegated officer) periodically polls the board:

- Filter by scope eligibility.
- Filter by guild capability — do we have members who can produce
  the target?
- Filter by deadline feasibility — can we meet it?
- Score by reward-to-effort ratio.
- Accept best match.

Acceptance assigns a fulfiller (specific NPC in the accepting guild
with the right skills). The fulfiller gets a job posting-style task
in their work queue.

## Fulfillment

### Production-based

For GATHER / CRAFT requests:

1. Fulfiller NPC produces the target items at their workshop.
2. Progress tracked per-item via existing production cycle.
3. When full quantity ready, caravan dispatch begins.
4. Caravan delivers items to origin guild's guild hall or market.
5. Guild verifies: acceptance event fires, bronze released.
6. Fulfiller paid their share (typically 70% of reward).
7. Accepting guild treasury keeps the rest (20%); village/kingdom
   takes small fee (10%).

### Travel-based

For HUNT / SURVEY / ESCORT:

- Fulfiller travels to target location.
- Existing caravan/travel infrastructure handles movement.
- Completion event fires on return with proof (mob kills tracked,
  biome surveys logged, escort delivery confirmed).
- Payment released similarly.

### Payment

Bronze flows:
- Original guild's treasury → escrow at posting.
- On fulfillment: escrow → accepting guild (80%) + platform fee
  retained.
- Accepting guild → fulfiller NPC wallet (70% of received).
- Remaining kept in accepting guild treasury.

### Failure

If fulfiller fails (death, quit, deadline miss):
- Accepting guild refunded escrow × 0.5 (penalty for non-delivery).
- Originating guild receives remainder back.
- Reputation hit for accepting guild.
- If repeated failure: guild loses credibility, fewer requests
  accepted at their scope in future.

## Scope escalation

Requests start at a scope and escalate if unfulfilled:

- VILLAGE_INTERNAL → VILLAGE_PUBLIC after 3 days.
- VILLAGE_PUBLIC → KINGDOM_WIDE after 7 days.
- KINGDOM_WIDE → GLOBAL after 14 days (for highest-urgency).

Escalation optional — originator can freeze scope if they want local-
only fulfillment (some crafts traditionally don't leave the village).

## Caravan integration

Fulfillment often requires caravan delivery. Integration:

- Accepting guild dispatches a caravan via existing
  `CaravanSavedData` system.
- Goods loaded from guild warehouse (or directly from fulfiller's
  workshop).
- Caravan travels to origin village's market or guild hall.
- On arrival, request fulfillment event fires.

Long-distance trade companies (`26-npc-companies.md`) are natural
candidates for accepting KINGDOM_WIDE and GLOBAL requests.

## GuildRequestChannel (deferred from Phase 3)

Now fully implemented:

```java
public class GuildRequestChannel implements EconomicChannel {
    public ChannelType type() { return CHANNEL_GUILD_REQUEST; }
    public int basePriority() { return 40; }

    public Optional<ChannelQuote> quote(TradeIntent intent, ...) {
        if (intent.urgency() != IMMEDIATE) return Optional.empty();
        // Only offers a quote for time-insensitive needs

        // Estimate cost: expected fulfillment time, request bounty markup
        long estimatedPrice = intent.maxPrice() + 10;
        int travelTimeTicks = 7 * 24000;  // deferred by ~week

        return Optional.of(new ChannelQuote(
            CHANNEL_GUILD_REQUEST, intent, estimatedPrice,
            intent.quantity(), travelTimeTicks,
            intent.tick() + 24000, null));
    }

    public TradeResult execute(ChannelQuote quote, TradeIntent intent,
                               ServerLevel level) {
        // Post a Request on behalf of the buyer's guild
        // Return partial success — items not immediate, payment reserved
    }
}
```

Player intent via this channel: player pays upfront into escrow; items
arrive when fulfilled; player notified of delivery.

## Player interaction

### Posting requests

Player holding guild office can post requests via office UI.

Player without office can "request via guild" — guild master considers
and posts on player's behalf if relationship/reputation sufficient.

### Accepting requests

Player can view open requests visible to them:

- At any guild hall: see requests available to that guild.
- "Request Board" screen lists with reward and difficulty.
- Accept via button.
- On acceptance, player gains the fulfillment task similar to a quest.

Existing adventurer quest UI extended to show non-adventurer requests.
Player in CRAFTSMEN guild sees craft requests; in MERCHANTS sees
delivery requests; etc.

### Fulfillment rewards

Player earns:
- Bronze from request bounty.
- Contribution points with their guild.
- Rank XP toward guild promotion.
- Relationship delta with requestor guild.
- Possible unique rewards (textbook, contract, sealed letter).

## Integration points

### Phase 4 integration

- `Request` record and supporting types.
- `RequestBoard` saved data per scope level.
- `GuildRequestChannel` wired fully.
- Guild posting and acceptance logic.
- Scope escalation daily ticker.
- Caravan dispatch on request acceptance for cross-village.
- Payment and refund flows.
- Extend `Quest` → `Request` migration (adventurer quests become
  `RequestType.HUNT` or `SURVEY` subtypes).
- UI extensions:
  - Guild hall "Request Board" screen.
  - Office actions for posting/reviewing requests.
- `/request` debug commands:
  - `/request list <scope>`
  - `/request post <guild> <type> <params>`
  - `/request accept <requestId> <guild>`

### Phase 4 upstream

- Resource categories (Phase 4) feed into request-generation
  decisions (villages detect shortages by category).
- NPC companies (Phase 4) post and accept as major fulfillers.
- Guild refactor (Phase 4) provides the sending/receiving entities.

## Behavior contract

### Does

- Generalize quest-style requests to multiple types and scopes.
- Post, accept, fulfill, expire requests.
- Route bronze via escrow with platform and guild cuts.
- Escalate unfulfilled requests up the scope hierarchy.
- Integrate with caravan delivery for remote fulfillment.

### Does not

- Support multi-guild collaborative fulfillment in v1.
- Handle request cancellation after acceptance except as failure.
- Model request auctions / bidding. Flat reward posting.
- Track per-request reputation at the individual fulfiller level
  (tracked at guild level only for v1).

## Edge cases

- **Guild posts request but has no treasury for escrow.** Posting
  fails; displayed as a warning.
- **Request accepted but fulfiller dies mid-production.** Partial
  progress refunded pro-rata; remainder of escrow returned to
  originator.
- **Village dissolves with outstanding accepted requests.** Requests
  revert to OPEN at higher scope; original escrow refunded minus
  penalty.
- **Deadline exactly met.** Timestamp check uses `<=`; borderline
  cases counted as success.
- **Two guilds race to accept.** First commit wins; second receives
  notification.
- **Request scope-escalated but original village no longer wants it.**
  Originator can cancel (with partial escrow penalty).

## Ordering dependencies

Phase 4 depends on:
- Guild refactor (same phase) — requests live on guilds.
- Resource categories (same phase) — shortage detection.
- NPC companies (same phase) — fulfiller candidates.
- Existing caravan system — delivery.
- Economic channels (Phase 3) — GuildRequestChannel finally wired.
- Existing adventurer Quest system for migration.

## Open decisions

- Should request escalation be automatic or gated by origin guild
  master decision? **Proposed: automatic unless guild master
  explicitly locks scope. Player-led guilds may prefer manual
  control via office action.**
- Partial fulfillment — fulfiller delivers half, keeps half bounty?
  **Proposed: yes for GATHER/CRAFT/DELIVER; not for HUNT/SURVEY/
  ESCORT (binary).**
- Multiple requests for same item from different villages — should
  fulfillers consolidate? **Proposed: no in v1; each request
  handled independently.**
- Cross-kingdom requests: need diplomatic eligibility? **Proposed:
  yes in Phase 5 (kingdom relations); Phase 4 permits all cross-
  kingdom.**

## Does-not-include

- Subscription / recurring requests.
- Bulk-discount pricing on large requests.
- Request insurance against failure.
- Specialized fulfiller pairing (matching apprentice to master-level
  requests). Quality-controlled fulfillment happens via skill
  checks during production.

## Revision Notes

### Phase 4 task 28 implementation pass

Things-to-flag responses:

1. **Quest → Request migration** — deferred. The existing
   adventurer `Quest` record has fields the new `Request` record
   doesn't carry (QuestType / QuestDifficulty enums, narrative
   title + description, an attached `QuestProgress` sub-record,
   tightly-coupled `PlayerGuildData` flow). A fully-typed
   migration would touch `Quest`, `PlayerGuildData`,
   `AdventurerQuestGenerator`, the adventurer quest UI, and the
   Phase 1-task 09 player verb that maps quest acceptance —
   too large for one session. v1 leaves the legacy adventurer
   path alone and runs the abstract `Request` layer alongside.
   An adapter that exposes a `Quest` as a
   `Request{ type=HUNT/SURVEY }` can land file-by-file when the
   adventurer screen migrates onto the framework GUI.
2. **Caravan dispatch on accept** — deferred. v1 transitions
   ACCEPTED → IN_PROGRESS → FULFILLED via
   `RequestBoard.updateProgress` and the daily ticker; the
   `/request fulfill` debug command short-circuits the
   completion. Real caravan dispatch wires when
   `CaravanSavedData` exposes a public dispatch entry. Same
   blocker as doc 26's trading-company caravan stub.
3. **Partial fulfilment.** Implemented at the data layer:
   `RequestType.supportsPartialFulfilment` returns true for
   GATHER / CRAFT / DELIVER, false for HUNT / SURVEY / ESCORT.
   The settlement layer reads `Progress.isComplete` which
   triggers on `currentProgress >= totalProgress`, so a
   GATHER request that delivered 3/10 stays IN_PROGRESS
   until either the deadline expires (FAILED with refund
   penalty) or the count reaches 10. Pro-rata partial pay
   on early termination is Phase 5 polish.
4. **Player-side reputation system.** Spec line 268
   describes the deltas. v1 fires guild contribution +/-
   from `RequestSettlement.addContribution(...)` (positive
   on fulfilment, -5 penalty on failure) so the existing
   guild-rank ladder reads from the right side. Per-NPC
   rep with the originator and per-village rep deltas land
   when the request system has a richer concept of who
   benefited (Phase 5 polish — currently the originator NPC
   is just stored on the `Request`).

Spec deviations:
- **Single global `RequestBoard`** instead of three nested
  boards (per-village, per-kingdom, global). The
  discriminator is the `RequestScope` field on each
  request; `RequestBoard.availableFor(guild, level)`
  filters at query time. Net effect identical for v1 with
  fewer SavedData slots; profiling can drive a split.
- **`Request` codec split into Target + Progress sub-
  records.** The flat layout would have been 17 fields,
  past DFU's 16-field `RecordCodecBuilder.Instance.group`
  ceiling — the same wall doc 26 hit. Two sub-records
  bring the outer codec to 13 fields.
- **Acceptance scoring** is first-eligible-wins ranked by
  member count with a treasury tiebreak. Spec line 131's
  "reward-to-effort" scoring needs a per-guild capacity
  model (workshop load + skill match) that v1 doesn't
  ship.
- **Player fulfilment UI** deferred. Debug commands work;
  the Request Board screen lands with Phase 5 GUI polish.
- **Office posting screens** deferred. The data path
  (`RequestPosting.post`) and `/request post` debug
  command both work; the leader / guild-master in-game
  posting UI lands later.
- **L0 guilds and `availableFor`.** The query filter
  early-returns on `!guild.canPostRequests()` — i.e. L0
  guilds don't see any acceptable requests. Spec lines
  153-154 say L0 guilds have "minimal request capability
  (local only, no inter-village posting)"; that's
  preserved on the post side via the same gate. Acceptance
  needs hall infrastructure; v1 ties both to L1+.

Deferrals:
- Multi-guild collaborative fulfilment ("Does not" #1).
- Cancellation after acceptance ("Does not" #2).
- Auctions / bidding / subscription requests
  ("Does-not-include").
- Pro-rata partial-pay on mid-flight cancellation.
- Caravan integration on accept / fulfilment.
- Cross-kingdom diplomatic gating (spec "Open decisions"
  #4 — Phase 5 with kingdom relations).
