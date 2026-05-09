package tterrag1112.life_in_the_village.Kingdom;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * Track D1 (Phase 0) — one-shot, idempotent migration that
 * back-fills {@link Village#getKingdomId()} from the legacy
 * {@link Kingdom#getVillageIds()} list.
 *
 * <h3>Idempotency</h3>
 * Gated by {@link VillageSavedData#isKingdomMembershipMigrated()}.
 * The first server tick after Phase D1 ships flips this flag.
 * Subsequent loads short-circuit. Fresh worlds initialise the flag
 * {@code true} so the migration never runs there.
 *
 * <h3>What it does</h3>
 * For each {@link Kingdom} in saved data, walk its
 * {@code villageIds}. For each member village, set
 * {@link Village#setKingdomId(java.util.UUID)} to the kingdom's id.
 * Existing kingdom code keeps reading {@code villageIds}; D2/D3
 * callers gain a O(1) reverse pointer via {@code Village.kingdomId}.
 *
 * <h3>Conflict semantics</h3>
 * If a village somehow appears in two kingdoms' lists (data
 * corruption from older code paths), the LAST kingdom processed
 * wins on the {@code kingdomId} pointer. The legacy lists stay
 * untouched, so D2's reconciliation pass can detect and resolve
 * the conflict later. The migration logs a WARN per duplicate.
 *
 * <h3>Heraldry back-fill</h3>
 * As a side-effect, kingdoms whose persisted heraldry is
 * {@link Heraldry#UNKNOWN} (i.e. saved before Phase D1 added the
 * field) get a fresh deterministic heraldry generated from
 * {@code (culture, kingdomId, foundingSeed=0L)}. This is purely
 * a display back-fill; no behaviour change.
 */
public final class KingdomMembershipMigration {

    private static final Logger LOGGER = LogUtils.getLogger();

    private KingdomMembershipMigration() {}

    /** Drives the migration. Safe to call on every level load. */
    public static void migrateIfNeeded(ServerLevel level) {
        VillageSavedData vdata = VillageSavedData.get(level);
        if (vdata.isKingdomMembershipMigrated()) return;

        int kingdoms = 0;
        int stamped  = 0;
        int collisions = 0;
        int heraldryBackFilled = 0;

        for (Kingdom k : vdata.getAllKingdoms()) {
            kingdoms++;

            // Heraldry back-fill: if the kingdom loaded with the
            // sentinel UNKNOWN, generate a real heraldry from its
            // culture + UUID. The Kingdom constructor already does
            // this for fresh kingdoms; this branch covers
            // codec-loaded pre-Phase-0 kingdoms whose stored
            // heraldry is the optionalFieldOf default.
            if (Heraldry.UNKNOWN.equals(k.getHeraldry())) {
                k.setHeraldry(HeraldryGenerator.generate(
                        tterrag1112.life_in_the_village.Cultures.CultureRegistry
                                .getOrDefault(k.getCulture()),
                        k.getId(), 0L));
                heraldryBackFilled++;
            }

            for (java.util.UUID vid : k.getVillageIds()) {
                Village v = vdata.getVillageById(vid).orElse(null);
                if (v == null) {
                    LOGGER.warn(
                            "[KingdomMembershipMigration] kingdom {} ({}) lists "
                                    + "missing village {} — skipping",
                            k.getName(), k.getId(), vid);
                    continue;
                }
                java.util.UUID existing = v.getKingdomId().orElse(null);
                if (existing != null && !existing.equals(k.getId())) {
                    LOGGER.warn(
                            "[KingdomMembershipMigration] village {} appears in "
                                    + "two kingdoms ({} and {}); LAST wins, "
                                    + "earlier listing left dangling on legacy list.",
                            v.getId(), existing, k.getId());
                    collisions++;
                }
                v.setKingdomId(k.getId());
                stamped++;
            }
        }

        vdata.setKingdomMembershipMigrated(true);
        vdata.markDirty();

        LOGGER.info(
                "[KingdomMembershipMigration] complete — kingdoms={} stamped={} "
                        + "collisions={} heraldryBackFilled={}",
                kingdoms, stamped, collisions, heraldryBackFilled);
    }
}
