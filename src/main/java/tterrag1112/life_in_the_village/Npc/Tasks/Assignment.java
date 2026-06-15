package tterrag1112.life_in_the_village.Npc.Tasks;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable claim-lifecycle state of a {@link Task}. A task can be claimed
 * by up to {@link #maxClaimants} actors (default 1; {@code >1} models a
 * shared quota). Transitions are guarded — callers cannot skip states
 * or advance a terminal task.
 *
 * <p>Status flow:
 * {@code OPEN -> CLAIMED -> IN_PROGRESS -> DONE},
 * with {@code FAILED} / {@code EXPIRED} reachable from any non-terminal
 * state via {@link #terminate}. Releasing the last claimant returns a
 * {@code CLAIMED} task to {@code OPEN}.</p>
 */
public final class Assignment {

    public enum Status { OPEN, CLAIMED, IN_PROGRESS, DONE, FAILED, EXPIRED }

    private Status status;
    private final Set<UUID> claimants;
    private final int maxClaimants;

    public Assignment() {
        this(Status.OPEN, new LinkedHashSet<>(), 1);
    }

    public Assignment(int maxClaimants) {
        this(Status.OPEN, new LinkedHashSet<>(), Math.max(1, maxClaimants));
    }

    private Assignment(Status status, Set<UUID> claimants, int maxClaimants) {
        this.status = status;
        this.claimants = new LinkedHashSet<>(claimants);
        this.maxClaimants = Math.max(1, maxClaimants);
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    public Status status()         { return status; }
    public int maxClaimants()      { return maxClaimants; }
    public Set<UUID> claimants()   { return Set.copyOf(claimants); }
    public boolean isTerminal()    { return status == Status.DONE || status == Status.FAILED || status == Status.EXPIRED; }
    public boolean isClaimable()   { return !isTerminal() && claimants.size() < maxClaimants; }
    public boolean isClaimedBy(UUID actor) { return claimants.contains(actor); }

    // ── Transitions (guarded) ────────────────────────────────────────────────

    /**
     * Adds {@code actor} as a claimant. No-op-returns {@code false} if
     * terminal, already at capacity, or already a claimant. The first
     * claim moves OPEN -> CLAIMED.
     */
    public boolean claim(UUID actor) {
        if (!isClaimable() || claimants.contains(actor)) return false;
        claimants.add(actor);
        if (status == Status.OPEN) status = Status.CLAIMED;
        return true;
    }

    /**
     * Removes {@code actor}'s claim. Returns {@code false} if it wasn't
     * a claimant. Dropping the last claimant of a non-IN_PROGRESS task
     * returns it to OPEN; an IN_PROGRESS task with no claimants left is
     * marked FAILED (work was abandoned mid-flight).
     */
    public boolean release(UUID actor) {
        if (!claimants.remove(actor)) return false;
        if (claimants.isEmpty()) {
            status = (status == Status.IN_PROGRESS) ? Status.FAILED : Status.OPEN;
        }
        return true;
    }

    /** CLAIMED -> IN_PROGRESS. Returns {@code false} from any other state. */
    public boolean advance() {
        if (status != Status.CLAIMED) return false;
        status = Status.IN_PROGRESS;
        return true;
    }

    /** Non-terminal -> DONE. Returns {@code false} if already terminal. */
    public boolean complete() {
        if (isTerminal()) return false;
        status = Status.DONE;
        return true;
    }

    /**
     * Non-terminal -> {@code outcome} (must be FAILED or EXPIRED).
     * Returns {@code false} if already terminal or given a non-terminal
     * outcome.
     */
    public boolean terminate(Status outcome) {
        if (outcome != Status.FAILED && outcome != Status.EXPIRED) return false;
        if (isTerminal()) return false;
        status = outcome;
        return true;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public static final Codec<Assignment> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.xmap(Status::valueOf, Status::name)
                    .optionalFieldOf("status", Status.OPEN).forGetter(Assignment::status),
            UUIDUtil.CODEC.listOf().optionalFieldOf("claimants", List.of())
                    .forGetter(a -> List.copyOf(a.claimants)),
            Codec.INT.optionalFieldOf("maxClaimants", 1).forGetter(Assignment::maxClaimants)
    ).apply(i, Assignment::fromCodec));

    private static Assignment fromCodec(Status status, List<UUID> claimants, int maxClaimants) {
        return new Assignment(status, new LinkedHashSet<>(claimants), maxClaimants);
    }
}
