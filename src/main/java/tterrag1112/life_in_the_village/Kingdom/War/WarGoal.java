package tterrag1112.life_in_the_village.Kingdom.War;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.List;
import java.util.UUID;

/**
 * Track D3.6.4 — sealed parameter union for war goals.
 *
 * <p>An attacker declares a war with one or more goals; the war's
 * outcome (on attacker victory) applies each goal as a specific
 * mechanical effect. Pattern mirrors {@code CharterParams} and
 * {@code PetitionPayload}: variant {@code MAP_CODEC}s composed
 * under {@code Codec.STRING.dispatch}.
 *
 * <ul>
 *   <li>{@link Territory} — list of village UUIDs to transfer.</li>
 *   <li>{@link Tribute} — one-shot or recurring treasury transfer.</li>
 *   <li>{@link Vassalize} — VASSALAGE treaty creation on win.</li>
 *   <li>{@link RegimeChange} — depose defender's ruler; succession
 *       fires with {@code conquered} legitimacy path.</li>
 * </ul>
 */
public sealed interface WarGoal permits
        WarGoal.Territory, WarGoal.Tribute, WarGoal.Vassalize, WarGoal.RegimeChange {

    Kind kind();

    enum Kind { TERRITORY, TRIBUTE, VASSALIZE, REGIME_CHANGE }

    Codec<WarGoal> CODEC = Codec.STRING.dispatch(
            "kind",
            g -> g.kind().name(),
            kn -> switch (Kind.valueOf(kn)) {
                case TERRITORY     -> Territory.MAP_CODEC;
                case TRIBUTE       -> Tribute.MAP_CODEC;
                case VASSALIZE     -> Vassalize.MAP_CODEC;
                case REGIME_CHANGE -> RegimeChange.MAP_CODEC;
            });

    record Territory(List<UUID> villageIds) implements WarGoal {
        @Override public Kind kind() { return Kind.TERRITORY; }

        public static final MapCodec<Territory> MAP_CODEC =
                RecordCodecBuilder.mapCodec(i -> i.group(
                        UUIDUtil.CODEC.listOf().fieldOf("villageIds")
                                .forGetter(Territory::villageIds)
                ).apply(i, Territory::new));
    }

    /**
     * One-shot or recurring tribute. {@code recurringDays = 0}
     * means one-shot transfer on war end; > 0 means new TRADE_DEAL
     * treaty established with built-in tribute rate.
     */
    record Tribute(long bronzeAmount, int recurringDays) implements WarGoal {
        @Override public Kind kind() { return Kind.TRIBUTE; }

        public static final MapCodec<Tribute> MAP_CODEC =
                RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.LONG.fieldOf("bronzeAmount").forGetter(Tribute::bronzeAmount),
                        Codec.INT.optionalFieldOf("recurringDays", 0)
                                .forGetter(Tribute::recurringDays)
                ).apply(i, Tribute::new));
    }

    record Vassalize() implements WarGoal {
        @Override public Kind kind() { return Kind.VASSALIZE; }

        public static final MapCodec<Vassalize> MAP_CODEC =
                MapCodec.unit(Vassalize::new);
    }

    record RegimeChange() implements WarGoal {
        @Override public Kind kind() { return Kind.REGIME_CHANGE; }

        public static final MapCodec<RegimeChange> MAP_CODEC =
                MapCodec.unit(RegimeChange::new);
    }
}
