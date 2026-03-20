package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.VillageBookScreen;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.*;

public record OpenVillageBookPacket(
        UUID villageId,
        String villageName,
        String leaderName,
        String tierName,
        int population,
        long treasuryBronze,
        // Houses: id, name, price, taxPerWeek, owned, occupied
        List<HouseEntry> houses,
        // Needs: category name → level name
        Map<String, String> needs,
        // Pending expansion type name, or empty
        String pendingExpansion,
        // Building type names present
        List<String> buildingTypes,
        // Player's current wealth in bronze
        long playerWealthBronze,
        int playerReputation,        // NEW — -1000 to 1000
        boolean playerHasWarning,    // NEW
        String kingdomName,          // NEW — empty if not in a kingdom
        String activeEventName,      // NEW — empty if none
        int tradeRouteCount
) implements CustomPacketPayload {

    public record HouseEntry(
            UUID buildingId,
            String name,
            long priceBronze,
            long taxPerWeekBronze,
            boolean ownedByThisPlayer,
            boolean ownedByOtherPlayer,
            boolean occupiedByNpc
    ) {}

    public static final Type<OpenVillageBookPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "open_village_book")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf,
            OpenVillageBookPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.villageId());
                buf.writeUtf(pkt.villageName());
                buf.writeUtf(pkt.leaderName());
                buf.writeUtf(pkt.tierName());
                buf.writeVarInt(pkt.population());
                buf.writeVarLong(pkt.treasuryBronze());

                buf.writeVarInt(pkt.houses().size());
                for (HouseEntry h : pkt.houses()) {
                    buf.writeUUID(h.buildingId());
                    buf.writeUtf(h.name());
                    buf.writeVarLong(h.priceBronze());
                    buf.writeVarLong(h.taxPerWeekBronze());
                    buf.writeBoolean(h.ownedByThisPlayer());
                    buf.writeBoolean(h.ownedByOtherPlayer());
                    buf.writeBoolean(h.occupiedByNpc());
                }

                buf.writeVarInt(pkt.needs().size());
                pkt.needs().forEach((k, v) -> {
                    buf.writeUtf(k);
                    buf.writeUtf(v);
                });

                buf.writeUtf(pkt.pendingExpansion());

                buf.writeVarInt(pkt.buildingTypes().size());
                pkt.buildingTypes().forEach(buf::writeUtf);

                buf.writeVarLong(pkt.playerWealthBronze());

                buf.writeVarInt(pkt.playerReputation());
                buf.writeBoolean(pkt.playerHasWarning());
                buf.writeUtf(pkt.kingdomName());
                buf.writeUtf(pkt.activeEventName());
                buf.writeVarInt(pkt.tradeRouteCount());
            },
            buf -> {
                UUID villageId     = buf.readUUID();
                String villageName = buf.readUtf();
                String leaderName  = buf.readUtf();
                String tierName    = buf.readUtf();
                int population     = buf.readVarInt();
                long treasury      = buf.readVarLong();

                int houseCount = buf.readVarInt();
                List<HouseEntry> houses = new ArrayList<>();
                for (int i = 0; i < houseCount; i++) {
                    houses.add(new HouseEntry(
                            buf.readUUID(), buf.readUtf(),
                            buf.readVarLong(), buf.readVarLong(),
                            buf.readBoolean(), buf.readBoolean(),
                            buf.readBoolean()));
                }

                int needCount = buf.readVarInt();
                Map<String, String> needs = new LinkedHashMap<>();
                for (int i = 0; i < needCount; i++)
                    needs.put(buf.readUtf(), buf.readUtf());

                String expansion = buf.readUtf();

                int btCount = buf.readVarInt();
                List<String> buildingTypes = new ArrayList<>();
                for (int i = 0; i < btCount; i++)
                    buildingTypes.add(buf.readUtf());

                long playerWealth = buf.readVarLong();

                int playerRep     = buf.readVarInt();
                boolean hasWarn   = buf.readBoolean();
                String kingdom    = buf.readUtf();
                String event      = buf.readUtf();
                int routeCount    = buf.readVarInt();

                return new OpenVillageBookPacket(villageId, villageName,
                        leaderName, tierName, population, treasury,
                        houses, needs, expansion, buildingTypes, playerWealth,
                        playerRep, hasWarn, kingdom, event, routeCount);
            }
    );

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(OpenVillageBookPacket pkt,
                              IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                net.minecraft.client.Minecraft.getInstance()
                        .setScreen(new VillageBookScreen(pkt)));
    }
}