package tterrag1112.life_in_the_village.Kingdom.Audience;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import tterrag1112.life_in_the_village.Kingdom.Charters.CharterParams;

import java.util.UUID;

/**
 * Track D3.5A — sealed parameter union for {@link Petition}. One
 * variant per {@link PetitionKind}; codec dispatches via the
 * {@code "kind"} discriminator stored on the parent petition.
 *
 * <p>Pattern follows {@link CharterParams}: variant
 * {@code MAP_CODEC} fields composed under
 * {@code Codec.STRING.dispatch}.
 */
public sealed interface PetitionPayload permits
        PetitionPayload.TreatyRatification,
        PetitionPayload.CharterRequest,
        PetitionPayload.AudienceGrievance,
        PetitionPayload.WarDeclaration,
        PetitionPayload.BreakTreaty {

    /** Discriminator used by the codec to pick the right variant. */
    PetitionKind kind();

    /** Dispatched union codec. */
    Codec<PetitionPayload> CODEC = Codec.STRING.dispatch(
            "kind",
            p -> p.kind().name(),
            kindName -> switch (PetitionKind.valueOf(kindName)) {
                case TREATY_RATIFICATION -> TreatyRatification.MAP_CODEC;
                case CHARTER_REQUEST     -> CharterRequest.MAP_CODEC;
                case AUDIENCE_GRIEVANCE  -> AudienceGrievance.MAP_CODEC;
                case WAR_DECLARATION     -> WarDeclaration.MAP_CODEC;
                case BREAK_TREATY        -> BreakTreaty.MAP_CODEC;
            });

    /**
     * TREATY_RATIFICATION — player asks the ruler to ratify (or
     * decline) a treaty already drafted by a Scholar. Approve →
     * Kingdom.ratifyTreaty.
     */
    record TreatyRatification(UUID treatyId) implements PetitionPayload {
        @Override public PetitionKind kind() { return PetitionKind.TREATY_RATIFICATION; }

        public static final MapCodec<TreatyRatification> MAP_CODEC =
                RecordCodecBuilder.mapCodec(i -> i.group(
                        UUIDUtil.CODEC.fieldOf("treatyId").forGetter(TreatyRatification::treatyId)
                ).apply(i, TreatyRatification::new));
    }

    /**
     * CHARTER_REQUEST — player asks for a charter to be granted to
     * themselves (or a named party). Carries the requested params
     * directly so the ruler's approval just calls
     * Kingdom.grantCharter without re-parsing.
     */
    record CharterRequest(String name, UUID granteeId,
                          String granteeKindName, CharterParams params)
            implements PetitionPayload {
        @Override public PetitionKind kind() { return PetitionKind.CHARTER_REQUEST; }

        public static final MapCodec<CharterRequest> MAP_CODEC =
                RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.STRING.fieldOf("name").forGetter(CharterRequest::name),
                        UUIDUtil.CODEC.fieldOf("granteeId").forGetter(CharterRequest::granteeId),
                        Codec.STRING.fieldOf("granteeKindName")
                                .forGetter(CharterRequest::granteeKindName),
                        CharterParams.CODEC.fieldOf("params").forGetter(CharterRequest::params)
                ).apply(i, CharterRequest::new));
    }

    /**
     * AUDIENCE_GRIEVANCE — narrative-only complaint / request.
     * Approve → small standing boost; deny → small standing dip.
     * Body is a free-form summary string (clamped at GUI level).
     */
    record AudienceGrievance(String summary) implements PetitionPayload {
        @Override public PetitionKind kind() { return PetitionKind.AUDIENCE_GRIEVANCE; }

        public static final MapCodec<AudienceGrievance> MAP_CODEC =
                RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.STRING.fieldOf("summary").forGetter(AudienceGrievance::summary)
                ).apply(i, AudienceGrievance::new));
    }

    /**
     * WAR_DECLARATION — player asks the ruler to declare WAR on
     * the target kingdom. Approve → Kingdom.setRelation(target,
     * WAR). Capability check happens at submit time
     * (DECLARE_WAR; vassal kingdoms still blocked).
     */
    record WarDeclaration(UUID targetKingdomId, String casusBelli)
            implements PetitionPayload {
        @Override public PetitionKind kind() { return PetitionKind.WAR_DECLARATION; }

        public static final MapCodec<WarDeclaration> MAP_CODEC =
                RecordCodecBuilder.mapCodec(i -> i.group(
                        UUIDUtil.CODEC.fieldOf("targetKingdomId")
                                .forGetter(WarDeclaration::targetKingdomId),
                        Codec.STRING.optionalFieldOf("casusBelli", "")
                                .forGetter(WarDeclaration::casusBelli)
                ).apply(i, WarDeclaration::new));
    }

    /**
     * BREAK_TREATY — player asks the ruler to formally break the
     * named treaty. Approve → Kingdom.breakTreaty mirrored on every
     * party. Ruler eats the legitimacy hit.
     */
    record BreakTreaty(UUID treatyId, String reason)
            implements PetitionPayload {
        @Override public PetitionKind kind() { return PetitionKind.BREAK_TREATY; }

        public static final MapCodec<BreakTreaty> MAP_CODEC =
                RecordCodecBuilder.mapCodec(i -> i.group(
                        UUIDUtil.CODEC.fieldOf("treatyId").forGetter(BreakTreaty::treatyId),
                        Codec.STRING.optionalFieldOf("reason", "")
                                .forGetter(BreakTreaty::reason)
                ).apply(i, BreakTreaty::new));
    }
}
