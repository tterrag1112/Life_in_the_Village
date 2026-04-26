package tterrag1112.life_in_the_village.Npc.Gossip;

import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * Phase 2 starter library of slant transforms applied to rumor content
 * when the speaker has a strong relationship to the topic's subject.
 * Spec ({@code docs/npc_redesign/12-gossip-rumor.md} line 138) calls
 * for "10-15 basic slant patterns" that the Phase 5 content pass
 * expands. Each pattern is a (find, replace) pair, applied
 * case-insensitively at most once per call.
 *
 * <p>Selection is deterministic via the supplied {@link RandomSource}
 * so retells through the same {@link tterrag1112.life_in_the_village.Npc.Knowledge.RumorMutator}
 * seed produce the same slanted output.</p>
 */
public final class SlantTemplates {

    private SlantTemplates() {}

    /** Replacement pair plus a "kind" hint used only in tests. */
    private record Pattern(String find, String replace) {}

    private static final List<Pattern> NEGATIVE = List.of(
            new Pattern("saving",       "hoarding"),
            new Pattern("working hard", "toiling away"),
            new Pattern("celebrated",   "made a spectacle"),
            new Pattern("shut",         "abandoned"),
            new Pattern("modest",       "miserly"),
            new Pattern("careful",      "cowardly"),
            new Pattern("proud",        "boastful")
    );

    private static final List<Pattern> POSITIVE = List.of(
            new Pattern("saving",       "saving for their family"),
            new Pattern("working hard", "working tirelessly"),
            new Pattern("shut",         "had to shut, poor soul,"),
            new Pattern("modest",       "humble"),
            new Pattern("careful",      "prudent"),
            new Pattern("argued",       "stood firm")
    );

    /** Wrappers used when no in-text find matches. */
    private static final List<String> NEGATIVE_PREFIXES = List.of(
            "Of course, ",
            "Predictably, ",
            "I shouldn't be surprised — "
    );

    private static final List<String> POSITIVE_PREFIXES = List.of(
            "Bless them — ",
            "It says a lot that ",
            "I'll admit, "
    );

    /** Total starter pattern count; verifies spec line 151 budget. */
    public static int starterPatternCount() {
        return NEGATIVE.size() + POSITIVE.size()
                + NEGATIVE_PREFIXES.size() + POSITIVE_PREFIXES.size();
    }

    /**
     * Applies a negative slant. Tries each {@link #NEGATIVE} pattern
     * in order; the first one whose find-text occurs (case-insensitive)
     * is applied. If none match, prepends a deterministic prefix from
     * {@link #NEGATIVE_PREFIXES}.
     */
    public static String slantNegative(String content, RandomSource rng) {
        return applySlant(content, NEGATIVE, NEGATIVE_PREFIXES, rng);
    }

    /** Mirror of {@link #slantNegative} for positive slant. */
    public static String slantPositive(String content, RandomSource rng) {
        return applySlant(content, POSITIVE, POSITIVE_PREFIXES, rng);
    }

    private static String applySlant(String content,
                                     List<Pattern> patterns,
                                     List<String> prefixes,
                                     RandomSource rng) {
        if (content == null || content.isEmpty()) return content == null ? "" : content;
        // Single-shot replacement on the first matching pattern.
        // Iteration order is fixed; rng is reserved for prefix-pick fallback.
        String lower = content.toLowerCase(java.util.Locale.ROOT);
        for (Pattern p : patterns) {
            int idx = lower.indexOf(p.find());
            if (idx < 0) continue;
            return content.substring(0, idx)
                    + p.replace()
                    + content.substring(idx + p.find().length());
        }
        // No literal pattern matched — prepend a slant frame.
        String prefix = prefixes.get(rng.nextInt(prefixes.size()));
        return prefix + decapitalizeFirst(content);
    }

    private static String decapitalizeFirst(String s) {
        if (s.isEmpty()) return s;
        char first = s.charAt(0);
        if (!Character.isUpperCase(first)) return s;
        return Character.toLowerCase(first) + s.substring(1);
    }
}
