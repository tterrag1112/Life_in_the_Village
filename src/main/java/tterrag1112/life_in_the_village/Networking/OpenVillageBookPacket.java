package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.VillageBookScreen;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.*;

public record OpenVillageBookPacket(
        UUID   villageId,
        String villageName,
        String leaderName,
        String tierName,
        int    population,
        long   treasuryBronze,
        List<HouseEntry>              houses,
        Map<String, String>           needs,
        String                        pendingExpansion,
        List<String>                  buildingTypes,
        long   playerWealthBronze,
        int    playerReputation,
        boolean playerHasWarning,
        String  kingdomName,
        String  activeEventName,
        int    tradeRouteCount,
        // Company fields
        boolean hasCompanyHere,
        UUID    companyId,
        String  companyName,
        int     companyWorkerCount,
        List<String>                  companyBuildingNames,
        // Per-building purchase entries for the Company Buildings page
        List<PurchasableBuildingEntry> purchasableBuildings
) implements CustomPacketPayload {

    // =========================================================================
    // NESTED RECORDS
    // =========================================================================

    /**
     * A house the player can potentially buy.
     * Equivalent record to the old HouseEntry — unchanged.
     */
    public record HouseEntry(
            UUID    buildingId,
            String  name,
            long    priceBronze,
            long    taxPerWeekBronze,
            boolean ownedByThisPlayer,
            boolean ownedByOtherPlayer,
            boolean occupiedByNpc
    ) {}

    /**
     * A non-house building in the village that a company can purchase.
     * Sent for every building that is not HOUSE, TOWN_HALL, or GUARD_TOWER
     * and is therefore potentially usable by a player company.
     *
     * price    — calculated server-side using the same footprint × rate
     *            formula as HousePurchaseManager so both pages are consistent.
     * inCompany — true if this building is already in the player's company.
     * buildingType — raw BuildingType enum name for ProducerType availability checks.
     */
    public record PurchasableBuildingEntry(
            UUID    buildingId,
            String  name,
            String  buildingType,
            long    priceBronze,
            boolean inCompany,
            boolean ownedByOtherCompany
    ) {}

    // =========================================================================
    // PACKET TYPE
    // =========================================================================

    public static final Type<OpenVillageBookPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "open_village_book")
    );

    // =========================================================================
    // STREAM CODEC
    // =========================================================================

    public static final StreamCodec<RegistryFriendlyByteBuf,
            OpenVillageBookPacket> CODEC = StreamCodec.of(

            // -----------------------------------------------------------------
            // ENCODER
            // -----------------------------------------------------------------
            (buf, pkt) -> {
                buf.writeUUID(pkt.villageId());
                buf.writeUtf(pkt.villageName());
                buf.writeUtf(pkt.leaderName());
                buf.writeUtf(pkt.tierName());
                buf.writeVarInt(pkt.population());
                buf.writeVarLong(pkt.treasuryBronze());

                // Houses
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

                // Needs
                buf.writeVarInt(pkt.needs().size());
                pkt.needs().forEach((k, v) -> {
                    buf.writeUtf(k);
                    buf.writeUtf(v);
                });

                buf.writeUtf(pkt.pendingExpansion());

                // Building type names
                buf.writeVarInt(pkt.buildingTypes().size());
                pkt.buildingTypes().forEach(buf::writeUtf);

                buf.writeVarLong(pkt.playerWealthBronze());
                buf.writeVarInt(pkt.playerReputation());
                buf.writeBoolean(pkt.playerHasWarning());
                buf.writeUtf(pkt.kingdomName());
                buf.writeUtf(pkt.activeEventName());
                buf.writeVarInt(pkt.tradeRouteCount());

                // Company fields
                buf.writeBoolean(pkt.hasCompanyHere());
                buf.writeUUID(pkt.companyId());
                buf.writeUtf(pkt.companyName());
                buf.writeVarInt(pkt.companyWorkerCount());
                buf.writeVarInt(pkt.companyBuildingNames().size());
                pkt.companyBuildingNames().forEach(buf::writeUtf);

                // Purchasable buildings
                buf.writeVarInt(pkt.purchasableBuildings().size());
                for (PurchasableBuildingEntry b : pkt.purchasableBuildings()) {
                    buf.writeUUID(b.buildingId());
                    buf.writeUtf(b.name());
                    buf.writeUtf(b.buildingType());
                    buf.writeVarLong(b.priceBronze());
                    buf.writeBoolean(b.inCompany());
                    buf.writeBoolean(b.ownedByOtherCompany());
                }
            },

            // -----------------------------------------------------------------
            // DECODER
            // -----------------------------------------------------------------
            buf -> {
                UUID   villageId   = buf.readUUID();
                String villageName = buf.readUtf();
                String leaderName  = buf.readUtf();
                String tierName    = buf.readUtf();
                int    population  = buf.readVarInt();
                long   treasury    = buf.readVarLong();

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

                long    playerWealth = buf.readVarLong();
                int     playerRep    = buf.readVarInt();
                boolean hasWarn      = buf.readBoolean();
                String  kingdom      = buf.readUtf();
                String  event        = buf.readUtf();
                int     routeCount   = buf.readVarInt();

                // Company fields
                boolean hasCompany   = buf.readBoolean();
                UUID    companyId    = buf.readUUID();
                String  companyName  = buf.readUtf();
                int     workerCount  = buf.readVarInt();

                int cbCount = buf.readVarInt();
                List<String> companyBuildings = new ArrayList<>();
                for (int i = 0; i < cbCount; i++)
                    companyBuildings.add(buf.readUtf());

                // Purchasable buildings
                int pbCount = buf.readVarInt();
                List<PurchasableBuildingEntry> purchasable = new ArrayList<>();
                for (int i = 0; i < pbCount; i++) {
                    purchasable.add(new PurchasableBuildingEntry(
                            buf.readUUID(),
                            buf.readUtf(),
                            buf.readUtf(),
                            buf.readVarLong(),
                            buf.readBoolean(),
                            buf.readBoolean()));
                }

                return new OpenVillageBookPacket(
                        villageId, villageName, leaderName, tierName,
                        population, treasury,
                        houses, needs, expansion, buildingTypes,
                        playerWealth, playerRep, hasWarn,
                        kingdom, event, routeCount,
                        hasCompany, companyId, companyName,
                        workerCount, companyBuildings,
                        purchasable);
            }
    );

    @Override
    public Type<?> type() { return TYPE; }

    // =========================================================================
    // CLIENT HANDLER
    // =========================================================================

    public static void handle(OpenVillageBookPacket pkt,
                              IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                net.minecraft.client.Minecraft.getInstance()
                        .setScreen(new VillageBookScreen(pkt)));
    }
}