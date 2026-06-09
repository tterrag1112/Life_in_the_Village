package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Npc.Letters.BookCategory;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity.HistoryEvent;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity.Taboo;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionIdentity.Virtue;
import tterrag1112.life_in_the_village.Npc.Scribal.BookRecord;
import tterrag1112.life_in_the_village.Npc.Scribal.ScribalItems;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Religion Deepening D3 — turns a faith's {@link ReligionIdentity} (D1) into
 * readable <b>scripture</b>: the cosmology, founding myth + key events, the deity,
 * and the virtues/taboos rendered as tenets/commandments. Mirrors
 * {@code ProceduralBookFactory} (compose a body → {@link ScribalItems#book} →
 * a readable WRITTEN_BOOK ItemStack carrying {@code ExtendedBookContent}), so a
 * player who opens a Sunstead scripture reads the Sun-Mother's lore in its own
 * words. Per-faith distinct, straight from the authored identity; page-capped.
 *
 * <p>The deity NAME stays the single source in {@link Religion#deity()} (D1
 * reconciliation); the rich layer comes from the identity. A faith without an
 * authored identity falls back to a coherent (shorter) body from its name +
 * core tenets — graceful, never empty.</p>
 */
public final class ScriptureFactory {

    private ScriptureFactory() {}

    private static final int CHARS_PER_PAGE = 256;          // matches ScribalItems
    private static final int MAX_PAGES      = 14;           // scripture cap
    private static final int MAX_CHARS      = CHARS_PER_PAGE * MAX_PAGES;

    /** A readable scripture WRITTEN_BOOK ItemStack for the faith (the high-value
     *  artifact a player can open + read), or empty for an unknown religion. */
    public static Optional<ItemStack> scriptureStack(String religionId,
                                                     Optional<UUID> copierId, long now) {
        Religion religion = ReligionRegistry.get(religionId);
        if (religion == null) return Optional.empty();
        String body = body(religionId);
        return Optional.of(ScribalItems.book(
                title(religionId), author(religion), body,
                BookCategory.RELIGIOUS, topics(religionId),
                copierId, Optional.empty(), now));
    }

    /** A catalogue {@link BookRecord} for the faith's scripture (metadata — the
     *  library catalogue stores records, the readable pages live on the stack). */
    public static Optional<BookRecord> scriptureRecord(String religionId,
                                                       Optional<UUID> copierId, long now) {
        Religion religion = ReligionRegistry.get(religionId);
        if (religion == null) return Optional.empty();
        int pages = Math.max(1, Math.min(MAX_PAGES,
                (body(religionId).length() + CHARS_PER_PAGE - 1) / CHARS_PER_PAGE));
        return Optional.of(new BookRecord(
                UUID.randomUUID(), title(religionId), author(religion),
                copierId, topics(religionId), Optional.empty(), pages, now, 1));
    }

    /** Faith-appropriate title — "The Book of <deity>" (or the faith name for a
     *  deity-less faith like the Loom). */
    public static String title(String religionId) {
        Religion r = ReligionRegistry.get(religionId);
        if (r == null) return "Scripture";
        return "The Book of " + r.deity().orElse(r.displayName());
    }

    private static String author(Religion r) {
        return "the " + r.displayName() + " tradition";
    }

    private static List<String> topics(String religionId) {
        return List.of("religion." + religionId, "scripture", "category.religious");
    }

    /** The scripture body, composed from the authored {@link ReligionIdentity}
     *  (graceful fallback to name + tenets when unauthored). Page-capped. */
    private static String body(String religionId) {
        Religion religion = ReligionRegistry.get(religionId);
        ReligionIdentity id = ReligionIdentity.get(religionId);
        StringBuilder sb = new StringBuilder();

        if (id == null) {
            // Graceful (sparse) — a short coherent scripture from the base record.
            sb.append(religion != null ? religion.displayName() : religionId).append("\n\n");
            if (religion != null) for (String t : religion.coreTenets()) sb.append(t).append("\n");
            return cap(sb.toString());
        }

        String deityName = (religion != null ? religion.deity() : java.util.Optional.<String>empty())
                .orElse(religion != null ? religion.displayName() : religionId);

        // Cosmology.
        sb.append(id.cosmology()).append("\n\n");
        // The deity.
        sb.append("Of ").append(deityName).append(" — ").append(id.deity().domain()).append(".\n");
        sb.append(id.deity().character()).append("\n");
        sb.append("It asks ").append(id.deity().demands()).append(".\n");
        sb.append("It gives ").append(id.deity().rewards()).append(".\n\n");
        // Sacred history.
        sb.append(id.history().foundingMyth()).append("\n\n");
        for (HistoryEvent e : id.history().events()) {
            sb.append(e.title()).append(": ").append(e.text()).append("\n");
        }
        sb.append("\n");
        // Virtues (what the faith holds).
        if (!id.virtues().isEmpty()) {
            sb.append("We hold:\n");
            for (Virtue v : id.virtues()) sb.append("- ").append(v.text()).append("\n");
            sb.append("\n");
        }
        // Taboos (what it forbids).
        if (!id.taboos().isEmpty()) {
            sb.append("We forbid:\n");
            for (Taboo t : id.taboos()) sb.append("- ").append(t.text()).append("\n");
            sb.append("\n");
        }
        // Practices.
        if (!id.practices().isEmpty()) {
            sb.append("We keep:\n");
            for (String p : id.practices()) sb.append("- ").append(p).append("\n");
        }
        return cap(sb.toString());
    }

    private static String cap(String body) {
        return body.length() <= MAX_CHARS ? body : body.substring(0, MAX_CHARS);
    }
}
