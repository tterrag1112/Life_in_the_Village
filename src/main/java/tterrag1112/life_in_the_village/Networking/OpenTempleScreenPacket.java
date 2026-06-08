package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.ArrayList;
import java.util.List;

/**
 * Server→client. Opens {@link tterrag1112.life_in_the_village.Gui.TempleScreen}
 * for a religious building (TEMPLE / CHAPEL / SHRINE). Religion Rework R9b.
 *
 * <p>Carries a fully server-computed, read-only snapshot of the building's
 * otherwise-invisible R4 economy and R4c decay, plus faith / consecration /
 * candle / clergy / congregation / upcoming-festival state — everything the
 * screen renders. Mirrors the {@code OpenBusinessFrontPacket} pattern (the
 * Open packet IS the data; no separate sync this phase). All fields are plain
 * scalars / strings so the record is trivially encoded; empty strings / false /
 * 0 are the graceful empty state (a vacant or non-religious building renders
 * cleanly, no crash).</p>
 */
public record OpenTempleScreenPacket(
        // Identity
        String buildingName,
        String buildingTypeName,
        String villageName,
        String faithName,        // "" = no resolved faith
        String deityName,        // "" = none

        // Economy (R4) + derived health
        long   treasury,
        long   dailyCost,
        long   surplus,          // treasury − solvency buffer (may be negative)
        int    daysInsolvent,
        String healthState,      // Flourishing / Solvent / At-risk / Decaying / Abandoned

        // Condition (R4c)
        String condition,        // BuildingCondition name
        boolean decaying,

        // Consecration (R3b-1)
        boolean consecrated,

        // Candles (R4b)
        int     candleCount,

        // Clergy
        boolean staffed,
        String  clergyName,      // "" = vacant
        String  clergyOrder,     // "" = generalist / vacant
        String  clergyTitle,     // "" = vacant

        // Congregation
        int     congregationCount,
        float   aggregatePiety,  // 0..1 average primary strength of served adherents

        // Upcoming holy days / festivals
        List<String> upcoming
) implements CustomPacketPayload {

    public OpenTempleScreenPacket {
        upcoming = List.copyOf(upcoming);
    }

    public static final Type<OpenTempleScreenPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "open_temple_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTempleScreenPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeUtf(pkt.buildingName());
                        buf.writeUtf(pkt.buildingTypeName());
                        buf.writeUtf(pkt.villageName());
                        buf.writeUtf(pkt.faithName());
                        buf.writeUtf(pkt.deityName());

                        buf.writeVarLong(pkt.treasury());
                        buf.writeVarLong(pkt.dailyCost());
                        buf.writeLong(pkt.surplus());
                        buf.writeVarInt(pkt.daysInsolvent());
                        buf.writeUtf(pkt.healthState());

                        buf.writeUtf(pkt.condition());
                        buf.writeBoolean(pkt.decaying());

                        buf.writeBoolean(pkt.consecrated());

                        buf.writeVarInt(pkt.candleCount());

                        buf.writeBoolean(pkt.staffed());
                        buf.writeUtf(pkt.clergyName());
                        buf.writeUtf(pkt.clergyOrder());
                        buf.writeUtf(pkt.clergyTitle());

                        buf.writeVarInt(pkt.congregationCount());
                        buf.writeFloat(pkt.aggregatePiety());

                        buf.writeVarInt(pkt.upcoming().size());
                        for (String s : pkt.upcoming()) buf.writeUtf(s);
                    },
                    buf -> {
                        String buildingName = buf.readUtf();
                        String buildingType = buf.readUtf();
                        String villageName  = buf.readUtf();
                        String faithName    = buf.readUtf();
                        String deityName    = buf.readUtf();

                        long treasury      = buf.readVarLong();
                        long dailyCost     = buf.readVarLong();
                        long surplus       = buf.readLong();
                        int  daysInsolvent = buf.readVarInt();
                        String healthState = buf.readUtf();

                        String condition = buf.readUtf();
                        boolean decaying = buf.readBoolean();

                        boolean consecrated = buf.readBoolean();

                        int candleCount = buf.readVarInt();

                        boolean staffed   = buf.readBoolean();
                        String clergyName  = buf.readUtf();
                        String clergyOrder = buf.readUtf();
                        String clergyTitle = buf.readUtf();

                        int congregationCount = buf.readVarInt();
                        float aggregatePiety  = buf.readFloat();

                        int upCount = buf.readVarInt();
                        List<String> upcoming = new ArrayList<>(upCount);
                        for (int i = 0; i < upCount; i++) upcoming.add(buf.readUtf());

                        return new OpenTempleScreenPacket(
                                buildingName, buildingType, villageName, faithName, deityName,
                                treasury, dailyCost, surplus, daysInsolvent, healthState,
                                condition, decaying, consecrated, candleCount,
                                staffed, clergyName, clergyOrder, clergyTitle,
                                congregationCount, aggregatePiety, upcoming);
                    });

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(OpenTempleScreenPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                net.minecraft.client.Minecraft.getInstance()
                        .setScreen(new tterrag1112.life_in_the_village.Gui.TempleScreen(pkt)));
    }
}
