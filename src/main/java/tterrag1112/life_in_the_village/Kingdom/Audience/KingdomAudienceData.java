package tterrag1112.life_in_the_village.Kingdom.Audience;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Track D3.5D — bundled audience-loop state on
 * {@link tterrag1112.life_in_the_village.Kingdom.Kingdom.KingdomGovernanceData}.
 *
 * <p>Slice D refactor: petitions + playerStandings + playerNobles
 * collapse from three top-level KGD fields into one. This keeps
 * {@code KingdomGovernanceData} under the DFU
 * {@code RecordCodecBuilder.group} cap of 16 (it was at 16/16 at
 * end of Slice C; Slice D's other additions would have overflowed).
 *
 * <p><b>Migration note:</b> the codec group cap precludes
 * keeping the three legacy fields alongside the bundle (KGD
 * would land at 17 fields). Pre-D3.5D dev saves with the legacy
 * fields lose their audience-loop state on first load post-Slice-D.
 * Real-world impact is bounded: petitions are transient (max 7-day
 * TTL); standings recover via play; player nobility is the painful
 * one. The migration window is small since Slices A/B/C all
 * shipped within 24 hours and are still dev-state.
 */
public record KingdomAudienceData(
        List<Petition> petitions,
        List<PlayerStanding> playerStandings,
        List<PlayerNobility> playerNobles
) {

    public static final KingdomAudienceData EMPTY =
            new KingdomAudienceData(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

    public static final Codec<KingdomAudienceData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Petition.CODEC.listOf().optionalFieldOf("petitions", new ArrayList<>())
                    .forGetter(KingdomAudienceData::petitions),
            PlayerStanding.CODEC.listOf().optionalFieldOf("playerStandings", new ArrayList<>())
                    .forGetter(KingdomAudienceData::playerStandings),
            PlayerNobility.CODEC.listOf().optionalFieldOf("playerNobles", new ArrayList<>())
                    .forGetter(KingdomAudienceData::playerNobles)
    ).apply(i, KingdomAudienceData::new));

    /** Convenience: returns this when non-empty, otherwise EMPTY. */
    public static KingdomAudienceData orEmpty(KingdomAudienceData d) {
        return d != null ? d : EMPTY;
    }
}
