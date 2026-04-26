package tterrag1112.life_in_the_village.Npc.Letters;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Scribal.ScribalItems;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Spec line 267 — generators for procedurally-authored books pulled
 * from in-world game state.
 *
 * <p>Phase 2 ships a single generator: {@link #generateVillageLedger}
 * which compiles a "Village Ledger" from the village's recent
 * {@code VillageEvent}s. The hook is intentionally <strong>debug-
 * gated</strong> per spec: there is no auto-trigger; only
 * {@code /book ledger} or a Village_Scribe office holder (Phase 3
 * wiring) calls in.</p>
 *
 * <p>Kingdom history books are listed in the spec under "procedural"
 * but depend on Phase 4 {@code KingdomHistoryData}. Stub
 * documented; no Phase 2 generator.</p>
 */
public final class ProceduralBookFactory {

    /** Page-cap so a long-running village doesn't generate a 60-page tome. */
    public static final int MAX_PAGES = 6;

    private ProceduralBookFactory() {}

    /**
     * Compiles the village's recent events into a ledger book item.
     * Returns empty if the village UUID has no associated village.
     */
    public static Optional<ItemStack> generateVillageLedger(ServerLevel level,
                                                            UUID villageId,
                                                            String authorName,
                                                            UUID authorNpcId) {
        VillageSavedData data = VillageSavedData.get(level);
        Optional<Village> village = data.getVillageById(villageId);
        if (village.isEmpty()) return Optional.empty();
        long now = level.getGameTime();

        List<VillageEvent> events = new ArrayList<>(data.getAllEvents().stream()
                .filter(e -> e.getVillageId().equals(villageId))
                .toList());
        events.sort((a, b) -> Long.compare(a.getStartTick(), b.getStartTick()));

        StringBuilder body = new StringBuilder();
        body.append("Village Ledger of ").append(village.get().getName()).append('\n');
        body.append("Compiled by ").append(authorName == null ? "the village scribe" : authorName)
                .append(" at tick ").append(now).append("\n\n");
        if (events.isEmpty()) {
            body.append("Nothing of note has been recorded this season.");
        } else {
            int written = 0;
            for (VillageEvent e : events) {
                long days = Math.max(0L, (now - e.getStartTick()) / 24000L);
                body.append(String.format(Locale.ROOT, "Day -%d: %s%n",
                        days, summarize(e)));
                written++;
                if (written >= MAX_PAGES * 4) break; // ~4 events per page
            }
        }

        List<String> topics = new ArrayList<>();
        topics.add("village.history." + village.get().getName().toLowerCase(Locale.ROOT));
        for (VillageEvent e : events) {
            topics.add("event." + e.getType().name().toLowerCase(Locale.ROOT));
            if (topics.size() >= 6) break;
        }

        ItemStack book = ScribalItems.book(
                "Ledger of " + village.get().getName(),
                authorName == null ? "Anonymous Scribe" : authorName,
                body.toString(),
                BookCategory.LEDGER,
                topics,
                authorNpcId == null ? Optional.empty() : Optional.of(authorNpcId),
                Optional.empty(),
                now);
        return Optional.of(book);
    }

    private static String summarize(VillageEvent e) {
        return e.getType().name().replace('_', ' ').toLowerCase(Locale.ROOT)
                + " (status " + e.getStatus().name().toLowerCase(Locale.ROOT) + ")";
    }
}
