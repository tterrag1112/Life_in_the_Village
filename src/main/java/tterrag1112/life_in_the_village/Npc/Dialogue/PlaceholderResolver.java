package tterrag1112.life_in_the_village.Npc.Dialogue;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code {placeholder}} tokens in a {@link DialogueLine}'s
 * text using {@link DialogueContext}. Spec
 * {@code 08-dialogue-tree.md} section "Placeholder substitution"
 * (line 173).
 *
 * <p>Recognised tokens (matching the spec list):</p>
 * <ul>
 *   <li>{@code {speaker.name}}, {@code {speaker.profession}}</li>
 *   <li>{@code {listener.name}}, {@code {listener.profession}}</li>
 *   <li>{@code {village.name}}, {@code {kingdom.name}}</li>
 *   <li>{@code {weather}}, {@code {season}}, {@code {time_of_day}}</li>
 *   <li>{@code {memory.&lt;type&gt;}} — newest memory of type involving the listener</li>
 *   <li>{@code {goal.primary.narrative}} — speaker's primary goal narrative</li>
 *   <li>{@code {relationship.tier}} — stranger / acquaintance / friend / etc.</li>
 * </ul>
 *
 * <p>Unknown / unresolved tokens render as the empty string and emit a
 * single warning log entry per unique token (spec line 186 + line 360).</p>
 */
public final class PlaceholderResolver {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_.]+)}");

    private PlaceholderResolver() {}

    public static String resolve(String text, DialogueContext ctx) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String token = m.group(1);
            String replacement = lookup(token, ctx);
            if (replacement == null) {
                LOGGER.warn("[Dialogue] Unresolved placeholder '{}' in line: {}", token, text);
                replacement = "";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String lookup(String token, DialogueContext ctx) {
        return switch (token) {
            case "speaker.name" -> ctx.speaker() == null ? null : displayName(ctx.speaker());
            case "speaker.profession" -> ctx.speaker() == null ? null
                    : ctx.speaker().getProfession().name();
            case "listener.name" -> listenerName(ctx);
            case "listener.profession" -> ctx.listener() == null ? null
                    : ctx.listener().getProfession().name();
            case "village.name" -> ctx.speaker() == null ? null
                    : ctx.speaker().getAssignedVillageName().orElse("");
            case "kingdom.name" -> kingdomName(ctx);
            case "weather" -> ctx.level() == null ? null
                    : ctx.level().isThundering() ? "stormy"
                    : ctx.level().isRaining() ? "rainy" : "clear";
            case "season" -> "spring"; // Phase 5 stub
            case "time_of_day" -> dayPhaseLabel(ctx);
            case "goal.primary.narrative" -> primaryGoalNarrative(ctx);
            case "relationship.tier" -> relationshipTier(ctx);
            default -> {
                if (token.startsWith("memory.")) {
                    yield memoryDescription(ctx, token.substring("memory.".length()));
                }
                yield null;
            }
        };
    }

    private static String displayName(TownspersonMob npc) {
        var name = npc.getCustomName();
        return name != null ? name.getString() : npc.getName().getString();
    }

    private static String listenerName(DialogueContext ctx) {
        if (ctx.player() != null) return ctx.player().getGameProfile().name();
        if (ctx.listener() != null) return displayName(ctx.listener());
        return "stranger";
    }

    private static String kingdomName(DialogueContext ctx) {
        if (ctx.speaker() == null || ctx.level() == null) return null;
        var data = tterrag1112.life_in_the_village.Networking.VillageSavedData.get(ctx.level());
        return ctx.speaker().getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .flatMap(v -> data.getKingdomForVillage(v.getId()))
                .map(tterrag1112.life_in_the_village.Kingdom.Kingdom::getName)
                .orElse("");
    }

    private static String dayPhaseLabel(DialogueContext ctx) {
        if (ctx.speaker() == null) return null;
        if (ctx.speaker().isSleepTime()) return "night";
        if (ctx.speaker().isWorkTime()) return "midday";
        if (ctx.speaker().isSocialTime()) return "evening";
        return "morning";
    }

    private static String primaryGoalNarrative(DialogueContext ctx) {
        if (ctx.speaker() == null) return null;
        return ctx.speaker().getLifeGoals().primaryGoal()
                .map(g -> g.narrative() == null || g.narrative().isEmpty()
                        ? g.type().name() : g.narrative())
                .orElse("");
    }

    private static String relationshipTier(DialogueContext ctx) {
        if (ctx.speaker() == null) return null;
        var rel = ctx.speaker().getRelationships();
        if (rel == null) return "stranger";
        java.util.UUID id = ctx.listenerUuid();
        if (id == null) return "stranger";
        int score = rel.getDelta(id);
        if (score >= 60) return "close friend";
        if (score >= 40) return "friend";
        if (score >= 10) return "acquaintance";
        if (score <= -40) return "rival";
        if (score <= -10) return "disliked";
        return "stranger";
    }

    /**
     * Phase 1 stub: returns a short summary of the most-recent memory
     * of the named type involving the listener, or empty string.
     */
    private static String memoryDescription(DialogueContext ctx, String typeName) {
        if (ctx.speaker() == null) return "";
        try {
            var type = tterrag1112.life_in_the_village.Npc.Memory.MemoryType
                    .valueOf(typeName.toUpperCase(java.util.Locale.ROOT));
            var listenerId = ctx.listenerUuid();
            if (listenerId == null) return "";
            return ctx.speaker().getMemory().ofType(type).stream()
                    .filter(m -> m.participantIds().contains(listenerId))
                    .max(java.util.Comparator.comparingLong(
                            tterrag1112.life_in_the_village.Npc.Memory.NpcMemory::tick))
                    .map(tterrag1112.life_in_the_village.Npc.Memory.NpcMemory::summary)
                    .orElse("");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
