package tterrag1112.life_in_the_village.Kingdom.Religion;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Kingdom.Audience.KingdomNewsfeed;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomModifier;
import tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLawInstance;
import tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLawState;
import tterrag1112.life_in_the_village.Kingdom.Provinces.Province;
import tterrag1112.life_in_the_village.Kingdom.Rebellion.CollapseEngine;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionRegistry;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.6.5 — religion-as-political-authority engine.
 *
 * <p>Per the user-locked "soft religion" model:
 * <ul>
 *   <li>Declaration of an official religion immediately stamps a
 *       stability dip on each dissenting province (those whose
 *       culture's default religion differs from the kingdom's
 *       declared religion).</li>
 *   <li>Belief shifts happen slowly via the
 *       {@link ConvertProvinceDriver} CONVERT_PROVINCE
 *       capability flow (treasury + Diplomat + Treasurer cost).</li>
 *   <li>No automatic religious-rebellion fires from declaration
 *       alone; only the standard rebellion threshold from D3.6.1
 *       triggers, but the religious tension lowers province
 *       stability so it gets there sooner.</li>
 * </ul>
 *
 * <p>Determinism seeds:
 * <ul>
 *   <li>Sanctification roll: {@code (kingdomId, "sanctify",
 *       rulerId, gameDay)}.</li>
 *   <li>Conversion campaign roll: {@code (kingdomId, "convert",
 *       provinceId, gameDay)}.</li>
 * </ul>
 */
public final class ReligionAuthorityEngine {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Modifier id stamped on a province with religious mismatch. */
    public static final String TENSION_MODIFIER_PREFIX = "religion.tension.";

    /** Permanent legitimacy bonus modifier id for a sanctified ruler. */
    public static final String SANCTIFIED_MODIFIER_ID = "religion.sanctified";

    /** Expiring legitimacy malus when sanctification was refused. */
    public static final String SANCTIFICATION_REFUSED_MODIFIER_ID
            = "religion.sanctification_refused";

    /** Legitimacy bonus on successful sanctification. */
    public static final int SANCTIFICATION_LEGITIMACY_BONUS = 10;

    /** Legitimacy malus on refused sanctification. */
    public static final int SANCTIFICATION_REFUSED_MALUS = 10;

    /** Per-province tension stability dip (always negative). */
    public static final int TENSION_STABILITY_DIP = -3;

    private ReligionAuthorityEngine() {}

    public static void dailyTick(ServerLevel level, VillageSavedData data, long tick) {
        for (Kingdom kingdom : new ArrayList<>(data.getAllKingdoms())) {
            if (kingdom.hasModifierWithId(CollapseEngine.COLLAPSED_MODIFIER_ID)) continue;
            applyReligiousTension(kingdom, tick);
        }
    }

    /**
     * Reads the kingdom's "official_religion" EnumLaw active state,
     * returns the declared religion id or empty if no law is
     * active or its choice is "none".
     */
    public static Optional<String> officialReligion(Kingdom kingdom) {
        KingdomLawInstance inst = kingdom.findLawInstance("official_religion").orElse(null);
        if (inst == null || inst.state() != KingdomLawState.ACTIVE) return Optional.empty();
        String choice = inst.enumChoice().orElse("none");
        if (choice.isEmpty() || choice.equalsIgnoreCase("none")) return Optional.empty();
        return Optional.of(choice);
    }

    /**
     * Stamps {@code religion.tension.<provinceId>} on the kingdom
     * for each province whose dominant religion (derived from
     * culture default in v1) doesn't match the kingdom's
     * declared official religion. Tension clears when official
     * religion matches OR a conversion campaign completes.
     */
    public static void applyReligiousTension(Kingdom kingdom, long tick) {
        Optional<String> officialOpt = officialReligion(kingdom);
        if (officialOpt.isEmpty()) {
            // No official religion → no tension. Sweep any old tension modifiers.
            for (Province p : new ArrayList<>(kingdom.getProvinces())) {
                kingdom.removeModifier(TENSION_MODIFIER_PREFIX + p.id());
            }
            return;
        }
        String official = officialOpt.get();
        // First-detection: fire OfficialReligionDeclared event once per
        // (kingdom, religion id) pair. Marker is permanent so it
        // never re-fires until the choice changes.
        String marker = "religion.declared." + official;
        if (!kingdom.hasModifierWithId(marker)) {
            // Sweep any prior declaration markers to allow re-fire on
            // choice change.
            kingdom.getModifiers().stream()
                    .filter(m -> m.id().startsWith("religion.declared."))
                    .map(KingdomModifier::id)
                    .toList()
                    .forEach(kingdom::removeModifier);
            kingdom.addModifier(KingdomModifier.permanent(
                    marker,
                    "Declared " + official + " as official religion",
                    0, 0, tick));
            onOfficialReligionDeclared(kingdom, official, tick);
        }
        // v1: dominant religion of a province = culture default of the kingdom
        // (provinces don't carry their own culture in v1). Same culture
        // → same default, so tension only fires when the kingdom
        // OVERRIDES the culture default with a different state religion.
        var culture = CultureResolver.ofKingdom(kingdom);
        String dominant = ReligionRegistry.dominantReligionFor(culture.displayName());
        boolean mismatch = !dominant.equalsIgnoreCase(official);
        for (Province p : kingdom.getProvinces()) {
            String tag = TENSION_MODIFIER_PREFIX + p.id();
            // Active conversion campaigns suppress tension.
            if (kingdom.hasModifierWithId(
                    ConvertProvinceDriver.CAMPAIGN_MODIFIER_PREFIX + p.id())) {
                kingdom.removeModifier(tag);
                continue;
            }
            if (mismatch) {
                if (!kingdom.hasModifierWithId(tag)) {
                    kingdom.addModifier(KingdomModifier.expiring(
                            tag,
                            "Religious tension in " + p.name(),
                            TENSION_STABILITY_DIP, 0,
                            tick, 24000L * 30));
                }
            } else {
                kingdom.removeModifier(tag);
            }
        }
    }

    /**
     * Track D3.6.5 — royal sanctification attempt. Called from
     * {@code NobilityEventDispatcher.runSuccession} after an heir
     * is seated; rolls deterministically against the senior priest's
     * disposition. Granted/refused stamps the matching modifier and
     * fires the corresponding event.
     */
    public static void attemptSanctification(ServerLevel level, VillageSavedData data,
                                             Kingdom kingdom, UUID rulerId, long tick) {
        // Skip if no official religion declared.
        if (officialReligion(kingdom).isEmpty()) return;
        // Skip if ruler already sanctified for this reign (modifier present).
        if (kingdom.hasModifierWithId(SANCTIFIED_MODIFIER_ID)) return;

        long seed = kingdom.getId().getMostSignificantBits()
                ^ Long.rotateLeft("sanctify".hashCode(), 17)
                ^ (rulerId != null ? rulerId.getMostSignificantBits() : 0L)
                ^ (tick / 24000L);
        var rng = new java.util.Random(seed);
        // v1 simplification: 70% grant baseline, modulated by ruler legitimacy.
        // High-legitimacy rulers (≥75) are auto-granted; low-legitimacy
        // (<35) often refused.
        double prob = 0.70 + (kingdom.getLegitimacy() - 50) * 0.005;
        prob = Math.max(0.20, Math.min(0.95, prob));
        boolean granted = rng.nextDouble() < prob;

        if (granted) {
            kingdom.addModifier(KingdomModifier.permanent(
                    SANCTIFIED_MODIFIER_ID,
                    "Sanctified by the senior priest",
                    0, SANCTIFICATION_LEGITIMACY_BONUS, tick));
            KingdomEventBus.fire(new KingdomEvent.Sanctified(
                    kingdom.getId(), rulerId, tick));
            KingdomNewsfeed.append(kingdom, "religion.sanctified",
                    "Ruler sanctified by the senior priest", tick);
            LOGGER.info("[ReligionAuthorityEngine] {} ruler sanctified", kingdom.getName());
        } else {
            kingdom.addModifier(KingdomModifier.expiring(
                    SANCTIFICATION_REFUSED_MODIFIER_ID,
                    "Senior priest refused sanctification",
                    -2, -SANCTIFICATION_REFUSED_MALUS,
                    tick, 24000L * 60));
            KingdomEventBus.fire(new KingdomEvent.SanctificationRefused(
                    kingdom.getId(), rulerId, tick));
            KingdomNewsfeed.append(kingdom, "religion.refused",
                    "Senior priest refused to sanctify the new ruler", tick);
            // 10% chance of cascading into a religious rebellion event;
            // this signals the standard rebellion driver to check
            // thresholds with extra urgency by stamping a kingdom-wide
            // tension modifier.
            if (rng.nextDouble() < 0.10) {
                KingdomEventBus.fire(new KingdomEvent.ReligiousRebellion(
                        kingdom.getId(), "sanctification refused", tick));
                kingdom.addModifier(KingdomModifier.expiring(
                        "religion.rebellion_pressure",
                        "Religious rebellion pressure following refusal",
                        -5, -3, tick, 24000L * 30));
                KingdomNewsfeed.append(kingdom, "religion.rebellion",
                        "Religious unrest follows the refusal", tick);
            }
            LOGGER.info("[ReligionAuthorityEngine] {} sanctification refused", kingdom.getName());
        }
    }

    /**
     * Track D3.6.5 — fires the OfficialReligionDeclared event +
     * newsfeed entry. Called once-per-declaration from
     * {@link #applyReligiousTension} when the marker modifier
     * isn't present; doesn't re-call applyReligiousTension to
     * avoid recursion.
     */
    public static void onOfficialReligionDeclared(Kingdom kingdom, String religionId, long tick) {
        KingdomEventBus.fire(new KingdomEvent.OfficialReligionDeclared(
                kingdom.getId(), religionId, tick));
        KingdomNewsfeed.append(kingdom, "religion.declared",
                "Declared " + religionId + " as the official religion", tick);
    }
}
