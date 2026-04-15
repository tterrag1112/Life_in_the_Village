package tterrag1112.life_in_the_village.Village.Economy.Trade;

import java.util.*;

/**
 * Client-side cache of map-scoped trade connection snapshots.
 *
 * <p>Map packets ship {@link MapRoadSnapshot} / {@link MapSeaRouteSnapshot}
 * — cell paths only, no block lists or server-side state — to stay
 * off NBT serialisation and its 2 MB per-field cap. The client map
 * renderers only ever need cell-path data anyway.
 */
public final class ClientTradeConnectionCache {
    private ClientTradeConnectionCache() {}

    private static final Map<UUID, MapRoadSnapshot>     roads     = new HashMap<>();
    private static final Map<UUID, MapSeaRouteSnapshot> seaRoutes = new HashMap<>();

    public static void setRoads(Collection<MapRoadSnapshot> incoming) {
        roads.clear();
        for (MapRoadSnapshot r : incoming) roads.put(r.roadId(), r);
    }

    public static Optional<MapRoadSnapshot> getRoad(UUID id) {
        return Optional.ofNullable(roads.get(id));
    }

    public static Collection<MapRoadSnapshot> allRoads() {
        return Collections.unmodifiableCollection(roads.values());
    }

    public static void setSeaRoutes(Collection<MapSeaRouteSnapshot> incoming) {
        seaRoutes.clear();
        for (MapSeaRouteSnapshot s : incoming) seaRoutes.put(s.connectionId(), s);
    }

    public static Optional<MapSeaRouteSnapshot> getSeaRoute(UUID id) {
        return Optional.ofNullable(seaRoutes.get(id));
    }

    public static Collection<MapSeaRouteSnapshot> allSeaRoutes() {
        return Collections.unmodifiableCollection(seaRoutes.values());
    }

    public static void clear() { roads.clear(); seaRoutes.clear(); }
}