package tterrag1112.life_in_the_village.Kingdom.Charters;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Track D3.4b — tagged-union reference to whatever entity holds a
 * {@link Charter}. Per the user-confirmed design, charters can be
 * granted to NPCs, dynasty houses, guilds, villages (cities-as-
 * villages in the v1 model), or religious orders.
 *
 * <p>The original PowerGrant pattern from D3.3b is strictly per-NPC
 * UUID; this record is the smallest extension that admits non-NPC
 * grantees without forking that infrastructure. Lookup helpers in
 * {@link CharterRegistry} resolve the {@link UUID} per
 * {@link GranteeKind}.
 */
public record GranteeRef(GranteeKind kind, UUID id) {

    public enum GranteeKind {
        /** A specific NPC, identified by the NPC's UUID. */
        NOBLE_NPC,
        /** A noble {@code House}, identified by the house id. */
        HOUSE,
        /** An adventurer / merchant / craft guild, identified by guild id. */
        GUILD,
        /** A village, identified by village id. */
        VILLAGE,
        /**
         * A religious order, identified by order id. Reserved —
         * v1 has no religious-order data structure; ORDINATION_RIGHTS
         * charters with this kind hold the id placeholder for Phase 6.
         */
        RELIGIOUS_ORDER;
    }

    public static final Codec<GranteeRef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.xmap(GranteeKind::valueOf, Enum::name)
                    .fieldOf("kind").forGetter(GranteeRef::kind),
            UUIDUtil.CODEC.fieldOf("id").forGetter(GranteeRef::id)
    ).apply(i, GranteeRef::new));

    // ── Convenience factories ──────────────────────────────────────────────

    public static GranteeRef ofNpc(UUID npcId)       { return new GranteeRef(GranteeKind.NOBLE_NPC, npcId); }
    public static GranteeRef ofHouse(UUID houseId)   { return new GranteeRef(GranteeKind.HOUSE, houseId); }
    public static GranteeRef ofGuild(UUID guildId)   { return new GranteeRef(GranteeKind.GUILD, guildId); }
    public static GranteeRef ofVillage(UUID villageId) { return new GranteeRef(GranteeKind.VILLAGE, villageId); }
    public static GranteeRef ofReligiousOrder(UUID orderId) {
        return new GranteeRef(GranteeKind.RELIGIOUS_ORDER, orderId);
    }
}
