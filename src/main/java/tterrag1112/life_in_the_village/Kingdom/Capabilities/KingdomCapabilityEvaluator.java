package tterrag1112.life_in_the_village.Kingdom.Capabilities;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Office.Competence;
import tterrag1112.life_in_the_village.Npc.Office.OfficeDefinition;
import tterrag1112.life_in_the_village.Npc.Office.OfficeHolding;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Office.OfficeState;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.3b — capability gate evaluator. Pure function on
 * {@link Kingdom} + {@link KingdomCapability} → {@link Result}.
 *
 * <p>Per the user-confirmed design:
 * <ul>
 *   <li><b>OR semantics:</b> a capability unlocks if EITHER the
 *       King's office is competently held OR the named delegated
 *       office is competently held.</li>
 *   <li><b>Competence floor:</b> "competently held" means the
 *       holder's primary-skill level &geq; the office definition's
 *       {@code minLevel} (multiplier &geq; 1.0). Vacancy or
 *       under-skilled holder fails the gate.</li>
 *   <li><b>Player rulers:</b> a player-held King's office always
 *       satisfies its capability (we don't read player skill
 *       levels server-side here).</li>
 * </ul>
 *
 * <p>The result includes a human-readable reason + the office id
 * that satisfied the gate (for GUI tooltips like "Magistrate
 * present and competent"), or the office id that failed (for
 * tooltips like "Magistrate vacant").
 */
public final class KingdomCapabilityEvaluator {

    /** Threshold used by {@link #isCompetent}: holder must clear minLevel. */
    public static final float COMPETENCE_FLOOR = 1.0f;

    /**
     * Capability → list of (office id, isKing) pairs that can
     * satisfy it. King is always first in each list so the result
     * prefers reporting "satisfied by King" over a delegate.
     *
     * <p>A delegated entry whose office id equals
     * {@link OfficeRegistry#KINGDOM_KING} is skipped (no point
     * checking King twice).
     */
    private static final Map<KingdomCapability, List<String>> SATISFIERS = buildTable();

    private KingdomCapabilityEvaluator() {}

    /**
     * Evaluates {@code capability} against {@code kingdom}. Returns
     * a {@link Result} with the allow/deny decision, the
     * satisfying office id (when allowed), the reason, and a snapshot
     * of every contributing office for GUI display.
     */
    public static Result evaluate(ServerLevel level, VillageSavedData data,
                                  Kingdom kingdom, KingdomCapability capability) {
        if (kingdom == null || capability == null) {
            return Result.deny(capability, "no kingdom or capability");
        }
        // Track D3.4b — VASSALAGE blocks DECLARE_WAR (vassals can
        // not declare war independently of their overlord).
        if (capability == KingdomCapability.DECLARE_WAR && kingdom.isVassal()) {
            return Result.deny(capability,
                    "vassal kingdoms cannot declare war independently");
        }
        OfficeState offices = kingdom.getOffices();
        List<String> satisfiers = SATISFIERS.getOrDefault(capability, List.of());

        // Walk the satisfier list in order — first competent wins.
        List<OfficeReport> reports = new ArrayList<>();
        for (String officeId : satisfiers) {
            OfficeReport report = inspect(level, kingdom, offices, officeId);
            reports.add(report);
            if (report.competent()) {
                return Result.allow(capability, officeId, report, reports);
            }
        }
        // No satisfier was competent.
        String reason = composeDenialReason(reports);
        return Result.deny(capability, reason, reports);
    }

    /**
     * Convenience for callers that only need the bool.
     */
    public static boolean canExercise(ServerLevel level, VillageSavedData data,
                                      Kingdom kingdom, KingdomCapability capability) {
        return evaluate(level, data, kingdom, capability).allowed();
    }

    // ── Office inspection ──────────────────────────────────────────────────

    /**
     * Inspects a single office holding and returns its competence
     * status. Called for each satisfier per capability.
     */
    private static OfficeReport inspect(ServerLevel level, Kingdom kingdom,
                                        OfficeState offices, String officeId) {
        OfficeHolding holding = offices.get(officeId).orElse(null);
        OfficeDefinition def = OfficeRegistry.get(officeId);
        if (def == null) {
            return new OfficeReport(officeId, "(unknown office)", false, false,
                    0, 0f, "office definition missing");
        }
        if (holding == null || holding.isVacant()) {
            float mult = def.competence().computeMultiplier(0); // vacancy penalty
            return new OfficeReport(officeId, def.displayName(), false, false,
                    0, mult, "vacant");
        }

        // Player-held King is sovereign and always competent.
        if (holding.holderIsPlayer()
                && OfficeRegistry.KINGDOM_KING.equals(officeId)) {
            return new OfficeReport(officeId, def.displayName(), true, true,
                    100, 1.0f, "held by player ruler");
        }

        // NPC holder — read primary-skill level if loaded.
        UUID npcId = holding.holderNpcId().orElse(null);
        if (npcId == null) {
            return new OfficeReport(officeId, def.displayName(), true, false,
                    0, 1.0f, "held by player (capability check requires NPC skill)");
        }
        TownspersonMob npc = TownspersonMob.findByUUID(level, npcId).orElse(null);
        if (npc == null) {
            // Holder not loaded — treat as competent at minLevel for
            // the purpose of capability checks. Otherwise capabilities
            // would flicker as chunks load and unload.
            return new OfficeReport(officeId, def.displayName(), true, true,
                    def.competence().minLevel(), 1.0f, "held by NPC (offline; assumed competent)");
        }

        Skill primary = def.competence().primarySkill();
        int level_ = npc.getSkills().getLevel(primary);
        float mult = def.competence().computeMultiplier(level_);
        boolean competent = mult >= COMPETENCE_FLOOR;
        String reason = competent
                ? "competent (" + primary.name() + " " + level_ + ")"
                : "under-skilled (" + primary.name() + " " + level_
                        + " < " + def.competence().minLevel() + ")";
        return new OfficeReport(officeId, def.displayName(), true, competent,
                level_, mult, reason);
    }

    private static String composeDenialReason(List<OfficeReport> reports) {
        if (reports.isEmpty()) return "no qualifying office";
        StringBuilder sb = new StringBuilder("no competent satisfier — ");
        for (int i = 0; i < reports.size(); i++) {
            if (i > 0) sb.append(", ");
            OfficeReport r = reports.get(i);
            sb.append(r.displayName()).append(": ").append(r.reason());
        }
        return sb.toString();
    }

    // ── Capability table ───────────────────────────────────────────────────

    private static Map<KingdomCapability, List<String>> buildTable() {
        String K = OfficeRegistry.KINGDOM_KING;
        Map<KingdomCapability, List<String>> m = new LinkedHashMap<>();
        m.put(KingdomCapability.PASS_LAW,
                List.of(K, OfficeRegistry.KINGDOM_MAGISTRATE));
        m.put(KingdomCapability.ISSUE_DECREE,
                List.of(K, OfficeRegistry.KINGDOM_CHANCELLOR));
        m.put(KingdomCapability.DECLARE_WAR,
                List.of(K, OfficeRegistry.KINGDOM_GENERAL));
        m.put(KingdomCapability.LEVY_TROOPS,
                List.of(K, OfficeRegistry.KINGDOM_GENERAL));
        m.put(KingdomCapability.DRAFT_TREATY,
                List.of(K, OfficeRegistry.KINGDOM_DIPLOMAT));
        m.put(KingdomCapability.INTRIGUE_FOREIGN,
                List.of(K, OfficeRegistry.KINGDOM_SPYMASTER));
        m.put(KingdomCapability.INVESTIGATE_CRIME,
                List.of(K, OfficeRegistry.KINGDOM_MAGISTRATE));
        m.put(KingdomCapability.ISSUE_CURRENCY,
                List.of(K, OfficeRegistry.KINGDOM_TREASURER));
        // Track D3.6.5 — CONVERT_PROVINCE: King OR Diplomat OR
        // Treasurer (per prompt; OR-semantics matches D3.3b).
        m.put(KingdomCapability.CONVERT_PROVINCE,
                List.of(K, OfficeRegistry.KINGDOM_DIPLOMAT,
                        OfficeRegistry.KINGDOM_TREASURER));
        return m;
    }

    /** Capability satisfier list (read-only). */
    public static List<String> satisfiersFor(KingdomCapability capability) {
        return SATISFIERS.getOrDefault(capability, List.of());
    }

    // ── Result types ───────────────────────────────────────────────────────

    /**
     * Result of an evaluation. {@code allowed} = true means the
     * capability can be exercised right now; {@code satisfyingOfficeId}
     * names which office cleared the gate. When denied,
     * {@code reason} is a one-line GUI tooltip-friendly string.
     * {@code allReports} carries every office that was inspected,
     * for richer UI surfacing.
     */
    public record Result(KingdomCapability capability,
                         boolean allowed,
                         Optional<String> satisfyingOfficeId,
                         String reason,
                         List<OfficeReport> allReports) {

        public static Result allow(KingdomCapability cap, String officeId,
                                   OfficeReport satisfier,
                                   List<OfficeReport> reports) {
            String reason = "satisfied by " + satisfier.displayName()
                    + " (" + satisfier.reason() + ")";
            return new Result(cap, true, Optional.of(officeId), reason,
                    List.copyOf(reports));
        }

        public static Result deny(KingdomCapability cap, String reason,
                                  List<OfficeReport> reports) {
            return new Result(cap, false, Optional.empty(),
                    reason == null ? "denied" : reason,
                    reports == null ? List.of() : List.copyOf(reports));
        }

        public static Result deny(KingdomCapability cap, String reason) {
            return deny(cap, reason, List.of());
        }
    }

    /**
     * Per-office snapshot used by {@link Result}. Independent of
     * the holder; safe to render in client-side GUI.
     *
     * @param officeId    canonical office id
     * @param displayName human-readable office name
     * @param held        true when the holding is non-vacant
     * @param competent   true when the holder clears the competence floor
     * @param skillLevel  holder's primary-skill level (0 if vacant or unknown)
     * @param multiplier  competence multiplier (used for tooltips)
     * @param reason      one-line status: "vacant", "competent (LITERACY 75)", etc.
     */
    public record OfficeReport(String officeId, String displayName,
                               boolean held, boolean competent,
                               int skillLevel, float multiplier,
                               String reason) {}
}
