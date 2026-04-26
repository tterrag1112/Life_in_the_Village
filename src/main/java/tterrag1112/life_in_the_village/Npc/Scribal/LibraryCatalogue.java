package tterrag1112.life_in_the_village.Npc.Scribal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-LIBRARY data record: every book the library knows about, plus
 * the active loans. Spec line 178.
 *
 * <p>Persisted on
 * {@link tterrag1112.life_in_the_village.Networking.VillageSavedData}
 * keyed by library building UUID. Lendings are tracked separately
 * from books so a missing book doesn't lose its outstanding loan
 * record.</p>
 */
public final class LibraryCatalogue {

    private final UUID libraryBuildingId;
    private final Map<UUID, BookRecord> books = new LinkedHashMap<>();
    private final Map<UUID, LibraryLending> activeLoans = new LinkedHashMap<>();

    public LibraryCatalogue(UUID libraryBuildingId) {
        this.libraryBuildingId = libraryBuildingId;
    }

    public UUID libraryBuildingId() { return libraryBuildingId; }
    public Map<UUID, BookRecord> books() { return Collections.unmodifiableMap(books); }
    public Map<UUID, LibraryLending> activeLoans() { return Collections.unmodifiableMap(activeLoans); }

    public Optional<BookRecord> get(UUID bookId) {
        return Optional.ofNullable(books.get(bookId));
    }

    public List<BookRecord> all() { return List.copyOf(books.values()); }

    /** Adds a book or bumps copy count if title+author already present. */
    public BookRecord acquire(BookRecord book) {
        // Prefer keying by id; if the same id is already held, bump copies.
        BookRecord existing = books.get(book.bookId());
        if (existing != null) {
            BookRecord bumped = existing.withCopyCount(existing.copyCount() + book.copyCount());
            books.put(existing.bookId(), bumped);
            return bumped;
        }
        books.put(book.bookId(), book);
        return book;
    }

    public boolean remove(UUID bookId) { return books.remove(bookId) != null; }

    public boolean isAvailableForLending(UUID bookId) {
        BookRecord b = books.get(bookId);
        if (b == null) return false;
        long outstanding = activeLoans.values().stream()
                .filter(l -> l.bookId().equals(bookId)).count();
        return outstanding < b.copyCount();
    }

    public Optional<LibraryLending> lend(UUID bookId, UUID borrowerId,
                                         boolean borrowerIsPlayer, long currentTick) {
        if (!isAvailableForLending(bookId)) return Optional.empty();
        LibraryLending lending = new LibraryLending(bookId, borrowerId, borrowerIsPlayer, currentTick);
        // One outstanding loan per (bookId, borrower) pair — replace if re-borrow.
        UUID key = compositeLoanKey(bookId, borrowerId);
        activeLoans.put(key, lending);
        return Optional.of(lending);
    }

    public Optional<LibraryLending> findLoan(UUID bookId, UUID borrowerId) {
        return Optional.ofNullable(activeLoans.get(compositeLoanKey(bookId, borrowerId)));
    }

    /** Returns the loan that was cleared, or empty if none was open. */
    public Optional<LibraryLending> returnBook(UUID bookId, UUID borrowerId) {
        return Optional.ofNullable(activeLoans.remove(compositeLoanKey(bookId, borrowerId)));
    }

    public List<LibraryLending> overdueLoans(long currentTick) {
        return activeLoans.values().stream()
                .filter(l -> l.isOverdue(currentTick))
                .toList();
    }

    public int size()        { return books.size(); }
    public boolean isEmpty() { return books.isEmpty(); }

    /** Composite key so same book can be loaned to different borrowers serially. */
    private static UUID compositeLoanKey(UUID bookId, UUID borrowerId) {
        return new UUID(
                bookId.getMostSignificantBits() ^ borrowerId.getMostSignificantBits(),
                bookId.getLeastSignificantBits() ^ borrowerId.getLeastSignificantBits());
    }

    public static final Codec<LibraryCatalogue> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("library").forGetter(LibraryCatalogue::libraryBuildingId),
            BookRecord.CODEC.listOf().optionalFieldOf("books", List.of())
                    .forGetter(c -> List.copyOf(c.books.values())),
            LibraryLending.CODEC.listOf().optionalFieldOf("loans", List.of())
                    .forGetter(c -> List.copyOf(c.activeLoans.values()))
    ).apply(i, LibraryCatalogue::fromCodec));

    public static LibraryCatalogue fromCodec(UUID library, List<BookRecord> books,
                                             List<LibraryLending> loans) {
        LibraryCatalogue cat = new LibraryCatalogue(library);
        for (BookRecord b : books) cat.books.put(b.bookId(), b);
        for (LibraryLending l : loans) {
            cat.activeLoans.put(compositeLoanKey(l.bookId(), l.borrowerId()), l);
        }
        return cat;
    }
}
