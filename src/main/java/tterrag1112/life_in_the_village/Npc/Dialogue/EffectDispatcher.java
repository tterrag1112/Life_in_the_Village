package tterrag1112.life_in_the_village.Npc.Dialogue;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeCategory;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeEntry;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeSource;
import tterrag1112.life_in_the_village.Npc.LifeGoal.LifeGoalType;
import tterrag1112.life_in_the_village.Npc.Memory.MemoryType;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemory;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;

import java.util.List;
import java.util.UUID;

/**
 * Routes a {@link DialogueEffect} to the relevant subsystem. Phase 1
 * dispatches inline, in declaration order — locked decision (spec is
 * silent on per-line effect ordering, so {@link DialogueLine#effects}
 * iteration order is the contract; documented in
 * {@code 08-dialogue-tree.md} Revision Notes).
 *
 * <p>Returns a small {@link Result} so the runner knows whether to end
 * the session early ({@link DialogueEffectType#END_CONVERSATION}).</p>
 */
public final class EffectDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    public record Result(boolean endSession) {
        public static final Result CONTINUE = new Result(false);
        public static final Result END = new Result(true);
    }

    private EffectDispatcher() {}

    public static Result apply(DialogueEffect effect, DialogueContext ctx) {
        if (effect == null) return Result.CONTINUE;
        try {
            return switch (effect.type()) {
                case MOOD_APPLY -> applyMood(effect, ctx);
                case MEMORY_REFRESH -> applyMemoryRefresh(effect, ctx);
                case MEMORY_CREATE -> applyMemoryCreate(effect, ctx);
                case KNOWLEDGE_SHARE -> applyKnowledgeShare(effect, ctx);
                case RELATIONSHIP_DELTA -> applyRelationshipDelta(effect, ctx);
                case GOAL_PROGRESS -> applyGoalProgress(effect, ctx);
                case TRAIT_DRIFT -> applyTraitDrift(effect, ctx);
                case OPEN_SCREEN -> {
                    // Phase 1 stub: log the request; Phase 3 wires real
                    // screen routing (trade UI, etc.).
                    LOGGER.debug("[Dialogue] OPEN_SCREEN requested: {}",
                            effect.paramOr("screen", "(unspecified)"));
                    yield Result.CONTINUE;
                }
                case END_CONVERSATION -> Result.END;
            };
        } catch (Throwable t) {
            // Per spec line 354 "Effect references a missing memory/etc.
            // — Fail silently; line still displays." Log at debug only.
            LOGGER.debug("[Dialogue] Effect '{}' failed: {}", effect.type(), t.getMessage());
            return Result.CONTINUE;
        }
    }

    public static void apply(List<DialogueEffect> effects, DialogueContext ctx,
                             java.util.function.Consumer<Boolean> endFlag) {
        boolean ended = false;
        for (DialogueEffect e : effects) {
            if (ended) break;
            ended = apply(e, ctx).endSession();
        }
        endFlag.accept(ended);
    }

    // ── Per-effect handlers ───────────────────────────────────────────────

    private static Result applyMood(DialogueEffect effect, DialogueContext ctx) {
        if (ctx.speaker() == null) return Result.CONTINUE;
        String name = effect.param("trigger");
        if (name == null) return Result.CONTINUE;
        MoodTrigger trigger;
        try { trigger = MoodTrigger.valueOf(name); }
        catch (IllegalArgumentException e) { return Result.CONTINUE; }
        long now = ctx.tick();
        String mag = effect.param("magnitude");
        if (mag != null) {
            try {
                ctx.speaker().getMood().applyWithRawMagnitude(trigger,
                        Integer.parseInt(mag), now);
            } catch (NumberFormatException ignore) { /* fall through */ }
        } else {
            ctx.speaker().getMood().apply(trigger, ctx.speaker().getTraitVector(), now);
        }
        return Result.CONTINUE;
    }

    private static Result applyMemoryRefresh(DialogueEffect effect, DialogueContext ctx) {
        if (ctx.speaker() == null) return Result.CONTINUE;
        UUID id = parseUuid(effect.param("memoryId"));
        if (id == null) return Result.CONTINUE;
        ctx.speaker().getMemory().findById(id).ifPresent(m ->
                ctx.speaker().getMemory().refresh(id,
                        tterrag1112.life_in_the_village.Npc.Memory.NpcMemoryLog.standardReminderBoost(m)));
        return Result.CONTINUE;
    }

    private static Result applyMemoryCreate(DialogueEffect effect, DialogueContext ctx) {
        if (ctx.speaker() == null) return Result.CONTINUE;
        MemoryType type;
        try { type = MemoryType.valueOf(effect.param("type")); }
        catch (Exception e) { return Result.CONTINUE; }
        UUID participant = resolveTargetUuid(effect.param("target"), ctx);
        if (participant == null) return Result.CONTINUE;
        int value = parseIntOr(effect.param("value"), 20);
        String summary = effect.paramOr("summary", "");
        NpcMemory mem = NpcMemory.create(type,
                List.of(participant), ctx.tick(), value, summary);
        ctx.speaker().getMemory().add(mem);
        return Result.CONTINUE;
    }

    /**
     * Picks the speaker's most-recent reliable knowledge entry on the
     * named topic and grants a one-hop-degraded copy to the listener.
     */
    private static Result applyKnowledgeShare(DialogueEffect effect, DialogueContext ctx) {
        if (ctx.speaker() == null) return Result.CONTINUE;
        TownspersonMob receiver = ctx.listener();
        if (receiver == null) return Result.CONTINUE; // can't share with player in v1
        String topic = effect.param("topic");
        if (topic == null) return Result.CONTINUE;
        var sourceEntry = ctx.speaker().getKnowledge().get(topic).orElse(null);
        if (sourceEntry == null) return Result.CONTINUE;
        var retold = ctx.speaker().getKnowledge()
                .degradeForRetelling(topic, ctx.speaker().getUUID());
        retold.ifPresent(r -> receiver.getKnowledge().add(r));
        return Result.CONTINUE;
    }

    private static Result applyRelationshipDelta(DialogueEffect effect, DialogueContext ctx) {
        if (ctx.speaker() == null) return Result.CONTINUE;
        UUID target = resolveTargetUuid(effect.param("target"), ctx);
        if (target == null) return Result.CONTINUE;
        int delta = parseIntOr(effect.param("delta"), 0);
        if (delta == 0) return Result.CONTINUE;
        ctx.speaker().getRelationships().adjust(target, delta);
        return Result.CONTINUE;
    }

    private static Result applyGoalProgress(DialogueEffect effect, DialogueContext ctx) {
        if (ctx.speaker() == null) return Result.CONTINUE;
        LifeGoalType type;
        try { type = LifeGoalType.valueOf(effect.param("goalType")); }
        catch (Exception e) { return Result.CONTINUE; }
        int delta = parseIntOr(effect.param("delta"), 1);
        ctx.speaker().getLifeGoals().activeByType(type).ifPresent(g ->
                ctx.speaker().getLifeGoals().updateProgress(g.goalId(), delta));
        return Result.CONTINUE;
    }

    private static Result applyTraitDrift(DialogueEffect effect, DialogueContext ctx) {
        if (ctx.speaker() == null) return Result.CONTINUE;
        TraitAxis axis;
        try { axis = TraitAxis.valueOf(effect.param("axis")); }
        catch (Exception e) { return Result.CONTINUE; }
        float delta = parseFloatOr(effect.param("delta"), 0f);
        if (delta == 0f) return Result.CONTINUE;
        tterrag1112.life_in_the_village.Npc.Events.Producers.TraitDriftProducer
                .drift(ctx.speaker(), axis, delta);
        return Result.CONTINUE;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static UUID parseUuid(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static UUID resolveTargetUuid(String s, DialogueContext ctx) {
        if (s == null) return null;
        // Prefer Target enum names; fall back to direct UUID parse.
        try {
            return Target.valueOf(s.toUpperCase(java.util.Locale.ROOT)).resolve(ctx);
        } catch (IllegalArgumentException e) {
            return parseUuid(s);
        }
    }

    private static int parseIntOr(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static float parseFloatOr(String s, float fallback) {
        if (s == null) return fallback;
        try { return Float.parseFloat(s); }
        catch (NumberFormatException e) { return fallback; }
    }
}
