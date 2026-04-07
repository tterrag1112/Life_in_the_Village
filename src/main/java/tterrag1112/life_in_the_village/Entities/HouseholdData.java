// src/main/java/tterrag1112/life_in_the_village/Village/Social/HouseholdData.java
package tterrag1112.life_in_the_village.Entities;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.*;

/**
 * Represents a single household — a group of NPCs who share a house.
 *
 * <h3>Stored in VillageSavedData</h3>
 * Households are created once at village spawn time (in a post-spawn pass)
 * and are subsequently updated when NPCs move houses, die, or are born.
 * They are keyed by {@code buildingId} — one household per house building.
 *
 * <h3>Behavioural uses</h3>
 * <ul>
 *   <li>{@link tterrag1112.life_in_the_village.Entities.Goals.Social.EatMealGoal}
 *       checks household membership to make NPCs face each other while eating.</li>
 *   <li>{@link tterrag1112.life_in_the_village.Entities.Goals.Social.SocialWalkGoal}
 *       uses household membership to pair NPCs for walks.</li>
 *   <li>{@link tterrag1112.life_in_the_village.Lore.HistoryTextGenerator}
 *       uses household events to generate {@code NOTABLE_MARRIAGE} /
 *       {@code NOTABLE_BIRTH} history entries.</li>
 * </ul>
 */
public class HouseholdData {

    // -------------------------------------------------------------------------
    // Household type
    // -------------------------------------------------------------------------

    public enum HouseholdType {
        SINGLE,     // one adult NPC
        COUPLE,     // two adults
        FAMILY,     // two adults and one or more children
        EXTENDED    // three or more adults (e.g. grandparent + couple)
    }

    // -------------------------------------------------------------------------
    // Codec
    // -------------------------------------------------------------------------

    public static final Codec<HouseholdData> CODEC =
            RecordCodecBuilder.create(i -> i.group(
                    UUIDUtil.CODEC.fieldOf("householdId")
                            .forGetter(h -> h.householdId),
                    UUIDUtil.CODEC.fieldOf("buildingId")
                            .forGetter(h -> h.buildingId),
                    UUIDUtil.CODEC.listOf()
                            .fieldOf("memberNpcIds")
                            .forGetter(h -> new ArrayList<>(h.memberNpcIds)),
                    Codec.STRING.xmap(HouseholdType::valueOf,
                                    HouseholdType::name)
                            .fieldOf("type")
                            .forGetter(h -> h.type),
                    Codec.LONG.fieldOf("formedTick")
                            .forGetter(h -> h.formedTick),
                    Codec.LONG.optionalFieldOf("pooledWealth", 0L)
                            .forGetter(h -> h.pooledWealth)

            ).apply(i, HouseholdData::new));

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final UUID              householdId;
    private final UUID              buildingId;
    private final List<UUID>        memberNpcIds;
    private       HouseholdType     type;
    private final long              formedTick;
    private long pooledWealth = 0L;


    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public HouseholdData(UUID householdId, UUID buildingId,
                         List<UUID> memberNpcIds,
                         HouseholdType type, long formedTick, long pooledWealth) {
        this.householdId  = householdId;
        this.buildingId   = buildingId;
        this.memberNpcIds = new ArrayList<>(memberNpcIds);
        this.type         = type;
        this.formedTick   = formedTick;
        this.pooledWealth  = pooledWealth;

    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Creates a household from a list of NPC UUIDs living in a building.
     * Infers the type from member count and age stage — callers should pass
     * NPCs sorted by age (adult first, children last) for best type inference.
     *
     * @param buildingId  UUID of the house building
     * @param memberIds   ordered list of NPC UUIDs in the house
     * @param childCount  how many of those NPCs are children
     * @param formedTick  {@code level.getGameTime()} at creation time
     */
    public static HouseholdData create(UUID buildingId,
                                       List<UUID> memberIds,
                                       int childCount,
                                       long formedTick) {
        HouseholdType type = inferType(memberIds.size(), childCount);
        return new HouseholdData(
                UUID.randomUUID(), buildingId,
                memberIds, type, formedTick, 0L);
    }

    private static HouseholdType inferType(int total, int childCount) {
        int adults = total - childCount;
        if (adults >= 3) return HouseholdType.EXTENDED;
        if (adults == 2 && childCount > 0) return HouseholdType.FAMILY;
        if (adults == 2) return HouseholdType.COUPLE;
        return HouseholdType.SINGLE;
    }

    // -------------------------------------------------------------------------
    // Mutation
    // -------------------------------------------------------------------------

    public void addMember(UUID npcId) {
        if (!memberNpcIds.contains(npcId)) memberNpcIds.add(npcId);
        recomputeType(0);
    }

    public void removeMember(UUID npcId) {
        memberNpcIds.remove(npcId);
        recomputeType(0);
    }

    private void recomputeType(int childCount) {
        type = inferType(memberNpcIds.size(), childCount);
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    public boolean hasMember(UUID npcId) { return memberNpcIds.contains(npcId); }
    public boolean isEmpty()             { return memberNpcIds.isEmpty(); }
    public int     size()                { return memberNpcIds.size(); }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID           getHouseholdId()  { return householdId; }
    public UUID           getBuildingId()   { return buildingId; }
    public List<UUID>     getMemberNpcIds() { return Collections.unmodifiableList(memberNpcIds); }
    public HouseholdType  getType()         { return type; }
    public long           getFormedTick()   { return formedTick; }

    public long getPooledWealth()         { return pooledWealth; }

    public void depositToPool(long bronze) {
        if (bronze > 0) pooledWealth += bronze;
    }

    /**
     * Attempts to withdraw from the household pool.
     * Role-based limits:
     *   HEAD   — up to 100% of pool
     *   SPOUSE — up to 60% of pool
     *   CHILD  — up to 15% of pool, capped at 30 bronze regardless of pool size
     *
     * @return true if the amount was withdrawn
     */
    public boolean withdrawFromPool(long bronze, FamilyRole role) {
        if (pooledWealth < bronze) return false;

        long maxAllowed = switch (role) {
            case HEAD     -> pooledWealth;
            case SPOUSE   -> (long)(pooledWealth * 0.6);
            case CHILD    -> Math.min(30L, (long)(pooledWealth * 0.15));
            case ELDERLY  -> (long)(pooledWealth * 0.4);
            default       -> 0L;
        };

        if (bronze > maxAllowed) return false;
        pooledWealth -= bronze;
        return true;
    }

    public boolean canWithdraw(long bronze, FamilyRole role) {
        if (pooledWealth < bronze) return false;
        long maxAllowed = switch (role) {
            case HEAD     -> pooledWealth;
            case SPOUSE   -> (long)(pooledWealth * 0.6);
            case CHILD    -> Math.min(30L, (long)(pooledWealth * 0.15));
            case ELDERLY  -> (long)(pooledWealth * 0.4);
            default       -> 0L;
        };
        return bronze <= maxAllowed;
    }
}