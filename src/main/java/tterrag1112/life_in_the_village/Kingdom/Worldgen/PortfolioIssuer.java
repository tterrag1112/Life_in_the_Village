package tterrag1112.life_in_the_village.Kingdom.Worldgen;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomClaim;
import tterrag1112.life_in_the_village.Kingdom.Settlement.CharterDigest;
import tterrag1112.life_in_the_village.Kingdom.Settlement.SettlementCharter;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;
import java.util.function.Consumer;

/**
 * Track C1-d — portfolio issuance for a newly born kingdom.
 *
 * <p>After the capital {@link SettlementCharter} is issued by
 * {@link CapitalGenerator}, this class walks the kingdom's claimed cells
 * and issues additional settlement charters, one per selected cell, driven
 * entirely by the now-live {@code biomeAffinity}, {@code kingdomRoles},
 * {@code maxPerKingdom}, and {@code tradePriority} fields on
 * {@link VillageTypeData}.
 *
 * <h3>Selection algorithm</h3>
 * <ol>
 *   <li>Collect candidate cells from {@link KingdomClaim#claimedCellKeys()}.
 *       Skip the capital cell and any cell already charted. Shuffle
 *       deterministically (seeded from kingdom id) so the sample isn't
 *       always the same compass direction.</li>
 *   <li>Walk candidates up to {@link #MAX_PORTFOLIO_CHARTERS} slots.
 *       Enforce one charter per cell (tracked by a local set).</li>
 *   <li>For each candidate cell read its {@link AtlasCell} from the already-
 *       filled atlas. If the cell is missing, skip it (atlas fill happened
 *       in {@link CapitalGenerator}; coverage should be good).</li>
 *   <li>Score each registered village type by biome affinity against the
 *       cell (see {@link #affinityScore}). Non-capital types only (capital
 *       is already issued). Exclude types whose {@code maxPerKingdom} cap is
 *       already met. Among matched types pick the one with the highest
 *       {@code tradePriority} (ties broken by registry iteration order, which
 *       is stable for a given data pack). If no affinity type matches, fall
 *       to the best generic type (empty {@code biomeAffinity}).</li>
 *   <li>Issue the charter; track the per-type count for cap enforcement.</li>
 * </ol>
 *
 * <h3>Constants</h3>
 * <ul>
 *   <li>{@link #MAX_PORTFOLIO_CHARTERS} — max non-capital charters issued per
 *       kingdom. Chosen so a 2-kingdom test world gets a sane spread (capital
 *       + 4 others = 5 pins on the map). Adjust here; no other site cares.</li>
 *   <li>{@link #CANDIDATE_STRIDE} — step interval through the shuffled claim
 *       list when sampling. A claim of 60–120 cells with stride 2 gives 30–60
 *       candidate evaluations — fast and covers the territory.</li>
 * </ul>
 *
 * <h3>Downstream unchanged</h3>
 * Issued charters are CHARTERED. {@code CharterSurveyTickSystem} (C1-b) picks
 * them up automatically. {@code CharterRealizationTickSystem} (C1-c) realizes
 * them on player approach. No survey or realization code is touched here.
 */
public final class PortfolioIssuer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Maximum number of non-capital settlement charters issued per kingdom at
     * worldgen. Capital + this many = total pins on the map.
     * Named constant — the only place to tune it.
     */
    public static final int MAX_PORTFOLIO_CHARTERS = 4;

    /**
     * Stride when stepping through the shuffled claimed-cell list.
     * Keeps the inner loop bounded even if a kingdom claims hundreds of cells.
     */
    private static final int CANDIDATE_STRIDE = 2;

    private PortfolioIssuer() {}

    /**
     * Issues up to {@link #MAX_PORTFOLIO_CHARTERS} non-capital settlement
     * charters for {@code kingdom}, driven by the dead portfolio fields now
     * wired for the first time. The capital charter must already be present
     * on the kingdom (issued by {@link CapitalGenerator}).
     *
     * @param kingdom   the newly born kingdom; its claim must be set.
     * @param atlas     the already-filled {@link WorldAtlas}.
     * @param rng       seeded RNG (caller provides — typically seeded from
     *                  kingdom id so the portfolio is deterministic per world).
     * @param tick      the current game tick (for {@code issuedTick}).
     * @param progress  info-level reporter mirrored to console.
     */
    public static void issue(Kingdom kingdom,
                             WorldAtlas atlas,
                             Random rng,
                             long tick,
                             Consumer<String> progress) {
        KingdomClaim claim = kingdom.getTerritorialClaim().orElse(null);
        if (claim == null || claim.claimedCellKeys().isEmpty()) {
            progress.accept("PortfolioIssuer: no claim — skipping portfolio.");
            return;
        }

        // Collect all registered non-capital-flagged village types.
        // (capital types are already issued by CapitalGenerator; portfolio
        //  types are everything else that has a role or affinity to offer.)
        Map<String, VillageTypeData> allTypes = loadNonCapitalTypes();
        if (allTypes.isEmpty()) {
            progress.accept("PortfolioIssuer: no non-capital village types available — skipping.");
            return;
        }

        // Track per-type issue counts for maxPerKingdom enforcement.
        Map<String, Integer> issuedCount = new HashMap<>();

        // Track cells already charted (capital cell + any we issue) so we
        // never put two charters in the same cell.
        Set<Long> chartedCells = new HashSet<>();
        for (SettlementCharter c : kingdom.getSettlementCharters()) {
            chartedCells.add(c.targetCellKey());
        }

        // Shuffle a copy of the claimed cells so the sample isn't directionally biased.
        List<Long> candidates = new ArrayList<>(claim.claimedCellKeys());
        Collections.shuffle(candidates, rng);

        int issued = 0;
        int evaluated = 0;

        outer:
        for (int i = 0; i < candidates.size() && issued < MAX_PORTFOLIO_CHARTERS;
             i += CANDIDATE_STRIDE) {
            long cellKey = candidates.get(i);

            // Skip already-charted cells.
            if (chartedCells.contains(cellKey)) continue;

            int cx = AtlasCell.unpackX(cellKey);
            int cz = AtlasCell.unpackZ(cellKey);
            AtlasCell cell = atlas.getCellByCoord(cx, cz);
            evaluated++;

            // Skip unbuildable or unsampled cells.
            if (cell == null || !cell.isBuildable()) continue;

            // Pick the best village type for this cell.
            VillageTypeData chosen = selectType(cell, allTypes, issuedCount);
            if (chosen == null) continue; // all types capped

            // Determine role: first kingdomRole declared, or a generic fallback.
            Set<String> roles = chosen.getKingdomRoles();
            String role = roles.isEmpty() ? "settlement" : roles.iterator().next();

            // Size band from SettlementTier → VillageSizeTier mapping.
            VillageSizeTier band = sizeBandFor(chosen);

            // Build the digest from the cell + band.
            CharterDigest digest = CharterDigest.fromCell(cell, band);

            // Generate a settlement name: role + ordinal within kingdom.
            String name = generateName(kingdom.getName(), role, issued + 1, rng);

            kingdom.issueSettlementCharter(name, cellKey, role,
                    chosen.getType(), band, false, digest, tick);

            chartedCells.add(cellKey);
            issuedCount.merge(chosen.getType(), 1, Integer::sum);
            issued++;

            progress.accept("  Chartered '" + name + "' (" + chosen.getType()
                    + ", role=" + role + ") in cell " + cx + "," + cz
                    + " (biome=" + cell.category()
                    + (cell.isCoast() ? "/coast" : "")
                    + (cell.isFreshwater() ? "/river" : "")
                    + (cell.isSteep() ? "/steep" : "")
                    + ")");
        }

        progress.accept("PortfolioIssuer: issued " + issued + " portfolio charter(s) for '"
                + kingdom.getName() + "' (evaluated " + evaluated + " of "
                + candidates.size() + " claimed cells, stride=" + CANDIDATE_STRIDE + ").");
    }

    // =========================================================================
    // Type selection
    // =========================================================================

    /**
     * Returns the best non-capital village type for {@code cell}, or
     * {@code null} if all eligible types are capped.
     *
     * <p>Priority: highest {@code tradePriority} among types whose
     * {@code biomeAffinity} matches the cell. If none match, fall to the
     * highest-priority generic type (empty affinity set). Ties broken by
     * iteration order (stable for a given data pack load).
     */
    static VillageTypeData selectType(AtlasCell cell,
                                      Map<String, VillageTypeData> types,
                                      Map<String, Integer> issuedCount) {
        VillageTypeData bestAffinity = null;
        VillageTypeData bestGeneric  = null;

        for (VillageTypeData t : types.values()) {
            if (isCapped(t, issuedCount)) continue;

            Set<String> affinity = t.getBiomeAffinity();
            if (affinity.isEmpty()) {
                // Generic type — no affinity declared.
                if (bestGeneric == null
                        || t.getTradePriority() > bestGeneric.getTradePriority()) {
                    bestGeneric = t;
                }
            } else if (matchesAffinity(cell, affinity)) {
                if (bestAffinity == null
                        || t.getTradePriority() > bestAffinity.getTradePriority()) {
                    bestAffinity = t;
                }
            }
        }

        return bestAffinity != null ? bestAffinity : bestGeneric;
    }

    /**
     * Returns true if {@code cell}'s terrain matches any tag in
     * {@code affinity}.
     *
     * <p>Matching rules (all based on existing atlas digest — no new fields):
     * <ul>
     *   <li>{@code "mountain"} — cell category is {@link BiomeCategory#MOUNTAIN}
     *       OR cell is steep (slope &gt; threshold).</li>
     *   <li>{@code "forest"}  — cell category is {@link BiomeCategory#FOREST}.</li>
     *   <li>{@code "river"}   — cell category is {@link BiomeCategory#RIVER}
     *       OR cell has {@code FLAG_HAS_RIVER} / {@code FLAG_FRESHWATER}.</li>
     *   <li>{@code "coast"}   — cell has {@code FLAG_COAST} (adjacent to ocean)
     *       OR category is {@link BiomeCategory#BEACH}.</li>
     *   <li>{@code "desert"}  — cell category is {@link BiomeCategory#DESERT}.</li>
     *   <li>{@code "snowy"}   — cell category is {@link BiomeCategory#SNOWY}.</li>
     *   <li>{@code "swamp"}   — cell category is {@link BiomeCategory#SWAMP}.</li>
     *   <li>{@code "jungle"}  — cell category is {@link BiomeCategory#JUNGLE}.</li>
     * </ul>
     *
     * All matching is purely data-driven: the tags come from
     * {@link VillageTypeData#getBiomeAffinity()} as declared in datagen.
     * No hard-coded biome→type map exists here; new affinities added in
     * datagen automatically take effect.
     */
    static boolean matchesAffinity(AtlasCell cell, Set<String> affinity) {
        for (String tag : affinity) {
            if (matchesTag(cell, tag)) return true;
        }
        return false;
    }

    private static boolean matchesTag(AtlasCell cell, String tag) {
        return switch (tag) {
            case "mountain" -> cell.category() == BiomeCategory.MOUNTAIN || cell.isSteep();
            case "forest"   -> cell.category() == BiomeCategory.FOREST;
            case "river"    -> cell.category() == BiomeCategory.RIVER
                               || cell.isFreshwater();
            case "coast"    -> cell.isCoast()
                               || cell.category() == BiomeCategory.BEACH;
            case "desert"   -> cell.category() == BiomeCategory.DESERT;
            case "snowy"    -> cell.category() == BiomeCategory.SNOWY;
            case "swamp"    -> cell.category() == BiomeCategory.SWAMP;
            case "jungle"   -> cell.category() == BiomeCategory.JUNGLE;
            default         -> false; // unknown tag — never matches
        };
    }

    /** True if this type's {@code maxPerKingdom} cap is already met. */
    private static boolean isCapped(VillageTypeData t,
                                    Map<String, Integer> issuedCount) {
        int max = t.getMaxPerKingdom();
        if (max == -1) return false; // -1 = unbounded
        return issuedCount.getOrDefault(t.getType(), 0) >= max;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Loads all village types from the registry that are NOT capital-eligible
     * ({@code canBeCapital() == false}). Capital types are already handled by
     * {@link CapitalGenerator}; issuing them again as portfolio charters would
     * duplicate the intent.
     *
     * <p>Note: types with no {@code kingdomRoles} and no {@code biomeAffinity}
     * are still included — they serve as generic fallbacks for cells that don't
     * match any affinity type.
     */
    private static Map<String, VillageTypeData> loadNonCapitalTypes() {
        Map<String, VillageTypeData> result = new LinkedHashMap<>();
        for (String typeId : VillageTypeRegistry.INSTANCE.getAvailableTypes()) {
            VillageTypeData data = VillageTypeRegistry.INSTANCE.getType(typeId);
            if (data == null || data.canBeCapital()) continue;
            result.put(typeId, data);
        }
        return result;
    }

    /**
     * Maps a village type's {@link tterrag1112.life_in_the_village.Village.SettlementTier}
     * to a {@link VillageSizeTier} for the stage-0 charter estimate.
     * Realization refines to truth.
     */
    private static VillageSizeTier sizeBandFor(VillageTypeData t) {
        return switch (t.getSettlementTier()) {
            case CAPITAL -> VillageSizeTier.CITY;
            case CITY    -> VillageSizeTier.CITY;
            case TOWN    -> VillageSizeTier.TOWN;
            case VILLAGE -> VillageSizeTier.VILLAGE;
            case HAMLET,
                 OUTPOST -> VillageSizeTier.HAMLET;
        };
    }

    /**
     * Generates a deterministic settlement name for a portfolio charter.
     * Format: {@code "<KingdomPrefix> <Role> <n>"} where the kingdom prefix
     * is the first word of the kingdom name (or the full name if one word),
     * role is title-cased, and n is the ordinal within the kingdom's portfolio.
     * Simple and recognizable in-world; not themed per culture (C4 can enrich).
     */
    static String generateName(String kingdomName, String role, int ordinal, Random rng) {
        // Use the first word of the kingdom name as a prefix (e.g. "Ironrealm" → "Iron")
        // Falls back to the full name if it's a single word without obvious split points.
        String prefix = firstWord(kingdomName);
        String titleRole = toTitleCase(role.replace('_', ' '));
        return prefix + " " + titleRole + " " + ordinal;
    }

    private static String firstWord(String name) {
        if (name == null || name.isEmpty()) return "New";
        // Split on uppercase boundary (e.g. "IronRealm" → "Iron")
        // or on common suffix words from the seeder's name table.
        for (String suffix : SUFFIX_WORDS) {
            if (name.toLowerCase(java.util.Locale.ROOT).endsWith(suffix.toLowerCase(java.util.Locale.ROOT))
                    && name.length() > suffix.length()) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        // Camel-case split: find first uppercase→lowercase transition after pos 0
        for (int i = 1; i < name.length(); i++) {
            if (Character.isUpperCase(name.charAt(i))) {
                return name.substring(0, i);
            }
        }
        return name;
    }

    private static String toTitleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // Seeder suffix words to strip when deriving a kingdom prefix.
    private static final String[] SUFFIX_WORDS = {
        "realm","domain","lands","hold","march","shire","ward","keep","mark","reach",
        "clan","moor","vale","ridge","peak","croft","tor","brae","fen",
        "ium","ia","us","atis","ensis","orum","anum","icus","inus","um"
    };
}
