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
 * Server→client. Opens
 * {@link tterrag1112.life_in_the_village.Gui.PlayerReligionScreen} for the
 * requesting player. Religion Rework R9c.
 *
 * <p>Carries a fully server-computed, read-only snapshot of the player's own
 * religious standing (faith / piety / tithe pledge / observance) plus the
 * upcoming religious calendar across faiths (computed on the {@code % 365}
 * liturgical axis). Mirrors the {@code OpenBusinessFrontPacket} /
 * {@code OpenTempleScreenPacket} pattern — the Open packet IS the data; no
 * separate sync this phase. Empty strings / false / 0 / empty lists are the
 * graceful unaffiliated state (no crash).</p>
 */
public record OpenPlayerReligionPacket(
        // Your faith
        String religionName,        // "" = unaffiliated
        String deityName,           // "" = none
        float  pietyStrength,
        String pietyTier,
        List<String> beliefSummary, // syncretic lines ("Faith — 30%"); empty for single-faith

        // Tithe pledge (R4d-1)
        boolean hasPledge,
        String  pledgeTempleName,   // "" = none
        String  pledgeFaithName,    // "" = none

        // Observance
        int     ritesThisMonth,
        boolean meetsMonthlyAttendance,

        // Religious calendar
        int     today,              // current day-of-year (0..364)
        List<CalendarRow> calendar,

        // Divine Layer V1 — favour per deity ("Sunstead 42"); empty = none
        List<String> favourSummary
) implements CustomPacketPayload {

    /** One upcoming calendar event for the list. {@code ownFaith} = the player's
     *  primary religion; {@code daysAway == 0} = today. */
    public record CalendarRow(String faithDisplay, String dayLabel,
                              int dayOfYear, int daysAway, boolean ownFaith) {}

    public OpenPlayerReligionPacket {
        beliefSummary = List.copyOf(beliefSummary);
        calendar      = List.copyOf(calendar);
        favourSummary = List.copyOf(favourSummary);
    }

    public static final Type<OpenPlayerReligionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "open_player_religion"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenPlayerReligionPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeUtf(pkt.religionName());
                        buf.writeUtf(pkt.deityName());
                        buf.writeFloat(pkt.pietyStrength());
                        buf.writeUtf(pkt.pietyTier());
                        buf.writeVarInt(pkt.beliefSummary().size());
                        for (String s : pkt.beliefSummary()) buf.writeUtf(s);

                        buf.writeBoolean(pkt.hasPledge());
                        buf.writeUtf(pkt.pledgeTempleName());
                        buf.writeUtf(pkt.pledgeFaithName());

                        buf.writeVarInt(pkt.ritesThisMonth());
                        buf.writeBoolean(pkt.meetsMonthlyAttendance());

                        buf.writeVarInt(pkt.today());
                        buf.writeVarInt(pkt.calendar().size());
                        for (CalendarRow r : pkt.calendar()) {
                            buf.writeUtf(r.faithDisplay());
                            buf.writeUtf(r.dayLabel());
                            buf.writeVarInt(r.dayOfYear());
                            buf.writeVarInt(r.daysAway());
                            buf.writeBoolean(r.ownFaith());
                        }

                        buf.writeVarInt(pkt.favourSummary().size());
                        for (String s : pkt.favourSummary()) buf.writeUtf(s);
                    },
                    buf -> {
                        String religionName = buf.readUtf();
                        String deityName    = buf.readUtf();
                        float  pietyStrength = buf.readFloat();
                        String pietyTier    = buf.readUtf();
                        int    beliefCount  = buf.readVarInt();
                        List<String> beliefSummary = new ArrayList<>(beliefCount);
                        for (int i = 0; i < beliefCount; i++) beliefSummary.add(buf.readUtf());

                        boolean hasPledge   = buf.readBoolean();
                        String pledgeTemple = buf.readUtf();
                        String pledgeFaith  = buf.readUtf();

                        int ritesThisMonth  = buf.readVarInt();
                        boolean meetsMonthly = buf.readBoolean();

                        int today = buf.readVarInt();
                        int calCount = buf.readVarInt();
                        List<CalendarRow> calendar = new ArrayList<>(calCount);
                        for (int i = 0; i < calCount; i++) {
                            String faith = buf.readUtf();
                            String day   = buf.readUtf();
                            int doy      = buf.readVarInt();
                            int away     = buf.readVarInt();
                            boolean own  = buf.readBoolean();
                            calendar.add(new CalendarRow(faith, day, doy, away, own));
                        }

                        int favCount = buf.readVarInt();
                        List<String> favourSummary = new ArrayList<>(favCount);
                        for (int i = 0; i < favCount; i++) favourSummary.add(buf.readUtf());

                        return new OpenPlayerReligionPacket(
                                religionName, deityName, pietyStrength, pietyTier, beliefSummary,
                                hasPledge, pledgeTemple, pledgeFaith,
                                ritesThisMonth, meetsMonthly,
                                today, calendar, favourSummary);
                    });

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(OpenPlayerReligionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                net.minecraft.client.Minecraft.getInstance()
                        .setScreen(new tterrag1112.life_in_the_village.Gui.PlayerReligionScreen(pkt)));
    }
}
