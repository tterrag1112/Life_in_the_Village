// src/main/java/tterrag1112/life_in_the_village/Village/Social/HouseholdManager.java
package tterrag1112.life_in_the_village.Entities;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Creates and maintains {@link HouseholdData} records for a village.
 *
 * <h3>Called from</h3>
 * {@link tterrag1112.life_in_the_village.Village.VillageSpawner} — call
 * {@link #buildHouseholdsForVillage} after NPC spawning is complete and all
 * house assignments have been made.
 *
 * <h3>Runtime updates</h3>
 * The on-death / on-move hooks keep households accurate over time without
 * requiring a full rebuild.
 */
public final class HouseholdManager {

    private HouseholdManager() {}

    // =========================================================================
    // Spawn-time grouping pass
    // =========================================================================

    /**
     * Scans all house buildings in the village, finds which NPCs live in each,
     * and creates a {@link HouseholdData} record per occupied house.
     *
     * <p>Call this once per village after all NPCs have been spawned and
     * assigned to houses — typically at the very end of
     * {@code VillageSpawner.spawnVillage()}, before returning the village.</p>
     *
     * @param level   server level (for NPC entity lookup)
     * @param village the newly spawned village
     * @param data    saved data (modified in place)
     */
    public static void buildHouseholdsForVillage(ServerLevel level,
                                                 Village village,
                                                 VillageSavedData data) {
        long tick = level.getGameTime();

        // Find all HOUSE buildings in this village
        List<Building> houses = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == BuildingType.HOUSE)
                .collect(Collectors.toList());

        if (houses.isEmpty()) return;

        // Find all TownspersonMob NPCs assigned to this village
        List<TownspersonMob> villageNpcs = level.getEntitiesOfClass(
                TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> b.inflate(64))
                        .orElse(new net.minecraft.world.phys.AABB(0, 0, 0, 0, 0, 0)),
                mob -> mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        );

        // Group NPCs by their assigned house
        Map<UUID, List<TownspersonMob>> byHouse = new HashMap<>();
        for (TownspersonMob npc : villageNpcs) {
            npc.getHouseId().ifPresent(houseId ->
                    byHouse.computeIfAbsent(houseId, k -> new ArrayList<>()).add(npc)
            );
        }

        int created = 0;
        for (Building house : houses) {
            List<TownspersonMob> residents = byHouse.getOrDefault(
                    house.getId(), Collections.emptyList());

            if (residents.isEmpty()) continue;

            // Sort: adults first, children last
            residents.sort(Comparator.comparingInt(npc ->
                    npc.isChild() ? 1 : 0));

            List<UUID> memberIds = residents.stream()
                    .map(TownspersonMob::getUUID)
                    .collect(Collectors.toList());

            int childCount = (int) residents.stream()
                    .filter(TownspersonMob::isChild)
                    .count();

            HouseholdData household = HouseholdData.create(
                    house.getId(), memberIds, childCount, tick);

            data.addHousehold(household);
            created++;
        }

        System.out.println("HouseholdManager: created " + created
                + " households in " + village.getName());
    }

    // =========================================================================
    // Runtime hooks
    // =========================================================================

    /**
     * Called when an NPC dies. Removes them from their household.
     * If the household becomes empty, it is dissolved.
     *
     * @param npcId  UUID of the deceased NPC
     * @param data   saved data
     */
    public static void onNpcDied(UUID npcId, VillageSavedData data) {
        data.getHouseholdForNpc(npcId).ifPresent(household -> {
            household.removeMember(npcId);
            if (household.isEmpty()) {
                data.removeHousehold(household.getHouseholdId());
            }
            data.markDirty();
        });
    }

    /**
     * Called when an NPC moves to a new house (e.g. after marriage or
     * a house assignment change). Moves them from the old household to
     * a new or existing one for the target building.
     *
     * @param npcId          UUID of the moving NPC
     * @param newBuildingId  UUID of the new house building
     * @param isChild        true if the NPC is a child
     * @param data           saved data
     * @param tick           current game tick
     */
    public static void onNpcMovedHouse(UUID npcId,
                                       UUID newBuildingId,
                                       boolean isChild,
                                       VillageSavedData data,
                                       long tick) {
        // Remove from old household
        data.getHouseholdForNpc(npcId).ifPresent(old -> {
            old.removeMember(npcId);
            if (old.isEmpty()) data.removeHousehold(old.getHouseholdId());
        });

        // Add to or create new household
        Optional<HouseholdData> existing =
                data.getHouseholdForBuilding(newBuildingId);

        if (existing.isPresent()) {
            existing.get().addMember(npcId);
        } else {
            HouseholdData household = HouseholdData.create(
                    newBuildingId,
                    List.of(npcId),
                    isChild ? 1 : 0,
                    tick);
            data.addHousehold(household);
        }

        data.markDirty();
    }

    /**
     * Called when a new NPC is born (SeekHouseGoal assigns them to a house).
     * Adds them to the household as a child.
     */
    public static void onNpcBorn(UUID childId,
                                 UUID houseId,
                                 VillageSavedData data,
                                 long tick) {
        onNpcMovedHouse(childId, houseId, true, data, tick);
    }

    // =========================================================================
    // Household-aware social behaviour helpers
    // =========================================================================

    /**
     * Returns a random household member other than the given NPC who is
     * currently alive and awake (not sleeping). Used by
     * {@code SocialWalkGoal} to find a walk partner.
     *
     * @param npc    the requesting NPC
     * @param level  server level
     * @param data   saved data
     * @return an optional partner NPC
     */
    public static Optional<TownspersonMob> findWalkPartner(
            TownspersonMob npc,
            ServerLevel level,
            VillageSavedData data) {

        return npc.getHouseId()
                .flatMap(data::getHouseholdForBuilding)
                .flatMap(household -> {
                    List<UUID> candidates = household.getMemberNpcIds().stream()
                            .filter(id -> !id.equals(npc.getUUID()))
                            .collect(Collectors.toList());

                    if (candidates.isEmpty()) return Optional.empty();

                    // Shuffle so we don't always pick the same partner
                    Collections.shuffle(candidates);

                    return candidates.stream()
                            .map(id -> TownspersonMob.findByUUID(level, id))
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .filter(partner -> !partner.isSleepTime()
                                    && partner.isAlive())
                            .findFirst();
                });
    }

    /**
     * Returns all household members of the same house as the given NPC,
     * including the NPC itself. Used by EatMealGoal to find dining companions.
     */
    public static List<TownspersonMob> getHouseholdMembers(
            TownspersonMob npc,
            ServerLevel level,
            VillageSavedData data) {

        return npc.getHouseId()
                .flatMap(data::getHouseholdForBuilding)
                .map(household -> household.getMemberNpcIds().stream()
                        .map(id -> TownspersonMob.findByUUID(level, id))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(net.minecraft.world.entity.LivingEntity::isAlive)
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());
    }
}