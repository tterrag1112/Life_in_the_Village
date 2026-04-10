package tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop;

import tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop.ProfessionSpecialization;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Central manager for profession specialization assignments.
 *
 * <p>Runs in parallel to {@link ProfessionRoleManager} but uses a separate
 * NBT key so an NPC can have a role <em>and</em> a specialization at the
 * same time. Structure and conventions mirror {@code ProfessionRoleManager}
 * exactly.</p>
 *
 * <h3>Storage</h3>
 * Specializations are stored in PersistentData under {@code "professionSpec"}
 * as {@code "TYPE:VALUE"} — e.g. {@code "BlacksmithSpecialization:TOOLSMITH"}.
 *
 * <h3>Registry</h3>
 * Each specialization enum registers a parser via {@link #register} from a
 * {@code static} block in the enum class, the same way
 * {@link ProfessionRoleManager} handles roles.
 */
public final class SpecializationManager {

    private SpecializationManager() {}

    // ── NBT key ───────────────────────────────────────────────────────────────
    private static final String NBT_KEY = "professionSpec";

    // ── Parser registry ───────────────────────────────────────────────────────
    private static final Map<String, Function<String, ProfessionSpecialization>> PARSERS =
            new HashMap<>();

    /**
     * Registers a specialization enum's parser. Call from a {@code static}
     * block in the enum class.
     *
     * @param typePrefix simple class name used as the storage prefix
     * @param parser     {@code EnumType::valueOf} reference
     */
    public static void register(String typePrefix,
                                Function<String, ProfessionSpecialization> parser) {
        PARSERS.put(typePrefix, parser);
    }

    // =========================================================================
    // Get / set
    // =========================================================================

    /**
     * Stores a specialization in the NPC's PersistentData.
     * Format: {@code "TypePrefix:SPEC_NAME"}.
     */
    public static void setSpecialization(TownspersonMob npc,
                                         ProfessionSpecialization spec) {
        String prefix = spec.getClass().getSimpleName();
        npc.getPersistentData().putString(NBT_KEY,
                prefix + ":" + spec.name());
    }

    /**
     * Reads the NPC's current specialization regardless of type.
     * Returns {@code null} if none is set or the type is unknown.
     */
    @Nullable
    public static ProfessionSpecialization getSpecialization(TownspersonMob npc) {
        String stored = npc.getPersistentData().getString(NBT_KEY).orElse("");
        if (stored.isEmpty()) return null;

        int sep = stored.indexOf(':');
        if (sep < 0) return null;

        String prefix = stored.substring(0, sep);
        String value  = stored.substring(sep + 1);

        Function<String, ProfessionSpecialization> parser = PARSERS.get(prefix);
        if (parser == null) return null;

        try {
            return parser.apply(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Reads the NPC's specialization and casts it to the expected type.
     * Returns {@code null} if the NPC has no specialization, or one of
     * a different type (e.g. asking for BlacksmithSpecialization when
     * they have WeaverSpecialization).
     */
    @Nullable
    public static <T extends ProfessionSpecialization> T getSpecialization(
            TownspersonMob npc, Class<T> type) {
        ProfessionSpecialization spec = getSpecialization(npc);
        if (type.isInstance(spec)) return type.cast(spec);
        return null;
    }

    /** Clears the NPC's specialization — treated as generalist thereafter. */
    public static void clearSpecialization(TownspersonMob npc) {
        npc.getPersistentData().remove(NBT_KEY);
    }

    /** True if the NPC has any specialization assigned. */
    public static boolean hasSpecialization(TownspersonMob npc) {
        return getSpecialization(npc) != null;
    }
}