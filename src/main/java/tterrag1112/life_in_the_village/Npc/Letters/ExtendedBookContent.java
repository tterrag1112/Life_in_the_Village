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
 * Mod-side metadata that rides alongside vanilla
 * {@code WrittenBookContent} on a {@code WRITTEN_BOOK} item. Spec
 * line 62.
 *
 * <p>The vanilla {@code WrittenBookContent} component still carries
 * the readable pages so the vanilla read screen opens normally;
 * {@link BookEffects#onBookRead} consults this extended payload for
 * the topics-covered list and optional {@link SkillBuff}. Books
 * authored before this component was added simply have no
 * extended payload — the vanilla read screen still works.</p>
 */
public record ExtendedBookContent(
        UUID bookId,
        String title,
        String author,
        Optional<UUID> authorNpcId,
        BookCategory category,
        List<String> pages,
        List<String> topicsCovered,
        Optional<SkillBuff> skillBuff,
        int pageCount,
        long authoredTick
) {
    public ExtendedBookContent {
        if (bookId == null) bookId = UUID.randomUUID();
        if (title == null) title = "Untitled";
        if (author == null) author = "Anonymous";
        if (authorNpcId == null) authorNpcId = Optional.empty();
        if (category == null) category = BookCategory.LITERATURE;
        pages = pages == null ? List.of() : List.copyOf(pages);
        topicsCovered = topicsCovered == null ? List.of() : List.copyOf(topicsCovered);
        if (skillBuff == null) skillBuff = Optional.empty();
        if (pageCount < 1) pageCount = Math.max(1, pages.size());
    }

    public static final Codec<ExtendedBookContent> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("bookId").forGetter(ExtendedBookContent::bookId),
            Codec.STRING.fieldOf("title").forGetter(ExtendedBookContent::title),
            Codec.STRING.optionalFieldOf("author", "Anonymous")
                    .forGetter(ExtendedBookContent::author),
            UUIDUtil.CODEC.optionalFieldOf("authorNpcId").forGetter(ExtendedBookContent::authorNpcId),
            BookCategory.CODEC.fieldOf("category").forGetter(ExtendedBookContent::category),
            Codec.STRING.listOf().optionalFieldOf("pages", List.of())
                    .forGetter(ExtendedBookContent::pages),
            Codec.STRING.listOf().optionalFieldOf("topics", List.of())
                    .forGetter(ExtendedBookContent::topicsCovered),
            SkillBuff.CODEC.optionalFieldOf("skillBuff").forGetter(ExtendedBookContent::skillBuff),
            Codec.INT.optionalFieldOf("pageCount", 1).forGetter(ExtendedBookContent::pageCount),
            Codec.LONG.optionalFieldOf("authoredTick", 0L).forGetter(ExtendedBookContent::authoredTick)
    ).apply(i, ExtendedBookContent::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExtendedBookContent> STREAM_CODEC =
            ByteBufCodecs.fromCodecWithRegistries(CODEC);
}
