# 27 — Guild Refactor

## Purpose

The current `GuildData` is adventurer-specific. "Guild" in the broader
redesign spans craftsmen, merchants, agricultural, religious, and
adventurer organizations. All share common structure: members, ranks,
offices, treasury, requests, shared reputation.

This subsystem refactors existing guild code into a common abstraction,
with subclasses for each guild type. It also introduces implicit
Level-0 guilds (every village has an implicit guild per profession
cluster even without a physical hall) and Level-1+ guilds that appear
when a `GuildHall` building is constructed.

## Data model

### AbstractGuild

Base class for all guild types:

```java
public abstract class AbstractGuild {
    protected UUID guildId;
    protected String name;
    protected GuildType type;
    protected UUID homeVillageId;
    protected UUID guildHallBuildingId;    // null for L0
    protected int level;                    // 0, 1, 2, 3
    protected Map<UUID, GuildMember> members;
    protected GuildTreasury treasury;
    protected OfficeState offices;          // from office framework
    protected long foundedTick;
    protected long lastQuestRefreshTick;

    public abstract GuildType type();
    public abstract List<Profession> memberProfessions();
    public abstract Optional<GuildHallStyle> hallStyle();

    // Common operations
    public void addMember(UUID npcId, GuildRank rank);
    public void removeMember(UUID npcId);
    public void updateRank(UUID npcId, GuildRank newRank);

    // Codec via polymorphic serialization (CompoundTag subtype field)
    public static final Codec<AbstractGuild> CODEC;
}

public enum GuildType {
    ADVENTURER,         // existing — combat and quest
    CRAFTSMEN,          // blacksmith, carpenter, weaver, stonemason
    MERCHANTS,          // merchant, innkeeper, market-related
    AGRICULTURAL,       // farmer, farmhand, miller, baker
    RELIGIOUS,          // priest, scholar
    SCHOLARLY;          // scholar, scribe, librarian (overlap with religious)

    public static final Codec<GuildType> CODEC;
}
```

### Subclasses

One class per guild type, mostly thin extensions:

```java
public class AdventurerGuild extends AbstractGuild {
    // Existing GuildData behavior moves here
}

public class CraftsmensGuild extends AbstractGuild {
    // Craft-specific behavior — masterpiece certification,
    // apprentice coordination, etc.
}

public class MerchantsGuild extends AbstractGuild { ... }
public class AgriculturalGuild extends AbstractGuild { ... }
public class ReligiousGuild extends AbstractGuild { ... }
public class ScholarlyGuild extends AbstractGuild { ... }
```

### GuildMember (existing, extended)

```java
public record GuildMember(
    UUID npcId,
    UUID guildId,
    GuildRank currentRank,
    int xp,
    int contributionPoints,     // tracks service
    long joinedTick,
    List<UUID> completedRequestIds
) { ... }
```

### GuildRank

Existing enum, generalized:

```java
public enum GuildRank {
    APPLICANT,
    BRONZE,             // existing — member
    SILVER,
    GOLD,
    PLATINUM,           // existing — veteran
    ELDER;              // very senior

    public static final Codec<GuildRank> CODEC;
}
```

Different guild types may use different subsets of ranks.

### GuildLevel

```java
public enum GuildLevel {
    IMPLICIT(0),        // no hall, emergent from profession cluster
    ESTABLISHED(1),     // hall built; offices active
    RECOGNIZED(2),      // multi-village; cross-village request eligible
    PROMINENT(3);       // kingdom-level influence

    private final int value;
}
```

Level determines which capabilities are available:

- L0: Members tracked; simple meet-ups; cannot post inter-village
  requests.
- L1: Guild hall exists; offices populated; local request posting.
- L2: Multi-village members; cross-village requests; guild caravans.
- L3: Kingdom-influencing; represents guild to king; can sway laws.

## Implicit L0 guilds

Every village has implicit guilds for every cluster of ≥ 2 matching
professions:

```java
// Village bootstrapping:
if (countOfProfessions(CRAFTSMEN_PROFESSIONS) >= 2) {
    createImplicitGuild(village, GuildType.CRAFTSMEN);
}
```

Implicit guilds have:
- Auto-populated member list (all village NPCs of matching
  professions).
- No guild hall.
- No offices (other than implicit informal leadership — the highest-
  skill member functions as informal leader).
- No physical treasury (village-level economics cover what they need).
- Minimal request capability (local only, no inter-village posting).

## L1+ upgrade

When a `GUILD_HALL` building (new type) is constructed in a village:

1. Village scans existing implicit guilds.
2. If appropriate guild type matches the hall (e.g. Craftsmen Hall
   matches Craftsmen Guild), the implicit guild is upgraded.
3. Guild gets a physical address, offices become appointable.
4. Master election runs (via office framework).
5. Guild registrar and treasurer appointed.
6. Guild can now post requests and dispatch contracts.

Multiple guild hall buildings in a village create multiple L1+ guilds
(one per type).

## Guild hall building types

New BuildingType variants:

```java
public enum BuildingType {
    // ... existing
    GUILD_HALL_ADVENTURER,      // existing guild hall renamed
    GUILD_HALL_CRAFTSMEN,
    GUILD_HALL_MERCHANTS,
    GUILD_HALL_AGRICULTURAL,
    GUILD_HALL_RELIGIOUS,
    GUILD_HALL_SCHOLARLY;
}
```

Each maps to the corresponding guild type in building placement logic.

## Offices (from Phase 3 framework)

Each guild has:
- `guild_master` — senior leader; election varies by culture.
- `guild_treasurer` — financial.
- `guild_registrar` — member records, request tracking.
- `master_of_apprentices` — apprenticeship coordination (craftsmen
  primarily).

At L0 guilds, offices exist conceptually but are unpopulated by formal
appointment. At L1+, offices are populated per framework rules.

## Request system (bridge to next doc)

Guilds post and accept inter-village requests for goods, services, and
quests. Full spec in `28-request-board.md`; this doc specifies the
guild-side structure:

```java
public class AbstractGuild {
    // ...

    public boolean canPostRequests() { return level >= 1; }
    public boolean canAcceptRemoteRequests() { return level >= 2; }
    public void postRequest(Request req);
    public void acceptRequest(Request req);
}
```

## Membership rules

### Joining

NPC automatically joins implicit guild when adopting matching
profession. Explicit guild (L1+) requires:

- Applicant rank initially.
- Sponsorship from existing member (automatic for villagers).
- May require skill minimum (craftsmen: primary skill ≥ 20).

Player joins via:
- Guild hall NPC interaction (existing adventurer flow generalized).
- Apprenticeship path: apprentice of a guild master auto-joins at
  applicant rank.

### Rank progression

XP and contribution-based, per subclass:

- AdventurerGuild: quests completed → XP.
- CraftsmensGuild: commissions fulfilled, masterpieces certified →
  contribution.
- MerchantsGuild: trade volume → contribution.
- Others similar.

Rank promotion fires via guild master approval (or automatic at L0).

### Expulsion

Guild can expel member:
- Repeated no-show on accepted requests.
- Severe crime (convicted).
- Violation of guild rules (culturally defined).

Expulsion = removed from guild; rank drops; reputation hit.

## Treasury

`GuildTreasury` (analogous to `VillageTreasury`):

- Income: membership dues, request fulfillment fees, donations.
- Expenses: officer wages, hall maintenance, request bounties paid to
  member accepters.
- Balance persisted; deficits trigger warnings to guild master.

## Player guild participation

Player can:
- Join any guild (if eligible by profession).
- Earn contribution points.
- Rise through ranks via accomplishments.
- Hold guild office (Phase 3 office framework applies).
- Found own guild (Phase 6 future).

## Integration points

### Phase 4 integration

- `AbstractGuild` base + subclasses.
- Existing `GuildData` renamed to `AdventurerGuild` extending
  `AbstractGuild`.
- `VillageSavedData` extended: `Map<UUID, List<AbstractGuild>>` per
  village.
- Implicit guild bootstrapping on village creation and profession
  assignment.
- L0 → L1 upgrade hook on GUILD_HALL building construction.
- New building types for each guild hall variant.
- Office framework wired for each guild.
- Guild treasury registered.
- Existing guild commands extended for all types:
  - `/guild info <type> <village>`
  - `/guild promote <member> <rank>`
  - `/guild upgrade <guild>` — force L1 upgrade

### Phase 4 consumers

- Request board (`28-request-board.md`, same phase) — requests flow
  through guilds.
- NPC-owned companies (`26-npc-companies.md`) can interact with
  merchant/craftsmen guilds.
- Apprenticeship (Phase 2) integrates with craftsmen guild's
  `master_of_apprentices`.

## Behavior contract

### Does

- Generalize guild to a type-polymorphic abstraction.
- Support implicit L0 guilds for any profession cluster.
- Upgrade to L1+ on guild hall construction.
- Wire offices via existing framework.
- Handle membership, ranks, expulsion uniformly.

### Does not

- Support multi-guild membership for NPCs (one guild per profession
  cluster per NPC).
- Implement full cross-village guild networks in v1 (Phase 4 has
  basic cross-village; deeper networks future).
- Model internal guild politics beyond office framework.
- Generate custom guild hall architecture — buildings use existing
  profile system.

## Edge cases

- **Village with one qualified profession member** — no implicit
  guild. L0 requires 2+.
- **Guild hall destroyed** — guild drops to L0 (if possible) or
  dissolves if no members remain.
- **Member moves to another village** — remains in original guild
  unless expelled or quits. Cross-village guild membership is allowed
  at L2+.
- **Guild master dies with no clear successor** — office vacates;
  framework handles selection.
- **Profession migration**: NPC changes profession; loses membership
  in old guild's cluster, auto-joins new one.

## Ordering dependencies

Phase 4 depends on:
- Existing GuildData → subclass transition.
- Office framework (Phase 3).
- Building type system.
- Resource categories (Phase 4 same-phase) — economic inputs.
- Apprenticeship (Phase 2) — craftsmen guild integration.
- NPC-owned companies (Phase 4 same-phase) — merchants guild.

## Open decisions

- Should existing adventurer guild code stay monolithic initially and
  refactor incrementally, or big-bang rewrite? **Proposed: incremental
  — extract abstract base, adventurer subclass retains all existing
  logic, new subclasses are additive.**
- Guild hall auto-construction: should villages with L0 guilds
  spontaneously build halls? **Proposed: no — halls are a player/
  kingdom investment, not auto-built. NPC village leader may petition
  kingdom for funds in Phase 5.**
- Guild membership exclusivity: can a scholar be in both Scholarly
  and Religious guilds? **Proposed: no, one guild per NPC at a time;
  primary profession decides.**

## Does-not-include

- Guild factionalism / internal splits.
- Inter-guild rivalries (craftsmen vs. merchants over shared
  territory).
- Guild taxes to kingdom (stub; Phase 5+).
- Custom guild rank names per guild type (stays with shared
  enum in v1).

## Revision Notes

(changes recorded here as the spec evolves after testing)
