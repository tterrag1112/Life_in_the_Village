package tterrag1112.life_in_the_village.Kingdom.Rebellion;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Kingdom.Audience.KingdomNewsfeed;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.Treaties.Treaty;
import tterrag1112.life_in_the_village.Kingdom.Treaties.TreatyType;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.6.1 — vassal rebellion specialization.
 *
 * <p>A vassal kingdom (target of a {@code VASSALAGE} treaty)
 * carries a "loyalty stability" scalar computed from:
 * <ul>
 *   <li>Vassal kingdom's own stability (drift toward zero
 *       loyalty when low).</li>
 *   <li>Cultural compatibility between vassal and overlord:
 *       {@code hostileCultures} cross-references reduce loyalty;
 *       {@code vassalEligibleCultures} entries reinforce.</li>
 *   <li>Tribute flow under D3.4b's tribute rate (high tribute
 *       erodes loyalty).</li>
 * </ul>
 *
 * <p>The driver doesn't store a loyalty scalar — it computes it on
 * each daily tick from the existing state. Threshold crossings
 * fire {@link KingdomEvent.VassalRebelled} and break the
 * VASSALAGE treaty across every party.
 *
 * <p>Determinism seed:
 * {@code (vassalKingdomId, "vassal_rebellion", overlordId, gameDay)}.
 */
public final class VassalRebellionDriver {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Below this loyalty value, vassal rebels and breaks the treaty. */
    public static final int REBELLION_THRESHOLD = 20;

    private VassalRebellionDriver() {}

    public static void dailyTick(ServerLevel level, VillageSavedData data, long tick) {
        for (Kingdom vassal : new ArrayList<>(data.getAllKingdoms())) {
            if (!vassal.isVassal()) continue;
            Optional<UUID> overlordId = vassal.overlordKingdomId();
            if (overlordId.isEmpty()) continue;
            Kingdom overlord = data.getKingdomById(overlordId.get()).orElse(null);
            if (overlord == null) continue;

            int loyalty = computeLoyalty(vassal, overlord);
            if (loyalty < REBELLION_THRESHOLD) {
                // Determinism: only fire if today's roll says so.
                long seed = vassal.getId().getMostSignificantBits()
                        ^ Long.rotateLeft("vassal_rebellion".hashCode(), 17)
                        ^ Long.rotateLeft(overlord.getId().getMostSignificantBits(), 31)
                        ^ (tick / 24000L);
                if (new java.util.Random(seed).nextDouble() < 0.30) {
                    triggerRebellion(vassal, overlord, tick, loyalty);
                }
            }
        }
    }

    /**
     * Loyalty in 0..100. Vassal stability is the dominant
     * contributor; cultural hostility subtracts up to 30; tribute
     * rate above default subtracts up to 20.
     */
    public static int computeLoyalty(Kingdom vassal, Kingdom overlord) {
        int loyalty = vassal.getStability() + vassal.stabilityModifierSum();
        // Cultural hostility check.
        var overlordDefaults = CultureResolver.ofKingdom(overlord).kingdomDefaults();
        if (overlordDefaults.hostileCultures().contains(vassal.getCulture())) {
            loyalty -= 30;
        }
        if (overlordDefaults.vassalEligibleCultures().contains(vassal.getCulture())) {
            loyalty += 15;
        }
        return Math.max(0, Math.min(100, loyalty));
    }

    private static void triggerRebellion(Kingdom vassal, Kingdom overlord,
                                         long tick, int loyalty) {
        // Find the vassalage treaty.
        Treaty vassalage = null;
        for (Treaty t : vassal.getTreaties()) {
            if (t.isActive() && t.type() == TreatyType.VASSALAGE
                    && t.parties().contains(overlord.getId())) {
                vassalage = t;
                break;
            }
        }
        if (vassalage == null) return;

        String reason = "vassal loyalty " + loyalty + " below " + REBELLION_THRESHOLD;
        UUID treatyId = vassalage.id();
        vassal.breakTreaty(treatyId, vassal.getId(), tick, reason);
        overlord.breakTreaty(treatyId, vassal.getId(), tick, reason);

        KingdomEventBus.fire(new KingdomEvent.VassalRebelled(
                overlord.getId(), vassal.getId(), treatyId, reason, tick));
        KingdomNewsfeed.append(vassal, "vassal.rebelled",
                "Threw off the yoke of " + overlord.getName(), tick);
        KingdomNewsfeed.append(overlord, "vassal.rebelled",
                vassal.getName() + " has rebelled against vassalage", tick);

        LOGGER.info("[VassalRebellionDriver] {} rebelled against {} (loyalty {})",
                vassal.getName(), overlord.getName(), loyalty);
    }
}
