package tterrag1112.life_in_the_village.Npc.Roles;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 6.3.2.a — a single role currently held by an NPC.
 *
 * <p>Parameters use {@code Map<String, String>} for codec simplicity:
 * UUIDs serialize via {@link UUID#toString()}, longs via
 * {@link Long#toString(long)}, enums via {@code name()}, booleans via
 * {@code "true"/"false"}. Accessor helpers below decode.
 *
 * <p>The Mojang-style {@code Map<String, Tag>} would technically be more
 * flexible but bloats the codec for the heterogeneous-but-narrow values
 * the role projections actually carry.
 */
public record RoleAssignment(RoleType type,
                             Map<String, String> parameters,
                             RoleLifetime lifetime) {

    public static final Codec<RoleAssignment> CODEC = RecordCodecBuilder.create(i -> i.group(
            RoleType.CODEC.fieldOf("type").forGetter(RoleAssignment::type),
            Codec.unboundedMap(Codec.STRING, Codec.STRING)
                    .optionalFieldOf("parameters", Map.of())
                    .forGetter(RoleAssignment::parameters),
            RoleLifetime.CODEC.fieldOf("lifetime").forGetter(RoleAssignment::lifetime)
    ).apply(i, RoleAssignment::new));

    public RoleAssignment {
        parameters = Map.copyOf(parameters);
    }

    /** Convenience: build a permanent role of {@code type} with no parameters. */
    public static RoleAssignment permanent(RoleType type) {
        return new RoleAssignment(type, Map.of(), RoleLifetime.Permanent.INSTANCE);
    }

    /** Convenience: build a permanent role with parameters. */
    public static RoleAssignment permanent(RoleType type, Map<String, String> params) {
        return new RoleAssignment(type, params, RoleLifetime.Permanent.INSTANCE);
    }

    /** Convenience: build a conditional role (owning system controls lifetime). */
    public static RoleAssignment conditional(RoleType type, Map<String, String> params) {
        return new RoleAssignment(type, params, RoleLifetime.Conditional.INSTANCE);
    }

    /** Convenience: build a TTL-bound role expiring at {@code expiryTick}. */
    public static RoleAssignment timed(RoleType type, Map<String, String> params, long expiryTick) {
        return new RoleAssignment(type, params, new RoleLifetime.Timed(expiryTick));
    }

    /** Replace parameters; preserves type + lifetime. */
    public RoleAssignment withParameters(Map<String, String> next) {
        return new RoleAssignment(type, next, lifetime);
    }

    public Optional<String>  param(String key)        { return Optional.ofNullable(parameters.get(key)); }
    public Optional<UUID>    paramUuid(String key)    { return param(key).map(UUID::fromString); }
    public Optional<Long>    paramLong(String key)    { return param(key).map(Long::parseLong); }
    public Optional<Boolean> paramBool(String key)    { return param(key).map(Boolean::parseBoolean); }
}
