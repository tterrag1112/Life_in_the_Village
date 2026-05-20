package tterrag1112.life_in_the_village.Networking;

import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Components.ModDataComponents;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Items.StallLeaseTerms;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStallPlacer;

import java.util.Optional;
import java.util.UUID;

/**
 * Client → server: confirm a stall-lease purchase from
 * {@code StallLeaseScreen}. Server charges the player, mints a
 * {@link tterrag1112.life_in_the_village.Items.StallLeaseItem} bound
 * to the player UUID, with the chosen duration.
 *
 * <p>{@code durationDays == 0} = outright purchase (the lease item's
 * {@code durationTicks} stored as {@link Long#MAX_VALUE}).</p>
 */
public record StallLeaseActionPacket(UUID merchantId, int durationDays,
                                     boolean bindToMarket)
        implements CustomPacketPayload {

    public static final Type<StallLeaseActionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "stall_lease_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StallLeaseActionPacket> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeUUID(p.merchantId);
                        buf.writeVarInt(p.durationDays);
                        buf.writeBoolean(p.bindToMarket);
                    },
                    buf -> new StallLeaseActionPacket(
                            buf.readUUID(), buf.readVarInt(), buf.readBoolean()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(StallLeaseActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel level)) return;
            TownspersonMob merchant = TownspersonMob.findByUUID(level, pkt.merchantId)
                    .orElse(null);
            if (merchant == null) return;

            VillageSavedData data = VillageSavedData.get(level);
            Building market = merchant.getAssignedBuildingId()
                    .flatMap(data::getBuildingById)
                    .filter(b -> b.getType() == BuildingType.MARKET)
                    .orElse(null);
            if (market == null) {
                sp.displayClientMessage(Component.literal(
                                "[" + merchant.getNpcName() + "] I'm not a market merchant.")
                        .withStyle(ChatFormatting.RED), false);
                return;
            }

            boolean purchase = pkt.durationDays <= 0;
            long cost = purchase
                    ? MarketStallPlacer.PURCHASE_PRICE
                    : MarketStallPlacer.RENT_PER_DAY * (long) pkt.durationDays;
            long wealth = CoinHelper.getPlayerWealth(sp).toBronze();
            if (wealth < cost) {
                sp.displayClientMessage(Component.literal(
                                "[" + merchant.getNpcName() + "] You need "
                                        + (cost - wealth) + " more bronze.")
                        .withStyle(ChatFormatting.RED), false);
                return;
            }
            CoinHelper.playerPay(sp, CurrencyValue.of(cost));
            merchant.getAssignedVillageName().flatMap(data::getVillageByName)
                    .ifPresent(v -> v.depositToTreasury(cost));

            StallLeaseTerms terms = new StallLeaseTerms(
                    sp.getUUID(),
                    purchase ? Long.MAX_VALUE : (long) pkt.durationDays * 24000L,
                    pkt.bindToMarket ? Optional.of(market.getId()) : Optional.empty());
            ItemStack lease = new ItemStack(ModItems.STALL_LEASE.get());
            lease.set(ModDataComponents.STALL_LEASE_TERMS.get(), terms);
            if (!sp.getInventory().add(lease)) {
                sp.drop(lease, false);
            }
            sp.displayClientMessage(Component.literal(
                            "[" + merchant.getNpcName() + "] "
                                    + (purchase ? "Stall purchased — " : "Stall leased for "
                                    + pkt.durationDays + " days — ")
                                    + cost + " bronze paid. Right-click me again to claim.")
                    .withStyle(ChatFormatting.GREEN), false);
        });
    }
}
