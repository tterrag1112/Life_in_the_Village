package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.Optional;

/**
 * F1b sub-stage 1a — the thin per-world religion accessor facade. One home for the
 * per-world lookup, so sub-stage 1b's call sites read cleanly
 * ({@code Religions.find(level, id)}) and the static {@link ReligionRegistry} can be
 * restricted to template-only once callers move here.
 *
 * <p>Pure delegation to {@link ReligionSavedData#get(ServerLevel)}; no caller in 1a
 * besides the {@code /religion world list} readout (1b migrates the rest).</p>
 */
public final class Religions {

    private Religions() {}

    /** The world's religion with {@code id}, or empty when none. */
    public static Optional<Religion> find(ServerLevel level, String id) {
        return ReligionSavedData.get(level).find(id);
    }

    /** The world's religion with {@code id}, or null when none. */
    public static Religion get(ServerLevel level, String id) {
        return ReligionSavedData.get(level).get(id);
    }

    /** Every religion live in the world (stable order). */
    public static Collection<Religion> all(ServerLevel level) {
        return ReligionSavedData.get(level).all();
    }
}
