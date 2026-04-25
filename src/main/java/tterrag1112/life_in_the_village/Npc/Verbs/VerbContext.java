package tterrag1112.life_in_the_village.Npc.Verbs;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

/**
 * Carrying envelope for a verb invocation. Spec
 * {@code 09-player-verbs.md} line 30.
 */
public record VerbContext(ServerPlayer player,
                          TownspersonMob npc,
                          ServerLevel level,
                          long tick) {
    public VerbContext {
        if (player == null) throw new IllegalArgumentException("player is required");
        if (npc == null) throw new IllegalArgumentException("npc is required");
        if (level == null) throw new IllegalArgumentException("level is required");
    }
}
