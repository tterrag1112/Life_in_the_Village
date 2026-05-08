package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.JobPosting;

import java.util.*;
import java.util.stream.Collectors;

public class PostJobGoal extends Goal {

    private static final int CHECK_INTERVAL = 1200; // check every minute
    private static final long DAILY_WAGE_BRONZE = 32; // half a silver per day
    private static final int MAX_FARMHANDS = 3;

    private final TownspersonMob entity;
    private int checkTimer = 0;

    public PostJobGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.noneOf(Flag.class)); // doesn't block movement
    }

    @Override
    public boolean canUse() {
        if (!entity.isWorkTime()) return false;

        return entity.getProfession() == Profession.FARMER;
    }

    @Override
    public boolean canContinueToUse() {
        if (!entity.isWorkTime()) return false;

        return true; }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        checkTimer++;
        if (checkTimer < CHECK_INTERVAL) return;
        checkTimer = 0;

        VillageSavedData data = VillageSavedData.get(level);
        Building farmhouse = findFarmhouse(level, data);
        if (farmhouse == null) return;

        long currentTick = level.getGameTime();

        // Process any postings this farmer already made
        processExistingPostings(level, data, farmhouse, currentTick);

        // Decide whether to post a new job
        maybePostNewJob(level, data, farmhouse, currentTick);
    }

    private void processExistingPostings(ServerLevel level, VillageSavedData data,
                                         Building farmhouse, long currentTick) {
        List<JobPosting> myPostings = data.getPostingsByPoster(entity.getUUID())
                .stream()
                .filter(p -> p.getStatus() == JobPosting.PostingStatus.OPEN
                        || p.getStatus() == JobPosting.PostingStatus.REVIEWING)
                .collect(Collectors.toList());

        System.out.println("PostJob data instance: " + System.identityHashCode(data));
        for (JobPosting posting : myPostings) {
            System.out.println("PostJob posting instance: " + System.identityHashCode(posting)
                    + " applicants: " + posting.getApplicantIds().size());

            switch (posting.getStatus()) {
                case OPEN -> {
                    if (posting.isReadyToReview(currentTick)) {
                        posting.startReview(currentTick);
                        data.setDirty();
                        System.out.println("Farmer: starting review for posting "
                                + posting.getId());
                    }
                }
                case REVIEWING -> {
                    System.out.println("  Review started at " + posting.getReviewStartTick()
                            + " complete=" + posting.isReviewComplete(currentTick));
                    if (posting.isReviewComplete(currentTick)) {
                        selectBestApplicant(level, data, posting);
                    }
                }
            }
        }
    }

    private boolean farmerCanAffordHire(ServerLevel level, Building farmhouse) {
        CurrencyValue weeklyWage = CurrencyValue.of(JobPosting.DAILY_WAGE_BRONZE_DEFAULT * 7);

        // Check personal inventory first
        if (entity.canAfford(weeklyWage)) return true;

        // Also check farmhouse chest
        long chestWealth = 0;
        for (Item coinItem : List.of(
                ModItems.DENIER_OR.get(),
                ModItems.DENIER_ARGENT.get(),
                ModItems.DENIER.get())) {
            int count = countItemInBuilding(level, farmhouse, coinItem);
            chestWealth += (long) count * CurrencyValue.valuePerCoin(coinItem);
        }

        return entity.getWealth().add(CurrencyValue.of(chestWealth)).isAffordable(weeklyWage);
    }

    private int countItemInBuilding(ServerLevel level, Building building, Item item) {
        int total = 0;
        for (var container : BuildingStorageAccess.findInventories(level, building)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.is(item)) total += stack.getCount();
            }
        }
        return total;
    }

    private void selectBestApplicant(ServerLevel level, VillageSavedData data,
                                     JobPosting posting) {
        if (posting.getApplicantIds().isEmpty()) {
            // No applicants — cancel and repost later
            posting.setStatus(JobPosting.PostingStatus.CANCELLED);
            data.removeJobPosting(posting.getId());
            System.out.println("Farmer: no applicants, cancelling posting");
            return;
        }

        // Find all applicant entities still alive and nearby
        List<TownspersonMob> applicants = posting.getApplicantIds().stream()
                .map(id -> findEntityByUUID(level, id))
                .filter(Objects::nonNull)
                .filter(mob -> mob.getAssignedBuildingId().isEmpty()) // still unemployed
                .collect(Collectors.toList());

        if (applicants.isEmpty()) {
            posting.setStatus(JobPosting.PostingStatus.CANCELLED);
            data.removeJobPosting(posting.getId());
            return;
        }

        // Pick the closest applicant as "best"
        TownspersonMob best = applicants.stream()
                .min(Comparator.comparingDouble(
                        mob -> mob.distanceToSqr(entity)
                ))
                .orElse(null);

        if (best == null) return;

        // Hire the best applicant
        hireFarmhand(level, data, posting, best);
    }

    private void hireFarmhand(ServerLevel level, VillageSavedData data,
                              JobPosting posting, TownspersonMob farmhand) {
        Building farmhouse = data.getBuildingById(posting.getFarmhouseBuildingId())
                .orElse(null);
        if (farmhouse == null) return;

        // Set profession and assign to farmhouse
        farmhand.setProfession(Profession.FARMHAND);
        farmhand.setAssignedBuildingId(farmhouse.getId());
        farmhand.setAssignedVillageName(entity.getAssignedVillageName().orElse(""));

        // Assign to a plot that needs help
        assignPlotToFarmhand(level, data, farmhouse, farmhand);

        // Mark posting as filled
        posting.setStatus(JobPosting.PostingStatus.FILLED);
        data.removeJobPosting(posting.getId());

        System.out.println("Farmer: hired " + farmhand.getUUID()
                + " as farmhand for " + farmhouse.getName());
    }

    private void assignPlotToFarmhand(ServerLevel level, VillageSavedData data,
                                      Building farmhouse, TownspersonMob farmhand) {
        // Find the plot with the most unharvested crops
        List<FarmPlot> plots = data.getFarmPlotsForFarmhouse(farmhouse.getId());

        FarmPlot bestPlot = plots.stream()
                .max(Comparator.comparingInt(plot ->
                        countMatureCrops(level, plot)
                ))
                .orElse(null);

        if (bestPlot != null) {
            farmhand.setAssignedPlotId(bestPlot.getId());
            System.out.println("Farmer: assigned farmhand to plot "
                    + bestPlot.getName());
        }
    }

    private void maybePostNewJob(ServerLevel level, VillageSavedData data,
                                 Building farmhouse, long currentTick) {
        // Count current farmhands
        int currentFarmhands = countFarmhands(level, farmhouse);
        if (currentFarmhands >= MAX_FARMHANDS) return;

        // Check if we already have an open posting
        boolean hasOpenPosting = data.getPostingsByPoster(entity.getUUID())
                .stream()
                .anyMatch(p -> p.getStatus() == JobPosting.PostingStatus.OPEN
                        || p.getStatus() == JobPosting.PostingStatus.REVIEWING);
        if (hasOpenPosting) return;

        // Check if we can afford to pay a farmhand
        CurrencyValue weeklyWage = CurrencyValue.of(DAILY_WAGE_BRONZE * 7);
        if (!farmerCanAffordHire(level, farmhouse)) {
            System.out.println("Farmer: can't afford to hire farmhand");
            return;
        }

        // Check if we actually need help — more plots than we can handle alone
        int plotCount = data.getFarmPlotsForFarmhouse(farmhouse.getId()).size();
        if (plotCount <= 1 && currentFarmhands >= 1) return;

        // Post the job
        JobPosting posting = new JobPosting(
                UUID.randomUUID(),
                entity.getUUID(),
                farmhouse.getId(),
                JobPosting.JobType.FARMHAND,
                DAILY_WAGE_BRONZE,
                currentTick
        );
        data.addJobPosting(posting);
        // B2.9 — demoted to SLF4J debug. Posting is already
        // idempotent (the open-posting check at line ~218 returns
        // early); the per-60s steady-state log was confused for
        // hot-loop spam during testing.
        org.slf4j.LoggerFactory.getLogger(PostJobGoal.class).debug(
                "Farmer: posted farmhand job at tick {}", currentTick);
    }

    // --- Helpers ---

    private Building findFarmhouse(ServerLevel level, VillageSavedData data) {
        return entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(null);
    }

    private int countFarmhands(ServerLevel level, Building farmhouse) {
        return (int) level.getEntitiesOfClass(TownspersonMob.class,
                farmhouse.getShape().toAABB().inflate(64),
                mob -> mob.getProfession() == Profession.FARMHAND
                        && farmhouse.getId().equals(mob.getAssignedBuildingId().orElse(null))
        ).size();
    }

    private int countMatureCrops(ServerLevel level, FarmPlot plot) {
        int count = 0;
        for (BlockPos farmland : plot.getFarmlandBlocks(level)) {
            BlockPos cropPos = farmland.above();
            var state = level.getBlockState(cropPos);
            if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop) {
                if (crop.isMaxAge(state)) count++;
            }
        }
        return count;
    }

    private TownspersonMob findEntityByUUID(ServerLevel level, UUID uuid) {
        return level.getEntitiesOfClass(TownspersonMob.class,
                new net.minecraft.world.phys.AABB(
                        entity.getX() - 128, entity.getY() - 64,
                        entity.getZ() - 128, entity.getX() + 128,
                        entity.getY() + 64, entity.getZ() + 128
                ),
                mob -> mob.getUUID().equals(uuid)
        ).stream().findFirst().orElse(null);
    }
}
