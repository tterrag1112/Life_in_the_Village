package tterrag1112.life_in_the_village.Npc.Scribal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * One pending or executed scribal order. Spec line 117.
 *
 * <p>{@code targetId} is optional — the recipient for a LETTER, the
 * apprentice for a CONTRACT, empty for a BOOK_COPY or DECREE.</p>
 */
public record ScribeCommission(
        UUID commissionId,
        UUID clientId,
        boolean clientIsPlayer,
        ScribeProductType product,
        String content,
        Optional<UUID> targetId,
        long requestedTick,
        long dueTick,
        long bronzeFee,
        CommissionStatus status,
        int priority
) {
    public ScribeCommission {
        if (commissionId == null) commissionId = UUID.randomUUID();
        if (clientId == null) clientId = new UUID(0L, 0L);
        if (product == null) product = ScribeProductType.LETTER;
        if (content == null) content = "";
        if (targetId == null) targetId = Optional.empty();
        if (status == null) status = CommissionStatus.PENDING;
    }

    public ScribeCommission withStatus(CommissionStatus newStatus) {
        return new ScribeCommission(commissionId, clientId, clientIsPlayer,
                product, content, targetId,
                requestedTick, dueTick, bronzeFee, newStatus, priority);
    }

    /**
     * Spec line 140 base fees in bronze. Length-based modifiers handled
     * by the caller via the {@code content.length()} check before
     * passing the bronzeFee into the commission.
     */
    public static long feeFor(ScribeProductType product, int contentChars) {
        return switch (product) {
            case LETTER -> contentChars > 200 ? 15L + (contentChars - 200L) / 50L * 5L : 5L;
            case CONTRACT -> 25L;
            case BOOK_COPY -> 100L + Math.max(0, contentChars - 500) / 200L * 20L;
            case DECREE -> 40L;
        };
    }

    public static final Codec<ScribeCommission> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("commissionId").forGetter(ScribeCommission::commissionId),
            UUIDUtil.CODEC.fieldOf("clientId").forGetter(ScribeCommission::clientId),
            Codec.BOOL.optionalFieldOf("clientIsPlayer", false)
                    .forGetter(ScribeCommission::clientIsPlayer),
            ScribeProductType.CODEC.fieldOf("product").forGetter(ScribeCommission::product),
            Codec.STRING.optionalFieldOf("content", "").forGetter(ScribeCommission::content),
            UUIDUtil.CODEC.optionalFieldOf("targetId").forGetter(ScribeCommission::targetId),
            Codec.LONG.fieldOf("requestedTick").forGetter(ScribeCommission::requestedTick),
            Codec.LONG.optionalFieldOf("dueTick", 0L).forGetter(ScribeCommission::dueTick),
            Codec.LONG.fieldOf("bronzeFee").forGetter(ScribeCommission::bronzeFee),
            CommissionStatus.CODEC.optionalFieldOf("status", CommissionStatus.PENDING)
                    .forGetter(ScribeCommission::status),
            Codec.INT.optionalFieldOf("priority", 0).forGetter(ScribeCommission::priority)
    ).apply(i, ScribeCommission::new));
}
