package tterrag1112.life_in_the_village.Npc.Crime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * One piece of evidence on a {@link Trial}. Spec line 80.
 *
 * @param type        what kind of evidence this is
 * @param description short human-readable label (e.g. "stolen iron axe in
 *                    accused's inventory")
 * @param providedBy  optional UUID of the witness / investigator who
 *                    produced it; empty for circumstantial / forensic
 * @param weight      0..1 contribution to the conviction sum (spec line
 *                    188); thresholds in {@link CrimeSeverity#convictionThreshold}
 */
public record TrialEvidence(EvidenceType type, String description,
                            Optional<UUID> providedBy, float weight) {
    public TrialEvidence {
        if (type == null) throw new IllegalArgumentException("type required");
        if (description == null) description = "";
        if (providedBy == null) providedBy = Optional.empty();
        if (weight < 0f) weight = 0f;
        if (weight > 1f) weight = 1f;
    }

    public static final Codec<TrialEvidence> CODEC = RecordCodecBuilder.create(i -> i.group(
            EvidenceType.CODEC.fieldOf("type").forGetter(TrialEvidence::type),
            Codec.STRING.optionalFieldOf("description", "").forGetter(TrialEvidence::description),
            UUIDUtil.CODEC.optionalFieldOf("providedBy").forGetter(TrialEvidence::providedBy),
            Codec.FLOAT.optionalFieldOf("weight", 0.5f).forGetter(TrialEvidence::weight)
    ).apply(i, TrialEvidence::new));
}
