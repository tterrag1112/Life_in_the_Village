package tterrag1112.life_in_the_village.Kingdom.Laws;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.4 — per-kingdom dynamic state for a {@link KingdomLaw}.
 * The static metadata (display name, archetype, cost, etc.) lives
 * in {@link KingdomLawRegistry}; this record carries only what
 * varies per kingdom.
 *
 * <p>One instance per (kingdom, lawId) pair exists once the law
 * has been touched (drafted at minimum). Laws that haven't been
 * touched have no instance and are surfaced in the GUI as
 * "available". Repeal sets state back to {@link KingdomLawState#DRAFT}
 * (Scholar can re-edit) or removes the instance entirely depending
 * on the path; {@link tterrag1112.life_in_the_village.Kingdom
 * .Kingdom#repealLaw(String)} chooses removal.
 *
 * @param lawId            references {@link KingdomLawRegistry}.
 * @param state            DRAFT / PROPOSED / ACTIVE.
 * @param drafterUuid      Scholar NPC who drafted (or empty if
 *                         drafted by player).
 * @param proposerUuid     King / Chancellor who proposed (filled
 *                         on DRAFT → PROPOSED).
 * @param enactedTick      tick the law transitioned to ACTIVE.
 *                         Zero when not active.
 * @param scalarValue      live value for {@link ScalarLaw};
 *                         empty for other archetypes.
 * @param enumChoice       live choice id for {@link EnumLaw};
 *                         empty for other archetypes.
 * @param stateChangedTick tick the most recent state transition
 *                         happened (used by GUI tooltips like
 *                         "drafted 3 days ago").
 */
public record KingdomLawInstance(
        String lawId,
        KingdomLawState state,
        Optional<UUID> drafterUuid,
        Optional<UUID> proposerUuid,
        long enactedTick,
        Optional<Double> scalarValue,
        Optional<String> enumChoice,
        long stateChangedTick
) {

    public static final Codec<KingdomLawInstance> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("lawId").forGetter(KingdomLawInstance::lawId),
            Codec.STRING.xmap(KingdomLawState::valueOf, Enum::name)
                    .optionalFieldOf("state", KingdomLawState.DRAFT)
                    .forGetter(KingdomLawInstance::state),
            UUIDUtil.CODEC.optionalFieldOf("drafterUuid").forGetter(KingdomLawInstance::drafterUuid),
            UUIDUtil.CODEC.optionalFieldOf("proposerUuid").forGetter(KingdomLawInstance::proposerUuid),
            Codec.LONG.optionalFieldOf("enactedTick", 0L).forGetter(KingdomLawInstance::enactedTick),
            Codec.DOUBLE.optionalFieldOf("scalarValue").forGetter(KingdomLawInstance::scalarValue),
            Codec.STRING.optionalFieldOf("enumChoice").forGetter(KingdomLawInstance::enumChoice),
            Codec.LONG.optionalFieldOf("stateChangedTick", 0L).forGetter(KingdomLawInstance::stateChangedTick)
    ).apply(i, KingdomLawInstance::new));

    public boolean isActive()    { return state == KingdomLawState.ACTIVE; }
    public boolean isProposed()  { return state == KingdomLawState.PROPOSED; }
    public boolean isDraft()     { return state == KingdomLawState.DRAFT; }

    /** Convenience constructor for a brand-new draft. */
    public static KingdomLawInstance freshDraft(KingdomLaw law,
                                                 Optional<UUID> drafter,
                                                 long tick) {
        Optional<Double> scalar = (law instanceof ScalarLaw s)
                ? Optional.of(s.defaultValue())
                : Optional.empty();
        Optional<String> choice = (law instanceof EnumLaw e)
                ? Optional.of(e.defaultChoice())
                : Optional.empty();
        return new KingdomLawInstance(
                law.id(), KingdomLawState.DRAFT,
                drafter, Optional.empty(),
                0L, scalar, choice, tick);
    }

    // ── State-transition copy helpers ──────────────────────────────────────

    public KingdomLawInstance withState(KingdomLawState newState, long tick) {
        return new KingdomLawInstance(lawId, newState, drafterUuid, proposerUuid,
                newState == KingdomLawState.ACTIVE ? tick : enactedTick,
                scalarValue, enumChoice, tick);
    }

    public KingdomLawInstance withProposer(UUID proposer, long tick) {
        return new KingdomLawInstance(lawId, KingdomLawState.PROPOSED,
                drafterUuid, Optional.ofNullable(proposer),
                enactedTick, scalarValue, enumChoice, tick);
    }

    public KingdomLawInstance withScalar(double value) {
        return new KingdomLawInstance(lawId, state, drafterUuid, proposerUuid,
                enactedTick, Optional.of(value), enumChoice, stateChangedTick);
    }

    public KingdomLawInstance withChoice(String choice) {
        return new KingdomLawInstance(lawId, state, drafterUuid, proposerUuid,
                enactedTick, scalarValue, Optional.ofNullable(choice), stateChangedTick);
    }
}
