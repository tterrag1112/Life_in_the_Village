package tterrag1112.life_in_the_village.Npc.Relations;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.GiftAppropriateness;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus;
import tterrag1112.life_in_the_village.Npc.Events.RelationshipType;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2 dispatcher (4th, after Memory / Mood / TraitDrift). Maps
 * {@link NpcLifeEvent} to {@link NpcRelationshipLedger} deltas per the
 * spec table at {@code 11-npc-relationship-ledger.md} (line 129).
 *
 * <p>Symmetry: most events update both sides — both A's ledger toward B
 * AND B's ledger toward A — using the same delta. Asymmetric outcomes
 * (one-sided crush, etc.) come from explicit one-sided callers
 * elsewhere; this dispatcher's policy is symmetric pair-bumps.</p>
 *
 * <p>Player→NPC events (where the "other party" is a player UUID and
 * not a TownspersonMob) bypass the NPC ledger entirely — the existing
 * Phase 0 {@code NpcRelationshipComponent} handles the player→NPC
 * direction independently.</p>
 */
public final class RelationshipDispatcher implements EventDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override public String name() { return "relationship"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        switch (event) {
            case NpcLifeEvent.Trade e -> {
                if (e.partnerIsPlayer()) return;
                applyPair(e.subject(), e.partnerId(), +2,
                        RelationshipOrigin.MET_THROUGH_TRADE);
            }
            case NpcLifeEvent.GiftReceived e -> {
                if (e.giverIsPlayer()) return;
                int delta = switch (e.appropriateness()) {
                    case FAVORITE -> +15;
                    case APPROPRIATE -> +8;
                    case OFF_BASE -> +3;
                    case INSULTING -> -8;
                };
                applyPair(e.subject(), e.giverId(), delta,
                        delta >= 0 ? RelationshipOrigin.MET_SOCIALLY
                                : RelationshipOrigin.MET_IN_CONFLICT);
            }
            case NpcLifeEvent.GaveGift e -> {
                // Subject GAVE the gift. Spec line 134-138 covers receiver
                // side; giver-side relationship growth is implicit (the
                // dispatcher mirrors the receiver-side delta automatically
                // via applyPair when the receiver's GiftReceived fires).
                // No-op here to avoid double-counting.
            }
            case NpcLifeEvent.Complimented e -> {
                if (e.complimenterIsPlayer()) return;
                int delta = e.matchedTrait() ? +5 : +1;
                applyPair(e.subject(), e.complimenterId(), delta,
                        RelationshipOrigin.MET_SOCIALLY);
            }
            case NpcLifeEvent.Insulted e -> {
                if (e.insulterIsPlayer()) return;
                int delta = e.inPublic() ? -20 : -12;
                applyPair(e.subject(), e.insulterId(), delta,
                        RelationshipOrigin.MET_IN_CONFLICT);
            }
            case NpcLifeEvent.Rescued e -> {
                if (e.rescuerIsPlayer()) return;
                applyPair(e.subject(), e.rescuerId(), +40,
                        RelationshipOrigin.MET_IN_CONFLICT);
                // Spec line 140: auto-promote to FRIEND minimum (+40).
                ledgerOf(e.subject()).ifPresent(l -> {
                    var current = l.get(e.rescuerId()).orElse(null);
                    if (current != null && current.score() < 40) {
                        l.set(e.rescuerId(), current.withScore(40));
                    }
                });
            }
            case NpcLifeEvent.SavedSomeone e -> {
                if (e.savedIsPlayer()) return;
                applyPair(e.subject(), e.savedId(), +25,
                        RelationshipOrigin.MET_IN_CONFLICT);
            }
            case NpcLifeEvent.WitnessedDeath e -> {
                // Witness's reaction depends on the relation tag. KIN /
                // CLOSE_FRIEND deaths produce shared-grief with anyone
                // who was also close to the deceased — but Phase 2 has
                // no fan-out across nearby NPCs without a separate scan.
                // Apply only the simple case here: rival's death → small
                // bittersweet positive-toward-other-witnesses left empty
                // for now. Spec line 143 is a niche corner; document.
            }
            case NpcLifeEvent.SharedFestival e -> {
                applyPair(e.subject(), e.otherId(), +3,
                        RelationshipOrigin.MET_SOCIALLY);
            }
            case NpcLifeEvent.SharedHardship e -> {
                applyPair(e.subject(), e.otherId(), +20,
                        RelationshipOrigin.MET_SOCIALLY);
            }
            case NpcLifeEvent.TaughtSkill e -> {
                applyPair(e.subject(), e.studentId(), +5,
                        RelationshipOrigin.WORKPLACE_COLLEAGUE);
            }
            case NpcLifeEvent.LearnedSkill e -> {
                applyPair(e.subject(), e.teacherId(), +8,
                        RelationshipOrigin.WORKPLACE_COLLEAGUE);
            }
            case NpcLifeEvent.Defended e -> {
                if (e.defenderIsPlayer()) return;
                applyPair(e.subject(), e.defenderId(), +25,
                        RelationshipOrigin.MET_IN_CONFLICT);
            }
            case NpcLifeEvent.Married e -> {
                applyPair(e.subject(), e.spouseId(), +60,
                        RelationshipOrigin.BORN_SAME_HOUSEHOLD);
            }
            case NpcLifeEvent.Attacked e -> {
                if (e.attackerIsPlayer()) return;
                applyPair(e.subject(), e.attackerId(), -40,
                        RelationshipOrigin.MET_IN_CONFLICT);
            }
            // Events the ledger doesn't react to are explicit no-ops so
            // the sealed switch stays exhaustive.
            case NpcLifeEvent.Hired ignored -> {}
            case NpcLifeEvent.Fired ignored -> {}
            case NpcLifeEvent.Promoted ignored -> {}
            case NpcLifeEvent.Demoted ignored -> {}
            case NpcLifeEvent.SurvivedBattle ignored -> {}
            case NpcLifeEvent.BirthInFamily ignored -> {}
            case NpcLifeEvent.FamilyDeath ignored -> {}
            case NpcLifeEvent.LifeStageAdvanced ignored -> {}
            case NpcLifeEvent.GoalCompleted ignored -> {}
            case NpcLifeEvent.GoalFailed ignored -> {}
            case NpcLifeEvent.GoalAbandoned ignored -> {}
            case NpcLifeEvent.RelationshipBoundaryCrossed ignored -> {}
            default -> throw new IllegalStateException("Unexpected value: " + event);
        }
    }

    /**
     * Symmetric pair adjust: writes {@code delta} on both NPC ledgers
     * (a→b and b→a). Resolves the {@code other} UUID to a loaded
     * TownspersonMob using {@code subject}'s level. If the other side
     * isn't a loaded NPC (e.g. unloaded chunk), the symmetric write is
     * silently skipped — events fired against unloaded NPCs are dropped
     * per spec line 367.
     */
    public static void applyPair(TownspersonMob subject, UUID otherId, int delta,
                                 RelationshipOrigin origin) {
        if (subject == null || otherId == null) return;
        long tick = subject.level().getGameTime();

        // Subject side first.
        applyOneAndFireBoundary(subject, otherId, delta, origin, tick);

        // Other side — resolve and mirror.
        if (!(subject.level() instanceof ServerLevel sl)) return;
        TownspersonMob other = TownspersonMob.findByUUID(sl, otherId).orElse(null);
        if (other == null) return;
        applyOneAndFireBoundary(other, subject.getUUID(), delta, origin, tick);
    }

    /** One-sided adjust used by debug commands and intentional asymmetric callers. */
    public static void applyOne(TownspersonMob subject, UUID otherId, int delta,
                                RelationshipOrigin origin) {
        if (subject == null || otherId == null) return;
        long tick = subject.level().getGameTime();
        applyOneAndFireBoundary(subject, otherId, delta, origin, tick);
    }

    private static void applyOneAndFireBoundary(TownspersonMob subject, UUID otherId,
                                                int delta, RelationshipOrigin origin,
                                                long tick) {
        var ledger = subject.getNpcRelationships();
        var prior = ledger.get(otherId).orElse(null);
        RelationshipMode oldMode = prior == null
                ? RelationshipMode.NEUTRAL
                : prior.mode();
        var updated = ledger.adjust(otherId, delta, tick, origin);
        if (updated == null) return;
        RelationshipMode newMode = updated.mode();
        if (oldMode != newMode) {
            try {
                NpcLifeEventBus.fire(new NpcLifeEvent.RelationshipBoundaryCrossed(
                        subject, otherId, oldMode, newMode));
            } catch (Throwable t) {
                LOGGER.debug("[Relations] boundary fire threw: {}", t.getMessage());
            }
        }
    }

    private static Optional<NpcRelationshipLedger> ledgerOf(TownspersonMob mob) {
        return mob == null ? Optional.empty()
                : Optional.ofNullable(mob.getNpcRelationships());
    }
}
