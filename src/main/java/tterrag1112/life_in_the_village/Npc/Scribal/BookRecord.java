package tterrag1112.life_in_the_village.Npc.Scribal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Catalogue entry describing one book held in (or known to) a
 * {@link LibraryCatalogue}. Spec line 184.
 *
 * <p>Phase 2 ships placeholder data shape — the physical-item wrapper
 * is doc 18's territory. The same record is reused there; only the
 * delivery format changes.</p>
 */
public record BookRecord(
        UUID bookId,
        String title,
        String author,
        Optional<UUID> authorNpcId,
        List<String> topicsCovered,
        Optional<Skill> skillBuff,
        int pageCount,
        long acquiredTick,
        int copyCount
) {
    public BookRecord {
        if (bookId == null) bookId = UUID.randomUUID();
        if (title == null) title = "Untitled";
        if (author == null) author = "Anonymous";
        if (authorNpcId == null) authorNpcId = Optional.empty();
        topicsCovered = topicsCovered == null ? List.of() : List.copyOf(topicsCovered);
        if (skillBuff == null) skillBuff = Optional.empty();
        if (pageCount < 1) pageCount = 1;
        if (copyCount < 1) copyCount = 1;
    }

    public BookRecord withCopyCount(int newCount) {
        return new BookRecord(bookId, title, author, authorNpcId,
                topicsCovered, skillBuff, pageCount, acquiredTick,
                Math.max(0, newCount));
    }

    public static final Codec<BookRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("bookId").forGetter(BookRecord::bookId),
            Codec.STRING.fieldOf("title").forGetter(BookRecord::title),
            Codec.STRING.optionalFieldOf("author", "Anonymous").forGetter(BookRecord::author),
            UUIDUtil.CODEC.optionalFieldOf("authorNpcId").forGetter(BookRecord::authorNpcId),
            Codec.STRING.listOf().optionalFieldOf("topics", List.of())
                    .forGetter(BookRecord::topicsCovered),
            Skill.CODEC.optionalFieldOf("skillBuff").forGetter(BookRecord::skillBuff),
            Codec.INT.optionalFieldOf("pageCount", 1).forGetter(BookRecord::pageCount),
            Codec.LONG.optionalFieldOf("acquiredTick", 0L).forGetter(BookRecord::acquiredTick),
            Codec.INT.optionalFieldOf("copyCount", 1).forGetter(BookRecord::copyCount)
    ).apply(i, BookRecord::new));
}
