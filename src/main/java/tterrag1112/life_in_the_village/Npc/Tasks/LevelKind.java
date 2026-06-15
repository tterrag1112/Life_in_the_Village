package tterrag1112.life_in_the_village.Npc.Tasks;

import com.mojang.serialization.Codec;

/**
 * The owning / issuing "level" of a {@link Task} board. Tasks live on
 * per-level boards (a business's board, a household's board, a village's
 * board, ...) and an NPC pulls a filtered, ranked view across the levels
 * it belongs to.
 *
 * <p>{@code NPC} is itself a level: personal tasks an individual issues
 * to itself (e.g. a self-directed errand) live on an NPC-keyed board.</p>
 */
public enum LevelKind {
    KINGDOM,
    GUILD,
    VILLAGE,
    BUSINESS,
    HOUSEHOLD,
    MONASTERY,
    NPC;

    public static final Codec<LevelKind> CODEC =
            Codec.STRING.xmap(LevelKind::valueOf, LevelKind::name);
}
