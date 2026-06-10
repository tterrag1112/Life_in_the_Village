package tterrag1112.life_in_the_village.Village.Graveyard;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
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
 * Religion Rework R5a — persistent per-village {@link Graveyard} store (mirrors
 * the R4 saved-data pattern: a UUID-keyed map with its own {@code SavedDataType}).
 * A village with no graveyard simply has no entry — deaths there fall back to the
 * FUNERAL rite's abstract remembrance (no grave, no error).
 */
public class GraveyardSavedData extends SavedData {

    private static final Codec<UUID> UUID_STRING_KEY =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final SavedDataType<GraveyardSavedData> TYPE = new SavedDataType<>(
            "life_in_the_village_graveyards",
            GraveyardSavedData::new,
            RecordCodecBuilder.create(i -> i.group(
                    Codec.unboundedMap(UUID_STRING_KEY, Graveyard.CODEC)
                            .optionalFieldOf("graveyards", Map.of())
                            .forGetter(d -> Map.copyOf(d.graveyards))
            ).apply(i, GraveyardSavedData::fromCodec)));

    private final Map<UUID, Graveyard> graveyards = new HashMap<>();

    public GraveyardSavedData() {}

    private static GraveyardSavedData fromCodec(Map<UUID, Graveyard> graveyards) {
        GraveyardSavedData d = new GraveyardSavedData();
        if (graveyards != null) d.graveyards.putAll(graveyards);
        return d;
    }

    public static GraveyardSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public Optional<Graveyard> getGraveyard(UUID villageId) {
        return Optional.ofNullable(graveyards.get(villageId));
    }

    /**
     * Creates (or replaces) a village graveyard at {@code centre} with a
     * {@code rows × cols} grid of grave slots, {@code spacing} blocks apart.
     * Manual-creation path (no auto-layout this phase).
     */
    public Graveyard createGraveyard(UUID villageId, BlockPos centre,
                                     int rows, int cols, int spacing) {
        List<BlockPos> slots = new ArrayList<>();
        int r = Math.max(1, rows), c = Math.max(1, cols), s = Math.max(1, spacing);
        int x0 = centre.getX() - (c - 1) * s / 2;
        int z0 = centre.getZ() - (r - 1) * s / 2;
        for (int ri = 0; ri < r; ri++) {
            for (int ci = 0; ci < c; ci++) {
                slots.add(new BlockPos(x0 + ci * s, centre.getY(), z0 + ri * s));
            }
        }
        Graveyard g = new Graveyard(villageId, centre, slots, List.of());
        graveyards.put(villageId, g);
        setDirty();
        return g;
    }

    /**
     * Buries {@code deceasedId} in {@code villageId}'s graveyard if one exists
     * (capacity-bounded, reuse-oldest). Returns the grave, or empty when the
     * village has no graveyard (graceful — the FUNERAL rite still remembers them).
     */
    public Optional<Grave> bury(UUID villageId, UUID deceasedId, String name, long deathTick) {
        Graveyard g = graveyards.get(villageId);
        if (g == null) return Optional.empty();
        Optional<Grave> grave = g.bury(deceasedId, name, deathTick);
        if (grave.isPresent()) setDirty();
        return grave;
    }

    /** The grave of {@code deceasedId} in ANY village graveyard (graveyards are few). */
    public Optional<Grave> graveOf(UUID deceasedId) {
        if (deceasedId == null) return Optional.empty();
        for (Graveyard g : graveyards.values()) {
            Optional<Grave> grave = g.graveOf(deceasedId);
            if (grave.isPresent()) return grave;
        }
        return Optional.empty();
    }

    /** SR2 — inscribe {@code deceasedId}'s grave epitaph wherever it is buried. */
    public boolean inscribeEpitaph(UUID deceasedId, String epitaph) {
        for (Graveyard g : graveyards.values()) {
            if (g.inscribe(deceasedId, epitaph)) { setDirty(); return true; }
        }
        return false;
    }
}
