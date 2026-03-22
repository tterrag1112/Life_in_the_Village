// src/main/java/tterrag1112/life_in_the_village/Profession/PlayerProfessionData.java
package tterrag1112.life_in_the_village.Profession;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Profession.PlayerWorkplace.WorkplaceEntry;

import java.util.*;

public class PlayerProfessionData {

    // -------------------------------------------------------------------------
    // Codec — extended to persist unlockedPerks and mentorNpcs
    // -------------------------------------------------------------------------

    public static final MapCodec<PlayerProfessionData> CODEC =
            RecordCodecBuilder.mapCodec(instance ->
                    instance.group(
                            Codec.unboundedMap(
                                            PlayerProfession.CODEC,
                                            Codec.INT)
                                    .optionalFieldOf(
                                            "professionXp",
                                            new HashMap<>())
                                    .forGetter(d -> d.professionXp),
                            PlayerProfession.CODEC
                                    .optionalFieldOf(
                                            "activeProfession")
                                    .forGetter(d -> Optional
                                            .ofNullable(
                                                    d.activeProfession)),
                            Codec.unboundedMap(
                                            PlayerProfession.CODEC,
                                            WorkplaceEntry.CODEC)
                                    .optionalFieldOf(
                                            "workplaces",
                                            new HashMap<>())
                                    .forGetter(d -> d.workplaces),
                            // NEW: persisted perk names
                            Codec.STRING.listOf()
                                    .optionalFieldOf(
                                            "unlockedPerks",
                                            new ArrayList<>())
                                    .forGetter(d -> d.unlockedPerks.stream()
                                            .map(ProfessionPerk::name)
                                            .toList()),
                            // NEW: mentor NPC UUIDs per profession
                            Codec.unboundedMap(
                                            PlayerProfession.CODEC,
                                            Codec.STRING.xmap(
                                                    UUID::fromString,
                                                    UUID::toString))
                                    .optionalFieldOf(
                                            "mentorNpcs",
                                            new HashMap<>())
                                    .forGetter(d -> d.mentorNpcs)
                    ).apply(instance,
                            PlayerProfessionData::fromCodec));

    private static PlayerProfessionData fromCodec(
            Map<PlayerProfession, Integer> xpMap,
            Optional<PlayerProfession> active,
            Map<PlayerProfession, WorkplaceEntry> workplaces,
            List<String> perkNames,
            Map<PlayerProfession, UUID> mentorNpcs) {

        PlayerProfessionData data = new PlayerProfessionData();
        data.professionXp.putAll(xpMap);
        data.activeProfession = active.orElse(null);
        data.workplaces.putAll(workplaces);
        data.mentorNpcs.putAll(mentorNpcs);

        for (String name : perkNames) {
            try {
                data.unlockedPerks.add(ProfessionPerk.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // Removed or renamed perk — skip gracefully
            }
        }
        return data;
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Map<PlayerProfession, Integer>      professionXp  = new HashMap<>();
    private       PlayerProfession                    activeProfession;
    private final Map<PlayerProfession, WorkplaceEntry> workplaces  = new HashMap<>();
    private final Set<ProfessionPerk>                 unlockedPerks = new HashSet<>();
    private final Map<PlayerProfession, UUID>         mentorNpcs    = new HashMap<>();

    // -------------------------------------------------------------------------
    // XP & Level
    // -------------------------------------------------------------------------

    public void addXp(PlayerProfession profession, int amount) {
        professionXp.merge(profession, amount, Integer::sum);
    }

    public int getXp(PlayerProfession profession) {
        return professionXp.getOrDefault(profession, 0);
    }

    public int getLevel(PlayerProfession profession) {
        return profession.getLevelFromXp(getXp(profession));
    }

    public String getLevelName(PlayerProfession profession) {
        return profession.getLevelName(getXp(profession));
    }

    public int getXpToNextLevel(PlayerProfession profession) {
        return profession.getXpToNextLevel(getXp(profession));
    }

    public boolean isMaxLevel(PlayerProfession profession) {
        return profession.isMaxLevel(getXp(profession));
    }

    // -------------------------------------------------------------------------
    // Yield bonus (legacy — kept for backwards compat with FarmerGoal etc.)
    // -------------------------------------------------------------------------

    /**
     * Returns the extra yield probability for the given profession
     * based on level alone (0.0 at Apprentice, up to 0.4 at Grandmaster).
     * Perks stack on top of this in ProfessionPerkManager.
     */
    public double getYieldBonus(PlayerProfession profession) {
        int level = getLevel(profession);
        return switch (level) {
            case 0  -> 0.00;
            case 1  -> 0.05;
            case 2  -> 0.15;
            case 3  -> 0.25;
            default -> 0.40;
        };
    }

    // -------------------------------------------------------------------------
    // Active profession
    // -------------------------------------------------------------------------

    public void setActiveProfession(PlayerProfession profession) {
        this.activeProfession = profession;
    }

    public Optional<PlayerProfession> getActiveProfession() {
        return Optional.ofNullable(activeProfession);
    }

    // -------------------------------------------------------------------------
    // Workplaces
    // -------------------------------------------------------------------------

    public Optional<WorkplaceEntry> getWorkplace(PlayerProfession profession) {
        return Optional.ofNullable(workplaces.get(profession));
    }

    public void setWorkplace(PlayerProfession profession, WorkplaceEntry entry) {
        workplaces.put(profession, entry);
    }

    public void removeWorkplace(PlayerProfession profession) {
        workplaces.remove(profession);
    }

    public Map<PlayerProfession, WorkplaceEntry> getAllWorkplaces() {
        return Collections.unmodifiableMap(workplaces);
    }

    // -------------------------------------------------------------------------
    // Perks
    // -------------------------------------------------------------------------

    /**
     * Grants a perk. Idempotent — calling multiple times is safe.
     */
    public void unlockPerk(ProfessionPerk perk) {
        unlockedPerks.add(perk);
    }

    /** Returns true if the player has unlocked the given perk. */
    public boolean hasUnlockedPerk(ProfessionPerk perk) {
        return unlockedPerks.contains(perk);
    }

    /** Returns an unmodifiable snapshot of all unlocked perks. */
    public Set<ProfessionPerk> getUnlockedPerks() {
        return Collections.unmodifiableSet(unlockedPerks);
    }

    // -------------------------------------------------------------------------
    // Mentor NPC links
    // -------------------------------------------------------------------------

    /**
     * Associates an NPC as the player's mentor for a specific profession.
     * The mentor relationship is used by {@code MentorCheckGoal} and grants
     * a passive XP bonus when working within 16 blocks of the mentor.
     *
     * @param profession the profession being mentored
     * @param npcId      UUID of the TownspersonMob acting as mentor
     */
    public void setMentor(PlayerProfession profession, UUID npcId) {
        mentorNpcs.put(profession, npcId);
    }

    /** Returns the mentor NPC UUID for the given profession, if any. */
    public Optional<UUID> getMentor(PlayerProfession profession) {
        return Optional.ofNullable(mentorNpcs.get(profession));
    }

    /** Removes the mentor link (e.g. when the NPC is removed or fired). */
    public void clearMentor(PlayerProfession profession) {
        mentorNpcs.remove(profession);
    }

    public Map<PlayerProfession, UUID> getAllMentors() {
        return Collections.unmodifiableMap(mentorNpcs);
    }

    /** Returns true if the player has an active workplace assignment for this profession. */
    public boolean hasWorkplace(PlayerProfession profession) {
        return workplaces.containsKey(profession);
    }
}