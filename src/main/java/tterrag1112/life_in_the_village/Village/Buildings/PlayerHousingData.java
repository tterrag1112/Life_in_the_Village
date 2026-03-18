package tterrag1112.life_in_the_village.Village.Buildings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.*;

public class PlayerHousingData {

    public enum OwnershipType {
        PURCHASED,   // bought an existing house structure
        PLOT         // owns a plot, built freely
    }

    public record PlayerProperty(
            UUID playerId,
            UUID buildingId,
            UUID villageId,
            OwnershipType type,
            long purchaseTick,
            long lastTaxTick,
            long purchasePrice
    ) {
        public static final Codec<PlayerProperty> CODEC =
                RecordCodecBuilder.create(i -> i.group(
                        Codec.STRING.xmap(UUID::fromString,
                                        UUID::toString)
                                .fieldOf("playerId")
                                .forGetter(PlayerProperty::playerId),
                        Codec.STRING.xmap(UUID::fromString,
                                        UUID::toString)
                                .fieldOf("buildingId")
                                .forGetter(PlayerProperty::buildingId),
                        Codec.STRING.xmap(UUID::fromString,
                                        UUID::toString)
                                .fieldOf("villageId")
                                .forGetter(PlayerProperty::villageId),
                        Codec.STRING.xmap(OwnershipType::valueOf,
                                        OwnershipType::name)
                                .fieldOf("type")
                                .forGetter(PlayerProperty::type),
                        Codec.LONG.fieldOf("purchaseTick")
                                .forGetter(PlayerProperty::purchaseTick),
                        Codec.LONG.fieldOf("lastTaxTick")
                                .forGetter(PlayerProperty::lastTaxTick),
                        Codec.LONG.fieldOf("purchasePrice")
                                .forGetter(PlayerProperty::purchasePrice)
                ).apply(i, PlayerProperty::new));
    }
}