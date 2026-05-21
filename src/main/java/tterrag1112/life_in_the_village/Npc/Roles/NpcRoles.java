package tterrag1112.life_in_the_village.Npc.Roles;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 6.3.2.a — thin static helper over {@link NpcRoleComponent} for
 * the common "does this NPC have role X (optionally matching parameter
 * Y)?" patterns. Behaviors / managers go through here so reads stay
 * consistent across the codebase.
 */
public final class NpcRoles {

    private NpcRoles() {}

    public static boolean hasRole(TownspersonMob npc, RoleType type) {
        return npc.getRoles().hasRole(type);
    }

    public static boolean hasAnyRole(TownspersonMob npc, RoleType... types) {
        NpcRoleComponent c = npc.getRoles();
        for (RoleType t : types) if (c.hasRole(t)) return true;
        return false;
    }

    public static Optional<RoleAssignment> getRole(TownspersonMob npc, RoleType type) {
        return npc.getRoles().getRole(type);
    }

    public static Optional<UUID> getRoleUuid(TownspersonMob npc, RoleType type, String paramKey) {
        return getRole(npc, type).flatMap(a -> a.paramUuid(paramKey));
    }

    /** True if the NPC is currently a caravan participant (principal/escort/carrier). */
    public static boolean isOnCaravan(TownspersonMob npc) {
        return hasAnyRole(npc,
                NpcRoleTypes.CARAVAN_PRINCIPAL,
                NpcRoleTypes.CARAVAN_ESCORT,
                NpcRoleTypes.CARAVAN_CARRIER);
    }

    /** True if the NPC is currently in an active apprenticeship (either side). */
    public static boolean isInApprenticeship(TownspersonMob npc) {
        return hasAnyRole(npc, NpcRoleTypes.APPRENTICE_TO, NpcRoleTypes.MASTER_OF);
    }

    /** True if the NPC is currently an active adventurer party member. */
    public static boolean isInParty(TownspersonMob npc) {
        return hasRole(npc, NpcRoleTypes.PARTY_MEMBER);
    }

    public static boolean isPartyLeader(TownspersonMob npc) {
        return getRole(npc, NpcRoleTypes.PARTY_MEMBER)
                .flatMap(a -> a.paramBool(NpcRoleTypes.P_IS_LEADER))
                .orElse(false);
    }
}
