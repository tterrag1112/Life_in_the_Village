package tterrag1112.life_in_the_village.Profession;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Profession.PlayerWorkplace.WorkplaceEntry;

import java.util.*;

public class PlayerProfessionData {

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
                                    .forGetter(d -> d.workplaces)
                    ).apply(instance,
                            PlayerProfessionData::fromCodec));

    private static PlayerProfessionData fromCodec(
            Map<PlayerProfession, Integer> xpMap,
            Optional<PlayerProfession> active,
            Map<PlayerProfession, WorkplaceEntry> workplaces) {
        PlayerProfessionData data = new PlayerProfessionData();
        data.professionXp.putAll(xpMap);
        data.activeProfession = active.orElse(null);
        data.workplaces.putAll(workplaces);
        return data;
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Map<PlayerProfession, Integer> professionXp
            = new HashMap<>();
    private PlayerProfession activeProfession = null;
    private final Map<PlayerProfession, WorkplaceEntry>
            workplaces = new HashMap<>();

    // -------------------------------------------------------------------------
    // XP management
    // -------------------------------------------------------------------------

    public void addXp(PlayerProfession profession,
                      int amount) {
        int current = professionXp.getOrDefault(
                profession, 0);
        professionXp.put(profession, current + amount);
    }

    public int getXp(PlayerProfession profession) {
        return professionXp.getOrDefault(profession, 0);
    }

    public int getLevel(PlayerProfession profession) {
        return profession.getLevelFromXp(
                getXp(profession));
    }

    public String getLevelName(PlayerProfession profession) {
        return profession.getLevelName(getXp(profession));
    }

    public boolean isMaxLevel(PlayerProfession profession) {
        return profession.isMaxLevel(getXp(profession));
    }

    // -------------------------------------------------------------------------
    // Active profession
    // -------------------------------------------------------------------------

    public Optional<PlayerProfession> getActiveProfession() {
        return Optional.ofNullable(activeProfession);
    }

    public void setActiveProfession(
            PlayerProfession profession) {
        this.activeProfession = profession;
    }

    public void clearActiveProfession() {
        this.activeProfession = null;
    }

    // -------------------------------------------------------------------------
    // Workplaces
    // -------------------------------------------------------------------------

    public Optional<WorkplaceEntry> getWorkplace(
            PlayerProfession profession) {
        return Optional.ofNullable(
                workplaces.get(profession));
    }

    public void setWorkplace(PlayerProfession profession,
                             WorkplaceEntry entry) {
        workplaces.put(profession, entry);
    }

    public void removeWorkplace(
            PlayerProfession profession) {
        workplaces.remove(profession);
    }

    public Map<PlayerProfession, WorkplaceEntry>
    getAllWorkplaces() {
        return Collections.unmodifiableMap(workplaces);
    }

    public boolean hasWorkplace(
            PlayerProfession profession) {
        return workplaces.containsKey(profession);
    }

    // -------------------------------------------------------------------------
    // Unlock checks
    // -------------------------------------------------------------------------

    public double getTradeDiscount(
            PlayerProfession profession) {
        return switch (getLevel(profession)) {
            case 0 -> 0.0;
            case 1 -> 0.05;
            case 2 -> 0.10;
            case 3 -> 0.15;
            case 4 -> 0.20;
            default -> 0.0;
        };
    }

    public double getYieldBonus(
            PlayerProfession profession) {
        return switch (getLevel(profession)) {
            case 0 -> 0.0;
            case 1 -> 0.1;
            case 2 -> 0.25;
            case 3 -> 0.50;
            case 4 -> 1.0;
            default -> 0.0;
        };
    }

    public boolean canHireWorkers(
            PlayerProfession profession) {
        return getLevel(profession) >= 3;
    }

    public boolean hasUnlockedRecipes(
            PlayerProfession profession) {
        return getLevel(profession) >= 2;
    }

    public int getNpcRespectBonus(
            PlayerProfession profession) {
        return getLevel(profession) * 5;
    }
}