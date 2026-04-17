// src/main/java/tterrag1112/life_in_the_village/Kingdom/WorldGenKingdomSeeder.java
package tterrag1112.life_in_the_village.Kingdom;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
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
    private static final int MAX_KINGDOMS      = 4;
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

    private static boolean seederRan  = false;
    private static final List<ScheduledKingdom> scheduled = new ArrayList<>();
    private static int scheduledDelay = 0;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel overworld = event.getServer().overworld();
        long tick = overworld.getGameTime();

        if (!seederRan) {
            seederRan = true;
            VillageSavedData data = VillageSavedData.get(overworld);
            if (data.getAllKingdoms().isEmpty()
                    && !VillageTypeRegistry.INSTANCE.getAvailableTypes().isEmpty()) {
                planKingdoms(overworld, tick);
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

    private static void planKingdoms(ServerLevel level, long tick) {
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
            List<String> comp = buildComposition(rng, culture[0], culture[1]);
            scheduled.add(new ScheduledKingdom(cx, cz, name, culture[0], comp));
            System.out.println("[WorldGenKingdomSeeder] Queued '" + name
                    + "' (" + culture[0] + ") at " + cx + "," + cz);
        }
        scheduledDelay = 20;
    }

    private static boolean processSpawn(ServerLevel level, ScheduledKingdom sk) {
        if (level.getServer() == null) return true;

        WorldAtlas atlas = WorldAtlas.get(level);

        // ── Step 1: ensure atlas is filled around the planned centre ─────────
        boolean done = atlas.ensureRegionFilled(level, sk.cx, sk.cz, 800, 30_000_000L);
        if (!done) {
            scheduled.add(0, sk);
            System.out.println("[WorldGenKingdomSeeder] Atlas fill in progress for '"
                    + sk.name + "' — deferring");
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
        tterrag1112.life_in_the_village.Networking.VillageSavedData data =
                tterrag1112.life_in_the_village.Networking.VillageSavedData.get(level);

        BlockPos chosenOrigin = trySpacedOrigin(atlas, level, data, sk, origin);
        if (chosenOrigin == null) {
            System.out.println("[WorldGenKingdomSeeder] '" + sk.name
                    + "' skipped — no spacing-clear origin found near "
                    + sk.cx + "," + sk.cz);
            return true;
        }

        System.out.println("[WorldGenKingdomSeeder] Spawning '" + sk.name
                + "' (" + sk.culture + ") at " + chosenOrigin.toShortString()
                + " | biome=" + cell.category() + " | " + sk.composition);

        KingdomSpawner.planComposed(level, chosenOrigin, sk.name, sk.culture,
                sk.composition,
                msg -> System.out.println("  " + msg));
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
                tterrag1112.life_in_the_village.Kingdom.KingdomClaimComputer.DEFAULT_BUDGET,
                tterrag1112.life_in_the_village.Kingdom.KingdomClaimComputer.DEFAULT_BUDGET * 0.6f,
                tterrag1112.life_in_the_village.Kingdom.KingdomClaimComputer.DEFAULT_BUDGET * 0.35f
        };

        java.util.Random rng = new java.util.Random(
                (long) sk.name.hashCode() * 2654435761L ^ level.getSeed());

        for (float budget : budgetTrials) {
            int projected = tterrag1112.life_in_the_village.Kingdom
                    .KingdomSpacingChecker.estimateProjectedRadius(budget);

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
                atlas.ensureRegionFilled(level, tx, tz, 600, 30_000_000L);
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

    private static List<String> buildComposition(Random rng, String culture, String capitalType) {
        Set<String> av = VillageTypeRegistry.INSTANCE.getAvailableTypes();
        List<String> c = new ArrayList<>();
        c.add(has(av, capitalType,        "default"));          // 0 — capital
        c.add(has(av, "farming_village",  "default"));          // 1 — food
        c.add(has(av, "farming_village",  "default"));          // 2 — food
        c.add(has(av, "mining_camp",      "default"));          // 3 — materials
        String specialist = switch (culture) {                   // 4 — specialist
            case "highland" -> has(av, "fortress_town",   "default");
            case "imperial" -> has(av, "trade_hub",       "default");
            default         -> {
                String[] opts = {"noble_estate","trade_hub","riverside_town","farming_village"};
                yield has(av, opts[rng.nextInt(opts.length)], "default");
            }
        };
        c.add(specialist);
        return c;
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

    private record ScheduledKingdom(int cx, int cz, String name,
                                    String culture, List<String> composition) {}
}

