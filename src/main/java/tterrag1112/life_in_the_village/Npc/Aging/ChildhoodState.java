package tterrag1112.life_in_the_village.Npc.Aging;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-NPC childhood/teen state. Spec line 43.
 *
 * <p>Lives in the entity's save subtree under {@code npcChildhood}.
 * Initialised when the NPC enters CHILD via
 * {@link ChildhoodInitializer}; cleared on TEEN→ADULT transition.</p>
 */
public final class ChildhoodState {

    public static final int MAX_INTERESTED_SKILLS = 3;
    public static final int DEFAULT_SKILL_BUDGET = 10;

    private static final String SAVE_KEY = "npcChildhood";

    private UUID primaryCaregiverId;
    private UUID closestFriendId;
    private final List<ChildhoodSkill> interestedSkills = new ArrayList<>();
    private boolean schoolingActive;
    private ProfessionPreference preferredProfession =
            new ProfessionPreference(null, 0);
    private int skillPoints = DEFAULT_SKILL_BUDGET;

    public ChildhoodState() {}

    public Optional<UUID> primaryCaregiverId() { return Optional.ofNullable(primaryCaregiverId); }
    public Optional<UUID> closestFriendId()    { return Optional.ofNullable(closestFriendId); }
    public List<ChildhoodSkill> interestedSkills() {
        return Collections.unmodifiableList(interestedSkills);
    }
    public boolean schoolingActive()        { return schoolingActive; }
    public ProfessionPreference preferredProfession() { return preferredProfession; }
    public int skillPoints()                { return skillPoints; }
    public boolean hasPreference()          { return preferredProfession.preferred()
            != tterrag1112.life_in_the_village.Profession.Profession.NONE; }

    // ── Mutations ──────────────────────────────────────────────────────────

    public void setPrimaryCaregiverId(UUID id) { this.primaryCaregiverId = id; }
    public void setClosestFriendId(UUID id)    { this.closestFriendId = id; }
    public void setSchoolingActive(boolean v)  { this.schoolingActive = v; }
    public void setPreferredProfession(ProfessionPreference p) {
        if (p != null) this.preferredProfession = p;
    }
    public void setSkillPoints(int points) { this.skillPoints = Math.max(0, points); }

    public void addInterestedSkill(Skill skill, int interest) {
        if (interestedSkills.size() >= MAX_INTERESTED_SKILLS) return;
        interestedSkills.add(new ChildhoodSkill(skill, interest));
    }

    public boolean hasInterestIn(Skill skill) {
        return interestedSkills.stream().anyMatch(c -> c.skill() == skill);
    }

    /** Replace the entire interested-skills list (used by initialiser). */
    public void setInterestedSkills(List<ChildhoodSkill> skills) {
        interestedSkills.clear();
        if (skills != null) {
            for (ChildhoodSkill s : skills) {
                if (interestedSkills.size() >= MAX_INTERESTED_SKILLS) break;
                interestedSkills.add(s);
            }
        }
    }

    // ── Persistence ────────────────────────────────────────────────────────

    public void save(ValueOutput output) {
        output.store(SAVE_KEY, CODEC, this);
    }

    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        ChildhoodState loaded = read.get();
        primaryCaregiverId = loaded.primaryCaregiverId;
        closestFriendId = loaded.closestFriendId;
        interestedSkills.clear();
        interestedSkills.addAll(loaded.interestedSkills);
        schoolingActive = loaded.schoolingActive;
        preferredProfession = loaded.preferredProfession;
        skillPoints = loaded.skillPoints;
        return true;
    }

    public static final Codec<ChildhoodState> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.optionalFieldOf("primaryCaregiverId").forGetter(s ->
                    Optional.ofNullable(s.primaryCaregiverId)),
            UUIDUtil.CODEC.optionalFieldOf("closestFriendId").forGetter(s ->
                    Optional.ofNullable(s.closestFriendId)),
            ChildhoodSkill.CODEC.listOf().optionalFieldOf("interestedSkills", List.of())
                    .forGetter(s -> List.copyOf(s.interestedSkills)),
            Codec.BOOL.optionalFieldOf("schoolingActive", false)
                    .forGetter(s -> s.schoolingActive),
            ProfessionPreference.CODEC.optionalFieldOf("preferredProfession",
                    new ProfessionPreference(
                            tterrag1112.life_in_the_village.Profession.Profession.NONE, 0))
                    .forGetter(s -> s.preferredProfession),
            Codec.INT.optionalFieldOf("skillPoints", DEFAULT_SKILL_BUDGET)
                    .forGetter(s -> s.skillPoints)
    ).apply(i, ChildhoodState::fromCodec));

    public static ChildhoodState fromCodec(Optional<UUID> caregiver,
                                           Optional<UUID> friend,
                                           List<ChildhoodSkill> skills,
                                           boolean schoolingActive,
                                           ProfessionPreference pref,
                                           int points) {
        ChildhoodState s = new ChildhoodState();
        s.primaryCaregiverId = caregiver.orElse(null);
        s.closestFriendId = friend.orElse(null);
        s.interestedSkills.addAll(skills);
        s.schoolingActive = schoolingActive;
        s.preferredProfession = pref;
        s.skillPoints = Math.max(0, points);
        return s;
    }
}
