package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-world persistent rite ledger. Spec line 205 — also parks the
 * player-piety values keyed by player UUID since v1 doesn't ship a
 * separate player-data attachment for religion.
 */
public class RiteSavedData extends SavedData {

    /**
     * String-form UUID codec for use as map keys.
     *
     * <p>{@code UUIDUtil.CODEC} produces an int-array NBT tag (4 ints).
     * That works fine when a UUID is a record FIELD (nested inside a
     * CompoundTag), but it crashes when used as a {@code Codec.unboundedMap}
     * KEY because CompoundTag keys must be strings — the encoder pulls the
     * key tag through {@code getStringValue}, which rejects IntArrayTag
     * with the error "Not a string". Use the string form for map keys.</p>
     */
    private static final com.mojang.serialization.Codec<UUID> UUID_STRING_KEY =
            com.mojang.serialization.Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final SavedDataType<RiteSavedData> TYPE = new SavedDataType<>(
            "life_in_the_village_rites",
            RiteSavedData::new,
            RecordCodecBuilder.create(i -> i.group(
                    RiteExecution.CODEC.listOf().optionalFieldOf("rites", List.of())
                            .forGetter(d -> new ArrayList<>(d.rites.values())),
                    com.mojang.serialization.Codec.unboundedMap(
                                    UUID_STRING_KEY,
                                    PietyComponent.CODEC)
                            .optionalFieldOf("playerPiety", Map.of())
                            .forGetter(d -> Map.copyOf(d.playerPiety)),
                    // R4d-1 — player auto-tithe opt-in: playerId → the temple
                    // building they tithe to. Optional so pre-R4d saves load empty.
                    com.mojang.serialization.Codec.unboundedMap(
                                    UUID_STRING_KEY, UUID_STRING_KEY)
                            .optionalFieldOf("autoTitheTemple", Map.of())
                            .forGetter(d -> Map.copyOf(d.autoTitheTemple)),
                    // Divine Layer V1 — player Divine Favour ledger: playerId →
                    // per-deity favour. Optional so pre-V1 saves load empty.
                    com.mojang.serialization.Codec.unboundedMap(
                                    UUID_STRING_KEY, PlayerFavour.CODEC)
                            .optionalFieldOf("playerFavour", Map.of())
                            .forGetter(d -> Map.copyOf(d.playerFavour)),
                    // Divine Layer V3 — player's current divine calling (one per
                    // player). Optional so pre-V3 saves load empty.
                    com.mojang.serialization.Codec.unboundedMap(
                                    UUID_STRING_KEY, PlayerCalling.CODEC)
                            .optionalFieldOf("playerCalling", Map.of())
                            .forGetter(d -> Map.copyOf(d.playerCalling))
            ).apply(i, RiteSavedData::fromCodec)));

    private final Map<UUID, RiteExecution>     rites           = new HashMap<>();
    private final Map<UUID, PietyComponent>    playerPiety     = new HashMap<>();
    private final Map<UUID, UUID>              autoTitheTemple = new HashMap<>();
    private final Map<UUID, PlayerFavour>      playerFavour    = new HashMap<>();
    private final Map<UUID, PlayerCalling>     playerCalling   = new HashMap<>();

    public RiteSavedData() {}

    private static RiteSavedData fromCodec(List<RiteExecution> rites,
                                           Map<UUID, PietyComponent> playerPiety,
                                           Map<UUID, UUID> autoTitheTemple,
                                           Map<UUID, PlayerFavour> playerFavour,
                                           Map<UUID, PlayerCalling> playerCalling) {
        RiteSavedData d = new RiteSavedData();
        if (rites != null) for (RiteExecution r : rites) d.rites.put(r.riteId(), r);
        if (playerPiety != null) d.playerPiety.putAll(playerPiety);
        if (autoTitheTemple != null) d.autoTitheTemple.putAll(autoTitheTemple);
        if (playerFavour != null) d.playerFavour.putAll(playerFavour);
        if (playerCalling != null) d.playerCalling.putAll(playerCalling);
        return d;
    }

    public static RiteSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ── Rites ──────────────────────────────────────────────────────────────

    public void putRite(RiteExecution r) {
        if (r != null) { rites.put(r.riteId(), r); setDirty(); }
    }

    public Optional<RiteExecution> getRite(UUID id) {
        return Optional.ofNullable(rites.get(id));
    }

    public List<RiteExecution> all() { return List.copyOf(rites.values()); }

    public List<RiteExecution> dueRites(long currentTick) {
        return rites.values().stream()
                .filter(r -> r.outcome() == RiteOutcome.PENDING)
                .filter(r -> r.scheduledTick() <= currentTick)
                .toList();
    }

    public List<RiteExecution> ritesForVillage(UUID villageId) {
        if (villageId == null) return List.of();
        return rites.values().stream()
                .filter(r -> villageId.equals(r.villageId()))
                .toList();
    }

    // ── Pruning (R4e) ────────────────────────────────────────────────────────

    /** Keep this many in-game days of completed rites for history/debug; older
     *  transient (non-marker) completed rites are pruned. Conservative — far
     *  longer than any in-flight read of a completed rite (a festival's linked
     *  rite is read only during its short active window). */
    private static final long RETENTION_TICKS = 30L * 24000L;
    /** Cap removals per daily pass so even a huge ledger is bounded gradually. */
    private static final int  MAX_PRUNE_PER_PASS = 500;

    /**
     * R4e — the ONLY load-bearing completed-rite marker: a SUCCESSFUL
     * {@link Rite#CONSECRATION} IS the durable "consecrated" marker (R3b-1).
     * {@code RiteScheduler.collectConsecrationMarkers} reads it to grant the
     * ongoing village blessing AND to skip re-consecrating the building — pruning
     * it would silently un-consecrate the building. Everything else completed is
     * transient: ordination reads the clergy spec (not the ledger), the schedulers
     * read PENDING/due rites, and the gathering queries filter on {@code
     * isActiveAt} (a completed rite is never active).
     */
    public static boolean isMarker(RiteExecution r) {
        return r.type() == Rite.CONSECRATION && r.outcome() == RiteOutcome.SUCCESSFUL;
    }

    /** True when {@code r} is a transient completed rite past the retention window
     *  — safe to drop. Never true for a PENDING (in-flight) rite or a marker. */
    public static boolean isPrunable(RiteExecution r, long currentTick) {
        if (r.outcome() == RiteOutcome.PENDING) return false;   // in-flight — keep
        if (isMarker(r)) return false;                          // load-bearing — keep forever
        return currentTick - r.completedTick() > RETENTION_TICKS;
    }

    /** Removes transient completed rites older than the retention window. Markers
     *  + PENDING rites + the player-piety map are untouched. Returns the count
     *  pruned. */
    public int pruneStaleRites(long currentTick) {
        List<UUID> toRemove = new ArrayList<>();
        for (RiteExecution r : rites.values()) {
            if (isPrunable(r, currentTick)) {
                toRemove.add(r.riteId());
                if (toRemove.size() >= MAX_PRUNE_PER_PASS) break;
            }
        }
        for (UUID id : toRemove) rites.remove(id);
        if (!toRemove.isEmpty()) setDirty();
        return toRemove.size();
    }

    // ── Player piety ────────────────────────────────────────────────────────

    public PietyComponent getOrCreatePlayerPiety(UUID playerId) {
        return playerPiety.computeIfAbsent(playerId, k -> {
            PietyComponent p = new PietyComponent();
            // Default unaffiliated; verbs / rite attendance grow it.
            return p;
        });
    }

    public Optional<PietyComponent> getPlayerPiety(UUID playerId) {
        return Optional.ofNullable(playerPiety.get(playerId));
    }

    public Map<UUID, PietyComponent> allPlayerPieties() {
        return java.util.Collections.unmodifiableMap(playerPiety);
    }

    // ── Player Divine Favour (Divine Layer V1) ───────────────────────────────

    public PlayerFavour getOrCreatePlayerFavour(UUID playerId) {
        return playerFavour.computeIfAbsent(playerId, k -> new PlayerFavour());
    }

    public Optional<PlayerFavour> getPlayerFavour(UUID playerId) {
        return Optional.ofNullable(playerFavour.get(playerId));
    }

    // ── Player divine calling (Divine Layer V3) ──────────────────────────────

    public Optional<PlayerCalling> getPlayerCalling(UUID playerId) {
        return Optional.ofNullable(playerCalling.get(playerId));
    }

    public void setPlayerCalling(UUID playerId, PlayerCalling calling) {
        if (calling == null) playerCalling.remove(playerId);
        else playerCalling.put(playerId, calling);
        setDirty();
    }

    public void clearPlayerCalling(UUID playerId) {
        if (playerCalling.remove(playerId) != null) setDirty();
    }

    // ── Player auto-tithe opt-in (R4d-1) ─────────────────────────────────────

    /** True when {@code playerId} has opted into the recurring auto-tithe. */
    public boolean isAutoTithe(UUID playerId) { return autoTitheTemple.containsKey(playerId); }

    /** Opt {@code playerId} into auto-tithing to {@code templeBuildingId}. */
    public void setAutoTithe(UUID playerId, UUID templeBuildingId) {
        autoTitheTemple.put(playerId, templeBuildingId);
        setDirty();
    }

    /** Opt {@code playerId} out of auto-tithing. */
    public void clearAutoTithe(UUID playerId) {
        if (autoTitheTemple.remove(playerId) != null) setDirty();
    }

    /** Snapshot of the opt-in map (playerId → temple building id). */
    public Map<UUID, UUID> autoTitheTemples() {
        return java.util.Collections.unmodifiableMap(new HashMap<>(autoTitheTemple));
    }

    public void markDirty() { setDirty(); }
}
