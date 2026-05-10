package tterrag1112.life_in_the_village.Kingdom.Charters;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Track D3.4b — sealed parameter union for {@link Charter}. Each
 * {@link CharterType} has its own parameter record matching the
 * user-confirmed design pass; the dispatched
 * {@link #CODEC} flattens the type discriminator + variant fields
 * into one map so saves stay terse.
 *
 * <p>Pattern follows {@code RouteSegment.CODEC} — variants expose
 * {@code MAP_CODEC} so {@code Codec.STRING.dispatch} can compose
 * them under a {@code "type"} key.
 */
public sealed interface CharterParams permits
        CharterParams.TollRights,
        CharterParams.TaxExemption,
        CharterParams.MarketMonopoly,
        CharterParams.TitleGrant,
        CharterParams.OrdinationRights,
        CharterParams.LandGrant {

    /** Discriminator used by the codec to pick the right record. */
    CharterType type();

    /**
     * Dispatched union codec. Encodes as
     * {@code {"type": "TITLE_GRANT", ...variant fields}}; decodes
     * by reading {@code "type"} and selecting the right variant
     * MapCodec.
     */
    Codec<CharterParams> CODEC = Codec.STRING.dispatch(
            "type",
            p -> p.type().name(),
            typeName -> switch (CharterType.valueOf(typeName)) {
                case TOLL_RIGHTS       -> TollRights.MAP_CODEC;
                case TAX_EXEMPTION     -> TaxExemption.MAP_CODEC;
                case MARKET_MONOPOLY   -> MarketMonopoly.MAP_CODEC;
                case TITLE_GRANT       -> TitleGrant.MAP_CODEC;
                case ORDINATION_RIGHTS -> OrdinationRights.MAP_CODEC;
                case LAND_GRANT        -> LandGrant.MAP_CODEC;
            });

    /**
     * TOLL_RIGHTS — grantee collects {@code feeRate} on traffic
     * crossing the road edge.
     */
    record TollRights(UUID roadEdgeId, double feeRate) implements CharterParams {
        @Override public CharterType type() { return CharterType.TOLL_RIGHTS; }

        public static final MapCodec<TollRights> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                UUIDUtil.CODEC.fieldOf("roadEdgeId").forGetter(TollRights::roadEdgeId),
                Codec.DOUBLE.fieldOf("feeRate").forGetter(TollRights::feeRate)
        ).apply(i, TollRights::new));
    }

    /**
     * TAX_EXEMPTION — grantee's tax obligation reduced by
     * {@code exemptionRate} (0..1).
     */
    record TaxExemption(double exemptionRate) implements CharterParams {
        @Override public CharterType type() { return CharterType.TAX_EXEMPTION; }

        public static final MapCodec<TaxExemption> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.DOUBLE.fieldOf("exemptionRate").forGetter(TaxExemption::exemptionRate)
        ).apply(i, TaxExemption::new));
    }

    /**
     * MARKET_MONOPOLY — grantee has exclusive right to a market
     * type within {@link Scope}.
     */
    record MarketMonopoly(String marketTypeName, Scope scope,
                          java.util.Optional<UUID> scopeTargetId) implements CharterParams {
        public enum Scope { KINGDOM, PROVINCE, VILLAGE }

        @Override public CharterType type() { return CharterType.MARKET_MONOPOLY; }

        public static final MapCodec<MarketMonopoly> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("marketTypeName").forGetter(MarketMonopoly::marketTypeName),
                Codec.STRING.xmap(Scope::valueOf, Enum::name)
                        .fieldOf("scope").forGetter(MarketMonopoly::scope),
                UUIDUtil.CODEC.optionalFieldOf("scopeTargetId").forGetter(MarketMonopoly::scopeTargetId)
        ).apply(i, MarketMonopoly::new));
    }

    /**
     * TITLE_GRANT — grantee receives nobility rank {@code rankIndex}.
     * Phase 5's titled-grants player-facing flow targets this.
     */
    record TitleGrant(int rankIndex) implements CharterParams {
        @Override public CharterType type() { return CharterType.TITLE_GRANT; }

        public static final MapCodec<TitleGrant> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.fieldOf("rankIndex").forGetter(TitleGrant::rankIndex)
        ).apply(i, TitleGrant::new));
    }

    /**
     * ORDINATION_RIGHTS — religious order's authority. Reserved for
     * Phase 6.
     */
    record OrdinationRights(UUID religiousOrderId) implements CharterParams {
        @Override public CharterType type() { return CharterType.ORDINATION_RIGHTS; }

        public static final MapCodec<OrdinationRights> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                UUIDUtil.CODEC.fieldOf("religiousOrderId").forGetter(OrdinationRights::religiousOrderId)
        ).apply(i, OrdinationRights::new));
    }

    /**
     * LAND_GRANT — grantee receives a manor / estate at the named
     * block origin with a square footprint of {@code sizeCells}
     * cells per side.
     */
    record LandGrant(int blockX, int blockZ, int sizeCells) implements CharterParams {
        @Override public CharterType type() { return CharterType.LAND_GRANT; }

        public static final MapCodec<LandGrant> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.fieldOf("blockX").forGetter(LandGrant::blockX),
                Codec.INT.fieldOf("blockZ").forGetter(LandGrant::blockZ),
                Codec.INT.fieldOf("sizeCells").forGetter(LandGrant::sizeCells)
        ).apply(i, LandGrant::new));
    }
}

