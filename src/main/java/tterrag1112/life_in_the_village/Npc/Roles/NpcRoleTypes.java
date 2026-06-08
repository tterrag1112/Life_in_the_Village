package tterrag1112.life_in_the_village.Npc.Roles;

import net.minecraft.resources.Identifier;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 6.3.2.a — registry of built-in role types. Static-initialiser
 * registration mirrors the {@code ProfessionRoleManager} parser
 * pattern (no DeferredRegister machinery needed since roles aren't
 * a Minecraft registry — they're a mod-internal taxonomy).
 *
 * <p>Each constant is paired with its default lifetime:
 * <ul>
 *   <li>Caravan / apprenticeship / party-member roles: CONDITIONAL —
 *       owning subsystem (CaravanSavedData, ApprenticeshipManager,
 *       AdventurerSavedData) controls when the role is removed.</li>
 *   <li>Greeting: TIMED with the GreeterAssignment TTL applied at
 *       assignment time (per-assignment, not registry-default).</li>
 *   <li>Professional: CONDITIONAL — workshop/farm role assigners
 *       overwrite on rotation tick.</li>
 *   <li>Visitor: CONDITIONAL — VisitorState bridges this role.</li>
 * </ul>
 */
public final class NpcRoleTypes {

    private NpcRoleTypes() {}

    private static final Map<Identifier, RoleType> BY_ID = new LinkedHashMap<>();

    private static RoleType register(String path, RoleLifetime defaultLifetime) {
        Identifier id = Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, path);
        RoleType type = new RoleType(id, defaultLifetime);
        BY_ID.put(id, type);
        return type;
    }

    // ── Caravan ──────────────────────────────────────────────────────────
    public static final RoleType CARAVAN_PRINCIPAL =
            register("caravan_principal", RoleLifetime.Conditional.INSTANCE);
    public static final RoleType CARAVAN_ESCORT =
            register("caravan_escort", RoleLifetime.Conditional.INSTANCE);
    public static final RoleType CARAVAN_CARRIER =
            register("caravan_carrier", RoleLifetime.Conditional.INSTANCE);

    // ── Apprenticeship ──────────────────────────────────────────────────
    public static final RoleType APPRENTICE_TO =
            register("apprentice_to", RoleLifetime.Conditional.INSTANCE);
    public static final RoleType MASTER_OF =
            register("master_of", RoleLifetime.Conditional.INSTANCE);

    // ── Adventurer party ─────────────────────────────────────────────────
    public static final RoleType PARTY_MEMBER =
            register("party_member", RoleLifetime.Conditional.INSTANCE);

    // ── Pilgrimage (Religion R3e-3b) ─────────────────────────────────────
    /** Marks a resident who has departed on a religious pilgrimage as a
     *  single-member traveller — the away-state mirror of CARAVAN_PRINCIPAL.
     *  {@code PilgrimageSavedData} controls removal on return/reintegration. */
    public static final RoleType PILGRIM =
            register("pilgrim", RoleLifetime.Conditional.INSTANCE);

    // ── Greeter (transient) ──────────────────────────────────────────────
    public static final RoleType GREETING =
            register("greeting", RoleLifetime.Conditional.INSTANCE);

    // ── Profession role (promoted from ProfessionRoleManager) ────────────
    public static final RoleType PROFESSIONAL =
            register("professional", RoleLifetime.Conditional.INSTANCE);

    // ── Visitor bridge ───────────────────────────────────────────────────
    public static final RoleType VISITOR =
            register("visitor", RoleLifetime.Conditional.INSTANCE);

    public static Optional<RoleType> byId(Identifier id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    // Parameter key constants — used across projection points to keep
    // strings consistent.
    public static final String P_CARAVAN_ID       = "caravanId";
    public static final String P_CARAVAN_STATE    = "state";
    public static final String P_MASTER_UUID      = "masterUuid";
    public static final String P_APPRENTICE_UUID  = "apprenticeUuid";
    public static final String P_CONTRACT_ID      = "contractId";
    public static final String P_PROFESSION       = "profession";
    public static final String P_GROUP_ID         = "groupId";
    public static final String P_IS_LEADER        = "isLeader";
    public static final String P_PLAYER_UUID      = "playerUuid";
    public static final String P_ROLE_NAME        = "role";     // e.g. "FarmRole:HARVESTER"
}
