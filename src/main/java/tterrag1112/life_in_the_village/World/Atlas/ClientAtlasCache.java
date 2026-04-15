package tterrag1112.life_in_the_village.World.Atlas;

import java.util.*;

/**
 * Client-side cache of {@link AtlasCell}s synced from the server.
 *
 * <p>Populated by a server→client packet when a GUI that needs atlas
 * data is opened (e.g. the Kingdom Map). Only cells within the
 * requested viewport are synced — the full server-side atlas is too
 * large to send wholesale.
 *
 * <p>Follows the same static-cache pattern as
 * {@code Kingdom.ClientKingdomCache} and {@code TradeRoute.ClientRouteCache}.
 */
public final class ClientAtlasCache {
    private ClientAtlasCache() {}

    private static final Map<Long, AtlasCell> cells = new HashMap<>();

    public static void setCells(Collection<AtlasCell> incoming) {
        cells.clear();
        for (AtlasCell c : incoming) cells.put(c.key(), c);
    }

    public static void addCells(Collection<AtlasCell> incoming) {
        for (AtlasCell c : incoming) cells.put(c.key(), c);
    }

    public static void clear() { cells.clear(); }

    public static Optional<AtlasCell> get(int cellX, int cellZ) {
        return Optional.ofNullable(cells.get(AtlasCell.packKey(cellX, cellZ)));
    }

    public static Optional<AtlasCell> get(long key) {
        return Optional.ofNullable(cells.get(key));
    }

    public static Collection<AtlasCell> all() {
        return Collections.unmodifiableCollection(cells.values());
    }

    public static int size() { return cells.size(); }
}