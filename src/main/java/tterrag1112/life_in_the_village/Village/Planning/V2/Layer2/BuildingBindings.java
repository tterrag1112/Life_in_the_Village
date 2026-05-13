package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Track E1B — declarative per-building anchor-type preference
 * list.
 *
 * <p>A strategy maps each relevant {@code BuildingType} to an
 * ordered list of {@code AnchorType}s. Example:
 *
 * <pre>{@code
 * Map.of(
 *     BuildingType.TOWN_HALL, List.of(AnchorType.FLAT_FERTILE, AnchorType.DEFENSIBLE_PEAK),
 *     BuildingType.MINE,      List.of(AnchorType.CLIFF_FACE),
 *     BuildingType.WOODCUTTER,List.of(AnchorType.FOREST_EDGE)
 * )
 * }</pre>
 *
 * <p>Per the locked design: this is a {@b preference list}, not
 * an assignment. Specific anchor-id resolution happens later
 * (prompt 4 building selector / placement). The dump serialises
 * the preference list as-is so the visualiser can render which
 * anchor types each building wants without needing a resolved
 * binding.
 */
public record BuildingBindings(
        Map<BuildingType, List<AnchorType>> preferences) {

    public BuildingBindings {
        if (preferences == null) {
            preferences = Map.of();
        } else {
            // Preserve registration order for stable serialisation.
            Map<BuildingType, List<AnchorType>> copy = new LinkedHashMap<>();
            for (var e : preferences.entrySet()) {
                copy.put(e.getKey(),
                        e.getValue() == null ? List.of() : List.copyOf(e.getValue()));
            }
            preferences = Collections.unmodifiableMap(copy);
        }
    }

    /** Convenience: no per-building preferences declared. */
    public static BuildingBindings none() {
        return new BuildingBindings(Map.of());
    }
}
