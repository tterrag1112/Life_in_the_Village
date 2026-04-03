// New file: src/main/java/tterrag1112/life_in_the_village/Entities/Goals/Profession/Farmer/FarmRoleManager.java

package tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class FarmRoleManager {

    public enum FarmRole {
        GENERALIST,      // 1-2 farmhands: does everything
        CROP_SPECIALIST, // 3-4: focuses on crops only
        ANIMAL_SPECIALIST, // 3-4: focuses on animals only
        HARVESTER,       // 5+: only harvests
        PLANTER,         // 5+: only plants
        MARKET_SELLER,   // 5+: only sells at market
        ANIMAL_TENDER,   // 5+: only tends animals
        FERTILIZER       // 5+: only fertilizes/maintains
    }

    /**
     * Assigns roles to all farmhands based on total count
     */
    public static void assignRoles(ServerLevel level, Building farmhouse) {
        VillageSavedData data = VillageSavedData.get(level);

        // Find all NPCs assigned to this farmhouse
        BlockPos origin = farmhouse.getShape().getOrigin();
        AABB searchArea = new AABB(origin).inflate(100);

        // Find all NPCs assigned to this farmhouse
        List<TownspersonMob> farmhands = level.getEntitiesOfClass(
                TownspersonMob.class,
                searchArea,
                npc -> npc.getAssignedBuildingId()
                        .map(id -> id.equals(farmhouse.getId()))
                        .orElse(false)
        );

        int totalFarmhands = farmhands.size();

        if (totalFarmhands == 0) return;

        // Get plots for this farmhouse
        List<FarmPlot> allPlots = data.getFarmPlotsForFarmhouse(farmhouse.getId());
        List<FarmPlot> cropPlots = allPlots.stream()
                .filter(p -> p.getSubtype() == FarmPlot.PlotSubtype.CROP_FIELD)
                .collect(Collectors.toList());
        List<FarmPlot> animalPens = allPlots.stream()
                .filter(p -> p.getSubtype() == FarmPlot.PlotSubtype.ANIMAL_PEN)
                .collect(Collectors.toList());

        // Assign roles based on count
        if (totalFarmhands <= 2) {
            // Everyone is a generalist
            for (TownspersonMob farmhand : farmhands) {
                setRole(farmhand, FarmRole.GENERALIST);
            }
        } else if (totalFarmhands <= 4) {
            // Specialize by crop vs animal
            int cropSpecialists = (int)(totalFarmhands * 0.6); // 60% crops

            for (int i = 0; i < farmhands.size(); i++) {
                TownspersonMob farmhand = farmhands.get(i);
                if (i < cropSpecialists) {
                    setRole(farmhand, FarmRole.CROP_SPECIALIST);
                    assignToPlot(farmhand, cropPlots, i);
                } else {
                    setRole(farmhand, FarmRole.ANIMAL_SPECIALIST);
                    assignToPlot(farmhand, animalPens, i - cropSpecialists);
                }
            }
        } else {
            // Full specialization
            int idx = 0;

            // 40% harvesters
            int harvesterCount = Math.max(1, (int)(totalFarmhands * 0.4));
            for (int i = 0; i < harvesterCount && idx < farmhands.size(); i++, idx++) {
                setRole(farmhands.get(idx), FarmRole.HARVESTER);
                assignToPlot(farmhands.get(idx), cropPlots, i);
            }

            // 20% planters
            int planterCount = Math.max(1, (int)(totalFarmhands * 0.2));
            for (int i = 0; i < planterCount && idx < farmhands.size(); i++, idx++) {
                setRole(farmhands.get(idx), FarmRole.PLANTER);
                assignToPlot(farmhands.get(idx), cropPlots, i);
            }

            // 20% animal tenders
            int tenderCount = Math.max(1, (int)(totalFarmhands * 0.2));
            for (int i = 0; i < tenderCount && idx < farmhands.size(); i++, idx++) {
                setRole(farmhands.get(idx), FarmRole.ANIMAL_TENDER);
                assignToPlot(farmhands.get(idx), animalPens, i);
            }

            // 10% market sellers
            int sellerCount = Math.max(1, (int)(totalFarmhands * 0.1));
            for (int i = 0; i < sellerCount && idx < farmhands.size(); i++, idx++) {
                setRole(farmhands.get(idx), FarmRole.MARKET_SELLER);
            }

            // Remaining are fertilizers
            while (idx < farmhands.size()) {
                setRole(farmhands.get(idx), FarmRole.FERTILIZER);
                idx++;
            }
        }

        System.out.println("FarmRoleManager: assigned roles to " + totalFarmhands
                + " farmhands for " + farmhouse.getName());
    }

    private static void setRole(TownspersonMob farmhand, FarmRole role) {
        // Store role in NPC data (you'll need to add this field to TownspersonMob)
        farmhand.getPersistentData().putString("FarmRole", role.name());
    }

    public static FarmRole getRole(TownspersonMob farmhand) {
        String roleStr = farmhand.getPersistentData().getString("FarmRole").orElse("");
        if (roleStr.isEmpty()) return FarmRole.GENERALIST;
        try {
            return FarmRole.valueOf(roleStr);
        } catch (IllegalArgumentException e) {
            return FarmRole.GENERALIST;
        }
    }

    private static void assignToPlot(TownspersonMob farmhand, List<FarmPlot> plots, int index) {
        if (plots.isEmpty()) return;
        FarmPlot plot = plots.get(index % plots.size());
        farmhand.setAssignedPlotId(plot.getId());
    }

    /**
     * Check if farmhand should perform a specific task based on their role
     */
    public static boolean canPerformTask(TownspersonMob farmhand, TaskType task) {
        FarmRole role = getRole(farmhand);

        return switch (role) {
            case GENERALIST -> true; // Can do anything
            case CROP_SPECIALIST -> task == TaskType.HARVEST || task == TaskType.PLANT;
            case ANIMAL_SPECIALIST -> task == TaskType.TEND_ANIMALS;
            case HARVESTER -> task == TaskType.HARVEST;
            case PLANTER -> task == TaskType.PLANT;
            case MARKET_SELLER -> task == TaskType.SELL;
            case ANIMAL_TENDER -> task == TaskType.TEND_ANIMALS;
            case FERTILIZER -> task == TaskType.FERTILIZE;
        };
    }

    public enum TaskType {
        HARVEST,
        PLANT,
        TEND_ANIMALS,
        SELL,
        FERTILIZE
    }
}