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
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Lore.KingdomHistoryData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

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
 *   <li>{@link VillageSavedData} gains a Kingdom (born regardless of
 *       capital-siting success — Track C1-a decoupling).</li>
 *   <li>A capital {@code SettlementCharter} is issued into the claim's
 *       origin cell. No block-siting happens here; survey (C1-b) and
 *       realization (C1-c) site the capital later, locally.</li>
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
 * issues the capital charter; survey (C1-b) and realization (C1-c)
 * site the capital later via the charter pipeline / the existing
 * {@code VillageRealisationSystem} when a player loads chunks near
 * the capital cell.
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

        // ── Claim overlay + enumeration (Track V2) ─────────────────────
        // Kingdom birth is DECOUPLED from capital block-siting. We enumerate
        // the deterministic V1 VillagePlacement candidates whose landing cell
        // falls inside this kingdom's claim and persist each as an owned
        // settlement charter (re-roled SettlementCharter, stage CHARTERED).
        // This SUPERSEDES the C1-a separate capital-charter issuance AND the
        // C1-d PortfolioIssuer scan: placement is the deterministic V1 spread
        // (doc 16), not a per-cell affinity walk and not a single hand-sited
        // capital cell. Reads load-free digests only -- no column scan, no
        // atlas-fill forcing, no V2 call (the cost-tier contract). The kingdom
        // can no longer die at worldgen because a capital couldn't site (the
        // old removeKingdom path is gone). Village -> kingdom is derived from
        // the cell-claim (VillageSavedData.getKingdomForCell); culture is
        // derived from the kingdom.
        int owned = ClaimVillageEnumerator.enumerate(
                kingdom, level, foundingTick, progress);
        data.setDirty();

        // ── Capital promotion (Track V3) ───────────────────────────────
        // The capital is NOT sited separately: promote the best-suited owned
        // village (nearest the claim origin, best digest) to capital -- a flag
        // on its existing charter, no new siting. This permanently removes the
        // capital-placement-failure class. If a sparse claim produced no owned
        // village, CapitalPromoter issues ONE fallback charter at the origin
        // cell so the kingdom always has a capital (never a hard failure).
        String capitalName = kingdomName;  // capital takes the kingdom's name
        var capitalCharter = CapitalPromoter.promote(
                kingdom, level, capitalName, capitalVillageType,
                foundingTick, progress);
        data.setDirty();

        progress.accept("Founded '" + kingdomName + "' with " + owned
                + " owned village(s); capital '" + capitalName
                + "' (cell centre " + capitalCharter.targetCellCentre().toShortString()
                + ", stage=" + capitalCharter.stage()
                + "). Realization happens later (V4) when a player loads nearby. "
                + "Heraldry: " + kingdom.getHeraldry().describe());

        // ── Fire the bus ────────────────────────────────────────────────
        // Office events fire later, post-realisation, from
        // KingdomOfficeBootstrapTickSystem. KingdomFounded fires now
        // because the kingdom record exists and has its identity.
        KingdomEventBus.fire(new KingdomEvent.KingdomFounded(
                kingdom.getId(), culture, foundingTick));

        return Optional.of(kingdom);
    }
}
