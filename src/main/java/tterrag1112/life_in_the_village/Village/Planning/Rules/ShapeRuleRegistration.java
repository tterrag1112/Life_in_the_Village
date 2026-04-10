package tterrag1112.life_in_the_village.Village.Planning.Rules;

/**
 * Registers all built-in {@link ShapeRule} parsers with the
 * {@link ShapeRule.Registry}. Called once at mod setup.
 *
 * <p>Addons that want to register custom rule types should do so in
 * their own setup event — the registry is open after this class runs.
 */
public final class ShapeRuleRegistration {

    private ShapeRuleRegistration() {}

    private static boolean registered = false;

    public static void registerBuiltins() {
        if (registered) return;
        registered = true;

        BuiltinRules.AnchorRule.register();
        BuiltinRules.AxisRule.register();
        BuiltinRules.ZoneRule.register();
        BuiltinRules.AdjacencyRule.register();
        BuiltinRules.DensityRule.register();
        BuiltinRules.WalledRule.register();
    }
}