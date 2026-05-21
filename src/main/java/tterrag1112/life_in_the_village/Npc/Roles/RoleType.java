package tterrag1112.life_in_the_village.Npc.Roles;

import com.mojang.serialization.Codec;
import net.minecraft.resources.Identifier;

/**
 * Phase 6.3.2.a — descriptor for a kind of role an NPC can hold.
 * Stored in {@link NpcRoleTypes} as named constants; behaviors and
 * subsystems reference them by {@link #name() ResourceLocation}.
 *
 * <p>Pure data: each instance just bundles a name with a default
 * lifetime. RoleAssignment carries the actual per-instance lifetime
 * (which may diverge from the default — e.g. a normally-permanent
 * role can carry a Timed expiry for one-off expiring assignments).
 */
public record RoleType(Identifier name, RoleLifetime defaultLifetime) {

    public static final Codec<RoleType> CODEC = Identifier.CODEC.xmap(
            id -> NpcRoleTypes.byId(id).orElseGet(
                    () -> new RoleType(id, RoleLifetime.Permanent.INSTANCE)),
            RoleType::name);

    @Override
    public String toString() { return name.toString(); }
}
