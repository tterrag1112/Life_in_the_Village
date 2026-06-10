package tterrag1112.life_in_the_village.Npc.Religion.Saints;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Npc.Religion.FaithConcept;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Saints &amp; Relics SR1 — the per-world store of <b>living saints</b> (holy people):
 * a being (player or NPC) recognised as the "Holy of god G" in life. Mirrors the
 * established SavedData idiom ({@code RiteSavedData} / {@code SacredSpaceSavedData}):
 * own {@link SavedDataType} + storage name, a codec over the collection,
 * {@code markDirty} on change.
 *
 * <p>A being holds at most one living-saint status at a time (keyed by being id), so
 * a fresh anointing by another god replaces it. <b>Earned + losable</b> — {@link Saints}
 * adds on anointing/designation and removes on lapse/apostasy.</p>
 *
 * <p>SR2 (canonized / deceased saints) will extend this store with a parallel roster
 * of the dead — the codec is a list of records, so a second list slots in then without
 * disturbing the living one.</p>
 */
public class SaintsSavedData extends SavedData {

    private static final String STORAGE = "life_in_the_village_saints";

    private static final Codec<UUID> UUID_STRING =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    /**
     * A living saint: a holy person of {@code godId} since {@code becameTick}.
     * {@code isPlayer} distinguishes the two criteria paths (player favour+theophany
     * vs NPC sustained piety) for the readout + later NPC reverence.
     */
    public record LivingSaint(UUID beingId, String godId, long becameTick, boolean isPlayer) {
        public static final Codec<LivingSaint> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUID_STRING.fieldOf("beingId").forGetter(LivingSaint::beingId),
                Codec.STRING.fieldOf("godId").forGetter(LivingSaint::godId),
                Codec.LONG.fieldOf("becameTick").forGetter(LivingSaint::becameTick),
                Codec.BOOL.optionalFieldOf("isPlayer", false).forGetter(LivingSaint::isPlayer)
        ).apply(i, LivingSaint::new));
    }

    private static final Codec<FaithConcept> CONCEPT_CODEC = Codec.STRING.xmap(
            s -> FaithConcept.valueOf(s.toUpperCase(Locale.ROOT)), FaithConcept::name);

    /**
     * SR2 — a canonized (deceased) saint, OR a {@code canonized=false} <b>Venerable</b>
     * candidate awaiting a high priest's elevation. {@code saintDay} is the calendar
     * day-of-year added on canonization (−1 while a Venerable). {@code relicId} is the
     * SR4 seam (always empty here).
     */
    public record Saint(UUID saintId, String name, String religionId, String godId,
                        FaithConcept virtue, String epitaph, boolean martyr, boolean canonized,
                        long deathTick, BlockPos gravePos, int saintDay, Optional<String> relicId,
                        int veneration) {
        public static final Codec<Saint> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUID_STRING.fieldOf("saintId").forGetter(Saint::saintId),
                Codec.STRING.fieldOf("name").forGetter(Saint::name),
                Codec.STRING.fieldOf("religionId").forGetter(Saint::religionId),
                Codec.STRING.fieldOf("godId").forGetter(Saint::godId),
                CONCEPT_CODEC.fieldOf("virtue").forGetter(Saint::virtue),
                Codec.STRING.optionalFieldOf("epitaph", "").forGetter(Saint::epitaph),
                Codec.BOOL.optionalFieldOf("martyr", false).forGetter(Saint::martyr),
                Codec.BOOL.optionalFieldOf("canonized", true).forGetter(Saint::canonized),
                Codec.LONG.fieldOf("deathTick").forGetter(Saint::deathTick),
                BlockPos.CODEC.fieldOf("gravePos").forGetter(Saint::gravePos),
                Codec.INT.optionalFieldOf("saintDay", -1).forGetter(Saint::saintDay),
                Codec.STRING.optionalFieldOf("relicId").forGetter(Saint::relicId),
                // SR3 — grassroots veneration accrued by player prayer (drives a
                // Venerable's popular elevation). 0 for a clergy/auto canonization.
                Codec.INT.optionalFieldOf("veneration", 0).forGetter(Saint::veneration)
        ).apply(i, Saint::new));

        /** A copy of this saint with {@code veneration} replaced. */
        public Saint withVeneration(int v) {
            return new Saint(saintId, name, religionId, godId, virtue, epitaph, martyr,
                    canonized, deathTick, gravePos, saintDay, relicId, v);
        }

        /** A copy of this saint with {@code relicId} set (SR4 — minted on canonization). */
        public Saint withRelicId(Optional<String> id) {
            return new Saint(saintId, name, religionId, godId, virtue, epitaph, martyr,
                    canonized, deathTick, gravePos, saintDay, id, veneration);
        }
    }

    public static final SavedDataType<SaintsSavedData> TYPE = new SavedDataType<>(
            STORAGE,
            SaintsSavedData::new,
            RecordCodecBuilder.create(i -> i.group(
                    LivingSaint.CODEC.listOf().optionalFieldOf("livingSaints", List.of())
                            .forGetter(d -> new ArrayList<>(d.living.values())),
                    Saint.CODEC.listOf().optionalFieldOf("saints", List.of())
                            .forGetter(d -> new ArrayList<>(d.saints.values())),
                    // SR3 — per-player/per-saint last-prayer tick ("playerId|saintId" → tick).
                    Codec.unboundedMap(Codec.STRING, Codec.LONG)
                            .optionalFieldOf("prayerCooldowns", Map.of())
                            .forGetter(d -> Map.copyOf(d.prayerCooldowns))
            ).apply(i, SaintsSavedData::fromCodec)));

    /** beingId → its living-saint status (a being is the Holy of at most one god). */
    private final Map<UUID, LivingSaint> living = new LinkedHashMap<>();
    /** saintId → its canonized record OR a pending Venerable ({@code canonized=false}). */
    private final Map<UUID, Saint> saints = new LinkedHashMap<>();
    /** SR3 — "playerId|saintId" → the tick that pair last prayed (per-saint/day gate). */
    private final Map<String, Long> prayerCooldowns = new LinkedHashMap<>();

    public SaintsSavedData() {}

    private static SaintsSavedData fromCodec(List<LivingSaint> loaded, List<Saint> loadedSaints,
                                             Map<String, Long> cooldowns) {
        SaintsSavedData d = new SaintsSavedData();
        if (loaded != null) for (LivingSaint s : loaded) {
            if (s != null) d.living.put(s.beingId(), s);
        }
        if (loadedSaints != null) for (Saint s : loadedSaints) {
            if (s != null) d.saints.put(s.saintId(), s);
        }
        if (cooldowns != null) d.prayerCooldowns.putAll(cooldowns);
        return d;
    }

    public static SaintsSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /** True when {@code beingId} is a living saint of {@code godId}. */
    public boolean isLivingSaint(UUID beingId, String godId) {
        if (beingId == null || godId == null) return false;
        LivingSaint s = living.get(beingId);
        return s != null && s.godId().equals(godId);
    }

    /** The god {@code beingId} is a living saint of, or empty. */
    public Optional<String> livingSaintGod(UUID beingId) {
        LivingSaint s = beingId == null ? null : living.get(beingId);
        return s == null ? Optional.empty() : Optional.of(s.godId());
    }

    /** Every living saint of {@code godId} (stable order). */
    public List<LivingSaint> livingSaintsOf(String godId) {
        List<LivingSaint> out = new ArrayList<>();
        if (godId != null) for (LivingSaint s : living.values()) {
            if (s.godId().equals(godId)) out.add(s);
        }
        return out;
    }

    /** Every living saint (for the readout). */
    public List<LivingSaint> all() { return new ArrayList<>(living.values()); }

    // ── Writes ───────────────────────────────────────────────────────────────

    /** Designates {@code beingId} the living saint of {@code godId} (replacing any
     *  prior status). No-op if already the saint of that god (idempotent). */
    public void add(UUID beingId, String godId, long now, boolean isPlayer) {
        if (beingId == null || godId == null) return;
        if (isLivingSaint(beingId, godId)) return;          // idempotent
        living.put(beingId, new LivingSaint(beingId, godId, now, isPlayer));
        setDirty();
    }

    /** Revokes {@code beingId}'s living-saint status (lapse / apostasy). */
    public void remove(UUID beingId) {
        if (beingId != null && living.remove(beingId) != null) setDirty();
    }

    // ── Canonized roster + Venerable candidates (SR2) ────────────────────────

    /** The saint record for {@code saintId} (canonized OR pending Venerable), or empty. */
    public Optional<Saint> saint(UUID saintId) {
        return saintId == null ? Optional.empty() : Optional.ofNullable(saints.get(saintId));
    }

    /** Every CANONIZED saint (excludes pending Venerables). */
    public List<Saint> canonizedSaints() {
        List<Saint> out = new ArrayList<>();
        for (Saint s : saints.values()) if (s.canonized()) out.add(s);
        return out;
    }

    /** Pending Venerable candidates (not yet elevated). */
    public List<Saint> venerables() {
        List<Saint> out = new ArrayList<>();
        for (Saint s : saints.values()) if (!s.canonized()) out.add(s);
        return out;
    }

    /** Canonized saints of {@code religionId}. */
    public List<Saint> saintsOf(String religionId) {
        List<Saint> out = new ArrayList<>();
        if (religionId != null) for (Saint s : saints.values()) {
            if (s.canonized() && s.religionId().equals(religionId)) out.add(s);
        }
        return out;
    }

    /** The canonized saint whose grave is at {@code pos}, if any. */
    public Optional<Saint> saintAtGrave(BlockPos pos) {
        if (pos == null) return Optional.empty();
        for (Saint s : saints.values()) {
            if (s.canonized() && pos.equals(s.gravePos())) return Optional.of(s);
        }
        return Optional.empty();
    }

    /** Records or replaces a saint record (canonized or Venerable) + persists. */
    public void putSaint(Saint s) {
        if (s == null) return;
        saints.put(s.saintId(), s);
        setDirty();
    }

    /** The nearest saint/Venerable whose grave is within {@code radius} (horizontal) of
     *  {@code pos} — the SR3 prayer target. Canonized and Venerable both qualify. */
    public Optional<Saint> nearestSaintGrave(BlockPos pos, int radius) {
        if (pos == null) return Optional.empty();
        Saint best = null;
        double bestD = Double.MAX_VALUE;
        for (Saint s : saints.values()) {
            double dx = pos.getX() - s.gravePos().getX();
            double dz = pos.getZ() - s.gravePos().getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d <= radius && d < bestD) { best = s; bestD = d; }
        }
        return Optional.ofNullable(best);
    }

    // ── Prayer cooldown (SR3) ────────────────────────────────────────────────

    private static String prayKey(UUID playerId, UUID saintId) {
        return playerId + "|" + saintId;
    }

    /** True when {@code playerId} may pray to {@code saintId} now (≥ {@code cooldown}
     *  ticks since their last prayer to this saint). */
    public boolean canPray(UUID playerId, UUID saintId, long now, long cooldown) {
        if (playerId == null || saintId == null) return false;
        Long last = prayerCooldowns.get(prayKey(playerId, saintId));
        return last == null || now - last >= cooldown;
    }

    /** Stamps {@code playerId}'s prayer to {@code saintId} at {@code now}. */
    public void recordPrayer(UUID playerId, UUID saintId, long now) {
        if (playerId == null || saintId == null) return;
        prayerCooldowns.put(prayKey(playerId, saintId), now);
        setDirty();
    }

    public void markDirty() { setDirty(); }
}
