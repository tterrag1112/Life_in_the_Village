package tterrag1112.life_in_the_village.Village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Represents an open position at a building that any NPC (or eventually player)
 * can apply for.
 *
 * <h3>Generalization</h3>
 * The posting is keyed to a {@link #buildingId} rather than a farmhouse-specific
 * ID, so it works for blacksmiths posting for an APPRENTICE, guards posting for
 * a LABORER, etc. The {@link #profession} field records which profession the
 * employer belongs to, so applicants can filter by relevant skill.
 *
 * <h3>Job types</h3>
 * <ul>
 *   <li>{@code FARMHAND} — general farm labour (legacy, maps to LABORER semantics)</li>
 *   <li>{@code APPRENTICE} — learner for any workshop or production profession</li>
 *   <li>{@code GUARD} — armed patrol/security hire</li>
 *   <li>{@code LABORER} — unskilled worker for any building</li>
 * </ul>
 */
public class JobPosting {

    public static final long DAILY_WAGE_BRONZE_DEFAULT = 32L;

    public enum JobType {
        FARMHAND,
        GUARD,
        APPRENTICE,
        LABORER
    }

    public enum PostingStatus {
        OPEN,        // accepting applicants
        REVIEWING,   // employer is evaluating applicants
        FILLED,      // job has been assigned
        CANCELLED    // posting was withdrawn
    }

    // ── Codec ──────────────────────────────────────────────────────────────────
    // The NBT key "farmhouseBuildingId" is preserved for backward compatibility
    // with saves written before this field was renamed to buildingId.
    public static final Codec<JobPosting> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("id").forGetter(JobPosting::getId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("posterEntityId").forGetter(JobPosting::getPosterEntityId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("farmhouseBuildingId").forGetter(JobPosting::getBuildingId),
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
                            .forGetter(JobPosting::getReviewStartTick),
                    Codec.STRING.xmap(Profession::valueOf, Profession::name)
                            .optionalFieldOf("profession", Profession.NONE)
                            .forGetter(JobPosting::getProfession)
            ).apply(instance, JobPosting::new)
    );

    // ── Fields ─────────────────────────────────────────────────────────────────

    private final UUID id;
    private final UUID posterEntityId;
    /** The building this job is attached to. Stored as "farmhouseBuildingId" in NBT. */
    private final UUID buildingId;
    private final JobType jobType;
    /** Profession of the employer — used to match apprentices to relevant workplaces. */
    private final Profession profession;
    private PostingStatus status;
    private final long dailyWageBronze;
    private final long postedAtTick;
    private long reviewStartTick = -1L;
    private List<UUID> applicantIds;

    // How long before the employer starts reviewing (1 in-game day = 24000 ticks)
    public static final long REVIEW_DELAY_TICKS    = 200L;
    public static final long REVIEW_DURATION_TICKS = 100L;

    // ── Constructors ───────────────────────────────────────────────────────────

    /** Convenience constructor — defaults to {@link Profession#NONE}. */
    public JobPosting(UUID id, UUID posterEntityId, UUID buildingId,
                      JobType jobType, long dailyWageBronze, long postedAtTick) {
        this(id, posterEntityId, buildingId, jobType, dailyWageBronze, postedAtTick,
                PostingStatus.OPEN, new ArrayList<>(), -1L, Profession.NONE);
    }

    /** Constructor that records which profession is posting this job. */
    public JobPosting(UUID id, UUID posterEntityId, UUID buildingId,
                      JobType jobType, long dailyWageBronze, long postedAtTick,
                      Profession profession) {
        this(id, posterEntityId, buildingId, jobType, dailyWageBronze, postedAtTick,
                PostingStatus.OPEN, new ArrayList<>(), -1L, profession);
    }

    /** Full constructor used by the codec. */
    public JobPosting(UUID id, UUID posterEntityId, UUID buildingId,
                      JobType jobType, long dailyWageBronze, long postedAtTick,
                      PostingStatus status, List<UUID> applicantIds,
                      long reviewStartTick, Profession profession) {
        this.id              = id;
        this.posterEntityId  = posterEntityId;
        this.buildingId      = buildingId;
        this.jobType         = jobType;
        this.dailyWageBronze = dailyWageBronze;
        this.postedAtTick    = postedAtTick;
        this.status          = status;
        this.applicantIds    = new ArrayList<>(applicantIds);
        this.reviewStartTick = reviewStartTick;
        this.profession      = profession;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    public void addApplicant(UUID entityId) {
        if (!applicantIds.contains(entityId)) applicantIds.add(entityId);
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
        boolean timeElapsed   = ticksSincePost >= REVIEW_DELAY_TICKS;
        return status == PostingStatus.OPEN && hasApplicants && timeElapsed;
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    public UUID getId()                   { return id; }
    public UUID getPosterEntityId()       { return posterEntityId; }
    public UUID getBuildingId()           { return buildingId; }
    /** @deprecated Use {@link #getBuildingId()} — kept for call-site compatibility. */
    @Deprecated
    public UUID getFarmhouseBuildingId()  { return buildingId; }
    public JobType getJobType()           { return jobType; }
    public Profession getProfession()     { return profession; }
    public PostingStatus getStatus()      { return status; }
    public long getDailyWageBronze()      { return dailyWageBronze; }
    public List<UUID> getApplicantIds()   { return Collections.unmodifiableList(applicantIds); }
    public long getPostedAtTick()         { return postedAtTick; }
    public long getReviewStartTick()      { return reviewStartTick; }

    public void setStatus(PostingStatus status) { this.status = status; }

    public CurrencyValue getDailyWage() { return CurrencyValue.of(dailyWageBronze); }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Returns true if this posting is looking for an apprentice — i.e. the
     * job type is {@link JobType#APPRENTICE} regardless of profession.
     */
    public boolean isApprenticePosting() { return jobType == JobType.APPRENTICE; }

    /**
     * Returns true if this posting is relevant for an NPC of the given profession.
     * LABORER and FARMHAND postings accept any applicant; APPRENTICE postings
     * prefer applicants whose profession matches the poster's profession.
     */
    public boolean acceptsProfession(tterrag1112.life_in_the_village.Profession.Profession applicantProfession) {
        return switch (jobType) {
            case LABORER, FARMHAND -> true;
            case GUARD     -> applicantProfession == Profession.GUARD
                    || applicantProfession == Profession.NONE;
            case APPRENTICE -> this.profession == Profession.NONE
                    || applicantProfession == this.profession
                    || applicantProfession == Profession.NONE;
        };
    }
}
