package tterrag1112.life_in_the_village.Quests;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Locale;

/**
 * F2a-1 — who issued a quest. The {@code id} is the giver-scoped identifier (e.g. a
 * god id for a {@link Type#DIVINE} quest). Rich NPC/saint/clergy givers are F2a-2.
 */
public record QuestGiver(QuestGiver.Type type, String id) {

    /** The giver kinds the quest base recognises (concrete consumers grow in F2a-2). */
    public enum Type { DIVINE, GUILD, NPC, KINGDOM }

    public static final Codec<Type> TYPE_CODEC = Codec.STRING.xmap(
            s -> Type.valueOf(s.toUpperCase(Locale.ROOT)), Type::name);

    public static final Codec<QuestGiver> CODEC = RecordCodecBuilder.create(i -> i.group(
            TYPE_CODEC.fieldOf("type").forGetter(QuestGiver::type),
            Codec.STRING.optionalFieldOf("id", "").forGetter(QuestGiver::id)
    ).apply(i, QuestGiver::new));
}
