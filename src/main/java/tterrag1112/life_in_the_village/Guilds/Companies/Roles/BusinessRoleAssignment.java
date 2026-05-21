package tterrag1112.life_in_the_village.Guilds.Companies.Roles;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Phase 6.3.3.c.3 — a single business-internal role currently held.
 * Persisted on {@code Business} as part of its codec; the legacy
 * {@code BUSINESS_FOREMAN} / {@code BUSINESS_BOOKKEEPER} office
 * holdings are migrated into this form on first load.
 */
public record BusinessRoleAssignment(Identifier roleId,
                                     UUID holderUuid,
                                     long appointedTick,
                                     UUID appointedBy) {

    public static final Codec<BusinessRoleAssignment> CODEC =
            RecordCodecBuilder.create(i -> i.group(
                    Identifier.CODEC.fieldOf("roleId").forGetter(BusinessRoleAssignment::roleId),
                    UUIDUtil.CODEC.fieldOf("holderUuid").forGetter(BusinessRoleAssignment::holderUuid),
                    Codec.LONG.fieldOf("appointedTick").forGetter(BusinessRoleAssignment::appointedTick),
                    UUIDUtil.CODEC.fieldOf("appointedBy").forGetter(BusinessRoleAssignment::appointedBy)
            ).apply(i, BusinessRoleAssignment::new));
}
