package tterrag1112.life_in_the_village.Entities.Goals.Profession;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Village.Building;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Central manager for all profession role assignments.
 *
 * <h3>Storage</h3>
 * Roles are stored in a single PersistentData key {@code "professionRole"}
 * as {@code "TYPE:VALUE"} — e.g. {@code "FarmRole:HARVESTER"} or
 * {@code "WorkshopRole:PRODUCER"}. This means any NPC has exactly one
 * role key regardless of how many role types exist.
 *
 * <h3>Registry</h3>
 * Each role enum registers a parser via {@link #register} from a
 * {@code static} block in the enum class. This is called automatically
 * when the enum's class is first loaded, which happens as soon as any
 * goal or manager references the enum.
 *
 * <h3>Worker finding</h3>
 * {@link #findWorkersForBuilding} is the canonical way to locate all
 * NPCs assigned to a given building within a search radius. Both
 * {@code FarmRoleAssigner} and {@code WorkshopRoleAssigner} use it.
 */
public final class ProfessionRoleManager {

    private ProfessionRoleManager() {}

    // ── NBT key ───────────────────────────────────────────────────────────────
    private static final String NBT_KEY        = "professionRole";
    private static final int    SEARCH_RADIUS  = 128;

    // ── Parser registry ───────────────────────────────────────────────────────
    // Maps type prefix (e.g. "FarmRole") → parser function (e.g. FarmRole::valueOf)
    private static final Map<String, Function<String, tterrag1112.life_in_the_village.Profession.Roles.ProfessionRole>> PARSERS =
            new HashMap<>();

    /**
     * Registers a role enum's parser. Call from a {@code static} block
     * in the role enum class.
     *
     * @param typePrefix  simple class name used as the storage prefix
     * @param parser      {@code EnumType::valueOf} reference
     */
    public static void register(String typePrefix,
                                Function<String, tterrag1112.life_in_the_village.Profession.Roles.ProfessionRole> parser) {
        PARSERS.put(typePrefix, parser);
    }

    // =========================================================================
    // Get / set
    // =========================================================================

    /**
     * Stores a role in the NPC's PersistentData.
     * Format: {@code "TypePrefix:ROLE_NAME"}
     */
    public static void setRole(TownspersonMob npc, tterrag1112.life_in_the_village.Profession.Roles.ProfessionRole role) {
        String prefix = role.getClass().getSimpleName();
        npc.getPersistentData().putString(NBT_KEY,
                prefix + ":" + role.name());
    }

    /**
     * Reads the NPC's current role regardless of type.
     * Returns {@code null} if no role is set or the type is unrecognised.
     */
    @Nullable
    public static tterrag1112.life_in_the_village.Profession.Roles.ProfessionRole getRole(TownspersonMob npc) {
        String stored = npc.getPersistentData()
                .getString(NBT_KEY).orElse("");
        if (stored.isEmpty()) return null;

        int sep = stored.indexOf(':');
        if (sep < 0) return null;

        String prefix = stored.substring(0, sep);
        String value  = stored.substring(sep + 1);

        Function<String, tterrag1112.life_in_the_village.Profession.Roles.ProfessionRole> parser = PARSERS.get(prefix);
        if (parser == null) return null;

        try {
            return parser.apply(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Reads the NPC's role and casts it to the expected type.
     * Returns {@code null} if the role is absent or belongs to a
     * different role type (e.g. asking for WorkshopRole but they have FarmRole).
     */
    @Nullable
    public static <T extends tterrag1112.life_in_the_village.Profession.Roles.ProfessionRole> T getRole(TownspersonMob npc,
                                                                                                        Class<T> type) {
        tterrag1112.life_in_the_village.Profession.Roles.ProfessionRole role = getRole(npc);
        if (type.isInstance(role)) return type.cast(role);
        return null;
    }

    /**
     * Clears the role — NPC will be treated as a generalist by all goals
     * until {@link #setRole} is called again.
     */
    public static void clearRole(TownspersonMob npc) {
        npc.getPersistentData().remove(NBT_KEY);
    }

    // =========================================================================
    // Convenience checks — work on any ProfessionRole
    // =========================================================================

    public static boolean isGeneralist(TownspersonMob npc) {
        tterrag1112.life_in_the_village.Profession.Roles.ProfessionRole role = getRole(npc);
        return role == null || role.isGeneralist();
    }

    public static boolean isMarketSeller(TownspersonMob npc) {
        tterrag1112.life_in_the_village.Profession.Roles.ProfessionRole role = getRole(npc);
        return role != null && role.isMarketSeller();
    }

    public static boolean splitsTime(TownspersonMob npc) {
        tterrag1112.life_in_the_village.Profession.Roles.ProfessionRole role = getRole(npc);
        return role == null || role.splitsTime();
    }

    // =========================================================================
    // Worker finding — shared by all role assigners
    // =========================================================================

    /**
     * Returns all living TownspersonMob entities whose assigned building
     * matches the given building ID, within {@value SEARCH_RADIUS} blocks
     * of the building's origin.
     */
    public static List<TownspersonMob> findWorkersForBuilding(
            ServerLevel level, Building building) {
        BlockPos origin = building.getShape().getOrigin();
        AABB area = new AABB(origin).inflate(SEARCH_RADIUS);
        return level.getEntitiesOfClass(
                TownspersonMob.class, area,
                npc -> npc.getAssignedBuildingId()
                        .map(id -> id.equals(building.getId()))
                        .orElse(false));
    }
}