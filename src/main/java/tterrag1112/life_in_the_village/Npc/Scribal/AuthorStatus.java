package tterrag1112.life_in_the_village.Npc.Scribal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Per-NPC emergent attribute granted on first publication. Spec
 * line 65. Tracks bookIds the NPC has authored and a 0..100 prestige
 * score that grows with each book; ≥40 unlocks the "renowned author"
 * flag surfaced in the profile (Phase 4 kingdom history pulls from
 * here).
 */
public final class AuthorStatus {

    public static final int RENOWNED_THRESHOLD = 40;
    public static final int MAX_PRESTIGE = 100;
    /** Prestige award for each new book published. */
    public static final int PRESTIGE_PER_BOOK = 5;
    /** Bonus when a book covers a high-value topic (≥3 topics). */
    public static final int PRESTIGE_NOTABLE_BONUS = 5;

    private static final String SAVE_KEY = "npcAuthor";

    private final List<UUID> publishedBookIds = new ArrayList<>();
    private int prestige = 0;

    public AuthorStatus() {}

    public boolean isAuthor()        { return !publishedBookIds.isEmpty(); }
    public boolean isRenowned()      { return prestige >= RENOWNED_THRESHOLD; }
    public int    prestige()         { return prestige; }
    public int    publishedCount()   { return publishedBookIds.size(); }
    public List<UUID> publishedBookIds() {
        return Collections.unmodifiableList(publishedBookIds);
    }

    /**
     * Records a newly-published book and bumps prestige. Notable books
     * (≥3 topics covered) earn the additional bonus per spec line 271.
     */
    public void publish(UUID bookId, int topicCount) {
        if (bookId == null) return;
        if (publishedBookIds.contains(bookId)) return;
        publishedBookIds.add(bookId);
        int award = PRESTIGE_PER_BOOK + (topicCount >= 3 ? PRESTIGE_NOTABLE_BONUS : 0);
        prestige = Math.min(MAX_PRESTIGE, prestige + award);
    }

    public void save(ValueOutput output) {
        if (publishedBookIds.isEmpty() && prestige == 0) return;
        output.store(SAVE_KEY, CODEC, this);
    }

    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        AuthorStatus loaded = read.get();
        publishedBookIds.clear();
        publishedBookIds.addAll(loaded.publishedBookIds);
        prestige = loaded.prestige;
        return true;
    }

    public static final Codec<AuthorStatus> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.listOf().optionalFieldOf("books", List.of())
                    .forGetter(s -> List.copyOf(s.publishedBookIds)),
            Codec.INT.optionalFieldOf("prestige", 0).forGetter(s -> s.prestige)
    ).apply(i, AuthorStatus::fromCodec));

    public static AuthorStatus fromCodec(List<UUID> books, int prestige) {
        AuthorStatus s = new AuthorStatus();
        s.publishedBookIds.addAll(books);
        s.prestige = Math.max(0, Math.min(MAX_PRESTIGE, prestige));
        return s;
    }
}
