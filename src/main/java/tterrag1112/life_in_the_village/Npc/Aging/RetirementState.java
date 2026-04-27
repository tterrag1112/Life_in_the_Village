package tterrag1112.life_in_the_village.Npc.Aging;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-NPC elderly state. Spec line 65.
 *
 * <p>Lives on the entity save subtree under {@code npcRetirement}.
 * Initialised on ELDERLY entry by {@link RetirementHandler}.</p>
 */
public final class RetirementState {

    private static final String SAVE_KEY = "npcRetirement";

    private long retirementStartTick;
    private boolean fullyRetired;
    private boolean actingAsMentor;
    private UUID mentorTargetId;
    private final List<UUID> mentoredInPast = new ArrayList<>();
    private UnfinishedBusiness unfinished;

    public RetirementState() {}

    public long retirementStartTick()      { return retirementStartTick; }
    public boolean fullyRetired()          { return fullyRetired; }
    public boolean actingAsMentor()        { return actingAsMentor; }
    public Optional<UUID> mentorTargetId() { return Optional.ofNullable(mentorTargetId); }
    public List<UUID> mentoredInPast()     { return Collections.unmodifiableList(mentoredInPast); }
    public Optional<UnfinishedBusiness> unfinished() { return Optional.ofNullable(unfinished); }

    public boolean isEmpty() {
        return retirementStartTick == 0L && !fullyRetired && !actingAsMentor
                && mentorTargetId == null && mentoredInPast.isEmpty()
                && unfinished == null;
    }

    public boolean hasUnresolvedBusiness() {
        return unfinished != null && !unfinished.resolved();
    }

    // ── Mutations ──────────────────────────────────────────────────────────

    public void setRetirementStartTick(long tick) { this.retirementStartTick = tick; }
    public void setFullyRetired(boolean v)        { this.fullyRetired = v; }
    public void setActingAsMentor(boolean v)      { this.actingAsMentor = v; }
    public void setMentorTargetId(UUID id) {
        this.mentorTargetId = id;
        this.actingAsMentor = id != null;
    }
    public void clearMentorTarget() {
        if (mentorTargetId != null && !mentoredInPast.contains(mentorTargetId)) {
            mentoredInPast.add(mentorTargetId);
        }
        this.mentorTargetId = null;
        this.actingAsMentor = false;
    }
    public void setUnfinished(UnfinishedBusiness business) { this.unfinished = business; }
    public void resolveUnfinished() {
        if (unfinished != null) unfinished = unfinished.withResolved(true);
    }

    // ── Persistence ────────────────────────────────────────────────────────

    public void save(ValueOutput output) {
        if (isEmpty()) return;
        output.store(SAVE_KEY, CODEC, this);
    }

    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        RetirementState loaded = read.get();
        this.retirementStartTick = loaded.retirementStartTick;
        this.fullyRetired = loaded.fullyRetired;
        this.actingAsMentor = loaded.actingAsMentor;
        this.mentorTargetId = loaded.mentorTargetId;
        this.mentoredInPast.clear();
        this.mentoredInPast.addAll(loaded.mentoredInPast);
        this.unfinished = loaded.unfinished;
        return true;
    }

    public static final Codec<RetirementState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.optionalFieldOf("retirementStartTick", 0L)
                    .forGetter(s -> s.retirementStartTick),
            Codec.BOOL.optionalFieldOf("fullyRetired", false)
                    .forGetter(s -> s.fullyRetired),
            Codec.BOOL.optionalFieldOf("actingAsMentor", false)
                    .forGetter(s -> s.actingAsMentor),
            UUIDUtil.CODEC.optionalFieldOf("mentorTargetId")
                    .forGetter(s -> Optional.ofNullable(s.mentorTargetId)),
            UUIDUtil.CODEC.listOf().optionalFieldOf("mentoredInPast", List.of())
                    .forGetter(s -> List.copyOf(s.mentoredInPast)),
            UnfinishedBusiness.CODEC.optionalFieldOf("unfinished")
                    .forGetter(s -> Optional.ofNullable(s.unfinished))
    ).apply(i, RetirementState::fromCodec));

    public static RetirementState fromCodec(long retirementStartTick,
                                            boolean fullyRetired,
                                            boolean actingAsMentor,
                                            Optional<UUID> mentorTargetId,
                                            List<UUID> mentoredInPast,
                                            Optional<UnfinishedBusiness> unfinished) {
        RetirementState s = new RetirementState();
        s.retirementStartTick = retirementStartTick;
        s.fullyRetired = fullyRetired;
        s.actingAsMentor = actingAsMentor;
        s.mentorTargetId = mentorTargetId.orElse(null);
        s.mentoredInPast.addAll(mentoredInPast);
        s.unfinished = unfinished.orElse(null);
        return s;
    }
}
