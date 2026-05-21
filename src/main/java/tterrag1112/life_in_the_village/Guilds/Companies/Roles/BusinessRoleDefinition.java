package tterrag1112.life_in_the_village.Guilds.Companies.Roles;

import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import tterrag1112.life_in_the_village.Npc.Office.OfficePower;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Phase 6.3.3.c.3 — descriptor for a business-internal role (foreman,
 * bookkeeper, future content roles). Parallels {@code OfficeDefinition}
 * but business-scoped: the holder is bound to a single {@code Business}
 * instance, not to a civic org.
 */
public record BusinessRoleDefinition(Identifier id,
                                     Component displayName,
                                     Set<OfficePower> powers,
                                     int defaultCardinality) {

    public BusinessRoleDefinition {
        powers = Set.copyOf(new LinkedHashSet<>(powers));
        if (defaultCardinality <= 0) defaultCardinality = 1;
    }

    /** Codec round-trips by id; the full definition is resolved via
     *  {@link BusinessRoleRegistry}. */
    public static final Codec<BusinessRoleDefinition> CODEC = Identifier.CODEC.xmap(
            id -> BusinessRoleRegistry.byId(id)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown BusinessRoleDefinition: " + id)),
            BusinessRoleDefinition::id);
}
