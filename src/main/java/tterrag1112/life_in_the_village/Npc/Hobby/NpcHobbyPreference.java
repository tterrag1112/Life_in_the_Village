package tterrag1112.life_in_the_village.Npc.Hobby;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Traits.TraitVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-NPC hobby state. Stores 3–5 preferred hobby ids generated at
 * adulthood, the currently-active hobby (if any), and a recent-uses
 * map for variety down-weighting.
 *
 * <p>Spec: {@code docs/npc_redesign/14-hobby-activities.md} (line 104,
 * 256). Persisted as {@code npcHobby} on the entity tag.</p>
 */
public final class NpcHobbyPreference {

    public static final int MIN_PREFERRED = 3;
    public static final int MAX_PREFERRED = 5;
    /** Days within which a hobby use counts toward the recency penalty. */
    public static final long RECENCY_WINDOW_DAYS = 3L;
    /** Score penalty per recent use within the window (spec line 169). */
    public static final float RECENCY_PENALTY_PER_USE = 0.5f;
    /** Bonus added when the hobby's gain skill matches the NPC's primary skill. */
    public static final float SKILL_INTEREST_BONUS = 0.3f;
    /** Softmax temperature for session pick over top 5 candidates. */
    public static final float SOFTMAX_TEMPERATURE = 1.0f;

    private static final String SAVE_KEY = "npcHobby";

    private final List<String> topHobbies = new ArrayList<>();
    private String currentHobby = null;
    private long currentHobbyStartTick = 0L;
    private final Map<String, Long> recentUses = new LinkedHashMap<>();

    public NpcHobbyPreference() {}

    // ── Queries ────────────────────────────────────────────────────────────

    public List<String> topHobbies() { return Collections.unmodifiableList(topHobbies); }
    public String currentHobby()     { return currentHobby; }
    public long currentHobbyStartTick() { return currentHobbyStartTick; }
    public Map<String, Long> recentUses() { return Collections.unmodifiableMap(recentUses); }
    public boolean hasPreferences()  { return !topHobbies.isEmpty(); }
    public boolean hasCurrent()      { return currentHobby != null && !currentHobby.isEmpty(); }

    // ── Mutations ──────────────────────────────────────────────────────────

    /** Replaces the preferred-hobby list. */
    public void setTopHobbies(List<String> hobbies) {
        topHobbies.clear();
        for (String h : hobbies) {
            if (h == null || h.isEmpty()) continue;
            if (topHobbies.size() >= MAX_PREFERRED) break;
            topHobbies.add(h);
        }
    }

    public void setCurrent(String hobbyId, long tick) {
        this.currentHobby = hobbyId;
        this.currentHobbyStartTick = tick;
    }

    public void clearCurrent() {
        this.currentHobby = null;
        this.currentHobbyStartTick = 0L;
    }

    /** Records that the NPC used {@code hobbyId} at {@code tick}. */
    public void noteUsed(String hobbyId, long tick) {
        if (hobbyId == null || hobbyId.isEmpty()) return;
        recentUses.put(hobbyId, tick);
        // Drop entries older than the recency window so the map doesn't
        // grow unbounded across years of play.
        long cutoff = tick - (RECENCY_WINDOW_DAYS + 7L) * 24000L;
        recentUses.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    // ── Generation (called at adulthood + via debug regenerate) ────────────

    /**
     * Regenerates the {@link #topHobbies} list using the spec scoring
     * formula: {@code traitFit + cultureBonus(stub=0) + skillInterestBonus}.
     * Picks the top {@code [MIN_PREFERRED, MAX_PREFERRED]} hobbies by
     * score, weighted toward variety (skip duplicates).
     */
    public void generate(TownspersonMob npc, ServerLevel level, RandomSource rng) {
        TraitVector traits = npc.getTraitVector();
        Skill primary = npc.getSkills().primary();
        List<HobbyDefinition> candidates = HobbyCatalogue.availableFor(npc, level);
        if (candidates.isEmpty()) {
            topHobbies.clear();
            return;
        }
        // Score each candidate. Phase 5 doc 31: multiply the
        // trait-derived score by the culture's per-hobby weight
        // (default 1.0 when the culture has no opinion).
        var culture = tterrag1112.life_in_the_village.Cultures.CultureResolver.of(npc);
        record Scored(HobbyDefinition def, float score) {}
        List<Scored> scored = new ArrayList<>();
        for (HobbyDefinition def : candidates) {
            float s = def.traitWeight().score(traits);
            if (def.skillGain().isPresent() && def.skillGain().get() == primary) {
                s += SKILL_INTEREST_BONUS;
            }
            float cultureMultiplier = tterrag1112.life_in_the_village.Cultures
                    .CultureResolver.hobbyWeightFor(culture, def.id());
            s *= cultureMultiplier;
            scored.add(new Scored(def, s));
        }
        scored.sort((a, b) -> Float.compare(b.score(), a.score()));

        int target = MIN_PREFERRED + rng.nextInt(MAX_PREFERRED - MIN_PREFERRED + 1);
        topHobbies.clear();
        for (int i = 0; i < scored.size() && topHobbies.size() < target; i++) {
            topHobbies.add(scored.get(i).def().id());
        }
    }

    /**
     * Picks a hobby for the current LEISURE / day-off session by
     * softmax over the top 5 (after recency penalty) of the NPC's
     * preferred hobbies. Spec line 162.
     */
    public Optional<HobbyDefinition> pickForSession(TownspersonMob npc,
                                                    ServerLevel level,
                                                    long tick,
                                                    RandomSource rng) {
        if (topHobbies.isEmpty()) return Optional.empty();
        TraitVector traits = npc.getTraitVector();
        Skill primary = npc.getSkills().primary();
        List<HobbyDefinition> avail = HobbyCatalogue.availableFor(npc, level);
        java.util.Set<String> availIds = new java.util.HashSet<>();
        for (HobbyDefinition d : avail) availIds.add(d.id());

        record Scored(HobbyDefinition def, float score) {}
        List<Scored> scored = new ArrayList<>();
        long recencyCutoff = tick - RECENCY_WINDOW_DAYS * 24000L;
        for (String id : topHobbies) {
            if (!availIds.contains(id)) continue;
            HobbyDefinition def = HobbyCatalogue.get(id).orElse(null);
            if (def == null) continue;
            float s = def.traitWeight().score(traits);
            if (def.skillGain().isPresent() && def.skillGain().get() == primary) {
                s += SKILL_INTEREST_BONUS;
            }
            Long lastUsed = recentUses.get(id);
            if (lastUsed != null && lastUsed >= recencyCutoff) {
                s -= RECENCY_PENALTY_PER_USE;
            }
            scored.add(new Scored(def, s));
        }
        // Fall back to availFresh hobbies if every preferred is unavailable.
        if (scored.isEmpty()) {
            for (HobbyDefinition def : avail) {
                scored.add(new Scored(def, def.traitWeight().score(traits)));
            }
        }
        if (scored.isEmpty()) return Optional.empty();
        scored.sort((a, b) -> Float.compare(b.score(), a.score()));
        int top = Math.min(5, scored.size());
        // Softmax-weighted pick.
        double[] weights = new double[top];
        double sum = 0;
        double maxScore = scored.get(0).score();
        for (int i = 0; i < top; i++) {
            weights[i] = Math.exp((scored.get(i).score() - maxScore) / SOFTMAX_TEMPERATURE);
            sum += weights[i];
        }
        double r = rng.nextDouble() * sum;
        double acc = 0;
        for (int i = 0; i < top; i++) {
            acc += weights[i];
            if (r <= acc) return Optional.of(scored.get(i).def());
        }
        return Optional.of(scored.get(top - 1).def());
    }

    // ── Persistence ────────────────────────────────────────────────────────

    public void save(ValueOutput output) {
        output.store(SAVE_KEY, CODEC, this);
    }

    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        NpcHobbyPreference loaded = read.get();
        topHobbies.clear();
        topHobbies.addAll(loaded.topHobbies);
        currentHobby = loaded.currentHobby;
        currentHobbyStartTick = loaded.currentHobbyStartTick;
        recentUses.clear();
        recentUses.putAll(loaded.recentUses);
        return true;
    }

    public static final Codec<NpcHobbyPreference> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.listOf().optionalFieldOf("topHobbies", List.of())
                    .forGetter(p -> List.copyOf(p.topHobbies)),
            Codec.STRING.optionalFieldOf("currentHobby", "")
                    .forGetter(p -> p.currentHobby == null ? "" : p.currentHobby),
            Codec.LONG.optionalFieldOf("currentHobbyStartTick", 0L)
                    .forGetter(p -> p.currentHobbyStartTick),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("recentUses", Map.of())
                    .forGetter(p -> Map.copyOf(p.recentUses))
    ).apply(i, NpcHobbyPreference::fromCodec));

    public static NpcHobbyPreference fromCodec(List<String> top, String current,
                                               long startTick, Map<String, Long> recent) {
        NpcHobbyPreference p = new NpcHobbyPreference();
        for (String h : top) {
            if (p.topHobbies.size() >= MAX_PREFERRED) break;
            if (h != null && !h.isEmpty()) p.topHobbies.add(h);
        }
        p.currentHobby = current == null || current.isEmpty() ? null : current;
        p.currentHobbyStartTick = startTick;
        p.recentUses.putAll(recent);
        return p;
    }
}
