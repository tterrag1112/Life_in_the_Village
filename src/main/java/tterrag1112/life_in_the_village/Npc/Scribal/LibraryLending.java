package tterrag1112.life_in_the_village.Npc.Scribal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * One active book loan from a {@link LibraryCatalogue}. Spec line 211.
 * 3-day reading window with a per-day late fine.
 */
public record LibraryLending(
        UUID bookId,
        UUID borrowerId,
        boolean borrowerIsPlayer,
        long lentTick,
        long dueTick
) {
    /** Spec line 214: in-game-day reading window. */
    public static final long READING_PERIOD_DAYS = 3L;
    /** 1 bronze per day late (spec line 216). */
    public static final long LATE_FINE_PER_DAY = 1L;
    /** Larger fine for a lost book (spec line 217). */
    public static final long LOST_BOOK_FINE = 25L;

    public LibraryLending(UUID bookId, UUID borrowerId, boolean borrowerIsPlayer, long lentTick) {
        this(bookId, borrowerId, borrowerIsPlayer, lentTick,
                lentTick + READING_PERIOD_DAYS * 24000L);
    }

    public boolean isOverdue(long currentTick) {
        return currentTick > dueTick;
    }

    public long lateFineFor(long currentTick) {
        if (!isOverdue(currentTick)) return 0L;
        long daysLate = Math.max(1L, (currentTick - dueTick) / 24000L);
        return daysLate * LATE_FINE_PER_DAY;
    }

    public static final Codec<LibraryLending> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("bookId").forGetter(LibraryLending::bookId),
            UUIDUtil.CODEC.fieldOf("borrowerId").forGetter(LibraryLending::borrowerId),
            Codec.BOOL.optionalFieldOf("borrowerIsPlayer", false)
                    .forGetter(LibraryLending::borrowerIsPlayer),
            Codec.LONG.fieldOf("lentTick").forGetter(LibraryLending::lentTick),
            Codec.LONG.fieldOf("dueTick").forGetter(LibraryLending::dueTick)
    ).apply(i, LibraryLending::new));
}
