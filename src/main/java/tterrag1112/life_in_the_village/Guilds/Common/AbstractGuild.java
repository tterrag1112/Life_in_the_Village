package tterrag1112.life_in_the_village.Guilds.Common;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import tterrag1112.life_in_the_village.Npc.Office.OfficeState;
import tterrag1112.life_in_the_village.Npc.Office.OrgType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4 doc 27. Single concrete class covering all six guild
 * categories — the {@link GuildType} field is the discriminator and
 * the polymorphic Codec is just a tagged record codec rather than a
 * sealed-interface dispatch.
 *
 * <p>Why a single class instead of one subclass per type: the spec
 * line 63 says "thin extensions" and the v1 type-specific behavior
 * is empty — every subclass would just override {@code type()}. Phase
 * 5 can split this into a sealed hierarchy when each type genuinely
 * diverges (e.g. CRAFTSMEN's masterpiece certification, MERCHANTS's
 * trade-volume tracker).</p>
 *
 * <p>Adventurer special case: an {@code AdventurerGuild} adapter
 * lives alongside the existing {@code GuildData} record so the legacy
 * 25-file consumer surface stays intact. The adapter forwards member
 * / treasury / office queries to the matching {@code GuildData}
 * entry; new subclasses keep their state in {@code GuildSavedData}.</p>
 */
public class AbstractGuild {

    private final UUID guildId;
    private String name;
    private final GuildType type;
    private final UUID homeVillageId;
    /** Null for L0 (no hall). */
    private UUID guildHallBuildingId;
    private GuildLevel level;
    private final Map<UUID, GuildMemberRef> members = new LinkedHashMap<>();
    private final GuildTreasury treasury;
    private OfficeState offices;
    private final long foundedTick;
    private long lastQuestRefreshTick;

    public AbstractGuild(UUID guildId, String name, GuildType type,
                         UUID homeVillageId, UUID guildHallBuildingId,
                         GuildLevel level, long foundedTick) {
        this.guildId             = guildId;
        this.name                = name;
        this.type                = type;
        this.homeVillageId       = homeVillageId;
        this.guildHallBuildingId = guildHallBuildingId;
        this.level               = level;
        this.foundedTick         = foundedTick;
        this.treasury            = new GuildTreasury(guildId);
        this.offices             = OfficeState.emptyFor(OrgType.GUILD, guildId);
    }

    /** Codec entry point — rebuilds an instance from persisted state. */
    private AbstractGuild(UUID guildId, String name, GuildType type,
                          UUID homeVillageId, Optional<UUID> hallId,
                          GuildLevel level, List<GuildMemberRef> memberList,
                          GuildTreasury treasury, Optional<OfficeState> offices,
                          long foundedTick, long lastQuestRefreshTick) {
        this.guildId             = guildId;
        this.name                = name;
        this.type                = type;
        this.homeVillageId       = homeVillageId;
        this.guildHallBuildingId = hallId.orElse(null);
        this.level               = level;
        this.foundedTick         = foundedTick;
        this.lastQuestRefreshTick = lastQuestRefreshTick;
        this.treasury            = treasury != null
                ? treasury : new GuildTreasury(guildId);
        this.offices             = offices.orElseGet(() -> OfficeState.emptyFor(OrgType.GUILD, guildId));
        if (memberList != null) {
            for (GuildMemberRef m : memberList) members.put(m.memberId(), m);
        }
    }

    // ── Identity ──────────────────────────────────────────────────────────

    public UUID guildId()             { return guildId; }
    public String name()              { return name; }
    public void setName(String n)     { this.name = n; }
    public GuildType type()           { return type; }
    public UUID homeVillageId()       { return homeVillageId; }
    public Optional<UUID> guildHallBuildingId() {
        return Optional.ofNullable(guildHallBuildingId);
    }
    public GuildLevel level()         { return level; }
    public long foundedTick()         { return foundedTick; }
    public long lastQuestRefreshTick(){ return lastQuestRefreshTick; }
    public void setLastQuestRefreshTick(long t) { this.lastQuestRefreshTick = t; }

    public boolean canPostRequests()         { return level.canPostRequests(); }
    public boolean canAcceptRemoteRequests() { return level.canAcceptRemoteRequests(); }

    // ── Members ───────────────────────────────────────────────────────────

    public Map<UUID, GuildMemberRef> members() {
        return java.util.Collections.unmodifiableMap(members);
    }

    public boolean hasMember(UUID id) { return id != null && members.containsKey(id); }

    public Optional<GuildMemberRef> member(UUID id) {
        return Optional.ofNullable(id == null ? null : members.get(id));
    }

    public GuildMemberRef addMember(UUID memberId, GuildRankTier rank, long tick) {
        if (memberId == null) return null;
        GuildMemberRef existing = members.get(memberId);
        if (existing != null) return existing;
        GuildMemberRef ref = new GuildMemberRef(memberId, rank, 0, tick);
        members.put(memberId, ref);
        return ref;
    }

    public boolean removeMember(UUID memberId) {
        return memberId != null && members.remove(memberId) != null;
    }

    public boolean updateRank(UUID memberId, GuildRankTier newRank) {
        if (memberId == null || newRank == null) return false;
        GuildMemberRef cur = members.get(memberId);
        if (cur == null) return false;
        members.put(memberId, cur.withRank(newRank));
        return true;
    }

    public boolean addContribution(UUID memberId, int delta) {
        if (memberId == null || delta == 0) return false;
        GuildMemberRef cur = members.get(memberId);
        if (cur == null) return false;
        members.put(memberId, cur.addContribution(delta));
        return true;
    }

    // ── Treasury / offices ───────────────────────────────────────────────

    public GuildTreasury treasury() { return treasury; }
    public OfficeState offices()    { return offices; }
    public void setOffices(OfficeState s) { if (s != null) this.offices = s; }

    // ── Level + hall transitions ─────────────────────────────────────────

    /**
     * Promotes the guild to ESTABLISHED (L1) on hall construction.
     * Idempotent — repeated calls are no-ops once the hall id is set.
     * Seeds the treasury with the L1 starting balance the first time
     * through.
     */
    public boolean promoteToEstablished(UUID hallBuildingId) {
        if (hallBuildingId == null) return false;
        if (this.guildHallBuildingId != null) return false;
        this.guildHallBuildingId = hallBuildingId;
        if (this.level == GuildLevel.IMPLICIT) {
            this.level = GuildLevel.ESTABLISHED;
            if (treasury.balance() == 0L) {
                treasury.deposit(GuildTreasury.L1_STARTING_BALANCE);
            }
            return true;
        }
        return false;
    }

    /** Demotes when the hall is destroyed. The treasury balance is
     *  preserved — members may still scrape something together to
     *  rebuild later. */
    public boolean demoteToImplicit() {
        if (this.level == GuildLevel.IMPLICIT) return false;
        this.guildHallBuildingId = null;
        this.level = GuildLevel.IMPLICIT;
        return true;
    }

    // ── Codec ────────────────────────────────────────────────────────────

    public static final Codec<AbstractGuild> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("guildId").forGetter(AbstractGuild::guildId),
            Codec.STRING.fieldOf("name").forGetter(AbstractGuild::name),
            GuildType.CODEC.fieldOf("type").forGetter(AbstractGuild::type),
            UUIDUtil.CODEC.fieldOf("homeVillageId").forGetter(AbstractGuild::homeVillageId),
            UUIDUtil.CODEC.optionalFieldOf("guildHallBuildingId")
                    .forGetter(AbstractGuild::guildHallBuildingId),
            GuildLevel.CODEC.optionalFieldOf("level", GuildLevel.IMPLICIT)
                    .forGetter(AbstractGuild::level),
            GuildMemberRef.CODEC.listOf().optionalFieldOf("members", List.of())
                    .forGetter(g -> List.copyOf(g.members.values())),
            GuildTreasury.CODEC.fieldOf("treasury").forGetter(AbstractGuild::treasury),
            OfficeState.CODEC.optionalFieldOf("offices")
                    .forGetter(g -> Optional.ofNullable(g.offices)),
            Codec.LONG.optionalFieldOf("foundedTick", 0L).forGetter(AbstractGuild::foundedTick),
            Codec.LONG.optionalFieldOf("lastQuestRefreshTick", 0L)
                    .forGetter(AbstractGuild::lastQuestRefreshTick)
    ).apply(i, AbstractGuild::new));
}
