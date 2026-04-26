package tterrag1112.life_in_the_village.Npc.Scribal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-scholar in-progress research and authorship state. Spec
 * line 247 splits the scholar work cycle into research → authorship
 * → teaching → consultation; this component tracks the first two.
 *
 * <p>{@code researchPoints} accumulates while studying books;
 * {@code currentTopic} is the working title of the in-flight book.
 * When research crosses the {@link #PUBLISH_THRESHOLD}, the goal
 * mints a {@link BookRecord} and resets.</p>
 */
public final class ScholarProgress {

    /** Research points needed to publish (~1 in-game month at 1/day). */
    public static final int PUBLISH_THRESHOLD = 30;
    /** Minimum days between authored books per scholar. */
    public static final long MIN_TICKS_BETWEEN_BOOKS = 30L * 24000L;

    private static final String SAVE_KEY = "npcScholarProgress";

    private int researchPoints = 0;
    private String currentTopic = "";
    private long lastPublishedTick = 0L;
    private final List<String> coveredTopics = new ArrayList<>();

    public ScholarProgress() {}

    public int researchPoints()  { return researchPoints; }
    public String currentTopic() { return currentTopic; }
    public long lastPublishedTick() { return lastPublishedTick; }
    public List<String> coveredTopics() { return Collections.unmodifiableList(coveredTopics); }

    public boolean readyToPublish() { return researchPoints >= PUBLISH_THRESHOLD; }

    public void addResearch(int points, String topic) {
        researchPoints = Math.min(PUBLISH_THRESHOLD * 4, researchPoints + Math.max(0, points));
        if (topic != null && !topic.isEmpty()) {
            currentTopic = topic;
            if (!coveredTopics.contains(topic)) coveredTopics.add(topic);
        }
    }

    /** Resets the in-flight research after a publish. */
    public void onPublished(long currentTick) {
        researchPoints = 0;
        currentTopic = "";
        lastPublishedTick = currentTick;
    }

    public boolean cooldownElapsed(long currentTick) {
        return currentTick - lastPublishedTick >= MIN_TICKS_BETWEEN_BOOKS;
    }

    public void save(ValueOutput output) {
        if (researchPoints == 0 && currentTopic.isEmpty()
                && lastPublishedTick == 0L && coveredTopics.isEmpty()) return;
        output.store(SAVE_KEY, CODEC, this);
    }

    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        ScholarProgress loaded = read.get();
        researchPoints = loaded.researchPoints;
        currentTopic = loaded.currentTopic;
        lastPublishedTick = loaded.lastPublishedTick;
        coveredTopics.clear();
        coveredTopics.addAll(loaded.coveredTopics);
        return true;
    }

    public static final Codec<ScholarProgress> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("research", 0).forGetter(p -> p.researchPoints),
            Codec.STRING.optionalFieldOf("topic", "").forGetter(p -> p.currentTopic),
            Codec.LONG.optionalFieldOf("lastPublished", 0L).forGetter(p -> p.lastPublishedTick),
            Codec.STRING.listOf().optionalFieldOf("covered", List.of())
                    .forGetter(p -> List.copyOf(p.coveredTopics))
    ).apply(i, ScholarProgress::fromCodec));

    public static ScholarProgress fromCodec(int research, String topic,
                                            long lastPublished, List<String> covered) {
        ScholarProgress p = new ScholarProgress();
        p.researchPoints = research;
        p.currentTopic = topic == null ? "" : topic;
        p.lastPublishedTick = lastPublished;
        p.coveredTopics.addAll(covered);
        return p;
    }
}
