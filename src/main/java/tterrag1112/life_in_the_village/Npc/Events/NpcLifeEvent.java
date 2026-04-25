package tterrag1112.life_in_the_village.Npc.Events;

import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

import java.util.UUID;

/**
 * Sealed event hierarchy fired through {@link NpcLifeEventBus}. Each
 * concrete record represents one kind of in-world life event that
 * Phase 1 dispatchers (memory / mood / trait drift) react to.
 *
 * <p>Spec: {@code docs/npc_redesign/10-phase1-integration.md}, sections
 * "Event source inventory" and "Event dispatch pattern". The list below
 * matches the spec's enumerated record forms; the {@code goalType}
 * fields on {@link GoalCompleted}/{@link GoalFailed} are typed
 * {@code String} as a placeholder until Phase 1 task 07 ships
 * {@code LifeGoal}.</p>
 */
public sealed interface NpcLifeEvent {

    /** Returns the primary {@link TownspersonMob} the event is about. */
    TownspersonMob subject();

    // ── Combat / rescue ────────────────────────────────────────────────────

    /** NPC took hostile damage. {@code attacker} may be a player or NPC UUID. */
    record Attacked(TownspersonMob subject, UUID attackerId,
                    boolean attackerIsPlayer, float amount) implements NpcLifeEvent {}

    /** NPC was rescued from death (HP &le; 1 → healed by external actor). */
    record Rescued(TownspersonMob subject, UUID rescuerId,
                   boolean rescuerIsPlayer) implements NpcLifeEvent {}

    /** NPC saved another (player or NPC). */
    record SavedSomeone(TownspersonMob subject, UUID savedId,
                        boolean savedIsPlayer) implements NpcLifeEvent {}

    /** NPC saw someone die nearby. {@code relation} flags kin/friend/rival. */
    record WitnessedDeath(TownspersonMob subject, UUID deceasedId,
                          RelationshipType relation) implements NpcLifeEvent {}

    /** NPC survived a battle without dying. Counted by drift dispatcher. */
    record SurvivedBattle(TownspersonMob subject) implements NpcLifeEvent {}

    // ── Trade / economic ───────────────────────────────────────────────────

    /** Trade completed with {@code partnerId} (usually a player). */
    record Trade(TownspersonMob subject, UUID partnerId,
                 boolean partnerIsPlayer, ItemStack item, long amount,
                 boolean notable) implements NpcLifeEvent {}

    /** Gift received from {@code giverId}; appropriateness flags the response. */
    record GiftReceived(TownspersonMob subject, UUID giverId,
                        boolean giverIsPlayer, ItemStack item,
                        GiftAppropriateness appropriateness) implements NpcLifeEvent {}

    /** NPC handed a gift to someone (small positive memory + mood). */
    record GaveGift(TownspersonMob subject, UUID recipientId,
                    boolean recipientIsPlayer, ItemStack item) implements NpcLifeEvent {}

    /** NPC was hired by an employer (first-time-hired flag drives mood). */
    record Hired(TownspersonMob subject, UUID employerId,
                 boolean firstTime) implements NpcLifeEvent {}

    /** NPC was fired from a job. */
    record Fired(TownspersonMob subject, UUID employerId) implements NpcLifeEvent {}

    /** NPC promoted within an org or office. */
    record Promoted(TownspersonMob subject, String role) implements NpcLifeEvent {}

    /** NPC demoted. */
    record Demoted(TownspersonMob subject, String role) implements NpcLifeEvent {}

    // ── Social ─────────────────────────────────────────────────────────────

    record Insulted(TownspersonMob subject, UUID insulterId,
                    boolean insulterIsPlayer, boolean inPublic) implements NpcLifeEvent {}

    record Complimented(TownspersonMob subject, UUID complimenterId,
                        boolean complimenterIsPlayer,
                        boolean matchedTrait) implements NpcLifeEvent {}

    record Defended(TownspersonMob subject, UUID defenderId,
                    boolean defenderIsPlayer) implements NpcLifeEvent {}

    record SharedHardship(TownspersonMob subject, UUID otherId,
                          String hardshipKey) implements NpcLifeEvent {}

    record SharedFestival(TownspersonMob subject, UUID otherId,
                          UUID eventId) implements NpcLifeEvent {}

    record TaughtSkill(TownspersonMob subject, UUID studentId,
                       Skill skill) implements NpcLifeEvent {}

    record LearnedSkill(TownspersonMob subject, UUID teacherId,
                        Skill skill) implements NpcLifeEvent {}

    // ── Family / household ─────────────────────────────────────────────────

    record Married(TownspersonMob subject, UUID spouseId) implements NpcLifeEvent {}

    record BirthInFamily(TownspersonMob subject, UUID childId) implements NpcLifeEvent {}

    /**
     * Family member died. {@code relation} is the relation of {@code deceasedId}
     * to {@code subject} (e.g. SPOUSE, KIN — for parent/sibling/child).
     */
    record FamilyDeath(TownspersonMob subject, UUID deceasedId,
                       FamilyRole relation) implements NpcLifeEvent {}

    /** NPC's age crossed a life-stage boundary. */
    record LifeStageAdvanced(TownspersonMob subject, String oldStage,
                             String newStage) implements NpcLifeEvent {}

    // ── Goals (life goals from doc 07; `goalType` is a String placeholder
    //          until task 07 ships the LifeGoal type — this session is bus-
    //          first per the user prompt) ─────────────────────────────────

    /** {@code importance} is 1..10; ≥ 7 fires the heavy-completion paths. */
    record GoalCompleted(TownspersonMob subject, String goalType,
                         int importance) implements NpcLifeEvent {}

    record GoalFailed(TownspersonMob subject, String goalType,
                      String reason) implements NpcLifeEvent {}

    record GoalAbandoned(TownspersonMob subject, String goalType) implements NpcLifeEvent {}
}
