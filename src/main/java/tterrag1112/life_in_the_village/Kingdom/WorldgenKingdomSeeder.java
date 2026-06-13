// src/main/java/tterrag1112/life_in_the_village/Kingdom/WorldGenKingdomSeeder.java
package tterrag1112.life_in_the_village.Kingdom;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.AtlasSiteSelector;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;

@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class WorldgenKingdomSeeder {

    private static final int MIN_KINGDOM_DIST  = 2_000;
    private static final int MAX_KINGDOM_DIST  = 4_000;
    private static final int MIN_KINGDOMS      = 2;
    private static final int MAX_KINGDOMS      = 2;
    private static final int SPAWN_INTERVAL    = 600;

    /** Radius (blocks) used for the regional viability scan. */
    private static final int VIABILITY_CHECK_RADIUS = 1000;
    /**
     * Minimum number of buildable, non-steep, non-ocean cells that must exist
     * within {@link #VIABILITY_CHECK_RADIUS} blocks of a candidate kingdom origin.
     * A region that can't meet this floor (e.g. a tiny island or cliff face) can't
     * host the kingdom's village portfolio.
     */
    private static final int MIN_VIABLE_CELLS = 80;
    /** Maximum fraction of ocean cells in the viability region before rejecting. */
    private static final float MAX_OCEAN_FRACTION = 0.30f;

    // {cultureName, capitalVillageType}
    private static final String[][] CULTURES = {
            { "default",  "royal_capital"    },
            { "highland", "highland_capital" },
            { "imperial", "imperial_capital" },
    };

    private static boolean kingdomsPlanned  = false;
    private static boolean waitingLogged    = false;
    private static long    seederStartMs    = 0;
    private static int     kingdomsPlaced   = 0;
    private static final List<ScheduledKingdom> scheduled = new ArrayList<>();
    private static int scheduledDelay = 0;
    private static int deferralCount  = 0;

    /** Returns a human-readable status string for debug commands. */
    public static String getStatusString() {
        if (!kingdomsPlanned && waitingLogged) return "WAITING_FOR_ROADS";
        if (!kingdomsPlanned) return "NOT_STARTED";
        if (!scheduled.isEmpty()) return "IN_PROGRESS (" + kingdomsPlaced
                + " placed, " + scheduled.size() + " queued)";
        return "COMPLETE (" + kingdomsPlaced + " kingdoms placed)";
    }

    public static int getKingdomsPlaced() { return kingdomsPlaced; }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel overworld = event.getServer().overworld();
        long tick = overworld.getGameTime();

        if (!kingdomsPlanned) {
            VillageSavedData data = VillageSavedData.get(overworld);
            if (data.getAllKingdoms().isEmpty()
                    && !VillageTypeRegistry.INSTANCE.getAvailableTypes().isEmpty()) {
                if (!WorldRoadSavedData.get(overworld).isGreatRoadGenerationComplete()) {
                    if (!waitingLogged) {
                        waitingLogged = true;
                        System.out.println("[Worldgen] Waiting for great-road generation to complete...");
                    }
                } else {
                    if (waitingLogged) {
                        System.out.println("[Worldgen] Great-road generation complete. Beginning kingdom seeding.");
                    }
                    kingdomsPlanned = true;
                    planKingdoms(overworld, tick);
                }
            } else {
                kingdomsPlanned = true; // kingdoms already exist or types not ready
            }
        }

        if (scheduled.isEmpty()) return;
        scheduledDelay--;
        if (scheduledDelay > 0) return;

        if (event.getServer().getAverageTickTimeNanos() > 100_000_000L) {
            scheduledDelay = 100;
            return;
        }

        ScheduledKingdom next = scheduled.remove(0);
        boolean completed = processSpawn(overworld, next);
        // Only advance to the next kingdom after this one is actually placed.
        // If processSpawn returned false it re-queued itself at the front for a
        // quick retry — don't overwrite its short delay with the long interval.
        scheduledDelay = completed ? SPAWN_INTERVAL : 5;
    }

    /** Returns atlas fill budget: 500ms during worldgen (no players / roads pending), 50ms otherwise. */
    private static long worldgenBudgetNanos(ServerLevel level) {
        boolean roadsComplete = WorldRoadSavedData.get(level).isGreatRoadGenerationComplete();
        if (!roadsComplete || level.players().isEmpty()) return 500_000_000L;
        return 50_000_000L;
    }

    private static void planKingdoms(ServerLevel level, long tick) {
        seederStartMs = System.currentTimeMillis();
        kingdomsPlaced = 0;
        Random rng = new Random(level.getSeed() * 6364136223846793005L + 1442695040888963407L);
        int count = MIN_KINGDOMS + rng.nextInt(MAX_KINGDOMS - MIN_KINGDOMS + 1);
        System.out.println("[WorldGenKingdomSeeder] Planning " + count
                + " kingdoms for seed " + level.getSeed());

        for (int i = 0; i < count; i++) {
            double angle = (2 * Math.PI * i / count) + (rng.nextDouble() - 0.5) * 0.4;
            int dist     = MIN_KINGDOM_DIST + rng.nextInt(MAX_KINGDOM_DIST - MIN_KINGDOM_DIST);
            int cx       = (int)(Math.cos(angle) * dist);
            int cz       = (int)(Math.sin(angle) * dist);
            String[] culture = chooseCulture(rng, i);
            String name      = generateKingdomName(rng);
            String capitalType = resolveCapitalType(culture[0], culture[1]);
            scheduled.add(new ScheduledKingdom(cx, cz, name, culture[0], capitalType));
            System.out.println("[WorldGenKingdomSeeder] Queued '" + name
                    + "' (" + culture[0] + ") at " + cx + "," + cz);
        }
        scheduledDelay = 20;
    }

    private static boolean processSpawn(ServerLevel level, ScheduledKingdom sk) {
        if (level.getServer() == null) return true;

        WorldAtlas atlas = WorldAtlas.get(level);

        // ── Step 1: ensure atlas is filled around the planned centre ─────────
        boolean done = atlas.ensureRegionFilled(level, sk.cx, sk.cz, 800, worldgenBudgetNanos(level));
        if (!done) {
            scheduled.add(0, sk);
            if (++deferralCount % 20 == 0) {
                System.out.println("[WorldGenKingdomSeeder] Atlas fill in progress for '"
                        + sk.name + "' — deferring (" + deferralCount + " defers so far)");
            }
            return false;
        }

        // ── Step 2: find best site within the filled region ──────────────────
        java.util.Optional<AtlasCell> bestOpt =
                AtlasSiteSelector.findBest(
                        atlas, sk.cx, sk.cz, 700,
                        c -> c.isBuildable() && c.centerY() > level.getMinY() + 8);

        if (bestOpt.isEmpty()) {
            System.out.println("[WorldGenKingdomSeeder] '" + sk.name
                    + "' skipped — no suitable atlas cell within 700 blocks");
            return true;
        }

        AtlasCell cell = bestOpt.get();
        BlockPos origin = new BlockPos(
                cell.blockCenterX(), cell.centerY(), cell.blockCenterZ());

        // ── Step 3: kingdom-to-kingdom spacing check ─────────────────────────
        VillageSavedData data =
                VillageSavedData.get(level);

        BlockPos chosenOrigin = trySpacedOrigin(atlas, level, data, sk, origin);
        if (chosenOrigin == null) {
            System.out.println("[WorldGenKingdomSeeder] '" + sk.name
                    + "' skipped — no spacing-clear origin found near "
                    + sk.cx + "," + sk.cz);
            return true;
        }

        System.out.println("[WorldGenKingdomSeeder] Spawning '" + sk.name
                + "' (" + sk.culture + ") at " + chosenOrigin.toShortString()
                + " | biome=" + cell.category()
                + " | capitalType=" + sk.capitalType());

        // Track D3.1 — capital-only initial state. The composition
        // list's first entry is the capital village type; the
        // remaining entries are no longer placed at worldgen (D3
        // phase 2 reintroduces multi-village kingdoms via a
        // vassalage / village-joining mechanism). The legacy
        // KingdomSpawner.planComposed multi-village body is
        // preserved as @Deprecated for /liv kingdom debug commands
        // that admin-spawn full kingdoms; the worldgen path uses
        // CapitalGenerator which honors the D1 culture sub-bundle's
        // claimBudgetHint, sets capitalVillageId / foundingTick,
        // and fires the KingdomFounded bus event.
        String capitalType = sk.capitalType();
        tterrag1112.life_in_the_village.Kingdom.Worldgen.CapitalGenerator.generate(
                level, chosenOrigin, sk.name, sk.culture, capitalType,
                msg -> System.out.println("  " + msg));
        logNearAncientRoad(level, sk.name, chosenOrigin);
        kingdomsPlaced++;
        if (scheduled.isEmpty()) {
            long elapsed = System.currentTimeMillis() - seederStartMs;
            System.out.println("[WorldGenKingdomSeeder] All " + kingdomsPlaced
                    + " kingdoms placed. Total seeder time: " + elapsed + "ms");
        }
        return true;
    }

    /**
     * Searches for a kingdom origin near the requested point that doesn't
     * overlap any existing kingdom's claim. Tries the requested point
     * first, then jittered points outward in a spiral. If no point in the
     * search radius is clear at the default budget, retries with a reduced
     * budget projection (allowing the kingdom to squeeze into a smaller
     * gap). Returns the chosen origin or null if nothing fits.
     */
    private static BlockPos trySpacedOrigin(
            WorldAtlas atlas, ServerLevel level,
            tterrag1112.life_in_the_village.Networking.VillageSavedData data,
            ScheduledKingdom sk, BlockPos preferred) {

        float[] budgetTrials = {
                KingdomClaimComputer.DEFAULT_BUDGET,
                KingdomClaimComputer.DEFAULT_BUDGET * 0.6f,
                KingdomClaimComputer.DEFAULT_BUDGET * 0.35f
        };

        Random rng = new java.util.Random(
                (long) sk.name.hashCode() * 2654435761L ^ level.getSeed());

        for (float budget : budgetTrials) {
            int projected = KingdomSpacingChecker.estimateProjectedRadius(budget);

            // Try the preferred point first
            if (tterrag1112.life_in_the_village.Kingdom.KingdomSpacingChecker
                    .isSpacedClear(data, preferred, projected)
                    && isViableOriginRegion(atlas, preferred)) {
                return preferred;
            }

            // Spiral outward — 12 attempts at increasing offsets
            for (int attempt = 1; attempt <= 12; attempt++) {
                int offset = 200 * attempt;
                double angle = rng.nextDouble() * 2 * Math.PI;
                int tx = preferred.getX() + (int)(Math.cos(angle) * offset);
                int tz = preferred.getZ() + (int)(Math.sin(angle) * offset);

                // Re-fill atlas around the candidate so the site selector has data
                atlas.ensureRegionFilled(level, tx, tz, 600, worldgenBudgetNanos(level));
                var altOpt = AtlasSiteSelector.findBest(atlas, tx, tz, 500,
                        c -> c.isBuildable() && c.centerY() > level.getMinY() + 8);
                if (altOpt.isEmpty()) continue;

                AtlasCell altCell = altOpt.get();
                BlockPos alt = new BlockPos(
                        altCell.blockCenterX(), altCell.centerY(), altCell.blockCenterZ());

                if (tterrag1112.life_in_the_village.Kingdom.KingdomSpacingChecker
                        .isSpacedClear(data, alt, projected)
                        && isViableOriginRegion(atlas, alt)) {
                    System.out.println("[WorldGenKingdomSeeder] '" + sk.name
                            + "' relocated to " + alt.toShortString()
                            + " (offset " + offset + ", budget " + budget + ")");
                    return alt;
                }
            }
            // None worked at this budget — try a smaller one
        }
        return null;
    }

    /**
     * Returns {@code true} if the region around {@code origin} contains enough
     * buildable, non-steep land to host a kingdom's village portfolio.
     *
     * <p>Rejects origins where:
     * <ul>
     *   <li>Ocean biome cells exceed {@link #MAX_OCEAN_FRACTION} of the region
     *       (island / coastal fringe with no buildable hinterland), or</li>
     *   <li>Fewer than {@link #MIN_VIABLE_CELLS} cells are both buildable and
     *       non-steep (cliff face, mountain range, etc.).</li>
     * </ul>
     */
    private static boolean isViableOriginRegion(WorldAtlas atlas, BlockPos origin) {
        int viable = 0, total = 0, ocean = 0;
        for (AtlasCell c : atlas.cellsWithinRadius(
                origin.getX(), origin.getZ(), VIABILITY_CHECK_RADIUS)) {
            total++;
            if (c.category() == BiomeCategory.OCEAN
                    || c.has(AtlasCell.FLAG_HAS_OCEAN)) {
                ocean++;
            } else if (c.isBuildable() && !c.isSteep()) {
                viable++;
            }
        }
        if (total == 0) return false;
        if ((float) ocean / total >= MAX_OCEAN_FRACTION) {
            return false; // too much ocean — no viable hinterland
        }
        return viable >= MIN_VIABLE_CELLS;
    }

    // First kingdom always gets default culture so there is always a royal capital.
    // Others are weighted 50% default, 25% highland, 25% imperial.
    private static String[] chooseCulture(Random rng, int index) {
        Set<String> av = VillageTypeRegistry.INSTANCE.getAvailableTypes();
        if (index == 0 && av.contains("royal_capital"))    return CULTURES[0];
        int roll = rng.nextInt(4);
        if (roll <= 1 && av.contains("royal_capital"))     return CULTURES[0];
        if (roll == 2 && av.contains("highland_capital"))  return CULTURES[1];
        if (roll == 3 && av.contains("imperial_capital"))  return CULTURES[2];
        return CULTURES[0];
    }

    /**
     * Track C1-d — returns the capital village type for the given culture.
     * The remaining composition entries (food, materials, specialist) are
     * removed; portfolio issuance is now handled by
     * {@link tterrag1112.life_in_the_village.Kingdom.Worldgen.PortfolioIssuer}
     * which picks settlement types from {@code biomeAffinity} / {@code kingdomRoles}
     * declared in village type datagen rather than from a hard-coded list.
     */
    private static String resolveCapitalType(String culture, String capitalType) {
        Set<String> av = VillageTypeRegistry.INSTANCE.getAvailableTypes();
        return has(av, capitalType, "default");
    }

    private static String has(Set<String> av, String pref, String fallback) {
        return av.contains(pref) ? pref : fallback;
    }


    // Name tables — indexed by style (independent of actual culture for variety)
    private static final String[][] PREFIXES = {
            {"Iron","Stone","Gold","Silver","Oak","Dawn","Ember","Ridge","Crest","Vale"},
            {"Dun","Glen","Crag","Fern","Ash","Raven","Storm","Mist","Frost","Cairn"},
            {"Aur","Ferr","Caer","Vect","Mons","Ripa","Petra","Vela","Lux","Sal"},
    };
    private static final String[][] SUFFIXES = {
            {"realm","domain","lands","hold","march","shire","ward","keep","mark","reach"},
            {"clan","hold","moor","vale","ridge","peak","croft","tor","brae","fen"},
            {"ium","ia","us","atis","ensis","orum","anum","icus","inus","um"},
    };

    private static String generateKingdomName(Random rng) {
        int style = rng.nextInt(PREFIXES.length);
        return PREFIXES[style][rng.nextInt(PREFIXES[style].length)]
                + SUFFIXES[style][rng.nextInt(SUFFIXES[style].length)];
    }

    /**
     * Checks whether a named great road exists within 6 000 blocks of the given
     * origin and, if so, logs a history note. This is a best-effort check — great
     * roads may not be generated yet on the first world load, in which case the
     * log is simply skipped.
     */
    private static void logNearAncientRoad(ServerLevel level, String kingdomName, BlockPos origin) {
        try {
            var graph = WorldRoadSavedData.get(level).getGraph();
            var nearby = graph.edgesNear(origin.getX(), origin.getZ(), 6_000);
            for (RoadEdge edge : nearby) {
                if (edge.getTier() != RoadEdge.EdgeTier.GREAT_ROAD) continue;
                if (edge.getRoadName().isEmpty()) continue;
                System.out.println("[RoadHistory] The kingdom of " + kingdomName
                        + " was founded near " + edge.getRoadName().get()
                        + ", an ancient road of the Old Realm.");
                return;
            }
        } catch (Exception ignored) {
            // Non-critical — never let history lookup break kingdom spawning
        }
    }

    private record ScheduledKingdom(int cx, int cz, String name,
                                    String culture, String capitalType) {}
}

