package tterrag1112.life_in_the_village.Npc.Scribal;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2 placeholder factory for scribal output items. Wraps vanilla
 * {@code Items.WRITTEN_BOOK} (and PAPER for letters) with the spec's
 * content data component so the receiver can read the text.
 *
 * <p>Phase 2 task 18 (letters and books) will introduce a custom
 * {@code WrittenLetterItem} / {@code ExtendedBookContent} pair that
 * carries richer metadata (recipient, fidelity, knowledge topics).
 * This file is the single swap point — once doc 18 lands, the
 * factory methods here delegate to the new items and every caller
 * keeps working without churn.</p>
 */
public final class ScribalItems {

    /** Hard cap on characters per "page" for vanilla written-book pagination. */
    public static final int CHARS_PER_PAGE = 256;

    private ScribalItems() {}

    /**
     * Builds a paper-wrapped letter. Phase 2 ships as a single PAPER
     * item with the body in {@code CUSTOM_NAME}; doc 18 swaps for the
     * real item type.
     */
    public static ItemStack letter(String content, String fromName, String toName) {
        ItemStack stack = new ItemStack(Items.PAPER);
        String label = "Letter — " + fromName + " → " + toName;
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        // Phase 2 sticks the full body in CUSTOM_NAME's hover-text via
        // ITEM_NAME so saved games keep the content readable. Doc 18
        // moves this to a dedicated component.
        stack.set(DataComponents.ITEM_NAME, Component.literal(content));
        return stack;
    }

    /** Builds a written-book item with given title, author, body. */
    public static ItemStack book(String title, String author, String body) {
        List<Filterable<Component>> pages = paginate(body);
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(title == null ? "Untitled" : title),
                author == null ? "Anonymous" : author,
                0,
                pages,
                true));
        return stack;
    }

    /** Builds a faithful copy of an existing book record's content. */
    public static ItemStack bookCopy(BookRecord source, String body) {
        return book(source.title() + " (copy)", source.author(), body);
    }

    /** Builds a contract item (placeholder = paper with title). */
    public static ItemStack contract(String title, String body) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal("Contract — " + (title == null ? "" : title)));
        stack.set(DataComponents.ITEM_NAME, Component.literal(body == null ? "" : body));
        return stack;
    }

    /** Builds a decree (paper) item. */
    public static ItemStack decree(String title, String body) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal("Decree — " + (title == null ? "" : title)));
        stack.set(DataComponents.ITEM_NAME, Component.literal(body == null ? "" : body));
        return stack;
    }

    /** Splits a body string into vanilla written-book pages. */
    public static List<Filterable<Component>> paginate(String body) {
        if (body == null || body.isEmpty()) {
            return List.of(Filterable.passThrough(Component.literal("")));
        }
        List<Filterable<Component>> out = new ArrayList<>();
        int idx = 0;
        while (idx < body.length()) {
            int end = Math.min(idx + CHARS_PER_PAGE, body.length());
            out.add(Filterable.passThrough(Component.literal(body.substring(idx, end))));
            idx = end;
        }
        return out;
    }
}
