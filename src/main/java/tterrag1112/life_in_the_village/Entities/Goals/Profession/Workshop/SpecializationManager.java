package tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Specialization.NpcSpecializationTypes;
import tterrag1112.life_in_the_village.Npc.Specialization.SpecializationDef;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Phase 6.3.2.c — promoted onto {@link
 * tterrag1112.life_in_the_village.Npc.Specialization.NpcSpecializationComponent}.
 *
 * <p>External API ({@link #setSpecialization}/{@link #getSpecialization}/
 * {@link #clearSpecialization}/{@link #hasSpecialization} + the parser
 * registry) is preserved so {@code AbstractProductionBehavior},
 * {@code BlacksmithProductionBehavior}, and other existing callers
 * compile unmodified. Internal storage now lives in the unified
 * Specialization component; the {@code "professionSpec"} legacy NBT
 * key is migrated once via {@link #migrateLegacyNbt}.
 */
public final class SpecializationManager {

    private SpecializationManager() {}

    private static final String LEGACY_NBT_KEY = "professionSpec";

    /** Parser registry kept for the legacy enum API; new code routes
     *  via NpcSpecializationTypes by ResourceLocation. */
    private static final Map<String, Function<String, ProfessionSpecialization>> PARSERS = new HashMap<>();

    public static void register(String typePrefix,
                                Function<String, ProfessionSpecialization> parser) {
        PARSERS.put(typePrefix, parser);
    }

    // =========================================================================
    // Get / set — bridge to component
    // =========================================================================

    public static void setSpecialization(TownspersonMob npc,
                                         ProfessionSpecialization spec) {
        SpecializationDef def = SpecializationDefBridge.toDef(spec);
        if (def != null) {
            // Explicit grant — force=true bypasses the skill gate. This
            // matches the legacy semantics of the old NBT setter.
            npc.getSpecializationComponent().assign(def, npc, true);
        } else {
            npc.getSpecializationComponent().clear();
        }
    }

    /**
     * Phase 6.3.2.c — gated assignment path. Consults
     * {@link tterrag1112.life_in_the_village.Npc.Skills.SpecializationGate#qualifies}
     * before writing. Returns true on success, false when the NPC
     * doesn't meet the spec's skill requirements. Used by
     * non-administrative assignment paths (auto-promotion candidates,
     * future content gates); admin / debug paths continue using the
     * un-gated {@link #setSpecialization} above.
     */
    public static boolean trySetSpecialization(TownspersonMob npc,
                                               ProfessionSpecialization spec) {
        SpecializationDef def = SpecializationDefBridge.toDef(spec);
        if (def == null) { npc.getSpecializationComponent().clear(); return true; }
        return npc.getSpecializationComponent().assign(def, npc, false);
    }

    @Nullable
    public static ProfessionSpecialization getSpecialization(TownspersonMob npc) {
        return npc.getSpecializationComponent().get()
                .map(SpecializationDefBridge::toEnum)
                .orElse(null);
    }

    @Nullable
    public static <T extends ProfessionSpecialization> T getSpecialization(
            TownspersonMob npc, Class<T> type) {
        ProfessionSpecialization spec = getSpecialization(npc);
        if (type.isInstance(spec)) return type.cast(spec);
        return null;
    }

    public static void clearSpecialization(TownspersonMob npc) {
        npc.getSpecializationComponent().clear();
    }

    public static boolean hasSpecialization(TownspersonMob npc) {
        return npc.getSpecializationComponent().get().isPresent();
    }

    // =========================================================================
    // Legacy migration
    // =========================================================================

    /**
     * Phase 6.3.2.c — one-shot translation of the legacy {@code
     * "professionSpec"} PersistentData entry into the unified
     * Specialization component. Called from {@code TownspersonMob.load}
     * after the component has loaded its own data; if the component
     * already holds a spec the legacy value is ignored (cleared
     * regardless to retire the key).
     */
    public static void migrateLegacyNbt(TownspersonMob npc) {
        String stored = npc.getPersistentData().getString(LEGACY_NBT_KEY).orElse("");
        if (stored.isEmpty()) return;
        if (npc.getSpecializationComponent().get().isEmpty()) {
            int sep = stored.indexOf(':');
            if (sep > 0) {
                String prefix = stored.substring(0, sep);
                String value  = stored.substring(sep + 1);
                Function<String, ProfessionSpecialization> parser = PARSERS.get(prefix);
                if (parser != null) {
                    try {
                        ProfessionSpecialization spec = parser.apply(value);
                        SpecializationDef def = SpecializationDefBridge.toDef(spec);
                        if (def != null) {
                            npc.getSpecializationComponent().assign(def, npc, true);
                        }
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }
        npc.getPersistentData().remove(LEGACY_NBT_KEY);
    }
}
