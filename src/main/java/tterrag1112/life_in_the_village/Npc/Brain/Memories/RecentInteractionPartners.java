package tterrag1112.life_in_the_village.Npc.Brain.Memories;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded LRU-ish record of NPCs we have recently interacted with,
 * mapped to the game tick of the last interaction. Used by future
 * social-cool-down logic so we don't immediately re-greet the same
 * partner. Phase 6.0 registers the memory shape; reads/writes land
 * in 6.1+.
 */
public record RecentInteractionPartners(Map<UUID, Long> partners) {

    public static final RecentInteractionPartners EMPTY =
            new RecentInteractionPartners(Map.of());

    public static final Codec<RecentInteractionPartners> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG)
                            .fieldOf("partners")
                            .forGetter(RecentInteractionPartners::partners)
            ).apply(instance, RecentInteractionPartners::new));

    public RecentInteractionPartners {
        partners = Collections.unmodifiableMap(new LinkedHashMap<>(partners));
    }
}
