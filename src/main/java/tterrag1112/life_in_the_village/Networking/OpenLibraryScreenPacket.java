package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.List;
import java.util.UUID;

/**
 * Server → client: open the LibraryScreen with the catalogue snapshot.
 */
public record OpenLibraryScreenPacket(UUID librarianId,
                                      String librarianName,
                                      UUID libraryBuildingId,
                                      List<Entry> entries)
        implements CustomPacketPayload {

    public record Entry(UUID bookId, String title, String author,
                        int copies, boolean available) {}

    public static final Type<OpenLibraryScreenPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "open_library_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenLibraryScreenPacket> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeUUID(p.librarianId);
                        buf.writeUtf(p.librarianName);
                        buf.writeUUID(p.libraryBuildingId);
                        buf.writeVarInt(p.entries.size());
                        for (Entry e : p.entries) {
                            buf.writeUUID(e.bookId);
                            buf.writeUtf(e.title);
                            buf.writeUtf(e.author);
                            buf.writeVarInt(e.copies);
                            buf.writeBoolean(e.available);
                        }
                    },
                    buf -> {
                        UUID lid = buf.readUUID();
                        String ln = buf.readUtf();
                        UUID bid = buf.readUUID();
                        int n = buf.readVarInt();
                        java.util.ArrayList<Entry> list = new java.util.ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            list.add(new Entry(buf.readUUID(), buf.readUtf(),
                                    buf.readUtf(), buf.readVarInt(), buf.readBoolean()));
                        }
                        return new OpenLibraryScreenPacket(lid, ln, bid, list);
                    });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenLibraryScreenPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> tterrag1112.life_in_the_village.Gui.LibraryScreen
                .openOnClient(pkt));
    }
}
