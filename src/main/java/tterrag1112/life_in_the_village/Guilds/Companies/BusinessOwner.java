package tterrag1112.life_in_the_village.Guilds.Companies;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Phase 6.3.3.b — sealed polymorphic ownership for {@link Business}.
 *
 * <p>Replaces the flat {@code OwnerType {PLAYER, NPC}} enum + nullable
 * UUID combo with a typed variant per ownership category. Mirrors the
 * {@code RoleLifetime} sealed-dispatch pattern from 6.3.2.a — MapCodec
 * dispatch on a {@code "kind"} discriminator, individual variants are
 * records carrying their own payload.
 *
 * <h3>Variants</h3>
 * <ul>
 *   <li>{@link NpcOwner} — single NPC. Current owner-bound dynamics
 *       (succession, payroll-from-treasury) target the wrapped UUID.</li>
 *   <li>{@link PlayerOwner} — single player. Treasury access via
 *       player inventory; no succession.</li>
 *   <li>{@link FamilyOwner} — household-collective ownership. Used by
 *       farms and any future business owned by a family rather than
 *       an individual. Succession and inheritance run over household
 *       members.</li>
 *   <li>{@link VillageOwner} / {@link KingdomOwner} / {@link GuildOwner}
 *       — collective ownership at scale. <em>Stubs</em> in 6.3.3.b:
 *       registered with the codec and the type system, but no current
 *       creation path produces them. Future content (subsidized
 *       industry, kingdom-run mines, guild-owned trade houses) adds
 *       the creation paths without changing this interface.</li>
 * </ul>
 *
 * <h3>Migration from legacy ownership trio</h3>
 * <p>Legacy fields on Business were {@code (OwnerType ownerType,
 * UUID ownerId, UUID ownerPlayerId)}. The {@link OwnershipInfo} codec
 * accepts both layouts: new saves write a sealed {@code owner} field;
 * old saves provide the legacy trio and the constructor reconstructs
 * an appropriate variant.
 */
public sealed interface BusinessOwner
        permits BusinessOwner.NpcOwner,
                BusinessOwner.PlayerOwner,
                BusinessOwner.FamilyOwner,
                BusinessOwner.VillageOwner,
                BusinessOwner.KingdomOwner,
                BusinessOwner.GuildOwner {

    /** Canonical UUID for ownership comparison / hash keys. */
    UUID getPrimaryUuid();

    /** Discriminator for codec dispatch. */
    String kind();

    default boolean isNpc()        { return this instanceof NpcOwner; }
    default boolean isPlayer()     { return this instanceof PlayerOwner; }
    default boolean isFamily()     { return this instanceof FamilyOwner; }
    default boolean isCollective() {
        return this instanceof VillageOwner
                || this instanceof KingdomOwner
                || this instanceof GuildOwner;
    }

    // ── Variants ────────────────────────────────────────────────────────────

    record NpcOwner(UUID npcUuid) implements BusinessOwner {
        public static final MapCodec<NpcOwner> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                UUIDUtil.CODEC.fieldOf("npcUuid").forGetter(NpcOwner::npcUuid)
        ).apply(i, NpcOwner::new));
        @Override public UUID getPrimaryUuid() { return npcUuid; }
        @Override public String kind() { return "npc"; }
    }

    record PlayerOwner(UUID playerUuid) implements BusinessOwner {
        public static final MapCodec<PlayerOwner> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                UUIDUtil.CODEC.fieldOf("playerUuid").forGetter(PlayerOwner::playerUuid)
        ).apply(i, PlayerOwner::new));
        @Override public UUID getPrimaryUuid() { return playerUuid; }
        @Override public String kind() { return "player"; }
    }

    record FamilyOwner(UUID householdId) implements BusinessOwner {
        public static final MapCodec<FamilyOwner> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                UUIDUtil.CODEC.fieldOf("householdId").forGetter(FamilyOwner::householdId)
        ).apply(i, FamilyOwner::new));
        @Override public UUID getPrimaryUuid() { return householdId; }
        @Override public String kind() { return "family"; }
    }

    record VillageOwner(UUID villageId) implements BusinessOwner {
        public static final MapCodec<VillageOwner> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                UUIDUtil.CODEC.fieldOf("villageId").forGetter(VillageOwner::villageId)
        ).apply(i, VillageOwner::new));
        @Override public UUID getPrimaryUuid() { return villageId; }
        @Override public String kind() { return "village"; }
    }

    record KingdomOwner(UUID kingdomId) implements BusinessOwner {
        public static final MapCodec<KingdomOwner> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                UUIDUtil.CODEC.fieldOf("kingdomId").forGetter(KingdomOwner::kingdomId)
        ).apply(i, KingdomOwner::new));
        @Override public UUID getPrimaryUuid() { return kingdomId; }
        @Override public String kind() { return "kingdom"; }
    }

    record GuildOwner(UUID guildId) implements BusinessOwner {
        public static final MapCodec<GuildOwner> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                UUIDUtil.CODEC.fieldOf("guildId").forGetter(GuildOwner::guildId)
        ).apply(i, GuildOwner::new));
        @Override public UUID getPrimaryUuid() { return guildId; }
        @Override public String kind() { return "guild"; }
    }

    Codec<BusinessOwner> CODEC = Codec.STRING.dispatch("kind",
            BusinessOwner::kind,
            kind -> switch (kind) {
                case "npc"      -> NpcOwner.CODEC;
                case "player"   -> PlayerOwner.CODEC;
                case "family"   -> FamilyOwner.CODEC;
                case "village"  -> VillageOwner.CODEC;
                case "kingdom"  -> KingdomOwner.CODEC;
                case "guild"    -> GuildOwner.CODEC;
                default -> throw new IllegalArgumentException(
                        "Unknown BusinessOwner kind: " + kind);
            });
}
