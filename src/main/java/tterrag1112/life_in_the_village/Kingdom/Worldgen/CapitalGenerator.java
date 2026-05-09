package tterrag1112.life_in_the_village.Kingdom.Worldgen;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureBundles;
import tterrag1112.life_in_the_village.Cultures.CultureRegistry;
import tterrag1112.life_in_the_village.Kingdom.Heraldry;
import tterrag1112.life_in_the_village.Kingdom.HeraldryGenerator;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomClaim;
import tterrag1112.life_in_the_village.Kingdom.KingdomClaimComputer;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus;
import tterrag1112.life_in_the_village.Kingdom.Placement.ClaimVillagePlacer;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Lore.KingdomHistoryData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Planning.VillagePlanHelper;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Track D3.1 — kingdom worldgen entry point. Replaces the legacy
 * {@code KingdomSpawner.planComposed} multi-village flow with a
 * capital-only generator: at world founding each kingdom owns
 * exactly one village (the capital). Multi-village kingdoms grow
 * later via the vassalage / village-joining mechanisms in D3 phases
 * 2+ (which D3.1 doesn't ship).
 *
 * <h3>Inputs</h3>
 * <ul>
 *   <li>{@code level} — overworld server level.</li>
 *   <li>{@code origin} — pre-selected anchor block. The
 *       {@code WorldgenKingdomSeeder} resolves this via
 *       {@code AtlasSiteSelector.findBest} after spacing checks.</li>
 *   <li>{@code kingdomName} — kingdom name (worldgen-generated).</li>
 *   <li>{@code culture} — culture id (e.g. {@code "highmarch"}).</li>
 *   <li>{@code capitalVillageType} — village-type registry id used
 *       for the capital's V2 layout. Falls back to a culture-default
 *       if the requested type is missing.</li>
 *   <li>{@code progress} — info-level reporter. Output is mirrored
 *       to {@code System.out} by callers; the seeder collects these
 *       lines for its log.</li>
 * </ul>
 *
 * <h3>Outputs</h3>
 * Returns the new {@link Kingdom} record on success. Side effects:
 * <ul>
 *   <li>{@link VillageSavedData} gains a Kingdom + a planned Village.</li>
 *   <li>The Village's {@code kingdomId} is set to the new kingdom.</li>
 *   <li>{@link KingdomClaim} is computed with the culture-keyed
 *       {@code claimBudgetHint} and stamped on the kingdom.</li>
 *   <li>{@link Heraldry} is regenerated from
 *       {@code (culture, kingdomId, foundingTick)} so the founding
 *       tick contributes to determinism.</li>
 *   <li>{@link KingdomEventBus} fires {@link KingdomEvent.KingdomFounded}.</li>
 * </ul>
 *
 * <h3>Office staffing</h3>
 * Office staffing (founding ruler, culture-required offices) is
 * deferred to {@code KingdomOfficeBootstrapTickSystem}, which runs
 * post-realisation when capital NPCs exist. CapitalGenerator only
 * plans the village; realisation happens later via
 * {@code VillageRealisationSystem} when a player loads chunks near
 * the capital.
 */
public final class CapitalGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CapitalGenerator() {}

    /**
     * Generates a kingdom with a capital village at {@code origin}.
     * Mirrors the old {@code KingdomSpawner.planComposed} contract
     * but with capital-only state (composition trimmed to size 1).
     */
    public static Optional<Kingdom> generate(ServerLevel level,
                                              BlockPos origin,
                                              String kingdomName,
                                              String culture,
                                              String capitalVillageType,
                                              Consumer<String> progress) {
        VillageSavedData data = VillageSavedData.get(level);

        if (data.getKingdomByName(kingdomName).isPresent()) {
            progress.accept("Kingdom '" + kingdomName + "' already exists.");
            return Optional.empty();
        }

        Culture cultureRecord = CultureRegistry.getOrDefault(culture);
        CultureBundles.CultureKingdomDefaults kd = cultureRecord.kingdomDefaults();

        progress.accept("Founding capital-tier kingdom '" + kingdomName
                + "' (" + culture + ") at " + origin.toShortString());

        // ── Kingdom record ──────────────────────────────────────────────
        Kingdom kingdom = new Kingdom(kingdomName, culture);
        long foundingTick = level.getGameTime();
        kingdom.setFoundingTick(foundingTick);
        // Regenerate heraldry now that foundingTick is set — the
        // constructor used seed=0L; this re-roll honours the founding
        // tick so seed determinism (worldSeed → kingdomIndex →
        // foundingTick) propagates into heraldry.
        kingdom.setHeraldry(HeraldryGenerator.generate(
                cultureRecord, kingdom.getId(), foundingTick));
        data.addKingdom(kingdom);
        kingdom.getHistory().recordEvent(
                HistoryTextGenerator.kingdomFounded(kingdomName,
                        "world-gen", foundingTick),
                kingdomName);
        kingdom.getHistory().setOrigin(
                new KingdomHistoryData.KingdomOriginData(
                        KingdomHistoryData.KingdomOrigins.FOUNDED_BY_PLAYER,
                        "world-gen", new UUID(0, 0),
                        "the wilderness", foundingTick, 0, ""));

        // ── Atlas preparation ───────────────────────────────────────────
        WorldAtlas atlas = WorldAtlas.get(level);
        int fillRadius = 2500;
        progress.accept("Filling atlas around capital (" + fillRadius + " blocks)...");
        atlas.ensureRegionFilled(level, origin.getX(), origin.getZ(),
                fillRadius, 80_000_000L);
        atlas.ensureRegionsIndexed(
                WorldAtlas.blockToCell(origin.getX()),
                WorldAtlas.blockToCell(origin.getZ()),
                (fillRadius >> tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_SHIFT) + 2);

        // ── Compute claim with culture-keyed budget ─────────────────────
        KingdomClaim territorialClaim = KingdomClaimComputer.compute(
                atlas, origin, kd.claimBudgetHint());
        kingdom.setTerritorialClaim(territorialClaim);
        progress.accept("Claimed " + territorialClaim.size() + " cells (home="
                + territorialClaim.homeCategoryName()
                + ", budget=" + kd.claimBudgetHint()
                + ", culture-resistance=" + kd.claimResistance() + ")");

        // ── Plan capital village ────────────────────────────────────────
        // Capital placement uses the same ClaimVillagePlacer that
        // legacy planComposed used. Composition is a single entry —
        // the capital itself.
        List<BlockPos> existingVillagePositions = data.getAllVillages().stream()
                .map(Village::getAnchorPos)
                .filter(java.util.Objects::nonNull)
                .toList();
        WorldRoadGraph roadGraph =
                WorldRoadSavedData.get(level).getGraph();

        List<ClaimVillagePlacer.PlacementResult> placements =
                ClaimVillagePlacer.plan(atlas, territorialClaim, origin,
                        List.of(capitalVillageType),
                        existingVillagePositions, roadGraph);

        if (placements.isEmpty() || !placements.get(0).placed()) {
            progress.accept("Capital placement failed — aborting '"
                    + kingdomName + "'");
            data.removeKingdom(kingdom.getId());
            data.setDirty();
            return Optional.empty();
        }

        ClaimVillagePlacer.PlacementResult capital = placements.get(0);
        BlockPos capitalPos = capital.position();

        // Don't clash with another kingdom's village placed earlier.
        boolean clashes = data.getAllVillages().stream()
                .anyMatch(v -> {
                    BlockPos a = v.getAnchorPos();
                    return a != null && a.distSqr(capitalPos) < 100L * 100L;
                });
        if (clashes) {
            progress.accept("Capital site clashes with an existing village — aborting '"
                    + kingdomName + "'");
            data.removeKingdom(kingdom.getId());
            data.setDirty();
            return Optional.empty();
        }

        String capitalName = kingdomName;  // capital takes the kingdom's name
        progress.accept("  Planning capital " + capitalName + " ("
                + capital.villageType()
                + (capital.relaxed() ? ", relaxed tag match" : "")
                + ") at " + capitalPos.toShortString()
                + " score=" + String.format("%.2f", capital.score()));

        Village planned = VillagePlanHelper.planVillage(level, data,
                capitalPos, capital.villageType(), capitalName);

        // ── Wire kingdom ↔ capital relationship ─────────────────────────
        kingdom.addVillage(planned.getId());
        kingdom.setCapitalVillageId(planned.getId());
        planned.setKingdomId(kingdom.getId());
        data.setDirty();

        progress.accept("Founded '" + kingdomName + "' with capital '"
                + capitalName + "' at " + capitalPos.toShortString()
                + ". Heraldry: " + kingdom.getHeraldry().describe());

        // ── Fire the bus ────────────────────────────────────────────────
        // Office events fire later, post-realisation, from
        // KingdomOfficeBootstrapTickSystem. KingdomFounded fires now
        // because the kingdom record exists and has its identity.
        KingdomEventBus.fire(new KingdomEvent.KingdomFounded(
                kingdom.getId(), culture, foundingTick));

        return Optional.of(kingdom);
    }
}
