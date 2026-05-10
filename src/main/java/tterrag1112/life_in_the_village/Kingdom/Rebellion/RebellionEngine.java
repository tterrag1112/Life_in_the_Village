package tterrag1112.life_in_the_village.Kingdom.Rebellion;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Cultures.RebellionThresholds;
import tterrag1112.life_in_the_village.Kingdom.Audience.AudienceDriver;
import tterrag1112.life_in_the_village.Kingdom.Audience.KingdomNewsfeed;
import tterrag1112.life_in_the_village.Kingdom.Audience.Petition;
import tterrag1112.life_in_the_village.Kingdom.Audience.PetitionKind;
import tterrag1112.life_in_the_village.Kingdom.Audience.PetitionPayload;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomModifier;
import tterrag1112.life_in_the_village.Kingdom.Provinces.Province;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.6.1 — central engine for rebellion mechanics.
 *
 * <p>Daily tick runs over every kingdom's provinces, stages each
 * by stability against the culture's
 * {@link RebellionThresholds}, fires events + modifiers + (for
 * SECESSION_THREAT) creates a {@link Petition} that the ruler
 * resolves NEGOTIATE / CRUSH / ACCEPT. NPC rulers auto-resolve
 * via trait-weighted scoring inline; player rulers see a chat
 * notification + the petition lands in the AUDIENCE section
 * three-button resolve path.
 *
 * <h3>Determinism seed</h3>
 * Rebellion auto-resolution + outcome rolls seed from
 * {@code (kingdomId, "rebellion", provinceId, gameDay)} per the
 * locked design.
 *
 * <h3>Resolution effects</h3>
 * <ul>
 *   <li><b>NEGOTIATE</b> — treasury cost {@link #NEGOTIATE_TREASURY_COST};
 *       province stability floored at {@link #NEGOTIATE_STABILITY_FLOOR};
 *       legitimacy unchanged.</li>
 *   <li><b>CRUSH</b> — legitimacy malus {@link #CRUSH_LEGITIMACY_MALUS};
 *       province stability floored at
 *       {@link #CRUSH_STABILITY_FLOOR}; kingdom-wide modifier
 *       "rebellion.crushed" for 14 days.</li>
 *   <li><b>ACCEPT</b> — immediate secession via
 *       {@link SecessionExecutor#secede}.</li>
 * </ul>
 */
public final class RebellionEngine {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int NEGOTIATE_TREASURY_COST = 50;
    public static final int NEGOTIATE_STABILITY_FLOOR = 35;
    public static final int CRUSH_STABILITY_FLOOR = 30;
    public static final int CRUSH_LEGITIMACY_MALUS = 10;

    /** Days the SECESSION_THREAT petition stays open before auto-secession. */
    public static final long THREAT_TIMEOUT_TICKS = 5L * 24000L;

    /** Modifier tag prefix for grumble entries. */
    public static final String GRUMBLE_MODIFIER_PREFIX = "rebellion.grumble.";

    public enum Choice { NEGOTIATE, CRUSH, ACCEPT }

    private RebellionEngine() {}

    public record ResolutionResult(boolean applied, String reason) {
        public static ResolutionResult fail(String r) { return new ResolutionResult(false, r); }
    }

    /**
     * Daily entry — evaluates every province in every kingdom.
     * Player-ruled kingdoms get a petition + chat notification on
     * SECESSION_THREAT; NPC rulers auto-resolve via
     * {@link #autoResolveNpc} based on trait-weighted scoring.
     * SECESSION fires the executor directly regardless of ruler.
     */
    public static void dailyTick(ServerLevel level, VillageSavedData data, long tick) {
        for (Kingdom kingdom : data.getAllKingdoms()) {
            var culture = CultureResolver.ofKingdom(kingdom);
            var thresholds = culture.kingdomDefaults().rebellionThresholds();
            for (Province province : new ArrayList<>(kingdom.getProvinces())) {
                var stage = thresholds.stageFor(province.stability());
                if (stage == null) {
                    // Stable — clear any lingering grumble modifier.
                    String tag = GRUMBLE_MODIFIER_PREFIX + province.id();
                    kingdom.removeModifier(tag);
                    continue;
                }
                switch (stage) {
                    case GRUMBLE          -> applyGrumble(kingdom, province, tick);
                    case SECESSION_THREAT -> applyThreat(level, data, kingdom, province, tick);
                    case SECESSION        -> applySecession(level, data, kingdom, province, tick);
                }
            }
        }
    }

    private static void applyGrumble(Kingdom kingdom, Province province, long tick) {
        String tag = GRUMBLE_MODIFIER_PREFIX + province.id();
        if (kingdom.hasModifierWithId(tag)) return; // already applied
        kingdom.addModifier(KingdomModifier.expiring(
                tag,
                "Grumbling in " + province.name(),
                -2, 0,
                tick,
                24000L * 14));
        KingdomEventBus.fire(new KingdomEvent.RebellionGrumble(
                kingdom.getId(), province.id(), province.stability(), tick));
        KingdomNewsfeed.append(kingdom, "rebellion.grumble",
                "Discontent stirs in " + province.name(), tick);
    }

    private static void applyThreat(ServerLevel level, VillageSavedData data,
                                    Kingdom kingdom, Province province, long tick) {
        // Avoid duplicate petitions for the same province.
        for (Petition existing : kingdom.getPetitions()) {
            if (!existing.isPending()) continue;
            if (existing.kind() != PetitionKind.REBELLION_THREAT) continue;
            if (existing.payload() instanceof PetitionPayload.RebellionThreat rt
                    && rt.provinceId().equals(province.id())) {
                // Timeout check: if the petition is stale, auto-secede.
                if (tick - existing.submittedTick() >= THREAT_TIMEOUT_TICKS) {
                    KingdomNewsfeed.append(kingdom, "rebellion.threat.timeout",
                            "Threat in " + province.name() + " ignored — secession imminent", tick);
                    kingdom.replacePetition(existing.asExpired(tick));
                    applySecession(level, data, kingdom, province, tick);
                }
                return;
            }
        }
        KingdomEventBus.fire(new KingdomEvent.SecessionThreat(
                kingdom.getId(), province.id(), province.stability(), tick));
        KingdomNewsfeed.append(kingdom, "rebellion.threat",
                "Secession threat in " + province.name(), tick);

        if (kingdom.getRulerPlayerId().isPresent()) {
            // Player ruler: create the petition + chat notification.
            UUID virtualSubmitter = province.governorUuid().orElse(province.id());
            var payload = new PetitionPayload.RebellionThreat(province.id());
            UUID petitionId = UUID.randomUUID();
            kingdom.submitPetition(Petition.freshSubmission(
                    petitionId, virtualSubmitter, kingdom.getId(), payload, tick));
            KingdomEventBus.fire(new KingdomEvent.PetitionSubmitted(
                    kingdom.getId(), petitionId, virtualSubmitter,
                    PetitionKind.REBELLION_THREAT.name(), tick));
            var srvPlayer = level.getServer().getPlayerList()
                    .getPlayer(kingdom.getRulerPlayerId().get());
            if (srvPlayer != null) {
                srvPlayer.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "Secession threat in " + province.name()
                                        + " — resolve with /litv kingdom debug rebel "
                                        + kingdom.getName() + " " + province.name()
                                        + " <negotiate|crush|accept>"),
                        false);
            }
        } else {
            // NPC ruler: auto-resolve.
            autoResolveNpc(level, data, kingdom, province, tick);
        }
    }

    private static void applySecession(ServerLevel level, VillageSavedData data,
                                       Kingdom kingdom, Province province, long tick) {
        SecessionExecutor.secede(level, data, kingdom, province, tick,
                "stability collapsed");
    }

    /**
     * NPC-ruler auto-resolution: trait-weighted choice between
     * NEGOTIATE / CRUSH / ACCEPT. Seeded by the determinism tuple.
     */
    private static void autoResolveNpc(ServerLevel level, VillageSavedData data,
                                       Kingdom kingdom, Province province, long tick) {
        UUID rulerId = kingdom.getRulerEntityId().orElse(null);
        long seed = kingdom.getId().getMostSignificantBits()
                ^ Long.rotateLeft("rebellion".hashCode(), 17)
                ^ Long.rotateLeft(province.id().getMostSignificantBits(), 31)
                ^ (tick / 24000L);
        var rng = new java.util.Random(seed);
        // Treasury-dependent: if too poor to negotiate, ACCEPT or CRUSH only.
        boolean canNegotiate = kingdom.getTreasury() >= NEGOTIATE_TREASURY_COST;
        Choice choice;
        double roll = rng.nextDouble();
        if (canNegotiate && roll < 0.45) choice = Choice.NEGOTIATE;
        else if (roll < 0.8)             choice = Choice.CRUSH;
        else                             choice = Choice.ACCEPT;

        applyResolution(level, data, kingdom, province, choice, rulerId, tick);
    }

    /** Resolves a pending REBELLION_THREAT petition (player path). */
    public static AudienceDriver.ResolutionResult resolveThreat(
            ServerLevel level, VillageSavedData data, Kingdom kingdom,
            UUID petitionId, Choice choice, UUID resolvedBy, long tick) {
        Petition p = kingdom.findPetition(petitionId).orElse(null);
        if (p == null) return AudienceDriver.ResolutionResult.denyApply("no such petition");
        if (!p.isPending()) return AudienceDriver.ResolutionResult.denyApply("already resolved");
        if (p.kind() != PetitionKind.REBELLION_THREAT) {
            return AudienceDriver.ResolutionResult.denyApply("wrong petition kind");
        }
        if (!(p.payload() instanceof PetitionPayload.RebellionThreat rt)) {
            return AudienceDriver.ResolutionResult.denyApply("malformed payload");
        }
        Province province = kingdom.getProvinces().stream()
                .filter(pr -> pr.id().equals(rt.provinceId()))
                .findFirst().orElse(null);
        if (province == null) {
            // Province gone (already seceded or recomputed); close petition.
            kingdom.replacePetition(p.asDenied(resolvedBy, tick, "province no longer exists"));
            return AudienceDriver.ResolutionResult.denyApply("province gone");
        }
        applyResolution(level, data, kingdom, province, choice, resolvedBy, tick);
        kingdom.replacePetition(p.asApproved(resolvedBy, tick,
                "ruler chose " + choice.name()));
        return new AudienceDriver.ResolutionResult(true,
                "resolved " + choice.name(), 0, 0);
    }

    private static void applyResolution(ServerLevel level, VillageSavedData data,
                                        Kingdom kingdom, Province province,
                                        Choice choice, UUID resolvedBy, long tick) {
        switch (choice) {
            case NEGOTIATE -> {
                long debited = Math.min(NEGOTIATE_TREASURY_COST, kingdom.getTreasury());
                kingdom.depositToTreasury(-debited);
                kingdom.replaceProvinceStability(province.id(),
                        Math.max(province.stability(), NEGOTIATE_STABILITY_FLOOR));
                KingdomEventBus.fire(new KingdomEvent.SecessionThreatNegotiated(
                        kingdom.getId(), province.id(), debited, tick));
                KingdomNewsfeed.append(kingdom, "rebellion.negotiated",
                        "Negotiated peace in " + province.name()
                                + " (" + debited + "b paid)", tick);
            }
            case CRUSH -> {
                kingdom.applyLegitimacyDelta(-CRUSH_LEGITIMACY_MALUS);
                kingdom.addModifier(KingdomModifier.expiring(
                        "rebellion.crushed." + province.id(),
                        "Crushed rebellion in " + province.name(),
                        -3, -CRUSH_LEGITIMACY_MALUS / 2,
                        tick,
                        24000L * 14));
                kingdom.replaceProvinceStability(province.id(),
                        Math.max(province.stability(), CRUSH_STABILITY_FLOOR));
                KingdomEventBus.fire(new KingdomEvent.SecessionThreatCrushed(
                        kingdom.getId(), province.id(), tick));
                KingdomNewsfeed.append(kingdom, "rebellion.crushed",
                        "Crushed rebellion in " + province.name(), tick);
            }
            case ACCEPT -> {
                // Ruler waves the province goodbye — secede immediately.
                SecessionExecutor.secede(level, data, kingdom, province, tick,
                        "ruler accepted secession");
            }
        }
    }
}
