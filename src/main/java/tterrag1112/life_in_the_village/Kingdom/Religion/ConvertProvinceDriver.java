package tterrag1112.life_in_the_village.Kingdom.Religion;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Kingdom.Audience.KingdomNewsfeed;
import tterrag1112.life_in_the_village.Kingdom.Capabilities.KingdomCapability;
import tterrag1112.life_in_the_village.Kingdom.Capabilities.KingdomCapabilityEvaluator;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomModifier;
import tterrag1112.life_in_the_village.Kingdom.Provinces.Province;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Track D3.6.5 — conversion-campaign driver.
 *
 * <p>{@link KingdomCapability#CONVERT_PROVINCE} requires Diplomat
 * + Treasurer + ruler authority + treasury cost
 * ({@link #CAMPAIGN_TREASURY_COST}). On launch:
 * <ul>
 *   <li>Stamps a permanent {@code religion.conversion_campaign.<provinceId>}
 *       modifier on the kingdom whose appliedAtTick = start tick;
 *       this serves as both the active-campaign flag and the
 *       suppress-tension signal for {@link ReligionAuthorityEngine}.</li>
 *   <li>Debits the treasury.</li>
 *   <li>Fires {@code ConversionCampaignStarted}.</li>
 * </ul>
 *
 * <p>The driver's daily tick scans for active campaigns past
 * {@link #CAMPAIGN_DURATION_TICKS}; on completion, removes the
 * campaign modifier (which lifts tension naturally next tick).
 *
 * <p>Determinism seed:
 * {@code (kingdomId, "convert", provinceId, gameDay)}.
 */
public final class ConvertProvinceDriver {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String CAMPAIGN_MODIFIER_PREFIX
            = "religion.conversion_campaign.";

    /** Treasury cost to launch a campaign. */
    public static final long CAMPAIGN_TREASURY_COST = 100L;

    /** Campaign runs for 30 in-game days then auto-completes. */
    public static final long CAMPAIGN_DURATION_TICKS = 30L * 24000L;

    private ConvertProvinceDriver() {}

    public record LaunchResult(boolean started, String reason) {
        public static LaunchResult fail(String r) { return new LaunchResult(false, r); }
    }

    public static void dailyTick(ServerLevel level, VillageSavedData data, long tick) {
        for (Kingdom kingdom : new ArrayList<>(data.getAllKingdoms())) {
            for (Province province : new ArrayList<>(kingdom.getProvinces())) {
                String campaignId = CAMPAIGN_MODIFIER_PREFIX + province.id();
                var modifier = kingdom.getModifiers().stream()
                        .filter(m -> m.id().equals(campaignId))
                        .findFirst().orElse(null);
                if (modifier == null) continue;
                if (tick - modifier.appliedAtTick() >= CAMPAIGN_DURATION_TICKS) {
                    // Campaign completed.
                    kingdom.removeModifier(campaignId);
                    KingdomNewsfeed.append(kingdom, "religion.converted",
                            "Conversion campaign in " + province.name()
                                    + " completed", tick);
                }
            }
        }
    }

    /**
     * Launches a conversion campaign for {@code provinceId}.
     * Validates CONVERT_PROVINCE capability, treasury balance,
     * and whether a campaign is already active for that province.
     */
    public static LaunchResult launch(ServerLevel level, VillageSavedData data,
                                      Kingdom kingdom, UUID provinceId, long tick) {
        Province province = kingdom.getProvinces().stream()
                .filter(p -> p.id().equals(provinceId))
                .findFirst().orElse(null);
        if (province == null) return LaunchResult.fail("province not in kingdom");

        var cap = KingdomCapabilityEvaluator.evaluate(
                level, data, kingdom, KingdomCapability.CONVERT_PROVINCE);
        if (!cap.allowed()) {
            return LaunchResult.fail("CONVERT_PROVINCE denied: " + cap.reason());
        }
        if (kingdom.getTreasuryBronze() < CAMPAIGN_TREASURY_COST) {
            return LaunchResult.fail("insufficient treasury (needs "
                    + CAMPAIGN_TREASURY_COST + "b)");
        }
        String campaignId = CAMPAIGN_MODIFIER_PREFIX + provinceId;
        if (kingdom.hasModifierWithId(campaignId)) {
            return LaunchResult.fail("campaign already active in this province");
        }

        kingdom.depositToTreasury(-CAMPAIGN_TREASURY_COST);
        kingdom.addModifier(KingdomModifier.permanent(
                campaignId,
                "Conversion campaign in " + province.name(),
                +1, 0, tick));
        KingdomEventBus.fire(new KingdomEvent.ConversionCampaignStarted(
                kingdom.getId(), provinceId, tick));
        KingdomNewsfeed.append(kingdom, "religion.campaign",
                "Launched conversion campaign in " + province.name()
                        + " (" + CAMPAIGN_TREASURY_COST + "b)", tick);
        LOGGER.info("[ConvertProvinceDriver] {} launched conversion in {}",
                kingdom.getName(), province.name());
        return new LaunchResult(true, "campaign launched");
    }
}
