package tterrag1112.life_in_the_village.Npc.Knowledge;

import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Entities.NpcNameRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic content mutator for rumor retelling.
 *
 * <p>The spec ({@code docs/npc_redesign/03-knowledge-system.md}, section
 * "Content mutation during retelling") requires that a rumor's mutation be
 * deterministic given {@code (topic, acquiredTick, mutator UUID)} so the
 * same chain always produces the same output across save/load and server
 * restarts. It does not prescribe how those three inputs combine.</p>
 *
 * <p><strong>Seed formula (locked in Phase 0 — do not change):</strong>
 * each of the four source longs — {@code (long) topic.hashCode()},
 * {@code acquiredTick}, {@code mutatorUuid.getMostSignificantBits()},
 * {@code mutatorUuid.getLeastSignificantBits()} — is passed through the
 * splitmix64 finaliser and the four results are XORed. All inputs are
 * JDK-spec-stable ({@link String#hashCode}, {@link UUID#getMostSignificantBits},
 * {@link Long#hashCode}). The resulting {@code long} seeds a Minecraft
 * {@link RandomSource}. Phase 2 gossip depends on this formula being
 * stable; any change will re-roll every existing rumor chain.</p>
 *
 * <p>Phase 0 ships the utility but nothing calls it — callers land with
 * the Phase 2 gossip system.</p>
 */
public final class RumorMutator {

    private RumorMutator() {}

    // ── Seed ────────────────────────────────────────────────────────────────

    public static long deriveSeed(String topic, long acquiredTick, UUID mutatorUuid) {
        long a = splitmix64((long) (topic == null ? 0 : topic.hashCode()));
        long b = splitmix64(acquiredTick);
        long c = splitmix64(mutatorUuid == null ? 0L : mutatorUuid.getMostSignificantBits());
        long d = splitmix64(mutatorUuid == null ? 0L : mutatorUuid.getLeastSignificantBits());
        return a ^ b ^ c ^ d;
    }

    /** Splitmix64 finaliser (Steele, Lea, Flood). Stable, high-quality mixer. */
    private static long splitmix64(long z) {
        z ^= z >>> 33;
        z *= 0xff51afd7ed558ccdL;
        z ^= z >>> 33;
        z *= 0xc4ceb9fe1a85ec53L;
        z ^= z >>> 33;
        return z;
    }

    // ── Mutation entry point ────────────────────────────────────────────────

    /**
     * Produces a mutated copy of {@code content}. Each mutation operation
     * fires with probability {@code 1 - newFidelity}, so higher-confidence
     * retellings drift less. Given identical inputs the output is always
     * identical.
     */
    public static String mutate(String content, float newFidelity, long seed) {
        if (content == null || content.isEmpty()) return "";
        RandomSource rng = RandomSource.create(seed);
        float mutationProb = Math.max(0f, Math.min(1f, 1f - newFidelity));

        String current = content;
        if (rng.nextFloat() < mutationProb) current = swapNumber(current, rng);
        if (rng.nextFloat() < mutationProb) current = swapMagnitude(current, rng);
        if (rng.nextFloat() < mutationProb) current = swapName(current, rng);
        if (rng.nextFloat() < mutationProb) current = swapEventOrder(current, rng);
        if (rng.nextFloat() < mutationProb) current = appendFlavor(current, rng);
        return current;
    }

    /** Convenience overload that builds the seed from spec inputs. */
    public static String mutate(String content, float newFidelity,
                                String topic, long acquiredTick, UUID mutatorUuid) {
        return mutate(content, newFidelity, deriveSeed(topic, acquiredTick, mutatorUuid));
    }

    // ── Operations ──────────────────────────────────────────────────────────

    private static final Pattern NUMBER = Pattern.compile("\\b(\\d+)\\b");

    /** Numbers off by ±50% (spec "Number fields off by ±50%"). */
    static String swapNumber(String s, RandomSource rng) {
        Matcher m = NUMBER.matcher(s);
        if (!m.find()) return s;
        // Deterministically pick one of the matches, then mutate it.
        List<int[]> spans = new ArrayList<>();
        m.reset();
        while (m.find()) spans.add(new int[]{m.start(), m.end()});
        int[] span = spans.get(rng.nextInt(spans.size()));
        long original = Long.parseLong(s.substring(span[0], span[1]));
        // Scale in [0.5, 1.5], rounded, min 0.
        double scale = 0.5 + rng.nextDouble();
        long mutated = Math.max(0L, Math.round(original * scale));
        return s.substring(0, span[0]) + mutated + s.substring(span[1]);
    }

    /** Hardcoded magnitude/intensity swaps (spec: "dozen ↔ several"). */
    private static final Map<String, String[]> MAGNITUDE_SWAPS = Map.ofEntries(
            Map.entry("huge",    new String[]{"big", "large"}),
            Map.entry("big",     new String[]{"huge", "sizeable"}),
            Map.entry("dozen",   new String[]{"several", "a handful of"}),
            Map.entry("several", new String[]{"a dozen", "a few"}),
            Map.entry("many",    new String[]{"a few", "some"}),
            Map.entry("few",     new String[]{"many", "plenty of"}),
            Map.entry("tiny",    new String[]{"small", "little"}),
            Map.entry("small",   new String[]{"tiny", "modest"}),
            Map.entry("always",  new String[]{"often", "usually"}),
            Map.entry("never",   new String[]{"rarely", "seldom"}),
            Map.entry("everyone",new String[]{"most folk", "plenty"})
    );

    static String swapMagnitude(String s, RandomSource rng) {
        String[] tokens = s.split("(?<=\\s)|(?=\\s)"); // keep spaces as their own tokens
        // Collect indices of swappable tokens (case-insensitive match against the table).
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            String key = stripPunctuation(tokens[i]).toLowerCase(java.util.Locale.ROOT);
            if (MAGNITUDE_SWAPS.containsKey(key)) candidates.add(i);
        }
        if (candidates.isEmpty()) return s;
        int idx = candidates.get(rng.nextInt(candidates.size()));
        String token = tokens[idx];
        String stripped = stripPunctuation(token);
        String key = stripped.toLowerCase(java.util.Locale.ROOT);
        String[] options = MAGNITUDE_SWAPS.get(key);
        String replacement = options[rng.nextInt(options.length)];
        // Preserve original capitalisation crudely: if original started uppercase, match it.
        if (!stripped.isEmpty() && Character.isUpperCase(stripped.charAt(0))) {
            replacement = Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
        }
        // Preserve trailing punctuation.
        String trailing = token.substring(stripped.length());
        tokens[idx] = replacement + trailing;
        return String.join("", tokens);
    }

    private static String stripPunctuation(String t) {
        int end = t.length();
        while (end > 0 && !Character.isLetterOrDigit(t.charAt(end - 1))) end--;
        return t.substring(0, end);
    }

    /** Capitalised-word substitution with a fresh name from {@link NpcNameRegistry}. */
    private static final Pattern NAME_CANDIDATE =
            Pattern.compile("\\b([A-Z][a-z]{2,})\\b");

    static String swapName(String s, RandomSource rng) {
        Matcher m = NAME_CANDIDATE.matcher(s);
        List<int[]> spans = new ArrayList<>();
        // Skip the very first word of the string (almost always a sentence start, not a name).
        while (m.find()) {
            if (m.start() == 0) continue;
            spans.add(new int[]{m.start(), m.end()});
        }
        if (spans.isEmpty()) return s;
        int[] span = spans.get(rng.nextInt(spans.size()));
        boolean male = rng.nextBoolean();
        String replacement = NpcNameRegistry.INSTANCE.generateFirstName(male, rng);
        return s.substring(0, span[0]) + replacement + s.substring(span[1]);
    }

    /** Swap a random adjacent pair of comma-delimited clauses. */
    static String swapEventOrder(String s, RandomSource rng) {
        if (!s.contains(",")) return s;
        String[] parts = s.split(",");
        if (parts.length < 2) return s;
        int i = rng.nextInt(parts.length - 1);
        String tmp = parts[i];
        parts[i] = parts[i + 1];
        parts[i + 1] = tmp;
        return String.join(",", parts);
    }

    private static final String[] FLAVOR_SUFFIXES = {
            " — or so I heard.",
            " (or something like that).",
            ", supposedly.",
            ", if you can believe it.",
            " — that's what folk are saying."
    };

    /** Appends a flavor phrase (user prompt: "addition (low-Honesty teller)"). */
    static String appendFlavor(String s, RandomSource rng) {
        return s + FLAVOR_SUFFIXES[rng.nextInt(FLAVOR_SUFFIXES.length)];
    }
}
