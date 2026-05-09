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

    /**
     * Drives the migration. Safe to call on every level load.
     *
     * <p>Two independent gates: the D1 membership migration (back-fills
     * {@code Village.kingdomId} from {@code Kingdom.villageIds}) and
     * the D3.1 capital migration (stamps {@code Kingdom.capitalVillageId}).
     * Either runs only when its respective flag is false. Pre-D1 saves
     * arrive with both false; post-D1 / pre-D3.1 saves arrive with the
     * D1 flag true and the D3.1 flag false.
     */
    public static void migrateIfNeeded(ServerLevel level) {
        VillageSavedData vdata = VillageSavedData.get(level);

        boolean needMembership = !vdata.isKingdomMembershipMigrated();
        boolean needCapitals   = !vdata.isKingdomCapitalMigrated();
        if (!needMembership && !needCapitals) return;

        int kingdoms = 0;
        int stamped  = 0;
        int collisions = 0;
        int heraldryBackFilled = 0;
        int capitalsStamped = 0;

        for (Kingdom k : vdata.getAllKingdoms()) {
            kingdoms++;

            // Heraldry back-fill: only on D1 path. Once D1 has run,
            // heraldry is already non-UNKNOWN.
            if (needMembership && Heraldry.UNKNOWN.equals(k.getHeraldry())) {
                k.setHeraldry(HeraldryGenerator.generate(
                        tterrag1112.life_in_the_village.Cultures.CultureRegistry
                                .getOrDefault(k.getCulture()),
                        k.getId(), 0L));
                heraldryBackFilled++;
            }

            // Track D3.1 — capital back-fill. Independent of the D1
            // path because pre-D3.1 saves may already have D1 done
            // but no capitalVillageId field. The first villageId in
            // the legacy list is implicitly the capital; stamp it
            // explicitly.
            if (needCapitals && k.getCapitalVillageId().isEmpty()
                    && !k.getVillageIds().isEmpty()) {
                k.setCapitalVillageId(k.getVillageIds().get(0));
                capitalsStamped++;
            }

            // Below: D1 membership stamping. Skip when only the D3.1
            // capital path is needed (the village.kingdomId reverse
            // pointer is already set from D1's earlier run).
            if (!needMembership) continue;

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

        if (needMembership) vdata.setKingdomMembershipMigrated(true);
        if (needCapitals)   vdata.setKingdomCapitalMigrated(true);
        vdata.markDirty();

        LOGGER.info(
                "[KingdomMembershipMigration] complete — kingdoms={} stamped={} "
                        + "collisions={} heraldryBackFilled={} capitalsStamped={}",
                kingdoms, stamped, collisions, heraldryBackFilled, capitalsStamped);
    }
}
