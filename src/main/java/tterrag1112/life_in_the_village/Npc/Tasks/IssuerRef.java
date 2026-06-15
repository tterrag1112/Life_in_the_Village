package tterrag1112.life_in_the_village.Npc.Tasks;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Identifies the level entity that owns / issued a {@link Task} (and
 * keys the {@link TaskBoard} for that entity).
 *
 * @param level which kind of level entity this is
 * @param id    that entity's stable id (business id, household id,
 *              village id, NPC UUID, ...)
 */
public record IssuerRef(LevelKind level, UUID id) {

    public static final Codec<IssuerRef> CODEC = RecordCodecBuilder.create(i -> i.group(
            LevelKind.CODEC.fieldOf("level").forGetter(IssuerRef::level),
            UUIDUtil.CODEC.fieldOf("id").forGetter(IssuerRef::id)
    ).apply(i, IssuerRef::new));

    /**
     * Stable, parseable string key for map storage (e.g. in
     * {@link TaskSavedData}'s board map). Round-trips via {@link #parseKey}.
     */
    public String key() {
        return level.name() + ":" + id;
    }

    /** Inverse of {@link #key()}. */
    public static IssuerRef parseKey(String key) {
        int sep = key.indexOf(':');
        LevelKind lvl = LevelKind.valueOf(key.substring(0, sep));
        UUID id = UUID.fromString(key.substring(sep + 1));
        return new IssuerRef(lvl, id);
    }
}
