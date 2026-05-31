package tterrag1112.life_in_the_village.Village.Travel;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/**
 * Phase 5e — a network-ready snapshot of one in-transit {@link
 * TravellingGroup} for the kingdom map's traveller layer. Built once per
 * map sync (server) and interpolated client-side: the client advances
 * {@link #progress} per frame and indexes the already-synced route
 * polyline for the {@code {originVillageId, destVillageId}} pair.
 *
 * <p>The traveller is matched to its route by the unordered village pair
 * (+ {@link #isSea}), not a route id — the route key ({@code connectionId})
 * is null for graph-based land routes, so the village pair is the reliable
 * join. A plain DTO (UUIDs / primitives / strings) — safe to cache and to
 * hold inside the immutable {@code KingdomMapData}.
 *
 * @param typeId {@link TravellerType} ordinal (see {@link #type()})
 * @param cargo  short pre-rendered cargo summary for the tooltip
 */
public record TravellerSnapshot(
        UUID groupId,
        int typeId,
        UUID originVillageId,
        UUID destVillageId,
        boolean isSea,
        float progress,
        boolean reversed,
        float speed,
        String originName,
        String destName,
        String cargo
) {

    public TravellerType type() { return TravellerType.byId(typeId); }

    public static final StreamCodec<ByteBuf, TravellerSnapshot> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public TravellerSnapshot decode(ByteBuf buf) {
                    FriendlyByteBuf b = new FriendlyByteBuf(buf);
                    UUID groupId = b.readUUID();
                    int typeId   = b.readVarInt();
                    UUID origin  = b.readUUID();
                    UUID dest    = b.readUUID();
                    boolean sea  = b.readBoolean();
                    float progress = b.readFloat();
                    boolean reversed = b.readBoolean();
                    float speed  = b.readFloat();
                    String originName = b.readUtf();
                    String destName   = b.readUtf();
                    String cargo      = b.readUtf();
                    return new TravellerSnapshot(groupId, typeId, origin, dest, sea,
                            progress, reversed, speed, originName, destName, cargo);
                }

                @Override
                public void encode(ByteBuf buf, TravellerSnapshot s) {
                    FriendlyByteBuf b = new FriendlyByteBuf(buf);
                    b.writeUUID(s.groupId());
                    b.writeVarInt(s.typeId());
                    b.writeUUID(s.originVillageId());
                    b.writeUUID(s.destVillageId());
                    b.writeBoolean(s.isSea());
                    b.writeFloat(s.progress());
                    b.writeBoolean(s.reversed());
                    b.writeFloat(s.speed());
                    b.writeUtf(s.originName());
                    b.writeUtf(s.destName());
                    b.writeUtf(s.cargo());
                }
            };
}
