package tterrag1112.life_in_the_village.Entities.custom;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Entities.HouseholdManager;
import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Manages all family-related state for a TownspersonMob.
 *
 * <h3>Why extract this?</h3>
 * TownspersonMob held ~15 fields and ~20 methods related to family
 * (spouse, children, household head, house assignment, surname
 * propagation, eligibility checks). This component encapsulates all
 * of that into a single cohesive unit.
 *
 * <h3>Usage</h3>
 * The TownspersonMob holds a {@code FamilyComponent family} field.
 * Goals like ChildBirthGoal, CourtingGoal, and SeekHouseGoal call
 * methods on the component rather than reaching into the mob directly.
 *
 * <h3>Persistence</h3>
 * The component saves/loads its state via {@link #save(CompoundTag)}
 * and {@link #load(CompoundTag)}, called from the mob's
 * {@code addAdditionalSaveData} / {@code readAdditionalSaveData}.
 */
public class FamilyComponent {

    // ── State ─────────────────────────────────────────────────────────────────

    private FamilyRole familyRole = FamilyRole.UNASSIGNED;
    @Nullable private UUID houseId;
    @Nullable private UUID spouseId;
    @Nullable private UUID headOfHouseholdId;
    private final List<UUID> childrenIds = new ArrayList<>();

    // =========================================================================
    // Queries
    // =========================================================================

    public FamilyRole getRole()      { return familyRole; }
    public boolean hasHome()         { return houseId != null; }
    public boolean hasSpouse()       { return spouseId != null; }
    public Optional<UUID> getHouseId()            { return Optional.ofNullable(houseId); }
    public Optional<UUID> getSpouseId()           { return Optional.ofNullable(spouseId); }
    public Optional<UUID> getHeadOfHouseholdId()  { return Optional.ofNullable(headOfHouseholdId); }
    public List<UUID> getChildrenIds()            { return Collections.unmodifiableList(childrenIds); }
    public int getChildCount()                    { return childrenIds.size(); }

    // =========================================================================
    // Mutations
    // =========================================================================

    public void setRole(FamilyRole role)                { this.familyRole = role; }
    public void setHouseId(@Nullable UUID id)           { this.houseId = id; }
    public void setSpouseId(@Nullable UUID id)          { this.spouseId = id; }
    public void setHeadOfHouseholdId(@Nullable UUID id) { this.headOfHouseholdId = id; }

    public void addChild(UUID childId) {
        if (!childrenIds.contains(childId)) {
            childrenIds.add(childId);
        }
    }

    public void removeChild(UUID childId) {
        childrenIds.remove(childId);
    }

    // =========================================================================
    // Marriage
    // =========================================================================

    /**
     * Links this NPC to a spouse. Both NPCs should call this on each other.
     * Handles house assignment and household manager notification.
     *
     * @param spouseUuid  UUID of the spouse NPC
     * @param houseToJoin the house building ID the spouse moves into (this NPC's house)
     * @param data        village saved data for household updates
     * @param tick        current game tick
     */
    public void marry(UUID spouseUuid, @Nullable UUID houseToJoin,
                      VillageSavedData data, long tick) {
        this.spouseId = spouseUuid;
        if (houseToJoin != null && this.houseId == null) {
            this.houseId = houseToJoin;
            HouseholdManager.onNpcMovedHouse(
                    spouseUuid, houseToJoin, false, data, tick);
        }
    }

    // =========================================================================
    // Eligibility checks (used by CourtingGoal, ChildBirthGoal)
    // =========================================================================

    /**
     * Can this NPC court someone?
     * Must be an adult HEAD without a spouse.
     */
    public boolean canCourt(LifeStage stage) {
        return stage == LifeStage.ADULT
                && familyRole == FamilyRole.HEAD
                && spouseId == null;
    }

    /**
     * Is this NPC eligible to be courted?
     * Must be an adult without a spouse, not a HEAD (avoids two heads merging).
     */
    public boolean isCourtable(LifeStage stage) {
        return stage == LifeStage.ADULT
                && spouseId == null
                && familyRole != FamilyRole.HEAD;
    }

    /**
     * Can this household produce a child?
     *
     * @param maxChildren  maximum children per household
     * @param parentMinAge minimum age in days before children are possible
     * @param npcAge       current age in days
     * @param stage        current life stage
     */
    public boolean canHaveChild(int maxChildren, int parentMinAge,
                                int npcAge, LifeStage stage) {
        return familyRole == FamilyRole.HEAD
                && spouseId != null
                && houseId != null
                && stage == LifeStage.ADULT
                && npcAge >= parentMinAge
                && childrenIds.size() < maxChildren;
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    private static final String TAG_ROLE = "familyRole";
    private static final String TAG_HOUSE = "houseId";
    private static final String TAG_SPOUSE = "spouseId";
    private static final String TAG_HEAD = "headOfHouseholdId";
    private static final String TAG_CHILDREN = "childrenIds";

    public void save(CompoundTag tag) {
        tag.putString(TAG_ROLE, familyRole.name());
        if (houseId != null) tag.putString(TAG_HOUSE, houseId.toString());
        if (spouseId != null) tag.putString(TAG_SPOUSE, spouseId.toString());
        if (headOfHouseholdId != null) tag.putString(TAG_HEAD, headOfHouseholdId.toString());
        if (!childrenIds.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < childrenIds.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(childrenIds.get(i).toString());
            }
            tag.putString(TAG_CHILDREN, sb.toString());
        }
    }

    public void load(CompoundTag tag) {
        if (tag.contains(TAG_ROLE)) {
            try {
                familyRole = FamilyRole.valueOf(tag.getString(TAG_ROLE).orElse(""));
            } catch (IllegalArgumentException e) {
                familyRole = FamilyRole.UNASSIGNED;
            }
        }
        if (tag.contains(TAG_HOUSE)) {
            try { houseId = UUID.fromString(tag.getString(TAG_HOUSE).orElse("")); }
            catch (IllegalArgumentException ignored) {}
        }
        if (tag.contains(TAG_SPOUSE)) {
            try { spouseId = UUID.fromString(tag.getString(TAG_SPOUSE).orElse("")); }
            catch (IllegalArgumentException ignored) {}
        }
        if (tag.contains(TAG_HEAD)) {
            try { headOfHouseholdId = UUID.fromString(tag.getString(TAG_HEAD).orElse("")); }
            catch (IllegalArgumentException ignored) {}
        }
        if (tag.contains(TAG_CHILDREN)) {
            childrenIds.clear();
            String raw = tag.getString(TAG_CHILDREN).orElse("");
            if (!raw.isEmpty()) {
                for (String id : raw.split(",")) {
                    try { childrenIds.add(UUID.fromString(id)); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
        }
    }
}