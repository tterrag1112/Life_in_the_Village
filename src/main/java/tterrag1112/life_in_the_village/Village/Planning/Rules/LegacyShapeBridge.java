package tterrag1112.life_in_the_village.Village.Planning.Rules;

import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts legacy {@link VillageTypeData.ShapeType} enum values into
 * equivalent rule stacks. Used by {@link VillageTypeData} when a village
 * type JSON uses the old {@code shape_profile.shape_type} field instead
 * of the new {@code shape_rules} array.
 *
 * <p>The translated rule stacks aim to reproduce the current planner's
 * behaviour for each shape as closely as possible. They're a migration
 * aid, not the final word — once a village type switches to writing its
 * own rule stack directly, the bridge is no longer consulted for that type.
 *
 * <p>Because this bridge only provides defaults, village type authors
 * who need more control can stop using the enum and write
 * {@code shape_rules} directly — the fields coexist.
 */
public final class LegacyShapeBridge {

    private LegacyShapeBridge() {}

    /**
     * Returns the default rule stack for a legacy shape type.
     * Callers can append additional rules to the returned list.
     */
    public static List<ShapeRule> ruleStackFor(VillageTypeData.ShapeType shape) {
        List<ShapeRule> rules = new ArrayList<>();

        // Every shape starts with the town square anchored on flat ground.
        rules.add(new BuiltinRules.AnchorRule(
                "town_square",
                BuiltinRules.AnchorRule.Strategy.FLAT_GROUND));

        switch (shape) {
            case RADIAL -> {
                // No axis; default zones from ZoneRegistry kick in.
                rules.add(new BuiltinRules.AxisRule(
                        BuiltinRules.AxisRule.Strategy.NONE,
                        0, Math.toRadians(180)));
            }
            case LINEAR -> {
                rules.add(new BuiltinRules.AxisRule(
                        BuiltinRules.AxisRule.Strategy.FLATTEST,
                        0, Math.toRadians(20)));
            }
            case CLUSTERED -> {
                // No axis, higher density
                rules.add(new BuiltinRules.DensityRule(0.9f));
            }
            case RIVERINE -> {
                // Anchor at the riverbank, axis follows the river.
                rules.clear(); // replace the flat-ground anchor
                rules.add(new BuiltinRules.AnchorRule(
                        "town_square",
                        BuiltinRules.AnchorRule.Strategy.RIVERBANK));
                rules.add(new BuiltinRules.AxisRule(
                        BuiltinRules.AxisRule.Strategy.ALONG_RIVER,
                        0, Math.toRadians(25)));
            }
            case HILLTOP -> {
                rules.clear();
                rules.add(new BuiltinRules.AnchorRule(
                        "town_square",
                        BuiltinRules.AnchorRule.Strategy.HIGHEST_POINT));
                rules.add(new BuiltinRules.WalledRule(true));
            }
            case PLAZA -> {
                // Capital-style — dense, walled, centred on flat ground.
                rules.add(new BuiltinRules.DensityRule(1.2f));
                rules.add(new BuiltinRules.WalledRule(true));
            }
            case COURTYARD -> {
                // Ring placement; nothing extra beyond the anchor.
            }
        }

        return rules;
    }
}