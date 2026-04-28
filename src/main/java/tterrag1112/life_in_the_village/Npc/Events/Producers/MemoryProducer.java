package tterrag1112.life_in_the_village.Npc.Events.Producers;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.GiftAppropriateness;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Events.RelationshipType;
import tterrag1112.life_in_the_village.Npc.Memory.MemoryType;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemory;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemoryLog;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Npc.Traits.TraitVector;

import java.util.List;
import java.util.UUID;

/**
 * Phase 1 dispatcher that converts {@link NpcLifeEvent}s into entries on
 * the subject's {@link NpcMemoryLog}. Initial values come from the spec
 * table in {@code 10-phase1-integration.md} ("Event source inventory");
 * the trait-modulation table in section "Trait-modulated memory values"
 * is applied via {@link #modulate}.
 *
 * <p>Some events do not produce memories (the spec table marks them
 * "—"); those branches are no-ops here.</p>
 */
public final class MemoryProducer implements EventDispatcher {

    @Override public String name() { return "memory"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        switch (event) {
            // ── Combat / rescue ────────────────────────────────────────────
            case NpcLifeEvent.Attacked e ->
                    record(e.subject(), MemoryType.VICTIM_OF_CRIME_BY,
                            e.attackerId(), 60, "Attacked");

            case NpcLifeEvent.Rescued e ->
                    record(e.subject(), MemoryType.SAVED_BY,
                            e.rescuerId(), 95, "Rescued");

            case NpcLifeEvent.SavedSomeone e ->
                    record(e.subject(), MemoryType.SAVED,
                            e.savedId(), 85, "Saved someone");

            case NpcLifeEvent.WitnessedDeath e ->
                    record(e.subject(), MemoryType.WITNESSED_DEATH_OF,
                            e.deceasedId(), witnessedDeathValue(e.relation()),
                            "Witnessed death (" + e.relation().name() + ")");

            // SurvivedBattle has no memory entry (spec: "—").
            case NpcLifeEvent.SurvivedBattle ignored -> { /* trait drift only */ }

            // ── Trade / economic ───────────────────────────────────────────
            case NpcLifeEvent.Trade e ->
                    record(e.subject(), MemoryType.TRADED_WITH,
                            e.partnerId(), e.notable() ? 15 : 8, "Traded");

            case NpcLifeEvent.GiftReceived e -> {
                int base = switch (e.appropriateness()) {
                    case FAVORITE -> 35;
                    case APPROPRIATE -> 20;
                    case OFF_BASE -> 12;
                    case INSULTING -> -1; // sentinel: see below
                };
                if (e.appropriateness() == GiftAppropriateness.INSULTING) {
                    // Insulting gifts produce an INSULTED_BY memory instead.
                    record(e.subject(), MemoryType.INSULTED_BY,
                            e.giverId(), 25, "Insulting gift");
                } else {
                    record(e.subject(), MemoryType.RECEIVED_GIFT,
                            e.giverId(), base, "Gift received");
                }
            }

            case NpcLifeEvent.GaveGift e ->
                    record(e.subject(), MemoryType.GAVE_GIFT,
                            e.recipientId(), 10, "Gave gift");

            // Hired/Fired/Promoted/Demoted have no memory entries (spec: "—").
            case NpcLifeEvent.Hired ignored -> {}
            case NpcLifeEvent.Fired ignored -> {}
            case NpcLifeEvent.Promoted ignored -> {}
            case NpcLifeEvent.Demoted ignored -> {}

            // ── Social ─────────────────────────────────────────────────────
            case NpcLifeEvent.Insulted e ->
                    record(e.subject(), MemoryType.INSULTED_BY,
                            e.insulterId(), e.inPublic() ? 50 : 25,
                            e.inPublic() ? "Public insult" : "Insulted");

            case NpcLifeEvent.Complimented e ->
                    record(e.subject(), MemoryType.COMPLIMENTED_BY,
                            e.complimenterId(), e.matchedTrait() ? 15 : 8,
                            e.matchedTrait() ? "Compliment matching trait" : "Compliment");

            case NpcLifeEvent.Defended e ->
                    record(e.subject(), MemoryType.DEFENDED_BY,
                            e.defenderId(), 55, "Defended in dispute");

            case NpcLifeEvent.SharedHardship e ->
                    record(e.subject(), MemoryType.SHARED_HARDSHIP,
                            e.otherId(), 65,
                            "Shared hardship: " + (e.hardshipKey() == null ? "" : e.hardshipKey()));

            case NpcLifeEvent.SharedFestival e ->
                    record(e.subject(), MemoryType.SHARED_FESTIVAL,
                            e.otherId(), 18, "Shared festival");

            case NpcLifeEvent.LearnedSkill e ->
                    record(e.subject(), MemoryType.TAUGHT_BY,
                            e.teacherId(), 30, "Learned " + e.skill().name());

            case NpcLifeEvent.TaughtSkill e ->
                    record(e.subject(), MemoryType.TAUGHT,
                            e.studentId(), 20, "Taught " + e.skill().name());

            // Family events leave their data on FamilyComponent; no memory entry
            // (spec: "—" for the memory column on every family-life row).
            case NpcLifeEvent.Married ignored -> {}
            case NpcLifeEvent.BirthInFamily ignored -> {}
            case NpcLifeEvent.FamilyDeath ignored -> {}
            case NpcLifeEvent.LifeStageAdvanced ignored -> {}

            // Goal events produce mood changes but no memory entry (spec
            // notes village-history book for high-importance completions —
            // that's a Phase 4 doc 30 concern, not a memory entry).
            case NpcLifeEvent.GoalCompleted ignored -> {}
            case NpcLifeEvent.GoalFailed ignored -> {}
            case NpcLifeEvent.GoalAbandoned ignored -> {}

            // ── Relationship boundary transitions (Phase 2 task 11) ────────
            // Fire a memory entry on "breakup" transitions where a positive
            // bond turns neutral or negative. Boundary changes that stay
            // positive (ACQUAINTANCE→FRIEND etc.) are silent — those are
            // organic growth, not narrative moments.
            case NpcLifeEvent.RelationshipBoundaryCrossed e -> {
                if (e.oldMode().isPositive() && !e.newMode().isPositive()) {
                    // Treat as INSULTED_BY — it's the closest spec memory
                    // type for "this person let me down". Phase 5 may add
                    // a dedicated BREAKUP / FRIENDSHIP_LOST type.
                    record(e.subject(), MemoryType.INSULTED_BY,
                            e.otherId(), 30, "Relationship breakup");
                }
            }

            // ── Letter received (Phase 2 task 18) ──────────────────────────
            // RECEIVED_LETTER memory; value scales with special tag.
            case NpcLifeEvent.LetterReceived e -> {
                int value = switch (e.content().special().orElse(null)) {
                    case null -> 30;
                    case LOVE_LETTER, APOLOGY -> 60;
                    case THREAT -> 70;
                    case ANNOUNCEMENT -> 25;
                    case CONTRACT, INTRODUCTION -> 40;
                };
                String summary = "Letter from " + e.content().authorName();
                record(e.subject(), MemoryType.RECEIVED_LETTER,
                        e.content().authorId(), value, summary);
            }
            default -> throw new IllegalStateException("Unexpected value: " + event);
        }
    }

    private static int witnessedDeathValue(RelationshipType relation) {
        return switch (relation) {
            case KIN, SPOUSE -> 100;       // pinned
            case CLOSE_FRIEND -> 80;       // pinned
            case FRIEND -> 60;
            case RIVAL -> 60;
            default -> 30;
        };
    }

    // ── Producer API (mirrors the spec section "Producer API") ─────────────

    /**
     * Records a single-participant memory on {@code npc}, applying trait
     * modulation. Equivalent to the spec's {@code MemoryProducer.record}.
     */
    public static NpcMemory record(TownspersonMob npc, MemoryType type,
                                   UUID participant, int baseValue,
                                   String summary) {
        return record(npc, type,
                participant == null ? List.of() : List.of(participant),
                baseValue, summary);
    }

    public static NpcMemory record(TownspersonMob npc, MemoryType type,
                                   List<UUID> participants, int baseValue,
                                   String summary) {
        if (npc == null || npc.level() == null) return null;
        if (!(npc.level() instanceof ServerLevel sl)) return null;
        int modulated = modulate(type, baseValue, npc.getTraitVector());
        NpcMemory memory = NpcMemory.create(type, participants,
                sl.getGameTime(), modulated, summary);
        npc.getMemory().add(memory);
        return memory;
    }

    /** Applies the boost via the spec's standard reminder formula. */
    public static void refresh(TownspersonMob npc, UUID memoryId) {
        if (npc == null) return;
        npc.getMemory().findById(memoryId).ifPresent(m -> {
            float boost = NpcMemoryLog.standardReminderBoost(m);
            npc.getMemory().refresh(memoryId, boost);
        });
    }

    /**
     * Trait-modulation table from the spec
     * ({@code 10-phase1-integration.md} → "Trait-modulated memory values").
     * Returned value is clamped to {@code [1, 100]} so it stays a valid
     * {@code initialValue}.
     */
    public static int modulate(MemoryType type, int baseValue, TraitVector traits) {
        float bonus = switch (type) {
            case RECEIVED_GIFT       -> 0.2f * traits.get(TraitAxis.GENEROSITY);
            case INSULTED_BY         -> -0.3f * traits.get(TraitAxis.TEMPERANCE);
            case SAVED_BY            -> 0.2f * traits.get(TraitAxis.COMPASSION);
            case VICTIM_OF_CRIME_BY  -> -0.2f * traits.get(TraitAxis.TEMPERANCE);
            case DEFENDED_BY         -> 0.3f * traits.get(TraitAxis.COMPASSION);
            case COMPLIMENTED_BY     -> 0.2f * traits.get(TraitAxis.SOCIABILITY);
            default                  -> 0f;
        };
        int modulated = Math.round(baseValue * (1f + bonus));
        return Math.max(1, Math.min(100, modulated));
    }
}
