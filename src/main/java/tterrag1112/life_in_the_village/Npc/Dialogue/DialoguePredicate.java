package tterrag1112.life_in_the_village.Npc.Dialogue;

import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Entities.custom.NpcRelationshipComponent;
import tterrag1112.life_in_the_village.Npc.Memory.MemoryType;
import tterrag1112.life_in_the_village.Npc.Mood.MoodCategory;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Npc.LifeGoal.LifeGoalType;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.List;
import java.util.UUID;

/**
 * Boolean checks that drive {@link DialogueNode.Branch} traversal in a
 * dialogue tree. Sealed; every implementation is a record nested below.
 *
 * <p>Spec: {@code docs/npc_redesign/08-dialogue-tree.md} (sections
 * "DialoguePredicate" line 79 onward and "DialogueContext" line 107).
 * The 17 variants below match the spec's permits clause.</p>
 *
 * <p>Codec: deferred — Phase 1 trees are registered in code per spec
 * line 272. Phase 6 JSON migration adds polymorphic codecs.</p>
 */
public sealed interface DialoguePredicate
        permits DialoguePredicate.TraitGreater,
                DialoguePredicate.TraitLess,
                DialoguePredicate.MoodAtCategory,
                DialoguePredicate.HasMemoryOf,
                DialoguePredicate.RelationshipAtLeast,
                DialoguePredicate.KnowsTopic,
                DialoguePredicate.HasGoal,
                DialoguePredicate.SkillAtLeast,
                DialoguePredicate.IsDayPhase,
                DialoguePredicate.IsSeason,
                DialoguePredicate.IsWeather,
                DialoguePredicate.HasProfession,
                DialoguePredicate.IsFamilyRelated,
                DialoguePredicate.EventActive,
                DialoguePredicate.HasOffice,
                DialoguePredicate.And,
                DialoguePredicate.Or,
                DialoguePredicate.Not {

    boolean test(DialogueContext ctx);

    /** Display string for {@code /dialogue test-predicate} output. */
    default String describe() { return getClass().getSimpleName(); }

    // ── Trait / mood / skill ───────────────────────────────────────────────

    record TraitGreater(TraitAxis axis, float threshold) implements DialoguePredicate {
        @Override public boolean test(DialogueContext ctx) {
            if (ctx.speaker() == null) return false;
            return ctx.speaker().getTraitVector().get(axis) > threshold;
        }
    }

    record TraitLess(TraitAxis axis, float threshold) implements DialoguePredicate {
        @Override public boolean test(DialogueContext ctx) {
            if (ctx.speaker() == null) return false;
            return ctx.speaker().getTraitVector().get(axis) < threshold;
        }
    }

    /** True when the speaker's mood falls in {@code category} or a more extreme matching one. */
    record MoodAtCategory(MoodCategory category) implements DialoguePredicate {
        @Override public boolean test(DialogueContext ctx) {
            if (ctx.speaker() == null) return false;
            return ctx.speaker().getMood().category() == category;
        }
    }

    record SkillAtLeast(Skill skill, int level) implements DialoguePredicate {
        @Override public boolean test(DialogueContext ctx) {
            if (ctx.speaker() == null) return false;
            return ctx.speaker().getSkills().getLevel(skill) >= level;
        }
    }

    // ── Memory / knowledge / relationship ──────────────────────────────────

    /**
     * True when the speaker has a memory of {@code type} involving
     * the {@code target}'s UUID.
     */
    record HasMemoryOf(MemoryType type, Target target) implements DialoguePredicate {
        @Override public boolean test(DialogueContext ctx) {
            UUID id = ctx.resolveTarget(target);
            if (ctx.speaker() == null || id == null) return false;
            return ctx.speaker().getMemory().hasMemoryOf(type, id);
        }
    }

    record KnowsTopic(String topic) implements DialoguePredicate {
        @Override public boolean test(DialogueContext ctx) {
            if (ctx.speaker() == null) return false;
            return ctx.speaker().getKnowledge().knows(topic);
        }
    }

    /**
     * True when the speaker's per-player relationship score reaches
     * {@code minScore}. NPC-NPC targets always return false in Phase 1
     * (the relationship ledger lands in Phase 2); documented in
     * {@code 08-dialogue-tree.md} Revision Notes.
     */
    record RelationshipAtLeast(Target target, int minScore) implements DialoguePredicate {
        @Override public boolean test(DialogueContext ctx) {
            if (ctx.speaker() == null) return false;
            UUID id = ctx.resolveTarget(target);
            if (id == null) return false;
            NpcRelationshipComponent rel = ctx.speaker().getRelationships();
            return rel != null && rel.getDelta(id) >= minScore;
        }
    }

    // ── Goals / offices ────────────────────────────────────────────────────

    record HasGoal(LifeGoalType type) implements DialoguePredicate {
        @Override public boolean test(DialogueContext ctx) {
            if (ctx.speaker() == null) return false;
            return ctx.speaker().getLifeGoals().has(type);
        }
    }

    /** Empty {@code officeId} matches any office held by the speaker. */
    record HasOffice(String officeId) implements DialoguePredicate {
        @Override public boolean test(DialogueContext ctx) {
            if (ctx.speaker() == null || ctx.level() == null) return false;
            var matches = OfficeRegistry.findOfficesHeldBy(ctx.speaker().getUUID(), ctx.level());
            if (matches.isEmpty()) return false;
            if (officeId == null || officeId.isEmpty()) return true;
            return matches.stream().anyMatch(m -> officeId.equals(m.holding().officeId()));
        }
    }

    // ── Time / weather / season ────────────────────────────────────────────

    /** Named day phases sourced from {@link TownspersonMob}'s schedule helpers. */
    enum DayPhase { WORK, SOCIAL, SLEEP }

    record IsDayPhase(DayPhase phase) implements DialoguePredicate {
        @Override public boolean test(DialogueContext ctx) {
            TownspersonMob s = ctx.speaker();
            if (s == null) return false;
            return switch (phase) {
                case WORK -> s.isWorkTime();
                case SOCIAL -> s.isSocialTime();
                case SLEEP -> s.isSleepTime();
            };
        }
    }

    /**
     * Phase 1 stub: Minecraft has no native seasons and the mod doesn't
     * ship one yet. Always {@code false}; Phase 5 culture/season pass
     * implements detection.
     */
    record IsSeason(String season) implements DialoguePredicate {
        @Override public boolean test(DialogueContext ctx) { return false; }
    }

    enum Weather { CLEAR, RAIN, THUNDER }

    record IsWeather(Weather weather) implements DialoguePredicate {
        @Override public boolean test(DialogueContext ctx) {
            if (ctx.level() == null) return false;
            return switch (weather) {
                case CLEAR -> !ctx.level().isRaining() && !ctx.level().isThundering();
                case RAIN -> ctx.level().isRaining() && !ctx.level().isThundering();
                case THUNDER -> ctx.level().isThundering();
            };
        }
    }

    // ── Identity / family / events ─────────────────────────────────────────

    record HasProfession(Profession profession) implements DialoguePredicate {
        @Override public boolean test(DialogueContext ctx) {
            if (ctx.speaker() == null) return false;
            return ctx.speaker().getProfession() == profession;
        }
    }

    /** True when {@code target}'s UUID is on speaker's household with {@code role}. */
    record IsFamilyRelated(Target target, FamilyRole role) implements DialoguePredicate {
        @Override public boolean test(DialogueContext ctx) {
            if (ctx.speaker() == null) return false;
            UUID id = ctx.resolveTarget(target);
            if (id == null) return false;
            var family = ctx.speaker().getFamily();
            return switch (role) {
                case SPOUSE -> family.getSpouseId().filter(id::equals).isPresent();
                case CHILD -> family.getChildrenIds().contains(id);
                case HEAD -> family.getHeadOfHouseholdId().filter(id::equals).isPresent();
                default -> false;
            };
        }
    }

    /**
     * True when the speaker's village has any active event whose name
     * matches {@code eventName} (case-insensitive). Phase 1 matches by
     * name string; Phase 5 events-expanded pass introduces typed event ids.
     */
    record EventActive(String eventName) implements DialoguePredicate {
        @Override public boolean test(DialogueContext ctx) {
            TownspersonMob s = ctx.speaker();
            if (s == null || ctx.level() == null) return false;
            var villageName = s.getAssignedVillageName().orElse(null);
            if (villageName == null) return false;
            var data = tterrag1112.life_in_the_village.Networking.VillageSavedData.get(ctx.level());
            return data.getVillageByName(villageName)
                    .map(v -> data.getActiveEventsForVillage(v.getId())
                            .stream()
                            .anyMatch(ev -> (ev.isActive() || ev.isAnnounced())
                                    && eventName != null
                                    && eventName.equalsIgnoreCase(ev.getType().name())))
                    .orElse(false);
        }
    }

    // ── Combinators ────────────────────────────────────────────────────────

    record And(List<DialoguePredicate> all) implements DialoguePredicate {
        public And { all = List.copyOf(all); }
        @Override public boolean test(DialogueContext ctx) {
            for (var p : all) if (!p.test(ctx)) return false;
            return true;
        }
    }

    record Or(List<DialoguePredicate> any) implements DialoguePredicate {
        public Or { any = List.copyOf(any); }
        @Override public boolean test(DialogueContext ctx) {
            for (var p : any) if (p.test(ctx)) return true;
            return false;
        }
    }

    record Not(DialoguePredicate inner) implements DialoguePredicate {
        @Override public boolean test(DialogueContext ctx) { return !inner.test(ctx); }
    }

    // ── Construction helpers (so trees read cleanly in code) ──────────────

    static DialoguePredicate and(DialoguePredicate... ps) {
        return new And(List.of(ps));
    }
    static DialoguePredicate or(DialoguePredicate... ps) {
        return new Or(List.of(ps));
    }
    static DialoguePredicate not(DialoguePredicate p) { return new Not(p); }
}
