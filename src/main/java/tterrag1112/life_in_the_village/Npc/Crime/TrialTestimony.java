package tterrag1112.life_in_the_village.Npc.Crime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/** Spec line 88. Recorded testimony at a {@link Trial}. */
public record TrialTestimony(UUID witnessId, String statement,
                             float credibility, boolean corroborated) {

    public TrialTestimony {
        if (witnessId == null) throw new IllegalArgumentException("witnessId required");
        if (statement == null) statement = "";
        if (credibility < 0f) credibility = 0f;
        if (credibility > 1f) credibility = 1f;
    }

    public static final Codec<TrialTestimony> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("witnessId").forGetter(TrialTestimony::witnessId),
            Codec.STRING.optionalFieldOf("statement", "").forGetter(TrialTestimony::statement),
            Codec.FLOAT.optionalFieldOf("credibility", 0.5f).forGetter(TrialTestimony::credibility),
            Codec.BOOL.optionalFieldOf("corroborated", false).forGetter(TrialTestimony::corroborated)
    ).apply(i, TrialTestimony::new));
}
