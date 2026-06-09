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
 * requesting player. Religion Rework R9c; F1a sub-stage 4b — the divine section
 * (favour / miracles / theophany) is now a <b>per-god</b> structure
 * ({@link GodStanding}) instead of the flat string summaries, so the player sees
 * each god's standing independently. The religion-level fields (faith / piety /
 * beliefs / tithe / observance / calendar / calling) are unchanged.
 */
public record OpenPlayerReligionPacket(
        // Your faith (religion-level)
        String religionName,        // "" = unaffiliated
        String deityName,           // primary god's name ("" = impersonal/none)
        float  pietyStrength,
        String pietyTier,
        List<String> beliefSummary, // syncretic lines ("Faith — 30%"); empty for single-faith

        // Tithe pledge (R4d-1)
        boolean hasPledge,
        String  pledgeTempleName,
        String  pledgeFaithName,

        // Observance
        int     ritesThisMonth,
        boolean meetsMonthlyAttendance,

        // Religious calendar
        int     today,
        List<CalendarRow> calendar,

        // Divine Layer V3 — the active divine calling (religion-level), or ""
        String activeCalling,

        // F1a 4b — per-god divine standing (favour / miracles / theophany)
        List<GodStanding> gods
) implements CustomPacketPayload {

    /** One upcoming calendar event for the list. */
    public record CalendarRow(String faithDisplay, String dayLabel,
                              int dayOfYear, int daysAway, boolean ownFaith) {}

    /**
     * F1a 4b — a player's standing with one god: the display name, signed favour +
     * its band ({@code NONE}/{@code OMEN}/{@code CURSE}/{@code WRATH} from
     * {@code DivineFavour.displeasureOf}), that god's miracles (each "Name glyph"),
     * and the god's theophany history (each "glory (Nd)" / "wrath (Nd)").
     */
    public record GodStanding(String name, int favour, String band,
                              List<String> miracles, List<String> theophanies) {
        public GodStanding {
            miracles    = List.copyOf(miracles);
            theophanies = List.copyOf(theophanies);
        }
    }

    public OpenPlayerReligionPacket {
        beliefSummary = List.copyOf(beliefSummary);
        calendar      = List.copyOf(calendar);
        gods          = List.copyOf(gods);
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

                        buf.writeUtf(pkt.activeCalling());

                        // Per-god standing (each with its inner miracle + theophany lists).
                        buf.writeVarInt(pkt.gods().size());
                        for (GodStanding g : pkt.gods()) {
                            buf.writeUtf(g.name());
                            buf.writeInt(g.favour());        // signed
                            buf.writeUtf(g.band());
                            buf.writeVarInt(g.miracles().size());
                            for (String s : g.miracles()) buf.writeUtf(s);
                            buf.writeVarInt(g.theophanies().size());
                            for (String s : g.theophanies()) buf.writeUtf(s);
                        }
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

                        String activeCalling = buf.readUtf();

                        int godCount = buf.readVarInt();
                        List<GodStanding> gods = new ArrayList<>(godCount);
                        for (int i = 0; i < godCount; i++) {
                            String name = buf.readUtf();
                            int favour  = buf.readInt();
                            String band = buf.readUtf();
                            int mCount  = buf.readVarInt();
                            List<String> miracles = new ArrayList<>(mCount);
                            for (int j = 0; j < mCount; j++) miracles.add(buf.readUtf());
                            int tCount  = buf.readVarInt();
                            List<String> theophanies = new ArrayList<>(tCount);
                            for (int j = 0; j < tCount; j++) theophanies.add(buf.readUtf());
                            gods.add(new GodStanding(name, favour, band, miracles, theophanies));
                        }

                        return new OpenPlayerReligionPacket(
                                religionName, deityName, pietyStrength, pietyTier, beliefSummary,
                                hasPledge, pledgeTemple, pledgeFaith,
                                ritesThisMonth, meetsMonthly,
                                today, calendar, activeCalling, gods);
                    });

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(OpenPlayerReligionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                net.minecraft.client.Minecraft.getInstance()
                        .setScreen(new tterrag1112.life_in_the_village.Gui.PlayerReligionScreen(pkt)));
    }
}
