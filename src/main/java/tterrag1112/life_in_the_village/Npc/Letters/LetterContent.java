package tterrag1112.life_in_the_village.Npc.Letters;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data-component payload for a {@link tterrag1112.life_in_the_village.Items.WrittenLetterItem}.
 * Spec line 29.
 *
 * <p>{@code sealed} flips to {@code false} the first time the
 * letter is opened by anyone other than the recipient — Phase 3
 * crime detection reads the broken-seal state. Spec line 184.</p>
 *
 * <p>Persisted as a vanilla {@code DataComponentType} (registered in
 * {@code ModDataComponents}). Both a JSON {@link Codec} and a
 * {@link StreamCodec} are exposed so the component round-trips
 * through save and network paths.</p>
 */
public record LetterContent(
        UUID letterId,
        UUID authorId,
        String authorName,
        UUID recipientId,
        String recipientName,
        long writtenTick,
        List<String> pages,
        boolean sealed,
        boolean sealBroken,
        UUID sealBrokenBy,
        Optional<LetterSpecial> special
) {

    /** Sentinel UUID for "no opener". */
    public static final UUID NIL_UUID = new UUID(0L, 0L);

    public LetterContent {
        if (letterId == null) letterId = UUID.randomUUID();
        if (authorId == null) authorId = NIL_UUID;
        if (authorName == null) authorName = "Anonymous";
        if (recipientId == null) recipientId = NIL_UUID;
        if (recipientName == null) recipientName = "the bearer";
        pages = pages == null ? List.of() : List.copyOf(pages);
        if (sealBrokenBy == null) sealBrokenBy = NIL_UUID;
        if (special == null) special = Optional.empty();
    }

    /** Convenience constructor for ScribalItems use. */
    public static LetterContent compose(UUID authorId, String authorName,
                                        UUID recipientId, String recipientName,
                                        String body, long tick,
                                        boolean sealed,
                                        Optional<LetterSpecial> special) {
        return new LetterContent(
                UUID.randomUUID(),
                authorId, authorName,
                recipientId, recipientName,
                tick,
                List.of(body == null ? "" : body),
                sealed, false, NIL_UUID,
                special);
    }

    /** Returns a copy with the seal flipped to broken by {@code opener}. */
    public LetterContent withBrokenSeal(UUID opener) {
        if (!sealed || sealBroken) return this;
        return new LetterContent(letterId, authorId, authorName,
                recipientId, recipientName, writtenTick,
                pages, sealed, true,
                opener == null ? NIL_UUID : opener,
                special);
    }

    public boolean isAddressedTo(UUID candidate) {
        return candidate != null && candidate.equals(recipientId);
    }

    public boolean isUnauthorisedReader(UUID candidate) {
        return sealed && !isAddressedTo(candidate);
    }

    public String fullText() {
        StringBuilder sb = new StringBuilder();
        for (String p : pages) sb.append(p).append('\n');
        return sb.toString().stripTrailing();
    }

    // ── Codecs ─────────────────────────────────────────────────────────────

    public static final Codec<LetterContent> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("letterId").forGetter(LetterContent::letterId),
            UUIDUtil.CODEC.fieldOf("authorId").forGetter(LetterContent::authorId),
            Codec.STRING.optionalFieldOf("authorName", "Anonymous")
                    .forGetter(LetterContent::authorName),
            UUIDUtil.CODEC.fieldOf("recipientId").forGetter(LetterContent::recipientId),
            Codec.STRING.optionalFieldOf("recipientName", "the bearer")
                    .forGetter(LetterContent::recipientName),
            Codec.LONG.fieldOf("writtenTick").forGetter(LetterContent::writtenTick),
            Codec.STRING.listOf().optionalFieldOf("pages", List.of())
                    .forGetter(LetterContent::pages),
            Codec.BOOL.optionalFieldOf("sealed", false).forGetter(LetterContent::sealed),
            Codec.BOOL.optionalFieldOf("sealBroken", false).forGetter(LetterContent::sealBroken),
            UUIDUtil.CODEC.optionalFieldOf("sealBrokenBy", NIL_UUID)
                    .forGetter(LetterContent::sealBrokenBy),
            LetterSpecial.CODEC.optionalFieldOf("special").forGetter(LetterContent::special)
    ).apply(i, LetterContent::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, LetterContent> STREAM_CODEC =
            ByteBufCodecs.fromCodecWithRegistries(CODEC);
}
