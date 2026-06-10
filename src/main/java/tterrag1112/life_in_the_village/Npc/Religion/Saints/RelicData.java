package tterrag1112.life_in_the_village.Npc.Religion.Saints;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/**
 * Saints &amp; Relics SR4 — the data component carried by a {@code RelicItem} stack: the
 * relic's identity. Mirrors {@code JobContractTerms}/{@code StallLeaseTerms} (a record
 * with a {@code CODEC} for persistence + a {@code STREAM_CODEC} for sync). The relic's
 * patron {@code godId} drives its carried benefit, portable prayer, and tooltip; the
 * {@code saintId} keys back to {@link SaintsSavedData.Saint} for the prayer.
 */
public record RelicData(UUID saintId, String saintName, String godId) {

    public static final Codec<RelicData> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("saintId").forGetter(RelicData::saintId),
            Codec.STRING.fieldOf("saintName").forGetter(RelicData::saintName),
            Codec.STRING.fieldOf("godId").forGetter(RelicData::godId)
    ).apply(i, RelicData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, RelicData> STREAM_CODEC =
            ByteBufCodecs.fromCodecWithRegistries(CODEC);
}
