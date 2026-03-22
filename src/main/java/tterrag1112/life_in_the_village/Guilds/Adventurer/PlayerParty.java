// src/main/java/tterrag1112/life_in_the_village/Entities/Party/PlayerParty.java
package tterrag1112.life_in_the_village.Guilds.Adventurer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Represents a player-led adventurer party.
 *
 * <h3>Party lifecycle</h3>
 * <ol>
 *   <li>Player interacts with an adventurer NPC at the guild hall
 *       → {@code /party invite} or right-click interaction</li>
 *   <li>{@link PlayerParty} is created and stored in
 *       {@link PlayerPartySavedData}</li>
 *   <li>NPC is flagged as a party member; goals are registered</li>
 *   <li>Party persists across sessions via codec serialisation</li>
 *   <li>Party disbands when player uses {@code /party disband}
 *       or all members die</li>
 * </ol>
 *
 * <h3>Party composition</h3>
 * Maximum 4 members (1 of each role, or duplicates of common roles).
 * The player is always the leader — they are not stored as a member.
 * Members are stored as {@link PartyMember} records.
 */
public class PlayerParty {

    public static final int MAX_MEMBERS = 4;

    // =========================================================================
    // PartyMember nested record
    // =========================================================================

    /**
     * Snapshot of a single NPC member's persistent state.
     * The NPC entity itself is identified by {@link #npcId} which
     * maps to a spawned {@code TownspersonMob} UUID.
     */
    public record PartyMember(
            UUID       npcId,
            String     name,
            CombatRole role,
            int        level,         // 1–5, increases with kills
            int        kills,         // lifetime kill count
            int        questsCompleted,
            boolean    isAlive,
            /** UUID of the home village this NPC came from. */
            UUID       homeVillageId
    ) {
        public static final Codec<PartyMember> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        UUIDUtil.CODEC
                                .fieldOf("npcId")
                                .forGetter(PartyMember::npcId),
                        Codec.STRING
                                .fieldOf("name")
                                .forGetter(PartyMember::name),
                        Codec.STRING.xmap(
                                        CombatRole::valueOf,
                                        CombatRole::name)
                                .fieldOf("role")
                                .forGetter(PartyMember::role),
                        Codec.INT
                                .optionalFieldOf("level", 1)
                                .forGetter(PartyMember::level),
                        Codec.INT
                                .optionalFieldOf("kills", 0)
                                .forGetter(PartyMember::kills),
                        Codec.INT
                                .optionalFieldOf("questsCompleted", 0)
                                .forGetter(PartyMember::questsCompleted),
                        Codec.BOOL
                                .optionalFieldOf("isAlive", true)
                                .forGetter(PartyMember::isAlive),
                        UUIDUtil.CODEC
                                .fieldOf("homeVillageId")
                                .forGetter(PartyMember::homeVillageId)
                ).apply(i, PartyMember::new));

        public PartyMember withKill() {
            int newKills = kills + 1;
            // Level up every 10 kills, capped at 5
            int newLevel = Math.min(5, 1 + newKills / 10);
            return new PartyMember(npcId, name, role,
                    newLevel, newKills, questsCompleted,
                    isAlive, homeVillageId);
        }

        public PartyMember withQuestCompleted() {
            return new PartyMember(npcId, name, role,
                    level, kills, questsCompleted + 1,
                    isAlive, homeVillageId);
        }

        public PartyMember withDead() {
            return new PartyMember(npcId, name, role,
                    level, kills, questsCompleted,
                    false, homeVillageId);
        }

        /** XP bonus multiplier from member level (1.0 → 1.5). */
        public float xpMultiplier() {
            return 1.0f + (level - 1) * 0.1f;
        }
    }

    // =========================================================================
    // Codec
    // =========================================================================

    public static final Codec<PlayerParty> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    UUIDUtil.CODEC
                            .fieldOf("partyId")
                            .forGetter(PlayerParty::getPartyId),
                    UUIDUtil.CODEC
                            .fieldOf("leaderPlayerId")
                            .forGetter(PlayerParty::getLeaderPlayerId),
                    Codec.STRING
                            .optionalFieldOf("partyName", "Unnamed Party")
                            .forGetter(PlayerParty::getPartyName),
                    PartyMember.CODEC.listOf()
                            .optionalFieldOf("members",
                                    new ArrayList<>())
                            .forGetter(PlayerParty::getMembers),
                    UUIDUtil.CODEC
                            .optionalFieldOf("activeQuestId")
                            .forGetter(p ->
                                    Optional.ofNullable(p.activeQuestId)),
                    Codec.LONG
                            .optionalFieldOf("formedTick", 0L)
                            .forGetter(PlayerParty::getFormedTick),
                    Codec.INT
                            .optionalFieldOf("totalQuestsCompleted", 0)
                            .forGetter(PlayerParty::getTotalQuestsCompleted)
            ).apply(instance, PlayerParty::fromCodec));

    private static PlayerParty fromCodec(
            UUID partyId, UUID leaderPlayerId, String partyName,
            List<PartyMember> members, Optional<UUID> activeQuestId,
            long formedTick, int totalQuestsCompleted) {
        PlayerParty party = new PlayerParty(
                partyId, leaderPlayerId, partyName, formedTick);
        party.members.addAll(members);
        party.activeQuestId         = activeQuestId.orElse(null);
        party.totalQuestsCompleted  = totalQuestsCompleted;
        return party;
    }

    // =========================================================================
    // Fields
    // =========================================================================

    private final UUID   partyId;
    private final UUID   leaderPlayerId;
    private String       partyName;
    private final List<PartyMember> members = new ArrayList<>();
    @Nullable private UUID activeQuestId;
    private final long   formedTick;
    private int          totalQuestsCompleted;

    // ── Runtime only — not persisted ─────────────────────────────────────────
    /** XZ position the party should rally to when not in combat. */
    @Nullable private BlockPos rallyPoint;
    /** True when the party is currently in combat formation. */
    private boolean inCombat = false;

    // =========================================================================
    // Constructors
    // =========================================================================

    public PlayerParty(UUID partyId, UUID leaderPlayerId,
                       String partyName, long formedTick) {
        this.partyId        = partyId;
        this.leaderPlayerId = leaderPlayerId;
        this.partyName      = partyName;
        this.formedTick     = formedTick;
    }

    public static PlayerParty create(UUID leaderPlayerId,
                                     String partyName,
                                     long currentTick) {
        return new PlayerParty(UUID.randomUUID(),
                leaderPlayerId, partyName, currentTick);
    }

    // =========================================================================
    // Member management
    // =========================================================================

    public boolean addMember(PartyMember member) {
        if (members.size() >= MAX_MEMBERS) return false;
        if (members.stream().anyMatch(m ->
                m.npcId().equals(member.npcId()))) return false;
        members.add(member);
        return true;
    }

    public void removeMember(UUID npcId) {
        members.removeIf(m -> m.npcId().equals(npcId));
    }

    public boolean hasMember(UUID npcId) {
        return members.stream()
                .anyMatch(m -> m.npcId().equals(npcId));
    }

    public Optional<PartyMember> getMember(UUID npcId) {
        return members.stream()
                .filter(m -> m.npcId().equals(npcId))
                .findFirst();
    }

    public void updateMember(PartyMember updated) {
        members.replaceAll(m ->
                m.npcId().equals(updated.npcId()) ? updated : m);
    }

    public boolean isFull()  { return members.size() >= MAX_MEMBERS; }
    public boolean isEmpty() { return members.isEmpty(); }

    public int getAliveCount() {
        return (int) members.stream()
                .filter(PartyMember::isAlive).count();
    }

    /**
     * Returns the combined XP reward multiplier for this party
     * composition. A well-rounded party (all four roles present)
     * gets a 25% bonus on top of individual member multipliers.
     */
    public float getPartyXpMultiplier() {
        float base = members.stream()
                .filter(PartyMember::isAlive)
                .map(PartyMember::xpMultiplier)
                .reduce(1.0f, Float::sum) / Math.max(1, getAliveCount());

        // Composition bonus — unique roles
        long uniqueRoles = members.stream()
                .filter(PartyMember::isAlive)
                .map(PartyMember::role)
                .distinct().count();
        float compositionBonus = uniqueRoles >= 4 ? 1.25f
                : uniqueRoles >= 3 ? 1.15f
                : uniqueRoles >= 2 ? 1.05f
                : 1.0f;

        return base * compositionBonus;
    }

    /**
     * Returns true if the party has at least one member of each
     * of the given roles — used for quest bonus conditions.
     */
    public boolean hasRole(CombatRole role) {
        return members.stream()
                .filter(PartyMember::isAlive)
                .anyMatch(m -> m.role() == role);
    }

    // =========================================================================
    // Quest
    // =========================================================================

    public void completeQuest() {
        activeQuestId = null;
        totalQuestsCompleted++;
        members.replaceAll(m -> m.isAlive()
                ? m.withQuestCompleted() : m);
    }

    // =========================================================================
    // Getters / setters
    // =========================================================================

    public UUID   getPartyId()              { return partyId; }
    public UUID   getLeaderPlayerId()       { return leaderPlayerId; }
    public String getPartyName()            { return partyName; }
    public void   setPartyName(String name) { this.partyName = name; }
    public List<PartyMember> getMembers()   { return Collections.unmodifiableList(members); }
    public @Nullable UUID getActiveQuestId(){ return activeQuestId; }
    public void setActiveQuestId(UUID id)   { this.activeQuestId = id; }
    public long  getFormedTick()            { return formedTick; }
    public int   getTotalQuestsCompleted()  { return totalQuestsCompleted; }
    public @Nullable BlockPos getRallyPoint(){ return rallyPoint; }
    public void setRallyPoint(BlockPos pos) { this.rallyPoint = pos; }
    public boolean isInCombat()             { return inCombat; }
    public void setInCombat(boolean v)      { this.inCombat = v; }
}