package tterrag1112.life_in_the_village.Village.Buildings.Inhabitants;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Entities.ModEntities;
import tterrag1112.life_in_the_village.Entities.NpcNameRegistry;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * Walks the placed buildings of a freshly-spawned village and populates
 * each one with the NPCs declared by its
 * {@link BuildingInhabitantSpec}. Forms households inline as houses are
 * processed — no separate post-pass needed.
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li>For every placed building, look up its inhabitant spec.</li>
 *   <li>For each inhabitant in the spec, roll its chance and spawn the
 *       NPC inside the building if the roll succeeds.</li>
 *   <li>If the inhabitant is a household head, optionally spawn a spouse
 *       and children with the same surname.</li>
 *   <li>If any NPCs ended up living in the building, register a
 *       {@link HouseholdData} for it immediately.</li>
 * </ol>
 *
 * <h3>What this replaces</h3>
 * The old {@code VillageSpawner.spawnNpcs} method, which read a flat
 * NPC list from the village type JSON and distributed NPCs across
 * building instances by index. The new flow is building-driven:
 * a building exists → it gets its NPCs. Buildings that failed to
 * place produce zero NPCs, eliminating the "EMERGENCY placing missing X"
 * code path entirely.
 */
public final class VillageInhabitantPopulator {

    private VillageInhabitantPopulator() {}

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Populates a village's buildings with their inhabitants.
     * Should be called from {@code VillageSpawner} after building
     * placement is complete and before decoration.
     */
    public static void populate(ServerLevel level,
                                Village village,
                                VillageSavedData data,
                                Map<BuildingType, List<Building>> placedBuildingsAll,
                                Random rng) {

        long tick = level.getGameTime();
        int totalNpcs = 0;
        int totalHouseholds = 0;

        for (Map.Entry<BuildingType, List<Building>> entry
                : placedBuildingsAll.entrySet()) {
            BuildingType type = entry.getKey();
            BuildingInhabitantSpec spec =
                    BuildingInhabitantRegistry.get(type);
            if (spec.isEmpty()) continue;

            for (Building building : entry.getValue()) {
                int spawned = populateBuilding(
                        level, village, data, building, spec, rng, tick);
                totalNpcs += spawned;
                if (spawned > 0 && (type == BuildingType.HOUSE
                        || type == BuildingType.NOBLE_MANOR
                        || type == BuildingType.FARMHOUSE)) {
                    totalHouseholds++;
                }
            }
        }

        System.out.println("VillageInhabitantPopulator: '" + village.getName()
                + "' populated with " + totalNpcs + " NPCs across "
                + totalHouseholds + " households");
    }

    // =========================================================================
    // Per-building population
    // =========================================================================

    /**
     * Spawns the inhabitants of one building. If any of those inhabitants
     * live in the building, also creates a {@link HouseholdData} for it.
     *
     * @return total NPCs spawned for this building
     */
    private static int populateBuilding(ServerLevel level,
                                        Village village,
                                        VillageSavedData data,
                                        Building building,
                                        BuildingInhabitantSpec spec,
                                        Random rng,
                                        long tick) {
        List<TownspersonMob> spawnedHere    = new ArrayList<>();
        List<TownspersonMob> houseResidents = new ArrayList<>();
        int childCount = 0;

        for (BuildingInhabitantSpec.Inhabitant inh : spec.getInhabitants()) {
            // Roll the chance
            if (inh.chance() < 1.0f && rng.nextFloat() > inh.chance()) continue;

            // ── Spawn the head NPC ──────────────────────────────────────────
            TownspersonMob head = spawnNpcInBuilding(
                    level, building, village, inh.profession(),
                    inh.familyRole(), rng);
            if (head == null) continue;

            spawnedHere.add(head);
            String surname = extractSurname(head);

            if (inh.livesHere()) {
                head.setHouseId(building.getId());
                houseResidents.add(head);
            }

            // ── Optional spouse ─────────────────────────────────────────────
            TownspersonMob spouse = null;
            if (inh.spouseChance() > 0
                    && rng.nextFloat() < inh.spouseChance()) {
                spouse = spawnNpcInBuilding(
                        level, building, village,
                        // Spouse is a citizen unless we have richer rules later
                        inh.profession() == Profession.NONE
                                ? Profession.NONE
                                : Profession.CITIZEN,
                        FamilyRole.SPOUSE, rng);
                if (spouse != null) {
                    applySurname(spouse, surname);
                    spawnedHere.add(spouse);
                    if (inh.livesHere()) {
                        spouse.setHouseId(building.getId());
                        houseResidents.add(spouse);
                    }
                    head.setSpouseId(spouse.getUUID());
                    spouse.setSpouseId(head.getUUID());
                }
            }

            // ── Optional children ───────────────────────────────────────────
            for (int c = 0; c < inh.maxChildren(); c++) {
                if (rng.nextFloat() > inh.childChanceEach()) continue;
                TownspersonMob child = spawnNpcInBuilding(
                        level, building, village,
                        Profession.NONE, FamilyRole.CHILD, rng);
                if (child == null) continue;

                applySurname(child, surname);
                child.setHeadOfHouseholdId(head.getUUID());
                head.addChildId(child.getUUID());
                if (spouse != null) spouse.addChildId(child.getUUID());

                if (inh.livesHere()) {
                    child.setHouseId(building.getId());
                    houseResidents.add(child);
                    childCount++;
                }
                spawnedHere.add(child);
            }
        }

        // ── Form a household if anybody lives here ──────────────────────────
        if (!houseResidents.isEmpty()) {
            List<UUID> memberIds = new ArrayList<>();
            for (TownspersonMob npc : houseResidents) memberIds.add(npc.getUUID());
            HouseholdData household = HouseholdData.create(
                    building.getId(), memberIds, childCount, tick);
            // B2.6 — pull the rolled HOMESTEAD_* type off the
            // building's AdjunctPlot (if one was reserved for HOUSE
            // by PhasedPlanner) so the household goal stack knows
            // which homestead handler to dispatch.
            for (var plot : data.getAdjunctPlotsForBuilding(building.getId())) {
                if (plot.type().name().startsWith("HOMESTEAD_")) {
                    household.setHomesteadPlotType(plot.type());
                    break;
                }
            }
            data.addHousehold(household);
            data.markDirty();
        }

        return spawnedHere.size();
    }

    // =========================================================================
    // NPC spawn helper
    // =========================================================================

    private static TownspersonMob spawnNpcInBuilding(ServerLevel level,
                                                     Building building,
                                                     Village village,
                                                     Profession profession,
                                                     FamilyRole familyRole,
                                                     Random rng) {
        BlockPos spawnPos = randomPosInsideBuilding(level, building, rng);

        try {
            TownspersonMob npc = ModEntities.TOWNSPERSON.get()
                    .create(level, EntitySpawnReason.MOB_SUMMONED);
            if (npc == null) return null;

            npc.setPos(spawnPos.getX() + 0.5,
                    spawnPos.getY(),
                    spawnPos.getZ() + 0.5);
            npc.setYRot(rng.nextFloat() * 360);
            npc.setXRot(0);

            npc.finalizeSpawn(level,
                    level.getCurrentDifficultyAt(spawnPos),
                    EntitySpawnReason.MOB_SUMMONED, null);

            // Phase 6.3.3.a — gated, OTHER/SYSTEM (worldgen NPC population).
            tterrag1112.life_in_the_village.Npc.Career.CareerTransitions.changeProfession(
                    npc, profession,
                    tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Reason.OTHER,
                    tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Source.SYSTEM);
            npc.setFamilyRole(familyRole);
            npc.assignToBuilding(building.getId(), village.getName());

            // Phase 2 task 17 — scribal professions need a literacy
            // top-up so the spec's hire gate (LITERACY ≥ 50/60/80) is
            // already met for bootstrap villagers.
            tterrag1112.life_in_the_village.Profession.ProfessionRequirements
                    .ensureLiteracyForBootstrap(npc, profession, level.getGameTime());

            // Starter wealth + items are now paid centrally by
            // setProfession on its first non-NONE invocation
            // (see TownspersonMob#applyStarterFor). Removed the
            // populator-side duplicate so other spawn paths
            // (VillageLeaderGoal, PostJobGoal, AdventurerSavedData,
            // /summon TOWNSPERSON, etc.) get the same treatment.

            level.addFreshEntity(npc);
            return npc;

        } catch (Exception e) {
            System.out.println("VillageInhabitantPopulator: NPC spawn failed — "
                    + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Surname utilities
    // =========================================================================

    private static String extractSurname(TownspersonMob npc) {
        String name = npc.getNpcName();
        if (name == null) return NpcNameRegistry.INSTANCE
                .generateSurname(npc.getRandom());
        int sp = name.indexOf(' ');
        return sp >= 0 ? name.substring(sp + 1) : name;
    }

    private static void applySurname(TownspersonMob npc, String surname) {
        String name = npc.getNpcName();
        if (name == null) return;
        int sp = name.indexOf(' ');
        String first = sp >= 0 ? name.substring(0, sp) : name;
        npc.setNpcName(first + " " + surname);
    }

    // =========================================================================
    // Building interior position picker
    // =========================================================================

    private static BlockPos randomPosInsideBuilding(ServerLevel level,
                                                    Building building,
                                                    Random rng) {
        BlockPos min = building.getShape().getMin();
        int w = building.getShape().getWidth();
        int l = building.getShape().getLength();
        int floorY = building.getShape().getOrigin().getY();

        for (int attempt = 0; attempt < 10; attempt++) {
            int x = min.getX() + 1 + rng.nextInt(Math.max(1, w - 2));
            int z = min.getZ() + 1 + rng.nextInt(Math.max(1, l - 2));
            BlockPos candidate = new BlockPos(x, floorY + 1, z);
            if (level.getBlockState(candidate).isAir()
                    && level.getBlockState(candidate.above()).isAir()) {
                return candidate;
            }
        }
        return building.getShape().getOrigin().offset(w / 2, 1, l / 2);
    }
}