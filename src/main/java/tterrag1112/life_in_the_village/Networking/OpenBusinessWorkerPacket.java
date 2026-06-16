package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.BusinessWorkerScreen;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.*;

public record OpenBusinessWorkerPacket(
        UUID npcId,
        UUID businessId,
        String npcName,
        long wage,
        long minWage,
        String currentItemId,
        int currentTargetCount,
        String role,
        String producerType,
        List<AvailableItem> availableItems,
        List<CompanyBuildingEntry> companyBuildings,
        long currentMarketPrice,
        List<SellListing> currentSellListings   // items this seller is currently selling

) implements CustomPacketPayload {

    // -------------------------------------------------------------------------
    // Nested records
    // -------------------------------------------------------------------------

    /**
     * An item found across the business's buildings, with its merged stock
     * count and the current village market price for comparison.
     *
     * PB3 additions:
     *   hasRecipe       — true if BusinessProductionTaskSource.recipeFor found a
     *                     SkillRecipes entry for this item. Producer tab filters to
     *                     hasRecipe==true so only craftable items can be assigned.
     *   requiredSkill   — human-readable skill gate, e.g. "BLACKSMITHING ≥50",
     *                     empty if the recipe has no skill requirement.
     */
    public record AvailableItem(
            String itemId,
            String displayName,
            int stockCount,
            long marketPrice,
            boolean hasRecipe,      // PB3
            String requiredSkill    // PB3 — empty if no gate
    ) {
        /** Backward-compat constructor for callers that predate PB3. */
        public AvailableItem(String itemId, String displayName,
                             int stockCount, long marketPrice) {
            this(itemId, displayName, stockCount, marketPrice, false, "");
        }
    }

    public record SellListing(
            String itemId,
            String displayName,
            int    stockCount,
            long   customPrice,    // 0 = no override (use market)
            long   marketPrice
    ) {
        public long effectivePrice() {
            return customPrice > 0 ? customPrice : marketPrice;
        }
    }

    /** A building the business owns — sent so the client can determine
     *  which ProducerTypes are available without a server round-trip. */
    public record CompanyBuildingEntry(
            UUID buildingId,
            String buildingName,
            String buildingType   // BuildingType enum name
    ) {}

    // -------------------------------------------------------------------------
    // Packet identity
    // -------------------------------------------------------------------------

    public static final Type<OpenBusinessWorkerPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "open_company_worker")
    );

    // -------------------------------------------------------------------------
    // Stream codec
    // -------------------------------------------------------------------------

    public static final StreamCodec<RegistryFriendlyByteBuf,
            OpenBusinessWorkerPacket> CODEC = StreamCodec.of(

            // ENCODER
            (buf, pkt) -> {
                buf.writeUUID(pkt.npcId());
                buf.writeUUID(pkt.businessId());
                buf.writeUtf(pkt.npcName());
                buf.writeVarLong(pkt.wage());
                buf.writeVarLong(pkt.minWage());
                buf.writeUtf(pkt.currentItemId());
                buf.writeVarInt(pkt.currentTargetCount());
                buf.writeUtf(pkt.role());
                buf.writeUtf(pkt.producerType());

                // Available items (PB3: include hasRecipe + requiredSkill)
                buf.writeVarInt(pkt.availableItems().size());
                for (AvailableItem item : pkt.availableItems()) {
                    buf.writeUtf(item.itemId());
                    buf.writeUtf(item.displayName());
                    buf.writeVarInt(item.stockCount());
                    buf.writeVarLong(item.marketPrice());
                    buf.writeBoolean(item.hasRecipe());
                    buf.writeUtf(item.requiredSkill());
                }

                // Business buildings
                buf.writeVarInt(pkt.companyBuildings().size());
                for (CompanyBuildingEntry b : pkt.companyBuildings()) {
                    buf.writeUUID(b.buildingId());
                    buf.writeUtf(b.buildingName());
                    buf.writeUtf(b.buildingType());
                }

                buf.writeVarLong(pkt.currentMarketPrice());

                // Current sell listings
                buf.writeVarInt(pkt.currentSellListings().size());
                for (SellListing s : pkt.currentSellListings()) {
                    buf.writeUtf(s.itemId());
                    buf.writeUtf(s.displayName());
                    buf.writeVarInt(s.stockCount());
                    buf.writeVarLong(s.customPrice());
                    buf.writeVarLong(s.marketPrice());
                }
            },

            // DECODER
            buf -> {
                UUID   npcId         = buf.readUUID();
                UUID   businessId     = buf.readUUID();
                String npcName       = buf.readUtf();
                long   wage          = buf.readVarLong();
                long   minWage       = buf.readVarLong();
                String currentItemId = buf.readUtf();
                int    currentTarget = buf.readVarInt();
                String role          = buf.readUtf();
                String producerType  = buf.readUtf();

                int itemCount = buf.readVarInt();
                List<AvailableItem> items = new ArrayList<>();
                for (int i = 0; i < itemCount; i++) {
                    items.add(new AvailableItem(
                            buf.readUtf(),
                            buf.readUtf(),
                            buf.readVarInt(),
                            buf.readVarLong(),
                            buf.readBoolean(),
                            buf.readUtf()));
                }

                int buildingCount = buf.readVarInt();
                List<CompanyBuildingEntry> buildings = new ArrayList<>();
                for (int i = 0; i < buildingCount; i++) {
                    buildings.add(new CompanyBuildingEntry(
                            buf.readUUID(),
                            buf.readUtf(),
                            buf.readUtf()));
                }

                long currentMarketPrice = buf.readVarLong();

                int sellCount = buf.readVarInt();
                List<SellListing> sellListings = new ArrayList<>();
                for (int i = 0; i < sellCount; i++) {
                    sellListings.add(new SellListing(
                            buf.readUtf(),
                            buf.readUtf(),
                            buf.readVarInt(),
                            buf.readVarLong(),
                            buf.readVarLong()));
                }

                return new OpenBusinessWorkerPacket(
                        npcId, businessId, npcName,
                        wage, minWage,
                        currentItemId, currentTarget,
                        role, producerType,
                        items, buildings,
                        currentMarketPrice,
                        sellListings);
            }
    );

    @Override
    public Type<?> type() { return TYPE; }

    // -------------------------------------------------------------------------
    // Client-side handler
    // -------------------------------------------------------------------------

    public static void handle(OpenBusinessWorkerPacket pkt,
                              IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                net.minecraft.client.Minecraft.getInstance()
                        .setScreen(new BusinessWorkerScreen(pkt)));
    }
}
