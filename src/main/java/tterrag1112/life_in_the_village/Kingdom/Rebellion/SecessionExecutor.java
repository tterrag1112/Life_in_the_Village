package tterrag1112.life_in_the_village.Kingdom.Rebellion;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Kingdom.Audience.KingdomNewsfeed;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.Provinces.Province;
import tterrag1112.life_in_the_village.Kingdom.HeraldryGenerator;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Track D3.6.1 — province secession executor.
 *
 * <p>Transforms a province into an independent kingdom:
 * <ol>
 *   <li>Create a fresh {@link Kingdom} record with the province
 *       name, inheriting the province's villages.</li>
 *   <li>Set the governor as the new kingdom's ruler with
 *       legitimacy {@code usurped} (low — 35) per D3.2's path-set
 *       legitimacy.</li>
 *   <li>Re-parent member villages
 *       ({@link Village#setKingdomId}).</li>
 *   <li>Remove villages from the parent kingdom's villageId list.</li>
 *   <li>Remove the province from the parent kingdom's province list
 *       (claim will recompute on next weekly tick via D3.3's
 *       invalidation pattern — reset
 *       {@code lastProvinceRecomputeTick = -1L}).</li>
 *   <li>Generate fresh heraldry for the new kingdom seeded by its
 *       UUID + culture.</li>
 *   <li>Apply legitimacy/stability hit on the parent kingdom.</li>
 *   <li>Fire {@link KingdomEvent.Secession} +
 *       {@code rebellion.seceded} newsfeed entry on both sides.</li>
 * </ol>
 */
public final class SecessionExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Legitimacy for the new ruler — "usurped" path per D3.2. */
    public static final int USURPED_LEGITIMACY = 35;

    /** Stability hit applied to the parent kingdom on secession. */
    public static final int PARENT_STABILITY_HIT = -10;

    /** Legitimacy hit applied to the parent kingdom's ruler. */
    public static final int PARENT_LEGITIMACY_HIT = -15;

    private SecessionExecutor() {}

    public static UUID secede(ServerLevel level, VillageSavedData data,
                              Kingdom parent, Province province, long tick,
                              String reason) {
        if (parent == null || province == null) return null;

        // 1. New kingdom record.
        String newName = province.name();
        // Per the locked design: "New kingdom inherits the seceding
        // province's culture if it differs from the parent
        // kingdom's; otherwise inherits parent culture." In v1
        // provinces don't carry their own culture field, so we
        // inherit parent culture and document for future
        // per-province culture support.
        String culture = parent.getCulture();
        Kingdom successor = new Kingdom(UUID.randomUUID(), newName, culture);

        // 2. Fresh heraldry — seeded by new kingdom UUID. The
        //    HeraldryGenerator.generate path already seeds from
        //    (culture, id, foundingTick) deterministically.
        successor.setHeraldry(HeraldryGenerator.generate(
                CultureResolver.ofKingdom(successor), successor.getId(), tick));

        // 3. Governor becomes ruler with usurped legitimacy.
        province.governorUuid().ifPresent(successor::setRulerEntityId);
        successor.setLegitimacy(USURPED_LEGITIMACY);
        // Province's existing stability baseline carries forward
        // implicitly through the per-province record; kingdom-tier
        // stability seeded modestly.
        successor.setStability(Math.max(50, province.stability() + 20));
        successor.setFoundingTick(tick);

        // 4. Transfer villages.
        List<UUID> transferred = new ArrayList<>(province.villageIds());
        for (UUID villageId : transferred) {
            data.getVillageById(villageId).ifPresent(v ->
                    v.setKingdomId(successor.getId()));
            parent.removeVillage(villageId);
            successor.addVillage(villageId);
        }

        // 5. Pick a capital — first village in the province, if any.
        if (!transferred.isEmpty()) {
            successor.setCapitalVillageId(transferred.get(0));
        }

        // 6. Detach the province from the parent. Province IDs are
        //    province-record-scoped; the recompute pass will
        //    rebuild the parent's province polygons.
        parent.getProvinces().removeIf(p -> p.id().equals(province.id()));
        parent.setLastProvinceRecomputeTick(-1L); // force recompute next weekly tick

        // 7. Apply parent malus.
        parent.applyStabilityDelta(PARENT_STABILITY_HIT);
        parent.applyLegitimacyDelta(PARENT_LEGITIMACY_HIT);

        // 8. Register the new kingdom.
        data.addKingdom(successor);
        data.setDirty();

        // 9. Events + newsfeed on both sides.
        UUID governorUuid = province.governorUuid().orElse(null);
        KingdomEventBus.fire(new KingdomEvent.Secession(
                parent.getId(), successor.getId(), province.id(),
                governorUuid, reason, tick));
        KingdomNewsfeed.append(parent, "rebellion.seceded",
                province.name() + " has seceded from " + parent.getName(), tick);
        KingdomNewsfeed.append(successor, "kingdom.founded",
                "Independent kingdom of " + newName
                        + " founded after seceding from " + parent.getName(), tick);

        LOGGER.info("[SecessionExecutor] {} seceded from {} as new kingdom {} (reason: {})",
                province.name(), parent.getName(), successor.getId(), reason);
        return successor.getId();
    }
}
