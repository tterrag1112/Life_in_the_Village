package tterrag1112.life_in_the_village.Npc.Relations;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Phase 2 seeding hooks for the NPC↔NPC relationship ledger. Listens
 * to the bus for a small set of life events that imply a starting
 * relationship — birth, adulthood, marriage — and seeds entries on
 * both sides.
 *
 * <p>Spec: {@code 11-npc-relationship-ledger.md} (section "Seeded
 * relationships" line 154 + section "Inherited grudges" line 222).
 * Workplace seeding is exposed as a static helper called from the
 * existing assignment paths when they wire it in.</p>
 */
public final class RelationshipSeeder implements EventDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Seed score for newborn / household member pair. */
    public static final int HOUSEHOLD_SEED = 40;
    /** Seed score for same-village peers at adulthood. */
    public static final int VILLAGE_SEED = 15;
    /** Seed score for new workmate. */
    public static final int WORKPLACE_SEED = 5;
    /** Number of random same-village peers seeded at adulthood. */
    public static final int VILLAGE_PEER_COUNT = 5;
    /** Inherited-grudge threshold — relative's score against target. */
    public static final int INHERITED_GRUDGE_TRIGGER = -85;
    /** Inherited-grudge seed score. */
    public static final int INHERITED_GRUDGE_SEED = -30;

    @Override public String name() { return "relationship_seeder"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        switch (event) {
            case NpcLifeEvent.BirthInFamily e -> seedHouseholdAroundBirth(e);
            case NpcLifeEvent.LifeStageAdvanced e -> {
                if ("ADULT".equalsIgnoreCase(e.newStage())) {
                    seedAdulthoodPeers(e.subject());
                    inheritGrudges(e.subject());
                }
            }
            // Marriage symmetric +60 is handled by RelationshipDispatcher;
            // no further seeding required here.
            default -> { /* no-op */ }
        }
    }

    // ── Birth / household ────────────────────────────────────────────────

    /**
     * On birth, seed +{@link #HOUSEHOLD_SEED} between the new child
     * and every existing household member. Spec line 158.
     */
    private void seedHouseholdAroundBirth(NpcLifeEvent.BirthInFamily e) {
        TownspersonMob parent = e.subject();
        if (parent == null) return;
        if (!(parent.level() instanceof ServerLevel sl)) return;
        TownspersonMob child = TownspersonMob.findByUUID(sl, e.childId()).orElse(null);
        if (child == null) return;

        // Parent ↔ child.
        seedPair(child, parent, HOUSEHOLD_SEED, RelationshipOrigin.BORN_SAME_HOUSEHOLD);

        // Parent's spouse, if any, shares the household — seed too.
        parent.getFamily().getSpouseId().ifPresent(spouseId -> {
            TownspersonMob spouse = TownspersonMob.findByUUID(sl, spouseId).orElse(null);
            if (spouse != null) {
                seedPair(child, spouse, HOUSEHOLD_SEED, RelationshipOrigin.BORN_SAME_HOUSEHOLD);
            }
        });
        // Existing siblings (other children of the parent).
        for (UUID siblingId : parent.getFamily().getChildrenIds()) {
            if (siblingId.equals(e.childId())) continue;
            TownspersonMob sibling = TownspersonMob.findByUUID(sl, siblingId).orElse(null);
            if (sibling != null) {
                seedPair(child, sibling, HOUSEHOLD_SEED,
                        RelationshipOrigin.BORN_SAME_HOUSEHOLD);
            }
        }
    }

    // ── Adulthood / village peers ───────────────────────────────────────

    /**
     * On the TEEN→ADULT transition, seed +{@link #VILLAGE_SEED} with
     * up to {@link #VILLAGE_PEER_COUNT} random same-village peers of
     * roughly similar age. Skips entries that already exist (the
     * ledger has more accurate state than this default).
     */
    public static void seedAdulthoodPeers(TownspersonMob npc) {
        if (npc == null) return;
        if (!(npc.level() instanceof ServerLevel sl)) return;
        String village = npc.getAssignedVillageName().orElse(null);
        if (village == null) return;

        // Gather candidate peers in the same village (loaded only).
        List<TownspersonMob> peers = sl.getEntitiesOfClass(
                TownspersonMob.class,
                npc.getBoundingBox().inflate(96),
                other -> !other.getUUID().equals(npc.getUUID())
                        && other.getAssignedVillageName().filter(village::equals).isPresent()
                        && Math.abs(other.getAge() - npc.getAge()) <= 6);
        if (peers.isEmpty()) return;

        // Random sampling without replacement.
        Collections.shuffle(peers, new java.util.Random(
                npc.getRandom().nextLong()));
        int count = Math.min(VILLAGE_PEER_COUNT, peers.size());
        for (int i = 0; i < count; i++) {
            TownspersonMob peer = peers.get(i);
            seedPair(npc, peer, VILLAGE_SEED, RelationshipOrigin.BORN_SAME_VILLAGE);
        }
    }

    // ── Workplace ────────────────────────────────────────────────────────

    /**
     * Seeds +{@link #WORKPLACE_SEED} between {@code newcomer} and every
     * existing worker at {@code buildingId}. Called by the workplace
     * assignment path when a new NPC takes a job (Phase 2 follow-up
     * wires this; the static helper is exposed now for that path).
     */
    public static void seedWorkplaceColleagues(TownspersonMob newcomer, UUID buildingId) {
        if (newcomer == null || buildingId == null) return;
        if (!(newcomer.level() instanceof ServerLevel sl)) return;
        var coworkers = sl.getEntitiesOfClass(
                TownspersonMob.class,
                newcomer.getBoundingBox().inflate(96),
                other -> !other.getUUID().equals(newcomer.getUUID())
                        && other.getAssignedBuildingId()
                                .filter(buildingId::equals)
                                .isPresent());
        for (TownspersonMob coworker : coworkers) {
            seedPair(newcomer, coworker, WORKPLACE_SEED,
                    RelationshipOrigin.WORKPLACE_COLLEAGUE);
        }
    }

    // ── Inherited grudges ────────────────────────────────────────────────

    /**
     * Spec line 224: at adulthood, if a household relative has a feud
     * at score ≤ -85 against another NPC, seed -30 toward that target.
     * Single-generation only — relatives' relatives don't count.
     */
    private static void inheritGrudges(TownspersonMob heir) {
        if (heir == null) return;
        if (!(heir.level() instanceof ServerLevel sl)) return;
        var family = heir.getFamily();
        List<UUID> householdMembers = new ArrayList<>();
        family.getSpouseId().ifPresent(householdMembers::add);
        family.getHeadOfHouseholdId().ifPresent(householdMembers::add);
        householdMembers.addAll(family.getChildrenIds());

        for (UUID relativeId : householdMembers) {
            if (relativeId.equals(heir.getUUID())) continue;
            TownspersonMob relative = TownspersonMob.findByUUID(sl, relativeId).orElse(null);
            if (relative == null) continue;
            for (var rel : relative.getNpcRelationships().all()) {
                if (rel.score() > INHERITED_GRUDGE_TRIGGER) continue;
                if (rel.otherId().equals(heir.getUUID())) continue;
                // Seed only if the heir doesn't already have a relationship
                // with this target (spec line 372: existing entries are
                // more accurate than defaults).
                if (heir.getNpcRelationships().get(rel.otherId()).isPresent()) continue;
                heir.getNpcRelationships().adjust(
                        rel.otherId(),
                        INHERITED_GRUDGE_SEED,
                        sl.getGameTime(),
                        RelationshipOrigin.MET_IN_CONFLICT);
            }
        }
    }

    // ── Common path ──────────────────────────────────────────────────────

    /**
     * Symmetric seed: only writes if the entry doesn't already exist
     * (spec line 372). Both directions get the same score.
     */
    public static void seedPair(TownspersonMob a, TownspersonMob b,
                                int score, RelationshipOrigin origin) {
        if (a == null || b == null || a == b) return;
        long tick = a.level().getGameTime();
        seedOne(a, b.getUUID(), score, origin, tick);
        seedOne(b, a.getUUID(), score, origin, tick);
    }

    private static void seedOne(TownspersonMob subject, UUID otherId,
                                int score, RelationshipOrigin origin, long tick) {
        var ledger = subject.getNpcRelationships();
        if (ledger.get(otherId).isPresent()) return;
        ledger.adjust(otherId, score, tick, origin);
    }
}
