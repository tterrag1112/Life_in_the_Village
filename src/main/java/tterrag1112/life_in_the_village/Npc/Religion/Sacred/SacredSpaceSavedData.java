package tterrag1112.life_in_the_village.Npc.Religion.Sacred;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Npc.Religion.BuildingFaith;
import tterrag1112.life_in_the_village.Utilities.PosUtil;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sacred Space S3 — the per-world store of decaying theophany <b>imprints</b>: the
 * first location-tied, persisted divine state. A glory theophany sanctifies the
 * ground where it occurs; the imprint <b>decays over time</b>, its decay <b>slowed by
 * a nearby player {@code SHRINE}</b> of the god's faith, its strength <b>raised by
 * nearby rites</b>. Neglect lets it fade back to ordinary ground (pruned).
 *
 * <p>Mirrors {@link tterrag1112.life_in_the_village.Village.Graveyard.GraveyardSavedData}
 * / {@code RiteSavedData}: own {@link SavedDataType} + storage name, a codec over the
 * collection, {@code markDirty} on change. <b>Lazy decay (relax-on-read)</b> like
 * {@code DivineFavour} — current strength is computed on query from
 * {@code (anchorStrength, anchorTick)}; no per-tick global scan. Pruning is light
 * (drop ~0-strength imprints on access).</p>
 *
 * <p>Folds into {@link SacredSpace#explain} as the third sacredness source, so it
 * composes with natural + built sacredness and therefore every S1b/S2 effect with no
 * extra wiring. <b>Glory only</b> this stage — wrath writes no imprint (profanity /
 * negative sacredness is a future option).</p>
 */
public class SacredSpaceSavedData extends SavedData {

    private static final String STORAGE = "life_in_the_village_sacred_imprints";

    // ── Tuning (decay / create / refresh / radii) ────────────────────────────
    /** A fresh theophany imprint's strength (MAJOR-tier: a new site reads strongly
     *  sacred — {@link SacrednessTier#MAJOR_AT} is 1.5). */
    public static final float INITIAL_STRENGTH = 2.0f;
    /** A rite's refresh raises strength toward this cap. */
    public static final float STRENGTH_CAP = 3.0f;
    /** Strength a nearby rite of the god's faith adds (toward the cap). */
    public static final float RITE_BOOST = 0.5f;
    /** Lifetime (ticks) over which an imprint decays linearly to 0 — ~6 in-game days. */
    public static final long  LIFETIME_TICKS = 144000L;
    /** A nearby {@code SHRINE} of the god's faith multiplies the lifetime (slows decay). */
    public static final float SHRINE_LIFETIME_MULT = 3.0f;
    /** Horizontal range an imprint's sacredness reaches a query position. */
    public static final int   IMPRINT_RADIUS = 24;
    /** Horizontal range within which a rite refreshes an imprint. */
    public static final int   REFRESH_RADIUS = 24;
    /** Horizontal range within which a shrine slows an imprint's decay. */
    public static final int   SHRINE_SLOW_RADIUS = 32;

    /**
     * One sanctified site: the god, where, when it was born, and the decay anchor
     * ({@code anchorStrength} at {@code anchorTick} — a rite refresh resets both).
     */
    public record Imprint(String godId, BlockPos pos, long bornTick,
                          float anchorStrength, long anchorTick) {
        public static final Codec<Imprint> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("godId").forGetter(Imprint::godId),
                BlockPos.CODEC.fieldOf("pos").forGetter(Imprint::pos),
                Codec.LONG.fieldOf("bornTick").forGetter(Imprint::bornTick),
                Codec.FLOAT.fieldOf("anchorStrength").forGetter(Imprint::anchorStrength),
                Codec.LONG.fieldOf("anchorTick").forGetter(Imprint::anchorTick)
        ).apply(i, Imprint::new));

        /** A stable map key (a god may imprint a spot once; distinct gods don't collide). */
        String key() { return godId + "@" + pos.asLong(); }
    }

    public static final SavedDataType<SacredSpaceSavedData> TYPE = new SavedDataType<>(
            STORAGE,
            SacredSpaceSavedData::new,
            RecordCodecBuilder.create(i -> i.group(
                    Imprint.CODEC.listOf().optionalFieldOf("imprints", List.of())
                            .forGetter(d -> new ArrayList<>(d.imprints.values()))
            ).apply(i, SacredSpaceSavedData::fromCodec)));

    private final Map<String, Imprint> imprints = new LinkedHashMap<>();

    public SacredSpaceSavedData() {}

    private static SacredSpaceSavedData fromCodec(List<Imprint> loaded) {
        SacredSpaceSavedData d = new SacredSpaceSavedData();
        if (loaded != null) for (Imprint im : loaded) {
            if (im != null) d.imprints.put(im.key(), im);
        }
        return d;
    }

    public static SacredSpaceSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ── Decay (lazy, shrine-slowed) ──────────────────────────────────────────

    /** {@code im}'s current strength at {@code now}: a linear decay to 0 over
     *  {@link #LIFETIME_TICKS} (×{@link #SHRINE_LIFETIME_MULT} when a same-faith shrine
     *  is near), from {@code anchorStrength} at {@code anchorTick}. 0 once expired. */
    private float currentStrength(ServerLevel level, Imprint im, long now) {
        float lifetime = LIFETIME_TICKS;
        if (BuildingFaith.hasBuildingOfGodNear(level, im.godId(), im.pos(),
                SHRINE_SLOW_RADIUS, t -> t == BuildingType.SHRINE)) {
            lifetime *= SHRINE_LIFETIME_MULT;
        }
        float frac = 1f - (now - im.anchorTick()) / lifetime;
        return frac <= 0f ? 0f : im.anchorStrength() * frac;
    }

    // ── Query (the SacredSpace fold) ─────────────────────────────────────────

    /** The summed current strength of {@code godId}'s imprints within
     *  {@link #IMPRINT_RADIUS} (horizontal) of {@code pos}. Lazily prunes any imprint
     *  that has fully decayed (read-time, no global scan). */
    public float imprintPotency(ServerLevel level, String godId, BlockPos pos, long now) {
        if (godId == null || pos == null || imprints.isEmpty()) return 0f;
        PosUtil at = new PosUtil(pos);
        float sum = 0f;
        boolean pruned = false;
        for (Iterator<Map.Entry<String, Imprint>> it = imprints.entrySet().iterator(); it.hasNext(); ) {
            Imprint im = it.next().getValue();
            float s = currentStrength(level, im, now);
            if (s <= 0f) { it.remove(); pruned = true; continue; }   // faded → prune
            if (!im.godId().equals(godId)) continue;
            if (at.horizontalDistanceTo(im.pos()) <= IMPRINT_RADIUS) sum += s;
        }
        if (pruned) setDirty();
        return sum;
    }

    /** True when a same-faith {@code SHRINE} is slowing the decay of any of
     *  {@code godId}'s imprints near {@code pos} (for the readout). */
    public boolean isShrineTended(ServerLevel level, String godId, BlockPos pos) {
        return BuildingFaith.hasBuildingOfGodNear(level, godId, pos,
                SHRINE_SLOW_RADIUS, t -> t == BuildingType.SHRINE);
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /** Sacred Space S3 — a glory theophany CREATES an imprint at {@code pos} (replacing
     *  any prior imprint of the same god at the same spot). */
    public void addImprint(String godId, BlockPos pos, long now, float strength) {
        if (godId == null || pos == null) return;
        Imprint im = new Imprint(godId, pos.immutable(), now, Math.min(STRENGTH_CAP, strength), now);
        imprints.put(im.key(), im);
        setDirty();
    }

    /** A rite of {@code godId}'s faith near {@code pos} REFRESHES existing imprints of
     *  that god within {@link #REFRESH_RADIUS}: raises strength toward {@link
     *  #STRENGTH_CAP} and resets the decay anchor. Rites maintain sacred ground; they
     *  do NOT seed new imprints (sacredness originates from the divine event). */
    public void refreshNear(ServerLevel level, String godId, BlockPos pos, long now) {
        if (godId == null || pos == null || imprints.isEmpty()) return;
        PosUtil at = new PosUtil(pos);
        boolean changed = false;
        for (Map.Entry<String, Imprint> e : imprints.entrySet()) {
            Imprint im = e.getValue();
            if (!im.godId().equals(godId)) continue;
            if (at.horizontalDistanceTo(im.pos()) > REFRESH_RADIUS) continue;
            float raised = Math.min(STRENGTH_CAP, currentStrength(level, im, now) + RITE_BOOST);
            e.setValue(new Imprint(im.godId(), im.pos(), im.bornTick(), raised, now));
            changed = true;
        }
        if (changed) setDirty();
    }

    public void markDirty() { setDirty(); }
}
