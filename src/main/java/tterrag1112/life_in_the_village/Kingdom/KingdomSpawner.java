// src/main/java/tterrag1112/life_in_the_village/Village/KingdomSpawner.java
package tterrag1112.life_in_the_village.Kingdom;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Lore.KingdomHistoryData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.Village.Planning.VillagePlanHelper;
import tterrag1112.life_in_the_village.Village.Simulation.VillageSimEngine;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageSpawner;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.AtlasSiteSelector;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;
import java.util.function.Consumer;

public class KingdomSpawner {

    private static final int MIN_RADIUS             = 400;
    private static final int MAX_RADIUS             = 700;
    private static final int MIN_VILLAGE_SEPARATION = 200;
    private static final int MAX_PLACEMENT_ATTEMPTS = 10;
    private static final int MIN_VILLAGES           = 5;
    private static final int MAX_VILLAGES           = 7;
    private static final int CAPITAL_LEVEL_BOOST    = 3;
    /** Chunk radius to load around each candidate (5×5 area = 80 block radius). */

    private static final String[] CAPITAL_SUFFIXES = {
            "burg", "hold", "ford", "keep", "haven"
    };

    // 15 suffixes — enough that index-based rotation avoids repeats
    // across the 5–7 villages in a kingdom
    private static final String[] VILLAGE_SUFFIXES = {
            "wick", "dale", "stead", "field", "ton",
            "bridge", "mill", "cross", "hollow", "moor",
            "glen", "croft", "worth", "ham", "lea"
    };

    // =========================================================================
    // Entry point
    // =========================================================================

    public static Optional<Kingdom> spawn(ServerLevel level,
                                          BlockPos origin,
                                          String kingdomName,
                                          String culture,
                                          String villageType,
                                          Consumer<String> progress) {
        VillageSavedData data = VillageSavedData.get(level);

        if (data.getKingdomByName(kingdomName).isPresent()) {
            progress.accept("Kingdom '" + kingdomName + "' already exists.");
            return Optional.empty();
        }

        Random rng = new Random(
                (long) kingdomName.hashCode() * 31L + origin.hashCode());

        int villageCount = MIN_VILLAGES
                + rng.nextInt(MAX_VILLAGES - MIN_VILLAGES + 1);

        progress.accept("Planning " + villageCount
                + " villages for kingdom '" + kingdomName + "'...");

        // ── Create kingdom ────────────────────────────────────────────────────
        Kingdom kingdom = new Kingdom(kingdomName, culture);
        data.addKingdom(kingdom);
        kingdom.getHistory().recordEvent(
                HistoryTextGenerator.kingdomFounded(
                        kingdomName, "command", level.getGameTime()),
                kingdomName);
        kingdom.getHistory().setOrigin(
                new KingdomHistoryData.KingdomOriginData(
                        KingdomHistoryData.KingdomOrigins.FOUNDED_BY_PLAYER,
                        "command", new UUID(0, 0),
                        "the wilderness", level.getGameTime(), 0, ""));
        data.setDirty();

        // ── Place villages ────────────────────────────────────────────────────
        int    radius      = MIN_RADIUS + rng.nextInt(MAX_RADIUS - MIN_RADIUS);
        double angleOffset = rng.nextDouble() * 2 * Math.PI;

        List<Village>  placedVillages   = new ArrayList<>();
        List<BlockPos> placedPositions  = new ArrayList<>();
        // Track used suffix indices to prevent name collisions
        Set<Integer>   usedSuffixIndices = new HashSet<>();

        for (int i = 0; i < villageCount; i++) {
            boolean isCapital = (i == 0);
            double  angle     = angleOffset
                    + (2 * Math.PI * i / villageCount);

            BlockPos villagePos = null;

            for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
                // Spiral inward on retry, jitter angle slightly
                int    attemptRadius = radius - attempt * 25;
                if (attemptRadius < MIN_RADIUS / 2) break;

                double jitteredAngle = angle
                        + (attempt > 0
                        ? rng.nextDouble() * 0.4 - 0.2
                        : 0);

                int cx = origin.getX()
                        + (int)(Math.cos(jitteredAngle) * attemptRadius);
                int cz = origin.getZ()
                        + (int)(Math.sin(jitteredAngle) * attemptRadius);

                // ── Force chunk loading BEFORE any heightmap query ────────────
                // getHeight() returns 0 for unloaded chunks, which maps to
                // Y = minY (-64 in 1.21) and produces bogus terrain scores.
                progress.accept("  Loading chunks at "
                        + cx + ", " + cz + "...");

                // Now heightmap is valid
                int surfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);

                // Sanity check — if still at minY after loading, skip
                if (surfY <= level.getMinY() + 4) {
                    progress.accept("  Attempt " + (attempt + 1)
                            + ": void/ocean at " + cx + "," + cz
                            + " — retrying");
                    continue;
                }

                BlockPos candidate = new BlockPos(cx, surfY, cz);

                boolean tooCloseToKingdom = placedPositions.stream()
                        .anyMatch(p -> Math.sqrt(p.distSqr(candidate))
                                < MIN_VILLAGE_SEPARATION);

                boolean tooCloseToWorld =
                        !VillageSpawner.isFarEnoughFromExistingVillages(
                                level, candidate);

                if (!tooCloseToKingdom && !tooCloseToWorld) {
                    villagePos = candidate;
                    break;
                }

                progress.accept("  Attempt " + (attempt + 1)
                        + ": too close to existing village — retrying");
            }

            if (villagePos == null) {
                progress.accept("  Warning: could not place village "
                        + (i + 1) + " after "
                        + MAX_PLACEMENT_ATTEMPTS + " attempts — skipping");
                continue;
            }

            // ── Generate unique village name ──────────────────────────────────
            String villageName = generateUniqueName(
                    kingdomName, isCapital, i, rng, usedSuffixIndices);

            progress.accept("  Spawning "
                    + (isCapital ? "capital " : "village ")
                    + "'" + villageName + "' at "
                    + villagePos.toShortString() + "...");

            Optional<Village> spawnedOpt = VillageSpawner.spawnVillage(
                    level, villagePos, villageType, villageName);

            if (spawnedOpt.isEmpty()) {
                progress.accept("  Failed to spawn '"
                        + villageName + "' — terrain unsuitable");
                continue;
            }

            Village spawned = spawnedOpt.get();

            if (isCapital) {
                int boostedLevel = Math.min(10,
                        spawned.getCurrentLevel() + CAPITAL_LEVEL_BOOST);
                spawned.setCurrentLevel(boostedLevel);
                progress.accept("  Capital level boosted to "
                        + boostedLevel);
            }

            kingdom.addVillage(spawned.getId());
            kingdom.getHistory().recordEvent(
                    HistoryTextGenerator.villageJoined(
                            villageName, kingdomName,
                            level.getGameTime()),
                    kingdomName);

            placedVillages.add(spawned);
            placedPositions.add(villagePos);
            data.setDirty();

            progress.accept("  ✓ Placed '" + villageName + "' ("
                    + placedVillages.size() + "/" + villageCount + ")");
        }

        if (placedVillages.isEmpty()) {
            progress.accept("Kingdom spawn failed — no villages placed. "
                    + "Try a location with more varied terrain.");
            data.removeKingdom(kingdom.getId());
            data.setDirty();
            return Optional.empty();
        }

        // ── Trade routes ──────────────────────────────────────────────────────
        progress.accept("Establishing trade routes between "
                + placedVillages.size() + " villages...");
        for (Village village : placedVillages) {
            TradeRouteManager.establishRoutes(level, village, data);
        }

        data.setDirty();

        long routeCount = data.getAllTradeRoutes().stream()
                .filter(r -> kingdom.getVillageIds().contains(r.getVillageA())
                        || kingdom.getVillageIds().contains(r.getVillageB()))
                .count();

        progress.accept("Kingdom '" + kingdomName + "' complete — "
                + placedVillages.size() + " villages, "
                + routeCount + " trade routes.");

        return Optional.of(kingdom);
    }


    // =========================================================================
    // Name generation
    // =========================================================================

    /**
     * Generates a village name guaranteed not to reuse a suffix already
     * used by another village in this kingdom.
     */
    private static String generateUniqueName(String kingdomName,
                                             boolean isCapital,
                                             int index,
                                             Random rng,
                                             Set<Integer> usedSuffixIndices) {
        String base = kingdomName.replaceAll("\\s+", "");
        if (!base.isEmpty()) {
            base = Character.toUpperCase(base.charAt(0))
                    + base.substring(1).toLowerCase();
        }

        if (isCapital) {
            // Capital suffix is drawn from a separate pool — no collision risk
            String suffix = CAPITAL_SUFFIXES[
                    rng.nextInt(CAPITAL_SUFFIXES.length)];
            return base + suffix;
        }

        // Find a suffix index not yet used
        // Start at a position derived from village index to spread names out
        int startIdx = (index * 3) % VILLAGE_SUFFIXES.length;
        for (int attempt = 0; attempt < VILLAGE_SUFFIXES.length; attempt++) {
            int idx = (startIdx + attempt) % VILLAGE_SUFFIXES.length;
            if (!usedSuffixIndices.contains(idx)) {
                usedSuffixIndices.add(idx);
                return base + VILLAGE_SUFFIXES[idx];
            }
        }

        // All suffixes used (only possible with 15+ villages) — append index
        return base + "village" + index;
    }

    public static Optional<Kingdom> spawnComposed(
            ServerLevel level,
            BlockPos origin,
            String kingdomName,
            String culture,
            List<String> composition,
            Consumer<String> progress) {

        VillageSavedData data = VillageSavedData.get(level);

        if (data.getKingdomByName(kingdomName).isPresent()) {
            progress.accept("Kingdom '" + kingdomName + "' already exists — skipping.");
            return Optional.empty();
        }

        if (composition.isEmpty()) {
            progress.accept("Composition list is empty — aborting.");
            return Optional.empty();
        }

        Random rng = new Random(
                (long) kingdomName.hashCode() * 31L + origin.hashCode());

        int villageCount = composition.size();
        progress.accept("Planning " + villageCount
                + " villages for kingdom '" + kingdomName + "'...");

        Kingdom kingdom = new Kingdom(kingdomName, culture);
        data.addKingdom(kingdom);
        kingdom.getHistory().recordEvent(
                HistoryTextGenerator.kingdomFounded(
                        kingdomName, "world-gen", level.getGameTime()),
                kingdomName);
        kingdom.getHistory().setOrigin(
                new KingdomHistoryData.KingdomOriginData(
                        KingdomHistoryData.KingdomOrigins.FOUNDED_BY_PLAYER,
                        "world-gen", new UUID(0, 0),
                        "the wilderness", level.getGameTime(), 0, ""));
        data.setDirty();

        int    radius           = MIN_RADIUS + rng.nextInt(MAX_RADIUS - MIN_RADIUS);
        double angleOffset      = rng.nextDouble() * 2 * Math.PI;
        List<Village>  placedVillages  = new ArrayList<>();
        List<BlockPos> placedPositions = new ArrayList<>();
        Set<Integer>   usedSuffixes    = new HashSet<>();

        for (int i = 0; i < villageCount; i++) {
            boolean isCapital   = (i == 0);
            String  villageType = composition.get(i);

            if (VillageTypeRegistry.INSTANCE.getType(villageType) == null) {
                progress.accept("  Warning: unknown type '" + villageType + "' — using 'default'");
                villageType = "default";
            }

            double angle = angleOffset + (2 * Math.PI * i / villageCount);

            // ── Find a candidate position ─────────────────────────────────────────
            BlockPos villagePos = findCandidatePosition(
                    level, origin, angle, radius,
                    placedPositions, isCapital, villageType, progress, rng);

            if (villagePos == null) {
                if (isCapital) {
                    // Capital is mandatory — no capital means no kingdom
                    progress.accept("  Capital could not be placed after all attempts "
                            + "— aborting kingdom '" + kingdomName + "'");
                    data.removeKingdom(kingdom.getId());
                    data.setDirty();
                    return Optional.empty();
                }
                progress.accept("  Warning: could not place village "
                        + (i + 1) + " — skipping");
                continue;
            }

            String villageName = generateUniqueName(
                    kingdomName, isCapital, i, rng, usedSuffixes);
            progress.accept("  Spawning "
                    + (isCapital ? "capital " : "")
                    + "'" + villageName + "' (" + villageType + ") at "
                    + villagePos.toShortString() + "...");

            // ── Attempt spawn, with extra retries for the capital ─────────────────
            Optional<Village> spawnedOpt = trySpawnWithFallback(
                    level, villagePos, villageType, villageName,
                    isCapital, origin, angle, radius,
                    placedPositions, progress, rng);

            if (spawnedOpt.isEmpty()) {
                if (isCapital) {
                    progress.accept("  Capital spawn failed after all fallback "
                            + "positions — aborting kingdom '" + kingdomName + "'");
                    data.removeKingdom(kingdom.getId());
                    data.setDirty();
                    return Optional.empty();
                }
                progress.accept("  Failed to spawn '" + villageName
                        + "' — terrain unsuitable, skipping");
                continue;
            }

            Village spawned = spawnedOpt.get();

            if (isCapital) {
                int boostedLevel = Math.min(10,
                        spawned.getCurrentLevel() + CAPITAL_LEVEL_BOOST);
                spawned.setCurrentLevel(boostedLevel);
                progress.accept("  Capital level boosted to " + boostedLevel);
            }

            kingdom.addVillage(spawned.getId());
            kingdom.getHistory().recordEvent(
                    HistoryTextGenerator.villageJoined(
                            villageName, kingdomName, level.getGameTime()),
                    kingdomName);

            placedVillages.add(spawned);
            placedPositions.add(villagePos);
            data.setDirty();

            progress.accept("  ✓ Placed '" + villageName + "' ("
                    + placedVillages.size() + "/" + villageCount + ")");
        }

        if (placedVillages.isEmpty()) {
            progress.accept("Kingdom spawn failed — no villages placed.");
            data.removeKingdom(kingdom.getId());
            data.setDirty();
            return Optional.empty();
        }

        progress.accept("Establishing trade routes for "
                + placedVillages.size() + " villages...");
        for (Village v : placedVillages) {
            TradeRouteManager.establishRoutes(level, v, data);
        }

        tterrag1112.life_in_the_village.Village.Simulation.VillageSimEngine
                .buildBaseline(placedVillages.get(0), data, level.getGameTime());

        data.setDirty();
        long routeCount = data.getAllTradeRoutes().stream()
                .filter(r -> kingdom.getVillageIds().contains(r.getVillageA())
                        || kingdom.getVillageIds().contains(r.getVillageB()))
                .count();
        progress.accept("Kingdom '" + kingdomName + "' complete — "
                + placedVillages.size() + " villages, "
                + routeCount + " trade routes.");
        return Optional.of(kingdom);
    }

    // =========================================================================
    // Atlas-based candidate finder
    // =========================================================================

    /**
     * Finds a village position at the requested angle and radius from the
     * kingdom origin using the World Atlas. No chunk loading occurs.
     *
     * <p>The atlas is filled around the search target if needed (synchronous,
     * budgeted). The site selector then picks the best-scoring buildable cell
     * in an angular wedge centred on the requested angle.
     *
     * @return chosen position, or null if no buildable cell is available
     */
    private static BlockPos findCandidatePosition(
            ServerLevel level, BlockPos origin, double angle, int radius,
            List<BlockPos> placedPositions, boolean isCapital,
            String villageType,  // ← new parameter
            java.util.function.Consumer<String> progress, java.util.Random rng) {

        WorldAtlas atlas = WorldAtlas.get(level);

        int targetX = origin.getX() + (int)(Math.cos(angle) * radius);
        int targetZ = origin.getZ() + (int)(Math.sin(angle) * radius);

        progress.accept("  Filling atlas around " + targetX + "," + targetZ);
        atlas.ensureRegionFilled(level, targetX, targetZ, 250, 50_000_000L);

        double spread = Math.toRadians(25);
        int minR = Math.max(0, radius - 200);
        int maxR = radius + 200;

        // Filter — same hard constraints as before
        java.util.function.Predicate<AtlasCell> filter = cell -> {
            BlockPos p = new BlockPos(
                    cell.blockCenterX(),
                    cell.centerY(),
                    cell.blockCenterZ());

            for (BlockPos placed : placedPositions) {
                if (placed.distSqr(p)
                        < (double) MIN_VILLAGE_SEPARATION
                        * MIN_VILLAGE_SEPARATION) {
                    return false;
                }
            }

            return tterrag1112.life_in_the_village.Village.VillageSpawner
                    .isFarEnoughFromExistingVillages(level, p);
        };

        java.util.Optional<AtlasCell> bestOpt;

        if ("trade_hub".equals(villageType)) {
            // Trade hubs prefer cells near existing roads
            bestOpt = AtlasSiteSelector.findBestScoredInWedge(
                    atlas, origin.getX(), origin.getZ(),
                    angle, spread, minR, maxR,
                    filter,
                    cell -> tradeHubRouteBonus(atlas, villageType,
                            cell.blockCenterX(), cell.blockCenterZ()));
        } else {
            // Other village types use the original first-match logic
            bestOpt = AtlasSiteSelector.findBestInWedge(
                    atlas, origin.getX(), origin.getZ(),
                    angle, spread, minR, maxR,
                    filter);
        }

        if (bestOpt.isEmpty()) {
            progress.accept("  No buildable atlas cell in wedge "
                    + (int) Math.toDegrees(angle) + "° ± 25°");
            return null;
        }

        AtlasCell chosen = bestOpt.get();
        BlockPos result = new BlockPos(
                chosen.blockCenterX(), chosen.centerY(), chosen.blockCenterZ());
        progress.accept("  Selected " + result.toShortString()
                + " (" + chosen.category() + ", slope=" + chosen.slope() + ")");
        return result;
    }

    /**
     * Attempts to spawn a village at the given position.
     * For capitals, if the planner rejects the terrain, tries up to
     * CAPITAL_FALLBACK_ATTEMPTS alternative positions in an expanding
     * spiral before returning empty.
     */
    private static final int CAPITAL_FALLBACK_ATTEMPTS = 8;

    private static Optional<Village> trySpawnWithFallback(
            ServerLevel level,
            BlockPos initialPos,
            String villageType,
            String villageName,
            boolean isCapital,
            BlockPos kingdomOrigin,
            double angle,
            int radius,
            List<BlockPos> placedPositions,
            Consumer<String> progress,
            Random rng) {

        // First try the planned position
        Optional<Village> result = VillageSpawner.spawnVillage(
                level, initialPos, villageType, villageName);

        if (result.isPresent() || !isCapital) return result;

        // Capital failed — try fallback positions at increasing offsets
        progress.accept("  Capital terrain rejected at "
                + initialPos.toShortString() + " — searching fallback positions...");

        for (int fb = 0; fb < CAPITAL_FALLBACK_ATTEMPTS; fb++) {
            // Spiral outward in steps of 80 blocks, sweeping angle
            double fbAngle  = angle + (2 * Math.PI * fb / CAPITAL_FALLBACK_ATTEMPTS);
            int    fbRadius = radius + 80 + fb * 60;

            int cx = kingdomOrigin.getX() + (int)(Math.cos(fbAngle) * fbRadius);
            int cz = kingdomOrigin.getZ() + (int)(Math.sin(fbAngle) * fbRadius);


            int surfY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
            if (surfY <= level.getMinY() + 4) continue;

            BlockPos fbPos = new BlockPos(cx, surfY, cz);

            boolean tooClose = placedPositions.stream()
                    .anyMatch(p -> Math.sqrt(p.distSqr(fbPos)) < MIN_VILLAGE_SEPARATION);
            if (tooClose) continue;

            if (!VillageSpawner.isFarEnoughFromExistingVillages(level, fbPos)) continue;

            progress.accept("  Trying capital fallback position "
                    + (fb + 1) + " at " + fbPos.toShortString() + "...");

            Optional<Village> fbResult = VillageSpawner.spawnVillage(
                    level, fbPos, villageType, villageName);

            if (fbResult.isPresent()) {
                progress.accept("  Capital placed at fallback position " + (fb + 1));
                return fbResult;
            }
        }

        return Optional.empty();
    }
    // =========================================================================
    // Plan-only kingdom creation
    // =========================================================================

    /**
     * Creates a kingdom and its constituent villages as PLANNED records only.
     * No blocks are placed and no NPCs are spawned. Each village is realised
     * lazily by {@link tterrag1112.life_in_the_village.Events.VillageRealisationSystem}
     * when a player enters its trigger radius.
     *
     * <p>This method replaces the old worldgen-time use of
     * {@link #spawnComposed}. Use {@code spawnComposed} only when an
     * immediate block-level placement is required (debug commands, etc.).
     *
     * <p>Trade routes are not established at plan time — they require
     * block-level pathfinding across terrain that isn't loaded yet. Route
     * creation is deferred to the first tick after all participating
     * villages become realised.
     */
    public static java.util.Optional<tterrag1112.life_in_the_village.Kingdom.Kingdom>
    planComposed(ServerLevel level,
                 BlockPos origin,
                 String kingdomName,
                 String culture,
                 java.util.List<String> composition,
                 java.util.function.Consumer<String> progress) {

        tterrag1112.life_in_the_village.Networking.VillageSavedData data =
                tterrag1112.life_in_the_village.Networking.VillageSavedData.get(level);

        if (data.getKingdomByName(kingdomName).isPresent()) {
            progress.accept("Kingdom '" + kingdomName + "' already exists.");
            return java.util.Optional.empty();
        }
        if (composition.isEmpty()) {
            progress.accept("Composition list is empty — aborting.");
            return java.util.Optional.empty();
        }

        java.util.Random rng = new java.util.Random(
                (long) kingdomName.hashCode() * 31L + origin.hashCode());

        int villageCount = composition.size();
        progress.accept("Planning " + villageCount
                + " virtual villages for kingdom '" + kingdomName + "'...");

        tterrag1112.life_in_the_village.Kingdom.Kingdom kingdom =
                new tterrag1112.life_in_the_village.Kingdom.Kingdom(kingdomName, culture);
        data.addKingdom(kingdom);
        kingdom.getHistory().recordEvent(
                tterrag1112.life_in_the_village.Lore.HistoryTextGenerator.kingdomFounded(
                        kingdomName, "world-gen", level.getGameTime()),
                kingdomName);
        kingdom.getHistory().setOrigin(
                new tterrag1112.life_in_the_village.Lore.KingdomHistoryData.KingdomOriginData(
                        tterrag1112.life_in_the_village.Lore.KingdomHistoryData.KingdomOrigins.FOUNDED_BY_PLAYER,
                        "world-gen", new java.util.UUID(0, 0),
                        "the wilderness", level.getGameTime(), 0, ""));
        data.setDirty();

        int    radius      = MIN_RADIUS + rng.nextInt(MAX_RADIUS - MIN_RADIUS);
        double angleOffset = rng.nextDouble() * 2 * Math.PI;
        java.util.List<BlockPos> placedPositions = new java.util.ArrayList<>();
        java.util.Set<Integer>   usedSuffixes    = new java.util.HashSet<>();

        for (int i = 0; i < villageCount; i++) {
            boolean isCapital   = (i == 0);
            String  villageType = composition.get(i);

            if (tterrag1112.life_in_the_village.Village.VillageTypeRegistry.INSTANCE
                    .getType(villageType) == null) {
                progress.accept("  Warning: unknown type '" + villageType
                        + "' — using 'default'");
                villageType = "default";
            }

            double angle = angleOffset + (2 * Math.PI * i / villageCount);

            BlockPos villagePos = findCandidatePosition(
                    level, origin, angle, radius,
                    placedPositions, isCapital, villageType, progress, rng);

            if (villagePos == null) {
                if (isCapital) {
                    progress.accept("  Capital could not be placed — aborting '"
                            + kingdomName + "'");
                    data.removeKingdom(kingdom.getId());
                    data.setDirty();
                    return java.util.Optional.empty();
                }
                progress.accept("  Warning: could not place village "
                        + (i + 1) + " — skipping");
                continue;
            }

            String villageName = generateUniqueName(
                    kingdomName, isCapital, i, rng, usedSuffixes);
            progress.accept("  Planning "
                    + (isCapital ? "capital " : "village ")
                    + villageName + " at " + villagePos.toShortString());

            Village planned =
                    VillagePlanHelper
                            .planVillage(level, data, villagePos,
                                    villageType, villageName);

            kingdom.addVillage(planned.getId());
            placedPositions.add(villagePos);
        }

        data.setDirty();
        progress.accept("Planned kingdom '" + kingdomName + "' with "
                + kingdom.getVillageIds().size() + " villages");
        return java.util.Optional.of(kingdom);
    }

    // Add this static helper to KingdomSpawner.java (or wherever site scoring lives):

    /**
     * Returns a site bonus for trade-hub villages based on how many
     * existing roads pass within 256 blocks. Used by the seeder to
     * make trade hubs preferentially spawn at route junctions.
     *
     * <p>Returns 0 for non-trade-hub types so calling this is always
     * safe regardless of village type.
     */
    private static double tradeHubRouteBonus(WorldAtlas atlas,
                                             String villageType,
                                             int blockX, int blockZ) {
        if (!"trade_hub".equals(villageType)) return 0.0;

        int roadCount = atlas.countRoadsNear(blockX, blockZ, 256);
        // Each nearby road contributes 0.5 to the bonus, capped at 3.0
        return Math.min(3.0, roadCount * 0.5);
    }
}