package tterrag1112.life_in_the_village.Npc.Roles;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Phase 6.3.2.a — lifetime semantics for a {@link RoleAssignment}.
 *
 * <ul>
 *   <li>{@link Permanent}: never auto-expires; cleared only by explicit
 *       {@link NpcRoleComponent#removeRole} or external system event.</li>
 *   <li>{@link Timed}: expires automatically when game time passes
 *       {@code expiryTick}; {@code NpcRoleComponent.tickRoles} prunes.</li>
 *   <li>{@link Conditional}: lifetime governed by the owning subsystem
 *       (caravan / apprenticeship / party). The role stays until the
 *       owning system explicitly removes it — the component does not
 *       prune.</li>
 * </ul>
 */
public sealed interface RoleLifetime
        permits RoleLifetime.Permanent, RoleLifetime.Timed, RoleLifetime.Conditional {

    /** True if {@code currentTick} is past the role's expiry. */
    boolean isExpired(long currentTick);

    /** Discriminator for codec dispatch. */
    String kind();

    record Permanent() implements RoleLifetime {
        public static final Permanent INSTANCE = new Permanent();
        public static final MapCodec<Permanent> CODEC = MapCodec.unit(INSTANCE);
        @Override public boolean isExpired(long currentTick) { return false; }
        @Override public String kind() { return "permanent"; }
    }

    record Timed(long expiryTick) implements RoleLifetime {
        public static final MapCodec<Timed> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.LONG.fieldOf("expiryTick").forGetter(Timed::expiryTick)
        ).apply(i, Timed::new));
        @Override public boolean isExpired(long currentTick) { return currentTick >= expiryTick; }
        @Override public String kind() { return "timed"; }
    }

    record Conditional() implements RoleLifetime {
        public static final Conditional INSTANCE = new Conditional();
        public static final MapCodec<Conditional> CODEC = MapCodec.unit(INSTANCE);
        @Override public boolean isExpired(long currentTick) { return false; }
        @Override public String kind() { return "conditional"; }
    }

    Codec<RoleLifetime> CODEC = Codec.STRING.dispatch("kind",
            RoleLifetime::kind,
            kind -> switch (kind) {
                case "permanent"   -> Permanent.CODEC;
                case "timed"       -> Timed.CODEC;
                case "conditional" -> Conditional.CODEC;
                default -> throw new IllegalArgumentException("Unknown lifetime kind: " + kind);
            });
}
