package tterrag1112.life_in_the_village.Npc.Scribal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ordered queue of {@link ScribeCommission}s for a single
 * SCRIBE_WORKSHOP. Spec line 115. Persisted on
 * {@link tterrag1112.life_in_the_village.Networking.VillageSavedData}
 * keyed by workshop building UUID.
 */
public final class CommissionQueue {

    /** Hard cap so a workshop's queue can't grow unbounded. */
    public static final int MAX_PENDING = 16;

    private final UUID workshopBuildingId;
    private final List<ScribeCommission> commissions = new ArrayList<>();

    public CommissionQueue(UUID workshopBuildingId) {
        this.workshopBuildingId = workshopBuildingId;
    }

    public UUID workshopBuildingId() { return workshopBuildingId; }
    public List<ScribeCommission> all() { return List.copyOf(commissions); }

    public int pendingCount() {
        int n = 0;
        for (var c : commissions) if (c.status() == CommissionStatus.PENDING) n++;
        return n;
    }

    /** Adds {@code c} unless the queue is full. Returns success. */
    public boolean enqueue(ScribeCommission c) {
        if (pendingCount() >= MAX_PENDING) return false;
        commissions.add(c);
        return true;
    }

    /** Highest-priority pending commission (priority desc, then oldest). */
    public Optional<ScribeCommission> nextPending() {
        return commissions.stream()
                .filter(c -> c.status() == CommissionStatus.PENDING)
                .min(Comparator
                        .comparingInt((ScribeCommission c) -> -c.priority())
                        .thenComparingLong(ScribeCommission::requestedTick));
    }

    /** Replaces the entry with matching commissionId. No-op if not found. */
    public boolean update(ScribeCommission updated) {
        for (int i = 0; i < commissions.size(); i++) {
            if (commissions.get(i).commissionId().equals(updated.commissionId())) {
                commissions.set(i, updated);
                return true;
            }
        }
        return false;
    }

    public Optional<ScribeCommission> get(UUID commissionId) {
        return commissions.stream()
                .filter(c -> c.commissionId().equals(commissionId))
                .findFirst();
    }

    /** Removes terminal-status entries older than {@code retainTicks}. */
    public int prune(long currentTick, long retainTicks) {
        int before = commissions.size();
        commissions.removeIf(c -> c.status().isTerminal()
                && currentTick - c.requestedTick() > retainTicks);
        return before - commissions.size();
    }

    public boolean isEmpty() { return commissions.isEmpty(); }
    public int size()        { return commissions.size(); }

    public static final Codec<CommissionQueue> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("workshop").forGetter(CommissionQueue::workshopBuildingId),
            ScribeCommission.CODEC.listOf().optionalFieldOf("commissions", List.of())
                    .forGetter(q -> List.copyOf(q.commissions))
    ).apply(i, CommissionQueue::fromCodec));

    public static CommissionQueue fromCodec(UUID workshop, List<ScribeCommission> loaded) {
        CommissionQueue q = new CommissionQueue(workshop);
        q.commissions.addAll(loaded);
        return q;
    }
}
