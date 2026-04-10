package tterrag1112.life_in_the_village.Village.Planning.Rules;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.*;

/**
 * Built-in {@link ShapeRule} implementations.
 *
 * <h3>Rule catalogue</h3>
 * <ul>
 *   <li>{@link AnchorRule} — sets a named position in the context</li>
 *   <li>{@link AxisRule} — sets the primary orientation</li>
 *   <li>{@link ZoneRule} — assigns building types to rings/sectors</li>
 *   <li>{@link AdjacencyRule} — requires a feature within a distance</li>
 *   <li>{@link DensityRule} — sets the target building density</li>
 *   <li>{@link WalledRule} — forces the perimeter wall on or off</li>
 * </ul>
 *
 * Each rule has a static {@code register()} method that installs its
 * parser in the global registry. {@link ShapeRuleRegistration} calls
 * all of them during mod setup.
 */
public final class BuiltinRules {

    private BuiltinRules() {}

    // =========================================================================
    // AnchorRule — places a named position in the context
    // =========================================================================

    public static final class AnchorRule implements ShapeRule {

        /** What kind of position to look for. */
        public enum Strategy {
            /** Use the terrain's best-flat position (from TerrainProfile). */
            FLAT_GROUND,
            /** Use the highest point in the terrain sample. */
            HIGHEST_POINT,
            /** Use the cell on the nearest riverbank. */
            RIVERBANK,
            /** Use the cell on the nearest coastline. */
            COASTLINE,
            /** Use the raw origin passed to the planner. */
            ORIGIN
        }

        private final String target;
        private final Strategy strategy;

        public AnchorRule(String target, Strategy strategy) {
            this.target   = target;
            this.strategy = strategy;
        }

        @Override public String typeName() { return "anchor"; }

        @Override
        public void apply(RuleContext ctx) {
            BlockPos pos = switch (strategy) {
                case ORIGIN        -> ctx.origin();
                case FLAT_GROUND   -> ctx.terrain().bestFlatNear(0, 0);
                case HIGHEST_POINT -> new BlockPos(
                        ctx.origin().getX(),
                        ctx.terrain().maxY(),
                        ctx.origin().getZ());
                case RIVERBANK, COASTLINE -> ctx.terrain().waterBody() != null
                        ? ctx.terrain().waterBody().nearestShore()
                        : ctx.origin();
            };
            ctx.setAnchor(target, pos);
        }

        public static void register() {
            ShapeRule.Registry.register("anchor", json -> {
                String target = json.has("target")
                        ? json.get("target").getAsString()
                        : "town_square";
                Strategy strategy = Strategy.FLAT_GROUND;
                if (json.has("strategy")) {
                    strategy = Strategy.valueOf(
                            json.get("strategy").getAsString()
                                    .toUpperCase(Locale.ROOT));
                }
                return new AnchorRule(target, strategy);
            });
        }
    }

    // =========================================================================
    // AxisRule — sets the primary orientation of the village
    // =========================================================================

    public static final class AxisRule implements ShapeRule {

        public enum Strategy {
            /** Align with the flattest horizontal axis. */
            FLATTEST,
            /** Follow the direction of the nearest river. */
            ALONG_RIVER,
            /** Follow the line of the nearest coast. */
            ALONG_COAST,
            /** Fixed angle in degrees (from the JSON 'angle' field). */
            FIXED,
            /** No axis — rules downstream should treat this as RADIAL. */
            NONE
        }

        private final Strategy strategy;
        private final double fixedAngleRad;
        private final double toleranceRad;

        public AxisRule(Strategy strategy, double fixedAngleRad, double toleranceRad) {
            this.strategy      = strategy;
            this.fixedAngleRad = fixedAngleRad;
            this.toleranceRad  = toleranceRad;
        }

        @Override public String typeName() { return "axis"; }

        @Override
        public void apply(RuleContext ctx) {
            switch (strategy) {
                case FIXED -> ctx.setAxis(fixedAngleRad, toleranceRad);

                case FLATTEST -> {
                    var dir = ctx.terrain().bestFlatDir();
                    if (dir != null) {
                        ctx.setAxis(flatDirectionToRadians(dir), toleranceRad);
                    }
                }

                case ALONG_RIVER, ALONG_COAST -> {
                    var water = ctx.terrain().waterBody();
                    if (water != null) {
                        int dx = water.nearestShore().getX() - ctx.origin().getX();
                        int dz = water.nearestShore().getZ() - ctx.origin().getZ();
                        // Perpendicular to the origin→shore vector runs along the shore
                        double perp = Math.atan2(dx, -dz);
                        ctx.setAxis(perp, toleranceRad);
                    }
                }

                case NONE -> {} // no-op
            }
        }

        /**
         * Maps a {@link tterrag1112.life_in_the_village.Village.Planning.TerrainAnalyzer.FlatDirection}
         * to a radian angle on the standard math plane (counterclockwise
         * from positive X / east).
         */
        private static double flatDirectionToRadians(
                tterrag1112.life_in_the_village.Village.Planning.TerrainAnalyzer.FlatDirection dir) {
            return switch (dir) {
                case EAST  -> 0.0;
                case NORTH -> -Math.PI / 2;   // -Z in Minecraft is north
                case WEST  -> Math.PI;
                case SOUTH -> Math.PI / 2;    // +Z is south
            };
        }

        public static void register() {
            ShapeRule.Registry.register("axis", json -> {
                Strategy strategy = Strategy.FLATTEST;
                if (json.has("strategy")) {
                    strategy = Strategy.valueOf(
                            json.get("strategy").getAsString()
                                    .toUpperCase(Locale.ROOT));
                }
                double angleRad = 0.0;
                if (json.has("angle")) {
                    angleRad = Math.toRadians(json.get("angle").getAsDouble());
                }
                double tolRad = Math.toRadians(
                        json.has("max_deviation")
                                ? json.get("max_deviation").getAsDouble()
                                : 30.0);
                return new AxisRule(strategy, angleRad, tolRad);
            });
        }
    }

    // =========================================================================
    // ZoneRule — assigns building types to rings and optional sectors
    // =========================================================================

    public static final class ZoneRule implements ShapeRule {

        private final List<BuildingType> buildings;
        private final RuleContext.Ring ring;
        private final RuleContext.AngularSector sector; // nullable

        public ZoneRule(List<BuildingType> buildings,
                        RuleContext.Ring ring,
                        RuleContext.AngularSector sector) {
            this.buildings = buildings;
            this.ring      = ring;
            this.sector    = sector;
        }

        @Override public String typeName() { return "zone"; }

        @Override
        public void apply(RuleContext ctx) {
            RuleContext.ZoneSpec spec = new RuleContext.ZoneSpec(ring, sector);
            for (BuildingType bt : buildings) {
                ctx.assignZone(bt, spec);
            }
        }

        public static void register() {
            ShapeRule.Registry.register("zone", json -> {
                List<BuildingType> buildings = new ArrayList<>();
                if (json.has("buildings")) {
                    for (var el : json.getAsJsonArray("buildings")) {
                        try {
                            buildings.add(BuildingType.valueOf(
                                    el.getAsString().toUpperCase(Locale.ROOT)));
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
                RuleContext.Ring ring = RuleContext.Ring.MIDDLE;
                if (json.has("ring")) {
                    ring = RuleContext.Ring.valueOf(
                            json.get("ring").getAsString()
                                    .toUpperCase(Locale.ROOT));
                }
                RuleContext.AngularSector sector = null;
                if (json.has("angular_sector")) {
                    var arr = json.getAsJsonArray("angular_sector");
                    if (arr.size() == 2) {
                        sector = new RuleContext.AngularSector(
                                arr.get(0).getAsInt(),
                                arr.get(1).getAsInt());
                    }
                }
                return new ZoneRule(buildings, ring, sector);
            });
        }
    }

    // =========================================================================
    // AdjacencyRule — hard constraint on where a building may be placed
    // =========================================================================

    public static final class AdjacencyRule implements ShapeRule {

        private final List<BuildingType> buildings;
        private final String feature;
        private final int maxDist;
        private final boolean required;

        public AdjacencyRule(List<BuildingType> buildings, String feature,
                             int maxDist, boolean required) {
            this.buildings = buildings;
            this.feature   = feature;
            this.maxDist   = maxDist;
            this.required  = required;
        }

        @Override public String typeName() { return "adjacency"; }

        @Override
        public void apply(RuleContext ctx) {
            RuleContext.AdjacencyReq req =
                    new RuleContext.AdjacencyReq(feature, maxDist, required);
            for (BuildingType bt : buildings) {
                ctx.addAdjacencyReq(bt, req);
            }
        }

        public static void register() {
            ShapeRule.Registry.register("adjacency", json -> {
                List<BuildingType> buildings = new ArrayList<>();
                if (json.has("buildings")) {
                    for (var el : json.getAsJsonArray("buildings")) {
                        try {
                            buildings.add(BuildingType.valueOf(
                                    el.getAsString().toUpperCase(Locale.ROOT)));
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
                String feature = json.has("requires")
                        ? json.get("requires").getAsString().toLowerCase(Locale.ROOT)
                        : "water";
                int maxDist = json.has("max_distance")
                        ? json.get("max_distance").getAsInt() : 16;
                boolean required = !json.has("optional")
                        || !json.get("optional").getAsBoolean();
                return new AdjacencyRule(buildings, feature, maxDist, required);
            });
        }
    }

    // =========================================================================
    // DensityRule — target building density for the layout
    // =========================================================================

    public static final class DensityRule implements ShapeRule {

        private final float per100m2;

        public DensityRule(float per100m2) { this.per100m2 = per100m2; }

        @Override public String typeName() { return "density"; }

        @Override
        public void apply(RuleContext ctx) {
            ctx.setDensityPer100m2(per100m2);
        }

        public static void register() {
            ShapeRule.Registry.register("density", json -> {
                float v = json.has("buildings_per_100m2")
                        ? json.get("buildings_per_100m2").getAsFloat() : 0.5f;
                return new DensityRule(v);
            });
        }
    }

    // =========================================================================
    // WalledRule — force the perimeter wall state
    // =========================================================================

    public static final class WalledRule implements ShapeRule {

        private final boolean walled;
        public WalledRule(boolean walled) { this.walled = walled; }

        @Override public String typeName() { return "walled"; }

        @Override
        public void apply(RuleContext ctx) { ctx.setForceWalled(walled); }

        public static void register() {
            ShapeRule.Registry.register("walled", json -> {
                boolean walled = !json.has("value")
                        || json.get("value").getAsBoolean();
                return new WalledRule(walled);
            });
        }
    }
}