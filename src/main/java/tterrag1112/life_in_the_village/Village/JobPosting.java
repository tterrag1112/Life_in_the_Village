package tterrag1112.life_in_the_village.Village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class JobPosting {

    public static final long DAILY_WAGE_BRONZE_DEFAULT = 32L;


    public enum JobType {
        FARMHAND,
        GUARD,          // for future use
        APPRENTICE,     // for future use
        LABORER         // for future use
    }

    public enum PostingStatus {
        OPEN,           // accepting applicants
        REVIEWING,      // farmer is evaluating applicants
        FILLED,         // job has been assigned
        CANCELLED       // posting was withdrawn
    }

    public static final Codec<JobPosting> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("id").forGetter(JobPosting::getId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("posterEntityId").forGetter(JobPosting::getPosterEntityId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("farmhouseBuildingId").forGetter(JobPosting::getFarmhouseBuildingId),
                    Codec.STRING.xmap(JobType::valueOf, JobType::name)
                            .fieldOf("jobType").forGetter(JobPosting::getJobType),
                    Codec.LONG
                            .fieldOf("dailyWageBronze").forGetter(JobPosting::getDailyWageBronze),
                    Codec.LONG
                            .optionalFieldOf("postedAtTick", 0L)
                            .forGetter(JobPosting::getPostedAtTick),
                    Codec.STRING.xmap(PostingStatus::valueOf, PostingStatus::name)
                            .fieldOf("status").forGetter(JobPosting::getStatus),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString).listOf()
                            .optionalFieldOf("applicantIds", new ArrayList<>())
                            .forGetter(p -> new ArrayList<>(p.applicantIds)),
                    Codec.LONG
                            .optionalFieldOf("reviewStartTick", -1L)
                            .forGetter(JobPosting::getReviewStartTick)
            ).apply(instance, JobPosting::new) // uses the full constructor directly
    );

    private final UUID id;
    private final UUID posterEntityId;       // UUID of the farmer NPC
    private final UUID farmhouseBuildingId;  // which farmhouse this is for
    private final JobType jobType;
    private PostingStatus status;
    private final long dailyWageBronze;
    private final long postedAtTick;
    private long reviewStartTick = -1L;
    private List<UUID> applicantIds;

    // How long before the farmer starts reviewing (1 in-game day)
    //public static final long REVIEW_DELAY_TICKS = 24000L;
    public static final long REVIEW_DELAY_TICKS = 200L;

    // How long the review period lasts before picking best applicant
    //public static final long REVIEW_DURATION_TICKS = 6000L;
    public static final long REVIEW_DURATION_TICKS = 100L;


    public JobPosting(UUID id, UUID posterEntityId, UUID farmhouseBuildingId,
                      JobType jobType, long dailyWageBronze, long postedAtTick) {
        this.id = id;
        this.posterEntityId = posterEntityId;
        this.farmhouseBuildingId = farmhouseBuildingId;
        this.jobType = jobType;
        this.dailyWageBronze = dailyWageBronze;
        this.postedAtTick = postedAtTick;
        this.status = PostingStatus.OPEN;
        this.applicantIds = new ArrayList<>(); // explicit init here
    }

    // Full constructor — used by codec
    public JobPosting(UUID id, UUID posterEntityId, UUID farmhouseBuildingId,
                      JobType jobType, long dailyWageBronze, long postedAtTick,
                      PostingStatus status, List<UUID> applicantIds, long reviewStartTick) {
        this.id = id;
        this.posterEntityId = posterEntityId;
        this.farmhouseBuildingId = farmhouseBuildingId;
        this.jobType = jobType;
        this.dailyWageBronze = dailyWageBronze;
        this.postedAtTick = postedAtTick;
        this.status = status;
        this.applicantIds = new ArrayList<>(applicantIds); // copy incoming list
        this.reviewStartTick = reviewStartTick;
    }

    public void addApplicant(UUID entityId) {
        if (!applicantIds.contains(entityId)) {
            applicantIds.add(entityId);
        }
    }

    public void startReview(long currentTick) {
        this.status = PostingStatus.REVIEWING;
        this.reviewStartTick = currentTick;
    }

    public boolean isReviewComplete(long currentTick) {
        return reviewStartTick >= 0
                && currentTick - reviewStartTick >= REVIEW_DURATION_TICKS;
    }

    public boolean isReadyToReview(long currentTick) {
        long ticksSincePost = currentTick - postedAtTick;
        boolean hasApplicants = !applicantIds.isEmpty();
        boolean timeElapsed = ticksSincePost >= REVIEW_DELAY_TICKS;
        System.out.println("isReadyToReview: applicants=" + applicantIds.size()
                + " ticksSince=" + ticksSincePost
                + " threshold=" + REVIEW_DELAY_TICKS
                + " hasApplicants=" + hasApplicants
                + " timeElapsed=" + timeElapsed);
        return status == PostingStatus.OPEN && hasApplicants && timeElapsed;
    }

    public UUID getId()                  { return id; }
    public UUID getPosterEntityId()      { return posterEntityId; }
    public UUID getFarmhouseBuildingId() { return farmhouseBuildingId; }
    public JobType getJobType()          { return jobType; }
    public PostingStatus getStatus()     { return status; }
    public long getDailyWageBronze()     { return dailyWageBronze; }
    public List<UUID> getApplicantIds()  { return Collections.unmodifiableList(applicantIds); }
    public long getPostedAtTick()        { return postedAtTick; }
    public long getReviewStartTick()     { return reviewStartTick; }

    public void setStatus(PostingStatus status) { this.status = status; }

    public CurrencyValue getDailyWage() {
        return CurrencyValue.of(dailyWageBronze);
    }
}
