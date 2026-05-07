package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * V2 Layer 3 reconciliation. Runs after {@link BuildingSelector}'s
 * inclination-driven selection and before
 * {@link DependencyResolver}'s topological sort.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Build a per-{@link Category} {@link Provides} pool from the
 *       initial selection.</li>
 *   <li>For each {@link Requires} entry, deduct {@code amount} from
 *       the pool. Tradeable entries fall back to trade when the
 *       tier supports it (gated by {@link TradeAvailability}).</li>
 *   <li>If unsatisfied (no pool, no trade) — drop the requiring
 *       building. Cascade: dropping a building releases its
 *       provides, so the loop runs again.</li>
 *   <li>Stops when stable. V1 only drops; the spec calls for adding
 *       providers when possible, but the V1 inclination profile is
 *       static and adding new types isn't supported yet — we log
 *       what would have been added when the dropped path is taken.</li>
 * </ol>
 *
 * <p>Output is the post-reconciliation selection list (preserving
 * multiplicity), the drop audit, the surplus / unsatisfied maps
 * for diagnostics, and the trade fulfilment list.
 */
public final class ReconciliationEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReconciliationEngine.class);
    /** Hard cap on cascade iterations — defends against any
     *  pathological provides/requires loop introduced by future
     *  authoring mistakes. */
    private static final int MAX_ITERATIONS = 32;
    /** Level used by availability lookups. V1 is level 1. */
    private static final int LEVEL = 1;

    private ReconciliationEngine() {}

    public static ReconciliationResult reconcile(List<BuildingType> initialSelection,
                                                 ViabilityTier tier,
                                                 String culture,
                                                 BuildingAvailability availability) {
        // Mutable state through the cascade.
        List<BuildingType> selection = new ArrayList<>(initialSelection);
        List<DropDetail> drops = new ArrayList<>();
        List<TradeFulfillment> trades = new ArrayList<>();

        LOGGER.info("reconciliation start: tier={} initial={}", tier, countByType(selection));

        boolean stable = false;
        int iter = 0;
        while (!stable && iter < MAX_ITERATIONS) {
            iter++;
            EnumMap<Category, Integer> provides = computeProvides(selection);
            LOGGER.info("reconciliation iter {} provides pool: {}", iter, provides);

            // Walk requires in selection order; first-come first-served
            // on the pool. Iteration order is selection order so the
            // cycle-2 topo-tie shuffle propagates here.
            BuildingType firstUnsatisfied = null;
            List<Category> firstBlocking = List.of();
            String firstReason = null;
            EnumMap<Category, Integer> remaining = new EnumMap<>(provides);

            for (BuildingType type : selection) {
                PlacementProfile pp = PlacementDefaults.get(type);
                if (pp == null) continue;
                List<Requires> reqs = pp.requires();
                if (reqs.isEmpty()) continue;
                List<Category> blocking = new ArrayList<>();
                List<TradeFulfillment> typeTrades = new ArrayList<>();
                for (Requires r : reqs) {
                    if (r.amount() == 0) continue;
                    int have = remaining.getOrDefault(r.category(), 0);
                    if (have >= r.amount()) {
                        remaining.put(r.category(), have - r.amount());
                        continue;
                    }
                    // Local pool exhausted — try trade.
                    if (r.tradeable() && TradeAvailability.canTradeSatisfy(
                            tier, r.category())) {
                        typeTrades.add(new TradeFulfillment(type, r.category(), r.amount()));
                        continue;
                    }
                    blocking.add(r.category());
                }
                if (!blocking.isEmpty()) {
                    firstUnsatisfied = type;
                    firstBlocking = blocking;
                    firstReason = "missing " + blocking + " (no local provides, "
                            + (anyTradeable(reqs, blocking)
                                    ? "trade unavailable for tier " + tier
                                    : "categories not tradeable")
                            + ")";
                    break;
                }
                trades.addAll(typeTrades);
            }

            if (firstUnsatisfied == null) {
                // Stable. Capture surplus / trades for the report.
                EnumMap<Category, Integer> unsatisfied = new EnumMap<>(Category.class);
                ReconciliationResult result = new ReconciliationResult(
                        List.copyOf(selection), List.copyOf(drops),
                        Map.copyOf(remaining), Map.copyOf(unsatisfied),
                        List.copyOf(trades));
                LOGGER.info("reconciliation result: VIABLE iters={} drops={} trades={}",
                        iter, drops.size(), trades.size());
                LOGGER.info("reconciliation surplus: {}", remaining);
                if (!trades.isEmpty()) LOGGER.info("reconciliation trade-fulfilled: {}", trades);
                return result;
            }

            PlacementProfile failingProfile = PlacementDefaults.get(firstUnsatisfied);
            boolean isRequired = failingProfile != null && failingProfile.required();
            if (isRequired) {
                // Required types (TOWN_HALL etc.) must not be dropped
                // here — Cycle 1's ViabilityValidator catches the
                // post-spawn shortage. Leave the selection as-is, log
                // loudly, and break the cascade.
                LOGGER.warn("reconciliation: required type {} unsatisfied ({})."
                        + " Leaving in selection; ViabilityValidator may abort spawn.",
                        firstUnsatisfied, firstReason);
                EnumMap<Category, Integer> unsatisfied = new EnumMap<>(Category.class);
                for (Category c : firstBlocking) unsatisfied.merge(c, 1, Integer::sum);
                return new ReconciliationResult(List.copyOf(selection),
                        List.copyOf(drops), Map.copyOf(remaining),
                        Map.copyOf(unsatisfied), List.copyOf(trades));
            }
            // Drop one instance of the unsatisfied type — this releases
            // its provides for the next iteration. Adding new providers
            // (the spec's preferred path) requires authoring a "preferred
            // provider per category" map and gating on the inclination
            // profile + availability. V1 chooses the simpler "drop and
            // see" strategy, logged loudly so we know when it triggers.
            int idx = lastIndexOf(selection, firstUnsatisfied);
            // Cycle-2 topo-tie shuffle gives interleaved selection;
            // dropping the LAST instance of the failing type avoids
            // re-trying with a different instance of the same type.
            selection.remove(idx);
            drops.add(new DropDetail(firstUnsatisfied, firstReason,
                    List.copyOf(firstBlocking)));
            LOGGER.info("reconciliation cascade iter {}: drop {} ({})",
                    iter, firstUnsatisfied, firstReason);

            // Note: we could inspect availability here to decide whether
            // a fresh ADD of a provider type is possible. Left for a
            // follow-up cycle once the inclination profile carries
            // "permitted but not in initial roster" hints.
            if (availability != null && !availability.isAvailable(culture, Style.RURAL,
                    firstUnsatisfied, LEVEL)) {
                LOGGER.info("  (availability would also fail for {}: no NBT)",
                        firstUnsatisfied);
            }
        }

        // Hit iteration cap — log loudly. Should be unreachable for
        // sane provides/requires authoring.
        if (iter >= MAX_ITERATIONS) {
            LOGGER.warn("reconciliation: hit MAX_ITERATIONS={} cap. Selection state may"
                    + " be partial. drops={}", MAX_ITERATIONS, drops);
        }
        EnumMap<Category, Integer> unsatisfied = new EnumMap<>(Category.class);
        return new ReconciliationResult(List.copyOf(selection), List.copyOf(drops),
                Map.of(), Map.copyOf(unsatisfied), List.copyOf(trades));
    }

    private static EnumMap<Category, Integer> computeProvides(List<BuildingType> selection) {
        EnumMap<Category, Integer> pool = new EnumMap<>(Category.class);
        for (BuildingType t : selection) {
            PlacementProfile pp = PlacementDefaults.get(t);
            if (pp == null) continue;
            for (Provides p : pp.provides()) {
                if (p.capacity() == 0) continue;
                pool.merge(p.category(), p.capacity(), Integer::sum);
            }
        }
        return pool;
    }

    private static EnumMap<BuildingType, Integer> countByType(List<BuildingType> sel) {
        EnumMap<BuildingType, Integer> m = new EnumMap<>(BuildingType.class);
        for (BuildingType t : sel) m.merge(t, 1, Integer::sum);
        return m;
    }

    private static boolean anyTradeable(List<Requires> reqs, List<Category> blocking) {
        for (Requires r : reqs) if (r.tradeable() && blocking.contains(r.category())) return true;
        return false;
    }

    private static int lastIndexOf(List<BuildingType> list, BuildingType type) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) == type) return i;
        }
        return -1;
    }

    public record ReconciliationResult(
            List<BuildingType> finalSelection,
            List<DropDetail> drops,
            Map<Category, Integer> providesSurplus,
            Map<Category, Integer> requiresUnsatisfied,
            List<TradeFulfillment> tradeFulfilled) {}

    public record DropDetail(BuildingType type, String reason,
                             List<Category> blockingCategories) {}

    public record TradeFulfillment(BuildingType requiringType,
                                   Category category, int amount) {}
}
