package tterrag1112.life_in_the_village.Kingdom.Worldgen;

import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomClaim;
import tterrag1112.life_in_the_village.Kingdom.Settlement.CharterDigest;
import tterrag1112.life_in_the_village.Kingdom.Settlement.SettlementCharter;
import tterrag1112.life_in_the_village.Kingdom.Settlement.VillagePlacement;
import tterrag1112.life_in_the_village.Kingdom.Settlement.VillagePlacement.VillageCandidate;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Track V2 -- the claim overlay + enumeration (Tier 1).
 *
 * <p>When a kingdom claim is established, this enumerates the deterministic
 * V1 {@link VillagePlacement} candidates whose landing cell falls inside the
 * claim, and persists each as a kingdom-owned settlement charter (the
 * re-roled {@link SettlementCharter}: cell + candidate pos + role +
 * {@code stage=CHARTERED}, realizedId empty). Capital promotion (V3) and
 * realization (V4) consume the result.
 *
 * <h3>Cost-tier compliance (THE load-bearing rule)</h3>
 * This is <b>Tier-1</b> work: it reads V1, which reads ONLY the load-free
 * on-demand atlas digest sample ({@code AtlasSampler.sampleCell}). It does
 * <b>not</b> scan columns, does <b>not</b> force the persisted atlas fill,
 * does <b>not</b> validate footprints, and does <b>not</b> call V2. Cost is
 * claim-sized (a handful of placement regions over the claim's bounding box,
 * one ~100us digest sample each). Pushing any heavier work here is the
 * documented relapse (doc 16 sec.1/7).
 *
 * <h3>Determinism</h3>
 * Enumeration is a pure function of (claim cells, world seed): the same claim
 * always yields the same charters. The placement is V1's deterministic
 * spread; this class only filters it to the claim and issues records.
 *
 * <h3>Village -> kingdom is derived, not duplicated (sec.5)</h3>
 * The charter records its issuing {@code kingdomId}, but the authoritative
 * ownership relation is the cell-claim: {@code
 * VillageSavedData.getKingdomForCell(cellKey)}. The kingdomId on the charter
 * is the issuer at enumeration time; if claims ever shift, the claim is
 * authoritative. Culture/religion are likewise derived from the owning
 * kingdom ({@code kingdom.getCulture()}), not copied onto the charter.
 */
public final class ClaimVillageEnumerator {

    private ClaimVillageEnumerator() {}

    /**
     * Enumerates V1 candidates within {@code kingdom}'s claim and issues a
     * settlement charter for each (skipping cells already charted, e.g. the
     * capital cell). Reuses {@link Kingdom#issueSettlementCharter}.
     *
     * <p>Reads load-free digests only -- see the class note on cost tiers.
     *
     * @param kingdom   the newly born kingdom; its claim must be set.
     * @param level     the overworld (source of the world seed + the load-free
     *                  digest sampler). NOT used to force any atlas fill.
     * @param tick      current game tick (for {@code issuedTick}).
     * @param progress  info-level reporter mirrored to console.
     * @return the number of charters issued.
     */
    public static int enumerate(Kingdom kingdom,
                                net.minecraft.server.level.ServerLevel level,
                                long tick,
                                Consumer<String> progress) {
        KingdomClaim claim = kingdom.getTerritorialClaim().orElse(null);
        if (claim == null || claim.claimedCellKeys().isEmpty()) {
            progress.accept("ClaimVillageEnumerator: no claim -- skipping.");
            return 0;
        }

        // Fast membership set of claimed cells.
        Set<Long> claimedCells = new HashSet<>(claim.claimedCellKeys());

        // Cells already charted (the capital cell issued by CapitalGenerator,
        // plus anything pre-existing) -- never double-charter a cell.
        Set<Long> chartedCells = new HashSet<>();
        for (SettlementCharter c : kingdom.getSettlementCharters()) {
            chartedCells.add(c.targetCellKey());
        }

        // The placement regions overlapping the claim's bounding box. A claim
        // cell is 64 blocks; a placement region is SPACING_CHUNKS*16 blocks.
        // Collect the distinct regions touched by any claimed cell, evaluate
        // each ONCE (V1 places one candidate per region), keep candidates whose
        // landing cell is claimed.
        Set<Long> regions = regionsOverlappingClaim(claimedCells);

        long worldSeed = level.getSeed();
        int issued = 0;
        int evaluated = 0;
        int ownership = 0; // candidates landing in a claimed cell

        for (long regionKey : regions) {
            int rx = AtlasCell.unpackX(regionKey);
            int rz = AtlasCell.unpackZ(regionKey);
            evaluated++;

            // V1 -- pure, load-free digest only.
            VillageCandidate cand = VillagePlacement
                    .evaluateRegion(level, rx, rz)
                    .orElse(null);
            if (cand == null) continue; // gate rejected (ocean / void / no land)

            // Only own candidates whose cell is inside this kingdom's claim.
            if (!claimedCells.contains(cand.cellKey())) continue;
            ownership++;

            // Skip a cell already charted (capital).
            if (chartedCells.contains(cand.cellKey())) continue;

            // Build the stage-0 digest from the candidate's cell. The atlas
            // was filled around the capital by CapitalGenerator, so the cell is
            // usually present; if not, the digest falls back to UNKNOWN (the
            // charter still issues -- never fail).
            AtlasCell cell = readCellIfPresent(level, cand.cellKey());
            CharterDigest digest = cell != null
                    ? CharterDigest.fromCell(cell, cand.sizeBand())
                    : CharterDigest.UNKNOWN;

            String name = settlementName(kingdom.getName(), cand.role(), issued + 1);

            // Re-role SettlementCharter: cell + role + size band, stage
            // CHARTERED, realizedId empty. villageType label = role for now
            // (type is near-vestigial in V2; the realizer derives the roster
            // from terrain). Non-capital (capital handled by V3 promotion).
            kingdom.issueSettlementCharter(name, cand.cellKey(), cand.role(),
                    cand.role(), cand.sizeBand(), false, digest, tick);
            chartedCells.add(cand.cellKey());
            issued++;
        }

        progress.accept("ClaimVillageEnumerator: issued " + issued
                + " owned-village charter(s) for '" + kingdom.getName()
                + "' (evaluated " + evaluated + " placement regions over "
                + claimedCells.size() + " claimed cells; " + ownership
                + " candidate(s) landed in claim). Reads load-free digests only "
                + "-- no scan, no atlas-fill forcing, no V2.");
        return issued;
    }

    /**
     * The distinct placement regions touched by any claimed cell. A claim cell
     * spans 64 blocks; we map each claimed cell's block bounds to the
     * placement region(s) it overlaps. Packed with {@link AtlasCell#packKey}
     * (region coords reuse the same packing -- distinct namespace, no clash
     * since we only use the set locally).
     */
    static Set<Long> regionsOverlappingClaim(Set<Long> claimedCells) {
        Set<Long> regions = new LinkedHashSet<>();
        for (long cellKey : claimedCells) {
            int cx = AtlasCell.unpackX(cellKey);
            int cz = AtlasCell.unpackZ(cellKey);
            int minBX = cx << AtlasCell.CELL_SHIFT;
            int minBZ = cz << AtlasCell.CELL_SHIFT;
            int maxBX = minBX + AtlasCell.CELL_SIZE - 1;
            int maxBZ = minBZ + AtlasCell.CELL_SIZE - 1;
            int rMinX = VillagePlacement.regionForBlockX(minBX);
            int rMaxX = VillagePlacement.regionForBlockX(maxBX);
            int rMinZ = VillagePlacement.regionForBlockZ(minBZ);
            int rMaxZ = VillagePlacement.regionForBlockZ(maxBZ);
            for (int rx = rMinX; rx <= rMaxX; rx++) {
                for (int rz = rMinZ; rz <= rMaxZ; rz++) {
                    regions.add(AtlasCell.packKey(rx, rz));
                }
            }
        }
        return regions;
    }

    /**
     * Reads an already-present atlas cell from the persisted {@link WorldAtlas}
     * WITHOUT forcing a fill. Returns null if absent -- the caller falls back
     * to {@link CharterDigest#UNKNOWN}, so this never blocks on a fill.
     */
    private static AtlasCell readCellIfPresent(
            net.minecraft.server.level.ServerLevel level, long cellKey) {
        WorldAtlas atlas = WorldAtlas.get(level);
        return atlas.getCellByCoord(
                AtlasCell.unpackX(cellKey), AtlasCell.unpackZ(cellKey));
    }

    /** Deterministic settlement name: "<KingdomPrefix> <Role> <n>". */
    static String settlementName(String kingdomName, String role, int ordinal) {
        String prefix = firstWord(kingdomName);
        String titleRole = toTitleCase(role.replace('_', ' '));
        return prefix + " " + titleRole + " " + ordinal;
    }

    private static String firstWord(String name) {
        if (name == null || name.isEmpty()) return "New";
        for (int i = 1; i < name.length(); i++) {
            if (Character.isUpperCase(name.charAt(i))) return name.substring(0, i);
        }
        return name;
    }

    private static String toTitleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
