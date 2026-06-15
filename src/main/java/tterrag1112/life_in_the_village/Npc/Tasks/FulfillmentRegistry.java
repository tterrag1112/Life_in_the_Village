package tterrag1112.life_in_the_village.Npc.Tasks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Maps an {@link Objective.Type} to the ordered {@link Fulfillment}
 * strategies that can satisfy it. The dispatcher asks for the strategies
 * of a task's objective, then scores them.
 *
 * <p>EMPTY in T0 — populated in T1 as objective variants gain their
 * fulfillment strategies. Keyed by {@link Objective.Type} (the same tag
 * the persistence codec dispatches on) so registration never needs an
 * exhaustive switch.</p>
 */
public final class FulfillmentRegistry {

    private final Map<Objective.Type, List<Fulfillment>> byType = new EnumMap<>(Objective.Type.class);

    /** Append {@code fulfillment} to the strategy list for {@code type}. */
    public void register(Objective.Type type, Fulfillment fulfillment) {
        byType.computeIfAbsent(type, k -> new ArrayList<>()).add(fulfillment);
    }

    /** Strategies registered for {@code objective}'s variant, in registration order. */
    public List<Fulfillment> strategiesFor(Objective objective) {
        List<Fulfillment> list = byType.get(objective.type());
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    public boolean isEmpty() {
        return byType.isEmpty();
    }
}
