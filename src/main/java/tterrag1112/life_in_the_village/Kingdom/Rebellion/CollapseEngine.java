package tterrag1112.life_in_the_village.Kingdom.Rebellion;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Kingdom.Audience.KingdomNewsfeed;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomModifier;
import tterrag1112.life_in_the_village.Kingdom.Provinces.Province;
import tterrag1112.life_in_the_village.Kingdom.Treaties.Treaty;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Track D3.6.2 — kingdom collapse engine.
 *
 * <h3>Trigger</h3>
 * Daily tick checks each kingdom's stability. When below
 * {@link tterrag1112.life_in_the_village.Cultures.RebellionThresholds#kingdomCollapse},
 * stamps a {@code COLLAPSE_TRACKER_MODIFIER_ID} modifier capturing
 * the tick the streak began. When that streak duration exceeds
 * {@code RebellionThresholds.collapseDurationTicks}, fires the
 * collapse.
 *
 * <h3>Effects</h3>
 * <ol>
 *   <li>Each province secedes into its own kingdom (via
 *       {@link SecessionExecutor#secede}) UNLESS it's the capital
 *       province AND its stability is above
 *       {@link #RUMP_CAPITAL_STABILITY_THRESHOLD} — in which case
 *       the original kingdom persists as a "rump" kingdom with
 *       only its capital village.</li>
 *   <li>All charters, treaties, and vassalage relations break
 *       (cascade via {@link Kingdom#breakTreaty} +
 *       {@link Kingdom#revokeCharter}).</li>
 *   <li>Orphaned villages (province-less but kingdom-tagged) get
 *       their {@code kingdomId} cleared so the D3.1 lazy claim
 *       resolution can absorb them.</li>
 *   <li>The collapsed kingdom is marked inactive (heraldry-only
 *       history record) — its villageIds and provinces emptied;
 *       a stability-zero flag set via the
 *       {@code "kingdom.collapsed"} modifier; saved as a
 *       history-only entity for newsfeed references.</li>
 * </ol>
 *
 * <p>Determinism seed: {@code (kingdomId, "collapse", gameDay)}.
 */
public final class CollapseEngine {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Modifier id stamped while a kingdom is in the collapse-clock state. */
    public static final String COLLAPSE_TRACKER_MODIFIER_ID = "collapse.tracker";

    /** Modifier id stamped on a successfully-collapsed kingdom (inactive marker). */
    public static final String COLLAPSED_MODIFIER_ID = "kingdom.collapsed";

    /**
     * Per the locked migration design: pre-D3.6 saves with kingdoms
     * already below the collapse threshold get a one-shot 30-day
     * +5 stability grace period before the collapse clock starts.
     * The {@code MARKER_ID} is permanent so re-applies are
     * idempotent across server restarts.
     */
    public static final String GRACE_MARKER_ID = "pre_d36_grace.applied";
    public static final String GRACE_BUFF_ID   = "pre_d36_grace.buff";
    public static final int    GRACE_STABILITY_BUFF = 5;
    public static final long   GRACE_DURATION_TICKS = 30L * 24000L;

    /**
     * Capital province must be at least this stability for the rump
     * kingdom to persist; below this, the capital becomes an
     * independent village too.
     */
    public static final int RUMP_CAPITAL_STABILITY_THRESHOLD = 25;

    private CollapseEngine() {}

    public static void dailyTick(ServerLevel level, VillageSavedData data, long tick) {
        for (Kingdom kingdom : new ArrayList<>(data.getAllKingdoms())) {
            if (kingdom.hasModifierWithId(COLLAPSED_MODIFIER_ID)) continue;
            var culture = CultureResolver.ofKingdom(kingdom);
            var thresholds = culture.kingdomDefaults().rebellionThresholds();
            int effectiveStability = kingdom.getStability()
                    + kingdom.stabilityModifierSum();
            boolean inDanger = effectiveStability < thresholds.kingdomCollapse();

            var tracker = kingdom.getModifiers().stream()
                    .filter(m -> m.id().equals(COLLAPSE_TRACKER_MODIFIER_ID))
                    .findFirst().orElse(null);

            if (!inDanger) {
                if (tracker != null) {
                    kingdom.removeModifier(COLLAPSE_TRACKER_MODIFIER_ID);
                    KingdomNewsfeed.append(kingdom, "collapse.recovered",
                            "Kingdom stability recovered above collapse threshold", tick);
                }
                continue;
            }

            if (tracker == null) {
                // Pre-D3.6 grace: kingdoms below threshold at first
                // detection get a 30-day +5 stability grace before
                // the collapse clock starts. Marker is permanent so
                // it never re-applies.
                if (!kingdom.hasModifierWithId(GRACE_MARKER_ID)) {
                    kingdom.addModifier(KingdomModifier.permanent(
                            GRACE_MARKER_ID,
                            "pre-D3.6 grace applied",
                            0, 0, tick));
                    kingdom.addModifier(KingdomModifier.expiring(
                            GRACE_BUFF_ID,
                            "Pre-D3.6 grace period (+5 stability)",
                            GRACE_STABILITY_BUFF, 0,
                            tick, GRACE_DURATION_TICKS));
                    KingdomNewsfeed.append(kingdom, "collapse.grace",
                            "Stability low at migration — 30-day grace applied", tick);
                    continue;
                }
                // Start the clock.
                kingdom.addModifier(KingdomModifier.expiring(
                        COLLAPSE_TRACKER_MODIFIER_ID,
                        "Kingdom approaching collapse",
                        -2, -2,
                        tick,
                        thresholds.collapseDurationTicks() + 24000L * 2));
                KingdomNewsfeed.append(kingdom, "collapse.clock_started",
                        "Stability has fallen dangerously low — collapse imminent without recovery", tick);
                continue;
            }

            long elapsed = tick - tracker.appliedAtTick();
            if (elapsed >= thresholds.collapseDurationTicks()) {
                collapse(level, data, kingdom, tick,
                        "stability below " + thresholds.kingdomCollapse()
                                + " for " + (elapsed / 24000L) + " days");
            }
        }
    }

    /** Forces a collapse immediately (used by debug + duration trigger). */
    public static void collapse(ServerLevel level, VillageSavedData data,
                                Kingdom kingdom, long tick, String reason) {
        if (kingdom.hasModifierWithId(COLLAPSED_MODIFIER_ID)) return;

        List<UUID> successorKingdomIds = new ArrayList<>();
        UUID rumpKingdomId = null;

        // Snapshot provinces and capital before mutations.
        List<Province> provinces = new ArrayList<>(kingdom.getProvinces());
        UUID capitalVillage = kingdom.getCapitalVillageId().orElse(null);
        Province capitalProvince = null;
        for (Province p : provinces) {
            if (capitalVillage != null && p.villageIds().contains(capitalVillage)) {
                capitalProvince = p;
                break;
            }
        }

        // If the capital province is healthy enough, KEEP this kingdom as the
        // rump. Otherwise everything secedes.
        boolean keepRump = capitalProvince != null
                && capitalProvince.stability() >= RUMP_CAPITAL_STABILITY_THRESHOLD;

        for (Province p : provinces) {
            if (keepRump && p == capitalProvince) continue;
            UUID newId = SecessionExecutor.secede(level, data, kingdom, p, tick,
                    "kingdom collapse — " + reason);
            if (newId != null) successorKingdomIds.add(newId);
        }

        if (keepRump) {
            // Strip everything except the capital village + rump heraldry +
            // history. Charters / treaties / nobles all break.
            cascadeBreakAll(kingdom, tick, "kingdom collapse");
            // Apply collapse modifier marker (rump retains identity but
            // gets a permanent legitimacy malus).
            kingdom.addModifier(KingdomModifier.permanent(
                    "collapse.rump",
                    "Rump kingdom — successor to collapsed " + kingdom.getName(),
                    -5, -20, tick));
            kingdom.removeModifier(COLLAPSE_TRACKER_MODIFIER_ID);
            kingdom.setStability(40);
            rumpKingdomId = kingdom.getId();
            KingdomNewsfeed.append(kingdom, "kingdom.rump",
                    kingdom.getName() + " survives as a rump kingdom amid collapse", tick);
        } else {
            // No rump — mark this kingdom inactive. Capital village
            // becomes orphaned along with everything else.
            for (UUID villageId : new ArrayList<>(kingdom.getVillageIds())) {
                data.getVillageById(villageId).ifPresent(v -> v.setKingdomId(null));
                kingdom.removeVillage(villageId);
            }
            cascadeBreakAll(kingdom, tick, "kingdom collapse");
            kingdom.addModifier(KingdomModifier.permanent(
                    COLLAPSED_MODIFIER_ID,
                    "Kingdom collapsed (history-only record)",
                    0, 0, tick));
            kingdom.setStability(0);
            kingdom.setLegitimacy(0);
            KingdomNewsfeed.append(kingdom, "kingdom.collapsed",
                    kingdom.getName() + " has collapsed", tick);
        }

        KingdomEventBus.fire(new KingdomEvent.KingdomCollapsed(
                kingdom.getId(), successorKingdomIds, rumpKingdomId, reason, tick));
        data.setDirty();
        LOGGER.info("[CollapseEngine] {} collapsed (reason: {}, successors: {}, rump: {})",
                kingdom.getName(), reason, successorKingdomIds.size(),
                rumpKingdomId != null);
    }

    /**
     * Breaks every active charter / treaty / vassalage relation on
     * the kingdom. Idempotent — already-broken treaties no-op.
     */
    private static void cascadeBreakAll(Kingdom kingdom, long tick, String reason) {
        // Revoke charters.
        for (var c : new ArrayList<>(kingdom.getCharters())) {
            if (c.active()) kingdom.revokeCharter(c.id(), tick);
        }
        // Break treaties on every party copy via this side; the
        // other side's KingdomActionPacket SET_RELATION mirror
        // path doesn't fire here, so loop the parties manually.
        for (Treaty t : new ArrayList<>(kingdom.getTreaties())) {
            if (t.broken()) continue;
            kingdom.breakTreaty(t.id(), kingdom.getId(), tick, reason);
        }
    }
}
