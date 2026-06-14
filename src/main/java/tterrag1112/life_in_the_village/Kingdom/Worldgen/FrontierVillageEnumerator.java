package tterrag1112.life_in_the_village.Kingdom.Worldgen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Kingdom.Settlement.CharterDigest;
import tterrag1112.life_in_the_village.Kingdom.Settlement.SettlementCharter;
import tterrag1112.life_in_the_village.Kingdom.Settlement.VillagePlacement;
import tterrag1112.life_in_the_village.Kingdom.Settlement.VillagePlacement.VillageCandidate;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Track V5 -- the FRONTIER village enumerator + the LOD substrate.
 *
 * <p>Frontier villages exist OUTSIDE every kingdom claim (doc 16 sec.2): the
 * world is the star, not just the kingdoms. They are placed by the SAME
 * deterministic V1 spread the kingdom grid uses, sampled at the sparser
 * frontier subset ({@link VillagePlacement#isFrontierRegion} /
 * {@link VillagePlacement#FRONTIER_SPACING_MULTIPLIER}). They carry no owning
 * kingdom (the {@link SettlementCharter#FRONTIER_KINGDOM} sentinel) and the
 * {@code default} culture until/unless a kingdom later claims their cell, at
 * which point they are absorbed in place (see
 * {@link VillageSavedData#absorbFrontierChartersInto}).
 *
 * <h3>LOD: near-player eager, distant lazy</h3>
 * This is the V5 LOD mechanism. Frontier enumeration runs ONLY around players,
 * within {@link #ENUMERATE_RADIUS_REGIONS} placement regions of any player, a
 * bounded budget of regions per pass ({@link #MAX_REGIONS_PER_PASS}). Distant
 * frontier never pays enumeration/persistence cost up front -- it is resolved
 * lazily as a player nears (or, for realization, only on approach in V4). This
 * directly addresses the "any area completely packed -> lag" failure: nothing
 * far away is enumerated, claimed, or realized until a player is near.
 *
 * <h3>Consistency given the IMPERATIVE claim mechanism (the key subtlety)</h3>
 * Kingdom claims are NOT a deterministic function of seed -- they are an
 * imperative Dijkstra flood over the atlas-fill state
 * ({@code KingdomClaimComputer}), so "which kingdom claims cell X" depends on
 * processing/fill order. Lazy processing must therefore NOT let the same region
 * yield different villages depending on what ran when. We keep it consistent
 * by RESOLVING THE CLAIM ON DEMAND before placing a frontier village: a region
 * is enumerated as frontier ONLY if {@code getKingdomForCell(cell)} is empty
 * RIGHT NOW (the cell is genuinely unclaimed against the persisted claims).
 * If a kingdom later claims that cell, absorption (sec.5) re-owns the SAME
 * village in place -- no duplicate, no reposition, because the frontier
 * candidate IS the candidate the kingdom grid would have enumerated (the subset
 * rule). The village's POSITION is deterministic-from-seed regardless of order;
 * only its OWNERSHIP/culture is derived from the (imperative) claim, and that is
 * a monotonic upgrade (default -> kingdom) that absorption applies idempotently.
 * So order affects who-owns, never which-village-where.
 *
 * <h3>Cost tier (sec.1)</h3>
 * Tier-0/1: reads ONLY load-free on-demand digests (via V1) + the persisted
 * claim sets (for the unclaimed check). No column scan, no atlas-fill forcing,
 * no V2. Realization stays the only Tier-2 work (V4, on approach).
 */
public final class FrontierVillageEnumerator {

    private FrontierVillageEnumerator() {}

    /**
     * How many placement regions out from a player frontier enumeration
     * considers. At {@code SPACING_CHUNKS=48} a region is 768 blocks, so a
     * radius of 3 regions reaches ~2.3k blocks -- well beyond the 256-block
     * realization radius, so frontier villages are charted before the player
     * is close enough to realize them, but nothing distant is touched. Tunable.
     */
    public static final int ENUMERATE_RADIUS_REGIONS = 3;

    /**
     * Hard cap on frontier regions evaluated per pass (the bounded LOD budget).
     * Each eval is one ~100us load-free digest read; this bounds the pass cost
     * regardless of how many players or how dense the frontier subset is.
     * Tunable.
     */
    public static final int MAX_REGIONS_PER_PASS = 64;

    /**
     * Enumerate frontier villages in the regions near {@code players} that are
     * (a) frontier regions, (b) not yet charted (frontier or owned), and (c)
     * genuinely unclaimed right now. Persists each as a frontier charter.
     *
     * @return the number of new frontier charters issued.
     */
    public static int enumerateNearPlayers(ServerLevel level, VillageSavedData data,
                                           java.util.List<? extends ServerPlayer> players,
                                           long tick, Consumer<String> progress) {
        if (players.isEmpty()) return 0;

        // Collect the distinct frontier regions within range of any player.
        Set<Long> regions = frontierRegionsNearPlayers(players);
        if (regions.isEmpty()) return 0;

        // Cells already charted as frontier (dedup) -- a set for O(1) checks.
        Set<Long> frontierCells = new java.util.HashSet<>();
        for (SettlementCharter c : data.getFrontierCharters()) {
            frontierCells.add(c.targetCellKey());
        }

        int issued = 0;
        int evaluated = 0;
        for (long regionKey : regions) {
            if (evaluated >= MAX_REGIONS_PER_PASS) break;
            int rx = AtlasCell.unpackX(regionKey);
            int rz = AtlasCell.unpackZ(regionKey);
            evaluated++;

            // V1 frontier subset -- pure, load-free digest only.
            VillageCandidate cand = VillagePlacement
                    .evaluateFrontierRegion(level, rx, rz)
                    .orElse(null);
            if (cand == null) continue; // not a frontier region, or gate rejected

            long cell = cand.cellKey();

            // Dedup: already a frontier charter here.
            if (frontierCells.contains(cell)) continue;

            // Consistency vs the imperative claim: only place frontier where
            // the cell is genuinely unclaimed RIGHT NOW. If a kingdom owns it,
            // the kingdom grid (V2) already charts it -- not frontier.
            if (data.getKingdomForCell(cell).isPresent()) continue;

            // Build the stage-0 digest from the candidate's cell if present
            // (load-free read; UNKNOWN fallback -- never blocks on a fill).
            AtlasCell atlasCell = readCellIfPresent(level, cell);
            CharterDigest digest = atlasCell != null
                    ? CharterDigest.fromCell(atlasCell, cand.sizeBand())
                    : CharterDigest.UNKNOWN;

            String name = frontierName(cand.role(), cell);
            SettlementCharter charter = SettlementCharter.frontier(
                    UUID.randomUUID(), name, cell, cand.role(),
                    cand.sizeBand(), digest, tick);
            data.addFrontierCharter(charter);
            frontierCells.add(cell);
            issued++;
        }

        if (issued > 0) {
            progress.accept("FrontierVillageEnumerator: issued " + issued
                    + " frontier village charter(s) (evaluated " + evaluated
                    + " frontier regions near players; default culture, no owner). "
                    + "Reads load-free digests only -- no scan, no atlas-fill, no V2.");
        }
        return issued;
    }

    /** Distinct frontier regions within {@link #ENUMERATE_RADIUS_REGIONS} of any player. */
    static Set<Long> frontierRegionsNearPlayers(
            java.util.List<? extends ServerPlayer> players) {
        Set<Long> regions = new LinkedHashSet<>();
        for (ServerPlayer p : players) {
            int prx = VillagePlacement.regionForBlockX((int) Math.floor(p.getX()));
            int prz = VillagePlacement.regionForBlockZ((int) Math.floor(p.getZ()));
            for (int dx = -ENUMERATE_RADIUS_REGIONS; dx <= ENUMERATE_RADIUS_REGIONS; dx++) {
                for (int dz = -ENUMERATE_RADIUS_REGIONS; dz <= ENUMERATE_RADIUS_REGIONS; dz++) {
                    int rx = prx + dx;
                    int rz = prz + dz;
                    if (!VillagePlacement.isFrontierRegion(rx, rz)) continue;
                    regions.add(AtlasCell.packKey(rx, rz));
                }
            }
        }
        return regions;
    }

    private static AtlasCell readCellIfPresent(ServerLevel level, long cellKey) {
        tterrag1112.life_in_the_village.World.Atlas.WorldAtlas atlas =
                tterrag1112.life_in_the_village.World.Atlas.WorldAtlas.get(level);
        return atlas.getCellByCoord(
                AtlasCell.unpackX(cellKey), AtlasCell.unpackZ(cellKey));
    }

    /** Deterministic frontier settlement name: "Frontier <Role> <cellX>_<cellZ>". */
    static String frontierName(String role, long cellKey) {
        String titleRole = toTitleCase(role.replace('_', ' '));
        return "Frontier " + titleRole + " "
                + AtlasCell.unpackX(cellKey) + "_" + AtlasCell.unpackZ(cellKey);
    }

    private static String toTitleCase(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}
