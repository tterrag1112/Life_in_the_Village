package tterrag1112.life_in_the_village.Entities.Goals.Profession;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes;
import tterrag1112.life_in_the_village.Npc.Roles.RoleAssignment;
import tterrag1112.life_in_the_village.Profession.Roles.ProfessionRole;
import tterrag1112.life_in_the_village.Village.Building;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Phase 6.3.2.a — promoted onto {@code NpcRoleComponent}.
 *
 * <p>External API unchanged ({@link #setRole}/{@link #getRole}/{@link #clearRole}
 * and the convenience checks) so existing call sites (FarmRoleAssigner,
 * WorkshopRoleAssigner, behavior consumers) continue to compile. Internal
 * storage now lives on the unified Role axis as a single
 * {@link NpcRoleTypes#PROFESSIONAL} role whose {@code "role"} parameter
 * carries the legacy {@code "Prefix:NAME"} string.
 *
 * <h3>Legacy migration</h3>
 * <p>The old PersistentData key {@code "professionRole"} is read once on
 * entity load via {@link #migrateLegacyNbt}; on the first hit the value
 * is translated into a role-component assignment and the legacy key is
 * cleared.
 */
public final class ProfessionRoleManager {

    private ProfessionRoleManager() {}

    private static final String LEGACY_NBT_KEY = "professionRole";
    private static final int    SEARCH_RADIUS  = 128;

    /** Parser registry: type prefix (e.g. "FarmRole") → enum-valueOf reference. */
    private static final Map<String, Function<String, ProfessionRole>> PARSERS = new HashMap<>();

    public static void register(String typePrefix, Function<String, ProfessionRole> parser) {
        PARSERS.put(typePrefix, parser);
    }

    // =========================================================================
    // Get / set (Role-component-backed)
    // =========================================================================

    public static void setRole(TownspersonMob npc, ProfessionRole role) {
        String encoded = role.getClass().getSimpleName() + ":" + role.name();
        npc.getRoles().assignRole(RoleAssignment.conditional(
                NpcRoleTypes.PROFESSIONAL,
                Map.of(NpcRoleTypes.P_ROLE_NAME, encoded)));
    }

    @Nullable
    public static ProfessionRole getRole(TownspersonMob npc) {
        String encoded = npc.getRoles().getRole(NpcRoleTypes.PROFESSIONAL)
                .flatMap(a -> a.param(NpcRoleTypes.P_ROLE_NAME))
                .orElse("");
        return decode(encoded);
    }

    @Nullable
    public static <T extends ProfessionRole> T getRole(TownspersonMob npc, Class<T> type) {
        ProfessionRole role = getRole(npc);
        if (type.isInstance(role)) return type.cast(role);
        return null;
    }

    public static void clearRole(TownspersonMob npc) {
        npc.getRoles().removeRole(NpcRoleTypes.PROFESSIONAL);
    }

    // =========================================================================
    // Convenience checks
    // =========================================================================

    public static boolean isGeneralist(TownspersonMob npc) {
        ProfessionRole role = getRole(npc);
        return role == null || role.isGeneralist();
    }

    public static boolean isMarketSeller(TownspersonMob npc) {
        ProfessionRole role = getRole(npc);
        return role != null && role.isMarketSeller();
    }

    public static boolean splitsTime(TownspersonMob npc) {
        ProfessionRole role = getRole(npc);
        return role == null || role.splitsTime();
    }

    public static boolean isApprentice(TownspersonMob npc) {
        ProfessionRole role = getRole(npc);
        return role != null && role.isApprentice();
    }

    // =========================================================================
    // Legacy migration
    // =========================================================================

    /**
     * Phase 6.3.2.a — one-shot read of the legacy {@code "professionRole"}
     * PersistentData entry, called from {@code TownspersonMob.load} after
     * the role component has loaded its own data. If both exist the
     * role-component value wins (already loaded); the legacy key is
     * cleared regardless.
     */
    public static void migrateLegacyNbt(TownspersonMob npc) {
        String stored = npc.getPersistentData().getString(LEGACY_NBT_KEY).orElse("");
        if (stored.isEmpty()) return;
        if (!npc.getRoles().hasRole(NpcRoleTypes.PROFESSIONAL)) {
            ProfessionRole role = decode(stored);
            if (role != null) {
                npc.getRoles().assignRole(RoleAssignment.conditional(
                        NpcRoleTypes.PROFESSIONAL,
                        Map.of(NpcRoleTypes.P_ROLE_NAME, stored)));
            }
        }
        npc.getPersistentData().remove(LEGACY_NBT_KEY);
    }

    @Nullable
    private static ProfessionRole decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        int sep = encoded.indexOf(':');
        if (sep < 0) return null;
        String prefix = encoded.substring(0, sep);
        String value  = encoded.substring(sep + 1);
        Function<String, ProfessionRole> parser = PARSERS.get(prefix);
        if (parser == null) return null;
        try { return parser.apply(value); }
        catch (IllegalArgumentException e) { return null; }
    }

    // =========================================================================
    // Worker finding — shared by all role assigners (unchanged)
    // =========================================================================

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
