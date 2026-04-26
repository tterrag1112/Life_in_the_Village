package tterrag1112.life_in_the_village.Npc.Gossip;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-village circulating-topic tracker. Heat increments when a topic
 * is exchanged in a {@link GossipChannel} and decays slowly each day so
 * old topics fade. Used by dialogue queries
 * ({@code DialoguePredicate.IsTopicHot}) to surface "what everyone is
 * talking about" without iterating every NPC's ledger.
 *
 * <p>Spec: {@code docs/npc_redesign/12-gossip-rumor.md} (line 250).</p>
 */
public final class GossipTopicHeat {

    /** Per-exchange heat increment. */
    public static final float HEAT_INCREMENT = 0.20f;
    /** Daily multiplicative decay factor. */
    public static final float DAILY_DECAY = 0.85f;
    /** Heat below this is dropped on decay. */
    public static final float CULL_THRESHOLD = 0.05f;
    /** Topics in/at this score count as "hot" for IsTopicHot predicate. */
    public static final float HOT_THRESHOLD = 0.40f;

    private final Map<String, Float> topicHeat = new LinkedHashMap<>();

    public GossipTopicHeat() {}

    public float get(String topic) {
        Float v = topicHeat.get(topic);
        return v == null ? 0f : v;
    }

    public boolean isHot(String topic) {
        return get(topic) >= HOT_THRESHOLD;
    }

    public void bump(String topic) {
        if (topic == null || topic.isEmpty()) return;
        float current = topicHeat.getOrDefault(topic, 0f);
        topicHeat.put(topic, Math.min(1f, current + HEAT_INCREMENT));
    }

    /** Multiplicative daily decay; entries below {@link #CULL_THRESHOLD} are removed. */
    public void decay(float daysElapsed) {
        if (daysElapsed <= 0f || topicHeat.isEmpty()) return;
        float factor = (float) Math.pow(DAILY_DECAY, daysElapsed);
        topicHeat.entrySet().removeIf(e -> {
            float v = e.getValue() * factor;
            if (v < CULL_THRESHOLD) return true;
            e.setValue(v);
            return false;
        });
    }

    /** Top {@code n} topics by heat, descending. */
    public List<Map.Entry<String, Float>> topN(int n) {
        return topicHeat.entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit(Math.max(0, n))
                .toList();
    }

    public int size()        { return topicHeat.size(); }
    public boolean isEmpty() { return topicHeat.isEmpty(); }

    public Map<String, Float> snapshot() {
        return Collections.unmodifiableMap(topicHeat);
    }

    public static final Codec<GossipTopicHeat> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Codec.FLOAT)
                    .optionalFieldOf("topics", new LinkedHashMap<>())
                    .forGetter(h -> h.topicHeat)
    ).apply(i, GossipTopicHeat::fromCodec));

    public static GossipTopicHeat fromCodec(Map<String, Float> loaded) {
        GossipTopicHeat h = new GossipTopicHeat();
        // Sort by heat descending so the in-memory iteration order
        // matches what topN expects on first call.
        loaded.entrySet().stream()
                .sorted(Comparator.comparingDouble(Map.Entry<String, Float>::getValue).reversed())
                .forEach(e -> h.topicHeat.put(e.getKey(),
                        Math.max(0f, Math.min(1f, e.getValue()))));
        return h;
    }
}
