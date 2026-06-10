package tterrag1112.life_in_the_village.Npc.Religion.Saints;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
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

    public static final SavedDataType<SaintsSavedData> TYPE = new SavedDataType<>(
            STORAGE,
            SaintsSavedData::new,
            RecordCodecBuilder.create(i -> i.group(
                    LivingSaint.CODEC.listOf().optionalFieldOf("livingSaints", List.of())
                            .forGetter(d -> new ArrayList<>(d.living.values()))
            ).apply(i, SaintsSavedData::fromCodec)));

    /** beingId → its living-saint status (a being is the Holy of at most one god). */
    private final Map<UUID, LivingSaint> living = new LinkedHashMap<>();

    public SaintsSavedData() {}

    private static SaintsSavedData fromCodec(List<LivingSaint> loaded) {
        SaintsSavedData d = new SaintsSavedData();
        if (loaded != null) for (LivingSaint s : loaded) {
            if (s != null) d.living.put(s.beingId(), s);
        }
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

    public void markDirty() { setDirty(); }
}
