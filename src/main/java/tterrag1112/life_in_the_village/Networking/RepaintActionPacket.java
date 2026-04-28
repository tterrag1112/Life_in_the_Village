package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.ColorSlot;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.RepaintAuthorization;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.RepaintCost;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.RepaintJob;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Doc 15 §"Player-requested repaint" — client → server. Confirms a
 * repaint job: deducts dye + bronze, updates the
 * {@link Building}'s colour fields immediately, and attaches a
 * {@link RepaintJob} to the builder NPC so the
 * {@link tterrag1112.life_in_the_village.Entities.Goals.Profession
 * .Builder.BuilderRepaintGoal} picks it up on its next work tick.
 */
public record RepaintActionPacket(UUID npcId,
                                  UUID buildingId,
                                  byte primaryColor,
                                  byte accentColor,
                                  byte roofColor)
        implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepaintActionPacket.class);

    public static final Type<RepaintActionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "repaint_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RepaintActionPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeUUID(pkt.npcId());
                        buf.writeUUID(pkt.buildingId());
                        buf.writeByte(pkt.primaryColor());
                        buf.writeByte(pkt.accentColor());
                        buf.writeByte(pkt.roofColor());
                    },
                    buf -> new RepaintActionPacket(
                            buf.readUUID(), buf.readUUID(),
                            buf.readByte(), buf.readByte(), buf.readByte()));

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(RepaintActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel level)) return;
            apply(pkt, sp, level);
        });
    }

    private static void apply(RepaintActionPacket pkt, ServerPlayer sp, ServerLevel level) {
        // Resolve NPC.
        Entity ent = level.getEntity(pkt.npcId());
        if (!(ent instanceof TownspersonMob npc)
                || npc.getProfession() != Profession.BUILDER) {
            sp.displayClientMessage(Component.literal(
                    "Repaint failed: builder no longer available."), true);
            return;
        }
        if (npc.getRepaintJob() != null) {
            sp.displayClientMessage(Component.literal(
                    "Repaint failed: builder already has a job."), true);
            return;
        }

        // Resolve building.
        Building building = tterrag1112.life_in_the_village.Networking.VillageSavedData
                .get(level).getBuildingById(pkt.buildingId()).orElse(null);
        if (building == null) {
            sp.displayClientMessage(Component.literal(
                    "Repaint failed: building no longer exists."), true);
            return;
        }
        if (!RepaintAuthorization.isRepaintAllowed(level, building, sp)) {
            sp.displayClientMessage(Component.literal(
                    "Repaint failed: this building isn't yours to repaint."), true);
            return;
        }

        // Resolve variant for colorSlots.
        var variantOpt = tterrag1112.life_in_the_village.Village.Decoration
                .Variants.VariantRegistry.INSTANCE
                .resolve(building.getType(), building.getVariantId());
        Set<ColorSlot> variantSlots = variantOpt
                .map(v -> v.colorSlots())
                .orElse(EnumSet.of(ColorSlot.PRIMARY));

        DyeColor primary = decode(pkt.primaryColor());
        DyeColor accent  = decode(pkt.accentColor());
        DyeColor roof    = decode(pkt.roofColor());

        Set<ColorSlot> slotsToPaint = EnumSet.noneOf(ColorSlot.class);
        if (primary != null && variantSlots.contains(ColorSlot.PRIMARY)) {
            slotsToPaint.add(ColorSlot.PRIMARY);
        }
        if (accent != null && variantSlots.contains(ColorSlot.ACCENT)) {
            slotsToPaint.add(ColorSlot.ACCENT);
        }
        if (roof != null && variantSlots.contains(ColorSlot.ROOF)) {
            slotsToPaint.add(ColorSlot.ROOF);
        }
        if (slotsToPaint.isEmpty()) {
            sp.displayClientMessage(Component.literal(
                    "Repaint failed: no colour changes selected."), true);
            return;
        }

        // Cost.
        RepaintCost.Result cost = RepaintCost.calculate(building,
                slotsToPaint, primary, accent, roof);

        // Verify the player has the dye items.
        for (Map.Entry<DyeColor, Integer> e : cost.dyes().entrySet()) {
            int have = sp.getInventory().countItem(RepaintCost.dyeItemFor(e.getKey()));
            if (have < e.getValue()) {
                sp.displayClientMessage(Component.literal(
                        "Repaint failed: need " + e.getValue() + " "
                                + e.getKey().name().toLowerCase() + " dye, have " + have), true);
                return;
            }
        }
        // Verify bronze.
        long wealth = CoinHelper.getPlayerWealth(sp).toBronze();
        if (wealth < cost.bronzeFee()) {
            sp.displayClientMessage(Component.literal(
                    "Repaint failed: need " + cost.bronzeFee() + " bronze, have " + wealth), true);
            return;
        }

        // Deduct dyes + bronze.
        for (Map.Entry<DyeColor, Integer> e : cost.dyes().entrySet()) {
            removeDyeItems(sp, e.getKey(), e.getValue());
        }
        if (cost.bronzeFee() > 0) {
            CoinHelper.playerPay(sp, CurrencyValue.of(cost.bronzeFee()));
        }

        // Update Building immediately so save-data reflects intent.
        if (slotsToPaint.contains(ColorSlot.PRIMARY)) building.setPrimaryColor(primary);
        if (slotsToPaint.contains(ColorSlot.ACCENT))  building.setAccentColor(accent);
        if (slotsToPaint.contains(ColorSlot.ROOF))    building.setRoofColor(roof);
        tterrag1112.life_in_the_village.Networking.VillageSavedData.get(level).markDirty();

        // Attach job to builder.
        npc.setRepaintJob(RepaintJob.fresh(
                building.getId(), sp.getUUID(),
                slotsToPaint, primary, accent, roof));

        sp.displayClientMessage(Component.literal(
                "Repaint commissioned: " + building.getName()
                        + " (" + cost.totalDyeItems() + " dye, "
                        + cost.bronzeFee() + " bronze)"), false);
        LOGGER.info("RepaintActionPacket: {} commissioned repaint of {} "
                + "(slots={}, primary={}, accent={}, roof={})",
                sp.getName().getString(), building.getName(),
                slotsToPaint, primary, accent, roof);
    }

    /** Decode a wire byte (-1 = null, 0..15 = DyeColor.ordinal). */
    private static DyeColor decode(byte b) {
        if (b < 0 || b >= DyeColor.values().length) return null;
        return DyeColor.values()[b];
    }

    private static void removeDyeItems(ServerPlayer sp, DyeColor color, int count) {
        var item = RepaintCost.dyeItemFor(color);
        int remaining = count;
        for (int i = 0; i < sp.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = sp.getInventory().getItem(i);
            if (stack.is(item)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
                if (stack.isEmpty()) sp.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
    }
}
