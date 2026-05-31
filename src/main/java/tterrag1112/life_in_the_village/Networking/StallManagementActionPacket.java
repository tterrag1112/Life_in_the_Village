package tterrag1112.life_in_the_village.Networking;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.TradeHandler;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;
import java.util.UUID;

/**
 * A player-owned-stall management action (merchant arc Phase 5b):
 * {@code SET_PRICE} (clamped server-side to the ±20% band), {@code
 * CLEAR_PRICE} (revert to village price), {@code OPEN_CHEST} (open the
 * vanilla stall-chest container). Custom-price values are NEVER trusted
 * from the client — the server re-validates the band via {@link
 * MarketStall#setCustomPrice}.
 */
public record StallManagementActionPacket(
        Action action,
        UUID stallId,
        Identifier itemId,
        long price
) implements CustomPacketPayload {

    public enum Action { SET_PRICE, CLEAR_PRICE, OPEN_CHEST }

    public static final Type<StallManagementActionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "stall_management_action")
    );

    public static final StreamCodec<FriendlyByteBuf, StallManagementActionPacket> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeUtf(p.action().name());
                        buf.writeUUID(p.stallId());
                        buf.writeIdentifier(p.itemId());
                        buf.writeVarLong(p.price());
                    },
                    buf -> new StallManagementActionPacket(
                            Action.valueOf(buf.readUtf()),
                            buf.readUUID(),
                            buf.readIdentifier(),
                            buf.readVarLong())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(StallManagementActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!(player.level() instanceof ServerLevel level)) return;

            var data = tterrag1112.life_in_the_village.Networking.VillageSavedData.get(level);
            MarketStall stall = data.getStallById(pkt.stallId()).orElse(null);
            // Owner gate — only the PLAYER owner may manage this stall.
            if (stall == null || !stall.isActive()
                    || stall.getOwnerType() != MarketStall.OwnerType.PLAYER
                    || !stall.getOwnerUUID().equals(player.getUUID())) {
                return;
            }

            switch (pkt.action()) {
                case SET_PRICE -> {
                    Item item = BuiltInRegistries.ITEM.get(pkt.itemId())
                            .map(h -> h.value()).orElse(null);
                    if (item == null) return;
                    Optional<Village> village = data.getVillageAt(stall.getStallOrigin());
                    long base = MarketPriceHelper.getDynamicSellPrice(
                            level, village.orElse(null), item);
                    // Server re-validates the ±20% band (never trust client).
                    boolean ok = stall.setCustomPrice(item, pkt.price(), base);
                    if (!ok) {
                        player.displayClientMessage(Component.literal(
                                "Price must be within ±20% of the market price."), true);
                    }
                    data.setDirty();
                    TradeHandler.openStallManagement(player, stall); // refresh
                }
                case CLEAR_PRICE -> {
                    Item item = BuiltInRegistries.ITEM.get(pkt.itemId())
                            .map(h -> h.value()).orElse(null);
                    if (item == null) return;
                    stall.clearCustomPrice(item);
                    data.setDirty();
                    TradeHandler.openStallManagement(player, stall);
                }
                case OPEN_CHEST -> {
                    BlockEntity be = level.getBlockEntity(stall.getChestPos());
                    if (be instanceof MenuProvider provider) {
                        player.openMenu(provider);
                    } else {
                        player.displayClientMessage(Component.literal(
                                "Stall chest not found."), true);
                    }
                }
            }
        });
    }
}
