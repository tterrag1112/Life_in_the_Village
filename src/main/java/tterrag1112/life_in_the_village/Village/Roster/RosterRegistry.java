package tterrag1112.life_in_the_village.Village.Roster;

import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 6.3.3.d — static-init registry of {@link RosterDefinition}s.
 *
 * <p>Empty in 6.3.3.d. The animal-husbandry content pass (6.3.3.g)
 * registers entries via {@link #register} at mod init:
 * <pre>
 *   RosterRegistry.register(new RosterDefinition(
 *       Identifier.fromNamespaceAndPath("life_in_the_village", "roster/chicken"),
 *       EntityType.CHICKEN, 4, 12, 24000L,
 *       List.of(new ItemStack(Items.EGG, 1)),
 *       Optional.of(new BreedingRule(
 *           List.of(new ItemStack(Items.WHEAT_SEEDS, 1)), 2, 6000L)),
 *       List.of(new ItemStack(Items.CHICKEN, 1)),
 *       12000L));
 * </pre>
 */
public final class RosterRegistry {

    private RosterRegistry() {}

    private static final Map<Identifier, RosterDefinition> DEFINITIONS = new LinkedHashMap<>();

    public static RosterDefinition register(RosterDefinition def) {
        if (def == null) return null;
        DEFINITIONS.put(def.id(), def);
        return def;
    }

    public static Optional<RosterDefinition> get(Identifier id) {
        return Optional.ofNullable(DEFINITIONS.get(id));
    }

    public static Map<Identifier, RosterDefinition> all() {
        return Collections.unmodifiableMap(DEFINITIONS);
    }

    public static void clearAll() { DEFINITIONS.clear(); }
}
