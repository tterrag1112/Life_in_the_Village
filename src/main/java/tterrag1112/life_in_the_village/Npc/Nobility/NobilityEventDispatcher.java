package tterrag1112.life_in_the_village.Npc.Nobility;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Kingdom.Houses.House;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomModifier;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.2b — single dispatcher that hangs nobility behaviour off
 * {@code NpcLifeEventBus}. Reads + mutates {@link NobilityComponent}
 * and the kingdom-tier {@link House} record in response to:
 *
 * <ul>
 *   <li>{@link NpcLifeEvent.Married} — marriage-up rank step
 *       (lower-rank spouse advances toward the higher-rank one),
 *       prestige bump for both, dynasty house adoption (the
 *       unaffiliated spouse joins the affiliated spouse's house).
 *       Fires {@code marriage.high_rank} legitimacy modifier when
 *       either spouse is at rank ≥ 2 in their culture.</li>
 *   <li>{@link NpcLifeEvent.FamilyDeath} — when the deceased was a
 *       house head, runs {@link SuccessionResolver} against the
 *       house head's culture to pick an heir; transfers the
 *       headship via {@code House.withHead}; emits
 *       {@code succession.transition} as a short-lived legitimacy
 *       penalty.</li>
 * </ul>
 *
 * <p>Registered in {@code NpcLifeEventBus.registerDefaults()}. Pure
 * subscriber; doesn't fire any events itself (the modifier
 * emissions are direct {@code Kingdom.addModifier} calls).
 */
public final class NobilityEventDispatcher implements EventDispatcher {

    /** Rank threshold at which "marriage to" emits a legitimacy modifier. */
    private static final int HIGH_RANK_THRESHOLD = 2;

    /** Prestige bump on marriage. */
    private static final int MARRIAGE_PRESTIGE_BUMP = 3;

    /** Modifier durations in ticks (24000 = one in-game day). */
    private static final long ONE_DAY_TICKS    = 24000L;
    private static final long ONE_WEEK_TICKS   = ONE_DAY_TICKS * 7L;

    @Override public String name() { return "NobilityEventDispatcher"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        if (event instanceof NpcLifeEvent.Married m) {
            onMarried(m);
        } else if (event instanceof NpcLifeEvent.FamilyDeath fd) {
            onFamilyDeath(fd);
        }
    }

    // ── Marriage ────────────────────────────────────────────────────────────

    private void onMarried(NpcLifeEvent.Married m) {
        TownspersonMob a = m.subject();
        if (a == null || !(a.level() instanceof ServerLevel level)) return;
        // CourtingGoal fires Married twice (once per partner). To
        // avoid double-applying rank steps, prestige bumps, and
        // modifier emissions, only act when the subject's UUID is
        // lexicographically less than the spouse's. The other side's
        // event is a no-op.
        UUID spouseId = m.spouseId();
        if (spouseId == null || a.getUUID().compareTo(spouseId) > 0) return;
        TownspersonMob b = TownspersonMob.findByUUID(level, spouseId).orElse(null);
        if (b == null) return;

        NobilityComponent na = a.getNobility();
        NobilityComponent nb = b.getNobility();

        // Dynasty-house adoption: the unaffiliated spouse joins the
        // affiliated spouse's dynasty. If both are affiliated, the
        // higher-rank spouse's house wins; ties keep both unchanged
        // (each retains their natal house).
        adoptHouse(na, nb);

        // Marriage-up rank step: lower-rank spouse advances by one
        // toward the higher-rank one. Capped at the higher rank − 1
        // so the union doesn't equalise titles.
        marryUp(na, nb);

        // Both spouses gain a small prestige bump.
        na.addPrestige(MARRIAGE_PRESTIGE_BUMP);
        nb.addPrestige(MARRIAGE_PRESTIGE_BUMP);

        // Kingdom-level modifier when either spouse is high-rank.
        int higher = Math.max(na.getRankIndex(), nb.getRankIndex());
        if (higher >= HIGH_RANK_THRESHOLD) {
            VillageSavedData data = VillageSavedData.get(level);
            // Find the kingdom by either spouse's culture (best
            // effort — both spouses typically share a culture).
            kingdomOf(level, data, a).ifPresent(k -> k.addModifier(
                    KingdomModifier.expiring(
                            "marriage.high_rank",
                            "High-rank marriage in " + a.getNpcName()
                                    + " / " + b.getNpcName(),
                            0, +3,
                            level.getGameTime(),
                            ONE_WEEK_TICKS)));
        }
    }

    private static void adoptHouse(NobilityComponent na, NobilityComponent nb) {
        Optional<UUID> ha = na.getDynastyHouseId();
        Optional<UUID> hb = nb.getDynastyHouseId();
        if (ha.isPresent() && hb.isEmpty()) {
            nb.setDynastyHouseId(ha.get());
            return;
        }
        if (hb.isPresent() && ha.isEmpty()) {
            na.setDynastyHouseId(hb.get());
            return;
        }
        if (ha.isPresent() && hb.isPresent() && !ha.get().equals(hb.get())) {
            // Both affiliated to different houses: higher rank wins.
            if (na.getRankIndex() > nb.getRankIndex()) {
                nb.setDynastyHouseId(ha.get());
            } else if (nb.getRankIndex() > na.getRankIndex()) {
                na.setDynastyHouseId(hb.get());
            }
            // Equal rank: each keeps their natal house.
        }
    }

    private static void marryUp(NobilityComponent na, NobilityComponent nb) {
        int ra = na.getRankIndex();
        int rb = nb.getRankIndex();
        if (ra == rb) return;
        if (ra < rb) {
            na.setRankIndex(Math.min(ra + 1, rb - 1 < ra ? ra : rb - 1));
        } else {
            nb.setRankIndex(Math.min(rb + 1, ra - 1 < rb ? rb : ra - 1));
        }
    }

    // ── Family death (head-of-house transfer) ───────────────────────────────

    private void onFamilyDeath(NpcLifeEvent.FamilyDeath fd) {
        // Use the FamilyDeath signal to find each house whose
        // headUuid == deceasedId and run succession. The witness
        // (subject) is only used for ServerLevel access; the
        // deceased's own component is unreachable (entity gone).
        TownspersonMob witness = fd.subject();
        if (witness == null || !(witness.level() instanceof ServerLevel level)) return;
        UUID deceasedId = fd.deceasedId();
        if (deceasedId == null) return;
        VillageSavedData data = VillageSavedData.get(level);
        for (Kingdom k : data.getAllKingdoms()) {
            for (House h : k.getHouses()) {
                if (h.headUuid().filter(id -> id.equals(deceasedId)).isEmpty()) continue;
                runSuccession(level, data, k, h, deceasedId);
            }
        }
    }

    /**
     * Resolves an heir for the dynasty {@code house} when its
     * current head ({@code deceasedId}) has died. Updates the
     * house record in-place and emits a brief
     * {@code succession.transition} legitimacy penalty.
     */
    private static void runSuccession(ServerLevel level, VillageSavedData data,
                                      Kingdom kingdom, House house, UUID deceasedId) {
        TownspersonMob predecessor = TownspersonMob.findByUUID(level, deceasedId).orElse(null);
        // The predecessor entity is gone (we're handling its death),
        // so SuccessionResolver can't read its culture. Use the
        // kingdom's culture as a proxy — house members typically
        // share their kingdom's culture.
        var culture = CultureResolver.ofKingdom(kingdom);
        var rule = culture.kingdomDefaults().successionRule();

        Optional<TownspersonMob> heir = (predecessor != null)
                ? SuccessionResolver.pickHeir(level, predecessor, rule)
                : Optional.empty();

        if (heir.isPresent()) {
            kingdom.replaceHouse(house.withHead(heir.get().getUUID()));
            heir.get().getNobility().setDynastyHouseId(house.id());
            // Heir inherits the predecessor's rank (capped at culture max).
            int predRank = predecessor != null
                    ? predecessor.getNobility().getRankIndex() : 0;
            int max = NobilityRanks.maxRankIndex(culture.id());
            heir.get().getNobility().setRankIndex(Math.min(predRank, max));
        } else {
            // No heir resolvable; mark extinct (head cleared).
            kingdom.replaceHouse(house.withoutHead());
        }

        // Brief legitimacy dip during the transition.
        kingdom.addModifier(KingdomModifier.expiring(
                "succession.transition",
                "Succession of " + house.name(),
                0, -5,
                level.getGameTime(),
                ONE_DAY_TICKS));

        // Track D3.3 — event-driven province polygon invalidation
        // (the C-hybrid timing: weekly recompute + manor-event
        // invalidation). House-head turnover is the proxy for
        // "manor changes hands"; resetting the recompute tick to
        // -1L makes the next weekly tick fire on this kingdom
        // immediately.
        kingdom.setLastProvinceRecomputeTick(-1L);

        // Mark data dirty so the kingdom + house mutation persists.
        data.setDirty();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static Optional<Kingdom> kingdomOf(ServerLevel level, VillageSavedData data,
                                               TownspersonMob npc) {
        return npc.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .flatMap(v -> v.getKingdomId())
                .flatMap(data::getKingdomById);
    }
}
