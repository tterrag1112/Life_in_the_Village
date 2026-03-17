package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.JobPosting;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class SeekJobGoal extends Goal {

    private static final int CHECK_INTERVAL = 2400; // check every 2 min
    private static final int WALK_TO_TOWN_HALL_TIMEOUT = 600;

    private final TownspersonMob entity;
    private int checkTimer = 0;
    private int walkTimer = 0;
    private JobPosting targetPosting = null;
    private Building townHall = null;
    private boolean hasApplied = false;

    public SeekJobGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!entity.isWorkTime()) return false;

        // Only unemployed townspeople seek jobs
        if (entity.getAssignedBuildingId().isPresent()) return false;
        if (entity.getProfession() != Profession.NONE) return false;

        checkTimer++;
        if (checkTimer < CHECK_INTERVAL) return false;
        checkTimer = 0;

        if (!(entity.level() instanceof ServerLevel level)) return false;

        VillageSavedData data = VillageSavedData.get(level);

        // Find a village this entity is near
        Optional<Village> village = findNearbyVillage(level, data);
        if (village.isEmpty()) return false;

        // Find an open job posting
        List<JobPosting> openPostings = data.getOpenPostingsForVillage(village.get())
                .stream()
                .filter(p -> p.getStatus() == JobPosting.PostingStatus.OPEN)
                .toList();

        if (openPostings.isEmpty()) return false;

        // Pick the highest paying posting
        targetPosting = openPostings.stream()
                .max(Comparator.comparingLong(JobPosting::getDailyWageBronze))
                .orElse(null);

        if (targetPosting == null) return false;

        // Find the town hall to walk to
        townHall = village.get().getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == BuildingType.TOWN_HALL)
                .findFirst()
                .orElse(null);

        return townHall != null;
    }

    @Override
    public void start() {
        hasApplied = false;
        walkTimer = 0;
        entity.getNavigation().moveTo(
                townHall.getShape().getOrigin().getX(),
                townHall.getShape().getOrigin().getY(),
                townHall.getShape().getOrigin().getZ(),
                1.0
        );
    }

    @Override
    public boolean canContinueToUse() {
        if (!entity.isWorkTime()) return false;

        return !hasApplied && walkTimer < WALK_TO_TOWN_HALL_TIMEOUT;
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        if (targetPosting == null || townHall == null) { stop(); return; }

        walkTimer++;

        BlockPos target = townHall.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ()
        );

        if (distSq > 9) {
            // Still walking
            if (!entity.getNavigation().isInProgress()) {
                entity.getNavigation().moveTo(
                        target.getX(), target.getY(), target.getZ(), 1.0
                );
            }
            return;
        }

        // Arrived at town hall — apply for the job
        entity.getNavigation().stop();

        VillageSavedData data = VillageSavedData.get(level);
        data.getJobPostingById(targetPosting.getId()).ifPresent(posting -> {
            System.out.println("SeekJob instance: " + System.identityHashCode(posting)
                    + " data instance: " + System.identityHashCode(data));
            posting.addApplicant(entity.getUUID());
            data.setDirty();
            System.out.println("After adding, size: " + posting.getApplicantIds().size());
        });



        hasApplied = true;
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        targetPosting = null;
        townHall = null;
        hasApplied = false;
        walkTimer = 0;
    }

    private Optional<Village> findNearbyVillage(ServerLevel level, VillageSavedData data) {
        return data.getAllVillages().stream()
                .filter(v -> v.getBounds(data)
                        .map(bounds -> bounds.inflate(32).contains(
                                entity.getX(), entity.getY(), entity.getZ()
                        ))
                        .orElse(false)
                )
                .findFirst();
    }
}
