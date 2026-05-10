package tterrag1112.life_in_the_village.Kingdom.Intrigue;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomModifier;
import tterrag1112.life_in_the_village.Kingdom.Provinces.Province;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Office.Competence;
import tterrag1112.life_in_the_village.Npc.Office.OfficeDefinition;
import tterrag1112.life_in_the_village.Npc.Office.OfficeHolding;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Office.OfficeState;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.4b — Spymaster intrigue mechanics.
 *
 * <p>Per the user-confirmed design pass items e + f (CC's call):
 * <ul>
 *   <li>Spymaster competence inputs: primary skill SOCIAL,
 *       secondary LITERACY (matches the office's Competence shape
 *       in {@link OfficeRegistry}). Counter-intelligence reads the
 *       same competence from the target's Spymaster.</li>
 *   <li>Per-kingdom cooldown: {@link #PER_KINGDOM_COOLDOWN_TICKS}
 *       between any sow attempts (any target).</li>
 *   <li>Per-target cooldown: {@link #PER_TARGET_COOLDOWN_TICKS}
 *       between sow attempts on the same target.</li>
 *   <li>Treasury cost: {@link #SOW_TREASURY_COST} bronze per
 *       attempt (debited from source kingdom's treasury).</li>
 * </ul>
 *
 * <h3>Determinism</h3>
 * Outcome seeded by
 * {@code (sourceKingdomId, "intrigue", targetKingdomId, gameDay)}
 * per the kingdom-plan determinism rule. Same world seed produces
 * same outcomes across reloads.
 *
 * <h3>Stability impact</h3>
 * On success: target province takes a {@link #SUCCESS_STABILITY_HIT}
 * stability dip applied via the existing
 * {@link KingdomModifier} system (tag
 * {@code intrigue.discontent.<sourceKingdom>}).
 *
 * <h3>Counter-intelligence</h3>
 * Target kingdom's Spymaster competence reduces the source's
 * success probability by {@link #COUNTER_INTEL_FACTOR_PER_LEVEL}
 * × competence multiplier; if the target Spymaster is at the
 * full curve {@code maxBonus}, expected success drops to
 * roughly half.
 */
public final class IntrigueDriver {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Per-kingdom cooldown — 7 in-game days between any attempts. */
    public static final long PER_KINGDOM_COOLDOWN_TICKS = 7L * 24000L;

    /** Per-target cooldown — 21 in-game days between attempts on the same target. */
    public static final long PER_TARGET_COOLDOWN_TICKS = 21L * 24000L;

    /** Treasury cost in bronze per sow-discontent attempt. */
    public static final long SOW_TREASURY_COST = 50L;

    /** Stability dip (negative) applied to the target province on success. */
    public static final int SUCCESS_STABILITY_HIT = -8;

    /** Modifier expiry duration on the target province (14 in-game days). */
    public static final long MODIFIER_DURATION_TICKS = 14L * 24000L;

    /**
     * Counter-intelligence reduction factor — multiplied by target
     * Spymaster's competence bonus. Default: 0.5 means a fully-
     * effective target Spymaster halves success probability.
     */
    public static final double COUNTER_INTEL_FACTOR_PER_LEVEL = 0.5;

    /** Discovery probability when the target has a competent Spymaster. */
    public static final double DISCOVERY_BASE_PROBABILITY = 0.4;

    private IntrigueDriver() {}

    /**
     * Result returned from {@link #sowDiscontent} so callers
     * (debug commands, packet handlers, future UI) can surface
     * the outcome without re-parsing the intrigue history.
     */
    public record SowResult(boolean attempted, boolean success,
                            boolean discovered, String reason,
                            UUID attemptId) {

        public static SowResult deny(String reason) {
            return new SowResult(false, false, false, reason, null);
        }
    }

    /**
     * Launches a sow-discontent attempt. Validates cooldowns +
     * treasury + capability + target province; rolls the outcome
     * deterministically; applies stability hit on success; records
     * the attempt + fires events.
     */
    public static SowResult sowDiscontent(ServerLevel level, VillageSavedData data,
                                          Kingdom source, Kingdom target,
                                          Province targetProvince, long tick) {
        if (source == null || target == null) {
            return SowResult.deny("missing kingdom");
        }
        if (source.getId().equals(target.getId())) {
            return SowResult.deny("cannot sow discontent in your own kingdom");
        }
        // Per-kingdom cooldown.
        Optional<IntrigueAttempt> last = source.lastAttempt();
        if (last.isPresent() && tick - last.get().tick() < PER_KINGDOM_COOLDOWN_TICKS) {
            return SowResult.deny("per-kingdom cooldown active ("
                    + ((tick - last.get().tick()) / 24000L) + "/"
                    + (PER_KINGDOM_COOLDOWN_TICKS / 24000L) + " days)");
        }
        // Per-target cooldown.
        Optional<IntrigueAttempt> lastTgt = source.lastAttemptAgainst(target.getId());
        if (lastTgt.isPresent() && tick - lastTgt.get().tick() < PER_TARGET_COOLDOWN_TICKS) {
            return SowResult.deny("per-target cooldown active");
        }
        // Treasury cost.
        if (source.getTreasury() < SOW_TREASURY_COST) {
            return SowResult.deny("insufficient treasury (" + source.getTreasury()
                    + "b / " + SOW_TREASURY_COST + "b required)");
        }

        // Resolve source Spymaster competence.
        double sourceComp = officeCompetence(level, source, OfficeRegistry.KINGDOM_SPYMASTER);
        // Resolve target Spymaster competence (counter-intel).
        double targetComp = officeCompetence(level, target, OfficeRegistry.KINGDOM_SPYMASTER);

        // Deterministic seed: (sourceKingdomId, "intrigue", targetKingdomId, gameDay).
        long gameDay = tick / 24000L;
        long seed = source.getId().getMostSignificantBits()
                ^ Long.rotateLeft("intrigue".hashCode(), 17)
                ^ Long.rotateLeft(target.getId().getMostSignificantBits(), 31)
                ^ gameDay;
        java.util.Random rng = new java.util.Random(seed);

        // Success probability: base 0.5 × source competence multiplier,
        // reduced by target competence × COUNTER_INTEL_FACTOR_PER_LEVEL.
        double successProb = 0.5 * sourceComp;
        successProb -= COUNTER_INTEL_FACTOR_PER_LEVEL * Math.max(0.0, targetComp - 1.0);
        successProb = Math.max(0.05, Math.min(0.95, successProb));
        boolean success = rng.nextDouble() < successProb;

        // Discovery probability: 0.4 × target competence, only when
        // target has a Spymaster competently held.
        double discoveryProb = DISCOVERY_BASE_PROBABILITY * Math.max(0.0, targetComp);
        boolean discovered = rng.nextDouble() < discoveryProb;

        // Apply effects.
        source.depositToTreasury(-SOW_TREASURY_COST);
        int hit = 0;
        if (success && targetProvince != null) {
            hit = SUCCESS_STABILITY_HIT;
            // Apply as expiring modifier on the target kingdom (province
            // stability is tracked there too via Province.modifiers; v1
            // applies the modifier at kingdom level for legibility).
            target.addModifier(KingdomModifier.expiring(
                    "intrigue.discontent." + source.getName(),
                    "Sown discontent from " + source.getName(),
                    hit, 0,
                    tick,
                    MODIFIER_DURATION_TICKS));
        }

        // Find the spymaster NPC (if loaded) for the event payload.
        Optional<UUID> spymasterUuid = npcSpymaster(level, source);

        UUID attemptId = UUID.randomUUID();
        IntrigueAttempt attempt = new IntrigueAttempt(
                attemptId, source.getId(), target.getId(),
                Optional.ofNullable(targetProvince).map(Province::id),
                spymasterUuid, tick, success, discovered, hit);
        source.recordIntrigueAttempt(attempt);

        // Events.
        KingdomEventBus.fire(new KingdomEvent.IntrigueLaunched(
                source.getId(), target.getId(),
                Optional.ofNullable(targetProvince).map(Province::id).orElse(null),
                spymasterUuid.orElse(null), success, tick));
        if (discovered) {
            KingdomEventBus.fire(new KingdomEvent.IntrigueDiscovered(
                    target.getId(), source.getId(),
                    Optional.ofNullable(targetProvince).map(Province::id).orElse(null),
                    tick));
        }
        // Track D3.5D — newsfeed entries for both sides. Source
        // sees their attempt outcome; target sees discovery only.
        tterrag1112.life_in_the_village.Kingdom.Audience.KingdomNewsfeed.append(
                source, success ? "intrigue.success" : "intrigue.failed",
                "Sowed discontent in " + target.getName()
                        + (success ? " (succeeded)" : " (failed)"), tick);
        if (discovered) {
            tterrag1112.life_in_the_village.Kingdom.Audience.KingdomNewsfeed.append(
                    target, "intrigue.discovered",
                    "Discovered intrigue from " + source.getName(), tick);
        }

        LOGGER.info("[IntrigueDriver] {} sowed discontent in {} (success={}, discovered={}, hit={})",
                source.getName(), target.getName(), success, discovered, hit);
        return new SowResult(true, success, discovered,
                "attempt resolved (success=" + success + ", discovered=" + discovered + ")",
                attemptId);
    }

    /**
     * Reads the kingdom's Spymaster competence multiplier (1.0 +
     * skill-derived bonus, or {@code 1 + vacancyPenalty} when
     * vacant). Returns 1.0 when the spymaster is loaded but at
     * minLevel exactly; > 1.0 when above; < 1.0 when vacant /
     * under-skilled.
     */
    private static double officeCompetence(ServerLevel level, Kingdom kingdom, String officeId) {
        OfficeState offices = kingdom.getOffices();
        OfficeHolding holding = offices.get(officeId).orElse(null);
        OfficeDefinition def = OfficeRegistry.get(officeId);
        if (def == null) return 1.0;
        Competence curve = def.competence();
        if (holding == null || holding.isVacant()) {
            return curve.computeMultiplier(0);
        }
        UUID npcId = holding.holderNpcId().orElse(null);
        if (npcId == null) return 1.0; // player holder; no skill check
        TownspersonMob npc = TownspersonMob.findByUUID(level, npcId).orElse(null);
        if (npc == null) return 1.0; // offline; assume competent at minLevel
        Skill primary = curve.primarySkill();
        return curve.computeMultiplier(npc.getSkills().getLevel(primary));
    }

    /** Returns the source kingdom's loaded Spymaster NPC UUID, if any. */
    private static Optional<UUID> npcSpymaster(ServerLevel level, Kingdom kingdom) {
        OfficeHolding h = kingdom.getOffices().get(OfficeRegistry.KINGDOM_SPYMASTER).orElse(null);
        if (h == null || h.isVacant()) return Optional.empty();
        return h.holderNpcId();
    }
}
