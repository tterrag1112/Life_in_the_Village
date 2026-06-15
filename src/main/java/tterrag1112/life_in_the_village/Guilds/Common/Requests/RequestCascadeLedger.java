package tterrag1112.life_in_the_village.Guilds.Common.Requests;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * T5b-2/3 — the persisted seam connecting a per-tick {@code DeliverExecutor}
 * back to the {@link Request} it advances.
 *
 * <p>The cascade ({@link RequestCascade}) decomposes a CRAFT {@link Request}
 * into per-business craft + deliver {@code Task}s on the businesses' boards.
 * The deliver task carries only item / qty / destination — it has no field
 * for the originating request id. The producing NPC runs that deliver task on
 * its own brain tick, potentially days after the daily cascade pass, so the
 * link from a deliver {@code TaskId} to its {@code requestId} (plus the
 * per-business share and delivered-so-far) must survive a refresh and a save.
 * This compact {@code SavedData} is that link.</p>
 *
 * <p>Keyed by the deliver task's {@code TaskId} (string-encoded UUID). Each
 * entry is three small fields, well under the codec ceiling. Entries are
 * cleared when the cascade observes the owning request reach a terminal state
 * (see {@link RequestCascade#prune}).</p>
 */
public final class RequestCascadeLedger extends SavedData {

    /** One delivery-leg record: which request this leg feeds, the share this
     *  business was assigned, and how much has been delivered so far. */
    public record Entry(UUID requestId, UUID businessId, int share, int deliveredSoFar) {
        public Entry {
            if (requestId == null) throw new IllegalArgumentException("requestId required");
            if (share < 0) share = 0;
            if (deliveredSoFar < 0) deliveredSoFar = 0;
        }

        public Entry withDelivered(int delivered) {
            return new Entry(requestId, businessId, share, Math.max(0, delivered));
        }

        public int remaining() { return Math.max(0, share - deliveredSoFar); }

        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("requestId").forGetter(Entry::requestId),
                UUIDUtil.CODEC.fieldOf("businessId").forGetter(Entry::businessId),
                Codec.INT.optionalFieldOf("share", 0).forGetter(Entry::share),
                Codec.INT.optionalFieldOf("deliveredSoFar", 0).forGetter(Entry::deliveredSoFar)
        ).apply(i, Entry::new));
    }

    public static final SavedDataType<RequestCascadeLedger> TYPE = new SavedDataType<>(
            "life_in_the_village_request_cascade_ledger",
            RequestCascadeLedger::new,
            RecordCodecBuilder.create(i -> i.group(
                    Codec.unboundedMap(Codec.STRING, Entry.CODEC)
                            .optionalFieldOf("legs", Map.of())
                            .forGetter(l -> l.legs)
            ).apply(i, RequestCascadeLedger::fromCodec)));

    private static RequestCascadeLedger fromCodec(Map<String, Entry> loaded) {
        RequestCascadeLedger l = new RequestCascadeLedger();
        if (loaded != null) l.legs.putAll(loaded);
        return l;
    }

    /** deliverTaskId (UUID string) -> leg record. */
    private final Map<String, Entry> legs = new LinkedHashMap<>();

    public RequestCascadeLedger() {}

    public static RequestCascadeLedger get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ── Access ────────────────────────────────────────────────────────────

    public Optional<Entry> get(UUID deliverTaskId) {
        return Optional.ofNullable(deliverTaskId == null ? null : legs.get(deliverTaskId.toString()));
    }

    /** Insert / refresh a delivery leg, preserving an existing
     *  deliveredSoFar when the leg already exists (re-arm across passes). */
    public void put(UUID deliverTaskId, UUID requestId, UUID businessId, int share) {
        if (deliverTaskId == null || requestId == null) return;
        String key = deliverTaskId.toString();
        Entry prev = legs.get(key);
        int delivered = prev == null ? 0 : prev.deliveredSoFar();
        legs.put(key, new Entry(requestId, businessId, share, delivered));
        setDirty();
    }

    /** Record that {@code addedQty} more units have been delivered on this
     *  leg. Returns the new cumulative deliveredSoFar for the leg (0 if the
     *  leg is unknown). */
    public int addDelivered(UUID deliverTaskId, int addedQty) {
        if (deliverTaskId == null || addedQty <= 0) {
            return get(deliverTaskId).map(Entry::deliveredSoFar).orElse(0);
        }
        String key = deliverTaskId.toString();
        Entry prev = legs.get(key);
        if (prev == null) return 0;
        Entry next = prev.withDelivered(prev.deliveredSoFar() + addedQty);
        legs.put(key, next);
        setDirty();
        return next.deliveredSoFar();
    }

    /** All legs feeding {@code requestId}. */
    public List<Entry> legsForRequest(UUID requestId) {
        if (requestId == null) return List.of();
        return legs.values().stream()
                .filter(e -> e.requestId().equals(requestId))
                .toList();
    }

    /** Drop every leg feeding {@code requestId} (called on terminal state). */
    public void clearRequest(UUID requestId) {
        if (requestId == null) return;
        boolean removed = legs.values().removeIf(e -> e.requestId().equals(requestId));
        if (removed) setDirty();
    }

    public void markChanged() { setDirty(); }
}
