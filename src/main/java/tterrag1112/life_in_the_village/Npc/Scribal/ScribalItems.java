package tterrag1112.life_in_the_village.Npc.Scribal;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import tterrag1112.life_in_the_village.Components.ModDataComponents;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Npc.Letters.BookCategory;
import tterrag1112.life_in_the_village.Npc.Letters.ExtendedBookContent;
import tterrag1112.life_in_the_village.Npc.Letters.LetterContent;
import tterrag1112.life_in_the_village.Npc.Letters.LetterSpecial;
import tterrag1112.life_in_the_village.Npc.Letters.SkillBuff;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Factory for scribal output items. Phase 2 task 18 swap point —
 * letters now use the real {@code WrittenLetterItem}; books carry
 * both vanilla {@code WRITTEN_BOOK_CONTENT} (for the read screen)
 * and the mod-side {@link ExtendedBookContent} (for topics + skill
 * buff).
 */
public final class ScribalItems {

    /** Hard cap on characters per "page" for vanilla written-book pagination. */
    public static final int CHARS_PER_PAGE = 256;

    private ScribalItems() {}

    // ── Letter factories ──────────────────────────────────────────────────

    /**
     * Builds a written letter with the given body. Phase 2 task 18:
     * uses the real {@code WrittenLetterItem} backed by a
     * {@code LetterContent} data component.
     */
    public static ItemStack letter(String content, String fromName, String toName) {
        return letter(content, LetterContent.NIL_UUID, fromName,
                LetterContent.NIL_UUID, toName, 0L,
                false, Optional.empty());
    }

    public static ItemStack letter(String content,
                                   UUID authorId, String authorName,
                                   UUID recipientId, String recipientName,
                                   long writtenTick,
                                   boolean sealed,
                                   Optional<LetterSpecial> special) {
        ItemStack stack = new ItemStack(ModItems.WRITTEN_LETTER.get());
        LetterContent payload = LetterContent.compose(
                authorId, authorName,
                recipientId, recipientName,
                content, writtenTick, sealed, special);
        stack.set(ModDataComponents.LETTER_CONTENT.get(), payload);
        // Vanilla ITEM_NAME so the floating-text label reads "Letter from X to Y"
        // even before the player opens the inventory tooltip.
        stack.set(DataComponents.ITEM_NAME, Component.literal(
                "Letter — " + authorName + " → " + recipientName));
        return stack;
    }

    /** Builds a contract item — paper with a CONTRACT-tagged letter payload. */
    public static ItemStack contract(String title, String body) {
        return contract(title, body, LetterContent.NIL_UUID, "Anonymous",
                LetterContent.NIL_UUID, "the bearer", 0L);
    }

    public static ItemStack contract(String title, String body,
                                     UUID authorId, String authorName,
                                     UUID recipientId, String recipientName,
                                     long writtenTick) {
        ItemStack stack = new ItemStack(ModItems.WRITTEN_LETTER.get());
        LetterContent payload = LetterContent.compose(
                authorId, authorName,
                recipientId, recipientName,
                body, writtenTick, true, Optional.of(LetterSpecial.CONTRACT));
        stack.set(ModDataComponents.LETTER_CONTENT.get(), payload);
        stack.set(DataComponents.ITEM_NAME, Component.literal(
                "Contract — " + (title == null ? "" : title)));
        return stack;
    }

    /** Builds a decree (sealed announcement) item. */
    public static ItemStack decree(String title, String body) {
        ItemStack stack = new ItemStack(ModItems.WRITTEN_LETTER.get());
        LetterContent payload = LetterContent.compose(
                LetterContent.NIL_UUID, "the village",
                LetterContent.NIL_UUID, "the people",
                body, 0L, true, Optional.of(LetterSpecial.ANNOUNCEMENT));
        stack.set(ModDataComponents.LETTER_CONTENT.get(), payload);
        stack.set(DataComponents.ITEM_NAME, Component.literal(
                "Decree — " + (title == null ? "" : title)));
        return stack;
    }

    // ── Book factories ────────────────────────────────────────────────────

    /** Builds a written-book item with given title, author, body. */
    public static ItemStack book(String title, String author, String body) {
        return book(title, author, body, BookCategory.LITERATURE, List.of(),
                Optional.empty(), Optional.empty(), 0L);
    }

    /**
     * Full-control book builder. Both the vanilla {@code WRITTEN_BOOK_CONTENT}
     * and the mod-side {@link ExtendedBookContent} are attached so the
     * vanilla read screen still opens AND
     * {@link tterrag1112.life_in_the_village.Npc.Letters.BookEffects#onBookRead}
     * has the metadata it needs.
     */
    public static ItemStack book(String title, String author, String body,
                                 BookCategory category,
                                 List<String> topicsCovered,
                                 Optional<UUID> authorNpcId,
                                 Optional<SkillBuff> skillBuff,
                                 long authoredTick) {
        List<String> pages = paginatePlain(body);
        List<Filterable<Component>> bookPages = new ArrayList<>(pages.size());
        for (String p : pages) bookPages.add(Filterable.passThrough(Component.literal(p)));

        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(title == null ? "Untitled" : title),
                author == null ? "Anonymous" : author,
                0,
                bookPages,
                true));
        stack.set(ModDataComponents.EXTENDED_BOOK_CONTENT.get(),
                new ExtendedBookContent(
                        UUID.randomUUID(),
                        title == null ? "Untitled" : title,
                        author == null ? "Anonymous" : author,
                        authorNpcId,
                        category,
                        pages,
                        topicsCovered,
                        skillBuff,
                        Math.max(1, pages.size()),
                        authoredTick));
        return stack;
    }

    /** Builds a faithful copy of an existing book record's content. */
    public static ItemStack bookCopy(BookRecord source, String body) {
        return book(
                source.title() + " (copy)",
                source.author(),
                body,
                BookCategory.LITERATURE,
                source.topicsCovered(),
                source.authorNpcId(),
                source.skillBuff().map(s -> new SkillBuff(s, 0, 1f, 0)),
                source.acquiredTick());
    }

    /** Splits a body string into vanilla written-book pages. */
    public static List<String> paginatePlain(String body) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isEmpty()) {
            out.add("");
            return out;
        }
        int idx = 0;
        while (idx < body.length()) {
            int end = Math.min(idx + CHARS_PER_PAGE, body.length());
            out.add(body.substring(idx, end));
            idx = end;
        }
        return out;
    }

    /** Back-compat for callers that need {@code Filterable<Component>} pages. */
    public static List<Filterable<Component>> paginate(String body) {
        List<String> raw = paginatePlain(body);
        List<Filterable<Component>> out = new ArrayList<>(raw.size());
        for (String p : raw) out.add(Filterable.passThrough(Component.literal(p)));
        return out;
    }
}
