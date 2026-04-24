package tterrag1112.life_in_the_village.Npc.Mood;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One past trigger event preserved on a {@link NpcMoodState} for display.
 * Logical contribution to the mood {@code value} is already baked in;
 * older events drop off the recent list once {@link NpcMoodState#RECENT_CAP}
 * is exceeded.
 */
public record MoodEvent(MoodTrigger trigger, int magnitude, long tick) {

    public static final Codec<MoodEvent> CODEC = RecordCodecBuilder.create(i -> i.group(
            MoodTrigger.CODEC.fieldOf("trigger").forGetter(MoodEvent::trigger),
            Codec.INT.fieldOf("magnitude").forGetter(MoodEvent::magnitude),
            Codec.LONG.fieldOf("tick").forGetter(MoodEvent::tick)
    ).apply(i, MoodEvent::new));
}
