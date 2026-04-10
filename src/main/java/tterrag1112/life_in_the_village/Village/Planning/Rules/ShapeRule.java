package tterrag1112.life_in_the_village.Village.Planning.Rules;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

/**
 * A single rule in a village shape profile's rule stack.
 *
 * <h3>Contract</h3>
 * Each rule reads and/or writes {@link RuleContext}. Rules are stateless
 * beyond their own configuration (parsed from JSON once at load time) —
 * the mutable state lives entirely in the context.
 *
 * <h3>Ordering</h3>
 * Rules are applied in the order they appear in the JSON array. The
 * expected dependency order is: anchor rules → axis rules → zone rules
 * → adjacency rules → density rules. A village type may omit any
 * category; default behaviour kicks in for anything the rules don't set.
 *
 * <h3>Implementing a new rule</h3>
 * <ol>
 *   <li>Create a class that implements {@code ShapeRule}.</li>
 *   <li>Accept its configuration in the constructor.</li>
 *   <li>Implement {@link #apply(RuleContext)}.</li>
 *   <li>Register a parser via {@link Registry#register} during mod setup.</li>
 * </ol>
 */
public interface ShapeRule {

    /** Called in stack order to mutate the planning context. */
    void apply(RuleContext ctx);

    /** A short name used in logs and error messages. */
    String typeName();

    // =========================================================================
    // Registry
    // =========================================================================

    /**
     * Maps JSON {@code "type"} strings to parser functions that build a
     * concrete {@code ShapeRule} from the remaining JSON fields.
     *
     * <p>Registry is populated in {@code ShapeRuleRegistration.registerBuiltins}
     * during mod setup. Addons can register custom rule types at any
     * point before village types are loaded.
     */
    final class Registry {

        private static final Logger LOGGER = LoggerFactory.getLogger(Registry.class);

        private static final Map<String, Function<JsonObject, ShapeRule>> PARSERS =
                new HashMap<>();

        private Registry() {}

        public static void register(String typeName,
                                    Function<JsonObject, ShapeRule> parser) {
            PARSERS.put(typeName.toLowerCase(Locale.ROOT), parser);
            LOGGER.debug("Registered shape rule type: {}", typeName);
        }

        public static boolean isRegistered(String typeName) {
            return PARSERS.containsKey(typeName.toLowerCase(Locale.ROOT));
        }

        /**
         * Parses a single rule from its JSON object. Returns null if the
         * {@code type} field is missing or unknown; logs a warning in
         * either case.
         */
        public static ShapeRule parse(JsonObject json) {
            if (!json.has("type")) {
                LOGGER.warn("Shape rule JSON missing 'type' field: {}", json);
                return null;
            }
            String typeName = json.get("type").getAsString().toLowerCase(Locale.ROOT);
            Function<JsonObject, ShapeRule> parser = PARSERS.get(typeName);
            if (parser == null) {
                LOGGER.warn("Unknown shape rule type '{}' — ignoring", typeName);
                return null;
            }
            try {
                return parser.apply(json);
            } catch (Exception e) {
                LOGGER.warn("Failed to parse rule of type '{}': {}",
                        typeName, e.getMessage());
                return null;
            }
        }

        public static Set<String> registeredTypes() {
            return Collections.unmodifiableSet(PARSERS.keySet());
        }
    }
}